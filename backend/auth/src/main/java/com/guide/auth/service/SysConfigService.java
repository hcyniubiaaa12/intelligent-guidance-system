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

    /** 敏感词违规的统计窗口（分钟，滑动窗口） */
    public static final String KEY_SENSITIVE_WINDOW_MINUTES = "sensitive.window.minutes";
    /** 窗口内禁止词命中词次达此值 → 警告 */
    public static final String KEY_SENSITIVE_BANNED_WARN = "sensitive.banned.warn.count";
    /** 窗口内禁止词命中词次达此值 → 禁言 */
    public static final String KEY_SENSITIVE_BANNED_MUTE = "sensitive.banned.mute.count";
    /** 窗口内观察词命中词次达此值 → 警告（观察词不禁言） */
    public static final String KEY_SENSITIVE_WATCH_WARN = "sensitive.watch.warn.count";
    /** 禁言时长（分钟，到期自动解除） */
    public static final String KEY_SENSITIVE_MUTE_MINUTES = "sensitive.mute.minutes";

    /** 切分：目标切片长度（字符），递归切尽量往它靠 */
    public static final String KEY_CHUNK_TARGET_LENGTH = "chunk.target.length";
    /** 切分：单切片上限（字符），超过它必走递归切 */
    public static final String KEY_CHUNK_MAX_LENGTH = "chunk.max.length";
    /** 切分：模型切触发长度（字符），无标题的连续文本达到它才值得花一次模型调用 */
    public static final String KEY_CHUNK_MODEL_MIN_LENGTH = "chunk.model.min.length";
    /** 入库轮询间隔（秒）：定时任务扫到 running 的解析任务后，隔多久去问一次外部服务 */
    public static final String KEY_INGEST_POLL_INTERVAL_SECONDS = "ingest.poll.interval.seconds";
    /** 单次解析的总超时（分钟）：超了标 failed(retryable)，不无限等下去 */
    public static final String KEY_INGEST_PARSE_TIMEOUT_MINUTES = "ingest.parse.timeout.minutes";

    /**
     * 键位的**代码侧默认值**：库中该键缺失时回落（首次部署不灌 sys_config 也能开管理端页面）。
     * 与 SysConfigAdminService 白名单里的 ParamSpec 同源——改这里就够，别再散落第二份字面量。
     */
    public static final int DEFAULT_RETRIEVE_TOP_K = 10;
    public static final int DEFAULT_RETRIEVE_TOP_N = 5;

    /** 切分参数的代码侧默认值（与 SplitParams 的常量同值，改一处即可） */
    public static final int DEFAULT_CHUNK_TARGET_LENGTH = 400;
    public static final int DEFAULT_CHUNK_MAX_LENGTH = 800;
    public static final int DEFAULT_CHUNK_MODEL_MIN_LENGTH = 1200;
    /** 入库轮询的代码侧默认值 */
    public static final int DEFAULT_INGEST_POLL_INTERVAL_SECONDS = 10;
    public static final int DEFAULT_INGEST_PARSE_TIMEOUT_MINUTES = 30;

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
