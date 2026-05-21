package io.phasetwo.keycloak.transactional.provider.brevo;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.transactional.spi.TransactionalEmailProviderFactory;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

@AutoService(TransactionalEmailProviderFactory.class)
public class BrevoEmailProviderFactory implements TransactionalEmailProviderFactory {

  public static final String PROVIDER_ID = "brevo";

  @Override
  public TransactionalEmailProvider create(KeycloakSession session) {
    return new BrevoEmailProvider(session);
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
}
