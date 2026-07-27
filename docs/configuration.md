# Configuration

All configuration is stored as realm attributes, managed via this extension's REST API (see [Setting attributes](#setting-attributes) below).

---

## Realm attribute keys

| Attribute key                                                 | Description                                                                                                                                |
| ------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `_providerConfig.ext-email-template.provider`                 | Active provider ID (`sendgrid`, `brevo`, `postmark`, `mailtrap`, `mailgun`, `customerio`, `resend`, `awsses`). Empty or absent = disabled. |
| `_providerConfig.ext-email-template.template.<name>`          | Provider template ID for a given email type (e.g. `template.password-reset`).                                                              |
| `_providerConfig.ext-email-template.event-date-format`        | Format for `eventDateFormatted` on event-notification emails (see [Event date formatting](#event-date-formatting)). Unset = locale-aware default. |
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

## Locale-specific templates

Each email type can have per-locale template overrides, tried before the locale-less mapping:

```
_providerConfig.ext-email-template.template.<name>.<locale>
```

For example, alongside a default `template.password-reset`, you can add:

```
_providerConfig.ext-email-template.template.password-reset.nl = d-nl-template-id
_providerConfig.ext-email-template.template.password-reset.fr = d-fr-template-id
```

Locale resolution, most specific first:

1. The recipient's own stored profile locale (`UserModel.LOCALE` attribute - the same one set by the account console's language switcher).
2. The realm's configured default locale.
3. The locale-less `template.<name>` key, if neither of the above has a mapping.
4. Standard FreeMarker + SMTP, if even that is absent.

Locale matching is case-insensitive (`nl` and `NL` are equivalent).

This is intentionally **not** resolved via Keycloak's `LocaleSelectorProvider` (used for login-page/theme locale selection), because that also factors in the *current HTTP request's* cookie, `Accept-Language` header, and active authentication session. For sends triggered by the recipient's own browser (e.g. self-service forgot-password) that happens to line up with the recipient, but for admin-triggered sends (e.g. "Send verification email" in the Admin Console, or the `execute-actions-email` admin REST endpoint) it would reflect the *admin's* locale, not the recipient's - the opposite of what per-recipient routing needs. Reading the recipient's own stored attribute directly stays correct regardless of who or what triggered the send.

---

## Event date formatting

Event-notification emails (`event-login_error`, `event-update_password`, etc. - see [template-variables.md](template-variables.md#event-notification-emails)) receive `eventDate` as a raw Unix millisecond timestamp, which isn't fit to put directly in a template. `eventDateFormatted` provides a human-readable rendering instead, controlled by the `_providerConfig.ext-email-template.event-date-format` realm attribute:

| Value                  | Result                                                    |
| ----------------------- | ---------------------------------------------------------- |
| unset, blank, or `auto` | Locale-aware default (`DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)`), using the same recipient-locale resolution as template routing above |
| `dmy`                   | `dd-MM-yyyy HH:mm` (e.g. `27-07-2026 15:49`)                |
| `mdy`                   | `MM/dd/yyyy hh:mm a` (e.g. `07/27/2026 03:49 PM`)           |
| `ymd`                   | `yyyy-MM-dd HH:mm` (e.g. `2026-07-27 15:49`)                |
| anything else           | Used directly as a [`DateTimeFormatter` pattern](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/format/DateTimeFormatter.html) - fully custom formats are supported, not just the three presets above (e.g. `EEEE d MMMM yyyy`) |

An invalid custom pattern falls back to the locale-aware default rather than failing the send (logged as a warning). Formatted in the server's local timezone - Keycloak does not store a per-user timezone.

---

## Setting attributes

Keycloak's Admin Console has no generic UI for editing arbitrary realm-level attributes like these ([keycloak/keycloak#17732](https://github.com/keycloak/keycloak/issues/17732)). Use this extension's own REST resource instead (see [REST API](#rest-api) below), or the underlying Keycloak Admin REST API directly (`PUT /admin/realms/{realm}` with an `attributes` map).

Don't confuse this with **Realm Settings → User profile → Attributes**, which manages the *user profile schema* (`username`, `email`, `locale`, etc.) - that's an unrelated feature and won't show or edit any `_providerConfig.ext-email-template.*` values.

### Example: configure SendGrid for password reset

```bash
curl -X PUT "$KC/realms/myrealm/ext-email-template/config" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "sendgrid",
    "templates": { "password-reset": "d-your-template-id" },
    "providerConfig": { "sendgrid.api-key": "SG.your-api-key" }
  }'
```

Repeat the `templates` entry for any additional email types you want to route, using the template names from the [template variables reference](template-variables.md).

To disable the extension without removing individual template mappings, set `provider` to an empty value or delete it.

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
