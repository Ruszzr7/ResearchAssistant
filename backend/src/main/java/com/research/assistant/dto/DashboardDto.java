package com.research.assistant.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 首页看板聚合数据 DTO。
 * <p>
 * 一次性返回论文库、阅读计划、异步任务、最近动态的统计信息，
 * 减少前端多次请求并避免在数据库上做复杂连接查询。
 */
public class DashboardDto {

    /** 论文统计 */
    private PaperStats paperStats;

    /** 文件夹堆积 Top N（按论文数降序） */
    private List<FolderBacklog> folderBacklog;

    /** 阅读计划统计 */
    private ReadingPlanStats readingPlanStats;

    /** 异步任务统计 */
    private TaskStats taskStats;

    /** 最近笔记 */
    private List<RecentNote> recentNotes;

    /** 最近批注 */
    private List<RecentAnnotation> recentAnnotations;

    public PaperStats getPaperStats() { return paperStats; }
    public void setPaperStats(PaperStats paperStats) { this.paperStats = paperStats; }

    public List<FolderBacklog> getFolderBacklog() { return folderBacklog; }
    public void setFolderBacklog(List<FolderBacklog> folderBacklog) { this.folderBacklog = folderBacklog; }

    public ReadingPlanStats getReadingPlanStats() { return readingPlanStats; }
    public void setReadingPlanStats(ReadingPlanStats readingPlanStats) { this.readingPlanStats = readingPlanStats; }

    public TaskStats getTaskStats() { return taskStats; }
    public void setTaskStats(TaskStats taskStats) { this.taskStats = taskStats; }

    public List<RecentNote> getRecentNotes() { return recentNotes; }
    public void setRecentNotes(List<RecentNote> recentNotes) { this.recentNotes = recentNotes; }

    public List<RecentAnnotation> getRecentAnnotations() { return recentAnnotations; }
    public void setRecentAnnotations(List<RecentAnnotation> recentAnnotations) { this.recentAnnotations = recentAnnotations; }

    // ===== 嵌套 DTO =====

    public static class PaperStats {
        private long total;
        private long unread;
        private long reading;
        private long read;
        private long pinned;
        private long thisMonth;

        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
        public long getUnread() { return unread; }
        public void setUnread(long unread) { this.unread = unread; }
        public long getReading() { return reading; }
        public void setReading(long reading) { this.reading = reading; }
        public long getRead() { return read; }
        public void setRead(long read) { this.read = read; }
        public long getPinned() { return pinned; }
        public void setPinned(long pinned) { this.pinned = pinned; }
        public long getThisMonth() { return thisMonth; }
        public void setThisMonth(long thisMonth) { this.thisMonth = thisMonth; }
    }

    public static class FolderBacklog {
        private Long id;
        private String name;
        private int paperCount;

        public FolderBacklog() {}

        public FolderBacklog(Long id, String name, int paperCount) {
            this.id = id;
            this.name = name;
            this.paperCount = paperCount;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getPaperCount() { return paperCount; }
        public void setPaperCount(int paperCount) { this.paperCount = paperCount; }
    }

    public static class ReadingPlanStats {
        private long overdue;
        private long dueSoon;
        private long thisWeek;

        public long getOverdue() { return overdue; }
        public void setOverdue(long overdue) { this.overdue = overdue; }
        public long getDueSoon() { return dueSoon; }
        public void setDueSoon(long dueSoon) { this.dueSoon = dueSoon; }
        public long getThisWeek() { return thisWeek; }
        public void setThisWeek(long thisWeek) { this.thisWeek = thisWeek; }
    }

    public static class TaskStats {
        private long pending;
        private long processing;
        private long retryWait;
        private long completed;
        private long failed;
        private long cancelled;
        private long pendingUser;
        private long expired;
        private long deadLetter;
        private long total;
        private List<RecentTask> recent;

        public long getPending() { return pending; }
        public void setPending(long pending) { this.pending = pending; }
        public long getProcessing() { return processing; }
        public void setProcessing(long processing) { this.processing = processing; }
        public long getRetryWait() { return retryWait; }
        public void setRetryWait(long retryWait) { this.retryWait = retryWait; }
        public long getCompleted() { return completed; }
        public void setCompleted(long completed) { this.completed = completed; }
        public long getFailed() { return failed; }
        public void setFailed(long failed) { this.failed = failed; }
        public long getCancelled() { return cancelled; }
        public void setCancelled(long cancelled) { this.cancelled = cancelled; }
        public long getPendingUser() { return pendingUser; }
        public void setPendingUser(long pendingUser) { this.pendingUser = pendingUser; }
        public long getExpired() { return expired; }
        public void setExpired(long expired) { this.expired = expired; }
        public long getDeadLetter() { return deadLetter; }
        public void setDeadLetter(long deadLetter) { this.deadLetter = deadLetter; }
        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
        public List<RecentTask> getRecent() { return recent; }
        public void setRecent(List<RecentTask> recent) { this.recent = recent; }
    }

    public static class RecentTask {
        private Long id;
        private String taskId;
        private String title;
        private String status;
        private String stageText;
        private LocalDateTime createdAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getStageText() { return stageText; }
        public void setStageText(String stageText) { this.stageText = stageText; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    public static class RecentNote {
        private Long id;
        private String title;
        private LocalDateTime createdAt;

        public RecentNote() {}

        public RecentNote(Long id, String title, LocalDateTime createdAt) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    public static class RecentAnnotation {
        private Long id;
        private Long paperId;
        private String paperTitle;
        private Integer page;
        private String note;
        private LocalDateTime createdAt;

        public RecentAnnotation() {}

        public RecentAnnotation(Long id, Long paperId, String paperTitle, Integer page,
                                String note, LocalDateTime createdAt) {
            this.id = id;
            this.paperId = paperId;
            this.paperTitle = paperTitle;
            this.page = page;
            this.note = note;
            this.createdAt = createdAt;
        }

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getPaperId() { return paperId; }
        public void setPaperId(Long paperId) { this.paperId = paperId; }
        public String getPaperTitle() { return paperTitle; }
        public void setPaperTitle(String paperTitle) { this.paperTitle = paperTitle; }
        public Integer getPage() { return page; }
        public void setPage(Integer page) { this.page = page; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }
}
