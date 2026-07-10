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
 * 阅读进度服务 —— 管理论文的当前页、阅读时长、进度百分比与阅读状态。
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
     * 更新当前页，计算进度百分比，并按规则推进阅读状态。
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
        update.setReadingStatus(nextReadingStatus(paper.getReadingStatus(), currentPage, pageCount));
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

    private String nextReadingStatus(String currentStatus, int currentPage, int pageCount) {
        if (currentPage >= pageCount) {
            return ReadingStatus.READ;
        }
        if (ReadingStatus.READ.equals(currentStatus)) {
            // 已读论文重新阅读时回到 READING
            return ReadingStatus.READING;
        }
        if (ReadingStatus.UNREAD.equals(currentStatus)) {
            return ReadingStatus.READING;
        }
        // READING 保持，其他未知状态保持原样
        return currentStatus != null ? currentStatus : ReadingStatus.READING;
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
