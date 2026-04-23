# keycloak-transactional-email

A Keycloak extension that routes email sends to external transactional email providers — SendGrid, Brevo, Mailtrap, and others — using those providers' native **dynamic template** systems.

Instead of rendering a FreeMarker template and delivering it over SMTP, this extension passes the raw variable data (links, expiration times, realm name, user details, etc.) directly to the provider's API.

Routing is **per-email-type and per-realm**. Any email type without a configured template ID falls back to Keycloak's standard FreeMarker + SMTP flow, so you can adopt incrementally.

---

## How It Works

```
Keycloak triggers an email (e.g. password reset)
  ↓
TransactionalEmailTemplateProvider intercepts the send
  ↓
Checks realm config: is a provider + template ID configured for this email type?
  ├─ Yes → calls the provider's API with raw template variables
  └─ No  → falls back to FreeMarker + SMTP (standard Keycloak behaviour)
```

The extension is built around a generic `TransactionalEmailProvider` SPI, so new provider implementations can be added as separate modules without modifying core extension code.

---

## Installation

### Build from source

```bash
git clone https://github.com/p2-inc/keycloak-transactional-email
cd keycloak-transactional-email
mvn package -DskipTests
```

### Deploy to Keycloak

Copy the JAR into your Keycloak `providers/` directory and restart:

```bash
cp target/keycloak-transactional-email-*.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build
/opt/keycloak/bin/kc.sh start
```

---

## Configuration

All configuration is stored as realm attributes and managed via the REST API. There is no UI built into the extension itself; see the [Phase Two UI](https://github.com/p2-inc/keycloak-themes) for a visual interface.

### Realm attribute keys

| Attribute                                             | Description                                                                  |
| ----------------------------------------------------- | ---------------------------------------------------------------------------- |
| `_providerConfig.ext-email-template.provider`         | Active provider ID (`sendgrid`, etc.). Empty = disabled.                     |
| `_providerConfig.ext-email-template.template.<name>`  | Provider template ID for a Keycloak email type                               |
| `_providerConfig.ext-email-template.sendgrid.api-key` | SendGrid API key                                                             |
| `_providerConfig.ext-email-template.sendgrid.api-url` | SendGrid API URL override (default: `https://api.sendgrid.com/v3/mail/send`) |

---

## Supported Email Types

| Template name                              | Trigger                          | Additional variables                                                                                        |
| ------------------------------------------ | -------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| `password-reset`                           | Forgot-password flow             | `link`, `linkExpiration`, `linkExpirationFormatted`                                                         |
| `email-verification`                       | Registration / admin verify      | `link`, `linkExpiration`, `linkExpirationFormatted`                                                         |
| `executeActions`                           | Admin-triggered required actions | `link`, `linkExpiration`, `linkExpirationFormatted`, `requiredActionsText`                                  |
| `email-update-confirmation`                | User changes their email address | `link`, `linkExpiration`, `linkExpirationFormatted`, `newEmail`                                             |
| `email-verification-with-code`             | OTP-style verification           | `code`                                                                                                      |
| `identity-provider-link`                   | Account linking to IdP           | `link`, `linkExpiration`, `linkExpirationFormatted`, `identityProviderDisplayName`, `identityProviderAlias` |
| `org-invite`                               | Organization invitation          | `link`, `linkExpiration`, `linkExpirationFormatted`, `organizationName`, `firstName`, `lastName`            |
| `event-login_error`                        | Failed login notification        | `eventDate`, `eventIpAddress`                                                                               |
| `event-update_password`                    | Password changed notification    | `eventDate`, `eventIpAddress`                                                                               |
| `event-remove_totp`                        | TOTP device removed              | `eventDate`, `eventIpAddress`                                                                               |
| `event-update_totp`                        | TOTP device updated              | `eventDate`, `eventIpAddress`                                                                               |
| `event-remove_credential`                  | Credential removed               | `eventDate`, `eventIpAddress`, `credentialType`                                                             |
| `event-update_credential`                  | Credential updated               | `eventDate`, `eventIpAddress`, `credentialType`                                                             |
| `event-user_disabled_by_temporary_lockout` | Temporary lockout                | `eventDate`, `eventIpAddress`                                                                               |
| `event-user_disabled_by_permanent_lockout` | Permanent lockout                | `eventDate`, `eventIpAddress`                                                                               |

Email types not listed here (e.g. `email-test` / SMTP test) are not routed and always use FreeMarker.

### Third-party extension emails

Emails sent by other Phase Two extensions (e.g. [keycloak-magic-link](https://github.com/p2-inc/keycloak-magic-link)'s `magic-link-email`, [keycloak-orgs](https://github.com/p2-inc/keycloak-orgs)' `invitation-email`) are also intercepted. Add a template mapping using the FTL name without the `.ftl` suffix:

```json
{
  "templates": {
    "magic-link-email": "d-your-magic-link-template-id",
    "invitation-email": "d-your-invite-template-id"
  }
}
```

String and numeric values from the extension's template attributes are forwarded as dynamic template data. Complex objects are filtered out.

---

## REST API

Base path: `/realms/{realm}/ext-email-template`

Requires a valid admin Bearer token. GET endpoints need `view-realm`; PUT/DELETE need `manage-realm`.

### `GET /config`

Returns the current configuration. Sensitive values (API keys) are masked as `**secret**`. Returns **404** if no configuration has been set on the realm yet.

```bash
curl "$KC/realms/myrealm/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "provider": "sendgrid",
  "templates": {
    "password-reset": "d-abc123abc123",
    "email-verification": "d-def456def456"
  },
  "providerConfig": {
    "sendgrid.api-key": "**secret**"
  }
}
```

### `PUT /config`

Saves configuration. Values equal to `**secret**` are silently ignored, so a round-tripped GET response can be PUT back without clearing stored API keys. Returns **400** if `provider` is set to a value that doesn't match any registered provider.

```bash
curl -X PUT "$KC/realms/myrealm/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "sendgrid",
    "templates": {
      "password-reset": "d-abc123abc123",
      "email-verification": "d-def456def456",
      "org-invite": "d-ghi789ghi789"
    },
    "providerConfig": {
      "sendgrid.api-key": "SG.your-api-key-here"
    }
  }'
```

Set `provider` to `""` or `null` to disable transactional routing entirely and revert to SMTP.

### `DELETE /config`

Removes all `_providerConfig.ext-email-template.*` attributes from the realm.

```bash
curl -X DELETE "$KC/realms/myrealm/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN"
```

### `GET /templates`

Lists all Keycloak email types this extension supports, with the variables available in each template. Use this as a reference when building templates in your provider's designer.

```bash
curl "$KC/realms/myrealm/ext-email-template/templates" \
  -H "Authorization: Bearer $TOKEN"
```

```json
[
  {
    "name": "password-reset",
    "description": "Password reset email sent from the forgot-password flow",
    "variables": ["link", "linkExpiration", "linkExpirationFormatted"]
  },
  {
    "name": "email-verification",
    "description": "Email address verification during registration or by admin request",
    "variables": ["link", "linkExpiration", "linkExpirationFormatted"]
  },
  ...
]
```

### Universal base variables

Every template also receives these variables regardless of type:

| Variable        | Value                                      |
| --------------- | ------------------------------------------ |
| `realmName`     | Realm display name or capitalised realm ID |
| `userEmail`     | Recipient email address                    |
| `userFirstName` | User first name                            |
| `userLastName`  | User last name                             |
| `username`      | Keycloak username                          |

---

## Providers

| Provider                                                                           | ID           | Dynamic template mechanism                                                                                                   | Config keys                                                                       | Tests | Reviewed |
| ---------------------------------------------------------------------------------- | ------------ | ---------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- | ----- | -------- |
| [SendGrid](https://sendgrid.com/en-us/solutions/email-api/dynamic-email-templates) | `sendgrid`   | [v3 Mail Send API](https://docs.sendgrid.com/api-reference/mail-send/mail-send) with `template_id` + `dynamic_template_data` | `sendgrid.api-key`, `sendgrid.api-url`                                            | ✅    | ✅       |
| [Brevo](https://www.brevo.com/products/transactional-email/) (formerly Sendinblue) | `brevo`      | REST API, numeric `templateId` + `params` JSON                                                                               | `brevo.api-key`, `brevo.api-url`                                                  | ✅    | -        |
| [Postmark](https://postmarkapp.com/transactional-email)                            | `postmark`   | REST API, `TemplateId` (numeric) or `TemplateAlias` (string) + `TemplateModel` JSON                                          | `postmark.server-token`, `postmark.api-url`                                       | ✅    | -        |
| [Mailtrap](https://mailtrap.io/email-sending/)                                     | `mailtrap`   | REST API, `template_uuid` + `template_variables` — also useful as a dev inbox                                                | `mailtrap.api-token`, `mailtrap.api-url`                                          | ✅    | -        |
| [Mailgun](https://www.mailgun.com/products/send/transactional-email/)              | `mailgun`    | REST API (form-encoded), Handlebars template name + `t:variables` JSON                                                       | `mailgun.api-key`, `mailgun.domain`, `mailgun.api-url`                            | ✅    | -        |
| [Customer.io](https://customer.io/transactional-api/)                              | `customerio` | REST API, `transactional_message_id` + `message_data` JSON; set `send_to_unsubscribed: true` for lifecycle emails            | `customerio.api-key`, `customerio.api-url`                                        | ✅    | -        |
| [Resend](https://resend.com)                                                       | `resend`     | REST API; no server-side template rendering — `templateData` must supply pre-rendered `html` and `subject` keys              | `resend.api-key`, `resend.api-url`                                                | ✅    | -        |
| [AWS SES](https://aws.amazon.com/ses/)                                             | `awsses`     | REST API v2, SES Handlebars template name (`TemplateName`) + `TemplateData` JSON; requests signed with AWS Signature V4      | `awsses.access-key-id`, `awsses.secret-access-key`, `awsses.region`, `awsses.api-url` | ✅    | -        |

See [Adding a New Provider](#adding-a-new-provider) below for the two-class pattern to implement any of these.

---

## Adding a New Provider

1. Implement `TransactionalEmailProvider`:

```java
public class BrevoEmailProvider implements TransactionalEmailProvider {
    @Override
    public void send(String templateId, Map<String, Object> templateData,
                     String toEmail, String toName, String fromEmail, String fromName)
            throws Exception {
        // call Brevo API
    }
    @Override public void close() {}
}
```

2. Implement and register the factory:

```java
@AutoService(TransactionalEmailProviderFactory.class)
public class BrevoEmailProviderFactory implements TransactionalEmailProviderFactory {
    @Override public String getId() { return "brevo"; }
    @Override public TransactionalEmailProvider create(KeycloakSession session) {
        return new BrevoEmailProvider(session);
    }
    // ... init/postInit/close stubs
}
```

3. Configure via the API with `"provider": "brevo"` and the appropriate `providerConfig` entries.

No changes to this extension's core code are needed.

---

## Local dev environment

A Docker Compose setup is included so you can run a full Keycloak instance with the extension loaded, explore the admin UI, and try firing real emails.

### Prerequisites

- Docker + Docker Compose
- Maven 3.8+ and Java 21

### Docker

If you prefer to call Maven and Docker directly:

**Build the JAR**

```bash
mvn package -DskipTests
```

**Start all services**

```bash
docker compose up --force-recreate
# or detached:
docker compose up --force-recreate -d
```

**Tail Keycloak logs**

```bash
docker compose logs -f keycloak
```

**Stop containers**

```bash
docker compose down
```

**Stop and remove volumes (full reset)**

```bash
docker compose down -v
```

**Rebuild after code changes**

```bash
mvn package -DskipTests && docker compose up --force-recreate -d
```

**Pin a specific image version**

```bash
KEYCLOAK_IMAGE_TAG=26.0.5 docker compose up --force-recreate -d
```

### Use makefile

```bash
make dev        # build JAR, start Keycloak + Mailhog in the foreground
# — or —
make start      # same but detached (background), prints service URLs
```

| Service                | URL                         | Credentials   |
| ---------------------- | --------------------------- | ------------- |
| Keycloak admin UI      | http://localhost:8080/admin | admin / admin |
| Mailhog (catches SMTP) | http://localhost:8025       | —             |

### Try it end to end

**1. Configure a realm's email settings**

In the admin UI, open a realm → **Realm Settings → Email**. Set the "From" address to anything (e.g. `test@example.com`). Keycloak's SMTP is pre-wired to Mailhog, so any email that falls back to the standard FreeMarker path will appear at http://localhost:8025.

**2. Configure the transactional provider via REST**

Get an admin token, then call the extension's config endpoint. The example below routes the password-reset email to a SendGrid dynamic template and points the API at a local mock so no real credentials are needed:

```bash
# Obtain an admin token
TOKEN=$(curl -s -X POST \
  "http://localhost:8080/realms/master/protocol/openid-connect/token" \
  -d "client_id=admin-cli&grant_type=password&username=admin&password=admin" \
  | python3 -m json.tool | grep access_token | cut -d'"' -f4)

# See what template types are available and what variables each one exposes
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

In the admin UI, go to **Users**, open a user that has an email address set, and click **Send email → Reset password**.

- If `password-reset` has a template ID mapped → the extension calls the SendGrid API with the raw template variables (`link`, `linkExpiration`, `realmName`, etc.)
- If no template is mapped for that type → Keycloak falls back to FreeMarker + SMTP, and the email appears in Mailhog at http://localhost:8025

**4. Test with a mock API endpoint instead of real SendGrid**

You can override the SendGrid API URL to point at any HTTP server you control — useful for inspecting the exact payload without a real SendGrid account:

```bash
# Using a free public request inspector (replace with your bin URL)
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

The full JSON payload (including `template_id` and `dynamic_template_data`) will be posted to that URL when you trigger the email.

**5. Remove configuration**

```bash
curl -s -X DELETE \
  "http://localhost:8080/realms/master/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN"
```

After deletion all email types fall back to FreeMarker + SMTP (Mailhog).

### Pinning the Keycloak image version

The compose file pins to a specific version by default. To override it:

```bash
KEYCLOAK_IMAGE_TAG=26.5.7 make start
```

### Other make targets

```bash
make restart    # stop → rebuild JAR → start fresh (use after code changes)
make stop       # stop containers, keep volumes
make logs       # tail Keycloak output
make clean      # stop + remove volumes + mvn clean
make package    # build JAR only (no containers)
```

---

## Development

```bash
# Compile only
mvn compile

# Unit tests (no Docker required)
mvn test

# Full integration tests (requires Docker)
mvn verify
```

Integration tests start a `quay.io/phasetwo/keycloak-crdb` container via Testcontainers and exercise the REST API end-to-end. The `SendGridEmailProviderTest` unit test uses an in-process JDK `HttpServer` mock and runs without any containers.

---

## OpenAPI spec

A pre-generated spec is committed at [`docs/openapi.yaml`](docs/openapi.yaml) and [`docs/openapi.json`](docs/openapi.json). It is regenerated automatically on every `mvn compile` via the `swagger-maven-plugin-jakarta` and copied into `docs/` by the build.

To regenerate manually:

```bash
mvn compile
```

The updated files will be written to `docs/openapi.yaml` and `docs/openapi.json`.

### What the spec covers

The spec describes the four endpoints under `/realms/{realm}/ext-email-template`:

| Method   | Path         | Description                                    |
| -------- | ------------ | ---------------------------------------------- |
| `GET`    | `/config`    | Retrieve current configuration                 |
| `PUT`    | `/config`    | Save configuration                             |
| `DELETE` | `/config`    | Remove all configuration                       |
| `GET`    | `/templates` | List supported email types and their variables |

Full request/response schemas for `TransactionalEmailConfig` and `TemplateInfo` are included, with field-level descriptions and examples.

### Customising the spec info block

The title, description, contact, license, and server URL template are defined in [`src/main/openapi/openapi-base.yaml`](src/main/openapi/openapi-base.yaml). Edit that file and rerun `mvn compile` to update the generated output.

---

## Compatibility

| Keycloak | Extension version |
| -------- | ----------------- |
| 26.5.x   | 0.1.x             |

---

## License

[Elastic License v2](https://www.elastic.co/licensing/elastic-license)

Built by [Phase Two](https://phasetwo.io).
