package com.jx.tracker.market.data.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.market.data.dto.MarketDataImportResultDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteQueryDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;
import com.jx.tracker.market.data.service.StockDailyQuoteService;
import com.jx.tracker.market.data.util.MarketDataNormalizer;
import com.jx.tracker.market.data.util.SymbolNormalizer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;

@Service
public class StockDailyQuoteServiceImpl extends ServiceImpl<StockDailyQuoteMapper, StockDailyQuote> implements StockDailyQuoteService {

    private final StockDailyQuoteMapper stockDailyQuoteMapper;

    public StockDailyQuoteServiceImpl(StockDailyQuoteMapper stockDailyQuoteMapper) {
        this.stockDailyQuoteMapper = stockDailyQuoteMapper;
    }

    @Override
    public MarketDataImportResultDto<StockDailyQuoteUpsertDto> upsertDailyQuotes(Collection<StockDailyQuoteUpsertDto> rows) {
        MarketDataImportResultDto<StockDailyQuoteUpsertDto> result = new MarketDataImportResultDto<>();
        if (rows == null) {
            return result;
        }
        int rowNumber = 0;
        for (StockDailyQuoteUpsertDto row : rows) {
            rowNumber++;
            String error = MarketDataNormalizer.validate(row);
            if (error != null) {
                result.reject(rowNumber, row == null ? null : row.getSymbol(), error);
                continue;
            }
            StockDailyQuote entity = toEntity(row);
            StockDailyQuote existing = getBySymbolAndTradeDate(row.getSymbol(), row.getTradeDate());
            result.accept(row);
            if (existing == null) {
                insertOrUpdateAfterDuplicate(row, entity, result);
            } else {
                entity.setId(existing.getId());
                stockDailyQuoteMapper.updateById(entity);
                result.markUpdated();
            }
        }
        return result;
    }

    @Override
    public PageResult<StockDailyQuote> pageDailyQuotes(StockDailyQuoteQueryDto query) {
        StockDailyQuoteQueryDto safeQuery = query == null ? new StockDailyQuoteQueryDto() : query;
        String symbol = SymbolNormalizer.normalize(safeQuery.getSymbol());
        LambdaQueryWrapper<StockDailyQuote> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(symbol != null, StockDailyQuote::getSymbol, symbol)
                .ge(safeQuery.getStartDate() != null, StockDailyQuote::getTradeDate, safeQuery.getStartDate())
                .le(safeQuery.getEndDate() != null, StockDailyQuote::getTradeDate, safeQuery.getEndDate())
                .orderByDesc(StockDailyQuote::getTradeDate)
                .orderByAsc(StockDailyQuote::getSymbol);
        Page<StockDailyQuote> page = stockDailyQuoteMapper.selectPage(new Page<>(pageNum(safeQuery.getPageNum()), pageSize(safeQuery.getPageSize())), wrapper);
        return PageResult.getDataTable(page.getRecords(), page.getTotal());
    }

    @Override
    public StockDailyQuote getBySymbolAndTradeDate(String symbol, LocalDate tradeDate) {
        String normalizedSymbol = SymbolNormalizer.normalize(symbol);
        if (normalizedSymbol == null || tradeDate == null) {
            return null;
        }
        return stockDailyQuoteMapper.selectOne(new LambdaQueryWrapper<StockDailyQuote>()
                .eq(StockDailyQuote::getSymbol, normalizedSymbol)
                .eq(StockDailyQuote::getTradeDate, tradeDate)
                .last("limit 1"));
    }

    private void insertOrUpdateAfterDuplicate(
            StockDailyQuoteUpsertDto row,
            StockDailyQuote entity,
            MarketDataImportResultDto<StockDailyQuoteUpsertDto> result) {
        try {
            stockDailyQuoteMapper.insert(entity);
            result.markInserted();
        } catch (DuplicateKeyException ex) {
            StockDailyQuote existing = getBySymbolAndTradeDate(row.getSymbol(), row.getTradeDate());
            if (existing == null) {
                throw ex;
            }
            entity.setId(existing.getId());
            stockDailyQuoteMapper.updateById(entity);
            result.markUpdated();
        }
    }

    private StockDailyQuote toEntity(StockDailyQuoteUpsertDto dto) {
        return StockDailyQuote.builder()
                .symbol(dto.getSymbol())
                .tradeDate(dto.getTradeDate())
                .openPrice(dto.getOpenPrice())
                .highPrice(dto.getHighPrice())
                .lowPrice(dto.getLowPrice())
                .closePrice(dto.getClosePrice())
                .preClose(dto.getPreClose())
                .volume(dto.getVolume())
                .amount(dto.getAmount())
                .changePct(dto.getChangePct())
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
