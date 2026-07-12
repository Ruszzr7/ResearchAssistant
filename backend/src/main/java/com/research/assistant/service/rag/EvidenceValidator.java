package com.research.assistant.service.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对 LLM 选择的 evidence 做后端确定性校验。
 * <p>标题、年份和 URL 始终从候选记录回填，避免把 LLM 生成的链接当成事实。</p>
 */
@Component
public class EvidenceValidator {

    private static final int MAX_EVIDENCE = 20;
    private static final int MAX_SNIPPET_LENGTH = 500;

    /**
     * 校验 fallback 路径的候选证据。候选记录必须由后端搜索/RAG 生成。
     */
    public List<Map<String, Object>> validate(List<Map<String, Object>> rawEvidence,
                                               List<Map<String, Object>> candidates) {
        if (rawEvidence == null || rawEvidence.isEmpty() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> raw : rawEvidence) {
            if (raw == null || result.size() >= MAX_EVIDENCE) {
                continue;
            }
            Map<String, Object> candidate = findCandidate(raw, candidates);
            if (candidate == null) {
                continue;
            }
            String evidenceId = text(candidate.get("evidenceId"));
            String snippet = text(raw.get("snippet"));
            String candidateText = text(candidate.getOrDefault("summary", candidate.get("content")));
            if (evidenceId.isBlank() || snippet.isBlank() || snippet.length() > MAX_SNIPPET_LENGTH
                    || !containsNormalized(candidateText, snippet)) {
                continue;
            }
            if (!seen.add(evidenceId)) {
                continue;
            }
            Map<String, Object> verified = new LinkedHashMap<>();
            verified.put("evidenceId", evidenceId);
            copyIfPresent(verified, candidate, "paperId");
            copyIfPresent(verified, candidate, "chunkId");
            copyIfPresent(verified, candidate, "chunkKey");
            copyIfPresent(verified, candidate, "chunkType");
            copyIfPresent(verified, candidate, "sourceType");
            verified.put("title", candidate.getOrDefault("title", ""));
            verified.put("source", candidate.getOrDefault("source", ""));
            verified.put("year", candidate.getOrDefault("published", candidate.getOrDefault("year", "")));
            verified.put("snippet", snippet);
            verified.put("url", candidate.getOrDefault("sourceUrl", candidate.getOrDefault("url", "")));
            copyIfPresent(verified, candidate, "locator");
            copyIfPresent(verified, candidate, "score");
            verified.put("verificationStatus", EvidenceVerificationStatus.VERIFIED.name());
            result.add(verified);
        }
        return result;
    }

    /**
     * Agent 工具调用路径没有把候选缓存传回服务端时，只接受带稳定 ID 的证据并明确标记为未验证。
     * 调用方可以据此触发带候选缓存的 fallback，而不是误把自由文本当作已验证证据。
     */
    public List<Map<String, Object>> markAgentEvidenceUnverified(List<Map<String, Object>> rawEvidence) {
        if (rawEvidence == null || rawEvidence.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> raw : rawEvidence) {
            if (raw == null || result.size() >= MAX_EVIDENCE) continue;
            String evidenceId = text(raw.get("evidenceId"));
            if (evidenceId.isBlank() || !seen.add(evidenceId)) continue;
            Map<String, Object> item = new LinkedHashMap<>(raw);
            item.remove("url");
            item.put("verificationStatus", EvidenceVerificationStatus.UNVERIFIED.name());
            item.put("verificationReason", "Agent 未返回可供后端比对的候选正文");
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> findCandidate(Map<String, Object> raw,
                                              List<Map<String, Object>> candidates) {
        String evidenceId = text(raw.get("evidenceId"));
        if (!evidenceId.isBlank()) {
            for (Map<String, Object> candidate : candidates) {
                if (evidenceId.equals(text(candidate.get("evidenceId")))) return candidate;
            }
            return null;
        }
        String title = text(raw.get("title"));
        if (title.isBlank()) return null;
        return candidates.stream()
                .filter(candidate -> title.equalsIgnoreCase(text(candidate.get("title"))))
                .findFirst()
                .orElse(null);
    }

    private boolean containsNormalized(String source, String quote) {
        String normalizedSource = normalize(source);
        String normalizedQuote = normalize(quote);
        return !normalizedQuote.isBlank() && normalizedSource.contains(normalizedQuote);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source.containsKey(key) && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }
}
