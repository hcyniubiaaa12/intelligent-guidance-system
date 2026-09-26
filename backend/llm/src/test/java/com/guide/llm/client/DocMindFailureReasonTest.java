package com.guide.llm.client;

import com.guide.llm.config.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败原因的分类：**管理员读的是这句话，它要能指导下一步**。
 *
 * <p>起因是 2026-09-26 的一次真实失败：提交要把文件流上传给阿里云，一段网络抖动让同一份文件
 * 连着失败两次，而页面上只有一句"解析服务暂时不可用"——看不出该等一会再试、还是换个文件、
 * 还是去查密钥。
 *
 * <p>分类只认**连接层**的异常类型，其余保持笼统：宁可说得少，也不要猜错病因。
 */
class DocMindFailureReasonTest {

    private final DocMindParseModel model = new DocMindParseModel(new LlmProperties());

    @Test
    @DisplayName("连接层的异常：说清是网络问题（这些才是「等一会再试」能好的）")
    void networkFailuresSayNetwork() {
        assertThat(model.humanReasonOf(new SocketTimeoutException("read timed out"))).contains("网络");
        assertThat(model.humanReasonOf(new ConnectException("Connection refused"))).contains("网络");
        assertThat(model.humanReasonOf(new UnknownHostException("docmind-api"))).contains("网络");
    }

    @Test
    @DisplayName("病因藏在 cause 链里也一样认得出（SDK 会把底层 IOException 包一层）")
    void unwrapsCauseChain() {
        Exception wrapped = new RuntimeException("upload failed",
                new IOException("broken pipe", new SocketTimeoutException("timeout")));

        assertThat(model.humanReasonOf(wrapped)).contains("网络");
    }

    @Test
    @DisplayName("其他异常保持笼统：不猜病因，也不把上游原文糊到页面上")
    void otherFailuresStayGeneric() {
        assertThat(model.humanReasonOf(new IllegalStateException("something")))
                .isEqualTo("解析服务暂时不可用，请稍后重试");
        assertThat(model.humanReasonOf(new RuntimeException("签名校验失败")))
                .isEqualTo("解析服务暂时不可用，请稍后重试");
    }

    @Test
    @DisplayName("自己指自己为 cause 的异常不会转圈（走链路的写法要能兜住）")
    void toleratesSelfReferencingCause() {
        Exception selfCaused = new RuntimeException("自环") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(model.humanReasonOf(selfCaused)).isEqualTo("解析服务暂时不可用，请稍后重试");
    }
}
