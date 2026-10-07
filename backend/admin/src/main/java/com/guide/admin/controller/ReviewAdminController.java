package com.guide.admin.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.guide.admin.dto.ReviewAdminDTO;
import com.guide.auth.security.LoginUser;
import com.guide.auth.security.LoginUserHolder;
import com.guide.common.api.ErrorCode;
import com.guide.common.api.Result;
import com.guide.common.exception.BizException;
import com.guide.feedback.entity.ClusterBucket;
import com.guide.feedback.enums.RootCauseKey;
import com.guide.feedback.scheduler.AggregationScheduler;
import com.guide.feedback.service.ApprovalService;
import com.guide.feedback.service.ClusteringService;
import com.guide.feedback.service.ReviewService;
import com.guide.feedback.service.RootCauseService;
import com.guide.kb.entity.Dept;
import com.guide.kb.service.DeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审核队列（链路 C，仅 ROLE_ADMIN）。
 *
 * <p>编排放在 admin：科室名的拼装是展示层的事，归桶、归因、产出的规则各在 feedback。
 * 操作人取登录用户 id，不取 {@code Principal.getName()}——那是用户名，和库里其他
 * {@code reviewed_by} 的口径对不上。
 */
@RestController
@RequestMapping("/api/admin/review")
@RequiredArgsConstructor
public class ReviewAdminController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ReviewService reviewService;
    private final RootCauseService rootCauseService;
    private final ApprovalService approvalService;
    private final DeptService deptService;
    private final AggregationScheduler aggregationScheduler;
    /**
     * 计数直接问 feedback 的归桶服务，而不是让 ReviewService 转一手：
     * 「哪些记录算待归桶」这条规则的属主是 {@code ClusteringService}，
     * 多一层转发只会多一个会过期的地方。
     */
    private final ClusteringService clusteringService;

    /** 立即聚合：归桶是整点定时任务，演示与排查等不了那一小时。返回本次成功归桶的记录数 */
    @PostMapping("/aggregate")
    public Result<Integer> aggregate() {
        return Result.ok(aggregationScheduler.aggregateNow());
    }

    /**
     * 待归桶记录数：审核页顶部那行提示用。
     * 与 {@code /aggregate} 实际会扫到的集合同源，见 {@code ClusteringService#countPending()}。
     *
     * <p>路径用 records 不用 samples：词表里「样本」是**桶内代表样本**（review 时看的成员），
     * 这里是还没归桶的**导诊记录**，两个概念不能共用一个词。
     */
    @GetMapping("/pending-records/count")
    public Result<Long> pendingRecordCount() {
        return Result.ok(clusteringService.countPending());
    }

    /** 待审桶（按样本数降序） */
    @GetMapping("/pending")
    public Result<ReviewAdminDTO.PageVO<ReviewAdminDTO.BucketVO>> pending(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Page<ClusterBucket> result = reviewService.listPendingBuckets(
                (int) Math.max(1, page), (int) Math.min(MAX_PAGE_SIZE, Math.max(1, size)));
        Map<String, Dept> depts = deptIndex();
        List<ReviewAdminDTO.BucketVO> records = new ArrayList<>();
        for (ClusterBucket bucket : result.getRecords()) {
            records.add(toBucket(bucket, depts));
        }
        ReviewAdminDTO.PageVO<ReviewAdminDTO.BucketVO> vo = new ReviewAdminDTO.PageVO<>();
        vo.setTotal(result.getTotal());
        vo.setRecords(records);
        return Result.ok(vo);
    }

    /** 终态桶：修正重审从这里进。已 approved 的重审会撤掉上次的台账与合成 chunk */
    @GetMapping("/terminal")
    public Result<ReviewAdminDTO.PageVO<ReviewAdminDTO.BucketVO>> terminal(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Page<ClusterBucket> result = reviewService.listTerminalBuckets(
                (int) Math.max(1, page), (int) Math.min(MAX_PAGE_SIZE, Math.max(1, size)));
        Map<String, Dept> depts = deptIndex();
        List<ReviewAdminDTO.BucketVO> records = new ArrayList<>();
        for (ClusterBucket bucket : result.getRecords()) {
            records.add(toBucket(bucket, depts));
        }
        ReviewAdminDTO.PageVO<ReviewAdminDTO.BucketVO> vo = new ReviewAdminDTO.PageVO<>();
        vo.setTotal(result.getTotal());
        vo.setRecords(records);
        return Result.ok(vo);
    }

    /** 待审数量：侧栏徽标。没有待审就是 0，不写死一个数字 */
    @GetMapping("/pending/count")
    public Result<Long> pendingCount() {
        return Result.ok(reviewService.countPending());
    }

    /** 桶详情：代表样本 + 证据快照 + 交叉科室预填 */
    @GetMapping("/buckets/{id}")
    public Result<ReviewAdminDTO.BucketDetailVO> detail(@PathVariable String id) {
        ReviewService.BucketDetail detail = reviewService.getBucketDetail(id);
        if (detail == null) {
            return Result.fail(ErrorCode.BUCKET_NOT_FOUND);
        }
        Map<String, Dept> depts = deptIndex();
        ReviewAdminDTO.BucketDetailVO vo = new ReviewAdminDTO.BucketDetailVO();
        vo.setBucket(toBucket(detail.bucket(), depts));
        // 主科室还没选：用实际科室当建议值（患者挂去的那个科），预填按它做差
        String suggestedMain = detail.bucket().getActualDeptId();
        vo.setSuggestedMainDeptId(suggestedMain);
        vo.setSuggestedCrossDeptIds(approvalService.prefillCrossDepts(detail.bucket(), suggestedMain));
        List<ReviewAdminDTO.SampleVO> samples = new ArrayList<>();
        for (ReviewService.RepresentativeSample sample : detail.samples()) {
            ReviewAdminDTO.SampleVO item = new ReviewAdminDTO.SampleVO();
            item.setRecordId(sample.recordId());
            item.setSymptom(sample.symptom());
            item.setEvidence(sample.evidence());
            item.setCauses(sample.causes());
            samples.add(item);
        }
        vo.setSamples(samples);
        return Result.ok(vo);
    }

    /** 根因字典：页面只显示，不在前端再抄一份 */
    @GetMapping("/causes")
    public Result<List<ReviewAdminDTO.CauseOptionVO>> causes() {
        List<ReviewAdminDTO.CauseOptionVO> options = new ArrayList<>();
        for (RootCauseKey key : RootCauseKey.values()) {
            ReviewAdminDTO.CauseOptionVO option = new ReviewAdminDTO.CauseOptionVO();
            option.setKey(key.getKey());
            option.setLabel(key.getLabel());
            options.add(option);
        }
        return Result.ok(options);
    }

    /** 科室选择器：含停用科室（审核是知识层动作，与科室开不开诊无关） */
    @GetMapping("/depts")
    public Result<List<ReviewAdminDTO.DeptOptionVO>> depts() {
        List<ReviewAdminDTO.DeptOptionVO> options = new ArrayList<>();
        for (Dept dept : deptService.listAll()) {
            ReviewAdminDTO.DeptOptionVO option = new ReviewAdminDTO.DeptOptionVO();
            option.setId(dept.getId());
            option.setName(dept.getName());
            option.setEnabled(dept.getEnabled() != null && dept.getEnabled() == 1);
            options.add(option);
        }
        return Result.ok(options);
    }

    /** 桶级套用根因（独立保存，不改桶状态） */
    @PostMapping("/buckets/{id}/causes")
    public Result<Integer> applyCauses(@PathVariable String id,
                                        @RequestBody ReviewAdminDTO.CausesRequest request) {
        int affected = rootCauseService.applyToBucket(id, request.getCauses(), currentUserId());
        return Result.ok(affected);
    }

    /** 逐条覆盖根因 */
    @PostMapping("/records/{id}/causes")
    public Result<Void> updateRecordCauses(@PathVariable String id,
                                            @RequestBody ReviewAdminDTO.CausesRequest request) {
        rootCauseService.updateSingle(id, request.getCauses(), currentUserId());
        return Result.ok();
    }

    /** 预览合成 chunk。症状取桶的锚点文本，科室名由后端查，不信任请求体里的名字 */
    @PostMapping("/buckets/{id}/preview")
    public Result<ReviewAdminDTO.PreviewVO> preview(@PathVariable String id,
                                                     @RequestBody ReviewAdminDTO.PreviewRequest request) {
        ClusterBucket bucket = requireBucket(id);
        Dept main = requireDept(request.getMainDeptId());
        List<String> crossIds = request.getCrossDeptIds() == null
                ? approvalService.prefillCrossDepts(bucket, main.getId())
                : request.getCrossDeptIds();
        List<String> crossNames = new ArrayList<>();
        for (String crossId : crossIds) {
            if (crossId == null || crossId.equals(main.getId())) {
                continue;
            }
            crossNames.add(requireDept(crossId).getName());
        }
        ReviewAdminDTO.PreviewVO vo = new ReviewAdminDTO.PreviewVO();
        vo.setText(approvalService.preview(bucket.getAnchorText(), main.getName(), crossNames));
        return Result.ok(vo);
    }

    /** 确认 approve：台账同事务，合成 chunk 异步入库 */
    @PostMapping("/buckets/{id}/approve")
    public Result<Void> approve(@PathVariable String id,
                                 @RequestBody ReviewAdminDTO.ApproveRequest request) {
        ClusterBucket bucket = requireBucket(id);
        approvalService.approve(id, bucket.getAnchorText(), request.getMainDeptId(),
                request.getCrossDeptIds(), request.getSyntheticText(), currentUserId());
        return Result.ok();
    }

    @PostMapping("/buckets/{id}/reject")
    public Result<Void> reject(@PathVariable String id) {
        approvalService.reject(id, currentUserId());
        return Result.ok();
    }

    @PostMapping("/buckets/{id}/dismiss")
    public Result<Void> dismiss(@PathVariable String id) {
        approvalService.dismiss(id, currentUserId());
        return Result.ok();
    }

    /** 修正重审：终态桶回到 pending，已 approved 的撤掉上次产物 */
    @PostMapping("/buckets/{id}/re-review")
    public Result<Void> reReview(@PathVariable String id) {
        approvalService.reReview(id);
        return Result.ok();
    }

    // ---------- 内部 ----------

    private ClusterBucket requireBucket(String id) {
        ClusterBucket bucket = reviewService.requireBucket(id);
        if (bucket == null) {
            throw new BizException(ErrorCode.BUCKET_NOT_FOUND);
        }
        return bucket;
    }

    private Dept requireDept(String id) {
        Dept dept = deptService.getById(id);
        if (dept == null) {
            throw new BizException(ErrorCode.DEPT_NOT_FOUND);
        }
        return dept;
    }

    private Map<String, Dept> deptIndex() {
        Map<String, Dept> index = new HashMap<>();
        for (Dept dept : deptService.listAll()) {
            index.put(dept.getId(), dept);
        }
        return index;
    }

    private ReviewAdminDTO.BucketVO toBucket(ClusterBucket bucket, Map<String, Dept> depts) {
        ReviewAdminDTO.BucketVO vo = new ReviewAdminDTO.BucketVO();
        vo.setId(bucket.getId());
        vo.setRecDeptId(bucket.getRecDeptId());
        vo.setRecDeptName(deptName(depts, bucket.getRecDeptId()));
        vo.setActualDeptId(bucket.getActualDeptId());
        vo.setActualDeptName(deptName(depts, bucket.getActualDeptId()));
        vo.setAnchorText(bucket.getAnchorText());
        vo.setCount(bucket.getCount());
        vo.setStatus(bucket.getStatus() == null ? null : bucket.getStatus().getCode());
        vo.setCreatedAt(bucket.getCreatedAt());
        return vo;
    }

    private String deptName(Map<String, Dept> depts, String id) {
        Dept dept = id == null ? null : depts.get(id);
        if (dept == null) {
            return "（科室已删除）";
        }
        boolean enabled = dept.getEnabled() != null && dept.getEnabled() == 1;
        return enabled ? dept.getName() : dept.getName() + "（已停用）";
    }

    private String currentUserId() {
        return LoginUserHolder.current()
                .map(LoginUser::userId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED));
    }
}
