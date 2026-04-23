# Local Development

A Docker Compose setup is included so you can run a full Keycloak instance with the extension loaded and try firing real emails.

## Prerequisites

- Docker + Docker Compose
- Maven 3.8+ and Java 21

## Makefile targets

```bash
make dev        # build JAR, start Keycloak + Mailhog in the foreground
make start      # same but detached (background), prints service URLs
make restart    # stop → rebuild JAR → start fresh (use after code changes)
make stop       # stop containers, keep volumes
make logs       # tail Keycloak output
make clean      # stop + remove volumes + mvn clean
make package    # build JAR only (no containers)
```

| Service                | URL                         | Credentials   |
| ---------------------- | --------------------------- | ------------- |
| Keycloak admin UI      | http://localhost:8080/admin | admin / admin |
| Mailhog (catches SMTP) | http://localhost:8025       | —             |

## Docker directly

```bash
# Build the JAR
mvn package -DskipTests

# Start all services
docker compose up --force-recreate

# Tail Keycloak logs
docker compose logs -f keycloak

# Stop containers
docker compose down

# Stop and remove volumes (full reset)
docker compose down -v

# Rebuild after code changes
mvn package -DskipTests && docker compose up --force-recreate -d

# Pin a specific Keycloak image version
KEYCLOAK_IMAGE_TAG=26.0.5 docker compose up --force-recreate -d
```

## End-to-end walkthrough

**1. Configure email settings**

In the admin UI, open a realm → **Realm Settings → Email**. Set the "From" address (e.g. `test@example.com`). The SMTP server is pre-wired to Mailhog, so any email that falls back to the standard FreeMarker path will appear at http://localhost:8025.

**2. Configure the transactional provider**

```bash
# Obtain an admin token
TOKEN=$(curl -s -X POST \
  "http://localhost:8080/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=admin" \
  | python3 -m json.tool | grep access_token | cut -d'"' -f4)

# See available email types and their variables
curl -s "http://localhost:8080/realms/master/ext-email-template/templates" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Write configuration
curl -s -X PUT \
  "http://localhost:8080/realms/master/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "sendgrid",
    "templates": {
      "password-reset": "d-your-sendgrid-template-id"
    },
    "providerConfig": {
      "sendgrid.api-key": "SG.your-api-key"
    }
  }'

# Read it back (API key will be masked as **secret**)
curl -s "http://localhost:8080/realms/master/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

**3. Trigger an email**

In the admin UI, go to **Users**, open a user with an email address set, and click **Send email → Reset password**.

- If `password-reset` has a template ID mapped → the extension calls the provider API with the raw template variables.
- If no template is mapped → Keycloak falls back to FreeMarker + SMTP, and the email appears in Mailhog.

**4. Test with a mock API endpoint**

You can override the provider API URL to point at any HTTP server you control — useful for inspecting the exact payload without real credentials:

```bash
curl -s -X PUT \
  "http://localhost:8080/realms/master/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "sendgrid",
    "templates": { "password-reset": "d-test" },
    "providerConfig": {
      "sendgrid.api-key": "SG.fake-key",
      "sendgrid.api-url": "https://your-request-bin.example.com/sendgrid"
    }
  }'
```

**5. Remove configuration**

```bash
curl -s -X DELETE \
  "http://localhost:8080/realms/master/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN"
```

After deletion all email types fall back to FreeMarker + SMTP (Mailhog).

## Running tests

```bash
# Compile only
mvn compile

# Unit tests (no Docker required)
mvn test

# Full integration tests (requires Docker)
mvn verify
```

Integration tests start a `quay.io/phasetwo/keycloak-crdb` container via Testcontainers and exercise the REST API end-to-end. The `SendGridEmailProviderTest` unit test uses an in-process JDK `HttpServer` mock and runs without any containers.

## OpenAPI spec

A pre-generated spec is committed at [`openapi.yaml`](openapi.yaml) and [`openapi.json`](openapi.json). It is regenerated automatically on every `mvn compile` and copied into `docs/` by the build.

To regenerate manually:

```bash
mvn compile
```

The spec covers the four endpoints under `/realms/{realm}/ext-email-template`. The title, description, and server URL template are defined in [`src/main/openapi/openapi-base.yaml`](../src/main/openapi/openapi-base.yaml).
