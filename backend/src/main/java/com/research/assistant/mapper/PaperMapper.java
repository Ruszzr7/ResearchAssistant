package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.research.assistant.entity.Paper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 论文 Mapper —— 复杂查询手写 SQL，简单 CRUD 继承 BaseMapper。
 * <p>
 * 注意：selectById 被重写以处理 abstract 列（MySQL 保留字 → AS abstract_text）。
 */
@Mapper
public interface PaperMapper extends BaseMapper<Paper> {

    /**
     * 分页 + 多条件筛选（文件夹/标签/阅读状态）。
     * folderIds 为空时不限制文件夹；tagId 非空时 JOIN paper_tag 过滤。
     */
    @Select("<script>" +
        "SELECT DISTINCT p.id, p.title, p.authors, p.year, p.source, p.doi, " +
        "p.`abstract` AS abstract_text, p.keywords, p.pdf_path, " +
        "p.acquisition_method, p.folder_id, p.reading_status, p.ai_summary, " +
        "p.created_at, p.updated_at " +
        "FROM paper p " +
        "<if test='tagId != null'>" +
        "INNER JOIN paper_tag pt ON p.id = pt.paper_id " +
        "</if>" +
        "<where>" +
        "  <if test='folderIds != null and folderIds.size() > 0'>" +
        "    AND p.folder_id IN <foreach item='id' collection='folderIds' open='(' separator=',' close=')'>#{id}</foreach>" +
        "  </if>" +
        "  <if test='tagId != null'> AND pt.tag_id = #{tagId}</if>" +
        "  <if test='status != null and status != \"\"'> AND p.reading_status = #{status}</if>" +
        "  <if test='keyword != null and keyword != \"\"'>" +
        "    AND (p.title LIKE CONCAT('%',#{keyword},'%') OR p.authors LIKE CONCAT('%',#{keyword},'%') OR p.keywords LIKE CONCAT('%',#{keyword},'%'))" +
        "  </if>" +
        "</where>" +
        " <choose>" +
        "  <when test='sortBy == \"title\"'> ORDER BY p.title ${sortDir}</when>" +
        "  <when test='sortBy == \"year\"'> ORDER BY p.year ${sortDir}</when>" +
        "  <otherwise> ORDER BY p.created_at ${sortDir}</otherwise>" +
        " </choose>" +
        "</script>")
    IPage<Paper> selectPageWithFilters(Page<Paper> page,
                                       @Param("folderIds") List<Long> folderIds,
                                       @Param("tagId") Long tagId,
                                       @Param("status") String status,
                                       @Param("keyword") String keyword,
                                       @Param("sortBy") String sortBy,
                                       @Param("sortDir") String sortDir);

    /** 覆盖 BaseMapper.selectById — 重载 Long 版本处理 abstract 列别名 */
    @Select("SELECT p.id, p.title, p.authors, p.year, p.source, p.doi, " +
        "p.`abstract` AS abstract_text, p.keywords, p.pdf_path, " +
        "p.acquisition_method, p.folder_id, p.reading_status, p.ai_summary, " +
        "p.created_at, p.updated_at " +
        "FROM paper p WHERE p.id = #{id}")
    Paper selectById(@Param("id") Long id);

    /** 统计各文件夹下的直接论文数 */
    @Select("SELECT p.folder_id AS folder_id, COUNT(*) AS cnt FROM paper p " +
        "WHERE p.folder_id IS NOT NULL GROUP BY p.folder_id")
    List<java.util.Map<String, Object>> countByFolder();
}
