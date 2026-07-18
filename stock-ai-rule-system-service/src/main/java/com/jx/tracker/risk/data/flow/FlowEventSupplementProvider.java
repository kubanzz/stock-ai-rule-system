package com.jx.tracker.risk.data.flow;

/** 历史补源 SPI；由部署环境注入具体实现和凭据。 */
public interface FlowEventSupplementProvider extends FlowEventSourceClient {

    String providerCode();

    int priority();

    boolean supports(String datasetCode);
}
