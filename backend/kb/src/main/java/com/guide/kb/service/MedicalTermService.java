package com.guide.kb.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.guide.auth.service.SysConfigService;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.kb.dto.ExtractedTerm;
import com.guide.kb.entity.MedicalTerm;
import com.guide.kb.enums.TermSource;
import com.guide.kb.enums.TermType;
import com.guide.kb.mapper.MedicalTermMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 医学术语白名单（链路 A 入口防误杀 + 信息充足性规则门槛）。
 * MySQL medical_term 是**唯一生效源**：内存 Set 从这里加载，候选由入库抽取直接写本表；
 * 知识库更新（回流 / 重新入库 / 管理端增删）后调用 {@link #refresh()} 失效重建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalTermService {

    private final MedicalTermMapper medicalTermMapper;
    private final SysConfigService sysConfigService;

    /** 进程内缓存存活时长：管理端增删/停用术语后无需重启，最长一分钟生效 */
    private static final long CACHE_TTL_MS = 60_000L;

    /** 启用术语快照（带加载时间；过期或在 refresh() 时整体替换） */
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();

    /** 启用术语集合（首次访问或缓存过期时加载） */
    public Set<String> enabledTerms() {
        CacheEntry entry = cache.get();
        long now = System.currentTimeMillis();
        if (entry == null || now - entry.loadedAt() > CACHE_TTL_MS) {
            entry = new CacheEntry(load(), now);
            cache.set(entry);
        }
        return entry.terms();
    }

    /** 文本是否命中医学术语白名单（含任一词即命中；用于信息充足性规则门槛） */
    public boolean matches(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String term : enabledTerms()) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该词本身是否就是医学术语（**精确匹配**，用于敏感词防误杀）。
     * 不能用 matches 的子串判定：白名单含「心/手/头」等单字术语，子串匹配会让
     * 「黑心医院」这类含单字的禁止词永远拦不住。
     */
    public boolean isTerm(String word) {
        return word != null && enabledTerms().contains(word.trim());
    }

    /** 失效重建（管理端增删/停用、知识库更新后调用；不调也有 60s TTL 兜底） */
    public void refresh() {
        cache.set(new CacheEntry(load(), System.currentTimeMillis()));
        log.info("医学术语白名单已刷新，启用术语 {} 条", enabledTerms().size());
    }

    /**
     * 管理端分页：**含停用**（停用只是不生效，行还留着，页面要能看到并重新启用）。
     *
     * <p><b>排序 = 最新在前</b>：管理员加完一个词，它应当出现在第一页第一条——
     * 否则他得先想"这个词该排在哪"才能确认自己加成功了。
     *
     * <p><b>为什么必须有 id 兜底</b>：`created_at` 是秒级 `DATETIME`，同一秒插入的行时间戳完全相同
     * （种子那批 72 条就是同一个时间戳）。只按时间排，同秒行之间的顺序由数据库决定，
     * 翻页时可能同一条出现两次或一条都看不到。雪花 id 单调递增，`id DESC` 能把同秒行也定死。
     */
    public IPage<MedicalTerm> page(String keyword, TermSource source, Boolean enabled,
                                   long pageNum, long pageSize) {
        return medicalTermMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<MedicalTerm>lambdaQuery()
                        .like(keyword != null && !keyword.isBlank(), MedicalTerm::getTerm, keyword)
                        .eq(source != null, MedicalTerm::getSource, source == null ? null : source.getCode())
                        .eq(enabled != null, MedicalTerm::getEnabled, Boolean.TRUE.equals(enabled) ? 1 : 0)
                        .orderByDesc(MedicalTerm::getCreatedAt)
                        .orderByDesc(MedicalTerm::getId));
    }

    /**
     * 批量启用「待确认」的模型抽取术语（管理端「待确认 N · 全部启用」）。
     *
     * <p><b>只动 {@code source=llm_extract && enabled=0}</b>，其余 id 静默跳过——这是「待确认」的严格口径，
     * 不能放宽成 {@code enabled=0}：那会把管理员**故意停用**的词也算进去，一次批量操作就推翻人的决定。
     * 不做「全库启用」语义（不可撤销的大动作），由调用方把明确的 id 清单交进来。
     *
     * <p><b>refresh 只调一次</b>：一条 UPDATE 改多行，白名单是整体重建，逐条刷新只是白扫全表。
     *
     * @return **实际**启用条数（可能小于请求条数：人工词、已生效的词都会被跳过），前端拿它做 toast
     */
    public int batchEnable(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int updated = medicalTermMapper.update(null, Wrappers.<MedicalTerm>lambdaUpdate()
                .set(MedicalTerm::getEnabled, 1)
                .in(MedicalTerm::getId, ids)
                // 用编码值而不是枚举本身：Wrapper 的取值可能被序列化进缓存的 SQL 片段，
                // 编码值是稳定字符串（同样由 @EnumValue 决定落库值），这里显式写出以免依赖参数绑定的实现细节
                .eq(MedicalTerm::getSource, TermSource.LLM_EXTRACT.getCode())
                .eq(MedicalTerm::getEnabled, 0));
        if (updated > 0) {
            refresh();
        }
        log.info("批量启用待确认术语：请求 {} 条，实际启用 {} 条", ids.size(), updated);
        return updated;
    }

    /**
     * 启用/停用（管理端「确认启用」与「停用」都走它）：翻转后**立即 refresh**。
     *
     * <p>白名单是 chat 入口的防误杀闸门，管理端点一下就该生效——不能等 60s TTL。
     * TTL 是兜底（多实例部署时别的实例靠它收敛），不是这里的正常路径。
     */
    public MedicalTerm toggle(String id) {
        MedicalTerm term = medicalTermMapper.selectById(id);
        if (term == null) {
            throw new BizException(ErrorCode.TERM_NOT_FOUND);
        }
        term.setEnabled(Integer.valueOf(1).equals(term.getEnabled()) ? 0 : 1);
        medicalTermMapper.updateById(term);
        refresh();
        log.info("术语「{}」已{}", term.getTerm(), Integer.valueOf(1).equals(term.getEnabled()) ? "启用" : "停用");
        return term;
    }

    /**
     * 删除术语（管理端「删除」）：**逻辑删除，不是停用、也不是物理删**。
     *
     * <p><b>与「停用」的区别</b>：停用是 {@code enabled=0}——行还在管理端列表里、能再启用回来，
     * 语义是"这个词暂时不算数"。删除是 {@code deleted=1}——行从列表与判定里一起消失，
     * 语义是"这个词不该在白名单里"。两者都让词立刻退出入口判定，但只有删除会让它从视野里消失。
     *
     * <p><b>为什么走 {@code deleteById}</b>：{@code @TableLogic} 挂在这个入口上（{@code BaseEntity}），
     * 它发的是 {@code UPDATE medical_term SET deleted=1}。因此唯一键 {@code uk_mt_term_type} 仍被占着，
     * 管理员用同一个词重新新增时会命中 {@link #add} 的复活分支——这就是本工单的恢复路径，
     * 也是为什么不必做回收站 UI（见《数据库设计.md》§0.1）。
     *
     * <p><b>为什么不校验历史记录引用</b>：术语表是**当前生效源**，不是历史快照。
     * 历史导诊记录读的是自己的证据快照，删掉一个词不会让任何一条旧记录变样。
     *
     * <p><b>为什么当场 refresh</b>：与启停同理——白名单是 chat 入口的防误杀闸门，
     * 管理端点一下就该生效，不能等 60s TTL。
     */
    public void remove(String id) {
        // selectById 受 @TableLogic 约束：已删除行查不到，这里要的正是"不存在（含已删除）"
        if (medicalTermMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.TERM_NOT_FOUND);
        }
        medicalTermMapper.deleteById(id);
        refresh();
        log.info("术语 {} 已删除（逻辑删除，同词新增可恢复）", id);
    }

    private record CacheEntry(Set<String> terms, long loadedAt) {
    }

    /**
     * 术语最短长度：单字一律拒绝，理由见 {@link #add}。
     * 公开是为了让 ES 写入侧（{@code ChunkIndexService}）引用同一个数——两处口径必须一致。
     */
    public static final int MIN_TERM_LENGTH = 2;

    /**
     * 术语最长长度（**业务口径，不是列宽**）：真实术语是「心慌 / 上腹痛 / 劳力性胸闷」这种长度，
     * 超过 20 字基本是误粘贴了一整段话。列宽 `VARCHAR(128)` 只是物理上限，不构成这个数的依据。
     */
    private static final int MAX_TERM_LENGTH = 20;

    /**
     * 新增术语（管理端「新增术语」）：默认 {@code source = manual}、{@code enabled = 1}，
     * **不受 {@code term.manual.review} 约束**——那个开关管的是"模型抽取的词要不要人工确认"，
     * 人工亲手加的词已经过一次确认。
     *
     * <p><b>单字为什么拒绝</b>：信息充足性门槛（{@code SufficiencyRule.tooVague}）用**子串**匹配
     * （{@link #matches}）。塞一个「痛」进白名单，几乎任何主诉都"命中术语"，门槛当场失真、
     * 短主诉再也不被追问。现有种子里有「头 / 腹 / 心 / 肺」等单字，那是历史集合，
     * **不构成新增时放行的先例**——本条注释就是防止后来者"顺手放开"的护栏。
     *
     * <p><b>判存在按物理行判</b>：`uk_mt_term_type` 是物理唯一键，逻辑删除的行仍占着它。
     * 活跃行 → 报「已存在」；**仅有已删除行 → 复活**（`deleted=0`、`enabled=1`，`source` 保持原值）。
     * 并发撞键兜底成同一个可展示错误，不冒 500。
     */
    public MedicalTerm add(String term, TermType type) {
        if (type == null) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "术语类型仅支持 part/symptom");
        }
        String text = validateTerm(term);
        String typeCode = type.getCode();
        try {
            if (medicalTermMapper.countIncludingDeleted(text, typeCode) > 0) {
                // 有物理行：要么是活跃行（报错），要么是已删除行（复活）。revive 的返回行数直接区分两者。
                // 并发下两个请求可能同时判到"已删除行"：后到的那个 revive 返回 0 ⇒ 那一行已经被
                // 对方复活成活跃行，此时报「已存在」是对的（管理员看到的是"它已经在表里了"），
                // 与 insert 撞唯一键后的兜底给出同一个可展示错误，都不会冒 500。
                if (medicalTermMapper.revive(text, typeCode) == 0) {
                    throw new BizException(ErrorCode.TERM_EXISTS);
                }
                refresh();
                log.info("术语「{}」（{}）曾被删除，已复活并启用（来源保持原值）", text, typeCode);
                return medicalTermMapper.selectOne(Wrappers.<MedicalTerm>lambdaQuery()
                        .eq(MedicalTerm::getTerm, text)
                        .eq(MedicalTerm::getType, type));
            }

            MedicalTerm entity = new MedicalTerm();
            entity.setTerm(text);
            entity.setType(type);
            entity.setSource(TermSource.MANUAL);
            entity.setEnabled(1);
            medicalTermMapper.insert(entity);
            refresh();
            log.info("术语「{}」（{}）已新增并启用", text, typeCode);
            return entity;
        } catch (DataIntegrityViolationException e) {
            // 并发撞唯一键：判存在与写库之间被别的请求插了队。转成可展示错误，不冒 500
            throw new BizException(ErrorCode.TERM_EXISTS);
        }
    }

    /**
     * 编辑术语（管理端「编辑」）：只改**词面 + 类型**，{@code source} 与 {@code enabled} 一律保持原值。
     *
     * <p><b>为什么不接收启用状态</b>：启停已有独立的 toggle 入口，同一件事给两个入口，管理员就再也
     * 说不清"我刚改的到底生效没有"。
     *
     * <p><b>为什么 source 不动</b>：来源是**审计标签**（这个词最初是模型抽取的还是人加的）。
     * 模型抽取的词被人工改一次词面就洗成「人工维护」，以后就答不上"这条到底是模型提的还是人加的"。
     *
     * <p><b>判重必须是「另一行」</b>：{@code (term, type)} 是物理唯一键，但它允许目标行自己保持原键。
     * 所以不能直接 {@code countIncludingDeleted}（会把目标行算进去），要走
     * {@link MedicalTermMapper#otherRowDeleted}。撞活跃行与撞已删除行的文案必须分开——
     * 后者管理员在列表里根本看不见，只说"已存在"等于让他无从下手。
     */
    public MedicalTerm update(String id, String term, TermType type) {
        if (type == null) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "术语类型仅支持 part/symptom");
        }
        // selectById 受 @TableLogic 约束：已删除行查不到，这里要的正是"不存在（含已删除）"
        MedicalTerm target = medicalTermMapper.selectById(id);
        if (target == null) {
            throw new BizException(ErrorCode.TERM_NOT_FOUND);
        }
        String text = validateTerm(term);
        String typeCode = type.getCode();
        try {
            Integer otherDeleted = medicalTermMapper.otherRowDeleted(text, typeCode, id);
            if (otherDeleted != null) {
                throw new BizException(ErrorCode.TERM_EXISTS.getCode(), Integer.valueOf(1).equals(otherDeleted)
                        ? "该术语曾被删除，请直接在新增里填写以恢复"
                        : "该术语已存在");
            }
            target.setTerm(text);
            target.setType(type);
            medicalTermMapper.updateById(target);
        } catch (DataIntegrityViolationException e) {
            // 并发：另一个请求在判重之后、写库之前占了同一个键
            throw new BizException(ErrorCode.TERM_EXISTS.getCode(), "该术语已存在");
        }
        refresh();
        log.info("术语 {} 已编辑为「{}」（{}），来源与启停状态保持不变", id, text, typeCode);
        return target;
    }

    /**
     * 词面校验：trim 后 2–20 字，拒绝内部空白与换行。
     * 内部空白/换行会让一个词在页面上看着是两个词，子串匹配也永远命不中它。
     *
     * <p><b>数的是「字」不是 char</b>：用 {@link String#codePointCount} 而不是 {@code length()}——
     * 后者按 UTF-16 code unit 数，而一个汉字在一个代理对里会被算成 2。虽然术语都是汉字、
     * 两种数法结果一致，但用 code point 才与提示文案「N 个字」对得上。
     */
    private String validateTerm(String term) {
        String text = term == null ? "" : term.strip();
        String normalized = normalizeTerm(text);
        if (normalized != null) {
            return normalized;
        }
        // **判定只在 normalizeTerm 一处**；这里只为"哪一条不合法"选一句给人看的话。
        // 若哪天规则变了，改 normalizeTerm 一处即可——这两条分支只负责文案，不负责判定方向。
        int length = text.codePointCount(0, text.length());
        if (length < MIN_TERM_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "术语至少 2 个字（单字会让所有主诉都被判为含医学术语，信息充足性门槛失真）");
        }
        if (length > MAX_TERM_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "术语最多 " + MAX_TERM_LENGTH + " 个字");
        }
        throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "术语不能包含空格或换行");
    }

    /**
     * 词面校验的**不抛版本**：合法则返回 trim 后的词，不合法返回 {@code null}。
     *
     * <p>给抽取器用——模型给的词不合规是常态，不是参数错误，不该让整批抽取失败；
     * 但"什么叫合法"只能有这一份判断。判据与 {@link #validateTerm} 完全同源
     * （后者只是把同一条规则翻译成给人看的错误，供管理端表单用）。
     *
     * <p><b>单字为什么必须在这里挡掉</b>：信息充足性门槛（{@code SufficiencyRule}）用**子串**匹配，
     * 一个「头」进白名单会让几乎全部主诉都被判"含医学术语"、门槛当场失真。
     * 管理端、抽取器、导入路径都汇聚到这一处，就不存在"某个入口忘了挡"的情况。
     */
    public String normalizeTerm(String term) {
        String text = term == null ? "" : term.strip();
        int length = text.codePointCount(0, text.length());
        if (length < MIN_TERM_LENGTH || length > MAX_TERM_LENGTH) {
            return null;
        }
        if (text.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return text;
    }

    /**
     * 合并模型抽取的术语（链路 B 入库即抽取的落库口）。
     *
     * <p><b>与 {@link #add} 刻意不同、不复用它</b>：{@code add} 的复活语义是人工路径的契约
     * （人删了又加 = 想恢复）。自动流程借用它是错的，见下表：
     *
     * <table>
     *   <tr><th>表内状态</th><th>处理</th></tr>
     *   <tr><td>活跃行（deleted=0）</td><td><b>跳过，不动 enabled</b>——人手动停用的词不能因又被抽到就复活</td></tr>
     *   <tr><td>已删除行（deleted=1）</td><td><b>跳过，不复活</b>——"删除"是人的决定</td></tr>
     *   <tr><td>不存在</td><td>插入，source=llm_extract，enabled 由 term.manual.review 决定</td></tr>
     * </table>
     *
     * <p><b>判存在按物理行判</b>：{@code uk_mt_term_type} 是物理唯一键，逻辑删除的行仍占着它
     * （《数据库设计.md》§0.1）。因词面校验已在入口完成，这里的词都已 trim 过、可直接查。
     *
     * <p>整份文档的词一次写、写后 {@code refresh()} 一次——不是逐词 refresh，也不是每批一次。
     *
     * @return 三个计数（插入 / 跳过活跃 / 跳过已删除），供调用方记日志
     */
    public MergeResult mergeExtracted(Collection<ExtractedTerm> terms) {
        if (terms == null || terms.isEmpty()) {
            return new MergeResult(0, 0, 0);
        }
        // 审核开关 = 抽出的词要不要人工确认；关掉时抽取即生效
        boolean review = sysConfigService.getBoolean(SysConfigService.KEY_TERM_MANUAL_REVIEW, true);
        int enabled = review ? 0 : 1;
        int inserted = 0;
        int skippedActive = 0;
        int skippedDeleted = 0;
        Set<String> seen = new HashSet<>();
        for (ExtractedTerm term : terms) {
            String text = normalizeTerm(term.term());
            if (text == null || term.type() == null || !seen.add(text + '\u0000' + term.type().getCode())) {
                continue;
            }
            String typeCode = term.type().getCode();
            if (medicalTermMapper.countIncludingDeleted(text, typeCode) > 0) {
                // 物理行存在：要么活跃、要么已删除。活跃行里可能有 deleted=0 的也有 enabled=0 的，
                // 用 @TableLogic 下的 selectCount 区分——能看到行 = 活跃行；看不到 = 只剩已删除行
                boolean active = medicalTermMapper.selectCount(Wrappers.<MedicalTerm>lambdaQuery()
                        .eq(MedicalTerm::getTerm, text)
                        .eq(MedicalTerm::getType, term.type())) > 0;
                if (active) {
                    skippedActive++;
                } else {
                    skippedDeleted++;
                }
                continue;
            }
            try {
                MedicalTerm entity = new MedicalTerm();
                entity.setTerm(text);
                entity.setType(term.type());
                entity.setSource(TermSource.LLM_EXTRACT);
                entity.setEnabled(enabled);
                medicalTermMapper.insert(entity);
                inserted++;
            } catch (DataIntegrityViolationException e) {
                // 并发：别的入库流程刚插了同一个词。跳过即可，不进白名单两次，也不冒 500
                log.warn("抽取术语「{}」（{}）并发撞唯一键，已跳过", text, typeCode);
                skippedActive++;
            }
        }
        if (inserted > 0) {
            refresh();
        }
        log.info("抽取术语合并：插入 {} / 跳过活跃 {} / 跳过已删除 {}（人工审核={}）",
                inserted, skippedActive, skippedDeleted, review);
        return new MergeResult(inserted, skippedActive, skippedDeleted);
    }

    /** 抽取合并的计数（三个数字各对应一条处理分支，日志与测试都读它） */
    public record MergeResult(int inserted, int skippedActive, int skippedDeleted) {
    }

    private Set<String> load() {
        Set<String> terms = new HashSet<>();
        for (MedicalTerm term : medicalTermMapper.selectList(Wrappers.<MedicalTerm>lambdaQuery()
                .eq(MedicalTerm::getEnabled, 1))) {
            if (term.getTerm() != null && !term.getTerm().isBlank()) {
                terms.add(term.getTerm().trim());
            }
        }
        return Set.copyOf(terms);
    }
}
