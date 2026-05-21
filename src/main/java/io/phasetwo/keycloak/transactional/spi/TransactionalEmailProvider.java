package io.phasetwo.keycloak.transactional.spi;

import java.util.Map;
import org.keycloak.provider.Provider;

/**
 * SPI for sending transactional emails via an external provider using that provider's dynamic
 * template system. Implementations receive a provider-specific template ID and the raw variables
 * that Keycloak would normally pass to FreeMarker, and are responsible for calling the provider's
 * API.
 *
 * <p>To add a new provider, implement this interface and {@link TransactionalEmailProviderFactory},
 * then register the factory with {@code @AutoService(TransactionalEmailProviderFactory.class)}.
 */
public interface TransactionalEmailProvider extends Provider {

  /**
   * Send an email using the provider's dynamic template system.
   *
   * @param templateId the provider-specific template identifier (e.g. SendGrid's "d-abc123")
   * @param templateData key/value pairs of variables to pass to the template engine
   * @param toEmail recipient email address
   * @param toName recipient display name (may be null or empty)
   * @param fromEmail sender email address
   * @param fromName sender display name (may be null or empty)
   * @throws Exception on any delivery failure; callers wrap this in {@code EmailException}
   */
  void send(
      String templateId,
      Map<String, Object> templateData,
      String toEmail,
      String toName,
      String fromEmail,
      String fromName)
      throws Exception;
}
