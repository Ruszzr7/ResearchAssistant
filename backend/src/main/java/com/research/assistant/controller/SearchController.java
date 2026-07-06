package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.constant.AcquisitionMethod;
import com.research.assistant.constant.ProcessingStatus;
import com.research.assistant.constant.ReadingStatus;
import com.research.assistant.dto.SearchExpandRequest;
import com.research.assistant.entity.Paper;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.AsyncTaskService;
import com.research.assistant.service.PaperService;
import com.research.assistant.service.SearchService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 智能文献检索 REST 接口。
 * <p>
 * 对应 Spec 功能二：六步对话式检索。
 */
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

    /** POST /api/search/extract — Step 2: Agent 提炼检索要素 */
    @PostMapping("/extract")
    public Result<Map<String, Object>> extract(@RequestBody Map<String, String> body) {
        String input = body.get("query");
        if (input == null || input.isBlank()) {
            return Result.error(400, "请输入研究方向描述");
        }
        return Result.ok(searchService.extractSearchParams(input));
    }

    /** POST /api/search/execute — Step 3: 执行检索 */
    @PostMapping("/execute")
    public Result<List<Map<String, Object>>> execute(@RequestBody Map<String, Object> params) {
        return Result.ok(searchService.executeSearch(params));
    }

    /** POST /api/search/expand — Step 5-6: 扩展检索 */
    @PostMapping("/expand")
    public Result<Map<String, Object>> expand(@RequestBody @Valid SearchExpandRequest request) {
        return Result.ok(searchService.expandSearch(request.getQueries()));
    }

    /**
     * POST /api/search/import — 批量导入检索结果中的论文。
     * <p>
     * 请求体: { "papers": [...], "folderId": 123 (可选) }
     * 每篇 paper map 应包含 title, authors, summary, published, arxivId, pdfUrl。
     */
    @PostMapping("/import")
    public Result<Map<String, Object>> importPapers(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> papers = (List<Map<String, Object>>) body.get("papers");
        if (papers == null || papers.isEmpty()) {
            return Result.error(400, "没有要导入的论文");
        }
        Long folderId = body.get("folderId") != null
                ? Long.valueOf(body.get("folderId").toString())
                : null;

        int imported = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        for (Map<String, Object> raw : papers) {
            try {
                Paper paper = convertToPaper(raw);
                if (paper.getTitle() == null || paper.getTitle().isBlank()) {
                    skipped++;
                    continue;
                }
                paper.setFolderId(folderId);
                Paper saved = paperService.create(paper);
                imported++;
                // 异步下载 arXiv PDF
                String arxivId = (String) raw.get("arxivId");
                if (arxivId != null && !arxivId.isBlank()) {
                    triggerPdfDownload(saved.getId(), arxivId);
                }
            } catch (Exception e) {
                String title = (String) raw.getOrDefault("title", "未知");
                log.warn("导入论文失败: {} — {}", title, e.getMessage());
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

    /** 将检索结果 map 转换为 Paper 实体 */
    private Paper convertToPaper(Map<String, Object> raw) {
        Paper paper = new Paper();
        paper.setTitle((String) raw.get("title"));

        // 作者：逗号分隔字符串 → JSON 数组
        String authorsStr = (String) raw.get("authors");
        if (authorsStr != null && !authorsStr.isBlank()) {
            String authorsJson = Arrays.stream(authorsStr.split(","))
                    .map(String::trim)
                    .filter(n -> !n.isEmpty())
                    .map(n -> "{\"name\":\"" + n.replace("\"", "\\\"") + "\",\"role\":\"\"}")
                    .collect(Collectors.joining(",", "[", "]"));
            paper.setAuthors(authorsJson);
        }

        // 年份：从 published 日期提取
        String published = (String) raw.get("published");
        if (published != null && published.length() >= 4) {
            try {
                paper.setYear(Integer.parseInt(published.substring(0, 4)));
            } catch (NumberFormatException ignored) {}
        }

        paper.setSource("arXiv");
        paper.setArxivId((String) raw.get("arxivId"));
        paper.setSourceUrl((String) raw.get("pdfUrl"));
        paper.setAbstractText((String) raw.get("summary"));
        paper.setAcquisitionMethod(AcquisitionMethod.OA);
        paper.setReadingStatus(ReadingStatus.UNREAD);
        paper.setProcessingStatus(ProcessingStatus.PENDING);

        return paper;
    }

    /** 异步下载 arXiv PDF 并更新论文 pdfPath，下载完成后自动触发 AI 处理 */
    private void triggerPdfDownload(Long paperId, String arxivId) {
        asyncTaskService.downloadArxivPdfAsync(paperId, arxivId);
    }
}
