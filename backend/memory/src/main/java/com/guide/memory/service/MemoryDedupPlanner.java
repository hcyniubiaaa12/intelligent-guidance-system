package com.guide.memory.service;

import com.guide.memory.dto.MemoryExtractionResult.BackgroundDraft;
import com.guide.memory.dto.MemoryExtractionResult.DedupDraft;
import com.guide.memory.dto.MemoryExtractionResult.PreferenceDraft;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryDedupAction;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.enums.MemoryType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 判重分派的**纯函数**：把「模型给的 action / target」翻译成「改哪一行、插哪几条」。
 *
 * <p><b>target 是序号，不是 id</b>：模型看到的是按序号编号的已记住清单（偏好与背景混排，
 * 见 {@code MemoryExtractor#rememberedText}），它回的 {@code target=3} 指的是**当初送进提示词的第 3 条**。
 * 所以调用方必须把**同一份清单**（同一个顺序、同一个对象）传进来——换一份查询就等于换了一套编号，
 * 患者的一条偏好会被悄悄改成另一条。这就是本类只收 {@code remembered} 一个清单、
 * 不再按类型各查一次的原因。
 *
 * <p>四道换算，任何一道不成立都**降级为新增**（不抛异常、不丢草稿）：
 * <ol>
 *   <li>序号在清单范围内；</li>
 *   <li>指到的那一行还生效（{@link MemoryStatus#ACTIVE}）；</li>
 *   <li>指到的那一行与本条草稿**同类**——偏好说的是「怎么回答我」，背景说的是「我是谁」，
 *       两者永远不是同一件事，跨类 update 一定是模型看错了行；</li>
 *   <li>同一行在本批里只被改一次：第二条指向同一行的草稿按新增落下。</li>
 * </ol>
 *
 * <p><b>为什么降级而不是丢弃</b>：多一条患者看得见、能自己删；丢一条他永远不知道自己被漏记了
 * （spec「失败一律按不重复落库」）。
 */
public final class MemoryDedupPlanner {

    private MemoryDedupPlanner() {
    }

    /**
     * 分派一批草稿。
     *
     * @param drafts     本轮抽到、已通过校验的草稿（{@code action} 已由解析层降级过）
     * @param remembered **送进提示词的那份清单**（同一个顺序）；为空 ⇒ 全部按新增
     */
    public static <T extends DedupDraft> Plan<T> plan(List<T> drafts, List<Memory> remembered) {
        if (drafts == null || drafts.isEmpty()) {
            return new Plan<>(List.of(), List.of());
        }
        List<Memory> rows = remembered == null ? List.of() : remembered;

        List<Update<T>> updates = new ArrayList<>();
        List<T> news = new ArrayList<>();
        Set<String> updatedIds = new HashSet<>();

        for (T draft : drafts) {
            // skip = 旧值已经更好或等价：零写入。它两边都不进——尤其不能当新增落库，
            // 那等于把患者已经记对的那句话说第二遍，还把观察时间刷新到最前面。
            if (draft.action() == MemoryDedupAction.SKIP) {
                continue;
            }
            Memory target = draft.action() == MemoryDedupAction.UPDATE
                    ? resolve(draft.target(), rows) : null;
            if (target == null
                    || target.getStatus() != MemoryStatus.ACTIVE
                    || !sameKind(target.getType(), draft)
                    || !updatedIds.add(target.getId())) {
                news.add(draft);
                continue;
            }
            updates.add(new Update<>(target, draft));
        }
        return new Plan<>(updates, news);
    }

    private static Memory resolve(Integer target, List<Memory> rows) {
        if (target == null || target < 1 || target > rows.size()) {
            return null;
        }
        Memory row = rows.get(target - 1);
        // 没有 id 的行无法定位（真实数据不可能出现，mock/异常路径兜底）
        return row == null || row.getId() == null ? null : row;
    }

    /** 偏好只改偏好行、背景只改背景行：跨类合并会把「怎么回答我」和「我是谁」搅在一起 */
    private static boolean sameKind(MemoryType type, DedupDraft draft) {
        if (draft instanceof PreferenceDraft) {
            return type == MemoryType.PREFERENCE;
        }
        if (draft instanceof BackgroundDraft) {
            return type == MemoryType.BACKGROUND_HEALTH || type == MemoryType.BACKGROUND_OTHER;
        }
        return false;
    }

    /**
     * 一次分派的结果：{@code updates} 要原地改的行（按草稿顺序），{@code news} 要插入的草稿。
     *
     * <p>两个列表互斥且合起来恰好覆盖入参中**非 skip** 的部分——{@code skip} 两边都不进
     * （零写入，也不审计：值没变却刷新观察时间会让这条无理由跳到「我记住的」最前面）。
     */
    public record Plan<T>(List<Update<T>> updates, List<T> news) {
    }

    /** 一条原地更新：改哪一行（旧快照） + 用哪条草稿的新值 */
    public record Update<T>(Memory row, T draft) {
    }
}
