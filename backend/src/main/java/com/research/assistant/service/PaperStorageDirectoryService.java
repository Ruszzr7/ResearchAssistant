package com.research.assistant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** Manages the configured local directory containing imported PDF files. */
@Service
public class PaperStorageDirectoryService {

    private final Path storageDirectory;

    public PaperStorageDirectoryService(
            @Value("${app.storage.pdf-dir:../data/papers}") String pdfStorageDir) {
        Path configured = Paths.get(pdfStorageDir);
        if (!configured.isAbsolute()) {
            configured = Paths.get(System.getProperty("user.dir")).resolve(configured);
        }
        this.storageDirectory = configured.toAbsolutePath().normalize();
    }

    public Path ensureDirectory() {
        try {
            return Files.createDirectories(storageDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建论文文件夹", exception);
        }
    }

    public Path openDirectory() {
        Path directory = ensureDirectory();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", directory.toString()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", directory.toString()).start();
            } else {
                new ProcessBuilder("xdg-open", directory.toString()).start();
            }
            return directory;
        } catch (IOException exception) {
            throw new IllegalStateException("无法打开论文文件夹", exception);
        }
    }
}
