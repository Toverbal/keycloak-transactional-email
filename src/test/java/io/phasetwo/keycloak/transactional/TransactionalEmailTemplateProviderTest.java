package io.phasetwo.keycloak.transactional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.transactional.template.TransactionalEmailTemplateProvider;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;

/**
 * Unit tests for {@link TransactionalEmailTemplateProvider}'s locale-aware template routing. No
 * Keycloak container required - a fake {@link TransactionalEmailProvider} records which template ID
 * it was asked to send with.
 */
class TransactionalEmailTemplateProviderTest {

  private static final String PROVIDER_KEY = "_providerConfig.ext-email-template.provider";
  private static final String TEMPLATE_PREFIX = "_providerConfig.ext-email-template.template.";

  /** Records the templateId and vars passed to send(), so tests can assert on routing decisions. */
  private static class RecordingProvider implements TransactionalEmailProvider {
    final AtomicReference<String> lastTemplateId = new AtomicReference<>();
    final AtomicReference<Map<String, Object>> lastVars = new AtomicReference<>();

    @Override
    public void send(
        String templateId,
        Map<String, Object> templateData,
        String toEmail,
        String toName,
        String fromEmail,
        String fromName) {
      lastTemplateId.set(templateId);
      lastVars.set(templateData);
    }

    @Override
    public void close() {}
  }

  private RecordingProvider recordingProvider;
  private MockKeycloakSession mock;

  private TransactionalEmailTemplateProvider buildProvider() {
    recordingProvider = new RecordingProvider();
    mock = new MockKeycloakSession();
    mock.setAttribute(PROVIDER_KEY, "fake");
    mock.registerProvider(TransactionalEmailProvider.class, "fake", recordingProvider);

    KeycloakSession session = mock.asSession();
    TransactionalEmailTemplateProvider templateProvider =
        new TransactionalEmailTemplateProvider(session);
    templateProvider.setRealm(session.getContext().getRealm()).setUser(mock.asUser());
    return templateProvider;
  }

  @Test
  void usesLocaleSpecificTemplate_whenUserHasLocaleAttributeAndMappingExists() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("nl-template"));
  }

  @Test
  void fallsBackToPlainTemplate_whenUserLocaleHasNoMapping() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    // No password-reset.de mapping configured.
    mock.setUserAttribute(UserModel.LOCALE, "de");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("base-template"));
  }

  @Test
  void fallsBackToRealmDefaultLocale_whenUserHasNoLocaleAttribute() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.fr", "fr-template");
    mock.setRealmDefaultLocale("fr");
    // No user LOCALE attribute set at all.

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("fr-template"));
  }

  @Test
  void userLocaleTakesPriorityOverRealmDefaultLocale() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.fr", "fr-template");
    mock.setRealmDefaultLocale("fr");
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("nl-template"));
  }

  @Test
  void localeLookupIsCaseInsensitive() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset", "base-template");
    mock.setAttribute(TEMPLATE_PREFIX + "password-reset.nl", "nl-template");
    mock.setUserAttribute(UserModel.LOCALE, "NL");

    templateProvider.sendPasswordReset("https://example.com/reset", 60);

    assertThat(recordingProvider.lastTemplateId.get(), is("nl-template"));
  }

  @Test
  void noLocaleMappingAndNoBaseMapping_fallsBackToFreeMarker() throws Exception {
    // No template mapping at all for password-reset - super.sendPasswordReset() would be called,
    // which needs a real FreeMarker/theme stack this mock doesn't provide. Assert indirectly: the
    // transactional provider is never invoked.
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setUserAttribute(UserModel.LOCALE, "nl");

    try {
      templateProvider.sendPasswordReset("https://example.com/reset", 60);
    } catch (Exception expected) {
      // Falling through to real FreeMarker rendering fails in this minimal mock (no theme
      // stack) - that's fine, we only care that it didn't route through our fake provider.
    }

    assertThat(recordingProvider.lastTemplateId.get(), is(nullValue()));
  }

  @Test
  void sendEvent_populatesEventDateFormatted() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setUserAttribute(UserModel.LOCALE, "en");

    Event event = new Event();
    event.setType(EventType.LOGIN_ERROR);
    event.setTime(1710504000000L); // 2024-03-15T12:00:00Z
    event.setIpAddress("203.0.113.5");

    templateProvider.sendEvent(event);

    Map<String, Object> vars = recordingProvider.lastVars.get();
    assertThat(vars.get("eventDate"), is(1710504000000L));
    assertThat(vars.get("eventDateFormatted"), instanceOf(String.class));
    String formatted = (String) vars.get("eventDateFormatted");
    assertThat(formatted.isBlank(), is(false));
    // Proves it's a human-readable rendering, not the raw epoch value passed through as-is.
    assertThat(formatted, not(is(String.valueOf(1710504000000L))));
  }

  @Test
  void eventDateFormatted_reflectsRecipientLocale() throws Exception {
    Event event = new Event();
    event.setType(EventType.LOGIN_ERROR);
    event.setTime(1710504000000L);

    TransactionalEmailTemplateProvider enProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setUserAttribute(UserModel.LOCALE, "en");
    enProvider.sendEvent(event);
    String enFormatted = (String) recordingProvider.lastVars.get().get("eventDateFormatted");

    TransactionalEmailTemplateProvider nlProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setUserAttribute(UserModel.LOCALE, "nl");
    nlProvider.sendEvent(event);
    String nlFormatted = (String) recordingProvider.lastVars.get().get("eventDateFormatted");

    assertThat(nlFormatted, not(is(enFormatted)));
  }

  @Test
  void sendExecuteActions_localizesRequiredActionsFromLoginThemeBundle() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions", "execute-actions-template");
    // Mirrors Keycloak's own base login theme messages_en.properties keys/values.
    mock.setLoginThemeMessage("en", "updatePasswordTitle", "Update password");
    mock.setLoginThemeMessage("en", "loginTotpTitle", "Mobile Authenticator Setup");
    templateProvider.setAttribute(
        org.keycloak.models.Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS,
        java.util.List.of("UPDATE_PASSWORD", "CONFIGURE_TOTP"));

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    Map<String, Object> vars = recordingProvider.lastVars.get();
    assertThat(vars.get("requiredActionsText"), is("Update password, Mobile Authenticator Setup"));
  }

  @Test
  void sendExecuteActions_localizesRequiredActionsPerRecipientLocale() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions", "execute-actions-template");
    mock.setLoginThemeMessage("en", "updatePasswordTitle", "Update password");
    mock.setLoginThemeMessage("nl", "updatePasswordTitle", "Wachtwoord bijwerken");
    mock.setUserAttribute(UserModel.LOCALE, "nl");
    templateProvider.setAttribute(
        org.keycloak.models.Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS,
        java.util.List.of("UPDATE_PASSWORD"));

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    Map<String, Object> vars = recordingProvider.lastVars.get();
    assertThat(vars.get("requiredActionsText"), is("Wachtwoord bijwerken"));
  }

  @Test
  void sendExecuteActions_humanizesUnknownRequiredAction() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions", "execute-actions-template");
    templateProvider.setAttribute(
        org.keycloak.models.Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS,
        java.util.List.of("some_custom_action"));

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    Map<String, Object> vars = recordingProvider.lastVars.get();
    assertThat(vars.get("requiredActionsText"), is("Some Custom Action"));
  }

  @Test
  void sendExecuteActions_fallsBackToHumanizedName_whenThemeHasNoMatchingKey() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions", "execute-actions-template");
    // UPDATE_PASSWORD is a known action, but no theme message was configured for it here.
    templateProvider.setAttribute(
        org.keycloak.models.Constants.TEMPLATE_ATTR_REQUIRED_ACTIONS,
        java.util.List.of("UPDATE_PASSWORD"));

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    Map<String, Object> vars = recordingProvider.lastVars.get();
    assertThat(vars.get("requiredActionsText"), is("Update Password"));
  }

  @Test
  void sendExecuteActions_noRequiredActionsAttribute_producesEmptyText() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "executeActions", "execute-actions-template");

    templateProvider.sendExecuteActions("https://example.com/actions", 60);

    Map<String, Object> vars = recordingProvider.lastVars.get();
    assertThat(vars.get("requiredActionsText"), is(""));
  }

  @Test
  void eventDateFormat_dmyPreset() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setAttribute("_providerConfig.ext-email-template.event-date-format", "dmy");

    Event event = new Event();
    event.setType(EventType.LOGIN_ERROR);
    event.setTime(1710504000000L); // 2024-03-15T12:00:00Z

    templateProvider.sendEvent(event);

    String formatted = (String) recordingProvider.lastVars.get().get("eventDateFormatted");
    assertThat(formatted, matchesPattern("\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2}"));
  }

  @Test
  void eventDateFormat_ymdPreset() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setAttribute("_providerConfig.ext-email-template.event-date-format", "YMD"); // case-insensitive

    Event event = new Event();
    event.setType(EventType.LOGIN_ERROR);
    event.setTime(1710504000000L);

    templateProvider.sendEvent(event);

    String formatted = (String) recordingProvider.lastVars.get().get("eventDateFormatted");
    assertThat(formatted, matchesPattern("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));
  }

  @Test
  void eventDateFormat_customPattern() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setAttribute("_providerConfig.ext-email-template.event-date-format", "yyyy/MM/dd");

    Event event = new Event();
    event.setType(EventType.LOGIN_ERROR);
    event.setTime(1710504000000L);

    templateProvider.sendEvent(event);

    String formatted = (String) recordingProvider.lastVars.get().get("eventDateFormatted");
    assertThat(formatted, matchesPattern("\\d{4}/\\d{2}/\\d{2}"));
  }

  @Test
  void eventDateFormat_invalidPattern_fallsBackToLocaleAwareDefault() throws Exception {
    TransactionalEmailTemplateProvider templateProvider = buildProvider();
    mock.setAttribute(TEMPLATE_PREFIX + "event-login_error", "login-error-template");
    mock.setAttribute("_providerConfig.ext-email-template.event-date-format", "not a valid pattern {{{");

    Event event = new Event();
    event.setType(EventType.LOGIN_ERROR);
    event.setTime(1710504000000L);

    templateProvider.sendEvent(event);

    // Doesn't throw, and still produces a non-blank formatted string.
    String formatted = (String) recordingProvider.lastVars.get().get("eventDateFormatted");
    assertThat(formatted.isBlank(), is(false));
  }
}
