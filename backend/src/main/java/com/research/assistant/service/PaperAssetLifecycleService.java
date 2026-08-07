package com.research.assistant.service;

import com.research.assistant.entity.Paper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

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

    private static final class AssetDeletionException extends RuntimeException {
        private AssetDeletionException(IOException cause) {
            super(cause);
        }
    }
}
