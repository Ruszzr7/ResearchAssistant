package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.PaperFormulaRegionRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PaperFormulaRegionMapper extends BaseMapper<PaperFormulaRegionRecord> {

    String COLUMNS = "id, paper_id, document_hash, parser_version, page_number, region_key, "
            + "box_x, box_y, box_width, box_height, latex, formula_items_json, "
            + "confidence, source, status, "
            + "created_at, updated_at";

    @Select("SELECT " + COLUMNS + " FROM paper_formula_region "
            + "WHERE paper_id = #{paperId} AND document_hash = #{documentHash} "
            + "AND parser_version = #{parserVersion} AND page_number = #{page} "
            + "AND region_key = #{regionKey} LIMIT 1")
    PaperFormulaRegionRecord selectCurrent(@Param("paperId") Long paperId,
                                           @Param("documentHash") String documentHash,
                                           @Param("parserVersion") String parserVersion,
                                           @Param("page") int page,
                                           @Param("regionKey") String regionKey);

    @Select("SELECT " + COLUMNS + " FROM paper_formula_region "
            + "WHERE paper_id = #{paperId} AND document_hash = #{documentHash} "
            + "AND parser_version = #{parserVersion} AND page_number = #{page} "
            + "AND status = 'CONFIRMED' "
            + "ORDER BY updated_at DESC, id DESC")
    List<PaperFormulaRegionRecord> selectConfirmedOnPage(@Param("paperId") Long paperId,
                                                         @Param("documentHash") String documentHash,
                                                         @Param("parserVersion") String parserVersion,
                                                         @Param("page") int page);
}
