package com.jx.tracker.market.data.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.market.data.dto.MarketDataImportResultDto;
import com.jx.tracker.market.data.dto.StockBaseQueryDto;
import com.jx.tracker.market.data.dto.StockBaseUpsertDto;
import com.jx.tracker.market.data.service.StockBaseService;
import com.jx.tracker.market.data.util.MarketDataNormalizer;
import org.springframework.stereotype.Service;

import java.util.Collection;

@Service
public class StockBaseServiceImpl extends ServiceImpl<StockBaseMapper, StockBase> implements StockBaseService {

    private final StockBaseMapper stockBaseMapper;

    public StockBaseServiceImpl(StockBaseMapper stockBaseMapper) {
        this.stockBaseMapper = stockBaseMapper;
    }

    @Override
    public MarketDataImportResultDto<StockBaseUpsertDto> upsertStockBases(Collection<StockBaseUpsertDto> rows) {
        MarketDataImportResultDto<StockBaseUpsertDto> result = new MarketDataImportResultDto<>();
        if (rows == null) {
            return result;
        }
        int rowNumber = 0;
        for (StockBaseUpsertDto row : rows) {
            rowNumber++;
            String error = MarketDataNormalizer.validate(row);
            if (error != null) {
                result.reject(rowNumber, row == null ? null : row.getSymbol(), error);
                continue;
            }
            StockBase entity = toEntity(row);
            StockBase existing = getBySymbolAndMarket(row.getSymbol(), row.getMarket());
            result.accept(row);
            if (existing == null) {
                stockBaseMapper.insert(entity);
                result.markInserted();
            } else {
                entity.setId(existing.getId());
                stockBaseMapper.updateById(entity);
                result.markUpdated();
            }
        }
        return result;
    }

    @Override
    public PageResult<StockBase> pageStocks(StockBaseQueryDto query) {
        StockBaseQueryDto safeQuery = query == null ? new StockBaseQueryDto() : query;
        LambdaQueryWrapper<StockBase> wrapper = new LambdaQueryWrapper<>();
        String symbol = MarketDataNormalizer.normalizeCode(safeQuery.getSymbol());
        String market = MarketDataNormalizer.normalizeCode(safeQuery.getMarket());
        wrapper.eq(symbol != null, StockBase::getSymbol, symbol)
                .eq(market != null, StockBase::getMarket, market)
                .eq(safeQuery.getStatus() != null && !safeQuery.getStatus().isBlank(), StockBase::getStatus, safeQuery.getStatus())
                .orderByAsc(StockBase::getSymbol);
        Page<StockBase> page = stockBaseMapper.selectPage(new Page<>(pageNum(safeQuery.getPageNum()), pageSize(safeQuery.getPageSize())), wrapper);
        return PageResult.getDataTable(page.getRecords(), page.getTotal());
    }

    @Override
    public StockBase getBySymbolAndMarket(String symbol, String market) {
        String normalizedSymbol = MarketDataNormalizer.normalizeCode(symbol);
        String normalizedMarket = MarketDataNormalizer.normalizeCode(market);
        if (normalizedSymbol == null || normalizedMarket == null) {
            return null;
        }
        return stockBaseMapper.selectOne(new LambdaQueryWrapper<StockBase>()
                .eq(StockBase::getSymbol, normalizedSymbol)
                .eq(StockBase::getMarket, normalizedMarket)
                .last("limit 1"));
    }

    private StockBase toEntity(StockBaseUpsertDto dto) {
        return StockBase.builder()
                .symbol(dto.getSymbol())
                .name(dto.getName())
                .market(dto.getMarket())
                .industry(dto.getIndustry())
                .status(dto.getStatus())
                .build();
    }

    private long pageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    private long pageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 20L : pageSize;
    }
}
