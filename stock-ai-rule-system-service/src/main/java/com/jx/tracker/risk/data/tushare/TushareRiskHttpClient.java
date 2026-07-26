package com.jx.tracker.risk.data.tushare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * TuShare 风险数据的安全 POST JSON 边界。
 */
public final class TushareRiskHttpClient {

    private static final RetryPolicy DEFAULT_RETRY_POLICY =
            new RetryPolicy(2, Duration.ofMillis(250));
    private static final Pattern TOKEN_ASSIGNMENT = Pattern.compile(
            "(?i)(token\\s*[=:]\\s*)[^\\s,;\"'}]+");

    private final String token;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final Consumer<Duration> retryWait;

    public TushareRiskHttpClient(
            String token,
            String apiUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this(token, apiUrl, restClientBuilder, objectMapper, DEFAULT_RETRY_POLICY,
                TushareRiskHttpClient::sleep);
    }

    public TushareRiskHttpClient(
            String token,
            String apiUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            RetryPolicy retryPolicy,
            Consumer<Duration> retryWait
    ) {
        if (token == null || token.isBlank()) {
            throw TushareRiskException.configuration("token must not be blank");
        }
        if (restClientBuilder == null || objectMapper == null
                || retryPolicy == null || retryWait == null) {
            throw TushareRiskException.configuration(
                    "RestClient builder, ObjectMapper, retry policy and retry wait are required");
        }
        this.token = token.trim();
        this.restClient = restClientBuilder.baseUrl(validateApiUrl(apiUrl)).build();
        this.objectMapper = objectMapper;
        this.retryPolicy = retryPolicy;
        this.retryWait = retryWait;
    }

    public TushareRiskResponse query(TushareRiskRequest request) {
        if (request == null) {
            throw TushareRiskException.configuration("request must not be null");
        }
        int retries = 0;
        while (true) {
            try {
                return execute(request);
            } catch (TushareRiskException exception) {
                if (exception.category() != TushareRiskException.Category.RATE_LIMIT
                        || retries >= retryPolicy.maxRetries()) {
                    throw exception;
                }
                waitBeforeRetry(request.apiName());
                retries++;
            }
        }
    }

    private TushareRiskResponse execute(TushareRiskRequest request) {
        String responseBody;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("api_name", request.apiName());
            body.put("token", token);
            body.put("params", request.params());
            body.put("fields", request.fields());
            responseBody = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException exception) {
            throw TushareRiskException.remote(request.apiName());
        }
        return parse(request.apiName(), responseBody);
    }

    private TushareRiskResponse parse(String apiName, String responseBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (Exception exception) {
            throw TushareRiskException.parse(apiName, "response JSON could not be parsed");
        }
        if (root == null || !root.isObject()) {
            throw TushareRiskException.parse(apiName, "response root must be an object");
        }
        JsonNode codeNode = root.get("code");
        if (codeNode == null || !codeNode.canConvertToInt()) {
            throw TushareRiskException.parse(apiName, "response code is missing or invalid");
        }
        int code = codeNode.intValue();
        if (code != 0) {
            String vendorMessage = sanitizeVendorMessage(text(root.get("msg")));
            throw TushareRiskException.vendor(
                    category(code, vendorMessage), apiName, code, vendorMessage);
        }
        return mapData(apiName, root.get("data"));
    }

    private TushareRiskResponse mapData(String apiName, JsonNode data) {
        if (data == null || !data.isObject()) {
            throw TushareRiskException.parse(apiName, "response data must be an object");
        }
        JsonNode fieldsNode = data.get("fields");
        if (fieldsNode == null || !fieldsNode.isArray()) {
            throw TushareRiskException.parse(apiName, "response fields array is missing");
        }
        List<String> fields = new ArrayList<>(fieldsNode.size());
        for (JsonNode field : fieldsNode) {
            if (!field.isTextual() || field.asText().isBlank()) {
                throw TushareRiskException.parse(apiName, "response fields contain an invalid name");
            }
            fields.add(field.asText());
        }

        JsonNode itemsNode = data.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            throw TushareRiskException.parse(apiName, "response items array is missing");
        }
        List<Map<String, Object>> rows = new ArrayList<>(itemsNode.size());
        for (JsonNode item : itemsNode) {
            if (!item.isArray()) {
                throw TushareRiskException.parse(apiName, "response item must be a column array");
            }
            if (item.size() != fields.size()) {
                throw TushareRiskException.parse(
                        apiName, "response item column count does not match fields");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 0; index < fields.size(); index++) {
                JsonNode value = item.get(index);
                row.put(fields.get(index),
                        value == null || value.isNull()
                                ? null
                                : objectMapper.convertValue(value, Object.class));
            }
            rows.add(row);
        }
        return new TushareRiskResponse(apiName, fields, rows);
    }

    private TushareRiskException.Category category(int code, String vendorMessage) {
        String normalized = vendorMessage == null
                ? ""
                : vendorMessage.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "限频", "频率", "每分钟", "访问次数", "rate limit",
                "too many request", "exceeded")) {
            return TushareRiskException.Category.RATE_LIMIT;
        }
        if (containsAny(normalized, "权限", "积分", "permission", "unauthorized",
                "not authorized", "access denied")) {
            return TushareRiskException.Category.PERMISSION;
        }
        return TushareRiskException.Category.REMOTE;
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String sanitizeVendorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown vendor error";
        }
        String sanitized = message.replace(token, "[REDACTED]");
        sanitized = TOKEN_ASSIGNMENT.matcher(sanitized).replaceAll("$1[REDACTED]");
        sanitized = sanitized.replace('\r', ' ').replace('\n', ' ').trim();
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512);
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.asText();
    }

    private void waitBeforeRetry(String apiName) {
        try {
            retryWait.accept(retryPolicy.delay());
        } catch (RuntimeException exception) {
            throw TushareRiskException.remote(apiName);
        }
    }

    private static String validateApiUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw TushareRiskException.configuration("TuShare API URL must not be blank");
        }
        URI uri;
        try {
            uri = URI.create(apiUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw TushareRiskException.configuration("TuShare API URL is invalid");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw TushareRiskException.configuration("TuShare API URL host is required");
        }
        if ("https".equalsIgnoreCase(scheme)
                || ("http".equalsIgnoreCase(scheme) && isLocalHost(host))) {
            return apiUrl.trim();
        }
        throw TushareRiskException.configuration(
                "TuShare API URL must use HTTPS for remote hosts");
    }

    private static boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static void sleep(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("retry wait interrupted");
        }
    }

    public record RetryPolicy(int maxRetries, Duration delay) {

        public RetryPolicy {
            if (maxRetries < 0 || maxRetries > 10) {
                throw TushareRiskException.configuration(
                        "maxRetries must be between 0 and 10");
            }
            if (delay == null || delay.isNegative()) {
                throw TushareRiskException.configuration("delay must not be negative");
            }
        }
    }
}
