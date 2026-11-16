package com.guide.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.memory.entity.Memory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MemoryMapper extends BaseMapper<Memory> {

    /**
     * 存量迁移的幂等判据：**按物理行判存在**（{@code deleted} 不参与）。
     *
     * <p>为什么不能用 {@code selectCount}：{@code @TableLogic} 会给它补 {@code deleted=0}，
     * 于是患者删掉的迁移记忆在下次启动时会被当成"不存在"重新插入——「我删了它，
     * 一重启又回来了」比没有迁移更糟。自定义 SQL 不被逻辑删除拦截，正好用来判物理行
     * （同《数据库设计.md》§0.1 的口径）。
     *
     * <p>{@code user_memory} 没有唯一键，撞键不会崩应用；这里的重点是**尊重患者的删除决定**。
     */
    @Select("SELECT COUNT(*) FROM user_memory "
            + "WHERE user_id = #{userId} AND type = #{type} AND mkey = #{mkey} "
            + "AND mvalue = #{mvalue} AND source_quote = #{sourceQuote}")
    int countIncludingDeleted(@Param("userId") String userId,
                              @Param("type") String type,
                              @Param("mkey") String mkey,
                              @Param("mvalue") String mvalue,
                              @Param("sourceQuote") String sourceQuote);
}
