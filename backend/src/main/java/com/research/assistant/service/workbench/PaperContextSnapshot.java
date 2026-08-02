package com.research.assistant.service.workbench;

import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.SelectionAnchor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Persisted, bounded context assembled by the server for one selection question. */
public record PaperContextSnapshot(String schemaVersion,
                                   long paperId,
                                   String documentHash,
                                   String parserVersion,
                                   String conversationId,
                                   String question,
                                   String selectedText,
                                   List<String> selectedBlockIds,
                                   String selectionFingerprint,
                                   String profileContext,
                                   List<ConversationItem> conversationTurns,
                                   List<ObservationItem> relevantObservations,
                                   List<String> sourcePriority,
                                   Budget budget,
                                   boolean truncated,
                                   Instant assembledAt) {

    public static final String SCHEMA_VERSION = "paper-context-v3";
    public static final int MAX_RETRIEVAL_QUERY_CHARACTERS = 6_000;

    public PaperContextSnapshot {
        schemaVersion = safe(schemaVersion, SCHEMA_VERSION);
        documentHash = safe(documentHash, "");
        parserVersion = safe(parserVersion, "");
        conversationId = safe(conversationId, "");
        question = safe(question, "");
        selectedText = safe(selectedText, "");
        selectedBlockIds = copy(selectedBlockIds);
        selectionFingerprint = safe(selectionFingerprint, "");
        profileContext = safe(profileContext, "");
        conversationTurns = copy(conversationTurns);
        relevantObservations = copy(relevantObservations);
        sourcePriority = copy(sourcePriority);
        budget = budget == null ? new Budget(0, 0, 0, 0, 0) : budget;
        assembledAt = assembledAt == null ? Instant.now() : assembledAt;
    }

    public boolean matches(WorkbenchRunTrace trace, SelectionAnchor anchor) {
        if (trace == null || !SCHEMA_VERSION.equals(schemaVersion)
                || trace.invocation().paperIds().size() != 1
                || trace.artifactVersions().isEmpty()) return false;
        WorkbenchPlan.ArtifactVersion version = trace.artifactVersions().get(0);
        return paperId == version.paperId()
                && documentHash.equals(version.documentHash())
                && parserVersion.equals(version.parserVersion())
                && question.equals(trace.invocation().question())
                && conversationId.equals(trace.invocation().conversationId())
                && selectionFingerprint.equals(selectionFingerprint(anchor));
    }

    /** Auxiliary memory is explicitly non-evidential; current evidence remains the only citation source. */
    public String modelQuestion() {
        return modelQuestion(Integer.MAX_VALUE);
    }

    /** Renders the highest-priority auxiliary sources inside a separate model-context budget. */
    public String modelQuestion(int maximumCharacters) {
        int safeMaximum = Math.max(0, maximumCharacters);
        if (safeMaximum == 0) return "";
        String rules = "上下文规则：仅当前 evidence 可支持论文事实，selected=true 的选区证据优先；"
                + "历史、论文画像和旧观察只帮助理解与检索。冲突时以当前 PDF 版本的本轮 evidence 为准。\n\n";
        String questionLabel = "\n当前问题：";
        if (rules.length() + questionLabel.length() >= safeMaximum) {
            return bounded("当前问题：" + question, safeMaximum);
        }
        int questionLimit = safeMaximum - rules.length() - questionLabel.length();
        String renderedQuestion = bounded(question, questionLimit);
        String suffix = questionLabel + renderedQuestion;
        int auxiliaryLimit = Math.max(0, safeMaximum - rules.length() - suffix.length());
        StringBuilder auxiliary = new StringBuilder();
        if (!conversationTurns.isEmpty()) {
            appendWithin(auxiliary, "同一论文与同一对话的服务端历史：\n", auxiliaryLimit);
            for (int index = conversationTurns.size() - 1; index >= 0; index--) {
                ConversationItem item = conversationTurns.get(index);
                appendWithin(auxiliary, "- 用户：" + item.question() + "\n"
                        + "  论文助手：" + item.answer() + "\n", auxiliaryLimit);
                if (auxiliary.length() >= auxiliaryLimit) break;
            }
            appendWithin(auxiliary, "\n", auxiliaryLimit);
        }
        if (!profileContext.isBlank()) {
            appendWithin(auxiliary,
                    "当前 PDF 版本的论文画像（检索提示，非直接证据）：\n"
                            + profileContext + "\n\n", auxiliaryLimit);
        }
        if (!relevantObservations.isEmpty()) {
            appendWithin(auxiliary,
                    "此前经证据门禁保存的相关观察（检索提示，必须在本轮 evidence 中重新核对）：\n",
                    auxiliaryLimit);
            for (ObservationItem item : relevantObservations) {
                StringBuilder line = new StringBuilder("- ").append(item.claimText());
                if (!item.evidenceBlockIds().isEmpty()) {
                    line.append(" [历史 block: ")
                            .append(String.join(", ", item.evidenceBlockIds())).append(']');
                }
                line.append('\n');
                appendWithin(auxiliary, line.toString(), auxiliaryLimit);
                if (auxiliary.length() >= auxiliaryLimit) break;
            }
            appendWithin(auxiliary, "\n", auxiliaryLimit);
        }
        return rules + auxiliary + suffix;
    }

    /** Retrieval may use memories as query expansion, but never returns them as answer evidence. */
    public String retrievalQuery() {
        return retrievalQuery(MAX_RETRIEVAL_QUERY_CHARACTERS);
    }

    public String retrievalQuery(int maximumCharacters) {
        int safeMaximum = Math.max(0, maximumCharacters);
        StringBuilder value = new StringBuilder();
        appendWithin(value, question, safeMaximum);
        if (!selectedText.isBlank()) appendWithin(value, "\n当前选区：" + selectedText, safeMaximum);
        for (ConversationItem item : conversationTurns.stream()
                .skip(Math.max(0, conversationTurns.size() - 2L)).toList()) {
            appendWithin(value, "\n历史追问：" + item.question(), safeMaximum);
        }
        return value.toString();
    }

    /** Recent grounded blocks are a weak follow-up hint, never a replacement for current-query relevance. */
    public List<String> preferredEvidenceBlockIds() {
        if (conversationTurns.isEmpty()) return List.of();
        return conversationTurns.get(conversationTurns.size() - 1).evidenceBlockIds();
    }

    /** Stable identity of the canonical, version-bound selection used to assemble this snapshot. */
    public static String selectionFingerprint(SelectionAnchor anchor) {
        if (anchor == null) return "none";
        StringBuilder canonical = new StringBuilder();
        component(canonical, anchor.paperId() == null ? "" : anchor.paperId().toString());
        component(canonical, Integer.toString(anchor.page()));
        component(canonical, anchor.kind().name());
        component(canonical, anchor.mappingStatus().name());
        component(canonical, anchor.contentType().name());
        component(canonical, anchor.evidenceUse().name());
        component(canonical, anchor.documentHash());
        component(canonical, anchor.parserVersion());
        component(canonical, anchor.anchorText());
        for (String blockId : anchor.blockIds()) component(canonical, blockId);
        for (NormalizedBoundingBox box : anchor.boxes()) {
            component(canonical, Double.toHexString(box.x()));
            component(canonical, Double.toHexString(box.y()));
            component(canonical, Double.toHexString(box.width()));
            component(canonical, Double.toHexString(box.height()));
        }
        if (anchor.tokenRange() != null) {
            component(canonical, Integer.toString(anchor.tokenRange().start()));
            component(canonical, Integer.toString(anchor.tokenRange().end()));
        }
        for (com.research.assistant.service.pdf.layout.SelectionBlockRange range : anchor.blockRanges()) {
            component(canonical, range.blockId());
            component(canonical, Integer.toString(range.start()));
            component(canonical, Integer.toString(range.end()));
        }
        if (anchor.clientTextAnchor() != null) {
            component(canonical, Integer.toString(anchor.clientTextAnchor().version()));
            component(canonical, Integer.toString(anchor.clientTextAnchor().page()));
            component(canonical, anchor.clientTextAnchor().documentFingerprint());
            component(canonical, Integer.toString(anchor.clientTextAnchor().textMapVersion()));
            component(canonical, anchor.clientTextAnchor().engine());
            component(canonical, String.valueOf(anchor.clientTextAnchor().charStart()));
            component(canonical, String.valueOf(anchor.clientTextAnchor().charEnd()));
            for (com.research.assistant.service.pdf.layout.ClientContentSegment segment
                    : anchor.clientTextAnchor().contentSegments()) {
                component(canonical, segment.type().name());
                component(canonical, Integer.toString(segment.charStart()));
                component(canonical, Integer.toString(segment.charEnd()));
                component(canonical, segment.sourceText());
            }
            for (com.research.assistant.service.pdf.layout.ClientTextRange range : anchor.clientTextAnchor().ranges()) {
                component(canonical, Integer.toString(range.itemIndex()));
                component(canonical, Integer.toString(range.spanIndex()));
                component(canonical, Integer.toString(range.startOffset()));
                component(canonical, Integer.toString(range.endOffset()));
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record ConversationItem(long turnId,
                                   String question,
                                   String answer,
                                   List<String> evidenceBlockIds) {
        public ConversationItem {
            question = safe(question, "");
            answer = safe(answer, "");
            evidenceBlockIds = copy(evidenceBlockIds);
        }
    }

    public record ObservationItem(long observationId,
                                  String claimText,
                                  List<String> evidenceBlockIds,
                                  int confirmationCount) {
        public ObservationItem {
            claimText = safe(claimText, "");
            evidenceBlockIds = copy(evidenceBlockIds);
            confirmationCount = Math.max(1, confirmationCount);
        }
    }

    public record Budget(int maximumCharacters,
                         int selectedCharacters,
                         int conversationCharacters,
                         int observationCharacters,
                         int profileCharacters) {
        public Budget {
            maximumCharacters = Math.max(0, maximumCharacters);
            selectedCharacters = Math.max(0, selectedCharacters);
            conversationCharacters = Math.max(0, conversationCharacters);
            observationCharacters = Math.max(0, observationCharacters);
            profileCharacters = Math.max(0, profileCharacters);
        }

        public int usedCharacters() {
            return selectedCharacters + conversationCharacters + observationCharacters + profileCharacters;
        }
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String bounded(String value, int maximumCharacters) {
        String normalized = value == null ? "" : value;
        return normalized.length() <= maximumCharacters
                ? normalized : normalized.substring(0, maximumCharacters);
    }

    private static void component(StringBuilder target, String value) {
        String safeValue = value == null ? "" : value;
        target.append(safeValue.length()).append(':').append(safeValue).append('|');
    }

    private static void appendWithin(StringBuilder target, String value, int maximumCharacters) {
        if (value == null || value.isEmpty() || target.length() >= maximumCharacters) return;
        int remaining = maximumCharacters - target.length();
        target.append(value, 0, Math.min(value.length(), remaining));
    }
}
