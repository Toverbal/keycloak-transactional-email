package io.phasetwo.keycloak.email.provider.mailgun;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.email.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.email.spi.TransactionalEmailProviderFactory;
import org.keycloak.models.KeycloakSession;

@AutoService(TransactionalEmailProviderFactory.class)
public class MailgunEmailProviderFactory implements TransactionalEmailProviderFactory {

  public static final String PROVIDER_ID = "mailgun";

  @Override
  public TransactionalEmailProvider create(KeycloakSession session) {
    return new MailgunEmailProvider(session);
  }

  @Override
  public String getId() {
    return PROVIDER_ID;
  }
}
