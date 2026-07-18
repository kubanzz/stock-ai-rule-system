package com.jx.tracker.risk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.data.flow.AkToolsFlowEventSourceClient;
import com.jx.tracker.risk.data.flow.FlowEventRiskDataProvider;
import com.jx.tracker.risk.data.market.AkToolsMarketRiskSourceClient;
import com.jx.tracker.risk.data.market.MarketRiskDataProvider;
import com.jx.tracker.risk.engine.RiskNormalizer;
import com.jx.tracker.risk.engine.RiskScoringEngine;
import com.jx.tracker.risk.gate.ShadowRiskGate;
import com.jx.tracker.risk.provider.RiskDataProvider;
import com.jx.tracker.risk.workflow.DefaultRiskSnapshotEvaluator;
import com.jx.tracker.risk.workflow.PercentileRiskEvidenceAssembler;
import com.jx.tracker.risk.workflow.RiskAfterCloseWorkflow;
import com.jx.tracker.risk.workflow.RiskWarningWorkflow;
import com.jx.tracker.risk.workflow.RiskWorkflowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RiskWarningConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RiskWarningConfiguration.class, Dependencies.class);

    @Test
    void riskRuntimeIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RiskWarningProperties.class);
            assertThat(context.getBean(RiskWarningProperties.class)).satisfies(properties -> {
                assertThat(properties.isEnabled()).isFalse();
                assertThat(properties.isBackfillEnabled()).isFalse();
                assertThat(properties.getCollectionChunkSize()).isEqualTo(200);
            });
            assertThat(context).doesNotHaveBean(Clock.class);
            assertThat(context).doesNotHaveBean(RiskDataProvider.class);
            assertThat(context).doesNotHaveBean(RiskWarningWorkflow.class);
            assertThat(context).doesNotHaveBean(RiskAfterCloseWorkflow.class);
            assertThat(context).doesNotHaveBean(RiskBackfillService.class);
        });
    }

    @Test
    void enabledRuntimeRegistersTheCompleteShadowOnlyBeanGraph() {
        enabledRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Clock.class);
            assertThat(context).hasSingleBean(AkToolsMarketRiskSourceClient.class);
            assertThat(context).hasSingleBean(AkToolsFlowEventSourceClient.class);
            assertThat(context).hasSingleBean(MarketRiskDataProvider.class);
            assertThat(context).hasSingleBean(FlowEventRiskDataProvider.class);
            assertThat(context.getBeansOfType(RiskDataProvider.class)).hasSize(2);
            assertThat(context).hasSingleBean(RiskNormalizer.class);
            assertThat(context).hasSingleBean(PercentileRiskEvidenceAssembler.class);
            assertThat(context).hasSingleBean(RiskScoringEngine.class);
            assertThat(context).hasSingleBean(DefaultRiskSnapshotEvaluator.class);
            assertThat(context).hasSingleBean(ShadowRiskGate.class);
            assertThat(context).hasSingleBean(RiskWarningWorkflow.class);
            assertThat(context).hasSingleBean(JdbcRiskUniverseReader.class);
            assertThat(context).hasSingleBean(JdbcRiskSignalCandidateReader.class);
            assertThat(context).hasSingleBean(RiskWorkflowPlanner.class);
            assertThat(context).hasSingleBean(DefaultRiskAfterCloseWorkflow.class);
            assertThat(context).hasSingleBean(RiskAfterCloseWorkflow.class);
            assertThat(context).hasSingleBean(RiskBackfillService.class);
        });
    }

    @Test
    void enabledRuntimeWiresTheOptionalDerivedGatewayIntoBothSourceClients() {
        enabledRunner().withPropertyValues(
                "stock-ai-rule.risk-warning.derived-gateway-base-url=http://127.0.0.1:18090"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(
                    context.getBean(AkToolsMarketRiskSourceClient.class), "derivedTransport"))
                    .isNotNull();
            assertThat(ReflectionTestUtils.getField(
                    context.getBean(AkToolsFlowEventSourceClient.class), "derivedRestClient"))
                    .isNotNull();
        });
    }

    @Test
    void enabledRuntimeFailsFastWhenRequiredSettingsAreMissing() {
        contextRunner.withPropertyValues(
                "stock-ai-rule.risk-warning.enabled=true",
                "stock-ai-rule.risk-warning.after-close-cutoff=19:00",
                "stock-ai-rule.risk-warning.ak-tools-base-url=http://127.0.0.1:8090"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "risk warning modelVersion must be configured when enabled");
        });

        contextRunner.withPropertyValues(
                "stock-ai-rule.risk-warning.enabled=true",
                "stock-ai-rule.risk-warning.model-version=risk-runtime-v1",
                "stock-ai-rule.risk-warning.after-close-cutoff=19:00",
                "stock-ai-rule.risk-warning.ak-tools-base-url=http://127.0.0.1:8090",
                "stock-ai-rule.risk-warning.collection-chunk-size=0"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "risk warning collectionChunkSize must be between 1 and 500");
        });

        contextRunner.withPropertyValues(
                "stock-ai-rule.risk-warning.enabled=true",
                "stock-ai-rule.risk-warning.model-version=risk-runtime-v1",
                "stock-ai-rule.risk-warning.after-close-cutoff=19:00",
                "stock-ai-rule.risk-warning.ak-tools-base-url=http://127.0.0.1:8090",
                "stock-ai-rule.risk-warning.collection-chunk-size=501"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "risk warning collectionChunkSize must be between 1 and 500");
        });

        contextRunner.withPropertyValues(
                "stock-ai-rule.risk-warning.enabled=true",
                "stock-ai-rule.risk-warning.model-version=risk-runtime-v1",
                "stock-ai-rule.risk-warning.ak-tools-base-url=http://127.0.0.1:8090"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "risk warning afterCloseCutoff must be configured when enabled");
        });

        contextRunner.withPropertyValues(
                "stock-ai-rule.risk-warning.enabled=true",
                "stock-ai-rule.risk-warning.model-version=risk-runtime-v1",
                "stock-ai-rule.risk-warning.after-close-cutoff=19:00",
                "stock-ai-rule.risk-warning.ak-tools-base-url=",
                "stock-ai-rule.market-data.provider.ak-tools-base-url="
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "risk warning AKTools baseUrl must be configured when enabled");
        });
    }

    private ApplicationContextRunner enabledRunner() {
        return contextRunner.withPropertyValues(
                "stock-ai-rule.risk-warning.enabled=true",
                "stock-ai-rule.risk-warning.backfill-enabled=false",
                "stock-ai-rule.risk-warning.model-version=risk-runtime-v1",
                "stock-ai-rule.risk-warning.after-close-cutoff=19:00",
                "stock-ai-rule.risk-warning.ak-tools-base-url=http://127.0.0.1:8090"
        );
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
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
