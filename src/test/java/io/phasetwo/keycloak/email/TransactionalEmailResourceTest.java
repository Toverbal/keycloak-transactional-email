package io.phasetwo.keycloak.email;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import io.phasetwo.keycloak.email.representation.TemplateInfo;
import io.phasetwo.keycloak.email.representation.TransactionalEmailConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the {@code /realms/{realm}/ext-email-template} REST
 * resource.
 */
@Disabled("TODO: testcontainer admin-cli auth fails")
class TransactionalEmailResourceTest extends AbstractTransactionalEmailTest {

    @BeforeEach
    void importTestRealm() {
        importRealm("/realms/test-realm.json");
    }

    // ---- GET /config ----

    @Test
    void getConfig_returnsEmptyConfigWhenNothingSet() {
        TransactionalEmailConfig config = given()
                .auth()
                .oauth2(adminToken())
                .when()
                .get(baseUrl("test") + "/config")
                .then()
                .statusCode(200)
                .extract()
                .as(TransactionalEmailConfig.class);

        assertThat(config.getProvider(), is(emptyOrNullString()));
        assertThat(config.getTemplates(), anEmptyMap());
        assertThat(config.getProviderConfig(), anEmptyMap());
    }

    // ---- PUT /config ----

    @Test
    void putConfig_storesProviderAndTemplateMappings() {
        TransactionalEmailConfig toSave = new TransactionalEmailConfig(
                "sendgrid",
                Map.of("password-reset", "d-abc123", "email-verification", "d-def456"),
                Map.of("sendgrid.api-key", "SG.test-key-value"));

        given()
                .auth()
                .oauth2(adminToken())
                .contentType("application/json")
                .body(toSave)
                .when()
                .put(baseUrl("test") + "/config")
                .then()
                .statusCode(204);

        TransactionalEmailConfig loaded = given()
                .auth()
                .oauth2(adminToken())
                .when()
                .get(baseUrl("test") + "/config")
                .then()
                .statusCode(200)
                .extract()
                .as(TransactionalEmailConfig.class);

        assertThat(loaded.getProvider(), is("sendgrid"));
        assertThat(loaded.getTemplates(), hasEntry("password-reset", "d-abc123"));
        assertThat(loaded.getTemplates(), hasEntry("email-verification", "d-def456"));
        // API key must be masked in the response
        assertThat(loaded.getProviderConfig(), hasEntry("sendgrid.api-key", "**secret**"));
    }

    @Test
    void putConfig_secretMaskDoesNotOverwriteStoredKey() {
        // First, store a real key
        TransactionalEmailConfig initial = new TransactionalEmailConfig(
                "sendgrid", Map.of(), Map.of("sendgrid.api-key", "SG.real-key"));
        given()
                .auth()
                .oauth2(adminToken())
                .contentType("application/json")
                .body(initial)
                .when()
                .put(baseUrl("test") + "/config")
                .then()
                .statusCode(204);

        // Now PUT again with the masked value (simulating a round-trip from the UI)
        TransactionalEmailConfig roundTrip = new TransactionalEmailConfig(
                "sendgrid", Map.of("password-reset", "d-new"), Map.of("sendgrid.api-key", "**secret**"));
        given()
                .auth()
                .oauth2(adminToken())
                .contentType("application/json")
                .body(roundTrip)
                .when()
                .put(baseUrl("test") + "/config")
                .then()
                .statusCode(204);

        // The stored key should still be masked (meaning the real key was preserved,
        // not overwritten)
        TransactionalEmailConfig loaded = given()
                .auth()
                .oauth2(adminToken())
                .when()
                .get(baseUrl("test") + "/config")
                .then()
                .statusCode(200)
                .extract()
                .as(TransactionalEmailConfig.class);

        assertThat(loaded.getProviderConfig(), hasEntry("sendgrid.api-key", "**secret**"));
        assertThat(loaded.getTemplates(), hasEntry("password-reset", "d-new"));
    }

    @Test
    void putConfig_clearingProviderDisablesRouting() {
        TransactionalEmailConfig initial = new TransactionalEmailConfig("sendgrid", Map.of(), Map.of());
        given()
                .auth()
                .oauth2(adminToken())
                .contentType("application/json")
                .body(initial)
                .when()
                .put(baseUrl("test") + "/config")
                .then()
                .statusCode(204);

        TransactionalEmailConfig cleared = new TransactionalEmailConfig("", Map.of(), Map.of());
        given()
                .auth()
                .oauth2(adminToken())
                .contentType("application/json")
                .body(cleared)
                .when()
                .put(baseUrl("test") + "/config")
                .then()
                .statusCode(204);

        TransactionalEmailConfig loaded = given()
                .auth()
                .oauth2(adminToken())
                .when()
                .get(baseUrl("test") + "/config")
                .then()
                .statusCode(200)
                .extract()
                .as(TransactionalEmailConfig.class);

        assertThat(loaded.getProvider(), is(emptyOrNullString()));
    }

    // ---- DELETE /config ----

    @Test
    void deleteConfig_removesAllAttributes() {
        TransactionalEmailConfig toSave = new TransactionalEmailConfig(
                "sendgrid",
                Map.of("password-reset", "d-abc123"),
                Map.of("sendgrid.api-key", "SG.key"));
        given()
                .auth()
                .oauth2(adminToken())
                .contentType("application/json")
                .body(toSave)
                .when()
                .put(baseUrl("test") + "/config")
                .then()
                .statusCode(204);

        given()
                .auth()
                .oauth2(adminToken())
                .when()
                .delete(baseUrl("test") + "/config")
                .then()
                .statusCode(204);

        TransactionalEmailConfig loaded = given()
                .auth()
                .oauth2(adminToken())
                .when()
                .get(baseUrl("test") + "/config")
                .then()
                .statusCode(200)
                .extract()
                .as(TransactionalEmailConfig.class);

        assertThat(loaded.getProvider(), is(emptyOrNullString()));
        assertThat(loaded.getTemplates(), anEmptyMap());
        assertThat(loaded.getProviderConfig(), anEmptyMap());
    }

    // ---- GET /templates ----

    @Test
    void getTemplates_returnsAllKnownEmailTypes() {
        List<TemplateInfo> templates = given()
                .auth()
                .oauth2(adminToken())
                .when()
                .get(baseUrl("test") + "/templates")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList(".", TemplateInfo.class);

        assertThat(templates, not(empty()));

        // Spot-check a few required types
        List<String> names = templates.stream().map(TemplateInfo::getName).toList();
        assertThat(names, hasItems("password-reset", "email-verification", "executeActions", "org-invite"));

        // Each template should declare at least one variable
        templates.forEach(t -> assertThat(t.getVariables(), not(empty())));
    }

    @Test
    void getTemplates_passwordResetHasExpectedVariables() {
        List<TemplateInfo> templates = given()
                .auth()
                .oauth2(adminToken())
                .when()
                .get(baseUrl("test") + "/templates")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList(".", TemplateInfo.class);

        TemplateInfo passwordReset = templates.stream()
                .filter(t -> "password-reset".equals(t.getName()))
                .findFirst()
                .orElseThrow();

        assertThat(passwordReset.getVariables(), hasItems("link", "linkExpiration"));
    }

    // ---- auth enforcement ----

    @Test
    void getConfig_rejectsUnauthenticated() {
        given()
                .when()
                .get(baseUrl("test") + "/config")
                .then()
                .statusCode(anyOf(is(401), is(403)));
    }
}
