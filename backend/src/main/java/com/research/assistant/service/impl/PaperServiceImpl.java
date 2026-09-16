package com.research.assistant.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.research.assistant.common.DuplicatePaperException;
import com.research.assistant.common.PaperFileValidationException;
import com.research.assistant.common.PaperMetadataValidationException;
import com.research.assistant.constant.AcquisitionMethod;
import com.research.assistant.constant.ReadingStatus;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.Tag;
import com.research.assistant.mapper.FolderMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.TagMapper;
import com.research.assistant.service.PaperService;
import com.research.assistant.service.PaperAssetLifecycleService;
import com.research.assistant.service.PdfExtractor;
import com.research.assistant.service.metadata.MetadataNormalizer;
import com.research.assistant.service.metadata.PaperDocumentReviewer;
import com.research.assistant.service.metadata.PdfMetadataHeuristics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 论文业务实现。
 * <p>
 * listWithFilters 存在 N+1 查询问题（每篇论文单独查标签），
 * 待数据量增大后优化为批量查询。
 */
@Service
public class PaperServiceImpl implements PaperService {

    private static final Logger log = LoggerFactory.getLogger(PaperServiceImpl.class);

    private final PaperMapper paperMapper;
    private final TagMapper tagMapper;
    private final FolderMapper folderMapper;
    private final PdfExtractor pdfExtractor;
    private final PaperAssetLifecycleService paperAssetLifecycleService;
    private final PaperDocumentReviewer paperDocumentReviewer;

    public PaperServiceImpl(PaperMapper paperMapper, TagMapper tagMapper, FolderMapper folderMapper,
                            PdfExtractor pdfExtractor, PaperAssetLifecycleService paperAssetLifecycleService,
                            PaperDocumentReviewer paperDocumentReviewer) {
        this.paperMapper = paperMapper;
        this.tagMapper = tagMapper;
        this.folderMapper = folderMapper;
        this.pdfExtractor = pdfExtractor;
        this.paperAssetLifecycleService = paperAssetLifecycleService;
        this.paperDocumentReviewer = paperDocumentReviewer;
    }

    @Override
    public IPage<Paper> listWithFilters(List<Long> folderIds, boolean uncategorized, Long tagId,
                                        String status, String keyword,
                                        String sortBy, String sortDir, int page, int size) {
        Page<Paper> pageParam = new Page<>(page, size);
        // 防止 SQL 注入：排序方向仅允许 ASC/DESC
        String safeSortDir = "ASC".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        IPage<Paper> result = paperMapper.selectPageWithFilters(pageParam, folderIds, uncategorized, tagId, status, keyword, sortBy, safeSortDir);

        // 批量加载标签，避免 N+1
        List<Paper> records = result.getRecords();
        if (!records.isEmpty()) {
            List<Long> paperIds = records.stream().map(Paper::getId).toList();
            List<TagMapper.TagWithPaperId> tags = tagMapper.selectByPaperIds(paperIds);
            Map<Long, List<Tag>> tagMap = tags.stream()
                    .collect(Collectors.groupingBy(
                            TagMapper.TagWithPaperId::getPaperId,
                            java.util.LinkedHashMap::new,
                            Collectors.mapping(
                                    t -> {
                                        Tag tag = new Tag();
                                        tag.setId(t.getId());
                                        tag.setName(t.getName());
                                        return tag;
                                    }, Collectors.toList())));
            for (Paper paper : records) {
                paper.setTags(tagMap.getOrDefault(paper.getId(), Collections.emptyList()));
            }
        }
        return result;
    }

    @Override
    public Paper getById(Long id) {
        Paper paper = paperMapper.selectById(id);
        if (paper != null) {
            paper.setTags(tagMapper.selectByPaperId(paper.getId()));
        }
        return paper;
    }

    @Override
    @Transactional
    public Paper create(Paper paper) {
        normalizePaperWrite(paper, true);
        if (paperMapper.insert(paper) != 1) {
            throw new IllegalStateException("论文记录写入失败");
        }
        return getById(paper.getId());   // 回查以填充 tags
    }

    @Override
    @Transactional
    public Paper update(Paper paper) {
        Paper existing = paper == null || paper.getId() == null ? null : paperMapper.selectById(paper.getId());
        if (existing == null) {
            throw new IllegalArgumentException("论文不存在");
        }
        normalizePaperWrite(paper, false);
        preserveServerManagedFields(paper, existing);
        if (paperMapper.updateById(paper) != 1) {
            throw new IllegalStateException("论文记录更新失败");
        }
        return getById(paper.getId());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Paper paper = paperMapper.selectById(id);
        if (paper == null) return;
        // Paper-owned database data (including annotations) is removed by foreign-key cascades.
        if (paperMapper.deleteById(id) != 1) {
            throw new IllegalStateException("论文记录删除失败");
        }
        paperAssetLifecycleService.deleteAfterCommit(List.of(paper));
    }

    // ========== PDF 文件管理 ==========

    @Override
    @Transactional
    public String uploadPdf(Long paperId, MultipartFile file) {
        Paper existing = paperMapper.selectById(paperId);
        if (existing == null) throw new IllegalArgumentException("论文不存在");
        PaperAssetLifecycleService.StoredPdf stored = paperAssetLifecycleService.storeUploadedPdf(file);
        try {
            ensurePaperDocument(stored);
            Paper paper = new Paper();
            String oldPdfPath = existing.getPdfPath();
            paper.setId(paperId);
            populatePdfFields(paper, stored, existing.getYear());
            paper.setProcessingStatus("PENDING");
            if (paperMapper.updateById(paper) != 1) {
                throw new IllegalStateException("论文记录更新失败");
            }
            if (oldPdfPath != null && !oldPdfPath.isBlank() && !oldPdfPath.equals(stored.storedName())) {
                Paper old = new Paper();
                old.setId(paperId);
                old.setPdfPath(oldPdfPath);
                paperAssetLifecycleService.deleteAfterCommit(List.of(old));
            }
            return stored.storedName();
        } catch (RuntimeException exception) {
            paperAssetLifecycleService.deleteImmediately(stored.storedName());
            throw exception;
        }
    }

    private void populatePdfFields(Paper paper, PaperAssetLifecycleService.StoredPdf stored,
                                   Integer existingYear) {
        paper.setPdfPath(stored.storedName());
        paper.setPageCount(stored.pageCount());
        // 异步提取 PDF 文本（暂存于 aiSummary，阶段三由 LLM 结构化）
        String extracted = pdfExtractor.extract(stored.storedName());
        // aiSummary 当前保存的是 PDF 派生文本，替换文件时不能沿用旧文档内容。
        paper.setAiSummary(extracted.isEmpty() ? null : extracted);
        // 若年份为空或历史默认值，使用出版信息行提取真实年份；识别不到时保持为空，
        // 不再把授权下载时间等首页年份误认为出版年份。
        if (existingYear == null || existingYear == 2025) {
            Integer parsedYear = new PdfMetadataHeuristics().extract(extracted).year();
            if (parsedYear != null) {
                paper.setYear(parsedYear);
            }
        }
    }

    @Override
    @Transactional
    public Paper uploadPdfAndCreate(MultipartFile file, Paper paper) {
        return uploadPdfAndCreate(file, paper, false);
    }

    @Override
    @Transactional
    public Paper uploadPdfAndCreate(MultipartFile file, Paper paper, boolean overwrite) {
        normalizePaperWrite(paper, true);
        if (file == null || file.isEmpty()) {
            throw new PaperFileValidationException("上传文件为空");
        }

        // 先完成文件校验和唯一存储，再处理重复判断。这样伪装、损坏或加密文件
        // 不会因为 DOI 重复而被错误地当成合法上传，也不会进入数据库。
        PaperAssetLifecycleService.StoredPdf stored = paperAssetLifecycleService.storeUploadedPdf(file);
        try {
            ensurePaperDocument(stored);
            Paper duplicate = findDuplicatePaper(file, paper);
            if (duplicate != null) {
                if (!overwrite) {
                    throw new DuplicatePaperException(duplicate.getId(), duplicate.getTitle());
                }
                paper.setId(duplicate.getId());
                // 阅读状态、置顶、阅读进度和已有分析属于服务端状态，覆盖元数据时保留原值。
                preserveServerManagedFields(paper, duplicate);
                populatePdfFields(paper, stored, duplicate.getYear());
                paper.setProcessingStatus("PENDING");
                if (paperMapper.updateById(paper) != 1) {
                    throw new IllegalStateException("论文记录更新失败");
                }
                if (duplicate.getPdfPath() != null
                        && !duplicate.getPdfPath().equals(stored.storedName())) {
                    Paper replaced = new Paper();
                    replaced.setId(duplicate.getId());
                    replaced.setPdfPath(duplicate.getPdfPath());
                    paperAssetLifecycleService.deleteAfterCommit(List.of(replaced));
                }
                return getById(duplicate.getId());
            }

            populatePdfFields(paper, stored, paper.getYear());
            if (paperMapper.insert(paper) != 1) {
                throw new IllegalStateException("论文记录写入失败");
            }
            return getById(paper.getId());
        } catch (RuntimeException exception) {
            paperAssetLifecycleService.deleteImmediately(stored.storedName());
            throw exception;
        }
    }

    private void ensurePaperDocument(PaperAssetLifecycleService.StoredPdf stored) {
        PdfExtractor.MetadataTextExtraction texts = pdfExtractor
                .extractMetadataTextExtraction(stored.storedName(), 5);
        PaperDocumentReviewer.Review review = paperDocumentReviewer
                .review(texts.identityText(), texts.metadataText());
        if (review.status() == PaperDocumentReviewer.Status.NOT_PAPER) {
            // Content classification is advisory. A valid user-supplied PDF with explicit
            // metadata must remain importable even when an old, scanned or unusual layout
            // provides too little text for the lightweight reviewer.
            log.info("paper_document_review_advisory status={} storedName={}",
                    review.status(), stored.storedName());
        }
    }

    private Paper findDuplicatePaper(MultipartFile file, Paper paper) {
        if (paper.getDoi() != null && !paper.getDoi().isBlank()) {
            Paper duplicate = paperMapper.selectByDoi(paper.getDoi().trim());
            if (duplicate != null) return duplicate;
        }
        if (file == null || file.isEmpty()) return null;
        String uploadedHash = sha256(file);
        if (uploadedHash == null) return null;
        for (Paper existing : paperMapper.selectList(null)) {
            Path stored = paperAssetLifecycleService.resolveStoredPdf(existing.getPdfPath());
            if (stored != null && uploadedHash.equals(sha256(stored))) return existing;
        }
        return null;
    }

    private String sha256(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            return sha256(input);
        } catch (Exception e) {
            log.warn("计算上传 PDF 指纹失败: {}", e.getMessage());
            return null;
        }
    }

    private String sha256(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            return sha256(input);
        } catch (Exception e) {
            return null;
        }
    }

    private String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) hex.append(String.format("%02x", value));
        return hex.toString();
    }

    @Override
    @Transactional
    public void deleteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        List<Paper> papers = paperMapper.selectBatchIds(ids);
        if (papers == null || papers.isEmpty()) return;
        if (paperMapper.deleteBatchIds(ids) != papers.size()) {
            throw new IllegalStateException("论文记录删除失败");
        }
        paperAssetLifecycleService.deleteAfterCommit(papers);
    }

    @Override
    @Transactional
    public void moveBatch(List<Long> ids, Long folderId) {
        if (ids == null || ids.isEmpty()) return;
        validateFolder(folderId);
        paperMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Paper>()
                        .in("id", ids)
                        .set("folder_id", folderId));
    }

    /** 将 authors 字段统一规范化为 JSON 数组字符串，保证数据库格式一致 */
    private void normalizeAuthors(Paper paper) {
        String normalized = MetadataNormalizer.normalizeAuthors(paper.getAuthors());
        paper.setAuthors(normalized);
    }

    private void normalizePaperWrite(Paper paper, boolean creating) {
        if (paper == null) throw new IllegalArgumentException("论文不能为空");
        if (paper.getTitle() == null || paper.getTitle().isBlank()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        normalizeAuthors(paper);
        validateMetadataLengths(paper);
        if (paper.getReadingStatus() == null || paper.getReadingStatus().isBlank()) {
            if (creating) paper.setReadingStatus(ReadingStatus.UNREAD);
        } else {
            String status = paper.getReadingStatus().trim().toUpperCase();
            if (!ReadingStatus.UNREAD.equals(status)
                    && !ReadingStatus.READING.equals(status)
                    && !ReadingStatus.READ.equals(status)) {
                throw new IllegalArgumentException("阅读状态必须是 UNREAD、READING 或 READ");
            }
            paper.setReadingStatus(status);
        }
        if (paper.getAcquisitionMethod() == null || paper.getAcquisitionMethod().isBlank()) {
            if (creating) paper.setAcquisitionMethod(AcquisitionMethod.MANUAL_UPLOAD);
        } else {
            String method = paper.getAcquisitionMethod().trim().toUpperCase();
            if (!AcquisitionMethod.OA.equals(method)
                    && !AcquisitionMethod.MANUAL_UPLOAD.equals(method)
                    && !AcquisitionMethod.BROWSER_DOWNLOAD.equals(method)) {
                throw new IllegalArgumentException("获取方式必须是 OA、MANUAL_UPLOAD 或 BROWSER_DOWNLOAD");
            }
            paper.setAcquisitionMethod(method);
        }
        validateFolder(paper.getFolderId());
    }

    private void validateMetadataLengths(Paper paper) {
        requireMax("title", "标题", paper.getTitle(), 500);
        requireMax("authors", "作者信息", paper.getAuthors(), 20_000);
        requireMax("source", "来源", paper.getSource(), 500);
        requireMax("doi", "DOI", paper.getDoi(), 200);
        requireMax("arxivId", "arXiv ID", paper.getArxivId(), 100);
        requireMax("semanticScholarId", "Semantic Scholar ID", paper.getSemanticScholarId(), 200);
        requireMax("sourceUrl", "来源链接", paper.getSourceUrl(), 2_000);
        requireMax("abstractText", "摘要", paper.getAbstractText(), 50_000);
        requireMax("keywords", "关键词", paper.getKeywords(), 4_000);
    }

    private void requireMax(String field, String label, String value, int maximum) {
        if (value != null && value.length() > maximum) {
            throw new PaperMetadataValidationException(field, label + "长度不能超过 " + maximum);
        }
    }

    private void preserveServerManagedFields(Paper update, Paper existing) {
        update.setPdfPath(existing.getPdfPath());
        update.setReadingStatus(existing.getReadingStatus());
        update.setPinned(existing.getPinned());
        update.setPageCount(existing.getPageCount());
        update.setCurrentPage(existing.getCurrentPage());
        update.setReadSeconds(existing.getReadSeconds());
        update.setLastReadAt(existing.getLastReadAt());
        update.setAiSummary(existing.getAiSummary());
        update.setProcessingStatus(existing.getProcessingStatus());
    }

    private void validateFolder(Long folderId) {
        if (folderId != null && (folderId <= 0 || folderMapper.selectById(folderId) == null)) {
            throw new IllegalArgumentException("文件夹不存在");
        }
    }

    @Override
    @Transactional
    public Paper togglePin(Long id) {
        Paper paper = paperMapper.selectById(id);
        if (paper == null) {
            throw new RuntimeException("论文不存在");
        }
        boolean newPinned = !Boolean.TRUE.equals(paper.getPinned());
        paperMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Paper>()
                        .eq("id", id)
                        .set("pinned", newPinned));
        paper.setPinned(newPinned);
        return paper;
    }
}
