package com.guide.auth.service;

import com.guide.auth.entity.SysConfig;
import com.guide.auth.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时可调配置（sys_config 字典表，管理端可改）。
 * 链路参数统一从这里读，避免魔法值散落：检索 Top-K/Top-N、追问上限、低置信度阈值等。
 * 读多写少 → 进程内缓存，管理端改参数后调用 {@link #refresh()} 失效重建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigService {

    public static final String KEY_RETRIEVE_TOP_K = "retrieve.top.k";
    public static final String KEY_RETRIEVE_TOP_N = "retrieve.top.n";
    public static final String KEY_ASK_MAX_ROUNDS = "chat.ask.max.rounds";
    public static final String KEY_LOW_CONFIDENCE = "guide.low.confidence";
    public static final String KEY_TERM_MANUAL_REVIEW = "term.manual.review";
    /** 聚合归桶相似度阈值（链路 C 后半用，键位先占，管理端已可调） */
    public static final String KEY_CLUSTER_BUCKET_THRESHOLD = "cluster.bucket.threshold";
    /** 追问升级阈值（轮次） */
    public static final String KEY_UPGRADE_ROUNDS = "guide.upgrade.rounds";

    /** 进程内缓存存活时长：管理端改参数后无需重启，最长一分钟生效 */
    private static final long CACHE_TTL_MS = 60_000L;

    private final SysConfigMapper sysConfigMapper;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public String get(String key, String defaultValue) {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.compute(key, (k, old) -> {
            if (old != null && now - old.loadedAt() <= CACHE_TTL_MS) {
                return old;
            }
            SysConfig config = sysConfigMapper.selectOne(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysConfig>lambdaQuery()
                            .eq(SysConfig::getConfigKey, k).last("LIMIT 1"));
            return new CacheEntry(config == null ? "" : config.getConfigValue(), now);
        });
        String value = entry.value();
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException e) {
            log.warn("配置 {} 不是合法整数，使用默认值 {}", key, defaultValue);
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(get(key, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException e) {
            log.warn("配置 {} 不是合法小数，使用默认值 {}", key, defaultValue);
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)).trim());
    }

    /** 管理端改参数后失效重建（不调也有 60s TTL 兜底） */
    public void refresh() {
        cache.clear();
    }

    private record CacheEntry(String value, long loadedAt) {
    }
}
