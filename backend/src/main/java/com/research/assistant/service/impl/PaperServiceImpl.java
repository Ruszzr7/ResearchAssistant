package com.research.assistant.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.TagMapper;
import com.research.assistant.service.PaperService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 论文业务实现。
 * <p>
 * listWithFilters 存在 N+1 查询问题（每篇论文单独查标签），
 * 待数据量增大后优化为批量查询。
 */
@Service
public class PaperServiceImpl implements PaperService {

    private final PaperMapper paperMapper;
    private final TagMapper tagMapper;

    public PaperServiceImpl(PaperMapper paperMapper, TagMapper tagMapper) {
        this.paperMapper = paperMapper;
        this.tagMapper = tagMapper;
    }

    @Override
    public IPage<Paper> listWithFilters(List<Long> folderIds, Long tagId, String status, String keyword,
                                        String sortBy, String sortDir, int page, int size) {
        Page<Paper> pageParam = new Page<>(page, size);
        IPage<Paper> result = paperMapper.selectPageWithFilters(pageParam, folderIds, tagId, status, keyword, sortBy, sortDir);

        // 逐篇加载标签（TODO: 改为批量 IN 查询）
        for (Paper paper : result.getRecords()) {
            paper.setTags(tagMapper.selectByPaperId(paper.getId()));
        }
        return result;
    }

    @Override
    public Paper getById(Long id) {
        Paper paper = paperMapper.selectById(id);
        if (paper != null) {
            paper.setTags(tagMapper.selectByPaperId(paper.getId()));
        }
        return paper;
    }

    @Override
    @Transactional
    public Paper create(Paper paper) {
        paperMapper.insert(paper);
        return getById(paper.getId());   // 回查以填充 tags
    }

    @Override
    @Transactional
    public Paper update(Paper paper) {
        paperMapper.updateById(paper);
        return getById(paper.getId());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        // MyBatis Plus 默认不处理关联表；paper_tag 由数据库外键 CASCADE 自动清理
        paperMapper.deleteById(id);
    }
}
