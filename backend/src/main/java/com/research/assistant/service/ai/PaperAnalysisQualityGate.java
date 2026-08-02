package com.research.assistant.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 论文精读结构化结果的轻量质量门禁。
 *
 * <p>模型输出不是可信的业务输入。这里集中处理空值、空白字符串、枚举值、评分范围和
 * 列表大小，避免这些规则散落在持久化映射代码中。该组件只做确定性的归一化，不会编造
 * 论文内容；关键字段缺失时由调用方决定是否走 fallback。</p>
 */
public final class PaperAnalysisQualityGate {

    public static final String PROMPT_VERSION = "paper-analysis-v1";
    public static final String FALLBACK_PROMPT_VERSION = "paper-analysis-fallback-v1";
    public static final String REPAIR_PROMPT_VERSION = "paper-analysis-repair-v1";

    private static final int MAX_LIST_ITEMS = 100;
    private static final Set<String> METHOD_TYPES = Set.of(
            "THEORETICAL", "EXPERIMENTAL", "SYSTEM", "SURVEY");
    private static final Set<String> ARTIFACT_TYPES = Set.of(
            "FORMULA", "PSEUDOCODE", "SOURCE_CODE", "DATASET", "METRIC", "OTHER");

    /**
     * 校验并就地归一化 POJO。
     *
     * @param result LangChain4j 反序列化结果
     */
    public QualityReport validateAndRepair(PaperAnalysisResult result) {
        if (result == null) {
            return QualityReport.invalid(List.of("result is null"));
        }

        List<String> issues = new ArrayList<>();
        boolean repaired = false;

        repaired |= trimField(result.getDomain(), result::setDomain);
        repaired |= trimField(result.getCoreContribution(), result::setCoreContribution);
        repaired |= trimField(result.getMethodSummary(), result::setMethodSummary);

        String normalizedMethodType = normalizeEnum(result.getMethodType(), METHOD_TYPES);
        if (!same(result.getMethodType(), normalizedMethodType)) {
            repaired = true;
            issues.add("methodType normalized");
            result.setMethodType(normalizedMethodType);
        }

        repaired |= normalizeStrings(result.getDatasets(), result::setDatasets, "datasets", issues);
        repaired |= normalizeStrings(result.getModels(), result::setModels, "models", issues);
        repaired |= normalizeStrings(result.getKeyFindings(), result::setKeyFindings, "keyFindings", issues);
        repaired |= normalizeStrings(result.getLimitations(), result::setLimitations, "limitations", issues);

        repaired |= normalizeSections(result, issues);
        repaired |= normalizeTables(result, issues);
        repaired |= normalizeFigures(result, issues);
        repaired |= normalizeArtifacts(result, issues);
        repaired |= normalizeExperimentSetup(result, issues);
        repaired |= normalizeBenchmarks(result, issues);

        boolean missingCoreContribution = !hasText(result.getCoreContribution());
        boolean missingMethodSummary = !hasText(result.getMethodSummary());
        boolean missingMethodType = !hasText(result.getMethodType());
        if (missingCoreContribution) {
            issues.add("coreContribution is blank");
        }
        if (missingMethodSummary) {
            issues.add("methodSummary is blank");
        }
        if (missingMethodType) {
            issues.add("methodType is blank or unsupported");
        }

        boolean valid = !missingCoreContribution && !missingMethodSummary && !missingMethodType;
        return new QualityReport(valid, repaired, List.copyOf(issues));
    }

    /**
     * 对旧 fallback JSON 做同样的边界校验。节点会被就地归一化，便于后续字段映射复用。
     */
    public QualityReport validateAndRepairFallback(JsonNode root) {
        if (root == null || !root.isObject()) {
            return QualityReport.invalid(List.of("fallback root is not an object"));
        }

        ObjectNode object = (ObjectNode) root;
        List<String> issues = new ArrayList<>();
        boolean repaired = false;

        String coreContribution = normalizeTextNode(object, "core_contribution");
        String methodSummary = normalizeTextNode(object, "method_summary");
        String methodType = normalizeEnum(normalizeTextNode(object, "method_type"), METHOD_TYPES);
        if (object.hasNonNull("method_type") && !same(object.path("method_type").asText(), methodType)) {
            repaired = true;
            issues.add("method_type normalized");
        }
        if (methodType == null) {
            object.putNull("method_type");
        } else {
            object.put("method_type", methodType);
        }

        for (String field : List.of("sections", "datasets", "models", "key_findings", "limitations",
                "tables_summary", "figures_summary", "reproducible_artifacts", "benchmark_results")) {
            JsonNode value = object.get(field);
            if (value == null || value.isNull()) {
                object.putArray(field);
                repaired = true;
                issues.add(field + " defaulted to empty");
            } else if (!value.isArray()) {
                object.putArray(field);
                repaired = true;
                issues.add(field + " replaced because it was not an array");
            } else if (value.size() > MAX_LIST_ITEMS) {
                ArrayNode array = (ArrayNode) value;
                while (array.size() > MAX_LIST_ITEMS) {
                    array.remove(array.size() - 1);
                }
                repaired = true;
                issues.add(field + " truncated to 100 items");
            }
        }

        JsonNode setup = object.get("experiment_setup");
        if (setup == null || setup.isNull()) {
            object.putObject("experiment_setup");
            repaired = true;
            issues.add("experiment_setup defaulted to empty object");
        } else if (!setup.isObject()) {
            object.putObject("experiment_setup");
            repaired = true;
            issues.add("experiment_setup replaced because it was not an object");
        }

        boolean missingCoreContribution = !hasText(coreContribution);
        boolean missingMethodSummary = !hasText(methodSummary);
        boolean missingMethodType = !hasText(methodType);
        if (missingCoreContribution) issues.add("core_contribution is blank");
        if (missingMethodSummary) issues.add("method_summary is blank");
        if (missingMethodType) issues.add("method_type is blank or unsupported");
        return new QualityReport(
                !missingCoreContribution && !missingMethodSummary && !missingMethodType,
                repaired, List.copyOf(issues));
    }

    private String normalizeTextNode(ObjectNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        object.put(field, value);
        return value;
    }

    private boolean normalizeSections(PaperAnalysisResult result, List<String> issues) {
        List<PaperAnalysisResult.Section> sections = result.getSections();
        if (sections == null) {
            result.setSections(new ArrayList<>());
            issues.add("sections defaulted to empty");
            return true;
        }
        List<PaperAnalysisResult.Section> normalized = new ArrayList<>();
        boolean changed = false;
        for (PaperAnalysisResult.Section section : sections) {
            if (section == null) {
                issues.add("null section removed");
                changed = true;
                continue;
            }
            String heading = trim(section.getHeading());
            String summary = trim(section.getSummary());
            changed |= !same(section.getHeading(), heading) || !same(section.getSummary(), summary);
            section.setHeading(heading);
            section.setSummary(summary);
            if (hasText(section.getHeading()) || hasText(section.getSummary())) {
                normalized.add(section);
            } else {
                issues.add("blank section removed");
                changed = true;
            }
        }
        changed |= normalized.size() != sections.size();
        if (normalized.size() > MAX_LIST_ITEMS) {
            normalized = new ArrayList<>(normalized.subList(0, MAX_LIST_ITEMS));
            issues.add("sections truncated to 100 items");
            changed = true;
        }
        result.setSections(normalized);
        return changed;
    }

    private boolean normalizeTables(PaperAnalysisResult result, List<String> issues) {
        List<PaperAnalysisResult.TableSummary> tables = result.getTablesSummary();
        if (tables == null) {
            result.setTablesSummary(new ArrayList<>());
            issues.add("tablesSummary defaulted to empty");
            return true;
        }
        List<PaperAnalysisResult.TableSummary> normalized = new ArrayList<>();
        boolean changed = false;
        for (PaperAnalysisResult.TableSummary table : tables) {
            if (table == null) {
                issues.add("null table removed");
                changed = true;
                continue;
            }
            String caption = trim(table.getCaption());
            String contentHint = trim(table.getContentHint());
            changed |= !same(table.getCaption(), caption) || !same(table.getContentHint(), contentHint);
            table.setCaption(caption);
            table.setContentHint(contentHint);
            if (hasText(table.getCaption()) || hasText(table.getContentHint())) {
                normalized.add(table);
            } else {
                issues.add("blank table removed");
                changed = true;
            }
        }
        changed |= normalized.size() != tables.size();
        if (normalized.size() > MAX_LIST_ITEMS) {
            normalized = new ArrayList<>(normalized.subList(0, MAX_LIST_ITEMS));
            issues.add("tablesSummary truncated to 100 items");
            changed = true;
        }
        result.setTablesSummary(normalized);
        return changed;
    }

    private boolean normalizeFigures(PaperAnalysisResult result, List<String> issues) {
        List<PaperAnalysisResult.FigureSummary> figures = result.getFiguresSummary();
        if (figures == null) {
            result.setFiguresSummary(new ArrayList<>());
            issues.add("figuresSummary defaulted to empty");
            return true;
        }
        List<PaperAnalysisResult.FigureSummary> normalized = new ArrayList<>();
        boolean changed = false;
        for (PaperAnalysisResult.FigureSummary figure : figures) {
            if (figure == null) {
                issues.add("null figure removed");
                changed = true;
                continue;
            }
            String caption = trim(figure.getCaption());
            changed |= !same(figure.getCaption(), caption);
            figure.setCaption(caption);
            if (hasText(figure.getCaption())) {
                normalized.add(figure);
            } else {
                issues.add("blank figure removed");
                changed = true;
            }
        }
        changed |= normalized.size() != figures.size();
        if (normalized.size() > MAX_LIST_ITEMS) {
            normalized = new ArrayList<>(normalized.subList(0, MAX_LIST_ITEMS));
            issues.add("figuresSummary truncated to 100 items");
            changed = true;
        }
        result.setFiguresSummary(normalized);
        return changed;
    }

    private boolean normalizeArtifacts(PaperAnalysisResult result, List<String> issues) {
        List<PaperAnalysisResult.ReproducibleArtifact> artifacts = result.getReproducibleArtifacts();
        if (artifacts == null) {
            result.setReproducibleArtifacts(new ArrayList<>());
            issues.add("reproducibleArtifacts defaulted to empty");
            return true;
        }
        List<PaperAnalysisResult.ReproducibleArtifact> normalized = new ArrayList<>();
        boolean changed = false;
        for (PaperAnalysisResult.ReproducibleArtifact artifact : artifacts) {
            if (artifact == null) {
                issues.add("null artifact removed");
                changed = true;
                continue;
            }
            String type = normalizeEnum(artifact.getType(), ARTIFACT_TYPES);
            if (hasText(artifact.getType()) && type == null) {
                type = "OTHER";
            }
            if (!same(artifact.getType(), type)) {
                artifact.setType(type);
                issues.add("artifact type normalized");
                changed = true;
            }
            String title = trim(artifact.getTitle());
            String content = trim(artifact.getContent());
            String location = trim(artifact.getLocation());
            changed |= !same(artifact.getTitle(), title)
                    || !same(artifact.getContent(), content)
                    || !same(artifact.getLocation(), location);
            artifact.setTitle(title);
            artifact.setContent(content);
            artifact.setLocation(location);
            if (hasText(artifact.getTitle()) || hasText(artifact.getContent())) {
                normalized.add(artifact);
            } else {
                issues.add("blank artifact removed");
                changed = true;
            }
        }
        if (normalized.size() > MAX_LIST_ITEMS) {
            normalized = new ArrayList<>(normalized.subList(0, MAX_LIST_ITEMS));
            issues.add("reproducibleArtifacts truncated to 100 items");
            changed = true;
        }
        result.setReproducibleArtifacts(normalized);
        return changed || normalized.size() != artifacts.size();
    }

    private boolean normalizeExperimentSetup(PaperAnalysisResult result, List<String> issues) {
        PaperAnalysisResult.ExperimentSetup setup = result.getExperimentSetup();
        if (setup == null) {
            return false;
        }
        boolean changed = trimField(setup.getTaskDefinition(), setup::setTaskDefinition);
        changed |= trimField(setup.getImplementationDetails(), setup::setImplementationDetails);
        changed |= normalizeStrings(setup.getDatasets(), setup::setDatasets, "experimentSetup.datasets", issues);
        changed |= normalizeStrings(setup.getBaselines(), setup::setBaselines, "experimentSetup.baselines", issues);
        changed |= normalizeStrings(setup.getMetrics(), setup::setMetrics, "experimentSetup.metrics", issues);
        return changed;
    }

    private boolean normalizeBenchmarks(PaperAnalysisResult result, List<String> issues) {
        List<PaperAnalysisResult.BenchmarkResult> benchmarks = result.getBenchmarkResults();
        if (benchmarks == null) {
            result.setBenchmarkResults(new ArrayList<>());
            issues.add("benchmarkResults defaulted to empty");
            return true;
        }
        List<PaperAnalysisResult.BenchmarkResult> normalized = new ArrayList<>();
        boolean changed = false;
        for (PaperAnalysisResult.BenchmarkResult benchmark : benchmarks) {
            if (benchmark == null) {
                issues.add("null benchmark removed");
                changed = true;
                continue;
            }
            changed |= trimField(benchmark.getMetric(), benchmark::setMetric);
            changed |= trimField(benchmark.getValue(), benchmark::setValue);
            changed |= trimField(benchmark.getBaselineValue(), benchmark::setBaselineValue);
            changed |= trimField(benchmark.getDataset(), benchmark::setDataset);
            changed |= trimField(benchmark.getSource(), benchmark::setSource);
            changed |= trimField(benchmark.getNote(), benchmark::setNote);
            if (hasText(benchmark.getMetric()) || hasText(benchmark.getValue())) {
                normalized.add(benchmark);
            } else {
                issues.add("blank benchmark removed");
                changed = true;
            }
        }
        if (normalized.size() > MAX_LIST_ITEMS) {
            normalized = new ArrayList<>(normalized.subList(0, MAX_LIST_ITEMS));
            issues.add("benchmarkResults truncated to 100 items");
            changed = true;
        }
        result.setBenchmarkResults(normalized);
        return changed || normalized.size() != benchmarks.size();
    }

    private boolean normalizeStrings(List<String> values, Consumer<List<String>> setter,
                                     String field, List<String> issues) {
        if (values == null) {
            setter.accept(new ArrayList<>());
            issues.add(field + " defaulted to empty");
            return true;
        }
        List<String> normalized = new ArrayList<>();
        boolean changed = false;
        for (String value : values) {
            String trimmed = trim(value);
            if (hasText(trimmed)) {
                normalized.add(trimmed);
                changed |= !same(value, trimmed);
            } else {
                changed = true;
            }
        }
        if (normalized.size() > MAX_LIST_ITEMS) {
            normalized = new ArrayList<>(normalized.subList(0, MAX_LIST_ITEMS));
            issues.add(field + " truncated to 100 items");
            changed = true;
        }
        setter.accept(normalized);
        return changed || normalized.size() != values.size();
    }

    private boolean trimField(String value, Consumer<String> setter) {
        String trimmed = trim(value);
        if (!same(value, trimmed)) {
            setter.accept(trimmed);
            return true;
        }
        return false;
    }

    private String normalizeEnum(String value, Set<String> allowed) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : null;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    /** 质量门禁结果，便于日志和后续持久化扩展。 */
    public record QualityReport(boolean valid, boolean repaired, List<String> issues) {
        public QualityReport {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public static QualityReport invalid(List<String> issues) {
            return new QualityReport(false, false, issues);
        }
    }
}
