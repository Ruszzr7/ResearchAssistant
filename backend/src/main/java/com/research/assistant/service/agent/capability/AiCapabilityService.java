package com.research.assistant.service.agent.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.dto.agent.AiCapabilityView;
import com.research.assistant.entity.AiModelCapabilityRecord;
import com.research.assistant.mapper.AiModelCapabilityMapper;
import com.research.assistant.service.ai.LangChain4jModelFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

@Service
public class AiCapabilityService {
    private static final String ROLE = "UNIFIED";
    private static final String VISION_CODE = "VISION_7";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TEST_IMAGE = createTestImage();
    private static final String TEST_PDF = createTestPdf();

    private final AiModelCapabilityMapper mapper;
    private final AiSettingsService settingsService;
    private final LangChain4jModelFactory modelFactory;

    public AiCapabilityService(AiModelCapabilityMapper mapper, AiSettingsService settingsService,
                               LangChain4jModelFactory modelFactory) {
        this.mapper = mapper;
        this.settingsService = settingsService;
        this.modelFactory = modelFactory;
    }

    public AiCapabilityView probe() {
        return probeInternal(settingsService.resolve(), false);
    }

    public AiCapabilityView probeDraft(String baseUrl, String model, String apiKey) {
        return probeInternal(settingsService.resolveDraft(baseUrl, model, apiKey), true);
    }

    /** Probe explicit settings without writing them to the settings table. */
    public AiCapabilityView probe(AiSettings settings) {
        return probeInternal(settings, true);
    }

    private AiCapabilityView probeInternal(AiSettings settings, boolean explicitSettings) {
        AiModelCapabilityRecord record = mapper.selectVersion(settings.signature());
        if (record == null) {
            record = new AiModelCapabilityRecord();
            record.setModelRole(ROLE);
            record.setConfigSignature(settings.signature());
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            ChatModel model = explicitSettings
                    ? modelFactory.createAgentChatModel(settings) : modelFactory.createAgentChatModel();
            probeRequired(record, model);
            record.setPdfSupported(probePdf(model));
            record.setStatus("VERIFIED");
            record.setErrorCode(null);
            record.setErrorMessage(null);
            record.setExpiresAt(now.plusDays(7));
        } catch (RuntimeException error) {
            markRequiredUnsupported(record);
            record.setStatus("FAILED");
            record.setErrorCode(classify(error));
            record.setErrorMessage(safe(error));
            record.setExpiresAt(now.plusMinutes(10));
        }
        record.setVerifiedAt(now);
        if (record.getId() == null) mapper.insert(record); else mapper.updateById(record);
        return view(record);
    }

    public void requireReady() {
        AiCapabilityView capability = current();
        if (!"VERIFIED".equals(capability.status())
                || capability.expiresAt() == null || !capability.expiresAt().isAfter(LocalDateTime.now())
                || !capability.chat() || !capability.toolCalling() || !capability.continuousTools()
                || !capability.toolImageContinuation() || !capability.structured() || !capability.image()) {
            throw new IllegalStateException("UNIFIED_MODEL_CAPABILITY_NOT_VERIFIED");
        }
    }

    public AiCapabilityView current() {
        AiSettings settings = settingsService.resolve();
        AiModelCapabilityRecord record = mapper.selectVersion(settings.signature());
        return record == null ? new AiCapabilityView("UNVERIFIED", false, false, false,
                false, false, false, false, null, "尚未测试", null, null) : view(record);
    }

    public boolean imageReady() {
        try {
            AiCapabilityView capability = current();
            return "VERIFIED".equals(capability.status()) && capability.image()
                    && capability.toolImageContinuation()
                    && capability.expiresAt() != null && capability.expiresAt().isAfter(LocalDateTime.now());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public boolean pdfReady() {
        try {
            AiCapabilityView capability = current();
            return "VERIFIED".equals(capability.status()) && capability.image() && capability.pdf()
                    && capability.expiresAt() != null && capability.expiresAt().isAfter(LocalDateTime.now());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void probeRequired(AiModelCapabilityRecord record, ChatModel model) {
        ToolSpecification tool = ToolSpecification.fromJson("""
                {"name":"capability_echo","description":"返回给定的值",
                "parameters":{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}}
                """);
        var first = model.chat(ChatRequest.builder().messages(
                        SystemMessage.from("请使用值 READY 调用一次 capability_echo。"),
                        UserMessage.from("执行能力测试。"))
                .toolSpecifications(tool).toolChoice(ToolChoice.AUTO).build());
        if (!first.aiMessage().hasToolExecutionRequests()) {
            throw new IllegalStateException("MODEL_DID_NOT_CALL_TOOL");
        }
        ToolExecutionRequest call = first.aiMessage().toolExecutionRequests().get(0);
        var visualMessage = UserMessage.from(List.of(
                TextContent.from("读取图像中的代码。只返回包含 tool 和 image 字段的 JSON。"),
                ImageContent.from(TEST_IMAGE, "image/png")));
        var second = model.chat(ChatRequest.builder().messages(
                        SystemMessage.from("收到工具结果和图像后，严格只返回 JSON。"
                                + "tool 字段必须为 READY，image 字段必须为图像中可见的代码。"),
                        UserMessage.from("执行能力测试。"),
                        AiMessage.from(List.of(call)),
                        ToolExecutionResultMessage.from(call, "READY"),
                        visualMessage)
                .toolSpecifications(tool).build());
        JsonNode json = parseJson(second.aiMessage().text());
        if (!"READY".equals(json.path("tool").asText())) {
            throw new IllegalStateException("MODEL_DID_NOT_CONTINUE_AFTER_TOOL");
        }
        if (!VISION_CODE.equalsIgnoreCase(json.path("image").asText())) {
            throw new IllegalStateException("IMAGE_INPUT_FAILED");
        }
        record.setChatSupported(true);
        record.setToolCallingSupported(true);
        record.setContinuousToolsSupported(true);
        record.setToolImageContinuationSupported(true);
        record.setStructuredSupported(true);
        record.setImageSupported(true);
    }

    private boolean probePdf(ChatModel model) {
        try {
            var result = model.chat(ChatRequest.builder().messages(UserMessage.from(List.of(
                    TextContent.from("只返回此 PDF 中可见的代码。"),
                    PdfFileContent.from(TEST_PDF, "application/pdf")))).build());
            return result.aiMessage().text() != null
                    && result.aiMessage().text().toUpperCase(java.util.Locale.ROOT).contains("PDF_7");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) throw new IllegalStateException("STRUCTURED_OUTPUT_FAILED");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("STRUCTURED_OUTPUT_FAILED");
        try {
            return JSON.readTree(text.substring(start, end + 1));
        } catch (IOException error) {
            throw new IllegalStateException("STRUCTURED_OUTPUT_FAILED", error);
        }
    }

    private static void markRequiredUnsupported(AiModelCapabilityRecord record) {
        record.setChatSupported(false);
        record.setToolCallingSupported(false);
        record.setContinuousToolsSupported(false);
        record.setToolImageContinuationSupported(false);
        record.setStructuredSupported(false);
        record.setImageSupported(false);
        record.setPdfSupported(false);
    }

    private static AiCapabilityView view(AiModelCapabilityRecord r) {
        return new AiCapabilityView(r.getStatus(), yes(r.getChatSupported()),
                yes(r.getToolCallingSupported()), yes(r.getContinuousToolsSupported()),
                yes(r.getToolImageContinuationSupported()), yes(r.getStructuredSupported()),
                yes(r.getImageSupported()), yes(r.getPdfSupported()), r.getErrorCode(), r.getErrorMessage(),
                r.getVerifiedAt(), r.getExpiresAt());
    }

    private static boolean yes(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    private static String classify(RuntimeException error) {
        String name = error.getClass().getSimpleName().toUpperCase();
        String detail = error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("AUTH")) return "AUTHENTICATION";
        if (name.contains("RATE") || detail.contains("rate limit") || detail.contains("rate_limit")
                || detail.contains("overloaded") || detail.contains("429")) return "RATE_LIMIT";
        if (name.contains("TIMEOUT") || detail.contains("timeout") || detail.contains("timed out")) return "TIMEOUT";
        return "CAPABILITY_PROBE_FAILED";
    }

    private static String safe(RuntimeException error) {
        String value = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return value.length() > 900 ? value.substring(0, 900) : value;
    }

    private static String createTestImage() {
        try {
            BufferedImage image = new BufferedImage(240, 72, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            graphics.drawString(VISION_CODE, 28, 48);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static String createTestPdf() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 24);
                stream.newLineAtOffset(72, 700);
                stream.showText("PDF_7");
                stream.endText();
            }
            document.save(output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
