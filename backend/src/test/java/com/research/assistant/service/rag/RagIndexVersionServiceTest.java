package com.research.assistant.service.rag;

import com.research.assistant.entity.RagIndexState;
import com.research.assistant.entity.RagIndexVersion;
import com.research.assistant.mapper.PaperChunkMapper;
import com.research.assistant.mapper.RagIndexStateMapper;
import com.research.assistant.mapper.RagIndexVersionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RagIndexVersionServiceTest {

    private final RagIndexStateMapper stateMapper = mock(RagIndexStateMapper.class);
    private final RagIndexVersionMapper versionMapper = mock(RagIndexVersionMapper.class);
    private final PaperChunkMapper chunkMapper = mock(PaperChunkMapper.class);
    private RagIndexVersionService service;

    @BeforeEach
    void setUp() {
        service = new RagIndexVersionService(stateMapper, versionMapper, chunkMapper);
    }

    @Test
    void shouldAllocateNextVersionUnderLockedState() {
        RagIndexState state = new RagIndexState();
        state.setPaperId(7L);
        state.setNextVersion(3);
        doReturn(state).when(stateMapper).selectForUpdate(7L);
        doReturn(1).when(stateMapper).updateNextVersion(7L, 4);
        doReturn(1).when(versionMapper).insert(any(RagIndexVersion.class));

        assertThat(service.beginBuild(7L)).isEqualTo(4);

        verify(stateMapper).updateNextVersion(7L, 4);
        verify(versionMapper).insert(any(RagIndexVersion.class));
    }

    @Test
    void shouldActivateReadyVersionInOrder() {
        RagIndexState state = new RagIndexState();
        state.setPaperId(7L);
        doReturn(state).when(stateMapper).selectForUpdate(7L);
        doReturn(1).when(versionMapper).markReady(7L, 4, 2);
        doReturn(1).when(stateMapper).activate(7L, 4);
        doReturn(1).when(versionMapper).activate(7L, 4);

        service.activate(7L, 4, 2);

        verify(versionMapper).retireActive(7L);
        verify(stateMapper).activate(7L, 4);
        verify(versionMapper).activate(7L, 4);
    }

    @Test
    void shouldDeleteChunksBeforeVersionMetadata() {
        doReturn(2).when(chunkMapper).deleteOldVersions(eq(7L), any());
        doReturn(2).when(versionMapper).deleteOldVersions(eq(7L), any());

        assertThat(service.cleanupOldVersions(7L, java.time.LocalDateTime.now())).isEqualTo(2);

        verify(chunkMapper).deleteOldVersions(eq(7L), any());
        verify(versionMapper).deleteOldVersions(eq(7L), any());
    }
}
