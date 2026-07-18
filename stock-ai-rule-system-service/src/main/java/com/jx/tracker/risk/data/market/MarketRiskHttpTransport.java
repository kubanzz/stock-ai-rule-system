package com.jx.tracker.risk.data.market;

import java.util.List;
import java.util.Map;

/**
 * AKTools HTTP 传输端口。鉴权、超时、重试和基础 URL 由部署侧注入，领域代码不持有凭据。
 */
@FunctionalInterface
public interface MarketRiskHttpTransport {

    List<Map<String, Object>> get(String endpoint, Map<String, String> query);

    /**
     * 衍生网关必须覆盖此方法并返回历史完整度与分页元数据；默认适配仅用于旧运输实现，
     * 会被领域层视为历史不完整。
     */
    default MarketRiskHttpResponse getResponse(String endpoint, Map<String, String> query) {
        return MarketRiskHttpResponse.legacyIncomplete(get(endpoint, query));
    }
}
