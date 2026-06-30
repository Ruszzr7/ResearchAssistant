package com.research.assistant.service;

import com.research.assistant.entity.Folder;

import java.util.List;

/**
 * 文件夹业务接口。
 * <p>
 * 文件夹支持嵌套（parentId 自引用），前端以树形结构展示。
 * delete 不级联删除 —— 子文件夹 parentId 置为 null（移到根目录）。
 *
 * @author ResearchAssistant
 */
public interface FolderService {

    /** 返回完整文件夹树（嵌套结构） */
    List<Folder> getTree();

    /** 新建文件夹 */
    Folder create(Folder folder);

    /** 更新文件夹（重命名/移动） */
    Folder update(Folder folder);

    /** 删除文件夹（子文件夹移到根目录，不级联删除） */
    void delete(Long id);

    /** 拖拽移动文件夹 */
    void move(Long id, Long parentId, Integer sortOrder);
}
