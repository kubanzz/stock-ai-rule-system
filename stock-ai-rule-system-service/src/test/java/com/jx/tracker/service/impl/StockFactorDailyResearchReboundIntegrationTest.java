package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.TechnicalFactorCalculateRequestDto;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.vo.StockFactorDailyVo;
import com.jx.tracker.factor.ResearchReboundFactorCalculator;
import com.jx.tracker.factor.TechnicalFactorCalculator;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 真实两个计算器与持久化服务协作测试；数据库边界使用 Mapper mock。 */
@ExtendWith(MockitoExtension.class)
class StockFactorDailyResearchReboundIntegrationTest {

    private static final String SYMBOL = "000062.SZ";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 29);
    @Mock private StockDailyQuoteMapper quoteMapper;
    @Mock private StockFactorDailyMapper factorMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void initializeLambdaMetadata() {
        Configuration configuration = new Configuration();
        // Match the application's underscore column mapping. Plain MyBatis
        // Configuration otherwise renders Java property names in this fixture.
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "test"), StockDailyQuote.class);
    }

    @Test
    void memberLoadsUntruncatedStockHistoryAndSameDateBenchmarkThenPersistsResearchFactors() throws Exception {
        stubQuotes(quotes(SYMBOL, 252, 100), quotes("000300.SH", 60, 3000));
        StockFactorDailyVo result = service().calculateAndSave(request());

        assertThat(result.getFactors()).containsEntry("research_history_bars", 252)
                .containsEntry("research_rebound_eligible", true)
                .containsEntry("research_rebound_version", ResearchReboundFactorCalculator.VERSION);
        JsonNode saved = savedFactors();
        assertThat(saved.path("research_history_bars").asInt()).isEqualTo(252);
        assertThat(saved.path("research_rebound_eligible").asBoolean()).isTrue();
        assertThat(saved.path("research_hs300_distance_ma60").asDouble()).isEqualTo(0);
        assertThat(saved.has("rsi14")).isTrue();
        assertMemberQueries(false);
    }

    @Test
    void historicalRealQuotePathAlsoPersistsResearchFields() throws Exception {
        stubQuotes(quotes(SYMBOL, 252, 100), quotes("000300.SH", 60, 3000));
        service().calculateAndSaveFromRealQuotes(request());
        assertThat(savedFactors().path("research_rebound_eligible").asBoolean()).isTrue();
        assertMemberQueries(true);
    }

    @Test
    void missingCurrentIndexPersistsExplicitIneligibilityInsteadOfOldIndexValues() throws Exception {
        List<StockDailyQuote> staleBenchmark = quotes("000300.SH", 61, 3000);
        staleBenchmark.removeLast();
        stubQuotes(quotes(SYMBOL, 252, 100), staleBenchmark);

        StockFactorDailyVo result = service().calculateAndSave(request());
        assertThat(result.getFactors()).containsEntry("research_rebound_eligible", false)
                .containsEntry("research_rebound_unavailable_reason", "missing_current_benchmark_quote")
                .doesNotContainKey("research_hs300_distance_ma60");
        JsonNode saved = savedFactors();
        assertThat(saved.path("research_rebound_eligible").asBoolean()).isFalse();
        assertThat(saved.path("research_rebound_unavailable_reason").asText())
                .isEqualTo("missing_current_benchmark_quote");
    }

    private void stubQuotes(List<StockDailyQuote> stock, List<StockDailyQuote> benchmark) {
        when(quoteMapper.selectList(any())).thenReturn(stock.subList(stock.size() - 80, stock.size()))
                .thenReturn(stock).thenReturn(benchmark);
        when(factorMapper.selectOne(any())).thenReturn(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertMemberQueries(boolean realOnly) {
        ArgumentCaptor<LambdaQueryWrapper> queries = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(quoteMapper, times(3)).selectList(queries.capture());
        List<LambdaQueryWrapper> captured = queries.getAllValues();
        assertThat(captured.get(0).getSqlSegment()).contains("LIMIT 80");
        if (realOnly) {
            assertThat(captured.get(0).getSqlSegment()).contains("data_source IS NOT NULL", "data_source <>");
        }
        for (int i = 1; i < 3; i++) {
            assertThat(captured.get(i).getSqlSegment()).contains("trade_date <=", "ORDER BY trade_date ASC")
                    .doesNotContain("LIMIT");
            assertThat(captured.get(i).getParamNameValuePairs()).containsValue(DATE)
                    .containsValue(i == 1 ? SYMBOL : "000300.SH");
        }
    }

    private JsonNode savedFactors() throws Exception {
        ArgumentCaptor<StockFactorDaily> row = ArgumentCaptor.forClass(StockFactorDaily.class);
        verify(factorMapper).insert(row.capture());
        assertThat(row.getValue().getSymbol()).isEqualTo(SYMBOL);
        assertThat(row.getValue().getTradeDate()).isEqualTo(DATE);
        return objectMapper.readTree(row.getValue().getFactorJson());
    }

    private StockFactorDailyServiceImpl service() {
        return new StockFactorDailyServiceImpl(quoteMapper, factorMapper, new TechnicalFactorCalculator(),
                objectMapper, new ResearchReboundFactorCalculator());
    }

    private static TechnicalFactorCalculateRequestDto request() {
        return TechnicalFactorCalculateRequestDto.builder().symbol(SYMBOL).tradeDate(DATE).build();
    }

    private static List<StockDailyQuote> quotes(String symbol, int size, int price) {
        List<StockDailyQuote> quotes = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            quotes.add(StockDailyQuote.builder().symbol(symbol).tradeDate(DATE.minusDays(size - i - 1))
                    .openPrice(BigDecimal.valueOf(price)).closePrice(BigDecimal.valueOf(price))
                    .highPrice(BigDecimal.valueOf(price + 1)).lowPrice(BigDecimal.valueOf(price - 1))
                    .volume(BigDecimal.valueOf(1_000_000)).amount(BigDecimal.valueOf(100_000_000))
                    .changePct(BigDecimal.ZERO).dataSource("aktools").build());
        }
        return quotes;
    }
}
