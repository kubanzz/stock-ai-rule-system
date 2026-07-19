package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.runtime.RiskBackfillService;
import com.jx.tracker.risk.runtime.RiskUniverseReader;
import com.jx.tracker.risk.runtime.RiskWarningProperties;
import com.jx.tracker.risk.workflow.RiskWorkflowRequest;
import com.jx.tracker.risk.workflow.RiskWorkflowRunSummary;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class RiskBackfillCommandRunner {

    private final RiskBackfillCommandProperties command;
    private final RiskWarningProperties warning;
    private final Optional<RiskUniverseReader> universeReader;
    private final Optional<RiskBackfillService> backfillService;
    private final RiskBackfillSampleSelector sampleSelector;
    private final RiskBackfillPreflightService preflightService;
    private final RiskBackfillReadinessRepository readinessRepository;
    private final RiskBackfillReadinessEvaluator readinessEvaluator;
    private final RiskBackfillReportStore reportStore;
    private final Clock clock;
    private final Supplier<String> runIdSupplier;

    public RiskBackfillCommandRunner(
            RiskBackfillCommandProperties command,
            RiskWarningProperties warning,
            Optional<RiskUniverseReader> universeReader,
            Optional<RiskBackfillService> backfillService,
            RiskBackfillSampleSelector sampleSelector,
            RiskBackfillPreflightService preflightService,
            RiskBackfillReadinessRepository readinessRepository,
            RiskBackfillReadinessEvaluator readinessEvaluator,
            RiskBackfillReportStore reportStore,
            Clock clock
    ) {
        this(command, warning, universeReader, backfillService, sampleSelector,
                preflightService, readinessRepository, readinessEvaluator,
                reportStore, clock, () -> UUID.randomUUID().toString());
    }

    RiskBackfillCommandRunner(
            RiskBackfillCommandProperties command,
            RiskWarningProperties warning,
            Optional<RiskUniverseReader> universeReader,
            Optional<RiskBackfillService> backfillService,
            RiskBackfillSampleSelector sampleSelector,
            RiskBackfillPreflightService preflightService,
            RiskBackfillReadinessRepository readinessRepository,
            RiskBackfillReadinessEvaluator readinessEvaluator,
            RiskBackfillReportStore reportStore,
            Clock clock,
            Supplier<String> runIdSupplier
    ) {
        if (command == null || warning == null || universeReader == null || backfillService == null
                || sampleSelector == null || preflightService == null
                || readinessRepository == null || readinessEvaluator == null
                || reportStore == null || clock == null || runIdSupplier == null) {
            throw new IllegalArgumentException("risk backfill command dependencies are required");
        }
        this.command = command;
        this.warning = warning;
        this.universeReader = universeReader;
        this.backfillService = backfillService;
        this.sampleSelector = sampleSelector;
        this.preflightService = preflightService;
        this.readinessRepository = readinessRepository;
        this.readinessEvaluator = readinessEvaluator;
        this.reportStore = reportStore;
        this.clock = clock;
        this.runIdSupplier = runIdSupplier;
    }

    public RiskBackfillCommandResult run() {
        RunState state = new RunState(
                safeRunId(), LocalDateTime.now(clock), mode(), modelVersion(), command.getEndDate());
        List<String> configurationFailures = new ArrayList<>(
                preflightService.configurationFailures(command, warning));
        if (universeReader.isEmpty()) {
            configurationFailures.add("risk universe reader is unavailable");
        }
        if (backfillService.isEmpty()) {
            configurationFailures.add("risk backfill service is unavailable");
        }
        if (!configurationFailures.isEmpty()) {
            return finish(state, RiskBackfillExitCode.CONFIGURATION_ERROR,
                    "configuration", configurationFailures, null);
        }

        List<String> activeSymbols;
        try {
            activeSymbols = List.copyOf(universeReader.orElseThrow().activeAshareSymbols());
        } catch (RuntimeException exception) {
            return finish(state, RiskBackfillExitCode.PREFLIGHT_ERROR,
                    "universe-preflight", List.of("failed to read active A-share universe"), exception);
        }
        state.activeStockCount = activeSymbols.size();
        state.fullChunkCount = chunks(activeSymbols.size(), warning.requiredCollectionChunkSize());

        RiskBackfillPreflight preflight = preflightService.check(command, warning, activeSymbols);
        state.preflight = preflight;
        if (!preflight.configurationReady()) {
            return finish(state, RiskBackfillExitCode.CONFIGURATION_ERROR,
                    "configuration", preflight.configurationFailures(), null);
        }
        if (!preflight.environmentReady()) {
            return finish(state, RiskBackfillExitCode.PREFLIGHT_ERROR,
                    "environment-preflight", preflight.environmentFailures(), null);
        }

        if (mode() == RiskBackfillMode.FULL) {
            if (!reportStore.hasPassedSampleGate(
                    command.getReportDirectory(), modelVersion(), command.getEndDate())) {
                return finish(state, RiskBackfillExitCode.CONFIGURATION_ERROR,
                        "full-authorization",
                        List.of("full mode requires a matching passed sample report"), null);
            }
            state.sampleGatePassed = true;
            return runFull(state, activeSymbols);
        }
        return runSample(state, activeSymbols);
    }

    private RiskBackfillCommandResult runSample(RunState state, List<String> activeSymbols) {
        try {
            state.sampleSymbols = sampleSelector.select(
                    activeSymbols, command.normalizedSymbols(), command.getSampleSize());
            Optional<RiskWorkflowRunSummary> result = backfillService.orElseThrow()
                    .runFiveYearBackfill(command.getEndDate(), state.sampleSymbols);
            if (result.isEmpty()) {
                return finish(state, RiskBackfillExitCode.CONFIGURATION_ERROR,
                        "sample-execution", List.of("risk backfill service is disabled"), null);
            }
            state.sampleSummary = result.orElseThrow();
            state.sampleReadiness = readiness(state.sampleSymbols);
        } catch (RuntimeException exception) {
            return finish(state, RiskBackfillExitCode.SAMPLE_EXECUTION_ERROR,
                    "sample-execution", List.of("sample backfill execution failed"), exception);
        }

        List<String> failures = new ArrayList<>();
        if (state.sampleSummary.unavailableDatasetCount() > 0) {
            failures.add("样本回填存在不可用数据集："
                    + state.sampleSummary.unavailableDatasetCount());
        }
        failures.addAll(safeFailures(state.sampleReadiness));
        state.sampleGatePassed = state.sampleSummary.unavailableDatasetCount() == 0
                && state.sampleReadiness.ready();
        if (!state.sampleGatePassed) {
            return finish(state, RiskBackfillExitCode.SAMPLE_GATE_REJECTED,
                    "sample-validation", failures, null);
        }
        if (mode() == RiskBackfillMode.SAMPLE) {
            return finish(state, RiskBackfillExitCode.SUCCESS,
                    "sample-validation", List.of(), null);
        }
        return runFull(state, activeSymbols);
    }

    private RiskBackfillCommandResult runFull(RunState state, List<String> activeSymbols) {
        try {
            Optional<RiskWorkflowRunSummary> result = backfillService.orElseThrow()
                    .runFiveYearBackfill(command.getEndDate(), List.of());
            if (result.isEmpty()) {
                return finish(state, RiskBackfillExitCode.CONFIGURATION_ERROR,
                        "full-execution", List.of("risk backfill service is disabled"), null);
            }
            state.fullSummary = result.orElseThrow();
        } catch (RuntimeException exception) {
            return finish(state, RiskBackfillExitCode.FULL_EXECUTION_ERROR,
                    "full-execution", List.of("full-market backfill execution failed"), exception);
        }

        try {
            state.finalReadiness = readiness(activeSymbols);
        } catch (RuntimeException exception) {
            RiskBackfillExitCode code = state.fullSummary.unavailableDatasetCount() > 0
                    ? RiskBackfillExitCode.FULL_EXECUTION_ERROR
                    : RiskBackfillExitCode.FINAL_VALIDATION_ERROR;
            return finish(state, code, "final-validation",
                    List.of("final readiness evaluation failed"), exception);
        }
        List<String> failures = new ArrayList<>();
        if (state.fullSummary.unavailableDatasetCount() > 0) {
            failures.add("全市场回填存在不可用数据集："
                    + state.fullSummary.unavailableDatasetCount());
        }
        failures.addAll(safeFailures(state.finalReadiness));
        if (state.fullSummary.unavailableDatasetCount() > 0) {
            return finish(state, RiskBackfillExitCode.FULL_EXECUTION_ERROR,
                    "full-execution", failures, null);
        }
        if (!state.finalReadiness.ready()) {
            return finish(state, RiskBackfillExitCode.FINAL_VALIDATION_ERROR,
                    "final-validation", failures, null);
        }
        return finish(state, RiskBackfillExitCode.SUCCESS,
                "final-validation", List.of(), null);
    }

    private RiskBackfillReadiness readiness(List<String> stockSymbols) {
        LocalDate endDate = command.getEndDate();
        LocalDate scoreStartDate = endDate.minusYears(RiskWorkflowRequest.BACKFILL_SCORE_YEARS);
        RiskBackfillReadinessData data = readinessRepository.load(
                modelVersion(), scoreStartDate, endDate, pointInTime(endDate), stockSymbols);
        return readinessEvaluator.evaluate(data);
    }

    private LocalDateTime pointInTime(LocalDate endDate) {
        LocalDateTime cutoff = endDate.atTime(warning.requiredAfterCloseCutoff());
        LocalDateTime now = LocalDateTime.now(clock);
        return now.isBefore(cutoff) ? now : cutoff;
    }

    private RiskBackfillCommandResult finish(
            RunState state,
            RiskBackfillExitCode exitCode,
            String stage,
            List<String> failures,
            Throwable error
    ) {
        RiskBackfillReport report = state.report(
                exitCode, stage, failures, error, LocalDateTime.now(clock));
        try {
            Path reportPath = reportStore.write(command.getReportDirectory(), report);
            return new RiskBackfillCommandResult(exitCode, reportPath, report);
        } catch (Exception reportException) {
            List<String> reportFailures = new ArrayList<>(failures == null ? List.of() : failures);
            reportFailures.add("risk backfill report write failed");
            RiskBackfillReport failedReport = state.report(
                    RiskBackfillExitCode.REPORT_WRITE_ERROR,
                    "report-write", reportFailures, reportException, LocalDateTime.now(clock));
            return new RiskBackfillCommandResult(
                    RiskBackfillExitCode.REPORT_WRITE_ERROR, null, failedReport);
        }
    }

    private List<String> safeFailures(RiskBackfillReadiness readiness) {
        return readiness == null || readiness.failures() == null
                ? List.of() : readiness.failures();
    }

    private int chunks(int size, int chunkSize) {
        return size == 0 ? 0 : (size + chunkSize - 1) / chunkSize;
    }

    private RiskBackfillMode mode() {
        return command.getMode() == null ? RiskBackfillMode.STAGED : command.getMode();
    }

    private String modelVersion() {
        String value = warning.getModelVersion();
        return value == null || value.isBlank() ? "unconfigured" : value.trim();
    }

    private String safeRunId() {
        String value = runIdSupplier.get();
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
    }

    private static RiskBackfillReport.ErrorSummary error(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return new RiskBackfillReport.ErrorSummary(
                root.getClass().getName(),
                message == null || message.isBlank() ? root.getClass().getSimpleName() : message);
    }

    private static final class RunState {
        private final String runId;
        private final LocalDateTime startedAt;
        private final RiskBackfillMode mode;
        private final String modelVersion;
        private final LocalDate endDate;
        private int activeStockCount;
        private int fullChunkCount;
        private RiskBackfillPreflight preflight;
        private List<String> sampleSymbols = List.of();
        private RiskWorkflowRunSummary sampleSummary;
        private RiskWorkflowRunSummary fullSummary;
        private RiskBackfillReadiness sampleReadiness;
        private RiskBackfillReadiness finalReadiness;
        private boolean sampleGatePassed;

        private RunState(
                String runId,
                LocalDateTime startedAt,
                RiskBackfillMode mode,
                String modelVersion,
                LocalDate endDate
        ) {
            this.runId = runId;
            this.startedAt = startedAt;
            this.mode = mode;
            this.modelVersion = modelVersion;
            this.endDate = endDate;
        }

        private RiskBackfillReport report(
                RiskBackfillExitCode exitCode,
                String stage,
                List<String> failures,
                Throwable throwable,
                LocalDateTime finishedAt
        ) {
            LocalDate scoreStartDate = endDate == null ? null
                    : endDate.minusYears(RiskWorkflowRequest.BACKFILL_SCORE_YEARS);
            LocalDate collectionStartDate = scoreStartDate == null ? null
                    : RiskWorkflowRequest.baselineCollectionStart(scoreStartDate);
            return new RiskBackfillReport(
                    1, runId, mode, modelVersion, stage, startedAt, finishedAt,
                    exitCode.code(), exitCode == RiskBackfillExitCode.SUCCESS ? "success" : "failed",
                    endDate, scoreStartDate, collectionStartDate,
                    sampleSymbols, activeStockCount, fullChunkCount,
                    preflight, sampleSummary, fullSummary, sampleReadiness, finalReadiness,
                    sampleGatePassed, failures == null ? List.of() : failures,
                    RiskBackfillCommandRunner.error(throwable));
        }
    }
}
