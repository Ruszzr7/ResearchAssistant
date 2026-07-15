package com.research.assistant.service.pdf.layout;

import com.research.assistant.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Runs an explicitly configured GROBID/MinerU adapter without invoking a shell.
 *
 * <p>The command may use {@code {input}} and {@code {output}} placeholders. If
 * {@code {output}} is absent, structured payload is read from stdout. GROBID
 * must emit coordinate-bearing TEI; MinerU may emit content-list/layout JSON.</p>
 */
@Component
public class ExternalCommandLayoutParserAdapter implements ExternalLayoutParserAdapter {

    static final String ENABLED_KEY = "pdf_layout_fallback_enabled";
    static final String PROVIDER_KEY = "pdf_layout_fallback_provider";
    static final String COMMAND_KEY = "pdf_layout_fallback_command";
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final long MAX_PAYLOAD_BYTES = 25L * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(ExternalCommandLayoutParserAdapter.class);

    private final SettingsService settingsService;
    private final ExternalLayoutPayloadNormalizer normalizer;

    public ExternalCommandLayoutParserAdapter(SettingsService settingsService,
                                              ExternalLayoutPayloadNormalizer normalizer) {
        this.settingsService = settingsService;
        this.normalizer = normalizer;
    }

    @Override
    public boolean enabled() {
        return "true".equalsIgnoreCase(settingsService.getValue(ENABLED_KEY))
                && !value(COMMAND_KEY).isBlank();
    }

    @Override
    public String policyFingerprint() {
        if (!enabled()) return "off";
        String provider = provider().toLowerCase(Locale.ROOT);
        return provider + "-" + shortHash(value(COMMAND_KEY));
    }

    @Override
    public ExternalLayoutParseResult parse(Long paperId, File file, String documentHash) {
        String provider = provider();
        if (!enabled()) return ExternalLayoutParseResult.failure(provider, "FALLBACK_NOT_CONFIGURED");
        if (!isSafePdfFile(file)) return ExternalLayoutParseResult.failure(provider, "UNSAFE_PDF_PATH");

        Path stdout = null;
        Path stderr = null;
        Path output = null;
        try {
            stdout = Files.createTempFile("ra-layout-stdout-", ".txt");
            stderr = Files.createTempFile("ra-layout-stderr-", ".txt");
            output = Files.createTempFile("ra-layout-output-",
                    "GROBID".equals(provider) ? ".xml" : ".json");
            String template = value(COMMAND_KEY);
            boolean hasInput = template.contains("{input}");
            boolean hasOutput = template.contains("{output}");
            String outputPath = output.toAbsolutePath().toString();
            List<String> arguments = new ArrayList<>();
            for (String token : tokenize(template)) {
                arguments.add(token.replace("{input}", file.getAbsolutePath())
                        .replace("{output}", outputPath));
            }
            if (!hasInput) arguments.add(file.getAbsolutePath());
            if (arguments.isEmpty()) {
                return ExternalLayoutParseResult.failure(provider, "FALLBACK_COMMAND_EMPTY");
            }

            ProcessBuilder processBuilder = new ProcessBuilder(arguments);
            processBuilder.redirectOutput(stdout.toFile());
            processBuilder.redirectError(stderr.toFile());
            Process process = processBuilder.start();
            boolean finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ExternalLayoutParseResult.failure(provider, "FALLBACK_TIMEOUT");
            }
            if (process.exitValue() != 0) {
                return ExternalLayoutParseResult.failure(provider, "FALLBACK_PROCESS_FAILED");
            }
            Path payloadPath = hasOutput && Files.size(output) > 0 ? output : stdout;
            long size = Files.size(payloadPath);
            if (size <= 0) return ExternalLayoutParseResult.failure(provider, "FALLBACK_EMPTY_OUTPUT");
            if (size > MAX_PAYLOAD_BYTES) {
                return ExternalLayoutParseResult.failure(provider, "FALLBACK_OUTPUT_TOO_LARGE");
            }
            String payload = Files.readString(payloadPath, StandardCharsets.UTF_8);
            PaperLayoutArtifact artifact = normalizer.normalize(
                    provider, payload, paperId, documentHash);
            return ExternalLayoutParseResult.success(artifact, provider.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("external_layout_invalid provider={} code=FALLBACK_INVALID_LAYOUT", provider);
            return ExternalLayoutParseResult.failure(provider, "FALLBACK_INVALID_LAYOUT");
        } catch (Exception e) {
            log.warn("external_layout_failed provider={} error={}", provider,
                    e.getClass().getSimpleName());
            return ExternalLayoutParseResult.failure(provider, "FALLBACK_EXECUTION_FAILED");
        } finally {
            deleteQuietly(stdout);
            deleteQuietly(stderr);
            deleteQuietly(output);
        }
    }

    static List<String> tokenize(String command) {
        List<String> result = new ArrayList<>();
        if (command == null || command.isBlank()) return result;
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < command.length(); index++) {
            char value = command.charAt(index);
            if ((value == '\'' || value == '"')) {
                if (quote == 0) {
                    quote = value;
                    continue;
                }
                if (quote == value) {
                    quote = 0;
                    continue;
                }
            }
            if (Character.isWhitespace(value) && quote == 0) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(value);
            }
        }
        if (quote != 0) throw new IllegalArgumentException("unclosed command quote");
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    private String provider() {
        String provider = value(PROVIDER_KEY).toUpperCase(Locale.ROOT);
        return switch (provider) {
            case "GROBID", "MINERU" -> provider;
            default -> "AUTO";
        };
    }

    private String value(String key) {
        String value = settingsService.getValue(key);
        return value == null ? "" : value.trim();
    }

    private boolean isSafePdfFile(File file) {
        if (file == null) return false;
        try {
            Path path = file.toPath().toRealPath().normalize();
            if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) return false;
            Path cwd = Path.of(System.getProperty("user.dir")).toRealPath().normalize();
            Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath().normalize();
            return path.startsWith(cwd) || path.startsWith(tmp);
        } catch (Exception e) {
            return false;
        }
    }

    private String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) { }
    }
}
