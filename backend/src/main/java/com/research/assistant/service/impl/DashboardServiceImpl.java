package com.research.assistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.research.assistant.dto.DashboardDto;
import com.research.assistant.entity.AsyncTaskRecord;
import com.research.assistant.entity.Folder;
import com.research.assistant.entity.Paper;
import com.research.assistant.entity.PaperAnnotation;
import com.research.assistant.mapper.AsyncTaskRecordMapper;
import com.research.assistant.mapper.PaperAnnotationMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.DashboardService;
import com.research.assistant.service.FolderService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 看板聚合服务实现。
 * <p>
 * 所有统计均为轻量级 count/list 查询，避免跨大表 JOIN，便于后续加缓存。
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    private static final int TOP_FOLDERS = 5;
    private static final int RECENT_LIMIT = 5;

    private final PaperMapper paperMapper;
    private final FolderService folderService;
    private final AsyncTaskRecordMapper asyncTaskRecordMapper;
    private final PaperAnnotationMapper paperAnnotationMapper;

    public DashboardServiceImpl(PaperMapper paperMapper,
                                FolderService folderService,
                                AsyncTaskRecordMapper asyncTaskRecordMapper,
                                PaperAnnotationMapper paperAnnotationMapper) {
        this.paperMapper = paperMapper;
        this.folderService = folderService;
        this.asyncTaskRecordMapper = asyncTaskRecordMapper;
        this.paperAnnotationMapper = paperAnnotationMapper;
    }

    @Override
    public DashboardDto aggregate() {
        DashboardDto dto = new DashboardDto();
        dto.setPaperStats(buildPaperStats());
        dto.setFolderBacklog(buildFolderBacklog());
        dto.setTaskStats(buildTaskStats());
        dto.setRecentNotes(buildRecentNotes());
        dto.setRecentAnnotations(buildRecentAnnotations());
        return dto;
    }

    private DashboardDto.PaperStats buildPaperStats() {
        DashboardDto.PaperStats stats = new DashboardDto.PaperStats();
        stats.setTotal(paperMapper.selectCount(null));
        stats.setUnread(paperMapper.countByReadingStatus("UNREAD"));
        stats.setReading(paperMapper.countByReadingStatus("READING"));
        stats.setRead(paperMapper.countByReadingStatus("READ"));
        stats.setPinned(paperMapper.countPinned());

        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        stats.setThisMonth(paperMapper.countCreatedSince(monthStart));
        return stats;
    }

    private List<DashboardDto.FolderBacklog> buildFolderBacklog() {
        List<Folder> tree = folderService.getTree();
        List<DashboardDto.FolderBacklog> all = new ArrayList<>();
        collectFolders(tree, all);
        all.sort(Comparator.comparingInt(DashboardDto.FolderBacklog::getPaperCount).reversed());
        return all.stream().limit(TOP_FOLDERS).toList();
    }

    private void collectFolders(List<Folder> folders, List<DashboardDto.FolderBacklog> out) {
        if (folders == null) return;
        for (Folder f : folders) {
            int count = f.getPaperCount() != null ? f.getPaperCount() : 0;
            out.add(new DashboardDto.FolderBacklog(f.getId(), f.getName(), count));
            collectFolders(f.getChildren(), out);
        }
    }

    private DashboardDto.TaskStats buildTaskStats() {
        DashboardDto.TaskStats stats = new DashboardDto.TaskStats();
        long pending = asyncTaskRecordMapper.countByStatus("PENDING");
        long processing = asyncTaskRecordMapper.countByStatus("PROCESSING");
        long retryWait = asyncTaskRecordMapper.countByStatus("RETRY_WAIT");
        long completed = asyncTaskRecordMapper.countByStatus("COMPLETED");
        long failed = asyncTaskRecordMapper.countByStatus("FAILED");
        long cancelled = asyncTaskRecordMapper.countByStatus("CANCELLED");
        long pendingUser = asyncTaskRecordMapper.countByStatus("PENDING_USER");
        long expired = asyncTaskRecordMapper.countByStatus("EXPIRED");
        long deadLetter = asyncTaskRecordMapper.countByStatus("DEAD_LETTER");
        stats.setPending(pending);
        stats.setProcessing(processing);
        stats.setRetryWait(retryWait);
        stats.setCompleted(completed);
        stats.setFailed(failed);
        stats.setCancelled(cancelled);
        stats.setPendingUser(pendingUser);
        stats.setExpired(expired);
        stats.setDeadLetter(deadLetter);
        stats.setTotal(pending + processing + retryWait + completed + failed + cancelled + pendingUser + expired + deadLetter);

        List<AsyncTaskRecord> recent = asyncTaskRecordMapper.selectRecent(RECENT_LIMIT);
        stats.setRecent(recent.stream().map(this::toRecentTask).toList());
        return stats;
    }

    private DashboardDto.RecentTask toRecentTask(AsyncTaskRecord record) {
        DashboardDto.RecentTask dto = new DashboardDto.RecentTask();
        dto.setId(record.getId());
        dto.setTaskId(record.getTaskId());
        dto.setTitle(record.getTitle());
        dto.setStatus(record.getStatus());
        dto.setStageText(record.getStageText());
        dto.setCreatedAt(record.getCreatedAt());
        return dto;
    }

    private List<DashboardDto.RecentNote> buildRecentNotes() {
        List<PaperAnnotation> notes = paperAnnotationMapper.selectList(
                new LambdaQueryWrapper<PaperAnnotation>()
                        .eq(PaperAnnotation::getType, "NOTE")
                        .orderByDesc(PaperAnnotation::getCreatedAt)
                        .last("LIMIT " + RECENT_LIMIT));
        return notes.stream()
                .map(n -> new DashboardDto.RecentNote(n.getId(), summarize(n.getNote()), n.getCreatedAt()))
                .toList();
    }

    private List<DashboardDto.RecentAnnotation> buildRecentAnnotations() {
        List<PaperAnnotation> annotations = paperAnnotationMapper.selectList(
                new LambdaQueryWrapper<PaperAnnotation>()
                        .eq(PaperAnnotation::getType, "COMMENT")
                        .orderByDesc(PaperAnnotation::getCreatedAt)
                        .last("LIMIT " + RECENT_LIMIT));
        if (annotations.isEmpty()) {
            return List.of();
        }
        List<Long> paperIds = annotations.stream()
                .map(PaperAnnotation::getPaperId)
                .distinct()
                .toList();
        Map<Long, String> titleMap = paperMapper.selectBatchIds(paperIds).stream()
                .collect(Collectors.toMap(Paper::getId, p -> p.getTitle() == null ? "" : p.getTitle(), (a, b) -> a));

        return annotations.stream()
                .map(a -> new DashboardDto.RecentAnnotation(
                        a.getId(),
                        a.getPaperId(),
                        titleMap.getOrDefault(a.getPaperId(), ""),
                        a.getPage(),
                        a.getNote(),
                        a.getCreatedAt()))
                .toList();
    }

    private String summarize(String value) {
        String normalized = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= 48 ? normalized : normalized.substring(0, 48) + "…";
    }
}
