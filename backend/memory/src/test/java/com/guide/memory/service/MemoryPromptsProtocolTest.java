package com.guide.memory.service;

import com.guide.common.config.PromptProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code prompts.yml} 里判重协议与合并占位符的**真文件**断言（不调模型、零 mock）。
 *
 * <p>为什么不用 {@code PromptProperties.validate()} 覆盖：那只校验占位符存在。
 * 这里钉的是「提示词里真的写了 action / target 与四条判重口径」——
 * 协议写在代码里、提示词却漏写，模型不会回这两个字段，落库层就永远只走 new。
 */
class MemoryPromptsProtocolTest {

    @Test
    @DisplayName("memory-extract：user 模板有 {remembered}，system 写清 action / target 与四条判重口径")
    void extractPromptCarriesDedupProtocol() throws Exception {
        Map<String, Object> extract = section("memory-extract");
        String system = string(extract.get("system"));
        String user = string(extract.get("user-template"));

        assertThat(user).contains(PromptProperties.PLACEHOLDER_REMEMBERED)
                .contains(PromptProperties.PLACEHOLDER_DIALOGUE);
        assertThat(system).contains("[偏好]").contains("[背景]").contains("（无）");
        assertThat(system).contains("\"action\"").contains("\"target\"").contains("action=new").contains("target=null");
        assertThat(system).contains("拿不准就 new");
        assertThat(system).contains("同一件事").contains("信息量更大").contains("相反");
    }

    @Test
    @DisplayName("memory-merge：两个占位符仍在，并要求 key 取自输入、不许自己编")
    void mergePromptRequiresKeyFromInput() throws Exception {
        Map<String, Object> merge = section("memory-merge");
        String system = string(merge.get("system"));
        String user = string(merge.get("user-template"));

        assertThat(user).contains(PromptProperties.PLACEHOLDER_EXISTING)
                .contains(PromptProperties.PLACEHOLDER_INCOMING);
        assertThat(system).contains("键：值");
        assertThat(system).contains("不要自己编新键");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> section(String name) throws Exception {
        Path file = repoRoot().resolve("backend/admin/src/main/resources/prompts.yml");
        assertThat(Files.exists(file)).as("prompts.yml 不存在：%s", file).isTrue();
        Map<String, Object> yaml;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            yaml = new Yaml().load(reader);
        }
        return (Map<String, Object>) ((Map<String, Object>) yaml.get("prompts")).get(name);
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

    /** 从当前目录往上找仓库根（含 backend 与 prompts.yml 的那一层） */
    private Path repoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("backend/admin/src/main/resources/prompts.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到仓库根（从 " + Paths.get("").toAbsolutePath() + " 往上）");
    }
}
