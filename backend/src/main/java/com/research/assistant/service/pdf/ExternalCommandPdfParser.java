package com.research.assistant.service.pdf;

import com.research.assistant.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 外部命令 PDF 解析器 —— 调用用户本地安装的 Marker / MinerU / Grobid 等工具。
 * <p>
 * 默认优先使用 PDFBox；当用户在设置中显式选择 "EXTERNAL" 或配置了外部命令时，
 * 会尝试调用外部解析器。执行失败自动回退到 PDFBox。
 */
@Component
public class ExternalCommandPdfParser implements PdfParser {

    private static final Logger log = LoggerFactory.getLogger(ExternalCommandPdfParser.class);

    private static final String PROVIDER_KEY = "pdf_parser_provider";
    private static final String ENABLED_KEY = "pdf_parser_external_enabled";
    private static final String COMMAND_KEY = "pdf_parser_external_command";
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final SettingsService settingsService;
    private final PdfBoxPdfParser fallback;

    public ExternalCommandPdfParser(SettingsService settingsService, PdfBoxPdfParser fallback) {
        this.settingsService = settingsService;
        this.fallback = fallback;
    }

    @Override
    public int countPages(File file) {
        // 外部命令通常不返回可靠页数，统一回退到 PDFBox
        return fallback.countPages(file);
    }

    @Override
    public PdfParseResult parse(File file) {
        return parseWithFallback(file, null);
    }

    @Override
    public PdfParseResult parseFirstPages(File file, int maxPages) {
        return parseWithFallback(file, maxPages);
    }

    private PdfParseResult parseWithFallback(File file, Integer maxPages) {
        if (!useExternal()) {
            return maxPages != null ? fallback.parseFirstPages(file, maxPages) : fallback.parse(file);
        }
        PdfParseResult result = parseExternal(file, maxPages);
        if (result.success()) {
            return result;
        }
        log.warn("外部 PDF 解析失败，回退到 PDFBox: {}", result.error());
        return maxPages != null ? fallback.parseFirstPages(file, maxPages) : fallback.parse(file);
    }

    private PdfParseResult parseExternal(File file, Integer maxPages) {
        String command = settingsService.getValue(COMMAND_KEY);
        if (command == null || command.isBlank()) {
            return PdfParseResult.failure("外部解析命令未配置");
        }
        if (!isSafePdfFile(file)) {
            return PdfParseResult.failure("PDF 文件路径不合法");
        }

        List<String> args = new ArrayList<>(Arrays.asList(command.trim().split("\\s+")));
        args.add(file.getAbsolutePath());
        if (maxPages != null && maxPages > 0) {
            args.add("--max-pages=" + maxPages);
        }

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            boolean finished = process.waitFor(TIMEOUT.getSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return PdfParseResult.failure("外部解析命令超时 (>" + TIMEOUT.getSeconds() + "s)");
            }
            int exitCode = process.exitValue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (exitCode != 0) {
                return PdfParseResult.failure("外部解析命令退出码 " + exitCode);
            }
            String text = output.trim();
            if (text.length() > 15 * 1024 * 1024) {
                text = text.substring(0, 15 * 1024 * 1024);
            }
            return PdfParseResult.success(text, -1);
        } catch (Exception e) {
            return PdfParseResult.failure("外部解析命令执行失败");
        }
    }

    /**
     * 是否应使用外部解析器。
     * <p>
     * 优先级：
     * 1. 若 {@code pdf_parser_provider} 显式设置为 PDFBOX/EXTERNAL，直接按设置执行。
     * 2. 否则保留旧开关 {@code pdf_parser_external_enabled} 的行为。
     * 3. 若以上均未设置，但已配置外部命令，则默认使用 EXTERNAL（布局恢复优先）。
     * 4. 否则使用 PDFBox。
     */
    private boolean useExternal() {
        String provider = settingsService.getValue(PROVIDER_KEY);
        if ("PDFBOX".equalsIgnoreCase(provider)) {
            return false;
        }
        if ("EXTERNAL".equalsIgnoreCase(provider)) {
            return true;
        }
        String enabled = settingsService.getValue(ENABLED_KEY);
        if ("true".equalsIgnoreCase(enabled)) {
            return true;
        }
        String command = settingsService.getValue(COMMAND_KEY);
        return command != null && !command.isBlank();
    }

    private boolean isSafePdfFile(File file) {
        if (file == null) return false;
        try {
            Path path = file.toPath().toRealPath().normalize();
            String name = path.getFileName().toString().toLowerCase();
            if (!name.endsWith(".pdf")) return false;
            // 禁止路径遍历：确保文件在项目目录或临时目录下
            Path cwd = Path.of(System.getProperty("user.dir")).toRealPath().normalize();
            Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath().normalize();
            return path.startsWith(cwd) || path.startsWith(tmp);
        } catch (Exception e) {
            return false;
        }
    }
}
