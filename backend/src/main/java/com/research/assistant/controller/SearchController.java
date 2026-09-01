package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.constant.AcquisitionMethod;
import com.research.assistant.constant.ProcessingStatus;
import com.research.assistant.constant.ReadingStatus;
import com.research.assistant.dto.SearchExecuteRequest;
import com.research.assistant.dto.SearchExtractRequest;
import com.research.assistant.dto.SearchImportPaper;
import com.research.assistant.dto.SearchImportRequest;
import com.research.assistant.entity.Paper;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.AsyncTaskService;
import com.research.assistant.service.PaperService;
import com.research.assistant.service.SearchService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final SearchService searchService;
    private final PaperService paperService;
    private final ArxivFetcher arxivFetcher;
    private final AsyncTaskService asyncTaskService;

    public SearchController(SearchService searchService, PaperService paperService,
                            ArxivFetcher arxivFetcher, AsyncTaskService asyncTaskService) {
        this.searchService = searchService;
        this.paperService = paperService;
        this.arxivFetcher = arxivFetcher;
        this.asyncTaskService = asyncTaskService;
    }

    @PostMapping("/extract")
    public Result<Map<String, Object>> extract(@RequestBody @Valid SearchExtractRequest request) {
        return Result.ok(searchService.extractSearchParams(request.getQuery()));
    }

    @PostMapping("/execute")
    public Result<List<Map<String, Object>>> execute(@RequestBody @Valid SearchExecuteRequest request) {
        return Result.ok(searchService.executeSearch(request.toParams()));
    }

    @PostMapping("/import")
    public Result<Map<String, Object>> importPapers(@RequestBody @Valid SearchImportRequest request) {
        List<SearchImportPaper> papers = request.getPapers();
        Long folderId = request.getFolderId();
        int imported = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        for (SearchImportPaper raw : papers) {
            try {
                Paper paper = convertToPaper(raw);
                if (paper.getTitle() == null || paper.getTitle().isBlank()) {
                    skipped++;
                    continue;
                }
                paper.setFolderId(folderId);
                Paper saved = paperService.create(paper);
                imported++;
                String arxivId = raw.getArxivId();
                if (arxivId != null && !arxivId.isBlank()) {
                    triggerPdfDownload(saved.getId(), arxivId);
                }
            } catch (Exception e) {
                String title = raw.getTitle() == null ? "未知" : raw.getTitle();
                log.warn("paper_import_failed type={}", e.getClass().getSimpleName());
                errors.add(title);
                skipped++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("skipped", skipped);
        result.put("errors", errors);
        result.put("total", papers.size());
        return Result.ok(result);
    }

    private Paper convertToPaper(SearchImportPaper raw) {
        Paper paper = new Paper();
        paper.setTitle(raw.getTitle());

        String authorsStr = raw.getAuthors();
        if (authorsStr != null && !authorsStr.isBlank()) {
            String authorsJson = Arrays.stream(authorsStr.split(","))
                    .map(String::trim)
                    .filter(n -> !n.isEmpty())
                    .map(n -> "{\"name\":\"" + n.replace("\"", "\\\"") + "\",\"role\":\"\"}")
                    .collect(Collectors.joining(",", "[", "]"));
            paper.setAuthors(authorsJson);
        }

        String published = raw.getPublished();
        if (published != null && published.length() >= 4) {
            try {
                paper.setYear(Integer.parseInt(published.substring(0, 4)));
            } catch (NumberFormatException ignored) {
                // Keep year unset when an external source has a non-standard date.
            }
        }

        String source = raw.getSource() == null || raw.getSource().isBlank() ? "arXiv" : raw.getSource();
        paper.setSource(source);
        paper.setArxivId(raw.getArxivId());
        paper.setSemanticScholarId("Semantic Scholar".equals(source) ? raw.getExternalId() : null);
        String sourceUrl = raw.getSourceUrl();
        String pdfUrl = raw.getPdfUrl();
        paper.setSourceUrl(sourceUrl != null && !sourceUrl.isBlank() ? sourceUrl : pdfUrl);
        paper.setAbstractText(raw.getSummary());
        paper.setAcquisitionMethod(AcquisitionMethod.OA);
        paper.setReadingStatus(ReadingStatus.UNREAD);
        paper.setProcessingStatus(ProcessingStatus.PENDING);
        return paper;
    }

    private void triggerPdfDownload(Long paperId, String arxivId) {
        asyncTaskService.downloadArxivPdfAsync(paperId, arxivId);
    }
}
