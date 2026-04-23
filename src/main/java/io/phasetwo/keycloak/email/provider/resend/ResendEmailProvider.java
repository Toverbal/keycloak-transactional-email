package io.phasetwo.keycloak.email.provider.resend;

import io.phasetwo.keycloak.email.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.email.template.TransactionalEmailTemplateProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.models.KeycloakSession;

/**
 * Sends emails via the Resend email API.
 *
 * <p>Required realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.resend.api-key} — Resend API key
 * </ul>
 *
 * <p>Optional realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.resend.api-url} — override the API endpoint;
 *       defaults to {@value #DEFAULT_API_URL}
 * </ul>
 *
 * <p><b>Template rendering:</b> Resend does not support server-side template rendering via its REST
 * API. The {@code templateId} is recorded as a {@code template_id} tag for traceability. The
 * {@code templateData} map must supply pre-rendered content under the following reserved keys:
 *
 * <ul>
 *   <li>{@code html} <em>(required)</em> — fully rendered HTML body
 *   <li>{@code subject} <em>(required)</em> — email subject line
 *   <li>{@code text} <em>(optional)</em> — plain-text fallback body
 * </ul>
 *
 * <p>All other {@code templateData} entries are passed as Resend tags (name/value pairs) for
 * filtering and analytics in the Resend dashboard.
 */
@JBossLog
public class ResendEmailProvider implements TransactionalEmailProvider {

  public static final String DEFAULT_API_URL = "https://api.resend.com/emails";

  public static final String CONFIG_API_KEY =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".resend.api-key";
  public static final String CONFIG_API_URL =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".resend.api-url";

  private final KeycloakSession session;

  public ResendEmailProvider(KeycloakSession session) {
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
          "Resend API key not configured. Set realm attribute: " + CONFIG_API_KEY);
    }

    Object html = templateData.get("html");
    if (html == null || html.toString().isBlank()) {
      throw new IllegalArgumentException(
          "Resend requires pre-rendered HTML. Supply 'html' in templateData.");
    }

    Object subject = templateData.get("subject");
    if (subject == null || subject.toString().isBlank()) {
      throw new IllegalArgumentException(
          "Resend requires a subject. Supply 'subject' in templateData.");
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
      throw new RuntimeException("Resend API error " + status + ": " + response.asString());
    }

    log.debugf("Resend accepted email to %s (template tag: %s)", toEmail, templateId);
  }

  private Map<String, Object> buildPayload(
      String templateId,
      Map<String, Object> templateData,
      String toEmail,
      String toName,
      String fromEmail,
      String fromName) {

    Map<String, Object> payload = new HashMap<>();

    String from =
        (fromName != null && !fromName.isBlank())
            ? fromName + " <" + fromEmail + ">"
            : fromEmail;
    payload.put("from", from);
    payload.put("to", List.of(toEmail));
    payload.put("subject", templateData.get("subject").toString());
    payload.put("html", templateData.get("html").toString());

    Object text = templateData.get("text");
    if (text != null && !text.toString().isBlank()) {
      payload.put("text", text.toString());
    }

    List<Map<String, String>> tags = new ArrayList<>();
    tags.add(Map.of("name", "template_id", "value", templateId));
    templateData.forEach(
        (key, value) -> {
          if (!"html".equals(key) && !"subject".equals(key) && !"text".equals(key)) {
            tags.add(Map.of("name", key, "value", value.toString()));
          }
        });
    payload.put("tags", tags);

    return payload;
  }

  @Override
  public void close() {}
}
