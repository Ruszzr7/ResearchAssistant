package com.research.assistant.service.pdf.figure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.pdf.FigureRegion;
import com.research.assistant.service.pdf.FigureRegionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link ExternalCommandFigureExtractor} 单元测试。
 */
class ExternalCommandFigureExtractorTest {

    private SettingsService settingsService;
    private ExternalCommandFigureExtractor extractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        extractor = new ExternalCommandFigureExtractor(settingsService, new ObjectMapper());
    }

    @Test
    void shouldReturnEmptyWhenDisabled() {
        when(settingsService.getValue("figure_extractor_enabled")).thenReturn("false");

        List<FigureRegion> result = extractor.extract(new File("any.pdf"));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenCommandBlank() {
        when(settingsService.getValue("figure_extractor_enabled")).thenReturn("true");
        when(settingsService.getValue("figure_extractor_command")).thenReturn("  ");

        List<FigureRegion> result = extractor.extract(new File("any.pdf"));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldParseTypedRegionsFromExternalCommand() throws IOException {
        File pdf = createTempPdf("typed.pdf");
        String script = createOutputScript("""
                [{"page":2,"x":10,"y":20,"width":100,"height":80,"caption":"Tab 1","type":"TABLE"},
                 {"page":3,"x":0,"y":0,"width":50,"height":50,"caption":"Fig 2"}]
                """);
        when(settingsService.getValue("figure_extractor_enabled")).thenReturn("true");
        when(settingsService.getValue("figure_extractor_command")).thenReturn(script);

        List<FigureRegion> result = extractor.extract(pdf);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).type()).isEqualTo(FigureRegionType.TABLE);
        assertThat(result.get(0).caption()).isEqualTo("Tab 1");
        assertThat(result.get(1).type()).isEqualTo(FigureRegionType.FIGURE);
    }

    @Test
    void shouldReturnEmptyOnInvalidJson() throws IOException {
        File pdf = createTempPdf("invalid.pdf");
        String script = createOutputScript("not json");
        when(settingsService.getValue("figure_extractor_enabled")).thenReturn("true");
        when(settingsService.getValue("figure_extractor_command")).thenReturn(script);

        List<FigureRegion> result = extractor.extract(pdf);

        assertThat(result).isEmpty();
    }

    private File createTempPdf(String name) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, "%PDF-1.4 dummy");
        return path.toFile();
    }

    private String createOutputScript(String output) throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("windows");
        String compact = output.replace("\r", "").replace("\n", "");
        if (windows) {
            Path script = tempDir.resolve("fig.bat");
            Files.writeString(script, "@echo off\r\necho " + compact + "\r\n");
            return script.toAbsolutePath().toString();
        } else {
            Path script = tempDir.resolve("fig.sh");
            Files.writeString(script, "#!/bin/sh\nprintf '%s' '" + compact.replace("'", "'\\''") + "'\n");
            script.toFile().setExecutable(true);
            return script.toAbsolutePath().toString();
        }
    }
}
