package com.research.assistant.service.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.Paper;
import com.research.assistant.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ObsidianSyncServiceTest {

    private final SettingsService settingsService = mock(SettingsService.class);
    private final ObsidianSyncService service = new ObsidianSyncService(settingsService, new BibTeXExporter(new ObjectMapper()));

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteMarkdownFilesToVault() throws Exception {
        when(settingsService.getValue("obsidian_vault_path")).thenReturn(tempDir.toString());

        Paper paper = new Paper();
        paper.setTitle("Test Paper!");
        paper.setAuthors("[{\"name\":\"A B\"}]");
        paper.setYear(2024);
        paper.setAbstractText("abstract");

        int count = service.sync(List.of(paper));

        assertThat(count).isEqualTo(1);
        Path file = tempDir.resolve("research-assistant/Test Paper.md");
        assertThat(Files.exists(file)).isTrue();
        String content = Files.readString(file);
        assertThat(content).contains("# Test Paper!");
        assertThat(content).contains("```bibtex");
    }
}
