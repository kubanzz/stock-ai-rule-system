package com.jx.tracker.risk.provider;

/**
 * 风险数据批量采集 SPI。实现方必须保留来源时间、可用时间和质量状态，避免未来函数。
 */
public interface RiskDataProvider {

    String providerCode();

    boolean supports(String datasetCode);

    RiskProviderBatch fetch(String datasetCode, RiskProviderRequest request);
}
