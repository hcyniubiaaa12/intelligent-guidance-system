package com.guide.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.guide.feedback.entity.ReviewTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 待审核队列。
 *
 * <p>下面两个写回方法不能用 {@code updateById}：**MyBatis-Plus 默认忽略实体里的 null 字段**
 * （字段策略 NOT_NULL），而这两处的语义恰恰是「把某些列写成空」——
 * 驳回时 {@code main_dept_id} 就是 null，修正重审时三个审核痕迹全是 null。
 * 走 {@code updateById} 这些 SET 根本不会生成，行上留着上一轮审核的值：
 * 一个 pending 的任务却记着「谁在什么时候审过、给了哪个科室」。
 */
@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    /**
     * 写回审核结果。status 与 {@code ReviewStatus} 的编码值一致（见《数据库设计.md》§0）。
     *
     * @param mainDeptId 审核给出的主科室；驳回/忽略时传 null，必须真的写空
     * @return 影响行数
     */
    @Update("UPDATE review_task SET status = 'done', reviewed_by = #{reviewedBy}, "
            + "reviewed_at = #{reviewedAt}, main_dept_id = #{mainDeptId} "
            + "WHERE id = #{id} AND deleted = 0")
    int finish(@Param("id") String id,
               @Param("reviewedBy") String reviewedBy,
               @Param("reviewedAt") LocalDateTime reviewedAt,
               @Param("mainDeptId") String mainDeptId);

    /**
     * 修正重审：清空审核痕迹，回到待审。不这么做的话，重审后的任务会带着上一轮的
     * 审核人与主科室，而它明明还没被审过。
     *
     * @return 影响行数
     */
    @Update("UPDATE review_task SET status = 'pending', reviewed_by = NULL, "
            + "reviewed_at = NULL, main_dept_id = NULL WHERE id = #{id} AND deleted = 0")
    int reopen(@Param("id") String id);
}
