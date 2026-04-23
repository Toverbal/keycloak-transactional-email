package io.phasetwo.keycloak.email.resource;

import io.phasetwo.keycloak.email.representation.TemplateInfo;
import io.phasetwo.keycloak.email.representation.TransactionalEmailConfig;
import io.phasetwo.keycloak.email.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.email.template.KnownEmailTemplate;
import io.phasetwo.keycloak.email.template.TransactionalEmailTemplateProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;

/**
 * REST resource for managing transactional email configuration per realm.
 *
 * <p>All endpoints require realm {@code manage-realm} permission except {@code GET /templates}
 * which requires {@code view-realm}.
 *
 * <p>Base path: {@code /realms/{realm}/ext-email-template}
 */
@Tag(name = "Transactional Email", description = "Configure per-realm transactional email provider routing")
@Path("/")
@JBossLog
public class TransactionalEmailResource extends AbstractAdminResource {

  static final String SECRET_MASK = "**secret**";

  /**
   * Realm attribute keys that contain sensitive values and should be masked in GET responses. The
   * key suffix (after the provider prefix) is what appears in {@code providerConfig}.
   */
  private static final List<String> SENSITIVE_SUFFIXES = List.of("api-key", "api-token", "secret");

  public TransactionalEmailResource(KeycloakSession session) {
    super(session);
  }

  /**
   * Returns the current transactional email configuration for the realm.
   *
   * <p>Sensitive values in {@code providerConfig} (API keys, tokens) are masked as {@value
   * #SECRET_MASK}.
   */
  @Operation(
      summary = "Get transactional email configuration",
      description = "Returns the current provider, template mappings, and provider-specific config. Sensitive values (API keys) are masked as \"**secret**\".")
  @ApiResponse(responseCode = "200", description = "Current configuration",
      content = @Content(schema = @Schema(implementation = TransactionalEmailConfig.class)))
  @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token")
  @ApiResponse(responseCode = "403", description = "Insufficient permissions (requires view-realm)")
  @ApiResponse(responseCode = "404", description = "No transactional email configuration exists for this realm")
  @GET
  @Path("config")
  @Produces(MediaType.APPLICATION_JSON)
  public TransactionalEmailConfig getConfig() {
    setup();
    permissions.realm().requireViewRealm();

    String provider = realm.getAttribute(TransactionalEmailTemplateProvider.PROVIDER_KEY);

    Map<String, String> templates = new HashMap<>();
    Map<String, String> providerConfig = new HashMap<>();

    realm
        .getAttributes()
        .forEach(
            (key, value) -> {
              if (key.startsWith(TransactionalEmailTemplateProvider.TEMPLATE_KEY_PREFIX)) {
                String templateName =
                    key.substring(TransactionalEmailTemplateProvider.TEMPLATE_KEY_PREFIX.length());
                templates.put(templateName, value);
              } else if (key.startsWith(
                  TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".") && !key.equals(TransactionalEmailTemplateProvider.PROVIDER_KEY)) {
                String configKey =
                    key.substring(
                        (TransactionalEmailTemplateProvider.CONFIG_PREFIX + ".").length());
                // skip template.* entries — already collected above
                if (!configKey.startsWith("template.")) {
                  String masked = isSensitive(configKey) ? SECRET_MASK : value;
                  providerConfig.put(configKey, masked);
                }
              }
            });

    return new TransactionalEmailConfig(provider, templates, providerConfig);
  }

  /**
   * Saves transactional email configuration for the realm.
   *
   * <p>To clear the active provider (disabling transactional routing entirely), set {@code
   * provider} to null or an empty string.
   *
   * <p>Sensitive {@code providerConfig} values equal to {@value #SECRET_MASK} are ignored so that
   * round-tripping a GET response does not overwrite the stored secret.
   */
  @Operation(
      summary = "Save transactional email configuration",
      description = "Stores provider, template mappings, and provider config as realm attributes. "
          + "providerConfig values equal to \"**secret**\" are ignored to allow safe round-tripping. "
          + "Set provider to empty string to disable transactional routing.")
  @ApiResponse(responseCode = "204", description = "Configuration saved")
  @ApiResponse(responseCode = "400", description = "Invalid request body")
  @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token")
  @ApiResponse(responseCode = "403", description = "Insufficient permissions (requires manage-realm)")
  @PUT
  @Path("config")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response putConfig(@NotNull TransactionalEmailConfig config) {
    setup();
    permissions.realm().requireManageRealm();

    if (config.getProvider() != null && !config.getProvider().isBlank()) {
      if (session.getProvider(TransactionalEmailProvider.class, config.getProvider()) == null) {
        throw new BadRequestException("Unknown provider: " + config.getProvider());
      }
    }

    realm.setAttribute(
        TransactionalEmailTemplateProvider.PROVIDER_KEY,
        config.getProvider() != null ? config.getProvider() : "");

    if (config.getTemplates() != null) {
      config
          .getTemplates()
          .forEach(
              (name, templateId) ->
                  realm.setAttribute(
                      TransactionalEmailTemplateProvider.TEMPLATE_KEY_PREFIX + name, templateId));
    }

    if (config.getProviderConfig() != null) {
      config
          .getProviderConfig()
          .forEach(
              (key, value) -> {
                if (!SECRET_MASK.equals(value)) {
                  realm.setAttribute(
                      TransactionalEmailTemplateProvider.CONFIG_PREFIX + "." + key, value);
                }
              });
    }

    return Response.noContent().build();
  }

  /**
   * Removes all transactional email configuration attributes from the realm, effectively disabling
   * transactional routing.
   */
  @Operation(
      summary = "Delete transactional email configuration",
      description = "Removes all _providerConfig.ext-email-template.* realm attributes, disabling transactional routing for all email types.")
  @ApiResponse(responseCode = "204", description = "Configuration deleted")
  @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token")
  @ApiResponse(responseCode = "403", description = "Insufficient permissions (requires manage-realm)")
  @DELETE
  @Path("config")
  public Response deleteConfig() {
    setup();
    permissions.realm().requireManageRealm();

    realm
        .getAttributes()
        .keySet()
        .stream()
        .filter(k -> k.startsWith(TransactionalEmailTemplateProvider.CONFIG_PREFIX))
        .toList()
        .forEach(realm::removeAttribute);

    return Response.noContent().build();
  }

  /**
   * Returns metadata about all known Keycloak email types and the template variables each one
   * provides. Use this to understand what data is available when designing dynamic templates.
   */
  @Operation(
      summary = "List supported email types and their template variables",
      description = "Returns all Keycloak email types this extension supports, with the dynamic template variables available for each. "
          + "Use this as a reference when designing templates in your provider's editor. "
          + "All types also receive the universal base variables: realmName, userEmail, userFirstName, userLastName, username.")
  @ApiResponse(responseCode = "200", description = "List of template metadata",
      content = @Content(array = @ArraySchema(schema = @Schema(implementation = TemplateInfo.class))))
  @ApiResponse(responseCode = "401", description = "Missing or invalid Bearer token")
  @ApiResponse(responseCode = "403", description = "Insufficient permissions (requires view-realm)")
  @GET
  @Path("templates")
  @Produces(MediaType.APPLICATION_JSON)
  public List<TemplateInfo> getTemplates() {
    setup();
    permissions.realm().requireViewRealm();
    return KnownEmailTemplate.allTemplateInfos();
  }

  private boolean isSensitive(String configKey) {
    return SENSITIVE_SUFFIXES.stream().anyMatch(configKey::endsWith);
  }
}
