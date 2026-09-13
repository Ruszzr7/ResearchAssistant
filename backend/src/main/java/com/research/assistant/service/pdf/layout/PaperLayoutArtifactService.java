package com.research.assistant.service.pdf.layout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperLayoutArtifactRecord;
import com.research.assistant.mapper.PaperLayoutArtifactMapper;
import com.research.assistant.mapper.PaperMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** Builds and caches semantic layout artifacts by paper, PDF hash and parser version. */
@Service
public class PaperLayoutArtifactService {

    private static final Logger log = LoggerFactory.getLogger(PaperLayoutArtifactService.class);
    private static final String READY = "READY";
    private static final TypeReference<List<DocumentBlock>> BLOCK_LIST = new TypeReference<>() { };

    private final PaperMapper paperMapper;
    private final PaperLayoutArtifactMapper artifactMapper;
    private final PaperLayoutParser parser;
    private final PaperLayoutSemanticEnricher semanticEnricher;
    private final PaperMathContentEnricher mathContentEnricher;
    private final PaperPdfFileResolver fileResolver;
    private final ObjectMapper objectMapper;

    public PaperLayoutArtifactService(PaperMapper paperMapper,
                                      PaperLayoutArtifactMapper artifactMapper,
                                      PaperLayoutParser parser,
                                      PaperLayoutSemanticEnricher semanticEnricher,
                                      PaperMathContentEnricher mathContentEnricher,
                                      PaperPdfFileResolver fileResolver,
                                      ObjectMapper objectMapper) {
        this.paperMapper = paperMapper;
        this.artifactMapper = artifactMapper;
        this.parser = parser;
        this.semanticEnricher = semanticEnricher;
        this.mathContentEnricher = mathContentEnricher;
        this.fileResolver = fileResolver;
        this.objectMapper = objectMapper;
    }

    public PaperLayoutArtifact ensureArtifact(Long paperId, boolean forceRefresh) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new IllegalArgumentException("论文不存在");
        }
        File pdf = fileResolver.resolveRequired(paper.getPdfPath());
        String documentHash = PdfDocumentFingerprint.sha256(pdf);
        String artifactVersion = parser.parserVersion() + "+" + semanticEnricher.version()
                + "+" + mathContentEnricher.version();
        PaperLayoutArtifactRecord cached = artifactMapper.selectReady(
                paperId, documentHash, artifactVersion);

        if (!forceRefresh && cached != null) {
            try {
                return fromRecord(cached);
            } catch (IllegalStateException e) {
                log.warn("layout_artifact_cache_invalid paperId={} recordId={}", paperId, cached.getId());
            }
        }

        PaperLayoutArtifact raw = parser.parse(paperId, pdf, documentHash);
        PaperLayoutArtifact enriched = mathContentEnricher.enrich(semanticEnricher.enrich(
                raw, new PaperLayoutHints(paper.getTitle(), paper.getAuthors(), paper.getAbstractText())));
        save(enriched, cached);
        PaperLayoutArtifactRecord persisted = artifactMapper.selectReady(
                paperId, documentHash, artifactVersion);
        return persisted == null ? enriched : fromRecord(persisted);
    }

    public PaperLayoutArtifact latestArtifact(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) return null;
        File pdf;
        try {
            pdf = fileResolver.resolveRequired(paper.getPdfPath());
        } catch (IllegalArgumentException unavailable) {
            return null;
        }
        String documentHash = PdfDocumentFingerprint.sha256(pdf);
        String artifactVersion = parser.parserVersion() + "+" + semanticEnricher.version()
                + "+" + mathContentEnricher.version();
        // 不向来源目录暴露旧 PDF 或旧解析器生成的最新历史制品。
        PaperLayoutArtifactRecord record = artifactMapper.selectReady(paperId, documentHash, artifactVersion);
        return record == null ? null : fromRecord(record);
    }

    private void save(PaperLayoutArtifact artifact, PaperLayoutArtifactRecord existing) {
        PaperLayoutArtifactRecord record = existing == null ? new PaperLayoutArtifactRecord() : existing;
        LocalDateTime now = LocalDateTime.now();
        record.setPaperId(artifact.paperId());
        record.setDocumentHash(artifact.documentHash());
        record.setParserVersion(artifact.parserVersion());
        record.setStatus(READY);
        record.setLayoutConfidence(artifact.layoutConfidence());
        record.setPageCount(artifact.pageCount());
        record.setBlocksJson(writeBlocks(artifact.blocks()));
        record.setProvenanceJson(writeProvenance(artifact.provenance()));
        record.setGeneratedAt(toLocalDateTime(artifact.generatedAt()));
        record.setUpdatedAt(now);

        if (record.getId() != null) {
            artifactMapper.updateById(record);
            return;
        }
        record.setCreatedAt(now);
        try {
            artifactMapper.insert(record);
        } catch (DuplicateKeyException e) {
            PaperLayoutArtifactRecord concurrent = artifactMapper.selectReady(
                    artifact.paperId(), artifact.documentHash(), artifact.parserVersion());
            if (concurrent == null) {
                throw e;
            }
            record.setId(concurrent.getId());
            artifactMapper.updateById(record);
        }
    }

    private PaperLayoutArtifact fromRecord(PaperLayoutArtifactRecord record) {
        try {
            List<DocumentBlock> blocks = objectMapper.readValue(record.getBlocksJson(), BLOCK_LIST);
            LayoutArtifactProvenance provenance = record.getProvenanceJson() == null
                    || record.getProvenanceJson().isBlank()
                    ? LayoutArtifactProvenance.direct(record.getParserVersion(),
                            record.getLayoutConfidence() == null ? 0 : record.getLayoutConfidence())
                    : objectMapper.readValue(record.getProvenanceJson(), LayoutArtifactProvenance.class);
            return new PaperLayoutArtifact(
                    record.getPaperId(),
                    record.getDocumentHash(),
                    record.getParserVersion(),
                    record.getLayoutConfidence() == null ? 0 : record.getLayoutConfidence(),
                    toInstant(record.getGeneratedAt()),
                    record.getPageCount() == null ? 0 : record.getPageCount(),
                    blocks,
                    provenance
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("版面制品缓存无法读取", e);
        }
    }

    private String writeBlocks(List<DocumentBlock> blocks) {
        try {
            return objectMapper.writeValueAsString(blocks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("版面制品无法序列化", e);
        }
    }

    private String writeProvenance(LayoutArtifactProvenance provenance) {
        try {
            return objectMapper.writeValueAsString(provenance);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("版面解析来源无法序列化", e);
        }
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private Instant toInstant(LocalDateTime time) {
        return (time == null ? LocalDateTime.now() : time)
                .atZone(ZoneId.systemDefault())
                .toInstant();
    }
}
