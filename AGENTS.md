# keycloak-transactional-email — Agent Context

## What This Extension Does

Routes Keycloak email sends to external transactional email providers (SendGrid, Brevo, Mailtrap, etc.)
that support provider-managed **dynamic templates**. Instead of rendering a FreeMarker template into
HTML and sending it over SMTP, the extension passes the raw variable data (link, linkExpiration,
realmName, etc.) directly to the provider's template engine via API.

Routing is per-email-type and per-realm. Any email type without a configured template ID falls back
to Keycloak's standard FreeMarker + SMTP flow automatically.

## Architecture Overview

### Three-layer design

```
EmailTemplateProvider SPI (Keycloak)
  └─ TransactionalEmailTemplateProvider  [overrides FreeMarkerEmailTemplateProvider, order=1]
        │  checks realm attributes for provider + template ID
        │  if found → calls TransactionalEmailProvider
        │  if not   → super.send*() delegates to FreeMarker + SMTP
        └─ TransactionalEmailProvider SPI (custom)
              └─ SendGridEmailProvider    [provider id: "sendgrid"]
                    calls SendGrid v3 /mail/send API
```

### Key classes

| Class | Role |
|---|---|
| `TransactionalEmailTemplateProviderFactory` | Registered with Keycloak's `EmailTemplateSpi`, `order=1` beats the default FreeMarker provider |
| `TransactionalEmailTemplateProvider` | Extends `FreeMarkerEmailTemplateProvider`; overrides each `send*()` method |
| `TransactionalEmailSpi` | Registers the custom `transactional-email` SPI so Keycloak can discover provider factories |
| `SendGridEmailProviderFactory` | `@AutoService(TransactionalEmailProviderFactory.class)`, id=`"sendgrid"` |
| `SendGridEmailProvider` | HTTP call to SendGrid v3 using `java.net.http.HttpClient` (no extra deps needed) |
| `TransactionalEmailResourceProviderFactory` | REST resource factory, id=`"ext-email-template"` |
| `TransactionalEmailResource` | JAX-RS resource at `/realms/{realm}/ext-email-template` |
| `KnownEmailTemplate` | Enum of all Keycloak email types + their available variables; drives the `/templates` endpoint |

### Adding a new provider

1. Create `YourProvider implements TransactionalEmailProvider` — implement `send()`
2. Create `YourProviderFactory implements TransactionalEmailProviderFactory`
   - `@AutoService(TransactionalEmailProviderFactory.class)`
   - `getId()` returns your provider ID (e.g. `"brevo"`)
3. Put the provider-specific config key constants in your provider class following the naming
   convention: `_providerConfig.ext-email-template.<providerId>.<key>`

No changes to the core `TransactionalEmailTemplateProvider` or the REST resource are needed.

## Realm Configuration

All configuration is stored as realm attributes under the `_providerConfig.ext-email-template.*`
namespace (consistent with Phase Two extension conventions).

```
_providerConfig.ext-email-template.provider
    → active provider ID, e.g. "sendgrid"
    → empty/absent = disabled, fall back to FreeMarker for all email types

_providerConfig.ext-email-template.template.<name>
    → provider-specific template ID for the named Keycloak email type
    → e.g. _providerConfig.ext-email-template.template.password-reset = "d-abc123"
    → absent = this email type falls back to FreeMarker

_providerConfig.ext-email-template.sendgrid.api-key
    → SendGrid API key (sensitive; masked as "**secret**" in GET responses)

_providerConfig.ext-email-template.sendgrid.api-url
    → optional override for the API endpoint; defaults to https://api.sendgrid.com/v3/mail/send
    → useful in tests to point at a mock server
```

## REST API

Base path: `/realms/{realm}/ext-email-template`

All endpoints require a valid Bearer token. GET requires `view-realm`; PUT/DELETE require `manage-realm`.

| Method | Path | Description |
|---|---|---|
| `GET` | `/config` | Return current config; sensitive values masked |
| `PUT` | `/config` | Save config; values equal to `**secret**` are not overwritten |
| `DELETE` | `/config` | Remove all `_providerConfig.ext-email-template.*` attributes |
| `GET` | `/templates` | List all known Keycloak email types with their available variables |

## Template Variables

Every template receives base variables automatically:
`realmName`, `userEmail`, `userFirstName`, `userLastName`, `username`

Per-type additional variables are listed in `KnownEmailTemplate` enum and returned by `GET /templates`.

Notable special cases:
- `email-update-confirmation` sends to the **new** (unverified) email address, not `user.getEmail()`
- `sendSmtpTestEmail` is intentionally **not** overridden — it tests SMTP connectivity directly
- `email-verification-with-code` provides a `code` variable instead of a `link`
- Event templates (`event-*`) receive `eventDate`, `eventIpAddress`, plus all event detail key/values
- All templates with a `link` also receive `linkExpirationFormatted` — a pre-formatted human-readable
  string (e.g. `"30 minutes"`, `"2 hours"`) derived from the raw `linkExpiration` minutes value.
  Use `{{linkExpirationFormatted}}` in templates rather than formatting the raw number yourself.

### SendGrid template syntax

SendGrid **dynamic** transactional templates require Handlebars double-brace syntax:
```
{{realmName}}  {{link}}  {{linkExpirationFormatted}}
```
Single-brace `{variable}` is the legacy substitution syntax and will not be replaced in dynamic
templates. Also note variable names must match exactly — `{{realmName}}` not `{{realm}}`.

## Phase Two Conventions Used

- `@AutoService` annotation for zero-config SPI discovery (no manual META-INF/services)
- `@JBossLog` for logging (Lombok)
- `_providerConfig.ext-email-template.*` realm attribute namespace
- `AbstractAdminResource` + `BaseRealmResourceProvider` + `CorsResource` pattern (copied from
  keycloak-magic-link) for admin REST resources
- `quay.io/phasetwo/keycloak-crdb:{version}` container image in integration tests
- `testcontainers-keycloak` + REST-Assured for integration test harness

## Build

```bash
mvn verify          # compile + unit tests (no container needed for SendGridEmailProviderTest)
mvn verify -Pit     # integration tests require Docker
```

The integration tests (`TransactionalEmailResourceTest`) start a real Keycloak container. The unit
test (`SendGridEmailProviderTest`) uses an in-process JDK `HttpServer` mock — no container required.

## Testing in a Standalone Container

Until this extension is included in the Phase Two base image, deploy it manually:

```bash
mvn package -DskipTests
cp target/keycloak-transactional-email-*.jar /path/to/keycloak/providers/
# restart Keycloak
```

Then configure via the REST API:
```bash
TOKEN=$(curl -s -X POST "$KC/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=admin" \
  | jq -r .access_token)

# Set provider + SendGrid API key + template mappings
curl -X PUT "$KC/realms/myrealm/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "sendgrid",
    "templates": {
      "password-reset": "d-your-template-id",
      "email-verification": "d-another-template-id"
    },
    "providerConfig": {
      "sendgrid.api-key": "SG.your-api-key"
    }
  }'

# Inspect what variables are available for each template type
curl "$KC/realms/myrealm/ext-email-template/templates" \
  -H "Authorization: Bearer $TOKEN"
```

## Future UI

The UI for this extension lives in [phasetwo-keycloak-admin-ui](../phasetwo-keycloak-admin-ui)
in `src/admin/realm-settings/EmailTab.tsx`. The extension itself deliberately ships no UI — see
other Phase Two extensions (keycloak-magic-link, keycloak-orgs) for the same pattern.

## Sibling Projects

- `keycloak` — Phase Two's Keycloak fork; email SPI interfaces are in `server-spi-private`
- `bridge-extensions` — other Phase Two extensions; keycloak-magic-link and keycloak-events have
  the most relevant patterns for this project
- `phasetwo-keycloak-admin-ui` — Keycloakify-based admin UI theme where the EmailTab UI lives
