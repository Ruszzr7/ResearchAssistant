package com.research.assistant.controller;

import com.research.assistant.dto.BibTeXExportRequest;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.export.BibTeXExporter;
import com.research.assistant.service.export.ObsidianSyncService;
import com.research.assistant.service.export.ZoteroSyncService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 论文导出与同步接口。
 */
@RestController
@RequestMapping("/api")
public class ExportController {

    private final PaperMapper paperMapper;
    private final BibTeXExporter bibTeXExporter;
    private final ObsidianSyncService obsidianSyncService;
    private final ZoteroSyncService zoteroSyncService;

    public ExportController(PaperMapper paperMapper,
                            BibTeXExporter bibTeXExporter,
                            ObsidianSyncService obsidianSyncService,
                            ZoteroSyncService zoteroSyncService) {
        this.paperMapper = paperMapper;
        this.bibTeXExporter = bibTeXExporter;
        this.obsidianSyncService = obsidianSyncService;
        this.zoteroSyncService = zoteroSyncService;
    }

    @GetMapping("/papers/{id}/export/bibtex")
    public ResponseEntity<String> exportSingleBibTeX(@PathVariable Long id) {
        Paper paper = paperMapper.selectById(id);
        if (paper == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"paper_" + id + ".bib\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(bibTeXExporter.export(paper));
    }

    @PostMapping("/papers/export/bibtex")
    public ResponseEntity<String> exportBatchBibTeX(@RequestBody BibTeXExportRequest request) {
        List<Paper> papers = paperMapper.selectBatchIds(request.getIds());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"papers.bib\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(bibTeXExporter.exportBatch(papers));
    }

    @PostMapping("/export/obsidian")
    public ResponseEntity<Map<String, Object>> syncObsidian(@RequestBody BibTeXExportRequest request) {
        List<Paper> papers = paperMapper.selectBatchIds(request.getIds());
        try {
            int count = obsidianSyncService.sync(papers);
            return ResponseEntity.ok(Map.of("success", true, "count", count));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PostMapping("/export/zotero")
    public ResponseEntity<Map<String, Object>> syncZotero(@RequestBody BibTeXExportRequest request) {
        List<Paper> papers = paperMapper.selectBatchIds(request.getIds());
        try {
            int count = zoteroSyncService.sync(papers);
            return ResponseEntity.ok(Map.of("success", true, "count", count));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}
