package com.jx.tracker.risk.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.runtime.RiskBackfillService;
import com.jx.tracker.risk.runtime.RiskWarningConfiguration;
import com.jx.tracker.risk.workflow.RiskWorkflowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RiskBackfillCommandConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RiskWarningConfiguration.class, Dependencies.class);

    @Test
    void commandRunnerIsAbsentByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RiskBackfillCommandProperties.class);
            assertThat(context.getBean(RiskBackfillCommandProperties.class).isEnabled()).isFalse();
            assertThat(context).doesNotHaveBean(RiskBackfillCommandRunner.class);
        });
    }

    @Test
    void commandSwitchCreatesANonAutomaticRunnerEvenWhenRiskRuntimeIsDisabled() {
        contextRunner.withPropertyValues(
                "stock-ai-rule.risk-warning.backfill-command.enabled=true",
                "stock-ai-rule.risk-warning.backfill-command.mode=sample",
                "stock-ai-rule.risk-warning.backfill-command.end-date=2026-07-10",
                "stock-ai-rule.risk-warning.backfill-command.confirmation=BACKFILL_5Y"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RiskBackfillCommandRunner.class);
            Object runner = context.getBean(RiskBackfillCommandRunner.class);
            assertThat(runner).isNotInstanceOf(ApplicationRunner.class);
            assertThat(runner).isNotInstanceOf(CommandLineRunner.class);
            assertThat(context).doesNotHaveBean(RiskBackfillService.class);
        });
    }

    @Test
    void allExplicitSwitchesWireTheCommandToTheRealBackfillService() {
        contextRunner.withPropertyValues(
                "stock-ai-rule.risk-warning.enabled=true",
                "stock-ai-rule.risk-warning.backfill-enabled=true",
                "stock-ai-rule.risk-warning.model-version=risk-v1",
                "stock-ai-rule.risk-warning.after-close-cutoff=20:00",
                "stock-ai-rule.risk-warning.ak-tools-base-url=http://127.0.0.1:8090",
                "stock-ai-rule.risk-warning.backfill-command.enabled=true",
                "stock-ai-rule.risk-warning.backfill-command.mode=staged",
                "stock-ai-rule.risk-warning.backfill-command.end-date=2026-07-10",
                "stock-ai-rule.risk-warning.backfill-command.confirmation=BACKFILL_5Y"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RiskBackfillService.class);
            assertThat(context).hasSingleBean(RiskBackfillCommandRunner.class);
            assertThat(context).hasSingleBean(RiskBackfillPreflightService.class);
            assertThat(context).hasSingleBean(RiskBackfillReadinessRepository.class);
            assertThat(context).hasSingleBean(RiskBackfillReportStore.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        JdbcTemplate jdbcTemplate() {
            return mock(JdbcTemplate.class);
        }

        @Bean
        RiskWorkflowRepository riskWorkflowRepository() {
            return mock(RiskWorkflowRepository.class);
        }
    }
}
