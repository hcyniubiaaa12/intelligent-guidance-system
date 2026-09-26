package com.guide.kb.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
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

    /**
     * 管理端维护科室蓝本：改名、改位置与简介、启用/停用。
     *
     * <p><b>科室名必须唯一</b>，这不是洁癖：模型输出的是**科室名字符串**，写库与推荐校验
     * 都靠 {@link #findByName} 精确匹配把它换回科室实体。重名会让这一步变成"看运气取第一条"
     * ——同一条主诉今天落到 A 科室、明天落到 B 科室，而且页面与看板都看不出异常。
     *
     * <p>停用只影响导诊入口（挂号页不列、推荐校验过滤），chunk 与历史记录原样保留，
     * 所以这里不做任何连带清理。
     */
    public Dept update(String id, String name, String location, String intro, boolean enabled) {
        Dept dept = getById(id);
        if (dept == null) {
            throw new BizException(ErrorCode.DEPT_NOT_FOUND);
        }
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "科室名不能为空");
        }
        Dept sameName = findByName(trimmed);
        if (sameName != null && !sameName.getId().equals(id)) {
            throw new BizException(ErrorCode.DEPT_NAME_EXISTS.getCode(), "科室名「" + trimmed + "」已被占用");
        }
        dept.setName(trimmed);
        dept.setLocation(blankToNull(location));
        dept.setIntro(blankToNull(intro));
        dept.setEnabled(enabled ? 1 : 0);
        deptMapper.updateById(dept);
        return dept;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
