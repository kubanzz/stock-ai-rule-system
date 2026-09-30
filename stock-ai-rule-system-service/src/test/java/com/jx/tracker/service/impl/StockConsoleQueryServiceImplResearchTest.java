package com.jx.tracker.service.impl;

import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.mapper.AiReviewReportMapper;
import com.jx.tracker.mapper.BacktestResultMapper;
import com.jx.tracker.mapper.CandidateRuleMapper;
import com.jx.tracker.mapper.MarketDataSyncRunMapper;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.StockActualResultMapper;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.mapper.WorkflowRunMapper;
import com.jx.tracker.mapper.WorkflowStepRunMapper;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.service.IStockFactorDailyService;
import com.jx.tracker.service.StockDashboardQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class StockConsoleQueryServiceImplResearchTest {

    private static final String SYMBOL = "000001.SZ";
    private static final LocalDate SIGNAL_DATE = LocalDate.of(2026, 7, 10);
    private static final LocalDate QUOTE_DATE = LocalDate.of(2026, 7, 13);

    @Mock private StockSignalDailyMapper signalMapper;
    @Mock private StockBaseMapper stockBaseMapper;
    @Mock private StockActualResultMapper actualMapper;
    @Mock private StockDailyQuoteMapper quoteMapper;
    @Mock private StockFactorDailyMapper factorMapper;
    @Mock private RuleDefinitionMapper ruleMapper;
    @Mock private BacktestResultMapper backtestMapper;
    @Mock private AiReviewReportMapper reviewMapper;
    @Mock private CandidateRuleMapper candidateMapper;
    @Mock private MarketDataSyncRunMapper syncRunMapper;
    @Mock private WorkflowRunMapper workflowRunMapper;
    @Mock private WorkflowStepRunMapper workflowStepRunMapper;
    @Mock private StockDashboardQueryService dashboardService;
    @Mock private MarketDataSyncService syncService;
    @Mock private IStockFactorDailyService factorService;
    @Mock private JdbcTemplate jdbcTemplate;
    @InjectMocks private StockConsoleQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(quoteMapper.selectCount(any())).thenReturn(26L);
        lenient().when(quoteMapper.selectOne(any())).thenReturn(StockDailyQuote.builder()
                .symbol(SYMBOL).tradeDate(QUOTE_DATE).closePrice(BigDecimal.TEN).build());
        lenient().when(quoteMapper.selectList(any())).thenReturn(List.of());
        lenient().when(signalMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void usesSavedTraceSnapshotAndDatesForSignalExplanation() {
        StockSignalDaily signal = baseSignal();
        signal.setTraceJson("""
                {"factorDate":"2026-07-10","factorSnapshot":{"close":10,"momentum":3},
                 "ruleEvaluations":[{"code":"R1","name":"动量规则","version":"v2",
                   "format":"json","priority":100,"status":"MATCHED","evidenceStatus":"FULL",
                   "bullishDelta":60,"bearishDelta":0,"riskDelta":0,
                   "conditions":[{"field":"momentum","operator":"gt","actual":3,"expected":2,"status":"MATCHED"}]}],
                 "decision":{"rawScores":{"bullish":60,"bearish":0,"risk":0},
                   "effectiveScores":{"bullish":60,"bearish":0,"risk":0},
                   "thresholds":{"strongBullish":70,"bullish":55,"bearish":60,"highRisk":80},
                   "conflict":false,"riskOverride":false,"confidence":0.6,
                   "direction":"bullish","level":"偏看涨",
                   "reason":"看涨规则得分达到阈值","signal":"bullish"}}
                """);
        when(signalMapper.selectOne(any())).thenReturn(signal);

        var detail = service.research(SYMBOL, SIGNAL_DATE);

        assertThat(detail.signalDate()).isEqualTo(SIGNAL_DATE);
        assertThat(detail.factorDate()).isEqualTo(SIGNAL_DATE);
        assertThat(detail.quoteDate()).isEqualTo(QUOTE_DATE);
        assertThat(detail.traceStatus()).isEqualTo("complete");
        assertThat(detail.trace().path("ruleEvaluations").get(0).path("bullishDelta").decimalValue())
                .isEqualByComparingTo("60");
        assertThat(detail.factors()).extracting(StockConsoleVo.FactorState::factor).contains("close", "momentum");
        assertThat(detail.factors()).extracting(StockConsoleVo.FactorState::value).contains("10", "3");
        assertThat(detail.explanation()).isEqualTo("看涨规则得分达到阈值");
        assertThat(detail.ruleChain()).singleElement().satisfies(rule -> {
            assertThat(rule.ruleName()).isEqualTo("动量规则");
            assertThat(rule.contribution()).isNull();
        });
        verify(factorMapper, never()).selectOne(any());
    }

    @Test
    void legacySignalKeepsUnknownContributionAndOnlySameDayFactors() {
        when(signalMapper.selectOne(any())).thenReturn(baseSignal());
        when(factorMapper.selectOne(any())).thenReturn(StockFactorDaily.builder()
                .symbol(SYMBOL).tradeDate(SIGNAL_DATE).factorJson("{\"close\":11}").build());

        var detail = service.research(SYMBOL, SIGNAL_DATE);

        assertThat(detail.trace()).isNull();
        assertThat(detail.traceStatus()).isEqualTo("legacy");
        assertThat(detail.factorDate()).isEqualTo(SIGNAL_DATE);
        assertThat(detail.factors()).extracting(StockConsoleVo.FactorState::value).contains("11");
        assertThat(detail.ruleChain()).singleElement().satisfies(rule -> {
            assertThat(rule.ruleCode()).isEqualTo("R1");
            assertThat(rule.ruleName()).isEqualTo("R1");
            assertThat(rule.contribution()).isNull();
        });

        verify(signalMapper).selectOne(any());
    }

    @Test
    void incompleteTraceIsLabeledPartial() {
        StockSignalDaily signal = baseSignal();
        signal.setTraceJson("""
                {"factorDate":"2026-07-10","factorSnapshot":{"momentum":3},
                 "ruleEvaluations":[{"code":"R1","status":"MATCHED","evidenceStatus":"PARTIAL",
                   "conditions":[],"bullishDelta":60,"bearishDelta":0,"riskDelta":0}],
                 "decision":{"signal":"bullish","reason":"证据不完整"}}
                """);
        when(signalMapper.selectOne(any())).thenReturn(signal);

        var detail = service.research(SYMBOL, SIGNAL_DATE);

        assertThat(detail.traceStatus()).isEqualTo("partial");
        assertThat(detail.trace()).isNotNull();
        assertThat(detail.factors()).extracting(StockConsoleVo.FactorState::factor).contains("momentum");
    }

    @Test
    void malformedSavedTraceIsPartialRatherThanLegacy() {
        StockSignalDaily signal = baseSignal();
        signal.setTraceJson("{broken");
        when(signalMapper.selectOne(any())).thenReturn(signal);

        var detail = service.research(SYMBOL, SIGNAL_DATE);

        assertThat(detail.traceStatus()).isEqualTo("partial");
        assertThat(detail.trace()).isNull();
        assertThat(detail.ruleChain()).singleElement().satisfies(rule ->
                assertThat(rule.contribution()).isNull());
    }

    @Test
    void missingSignalOnRequestedDateIsPendingInsteadOfUsingEarlierSignal() {
        var detail = service.research(SYMBOL, SIGNAL_DATE);

        assertThat(detail.signalStatus()).isEqualTo("pending");
        assertThat(detail.signalDate()).isNull();
        assertThat(detail.ruleChain()).isEmpty();
        assertThat(detail.trace()).isNull();
        verify(factorService, never()).calculateAndSave(any());
    }

    @Test
    void unversionedResearchReturnsLatestAvailableVersionForSignalDate() {
        when(signalMapper.selectOne(any())).thenReturn(baseSignal());
        when(jdbcTemplate.query(any(String.class), org.mockito.ArgumentMatchers.<RowMapper<StockConsoleVo.SignalVersion>>any(),
                eq(SYMBOL), eq(Date.valueOf(SIGNAL_DATE)))).thenReturn(List.of(
                        new StockConsoleVo.SignalVersion(2, LocalDateTime.of(2026, 7, 10, 18, 0)),
                        new StockConsoleVo.SignalVersion(1, LocalDateTime.of(2026, 7, 10, 17, 0))));

        var detail = service.research(SYMBOL, SIGNAL_DATE);

        assertThat(detail.currentVersionNo()).isEqualTo(2L);
        assertThat(detail.versions()).extracting(StockConsoleVo.SignalVersion::versionNo)
                .containsExactly(2L, 1L);
    }

    @Test
    void explicitVersionUsesHistoricalSignalOnly() {
        StockSignalDaily historical = baseSignal();
        historical.setSignal("bearish");
        historical.setExplanation("第一版判断");
        historical.setConfidence(null);
        historical.setRiskScore(null);
        when(jdbcTemplate.query(any(String.class), org.mockito.ArgumentMatchers.<RowMapper<StockSignalDaily>>any(),
                eq(SYMBOL), eq(Date.valueOf(SIGNAL_DATE)), eq(1)))
                .thenReturn(List.of(historical));
        when(jdbcTemplate.query(any(String.class), org.mockito.ArgumentMatchers.<RowMapper<StockConsoleVo.SignalVersion>>any(),
                eq(SYMBOL), eq(Date.valueOf(SIGNAL_DATE))))
                .thenReturn(List.of(new StockConsoleVo.SignalVersion(2, null),
                        new StockConsoleVo.SignalVersion(1, null)));

        var detail = service.research(SYMBOL, SIGNAL_DATE, 1);

        assertThat(detail.signal()).isEqualTo("bearish");
        assertThat(detail.explanation()).isEqualTo("第一版判断");
        assertThat(detail.currentVersionNo()).isEqualTo(1L);
        assertThat(detail.versions()).extracting(StockConsoleVo.SignalVersion::versionNo)
                .containsExactly(2L, 1L);
        assertThat(detail.confidence()).isNull();
        assertThat(detail.riskScore()).isNull();
        verify(signalMapper, never()).selectOne(any());
    }

    @Test
    void missingExplicitVersionDoesNotFallBackToCurrentSignal() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.research(SYMBOL, SIGNAL_DATE, 3))
                .isInstanceOf(com.jx.tracker.exception.ServiceException.class)
                .hasMessageContaining("版本不存在");
        verify(signalMapper, never()).selectOne(any());
    }

    @Test
    void nullLegacyScoresRemainUnknownInDetailAndHistory() {
        StockSignalDaily signal = baseSignal();
        signal.setConfidence(null);
        signal.setRiskScore(null);
        when(signalMapper.selectOne(any())).thenReturn(signal);
        when(signalMapper.selectList(any())).thenReturn(List.of(signal));

        var detail = service.research(SYMBOL, SIGNAL_DATE);

        assertThat(detail.confidence()).isNull();
        assertThat(detail.riskScore()).isNull();
        assertThat(detail.history()).singleElement().satisfies(record ->
                assertThat(record.confidence()).isNull());
    }

    private StockSignalDaily baseSignal() {
        return StockSignalDaily.builder()
                .symbol(SYMBOL).signalDate(SIGNAL_DATE).signal("bullish")
                .bullishScore(BigDecimal.valueOf(60)).confidence(new BigDecimal("0.6000"))
                .riskScore(BigDecimal.ZERO).triggeredRules("[\"R1\"]")
                .explanation("旧规则说明").build();
    }
}
