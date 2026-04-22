package io.phasetwo.keycloak.email;

import java.util.HashMap;
import java.util.Map;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Minimal {@link KeycloakSession} stub for unit tests that exercise SendGrid provider logic
 * without requiring a full Keycloak container.
 *
 * <p>Uses a JDK dynamic proxy so that only the methods actually called during a send are
 * implemented; all others throw {@link UnsupportedOperationException}.
 */
class MockKeycloakSession {

  private final Map<String, String> realmAttributes = new HashMap<>();

  void setAttribute(String key, String value) {
    realmAttributes.put(key, value);
  }

  KeycloakSession asSession() {
    RealmModel realm = mockRealm();
    KeycloakContext context = mockContext(realm);
    return proxy(
        KeycloakSession.class,
        (proxy, method, args) -> {
          if ("getContext".equals(method.getName())) return context;
          throw new UnsupportedOperationException("MockKeycloakSession: " + method.getName());
        });
  }

  // Make MockKeycloakSession directly usable as a KeycloakSession via implicit conversion
  // for the SendGridEmailProvider constructor.
  KeycloakSession session() {
    return asSession();
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

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> iface, InvocationHandler handler) {
    return (T)
        Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[] {iface}, handler);
  }
}
