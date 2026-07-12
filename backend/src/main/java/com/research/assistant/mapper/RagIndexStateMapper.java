package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.RagIndexState;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RagIndexStateMapper extends BaseMapper<RagIndexState> {

    @Select("SELECT paper_id, active_version, next_version, updated_at "
            + "FROM rag_index_state WHERE paper_id = #{paperId} FOR UPDATE")
    RagIndexState selectForUpdate(@Param("paperId") Long paperId);

    @Insert("INSERT INTO rag_index_state (paper_id, active_version, next_version) "
            + "VALUES (#{paperId}, NULL, 0)")
    int insertInitial(@Param("paperId") Long paperId);

    @Update("UPDATE rag_index_state SET next_version = #{nextVersion}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE paper_id = #{paperId}")
    int updateNextVersion(@Param("paperId") Long paperId, @Param("nextVersion") Integer nextVersion);

    @Update("UPDATE rag_index_state SET active_version = #{versionNo}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE paper_id = #{paperId}")
    int activate(@Param("paperId") Long paperId, @Param("versionNo") Integer versionNo);

    @Select("SELECT paper_id, active_version, next_version, updated_at "
            + "FROM rag_index_state WHERE active_version IS NOT NULL")
    java.util.List<RagIndexState> selectActiveStates();
}
