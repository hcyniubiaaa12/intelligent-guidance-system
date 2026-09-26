package com.guide.kb.parse;

import com.guide.common.model.LayoutBlock;

import java.util.List;

/**
 * 一次解析的起手形态。两条路殊途同归——都指向"这份文档的版面块可用了"，
 * 但**谁能立刻拿到、谁要等**不同，所以必须分开表达：
 *
 * <ul>
 *   <li>{@link Submitted}——pdf/docx/png 走 DocumentMind：拿到外部任务号就结束，
 *       几分钟后才回得来，中间靠 {@code ingest_task.external_job_id} 接续</li>
 *   <li>{@link Parsed}——txt/md/html 本地解析：块就在手上，没必要多绕一圈</li>
 * </ul>
 *
 * <p>第二段永远能用 {@code loadBlocks(docId, externalJobId)} 重新取到块，所以即使 JVM 重启、
 * 手上的块丢了，任务也能接着往下走——**任务表是权威状态**，靠的就是这一点。
 */
public sealed interface ParseSession {

    /** 外部解析已提交，等轮询 */
    record Submitted(String jobId) implements ParseSession {
    }

    /** 本地解析已完成 */
    record Parsed(List<LayoutBlock> blocks) implements ParseSession {
    }
}
