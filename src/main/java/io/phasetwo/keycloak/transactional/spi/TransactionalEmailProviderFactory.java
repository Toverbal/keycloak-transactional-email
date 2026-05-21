package io.phasetwo.keycloak.transactional.spi;

import org.keycloak.provider.ProviderFactory;

/** Factory interface for {@link TransactionalEmailProvider} implementations. */
public interface TransactionalEmailProviderFactory
    extends ProviderFactory<TransactionalEmailProvider> {}
