package com.guide.memory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.guide.auth.service.MemorySettingService;
import com.guide.common.config.PromptProperties;
import com.guide.llm.client.ChatModel;
import com.guide.llm.client.ChatMsg;
import com.guide.memory.config.MemoryExecutorConfig;
import com.guide.memory.entity.Memory;
import com.guide.memory.enums.MemoryStatus;
import com.guide.memory.mapper.MemoryMapper;
import com.guide.memory.support.MemoryExtractValidator;
import com.guide.memory.support.MemoryJsonSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 稳定背景的检索扩展文本生成（患者手动改值后的异步补写）。
 *
 * <p><b>只换说法、不补充事实</b>：输入是条目本身的 key / value / quote + 本轮对话上下文
 * （上下文只用来理解患者怎么表达，不得据此新增事实），输出用于 embedding 的 {@code retrieval_text}——
 * 不注入 prompt、不展示给患者、不进证据快照。它存在的唯一理由是「患者换一种说法时，背景还能被向量路找回来」。
 *
 * <p><b>两条入口</b>：抽取落库后（带对话上下文）与患者手动改值后（无上下文，避免旧原话锚定改口前的说法）。
 *
 * <p><b>失败是旁路</b>：模型失败、空输出、超长一律只 WARN；编辑时已经把 {@code retrieval_text}
 * 回退成新值，召回行为与没有扩写时完全一致。**不重试**（回填任务只补向量，不生成扩写）。
 *
 * <p><b>开关关闭时不生成</b>：患者关掉自动记忆后，系统不再为记忆做任何模型调用；
 * 代价只是关闭期间改的口暂时没有扩写，重新打开后下次改口会再生成。**入队与执行各查一次**——
 * 任务排队期间关掉开关，也不能在之后偷偷调模型。
 *
 * <p><b>写回带条件</b>：只有 {@code mvalue} 仍是任务开始时的值才写入，否则跳过——
 * 患者连续改两次时，先启动的任务不能覆盖后一次的新值。
 *
 * <p><b>quote 可能为空</b>：手动编辑只改值，旧原话指向改口前的说法，调用方**不传它**——
 * 否则模型会锚定旧说法（「高血压」→「低血压」时仍扩出「血压高」），正是本功能要避免的错。
 *
 * <p><b>专用单线程池</b>：扩写是一次完整 chat 调用（可达数十秒），与抽取池共用会把两个核心线程占满、
 * 饿住轮次抽取；改口是低频动作，单线程足够。
 */
@Slf4j
@Component
public class MemoryEnricher {

    /** 对话上下文截断长度（约 8 条对话），防止整段历史把 prompt 撑爆 */
    private static final int MAX_CONTEXT_CHARS = 2000;

    private final ChatModel chatModel;
    private final PromptProperties prompts;
    private final MemorySettingService memorySettingService;
    private final MemoryMapper memoryMapper;
    private final MemoryVectorIndexer vectorIndexer;
    private final ThreadPoolTaskExecutor memoryExecutor;

    public MemoryEnricher(ChatModel chatModel,
                          PromptProperties prompts,
                          MemorySettingService memorySettingService,
                          MemoryMapper memoryMapper,
                          MemoryVectorIndexer vectorIndexer,
                          @Qualifier(MemoryExecutorConfig.MEMORY_ENRICH_EXECUTOR) ThreadPoolTaskExecutor memoryExecutor) {
        this.chatModel = chatModel;
        this.prompts = prompts;
        this.memorySettingService = memorySettingService;
        this.memoryMapper = memoryMapper;
        this.vectorIndexer = vectorIndexer;
        this.memoryExecutor = memoryExecutor;
    }

    /**
     * 患者手动改值后：事务提交后异步补一次扩写。
     *
     * <p>**必须在事务提交后执行**：编辑方法在 {@code @Transactional} 里，任务若在提交前跑，
     * 它对同一行的写会与事务抢锁 / 读到旧值，症状是「改口后扩写文本还是旧说法的」。
     */
    public void scheduleAfterEdit(String userId, String memoryId, String key, String value, String quote) {
        // 手动编辑没有新的对话上下文：旧原话可能指向改口前的说法，不能当上下文喂给模型
        schedule(userId, memoryId, key, value, quote, null);
    }

    /**
     * 抽取落库后：带着本轮对话上下文异步补一次扩写。
     *
     * <p>上下文只用来理解「患者是怎么表达这件事的」，不得据此新增事实；手动编辑路径上下文为空。
     */
    public void scheduleAfterExtract(String userId, String memoryId, String key, String value,
                                     String quote, String context) {
        schedule(userId, memoryId, key, value, quote, context);
    }

    private void schedule(String userId, String memoryId, String key, String value,
                          String quote, String context) {
        if (userId == null || userId.isBlank() || memoryId == null || memoryId.isBlank()) {
            return;
        }
        // 调度本身也是旁路：开关查询 / 事务同步注册失败不能把已经落库的记忆回滚
        try {
            // 关闭自动记忆：不生成、不补（关掉后系统不再为记忆调模型）
            if (!memorySettingService.isEnabled(userId)) {
                return;
            }
            Runnable task = () -> enrichAndSave(userId, memoryId, key, value, quote, context);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        submit(task);
                    }
                });
            } else {
                submit(task);
            }
        } catch (RuntimeException e) {
            log.warn("记忆扩写调度失败（不影响主流程，保持回退文案）：userId={} memoryId={}｜原因={}",
                    userId, memoryId, e.getMessage());
        }
    }

    private void submit(Runnable task) {
        try {
            memoryExecutor.execute(task);
        } catch (RuntimeException e) {
            // 池满 / 已关闭：记忆本身已经改好了（retrieval_text 已回退新值），只影响召回粒度
            log.warn("记忆扩写任务提交失败（保持回退文案）：{}", e.getMessage());
        }
    }

    /** 生成并写回；失败保持落库时写入的回退值（新 mvalue） */
    void enrichAndSave(String userId, String memoryId, String key, String value,
                       String quote, String context) {
        // 任务排队期间患者可能已经关掉自动记忆：执行前再确认一次，
        // 「关掉后系统不再为记忆调模型」不能只在入队那一刻成立
        if (!memorySettingService.isEnabled(userId)) {
            return;
        }
        String retrievalText = enrich(key, value, quote, context);
        if (retrievalText == null) {
            return;
        }
        try {
            int affected = memoryMapper.update(null, Wrappers.<Memory>lambdaUpdate()
                    .set(Memory::getRetrievalText, retrievalText)
                    .set(Memory::getUpdatedAt, LocalDateTime.now())
                    .eq(Memory::getId, memoryId)
                    .eq(Memory::getUserId, userId)
                    .eq(Memory::getMvalue, value)
                    .eq(Memory::getStatus, MemoryStatus.ACTIVE));
            if (affected <= 0) {
                // 患者已经又改了一次 / 这条被删了：旧任务不能覆盖新值
                log.debug("记忆扩写写回跳过（值已变化或记忆已失效）：userId={} memoryId={}", userId, memoryId);
                return;
            }
            Memory row = memoryMapper.selectById(memoryId);
            if (row != null) {
                vectorIndexer.index(row);
            }
            log.info("稳定背景扩写已写入：userId={} memoryId={}｜{}", userId, memoryId, retrievalText);
        } catch (RuntimeException e) {
            log.warn("稳定背景扩写写回失败（保持回退文案）：userId={} memoryId={}｜原因={}",
                    userId, memoryId, e.getMessage());
        }
    }

    /**
     * 调模型生成一条扩写；失败 / 空 / 超长返回 null（调用方保持回退值）。
     *
     * <p>代码只校验非空与长度：事实边界靠提示词约束——用规则判「有没有编造事实」不可靠，
     * 还会误杀合法同义词（「血糖高」不含「2型糖尿病」）。
     */
    String enrich(String key, String value, String quote, String context) {
        String output;
        try {
            output = chatModel.chat(messages(key, value, quote, context));
        } catch (RuntimeException e) {
            log.warn("记忆扩写模型调用失败（保持回退文案）：{}", e.getMessage());
            return null;
        }
        try {
            JsonNode root = MemoryJsonSupport.parseObject(output);
            // 与抽取路径共用同一套清理：空 / 占位词（「无」「null」「N/A」）/ 过短 / 超长 ⇒ 回退 mvalue。
            // 各自实现一份的话，同一句模型输出会在一条路径落库、另一条被当成有效文本。
            return MemoryExtractValidator.cleanRetrievalText(root.path("retrievalText").asText(""));
        } catch (RuntimeException e) {
            log.warn("记忆扩写输出不可解析（保持回退文案）：{}｜原始输出：{}", e.getMessage(), abbreviate(output));
            return null;
        }
    }

    private List<ChatMsg> messages(String key, String value, String quote, String context) {
        String user = prompts.getMemoryEnrich().getUserTemplate();
        user = PromptProperties.render(user, PromptProperties.PLACEHOLDER_ENRICH_KEY, key);
        user = PromptProperties.render(user, PromptProperties.PLACEHOLDER_ENRICH_VALUE, value);
        user = PromptProperties.render(user, PromptProperties.PLACEHOLDER_ENRICH_QUOTE, quote);
        user = PromptProperties.render(user, PromptProperties.PLACEHOLDER_ENRICH_CONTEXT, contextText(context));
        return List.of(
                ChatMsg.system(prompts.getMemoryEnrich().getSystem()),
                ChatMsg.user(user));
    }

    /**
     * 上下文截断：最多 2000 字（约 8 条对话），防止整段历史把 prompt 撑爆。
     * 只用于理解患者怎么表达，不进入任何展示或事实存储。
     */
    private String contextText(String context) {
        if (context == null) {
            return "";
        }
        String text = context.strip();
        return text.length() > MAX_CONTEXT_CHARS ? text.substring(0, MAX_CONTEXT_CHARS) + "…" : text;
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() > 300 ? flat.substring(0, 300) + "…" : flat;
    }
}
