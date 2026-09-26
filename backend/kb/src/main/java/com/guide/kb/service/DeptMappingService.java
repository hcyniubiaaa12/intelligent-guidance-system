package com.guide.kb.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.guide.kb.entity.DeptMapping;
import com.guide.kb.mapper.DeptMappingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 症状交叉映射台账（链路 B）。
 *
 * <p><b>台账是事实记录，不是运行时输入</b>：RAG 检索不读它（闭环生效靠回流 approve 产出的
 * 合成 chunk，见数据库设计 §3）。它的键是 {@code (症状原文, 主科室)}——同症状可以因不同方向的桶
 * 各审出一个主科室，各占一行，所以管理端只做**只读展示**：这里没有"合并/去重"的余地，
 * 每一行都是一次真实审核结论的留痕。
 */
@Service
@RequiredArgsConstructor
public class DeptMappingService {

    private final DeptMappingMapper deptMappingMapper;

    /** 分页查询：症状关键字模糊匹配（台账行数少，不做更多筛选维度） */
    public IPage<DeptMapping> page(String keyword, long pageNum, long pageSize) {
        return deptMappingMapper.selectPage(new Page<>(pageNum, pageSize),
                Wrappers.<DeptMapping>lambdaQuery()
                        .like(keyword != null && !keyword.isBlank(), DeptMapping::getSymptom, keyword)
                        .orderByDesc(DeptMapping::getCreatedAt));
    }
}
