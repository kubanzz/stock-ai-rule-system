package com.jx.tracker.risk.data.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Spring RestClient 的 AKTools 生产传输实现。配置由构造参数注入，不保存凭据。
 */
public final class RestClientMarketRiskHttpTransport implements MarketRiskHttpTransport {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RestClientMarketRiskHttpTransport(
            String baseUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        if (baseUrl == null || baseUrl.isBlank() || restClientBuilder == null || objectMapper == null) {
            throw new IllegalArgumentException("baseUrl, restClientBuilder and objectMapper are required");
        }
        this.restClient = restClientBuilder.baseUrl(baseUrl.trim()).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Map<String, Object>> get(String endpoint, Map<String, String> query) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        String response = restClient.get().uri(builder -> {
            builder.path(endpoint);
            (query == null ? Map.<String, String>of() : query).entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> builder.queryParam(entry.getKey(), entry.getValue()));
            return builder.build();
        }).retrieve().body(String.class);
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode rows = root != null && root.isArray() ? root : root == null ? null : root.path("data");
            if (rows == null || rows.isMissingNode() || rows.isNull()) {
                return List.of();
            }
            if (!rows.isArray()) {
                throw new IllegalArgumentException("AKTools response data must be an array");
            }
            List<Map<String, Object>> result = new ArrayList<>(rows.size());
            rows.forEach(row -> result.add(Collections.unmodifiableMap(
                    new LinkedHashMap<>(objectMapper.convertValue(row, MAP_TYPE))
            )));
            return List.copyOf(result);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("failed to parse AKTools risk response: " + endpoint, exception);
        }
    }
}
