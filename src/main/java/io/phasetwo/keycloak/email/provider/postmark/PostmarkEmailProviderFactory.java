package io.phasetwo.keycloak.email.provider.postmark;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.email.spi.TransactionalEmailProvider;
import io.phasetwo.keycloak.email.spi.TransactionalEmailProviderFactory;
import org.keycloak.models.KeycloakSession;

@AutoService(TransactionalEmailProviderFactory.class)
public class PostmarkEmailProviderFactory implements TransactionalEmailProviderFactory {

  public static final String PROVIDER_ID = "postmark";

  @Override
  public TransactionalEmailProvider create(KeycloakSession session) {
    return new PostmarkEmailProvider(session);
  }

  @Override
  public String getId() {
    return PROVIDER_ID;
  }
}
