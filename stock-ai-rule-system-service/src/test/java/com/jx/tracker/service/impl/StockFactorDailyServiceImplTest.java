package com.jx.tracker.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.jx.tracker.domain.dto.TechnicalFactorCalculateRequestDto;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.vo.StockFactorDailyVo;
import com.jx.tracker.factor.TechnicalFactorCalculator;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockFactorDailyServiceImplTest {

    @Mock
    private StockDailyQuoteMapper quoteMapper;

    @Mock
    private StockFactorDailyMapper factorMapper;

    @Test
    void calculatesAndInsertsDailyTechnicalFactorJson() throws Exception {
        LocalDate targetDate = LocalDate.of(2026, 6, 26);
        when(quoteMapper.selectList(any())).thenReturn(risingQuotes("000001.SZ", targetDate.minusDays(34), 35));
        when(factorMapper.selectOne(any())).thenReturn(null);

        StockFactorDailyServiceImpl service = new StockFactorDailyServiceImpl(
                quoteMapper,
                factorMapper,
                new TechnicalFactorCalculator(),
                new ObjectMapper()
        );
        TechnicalFactorCalculateRequestDto request = TechnicalFactorCalculateRequestDto.builder()
                .symbol("000001.SZ")
                .tradeDate(targetDate)
                .build();

        StockFactorDailyVo result = service.calculateAndSave(request);

        ArgumentCaptor<StockFactorDaily> captor = ArgumentCaptor.forClass(StockFactorDaily.class);
        verify(factorMapper).insert(captor.capture());
        verify(quoteMapper).selectList(any());
        Map<String, Object> savedFactors = new ObjectMapper().readValue(
                captor.getValue().getFactorJson(),
                new TypeReference<>() {
                }
        );
        assertThat(result.getSymbol()).isEqualTo("000001.SZ");
        assertThat(result.getTradeDate()).isEqualTo(targetDate);
        assertThat(savedFactors)
                .containsEntry("short_term_trend", "strong_up")
                .containsEntry("volume_status", "normal")
                .containsEntry("technical_status", "bullish")
                .containsEntry("risk_status", "normal")
                .containsEntry("data_status", "normal");
    }

    @Test
    void batchCalculationCalculatesEachRequestedSymbolForSameTradeDate() {
        LocalDate targetDate = LocalDate.of(2026, 6, 26);
        when(quoteMapper.selectList(any()))
                .thenReturn(risingQuotes("000001.SZ", targetDate.minusDays(34), 35))
                .thenReturn(risingQuotes("000002.SZ", targetDate.minusDays(34), 35));
        when(factorMapper.selectOne(any())).thenReturn(null);

        StockFactorDailyServiceImpl service = new StockFactorDailyServiceImpl(
                quoteMapper,
                factorMapper,
                new TechnicalFactorCalculator(),
                new ObjectMapper()
        );

        List<StockFactorDailyVo> results = service.calculateAndSaveBatch(
                List.of("000001.SZ", "000002.SZ", "000001.SZ", " "),
                targetDate
        );

        assertThat(results)
                .extracting(StockFactorDailyVo::getSymbol)
                .containsExactly("000001.SZ", "000002.SZ");
        verify(factorMapper, times(2)).insert(any(StockFactorDaily.class));
    }

    private static List<StockDailyQuote> risingQuotes(String symbol, LocalDate startDate, int days) {
        List<StockDailyQuote> quotes = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            BigDecimal close = BigDecimal.valueOf(i + 1);
            quotes.add(StockDailyQuote.builder()
                    .symbol(symbol)
                    .tradeDate(startDate.plusDays(i))
                    .openPrice(close)
                    .highPrice(close)
                    .lowPrice(close)
                    .closePrice(close)
                    .volume(new BigDecimal("1000000"))
                    .changePct(BigDecimal.ONE)
                    .build());
        }
        return quotes;
    }
}
