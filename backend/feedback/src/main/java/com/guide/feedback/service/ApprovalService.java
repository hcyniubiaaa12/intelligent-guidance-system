package com.guide.feedback.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guide.common.api.ErrorCode;
import com.guide.common.config.PromptProperties;
import com.guide.common.exception.BizException;
import com.guide.feedback.entity.ClusterBucket;
import com.guide.feedback.entity.ReviewTask;
import com.guide.feedback.enums.BucketStatus;
import com.guide.feedback.enums.ReviewStatus;
import com.guide.feedback.mapper.ClusterBucketMapper;
import com.guide.feedback.mapper.ReviewTaskMapper;
import com.guide.kb.entity.Dept;
import com.guide.kb.entity.DeptMapping;
import com.guide.kb.entity.KbChunk;
import com.guide.kb.entity.KbDoc;
import com.guide.kb.enums.MappingSource;
import com.guide.kb.mapper.DeptMappingMapper;
import com.guide.kb.service.ChunkIndexService;
import com.guide.kb.service.DeptService;
import com.guide.kb.service.KbDocService;
import com.guide.llm.client.ChatModel;
import com.guide.llm.client.ChatMsg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 审核产出（链路 C）：预览、approve、驳回、忽略、修正重审。
 *
 * <p>approve 的两样产物：映射台账（键 = 症状原文 + 主科室，同键交叉科室取并集）与合成 chunk。
 * 台账和桶状态在同一个事务里；合成 chunk 走 kb 的写入入口，事务提交后才入库——
 * 入库要调向量模型，不能占着事务。失败时桶保持 approved，状态在知识库页的回流容器上可见，
 * 重试路径是修正重审后再审一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ClusterBucketMapper clusterBucketMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final DeptMappingMapper deptMappingMapper;
    private final DeptService deptService;
    private final KbDocService kbDocService;
    private final ChunkIndexService chunkIndexService;
    private final ChatModel chatModel;
    private final PromptProperties prompts;
    private final ObjectMapper objectMapper;

    /** dept_mapping.symptom 的列宽：台账键超长会让整次 approve 失败 */
    private static final int SYMPTOM_MAX = 128;
    /** 合成切片标题里的症状摘要长度：标题要稳定，修正重审靠它找回那一片 */
    private static final int TITLE_SYMPTOM_MAX = 40;

    /**
     * 预览鉴别诊断文本。症状与科室都由调用方按桶推导后传入，这里只负责生成。
     * LLM 失败用模板兜底，不中断审核。
     */
    public String preview(String symptom, String mainDeptName, List<String> crossDeptNames) {
        String cross = crossText(crossDeptNames);
        try {
            PromptProperties.Feedback feedback = prompts.getFeedback();
            String user = render(feedback.getUserTemplate(), symptom, mainDeptName, cross);
            String generated = chatModel.chat(List.of(
                    ChatMsg.system(feedback.getSystem()),
                    ChatMsg.user(user)));
            if (generated != null && !generated.isBlank()) {
                return generated.strip();
            }
            log.warn("合成 chunk 生成为空，使用模板兜底");
        } catch (RuntimeException e) {
            log.error("合成 chunk 生成失败，使用模板兜底", e);
        }
        return render(prompts.getFeedback().getFallbackTemplate(), symptom, mainDeptName, cross);
    }

    /**
     * 确认 approve。
     *
     * @param symptom       症状原文（台账键的一半，取桶的锚点文本）
     * @param mainDeptId    主科室
     * @param crossDeptIds  交叉科室（预填后管理员可增删）
     * @param syntheticText 管理员定稿的鉴别诊断文本
     * @param reviewedBy    审核人（用户 id）
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(String bucketId, String symptom, String mainDeptId, List<String> crossDeptIds,
                        String syntheticText, String reviewedBy) {
        ClusterBucket bucket = requirePending(bucketId);
        Dept mainDept = requireDept(mainDeptId);
        if (syntheticText == null || syntheticText.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(), "鉴别诊断文本不能为空");
        }
        List<String> cross = normalizeDeptIds(crossDeptIds, mainDeptId);
        // 台账键的症状半边有列宽：超长直接拒绝，不能静默截断——截断会把不同主诉并成一行
        if (symptom == null || symptom.isBlank() || symptom.length() > SYMPTOM_MAX) {
            throw new BizException(ErrorCode.PARAM_INVALID.getCode(),
                    "主诉超过 " + SYMPTOM_MAX + " 字，无法写入映射台账");
        }

        upsertMapping(symptom, mainDeptId, cross);
        bucket.setStatus(BucketStatus.APPROVED);
        clusterBucketMapper.updateById(bucket);
        finishTask(bucketId, mainDeptId, reviewedBy);

        // 容器在事务内懒创建（只是一行 kb_doc）；切片入库等事务提交后再做
        KbDoc container = kbDocService.ensureFeedbackContainer(mainDeptId, mainDept.getName());
        // 标题带桶 id：同科室下症状前几十字相同的桶不会互相覆盖，修正重审也按它找回
        String title = syntheticTitle(bucketId, symptom);
        afterCommit(() -> indexSynthetic(container.getId(), mainDeptId, title, syntheticText.strip()));
        log.info("桶 {} approve 完成，合成 chunk 待入库：容器 {}", bucketId, container.getId());
    }

    /** 驳回：审过并判定不是系统错 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(String bucketId, String reviewedBy) {
        close(bucketId, BucketStatus.REJECTED, reviewedBy);
    }

    /** 忽略：没审就关（测试数据、重复桶） */
    @Transactional(rollbackFor = Exception.class)
    public void dismiss(String bucketId, String reviewedBy) {
        close(bucketId, BucketStatus.DISMISSED, reviewedBy);
    }

    /**
     * 修正重审：任何终态桶都能回到 pending。
     *
     * <p>已 approved 的桶要把上次的产物撤掉——台账行按键删除，合成 chunk 按标题前缀删掉。
     * 撤不干净（标题对不上、容器已被删）只记日志，不挡住重审：人工闸门有权改自己。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reReview(String bucketId) {
        ClusterBucket bucket = clusterBucketMapper.selectById(bucketId);
        if (bucket == null) {
            throw new BizException(ErrorCode.BUCKET_NOT_FOUND);
        }
        if (!isTerminal(bucket.getStatus())) {
            throw new BizException(ErrorCode.BUCKET_STATUS_INVALID, "只有终态桶才能修正重审");
        }
        ReviewTask task = taskOf(bucketId);
        if (bucket.getStatus() == BucketStatus.APPROVED && task != null && task.getMainDeptId() != null) {
            revokeOutputs(bucket, task.getMainDeptId());
        }
        bucket.setStatus(BucketStatus.PENDING);
        clusterBucketMapper.updateById(bucket);
        reopenTask(bucketId, task);
        log.info("桶 {} 修正重审，回到 pending", bucketId);
    }

    /**
     * 交叉科室预填 = 桶方向 {推荐, 实际} − {主科室}。
     * 只做集合差，不校验科室是否存在：方向来自导诊记录，科室中途被删也不该让详情接口失败。
     */
    public List<String> prefillCrossDepts(ClusterBucket bucket, String mainDeptId) {
        List<String> direction = new ArrayList<>();
        if (bucket.getRecDeptId() != null && !bucket.getRecDeptId().equals(mainDeptId)) {
            direction.add(bucket.getRecDeptId());
        }
        if (bucket.getActualDeptId() != null && !bucket.getActualDeptId().equals(mainDeptId)
                && !bucket.getActualDeptId().equals(bucket.getRecDeptId())) {
            direction.add(bucket.getActualDeptId());
        }
        return direction;
    }

    // ---------- 内部 ----------

    private void indexSynthetic(String docId, String deptId, String title, String content) {
        try {
            KbChunk chunk = chunkIndexService.indexSyntheticChunk(docId, deptId, title, content);
            if (chunk == null) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "合成 chunk 入库没有返回结果");
            }
            kbDocService.markDone(docId, countChunks(docId));
            log.info("合成 chunk 入库完成：docId={} chunkId={}", docId, chunk.getId());
        } catch (RuntimeException e) {
            // 桶保持 approved，不把容器打成 failed：一个科室的回流容器是共享的，
            // 一次失败就把整容器标失败，会让这个科室此前已入库的回流看起来全部坏了。
            // 这条失败留在日志里，重试路径是对该桶发起修正重审后再审一次。
            log.error("合成 chunk 入库失败（桶保持 approved，重试走修正重审）：docId={}", docId, e);
        }
    }

    /** 容器里现有的切片数（markDone 要写 chunk_total，不能只算本次这一片） */
    private int countChunks(String docId) {
        return kbDocService.listChunks(docId).size();
    }

    private void revokeOutputs(ClusterBucket bucket, String previousMainDeptId) {
        deptMappingMapper.delete(Wrappers.<DeptMapping>lambdaQuery()
                .eq(DeptMapping::getSymptom, bucket.getAnchorText())
                .eq(DeptMapping::getMainDeptId, previousMainDeptId));
        KbDoc container = kbDocService.findFeedbackContainer(previousMainDeptId);
        if (container == null) {
            return;
        }
        String title = syntheticTitle(bucket.getId(), bucket.getAnchorText());
        List<String> chunkIds = kbDocService.listChunks(container.getId()).stream()
                .filter(chunk -> title.equals(chunk.getTitle()))
                .map(KbChunk::getId)
                .toList();
        for (String chunkId : chunkIds) {
            chunkIndexService.deleteChunk(chunkId);
        }
        if (kbDocService.listChunks(container.getId()).isEmpty()) {
            // 容器空了就回到 done + 0 片，别留着上一次的失败状态
            kbDocService.markDone(container.getId(), 0);
        }
        log.info("桶 {} 修正重审，已撤上次产出：台账键=({}, {}) 切片 {} 条",
                bucket.getId(), bucket.getAnchorText(), previousMainDeptId, chunkIds.size());
    }

    /**
     * 同键合并。按**物理行**判存在：修正重审会逻辑删除旧行，唯一键却还被它占着，
     * 只看逻辑存在就会在 INSERT 时撞 {@code uk_dm_symptom_main}。撞上的行直接复活并合并。
     */
    private void upsertMapping(String symptom, String mainDeptId, List<String> crossDeptIds) {
        DeptMapping existing = deptMappingMapper.selectByKeyIncludingDeleted(symptom, mainDeptId);
        String merged = toJson(merge(existing == null ? List.of() : parseIds(existing.getCrossDeptIds()), crossDeptIds));
        if (existing == null) {
            DeptMapping mapping = new DeptMapping();
            mapping.setSymptom(symptom);
            mapping.setMainDeptId(mainDeptId);
            mapping.setCrossDeptIds(merged);
            mapping.setSource(MappingSource.FEEDBACK);
            deptMappingMapper.insert(mapping);
            return;
        }
        existing.setCrossDeptIds(merged);
        existing.setSource(MappingSource.FEEDBACK);
        existing.setDeleted(0);
        deptMappingMapper.updateById(existing);
    }

    /** 合成切片的稳定标题：桶 id 在前，症状摘要在后（给人看，不参与匹配） */
    private String syntheticTitle(String bucketId, String symptom) {
        return "回流 · " + bucketId + " · " + abbreviate(symptom, TITLE_SYMPTOM_MAX);
    }

    private void close(String bucketId, BucketStatus status, String reviewedBy) {
        requirePending(bucketId);
        ClusterBucket update = new ClusterBucket();
        update.setId(bucketId);
        update.setStatus(status);
        clusterBucketMapper.updateById(update);
        finishTask(bucketId, null, reviewedBy);
        log.info("桶 {} 状态更新为 {}", bucketId, status.getCode());
    }

    private ClusterBucket requirePending(String bucketId) {
        ClusterBucket bucket = clusterBucketMapper.selectById(bucketId);
        if (bucket == null) {
            throw new BizException(ErrorCode.BUCKET_NOT_FOUND);
        }
        if (bucket.getStatus() != BucketStatus.PENDING) {
            throw new BizException(ErrorCode.BUCKET_STATUS_INVALID, "只有待审核的桶才能操作");
        }
        return bucket;
    }

    private Dept requireDept(String deptId) {
        Dept dept = deptService.getById(deptId);
        if (dept == null) {
            throw new BizException(ErrorCode.DEPT_NOT_FOUND);
        }
        return dept;
    }

    /** 去掉空值、主科室自身与重复项，保留管理员给出的顺序 */
    private List<String> normalizeDeptIds(List<String> deptIds, String mainDeptId) {
        if (deptIds == null || deptIds.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String id : deptIds) {
            if (id == null || id.isBlank() || id.equals(mainDeptId)) {
                continue;
            }
            if (deptService.getById(id.strip()) == null) {
                throw new BizException(ErrorCode.DEPT_NOT_FOUND);
            }
            unique.add(id.strip());
        }
        return new ArrayList<>(unique);
    }

    private List<String> merge(List<String> left, List<String> right) {
        Set<String> union = new LinkedHashSet<>();
        union.addAll(left);
        union.addAll(right);
        return new ArrayList<>(union);
    }

    /** 写回审核结果。走自定义 SQL 而不是 updateById：驳回时 mainDeptId 是 null，见 ReviewTaskMapper */
    private void finishTask(String bucketId, String mainDeptId, String reviewedBy) {
        ReviewTask task = taskOf(bucketId);
        if (task == null) {
            ReviewTask created = new ReviewTask();
            created.setBucketId(bucketId);
            created.setStatus(ReviewStatus.DONE);
            created.setMainDeptId(mainDeptId);
            created.setReviewedBy(reviewedBy);
            created.setReviewedAt(LocalDateTime.now());
            reviewTaskMapper.insert(created);
            return;
        }
        reviewTaskMapper.finish(task.getId(), reviewedBy, LocalDateTime.now(), mainDeptId);
    }

    /** 修正重审：审核痕迹三个字段都要写成空，同样不能走 updateById */
    private void reopenTask(String bucketId, ReviewTask task) {
        if (task == null) {
            ReviewTask created = new ReviewTask();
            created.setBucketId(bucketId);
            created.setStatus(ReviewStatus.PENDING);
            reviewTaskMapper.insert(created);
            return;
        }
        reviewTaskMapper.reopen(task.getId());
    }

    private ReviewTask taskOf(String bucketId) {
        return reviewTaskMapper.selectOne(Wrappers.<ReviewTask>lambdaQuery()
                .eq(ReviewTask::getBucketId, bucketId)
                .last("LIMIT 1"));
    }

    private boolean isTerminal(BucketStatus status) {
        return status == BucketStatus.APPROVED || status == BucketStatus.REJECTED
                || status == BucketStatus.DISMISSED;
    }

    /** 交叉科室为空时替换成空串，模板句就自然收在句号上 */
    private String crossText(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        List<String> present = names.stream().filter(name -> name != null && !name.isBlank()).toList();
        return present.isEmpty() ? "" : "，需与" + String.join("、", present) + "进行鉴别";
    }

    private String render(String template, String symptom, String mainDept, String cross) {
        return template
                .replace(PromptProperties.PLACEHOLDER_SYMPTOM, symptom == null ? "" : symptom)
                .replace(PromptProperties.PLACEHOLDER_MAIN_DEPT, mainDept == null ? "" : mainDept)
                .replace(PromptProperties.PLACEHOLDER_CROSS_DEPTS, cross == null ? "" : cross);
    }

    private List<String> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("交叉科室 JSON 无法解析，按空处理：{}", json);
            return List.of();
        }
    }

    private String toJson(List<String> ids) {
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "交叉科室序列化失败");
        }
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }

    /** 没有事务时直接跑（单测与已提交后的调用都走这里） */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
