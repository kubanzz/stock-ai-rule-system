package com.jx.tracker.risk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.backfill.RiskBackfillCommandProperties;
import com.jx.tracker.risk.data.flow.AkToolsFlowEventSourceClient;
import com.jx.tracker.risk.data.flow.CompositeFlowEventSourceClient;
import com.jx.tracker.risk.data.flow.FlowEventRiskDataProvider;
import com.jx.tracker.risk.data.flow.FlowEventSourceClient;
import com.jx.tracker.risk.data.flow.TushareFlowEventSourceClient;
import com.jx.tracker.risk.data.market.AkToolsMarketRiskSourceClient;
import com.jx.tracker.risk.data.market.FallbackMarketRiskSourceClient;
import com.jx.tracker.risk.data.market.MarketRiskDataProvider;
import com.jx.tracker.risk.data.market.MarketRiskSourceClient;
import com.jx.tracker.risk.data.market.TushareMarketRiskSourceClient;
import com.jx.tracker.risk.data.tushare.TushareRiskHttpClient;
import com.jx.tracker.risk.engine.RiskNormalizer;
import com.jx.tracker.risk.engine.RiskScoringEngine;
import com.jx.tracker.risk.gate.ShadowRiskGate;
import com.jx.tracker.risk.provider.RiskDataProvider;
import com.jx.tracker.risk.sync.RiskSyncJobService;
import com.jx.tracker.risk.workflow.DefaultRiskSnapshotEvaluator;
import com.jx.tracker.risk.workflow.PercentileRiskEvidenceAssembler;
import com.jx.tracker.risk.workflow.RiskAfterCloseWorkflow;
import com.jx.tracker.risk.workflow.RiskWarningWorkflow;
import com.jx.tracker.risk.workflow.RiskWorkflowRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RiskWarningConfigurationTest {

    private static final String TEST_TUSHARE_TOKEN = "test-token-not-secret";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RiskWarningConfiguration.class, Dependencies.class);

    @Test
    void riskRuntimeIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RiskWarningProperties.class);
            assertThat(context).hasSingleBean(RiskBackfillCommandProperties.class);
            assertThat(context.getBean(RiskBackfillCommandProperties.class).isEnabled()).isFalse();
            assertThat(context.getBean(RiskWarningProperties.class)).satisfies(properties -> {
                assertThat(properties.isEnabled()).isFalse();
                assertThat(properties.isBackfillEnabled()).isFalse();
                assertThat(properties.getCollectionChunkSize()).isEqualTo(25);
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
            assertThat(context).doesNotHaveBean(TushareRiskHttpClient.class);
            assertThat(context).doesNotHaveBean(TushareMarketRiskSourceClient.class);
            assertThat(context).doesNotHaveBean(FallbackMarketRiskSourceClient.class);
            assertThat(context).hasSingleBean(AkToolsFlowEventSourceClient.class);
            assertThat(context).doesNotHaveBean(TushareFlowEventSourceClient.class);
            assertThat(context).doesNotHaveBean(CompositeFlowEventSourceClient.class);
            assertThat(context.getBean(FlowEventSourceClient.class))
                    .isSameAs(context.getBean(AkToolsFlowEventSourceClient.class));
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
            assertThat(context).hasSingleBean(JdbcRiskTradeDateResolver.class);
            assertThat(context).hasSingleBean(RiskWorkflowPlanner.class);
            assertThat(context).hasSingleBean(DefaultRiskAfterCloseWorkflow.class);
            assertThat(context).hasSingleBean(RiskAfterCloseWorkflow.class);
            assertThat(context).hasSingleBean(RiskBackfillService.class);
            assertThat(context).hasSingleBean(RiskSyncJobService.class);
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
    void enabledRuntimeRejectsAnUnsupportedRiskPrimarySource() {
        enabledRunner().withPropertyValues(
                "stock-ai-rule.risk-warning.source.primary=csv"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "risk warning source primary must be one of: aktools, tushare");
            assertThat(rootCause(context.getStartupFailure()).getMessage()).doesNotContain(TEST_TUSHARE_TOKEN);
        });
    }

    @Test
    void enabledRuntimeRejectsTusharePrimarySourceWhenItsSwitchIsDisabled() {
        enabledRunner().withPropertyValues(
                "stock-ai-rule.risk-warning.source.primary=tushare",
                "stock-ai-rule.risk-warning.source.tushare-enabled=false",
                "stock-ai-rule.market-data.provider.token=" + TEST_TUSHARE_TOKEN
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "risk warning TuShare must be enabled when it is the primary source");
            assertThat(rootCause(context.getStartupFailure()).getMessage()).doesNotContain(TEST_TUSHARE_TOKEN);
        });
    }

    @Test
    void enabledRuntimeRejectsAnEnabledTushareSourceWithoutAToken() {
        enabledRunner().withPropertyValues(
                "stock-ai-rule.risk-warning.source.tushare-enabled=true"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "risk warning TuShare token must be configured when TuShare is enabled");
            assertThat(rootCause(context.getStartupFailure()).getMessage()).doesNotContain(TEST_TUSHARE_TOKEN);
        });
    }

    @Test
    void enabledRuntimeAcceptsACompleteTushareSourceConfiguration() {
        enabledRunner().withPropertyValues(
                "stock-ai-rule.risk-warning.source.primary=tushare",
                "stock-ai-rule.risk-warning.source.tushare-enabled=true",
                "stock-ai-rule.market-data.provider.token=" + TEST_TUSHARE_TOKEN
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void tusharePrimaryConditionTrimsAndNormalizesCase() {
        enabledRunner().withPropertyValues(
                "stock-ai-rule.risk-warning.source.primary= TuShArE ",
                "stock-ai-rule.risk-warning.source.tushare-enabled=true",
                "stock-ai-rule.market-data.provider.token=" + TEST_TUSHARE_TOKEN
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TushareRiskHttpClient.class);
            assertThat(context).hasSingleBean(TushareMarketRiskSourceClient.class);
            assertThat(context).hasSingleBean(FallbackMarketRiskSourceClient.class);
            assertThat(context).hasSingleBean(TushareFlowEventSourceClient.class);
            assertThat(context).hasSingleBean(CompositeFlowEventSourceClient.class);
        });
    }

    @Test
    void tusharePrimaryWiresAnAuditableFallbackAndConfiguredHttpTimeouts() {
        enabledRunner().withPropertyValues(
                "stock-ai-rule.risk-warning.source.primary=tushare",
                "stock-ai-rule.risk-warning.source.tushare-enabled=true",
                "stock-ai-rule.market-data.provider.token=" + TEST_TUSHARE_TOKEN,
                "stock-ai-rule.market-data.provider.connect-timeout=2s",
                "stock-ai-rule.market-data.provider.read-timeout=17s"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AkToolsMarketRiskSourceClient.class);
            assertThat(context).hasSingleBean(TushareRiskHttpClient.class);
            assertThat(context).hasSingleBean(TushareMarketRiskSourceClient.class);
            assertThat(context).hasSingleBean(FallbackMarketRiskSourceClient.class);
            assertThat(context).hasSingleBean(TushareFlowEventSourceClient.class);
            assertThat(context).hasSingleBean(CompositeFlowEventSourceClient.class);

            MarketRiskSourceClient selected = context.getBean(MarketRiskSourceClient.class);
            FallbackMarketRiskSourceClient fallback =
                    context.getBean(FallbackMarketRiskSourceClient.class);
            assertThat(selected).isSameAs(fallback);
            assertThat(ReflectionTestUtils.getField(fallback, "primary"))
                    .isSameAs(context.getBean(TushareMarketRiskSourceClient.class));
            assertThat(ReflectionTestUtils.getField(fallback, "fallback"))
                    .isSameAs(context.getBean(AkToolsMarketRiskSourceClient.class));
            assertThat(ReflectionTestUtils.getField(
                    context.getBean(MarketRiskDataProvider.class), "sourceClient"))
                    .isSameAs(fallback);

            FlowEventSourceClient selectedFlow =
                    context.getBean(FlowEventSourceClient.class);
            CompositeFlowEventSourceClient composite =
                    context.getBean(CompositeFlowEventSourceClient.class);
            assertThat(selectedFlow).isSameAs(composite);
            assertThat(ReflectionTestUtils.getField(composite, "primary"))
                    .isSameAs(context.getBean(TushareFlowEventSourceClient.class));
            assertThat((java.util.Map<Object, Object>) ReflectionTestUtils.getField(
                    composite, "directRoutes"))
                    .containsKey("stock_announcement");
            assertThat(ReflectionTestUtils.getField(
                    context.getBean(FlowEventRiskDataProvider.class), "sourceClient"))
                    .isSameAs(composite);

            TushareRiskHttpClient riskHttpClient =
                    context.getBean(TushareRiskHttpClient.class);
            Object restClient = ReflectionTestUtils.getField(riskHttpClient, "restClient");
            Object requestFactory =
                    ReflectionTestUtils.getField(restClient, "clientRequestFactory");
            assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);
            HttpClient javaHttpClient = (HttpClient) ReflectionTestUtils.getField(
                    requestFactory, "httpClient");
            assertThat(javaHttpClient.connectTimeout()).contains(Duration.ofSeconds(2));
            assertThat(ReflectionTestUtils.getField(requestFactory, "readTimeout"))
                    .isEqualTo(Duration.ofSeconds(17));
        });
    }

    @Test
    void tusharePrimaryCanDisableTheDirectCninfoAnnouncementRoute() {
        enabledRunner().withPropertyValues(
                "stock-ai-rule.risk-warning.source.primary=tushare",
                "stock-ai-rule.risk-warning.source.tushare-enabled=true",
                "stock-ai-rule.risk-warning.source.cninfo-announcement-fallback-enabled=false",
                "stock-ai-rule.market-data.provider.token=" + TEST_TUSHARE_TOKEN
        ).run(context -> {
            assertThat(context).hasNotFailed();
            CompositeFlowEventSourceClient composite =
                    context.getBean(CompositeFlowEventSourceClient.class);
            assertThat((java.util.Map<?, ?>) ReflectionTestUtils.getField(
                    composite, "directRoutes")).isEmpty();
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
                    "risk warning collectionChunkSize must be between 21 and 50");
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
                    "risk warning collectionChunkSize must be between 21 and 50");
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

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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
