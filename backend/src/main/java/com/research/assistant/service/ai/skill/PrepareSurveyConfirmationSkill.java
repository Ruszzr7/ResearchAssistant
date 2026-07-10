package com.research.assistant.service.ai.skill;

import com.research.assistant.mapper.FolderMapper;
import com.research.assistant.service.ai.skill.io.PrepareSurveyConfirmationInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调研确认准备 Skill —— 把候选列表格式化为前端确认所需的结构。
 */
@Component
public class PrepareSurveyConfirmationSkill implements Skill<PrepareSurveyConfirmationInput, Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(PrepareSurveyConfirmationSkill.class);

    private final FolderMapper folderMapper;

    public PrepareSurveyConfirmationSkill(FolderMapper folderMapper) {
        this.folderMapper = folderMapper;
    }

    @Override
    public String name() {
        return Skills.PREPARE_SURVEY_CONFIRMATION;
    }

    @Override
    public String description() {
        return "将检索候选列表格式化为用户确认结构。输入：{candidates: List}；输出：{candidates, defaultFolderId}。";
    }

    @Override
    public Class<PrepareSurveyConfirmationInput> inputType() {
        return PrepareSurveyConfirmationInput.class;
    }

    @Override
    public Map<String, Object> execute(SkillContext ctx, PrepareSurveyConfirmationInput input) {
        ctx.stage("正在准备候选论文确认列表…");
        List<Map<String, Object>> candidates = input.candidates();
        Long defaultFolderId = folderMapper.selectList(null).stream()
                .findFirst()
                .map(f -> f.getId())
                .orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidates", candidates);
        result.put("defaultFolderId", defaultFolderId);
        return result;
    }
}
