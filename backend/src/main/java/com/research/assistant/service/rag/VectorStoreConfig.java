package com.research.assistant.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.mapper.PaperChunkMapper;
import com.research.assistant.service.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 向量存储配置 —— 根据 {@code settings.vector_store_provider} 决定使用内存还是 Qdrant。
 *
 * <p>默认 provider 为 {@code memory}，保证零配置/测试环境可直接运行。
 * 切换 provider 后需要重启应用。
 */
@Configuration
public class VectorStoreConfig {

    private static final String PROVIDER_QDRANT = "qdrant";

    @Bean
    @Primary
    public VectorStore vectorStore(SettingsService settingsService,
                                   PaperChunkMapper paperChunkMapper,
                                   ObjectMapper objectMapper) {
        String provider = settingsService.getValue("vector_store_provider");
        boolean qdrantEnabled = PROVIDER_QDRANT.equalsIgnoreCase(provider);

        InMemoryVectorStore memory = new InMemoryVectorStore(paperChunkMapper, objectMapper);

        if (qdrantEnabled) {
            QdrantVectorStore qdrant = new QdrantVectorStore(settingsService, paperChunkMapper, objectMapper);
            return new VectorStoreRouter(memory, qdrant, true);
        }
        return memory;
    }
}
