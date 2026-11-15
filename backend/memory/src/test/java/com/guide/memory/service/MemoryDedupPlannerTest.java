package com.guide.memory.service;

import com.guide.memory.dto.MemoryExtractionResult.BackgroundDraft;
import com.guide.memory.dto.MemoryExtractionResult.PreferenceDraft;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryDedupAction;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 判重分派的纯逻辑：给定「模型给的 action/target」与「送进提示词的旧记忆清单」，决定改哪一条、插哪几条。
 *
 * <p><b>为什么单独钉</b>：{@code target} 是**送进提示词的清单里的序号**（偏好与背景混排编号），
 * 它到具体记忆行之间隔着「序号 → 位置 → 类型是否一致 → 行是否还生效」四道换算。
 * 这一段算错的表现是**改错了别人的记忆**，而患者只会看到自己的一条偏好莫名其妙变了。
 *
 * <p>零 mock：不碰模型、不碰数据库，只吃草稿与行对象。
 */
class MemoryDedupPlannerTest {

    @Test
    @DisplayName("action=new：直接进新增，不碰任何旧行")
    void newGoesToInsert() {
        PreferenceDraft draft = pref("语速", "语速要慢", new BigDecimal("0.9"),
                MemoryDedupAction.NEW, null);

        MemoryDedupPlanner.Plan<PreferenceDraft> plan =
                MemoryDedupPlanner.plan(List.of(draft), List.of(row("m1", preference("讲慢一点"))));

        assertThat(plan.updates()).isEmpty();
        assertThat(plan.news()).containsExactly(draft);
    }

    @Test
    @DisplayName("action=update + target 指到偏好行：原地改那一行（序号从 1 起）")
    void updateResolvesTarget() {
        Memory first = row("m1", preference("讲慢一点"));
        Memory second = row("m2", preference("别用专业词"));
        PreferenceDraft draft = pref("语速", "语速要慢", new BigDecimal("0.9"),
                MemoryDedupAction.UPDATE, 2);

        MemoryDedupPlanner.Plan<PreferenceDraft> plan =
                MemoryDedupPlanner.plan(List.of(draft), List.of(first, second));

        assertThat(plan.news()).isEmpty();
        assertThat(plan.updates()).hasSize(1);
        assertThat(plan.updates().get(0).row().getId()).isEqualTo("m2");
        assertThat(plan.updates().get(0).draft()).isSameAs(draft);
    }

    @Test
    @DisplayName("target 指向背景行：类型不一致 ⇒ 降级为新增（偏好是「怎么回答我」，背景是「我是谁」）")
    void targetOnBackgroundFallsBackToNew() {
        Memory background = row("m1", background("2型糖尿病"));
        PreferenceDraft draft = pref("语速", "语速要慢", new BigDecimal("0.9"),
                MemoryDedupAction.UPDATE, 1);

        MemoryDedupPlanner.Plan<PreferenceDraft> plan =
                MemoryDedupPlanner.plan(List.of(draft), List.of(background));

        assertThat(plan.updates()).isEmpty();
        assertThat(plan.news()).containsExactly(draft);
    }

    @Test
    @DisplayName("target 越界 / 为空 / 指向已失效的行：一律降级为新增，不抛异常")
    void unresolvableTargetFallsBackToNew() {
        Memory active = row("m1", preference("讲慢一点"));
        Memory expired = row("m2", preference("讲慢一点"));
        expired.setStatus(MemoryStatus.EXPIRED);
        List<Memory> remembered = List.of(active, expired);

        List<PreferenceDraft> drafts = List.of(
                pref("语速", "甲", new BigDecimal("0.9"), MemoryDedupAction.UPDATE, 5),
                pref("语速", "乙", new BigDecimal("0.9"), MemoryDedupAction.UPDATE, null),
                pref("语速", "丙", new BigDecimal("0.9"), MemoryDedupAction.UPDATE, 2));

        MemoryDedupPlanner.Plan<PreferenceDraft> plan = MemoryDedupPlanner.plan(drafts, remembered);

        assertThat(plan.updates()).isEmpty();
        assertThat(plan.news()).extracting(PreferenceDraft::value).containsExactly("甲", "乙", "丙");
    }

    @Test
    @DisplayName("action=skip：零写入——值没变就不该刷新观察时间，否则这条会无理由跳到列表最前")
    void skipWritesNothing() {
        PreferenceDraft draft = pref("语速", "讲慢一点", new BigDecimal("0.9"),
                MemoryDedupAction.SKIP, 1);

        MemoryDedupPlanner.Plan<PreferenceDraft> plan =
                MemoryDedupPlanner.plan(List.of(draft), List.of(row("m1", preference("讲慢一点"))));

        assertThat(plan.updates()).isEmpty();
        assertThat(plan.news()).isEmpty();
    }

    @Test
    @DisplayName("同批两条指向同一行：只改第一条，第二条按新增落下（宁可多一条可见可删，也不静默丢）")
    void secondUpdateOnSameRowBecomesNew() {
        Memory only = row("m1", preference("讲慢一点"));
        PreferenceDraft first = pref("语速", "甲", new BigDecimal("0.9"), MemoryDedupAction.UPDATE, 1);
        PreferenceDraft second = pref("语速", "乙", new BigDecimal("0.9"), MemoryDedupAction.UPDATE, 1);

        MemoryDedupPlanner.Plan<PreferenceDraft> plan =
                MemoryDedupPlanner.plan(List.of(first, second), List.of(only));

        assertThat(plan.updates()).hasSize(1);
        assertThat(plan.updates().get(0).draft()).isSameAs(first);
        assertThat(plan.news()).containsExactly(second);
    }

    @Test
    @DisplayName("偏好批次的背景草稿（类型不对）不会被当成偏好改：由草稿自身的类别决定，不看目标行")
    void backgroundDraftWithPreferenceTargetFallsBackToNew() {
        Memory only = row("m1", preference("讲慢一点"));
        BackgroundDraft draft = backgroundDraft("2型糖尿病，控制良好", MemoryDedupAction.UPDATE, 1);

        MemoryDedupPlanner.Plan<BackgroundDraft> plan =
                MemoryDedupPlanner.plan(List.of(draft), List.of(only));

        assertThat(plan.updates()).isEmpty();
        assertThat(plan.news()).containsExactly(draft);
    }

    @Test
    @DisplayName("旧清单为空 / 为空草稿：安全返回空计划（取不到旧记忆时退化成全新增）")
    void emptyInputs() {
        assertThat(MemoryDedupPlanner.plan(null, null).news()).isEmpty();
        assertThat(MemoryDedupPlanner.plan(List.of(), List.of()).updates()).isEmpty();
        assertThat(MemoryDedupPlanner.plan(List.of(pref("语速", "慢点", new BigDecimal("0.9"),
                MemoryDedupAction.UPDATE, 1)), List.of()).news()).hasSize(1);
    }

    private PreferenceDraft pref(String key, String value, BigDecimal confidence,
                                 MemoryDedupAction action, Integer target) {
        return new PreferenceDraft(key, value, "我希望你讲慢一点", confidence, action, target);
    }

    private BackgroundDraft backgroundDraft(String value, MemoryDedupAction action, Integer target) {
        return new BackgroundDraft("慢病", value, "health", "我有2型糖尿病，控制良好",
                new BigDecimal("0.9"), null, action, target);
    }

    private Memory preference(String value) {
        return memory(MemoryType.PREFERENCE, "回答方式", value);
    }

    private Memory background(String value) {
        return memory(MemoryType.BACKGROUND_HEALTH, "慢病", value);
    }

    private Memory memory(MemoryType type, String key, String value) {
        Memory memory = new Memory();
        memory.setId("tmp");
        memory.setUserId("u1");
        memory.setType(type);
        memory.setMkey(key);
        memory.setMvalue(value);
        memory.setStatus(MemoryStatus.ACTIVE);
        memory.setObservedAt(LocalDateTime.now());
        return memory;
    }

    private Memory row(String id, Memory memory) {
        memory.setId(id);
        return memory;
    }
}
