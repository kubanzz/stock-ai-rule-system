package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import com.jx.tracker.market.data.util.MarketCodeNormalizer;
import com.jx.tracker.market.data.util.SymbolNormalizer;
import com.jx.tracker.service.StockWatchlistService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;

@Service
public class StockWatchlistServiceImpl implements StockWatchlistService {

    private static final String ALL_POOL_CODE = "all";
    private static final String DEFAULT_POOL_CODE = "my-follow";
    private static final String DEFAULT_MARKET = "A股";
    private static final int MAX_POOL_NAME_LENGTH = 128;
    private static final int MAX_MARKET_LENGTH = 32;
    private static final int MAX_SYMBOL_LENGTH = 32;
    private static final int MAX_GROUP_NAME_LENGTH = 64;

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
        String targetMarket = MarketCodeNormalizer.toDisplayName(
                StringUtils.hasText(market) ? market : DEFAULT_MARKET
        );
        ensureDefaultPool();

        List<StockWatchlist> watchlists = safeList(watchlistMapper.selectList(
                Wrappers.<StockWatchlist>lambdaQuery()
                        .in(StockWatchlist::getMarket, MarketCodeNormalizer.aliases(targetMarket))
                        .orderByAsc(StockWatchlist::getSortOrder)
                        .orderByAsc(StockWatchlist::getId)
        ));
        List<StockBase> stocks = safeList(stockBaseMapper.selectList(
                Wrappers.<StockBase>lambdaQuery()
                        .in(StockBase::getMarket, MarketCodeNormalizer.aliases(targetMarket))
                        .orderByAsc(StockBase::getSymbol)
        ));
        Map<String, StockBase> stocksBySymbol = indexStocks(stocks);
        Map<Long, List<StockWatchlistItem>> itemsByWatchlistId = loadItemsByWatchlistId(watchlists);

        List<StockConsoleVo.WatchlistPool> pools = new ArrayList<>(watchlists.size() + 1);
        for (StockWatchlist watchlist : watchlists) {
            pools.add(toPool(
                    watchlist,
                    itemsByWatchlistId.getOrDefault(watchlist.getId(), List.of()),
                    stocksBySymbol
            ));
        }
        pools.add(allPool(targetMarket, stocks));
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
        StockWatchlist watchlist = requirePoolForUpdate(normalizedPoolCode);
        if (!MarketCodeNormalizer.equivalent(watchlist.getMarket(), mutation.market())
                && itemMapper.selectCount(Wrappers.<StockWatchlistItem>lambdaQuery()
                .eq(StockWatchlistItem::getWatchlistId, watchlist.getId())) > 0) {
            throw new ServiceException("非空股票池不可修改市场");
        }
        watchlist.setPoolName(mutation.poolName());
        watchlist.setMarket(mutation.market());
        requireAffectedRow(watchlistMapper.updateById(watchlist));
        return toPool(watchlist);
    }

    @Override
    @Transactional
    public void delete(String poolCode) {
        String normalizedPoolCode = normalizePoolCode(poolCode);
        if (ALL_POOL_CODE.equals(normalizedPoolCode)) {
            throw new ServiceException("系统股票池不可删除：" + normalizedPoolCode, 400);
        }
        StockWatchlist watchlist = requirePoolForUpdate(normalizedPoolCode);
        if (Boolean.TRUE.equals(watchlist.getIsSystem())) {
            throw new ServiceException("系统股票池不可删除：" + normalizedPoolCode, 400);
        }
        requireAffectedRow(watchlistMapper.deleteById(watchlist.getId()));
    }

    @Override
    @Transactional
    public StockConsoleVo.WatchlistPool addStock(
            String poolCode,
            StockConsoleVo.WatchlistStockMutationRequest request
    ) {
        String normalizedPoolCode = rejectVirtualPool(poolCode, "添加股票");
        StockWatchlist watchlist = requirePoolForUpdate(normalizedPoolCode);
        String symbol = requireSymbol(request == null ? null : request.symbol());
        String groupName = requireGroupName(request.groupName());
        StockBase stock = stockBaseMapper.selectOne(Wrappers.<StockBase>lambdaQuery()
                .eq(StockBase::getSymbol, symbol));
        if (stock == null) {
            throw new ServiceException("股票不存在：" + symbol);
        }
        if (!MarketCodeNormalizer.equivalent(watchlist.getMarket(), stock.getMarket())) {
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
                .groupName(groupName)
                .sortOrder(0)
                .build();
        try {
            requireAffectedRow(itemMapper.insert(item));
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("股票已在股票池中：" + symbol, exception);
        }
        return toPool(watchlist);
    }

    @Override
    public PageResult<StockConsoleVo.WatchlistCandidate> searchCandidates(
            String poolCode,
            String market,
            String keyword,
            int pageNum,
            int pageSize) {
        String normalizedPoolCode = rejectVirtualPool(poolCode, "搜索候选股票");
        StockWatchlist watchlist = findPool(normalizedPoolCode);
        if (watchlist == null) {
            throw new ServiceException("股票池不存在：" + normalizedPoolCode);
        }
        String targetMarket = MarketCodeNormalizer.toDisplayName(
                StringUtils.hasText(market) ? market : watchlist.getMarket());
        if (!MarketCodeNormalizer.equivalent(watchlist.getMarket(), targetMarket)) {
            throw new ServiceException("股票池市场与搜索市场不一致");
        }
        String search = normalizeNullable(keyword);
        String normalizedSearch = StringUtils.hasText(search) && search.matches("\\d{6}")
                ? SymbolNormalizer.normalize(search)
                : null;
        var query = Wrappers.<StockBase>lambdaQuery()
                .in(StockBase::getMarket, MarketCodeNormalizer.aliases(targetMarket))
                .and(StringUtils.hasText(search), wrapper -> {
                    if (normalizedSearch != null) {
                        wrapper.eq(StockBase::getSymbol, normalizedSearch);
                    } else {
                        wrapper.like(StockBase::getSymbol, search)
                                .or()
                                .like(StockBase::getName, search);
                    }
                })
                .orderByAsc(StockBase::getSymbol);
        long safePageNum = pageNum < 1 ? 1L : pageNum;
        long safePageSize = pageSize < 1 ? 20L : Math.min(pageSize, 100);
        Page<StockBase> page = stockBaseMapper.selectPage(new Page<>(safePageNum, safePageSize), query);
        List<String> pageSymbols = page.getRecords().stream()
                .map(StockBase::getSymbol)
                .map(SymbolNormalizer::normalize)
                .toList();
        Set<String> existingSymbols = pageSymbols.isEmpty()
                ? Set.of()
                : safeList(itemMapper.selectList(Wrappers.<StockWatchlistItem>lambdaQuery()
                .eq(StockWatchlistItem::getWatchlistId, watchlist.getId())
                .in(StockWatchlistItem::getSymbol, pageSymbols))).stream()
                .map(StockWatchlistItem::getSymbol)
                .map(SymbolNormalizer::normalize)
                .collect(java.util.stream.Collectors.toSet());
        List<StockConsoleVo.WatchlistCandidate> rows = page.getRecords().stream()
                .map(stock -> new StockConsoleVo.WatchlistCandidate(
                        SymbolNormalizer.normalize(stock.getSymbol()),
                        stock.getName(),
                        MarketCodeNormalizer.toDisplayName(stock.getMarket()),
                        stock.getExchange(),
                        stock.getIndustry(),
                        existingSymbols.contains(SymbolNormalizer.normalize(stock.getSymbol()))
                ))
                .toList();
        return PageResult.getDataTable(rows, page.getTotal());
    }

    @Override
    @Transactional
    public StockConsoleVo.WatchlistBatchMutationResult addStocks(
            String poolCode,
            StockConsoleVo.WatchlistBatchMutationRequest request) {
        String normalizedPoolCode = rejectVirtualPool(poolCode, "添加股票");
        StockWatchlist watchlist = requirePoolForUpdate(normalizedPoolCode);
        if (request == null || request.symbols() == null || request.symbols().isEmpty()) {
            throw new ServiceException("请选择至少一只股票");
        }
        if (request.symbols().size() > 100) {
            throw new ServiceException("每次最多添加100只股票");
        }
        String groupName = requireGroupName(request.groupName());
        List<String> symbols = new ArrayList<>(new LinkedHashSet<>(request.symbols().stream()
                .map(this::requireSymbol)
                .toList()));
        Map<String, StockBase> stocksBySymbol = safeList(stockBaseMapper.selectList(
                Wrappers.<StockBase>lambdaQuery().in(StockBase::getSymbol, symbols)))
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        stock -> SymbolNormalizer.normalize(stock.getSymbol()),
                        stock -> stock,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        Set<String> existingSymbols = safeList(itemMapper.selectList(
                Wrappers.<StockWatchlistItem>lambdaQuery()
                        .eq(StockWatchlistItem::getWatchlistId, watchlist.getId())
                        .in(StockWatchlistItem::getSymbol, symbols)))
                .stream()
                .map(StockWatchlistItem::getSymbol)
                .map(SymbolNormalizer::normalize)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> added = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String symbol : symbols) {
            StockBase stock = stocksBySymbol.get(symbol);
            if (stock == null || !MarketCodeNormalizer.equivalent(watchlist.getMarket(), stock.getMarket())) {
                failed.add(symbol);
                continue;
            }
            if (existingSymbols.contains(symbol)) {
                skipped.add(symbol);
                continue;
            }
            StockWatchlistItem item = StockWatchlistItem.builder()
                    .watchlistId(watchlist.getId())
                    .symbol(symbol)
                    .groupName(groupName)
                    .sortOrder(0)
                    .build();
            try {
                requireAffectedRow(itemMapper.insert(item));
                added.add(symbol);
            } catch (DuplicateKeyException exception) {
                skipped.add(symbol);
            }
        }
        return new StockConsoleVo.WatchlistBatchMutationResult(
                normalizedPoolCode,
                List.copyOf(added),
                List.copyOf(skipped),
                List.copyOf(failed)
        );
    }

    @Override
    @Transactional
    public StockConsoleVo.WatchlistPool removeStock(String poolCode, String symbol) {
        String normalizedPoolCode = rejectVirtualPool(poolCode, "移除股票");
        StockWatchlist watchlist = requirePoolForUpdate(normalizedPoolCode);
        String normalizedSymbol = requireSymbol(symbol);
        requireAffectedRow(itemMapper.delete(Wrappers.<StockWatchlistItem>lambdaQuery()
                .eq(StockWatchlistItem::getWatchlistId, watchlist.getId())
                .eq(StockWatchlistItem::getSymbol, normalizedSymbol)));
        return toPool(watchlist);
    }

    private void ensureDefaultPool() {
        StockWatchlist existing = watchlistMapper.selectOne(Wrappers.<StockWatchlist>lambdaQuery()
                .eq(StockWatchlist::getPoolCode, DEFAULT_POOL_CODE));
        if (existing != null) {
            return;
        }
        try {
            watchlistMapper.insert(StockWatchlist.builder()
                    .poolCode(DEFAULT_POOL_CODE)
                    .poolName("我的关注")
                    .market(DEFAULT_MARKET)
                    .sortOrder(0)
                    .isSystem(true)
                    .build());
        } catch (DuplicateKeyException exception) {
            if (findPool(DEFAULT_POOL_CODE) == null) {
                throw exception;
            }
        }
    }

    private StockWatchlist requirePoolForUpdate(String poolCode) {
        String normalizedPoolCode = normalizePoolCode(poolCode);
        StockWatchlist watchlist = watchlistMapper.selectOne(Wrappers.<StockWatchlist>lambdaQuery()
                .eq(StockWatchlist::getPoolCode, normalizedPoolCode)
                .last("FOR UPDATE"));
        if (watchlist == null) {
            throw new ServiceException("股票池不存在：" + normalizedPoolCode);
        }
        return watchlist;
    }

    private StockWatchlist findPool(String poolCode) {
        String normalizedPoolCode = normalizePoolCode(poolCode);
        return watchlistMapper.selectOne(Wrappers.<StockWatchlist>lambdaQuery()
                .eq(StockWatchlist::getPoolCode, normalizedPoolCode));
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

        List<String> symbols = items.stream()
                .map(StockWatchlistItem::getSymbol)
                .map(SymbolNormalizer::normalize)
                .toList();
        List<StockBase> stocks = safeList(stockBaseMapper.selectList(Wrappers.<StockBase>lambdaQuery()
                .in(StockBase::getSymbol, symbols)));
        Map<String, StockBase> stocksBySymbol = indexStocks(stocks);

        return toPool(watchlist, items, stocksBySymbol);
    }

    private StockConsoleVo.WatchlistPool toPool(StockWatchlist watchlist,
                                                 List<StockWatchlistItem> items,
                                                 Map<String, StockBase> stocksBySymbol) {
        if (items.isEmpty()) {
            return emptyPool(watchlist);
        }

        List<StockConsoleVo.WatchlistStock> rows = items.stream()
                .filter(item -> stocksBySymbol.containsKey(SymbolNormalizer.normalize(item.getSymbol())))
                .map(item -> toStock(
                        stocksBySymbol.get(SymbolNormalizer.normalize(item.getSymbol())),
                        item.getGroupName(),
                        true
                ))
                .toList();
        return new StockConsoleVo.WatchlistPool(
                watchlist.getPoolCode(),
                watchlist.getPoolName(),
                MarketCodeNormalizer.toDisplayName(watchlist.getMarket()),
                rows.size(),
                rows
        );
    }

    private StockConsoleVo.WatchlistPool allPool(String market, List<StockBase> stocks) {
        List<StockConsoleVo.WatchlistStock> rows = stocks.stream()
                .map(stock -> toStock(stock, null, false))
                .toList();
        return new StockConsoleVo.WatchlistPool(ALL_POOL_CODE, "股票池", market, rows.size(), rows);
    }

    private StockConsoleVo.WatchlistStock toStock(StockBase stock, String groupName, boolean selected) {
        return new StockConsoleVo.WatchlistStock(
                SymbolNormalizer.normalize(stock.getSymbol()),
                stock.getName(),
                MarketCodeNormalizer.toDisplayName(stock.getMarket()),
                stock.getIndustry(),
                groupName,
                selected
        );
    }

    private StockConsoleVo.WatchlistPool emptyPool(StockWatchlist watchlist) {
        return new StockConsoleVo.WatchlistPool(
                watchlist.getPoolCode(),
                watchlist.getPoolName(),
                MarketCodeNormalizer.toDisplayName(watchlist.getMarket()),
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
        String poolName = request.poolName().trim();
        String market = MarketCodeNormalizer.toDisplayName(request.market());
        if (poolName.length() > MAX_POOL_NAME_LENGTH) {
            throw new ServiceException("股票池名称不能超过128个字符");
        }
        if (market.length() > MAX_MARKET_LENGTH) {
            throw new ServiceException("股票池市场不能超过32个字符");
        }
        return new ValidatedPoolMutation(poolName, market);
    }

    private String requireSymbol(String symbol) {
        String normalized = SymbolNormalizer.normalize(symbol);
        if (!StringUtils.hasText(normalized)) {
            throw new ServiceException("股票代码不能为空");
        }
        if (normalized.length() > MAX_SYMBOL_LENGTH) {
            throw new ServiceException("股票代码不能超过32个字符");
        }
        return normalized;
    }

    private String requireGroupName(String groupName) {
        String normalized = normalizeNullable(groupName);
        if (normalized != null && normalized.length() > MAX_GROUP_NAME_LENGTH) {
            throw new ServiceException("股票分组名称不能超过64个字符");
        }
        return normalized;
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
        return poolCode.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String generatePoolCode() {
        return "custom-" + UUID.randomUUID().toString().replace("-", "");
    }

    private Map<Long, List<StockWatchlistItem>> loadItemsByWatchlistId(List<StockWatchlist> watchlists) {
        if (watchlists.isEmpty()) {
            return Map.of();
        }
        List<Long> watchlistIds = watchlists.stream().map(StockWatchlist::getId).toList();
        List<StockWatchlistItem> items = safeList(itemMapper.selectList(
                Wrappers.<StockWatchlistItem>lambdaQuery()
                        .in(StockWatchlistItem::getWatchlistId, watchlistIds)
                        .orderByAsc(StockWatchlistItem::getSortOrder)
                        .orderByAsc(StockWatchlistItem::getId)
        ));
        Map<Long, List<StockWatchlistItem>> result = new HashMap<>();
        for (StockWatchlistItem item : items) {
            result.computeIfAbsent(item.getWatchlistId(), ignored -> new ArrayList<>()).add(item);
        }
        return result;
    }

    private Map<String, StockBase> indexStocks(List<StockBase> stocks) {
        Map<String, StockBase> result = new HashMap<>();
        for (StockBase stock : stocks) {
            result.put(SymbolNormalizer.normalize(stock.getSymbol()), stock);
        }
        return result;
    }

    private void requireAffectedRow(int affectedRows) {
        if (affectedRows == 0) {
            throw staleState(null);
        }
    }

    private ServiceException staleState(Throwable cause) {
        return new ServiceException("股票池状态已变化，请刷新后重试", cause);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record ValidatedPoolMutation(String poolName, String market) {
    }
}
