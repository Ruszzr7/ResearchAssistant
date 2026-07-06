package com.research.assistant.constant;

/**
 * 论文阅读状态常量 —— 消除魔法值。
 *
 * @author ResearchAssistant
 */
public final class ReadingStatus {

    /** 未读 */
    public static final String UNREAD = "UNREAD";

    /** 正读（正在阅读） */
    public static final String READING = "READING";

    /** 已读 */
    public static final String READ = "READ";

    private ReadingStatus() {
        // 工具类不允许实例化
    }
}
