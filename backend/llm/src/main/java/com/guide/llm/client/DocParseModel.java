package com.guide.llm.client;

import com.guide.common.model.LayoutBlock;

import java.io.InputStream;
import java.util.List;

/**
 * 文档解析适配（链路 B）：把 pdf/docx/png 交给外部解析服务，产出**版面块**。
 *
 * <p>放在 llm 是因为 llm 是本项目**唯一的对外出口**——DocumentMind 与前三个客户端
 * （DeepSeek / DashScope embedding / DashScope rerank）是同一类东西：外部 AI 服务。
 *
 * <p>调用是**三步异步**：{@link #submit} 拿到任务号就结束（一份件可能解析几分钟，
 * 不能让线程池干等），{@link #status} 轮询，{@link #fetchBlocks} 取结果。
 * 后两步都是**读接口，反复拉不再计费**——所以任务号要长期留着（存在
 * {@code ingest_task.external_job_id}）：进程重启后接着轮询靠的就是它。
 *
 * <p><b>不做降级</b>：解析失败就是 failed(retryable)，不退回 POI / PDFBox。
 * 降级路径产出质量不同，会让知识库混进两种切分风格，而切分质量直接就是检索质量；
 * 更要命的是降级路径平时永远不跑，只在故障时跑一次，产出的东西没人验证过就进了知识库。
 */
public interface DocParseModel {

    /**
     * 提交解析任务。
     *
     * @param in        文件流（**传流不传 URL**：URL 要求对服务端公网可达，而本项目的
     *                  MinIO 在局域网；SDK 内部会把流上传走）
     * @param fileName  原始文件名（含扩展名，服务端按它判格式）
     * @param extension 扩展名（小写，不含点）
     * @return 外部任务号
     */
    String submit(InputStream in, String fileName, String extension);

    /** 查询任务状态 */
    ParseStatus status(String jobId);

    /** 拉取全部版面块（服务端分页，这里拉全并按阅读顺序排好） */
    List<LayoutBlock> fetchBlocks(String jobId);

    /**
     * 解析任务状态。
     *
     * <p>失败时带上**分类与给人看的原因**：加密件/损坏件是输入问题（重跑也没用），
     * 服务端抖动是依赖问题（可重试）——这个判断只有解析层做得了，
     * 上层若一律当"可重试"，管理端就会拿到一个重跑一百遍也没用的「重新处理」按钮。
     *
     * @param failureKind 仅 {@link ParseState#FAILED} 时有意义；为 null 按依赖故障处理
     */
    record ParseStatus(ParseState state, int processing, String message, DocParseException.Kind failureKind) {

        public ParseStatus(ParseState state, int processing, String message) {
            this(state, processing, message, null);
        }

        public ParseStatus {
            failureKind = failureKind == null ? DocParseException.Kind.DEPENDENCY : failureKind;
        }

        public boolean running() {
            return state == ParseState.RUNNING;
        }

        public boolean succeeded() {
            return state == ParseState.SUCCESS;
        }

        public boolean failed() {
            return state == ParseState.FAILED;
        }
    }

    enum ParseState {
        RUNNING,
        SUCCESS,
        FAILED
    }
}
