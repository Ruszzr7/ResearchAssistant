package com.research.assistant.controller;

import com.research.assistant.common.Result;
import com.research.assistant.dto.agent.AgentAttachmentView;
import com.research.assistant.entity.AgentAttachmentRecord;
import com.research.assistant.service.agent.runtime.AgentAttachmentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/agent/attachments")
public class AgentAttachmentController {
    private final AgentAttachmentService attachmentService;

    public AgentAttachmentController(AgentAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping
    public Result<AgentAttachmentView> upload(@RequestParam long conversationId,
                                              @RequestParam(defaultValue = "FILE") String kind,
                                              @RequestParam("file") MultipartFile file) throws Exception {
        AgentAttachmentRecord record = attachmentService.stage(conversationId, kind,
                file.getOriginalFilename(), file.getContentType(), file.getBytes());
        return Result.ok(new AgentAttachmentView(record.getAttachmentId(), record.getOriginalName(),
                record.getMediaType(), record.getSizeBytes(), record.getExtractionStatus()));
    }
}
