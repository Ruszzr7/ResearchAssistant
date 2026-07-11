package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.Tag;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 标签 Mapper —— 标签字典表 + 论文-标签关联查询。
 */
@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    /** 查询某篇论文的所有标签 */
    @Select("SELECT t.id, t.name FROM tag t INNER JOIN paper_tag pt ON t.id = pt.tag_id " +
            "WHERE pt.paper_id = #{paperId}")
    List<Tag> selectByPaperId(@Param("paperId") Long paperId);

    /** 批量查询多篇论文的标签，用于优化 N+1 */
    @Select("<script>" +
            "SELECT t.id, t.name, pt.paper_id AS paper_id FROM tag t " +
            "INNER JOIN paper_tag pt ON t.id = pt.tag_id " +
            "WHERE pt.paper_id IN " +
            "<foreach item='id' collection='paperIds' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "name", column = "name"),
            @Result(property = "paperId", column = "paper_id")
    })
    List<TagWithPaperId> selectByPaperIds(@Param("paperIds") List<Long> paperIds);

    /** 按名称精确查询标签 */
    @Select("SELECT id, name FROM tag WHERE name = #{name} LIMIT 1")
    Tag selectByName(@Param("name") String name);

    /** 删除某篇论文的所有标签关联 */
    @Delete("DELETE FROM paper_tag WHERE paper_id = #{paperId}")
    int deletePaperTagsByPaperId(@Param("paperId") Long paperId);

    /** 删除某个标签的所有关联 */
    @Delete("DELETE FROM paper_tag WHERE tag_id = #{tagId}")
    int deletePaperTagsByTagId(@Param("tagId") Long tagId);

    /** 删除某篇论文与某个标签的关联 */
    @Delete("DELETE FROM paper_tag WHERE paper_id = #{paperId} AND tag_id = #{tagId}")
    int deletePaperTag(@Param("paperId") Long paperId, @Param("tagId") Long tagId);

    /** 为论文添加标签关联 */
    @Insert("INSERT INTO paper_tag(paper_id, tag_id) VALUES(#{paperId}, #{tagId})")
    int insertPaperTag(@Param("paperId") Long paperId, @Param("tagId") Long tagId);

    /** 内部传输对象：标签 + 所属论文 ID */
    class TagWithPaperId {
        private Long id;
        private String name;
        private Long paperId;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Long getPaperId() { return paperId; }
        public void setPaperId(Long paperId) { this.paperId = paperId; }
    }
}
