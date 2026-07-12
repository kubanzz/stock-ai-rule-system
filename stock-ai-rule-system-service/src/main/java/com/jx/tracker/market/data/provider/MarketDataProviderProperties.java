package com.jx.tracker.market.data.provider;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "stock-ai-rule.market-data.provider")
public class MarketDataProviderProperties {

    private String type = "mock";

    private String fallbackType = "mock";

    private String token;

    private String apiUrl = "https://api.tushare.pro";

    private String akToolsBaseUrl = "http://127.0.0.1:8090";

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration readTimeout = Duration.ofSeconds(60);

    private int retryCount = 3;

    private int minimumStockCount = 4000;

    private BigDecimal minimumQuoteCoverage = new BigDecimal("0.95");

    private String stockListCsvPath;

    private String dailyQuoteCsvPath;

    private String tradeCalendarCsvPath;
}
