package io.phasetwo.keycloak.email.template;

import io.phasetwo.keycloak.email.representation.TemplateInfo;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.Getter;

/**
 * Canonical list of Keycloak email types, their FTL template names (without {@code .ftl}), and the
 * template variables available for each. This is used both to populate the {@code /templates}
 * discovery endpoint and to build the variable map passed to external providers.
 *
 * <p>All templates also receive {@code realmName}, {@code userEmail}, {@code userFirstName}, {@code
 * userLastName}, and {@code username} from the base variable set.
 */
@Getter
public enum KnownEmailTemplate {
  PASSWORD_RESET(
      "password-reset",
      "Password reset email sent from the forgot-password flow",
      "link",
      "linkExpiration",
      "linkExpirationFormatted"),

  EMAIL_VERIFICATION(
      "email-verification",
      "Email address verification during registration or by admin request",
      "link",
      "linkExpiration",
      "linkExpirationFormatted"),

  EXECUTE_ACTIONS(
      "executeActions",
      "Required-actions email (update password, verify email, etc.) sent by admin",
      "link",
      "linkExpiration",
      "linkExpirationFormatted",
      "requiredActionsText"),

  EMAIL_UPDATE_CONFIRMATION(
      "email-update-confirmation",
      "Confirmation sent to a new email address when a user requests an email change",
      "link",
      "linkExpiration",
      "linkExpirationFormatted",
      "newEmail"),

  EMAIL_VERIFICATION_WITH_CODE(
      "email-verification-with-code",
      "Email verification using a short numeric code instead of a link",
      "code"),

  IDENTITY_PROVIDER_LINK(
      "identity-provider-link",
      "Confirmation to link an existing account with an identity provider",
      "link",
      "linkExpiration",
      "linkExpirationFormatted",
      "identityProviderDisplayName",
      "identityProviderAlias"),

  ORG_INVITE(
      "org-invite",
      "Invitation to join an organization",
      "link",
      "linkExpiration",
      "linkExpirationFormatted",
      "organizationName",
      "firstName",
      "lastName"),

  EVENT_LOGIN_ERROR(
      "event-login_error", "Notification of a failed login attempt", "eventDate", "eventIpAddress"),

  EVENT_REMOVE_CREDENTIAL(
      "event-remove_credential",
      "Notification that a credential was removed",
      "eventDate",
      "eventIpAddress",
      "credentialType"),

  EVENT_REMOVE_TOTP(
      "event-remove_totp",
      "Notification that a TOTP device was removed",
      "eventDate",
      "eventIpAddress"),

  EVENT_UPDATE_CREDENTIAL(
      "event-update_credential",
      "Notification that a credential was updated",
      "eventDate",
      "eventIpAddress",
      "credentialType"),

  EVENT_UPDATE_PASSWORD(
      "event-update_password",
      "Notification that the account password was changed",
      "eventDate",
      "eventIpAddress"),

  EVENT_UPDATE_TOTP(
      "event-update_totp",
      "Notification that a TOTP device was added or updated",
      "eventDate",
      "eventIpAddress"),

  EVENT_USER_DISABLED_TEMPORARY(
      "event-user_disabled_by_temporary_lockout",
      "Notification of a temporary account lockout",
      "eventDate",
      "eventIpAddress"),

  EVENT_USER_DISABLED_PERMANENT(
      "event-user_disabled_by_permanent_lockout",
      "Notification of a permanent account lockout",
      "eventDate",
      "eventIpAddress");

  private final String templateName;
  private final String description;
  private final List<String> variables;

  KnownEmailTemplate(String templateName, String description, String... variables) {
    this.templateName = templateName;
    this.description = description;
    this.variables = Arrays.asList(variables);
  }

  public TemplateInfo toTemplateInfo() {
    return new TemplateInfo(templateName, description, variables);
  }

  public static Optional<KnownEmailTemplate> forName(String name) {
    return Arrays.stream(values()).filter(t -> t.templateName.equals(name)).findFirst();
  }

  public static List<TemplateInfo> allTemplateInfos() {
    return Arrays.stream(values()).map(KnownEmailTemplate::toTemplateInfo).toList();
  }
}
