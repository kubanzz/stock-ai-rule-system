package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import com.jx.tracker.service.StockWatchlistService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StockWatchlistServiceImpl implements StockWatchlistService {

    private static final String ALL_POOL_CODE = "all";
    private static final String DEFAULT_POOL_CODE = "my-follow";
    private static final String DEFAULT_MARKET = "A股";

    private final StockWatchlistMapper watchlistMapper;
    private final StockWatchlistItemMapper itemMapper;
    private final StockBaseMapper stockBaseMapper;

    public StockWatchlistServiceImpl(StockWatchlistMapper watchlistMapper,
                                     StockWatchlistItemMapper itemMapper,
                                     StockBaseMapper stockBaseMapper) {
        this.watchlistMapper = watchlistMapper;
        this.itemMapper = itemMapper;
        this.stockBaseMapper = stockBaseMapper;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<StockConsoleVo.WatchlistPool> list(String market) {
        String targetMarket = StringUtils.hasText(market) ? market.trim() : DEFAULT_MARKET;
        ensureDefaultPool(targetMarket);

        List<StockWatchlist> watchlists = safeList(watchlistMapper.selectList(
                Wrappers.<StockWatchlist>lambdaQuery()
                        .eq(StockWatchlist::getMarket, targetMarket)
                        .orderByAsc(StockWatchlist::getSortOrder)
                        .orderByAsc(StockWatchlist::getId)
        ));
        List<StockConsoleVo.WatchlistPool> pools = new ArrayList<>(watchlists.size() + 1);
        for (StockWatchlist watchlist : watchlists) {
            pools.add(toPool(watchlist));
        }
        pools.add(allPool(targetMarket));
        return List.copyOf(pools);
    }

    @Override
    @Transactional
    public StockConsoleVo.WatchlistPool create(StockConsoleVo.WatchlistMutationRequest request) {
        ValidatedPoolMutation mutation = validatePoolMutation(request);
        StockWatchlist watchlist = StockWatchlist.builder()
                .poolCode(generatePoolCode())
                .poolName(mutation.poolName())
                .market(mutation.market())
                .sortOrder(100)
                .isSystem(false)
                .build();
        watchlistMapper.insert(watchlist);
        return emptyPool(watchlist);
    }

    @Override
    @Transactional
    public StockConsoleVo.WatchlistPool update(String poolCode,
                                               StockConsoleVo.WatchlistMutationRequest request) {
        String normalizedPoolCode = normalizePoolCode(poolCode);
        if (ALL_POOL_CODE.equals(normalizedPoolCode)) {
            throw new ServiceException("全量股票池不可更新");
        }
        ValidatedPoolMutation mutation = validatePoolMutation(request);
        StockWatchlist watchlist = requirePool(normalizedPoolCode);
        watchlist.setPoolName(mutation.poolName());
        watchlist.setMarket(mutation.market());
        watchlistMapper.updateById(watchlist);
        return toPool(watchlist);
    }

    @Override
    @Transactional
    public void delete(String poolCode) {
        String normalizedPoolCode = normalizePoolCode(poolCode);
        if (DEFAULT_POOL_CODE.equals(normalizedPoolCode) || ALL_POOL_CODE.equals(normalizedPoolCode)) {
            throw new ServiceException("系统股票池不可删除：" + normalizedPoolCode);
        }
        StockWatchlist watchlist = requirePool(normalizedPoolCode);
        watchlistMapper.deleteById(watchlist.getId());
    }

    @Override
    @Transactional
    public StockConsoleVo.WatchlistPool addStock(
            String poolCode,
            StockConsoleVo.WatchlistStockMutationRequest request
    ) {
        String normalizedPoolCode = rejectVirtualPool(poolCode, "添加股票");
        StockWatchlist watchlist = requirePool(normalizedPoolCode);
        String symbol = requireSymbol(request == null ? null : request.symbol());
        StockBase stock = stockBaseMapper.selectOne(Wrappers.<StockBase>lambdaQuery()
                .eq(StockBase::getSymbol, symbol));
        if (stock == null) {
            throw new ServiceException("股票不存在：" + symbol);
        }
        if (!watchlist.getMarket().equals(stock.getMarket())) {
            throw new ServiceException("股票市场与股票池市场不一致：" + symbol);
        }

        StockWatchlistItem existing = itemMapper.selectOne(Wrappers.<StockWatchlistItem>lambdaQuery()
                .eq(StockWatchlistItem::getWatchlistId, watchlist.getId())
                .eq(StockWatchlistItem::getSymbol, symbol));
        if (existing != null) {
            throw new ServiceException("股票已在股票池中：" + symbol);
        }

        StockWatchlistItem item = StockWatchlistItem.builder()
                .watchlistId(watchlist.getId())
                .symbol(symbol)
                .groupName(normalizeNullable(request.groupName()))
                .sortOrder(0)
                .build();
        try {
            itemMapper.insert(item);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("股票已在股票池中：" + symbol, exception);
        }
        return toPool(watchlist);
    }

    @Override
    @Transactional
    public StockConsoleVo.WatchlistPool removeStock(String poolCode, String symbol) {
        String normalizedPoolCode = rejectVirtualPool(poolCode, "移除股票");
        StockWatchlist watchlist = requirePool(normalizedPoolCode);
        String normalizedSymbol = requireSymbol(symbol);
        itemMapper.delete(Wrappers.<StockWatchlistItem>lambdaQuery()
                .eq(StockWatchlistItem::getWatchlistId, watchlist.getId())
                .eq(StockWatchlistItem::getSymbol, normalizedSymbol));
        return toPool(watchlist);
    }

    private void ensureDefaultPool(String market) {
        StockWatchlist existing = watchlistMapper.selectOne(Wrappers.<StockWatchlist>lambdaQuery()
                .eq(StockWatchlist::getPoolCode, DEFAULT_POOL_CODE));
        if (existing != null) {
            return;
        }
        try {
            watchlistMapper.insert(StockWatchlist.builder()
                    .poolCode(DEFAULT_POOL_CODE)
                    .poolName("我的关注")
                    .market(market)
                    .sortOrder(0)
                    .isSystem(true)
                    .build());
        } catch (DuplicateKeyException exception) {
            if (findPool(DEFAULT_POOL_CODE) == null) {
                throw exception;
            }
        }
    }

    private StockWatchlist requirePool(String poolCode) {
        String normalizedPoolCode = normalizePoolCode(poolCode);
        StockWatchlist watchlist = findPool(normalizedPoolCode);
        if (watchlist == null) {
            throw new ServiceException("股票池不存在：" + normalizedPoolCode);
        }
        return watchlist;
    }

    private StockWatchlist findPool(String poolCode) {
        return watchlistMapper.selectOne(Wrappers.<StockWatchlist>lambdaQuery()
                .eq(StockWatchlist::getPoolCode, poolCode));
    }

    private StockConsoleVo.WatchlistPool toPool(StockWatchlist watchlist) {
        List<StockWatchlistItem> items = safeList(itemMapper.selectList(
                Wrappers.<StockWatchlistItem>lambdaQuery()
                        .eq(StockWatchlistItem::getWatchlistId, watchlist.getId())
                        .orderByAsc(StockWatchlistItem::getSortOrder)
                        .orderByAsc(StockWatchlistItem::getId)
        ));
        if (items.isEmpty()) {
            return emptyPool(watchlist);
        }

        List<String> symbols = items.stream().map(StockWatchlistItem::getSymbol).toList();
        List<StockBase> stocks = safeList(stockBaseMapper.selectList(Wrappers.<StockBase>lambdaQuery()
                .in(StockBase::getSymbol, symbols)));
        Map<String, StockBase> stocksBySymbol = new HashMap<>();
        for (StockBase stock : stocks) {
            stocksBySymbol.put(stock.getSymbol(), stock);
        }

        List<StockConsoleVo.WatchlistStock> rows = items.stream()
                .filter(item -> stocksBySymbol.containsKey(item.getSymbol()))
                .map(item -> toStock(stocksBySymbol.get(item.getSymbol()), item.getGroupName(), true))
                .toList();
        return new StockConsoleVo.WatchlistPool(
                watchlist.getPoolCode(),
                watchlist.getPoolName(),
                watchlist.getMarket(),
                rows.size(),
                rows
        );
    }

    private StockConsoleVo.WatchlistPool allPool(String market) {
        List<StockConsoleVo.WatchlistStock> rows = safeList(stockBaseMapper.selectList(
                        Wrappers.<StockBase>lambdaQuery()
                                .eq(StockBase::getMarket, market)
                                .orderByAsc(StockBase::getSymbol)
                )).stream()
                .map(stock -> toStock(stock, null, false))
                .toList();
        return new StockConsoleVo.WatchlistPool(ALL_POOL_CODE, "股票池", market, rows.size(), rows);
    }

    private StockConsoleVo.WatchlistStock toStock(StockBase stock, String groupName, boolean selected) {
        return new StockConsoleVo.WatchlistStock(
                stock.getSymbol(),
                stock.getName(),
                stock.getMarket(),
                stock.getIndustry(),
                groupName,
                selected
        );
    }

    private StockConsoleVo.WatchlistPool emptyPool(StockWatchlist watchlist) {
        return new StockConsoleVo.WatchlistPool(
                watchlist.getPoolCode(),
                watchlist.getPoolName(),
                watchlist.getMarket(),
                0,
                List.of()
        );
    }

    private ValidatedPoolMutation validatePoolMutation(StockConsoleVo.WatchlistMutationRequest request) {
        if (request == null || !StringUtils.hasText(request.poolName())) {
            throw new ServiceException("股票池名称不能为空");
        }
        if (!StringUtils.hasText(request.market())) {
            throw new ServiceException("股票池市场不能为空");
        }
        return new ValidatedPoolMutation(request.poolName().trim(), request.market().trim());
    }

    private String requireSymbol(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            throw new ServiceException("股票代码不能为空");
        }
        return symbol.trim();
    }

    private String rejectVirtualPool(String poolCode, String operation) {
        String normalizedPoolCode = normalizePoolCode(poolCode);
        if (ALL_POOL_CODE.equals(normalizedPoolCode)) {
            throw new ServiceException("全量股票池不可" + operation);
        }
        return normalizedPoolCode;
    }

    private String normalizePoolCode(String poolCode) {
        if (!StringUtils.hasText(poolCode)) {
            throw new ServiceException("股票池编码不能为空");
        }
        return poolCode.trim();
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String generatePoolCode() {
        return "custom-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record ValidatedPoolMutation(String poolName, String market) {
    }
}
