package com.jx.tracker.risk.data.tushare;

import java.time.Duration;

/**
 * TuShare HTTP 边界的结构化、已脱敏异常。
 */
public final class TushareRiskException extends RuntimeException {

    public enum Category {
        PERMISSION,
        RATE_LIMIT,
        REMOTE,
        PARSE,
        CONFIGURATION
    }

    private final Category category;
    private final String apiName;
    private final Integer code;
    private final String vendorMessage;
    private final Duration retryAfter;

    private TushareRiskException(
            Category category,
            String apiName,
            Integer code,
            String vendorMessage,
            String detail,
            Duration retryAfter
    ) {
        super(message(category, apiName, code, vendorMessage, detail));
        this.category = category;
        this.apiName = apiName;
        this.code = code;
        this.vendorMessage = vendorMessage;
        this.retryAfter = retryAfter;
    }

    static TushareRiskException configuration(String detail) {
        return new TushareRiskException(
                Category.CONFIGURATION, null, null, null, detail, null);
    }

    static TushareRiskException remote(String apiName) {
        return new TushareRiskException(
                Category.REMOTE, apiName, null, null, "remote request failed", null);
    }

    static TushareRiskException parse(String apiName, String detail) {
        return new TushareRiskException(Category.PARSE, apiName, null, null, detail, null);
    }

    static TushareRiskException vendor(
            Category category,
            String apiName,
            int code,
            String vendorMessage
    ) {
        return new TushareRiskException(
                category, apiName, code, vendorMessage, null, null);
    }

    static TushareRiskException httpRateLimit(
            String apiName,
            String vendorMessage,
            Duration retryAfter
    ) {
        return new TushareRiskException(
                Category.RATE_LIMIT, apiName, 429, vendorMessage, null, retryAfter);
    }

    public Category category() {
        return category;
    }

    public String apiName() {
        return apiName;
    }

    public Integer code() {
        return code;
    }

    public String vendorMessage() {
        return vendorMessage;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    private static String message(
            Category category,
            String apiName,
            Integer code,
            String vendorMessage,
            String detail
    ) {
        StringBuilder message = new StringBuilder("TuShare risk request failed")
                .append(" [category=").append(category);
        if (apiName != null) {
            message.append(", apiName=").append(apiName);
        }
        if (code != null) {
            message.append(", code=").append(code);
        }
        message.append(']');
        String reason = vendorMessage != null ? vendorMessage : detail;
        if (reason != null && !reason.isBlank()) {
            message.append(": ").append(reason);
        }
        return message.toString();
    }
}
