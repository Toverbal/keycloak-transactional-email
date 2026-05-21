package io.phasetwo.keycloak.transactional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.phasetwo.keycloak.transactional.provider.awsses.AwsSesEmailProvider;
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
 * Unit tests for {@link AwsSesEmailProvider}. Uses an in-process HTTP mock server; SigV4 signature
 * correctness is verified structurally (format and credential scope) rather than byte-for-byte,
 * since the signature includes the current timestamp.
 */
class AwsSesEmailProviderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer mockServer;
  private int mockPort;
  private final AtomicInteger requestCount = new AtomicInteger(0);
  private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
  private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
  private final AtomicReference<String> lastAmzDate = new AtomicReference<>();
  private int responseStatus = 200;

  @BeforeEach
  void startMockServer() throws IOException {
    mockServer = HttpServer.create(new InetSocketAddress(0), 0);
    mockPort = mockServer.getAddress().getPort();
    mockServer.createContext(
        "/",
        exchange -> {
          lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
          lastAmzDate.set(exchange.getRequestHeaders().getFirst("x-amz-date"));
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
    lastAmzDate.set(null);
    responseStatus = 200;
  }

  private AwsSesEmailProvider providerWithConfig() {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(AwsSesEmailProvider.CONFIG_ACCESS_KEY_ID, "AKIAIOSFODNN7EXAMPLE");
    mock.setAttribute(
        AwsSesEmailProvider.CONFIG_SECRET_ACCESS_KEY, "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
    mock.setAttribute(AwsSesEmailProvider.CONFIG_REGION, "us-east-1");
    mock.setAttribute(
        AwsSesEmailProvider.CONFIG_API_URL,
        "http://localhost:" + mockPort + "/v2/email/outbound-emails");
    return new AwsSesEmailProvider(mock.asSession());
  }

  @Test
  void send_postsToSesWithCorrectPayload() throws Exception {
    AwsSesEmailProvider provider = providerWithConfig();

    provider.send(
        "WelcomeTemplate",
        Map.of("link", "https://example.com/verify", "realmName", "My Realm"),
        "user@example.com",
        "Test User",
        "from@example.com",
        "Keycloak");

    assertThat(requestCount.get(), is(1));

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("FromEmailAddress").asText(), is("Keycloak <from@example.com>"));
    assertThat(
        payload.get("Destination").get("ToAddresses").get(0).asText(), is("user@example.com"));

    JsonNode template = payload.get("Content").get("Template");
    assertThat(template.get("TemplateName").asText(), is("WelcomeTemplate"));

    JsonNode templateData = MAPPER.readTree(template.get("TemplateData").asText());
    assertThat(templateData.get("link").asText(), is("https://example.com/verify"));
    assertThat(templateData.get("realmName").asText(), is("My Realm"));
  }

  @Test
  void send_includesSigV4AuthorizationHeader() throws Exception {
    AwsSesEmailProvider provider = providerWithConfig();

    provider.send("tmpl", Map.of(), "to@example.com", "", "from@example.com", "");

    String auth = lastAuthHeader.get();
    assertThat(auth, startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/"));
    assertThat(auth, containsString("/us-east-1/ses/aws4_request"));
    assertThat(auth, containsString("SignedHeaders=content-type;host;x-amz-date"));
    assertThat(auth, containsString("Signature="));

    assertThat(lastAmzDate.get(), matchesPattern("\\d{8}T\\d{6}Z"));
  }

  @Test
  void send_omitsFromNameWhenBlank() throws Exception {
    AwsSesEmailProvider provider = providerWithConfig();

    provider.send("tmpl", Map.of(), "to@example.com", "", "from@example.com", "");

    JsonNode payload = MAPPER.readTree(lastRequestBody.get());
    assertThat(payload.get("FromEmailAddress").asText(), is("from@example.com"));
  }

  @Test
  void send_throwsWhenAccessKeyMissing() {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(
        AwsSesEmailProvider.CONFIG_SECRET_ACCESS_KEY, "secret");
    mock.setAttribute(AwsSesEmailProvider.CONFIG_REGION, "us-east-1");
    mock.setAttribute(
        AwsSesEmailProvider.CONFIG_API_URL,
        "http://localhost:" + mockPort + "/v2/email/outbound-emails");
    AwsSesEmailProvider provider = new AwsSesEmailProvider(mock.asSession());

    assertThrows(
        IllegalStateException.class,
        () -> provider.send("tmpl", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsWhenRegionMissing() {
    MockKeycloakSession mock = new MockKeycloakSession();
    mock.setAttribute(AwsSesEmailProvider.CONFIG_ACCESS_KEY_ID, "key");
    mock.setAttribute(AwsSesEmailProvider.CONFIG_SECRET_ACCESS_KEY, "secret");
    mock.setAttribute(
        AwsSesEmailProvider.CONFIG_API_URL,
        "http://localhost:" + mockPort + "/v2/email/outbound-emails");
    AwsSesEmailProvider provider = new AwsSesEmailProvider(mock.asSession());

    assertThrows(
        IllegalStateException.class,
        () -> provider.send("tmpl", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(requestCount.get(), is(0));
  }

  @Test
  void send_throwsOnNon2xxResponse() {
    responseStatus = 400;
    AwsSesEmailProvider provider = providerWithConfig();

    RuntimeException ex =
        assertThrows(
            RuntimeException.class,
            () -> provider.send("tmpl", Map.of(), "to@example.com", "", "from@example.com", ""));

    assertThat(ex.getMessage(), containsString("400"));
  }
}
