package io.phasetwo.keycloak.transactional.provider.brevo;

import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.transactional.template.TransactionalEmailTemplateProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.models.KeycloakSession;

/**
 * Sends emails via the Brevo (formerly Sendinblue) Transactional Email API.
 *
 * <p>Required realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.brevo.api-key} — Brevo API key
 * </ul>
 *
 * <p>Optional realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.brevo.api-url} — override the API endpoint;
 *       defaults to {@value #DEFAULT_API_URL}
 * </ul>
 *
 * <p>The template ID stored in the realm must be a numeric Brevo template ID (e.g. {@code "42"}).
 */
@JBossLog
public class BrevoEmailProvider implements TransactionalEmailProvider {

  public static final String DEFAULT_API_URL = "https://api.brevo.com/v3/smtp/email";

  public static final String CONFIG_API_KEY =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".brevo.api-key";
  public static final String CONFIG_API_URL =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".brevo.api-url";

  private final KeycloakSession session;

  public BrevoEmailProvider(KeycloakSession session) {
    this.session = session;
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
          "Brevo API key not configured. Set realm attribute: " + CONFIG_API_KEY);
    }

    int templateIdInt;
    try {
      templateIdInt = Integer.parseInt(templateId);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Brevo template ID must be a numeric value, got: " + templateId);
    }

    String apiUrl = session.getContext().getRealm().getAttribute(CONFIG_API_URL);
    if (apiUrl == null || apiUrl.isBlank()) {
      apiUrl = DEFAULT_API_URL;
    }

    SimpleHttp.Response response =
        SimpleHttp.doPost(apiUrl, session)
            .header("api-key", apiKey)
            .json(buildPayload(templateIdInt, templateData, toEmail, toName, fromEmail, fromName))
            .asResponse();

    int status = response.getStatus();
    if (status < 200 || status >= 300) {
      throw new RuntimeException("Brevo API error " + status + ": " + response.asString());
    }

    log.debugf("Brevo accepted email to %s via template %d", toEmail, templateIdInt);
  }

  private Map<String, Object> buildPayload(
      int templateId,
      Map<String, Object> templateData,
      String toEmail,
      String toName,
      String fromEmail,
      String fromName) {

    Map<String, Object> payload = new HashMap<>();
    payload.put("templateId", templateId);

    Map<String, Object> sender = new HashMap<>();
    sender.put("email", fromEmail);
    if (fromName != null && !fromName.isBlank()) sender.put("name", fromName);
    payload.put("sender", sender);

    Map<String, Object> to = new HashMap<>();
    to.put("email", toEmail);
    if (toName != null && !toName.isBlank()) to.put("name", toName);
    payload.put("to", List.of(to));

    if (!templateData.isEmpty()) {
      payload.put("params", templateData);
    }

    return payload;
  }

  @Override
  public void close() {}
}
