package com.research.assistant.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Structural guard for the 30-paper, 300-case Skill routing dataset. */
class AgentSkillEvalDatasetTest {

    private static final Set<Integer> EXPECTED_PAPER_IDS = Set.of(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32);
    private static final Set<String> SKILLS = Set.of(
            "paper-profile", "paper-evidence", "paper-action");

    @Test
    void containsTenCasesForEachOfTheThirtyImportedPapers() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<Integer, Integer> casesByPaper = new HashMap<>();
        Map<String, Integer> positivesBySkill = new HashMap<>();
        Set<String> caseIds = new HashSet<>();
        int total = 0;

        try (InputStream input = getClass().getResourceAsStream("/eval/agent-skill-300.jsonl")) {
            assertThat(input).as("Skill evaluation dataset must be packaged").isNotNull();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode item = mapper.readTree(line);
                    total++;
                    String caseId = item.path("caseId").asText();
                    assertThat(caseIds.add(caseId)).as("duplicate case id").isTrue();
                    int paperId = item.path("paperId").asInt(-1);
                    assertThat(EXPECTED_PAPER_IDS).contains(paperId);
                    assertThat(item.path("paperTitle").asText()).isNotBlank();
                    assertThat(item.path("topicTitle").asText()).isNotBlank();
                    assertThat(item.path("question").asText()).isNotBlank();
                    int caseNumber = item.path("caseNumber").asInt(-1);
                    assertThat(caseNumber).isBetween(1, 10);
                    casesByPaper.merge(paperId, 1, Integer::sum);

                    assertThat(item.path("requiredSkills").isArray()).isTrue();
                    assertThat(item.path("allowedSkills").isArray()).isTrue();
                    assertThat(item.path("goldSkills")).isEqualTo(item.path("requiredSkills"));
                    Set<String> allowed = new HashSet<>();
                    item.path("allowedSkills").forEach(skill -> {
                        assertThat(SKILLS).contains(skill.asText());
                        allowed.add(skill.asText());
                    });
                    for (JsonNode skill : item.path("requiredSkills")) {
                        assertThat(SKILLS).contains(skill.asText());
                        assertThat(allowed).contains(skill.asText());
                        positivesBySkill.merge(skill.asText(), 1, Integer::sum);
                    }
                    assertThat(item.path("rationale").fieldNames()).toIterable()
                            .containsExactlyInAnyOrderElementsOf(SKILLS);
                    assertThat(item.path("context").has("profileAvailable")).isTrue();
                    assertThat(item.path("context").has("trustedSourceObjectIds")).isTrue();
                    assertRoutingContract(item, caseNumber);
                }
            }
        }

        assertThat(total).isEqualTo(300);
        assertThat(casesByPaper.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_PAPER_IDS);
        assertThat(casesByPaper.values()).allMatch(count -> count == 10);
        assertThat(positivesBySkill).containsEntry("paper-profile", 40)
                .containsEntry("paper-evidence", 220)
                .containsEntry("paper-action", 80);
    }

    private static void assertRoutingContract(JsonNode item, int caseNumber) {
        Set<String> gold = new HashSet<>();
        item.path("requiredSkills").forEach(skill -> gold.add(skill.asText()));
        Set<String> allowed = new HashSet<>();
        item.path("allowedSkills").forEach(skill -> allowed.add(skill.asText()));
        if (caseNumber == 1) {
            assertThat(gold).containsExactlyInAnyOrder("paper-profile", "paper-evidence");
        } else if (caseNumber >= 2 && caseNumber <= 6) {
            assertThat(gold).containsExactly("paper-evidence");
            if (caseNumber == 2 || caseNumber == 4) {
                assertThat(allowed).containsExactlyInAnyOrder("paper-evidence", "paper-profile");
            } else {
                assertThat(allowed).containsExactly("paper-evidence");
            }
        } else if (caseNumber == 7) {
            assertThat(gold).isEmpty();
            assertThat(item.path("caseType").asText()).isEqualTo("rewrite");
            assertThat(item.path("question").asText()).contains("不要求核对论文");
            assertThat(item.at("/context/profileAlreadyLoaded").asBoolean()).isFalse();
            assertThat(item.at("/context/priorEvidenceRead").asBoolean()).isFalse();
        } else if (caseNumber == 8) {
            assertThat(gold).containsExactly("paper-action");
            assertThat(item.path("executionMode").asText()).isEqualTo("browser-selection");
            assertThat(item.at("/context/requiresLiveSelection").asBoolean()).isTrue();
            assertThat(item.path("question").asText()).isEqualTo("请把当前选区高亮为黄色。");
        } else if (caseNumber == 9) {
            assertThat(gold).containsExactlyInAnyOrder("paper-evidence", "paper-action");
        }

        if (gold.contains("paper-profile")) {
            assertThat(item.path("question").asText()).containsAnyOf("全文", "核心结论");
        }
        if (!gold.contains("paper-action")) {
            assertThat(item.path("question").asText()).doesNotContain("跳转并高亮");
        }
    }
}
