package com.jx.tracker.risk.backfill;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;

@ConfigurationProperties(prefix = "stock-ai-rule.risk-warning.backfill-command")
public class RiskBackfillCommandProperties {

    public static final String REQUIRED_CONFIRMATION = "BACKFILL_5Y";
    public static final int DEFAULT_SAMPLE_SIZE = 50;

    private boolean enabled;
    private RiskBackfillMode mode = RiskBackfillMode.STAGED;
    private LocalDate endDate;
    private int sampleSize = DEFAULT_SAMPLE_SIZE;
    private List<String> symbols = List.of();
    private boolean resumeFromStoredData;
    private String confirmation;
    private Path reportDirectory = Path.of("target/risk-backfill/reports");

    public void validate() {
        if (!enabled) {
            throw new IllegalStateException("risk backfill command must be explicitly enabled");
        }
        if (mode == null) {
            throw new IllegalStateException("risk backfill command mode must be configured");
        }
        if (endDate == null) {
            throw new IllegalStateException("risk backfill command endDate must be configured");
        }
        if (sampleSize < 1 || sampleSize > 500) {
            throw new IllegalStateException("risk backfill command sampleSize must be between 1 and 500");
        }
        if (!REQUIRED_CONFIRMATION.equals(confirmation)) {
            throw new IllegalStateException(
                    "risk backfill command confirmation must equal " + REQUIRED_CONFIRMATION);
        }
        if (reportDirectory == null || reportDirectory.toString().isBlank()) {
            throw new IllegalStateException("risk backfill command reportDirectory must be configured");
        }
        if (mode != RiskBackfillMode.SAMPLE && !normalizedSymbols().isEmpty()) {
            throw new IllegalStateException("symbols are only allowed in sample mode");
        }
    }

    public List<String> normalizedSymbols() {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String symbol : symbols == null ? List.<String>of() : symbols) {
            if (symbol != null && !symbol.isBlank()) {
                normalized.add(symbol.trim());
            }
        }
        return List.copyOf(normalized);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public RiskBackfillMode getMode() {
        return mode;
    }

    public void setMode(RiskBackfillMode mode) {
        this.mode = mode;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public int getSampleSize() {
        return sampleSize;
    }

    public void setSampleSize(int sampleSize) {
        this.sampleSize = sampleSize;
    }

    public List<String> getSymbols() {
        return symbols == null ? List.of() : List.copyOf(symbols);
    }

    public void setSymbols(List<String> symbols) {
        this.symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }

    public boolean isResumeFromStoredData() {
        return resumeFromStoredData;
    }

    public void setResumeFromStoredData(boolean resumeFromStoredData) {
        this.resumeFromStoredData = resumeFromStoredData;
    }

    public String getConfirmation() {
        return confirmation;
    }

    public void setConfirmation(String confirmation) {
        this.confirmation = confirmation;
    }

    public Path getReportDirectory() {
        return reportDirectory;
    }

    public void setReportDirectory(Path reportDirectory) {
        this.reportDirectory = reportDirectory;
    }
}
