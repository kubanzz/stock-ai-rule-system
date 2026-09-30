package com.jx.tracker.rule.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class StockFactorFact {

    private final String symbol;
    private final LocalDate tradeDate;
    private final Map<String, Object> factors;
    private final BigDecimal rsi;
    private final BigDecimal macd;
    private final BigDecimal priceChange5d;
    private final BigDecimal volumeRatio;
    private final String shortTermTrend;
    private final String volumeStatus;
    private final String technicalStatus;
    private final String sentimentStatus;
    private final String marketStatus;
    private final String industryStatus;
    private final String fundamentalStatus;
    private BigDecimal bullishScore = BigDecimal.ZERO;
    private BigDecimal bearishScore = BigDecimal.ZERO;
    private BigDecimal riskScore = BigDecimal.ZERO;
    private final List<String> triggeredRules = new ArrayList<>();
    private final List<String> explanations = new ArrayList<>();

    private StockFactorFact(RuleExecutionRequest request) {
        Map<String, Object> inputFactors = request.factors() == null ? Map.of() : request.factors();
        this.symbol = request.symbol();
        this.tradeDate = request.tradeDate();
        // Null entries are unavailable evidence; Map.copyOf rejects them.
        Map<String, Object> availableFactors = new HashMap<>();
        inputFactors.forEach((key, value) -> {
            if (key != null && value != null) {
                availableFactors.put(key, value);
            }
        });
        this.factors = Collections.unmodifiableMap(availableFactors);
        // TechnicalFactorCalculator uses the more explicit persisted names
        // (rsi14, macd_histogram and volume_ratio_5d). Keep the shorter
        // aliases for manually supplied factors and older rule requests.
        this.rsi = decimalFactor(inputFactors, "rsi", "rsi14");
        this.macd = decimalFactor(inputFactors, "macd", "macd_histogram");
        this.priceChange5d = decimalFactor(inputFactors, "price_change_5d", "priceChange5d", "change_pct_5d");
        this.volumeRatio = decimalFactor(inputFactors, "volume_ratio", "volumeRatio", "volume_ratio_5d");
        this.shortTermTrend = textFactor(inputFactors, "short_term_trend", "shortTermTrend");
        this.volumeStatus = textFactor(inputFactors, "volume_status", "volumeStatus");
        this.technicalStatus = textFactor(inputFactors, "technical_status", "technicalStatus");
        this.sentimentStatus = textFactor(inputFactors, "sentiment_status", "sentimentStatus");
        this.marketStatus = textFactor(inputFactors, "market_status", "marketStatus");
        this.industryStatus = textFactor(inputFactors, "industry_status", "industryStatus");
        this.fundamentalStatus = textFactor(inputFactors, "fundamental_status", "fundamentalStatus");
    }

    public static StockFactorFact from(RuleExecutionRequest request) {
        return new StockFactorFact(request);
    }

    public String getSymbol() {
        return symbol;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public Map<String, Object> getFactors() {
        return factors;
    }

    public BigDecimal getRsi() {
        return rsi;
    }

    public BigDecimal getMacd() {
        return macd;
    }

    public BigDecimal getPriceChange5d() {
        return priceChange5d;
    }

    public BigDecimal getVolumeRatio() {
        return volumeRatio;
    }

    public String getShortTermTrend() {
        return shortTermTrend;
    }

    public String getVolumeStatus() {
        return volumeStatus;
    }

    public String getTechnicalStatus() {
        return technicalStatus;
    }

    public String getSentimentStatus() {
        return sentimentStatus;
    }

    public String getMarketStatus() {
        return marketStatus;
    }

    public String getIndustryStatus() {
        return industryStatus;
    }

    public String getFundamentalStatus() {
        return fundamentalStatus;
    }

    public BigDecimal getBullishScore() {
        return bullishScore;
    }

    public BigDecimal getBearishScore() {
        return bearishScore;
    }

    public BigDecimal getRiskScore() {
        return riskScore;
    }

    public List<String> getTriggeredRules() {
        return List.copyOf(triggeredRules);
    }

    public List<String> getExplanations() {
        return List.copyOf(explanations);
    }

    public void addBullishScore(BigDecimal score) {
        bullishScore = bullishScore.add(safeScore(score));
    }

    public void addBearishScore(BigDecimal score) {
        bearishScore = bearishScore.add(safeScore(score));
    }

    public void addRiskScore(BigDecimal score) {
        riskScore = riskScore.add(safeScore(score));
    }

    public void addTriggeredRule(String ruleCode) {
        if (hasText(ruleCode)) {
            triggeredRules.add(ruleCode);
        }
    }

    public void addExplanation(String explanation) {
        if (hasText(explanation)) {
            explanations.add(explanation);
        }
    }

    public void addReason(String reason) {
        addExplanation(reason);
    }

    /**
     * Evaluates a restricted, JSON-compatible condition from a Drools rule.
     *
     * <p>This method is intentionally kept on the fact instead of generating
     * arbitrary MVEL expressions from AI output.  Candidate JSON rules can be
     * compiled into a small, auditable DRL predicate while production still
     * runs through the Drools engine.</p>
     */
    public boolean matches(String field, String operator, String expected) {
        if (!hasText(field) || !hasText(operator) || expected == null) {
            return false;
        }
        Object actual = factors.get(field);
        if (actual == null || isUnavailable(actual)) {
            return false;
        }
        String normalizedOperator = operator.trim().toLowerCase(Locale.ROOT);
        try {
            return switch (normalizedOperator) {
                case "eq" -> String.valueOf(actual).equals(expected);
                case "ne" -> String.valueOf(actual).equals(expected) ? false : true;
                case "gt" -> compareDecimal(actual, expected) > 0;
                case "gte" -> compareDecimal(actual, expected) >= 0;
                case "lt" -> compareDecimal(actual, expected) < 0;
                case "lte" -> compareDecimal(actual, expected) <= 0;
                default -> false;
            };
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static BigDecimal decimalFactor(Map<String, Object> factors, String... keys) {
        Object value = factorValue(factors, keys);
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof Number number) {
                return new BigDecimal(number.toString());
            }
            String text = String.valueOf(value).trim();
            return hasText(text) ? new BigDecimal(text) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String textFactor(Map<String, Object> factors, String... keys) {
        Object value = factorValue(factors, keys);
        return value == null ? null : String.valueOf(value);
    }

    private static Object factorValue(Map<String, Object> factors, String... keys) {
        for (String key : keys) {
            if (factors.containsKey(key)) {
                return factors.get(key);
            }
        }
        return null;
    }

    private static BigDecimal safeScore(BigDecimal score) {
        return score == null ? BigDecimal.ZERO : score;
    }

    private static int compareDecimal(Object actual, String expected) {
        return new BigDecimal(String.valueOf(actual)).compareTo(new BigDecimal(expected));
    }

    private static boolean isUnavailable(Object actual) {
        if (!(actual instanceof CharSequence text)) {
            return false;
        }
        String normalized = text.toString().trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || "unknown".equals(normalized)
                || "suspended".equals(normalized)
                || "suspended_or_missing".equals(normalized)
                || "insufficient_data".equals(normalized)
                || "data_insufficient".equals(normalized);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
