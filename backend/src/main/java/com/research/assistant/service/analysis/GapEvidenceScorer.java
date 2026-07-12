package com.research.assistant.service.analysis;

import java.time.Year;
import java.util.List;
import java.util.Map;

/**
 * Gap 验证证据评分器 —— 按发表时间加权判断证据强度。
 */
public class GapEvidenceScorer {

    private static final int RECENT_YEARS = 3;
    private static final int MODERATE_YEARS = 6;

    /**
     * 根据年份计算权重：越近权重越高。
     *
     * @param yearString 年份字符串（可能为空或非法）
     * @return 权重：近 3 年 1.0，3–6 年 0.6，更早 0.3，无法解析 0.5
     */
    public static double weightByYear(String yearString) {
        int year = parseYear(yearString);
        if (year <= 0) {
            return 0.5;
        }
        int current = Year.now().getValue();
        int diff = current - year;
        if (diff <= RECENT_YEARS) {
            return 1.0;
        }
        if (diff <= MODERATE_YEARS) {
            return 0.6;
        }
        return 0.3;
    }

    /**
     * 对证据列表按年份加权求和。
     */
    public static double score(List<Map<String, Object>> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (Map<String, Object> e : evidence) {
            String verificationStatus = String.valueOf(e.getOrDefault("verificationStatus", "VERIFIED"));
            if (!"VERIFIED".equalsIgnoreCase(verificationStatus)) {
                continue;
            }
            String year = String.valueOf(e.getOrDefault("year", ""));
            total += weightByYear(year);
        }
        return total;
    }

    /**
     * 根据加权总分映射到 red/yellow/green。
     */
    public static String determineLevel(double weightedScore) {
        if (weightedScore >= 1.5) {
            return "green";
        }
        if (weightedScore >= 0.5) {
            return "yellow";
        }
        return "red";
    }

    private static int parseYear(String yearString) {
        if (yearString == null || yearString.isBlank()) {
            return 0;
        }
        try {
            String digits = yearString.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) {
                return 0;
            }
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
