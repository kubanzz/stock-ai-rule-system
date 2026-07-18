package com.jx.tracker.risk.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

@ConfigurationProperties(prefix = "stock-ai-rule.risk-warning")
public class RiskWarningProperties {

    private boolean enabled;
    private boolean backfillEnabled;
    private String akToolsBaseUrl;
    private String modelVersion;
    private LocalTime afterCloseCutoff;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isBackfillEnabled() {
        return backfillEnabled;
    }

    public void setBackfillEnabled(boolean backfillEnabled) {
        this.backfillEnabled = backfillEnabled;
    }

    public String getAkToolsBaseUrl() {
        return akToolsBaseUrl;
    }

    public void setAkToolsBaseUrl(String akToolsBaseUrl) {
        this.akToolsBaseUrl = akToolsBaseUrl;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public LocalTime getAfterCloseCutoff() {
        return afterCloseCutoff;
    }

    public void setAfterCloseCutoff(LocalTime afterCloseCutoff) {
        this.afterCloseCutoff = afterCloseCutoff;
    }

    public String requiredModelVersion() {
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalStateException("risk warning modelVersion must be configured when enabled");
        }
        return modelVersion.trim();
    }

    public LocalTime requiredAfterCloseCutoff() {
        if (afterCloseCutoff == null) {
            throw new IllegalStateException("risk warning afterCloseCutoff must be configured when enabled");
        }
        return afterCloseCutoff;
    }

    public String resolvedAkToolsBaseUrl(String fallbackBaseUrl) {
        String resolved = akToolsBaseUrl == null || akToolsBaseUrl.isBlank()
                ? fallbackBaseUrl
                : akToolsBaseUrl;
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException("risk warning AKTools baseUrl must be configured when enabled");
        }
        return resolved.trim();
    }
}
