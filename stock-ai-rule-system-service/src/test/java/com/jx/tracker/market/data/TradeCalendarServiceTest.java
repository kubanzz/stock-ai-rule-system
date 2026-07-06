package com.jx.tracker.market.data;

import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.mapper.TradeCalendarMapper;
import com.jx.tracker.market.data.dto.TradeCalendarDto;
import com.jx.tracker.market.data.service.impl.TradeCalendarServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeCalendarServiceTest {

    @Test
    void upsertTradeCalendarInsertsNewMarketDateAndNormalizesMarket() {
        TradeCalendarMapper mapper = mock(TradeCalendarMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        TradeCalendarServiceImpl service = new TradeCalendarServiceImpl(mapper);

        var result = service.upsertTradeCalendars(List.of(calendar("sz", LocalDate.of(2026, 7, 6))));

        assertThat(result.getTotalRows()).isEqualTo(1);
        assertThat(result.getInsertedRows()).isEqualTo(1);
        assertThat(result.getUpdatedRows()).isZero();
        verify(mapper).insert(any(TradeCalendar.class));
        verify(mapper, never()).updateById(any(TradeCalendar.class));
    }

    @Test
    void upsertTradeCalendarUpdatesExistingMarketDate() {
        TradeCalendarMapper mapper = mock(TradeCalendarMapper.class);
        when(mapper.selectOne(any())).thenReturn(TradeCalendar.builder()
                .id(10L)
                .market("CN")
                .tradeDate(LocalDate.of(2026, 7, 6))
                .build());
        TradeCalendarServiceImpl service = new TradeCalendarServiceImpl(mapper);

        var result = service.upsertTradeCalendars(List.of(calendar("CN", LocalDate.of(2026, 7, 6))));

        assertThat(result.getInsertedRows()).isZero();
        assertThat(result.getUpdatedRows()).isEqualTo(1);
        verify(mapper, never()).insert(any(TradeCalendar.class));
        verify(mapper).updateById(any(TradeCalendar.class));
    }

    @Test
    void upsertTradeCalendarRetriesAsUpdateWhenConcurrentInsertWins() {
        TradeCalendarMapper mapper = mock(TradeCalendarMapper.class);
        TradeCalendar existing = TradeCalendar.builder()
                .id(10L)
                .market("CN")
                .tradeDate(LocalDate.of(2026, 7, 6))
                .build();
        when(mapper.selectOne(any())).thenReturn(null, existing);
        when(mapper.insert(any(TradeCalendar.class))).thenThrow(new DuplicateKeyException("duplicate calendar"));
        TradeCalendarServiceImpl service = new TradeCalendarServiceImpl(mapper);

        var result = service.upsertTradeCalendars(List.of(calendar("CN", LocalDate.of(2026, 7, 6))));

        assertThat(result.getInsertedRows()).isZero();
        assertThat(result.getUpdatedRows()).isEqualTo(1);
        verify(mapper).updateById(any(TradeCalendar.class));
    }

    private TradeCalendarDto calendar(String market, LocalDate date) {
        TradeCalendarDto dto = new TradeCalendarDto();
        dto.setMarket(market);
        dto.setTradeDate(date);
        dto.setOpen(true);
        dto.setPreTradeDate(date.minusDays(1));
        dto.setNextTradeDate(date.plusDays(1));
        dto.setDataSource("mock");
        return dto;
    }
}
