package com.research.assistant.service.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 推荐结果本地缓存。
 *
 * <p>用于缓存标签、文件夹、阅读状态推荐结果，减少重复 LLM 调用。</p>
 */
@Component
public class RecommendationCache {

    private static final int MAX_SIZE = 1000;
    private static final Duration TTL = Duration.ofMinutes(15);

    private final Cache<String, Object> cache;

    public RecommendationCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterWrite(TTL)
                .recordStats()
                .build();
    }

    /**
     * 获取缓存值。
     *
     * @param type   推荐类型，如 "tag" / "folder" / "status"
     * @param paperId 论文 ID
     * @param <T>     期望类型
     * @return 缓存值或 null
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String type, Long paperId) {
        return (T) cache.getIfPresent(key(type, paperId));
    }

    /**
     * 允许推荐逻辑使用内容指纹作为缓存键。论文补全摘要或关键词后，旧推荐不会被继续复用。
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String type, String contentKey) {
        return (T) cache.getIfPresent(key(type, contentKey));
    }

    /**
     * 写入缓存。
     *
     * @param type    推荐类型
     * @param paperId 论文 ID
     * @param value   推荐结果
     */
    public void put(String type, Long paperId, Object value) {
        cache.put(key(type, paperId), value);
    }

    /**
     * 写入以内容指纹区分的推荐结果。
     */
    public void put(String type, String contentKey, Object value) {
        cache.put(key(type, contentKey), value);
    }

    /**
     * 清空全部缓存（管理后台可用）。
     */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    private String key(String type, Long paperId) {
        return type + ":" + paperId;
    }

    private String key(String type, String contentKey) {
        return type + ":" + contentKey;
    }
}
