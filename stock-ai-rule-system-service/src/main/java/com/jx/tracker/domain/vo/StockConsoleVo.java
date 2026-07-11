package com.jx.tracker.domain.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class StockConsoleVo {

    private StockConsoleVo() {
    }

    public record MetricCard(String label, BigDecimal value, String unit, BigDecimal change, String tone) {
    }

    public record SparkPoint(String label, BigDecimal value) {
    }

    public record SignalRow(
            String symbol,
            String name,
            BigDecimal price,
            BigDecimal changePct,
            String signal,
            BigDecimal bullishScore,
            BigDecimal bearishScore,
            BigDecimal riskScore,
            BigDecimal confidence,
            int triggeredRuleCount,
            String suggestedPeriod,
            LocalDateTime updatedAt
    ) {
    }

    public record MarketContext(
            String indexName,
            BigDecimal indexValue,
            BigDecimal changePct,
            String status,
            List<SparkPoint> trend
    ) {
    }

    public record SignalDashboardOverview(
            LocalDate tradeDate,
            String riskDisclaimer,
            List<MetricCard> metrics,
            List<SignalRow> signals,
            MarketContext marketContext,
            long total
    ) {
    }

    public record WatchlistStock(
            String symbol,
            String name,
            String market,
            String industry,
            String groupName,
            Boolean selected
    ) {
    }

    public record WatchlistPool(String poolId, String poolName, String market, long total, List<WatchlistStock> stocks) {
    }

    public record WatchlistMutationRequest(String poolName, String market) {
    }

    public record WatchlistStockMutationRequest(String symbol, String groupName) {
    }

    public record PricePoint(LocalDate date, BigDecimal close, BigDecimal volume) {
    }

    public record FactorState(String factor, String value, String status, BigDecimal strength, String description) {
    }

    public record RuleContribution(String ruleCode, String ruleName, String condition, BigDecimal contribution) {
    }

    public record PredictionRecord(
            LocalDate date,
            String signal,
            String direction,
            BigDecimal confidence,
            BigDecimal actualReturn,
            String hitStatus,
            List<String> triggeredRules
    ) {
    }

    public record StockResearchDetail(
            String symbol,
            String name,
            String market,
            String industry,
            LocalDate tradeDate,
            String signal,
            BigDecimal confidence,
            BigDecimal riskScore,
            String riskDisclaimer,
            String explanation,
            List<PricePoint> priceSeries,
            List<FactorState> factors,
            List<RuleContribution> ruleChain,
            List<PredictionRecord> history
    ) {
    }

    public record RuleSummary(
            String ruleCode,
            String ruleName,
            String ruleType,
            String version,
            String status,
            long triggerCount30d,
            BigDecimal winRate5d,
            BigDecimal avgReturn,
            BigDecimal maxDrawdown,
            LocalDateTime updatedAt
    ) {
    }

    public record RuleGovernanceOverview(
            String riskDisclaimer,
            List<MetricCard> metrics,
            List<RuleSummary> rules
    ) {
    }

    public record VersionDiff(String currentContent, String proposedContent, List<String> highlights) {
    }

    public record RuleGovernanceDetail(
            String ruleCode,
            String ruleName,
            String ruleType,
            String version,
            String status,
            String source,
            String description,
            String expression,
            List<String> relatedFactors,
            List<MetricCard> performance,
            VersionDiff candidateDiff
    ) {
    }

    public record SeriesPoint(LocalDate date, BigDecimal value) {
    }

    public record ComparisonMetric(String metric, BigDecimal currentValue, BigDecimal candidateValue, String winner) {
    }

    public record BacktestFailureSample(
            String date,
            String symbol,
            String signal,
            BigDecimal actualReturn,
            String reasonCategory,
            String relatedRule
    ) {
    }

    public record BacktestReportOverview(
            String riskDisclaimer,
            List<MetricCard> metrics,
            List<SeriesPoint> cumulativeReturns,
            List<ComparisonMetric> comparison,
            List<BacktestFailureSample> failureSamples
    ) {
    }

    public record BacktestReportDetail(
            String reportId,
            String objectCode,
            String objectType,
            LocalDate startDate,
            LocalDate endDate,
            List<MetricCard> metrics,
            List<SeriesPoint> cumulativeReturns,
            List<BacktestFailureSample> failureSamples
    ) {
    }

    public record ErrorCluster(String reasonCategory, long count, BigDecimal ratio) {
    }

    public record MisjudgementSample(
            String sampleId,
            String symbol,
            LocalDate predictionDate,
            String predictedSignal,
            BigDecimal actualReturn,
            String reasonCategory,
            String triggeredRule,
            String status
    ) {
    }

    public record CandidateRuleSuggestion(
            String candidateCode,
            String targetRuleCode,
            String oldCondition,
            String proposedCondition,
            String priority,
            String actionRequired
    ) {
    }

    public record AiReviewOverview(
            LocalDate reviewDate,
            String riskDisclaimer,
            List<MetricCard> metrics,
            List<ErrorCluster> errorClusters,
            List<MisjudgementSample> misjudgements,
            CandidateRuleSuggestion candidateSuggestion
    ) {
    }

    public record RunStep(String stepCode, String stepName, String status, LocalDateTime startedAt, LocalDateTime finishedAt, String message) {
    }

    public record RunCenterOverview(
            LocalDate tradeDate,
            String serviceStatus,
            List<MetricCard> metrics,
            List<RunStep> steps
    ) {
    }
}
