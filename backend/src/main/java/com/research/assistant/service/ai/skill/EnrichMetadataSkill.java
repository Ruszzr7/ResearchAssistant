package com.research.assistant.service.ai.skill;

import com.research.assistant.dto.EnrichmentResult;
import com.research.assistant.service.metadata.MetadataEnrichmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 元数据补全 Skill —— 为已入库且上传了 PDF 的论文识别 DOI / arXiv ID 并补全缺失元数据。
 */
@Component
public class EnrichMetadataSkill implements Skill<Long, EnrichmentResult> {

    private static final Logger log = LoggerFactory.getLogger(EnrichMetadataSkill.class);

    private final MetadataEnrichmentService metadataEnrichmentService;

    public EnrichMetadataSkill(MetadataEnrichmentService metadataEnrichmentService) {
        this.metadataEnrichmentService = metadataEnrichmentService;
    }

    @Override
    public String name() {
        return Skills.ENRICH_METADATA;
    }

    @Override
    public String description() {
        return "为已入库论文补全元数据。输入：{\"paperId\": Long}；输出：EnrichmentResult。";
    }

    @Override
    public Class<Long> inputType() {
        return Long.class;
    }

    @Override
    public EnrichmentResult execute(SkillContext ctx, Long paperId) {
        ctx.stage("正在识别 DOI / arXiv ID 并补全元数据…");
        try {
            EnrichmentResult result = metadataEnrichmentService.enrichFromPaper(paperId);
            if (result.isFound()) {
                ctx.stage("元数据补全完成");
            } else {
                ctx.stage("未识别到 DOI/arXiv ID，跳过元数据补全");
            }
            return result;
        } catch (Exception e) {
            log.warn("元数据补全失败 paperId={}: {}", paperId, e.getMessage());
            ctx.stage("元数据补全失败，继续后续步骤");
            EnrichmentResult fallback = new EnrichmentResult();
            fallback.setFound(false);
            fallback.setMessage(e.getMessage());
            return fallback;
        }
    }
}
