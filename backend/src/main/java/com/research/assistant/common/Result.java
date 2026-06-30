package com.research.assistant.common;

/**
 * 统一 API 响应格式：{ code, message, data }。
 * 所有 Controller 返回此类型，前端依 code 判断成功/失败。
 */
public class Result<T> {
    private int code;
    private String message;
    private T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功（带数据） */
    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    /** 成功（无数据，如删除操作） */
    public static <T> Result<T> ok() {
        return new Result<>(200, "success", null);
    }

    /** 失败（自定义 code + message） */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    // ---- getter / setter ----
    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
}
