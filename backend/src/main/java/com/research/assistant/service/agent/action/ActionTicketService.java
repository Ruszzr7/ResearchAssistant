package com.research.assistant.service.agent.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.entity.AgentToolCallRecord;
import com.research.assistant.mapper.AgentToolCallMapper;
import com.research.assistant.service.agent.runtime.AgentRuntimeConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ActionTicketService {
    private static final long TICKET_SECONDS = 300;
    private final AgentToolCallMapper toolCallMapper;
    private final ObjectMapper objectMapper;
    private final byte[] signingKey = new byte[32];

    public ActionTicketService(AgentToolCallMapper toolCallMapper, ObjectMapper objectMapper) {
        this.toolCallMapper = toolCallMapper;
        this.objectMapper = objectMapper;
        new SecureRandom().nextBytes(signingKey);
    }

    @Transactional
    public IssuedActionTicket issue(String runId, AgentToolCallRecord call, PaperActionType type,
                                    ActionTarget target, String content, String color) {
        if (call == null || !runId.equals(call.getRunId())) throw new IllegalArgumentException("tool call does not belong to run");
        Instant expiresAt = Instant.now().plusSeconds(TICKET_SECONDS);
        ActionTicketPayload payload = new ActionTicketPayload(runId, call.getToolCallId(), target.paperId(),
                target.documentHash(), target.sourceObjectId(), type, content, color, expiresAt, UUID.randomUUID().toString());
        String ticket = encode(payload);
        int updated = toolCallMapper.storeActionTicket(call.getId(), value(call.getVersion()), sha256(ticket),
                LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        if (updated != 1) throw new AgentRuntimeConflictException("action ticket could not be stored");
        return new IssuedActionTicket(ticket, expiresAt, payload);
    }

    public ActionTicketPayload verify(String ticket) {
        if (ticket == null || ticket.isBlank()) throw new IllegalArgumentException("action ticket is required");
        String[] parts = ticket.split("\\.", -1);
        if (parts.length != 2) throw new IllegalArgumentException("invalid action ticket");
        byte[] expected = hmac(parts[0]);
        byte[] actual;
        try { actual = Base64.getUrlDecoder().decode(parts[1]); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("invalid action ticket", error); }
        if (!MessageDigest.isEqual(expected, actual)) throw new IllegalArgumentException("action ticket signature is invalid");
        try {
            ActionTicketPayload payload = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[0]), ActionTicketPayload.class);
            if (payload.expiresAt() == null || !payload.expiresAt().isAfter(Instant.now())) {
                throw new IllegalArgumentException("action ticket expired");
            }
            AgentToolCallRecord call = toolCallMapper.selectByToolCallId(payload.toolCallId());
            if (call == null || !payload.runId().equals(call.getRunId())
                    || !sha256(ticket).equals(call.getActionTicketHash())) {
                throw new IllegalArgumentException("action ticket is stale or does not belong to the tool call");
            }
            return payload;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid action ticket payload", error);
        }
    }

    private String encode(ActionTicketPayload payload) {
        try {
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(payload));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(body));
            return body + "." + signature;
        } catch (Exception error) {
            throw new IllegalStateException("failed to issue action ticket", error);
        }
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", error);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static int value(Integer value) { return value == null ? 0 : value; }

    public record IssuedActionTicket(String ticket, Instant expiresAt, ActionTicketPayload payload) { }
}
