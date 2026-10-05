package com.jx.tracker.verification;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.mapper.BacktestResultMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only descriptive summary for a saved backtest report.
 *
 * Report fields are reported statistics, not independently verified evidence.
 * Candidate/verified promotion requires an independent execution path bound to
 * the frozen strategy version, stock scope and prediction horizon.
 */
@Service
public class ResearchVerificationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal TARGET_WIN_RATE = new BigDecimal("0.65");
    private static final int MIN_TOTAL_EVENTS = 100;
    private static final int MIN_ENTRY_DATES = 30;
    private static final int MIN_SYMBOLS = 20;
    private static final int MIN_PERIOD_EVENTS = 30;
    private static final int MIN_PERIOD_ENTRY_DATES = 10;
    private static final int MIN_PERIOD_SYMBOLS = 10;
    private static final BigDecimal MIN_BASELINE_LIFT = new BigDecimal("0.03");

    private final BacktestResultMapper backtestResultMapper;

    public ResearchVerificationService(BacktestResultMapper backtestResultMapper) {
        this.backtestResultMapper = backtestResultMapper;
    }

    public ResearchVerificationSummary summarize(String objectType, String objectCode, Integer holdingPeriod) {
        List<BacktestResult> reports = backtestResultMapper.selectList(
                Wrappers.<BacktestResult>lambdaQuery()
                        .eq(StringUtils.hasText(objectType), BacktestResult::getObjectType, objectType)
                        .eq(StringUtils.hasText(objectCode), BacktestResult::getObjectCode, objectCode)
                        .eq(holdingPeriod != null, BacktestResult::getHoldingPeriod, holdingPeriod)
                        .orderByDesc(BacktestResult::getCreatedTime)
                        .orderByDesc(BacktestResult::getId)
                        .last("LIMIT 1"));
        return summarize(reports.isEmpty() ? null : reports.getFirst());
    }

    /**
     * Deny activation until an independent final-test executor and its binding
     * to strategy version, scope and horizon are implemented. JSON claims in a
     * generic backtest report cannot authorize production use.
     */
    public boolean isVerifiedStrategy(String strategyCode) {
        return false;
    }

    public ResearchVerificationSummary summarize(BacktestResult report) {
        GateValues gates = parse(report == null ? null : report.getResultJson());
        String status = report == null ? "pending" : classify(report);
        String reason = report == null
                ? "尚无保存的回测报告"
                : reason(report);
        return new ResearchVerificationSummary(
                report == null ? null : report.getId(),
                report == null ? null : report.getObjectType(),
                report == null ? null : report.getObjectCode(),
                report == null ? null : report.getHoldingPeriod(),
                report == null ? null : report.getStartDate(),
                report == null ? null : report.getEndDate(),
                report == null ? null : report.getStatus(),
                status,
                reason,
                report == null ? null : report.getTriggerCount(),
                gates.evaluatedCount,
                gates.winRate,
                gates.entryDates,
                gates.symbols,
                gates.baselineLift,
                gates.descriptiveQualified,
                gates.baselineCompared,
                gates.nonOverlapping,
                gates.independentFinalTest,
                gates.completeTriggerRecord,
                gates.periodsSufficient,
                gates.periodsStable,
                gates.gateSnapshot());
    }

    private String classify(BacktestResult report) {
        if (!"success".equalsIgnoreCase(report.getStatus())) {
            return "rejected";
        }
        return "exploratory";
    }

    private String reason(BacktestResult report) {
        if (!"success".equalsIgnoreCase(report.getStatus())) {
            return "回测报告状态为 " + report.getStatus() + "，不具备研究验收证据。";
        }
        return "普通回测仅提供描述性统计，报告中的分期、独立测试与完整记录标记尚未经独立验收；"
                + "在终验执行器与方案版本、范围、周期绑定接入前，不能晋级候选/已验证或授权生产启用。仅作为辅助决策信号。";
    }

    private GateValues parse(String resultJson) {
        if (!StringUtils.hasText(resultJson)) return GateValues.empty();
        try {
            JsonNode root = MAPPER.readTree(resultJson);
            JsonNode validation = root.path("validation");
            int evaluated = intValue(validation, "evaluatedSamples", intValue(root, "evaluatedCount", 0));
            BigDecimal winRate = decimal(validation, "overallWinRate", decimal(root, "winRate", null));
            int entryDates = intValue(validation, "entryDates", intValue(root, "entryDates", 0));
            int symbols = intValue(validation, "symbols", intValue(root, "symbols", 0));
            BigDecimal lift = decimal(validation, "baselineLift", decimal(root, "baselineLift", null));
            if (lift == null) {
                BigDecimal percentagePoints = decimal(root, "baselineLiftPercentagePoints", null);
                if (percentagePoints != null) lift = percentagePoints.movePointLeft(2);
            }
            boolean periodsSufficient = bool(validation, "periodSamplesSufficient", false)
                    || bool(root, "periodSamplesSufficient", false);
            boolean periodsStable = bool(validation, "stableAcrossPeriods", false)
                    || bool(root, "stableAcrossPeriods", false);
            boolean descriptive = bool(validation, "descriptiveQualified", false);
            boolean baseline = bool(validation, "baselineCompared", bool(root, "baselineCompared", false));
            boolean nonOverlapping = bool(validation, "nonOverlapping", bool(root, "nonOverlapping", false));
            boolean independent = bool(validation, "independentFinalTest", bool(root, "independentFinalTest", false));
            boolean complete = bool(validation, "completeTriggerRecord", bool(root, "completeTriggerRecord", false));
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("targetWinRate", TARGET_WIN_RATE);
            snapshot.put("minTotalEvents", MIN_TOTAL_EVENTS);
            snapshot.put("minEntryDates", MIN_ENTRY_DATES);
            snapshot.put("minSymbols", MIN_SYMBOLS);
            snapshot.put("minPeriodEvents", MIN_PERIOD_EVENTS);
            snapshot.put("minPeriodEntryDates", MIN_PERIOD_ENTRY_DATES);
            snapshot.put("minPeriodSymbols", MIN_PERIOD_SYMBOLS);
            snapshot.put("minBaselineLift", MIN_BASELINE_LIFT);
            return new GateValues(evaluated, winRate, entryDates, symbols, lift,
                    descriptive, baseline, nonOverlapping, independent, complete, periodsSufficient,
                    periodsStable, snapshot);
        } catch (Exception ignored) {
            return GateValues.empty();
        }
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        return node.has(field) && node.path(field).canConvertToInt() ? node.path(field).asInt() : fallback;
    }

    private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : fallback;
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        return node.has(field) && node.path(field).isBoolean() ? node.path(field).asBoolean() : fallback;
    }

    private record GateValues(int evaluatedCount, BigDecimal winRate, int entryDates, int symbols,
                              BigDecimal baselineLift, boolean descriptiveQualified, boolean baselineCompared,
                              boolean nonOverlapping,
                              boolean independentFinalTest, boolean completeTriggerRecord,
                              boolean periodsSufficient, boolean periodsStable,
                              Map<String, Object> gateSnapshot) {
        private static GateValues empty() {
            return new GateValues(0, null, 0, 0, null, false, false, false,
                    false, false, false, false, Map.of(
                    "targetWinRate", TARGET_WIN_RATE,
                    "minTotalEvents", MIN_TOTAL_EVENTS,
                    "minEntryDates", MIN_ENTRY_DATES,
                    "minSymbols", MIN_SYMBOLS,
                    "minPeriodEvents", MIN_PERIOD_EVENTS,
                    "minPeriodEntryDates", MIN_PERIOD_ENTRY_DATES,
                    "minPeriodSymbols", MIN_PERIOD_SYMBOLS,
                    "minBaselineLift", MIN_BASELINE_LIFT));
        }
    }

    public record ResearchVerificationSummary(Long reportId, String objectType, String objectCode,
                                              Integer holdingPeriod, LocalDate startDate, LocalDate endDate,
                                              String reportStatus, String researchStatus, String statusReason,
                                              Integer triggerCount, int evaluatedCount, BigDecimal winRate,
                                              int entryDates, int symbols, BigDecimal baselineLift,
                                              boolean descriptiveQualified, boolean baselineCompared,
                                              boolean nonOverlapping,
                                              boolean independentFinalTest, boolean completeTriggerRecord,
                                              boolean periodsSufficient, boolean periodsStable,
                                              Map<String, Object> gateSnapshot) {
    }
}
