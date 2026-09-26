package com.guide.admin.controller;

import com.guide.admin.dto.KbAdminDTO;
import com.guide.admin.service.KbAdminService;
import com.guide.common.api.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 管理端知识库接口（链路 B 上传流水线，仅 ROLE_ADMIN）。
 *
 * <p>接口形状跟着**上传的校验边界**走：判定权在后端——扩展名白名单、文件大小、
 * "该文档是不是正在处理中"都在服务端判，前端只做即时提示。
 *
 * <p>**没有"批量上传"这个接口形态**：一次请求只处理一个文件，多个文件由前端循环发起。
 * 这样每个文件有独立的事务边界、独立的 taskId、独立的失败，**部分成功不需要任何额外设计**
 * （传 10 个挂 3 个，用户看到 3 条错误 + 7 条正常记录）。把"批量"塞进一个请求，
 * 等于让后端承担"部分成功"这个新状态空间，再让管理端表达一遍。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/kb")
@RequiredArgsConstructor
public class KbAdminController {

    private final KbAdminService kbAdminService;

    /** 上传文档并开始入库（同步落 MinIO，异步入库；返回 taskId 供前端轮询进度） */
    @PostMapping("/docs")
    public Result<KbAdminDTO.IngestVO> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam("deptId") String deptId,
                                              @RequestParam(value = "title", required = false) String title) {
        return Result.ok(kbAdminService.upload(file, deptId, title));
    }

    /** 文档分页列表（含最近一次运行的阶段与真实进度量） */
    @GetMapping("/docs")
    public Result<KbAdminDTO.PageVO<KbAdminDTO.DocVO>> docs(@RequestParam(defaultValue = "1") long page,
                                                            @RequestParam(defaultValue = "10") long size,
                                                            @RequestParam(required = false) String deptId,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) String keyword) {
        return Result.ok(kbAdminService.page(deptId, status, keyword, page, size));
    }

    /** 单次运行的进度（前端上传后按 taskId 轮询） */
    @GetMapping("/tasks/{taskId}")
    public Result<KbAdminDTO.TaskVO> task(@PathVariable String taskId) {
        return Result.ok(kbAdminService.task(taskId));
    }

    /** 查看切片 */
    @GetMapping("/docs/{docId}/chunks")
    public Result<List<KbAdminDTO.ChunkVO>> chunks(@PathVariable String docId) {
        return Result.ok(kbAdminService.chunks(docId));
    }

    /** 重新处理（先删旧切片再重建，用 MinIO 里的原文件） */
    @PostMapping("/docs/{docId}/reprocess")
    public Result<KbAdminDTO.IngestVO> reprocess(@PathVariable String docId) {
        return Result.ok(kbAdminService.reprocess(docId));
    }

    /** 删除文档（先终止在飞任务，再走删除补偿） */
    @DeleteMapping("/docs/{docId}")
    public Result<Void> delete(@PathVariable String docId) {
        kbAdminService.delete(docId);
        return Result.ok();
    }

    /** 科室蓝本（科室 tab 与上传表单共用；含停用科室，停用只在导诊入口生效） */
    @GetMapping("/depts")
    public Result<List<KbAdminDTO.DeptVO>> depts() {
        return Result.ok(kbAdminService.depts());
    }

    /** 编辑科室蓝本：改名 / 位置 / 简介 / 启停（科室名全库唯一，模型按名字回填科室实体） */
    @PutMapping("/depts/{deptId}")
    public Result<Void> updateDept(@PathVariable String deptId,
                                   @Valid @RequestBody KbAdminDTO.DeptUpdateReq request) {
        kbAdminService.updateDept(deptId, request);
        return Result.ok();
    }

    /** 症状交叉映射台账（只读：台账是审核事实的留痕，不做增删改） */
    @GetMapping("/mappings")
    public Result<KbAdminDTO.PageVO<KbAdminDTO.MappingVO>> mappings(@RequestParam(defaultValue = "1") long page,
                                                                    @RequestParam(defaultValue = "10") long size,
                                                                    @RequestParam(required = false) String keyword) {
        return Result.ok(kbAdminService.mappings(keyword, page, size));
    }

    /** 术语白名单（含停用；enabled 不传即全部） */
    @GetMapping("/terms")
    public Result<KbAdminDTO.PageVO<KbAdminDTO.TermVO>> terms(@RequestParam(defaultValue = "1") long page,
                                                              @RequestParam(defaultValue = "10") long size,
                                                              @RequestParam(required = false) String keyword,
                                                              @RequestParam(required = false) Boolean enabled) {
        return Result.ok(kbAdminService.terms(keyword, enabled, page, size));
    }

    /** 启用/停用术语：立即刷新内存白名单（chat 入口的防误杀闸门） */
    @PostMapping("/terms/{termId}/toggle")
    public Result<KbAdminDTO.TermVO> toggleTerm(@PathVariable String termId) {
        return Result.ok(kbAdminService.toggleTerm(termId));
    }
}
