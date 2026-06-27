package com.jx.tracker.signal;

import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.rule.engine.JsonRuleEngineExecutor;
import com.jx.tracker.signal.service.SignalScoringService;
import com.jx.tracker.signal.service.StockSignalService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
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
}
