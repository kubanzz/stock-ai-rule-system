package com.jx.tracker.risk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.market.data.provider.MarketDataProviderProperties;
import com.jx.tracker.risk.data.flow.AkToolsFlowEventSourceClient;
import com.jx.tracker.risk.data.flow.FlowEventRiskDataProvider;
import com.jx.tracker.risk.data.market.AkToolsMarketRiskSourceClient;
import com.jx.tracker.risk.data.market.MarketRiskDataProvider;
import com.jx.tracker.risk.data.market.RestClientMarketRiskHttpTransport;
import com.jx.tracker.risk.backfill.RiskBackfillCommandConfiguration;
import com.jx.tracker.risk.backfill.RiskBackfillCommandProperties;
import com.jx.tracker.risk.engine.RiskNormalizer;
import com.jx.tracker.risk.engine.RiskScoringEngine;
import com.jx.tracker.risk.gate.ShadowRiskGate;
import com.jx.tracker.risk.sync.RiskSyncJobService;
import com.jx.tracker.risk.workflow.DefaultRiskSnapshotEvaluator;
import com.jx.tracker.risk.workflow.PercentileRiskEvidenceAssembler;
import com.jx.tracker.risk.workflow.RiskWarningWorkflow;
import com.jx.tracker.risk.workflow.RiskWorkflowRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        RiskWarningProperties.class,
        RiskBackfillCommandProperties.class,
        MarketDataProviderProperties.class
})
@Import({
        RiskWarningConfiguration.EnabledRiskWarningConfiguration.class,
        RiskBackfillCommandConfiguration.class
})
public class RiskWarningConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "stock-ai-rule.risk-warning",
            name = "enabled",
            havingValue = "true"
    )
    static class EnabledRiskWarningConfiguration {

        @Bean
        @ConditionalOnMissingBean(Clock.class)
        Clock riskWarningClock() {
            return Clock.system(ZoneId.of("Asia/Shanghai"));
        }

        @Bean
        ValidatedRiskSource validatedRiskSource(
                RiskWarningProperties riskProperties,
                MarketDataProviderProperties marketDataProperties
        ) {
            riskProperties.validateSource(marketDataProperties.getToken());
            return ValidatedRiskSource.INSTANCE;
        }

        @Bean
        RestClientMarketRiskHttpTransport riskMarketHttpTransport(
                RiskWarningProperties riskProperties,
                MarketDataProviderProperties marketDataProperties,
                ValidatedRiskSource validatedRiskSource,
                RestClient.Builder restClientBuilder,
                ObjectMapper objectMapper
        ) {
            return new RestClientMarketRiskHttpTransport(
                    riskProperties.resolvedAkToolsBaseUrl(marketDataProperties.getAkToolsBaseUrl()),
                    restClientBuilder.clone(),
                    objectMapper
            );
        }

        @Bean
        AkToolsMarketRiskSourceClient riskMarketSourceClient(
                RestClientMarketRiskHttpTransport transport,
                RiskWarningProperties riskProperties,
                RestClient.Builder restClientBuilder,
                ObjectMapper objectMapper,
                Clock clock
        ) {
            String derivedBaseUrl = riskProperties.resolvedDerivedGatewayBaseUrl();
            RestClientMarketRiskHttpTransport derivedTransport = derivedBaseUrl == null
                    ? null
                    : new RestClientMarketRiskHttpTransport(
                            derivedBaseUrl, restClientBuilder.clone(), objectMapper);
            return new AkToolsMarketRiskSourceClient(transport, derivedTransport, clock);
        }

        @Bean
        MarketRiskDataProvider riskMarketDataProvider(AkToolsMarketRiskSourceClient sourceClient) {
            return new MarketRiskDataProvider(sourceClient);
        }

        @Bean
        AkToolsFlowEventSourceClient riskFlowEventSourceClient(
                RiskWarningProperties riskProperties,
                MarketDataProviderProperties marketDataProperties,
                ValidatedRiskSource validatedRiskSource,
                RestClient.Builder restClientBuilder,
                ObjectMapper objectMapper,
                Clock clock
        ) {
            return new AkToolsFlowEventSourceClient(
                    riskProperties.resolvedAkToolsBaseUrl(marketDataProperties.getAkToolsBaseUrl()),
                    riskProperties.resolvedDerivedGatewayBaseUrl(),
                    restClientBuilder.clone(),
                    objectMapper,
                    clock
            );
        }

        @Bean
        FlowEventRiskDataProvider riskFlowEventDataProvider(AkToolsFlowEventSourceClient sourceClient) {
            return new FlowEventRiskDataProvider(sourceClient);
        }

        private enum ValidatedRiskSource {
            INSTANCE
        }

        @Bean
        RiskNormalizer riskNormalizer() {
            return new RiskNormalizer();
        }

        @Bean
        PercentileRiskEvidenceAssembler riskEvidenceAssembler(RiskNormalizer normalizer) {
            return new PercentileRiskEvidenceAssembler(normalizer);
        }

        @Bean
        RiskScoringEngine riskScoringEngine() {
            return new RiskScoringEngine();
        }

        @Bean
        DefaultRiskSnapshotEvaluator riskSnapshotEvaluator(RiskScoringEngine engine) {
            return new DefaultRiskSnapshotEvaluator(engine);
        }

        @Bean
        ShadowRiskGate shadowRiskGate() {
            return new ShadowRiskGate();
        }

        @Bean
        RiskWarningWorkflow riskWarningWorkflow(
                MarketRiskDataProvider marketProvider,
                FlowEventRiskDataProvider flowEventProvider,
                RiskWorkflowRepository repository,
                PercentileRiskEvidenceAssembler evidenceAssembler,
                DefaultRiskSnapshotEvaluator snapshotEvaluator,
                ShadowRiskGate shadowRiskGate,
                RiskWarningProperties properties
        ) {
            return new RiskWarningWorkflow(
                    List.of(marketProvider, flowEventProvider),
                    repository,
                    evidenceAssembler,
                    snapshotEvaluator,
                    shadowRiskGate,
                    properties.requiredCollectionChunkSize()
            );
        }

        @Bean
        JdbcRiskUniverseReader riskUniverseReader(JdbcTemplate jdbcTemplate) {
            return new JdbcRiskUniverseReader(jdbcTemplate);
        }

        @Bean
        JdbcRiskSignalCandidateReader riskSignalCandidateReader(JdbcTemplate jdbcTemplate) {
            return new JdbcRiskSignalCandidateReader(jdbcTemplate);
        }

        @Bean
        JdbcRiskTradeDateResolver riskTradeDateResolver(JdbcTemplate jdbcTemplate) {
            return new JdbcRiskTradeDateResolver(jdbcTemplate);
        }

        @Bean
        RiskWorkflowPlanner riskWorkflowPlanner(
                JdbcRiskUniverseReader universeReader,
                RiskWarningProperties properties
        ) {
            return new RiskWorkflowPlanner(universeReader, properties.requiredCollectionChunkSize());
        }

        @Bean
        DefaultRiskAfterCloseWorkflow riskAfterCloseWorkflow(
                RiskWorkflowPlanner planner,
                JdbcRiskSignalCandidateReader candidateReader,
                RiskWarningWorkflow workflow,
                Clock clock,
                RiskWarningProperties properties
        ) {
            return new DefaultRiskAfterCloseWorkflow(
                    planner, candidateReader, workflow, clock, properties);
        }

        @Bean
        RiskSyncJobService riskSyncJobService(
                DefaultRiskAfterCloseWorkflow workflow,
                JdbcRiskTradeDateResolver tradeDateResolver,
                @Qualifier("applicationTaskExecutor") ObjectProvider<TaskExecutor> taskExecutor,
                Clock clock
        ) {
            return new RiskSyncJobService(
                    workflow, tradeDateResolver,
                    taskExecutor.getIfAvailable(SyncTaskExecutor::new), clock);
        }

        @Bean
        RiskBackfillService riskBackfillService(
                RiskWorkflowPlanner planner,
                JdbcRiskSignalCandidateReader candidateReader,
                RiskWarningWorkflow workflow,
                Clock clock,
                RiskWarningProperties properties
        ) {
            return new RiskBackfillService(
                    planner, candidateReader, workflow, clock, properties);
        }
    }
}
