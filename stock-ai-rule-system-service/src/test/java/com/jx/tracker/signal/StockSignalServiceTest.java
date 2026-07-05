package com.jx.tracker.signal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.rule.engine.JsonRuleEngineExecutor;
import com.jx.tracker.rule.engine.RuleEngineExecutor;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import com.jx.tracker.signal.service.SignalScoringService;
import com.jx.tracker.signal.service.StockSignalService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StockSignalServiceTest {

    @Test
    void generatesAndSavesDailySignalWithTriggeredRulesExplanationAndRiskDisclaimer() {
        RuleDefinition bullishRule = rule("R_TREND_BREAKOUT_001", 100, """
                {
                  "conditions": [
                    {"field": "short_term_trend", "operator": "eq", "value": "strong_up"},
                    {"field": "volume_status", "operator": "eq", "value": "abnormal_high"},
                    {"field": "market_status", "operator": "ne", "value": "weak"}
                  ],
                  "actions": {
                    "bullish_score": 75,
                    "explanation": "放量突破且市场环境未明显走弱"
                  }
                }
                """);
        RuleDefinitionMapper ruleDefinitionMapper = fakeRuleDefinitionMapper(List.of(bullishRule));
        AtomicReference<StockSignalDaily> insertedSignal = new AtomicReference<>();
        StockSignalDailyMapper stockSignalDailyMapper = fakeStockSignalDailyMapper(insertedSignal);

        StockSignalService service = new StockSignalService(
                ruleDefinitionMapper,
                fakeStockFactorDailyMapper(List.of()),
                stockSignalDailyMapper,
                new JsonRuleEngineExecutor(),
                new SignalScoringService()
        );

        StockSignalDaily saved = service.generateDailySignal(
                "AAPL",
                LocalDate.of(2026, 6, 20),
                Map.of(
                        "short_term_trend", "strong_up",
                        "volume_status", "abnormal_high",
                        "market_status", "strong"
                )
        );

        StockSignalDaily inserted = insertedSignal.get();

        assertThat(saved).isSameAs(inserted);
        assertThat(inserted.getSymbol()).isEqualTo("AAPL");
        assertThat(inserted.getSignalDate()).isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(inserted.getSignal()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(inserted.getSignalLevel()).isEqualTo("强看涨");
        assertThat(inserted.getBullishScore()).isEqualByComparingTo(new BigDecimal("75"));
        assertThat(inserted.getTriggeredRules()).contains("R_TREND_BREAKOUT_001");
        assertThat(inserted.getExplanation()).contains("放量突破且市场环境未明显走弱");
        assertThat(inserted.getRiskDisclaimer()).isEqualTo(StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
    }

    @Test
    void generatesSignalsFromPersistedFactorSnapshots() {
        RuleDefinition bullishRule = rule("R_TREND_BREAKOUT_001", 100, """
                {
                  "conditions": [
                    {"field": "short_term_trend", "operator": "eq", "value": "strong_up"},
                    {"field": "volume_status", "operator": "eq", "value": "abnormal_high"}
                  ],
                  "actions": {
                    "bullish_score": 75,
                    "explanation": "因子快照触发强势规则"
                  }
                }
                """);
        AtomicReference<StockSignalDaily> insertedSignal = new AtomicReference<>();
        StockSignalService service = new StockSignalService(
                fakeRuleDefinitionMapper(List.of(bullishRule)),
                fakeStockFactorDailyMapper(List.of(StockFactorDaily.builder()
                        .symbol("AAPL")
                        .tradeDate(LocalDate.of(2026, 6, 20))
                        .factorJson("""
                                {"short_term_trend":"strong_up","volume_status":"abnormal_high"}
                                """)
                        .build())),
                fakeStockSignalDailyMapper(insertedSignal),
                new JsonRuleEngineExecutor(),
                new SignalScoringService()
        );

        List<StockSignalDaily> saved = service.generateDailySignalsFromFactors(
                LocalDate.of(2026, 6, 20),
                List.of("AAPL")
        );

        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getSignal()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(saved.getFirst().getExplanation()).contains("因子快照触发强势规则");
    }

    @Test
    void passesActiveJsonAndDroolsRulesToRuleEngineForSameFactorSnapshot() {
        RuleDefinition jsonRule = rule("R_JSON_TREND_001", 100, """
                {
                  "conditions": [{"field": "short_term_trend", "operator": "eq", "value": "strong_up"}],
                  "actions": {"bullish_score": 25, "explanation": "JSON 规则识别趋势偏强"}
                }
                """);
        RuleDefinition droolsRule = rule("R_DROOLS_TREND_001", 90, """
                import java.math.BigDecimal;
                import com.jx.tracker.rule.engine.StockFactorFact;

                rule "R_DROOLS_TREND_001"
                when
                    $f : StockFactorFact(shortTermTrend == "strong_up")
                then
                    $f.addBullishScore(new BigDecimal("35"));
                    $f.addTriggeredRule("R_DROOLS_TREND_001");
                    $f.addExplanation("Drools 规则补充趋势信号");
                end
                """);
        droolsRule.setRuleFormat(RuleFormat.DROOLS.getCode());

        CapturingRuleEngineExecutor ruleEngineExecutor = new CapturingRuleEngineExecutor();
        AtomicReference<StockSignalDaily> insertedSignal = new AtomicReference<>();
        StockSignalService service = new StockSignalService(
                fakeRuleDefinitionMapperApplyingRuleFormatFilter(List.of(jsonRule, droolsRule)),
                fakeStockFactorDailyMapper(List.of()),
                fakeStockSignalDailyMapper(insertedSignal),
                ruleEngineExecutor,
                new SignalScoringService()
        );

        StockSignalDaily saved = service.generateDailySignal(
                "AAPL",
                LocalDate.of(2026, 6, 20),
                Map.of("short_term_trend", "strong_up")
        );

        assertThat(ruleEngineExecutor.ruleCodes()).containsExactly("R_JSON_TREND_001", "R_DROOLS_TREND_001");
        assertThat(saved.getTriggeredRules()).contains("R_JSON_TREND_001", "R_DROOLS_TREND_001");
        assertThat(saved.getRiskDisclaimer()).isEqualTo(StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
    }

    @SuppressWarnings("unchecked")
    private RuleDefinitionMapper fakeRuleDefinitionMapper(List<RuleDefinition> rules) {
        return (RuleDefinitionMapper) Proxy.newProxyInstance(
                RuleDefinitionMapper.class.getClassLoader(),
                new Class<?>[]{RuleDefinitionMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return rules;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private StockSignalDailyMapper fakeStockSignalDailyMapper(AtomicReference<StockSignalDaily> insertedSignal) {
        return (StockSignalDailyMapper) Proxy.newProxyInstance(
                StockSignalDailyMapper.class.getClassLoader(),
                new Class<?>[]{StockSignalDailyMapper.class},
                (proxy, method, args) -> {
                    if ("selectOne".equals(method.getName())) {
                        return null;
                    }
                    if ("insert".equals(method.getName())) {
                        insertedSignal.set((StockSignalDaily) args[0]);
                        return 1;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    @SuppressWarnings("unchecked")
    private RuleDefinitionMapper fakeRuleDefinitionMapperApplyingRuleFormatFilter(List<RuleDefinition> rules) {
        return (RuleDefinitionMapper) Proxy.newProxyInstance(
                RuleDefinitionMapper.class.getClassLoader(),
                new Class<?>[]{RuleDefinitionMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        initializeRuleDefinitionTableInfo();
                        String sqlSegment = args == null || args.length == 0 || args[0] == null
                                ? ""
                                : String.valueOf(args[0].getClass().getMethod("getSqlSegment").invoke(args[0]));
                        List<RuleDefinition> activeRules = rules.stream()
                                .filter(rule -> RuleLifecycleStatus.ACTIVE.getCode().equals(rule.getStatus()))
                                .sorted((left, right) -> Integer.compare(
                                        right.getPriority() == null ? 0 : right.getPriority(),
                                        left.getPriority() == null ? 0 : left.getPriority()))
                                .toList();
                        if (sqlSegment.contains("rule_format")) {
                            return activeRules.stream()
                                    .filter(rule -> RuleFormat.JSON.getCode().equals(rule.getRuleFormat()))
                                    .toList();
                        }
                        return activeRules;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private void initializeRuleDefinitionTableInfo() {
        if (TableInfoHelper.getTableInfo(RuleDefinition.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MybatisMapperBuilderAssistant(new MybatisConfiguration(), ""),
                    RuleDefinition.class
            );
        }
    }

    @SuppressWarnings("unchecked")
    private StockFactorDailyMapper fakeStockFactorDailyMapper(List<StockFactorDaily> factors) {
        return (StockFactorDailyMapper) Proxy.newProxyInstance(
                StockFactorDailyMapper.class.getClassLoader(),
                new Class<?>[]{StockFactorDailyMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return factors;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private RuleDefinition rule(String code, int priority, String content) {
        return RuleDefinition.builder()
                .ruleCode(code)
                .ruleName(code)
                .ruleType("trend")
                .ruleFormat(RuleFormat.JSON.getCode())
                .status(RuleLifecycleStatus.ACTIVE.getCode())
                .priority(priority)
                .ruleContent(content)
                .build();
    }

    private static class CapturingRuleEngineExecutor implements RuleEngineExecutor {

        private final List<RuleDefinition> capturedRules = new ArrayList<>();

        @Override
        public RuleExecutionResult execute(com.jx.tracker.rule.engine.RuleExecutionRequest request) {
            capturedRules.clear();
            capturedRules.addAll(request.rules());
            return new RuleExecutionResult(
                    new BigDecimal("60"),
                    BigDecimal.ZERO,
                    new BigDecimal("20"),
                    List.of("R_JSON_TREND_001", "R_DROOLS_TREND_001"),
                    List.of("JSON 规则识别趋势偏强", "Drools 规则补充趋势信号")
            );
        }

        private List<String> ruleCodes() {
            return capturedRules.stream()
                    .map(RuleDefinition::getRuleCode)
                    .toList();
        }
    }
}
