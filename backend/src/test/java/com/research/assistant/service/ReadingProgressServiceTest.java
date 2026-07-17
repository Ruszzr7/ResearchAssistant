package com.research.assistant.service;

import com.research.assistant.constant.ReadingStatus;
import com.research.assistant.dto.ReadingProgressDto;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ReadingProgressService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ReadingProgressServiceTest {

    @Mock
    private PaperMapper paperMapper;
    @Mock
    private PdfExtractor pdfExtractor;

    @Test
    void shouldLazyLoadPageCountWhenMissing() {
        ReadingProgressService service = new ReadingProgressService(paperMapper, pdfExtractor);
        Paper paper = new Paper();
        paper.setId(1L);
        paper.setPdfPath("1.pdf");
        paper.setCurrentPage(0);
        paper.setReadSeconds(0);
        when(paperMapper.selectById(1L)).thenReturn(paper);
        when(pdfExtractor.countPages("1.pdf")).thenReturn(10);

        ReadingProgressDto dto = service.getProgress(1L);

        assertThat(dto.getPageCount()).isEqualTo(10);
        assertThat(dto.getProgressPercent()).isEqualTo(0);
        verify(paperMapper).updateById(any(Paper.class));
    }

    @Test
    void shouldComputeProgressPercent() {
        ReadingProgressService service = new ReadingProgressService(paperMapper, pdfExtractor);
        Paper paper = new Paper();
        paper.setId(1L);
        paper.setPageCount(20);
        paper.setCurrentPage(5);
        paper.setReadSeconds(120);
        paper.setReadingStatus(ReadingStatus.READING);
        when(paperMapper.selectById(1L)).thenReturn(paper);

        ReadingProgressDto dto = service.getProgress(1L);

        assertThat(dto.getProgressPercent()).isEqualTo(25);
        assertThat(dto.getReadSeconds()).isEqualTo(120);
    }

    @Test
    void shouldPersistLastPageWithoutChangingReadingStatus() {
        ReadingProgressService service = new ReadingProgressService(paperMapper, pdfExtractor);
        Paper paper = new Paper();
        paper.setId(1L);
        paper.setPageCount(10);
        paper.setReadingStatus(ReadingStatus.UNREAD);
        when(paperMapper.selectById(1L)).thenReturn(paper);

        service.updateProgress(1L, 3);

        verify(paperMapper).updateById(ArgumentMatchers.<Paper>argThat(p ->
                p.getId().equals(1L)
                        && p.getCurrentPage() == 3
                        && p.getReadingStatus() == null
                        && p.getLastReadAt() != null));
    }

    @Test
    void shouldNotMarkReadWhenReachingLastPage() {
        ReadingProgressService service = new ReadingProgressService(paperMapper, pdfExtractor);
        Paper paper = new Paper();
        paper.setId(1L);
        paper.setPageCount(10);
        paper.setReadingStatus(ReadingStatus.READING);
        when(paperMapper.selectById(1L)).thenReturn(paper);

        service.updateProgress(1L, 10);

        verify(paperMapper).updateById(ArgumentMatchers.<Paper>argThat(p ->
                p.getReadingStatus() == null
                        && p.getCurrentPage() == 10));
    }

    @Test
    void shouldRejectOutOfBoundsPage() {
        ReadingProgressService service = new ReadingProgressService(paperMapper, pdfExtractor);
        Paper paper = new Paper();
        paper.setId(1L);
        paper.setPageCount(10);
        when(paperMapper.selectById(1L)).thenReturn(paper);

        assertThatThrownBy(() -> service.updateProgress(1L, 11))
                .isInstanceOf(IllegalArgumentException.class);
        verify(paperMapper, never()).updateById(any(Paper.class));
    }

    @Test
    void shouldAccumulateReadSeconds() {
        ReadingProgressService service = new ReadingProgressService(paperMapper, pdfExtractor);
        Paper paper = new Paper();
        paper.setId(1L);
        paper.setReadSeconds(60);
        when(paperMapper.selectById(1L)).thenReturn(paper);

        service.addReadSeconds(1L, 30);

        verify(paperMapper).updateById(ArgumentMatchers.<Paper>argThat(p ->
                p.getReadSeconds() == 90 && p.getLastReadAt() != null));
    }
}
