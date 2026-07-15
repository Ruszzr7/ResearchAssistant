package com.research.assistant.service.workbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.workbench.PdfWorkbenchMetricsSnapshot;
import com.research.assistant.entity.PaperLayoutArtifactRecord;
import com.research.assistant.entity.PaperWorkbenchRunRecord;
import com.research.assistant.mapper.PaperLayoutArtifactMapper;
import com.research.assistant.mapper.PaperWorkbenchRunMapper;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.LayoutArtifactProvenance;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Computes aggregate workbench health from persisted artifacts and run traces. */
@Service
public class PdfWorkbenchMetricsService {

    static final int MAX_RUNS = 5_000;
    private static final double LOW_QUALITY_THRESHOLD = 0.68;
    private static final TypeReference<List<DocumentBlock>> BLOCK_LIST = new TypeReference<>() { };

    private final PaperLayoutArtifactMapper artifactMapper;
    private final PaperWorkbenchRunMapper runMapper;
    private final PdfWorkbenchEvalService evalService;
    private final ObjectMapper objectMapper;

    public PdfWorkbenchMetricsService(PaperLayoutArtifactMapper artifactMapper,
                                      PaperWorkbenchRunMapper runMapper,
                                      PdfWorkbenchEvalService evalService,
                                      ObjectMapper objectMapper) {
        this.artifactMapper = artifactMapper;
        this.runMapper = runMapper;
        this.evalService = evalService;
        this.objectMapper = objectMapper;
    }

    public PdfWorkbenchMetricsSnapshot snapshot(int requestedDays) {
        int days = Math.max(1, Math.min(365, requestedDays));
        List<PaperLayoutArtifactRecord> artifacts = safeList(artifactMapper.selectLatestReadyForMetrics());
        List<PaperWorkbenchRunRecord> runs = safeList(runMapper.selectForMetrics(
                LocalDateTime.now().minusDays(days), MAX_RUNS));
        LayoutAccumulator layout = aggregateLayouts(artifacts);
        RunAccumulators runMetrics = aggregateRuns(runs);
        return new PdfWorkbenchMetricsSnapshot(
                Instant.now(), days, runs.size() >= MAX_RUNS,
                layout.toSummary(), runMetrics.anchorSummary(), runMetrics.evidenceSummary(),
                runMetrics.overall().toRunSummary(runMetrics.unreadablePayloads()),
                runMetrics.workflowSummaries(), evalService.evaluate());
    }

    private LayoutAccumulator aggregateLayouts(List<PaperLayoutArtifactRecord> artifacts) {
        LayoutAccumulator metrics = new LayoutAccumulator();
        for (PaperLayoutArtifactRecord record : artifacts) {
            metrics.artifacts++;
            double quality = value(record.getLayoutConfidence());
            metrics.qualitySum += quality;
            if (quality < LOW_QUALITY_THRESHOLD) metrics.lowQuality++;
            LayoutArtifactProvenance provenance;
            try {
                provenance = record.getProvenanceJson() == null || record.getProvenanceJson().isBlank()
                        ? LayoutArtifactProvenance.direct(record.getParserVersion(), quality)
                        : objectMapper.readValue(record.getProvenanceJson(), LayoutArtifactProvenance.class);
            } catch (Exception error) {
                provenance = LayoutArtifactProvenance.direct(record.getParserVersion(), quality);
                metrics.unreadable++;
            }
            if (provenance.fallbackEligible()) metrics.fallbackEligible++;
            if (provenance.fallbackAttempted()) metrics.fallbackAttempted++;
            if (provenance.fallbackAccepted()) {
                metrics.fallbackAccepted++;
                if (provenance.fallbackQuality() != null) {
                    metrics.acceptedGainSum += Math.max(0,
                            provenance.fallbackQuality() - provenance.primaryQuality());
                }
            }
            metrics.parserCounts.merge(provenance.selectedParser(), 1, Integer::sum);
            try {
                List<DocumentBlock> blocks = objectMapper.readValue(record.getBlocksJson(), BLOCK_LIST);
                for (DocumentBlock block : blocks) {
                    DocumentBlockContentMode mode = block.contentMode();
                    if (mode == DocumentBlockContentMode.STRUCTURED) metrics.structuredBlocks++;
                    else if (mode == DocumentBlockContentMode.REGION) metrics.regionBlocks++;
                    else metrics.textBlocks++;
                }
            } catch (Exception error) {
                metrics.unreadable++;
            }
        }
        return metrics;
    }

    private RunAccumulators aggregateRuns(List<PaperWorkbenchRunRecord> runs) {
        MutableRunSummary overall = new MutableRunSummary("ALL");
        Map<WorkbenchPlan.Workflow, MutableRunSummary> workflows = new EnumMap<>(WorkbenchPlan.Workflow.class);
        for (WorkbenchPlan.Workflow workflow : WorkbenchPlan.Workflow.values()) {
            workflows.put(workflow, new MutableRunSummary(workflow.name()));
        }
        int selectionRuns = 0;
        int textAnchors = 0;
        int formulaAnchors = 0;
        int tableAnchors = 0;
        int regionAnchors = 0;
        int completedSelectionRuns = 0;
        int selectionRegionFallbacks = 0;
        double anchorConfidenceSum = 0;
        int claims = 0;
        int groundedClaims = 0;
        int completedResults = 0;
        int noEvidenceFailures = 0;
        int gateRejectedFailures = 0;
        int repairedRuns = 0;
        int comparisonRuns = 0;
        int fullyCoveredComparisons = 0;
        int unreadablePayloads = 0;
        int unreadableResults = 0;

        for (PaperWorkbenchRunRecord run : runs) {
            overall.add(run);
            WorkbenchPlan.Workflow workflow = enumValue(
                    WorkbenchPlan.Workflow.class, run.getWorkflow());
            if (workflow != null) workflows.get(workflow).add(run);
            if (value(run.getRepairCount()) > 0) repairedRuns++;
            if ("NO_EVIDENCE".equals(run.getErrorCode())) noEvidenceFailures++;
            if ("EVIDENCE_GATE_REJECTED".equals(run.getErrorCode())) gateRejectedFailures++;

            JsonNode request = readTree(run.getRequestJson());
            if (request == null) {
                unreadablePayloads++;
            } else if (request.hasNonNull("selectionAnchor")) {
                JsonNode anchor = request.path("selectionAnchor");
                selectionRuns++;
                anchorConfidenceSum += clamp(anchor.path("confidence").asDouble(0));
                String kind = anchor.path("kind").asText("REGION");
                switch (kind) {
                    case "TEXT" -> textAnchors++;
                    case "FORMULA" -> formulaAnchors++;
                    case "TABLE" -> tableAnchors++;
                    default -> regionAnchors++;
                }
            }

            if (!"COMPLETED".equals(run.getStatus())) continue;
            JsonNode result = readTree(run.getResultJson());
            if (result == null) {
                unreadableResults++;
                continue;
            }
            completedResults++;
            if (request != null && request.hasNonNull("selectionAnchor")) {
                completedSelectionRuns++;
                if (result.path("regionFallback").asBoolean(false)) selectionRegionFallbacks++;
            }
            Map<String, Long> paperByEvidence = new LinkedHashMap<>();
            for (JsonNode item : result.path("evidence")) {
                String evidenceId = item.path("evidenceId").asText("");
                long paperId = item.path("paperId").asLong(0);
                if (!evidenceId.isBlank() && paperId > 0) paperByEvidence.put(evidenceId, paperId);
            }
            Set<Long> citedPaperIds = new LinkedHashSet<>();
            for (JsonNode claim : result.path("claims")) {
                claims++;
                boolean grounded = false;
                for (JsonNode evidenceId : claim.path("evidenceIds")) {
                    Long citedPaperId = paperByEvidence.get(evidenceId.asText());
                    if (citedPaperId != null) {
                        grounded = true;
                        citedPaperIds.add(citedPaperId);
                    }
                }
                if (grounded) groundedClaims++;
            }
            if (workflow == WorkbenchPlan.Workflow.PAPER_COMPARISON) {
                comparisonRuns++;
                Set<Long> requiredPaperIds = parsePaperIds(run.getPaperIdsJson());
                if (requiredPaperIds.size() >= 2 && citedPaperIds.containsAll(requiredPaperIds)) {
                    fullyCoveredComparisons++;
                }
            }
        }

        double anchorAverage = selectionRuns == 0 ? 0 : anchorConfidenceSum / selectionRuns;
        PdfWorkbenchMetricsSnapshot.AnchorSummary anchors = new PdfWorkbenchMetricsSnapshot.AnchorSummary(
                selectionRuns, textAnchors, formulaAnchors, tableAnchors, regionAnchors,
                round(anchorAverage), completedSelectionRuns, selectionRegionFallbacks,
                rate(selectionRegionFallbacks, completedSelectionRuns));
        PdfWorkbenchMetricsSnapshot.EvidenceSummary evidence =
                new PdfWorkbenchMetricsSnapshot.EvidenceSummary(
                        completedResults, claims, groundedClaims, rate(groundedClaims, claims),
                        noEvidenceFailures, gateRejectedFailures, repairedRuns,
                        comparisonRuns, fullyCoveredComparisons,
                        rate(fullyCoveredComparisons, comparisonRuns), unreadableResults);
        return new RunAccumulators(overall, workflows, anchors, evidence, unreadablePayloads);
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Set<Long> parsePaperIds(String json) {
        JsonNode node = readTree(json);
        if (node == null || !node.isArray()) return Set.of();
        Set<Long> result = new LinkedHashSet<>();
        for (JsonNode item : node) {
            long value = item.asLong(0);
            if (value > 0) result.add(value);
        }
        return Set.copyOf(result);
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private int value(Integer value) { return value == null ? 0 : Math.max(0, value); }
    private long value(Long value) { return value == null ? 0 : Math.max(0, value); }
    private double value(Double value) { return value == null ? 0 : clamp(value); }
    private double clamp(double value) { return Math.max(0, Math.min(1, value)); }
    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : round(numerator / (double) denominator);
    }
    private double round(double value) { return Math.round(value * 10_000d) / 10_000d; }

    private final class LayoutAccumulator {
        int artifacts;
        int lowQuality;
        int fallbackEligible;
        int fallbackAttempted;
        int fallbackAccepted;
        int unreadable;
        long textBlocks;
        long structuredBlocks;
        long regionBlocks;
        double qualitySum;
        double acceptedGainSum;
        Map<String, Integer> parserCounts = new LinkedHashMap<>();

        PdfWorkbenchMetricsSnapshot.LayoutSummary toSummary() {
            return new PdfWorkbenchMetricsSnapshot.LayoutSummary(
                    artifacts, artifacts == 0 ? 0 : round(qualitySum / artifacts), lowQuality,
                    fallbackEligible, fallbackAttempted, fallbackAccepted,
                    fallbackAccepted == 0 ? 0 : round(acceptedGainSum / fallbackAccepted),
                    textBlocks, structuredBlocks, regionBlocks, unreadable, parserCounts);
        }
    }

    private final class MutableRunSummary {
        final String workflow;
        int total;
        int completed;
        int failed;
        int cancelled;
        int active;
        int repaired;
        long latency;
        long tokens;
        long evidence;
        int terminal;

        MutableRunSummary(String workflow) { this.workflow = workflow; }

        void add(PaperWorkbenchRunRecord run) {
            total++;
            String status = run.getStatus() == null ? "" : run.getStatus();
            switch (status) {
                case "COMPLETED" -> completed++;
                case "FAILED" -> failed++;
                case "CANCELLED" -> cancelled++;
                default -> active++;
            }
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                terminal++;
                latency += value(run.getLatencyMs());
                tokens += value(run.getTotalTokens());
                evidence += value(run.getEvidenceCount());
            }
            if (value(run.getRepairCount()) > 0) repaired++;
        }

        PdfWorkbenchMetricsSnapshot.RunSummary toRunSummary(int unreadablePayloads) {
            return new PdfWorkbenchMetricsSnapshot.RunSummary(
                    total, completed, failed, cancelled, active, rate(completed, terminal),
                    rate(repaired, total), terminal == 0 ? 0 : latency / terminal,
                    terminal == 0 ? 0 : round(tokens / (double) terminal),
                    terminal == 0 ? 0 : round(evidence / (double) terminal), unreadablePayloads);
        }

        PdfWorkbenchMetricsSnapshot.WorkflowSummary toWorkflowSummary() {
            return new PdfWorkbenchMetricsSnapshot.WorkflowSummary(
                    workflow, total, completed, failed, cancelled, rate(completed, terminal),
                    rate(repaired, total), terminal == 0 ? 0 : latency / terminal,
                    terminal == 0 ? 0 : round(tokens / (double) terminal),
                    terminal == 0 ? 0 : round(evidence / (double) terminal));
        }
    }

    private record RunAccumulators(MutableRunSummary overall,
                                   Map<WorkbenchPlan.Workflow, MutableRunSummary> workflows,
                                   PdfWorkbenchMetricsSnapshot.AnchorSummary anchorSummary,
                                   PdfWorkbenchMetricsSnapshot.EvidenceSummary evidenceSummary,
                                   int unreadablePayloads) {
        List<PdfWorkbenchMetricsSnapshot.WorkflowSummary> workflowSummaries() {
            List<PdfWorkbenchMetricsSnapshot.WorkflowSummary> summaries = new ArrayList<>();
            for (WorkbenchPlan.Workflow workflow : WorkbenchPlan.Workflow.values()) {
                summaries.add(workflows.get(workflow).toWorkflowSummary());
            }
            return List.copyOf(summaries);
        }
    }
}
