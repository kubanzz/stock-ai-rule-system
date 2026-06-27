package com.jx.tracker.ai.review;

public interface LlmClient {

    LlmResponse complete(LlmRequest request);
}
