package com.research.assistant.service;

import com.research.assistant.entity.Paper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void validatesPdfBytesInsteadOfTrustingExtensionAndStoresPageCount() throws Exception {
        Path papers = Files.createDirectories(tempDir.resolve("papers"));
        PaperAssetLifecycleService service = new PaperAssetLifecycleService(
                papers.toString(), tempDir.resolve("figures").toString());

        MockMultipartFile upload = new MockMultipartFile(
                "file", "paper.txt", "text/plain", pdfBytes(2, false));
        PaperAssetLifecycleService.StoredPdf stored = service.storeUploadedPdf(upload);

        assertThat(stored.pageCount()).isEqualTo(2);
        assertThat(stored.storedName()).endsWith(".pdf");
        assertThat(papers.resolve(stored.storedName())).isRegularFile();
    }

    @Test
    void rejectsEmptyCorruptAndEncryptedPdfWithoutLeavingFiles() throws Exception {
        Path papers = Files.createDirectories(tempDir.resolve("papers"));
        PaperAssetLifecycleService service = new PaperAssetLifecycleService(
                papers.toString(), tempDir.resolve("figures").toString());

        assertThatThrownBy(() -> service.storeUploadedPdf(
                new MockMultipartFile("file", "empty.bin", "application/octet-stream", new byte[0])))
                .hasMessage("上传文件为空");
        assertThatThrownBy(() -> service.storeUploadedPdf(
                new MockMultipartFile("file", "corrupt.pdf", "application/pdf", "not a pdf".getBytes())))
                .hasMessage("文件不是有效的 PDF，或 PDF 无法打开");
        assertThatThrownBy(() -> service.storeUploadedPdf(
                new MockMultipartFile("file", "locked.pdf", "application/pdf", pdfBytes(1, true))))
                .hasMessage("加密 PDF 无法打开，请先解除密码保护");

        try (var entries = Files.list(papers)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void removesPromotedPdfWhenTransactionRollsBack() throws Exception {
        Path papers = Files.createDirectories(tempDir.resolve("papers"));
        PaperAssetLifecycleService service = new PaperAssetLifecycleService(
                papers.toString(), tempDir.resolve("figures").toString());
        TransactionSynchronizationManager.initSynchronization();
        try {
            PaperAssetLifecycleService.StoredPdf stored = service.storeUploadedPdf(
                    new MockMultipartFile("file", "paper.pdf", "application/pdf", pdfBytes(1, false)));
            assertThat(papers.resolve(stored.storedName())).exists();
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            assertThat(papers.resolve(stored.storedName())).doesNotExist();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private byte[] pdfBytes(int pages, boolean encrypted) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pages; index++) document.addPage(new PDPage());
            if (encrypted) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy(
                        "owner-password", "user-password", new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
