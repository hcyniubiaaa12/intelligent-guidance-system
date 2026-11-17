package com.guide.memory.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.guide.auth.service.MemorySettingService;
import com.guide.common.config.PromptProperties;
import com.guide.llm.client.ChatModel;
import com.guide.memory.MpTableInfoTestSupport;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import com.guide.memory.mapper.MemoryMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 记忆扩写：只换说法、不补事实；失败回退、开关关闭不生成、写回带值条件。
 *
 * <p>它测的是「扩写失败时召回不能变差」这条底线——所有失败路径都只 WARN，
 * 保持编辑时写入的回退值（新 mvalue）。
 */
class MemoryEnricherTest {

    private static final String USER = "u1";
    private static final String MEMORY_ID = "m1";

    private ChatModel chatModel;
    private PromptProperties prompts;
    private MemorySettingService memorySettingService;
    private MemoryMapper memoryMapper;
    private MemoryVectorIndexer vectorIndexer;
    private ThreadPoolTaskExecutor executor;
    private MemoryEnricher enricher;

    @BeforeAll
    static void initTableInfo() {
        MpTableInfoTestSupport.init(Memory.class);
    }

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        prompts = new PromptProperties();
        prompts.getMemoryEnrich().setSystem("system");
        prompts.getMemoryEnrich().setUserTemplate("键：{key}\n值：{value}\n原话：{quote}");
        memorySettingService = mock(MemorySettingService.class);
        memoryMapper = mock(MemoryMapper.class);
        vectorIndexer = mock(MemoryVectorIndexer.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        // 默认同步执行：测试里不真的开线程
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        enricher = new MemoryEnricher(chatModel, prompts, memorySettingService,
                memoryMapper, vectorIndexer, executor);
    }

    @Test
    @DisplayName("扩写成功：解析 JSON（允许代码围栏），返回去空白后的文本")
    void parsesEnrichment() {
        when(chatModel.chat(any())).thenReturn(
                "```json\n{\"retrievalText\":\"  2型糖尿病；糖尿病、血糖高  \"}\n```");

        String text = enricher.enrich("慢病", "2型糖尿病", "我有2型糖尿病");

        assertThat(text).isEqualTo("2型糖尿病；糖尿病、血糖高");
    }

    @Test
    @DisplayName("扩写为空 / 占位词 / 超长 / 模型异常：一律返回 null（调用方保持回退值）")
    void invalidEnrichmentReturnsNull() {
        when(chatModel.chat(any())).thenReturn("{\"retrievalText\":\"\"}");
        assertThat(enricher.enrich("慢病", "2型糖尿病", "我有2型糖尿病")).isNull();

        // 「无 / null / N/A」是模型没答案时的常见占位：嵌进去会让这条背景再也匹配不到任何 query
        when(chatModel.chat(any())).thenReturn("{\"retrievalText\":\"无\"}");
        assertThat(enricher.enrich("慢病", "2型糖尿病", "我有2型糖尿病")).isNull();
        when(chatModel.chat(any())).thenReturn("{\"retrievalText\":\"N/A\"}");
        assertThat(enricher.enrich("慢病", "2型糖尿病", "我有2型糖尿病")).isNull();

        when(chatModel.chat(any())).thenReturn(
                "{\"retrievalText\":\"" + "长".repeat(121) + "\"}");
        assertThat(enricher.enrich("慢病", "2型糖尿病", "我有2型糖尿病")).isNull();

        when(chatModel.chat(any())).thenThrow(new RuntimeException("超时"));
        assertThat(enricher.enrich("慢病", "2型糖尿病", "我有2型糖尿病")).isNull();
    }

    @Test
    @DisplayName("开关关闭：不提交任务、不调模型（关掉后不再为记忆做任何模型调用）")
    void disabledDoesNotEnrich() {
        when(memorySettingService.isEnabled(USER)).thenReturn(false);

        enricher.scheduleAfterEdit(USER, MEMORY_ID, "慢病", "2型糖尿病", "我有2型糖尿病");

        verifyNoInteractions(chatModel);
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("任务执行前开关已关：不调模型（排队期间关掉自动记忆也算数）")
    void runtimeDisabledDoesNotCallModel() {
        // 第一次（入队时）开着，第二次（真正执行时）已关
        when(memorySettingService.isEnabled(USER)).thenReturn(true, false);

        enricher.scheduleAfterEdit(USER, MEMORY_ID, "慢病", "2型糖尿病", "我有2型糖尿病");

        verifyNoInteractions(chatModel);
        verify(memoryMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("写回成功：条件里带新值（防止连续改口时旧任务覆盖新值），随后重算向量")
    void writesEnrichmentWithValueCondition() {
        when(memorySettingService.isEnabled(USER)).thenReturn(true);
        when(chatModel.chat(any())).thenReturn("{\"retrievalText\":\"2型糖尿病；糖尿病、血糖高\"}");
        when(memoryMapper.update(isNull(), any())).thenReturn(1);
        Memory row = memory(MEMORY_ID, "2型糖尿病");
        when(memoryMapper.selectById(MEMORY_ID)).thenReturn(row);

        enricher.scheduleAfterEdit(USER, MEMORY_ID, "慢病", "2型糖尿病", "我有2型糖尿病");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Memory>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(memoryMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("retrieval_text", "updated_at");
        // 写回必须带「值仍是任务开始时的值」条件：患者连续改口时，先启动的任务不能覆盖新值
        assertThat(captor.getValue().getSqlSegment()).contains("id", "user_id", "mvalue", "status");
        verify(vectorIndexer).index(row);
    }

    @Test
    @DisplayName("写回未命中（患者已又改了一次 / 已删除）：跳过，不重算向量")
    void skipsWhenValueChanged() {
        when(memorySettingService.isEnabled(USER)).thenReturn(true);
        when(chatModel.chat(any())).thenReturn("{\"retrievalText\":\"2型糖尿病；糖尿病\"}");
        when(memoryMapper.update(isNull(), any())).thenReturn(0);

        enricher.scheduleAfterEdit(USER, MEMORY_ID, "慢病", "2型糖尿病", "我有2型糖尿病");

        verify(memoryMapper, never()).selectById(anyString());
        verifyNoInteractions(vectorIndexer);
    }

    private Memory memory(String id, String value) {
        Memory memory = new Memory();
        memory.setId(id);
        memory.setUserId(USER);
        memory.setType(MemoryType.BACKGROUND_HEALTH);
        memory.setMkey("慢病");
        memory.setMvalue(value);
        memory.setStatus(MemoryStatus.ACTIVE);
        return memory;
    }
}
