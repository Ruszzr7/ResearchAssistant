package com.research.assistant.common;

import com.research.assistant.service.async.AsyncTaskCapacityException;
import com.research.assistant.service.async.AsyncTaskIdempotencyConflictException;
import com.research.assistant.service.agent.runtime.AgentRuntimeConflictException;
import com.research.assistant.service.agent.source.PaperUnderstandingNotReadyException;
import com.research.assistant.service.pdf.layout.StaleLayoutArtifactException;
import com.research.assistant.service.translation.TranslationException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converts failures to safe, stable JSON without echoing provider or SQL details. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("request_validation_failed type={}", typeOf(e));
        return response(HttpStatus.BAD_REQUEST, "请求参数不合法");
    }

    @ExceptionHandler(PaperFileValidationException.class)
    public ResponseEntity<Result<Void>> handlePaperFileValidation(PaperFileValidationException e) {
        log.warn("paper_file_validation_failed");
        return response(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(AsyncTaskCapacityException.class)
    public ResponseEntity<Result<Void>> handleTaskCapacity(AsyncTaskCapacityException e) {
        log.warn("async_capacity_rejected type={}", typeOf(e));
        return response(HttpStatus.TOO_MANY_REQUESTS, "任务容量已达上限，请稍后重试");
    }

    @ExceptionHandler(AsyncTaskIdempotencyConflictException.class)
    public ResponseEntity<Result<Void>> handleIdempotencyConflict(AsyncTaskIdempotencyConflictException e) {
        log.warn("async_idempotency_conflict type={}", typeOf(e));
        return response(HttpStatus.CONFLICT, "幂等键与请求参数不匹配");
    }

    @ExceptionHandler(AgentRuntimeConflictException.class)
    public ResponseEntity<Result<Void>> handleAgentConflict(AgentRuntimeConflictException e) {
        log.warn("agent_runtime_conflict type={}", typeOf(e));
        return response(HttpStatus.CONFLICT, "当前对话已有任务正在处理，请等待完成后再发送");
    }

    @ExceptionHandler(PaperUnderstandingNotReadyException.class)
    public ResponseEntity<Result<Void>> handlePaperUnderstandingNotReady(PaperUnderstandingNotReadyException e) {
        log.warn("paper_understanding_not_ready");
        return response(HttpStatus.CONFLICT, "请先完成论文理解，再开始对话");
    }

    @ExceptionHandler(StaleLayoutArtifactException.class)
    public ResponseEntity<Result<Void>> handleStaleLayoutArtifact(StaleLayoutArtifactException e) {
        log.warn("stale_layout_artifact");
        return response(HttpStatus.CONFLICT, "PDF 已更新，请重新选择内容");
    }

    @ExceptionHandler(TranslationException.class)
    public ResponseEntity<Result<Void>> handleTranslation(TranslationException e) {
        log.warn("translation_failed code={} retryable={}", e.code(), e.retryable());
        return response(e.status(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String field = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField())
                .findFirst()
                .orElse("request");
        log.warn("request_validation_failed field={}", field);
        return response(HttpStatus.BAD_REQUEST, "请求参数校验失败: " + field);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException e) {
        log.warn("constraint_validation_failed count={}", e.getConstraintViolations().size());
        return response(HttpStatus.BAD_REQUEST, "请求参数校验失败");
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Result<Void>> handleMalformedRequest(Exception e) {
        log.warn("malformed_request type={}", typeOf(e));
        return response(HttpStatus.BAD_REQUEST, "请求格式或参数类型错误");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("request_upload_too_large");
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件超过大小限制");
    }

    @ExceptionHandler(DuplicatePaperException.class)
    public ResponseEntity<Result<Map<String, Object>>> handleDuplicatePaper(DuplicatePaperException e) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paperId", e.getExistingPaperId());
        data.put("title", e.getExistingTitle());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Result.error(HttpStatus.CONFLICT.value(), "文献已存在，是否覆盖？"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("http_method_not_supported method={}", e.getMethod());
        return response(HttpStatus.METHOD_NOT_ALLOWED, "不支持的请求方法");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> handleDataAccess(DataAccessException e) {
        log.error("database_operation_failed type={} requestId={}", typeOf(e), requestId());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "数据库操作失败，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("internal_server_error type={} requestId={}", typeOf(e), requestId());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请稍后重试");
    }

    private ResponseEntity<Result<Void>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Result.error(status.value(), message));
    }

    private String typeOf(Exception e) {
        return e == null ? "unknown" : e.getClass().getSimpleName();
    }

    private String requestId() {
        return MDC.get("requestId");
    }
}
