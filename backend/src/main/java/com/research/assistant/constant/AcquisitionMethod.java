package com.research.assistant.constant;

/**
 * 论文获取方式常量。
 *
 * @author ResearchAssistant
 */
public final class AcquisitionMethod {

    /** 开放获取（Agent 自动下载 PDF） */
    public static final String OA = "OA";

    /** 用户手动上传 */
    public static final String MANUAL_UPLOAD = "MANUAL_UPLOAD";

    /** 浏览器下载（从付费页面手动保存） */
    public static final String BROWSER_DOWNLOAD = "BROWSER_DOWNLOAD";

    private AcquisitionMethod() {
        // 工具类不允许实例化
    }
}
