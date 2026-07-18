package com.research.assistant.service.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Global paper portrait synthesized only from grounded chunk summaries. */
public record PaperGlobalProfile(String schemaVersion,
                                 Long paperId,
                                 String title,
                                 String domain,
                                 String researchProblem,
                                 List<PaperMemoryClaim> coreContributions,
                                 String methodType,
                                 String methodSummary,
                                 List<String> datasets,
                                 List<String> models,
                                 List<String> metrics,
                                 List<PaperMemoryClaim> keyFindings,
                                 List<PaperMemoryClaim> limitations,
                                 Map<String, String> experimentSetup,
                                 List<BenchmarkResult> benchmarkResults,
                                 List<SectionDigest> sectionDigests,
                                 List<String> openQuestions,
                                 Coverage coverage,
                                 List<String> qualityIssues,
                                 Instant generatedAt) {

    public static final String SCHEMA_VERSION = "paper-profile-v1";

    public PaperGlobalProfile {
        schemaVersion = safe(schemaVersion, SCHEMA_VERSION);
        title = safe(title, "");
        domain = safe(domain, "");
        researchProblem = safe(researchProblem, "");
        coreContributions = copy(coreContributions);
        methodType = safe(methodType, "OTHER");
        methodSummary = safe(methodSummary, "");
        datasets = copy(datasets);
        models = copy(models);
        metrics = copy(metrics);
        keyFindings = copy(keyFindings);
        limitations = copy(limitations);
        experimentSetup = experimentSetup == null ? Map.of() : Map.copyOf(experimentSetup);
        benchmarkResults = copy(benchmarkResults);
        sectionDigests = copy(sectionDigests);
        openQuestions = copy(openQuestions);
        coverage = coverage == null ? new Coverage(0, 0, 0, false) : coverage;
        qualityIssues = copy(qualityIssues);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }

    public record BenchmarkResult(String metric,
                                  String value,
                                  String baseline,
                                  String dataset,
                                  List<String> evidenceBlockIds) {
        public BenchmarkResult {
            metric = safe(metric, "");
            value = safe(value, "");
            baseline = safe(baseline, "");
            dataset = safe(dataset, "");
            evidenceBlockIds = copy(evidenceBlockIds);
        }
    }

    public record SectionDigest(String sectionId,
                                List<String> headingPath,
                                String summary,
                                List<String> sourceChunkIds) {
        public SectionDigest {
            sectionId = safe(sectionId, "");
            headingPath = copy(headingPath);
            summary = safe(summary, "");
            sourceChunkIds = copy(sourceChunkIds);
        }
    }

    public record Coverage(int totalChunks,
                           int summarizedChunks,
                           int failedChunks,
                           boolean complete) {
        public Coverage {
            totalChunks = Math.max(0, totalChunks);
            summarizedChunks = Math.max(0, summarizedChunks);
            failedChunks = Math.max(0, failedChunks);
            complete = complete && totalChunks > 0 && summarizedChunks == totalChunks && failedChunks == 0;
        }
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
