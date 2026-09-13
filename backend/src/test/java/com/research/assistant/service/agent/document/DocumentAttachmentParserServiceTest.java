package com.research.assistant.service.agent.document;

import com.research.assistant.entity.AgentAttachmentRecord;
import com.research.assistant.service.agent.capability.AiCapabilityService;
import com.research.assistant.service.agent.runtime.AgentAttachmentService;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAttachmentParserServiceTest {

    @Test
    void sendsOriginalImageToDocumentModelAndPersistsStructuredResult() throws Exception {
        AgentAttachmentService attachments = mock(AgentAttachmentService.class);
        AiCapabilityService capabilities = mock(AiCapabilityService.class);
        LangChain4jModelFactory factory = mock(LangChain4jModelFactory.class);
        ChatModel model = mock(ChatModel.class);
        Path image = Files.createTempFile("agent-attachment-test", ".png");
        byte[] bytes = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        Files.write(image, bytes);

        AgentAttachmentRecord record = new AgentAttachmentRecord();
        record.setAttachmentId("attachment-1");
        record.setSessionId(7L);
        record.setMediaType("image/png");
        record.setOriginalName("figure.png");
        record.setExtractionStatus("PENDING");
        when(attachments.resolveContent(record)).thenReturn(image);
        when(factory.createPaperUnderstandingModel()).thenReturn(model);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("结构化图片说明")).build());
        doNothing().when(capabilities).requireReady();
        when(capabilities.pdfReady()).thenReturn(false);

        DocumentAttachmentParserService service = new DocumentAttachmentParserService(attachments, capabilities, factory);
        String result = service.resolve(record, "这张图表达了什么？");

        assertThat(result).isEqualTo("结构化图片说明");
        verify(attachments).updateExtraction(record, "PARSED", "结构化图片说明",
                "{\"source\":\"document-api\",\"mode\":\"image\"}");
        var request = org.mockito.ArgumentCaptor.forClass(dev.langchain4j.model.chat.request.ChatRequest.class);
        verify(model).chat(request.capture());
        assertThat(request.getValue().messages().get(1)).isInstanceOf(UserMessage.class);
        UserMessage user = (UserMessage) request.getValue().messages().get(1);
        assertThat(user.contents()).anyMatch(content -> content instanceof ImageContent
                && ((ImageContent) content).image().mimeType().equals("image/png"));
        Files.deleteIfExists(image);
    }

    @Test
    void formulaTextBypassesDocumentModelAndKeepsLatexExact() {
        AgentAttachmentService attachments = mock(AgentAttachmentService.class);
        AiCapabilityService capabilities = mock(AiCapabilityService.class);
        LangChain4jModelFactory factory = mock(LangChain4jModelFactory.class);
        AgentAttachmentRecord record = new AgentAttachmentRecord();
        record.setAttachmentKind("FORMULA_TEXT");
        record.setMediaType("application/x-latex");
        record.setPreviewText("\\frac{a}{b}");

        String result = new DocumentAttachmentParserService(attachments, capabilities, factory)
                .resolve(record, "解释公式");

        assertThat(result).isEqualTo("\\frac{a}{b}");
        org.mockito.Mockito.verifyNoInteractions(factory, capabilities);
    }

    @Test
    void extractsDocxTextBeforeSendingItToTheDocumentModel() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Word 研究内容");
            document.write(output);
        }
        Path docx = Files.createTempFile("agent-attachment-test", ".docx");
        Files.write(docx, output.toByteArray());

        AgentAttachmentService attachments = mock(AgentAttachmentService.class);
        AiCapabilityService capabilities = mock(AiCapabilityService.class);
        LangChain4jModelFactory factory = mock(LangChain4jModelFactory.class);
        ChatModel model = mock(ChatModel.class);
        AgentAttachmentRecord record = new AgentAttachmentRecord();
        record.setAttachmentId("attachment-docx");
        record.setSessionId(7L);
        record.setMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        record.setOriginalName("paper.docx");
        record.setExtractionStatus("PENDING");
        when(attachments.resolveContent(record)).thenReturn(docx);
        when(factory.createPaperUnderstandingModel()).thenReturn(model);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("结构化 Word 内容")).build());

        String result = new DocumentAttachmentParserService(attachments, capabilities, factory)
                .resolve(record, "总结附件");

        assertThat(result).isEqualTo("结构化 Word 内容");
        var request = org.mockito.ArgumentCaptor.forClass(dev.langchain4j.model.chat.request.ChatRequest.class);
        verify(model).chat(request.capture());
        UserMessage user = (UserMessage) request.getValue().messages().get(1);
        assertThat(user.contents()).anyMatch(content -> content.toString().contains("Word 研究内容"));
        Files.deleteIfExists(docx);
    }

    @Test
    void fallsBackToRenderedPdfPageImagesWhenNativePdfIsUnavailable() throws Exception {
        Path pdf = Files.createTempFile("agent-attachment-test", ".pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        AgentAttachmentService attachments = mock(AgentAttachmentService.class);
        AiCapabilityService capabilities = mock(AiCapabilityService.class);
        LangChain4jModelFactory factory = mock(LangChain4jModelFactory.class);
        ChatModel model = mock(ChatModel.class);
        AgentAttachmentRecord record = new AgentAttachmentRecord();
        record.setAttachmentId("attachment-pdf");
        record.setSessionId(7L);
        record.setMediaType("application/pdf");
        record.setOriginalName("paper.pdf");
        record.setExtractionStatus("PENDING");
        when(attachments.resolveContent(record)).thenReturn(pdf);
        when(capabilities.pdfReady()).thenReturn(false);
        when(factory.createPaperUnderstandingModel()).thenReturn(model);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("结构化 PDF 内容")).build());

        String result = new DocumentAttachmentParserService(attachments, capabilities, factory)
                .resolve(record, "概括 PDF");

        assertThat(result).isEqualTo("结构化 PDF 内容");
        var request = org.mockito.ArgumentCaptor.forClass(dev.langchain4j.model.chat.request.ChatRequest.class);
        verify(model).chat(request.capture());
        UserMessage user = (UserMessage) request.getValue().messages().get(1);
        assertThat(user.contents()).anyMatch(content -> content instanceof ImageContent);
        verify(attachments).updateExtraction(record, "PARSED", "结构化 PDF 内容",
                "{\"source\":\"document-api\",\"mode\":\"page-images\"}");
        Files.deleteIfExists(pdf);
    }

    @Test
    void rejectsPdfOverTwentyPagesBeforeCallingTheModel() throws Exception {
        Path pdf = Files.createTempFile("agent-attachment-test", ".pdf");
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < 21; page++) document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        AgentAttachmentService attachments = mock(AgentAttachmentService.class);
        AiCapabilityService capabilities = mock(AiCapabilityService.class);
        LangChain4jModelFactory factory = mock(LangChain4jModelFactory.class);
        AgentAttachmentRecord record = binaryRecord("attachment-long-pdf", "application/pdf", "long.pdf");
        when(attachments.resolveContent(record)).thenReturn(pdf);

        assertThatThrownBy(() -> new DocumentAttachmentParserService(attachments, capabilities, factory)
                .resolve(record, "概括 PDF"))
                .hasMessage("附件页数过多");
        org.mockito.Mockito.verifyNoInteractions(factory);
        Files.deleteIfExists(pdf);
    }

    @Test
    void rejectsOversizedModelExtractionInsteadOfSavingATruncatedPreview() throws Exception {
        Path image = Files.createTempFile("agent-attachment-test", ".png");
        Files.write(image, Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
        AgentAttachmentService attachments = mock(AgentAttachmentService.class);
        AiCapabilityService capabilities = mock(AiCapabilityService.class);
        LangChain4jModelFactory factory = mock(LangChain4jModelFactory.class);
        ChatModel model = mock(ChatModel.class);
        AgentAttachmentRecord record = binaryRecord("attachment-long-output", "image/png", "figure.png");
        when(attachments.resolveContent(record)).thenReturn(image);
        when(factory.createPaperUnderstandingModel()).thenReturn(model);
        when(model.chat(any(dev.langchain4j.model.chat.request.ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(
                        dev.langchain4j.data.message.AiMessage.from("字".repeat(3_001))).build());

        assertThatThrownBy(() -> new DocumentAttachmentParserService(attachments, capabilities, factory)
                .resolve(record, "解释图片"))
                .hasMessage("附件内容过长");
        org.mockito.Mockito.verify(attachments, org.mockito.Mockito.never()).updateExtraction(
                any(), org.mockito.ArgumentMatchers.eq("PARSED"), any(), any());
        Files.deleteIfExists(image);
    }

    private static AgentAttachmentRecord binaryRecord(String id, String mediaType, String name) {
        AgentAttachmentRecord record = new AgentAttachmentRecord();
        record.setAttachmentId(id);
        record.setSessionId(7L);
        record.setMediaType(mediaType);
        record.setOriginalName(name);
        record.setExtractionStatus("PENDING");
        return record;
    }
}
