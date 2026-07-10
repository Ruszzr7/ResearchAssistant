package com.research.assistant.dto;

import java.util.List;

/**
 * BibTeX / 同步导出请求。
 */
public class BibTeXExportRequest {

    private List<Long> ids;

    public List<Long> getIds() { return ids; }
    public void setIds(List<Long> ids) { this.ids = ids; }
}
