package com.research.assistant.service.workbench;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperContextAttachmentTest {

    @Test
    void rendersUserAttachmentsAsAuxiliaryContextInsteadOfPaperEvidence() {
        PaperContextSnapshot snapshot = new PaperContextSnapshot(
                PaperContextSnapshot.SCHEMA_VERSION, 7L, "hash", "parser", "conversation-1",
                "比较定义", "", "附件「notes.tex」：\n\\Gamma = a + b",
                List.of(), "none", "", List.of(), List.of(),
                List.of("CURRENT_QUESTION", "CURRENT_USER_ATTACHMENTS"),
                new PaperContextSnapshot.Budget(8_000, 24, 0, 0, 0), false, Instant.now());

        assertThat(snapshot.modelQuestion()).contains("本轮用户附件", "notes.tex", "\\Gamma = a + b")
                .contains("不是左侧论文证据");
        assertThat(snapshot.retrievalQuery()).contains("本轮附件", "notes.tex", "\\Gamma = a + b");
    }
}
