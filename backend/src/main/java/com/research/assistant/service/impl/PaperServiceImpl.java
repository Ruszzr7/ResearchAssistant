package com.research.assistant.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.Tag;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.TagMapper;
import com.research.assistant.service.AgentOrchestrator;
import com.research.assistant.service.AsyncTaskService;
import com.research.assistant.service.PaperService;
import com.research.assistant.service.PdfExtractor;
import com.research.assistant.service.metadata.MetadataNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
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
    private final PdfExtractor pdfExtractor;
    private final AgentOrchestrator agentOrchestrator;
    private final AsyncTaskService asyncTaskService;

    @Value("${app.storage.pdf-dir:./data/papers}")
    private String pdfStorageDir;

    public PaperServiceImpl(PaperMapper paperMapper, TagMapper tagMapper, PdfExtractor pdfExtractor,
                            AgentOrchestrator agentOrchestrator, AsyncTaskService asyncTaskService) {
        this.paperMapper = paperMapper;
        this.tagMapper = tagMapper;
        this.pdfExtractor = pdfExtractor;
        this.agentOrchestrator = agentOrchestrator;
        this.asyncTaskService = asyncTaskService;
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
        normalizeAuthors(paper);
        paperMapper.insert(paper);
        // 入库后自动触发 AI 分析（异步）
        if (paper.getPdfPath() != null && !paper.getPdfPath().isBlank()) {
            triggerAsyncProcessing(paper.getId());
        }
        return getById(paper.getId());   // 回查以填充 tags
    }

    @Override
    @Transactional
    public Paper update(Paper paper) {
        normalizeAuthors(paper);
        paperMapper.updateById(paper);
        return getById(paper.getId());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        // MyBatis Plus 默认不处理关联表；paper_tag 由数据库外键 CASCADE 自动清理
        paperMapper.deleteById(id);
    }

    // ========== PDF 文件管理 ==========

    @Override
    public String uploadPdf(Long paperId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("文件为空");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".pdf")) {
            throw new RuntimeException("仅支持 PDF 文件");
        }
        // 确保存储目录存在（相对路径 → 基于 user.dir 解析为绝对路径）
        File dir = new File(pdfStorageDir);
        if (!dir.isAbsolute()) {
            dir = new File(System.getProperty("user.dir"), pdfStorageDir);
        }
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成唯一文件名: {paperId}_{timestamp}.pdf
        String storedName = paperId + "_" + System.currentTimeMillis() + ".pdf";
        File dest = new File(dir, storedName);
        try {
            file.transferTo(dest);
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败", e);
        }
        // 更新 paper.pdfPath + 提取文本
        Paper paper = new Paper();
        paper.setId(paperId);
        paper.setPdfPath(storedName);
        // 异步提取 PDF 文本（暂存于 aiSummary，阶段三由 LLM 结构化）
        String extracted = pdfExtractor.extract(storedName);
        if (!extracted.isEmpty()) {
            paper.setAiSummary(extracted);
        }
        // 若年份为空或默认值，尝试从 PDF 文本中自动提取真实年份
        Integer existingYear = paperMapper.selectById(paperId).getYear();
        if (existingYear == null || existingYear == 2025) {
            Integer parsedYear = extractYearFromText(extracted);
            if (parsedYear != null) {
                paper.setYear(parsedYear);
            }
        }
        paperMapper.updateById(paper);
        return storedName;
    }

    private static final java.util.regex.Pattern YEAR_PATTERN =
            java.util.regex.Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    /**
     * 从 PDF 文本中提取最可能的出版年份。
     * 策略：优先扫描文本前 1500 个字符内的年份（通常包含期刊/会议页眉），
     * 若不存在则在整个文本中选择出现次数最多的年份。
     */
    private Integer extractYearFromText(String text) {
        if (text == null || text.isBlank()) return null;
        // 先尝试前 1500 字符
        String header = text.length() > 1500 ? text.substring(0, 1500) : text;
        java.util.Map<Integer, Integer> headerFreq = collectYearFreq(header);
        if (!headerFreq.isEmpty()) {
            return headerFreq.entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .map(java.util.Map.Entry::getKey)
                    .orElse(null);
        }
        // 否则全文中找出现最多的
        java.util.Map<Integer, Integer> freq = collectYearFreq(text);
        if (freq.isEmpty()) return null;
        return freq.entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(null);
    }

    private java.util.Map<Integer, Integer> collectYearFreq(String text) {
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        java.util.regex.Matcher matcher = YEAR_PATTERN.matcher(text);
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            freq.put(year, freq.getOrDefault(year, 0) + 1);
        }
        return freq;
    }

    @Override
    @Transactional
    public Paper uploadPdfAndCreate(MultipartFile file, Paper paper) {
        normalizeAuthors(paper);
        paperMapper.insert(paper);
        if (file != null && !file.isEmpty()) {
            uploadPdf(paper.getId(), file);
        }
        // 仅上传了 PDF 才自动触发 AI 分析，避免无 PDF 时异步任务直接失败
        if (paper.getPdfPath() != null && !paper.getPdfPath().isBlank()) {
            triggerAsyncProcessing(paper.getId());
        }
        return getById(paper.getId());
    }

    @Override
    @Transactional
    public void deleteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        paperMapper.deleteBatchIds(ids);
    }

    @Override
    @Transactional
    public void moveBatch(List<Long> ids, Long folderId) {
        if (ids == null || ids.isEmpty()) return;
        paperMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Paper>()
                        .in("id", ids)
                        .set("folder_id", folderId));
    }

    /** 异步触发 AI 分析，不阻塞入库响应 */
    private void triggerAsyncProcessing(Long paperId) {
        asyncTaskService.processPaperAsync(paperId);
    }

    /** 将 authors 字段统一规范化为 JSON 数组字符串，保证数据库格式一致 */
    private void normalizeAuthors(Paper paper) {
        String normalized = MetadataNormalizer.normalizeAuthors(paper.getAuthors());
        paper.setAuthors(normalized);
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
