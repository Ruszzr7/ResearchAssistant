package com.research.assistant.service;

import com.research.assistant.entity.Paper;
import com.research.assistant.common.PaperFileValidationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Owns the on-disk lifecycle of files derived from an imported paper.
 * Database rows are deleted transactionally; files are removed only after that
 * transaction commits so a failed database delete never leaves a live paper
 * without its source PDF.
 */
@Service
public class PaperAssetLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(PaperAssetLifecycleService.class);

    private final Path pdfRoot;
    private final Path figuresRoot;

    public PaperAssetLifecycleService(
            @Value("${app.storage.pdf-dir:../data/papers}") String pdfDir,
            @Value("${app.storage.figures-dir:../data/figures}") String figuresDir) {
        this.pdfRoot = resolveRoot(pdfDir);
        this.figuresRoot = resolveRoot(figuresDir);
    }

    public void deleteAfterCommit(Collection<Paper> papers) {
        List<Paper> snapshot = papers == null
                ? List.of()
                : papers.stream().filter(java.util.Objects::nonNull).toList();
        if (snapshot.isEmpty()) return;

        Runnable deletion = () -> snapshot.forEach(this::deleteAssets);
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

    /**
     * 将上传内容写入论文目录下的临时文件，使用 PDFBox 验证后再移动为唯一正式文件。
     * 正式文件会在当前事务回滚时自动清理，避免数据库失败留下孤儿文件。
     */
    public StoredPdf storeUploadedPdf(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new PaperFileValidationException("上传文件为空");
        }

        Path temporary = null;
        try {
            Files.createDirectories(pdfRoot);
            temporary = Files.createTempFile(pdfRoot, ".upload-", ".tmp");
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }

            int pageCount;
            try (PDDocument document = Loader.loadPDF(temporary.toFile())) {
                pageCount = document.getNumberOfPages();
            } catch (InvalidPasswordException exception) {
                throw new PaperFileValidationException("加密 PDF 无法打开，请先解除密码保护");
            } catch (IOException exception) {
                throw new PaperFileValidationException("文件不是有效的 PDF，或 PDF 无法打开");
            }
            if (pageCount <= 0) {
                throw new PaperFileValidationException("PDF 文件没有可读取的页面");
            }

            String storedName = "paper-" + UUID.randomUUID() + ".pdf";
            Path stored = safeChild(pdfRoot, storedName);
            if (stored == null) {
                throw new IllegalStateException("无法创建论文文件路径");
            }
            moveIntoPlace(temporary, stored);
            temporary = null;
            cleanupOnRollback(stored);
            return new StoredPdf(storedName, pageCount);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new PaperFileValidationException("上传文件读取或保存失败");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    log.warn("Failed to delete temporary uploaded PDF: {}", temporary);
                }
            }
        }
    }

    /** 返回存储根目录内的真实 PDF，路径越界或文件不存在时返回 null。 */
    public Path resolveStoredPdf(String storedName) {
        if (storedName == null || storedName.isBlank()) return null;
        Path candidate = safeChild(pdfRoot, storedName);
        return candidate != null && Files.isRegularFile(candidate) ? candidate : null;
    }

    /** 立即删除尚未提交的正式文件；仅允许删除配置目录内的文件。 */
    public void deleteImmediately(String storedName) {
        Path candidate = safeChild(pdfRoot, storedName);
        if (candidate != null) deletePath(candidate);
    }

    private void moveIntoPlace(Path temporary, Path stored) throws IOException {
        try {
            Files.move(temporary, stored, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, stored);
        }
    }

    private void deleteAssets(Paper paper) {
        String storedName = paper.getPdfPath();
        if (storedName == null || storedName.isBlank()) return;

        Path pdf = safeChild(pdfRoot, storedName);
        if (pdf != null) deletePath(pdf);

        String fileName = Path.of(storedName).getFileName().toString();
        String baseName = fileName.replaceFirst("(?i)\\.pdf$", "");
        if (baseName.isBlank() || !Files.isDirectory(figuresRoot)) return;
        try (var entries = Files.list(figuresRoot)) {
            entries.filter(path -> path.getFileName().toString().startsWith(baseName + "_"))
                    .forEach(this::deletePath);
        } catch (IOException exception) {
            log.warn("Failed to inspect derived assets for paper {}: {}", paper.getId(), exception.getMessage());
        }
    }

    private void deletePath(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(pdfRoot) && !normalized.startsWith(figuresRoot)) {
            log.warn("Refusing to delete paper asset outside configured storage: {}", normalized);
            return;
        }
        try {
            if (Files.isDirectory(normalized)) {
                try (var paths = Files.walk(normalized)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new AssetDeletionException(exception);
                        }
                    });
                }
            } else {
                Files.deleteIfExists(normalized);
            }
        } catch (IOException | AssetDeletionException exception) {
            Throwable cause = exception instanceof AssetDeletionException && exception.getCause() != null
                    ? exception.getCause() : exception;
            log.warn("Failed to delete paper asset {}: {}", normalized, cause.getMessage());
        }
    }

    private static Path safeChild(Path root, String storedName) {
        Path candidate = root.resolve(storedName).toAbsolutePath().normalize();
        return candidate.startsWith(root) ? candidate : null;
    }

    private static Path resolveRoot(String configured) {
        Path path = Path.of(configured);
        if (!path.isAbsolute()) path = Path.of(System.getProperty("user.dir")).resolve(path);
        return path.toAbsolutePath().normalize();
    }

    private static void cleanupOnRollback(Path file) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException exception) {
                        // Do not mask the database rollback with a filesystem cleanup failure.
                    }
                }
            }
        });
    }

    public record StoredPdf(String storedName, int pageCount) {
    }

    private static final class AssetDeletionException extends RuntimeException {
        private AssetDeletionException(IOException cause) {
            super(cause);
        }
    }
}
