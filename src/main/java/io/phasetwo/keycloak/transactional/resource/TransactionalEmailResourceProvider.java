package io.phasetwo.keycloak.transactional.resource;

import org.keycloak.models.KeycloakSession;

public class TransactionalEmailResourceProvider extends BaseRealmResourceProvider {

  public TransactionalEmailResourceProvider(KeycloakSession session) {
    super(session);
  }

  @Override
  protected Object getRealmResource() {
    return new TransactionalEmailResource(session);
  }
}
