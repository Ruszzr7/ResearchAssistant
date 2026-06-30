package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 标签 Mapper —— 标签字典表 + 论文-标签关联查询。
 */
@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    /** 查询某篇论文的所有标签 */
    @Select("SELECT t.* FROM tag t INNER JOIN paper_tag pt ON t.id = pt.tag_id WHERE pt.paper_id = #{paperId}")
    List<Tag> selectByPaperId(@Param("paperId") Long paperId);
}
