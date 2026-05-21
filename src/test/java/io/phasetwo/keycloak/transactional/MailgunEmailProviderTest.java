package io.phasetwo.keycloak.transactional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.phasetwo.keycloak.transactional.provider.mailgun.MailgunEmailProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MailgunEmailProviderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer mockServer;
  private int mockPort;
  private final AtomicInteger requestCount = new AtomicInteger(0);
  private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
  private final AtomicReference<Map<String, String>> lastFormParams = new AtomicReference<>();
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
          String rawBody = new String(body, StandardCharsets.UTF_8);
          lastFormParams.set(parseFormBody(rawBody));
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
    lastAuthHeader.set(null);
    lastFormParams.set(null);
    responseStatus = 200;
  }

  private static Map<String, String> parseFormBody(String body) {
    return Arrays.stream(body.split("&"))
        .map(pair -> pair.split("=", 2))
        .filter(parts -> parts.length == 2)
        .collect(
            Collectors.toMap(
                parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
  }

  private MailgunEmailProvider providerWithConfig(String apiKey, String domain) {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(MailgunEmailProvider.CONFIG_API_KEY, apiKey);
    mock.setAttribute(MailgunEmailProvider.CONFIG_DOMAIN, domain);
    mock.setAttribute(
        MailgunEmailProvider.CONFIG_API_URL, "http://localhost:" + mockPort + "/v3/mg.example.com/messages");
    return new MailgunEmailProvider(mock.asSession());
  }

  @Test
  void send_postsToMailgunWithCorrectPayload() throws Exception {
    MailgunEmailProvider provider = providerWithConfig("test-api-key", "mg.example.com");

    provider.send(
        "welcome-email",
        Map.of("link", "https://example.com/verify", "realmName", "My Realm"),
        "user@example.com",
        "Test User",
        "from@example.com",
        "Keycloak");

    assertThat(requestCount.get(), is(1));

    String auth = lastAuthHeader.get();
    assertThat(auth, startsWith("Basic "));
    String decoded =
        new String(
            java.util.Base64.getDecoder().decode(auth.substring("Basic ".length())),
            StandardCharsets.UTF_8);
    assertThat(decoded, is("api:test-api-key"));

    Map<String, String> params = lastFormParams.get();
    assertThat(params.get("template"), is("welcome-email"));
    assertThat(params.get("from"), is("Keycloak <from@example.com>"));
    assertThat(params.get("to"), is("Test User <user@example.com>"));

    JsonNode variables = MAPPER.readTree(params.get("t:variables"));
    assertThat(variables.get("link").asText(), is("https://example.com/verify"));
    assertThat(variables.get("realmName").asText(), is("My Realm"));
  }

  @Test
  void send_omitsVariablesWhenTemplateDataEmpty() throws Exception {
    MailgunEmailProvider provider = providerWithConfig("key", "mg.example.com");

    provider.send("tmpl", Map.of(), "to@example.com", "", "from@example.com", "");

    Map<String, String> params = lastFormParams.get();
    assertThat(params.get("from"), is("from@example.com"));
    assertThat(params.get("to"), is("to@example.com"));
    assertThat(params.containsKey("t:variables"), is(false));
  }

  @Test
  void send_throwsWhenApiKeyMissing() {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(MailgunEmailProvider.CONFIG_DOMAIN, "mg.example.com");
    mock.setAttribute(
        MailgunEmailProvider.CONFIG_API_URL,
        "http://localhost:" + mockPort + "/v3/mg.example.com/messages");
    MailgunEmailProvider provider = new MailgunEmailProvider(mock.asSession());

    assertThrows(
        IllegalStateException.class,
        () -> provider.send("tmpl", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsWhenDomainMissing() {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(MailgunEmailProvider.CONFIG_API_KEY, "key");
    mock.setAttribute(
        MailgunEmailProvider.CONFIG_API_URL,
        "http://localhost:" + mockPort + "/v3/mg.example.com/messages");
    MailgunEmailProvider provider = new MailgunEmailProvider(mock.asSession());

    assertThrows(
        IllegalStateException.class,
        () -> provider.send("tmpl", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsOnNon2xxResponse() {
    responseStatus = 400;
    MailgunEmailProvider provider = providerWithConfig("key", "mg.example.com");

    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () -> provider.send("tmpl", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(ex.getMessage(), containsString("400"));
  }
}
