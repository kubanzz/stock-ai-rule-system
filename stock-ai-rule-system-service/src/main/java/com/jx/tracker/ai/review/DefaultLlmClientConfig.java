package com.jx.tracker.ai.review;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DefaultLlmClientConfig {

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient defaultLlmClient() {
        return new MockLlmClient();
    }
}
