package com.research.assistant.service.pdf.figure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.service.SettingsService;
import com.research.assistant.service.pdf.FigureRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 通过外部命令提取图表区域。
 * <p>
 * 命令应输出 JSON 数组：
 * [{"page":1,"x":10,"y":20,"width":100,"height":80,"caption":"Fig 1","imagePath":"...","type":"FIGURE"}, ...]
 * type 可选 FIGURE / TABLE，缺省时解析为 FIGURE。
 */
@Component
public class ExternalCommandFigureExtractor implements FigureExtractor {

    private static final Logger log = LoggerFactory.getLogger(ExternalCommandFigureExtractor.class);

    private static final String ENABLED_KEY = "figure_extractor_enabled";
    private static final String COMMAND_KEY = "figure_extractor_command";

    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    public ExternalCommandFigureExtractor(SettingsService settingsService, ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<FigureRegion> extract(File pdfFile) {
        String enabled = settingsService.getValue(ENABLED_KEY);
        if (!"true".equalsIgnoreCase(enabled)) {
            return Collections.emptyList();
        }
        String command = settingsService.getValue(COMMAND_KEY);
        if (command == null || command.isBlank()) {
            return Collections.emptyList();
        }
        if (!isSafePdfFile(pdfFile)) {
            return Collections.emptyList();
        }

        List<String> args = new ArrayList<>(Arrays.asList(command.trim().split("\\s+")));
        args.add(pdfFile.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Collections.emptyList();
            }
            if (process.exitValue() != 0) {
                return Collections.emptyList();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return objectMapper.readValue(output, new TypeReference<List<FigureRegion>>() {});
        } catch (Exception e) {
            log.warn("图表提取命令失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean isSafePdfFile(File file) {
        if (file == null) return false;
        try {
            Path path = file.toPath().toRealPath().normalize();
            String name = path.getFileName().toString().toLowerCase();
            if (!name.endsWith(".pdf")) return false;
            Path cwd = Path.of(System.getProperty("user.dir")).toRealPath().normalize();
            Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath().normalize();
            return path.startsWith(cwd) || path.startsWith(tmp);
        } catch (Exception e) {
            return false;
        }
    }
}
