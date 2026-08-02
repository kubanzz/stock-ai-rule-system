package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.runtime.RiskWarningProperties;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RiskBackfillPreflightService {

    private static final BigDecimal MINIMUM_FLYWAY_VERSION = new BigDecimal("2");

    private final RiskBackfillPreflightRepository repository;
    private final RiskBackfillSourceProbe sourceProbe;

    public RiskBackfillPreflightService(
            RiskBackfillPreflightRepository repository,
            RiskBackfillSourceProbe sourceProbe
    ) {
        if (repository == null || sourceProbe == null) {
            throw new IllegalArgumentException("preflight repository and sourceProbe are required");
        }
        this.repository = repository;
        this.sourceProbe = sourceProbe;
    }

    public RiskBackfillPreflight check(
            RiskBackfillCommandProperties command,
            RiskWarningProperties warning,
            List<String> activeSymbols
    ) {
        List<String> configurationFailures = configurationFailures(command, warning);
        if (!configurationFailures.isEmpty()) {
            return result(configurationFailures, List.of(), null, false,
                    size(activeSymbols), null, null, null, false, 0L);
        }

        LocalDate endDate = command.getEndDate();
        List<String> environmentFailures = new ArrayList<>();
        Optional<String> flywayVersion = safelyReadFlywayVersion(environmentFailures);
        boolean flywayReady = flywayVersion.filter(this::atLeastV2).isPresent();
        if (!flywayReady) {
            environmentFailures.add("Flyway schema version must be at least 2, actual: "
                    + flywayVersion.orElse("missing"));
        }
        boolean openTradingDay = safelyReadTradingDay(endDate, environmentFailures);
        if (!openTradingDay) {
            environmentFailures.add("endDate is not an open A-share trading day: " + endDate);
        }
        int activeStockCount = size(activeSymbols);
        if (activeStockCount == 0) {
            environmentFailures.add("active A-share universe must not be empty");
        }
        long enforcedGateCount = safelyReadEnforcedGateCount(environmentFailures);
        if (enforcedGateCount > 0) {
            environmentFailures.add(
                    "risk gate must remain shadow-only, enforced rows: " + enforcedGateCount);
        }
        boolean reportDirectoryWritable = writable(command.getReportDirectory(), environmentFailures);

        SourceProbeResult tushare = SourceProbeResult.notProbed("tushare");
        SourceProbeResult akTools = SourceProbeResult.notProbed("aktools");
        SourceProbeResult derived = SourceProbeResult.notProbed("derived-gateway");
        if (environmentFailures.isEmpty()) {
            if ("tushare".equals(warning.requiredPrimarySource())) {
                tushare = safeProbe(
                        () -> sourceProbe.probeTushare(endDate), "tushare");
                if (!tushare.reachable()) {
                    environmentFailures.add(tushare.detail());
                }
            }
            akTools = safeProbe(() -> sourceProbe.probeAkTools(endDate), "aktools");
            if (!akTools.reachable()) {
                environmentFailures.add(akTools.detail());
            }
            derived = safeProbe(
                    () -> sourceProbe.probeDerivedGateway(endDate), "derived-gateway");
            if (!derived.reachable()) {
                environmentFailures.add(derived.detail());
            }
        }
        return result(configurationFailures, environmentFailures,
                flywayVersion.orElse(null), openTradingDay, activeStockCount,
                tushare, akTools, derived, reportDirectoryWritable, enforcedGateCount);
    }

    public List<String> configurationFailures(
            RiskBackfillCommandProperties command,
            RiskWarningProperties warning
    ) {
        List<String> failures = new ArrayList<>();
        if (command == null) {
            failures.add("risk backfill command properties are required");
        } else {
            capture(command::validate, failures);
        }
        if (warning == null) {
            failures.add("risk warning properties are required");
            return List.copyOf(failures);
        }
        if (!warning.isEnabled()) {
            failures.add("stock-ai-rule.risk-warning.enabled must be true");
        }
        if (!warning.isBackfillEnabled()) {
            failures.add("stock-ai-rule.risk-warning.backfill-enabled must be true");
        }
        capture(warning::requiredModelVersion, failures);
        capture(warning::requiredAfterCloseCutoff, failures);
        capture(warning::requiredCollectionChunkSize, failures);
        capture(warning::requiredPrimarySource, failures);
        return List.copyOf(failures);
    }

    private Optional<String> safelyReadFlywayVersion(List<String> failures) {
        try {
            return repository.latestSuccessfulFlywayVersion();
        } catch (RuntimeException exception) {
            failures.add("failed to read Flyway schema history: " + rootMessage(exception));
            return Optional.empty();
        }
    }

    private boolean safelyReadTradingDay(LocalDate endDate, List<String> failures) {
        try {
            return repository.isOpenAshareTradingDay(endDate);
        } catch (RuntimeException exception) {
            failures.add("failed to read A-share trading calendar: " + rootMessage(exception));
            return false;
        }
    }

    private long safelyReadEnforcedGateCount(List<String> failures) {
        try {
            return repository.enforcedGateCount();
        } catch (RuntimeException exception) {
            failures.add("failed to verify shadow gate constraint: " + rootMessage(exception));
            return 0L;
        }
    }

    private boolean writable(Path directory, List<String> failures) {
        Path probeFile = null;
        try {
            Files.createDirectories(directory);
            probeFile = Files.createTempFile(directory, "risk-backfill-preflight-", ".tmp");
            return true;
        } catch (IOException | RuntimeException exception) {
            failures.add("risk backfill report directory is not writable: " + rootMessage(exception));
            return false;
        } finally {
            if (probeFile != null) {
                try {
                    Files.deleteIfExists(probeFile);
                } catch (IOException ignored) {
                    // The command report writer will surface a later cleanup/write failure.
                }
            }
        }
    }

    private boolean atLeastV2(String version) {
        try {
            return new BigDecimal(version).compareTo(MINIMUM_FLYWAY_VERSION) >= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private SourceProbeResult safeProbe(ProbeOperation operation, String source) {
        try {
            SourceProbeResult result = operation.probe();
            return result == null
                    ? SourceProbeResult.unreachable(source, source + " probe returned no result")
                    : result;
        } catch (RuntimeException exception) {
            return SourceProbeResult.unreachable(
                    source, source + " probe failed: " + rootMessage(exception));
        }
    }

    private void capture(ValidationOperation operation, List<String> failures) {
        try {
            operation.validate();
        } catch (RuntimeException exception) {
            failures.add(rootMessage(exception));
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null || root.getMessage().isBlank()
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    private int size(List<String> symbols) {
        return symbols == null ? 0 : symbols.size();
    }

    private RiskBackfillPreflight result(
            List<String> configurationFailures,
            List<String> environmentFailures,
            String flywayVersion,
            boolean openTradingDay,
            int activeStockCount,
            SourceProbeResult tushare,
            SourceProbeResult akTools,
            SourceProbeResult derived,
            boolean reportDirectoryWritable,
            long enforcedGateCount
    ) {
        return new RiskBackfillPreflight(
                configurationFailures, environmentFailures, flywayVersion,
                openTradingDay, activeStockCount, tushare, akTools, derived,
                reportDirectoryWritable, enforcedGateCount);
    }

    @FunctionalInterface
    private interface ValidationOperation {
        void validate();
    }

    @FunctionalInterface
    private interface ProbeOperation {
        SourceProbeResult probe();
    }
}
