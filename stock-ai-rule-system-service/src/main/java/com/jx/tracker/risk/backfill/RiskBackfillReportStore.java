package com.jx.tracker.risk.backfill;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;

public interface RiskBackfillReportStore {

    Path write(Path directory, RiskBackfillReport report) throws IOException;

    boolean hasPassedSampleGate(Path directory, String modelVersion, LocalDate endDate);
}
