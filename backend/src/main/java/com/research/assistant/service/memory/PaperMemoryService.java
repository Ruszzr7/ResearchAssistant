package com.research.assistant.service.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Creates and caches the deterministic structure layer of a paper memory. */
@Service
public class PaperMemoryService {

    public static final String STATUS_STRUCTURED = "STRUCTURED";

    private static final Logger log = LoggerFactory.getLogger(PaperMemoryService.class);

    private final PaperMapper paperMapper;
    private final PaperMemoryMapper memoryMapper;
    private final PaperLayoutArtifactService layoutArtifactService;
    private final PaperStructureBuilder structureBuilder;
    private final ObjectMapper objectMapper;

    public PaperMemoryService(PaperMapper paperMapper,
                              PaperMemoryMapper memoryMapper,
                              PaperLayoutArtifactService layoutArtifactService,
                              PaperStructureBuilder structureBuilder,
                              ObjectMapper objectMapper) {
        this.paperMapper = paperMapper;
        this.memoryMapper = memoryMapper;
        this.layoutArtifactService = layoutArtifactService;
        this.structureBuilder = structureBuilder;
        this.objectMapper = objectMapper;
    }

    public PaperMemoryState ensureStructure(Long paperId, boolean forceRefresh) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) throw new IllegalArgumentException("论文不存在");

        PaperLayoutArtifact artifact = layoutArtifactService.ensureArtifact(paperId, forceRefresh);
        PaperMemoryRecord existing = memoryMapper.selectVersion(
                paperId,
                artifact.documentHash(),
                artifact.parserVersion(),
                PaperStructure.SCHEMA_VERSION);
        if (!forceRefresh && existing != null) {
            try {
                PaperMemoryState cached = fromRecord(existing, artifact);
                if (sameTitle(paper.getTitle(), cached.structure().metadata().title())) {
                    return cached;
                }
                log.info("paper_memory_metadata_changed paperId={} recordId={}", paperId, existing.getId());
            } catch (IllegalStateException exception) {
                log.warn("paper_memory_cache_invalid paperId={} recordId={}", paperId, existing.getId());
            }
        }

        PaperStructure structure = structureBuilder.build(paper, artifact);
        PaperStructureValidator.validate(structure, artifact);
        PaperMemoryRecord saved = saveStructure(existing, structure);
        PaperMemoryRecord persisted = memoryMapper.selectVersion(
                paperId,
                artifact.documentHash(),
                artifact.parserVersion(),
                PaperStructure.SCHEMA_VERSION);
        return fromRecord(persisted == null ? saved : persisted, artifact);
    }

    private boolean sameTitle(String current, String cached) {
        return normalizeTitle(current).equals(normalizeTitle(cached));
    }

    private String normalizeTitle(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim()
                .toLowerCase(java.util.Locale.ROOT);
    }

    public PaperMemoryState latestStructure(Long paperId) {
        PaperMemoryRecord record = memoryMapper.selectLatest(paperId);
        return record == null ? null : fromRecord(record);
    }

    private PaperMemoryRecord saveStructure(PaperMemoryRecord existing,
                                            PaperStructure structure) {
        LocalDateTime now = LocalDateTime.now();
        PaperMemoryRecord record = existing == null ? new PaperMemoryRecord() : existing;
        record.setPaperId(structure.paperId());
        record.setDocumentHash(structure.source().documentHash());
        record.setLayoutParserVersion(structure.source().layoutParserVersion());
        record.setSchemaVersion(structure.schemaVersion());
        record.setStatus(STATUS_STRUCTURED);
        record.setStructureJson(write(structure, "论文结构无法序列化"));
        record.setMemoryQualityJson(write(structure.quality(), "论文结构质量无法序列化"));
        record.setChunkSummariesJson(null);
        record.setProfileJson(null);
        record.setLayoutRecoveryJson(null);
        record.setUnderstandingVersion(null);
        record.setStageText("PDF 结构已就绪");
        record.setTotalChunks(0);
        record.setCompletedChunks(0);
        record.setFailedChunks(0);
        record.setPromptTokens(0);
        record.setCompletionTokens(0);
        record.setLastErrorCode(null);
        record.setUnderstandingStartedAt(null);
        record.setUnderstandingCompletedAt(null);
        record.setRevision(existing == null || existing.getRevision() == null
                ? 1 : existing.getRevision() + 1);
        record.setGeneratedAt(toLocalDateTime(structure.generatedAt()));
        record.setUpdatedAt(now);

        if (record.getId() != null) {
            memoryMapper.updateById(record);
            return record;
        }
        record.setCreatedAt(now);
        try {
            memoryMapper.insert(record);
        } catch (DuplicateKeyException duplicate) {
            PaperMemoryRecord concurrent = memoryMapper.selectVersion(
                    structure.paperId(),
                    structure.source().documentHash(),
                    structure.source().layoutParserVersion(),
                    structure.schemaVersion());
            if (concurrent == null) throw duplicate;
            record = concurrent;
        }
        return record;
    }

    private PaperMemoryState fromRecord(PaperMemoryRecord record) {
        try {
            PaperStructure structure = objectMapper.readValue(
                    record.getStructureJson(), PaperStructure.class);
            return new PaperMemoryState(
                    record.getId(),
                    record.getPaperId(),
                    record.getDocumentHash(),
                    record.getLayoutParserVersion(),
                    record.getSchemaVersion(),
                    record.getStatus(),
                    record.getRevision() == null ? 1 : record.getRevision(),
                    structure,
                    record.getLastErrorCode() == null ? "" : record.getLastErrorCode(),
                    toInstant(record.getGeneratedAt()),
                    toInstant(record.getUpdatedAt()));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("论文记忆结构无法读取", exception);
        }
    }

    private PaperMemoryState fromRecord(PaperMemoryRecord record, PaperLayoutArtifact artifact) {
        PaperMemoryState state = fromRecord(record);
        PaperStructureValidator.validate(state.structure(), artifact);
        return state;
    }

    private String write(Object value, String message) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant == null ? Instant.now() : instant,
                ZoneId.systemDefault());
    }

    private Instant toInstant(LocalDateTime time) {
        return (time == null ? LocalDateTime.now() : time)
                .atZone(ZoneId.systemDefault()).toInstant();
    }
}
