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
import java.util.Locale;
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
            String apiKey = resolveApiKey();
            HttpRequest httpRequest = HttpRequest.newBuilder(resolveEndpoint())
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + apiKey)
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
        } catch (IllegalArgumentException e) {
            throw new ServiceException("AI 复盘模型请求配置不合法");
        } catch (IOException e) {
            throw new ServiceException("AI 复盘模型请求失败", e);
        }
    }

    private String buildRequestBody(LlmRequest request) throws JsonProcessingException {
        return objectMapper.writeValueAsString(Map.of(
                "model", resolveModel(),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", request.prompt()
                )),
                "response_format", Map.of("type", "json_object")
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
        URI endpoint;
        if (baseUrl.endsWith("/chat/completions")) {
            endpoint = URI.create(baseUrl);
        } else if (baseUrl.endsWith("/v1")) {
            endpoint = URI.create(baseUrl + "/chat/completions");
        } else {
            endpoint = URI.create(baseUrl + "/v1/chat/completions");
        }
        validateEndpoint(endpoint);
        return endpoint;
    }

    private String resolveModel() {
        return StringUtils.hasText(properties.getModel()) ? properties.getModel().trim() : "gpt-4o-mini";
    }

    private String resolveApiKey() {
        String apiKey = properties.getApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new ServiceException("AI 复盘模型 API Key 未配置");
        }
        if (containsControlCharacter(apiKey)) {
            throw new ServiceException("AI 复盘模型 API Key 不合法");
        }
        String trimmedApiKey = apiKey.trim();
        if (!StringUtils.hasText(trimmedApiKey)) {
            throw new ServiceException("AI 复盘模型 API Key 未配置");
        }
        return trimmedApiKey;
    }

    private void validateEndpoint(URI endpoint) {
        String scheme = endpoint.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if ("http".equalsIgnoreCase(scheme) && isLocalDevelopmentHost(endpoint.getHost())) {
            return;
        }
        throw new ServiceException("AI 复盘模型地址必须使用 HTTPS，本地开发仅允许 localhost/127.0.0.1/[::1] 使用 HTTP");
    }

    private boolean isLocalDevelopmentHost(String host) {
        if (!StringUtils.hasText(host)) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalizedHost)
                || "127.0.0.1".equals(normalizedHost)
                || "::1".equals(normalizedHost)
                || "[::1]".equals(normalizedHost)
                || "0:0:0:0:0:0:0:1".equals(normalizedHost)
                || "[0:0:0:0:0:0:0:1]".equals(normalizedHost);
    }

    private boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private String trimTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
