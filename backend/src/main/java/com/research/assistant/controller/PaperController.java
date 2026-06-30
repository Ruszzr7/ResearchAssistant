package com.research.assistant.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.research.assistant.common.Result;
import com.research.assistant.constant.AcquisitionMethod;
import com.research.assistant.constant.ReadingStatus;
import com.research.assistant.entity.Paper;
import com.research.assistant.service.PaperService;
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
import java.util.stream.Collectors;

/**
 * 论文 REST 接口。
 */
@RestController
@RequestMapping("/api/papers")
public class PaperController {

    private final PaperService paperService;

    @Value("${app.storage.pdf-dir:./data/papers}")
    private String pdfStorageDir;

    public PaperController(PaperService paperService) {
        this.paperService = paperService;
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
    public Result<Paper> create(@RequestBody Paper paper) {
        return Result.ok(paperService.create(paper));
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
    public Result<Paper> upload(
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
            @RequestParam(value = "folderId", required = false) Long folderId) {
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
        return Result.ok(paperService.uploadPdfAndCreate(file, paper));
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
}
