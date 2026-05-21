# Configuration

All configuration is stored as realm attributes. There are two ways to manage it: directly in the Keycloak Admin UI, or via the REST API.

---

## Realm attribute keys

| Attribute key                                                 | Description                                                                                                                                |
| ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `_providerConfig.ext-email-template.provider`                 | Active provider ID (`sendgrid`, `brevo`, `postmark`, `mailtrap`, `mailgun`, `customerio`, `resend`, `awsses`). Empty or absent = disabled. |
| `_providerConfig.ext-email-template.template.<name>`          | Provider template ID for a given email type (e.g. `template.password-reset`).                                                              |
| `_providerConfig.ext-email-template.sendgrid.api-key`         | SendGrid API key                                                                                                                           |
| `_providerConfig.ext-email-template.sendgrid.api-url`         | SendGrid API URL override (default: `https://api.sendgrid.com/v3/mail/send`)                                                               |
| `_providerConfig.ext-email-template.brevo.api-key`            | Brevo API key                                                                                                                              |
| `_providerConfig.ext-email-template.brevo.api-url`            | Brevo API URL override                                                                                                                     |
| `_providerConfig.ext-email-template.postmark.server-token`    | Postmark server token                                                                                                                      |
| `_providerConfig.ext-email-template.postmark.api-url`         | Postmark API URL override                                                                                                                  |
| `_providerConfig.ext-email-template.mailtrap.api-token`       | Mailtrap API token                                                                                                                         |
| `_providerConfig.ext-email-template.mailtrap.api-url`         | Mailtrap API URL override                                                                                                                  |
| `_providerConfig.ext-email-template.mailgun.api-key`          | Mailgun API key                                                                                                                            |
| `_providerConfig.ext-email-template.mailgun.domain`           | Mailgun sending domain                                                                                                                     |
| `_providerConfig.ext-email-template.mailgun.api-url`          | Mailgun API URL override                                                                                                                   |
| `_providerConfig.ext-email-template.customerio.api-key`       | Customer.io API key                                                                                                                        |
| `_providerConfig.ext-email-template.customerio.api-url`       | Customer.io API URL override                                                                                                               |
| `_providerConfig.ext-email-template.resend.api-key`           | Resend API key                                                                                                                             |
| `_providerConfig.ext-email-template.resend.api-url`           | Resend API URL override                                                                                                                    |
| `_providerConfig.ext-email-template.awsses.access-key-id`     | AWS access key ID                                                                                                                          |
| `_providerConfig.ext-email-template.awsses.secret-access-key` | AWS secret access key                                                                                                                      |
| `_providerConfig.ext-email-template.awsses.region`            | AWS region (e.g. `us-east-1`)                                                                                                              |
| `_providerConfig.ext-email-template.awsses.api-url`           | AWS SES API URL override                                                                                                                   |

### Template names

Substitute `<name>` in `_providerConfig.ext-email-template.template.<name>` with one of the following. Any name not listed here is not intercepted and falls back to Keycloak's standard FreeMarker + SMTP flow.

| `<name>`                                   | Trigger                            |
| ------------------------------------------ | ---------------------------------- |
| `password-reset`                           | Forgot-password flow               |
| `email-verification`                       | Registration / admin verify        |
| `executeActions`                           | Admin-triggered required actions   |
| `email-update-confirmation`                | User changes their email address   |
| `email-verification-with-code`             | OTP-style verification             |
| `identity-provider-link`                   | Account linking to IdP             |
| `org-invite`                               | Organization invitation            |
| `event-login_error`                        | Failed login notification          |
| `event-update_password`                    | Password changed notification      |
| `event-remove_totp`                        | TOTP device removed                |
| `event-update_totp`                        | TOTP device updated                |
| `event-remove_credential`                  | Credential removed                 |
| `event-update_credential`                  | Credential updated                 |
| `event-user_disabled_by_temporary_lockout` | Temporary lockout                  |
| `event-user_disabled_by_permanent_lockout` | Permanent lockout                  |
| `magic-link-email`                         | `keycloak-magic-link` extension    |
| `invitation-email`                         | `keycloak-orgs` extension          |

See [template-variables.md](template-variables.md) for the variables each template receives.

---

## Setting attributes via the Admin UI

You can configure the extension entirely through the Keycloak Admin Console without using the REST API.

1. Open the Admin Console and select your realm.
2. Go to **Realm Settings** in the left navigation.
3. Click the **Attributes** tab.
4. Use **Add attribute** to create each key-value pair.

### Example: configure SendGrid for password reset

Add these three attributes:

| Key                                                          | Value                |
| ------------------------------------------------------------ | -------------------- |
| `_providerConfig.ext-email-template.provider`                | `sendgrid`           |
| `_providerConfig.ext-email-template.sendgrid.api-key`        | `SG.your-api-key`    |
| `_providerConfig.ext-email-template.template.password-reset` | `d-your-template-id` |

Repeat the last row for any additional email types you want to route, using the template names from the [template variables reference](template-variables.md).

To disable the extension without removing individual template mappings, set the provider key to an empty value or delete it.

---

## REST API

Base path: `/realms/{realm}/ext-email-template`

Requires a valid admin Bearer token. `GET` endpoints need `view-realm`; `PUT`/`DELETE` need `manage-realm`.

### `GET /config`

Returns the current configuration. Sensitive values (API keys) are masked as `**secret**`. Returns **404** if no configuration has been set.

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

Saves configuration. Values equal to `**secret**` are silently ignored, so a round-tripped GET response can be PUT back without clearing stored API keys. Returns **400** if `provider` is set to an unrecognised value.

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

Set `provider` to `""` or `null` to disable transactional routing and revert to SMTP.

### `DELETE /config`

Removes all `_providerConfig.ext-email-template.*` attributes from the realm.

```bash
curl -X DELETE "$KC/realms/myrealm/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN"
```

### `GET /templates`

Lists all supported email types and their available template variables.

```bash
curl "$KC/realms/myrealm/ext-email-template/templates" \
  -H "Authorization: Bearer $TOKEN"
```
