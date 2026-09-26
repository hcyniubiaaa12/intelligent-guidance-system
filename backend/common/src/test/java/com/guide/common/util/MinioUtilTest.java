package com.guide.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MinIO 上传的 contentType 回落。
 *
 * <p>这条规则看着像洁癖，实际是**上传链路的硬故障点**：multipart 的 part 可以不带 Content-Type
 * （客户端按扩展名猜不出来时就省掉），而 MinIO SDK 对它做非空校验、直接抛
 * {@code IllegalArgumentException}。透传的后果是管理员上传一份正常的 pdf 得到
 * 「1000 系统内部错误」——错误信息与真实原因毫无关系，排查要从服务端日志里翻栈。
 */
class MinioUtilTest {

    @Test
    @DisplayName("contentType 为空/空白时回落 octet-stream（不能透传给 SDK）")
    void fallsBackWhenBlank() {
        assertEquals("application/octet-stream", MinioUtil.contentTypeOrDefault(null));
        assertEquals("application/octet-stream", MinioUtil.contentTypeOrDefault(""));
        assertEquals("application/octet-stream", MinioUtil.contentTypeOrDefault("   "));
    }

    @Test
    @DisplayName("有值时原样使用：回落只补空，不改写浏览器给的类型")
    void keepsProvidedType() {
        assertEquals("application/pdf", MinioUtil.contentTypeOrDefault("application/pdf"));
    }
}
