package com.jx.tracker.config.properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class StockDashboardPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsCustomBenchmarkSymbolUsingNormalizedMarketAlias() {
        contextRunner
                .withPropertyValues("stock.dashboard.benchmark-symbols[CN]=CUSTOM.CN")
                .run(context -> {
                    StockDashboardProperties properties = context.getBean(StockDashboardProperties.class);

                    assertThat(properties.benchmarkSymbol("A股")).isEqualTo("CUSTOM.CN");
                    assertThat(properties.benchmarkSymbol("CN")).isEqualTo("CUSTOM.CN");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StockDashboardProperties.class)
    static class TestConfiguration {
    }
}
