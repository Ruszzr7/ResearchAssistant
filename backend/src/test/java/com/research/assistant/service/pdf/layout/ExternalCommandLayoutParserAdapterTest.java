package com.research.assistant.service.pdf.layout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalCommandLayoutParserAdapterTest {

    @TempDir
    Path tempDir;

    private SettingsService settings;
    private ExternalCommandLayoutParserAdapter adapter;

    @BeforeEach
    void setUp() {
        settings = mock(SettingsService.class);
        adapter = new ExternalCommandLayoutParserAdapter(settings,
                new ExternalLayoutPayloadNormalizer(new ObjectMapper()));
    }

    @Test
    void shouldExecuteQuotedTemplateAndReadOutputFile() throws Exception {
        Path pdf = Files.writeString(tempDir.resolve("input paper.pdf"), "%PDF-1.4");
        Path script = outputScript();
        when(settings.getValue(ExternalCommandLayoutParserAdapter.ENABLED_KEY)).thenReturn("true");
        when(settings.getValue(ExternalCommandLayoutParserAdapter.PROVIDER_KEY)).thenReturn("MINERU");
        when(settings.getValue(ExternalCommandLayoutParserAdapter.COMMAND_KEY))
                .thenReturn('"' + script.toString() + '"' + " {input} {output}");

        ExternalLayoutParseResult result = adapter.parse(5L, pdf.toFile(), "f".repeat(64));

        assertThat(result.success()).isTrue();
        assertThat(result.artifact().blocks()).singleElement().satisfies(block -> {
            assertThat(block.page()).isEqualTo(1);
            assertThat(block.text()).isEqualTo("Grounded external evidence.");
        });
        assertThat(adapter.policyFingerprint()).startsWith("mineru-");
    }

    @Test
    void shouldStayDisabledWithoutExplicitSwitch() {
        when(settings.getValue(ExternalCommandLayoutParserAdapter.ENABLED_KEY)).thenReturn("false");
        when(settings.getValue(ExternalCommandLayoutParserAdapter.COMMAND_KEY)).thenReturn("mineru");

        assertThat(adapter.enabled()).isFalse();
        assertThat(adapter.policyFingerprint()).isEqualTo("off");
    }

    @Test
    void tokenizerShouldPreserveQuotedExecutableAndArguments() {
        assertThat(ExternalCommandLayoutParserAdapter.tokenize(
                "\"C:\\Program Files\\adapter.exe\" --input \"{input}\" --output {output}"))
                .containsExactly("C:\\Program Files\\adapter.exe", "--input", "{input}",
                        "--output", "{output}");
    }

    private Path outputScript() throws Exception {
        String json = "{\"pageCount\":1,\"pages\":[{\"page\":1,\"width\":1000,\"height\":1000}],"
                + "\"blocks\":[{\"page\":1,\"type\":\"text\",\"bbox\":[100,100,900,160],"
                + "\"text\":\"Grounded external evidence.\",\"confidence\":0.9}]}";
        boolean windows = System.getProperty("os.name").toLowerCase().contains("windows");
        if (windows) {
            Path script = tempDir.resolve("layout adapter.bat");
            Files.writeString(script, "@echo off\r\n> \"%~2\" echo " + json + "\r\n");
            return script;
        }
        Path script = tempDir.resolve("layout adapter.sh");
        Files.writeString(script, "#!/bin/sh\nprintf '%s' '"
                + json.replace("'", "'\\''") + "' > \"$2\"\n");
        script.toFile().setExecutable(true);
        return script;
    }
}
