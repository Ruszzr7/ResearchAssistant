package com.research.assistant.service.pdf.formula;

import com.research.assistant.service.SettingsService;
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
 * 通过外部 LaTeX-OCR 命令提取公式。
 * <p>
 * 命令示例（用户需本地安装 pix2tex 等）：
 * `python -m pix2tex --pdf {pdfPath}`
 * 输出每行一个 LaTeX 公式。
 */
@Component
public class ExternalLatexOcrFormulaExtractor implements FormulaExtractor {

    private static final Logger log = LoggerFactory.getLogger(ExternalLatexOcrFormulaExtractor.class);

    private static final String ENABLED_KEY = "formula_extractor_enabled";
    private static final String COMMAND_KEY = "formula_extractor_command";

    private final SettingsService settingsService;

    public ExternalLatexOcrFormulaExtractor(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public List<String> extract(File pdfFile) {
        String enabled = settingsService.getValue(ENABLED_KEY);
        if (!"true".equalsIgnoreCase(enabled)) {
            return Collections.emptyList();
        }
        String command = settingsService.getValue(COMMAND_KEY);
        if (command == null || command.isBlank()) {
            return Collections.emptyList();
        }
        if (!isSafePdfFile(pdfFile)) {
            log.warn("公式提取：PDF 文件路径不合法");
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
            List<String> formulas = new ArrayList<>();
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isBlank()) formulas.add(trimmed);
            }
            return formulas;
        } catch (Exception e) {
            log.warn("公式提取失败: {}", e.getMessage());
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
