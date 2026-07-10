package com.research.assistant.service.ai;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.PdfExtractor;
import com.research.assistant.service.SemanticScholarFetcher;
import com.research.assistant.service.metadata.CrossrefFetcher;
import com.research.assistant.service.rag.RagRetrievalService;
import com.research.assistant.service.rag.ScoredChunk;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科研 Agent 可调用的外部工具集合。
 * <p>
 * 通过 {@link Tool} 注解暴露给 LangChain4j AiServices，让 LLM 在 Gap 验证、
 * 扩展检索、元数据补全等场景中自主决定调用哪些工具。
 */
@Component
public class ResearchTools {

    private static final Logger log = LoggerFactory.getLogger(ResearchTools.class);

    private final ArxivFetcher arxivFetcher;
    private final SemanticScholarFetcher semanticScholarFetcher;
    private final CrossrefFetcher crossrefFetcher;
    private final PdfExtractor pdfExtractor;
    private final PaperMapper paperMapper;
    private final RagRetrievalService ragRetrievalService;

    public ResearchTools(ArxivFetcher arxivFetcher,
                         SemanticScholarFetcher semanticScholarFetcher,
                         CrossrefFetcher crossrefFetcher,
                         PdfExtractor pdfExtractor,
                         PaperMapper paperMapper,
                         RagRetrievalService ragRetrievalService) {
        this.arxivFetcher = arxivFetcher;
        this.semanticScholarFetcher = semanticScholarFetcher;
        this.crossrefFetcher = crossrefFetcher;
        this.pdfExtractor = pdfExtractor;
        this.paperMapper = paperMapper;
        this.ragRetrievalService = ragRetrievalService;
    }

    /**
     * 在 arXiv 上搜索与查询词相关的论文。
     *
     * @param query      英文或中文检索词（建议使用英文术语）
     * @param maxResults 期望返回的最大论文数，1-50
     * @return 论文列表，每篇包含 title、authors、summary、published、arxivId、pdfUrl
     */
    @Tool("Search arXiv for papers matching the query. Returns a list of papers with title, authors, summary, published date, arxivId and pdfUrl.")
    public List<Map<String, Object>> searchArxiv(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            int limit = Math.max(1, Math.min(maxResults, 50));
            return arxivFetcher.search(query, limit);
        } catch (Exception e) {
            log.warn("arXiv 工具搜索失败 query={}: {}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 在 Semantic Scholar 上搜索与查询词相关的论文。
     *
     * @param query      英文或中文检索词（建议使用英文术语）
     * @param maxResults 期望返回的最大论文数，1-50
     * @return 论文列表，每篇包含 title、authors、summary、published、sourceUrl、source、arxivId、pdfUrl
     */
    @Tool("Search Semantic Scholar for papers matching the query. Returns a list of papers with title, authors, summary, published year, sourceUrl, source, arxivId and pdfUrl.")
    public List<Map<String, Object>> searchSemanticScholar(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            int limit = Math.max(1, Math.min(maxResults, 50));
            return semanticScholarFetcher.search(query, limit);
        } catch (Exception e) {
            log.warn("Semantic Scholar 工具搜索失败 query={}: {}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 通过 DOI 从 Crossref 获取论文元数据。
     *
     * @param doi DOI，例如 10.1038/nature14539
     * @return 元数据，包含 title、source、year、authors、doi、sourceUrl、abstractText
     */
    @Tool("Fetch paper metadata from Crossref by DOI. Returns title, source, year, authors, doi, sourceUrl and abstractText.")
    public Map<String, String> fetchCrossref(String doi) {
        if (doi == null || doi.isBlank()) {
            return Map.of();
        }
        try {
            return crossrefFetcher.fetch(doi);
        } catch (Exception e) {
            log.warn("Crossref 工具查询失败 doi={}: {}", doi, e.getMessage());
            Map<String, String> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return error;
        }
    }

    /**
     * 按论文标题关键词在本地论文库中搜索。
     *
     * @param keyword 标题关键词
     * @return 本地论文列表，每篇包含 id、title、year、pdfPath
     */
    @Tool("Search the local paper library by title keyword. Returns a list of local papers with id, title, year and pdfPath.")
    public List<Map<String, Object>> searchLocalPapersByTitle(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        try {
            // 复用 MyBatis Plus BaseMapper 的 queryWrapper 做简单 LIKE 查询
            List<Paper> papers = paperMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Paper>()
                            .like(Paper::getTitle, keyword)
                            .orderByDesc(Paper::getCreatedAt)
                            .last("LIMIT 20"));
            List<Map<String, Object>> result = new ArrayList<>();
            for (Paper p : papers) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", p.getId());
                item.put("title", p.getTitle());
                item.put("year", p.getYear());
                item.put("pdfPath", p.getPdfPath());
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            log.warn("本地论文搜索失败 keyword={}: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从本地已存储 PDF 的文件路径中提取文本。
     *
     * @param pdfPath  相对于 pdfStorageDir 的文件路径
     * @param maxPages 最大扫描页数，0 表示全部页
     * @return PDF 文本内容；文件不存在或提取失败返回空字符串
     */
    @Tool("Extract text from a local PDF file path. Provide pdfPath (relative to storage dir) and maxPages (0 means all pages). Returns the extracted text or empty string if unavailable.")
    public String extractPdfTextByPath(String pdfPath, int maxPages) {
        if (pdfPath == null || pdfPath.isBlank()) {
            return "";
        }
        try {
            String text = maxPages <= 0
                    ? pdfExtractor.extract(pdfPath)
                    : pdfExtractor.extractFirstPages(pdfPath, maxPages);
            if (text.length() > 8000) {
                text = text.substring(0, 8000) + "\n...（已截断，共 " + text.length() + " 字符）";
            }
            return text;
        } catch (Exception e) {
            log.warn("PDF 文本提取工具失败 pdfPath={}: {}", pdfPath, e.getMessage());
            return "";
        }
    }

    /**
     * 在本地论文知识库中检索与查询相关的文本片段。
     *
     * @param query 查询词
     * @return 相关片段列表，每个片段包含 paperId、chunkType、content、source、score
     */
    @Tool("Search the local paper knowledge base for chunks relevant to the query. Returns a list of relevant snippets with paperId, chunkType, content, source and similarity score.")
    public List<Map<String, Object>> searchKnowledgeBase(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            List<ScoredChunk> chunks = ragRetrievalService.retrieve(query, 5, 0.65);
            List<Map<String, Object>> result = new ArrayList<>();
            for (ScoredChunk c : chunks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("paperId", c.paperId());
                item.put("chunkType", c.chunkType());
                item.put("content", c.content());
                item.put("source", c.source());
                item.put("score", c.score());
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            log.warn("知识库检索工具失败 query={}: {}", query, e.getMessage());
            return List.of();
        }
    }
}
