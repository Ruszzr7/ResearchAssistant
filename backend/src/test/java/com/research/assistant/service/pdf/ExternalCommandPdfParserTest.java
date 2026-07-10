package com.research.assistant.service.pdf;

import com.research.assistant.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link ExternalCommandPdfParser} 解析器选择策略单元测试。
 */
class ExternalCommandPdfParserTest {

    private SettingsService settingsService;
    private PdfBoxPdfParser fallback;
    private ExternalCommandPdfParser parser;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        fallback = mock(PdfBoxPdfParser.class);
        parser = new ExternalCommandPdfParser(settingsService, fallback);
    }

    @Test
    void providerPdfBoxShouldUseFallback() {
        when(settingsService.getValue("pdf_parser_provider")).thenReturn("PDFBOX");
        when(settingsService.getValue("pdf_parser_external_command")).thenReturn("marker_single {input}");

        File pdf = createTempPdf("fallback.pdf");
        when(fallback.parse(pdf)).thenReturn(PdfParseResult.success("pdfbox text", 1));

        PdfParseResult result = parser.parse(pdf);

        assertThat(result.text()).isEqualTo("pdfbox text");
        verify(fallback).parse(pdf);
        verifyNoMoreInteractions(fallback);
    }

    @Test
    void providerExternalWithBlankCommandShouldFallback() {
        when(settingsService.getValue("pdf_parser_provider")).thenReturn("EXTERNAL");
        when(settingsService.getValue("pdf_parser_external_command")).thenReturn("   ");

        File pdf = createTempPdf("blank.pdf");
        when(fallback.parse(pdf)).thenReturn(PdfParseResult.success("pdfbox text", 1));

        PdfParseResult result = parser.parse(pdf);

        assertThat(result.text()).isEqualTo("pdfbox text");
        verify(fallback).parse(pdf);
    }

    @Test
    void providerExternalWithConfiguredCommandShouldExecuteExternal() throws IOException {
        File pdf = createTempPdf("external.pdf");
        String command = createEchoScript();
        when(settingsService.getValue("pdf_parser_provider")).thenReturn("EXTERNAL");
        when(settingsService.getValue("pdf_parser_external_command")).thenReturn(command);

        PdfParseResult result = parser.parse(pdf);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("%PDF-1.4 dummy");
        verify(fallback, never()).parse(any(File.class));
    }

    @Test
    void defaultProviderShouldUseExternalWhenCommandConfigured() throws IOException {
        File pdf = createTempPdf("default-external.pdf");
        String command = createEchoScript();
        when(settingsService.getValue("pdf_parser_provider")).thenReturn(null);
        when(settingsService.getValue("pdf_parser_external_enabled")).thenReturn(null);
        when(settingsService.getValue("pdf_parser_external_command")).thenReturn(command);

        PdfParseResult result = parser.parse(pdf);

        assertThat(result.success()).isTrue();
        assertThat(result.text()).contains("%PDF-1.4 dummy");
    }

    @Test
    void legacyEnabledSwitchShouldStillTriggerExternal() {
        File pdf = createTempPdf("legacy.pdf");
        when(settingsService.getValue("pdf_parser_provider")).thenReturn(null);
        when(settingsService.getValue("pdf_parser_external_enabled")).thenReturn("true");
        when(settingsService.getValue("pdf_parser_external_command")).thenReturn("non-existent-command-xyz");
        when(fallback.parse(pdf)).thenReturn(PdfParseResult.success("pdfbox text", 1));

        PdfParseResult result = parser.parse(pdf);

        assertThat(result.text()).isEqualTo("pdfbox text");
        verify(fallback).parse(pdf);
    }

    @Test
    void externalFailureShouldFallbackToPdfBox() {
        when(settingsService.getValue("pdf_parser_provider")).thenReturn("EXTERNAL");
        when(settingsService.getValue("pdf_parser_external_command")).thenReturn("non-existent-command-xyz");

        File pdf = createTempPdf("fail.pdf");
        when(fallback.parse(pdf)).thenReturn(PdfParseResult.success("pdfbox text", 1));

        PdfParseResult result = parser.parse(pdf);

        assertThat(result.text()).isEqualTo("pdfbox text");
        verify(fallback).parse(pdf);
    }

    @Test
    void countPagesShouldAlwaysUseFallback() {
        File pdf = createTempPdf("pages.pdf");
        when(fallback.countPages(pdf)).thenReturn(42);

        int pages = parser.countPages(pdf);

        assertThat(pages).isEqualTo(42);
        verify(fallback).countPages(pdf);
    }

    private File createTempPdf(String name) {
        try {
            Path path = tempDir.resolve(name);
            Files.writeString(path, "%PDF-1.4 dummy");
            return path.toFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String createEchoScript() throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("windows");
        if (windows) {
            Path script = tempDir.resolve("echo.bat");
            Files.writeString(script, "@echo off\r\ntype %1\r\n");
            return script.toAbsolutePath().toString();
        } else {
            Path script = tempDir.resolve("echo.sh");
            Files.writeString(script, "#!/bin/sh\ncat \"$1\"\n");
            script.toFile().setExecutable(true);
            return script.toAbsolutePath().toString();
        }
    }
}
