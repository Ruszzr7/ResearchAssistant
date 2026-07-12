package com.research.assistant.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.RagIndexState;
import com.research.assistant.entity.RagIndexVersion;
import com.research.assistant.entity.RagConsistencyAudit;
import com.research.assistant.mapper.PaperChunkMapper;
import com.research.assistant.mapper.RagConsistencyAuditMapper;
import com.research.assistant.mapper.RagIndexStateMapper;
import com.research.assistant.mapper.RagIndexVersionMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagConsistencyServiceTest {

    @Test
    void reportsMismatchWithoutHidingReadResultWhenAuditPersistenceFails() {
        RagIndexStateMapper stateMapper = mock(RagIndexStateMapper.class);
        RagIndexVersionMapper versionMapper = mock(RagIndexVersionMapper.class);
        PaperChunkMapper chunkMapper = mock(PaperChunkMapper.class);
        RagConsistencyAuditMapper auditMapper = mock(RagConsistencyAuditMapper.class);

        RagIndexState state = new RagIndexState();
        state.setPaperId(7L);
        state.setActiveVersion(2);
        RagIndexVersion active = new RagIndexVersion();
        active.setVersionNo(2);
        active.setChunkCount(3);

        when(stateMapper.selectById(7L)).thenReturn(state);
        when(versionMapper.selectActive(7L)).thenReturn(active);
        when(chunkMapper.selectActiveByPaperId(7L)).thenReturn(List.of());
        doThrow(new RuntimeException("audit table unavailable"))
                .when(auditMapper).insert(any(RagConsistencyAudit.class));

        RagConsistencyService service = new RagConsistencyService(
                stateMapper, versionMapper, chunkMapper, auditMapper, new ObjectMapper());

        var reports = service.check(7L);

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).status()).isEqualTo("MISMATCH");
        assertThat(reports.get(0).expectedChunkCount()).isEqualTo(3);
        assertThat(reports.get(0).metadataChunkCount()).isZero();
    }
}
