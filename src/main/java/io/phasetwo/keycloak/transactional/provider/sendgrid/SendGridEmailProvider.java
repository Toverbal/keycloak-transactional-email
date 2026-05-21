package io.phasetwo.keycloak.transactional.provider.sendgrid;

import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.transactional.template.TransactionalEmailTemplateProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.broker.provider.util.SimpleHttp;
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

  public SendGridEmailProvider(KeycloakSession session) {
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
          "SendGrid API key not configured. Set realm attribute: " + CONFIG_API_KEY);
    }

    String apiUrl = session.getContext().getRealm().getAttribute(CONFIG_API_URL);
    if (apiUrl == null || apiUrl.isBlank()) {
      apiUrl = DEFAULT_API_URL;
    }

    SimpleHttp.Response response =
        SimpleHttp.doPost(apiUrl, session)
            .header("Authorization", "Bearer " + apiKey)
            .json(buildPayload(templateId, templateData, toEmail, toName, fromEmail, fromName))
            .asResponse();

    int status = response.getStatus();
    if (status < 200 || status >= 300) {
      throw new RuntimeException("SendGrid API error " + status + ": " + response.asString());
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
