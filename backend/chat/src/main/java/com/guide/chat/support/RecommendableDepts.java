package com.guide.chat.support;

import com.guide.kb.entity.Dept;
import com.guide.kb.service.DeptService;
import com.guide.kb.service.KbDocService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 可推荐科室 = **启用（{@code enabled=1}）且已有切片（切片数 ≥ 1）**。
 *
 * <p>这是链路 A 的候选科室不变式：候选清单 = 推荐校验白名单，两者必须同源
 * （白名单摆在 prompt 里，模型只能抄）——{@link com.guide.chat.service.ChatService}
 * 与 {@link com.guide.chat.service.GuideService} 共用本类，不各写一遍过滤。
 *
 * <p><b>为什么不是「全部启用科室」</b>：种子/管理端可以新建一个只建了蓝本、语料待上传的科室
 * （{@code chunk_total=0} 的合法中间态）。这种科室知识库里零内容，模型对它的全部认知来自自身先验、
 * 不来自本项目数据——推荐它天然拿不出任何判断依据。把它留在候选清单里，实测会让模型推荐
 * 一个知识库中根本不存在的科室（2026-10-03 实测：老年医学科，切片为空仍被推荐）。
 * 语料上传后它自动进入候选（无需人工开关）。
 *
 * <p><b>为什么放在 chat 而不是 kb 的 {@code DeptService}</b>：本类依赖 {@code KbDocService}，
 * 而 {@code KbDocService} 已经注入 {@code DeptService}——若把 {@code listRecommendable()} 加进
 * {@code DeptService}，就构成 {@code DeptService → KbDocService → DeptService} 的构造器循环依赖，
 * Spring 启动直接失败。两个调用方都在 chat，故作为 chat 侧的小 helper 共用。
 */
@Component
@RequiredArgsConstructor
public class RecommendableDepts {

    private final DeptService deptService;
    private final KbDocService kbDocService;

    /** 可推荐科室列表（候选清单 = 校验白名单） */
    public List<Dept> list() {
        return snapshot().recommendable();
    }

    /**
     * 可推荐科室 + 启用总数：供调用方一次拿全，日志据此说明「因无切片剔除几个」。
     * 只暴露计数，不把科室行塞进日志（不打印业务明细）。
     */
    public Snapshot snapshot() {
        List<Dept> enabled = deptService.listEnabled();
        Map<String, Long> chunkCounts = kbDocService.countChunksByDept();
        List<Dept> recommendable = enabled.stream()
                .filter(dept -> chunkCounts.getOrDefault(dept.getId(), 0L) >= 1)
                .toList();
        return new Snapshot(recommendable, enabled.size());
    }

    /** 可推荐科室快照：{@code enabledCount} = 启用科室总数（含无切片者） */
    public record Snapshot(List<Dept> recommendable, int enabledCount) {

        public int recommendableCount() {
            return recommendable.size();
        }

        /** 启用但因无切片被剔除的科室数 */
        public int filtered() {
            return enabledCount - recommendable.size();
        }
    }
}
