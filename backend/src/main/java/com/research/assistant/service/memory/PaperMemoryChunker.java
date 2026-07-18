package com.research.assistant.service.memory;

import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Splits layout facts at section and semantic block boundaries before model calls. */
@Component
public class PaperMemoryChunker {

    public static final String VERSION = "section-chunker-v1";

    private final int targetCharacters;
    private final int maxCharacters;

    public PaperMemoryChunker(
            @Value("${app.paper-memory.chunk-target-chars:6000}") int targetCharacters,
            @Value("${app.paper-memory.chunk-max-chars:8000}") int maxCharacters) {
        this.targetCharacters = Math.max(1_000, targetCharacters);
        this.maxCharacters = Math.max(this.targetCharacters, maxCharacters);
    }

    public List<PaperMemoryChunk> chunk(PaperStructure structure, PaperLayoutArtifact artifact) {
        if (structure == null || artifact == null) {
            throw new IllegalArgumentException("论文结构与版面制品不能为空");
        }
        Map<String, DocumentBlock> blocks = new LinkedHashMap<>();
        artifact.blocks().stream()
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .forEach(block -> blocks.put(block.id(), block));
        Map<String, PaperStructure.Section> sectionByBlock = sectionIndex(structure.sections());

        List<ChunkDraft> drafts = new ArrayList<>();
        ChunkDraft current = null;
        for (String blockId : structure.readingOrder()) {
            DocumentBlock block = blocks.get(blockId);
            if (block == null || skip(block)) continue;
            PaperStructure.Section section = sectionByBlock.get(block.id());
            String sectionId = section == null ? "unassigned" : section.id();
            List<String> headingPath = section == null
                    ? block.sectionPath() : section.path();
            List<String> segments = renderSegments(block);
            for (String segment : segments) {
                boolean sectionChanged = current != null && !current.sectionId.equals(sectionId);
                boolean wouldOverflow = current != null
                        && current.characterCount() + segment.length() + 1 > maxCharacters;
                boolean targetReached = current != null && current.characterCount() >= targetCharacters;
                if (current == null || sectionChanged || wouldOverflow || targetReached) {
                    current = new ChunkDraft(sectionId, headingPath);
                    drafts.add(current);
                }
                current.add(block, segment);
            }
        }

        List<PaperMemoryChunk> result = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            ChunkDraft draft = drafts.get(index);
            String text = draft.text.toString().strip();
            String fingerprint = fingerprint(structure, draft, text);
            result.add(new PaperMemoryChunk(
                    "pmc-" + fingerprint.substring(0, 16),
                    fingerprint,
                    index + 1,
                    draft.sectionId,
                    draft.headingPath,
                    draft.pageStart == Integer.MAX_VALUE ? 0 : draft.pageStart,
                    draft.pageEnd,
                    List.copyOf(draft.blockIds),
                    text));
        }
        return List.copyOf(result);
    }

    private Map<String, PaperStructure.Section> sectionIndex(List<PaperStructure.Section> sections) {
        Map<String, PaperStructure.Section> result = new LinkedHashMap<>();
        for (PaperStructure.Section section : sections) {
            for (String blockId : section.blockIds()) result.put(blockId, section);
        }
        return result;
    }

    private boolean skip(DocumentBlock block) {
        return block.role() == DocumentBlockRole.TITLE
                || block.role() == DocumentBlockRole.REFERENCE
                || block.role() == DocumentBlockRole.HEADER
                || block.role() == DocumentBlockRole.FOOTER
                || block.role() == DocumentBlockRole.MARGIN_METADATA
                || block.role() == DocumentBlockRole.AUTHOR;
    }

    private List<String> renderSegments(DocumentBlock block) {
        String content = switch (block.role()) {
            case FORMULA -> firstNonBlank(block.latex(), block.text(), "[visual formula region]");
            case TABLE -> firstNonBlank(block.tableText(), block.text(), "[visual table region]");
            case FIGURE -> firstNonBlank(block.text(), "[visual figure region]");
            default -> firstNonBlank(block.text(), "[empty block]");
        };
        String marker = "[" + block.id() + " | page " + block.page() + " | "
                + block.role().name() + "] ";
        int segmentSize = Math.max(256, maxCharacters - marker.length() - 32);
        if (content.length() <= segmentSize) return List.of(marker + content);
        List<String> result = new ArrayList<>();
        int start = 0;
        int segment = 1;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + segmentSize);
            result.add(marker + "(part " + segment++ + ") " + content.substring(start, end));
            start = end;
        }
        return result;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }

    private String fingerprint(PaperStructure structure, ChunkDraft draft, String text) {
        String source = VERSION + "\n" + structure.source().documentHash() + "\n"
                + structure.source().layoutParserVersion() + "\n" + draft.sectionId + "\n"
                + String.join("\u001f", draft.blockIds) + "\n" + text;
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : bytes) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static final class ChunkDraft {
        private final String sectionId;
        private final List<String> headingPath;
        private final LinkedHashSet<String> blockIds = new LinkedHashSet<>();
        private final StringBuilder text = new StringBuilder();
        private int pageStart = Integer.MAX_VALUE;
        private int pageEnd;

        private ChunkDraft(String sectionId, List<String> headingPath) {
            this.sectionId = sectionId;
            this.headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        }

        private void add(DocumentBlock block, String value) {
            if (text.length() > 0) text.append('\n');
            text.append(value);
            blockIds.add(block.id());
            pageStart = Math.min(pageStart, block.page());
            pageEnd = Math.max(pageEnd, block.page());
        }

        private int characterCount() {
            return text.length();
        }
    }
}
