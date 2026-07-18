package com.jx.tracker.risk.data.flow;

/** 底层数据源适配器。实现不得在代码中携带 Token 或账号。 */
@FunctionalInterface
public interface FlowEventSourceClient {

    FlowEventSourceBatch fetch(FlowEventSourceRequest request);
}
