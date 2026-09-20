package com.guide.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.guide.chat.port.SensitiveHitCounter;
import com.guide.feedback.entity.FilterLog;
import com.guide.feedback.enums.FilterAction;
import com.guide.feedback.mapper.FilterLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词命中查询（feedback）：实现 chat 声明的 {@link SensitiveHitCounter} 端口，并给管理端提供明细。
 *
 * <p>为什么读侧在 feedback：{@code filter_log} 是 feedback 的表（chat 只发事件、由 feedback 落库），
 * 所以「按用户 + 时间窗计数」的读写都在数据持有方，chat 通过端口拿数、不反向依赖。
 *
 * <p>口径以 {@code matched_at}（命中时间）为准，不用 {@code created_at}：旁路留痕可能延迟落库，
 * 用写入时间会把时间窗算歪。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveHitQueryService implements SensitiveHitCounter {

    private final FilterLogMapper filterLogMapper;

    @Override
    public Counts countSince(String userId, LocalDateTime since) {
        if (userId == null) {
            return Counts.zero();
        }
        return countsByUsers(List.of(userId), since).getOrDefault(userId, Counts.zero());
    }

    /**
     * 批量统计多个用户在窗口内的命中词次（管理端用户列表一页一次查询，不在循环里逐个回表）。
     * 返回里没有的用户 = 该窗口内零命中。
     */
    public Map<String, Counts> countsByUsers(Collection<String> userIds, LocalDateTime since) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<FilterLog> wrapper = new QueryWrapper<>();
        wrapper.select("user_id AS uid", "action AS act", "COUNT(*) AS cnt")
                .in("user_id", userIds)
                .ge("matched_at", since)
                .groupBy("user_id", "action");

        Map<String, Counts> result = new HashMap<>();
        for (Map<String, Object> row : filterLogMapper.selectMaps(wrapper)) {
            String userId = String.valueOf(row.get("uid"));
            FilterAction action = FilterAction.fromCode(String.valueOf(row.get("act")));
            if (action == null) {
                // 未知动作（脏数据）不计入：阈值判定宁可少算，也不该把来历不明的行当成命中
                log.warn("filter_log 出现未知动作，已跳过：{}", row.get("act"));
                continue;
            }
            long count = toLong(row.get("cnt"));
            Counts current = result.getOrDefault(userId, Counts.zero());
            result.put(userId, action == FilterAction.BLOCKED
                    ? new Counts(current.banned() + count, current.watch())
                    : new Counts(current.banned(), current.watch() + count));
        }
        return result;
    }

    /** 窗口内按「词 + 类型」分组的命中明细（管理端查看触发次数用），按次数倒序 */
    public List<WordHit> words(String userId, LocalDateTime since) {
        if (userId == null) {
            return List.of();
        }
        QueryWrapper<FilterLog> wrapper = new QueryWrapper<>();
        wrapper.select("word_id AS wid", "action AS act", "COUNT(*) AS cnt", "MAX(matched_at) AS last_at")
                .eq("user_id", userId)
                .ge("matched_at", since)
                .groupBy("word_id", "action")
                .orderByDesc("cnt");
        List<WordHit> hits = new ArrayList<>();
        for (Map<String, Object> row : filterLogMapper.selectMaps(wrapper)) {
            FilterAction action = FilterAction.fromCode(String.valueOf(row.get("act")));
            if (action == null) {
                continue;
            }
            hits.add(new WordHit(String.valueOf(row.get("wid")), action,
                    toLong(row.get("cnt")), toDateTime(row.get("last_at"))));
        }
        return hits;
    }

    /** 单个词在窗口内的命中聚合（词面由 auth 的敏感词库补齐，feedback 只管事实） */
    public record WordHit(String wordId, FilterAction action, long hitCount, LocalDateTime lastAt) {
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static LocalDateTime toDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
