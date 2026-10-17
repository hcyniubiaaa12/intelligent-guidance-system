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

    // ---------- 编辑 ----------

    @Test
    @DisplayName("编辑：新词面与新类型都落库，source / enabled 保持原值，白名单当场换成新词面")
    void updateChangesTermAndTypeKeepingSourceAndEnabled() {
        MedicalTerm original = term("t1", "反酸", TermType.PART, TermSource.LLM_EXTRACT, 1);
        MedicalTerm afterUpdate = term("t1", "胃食管反流", TermType.SYMPTOM, TermSource.LLM_EXTRACT, 1);
        when(medicalTermMapper.selectById("t1")).thenReturn(original);
        // 「另一行」= id 不等于目标 id 的物理行；这里没有冲突
        when(medicalTermMapper.otherRowDeleted("胃食管反流", "symptom", "t1")).thenReturn(null);
        when(medicalTermMapper.updateById(any(MedicalTerm.class))).thenReturn(1);
        when(medicalTermMapper.selectList(any())).thenReturn(List.of(original), List.of(afterUpdate));

        assertThat(service.enabledTerms()).contains("反酸");

        MedicalTerm updated = service.update("t1", "  胃食管反流  ", TermType.SYMPTOM);

        ArgumentCaptor<MedicalTerm> saved = ArgumentCaptor.forClass(MedicalTerm.class);
        verify(medicalTermMapper).updateById(saved.capture());
        assertThat(saved.getValue().getTerm()).isEqualTo("胃食管反流");
        assertThat(saved.getValue().getType()).isEqualTo(TermType.SYMPTOM);
        // 来源是审计标签、启停有独立入口：编辑一次都不许把它们洗掉
        assertThat(saved.getValue().getSource()).isEqualTo(TermSource.LLM_EXTRACT);
        assertThat(saved.getValue().getEnabled()).isEqualTo(1);
        assertThat(updated.getTerm()).isEqualTo("胃食管反流");
        // 判定用的启用词集合当场换面（不等 60s TTL）
        assertThat(service.enabledTerms()).contains("胃食管反流").doesNotContain("反酸");
    }

    @Test
    @DisplayName("编辑：撞另一条活跃行 → 「该术语已存在」，不写库")
    void updateRejectsActiveConflict() {
        when(medicalTermMapper.selectById("t1")).thenReturn(term("t1", "反酸", TermType.SYMPTOM, 1));
        when(medicalTermMapper.otherRowDeleted("胃痛", "symptom", "t1")).thenReturn(0);

        assertThatThrownBy(() -> service.update("t1", "胃痛", TermType.SYMPTOM))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("该术语已存在")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TERM_EXISTS.getCode());
        verify(medicalTermMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("编辑：撞另一条已删除行 → 文案指向「用新增恢复」（列表里看不到那一行）")
    void updateRejectsDeletedConflictPointingToCreate() {
        when(medicalTermMapper.selectById("t1")).thenReturn(term("t1", "反酸", TermType.SYMPTOM, 1));
        when(medicalTermMapper.otherRowDeleted("胃痛", "symptom", "t1")).thenReturn(1);

        assertThatThrownBy(() -> service.update("t1", "胃痛", TermType.SYMPTOM))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("曾被删除")
                .hasMessageContaining("新增")
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TERM_EXISTS.getCode());
        verify(medicalTermMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("编辑：目标行不存在（含已逻辑删除）→ 「术语不存在」，不写库")
    void updateRejectsMissingTerm() {
        when(medicalTermMapper.selectById("gone")).thenReturn(null);

        assertThatThrownBy(() -> service.update("gone", "胃痛", TermType.SYMPTOM))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TERM_NOT_FOUND.getCode());
        verify(medicalTermMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("编辑：词面校验与新增同一套（单字 / 内部空白一律参数错误），不写库")
    void updateRejectsMalformedTerm() {
        when(medicalTermMapper.selectById("t1")).thenReturn(term("t1", "反酸", TermType.SYMPTOM, 1));
        for (String bad : new String[]{"   ", "痛", "腹 痛", "腹\n痛"}) {
            assertThatThrownBy(() -> service.update("t1", bad, TermType.SYMPTOM))
                    .as("非法输入：[%s]", bad)
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getCode())
                    .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        }
        verify(medicalTermMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("编辑：并发撞唯一键兜底成「已存在」，不冒 500")
    void updateConvertsConcurrentKeyClash() {
        when(medicalTermMapper.selectById("t1")).thenReturn(term("t1", "反酸", TermType.SYMPTOM, 1));
        when(medicalTermMapper.otherRowDeleted("胃痛", "symptom", "t1")).thenReturn(null);
        when(medicalTermMapper.updateById(any(MedicalTerm.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uk_mt_term_type"));

        assertThatThrownBy(() -> service.update("t1", "胃痛", TermType.SYMPTOM))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TERM_EXISTS.getCode());
    }

    // ---------- 删除 ----------

    @Test
    @DisplayName("删除：走 deleteById（@TableLogic → UPDATE deleted=1，不物理删、不改 enabled），且当场从内存白名单里消失")
    void removeDeletesLogicallyAndTakesEffectImmediately() {
        MedicalTerm term = term("t1", "胸痛", 1);
        when(medicalTermMapper.selectById("t1")).thenReturn(term);
        // 删除前 load 命中；删除后 selectList（带 deleted=0）不再返回它
        when(medicalTermMapper.selectList(any())).thenReturn(List.of(term), List.of());

        assertThat(service.enabledTerms()).contains("胸痛");

        service.remove("t1");

        // deleteById 是 MyBatis-Plus 的逻辑删除入口：实体带 @TableLogic，它发的是 UPDATE deleted=1。
        // 若有人改成 updateById(enabled=0) 或自写物理 DELETE，这两条断言至少有一条会红
        verify(medicalTermMapper).deleteById("t1");
        verify(medicalTermMapper, never()).updateById(any());
        assertThat(service.enabledTerms()).doesNotContain("胸痛");
    }

    @Test
    @DisplayName("删除 → 同词新增：走复活分支而不是 INSERT（逻辑删除留下物理行，正是恢复路径的前半段）")
    void removeThenAddRevivesSameRow() {
        MedicalTerm term = term("t1", "胸痛", 1);
        when(medicalTermMapper.selectById("t1")).thenReturn(term);
        when(medicalTermMapper.selectList(any())).thenReturn(List.of(term), List.of());

        service.remove("t1");

        // 删除后物理行仍在，占着 uk_mt_term_type —— 新增必须命中判存在 + 复活
        when(medicalTermMapper.countIncludingDeleted("胸痛", "symptom")).thenReturn(1);
        when(medicalTermMapper.revive("胸痛", "symptom")).thenReturn(1);
        when(medicalTermMapper.selectOne(any())).thenReturn(term);
        when(medicalTermMapper.selectList(any())).thenReturn(List.of(term));

        service.add("胸痛", TermType.SYMPTOM);

        verify(medicalTermMapper).revive("胸痛", "symptom");
        verify(medicalTermMapper, never()).insert(any(MedicalTerm.class));
        assertThat(service.enabledTerms()).contains("胸痛");
    }

    @Test
    @DisplayName("删除：目标行不存在（含已逻辑删除）→ 「术语不存在」，不写库")
    void removeRejectsMissingTerm() {
        when(medicalTermMapper.selectById("gone")).thenReturn(null);

        assertThatThrownBy(() -> service.remove("gone"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.TERM_NOT_FOUND.getCode());
        verify(medicalTermMapper, never()).deleteById(any(String.class));
    }

    // ---------- 列表排序 ----------

    @Test
    @DisplayName("列表按时间倒序、id 兜底：新加的词出现在第一页，同秒插入的行也不会翻页错乱")
    void pageOrdersByCreatedAtThenIdDesc() {
        // lambda 版 Wrapper 解析列名要查 MyBatis-Plus 的实体元数据；纯单测没有 Spring 上下文，
        // 得手工注册一次（否则 getSqlSegment() 抛 "can not find lambda cache"）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), ""),
                MedicalTerm.class);

        service.page(null, null, 1, 10);

        String sql = captureWrapper().getSqlSegment().toLowerCase();
        // 顺序有讲究：时间优先、id 只负责把同一秒内的顺序定死（种子那 72 条共用一个时间戳）。
        // 反成「先按 id 排」虽然同秒也稳定，但语义就变成"谁先入库谁在下"，与"最新在前"相反。
        // 这里只断言排序键与方向，不断言列名写法——单测没有 MyBatis 的列名映射，
        // 属性名会被原样带出（createdat）；运行期由 MyBatis-Plus 映射成 created_at。
        assertThat(sql).contains("order by").contains("createdat desc").contains("id desc");
        assertThat(sql.indexOf("createdat")).isLessThan(sql.indexOf("id desc"));
        // 词面排序已废弃：按词面排，新加的词会落到中间，管理员看不见自己刚加的东西
        assertThat(sql).doesNotContain("term asc");
    }

    /** 抓取 selectPage 收到的 wrapper（只关心它带的排序条件） */
    @SuppressWarnings("unchecked")
    private com.baomidou.mybatisplus.core.conditions.Wrapper<MedicalTerm> captureWrapper() {
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<MedicalTerm>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(medicalTermMapper).selectPage(any(), captor.capture());
        return captor.getValue();
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
