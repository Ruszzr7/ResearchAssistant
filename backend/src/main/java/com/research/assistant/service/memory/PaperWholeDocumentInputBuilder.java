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
    private static final int MAX_PAGES = 40;
    private static final long MAX_IMAGE_BYTES = 64L * 1024 * 1024;

    private final PaperMapper paperMapper;
    private final PaperPdfFileResolver fileResolver;
    private final AiCapabilityService capabilityService;
    private final PaperSemanticSpanBuilder spanBuilder;

    public PaperWholeDocumentInputBuilder(PaperMapper paperMapper,
                                           PaperPdfFileResolver fileResolver,
                                           AiCapabilityService capabilityService) {
        this(paperMapper, fileResolver, capabilityService, new PaperSemanticSpanBuilder());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaperWholeDocumentInputBuilder(PaperMapper paperMapper,
                                           PaperPdfFileResolver fileResolver,
                                           AiCapabilityService capabilityService,
                                           PaperSemanticSpanBuilder spanBuilder) {
        this.paperMapper = paperMapper;
        this.fileResolver = fileResolver;
        this.capabilityService = capabilityService;
        this.spanBuilder = spanBuilder;
    }

    public PaperWholeDocumentInput build(PaperStructure structure,
                                         PaperLayoutArtifact artifact,
                                         String prompt) {
        if (structure == null || artifact == null) {
            throw new IllegalArgumentException("论文结构与版面制品不能为空");
        }
        Paper paper = paperMapper.selectById(structure.paperId());
        if (paper == null) throw new IllegalArgumentException("论文不存在");
        File pdf = fileResolver.resolveRequired(paper.getPdfPath());
        List<PaperSemanticSpan> spans = readableSpans(artifact);
        Map<String, List<String>> spanBlockIds = spanBlockIds(spans);

        boolean nativePdf = capabilityService.documentPdfReady();
        if (nativePdf) {
            try {
                byte[] bytes = Files.readAllBytes(pdf.toPath());
                return new PaperWholeDocumentInput(
                        "native-pdf", structure.pageCount(), 0,
                        List.of(TextContent.from(prompt),
                                TextContent.from("\n以下是用于来源定位的按页结构化文本；原生 PDF 用于视觉核对。\n"
                                        + renderText(structure, spans)),
                                PdfFileContent.from(Base64.getEncoder().encodeToString(bytes),
                                        "application/pdf")), spanBlockIds);
            } catch (IOException error) {
                throw new IllegalStateException("无法读取论文 PDF", error);
            }
        }

        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(prompt));
        contents.add(TextContent.from("\n下面是按页排列的论文文本；页面图片用于校正双栏、公式、图表和版式。\n"
                + renderText(structure, spans)));
        List<byte[]> images = renderPageImages(pdf);
        for (int index = 0; index < images.size(); index++) {
            contents.add(TextContent.from("\n[PAGE_IMAGE page=" + (index + 1) + "]"));
            contents.add(ImageContent.from(Base64.getEncoder().encodeToString(images.get(index)),
                    "image/jpeg"));
        }
        if (images.size() != structure.pageCount()) {
            throw new IllegalStateException("论文页面图片数量与 PDF 页数不一致");
        }
        return new PaperWholeDocumentInput("page-images", structure.pageCount(), images.size(),
                contents, spanBlockIds);
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
                if (totalBytes > MAX_IMAGE_BYTES) {
                    throw new IllegalStateException("论文页面图片总大小超过当前请求范围");
                }
                images.add(bytes);
            }
            return List.copyOf(images);
        } catch (IOException error) {
            throw new IllegalStateException("论文页面图片生成失败", error);
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
                                           List<Content> contents,
                                           Map<String, List<String>> spanBlockIds) {
        public PaperWholeDocumentInput {
            mode = mode == null || mode.isBlank() ? "unknown" : mode.toLowerCase(Locale.ROOT);
            contents = contents == null ? List.of() : List.copyOf(contents);
            spanBlockIds = spanBlockIds == null ? Map.of() : Map.copyOf(spanBlockIds);
        }

        public PaperWholeDocumentInput(String mode, int pageCount, int imageCount,
                                       List<Content> contents) {
            this(mode, pageCount, imageCount, contents, Map.of());
        }
    }
}
