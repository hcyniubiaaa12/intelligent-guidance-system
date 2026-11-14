package com.guide.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动器：聚合全部业务模块。
 * scanBasePackages 放宽到 com.guide——各模块的 @Component/@Configuration（如 common 的
 * MybatisPlusConfig 分页插件、MybatisMetaHandler 字段填充、各 Properties 配置类）都在
 * com.guide.&lt;模块&gt; 下，默认只扫 com.guide.admin 会全部漏掉。
 */
@SpringBootApplication(scanBasePackages = "com.guide")
@MapperScan({
        "com.guide.auth.mapper",
        "com.guide.chat.mapper",
        "com.guide.kb.mapper",
        "com.guide.feedback.mapper",
        "com.guide.async.mapper",
        "com.guide.memory.mapper"
})
public class GuideApplication {

    public static void main(String[] args) {
        SpringApplication.run(GuideApplication.class, args);
    }
}
