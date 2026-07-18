package com.jx.tracker.risk.data.market;

import java.util.List;
import java.util.Map;

/**
 * AKTools HTTP 传输端口。鉴权、超时、重试和基础 URL 由部署侧注入，领域代码不持有凭据。
 */
@FunctionalInterface
public interface MarketRiskHttpTransport {

    List<Map<String, Object>> get(String endpoint, Map<String, String> query);
}
