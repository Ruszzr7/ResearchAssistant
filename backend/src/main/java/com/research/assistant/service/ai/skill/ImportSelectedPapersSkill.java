package com.research.assistant.service.ai.skill;

import com.research.assistant.constant.AcquisitionMethod;
import com.research.assistant.constant.ReadingStatus;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.ArxivFetcher;
import com.research.assistant.service.ai.skill.io.ImportSelectedPapersInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;

/**
 * 批量导入选中论文 Skill —— 根据用户选择将外部论文创建为 Paper 记录并尝试下载 PDF。
 */
@Component
public class ImportSelectedPapersSkill implements Skill<ImportSelectedPapersInput, Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(ImportSelectedPapersSkill.class);

    private final PaperMapper paperMapper;
    private final ArxivFetcher arxivFetcher;

    @Value("${app.storage.pdf-dir:../data/papers}")
    private String pdfStorageDir;

    public ImportSelectedPapersSkill(PaperMapper paperMapper, ArxivFetcher arxivFetcher) {
        this.paperMapper = paperMapper;
        this.arxivFetcher = arxivFetcher;
    }

    @Override
    public String name() {
        return Skills.IMPORT_SELECTED_PAPERS;
    }

    @Override
    public String description() {
        return "将用户选中的候选论文批量导入本地文库。输入：{selected: List, folderId: Long}；输出：{importedIds, failed}。";
    }

    @Override
    public Class<ImportSelectedPapersInput> inputType() {
        return ImportSelectedPapersInput.class;
    }

    @Override
    public Map<String, Object> execute(SkillContext ctx, ImportSelectedPapersInput input) {
        ctx.stage("正在批量导入选中论文…");
        List<Map<String, Object>> selected = input.selected();
        if (selected == null || selected.isEmpty()) {
            return Map.of("importedIds", Collections.emptyList(), "failed", 0);
        }

        List<Long> importedIds = new ArrayList<>();
        int failed = 0;
        for (Map<String, Object> candidate : selected) {
            try {
                Long paperId = importOne(candidate, input.folderId());
                importedIds.add(paperId);
            } catch (Exception e) {
                log.warn("导入论文失败: {}", e.getMessage());
                failed++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("importedIds", importedIds);
        result.put("failed", failed);
        return result;
    }

    private Long importOne(Map<String, Object> candidate, Long folderId) throws Exception {
        String title = String.valueOf(candidate.getOrDefault("title", "未命名论文"));
        Paper paper = new Paper();
        paper.setTitle(title);
        paper.setAuthors(stringOrNull(candidate.get("authors")));
        paper.setYear(intOrNull(candidate.get("year")));
        paper.setSource(stringOrNull(candidate.get("source")));
        paper.setDoi(stringOrNull(candidate.get("doi")));
        paper.setArxivId(stringOrNull(candidate.get("arxivId")));
        paper.setSourceUrl(stringOrNull(candidate.get("sourceUrl")));
        paper.setAbstractText(stringOrNull(candidate.get("summary")));
        paper.setKeywords(stringOrNull(candidate.get("keywords")));
        paper.setFolderId(folderId);
        paper.setReadingStatus(ReadingStatus.UNREAD);
        paper.setAcquisitionMethod(AcquisitionMethod.OA);
        paper.setPinned(false);
        paperMapper.insert(paper);

        String arxivId = paper.getArxivId();
        if (arxivId != null && !arxivId.isBlank()) {
            try {
                File dir = new File(pdfStorageDir);
                if (!dir.isAbsolute()) {
                    dir = new File(System.getProperty("user.dir"), pdfStorageDir);
                }
                String fileName = arxivFetcher.downloadPdf(arxivId, dir.getAbsolutePath());
                if (fileName != null) {
                    Paper update = new Paper();
                    update.setId(paper.getId());
                    update.setPdfPath(fileName);
                    paperMapper.updateById(update);
                }
            } catch (Exception e) {
                log.warn("下载 PDF 失败 arxivId={}: {}", arxivId, e.getMessage());
            }
        }

        return paper.getId();
    }

    private String stringOrNull(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }

    private Integer intOrNull(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
