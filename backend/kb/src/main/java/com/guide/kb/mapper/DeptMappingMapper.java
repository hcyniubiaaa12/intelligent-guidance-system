package com.guide.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.kb.entity.DeptMapping;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 台账键 {@code (symptom, main_dept_id)} 是物理唯一键，而 {@code deleted} 是逻辑标志。
 * 下面这条查询把删过的行也算上：修正重审会逻辑删除旧行，再次 approve 同键时若只按
 * 逻辑存在判，INSERT 会撞 {@code uk_dm_symptom_main}。{@code @TableLogic} 管不到自定义 SQL。
 */
@Mapper
public interface DeptMappingMapper extends BaseMapper<DeptMapping> {

    @Select("SELECT * FROM dept_mapping WHERE symptom = #{symptom} AND main_dept_id = #{mainDeptId} LIMIT 1")
    DeptMapping selectByKeyIncludingDeleted(@Param("symptom") String symptom,
                                             @Param("mainDeptId") String mainDeptId);
}
