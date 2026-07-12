package com.jx.tracker.market.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;
import com.jx.tracker.market.data.provider.AkToolsMarketDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AkToolsMarketDataProviderTest {

    @Test
    void mapsAllMarketSpotRowsToStockBases() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/stock_zh_a_spot_em"))
                .andRespond(withSuccess("""
                        [{
                          "代码": "600519",
                          "名称": "贵州茅台",
                          "最新价": 1488.88,
                          "今开": 1470.00,
                          "最高": 1499.00,
                          "最低": 1466.00,
                          "昨收": 1468.88,
                          "涨跌幅": 1.36,
                          "成交量": 215000,
                          "成交额": 3210000000
                        }]
                        """, MediaType.APPLICATION_JSON));
        AkToolsMarketDataProvider provider = provider(builder);

        var rows = provider.fetchStockList();

        assertThat(rows).singleElement().satisfies(stock -> {
            assertThat(stock.getSymbol()).isEqualTo("600519.SH");
            assertThat(stock.getName()).isEqualTo("贵州茅台");
            assertThat(stock.getMarket()).isEqualTo("CN");
            assertThat(stock.getExchange()).isEqualTo("SH");
            assertThat(stock.getStatus()).isEqualTo("active");
            assertThat(stock.getDataSource()).isEqualTo("aktools/akshare");
        });
        server.verify();
    }

    @Test
    void mapsSpotRowsToWholeMarketDailySnapshotForRequestedDate() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/stock_zh_a_spot_em"))
                .andRespond(withSuccess("""
                        [{
                          "代码": "300750",
                          "名称": "宁德时代",
                          "最新价": 251.20,
                          "今开": 248.10,
                          "最高": 253.00,
                          "最低": 247.50,
                          "昨收": 248.00,
                          "涨跌幅": 1.29,
                          "成交量": 10000,
                          "成交额": 250000000
                        }]
                        """, MediaType.APPLICATION_JSON));
        AkToolsMarketDataProvider provider = provider(builder);

        var rows = provider.fetchDailyQuotes(null, LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 10));

        assertThat(rows).singleElement().satisfies(quote -> {
            assertThat(quote.getSymbol()).isEqualTo("300750.SZ");
            assertThat(quote.getTradeDate()).isEqualTo(LocalDate.of(2026, 7, 10));
            assertThat(quote.getClosePrice()).isEqualByComparingTo("251.20");
            assertThat(quote.getPreClose()).isEqualByComparingTo("248.00");
            assertThat(quote.getChangePct()).isEqualByComparingTo("1.29");
            assertThat(quote.getVolume()).isEqualByComparingTo("10000");
            assertThat(quote.getAmount()).isEqualByComparingTo("250000000");
            assertThat(quote.getDataSource()).isEqualTo("aktools/akshare");
        });
        server.verify();
    }

    @Test
    void routesHs300ToIndexHistoryAndComputesPreviousClose() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_zh_index_daily_em")))
                .andExpect(queryParam("symbol", "sh000300"))
                .andExpect(queryParam("start_date", "20260709"))
                .andExpect(queryParam("end_date", "20260710"))
                .andRespond(withSuccess("""
                        [
                          {"date":"2026-07-09T00:00:00.000","open":4000,"close":4010,"high":4020,"low":3990,"volume":100,"amount":1000},
                          {"date":"2026-07-10T00:00:00.000","open":4012,"close":4050,"high":4060,"low":4005,"volume":120,"amount":1200}
                        ]
                        """, MediaType.APPLICATION_JSON));
        AkToolsMarketDataProvider provider = provider(builder);

        var rows = provider.fetchDailyQuotes(
                "000300.SH", LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 10));

        assertThat(rows).hasSize(2);
        StockDailyQuoteUpsertDto latest = rows.get(1);
        assertThat(latest.getSymbol()).isEqualTo("000300.SH");
        assertThat(latest.getPreClose()).isEqualByComparingTo("4010");
        assertThat(latest.getChangePct()).isEqualByComparingTo(
                new BigDecimal("40").multiply(new BigDecimal("100"))
                        .divide(new BigDecimal("4010"), 6, java.math.RoundingMode.HALF_UP));
        server.verify();
    }

    @Test
    void rejectsEmptyExternalResponsesInsteadOfReturningMockData() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/stock_zh_a_spot_em"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        AkToolsMarketDataProvider provider = provider(builder);

        assertThatThrownBy(provider::fetchStockList)
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("空数据");
        server.verify();
    }

    private AkToolsMarketDataProvider provider(RestClient.Builder builder) {
        return new AkToolsMarketDataProvider(
                "http://127.0.0.1:8090", builder, new ObjectMapper());
    }
}
