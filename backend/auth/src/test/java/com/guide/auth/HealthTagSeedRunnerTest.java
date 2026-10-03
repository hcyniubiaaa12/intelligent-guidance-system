package com.guide.auth;

import com.guide.auth.entity.HealthTag;
import com.guide.auth.enums.HealthTagType;
import com.guide.auth.mapper.HealthTagMapper;
import com.guide.auth.seed.HealthTagSeedRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标签词表种子装载的**判存在口径**。
 *
 * <p>与 {@code SensitiveWordSeedRunnerTest} 同类：唯一键 {@code uk_ht_term_type} 认的是**物理行**，
 * 判存在必须用 {@code countIncludingDeleted}。种子跑在 {@code ApplicationRunner} 里，
 * 撞键抛异常会让 Spring Boot 直接退出（见《数据库设计.md》§0.1）。
 */
class HealthTagSeedRunnerTest {

    private final HealthTagMapper mapper = mock(HealthTagMapper.class);
    private final HealthTagSeedRunner runner = new HealthTagSeedRunner(mapper);

    @Test
    @DisplayName("词被逻辑删除过：跳过不插（物理行还占着唯一键，插就崩）")
    void skipsSoftDeletedTags() {
        when(mapper.countIncludingDeleted(anyString(), anyString())).thenReturn(1);

        runner.run(null);

        verify(mapper, never()).insert(any(HealthTag.class));
    }

    @Test
    @DisplayName("空库：45 个种子词全部入库，且默认启用（慢病 25 + 用药 12 + 过敏 8）")
    void insertsAllWhenLibraryEmpty() {
        when(mapper.countIncludingDeleted(anyString(), anyString())).thenReturn(0);

        runner.run(null);

        ArgumentCaptor<HealthTag> captor = ArgumentCaptor.forClass(HealthTag.class);
        verify(mapper, times(45)).insert(captor.capture());
        List<HealthTag> inserted = captor.getAllValues();
        assertThat(inserted).extracting(HealthTag::getTerm)
                .contains("高血压", "阿司匹林", "青霉素类");
        assertThat(inserted).allMatch(t -> t.getEnabled() == 1);
        assertThat(inserted).extracting(HealthTag::getType).contains(
                HealthTagType.CHRONIC, HealthTagType.MEDICATION, HealthTagType.ALLERGY);
    }

    @Test
    @DisplayName("部分存在：只补缺的那些，已存在的（含删过的）一个不动")
    void insertsOnlyMissing() {
        // 三个词各只出现在一个类别里（注意「阿司匹林」在用药与过敏各占一行，不能拿它当单例样本）
        Set<String> existing = Set.of("高血压", "二甲双胍", "青霉素类");
        when(mapper.countIncludingDeleted(anyString(), anyString()))
                .thenAnswer(invocation -> existing.contains(invocation.getArgument(0)) ? 1 : 0);

        runner.run(null);

        verify(mapper, times(45 - existing.size())).insert(any(HealthTag.class));
    }
}
