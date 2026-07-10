package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.ReadingPlanItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface ReadingPlanItemMapper extends BaseMapper<ReadingPlanItem> {

    /** 已逾期条目数（status != DONE 且 deadline < today） */
    @Select("SELECT COUNT(*) FROM reading_plan_item WHERE status != 'DONE' AND deadline < #{today}")
    long countOverdue(@Param("today") LocalDate today);

    /** 指定日期范围内到期条目数（status != DONE） */
    @Select("SELECT COUNT(*) FROM reading_plan_item WHERE status != 'DONE' AND deadline BETWEEN #{start} AND #{end}")
    long countDueBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** 本周待读条目数（status != DONE 且 deadline 落在本周，含无 deadline 条目） */
    @Select("SELECT COUNT(*) FROM reading_plan_item WHERE status != 'DONE' AND (deadline BETWEEN #{weekStart} AND #{weekEnd} OR deadline IS NULL)")
    long countThisWeek(@Param("weekStart") LocalDate weekStart, @Param("weekEnd") LocalDate weekEnd);
}
