package io.phasetwo.keycloak.transactional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.phasetwo.keycloak.transactional.provider.brevo.BrevoEmailProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BrevoEmailProviderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer mockServer;
  private int mockPort;
  private final AtomicInteger requestCount = new AtomicInteger(0);
  private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
  private final AtomicReference<String> lastApiKeyHeader = new AtomicReference<>();
  private int responseStatus = 201;

  @BeforeEach
  void startMockServer() throws IOException {
    mockServer = HttpServer.create(new InetSocketAddress(0), 0);
    mockPort = mockServer.getAddress().getPort();
    mockServer.createContext(
        "/",
        exchange -> {
          lastApiKeyHeader.set(exchange.getRequestHeaders().getFirst("api-key"));
          byte[] body = exchange.getRequestBody().readAllBytes();
          lastRequestBody.set(new String(body, StandardCharsets.UTF_8));
          requestCount.incrementAndGet();
          exchange.sendResponseHeaders(responseStatus, -1);
          exchange.getResponseBody().close();
        });
    mockServer.start();
  }

  @AfterEach
  void stopMockServer() {
    mockServer.stop(0);
    requestCount.set(0);
    lastRequestBody.set(null);
    lastApiKeyHeader.set(null);
    responseStatus = 201;
  }

  private BrevoEmailProvider providerWithConfig(String apiKey) {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(BrevoEmailProvider.CONFIG_API_KEY, apiKey);
    mock.setAttribute(
        BrevoEmailProvider.CONFIG_API_URL, "http://localhost:" + mockPort + "/v3/smtp/email");
    return new BrevoEmailProvider(mock.asSession());
  }

  @Test
  void send_postsToBrevoWithCorrectPayload() throws Exception {
    BrevoEmailProvider provider = providerWithConfig("brevo-api-key");

    provider.send(
        "42",
        Map.of("link", "https://example.com/verify", "realmName", "My Realm"),
        "user@example.com",
        "Test User",
        "from@example.com",
        "Keycloak");

    assertThat(requestCount.get(), is(1));
    assertThat(lastApiKeyHeader.get(), is("brevo-api-key"));

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("templateId").asInt(), is(42));
    assertThat(payload.get("sender").get("email").asText(), is("from@example.com"));
    assertThat(payload.get("sender").get("name").asText(), is("Keycloak"));

    JsonNode toArray = payload.get("to");
    assertThat(toArray.get(0).get("email").asText(), is("user@example.com"));
    assertThat(toArray.get(0).get("name").asText(), is("Test User"));

    JsonNode params = payload.get("params");
    assertThat(params.get("link").asText(), is("https://example.com/verify"));
    assertThat(params.get("realmName").asText(), is("My Realm"));
  }

  @Test
  void send_omitsNamesWhenBlank() throws Exception {
    BrevoEmailProvider provider = providerWithConfig("key");

    provider.send("7", Map.of(), "to@example.com", "", "from@example.com", "");

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("sender").has("name"), is(false));
    assertThat(payload.get("to").get(0).has("name"), is(false));
    assertThat(payload.has("params"), is(false));
  }

  @Test
  void send_throwsWhenApiKeyMissing() {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(
        BrevoEmailProvider.CONFIG_API_URL, "http://localhost:" + mockPort + "/v3/smtp/email");
    BrevoEmailProvider provider = new BrevoEmailProvider(mock.asSession());

    assertThrows(
        IllegalStateException.class,
        () -> provider.send("7", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsWhenTemplateIdNotNumeric() {
    BrevoEmailProvider provider = providerWithConfig("key");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            provider.send(
                "not-a-number", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsOnNon2xxResponse() {
    responseStatus = 400;
    BrevoEmailProvider provider = providerWithConfig("key");

    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () -> provider.send("7", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(ex.getMessage(), containsString("400"));
  }
}
