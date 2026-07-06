package com.jx.tracker.backtest;

import com.jx.tracker.domain.dto.BacktestRequestDto;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.entity.StockActualResult;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.enums.BacktestStatus;
import com.jx.tracker.domain.enums.RuleObjectType;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.mapper.CandidateRuleMapper;
import com.jx.tracker.mapper.BacktestResultMapper;
import com.jx.tracker.mapper.StockActualResultMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.rule.engine.JsonRuleEngineExecutor;
import com.jx.tracker.signal.service.SignalScoringService;
import com.jx.tracker.verification.PredictionHitPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class SingleRuleBacktestServiceTest {

    private final FakeMapper<StockSignalDailyMapper, StockSignalDaily> signalMapper = fakeMapper(StockSignalDailyMapper.class);
    private final FakeMapper<StockFactorDailyMapper, StockFactorDaily> factorMapper = fakeMapper(StockFactorDailyMapper.class);
    private final FakeMapper<StockActualResultMapper, StockActualResult> actualResultMapper = fakeMapper(StockActualResultMapper.class);
    private final FakeMapper<StockDailyQuoteMapper, StockDailyQuote> quoteMapper = fakeMapper(StockDailyQuoteMapper.class);
    private final FakeMapper<BacktestResultMapper, BacktestResult> backtestResultMapper = fakeMapper(BacktestResultMapper.class);
    private final FakeMapper<CandidateRuleMapper, CandidateRule> candidateRuleMapper = fakeMapper(CandidateRuleMapper.class);
    private final SingleRuleBacktestService service = new SingleRuleBacktestService(
            signalMapper.mapper,
            factorMapper.mapper,
            actualResultMapper.mapper,
            quoteMapper.mapper,
            backtestResultMapper.mapper,
            candidateRuleMapper.mapper,
            new JsonRuleEngineExecutor(),
            new SignalScoringService(),
            new PredictionHitPolicy()
    );

    @Test
    void emptySamplePersistsZeroMetricsWithDefaultCostSettings() {
        BacktestRequestDto request = request();
        signalMapper.selectResponses.add(List.of());

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getTriggerCount()).isZero();
        assertThat(result.getWinRate()).isEqualByComparingTo("0.0000");
        assertThat(result.getAvgReturn()).isEqualByComparingTo("0.0000");
        assertThat(result.getMaxDrawdown()).isEqualByComparingTo("0.0000");
        assertThat(result.getSharpeRatio()).isEqualByComparingTo("0.0000");
        assertThat(result.getResultJson()).contains("\"feeRate\":0.0010", "\"slippageRate\":0.0005");
        assertThat(backtestResultMapper.inserted).containsExactly(result);
    }

    @Test
    void singleRuleBacktestUsesOnlyActualResultsFromSignalDateSliceAndPersistsMetrics() {
        BacktestRequestDto request = request();
        StockSignalDaily bullish = signal(1L, "AAPL", LocalDate.of(2026, 1, 2), SignalType.BULLISH.getCode());
        StockSignalDaily bearish = signal(2L, "MSFT", LocalDate.of(2026, 1, 3), SignalType.BEARISH.getCode());
        signalMapper.selectResponses.add(List.of(bullish, bearish));
        actualResultMapper.selectResponses.add(List.of(actual("AAPL", LocalDate.of(2026, 1, 2), "0.0200", true)));
        actualResultMapper.selectResponses.add(List.of(actual("MSFT", LocalDate.of(2026, 1, 3), "-0.0100", true)));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getObjectType()).isEqualTo(RuleObjectType.RULE.getCode());
        assertThat(result.getObjectCode()).isEqualTo("R_TREND_BREAKOUT_001");
        assertThat(result.getTriggerCount()).isEqualTo(2);
        assertThat(result.getWinRate()).isEqualByComparingTo("1.0000");
        assertThat(result.getAvgReturn()).isEqualByComparingTo("0.0135");
        assertThat(result.getMaxDrawdown()).isEqualByComparingTo("0.0000");
        assertThat(result.getSharpeRatio()).isGreaterThan(BigDecimal.ZERO);
        assertThat(result.getResultJson()).contains("\"riskDisclaimer\"");

        assertThat(backtestResultMapper.inserted).hasSize(1);
        assertThat(backtestResultMapper.inserted.getFirst().getTriggerCount()).isEqualTo(2);
        assertThat(actualResultMapper.selectCalls).isEqualTo(2);
    }

    @Test
    void singleRuleBacktestParsesTriggeredRulesJsonPrecisely() {
        BacktestRequestDto request = request();
        StockSignalDaily descriptionOnly = signal(1L, "AAPL", LocalDate.of(2026, 1, 2), SignalType.BULLISH.getCode(),
                "[{\"rule_code\":\"R_OTHER\",\"explanation\":\"R_TREND_BREAKOUT_001\"}]");
        StockSignalDaily stringRule = signal(2L, "MSFT", LocalDate.of(2026, 1, 3), SignalType.BULLISH.getCode(),
                "[\"R_TREND_BREAKOUT_001\"]");
        StockSignalDaily objectRule = signal(3L, "NVDA", LocalDate.of(2026, 1, 4), SignalType.BULLISH.getCode(),
                "[{\"rule_code\":\"R_TREND_BREAKOUT_001\"}]");
        signalMapper.selectResponses.add(List.of(descriptionOnly, stringRule, objectRule));
        actualResultMapper.selectResponses.add(List.of(actual("MSFT", LocalDate.of(2026, 1, 3), "0.0200", true)));
        actualResultMapper.selectResponses.add(List.of(actual("NVDA", LocalDate.of(2026, 1, 4), "0.0100", true)));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(actualResultMapper.selectCalls).isEqualTo(2);
        assertThat(result.getTriggerCount()).isEqualTo(2);
        assertThat(result.getAvgReturn()).isEqualByComparingTo("0.0135");
    }

    @Test
    void watchSignalsDoNotPayFeeOrSlippage() {
        BacktestRequestDto request = request();
        StockSignalDaily watch = signal(1L, "AAPL", LocalDate.of(2026, 1, 2), SignalType.WATCH.getCode());
        signalMapper.selectResponses.add(List.of(watch));
        actualResultMapper.selectResponses.add(List.of(actual("AAPL", LocalDate.of(2026, 1, 2), "0.0200", false)));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getTriggerCount()).isEqualTo(1);
        assertThat(result.getAvgReturn()).isEqualByComparingTo("0.0000");
        assertThat(result.getTotalReturn()).isEqualByComparingTo("0.0000");
    }

    @Test
    void unevaluableSamplesAreReportedInResultJson() {
        BacktestRequestDto request = request();
        StockSignalDaily tailSample = signal(1L, "AAPL", LocalDate.of(2026, 1, 30), SignalType.BULLISH.getCode());
        signalMapper.selectResponses.add(List.of(tailSample));
        actualResultMapper.selectResponses.add(List.of(actual("AAPL", LocalDate.of(2026, 1, 30), null, false)));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getTriggerCount()).isZero();
        assertThat(result.getResultJson()).contains("\"signalCount\":1", "\"skippedCount\":1", "\"unevaluableCount\":1");
    }

    @Test
    void candidateRuleBacktestRerunsProposedContentAgainstHistoricalFactorsInsteadOfPersistedSignals() {
        BacktestRequestDto request = request();
        request.setObjectType(RuleObjectType.CANDIDATE_RULE.getCode());
        request.setObjectCode("CR_20260620_0001");
        candidateRuleMapper.selectResponses.add(List.of(CandidateRule.builder()
                .id(21L)
                .candidateCode("CR_20260620_0001")
                .targetRuleCode("R_TREND_BREAKOUT_001")
                .proposedContent("""
                        {
                          "conditions": [
                            {"field": "candidate_momentum", "operator": "eq", "value": "breakout"}
                          ],
                          "actions": {
                            "bullish_score": 75,
                            "explanation": "候选规则基于历史因子触发"
                          }
                        }
                        """)
                .build()));
        factorMapper.selectResponses.add(List.of(
                factor("AAPL", LocalDate.of(2026, 1, 2), """
                        {"candidate_momentum":"breakout"}
                        """),
                factor("MSFT", LocalDate.of(2026, 1, 3), """
                        {"candidate_momentum":"flat"}
                        """)
        ));
        actualResultMapper.selectResponses.add(List.of(actual("AAPL", LocalDate.of(2026, 1, 2), "0.0200", true)));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getObjectType()).isEqualTo(RuleObjectType.CANDIDATE_RULE.getCode());
        assertThat(result.getObjectCode()).isEqualTo("CR_20260620_0001");
        assertThat(result.getCandidateRuleId()).isEqualTo(21L);
        assertThat(result.getTriggerCount()).isEqualTo(1);
        assertThat(signalMapper.selectCalls).isZero();
        assertThat(candidateRuleMapper.updated).hasSize(1);
        CandidateRule updatedCandidate = candidateRuleMapper.updated.getFirst();
        assertThat(updatedCandidate.getBacktestStatus()).isEqualTo(BacktestStatus.SUCCESS.getCode());
        assertThat(updatedCandidate.getLatestBacktestReportId()).isEqualTo(result.getId());
        assertThat(updatedCandidate.getBacktestResult())
                .contains("\"triggerCount\":1", "\"winRate\":1.0000", "\"avgReturn\":0.0185", "\"totalReturn\":0.0185");
    }

    @Test
    void candidateRuleBacktestFallsBackToDailyQuotesWhenActualResultIsMissing() {
        BacktestRequestDto request = request();
        request.setObjectType(RuleObjectType.CANDIDATE_RULE.getCode());
        request.setObjectCode("CR_20260620_0002");
        candidateRuleMapper.selectResponses.add(List.of(CandidateRule.builder()
                .id(22L)
                .candidateCode("CR_20260620_0002")
                .targetRuleCode("R_TREND_BREAKOUT_001")
                .proposedContent("""
                        {
                          "conditions": [
                            {"field": "candidate_momentum", "operator": "eq", "value": "breakout"}
                          ],
                          "actions": {
                            "bullish_score": 75,
                            "explanation": "候选规则基于历史因子触发"
                          }
                        }
                        """)
                .build()));
        factorMapper.selectResponses.add(List.of(factor("AAPL", LocalDate.of(2026, 1, 2), """
                {"candidate_momentum":"breakout"}
                """)));
        actualResultMapper.selectResponses.add(List.of());
        quoteMapper.selectResponses.add(List.of(
                quote("AAPL", LocalDate.of(2026, 1, 2), "10.00"),
                quote("AAPL", LocalDate.of(2026, 1, 5), "10.10"),
                quote("AAPL", LocalDate.of(2026, 1, 6), "10.20"),
                quote("AAPL", LocalDate.of(2026, 1, 7), "10.30"),
                quote("AAPL", LocalDate.of(2026, 1, 8), "10.40"),
                quote("AAPL", LocalDate.of(2026, 1, 9), "11.00")
        ));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getTriggerCount()).isEqualTo(1);
        assertThat(result.getAvgReturn()).isEqualByComparingTo("0.0985");
        assertThat(result.getResultJson()).contains(
                "\"returnSourceActualCount\":0",
                "\"returnSourceQuoteCount\":1",
                "\"skippedCount\":0");
        assertThat(candidateRuleMapper.updated.getFirst().getBacktestResult())
                .contains("\"avgReturn\":0.0985", "\"returnSourceQuoteCount\":1");
    }

    @Test
    void invalidCandidateProposedContentPersistsFailedReportAndCandidateStatus() {
        BacktestRequestDto request = request();
        request.setObjectType(RuleObjectType.CANDIDATE_RULE.getCode());
        request.setObjectCode("CR_20260620_0003");
        candidateRuleMapper.selectResponses.add(List.of(CandidateRule.builder()
                .id(23L)
                .candidateCode("CR_20260620_0003")
                .targetRuleCode("R_TREND_BREAKOUT_001")
                .proposedContent("当候选动量突破时提高看涨分")
                .build()));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getStatus()).isEqualTo(BacktestStatus.FAILED.getCode());
        assertThat(result.getTriggerCount()).isZero();
        assertThat(result.getResultJson()).contains("不是可执行 JSON", "\"riskDisclaimer\"");
        assertThat(backtestResultMapper.inserted).containsExactly(result);
        assertThat(factorMapper.selectCalls).isZero();
        CandidateRule updatedCandidate = candidateRuleMapper.updated.getFirst();
        assertThat(updatedCandidate.getBacktestStatus()).isEqualTo(BacktestStatus.FAILED.getCode());
        assertThat(updatedCandidate.getLatestBacktestReportId()).isEqualTo(result.getId());
        assertThat(updatedCandidate.getBacktestResult()).contains("\"backtestStatus\":\"failed\"", "不是可执行 JSON");
    }

    @Test
    void candidateRuleWithNaturalLanguageConditionObjectFailsInsteadOfRunningUnconditionally() {
        BacktestRequestDto request = request();
        request.setObjectType(RuleObjectType.CANDIDATE_RULE.getCode());
        request.setObjectCode("CR_20260620_0004");
        candidateRuleMapper.selectResponses.add(List.of(CandidateRule.builder()
                .id(24L)
                .candidateCode("CR_20260620_0004")
                .targetRuleCode("R_TREND_BREAKOUT_001")
                .proposedContent("""
                        {
                          "condition": "当候选动量突破时提高看涨分",
                          "actions": {
                            "bullish_score": 75
                          }
                        }
                        """)
                .build()));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getStatus()).isEqualTo(BacktestStatus.FAILED.getCode());
        assertThat(result.getResultJson()).contains("缺少 conditions 数组");
        assertThat(factorMapper.selectCalls).isZero();
        assertThat(candidateRuleMapper.updated.getFirst().getBacktestStatus()).isEqualTo(BacktestStatus.FAILED.getCode());
    }

    private BacktestRequestDto request() {
        BacktestRequestDto request = new BacktestRequestDto();
        request.setObjectType(RuleObjectType.RULE.getCode());
        request.setObjectCode("R_TREND_BREAKOUT_001");
        request.setStartDate(LocalDate.of(2026, 1, 1));
        request.setEndDate(LocalDate.of(2026, 1, 31));
        request.setHoldingPeriod(5);
        return request;
    }

    private StockSignalDaily signal(Long id, String symbol, LocalDate signalDate, String signal) {
        return signal(id, symbol, signalDate, signal, "[{\"rule_code\":\"R_TREND_BREAKOUT_001\"}]");
    }

    private StockSignalDaily signal(Long id, String symbol, LocalDate signalDate, String signal, String triggeredRules) {
        return StockSignalDaily.builder()
                .id(id)
                .symbol(symbol)
                .signalDate(signalDate)
                .signal(signal)
                .triggeredRules(triggeredRules)
                .build();
    }

    private StockFactorDaily factor(String symbol, LocalDate tradeDate, String factorJson) {
        return StockFactorDaily.builder()
                .symbol(symbol)
                .tradeDate(tradeDate)
                .factorJson(factorJson)
                .build();
    }

    private StockActualResult actual(String symbol, LocalDate signalDate, String return5d, boolean hit5d) {
        return StockActualResult.builder()
                .symbol(symbol)
                .signalDate(signalDate)
                .return5d(return5d == null ? null : new BigDecimal(return5d))
                .hit5d(hit5d)
                .build();
    }

    private StockDailyQuote quote(String symbol, LocalDate tradeDate, String closePrice) {
        return StockDailyQuote.builder()
                .symbol(symbol)
                .tradeDate(tradeDate)
                .closePrice(new BigDecimal(closePrice))
                .build();
    }

    @SuppressWarnings("unchecked")
    private <M, E> FakeMapper<M, E> fakeMapper(Class<M> mapperClass) {
        FakeMapper<M, E> fake = new FakeMapper<>();
        fake.mapper = (M) Proxy.newProxyInstance(
                mapperClass.getClassLoader(),
                new Class<?>[]{mapperClass},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        fake.selectCalls++;
                        return fake.selectResponses.isEmpty() ? List.of() : fake.selectResponses.remove();
                    }
                    if ("insert".equals(method.getName())) {
                        if (args[0] instanceof BacktestResult result && result.getId() == null) {
                            result.setId(1001L + fake.inserted.size());
                        }
                        fake.inserted.add((E) args[0]);
                        return 1;
                    }
                    if ("updateById".equals(method.getName())) {
                        fake.updated.add((E) args[0]);
                        return 1;
                    }
                    return null;
                }
        );
        return fake;
    }

    private static class FakeMapper<M, E> {
        private M mapper;
        private final Queue<List<E>> selectResponses = new ArrayDeque<>();
        private final List<E> inserted = new ArrayList<>();
        private final List<E> updated = new ArrayList<>();
        private int selectCalls;
    }
}
