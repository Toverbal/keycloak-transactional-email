package io.phasetwo.keycloak.transactional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.phasetwo.keycloak.transactional.provider.postmark.PostmarkEmailProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostmarkEmailProviderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer mockServer;
  private int mockPort;
  private final AtomicInteger requestCount = new AtomicInteger(0);
  private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
  private final AtomicReference<String> lastServerTokenHeader = new AtomicReference<>();
  private int responseStatus = 200;

  @BeforeEach
  void startMockServer() throws IOException {
    mockServer = HttpServer.create(new InetSocketAddress(0), 0);
    mockPort = mockServer.getAddress().getPort();
    mockServer.createContext(
        "/",
        exchange -> {
          lastServerTokenHeader.set(
              exchange.getRequestHeaders().getFirst("X-Postmark-Server-Token"));
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
    lastServerTokenHeader.set(null);
    responseStatus = 200;
  }

  private PostmarkEmailProvider providerWithConfig(String serverToken) {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(PostmarkEmailProvider.CONFIG_SERVER_TOKEN, serverToken);
    mock.setAttribute(
        PostmarkEmailProvider.CONFIG_API_URL,
        "http://localhost:" + mockPort + "/email/withTemplate");
    return new PostmarkEmailProvider(mock.asSession());
  }

  @Test
  void send_postsToPostmarkWithNumericTemplateId() throws Exception {
    PostmarkEmailProvider provider = providerWithConfig("pm-token");

    provider.send(
        "12345",
        Map.of("action_url", "https://example.com/verify"),
        "user@example.com",
        "Test User",
        "from@example.com",
        "Keycloak");

    assertThat(requestCount.get(), is(1));
    assertThat(lastServerTokenHeader.get(), is("pm-token"));

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("TemplateId").asInt(), is(12345));
    assertThat(payload.has("TemplateAlias"), is(false));
    assertThat(payload.get("From").asText(), is("Keycloak <from@example.com>"));
    assertThat(payload.get("To").asText(), is("Test User <user@example.com>"));
    assertThat(
        payload.get("TemplateModel").get("action_url").asText(), is("https://example.com/verify"));
  }

  @Test
  void send_usesTemplateAliasForNonNumericId() throws Exception {
    PostmarkEmailProvider provider = providerWithConfig("pm-token");

    provider.send(
        "welcome-email", Map.of(), "user@example.com", "", "from@example.com", "");

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.has("TemplateId"), is(false));
    assertThat(payload.get("TemplateAlias").asText(), is("welcome-email"));
  }

  @Test
  void send_omitsNameWhenBlank() throws Exception {
    PostmarkEmailProvider provider = providerWithConfig("pm-token");

    provider.send("alias", Map.of(), "to@example.com", "", "from@example.com", "");

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("From").asText(), is("from@example.com"));
    assertThat(payload.get("To").asText(), is("to@example.com"));
  }

  @Test
  void send_throwsWhenServerTokenMissing() {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(
        PostmarkEmailProvider.CONFIG_API_URL,
        "http://localhost:" + mockPort + "/email/withTemplate");
    PostmarkEmailProvider provider = new PostmarkEmailProvider(mock.asSession());

    assertThrows(
        IllegalStateException.class,
        () -> provider.send("alias", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsOnNon2xxResponse() {
    responseStatus = 422;
    PostmarkEmailProvider provider = providerWithConfig("pm-token");

    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () ->
                provider.send("alias", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(ex.getMessage(), containsString("422"));
  }
}
