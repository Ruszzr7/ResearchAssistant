package com.research.assistant.service.rag;

import com.research.assistant.entity.RagIndexState;
import com.research.assistant.entity.RagIndexVersion;
import com.research.assistant.mapper.RagIndexStateMapper;
import com.research.assistant.mapper.RagIndexVersionMapper;
import com.research.assistant.mapper.PaperChunkMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理 RAG 版本生命周期。远程 embedding 不在事务内执行，只有短事务负责版本分配和激活。
 */
@Service
public class RagIndexVersionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RagIndexVersionService.class);

    private final RagIndexStateMapper stateMapper;
    private final RagIndexVersionMapper versionMapper;
    private final PaperChunkMapper paperChunkMapper;

    @Value("${app.rag.index-retention:7d}")
    private java.time.Duration retention;

    public RagIndexVersionService(RagIndexStateMapper stateMapper,
                                  RagIndexVersionMapper versionMapper,
                                  PaperChunkMapper paperChunkMapper) {
        this.stateMapper = stateMapper;
        this.versionMapper = versionMapper;
        this.paperChunkMapper = paperChunkMapper;
    }

    /** 为论文分配一个不会与并发构建冲突的版本号，并记录 BUILDING。 */
    @Transactional
    public int beginBuild(Long paperId) {
        RagIndexState state = stateMapper.selectForUpdate(paperId);
        if (state == null) {
            try {
                stateMapper.insertInitial(paperId);
            } catch (DataIntegrityViolationException ignored) {
                // 另一个节点刚创建了状态行，下面的 FOR UPDATE 会重新读取它。
            }
            state = stateMapper.selectForUpdate(paperId);
        }
        if (state == null) {
            throw new IllegalStateException("无法初始化论文 RAG 索引状态: " + paperId);
        }
        int nextVersion = (state.getNextVersion() == null ? 0 : state.getNextVersion()) + 1;
        if (stateMapper.updateNextVersion(paperId, nextVersion) != 1) {
            throw new IllegalStateException("无法推进论文 RAG 索引版本: " + paperId);
        }
        RagIndexVersion version = new RagIndexVersion();
        version.setPaperId(paperId);
        version.setVersionNo(nextVersion);
        version.setStatus(RagIndexStatus.BUILDING.name());
        version.setChunkCount(0);
        versionMapper.insert(version);
        return nextVersion;
    }

    /** 将完整构建标记为 READY，并在同一事务中切换 active 指针。 */
    @Transactional
    public void activate(Long paperId, int versionNo, int chunkCount) {
        if (stateMapper.selectForUpdate(paperId) == null) {
            throw new IllegalStateException("论文 RAG 索引状态不存在: " + paperId);
        }
        if (versionMapper.markReady(paperId, versionNo, chunkCount) != 1) {
            throw new IllegalStateException("RAG 索引版本不在 BUILDING 状态: " + paperId + "@" + versionNo);
        }
        versionMapper.retireActive(paperId);
        if (stateMapper.activate(paperId, versionNo) != 1
                || versionMapper.activate(paperId, versionNo) != 1) {
            throw new IllegalStateException("RAG 索引版本激活失败: " + paperId + "@" + versionNo);
        }
    }

    @Transactional
    public void markFailed(Long paperId, int versionNo, String error) {
        versionMapper.markFailed(paperId, versionNo, truncate(error));
    }

    public RagIndexVersion activeVersion(Long paperId) {
        return versionMapper.selectActive(paperId);
    }

    public List<RagIndexVersion> activeVersions() {
        return versionMapper.selectAllActive();
    }

    @Transactional
    public int cleanupOldVersions(Long paperId, LocalDateTime before) {
        paperChunkMapper.deleteOldVersions(paperId, before);
        return versionMapper.deleteOldVersions(paperId, before);
    }

    /** 定期删除不再 active 且已过保留期的分片和版本元数据。 */
    @Scheduled(cron = "${app.rag.index-cleanup-cron:0 45 3 * * *}")
    @Transactional
    public void cleanupRetiredVersions() {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            return;
        }
        LocalDateTime before = LocalDateTime.now().minus(retention);
        int deleted = 0;
        List<RagIndexState> states = stateMapper.selectActiveStates();
        if (states == null) {
            return;
        }
        for (RagIndexState state : states) {
            deleted += cleanupOldVersions(state.getPaperId(), before);
        }
        if (deleted > 0) {
            log.info("event=rag_index_versions_cleaned count={} retentionDays={}",
                    deleted, retention.toDays());
        }
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "RAG 索引构建失败";
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() > 1000 ? normalized.substring(0, 1000) : normalized;
    }
}
