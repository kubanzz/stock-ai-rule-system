package com.jx.tracker.risk.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.market.data.provider.MarketDataProviderProperties;
import com.jx.tracker.risk.runtime.RiskBackfillService;
import com.jx.tracker.risk.runtime.RiskUniverseReader;
import com.jx.tracker.risk.runtime.RiskWarningProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "stock-ai-rule.risk-warning.backfill-command",
        name = "enabled",
        havingValue = "true"
)
public class RiskBackfillCommandConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock riskBackfillCommandClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }

    @Bean
    RiskBackfillPreflightRepository riskBackfillPreflightRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRiskBackfillPreflightRepository(jdbcTemplate);
    }

    @Bean
    RiskBackfillReadinessRepository riskBackfillReadinessRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRiskBackfillReadinessRepository(jdbcTemplate);
    }

    @Bean
    RiskBackfillSourceProbe riskBackfillSourceProbe(
            RiskWarningProperties riskProperties,
            MarketDataProviderProperties marketDataProperties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        return new HttpRiskBackfillSourceProbe(
                riskProperties, marketDataProperties, restClientBuilder, objectMapper);
    }

    @Bean
    RiskBackfillPreflightService riskBackfillPreflightService(
            RiskBackfillPreflightRepository repository,
            RiskBackfillSourceProbe sourceProbe
    ) {
        return new RiskBackfillPreflightService(repository, sourceProbe);
    }

    @Bean
    RiskBackfillSampleSelector riskBackfillSampleSelector() {
        return new RiskBackfillSampleSelector();
    }

    @Bean
    RiskBackfillReadinessEvaluator riskBackfillReadinessEvaluator() {
        return new RiskBackfillReadinessEvaluator();
    }

    @Bean
    RiskBackfillReportStore riskBackfillReportStore(ObjectMapper objectMapper) {
        return new JacksonRiskBackfillReportStore(objectMapper);
    }

    @Bean
    RiskBackfillCommandRunner riskBackfillCommandRunner(
            RiskBackfillCommandProperties commandProperties,
            RiskWarningProperties warningProperties,
            ObjectProvider<RiskUniverseReader> universeReader,
            ObjectProvider<RiskBackfillService> backfillService,
            RiskBackfillSampleSelector sampleSelector,
            RiskBackfillPreflightService preflightService,
            RiskBackfillReadinessRepository readinessRepository,
            RiskBackfillReadinessEvaluator readinessEvaluator,
            RiskBackfillReportStore reportStore,
            Clock clock
    ) {
        return new RiskBackfillCommandRunner(
                commandProperties, warningProperties,
                Optional.ofNullable(universeReader.getIfAvailable()),
                Optional.ofNullable(backfillService.getIfAvailable()),
                sampleSelector, preflightService, readinessRepository,
                readinessEvaluator, reportStore, clock);
    }
}
