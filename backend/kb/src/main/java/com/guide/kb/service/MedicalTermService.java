package com.guide.kb.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.kb.entity.MedicalTerm;
import com.guide.kb.enums.TermType;
import com.guide.kb.mapper.MedicalTermMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 医学术语白名单（链路 A 入口防误杀 + 信息充足性规则门槛）。
 * MySQL medical_term 是**唯一生效源**：内存 Set 从这里加载，ES 聚合只是候选池；
 * 知识库更新（回流 / 重新入库 / 管理端增删）后调用 {@link #refresh()} 失效重建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicalTermService {

    private final MedicalTermMapper medicalTermMapper;

    /** 进程内缓存存活时长：管理端增删/停用术语后无需重启，最长一分钟生效 */
    private static final long CACHE_TTL_MS = 60_000L;

    /** 启用术语快照（带加载时间；过期或在 refresh() 时整体替换） */
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();

    /** 启用部位词快照：与上面那份**互不影响**，改一类不必连坐另一类（见 enabledParts 的说明） */
    private final AtomicReference<PartCacheEntry> partCache = new AtomicReference<>();

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

    /**
     * 启用中的**部位词**（患者端「人体图」的选项来源）。
     *
     * <p>与 {@link #enabledTerms()} 分开缓存、分开加载：那一路是链路 A 入口的两类判定
     * （防误杀精确匹配 / 信息充足性子串匹配），语义敏感、动它就是在动判定口径；本方法是
     * 第三类读者（患者能点什么），两条路的过滤条件与顺序都各自独立。
     * 返回的是<b>有序去重</b>列表：图上的选项按这个词表渲染，顺序抖动会让患者每次进来看到的
     * 排列不一样（{@code Set} 不保证顺序）。
     */
    public List<String> enabledParts() {
        PartCacheEntry entry = partCache.get();
        long now = System.currentTimeMillis();
        if (entry == null || now - entry.loadedAt() > CACHE_TTL_MS) {
            entry = new PartCacheEntry(loadParts(), now);
            partCache.set(entry);
        }
        return entry.parts();
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
        partCache.set(new PartCacheEntry(loadParts(), System.currentTimeMillis()));
        log.info("医学术语白名单已刷新，启用术语 {} 条（部位 {} 个）",
                enabledTerms().size(), enabledParts().size());
    }

    /** 管理端分页：**含停用**（停用只是不生效，行还留着，页面要能看到并重新启用） */
    public IPage<MedicalTerm> page(String keyword, Boolean enabled, long pageNum, long pageSize) {
        return medicalTermMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<MedicalTerm>lambdaQuery()
                        .like(keyword != null && !keyword.isBlank(), MedicalTerm::getTerm, keyword)
                        .eq(enabled != null, MedicalTerm::getEnabled, Boolean.TRUE.equals(enabled) ? 1 : 0)
                        .orderByAsc(MedicalTerm::getTerm));
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

    private record CacheEntry(Set<String> terms, long loadedAt) {
    }

    private record PartCacheEntry(List<String> parts, long loadedAt) {
    }

    /**
     * 启用部位词：查全部启用词后在 Java 侧按 type 收口。
     *
     * <p>类型与启用状态<b>都</b>在本方法里判，不只交给 SQL 条件。SQL 里的 {@code enabled=1}
     * 是查询入口，而"停用的词不许出现在患者端选项里"这条业务约束必须落在这一行代码上才立得住——
     * 否则它只是"恰好被过滤条件带上了"，换个查询就漏。type 同理：库里存的是编码字符串，
     * 历史行可能为空，落到这里统一按"不是 part 就不给"收口，不必把判据散到 SQL。
     */
    private List<String> loadParts() {
        List<String> parts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MedicalTerm term : medicalTermMapper.selectList(Wrappers.<MedicalTerm>lambdaQuery()
                .eq(MedicalTerm::getEnabled, 1))) {
            boolean usable = term.getType() == TermType.PART
                    && Integer.valueOf(1).equals(term.getEnabled())
                    && term.getTerm() != null && !term.getTerm().isBlank();
            if (!usable) {
                continue;
            }
            String word = term.getTerm().trim();
            if (seen.add(word)) {
                parts.add(word);
            }
        }
        return List.copyOf(parts);
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
