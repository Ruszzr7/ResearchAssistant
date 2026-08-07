package com.research.assistant.service;

import com.research.assistant.entity.Paper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperAssetLifecycleServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void deletesSourcePdfAndDerivedFigureDirectories() throws Exception {
        Path papers = Files.createDirectories(tempDir.resolve("papers"));
        Path figures = Files.createDirectories(tempDir.resolve("figures"));
        Files.writeString(papers.resolve("42_1000.pdf"), "pdf");
        Path derived = Files.createDirectories(figures.resolve("42_1000_2000"));
        Files.writeString(derived.resolve("figure.png"), "image");

        Paper paper = new Paper();
        paper.setId(42L);
        paper.setPdfPath("42_1000.pdf");
        new PaperAssetLifecycleService(papers.toString(), figures.toString())
                .deleteAfterCommit(List.of(paper));

        assertThat(papers.resolve("42_1000.pdf")).doesNotExist();
        assertThat(derived).doesNotExist();
    }

    @Test
    void refusesPathTraversalOutsideStorageRoot() throws Exception {
        Path papers = Files.createDirectories(tempDir.resolve("papers"));
        Path figures = Files.createDirectories(tempDir.resolve("figures"));
        Path outside = Files.writeString(tempDir.resolve("outside.pdf"), "keep");
        Paper paper = new Paper();
        paper.setPdfPath("../outside.pdf");

        new PaperAssetLifecycleService(papers.toString(), figures.toString())
                .deleteAfterCommit(List.of(paper));

        assertThat(outside).exists();
    }
}
