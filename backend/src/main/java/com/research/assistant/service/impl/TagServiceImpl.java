package com.research.assistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.research.assistant.entity.Tag;
import com.research.assistant.mapper.TagMapper;
import com.research.assistant.service.TagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 标签服务实现。
 */
@Service
public class TagServiceImpl implements TagService {

    private static final Logger log = LoggerFactory.getLogger(TagServiceImpl.class);

    private final TagMapper tagMapper;

    public TagServiceImpl(TagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

    @Override
    public List<Tag> listAll() {
        return tagMapper.selectList(new QueryWrapper<Tag>().orderByAsc("name"));
    }

    @Override
    public Tag getById(Long id) {
        return tagMapper.selectById(id);
    }

    @Override
    @Transactional
    public Tag create(String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("标签名称不能为空");
        }
        String trimmed = name.trim();
        Tag existing = tagMapper.selectByName(trimmed);
        if (existing != null) {
            return existing;
        }
        Tag tag = new Tag();
        tag.setName(trimmed);
        tagMapper.insert(tag);
        return tag;
    }

    @Override
    @Transactional
    public Tag rename(Long id, String name) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("标签名称不能为空");
        }
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new IllegalArgumentException("标签不存在");
        }
        String trimmed = name.trim();
        Tag existing = tagMapper.selectByName(trimmed);
        if (existing != null && !existing.getId().equals(id)) {
            throw new IllegalArgumentException("标签名称已存在");
        }
        tag.setName(trimmed);
        tagMapper.updateById(tag);
        return tag;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        // 先清理关联
        // 由于 paper_tag 没有独立主键，无法通过 BaseMapper 删除；使用自定义 SQL
        tagMapper.deletePaperTagsByTagId(id);
        tagMapper.deleteById(id);
    }

    @Override
    public List<Tag> getTagsByPaperId(Long paperId) {
        return tagMapper.selectByPaperId(paperId);
    }

    @Override
    @Transactional
    public void setPaperTags(Long paperId, List<Long> tagIds) {
        tagMapper.deletePaperTagsByPaperId(paperId);
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        for (Long tagId : tagIds) {
            if (tagId != null) {
                tagMapper.insertPaperTag(paperId, tagId);
            }
        }
    }

    @Override
    @Transactional
    public void addTagToPaper(Long paperId, String tagName) {
        Tag tag = create(tagName);
        tagMapper.insertPaperTag(paperId, tag.getId());
    }

    @Override
    @Transactional
    public void removeTagFromPaper(Long paperId, Long tagId) {
        // 使用自定义 SQL 删除单条关联
        tagMapper.deletePaperTag(paperId, tagId);
    }
}
