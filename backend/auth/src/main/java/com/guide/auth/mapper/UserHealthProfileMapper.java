package com.guide.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.auth.entity.UserHealthProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 患者健康档案（一人一行）。
 *
 * <p>下面两个方法是**自定义 SQL，绕开 MyBatis-Plus 的逻辑删除**——理由与
 * {@link SensitiveWordMapper} 相同：{@code uk_uhp_user} 是**物理**唯一键，它不认 {@code deleted} 标志。
 * 「整份覆盖写」在首次保存时会插入一行，若这一行曾被逻辑删除过（逻辑上查不到、物理上仍占着唯一键），
 * 直接 INSERT 就会撞键。判存在必须按物理行判（见《数据库设计.md》§0.1）。
 */
@Mapper
public interface UserHealthProfileMapper extends BaseMapper<UserHealthProfile> {

    /** 物理存在性：把逻辑删除的行也算作存在（唯一键就是这么看的）。不要用 selectCount 替代。 */
    @Select("SELECT COUNT(*) FROM user_health_profile WHERE user_id = #{userId}")
    int countIncludingDeleted(@Param("userId") String userId);

    /** 复活一条被逻辑删除的档案（保存前调用；本来就没删过时影响 0 行，幂等） */
    @Update("UPDATE user_health_profile SET deleted = 0, updated_at = NOW() "
            + "WHERE user_id = #{userId} AND deleted = 1")
    int revive(@Param("userId") String userId);
}
