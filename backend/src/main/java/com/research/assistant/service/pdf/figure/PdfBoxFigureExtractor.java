package com.research.assistant.service.pdf.figure;

import com.research.assistant.service.pdf.FigureRegion;
import com.research.assistant.service.pdf.FigureRegionType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 基于 PDFBox 提取 PDF 中嵌入图片的图表提取器。
 * <p>
 * 目前仅保存尺寸大于阈值的嵌入图片，返回区域信息；更复杂的版面分析留待后续阶段。
 */
@Primary
@Component
public class PdfBoxFigureExtractor implements FigureExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxFigureExtractor.class);

    private static final int MIN_WIDTH = 100;
    private static final int MIN_HEIGHT = 100;

    @Value("${app.storage.figures-dir:./data/figures}")
    private String figuresDir;

    @Override
    public List<FigureRegion> extract(File pdfFile) {
        List<FigureRegion> regions = new ArrayList<>();
        if (pdfFile == null || !pdfFile.exists()) {
            return regions;
        }
        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            Path outputDir = resolveOutputDir(pdfFile);
            Files.createDirectories(outputDir);
            int pageNumber = 0;
            for (PDPage page : doc.getPages()) {
                pageNumber++;
                PDResources resources = page.getResources();
                if (resources == null) continue;
                for (COSName name : resources.getXObjectNames()) {
                    PDXObject xObject = resources.getXObject(name);
                    if (xObject instanceof PDImageXObject img) {
                        if (img.getWidth() >= MIN_WIDTH && img.getHeight() >= MIN_HEIGHT) {
                            String fileName = UUID.randomUUID() + ".png";
                            Path imagePath = outputDir.resolve(fileName);
                            ImageIO.write(img.getImage(), "png", imagePath.toFile());
                            regions.add(new FigureRegion(
                                    pageNumber, 0, 0, img.getWidth(), img.getHeight(),
                                    "嵌入图片 " + name.getName(), imagePath.toString(), FigureRegionType.FIGURE));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("PDFBox 图表提取失败 {}: {}", pdfFile.getName(), e.getMessage());
        }
        return regions;
    }

    private Path resolveOutputDir(File pdfFile) {
        File dir = new File(figuresDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), figuresDir);
        }
        String subDir = pdfFile.getName().replaceAll("\\.pdf$", "") + "_" + System.currentTimeMillis();
        return dir.toPath().resolve(subDir);
    }
}
