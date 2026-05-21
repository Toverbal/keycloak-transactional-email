package io.phasetwo.keycloak.transactional.provider.postmark;

import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.transactional.template.TransactionalEmailTemplateProvider;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.models.KeycloakSession;

/**
 * Sends emails via the Postmark Transactional Email API.
 *
 * <p>Required realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.postmark.server-token} — Postmark server token
 * </ul>
 *
 * <p>Optional realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.postmark.api-url} — override the API endpoint;
 *       defaults to {@value #DEFAULT_API_URL}
 * </ul>
 *
 * <p>The template ID stored in the realm may be a numeric ID or an alias string (both are accepted
 * by Postmark's API).
 */
@JBossLog
public class PostmarkEmailProvider implements TransactionalEmailProvider {

  public static final String DEFAULT_API_URL =
      "https://api.postmarkapp.com/email/withTemplate";

  public static final String CONFIG_SERVER_TOKEN =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".postmark.server-token";
  public static final String CONFIG_API_URL =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".postmark.api-url";

  private final KeycloakSession session;

  public PostmarkEmailProvider(KeycloakSession session) {
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

    String serverToken = session.getContext().getRealm().getAttribute(CONFIG_SERVER_TOKEN);
    if (serverToken == null || serverToken.isBlank()) {
      throw new IllegalStateException(
          "Postmark server token not configured. Set realm attribute: " + CONFIG_SERVER_TOKEN);
    }

    String apiUrl = session.getContext().getRealm().getAttribute(CONFIG_API_URL);
    if (apiUrl == null || apiUrl.isBlank()) {
      apiUrl = DEFAULT_API_URL;
    }

    SimpleHttp.Response response =
        SimpleHttp.doPost(apiUrl, session)
            .header("X-Postmark-Server-Token", serverToken)
            .json(buildPayload(templateId, templateData, toEmail, toName, fromEmail, fromName))
            .asResponse();

    int status = response.getStatus();
    if (status < 200 || status >= 300) {
      throw new RuntimeException("Postmark API error " + status + ": " + response.asString());
    }

    log.debugf("Postmark accepted email to %s via template %s", toEmail, templateId);
  }

  private Map<String, Object> buildPayload(
      String templateId,
      Map<String, Object> templateData,
      String toEmail,
      String toName,
      String fromEmail,
      String fromName) {

    Map<String, Object> payload = new HashMap<>();

    // Postmark accepts either a numeric TemplateId or a string TemplateAlias
    try {
      payload.put("TemplateId", Integer.parseInt(templateId));
    } catch (NumberFormatException e) {
      payload.put("TemplateAlias", templateId);
    }

    String from = (fromName != null && !fromName.isBlank())
        ? fromName + " <" + fromEmail + ">"
        : fromEmail;
    payload.put("From", from);

    String to = (toName != null && !toName.isBlank())
        ? toName + " <" + toEmail + ">"
        : toEmail;
    payload.put("To", to);

    payload.put("TemplateModel", templateData);

    return payload;
  }

  @Override
  public void close() {}
}
