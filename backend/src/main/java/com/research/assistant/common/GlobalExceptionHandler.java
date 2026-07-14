package com.research.assistant.common;

import com.research.assistant.service.async.AsyncTaskCapacityException;
import com.research.assistant.service.async.AsyncTaskIdempotencyConflictException;
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
