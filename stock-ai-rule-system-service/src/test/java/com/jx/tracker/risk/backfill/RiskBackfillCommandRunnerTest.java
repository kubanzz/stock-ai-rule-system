package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.runtime.RiskBackfillService;
import com.jx.tracker.risk.runtime.RiskUniverseReader;
import com.jx.tracker.risk.runtime.RiskWarningProperties;
import com.jx.tracker.risk.workflow.RiskWorkflowRunSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiskBackfillCommandRunnerTest {

    private static final LocalDate END_DATE = LocalDate.of(2026, 7, 10);
    private static final RiskWorkflowRunSummary SUCCESS_SUMMARY =
            new RiskWorkflowRunSummary(100, 10, 30, 300, 0, 12, 0);

    private final RiskUniverseReader universeReader = mock(RiskUniverseReader.class);
    private final RiskBackfillService backfillService = mock(RiskBackfillService.class);
    private final RiskBackfillPreflightService preflightService =
            mock(RiskBackfillPreflightService.class);
    private final RiskBackfillReadinessRepository readinessRepository =
            mock(RiskBackfillReadinessRepository.class);
    private final RiskBackfillReadinessEvaluator readinessEvaluator =
            mock(RiskBackfillReadinessEvaluator.class);
    private final RiskBackfillReportStore reportStore = mock(RiskBackfillReportStore.class);
    private final RiskBackfillReadinessData readinessData = mock(RiskBackfillReadinessData.class);
    private final RiskBackfillReadiness ready = mock(RiskBackfillReadiness.class);
    private final List<String> universe = IntStream.rangeClosed(1, 100)
            .mapToObj(index -> "%06d.SH".formatted(index)).toList();

    private RiskBackfillCommandProperties command;
    private RiskWarningProperties warning;

    @BeforeEach
    void setUp() throws Exception {
        command = validCommand();
        warning = enabledWarning();
        when(universeReader.activeAshareSymbols()).thenReturn(universe);
        when(preflightService.configurationFailures(command, warning)).thenReturn(List.of());
        when(preflightService.check(command, warning, universe)).thenReturn(readyPreflight());
        when(readinessRepository.load(eq("risk-v1"), any(), eq(END_DATE), any(), anyList()))
                .thenReturn(readinessData);
        when(readinessEvaluator.evaluate(readinessData)).thenReturn(ready);
        when(ready.ready()).thenReturn(true);
        when(reportStore.write(any(), any())).thenReturn(Path.of("report.json"));
    }

    @Test
    void stagedRunsFullMarketOnlyAfterTheSampleGatePasses() {
        List<String> sample = new RiskBackfillSampleSelector()
                .select(universe, List.of(), 50);
        when(backfillService.runFiveYearBackfill(END_DATE, sample))
                .thenReturn(Optional.of(SUCCESS_SUMMARY));
        when(backfillService.runFiveYearBackfill(eq(END_DATE), anyList()))
                .thenReturn(Optional.of(SUCCESS_SUMMARY));

        RiskBackfillCommandResult result = runner().run();

        assertThat(result.exitCode()).isEqualTo(RiskBackfillExitCode.SUCCESS);
        assertThat(result.report().sampleGatePassed()).isTrue();
        assertThat(result.report().fullSummary()).isEqualTo(
                new RiskWorkflowRunSummary(400, 40, 120, 1200, 0, 48, 0));
        assertThat(result.report().fullChunkCount()).isEqualTo(4);
        verify(backfillService).runFiveYearBackfill(END_DATE, sample);
        for (int offset = 0; offset < universe.size(); offset += 25) {
            verify(backfillService).runFiveYearBackfill(
                    END_DATE, universe.subList(offset, Math.min(offset + 25, universe.size())));
        }
        verify(backfillService, never()).runFiveYearBackfill(END_DATE, List.of());
    }

    @Test
    void stagedNeverCallsFullBackfillWhenSampleGateFails() {
        List<String> sample = new RiskBackfillSampleSelector()
                .select(universe, List.of(), 50);
        RiskWorkflowRunSummary unavailable =
                new RiskWorkflowRunSummary(10, 0, 3, 2, 0, 1, 1);
        when(backfillService.runFiveYearBackfill(END_DATE, sample))
                .thenReturn(Optional.of(unavailable));

        RiskBackfillCommandResult result = runner().run();

        assertThat(result.exitCode()).isEqualTo(RiskBackfillExitCode.SAMPLE_GATE_REJECTED);
        assertThat(result.report().sampleGatePassed()).isFalse();
        assertThat(result.report().failures())
                .contains("样本回填存在不可用数据集：1");
        verify(backfillService, never()).runFiveYearBackfill(END_DATE, List.of());
    }

    @Test
    void sampleModeStopsAfterAValidatedSample() {
        command.setMode(RiskBackfillMode.SAMPLE);
        when(preflightService.configurationFailures(command, warning)).thenReturn(List.of());
        when(preflightService.check(command, warning, universe)).thenReturn(readyPreflight());
        when(backfillService.runFiveYearBackfill(eq(END_DATE), anyList()))
                .thenReturn(Optional.of(SUCCESS_SUMMARY));

        RiskBackfillCommandResult result = runner().run();

        assertThat(result.exitCode()).isEqualTo(RiskBackfillExitCode.SUCCESS);
        assertThat(result.report().fullSummary()).isNull();
        verify(backfillService, never()).runFiveYearBackfill(END_DATE, List.of());
    }

    @Test
    void sampleRecoveryScoresStoredDataWithoutRepeatingCollection() {
        command.setMode(RiskBackfillMode.SAMPLE);
        command.setResumeFromStoredData(true);
        when(preflightService.configurationFailures(command, warning)).thenReturn(List.of());
        when(preflightService.check(command, warning, universe)).thenReturn(readyPreflight());
        when(backfillService.runFiveYearBackfillFromStoredData(eq(END_DATE), anyList()))
                .thenReturn(Optional.of(SUCCESS_SUMMARY));

        RiskBackfillCommandResult result = runner().run();

        assertThat(result.exitCode()).isEqualTo(RiskBackfillExitCode.SUCCESS);
        verify(backfillService).runFiveYearBackfillFromStoredData(eq(END_DATE), anyList());
        verify(backfillService, never()).runFiveYearBackfill(eq(END_DATE), anyList());
    }

    @Test
    void fullModeRequiresMatchingSampleReport() {
        command.setMode(RiskBackfillMode.FULL);
        when(preflightService.configurationFailures(command, warning)).thenReturn(List.of());
        when(preflightService.check(command, warning, universe)).thenReturn(readyPreflight());
        when(reportStore.hasPassedSampleGate(command.getReportDirectory(), "risk-v1", END_DATE))
                .thenReturn(false);

        RiskBackfillCommandResult result = runner().run();

        assertThat(result.exitCode()).isEqualTo(RiskBackfillExitCode.CONFIGURATION_ERROR);
        assertThat(result.report().failures()).contains(
                "full mode requires a matching passed sample report");
        verifyNoInteractions(backfillService);
    }

    @Test
    void mapsPreflightFailureWithoutCallingBackfill() {
        when(preflightService.check(command, warning, universe)).thenReturn(new RiskBackfillPreflight(
                List.of(), List.of("derived gateway is not configured"), "2", true,
                universe.size(), SourceProbeResult.reachable("aktools"),
                SourceProbeResult.unreachable(
                        "derived-gateway", "derived gateway is not configured"),
                true, 0));

        RiskBackfillCommandResult result = runner().run();

        assertThat(result.exitCode()).isEqualTo(RiskBackfillExitCode.PREFLIGHT_ERROR);
        assertThat(result.report().failures()).contains("derived gateway is not configured");
        verifyNoInteractions(backfillService);
    }

    @Test
    void mapsSampleAndFullExecutionFailuresToTheirStableCodes() {
        when(backfillService.runFiveYearBackfill(eq(END_DATE), anyList()))
                .thenThrow(new IllegalStateException("sample source exploded"));

        RiskBackfillCommandResult sampleFailure = runner().run();

        assertThat(sampleFailure.exitCode())
                .isEqualTo(RiskBackfillExitCode.SAMPLE_EXECUTION_ERROR);
        assertThat(sampleFailure.report().error().message()).contains("sample source exploded");
    }

    @Test
    void reportWriteFailureOverridesTheStageExitCode() throws Exception {
        when(backfillService.runFiveYearBackfill(eq(END_DATE), anyList()))
                .thenReturn(Optional.of(SUCCESS_SUMMARY));
        when(reportStore.write(any(), any())).thenThrow(new IOException("disk full"));

        RiskBackfillCommandResult result = runner().run();

        assertThat(result.exitCode()).isEqualTo(RiskBackfillExitCode.REPORT_WRITE_ERROR);
        assertThat(result.reportPath()).isNull();
        assertThat(result.report().error().message()).contains("disk full");
    }

    @Test
    void configurationFailureDoesNotReadTheUniverseOrTouchInfrastructure() {
        when(preflightService.configurationFailures(command, warning))
                .thenReturn(List.of("backfill-enabled must be true"));

        RiskBackfillCommandResult result = runner().run();

        assertThat(result.exitCode()).isEqualTo(RiskBackfillExitCode.CONFIGURATION_ERROR);
        verifyNoInteractions(universeReader, backfillService, readinessRepository);
        verify(preflightService, never()).check(any(), any(), anyList());
    }

    private RiskBackfillCommandRunner runner() {
        return new RiskBackfillCommandRunner(
                command, warning,
                Optional.of(universeReader), Optional.of(backfillService),
                new RiskBackfillSampleSelector(), preflightService,
                readinessRepository, readinessEvaluator, reportStore,
                Clock.fixed(Instant.parse("2026-07-19T02:30:00Z"),
                        ZoneId.of("Asia/Shanghai")),
                () -> "run-test");
    }

    private RiskBackfillPreflight readyPreflight() {
        return new RiskBackfillPreflight(
                List.of(), List.of(), "2", true, universe.size(),
                SourceProbeResult.reachable("aktools"),
                SourceProbeResult.reachable("derived-gateway"),
                true, 0);
    }

    private RiskBackfillCommandProperties validCommand() {
        RiskBackfillCommandProperties properties = new RiskBackfillCommandProperties();
        properties.setEnabled(true);
        properties.setMode(RiskBackfillMode.STAGED);
        properties.setEndDate(END_DATE);
        properties.setSampleSize(50);
        properties.setConfirmation(RiskBackfillCommandProperties.REQUIRED_CONFIRMATION);
        properties.setReportDirectory(Path.of("target/test-risk-reports"));
        return properties;
    }

    private RiskWarningProperties enabledWarning() {
        RiskWarningProperties properties = new RiskWarningProperties();
        properties.setEnabled(true);
        properties.setBackfillEnabled(true);
        properties.setModelVersion("risk-v1");
        properties.setAfterCloseCutoff(LocalTime.of(20, 0));
        properties.setCollectionChunkSize(25);
        return properties;
    }
}
