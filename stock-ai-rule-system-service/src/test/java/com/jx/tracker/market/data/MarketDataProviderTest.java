package com.jx.tracker.market.data;

import com.jx.tracker.market.data.provider.MarketDataProvider;
import com.jx.tracker.market.data.provider.MockMarketDataProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataProviderTest {

    @Test
    void mockProviderExposesDeterministicStockBaseAndDailyQuotes() {
        MarketDataProvider provider = new MockMarketDataProvider();

        assertThat(provider.fetchStockList())
                .extracting("symbol")
                .contains("000001.SZ", "600000.SH");

        assertThat(provider.fetchDailyQuotes("sz000001", LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 21)))
                .hasSize(2)
                .allSatisfy(quote -> {
                    assertThat(quote.getSymbol()).isEqualTo("000001.SZ");
                    assertThat(quote.getTradeDate()).isBetween(LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 21));
                });

        assertThat(provider.fetchTradeCalendar(LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 21)))
                .hasSize(2)
                .allSatisfy(day -> {
                    assertThat(day.getMarket()).isEqualTo("CN");
                    assertThat(day.isOpen()).isTrue();
                });
    }
}
