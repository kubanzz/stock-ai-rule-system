package com.jx.tracker.risk.data.event;

import com.jx.tracker.risk.data.flow.FlowEventDataset;
import com.jx.tracker.risk.data.flow.FlowEventMetrics;
import com.jx.tracker.risk.data.flow.FlowEventSourceRecord;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowEventTranslatorAndMetricsTest {

    private static final RiskObjectKey STOCK = new RiskObjectKey(RiskObjectType.STOCK, "000001.SZ");
    private static final LocalDateTime AVAILABLE_AT = LocalDateTime.of(2026, 7, 18, 16, 0);

    @Test
    void mapsFourEconomicMeaningsToTIndicators() {
        assertThat(EventEconomicMeaningDictionary.defaultDictionary().indicatorCode(EconomicMeaning.CASH_FLOW))
                .contains("T1");
        assertThat(EventEconomicMeaningDictionary.defaultDictionary().indicatorCode(EconomicMeaning.DISCOUNT_RATE))
                .contains("T2");
        assertThat(EventEconomicMeaningDictionary.defaultDictionary().indicatorCode(EconomicMeaning.FINANCING_CONDITIONS))
                .contains("T3");
        assertThat(EventEconomicMeaningDictionary.defaultDictionary().indicatorCode(EconomicMeaning.MARKET_TRUST))
                .contains("T4");
    }

    @Test
    void rejectsUnmappedAnnouncementInsteadOfCreatingHighSeverityEvent() {
        FlowEventTranslator translator = new FlowEventTranslator(EventEconomicMeaningDictionary.defaultDictionary());

        FlowEventTranslation result = translator.translate(
                FlowEventDataset.STOCK_ANNOUNCEMENT,
                record(Map.of("announcementCategory", "other")));

        assertThat(result.observations()).isEmpty();
        assertThat(result.events()).isEmpty();
        assertThat(result.rejectionReason()).contains("未映射");
    }

    @Test
    void computesRawProxiesWithoutCalculatingFinalRiskScore() {
        assertThat(FlowEventMetrics.balanceLevel(new BigDecimal("120"), new BigDecimal("100")))
                .isEqualByComparingTo("1.2");
        assertThat(FlowEventMetrics.balanceChange(new BigDecimal("90"), new BigDecimal("100")))
                .isEqualByComparingTo("-0.1");
        assertThat(FlowEventMetrics.deleveragingProxy(new BigDecimal("90"), new BigDecimal("100")))
                .isEqualByComparingTo("0.1");
        assertThat(FlowEventMetrics.redemptionProxy(new BigDecimal("-300"), new BigDecimal("1000")))
                .isEqualByComparingTo("0.3");
        assertThat(FlowEventMetrics.normalizeSeverity(new BigDecimal("150")))
                .isEqualByComparingTo("100");
        assertThat(FlowEventMetrics.normalizeSeverity(new BigDecimal("-1")))
                .isEqualByComparingTo("0");
        assertThatThrownBy(() -> FlowEventMetrics.normalizeSeverity(null))
                .isInstanceOf(FlowEventMetrics.InsufficientHistoryException.class);
        assertThat(FlowEventMetrics.netFlowSeries(List.of(
                new BigDecimal("100"), new BigDecimal("-20"), new BigDecimal("-30"))))
                .containsExactly(new BigDecimal("100"), new BigDecimal("-20"), new BigDecimal("-30"));
    }

    @Test
    void zeroOrMissingDenominatorIsInsufficientInsteadOfSyntheticZero() {
        assertThatThrownBy(() -> FlowEventMetrics.balanceLevel(BigDecimal.TEN, BigDecimal.ZERO))
                .isInstanceOf(FlowEventMetrics.InsufficientHistoryException.class);
        assertThatThrownBy(() -> FlowEventMetrics.redemptionProxy(BigDecimal.TEN, null))
                .isInstanceOf(FlowEventMetrics.InsufficientHistoryException.class);

        FlowEventTranslator translator = new FlowEventTranslator(EventEconomicMeaningDictionary.defaultDictionary());
        FlowEventSourceRecord firstMarginPoint = sourceRecord(
                "margin-first", new BigDecimal("100"), "balance",
                Map.of("referenceBalance", "100"));

        FlowEventTranslation translation = translator.translate(
                FlowEventDataset.MARGIN_FINANCING, firstMarginPoint,
                RiskHorizon.SHORT_TERM, "aktools", null);

        assertThat(translation.observations()).hasSize(2);
        assertThat(translation.observations()).filteredOn(observation -> observation.indicatorCode().equals("V5"))
                .singleElement().satisfies(observation -> {
                    assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
                    assertThat(observation.value()).isEqualByComparingTo("1");
                });
        assertThat(translation.observations()).filteredOn(observation -> observation.indicatorCode().equals("A1"))
                .singleElement().satisfies(observation -> {
                    assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
                    assertThat(observation.value()).isNull();
                });
    }

    @Test
    void explicitZeroAndImprovementAreValidZeroWithoutAdverseEvent() {
        FlowEventTranslator translator = new FlowEventTranslator(EventEconomicMeaningDictionary.defaultDictionary());
        FlowEventSourceRecord improvement = sourceRecord(
                "forecast-improvement", new BigDecimal("18.5"), "forecast_change",
                Map.of("economicMeaning", "cash_flow", "adverse", false));

        FlowEventTranslation result = translator.translate(
                FlowEventDataset.EARNINGS_FORECAST, improvement,
                RiskHorizon.SHORT_TERM, "aktools", null);

        assertThat(result.events()).isEmpty();
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.indicatorCode()).isEqualTo("T1");
            assertThat(observation.value()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        });

        FlowEventTranslation explicitZero = translator.translate(
                FlowEventDataset.EARNINGS_FORECAST,
                sourceRecord("forecast-zero", BigDecimal.ZERO, "forecast_change",
                        Map.of("economicMeaning", "cash_flow", "adverse", true)),
                RiskHorizon.SHORT_TERM, "aktools", null);
        assertThat(explicitZero.events()).isEmpty();
        assertThat(explicitZero.observations()).singleElement()
                .satisfies(observation -> assertThat(observation.qualityStatus())
                        .isEqualTo(RiskDataQualityStatus.VALID_ZERO));
    }

    @Test
    void delayedMarketFactsUseTheirFirstAvailableTradingDateForScoring() {
        LocalDate sourceTradeDate = LocalDate.of(2026, 7, 17);
        LocalDate effectiveTradeDate = LocalDate.of(2026, 7, 20);
        FlowEventSourceRecord margin = new FlowEventSourceRecord(
                "margin-20260717", "cursor-1", STOCK, sourceTradeDate,
                sourceTradeDate.atTime(15, 0), sourceTradeDate.atTime(15, 0),
                effectiveTradeDate.atTime(8, 30), new BigDecimal("90"),
                "currency", "balance", "融资余额",
                Map.of("referenceBalance", "100", "previousBalance", "100"));

        FlowEventTranslation result = new FlowEventTranslator(
                EventEconomicMeaningDictionary.defaultDictionary())
                .translate(FlowEventDataset.MARGIN_FINANCING, margin,
                        RiskHorizon.SHORT_TERM, "tushare", null);

        assertThat(result.observations()).hasSize(2).allSatisfy(observation -> {
            assertThat(observation.tradeDate()).isEqualTo(effectiveTradeDate);
            assertThat(observation.attributes())
                    .containsEntry("sourceTradeDate", sourceTradeDate.toString());
        });
    }

    @Test
    void delayedSubstantiveEventsUseTheirFirstAvailableTradingDateForScoring() {
        LocalDate sourceTradeDate = LocalDate.of(2026, 7, 17);
        LocalDate effectiveTradeDate = LocalDate.of(2026, 7, 20);
        FlowEventSourceRecord forecast = new FlowEventSourceRecord(
                "forecast-20260717", "cursor-1", STOCK, sourceTradeDate,
                sourceTradeDate.atStartOfDay(), sourceTradeDate.atStartOfDay(),
                effectiveTradeDate.atStartOfDay(), new BigDecimal("-30"),
                "forecast_change", "forecast", "业绩预减",
                Map.of("adverse", true));

        FlowEventTranslation result = new FlowEventTranslator(
                EventEconomicMeaningDictionary.defaultDictionary())
                .translate(FlowEventDataset.EARNINGS_FORECAST, forecast,
                        RiskHorizon.SHORT_TERM, "tushare", null);

        assertThat(result.observations()).singleElement().satisfies(observation ->
                assertThat(observation.tradeDate()).isEqualTo(effectiveTradeDate));
        assertThat(result.events()).singleElement().satisfies(event -> {
            assertThat(event.tradeDate()).isEqualTo(effectiveTradeDate);
            assertThat(event.payload())
                    .containsEntry("sourceTradeDate", sourceTradeDate.toString());
        });
    }

    @Test
    void delayedModifierEventsUseTheirFirstAvailableTradingDateForScoring() {
        LocalDate sourceTradeDate = LocalDate.of(2026, 7, 17);
        LocalDate effectiveTradeDate = LocalDate.of(2026, 7, 20);
        FlowEventSourceRecord reduction = new FlowEventSourceRecord(
                "reduction-20260717", "cursor-1", STOCK, sourceTradeDate,
                sourceTradeDate.atTime(15, 0), sourceTradeDate.atTime(15, 0),
                effectiveTradeDate.atTime(8, 30), new BigDecimal("1000"),
                "shares", "share_reduction", "股东减持",
                Map.of("actualReduction", true, "modifierRatio", new BigDecimal("3")));

        FlowEventTranslation result = new FlowEventTranslator(
                EventEconomicMeaningDictionary.defaultDictionary())
                .translate(FlowEventDataset.SHARE_REDUCTION, reduction,
                        RiskHorizon.SHORT_TERM, "tushare", null);

        assertThat(result.events()).singleElement().satisfies(event -> {
            assertThat(event.tradeDate()).isEqualTo(effectiveTradeDate);
            assertThat(event.payload())
                    .containsEntry("sourceTradeDate", sourceTradeDate.toString());
        });
    }

    @Test
    void missingAdverseSeverityRemainsInsufficientHistory() {
        FlowEventTranslator translator = new FlowEventTranslator(EventEconomicMeaningDictionary.defaultDictionary());
        FlowEventSourceRecord adverseWithoutValue = sourceRecord(
                "notice-missing", null, "notice",
                Map.of("economicMeaning", "market_trust", "adverse", true));

        FlowEventTranslation result = translator.translate(
                FlowEventDataset.STOCK_ANNOUNCEMENT, adverseWithoutValue,
                RiskHorizon.SHORT_TERM, "aktools", null);

        assertThat(result.events()).singleElement()
                .satisfies(event -> assertThat(event.severityScore()).isNull());
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.indicatorCode()).isEqualTo("T4");
            assertThat(observation.value()).isNull();
            assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        });
    }

    @Test
    void shareholdingIncreaseDoesNotCreateForcedSellingModifier() {
        FlowEventTranslator translator = new FlowEventTranslator(EventEconomicMeaningDictionary.defaultDictionary());
        FlowEventTranslation result = translator.translate(
                FlowEventDataset.SHARE_REDUCTION,
                sourceRecord("increase", new BigDecimal("200000"), "share_reduction",
                        Map.of("actualReduction", false, "adverse", false)),
                RiskHorizon.SHORT_TERM, "aktools", null);

        assertThat(result.events()).isEmpty();
        assertThat(result.observations()).isEmpty();
    }

    @Test
    void longBusinessKeysAreHashedWithinPersistenceLimitWithoutColliding() {
        FlowEventTranslator translator = new FlowEventTranslator(
                EventEconomicMeaningDictionary.defaultDictionary());
        String longMetric = "预测指标".repeat(60);
        String longBatch = "上市批次".repeat(60);
        String longShareholder = "股东名称".repeat(60);
        List<FlowEventSourceRecord> records = List.of(
                sourceRecord(
                        "earnings_forecast:000001.SZ:2026-07-18:" + longMetric + "净利润:预减",
                        new BigDecimal("-10"), "forecast_change",
                        Map.of("adverse", true)),
                sourceRecord(
                        "earnings_forecast:000001.SZ:2026-07-18:" + longMetric + "扣非净利润:预减",
                        new BigDecimal("-10"), "forecast_change",
                        Map.of("adverse", true)),
                sourceRecord(
                        "share_unlock:000001.SZ:2026-08-01:" + longBatch + "第一批:1000",
                        new BigDecimal("1000"), "share_unlock",
                        Map.of("scheduled", true, "modifierRatio", new BigDecimal("1"))),
                sourceRecord(
                        "share_unlock:000001.SZ:2026-08-01:" + longBatch + "第二批:1000",
                        new BigDecimal("1000"), "share_unlock",
                        Map.of("scheduled", true, "modifierRatio", new BigDecimal("1"))),
                sourceRecord(
                        "share_reduction:000001.SZ:" + longShareholder + "甲:2026-07-18:1000",
                        new BigDecimal("1000"), "share_reduction",
                        Map.of("actualReduction", true, "modifierRatio", new BigDecimal("1"))),
                sourceRecord(
                        "share_reduction:000001.SZ:" + longShareholder + "乙:2026-07-18:1000",
                        new BigDecimal("1000"), "share_reduction",
                        Map.of("actualReduction", true, "modifierRatio", new BigDecimal("1")))
        );
        List<FlowEventDataset> datasets = List.of(
                FlowEventDataset.EARNINGS_FORECAST, FlowEventDataset.EARNINGS_FORECAST,
                FlowEventDataset.SHARE_UNLOCK, FlowEventDataset.SHARE_UNLOCK,
                FlowEventDataset.SHARE_REDUCTION, FlowEventDataset.SHARE_REDUCTION);

        List<com.jx.tracker.risk.provider.RiskEvent> events = java.util.stream.IntStream
                .range(0, records.size())
                .mapToObj(index -> translator.translate(
                        datasets.get(index), records.get(index),
                        RiskHorizon.SHORT_TERM, "aktools", null).events().getFirst())
                .toList();

        assertThat(events).extracting(com.jx.tracker.risk.provider.RiskEvent::eventKey)
                .doesNotHaveDuplicates()
                .allSatisfy(key -> {
                    assertThat(key).hasSizeLessThanOrEqualTo(128);
                    assertThat(key).matches("[a-z_]+:sha256:[0-9a-f]{64}");
                });
        assertThat(events).allSatisfy(event -> {
            String sourceRecordId = (String) event.payload().get("sourceRecordId");
            assertThat(sourceRecordId).hasSizeGreaterThan(128);
            assertThat(event.payload()).containsEntry(
                    "canonicalBusinessKey",
                    event.eventType() + ":stock:000001.SZ:" + sourceRecordId);
        });
    }

    private FlowEventSourceRecord record(Map<String, Object> attributes) {
        return sourceRecord("announcement-1", new BigDecimal("99"), "notice", attributes);
    }

    private FlowEventSourceRecord sourceRecord(
            String id,
            BigDecimal value,
            String eventCode,
            Map<String, Object> attributes
    ) {
        return new FlowEventSourceRecord(
                id, "cursor-1", STOCK, LocalDate.of(2026, 7, 18),
                LocalDateTime.of(2026, 7, 18, 12, 0), LocalDateTime.of(2026, 7, 18, 15, 0),
                AVAILABLE_AT, value, "score", eventCode, "普通公告", attributes);
    }
}
