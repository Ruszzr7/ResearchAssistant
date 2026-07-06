package com.research.assistant.service.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据规范化工具。
 * <p>
 * 把来自 arXiv、Crossref 等不同来源的作者、标题、年份、arXiv ID 统一成数据库要求的格式。
 */
public class MetadataNormalizer {

    private static final Logger log = LoggerFactory.getLogger(MetadataNormalizer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MetadataNormalizer() {
        // 工具类
    }

    /**
     * 把作者字符串/列表规范化为 JSON 数组字符串：
     * <pre>[{"name":"Alice","role":""},{"name":"Bob","role":""}]</pre>
     *
     * @param raw 逗号/分号分隔的作者字符串、JSON 数组字符串、或 null/空
     * @return JSON 数组字符串；无法识别时返回 null
     */
    public static String normalizeAuthors(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            try {
                JsonNode array = OBJECT_MAPPER.readTree(trimmed);
                if (array.isArray() && !array.isEmpty()) {
                    // 若已是带 name 字段的对象数组，直接保留
                    if (array.get(0).has("name")) {
                        return trimmed;
                    }
                    // 否则按文本数组处理
                    List<String> names = new ArrayList<>();
                    for (JsonNode node : array) {
                        names.add(node.asText("").trim());
                    }
                    return buildAuthorsJson(names);
                }
            } catch (Exception e) {
                log.debug("作者字段看起来像 JSON 但解析失败：{}，按普通字符串处理", raw);
            }
        }
        return buildAuthorsJson(splitAuthorString(trimmed));
    }

    /**
     * 把作者名称列表规范化为 JSON 数组字符串。
     */
    public static String normalizeAuthors(List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        return buildAuthorsJson(names);
    }

    /**
     * 规范化标题：trim、合并连续空白。
     */
    public static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        return title.replaceAll("\\s+", " ").trim();
    }

    /**
     * 规范化年份为 4 位整数，并限制在合理范围（1900–2099）。
     */
    public static Integer normalizeYear(Object year) {
        if (year == null) {
            return null;
        }
        int value;
        if (year instanceof Number n) {
            value = n.intValue();
        } else {
            String s = year.toString().trim();
            if (s.length() >= 4) {
                // 支持 "2023" 或 "2023-01-15"
                s = s.substring(0, 4);
            }
            try {
                value = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return (value >= 1900 && value <= 2099) ? value : null;
    }

    /**
     * 规范化 arXiv ID：去除版本号、trim。
     */
    public static String normalizeArxivId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return id.replaceAll("v\\d+$", "").trim();
    }

    private static List<String> splitAuthorString(String raw) {
        List<String> names = new ArrayList<>();
        for (String sep : new String[]{";", ","}) {
            if (raw.contains(sep)) {
                for (String part : raw.split(sep)) {
                    String name = part.trim();
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
                return names;
            }
        }
        names.add(raw.trim());
        return names;
    }

    private static String buildAuthorsJson(List<String> names) {
        List<Map<String, String>> list = new ArrayList<>();
        for (String name : names) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Map<String, String> map = new LinkedHashMap<>();
            map.put("name", trimmed);
            map.put("role", "");
            list.add(map);
        }
        if (list.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("作者 JSON 序列化失败", e);
            return null;
        }
    }
}
