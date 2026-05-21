package io.phasetwo.keycloak.transactional.provider.mailgun;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.transactional.template.TransactionalEmailTemplateProvider;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.models.KeycloakSession;

/**
 * Sends emails via the Mailgun Sending API using stored Handlebars templates.
 *
 * <p>Required realm attributes:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.mailgun.api-key} — Mailgun API key
 *   <li>{@code _providerConfig.ext-email-template.mailgun.domain} — Mailgun sending domain (e.g.
 *       {@code mg.example.com})
 * </ul>
 *
 * <p>Optional realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.mailgun.api-url} — override the full API
 *       endpoint; defaults to {@value #DEFAULT_BASE_URL}/{domain}/messages
 * </ul>
 *
 * <p>For EU customers, set {@code mailgun.api-url} to
 * {@code https://api.eu.mailgun.net/v3/{domain}/messages}.
 */
@JBossLog
public class MailgunEmailProvider implements TransactionalEmailProvider {

  public static final String DEFAULT_BASE_URL = "https://api.mailgun.net/v3";

  public static final String CONFIG_API_KEY =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".mailgun.api-key";
  public static final String CONFIG_DOMAIN =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".mailgun.domain";
  public static final String CONFIG_API_URL =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".mailgun.api-url";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final KeycloakSession session;

  public MailgunEmailProvider(KeycloakSession session) {
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
          "Mailgun API key not configured. Set realm attribute: " + CONFIG_API_KEY);
    }

    String domain = session.getContext().getRealm().getAttribute(CONFIG_DOMAIN);
    if (domain == null || domain.isBlank()) {
      throw new IllegalStateException(
          "Mailgun domain not configured. Set realm attribute: " + CONFIG_DOMAIN);
    }

    String apiUrl = session.getContext().getRealm().getAttribute(CONFIG_API_URL);
    if (apiUrl == null || apiUrl.isBlank()) {
      apiUrl = DEFAULT_BASE_URL + "/" + domain + "/messages";
    }

    String credentials =
        Base64.getEncoder()
            .encodeToString(("api:" + apiKey).getBytes(StandardCharsets.UTF_8));

    String from =
        (fromName != null && !fromName.isBlank())
            ? fromName + " <" + fromEmail + ">"
            : fromEmail;
    String to =
        (toName != null && !toName.isBlank()) ? toName + " <" + toEmail + ">" : toEmail;

    SimpleHttp req =
        SimpleHttp.doPost(apiUrl, session)
            .header("Authorization", "Basic " + credentials)
            .param("from", from)
            .param("to", to)
            .param("template", templateId);

    if (!templateData.isEmpty()) {
      req.param("t:variables", MAPPER.writeValueAsString(templateData));
    }

    SimpleHttp.Response response = req.asResponse();
    int status = response.getStatus();
    if (status < 200 || status >= 300) {
      throw new RuntimeException("Mailgun API error " + status + ": " + response.asString());
    }

    log.debugf("Mailgun accepted email to %s via template %s", toEmail, templateId);
  }

  @Override
  public void close() {}
}
