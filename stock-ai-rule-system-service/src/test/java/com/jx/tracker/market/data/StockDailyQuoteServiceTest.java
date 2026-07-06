package com.jx.tracker.market.data;

import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;
import com.jx.tracker.market.data.service.impl.StockDailyQuoteServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockDailyQuoteServiceTest {

    @Test
    void upsertBySymbolAndTradeDateUpdatesExistingRecordWithoutDuplicateInsert() {
        StockDailyQuoteMapper mapper = mock(StockDailyQuoteMapper.class);
        StockDailyQuote existing = StockDailyQuote.builder()
                .id(100L)
                .symbol("SZ000001")
                .tradeDate(LocalDate.of(2026, 6, 20))
                .closePrice(new BigDecimal("10.10"))
                .build();
        when(mapper.selectOne(any())).thenReturn(existing);

        StockDailyQuoteServiceImpl service = new StockDailyQuoteServiceImpl(mapper);

        var result = service.upsertDailyQuotes(List.of(validQuote("sz000001", "10.50")));

        assertThat(result.getTotalRows()).isEqualTo(1);
        assertThat(result.getInsertedRows()).isZero();
        assertThat(result.getUpdatedRows()).isEqualTo(1);
        verify(mapper, never()).insert(any(StockDailyQuote.class));
        verify(mapper).updateById(any(StockDailyQuote.class));
    }

    @Test
    void upsertRejectsAbnormalQuoteAndDoesNotWriteIt() {
        StockDailyQuoteMapper mapper = mock(StockDailyQuoteMapper.class);
        StockDailyQuoteServiceImpl service = new StockDailyQuoteServiceImpl(mapper);
        StockDailyQuoteUpsertDto abnormal = validQuote("SZ000001", "10.50");
        abnormal.setHighPrice(new BigDecimal("9.80"));
        abnormal.setLowPrice(new BigDecimal("10.20"));

        var result = service.upsertDailyQuotes(List.of(abnormal));

        assertThat(result.getRejectedRows()).hasSize(1);
        assertThat(result.getRejectedRows().getFirst().getReason()).contains("high_price");
        verify(mapper, never()).insert(any(StockDailyQuote.class));
        verify(mapper, never()).updateById(any(StockDailyQuote.class));
    }

    @Test
    void upsertDailyQuoteRetriesAsUpdateWhenConcurrentInsertWins() {
        StockDailyQuoteMapper mapper = mock(StockDailyQuoteMapper.class);
        StockDailyQuote existing = StockDailyQuote.builder()
                .id(100L)
                .symbol("000001.SZ")
                .tradeDate(LocalDate.of(2026, 6, 20))
                .build();
        when(mapper.selectOne(any())).thenReturn(null, existing);
        when(mapper.insert(any(StockDailyQuote.class))).thenThrow(new DuplicateKeyException("duplicate quote"));
        StockDailyQuoteServiceImpl service = new StockDailyQuoteServiceImpl(mapper);

        var result = service.upsertDailyQuotes(List.of(validQuote("sz000001", "10.50")));

        assertThat(result.getInsertedRows()).isZero();
        assertThat(result.getUpdatedRows()).isEqualTo(1);
        verify(mapper).updateById(any(StockDailyQuote.class));
    }

    private StockDailyQuoteUpsertDto validQuote(String symbol, String closePrice) {
        StockDailyQuoteUpsertDto dto = new StockDailyQuoteUpsertDto();
        dto.setSymbol(symbol);
        dto.setTradeDate(LocalDate.of(2026, 6, 20));
        dto.setOpenPrice(new BigDecimal("10.00"));
        dto.setHighPrice(new BigDecimal("10.80"));
        dto.setLowPrice(new BigDecimal("9.90"));
        dto.setClosePrice(new BigDecimal(closePrice));
        dto.setVolume(new BigDecimal("100000"));
        dto.setAmount(new BigDecimal("1050000"));
        dto.setChangePct(new BigDecimal("1.20"));
        return dto;
    }
}
