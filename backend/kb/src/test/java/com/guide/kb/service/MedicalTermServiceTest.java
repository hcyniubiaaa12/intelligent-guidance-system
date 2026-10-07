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
    @DisplayName("按类型取词：只给部位词，症状词一个都不混进来")
    void enabledPartsReturnsOnlyPartTerms() {
        when(medicalTermMapper.selectList(any())).thenReturn(List.of(
                term("t1", "腹部", TermType.PART, 1),
                term("t2", "胸痛", TermType.SYMPTOM, 1),
                term("t3", "上腹", TermType.PART, 1),
                term("t4", "头痛", TermType.SYMPTOM, 1)));

        assertThat(service.enabledParts()).containsExactly("腹部", "上腹");
    }

    @Test
    @DisplayName("按类型取词：停用的部位词不返回（与防误杀白名单同一批查询，停用一处两处都掉）")
    void enabledPartsSkipsDisabled() {
        MedicalTerm on = term("t1", "腹部", TermType.PART, 1);
        MedicalTerm off = term("t2", "腰", TermType.PART, 0);
        when(medicalTermMapper.selectList(any())).thenReturn(List.of(on, off));

        // 库里 enabled=0 的行本就查不出来；这里显式演一次"查得到却不是启用"的防线
        assertThat(service.enabledParts()).containsExactly("腹部");
    }

    @Test
    @DisplayName("按类型取词：type 为空的历史行不给（分类缺失的老数据不能被当成部位）")
    void enabledPartsSkipsUntypedRows() {
        MedicalTerm untyped = term("t9", "肚子", null, 1);
        when(medicalTermMapper.selectList(any())).thenReturn(List.of(untyped, term("t1", "头部", TermType.PART, 1)));

        assertThat(service.enabledParts()).containsExactly("头部");
    }

    @Test
    @DisplayName("按类型取词：重复词按首次出现去重（图上选项重影会让人以为点错了）")
    void enabledPartsDedupes() {
        when(medicalTermMapper.selectList(any())).thenReturn(List.of(
                term("t1", "腹部", TermType.PART, 1),
                term("t2", "腹部", TermType.PART, 1)));

        assertThat(service.enabledParts()).containsExactly("腹部");
    }

    @Test
    @DisplayName("停用部位词：当场从部位词列表里消失，不等 TTL（患者端选项与管理端开关同源）")
    void partToggleTakesEffectImmediately() {
        MedicalTerm term = term("t1", "腹部", TermType.PART, 1);
        when(medicalTermMapper.selectById("t1")).thenReturn(term);
        when(medicalTermMapper.selectList(any())).thenReturn(List.of(term), List.of());

        assertThat(service.enabledParts()).containsExactly("腹部");

        service.toggle("t1");

        assertThat(service.enabledParts()).isEmpty();
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

    private MedicalTerm term(String id, String text, int enabled) {
        return term(id, text, TermType.SYMPTOM, enabled);
    }

    private MedicalTerm term(String id, String text, TermType type, int enabled) {
        MedicalTerm term = new MedicalTerm();
        term.setId(id);
        term.setTerm(text);
        term.setType(type);
        term.setSource(TermSource.MANUAL);
        term.setEnabled(enabled);
        return term;
    }
}
