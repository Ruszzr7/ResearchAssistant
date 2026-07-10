package com.research.assistant.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.research.assistant.common.Result;
import com.research.assistant.constant.AcquisitionMethod;
import com.research.assistant.constant.ReadingStatus;
import com.research.assistant.entity.Paper;
import com.research.assistant.dto.EnrichmentResult;
import com.research.assistant.dto.ReadingProgressDto;
import com.research.assistant.dto.ReadingProgressUpdateRequest;
import com.research.assistant.dto.ReadingTimeRequest;
import com.research.assistant.service.PaperService;
import com.research.assistant.service.ReadingProgressService;
import com.research.assistant.service.ai.workflow.WorkflowService;
import com.research.assistant.service.metadata.MetadataEnrichmentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 论文 REST 接口。
 */
@RestController
@RequestMapping("/api/papers")
public class PaperController {

    private final PaperService paperService;
    private final ReadingProgressService readingProgressService;
    private final MetadataEnrichmentService metadataEnrichmentService;
    private final WorkflowService workflowService;

    @Value("${app.storage.pdf-dir:./data/papers}")
    private String pdfStorageDir;

    public PaperController(PaperService paperService,
                           ReadingProgressService readingProgressService,
                           MetadataEnrichmentService metadataEnrichmentService,
                           WorkflowService workflowService) {
        this.paperService = paperService;
        this.readingProgressService = readingProgressService;
        this.metadataEnrichmentService = metadataEnrichmentService;
        this.workflowService = workflowService;
    }

    /** GET /api/papers?folder=1,2 或 folder=uncategorized 或 folder=recent */
    @GetMapping
    public Result<IPage<Paper>> list(
            @RequestParam(required = false) String folder,
            @RequestParam(required = false) Long tag,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "created_at") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        List<Long> folderIds = null;
        boolean uncategorized = false;
        if (folder != null && !folder.isEmpty()) {
            if ("uncategorized".equals(folder)) {
                uncategorized = true;
            } else if ("recent".equals(folder)) {
                sortBy = "created_at";
                sortDir = "DESC";
            } else {
                folderIds = Arrays.stream(folder.split(","))
                    .map(Long::parseLong)
                    .collect(Collectors.toList());
            }
        }
        return Result.ok(paperService.listWithFilters(
            folderIds, uncategorized, tag, status, keyword, sortBy, sortDir, page, size));
    }

    /** GET /api/papers/:id */
    @GetMapping("/{id}")
    public Result<Paper> getById(@PathVariable Long id) {
        Paper paper = paperService.getById(id);
        if (paper == null) {
            return Result.error(404, "论文不存在");
        }
        return Result.ok(paper);
    }

    /** POST /api/papers — 手动导入论文 */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Paper paper,
                                                 @RequestParam(defaultValue = "true") boolean runWorkflow) {
        Paper saved = paperService.create(paper);
        String taskId = runWorkflow ? workflowService.submitPaperImport(saved.getId()) : null;
        return Result.ok(buildPaperResult(saved, taskId));
    }

    /** PUT /api/papers/:id — 编辑论文 */
    @PutMapping("/{id}")
    public Result<Paper> update(@PathVariable Long id, @RequestBody Paper paper) {
        paper.setId(id);
        return Result.ok(paperService.update(paper));
    }

    /** DELETE /api/papers/:id — 删除论文 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        paperService.delete(id);
        return Result.ok();
    }

    /** POST /api/papers/upload — multipart: PDF 文件 + 论文元数据，一步完成上传和入库 */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "authors", required = false) String authors,
            @RequestParam(value = "year", required = false) Integer year,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "doi", required = false) String doi,
            @RequestParam(value = "arxivId", required = false) String arxivId,
            @RequestParam(value = "sourceUrl", required = false) String sourceUrl,
            @RequestParam(value = "abstractText", required = false) String abstractText,
            @RequestParam(value = "keywords", required = false) String keywords,
            @RequestParam(value = "folderId", required = false) Long folderId,
            @RequestParam(defaultValue = "true") boolean runWorkflow) {
        Paper paper = new Paper();
        paper.setTitle(title);
        paper.setAuthors(authors);
        paper.setYear(year);
        paper.setSource(source);
        paper.setDoi(doi);
        paper.setArxivId(arxivId);
        paper.setSourceUrl(sourceUrl);
        paper.setAbstractText(abstractText);
        paper.setKeywords(keywords);
        paper.setFolderId(folderId);
        paper.setAcquisitionMethod(AcquisitionMethod.MANUAL_UPLOAD);
        paper.setReadingStatus(ReadingStatus.UNREAD);
        Paper saved = paperService.uploadPdfAndCreate(file, paper);
        String taskId = runWorkflow ? workflowService.submitPaperImport(saved.getId()) : null;
        return Result.ok(buildPaperResult(saved, taskId));
    }

    private Map<String, Object> buildPaperResult(Paper paper, String taskId) {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("paper", paper);
        result.put("taskId", taskId);
        return result;
    }

    /** POST /api/papers/enrich-metadata — 从上传的 PDF 中识别并返回元数据预览 */
    @PostMapping("/enrich-metadata")
    public Result<EnrichmentResult> enrichMetadataFromPdf(@RequestParam("file") MultipartFile file) {
        return Result.ok(metadataEnrichmentService.enrichFromPdf(file));
    }

    /** POST /api/papers/batch/delete — 批量删除论文 */
    @PostMapping("/batch/delete")
    public Result<Void> deleteBatch(@RequestBody List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Result.error(400, "请选择要删除的论文");
        }
        paperService.deleteBatch(ids);
        return Result.ok();
    }

    /** POST /api/papers/batch/move — 批量移动论文 */
    @PostMapping("/batch/move")
    public Result<Void> moveBatch(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = (List<Long>) body.get("ids");
        Long folderId = body.get("folderId") != null ? ((Number) body.get("folderId")).longValue() : null;
        if (ids == null || ids.isEmpty()) {
            return Result.error(400, "请选择要移动的论文");
        }
        paperService.moveBatch(ids, folderId);
        return Result.ok();
    }

    /** POST /api/papers/{id}/pin — 切换论文置顶状态 */
    @PostMapping("/{id}/pin")
    public Result<Paper> togglePin(@PathVariable Long id) {
        return Result.ok(paperService.togglePin(id));
    }

    /** POST /api/papers/{id}/enrich-metadata — 为已入库论文补全缺失元数据 */
    @PostMapping("/{id}/enrich-metadata")
    public Result<EnrichmentResult> enrichMetadataForPaper(@PathVariable Long id) {
        return Result.ok(metadataEnrichmentService.enrichFromPaper(id));
    }

    /** GET /api/papers/:id/pdf — 下载/浏览器预览 PDF */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<Resource> downloadPdf(@PathVariable Long id) {
        Paper paper = paperService.getById(id);
        if (paper == null || paper.getPdfPath() == null) {
            return ResponseEntity.notFound().build();
        }
        // 相对路径 → 基于 user.dir 解析
        File pdfDir = new File(pdfStorageDir);
        if (!pdfDir.isAbsolute()) {
            pdfDir = new File(System.getProperty("user.dir"), pdfStorageDir);
        }
        File file = new File(pdfDir, paper.getPdfPath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        // URL 编码处理中文文件名
        String encodedName = URLEncoder.encode(paper.getTitle() + ".pdf", StandardCharsets.UTF_8)
                .replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encodedName)
                .body(resource);
    }

    /** GET /api/papers/{id}/reading-progress — 查询阅读进度 */
    @GetMapping("/{id}/reading-progress")
    public Result<ReadingProgressDto> getReadingProgress(@PathVariable Long id) {
        return Result.ok(readingProgressService.getProgress(id));
    }

    /** POST /api/papers/{id}/reading-progress — 更新当前页 */
    @PostMapping("/{id}/reading-progress")
    public Result<Void> updateReadingProgress(@PathVariable Long id,
                                                @RequestBody @Valid ReadingProgressUpdateRequest request) {
        readingProgressService.updateProgress(id, request.getCurrentPage());
        return Result.ok();
    }

    /** POST /api/papers/{id}/reading-time — 增加阅读时长 */
    @PostMapping("/{id}/reading-time")
    public Result<Void> addReadingTime(@PathVariable Long id,
                                        @RequestBody @Valid ReadingTimeRequest request) {
        readingProgressService.addReadSeconds(id, request.getSeconds());
        return Result.ok();
    }
}
