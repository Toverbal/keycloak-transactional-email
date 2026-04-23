package io.phasetwo.keycloak.email;

import java.util.HashMap;
import java.util.Map;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.keycloak.connections.httpclient.HttpClientProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * Minimal {@link KeycloakSession} stub for unit tests that exercise provider logic without
 * requiring a full Keycloak container.
 *
 * <p>Uses JDK dynamic proxies so only the methods actually called during a send are implemented;
 * all others throw {@link UnsupportedOperationException}.
 *
 * <p>Supports {@code session.getProvider(HttpClientProvider.class)} so that {@code SimpleHttp}
 * can make real HTTP calls to the in-process mock server.
 */
class MockKeycloakSession {

  private final Map<String, String> realmAttributes = new HashMap<>();
  private final CloseableHttpClient httpClient = HttpClients.createDefault();

  void setAttribute(String key, String value) {
    realmAttributes.put(key, value);
  }

  KeycloakSession asSession() {
    RealmModel realm = mockRealm();
    KeycloakContext context = mockContext(realm);
    HttpClientProvider httpClientProvider = mockHttpClientProvider();
    return proxy(
        KeycloakSession.class,
        (proxy, method, args) -> {
          if ("getContext".equals(method.getName())) return context;
          if ("getProvider".equals(method.getName()) && args != null && args.length >= 1) {
            if (HttpClientProvider.class.equals(args[0])) return httpClientProvider;
          }
          throw new UnsupportedOperationException("MockKeycloakSession: " + method.getName());
        });
  }

  private RealmModel mockRealm() {
    return proxy(
        RealmModel.class,
        (proxy, method, args) -> {
          if ("getAttribute".equals(method.getName()) && args != null && args.length >= 1) {
            return realmAttributes.get((String) args[0]);
          }
          throw new UnsupportedOperationException("MockRealm: " + method.getName());
        });
  }

  private KeycloakContext mockContext(RealmModel realm) {
    return proxy(
        KeycloakContext.class,
        (proxy, method, args) -> {
          if ("getRealm".equals(method.getName())) return realm;
          throw new UnsupportedOperationException("MockContext: " + method.getName());
        });
  }

  private HttpClientProvider mockHttpClientProvider() {
    return proxy(
        HttpClientProvider.class,
        (proxy, method, args) -> {
          if ("getHttpClient".equals(method.getName())) return httpClient;
          if ("getMaxConsumedResponseSize".equals(method.getName())) return Long.MAX_VALUE;
          throw new UnsupportedOperationException("MockHttpClientProvider: " + method.getName());
        });
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
    return (T)
        Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, handler);
  }
}
