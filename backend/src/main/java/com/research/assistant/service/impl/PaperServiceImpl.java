package com.research.assistant.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.TagMapper;
import com.research.assistant.service.PaperService;
import com.research.assistant.service.PdfExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 论文业务实现。
 * <p>
 * listWithFilters 存在 N+1 查询问题（每篇论文单独查标签），
 * 待数据量增大后优化为批量查询。
 */
@Service
public class PaperServiceImpl implements PaperService {

    private final PaperMapper paperMapper;
    private final TagMapper tagMapper;
    private final PdfExtractor pdfExtractor;

    @Value("${app.storage.pdf-dir:./data/papers}")
    private String pdfStorageDir;

    public PaperServiceImpl(PaperMapper paperMapper, TagMapper tagMapper, PdfExtractor pdfExtractor) {
        this.paperMapper = paperMapper;
        this.tagMapper = tagMapper;
        this.pdfExtractor = pdfExtractor;
    }

    @Override
    public IPage<Paper> listWithFilters(List<Long> folderIds, boolean uncategorized, Long tagId,
                                        String status, String keyword,
                                        String sortBy, String sortDir, int page, int size) {
        Page<Paper> pageParam = new Page<>(page, size);
        IPage<Paper> result = paperMapper.selectPageWithFilters(pageParam, folderIds, uncategorized, tagId, status, keyword, sortBy, sortDir);

        // 逐篇加载标签（TODO: 改为批量 IN 查询）
        for (Paper paper : result.getRecords()) {
            paper.setTags(tagMapper.selectByPaperId(paper.getId()));
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
        paperMapper.insert(paper);
        return getById(paper.getId());   // 回查以填充 tags
    }

    @Override
    @Transactional
    public Paper update(Paper paper) {
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
        paperMapper.updateById(paper);
        return storedName;
    }

    @Override
    @Transactional
    public Paper uploadPdfAndCreate(MultipartFile file, Paper paper) {
        // 先创建论文记录（获得 ID）
        paperMapper.insert(paper);
        // 再上传文件（用刚才拿到的 ID）
        if (file != null && !file.isEmpty()) {
            uploadPdf(paper.getId(), file);
        }
        // 回查以填充 tags 和更新后的 pdfPath
        return getById(paper.getId());
    }
}
