package com.research.assistant.service.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.workbench.PdfWorkbenchEvalMetrics;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.LayoutEvidence;
import com.research.assistant.service.pdf.layout.LayoutQualityReport;
import com.research.assistant.service.pdf.layout.LocalEvidenceResult;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidencePolicy;
import com.research.assistant.service.pdf.layout.PaperLayoutEvidenceService;
import com.research.assistant.service.pdf.layout.PaperLayoutHints;
import com.research.assistant.service.pdf.layout.PaperLayoutParser;
import com.research.assistant.service.pdf.layout.PaperLayoutQualityAssessor;
import com.research.assistant.service.pdf.layout.PaperLayoutSemanticEnricher;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import com.research.assistant.service.pdf.layout.SelectionAnchorKind;
import com.research.assistant.service.pdf.layout.SelectionAnchorResolver;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Repeatable, model-free regression evaluator for layout, selection anchoring
 * and evidence retrieval. Optional local PDFs are addressed only by environment
 * variable names in the committed manifest, so paths and copyrighted content
 * never enter the API response.
 */
@Service
public class PdfWorkbenchEvalService {

    private static final String GOLDEN_RESOURCE = "eval/pdf-workbench-golden.json";
    private static final String REAL_RESOURCE = "eval/pdf-workbench-real-manifest.json";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final ObjectMapper objectMapper;
    private final PaperLayoutSemanticEnricher semanticEnricher;
    private final PaperLayoutQualityAssessor qualityAssessor;
    private final PaperLayoutEvidencePolicy evidencePolicy;
    private final SelectionAnchorResolver anchorResolver;
    private final PaperLayoutEvidenceService evidenceService;
    private final PaperLayoutParser layoutParser;
    private final Environment environment;
    private volatile CachedEvaluation cached;

    public PdfWorkbenchEvalService(ObjectMapper objectMapper,
                                   PaperLayoutSemanticEnricher semanticEnricher,
                                   PaperLayoutQualityAssessor qualityAssessor,
                                   PaperLayoutEvidencePolicy evidencePolicy,
                                   SelectionAnchorResolver anchorResolver,
                                   PaperLayoutEvidenceService evidenceService,
                                   PaperLayoutParser layoutParser,
                                   Environment environment) {
        this.objectMapper = objectMapper;
        this.semanticEnricher = semanticEnricher;
        this.qualityAssessor = qualityAssessor;
        this.evidencePolicy = evidencePolicy;
        this.anchorResolver = anchorResolver;
        this.evidenceService = evidenceService;
        this.layoutParser = layoutParser;
        this.environment = environment;
    }

    public PdfWorkbenchEvalMetrics evaluate() {
        String configurationKey = realConfigurationKey();
        CachedEvaluation current = cached;
        Instant now = Instant.now();
        if (current != null && current.configurationKey().equals(configurationKey)
                && Duration.between(current.createdAt(), now).compareTo(CACHE_TTL) < 0) {
            return current.metrics();
        }
        synchronized (this) {
            current = cached;
            if (current != null && current.configurationKey().equals(configurationKey)
                    && Duration.between(current.createdAt(), now).compareTo(CACHE_TTL) < 0) {
                return current.metrics();
            }
            PdfWorkbenchEvalMetrics metrics = evaluateUncached();
            cached = new CachedEvaluation(configurationKey, now, metrics);
            return metrics;
        }
    }

    private PdfWorkbenchEvalMetrics evaluateUncached() {
        List<PdfWorkbenchEvalMetrics.CaseResult> results = new ArrayList<>();
        int schemaVersion = 1;
        try {
            JsonNode root = readResource(GOLDEN_RESOURCE);
            schemaVersion = Math.max(schemaVersion, root.path("schemaVersion").asInt(1));
            for (JsonNode fixture : root.path("layoutCases")) {
                results.add(evaluateLayoutCase(fixture));
            }
            for (JsonNode fixture : root.path("selectionCases")) {
                results.add(evaluateSelectionCase(fixture, "deterministic-selection"));
            }
        } catch (Exception error) {
            results.add(failed("golden-resource", "deterministic-resource", error));
        }

        int deterministicCases = results.size();
        int deterministicPassed = passed(results);
        int realCases = 0;
        try {
            JsonNode root = readResource(REAL_RESOURCE);
            schemaVersion = Math.max(schemaVersion, root.path("schemaVersion").asInt(1));
            int index = 0;
            for (JsonNode fixture : root.path("cases")) {
                results.add(evaluateRealCase(fixture, index++));
                realCases++;
            }
        } catch (Exception error) {
            results.add(failed("real-manifest", "real-pdf-resource", error));
            realCases = 1;
        }

        List<PdfWorkbenchEvalMetrics.CaseResult> realResults = results.stream()
                .skip(deterministicCases)
                .toList();
        int realSkipped = (int) realResults.stream()
                .filter(result -> result.status() == PdfWorkbenchEvalMetrics.Status.SKIPPED)
                .count();
        int realExecuted = realResults.size() - realSkipped;
        int realPassed = passed(realResults);
        int executed = deterministicCases + realExecuted;
        double passRate = executed == 0 ? 0
                : (deterministicPassed + realPassed) / (double) executed;
        return new PdfWorkbenchEvalMetrics(
                schemaVersion, Instant.now(), deterministicCases, deterministicPassed,
                realCases, realExecuted, realPassed, realSkipped, passRate, results);
    }

    private PdfWorkbenchEvalMetrics.CaseResult evaluateLayoutCase(JsonNode fixture) {
        long started = System.nanoTime();
        String id = fixture.path("id").asText("layout-case");
        List<String> issues = new ArrayList<>();
        try {
            PaperLayoutArtifact raw = objectMapper.treeToValue(
                    fixture.path("artifact"), PaperLayoutArtifact.class);
            JsonNode hintsNode = fixture.path("hints");
            PaperLayoutArtifact artifact = semanticEnricher.enrich(raw, new PaperLayoutHints(
                    hintsNode.path("title").asText(""),
                    hintsNode.path("authors").asText(""),
                    hintsNode.path("abstractText").asText("")));
            LayoutQualityReport quality = qualityAssessor.assess(artifact);
            JsonNode expected = fixture.path("expected");

            Map<DocumentBlockRole, Long> roleCounts = artifact.blocks().stream()
                    .collect(Collectors.groupingBy(DocumentBlock::role,
                            () -> new EnumMap<>(DocumentBlockRole.class), Collectors.counting()));
            expected.path("roleMinimums").fields().forEachRemaining(entry -> {
                DocumentBlockRole role = enumValue(DocumentBlockRole.class, entry.getKey(), null);
                if (role == null || roleCounts.getOrDefault(role, 0L) < entry.getValue().asLong()) {
                    issues.add("role_minimum:" + entry.getKey());
                }
            });
            double minimumGeometry = expected.path("minimumValidGeometryRatio").asDouble(0);
            if (quality.validGeometryRatio() + 1e-9 < minimumGeometry) {
                issues.add("valid_geometry_ratio");
            }
            if (!readingOrderIsContiguous(artifact.blocks())) issues.add("reading_order");
            Set<DocumentBlockRole> forbidden = enumSet(
                    expected.path("forbiddenEvidenceRoles"), DocumentBlockRole.class);
            if (evidencePolicy.selectAllowed(artifact).stream()
                    .map(DocumentBlock::role).anyMatch(forbidden::contains)) {
                issues.add("forbidden_evidence_role");
            }
            return result(id, "deterministic-layout", issues, started);
        } catch (Exception error) {
            return failed(id, "deterministic-layout", error, started);
        }
    }

    private PdfWorkbenchEvalMetrics.CaseResult evaluateSelectionCase(JsonNode fixture,
                                                                      String suite) {
        long started = System.nanoTime();
        String id = fixture.path("id").asText("selection-case");
        List<String> issues = new ArrayList<>();
        try {
            PaperLayoutArtifact artifact = objectMapper.treeToValue(
                    fixture.path("artifact"), PaperLayoutArtifact.class);
            JsonNode selection = fixture.path("selection");
            SelectionAnchorKind preferred = enumValue(
                    SelectionAnchorKind.class, selection.path("preferredKind").asText(), null);
            List<NormalizedBoundingBox> boxes = new ArrayList<>();
            for (JsonNode box : selection.path("boxes")) {
                boxes.add(objectMapper.treeToValue(box, NormalizedBoundingBox.class));
            }
            SelectionAnchor anchor = anchorResolver.resolve(
                    artifact, selection.path("page").asInt(), boxes,
                    selection.path("anchorText").asText(""), preferred);
            LocalEvidenceResult local = evidenceService.retrieve(
                    artifact, anchor, fixture.path("query").asText(""),
                    fixture.path("maxResults").asInt(6));
            checkSelectionExpectations(fixture.path("expected"), local, issues);
            return result(id, suite, issues, started);
        } catch (Exception error) {
            return failed(id, suite, error, started);
        }
    }

    private PdfWorkbenchEvalMetrics.CaseResult evaluateRealCase(JsonNode fixture, int index) {
        long started = System.nanoTime();
        String id = fixture.path("id").asText("real-pdf-" + index);
        String property = fixture.path("pathEnvironment").asText("");
        String configuredPath = property.isBlank() ? null : environment.getProperty(property);
        if (configuredPath == null || configuredPath.isBlank()) {
            return new PdfWorkbenchEvalMetrics.CaseResult(
                    id, "real-pdf", PdfWorkbenchEvalMetrics.Status.SKIPPED,
                    List.of("sample_not_configured:" + property), elapsed(started));
        }
        File file = new File(configuredPath);
        if (!file.isFile()) {
            return new PdfWorkbenchEvalMetrics.CaseResult(
                    id, "real-pdf", PdfWorkbenchEvalMetrics.Status.FAILED,
                    List.of("sample_not_found"), elapsed(started));
        }
        List<String> issues = new ArrayList<>();
        try {
            long paperId = 9_900_000L + index;
            PaperLayoutArtifact raw = layoutParser.parse(paperId, file);
            PaperLayoutArtifact artifact = semanticEnricher.enrich(raw, PaperLayoutHints.empty());
            LayoutQualityReport quality = qualityAssessor.assess(artifact);
            JsonNode expected = fixture.path("expected");
            if (artifact.pageCount() < expected.path("minimumPageCount").asInt(1)) {
                issues.add("page_count");
            }
            if (quality.score() + 1e-9 < expected.path("minimumQuality").asDouble(0)) {
                issues.add("quality_score");
            }
            Set<DocumentBlockRole> actualRoles = artifact.blocks().stream()
                    .map(DocumentBlock::role).collect(Collectors.toSet());
            for (JsonNode roleNode : expected.path("requiredRoles")) {
                DocumentBlockRole role = enumValue(DocumentBlockRole.class, roleNode.asText(), null);
                if (role == null || !actualRoles.contains(role)) issues.add("required_role:" + roleNode.asText());
            }
            Set<DocumentBlockRole> forbidden = enumSet(
                    expected.path("forbiddenEvidenceRoles"), DocumentBlockRole.class);
            if (evidencePolicy.selectAllowed(artifact).stream()
                    .map(DocumentBlock::role).anyMatch(forbidden::contains)) {
                issues.add("forbidden_evidence_role");
            }
            if (fixture.hasNonNull("selection")) {
                JsonNode selectionFixture = objectMapper.createObjectNode()
                        .set("artifact", objectMapper.valueToTree(artifact));
                ((com.fasterxml.jackson.databind.node.ObjectNode) selectionFixture)
                        .set("selection", fixture.path("selection"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) selectionFixture)
                        .set("expected", fixture.path("selectionExpected"));
                PdfWorkbenchEvalMetrics.CaseResult selectionResult = evaluateSelectionCase(
                        selectionFixture, "real-pdf-selection");
                issues.addAll(selectionResult.issues());
            }
            return result(id, "real-pdf", issues, started);
        } catch (Exception error) {
            return failed(id, "real-pdf", error, started);
        }
    }

    private void checkSelectionExpectations(JsonNode expected,
                                            LocalEvidenceResult local,
                                            List<String> issues) {
        SelectionAnchor anchor = local.anchor();
        SelectionAnchorKind expectedKind = enumValue(
                SelectionAnchorKind.class, expected.path("anchorKind").asText(), null);
        if (expectedKind != null && anchor.kind() != expectedKind) issues.add("anchor_kind");
        if (anchor.confidence() + 1e-9 < expected.path("minimumConfidence").asDouble(0)) {
            issues.add("anchor_confidence");
        }
        for (JsonNode blockId : expected.path("anchorBlockIds")) {
            if (!anchor.blockIds().contains(blockId.asText())) issues.add("anchor_block:" + blockId.asText());
        }
        if (expected.has("regionFallback")
                && local.regionFallback() != expected.path("regionFallback").asBoolean()) {
            issues.add("region_fallback");
        }
        if (expected.path("evidenceEmpty").asBoolean(false) && !local.evidence().isEmpty()) {
            issues.add("evidence_not_empty");
        }
        Map<String, LayoutEvidence> byBlock = local.evidence().stream()
                .collect(Collectors.toMap(LayoutEvidence::blockId, item -> item, (left, right) -> left,
                        LinkedHashMap::new));
        for (JsonNode blockId : expected.path("evidenceBlockIds")) {
            if (!byBlock.containsKey(blockId.asText())) issues.add("evidence_block:" + blockId.asText());
        }
        Set<DocumentBlockRole> forbiddenRoles = enumSet(
                expected.path("forbiddenEvidenceRoles"), DocumentBlockRole.class);
        if (local.evidence().stream().map(LayoutEvidence::role).anyMatch(forbiddenRoles::contains)) {
            issues.add("forbidden_evidence_role");
        }
        expected.path("evidenceModes").fields().forEachRemaining(entry -> {
            LayoutEvidence item = byBlock.get(entry.getKey());
            DocumentBlockContentMode expectedMode = enumValue(
                    DocumentBlockContentMode.class, entry.getValue().asText(), null);
            if (item == null || item.contentMode() != expectedMode) {
                issues.add("evidence_mode:" + entry.getKey());
            }
        });
        expected.path("structuredContentContains").fields().forEachRemaining(entry -> {
            LayoutEvidence item = byBlock.get(entry.getKey());
            if (item == null || !item.structuredContent().contains(entry.getValue().asText())) {
                issues.add("structured_content:" + entry.getKey());
            }
        });
        for (JsonNode forbiddenText : expected.path("forbiddenEvidenceText")) {
            if (local.evidence().stream().anyMatch(item -> item.text().contains(forbiddenText.asText()))) {
                issues.add("unsafe_evidence_text");
            }
        }
    }

    private JsonNode readResource(String path) throws Exception {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(input);
        }
    }

    private String realConfigurationKey() {
        try {
            JsonNode root = readResource(REAL_RESOURCE);
            StringBuilder key = new StringBuilder(root.path("schemaVersion").asText("1"));
            for (JsonNode fixture : root.path("cases")) {
                String property = fixture.path("pathEnvironment").asText("");
                String path = property.isBlank() ? "" : environment.getProperty(property, "");
                File file = path.isBlank() ? null : new File(path);
                key.append('|').append(property).append('=').append(path.hashCode());
                if (file != null && file.isFile()) {
                    key.append(':').append(file.length()).append(':').append(file.lastModified());
                }
            }
            return key.toString();
        } catch (Exception error) {
            return "manifest-unavailable";
        }
    }

    private boolean readingOrderIsContiguous(List<DocumentBlock> blocks) {
        List<Integer> orders = blocks.stream().map(DocumentBlock::readingOrder).sorted().toList();
        for (int index = 0; index < orders.size(); index++) {
            if (orders.get(index) != index) return false;
        }
        return true;
    }

    private <E extends Enum<E>> Set<E> enumSet(JsonNode values, Class<E> type) {
        if (values == null || !values.isArray()) return Set.of();
        return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                .map(JsonNode::asText)
                .map(value -> enumValue(type, value, null))
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private PdfWorkbenchEvalMetrics.CaseResult result(String id,
                                                       String suite,
                                                       List<String> issues,
                                                       long started) {
        return new PdfWorkbenchEvalMetrics.CaseResult(
                id, suite,
                issues.isEmpty() ? PdfWorkbenchEvalMetrics.Status.PASSED
                        : PdfWorkbenchEvalMetrics.Status.FAILED,
                issues, elapsed(started));
    }

    private PdfWorkbenchEvalMetrics.CaseResult failed(String id, String suite, Exception error) {
        return failed(id, suite, error, System.nanoTime());
    }

    private PdfWorkbenchEvalMetrics.CaseResult failed(String id,
                                                       String suite,
                                                       Exception error,
                                                       long started) {
        return new PdfWorkbenchEvalMetrics.CaseResult(
                id, suite, PdfWorkbenchEvalMetrics.Status.FAILED,
                List.of("exception:" + error.getClass().getSimpleName()), elapsed(started));
    }

    private int passed(List<PdfWorkbenchEvalMetrics.CaseResult> results) {
        return (int) results.stream()
                .filter(result -> result.status() == PdfWorkbenchEvalMetrics.Status.PASSED)
                .count();
    }

    private long elapsed(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private record CachedEvaluation(String configurationKey,
                                    Instant createdAt,
                                    PdfWorkbenchEvalMetrics metrics) {
    }
}
