package com.research.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 分批清理过期质量事件，避免观测表无限增长。 */
@Component
public class AiQualityEventRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AiQualityEventRetentionJob.class);

    private final AiQualityEventService service;
    private final int retentionDays;

    public AiQualityEventRetentionJob(AiQualityEventService service,
                                      @Value("${app.ai-quality.retention-days:180}") int retentionDays) {
        this.service = service;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(cron = "${app.ai-quality.retention-cron:0 30 3 * * *}")
    public void purgeExpiredEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted;
        int total = 0;
        do {
            deleted = service.deleteBatchBefore(cutoff);
            total += deleted;
        } while (deleted > 0);
        if (total > 0) {
            log.info("Purged {} AI quality events older than {} days", total, retentionDays);
        }
    }
}
