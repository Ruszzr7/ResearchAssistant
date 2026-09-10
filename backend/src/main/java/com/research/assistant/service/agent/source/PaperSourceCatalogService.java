package com.research.assistant.service.agent.source;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.EquationEntity;
import com.research.assistant.service.pdf.layout.EvidenceLocator;
import com.research.assistant.service.pdf.layout.LayoutTextSimilarity;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.research.assistant.service.pdf.layout.PaperSourceIndex;
import com.research.assistant.service.pdf.layout.PaperSourceIndexService;
import com.research.assistant.service.pdf.layout.PaperSourceContinuation;
import com.research.assistant.service.pdf.layout.PaperSourceUnit;
import com.research.assistant.service.pdf.layout.PaperSemanticSpan;
import com.research.assistant.service.pdf.layout.PaperSemanticSpanBuilder;
import com.research.assistant.service.pdf.layout.SourceAnchor;
import com.research.assistant.service.memory.PaperLayoutRecovery;
import com.research.assistant.service.memory.PaperLayoutRecoveryStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PaperSourceCatalogService {

    private static final Pattern FORMULA_NUMBER = Pattern.compile("(?:eq(?:uation)?\\.?\\s*)?\\((\\d{1,4}[a-z]?)\\)",
            Pattern.CASE_INSENSITIVE);

    private final PaperLayoutArtifactService artifactService;
    private final PaperSourceIndexService sourceIndexService;
    private final PaperSemanticSpanBuilder spanBuilder;
    private final PaperLayoutRecoveryStore recoveryStore;
    /**
     * Building the source index performs structural equation/statement analysis over the
     * complete layout artifact.  It is deterministic for one PDF/parser version, so rebuilding
     * it for every chat turn only adds latency and can consume the whole model deadline.
     */
    private final Cache<CatalogKey, PaperSourceCatalog> catalogCache = Caffeine.newBuilder()
            .maximumSize(8)
            .build();

    public PaperSourceCatalogService(PaperLayoutArtifactService artifactService,
                                     PaperSourceIndexService sourceIndexService) {
        this(artifactService, sourceIndexService, new PaperSemanticSpanBuilder(), null);
    }

    public PaperSourceCatalogService(PaperLayoutArtifactService artifactService,
                                     PaperSourceIndexService sourceIndexService,
                                     PaperSemanticSpanBuilder spanBuilder) {
        this(artifactService, sourceIndexService, spanBuilder, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaperSourceCatalogService(PaperLayoutArtifactService artifactService,
                                     PaperSourceIndexService sourceIndexService,
                                     PaperSemanticSpanBuilder spanBuilder,
                                     PaperLayoutRecoveryStore recoveryStore) {
        this.artifactService = artifactService;
        this.sourceIndexService = sourceIndexService;
        this.spanBuilder = spanBuilder;
        this.recoveryStore = recoveryStore;
    }

    public PaperSourceCatalog latest(long paperId) {
        PaperLayoutArtifact artifact = artifactService.latestArtifact(paperId);
        if (artifact == null) throw new IllegalStateException("本地论文来源尚未就绪（LOCAL_SOURCE_NOT_READY）");
        return cached(artifact);
    }

    public PaperSourceCatalog ensure(long paperId, boolean forceRefresh) {
        PaperLayoutArtifact artifact = artifactService.ensureArtifact(paperId, forceRefresh);
        return cached(artifact);
    }

    public PaperSourceCatalog build(PaperLayoutArtifact artifact) {
        return cached(artifact);
    }

    private PaperSourceCatalog cached(PaperLayoutArtifact artifact) {
        if (artifact == null || artifact.paperId() == null || artifact.paperId() <= 0) {
            throw new IllegalArgumentException("需要提供带版本的论文版面解析产物");
        }
        List<PaperLayoutRecovery> recoveries = recoveryStore == null ? List.of() : recoveryStore.read(artifact);
        CatalogKey key = new CatalogKey(artifact.paperId(), artifact.documentHash(), artifact.parserVersion(),
                recoveries);
        return catalogCache.get(key, ignored -> buildUncached(artifact, recoveries));
    }

    private PaperSourceCatalog buildUncached(PaperLayoutArtifact artifact,
                                             List<PaperLayoutRecovery> recoveries) {
        PaperSourceIndex index = sourceIndexService.build(artifact);
        Set<String> recoveredBlockIds = recoveries.stream()
                .filter(PaperLayoutRecovery::corrected)
                .flatMap(recovery -> recovery.orderedBlockIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, DocumentBlock> blockById = new LinkedHashMap<>();
        artifact.blocks().stream().sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .forEach(block -> blockById.put(block.id(), block));
        Map<String, SourceAnchor> textAnchorByBlock = new LinkedHashMap<>();
        index.textAnchors().forEach(anchor -> textAnchorByBlock.put(anchor.blockId(), anchor));

        Map<String, SourceObject> objects = new LinkedHashMap<>();
        Map<String, List<SourceLocator>> locators = new LinkedHashMap<>();
        for (PaperSemanticSpan span : spanBuilder.build(artifact)) {
            List<DocumentBlock> sourceBlocks = span.blocks().stream()
                    .filter(block -> !recoveredBlockIds.contains(block.id()))
                    .toList();
            if (sourceBlocks.isEmpty()) continue;
            String sourceId = stableId(artifact, "span:" + span.id());
            String raw = sourceBlocks.size() == span.blocks().size()
                    ? span.text()
                    : sourceBlocks.stream().map(DocumentBlock::text)
                            .filter(text -> !text.isBlank())
                            .collect(java.util.stream.Collectors.joining("\n"));
            if (raw.isBlank()) continue;
            SourceContentType sourceType = contentType(span.role());
            if (sourceType == SourceContentType.TEXT && !SourceEvidenceQuality.usableText(raw)) continue;
            Map<String, String> spanProvenance = new LinkedHashMap<>();
            spanProvenance.put("spanId", span.id());
            spanProvenance.put("blockIds", sourceBlocks.stream().map(DocumentBlock::id)
                    .collect(java.util.stream.Collectors.joining(",")));
            spanProvenance.put("role", span.role().name());
            spanProvenance.put("readingOrder", Integer.toString(sourceBlocks.get(0).readingOrder()));
            addTextMetadata(spanProvenance, sourceType, sourceBlocks, raw);
            SourceObject object = new SourceObject(sourceId, artifact.paperId(), artifact.documentHash(),
                    artifact.parserVersion(), PaperSourceIndex.SCHEMA_VERSION, sourceType, raw,
                    SourceObject.normalize(raw), span.sectionPath(), "", spanProvenance);
            if (!SourceEvidenceQuality.usableForCitation(object)) continue;
            List<SourceLocator> spanLocators = sourceBlocks.stream().map(block -> {
                SourceAnchor anchor = textAnchorByBlock.get(block.id());
                if (anchor != null && span.role() == block.role()) {
                    return locator(sourceId, anchor, block.id());
                }
                return new SourceLocator(locatorId(sourceId, block.page(), block.id()),
                        sourceId, block.page(), "PDF_NORMALIZED", List.of(block.bbox()),
                        block.text(), precision(span.role(), block.contentMode()));
            }).toList();
            objects.put(sourceId, object);
            locators.put(sourceId, spanLocators);
        }

        for (EquationEntity equation : index.equations()) {
            SourceAnchor definition = equation.definition();
            if (recoveredBlockIds.contains(definition.blockId())) continue;
            DocumentBlock block = blockById.get(definition.blockId());
            List<String> sectionPath = new ArrayList<>(block == null ? List.of() : block.sectionPath());
            sectionPath.add("公式 (" + equation.number() + ")");
            if (!equation.statementLabel().isBlank()) sectionPath.add(equation.statementLabel());
            String latex = block == null ? "" : block.latex();
            String raw = latex == null || latex.isBlank() ? definition.targetText() : latex.strip();
            if (raw.isBlank() && block != null) raw = rawContent(block);
            Map<String, String> formulaProvenance = new LinkedHashMap<>();
            formulaProvenance.put("blockId", definition.blockId());
            formulaProvenance.put("relation", equation.relation().name());
            formulaProvenance.put("textFormat", latex == null || latex.isBlank() ? "PLAIN_TEXT" : "LATEX");
            formulaProvenance.put("textReliable", Boolean.toString(latex != null && !latex.isBlank()));
            SourceObject object = new SourceObject(equation.entityId(), artifact.paperId(),
                    artifact.documentHash(), artifact.parserVersion(), PaperSourceIndex.SCHEMA_VERSION,
                    SourceContentType.FORMULA, raw, SourceObject.normalize(raw), sectionPath,
                    equation.number(), formulaProvenance);
            if (!SourceEvidenceQuality.usableForCitation(object)) continue;
            objects.put(equation.entityId(), object);
            locators.put(equation.entityId(), List.of(
                    formulaLocator(equation.entityId(), definition, block)));
        }
        for (PaperSourceUnit unit : index.sourceUnits()) {
            if (unit.blocks().stream().map(DocumentBlock::id).anyMatch(recoveredBlockIds::contains)) continue;
            String sourceId = stableId(artifact, "unit:" + unit.id());
            List<String> sectionPath = new ArrayList<>(unit.sectionPath());
            sectionPath.add(unit.label());
            SourceContentType sourceType = contentType(unit.kind());
            Map<String, String> unitProvenance = new LinkedHashMap<>();
            unitProvenance.put("sourceUnitId", unit.id());
            unitProvenance.put("sourceUnitKind", unit.kind().name());
            unitProvenance.put("blockIds", unit.blocks().stream().map(DocumentBlock::id)
                    .collect(java.util.stream.Collectors.joining(",")));
            unitProvenance.put("readingOrder", Integer.toString(unit.blocks().get(0).readingOrder()));
            addTextMetadata(unitProvenance, sourceType, unit.blocks(), unit.text());
            SourceObject object = new SourceObject(sourceId, artifact.paperId(), artifact.documentHash(),
                    artifact.parserVersion(), PaperSourceIndex.SCHEMA_VERSION, sourceType,
                    unit.text(), SourceObject.normalize(unit.text()), sectionPath, "", unitProvenance);
            if (!SourceEvidenceQuality.usableForCitation(object)) continue;
            SourceLocator sourceLocator = new SourceLocator(
                    locatorId(sourceId, unit.page(), unit.id()), sourceId, unit.page(),
                    "PDF_NORMALIZED", unit.boxes(), unit.text(), precision(unit));
            objects.put(sourceId, object);
            locators.put(sourceId, List.of(sourceLocator));
        }
        for (PaperSourceContinuation continuation : index.continuations()) {
            if (continuation.parts().stream().flatMap(part -> part.blocks().stream())
                    .map(DocumentBlock::id).anyMatch(recoveredBlockIds::contains)) continue;
            String sourceId = stableId(artifact, continuation.id());
            PaperSourceUnit first = continuation.parts().get(0);
            List<String> sectionPath = new ArrayList<>(first.sectionPath());
            sectionPath.add(continuation.label());
            SourceContentType sourceType = contentType(continuation.kind());
            Map<String, String> continuationProvenance = new LinkedHashMap<>();
            continuationProvenance.put("continuationId", continuation.id());
            continuationProvenance.put("sourceUnitKind", continuation.kind().name());
            continuationProvenance.put("pages", continuation.parts().stream()
                    .map(part -> Integer.toString(part.page()))
                    .collect(java.util.stream.Collectors.joining(",")));
            continuationProvenance.put("readingOrder", Integer.toString(first.blocks().get(0).readingOrder()));
            addTextMetadata(continuationProvenance, sourceType,
                    continuation.parts().stream().flatMap(part -> part.blocks().stream()).toList(),
                    continuation.text());
            SourceObject object = new SourceObject(sourceId, artifact.paperId(), artifact.documentHash(),
                    artifact.parserVersion(), PaperSourceIndex.SCHEMA_VERSION, sourceType, continuation.text(),
                    SourceObject.normalize(continuation.text()), sectionPath, "", continuationProvenance);
            if (!SourceEvidenceQuality.usableForCitation(object)) continue;
            List<SourceLocator> continuationLocators = continuation.parts().stream()
                    .map(part -> new SourceLocator(locatorId(sourceId, part.page(), part.id()),
                            sourceId, part.page(), "PDF_NORMALIZED", part.boxes(),
                            part.text(), precision(part)))
                    .toList();
            objects.put(sourceId, object);
            locators.put(sourceId, continuationLocators);
        }
        for (PaperLayoutRecovery recovery : recoveries) {
            String sourceId = stableId(artifact, "recovery:" + recovery.regionId());
            DocumentBlock first = recovery.orderedBlockIds().stream().map(blockById::get)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            List<String> sectionPath = new ArrayList<>(first == null ? List.of() : first.sectionPath());
            boolean corrected = recovery.corrected();
            sectionPath.add(corrected ? "已恢复版面区域" : "视觉来源区域");
            SourceContentType type = recoveryContentType(recovery, blockById);
            String sourceText = corrected ? recovery.correctedText()
                    : visualFallbackText(recovery, artifact.blocks(), blockById, type);
            if (type == SourceContentType.TEXT && !SourceEvidenceQuality.usableText(sourceText)) continue;
            String formulaNumber = type == SourceContentType.FORMULA
                    ? String.join(",", formulaNumbers(sourceText)) : "";
            Map<String, String> provenance = new LinkedHashMap<>();
            provenance.put("recoveryRegionId", recovery.regionId());
            provenance.put("issueType", recovery.issueType());
            provenance.put("source", corrected ? recovery.provenance() : "VISUAL_FALLBACK");
            provenance.put("blockIds", String.join(",", recovery.orderedBlockIds()));
            provenance.put("readingOrder", Integer.toString(
                    first == null ? Integer.MAX_VALUE : first.readingOrder()));
            provenance.put("recoveryMode", corrected ? "TEXT_RECOVERY" : "VISUAL_FALLBACK");
            provenance.put("textFormat", corrected ? "PLAIN_TEXT" : "VISUAL_FALLBACK");
            provenance.put("textReliable", Boolean.toString(corrected));
            SourceObject object = new SourceObject(sourceId, artifact.paperId(), artifact.documentHash(),
                    artifact.parserVersion(), PaperSourceIndex.SCHEMA_VERSION, type,
                    sourceText, SourceObject.normalize(sourceText), sectionPath, formulaNumber, provenance);
            if (!SourceEvidenceQuality.usableForCitation(object)) continue;
            List<SourceLocator> recoveryLocators = recovery.pageAreas().stream()
                    .map(area -> new SourceLocator(locatorId(sourceId, area.page(), recovery.regionId()),
                            sourceId, area.page(), "PDF_NORMALIZED", area.boxes(),
                            sourceText, type == SourceContentType.FORMULA
                            ? EvidenceLocator.Precision.FORMULA_REGION
                            : EvidenceLocator.Precision.VISUAL_REGION))
                    .toList();
            if (!recoveryLocators.isEmpty()) {
                objects.put(sourceId, object);
                locators.put(sourceId, recoveryLocators);
            }
        }
        deduplicatePhysicalSources(objects, locators);
        return new PaperSourceCatalog(artifact.paperId(), artifact.documentHash(), artifact.parserVersion(),
                artifact.pageCount(), objects, locators);
    }

    /**
     * A single printed formula can be published once by the equation index and again by an
     * unresolved visual recovery.  Keep one canonical source before retrieval so the Agent
     * cannot cite the same physical evidence twice.  Exact overlapping text duplicates use
     * the same guard; repeated text on another page remains independent.
     */
    private void deduplicatePhysicalSources(Map<String, SourceObject> objects,
                                            Map<String, List<SourceLocator>> locators) {
        List<String> ids = new ArrayList<>(objects.keySet());
        for (int leftIndex = 0; leftIndex < ids.size(); leftIndex++) {
            String leftId = ids.get(leftIndex);
            SourceObject left = objects.get(leftId);
            if (left == null) continue;
            for (int rightIndex = leftIndex + 1; rightIndex < ids.size(); rightIndex++) {
                String rightId = ids.get(rightIndex);
                SourceObject right = objects.get(rightId);
                if (right == null) continue;
                List<SourceLocator> leftLocators = locators.get(leftId);
                List<SourceLocator> rightLocators = locators.get(rightId);
                boolean formulaDuplicate = SourceEvidenceIdentity.formulaEquivalent(
                        left, leftLocators, right, rightLocators);
                boolean textDuplicate = SourceEvidenceIdentity.textEquivalent(
                        left, leftLocators, right, rightLocators)
                        || SourceEvidenceIdentity.equivalent(left, leftLocators, right, rightLocators);
                if (!formulaDuplicate && !textDuplicate) continue;
                String keepId = formulaDuplicate
                        ? preferredFormula(left, right).sourceObjectId()
                        : preferredText(left, right).sourceObjectId();
                String dropId = keepId.equals(leftId) ? rightId : leftId;
                objects.remove(dropId);
                locators.remove(dropId);
                if (dropId.equals(leftId)) {
                    left = null;
                    break;
                }
                right = null;
            }
        }
    }

    private SourceObject preferredFormula(SourceObject first, SourceObject second) {
        int firstScore = formulaQuality(first);
        int secondScore = formulaQuality(second);
        if (firstScore != secondScore) return firstScore > secondScore ? first : second;
        int firstLength = first.rawContent().strip().length();
        int secondLength = second.rawContent().strip().length();
        return firstLength >= secondLength ? first : second;
    }

    private SourceObject preferredText(SourceObject first, SourceObject second) {
        int firstLength = first.rawContent().strip().length();
        int secondLength = second.rawContent().strip().length();
        return firstLength >= secondLength ? first : second;
    }

    private int formulaQuality(SourceObject source) {
        boolean reliable = Boolean.parseBoolean(source.provenance().getOrDefault("textReliable", "false"));
        String format = source.provenance().getOrDefault("textFormat", "");
        String recovery = source.provenance().getOrDefault("recoveryMode", "");
        int score = reliable ? 1_000 : 0;
        score += "LATEX".equalsIgnoreCase(format) ? 300
                : "PLAIN_TEXT".equalsIgnoreCase(format) ? 100 : 0;
        if ("VISUAL_FALLBACK".equalsIgnoreCase(recovery)) score -= 100;
        return score;
    }

    private SourceContentType recoveredContentType(String value) {
        try {
            return SourceContentType.valueOf(value == null ? "TEXT" : value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SourceContentType.TEXT;
        }
    }

    private SourceContentType recoveryContentType(PaperLayoutRecovery recovery,
                                                  Map<String, DocumentBlock> blockById) {
        SourceContentType declared = recoveredContentType(recovery.contentType());
        if (declared != SourceContentType.TEXT) return declared;
        boolean formula = recovery.orderedBlockIds().stream().map(blockById::get)
                .filter(java.util.Objects::nonNull)
                .anyMatch(block -> block.role() == DocumentBlockRole.FORMULA);
        return formula ? SourceContentType.FORMULA : declared;
    }

    private String visualFallbackText(PaperLayoutRecovery recovery,
                                      List<DocumentBlock> allBlocks,
                                      Map<String, DocumentBlock> blockById,
                                      SourceContentType type) {
        List<DocumentBlock> regionBlocks = recovery.orderedBlockIds().stream().map(blockById::get)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .toList();
        String label = type == SourceContentType.FORMULA
                ? formulaLabel(regionBlocks) : "视觉内容区域";
        StringBuilder text = new StringBuilder(label)
                .append("的文本提取不可靠，请查看原始页面区域。\n");
        String context = neighbouringText(regionBlocks, allBlocks);
        if (!context.isBlank()) text.append("邻近正文：").append(context);
        return text.toString().strip();
    }

    private String formulaLabel(List<DocumentBlock> blocks) {
        String labels = formulaNumbers(blocks.stream().map(DocumentBlock::text)
                        .collect(java.util.stream.Collectors.joining(" "))).stream()
                .map(number -> "(" + number + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        return labels.isBlank() ? "公式区域" : "公式 " + labels;
    }

    private String neighbouringText(List<DocumentBlock> regionBlocks, List<DocumentBlock> allBlocks) {
        if (regionBlocks.isEmpty()) return "";
        int firstOrder = regionBlocks.get(0).readingOrder();
        int lastOrder = regionBlocks.get(regionBlocks.size() - 1).readingOrder();
        String before = allBlocks.stream()
                .filter(block -> block.readingOrder() < firstOrder && block.role() == DocumentBlockRole.BODY)
                .filter(block -> block.text() != null && !block.text().isBlank())
                .max(Comparator.comparingInt(DocumentBlock::readingOrder))
                .map(block -> limitContext(block.text())).orElse("");
        String after = allBlocks.stream()
                .filter(block -> block.readingOrder() > lastOrder && block.role() == DocumentBlockRole.BODY)
                .filter(block -> block.text() != null && !block.text().isBlank())
                .min(Comparator.comparingInt(DocumentBlock::readingOrder))
                .map(block -> limitContext(block.text())).orElse("");
        return java.util.stream.Stream.of(before, after).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private String limitContext(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 360 ? normalized : normalized.substring(0, 360).strip() + "…";
    }

    private record CatalogKey(long paperId, String documentHash, String parserVersion,
                              List<PaperLayoutRecovery> recoveries) { }

    public List<RetrievalHit> search(PaperSourceCatalog catalog, PaperSearchRequest request) {
        String query = SourceObject.normalize(request.query()).toLowerCase(Locale.ROOT);
        Set<String> formulaNumbers = formulaNumbers(query);
        List<ScoredSource> scored = new ArrayList<>();
        for (SourceObject object : catalog.objects().values()) {
            if (!request.contentTypes().isEmpty() && !request.contentTypes().contains(object.contentType())) continue;
            List<SourceLocator> objectLocators = catalog.requireLocators(object.sourceObjectId());
            int page = objectLocators.stream().mapToInt(SourceLocator::pageNumber).min().orElse(1);
            if (request.pageStart() != null || request.pageEnd() != null) {
                int start = request.pageStart() == null ? 1 : request.pageStart();
                int end = request.pageEnd() == null ? catalog.pageCount() : request.pageEnd();
                if (objectLocators.stream().noneMatch(locator ->
                        locator.pageNumber() >= start && locator.pageNumber() <= end)) continue;
            }

            String searchable = (object.normalizedContent() + " "
                    + String.join(" ", object.sectionPath()) + " " + object.formulaNumber())
                    .toLowerCase(Locale.ROOT);
            LinkedHashSet<String> routes = new LinkedHashSet<>();
            double lexical = LayoutTextSimilarity.queryCoverage(query, searchable);
            double score = lexical * 0.72;
            if (!query.isBlank() && searchable.contains(query)) {
                routes.add("EXACT_PHRASE");
                score = Math.max(score, 0.96);
            } else if (lexical > 0) {
                routes.add("TOKEN_COVERAGE");
            }
            Set<String> objectFormulaNumbers = new LinkedHashSet<>(formulaNumbers(object.rawContent()));
            if (!object.formulaNumber().isBlank()) {
                objectFormulaNumbers.addAll(java.util.Arrays.stream(object.formulaNumber().split(","))
                        .map(String::strip).filter(value -> !value.isBlank()).toList());
            }
            if (!formulaNumbers.isEmpty() && objectFormulaNumbers.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(formulaNumbers::contains)) {
                routes.add("FORMULA_NUMBER");
                score = Math.max(score, 1.0);
            }
            double sectionCoverage = LayoutTextSimilarity.queryCoverage(query, String.join(" ", object.sectionPath()));
            if (sectionCoverage > 0) {
                routes.add("SECTION");
                score = Math.max(score, 0.35 + 0.55 * sectionCoverage);
            }
            if (score > 0) scored.add(new ScoredSource(object.sourceObjectId(), Math.min(1, score), List.copyOf(routes), page));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredSource::score).reversed()
                        .thenComparingInt(ScoredSource::page)
                        .thenComparing(ScoredSource::sourceObjectId))
                .limit(request.maxResults())
                .map(item -> new RetrievalHit(item.sourceObjectId(), item.score(), item.routes()))
                .toList();
    }

    public SourceObject readSource(PaperSourceCatalog catalog, String sourceObjectId) {
        return catalog.requireObject(sourceObjectId);
    }

    public List<SourceObject> readPages(PaperSourceCatalog catalog, int startPage, int endPage, int maxCharacters) {
        if (startPage < 1 || endPage < startPage || endPage > catalog.pageCount()) {
            throw new IllegalArgumentException("页码范围无效");
        }
        return bounded(catalog, catalog.objects().values().stream()
                .filter(object -> catalog.requireLocators(object.sourceObjectId()).stream()
                        .anyMatch(locator -> locator.pageNumber() >= startPage
                                && locator.pageNumber() <= endPage))
                .toList(), maxCharacters);
    }

    public List<SourceObject> readSection(PaperSourceCatalog catalog, String section, int maxCharacters) {
        if (section == null || section.isBlank()) throw new IllegalArgumentException("章节名称不能为空");
        String normalized = SourceObject.normalize(section).toLowerCase(Locale.ROOT);
        List<SourceObject> matched = catalog.objects().values().stream()
                .filter(object -> SourceObject.normalize(String.join(" ", object.sectionPath()))
                        .toLowerCase(Locale.ROOT).contains(normalized))
                .toList();
        return bounded(catalog, matched, maxCharacters);
    }

    private List<SourceObject> bounded(PaperSourceCatalog catalog,
                                       List<SourceObject> candidates,
                                       int maxCharacters) {
        int limit = Math.max(1_000, Math.min(200_000, maxCharacters));
        List<SourceObject> ordered = candidates.stream()
                .sorted(Comparator.comparingInt((SourceObject object) ->
                                catalog.requireLocators(object.sourceObjectId()).get(0).pageNumber())
                        .thenComparingInt(this::readingOrder)
                        .thenComparing(SourceObject::sourceObjectId))
                .toList();
        List<SourceObject> result = new ArrayList<>();
        int characters = 0;
        for (SourceObject object : ordered) {
            int next = characters + object.rawContent().length();
            if (!result.isEmpty() && next > limit) break;
            result.add(object);
            characters = next;
        }
        return List.copyOf(result);
    }

    private int readingOrder(SourceObject object) {
        try {
            return Integer.parseInt(object.provenance().getOrDefault("readingOrder", "2147483647"));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private SourceLocator locator(String sourceId, SourceAnchor anchor, String blockId) {
        EvidenceLocator.Precision precision = switch (anchor.kind()) {
            case TEXT_RANGE -> EvidenceLocator.Precision.TEXT_RANGE;
            case FORMULA_REGION -> EvidenceLocator.Precision.FORMULA_REGION;
            case VISUAL_REGION -> EvidenceLocator.Precision.VISUAL_REGION;
        };
        return new SourceLocator(locatorId(sourceId, anchor.page(), blockId), sourceId, anchor.page(),
                "PDF_NORMALIZED", anchor.boxes(), anchor.targetText(), precision);
    }

    /**
     * Formula evidence has two independent UI geometries: the complete equation
     * region and a small focus region used for the printed equation number.  The
     * PDF viewer may still refine the number to glyph-level coordinates, but it
     * must never replace the complete content region with a partial text match.
     */
    private SourceLocator formulaLocator(String sourceId, SourceAnchor anchor, DocumentBlock label) {
        List<com.research.assistant.service.pdf.layout.NormalizedBoundingBox> focus = label == null
                ? anchor.boxes() : List.of(formulaLabelBox(label));
        return new SourceLocator(locatorId(sourceId, anchor.page(), anchor.blockId()), sourceId,
                anchor.page(), "PDF_NORMALIZED", anchor.boxes(), focus, anchor.targetText(),
                EvidenceLocator.Precision.FORMULA_REGION);
    }

    private com.research.assistant.service.pdf.layout.NormalizedBoundingBox formulaLabelBox(DocumentBlock label) {
        var box = label.bbox();
        if (box.width() >= .68 && FORMULA_NUMBER.matcher(label.text()).find()
                && label.text().trim().matches("(?s).*\\(\\d{1,4}[a-z]?\\)\\s*$")) {
            double left = Math.max(.505, box.x());
            return new com.research.assistant.service.pdf.layout.NormalizedBoundingBox(left, box.y(),
                    Math.max(.01, box.right() - left), box.height());
        }
        return box;
    }

    private static String rawContent(DocumentBlock block) {
        if (block.contentMode() == DocumentBlockContentMode.STRUCTURED) {
            if (block.role() == DocumentBlockRole.FORMULA && block.latex() != null && !block.latex().isBlank()) {
                return block.text().isBlank() ? block.latex() : block.text() + "\nLaTeX：" + block.latex();
            }
            if (block.role() == DocumentBlockRole.TABLE && block.tableText() != null && !block.tableText().isBlank()) {
                return block.text().isBlank() ? block.tableText() : block.text() + "\n" + block.tableText();
            }
        }
        return block.text();
    }

    private static void addTextMetadata(Map<String, String> provenance,
                                        SourceContentType type,
                                        List<DocumentBlock> blocks,
                                        String raw) {
        if (type == SourceContentType.FORMULA) {
            boolean latex = blocks != null && blocks.stream()
                    .anyMatch(block -> block.latex() != null && !block.latex().isBlank());
            provenance.put("textFormat", latex ? "LATEX" : "PLAIN_TEXT");
            provenance.put("textReliable", Boolean.toString(latex));
            return;
        }
        provenance.put("textFormat", "PLAIN_TEXT");
        provenance.put("textReliable", Boolean.toString(raw != null && !raw.isBlank()));
    }

    private static SourceContentType contentType(DocumentBlockRole role) {
        return switch (role) {
            case FORMULA -> SourceContentType.FORMULA;
            case TABLE -> SourceContentType.TABLE;
            case FIGURE -> SourceContentType.FIGURE;
            default -> SourceContentType.TEXT;
        };
    }

    private static SourceContentType contentType(PaperSourceUnit.Kind kind) {
        return switch (kind) {
            case FORMULA_FAMILY -> SourceContentType.FORMULA;
            case FIGURE -> SourceContentType.FIGURE;
            case TABLE -> SourceContentType.TABLE;
            case ALGORITHM -> SourceContentType.ALGORITHM;
        };
    }

    private static EvidenceLocator.Precision precision(PaperSourceUnit unit) {
        return switch (unit.kind()) {
            case FORMULA_FAMILY -> EvidenceLocator.Precision.FORMULA_REGION;
            case FIGURE -> unit.blocks().stream().anyMatch(block -> block.role() == DocumentBlockRole.FIGURE)
                    ? EvidenceLocator.Precision.VISUAL_REGION : EvidenceLocator.Precision.BLOCK;
            case TABLE -> unit.blocks().stream().anyMatch(block -> block.role() == DocumentBlockRole.TABLE)
                    ? EvidenceLocator.Precision.VISUAL_REGION : EvidenceLocator.Precision.BLOCK;
            case ALGORITHM -> EvidenceLocator.Precision.BLOCK;
        };
    }

    private static EvidenceLocator.Precision precision(DocumentBlockRole role,
                                                        DocumentBlockContentMode mode) {
        if (role == DocumentBlockRole.FORMULA && mode == DocumentBlockContentMode.REGION) {
            return EvidenceLocator.Precision.FORMULA_REGION;
        }
        if ((role == DocumentBlockRole.FIGURE || role == DocumentBlockRole.TABLE)
                && mode == DocumentBlockContentMode.REGION) {
            return EvidenceLocator.Precision.VISUAL_REGION;
        }
        return EvidenceLocator.Precision.BLOCK;
    }

    private static Set<String> formulaNumbers(String query) {
        LinkedHashSet<String> numbers = new LinkedHashSet<>();
        Matcher matcher = FORMULA_NUMBER.matcher(query);
        while (matcher.find()) numbers.add(matcher.group(1).toLowerCase(Locale.ROOT));
        return Set.copyOf(numbers);
    }

    private static String stableId(PaperLayoutArtifact artifact, String suffix) {
        return "src_" + hash(artifact.paperId() + "|" + artifact.documentHash() + "|"
                + artifact.parserVersion() + "|source-v" + PaperSourceIndex.SCHEMA_VERSION + "|" + suffix).substring(0, 24);
    }

    private static String locatorId(String sourceId, int page, String suffix) {
        return "loc_" + hash(sourceId + "|page:" + page + "|" + suffix).substring(0, 24);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record ScoredSource(String sourceObjectId, double score, List<String> routes, int page) { }
}
