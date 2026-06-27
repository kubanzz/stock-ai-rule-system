package com.jx.tracker.factor;

import java.time.LocalDate;
import java.util.Map;

public record TechnicalFactorResult(String symbol, LocalDate tradeDate, Map<String, Object> factors) {
}
