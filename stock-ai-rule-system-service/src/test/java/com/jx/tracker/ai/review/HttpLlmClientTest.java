package com.jx.tracker.ai.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.config.properties.AiLlmProperties;
import com.jx.tracker.exception.ServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

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
            AiLlmProperties properties = properties(server.baseUrl(), "  secret-test-token  ", "review-model");
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
            assertThat(body.get("response_format").get("type").asText()).isEqualTo("json_object");
        }
    }

    @Test
    void rejectsPlainHttpUrlForExternalHostBeforeSendingRequest() {
        CapturingHttpClient httpClient = new CapturingHttpClient(successProviderResponse());
        HttpLlmClient client = new HttpLlmClient(
                properties("http://llm.example.test", "secret-test-token", "review-model"),
                objectMapper,
                httpClient
        );

        assertThatThrownBy(() -> client.complete(new LlmRequest("请复盘 AAPL 信号")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("HTTPS")
                .hasMessageNotContaining("secret-test-token");
        assertThat(httpClient.sentRequest()).isNull();
    }

    @Test
    void allowsPlainHttpUrlForLocalDevelopmentHosts() {
        for (String baseUrl : List.of("http://localhost:11434", "http://127.0.0.1:11434", "http://[::1]:11434")) {
            CapturingHttpClient httpClient = new CapturingHttpClient(successProviderResponse());
            HttpLlmClient client = new HttpLlmClient(
                    properties(baseUrl, "secret-test-token", "review-model"),
                    objectMapper,
                    httpClient
            );

            LlmResponse response = client.complete(new LlmRequest("请复盘 AAPL 信号"));

            assertThat(response.modelName()).isEqualTo("review-model");
            assertThat(httpClient.sentRequest()).isNotNull();
        }
    }

    @Test
    void rejectsApiKeyContainingControlCharactersWithoutExposingSecret() {
        CapturingHttpClient httpClient = new CapturingHttpClient(successProviderResponse());
        HttpLlmClient client = new HttpLlmClient(
                properties("https://llm.example.test", "secret\rtest-token", "review-model"),
                objectMapper,
                httpClient
        );

        assertThatThrownBy(() -> client.complete(new LlmRequest("请复盘 AAPL 信号")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("API Key")
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("test-token")
                .satisfies(throwable -> assertThat(throwable.getCause()).isNull());
        assertThat(httpClient.sentRequest()).isNull();
    }

    @Test
    void resolvesOpenAiCompatibleBaseUrlVariants() {
        Map<String, String> cases = Map.of(
                "https://llm.example.test", "https://llm.example.test/v1/chat/completions",
                "https://llm.example.test/v1", "https://llm.example.test/v1/chat/completions",
                "https://llm.example.test/v1/chat/completions", "https://llm.example.test/v1/chat/completions"
        );

        cases.forEach((baseUrl, expectedEndpoint) -> {
            CapturingHttpClient httpClient = new CapturingHttpClient(successProviderResponse());
            HttpLlmClient client = new HttpLlmClient(
                    properties(baseUrl, "secret-test-token", "review-model"),
                    objectMapper,
                    httpClient
            );

            client.complete(new LlmRequest("请复盘 AAPL 信号"));

            assertThat(httpClient.sentRequest().uri()).isEqualTo(URI.create(expectedEndpoint));
        });
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

    private String successProviderResponse() {
        String assistantContent = """
                {
                  "diagnosis": "规则表现正常。",
                  "related_rules": [],
                  "suggestions": [],
                  "need_backtest": false,
                  "risk": "仅作辅助决策参考。"
                }
                """;
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "model", "review-model",
                    "choices", List.of(Map.of(
                            "message", Map.of("content", assistantContent)
                    ))
            ));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private record CapturedRequest(String method, String path, String authorization, String body) {
    }

    private static final class CapturingHttpClient extends HttpClient {

        private final HttpClient delegate = HttpClient.newHttpClient();
        private final String responseBody;
        private HttpRequest sentRequest;

        private CapturingHttpClient(String responseBody) {
            this.responseBody = responseBody;
        }

        private HttpRequest sentRequest() {
            return sentRequest;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            sentRequest = request;
            return new TestHttpResponse<>(request, responseBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                 HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("sendAsync is not used in tests"));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                 HttpResponse.BodyHandler<T> responseBodyHandler,
                                                                 HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("sendAsync is not used in tests"));
        }
    }

    private record TestHttpResponse<T>(HttpRequest request, String responseBody) implements HttpResponse<T> {

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public T body() {
            return (T) responseBody;
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
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
