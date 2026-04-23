package io.phasetwo.keycloak.email.provider.brevo;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.email.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.email.spi.TransactionalEmailProviderFactory;
import org.keycloak.models.KeycloakSession;

@AutoService(TransactionalEmailProviderFactory.class)
public class BrevoEmailProviderFactory implements TransactionalEmailProviderFactory {

  public static final String PROVIDER_ID = "brevo";

  @Override
  public TransactionalEmailProvider create(KeycloakSession session) {
    return new BrevoEmailProvider(session);
  }

  @Override
  public String getId() {
    return PROVIDER_ID;
  }
}
