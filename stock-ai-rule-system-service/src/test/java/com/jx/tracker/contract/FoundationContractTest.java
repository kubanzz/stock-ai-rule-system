package com.jx.tracker.contract;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.ai.review.DefaultLlmClientConfig;
import com.jx.tracker.ai.review.LlmClient;
import com.jx.tracker.ai.review.MockLlmClient;
import com.jx.tracker.domain.entity.AiReviewReport;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.StockActualResult;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.domain.enums.SignalType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FoundationContractTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void signalTypeCodesMatchPublicContract() {
        assertThat(SignalType.BULLISH.getCode()).isEqualTo("bullish");
        assertThat(SignalType.BEARISH.getCode()).isEqualTo("bearish");
        assertThat(SignalType.WATCH.getCode()).isEqualTo("watch");
        assertThat(SignalType.HIGH_RISK.getCode()).isEqualTo("high_risk");
    }

    @Test
    void ruleLifecycleStatusCoversAiGovernanceFlow() {
        assertThat(RuleLifecycleStatus.codes()).containsExactly(
                "draft",
                "candidate",
                "backtesting",
                "paper_trade",
                "approved",
                "active",
                "disabled",
                "archived"
        );
    }

    @Test
    void riskDisclaimerKeepsSignalsAsDecisionSupport() {
        assertThat(StockRiskConstants.SIGNAL_RISK_DISCLAIMER)
                .contains("辅助决策")
                .contains("不构成投资建议")
                .contains("不保证收益");
    }

    @Test
    void coreEntitiesDeclareStableTableNames() {
        Map<Class<?>, String> tableNames = Map.of(
                StockBase.class, "stock_base",
                StockDailyQuote.class, "stock_daily_quote",
                StockFactorDaily.class, "stock_factor_daily",
                RuleDefinition.class, "rule_definition",
                StockSignalDaily.class, "stock_signal_daily",
                StockActualResult.class, "stock_actual_result",
                AiReviewReport.class, "ai_review_report",
                CandidateRule.class, "candidate_rule",
                BacktestResult.class, "backtest_result"
        );

        tableNames.forEach((entityClass, expectedTableName) -> assertThat(entityClass.getAnnotation(TableName.class).value())
                .isEqualTo(expectedTableName));
    }

    @Test
    void mysqlSchemaDoesNotUsePostgresqlOnlyTypes() throws IOException {
        String ddl = new String(getClass().getResourceAsStream("/db/stock_ai_rule_schema.sql").readAllBytes(), StandardCharsets.UTF_8);

        assertThat(ddl).doesNotContain("BIGSERIAL", "JSONB");
        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS stock_signal_daily");
        assertThat(ddl).contains("triggered_rules JSON");
        assertThat(ddl).contains("UNIQUE KEY uk_stock_daily_quote_symbol_trade_date");
    }

    @Test
    void defaultLlmClientFallsBackToMockWhenNoRealClientIsConfigured() {
        contextRunner
                .withUserConfiguration(DefaultLlmClientConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(LlmClient.class);
                    assertThat(context.getBean(LlmClient.class)).isInstanceOf(MockLlmClient.class);
                });
    }
}
