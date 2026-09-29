package com.jx.tracker.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.BacktestRequestDto;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.StockActualResult;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.enums.BacktestStatus;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.domain.enums.RuleObjectType;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.mapper.BacktestResultMapper;
import com.jx.tracker.mapper.CandidateRuleMapper;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.StockActualResultMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.rule.engine.DroolsRuleEngineExecutor;
import com.jx.tracker.rule.engine.JsonRuleEngineExecutor;
import com.jx.tracker.service.IStockFactorDailyService;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SingleRuleBacktestServiceTest {

    private final FakeMapper<StockSignalDailyMapper, StockSignalDaily> signalMapper = fakeMapper(StockSignalDailyMapper.class);
    private final FakeMapper<StockFactorDailyMapper, StockFactorDaily> factorMapper = fakeMapper(StockFactorDailyMapper.class);
    private final FakeMapper<StockActualResultMapper, StockActualResult> actualResultMapper = fakeMapper(StockActualResultMapper.class);
    private final FakeMapper<StockDailyQuoteMapper, StockDailyQuote> quoteMapper = fakeMapper(StockDailyQuoteMapper.class);
    private final FakeMapper<BacktestResultMapper, BacktestResult> backtestResultMapper = fakeMapper(BacktestResultMapper.class);
    private final FakeMapper<CandidateRuleMapper, CandidateRule> candidateRuleMapper = fakeMapper(CandidateRuleMapper.class);
    private final FakeMapper<RuleDefinitionMapper, RuleDefinition> ruleDefinitionMapper = fakeMapper(RuleDefinitionMapper.class);
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
    void emptySamplePersistsUnavailableMetricsWithDefaultCostSettings() {
        BacktestRequestDto request = request();
        signalMapper.selectResponses.add(List.of());

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getTriggerCount()).isZero();
        assertThat(result.getWinRate()).isNull();
        assertThat(result.getAvgReturn()).isNull();
        assertThat(result.getMaxDrawdown()).isNull();
        assertThat(result.getSharpeRatio()).isNull();
        assertThat(result.getTotalReturn()).isNull();
        assertThat(result.getStatus()).isEqualTo(BacktestStatus.SKIPPED.getCode());
        assertThat(result.getResultJson()).contains(
                "\"emptyReasonCode\":\"no_historical_quote\"",
                "\"emptyReason\":");
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
        assertThat(result.getStatus()).isEqualTo(BacktestStatus.SUCCESS.getCode());
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
    void watchOnlyReportCountsRuleMatchesWithoutTreatingWatchAsATrade() {
        BacktestRequestDto request = request();
        StockSignalDaily watch = signal(1L, "AAPL", LocalDate.of(2026, 1, 2), SignalType.WATCH.getCode());
        signalMapper.selectResponses.add(List.of(watch));
        actualResultMapper.selectResponses.add(List.of(actual("AAPL", LocalDate.of(2026, 1, 2), "0.0200", false)));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getTriggerCount()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(BacktestStatus.SKIPPED.getCode());
        assertThat(result.getWinRate()).isNull();
        assertThat(result.getAvgReturn()).isNull();
        assertThat(result.getMaxDrawdown()).isNull();
        assertThat(result.getTotalReturn()).isNull();
        assertThat(result.getResultJson()).contains(
                "\"statisticsVersion\":3", "\"evaluationBasis\":\"stored_signal\"",
                "\"signalCount\":1", "\"triggerCount\":1",
                "\"watchCount\":1", "\"directionalCount\":0", "\"evaluatedCount\":0",
                "\"undirectedCount\":1",
                "\"unevaluableCount\":0", "\"equityCurve\":[]",
                "\"emptyReasonCode\":\"watch_only\"");
        assertThat(quoteMapper.selectCalls).isZero();
        assertThat(actualResultMapper.selectCalls).isZero();
    }

    @Test
    void formalRuleDirectionEvaluatesBullishEvidenceEvenWhenFinalSignalIsWatch() {
        BacktestRequestDto request = request();
        request.setHoldingPeriod(1);
        LocalDate signalDate = LocalDate.of(2026, 1, 2);
        ruleDefinitionMapper.selectOneResponses.add(RuleDefinition.builder()
                .ruleCode(request.getObjectCode())
                .ruleName("趋势量能确认")
                .ruleFormat(RuleFormat.DROOLS.getCode())
                .status(RuleLifecycleStatus.ACTIVE.getCode())
                .ruleContent("""
                        import java.math.BigDecimal;
                        import com.jx.tracker.rule.engine.StockFactorFact;

                        rule "R_TREND_BREAKOUT_001"
                        when
                            $f : StockFactorFact(shortTermTrend == "up")
                        then
                            $f.addBullishScore(new BigDecimal("35"));
                            $f.addRiskScore(new BigDecimal("10"));
                            $f.addTriggeredRule("R_TREND_BREAKOUT_001");
                        end
                        """)
                .build());
        factorMapper.selectResponses.add(List.of(factor("AAPL", signalDate,
                "{\"data_status\":\"normal\",\"short_term_trend\":\"up\"}")));
        quoteMapper.selectResponses.add(List.of(
                quote("AAPL", signalDate, "10.00"),
                quote("AAPL", signalDate.plusDays(3), "11.00")));

        BacktestResult result = replayingRuleService().runSingleRuleBacktest(request);

        assertThat(result.getStatus()).isEqualTo(BacktestStatus.SUCCESS.getCode());
        assertThat(result.getTriggerCount()).isEqualTo(1);
        assertThat(result.getWinRate()).isEqualByComparingTo("1.0000");
        assertThat(result.getAvgReturn()).isEqualByComparingTo("0.0985");
        assertThat(result.getResultJson()).contains(
                "\"statisticsVersion\":3", "\"evaluationBasis\":\"rule_direction\"",
                "\"watchCount\":1", "\"directionalCount\":1", "\"undirectedCount\":0",
                "\"evaluatedCount\":1", "\"returnSourceQuoteCount\":1");
    }

    @Test
    void candidateRuleDirectionUsesRawReturnInsteadOfCachedWatchHit() {
        BacktestRequestDto request = candidateRequestWithActions("\"bearish_score\":35,\"risk_score\":10");
        request.setHoldingPeriod(1);
        LocalDate signalDate = LocalDate.of(2026, 1, 2);
        factorMapper.selectResponses.add(List.of(factor("AAPL", signalDate,
                "{\"data_status\":\"normal\",\"candidate_momentum\":\"breakout\"}")));
        StockActualResult actual = actual("AAPL", signalDate, null, false);
        actual.setReturn1d(new BigDecimal("-0.0200"));
        actual.setHit1d(false); // Cache was calculated from the final WATCH signal.
        actualResultMapper.selectResponses.add(List.of(actual));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getStatus()).isEqualTo(BacktestStatus.SUCCESS.getCode());
        assertThat(result.getWinRate()).isEqualByComparingTo("1.0000");
        assertThat(result.getAvgReturn()).isEqualByComparingTo("0.0185");
        assertThat(result.getResultJson()).contains(
                "\"evaluationBasis\":\"rule_direction\"", "\"watchCount\":1",
                "\"directionalCount\":1", "\"returnSourceActualCount\":1");
    }

    @Test
    void riskOnlyCandidateMatchHasNoInferredReturnDirection() {
        assertCandidateHasNoDirection("\"risk_score\":10");
    }

    @Test
    void conflictingBullishAndBearishCandidateMatchHasNoInferredReturnDirection() {
        assertCandidateHasNoDirection("\"bullish_score\":35,\"bearish_score\":35");
    }

    private void assertCandidateHasNoDirection(String actions) {
        BacktestRequestDto request = candidateRequestWithActions(actions);
        factorMapper.selectResponses.add(List.of(factor("AAPL", LocalDate.of(2026, 1, 2),
                "{\"data_status\":\"normal\",\"candidate_momentum\":\"breakout\"}")));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getStatus()).isEqualTo(BacktestStatus.SKIPPED.getCode());
        assertThat(result.getTriggerCount()).isEqualTo(1);
        assertThat(result.getWinRate()).isNull();
        assertThat(result.getAvgReturn()).isNull();
        assertThat(result.getResultJson()).contains(
                "\"evaluationBasis\":\"rule_direction\"", "\"directionalCount\":0",
                "\"undirectedCount\":1", "\"evaluatedCount\":0",
                "\"emptyReasonCode\":\"no_rule_direction\"");
        assertThat(quoteMapper.selectCalls).isZero();
        assertThat(actualResultMapper.selectCalls).isZero();
    }

    private BacktestRequestDto candidateRequestWithActions(String actions) {
        BacktestRequestDto request = request();
        request.setObjectType(RuleObjectType.CANDIDATE_RULE.getCode());
        request.setObjectCode("CR_DIRECTION_TEST");
        candidateRuleMapper.selectResponses.add(List.of(CandidateRule.builder()
                .id(81L)
                .candidateCode(request.getObjectCode())
                .proposedContent("""
                        {"conditions":[{"field":"candidate_momentum","operator":"eq","value":"breakout"}],
                         "actions":{%s}}
                        """.formatted(actions))
                .build()));
        return request;
    }

    private SingleRuleBacktestService replayingRuleService() {
        return new SingleRuleBacktestService(
                signalMapper.mapper, factorMapper.mapper, actualResultMapper.mapper,
                quoteMapper.mapper, backtestResultMapper.mapper, candidateRuleMapper.mapper,
                new DroolsRuleEngineExecutor(), new SignalScoringService(), new PredictionHitPolicy(),
                null, ruleDefinitionMapper.mapper, null, null, null);
    }

    @Test
    void mixedSignalsExcludeWatchFromReturnAndKeepMissingDirectionSeparate() {
        BacktestRequestDto request = request();
        LocalDate first = LocalDate.of(2026, 1, 2);
        LocalDate second = LocalDate.of(2026, 1, 3);
        LocalDate third = LocalDate.of(2026, 1, 4);
        signalMapper.selectResponses.add(List.of(
                signal(1L, "AAPL", first, SignalType.WATCH.getCode()),
                signal(2L, "MSFT", second, SignalType.BULLISH.getCode()),
                signal(3L, "NVDA", third, SignalType.BEARISH.getCode())));
        actualResultMapper.selectResponses.add(List.of(actual("MSFT", second, "0.0200", true)));
        actualResultMapper.selectResponses.add(List.of(actual("NVDA", third, null, false)));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getStatus()).isEqualTo(BacktestStatus.SUCCESS.getCode());
        assertThat(result.getTriggerCount()).isEqualTo(3);
        assertThat(result.getWinRate()).isEqualByComparingTo("1.0000");
        assertThat(result.getAvgReturn()).isEqualByComparingTo("0.0185");
        assertThat(result.getTotalReturn()).isEqualByComparingTo("0.0185");
        assertThat(result.getResultJson()).contains(
                "\"signalCount\":3", "\"triggerCount\":3", "\"watchCount\":1",
                "\"directionalCount\":2", "\"evaluatedCount\":1", "\"unevaluableCount\":1",
                "\"returnSourceActualCount\":1");
        assertThat(actualResultMapper.selectCalls).isEqualTo(2);
    }

    @Test
    void unevaluableSamplesAreReportedInResultJson() {
        BacktestRequestDto request = request();
        StockSignalDaily tailSample = signal(1L, "AAPL", LocalDate.of(2026, 1, 30), SignalType.BULLISH.getCode());
        signalMapper.selectResponses.add(List.of(tailSample));
        actualResultMapper.selectResponses.add(List.of(actual("AAPL", LocalDate.of(2026, 1, 30), null, false)));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getTriggerCount()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(BacktestStatus.SKIPPED.getCode());
        assertThat(result.getResultJson()).contains("\"emptyReasonCode\":\"no_forward_quote\"");
        assertThat(result.getAvgReturn()).isNull();
        assertThat(result.getResultJson()).contains("\"signalCount\":1", "\"directionalCount\":1",
                "\"skippedCount\":1", "\"unevaluableCount\":1", "\"evaluatedCount\":0");
    }

    @Test
    void candidateBacktestExplainsUnusableHistoricalFactorsInsteadOfReportingSuccess() {
        BacktestRequestDto request = candidateRequest();
        factorMapper.selectResponses.add(List.of(factor("AAPL", LocalDate.of(2026, 1, 2), """
                {"data_status":"insufficient_data","short_term_trend":"unknown"}
                """)));
        quoteMapper.selectResponses.add(List.of(quote("AAPL", LocalDate.of(2026, 1, 2), "10.00")));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getStatus()).isEqualTo(BacktestStatus.SKIPPED.getCode());
        assertThat(result.getTriggerCount()).isZero();
        assertThat(result.getResultJson()).contains("\"emptyReasonCode\":\"no_usable_factor\"");
        assertThat(candidateRuleMapper.updated.getFirst().getBacktestStatus())
                .isEqualTo(BacktestStatus.SKIPPED.getCode());
        assertThat(candidateRuleMapper.updated.getFirst().getBacktestResult())
                .contains("\"emptyReasonCode\":\"no_usable_factor\"");
    }

    @Test
    void candidateBacktestExplainsRuleNotTriggeredWhenFactorsAreUsable() {
        BacktestRequestDto request = candidateRequest();
        factorMapper.selectResponses.add(List.of(factor("AAPL", LocalDate.of(2026, 1, 2), """
                {"data_status":"normal","candidate_momentum":"flat"}
                """)));
        quoteMapper.selectResponses.add(List.of(quote("AAPL", LocalDate.of(2026, 1, 2), "10.00")));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getStatus()).isEqualTo(BacktestStatus.SKIPPED.getCode());
        assertThat(result.getResultJson()).contains("\"emptyReasonCode\":\"rule_not_triggered\"");
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
    void formalRuleBacktestFallsBackToDailyQuotesWhenActualResultIsMissing() {
        BacktestRequestDto request = request();
        StockSignalDaily bullish = signal(1L, "AAPL", LocalDate.of(2026, 1, 2), SignalType.BULLISH.getCode());
        signalMapper.selectResponses.add(List.of(bullish));
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
    }

    @Test
    void historicalBacktestPrefersFreshForwardQuoteOverStaleActualReturn() {
        BacktestRequestDto request = request();
        request.setHoldingPeriod(1);
        LocalDate signalDate = LocalDate.of(2026, 1, 2);
        signalMapper.selectResponses.add(List.of(signal(1L, "AAPL", signalDate, SignalType.BULLISH.getCode())));
        quoteMapper.selectResponses.add(List.of(
                quote("AAPL", signalDate, "10.00"),
                quote("AAPL", signalDate.plusDays(3), "11.00")));
        StockActualResult stale = actual("AAPL", signalDate, null, false);
        stale.setReturn1d(new BigDecimal("-0.2000"));
        actualResultMapper.selectResponses.add(List.of(stale));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getAvgReturn()).isEqualByComparingTo("0.0985");
        assertThat(result.getResultJson()).contains("\"returnSourceQuoteCount\":1");
        assertThat(actualResultMapper.selectCalls).isZero();
    }

    @Test
    void forwardReturnRequiresQuoteOnSignalDate() {
        BacktestRequestDto request = request();
        request.setHoldingPeriod(1);
        LocalDate signalDate = LocalDate.of(2026, 1, 2);
        signalMapper.selectResponses.add(List.of(signal(1L, "AAPL", signalDate, SignalType.BULLISH.getCode())));
        quoteMapper.selectResponses.add(List.of(
                quote("AAPL", signalDate.plusDays(3), "11.00"),
                quote("AAPL", signalDate.plusDays(4), "12.00")));
        actualResultMapper.selectResponses.add(List.of(actual("AAPL", signalDate, null, false)));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getTriggerCount()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo(BacktestStatus.SKIPPED.getCode());
        assertThat(result.getResultJson()).contains("\"returnSourceQuoteCount\":0");
    }

    @Test
    void forwardReturnDoesNotMixQuoteProviders() {
        BacktestRequestDto request = request();
        request.setHoldingPeriod(1);
        LocalDate signalDate = LocalDate.of(2026, 1, 2);
        signalMapper.selectResponses.add(List.of(signal(1L, "AAPL", signalDate, SignalType.BULLISH.getCode())));
        StockDailyQuote base = quote("AAPL", signalDate, "10.00");
        base.setDataSource("aktools/akshare");
        StockDailyQuote forward = quote("AAPL", signalDate.plusDays(3), "11.00");
        forward.setDataSource("tushare");
        quoteMapper.selectResponses.add(List.of(base, forward));
        actualResultMapper.selectResponses.add(List.of(actual("AAPL", signalDate, null, false)));

        BacktestResult result = service.runSingleRuleBacktest(request);

        assertThat(result.getTriggerCount()).isEqualTo(1);
        assertThat(result.getResultJson()).contains("\"returnSourceQuoteCount\":0");
    }

    @Test
    void customStockPoolFiltersSignalsAndFillsMissingHistoricalFactors() {
        BacktestRequestDto request = request();
        request.setStockPoolType("custom");
        request.setPoolCode("research-small");
        request.setSymbols(List.of("AAPL"));

        LocalDate signalDate = LocalDate.of(2026, 1, 2);
        quoteMapper.selectResponses.add(List.of(quote("AAPL", signalDate, "10.00")));
        factorMapper.selectResponses.add(List.of());
        signalMapper.selectResponses.add(List.of(
                signal(1L, "AAPL", signalDate, SignalType.BULLISH.getCode()),
                signal(2L, "MSFT", signalDate, SignalType.BULLISH.getCode())
        ));
        actualResultMapper.selectResponses.add(List.of(actual("AAPL", signalDate, "0.0200", true)));

        AtomicInteger factorCalculations = new AtomicInteger();
        IStockFactorDailyService factorService = (IStockFactorDailyService) Proxy.newProxyInstance(
                IStockFactorDailyService.class.getClassLoader(),
                new Class<?>[]{IStockFactorDailyService.class},
                (proxy, method, args) -> {
                    if ("calculateAndSave".equals(method.getName())) {
                        factorCalculations.incrementAndGet();
                    }
                    return null;
                }
        );
        SingleRuleBacktestService scopedService = new SingleRuleBacktestService(
                signalMapper.mapper,
                factorMapper.mapper,
                actualResultMapper.mapper,
                quoteMapper.mapper,
                backtestResultMapper.mapper,
                candidateRuleMapper.mapper,
                new JsonRuleEngineExecutor(),
                new SignalScoringService(),
                new PredictionHitPolicy(),
                factorService,
                null,
                null,
                null,
                null
        );

        BacktestResult result = scopedService.runSingleRuleBacktest(request);

        assertThat(result.getTriggerCount()).isEqualTo(1);
        assertThat(factorCalculations).hasValue(1);
        assertThat(result.getResultJson()).contains(
                "\"stockPoolType\":\"custom\"",
                "\"stockPoolCode\":\"research-small\"");
    }

    @Test
    void recalculatesInsufficientHistoricalFactorAfterEnoughPastQuotesBecomeAvailable() {
        assertInsufficientFactorRecalculation(26, 1);
    }

    @Test
    void doesNotRecalculateInsufficientFactorWhenOnlyFutureQuoteReachesHistoryThreshold() {
        assertInsufficientFactorRecalculation(25, 0);
    }

    @Test
    void recalculatesPreviouslyNormalFactorWhenHistoricalQuotesWereUpdated() {
        BacktestRequestDto request = request();
        request.setStockPoolType("custom");
        request.setSymbols(List.of("AAPL"));
        request.setForceFactorRecalculation(true);
        LocalDate targetDate = LocalDate.of(2026, 1, 30);
        StockDailyQuote targetQuote = quote("AAPL", targetDate, "10.00");
        targetQuote.setVolume(BigDecimal.ONE);
        quoteMapper.selectResponses.add(List.of(targetQuote));
        factorMapper.selectResponses.add(List.of(factor("AAPL", targetDate,
                "{\"data_status\":\"normal\",\"ma5\":9.0000}")));
        signalMapper.selectResponses.add(List.of(signal(1L, "AAPL", targetDate, SignalType.BULLISH.getCode())));
        actualResultMapper.selectResponses.add(List.of(actual("AAPL", targetDate, "0.0200", true)));

        AtomicInteger factorCalculations = new AtomicInteger();
        IStockFactorDailyService factorService = (IStockFactorDailyService) Proxy.newProxyInstance(
                IStockFactorDailyService.class.getClassLoader(),
                new Class<?>[]{IStockFactorDailyService.class},
                (proxy, method, args) -> {
                    if ("calculateAndSave".equals(method.getName())) {
                        factorCalculations.incrementAndGet();
                    }
                    return null;
                });
        SingleRuleBacktestService scopedService = new SingleRuleBacktestService(
                signalMapper.mapper, factorMapper.mapper, actualResultMapper.mapper,
                quoteMapper.mapper, backtestResultMapper.mapper, candidateRuleMapper.mapper,
                new JsonRuleEngineExecutor(), new SignalScoringService(), new PredictionHitPolicy(),
                factorService, null, null, null, null);

        BacktestResult result = scopedService.runSingleRuleBacktest(request);

        assertThat(result.getTriggerCount()).isEqualTo(1);
        assertThat(actualResultMapper.selectCalls).isZero();
        assertThat(factorCalculations).hasValue(1);
    }

    @Test
    void clientsCannotRequestForcedFactorRecalculation() throws Exception {
        BacktestRequestDto request = new ObjectMapper().readValue(
                "{\"forceFactorRecalculation\":true,\"force_factor_recalculation\":true}",
                BacktestRequestDto.class);

        assertThat(request.isForceFactorRecalculation()).isFalse();
    }

    private void assertInsufficientFactorRecalculation(int pastQuoteCount, int expectedCalculations) {
        BacktestRequestDto request = request();
        request.setStockPoolType("custom");
        request.setSymbols(List.of("AAPL"));
        LocalDate targetDate = LocalDate.of(2026, 1, 30);
        StockDailyQuote targetQuote = quote("AAPL", targetDate, "10.00");
        targetQuote.setVolume(BigDecimal.ONE);
        quoteMapper.selectResponses.add(List.of(targetQuote));
        factorMapper.selectResponses.add(List.of(factor("AAPL", targetDate,
                "{\"data_status\":\"insufficient_data\",\"short_term_trend\":\"unknown\"}")));
        List<StockDailyQuote> lookback = new ArrayList<>();
        for (int daysAgo = 0; daysAgo < pastQuoteCount; daysAgo++) {
            StockDailyQuote historical = quote("AAPL", targetDate.minusDays(daysAgo), "10.00");
            historical.setVolume(BigDecimal.ONE);
            lookback.add(historical);
        }
        StockDailyQuote future = quote("AAPL", targetDate.plusDays(1), "10.00");
        future.setVolume(BigDecimal.ONE);
        lookback.add(future);
        quoteMapper.selectResponses.add(lookback);
        signalMapper.selectResponses.add(List.of(signal(1L, "AAPL", targetDate, SignalType.BULLISH.getCode())));
        actualResultMapper.selectResponses.add(List.of(actual("AAPL", targetDate, "0.0200", true)));

        AtomicInteger factorCalculations = new AtomicInteger();
        IStockFactorDailyService factorService = (IStockFactorDailyService) Proxy.newProxyInstance(
                IStockFactorDailyService.class.getClassLoader(),
                new Class<?>[]{IStockFactorDailyService.class},
                (proxy, method, args) -> {
                    if ("calculateAndSave".equals(method.getName())) {
                        factorCalculations.incrementAndGet();
                    }
                    return null;
                });
        SingleRuleBacktestService scopedService = new SingleRuleBacktestService(
                signalMapper.mapper, factorMapper.mapper, actualResultMapper.mapper,
                quoteMapper.mapper, backtestResultMapper.mapper, candidateRuleMapper.mapper,
                new JsonRuleEngineExecutor(), new SignalScoringService(), new PredictionHitPolicy(),
                factorService, null, null, null, null);

        scopedService.runSingleRuleBacktest(request);

        assertThat(factorCalculations).hasValue(expectedCalculations);
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
        assertThat(result.getWinRate()).isNull();
        assertThat(result.getAvgReturn()).isNull();
        assertThat(result.getTotalReturn()).isNull();
        assertThat(result.getSharpeRatio()).isNull();
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

    private BacktestRequestDto candidateRequest() {
        BacktestRequestDto request = request();
        request.setObjectType(RuleObjectType.CANDIDATE_RULE.getCode());
        request.setObjectCode("CR_EMPTY_DIAGNOSTIC");
        candidateRuleMapper.selectResponses.add(List.of(CandidateRule.builder()
                .id(31L)
                .candidateCode("CR_EMPTY_DIAGNOSTIC")
                .targetRuleCode("R_TREND_BREAKOUT_001")
                .proposedContent("""
                        {"conditions":[{"field":"candidate_momentum","operator":"eq","value":"breakout"}],
                         "actions":{"bullish_score":75}}
                        """)
                .build()));
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
                    if ("selectOne".equals(method.getName())) {
                        fake.selectCalls++;
                        return fake.selectOneResponses.poll();
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
        private final Queue<E> selectOneResponses = new ArrayDeque<>();
        private final List<E> inserted = new ArrayList<>();
        private final List<E> updated = new ArrayList<>();
        private int selectCalls;
    }
}
