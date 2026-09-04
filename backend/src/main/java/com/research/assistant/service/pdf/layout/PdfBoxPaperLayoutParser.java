package com.research.assistant.service.pdf.layout;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDFBox-backed first-stage layout parser.
 *
 * <p>This parser deliberately stops at line-level blocks. It establishes a
 * stable coordinate system and column-aware reading order; semantic paragraph
 * merging and richer role classification are delegated to
 * {@link PaperLayoutSemanticEnricher}.</p>
 */
@Component
public class PdfBoxPaperLayoutParser implements PaperLayoutParser {

    static final String VERSION = "pdfbox-layout-v6";
    private static final double EPSILON = 0.0001;

    @Override
    public PaperLayoutArtifact parse(Long paperId, File file) {
        return parse(paperId, file, PdfDocumentFingerprint.sha256(file));
    }

    @Override
    public PaperLayoutArtifact parse(Long paperId, File file, String documentHash) {
        if (file == null || !file.isFile()) {
            throw new IllegalArgumentException("PDF 文件不存在");
        }
        if (documentHash == null || !documentHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("PDF 指纹不合法");
        }
        try (PDDocument document = Loader.loadPDF(file)) {
            GlyphCollector collector = new GlyphCollector();
            List<PageGlyphs> pages = collector.collect(document);
            DocumentGutterProfile documentGutter = detectDocumentGutter(pages);
            List<DocumentBlock> blocks = new ArrayList<>();
            List<Double> pageConfidences = new ArrayList<>();
            int readingOrder = 0;

            for (PageGlyphs page : pages) {
                PageLayout pageLayout = buildPageLayout(page, documentGutter);
                pageConfidences.add(pageLayout.confidence());
                int pageBlockIndex = 0;
                for (VisualLine line : pageLayout.orderedLines()) {
                    NormalizedBoundingBox bbox = normalize(line, page.width(), page.height());
                    DocumentBlockRole role = inferGeometricRole(line, bbox);
                    blocks.add(new DocumentBlock(
                            "p%d-b%04d".formatted(page.pageNumber(), pageBlockIndex++),
                            page.pageNumber(),
                            bbox,
                            role,
                            readingOrder++,
                            List.of(),
                            line.text(),
                            null,
                            null,
                            blockConfidence(line, role, pageLayout.doubleColumn()),
                            null,
                            null,
                            layoutLane(line.lane())
                    ));
                }
            }

            return new PaperLayoutArtifact(
                    paperId,
                    documentHash,
                    parserVersion(),
                    average(pageConfidences),
                    Instant.now(),
                    document.getNumberOfPages(),
                    blocks
            );
        } catch (IOException e) {
            throw new IllegalStateException("PDF 版面解析失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String parserVersion() {
        return VERSION;
    }

    private DocumentLayoutLane layoutLane(Lane lane) {
        return switch (lane) {
            case SINGLE -> DocumentLayoutLane.SINGLE;
            case LEFT -> DocumentLayoutLane.LEFT;
            case RIGHT -> DocumentLayoutLane.RIGHT;
            case FULL -> DocumentLayoutLane.FULL;
            case MARGIN -> DocumentLayoutLane.MARGIN;
        };
    }

    private PageLayout buildPageLayout(PageGlyphs page, DocumentGutterProfile documentGutter) {
        List<Glyph> horizontal = page.glyphs().stream()
                .filter(glyph -> glyph.orientation() == Orientation.HORIZONTAL)
                .filter(glyph -> !glyph.text().isBlank())
                .toList();
        List<RowBand> rows = clusterHorizontalRows(horizontal);
        Gutter gutter = resolvePageGutter(
                detectStableGutter(rows, page.width()), documentGutter, page.width());
        List<VisualLine> horizontalLines = rows.stream()
                .flatMap(row -> splitRow(row, gutter, page.width()).stream())
                .filter(line -> !line.text().isBlank())
                .toList();
        List<VisualLine> marginLines = clusterVerticalGlyphs(page.glyphs().stream()
                .filter(glyph -> glyph.orientation() == Orientation.VERTICAL)
                .toList());

        boolean doubleColumn = gutter != null
                && horizontalLines.stream().filter(line -> line.lane() == Lane.LEFT).count() >= 3
                && horizontalLines.stream().filter(line -> line.lane() == Lane.RIGHT).count() >= 3;
        List<VisualLine> ordered = doubleColumn
                ? orderDoubleColumn(horizontalLines)
                : horizontalLines.stream()
                        .sorted(Comparator.comparingDouble(VisualLine::top)
                                .thenComparingDouble(VisualLine::left))
                        .toList();

        List<VisualLine> withMargins = new ArrayList<>(ordered);
        marginLines.stream()
                .sorted(Comparator.comparingDouble(VisualLine::left)
                        .thenComparingDouble(VisualLine::top))
                .forEach(withMargins::add);

        double confidence;
        if (horizontalLines.isEmpty()) {
            confidence = marginLines.isEmpty() ? 0 : 0.35;
        } else if (doubleColumn) {
            confidence = 0.9;
        } else if (gutter != null) {
            confidence = 0.62;
        } else {
            confidence = 0.82;
        }
        return new PageLayout(List.copyOf(withMargins), doubleColumn, confidence);
    }

    /** Learns the dominant normalized gutter with one vote per page. */
    private DocumentGutterProfile detectDocumentGutter(List<PageGlyphs> pages) {
        List<NormalizedGutter> candidates = pages.stream()
                .map(page -> {
                    List<Glyph> horizontal = page.glyphs().stream()
                            .filter(glyph -> glyph.orientation() == Orientation.HORIZONTAL)
                            .filter(glyph -> !glyph.text().isBlank())
                            .toList();
                    Gutter gutter = detectStableGutter(clusterHorizontalRows(horizontal), page.width());
                    return gutter == null ? null : new NormalizedGutter(
                            gutter.x() / page.width(), gutter.minimumGap() / page.width());
                })
                .filter(candidate -> candidate != null)
                .toList();
        if (candidates.size() < 2) return null;

        double center = median(candidates.stream().map(NormalizedGutter::x).toList());
        List<NormalizedGutter> inliers = candidates.stream()
                .filter(candidate -> Math.abs(candidate.x() - center) <= 0.045)
                .toList();
        if (inliers.size() < 2) return null;
        return new DocumentGutterProfile(
                median(inliers.stream().map(NormalizedGutter::x).toList()),
                median(inliers.stream().map(NormalizedGutter::minimumGap).toList()),
                inliers.size());
    }

    private Gutter resolvePageGutter(Gutter local,
                                     DocumentGutterProfile document,
                                     double pageWidth) {
        if (document == null) return local;
        Gutter dominant = new Gutter(
                document.x() * pageWidth,
                document.minimumGap() * pageWidth,
                document.pageSupport());
        if (local == null || Math.abs(local.x() / pageWidth - document.x()) > 0.035) {
            return dominant;
        }
        return local;
    }

    private List<RowBand> clusterHorizontalRows(List<Glyph> glyphs) {
        if (glyphs.isEmpty()) {
            return List.of();
        }
        List<Glyph> sorted = glyphs.stream()
                .sorted(Comparator.comparingDouble(Glyph::baseline)
                        .thenComparingDouble(Glyph::left))
                .toList();
        double medianHeight = median(sorted.stream().map(Glyph::height).toList());
        double baselineTolerance = clamp(medianHeight * 0.32, 1.4, 4.5);
        List<RowBand> rows = new ArrayList<>();

        for (Glyph glyph : sorted) {
            RowBand target = null;
            for (int index = rows.size() - 1; index >= 0; index--) {
                RowBand candidate = rows.get(index);
                if (Math.abs(glyph.baseline() - candidate.baseline()) <= baselineTolerance) {
                    target = candidate;
                    break;
                }
                if (glyph.baseline() - candidate.baseline() > baselineTolerance) {
                    break;
                }
            }
            if (target == null) {
                target = new RowBand();
                rows.add(target);
            }
            target.add(glyph);
        }
        return rows;
    }

    private Gutter detectStableGutter(List<RowBand> rows, double pageWidth) {
        if (rows.size() < 4) {
            return null;
        }
        double medianHeight = median(rows.stream()
                .flatMap(row -> row.glyphs().stream())
                .map(Glyph::height)
                .toList());
        double center = pageWidth / 2;
        double centerWindow = pageWidth * 0.16;
        double minimumGap = Math.max(medianHeight * 0.78, pageWidth * 0.008);
        List<GapCandidate> candidates = new ArrayList<>();

        for (RowBand row : rows) {
            List<Glyph> ordered = row.orderedGlyphs();
            for (int index = 1; index < ordered.size(); index++) {
                Glyph previous = ordered.get(index - 1);
                Glyph current = ordered.get(index);
                double gap = current.left() - previous.right();
                double x = (previous.right() + current.left()) / 2;
                if (gap >= minimumGap
                        && previous.right() <= center
                        && current.left() >= center
                        && Math.abs(x - center) <= centerWindow) {
                    candidates.add(new GapCandidate(x, gap));
                }
            }
        }
        if (candidates.size() < 4) {
            return null;
        }

        double groupingTolerance = Math.max(medianHeight * 1.2, pageWidth * 0.012);
        List<List<GapCandidate>> groups = new ArrayList<>();
        candidates.stream()
                .sorted(Comparator.comparingDouble(GapCandidate::x))
                .forEach(candidate -> {
                    List<GapCandidate> latest = groups.isEmpty() ? null : groups.get(groups.size() - 1);
                    if (latest == null || candidate.x() - median(latest.stream().map(GapCandidate::x).toList())
                            > groupingTolerance) {
                        latest = new ArrayList<>();
                        groups.add(latest);
                    }
                    latest.add(candidate);
                });

        return groups.stream()
                .filter(group -> group.size() >= 4)
                .map(group -> new Gutter(
                        median(group.stream().map(GapCandidate::x).toList()),
                        Math.max(minimumGap,
                                median(group.stream().map(GapCandidate::gap).toList()) * 0.7),
                        group.size()))
                .max(Comparator.comparingInt(Gutter::support)
                        .thenComparingDouble(gutter -> -Math.abs(gutter.x() - center)))
                .orElse(null);
    }

    private List<VisualLine> splitRow(RowBand row, Gutter gutter, double pageWidth) {
        List<Glyph> glyphs = row.orderedGlyphs();
        if (glyphs.isEmpty()) {
            return List.of();
        }
        if (gutter == null) {
            return List.of(createLine(glyphs, Lane.SINGLE));
        }

        int splitIndex = -1;
        double splitGap = 0;
        for (int index = 1; index < glyphs.size(); index++) {
            Glyph previous = glyphs.get(index - 1);
            Glyph current = glyphs.get(index);
            double gap = current.left() - previous.right();
            if (previous.right() <= gutter.x()
                    && current.left() >= gutter.x()
                    && gap >= gutter.minimumGap()
                    && gap > splitGap) {
                splitIndex = index;
                splitGap = gap;
            }
        }
        if (splitIndex > 0) {
            return List.of(
                    createLine(glyphs.subList(0, splitIndex), Lane.LEFT),
                    createLine(glyphs.subList(splitIndex, glyphs.size()), Lane.RIGHT)
            );
        }

        // Superscripts and tall math glyphs can disturb adjacency order even though the two
        // physical columns still have a clear gutter. Partition by the stable gutter as a
        // bounded fallback; never split a genuinely full-width line whose glyphs cross it.
        List<Glyph> leftGlyphs = glyphs.stream()
                .filter(glyph -> glyph.left() + glyph.width() / 2 < gutter.x()).toList();
        List<Glyph> rightGlyphs = glyphs.stream()
                .filter(glyph -> glyph.left() + glyph.width() / 2 >= gutter.x()).toList();
        if (leftGlyphs.size() >= 2 && rightGlyphs.size() >= 2) {
            double leftRight = leftGlyphs.stream().mapToDouble(Glyph::right).max().orElse(gutter.x());
            double rightLeft = rightGlyphs.stream().mapToDouble(Glyph::left).min().orElse(gutter.x());
            VisualLine leftLine = createLine(leftGlyphs, Lane.LEFT);
            VisualLine rightLine = createLine(rightGlyphs, Lane.RIGHT);
            boolean proseBesideNumberedFormula = numberedFormulaBesideProse(leftLine.text(), rightLine.text())
                    || numberedFormulaBesideProse(rightLine.text(), leftLine.text());
            boolean pairedNumberedFormulas = containsNumberedFormula(leftLine.text())
                    && containsNumberedFormula(rightLine.text());
            if (rightLeft - leftRight >= gutter.minimumGap()
                    || hasCenterWhitespaceValley(leftGlyphs, rightGlyphs, pageWidth)
                    || proseBesideNumberedFormula || pairedNumberedFormulas) {
                return List.of(leftLine, rightLine);
            }
        }

        VisualLine line = createLine(glyphs, Lane.FULL);
        double tolerance = Math.max(2, pageWidth * 0.004);
        if (line.right() <= gutter.x() + tolerance) {
            return List.of(line.withLane(Lane.LEFT));
        }
        if (line.left() >= gutter.x() - tolerance) {
            return List.of(line.withLane(Lane.RIGHT));
        }
        return List.of(line);
    }

    /**
     * Uses glyph centers so a tall or wide mathematical symbol may overhang the
     * gutter without joining two otherwise separate column lines.
     */
    private boolean hasCenterWhitespaceValley(List<Glyph> leftGlyphs,
                                              List<Glyph> rightGlyphs,
                                              double pageWidth) {
        double leftCenter = leftGlyphs.stream()
                .mapToDouble(glyph -> glyph.left() + glyph.width() / 2)
                .max()
                .orElse(Double.POSITIVE_INFINITY);
        double rightCenter = rightGlyphs.stream()
                .mapToDouble(glyph -> glyph.left() + glyph.width() / 2)
                .min()
                .orElse(Double.NEGATIVE_INFINITY);
        double medianGlyphWidth = median(java.util.stream.Stream.concat(
                        leftGlyphs.stream(), rightGlyphs.stream())
                .map(Glyph::width)
                .toList());
        double minimumCenterGap = Math.max(pageWidth * 0.009, medianGlyphWidth * 1.6);
        return rightCenter - leftCenter >= minimumCenterGap;
    }

    private boolean numberedFormulaBesideProse(String prose, String formula) {
        if (prose == null || formula == null
                || !formula.matches("(?s).*\\(\\d{1,4}[a-z]?\\)\\s*[.,;:]?\\s*$")) return false;
        Matcher words = Pattern.compile("[A-Za-z]{2,}").matcher(prose);
        int count = 0;
        while (words.find() && count < 4) count++;
        return count >= 4;
    }

    private boolean containsNumberedFormula(String value) {
        return value != null && value.matches("(?s).*\\(\\d{1,4}[a-z]?\\).*" );
    }

    private List<VisualLine> clusterVerticalGlyphs(List<Glyph> glyphs) {
        if (glyphs.isEmpty()) {
            return List.of();
        }
        double medianWidth = median(glyphs.stream().map(Glyph::width).toList());
        double xTolerance = clamp(medianWidth * 0.8, 2, 8);
        List<List<Glyph>> columns = new ArrayList<>();

        glyphs.stream()
                .sorted(Comparator.comparingDouble(Glyph::left)
                        .thenComparingDouble(Glyph::top))
                .forEach(glyph -> {
                    List<Glyph> target = columns.stream()
                            .filter(column -> Math.abs(glyph.left()
                                    - median(column.stream().map(Glyph::left).toList())) <= xTolerance)
                            .findFirst()
                            .orElse(null);
                    if (target == null) {
                        target = new ArrayList<>();
                        columns.add(target);
                    }
                    target.add(glyph);
                });

        return columns.stream()
                .map(this::createVerticalLine)
                .toList();
    }

    private List<VisualLine> orderDoubleColumn(List<VisualLine> lines) {
        List<VisualLine> left = lines.stream()
                .filter(line -> line.lane() == Lane.LEFT)
                .sorted(Comparator.comparingDouble(VisualLine::top)
                        .thenComparingDouble(VisualLine::left))
                .toList();
        List<VisualLine> right = lines.stream()
                .filter(line -> line.lane() == Lane.RIGHT)
                .sorted(Comparator.comparingDouble(VisualLine::top)
                        .thenComparingDouble(VisualLine::left))
                .toList();
        List<VisualLine> anchors = lines.stream()
                .filter(line -> line.lane() == Lane.FULL)
                .sorted(Comparator.comparingDouble(VisualLine::top)
                        .thenComparingDouble(VisualLine::left))
                .toList();
        List<VisualLine> result = new ArrayList<>(lines.size());
        Set<VisualLine> consumed = new LinkedHashSet<>();
        double cursor = Double.NEGATIVE_INFINITY;

        for (List<VisualLine> anchorGroup : groupFullWidthAnchors(anchors)) {
            double groupTop = anchorGroup.stream()
                    .mapToDouble(VisualLine::top)
                    .min()
                    .orElse(Double.POSITIVE_INFINITY);
            double groupBottom = anchorGroup.stream()
                    .mapToDouble(VisualLine::bottom)
                    .max()
                    .orElse(groupTop);

            appendColumnInterval(result, consumed, left, cursor, groupTop);
            appendColumnInterval(result, consumed, right, cursor, groupTop);
            anchorGroup.forEach(anchor -> {
                result.add(anchor);
                consumed.add(anchor);
            });
            appendOverlappingColumnLines(result, consumed, left, groupTop, groupBottom);
            appendOverlappingColumnLines(result, consumed, right, groupTop, groupBottom);
            cursor = Math.max(cursor, groupBottom);
        }
        appendColumnInterval(result, consumed, left, cursor, Double.POSITIVE_INFINITY);
        appendColumnInterval(result, consumed, right, cursor, Double.POSITIVE_INFINITY);

        lines.stream()
                .filter(line -> !consumed.contains(line))
                .sorted(Comparator.comparingDouble(VisualLine::top)
                        .thenComparingDouble(VisualLine::left))
                .forEach(result::add);
        return stabilizeLanePhases(result);
    }

    /** Enforces the page-region contract: within two full-width boundaries, read left then right. */
    private List<VisualLine> stabilizeLanePhases(List<VisualLine> ordered) {
        List<VisualLine> result = new ArrayList<>(ordered.size());
        List<VisualLine> region = new ArrayList<>();
        for (VisualLine line : ordered) {
            if (line.lane() == Lane.FULL) {
                appendStableRegion(result, region);
                result.add(line);
                region.clear();
            } else {
                region.add(line);
            }
        }
        appendStableRegion(result, region);
        return List.copyOf(result);
    }

    private void appendStableRegion(List<VisualLine> target, List<VisualLine> region) {
        if (!hasOverlappingLaneInversion(region)) {
            target.addAll(region);
            return;
        }
        region.stream().filter(line -> line.lane() == Lane.LEFT).forEach(target::add);
        region.stream().filter(line -> line.lane() == Lane.RIGHT).forEach(target::add);
        region.stream().filter(line -> line.lane() != Lane.LEFT && line.lane() != Lane.RIGHT)
                .forEach(target::add);
    }

    private boolean hasOverlappingLaneInversion(List<VisualLine> region) {
        VisualLine previous = null;
        for (VisualLine line : region) {
            if (line.lane() != Lane.LEFT && line.lane() != Lane.RIGHT) continue;
            if (previous != null && previous.lane() == Lane.RIGHT && line.lane() == Lane.LEFT) {
                double tolerance = Math.max(2.0,
                        Math.min(previous.bottom() - previous.top(), line.bottom() - line.top()) * .5);
                if (line.top() <= previous.bottom() + tolerance) return true;
            }
            previous = line;
        }
        return false;
    }

    /**
     * Treat adjacent full-width lines as one vertical reading-order boundary.
     *
     * <p>A multi-line formula, caption, or algorithm header is commonly emitted
     * as several PDF text lines. If each line is used as an independent anchor,
     * a column line whose baseline falls between two object lines can be
     * inserted into the object. Grouping only changes ordering; the original
     * line-level blocks and their coordinates remain intact.</p>
     */
    private List<List<VisualLine>> groupFullWidthAnchors(List<VisualLine> anchors) {
        if (anchors.isEmpty()) {
            return List.of();
        }
        List<List<VisualLine>> groups = new ArrayList<>();
        List<VisualLine> current = new ArrayList<>();
        double currentBottom = Double.NEGATIVE_INFINITY;

        for (VisualLine anchor : anchors) {
            double gap = anchor.top() - currentBottom;
            double tolerance = current.isEmpty()
                    ? 0
                    : Math.max(3.0, median(current.stream()
                            .map(line -> line.bottom() - line.top())
                            .toList()) * 3.0);
            if (!current.isEmpty() && gap > tolerance) {
                groups.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            current.add(anchor);
            currentBottom = Math.max(currentBottom, anchor.bottom());
        }
        if (!current.isEmpty()) {
            groups.add(List.copyOf(current));
        }
        return List.copyOf(groups);
    }

    private void appendOverlappingColumnLines(List<VisualLine> target,
                                              Set<VisualLine> consumed,
                                              List<VisualLine> source,
                                              double fromInclusive,
                                              double toInclusive) {
        source.stream()
                .filter(line -> !consumed.contains(line))
                .filter(line -> line.centerY() > fromInclusive + EPSILON
                        && line.centerY() < toInclusive - EPSILON)
                .forEach(line -> {
                    target.add(line);
                    consumed.add(line);
                });
    }

    private void appendColumnInterval(List<VisualLine> target,
                                      Set<VisualLine> consumed,
                                      List<VisualLine> source,
                                      double fromInclusive,
                                      double toInclusive) {
        source.stream()
                .filter(line -> !consumed.contains(line))
                .filter(line -> line.centerY() >= fromInclusive - EPSILON
                        && line.centerY() <= toInclusive + EPSILON)
                .forEach(line -> {
                    target.add(line);
                    consumed.add(line);
                });
    }

    private VisualLine createLine(List<Glyph> glyphs, Lane lane) {
        List<Glyph> ordered = glyphs.stream()
                .sorted(Comparator.comparingDouble(Glyph::left)
                        .thenComparingDouble(Glyph::baseline))
                .toList();
        double left = ordered.stream().mapToDouble(Glyph::left).min().orElse(0);
        double right = ordered.stream().mapToDouble(Glyph::right).max().orElse(left);
        double top = ordered.stream().mapToDouble(Glyph::top).min().orElse(0);
        double bottom = ordered.stream().mapToDouble(Glyph::bottom).max().orElse(top);
        return new VisualLine(joinGlyphs(ordered), left, top, right, bottom, lane);
    }

    private VisualLine createVerticalLine(List<Glyph> glyphs) {
        List<Glyph> ordered = glyphs.stream()
                .sorted(Comparator.comparingInt(Glyph::sourceOrder))
                .toList();
        double left = ordered.stream().mapToDouble(Glyph::left).min().orElse(0);
        double right = ordered.stream().mapToDouble(Glyph::right).max().orElse(left);
        double top = ordered.stream().mapToDouble(Glyph::top).min().orElse(0);
        double bottom = ordered.stream().mapToDouble(Glyph::bottom).max().orElse(top);
        String text = ordered.stream()
                .map(Glyph::text)
                .map(this::normalizeGlyphText)
                .reduce("", String::concat)
                .replaceAll("\\s+", " ")
                .trim();
        return new VisualLine(text, left, top, right, bottom, Lane.MARGIN);
    }

    private String joinGlyphs(List<Glyph> glyphs) {
        if (glyphs.isEmpty()) {
            return "";
        }
        double medianCharacterWidth = median(glyphs.stream()
                .map(glyph -> glyph.width() / Math.max(1, glyph.text().codePointCount(0, glyph.text().length())))
                .toList());
        // PDF content streams frequently omit literal space glyphs and encode
        // a word boundary only as a small cursor jump. Keep the threshold well
        // below half a normal character width so headings such as
        // "PROOF OF LEMMA 1" do not collapse, while ordinary kerning (usually
        // zero or slightly negative) remains untouched.
        double spaceThreshold = Math.max(0.7, medianCharacterWidth * 0.28);
        StringBuilder text = new StringBuilder();
        Glyph previous = null;
        for (Glyph glyph : glyphs) {
            String value = normalizeGlyphText(glyph.text());
            if (value.isBlank()) {
                continue;
            }
            if (previous != null && glyph.left() - previous.right() > spaceThreshold
                    && !endsWithWhitespace(text)) {
                text.append(' ');
            }
            text.append(value);
            previous = glyph;
        }
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private String normalizeGlyphText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00a0', ' ')
                .replaceAll("[\\p{Cc}&&[^\\t]]", "");
    }

    private boolean endsWithWhitespace(StringBuilder text) {
        return !text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1));
    }

    private NormalizedBoundingBox normalize(VisualLine line, double pageWidth, double pageHeight) {
        return new NormalizedBoundingBox(
                line.left() / pageWidth,
                line.top() / pageHeight,
                Math.max(0, line.right() - line.left()) / pageWidth,
                Math.max(0, line.bottom() - line.top()) / pageHeight
        );
    }

    private DocumentBlockRole inferGeometricRole(VisualLine line, NormalizedBoundingBox bbox) {
        if (line.lane() == Lane.MARGIN
                || (bbox.width() < 0.08 && (bbox.x() < 0.035 || bbox.right() > 0.965))) {
            return DocumentBlockRole.MARGIN_METADATA;
        }
        if (bbox.y() < 0.055 && bbox.height() < 0.05) {
            return DocumentBlockRole.HEADER;
        }
        if (bbox.bottom() > 0.95 && bbox.height() < 0.05) {
            return DocumentBlockRole.FOOTER;
        }
        return DocumentBlockRole.BODY;
    }

    private double blockConfidence(VisualLine line, DocumentBlockRole role, boolean doubleColumn) {
        if (role == DocumentBlockRole.MARGIN_METADATA) {
            return 0.68;
        }
        if (role == DocumentBlockRole.HEADER || role == DocumentBlockRole.FOOTER) {
            return 0.82;
        }
        if (line.lane() == Lane.FULL && doubleColumn) {
            return 0.72;
        }
        return doubleColumn ? 0.88 : 0.82;
    }

    private static double average(List<Double> values) {
        return values == null || values.isEmpty()
                ? 0
                : values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double median(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = values.stream()
                .filter(Double::isFinite)
                .sorted()
                .toList();
        if (sorted.isEmpty()) {
            return 0;
        }
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2
                : sorted.get(middle);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private enum Orientation { HORIZONTAL, VERTICAL }

    private enum Lane { SINGLE, LEFT, RIGHT, FULL, MARGIN }

    private record Glyph(String text,
                         double left,
                         double top,
                         double width,
                         double height,
                         double baseline,
                         Orientation orientation,
                         int sourceOrder) {
        double right() {
            return left + width;
        }

        double bottom() {
            return top + height;
        }
    }

    private record PageGlyphs(int pageNumber, double width, double height, List<Glyph> glyphs) {
    }

    private record GapCandidate(double x, double gap) {
    }

    private record Gutter(double x, double minimumGap, int support) {
    }

    private record NormalizedGutter(double x, double minimumGap) {
    }

    private record DocumentGutterProfile(double x, double minimumGap, int pageSupport) {
    }

    private record PageLayout(List<VisualLine> orderedLines, boolean doubleColumn, double confidence) {
    }

    private record VisualLine(String text,
                              double left,
                              double top,
                              double right,
                              double bottom,
                              Lane lane) {
        double centerY() {
            return (top + bottom) / 2;
        }

        VisualLine withLane(Lane newLane) {
            return new VisualLine(text, left, top, right, bottom, newLane);
        }
    }

    private static final class RowBand {
        private final List<Glyph> glyphs = new ArrayList<>();
        private double baseline;

        void add(Glyph glyph) {
            glyphs.add(glyph);
            baseline = median(glyphs.stream().map(Glyph::baseline).toList());
        }

        double baseline() {
            return baseline;
        }

        List<Glyph> glyphs() {
            return glyphs;
        }

        List<Glyph> orderedGlyphs() {
            return glyphs.stream()
                    .sorted(Comparator.comparingDouble(Glyph::left)
                            .thenComparingDouble(Glyph::baseline))
                    .toList();
        }
    }

    private static final class GlyphCollector extends PDFTextStripper {
        private final List<PageGlyphs> pages = new ArrayList<>();
        private List<Glyph> currentGlyphs;
        private int pageNumber;
        private double pageWidth;
        private double pageHeight;
        private int glyphOrder;

        GlyphCollector() throws IOException {
            setSortByPosition(true);
            setSuppressDuplicateOverlappingText(true);
        }

        List<PageGlyphs> collect(PDDocument document) throws IOException {
            pages.clear();
            pageNumber = 0;
            glyphOrder = 0;
            getText(document);
            return List.copyOf(pages);
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            pageNumber++;
            currentGlyphs = new ArrayList<>();
            int rotation = Math.floorMod(page.getRotation(), 360);
            boolean quarterTurn = rotation == 90 || rotation == 270;
            pageWidth = quarterTurn ? page.getCropBox().getHeight() : page.getCropBox().getWidth();
            pageHeight = quarterTurn ? page.getCropBox().getWidth() : page.getCropBox().getHeight();
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            for (TextPosition position : textPositions) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isBlank()) {
                    continue;
                }
                double height = Math.max(0.1, position.getHeightDir());
                double baseline = position.getYDirAdj();
                double direction = Math.floorMod(Math.round(position.getDir()), 360);
                boolean vertical = (direction >= 45 && direction < 135)
                        || (direction >= 225 && direction < 315);
                double left = vertical ? position.getX() : position.getXDirAdj();
                double visualHeight = vertical
                        ? Math.max(0.1, position.getWidth())
                        : height;
                double visualWidth = vertical
                        ? Math.max(0.1, position.getHeight())
                        : Math.max(0.1, position.getWidthDirAdj());
                double pageBaseline = vertical ? position.getY() : baseline;
                currentGlyphs.add(new Glyph(
                        unicode,
                        left,
                        Math.max(0, pageBaseline - visualHeight),
                        visualWidth,
                        visualHeight,
                        pageBaseline,
                        vertical ? Orientation.VERTICAL : Orientation.HORIZONTAL,
                        glyphOrder++
                ));
            }
            super.writeString(text, textPositions);
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            pages.add(new PageGlyphs(pageNumber, pageWidth, pageHeight, List.copyOf(currentGlyphs)));
            super.endPage(page);
        }
    }
}
