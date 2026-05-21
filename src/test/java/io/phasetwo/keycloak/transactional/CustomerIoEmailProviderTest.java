package io.phasetwo.keycloak.transactional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.phasetwo.keycloak.transactional.provider.customerio.CustomerIoEmailProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomerIoEmailProviderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer mockServer;
  private int mockPort;
  private final AtomicInteger requestCount = new AtomicInteger(0);
  private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
  private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
  private int responseStatus = 200;

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
    responseStatus = 200;
  }

  private CustomerIoEmailProvider providerWithConfig(String apiKey) {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(CustomerIoEmailProvider.CONFIG_API_KEY, apiKey);
    mock.setAttribute(
        CustomerIoEmailProvider.CONFIG_API_URL, "http://localhost:" + mockPort + "/v1/send/email");
    return new CustomerIoEmailProvider(mock.asSession());
  }

  @Test
  void send_postsToCustomerIoWithCorrectPayload() throws Exception {
    CustomerIoEmailProvider provider = providerWithConfig("cio-app-api-key");

    provider.send(
        "password-reset",
        Map.of("link", "https://example.com/reset", "realmName", "My Realm"),
        "user@example.com",
        "Test User",
        "from@example.com",
        "Keycloak");

    assertThat(requestCount.get(), is(1));
    assertThat(lastAuthHeader.get(), is("Bearer cio-app-api-key"));

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("transactional_message_id").asText(), is("password-reset"));
    assertThat(payload.get("to").asText(), is("user@example.com"));
    assertThat(payload.get("from").asText(), is("Keycloak <from@example.com>"));
    assertThat(payload.get("send_to_unsubscribed").asBoolean(), is(true));

    JsonNode identifiers = payload.get("identifiers");
    assertThat(identifiers.get("email").asText(), is("user@example.com"));

    JsonNode messageData = payload.get("message_data");
    assertThat(messageData.get("link").asText(), is("https://example.com/reset"));
    assertThat(messageData.get("realmName").asText(), is("My Realm"));
  }

  @Test
  void send_omitsFromNameWhenBlank() throws Exception {
    CustomerIoEmailProvider provider = providerWithConfig("key");

    provider.send("tmpl", Map.of("k", "v"), "to@example.com", "", "from@example.com", "");

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("from").asText(), is("from@example.com"));
  }

  @Test
  void send_omitsMessageDataWhenTemplateDataEmpty() throws Exception {
    CustomerIoEmailProvider provider = providerWithConfig("key");

    provider.send("tmpl", Map.of(), "to@example.com", "", "from@example.com", "");

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.has("message_data"), is(false));
  }

  @Test
  void send_throwsWhenApiKeyMissing() {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(
        CustomerIoEmailProvider.CONFIG_API_URL,
        "http://localhost:" + mockPort + "/v1/send/email");
    CustomerIoEmailProvider provider = new CustomerIoEmailProvider(mock.asSession());

    assertThrows(
        IllegalStateException.class,
        () -> provider.send("tmpl", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsOnNon2xxResponse() {
    responseStatus = 400;
    CustomerIoEmailProvider provider = providerWithConfig("key");

    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () -> provider.send("tmpl", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(ex.getMessage(), containsString("400"));
  }
}
