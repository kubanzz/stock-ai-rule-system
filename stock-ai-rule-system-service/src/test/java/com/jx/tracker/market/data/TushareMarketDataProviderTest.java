package com.jx.tracker.market.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.market.data.dto.TradeCalendarDto;
import com.jx.tracker.factor.P4VoteFactorCalculator;
import com.jx.tracker.market.data.provider.TushareMarketDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TushareMarketDataProviderTest {

    @Test
    void rejectsPlainHttpRemoteApiUrlBecauseTokenWouldBeSentInBody() {
        assertThatThrownBy(() -> new TushareMarketDataProvider(
                "test-token",
                "http://api.tushare.pro",
                RestClient.builder(),
                new ObjectMapper()
        )).isInstanceOf(ServiceException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void rejectsDailyQuoteSyncWithoutTargetSymbol() {
        TushareMarketDataProvider provider = new TushareMarketDataProvider(
                "test-token",
                "https://api.tushare.pro",
                RestClient.builder(),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> provider.fetchDailyQuotes(null, null, null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("targetSymbol");
    }

    @Test
    void usesIndexDailyForHs300AndNormalizesVolumeAmountAndProvenance() {
        assertQuoteRequest("sh000300", "000300.SH", "index_daily",
                P4VoteFactorCalculator.TUSHARE_INDEX_SOURCE);
    }

    @Test
    void keepsStocksOnDailyAndNormalizesVolumeAmountAndProvenance() {
        assertQuoteRequest("sz000547", "000547.SZ", "daily",
                P4VoteFactorCalculator.TUSHARE_RAW_SOURCE);
    }

    private void assertQuoteRequest(String requestedSymbol, String symbol, String apiName, String source) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.tushare.pro"))
                .andExpect(content().json("""
                        {
                          "api_name": "%s",
                          "token": "test-token",
                          "params": {"ts_code": "%s", "start_date": "20260901", "end_date": "20260930"},
                          "fields": "ts_code,trade_date,open,high,low,close,pre_close,pct_chg,vol,amount"
                        }
                        """.formatted(apiName, symbol)))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "data": {
                            "fields": ["ts_code", "trade_date", "open", "high", "low", "close", "pre_close", "pct_chg", "vol", "amount"],
                            "items": [["%s", "20260930", 10, 12, 9, 11, 10, 10, 123.4, 567.89]]
                          }
                        }
                        """.formatted(symbol), MediaType.APPLICATION_JSON));
        TushareMarketDataProvider provider = new TushareMarketDataProvider(
                "test-token", "https://api.tushare.pro", builder, new ObjectMapper());

        var rows = provider.fetchDailyQuotes(requestedSymbol,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSymbol()).isEqualTo(symbol);
        assertThat(rows.getFirst().getClosePrice()).isEqualByComparingTo("11");
        assertThat(rows.getFirst().getVolume()).isEqualByComparingTo("12340");
        assertThat(rows.getFirst().getAmount()).isEqualByComparingTo("567890");
        assertThat(rows.getFirst().getDataSource()).isEqualTo(source);
        server.verify();
    }

    @Test
    void computesNextTradeDateFromNextOpenCalendarRow() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.tushare.pro"))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "data": {
                            "fields": ["exchange", "cal_date", "is_open", "pretrade_date"],
                            "items": [
                              ["SSE", "20260703", "1", "20260702"],
                              ["SSE", "20260704", "0", "20260703"],
                              ["SSE", "20260706", "1", "20260703"]
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        TushareMarketDataProvider provider = new TushareMarketDataProvider(
                "test-token",
                "https://api.tushare.pro",
                builder,
                new ObjectMapper()
        );

        var rows = provider.fetchTradeCalendar(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 6));

        assertThat(rows).extracting(TradeCalendarDto::getTradeDate)
                .containsExactly(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 6));
        assertThat(rows.get(0).getNextTradeDate()).isEqualTo(LocalDate.of(2026, 7, 6));
        assertThat(rows.get(1).getNextTradeDate()).isEqualTo(LocalDate.of(2026, 7, 6));
        assertThat(rows.get(2).getNextTradeDate()).isNull();
        server.verify();
    }
}
