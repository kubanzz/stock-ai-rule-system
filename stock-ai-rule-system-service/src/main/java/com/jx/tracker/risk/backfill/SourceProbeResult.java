package com.jx.tracker.risk.backfill;

public record SourceProbeResult(
        String source,
        boolean reachable,
        String detail,
        java.util.List<SourceProbeSummary> summaries
) {

    public SourceProbeResult(String source, boolean reachable, String detail) {
        this(source, reachable, detail, java.util.List.of());
    }

    public SourceProbeResult {
        source = source == null || source.isBlank() ? "unknown" : source.trim();
        detail = detail == null ? "" : detail.trim();
        summaries = java.util.List.copyOf(
                summaries == null ? java.util.List.of() : summaries);
    }

    public static SourceProbeResult reachable(String source) {
        return new SourceProbeResult(source, true, "reachable");
    }

    public static SourceProbeResult reachable(
            String source,
            java.util.List<SourceProbeSummary> summaries
    ) {
        return new SourceProbeResult(source, true, "reachable", summaries);
    }

    public static SourceProbeResult unreachable(String source, String detail) {
        return new SourceProbeResult(source, false, detail);
    }

    public static SourceProbeResult notProbed(String source) {
        return new SourceProbeResult(source, false, "not probed");
    }
}
