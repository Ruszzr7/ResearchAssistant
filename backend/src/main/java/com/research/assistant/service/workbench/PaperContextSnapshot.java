package com.research.assistant.service.workbench;

import java.time.Instant;
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
                                   String profileContext,
                                   List<ConversationItem> conversationTurns,
                                   List<ObservationItem> relevantObservations,
                                   List<String> sourcePriority,
                                   Budget budget,
                                   boolean truncated,
                                   Instant assembledAt) {

    public static final String SCHEMA_VERSION = "paper-context-v1";

    public PaperContextSnapshot {
        schemaVersion = safe(schemaVersion, SCHEMA_VERSION);
        documentHash = safe(documentHash, "");
        parserVersion = safe(parserVersion, "");
        conversationId = safe(conversationId, "");
        question = safe(question, "");
        selectedText = safe(selectedText, "");
        selectedBlockIds = copy(selectedBlockIds);
        profileContext = safe(profileContext, "");
        conversationTurns = copy(conversationTurns);
        relevantObservations = copy(relevantObservations);
        sourcePriority = copy(sourcePriority);
        budget = budget == null ? new Budget(0, 0, 0, 0, 0) : budget;
        assembledAt = assembledAt == null ? Instant.now() : assembledAt;
    }

    public boolean matches(WorkbenchRunTrace trace) {
        if (trace == null || !SCHEMA_VERSION.equals(schemaVersion)
                || trace.invocation().paperIds().size() != 1
                || trace.artifactVersions().isEmpty()) return false;
        WorkbenchPlan.ArtifactVersion version = trace.artifactVersions().get(0);
        return paperId == version.paperId()
                && documentHash.equals(version.documentHash())
                && parserVersion.equals(version.parserVersion())
                && question.equals(trace.invocation().question())
                && conversationId.equals(trace.invocation().conversationId());
    }

    /** Auxiliary memory is explicitly non-evidential; current evidence remains the only citation source. */
    public String modelQuestion() {
        return modelQuestion(Integer.MAX_VALUE);
    }

    /** Renders the highest-priority auxiliary sources inside a separate model-context budget. */
    public String modelQuestion(int maximumCharacters) {
        int safeMaximum = Math.max(512, maximumCharacters);
        StringBuilder value = new StringBuilder();
        value.append("上下文使用规则：当前 evidence（其中 selected=true 的选区证据优先）是论文事实的唯一回答依据；")
                .append("下面的对话、论文画像和旧观察只用于理解追问与检索方向，不能替代本轮 evidence 或作为引用。")
                .append("若内容冲突，以当前 PDF 版本和本轮 evidence 为准。\n\n");
        String suffix = "\n当前问题：" + question;
        int auxiliaryLimit = Math.max(0, safeMaximum - value.length() - suffix.length());
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
        value.append(auxiliary).append(suffix);
        return value.toString();
    }

    /** Retrieval may use memories as query expansion, but never returns them as answer evidence. */
    public String retrievalQuery() {
        StringBuilder value = new StringBuilder(question);
        if (!selectedText.isBlank()) value.append("\n当前选区：").append(selectedText);
        for (ConversationItem item : conversationTurns.stream()
                .skip(Math.max(0, conversationTurns.size() - 2L)).toList()) {
            value.append("\n历史追问：").append(item.question())
                    .append(" ").append(item.answer());
        }
        for (ObservationItem item : relevantObservations) {
            value.append("\n相关观察：").append(item.claimText());
        }
        if (!profileContext.isBlank()) value.append("\n论文画像：").append(profileContext);
        return value.toString();
    }

    public record ConversationItem(long turnId, String question, String answer) {
        public ConversationItem {
            question = safe(question, "");
            answer = safe(answer, "");
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

    private static void appendWithin(StringBuilder target, String value, int maximumCharacters) {
        if (value == null || value.isEmpty() || target.length() >= maximumCharacters) return;
        int remaining = maximumCharacters - target.length();
        target.append(value, 0, Math.min(value.length(), remaining));
    }
}
