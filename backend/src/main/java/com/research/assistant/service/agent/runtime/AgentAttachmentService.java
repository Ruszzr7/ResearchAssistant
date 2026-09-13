package com.research.assistant.service.agent.runtime;

import com.research.assistant.entity.AgentAttachmentRecord;
import com.research.assistant.entity.AgentTurnRecord;
import com.research.assistant.mapper.AgentAttachmentMapper;
import com.research.assistant.mapper.AgentTurnMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentAttachmentService {

    static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final long MAX_TEXT_BYTES = 1L * 1024 * 1024;
    static final long MAX_TURN_ATTACHMENT_BYTES = 10L * 1024 * 1024;
    static final int MAX_FORMULA_TEXT_CHARACTERS = 2_000;
    private static final Set<String> ALLOWED_MEDIA_TYPES = Set.of(
            "application/pdf", "text/plain", "text/markdown", "text/csv", "application/json",
            "application/xml", "application/yaml", "application/x-tex", "application/x-latex",
            "image/png", "image/jpeg", "image/webp", "application/msword",
            "application/vnd.ms-word", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final AgentAttachmentMapper attachmentMapper;
    private final AgentTurnMapper turnMapper;
    private final Path storageRoot;

    public AgentAttachmentService(AgentAttachmentMapper attachmentMapper,
                                  AgentTurnMapper turnMapper,
                                  @Value("${app.storage.agent-attachments-dir:../data/agent-attachments}") String storageDir) {
        this.attachmentMapper = attachmentMapper;
        this.turnMapper = turnMapper;
        Path configured = Paths.get(storageDir);
        if (!configured.isAbsolute()) configured = Paths.get(System.getProperty("user.dir")).resolve(configured);
        this.storageRoot = configured.toAbsolutePath().normalize();
    }

    @Transactional
    public AgentAttachmentRecord store(long turnRecordId,
                                       String kind,
                                       String originalName,
                                       String mediaType,
                                       byte[] content) {
        AgentTurnRecord turn = turnMapper.selectById(turnRecordId);
        if (turn == null) throw new IllegalArgumentException("agent turn not found: " + turnRecordId);
        return storeRecord(turn.getSessionId(), turnRecordId, kind, originalName, mediaType, content);
    }

    @Transactional
    public AgentAttachmentRecord stage(long sessionId, String kind, String originalName,
                                       String mediaType, byte[] content) {
        if (turnMapper.lockSession(sessionId) == null) throw new IllegalArgumentException("research session not found");
        return storeRecord(sessionId, null, kind, originalName, mediaType, content);
    }

    @Transactional
    public List<AgentAttachmentRecord> claim(long turnRecordId, long sessionId, List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return List.of();
        return attachmentIds.stream().distinct().map(id -> {
            AgentAttachmentRecord record = requireForSession(sessionId, id);
            if (attachmentMapper.claim(id, sessionId, turnRecordId) != 1) {
                throw new IllegalStateException("attachment could not be claimed: " + id);
            }
            record.setTurnId(turnRecordId);
            return record;
        }).toList();
    }

    public List<AgentAttachmentRecord> requireForSession(long sessionId, List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return List.of();
        return attachmentIds.stream().distinct().map(id -> requireForSession(sessionId, id)).toList();
    }

    public List<AgentAttachmentRecord> requireForTurn(long sessionId, List<String> attachmentIds) {
        List<AgentAttachmentRecord> records = requireForSession(sessionId, attachmentIds);
        if (records.size() > 2) throw new IllegalArgumentException("每条消息最多添加 2 个附件");
        long totalBytes = records.stream().mapToLong(record -> record.getSizeBytes() == null
                ? 0 : record.getSizeBytes()).sum();
        if (totalBytes > MAX_TURN_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("附件总大小不能超过 10 MB");
        }
        return records;
    }

    private AgentAttachmentRecord requireForSession(long sessionId, String id) {
        AgentAttachmentRecord record = attachmentMapper.selectByAttachmentId(id);
        if (record == null || record.getSessionId() == null || record.getSessionId() != sessionId) {
            throw new IllegalArgumentException("attachment does not belong to this conversation: " + id);
        }
        return record;
    }

    private AgentAttachmentRecord storeRecord(long sessionId, Long turnRecordId,
                                                String kind, String originalName,
                                                String mediaType, byte[] content) {
        if (content == null || content.length == 0) throw new IllegalArgumentException("attachment content is required");
        String normalizedMediaType = normalizeMediaType(mediaType, originalName);
        String normalizedKind = requireText(kind, "kind").toUpperCase(Locale.ROOT);
        if (!Set.of("FILE", "FORMULA_IMAGE", "FORMULA_TEXT", "SELECTION_EXPORT").contains(normalizedKind)) {
            throw new IllegalArgumentException("unsupported attachment kind: " + normalizedKind);
        }
        long maxBytes = normalizedMediaType.startsWith("image/") ? MAX_IMAGE_BYTES
                : (isTextMediaType(normalizedMediaType) ? MAX_TEXT_BYTES : MAX_ATTACHMENT_BYTES);
        if (content.length > maxBytes) throw new IllegalArgumentException("附件过大");
        if ("FORMULA_TEXT".equals(normalizedKind)) {
            String formula = new String(content, StandardCharsets.UTF_8).trim();
            if (formula.length() > MAX_FORMULA_TEXT_CHARACTERS) {
                throw new IllegalArgumentException("附件内容过长");
            }
        }

        String attachmentId = UUID.randomUUID().toString();
        String suffix = extension(normalizedMediaType);
        String owner = turnRecordId == null ? "staged/session-" + sessionId : "turn-" + turnRecordId;
        Path turnDirectory = resolveInsideRoot(owner);
        Path file = resolveInsideRoot(owner + "/" + attachmentId + suffix);
        try {
            Files.createDirectories(turnDirectory);
            Files.write(file, content);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to store agent attachment", exception);
        }
        cleanupOnRollback(file);

        AgentAttachmentRecord record = new AgentAttachmentRecord();
        record.setAttachmentId(attachmentId);
        record.setTurnId(turnRecordId);
        record.setSessionId(sessionId);
        record.setAttachmentKind(normalizedKind);
        record.setMediaType(normalizedMediaType);
        record.setOriginalName(sanitizeName(originalName));
        record.setStoragePath(storageRoot.relativize(file).toString().replace('\\', '/'));
        record.setContentSha256(sha256(content));
        record.setSizeBytes((long) content.length);
        String preview = extractPreview(normalizedMediaType, content);
        record.setPreviewText(preview);
        // Text/LaTeX can be injected directly. Binary paper material remains
        // pending until the document API has seen the original bytes.
        record.setExtractionStatus(preview == null ? "PENDING" : "READY");
        attachmentMapper.insert(record);
        return record;
    }

    public Path resolveContent(AgentAttachmentRecord record) {
        if (record == null || record.getStoragePath() == null) {
            throw new IllegalArgumentException("attachment has no stored content");
        }
        Path resolved = resolveInsideRoot(record.getStoragePath());
        if (!Files.isRegularFile(resolved)) throw new IllegalStateException("attachment content is missing");
        return resolved;
    }

    /** 会话删除成功后清理该会话的暂存附件和已归档附件物理文件。 */
    public void deleteAfterCommitBySession(long sessionId) {
        List<AgentAttachmentRecord> snapshot = attachmentMapper.selectBySessionId(sessionId);
        if (snapshot == null || snapshot.isEmpty()) return;
        Runnable deletion = () -> snapshot.forEach(this::deleteStoredFile);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deletion.run();
                }
            });
        } else {
            deletion.run();
        }
    }

    @Transactional
    public void updateExtraction(AgentAttachmentRecord record, String status,
                                 String previewText, String metadataJson) {
        if (record == null || record.getAttachmentId() == null || record.getSessionId() == null) {
            throw new IllegalArgumentException("attachment identity is required");
        }
        String normalizedText = previewText == null ? null : previewText.trim();
        attachmentMapper.updateExtraction(record.getAttachmentId(), record.getSessionId(),
                requireText(status, "extraction status"), normalizedText, metadataJson);
        record.setExtractionStatus(status);
        record.setPreviewText(normalizedText);
        record.setMetadataJson(metadataJson);
    }

    private Path resolveInsideRoot(String relative) {
        Path resolved = storageRoot.resolve(relative).normalize();
        if (!resolved.startsWith(storageRoot)) throw new IllegalArgumentException("attachment path escapes storage root");
        return resolved;
    }

    private void deleteStoredFile(AgentAttachmentRecord record) {
        if (record == null || record.getStoragePath() == null || record.getStoragePath().isBlank()) return;
        try {
            Files.deleteIfExists(resolveInsideRoot(record.getStoragePath()));
        } catch (Exception exception) {
            // 文件清理失败不能影响已经成功提交的会话删除。
        }
    }

    private static String normalizeMediaType(String mediaType, String originalName) {
        String value = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        int separator = value.indexOf(';');
        if (separator >= 0) value = value.substring(0, separator).trim();
        String inferred = inferMediaType(originalName);
        if (value.isBlank() || "application/octet-stream".equals(value)) value = inferred;
        if ("image/jpg".equals(value)) value = "image/jpeg";
        if ("application/vnd.ms-word".equals(value)) value = "application/msword";
        if (value.isBlank()) throw new IllegalArgumentException("mediaType is required");
        if (!ALLOWED_MEDIA_TYPES.contains(value)) throw new IllegalArgumentException("unsupported attachment media type");
        return value;
    }

    private static String inferMediaType(String originalName) {
        if (originalName == null || originalName.isBlank()) return "";
        String name = originalName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return switch (name.substring(dot + 1)) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "txt", "log" -> "text/plain";
            case "md", "markdown" -> "text/markdown";
            case "csv" -> "text/csv";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "yaml", "yml" -> "application/yaml";
            case "tex" -> "application/x-tex";
            default -> "";
        };
    }

    private static String sanitizeName(String name) {
        if (name == null || name.isBlank()) return null;
        String base = Paths.get(name).getFileName().toString().replaceAll("[\\r\\n]", "");
        return base.length() <= 255 ? base : base.substring(0, 255);
    }

    private static String extension(String mediaType) {
        return switch (mediaType) {
            case "application/pdf" -> ".pdf";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "text/plain" -> ".txt";
            case "text/markdown" -> ".md";
            case "text/csv" -> ".csv";
            case "application/json" -> ".json";
            case "application/xml" -> ".xml";
            case "application/yaml" -> ".yaml";
            case "application/x-tex", "application/x-latex" -> ".tex";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }

    private static String extractPreview(String mediaType, byte[] content) {
        try {
            String value;
            if (isTextMediaType(mediaType)) {
                value = new String(content, StandardCharsets.UTF_8);
            } else return null;
            value = value.replace("\u0000", "").trim();
            if (value.length() > 3_000) throw new IllegalArgumentException("附件内容过长");
            return value.isEmpty() ? null : value;
        } catch (IllegalArgumentException limit) {
            throw limit;
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static boolean isTextMediaType(String mediaType) {
        return mediaType.startsWith("text/") || Set.of("application/json", "application/xml",
                "application/yaml", "application/x-tex", "application/x-latex").contains(mediaType);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static void cleanupOnRollback(Path file) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException ignored) {
                        // A later lifecycle cleanup can remove the orphan; never mask the database failure.
                    }
                }
            }
        });
    }
}
