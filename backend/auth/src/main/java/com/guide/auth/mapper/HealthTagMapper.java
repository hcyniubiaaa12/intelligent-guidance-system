package com.guide.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.auth.entity.HealthTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 健康档案标签词表。
 *
 * <p>{@link #countIncludingDeleted} 是**唯一一处自定义 SQL，绕开逻辑删除**：{@code uk_ht_term_type}
 * 是物理唯一键。种子 runner 判存在必须按物理行判，否则「管理端停用/删过的词」会让种子 INSERT
 * 撞键、把整个应用启动带崩（与 {@code sensitive_word} 同一场事故，见《数据库设计.md》§0.1）。
 */
@Mapper
public interface HealthTagMapper extends BaseMapper<HealthTag> {

    /** 物理存在性（含逻辑删除的行）；种子的判重口径 */
    @Select("SELECT COUNT(*) FROM health_tag WHERE term = #{term} AND type = #{type}")
    int countIncludingDeleted(@Param("term") String term, @Param("type") String type);
}
