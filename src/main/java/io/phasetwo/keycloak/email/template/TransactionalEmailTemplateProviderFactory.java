package io.phasetwo.keycloak.email.template;

import com.google.auto.service.AutoService;
import org.keycloak.Config;
import org.keycloak.email.EmailTemplateProvider;
import org.keycloak.email.EmailTemplateProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Registers {@link TransactionalEmailTemplateProvider} with a higher order than Keycloak's default
 * {@code FreeMarkerEmailTemplateProviderFactory} (order 0), so that it is selected automatically
 * when the extension is on the classpath. The provider falls back to FreeMarker for any email type
 * that has no transactional template mapping configured.
 */
@AutoService(EmailTemplateProviderFactory.class)
public class TransactionalEmailTemplateProviderFactory implements EmailTemplateProviderFactory {

  public static final String PROVIDER_ID = "transactional-email";

  @Override
  public EmailTemplateProvider create(KeycloakSession session) {
    return new TransactionalEmailTemplateProvider(session);
  }

  @Override
  public void init(Config.Scope config) {}

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public void close() {}

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public int order() {
    return 1;
  }
}
