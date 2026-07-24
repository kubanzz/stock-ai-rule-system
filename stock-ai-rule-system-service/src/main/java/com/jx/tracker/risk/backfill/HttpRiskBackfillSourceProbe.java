package com.jx.tracker.risk.backfill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.market.data.provider.MarketDataProviderProperties;
import com.jx.tracker.risk.runtime.RiskWarningProperties;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class HttpRiskBackfillSourceProbe implements RiskBackfillSourceProbe {

    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final String akToolsBaseUrl;
    private final String derivedGatewayBaseUrl;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    public HttpRiskBackfillSourceProbe(
            RiskWarningProperties riskProperties,
            MarketDataProviderProperties marketDataProperties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        if (riskProperties == null || marketDataProperties == null
                || restClientBuilder == null || objectMapper == null) {
            throw new IllegalArgumentException("source probe dependencies are required");
        }
        this.akToolsBaseUrl = text(riskProperties.getAkToolsBaseUrl())
                ? riskProperties.getAkToolsBaseUrl().trim()
                : trimToNull(marketDataProperties.getAkToolsBaseUrl());
        this.derivedGatewayBaseUrl = trimToNull(riskProperties.getDerivedGatewayBaseUrl());
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public SourceProbeResult probeAkTools(LocalDate endDate) {
        if (akToolsBaseUrl == null) {
            return SourceProbeResult.unreachable("aktools", "AKTools is not configured");
        }
        if (endDate == null) {
            return SourceProbeResult.unreachable("aktools", "AKTools probe endDate is required");
        }
        try {
            String body = client(akToolsBaseUrl).get()
                    .uri("/api/public/stock_info_a_code_name")
                    .retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);
            JsonNode rows = root != null && root.isArray()
                    ? root : root == null ? null : root.path("data");
            if (rows == null || !rows.isArray()) {
                return SourceProbeResult.unreachable(
                        "aktools", "AKTools probe response must contain an array");
            }
            return SourceProbeResult.reachable("aktools");
        } catch (Exception exception) {
            return requestFailure("aktools", exception);
        }
    }

    @Override
    public SourceProbeResult probeDerivedGateway(LocalDate endDate) {
        if (derivedGatewayBaseUrl == null) {
            return SourceProbeResult.unreachable(
                    "derived-gateway", "derived gateway is not configured");
        }
        if (endDate == null) {
            return SourceProbeResult.unreachable(
                    "derived-gateway", "derived gateway probe endDate is required");
        }
        try {
            String date = BASIC_DATE.format(endDate);
            String body = client(derivedGatewayBaseUrl).get().uri(builder -> builder
                            .path("/api/risk/market-daily")
                            .queryParam("start_date", date)
                            .queryParam("end_date", date)
                            .queryParam("objects", "market:CN-A")
                            .build())
                    .retrieve().body(String.class);
            JsonNode root = objectMapper.readTree(body);
            JsonNode rows = root == null ? null : root.path("data");
            JsonNode meta = root == null ? null : root.path("meta");
            if (rows == null || !rows.isArray()) {
                return SourceProbeResult.unreachable(
                        "derived-gateway", "derived gateway response data must be an array");
            }
            if (meta == null || !meta.isObject() || !meta.has("historyComplete")) {
                return SourceProbeResult.unreachable(
                        "derived-gateway", "derived gateway response meta.historyComplete is required");
            }
            return SourceProbeResult.reachable("derived-gateway");
        } catch (Exception exception) {
            return requestFailure("derived-gateway", exception);
        }
    }

    private RestClient client(String baseUrl) {
        return restClientBuilder.clone().baseUrl(baseUrl).build();
    }

    private SourceProbeResult requestFailure(String source, Exception exception) {
        Throwable root = exception;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return SourceProbeResult.unreachable(
                source, source + " probe request failed: " + root.getClass().getSimpleName());
    }

    private boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        return text(value) ? value.trim() : null;
    }
}
