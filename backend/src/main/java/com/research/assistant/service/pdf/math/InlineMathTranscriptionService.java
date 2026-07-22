package com.research.assistant.service.pdf.math;

import com.research.assistant.entity.InlineMathTranscriptionRecord;
import com.research.assistant.mapper.InlineMathTranscriptionMapper;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.InlineMathFragment;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/** On-demand inline-math transcription with a version-bound persistent cache. */
@Service
public class InlineMathTranscriptionService {

    private final InlineMathTranscriptionMapper mapper;
    private final List<InlineMathTranscriptionProvider> providers;

    public InlineMathTranscriptionService(InlineMathTranscriptionMapper mapper,
                                          List<InlineMathTranscriptionProvider> providers) {
        this.mapper = mapper;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public InlineMathTranscription transcribe(PaperLayoutArtifact artifact,
                                              DocumentBlock block,
                                              InlineMathFragment fragment) {
        validate(artifact, block, fragment);
        InlineMathTranscriptionProvider provider = providers.stream().findFirst().orElse(null);
        if (provider == null) {
            return unavailable(block, fragment, "none", "未配置数学转写 provider", false);
        }
        String sourceHash = sha256(fragment.sourceText());
        InlineMathTranscriptionRecord cached = mapper.selectCurrent(
                artifact.paperId(), artifact.documentHash(), artifact.parserVersion(), block.id(),
                fragment.start(), fragment.end(), sourceHash, provider.version());
        if (cached != null) return fromRecord(cached, fragment.sourceText(), true);

        MathTranscriptionCandidate candidate;
        try {
            candidate = provider.transcribe(new MathTranscriptionRequest(
                    artifact.paperId(), artifact.documentHash(), artifact.parserVersion(), block.page(),
                    block.id(), fragment.start(), fragment.end(), fragment.sourceText()));
        } catch (RuntimeException exception) {
            candidate = new MathTranscriptionCandidate(MathTranscriptionStatus.UNAVAILABLE, "", 0,
                    "数学转写失败，保留 PDF 原文并要求回原页核对");
        }
        InlineMathTranscriptionRecord saved = save(artifact, block, fragment, sourceHash,
                provider.version(), candidate);
        return fromRecord(saved, fragment.sourceText(), false);
    }

    private InlineMathTranscriptionRecord save(PaperLayoutArtifact artifact,
                                               DocumentBlock block,
                                               InlineMathFragment fragment,
                                               String sourceHash,
                                               String providerVersion,
                                               MathTranscriptionCandidate candidate) {
        LocalDateTime now = LocalDateTime.now();
        InlineMathTranscriptionRecord record = new InlineMathTranscriptionRecord();
        record.setPaperId(artifact.paperId());
        record.setDocumentHash(artifact.documentHash());
        record.setParserVersion(artifact.parserVersion());
        record.setBlockId(block.id());
        record.setStartOffset(fragment.start());
        record.setEndOffset(fragment.end());
        record.setSourceHash(sourceHash);
        record.setProviderVersion(providerVersion);
        record.setStatus(candidate.status().name());
        record.setLatex(candidate.latex());
        record.setConfidence(candidate.confidence());
        record.setMessage(candidate.message());
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        try {
            mapper.insert(record);
            return record;
        } catch (DuplicateKeyException exception) {
            InlineMathTranscriptionRecord concurrent = mapper.selectCurrent(
                    artifact.paperId(), artifact.documentHash(), artifact.parserVersion(), block.id(),
                    fragment.start(), fragment.end(), sourceHash, providerVersion);
            if (concurrent == null) throw exception;
            return concurrent;
        }
    }

    private InlineMathTranscription fromRecord(InlineMathTranscriptionRecord record,
                                               String sourceText,
                                               boolean cached) {
        MathTranscriptionStatus status;
        try {
            status = MathTranscriptionStatus.valueOf(record.getStatus());
        } catch (RuntimeException exception) {
            status = MathTranscriptionStatus.UNAVAILABLE;
        }
        return new InlineMathTranscription(record.getBlockId(), record.getStartOffset(),
                record.getEndOffset(), sourceText, record.getLatex(), status,
                record.getConfidence() == null ? 0 : record.getConfidence(),
                record.getProviderVersion(), record.getMessage(), cached);
    }

    private InlineMathTranscription unavailable(DocumentBlock block,
                                                InlineMathFragment fragment,
                                                String provider,
                                                String message,
                                                boolean cached) {
        return new InlineMathTranscription(block.id(), fragment.start(), fragment.end(),
                fragment.sourceText(), "", MathTranscriptionStatus.UNAVAILABLE, 0,
                provider, message, cached);
    }

    private void validate(PaperLayoutArtifact artifact,
                          DocumentBlock block,
                          InlineMathFragment fragment) {
        if (artifact == null || artifact.paperId() == null || block == null || fragment == null
                || block.page() < 1 || block.page() > artifact.pageCount()
                || artifact.blocks().stream().noneMatch(candidate -> candidate.id().equals(block.id()))
                || fragment.start() < 0 || fragment.end() > block.text().length()
                || !block.text().substring(fragment.start(), fragment.end()).equals(fragment.sourceText())) {
            throw new IllegalArgumentException("invalid inline math transcription target");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
