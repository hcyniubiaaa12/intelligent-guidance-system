package com.guide.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.kb.entity.MedicalTerm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 医疗术语白名单。
 *
 * <p>下面三个方法是**本模块仅有的自定义 SQL**，它们绕开 MyBatis-Plus 的逻辑删除——这是刻意的：
 * `deleted` 是逻辑标志，而 **`uk_mt_term_type(term, type)` 是物理唯一键，它不认这个标志**。
 * 只按"逻辑存在"判重，删过的词仍占着唯一键，下次 INSERT 就会撞键（`@TableLogic` 只作用于
 * 框架生成的语句，`selectCount` / `selectList` 都被自动加上 `deleted = 0`，指望它们看见被删行
 * 是错的）。这与 `sensitive_word` 那场"种子撞键导致应用起不来"的事故（《数据库设计.md》§0.1）
 * 是同一个形状，只是术语表此前没有删除入口、没被触发过。
 */
@Mapper
public interface MedicalTermMapper extends BaseMapper<MedicalTerm> {

    /**
     * 物理存在性：**把逻辑删除的行也算作存在**（唯一键就是这么看的）。
     * 不要用 `selectCount` 替代——`@TableLogic` 会给它加上 `deleted = 0`。
     */
    @Select("SELECT COUNT(*) FROM medical_term WHERE term = #{term} AND type = #{type}")
    int countIncludingDeleted(@Param("term") String term, @Param("type") String type);

    /**
     * 复活一个被逻辑删除的术语：{@code deleted = 0}、{@code enabled = 1}，
     * **{@code source} 保持原值**——来源是审计标签（这个词最初是模型抽取还是人工加的），
     * 不该被一次"恢复"洗掉。
     *
     * @return 影响行数；0 表示没有可复活的行（调用方据此判定活跃行已存在）
     */
    @Update("UPDATE medical_term SET deleted = 0, enabled = 1, updated_at = NOW() "
            + "WHERE term = #{term} AND type = #{type} AND deleted = 1")
    int revive(@Param("term") String term, @Param("type") String type);

    /**
     * 编辑时的「另一行」判重：取**除目标行以外**、物理存在的同键行的 {@code deleted} 标志。
     *
     * <p>三个返回值各有含义：{@code null} = 没有另一行（不冲突）；{@code 0} = 另一行是活跃行；
     * {@code 1} = 另一行是**已被逻辑删除**的行——管理员在列表里看不到它，报"已存在"会让他无从下手，
     * 文案要指向"用新增恢复"。用一条 SQL 同时回答"有没有"和"是哪种"，避免两次查询之间状态漂移。
     *
     * <p>**不能用 {@code countIncludingDeleted} 判**：它会把**目标行自己**算进去，编辑时永远是
     * "已存在"。也不能用 {@code selectOne} 之类框架语句——{@code @TableLogic} 会加上
     * {@code deleted = 0}，看不见已删除的另一行。
     *
     * <p>值域上文已保证 {@code (term, type)} 在物理行里最多一条，故取 {@code MAX} 只是为了拿单值。
     */
    @Select("SELECT MAX(deleted) FROM medical_term WHERE term = #{term} AND type = #{type} AND id <> #{excludeId}")
    Integer otherRowDeleted(@Param("term") String term, @Param("type") String type,
                            @Param("excludeId") String excludeId);
}
