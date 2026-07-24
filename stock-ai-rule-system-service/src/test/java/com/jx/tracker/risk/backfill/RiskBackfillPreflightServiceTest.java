package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.runtime.RiskWarningProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiskBackfillPreflightServiceTest {

    private static final LocalDate END_DATE = LocalDate.of(2026, 7, 10);

    @TempDir
    Path temporaryDirectory;

    private final RiskBackfillPreflightRepository repository =
            mock(RiskBackfillPreflightRepository.class);
    private final RiskBackfillSourceProbe sourceProbe = mock(RiskBackfillSourceProbe.class);
    private final RiskBackfillPreflightService service =
            new RiskBackfillPreflightService(repository, sourceProbe);

    @Test
    void acceptsACompleteSafeEnvironment() {
        arrangeReadyEnvironment();

        RiskBackfillPreflight result = service.check(
                validCommand(), enabledRiskProperties(), List.of("600519.SH"));

        assertThat(result.configurationReady()).isTrue();
        assertThat(result.environmentReady()).isTrue();
        assertThat(result.ready()).isTrue();
        assertThat(result.failures()).isEmpty();
        assertThat(result.flywayVersion()).isEqualTo("2");
        assertThat(result.activeStockCount()).isEqualTo(1);
    }

    @Test
    void doesNotTouchDatabaseOrNetworkWhenTheFourSwitchContractIsIncomplete() {
        RiskBackfillCommandProperties command = validCommand();
        command.setEnabled(false);
        RiskWarningProperties warning = enabledRiskProperties();
        warning.setBackfillEnabled(false);

        RiskBackfillPreflight result = service.check(
                command, warning, List.of("600519.SH"));

        assertThat(result.configurationReady()).isFalse();
        assertThat(result.configurationFailures())
                .anyMatch(message -> message.contains("command must be explicitly enabled"))
                .anyMatch(message -> message.contains("backfill-enabled"));
        verifyNoInteractions(repository, sourceProbe);
    }

    @Test
    void rejectsMissingHistoricalDerivedGatewayBeforeCallingProviders() {
        arrangeReadyEnvironment();
        when(sourceProbe.probeDerivedGateway(END_DATE)).thenReturn(
                SourceProbeResult.unreachable("derived-gateway", "derived gateway is not configured"));

        RiskBackfillPreflight result = service.check(
                validCommand(), enabledRiskProperties(), List.of("600519.SH"));

        assertThat(result.ready()).isFalse();
        assertThat(result.environmentFailures())
                .contains("derived gateway is not configured");
    }

    @Test
    void rejectsNonTradingDateAndEmptyUniverse() {
        arrangeReadyEnvironment();
        when(repository.isOpenAshareTradingDay(END_DATE)).thenReturn(false);

        RiskBackfillPreflight result = service.check(
                validCommand(), enabledRiskProperties(), List.of());

        assertThat(result.ready()).isFalse();
        assertThat(result.environmentFailures())
                .contains("endDate is not an open A-share trading day: 2026-07-10")
                .contains("active A-share universe must not be empty");
    }

    @Test
    void rejectsFlywayBeforeV2AndAnyEnforcedGate() {
        arrangeReadyEnvironment();
        when(repository.latestSuccessfulFlywayVersion()).thenReturn(Optional.of("1"));
        when(repository.enforcedGateCount()).thenReturn(2L);

        RiskBackfillPreflight result = service.check(
                validCommand(), enabledRiskProperties(), List.of("600519.SH"));

        assertThat(result.environmentFailures())
                .contains("Flyway schema version must be at least 2, actual: 1")
                .contains("risk gate must remain shadow-only, enforced rows: 2");
    }

    @Test
    void skipsSourceProbeWhenDatabasePreflightAlreadyFails() {
        arrangeReadyEnvironment();
        when(repository.latestSuccessfulFlywayVersion()).thenReturn(Optional.empty());

        RiskBackfillPreflight result = service.check(
                validCommand(), enabledRiskProperties(), List.of("600519.SH"));

        assertThat(result.ready()).isFalse();
        verify(sourceProbe, never()).probeAkTools(any());
        verify(sourceProbe, never()).probeDerivedGateway(any());
    }

    private void arrangeReadyEnvironment() {
        when(repository.latestSuccessfulFlywayVersion()).thenReturn(Optional.of("2"));
        when(repository.isOpenAshareTradingDay(END_DATE)).thenReturn(true);
        when(repository.enforcedGateCount()).thenReturn(0L);
        when(sourceProbe.probeAkTools(END_DATE))
                .thenReturn(SourceProbeResult.reachable("aktools"));
        when(sourceProbe.probeDerivedGateway(END_DATE))
                .thenReturn(SourceProbeResult.reachable("derived-gateway"));
    }

    private RiskBackfillCommandProperties validCommand() {
        RiskBackfillCommandProperties properties = new RiskBackfillCommandProperties();
        properties.setEnabled(true);
        properties.setMode(RiskBackfillMode.STAGED);
        properties.setEndDate(END_DATE);
        properties.setSampleSize(50);
        properties.setConfirmation(RiskBackfillCommandProperties.REQUIRED_CONFIRMATION);
        properties.setReportDirectory(temporaryDirectory.resolve("reports"));
        return properties;
    }

    private RiskWarningProperties enabledRiskProperties() {
        RiskWarningProperties properties = new RiskWarningProperties();
        properties.setEnabled(true);
        properties.setBackfillEnabled(true);
        properties.setModelVersion("risk-warning-v1");
        properties.setAfterCloseCutoff(LocalTime.of(20, 0));
        properties.setCollectionChunkSize(25);
        return properties;
    }
}
