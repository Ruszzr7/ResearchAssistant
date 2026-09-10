package com.research.assistant.service;

import com.research.assistant.constant.ReadingStatus;
import com.research.assistant.dto.ReadingProgressDto;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 阅读位置服务 —— 管理论文的最后页码与阅读时长。
 */
@Service
public class ReadingProgressService {

    private static final Logger log = LoggerFactory.getLogger(ReadingProgressService.class);

    private final PaperMapper paperMapper;
    private final PdfExtractor pdfExtractor;

    public ReadingProgressService(PaperMapper paperMapper, PdfExtractor pdfExtractor) {
        this.paperMapper = paperMapper;
        this.pdfExtractor = pdfExtractor;
    }

    /**
     * 查询阅读进度；若 PDF 总页数未初始化，则懒加载并回写。
     */
    public ReadingProgressDto getProgress(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new RuntimeException("论文不存在: " + paperId);
        }

        Integer pageCount = paper.getPageCount();
        if (pageCount == null || pageCount <= 0) {
            pageCount = loadPageCount(paper);
        }

        return toDto(paper, pageCount);
    }

    /**
     * 更新最后阅读页。首次成功写入阅读进度时，未读论文进入正读；浏览到末页不会自动改成“已读”。
     */
    @Transactional
    public void updateProgress(Long paperId, int currentPage) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new RuntimeException("论文不存在: " + paperId);
        }

        Integer pageCount = paper.getPageCount();
        if (pageCount == null || pageCount <= 0) {
            pageCount = loadPageCount(paper);
        }

        if (currentPage < 1 || currentPage > pageCount) {
            throw new IllegalArgumentException(
                    "当前页必须在 1 到 " + pageCount + " 之间");
        }

        Paper update = new Paper();
        update.setId(paperId);
        update.setCurrentPage(currentPage);
        update.setLastReadAt(LocalDateTime.now());
        if (ReadingStatus.UNREAD.equals(paper.getReadingStatus())) {
            update.setReadingStatus(ReadingStatus.READING);
        }
        paperMapper.updateById(update);
    }

    /**
     * 用户显式切换阅读状态。状态接口与论文元数据更新分离，避免客户端顺带覆盖服务端管理字段。
     */
    @Transactional
    public void updateStatus(Long paperId, String status) {
        if (!ReadingStatus.UNREAD.equals(status)
                && !ReadingStatus.READING.equals(status)
                && !ReadingStatus.READ.equals(status)) {
            throw new IllegalArgumentException("阅读状态必须是 UNREAD、READING 或 READ");
        }
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new RuntimeException("论文不存在: " + paperId);
        }
        Paper update = new Paper();
        update.setId(paperId);
        update.setReadingStatus(status);
        paperMapper.updateById(update);
    }

    /**
     * 累加阅读时长。
     */
    @Transactional
    public void addReadSeconds(Long paperId, int seconds) {
        if (seconds <= 0) {
            return;
        }
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new RuntimeException("论文不存在: " + paperId);
        }

        Paper update = new Paper();
        update.setId(paperId);
        update.setReadSeconds((paper.getReadSeconds() != null ? paper.getReadSeconds() : 0) + seconds);
        update.setLastReadAt(LocalDateTime.now());
        paperMapper.updateById(update);
    }

    private Integer loadPageCount(Paper paper) {
        if (paper.getPdfPath() == null || paper.getPdfPath().isBlank()) {
            return 0;
        }
        int count = pdfExtractor.countPages(paper.getPdfPath());
        if (count > 0) {
            Paper update = new Paper();
            update.setId(paper.getId());
            update.setPageCount(count);
            paperMapper.updateById(update);
            paper.setPageCount(count);
        }
        return Math.max(count, 0);
    }

    private ReadingProgressDto toDto(Paper paper, Integer pageCount) {
        ReadingProgressDto dto = new ReadingProgressDto();
        dto.setPaperId(paper.getId());
        dto.setPageCount(pageCount);
        dto.setCurrentPage(paper.getCurrentPage() != null ? paper.getCurrentPage() : 0);
        dto.setReadSeconds(paper.getReadSeconds() != null ? paper.getReadSeconds() : 0);
        dto.setProgressPercent(pageCount > 0
                ? Math.min(100, paper.getCurrentPage() * 100 / pageCount)
                : 0);
        dto.setReadingStatus(paper.getReadingStatus());
        return dto;
    }
}
