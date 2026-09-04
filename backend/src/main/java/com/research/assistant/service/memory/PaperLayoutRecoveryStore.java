package com.research.assistant.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.PaperMemoryRecord;
import com.research.assistant.mapper.PaperMemoryMapper;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import org.springframework.stereotype.Component;

import java.util.List;

/** Reads the validated recovery overlay belonging to the current immutable layout artifact. */
@Component
public class PaperLayoutRecoveryStore {

    private final PaperMemoryMapper memoryMapper;
    private final ObjectMapper objectMapper;

    public PaperLayoutRecoveryStore(PaperMemoryMapper memoryMapper, ObjectMapper objectMapper) {
        this.memoryMapper = memoryMapper;
        this.objectMapper = objectMapper;
    }

    public List<PaperLayoutRecovery> read(PaperLayoutArtifact artifact) {
        if (artifact == null || artifact.paperId() == null) return List.of();
        PaperMemoryRecord record = memoryMapper.selectVersion(artifact.paperId(), artifact.documentHash(),
                artifact.parserVersion(), PaperStructure.SCHEMA_VERSION);
        boolean usableStatus = record != null
                && (PaperUnderstandingService.STATUS_READY.equals(record.getStatus())
                || PaperUnderstandingService.STATUS_PARTIAL.equals(record.getStatus()));
        if (!usableStatus
                || !PaperUnderstandingService.PIPELINE_VERSION.equals(record.getUnderstandingVersion())
                || record.getLayoutRecoveryJson() == null || record.getLayoutRecoveryJson().isBlank()) {
            return List.of();
        }
        try {
            List<PaperLayoutRecovery> recoveries = objectMapper.readValue(
                    record.getLayoutRecoveryJson(), objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, PaperLayoutRecovery.class));
            // A recovery overlay has two safe outcomes: corrected text, or an
            // addressable visual fallback.  Keep both so a failed transcription
            // never makes its original PDF region disappear from evidence.
            return List.copyOf(recoveries);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
