package com.jx.tracker.domain.vo;

import com.jx.tracker.market.data.util.MarketCodeNormalizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    public record SignalDashboardQuery(
            LocalDate date,
            String market,
            String poolCode,
            String symbol,
            String signal,
            String industry,
            BigDecimal confidenceMin,
            BigDecimal confidenceMax,
            int pageNum,
            int pageSize,
            String sortField,
            String sortOrder
    ) {
        private static final Set<String> SORT_FIELDS = Set.of(
                "symbol", "price", "changePct", "signal", "bullishScore", "bearishScore",
                "riskScore", "confidence", "triggeredRuleCount", "updatedAt"
        );

        public SignalDashboardQuery {
            market = MarketCodeNormalizer.toDisplayName(defaultIfBlank(market, "A股"));
            poolCode = defaultIfBlank(poolCode, "all").toLowerCase(Locale.ROOT);
            symbol = trimToNull(symbol);
            signal = trimToNull(signal);
            industry = trimToNull(industry);
            pageNum = pageNum < 1 ? 1 : pageNum;
            pageSize = pageSize < 1 ? 20 : Math.min(pageSize, 100);
            sortField = sortField != null && SORT_FIELDS.contains(sortField) ? sortField : "confidence";
            sortOrder = "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc";
        }

        private static String defaultIfBlank(String value, String defaultValue) {
            String trimmed = trimToNull(value);
            return trimmed == null ? defaultValue : trimmed;
        }

        private static String trimToNull(String value) {
            return value == null || value.trim().isEmpty() ? null : value.trim();
        }
    }

    public record IndustryStrength(String industry, BigDecimal strength, String status) {
    }

    public record Sentiment(String label, BigDecimal score, String status) {
    }

    public record RiskOverview(
            long highRiskCount,
            BigDecimal highRiskRatio,
            String syncStatus,
            String level,
            String summary
    ) {
    }

    public record MarketContext(
            boolean available,
            String indexName,
            BigDecimal indexValue,
            BigDecimal changePct,
            String status,
            List<SparkPoint> trend,
            List<IndustryStrength> industryStrength,
            Sentiment sentiment,
            RiskOverview riskOverview
    ) {
        public MarketContext(String indexName, BigDecimal indexValue, BigDecimal changePct, String status, List<SparkPoint> trend) {
            this(true, indexName, indexValue, changePct, status, trend, List.of(), null, null);
        }
    }

    public record SignalDashboardOverview(
            LocalDate tradeDate,
            String riskDisclaimer,
            List<MetricCard> metrics,
            List<SignalRow> signals,
            MarketContext marketContext,
            long total,
            int pageNum,
            int pageSize,
            List<String> availableIndustries,
            LocalDateTime dataUpdatedAt
    ) {
        public SignalDashboardOverview(
                LocalDate tradeDate,
                String riskDisclaimer,
                List<MetricCard> metrics,
                List<SignalRow> signals,
                MarketContext marketContext,
                long total
        ) {
            this(tradeDate, riskDisclaimer, metrics, signals, marketContext, total, 1, 20, List.of(), null);
        }
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

    public record WatchlistMutationRequest(
            @NotBlank(message = "股票池名称不能为空")
            @Size(max = 128, message = "股票池名称不能超过128个字符")
            String poolName,
            @NotBlank(message = "股票池市场不能为空")
            @Size(max = 32, message = "股票池市场不能超过32个字符")
            String market
    ) {
    }

    public record WatchlistStockMutationRequest(
            @NotBlank(message = "股票代码不能为空")
            @Size(max = 32, message = "股票代码不能超过32个字符")
            String symbol,
            @Size(max = 64, message = "股票分组名称不能超过64个字符")
            String groupName
    ) {
    }

    public record WatchlistCandidate(
            String symbol,
            String name,
            String market,
            String exchange,
            String industry,
            boolean inPool
    ) {
    }

    public record WatchlistBatchMutationRequest(
            @Size(min = 1, max = 100, message = "每次请选择1至100只股票")
            List<@NotBlank(message = "股票代码不能为空") String> symbols,
            @Size(max = 64, message = "股票分组名称不能超过64个字符")
            String groupName
    ) {
    }

    public record WatchlistBatchMutationResult(
            String poolCode,
            List<String> addedSymbols,
            List<String> skippedSymbols,
            List<String> failedSymbols
    ) {
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
