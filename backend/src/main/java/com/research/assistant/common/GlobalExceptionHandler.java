package com.research.assistant.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.research.assistant.service.async.AsyncTaskCapacityException;
import com.research.assistant.service.async.AsyncTaskIdempotencyConflictException;

/**
 * 全局异常处理器 —— 将所有未捕获异常统一包装为 {@link Result} 返回。
 * <p>
 * 避免 Controller 抛出的 RuntimeException 直接返回 Tomcat 默认 500 页面，
 * 让前端始终能解析统一响应结构。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        return Result.error(400, e.getMessage());
    }

    @ExceptionHandler(AsyncTaskCapacityException.class)
    public Result<Void> handleTaskCapacity(AsyncTaskCapacityException e) {
        log.warn("异步任务容量达到上限: {}", e.getMessage());
        return Result.error(429, e.getMessage());
    }

    @ExceptionHandler(AsyncTaskIdempotencyConflictException.class)
    public Result<Void> handleIdempotencyConflict(AsyncTaskIdempotencyConflictException e) {
        log.warn("异步任务幂等键冲突: {}", e.getMessage());
        return Result.error(409, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("请求参数校验失败");
        log.warn("参数校验失败: {}", message);
        return Result.error(400, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return Result.error(400, "请求体格式错误");
    }

    @ExceptionHandler(DataAccessException.class)
    public Result<Void> handleDataAccess(DataAccessException e) {
        log.error("数据库访问异常", e);
        return Result.error(500, "数据库操作失败，请稍后重试");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("服务器内部错误", e);
        return Result.error(500, "服务器内部错误: " + e.getMessage());
    }
}
