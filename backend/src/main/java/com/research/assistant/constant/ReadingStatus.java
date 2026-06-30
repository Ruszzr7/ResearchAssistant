package com.research.assistant.constant;

/**
 * 论文阅读状态常量 —— 消除魔法值。
 *
 * @author ResearchAssistant
 */
public final class ReadingStatus {

    /** 未读 */
    public static final String UNREAD = "UNREAD";

    /** 略读 */
    public static final String SKIMMED = "SKIMMED";

    /** 精读 */
    public static final String CLOSE_READ = "CLOSE_READ";

    /** 已归档 */
    public static final String ARCHIVED = "ARCHIVED";

    private ReadingStatus() {
        // 工具类不允许实例化
    }
}
