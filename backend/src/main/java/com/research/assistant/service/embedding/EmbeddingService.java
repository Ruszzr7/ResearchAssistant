package com.research.assistant.service.embedding;

import com.research.assistant.service.ai.LangChain4jModelFactory;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding 生成服务 —— 封装 OpenAI 兼容的 embedding API。
 * <p>
 * 失败时抛出 {@link EmbeddingUnavailableException}，供上层降级到关键词/BM25。
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final LangChain4jModelFactory modelFactory;

    public EmbeddingService(LangChain4jModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 为单段文本生成 embedding。
     */
    public List<Float> embed(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            EmbeddingModel model = modelFactory.createEmbeddingModel();
            Embedding embedding = model.embed(TextSegment.from(text)).content();
            return toList(embedding.vector());
        } catch (Exception e) {
            log.warn("Embedding 生成失败: {}", e.getMessage());
            throw new EmbeddingUnavailableException("Embedding 服务不可用: " + e.getMessage(), e);
        }
    }

    /**
     * 批量生成 embedding，使用 Provider 支持的原生批量接口减少网络往返。
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<String> nonBlank = texts.stream()
                .filter(t -> t != null && !t.isBlank())
                .toList();
        if (nonBlank.isEmpty()) {
            return List.of();
        }
        try {
            EmbeddingModel model = modelFactory.createEmbeddingModel();
            List<TextSegment> segments = nonBlank.stream().map(TextSegment::from).toList();
            Response<List<Embedding>> response = model.embedAll(segments);
            List<Embedding> embeddings = response != null ? response.content() : null;
            if (embeddings == null || embeddings.size() != nonBlank.size()) {
                throw new EmbeddingUnavailableException("Embedding 返回数量不一致",
                        new IllegalStateException("provider returned an unexpected number of embeddings"));
            }
            return embeddings.stream().map(embedding -> toList(embedding.vector())).toList();
        } catch (Exception e) {
            log.warn("批量 Embedding 生成失败: {}", e.getMessage());
            throw new EmbeddingUnavailableException("Embedding 服务不可用: " + e.getMessage(), e);
        }
    }

    private List<Float> toList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }
}
