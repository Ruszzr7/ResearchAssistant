package com.research.assistant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.research.assistant.entity.Folder;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件夹 Mapper —— BaseMapper 自带 CRUD，无需额外 SQL。
 */
@Mapper
public interface FolderMapper extends BaseMapper<Folder> {
}
