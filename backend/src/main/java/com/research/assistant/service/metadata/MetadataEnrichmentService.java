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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private final PdfMetadataHeuristics pdfMetadataHeuristics;

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
        this.pdfMetadataHeuristics = new PdfMetadataHeuristics();
    }

    /**
     * 从上传的 PDF 中识别并补全元数据（导入预览用，不持久化）。
     *
     * @param file PDF 文件
     * @return 补全结果
     */
    public EnrichmentResult enrichFromPdf(MultipartFile file) {
        PdfExtractor.MetadataTextExtraction texts = pdfExtractor.extractMetadataTextExtraction(file, 5);
        return enrichFromText(texts.identityText(), texts.metadataText());
    }

    /**
     * 为已入库论文重新识别元数据并返回预览，不直接写入数据库。
     * 用户确认后由前端通过普通论文编辑接口应用结果。
     *
     * @param paperId 论文 ID
     * @return 识别结果
     */
    public EnrichmentResult enrichFromPaper(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new RuntimeException("论文不存在");
        }
        if (paper.getPdfPath() == null || paper.getPdfPath().isBlank()) {
            throw new RuntimeException("论文未上传 PDF");
        }
        PdfExtractor.MetadataTextExtraction texts = pdfExtractor
                .extractMetadataTextExtraction(paper.getPdfPath(), 5);
        return enrichFromText(texts.identityText(), texts.metadataText());
    }

    /**
     * 身份标识只来自首页；前几页文本仅用于本地标题、摘要、关键词等兜底信息。
     * 这样参考文献中的 arXiv / DOI 不会覆盖当前 PDF 的元数据。
     */
    private EnrichmentResult enrichFromText(String identityText, String metadataText) {
        IdentifierResult ids = identifierExtractor.extract(identityText);
        EnrichmentResult result = new EnrichmentResult();
        PdfMetadataHeuristics.Metadata localMetadata = pdfMetadataHeuristics.extract(metadataText);

        if (ids.getDoi() != null) {
            try {
                Map<String, String> meta = crossrefFetcher.fetch(ids.getDoi());
                if (!isExternalTitleConsistent(localMetadata.title(), meta.get("title"))) {
                    return fillFromMismatchedExternalRecord(result, localMetadata, "DOI");
                }
                result.setFoundDoi(ids.getDoi());
                result.setDoi(ids.getDoi());
                fillFromCrossref(result, meta);
                fillFromPdfFallback(result, localMetadata);
                result.setFound(true);
                result.setMessage("已从 Crossref 补全元数据");
            } catch (Exception e) {
                log.warn("Crossref 元数据查询失败 doi={}: {}", ids.getDoi(), e.getMessage());
                result.setFoundDoi(ids.getDoi());
                result.setDoi(ids.getDoi());
                fillFromPdfFallback(result, localMetadata);
                boolean localFound = hasUsableMetadata(result);
                result.setFound(localFound);
                result.setMessage(localFound
                        ? "已识别 DOI，并从 PDF 提取可用元数据，请核对"
                        : "识别到 DOI，但查询失败：" + e.getMessage());
            }
            return result;
        }

        if (ids.getArxivId() != null) {
            try {
                Map<String, String> meta = arxivFetcher.getMetadata(ids.getArxivId());
                if (!isExternalTitleConsistent(localMetadata.title(), meta.get("title"))) {
                    return fillFromMismatchedExternalRecord(result, localMetadata, "arXiv ID");
                }
                result.setFoundArxivId(ids.getArxivId());
                fillFromArxiv(result, meta);
                fillFromPdfFallback(result, localMetadata);
                result.setFound(true);
                result.setMessage("已从 arXiv 补全元数据");
            } catch (Exception e) {
                log.warn("arXiv 元数据查询失败 arxivId={}: {}", ids.getArxivId(), e.getMessage());
                result.setFoundArxivId(ids.getArxivId());
                fillFromPdfFallback(result, localMetadata);
                boolean localFound = hasUsableMetadata(result);
                result.setFound(localFound);
                result.setMessage(localFound
                        ? "已识别 arXiv ID，并从 PDF 提取可用元数据，请核对"
                        : "识别到 arXiv ID，但查询失败：" + e.getMessage());
            }
            return result;
        }

        fillFromPdfFallback(result, localMetadata);
        boolean localFound = result.getTitle() != null
                || result.getAuthors() != null
                || result.getAbstractText() != null
                || result.getSource() != null;
        result.setFound(localFound);
        result.setMessage(localFound
                ? "已从 PDF 提取标题和摘要，请核对其他元数据"
                : "未识别到 DOI 或 arXiv ID");
        return result;
    }

    /**
     * 外部记录与 PDF 首页标题不一致时，宁可只返回本地提取结果，也不能写入无关论文。
     */
    private EnrichmentResult fillFromMismatchedExternalRecord(EnrichmentResult result,
                                                               PdfMetadataHeuristics.Metadata localMetadata,
                                                               String identifierName) {
        fillFromPdfFallback(result, localMetadata);
        result.setFound(hasUsableMetadata(result));
        result.setMessage("检测到首页 " + identifierName
                + "，但查询记录与 PDF 标题不一致，已忽略外部结果并保留 PDF 提取内容");
        return result;
    }

    private void fillFromPdfFallback(EnrichmentResult result, PdfMetadataHeuristics.Metadata localMetadata) {
        if (localMetadata == null) {
            return;
        }
        if (result.getTitle() == null || result.getTitle().isBlank()) {
            result.setTitle(localMetadata.title());
        }
        if (result.getAuthors() == null || result.getAuthors().isBlank()) {
            result.setAuthors(MetadataNormalizer.normalizeAuthors(localMetadata.authors()));
        }
        if (result.getYear() == null && localMetadata.year() != null) {
            result.setYear(localMetadata.year());
        }
        if (result.getAbstractText() == null || result.getAbstractText().isBlank()) {
            result.setAbstractText(localMetadata.abstractText());
        }
        if (result.getSource() == null || result.getSource().isBlank()) {
            result.setSource(localMetadata.source());
        }
        if (result.getKeywords() == null || result.getKeywords().isBlank()) {
            result.setKeywords(localMetadata.keywords());
        }
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
        result.setSource(MetadataNormalizer.normalizeSource(meta.get("source")));
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

    /**
     * 以标题关键词交集做一层保守校验。PDF 本地标题缺失或外部记录没有标题时不阻断，
     * 否则至少要求短标题中的主要词有一半重合，避免参考文献标识符污染当前论文。
     */
    private boolean isExternalTitleConsistent(String localTitle, String externalTitle) {
        if (!hasReliableTitle(localTitle) || !hasText(externalTitle)) {
            return true;
        }
        String localCompact = compactTitle(localTitle);
        String externalCompact = compactTitle(externalTitle);
        if (localCompact.length() >= 12
                && (localCompact.contains(externalCompact) || externalCompact.contains(localCompact))) {
            return true;
        }

        Set<String> localTokens = titleTokens(localTitle);
        Set<String> externalTokens = titleTokens(externalTitle);
        if (localTokens.isEmpty() || externalTokens.isEmpty()) {
            return true;
        }
        Set<String> overlap = new HashSet<>(localTokens);
        overlap.retainAll(externalTokens);
        int smallerTitleSize = Math.min(localTokens.size(), externalTokens.size());
        int minimumOverlap = Math.min(2, smallerTitleSize);
        return overlap.size() >= minimumOverlap
                && overlap.size() * 1.0 / smallerTitleSize >= 0.45;
    }

    private boolean hasReliableTitle(String title) {
        return hasText(title) && compactTitle(title).length() >= 12;
    }

    private String compactTitle(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private Set<String> titleTokens(String title) {
        Set<String> ignored = Set.of("a", "an", "and", "the", "of", "for", "in", "on", "to", "with",
                "via", "using", "based", "toward", "towards", "from", "by", "at");
        Set<String> tokens = new HashSet<>();
        Arrays.stream((title == null ? "" : title).toLowerCase(Locale.ROOT)
                        .split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 3 && !ignored.contains(token))
                .forEach(tokens::add);
        return tokens;
    }

    private boolean hasUsableMetadata(EnrichmentResult result) {
        return hasText(result.getTitle())
                || hasText(result.getAuthors())
                || hasText(result.getSource())
                || result.getYear() != null
                || hasText(result.getDoi())
                || hasText(result.getArxivId())
                || hasText(result.getAbstractText())
                || hasText(result.getKeywords());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
