package com.research.assistant.service.pdf.math;

import com.research.assistant.entity.InlineMathTranscriptionRecord;
import com.research.assistant.mapper.InlineMathTranscriptionMapper;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.InlineMathFragment;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InlineMathTranscriptionServiceTest {

    @Mock InlineMathTranscriptionMapper mapper;
    @Mock InlineMathTranscriptionProvider provider;
    private InlineMathTranscriptionService service;
    private PaperLayoutArtifact artifact;
    private DocumentBlock block;
    private InlineMathFragment fragment;

    @BeforeEach
    void setUp() {
        service = new InlineMathTranscriptionService(mapper, List.of(provider));
        block = new DocumentBlock("body", 1, new NormalizedBoundingBox(0.1, 0.2, 0.8, 0.1),
                DocumentBlockRole.BODY, 0, List.of(), "where μ_k ≥ 0", null, null, 0.9);
        artifact = new PaperLayoutArtifact(1L, "a".repeat(64), "parser+math", 0.9,
                Instant.now(), 1, List.of(block));
        fragment = new InlineMathFragment(6, 13, "μ_k ≥ 0", 0.8,
                List.of("GREEK_SYMBOL"));
        when(provider.version()).thenReturn("provider-v1");
    }

    @Test
    void writesAndReturnsANewVersionBoundTranscription() {
        when(provider.transcribe(any())).thenReturn(new MathTranscriptionCandidate(
                MathTranscriptionStatus.APPROXIMATE, "\\mu_k \\ge 0", 0.74, "local"));
        when(mapper.insert(any(InlineMathTranscriptionRecord.class))).thenAnswer(invocation -> {
            ((InlineMathTranscriptionRecord) invocation.getArgument(0)).setId(9L);
            return 1;
        });

        InlineMathTranscription result = service.transcribe(artifact, block, fragment);

        assertThat(result.status()).isEqualTo(MathTranscriptionStatus.APPROXIMATE);
        assertThat(result.latex()).isEqualTo("\\mu_k \\ge 0");
        assertThat(result.cached()).isFalse();
        verify(mapper).insert(any(InlineMathTranscriptionRecord.class));
    }

    @Test
    void returnsTheCacheWithoutCallingTheProvider() {
        InlineMathTranscriptionRecord cached = new InlineMathTranscriptionRecord();
        cached.setBlockId("body"); cached.setStartOffset(6); cached.setEndOffset(13);
        cached.setProviderVersion("provider-v1"); cached.setStatus("APPROXIMATE");
        cached.setLatex("cached"); cached.setConfidence(0.7); cached.setMessage("cache");
        when(mapper.selectCurrent(eq(1L), anyString(), eq("parser+math"), eq("body"),
                eq(6), eq(13), anyString(), eq("provider-v1"))).thenReturn(cached);

        InlineMathTranscription result = service.transcribe(artifact, block, fragment);

        assertThat(result.cached()).isTrue();
        assertThat(result.latex()).isEqualTo("cached");
        verify(provider, never()).transcribe(any());
        verify(mapper, never()).insert(any(InlineMathTranscriptionRecord.class));
    }
}
