package com.guide.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.rag.dto.RagAnswer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 「判断依据」只列**模型真正引用**的注——这条规则的两个边界都在这里钉住。
 *
 * <p>起因是 2026-09-26 的一次实测：卡片列了 5 条依据，而模型只引用了其中 2 条
 * （注1、注3），且剩下几条的标题一模一样，患者看到的是一串重复噪声。
 */
class CitedNotesTest {

    private final GuideService service = new GuideService(
            mock(com.guide.chat.mapper.GuideRecordMapper.class),
            mock(com.guide.chat.mapper.ChatSessionMapper.class),
            mock(com.guide.kb.service.DeptService.class),
            mock(com.guide.auth.service.SysConfigService.class),
            new ObjectMapper());

    @Test
    @DisplayName("去重 + 升序：模型重复引用同一条只出一行，顺序按注号")
    void dedupesAndSorts() {
        assertThat(service.citedNos(List.of(3, 1, 3, 2), 5)).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("越界的注号丢掉：模型偶尔会写「注7」而只召回了 5 条，拿它取 chunks 会越界崩")
    void dropsOutOfRange() {
        assertThat(service.citedNos(List.of(0, 1, 5, 6, -2), 5)).containsExactly(1, 5);
    }

    @Test
    @DisplayName("一条都没引用时退回全部召回：不让「判断依据」整块空掉")
    void fallsBackToAllWhenNothingCited() {
        assertThat(service.citedNos(List.of(), 3)).containsExactly(1, 2, 3);
        assertThat(service.citedNos(null, 3)).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("引用全越界时也退回全部（等同「没引用」）")
    void fallsBackWhenAllOutOfRange() {
        assertThat(service.citedNos(List.of(9, 10), 2)).containsExactly(1, 2);
    }

    @Test
    @DisplayName("正文里写了、cites 数组里没有的注号也要能查到（模型这两处会不一致）")
    void keepsProseCites() {
        // 实测过：正文写「（注3、注5）」而结构化 cites 只有 [1,3]。
        // 只按数组取，患者会看到一个正文引用了、判断依据里却不存在的注号
        RagAnswer answer = new RagAnswer(RagAnswer.Verdict.RECOMMEND,
                "符合皮肤科范围（注1），必要时升级治疗（注3、注5）。",
                List.of(), "", List.of(1, 3), 0.9, true);

        assertThat(service.citedNos(service.withProseCites(answer), 5)).containsExactly(1, 3, 5);
    }

    @Test
    @DisplayName("「关注」「备注」这类词不会被误当成注号（后面得跟数字才算）")
    void ignoresNonCiteWords() {
        RagAnswer answer = new RagAnswer(RagAnswer.Verdict.RECOMMEND,
                "建议关注皮肤变化；备注如下，无需复查。",
                List.of(), "", List.of(), 0.9, true);

        // 正文没有注号 → withProseCites 只回结构化 cites（空）→ citedNos 退回全部召回
        assertThat(service.withProseCites(answer)).isEmpty();
        assertThat(service.citedNos(service.withProseCites(answer), 3)).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("没有召回片段时是空集，不是 [1]")
    void emptyWhenNoChunks() {
        assertThat(service.citedNos(List.of(1), 0)).isEmpty();
        assertThat(service.citedNos(List.of(), 0)).isEmpty();
    }
}
