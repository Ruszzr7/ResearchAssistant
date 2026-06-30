package com.research.assistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.research.assistant.entity.Folder;
import com.research.assistant.mapper.FolderMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.FolderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文件夹业务实现。
 * <p>
 * getTree() 将数据库平铺的文件夹列表转为嵌套树结构。
 * delete() 不级联删除子文件夹 —— 子文件夹 parentId 被置为 null（移到根目录）。
 */
@Service
public class FolderServiceImpl implements FolderService {

    private final FolderMapper folderMapper;
    private final PaperMapper paperMapper;

    public FolderServiceImpl(FolderMapper folderMapper, PaperMapper paperMapper) {
        this.folderMapper = folderMapper;
        this.paperMapper = paperMapper;
    }

    @Override
    public List<Folder> getTree() {
        List<Folder> all = folderMapper.selectList(
            new LambdaQueryWrapper<Folder>().orderByAsc(Folder::getSortOrder)
        );

        // 各文件夹直接论文数（手动把 List<Map> 转 Map<Long, Long>）
        Map<Long, Long> directCounts = new java.util.HashMap<>();
        for (var row : paperMapper.countByFolder()) {
            Long fid = ((Number) row.get("folder_id")).longValue();
            Long cnt = ((Number) row.get("cnt")).longValue();
            directCounts.put(fid, cnt);
        }

        // 按 parentId 分组，建立父子关系
        Map<Long, List<Folder>> childrenMap = all.stream()
            .filter(f -> f.getParentId() != null)
            .collect(Collectors.groupingBy(Folder::getParentId));

        for (Folder f : all) {
            f.setChildren(childrenMap.getOrDefault(f.getId(), List.of()));
        }

        // 递归计算论文数（自身 + 所有子孙）
        for (Folder f : all) {
            f.setPaperCount(computePaperCount(f, directCounts));
        }

        // 返回顶层（parentId == null）
        return all.stream()
            .filter(f -> f.getParentId() == null)
            .collect(Collectors.toList());
    }

    /** 递归计算文件夹下论文总数（含自身及所有子孙） */
    private int computePaperCount(Folder folder, Map<Long, Long> directCounts) {
        int count = directCounts.getOrDefault(folder.getId(), 0L).intValue();
        if (folder.getChildren() != null) {
            for (Folder child : folder.getChildren()) {
                count += computePaperCount(child, directCounts);
            }
        }
        return count;
    }

    @Override
    public Folder create(Folder folder) {
        folderMapper.insert(folder);
        return folder;
    }

    @Override
    public Folder update(Folder folder) {
        folderMapper.updateById(folder);
        return folderMapper.selectById(folder.getId());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        // 子文件夹移到根目录（UpdateWrapper 强制写 null），不级联删除
        List<Folder> children = folderMapper.selectList(
            new LambdaQueryWrapper<Folder>().eq(Folder::getParentId, id)
        );
        for (Folder child : children) {
            UpdateWrapper<Folder> uw = new UpdateWrapper<>();
            uw.eq("id", child.getId()).set("parent_id", null);
            folderMapper.update(null, uw);
        }
        folderMapper.deleteById(id);
    }

    @Override
    public void move(Long id, Long parentId, Integer sortOrder) {
        // updateById 默认忽略 null → 用 UpdateWrapper 强制更新
        UpdateWrapper<Folder> uw = new UpdateWrapper<>();
        uw.eq("id", id);
        if (parentId != null) {
            uw.set("parent_id", parentId);
        } else {
            uw.set("parent_id", null);
        }
        uw.set("sort_order", sortOrder);
        folderMapper.update(null, uw);
    }
}
