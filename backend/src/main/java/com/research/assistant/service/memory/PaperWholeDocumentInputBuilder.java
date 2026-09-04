package com.research.assistant.service.memory;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.agent.capability.AiCapabilityService;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperPdfFileResolver;
import com.research.assistant.service.pdf.layout.PaperSemanticSpan;
import com.research.assistant.service.pdf.layout.PaperSemanticSpanBuilder;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the single model request used to understand a paper. The local work
 * here is deterministic transport preparation: paragraph-level source markers
 * and page images. It does not perform model-based chunking or summarisation.
 */
@Component
public class PaperWholeDocumentInputBuilder {

    // 72 DPI keeps a full non-native PDF request within a practical vision
    // payload while the lossless structured text (including LaTeX/table text)
    // remains the primary source. Images are supplied to correct layout and
    // visual-only content, not as a second OCR transcript.
    private static final float PAGE_IMAGE_DPI = 72f;
    private static final float RECOVERY_IMAGE_DPI = 144f;
    private static final int MAX_PAGES = 60;
    private static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;

    private final PaperMapper paperMapper;
    private final PaperPdfFileResolver fileResolver;
    private final AiCapabilityService capabilityService;
    private final PaperSemanticSpanBuilder spanBuilder;
    private final LayoutUncertainRegionDetector regionDetector;

    public PaperWholeDocumentInputBuilder(PaperMapper paperMapper,
                                           PaperPdfFileResolver fileResolver,
                                           AiCapabilityService capabilityService) {
        this(paperMapper, fileResolver, capabilityService, new PaperSemanticSpanBuilder());
    }

    public PaperWholeDocumentInputBuilder(PaperMapper paperMapper,
                                           PaperPdfFileResolver fileResolver,
                                           AiCapabilityService capabilityService,
                                           PaperSemanticSpanBuilder spanBuilder) {
        this(paperMapper, fileResolver, capabilityService, spanBuilder,
                new LayoutUncertainRegionDetector());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaperWholeDocumentInputBuilder(PaperMapper paperMapper,
                                           PaperPdfFileResolver fileResolver,
                                           AiCapabilityService capabilityService,
                                           PaperSemanticSpanBuilder spanBuilder,
                                           LayoutUncertainRegionDetector regionDetector) {
        this.paperMapper = paperMapper;
        this.fileResolver = fileResolver;
        this.capabilityService = capabilityService;
        this.spanBuilder = spanBuilder;
        this.regionDetector = regionDetector;
    }

    public PaperWholeDocumentInput build(PaperStructure structure,
                                         PaperLayoutArtifact artifact,
                                         String prompt) {
        if (structure == null || artifact == null) {
            throw new IllegalArgumentException("论文结构与版面制品不能为空");
        }
        if (structure.pageCount() > MAX_PAGES) {
            throw new IllegalStateException("论文页数超过当前整篇理解范围：" + structure.pageCount());
        }
        Paper paper = paperMapper.selectById(structure.paperId());
        if (paper == null) throw new IllegalArgumentException("论文不存在");
        File pdf = fileResolver.resolveRequired(paper.getPdfPath());
        List<PaperSemanticSpan> spans = readableSpans(artifact);
        Map<String, List<String>> spanBlockIds = spanBlockIds(spans);
        List<LayoutUncertainRegion> regions = regionDetector.detect(artifact);
        Map<String, LayoutUncertainRegion> regionMap = new LinkedHashMap<>();
        regions.forEach(region -> regionMap.put(region.regionId(), region));
        List<RecoveryImage> recoveryImages = renderRecoveryImages(pdf, regions);
        String recoveryManifest = renderRecoveryManifest(regions);

        boolean nativePdf = capabilityService.documentPdfReady();
        if (nativePdf) {
            try {
                byte[] bytes = Files.readAllBytes(pdf.toPath());
                List<Content> contents = new ArrayList<>();
                contents.add(TextContent.from(prompt));
                contents.add(TextContent.from("\n以下是用于来源定位的按页结构化文本；原生 PDF 用于视觉核对。\n"
                        + renderText(structure, spans) + recoveryManifest));
                contents.add(PdfFileContent.from(Base64.getEncoder().encodeToString(bytes), "application/pdf"));
                appendRecoveryImages(contents, recoveryImages);
                ensureImageBudget(recoveryImages.stream().map(RecoveryImage::bytes).toList());
                return new PaperWholeDocumentInput(
                        "native-pdf", structure.pageCount(), 0, recoveryImages.size(),
                        contents, spanBlockIds, regionMap);
            } catch (IOException error) {
                throw new IllegalStateException("无法读取论文 PDF", error);
            }
        }

        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(prompt));
        contents.add(TextContent.from("\n下面是按页排列的论文文本；页面图片用于校正双栏、公式、图表和版式。\n"
                + renderText(structure, spans) + recoveryManifest));
        List<byte[]> images = renderPageImages(pdf);
        for (int index = 0; index < images.size(); index++) {
            contents.add(TextContent.from("\n[PAGE_IMAGE page=" + (index + 1) + "]"));
            contents.add(ImageContent.from(Base64.getEncoder().encodeToString(images.get(index)),
                    "image/jpeg"));
        }
        appendRecoveryImages(contents, recoveryImages);
        List<byte[]> allImages = new ArrayList<>(images);
        allImages.addAll(recoveryImages.stream().map(RecoveryImage::bytes).toList());
        ensureImageBudget(allImages);
        if (images.size() != structure.pageCount()) {
            throw new IllegalStateException("论文页面图片数量与 PDF 页数不一致");
        }
        return new PaperWholeDocumentInput("page-images", structure.pageCount(), images.size(),
                recoveryImages.size(), contents, spanBlockIds, regionMap);
    }

    private String renderRecoveryManifest(List<LayoutUncertainRegion> regions) {
        if (regions.isEmpty()) {
            return "\n[LAYOUT_RECOVERY_REGIONS]\nnone\n";
        }
        StringBuilder text = new StringBuilder("\n[LAYOUT_RECOVERY_REGIONS]\n");
        for (LayoutUncertainRegion region : regions) {
            text.append("regionId=").append(region.regionId())
                    .append(" issueType=").append(region.issueType())
                    .append(" blockIds=").append(String.join(",", region.blockIds()))
                    .append(" pages=").append(region.pageAreas().stream()
                            .map(area -> Integer.toString(area.page())).distinct().toList())
                    .append("\nrawText:\n").append(region.rawText()).append("\n");
        }
        text.append("只修复以上区域；无法从页面确认时返回 UNRESOLVED。\n");
        return text.toString();
    }

    private void appendRecoveryImages(List<Content> contents, List<RecoveryImage> images) {
        for (RecoveryImage image : images) {
            contents.add(TextContent.from("\n[LAYOUT_RECOVERY_IMAGE regionId=" + image.regionId()
                    + " page=" + image.page() + "]"));
            contents.add(ImageContent.from(Base64.getEncoder().encodeToString(image.bytes()), "image/jpeg"));
        }
    }

    private String renderText(PaperStructure structure, List<PaperSemanticSpan> spans) {
        StringBuilder text = new StringBuilder();
        text.append("[DOCUMENT_METADATA]\n")
                .append("title: ").append(structure.metadata().title()).append('\n')
                .append("authors: ").append(String.join(", ", structure.metadata().authors())).append('\n')
                .append("abstract: ").append(structure.metadata().abstractText()).append("\n\n");
        int currentPage = -1;
        for (PaperSemanticSpan span : spans) {
            if (span.page() != currentPage) {
                currentPage = span.page();
                text.append("\n=== PAGE ").append(currentPage).append(" ===\n");
            }
            if (span.role() == DocumentBlockRole.CAPTION) {
                text.append("[page=").append(span.page())
                        .append(" | AUXILIARY_CAPTION | no-evidence-id] ")
                        .append(span.text()).append('\n');
            } else {
                text.append('[').append(span.id()).append(" | page=")
                        .append(span.page()).append(" | ")
                        .append(displayRole(span.role())).append("] ")
                        .append(span.text()).append('\n');
            }
        }
        return text.toString();
    }

    private List<PaperSemanticSpan> readableSpans(PaperLayoutArtifact artifact) {
        return spanBuilder.build(artifact).stream()
                .filter(span -> !excluded(span.role()))
                .toList();
    }

    private Map<String, List<String>> spanBlockIds(List<PaperSemanticSpan> spans) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        spans.stream()
                .filter(span -> span.role() != DocumentBlockRole.CAPTION)
                .forEach(span -> result.put(span.id(), span.blockIds()));
        return Map.copyOf(result);
    }

    private String displayRole(DocumentBlockRole role) {
        if (role == DocumentBlockRole.BODY) return "PARAGRAPH";
        if (role == DocumentBlockRole.CAPTION) return "AUXILIARY_CAPTION";
        return role.name();
    }

    private List<byte[]> renderPageImages(File pdf) {
        try (var document = Loader.loadPDF(pdf)) {
            int pages = document.getNumberOfPages();
            if (pages <= 0) throw new IllegalStateException("论文 PDF 没有页面");
            if (pages > MAX_PAGES) {
                throw new IllegalStateException("论文页数超过当前整篇理解范围：" + pages);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            List<byte[]> images = new ArrayList<>(pages);
            long totalBytes = 0;
            for (int page = 0; page < pages; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, PAGE_IMAGE_DPI, ImageType.RGB);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "jpg", output)) {
                    throw new IllegalStateException("论文页面无法渲染为图片");
                }
                byte[] bytes = output.toByteArray();
                totalBytes += bytes.length;
                images.add(bytes);
            }
            return List.copyOf(images);
        } catch (IOException error) {
            throw new IllegalStateException("论文页面图片生成失败", error);
        }
    }

    private List<RecoveryImage> renderRecoveryImages(File pdf, List<LayoutUncertainRegion> regions) {
        if (regions.isEmpty()) return List.of();
        try (var document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            List<RecoveryImage> result = new ArrayList<>();
            for (LayoutUncertainRegion region : regions) {
                for (LayoutUncertainRegion.PageArea area : region.pageAreas()) {
                    BufferedImage page = renderer.renderImageWithDPI(
                            area.page() - 1, RECOVERY_IMAGE_DPI, ImageType.RGB);
                    NormalizedBox union = union(area.boxes());
                    int padding = Math.max(8, Math.round(page.getWidth() * 0.012f));
                    int x = Math.max(0, (int) Math.floor(union.x() * page.getWidth()) - padding);
                    int y = Math.max(0, (int) Math.floor(union.y() * page.getHeight()) - padding);
                    int right = Math.min(page.getWidth(),
                            (int) Math.ceil(union.right() * page.getWidth()) + padding);
                    int bottom = Math.min(page.getHeight(),
                            (int) Math.ceil(union.bottom() * page.getHeight()) + padding);
                    if (right <= x || bottom <= y) continue;
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    ImageIO.write(page.getSubimage(x, y, right - x, bottom - y), "jpg", output);
                    result.add(new RecoveryImage(region.regionId(), area.page(), output.toByteArray()));
                }
            }
            return List.copyOf(result);
        } catch (IOException error) {
            throw new IllegalStateException("论文异常区域图片生成失败", error);
        }
    }

    private NormalizedBox union(List<com.research.assistant.service.pdf.layout.NormalizedBoundingBox> boxes) {
        double left = boxes.stream().mapToDouble(box -> box.x()).min().orElse(0);
        double top = boxes.stream().mapToDouble(box -> box.y()).min().orElse(0);
        double right = boxes.stream().mapToDouble(box -> box.right()).max().orElse(1);
        double bottom = boxes.stream().mapToDouble(box -> box.bottom()).max().orElse(1);
        return new NormalizedBox(left, top, right, bottom);
    }

    private void ensureImageBudget(List<byte[]> images) {
        long total = images.stream().mapToLong(bytes -> bytes.length).sum();
        if (total > MAX_IMAGE_BYTES) {
            throw new IllegalStateException("论文页面及恢复区域图片总大小超过当前请求范围");
        }
    }

    private boolean excluded(DocumentBlockRole role) {
        return role == DocumentBlockRole.TITLE
                || role == DocumentBlockRole.REFERENCE
                || role == DocumentBlockRole.HEADER
                || role == DocumentBlockRole.FOOTER
                || role == DocumentBlockRole.MARGIN_METADATA
                || role == DocumentBlockRole.AUTHOR;
    }

    public record PaperWholeDocumentInput(String mode,
                                           int pageCount,
                                           int imageCount,
                                           int recoveryImageCount,
                                           List<Content> contents,
                                           Map<String, List<String>> spanBlockIds,
                                           Map<String, LayoutUncertainRegion> recoveryRegions) {
        public PaperWholeDocumentInput {
            mode = mode == null || mode.isBlank() ? "unknown" : mode.toLowerCase(Locale.ROOT);
            contents = contents == null ? List.of() : List.copyOf(contents);
            spanBlockIds = spanBlockIds == null ? Map.of() : Map.copyOf(spanBlockIds);
            recoveryRegions = recoveryRegions == null ? Map.of() : Map.copyOf(recoveryRegions);
        }

        public PaperWholeDocumentInput(String mode, int pageCount, int imageCount,
                                       List<Content> contents) {
            this(mode, pageCount, imageCount, 0, contents, Map.of(), Map.of());
        }

        public PaperWholeDocumentInput(String mode, int pageCount, int imageCount,
                                       List<Content> contents,
                                       Map<String, List<String>> spanBlockIds) {
            this(mode, pageCount, imageCount, 0, contents, spanBlockIds, Map.of());
        }
    }

    private record RecoveryImage(String regionId, int page, byte[] bytes) { }

    private record NormalizedBox(double x, double y, double right, double bottom) { }
}
