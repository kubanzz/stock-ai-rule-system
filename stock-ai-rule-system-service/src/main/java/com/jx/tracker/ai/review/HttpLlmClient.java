package com.jx.tracker.ai.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.config.properties.AiLlmProperties;
import com.jx.tracker.exception.ServiceException;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class HttpLlmClient implements LlmClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final AiLlmProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpLlmClient(AiLlmProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (request == null || !StringUtils.hasText(request.prompt())) {
            throw new ServiceException("AI 复盘 Prompt 不能为空");
        }
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(resolveEndpoint())
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(request), java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ServiceException("AI 复盘模型请求失败: HTTP " + response.statusCode());
            }
            return parseResponse(response.body());
        } catch (ServiceException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("AI 复盘模型请求失败", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new ServiceException("AI 复盘模型请求失败", e);
        }
    }

    private String buildRequestBody(LlmRequest request) throws JsonProcessingException {
        return objectMapper.writeValueAsString(Map.of(
                "model", resolveModel(),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", request.prompt()
                ))
        ));
    }

    private LlmResponse parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (!contentNode.isTextual() || !StringUtils.hasText(contentNode.asText())) {
                throw new ServiceException("AI 复盘模型响应必须包含 content");
            }
            String modelName = root.path("model").asText(resolveModel());
            return new LlmResponse(modelName, contentNode.asText());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("AI 复盘模型响应必须是结构化 JSON", e);
        }
    }

    private URI resolveEndpoint() {
        if (!StringUtils.hasText(properties.getBaseUrl())) {
            throw new ServiceException("AI 复盘模型地址未配置");
        }
        String baseUrl = trimTrailingSlashes(properties.getBaseUrl().trim());
        if (baseUrl.endsWith("/chat/completions")) {
            return URI.create(baseUrl);
        }
        if (baseUrl.endsWith("/v1")) {
            return URI.create(baseUrl + "/chat/completions");
        }
        return URI.create(baseUrl + "/v1/chat/completions");
    }

    private String resolveModel() {
        return StringUtils.hasText(properties.getModel()) ? properties.getModel() : "gpt-4o-mini";
    }

    private String trimTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
