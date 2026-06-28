package com.jx.tracker.market.data;

import com.jx.tracker.market.data.dto.MarketDataImportResultDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;
import com.jx.tracker.market.data.provider.CsvMarketDataProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CsvMarketDataProviderTest {

    @Test
    void importsValidRowsAndRejectsAbnormalDailyQuotes() {
        String csv = """
                symbol,trade_date,open_price,high_price,low_price,close_price,volume,amount,change_pct
                sz000001,2026-06-20,10.00,10.80,9.90,10.50,100000,1050000,1.20
                sz000001,2026-06-21,10.00,9.80,10.20,10.10,100000,1010000,0.30
                """;

        CsvMarketDataProvider provider = CsvMarketDataProvider.fromDailyQuoteCsv(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));

        MarketDataImportResultDto<StockDailyQuoteUpsertDto> result = provider.importDailyQuotes();

        assertThat(result.getAcceptedRows()).hasSize(1);
        assertThat(result.getRejectedRows()).hasSize(1);
        assertThat(result.getRejectedRows().getFirst().getReason()).contains("high_price");
        assertThat(result.getAcceptedRows().getFirst().getSymbol()).isEqualTo("000001.SZ");
        assertThat(result.getAcceptedRows().getFirst().getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(result.getAcceptedRows().getFirst().getClosePrice()).isEqualByComparingTo(new BigDecimal("10.50"));
    }
}
