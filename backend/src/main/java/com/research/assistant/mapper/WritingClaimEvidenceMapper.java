package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.WritingClaimEvidence;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WritingClaimEvidenceMapper extends BaseMapper<WritingClaimEvidence> {

    @Select("SELECT COUNT(*) FROM writing_claim_evidence e "
            + "JOIN writing_claim c ON c.id = e.claim_id "
            + "WHERE c.project_id = #{projectId} AND e.paper_id = #{paperId}")
    long countByProjectAndPaper(@Param("projectId") long projectId,
                                @Param("paperId") long paperId);
}
