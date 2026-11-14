package com.guide.memory.service;

import com.guide.auth.service.MemorySettingService;
import com.guide.auth.service.SysConfigService;
import com.guide.common.model.MemoryHit;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import com.guide.memory.service.MemoryVectorStore.ScoredMemory;
import com.guide.memory.support.StickyTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 稳定背景召回：向量命中 → 回表校验（用户/状态/有效期/类型）→ 否定句拦截 → sticky 2 轮。
 *
 * <p>这里不重算余弦相似度：向量库返回分数，本类只做**取舍**。所以断言的是
 * 「哪些条进了 prompt、哪些没有、为什么」，而不是 SQL。
 */
class MemoryRecallServiceTest {

    private static final String USER = "u1";
    private static final String SESSION = "s1";
    private static final float[] QUERY_VECTOR = {0.1f, 0.2f, 0.3f};

    private MemoryMapper memoryMapper;
    private MemoryVectorStore vectorStore;
    private MemorySettingService memorySettingService;
    private SysConfigService sysConfigService;
    private StickyTracker stickyTracker;
    private MemoryRecallService service;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(MemoryMapper.class);
        vectorStore = mock(MemoryVectorStore.class);
        memorySettingService = mock(MemorySettingService.class);
        sysConfigService = mock(SysConfigService.class);
        stickyTracker = new StickyTracker();
        service = new MemoryRecallService(USER, SESSION, memoryMapper, vectorStore,
                memorySettingService, sysConfigService, stickyTracker);

        when(memorySettingService.isEnabled(USER)).thenReturn(true);
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_BACKGROUND_RECALL_TOP_K,
                SysConfigService.DEFAULT_MEMORY_BACKGROUND_RECALL_TOP_K)).thenReturn(3);
        when(sysConfigService.getDouble(SysConfigService.KEY_MEMORY_BACKGROUND_RECALL_THRESHOLD,
                SysConfigService.DEFAULT_MEMORY_BACKGROUND_RECALL_THRESHOLD)).thenReturn(0.70);
    }

    @Test
    @DisplayName("命中：命中分数与记忆字段透传，topK 取自配置")
    void hitIsReturned() {
        when(vectorStore.search(USER, QUERY_VECTOR, 3)).thenReturn(List.of(new ScoredMemory("m1", 0.92)));
        when(memoryMapper.selectBatchIds(any())).thenReturn(List.of(
                memory("m1", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病", null)));

        List<MemoryHit> hits = service.recall("最近血糖有点高", QUERY_VECTOR);

        assertThat(hits).hasSize(1);
        MemoryHit hit = hits.get(0);
        assertThat(hit.id()).isEqualTo("m1");
        assertThat(hit.type()).isEqualTo("background_health");
        assertThat(hit.key()).isEqualTo("慢病");
        assertThat(hit.value()).isEqualTo("2型糖尿病");
        assertThat(hit.score()).isEqualTo(0.92);
        verify(vectorStore).search(USER, QUERY_VECTOR, 3);
    }

    @Test
    @DisplayName("未命中：低于阈值不注入（未命中项完全不可见，不做索引兜底）")
    void belowThresholdIsInvisible() {
        when(vectorStore.search(USER, QUERY_VECTOR, 3)).thenReturn(List.of(new ScoredMemory("m1", 0.69)));
        when(memoryMapper.selectBatchIds(any())).thenReturn(List.of(
                memory("m1", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病", null)));

        assertThat(service.recall("最近血糖有点高", QUERY_VECTOR)).isEmpty();
    }

    @Test
    @DisplayName("阈值边界：恰好等于阈值算命中（>=，不是 >）")
    void thresholdIsInclusive() {
        when(vectorStore.search(USER, QUERY_VECTOR, 3)).thenReturn(List.of(new ScoredMemory("m1", 0.70)));
        when(memoryMapper.selectBatchIds(any())).thenReturn(List.of(
                memory("m1", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "2型糖尿病", null)));

        assertThat(service.recall("血糖", QUERY_VECTOR)).hasSize(1);
    }

    @Test
    @DisplayName("按相似度降序返回，相似度只用于取舍与排序（是否进 prompt 由 11 决定）")
    void sortedByScoreDesc() {
        when(vectorStore.search(USER, QUERY_VECTOR, 3)).thenReturn(List.of(
                new ScoredMemory("m1", 0.75), new ScoredMemory("m2", 0.91)));
        when(memoryMapper.selectBatchIds(any())).thenReturn(List.of(
                memory("m1", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "高血压", null),
                memory("m2", USER, MemoryType.BACKGROUND_OTHER, "生活", "长期夜班", null)));

        List<MemoryHit> hits = service.recall("最近睡不好", QUERY_VECTOR);

        assertThat(hits).extracting(MemoryHit::id).containsExactly("m2", "m1");
    }

    @Test
    @DisplayName("开关关闭：不查向量、不调 embedding、不回表")
    void disabledReturnsEmpty() {
        when(memorySettingService.isEnabled(USER)).thenReturn(false);

        assertThat(service.recall("糖尿病", QUERY_VECTOR)).isEmpty();

        verifyNoInteractions(vectorStore);
        verifyNoInteractions(memoryMapper);
    }

    @Test
    @DisplayName("没有 query 向量：直接空，不查向量库（不在这里补一次 embedding）")
    void missingQueryVectorReturnsEmpty() {
        assertThat(service.recall("糖尿病", null)).isEmpty();
        assertThat(service.recall("糖尿病", new float[0])).isEmpty();

        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("否定句拦截：当前消息说「我没有糖尿病」时不注入该背景")
    void negationSkipsHit() {
        when(vectorStore.search(USER, QUERY_VECTOR, 3)).thenReturn(List.of(new ScoredMemory("m1", 0.9)));
        when(memoryMapper.selectBatchIds(any())).thenReturn(List.of(
                memory("m1", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "糖尿病", null)));

        assertThat(service.recall("我没有糖尿病", QUERY_VECTOR)).isEmpty();
        assertThat(service.recall("不是糖尿病，是低血糖", QUERY_VECTOR)).isEmpty();
        assertThat(service.recall("我没做过糖尿病相关检查", QUERY_VECTOR)).isEmpty();
        assertThat(service.recall("我有糖尿病", QUERY_VECTOR)).hasSize(1);
    }

    @Test
    @DisplayName("sticky 2 轮：向量不再命中后，接下来两轮仍注入，第三轮消失")
    void stickyKeepsHitForTwoMoreRounds() {
        Memory row = memory("m1", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "糖尿病", null);
        when(memoryMapper.selectBatchIds(any())).thenReturn(List.of(row));
        when(vectorStore.search(USER, QUERY_VECTOR, 3))
                .thenReturn(List.of(new ScoredMemory("m1", 0.9)), List.of(), List.of(), List.of());

        assertThat(service.recall("血糖", QUERY_VECTOR)).hasSize(1); // 命中轮
        assertThat(service.recall("这个病要注意什么", QUERY_VECTOR)).hasSize(1); // R+1
        assertThat(service.recall("那饮食呢", QUERY_VECTOR)).hasSize(1);        // R+2
        assertThat(service.recall("还有别的吗", QUERY_VECTOR)).isEmpty();        // R+3
    }

    @Test
    @DisplayName("过期背景：validTo 早于今天不注入（等于今天仍有效）")
    void expiredIsNotRecalled() {
        when(vectorStore.search(USER, QUERY_VECTOR, 3)).thenReturn(List.of(
                new ScoredMemory("m1", 0.9), new ScoredMemory("m2", 0.8)));
        when(memoryMapper.selectBatchIds(any())).thenReturn(List.of(
                memory("m1", USER, MemoryType.BACKGROUND_HEALTH, "用药", "已停药",
                        LocalDate.now().minusDays(1)),
                memory("m2", USER, MemoryType.BACKGROUND_HEALTH, "用药", "今天到期",
                        LocalDate.now())));

        List<MemoryHit> hits = service.recall("我在吃什么药", QUERY_VECTOR);

        assertThat(hits).extracting(MemoryHit::id).containsExactly("m2");
    }

    @Test
    @DisplayName("用户隔离与类型闸：他人记忆、偏好、已删除、非生效都不注入")
    void onlyActiveBackgroundOfCurrentUser() {
        when(vectorStore.search(USER, QUERY_VECTOR, 3)).thenReturn(List.of(
                new ScoredMemory("m1", 0.9), new ScoredMemory("m2", 0.9),
                new ScoredMemory("m3", 0.9), new ScoredMemory("m4", 0.9)));
        Memory preference = memory("m1", USER, MemoryType.PREFERENCE, "回答方式", "讲慢一点", null);
        Memory otherUser = memory("m2", "u2", MemoryType.BACKGROUND_HEALTH, "慢病", "糖尿病", null);
        Memory deleted = memory("m3", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "糖尿病", null);
        deleted.setDeleted(1);
        Memory ok = memory("m4", USER, MemoryType.BACKGROUND_HEALTH, "慢病", "高血压", null);
        when(memoryMapper.selectBatchIds(any())).thenReturn(List.of(preference, otherUser, deleted, ok));

        List<MemoryHit> hits = service.recall("血压高", QUERY_VECTOR);

        assertThat(hits).extracting(MemoryHit::id).containsExactly("m4");
    }

    @Test
    @DisplayName("向量召回抛异常：按无命中处理，不打断本轮对话")
    void vectorFailureIsTreatedAsNoHit() {
        when(vectorStore.search(USER, QUERY_VECTOR, 3)).thenThrow(new RuntimeException("pg down"));

        assertThat(service.recall("糖尿病", QUERY_VECTOR)).isEmpty();

        verify(memoryMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("阈值与 topK 都从 sys_config 读（改配置即生效，不写死）")
    void configIsReadPerTurn() {
        when(sysConfigService.getInt(SysConfigService.KEY_MEMORY_BACKGROUND_RECALL_TOP_K,
                SysConfigService.DEFAULT_MEMORY_BACKGROUND_RECALL_TOP_K)).thenReturn(1);
        when(sysConfigService.getDouble(SysConfigService.KEY_MEMORY_BACKGROUND_RECALL_THRESHOLD,
                SysConfigService.DEFAULT_MEMORY_BACKGROUND_RECALL_THRESHOLD)).thenReturn(0.85);
        when(vectorStore.search(USER, QUERY_VECTOR, 1)).thenReturn(List.of(new ScoredMemory("m1", 0.8)));

        assertThat(service.recall("糖尿病", QUERY_VECTOR)).isEmpty();

        verify(vectorStore).search(USER, QUERY_VECTOR, 1);
        verify(sysConfigService).getDouble(eq(SysConfigService.KEY_MEMORY_BACKGROUND_RECALL_THRESHOLD),
                anyDouble());
        verify(sysConfigService).getInt(eq(SysConfigService.KEY_MEMORY_BACKGROUND_RECALL_TOP_K), anyInt());
    }

    private Memory memory(String id, String userId, MemoryType type, String key, String value,
                          LocalDate validTo) {
        Memory memory = new Memory();
        memory.setId(id);
        memory.setUserId(userId);
        memory.setType(type);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setMkey(key);
        memory.setMvalue(value);
        memory.setValidTo(validTo);
        memory.setSourceQuote("原话");
        memory.setDeleted(0);
        return memory;
    }
}
