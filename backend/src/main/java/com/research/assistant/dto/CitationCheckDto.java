package com.research.assistant.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 引用推荐与冲突检查响应。
 */
public class CitationCheckDto {

    private List<Suggestion> suggestions = new ArrayList<>();
    private List<Conflict> conflicts = new ArrayList<>();

    public List<Suggestion> getSuggestions() { return suggestions; }
    public void setSuggestions(List<Suggestion> suggestions) { this.suggestions = suggestions; }

    public List<Conflict> getConflicts() { return conflicts; }
    public void setConflicts(List<Conflict> conflicts) { this.conflicts = conflicts; }

    /**
     * 推荐引用。
     */
    public static class Suggestion {
        private Long paperId;
        private String paperTitle;
        private String reason;
        private String position;
        private String evidenceId;
        private String source;
        private String locator;

        public Long getPaperId() { return paperId; }
        public void setPaperId(Long paperId) { this.paperId = paperId; }

        public String getPaperTitle() { return paperTitle; }
        public void setPaperTitle(String paperTitle) { this.paperTitle = paperTitle; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public String getPosition() { return position; }
        public void setPosition(String position) { this.position = position; }

        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }

        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }

        public String getLocator() { return locator; }
        public void setLocator(String locator) { this.locator = locator; }
    }

    /**
     * 潜在重复或冲突。
     */
    public static class Conflict {
        private Long paperId;
        private String paperTitle;
        private String type;
        private String reason;
        private String evidenceId;

        public Long getPaperId() { return paperId; }
        public void setPaperId(Long paperId) { this.paperId = paperId; }

        public String getPaperTitle() { return paperTitle; }
        public void setPaperTitle(String paperTitle) { this.paperTitle = paperTitle; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }

        public String getEvidenceId() { return evidenceId; }
        public void setEvidenceId(String evidenceId) { this.evidenceId = evidenceId; }
    }
}
