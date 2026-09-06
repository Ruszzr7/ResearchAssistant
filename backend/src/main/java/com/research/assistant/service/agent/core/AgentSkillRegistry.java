package com.research.assistant.service.agent.core;

import com.research.assistant.service.agent.skill.PaperActionSkillTool;
import com.research.assistant.service.agent.skill.PaperEvidenceSkillTool;
import com.research.assistant.service.agent.skill.PaperProfileSkillTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import dev.langchain4j.skills.FileSystemSkill;
import dev.langchain4j.skills.FileSystemSkillLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers standard on-disk Agent Skills and binds their existing application
 * tools. It does not classify user intent, load a Skill on the model's behalf,
 * or prescribe a call sequence.
 */
@Service
public class AgentSkillRegistry {

    private static final List<String> PAPER_SKILL_NAMES = List.of(
            "paper-profile", "paper-evidence", "paper-action");
    private final PaperEvidenceSkillTool evidenceTool;
    private final PaperProfileSkillTool profileTool;
    private final PaperActionSkillTool actionTool;
    private final Path skillsRoot;
    private volatile Map<String, FileSystemSkill> discovered;

    public AgentSkillRegistry(PaperReadToolRegistry paperReadToolRegistry) {
        this(new PaperEvidenceSkillTool(paperReadToolRegistry), null,
                new PaperActionSkillTool(null), Path.of("../skills"));
    }

    public AgentSkillRegistry(PaperReadToolRegistry paperReadToolRegistry,
                              PaperOverviewToolRegistry paperOverviewToolRegistry) {
        this(new PaperEvidenceSkillTool(paperReadToolRegistry),
                paperOverviewToolRegistry == null ? null : new PaperProfileSkillTool(paperOverviewToolRegistry),
                new PaperActionSkillTool(null), Path.of("../skills"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentSkillRegistry(PaperEvidenceSkillTool evidenceTool,
                              PaperProfileSkillTool profileTool,
                              PaperActionSkillTool actionTool,
                              @Value("${app.agent.skills-dir:../skills}") String skillsDirectory) {
        this(evidenceTool, profileTool, actionTool, Path.of(skillsDirectory));
    }

    AgentSkillRegistry(PaperEvidenceSkillTool evidenceTool,
                       PaperProfileSkillTool profileTool,
                       PaperActionSkillTool actionTool,
                       Path skillsRoot) {
        this.evidenceTool = evidenceTool;
        this.profileTool = profileTool;
        this.actionTool = actionTool;
        Path root = skillsRoot == null ? Path.of("../skills") : skillsRoot;
        if (!root.isAbsolute()) root = Path.of(System.getProperty("user.dir")).resolve(root);
        this.skillsRoot = root.toAbsolutePath().normalize();
    }

    /**
     * Returns the standard paper Skill bindings. Skill metadata and instructions
     * are available even before a paper is open; the Agent still decides whether
     * to activate a Skill, while the host tool reports an unavailable paper when
     * there is no current source to operate on.
     */
    public List<AgentSkillBinding> bindings(AgentContextSnapshot context, String userMessage) {
        Map<String, FileSystemSkill> skills = discoveredSkills();
        List<AgentSkillBinding> result = new ArrayList<>();
        if (profileTool != null) {
            result.add(new AgentSkillBinding(skills.get("paper-profile"), profileTool.definitions()));
        }
        result.add(new AgentSkillBinding(skills.get("paper-evidence"), evidenceTool.definitions()));
        result.add(new AgentSkillBinding(skills.get("paper-action"), actionTool.definitions()));
        return List.copyOf(result);
    }

    private Map<String, FileSystemSkill> discoveredSkills() {
        Map<String, FileSystemSkill> cached = discovered;
        if (cached != null) return cached;
        synchronized (this) {
            if (discovered != null) return discovered;
            if (!Files.isDirectory(skillsRoot)) {
                throw new IllegalStateException("Agent Skill directory is unavailable: " + skillsRoot);
            }
            Map<String, FileSystemSkill> values = new LinkedHashMap<>();
            for (String name : PAPER_SKILL_NAMES) {
                Path directory = skillsRoot.resolve(name).normalize();
                if (!directory.startsWith(skillsRoot) || !Files.isDirectory(directory)
                        || !Files.isRegularFile(directory.resolve("SKILL.md"))) {
                    throw new IllegalStateException("Agent Skill is missing: " + name);
                }
                FileSystemSkill skill = FileSystemSkillLoader.loadSkill(directory);
                if (!name.equals(skill.name())) {
                    throw new IllegalStateException("Agent Skill name does not match its directory: " + name);
                }
                values.put(name, skill);
            }
            discovered = Map.copyOf(values);
            return discovered;
        }
    }
}
