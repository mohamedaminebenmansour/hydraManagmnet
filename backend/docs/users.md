# Comptes utilisateurs — reference (U1)

## Password policy

`GET /api/auth/password-policy` (public) → `{minLength, maxLength, rules}`.

- **Min length**: `hydra.password.min-length` (env `HYDRA_PASSWORD_MIN_LENGTH`, default 12).
- **Max length**: 128.
- **No composition rules** (no required uppercase/digit/symbol).
- **Common-password blocklist**: the 10k most common passwords from SecLists
  (`security/common-passwords.txt`, MIT licence). Case-insensitive.
- **Forbidden words** (case-insensitive, checked as substrings): hydra, sigat,
  ngnet, chantier, tunisie, password, motdepasse, azerty, qwerty, the user's
  email local part, any word of the user's full name of 4+ letters.
- **Different from the old password** (when known).

`PasswordPolicy.validate(user, candidate)` returns a list of French messages
(empty = OK). Used in every place that sets a password:
`changeMyPassword`, `createUser`, `resetPassword`, `updateUser`. Error =
`BusinessRuleException("WEAK_PASSWORD", first message)`.

## Temporary password

When `createUser` or `resetPassword` is called with a blank password,
`TemporaryPasswordGenerator` generates a 12-char password from
`ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789` (no 0 O 1 l I),
with at least one digit, never in the blocklist. It is returned ONCE in the
response (`temporaryPassword` field) and never stored or logged in clear.
`must_change_password` is set to `true`.

## Forced change at first login

`PasswordChangeRequiredFilter` (after authentication, before controllers):
if the user is authenticated and `must_change_password = true` and the path
is NOT one of `/api/auth/me`, `/api/auth/change-password`, `/api/auth/logout`,
`/api/auth/password-policy` → 403 JSON
`{"code":"PASSWORD_CHANGE_REQUIRED","message":"Vous devez changer votre mot de passe."}`.

The flag is stored in the HTTP session at login (key
`HYDRA_MUST_CHANGE_PASSWORD`) so the filter does not hit the database on every
request. It is updated at change-password and at admin reset (a reset revokes
sessions anyway).

## Login

`POST /api/auth/login` → on success: `last_login_at = now()`,
`first_login_at = coalesce(first_login_at, now())`, session flag set.

`MeResponse` adds: `phone`, `jobTitle`, `lastLoginAt`, `passwordChangedAt`.

## Endpoints

### Auth
- `POST /api/auth/login` `{email, password}` → `MeResponse`.
- `GET /api/auth/me` → `MeResponse`.
- `GET /api/auth/password-policy` (public) → `{minLength, maxLength, rules}`.
- `GET /api/auth/mail-status` (public) → `{enabled}` — whether SMTP is configured.
- `POST /api/auth/forgot-password` `{email}` (public) → always 200
  `{"message":"Si un compte existe pour cette adresse, un lien vient d'y être envoyé."}`
  Rate-limited: 3/h per email, 10/h per IP. Sends a reset link only when the user
  exists, is active, and mail is enabled. Fixed ~400 ms timing to prevent enumeration.
- `GET /api/auth/reset-password/check?token=...` (public) → `{valid, purpose, firstName}`.
- `POST /api/auth/reset-password` `{token, newPassword}` (public) → 200 on success,
  400 `TOKEN_INVALID` / `WEAK_PASSWORD` on failure. No automatic login.
- `POST /api/auth/change-password` `{oldPassword, newPassword}`.
- `POST /api/auth/logout`.

### Mail (optional)
- SMTP is used when `HYDRA_MAIL_HOST` AND `HYDRA_MAIL_FROM` are both set.
- Otherwise `MailServiceDisabled`: `isEnabled()=false`, `send()` logs one INFO line.
- Emails are sent AFTER the transaction commits (`sendAfterCommit`); a send failure
  is logged at WARN and never breaks the user action.
- 3 templates: `reset.html`, `invite.html`, `changed.html` (French, plain HTML, no images).
- Links use `hydra.app-url` (env `HYDRA_APP_URL`), NEVER the request `Host` header.

### Tokens
- `password_token` table (V52): `token_hash` = SHA-256 hex of the raw token. The raw
  token is NEVER stored — it appears only in the email link.
- RESET tokens: valid 30 minutes. INVITE tokens: valid 72 hours.
- Creating a token marks the user's previous unused tokens of the same purpose as used.
- `consume`: validates, applies `PasswordPolicy`, sets password
  (`must_change_password=false`), revokes ALL sessions, audits
  `PASSWORD_RESET_BY_TOKEN` or `ACCOUNT_ACTIVATED`. Sends the « changed » email.

### Users (admin)
- `GET /api/users` → `UserResponse[]` (one SQL query + one for projects).
- `GET /api/users/options` → active users for pickers.
- `POST /api/users` `{fullName, email, password?, roleCodes, overrideReason?, sendInvitation?}` →
  `UserResponse` (with `temporaryPassword` when password was blank;
  `invitationSent=true` when `sendInvitation=true` and mail enabled).
- `PUT /api/users/{id}` `{fullName, password?}`.
- `PUT /api/users/{id}/roles` `{roleCodes, overrideReason?}`.
- `POST /api/users/{id}/deactivate` / `/reactivate` / `/activate`.
- `POST /api/users/{id}/reset-password` `{newPassword?}` →
  `{temporaryPassword}` when password was blank. Sends the « changed » email.
- `POST /api/users/{id}/send-reset-link` (U2) — admin-triggered RESET email.
  400 `MAIL_DISABLED` if mail is not configured.
- `POST /api/users/{id}/resend-invitation` (U2) — resend the INVITE email for a
  user still in PENDING_FIRST_LOGIN. 400 `NOT_PENDING` if already activated.
- `PUT /api/users/{id}/absence` `{absentUntil}`.

### My profile (any authenticated user)
- `GET /api/me/profile` → `{fullName, email, phone, jobTitle, lastLoginAt,
  passwordChangedAt, roles:[{code,label}], projects:[{id,name,projectRole}],
  absentUntil}`.
- `PUT /api/me/profile` `{fullName, phone, jobTitle}` — fullName 2..120,
  phone digits/spaces/+ 8..20, jobTitle ≤ 80. Email cannot be changed.
  Audits before → after.
- `PUT /api/me/absence` `{absentUntil}` — same rules as the admin endpoint.
- `POST /api/me/sessions/revoke-others` — revokes all my sessions except the
  current one.

## UserResponse

```json
{"id":"...","email":"...","fullName":"...","isOwner":false,"isActive":true,
 "mustChangePassword":false,"phone":"...","jobTitle":"...",
 "lastLoginAt":"...","passwordChangedAt":"...",
 "status":"ACTIVE","projectCount":2,"projectNames":["Projet A","Projet B"],
 "roleCodes":["BUREAU"],"roleLabels":["Bureau"],
 "temporaryPassword":null}
```

`temporaryPassword` is set ONLY in the `createUser` and `resetPassword`
responses, and only when the caller did not provide a password. It is never
stored or logged in clear.

## Statuses (computed, priority order)

| Code | Meaning |
|------|---------|
| INACTIVE | `is_active = false` |
| PENDING | `must_change_password = true` (never logged in / admin reset) |
| OWNER | `is_owner = true` (the gérant; always shown last) |
| NO_PROJECT | active, no password change, not owner, no project membership |
| ACTIVE | active, no password change, not owner, has a project membership |

Computed in `UserService.userStatus(...)` — one method, used everywhere.

## Schema

`app_user` (V1 + V3 + V13 + V44 + V51):
- V1: id, org_id, email, password_hash, full_name, is_owner, is_active, created_at.
- V3: must_change_password.
- V13: (project roles).
- V44: absent_until.
- V51: phone, job_title, last_login_at, password_changed_at, first_login_at.
