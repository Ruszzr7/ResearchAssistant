package com.research.assistant.service.pdf.layout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves a stored relative PDF path without allowing traversal outside the configured root. */
@Component
public class PaperPdfFileResolver {

    private final Path storageRoot;

    public PaperPdfFileResolver(@Value("${app.storage.pdf-dir:./data/papers}") String pdfStorageDir) {
        Path configured = Path.of(pdfStorageDir);
        if (!configured.isAbsolute()) {
            configured = Path.of(System.getProperty("user.dir")).resolve(configured);
        }
        storageRoot = configured.toAbsolutePath().normalize();
    }

    public File resolveRequired(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw new IllegalArgumentException("论文尚未上传 PDF");
        }
        Path resolved = storageRoot.resolve(storedPath).normalize();
        if (!resolved.startsWith(storageRoot) || !Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("论文 PDF 不存在");
        }
        try {
            Path realRoot = Files.exists(storageRoot) ? storageRoot.toRealPath() : storageRoot;
            Path realFile = resolved.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                throw new IllegalArgumentException("论文 PDF 路径不合法");
            }
            return realFile.toFile();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("论文 PDF 不存在", e);
        }
    }

    Path storageRoot() {
        return storageRoot;
    }
}
