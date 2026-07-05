package com.jx.tracker.market.data.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.mapper.TradeCalendarMapper;
import com.jx.tracker.market.data.dto.MarketDataImportResultDto;
import com.jx.tracker.market.data.dto.TradeCalendarDto;
import com.jx.tracker.market.data.dto.TradeCalendarQueryDto;
import com.jx.tracker.market.data.service.TradeCalendarService;
import com.jx.tracker.market.data.util.MarketDataNormalizer;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;

@Service
public class TradeCalendarServiceImpl extends ServiceImpl<TradeCalendarMapper, TradeCalendar> implements TradeCalendarService {

    private final TradeCalendarMapper tradeCalendarMapper;

    public TradeCalendarServiceImpl(TradeCalendarMapper tradeCalendarMapper) {
        this.tradeCalendarMapper = tradeCalendarMapper;
    }

    @Override
    public MarketDataImportResultDto<TradeCalendarDto> upsertTradeCalendars(Collection<TradeCalendarDto> rows) {
        MarketDataImportResultDto<TradeCalendarDto> result = new MarketDataImportResultDto<>();
        if (rows == null) {
            return result;
        }
        int rowNumber = 0;
        for (TradeCalendarDto row : rows) {
            rowNumber++;
            String error = MarketDataNormalizer.validate(row);
            if (error != null) {
                result.reject(rowNumber, null, error);
                continue;
            }
            TradeCalendar entity = toEntity(row);
            TradeCalendar existing = getByMarketAndTradeDate(row.getMarket(), row.getTradeDate());
            result.accept(row);
            if (existing == null) {
                tradeCalendarMapper.insert(entity);
                result.markInserted();
            } else {
                entity.setId(existing.getId());
                tradeCalendarMapper.updateById(entity);
                result.markUpdated();
            }
        }
        return result;
    }

    @Override
    public PageResult<TradeCalendar> pageTradeCalendars(TradeCalendarQueryDto query) {
        TradeCalendarQueryDto safeQuery = query == null ? new TradeCalendarQueryDto() : query;
        String market = MarketDataNormalizer.normalizeMarket(safeQuery.getMarket());
        LambdaQueryWrapper<TradeCalendar> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(market != null, TradeCalendar::getMarket, market)
                .eq(safeQuery.getOpen() != null, TradeCalendar::getOpen, safeQuery.getOpen())
                .ge(safeQuery.getStartDate() != null, TradeCalendar::getTradeDate, safeQuery.getStartDate())
                .le(safeQuery.getEndDate() != null, TradeCalendar::getTradeDate, safeQuery.getEndDate())
                .orderByDesc(TradeCalendar::getTradeDate)
                .orderByAsc(TradeCalendar::getMarket);
        Page<TradeCalendar> page = tradeCalendarMapper.selectPage(new Page<>(pageNum(safeQuery.getPageNum()), pageSize(safeQuery.getPageSize())), wrapper);
        return PageResult.getDataTable(page.getRecords(), page.getTotal());
    }

    @Override
    public TradeCalendar getByMarketAndTradeDate(String market, LocalDate tradeDate) {
        String normalizedMarket = MarketDataNormalizer.normalizeMarket(market);
        if (normalizedMarket == null || tradeDate == null) {
            return null;
        }
        return tradeCalendarMapper.selectOne(new LambdaQueryWrapper<TradeCalendar>()
                .eq(TradeCalendar::getMarket, normalizedMarket)
                .eq(TradeCalendar::getTradeDate, tradeDate)
                .last("limit 1"));
    }

    private TradeCalendar toEntity(TradeCalendarDto dto) {
        return TradeCalendar.builder()
                .market(dto.getMarket())
                .tradeDate(dto.getTradeDate())
                .open(dto.isOpen())
                .preTradeDate(dto.getPreTradeDate())
                .nextTradeDate(dto.getNextTradeDate())
                .dataSource(dto.getDataSource())
                .syncTime(dto.getSyncTime())
                .build();
    }

    private long pageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    private long pageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 20L : pageSize;
    }
}
