package com.guide.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.auth.entity.SensitiveWord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 敏感词库。
 *
 * <p>下面两个方法是**仅有的自定义 SQL**，它们绕开 MyBatis-Plus 的逻辑删除——这是刻意的：
 * `deleted` 是逻辑标志，而 **`uk_sw_word` 是物理唯一键，它不认这个标志**。
 * 只按"逻辑存在"判重，删过的词就会在下次 INSERT 时撞键。
 *
 * <p>2026-09-26 实测的后果比预想严重：删掉「滚」之后重启服务，
 * **种子 runner 撞键抛异常，整个应用起不来**（`ApplicationRunner` 抛异常会让 Spring Boot 退出）。
 * 所以"删过的词仍在库里占位"这件事，凡是新增/种子/导入路径都必须知道。
 */
@Mapper
public interface SensitiveWordMapper extends BaseMapper<SensitiveWord> {

    /**
     * 物理存在性：**把逻辑删除的行也算作存在**（唯一键就是这么看的）。
     * 不要用 `selectCount` 替代——`@TableLogic` 会给它加上 `deleted = 0`。
     */
    @Select("SELECT COUNT(*) FROM sensitive_word WHERE word = #{word}")
    int countIncludingDeleted(@Param("word") String word);

    /**
     * 复活一个被逻辑删除的词（管理端显式"添加/导入"这些词时的正确动作）。
     *
     * <p>为什么不是直接 INSERT：物理行还在、唯一键还被占着。复活比"报个错说已存在、
     * 但列表里又找不到它"有用得多——**顺带把 hit_count 等历史留了下来**。
     *
     * @return 影响行数；0 表示没有可复活的行（调用方按需要回落到 INSERT）
     */
    @Update("UPDATE sensitive_word SET deleted = 0, type = #{type}, enabled = 1, updated_at = NOW() "
            + "WHERE word = #{word} AND deleted = 1")
    int revive(@Param("word") String word, @Param("type") String type);
}
