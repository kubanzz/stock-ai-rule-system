package com.jx.tracker.risk.runtime;

import com.jx.tracker.risk.data.flow.FlowEventDataset;
import com.jx.tracker.risk.data.market.AshareRiskObjectCatalog;
import com.jx.tracker.risk.data.market.MarketDatasetCode;
import com.jx.tracker.risk.data.market.MarketRiskDataProvider;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.workflow.RiskCollectionTask;
import com.jx.tracker.risk.workflow.RiskCollectionScope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RiskWorkflowPlanner {

    private static final String FLOW_EVENT_PROVIDER = "flow-event";

    private final RiskUniverseReader universeReader;
    private final int collectionChunkSize;
    private final AshareRiskObjectCatalog objectCatalog = new AshareRiskObjectCatalog();

    public RiskWorkflowPlanner(RiskUniverseReader universeReader) {
        this(universeReader, RiskWarningProperties.DEFAULT_COLLECTION_CHUNK_SIZE);
    }

    public RiskWorkflowPlanner(RiskUniverseReader universeReader, int collectionChunkSize) {
        if (universeReader == null) {
            throw new IllegalArgumentException("universeReader must not be null");
        }
        if (collectionChunkSize < 21 || collectionChunkSize > 50) {
            throw new IllegalArgumentException("collectionChunkSize must be between 21 and 50");
        }
        this.universeReader = universeReader;
        this.collectionChunkSize = collectionChunkSize;
    }

    public RiskWorkflowPlan plan(List<String> requestedSymbols) {
        List<RiskObjectKey> activeStocks = activeStocks();
        List<RiskObjectKey> stocks = selectStocks(activeStocks, requestedSymbols);
        RiskObjectKey market = objectCatalog.market();
        List<RiskCollectionTask> tasks = new ArrayList<>();
        tasks.addAll(chunkedTasks(MarketRiskDataProvider.PROVIDER_CODE,
                MarketDatasetCode.CN_A_STOCK_MASTER.code(), stocks));
        tasks.addAll(chunkedTasks(MarketRiskDataProvider.PROVIDER_CODE,
                MarketDatasetCode.SW1_MEMBERSHIP.code(), stocks));
        tasks.addAll(marketAndStockTasks(MarketDatasetCode.MARKET_DAILY.code(), market, stocks));
        tasks.addAll(valuationTasks(market, stocks));
        tasks.add(task(MarketRiskDataProvider.PROVIDER_CODE,
                MarketDatasetCode.BREADTH.code(), List.of(market)));
        tasks.add(task(MarketRiskDataProvider.PROVIDER_CODE,
                MarketDatasetCode.CROSS_MARKET.code(), List.of(market)));
        tasks.add(task(FLOW_EVENT_PROVIDER,
                FlowEventDataset.MARGIN_FINANCING.code(), List.of(market)));
        tasks.add(task(FLOW_EVENT_PROVIDER,
                FlowEventDataset.ETF_FUND_FLOW.code(), List.of(market)));
        tasks.addAll(chunkedTasks(FLOW_EVENT_PROVIDER,
                FlowEventDataset.EARNINGS_FORECAST.code(), stocks));
        tasks.addAll(chunkedTasks(FLOW_EVENT_PROVIDER,
                FlowEventDataset.STOCK_ANNOUNCEMENT.code(), stocks));
        tasks.addAll(chunkedTasks(FLOW_EVENT_PROVIDER,
                FlowEventDataset.SHARE_UNLOCK.code(), stocks));
        tasks.addAll(chunkedTasks(FLOW_EVENT_PROVIDER,
                FlowEventDataset.SHARE_REDUCTION.code(), stocks));
        return new RiskWorkflowPlan(stocks, tasks, List.of(RiskHorizon.values()));
    }

    public RiskWorkflowPlan planMarket() {
        List<RiskObjectKey> activeStocks = activeStocks();
        RiskObjectKey market = objectCatalog.market();
        List<RiskCollectionTask> tasks = new ArrayList<>();
        tasks.addAll(chunkedTasks(MarketRiskDataProvider.PROVIDER_CODE,
                MarketDatasetCode.SW1_MEMBERSHIP.code(), activeStocks));
        tasks.add(task(MarketRiskDataProvider.PROVIDER_CODE,
                MarketDatasetCode.MARKET_DAILY.code(), List.of(market)));
        tasks.add(task(MarketRiskDataProvider.PROVIDER_CODE,
                MarketDatasetCode.VALUATION.code(), List.of(market)));
        tasks.add(task(MarketRiskDataProvider.PROVIDER_CODE,
                MarketDatasetCode.BREADTH.code(), List.of(market)));
        tasks.add(task(MarketRiskDataProvider.PROVIDER_CODE,
                MarketDatasetCode.CROSS_MARKET.code(), List.of(market)));
        tasks.add(task(FLOW_EVENT_PROVIDER,
                FlowEventDataset.MARGIN_FINANCING.code(), List.of(market)));
        tasks.add(task(FLOW_EVENT_PROVIDER,
                FlowEventDataset.ETF_FUND_FLOW.code(), List.of(market)));
        return new RiskWorkflowPlan(List.of(), tasks, List.of(RiskHorizon.values()));
    }

    public String normalizeStockSymbol(String symbol) {
        List<RiskObjectKey> selected = selectStocks(activeStocks(), List.of(symbol));
        return selected.getFirst().objectId();
    }

    private List<RiskObjectKey> activeStocks() {
        List<RiskObjectKey> activeStocks = normalize(universeReader.activeAshareSymbols());
        if (activeStocks.isEmpty()) {
            throw new IllegalStateException("active A-share universe must not be empty");
        }
        return activeStocks;
    }

    private List<RiskCollectionTask> marketAndStockTasks(
            String datasetCode,
            RiskObjectKey market,
            List<RiskObjectKey> stocks
    ) {
        if (stocks.size() + 1 <= collectionChunkSize) {
            List<RiskObjectKey> combined = new ArrayList<>();
            combined.add(market);
            combined.addAll(stocks);
            return List.of(task(MarketRiskDataProvider.PROVIDER_CODE, datasetCode, combined));
        }
        List<RiskCollectionTask> tasks = new ArrayList<>();
        tasks.add(task(MarketRiskDataProvider.PROVIDER_CODE, datasetCode, List.of(market)));
        tasks.addAll(chunkedTasks(MarketRiskDataProvider.PROVIDER_CODE, datasetCode, stocks));
        return List.copyOf(tasks);
    }

    private List<RiskCollectionTask> valuationTasks(
            RiskObjectKey market,
            List<RiskObjectKey> stocks
    ) {
        List<RiskCollectionTask> tasks = new ArrayList<>();
        tasks.add(task(MarketRiskDataProvider.PROVIDER_CODE,
                MarketDatasetCode.VALUATION.code(), List.of(market)));
        tasks.addAll(chunkedTasks(MarketRiskDataProvider.PROVIDER_CODE,
                MarketDatasetCode.VALUATION.code(), stocks));
        return List.copyOf(tasks);
    }

    private List<RiskCollectionTask> chunkedTasks(
            String providerCode,
            String datasetCode,
            List<RiskObjectKey> objects
    ) {
        List<RiskCollectionTask> tasks = new ArrayList<>();
        for (int offset = 0; offset < objects.size(); offset += collectionChunkSize) {
            int end = Math.min(offset + collectionChunkSize, objects.size());
            tasks.add(task(providerCode, datasetCode, objects.subList(offset, end)));
        }
        return List.copyOf(tasks);
    }

    private List<RiskObjectKey> selectStocks(
            List<RiskObjectKey> activeStocks,
            List<String> requestedSymbols
    ) {
        if (requestedSymbols == null || requestedSymbols.isEmpty()) {
            return activeStocks;
        }
        List<RiskObjectKey> requested = normalize(requestedSymbols);
        Set<RiskObjectKey> active = Set.copyOf(activeStocks);
        List<RiskObjectKey> unknown = requested.stream().filter(stock -> !active.contains(stock)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "requested symbols must belong to the active A-share universe: " + unknown
            );
        }
        return requested;
    }

    private List<RiskObjectKey> normalize(List<String> symbols) {
        if (symbols == null) {
            return List.of();
        }
        Set<RiskObjectKey> normalized = new LinkedHashSet<>();
        symbols.stream()
                .map(objectCatalog::stock)
                .sorted(java.util.Comparator.comparing(RiskObjectKey::objectId))
                .forEach(normalized::add);
        return List.copyOf(normalized);
    }

    private RiskCollectionTask task(
            String providerCode,
            String datasetCode,
            List<RiskObjectKey> objects
    ) {
        List<RiskObjectKey> immutableObjects = List.copyOf(objects);
        String scopeKey = RiskCollectionScope.key(immutableObjects);
        return new RiskCollectionTask(providerCode, datasetCode, scopeKey, immutableObjects);
    }
}
