package com.jx.tracker.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "ai.llm")
public class AiLlmProperties {

    private String baseUrl;

    private String apiKey;

    private String model = "gpt-4o-mini";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isHttpEnabled() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey);
    }
}
