package io.phasetwo.keycloak.email;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.phasetwo.keycloak.email.provider.mailtrap.MailtrapEmailProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MailtrapEmailProviderTest {

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

  private MailtrapEmailProvider providerWithConfig(String apiToken) {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(MailtrapEmailProvider.CONFIG_API_TOKEN, apiToken);
    mock.setAttribute(
        MailtrapEmailProvider.CONFIG_API_URL, "http://localhost:" + mockPort + "/api/send");
    return new MailtrapEmailProvider(mock.asSession());
  }

  @Test
  void send_postsToMailtrapWithCorrectPayload() throws Exception {
    MailtrapEmailProvider provider = providerWithConfig("mt-token");

    provider.send(
        "uuid-template-123",
        Map.of("link", "https://example.com/verify", "realmName", "My Realm"),
        "user@example.com",
        "Test User",
        "from@example.com",
        "Keycloak");

    assertThat(requestCount.get(), is(1));
    assertThat(lastAuthHeader.get(), is("Bearer mt-token"));

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("template_uuid").asText(), is("uuid-template-123"));
    assertThat(payload.get("from").get("email").asText(), is("from@example.com"));
    assertThat(payload.get("from").get("name").asText(), is("Keycloak"));

    JsonNode toArray = payload.get("to");
    assertThat(toArray.get(0).get("email").asText(), is("user@example.com"));
    assertThat(toArray.get(0).get("name").asText(), is("Test User"));

    JsonNode vars = payload.get("template_variables");
    assertThat(vars.get("link").asText(), is("https://example.com/verify"));
    assertThat(vars.get("realmName").asText(), is("My Realm"));
  }

  @Test
  void send_omitsNamesAndVarsWhenBlank() throws Exception {
    MailtrapEmailProvider provider = providerWithConfig("mt-token");

    provider.send("uuid-123", Map.of(), "to@example.com", "", "from@example.com", "");

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("from").has("name"), is(false));
    assertThat(payload.get("to").get(0).has("name"), is(false));
    assertThat(payload.has("template_variables"), is(false));
  }

  @Test
  void send_throwsWhenApiTokenMissing() {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(
        MailtrapEmailProvider.CONFIG_API_URL, "http://localhost:" + mockPort + "/api/send");
    MailtrapEmailProvider provider = new MailtrapEmailProvider(mock.asSession());

    assertThrows(
        IllegalStateException.class,
        () -> provider.send("uuid", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsOnNon2xxResponse() {
    responseStatus = 500;
    MailtrapEmailProvider provider = providerWithConfig("mt-token");

    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () -> provider.send("uuid", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(ex.getMessage(), containsString("500"));
  }
}
