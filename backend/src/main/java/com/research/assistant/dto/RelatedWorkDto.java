package com.research.assistant.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Related Work 生成响应。
 */
public class RelatedWorkDto {

    private String content;
    private List<Citation> citations = new ArrayList<>();

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<Citation> getCitations() { return citations; }
    public void setCitations(List<Citation> citations) { this.citations = citations; }

    /**
     * 段落中使用的引用占位信息。
     */
    public static class Citation {
        private Long paperId;
        private String placeholder;
        private String sentence;

        public Long getPaperId() { return paperId; }
        public void setPaperId(Long paperId) { this.paperId = paperId; }

        public String getPlaceholder() { return placeholder; }
        public void setPlaceholder(String placeholder) { this.placeholder = placeholder; }

        public String getSentence() { return sentence; }
        public void setSentence(String sentence) { this.sentence = sentence; }
    }
}
