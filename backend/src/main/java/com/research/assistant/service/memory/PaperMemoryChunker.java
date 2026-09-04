package com.research.assistant.service.memory;

import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import org.springframework.beans.factory.annotation.Autowired;
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

/** Builds one stable, ordered paper source envelope with provenance markers.
 * It is not a model chunker: the active understanding path sends this source
 * together with the page images in one request. */
@Component
public class PaperMemoryChunker {

    public static final String VERSION = "whole-paper-input-v1";

    private final int maxCharacters;

    @Autowired
    public PaperMemoryChunker(
            @Value("${app.paper-memory.source-max-chars:36000}") int maxCharacters) {
        this.maxCharacters = Math.max(1_000, maxCharacters);
    }

    /** Creates exactly one ordered representation of the complete scientific
     * content. The character limit only prevents a single unusually large
     * block from creating an unbounded transport string. */
    public PaperMemoryChunk wholePaper(PaperStructure structure,
                                       PaperLayoutArtifact artifact) {
        if (structure == null || artifact == null) {
            throw new IllegalArgumentException("论文结构与版面制品不能为空");
        }
        Map<String, DocumentBlock> blocks = new LinkedHashMap<>();
        artifact.blocks().stream()
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .forEach(block -> blocks.put(block.id(), block));

        ChunkDraft draft = new ChunkDraft("whole-paper", List.of());
        for (String blockId : structure.readingOrder()) {
            DocumentBlock block = blocks.get(blockId);
            if (block == null || skip(block)) continue;
            for (String segment : renderSegments(block)) {
                draft.add(block, segment);
            }
        }
        String text = draft.text.toString().strip();
        String fingerprint = fingerprint(structure, draft, text);
        return new PaperMemoryChunk(
                "pmc-whole-" + fingerprint.substring(0, 16), fingerprint, 1,
                "whole-paper", List.of(),
                draft.pageStart == Integer.MAX_VALUE ? 0 : draft.pageStart,
                draft.pageEnd, List.copyOf(draft.blockIds), text);
    }

    private boolean skip(DocumentBlock block) {
        return block.role() == DocumentBlockRole.TITLE
                || block.role() == DocumentBlockRole.REFERENCE
                || block.role() == DocumentBlockRole.HEADER
                || block.role() == DocumentBlockRole.FOOTER
                || block.role() == DocumentBlockRole.MARGIN_METADATA
                || block.role() == DocumentBlockRole.AUTHOR
                || isReferenceHeading(block);
    }

    private boolean isReferenceHeading(DocumentBlock block) {
        if (block.role() != DocumentBlockRole.HEADING || block.text() == null) return false;
        String normalized = block.text().strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("(?:\\d+(?:\\.\\d+)*\\s+)?(?:references|bibliography)");
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

    }
}
