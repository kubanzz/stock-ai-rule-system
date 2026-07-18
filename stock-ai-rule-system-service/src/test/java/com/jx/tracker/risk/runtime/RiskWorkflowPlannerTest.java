package com.jx.tracker.risk.runtime;

import com.jx.tracker.risk.data.flow.FlowEventDataset;
import com.jx.tracker.risk.data.market.MarketDatasetCode;
import com.jx.tracker.risk.data.market.MarketRiskDataProvider;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.workflow.RiskCollectionTask;
import com.jx.tracker.risk.workflow.RiskCollectionScope;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskWorkflowPlannerTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 18);
    private static final LocalDateTime AS_OF = TRADE_DATE.atTime(19, 30);

    @Test
    void explicitSymbolsAreANormalizedStrictSelectionOfTheActiveUniverse() {
        RiskWorkflowPlanner planner = planner();

        RiskWorkflowPlan plan = planner.plan(List.of("sh600519", " 000001 "));

        assertThat(plan.stockObjects()).extracting(RiskObjectKey::objectId)
                .containsExactly("000001.SZ", "600519.SH");
        assertThatThrownBy(() -> planner.plan(List.of("000002.SZ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active A-share universe");
    }

    @Test
    void emptySymbolsUseAllActiveStocksAndAllocateTwelveProviderTasks() {
        RiskWorkflowPlan plan = planner().plan(List.of());

        assertThat(plan.stockObjects()).extracting(RiskObjectKey::objectId)
                .containsExactly("000001.SZ", "600519.SH", "920992.BJ");
        assertThat(plan.horizons()).containsExactly(RiskHorizon.values());
        assertThat(plan.collectionTasks()).hasSize(12);

        Map<String, RiskCollectionTask> tasks = plan.collectionTasks().stream()
                .collect(Collectors.toMap(RiskCollectionTask::datasetCode, Function.identity()));
        assertThat(tasks.keySet()).containsExactlyInAnyOrderElementsOf(
                List.of(
                        MarketDatasetCode.CN_A_STOCK_MASTER.code(),
                        MarketDatasetCode.SW1_MEMBERSHIP.code(),
                        MarketDatasetCode.MARKET_DAILY.code(),
                        MarketDatasetCode.VALUATION.code(),
                        MarketDatasetCode.BREADTH.code(),
                        MarketDatasetCode.CROSS_MARKET.code(),
                        FlowEventDataset.MARGIN_FINANCING.code(),
                        FlowEventDataset.ETF_FUND_FLOW.code(),
                        FlowEventDataset.EARNINGS_FORECAST.code(),
                        FlowEventDataset.STOCK_ANNOUNCEMENT.code(),
                        FlowEventDataset.SHARE_UNLOCK.code(),
                        FlowEventDataset.SHARE_REDUCTION.code()
                )
        );
        assertThat(tasks.get(MarketDatasetCode.CN_A_STOCK_MASTER.code()).providerCode())
                .isEqualTo(MarketRiskDataProvider.PROVIDER_CODE);
        assertThat(tasks.get(FlowEventDataset.MARGIN_FINANCING.code()).providerCode())
                .isEqualTo("flow-event");
        assertThat(tasks.get(FlowEventDataset.MARGIN_FINANCING.code()).objects())
                .containsExactly(market());
        assertThat(tasks.get(FlowEventDataset.EARNINGS_FORECAST.code()).objects())
                .containsExactlyElementsOf(plan.stockObjects());
        assertThat(tasks.get(MarketDatasetCode.MARKET_DAILY.code()).objects())
                .containsExactly(market(), plan.stockObjects().get(0),
                        plan.stockObjects().get(1), plan.stockObjects().get(2));
    }

    @Test
    void planBuildsDailyRequestWithConfiguredModelAndCutoff() {
        RiskWorkflowPlan plan = planner().plan(List.of("600519.SH"));

        var request = plan.dailyRequest(
                TRADE_DATE, AS_OF, List.of(), "risk-runtime-v1", LocalTime.of(19, 0));

        assertThat(request.collectionStartDate()).isEqualTo(TRADE_DATE.minusYears(5));
        assertThat(request.scoreStartDate()).isEqualTo(TRADE_DATE);
        assertThat(request.endDate()).isEqualTo(TRADE_DATE);
        assertThat(request.asOf()).isEqualTo(AS_OF);
        assertThat(request.modelVersion()).isEqualTo("risk-runtime-v1");
        assertThat(request.afterCloseCutoff()).isEqualTo(LocalTime.of(19, 0));
    }

    @Test
    void fiveThousandStocksAreChunkedAndEveryPersistedScopeKeyIsShortStableAndOrderIndependent() {
        List<String> universe = java.util.stream.IntStream.range(0, 5_000)
                .mapToObj(index -> String.format("%06d.SH", index))
                .toList();
        RiskWorkflowPlanner planner = new RiskWorkflowPlanner(() -> universe, 200);

        RiskWorkflowPlan first = planner.plan(List.of());
        RiskWorkflowPlan second = new RiskWorkflowPlanner(
                () -> universe.reversed(), 200).plan(List.of());

        assertThat(first.collectionTasks()).allSatisfy(task -> {
            assertThat(task.objects()).hasSizeLessThanOrEqualTo(200);
            assertThat(task.scopeKey()).startsWith("scope:v1:n=").contains(":sha256=");
            assertThat(task.scopeKey().length()).isLessThanOrEqualTo(128);
        });
        assertThat(first.collectionTasks()).extracting(RiskCollectionTask::datasetCode)
                .containsExactlyInAnyOrderElementsOf(second.collectionTasks().stream()
                        .map(RiskCollectionTask::datasetCode).toList());
        assertThat(first.collectionTasks()).extracting(RiskCollectionTask::scopeKey)
                .containsExactlyElementsOf(second.collectionTasks().stream()
                        .map(RiskCollectionTask::scopeKey).toList());
        assertThat(first.collectionTasks()).extracting(RiskCollectionTask::datasetCode)
                .contains(MarketDatasetCode.values()[0].code())
                .containsAll(Set.of(MarketDatasetCode.values()).stream().map(MarketDatasetCode::code).toList())
                .containsAll(Set.of(FlowEventDataset.values()).stream().map(FlowEventDataset::code).toList());
        assertThat(first.collectionTasks().stream()
                .filter(task -> task.datasetCode().equals(MarketDatasetCode.SW1_MEMBERSHIP.code())))
                .hasSize(25);
        assertThat(first.collectionTasks().stream()
                .filter(task -> task.datasetCode().equals(MarketDatasetCode.MARKET_DAILY.code())))
                .hasSize(26);

        RiskWorkflowPlan subset = planner.plan(List.of("000001.SH"));
        assertThat(subset.collectionTasks().stream()
                .filter(task -> task.datasetCode().equals(MarketDatasetCode.SW1_MEMBERSHIP.code()))
                .map(RiskCollectionTask::scopeKey).findFirst())
                .isNotEqualTo(first.collectionTasks().stream()
                        .filter(task -> task.datasetCode().equals(MarketDatasetCode.SW1_MEMBERSHIP.code()))
                        .map(RiskCollectionTask::scopeKey).findFirst());

        RiskObjectKey stock = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
        RiskObjectKey sectorA = new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801780");
        RiskObjectKey sectorB = new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801790");
        assertThat(RiskCollectionScope.key(List.of(stock, sectorA)))
                .isEqualTo(RiskCollectionScope.key(List.of(sectorA, stock)))
                .isNotEqualTo(RiskCollectionScope.key(List.of(stock, sectorB)));
    }

    private RiskWorkflowPlanner planner() {
        return new RiskWorkflowPlanner(
                () -> List.of("600519.SH", "000001.SZ", "920992.BJ")
        );
    }

    private RiskObjectKey market() {
        return new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    }
}
