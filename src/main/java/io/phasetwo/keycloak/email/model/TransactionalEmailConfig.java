package io.phasetwo.keycloak.email.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * REST representation of the transactional email configuration for a realm.
 *
 * <p>{@code provider} is the ID of the active {@code TransactionalEmailProvider} (e.g. {@code
 * "sendgrid"}). Leave null or empty to disable transactional routing and fall back to standard
 * SMTP/FreeMarker.
 *
 * <p>{@code templates} maps Keycloak email-type names (e.g. {@code "password-reset"}) to
 * provider-specific template IDs. Only email types with a mapping are routed to the external
 * provider; others fall back to FreeMarker.
 *
 * <p>{@code providerConfig} holds provider-specific key/value settings scoped under the provider
 * ID (e.g. {@code sendgrid.api-key}). Sensitive values (API keys) are masked as {@code "**secret**"} in
 * GET responses; send the real value on PUT to update it, or omit the key entirely to leave it
 * unchanged.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Transactional email configuration for a realm")
public class TransactionalEmailConfig {

  @Schema(
      description =
          "ID of the active TransactionalEmailProvider (e.g. 'sendgrid'). "
              + "Empty or null disables transactional routing for all email types.",
      example = "sendgrid")
  private String provider;

  @Schema(
      description =
          "Maps Keycloak email-type names to provider-specific template IDs. "
              + "Only mapped types are routed externally; others fall back to FreeMarker + SMTP.",
      example = "{\"password-reset\": \"d-abc123\", \"email-verification\": \"d-def456\"}")
  private Map<String, String> templates;

  @Schema(
      description =
          "Provider-specific configuration keyed as '<providerId>.<key>' "
              + "(e.g. 'sendgrid.api-key'). Sensitive values are masked as '**secret**' in GET responses; "
              + "send the real value on PUT to update, or omit entirely to preserve the stored value.",
      example = "{\"sendgrid.api-key\": \"SG.your-key\"}")
  private Map<String, String> providerConfig;
}
