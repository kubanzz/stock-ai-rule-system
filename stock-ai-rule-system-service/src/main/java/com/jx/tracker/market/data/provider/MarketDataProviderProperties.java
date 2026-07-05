package com.jx.tracker.market.data.provider;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "stock-ai-rule.market-data.provider")
public class MarketDataProviderProperties {

    private String type = "mock";

    private String fallbackType = "mock";

    private String token;

    private String apiUrl = "http://api.tushare.pro";

    private String stockListCsvPath;

    private String dailyQuoteCsvPath;

    private String tradeCalendarCsvPath;
}
