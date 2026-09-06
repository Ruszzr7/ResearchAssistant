package com.research.assistant.service.agent.skill;

import com.research.assistant.service.agent.core.AgentToolDefinition;
import com.research.assistant.service.agent.core.AgentToolExecution;
import com.research.assistant.service.agent.core.PaperOverviewToolRegistry;
import com.research.assistant.service.agent.source.PaperSourceCatalog;
import org.springframework.stereotype.Service;

import java.util.List;

/** Model-facing adapter for the paper-profile Skill. */
@Service
public class PaperProfileSkillTool {

    public static final String SKILL_NAME = "paper-profile";

    private final PaperOverviewToolRegistry overviewTool;

    public PaperProfileSkillTool(PaperOverviewToolRegistry overviewTool) {
        this.overviewTool = overviewTool;
    }

    public List<AgentToolDefinition> definitions() {
        return List.of(overviewTool.definition());
    }

    public boolean supports(String toolName) {
        return PaperOverviewToolRegistry.TOOL_NAME.equals(toolName);
    }

    public AgentToolExecution execute(long paperId, PaperSourceCatalog catalog) {
        return overviewTool.execute(paperId, catalog);
    }
}
