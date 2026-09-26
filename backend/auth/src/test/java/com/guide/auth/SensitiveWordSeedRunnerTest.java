package com.guide.auth;

import com.guide.auth.entity.SensitiveWord;
import com.guide.auth.mapper.SensitiveWordMapper;
import com.guide.auth.seed.SensitiveWordSeedRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 种子装载的**判存在口径**。
 *
 * <p>这条是应用能不能启动的问题，不是数据好不好看的问题：种子跑在 {@code ApplicationRunner} 里，
 * 抛异常会让 Spring Boot 直接退出。而唯一键 {@code uk_sw_word} 认的是**物理行**——
 * 管理端删过的词（逻辑删除）在按逻辑过滤的 `selectCount` 里查不到，INSERT 就会撞键。
 *
 * <p>2026-09-26 实测：删掉「滚」之后重启，应用起不来。
 */
class SensitiveWordSeedRunnerTest {

    private final SensitiveWordMapper mapper = mock(SensitiveWordMapper.class);
    private final SensitiveWordSeedRunner runner = new SensitiveWordSeedRunner(mapper);

    @Test
    @DisplayName("词被逻辑删除过：跳过不插（物理行还占着唯一键，插就崩）")
    void skipsSoftDeletedWords() {
        // 15 个种子词全部"物理存在"（真实场景里其中一部分是删过的）
        when(mapper.countIncludingDeleted(anyString())).thenReturn(1);

        runner.run(null);

        verify(mapper, never()).insert(any(SensitiveWord.class));
    }

    @Test
    @DisplayName("空库：15 个种子词全部入库（禁止 10 + 观察 5）")
    void insertsAllWhenLibraryEmpty() {
        when(mapper.countIncludingDeleted(anyString())).thenReturn(0);

        runner.run(null);

        ArgumentCaptor<SensitiveWord> captor = ArgumentCaptor.forClass(SensitiveWord.class);
        verify(mapper, times(15)).insert(captor.capture());
        List<SensitiveWord> inserted = captor.getAllValues();
        assertThat(inserted).extracting(SensitiveWord::getWord).contains("狗屎", "滚", "投诉");
        assertThat(inserted).allMatch(w -> w.getEnabled() == 1 && w.getHitCount() == 0);
    }

    @Test
    @DisplayName("部分存在：只补缺的那些，已存在的（含删过的）一个不动")
    void insertsOnlyMissing() {
        // 前 13 个查得到（物理存在），后 2 个查不到
        when(mapper.countIncludingDeleted(anyString())).thenReturn(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0);

        runner.run(null);

        verify(mapper, times(2)).insert(any(SensitiveWord.class));
    }
}
