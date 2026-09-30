package com.guide.llm.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.llm.config.LlmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * LLM 适配层内部 HTTP 工具：基于 JDK HttpClient（不引入额外 HTTP 客户端依赖）。
 * 提供「一次性 JSON 请求」与「SSE 流式逐行回调」两种形态。
 *
 * <p>三种超时都从 {@link LlmProperties.Http} 读，**不能一个都不设**：没有超时的模型调用
 * 就是一次无限挂起。2026-09-29 实测撞上一次——DeepSeek 流式调用挂住，SSE 一直开着、
 * 后端线程卡在模型调用上不返回也不打错误日志，患者端就是无限转圈。超时一律按
 * {@code LLM_CALL_FAILED}(7001) 抛出，走链路 A 既有的 error 事件路径。
 */
@Slf4j
@Component
public class HttpJson {

    /** 看门狗巡检间隔：静默超时不需要毫秒级精度，1s 足够且开销可忽略 */
    private static final long WATCHDOG_INTERVAL_MS = 1_000;

    private final LlmProperties properties;

    private final HttpClient httpClient;

    /**
     * 流式看门狗线程池。守护线程，一个线程足以（看门狗只做"超时就掐断"这一件事，
     * 真正的读取仍在线程池里的业务线程上）。
     */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "llm-stream-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final ObjectMapper objectMapper;

    public HttpJson(ObjectMapper objectMapper, LlmProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(http().getConnectTimeoutMs()))
                .build();
    }

    /** 普通 JSON 请求：非 2xx 抛 LLM_CALL_FAILED（含服务端返回片段，便于排查 Key/配额问题） */
    public JsonNode postJson(String url, Map<String, String> headers, Object body) {
        long readTimeoutMs = http().getReadTimeoutMs();
        HttpRequest request = baseBuilder(url, headers)
                .timeout(Duration.ofMillis(readTimeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new BizException(ErrorCode.LLM_CALL_FAILED,
                        "HTTP " + response.statusCode() + " " + abbreviate(response.body()));
            }
            return objectMapper.readTree(response.body());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, timeoutMessage(readTimeoutMs));
        } catch (IOException e) {
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "网络异常 " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "请求被中断");
        }
    }

    /**
     * SSE 流式请求：逐行回调响应体文本行（含空行），由调用方按 SSE 协议解析。
     *
     * <p>流式不设"整条流的死线"——生成时长由模型侧决定。但**必须**防住两种挂死：
     * <ol>
     *   <li>等响应头超时（{@code readTimeoutMs}）：连上了却迟迟不给响应</li>
     *   <li>静默超时（{@code streamIdleTimeoutMs}）：流开着但长时间一个字都不吐——就是本次要治的病</li>
     * </ol>
     * 另加一条整条流的总预算（{@code streamTotalTimeoutMs}）兜底。看门狗只负责"超时掐断"，
     * 掐断靠关闭响应体让阻塞中的 {@code readLine()} 立刻抛 IOException。
     */
    public void postStream(String url, Map<String, String> headers, Object body, Consumer<String> lineConsumer) {
        long readTimeoutMs = http().getReadTimeoutMs();
        long idleTimeoutMs = http().getStreamIdleTimeoutMs();
        long totalTimeoutMs = http().getStreamTotalTimeoutMs();

        HttpRequest request = baseBuilder(url, headers)
                .POST(HttpRequest.BodyPublishers.ofString(write(body), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<InputStream>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());

        HttpResponse<InputStream> response = awaitHeaders(future, readTimeoutMs);

        long startedAt = System.currentTimeMillis();
        AtomicLong lastActivity = new AtomicLong(startedAt);
        AtomicBoolean aborted = new AtomicBoolean(false);
        ScheduledFuture<?> watchdogTask = watchdog.scheduleWithFixedDelay(() -> {
            long now = System.currentTimeMillis();
            boolean idle = now - lastActivity.get() > idleTimeoutMs;
            boolean overBudget = now - startedAt > totalTimeoutMs;
            if (idle || overBudget) {
                aborted.set(true);
                future.cancel(true);
                closeQuietly(response.body());
            }
        }, idleTimeoutMs, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);

        try (InputStream in = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            if (response.statusCode() / 100 != 2) {
                String errorBody = reader.lines().reduce("", (a, b) -> a + b);
                throw new BizException(ErrorCode.LLM_CALL_FAILED,
                        "HTTP " + response.statusCode() + " " + abbreviate(errorBody));
            }
            String line;
            while ((line = reader.readLine()) != null) {
                lastActivity.set(System.currentTimeMillis());
                lineConsumer.accept(line);
            }
        } catch (IOException e) {
            if (aborted.get()) {
                // 看门狗掐断的：区分"静默太久"与"总预算耗尽"，两者给人的处置不一样
                boolean idleTimedOut = System.currentTimeMillis() - lastActivity.get() >= idleTimeoutMs;
                throw new BizException(ErrorCode.LLM_CALL_FAILED, idleTimedOut
                        ? "模型调用超时（" + idleTimeoutMs + "ms 内无新数据，模型可能已挂起）"
                        : "模型调用超时（超过总时长预算 " + totalTimeoutMs + "ms）");
            }
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "流式网络异常 " + e.getMessage());
        } finally {
            watchdogTask.cancel(false);
        }
    }

    public ObjectMapper mapper() {
        return objectMapper;
    }

    // ---------- 内部 ----------

    /** 等响应头：超时或连接失败都翻成 LLM_CALL_FAILED，别把裸的 JDK 异常冒到上层 */
    private HttpResponse<InputStream> awaitHeaders(CompletableFuture<HttpResponse<InputStream>> future,
                                                   long readTimeoutMs) {
        try {
            return future.get(readTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BizException(ErrorCode.LLM_CALL_FAILED, timeoutMessage(readTimeoutMs));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof java.net.http.HttpTimeoutException) {
                throw new BizException(ErrorCode.LLM_CALL_FAILED, timeoutMessage(readTimeoutMs));
            }
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "流式请求失败 " + cause.getMessage());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "流式请求被中断");
        } catch (CancellationException e) {
            // 被取消（如调用方线程池关停）也要翻成统一错误码，别让裸的运行时异常冒到上层
            throw new BizException(ErrorCode.LLM_CALL_FAILED, "流式请求已取消");
        }
    }

    private String timeoutMessage(long timeoutMs) {
        return "模型调用超时（" + timeoutMs + "ms 未返回），请稍后重试";
    }

    private void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException e) {
            log.debug("关闭已超时的流式响应体失败：{}", e.getMessage());
        }
    }

    private LlmProperties.Http http() {
        // properties.http 缺省即 new Http()（字段初始化），不会为 null；再兜一层防止配置被整体替换
        return properties.getHttp() == null ? new LlmProperties.Http() : properties.getHttp();
    }

    private HttpRequest.Builder baseBuilder(String url, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json");
        headers.forEach(builder::header);
        return builder;
    }

    private String write(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "请求体序列化失败");
        }
    }

    /** 错误信息截断，避免日志/异常消息过长 */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
