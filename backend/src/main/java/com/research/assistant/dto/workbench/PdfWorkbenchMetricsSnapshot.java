package com.research.assistant.dto.workbench;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Privacy-safe, restart-stable aggregate metrics for the PDF workbench. */
public record PdfWorkbenchMetricsSnapshot(Instant generatedAt,
                                          int windowDays,
                                          boolean runWindowTruncated,
                                          LayoutSummary layout,
                                          AnchorSummary anchors,
                                          EvidenceSummary evidence,
                                          RunSummary runs,
                                          List<WorkflowSummary> workflows,
                                          PdfWorkbenchEvalMetrics evaluation) {

    public PdfWorkbenchMetricsSnapshot {
        workflows = workflows == null ? List.of() : List.copyOf(workflows);
    }

    public record LayoutSummary(int latestArtifacts,
                                double averageQuality,
                                int lowQualityArtifacts,
                                int fallbackEligible,
                                int fallbackAttempted,
                                int fallbackAccepted,
                                double averageAcceptedQualityGain,
                                long textBlocks,
                                long structuredBlocks,
                                long regionBlocks,
                                int unreadableArtifacts,
                                Map<String, Integer> selectedParserCounts) {
        public LayoutSummary {
            selectedParserCounts = selectedParserCounts == null
                    ? Map.of() : Map.copyOf(selectedParserCounts);
        }
    }

    public record AnchorSummary(int selectionRuns,
                                int textAnchors,
                                int formulaAnchors,
                                int tableAnchors,
                                int regionAnchors,
                                double averageConfidence,
                                int completedSelectionRuns,
                                int regionFallbackRuns,
                                double regionFallbackRate) {
    }

    public record EvidenceSummary(int completedRunsEvaluated,
                                  int claims,
                                  int groundedClaims,
                                  double claimCoverage,
                                  int noEvidenceFailures,
                                  int evidenceGateRejectedFailures,
                                  int repairedRuns,
                                  int comparisonRuns,
                                  int fullyCoveredComparisonRuns,
                                  double comparisonCoverageRate,
                                  int unreadableResults) {
    }

    public record RunSummary(int total,
                             int completed,
                             int failed,
                             int cancelled,
                             int active,
                             double completionRate,
                             double repairRate,
                             long averageLatencyMs,
                             double averageTokens,
                             double averageEvidence,
                             int unreadablePayloads) {
    }

    public record WorkflowSummary(String workflow,
                                  int total,
                                  int completed,
                                  int failed,
                                  int cancelled,
                                  double completionRate,
                                  double repairRate,
                                  long averageLatencyMs,
                                  double averageTokens,
                                  double averageEvidence) {
    }
}
