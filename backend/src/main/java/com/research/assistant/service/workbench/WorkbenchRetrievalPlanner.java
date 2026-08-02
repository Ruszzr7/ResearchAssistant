package com.research.assistant.service.workbench;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a cheap retrieval plan without adding another model call to every question. */
@Component
public class WorkbenchRetrievalPlanner {

    private static final Pattern TOKEN = Pattern.compile(
            "[\\p{IsLatin}\\p{IsGreek}\\p{N}_+/-]+|[\\p{IsHan}]{2,}");
    private static final Pattern QUOTED = Pattern.compile("[\\\"“”']([^\\\"“”']{2,80})[\\\"“”']");
    private static final Set<String> STOP_TERMS = Set.of(
            "论文", "文章", "问题", "回答", "请问", "请", "帮我", "为我", "告诉我", "找出", "找到",
            "哪里", "在哪", "位置", "什么", "如何", "怎么", "是否", "这个", "这一", "上述", "前面",
            "the", "and", "this", "that", "what", "where", "find", "locate", "show", "paper",
            "formula", "equation", "defined", "definition");
    private static final Map<String, List<String>> SCIENTIFIC_ALIASES = aliases();

    public WorkbenchRetrievalPlan plan(String query) {
        String source = query == null ? "" : query.trim();
        String normalized = normalize(source);
        boolean location = containsAny(normalized, "在哪", "哪里", "位置", "where", "locate", "find");
        boolean definition = containsAny(normalized, "是什么", "定义", "含义", "meaning", "defined", "definition");
        boolean broad = containsAny(normalized, "总结", "概述", "全文", "主要贡献", "创新点",
                "summary", "overview", "whole paper", "contribution");
        boolean comparison = containsAny(normalized, "比较", "对比", "区别", "comparison", "compare", "versus");
        boolean formula = containsAny(normalized, "公式", "方程", "推导", "equation", "formula");
        boolean referential = containsAny(normalized, "这个", "这一", "上述", "前面", "继续", "它", "该方法",
                "该公式", "this", "that", "above", "continue", "former", "latter");

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(normalized);
        while (matcher.find()) {
            String term = matcher.group().trim();
            if (isUseful(term)) terms.add(term);
        }
        SCIENTIFIC_ALIASES.forEach((trigger, expansions) -> {
            if (normalized.contains(trigger)) terms.addAll(expansions);
        });
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        Matcher quoted = QUOTED.matcher(source);
        while (quoted.find()) phrases.add(normalize(quoted.group(1)));
        terms.stream().filter(term -> term.length() >= 4).limit(4).forEach(phrases::add);

        WorkbenchRetrievalPlan.QueryType type = broad
                ? WorkbenchRetrievalPlan.QueryType.SUMMARY
                : comparison ? WorkbenchRetrievalPlan.QueryType.COMPARISON
                : location ? WorkbenchRetrievalPlan.QueryType.LOCATION
                : definition ? WorkbenchRetrievalPlan.QueryType.DEFINITION
                : WorkbenchRetrievalPlan.QueryType.EXPLANATION;
        return new WorkbenchRetrievalPlan(type, source, List.copyOf(terms), List.copyOf(phrases),
                formula || location, referential, broad, formula || definition ? 2 : 1);
    }

    private boolean isUseful(String term) {
        if (STOP_TERMS.contains(term)) return false;
        if (term.matches("[a-z]")) return false;
        return term.length() >= 2 || term.matches("[A-Z0-9]+") || term.matches("[α-ωΑ-Ω]");
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static Map<String, List<String>> aliases() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("功率", List.of("power"));
        values.put("系数", List.of("coefficient"));
        values.put("分配", List.of("allocation", "division", "distribution"));
        values.put("公共流", List.of("common stream", "common-stream"));
        values.put("私有流", List.of("private stream", "private-stream"));
        values.put("信干噪比", List.of("sinr", "signal-to-interference-plus-noise ratio"));
        values.put("信噪比", List.of("snr", "signal-to-noise ratio"));
        values.put("时延", List.of("latency", "delay"));
        values.put("吞吐量", List.of("throughput"));
        values.put("信道", List.of("channel"));
        values.put("车辆", List.of("vehicle"));
        values.put("实验", List.of("experiment", "experimental"));
        values.put("消融", List.of("ablation"));
        values.put("局限", List.of("limitation", "limitations"));
        values.put("贡献", List.of("contribution", "contributions"));
        values.put("方法", List.of("method", "approach"));
        values.put("结果", List.of("result", "results"));
        return Map.copyOf(values);
    }
}
