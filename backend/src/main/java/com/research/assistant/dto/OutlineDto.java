package com.research.assistant.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化大纲响应。
 */
public class OutlineDto {

    private List<OutlineSection> sections = new ArrayList<>();

    public List<OutlineSection> getSections() { return sections; }
    public void setSections(List<OutlineSection> sections) { this.sections = sections; }

    /**
     * 大纲节点。
     */
    public static class OutlineSection {
        private Integer level;
        private String title;
        private List<OutlineSection> children = new ArrayList<>();

        public Integer getLevel() { return level; }
        public void setLevel(Integer level) { this.level = level; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public List<OutlineSection> getChildren() { return children; }
        public void setChildren(List<OutlineSection> children) { this.children = children; }
    }
}
