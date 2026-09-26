package com.guide.kb.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.kb.entity.Dept;
import com.guide.kb.mapper.DeptMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 科室蓝本服务（链路 B）。
 * 停用仅入口生效：挂号页只列 enabled=1；推荐校验按 enabled 过滤（见链路 A 对齐点）。
 */
@Service
@RequiredArgsConstructor
public class DeptService {

    private final DeptMapper deptMapper;

    /** 启用科室（挂号科室范围 / 推荐候选范围） */
    public List<Dept> listEnabled() {
        return deptMapper.selectList(Wrappers.<Dept>lambdaQuery()
                .eq(Dept::getEnabled, 1)
                .orderByAsc(Dept::getCreatedAt));
    }

    /**
     * 全部科室（**含停用**）：管理端知识维护用。
     * 停用只在导诊入口生效——知识库该挂哪个科室，与它此刻开不开诊是两件事。
     */
    public List<Dept> listAll() {
        return deptMapper.selectList(Wrappers.<Dept>lambdaQuery().orderByAsc(Dept::getCreatedAt));
    }

    public Dept getById(String id) {
        return id == null ? null : deptMapper.selectById(id);
    }

    /** 按科室名精确匹配（模型输出的科室名 → 科室实体，校验用） */
    public Dept findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return deptMapper.selectOne(Wrappers.<Dept>lambdaQuery()
                .eq(Dept::getName, name.trim())
                .last("LIMIT 1"));
    }
}
