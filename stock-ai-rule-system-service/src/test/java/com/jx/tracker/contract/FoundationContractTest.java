package com.jx.tracker.contract;

import com.baomidou.mybatisplus.annotation.TableName;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.ai.review.DefaultLlmClientConfig;
import com.jx.tracker.ai.review.LlmClient;
import com.jx.tracker.ai.review.MockLlmClient;
import com.jx.tracker.domain.entity.AiReviewReport;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.domain.entity.MarketDataSyncRun;
import com.jx.tracker.domain.entity.RuleOperationLog;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.RuleVersion;
import com.jx.tracker.domain.entity.StockActualResult;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.domain.entity.WorkflowRun;
import com.jx.tracker.domain.entity.WorkflowStepRun;
import com.jx.tracker.domain.enums.BacktestStatus;
import com.jx.tracker.domain.enums.CandidateRuleStatus;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.domain.enums.RuleVersionApprovalStatus;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.domain.enums.WorkflowRunStatus;
import com.jx.tracker.domain.enums.WorkflowStepStatus;
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
        Map<Class<?>, String> tableNames = Map.ofEntries(
                Map.entry(StockBase.class, "stock_base"),
                Map.entry(StockDailyQuote.class, "stock_daily_quote"),
                Map.entry(StockFactorDaily.class, "stock_factor_daily"),
                Map.entry(RuleDefinition.class, "rule_definition"),
                Map.entry(StockSignalDaily.class, "stock_signal_daily"),
                Map.entry(StockActualResult.class, "stock_actual_result"),
                Map.entry(AiReviewReport.class, "ai_review_report"),
                Map.entry(CandidateRule.class, "candidate_rule"),
                Map.entry(BacktestResult.class, "backtest_result"),
                Map.entry(TradeCalendar.class, "trade_calendar"),
                Map.entry(MarketDataSyncRun.class, "market_data_sync_run"),
                Map.entry(RuleVersion.class, "rule_version"),
                Map.entry(RuleOperationLog.class, "rule_operation_log"),
                Map.entry(WorkflowRun.class, "workflow_run"),
                Map.entry(WorkflowStepRun.class, "workflow_step_run")
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
        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS trade_calendar");
        assertThat(ddl).contains("UNIQUE KEY uk_trade_calendar_market_trade_date (market, trade_date)");
        assertThat(ddl).contains("UNIQUE KEY uk_stock_base_symbol (symbol)");
        assertThat(ddl).contains("UNIQUE KEY uk_rule_version_rule_id_version_no (rule_id, version_no)");
        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS market_data_sync_run");
        assertThat(ddl).contains("request_params JSON");
        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS rule_operation_log");
        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS workflow_run");
        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS workflow_step_run");
    }

    @Test
    void governanceAndWorkflowEnumsExposeStableLowercaseCodes() {
        assertThat(CandidateRuleStatus.codes()).containsExactly(
                "generated",
                "validated",
                "backtested",
                "pending_review",
                "approved",
                "rejected",
                "published",
                "disabled"
        );
        assertThat(BacktestStatus.codes()).containsExactly(
                "pending",
                "running",
                "success",
                "failed",
                "skipped"
        );
        assertThat(RuleVersionApprovalStatus.codes()).containsExactly(
                "pending",
                "approved",
                "rejected",
                "published",
                "rolled_back"
        );
        assertThat(WorkflowRunStatus.codes()).containsExactly(
                "pending",
                "running",
                "success",
                "failed",
                "skipped"
        );
        assertThat(WorkflowStepStatus.codes()).containsExactly(
                "pending",
                "running",
                "success",
                "failed",
                "skipped"
        );
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
