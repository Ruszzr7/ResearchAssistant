package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.InlineMathTranscriptionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InlineMathTranscriptionMapper extends BaseMapper<InlineMathTranscriptionRecord> {

    @Select("SELECT id, paper_id, document_hash, parser_version, block_id, start_offset, "
            + "end_offset, source_hash, provider_version, status, latex, confidence, message, "
            + "created_at, updated_at FROM inline_math_transcription_cache "
            + "WHERE paper_id = #{paperId} AND document_hash = #{documentHash} "
            + "AND parser_version = #{parserVersion} AND block_id = #{blockId} "
            + "AND start_offset = #{startOffset} AND end_offset = #{endOffset} "
            + "AND source_hash = #{sourceHash} AND provider_version = #{providerVersion} LIMIT 1")
    InlineMathTranscriptionRecord selectCurrent(@Param("paperId") Long paperId,
                                                 @Param("documentHash") String documentHash,
                                                 @Param("parserVersion") String parserVersion,
                                                 @Param("blockId") String blockId,
                                                 @Param("startOffset") int startOffset,
                                                 @Param("endOffset") int endOffset,
                                                 @Param("sourceHash") String sourceHash,
                                                 @Param("providerVersion") String providerVersion);
}
