package io.phasetwo.keycloak.email;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.phasetwo.keycloak.email.provider.resend.ResendEmailProvider;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResendEmailProviderTest {

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

  private ResendEmailProvider providerWithConfig(String apiKey) {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(ResendEmailProvider.CONFIG_API_KEY, apiKey);
    mock.setAttribute(ResendEmailProvider.CONFIG_API_URL, "http://localhost:" + mockPort + "/emails");
    return new ResendEmailProvider(mock.asSession());
  }

  @Test
  void send_postsToResendWithCorrectPayload() throws Exception {
    ResendEmailProvider provider = providerWithConfig("re_test_key");

    provider.send(
        "welcome-v2",
        Map.of(
            "subject", "Welcome to My Realm",
            "html", "<p>Hello Test User</p>",
            "realmName", "My Realm"),
        "user@example.com",
        "Test User",
        "from@example.com",
        "Keycloak");

    assertThat(requestCount.get(), is(1));
    assertThat(lastAuthHeader.get(), is("Bearer re_test_key"));

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("from").asText(), is("Keycloak <from@example.com>"));
    assertThat(payload.get("to").get(0).asText(), is("user@example.com"));
    assertThat(payload.get("subject").asText(), is("Welcome to My Realm"));
    assertThat(payload.get("html").asText(), is("<p>Hello Test User</p>"));

    JsonNode tags = payload.get("tags");
    boolean hasTemplateIdTag =
        StreamSupport.stream(tags.spliterator(), false)
            .anyMatch(
                tag ->
                    "template_id".equals(tag.get("name").asText())
                        && "welcome-v2".equals(tag.get("value").asText()));
    assertThat(hasTemplateIdTag, is(true));

    boolean hasRealmTag =
        StreamSupport.stream(tags.spliterator(), false)
            .anyMatch(
                tag ->
                    "realmName".equals(tag.get("name").asText())
                        && "My Realm".equals(tag.get("value").asText()));
    assertThat(hasRealmTag, is(true));
  }

  @Test
  void send_includesTextWhenProvided() throws Exception {
    ResendEmailProvider provider = providerWithConfig("key");

    provider.send(
        "tmpl",
        Map.of("subject", "Hello", "html", "<p>Hi</p>", "text", "Hi"),
        "to@example.com",
        "",
        "from@example.com",
        "");

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("text").asText(), is("Hi"));
  }

  @Test
  void send_omitsTextWhenAbsent() throws Exception {
    ResendEmailProvider provider = providerWithConfig("key");

    provider.send(
        "tmpl",
        Map.of("subject", "Hello", "html", "<p>Hi</p>"),
        "to@example.com",
        "",
        "from@example.com",
        "");

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.has("text"), is(false));
  }

  @Test
  void send_throwsWhenApiKeyMissing() {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(ResendEmailProvider.CONFIG_API_URL, "http://localhost:" + mockPort + "/emails");
    ResendEmailProvider provider = new ResendEmailProvider(mock.asSession());

    assertThrows(
        IllegalStateException.class,
        () ->
            provider.send(
                "tmpl",
                Map.of("subject", "s", "html", "<p>h</p>"),
                "to@example.com",
                "",
                "from@example.com",
                ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsWhenHtmlMissing() {
    ResendEmailProvider provider = providerWithConfig("key");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            provider.send(
                "tmpl",
                Map.of("subject", "Hello"),
                "to@example.com",
                "",
                "from@example.com",
                ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsWhenSubjectMissing() {
    ResendEmailProvider provider = providerWithConfig("key");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            provider.send(
                "tmpl",
                Map.of("html", "<p>Hi</p>"),
                "to@example.com",
                "",
                "from@example.com",
                ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsOnNon2xxResponse() {
    responseStatus = 422;
    ResendEmailProvider provider = providerWithConfig("key");

    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () ->
                provider.send(
                    "tmpl",
                    Map.of("subject", "s", "html", "<p>h</p>"),
                    "to@example.com",
                    "",
                    "from@example.com",
                    ""));

    assertThat(ex.getMessage(), containsString("422"));
  }
}
