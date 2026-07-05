package com.jx.tracker.ai.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.config.properties.AiLlmProperties;
import com.jx.tracker.exception.ServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void postsOpenAiCompatibleChatCompletionRequest() throws Exception {
        String assistantContent = """
                {
                  "diagnosis": "弱势市场中放量突破失效。",
                  "related_rules": [],
                  "suggestions": [],
                  "need_backtest": false,
                  "risk": "仅作辅助决策参考。"
                }
                """;
        String providerResponse = objectMapper.writeValueAsString(Map.of(
                "model", "review-model",
                "choices", List.of(Map.of(
                        "message", Map.of("content", assistantContent)
                ))
        ));

        try (TestLlmServer server = TestLlmServer.start(200, providerResponse)) {
            AiLlmProperties properties = properties(server.baseUrl(), "secret-test-token", "review-model");
            HttpLlmClient client = new HttpLlmClient(properties, objectMapper, HttpClient.newHttpClient());

            LlmResponse response = client.complete(new LlmRequest("请复盘 AAPL 信号"));

            assertThat(response.modelName()).isEqualTo("review-model");
            assertThat(response.content()).contains("弱势市场");

            CapturedRequest request = server.capturedRequest();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/v1/chat/completions");
            assertThat(request.authorization()).isEqualTo("Bearer secret-test-token");
            assertThat(request.body()).doesNotContain("secret-test-token");

            JsonNode body = objectMapper.readTree(request.body());
            assertThat(body.get("model").asText()).isEqualTo("review-model");
            assertThat(body.get("messages")).hasSize(1);
            assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
            assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("请复盘 AAPL 信号");
        }
    }

    @Test
    void rejectsProviderResponseWithoutAssistantContent() throws Exception {
        String providerResponse = objectMapper.writeValueAsString(Map.of(
                "model", "review-model",
                "choices", List.of(Map.of("message", Map.of()))
        ));

        try (TestLlmServer server = TestLlmServer.start(200, providerResponse)) {
            HttpLlmClient client = new HttpLlmClient(
                    properties(server.baseUrl(), "secret-test-token", "review-model"),
                    objectMapper,
                    HttpClient.newHttpClient()
            );

            assertThatThrownBy(() -> client.complete(new LlmRequest("请复盘 AAPL 信号")))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("content");
        }
    }

    @Test
    void doesNotExposeApiKeyWhenProviderRequestFails() throws Exception {
        try (TestLlmServer ignored = TestLlmServer.start(500, "{\"error\":\"downstream failed\"}")) {
            HttpLlmClient client = new HttpLlmClient(
                    properties(ignored.baseUrl(), "secret-test-token", "review-model"),
                    objectMapper,
                    HttpClient.newHttpClient()
            );

            assertThatThrownBy(() -> client.complete(new LlmRequest("请复盘 AAPL 信号")))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("模型请求失败")
                    .hasMessageNotContaining("secret-test-token");
        }
    }

    private AiLlmProperties properties(String baseUrl, String apiKey, String model) {
        AiLlmProperties properties = new AiLlmProperties();
        properties.setBaseUrl(baseUrl);
        properties.setApiKey(apiKey);
        properties.setModel(model);
        return properties;
    }

    private record CapturedRequest(String method, String path, String authorization, String body) {
    }

    private static final class TestLlmServer implements AutoCloseable {

        private final HttpServer server;
        private final AtomicReference<CapturedRequest> capturedRequest;

        private TestLlmServer(HttpServer server, AtomicReference<CapturedRequest> capturedRequest) {
            this.server = server;
            this.capturedRequest = capturedRequest;
        }

        static TestLlmServer start(int statusCode, String responseBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicReference<CapturedRequest> capturedRequest = new AtomicReference<>();
            server.createContext("/v1/chat/completions", exchange -> {
                String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                capturedRequest.set(new CapturedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        requestBody
                ));
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(statusCode, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return new TestLlmServer(server, capturedRequest);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        CapturedRequest capturedRequest() {
            return capturedRequest.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
