package io.phasetwo.keycloak.transactional.spi;

import com.google.auto.service.AutoService;
import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

/**
 * Registers the {@code transactional-email} SPI with Keycloak so that {@link
 * TransactionalEmailProvider} implementations can be discovered and selected by provider ID.
 */
@AutoService(Spi.class)
public class TransactionalEmailSpi implements Spi {

  public static final String SPI_NAME = "transactional-email";

  @Override
  public boolean isInternal() {
    return false;
  }

  @Override
  public String getName() {
    return SPI_NAME;
  }

  @Override
  public Class<? extends Provider> getProviderClass() {
    return TransactionalEmailProvider.class;
  }

  @Override
  @SuppressWarnings("rawtypes")
  public Class<? extends ProviderFactory> getProviderFactoryClass() {
    return TransactionalEmailProviderFactory.class;
  }
}
