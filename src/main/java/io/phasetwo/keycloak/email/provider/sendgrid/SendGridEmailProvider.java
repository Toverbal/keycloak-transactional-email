package io.phasetwo.keycloak.email.provider.sendgrid;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.phasetwo.keycloak.email.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.email.template.TransactionalEmailTemplateProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;

/**
 * Sends emails via the SendGrid v3 Mail Send API using dynamic transactional templates.
 *
 * <p>Required realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.sendgrid.api-key} — SendGrid API key
 * </ul>
 *
 * <p>Optional realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.sendgrid.api-url} — override the API endpoint
 *       (useful in tests); defaults to {@value #DEFAULT_API_URL}
 * </ul>
 */
@JBossLog
public class SendGridEmailProvider implements TransactionalEmailProvider {

  public static final String DEFAULT_API_URL = "https://api.sendgrid.com/v3/mail/send";

  public static final String CONFIG_API_KEY =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".sendgrid.api-key";
  public static final String CONFIG_API_URL =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".sendgrid.api-url";

  private final KeycloakSession session;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public SendGridEmailProvider(KeycloakSession session) {
    this.session = session;
    this.httpClient = HttpClient.newHttpClient();
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public void send(
      String templateId,
      Map<String, Object> templateData,
      String toEmail,
      String toName,
      String fromEmail,
      String fromName)
      throws Exception {

    String apiKey = session.getContext().getRealm().getAttribute(CONFIG_API_KEY);
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException(
          "SendGrid API key not configured. Set realm attribute: " + CONFIG_API_KEY);
    }

    String apiUrl = session.getContext().getRealm().getAttribute(CONFIG_API_URL);
    if (apiUrl == null || apiUrl.isBlank()) {
      apiUrl = DEFAULT_API_URL;
    }

    String body = objectMapper.writeValueAsString(buildPayload(templateId, templateData, toEmail, toName, fromEmail, fromName));

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new RuntimeException(
          "SendGrid API error " + response.statusCode() + ": " + response.body());
    }

    log.debugf("SendGrid accepted email to %s via template %s", toEmail, templateId);
  }

  private Map<String, Object> buildPayload(
      String templateId,
      Map<String, Object> templateData,
      String toEmail,
      String toName,
      String fromEmail,
      String fromName) {

    Map<String, Object> payload = new HashMap<>();
    payload.put("template_id", templateId);

    Map<String, Object> from = new HashMap<>();
    from.put("email", fromEmail);
    if (fromName != null && !fromName.isBlank()) from.put("name", fromName);
    payload.put("from", from);

    Map<String, Object> to = new HashMap<>();
    to.put("email", toEmail);
    if (toName != null && !toName.isBlank()) to.put("name", toName);

    Map<String, Object> personalization = new HashMap<>();
    personalization.put("to", List.of(to));
    personalization.put("dynamic_template_data", templateData);

    payload.put("personalizations", List.of(personalization));

    return payload;
  }

  @Override
  public void close() {}
}
