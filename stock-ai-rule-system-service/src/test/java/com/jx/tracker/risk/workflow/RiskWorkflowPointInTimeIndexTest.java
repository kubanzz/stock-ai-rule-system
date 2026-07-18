package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.data.market.IndustryExposure;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskEvent;
import com.jx.tracker.risk.provider.RiskObservation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskWorkflowPointInTimeIndexTest {

    private static final RiskObjectKey STOCK = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
    private static final RiskObjectKey SECTOR = new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801780");

    @Test
    void exposureIndexVisitsOnlyRequestedStockRevisionsAndKeepsPointInTimeOrdering() {
        LocalDate tradeDate = LocalDate.of(2026, 7, 20);
        LocalDateTime asOf = tradeDate.atTime(20, 0);
        List<IndustryExposure> exposures = new ArrayList<>();
        for (int index = 0; index < 10_000; index++) {
            RiskObjectKey unrelated = new RiskObjectKey(
                    RiskObjectType.STOCK, String.format("%06d.SZ", index));
            exposures.add(exposure(unrelated, "SW1:000001", tradeDate.minusYears(1), null,
                    tradeDate.minusDays(1).atTime(18, 0)));
        }
        exposures.add(exposure(STOCK, "SW1:801010", tradeDate.minusYears(2), tradeDate.minusDays(1),
                tradeDate.minusYears(2).atTime(18, 0)));
        exposures.add(exposure(STOCK, SECTOR.objectId(), tradeDate.minusDays(1), null,
                tradeDate.minusDays(1).atTime(18, 0)));
        exposures.add(exposure(STOCK, "SW1:801790", tradeDate.minusDays(1), null,
                tradeDate.plusDays(1).atTime(9, 0)));

        RiskIndustryExposureIndex index = new RiskIndustryExposureIndex(exposures);

        assertThat(index.effectiveSector(STOCK, tradeDate, asOf)).contains(SECTOR);
        assertThat(index.lastVisitedRows()).isLessThanOrEqualTo(3);
    }

    @Test
    void eventIndexUsesActualTradingDayBoundsAcrossWeekendAndLongHoliday() {
        LocalDate evaluationDate = LocalDate.of(2026, 10, 12);
        LocalDateTime asOf = evaluationDate.atTime(20, 0);
        List<LocalDate> tradingDates = List.of(
                LocalDate.of(2026, 9, 28),
                LocalDate.of(2026, 9, 29),
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 10, 9),
                evaluationDate);
        List<RiskObservation> observations = tradingDates.stream()
                .map(this::priceObservation)
                .toList();
        RiskTradingDayCalendar calendar = new RiskTradingDayCalendar(observations);
        RiskEvent weekend = event(STOCK, LocalDate.of(2026, 10, 3), "weekend");
        List<RiskEvent> events = new ArrayList<>();
        events.add(weekend);
        for (int day = 1; day <= 10_000; day++) {
            events.add(event(STOCK, evaluationDate.minusDays(100L + day), "old-" + day));
        }
        RiskEventWindowIndex index = new RiskEventWindowIndex(events);

        assertThat(calendar.windowStart(RiskHorizon.SHORT_TERM, evaluationDate, asOf))
                .contains(LocalDate.of(2026, 9, 28));
        assertThat(index.window(
                STOCK,
                calendar.windowStart(RiskHorizon.SHORT_TERM, evaluationDate, asOf).orElseThrow(),
                evaluationDate,
                asOf)).containsExactly(weekend);
        assertThat(index.lastVisitedRows()).isLessThanOrEqualTo(1);
    }

    @Test
    void tradingCalendarIgnoresNonPriceObservationsAndHasNoCalendarFallback() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        RiskObservation structural = new RiskObservation(
                STOCK, RiskHorizon.SHORT_TERM, date, RiskDimension.STRUCTURAL_FRAGILITY,
                "V1", BigDecimal.TEN, "score", date.atTime(18, 0), date.atTime(19, 0),
                "fundamental-source", RiskDataQualityStatus.AVAILABLE, Map.of());
        RiskObservation membership = new RiskObservation(
                STOCK, RiskHorizon.SHORT_TERM, date, RiskDimension.LOCAL_CONFIRMATION,
                "DATA_SW1_MEMBERSHIP", BigDecimal.ONE, "boolean",
                date.atTime(18, 0), date.atTime(19, 0),
                "membership-source", RiskDataQualityStatus.AVAILABLE,
                Map.of("metric", "industryMembership"));
        RiskObservation nonPriceConfirmation = new RiskObservation(
                STOCK, RiskHorizon.SHORT_TERM, date, RiskDimension.LOCAL_CONFIRMATION,
                "C2", BigDecimal.TEN, "ratio", date.atTime(18, 0), date.atTime(19, 0),
                "breadth-source", RiskDataQualityStatus.AVAILABLE,
                Map.of("metric", "advanceRatio"));

        RiskTradingDayCalendar calendar = new RiskTradingDayCalendar(
                List.of(structural, membership, nonPriceConfirmation));

        assertThat(calendar.windowStart(RiskHorizon.SHORT_TERM, date, date.atTime(20, 0))).isEmpty();
    }

    @Test
    void allHorizonsUseFiveTwentyAndSixtyObservedTradingDates() {
        LocalDate endDate = LocalDate.of(2026, 10, 12);
        List<LocalDate> tradingDates = java.util.stream.Stream.iterate(
                        endDate.minusDays(120), date -> !date.isAfter(endDate), date -> date.plusDays(1))
                .filter(date -> date.getDayOfWeek() != DayOfWeek.SATURDAY
                        && date.getDayOfWeek() != DayOfWeek.SUNDAY)
                .filter(date -> !date.equals(LocalDate.of(2026, 10, 1))
                        && !date.equals(LocalDate.of(2026, 10, 2)))
                .toList();
        List<LocalDate> latestSixty = tradingDates.subList(tradingDates.size() - 60, tradingDates.size());
        RiskTradingDayCalendar calendar = new RiskTradingDayCalendar(
                latestSixty.stream().map(this::priceObservation).toList());
        LocalDateTime asOf = endDate.atTime(20, 0);

        assertThat(calendar.windowStart(RiskHorizon.SHORT_TERM, endDate, asOf))
                .contains(latestSixty.get(55));
        assertThat(calendar.windowStart(RiskHorizon.MEDIUM_TERM, endDate, asOf))
                .contains(latestSixty.get(40));
        assertThat(calendar.windowStart(RiskHorizon.LONG_TERM, endDate, asOf))
                .contains(latestSixty.getFirst());
    }

    private IndustryExposure exposure(
            RiskObjectKey stock,
            String sectorId,
            LocalDate validFrom,
            LocalDate validTo,
            LocalDateTime availableAt
    ) {
        return new IndustryExposure(
                stock, new RiskObjectKey(RiskObjectType.SECTOR, sectorId), validFrom, validTo,
                availableAt.minusHours(1), availableAt, "source-a", RiskDataQualityStatus.AVAILABLE);
    }

    private RiskObservation priceObservation(LocalDate date) {
        return new RiskObservation(
                STOCK, RiskHorizon.SHORT_TERM, date, RiskDimension.LOCAL_CONFIRMATION,
                "C1", BigDecimal.TEN, "score", date.atTime(18, 0), date.atTime(19, 0),
                "price-source", RiskDataQualityStatus.AVAILABLE,
                Map.of("tradingDay", true));
    }

    private RiskEvent event(RiskObjectKey object, LocalDate date, String key) {
        return new RiskEvent(
                object, date, RiskDimension.FORCED_SELLING, "share_reduction", key,
                new BigDecimal("80"), date.atTime(15, 0), date.atTime(18, 0), date.atTime(19, 0),
                "source-a", RiskDataQualityStatus.AVAILABLE,
                Map.of("modifierCandidate", true, "confirmed", true));
    }
}
