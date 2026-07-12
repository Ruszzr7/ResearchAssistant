package com.research.assistant.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.RagConsistencyReport;
import com.research.assistant.entity.RagConsistencyAudit;
import com.research.assistant.entity.RagIndexState;
import com.research.assistant.entity.RagIndexVersion;
import com.research.assistant.mapper.PaperChunkMapper;
import com.research.assistant.mapper.RagConsistencyAuditMapper;
import com.research.assistant.mapper.RagIndexStateMapper;
import com.research.assistant.mapper.RagIndexVersionMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class RagConsistencyService {

    private final RagIndexStateMapper stateMapper;
    private final RagIndexVersionMapper versionMapper;
    private final PaperChunkMapper chunkMapper;
    private final RagConsistencyAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    public RagConsistencyService(RagIndexStateMapper stateMapper,
                                 RagIndexVersionMapper versionMapper,
                                 PaperChunkMapper chunkMapper,
                                 RagConsistencyAuditMapper auditMapper,
                                 ObjectMapper objectMapper) {
        this.stateMapper = stateMapper;
        this.versionMapper = versionMapper;
        this.chunkMapper = chunkMapper;
        this.auditMapper = auditMapper;
        this.objectMapper = objectMapper;
    }

    public List<RagConsistencyReport> check(Long paperId) {
        List<RagIndexState> states;
        if (paperId == null) {
            states = stateMapper.selectActiveStates();
        } else {
            RagIndexState state = stateMapper.selectById(paperId);
            states = state == null ? List.of() : List.of(state);
        }
        return states.stream()
                .filter(java.util.Objects::nonNull)
                .map(this::checkState)
                .toList();
    }

    private RagConsistencyReport checkState(RagIndexState state) {
        Long paperId = state.getPaperId();
        RagIndexVersion active = versionMapper.selectActive(paperId);
        int chunkCount = chunkMapper.selectActiveByPaperId(paperId).size();
        String status = "OK";
        String details = "active_version、版本状态和分片数量一致";
        Integer expected = active == null ? null : active.getChunkCount();
        if (active == null || state.getActiveVersion() == null
                || !state.getActiveVersion().equals(active.getVersionNo())) {
            status = "MISMATCH";
            details = "rag_index_state 与 ACTIVE 版本记录不一致";
        } else if (expected == null || expected != chunkCount) {
            status = "MISMATCH";
            details = "ACTIVE 版本记录的 chunk_count 与 MySQL 分片数量不一致";
        }
        RagConsistencyReport report = new RagConsistencyReport(
                paperId, "MYSQL", status, state.getActiveVersion(), chunkCount, expected, details);
        persist(report);
        return report;
    }

    private void persist(RagConsistencyReport report) {
        RagConsistencyAudit audit = new RagConsistencyAudit();
        audit.setPaperId(report.paperId());
        audit.setProvider(report.provider());
        audit.setStatus(report.status());
        audit.setActiveVersion(report.activeVersion());
        audit.setMetadataChunkCount(report.metadataChunkCount());
        audit.setExpectedChunkCount(report.expectedChunkCount());
        audit.setDetailsJson(toJson(report.details()));
        audit.setCheckedAt(LocalDateTime.now());
        try {
            auditMapper.insert(audit);
        } catch (RuntimeException ignored) {
            // Consistency inspection is read-only for business data; an audit
            // write failure must not hide the actual report.
        }
    }

    private String toJson(String details) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("message", details));
        } catch (JsonProcessingException e) {
            return "{\"message\":\"consistency check\"}";
        }
    }
}
