package com.research.assistant.service.agent.document;

import com.research.assistant.dto.agent.AiCapabilityView;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DocumentInputPlanner {
    private static final int DIRECT_TEXT_LIMIT = 120_000;

    public DocumentInputPlan plan(DocumentInputRequest request, AiCapabilityView capability) {
        if (capability == null || !"VERIFIED".equals(capability.status()) || !capability.image()) {
            throw new IllegalStateException("DOCUMENT_MODEL_IMAGE_CAPABILITY_NOT_VERIFIED");
        }
        if (request.visualQuestion()) {
            return new DocumentInputPlan(DocumentInputMode.PAGE_IMAGES,
                    List.of(DocumentInputMode.STRUCTURED_TEXT, DocumentInputMode.HIERARCHICAL_READ),
                    "视觉问题使用相关页面图片，并在回答前回查本地来源");
        }
        if (request.wholeDocument() && capability.pdf()) {
            return new DocumentInputPlan(DocumentInputMode.NATIVE_PDF,
                    List.of(DocumentInputMode.STRUCTURED_TEXT, DocumentInputMode.HIERARCHICAL_READ),
                    "连接测试已验证原生 PDF 输入");
        }
        if (request.structuredCharacters() <= DIRECT_TEXT_LIMIT) {
            return new DocumentInputPlan(DocumentInputMode.STRUCTURED_TEXT,
                    List.of(DocumentInputMode.HIERARCHICAL_READ), "结构化文本可在有界输入内直接发送");
        }
        return new DocumentInputPlan(DocumentInputMode.HIERARCHICAL_READ,
                List.of(DocumentInputMode.STRUCTURED_TEXT), "长文档按结构分层读取，避免无界上下文");
    }
}
