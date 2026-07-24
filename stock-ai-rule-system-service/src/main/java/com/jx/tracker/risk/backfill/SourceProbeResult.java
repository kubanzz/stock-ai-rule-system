package com.jx.tracker.risk.backfill;

public record SourceProbeResult(
        String source,
        boolean reachable,
        String detail
) {

    public SourceProbeResult {
        source = source == null || source.isBlank() ? "unknown" : source.trim();
        detail = detail == null ? "" : detail.trim();
    }

    public static SourceProbeResult reachable(String source) {
        return new SourceProbeResult(source, true, "reachable");
    }

    public static SourceProbeResult unreachable(String source, String detail) {
        return new SourceProbeResult(source, false, detail);
    }

    public static SourceProbeResult notProbed(String source) {
        return new SourceProbeResult(source, false, "not probed");
    }
}
