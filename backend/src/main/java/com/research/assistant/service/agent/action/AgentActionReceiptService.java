package com.research.assistant.service.agent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AgentActionReceiptRequest;
import com.research.assistant.dto.agent.AgentActionReceiptResult;
import com.research.assistant.dto.agent.AgentTurnResult;
import com.research.assistant.entity.AgentRunRecord;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.entity.PaperAnnotation;
import com.research.assistant.entity.ResearchMessage;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.mapper.PaperAnnotationMapper;
import com.research.assistant.mapper.ResearchMessageMapper;
import com.research.assistant.service.agent.runtime.AgentRunStatus;
import com.research.assistant.service.agent.runtime.AgentRuntimeService;
import com.research.assistant.service.agent.runtime.AgentToolCallStatus;
import com.research.assistant.service.agent.source.GroundedAnswer;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import com.research.assistant.service.agent.source.PaperSourceCatalogService;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentActionReceiptService {
    private final ActionTicketService ticketService;
    private final AgentToolCallMapper toolCallMapper;
    private final PaperAnnotationMapper annotationMapper;
    private final PaperSourceCatalogService sourceService;
    private final AgentRuntimeService runtimeService;
    private final ResearchMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public AgentActionReceiptService(ActionTicketService ticketService, AgentToolCallMapper toolCallMapper,
                                     PaperAnnotationMapper annotationMapper, PaperSourceCatalogService sourceService,
                                     AgentRuntimeService runtimeService, ResearchMessageMapper messageMapper,
                                     ObjectMapper objectMapper) {
        this.ticketService = ticketService;
        this.toolCallMapper = toolCallMapper;
        this.annotationMapper = annotationMapper;
        this.sourceService = sourceService;
        this.runtimeService = runtimeService;
        this.messageMapper = messageMapper;
        this.objectMapper = objectMapper;
    }

    public AgentActionReceiptResult accept(AgentActionReceiptRequest request) {
        ActionTicketPayload payload = ticketService.verify(request.ticket());
        AgentToolCallRecord call = toolCallMapper.selectByToolCallId(payload.toolCallId());
        if (AgentToolCallStatus.COMPLETED.name().equals(call.getStatus())) return completedResult(payload, call);
        if (!AgentToolCallStatus.WAITING_CLIENT.name().equals(call.getStatus())) {
            throw new IllegalArgumentException("tool call is not waiting for a client receipt");
        }
        if (!request.success()) {
            String error = safe(request.clientError(), "client action failed");
            runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.FAILED, null,
                    "CLIENT_ACTION_FAILED", error);
            failRun(payload.runId(), error);
            return new AgentActionReceiptResult(payload.runId(), payload.toolCallId(), "FAILED", null, error);
        }

        PaperSourceCatalog catalog = sourceService.latest(payload.paperId());
        if (!catalog.documentHash().equals(payload.documentHash())) {
            runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.FAILED, null,
                    "STALE_DOCUMENT_VERSION", "PDF version changed before action receipt");
            failRun(payload.runId(), "PDF version changed before action receipt");
            throw new IllegalArgumentException("PDF version changed; action was not accepted");
        }
        var source = catalog.requireObject(payload.sourceObjectId());
        var locators = catalog.requireLocators(source.sourceObjectId());
        validateClientCoordinates(request.actualCoordinates(), locators, payload.actionType());

        Long annotationId = payload.actionType().createsAnnotation()
                ? persistAnnotation(payload, catalog, request.actualCoordinates()) : null;
        String message = successMessage(payload.actionType());
        String resultJson = actionResultJson(payload, annotationId, message);
        runtimeService.transitionToolCall(call.getToolCallId(), AgentToolCallStatus.COMPLETED, resultJson, null, null);
        boolean allFinished = toolCallMapper.selectByRunId(payload.runId()).stream()
                .filter(tool -> !Boolean.TRUE.equals(tool.getReadOnly()))
                .allMatch(tool -> AgentToolCallStatus.COMPLETED.name().equals(tool.getStatus())
                        || tool.getToolCallId().equals(payload.toolCallId()));
        String completionMessage = allFinished ? completionMessage(payload.runId(), message) : message;
        if (allFinished) completeRun(payload.runId(), completionMessage);
        return new AgentActionReceiptResult(payload.runId(), payload.toolCallId(),
                allFinished ? "COMPLETED" : "WAITING_CLIENT", annotationId, completionMessage);
    }

    private Long persistAnnotation(ActionTicketPayload payload, PaperSourceCatalog catalog,
                                   Map<String, Object> actualCoordinates) {
        PaperAnnotation existing = annotationMapper.selectByAgentToolCallId(payload.toolCallId());
        if (existing != null) return existing.getId();
        var locators = catalog.requireLocators(payload.sourceObjectId());
        PaperAnnotation annotation = new PaperAnnotation();
        annotation.setPaperId(payload.paperId());
        annotation.setType(payload.actionType().name());
        annotation.setPage(locators.get(0).pageNumber());
        annotation.setColor(payload.color() == null || payload.color().isBlank() ? "#ffeb3b" : payload.color());
        annotation.setNote(payload.content());
        annotation.setAiGenerated(true);
        annotation.setAgentToolCallId(payload.toolCallId());
        annotation.setDocumentHash(payload.documentHash());
        annotation.setSourceObjectId(payload.sourceObjectId());
        annotation.setCompleted(false);
        try {
            annotation.setCoordinatesJson(objectMapper.writeValueAsString(
                    coordinates(payload.actionType(), locators, actualCoordinates)));
            annotationMapper.insert(annotation);
            return annotation.getId();
        } catch (DuplicateKeyException duplicate) {
            PaperAnnotation raced = annotationMapper.selectByAgentToolCallId(payload.toolCallId());
            if (raced != null) return raced.getId();
            throw duplicate;
        } catch (Exception error) {
            throw new IllegalStateException("failed to persist agent annotation", error);
        }
    }

    static Map<String, Object> coordinates(PaperActionType type,
                                           List<com.research.assistant.service.agent.source.SourceLocator> locators,
                                           Map<String, Object> actualCoordinates) {
        List<NormalizedBoundingBox> expected = locators.stream()
                .flatMap(locator -> locator.rects().stream()).toList();
        List<NormalizedBoundingBox> actual = clientRects(actualCoordinates == null
                ? null : actualCoordinates.get("rects"));
        List<NormalizedBoundingBox> selected = actual.isEmpty() ? expected : actual;
        List<Map<String, Double>> quads = new ArrayList<>();
        selected.forEach(rect -> quads.add(Map.of(
                // Keep the same top-left-origin order as boundingBoxToViewportQuad:
                // first edge is the lower text edge, which is used by underlines.
                "x1", rect.x(), "y1", rect.bottom(), "x2", rect.right(), "y2", rect.bottom(),
                "x3", rect.right(), "y3", rect.y(), "x4", rect.x(), "y4", rect.y())));
        Map<String, Object> coordinates = new LinkedHashMap<>();
        coordinates.put(type == PaperActionType.NOTE || type == PaperActionType.COMMENT ? "anchorQuads" : "quads", quads);
        coordinates.put("coordinateSpace", "PDF_NORMALIZED");
        coordinates.put("source", actual.isEmpty() ? "AGENT_TRUSTED_LOCATOR" : "AGENT_PDFIUM");
        String anchorText = locators.stream().map(com.research.assistant.service.agent.source.SourceLocator::targetText)
                .filter(text -> text != null && !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (!anchorText.isBlank()) coordinates.put("anchorText", safe(anchorText, ""));
        if (actualCoordinates != null && actualCoordinates.get("textAnchor") instanceof Map<?, ?> textAnchor) {
            coordinates.put("textAnchor", new LinkedHashMap<>(textAnchor));
        }
        if (actualCoordinates != null && "TEXT_RANGE".equals(actualCoordinates.get("geometryKind"))) {
            // 客户端已经用 PDFium 字符范围重新选取时，必须保留普通文本几何；
            // 否则公式来源会在重新加载后再次走区域底部专用下划线。
            coordinates.put("geometryKind", "TEXT_RANGE");
        } else if (!locators.isEmpty() && locators.stream()
                .allMatch(locator -> locator.precision() == com.research.assistant.service.pdf.layout.EvidenceLocator.Precision.FORMULA_REGION)) {
            coordinates.put("geometryKind", "FORMULA_REGION");
        }
        return coordinates;
    }

    private void completeRun(String runId, String text) {
        AgentRunRecord run = runtimeService.getRun(runId);
        AgentTurnResult pending = pendingResult(run);
        boolean composite = hasPendingAnswer(pending);
        if (AgentRunStatus.WAITING_CLIENT.name().equals(run.getStatus())) {
            runtimeService.transitionRun(runId, AgentRunStatus.RUNNING, null, null, null);
        }
        try {
            AgentTurnRecord turn = runtimeService.getTurnForRun(runId);
            String finalText = composite ? merge(pending.message(), text) : text;
            AgentTurnResult result = new AgentTurnResult(turn.getTurnId(), runId, AgentRunStatus.COMPLETED.name(),
                    finalText, composite ? pending.citations() : List.of(),
                    composite ? pending.evidence() : List.of());
            String resultJson = objectMapper.writeValueAsString(result);
            ResearchMessage message = new ResearchMessage();
            message.setSessionId(turn.getSessionId()); message.setMessageKey("agent-assistant-" + UUID.randomUUID());
            message.setRole("ASSISTANT"); message.setMessageType(composite ? "CHAT" : "ACTION_RECEIPT");
            message.setMessageStatus("FINAL"); message.setContent(finalText); message.setRunId(runId);
            message.setAgentTurnId(turn.getId());
            if (composite) {
                message.setEvidenceJson(resultJson);
                message.setEvidenceSchemaVersion("ground-evidence-v2");
            }
            messageMapper.insert(message);
            runtimeService.bindFinalMessage(turn.getTurnId(), message.getMessageKey());
            runtimeService.transitionRun(runId, AgentRunStatus.COMPLETED, resultJson, null, null);
        } catch (Exception error) {
            throw new IllegalStateException("failed to finalize action run", error);
        }
    }

    private void failRun(String runId, String error) {
        AgentRunRecord run = runtimeService.getRun(runId);
        AgentTurnResult pending = pendingResult(run);
        boolean composite = hasPendingAnswer(pending);
        if (AgentRunStatus.WAITING_CLIENT.name().equals(run.getStatus())) {
            runtimeService.transitionRun(runId, AgentRunStatus.RUNNING, null, null, null);
            if (!composite) {
                runtimeService.transitionRun(runId, AgentRunStatus.FAILED, null, "CLIENT_ACTION_FAILED", error);
                return;
            }
            try {
                AgentTurnRecord turn = runtimeService.getTurnForRun(runId);
                String finalText = merge(pending.message(), "页面操作失败：" + error);
                AgentTurnResult result = new AgentTurnResult(turn.getTurnId(), runId,
                        AgentRunStatus.FAILED.name(), finalText, pending.citations(), pending.evidence());
                String resultJson = objectMapper.writeValueAsString(result);
                ResearchMessage message = new ResearchMessage();
                message.setSessionId(turn.getSessionId()); message.setMessageKey("agent-assistant-" + UUID.randomUUID());
                message.setRole("ASSISTANT"); message.setMessageType("CHAT"); message.setMessageStatus("FINAL");
                message.setContent(finalText); message.setRunId(runId); message.setAgentTurnId(turn.getId());
                message.setEvidenceJson(resultJson); message.setEvidenceSchemaVersion("ground-evidence-v2");
                messageMapper.insert(message);
                runtimeService.bindFinalMessage(turn.getTurnId(), message.getMessageKey());
                runtimeService.transitionRun(runId, AgentRunStatus.FAILED, resultJson,
                        "CLIENT_ACTION_FAILED", error);
            } catch (Exception serializationError) {
                throw new IllegalStateException("failed to retain composite answer", serializationError);
            }
        }
    }

    private AgentTurnResult pendingResult(AgentRunRecord run) {
        if (run == null || run.getResultJson() == null || run.getResultJson().isBlank()) return null;
        try {
            return objectMapper.readValue(run.getResultJson(), AgentTurnResult.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasPendingAnswer(AgentTurnResult result) {
        return result != null && result.message() != null && !result.message().isBlank()
                && !"正在执行页面操作。".equals(result.message());
    }

    private static String merge(String answer, String actionStatus) {
        return answer + "\n\n" + actionStatus;
    }

    private AgentActionReceiptResult completedResult(ActionTicketPayload payload, AgentToolCallRecord call) {
        PaperAnnotation annotation = annotationMapper.selectByAgentToolCallId(payload.toolCallId());
        return new AgentActionReceiptResult(payload.runId(), payload.toolCallId(), "COMPLETED",
                annotation == null ? null : annotation.getId(), successMessage(payload.actionType()));
    }

    /** Summarize every physical action in a heterogeneous batch once all receipts arrive. */
    private String completionMessage(String runId, String fallback) {
        Map<PaperActionType, Integer> counts = new LinkedHashMap<>();
        for (AgentToolCallRecord tool : toolCallMapper.selectByRunId(runId)) {
            if (Boolean.TRUE.equals(tool.getReadOnly())
                    || !AgentToolCallStatus.COMPLETED.name().equals(tool.getStatus())) continue;
            try {
                var result = objectMapper.readTree(tool.getResultJson());
                String value = result.path("actionType").asText("");
                PaperActionType type = PaperActionType.valueOf(value);
                counts.merge(type, 1, Integer::sum);
            } catch (Exception ignored) {
                // Older/single-action records may not carry an actionType. Keep the
                // per-action fallback rather than failing an otherwise completed run.
            }
        }
        if (counts.isEmpty()) return fallback;
        if (counts.size() == 1 && counts.values().iterator().next() == 1) return fallback;
        String summary = counts.entrySet().stream()
                .map(entry -> actionLabel(entry.getKey()) + " " + entry.getValue() + " 项")
                .reduce((left, right) -> left + "、" + right).orElse(fallback);
        return "已完成" + summary + "。";
    }

    private static String actionLabel(PaperActionType type) {
        return switch (type) {
            case JUMP -> "跳转";
            case HIGHLIGHT -> "高亮";
            case UNDERLINE -> "下划线";
            case NOTE -> "笔记";
            case COMMENT -> "批注";
        };
    }

    private String actionResultJson(ActionTicketPayload payload, Long annotationId, String message) {
        try { return objectMapper.writeValueAsString(Map.of("actionType", payload.actionType(), "annotationId",
                annotationId == null ? "" : annotationId, "message", message)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    static void validateClientCoordinates(Map<String, Object> coordinates,
                                          List<com.research.assistant.service.agent.source.SourceLocator> locators,
                                          PaperActionType actionType) {
        boolean textAction = actionType == PaperActionType.HIGHLIGHT || actionType == PaperActionType.UNDERLINE;
        if (textAction && (coordinates == null || coordinates.isEmpty() || !coordinates.containsKey("rects"))) {
            throw new IllegalArgumentException("文本标记必须回传 PDFium 字符矩形");
        }
        if (coordinates == null || coordinates.isEmpty()) return;
        Object page = coordinates.get("page");
        int expectedPage = locators.get(0).pageNumber();
        if (page instanceof Number value && value.intValue() != expectedPage) {
            throw new IllegalArgumentException("client receipt page does not match trusted target");
        }
        Object rects = coordinates.get("rects");
        if (rects == null) return;
        if (!(rects instanceof List<?> actualRects)) {
            throw new IllegalArgumentException("client receipt rectangles are invalid");
        }
        if (actualRects.isEmpty()) {
            throw new IllegalArgumentException("client receipt rectangles are empty");
        }
        List<com.research.assistant.service.pdf.layout.NormalizedBoundingBox> expectedRects = locators.stream()
                .flatMap(locator -> locator.rects().stream()).toList();
        List<NormalizedBoundingBox> parsed = clientRects(rects);
        if (parsed.size() != actualRects.size()) {
            throw new IllegalArgumentException("client receipt rectangles are invalid");
        }
        for (int index = 0; index < parsed.size(); index++) {
            NormalizedBoundingBox actual = parsed.get(index);
            if (expectedRects.stream().anyMatch(expected -> overlaps(actual, expected))) continue;
            // PDFium 的自然选区是连续字符范围；解析器偶尔会在同一算法/段落内
            // 漏掉一个物理行。首尾行仍必须直接命中可信来源；只有夹在两者之间、
            // 且仍在同一栏水平范围内的中间行才可作为连续选区通过。
            boolean intermediate = index > 0 && index < parsed.size() - 1;
            if (intermediate && withinTrustedTextSpan(actual, expectedRects)) continue;
            throw new IllegalArgumentException("client receipt rectangles are outside the trusted target");
        }
        if (textAction) {
            // Text actions must report the character geometry used by the client;
            // accepting an empty rectangle list would silently fall back to a
            // coarse parser block.
            if (parsed.isEmpty()) throw new IllegalArgumentException("client receipt rectangles are empty");
        }
    }

    private static List<NormalizedBoundingBox> clientRects(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<NormalizedBoundingBox> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof Map<?, ?> rect)) return List.of();
            Double x = number(rect.get("x"));
            Double y = number(rect.get("y"));
            Double width = number(rect.get("width"));
            Double height = number(rect.get("height"));
            if (x == null || y == null || width == null || height == null
                    || !Double.isFinite(x) || !Double.isFinite(y)
                    || !Double.isFinite(width) || !Double.isFinite(height)
                    || x < 0 || y < 0 || width <= 0 || height <= 0
                    || x + width > 1.000001 || y + height > 1.000001) return List.of();
            result.add(new NormalizedBoundingBox(x, y, width, height));
        }
        return List.copyOf(result);
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static boolean overlaps(NormalizedBoundingBox actual, NormalizedBoundingBox expected) {
        double left = Math.max(actual.x(), expected.x());
        double top = Math.max(actual.y(), expected.y());
        double right = Math.min(actual.right(), expected.right());
        double bottom = Math.min(actual.bottom(), expected.bottom());
        double intersection = Math.max(0, right - left) * Math.max(0, bottom - top);
        double area = actual.width() * actual.height();
        return area > 0 && intersection / area >= 0.12;
    }

    private static boolean withinTrustedTextSpan(NormalizedBoundingBox actual,
                                                 List<NormalizedBoundingBox> expected) {
        if (expected.isEmpty()) return false;
        double left = expected.stream().mapToDouble(NormalizedBoundingBox::x).min().orElse(0);
        double top = expected.stream().mapToDouble(NormalizedBoundingBox::y).min().orElse(0);
        double right = expected.stream().mapToDouble(NormalizedBoundingBox::right).max().orElse(0);
        double bottom = expected.stream().mapToDouble(NormalizedBoundingBox::bottom).max().orElse(0);
        double horizontalIntersection = Math.max(0, Math.min(actual.right(), right) - Math.max(actual.x(), left));
        double horizontalCoverage = actual.width() <= 0 ? 0 : horizontalIntersection / actual.width();
        return horizontalCoverage >= .75
                && actual.y() >= top - .01
                && actual.bottom() <= bottom + .01;
    }

    private static String successMessage(PaperActionType type) {
        return switch (type) {
            case JUMP -> "已跳转到目标位置。";
            case HIGHLIGHT -> "已完成高亮。";
            case UNDERLINE -> "已添加下划线。";
            case NOTE -> "已添加笔记。";
            case COMMENT -> "已添加批注。";
        };
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.length() > 900 ? value.substring(0, 900) : value;
    }
}
