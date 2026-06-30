package com.research.assistant.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.research.assistant.entity.Paper;

import java.util.List;

/**
 * 论文业务接口。
 *
 * @author ResearchAssistant
 */
public interface PaperService {

    /** 分页 + 筛选查询（按文件夹/标签/阅读状态），标签随论文一起返回 */
    IPage<Paper> listWithFilters(List<Long> folderIds, Long tagId, String status, String keyword,
                                String sortBy, String sortDir, int page, int size);

    /** 查单篇论文详情（含标签） */
    Paper getById(Long id);

    /** 新建论文 */
    Paper create(Paper paper);

    /** 更新论文信息 */
    Paper update(Paper paper);

    /** 删除论文 */
    void delete(Long id);
}
