package io.phasetwo.keycloak.transactional.provider.awsses;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.transactional.template.TransactionalEmailTemplateProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;

/**
 * Sends emails via the AWS SES v2 API using stored SES templates and AWS Signature Version 4
 * request signing.
 *
 * <p>Required realm attributes:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.awsses.access-key-id} — AWS access key ID
 *   <li>{@code _providerConfig.ext-email-template.awsses.secret-access-key} — AWS secret access key
 *   <li>{@code _providerConfig.ext-email-template.awsses.region} — AWS region (e.g. {@code us-east-1})
 * </ul>
 *
 * <p>Optional realm attribute:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.awsses.api-url} — override the full endpoint URL;
 *       defaults to {@code https://email.{region}.amazonaws.com/v2/email/outbound-emails}
 * </ul>
 *
 * <p>The {@code templateId} maps to SES {@code TemplateName}. The {@code templateData} map is
 * serialized to JSON and passed as {@code TemplateData}, which SES uses to replace Handlebars-style
 * variables ({@code {{variable}}}) in the stored template.
 */
@JBossLog
public class AwsSesEmailProvider implements TransactionalEmailProvider {

  public static final String CONFIG_ACCESS_KEY_ID =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".awsses.access-key-id";
  public static final String CONFIG_SECRET_ACCESS_KEY =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".awsses.secret-access-key";
  public static final String CONFIG_REGION =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".awsses.region";
  public static final String CONFIG_API_URL =
      TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".awsses.api-url";

  private static final String API_PATH = "/v2/email/outbound-emails";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  private final KeycloakSession session;

  public AwsSesEmailProvider(KeycloakSession session) {
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

    String accessKeyId = session.getContext().getRealm().getAttribute(CONFIG_ACCESS_KEY_ID);
    if (accessKeyId == null || accessKeyId.isBlank()) {
      throw new IllegalStateException(
          "AWS SES access key ID not configured. Set realm attribute: " + CONFIG_ACCESS_KEY_ID);
    }

    String secretAccessKey = session.getContext().getRealm().getAttribute(CONFIG_SECRET_ACCESS_KEY);
    if (secretAccessKey == null || secretAccessKey.isBlank()) {
      throw new IllegalStateException(
          "AWS SES secret access key not configured. Set realm attribute: "
              + CONFIG_SECRET_ACCESS_KEY);
    }

    String region = session.getContext().getRealm().getAttribute(CONFIG_REGION);
    if (region == null || region.isBlank()) {
      throw new IllegalStateException(
          "AWS SES region not configured. Set realm attribute: " + CONFIG_REGION);
    }

    String apiUrl = session.getContext().getRealm().getAttribute(CONFIG_API_URL);
    if (apiUrl == null || apiUrl.isBlank()) {
      apiUrl = "https://email." + region + ".amazonaws.com" + API_PATH;
    }

    String payload = MAPPER.writeValueAsString(
        buildPayload(templateId, templateData, toEmail, toName, fromEmail, fromName));

    URI uri = URI.create(apiUrl);
    String host = uri.getHost();
    String path = uri.getPath();

    ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
    String[] sigHeaders =
        AwsSigV4Signer.sign(accessKeyId, secretAccessKey, region, host, path, payload, now);
    String amzDate = sigHeaders[0];
    String authorization = sigHeaders[1];

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(uri)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .header("Content-Type", "application/json")
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    int status = response.statusCode();
    if (status < 200 || status >= 300) {
      throw new RuntimeException("AWS SES API error " + status + ": " + response.body());
    }

    log.debugf("AWS SES accepted email to %s via template %s", toEmail, templateId);
  }

  private Map<String, Object> buildPayload(
      String templateId,
      Map<String, Object> templateData,
      String toEmail,
      String toName,
      String fromEmail,
      String fromName)
      throws Exception {

    String from =
        (fromName != null && !fromName.isBlank())
            ? fromName + " <" + fromEmail + ">"
            : fromEmail;

    Map<String, Object> destination = new HashMap<>();
    destination.put("ToAddresses", new String[] {toEmail});

    Map<String, Object> template = new HashMap<>();
    template.put("TemplateName", templateId);
    template.put("TemplateData", MAPPER.writeValueAsString(templateData));

    Map<String, Object> content = new HashMap<>();
    content.put("Template", template);

    Map<String, Object> payload = new HashMap<>();
    payload.put("FromEmailAddress", from);
    payload.put("Destination", destination);
    payload.put("Content", content);

    return payload;
  }

  @Override
  public void close() {}
}
