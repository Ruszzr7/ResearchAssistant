package com.research.assistant.service.export;

import com.research.assistant.entity.Paper;
import com.research.assistant.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 将论文导出为 Obsidian Markdown + BibTeX 文件。
 */
@Service
public class ObsidianSyncService {

    private static final Logger log = LoggerFactory.getLogger(ObsidianSyncService.class);

    private static final String VAULT_PATH_KEY = "obsidian_vault_path";

    private final SettingsService settingsService;
    private final BibTeXExporter bibTeXExporter;

    public ObsidianSyncService(SettingsService settingsService, BibTeXExporter bibTeXExporter) {
        this.settingsService = settingsService;
        this.bibTeXExporter = bibTeXExporter;
    }

    public int sync(List<Paper> papers) throws IOException {
        String vaultPath = settingsService.getValue(VAULT_PATH_KEY);
        if (vaultPath == null || vaultPath.isBlank()) {
            throw new IllegalArgumentException("未配置 Obsidian vault 路径");
        }
        Path dir = Path.of(vaultPath).resolve("research-assistant").toAbsolutePath().normalize();
        Files.createDirectories(dir);

        int count = 0;
        for (Paper paper : papers) {
            String filename = sanitizeFilename(paper.getTitle()) + ".md";
            Path file = dir.resolve(filename);
            String content = toMarkdown(paper);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            count++;
        }
        return count;
    }

    private String toMarkdown(Paper paper) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(paper.getTitle()).append("\n\n");
        sb.append("- **作者**: ").append(paper.getAuthors()).append("\n");
        sb.append("- **年份**: ").append(paper.getYear()).append("\n");
        sb.append("- **来源**: ").append(paper.getSource()).append("\n");
        sb.append("- **DOI**: ").append(paper.getDoi()).append("\n");
        sb.append("- **arXiv**: ").append(paper.getArxivId()).append("\n");
        sb.append("- **URL**: ").append(paper.getSourceUrl()).append("\n\n");
        sb.append("## 摘要\n\n").append(paper.getAbstractText()).append("\n\n");
        sb.append("## BibTeX\n\n").append("```bibtex\n").append(bibTeXExporter.export(paper)).append("```\n");
        return sb.toString();
    }

    private String sanitizeFilename(String title) {
        if (title == null || title.isBlank()) {
            return "untitled";
        }
        String sanitized = title.replaceAll("[^\\w\\u4e00-\\u9fa5\\- ]", "").trim();
        if (sanitized.length() > 80) {
            sanitized = sanitized.substring(0, 80);
        }
        return sanitized.isBlank() ? "untitled" : sanitized;
    }
}
