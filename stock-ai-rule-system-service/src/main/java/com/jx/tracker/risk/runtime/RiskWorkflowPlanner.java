package com.jx.tracker.risk.runtime;

import com.jx.tracker.risk.data.flow.FlowEventDataset;
import com.jx.tracker.risk.data.market.AshareRiskObjectCatalog;
import com.jx.tracker.risk.data.market.MarketDatasetCode;
import com.jx.tracker.risk.data.market.MarketRiskDataProvider;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.workflow.RiskCollectionTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RiskWorkflowPlanner {

    private static final String FLOW_EVENT_PROVIDER = "flow-event";

    private final RiskUniverseReader universeReader;
    private final AshareRiskObjectCatalog objectCatalog = new AshareRiskObjectCatalog();

    public RiskWorkflowPlanner(RiskUniverseReader universeReader) {
        if (universeReader == null) {
            throw new IllegalArgumentException("universeReader must not be null");
        }
        this.universeReader = universeReader;
    }

    public RiskWorkflowPlan plan(List<String> requestedSymbols) {
        List<RiskObjectKey> activeStocks = normalize(universeReader.activeAshareSymbols());
        if (activeStocks.isEmpty()) {
            throw new IllegalStateException("active A-share universe must not be empty");
        }
        List<RiskObjectKey> stocks = selectStocks(activeStocks, requestedSymbols);
        RiskObjectKey market = objectCatalog.market();
        List<RiskObjectKey> marketAndStocks = new ArrayList<>();
        marketAndStocks.add(market);
        marketAndStocks.addAll(stocks);

        List<RiskCollectionTask> tasks = List.of(
                task(MarketRiskDataProvider.PROVIDER_CODE,
                        MarketDatasetCode.CN_A_STOCK_MASTER.code(), stocks),
                task(MarketRiskDataProvider.PROVIDER_CODE,
                        MarketDatasetCode.SW1_MEMBERSHIP.code(), stocks),
                task(MarketRiskDataProvider.PROVIDER_CODE,
                        MarketDatasetCode.MARKET_DAILY.code(), marketAndStocks),
                task(MarketRiskDataProvider.PROVIDER_CODE,
                        MarketDatasetCode.VALUATION.code(), marketAndStocks),
                task(MarketRiskDataProvider.PROVIDER_CODE,
                        MarketDatasetCode.BREADTH.code(), List.of(market)),
                task(MarketRiskDataProvider.PROVIDER_CODE,
                        MarketDatasetCode.CROSS_MARKET.code(), List.of(market)),
                task(FLOW_EVENT_PROVIDER,
                        FlowEventDataset.MARGIN_FINANCING.code(), List.of(market)),
                task(FLOW_EVENT_PROVIDER,
                        FlowEventDataset.ETF_FUND_FLOW.code(), List.of(market)),
                task(FLOW_EVENT_PROVIDER,
                        FlowEventDataset.EARNINGS_FORECAST.code(), stocks),
                task(FLOW_EVENT_PROVIDER,
                        FlowEventDataset.STOCK_ANNOUNCEMENT.code(), stocks),
                task(FLOW_EVENT_PROVIDER,
                        FlowEventDataset.SHARE_UNLOCK.code(), stocks),
                task(FLOW_EVENT_PROVIDER,
                        FlowEventDataset.SHARE_REDUCTION.code(), stocks)
        );
        return new RiskWorkflowPlan(stocks, tasks, List.of(RiskHorizon.values()));
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
        String scopeKey = immutableObjects.stream()
                .map(object -> object.objectType().getCode() + ":" + object.objectId())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        return new RiskCollectionTask(providerCode, datasetCode, scopeKey, immutableObjects);
    }
}
