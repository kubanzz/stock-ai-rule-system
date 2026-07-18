package com.jx.tracker.risk.data.event;

import com.jx.tracker.risk.data.flow.FlowEventDataset;
import com.jx.tracker.risk.data.flow.FlowEventMetrics;
import com.jx.tracker.risk.data.flow.FlowEventSourceRecord;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(FlowEventMetrics.netFlowSeries(List.of(
                new BigDecimal("100"), new BigDecimal("-20"), new BigDecimal("-30"))))
                .containsExactly(new BigDecimal("100"), new BigDecimal("-20"), new BigDecimal("-30"));
    }

    private FlowEventSourceRecord record(Map<String, Object> attributes) {
        return new FlowEventSourceRecord(
                "announcement-1", "cursor-1", STOCK, LocalDate.of(2026, 7, 18),
                LocalDateTime.of(2026, 7, 18, 12, 0), LocalDateTime.of(2026, 7, 18, 15, 0),
                AVAILABLE_AT, new BigDecimal("99"), "score", "notice", "普通公告", attributes);
    }
}
