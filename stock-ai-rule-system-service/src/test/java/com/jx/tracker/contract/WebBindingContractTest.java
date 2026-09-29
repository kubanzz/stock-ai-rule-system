package com.jx.tracker.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jx.tracker.domain.dto.BacktestRequestDto;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.dto.TradeCalendarDto;
import com.jx.tracker.signal.controller.StockSignalController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WebBindingContractTest {

    private static final List<String> CONTROLLER_CLASSES = List.of(
            "com.jx.tracker.signal.controller.StockSignalController",
            "com.jx.tracker.market.data.controller.MarketDataController",
            "com.jx.tracker.controller.AiReviewController",
            "com.jx.tracker.controller.UserController",
            "com.jx.tracker.controller.CandidateRuleController",
            "com.jx.tracker.controller.StockConsoleController",
            "com.jx.tracker.rule.controller.RuleDefinitionController",
            "com.jx.tracker.rule.controller.RulePublishController"
    );

    @Test
    void requestParamsAndPathVariablesDeclareExplicitNames() throws Exception {
        for (String className : CONTROLLER_CLASSES) {
            Class<?> controllerClass = Class.forName(className);
            assertThat(controllerClass.isAnnotationPresent(RestController.class)).isTrue();
            for (Method method : controllerClass.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
                    if (requestParam != null) {
                        assertThat(namedValue(requestParam.value(), requestParam.name()))
                                .as("%s#%s @RequestParam %s", className, method.getName(), parameter)
                                .isNotBlank();
                    }
                    PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
                    if (pathVariable != null) {
                        assertThat(namedValue(pathVariable.value(), pathVariable.name()))
                                .as("%s#%s @PathVariable %s", className, method.getName(), parameter)
                                .isNotBlank();
                    }
                }
            }
        }
    }

    @Test
    void backtestRequestAcceptsFrontendCamelCasePayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        BacktestRequestDto dto = mapper.readValue("""
                {
                  "objectType": "rule",
                  "objectCode": "R_TREND_BREAKOUT_001",
                  "startDate": "2023-01-01",
                  "endDate": "2026-06-20",
                  "holdingPeriod": 5
                }
                """, BacktestRequestDto.class);

        assertThat(dto.getObjectType()).isEqualTo("rule");
        assertThat(dto.getObjectCode()).isEqualTo("R_TREND_BREAKOUT_001");
        assertThat(dto.getStartDate()).isEqualTo(LocalDate.of(2023, 1, 1));
        assertThat(dto.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(dto.getHoldingPeriod()).isEqualTo(5);
    }

    @Test
    void backtestRequestAcceptsCustomStockPoolPayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        BacktestRequestDto dto = mapper.readValue("""
                {
                  "objectType": "candidate_rule",
                  "objectCode": "CR_TREND_BEAR_GUARD_001",
                  "startDate": "2026-01-01",
                  "endDate": "2026-06-20",
                  "holdingPeriod": 3,
                  "stockPoolType": "custom",
                  "stockPoolCode": "research-small",
                  "symbols": ["000001.SZ", "600519.SH"]
                }
                """, BacktestRequestDto.class);

        assertThat(dto.getStockPoolType()).isEqualTo("custom");
        assertThat(dto.getPoolCode()).isEqualTo("research-small");
        assertThat(dto.getSymbols()).containsExactly("000001.SZ", "600519.SH");
    }

    @Test
    void backtestRequestAcceptsSnakeCaseStockPoolPayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        BacktestRequestDto dto = mapper.readValue("""
                {
                  "object_type": "candidate_rule",
                  "object_code": "CR_TREND_BEAR_GUARD_001",
                  "start_date": "2026-01-01",
                  "end_date": "2026-06-20",
                  "stock_pool_type": "watchlist",
                  "pool_code": "core-watchlist",
                  "symbols": []
                }
                """, BacktestRequestDto.class);

        assertThat(dto.getStockPoolType()).isEqualTo("watchlist");
        assertThat(dto.getPoolCode()).isEqualTo("core-watchlist");
        assertThat(dto.getSymbols()).isEmpty();
    }

    @Test
    void backtestReportEndpointDeclaresHistoricalScopeParams() throws Exception {
        Method method = Class.forName("com.jx.tracker.controller.StockConsoleController")
                .getDeclaredMethod("backtestReports", String.class, String.class, String.class,
                        LocalDate.class, LocalDate.class, Integer.class, String.class, String.class, List.class);
        List<String> names = java.util.Arrays.stream(method.getParameters())
                .map(parameter -> parameter.getAnnotation(RequestParam.class))
                .map(annotation -> namedValue(annotation.value(), annotation.name()))
                .toList();
        assertThat(names).contains("stockPoolType", "stockPoolCode", "symbols", "startDate", "endDate", "holdingPeriod");
    }

    @Test
    void marketDataSyncContractsAcceptCamelCaseAndSnakeCasePayloads() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        DailyQuoteSyncRequestDto camelCase = mapper.readValue("""
                {
                  "targetSymbol": "sz000001",
                  "startDate": "2024-01-01",
                  "endDate": "2024-01-31",
                  "triggerType": "manual",
                  "triggerBy": "codex"
                }
                """, DailyQuoteSyncRequestDto.class);
        DailyQuoteSyncRequestDto snakeCase = mapper.readValue("""
                {
                  "target_symbol": "600000.SH",
                  "start_date": "2024-02-01",
                  "end_date": "2024-02-29",
                  "trigger_type": "scheduler",
                  "trigger_by": "system"
                }
                """, DailyQuoteSyncRequestDto.class);

        assertThat(camelCase.getTargetSymbol()).isEqualTo("sz000001");
        assertThat(camelCase.getStartDate()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(camelCase.getTriggerBy()).isEqualTo("codex");
        assertThat(snakeCase.getTargetSymbol()).isEqualTo("600000.SH");
        assertThat(snakeCase.getEndDate()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(snakeCase.getTriggerType()).isEqualTo("scheduler");

        MarketDataSyncResultDto result = new MarketDataSyncResultDto();
        result.setDataSource("mock");
        result.setSyncType("daily_quote");
        result.setTargetSymbol("000001.SZ");
        result.setScanned(2);
        result.setInserted(1);

        assertThat(result.getDataSource()).isEqualTo("mock");
        assertThat(result.getTargetSymbol()).isEqualTo("000001.SZ");
        assertThat(result.getInserted()).isEqualTo(1);
    }

    @Test
    void tradeCalendarDtoCapturesAdjacentTradingDays() {
        TradeCalendarDto dto = new TradeCalendarDto();
        dto.setMarket("CN");
        dto.setTradeDate(LocalDate.of(2024, 1, 2));
        dto.setOpen(true);
        dto.setPreTradeDate(LocalDate.of(2023, 12, 29));
        dto.setNextTradeDate(LocalDate.of(2024, 1, 3));

        assertThat(dto.getMarket()).isEqualTo("CN");
        assertThat(dto.getTradeDate()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(dto.isOpen()).isTrue();
        assertThat(dto.getPreTradeDate()).isEqualTo(LocalDate.of(2023, 12, 29));
        assertThat(dto.getNextTradeDate()).isEqualTo(LocalDate.of(2024, 1, 3));
    }

    @Test
    void stockAnalysisDateRequestParamIsOptionalForDirectDetailLinks() throws Exception {
        Method analysisMethod = StockSignalController.class
                .getDeclaredMethod("analysis", String.class, LocalDate.class);
        RequestParam dateParam = analysisMethod.getParameters()[1].getAnnotation(RequestParam.class);

        assertThat(dateParam).isNotNull();
        assertThat(namedValue(dateParam.value(), dateParam.name())).isEqualTo("date");
        assertThat(dateParam.required()).isFalse();
    }

    private String namedValue(String value, String name) {
        return value == null || value.isBlank() ? name : value;
    }
}
