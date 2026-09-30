package com.jx.tracker.domain.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** A persisted progress snapshot for the watchlist quote and signal catch-up job. */
public record SignalBackfillRunVo(
        String runId,
        String status,
        String stage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDate latestCompletedTradeDate,
        int totalSymbols,
        int totalDates,
        int totalTasks,
        int completedTasks,
        int syncedQuotes,
        int calculatedFactors,
        int generatedSignals,
        int backfilledSignalCount,
        List<Gap> missingQuotes,
        List<Failure> failures
) {
    public record Gap(String symbol, LocalDate tradeDate, String reason) {}
    public record Failure(String symbol, LocalDate tradeDate, String stage, String reason) {}
}
