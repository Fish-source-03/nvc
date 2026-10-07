package org.example.agent_qr.web.config;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import lombok.extern.slf4j.Slf4j;
import org.example.agent_qr.common.BusinessException;
import org.example.agent_qr.common.Result;
import org.slf4j.MDC;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import jakarta.servlet.http.HttpServletResponse;
import java.util.stream.Collectors;

/**
 * 全局异常处理器（P2 扩展：TraceId 追踪）。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("[traceId={}] 业务异常: {}", MDC.get("traceId"), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return Result.error(400, msg);
    }

    // ★ 问题 41：AccessDeniedException 的处理器已移除 —— 唯一处理者改为
    //   agent-qr-auth 的 AbacAccessDeniedHandler（携带 uri/method 等审计信息，
    //   并统一返回 HTTP 403 + Result 结构）。此处保留兜底 Exception 处理器，
    //   但 AbacAccessDeniedHandler 标注了 @Order(HIGHEST_PRECEDENCE)，
    //   解析优先级高于本类的兜底方法。

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof InvalidFormatException ife) {
            String fieldPath = extractFieldPath(ife);
            String msg = String.format("字段类型错误: %s 应为 %s 类型",
                    fieldPath, ife.getTargetType().getSimpleName());
            log.warn("[traceId={}] JSON 字段类型错误: {}", MDC.get("traceId"), msg);
            return Result.error(400, msg);
        }
        log.warn("[traceId={}] 请求体不可读: {}", MDC.get("traceId"), e.getMessage());
        return Result.error(400, "请求体 JSON 格式错误，请检查语法");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public Result<Void> handleMissingServletRequestPart(MissingServletRequestPartException e) {
        log.warn("[traceId={}] 缺少必传参数: {}", MDC.get("traceId"), e.getMessage());
        String partName = e.getRequestPartName();
        String msg = "file".equals(partName)
                ? "缺少必传文件，请选择文件后上传"
                : "缺少必传参数: " + partName;
        return Result.error(400, msg);
    }

    /**
     * 查询参数/路径变量类型不匹配（批次 11 · R35）。
     * <p>
     * 修复前 {@code ?sensitivityLevel=abc} / {@code ?page=abc} 没有处理器，
     * 落入本类兜底 {@code Exception} 分支 → {@code code=500}「服务器内部错误」——
     * <b>客户端参数错误被报成服务器故障</b>，前端提示与排障都被误导。
     * 现按客户端错误语义返回 {@code code=400}，并保留具体参数名与目标类型便于定位。
     * </p>
     * <p>
     * 与 {@code MethodArgumentNotValidException} 等既有处理器一致采用
     * HTTP 200 + body code=400 的统一 Result 约定（前端拦截器按业务码分支处理）；
     * 故不额外标注 {@code @ResponseStatus}。
     * </p>
     *
     * @param e 参数类型不匹配异常
     * @return 统一错误结果（code=400）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        Class<?> requiredType = e.getRequiredType();
        String expected = requiredType != null ? requiredType.getSimpleName() : "期望类型";
        String msg = String.format("参数 '%s' 取值非法，应为 %s 类型", e.getName(), expected);
        log.warn("[traceId={}] 请求参数类型错误: {}", MDC.get("traceId"), msg);
        return Result.error(400, msg);
    }

    /**
     * 缺少必传的请求参数（批次 11 · R35 同族补漏）。
     * <p>
     * 与类型不匹配同属"客户端请求错误"，此前同样被兜底分支报成 500；
     * 现统一返回 {@code code=400}，避免把可自愈的调用错误报成服务器故障。
     * </p>
     *
     * @param e 缺少请求参数异常
     * @return 统一错误结果（code=400）
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingServletRequestParameter(MissingServletRequestParameterException e) {
        String msg = String.format("缺少必传参数 '%s'（%s 类型）",
                e.getParameterName(), e.getParameterType());
        log.warn("[traceId={}] 缺少必传参数: {}", MDC.get("traceId"), msg);
        return Result.error(400, msg);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        log.warn("[traceId={}] 客户端连接已断开，SSE 传输中断: {}", MDC.get("traceId"), e.getMessage());
        // SSE 场景下 Content-Type 已锁定为 text/event-stream，无法写入 JSON 响应体，直接返回 void
    }

    @ExceptionHandler(Exception.class)
    public Object handleException(Exception e, HttpServletResponse response) {
        log.error("[traceId={}] 系统异常", MDC.get("traceId"), e);
        // SSE 流式响应：Content-Type 已锁定为 text/event-stream，无法写入 JSON 响应体
        if (response.getContentType() != null && response.getContentType().contains("text/event-stream")) {
            log.warn("[traceId={}] SSE 异常已记录，跳过 JSON 响应写入", MDC.get("traceId"));
            return null;
        }
        return Result.error(500, "服务器内部错误，请稍后重试");
    }

    private String extractFieldPath(InvalidFormatException e) {
        String ref = e.getPathReference();
        if (ref != null) {
            int lastBracket = ref.lastIndexOf("[\"");
            if (lastBracket >= 0) {
                int end = ref.indexOf("\"]", lastBracket);
                if (end >= 0) {
                    return ref.substring(lastBracket + 2, end) + " 字段";
                }
            }
            int lastDot = ref.lastIndexOf('.');
            if (lastDot >= 0) {
                return ref.substring(lastDot + 1);
            }
        }
        return "请求参数";
    }
}
