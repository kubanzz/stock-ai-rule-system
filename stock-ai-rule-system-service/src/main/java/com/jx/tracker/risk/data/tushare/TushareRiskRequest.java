package com.jx.tracker.risk.data.tushare;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        params = Collections.unmodifiableMap(new LinkedHashMap<>(
                params == null ? Map.of() : params));
        fields = fields.trim();
    }
}
