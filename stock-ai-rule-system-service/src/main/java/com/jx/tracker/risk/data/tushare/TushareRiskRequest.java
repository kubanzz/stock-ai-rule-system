package com.jx.tracker.risk.data.tushare;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TuShare 风险数据查询。Token 由 HTTP 客户端持有，不属于业务请求。
 */
public record TushareRiskRequest(
        String apiName,
        Map<String, Object> params,
        String fields
) {

    public TushareRiskRequest {
        if (apiName == null || apiName.isBlank()) {
            throw new IllegalArgumentException("apiName must not be blank");
        }
        if (fields == null || fields.isBlank()) {
            throw new IllegalArgumentException("fields must not be blank");
        }
        apiName = apiName.trim();
        params = immutableJsonMap(params == null ? Map.of() : params, "params");
        fields = fields.trim();
    }

    static Map<String, Object> immutableJsonMap(Map<?, ?> source, String path) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(path + " contains a non-string object key");
            }
            snapshot.put(key, immutableJsonValue(entry.getValue(), path + "." + key));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private static Object immutableJsonValue(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof BigDecimal || value instanceof BigInteger
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Double doubleValue) {
            if (!Double.isFinite(doubleValue)) {
                throw new IllegalArgumentException(path + " contains a non-finite number");
            }
            return doubleValue;
        }
        if (value instanceof Float floatValue) {
            if (!Float.isFinite(floatValue)) {
                throw new IllegalArgumentException(path + " contains a non-finite number");
            }
            return floatValue;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(path + " contains an invalid number");
            }
        }
        if (value instanceof Map<?, ?> map) {
            return immutableJsonMap(map, path);
        }
        if (value instanceof List<?> list) {
            List<Object> snapshot = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                snapshot.add(immutableJsonValue(list.get(index), path + "[" + index + "]"));
            }
            return Collections.unmodifiableList(snapshot);
        }
        throw new IllegalArgumentException(
                path + " contains unsupported JSON value type " + value.getClass().getName());
    }
}
