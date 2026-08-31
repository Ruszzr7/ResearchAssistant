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
import com.research.assistant.service.pdf.layout.PaperSemanticSpan;
import com.research.assistant.service.pdf.layout.PaperSemanticSpanBuilder;
import com.research.assistant.service.pdf.layout.SourceAnchor;
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
        this(artifactService, sourceIndexService, new PaperSemanticSpanBuilder());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaperSourceCatalogService(PaperLayoutArtifactService artifactService,
                                     PaperSourceIndexService sourceIndexService,
                                     PaperSemanticSpanBuilder spanBuilder) {
        this.artifactService = artifactService;
        this.sourceIndexService = sourceIndexService;
        this.spanBuilder = spanBuilder;
    }

    public PaperSourceCatalog latest(long paperId) {
        PaperLayoutArtifact artifact = artifactService.latestArtifact(paperId);
        if (artifact == null) throw new IllegalStateException("LOCAL_SOURCE_NOT_READY");
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
            throw new IllegalArgumentException("a versioned paper layout artifact is required");
        }
        CatalogKey key = new CatalogKey(artifact.paperId(), artifact.documentHash(), artifact.parserVersion());
        return catalogCache.get(key, ignored -> buildUncached(artifact));
    }

    private PaperSourceCatalog buildUncached(PaperLayoutArtifact artifact) {
        PaperSourceIndex index = sourceIndexService.build(artifact);
        Map<String, DocumentBlock> blockById = new LinkedHashMap<>();
        artifact.blocks().stream().sorted(Comparator.comparingInt(DocumentBlock::readingOrder))
                .forEach(block -> blockById.put(block.id(), block));
        Map<String, SourceAnchor> textAnchorByBlock = new LinkedHashMap<>();
        index.textAnchors().forEach(anchor -> textAnchorByBlock.put(anchor.blockId(), anchor));

        Map<String, SourceObject> objects = new LinkedHashMap<>();
        Map<String, List<SourceLocator>> locators = new LinkedHashMap<>();
        for (PaperSemanticSpan span : spanBuilder.build(artifact)) {
            String sourceId = stableId(artifact, "span:" + span.id());
            String raw = span.text();
            SourceObject object = new SourceObject(sourceId, artifact.paperId(), artifact.documentHash(),
                    artifact.parserVersion(), PaperSourceIndex.SCHEMA_VERSION, contentType(span.role()), raw,
                    SourceObject.normalize(raw), span.sectionPath(), "",
                    Map.of("spanId", span.id(), "blockIds", String.join(",", span.blockIds()),
                            "role", span.role().name(), "readingOrder",
                            Integer.toString(span.blocks().get(0).readingOrder())));
            List<SourceLocator> spanLocators = span.blocks().stream().map(block -> {
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
            DocumentBlock block = blockById.get(definition.blockId());
            List<String> sectionPath = new ArrayList<>(block == null ? List.of() : block.sectionPath());
            sectionPath.add("Equation (" + equation.number() + ")");
            if (!equation.statementLabel().isBlank()) sectionPath.add(equation.statementLabel());
            String raw = definition.targetText();
            if (raw.isBlank() && block != null) raw = rawContent(block);
            SourceObject object = new SourceObject(equation.entityId(), artifact.paperId(),
                    artifact.documentHash(), artifact.parserVersion(), PaperSourceIndex.SCHEMA_VERSION,
                    SourceContentType.FORMULA, raw, SourceObject.normalize(raw), sectionPath,
                    equation.number(), Map.of("blockId", definition.blockId(),
                    "relation", equation.relation().name()));
            objects.put(equation.entityId(), object);
            locators.put(equation.entityId(), List.of(
                    locator(equation.entityId(), definition, definition.blockId())));
        }
        return new PaperSourceCatalog(artifact.paperId(), artifact.documentHash(), artifact.parserVersion(),
                artifact.pageCount(), objects, locators);
    }

    private record CatalogKey(long paperId, String documentHash, String parserVersion) { }

    public List<RetrievalHit> search(PaperSourceCatalog catalog, PaperSearchRequest request) {
        String query = SourceObject.normalize(request.query()).toLowerCase(Locale.ROOT);
        Set<String> formulaNumbers = formulaNumbers(query);
        List<ScoredSource> scored = new ArrayList<>();
        for (SourceObject object : catalog.objects().values()) {
            if (!request.contentTypes().isEmpty() && !request.contentTypes().contains(object.contentType())) continue;
            int page = catalog.requireLocators(object.sourceObjectId()).get(0).pageNumber();
            if (request.pageStart() != null && page < request.pageStart()) continue;
            if (request.pageEnd() != null && page > request.pageEnd()) continue;

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
            if (!formulaNumbers.isEmpty() && formulaNumbers.contains(object.formulaNumber().toLowerCase(Locale.ROOT))) {
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
            throw new IllegalArgumentException("invalid page range");
        }
        return bounded(catalog, catalog.objects().values().stream()
                .filter(object -> {
                    int page = catalog.requireLocators(object.sourceObjectId()).get(0).pageNumber();
                    return page >= startPage && page <= endPage;
                }).toList(), maxCharacters);
    }

    public List<SourceObject> readSection(PaperSourceCatalog catalog, String section, int maxCharacters) {
        if (section == null || section.isBlank()) throw new IllegalArgumentException("section is required");
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

    private static String rawContent(DocumentBlock block) {
        if (block.contentMode() == DocumentBlockContentMode.STRUCTURED) {
            if (block.role() == DocumentBlockRole.FORMULA && block.latex() != null && !block.latex().isBlank()) {
                return block.text().isBlank() ? block.latex() : block.text() + "\nLaTeX: " + block.latex();
            }
            if (block.role() == DocumentBlockRole.TABLE && block.tableText() != null && !block.tableText().isBlank()) {
                return block.text().isBlank() ? block.tableText() : block.text() + "\n" + block.tableText();
            }
        }
        return block.text();
    }

    private static SourceContentType contentType(DocumentBlockRole role) {
        return switch (role) {
            case FORMULA -> SourceContentType.FORMULA;
            case TABLE -> SourceContentType.TABLE;
            case FIGURE -> SourceContentType.FIGURE;
            default -> SourceContentType.TEXT;
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
