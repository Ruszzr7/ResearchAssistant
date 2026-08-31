package com.research.assistant.service.agent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.service.pdf.layout.NormalizedBoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionTicketServiceTest {
    @Test
    void signsBindsAndRejectsTamperedTicket() {
        AgentToolCallMapper mapper = mock(AgentToolCallMapper.class);
        AgentToolCallRecord call = new AgentToolCallRecord();
        call.setId(1L); call.setRunId("run-1"); call.setToolCallId("tool-1"); call.setVersion(0);
        when(mapper.storeActionTicket(eq(1L), eq(0), anyString(), any())).thenAnswer(invocation -> {
            call.setActionTicketHash(invocation.getArgument(2)); call.setStatus("WAITING_CLIENT"); return 1;
        });
        when(mapper.selectByToolCallId("tool-1")).thenReturn(call);
        ActionTicketService service = new ActionTicketService(mapper, new ObjectMapper().findAndRegisterModules());
        ActionTarget target = new ActionTarget(9, "hash", "src", 2, List.of("loc"),
                List.of(new NormalizedBoundingBox(.1, .2, .3, .04)));

        var issued = service.issue("run-1", call, PaperActionType.HIGHLIGHT, target, null, "#fff000");

        assertThat(service.verify(issued.ticket()).sourceObjectId()).isEqualTo("src");
        String tampered = (issued.ticket().startsWith("a") ? "b" : "a") + issued.ticket().substring(1);
        assertThatThrownBy(() -> service.verify(tampered)).hasMessageContaining("signature");
    }
}
