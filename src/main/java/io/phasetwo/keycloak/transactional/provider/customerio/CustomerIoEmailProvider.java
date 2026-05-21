package io.phasetwo.keycloak.transactional.provider.customerio;

import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.transactional.template.TransactionalEmailTemplateProvider;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.models.KeycloakSession;

/**
 * Sends emails via the Customer.io Transactional API using stored message templates.
 *
 * <p>Required realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.customerio.api-key} — Customer.io App API key
 * </ul>
 *
 * <p>Optional realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.customerio.api-url} — override the API endpoint;
 *       defaults to {@value #DEFAULT_API_URL}
 * </ul>
 *
 * <p>The {@code templateId} maps to Customer.io's {@code transactional_message_id}, which may be
 * either a numeric ID or a string trigger name.
 *
 * <p>Transactional emails are sent with {@code send_to_unsubscribed: true} so that lifecycle
 * messages (e.g. password reset) reach all recipients regardless of subscription state.
 */
@JBossLog
public class CustomerIoEmailProvider implements TransactionalEmailProvider {

  public static final String DEFAULT_API_URL = "https://api.customer.io/v1/send/email";

  public static final String CONFIG_API_KEY =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".customerio.api-key";
  public static final String CONFIG_API_URL =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".customerio.api-url";

  private final KeycloakSession session;

  public CustomerIoEmailProvider(KeycloakSession session) {
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
          "Customer.io API key not configured. Set realm attribute: " + CONFIG_API_KEY);
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
      throw new RuntimeException("Customer.io API error " + status + ": " + response.asString());
    }

    log.debugf("Customer.io accepted email to %s via message %s", toEmail, templateId);
  }

  private Map<String, Object> buildPayload(
      String templateId,
      Map<String, Object> templateData,
      String toEmail,
      String toName,
      String fromEmail,
      String fromName) {

    Map<String, Object> payload = new HashMap<>();
    payload.put("transactional_message_id", templateId);
    payload.put("to", toEmail);
    payload.put("send_to_unsubscribed", true);

    Map<String, String> identifiers = new HashMap<>();
    identifiers.put("email", toEmail);
    payload.put("identifiers", identifiers);

    String from =
        (fromName != null && !fromName.isBlank())
            ? fromName + " <" + fromEmail + ">"
            : fromEmail;
    payload.put("from", from);

    if (!templateData.isEmpty()) {
      payload.put("message_data", templateData);
    }

    return payload;
  }

  @Override
  public void close() {}
}
