package com.research.assistant.service.ai;

import dev.langchain4j.model.output.structured.Description;

import java.util.List;

/**
 * Agent 推荐类结构化输出 POJO。
 * <p>
 * 用于标签、文件夹、阅读状态等 Agent 推荐场景，配合 LangChain4j 的 JSON schema 输出。
 */
public class SuggestionPojos {

    private SuggestionPojos() {}

    /**
     * 标签建议结果。
     */
    public static class TagSuggestionResult {

        @Description("3-5 个精准的技术关键词标签，英文优先，用列表返回")
        private List<String> tags;

        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
    }

    /**
     * 文件夹推荐结果。
     */
    public static class FolderSuggestionResult {

        @Description("推荐使用的现有文件夹 ID；如果没有合适文件夹则返回 null")
        private Long folderId;

        @Description("推荐理由，一句话")
        private String reason;

        @Description("是否建议新建文件夹")
        private boolean suggestNew;

        @Description("若建议新建文件夹，给出建议名称")
        private String newName;

        @Description("若建议新建子文件夹，填写现有父文件夹 ID；根目录新建时为 null")
        private Long parentFolderId;

        public Long getFolderId() { return folderId; }
        public void setFolderId(Long folderId) { this.folderId = folderId; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public boolean isSuggestNew() { return suggestNew; }
        public void setSuggestNew(boolean suggestNew) { this.suggestNew = suggestNew; }

        public String getNewName() { return newName; }
        public void setNewName(String newName) { this.newName = newName; }

        public Long getParentFolderId() { return parentFolderId; }
        public void setParentFolderId(Long parentFolderId) { this.parentFolderId = parentFolderId; }
    }

    /**
     * 阅读状态推荐结果。
     */
    public static class ReadingStatusSuggestionResult {

        @Description("推荐的阅读状态：UNREAD（未读）/ READING（正在阅读）/ READ（已读）")
        private String status;

        @Description("推荐理由，一句话")
        private String reason;

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
