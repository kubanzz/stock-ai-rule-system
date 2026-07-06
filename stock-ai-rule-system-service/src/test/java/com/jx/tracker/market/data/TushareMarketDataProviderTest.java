package com.jx.tracker.market.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.market.data.dto.TradeCalendarDto;
import com.jx.tracker.market.data.provider.TushareMarketDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
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
