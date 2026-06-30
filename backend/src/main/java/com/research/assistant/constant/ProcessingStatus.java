package com.research.assistant.constant;

/**
 * Agent 异步处理状态常量。
 *
 * @author ResearchAssistant
 */
public final class ProcessingStatus {

    /** 等待处理 */
    public static final String PENDING = "PENDING";

    /** 处理中 */
    public static final String PROCESSING = "PROCESSING";

    /** 已完成 */
    public static final String COMPLETED = "COMPLETED";

    /** 处理失败 */
    public static final String FAILED = "FAILED";

    private ProcessingStatus() {
        // 工具类不允许实例化
    }
}
