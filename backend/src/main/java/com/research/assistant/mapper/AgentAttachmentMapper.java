package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.AgentAttachmentRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentAttachmentMapper extends BaseMapper<AgentAttachmentRecord> {
    @Select("SELECT * FROM agent_attachment WHERE attachment_id = #{attachmentId} LIMIT 1")
    AgentAttachmentRecord selectByAttachmentId(@Param("attachmentId") String attachmentId);

    @Select("SELECT * FROM agent_attachment WHERE turn_id = #{turnId} ORDER BY created_at, id")
    List<AgentAttachmentRecord> selectByTurnId(@Param("turnId") long turnId);

    @Select("SELECT * FROM agent_attachment WHERE session_id = #{sessionId} ORDER BY created_at, id")
    List<AgentAttachmentRecord> selectBySessionId(@Param("sessionId") long sessionId);

    @Update("UPDATE agent_attachment SET turn_id = #{turnId} WHERE attachment_id = #{attachmentId} "
            + "AND session_id = #{sessionId} AND (turn_id IS NULL OR turn_id = #{turnId})")
    int claim(@Param("attachmentId") String attachmentId,
              @Param("sessionId") long sessionId,
              @Param("turnId") long turnId);

    @Update("UPDATE agent_attachment SET extraction_status = #{status}, preview_text = #{previewText}, "
            + "metadata_json = #{metadataJson} WHERE attachment_id = #{attachmentId} AND session_id = #{sessionId}")
    int updateExtraction(@Param("attachmentId") String attachmentId,
                         @Param("sessionId") long sessionId,
                         @Param("status") String status,
                         @Param("previewText") String previewText,
                         @Param("metadataJson") String metadataJson);
}
