package com.jx.tracker.risk.data.tushare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已将 TuShare 列数组展开为字段行的不可变响应。
 */
public record TushareRiskResponse(
        String apiName,
        List<String> fields,
        List<Map<String, Object>> rows
) {

    public TushareRiskResponse {
        if (apiName == null || apiName.isBlank()) {
            throw new IllegalArgumentException("apiName must not be blank");
        }
        apiName = apiName.trim();
        fields = List.copyOf(fields == null ? List.of() : fields);
        List<Map<String, Object>> immutableRows = new ArrayList<>();
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            immutableRows.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        rows = List.copyOf(immutableRows);
    }
}
