package com.research.assistant.service.ai.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.research.assistant.common.JsonUtils;
import com.research.assistant.entity.Folder;
import com.research.assistant.entity.Paper;
import com.research.assistant.mapper.FolderMapper;
import com.research.assistant.mapper.PaperMapper;
import com.research.assistant.service.LLMService;
import com.research.assistant.service.ai.ResearchToolAgent;
import com.research.assistant.service.ai.skill.io.SuggestFolderInput;
import com.research.assistant.service.cache.RecommendationCache;
import com.research.assistant.service.rag.RagRetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文件夹推荐 Skill。
 */
@Component
public class SuggestFolderSkill implements Skill<SuggestFolderInput, Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(SuggestFolderSkill.class);

    private final PaperMapper paperMapper;
    private final FolderMapper folderMapper;
    private final ResearchToolAgent researchToolAgent;
    private final LLMService llmService;
    private final RecommendationCache recommendationCache;
    private final ObjectMapper objectMapper;
    private final RagRetrievalService ragRetrievalService;

    public SuggestFolderSkill(PaperMapper paperMapper, FolderMapper folderMapper,
                              @Lazy ResearchToolAgent researchToolAgent, LLMService llmService,
                              RecommendationCache recommendationCache, ObjectMapper objectMapper,
                              RagRetrievalService ragRetrievalService) {
        this.paperMapper = paperMapper;
        this.folderMapper = folderMapper;
        this.researchToolAgent = researchToolAgent;
        this.llmService = llmService;
        this.recommendationCache = recommendationCache;
        this.objectMapper = objectMapper;
        this.ragRetrievalService = ragRetrievalService;
    }

    @Override
    public String name() {
        return Skills.SUGGEST_FOLDER;
    }

    @Override
    public String description() {
        return "为单篇论文或标题推荐所属文件夹。输入：{\"paperId\": Long}（已有论文）或 {\"title\": \"论文标题\"}（导入前）；输出：Map{recommended, reason, suggestNew, newName, parentFolderId}。";
    }

    @Override
    public Class<SuggestFolderInput> inputType() {
        return SuggestFolderInput.class;
    }

    @Override
    public Map<String, Object> execute(SkillContext ctx, SuggestFolderInput input) {
        String title;
        String abstractText;
        Long cacheKey;

        if (input.paperId() != null) {
            Paper paper = paperMapper.selectById(input.paperId());
            if (paper == null) return fallbackSuggestion("论文不存在，请手动选择文件夹", false, null);
            title = paper.getTitle();
            abstractText = paper.getAbstractText();
            cacheKey = input.paperId();

            Map<String, Object> cached = recommendationCache.get("folder", cacheKey);
            if (cached != null) {
                log.debug("文件夹推荐命中缓存 paperId={}", cacheKey);
                return cached;
            }
        } else {
            title = input.title() != null ? input.title() : "";
            abstractText = input.abstractText() != null ? input.abstractText() : "";
            cacheKey = null;
        }

        // 已入库论文使用 RAG 重排序；导入前保留向量召回但跳过一次额外的 LLM 重排序。
        Map<String, Object> result = doSuggestFolder(title, abstractText, cacheKey != null);
        if (cacheKey != null) {
            recommendationCache.put("folder", cacheKey, result);
        }
        return result;
    }

    private Map<String, Object> doSuggestFolder(String title, String abstractText, boolean useRag) {
        List<Folder> folders = folderMapper.selectList(null);
        if (folders.isEmpty()) {
            return fallbackSuggestion("当前没有可用文件夹", true, "新文件夹");
        }

        Map<Long, Folder> folderById = new LinkedHashMap<>();
        for (Folder folder : folders) {
            if (folder.getId() != null) {
                folderById.put(folder.getId(), folder);
            }
        }
        StringBuilder folderList = new StringBuilder();
        for (Folder f : folders) {
            folderList.append("- ").append(folderPath(f, folderById))
                    .append(" (id=").append(f.getId()).append(")\n");
        }

        Folder contentMatch = findContentFolderMatch(title, abstractText, folders, folderById);
        if (contentMatch != null && !hasChildren(contentMatch, folders)) {
            return folderSuggestion(contentMatch.getId(), "根据论文内容匹配到文件夹");
        }

        String relatedSnippets;
        try {
            if (useRag) {
            relatedSnippets = ragRetrievalService.retrieveAndRerankAsContext(
                    title + "\n" + (abstractText != null ? abstractText : ""), 8, 0.65);
            } else {
                relatedSnippets = ragRetrievalService.retrieveAsContext(
                        title + "\n" + (abstractText != null ? abstractText : ""), 8, 0.65);
            }
        } catch (Exception e) {
            log.warn("文件夹推荐检索相关论文失败，继续使用标题和摘要: {}", e.getMessage());
            relatedSnippets = "";
        }

        try {
            var result = researchToolAgent.suggestFolder(
                    title,
                    abstractText != null ? abstractText : "",
                    folderList.toString(),
                    relatedSnippets);
            if (result != null && result.content() != null) {
                var pojo = result.content();
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("recommended", pojo.getFolderId());
                map.put("reason", pojo.getReason());
                map.put("suggestNew", pojo.isSuggestNew());
                map.put("newName", pojo.getNewName());
                map.put("parentFolderId", pojo.getParentFolderId());
                return enforceNewChildSuggestion(map,
                        resolveBroadParent(map, contentMatch, folders, folderById),
                        title, abstractText, folders);
            }
        } catch (Exception e) {
            log.warn("Agent 文件夹推荐失败，回退到字符串解析: {}", e.getMessage());
        }

        String prompt = "论文标题：" + title + "\n摘要：" + (abstractText != null ? abstractText : "")
                + relatedSnippets
                + "\n现有文件夹列表：\n" + folderList +
                "\n请为这篇论文推荐最合适的现有文件夹。若没有合适文件夹，可建议新建；若新建子文件夹，newName 只填写子文件夹名称，parentFolderId 填现有父文件夹 ID。返回 JSON: {\"folderId\": 数字 或 null, \"reason\": \"一句话理由\", \"suggestNew\": true/false, \"newName\": \"建议新文件夹名（若 suggestNew 为 true）\", \"parentFolderId\": 数字 或 null}";
        try {
            String result = llmService.chat("你是一位学术文献管理助手。请为论文推荐最合适的文件夹。只返回JSON。", prompt);
            Map<?, ?> raw = objectMapper.readValue(JsonUtils.extractJson(result), Map.class);
            Map<String, Object> normalized = normalizeSuggestion(raw);
            return enforceNewChildSuggestion(normalized,
                    resolveBroadParent(normalized, contentMatch, folders, folderById),
                    title, abstractText, folders);
        } catch (Exception e) {
            log.warn("字符串解析文件夹推荐失败: {}", e.getMessage());
            return fallbackSuggestion("AI 推荐暂不可用，请手动选择文件夹", false, null);
        }
    }

    /**
     * Agent 和字符串解析使用的字段名不同，统一成前端约定的 recommended。
     */
    private Map<String, Object> normalizeSuggestion(Map<?, ?> raw) {
        if (raw == null) {
            return fallbackSuggestion("AI 推荐暂不可用，请手动选择文件夹", false, null);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("recommended", raw.containsKey("recommended")
                ? raw.get("recommended") : raw.get("folderId"));
        normalized.put("reason", raw.get("reason"));
        normalized.put("suggestNew", Boolean.TRUE.equals(raw.get("suggestNew")));
        normalized.put("newName", raw.get("newName"));
        normalized.put("parentFolderId", raw.containsKey("parentFolderId")
                ? raw.get("parentFolderId") : raw.get("newParentId"));
        return normalized;
    }

    private Map<String, Object> fallbackSuggestion(String reason, boolean suggestNew, String newName) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("recommended", null);
        fallback.put("reason", reason);
        fallback.put("suggestNew", suggestNew);
        fallback.put("newName", newName);
        fallback.put("parentFolderId", null);
        return fallback;
    }

    /**
     * 父文件夹已有子文件夹时，不能因为论文标题命中了父名称就直接把论文放进父目录。
     * 让 Agent 先判断同级子主题；如果 Agent 仍返回父目录，则转为在父目录下新建子文件夹，
     * 避免把不同方法/指标的论文全部堆在同一个父文件夹中。
     */
    private Map<String, Object> enforceNewChildSuggestion(Map<String, Object> suggestion,
                                                            Folder broadParent,
                                                            String title,
                                                            String abstractText,
                                                            List<Folder> folders) {
        if (broadParent == null || !hasChildren(broadParent, folders)) {
            return suggestion;
        }

        Long recommended = asLong(suggestion.get("recommended"));
        Long parentId = asLong(suggestion.get("parentFolderId"));
        boolean belongsToParent = Objects.equals(recommended, broadParent.getId())
                || Objects.equals(parentId, broadParent.getId());
        if (!belongsToParent) {
            return suggestion;
        }

        Map<String, Object> normalized = new LinkedHashMap<>(suggestion);
        normalized.put("recommended", null);
        normalized.put("suggestNew", true);
        normalized.put("parentFolderId", broadParent.getId());
        FolderCategory category = dominantChildCategory(broadParent, folders);
        String name = stringValue(normalized.get("newName"));
        if (name == null || name.isBlank() ||
                (category != FolderCategory.GENERIC && !category.pattern().matcher(name).find())) {
            name = deriveNewFolderName(title, abstractText, broadParent.getName(), category);
            normalized.put("newName", name);
        }
        normalized.put("newFolderCategory", category.label());
        normalized.put("reason", "论文属于“" + broadParent.getName() + "”主题，现有子文件夹主要按"
                + category.label() + "分类，建议新建同类子文件夹“" + name + "”");
        return normalized;
    }

    private boolean hasChildren(Folder parent, List<Folder> folders) {
        return parent != null && folders.stream().anyMatch(folder ->
                Objects.equals(folder.getParentId(), parent.getId()));
    }

    private Folder resolveBroadParent(Map<String, Object> suggestion, Folder contentMatch,
                                      List<Folder> folders, Map<Long, Folder> folderById) {
        if (contentMatch != null && hasChildren(contentMatch, folders)) {
            return contentMatch;
        }
        Long recommended = asLong(suggestion.get("recommended"));
        Folder candidate = recommended == null ? null : folderById.get(recommended);
        return hasChildren(candidate, folders) ? candidate : contentMatch;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String deriveNewFolderName(String title, String abstractText, String parentName,
                                       FolderCategory category) {
        String value = ((title == null ? "" : title) + " " + (abstractText == null ? "" : abstractText))
                .replace(parentName == null ? "" : parentName, " ")
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim();
        if (value.isBlank()) return "新子文件夹";

        String focused = category.extract(value);
        if (focused != null && !focused.isBlank()) return focused;

        String[] stopWords = {"the", "and", "for", "with", "from", "using", "based", "via", "method",
                "approach", "framework", "study", "analysis", "novel", "efficient", "learning", "paper"};
        List<String> words = new ArrayList<>();
        for (String word : value.split("\\s+")) {
            String lower = word.toLowerCase(Locale.ROOT);
            if (word.length() < 3 || java.util.Arrays.asList(stopWords).contains(lower)) continue;
            words.add(word);
            if (words.size() == 3) break;
        }
        return words.isEmpty() ? "新子文件夹" : String.join(" ", words);
    }

    private FolderCategory dominantChildCategory(Folder parent, List<Folder> folders) {
        Map<FolderCategory, Integer> counts = new LinkedHashMap<>();
        for (Folder child : folders) {
            if (!Objects.equals(child.getParentId(), parent.getId())) continue;
            counts.merge(classifyFolderName(child.getName()), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(FolderCategory.GENERIC);
    }

    private FolderCategory classifyFolderName(String name) {
        if (name == null) return FolderCategory.GENERIC;
        for (FolderCategory category : FolderCategory.values()) {
            if (category != FolderCategory.GENERIC && category.pattern().matcher(name).find()) {
                return category;
            }
        }
        return FolderCategory.GENERIC;
    }

    private enum FolderCategory {
        METRIC("指标",
                Pattern.compile("(?i)(指标|a?ao?i|age|rate|throughput|fairness|latency|delay|efficiency|"
                        + "energy|error|bler|sum[- ]?rate|吞吐|速率|公平|时延|能效|误码)"),
                Pattern.compile("(?i)\\b(?:AAoI|AoI|age of information|sum[- ]?rate|throughput|"
                        + "fairness|latency|delay|energy efficiency|error probability|BLER|spectral efficiency)\\b")),
        SCENARIO("场景",
                Pattern.compile("(?i)(场景|satellite|vehicular|autonomous|vehicle|v2x|iot|uav|drone|"
                        + "network|卫星|车联网|自动驾驶|物联网)"),
                Pattern.compile("(?i)\\b(?:satellite(?:[- ]based)? IoT|vehicular|autonomous driving|"
                        + "V2X|IoT|UAV|drone|wireless network)\\b")),
        METHOD("方法",
                Pattern.compile("(?i)(方法|算法|method|algorithm|optimization|beamforming|learning|"
                        + "rsma|noma|强化学习|波束)"),
                Pattern.compile("(?i)\\b(?:RSMA|NOMA|beamforming|power allocation|optimization|"
                        + "reinforcement learning|particle swarm|algorithm|scheme)\\b")),
        GENERIC("主题", Pattern.compile("$^"), Pattern.compile("$^"));

        private final String label;
        private final Pattern pattern;
        private final Pattern contentPattern;

        FolderCategory(String label, Pattern pattern, Pattern contentPattern) {
            this.label = label;
            this.pattern = pattern;
            this.contentPattern = contentPattern;
        }

        String label() { return label; }

        Pattern pattern() { return pattern; }

        String extract(String text) {
            var matcher = contentPattern.matcher(text);
            if (!matcher.find()) return null;
            String value = matcher.group().trim();
            if (value.equalsIgnoreCase("age of information")) return "AoI";
            if (value.equalsIgnoreCase("satellite-based Internet of Things")) return "Satellite IoT";
            return value.replaceAll("\\s+", " ");
        }
    }

    /**
     * 对名称和论文内容做轻量级高置信度匹配。多个层级同时命中时，优先选择更深的子文件夹。
     * 无法明确匹配时仍交给 RAG/Agent 做语义判断。
     */
    private Folder findContentFolderMatch(String title, String abstractText,
                                          List<Folder> folders, Map<Long, Folder> folderById) {
        String content = normalizeForMatch((title == null ? "" : title) + " "
                + (abstractText == null ? "" : abstractText));
        String compactContent = content.replaceAll("[^\\p{L}\\p{Nd}]", "");
        if (compactContent.isBlank()) {
            return null;
        }

        List<FolderMatch> matches = new ArrayList<>();
        for (Folder folder : folders) {
            String name = folder.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            String normalizedName = normalizeForMatch(name);
            String compactName = normalizedName.replaceAll("[^\\p{L}\\p{Nd}]", "");
            if (compactName.length() < 3 || !compactContent.contains(compactName)) {
                continue;
            }
            int depth = folderDepth(folder, folderById);
            int score = compactName.length() + depth * 3;
            matches.add(new FolderMatch(folder, score, depth));
        }

        return matches.stream()
                .max(Comparator.comparingInt(FolderMatch::score)
                        .thenComparingInt(match -> match.folder().getName().length()))
                .map(FolderMatch::folder)
                .orElse(null);
    }

    private String normalizeForMatch(String value) {
        return Pattern.compile("([a-z])([A-Z])")
                .matcher(value)
                .replaceAll("$1 $2")
                .toLowerCase(Locale.ROOT);
    }

    private int folderDepth(Folder folder, Map<Long, Folder> folderById) {
        int depth = 0;
        Set<Long> visited = new HashSet<>();
        Folder current = folder;
        while (current != null && current.getId() != null && visited.add(current.getId())) {
            depth++;
            current = current.getParentId() == null ? null : folderById.get(current.getParentId());
        }
        return depth;
    }

    private Map<String, Object> folderSuggestion(Long folderId, String reason) {
        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("recommended", folderId);
        suggestion.put("reason", reason);
        suggestion.put("suggestNew", false);
        suggestion.put("newName", null);
        suggestion.put("parentFolderId", null);
        return suggestion;
    }

    private record FolderMatch(Folder folder, int score, int depth) {
    }

    private String folderPath(Folder folder, Map<Long, Folder> folderById) {
        Deque<String> path = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        Folder current = folder;
        while (current != null && visited.add(current.getId())) {
            if (current.getName() != null && !current.getName().isBlank()) {
                path.addFirst(current.getName().trim());
            }
            current = current.getParentId() == null ? null : folderById.get(current.getParentId());
        }
        return String.join(" / ", path);
    }
}
