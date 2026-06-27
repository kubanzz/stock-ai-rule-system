package com.jx.tracker.factor;

import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.entity.StockDailyQuote;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class TechnicalFactorCalculator {

    private static final int MIN_HISTORY_SIZE = 26;
    private static final int SCALE = 4;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public TechnicalFactorResult calculate(String symbol, LocalDate targetDate, List<StockDailyQuote> quotes) {
        List<StockDailyQuote> sourceQuotes = quotes == null ? List.of() : quotes;
        List<StockDailyQuote> usableQuotes = sourceQuotes.stream()
                .filter(Objects::nonNull)
                .filter(quote -> Objects.equals(symbol, quote.getSymbol()))
                .filter(quote -> quote.getTradeDate() != null && !quote.getTradeDate().isAfter(targetDate))
                .sorted(Comparator.comparing(StockDailyQuote::getTradeDate))
                .toList();

        StockDailyQuote targetQuote = usableQuotes.stream()
                .filter(quote -> targetDate.equals(quote.getTradeDate()))
                .findFirst()
                .orElse(null);
        if (isSuspendedOrMissing(targetQuote)) {
            return new TechnicalFactorResult(symbol, targetDate, statusOnlyFactors(
                    "unknown",
                    "suspended",
                    "suspended_or_missing",
                    "suspended_or_missing",
                    "suspended_or_missing"
            ));
        }
        List<StockDailyQuote> completeQuotes = usableQuotes.stream()
                .filter(quote -> quote.getClosePrice() != null)
                .filter(quote -> quote.getVolume() != null)
                .toList();
        if (completeQuotes.size() < MIN_HISTORY_SIZE) {
            return new TechnicalFactorResult(symbol, targetDate, statusOnlyFactors(
                    "unknown",
                    "unknown",
                    "insufficient_data",
                    "data_insufficient",
                    "insufficient_data"
            ));
        }

        BigDecimal ma5 = movingAverage(completeQuotes, 5);
        BigDecimal ma10 = movingAverage(completeQuotes, 10);
        BigDecimal ma20 = movingAverage(completeQuotes, 20);
        BigDecimal rsi14 = rsi(completeQuotes, 14);
        MacdValue macd = macd(completeQuotes);
        BigDecimal volumeRatio5d = volumeRatio(completeQuotes, 5);
        BigDecimal changePct5d = changePct(completeQuotes, 5);

        String shortTermTrend = shortTermTrend(targetQuote.getClosePrice(), ma5, ma20, changePct5d);
        String volumeStatus = volumeStatus(volumeRatio5d);
        String technicalStatus = technicalStatus(shortTermTrend, volumeStatus, rsi14, macd.histogram());
        String riskStatus = riskStatus(targetQuote, technicalStatus, volumeStatus);

        Map<String, Object> factors = baseFactors(shortTermTrend, volumeStatus, technicalStatus, riskStatus, "normal");
        factors.put("ma5", ma5);
        factors.put("ma10", ma10);
        factors.put("ma20", ma20);
        factors.put("rsi14", rsi14);
        factors.put("macd_dif", macd.dif());
        factors.put("macd_dea", macd.dea());
        factors.put("macd_histogram", macd.histogram());
        factors.put("volume_ratio_5d", volumeRatio5d);
        factors.put("change_pct_5d", changePct5d);
        return new TechnicalFactorResult(symbol, targetDate, factors);
    }

    private static boolean isSuspendedOrMissing(StockDailyQuote quote) {
        return quote == null
                || quote.getClosePrice() == null
                || quote.getVolume() == null
                || quote.getVolume().compareTo(BigDecimal.ZERO) <= 0;
    }

    private static Map<String, Object> statusOnlyFactors(
            String shortTermTrend,
            String volumeStatus,
            String technicalStatus,
            String riskStatus,
            String dataStatus
    ) {
        return baseFactors(shortTermTrend, volumeStatus, technicalStatus, riskStatus, dataStatus);
    }

    private static Map<String, Object> baseFactors(
            String shortTermTrend,
            String volumeStatus,
            String technicalStatus,
            String riskStatus,
            String dataStatus
    ) {
        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("short_term_trend", shortTermTrend);
        factors.put("volume_status", volumeStatus);
        factors.put("technical_status", technicalStatus);
        factors.put("risk_status", riskStatus);
        factors.put("data_status", dataStatus);
        factors.put("risk_disclaimer", StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
        return factors;
    }

    private static BigDecimal movingAverage(List<StockDailyQuote> quotes, int days) {
        return quotes.stream()
                .skip(quotes.size() - days)
                .map(StockDailyQuote::getClosePrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(days), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal rsi(List<StockDailyQuote> quotes, int days) {
        BigDecimal gains = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        int start = quotes.size() - days;
        for (int i = start; i < quotes.size(); i++) {
            BigDecimal diff = quotes.get(i).getClosePrice().subtract(quotes.get(i - 1).getClosePrice());
            if (diff.compareTo(BigDecimal.ZERO) >= 0) {
                gains = gains.add(diff);
            } else {
                losses = losses.add(diff.abs());
            }
        }
        if (losses.compareTo(BigDecimal.ZERO) == 0) {
            return HUNDRED.setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal averageGain = gains.divide(BigDecimal.valueOf(days), 10, RoundingMode.HALF_UP);
        BigDecimal averageLoss = losses.divide(BigDecimal.valueOf(days), 10, RoundingMode.HALF_UP);
        BigDecimal rs = averageGain.divide(averageLoss, 10, RoundingMode.HALF_UP);
        return HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(rs), 10, RoundingMode.HALF_UP))
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static MacdValue macd(List<StockDailyQuote> quotes) {
        BigDecimal ema12 = quotes.getFirst().getClosePrice();
        BigDecimal ema26 = quotes.getFirst().getClosePrice();
        BigDecimal dea = BigDecimal.ZERO;
        for (StockDailyQuote quote : quotes) {
            ema12 = ema(quote.getClosePrice(), ema12, 12);
            ema26 = ema(quote.getClosePrice(), ema26, 26);
            BigDecimal dif = ema12.subtract(ema26);
            dea = ema(dif, dea, 9);
        }
        BigDecimal dif = ema12.subtract(ema26);
        BigDecimal histogram = dif.subtract(dea);
        return new MacdValue(
                dif.setScale(SCALE, RoundingMode.HALF_UP),
                dea.setScale(SCALE, RoundingMode.HALF_UP),
                histogram.setScale(SCALE, RoundingMode.HALF_UP)
        );
    }

    private static BigDecimal ema(BigDecimal current, BigDecimal previousEma, int days) {
        BigDecimal multiplier = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(days + 1L), 10, RoundingMode.HALF_UP);
        return current.subtract(previousEma).multiply(multiplier).add(previousEma);
    }

    private static BigDecimal volumeRatio(List<StockDailyQuote> quotes, int days) {
        StockDailyQuote target = quotes.getLast();
        BigDecimal averageVolume = quotes.stream()
                .skip(quotes.size() - days - 1L)
                .limit(days)
                .map(StockDailyQuote::getVolume)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(days), SCALE, RoundingMode.HALF_UP);
        if (averageVolume.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return target.getVolume().divide(averageVolume, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal changePct(List<StockDailyQuote> quotes, int days) {
        StockDailyQuote target = quotes.getLast();
        BigDecimal baseClose = quotes.get(quotes.size() - days - 1).getClosePrice();
        if (baseClose.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return target.getClosePrice()
                .subtract(baseClose)
                .divide(baseClose, 10, RoundingMode.HALF_UP)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static String shortTermTrend(BigDecimal close, BigDecimal ma5, BigDecimal ma20, BigDecimal changePct5d) {
        if (close.compareTo(ma5) >= 0 && ma5.compareTo(ma20) >= 0) {
            return changePct5d.compareTo(new BigDecimal("0.05")) >= 0 ? "strong_up" : "up";
        }
        if (close.compareTo(ma5) <= 0 && ma5.compareTo(ma20) <= 0) {
            return changePct5d.compareTo(new BigDecimal("-0.05")) <= 0 ? "strong_down" : "down";
        }
        return "sideways";
    }

    private static String volumeStatus(BigDecimal volumeRatio5d) {
        if (volumeRatio5d.compareTo(new BigDecimal("2.00")) >= 0) {
            return "abnormal_high";
        }
        if (volumeRatio5d.compareTo(new BigDecimal("1.30")) >= 0) {
            return "high";
        }
        if (volumeRatio5d.compareTo(new BigDecimal("0.50")) <= 0) {
            return "low";
        }
        return "normal";
    }

    private static String technicalStatus(String trend, String volumeStatus, BigDecimal rsi14, BigDecimal macdHistogram) {
        if (rsi14.compareTo(new BigDecimal("20")) <= 0) {
            return "oversold";
        }
        if (rsi14.compareTo(new BigDecimal("85")) >= 0 && "abnormal_high".equals(volumeStatus)) {
            return "overbought";
        }
        if (("strong_up".equals(trend) || "up".equals(trend)) && macdHistogram.compareTo(BigDecimal.ZERO) > 0) {
            return "bullish";
        }
        if (("strong_down".equals(trend) || "down".equals(trend)) && macdHistogram.compareTo(BigDecimal.ZERO) < 0) {
            return "bearish";
        }
        return "neutral";
    }

    private static String riskStatus(StockDailyQuote targetQuote, String technicalStatus, String volumeStatus) {
        if ("overbought".equals(technicalStatus) || "abnormal_high".equals(volumeStatus)) {
            return "high_risk";
        }
        if (targetQuote.getChangePct() != null
                && targetQuote.getChangePct().abs().compareTo(new BigDecimal("7.00")) >= 0) {
            return "caution";
        }
        return "normal";
    }

    private record MacdValue(BigDecimal dif, BigDecimal dea, BigDecimal histogram) {
    }
}
