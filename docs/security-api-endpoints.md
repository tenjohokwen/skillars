# Security API Endpoints

The REST endpoints owned by the `platform.security` module: authentication and sessions, role
registration and verification, account self-service, and player profiles. Every path below is
checked against the controllers and filters in `src/main/java/com/softropic/skillars/platform/security`
(`api/*Resource.java`, `config/SecurityConfiguration.java`, `config/AppEndpoints.java`).

Admin-only user management (suspend, reactivate, unlock login) lives in the `admin` module under
`/api/admin/users/**` and is not repeated here.

## Conventions

- **Cookies, not headers.** The session is carried in HttpOnly cookies (`potc` JWT, `rtkn` refresh
  token, plus the readable `skp` profile cookie `{"id":"<userId>","role":"COACH"}`). The SPA's axios
  instance sends them with `withCredentials: true`.
- **Public vs. secured.** Public paths are listed in `AppEndpoints.PUBLIC_ENDPOINTS`. Everything else
  under `/api/**` and `/v1/**` requires an authenticated session, and every controller method also
  carries a `@PreAuthorize`.
- **Errors** are an `ErrorDto` produced by `ApiAdvice`; the stable key is in `errorMsg.message`
  (for example `security.badCreds`, `security.accLocked`, `security.accNotEnabled`).
- **Rate limits.** `@RateLimited` (per IP, token bucket) guards registration and password-reset
  calls; failed password logins are limited separately (see [Login rate limit](#login-rate-limit)).

---

## 1. Authentication and session (SPA)

These are the endpoints the Vue SPA uses (`src/frontend/src/api/auth.api.js`, `boot/axios.js`).

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/auth/login` | public | Email + password login. Sets `potc`, `rtkn`, `skp`. |
| POST | `/api/auth/refresh` | public (needs `rtkn`) | Rotates the refresh token and mints a new JWT. |
| POST | `/api/auth/logout` | public | Revokes this session's refresh token, clears all auth cookies. `204`. |
| POST | `/refresh` | any authenticated role | Extends the TTL of an already-valid JWT session. Empty `200`. |

### `POST /api/auth/login`

Request (`LoginRequest`):

```json
{ "email": "coach@example.com", "password": "at-least-8-chars" }
```

Response `200` (`LoginResponse`):

```json
{ "userId": "7310000000000001", "role": "COACH", "displayName": "Sam" }
```

`role` is the account's `SkillarsRole`, or `ADMIN` for accounts without one.

| Status | Key | When |
|--------|-----|------|
| 401 | `security.badCreds` | Unknown email or wrong password (recorded as a failed attempt) |
| 401 | `security.accNotEnabled` | Account not activated, or GDPR-erased |
| 401 | `security.accLocked` | Account locked by an admin, or suspended (`verification_status = SUSPENDED`) |
| 403 | `security.accountNotVerified` | A role account that is not `BASIC_VERIFIED` while `security.registration.phone-otp-required` is true |
| 429 | `security.accountLocked` | Login rate limit reached; `Retry-After` header carries the seconds to wait |

### `POST /api/auth/refresh`

Reads the `rtkn` cookie. On success the presented token is marked used, a new one is issued, and the
response is the same `LoginResponse` as login. A token that was already used outside the 30-second
multi-tab grace window is treated as theft: every refresh token of the account is revoked, and every
standing JWT is invalidated through `user.security_session_invalidated_at`. A locked, deactivated or
suspended account is refused with `401` and the presented token is revoked.

### `POST /api/auth/logout`

Revokes the refresh token in the `rtkn` cookie (a `REQUIRES_NEW` write, so it is durable) and clears
the auth cookies. Returns `204`.

### Login rate limit

`LoginRateLimiter` counts failed password attempts in `main.login_attempts`, keyed by
SHA-256(lower-cased login | client IP), so it holds across every instance and survives restarts.
The budget and window come from `platform_config`: `security.login.max-attempts` (default 5) and
`security.login.lock-window-minutes` (default 15). The legacy `/authenticate` endpoint below uses the
same limiter and the same key, so failures on either entry point count against both. The legacy
path also refuses an account that has reached the budget across all IPs combined (the SPA path does
not, so a stranger cannot lock a real user out of it). An admin clears both with
`POST /api/admin/users/{id}/unlock-login`.

---

## 2. Legacy endpoints (not used by the SPA)

Kept because integration tests and tooling still authenticate through them. New code should use
section 1.

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/authenticate` | public | Spring filter (`JWTAuthenticationFilter`). Body `{"id": "<email>", "password": "..."}` (optional `loginCode`). Returns `JWT_CREATED`, or `check.otp` with a `loginInfoId` when the account has `otp_enabled`. Uses the shared login rate limit. |
| POST | `/otp` | 2FA cookie from `/authenticate` | Completes the `check.otp` step (`SecondFactorLoginFilter`). Body `{"loginInfoId": "...", "otp": "123456"}`. |
| POST | `/api/logout` | public | Spring Security `LogoutFilter`, POST only. Same server-side teardown as `/api/auth/logout` (revokes the refresh token, clears cookies), returns `200`. |
| GET | `/v1/account/authenticate` | authenticated | Returns the remote user's login. |

There is no self-service two-factor toggle: `otp_enabled` only affects the legacy `/authenticate`
flow, which the SPA does not use, so the former `PUT /api/account/2fa` endpoint and its profile
toggle were removed.

---

## 3. Role registration and verification

Coach, parent and player self-registration share one shape. Replace `{role}` with `coach`,
`parent` or `player`. All are public.

| Method | Path | Body / params | Response |
|--------|------|---------------|----------|
| POST | `/api/security/{role}/register` | role registration request (below) | `200`, empty |
| GET | `/api/security/{role}/verify-email` | `?token=<uuid>` from the email link | `200` `VerifyEmailResponse` |
| POST | `/api/security/{role}/verify-phone` | `{"verificationToken": "...", "otp": "123456"}` | `200`, empty |
| POST | `/api/security/{role}/resend-verification` | `{"email": "..."}` | `200`, empty |
| POST | `/api/security/{role}/resend-otp` | `{"verificationToken": "..."}` | `200`, empty |

Registration request fields:

| Field | Coach | Parent | Player |
|-------|-------|--------|--------|
| `firstName`, `lastName` | required, max 100 | required, max 50 | required, max 100 |
| `email` | required, valid email | required | required |
| `password` | required, min 8 | required, min 8 | required, min 8 |
| `phone` | required, 7-20 chars | required | required, 7-20 chars |
| `dateOfBirth` | n/a | n/a | required, past date (`yyyy-MM-dd`) |
| `langKey` | optional, 2-5 chars | optional | optional |

The account becomes `EMAIL_VERIFIED` after the email link and `BASIC_VERIFIED` after the phone OTP.

### Generic account registration and password reset (`/v1/account`)

| Method | Path | Auth | Notes |
|--------|------|------|-------|
| POST | `/v1/account/register` | public | Generic (role-less) account, body `UserDto`. Rate limited 5/hour/IP. |
| POST | `/v1/account/regislink` | public | Resend the activation link. Params `login`, `password`. Rate limited. |
| POST | `/v1/account/activate` | public | `?key=<activation key>`. |
| POST | `/v1/account/reset_password/init` | public | Body `{"loginId", "dob", "currentEmail"}`. `202`. Rate limited 3/15 min. |
| POST | `/v1/account/reset_password/finish` | public | Body `{"key", "password"}`. Rate limited 5/10 min. |
| GET | `/v1/account/` | authenticated | Current user (`UserDto`). |
| GET | `/v1/account/ping` | authenticated | `{"server": "up"}`. |

---

## 4. Account self-service (`/api/account`)

Every method requires an authenticated session (any role).

| Method | Path | Body | Success key |
|--------|------|------|-------------|
| GET | `/api/account/profile` | - | `UserDto` |
| PUT | `/api/account/email` | `{"oldEmail", "newEmail", "password"}` | `email.updated` |
| PUT | `/api/account/password` | `{"currentPassword", "newPassword"}` | `password.changed` |
| PUT | `/api/account/phone` | `{"phone"}` | `phone.changed` |
| PUT | `/api/account/address` | `AddressDto` (`name`, `addressLine1`, `city`, `stateProvince`, ...) | `address.changed` |
| PUT | `/api/account/info` | `{"firstName", "lastName", "langKey", "nationalId", "gender", "title"}` | `info.changed` |
| POST | `/api/util/sanitize-preview` | `{"text"}` (max 2000) | `{"original", "sanitized", "detectionFound"}` |

---

## 5. Player profiles (`/api/security/players`)

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/security/players` | PARENT | Create a child profile (`name`, `dateOfBirth`, `position`, `parentConsent`, `consentPolicyVersion`; consent fields required for minors). |
| GET | `/api/security/players` | PARENT | List the caller's children. |
| GET | `/api/security/players/{playerId}` | owning parent (`@playerOwnershipGuard`) | One child profile. |
| PATCH | `/api/security/players/{playerId}/position` | owning parent | Change a child's position. |
| POST | `/api/security/players/me` | PLAYER | Create the self-registered player's own profile (`position`). One-time. |
| GET | `/api/security/players/me` | PLAYER | The caller's own profile. |
| PATCH | `/api/security/players/me/position` | PLAYER | Change own position. |

One parent per player (FR-USR-001) is enforced by the `uq_ppl_player_id` unique constraint on
`parent_player_links`. There is no "link another parent" endpoint.

---

## 6. Account suspension and session revocation

Server-side behaviour that clients observe as `401`s:

- **Admin suspension** (`POST /api/admin/users/{id}/suspend`) locks the account, invalidates every
  JWT and revokes every refresh token.
- **Safety suspension.** When a video upload matches the CSAM scan, `AccountSuspensionEventListener`
  sets `verification_status = SUSPENDED`, locks the account, invalidates every JWT and revokes every
  refresh token. Login and refresh are refused with `security.accLocked`. The admin console's
  "reactivate" refuses such an account with `409 admin.users.safetyHold`.
- **Refresh-token reuse** (theft detection, see `/api/auth/refresh`) revokes every session.
- **Logout** (`/api/auth/logout` or `/api/logout`) revokes the current session's refresh token.
