package io.phasetwo.keycloak.email.provider.mailtrap;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.email.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.email.spi.TransactionalEmailProviderFactory;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

@AutoService(TransactionalEmailProviderFactory.class)
public class MailtrapEmailProviderFactory implements TransactionalEmailProviderFactory {

  public static final String PROVIDER_ID = "mailtrap";

  @Override
  public TransactionalEmailProvider create(KeycloakSession session) {
    return new MailtrapEmailProvider(session);
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
