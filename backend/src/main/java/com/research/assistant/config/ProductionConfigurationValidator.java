package com.research.assistant.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Fail-fast validation for the small set of deployment settings that cannot be
 * safely inferred at runtime. Local development and the isolated test profile
 * intentionally keep their existing compatibility behavior.
 */
@Component
@Profile("prod")
public class ProductionConfigurationValidator implements InitializingBean {

    private final String masterKey;
    private final boolean failClosed;
    private final String datasourceUrl;
    private final String pdfDirectory;
    private final String allowedOrigins;

    public ProductionConfigurationValidator(
            @Value("${app.encryption.master-key:}") String masterKey,
            @Value("${app.encryption.fail-closed:true}") boolean failClosed,
            @Value("${spring.datasource.url:}") String datasourceUrl,
            @Value("${app.storage.pdf-dir:}") String pdfDirectory,
            @Value("${app.http.cors.allowed-origins:}") String allowedOrigins) {
        this.masterKey = masterKey;
        this.failClosed = failClosed;
        this.datasourceUrl = datasourceUrl;
        this.pdfDirectory = pdfDirectory;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void afterPropertiesSet() {
        if (masterKey == null || masterKey.isBlank() || masterKey.length() < 16) {
            throw new IllegalStateException("生产环境必须配置长度至少为 16 的 RA_MASTER_KEY");
        }
        if (!failClosed) {
            throw new IllegalStateException("生产环境必须启用 app.encryption.fail-closed");
        }
        if (datasourceUrl == null || !datasourceUrl.startsWith("jdbc:mysql:")) {
            throw new IllegalStateException("生产环境必须使用 MySQL 数据源");
        }
        if (pdfDirectory == null || pdfDirectory.isBlank()) {
            throw new IllegalStateException("生产环境必须配置 APP_STORAGE_PDF_DIR");
        }
        try {
            Path.of(pdfDirectory);
        } catch (InvalidPathException e) {
            throw new IllegalStateException("APP_STORAGE_PDF_DIR 不是有效路径", e);
        }
        if (allowedOrigins == null || allowedOrigins.isBlank()
                || allowedOrigins.contains("*")) {
            throw new IllegalStateException("生产环境必须配置明确的 RA_CORS_ALLOWED_ORIGINS");
        }
    }
}
