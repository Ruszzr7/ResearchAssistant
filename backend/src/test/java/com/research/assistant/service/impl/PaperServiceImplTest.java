package com.research.assistant.service.impl;

import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.FolderMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.mapper.TagMapper;
import com.research.assistant.service.PaperAssetLifecycleService;
import com.research.assistant.service.PdfExtractor;
import com.research.assistant.service.metadata.PaperDocumentReviewer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PaperServiceImplTest {

    @TempDir
    Path tempDir;

    private PaperMapper paperMapper;
    private TagMapper tagMapper;
    private FolderMapper folderMapper;
    private PdfExtractor pdfExtractor;
    private PaperAssetLifecycleService assetService;
    private PaperDocumentReviewer paperDocumentReviewer;
    private PaperServiceImpl service;

    @BeforeEach
    void setUp() {
        paperMapper = mock(PaperMapper.class);
        tagMapper = mock(TagMapper.class);
        folderMapper = mock(FolderMapper.class);
        pdfExtractor = mock(PdfExtractor.class);
        paperDocumentReviewer = mock(PaperDocumentReviewer.class);
        assetService = new PaperAssetLifecycleService(tempDir.resolve("papers").toString(),
                tempDir.resolve("figures").toString());
        service = new PaperServiceImpl(paperMapper, tagMapper, folderMapper, pdfExtractor, assetService,
                paperDocumentReviewer);
        when(tagMapper.selectByPaperId(anyLong())).thenReturn(List.of());
        when(paperMapper.selectList(isNull())).thenReturn(List.of());
        when(pdfExtractor.extract(any())).thenReturn("");
        when(pdfExtractor.extractMetadataTextExtraction(any(org.springframework.web.multipart.MultipartFile.class), any(Integer.class)))
                .thenReturn(new PdfExtractor.MetadataTextExtraction("", ""));
        when(pdfExtractor.extractMetadataTextExtraction(any(String.class), any(Integer.class)))
                .thenReturn(new PdfExtractor.MetadataTextExtraction("", ""));
        when(paperDocumentReviewer.review(any(), any()))
                .thenReturn(new PaperDocumentReviewer.Review(PaperDocumentReviewer.Status.PAPER, ""));
    }

    @Test
    void createsPaperFromValidPdfWithDetectedPageCountEvenWhenNameIsNotPdf() throws Exception {
        AtomicReference<Paper> saved = new AtomicReference<>();
        doAnswer(invocation -> {
            Paper paper = invocation.getArgument(0);
            paper.setId(11L);
            saved.set(paper);
            return 1;
        }).when(paperMapper).insert(any(Paper.class));
        when(paperMapper.selectByDoi(any())).thenReturn(null);
        when(paperMapper.selectById(11L)).thenAnswer(invocation -> saved.get());

        Paper result = service.uploadPdfAndCreate(
                new MockMultipartFile("file", "document.bin", "application/octet-stream", pdfBytes(3)),
                paper("新论文"), false);

        assertThat(result.getId()).isEqualTo(11L);
        assertThat(result.getPdfPath()).endsWith(".pdf");
        assertThat(result.getPageCount()).isEqualTo(3);
        assertThat(Files.isRegularFile(tempDir.resolve("papers").resolve(result.getPdfPath()))).isTrue();
    }

    @Test
    void databaseFailureCleansNewlyPromotedPdf() throws Exception {
        when(paperMapper.selectByDoi(any())).thenReturn(null);
        doThrow(new IllegalStateException("database failure")).when(paperMapper).insert(any(Paper.class));

        assertThatThrownBy(() -> service.uploadPdfAndCreate(
                new MockMultipartFile("file", "paper.pdf", "application/pdf", pdfBytes(1)),
                paper("数据库失败"), false))
                .isInstanceOf(IllegalStateException.class);

        Path papers = tempDir.resolve("papers");
        if (Files.exists(papers)) {
            try (var entries = Files.list(papers)) {
                assertThat(entries.toList()).isEmpty();
            }
        }
    }

    @Test
    void failedOverwriteLeavesOldPdfAndDoesNotLeaveNewPdf() throws Exception {
        Path papers = Files.createDirectories(tempDir.resolve("papers"));
        Files.writeString(papers.resolve("old.pdf"), "old");
        Paper duplicate = paper("旧论文");
        duplicate.setId(7L);
        duplicate.setDoi("10.1000/old");
        duplicate.setPdfPath("old.pdf");
        when(paperMapper.selectByDoi("10.1000/old")).thenReturn(duplicate);
        doThrow(new IllegalStateException("database failure")).when(paperMapper).updateById(any(Paper.class));

        Paper replacement = paper("新标题");
        replacement.setDoi("10.1000/old");
        assertThatThrownBy(() -> service.uploadPdfAndCreate(
                new MockMultipartFile("file", "new-name.any", "application/octet-stream", pdfBytes(2)),
                replacement, true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(papers.resolve("old.pdf")).exists();
        try (var entries = Files.list(papers)) {
            assertThat(entries.map(path -> path.getFileName().toString())
                    .filter(name -> !name.equals("old.pdf")).toList()).isEmpty();
        }
    }

    @Test
    void successfulOverwriteRemovesOldPdfAfterDatabaseUpdate() throws Exception {
        Path papers = Files.createDirectories(tempDir.resolve("papers"));
        Files.writeString(papers.resolve("old.pdf"), "old");
        Paper duplicate = paper("旧论文");
        duplicate.setId(8L);
        duplicate.setDoi("10.1000/old-2");
        duplicate.setPdfPath("old.pdf");
        when(paperMapper.selectByDoi("10.1000/old-2")).thenReturn(duplicate);
        AtomicReference<Paper> updated = new AtomicReference<>();
        doAnswer(invocation -> {
            updated.set(invocation.getArgument(0));
            return 1;
        }).when(paperMapper).updateById(any(Paper.class));
        when(paperMapper.selectById(8L)).thenAnswer(invocation -> updated.get());

        Paper replacement = paper("新标题");
        replacement.setDoi("10.1000/old-2");
        Paper result = service.uploadPdfAndCreate(
                new MockMultipartFile("file", "new-name.any", "application/octet-stream", pdfBytes(2)),
                replacement, true);

        assertThat(result.getPdfPath()).isNotEqualTo("old.pdf");
        assertThat(papers.resolve("old.pdf")).doesNotExist();
        assertThat(papers.resolve(result.getPdfPath())).isRegularFile();
    }

    @Test
    void rejectsCorruptPdfBeforeInsertingPaper() {
        when(paperMapper.selectByDoi(any())).thenReturn(null);

        assertThatThrownBy(() -> service.uploadPdfAndCreate(
                new MockMultipartFile("file", "bad.txt", "text/plain", "bad".getBytes()),
                paper("损坏文件"), false))
                .hasMessage("文件不是有效的 PDF，或 PDF 无法打开");
    }

    @Test
    void rejectsClearlyNonPaperPdfBeforeInsertingPaper() throws Exception {
        when(paperDocumentReviewer.review(any(), any()))
                .thenReturn(new PaperDocumentReviewer.Review(
                        PaperDocumentReviewer.Status.NOT_PAPER, "导入文件不是有效论文"));

        assertThatThrownBy(() -> service.uploadPdfAndCreate(
                new MockMultipartFile("file", "diagram.pdf", "application/pdf", pdfBytes(1)),
                paper("图示文件"), false))
                .hasMessage("导入文件不是有效论文");

        org.mockito.Mockito.verify(paperMapper, never()).insert(any(Paper.class));
        try (var entries = Files.list(tempDir.resolve("papers"))) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void rejectsUnknownFolderAndPreservesServerManagedFieldsOnUpdate() {
        Paper input = paper("论文");
        input.setFolderId(99L);
        when(folderMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.create(input)).hasMessage("文件夹不存在");

        Paper existing = paper("已有论文");
        existing.setId(10L);
        existing.setPageCount(12);
        existing.setCurrentPage(5);
        existing.setReadSeconds(99);
        when(paperMapper.selectById(10L)).thenReturn(existing);
        when(paperMapper.updateById(any(Paper.class))).thenReturn(1);
        Paper update = paper("修改标题");
        update.setId(10L);
        update.setPageCount(1);
        update.setCurrentPage(1);
        update.setReadSeconds(1);
        service.update(update);
        org.mockito.Mockito.verify(paperMapper).updateById(update);
        assertThat(update.getPageCount()).isEqualTo(12);
        assertThat(update.getCurrentPage()).isEqualTo(5);
        assertThat(update.getReadSeconds()).isEqualTo(99);
    }

    @Test
    void deletesPaperOwnedPdfAndDerivedFiguresOnlyAfterTheRowIsDeleted() throws Exception {
        Path papers = Files.createDirectories(tempDir.resolve("papers"));
        Path figures = Files.createDirectories(tempDir.resolve("figures"));
        Files.writeString(papers.resolve("paper-42.pdf"), "pdf");
        Path derived = Files.createDirectories(figures.resolve("paper-42_figure-1"));
        Files.writeString(derived.resolve("image.png"), "image");

        Paper paper = paper("待删除论文");
        paper.setId(42L);
        paper.setPdfPath("paper-42.pdf");
        when(paperMapper.selectById(42L)).thenReturn(paper);
        when(paperMapper.deleteById(42L)).thenReturn(1);

        service.delete(42L);

        assertThat(papers.resolve("paper-42.pdf")).doesNotExist();
        assertThat(derived).doesNotExist();
    }

    @Test
    void rejectsBlankTitleAndInvalidReadingStatusBeforeWriting() {
        Paper blank = paper(" ");
        assertThatThrownBy(() -> service.create(blank)).hasMessage("标题不能为空");

        Paper invalid = paper("论文");
        invalid.setReadingStatus("DONE");
        assertThatThrownBy(() -> service.create(invalid))
                .hasMessage("阅读状态必须是 UNREAD、READING 或 READ");
    }

    private Paper paper(String title) {
        Paper paper = new Paper();
        paper.setTitle(title);
        return paper;
    }

    private byte[] pdfBytes(int pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int index = 0; index < pages; index++) document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }
}
