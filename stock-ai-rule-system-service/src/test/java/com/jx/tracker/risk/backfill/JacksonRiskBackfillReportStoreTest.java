package com.jx.tracker.risk.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.workflow.RiskWorkflowRunSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonRiskBackfillReportStoreTest {

    private static final LocalDate END_DATE = LocalDate.of(2026, 7, 10);

    @TempDir
    Path directory;

    private final JacksonRiskBackfillReportStore store =
            new JacksonRiskBackfillReportStore(new ObjectMapper().findAndRegisterModules());

    @Test
    void writesReadableJsonAtomicallyWithAuditableFileName() throws Exception {
        RiskBackfillReport report = report("risk-v1", END_DATE, true, List.of());

        Path written = store.write(directory, report);

        assertThat(written.getFileName().toString())
                .startsWith("risk-backfill-20260719-103000000-sample-20260710-")
                .endsWith(".json");
        assertThat(Files.list(directory).map(path -> path.getFileName().toString()))
                .noneMatch(name -> name.endsWith(".tmp"));
        RiskBackfillReport reloaded = new ObjectMapper().findAndRegisterModules()
                .readValue(written.toFile(), RiskBackfillReport.class);
        assertThat(reloaded.runId()).isEqualTo(report.runId());
        assertThat(reloaded.sampleGatePassed()).isTrue();
    }

    @Test
    void fullModeRequiresMatchingPassedSampleReport() throws Exception {
        store.write(directory, report("risk-v1", END_DATE, true, List.of()));
        store.write(directory, report("risk-v2", END_DATE, false, List.of("gate failed")));
        store.write(directory, report(
                "risk-forged", END_DATE, true, List.of(), false));

        assertThat(store.hasPassedSampleGate(directory, "risk-v1", END_DATE)).isTrue();
        assertThat(store.hasPassedSampleGate(directory, "risk-v2", END_DATE)).isFalse();
        assertThat(store.hasPassedSampleGate(directory, "risk-forged", END_DATE)).isFalse();
        assertThat(store.hasPassedSampleGate(
                directory, "risk-v1", END_DATE.minusDays(1))).isFalse();
    }

    @Test
    void redactsSecretsAndUrlUserInfoBeforeWriting() throws Exception {
        RiskBackfillReport report = report("risk-v1", END_DATE, false, List.of(
                "password=secret-value",
                "TUSHARE_TOKEN: abc123",
                "request failed at https://user:pass@example.test/api"));

        Path written = store.write(directory, report);
        String json = Files.readString(written, StandardCharsets.UTF_8);

        assertThat(json)
                .doesNotContain("secret-value", "abc123", "user:pass")
                .contains("[REDACTED]");
    }

    @Test
    void writesAnUndatedConfigurationFailureReport() throws Exception {
        RiskBackfillReport report = report(
                "risk-v1", null, false, List.of("endDate must be configured"));

        Path written = store.write(directory, report);

        assertThat(written.getFileName().toString()).contains("-undated-");
    }

    private RiskBackfillReport report(
            String modelVersion,
            LocalDate endDate,
            boolean sampleGatePassed,
            List<String> failures
    ) {
        return report(modelVersion, endDate, sampleGatePassed, failures, sampleGatePassed);
    }

    private RiskBackfillReport report(
            String modelVersion,
            LocalDate endDate,
            boolean sampleGatePassed,
            List<String> failures,
            boolean includeGateProof
    ) {
        return new RiskBackfillReport(
                1,
                "run-123456",
                RiskBackfillMode.SAMPLE,
                modelVersion,
                "sample-validation",
                LocalDateTime.of(2026, 7, 19, 10, 30),
                LocalDateTime.of(2026, 7, 19, 10, 35),
                sampleGatePassed ? RiskBackfillExitCode.SUCCESS.code()
                        : RiskBackfillExitCode.SAMPLE_GATE_REJECTED.code(),
                sampleGatePassed ? "success" : "failed",
                endDate,
                endDate == null ? null : endDate.minusYears(5),
                endDate == null ? null : endDate.minusYears(11),
                List.of("600519.SH"),
                5530,
                28,
                null,
                includeGateProof ? new RiskWorkflowRunSummary(1, 1, 1, 1, 0, 1, 0) : null,
                null,
                includeGateProof ? passedReadiness() : null,
                null,
                sampleGatePassed,
                failures,
                null);
    }

    private RiskBackfillReadiness passedReadiness() {
        return new RiskBackfillReadiness(
                26, 100, 100, java.math.BigDecimal.ONE,
                java.util.Map.of(), List.of(), true,
                END_DATE.minusYears(5), END_DATE, true,
                java.util.Map.of(), true,
                1, 1, 0, true,
                0, true, 0, true,
                List.of(), List.of(), true);
    }
}
