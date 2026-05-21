package io.phasetwo.keycloak.email.provider.mailtrap;

import io.phasetwo.keycloak.email.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.email.template.TransactionalEmailTemplateProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.models.KeycloakSession;

/**
 * Sends emails via the Mailtrap Email Sending API.
 *
 * <p>Required realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.mailtrap.api-token} — Mailtrap API token
 * </ul>
 *
 * <p>Optional realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.mailtrap.api-url} — override the API endpoint;
 *       defaults to {@value #DEFAULT_API_URL}
 * </ul>
 */
@JBossLog
public class MailtrapEmailProvider implements TransactionalEmailProvider {

  public static final String DEFAULT_API_URL = "https://send.api.mailtrap.io/api/send";

  public static final String CONFIG_API_TOKEN =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".mailtrap.api-token";
  public static final String CONFIG_API_URL =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".mailtrap.api-url";

  private final KeycloakSession session;

  public MailtrapEmailProvider(KeycloakSession session) {
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

    String apiToken = session.getContext().getRealm().getAttribute(CONFIG_API_TOKEN);
    if (apiToken == null || apiToken.isBlank()) {
      throw new IllegalStateException(
          "Mailtrap API token not configured. Set realm attribute: " + CONFIG_API_TOKEN);
    }

    String apiUrl = session.getContext().getRealm().getAttribute(CONFIG_API_URL);
    if (apiUrl == null || apiUrl.isBlank()) {
      apiUrl = DEFAULT_API_URL;
    }

    SimpleHttp.Response response =
        SimpleHttp.doPost(apiUrl, session)
            .header("Authorization", "Bearer " + apiToken)
            .json(buildPayload(templateId, templateData, toEmail, toName, fromEmail, fromName))
            .asResponse();

    int status = response.getStatus();
    if (status < 200 || status >= 300) {
      throw new RuntimeException("Mailtrap API error " + status + ": " + response.asString());
    }

    log.debugf("Mailtrap accepted email to %s via template %s", toEmail, templateId);
  }

  private Map<String, Object> buildPayload(
      String templateId,
      Map<String, Object> templateData,
      String toEmail,
      String toName,
      String fromEmail,
      String fromName) {

    Map<String, Object> payload = new HashMap<>();
    payload.put("template_uuid", templateId);

    Map<String, Object> from = new HashMap<>();
    from.put("email", fromEmail);
    if (fromName != null && !fromName.isBlank()) from.put("name", fromName);
    payload.put("from", from);

    Map<String, Object> to = new HashMap<>();
    to.put("email", toEmail);
    if (toName != null && !toName.isBlank()) to.put("name", toName);
    payload.put("to", List.of(to));

    if (!templateData.isEmpty()) {
      payload.put("template_variables", templateData);
    }

    return payload;
  }

  @Override
  public void close() {}
}
