package com.research.assistant.service.metadata;

import com.research.assistant.dto.EnrichmentResult;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.PdfExtractor;
import com.research.assistant.service.identifier.IdentifierExtractor;
import com.research.assistant.service.identifier.IdentifierResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 元数据补全服务 —— 从 PDF 中识别 DOI / arXiv ID，并查询外部 API 回填论文元数据。
 * <p>
 * 参考 Zotero / 小绿鲸的元数据提取流程：提取文本 → 找标识符 → 调 Crossref / arXiv → 回填。
 */
@Service
public class MetadataEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(MetadataEnrichmentService.class);

    private final PdfExtractor pdfExtractor;
    private final IdentifierExtractor identifierExtractor;
    private final ArxivFetcher arxivFetcher;
    private final CrossrefFetcher crossrefFetcher;
    private final PaperMapper paperMapper;

    public MetadataEnrichmentService(PdfExtractor pdfExtractor,
                                     IdentifierExtractor identifierExtractor,
                                     ArxivFetcher arxivFetcher,
                                     CrossrefFetcher crossrefFetcher,
                                     PaperMapper paperMapper) {
        this.pdfExtractor = pdfExtractor;
        this.identifierExtractor = identifierExtractor;
        this.arxivFetcher = arxivFetcher;
        this.crossrefFetcher = crossrefFetcher;
        this.paperMapper = paperMapper;
    }

    /**
     * 从上传的 PDF 中识别并补全元数据（导入预览用，不持久化）。
     *
     * @param file PDF 文件
     * @return 补全结果
     */
    public EnrichmentResult enrichFromPdf(MultipartFile file) {
        String text = pdfExtractor.extractFromMultipartFile(file, 5);
        return enrichFromText(text);
    }

    /**
     * 为已入库论文重新识别并补全缺失元数据。
     *
     * @param paperId 论文 ID
     * @return 补全结果
     */
    public EnrichmentResult enrichFromPaper(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new RuntimeException("论文不存在");
        }
        if (paper.getPdfPath() == null || paper.getPdfPath().isBlank()) {
            throw new RuntimeException("论文未上传 PDF");
        }
        String text = pdfExtractor.extractFirstPages(paper.getPdfPath(), 5);
        EnrichmentResult result = enrichFromText(text);

        if (result.isFound()) {
            Paper update = new Paper();
            update.setId(paperId);
            boolean hasUpdate = false;

            if (shouldFill(paper.getTitle(), result.getTitle())) { update.setTitle(result.getTitle()); hasUpdate = true; }
            if (shouldFill(paper.getAuthors(), result.getAuthors())) { update.setAuthors(result.getAuthors()); hasUpdate = true; }
            if (paper.getYear() == null && result.getYear() != null) { update.setYear(result.getYear()); hasUpdate = true; }
            if (shouldFill(paper.getSource(), result.getSource())) { update.setSource(result.getSource()); hasUpdate = true; }
            if (shouldFill(paper.getDoi(), result.getDoi())) { update.setDoi(result.getDoi()); hasUpdate = true; }
            if (shouldFill(paper.getArxivId(), result.getArxivId())) { update.setArxivId(result.getArxivId()); hasUpdate = true; }
            if (shouldFill(paper.getSourceUrl(), result.getSourceUrl())) { update.setSourceUrl(result.getSourceUrl()); hasUpdate = true; }
            if (shouldFill(paper.getAbstractText(), result.getAbstractText())) { update.setAbstractText(result.getAbstractText()); hasUpdate = true; }

            if (hasUpdate) {
                paperMapper.updateById(update);
            }
        }
        return result;
    }

    private EnrichmentResult enrichFromText(String text) {
        IdentifierResult ids = identifierExtractor.extract(text);
        EnrichmentResult result = new EnrichmentResult();

        if (ids.getArxivId() != null) {
            result.setFoundArxivId(ids.getArxivId());
            try {
                Map<String, String> meta = arxivFetcher.getMetadata(ids.getArxivId());
                fillFromArxiv(result, meta);
                result.setFound(true);
                result.setMessage("已从 arXiv 补全元数据");
            } catch (Exception e) {
                log.warn("arXiv 元数据查询失败 arxivId={}: {}", ids.getArxivId(), e.getMessage());
                result.setFound(false);
                result.setMessage("识别到 arXiv ID，但查询失败：" + e.getMessage());
            }
            return result;
        }

        if (ids.getDoi() != null) {
            result.setFoundDoi(ids.getDoi());
            try {
                Map<String, String> meta = crossrefFetcher.fetch(ids.getDoi());
                fillFromCrossref(result, meta);
                result.setFound(true);
                result.setMessage("已从 Crossref 补全元数据");
            } catch (Exception e) {
                log.warn("Crossref 元数据查询失败 doi={}: {}", ids.getDoi(), e.getMessage());
                result.setFound(false);
                result.setMessage("识别到 DOI，但查询失败：" + e.getMessage());
            }
            return result;
        }

        result.setFound(false);
        result.setMessage("未识别到 DOI 或 arXiv ID");
        return result;
    }

    private void fillFromArxiv(EnrichmentResult result, Map<String, String> meta) {
        result.setTitle(MetadataNormalizer.normalizeTitle(meta.get("title")));
        result.setAuthors(MetadataNormalizer.normalizeAuthors(meta.get("authors")));
        result.setYear(MetadataNormalizer.normalizeYear(meta.get("published")));
        result.setSource("arXiv");
        result.setArxivId(MetadataNormalizer.normalizeArxivId(meta.get("arxiv_id")));
        result.setSourceUrl(meta.get("source_url"));
        result.setAbstractText(MetadataNormalizer.normalizeTitle(meta.get("summary")));
    }

    private void fillFromCrossref(EnrichmentResult result, Map<String, String> meta) {
        result.setTitle(MetadataNormalizer.normalizeTitle(meta.get("title")));
        result.setAuthors(MetadataNormalizer.normalizeAuthors(meta.get("authors")));
        result.setYear(MetadataNormalizer.normalizeYear(meta.get("year")));
        result.setSource(MetadataNormalizer.normalizeTitle(meta.get("source")));
        result.setDoi(meta.get("doi"));
        result.setSourceUrl(meta.get("sourceUrl"));
        result.setAbstractText(MetadataNormalizer.normalizeTitle(meta.get("abstractText")));
    }

    private boolean shouldFill(String existing, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return existing == null || existing.isBlank() || "[]".equals(existing.trim());
    }
}
