package com.research.assistant.service.pdf.formula.region;

import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperFormulaRegionRecord;
import com.research.assistant.mapper.PaperFormulaRegionMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.pdf.layout.DocumentBlock;
import com.research.assistant.service.pdf.layout.DocumentBlockContentMode;
import com.research.assistant.service.pdf.layout.DocumentBlockRole;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifact;
import com.research.assistant.service.pdf.layout.PaperLayoutArtifactService;
import com.research.assistant.service.pdf.layout.PaperPdfFileResolver;
import com.research.assistant.service.pdf.layout.SelectionAnchor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Coordinates deterministic layout reuse, bounded image recognition and user confirmation. */
@Service
public class FormulaRegionService {

    private static final Logger log = LoggerFactory.getLogger(FormulaRegionService.class);
    private static final double MIN_MULTIMODAL_CONFIDENCE = 0.55;
    private static final double CONFIRMED_REGION_REUSE_IOU = 0.82;

    private final PaperLayoutArtifactService artifactService;
    private final PaperMapper paperMapper;
    private final PaperFormulaRegionMapper regionMapper;
    private final PaperPdfFileResolver fileResolver;
    private final FormulaRegionImageService imageService;
    private final FormulaVisionRecognizer visionRecognizer;
    private final ConfirmedFormulaRegionService confirmedService;
    private final FormulaRecognitionTelemetry telemetry;

    public FormulaRegionService(PaperLayoutArtifactService artifactService,
                                PaperMapper paperMapper,
                                PaperFormulaRegionMapper regionMapper,
                                PaperPdfFileResolver fileResolver,
                                FormulaRegionImageService imageService,
                                FormulaVisionRecognizer visionRecognizer,
                                ConfirmedFormulaRegionService confirmedService,
                                FormulaRecognitionTelemetry telemetry) {
        this.artifactService = artifactService;
        this.paperMapper = paperMapper;
        this.regionMapper = regionMapper;
        this.fileResolver = fileResolver;
        this.imageService = imageService;
        this.visionRecognizer = visionRecognizer;
        this.confirmedService = confirmedService;
        this.telemetry = telemetry;
    }

    public FormulaRegionRecognition recognize(Long paperId,
                                              int page,
                                              NormalizedBoundingBox bbox) {
        return recognize(paperId, page, bbox, false);
    }

    public FormulaRegionRecognition recognize(Long paperId,
                                              int page,
                                              NormalizedBoundingBox bbox,
                                              boolean refresh) {
        return recognize(paperId, page, bbox, refresh, null);
    }

    public FormulaRegionRecognition recognize(Long paperId,
                                              int page,
                                              NormalizedBoundingBox bbox,
                                              boolean refresh,
                                              String clientImageDataUrl) {
        long totalStarted = telemetry.start();
        FormulaRegionGeometry.validate(bbox);
        long artifactStarted = telemetry.start();
        PaperLayoutArtifact artifact = artifactService.ensureArtifact(paperId, false);
        telemetry.stage("layout_artifact", "success", artifactStarted);
        if (page < 1 || page > artifact.pageCount()) {
            throw new IllegalArgumentException("公式页码超出 PDF 范围");
        }
        String regionKey = FormulaRegionGeometry.regionKey(page, bbox);

        PaperFormulaRegionRecord existing = regionMapper.selectCurrent(
                paperId, artifact.documentHash(), artifact.parserVersion(), page, regionKey);
        if (existing != null && FormulaRegionStatus.CONFIRMED.name().equals(existing.getStatus())
                && existing.getLatex() != null && !existing.getLatex().isBlank()) {
            return completed(result(artifact, existing, "", "已读取确认过的公式，无需再次识别"),
                    "exact_confirmed_cache", totalStarted);
        }

        Optional<PaperFormulaRegionRecord> similarConfirmed =
                bestConfirmedRegion(artifact, page, bbox);
        if (similarConfirmed.isPresent()) {
            return completed(result(artifact, similarConfirmed.get(), "",
                    "已复用同页确认过的公式，无需再次识别"), "similar_confirmed_cache", totalStarted);
        }

        Optional<DocumentBlock> structured = bestStructuredFormula(artifact, page, bbox);
        if (structured.isPresent()) {
            DocumentBlock block = structured.get();
            PaperFormulaRegionRecord record = save(existing, artifact, page, bbox, regionKey,
                    block.latex(), block.confidence(), FormulaRegionSource.LAYOUT,
                    FormulaRegionStatus.CONFIRMED);
            return completed(result(artifact, record, "", "已复用论文版面中的结构化公式"),
                    "structured_layout", totalStarted);
        }

        if (!refresh && existing != null) {
            return completed(result(artifact, existing, "",
                    "已读取该区域的识别结果；如需重新调用模型，请点击重新识别"),
                    "exact_candidate_cache", totalStarted);
        }

        Paper paper = requirePaper(paperId);
        File pdf = fileResolver.resolveRequired(paper.getPdfPath());
        long imageStarted = telemetry.start();
        FormulaRegionImage image;
        if (clientImageDataUrl == null || clientImageDataUrl.isBlank()) {
            image = imageService.render(pdf, page, bbox, artifact.documentHash());
        } else {
            try {
                image = imageService.fromClientDataUrl(clientImageDataUrl);
            } catch (IllegalArgumentException invalidClientImage) {
                telemetry.stage("client_crop_normalize", "fallback", imageStarted);
                log.debug("event=formula_client_crop_fallback errorType={}",
                        invalidClientImage.getClass().getSimpleName());
                image = imageService.render(pdf, page, bbox, artifact.documentHash());
            }
        }
        telemetry.stage("image_prepare", "success", imageStarted);
        FormulaVisionRecognizer.FormulaCandidate candidate;
        long modelStarted = telemetry.start();
        try {
            candidate = visionRecognizer.recognize(image.png());
            telemetry.stage("vision_model", "success", modelStarted);
        } catch (RuntimeException e) {
            telemetry.stage("vision_model", "failure", modelStarted);
            telemetry.completed("vision_model", "fallback", totalStarted);
            log.info("event=formula_region_recognition_unavailable paperId={} page={} errorType={}",
                    paperId, page, e.getClass().getSimpleName());
            PaperFormulaRegionRecord record = save(existing, artifact, page, bbox, regionKey,
                    "", 0, FormulaRegionSource.MULTIMODAL, FormulaRegionStatus.REGION);
            return result(artifact, record, image.dataUrl(),
                    "当前模型未返回可用公式；可手动填写 LaTeX 后确认");
        }

        FormulaRegionStatus status = candidate.latex().isBlank()
                || candidate.confidence() < MIN_MULTIMODAL_CONFIDENCE
                ? FormulaRegionStatus.REGION : FormulaRegionStatus.CANDIDATE;
        PaperFormulaRegionRecord record = save(existing, artifact, page, bbox, regionKey,
                candidate.latex(), candidate.confidence(), FormulaRegionSource.MULTIMODAL, status);
        String message = status == FormulaRegionStatus.CANDIDATE
                ? "请核对 LaTeX，确认后才会用于论文问答"
                : "识别置信度不足，请校正或手动填写 LaTeX 后确认";
        return completed(result(artifact, record, image.dataUrl(), message),
                "vision_model", totalStarted);
    }

    private FormulaRegionRecognition completed(FormulaRegionRecognition recognition,
                                               String path,
                                               long startedAtNanos) {
        telemetry.completed(path, "success", startedAtNanos);
        return recognition;
    }

    @Transactional
    public FormulaRegionRecognition confirm(Long paperId, Long regionId, String latex) {
        String safeLatex = FormulaLatexSanitizer.sanitize(latex);
        if (safeLatex.isBlank()) throw new IllegalArgumentException("LaTeX 不能为空");
        PaperFormulaRegionRecord record = regionMapper.selectById(regionId);
        if (record == null || !paperId.equals(record.getPaperId())) {
            throw new IllegalArgumentException("公式区域不存在");
        }
        PaperLayoutArtifact artifact = artifactService.ensureArtifact(paperId, false);
        if (!artifact.documentHash().equals(record.getDocumentHash())
                || !artifact.parserVersion().equals(record.getParserVersion())) {
            throw new com.research.assistant.service.pdf.layout.StaleLayoutArtifactException();
        }
        record.setLatex(safeLatex);
        record.setConfidence(1d);
        record.setSource(FormulaRegionSource.USER.name());
        record.setStatus(FormulaRegionStatus.CONFIRMED.name());
        record.setUpdatedAt(LocalDateTime.now());
        regionMapper.updateById(record);
        return result(artifact, record, "", "公式已确认，可用于选区问答");
    }

    private Optional<DocumentBlock> bestStructuredFormula(PaperLayoutArtifact artifact,
                                                          int page,
                                                          NormalizedBoundingBox bbox) {
        return artifact.blocks().stream()
                .filter(block -> block.page() == page)
                .filter(block -> block.role() == DocumentBlockRole.FORMULA)
                .filter(block -> block.contentMode() == DocumentBlockContentMode.STRUCTURED)
                .filter(block -> block.latex() != null && !block.latex().isBlank())
                .map(block -> new BlockMatch(block, FormulaRegionGeometry.overlap(bbox, block.bbox())))
                .filter(match -> match.overlap() >= 0.40)
                .max(Comparator.comparingDouble(BlockMatch::overlap))
                .map(BlockMatch::block);
    }

    private Optional<PaperFormulaRegionRecord> bestConfirmedRegion(
            PaperLayoutArtifact artifact,
            int page,
            NormalizedBoundingBox bbox) {
        List<PaperFormulaRegionRecord> confirmed = regionMapper.selectConfirmedOnPage(
                artifact.paperId(), artifact.documentHash(), artifact.parserVersion(), page);
        if (confirmed == null || confirmed.isEmpty()) return Optional.empty();
        return confirmed.stream()
                .filter(record -> record.getLatex() != null && !record.getLatex().isBlank())
                .map(record -> new ConfirmedMatch(
                        record,
                        FormulaRegionGeometry.intersectionOverUnion(
                                bbox, confirmedService.box(record))))
                .filter(match -> match.similarity() >= CONFIRMED_REGION_REUSE_IOU)
                .max(Comparator.comparingDouble(ConfirmedMatch::similarity))
                .map(ConfirmedMatch::record);
    }

    private PaperFormulaRegionRecord save(PaperFormulaRegionRecord existing,
                                          PaperLayoutArtifact artifact,
                                          int page,
                                          NormalizedBoundingBox bbox,
                                          String regionKey,
                                          String latex,
                                          double confidence,
                                          FormulaRegionSource source,
                                          FormulaRegionStatus status) {
        PaperFormulaRegionRecord record = existing == null ? new PaperFormulaRegionRecord() : existing;
        LocalDateTime now = LocalDateTime.now();
        record.setPaperId(artifact.paperId());
        record.setDocumentHash(artifact.documentHash());
        record.setParserVersion(artifact.parserVersion());
        record.setPageNumber(page);
        record.setRegionKey(regionKey);
        record.setBoxX(bbox.x());
        record.setBoxY(bbox.y());
        record.setBoxWidth(bbox.width());
        record.setBoxHeight(bbox.height());
        record.setLatex(latex == null ? "" : latex);
        record.setConfidence(Math.max(0, Math.min(1, confidence)));
        record.setSource(source.name());
        record.setStatus(status.name());
        record.setUpdatedAt(now);
        if (record.getId() != null) {
            regionMapper.updateById(record);
            return record;
        }
        record.setCreatedAt(now);
        try {
            regionMapper.insert(record);
            return record;
        } catch (DuplicateKeyException e) {
            PaperFormulaRegionRecord concurrent = regionMapper.selectCurrent(
                    artifact.paperId(), artifact.documentHash(), artifact.parserVersion(), page, regionKey);
            if (concurrent == null) throw e;
            record.setId(concurrent.getId());
            regionMapper.updateById(record);
            return record;
        }
    }

    private FormulaRegionRecognition result(PaperLayoutArtifact artifact,
                                            PaperFormulaRegionRecord record,
                                            String previewDataUrl,
                                            String message) {
        FormulaRegionStatus status = FormulaRegionStatus.valueOf(record.getStatus());
        SelectionAnchor anchor = status == FormulaRegionStatus.CONFIRMED
                ? confirmedService.anchor(artifact, record) : null;
        return new FormulaRegionRecognition(
                record.getId(), record.getPaperId(), record.getPageNumber(), confirmedService.box(record),
                record.getLatex(), record.getConfidence() == null ? 0 : record.getConfidence(),
                FormulaRegionSource.valueOf(record.getSource()), status, previewDataUrl, message,
                anchor, status == FormulaRegionStatus.CONFIRMED);
    }

    private Paper requirePaper(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) throw new IllegalArgumentException("论文不存在");
        return paper;
    }

    private record BlockMatch(DocumentBlock block, double overlap) { }

    private record ConfirmedMatch(PaperFormulaRegionRecord record, double similarity) { }
}
