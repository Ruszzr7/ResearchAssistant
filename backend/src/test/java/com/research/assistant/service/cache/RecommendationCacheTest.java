package com.research.assistant.service.cache;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecommendationCache} 单元测试。
 */
class RecommendationCacheTest {

    private final RecommendationCache cache = new RecommendationCache();

    @Test
    void shouldCacheTags() {
        List<String> tags = List.of("Transformer", "Attention");
        cache.put("tag", 1L, tags);

        List<String> cached = cache.get("tag", 1L);
        assertThat(cached).isEqualTo(tags);

        Object missing = cache.get("tag", 2L);
        assertThat(missing).isNull();
    }

    @Test
    void shouldCacheFolderSuggestion() {
        Map<String, Object> folder = Map.of("recommended", 3, "reason", "同领域");
        cache.put("folder", 1L, folder);

        Map<String, Object> cached = cache.get("folder", 1L);
        assertThat(cached).isEqualTo(folder);
    }

    @Test
    void shouldCacheReadingStatus() {
        Map<String, Object> status = Map.of("status", "READING", "reason", "经典论文");
        cache.put("status", 1L, status);

        Map<String, Object> cached = cache.get("status", 1L);
        assertThat(cached).isEqualTo(status);
    }

    @Test
    void shouldInvalidateAll() {
        cache.put("tag", 1L, List.of("A"));
        cache.invalidateAll();

        Object cached = cache.get("tag", 1L);
        assertThat(cached).isNull();
    }
}
