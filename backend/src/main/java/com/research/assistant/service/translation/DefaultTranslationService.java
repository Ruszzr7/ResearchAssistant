package com.research.assistant.service.translation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.research.assistant.dto.translation.TranslationItem;
import com.research.assistant.dto.translation.TranslationRequest;
import com.research.assistant.dto.translation.TranslationResponse;
import com.research.assistant.dto.translation.TranslationStatus;
import com.research.assistant.service.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DefaultTranslationService implements TranslationService {

    private static final int MAX_TOTAL_CHARACTERS = 120_000;
    private static final int MAX_CHUNK_CHARACTERS = 8_000;
    private static final int MAX_PROVIDER_ITEMS = 50;
    // Leave room for JSON escaping and request metadata under DeepL's 128 KiB body limit.
    private static final int MAX_PROVIDER_UTF8_BYTES = 48 * 1024;

    private final SettingsService settingsService;
    private final Map<String, TranslationProvider> providers;
    private final String defaultProvider;
    private final String glossaryVersion;
    private final Cache<CacheKey, CachedTranslation> cache;
    private final AcademicTextProtector textProtector = new AcademicTextProtector();

    public DefaultTranslationService(
            SettingsService settingsService,
            List<TranslationProvider> providers,
            @Value("${app.translation.provider:deepl}") String defaultProvider,
            @Value("${app.translation.glossary-version:v1}") String glossaryVersion,
            @Value("${app.translation.cache-maximum-size:1000}") long cacheMaximumSize,
            @Value("${app.translation.cache-expire-after-access:24h}") Duration cacheTtl) {
        this.settingsService = settingsService;
        Map<String, TranslationProvider> indexed = new LinkedHashMap<>();
        for (TranslationProvider provider : providers) {
            indexed.put(provider.id().toLowerCase(Locale.ROOT), provider);
        }
        this.providers = Map.copyOf(indexed);
        this.defaultProvider = normalized(defaultProvider, "deepl");
        this.glossaryVersion = normalized(glossaryVersion, "v1");
        this.cache = Caffeine.newBuilder()
                .maximumSize(Math.max(100, Math.min(cacheMaximumSize, 10_000)))
                .expireAfterAccess(safeTtl(cacheTtl))
                .build();
    }

    @Override
    public TranslationStatus status() {
        TranslationProvider provider = provider();
        return new TranslationStatus(provider.id(), provider.configured(), List.of("ZH", "EN"));
    }

    @Override
    public TranslationResponse translate(TranslationRequest request) {
        if (request == null || request.texts() == null) throw new IllegalArgumentException("texts required");
        int totalCharacters = request.texts().stream().mapToInt(String::length).sum();
        if (totalCharacters > MAX_TOTAL_CHARACTERS) {
            throw new IllegalArgumentException("translation request too large");
        }
        TranslationLanguage source = TranslationLanguage.source(request.sourceLanguage());
        TranslationLanguage target = TranslationLanguage.target(request.targetLanguage());
        TranslationProvider provider = provider();
        if (!provider.configured()) {
            throw new TranslationException("PROVIDER_NOT_CONFIGURED",
                    "DeepL 翻译服务尚未配置", HttpStatus.SERVICE_UNAVAILABLE, false);
        }

        List<MutableItem> items = new ArrayList<>(request.texts().size());
        List<PendingChunk> pending = new ArrayList<>();
        for (int itemIndex = 0; itemIndex < request.texts().size(); itemIndex++) {
            String text = request.texts().get(itemIndex);
            CacheKey key = new CacheKey(provider.id(), source.responseCode(), target.responseCode(),
                    sha256(text), glossaryVersion);
            CachedTranslation cached = cache.getIfPresent(key);
            MutableItem item = new MutableItem(key, cached);
            items.add(item);
            if (cached == null) {
                List<String> chunks = chunks(text);
                for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
                    pending.add(new PendingChunk(itemIndex, chunkIndex,
                            textProtector.protect(chunks.get(chunkIndex))));
                }
            }
        }

        translatePending(provider, source, target, pending, items);
        List<TranslationItem> responseItems = new ArrayList<>(items.size());
        boolean allCached = true;
        for (MutableItem item : items) {
            CachedTranslation translated = item.result();
            if (translated == null) {
                throw new TranslationException("INCOMPLETE_PROVIDER_RESPONSE",
                        "翻译服务未返回完整内容，请重试", HttpStatus.BAD_GATEWAY, true);
            }
            responseItems.add(new TranslationItem(
                    translated.text(), translated.detectedSourceLanguage(), item.wasCached()));
            allCached &= item.wasCached();
        }
        return new TranslationResponse(provider.id(), source.responseCode(), target.responseCode(),
                List.copyOf(responseItems), allCached);
    }

    private void translatePending(TranslationProvider provider,
                                  TranslationLanguage source,
                                  TranslationLanguage target,
                                  List<PendingChunk> pending,
                                  List<MutableItem> items) {
        int cursor = 0;
        while (cursor < pending.size()) {
            int end = cursor;
            int bytes = 0;
            while (end < pending.size() && end - cursor < MAX_PROVIDER_ITEMS) {
                int nextBytes = pending.get(end).protectedText().getBytes(StandardCharsets.UTF_8).length;
                if (end > cursor && bytes + nextBytes > MAX_PROVIDER_UTF8_BYTES) break;
                bytes += nextBytes;
                end++;
            }
            List<PendingChunk> batch = pending.subList(cursor, end);
            List<TranslationProvider.ProviderTranslation> translated = provider.translate(
                    batch.stream().map(PendingChunk::protectedText).toList(), source, target);
            if (translated.size() != batch.size()) {
                throw new TranslationException("INCOMPLETE_PROVIDER_RESPONSE",
                        "翻译服务未返回完整内容，请重试", HttpStatus.BAD_GATEWAY, true);
            }
            for (int index = 0; index < batch.size(); index++) {
                PendingChunk chunk = batch.get(index);
                TranslationProvider.ProviderTranslation result = translated.get(index);
                items.get(chunk.itemIndex()).addChunk(chunk.chunkIndex(),
                        textProtector.restore(result.text()), result.detectedSourceLanguage());
            }
            cursor = end;
        }

        for (MutableItem item : items) {
            if (!item.wasCached()) {
                CachedTranslation translated = item.complete();
                cache.put(item.key(), translated);
            }
        }
    }

    private TranslationProvider provider() {
        String configured = settingsService.getValue("translation_provider");
        String id = normalized(configured, defaultProvider);
        TranslationProvider provider = providers.get(id);
        if (provider == null) {
            throw new TranslationException("UNSUPPORTED_PROVIDER",
                    "翻译服务配置不受支持", HttpStatus.SERVICE_UNAVAILABLE, false);
        }
        return provider;
    }

    static List<String> chunks(String text) {
        if (text.length() <= MAX_CHUNK_CHARACTERS) return List.of(text);
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + MAX_CHUNK_CHARACTERS);
            if (end < text.length()) {
                int floor = start + MAX_CHUNK_CHARACTERS / 2;
                int boundary = bestBoundary(text, floor, end);
                if (boundary > floor) end = boundary;
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    private static int bestBoundary(String text, int floor, int end) {
        String[] separators = {"\n\n", "\n", "。", ". ", "; ", "；"};
        for (String separator : separators) {
            int index = text.lastIndexOf(separator, end - 1);
            if (index >= floor) return index + separator.length();
        }
        return end;
    }

    private String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toLowerCase(Locale.ROOT);
    }

    private Duration safeTtl(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) return Duration.ofHours(24);
        return value.compareTo(Duration.ofDays(30)) > 0 ? Duration.ofDays(30) : value;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record CacheKey(String provider, String sourceLanguage, String targetLanguage,
                            String contentHash, String glossaryVersion) {
    }

    private record CachedTranslation(String text, String detectedSourceLanguage) {
    }

    private record PendingChunk(int itemIndex, int chunkIndex, String protectedText) {
    }

    private final class MutableItem {
        private final CacheKey key;
        private final CachedTranslation cached;
        private final Map<Integer, String> chunks = new HashMap<>();
        private String detectedSourceLanguage = "AUTO";

        private MutableItem(CacheKey key, CachedTranslation cached) {
            this.key = key;
            this.cached = cached;
        }

        void addChunk(int index, String text, String detected) {
            chunks.put(index, text);
            if ("AUTO".equals(detectedSourceLanguage) && detected != null && !detected.isBlank()) {
                detectedSourceLanguage = TranslationLanguage.normalizeDetected(detected);
            }
        }

        CachedTranslation complete() {
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < chunks.size(); index++) {
                String chunk = chunks.get(index);
                if (chunk == null) {
                    throw new TranslationException("INCOMPLETE_PROVIDER_RESPONSE",
                            "翻译服务未返回完整内容，请重试", HttpStatus.BAD_GATEWAY, true);
                }
                text.append(chunk);
            }
            return new CachedTranslation(text.toString(), detectedSourceLanguage);
        }

        CachedTranslation result() {
            if (cached != null) return cached;
            CachedTranslation cachedAfterCall = cache.getIfPresent(key);
            return cachedAfterCall == null ? complete() : cachedAfterCall;
        }

        CacheKey key() {
            return key;
        }

        boolean wasCached() {
            return cached != null;
        }
    }
}
