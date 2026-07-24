package com.jx.tracker.risk.backfill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskBackfillCommandPropertiesTest {

    @Test
    void hasSafeStagedDefaults() {
        RiskBackfillCommandProperties properties = new RiskBackfillCommandProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getMode()).isEqualTo(RiskBackfillMode.STAGED);
        assertThat(properties.getSampleSize()).isEqualTo(50);
        assertThat(properties.getSymbols()).isEmpty();
        assertThat(properties.getReportDirectory())
                .isEqualTo(Path.of("target/risk-backfill/reports"));
    }

    @Test
    void acceptsAnExplicitlyConfirmedSampleCommand() {
        RiskBackfillCommandProperties properties = validProperties();
        properties.setMode(RiskBackfillMode.SAMPLE);
        properties.setSymbols(List.of("600519.SH", "000001.SZ"));

        properties.validate();

        assertThat(properties.normalizedSymbols())
                .containsExactly("600519.SH", "000001.SZ");
    }

    @Test
    void rejectsExecutionWithoutExactConfirmationToken() {
        RiskBackfillCommandProperties properties = validProperties();
        properties.setConfirmation("backfill_5y");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BACKFILL_5Y");
    }

    @Test
    void rejectsMissingEndDate() {
        RiskBackfillCommandProperties properties = validProperties();
        properties.setEndDate(null);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endDate");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 501})
    void rejectsOutOfRangeSampleSize(int sampleSize) {
        RiskBackfillCommandProperties properties = validProperties();
        properties.setSampleSize(sampleSize);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sampleSize");
    }

    @Test
    void explicitSymbolsAreSampleOnly() {
        RiskBackfillCommandProperties properties = validProperties();
        properties.setMode(RiskBackfillMode.STAGED);
        properties.setSymbols(List.of("600519.SH"));

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("symbols are only allowed in sample mode");
    }

    @Test
    void ignoresBlankSymbolsFromEnvironmentBinding() {
        RiskBackfillCommandProperties properties = validProperties();
        properties.setMode(RiskBackfillMode.SAMPLE);
        properties.setSymbols(List.of(" ", "600519.SH", ""));

        properties.validate();

        assertThat(properties.normalizedSymbols()).containsExactly("600519.SH");
    }

    @Test
    void exposesStableProcessExitCodes() {
        assertThat(RiskBackfillExitCode.values())
                .extracting(RiskBackfillExitCode::code)
                .containsExactly(0, 2, 3, 4, 5, 6, 7, 8);
    }

    private RiskBackfillCommandProperties validProperties() {
        RiskBackfillCommandProperties properties = new RiskBackfillCommandProperties();
        properties.setEnabled(true);
        properties.setMode(RiskBackfillMode.STAGED);
        properties.setEndDate(LocalDate.of(2026, 7, 10));
        properties.setSampleSize(50);
        properties.setConfirmation("BACKFILL_5Y");
        return properties;
    }
}
