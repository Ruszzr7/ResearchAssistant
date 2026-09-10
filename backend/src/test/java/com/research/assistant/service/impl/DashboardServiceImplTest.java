package com.research.assistant.service.impl;

import com.research.assistant.dto.DashboardDto;
import com.research.assistant.entity.AsyncTaskRecord;
import com.research.assistant.entity.Folder;
import com.research.assistant.mapper.AsyncTaskRecordMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.FolderService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link DashboardServiceImpl} 单元测试。
 */
class DashboardServiceImplTest {

    private final PaperMapper paperMapper = mock(PaperMapper.class);
    private final FolderService folderService = mock(FolderService.class);
    private final AsyncTaskRecordMapper taskMapper = mock(AsyncTaskRecordMapper.class);

    private final DashboardServiceImpl service = new DashboardServiceImpl(
            paperMapper, folderService, taskMapper);

    @Test
    void aggregateReturnsPaperStats() {
        when(paperMapper.selectCount(null)).thenReturn(10L);
        when(paperMapper.countByReadingStatus("UNREAD")).thenReturn(3L);
        when(paperMapper.countByReadingStatus("READING")).thenReturn(2L);
        when(paperMapper.countByReadingStatus("READ")).thenReturn(5L);
        when(paperMapper.countUncategorized()).thenReturn(4L);
        when(paperMapper.countPinned()).thenReturn(1L);
        when(paperMapper.countCreatedSince(any())).thenReturn(2L);
        stubEmptyOther();

        DashboardDto dto = service.aggregate();

        assertThat(dto.getPaperStats().getTotal()).isEqualTo(10L);
        assertThat(dto.getPaperStats().getUnread()).isEqualTo(3L);
        assertThat(dto.getPaperStats().getUncategorized()).isEqualTo(4L);
        assertThat(dto.getPaperStats().getThisMonth()).isEqualTo(2L);
    }

    @Test
    void folderBacklogIsSortedAndLimited() {
        Folder root = new Folder();
        root.setId(1L);
        root.setName("A");
        root.setPaperCount(5);
        Folder child = new Folder();
        child.setId(2L);
        child.setName("B");
        child.setPaperCount(8);
        root.setChildren(List.of(child));

        stubEmptyCounts();
        when(folderService.getTree()).thenReturn(List.of(root));

        DashboardDto dto = service.aggregate();

        assertThat(dto.getFolderBacklog()).hasSize(2);
        assertThat(dto.getFolderBacklog().get(0).getName()).isEqualTo("B");
        assertThat(dto.getFolderBacklog().get(0).getPaperCount()).isEqualTo(8);
    }

    @Test
    void taskStatsIncludeStatusCountsAndRecentTasks() {
        AsyncTaskRecord task = new AsyncTaskRecord();
        task.setId(1L);
        task.setTaskId("t1");
        task.setTitle("Analyze");
        task.setStatus("COMPLETED");
        task.setStageText("Done");
        task.setCreatedAt(LocalDateTime.now());

        stubEmptyCountsExceptTasks();
        when(taskMapper.countByStatus("PENDING")).thenReturn(2L);
        when(taskMapper.countByStatus("PROCESSING")).thenReturn(1L);
        when(taskMapper.countByStatus("COMPLETED")).thenReturn(4L);
        when(taskMapper.countByStatus("FAILED")).thenReturn(1L);
        when(taskMapper.countHistoricalFailures()).thenReturn(0L);
        when(taskMapper.countByStatus("CANCELLED")).thenReturn(0L);
        when(taskMapper.selectRecent(5)).thenReturn(List.of(task));

        DashboardDto dto = service.aggregate();

        assertThat(dto.getTaskStats().getTotal()).isEqualTo(8L);
        assertThat(dto.getTaskStats().getCompleted()).isEqualTo(4L);
        assertThat(dto.getTaskStats().getRecent()).hasSize(1);
        assertThat(dto.getTaskStats().getRecent().get(0).getTitle()).isEqualTo("Analyze");
    }

    @Test
    void separatesLegacyFailedTasksFromCurrentFailures() {
        stubEmptyCountsExceptTasks();
        when(taskMapper.countByStatus("FAILED")).thenReturn(3L);
        when(taskMapper.countHistoricalFailures()).thenReturn(2L);

        DashboardDto dto = service.aggregate();

        assertThat(dto.getTaskStats().getFailed()).isEqualTo(1L);
        assertThat(dto.getTaskStats().getHistoricalFailed()).isEqualTo(2L);
        assertThat(dto.getTaskStats().getTotal()).isEqualTo(3L);
    }

    private void stubEmptyCounts() {
        when(paperMapper.selectCount(null)).thenReturn(0L);
        when(paperMapper.countByReadingStatus(any())).thenReturn(0L);
        when(paperMapper.countUncategorized()).thenReturn(0L);
        when(paperMapper.countPinned()).thenReturn(0L);
        when(paperMapper.countCreatedSince(any())).thenReturn(0L);
        when(folderService.getTree()).thenReturn(List.of());
        when(taskMapper.countByStatus(any())).thenReturn(0L);
        when(taskMapper.selectRecent(5)).thenReturn(List.of());
    }

    private void stubEmptyCountsExceptTasks() {
        when(paperMapper.selectCount(null)).thenReturn(0L);
        when(paperMapper.countByReadingStatus(any())).thenReturn(0L);
        when(paperMapper.countUncategorized()).thenReturn(0L);
        when(paperMapper.countPinned()).thenReturn(0L);
        when(paperMapper.countCreatedSince(any())).thenReturn(0L);
        when(folderService.getTree()).thenReturn(List.of());
    }

    private void stubEmptyOther() {
        when(folderService.getTree()).thenReturn(List.of());
        when(taskMapper.countByStatus(any())).thenReturn(0L);
        when(taskMapper.selectRecent(5)).thenReturn(List.of());
    }
}
