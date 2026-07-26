package com.jx.tracker.risk.data.tushare;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * TuShare 风险数据的安全 POST JSON 边界。
 *
 * <p>调用方负责为传入的 {@link RestClient.Builder} 配置生产环境连接和读取超时；
 * 本类只负责协议、脱敏、解析与有界重试。</p>
 */
public final class TushareRiskHttpClient {

    private static final RetryPolicy DEFAULT_RETRY_POLICY =
            new RetryPolicy(2, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30));
    private static final Set<Integer> RATE_LIMIT_CODES = Set.of(-2002, -429, 429);
    private static final Set<Integer> PERMISSION_CODES = Set.of(-2001);
    private static final Pattern TOKEN_ASSIGNMENT = Pattern.compile(
            "(?i)(token\\s*[=:]\\s*)[^\\s,;\"'}]+");

    private final String token;
    private final RestClient restClient;
    private final ObjectReader responseReader;
    private final RetryPolicy retryPolicy;
    private final Consumer<Duration> retryWait;
    private final Clock clock;

    public TushareRiskHttpClient(
            String token,
            String apiUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper
    ) {
        this(token, apiUrl, restClientBuilder, objectMapper, DEFAULT_RETRY_POLICY,
                TushareRiskHttpClient::sleep, Clock.systemUTC());
    }

    public TushareRiskHttpClient(
            String token,
            String apiUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            RetryPolicy retryPolicy,
            Consumer<Duration> retryWait
    ) {
        this(token, apiUrl, restClientBuilder, objectMapper, retryPolicy, retryWait,
                Clock.systemUTC());
    }

    public TushareRiskHttpClient(
            String token,
            String apiUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            RetryPolicy retryPolicy,
            Consumer<Duration> retryWait,
            Clock clock
    ) {
        if (token == null || token.isBlank()) {
            throw TushareRiskException.configuration("token must not be blank");
        }
        if (restClientBuilder == null || objectMapper == null
                || retryPolicy == null || retryWait == null || clock == null) {
            throw TushareRiskException.configuration(
                    "RestClient builder, ObjectMapper, retry policy, retry wait and clock are required");
        }
        this.token = token.trim();
        this.restClient = restClientBuilder.baseUrl(validateApiUrl(apiUrl)).build();
        this.responseReader = objectMapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.retryPolicy = retryPolicy;
        this.retryWait = retryWait;
        this.clock = clock;
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
                retries++;
                Duration delay = retryPolicy.delay(retries, exception.retryAfter());
                waitBeforeRetry(request.apiName(), delay);
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
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) {
                throw TushareRiskException.httpRateLimit(
                        request.apiName(),
                        httpRateLimitMessage(exception),
                        retryAfter(exception.getResponseHeaders()));
            }
            throw TushareRiskException.remote(request.apiName());
        } catch (RuntimeException exception) {
            throw TushareRiskException.remote(request.apiName());
        }
        return parse(request.apiName(), responseBody);
    }

    private TushareRiskResponse parse(String apiName, String responseBody) {
        JsonNode root;
        try {
            root = responseReader.readTree(responseBody);
        } catch (Exception exception) {
            throw TushareRiskException.parse(apiName, "response JSON could not be parsed");
        }
        if (root == null || !root.isObject()) {
            throw TushareRiskException.parse(apiName, "response root must be an object");
        }
        JsonNode codeNode = root.get("code");
        if (codeNode == null || !codeNode.isIntegralNumber() || !codeNode.canConvertToInt()) {
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
        Set<String> uniqueFields = new HashSet<>();
        for (JsonNode field : fieldsNode) {
            if (!field.isTextual() || field.asText().isBlank()) {
                throw TushareRiskException.parse(apiName, "response fields contain an invalid name");
            }
            String fieldName = field.asText();
            if (!uniqueFields.add(fieldName)) {
                throw TushareRiskException.parse(
                        apiName, "response fields contain a duplicate name");
            }
            fields.add(fieldName);
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
                row.put(fields.get(index), jsonValue(apiName, item.get(index)));
            }
            rows.add(row);
        }
        return new TushareRiskResponse(apiName, fields, rows);
    }

    private TushareRiskException.Category category(int code, String vendorMessage) {
        if (RATE_LIMIT_CODES.contains(code)) {
            return TushareRiskException.Category.RATE_LIMIT;
        }
        if (PERMISSION_CODES.contains(code)) {
            return TushareRiskException.Category.PERMISSION;
        }
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

    private Object jsonValue(String apiName, JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber()) {
            if (value.canConvertToInt()) {
                return value.intValue();
            }
            if (value.canConvertToLong()) {
                return value.longValue();
            }
            return value.bigIntegerValue();
        }
        if (value.isFloatingPointNumber()) {
            return value.decimalValue();
        }
        if (value.isObject()) {
            Map<String, Object> result = new LinkedHashMap<>();
            value.properties().forEach(entry ->
                    result.put(entry.getKey(), jsonValue(apiName, entry.getValue())));
            return result;
        }
        if (value.isArray()) {
            List<Object> result = new ArrayList<>(value.size());
            value.forEach(element -> result.add(jsonValue(apiName, element)));
            return result;
        }
        throw TushareRiskException.parse(
                apiName, "response contains an unsupported JSON value");
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

    private String httpRateLimitMessage(RestClientResponseException exception) {
        try {
            JsonNode root = responseReader.readTree(exception.getResponseBodyAsString());
            String message = root == null || !root.isObject() ? null : text(root.get("msg"));
            if (message != null && !message.isBlank()) {
                return sanitizeVendorMessage(message);
            }
        } catch (Exception ignored) {
            // HTTP 状态和响应头足以分类；不得传播可能包含敏感正文的解析异常。
        }
        return "HTTP 429 Too Many Requests";
    }

    private Duration retryAfter(HttpHeaders headers) {
        String value = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        try {
            long seconds = Long.parseLong(normalized);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException | ArithmeticException ignored) {
            // Retry-After 也允许 RFC 1123 日期，继续按日期解析。
        }
        try {
            Duration delay = Duration.between(
                    clock.instant(),
                    ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant());
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException | ArithmeticException ignored) {
            return null;
        }
    }

    private void waitBeforeRetry(String apiName, Duration delay) {
        try {
            retryWait.accept(delay);
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
        if (uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw TushareRiskException.configuration(
                    "TuShare API URL must not contain user-info, query or fragment");
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

    public record RetryPolicy(
            int maxRetries,
            Duration initialDelay,
            double multiplier,
            Duration maxDelay
    ) {

        private static final Duration ABSOLUTE_MAX_DELAY = Duration.ofMinutes(10);

        public RetryPolicy(int maxRetries, Duration fixedDelay) {
            this(maxRetries, fixedDelay, 1.0, fixedDelay);
        }

        public RetryPolicy {
            if (maxRetries < 0 || maxRetries > 10) {
                throw TushareRiskException.configuration(
                        "maxRetries must be between 0 and 10");
            }
            if (initialDelay == null || initialDelay.isNegative()) {
                throw TushareRiskException.configuration(
                        "initialDelay must not be negative");
            }
            if (!Double.isFinite(multiplier) || multiplier < 1.0) {
                throw TushareRiskException.configuration(
                        "multiplier must be finite and at least 1.0");
            }
            if (maxDelay == null || maxDelay.isNegative()
                    || maxDelay.compareTo(ABSOLUTE_MAX_DELAY) > 0) {
                throw TushareRiskException.configuration(
                        "maxDelay must be between zero and 10 minutes");
            }
            if (initialDelay.compareTo(maxDelay) > 0) {
                throw TushareRiskException.configuration(
                        "initialDelay must not exceed maxDelay");
            }
        }

        Duration delay(int retryNumber, Duration retryAfter) {
            if (retryAfter != null) {
                return capped(retryAfter);
            }
            if (initialDelay.isZero() || retryNumber <= 1 || multiplier == 1.0) {
                return initialDelay;
            }
            double nanos = initialDelay.toNanos()
                    * Math.pow(multiplier, retryNumber - 1);
            if (!Double.isFinite(nanos) || nanos >= maxDelay.toNanos()) {
                return maxDelay;
            }
            return Duration.ofNanos((long) Math.ceil(nanos));
        }

        private Duration capped(Duration delay) {
            if (delay.isNegative()) {
                return Duration.ZERO;
            }
            return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
        }
    }
}
