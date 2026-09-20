package com.guide.feedback;

import com.guide.chat.port.SensitiveHitCounter;
import com.guide.feedback.enums.FilterAction;
import com.guide.feedback.mapper.FilterLogMapper;
import com.guide.feedback.service.SensitiveHitQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 敏感词命中计数单测（feedback）：分组聚合、按类型分流、坏数据跳过。
 *
 * <p>这些数字直接决定用户会不会被警告/禁言，算错就是处置错（少算 = 纵容，多算 = 误伤）。
 */
class SensitiveHitQueryServiceTest {

    private FilterLogMapper filterLogMapper;
    private SensitiveHitQueryService service;

    @BeforeEach
    void setUp() {
        filterLogMapper = mock(FilterLogMapper.class);
        service = new SensitiveHitQueryService(filterLogMapper);
    }

    private static Map<String, Object> row(String userId, String action, long count) {
        Map<String, Object> row = new HashMap<>();
        row.put("uid", userId);
        row.put("act", action);
        row.put("cnt", count);
        return row;
    }

    @Test
    @DisplayName("按用户与类型分流：同一用户的 blocked/watched 分别累计")
    void countsAreSplitByAction() {
        when(filterLogMapper.selectMaps(any())).thenReturn(List.of(
                row("u1", "blocked", 3),
                row("u1", "watched", 5),
                row("u2", "watched", 2)));

        Map<String, SensitiveHitCounter.Counts> counts =
                service.countsByUsers(List.of("u1", "u2"), LocalDateTime.now().minusMinutes(60));

        assertEquals(3, counts.get("u1").banned());
        assertEquals(5, counts.get("u1").watch());
        assertEquals(0, counts.get("u2").banned());
        assertEquals(2, counts.get("u2").watch());
    }

    @Test
    @DisplayName("窗口内零命中的用户在结果里缺席，取数方按零处理（而不是抛空指针）")
    void missingUserMeansZero() {
        when(filterLogMapper.selectMaps(any())).thenReturn(List.of(row("u1", "blocked", 1)));

        Map<String, SensitiveHitCounter.Counts> counts =
                service.countsByUsers(List.of("u1", "u9"), LocalDateTime.now().minusMinutes(60));

        assertTrue(!counts.containsKey("u9"));
        assertEquals(1, service.countSince("u1", LocalDateTime.now().minusMinutes(60)).banned());
        assertEquals(0, service.countSince("u9", LocalDateTime.now().minusMinutes(60)).banned());
    }

    @Test
    @DisplayName("未知动作（脏数据）不计入，也不影响同批其他行")
    void unknownActionIsSkipped() {
        when(filterLogMapper.selectMaps(any())).thenReturn(List.of(
                row("u1", "mystery", 99),
                row("u1", "blocked", 2)));

        Map<String, SensitiveHitCounter.Counts> counts =
                service.countsByUsers(List.of("u1"), LocalDateTime.now().minusMinutes(60));

        assertEquals(2, counts.get("u1").banned());
        assertEquals(0, counts.get("u1").watch());
    }

    @Test
    @DisplayName("空用户集合与空 userId 都不查库（避免空 IN 查询与无意义回表）")
    void emptyInputsSkipQuery() {
        assertEquals(Map.of(), service.countsByUsers(List.of(), LocalDateTime.now()));
        assertEquals(SensitiveHitCounter.Counts.zero(), service.countSince(null, LocalDateTime.now()));
        verify(filterLogMapper, never()).selectMaps(any());
    }

    @Test
    @DisplayName("按词明细：带次数与最近命中时间（Timestamp 也要能转成 LocalDateTime）")
    void wordHits() {
        LocalDateTime last = LocalDateTime.now().minusMinutes(5);
        Map<String, Object> row = new HashMap<>();
        row.put("wid", "w1");
        row.put("act", "watched");
        row.put("cnt", 4L);
        row.put("last_at", Timestamp.valueOf(last));
        when(filterLogMapper.selectMaps(any())).thenReturn(List.of(row));

        List<SensitiveHitQueryService.WordHit> hits = service.words("u1", LocalDateTime.now().minusMinutes(60));

        assertEquals(1, hits.size());
        assertEquals("w1", hits.get(0).wordId());
        assertEquals(FilterAction.WATCHED, hits.get(0).action());
        assertEquals(4, hits.get(0).hitCount());
        assertEquals(last, hits.get(0).lastAt());
    }

    @Test
    @DisplayName("按词明细：未知动作的行跳过，不让一行脏数据毁掉整个明细")
    void wordHitsSkipUnknownAction() {
        Map<String, Object> row = new HashMap<>();
        row.put("wid", "w1");
        row.put("act", "mystery");
        row.put("cnt", 1L);
        row.put("last_at", null);
        when(filterLogMapper.selectMaps(any())).thenReturn(List.of(row));

        assertTrue(service.words("u1", LocalDateTime.now().minusMinutes(60)).isEmpty());
    }
}
