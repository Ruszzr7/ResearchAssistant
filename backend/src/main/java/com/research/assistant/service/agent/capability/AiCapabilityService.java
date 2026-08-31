package com.research.assistant.service.agent.capability;

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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

@Service
public class AiCapabilityService {
    private static final String TINY_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    private static final String TINY_PDF = createTinyPdf();

    private final AiModelCapabilityMapper mapper;
    private final AiRoleSettingsService settingsService;
    private final LangChain4jModelFactory modelFactory;

    public AiCapabilityService(AiModelCapabilityMapper mapper, AiRoleSettingsService settingsService,
                               LangChain4jModelFactory modelFactory) {
        this.mapper = mapper;
        this.settingsService = settingsService;
        this.modelFactory = modelFactory;
    }

    public AiCapabilityView probe(AiModelRole role) {
        return probeInternal(role, settingsService.resolve(role), false);
    }

    public AiCapabilityView probeDraft(AiModelRole role, String baseUrl, String model, String apiKey) {
        return probe(role, settingsService.resolveDraft(role, baseUrl, model, apiKey));
    }

    /** Probe explicit draft settings without writing them to the settings table. */
    public AiCapabilityView probe(AiModelRole role, AiRoleSettings settings) {
        return probeInternal(role, settings, true);
    }

    private AiCapabilityView probeInternal(AiModelRole role, AiRoleSettings settings, boolean explicitSettings) {
        if (settings == null || settings.role() != role) {
            throw new IllegalArgumentException("capability probe settings do not match the requested role");
        }
        AiModelCapabilityRecord record = mapper.selectVersion(role.name(), settings.signature());
        if (record == null) { record = new AiModelCapabilityRecord(); record.setModelRole(role.name()); record.setConfigSignature(settings.signature()); }
        LocalDateTime now = LocalDateTime.now();
        try {
            if (role == AiModelRole.CHAT) {
                probeChat(record, explicitSettings
                        ? modelFactory.createAgentChatModel(settings) : modelFactory.createAgentChatModel());
            } else {
                probeDocument(record, explicitSettings
                        ? modelFactory.createDocumentModel(settings) : modelFactory.createDocumentModel());
            }
            record.setStatus("VERIFIED"); record.setErrorCode(null); record.setErrorMessage(null);
            record.setExpiresAt(now.plusDays(7));
        } catch (RuntimeException error) {
            record.setStatus("FAILED"); record.setErrorCode(classify(error)); record.setErrorMessage(safe(error));
            record.setExpiresAt(now.plusMinutes(10));
        }
        record.setVerifiedAt(now);
        if (record.getId() == null) mapper.insert(record); else mapper.updateById(record);
        return view(record);
    }

    public void requireChatAgentReady() {
        AiRoleSettings settings = settingsService.resolve(AiModelRole.CHAT);
        AiModelCapabilityRecord record = mapper.selectVersion(AiModelRole.CHAT.name(), settings.signature());
        if (record == null || !"VERIFIED".equals(record.getStatus())
                || record.getExpiresAt() == null || !record.getExpiresAt().isAfter(LocalDateTime.now())
                || !Boolean.TRUE.equals(record.getToolCallingSupported())
                || !Boolean.TRUE.equals(record.getContinuousToolsSupported())) {
            throw new IllegalStateException("AGENT_MODEL_CAPABILITY_NOT_VERIFIED");
        }
    }

    public void requireDocumentReady() {
        AiRoleSettings settings = settingsService.resolve(AiModelRole.DOCUMENT);
        AiModelCapabilityRecord record = mapper.selectVersion(AiModelRole.DOCUMENT.name(), settings.signature());
        if (record == null || !"VERIFIED".equals(record.getStatus())
                || record.getExpiresAt() == null || !record.getExpiresAt().isAfter(LocalDateTime.now())
                || !Boolean.TRUE.equals(record.getImageSupported())) {
            throw new IllegalStateException("DOCUMENT_MODEL_CAPABILITY_NOT_VERIFIED");
        }
    }

    public AiCapabilityView current(AiModelRole role) {
        AiRoleSettings settings = settingsService.resolve(role);
        AiModelCapabilityRecord record = mapper.selectVersion(role.name(), settings.signature());
        return record == null ? new AiCapabilityView(role.name(), "UNVERIFIED", false, false, false,
                false, false, false, null, "尚未测试", null, null) : view(record);
    }

    public boolean documentImageReady() {
        try {
            AiCapabilityView capability = current(AiModelRole.DOCUMENT);
            return "VERIFIED".equals(capability.status()) && capability.image()
                    && capability.expiresAt() != null && capability.expiresAt().isAfter(LocalDateTime.now());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public boolean documentPdfReady() {
        try {
            AiCapabilityView capability = current(AiModelRole.DOCUMENT);
            return "VERIFIED".equals(capability.status()) && capability.image() && capability.pdf()
                    && capability.expiresAt() != null && capability.expiresAt().isAfter(LocalDateTime.now());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void probeChat(AiModelCapabilityRecord record, ChatModel model) {
        ToolSpecification tool = ToolSpecification.fromJson("""
                {"name":"capability_echo","description":"Return the supplied value",
                "parameters":{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}}
                """);
        var first = model.chat(ChatRequest.builder().messages(SystemMessage.from("Always call capability_echo."),
                UserMessage.from("Echo READY using the tool.")).toolSpecifications(tool)
                // Capability probing must exercise the same model-controlled protocol as
                // production Agent turns.  Some compatible providers reject REQUIRED when
                // thinking is enabled; AUTO plus an explicit instruction is portable.
                .toolChoice(ToolChoice.AUTO).build());
        if (!first.aiMessage().hasToolExecutionRequests()) throw new IllegalStateException("MODEL_DID_NOT_CALL_TOOL");
        ToolExecutionRequest call = first.aiMessage().toolExecutionRequests().get(0);
        var second = model.chat(ChatRequest.builder().messages(SystemMessage.from("After tool output, answer DONE."),
                UserMessage.from("Run the test."), AiMessage.from(List.of(call)),
                ToolExecutionResultMessage.from(call, "READY")).toolSpecifications(tool).build());
        if (second.aiMessage().text() == null || second.aiMessage().text().isBlank()) {
            throw new IllegalStateException("MODEL_DID_NOT_CONTINUE_AFTER_TOOL");
        }
        record.setChatSupported(true); record.setToolCallingSupported(true);
        record.setContinuousToolsSupported(true); record.setStructuredSupported(true);
        record.setImageSupported(false); record.setPdfSupported(false);
    }

    private void probeDocument(AiModelCapabilityRecord record, ChatModel model) {
        var image = model.chat(UserMessage.from(List.of(TextContent.from("Reply IMAGE_OK if you received the image."),
                ImageContent.from(TINY_PNG, "image/png"))));
        if (image.aiMessage().text() == null || image.aiMessage().text().isBlank()) {
            throw new IllegalStateException("IMAGE_INPUT_FAILED");
        }
        boolean pdf = false;
        try {
            var result = model.chat(UserMessage.from(List.of(TextContent.from("Reply PDF_OK if you received the PDF."),
                    PdfFileContent.from(TINY_PDF, "application/pdf"))));
            pdf = result.aiMessage().text() != null && !result.aiMessage().text().isBlank();
        } catch (RuntimeException ignored) { pdf = false; }
        record.setChatSupported(true); record.setToolCallingSupported(false);
        record.setContinuousToolsSupported(false); record.setStructuredSupported(false);
        record.setImageSupported(true); record.setPdfSupported(pdf);
    }

    private static AiCapabilityView view(AiModelCapabilityRecord r) {
        return new AiCapabilityView(r.getModelRole(), r.getStatus(), yes(r.getChatSupported()),
                yes(r.getToolCallingSupported()), yes(r.getContinuousToolsSupported()), yes(r.getStructuredSupported()),
                yes(r.getImageSupported()), yes(r.getPdfSupported()), r.getErrorCode(), r.getErrorMessage(),
                r.getVerifiedAt(), r.getExpiresAt());
    }
    private static boolean yes(Boolean value) { return Boolean.TRUE.equals(value); }
    private static String classify(RuntimeException e) {
        String name = e.getClass().getSimpleName().toUpperCase();
        String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("AUTH")) return "AUTHENTICATION";
        if (name.contains("RATE") || detail.contains("rate limit") || detail.contains("rate_limit")
                || detail.contains("overloaded") || detail.contains("429")) return "RATE_LIMIT";
        if (name.contains("TIMEOUT")) return "TIMEOUT";
        return "CAPABILITY_PROBE_FAILED";
    }
    private static String safe(RuntimeException e) {
        String value = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return value.length() > 900 ? value.substring(0, 900) : value;
    }

    private static String createTinyPdf() {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
