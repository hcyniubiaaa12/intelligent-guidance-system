package com.guide.kb.service;

import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.kb.entity.MedicalTerm;
import com.guide.kb.enums.TermSource;
import com.guide.kb.enums.TermType;
import com.guide.kb.mapper.MedicalTermMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 术语白名单的启停与按类型取词：**点一下就该生效**。
 *
 * <p>启停那三条盯的是 chat 入口的防误杀闸门（禁止词命中时，词本身是医学术语则放行）。管理端点「停用」
 * 之后如果还要等 60s TTL，管理员会以为没生效、再点一次——所以 {@code toggle} 必须当场
 * {@code refresh()}；TTL 只是多实例部署时的兜底，不是正常路径。
 *
 * <p>按类型取词那几条盯的是<b>患者端「人体图」的选项来源</b>：同一张表停下来后，图上那个词也得
 * 立刻消失。症状词混进来会让患者点到一个拼不出有效主诉的词；type 为空的历史行混进来更糟——
 * 那是一条分类缺失的老数据，图上却要把它当成部位展示。
 */
class MedicalTermServiceTest {

    private final MedicalTermMapper medicalTermMapper = mock(MedicalTermMapper.class);
    private final MedicalTermService service = new MedicalTermService(medicalTermMapper);

    @Test
    @DisplayName("停用：写库 enabled=0，且当场从内存白名单里消失（不等 TTL）")
    void toggleOffTakesEffectImmediately() {
        MedicalTerm term = term("t1", "胸痛", 1);
        when(medicalTermMapper.selectById("t1")).thenReturn(term);
        // 第一次 load 命中启用词；停用后第二次 load 为空
        when(medicalTermMapper.selectList(any())).thenReturn(List.of(term), List.of());

        assertThat(service.enabledTerms()).contains("胸痛");

        service.toggle("t1");

        ArgumentCaptor<MedicalTerm> saved = ArgumentCaptor.forClass(MedicalTerm.class);
        verify(medicalTermMapper).updateById(saved.capture());
        assertThat(saved.getValue().getEnabled()).isZero();
        assertThat(service.enabledTerms()).doesNotContain("胸痛");
    }

    @Test
    @DisplayName("启用：写库 enabled=1，且当场进入内存白名单")
    void toggleOnTakesEffectImmediately() {
        MedicalTerm term = term("t2", "反酸", 0);
        when(medicalTermMapper.selectById("t2")).thenReturn(term);
        // 启用后库里查得到它（refresh 当场重载，不等 60s TTL）
        when(medicalTermMapper.selectList(any())).thenReturn(List.of(term));

        service.toggle("t2");

        assertThat(service.enabledTerms()).contains("反酸");
    }

    @Test
    @DisplayName("术语不存在：拒绝且不写库")
    void rejectsUnknownTerm() {
        when(medicalTermMapper.selectById("nope")).thenReturn(null);

        assertThatThrownBy(() -> service.toggle("nope"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TERM_NOT_FOUND.getCode());
        verify(medicalTermMapper, never()).updateById(any());
    }

    // ---------- 新增 ----------

    @Test
    @DisplayName("新增：落库为 manual / enabled=1，且当场进入内存白名单（chat 入口门槛立即生效）")
    void addTakesEffectImmediately() {
        when(medicalTermMapper.countIncludingDeleted("肚子疼", "symptom")).thenReturn(0);
        when(medicalTermMapper.insert(any(MedicalTerm.class))).thenReturn(1);
        when(medicalTermMapper.selectList(any())).thenReturn(
                List.of(term("t1", "肚子疼", TermType.SYMPTOM, 1)));

        MedicalTerm created = service.add("  肚子疼  ", TermType.SYMPTOM);

        ArgumentCaptor<MedicalTerm> saved = ArgumentCaptor.forClass(MedicalTerm.class);
        verify(medicalTermMapper).insert(saved.capture());
        // trim 后再落库；人工新增不受 term.manual.review 约束，直接启用
        assertThat(saved.getValue().getTerm()).isEqualTo("肚子疼");
        assertThat(saved.getValue().getType()).isEqualTo(TermType.SYMPTOM);
        assertThat(saved.getValue().getSource()).isEqualTo(TermSource.MANUAL);
        assertThat(saved.getValue().getEnabled()).isEqualTo(1);
        assertThat(created.getTerm()).isEqualTo("肚子疼");
        // 不等 60s TTL
        assertThat(service.enabledTerms()).contains("肚子疼");
    }

    @Test
    @DisplayName("新增：撞活跃行报「已存在」，不写库")
    void addRejectsExistingActiveTerm() {
        when(medicalTermMapper.countIncludingDeleted("反酸", "symptom")).thenReturn(1);

        assertThatThrownBy(() -> service.add("反酸", TermType.SYMPTOM))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TERM_EXISTS.getCode());
        verify(medicalTermMapper, never()).insert(any());
    }

    @Test
    @DisplayName("新增：仅有已删除行时复活（同一物理行回正常态），且 source 保持原值")
    void addRevivesDeletedTermKeepingSource() {
        when(medicalTermMapper.countIncludingDeleted("反酸", "symptom")).thenReturn(1);
        when(medicalTermMapper.revive("反酸", "symptom")).thenReturn(1);
        when(medicalTermMapper.selectOne(any())).thenReturn(
                term("dead", "反酸", TermType.SYMPTOM, TermSource.LLM_EXTRACT, 1));
        when(medicalTermMapper.selectList(any())).thenReturn(
                List.of(term("dead", "反酸", TermType.SYMPTOM, 1)));

        service.add("反酸", TermType.SYMPTOM);

        // 复活而不是 INSERT：物理行还占着 uk_mt_term_type
        verify(medicalTermMapper).revive("反酸", "symptom");
        verify(medicalTermMapper, never()).insert(any());
        // source 是审计标签，不被一次恢复洗掉
        assertThat(service.enabledTerms()).contains("反酸");
    }

    @Test
    @DisplayName("新增：单字被拒——子串匹配会让几乎所有主诉都被判「含医学术语」")
    void addRejectsSingleCharTerm() {
        assertThatThrownBy(() -> service.add("痛", TermType.SYMPTOM))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("单字")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        verify(medicalTermMapper, never()).countIncludingDeleted(any(), any());
        verify(medicalTermMapper, never()).insert(any());
    }

    @Test
    @DisplayName("新增：纯空白 / 超长 / 内部空白与换行一律参数错误，不写库")
    void addRejectsMalformedTerm() {
        for (String bad : new String[]{"   ", "", "腹 痛", "腹\n痛", "一二三四五六七八九十一二三四五六七八九十一"}) {
            assertThatThrownBy(() -> service.add(bad, TermType.SYMPTOM))
                    .as("非法输入：[%s]", bad)
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        }
        verify(medicalTermMapper, never()).insert(any());
    }

    @Test
    @DisplayName("新增：并发撞唯一键兜底成「已存在」，不冒 500")
    void addConvertsConcurrentKeyClash() {
        when(medicalTermMapper.countIncludingDeleted("反酸", "symptom")).thenReturn(0);
        when(medicalTermMapper.revive("反酸", "symptom")).thenReturn(0);
        when(medicalTermMapper.insert(any(MedicalTerm.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_mt_term_type"));

        assertThatThrownBy(() -> service.add("反酸", TermType.SYMPTOM))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TERM_EXISTS.getCode());
    }

    private MedicalTerm term(String id, String text, int enabled) {
        return term(id, text, TermType.SYMPTOM, enabled);
    }

    private MedicalTerm term(String id, String text, TermType type, int enabled) {
        return term(id, text, type, TermSource.MANUAL, enabled);
    }

    private MedicalTerm term(String id, String text, TermType type, TermSource source, int enabled) {
        MedicalTerm term = new MedicalTerm();
        term.setId(id);
        term.setTerm(text);
        term.setType(type);
        term.setSource(source);
        term.setEnabled(enabled);
        return term;
    }
}
