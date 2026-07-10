package com.research.assistant.config;

import com.research.assistant.service.Encryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 加密配置 —— 从环境变量或配置文件装配主密钥。
 */
@Configuration
public class EncryptionConfig {

    @Bean
    public Encryptor encryptor(Environment environment,
                               @Value("${app.encryption.master-key:}") String configMasterKey) {
        String envMasterKey = environment.getProperty("RA_MASTER_KEY");
        String masterKey = (envMasterKey != null && !envMasterKey.isBlank()) ? envMasterKey : configMasterKey;
        return new Encryptor(masterKey);
    }
}
