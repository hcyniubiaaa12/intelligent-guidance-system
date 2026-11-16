package com.guide.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.chat.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    /**
     * 原子申领一次记忆抽取配额。
     *
     * <p>为什么必须用自定义 SQL 而不是 Wrapper：这是一条「读自己、条件写自己」的原子更新
     * （{@code SET x = x + 1 WHERE x < max}），先 select 再 update 在并发下会超出上限。
     * 自定义 {@code @Update} 不受 {@code @TableLogic} 影响，所以 {@code deleted = 0} 必须显式写。
     *
     * @return 影响行数：1 = 申领成功；0 = 已达上限或会话不存在/已删除
     */
    @Update("UPDATE chat_session SET memory_extract_count = memory_extract_count + 1 "
            + "WHERE id = #{sessionId} AND deleted = 0 AND memory_extract_count < #{max}")
    int tryIncrementMemoryExtractCount(@Param("sessionId") String sessionId, @Param("max") int max);

    /**
     * 空闲会话扫描（记忆抽取兜底）：最后一条消息早于 {@code idleBefore} 且配额未用尽。
     *
     * <p>自定义 SQL 不受 {@code @TableLogic} 影响，{@code cs.deleted = 0} / {@code cm.deleted = 0}
     * 都显式写。只取 id / user_id 两列——扫描只需要归属键，不必把整行读出来。
     */
    @Select("SELECT cs.id AS id, cs.user_id AS user_id "
            + "FROM chat_session cs "
            + "JOIN (SELECT cm.session_id, MAX(cm.created_at) AS last_at "
            + "      FROM chat_message cm WHERE cm.deleted = 0 GROUP BY cm.session_id) m "
            + "  ON m.session_id = cs.id "
            + "WHERE cs.deleted = 0 AND cs.memory_extract_count < #{maxExtractCount} "
            + "  AND m.last_at < #{idleBefore} "
            + "ORDER BY m.last_at ASC LIMIT #{limit}")
    List<ChatSession> findIdleSessions(@Param("idleBefore") LocalDateTime idleBefore,
                                       @Param("maxExtractCount") int maxExtractCount,
                                       @Param("limit") int limit);
}
