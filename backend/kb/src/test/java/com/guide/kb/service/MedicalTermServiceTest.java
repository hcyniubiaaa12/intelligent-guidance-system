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
 * 术语白名单的启停：**点一下就该生效**。
 *
 * <p>它是 chat 入口的防误杀闸门（禁止词命中时，词本身是医学术语则放行）。管理端点「停用」
 * 之后如果还要等 60s TTL，管理员会以为没生效、再点一次——所以 {@code toggle} 必须当场
 * {@code refresh()}；TTL 只是多实例部署时的兜底，不是正常路径。
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

    private MedicalTerm term(String id, String text, int enabled) {
        MedicalTerm term = new MedicalTerm();
        term.setId(id);
        term.setTerm(text);
        term.setType(TermType.SYMPTOM);
        term.setSource(TermSource.MANUAL);
        term.setEnabled(enabled);
        return term;
    }
}
