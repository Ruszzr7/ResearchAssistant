package com.research.assistant.service.memory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class PaperMemoryRuntimeConfigurationTest {

    @Test
    void applicationDefaultsKeepTheAdaptiveChunkerOnTheLargeChunkPath() throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .forEach(sources::addLast);
        PropertySourcesPropertyResolver properties = new PropertySourcesPropertyResolver(sources);

        assertThat(properties.getProperty(
                "app.paper-memory.chunk-target-chars", Integer.class)).isEqualTo(24_000);
        assertThat(properties.getProperty(
                "app.paper-memory.chunk-max-chars", Integer.class)).isEqualTo(36_000);
        assertThat(properties.getProperty(
                "app.paper-memory.single-pass-max-chars", Integer.class)).isEqualTo(80_000);
    }
}
