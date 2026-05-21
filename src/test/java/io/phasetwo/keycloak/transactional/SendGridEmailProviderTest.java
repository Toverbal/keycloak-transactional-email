package io.phasetwo.keycloak.transactional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.phasetwo.keycloak.transactional.provider.sendgrid.SendGridEmailProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SendGridEmailProvider} using a lightweight in-process HTTP server to mock
 * the SendGrid API. No Keycloak container required.
 */
class SendGridEmailProviderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer mockServer;
  private int mockPort;
  private final AtomicInteger requestCount = new AtomicInteger(0);
  private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
  private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
  private int responseStatus = 202;

  @BeforeEach
  void startMockServer() throws IOException {
    mockServer = HttpServer.create(new InetSocketAddress(0), 0);
    mockPort = mockServer.getAddress().getPort();
    mockServer.createContext(
        "/",
        exchange -> {
          lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
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
    lastAuthHeader.set(null);
    responseStatus = 202;
  }

  private SendGridEmailProvider providerWithConfig(String apiKey) {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(SendGridEmailProvider.CONFIG_API_KEY, apiKey);
    mock.setAttribute(
        SendGridEmailProvider.CONFIG_API_URL, "http://localhost:" + mockPort + "/v3/mail/send");
    return new SendGridEmailProvider(mock.asSession());
  }

  @Test
  void send_postsToSendGridWithCorrectPayload() throws Exception {
    SendGridEmailProvider provider = providerWithConfig("SG.test-api-key");

    provider.send(
        "d-template123",
        Map.of("link", "https://example.com/verify", "realmName", "My Realm"),
        "user@example.com",
        "Test User",
        "from@example.com",
        "Keycloak");

    assertThat(requestCount.get(), is(1));
    assertThat(lastAuthHeader.get(), is("Bearer SG.test-api-key"));

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("template_id").asText(), is("d-template123"));
    assertThat(payload.get("from").get("email").asText(), is("from@example.com"));
    assertThat(payload.get("from").get("name").asText(), is("Keycloak"));

    JsonNode personalization = payload.get("personalizations").get(0);
    assertThat(personalization.get("to").get(0).get("email").asText(), is("user@example.com"));
    assertThat(personalization.get("to").get(0).get("name").asText(), is("Test User"));

    JsonNode data = personalization.get("dynamic_template_data");
    assertThat(data.get("link").asText(), is("https://example.com/verify"));
    assertThat(data.get("realmName").asText(), is("My Realm"));
  }

  @Test
  void send_omitsFromNameWhenBlank() throws Exception {
    SendGridEmailProvider provider = providerWithConfig("SG.key");

    provider.send("d-t", Map.of(), "to@example.com", "", "from@example.com", "");

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("from").has("name"), is(false));
    assertThat(payload.get("personalizations").get(0).get("to").get(0).has("name"), is(false));
  }

  @Test
  void send_throwsWhenApiKeyMissing() {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(
        SendGridEmailProvider.CONFIG_API_URL, "http://localhost:" + mockPort + "/v3/mail/send");
    SendGridEmailProvider provider = new SendGridEmailProvider(mock.asSession());

    assertThrows(
        IllegalStateException.class,
        () -> provider.send("d-t", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsOnNon2xxResponse() {
    responseStatus = 400;
    SendGridEmailProvider provider = providerWithConfig("SG.key");

    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () -> provider.send("d-t", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(ex.getMessage(), containsString("400"));
  }
}
