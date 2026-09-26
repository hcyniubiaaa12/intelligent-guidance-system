package com.guide.kb.service;

import com.guide.common.api.ErrorCode;
import com.guide.common.exception.BizException;
import com.guide.kb.entity.Dept;
import com.guide.kb.mapper.DeptMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 科室蓝本编辑：**科室名唯一**是这里唯一有后果的规则。
 *
 * <p>模型输出的是科室名字符串，写库与推荐校验都靠"按名字精确匹配"把它换回科室实体
 * （{@link DeptService#findByName}）。重名会让这一步变成"取第一条"——同一条主诉今天落到
 * A 科室、明天落到 B 科室，而页面与看板都看不出异常。所以它必须在写库前拦住。
 */
class DeptServiceTest {

    private final DeptMapper deptMapper = mock(DeptMapper.class);
    private final DeptService service = new DeptService(deptMapper);

    @Test
    @DisplayName("改名 + 位置简介 + 启停：一次写全，空白位置/简介存 NULL 而不是空串")
    void updatesAllEditableFields() {
        when(deptMapper.selectById("d1")).thenReturn(dept("d1", "心血管内科", 1));
        when(deptMapper.selectOne(any())).thenReturn(null);

        service.update("d1", "  心血管内科（本部） ", "  ", " 冠心病、高血压诊治 ", false);

        ArgumentCaptor<Dept> captor = ArgumentCaptor.forClass(Dept.class);
        verify(deptMapper).updateById(captor.capture());
        Dept saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("心血管内科（本部）");
        assertThat(saved.getLocation()).isNull();
        assertThat(saved.getIntro()).isEqualTo("冠心病、高血压诊治");
        assertThat(saved.getEnabled()).isZero();
    }

    @Test
    @DisplayName("改成别的科室已经在用的名字：拒绝，且不写库")
    void rejectsDuplicateName() {
        when(deptMapper.selectById("d1")).thenReturn(dept("d1", "骨科", 1));
        when(deptMapper.selectOne(any())).thenReturn(dept("d2", "神经内科", 1));

        assertThatThrownBy(() -> service.update("d1", "神经内科", null, null, true))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.DEPT_NAME_EXISTS.getCode());
        verify(deptMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("改成自己原来的名字不算重名（只改位置时名字原样回传是常态）")
    void allowsKeepingOwnName() {
        when(deptMapper.selectById("d1")).thenReturn(dept("d1", "骨科", 1));
        when(deptMapper.selectOne(any())).thenReturn(dept("d1", "骨科", 1));

        service.update("d1", "骨科", "门诊楼 2F", null, true);

        verify(deptMapper).updateById(any());
    }

    @Test
    @DisplayName("科室不存在 / 名字为空：都在写库前拒绝")
    void rejectsMissingDeptAndBlankName() {
        when(deptMapper.selectById("nope")).thenReturn(null);
        assertThatThrownBy(() -> service.update("nope", "骨科", null, null, true))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.DEPT_NOT_FOUND.getCode());

        when(deptMapper.selectById("d1")).thenReturn(dept("d1", "骨科", 1));
        assertThatThrownBy(() -> service.update("d1", "   ", null, null, true))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("科室名不能为空");
        verify(deptMapper, never()).updateById(any());
    }

    private Dept dept(String id, String name, int enabled) {
        Dept dept = new Dept();
        dept.setId(id);
        dept.setName(name);
        dept.setEnabled(enabled);
        return dept;
    }
}
