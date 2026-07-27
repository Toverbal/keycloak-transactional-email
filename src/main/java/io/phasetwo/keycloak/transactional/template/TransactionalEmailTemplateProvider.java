package io.phasetwo.keycloak.transactional.template;

import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.email.EmailException;
import org.keycloak.email.freemarker.FreeMarkerEmailTemplateProvider;
import org.keycloak.events.Event;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.UserModel;

/**
 * Extends {@link FreeMarkerEmailTemplateProvider} to intercept email sends and route them to a
 * configured {@link TransactionalEmailProvider} when a matching template mapping exists in the
 * realm configuration.
 *
 * <p>Per-template routing is controlled by realm attributes:
 *
 * <ul>
 *   <li>{@code _providerConfig.ext-email-template.provider} — the provider ID to use (e.g. {@code
 *       "sendgrid"})
 *   <li>{@code _providerConfig.ext-email-template.template.<name>} — the provider-specific template
 *       ID for a given Keycloak email type
 *   <li>{@code _providerConfig.ext-email-template.template.<name>.<locale>} — an optional
 *       locale-specific override, tried before the locale-less key above (see {@link
 *       #getTemplateId(String)})
 * </ul>
 *
 * <p>If no provider is configured, or no template mapping exists for the current email type, the
 * call falls back to FreeMarker + SMTP.
 */
@JBossLog
public class TransactionalEmailTemplateProvider extends FreeMarkerEmailTemplateProvider {

  public static final String CONFIG_PREFIX = "_providerConfig.ext-email-template";
  public static final String PROVIDER_KEY = CONFIG_PREFIX + ".provider";
  public static final String TEMPLATE_KEY_PREFIX = CONFIG_PREFIX + ".template.";

  public TransactionalEmailTemplateProvider(KeycloakSession session) {
    super(session);
  }

  @Override
  public void sendPasswordReset(String link, long expirationInMinutes) throws EmailException {
    Optional<String> templateId = getTemplateId("password-reset");
    if (templateId.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("link", link);
      vars.put("linkExpiration", expirationInMinutes);
      vars.put("linkExpirationFormatted", formatExpiration(expirationInMinutes));
      sendViaProvider(templateId.get(), vars, null);
    } else {
      super.sendPasswordReset(link, expirationInMinutes);
    }
  }

  @Override
  public void sendVerifyEmail(String link, long expirationInMinutes) throws EmailException {
    Optional<String> templateId = getTemplateId("email-verification");
    if (templateId.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("link", link);
      vars.put("linkExpiration", expirationInMinutes);
      vars.put("linkExpirationFormatted", formatExpiration(expirationInMinutes));
      sendViaProvider(templateId.get(), vars, null);
    } else {
      super.sendVerifyEmail(link, expirationInMinutes);
    }
  }

  @Override
  public void sendExecuteActions(String link, long expirationInMinutes) throws EmailException {
    Optional<String> templateId = getTemplateId("executeActions");
    if (templateId.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("link", link);
      vars.put("linkExpiration", expirationInMinutes);
      vars.put("linkExpirationFormatted", formatExpiration(expirationInMinutes));
      sendViaProvider(templateId.get(), vars, null);
    } else {
      super.sendExecuteActions(link, expirationInMinutes);
    }
  }

  @Override
  public void sendEmailUpdateConfirmation(String link, long expirationInMinutes, String newEmail)
      throws EmailException {
    Optional<String> templateId = getTemplateId("email-update-confirmation");
    if (templateId.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("link", link);
      vars.put("linkExpiration", expirationInMinutes);
      vars.put("linkExpirationFormatted", formatExpiration(expirationInMinutes));
      vars.put("newEmail", newEmail);
      // Send to the new (unconfirmed) address, matching FreeMarker behaviour
      sendViaProvider(templateId.get(), vars, newEmail);
    } else {
      super.sendEmailUpdateConfirmation(link, expirationInMinutes, newEmail);
    }
  }

  @Override
  public void sendConfirmIdentityBrokerLink(String link, long expirationInMinutes)
      throws EmailException {
    Optional<String> templateId = getTemplateId("identity-provider-link");
    if (templateId.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("link", link);
      vars.put("linkExpiration", expirationInMinutes);
      vars.put("linkExpirationFormatted", formatExpiration(expirationInMinutes));

      Object brokerCtx = attributes.get(IDENTITY_PROVIDER_BROKER_CONTEXT);
      if (brokerCtx instanceof org.keycloak.broker.provider.BrokeredIdentityContext ctx) {
        String displayName = ctx.getIdpConfig().getDisplayName();
        if (displayName == null || displayName.isBlank()) {
          displayName =
              org.keycloak.common.util.ObjectUtil.capitalize(ctx.getIdpConfig().getAlias());
        }
        vars.put("identityProviderDisplayName", displayName);
        vars.put("identityProviderAlias", ctx.getIdpConfig().getAlias());
      }

      sendViaProvider(templateId.get(), vars, null);
    } else {
      super.sendConfirmIdentityBrokerLink(link, expirationInMinutes);
    }
  }

  @Override
  public void sendOrgInviteEmail(
      OrganizationModel organization, String link, long expirationInMinutes) throws EmailException {
    Optional<String> templateId = getTemplateId("org-invite");
    if (templateId.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("link", link);
      vars.put("linkExpiration", expirationInMinutes);
      vars.put("linkExpirationFormatted", formatExpiration(expirationInMinutes));
      vars.put("organizationName", organization.getName());
      if (user.getFirstName() != null) vars.put("firstName", user.getFirstName());
      if (user.getLastName() != null) vars.put("lastName", user.getLastName());
      sendViaProvider(templateId.get(), vars, null);
    } else {
      super.sendOrgInviteEmail(organization, link, expirationInMinutes);
    }
  }

  @Override
  public void sendEvent(Event event) throws EmailException {
    String ftlName = "event-" + event.getType().toString().toLowerCase();
    Optional<String> templateId = getTemplateId(ftlName);
    if (templateId.isPresent()) {
      Map<String, Object> vars = baseVariables();
      vars.put("eventDate", event.getTime());
      vars.put("eventIpAddress", event.getIpAddress());
      if (event.getDetails() != null) {
        vars.put("credentialType", event.getDetails().get("credential_type"));
        vars.putAll(event.getDetails());
      }
      sendViaProvider(templateId.get(), vars, null);
    } else {
      super.sendEvent(event);
    }
  }

  @Override
  public void send(
      String subjectKey,
      List<Object> subjectAttributes,
      String template,
      Map<String, Object> bodyAttributes)
      throws EmailException {
    // Strip .ftl suffix if present — template names in realm config are stored without it
    String templateName = template.endsWith(".ftl") ? template.substring(0, template.length() - 4) : template;
    Optional<String> templateId = getTemplateId(templateName);
    if (templateId.isPresent()) {
      Map<String, Object> vars = baseVariables();
      if (bodyAttributes != null) {
        bodyAttributes.forEach((k, v) -> {
          if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            vars.put(k, v);
          }
        });
      }
      sendViaProvider(templateId.get(), vars, null);
    } else {
      super.send(subjectKey, subjectAttributes, template, bodyAttributes);
    }
  }

  // sendSmtpTestEmail is intentionally not overridden — it tests the SMTP connection directly

  // ---- helpers ----

  /**
   * Resolves the template ID for {@code templateName}, preferring a locale-specific mapping over
   * the plain one. Locale suffixes are tried in order:
   *
   * <ol>
   *   <li>the recipient's own stored profile locale ({@link UserModel#LOCALE} attribute - the same
   *       one set by the account console's language switcher)
   *   <li>the realm's configured default locale
   * </ol>
   *
   * for keys of the form {@code <TEMPLATE_KEY_PREFIX><templateName>.<locale>}, before finally
   * falling back to the locale-less {@code <TEMPLATE_KEY_PREFIX><templateName>} key. A missing or
   * unmapped locale simply falls through to the next candidate, ending at the same FreeMarker
   * fallback as before if nothing matches.
   *
   * <p>Deliberately does NOT use {@link org.keycloak.locale.LocaleSelectorProvider}: it also
   * factors in the CURRENT HTTP request's locale cookie / Accept-Language header / auth session,
   * which for admin-triggered sends (e.g. "Send verification email" in the admin console, or the
   * {@code execute-actions-email} admin REST endpoint) reflects whoever is performing the action,
   * not the recipient the email is actually going to - the opposite of what per-recipient template
   * routing needs. Reading the recipient's own stored attribute directly is what stays correct
   * regardless of who/what triggered the send.
   */
  private Optional<String> getTemplateId(String templateName) {
    if (realm == null) return Optional.empty();
    String provider = realm.getAttribute(PROVIDER_KEY);
    if (provider == null || provider.isBlank()) {
      log.debugf("No transactional provider configured for %s; falling back to FreeMarker", templateName);
      return Optional.empty();
    }

    for (String locale : candidateLocales()) {
      String localized = realm.getAttribute(TEMPLATE_KEY_PREFIX + templateName + "." + locale);
      if (localized != null && !localized.isBlank()) {
        log.debugf("Using locale '%s' template mapping for %s", locale, templateName);
        return Optional.of(localized);
      }
    }

    String templateId = realm.getAttribute(TEMPLATE_KEY_PREFIX + templateName);
    if (templateId == null || templateId.isBlank()) {
      log.debugf(
          "No template mapping for %s under provider '%s'; falling back to FreeMarker",
          templateName, provider);
      return Optional.empty();
    }
    return Optional.of(templateId);
  }

  private List<String> candidateLocales() {
    List<String> locales = new ArrayList<>();
    if (user != null) {
      String userLocale = normalizeLocale(user.getFirstAttribute(UserModel.LOCALE));
      if (userLocale != null) locales.add(userLocale);
    }
    if (realm != null) {
      String realmDefault = normalizeLocale(realm.getDefaultLocale());
      if (realmDefault != null && !locales.contains(realmDefault)) locales.add(realmDefault);
    }
    return locales;
  }

  private static String normalizeLocale(String locale) {
    if (locale == null || locale.isBlank()) return null;
    return locale.trim().toLowerCase(java.util.Locale.ROOT);
  }

  private TransactionalEmailProvider resolveProvider() {
    String providerId = realm.getAttribute(PROVIDER_KEY);
    return session.getProvider(TransactionalEmailProvider.class, providerId);
  }

  private Map<String, Object> baseVariables() {
    Map<String, Object> vars = new HashMap<>();
    vars.put("realmName", getRealmName());
    if (user != null) {
      vars.put("userEmail", user.getEmail());
      vars.put("userFirstName", user.getFirstName());
      vars.put("userLastName", user.getLastName());
      vars.put("username", user.getUsername());
    }
    return vars;
  }

  private static String formatExpiration(long minutes) {
    if (minutes % 60 == 0) {
      long hours = minutes / 60;
      return hours + " " + (hours == 1 ? "hour" : "hours");
    }
    return minutes + " " + (minutes == 1 ? "minute" : "minutes");
  }

  /**
   * Resolves the active provider and dispatches the send call. Falls back to FreeMarker if the
   * provider is not available at runtime (e.g. factory JAR not installed).
   */
  private void sendViaProvider(String templateId, Map<String, Object> vars, String overrideToEmail)
      throws EmailException {
    TransactionalEmailProvider provider = resolveProvider();
    if (provider == null) {
      log.warnf(
          "TransactionalEmailProvider '%s' not found; falling back to FreeMarker",
          realm.getAttribute(PROVIDER_KEY));
      return;
    }

    String toEmail = overrideToEmail != null ? overrideToEmail : user.getEmail();
    String toName =
        Stream.of(user.getFirstName(), user.getLastName())
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.joining(" "));

    Map<String, String> smtpConfig = realm.getSmtpConfig();
    String fromEmail = smtpConfig.getOrDefault("from", "");
    String fromName = smtpConfig.getOrDefault("fromDisplayName", "");

    log.infof(
        "Sending email via transactional provider '%s' (templateId=%s, to=%s)",
        realm.getAttribute(PROVIDER_KEY), templateId, toEmail);

    try {
      provider.send(templateId, vars, toEmail, toName, fromEmail, fromName);
    } catch (Exception e) {
      throw new EmailException("Transactional email provider failed to send", e);
    }
  }
}
