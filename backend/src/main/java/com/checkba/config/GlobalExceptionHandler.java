package com.checkba.config;

import com.checkba.exception.FeatureNotConfiguredException;
import com.checkba.exception.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 * 统一处理所有异常，返回统一的错误响应格式
 * 所有接口统一返回 HTTP 200，通过响应体中的 code 字段区分成功（code=0）或失败（code=1）
 */
@ControllerAdvice
@lombok.extern.slf4j.Slf4j
public class GlobalExceptionHandler {

    /**
     * 未登录/会话过期的机器可读码（PR4-0）。前端 api.js 只认这个 code 来清会话/跳登录，
     * 不再做「登录/未授权/请先」中文子串匹配——那套判定会误伤含「请先」的业务文案，
     * 且后端文案英文化后整条失效。
     */
    public static final int CODE_UNAUTHENTICATED = 4010;

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorizedException(UnauthorizedException e) {
        log.warn("GlobalExceptionHandler caught UnauthorizedException: {}", e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("code", CODE_UNAUTHENTICATED);
        result.put("message", e.getMessage() != null ? e.getMessage() : "请先登录");
        // 统一返回 HTTP 200，通过 code 字段表示失败
        return ResponseEntity.ok().body(result);
    }

    /**
     * 功能未配置：返回可识别的 code=4001 + feature 字段，前端据此引导"去设置"
     * 而非显示通用报错。/ Feature not configured — return a recognizable
     * code=4001 with a feature id so the frontend can prompt "go to settings".
     */
    @ExceptionHandler(FeatureNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handleFeatureNotConfigured(FeatureNotConfiguredException e) {
        log.info("Feature not configured [{}]: {}", e.getFeature(), e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("code", 4001);
        result.put("feature", e.getFeature());
        result.put("configured", false);
        result.put("message", e.getMessage());
        // 统一返回 HTTP 200，通过 code=4001 表示"功能未配置"
        return ResponseEntity.ok().body(result);
    }

    /**
     * 文件缓存区免费额度已满：code=4003 + feature=stage.unlimited，前端据此显示解锁引导。
     * 与 4001（功能未配置）区分开——这里功能是好的，只是额度到顶了，下一步是解锁而非去设置。
     */
    @ExceptionHandler(com.checkba.exception.StageQuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleStageQuota(com.checkba.exception.StageQuotaExceededException e) {
        log.info("文件缓存区额度已满: {}", e.getMessage());
        Map<String, Object> result = new HashMap<>();
        result.put("code", 4003);
        result.put("feature", com.checkba.service.entitlement.FeatureCatalog.STAGE_UNLIMITED);
        result.put("message", e.getMessage());
        Map<String, Object> usage = new HashMap<>();
        usage.put("fileCount", e.getFileCount());
        usage.put("totalBytes", e.getTotalBytes());
        usage.put("maxFiles", e.getMaxFiles());
        usage.put("maxBytes", e.getMaxBytes());
        result.put("usage", usage);
        return ResponseEntity.ok().body(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("GlobalExceptionHandler caught IllegalArgumentException: {}", e.getMessage());
        Map<String, Object> result = new HashMap<>();
        // 全站 ~100 处鉴权失败写的是 throw new IllegalArgumentException("未登录")，
        // 判定收敛在这一处：message 恰为这两个字面量时视为未登录，回 4010。
        // 只做精确匹配，不做子串——「请先选择文件」这类业务提示必须仍是 code=1。
        boolean isAuthFailure = "未登录".equals(e.getMessage()) || "请先登录".equals(e.getMessage());
        result.put("code", isAuthFailure ? CODE_UNAUTHENTICATED : 1);
        result.put("message", e.getMessage() != null ? e.getMessage() : "请求参数错误");
        // 统一返回 HTTP 200，通过 code 字段表示失败
        return ResponseEntity.ok().body(result);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("GlobalExceptionHandler caught Exception: ", e);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 1);
        // 不回显内部异常 message（可能含 SQL/表名/文件路径/SDK 细节等），统一通用文案；详情仅进日志
        result.put("message", "服务器内部错误");
        // 统一返回 HTTP 200，通过 code 字段表示失败
        return ResponseEntity.ok().body(result);
    }
}

