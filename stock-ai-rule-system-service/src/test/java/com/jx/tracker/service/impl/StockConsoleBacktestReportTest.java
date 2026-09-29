package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.BacktestResultMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockConsoleBacktestReportTest {

    @Mock private BacktestResultMapper backtestResultMapper;
    @InjectMocks private StockConsoleQueryServiceImpl service;

    @Test
    void overviewIncludesSavedEmptyReasonForMatchingRun() {
        String resultJson = """
                {"stockPoolType":"watchlist","stockPoolCode":"my-follow",
                 "signalCount":0,"evaluatedCount":0,"unevaluableCount":0,
                 "emptyReason":"所选日期没有可用的历史因子","equityCurve":[]}
                """;
        when(backtestResultMapper.selectList(any())).thenReturn(List.of(BacktestResult.builder()
                .id(22L)
                .objectType("rule")
                .objectCode("R1")
                .holdingPeriod(1)
                .status("skipped")
                .resultJson(resultJson)
                .build()));

        var report = service.backtestReports("rule", "R1", LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 28), 1, "watchlist", "my-follow", List.of());

        assertThat(report.reportId()).isEqualTo("22");
        assertThat(report.status()).isEqualTo("skipped");
        assertThat(report.sampleCount()).isZero();
        assertThat(report.resultJson()).isEqualTo(resultJson);
        assertThat(report.metrics()).extracting("label").contains("1日胜率");
    }

    @Test
    void detailIncludesSavedEmptyReasonForSelectedRun() {
        String resultJson = """
                {"signalCount":0,"evaluatedCount":0,"unevaluableCount":0,
                 "emptyReason":"规则没有触发历史信号","equityCurve":[]}
                """;
        when(backtestResultMapper.selectById("22")).thenReturn(BacktestResult.builder()
                .id(22L)
                .objectType("rule")
                .objectCode("R1")
                .holdingPeriod(1)
                .status("skipped")
                .resultJson(resultJson)
                .build());

        var report = service.backtestReport("22");

        assertThat(report.reportId()).isEqualTo("22");
        assertThat(report.status()).isEqualTo("skipped");
        assertThat(report.resultJson()).isEqualTo(resultJson);
    }

    @Test
    void overviewUsesOnlyNewestSavedRunEvenWhenOlderRunsAlsoMatch() {
        BacktestResult latest = BacktestResult.builder()
                .id(24L).objectType("rule").objectCode("R1")
                .holdingPeriod(5).triggerCount(3)
                .winRate(new BigDecimal("0.75"))
                .avgReturn(new BigDecimal("0.02"))
                .maxDrawdown(new BigDecimal("-0.03"))
                .status("success")
                .resultJson("{\"signalCount\":4,\"equityCurve\":[{\"date\":\"2026-09-01\",\"value\":1.02}]}")
                .build();
        BacktestResult older = BacktestResult.builder()
                .id(23L).objectType("rule").objectCode("R1")
                .holdingPeriod(5).triggerCount(100)
                .winRate(new BigDecimal("0.10"))
                .avgReturn(new BigDecimal("-0.20"))
                .status("failed")
                .build();
        when(backtestResultMapper.selectList(any())).thenReturn(List.of(latest, older));

        var overview = service.backtestReports("rule", "R1", null, null,
                5, null, null, List.of());

        assertThat(overview.reportId()).isEqualTo("24");
        assertThat(overview.metrics()).filteredOn(metric -> metric.label().equals("触发次数"))
                .singleElement().extracting(StockConsoleVo.MetricCard::value)
                .isEqualTo(new BigDecimal("3"));
        assertThat(overview.metrics()).filteredOn(metric -> metric.label().equals("5日胜率"))
                .singleElement().satisfies(metric -> assertThat(metric.value())
                        .isEqualByComparingTo(new BigDecimal("75.00")));
        assertThat(overview.comparison()).isEmpty();
        assertThat(overview.failureSamples()).isEmpty();
        assertThat(overview.equityCurve()).hasSize(1);
    }

    @Test
    void historyPaginatesRowsAndKeepsLegacyScopeUnknown() {
        BacktestResult legacy = BacktestResult.builder()
                .id(9L).objectType("candidate_rule").objectCode("OLD-CAND")
                .startDate(LocalDate.of(2024, 1, 1))
                .endDate(LocalDate.of(2024, 2, 1))
                .holdingPeriod(5).status("skipped")
                .createdTime(LocalDateTime.of(2024, 2, 2, 12, 0))
                .resultJson("{\"signalCount\":0}")
                .build();
        when(backtestResultMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(invocation -> {
                    Page<BacktestResult> page = invocation.getArgument(0);
                    assertThat(page.getCurrent()).isEqualTo(2);
                    assertThat(page.getSize()).isEqualTo(1);
                    page.setRecords(List.of(legacy));
                    page.setTotal(3);
                    return page;
                });

        var result = service.backtestReportHistory(new StockConsoleVo.BacktestReportHistoryQuery(
                null, null, null, null, null, null, null, List.of(), null, 2, 1));

        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getRows()).singleElement().satisfies(row -> {
            assertThat(row.reportId()).isEqualTo("9");
            assertThat(row.stockPoolType()).isNull();
            assertThat(row.stockPoolCode()).isNull();
            assertThat(row.symbols()).isEmpty();
            assertThat(row.createdTime()).isEqualTo(LocalDateTime.of(2024, 2, 2, 12, 0));
        });
    }

    @Test
    void historyCountsAllMatchingCustomSymbolSetsAcrossDatabasePages() {
        BacktestResult matching = BacktestResult.builder()
                .id(4L).objectType("rule").objectCode("R1")
                .resultJson("{\"stockPoolType\":\"custom\",\"symbols\":[\"SZ000001\",\"600519.SH\"]}")
                .build();
        BacktestResult different = BacktestResult.builder()
                .id(3L).objectType("rule").objectCode("R1")
                .resultJson("{\"stockPoolType\":\"custom\",\"symbols\":[\"600519.SH\"]}")
                .build();
        when(backtestResultMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(invocation -> {
                    Page<BacktestResult> page = invocation.getArgument(0);
                    page.setRecords(page.getCurrent() == 1 ? List.of(matching, different) : List.of());
                    return page;
                });

        var result = service.backtestReportHistory(new StockConsoleVo.BacktestReportHistoryQuery(
                "rule", "R1", null, null, null, "custom", null,
                List.of("000001.SZ", "SH600519"), null, 1, 20));

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRows()).extracting(StockConsoleVo.BacktestReportHistoryRow::reportId)
                .containsExactly("4");
    }

    @Test
    void detailExposesSavedParametersAndMissingReportIsNotFound() {
        BacktestResult saved = BacktestResult.builder()
                .id(25L).objectType("rule").objectCode("R2")
                .holdingPeriod(10).createdTime(LocalDateTime.of(2026, 9, 28, 9, 30))
                .resultJson("{\"stockPoolType\":\"watchlist\",\"stockPoolCode\":\"my-follow\",\"symbols\":[]}")
                .build();
        when(backtestResultMapper.selectById("25")).thenReturn(saved);

        var detail = service.backtestReport("25");

        assertThat(detail.holdingPeriod()).isEqualTo(10);
        assertThat(detail.stockPoolType()).isEqualTo("watchlist");
        assertThat(detail.stockPoolCode()).isEqualTo("my-follow");
        assertThat(detail.createdTime()).isEqualTo(LocalDateTime.of(2026, 9, 28, 9, 30));
        assertThatThrownBy(() -> service.backtestReport("missing"))
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getCode()).isEqualTo(404));
    }
}
