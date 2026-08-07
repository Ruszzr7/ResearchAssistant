package com.research.assistant.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PaperStorageDirectoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsConfiguredStorageDirectory() {
        Path directory = tempDir.resolve("data").resolve("papers");
        PaperStorageDirectoryService service =
                new PaperStorageDirectoryService(directory.toString());

        assertThat(service.ensureDirectory()).isEqualTo(directory.toAbsolutePath().normalize());
        assertThat(Files.isDirectory(directory)).isTrue();
    }
}
