package com.research.assistant.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.research.assistant.entity.Paper;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 论文业务接口。
 *
 * @author ResearchAssistant
 */
public interface PaperService {

    /** 分页 + 筛选查询（按文件夹/标签/阅读状态/未分类），标签随论文一起返回 */
    IPage<Paper> listWithFilters(List<Long> folderIds, boolean uncategorized, Long tagId,
                                String status, String keyword,
                                String sortBy, String sortDir, int page, int size);

    /** 查单篇论文详情（含标签） */
    Paper getById(Long id);

    /** 新建论文 */
    Paper create(Paper paper);

    /** 更新论文信息 */
    Paper update(Paper paper);

    /** 删除论文 */
    void delete(Long id);

    /** 上传 PDF 到已有论文，返回存储的相对路径 */
    String uploadPdf(Long paperId, MultipartFile file);

    /** 上传 PDF 并创建论文（一步完成入库），返回创建后的论文 */
    Paper uploadPdfAndCreate(MultipartFile file, Paper paper);

    /** 上传 PDF 并创建论文；overwrite=true 时覆盖已存在的同 DOI/同文件论文。 */
    Paper uploadPdfAndCreate(MultipartFile file, Paper paper, boolean overwrite);

    /** 批量删除论文 */
    void deleteBatch(List<Long> ids);

    /** 批量移动论文到指定文件夹（folderId 为 null 表示移出到根） */
    void moveBatch(List<Long> ids, Long folderId);

    /** 切换论文置顶状态 */
    Paper togglePin(Long id);
}
