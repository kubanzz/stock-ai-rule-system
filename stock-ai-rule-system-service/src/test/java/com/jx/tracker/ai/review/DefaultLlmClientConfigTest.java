package com.jx.tracker.ai.review;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultLlmClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DefaultLlmClientConfig.class);

    @Test
    void fallsBackToMockWhenLlmConnectionIsNotConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LlmClient.class);
            assertThat(context.getBean(LlmClient.class)).isInstanceOf(MockLlmClient.class);
        });
    }

    @Test
    void fallsBackToMockWhenApiKeyIsMissing() {
        contextRunner
                .withPropertyValues(
                        "ai.llm.base-url=http://llm.example.test",
                        "ai.llm.model=review-model"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmClient.class);
                    assertThat(context.getBean(LlmClient.class)).isInstanceOf(MockLlmClient.class);
                });
    }

    @Test
    void fallsBackToMockWhenBaseUrlIsMissing() {
        contextRunner
                .withPropertyValues(
                        "ai.llm.api-key=test-api-key",
                        "ai.llm.model=review-model"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmClient.class);
                    assertThat(context.getBean(LlmClient.class)).isInstanceOf(MockLlmClient.class);
                });
    }

    @Test
    void createsHttpLlmClientWhenBaseUrlAndApiKeyAreConfigured() {
        contextRunner
                .withPropertyValues(
                        "ai.llm.base-url=http://llm.example.test",
                        "ai.llm.api-key=test-api-key",
                        "ai.llm.model=review-model"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmClient.class);
                    assertThat(context.getBean(LlmClient.class)).isInstanceOf(HttpLlmClient.class);
                });
    }
}
