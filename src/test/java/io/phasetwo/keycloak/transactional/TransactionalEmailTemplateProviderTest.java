package io.phasetwo.keycloak.transactional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.transactional.template.TransactionalEmailTemplateProvider;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
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

  /** Records the templateId passed to send(), so tests can assert on routing decisions. */
  private static class RecordingProvider implements TransactionalEmailProvider {
    final AtomicReference<String> lastTemplateId = new AtomicReference<>();

    @Override
    public void send(
        String templateId,
        Map<String, Object> templateData,
        String toEmail,
        String toName,
        String fromEmail,
        String fromName) {
      lastTemplateId.set(templateId);
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
}
