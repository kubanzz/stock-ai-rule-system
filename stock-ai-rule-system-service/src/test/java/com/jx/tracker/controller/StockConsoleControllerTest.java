package com.jx.tracker.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.constant.HttpStatus;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.service.StockConsoleQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StockConsoleControllerTest {

    @Test
    void exposesPageLevelAggregateEndpoints() throws Exception {
        RequestMapping classMapping = StockConsoleController.class.getAnnotation(RequestMapping.class);
        assertThat(classMapping.value()).containsExactly("/api");

        assertThat(getMapping("dashboard", LocalDate.class, String.class, String.class).value())
                .containsExactly("/signals/dashboard");
        assertThat(getMapping("watchlists", String.class).value()).containsExactly("/watchlists");
        assertThat(postMapping("addWatchlistStock", String.class, StockConsoleVo.WatchlistStockMutationRequest.class).value())
                .containsExactly("/watchlists/{poolId}/stocks");
        assertThat(deleteMapping("removeWatchlistStock", String.class, String.class).value())
                .containsExactly("/watchlists/{poolId}/stocks/{symbol}");
        assertThat(getMapping("research", String.class, LocalDate.class).value()).containsExactly("/stocks/{symbol}/research");
        assertThat(getMapping("ruleGovernance", String.class, String.class, String.class).value()).containsExactly("/rules/governance");
        assertThat(getMapping("ruleGovernanceDetail", String.class).value()).containsExactly("/rules/{ruleCode}/governance");
        assertThat(getMapping("backtestReports", String.class, String.class).value()).containsExactly("/backtests/reports");
        assertThat(getMapping("backtestReport", String.class).value()).containsExactly("/backtests/reports/{reportId}");
        assertThat(getMapping("backtestFailureSamples", String.class).value()).containsExactly("/backtests/reports/{reportId}/failure-samples");
        assertThat(getMapping("aiReviewSummary", LocalDate.class).value()).containsExactly("/ai/reviews/summary");
        assertThat(getMapping("aiMisjudgements", LocalDate.class, String.class).value()).containsExactly("/ai/reviews/misjudgements");
        assertThat(postMapping("createCandidateFromMisjudgement", String.class).value())
                .containsExactly("/ai/reviews/misjudgements/{sampleId}/candidate-rule");
        assertThat(getMapping("runCenterOverview", LocalDate.class).value()).containsExactly("/run-center/overview");
    }

    @Test
    void optionalFiltersDeclareExplicitRequestParamNames() throws Exception {
        Method dashboard = StockConsoleController.class.getDeclaredMethod(
                "dashboard",
                LocalDate.class,
                String.class,
                String.class
        );

        assertOptionalRequestParam(dashboard, 0, "date");
        assertOptionalRequestParam(dashboard, 1, "market");
        assertOptionalRequestParam(dashboard, 2, "poolCode");
    }

    @Test
    void dashboardReturnsAuxiliaryDecisionDisclaimer() {
        CapturingStockConsoleQueryService service = new CapturingStockConsoleQueryService();
        StockConsoleController controller = new StockConsoleController(service);

        AjaxResult response = controller.dashboard(LocalDate.of(2026, 7, 10), "A股", "my-follow");

        assertThat(response.get(AjaxResult.CODE_TAG)).isEqualTo(HttpStatus.SUCCESS);
        assertThat(service.date).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(service.market).isEqualTo("A股");
        assertThat(service.poolCode).isEqualTo("my-follow");
        StockConsoleVo.SignalDashboardOverview data =
                (StockConsoleVo.SignalDashboardOverview) response.get(AjaxResult.DATA_TAG);
        assertThat(data.riskDisclaimer()).contains("辅助决策");
        assertThat(data.metrics()).extracting(StockConsoleVo.MetricCard::label).contains("信号总数");
    }

    private GetMapping getMapping(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        return StockConsoleController.class.getDeclaredMethod(methodName, parameterTypes).getAnnotation(GetMapping.class);
    }

    private PostMapping postMapping(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        return StockConsoleController.class.getDeclaredMethod(methodName, parameterTypes).getAnnotation(PostMapping.class);
    }

    private DeleteMapping deleteMapping(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        return StockConsoleController.class.getDeclaredMethod(methodName, parameterTypes).getAnnotation(DeleteMapping.class);
    }

    private void assertOptionalRequestParam(Method method, int parameterIndex, String name) {
        RequestParam annotation = method.getParameters()[parameterIndex].getAnnotation(RequestParam.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(name);
        assertThat(annotation.required()).isFalse();
    }

    private static final class CapturingStockConsoleQueryService implements StockConsoleQueryService {

        private LocalDate date;
        private String market;
        private String poolCode;

        @Override
        public StockConsoleVo.SignalDashboardOverview dashboard(LocalDate date, String market, String poolCode) {
            this.date = date;
            this.market = market;
            this.poolCode = poolCode;
            return new StockConsoleVo.SignalDashboardOverview(
                    date,
                    "本系统输出仅作为股票研究和辅助决策信号，不构成投资建议。",
                    List.of(new StockConsoleVo.MetricCard("信号总数", BigDecimal.valueOf(128), "条", BigDecimal.valueOf(12), "blue")),
                    List.of(),
                    new StockConsoleVo.MarketContext("沪深300", BigDecimal.valueOf(3692.61), BigDecimal.valueOf(0.68), "偏强", List.of()),
                    128
            );
        }

        @Override
        public List<StockConsoleVo.WatchlistPool> watchlists(String market) {
            return List.of();
        }

        @Override
        public StockConsoleVo.WatchlistPool addWatchlistStock(String poolId, StockConsoleVo.WatchlistStockMutationRequest request) {
            return null;
        }

        @Override
        public StockConsoleVo.WatchlistPool removeWatchlistStock(String poolId, String symbol) {
            return null;
        }

        @Override
        public StockConsoleVo.StockResearchDetail research(String symbol, LocalDate date) {
            return null;
        }

        @Override
        public StockConsoleVo.RuleGovernanceOverview ruleGovernance(String ruleType, String status, String source) {
            return null;
        }

        @Override
        public StockConsoleVo.RuleGovernanceDetail ruleGovernanceDetail(String ruleCode) {
            return null;
        }

        @Override
        public StockConsoleVo.BacktestReportOverview backtestReports(String objectCode, String market) {
            return null;
        }

        @Override
        public StockConsoleVo.BacktestReportDetail backtestReport(String reportId) {
            return null;
        }

        @Override
        public List<StockConsoleVo.BacktestFailureSample> backtestFailureSamples(String reportId) {
            return List.of();
        }

        @Override
        public StockConsoleVo.AiReviewOverview aiReviewSummary(LocalDate date) {
            return null;
        }

        @Override
        public List<StockConsoleVo.MisjudgementSample> aiMisjudgements(LocalDate date, String reasonCategory) {
            return List.of();
        }

        @Override
        public StockConsoleVo.CandidateRuleSuggestion createCandidateFromMisjudgement(String sampleId) {
            return null;
        }

        @Override
        public StockConsoleVo.RunCenterOverview runCenterOverview(LocalDate date) {
            return null;
        }
    }
}
