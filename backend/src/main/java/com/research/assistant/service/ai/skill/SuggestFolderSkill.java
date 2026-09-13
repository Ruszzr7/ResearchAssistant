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

    public SuggestFolderSkill(PaperMapper paperMapper, FolderMapper folderMapper,
                              @Lazy ResearchToolAgent researchToolAgent, LLMService llmService,
                              RecommendationCache recommendationCache, ObjectMapper objectMapper) {
        this.paperMapper = paperMapper;
        this.folderMapper = folderMapper;
        this.researchToolAgent = researchToolAgent;
        this.llmService = llmService;
        this.recommendationCache = recommendationCache;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return Skills.SUGGEST_FOLDER;
    }

    @Override
    public String description() {
        return "为单篇论文或标题推荐所属文件夹。使用标题、关键词、摘要和目录树识别一级主题及二级分类；输出：Map{recommended, reason, suggestNew, newName, parentFolderId}。";
    }

    @Override
    public Class<SuggestFolderInput> inputType() {
        return SuggestFolderInput.class;
    }

    @Override
    public Map<String, Object> execute(SkillContext ctx, SuggestFolderInput input) {
        String title;
        String abstractText;
        String keywords;
        String cacheKey;

        if (input.paperId() != null) {
            Paper paper = paperMapper.selectById(input.paperId());
            if (paper == null) return fallbackSuggestion("论文不存在，请手动选择文件夹", false, null);
            title = paper.getTitle();
            abstractText = paper.getAbstractText();
            keywords = paper.getKeywords();
            cacheKey = recommendationCacheKey(paper.getId(), title, abstractText, keywords);

            Map<String, Object> cached = recommendationCache.get("folder", cacheKey);
            if (cached != null) {
                log.debug("文件夹推荐命中缓存 paperId={}", cacheKey);
                return cached;
            }
        } else {
            title = input.title() != null ? input.title() : "";
            abstractText = input.abstractText() != null ? input.abstractText() : "";
            keywords = input.keywords() != null ? input.keywords() : "";
            cacheKey = null;
        }

        // 文件夹推荐直接使用已经填充的元数据和目录树，只进行一次结构化 LLM 调用。
        // 这里不做额外召回，避免导入流程等待不必要的检索和二次重排序。
        Map<String, Object> result = doSuggestFolder(title, abstractText, keywords);
        if (cacheKey != null) {
            recommendationCache.put("folder", cacheKey, result);
        }
        return result;
    }

    private Map<String, Object> doSuggestFolder(String title, String abstractText, String keywords) {
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
        String folderList = buildFolderContext(folders, folderById);

        Folder contentMatch = findContentFolderMatch(title, abstractText, keywords, folders, folderById);

        try {
            var result = researchToolAgent.suggestFolder(
                    title,
                    keywords != null ? keywords : "",
                    abstractText != null ? abstractText : "",
                    folderList);
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
                        title, abstractText, keywords, folders, folderById);
            }
        } catch (Exception e) {
            log.warn("Agent 文件夹推荐失败，回退到字符串解析: {}", e.getMessage());
        }

        String prompt = "论文标题：" + title + "\n关键词：" + (keywords != null ? keywords : "")
                + "\n摘要：" + (abstractText != null ? abstractText : "")
                + "\n现有文件夹列表：\n" + folderList +
                "\n先根据关键词和摘要归纳主题、场景、方法和指标，再匹配目录及同级子目录的分类习惯。指标中要区分主优化目标和约束条件：如元数据存在 Ergodic Rate 或 Sum Rate，不得仅因出现 low-latency/URLLC 就命名为 Latency。请为这篇论文推荐最合适的现有文件夹。若没有合适文件夹，可建议新建；若新建子文件夹，newName 只填写子文件夹名称，parentFolderId 填现有父文件夹 ID。返回 JSON: {\"folderId\": 数字 或 null, \"reason\": \"一句话理由\", \"suggestNew\": true/false, \"newName\": \"建议新文件夹名（若 suggestNew 为 true）\", \"parentFolderId\": 数字 或 null}";
        try {
            String result = llmService.chat("你是一位学术文献管理助手。请为论文推荐最合适的文件夹。只返回JSON。", prompt);
            Map<?, ?> raw = objectMapper.readValue(JsonUtils.extractJson(result), Map.class);
            Map<String, Object> normalized = normalizeSuggestion(raw);
            return enforceNewChildSuggestion(normalized,
                    resolveBroadParent(normalized, contentMatch, folders, folderById),
                    title, abstractText, keywords, folders, folderById);
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
                                                            String keywords,
                                                            List<Folder> folders,
                                                            Map<Long, Folder> folderById) {
        if (broadParent == null || !hasChildren(broadParent, folders)) {
            return suggestion;
        }

        Long recommended = asLong(suggestion.get("recommended"));
        Long parentId = asLong(suggestion.get("parentFolderId"));
        boolean belongsToParent = Objects.equals(recommended, broadParent.getId())
                || Objects.equals(parentId, broadParent.getId());
        FolderCategory category = dominantChildCategory(broadParent, folders);
        // 模型已明确给出某个已有子目录时，只有标题/关键词中存在更强的、明确的
        // 分类信号才覆盖它。摘要里偶发的 "throughput" 等泛化表述不足以推翻
        // 已有的 SumRate 判断；反之，关键词中的 Ergodic Rate 足以否决 Latency。
        FolderTopic primaryTopic = category.findPrimaryTopic(title, keywords);
        Folder recommendedFolder = recommended == null ? null : folderById.get(recommended);
        boolean mismatchesPrimaryTopic = recommendedFolder != null
                && isDescendantOf(recommendedFolder, broadParent, folderById)
                && primaryTopic != null
                && !primaryTopic.matchesFolderName(recommendedFolder.getName());
        Folder existingTopicFolder = primaryTopic == null ? null
                : findExistingTopicFolder(broadParent, primaryTopic, folders, folderById);
        if (existingTopicFolder != null && (belongsToParent || mismatchesPrimaryTopic)) {
            Map<String, Object> normalized = new LinkedHashMap<>(suggestion);
            normalized.put("recommended", existingTopicFolder.getId());
            normalized.put("suggestNew", false);
            normalized.put("newName", null);
            normalized.put("parentFolderId", null);
            normalized.put("reason", "论文属于“" + broadParent.getName() + "”主题，主"
                    + category.label() + "为“" + primaryTopic.label() + "”，匹配现有文件夹“"
                    + existingTopicFolder.getName() + "”");
            return normalized;
        }
        if (!belongsToParent && !mismatchesPrimaryTopic) {
            return suggestion;
        }

        Map<String, Object> normalized = new LinkedHashMap<>(suggestion);
        normalized.put("recommended", null);
        normalized.put("suggestNew", true);
        normalized.put("parentFolderId", broadParent.getId());
        String name = stringValue(normalized.get("newName"));
        if (primaryTopic != null) {
            // 对“主指标”和“约束词”进行确定性兜底：例如存在 Ergodic Rate 时不接受 Latency。
            name = primaryTopic.label();
        } else if (name == null || name.isBlank()
                || (category != FolderCategory.GENERIC && !category.pattern().matcher(name).find())) {
            name = deriveNewFolderName(title, abstractText, keywords, broadParent.getName(), category);
        }
        normalized.put("newName", name);
        normalized.put("newFolderCategory", category.label());
        normalized.put("reason", "论文属于“" + broadParent.getName() + "”主题，现有子文件夹主要按"
                + category.label() + "分类，建议新建同类子文件夹“" + name + "”");
        return normalized;
    }

    /**
     * 目录名可能是中文的“和速率”而元数据写作 Ergodic Rate。以主题别名匹配，并优先最深层目录。
     */
    private Folder findExistingTopicFolder(Folder parent, FolderTopic topic,
                                           List<Folder> folders, Map<Long, Folder> folderById) {
        return folders.stream()
                .filter(folder -> isDescendantOf(folder, parent, folderById))
                .filter(folder -> topic.matchesFolderName(folder.getName()))
                .max(Comparator.comparingInt(folder -> folderDepth(folder, folderById)))
                .orElse(null);
    }

    private boolean hasChildren(Folder parent, List<Folder> folders) {
        return parent != null && folders.stream().anyMatch(folder ->
                Objects.equals(folder.getParentId(), parent.getId()));
    }

    private Folder resolveBroadParent(Map<String, Object> suggestion, Folder contentMatch,
                                      List<Folder> folders, Map<Long, Folder> folderById) {
        Folder fromContent = nearestParentWithChildren(contentMatch, folders, folderById);
        if (fromContent != null) return fromContent;
        Long recommended = asLong(suggestion.get("recommended"));
        Folder candidate = recommended == null ? null : folderById.get(recommended);
        Folder fromRecommendation = nearestParentWithChildren(candidate, folders, folderById);
        if (fromRecommendation != null) return fromRecommendation;
        Long parentId = asLong(suggestion.get("parentFolderId"));
        return nearestParentWithChildren(parentId == null ? null : folderById.get(parentId), folders, folderById);
    }

    private Folder nearestParentWithChildren(Folder folder, List<Folder> folders,
                                             Map<Long, Folder> folderById) {
        Folder current = folder;
        Set<Long> visited = new HashSet<>();
        while (current != null && current.getId() != null && visited.add(current.getId())) {
            if (hasChildren(current, folders)) return current;
            current = current.getParentId() == null ? null : folderById.get(current.getParentId());
        }
        return null;
    }

    private boolean isDescendantOf(Folder candidate, Folder parent, Map<Long, Folder> folderById) {
        Folder current = candidate;
        Set<Long> visited = new HashSet<>();
        while (current != null && current.getId() != null && visited.add(current.getId())) {
            if (Objects.equals(current.getId(), parent.getId())) return !Objects.equals(candidate.getId(), parent.getId());
            current = current.getParentId() == null ? null : folderById.get(current.getParentId());
        }
        return false;
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

    private String deriveNewFolderName(String title, String abstractText, String keywords, String parentName,
                                       FolderCategory category) {
        String value = ((keywords == null ? "" : keywords) + " " + (title == null ? "" : title)
                + " " + (abstractText == null ? "" : abstractText))
                .replace(parentName == null ? "" : parentName, " ")
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim();
        if (value.isBlank()) return "新子文件夹";

        String focused = category.extract(title, keywords, abstractText);
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

    private record FolderTopic(String label, Pattern pattern, List<String> aliases) {
        static FolderTopic of(String label, String expression, String... aliases) {
            return new FolderTopic(label, Pattern.compile(expression), List.of(aliases));
        }

        boolean matchesFolderName(String folderName) {
            if (folderName == null || folderName.isBlank()) return false;
            String compact = normalizeTopicToken(folderName);
            for (String alias : aliases) {
                String normalizedAlias = normalizeTopicToken(alias);
                if (normalizedAlias.isBlank()) continue;
                // "rate" 这类短通用词只能精确匹配，避免把 SumRate 误判为 Ergodic Rate。
                boolean matched = normalizedAlias.length() < 5
                        ? compact.equals(normalizedAlias)
                        : compact.contains(normalizedAlias);
                if (matched) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String normalizeTopicToken(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]", "");
    }

    private enum FolderCategory {
        METRIC("指标",
                Pattern.compile("(?i)(指标|a?ao?i|age|rate|throughput|fairness|latency|delay|efficiency|"
                        + "energy|error|bler|sum[- ]?rate|吞吐|速率|公平|时延|能效|误码)"),
                List.of(
                        FolderTopic.of("AAoI", "(?i)\\b(?:AAoI|AoI|age of information)\\b", "aoi", "aaoi", "age of information"),
                        FolderTopic.of("Ergodic Rate", "(?i)\\b(?:ergodic[- ]?(?:sum[- ]?)?rate|ergodic performance)\\b|遍历(?:和)?速率", "ergodic rate", "ergodic sum rate", "ergodic performance", "rate", "速率", "和速率", "遍历速率", "遍历和速率"),
                        FolderTopic.of("Sum Rate", "(?i)\\b(?:sum[- ]?rate|achievable rate|average rate)\\b|(?:和|总|可达)速率", "sum rate", "achievable rate", "average rate", "rate", "和速率", "总速率", "可达速率"),
                        FolderTopic.of("Rate Region", "(?i)\\b(?:rate region|rate[- ]?region)\\b", "rate region", "rate"),
                        FolderTopic.of("Throughput", "(?i)\\b(?:throughput|goodput|effective throughput)\\b", "throughput", "goodput"),
                        FolderTopic.of("Fairness", "(?i)\\b(?:fairness|max[- ]?min|minimum rate)\\b", "fairness", "max min"),
                        FolderTopic.of("Energy Efficiency", "(?i)\\b(?:energy efficiency|energy[- ]?efficient)\\b", "energy efficiency"),
                        FolderTopic.of("Error Probability", "(?i)\\b(?:error probability|block error rate|BLER)\\b", "error probability", "bler"),
                        FolderTopic.of("Latency", "(?i)\\b(?:latency|delay)\\b", "latency", "delay"))),
        SCENARIO("场景",
                Pattern.compile("(?i)(场景|satellite|vehicular|autonomous|vehicle|v2x|iot|uav|drone|"
                        + "network|卫星|车联网|自动驾驶|物联网)"),
                List.of(
                        FolderTopic.of("Autonomous Driving", "(?i)\\b(?:autonomous driving|autonomous vehicles?)\\b", "autonomous driving", "autonomous vehicle"),
                        FolderTopic.of("URLLC", "(?i)\\bURLLC\\b", "urllc"),
                        FolderTopic.of("Vehicular", "(?i)\\b(?:vehicular|vehicle|V2X)\\b", "vehicular", "vehicle", "v2x"),
                        FolderTopic.of("Satellite IoT", "(?i)\\b(?:satellite(?:[- ]based)? IoT|satellite)\\b", "satellite iot", "satellite"),
                        FolderTopic.of("IoT", "(?i)\\bIoT\\b", "iot"),
                        FolderTopic.of("UAV", "(?i)\\b(?:UAV|drone)\\b", "uav", "drone"))),
        METHOD("方法",
                Pattern.compile("(?i)(方法|算法|method|algorithm|optimization|beamforming|learning|"
                        + "rsma|noma|强化学习|波束)"),
                List.of(
                        FolderTopic.of("RSMA", "(?i)\\bRSMA\\b", "rsma"),
                        FolderTopic.of("NOMA", "(?i)\\bNOMA\\b", "noma"),
                        FolderTopic.of("Beamforming", "(?i)\\bbeamforming\\b", "beamforming"),
                        FolderTopic.of("Power Allocation", "(?i)\\bpower allocation\\b", "power allocation"),
                        FolderTopic.of("Reinforcement Learning", "(?i)\\breinforcement learning\\b", "reinforcement learning"))),
        GENERIC("主题", Pattern.compile("$^"), List.of());

        private final String label;
        private final Pattern pattern;
        private final List<FolderTopic> topics;

        FolderCategory(String label, Pattern pattern, List<FolderTopic> topics) {
            this.label = label;
            this.pattern = pattern;
            this.topics = topics;
        }

        String label() { return label; }

        Pattern pattern() { return pattern; }

        String extract(String title, String keywords, String abstractText) {
            FolderTopic topic = findTopic(title, keywords, abstractText);
            return topic == null ? null : topic.label();
        }

        FolderTopic findTopic(String title, String keywords, String abstractText) {
            FolderTopic explicit = findPrimaryTopic(title, keywords);
            if (explicit != null) return explicit;
            String body = abstractText == null ? "" : abstractText;
            for (FolderTopic topic : topics) {
                if (topic.pattern().matcher(body).find()) return topic;
            }
            return null;
        }

        FolderTopic findPrimaryTopic(String title, String keywords) {
            String primaryMetadata = (title == null ? "" : title) + " " + (keywords == null ? "" : keywords);
            for (FolderTopic topic : topics) {
                if (topic.pattern().matcher(primaryMetadata).find()) return topic;
            }
            return null;
        }
    }

    /**
     * 从元数据中定位可能相关的目录，用于确定“应在何处新建子目录”。最终落点仍由一次结构化 Agent 调用决定。
     */
    private Folder findContentFolderMatch(String title, String abstractText, String keywords,
                                          List<Folder> folders, Map<Long, Folder> folderById) {
        String content = normalizeForMatch((title == null ? "" : title) + " "
                + (keywords == null ? "" : keywords) + " "
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

    private record FolderMatch(Folder folder, int score, int depth) {
    }

    private String recommendationCacheKey(Long paperId, String title, String abstractText, String keywords) {
        return paperId + ":" + Integer.toHexString(Objects.hash(title, abstractText, keywords));
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

    /**
     * 把树状目录和同级分类习惯一起交给模型，避免模型只看见孤立名称后把“约束词”误当作子主题。
     */
    private String buildFolderContext(List<Folder> folders, Map<Long, Folder> folderById) {
        StringBuilder context = new StringBuilder("目录树：\n");
        for (Folder folder : folders) {
            context.append("- ").append(folderPath(folder, folderById))
                    .append(" (id=").append(folder.getId()).append(")\n");
        }
        context.append("同级分类提示：\n");
        for (Folder parent : folders) {
            if (!hasChildren(parent, folders)) continue;
            List<String> children = folders.stream()
                    .filter(child -> Objects.equals(child.getParentId(), parent.getId()))
                    .map(Folder::getName)
                    .filter(Objects::nonNull)
                    .toList();
            FolderCategory category = dominantChildCategory(parent, folders);
            context.append("- “").append(folderPath(parent, folderById)).append("” 的直接子文件夹：")
                    .append(String.join("、", children))
                    .append("；主要分类维度：").append(category.label()).append("\n");
        }
        return context.toString();
    }
}
