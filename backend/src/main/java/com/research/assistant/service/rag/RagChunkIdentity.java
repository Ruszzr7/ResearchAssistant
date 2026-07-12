package com.research.assistant.service.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 生成稳定的 RAG chunk 标识和内容摘要。
 *
 * <p>标识不依赖数据库自增 id，因此可以同时用于 MySQL 记录、内存索引和 Qdrant 点。</p>
 */
public final class RagChunkIdentity {

    private RagChunkIdentity() {
    }

    public static String contentHash(String content) {
        String normalized = content == null ? "" : content.trim().replaceAll("\\s+", " ");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 不支持 SHA-256", e);
        }
    }

    public static String chunkKey(Long paperId, int indexVersion, int chunkOrder, String content) {
        return "p" + paperId + "-v" + indexVersion + "-c" + chunkOrder + "-" + contentHash(content).substring(0, 16);
    }

    public static String evidenceId(Long paperId, Integer indexVersion, String chunkKey) {
        return "local:" + paperId + ":v" + (indexVersion == null ? 1 : indexVersion)
                + ":" + (chunkKey == null || chunkKey.isBlank() ? "unknown" : chunkKey);
    }
}
