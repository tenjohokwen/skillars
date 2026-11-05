# Story skillars-deferred-172: Account email change — verified, reversible, safe

Status: ready-for-dev

<!-- CREATED 2026-11-05 (/bmad-create-story equivalent, written by hand), right after skillars-deferred-171
     (#451, merged 78bb2c9e) and Dependabot #452-#455. Built from ledger item RS03 plus owner decisions taken in
     conversation on 2026-11-05 (see Decisions). Citations read at origin/master b80569ec. Not yet reviewed: run
     /mto-story-review before dev. The other three items of the ledger's "Bundle 1" (RS10, RS11, RS04) are
     deliberately NOT here: owner chose to split them into the next story (skillars-deferred-173). -->

## Story

As **a parent, player or coach**,
I want **to change the email address I sign in with, safely**,
so that **a typo, a stranger who learned my password, or a mixed-case address cannot lock me out or take my account,
and nobody can find out through this form which addresses are registered**.

## Ledger mapping (so future passes do not re-pick this)

| Source | AC |
|---|---|
| `deferred-work.md:336` RS03 "Email change sets login to the raw new email (no lowercasing, no uniqueness check)" | AC1, AC2, AC3 |
| Owner decisions 2026-11-05 (conversation, not a ledger line): verify the new address, undo link to the old address, 72 h cool-off on password reset, generic answer + owner notice for a taken address, revoke sessions | AC2-AC7 |

**Not in this story:** RS10, RS11, RS04 (queued as skillars-deferred-173). Recovery for a user who lost the old
mailbox **and** forgot the password (a support/admin path) is not designed here: one new ledger bullet (AC9).

## Background (code at b80569ec)

- **Login = email.** Every account registered with an email has `login` = that email, lowercased by the account
  facade (`AccountManagementFacade.registerAccount:129-150`, stored lowercased at `:231`). The parent, player and coach
  registration services store `req.email()` **as typed** (`ParentRegistrationService:94` sets `login` = `req.email()`).
  Sign-in lowercases the typed id and looks up `login` (`LoadUserByUserNameService:31-32`). `email` and `login` have
  case-sensitive UNIQUE constraints (`user_email_key`, `user_login_key`, mapped in `ApiAdvice:154-155` to
  `user.email.duplicate` / `user.login.duplicate`). `LoginIdType.PHONE` exists (`AccountManagementFacade:143`) but is
  effectively unreachable (`UserDto.email` is `@NotNull @Email`).
- **Today's change flow** (`PUT /api/account/email`, `ProfileResource:89-99` → `AccountManagementFacade.changeEmail:171-188`
  (`@RateLimited change_email 5/60`) → `UserProfileService.updateUserEmail:115-150`):
  - checks the password and `oldEmail == u.getEmail()` (exact case); sets `email` **and** `login` to the raw new value
    (`:119-120`) at once, no verification, no uniqueness check (a taken address fails at flush with
    `user.email.duplicate`, which tells the caller the address is registered);
  - sends **two** `PROFILE_CHANGE` mails, neither through the outbox: one to the OLD address
    (`AccountChangeEvent EMAIL_CHANGED` → `AccountChangeEmailListener:40-77`) and one to the NEW address (the facade's
    `sendMail`, recipient `user.getEmail()` after the mutation, `:218`). The audit listener
    (`security/audit/listener/AccountChangeEventListener`) only writes an audit row;
  - revokes no session. `UpdateEmailDialog.vue:156-171` logs the current tab out; other devices keep tokens whose
    username is the old login, and `UserProfileService` looks the user up by that login in 5 places (`:59, :82, :116,
    :164, :205`), so those devices fail until they sign in again.
  - Copy is wrong: `success.emailChangeInitiated` says "check your new email for verification"; `ProfileResource:85`
    Javadoc says "Sends verification email to the new address"; `User.java:58` says the username is not updated.
- **Mixed case locks the user out:** a new address typed as `Anna@Example.com` is stored as the login with capitals;
  sign-in looks up `anna@example.com` and never finds it.
- **Precedents to reuse:**
  - Taken-address notice: `RegistrationDuplicateGuard` (package-private, static) — owner notice
    `RegistrationDuplicateAttemptEvent` → `RegistrationDuplicateAttemptListener` (BEFORE_COMMIT) →
    `NotificationOutboxSupport.enqueueEmail(EmailTemplate.CREATION_DUP, recipient, data, sendId)`; rate limit
    `rateLimitingService.tryConsume(String.valueOf(user.getId()), "registration_duplicate_notice", 2, 60, MINUTES)`,
    exhaustion skips silently; GDPR tombstones skipped.
  - Tokens: refresh tokens store `sha256Hex(rawToken)` (`AuthService:156, 224, 742-750`) — hashed storage;
    `RandomUtil.generateActivationKey` (16 bytes SecureRandom, URL-safe Base64). Email verification tokens
    (`EmailVerificationToken`, `@Version`, single use) are the closest entity shape.
  - Session revocation (password change `UserProfileService:175-178`, reset `PasswordResetService:74-76`):
    `refreshTokenRepository.markAllUsedByUserId(userId)` (REQUIRES_NEW) **then**
    `userRepository.invalidateSessionsForUserInCurrentTransaction(userId, now)` (MANDATORY; the REQUIRES_NEW variant
    self-deadlocks after this transaction wrote the user row). Access tokens die at the next DB re-check (≤ 5 min,
    `SecurityConstants.DB_REFRESH_TOKEN_INTERVAL`).
  - Password reset: `PasswordResetService.prepareForPasswordReset:41-52` (`findOneByEmail(currentEmail)` exact case,
    `login == loginId`, dob); mail via `AccountManagementFacade.sendPasswordResetMail:81-98` → `buildRecipient` →
    `user.getEmail()`. Reset key: `User.preparePasswordReset:445-456`, 24 h.
  - Public token page: `UnsubscribePage.vue` (route without `meta`, user clicks a button to act). New public endpoints
    go in `AppEndpoints.PUBLIC_ENDPOINTS` (`AppEndpointsConventionTest`).
  - Templates: enum `EmailTemplate` (`EMAIL_CHANGE("email.change.title")` exists at `:41`, unused, no html, exempt in
    `EmailTemplateFileParityTest:42`, and already in the envelope CHECK per V227); body `src/main/resources/mails/<lowerCamel>.html`;
    strings in `i18n/messages{,_en,_de,_fr}.properties`; `NotificationCatalog.of` case; a migration re-unioning
    `envelope_entity_email_template_check` (V227 is the example); guard tests `EmailTemplateFileParityTest`,
    `EmailTemplateSubjectKeyParityTest`, `EnvelopeEntitySchemaIT`, `NotificationCatalogTest`,
    `DefaultMessageBundleFallbackIT`. Frontend locales: `en-US`, `de-DE`, `fr-FR`.
- Latest migration: V230. Next free: V231.

## Decisions (owner, 2026-11-05)

- **D1: a taken address gets the same answer as a free one** (option B). The response never says whether the address
  is registered. Instead the **owner of the taken address** gets a notice ("someone tried to move another account onto
  your address"), rate-limited per owner like registration's `CREATION_DUP`.
- **D2: the new address must be confirmed** (option 1). Nothing changes until the link sent to the new address is
  clicked. Link valid **24 h**.
- **D3: the old address gets a notice with an undo link** (option 2), as today's security notice does, so fraud can be
  caught. Undo valid **7 days**. Undo reverts email and login, revokes every session, and forces a password reset
  whose link goes to the restored old address. **The old address stays reserved for those 7 days**: no registration
  and no other email change may take it.
- **D4: 72 h cool-off** (option 3): for 72 h after a confirmed change, password reset for the account goes to the
  **old** address, not the new one.
- **D5: lowercase the stored address; revoke the user's sessions; fix the copy and the stale comment.** Sessions are
  revoked when the change is **confirmed** (all of them, including the requesting browser: its token carries the old
  login), not at request time (nothing changed yet).
- **D6 (default, not discussed): the requesting tab is not logged out at request time** any more; the dialog says a
  confirmation link was sent "if the address can be used". At confirm time the confirming page tells the user to sign
  in with the new address.

## Design (pinned; do not re-derive)

1. **Table (V231)** `main.email_change_requests`: `id uuid PK`, `user_id bigint NOT NULL REFERENCES main."user"(id) ON
   DELETE CASCADE`, `old_email text NOT NULL`, `old_login text NOT NULL`, `new_email text NOT NULL` (lowercased),
   `confirm_token_hash text NOT NULL UNIQUE`, `confirm_expires_at timestamptz NOT NULL`, `confirmed_at timestamptz`,
   `undo_token_hash text UNIQUE`, `undo_expires_at timestamptz`, `undone_at timestamptz`, `cooloff_until timestamptz`,
   `superseded_at timestamptz`, `created_at timestamptz NOT NULL DEFAULT now()`, `version bigint NOT NULL DEFAULT 0`.
   Indexes: partial unique `(user_id) WHERE confirmed_at IS NULL AND superseded_at IS NULL` (one open request per user);
   `lower(old_email)` for the reservation lookup. New table, empty, additive: no lint opt-out needed. Same migration
   re-unions `envelope_entity_email_template_check` with the two new template names (see 4).
2. **Tokens:** 32 bytes `SecureRandom`, URL-safe Base64; only `sha256Hex(token)` is stored (refresh-token precedent).
   Links: `<frontend>/#/email-change/confirm?token=…` and `<frontend>/#/email-change/undo?token=…` (build with
   `FrontendLinkBuilder`).
3. **"Address is taken"** (one helper, used by request, confirm and registration): another user has
   `lower(email) = lower(x)` or `lower(login) = lower(x)`, **or** a confirmed, not undone request has
   `lower(old_email) = lower(x)` and `undo_expires_at > now()` (the reservation). The requester's own current address
   is not "taken" for them (requesting your own address is a no-op answered generically, no mail).
4. **Templates:**
   - `EMAIL_CHANGE` (exists, unused): confirmation link, sent to the **new** address. Add html + strings + catalog case.
   - `EMAIL_CHANGED_NOTICE` (new): sent to the **old** address at confirm time: "your sign-in email was changed to
     `n…@domain` (masked); if this wasn't you, undo it within 7 days" + undo link + help code.
   - `EMAIL_CHANGE_ADDRESS_IN_USE` (new): sent to the **owner of a taken address**: "someone tried to change another
     account's email to this address; nothing changed; if this was you, sign in with this address instead".
   - All three through `NotificationOutboxSupport.enqueueEmail` in the business transaction (outbox), not bare
     `Envelope` events.
   - The facade's `PROFILE_CHANGE` mail to the new address and `AccountChangeEmailListener`'s `PROFILE_CHANGE` mail for
     `EMAIL_CHANGED` are **removed** (replaced by the above). The `AccountChangeEvent EMAIL_CHANGED` is still published
     at confirm (and an `EMAIL_CHANGE_UNDONE` action at undo) for the audit listener.
5. **Request** (`PUT /api/account/email`, same DTO, same rate limit): password check and `oldEmail` compared
   case-insensitively to the current email (mismatch: today's `EMAIL_OR_PW_MISMATCH`, unchanged). Then:
   - lowercase `newEmail`;
   - if taken (3) by another account: notify that account's owner (D1; per-owner bucket
     `"email_change_address_in_use_notice"`, 2 per 60 min, exhaustion skips silently; skip GDPR tombstones; a
     reserved old address notifies the reserving user at that address) — **no** request row;
   - else: mark any open request of this user superseded, insert a new request (confirm expires +24 h), enqueue
     `EMAIL_CHANGE` to the new address;
   - in **every** non-mismatch case return the same `Success` body (code `email.change.requested`).
6. **Confirm** (`POST /api/account/email/confirm`, public, body `{token}`, `@RateLimited` 10/10 min):
   - find by hash; reject (one generic error key `email.change.linkInvalid`) if missing, expired, superseded or already
     confirmed;
   - re-check "taken" (3) for `new_email` excluding this user; if taken now: mark superseded, generic invalid-link error;
   - lock the user row; set `email` and (for `LoginIdType.EMAIL`) `login` to `new_email`; set `confirmed_at`, an undo
     token (hash), `undo_expires_at` +7 d, `cooloff_until` +72 h;
   - revoke all sessions (refresh tokens then epoch, MANDATORY variant — see Background);
   - enqueue `EMAIL_CHANGED_NOTICE` to `old_email`; publish `AccountChangeEvent EMAIL_CHANGED` (audit);
   - answer `email.change.confirmed`.
7. **Undo** (`POST /api/account/email/undo`, public, body `{token}`, `@RateLimited` 10/10 min):
   - find by undo hash; reject (`email.change.linkInvalid`) if missing, expired or already undone;
   - lock the user row; restore `email` = `old_email`, `login` = `old_login`; set `undone_at`;
   - set the password to an unusable random value (encoded) so a known password stops working, prepare a reset key
     (`User.preparePasswordReset`) and send the `PASSWORD_RESET` mail to the restored old address;
   - revoke all sessions; publish `AccountChangeEvent EMAIL_CHANGE_UNDONE` (audit); answer `email.change.undone`.
8. **Cool-off in password reset** (`PasswordResetService.prepareForPasswordReset` + `sendPasswordResetMail`): if the
   user has a confirmed, not undone request with `cooloff_until > now()`, the reset mail goes to that request's
   `old_email` instead of `user.getEmail()`. The request still identifies the user as today (current email + login + dob).
9. **Lookups by user id:** the 5 `findOneByLogin(securityUtil.getCurrentUser().getUsername())` in `UserProfileService`
   become `findById(securityUtil.requireCurrentUserId())` (`SecurityUtil:381`), so a token whose username is stale
   still reaches the right user during the ≤ 5 min revocation window.
10. **Registration** treats a reserved address (3) as taken: `RegistrationDuplicateGuard.handledAsDuplicate` (used by
    parent, player and coach registration) and `AccountManagementFacade.registerAccount`. For a reservation match the
    notice goes to the reserving user at the reserved address (same template/bucket as the guard's owner notice).
11. **Frontend:**
    - `UpdateEmailDialog.vue`: on success show the new message (no logout); keep the error mapping for mismatch.
    - New public pages `EmailChangeConfirmPage.vue` (`/email-change/confirm`) and `EmailChangeUndoPage.vue`
      (`/email-change/undo`), modelled on `UnsubscribePage.vue` (no auth meta; the user clicks a button; then success or
      the generic invalid-link message). After confirm: "Your email is changed. Sign in with <new address>." After
      undo: "Your previous email is restored and you've been signed out everywhere. Check that inbox to set a new
      password."
    - API functions in `profile.api.js` (or a new `emailChange.api.js`), using the public axios client.
    - i18n keys in `en-US`, `de-DE`, `fr-FR`.
12. **Copy fixes:** `success.emailChangeInitiated` (all three locales), `ProfileResource:85` Javadoc, `User.java:58`
    comment.

## Acceptance Criteria

**AC1: the new address is stored lowercased and compared case-insensitively.** A request with `New@Example.COM`
confirms to `email` = `login` = `new@example.com`; the user can then sign in with any casing. `oldEmail` matches the
current address case-insensitively. A phone-login user's `login` is not overwritten.

**AC2: a taken address is answered like a free one, and its owner is told.** Requesting another account's address (any
casing) or a reserved old address returns the same body as a successful request; no request row is written; the owner
gets `EMAIL_CHANGE_ADDRESS_IN_USE` once, and a third attempt within 60 min sends nothing more. A GDPR-erased owner gets
nothing. (IT + unit.)

**AC3: nothing changes until the new address confirms.** After a request, `email` and `login` are unchanged and the user
can still sign in with the old address; `EMAIL_CHANGE` with the link went to the new address only; a second request
supersedes the first (the first link answers `email.change.linkInvalid`). An expired (24 h) link is refused. (IT.)

**AC4: confirming switches the address, revokes sessions, notifies the old address.** After confirm: email and login are
the new lowercased address; every refresh token of the user is used and the session epoch is bumped (an existing access
token is refused at its next DB re-check); `EMAIL_CHANGED_NOTICE` with an undo link went to the old address only; an
address that became taken in between is refused and the request superseded. A confirm link works once. (IT.)

**AC5: undo restores the account and locks out whoever changed it.** Within 7 days: email and login are restored; the
old password no longer works; all sessions are revoked; a `PASSWORD_RESET` mail went to the restored address. After 7
days, or a second time, the link answers `email.change.linkInvalid`. (IT.)

**AC6: the old address is reserved for 7 days.** During the undo window, registering (parent, player, coach, account
facade) or another user's email change with the old address is treated as taken (AC2 behaviour). After the window it is
free. (IT for one registration path + the change path; unit for the helper.)

**AC7: 72 h cool-off.** Within 72 h of a confirmed change, a password reset for the account sends its mail to the old
address; after 72 h, or after an undo, to the current address. (IT or unit with a fixed clock.)

**AC8: profile endpoints keep working after the login changes.** The 5 `UserProfileService` lookups use the user id; a
test calls one with a principal whose username is stale and gets the user. The two old `PROFILE_CHANGE` mails are gone
(no `PROFILE_CHANGE` envelope for an email change). Existing `UserProfileServiceIT` / `AccountManagementFacadeIT` email
tests are rewritten for the new flow.

**AC9: frontend, copy, docs, ledger, eval.**
- Dialog no longer logs out; new message in three locales; two new public pages with specs (Vitest, real enUS messages
  for the visible text).
- `ProfileResource` Javadoc and `User.java:58` corrected.
- **Dev docs (`docs/dev-docs`) updated to reflect the change.** No page describes the email change today (checked at
  b80569ec: no page mentions `updateUserEmail`, `change_email`, `UpdateEmailDialog` or `PROFILE_CHANGE`), so this is new
  content, not a touch-up. At least:
  - `security/index.html`: the email-change flow end to end (request → confirm → undo), the two public endpoints and
    their rate limits, hashed single-use tokens and their lifetimes (24 h / 7 d), the address reservation, the 72 h
    password-reset cool-off, session revocation at confirm and undo, the generic answer for a taken address, and that
    `login` follows `email` (lowercased) for email-login accounts; the `email_change_requests` entity in the entity
    table; a gotcha callout ("a new uniqueness check on an email must also consult the reservation").
  - `notification/index.html`: the three templates (`EMAIL_CHANGE`, `EMAIL_CHANGED_NOTICE`,
    `EMAIL_CHANGE_ADDRESS_IN_USE`), who receives each, that they go through the outbox, and that `PROFILE_CHANGE` is no
    longer sent for an email change.
  - `database/index.html`: V231 (`main.email_change_requests`, its indexes, the template CHECK re-union).
  - `frontend/index.html`: the two new public routes/pages (`/email-change/confirm`, `/email-change/undo`) and the
    dialog's new behaviour (no logout on request).
  - `glossary/index.html` if it gains terms ("email-change reservation", "reset cool-off"); any other page a `grep` for
    the touched classes finds.
  - Rebuild the search index (`node docs/dev-docs/tools/build-search-index.mjs`); `check-docs` passes.
- Provisioning checklist entry for V231 (new table + template CHECK) and the three templates.
- Ledger: delete RS03 (`:336`); add one bullet "lost old mailbox and forgotten password: no self-service recovery
  (needs a support/admin path) — owner decision"; flip `evals/review/cases/_drafts/pending-login-case-normalisation.yaml`
  from `fix_pr: pending` to the PR number once known.

**AC10: `/pr-review`** on the final commit, run to a clean round or an owner decision. Then open the PR (not `no-build`;
add the `frontend-tests` label so the Vitest job runs).

## Tasks / Subtasks

- [ ] T1 (AC1-AC7): V231 table + CHECK re-union; entity + repository; token helper; "address is taken" helper (unit).
- [ ] T2 (AC2, AC3): request flow in `UserProfileService`/facade; owner notice; `EMAIL_CHANGE` template; tests.
- [ ] T3 (AC4): confirm endpoint + service; session revocation; `EMAIL_CHANGED_NOTICE`; public endpoint list; tests.
- [ ] T4 (AC5): undo endpoint + service; password invalidation + reset mail; tests.
- [ ] T5 (AC6): reservation in `RegistrationDuplicateGuard` and `registerAccount`; tests.
- [ ] T6 (AC7): cool-off in password reset; tests.
- [ ] T7 (AC8): id-based lookups; remove the two `PROFILE_CHANGE` mails; rewrite existing email-change tests.
- [ ] T8 (AC9): frontend dialog, two pages, routes, API, i18n (3 locales), specs.
- [ ] T9 (AC9): templates (html + 4 properties files + catalog), copy fixes, checklist, ledger, eval case.
- [ ] T9b (AC9): dev docs in `docs/dev-docs` (security, notification, database, frontend, glossary as listed in AC9),
      search index rebuilt, `check-docs` passes.
- [ ] T10 (AC10): `/pr-review`, PR.

## Dev Notes

- Worktree `../skillars-deferred-168`, branch `skillars-deferred-172-account-email-change`. No local `mvn verify`;
  targeted tests with `-DskipFrontend`; Vitest with `cd src/frontend && npm run test:unit -- <spec>` (copy
  `src/frontend/.quasar` into the worktree first if missing). Add files by name.
- **Template checklist** (each new template): enum constant, `mails/<lowerCamel>.html`, subject + body keys in
  `messages.properties`, `_en`, `_de`, `_fr`, `NotificationCatalog.of` case, the V231 CHECK union, and the five guard
  tests listed in Background must stay green. Remove `EMAIL_CHANGE`'s exemption in `EmailTemplateFileParityTest:42`.
- **Transaction shape:** confirm and undo write the user row, so use `invalidateSessionsForUserInCurrentTransaction`
  (MANDATORY), after `markAllUsedByUserId` (REQUIRES_NEW on rows this transaction has not touched). Enqueue the mails
  in the same transaction (outbox). Lock the request row (or rely on `@Version`) so a double click confirms once.
- **Enumeration:** the request endpoint's answer, status and timing must not depend on whether the address is taken.
  The password check runs first on both paths; the owner notice is enqueued (outbox), not sent inline.
- **IT hygiene:** writes in an IT method body must go through `transactionTemplate` (hikari auto-commit is off). Do not
  add `@MockitoBean` to IT base classes (context-cache gate).
- **Masking** the new address in `EMAIL_CHANGED_NOTICE`: first character + `…` + `@domain`.

### References

- `deferred-work.md:336` (RS03) and the section intro `:22-28` (eval-case flip rule).
- `RegistrationDuplicateGuard`, `RegistrationDuplicateAttemptListener`, `EmailVerificationToken`, `AuthService` token
  hashing, `PasswordResetService`, `UnsubscribePage.vue`, V227 (template CHECK union).

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log

- 2026-11-05: created (ready-for-dev).
