package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.RagIndexVersion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RagIndexVersionMapper extends BaseMapper<RagIndexVersion> {

    @Update("UPDATE rag_index_version SET status = 'READY', chunk_count = #{chunkCount}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE paper_id = #{paperId} AND version_no = #{versionNo} "
            + "AND status = 'BUILDING'")
    int markReady(@Param("paperId") Long paperId, @Param("versionNo") Integer versionNo,
                  @Param("chunkCount") Integer chunkCount);

    @Update("UPDATE rag_index_version SET status = 'RETIRED', updated_at = CURRENT_TIMESTAMP "
            + "WHERE paper_id = #{paperId} AND status = 'ACTIVE'")
    int retireActive(@Param("paperId") Long paperId);

    @Update("UPDATE rag_index_version SET status = 'ACTIVE', activated_at = CURRENT_TIMESTAMP, "
            + "updated_at = CURRENT_TIMESTAMP WHERE paper_id = #{paperId} AND version_no = #{versionNo} "
            + "AND status = 'READY'")
    int activate(@Param("paperId") Long paperId, @Param("versionNo") Integer versionNo);

    @Update("UPDATE rag_index_version SET status = 'FAILED', error = #{error}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE paper_id = #{paperId} AND version_no = #{versionNo} "
            + "AND status = 'BUILDING'")
    int markFailed(@Param("paperId") Long paperId, @Param("versionNo") Integer versionNo,
                   @Param("error") String error);

    @Select("SELECT id, paper_id, version_no, status, chunk_count, error, created_at, activated_at, updated_at "
            + "FROM rag_index_version WHERE paper_id = #{paperId} AND status = 'ACTIVE' LIMIT 1")
    RagIndexVersion selectActive(@Param("paperId") Long paperId);

    @Select("SELECT id, paper_id, version_no, status, chunk_count, error, created_at, activated_at, updated_at "
            + "FROM rag_index_version WHERE status = 'ACTIVE'")
    List<RagIndexVersion> selectAllActive();

    @Delete("DELETE FROM rag_index_version WHERE paper_id = #{paperId} AND status IN ('RETIRED', 'FAILED') "
            + "AND updated_at < #{before}")
    int deleteOldVersions(@Param("paperId") Long paperId,
                          @Param("before") java.time.LocalDateTime before);
}
