package com.jx.tracker.ai.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.config.properties.AiLlmProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(AiLlmProperties.class)
public class DefaultLlmClientConfig {

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient defaultLlmClient(AiLlmProperties properties, ObjectProvider<ObjectMapper> objectMapperProvider) {
        if (properties.isHttpEnabled()) {
            ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
            return new HttpLlmClient(properties, objectMapper, HttpClient.newHttpClient());
        }
        return new MockLlmClient();
    }
}
