package com.research.assistant.service;

import com.research.assistant.entity.Tag;

import java.util.List;

/**
 * 标签服务 —— 标签字典与论文-标签关联的维护。
 */
public interface TagService {

    /** 查询所有标签 */
    List<Tag> listAll();

    /** 根据 ID 查询标签 */
    Tag getById(Long id);

    /** 创建标签；若同名标签已存在则返回已有标签 */
    Tag create(String name);

    /** 重命名标签 */
    Tag rename(Long id, String name);

    /** 删除标签，并清理所有论文关联 */
    void delete(Long id);

    /** 查询某篇论文的标签 */
    List<Tag> getTagsByPaperId(Long paperId);

    /** 批量设置论文的标签（先清空再绑定） */
    void setPaperTags(Long paperId, List<Long> tagIds);

    /** 为论文添加标签（按名称，不存在则创建） */
    void addTagToPaper(Long paperId, String tagName);

    /** 移除论文的某个标签 */
    void removeTagFromPaper(Long paperId, Long tagId);
}
