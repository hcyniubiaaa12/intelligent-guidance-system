package com.guide.common.exception;

import com.guide.common.api.ErrorCode;
import com.guide.common.api.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理：业务异常与非受控异常统一转 Result。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 上传上限取自 multipart 配置，保证「提示的阈值」与「实际拦下的大小」是同一个数 */
    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private String maxFileSize;

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 上传超限：multipart 的拒绝发生在 **controller 之前**，不接这一手的话
     * 管理员看到的是一个跟本系统毫无关系的错误页（见链路 B「上传的校验边界」）。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUpload(MaxUploadSizeExceededException e) {
        log.warn("上传被 multipart 上限拦下：{}", e.getMessage());
        return Result.fail(ErrorCode.FILE_TOO_LARGE.getCode(), "文件过大，单个文件不超过 " + maxFileSize);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst()
                .orElse(ErrorCode.PARAM_INVALID.getMessage());
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        log.error("unhandled exception", e);
        return Result.fail(ErrorCode.INTERNAL_ERROR);
    }
}
