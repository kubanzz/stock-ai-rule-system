package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.TechnicalFactorCalculateRequestDto;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.factor.P4VoteFactorCalculator;
import com.jx.tracker.factor.ResearchReboundFactorCalculator;
import com.jx.tracker.factor.TechnicalFactorCalculator;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockFactorDailyP4VoteIntegrationTest {
    private static final String SYMBOL = "600418.SH";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
    private final StockDailyQuoteMapper quotes = mock(StockDailyQuoteMapper.class);
    private final StockFactorDailyMapper factors = mock(StockFactorDailyMapper.class);
    private final StockBaseMapper stocks = mock(StockBaseMapper.class);
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void initializeLambdaMetadata() {
        Configuration config = new Configuration();config.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "test"), StockDailyQuote.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, "test"), StockBase.class);
    }

    @Test
    void productionLoadsFullFrozenHistoryAndCurrentMetadataThenPersistsRawAtoms() throws Exception {
        List<StockDailyQuote> history = history();
        when(quotes.selectList(any())).thenReturn(history.subList(172, 252)).thenReturn(history).thenReturn(index());
        when(stocks.selectOne(any())).thenReturn(StockBase.builder().symbol(SYMBOL).name("江淮汽车").status("active")
                .dataSource("tushare").lastSyncTime(LocalDateTime.of(2026, 10, 5, 10, 0)).build());
        var result = service().calculateAndSave(request());
        assertThat(result.getFactors()).containsEntry("p4_vote_001_eligible", true)
                .containsEntry("p4_vote_001_change_pct_5d", 0.0).containsEntry("p4_vote_001_open_gap", 0.0);
        JsonNode saved = saved();
        assertThat(saved.path("p4_vote_001_history_bars").asInt()).isEqualTo(252);
        assertThat(saved.path("p4_vote_001_index_close_position").asDouble()).isEqualTo(.2);
        assertThat(saved.path("p4_vote_001_st_check_basis").asText()).contains("not_historical_daily_isST");
        assertThat(saved.has("change_pct_5d")).isTrue();
        assertQueries(false);
    }

    @Test
    void historicalPathRetainsCurrentMetadataLimitationAndRejectsAdjustedSource() throws Exception {
        List<StockDailyQuote> history = history();history.forEach(q -> q.setDataSource("aktools/akshare"));
        when(quotes.selectList(any())).thenReturn(history.subList(172, 252)).thenReturn(history).thenReturn(index());
        when(stocks.selectOne(any())).thenReturn(StockBase.builder().symbol(SYMBOL).name("江淮汽车").status("active")
                .dataSource("tushare").lastSyncTime(LocalDateTime.of(2026, 10, 5, 10, 0)).build());
        service().calculateAndSaveFromRealQuotes(request());
        assertThat(saved().path("p4_vote_001_eligible").asBoolean()).isFalse();
        assertQueries(true);
    }

    private StockFactorDailyServiceImpl service() {
        return new StockFactorDailyServiceImpl(quotes, factors, new TechnicalFactorCalculator(), json,
                new ResearchReboundFactorCalculator(), new P4VoteFactorCalculator(), stocks);
    }
    private TechnicalFactorCalculateRequestDto request() {
        return TechnicalFactorCalculateRequestDto.builder().symbol(SYMBOL).tradeDate(DATE).build();
    }
    private JsonNode saved() throws Exception {
        ArgumentCaptor<StockFactorDaily> captured = ArgumentCaptor.forClass(StockFactorDaily.class);
        verify(factors).insert(captured.capture());
        return json.readTree(captured.getValue().getFactorJson());
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertQueries(boolean realOnly) {
        ArgumentCaptor<LambdaQueryWrapper> captured = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(quotes, times(3)).selectList(captured.capture());
        assertThat(captured.getAllValues().getFirst().getSqlSegment()).contains("LIMIT 80");
        if (realOnly) assertThat(captured.getAllValues().getFirst().getSqlSegment()).contains("data_source IS NOT NULL", "data_source <>");
        for (int i = 1; i < 3; i++) assertThat(captured.getAllValues().get(i).getSqlSegment())
                .contains("trade_date <=", "ORDER BY trade_date ASC").doesNotContain("LIMIT");
        verify(stocks).selectOne(any());
    }
    private static List<StockDailyQuote> history() {
        List<StockDailyQuote> result = new ArrayList<>();
        for (int i = 0; i < 252; i++) result.add(StockDailyQuote.builder().symbol(SYMBOL).tradeDate(DATE.minusDays(251 - i))
                .openPrice(new BigDecimal("100")).highPrice(new BigDecimal("101")).lowPrice(new BigDecimal("99"))
                .closePrice(new BigDecimal("100")).volume(new BigDecimal("1000000")).amount(new BigDecimal("100000000"))
                .dataSource(P4VoteFactorCalculator.BAOSTOCK_RAW_SOURCE).build());
        return result;
    }
    private static List<StockDailyQuote> index() {
        return List.of(StockDailyQuote.builder().symbol("000300.SH").tradeDate(DATE).openPrice(new BigDecimal("110"))
                .highPrice(new BigDecimal("200")).lowPrice(new BigDecimal("100")).closePrice(new BigDecimal("120"))
                .dataSource(P4VoteFactorCalculator.BAOSTOCK_INDEX_SOURCE).build());
    }
}
