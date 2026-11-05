# Story skillars-deferred-172: Account email change — verified, reversible, safe

Status: in-progress

<!-- CREATED 2026-11-05 (/bmad-create-story equivalent, written by hand), right after skillars-deferred-171
     (#451, merged 78bb2c9e) and Dependabot #452-#455. Built from ledger item RS03 plus owner decisions taken in
     conversation on 2026-11-05 (see Decisions). Citations read at origin/master b80569ec.
     REVISED 2026-11-05 after /mto-story-review (story-review.md, HEAD 70aae81d): findings F1-F15 folded in; owner
     decisions R1-R5 on the review's open questions added to Decisions. The other three items of the ledger's
     "Bundle 1" (RS10, RS11, RS04) are deliberately NOT here: owner chose to split them into skillars-deferred-173. -->

## Story

As **a parent, player or coach**,
I want **to change the email address I sign in with, safely**,
so that **a typo, a stranger who learned my password, or a mixed-case address cannot lock me out or take my account,
and nobody can find out through this form which addresses are registered**.

## Ledger mapping (so future passes do not re-pick this)

| Source | AC |
|---|---|
| `deferred-work.md:336` RS03 "Email change sets login to the raw new email (no lowercasing, no uniqueness check)", including its "also S16: AccountManagementFacade.java" registration half | AC1, AC2, AC3, AC9 |
| Owner decisions 2026-11-05 (conversation, not a ledger line): verify the new address, undo link to the old address, generic answer + owner notice for a taken address, revoke sessions, one change per undo window, hold on erasure | AC2-AC8 |
| Eval case `evals/review/cases/_drafts/pending-login-case-normalisation.yaml` (expects F1 email change **and** F2 registration raw-case login) | AC1, AC9, AC11 |

**Not in this story:** RS10, RS11, RS04 (queued as skillars-deferred-173). Recovery for a user who lost the old
mailbox **and** forgot the password (a support/admin path) is not designed here: one new ledger bullet (AC11).

## Background (code at b80569ec)

- **Login = email.** Every account registered with an email has `login` = that email. The account facade lowercases
  only `email` (`AccountManagementFacade.registerAccount:129-158`, `:231`); `login` is set to the email **as typed**
  (`:140`) and copied raw by `toUser:227` (`RegistrationDuplicateGuard:76` says so). The parent, player and coach
  registration services store `req.email()` **as typed** (`ParentRegistrationService:94` sets `login` = `req.email()`).
  Sign-in lowercases the typed id and looks up `login` by exact match (`LoadUserByUserNameService:31-32`,
  `AuthService.login:113`), so a mixed-case stored login can never sign in. `email` and `login` have case-sensitive
  UNIQUE constraints (`user_email_key`, `user_login_key`, mapped in `ApiAdvice:154-155` to `user.email.duplicate` /
  `user.login.duplicate`), so `Anna@X.com` and `anna@x.com` can both exist. `LoginIdType.PHONE` exists
  (`AccountManagementFacade:143`) but is effectively unreachable (`UserDto.email` is `@NotNull @Email`).
- **Today's change flow** (`PUT /api/account/email`, `ProfileResource:89-99` → `AccountManagementFacade.changeEmail:171-187`
  (`@RateLimited change_email 5/60`, per IP, in memory per server) → `UserProfileService.updateUserEmail:115-151`):
  - checks the password and `oldEmail == u.getEmail()` (exact case); sets `email` **and** `login` to the raw new value
    (`:119-120`) at once, no verification, no uniqueness check (a taken address fails at flush with
    `user.email.duplicate`, which tells the caller the address is registered);
  - sends **two** `PROFILE_CHANGE` mails, neither through the outbox: one to the OLD address
    (`AccountChangeEvent EMAIL_CHANGED` → `AccountChangeEmailListener:40-77`, a synchronous `@Transactional
    @EventListener` with **no action filter**: it mails `PROFILE_CHANGE` for every `AccountChangeEvent`) and one to the
    NEW address (the facade's `sendMail`, recipient `user.getEmail()` after the mutation, `:218`). The audit listener
    (`security/audit/listener/AccountChangeEventListener`) only writes an audit row (it records `login =
    userInfo.email`, `authenticated = true`);
  - revokes no session. `UpdateEmailDialog.vue:156-172` logs the current tab out; other devices keep tokens whose
    username is the old login, and the login-based lookups (`UserProfileService:59, :82, :116, :164, :205`,
    `UserService:56, :65`) fail for them until they sign in again. **This fail-closed behaviour is kept** (R4).
  - Copy is wrong: `success.emailChangeInitiated` says "check your new email for verification"; `ProfileResource:85`
    Javadoc says "Sends verification email to the new address"; `User.java:58` says the username is not updated.
- **GDPR erasure keeps the user row** and tombstones it (`GdprErasureService:422-429`: login and email
  `deleted.<id>@erased.io`, `locked`), so `ON DELETE CASCADE` on a child table never fires. Self-service erasure
  (`POST /api/gdpr/erasure`, `GdprResource:53` → `GdprRequestService.requestErasure:98`) needs only a session and runs
  without a grace period; a parent erases a shadow child via `requestChildErasure:152`.
- **Password reset** (`PasswordResetService.prepareForPasswordReset:41-52`): finds the user by `findOneByEmail(currentEmail)`
  (exact case), checks `login == loginId` and dob; the frontend sends the typed value as `currentEmail`
  (`account.api.js:43-47`). Mail via `AccountManagementFacade.sendPasswordResetMail:81-98` → `buildRecipient` →
  `user.getEmail()`. Reset key: `User.preparePasswordReset:445-456`, 24 h; it **throws for a locked or not-activated
  user** (`:450-452`). `changePassword` needs only the current password (`UserProfileService:166`).
- **Precedents to reuse:**
  - Taken-address notice: `RegistrationDuplicateGuard` (package-private, static; same package as `UserProfileService`,
    not reachable from `security.api`; wired to registration: bcrypt timing, `flow`, phone, hard-coded bucket, mails
    `user.getEmail()`, **exact-case** lookup) — owner notice `RegistrationDuplicateAttemptEvent` →
    `RegistrationDuplicateAttemptListener` (BEFORE_COMMIT) → `NotificationOutboxSupport.enqueueEmail(EmailTemplate.CREATION_DUP,
    recipient, data, sendId)` (MANDATORY, no principal dependency); rate limit
    `rateLimitingService.tryConsume(String.valueOf(user.getId()), "registration_duplicate_notice", 2, 60, MINUTES)`,
    exhaustion skips silently; GDPR tombstones skipped.
  - Tokens: refresh tokens store `sha256Hex(rawToken)` (`AuthService:156, 224, 742-750`). Email verification tokens
    (`EmailVerificationToken`, `@Version`, single use) are the closest entity shape. Note: the raw token also sits in
    the outbox payload JSON (as OTPs already do); accepted.
  - Session revocation (password change `UserProfileService:175-178`, reset `PasswordResetService:74-76`, `suspendUser`):
    `refreshTokenRepository.markAllUsedByUserId(userId)` (REQUIRES_NEW, touches only `refresh_tokens`) **then**
    `userRepository.invalidateSessionsForUserInCurrentTransaction(userId, now)` (MANDATORY; the REQUIRES_NEW variant
    self-deadlocks after this transaction wrote the user row). Access tokens die at the next DB re-check (≤ 5 min,
    `SecurityConstants.DB_REFRESH_TOKEN_INTERVAL`, `JWTAuthorizationFilter`).
  - Public token page: `UnsubscribePage.vue` (route without `meta`, user clicks a button to act; uses the one `api`
    axios instance, `boot/axios.js:94`). New public endpoints go in `AppEndpoints.PUBLIC_ENDPOINTS`
    (`AppEndpointsConventionTest` checks only pattern anchoring). `ProfileResource` has a class-level
    `@PreAuthorize(HAS_ANY_ROLE)` (`:47-48`), so public endpoints cannot live there.
  - Templates: enum `EmailTemplate`. `EMAIL_CHANGE("email.change.title")` exists (`:41`), is unused, has no html, is exempt
    in `EmailTemplateFileParityTest:42`, already has a `NotificationCatalog.of` case (`NotificationCatalog:73`) and a
    subject key in all four bundles with the wrong wording for a confirmation mail ("Email Address Changed",
    `messages.properties:55`), and is already in the envelope CHECK (V227). Body `src/main/resources/mails/<lowerCamel>.html`
    (Thymeleaf; `profileChange.html:10-17` switches wording on a data field); strings in
    `i18n/messages{,_en,_de,_fr}.properties`. Guard tests: `EmailTemplateFileParityTest`, `EmailTemplateSubjectKeyParityTest`,
    `EnvelopeEntitySchemaIT`, `NotificationCatalogTest` (incl. `:48`), `DefaultMessageBundleFallbackIT`,
    `MessageBundleParityTest` (all bundles, no English in de/fr, MessageFormat quoting); frontend `localeKeyParitySpec.js`,
    `i18nTemplateKeyResolutionSpec.js`. Frontend locales: `en-US`, `de-DE`, `fr-FR`.
  - Migrations: `MigrationLint` requires `-- migration-lint: allow-blocking-index` on every non-concurrent `CREATE INDEX`
    (even on a new table; V220/V222/V228) and `allow-validating-constraint` on a validating CHECK re-union (V227: single
    DROP/ADD, `SET LOCAL lock_timeout`). Latest migration: V230. Next free: V231, V232.
- **Already documented (wrongly):** `docs/dev-docs/security/index.html:186` ("Change email (sends verification)"),
  `docs/dev-docs/notification/index.html:456` (`EMAIL_CHANGE`, "which nothing sends"), `docs/security-api-endpoints.md:183`,
  `docs/deployment/provisioning-checklist.md:569`.

## Decisions (owner, 2026-11-05)

From the first conversation:

- **D1: a taken address gets the same answer as a free one.** The response never says whether the address is
  registered. Instead the **owner of the taken address** gets a notice, rate-limited per owner like `CREATION_DUP`.
- **D2: the new address must be confirmed.** Nothing changes until the link sent to the new address is clicked. Link
  valid **24 h**.
- **D3: the old address gets a notice with an undo link**, so fraud can be caught. Undo valid **7 days**. Undo reverts
  email and login, revokes every session, and forces a password reset whose link goes to the restored old address
  (see R2 for suspended accounts). **The old address stays reserved for those 7 days**: no registration and no other
  email change may take it.
- ~~D4: 72 h password-reset cool-off~~ — **replaced by R3** (review F4: it protected nothing).
- **D5: lowercase the stored address; revoke the user's sessions; fix the copy and the stale comment.** Sessions are
  revoked when the change is **confirmed** and when it is **undone** (all of them), not at request time.
- **D6: the requesting tab is not logged out at request time**; the dialog says a confirmation link was sent "if the
  address can be used".

From the story review (R = review decision):

- **R1 (F2): one change per undo window.** While the user has a confirmed change whose undo link is still valid
  (`undo_expires_at > now()`, not undone), a new change request is refused (after the password check) with
  `email.change.recentChange` carrying the date it becomes possible again: "You changed your email recently. You can
  change it again after {date}. If you didn't make that change, use the link we sent to your previous address."
  Baseline on top: undo supersedes every open request of the user and invalidates every other undo token of the user;
  confirm refuses when the request's `old_email` is not (case-insensitively) the user's current email.
- **R2 (F3): erased and suspended accounts.** Erasure deletes all the user's `email_change_requests` rows in the erasure
  transaction (frees the reservation, kills every link). Confirm refuses a locked user. **Undo on a locked (suspended)
  account restores the address but leaves the suspension alone:** it restores email and login, revokes sessions, ends
  the reservation, and **skips** the password randomisation and reset mail; the notice to the restored address says the
  email was restored and to reset the password once the account is reinstated. (Same skip for a not-activated user,
  for which `preparePasswordReset` also throws.)
- **R3 (F4): hold on irreversible actions while undo is possible** (replaces D4). While the user has an open undo window
  (same check as R1), self-service erasure (`requestErasure`) and a parent's child erasure (`requestChildErasure`) are
  refused, 409 `gdpr.recentEmailChange` with the date: "You changed your email recently; you can delete your account
  after {date}." Nothing else is held. The 72 h number is gone.
- **R4 (F8): no id-based lookups.** The login-based lookups stay and fail closed for a revoked token. **F14 is a
  mandatory first task:** check whether the DB re-check can re-authorise a stale token as a *different* account (reload by
  username, no `busId` comparison); if so, add the id equality check.
- **R5 (F9): include RS03's registration half, fix the data and enforce it in the DB** (option A+). Lowercase login and
  email at registration (facade and the three role services); a migration lowercases existing `login`/`email` and adds
  unique indexes on `lower(login)` and `lower(email)`, failing loudly on a collision.

## Design (pinned; do not re-derive)

1. **V231 — case-insensitive identity** (`main."user"`):
   - `SET LOCAL lock_timeout = '5s'`; header states the waiver "prod not live; code and data change ship in one release".
   - A `DO` block that `RAISE EXCEPTION`s, naming the colliding values, if `lower(email)` or `lower(login)` collides
     across rows (no silent merge).
   - `UPDATE main."user" SET email = lower(email), login = lower(login) WHERE email <> lower(email) OR login <> lower(login)`
     (tombstones are already lowercase; phone logins are unaffected).
   - `CREATE UNIQUE INDEX ux_user_email_lower ON main."user" (lower(email))` and `ux_user_login_lower ON main."user" (lower(login))`,
     each with `-- migration-lint: allow-blocking-index` (and any backfill marker the lint asks for). Keep the existing
     constraints. Map both new index names in `ApiAdvice` next to `:154-155` (same message keys).
2. **V232 — table** `main.email_change_requests`: `id uuid PK`, `user_id bigint NOT NULL REFERENCES main."user"(id)`,
   `old_email text NOT NULL`, `old_login text NOT NULL`, `new_email text NOT NULL` (lowercased),
   `confirm_token_hash text NOT NULL UNIQUE`, `confirm_expires_at timestamptz NOT NULL`, `confirmed_at timestamptz`,
   `undo_token_hash text UNIQUE`, `undo_expires_at timestamptz`, `undone_at timestamptz`, `superseded_at timestamptz`,
   `created_at timestamptz NOT NULL DEFAULT now()`, `version bigint NOT NULL DEFAULT 0` (no `cooloff_until`: R3).
   Indexes (each with `allow-blocking-index`): partial unique `(user_id) WHERE confirmed_at IS NULL AND superseded_at IS NULL`
   (one open request per user); `(user_id)`; `lower(old_email)` for the reservation lookup. `SET LOCAL lock_timeout = '5s'`
   (the FK locks `main."user"`; `EmailRetryScheduler` polls `envelope_entity`). Same migration re-unions
   `envelope_entity_email_template_check` as V227 does (single DROP/ADD, `allow-validating-constraint`), keeping every V227
   value and adding the two new names (see 5). Header states the waiver as in 1.
3. **Tokens:** 32 bytes `SecureRandom`, URL-safe Base64; only `sha256Hex(token)` is stored in the table. Links:
   `<frontend>/#/email-change/confirm?token=…` and `<frontend>/#/email-change/undo?token=…` (`FrontendLinkBuilder`).
4. **"Address is taken"** — one helper (in `security.service`, reachable from both the facade and the guard), used by
   request, confirm and registration. Exists/limit-1 queries only (never a single-result query):
   - another user has `lower(email) = lower(x)` or `lower(login) = lower(x)`, **or**
   - a confirmed, not undone request of **another** user has `lower(old_email) = lower(x)` and `undo_expires_at > now()`
     (the reservation).
   It returns which case hit (owner user, or reserving user + reserved address) so the caller picks the recipient. The
   requester's own current address is not "taken" for them (requesting it is answered generically, no mail, no row).
   It **replaces** `RegistrationDuplicateGuard`'s exact-case lookup.
5. **Templates** (all through `NotificationOutboxSupport.enqueueEmail` in the business transaction):
   - `EMAIL_CHANGE` (exists): confirmation link, sent to the **new** address. Add the html; **reword** the existing
     subject `email.change.title` in all four bundles (it says "changed"); remove its exemption in
     `EmailTemplateFileParityTest:42`. The catalog case exists.
   - `EMAIL_CHANGED_NOTICE` (new): sent to the **old** address at confirm: "your sign-in email was changed to
     `n…@domain` (masked); if this wasn't you, undo it within 7 days" + undo link + help code.
   - `EMAIL_CHANGE_ADDRESS_IN_USE` (new), two wordings selected by a `reserved` data flag (as `profileChange.html` switches
     on its data):
     - `reserved=false`, to the **owner** of a taken address: "someone tried to change another account's email to this
       address; nothing changed; if this was you, sign in with this address instead";
     - `reserved=true`, to the **reserved old address** (never `user.getEmail()`, which is now the new, possibly
       attacker's, address): "this address was recently removed from your Skillars account; someone tried to use it;
       if you didn't change your email, use the undo link we sent you".
     Used by both the email-change request and registration (guard and `registerAccount`) for a reservation hit; a
     non-reservation registration hit keeps `CREATION_DUP` as today.
   - The undo's reset mail is the existing `PASSWORD_RESET`; on a suspended account (R2) a short variant of
     `EMAIL_CHANGED_NOTICE` (`undone=true` flag) tells the restored address the email was restored.
   - The facade's `PROFILE_CHANGE` mail to the new address is **removed**. `AccountChangeEmailListener` **skips**
     `EMAIL_CHANGED` and `EMAIL_CHANGE_UNDONE` (explicit action filter). Both events are still published (confirm, undo)
     for the audit listener, which records the **old** address for `EMAIL_CHANGED` and the restored address for
     `EMAIL_CHANGE_UNDONE`, with `authenticated = false` (both are anonymous link clicks).
   - Masking: first character + `…` + `@domain`; a local part of ≤ 2 characters shows only `…@domain`.
6. **Request** (`PUT /api/account/email`, same DTO with `@NotNull` added to `password`, `@RateLimited change_email`
   kept, plus a per-user bucket `tryConsume(userId, "email_change_request", 3, 60, MINUTES)` — exhaustion answers 429):
   - password check, then `oldEmail` compared case-insensitively to the current email (mismatch:
     `EMAIL_OR_PW_MISMATCH`, unchanged);
   - open undo window (R1)? refuse with `email.change.recentChange` + date;
   - lowercase `newEmail`; **supersede the user's open request on every path below** (F1: whether a previous link still
     works must not depend on whether the new address is taken);
   - new address = own current address: generic answer, nothing else;
   - taken (4): enqueue `EMAIL_CHANGE_ADDRESS_IN_USE` to the owner / reserved address (per-recipient-user bucket
     `"email_change_address_in_use_notice"`, 2 per 60 min, exhaustion skips silently; GDPR tombstones skipped) — no row;
   - free: per-target bucket `tryConsume(lower(newEmail), "email_change_confirm_mail", 3, 24, HOURS)` — exhaustion skips
     silently (no row, no mail); else insert a request (confirm expires +24 h) and enqueue `EMAIL_CHANGE` to the new address;
   - every non-mismatch, non-refused case returns the same `Success` body (code `email.change.requested`), status 200.
7. **Confirm** (`POST /api/account/email/confirm`, public, body `{token}`, `@RateLimited` 10/10 min):
   - find by hash; reject (`email.change.linkInvalid`, **400**) if missing, expired, superseded or already confirmed;
   - reject (`linkInvalid`) and supersede if the user is locked (R2), or `old_email` is not the user's current email
     (case-insensitive, R1 baseline), or `new_email` is taken now (4, excluding this user);
   - write the user row: `email` and (for `LoginIdType.EMAIL`) `login` = `new_email`; set `confirmed_at`, an undo token
     (hash), `undo_expires_at` +7 d; a concurrent second click loses on `@Version` and gets `linkInvalid` (no NOWAIT lock);
   - revoke all sessions (refresh tokens then epoch, MANDATORY variant — Background);
   - enqueue `EMAIL_CHANGED_NOTICE` to `old_email`; publish `AccountChangeEvent EMAIL_CHANGED` (audit only);
   - answer `email.change.confirmed`.
8. **Undo** (`POST /api/account/email/undo`, public, body `{token}`, `@RateLimited` 10/10 min):
   - find by undo hash; reject (`email.change.linkInvalid`, 400) if missing, expired or already undone (an erased user
     has no rows: R2);
   - restore `email` = `old_email`, `login` = `old_login`; set `undone_at`; supersede every open request of the user and
     null/expire every other undo token of the user (R1);
   - unless the user is locked or not activated (R2): set the password to an unusable random value (encoded), prepare a
     reset key (`User.preparePasswordReset`) and send `PASSWORD_RESET` to the restored address; otherwise send the
     `undone` notice variant;
   - revoke all sessions; publish `AccountChangeEvent EMAIL_CHANGE_UNDONE` (audit only); answer `email.change.undone`.
9. **Holds** (R1, R3): one repository query "user has an open undo window" (confirmed, not undone,
   `undo_expires_at > now()`) returning the expiry; used by the request (6), `GdprRequestService.requestErasure` and
   `requestChildErasure` (409 `gdpr.recentEmailChange` + date; for the child path the check is on the parent).
10. **Erasure cleanup** (R2): `GdprErasureService` deletes the user's `email_change_requests` rows in the same
    transaction as the tombstone (one more statement in the group that tombstones the user; update the group statement
    count in its test).
11. **Password change and reset supersede an open (unconfirmed) request** of the user (a changed password ends any
    pending change). `prepareForPasswordReset` lowercases `currentEmail` before the lookup.
12. **Registration** (R5): lowercase `login` and `email` in `AccountManagementFacade.registerAccount` (`:140`, `toUser`) and
    in the parent, player and coach registration services; `RegistrationDuplicateGuard` and `registerAccount` use the
    helper (4), so a reserved address is taken and a reservation hit mails the reserved address (5).
13. **F14 check (first):** trace `JWTAuthorizationFilter` → `DaoAuthProvider:34` `retrieveUser(username)` on the DB
    re-check. If the reloaded user's id is not compared with the token's `busId`, add that comparison (mismatch = refuse
    as a revoked token) with a test where a stale token's login now belongs to another user.
14. **Frontend:**
    - `UpdateEmailDialog.vue`: on success show the new message (no logout); map `email.change.recentChange` (with date);
      keep the mismatch mapping; make `notSameEmail` case-insensitive.
    - New public pages `EmailChangeConfirmPage.vue` (`/email-change/confirm`) and `EmailChangeUndoPage.vue`
      (`/email-change/undo`), modelled on `UnsubscribePage.vue` (no auth meta; the user clicks a button; then success or
      the generic invalid-link message). **On success both clear the local session** (store + tokens), since `/login` and
      `/reset-password` are guest-only and the old session can live up to 5 minutes. After confirm: "Your email is
      changed. Sign in with <new address>." After undo: "Your previous email is restored and you've been signed out
      everywhere. Check that inbox to set a new password." (suspended-account wording when the response says so).
    - Profile "Privacy & data" card: map `gdpr.recentEmailChange` (with date).
    - API functions in a new `emailChange.api.js` using the one `api` instance (`boot/axios.js:94`).
    - i18n keys in `en-US`, `de-DE`, `fr-FR`.
15. **Copy fixes:** `success.emailChangeInitiated` (all three locales), `ProfileResource:85` Javadoc, `User.java:58`.

## Acceptance Criteria

**AC1: addresses are stored lowercased and compared case-insensitively.** A request with `New@Example.COM` confirms to
`email` = `login` = `new@example.com`; the user can then sign in with any casing. `oldEmail` and the reset's
`currentEmail` match case-insensitively. A phone-login user's `login` is not overwritten. Registering via the facade or
any role service with `Anna@X.com` stores `anna@x.com` (login and email) and can sign in; `anna@x.com` then counts as
taken for `ANNA@x.com`. (IT + unit.)

**AC2: V231 lowercases existing data and enforces it.** After V231, mixed-case rows are lowercase; inserting a row whose
email or login differs from an existing one only in case fails on the new unique index and maps to
`user.email.duplicate` / `user.login.duplicate`; the collision `DO` block is covered by a test that seeds two colliding
rows and expects the migration's error (or, if seeding before migration is impractical, a SQL-level test of the block).
`MigrationLint` passes. (IT.)

**AC3: a taken address is answered like a free one, and its owner is told.** Requesting another account's address (any
casing) or a reserved old address returns the same body and status as a successful request; no request row is written;
the user's previous open request is superseded in both cases; the owner (or the reserved address, `reserved=true`)
gets `EMAIL_CHANGE_ADDRESS_IN_USE`, at most twice per 60 min (the third attempt sends nothing). A GDPR-erased owner gets
nothing. The per-user bucket answers 429 after 3 requests in 60 min. (IT + unit.)

**AC4: nothing changes until the new address confirms.** After a request, `email` and `login` are unchanged and the user
can still sign in with the old address; `EMAIL_CHANGE` with the link went to the new address only; a second request
supersedes the first (the first link answers `email.change.linkInvalid`). An expired (24 h) link is refused. A password
change or reset supersedes the open request. (IT.)

**AC5: confirming switches the address, revokes sessions, notifies the old address.** After confirm: email and login are
the new lowercased address; every refresh token of the user is used and the session epoch is bumped; `EMAIL_CHANGED_NOTICE`
with an undo link went to the old address only; no `PROFILE_CHANGE` envelope was written. Confirm is refused (and the
request superseded) when the address became taken, when the user's email no longer equals `old_email`, or when the user
is locked. A confirm link works once. (IT.)

**AC6: undo restores the account and locks out whoever changed it.** Within 7 days: email and login are restored; the
old password no longer works; all sessions are revoked; `PASSWORD_RESET` went to the restored address; every other open
request and undo token of the user is dead. On a locked account: address restored, sessions revoked, account still
locked, no password change, the `undone` notice sent. After 7 days, or a second time, the link answers
`email.change.linkInvalid`. (IT.)

**AC7: one change per undo window, and the old address is reserved.** During the undo window: a new change request is
refused with `email.change.recentChange` + date; registering (parent, player, coach, account facade) or another user's
email change with the old address is treated as taken (AC3 behaviour, `reserved=true` mail to the old address). After the
window (or after an undo) both are possible again. (IT for one registration path + the change path; unit for the helper.)

**AC8: erasure is held while undo is possible, and cleans up after.** During the undo window `requestErasure` and a
parent's `requestChildErasure` answer 409 `gdpr.recentEmailChange`; after it they work. Erasing a user deletes their
`email_change_requests` rows (an open confirm link then answers `linkInvalid`; the reserved address is free). (IT.)

**AC9: stale tokens.** F14 checked and the result recorded in Completion Notes; if the gap exists, a token whose login now
belongs to another user is refused at the DB re-check (IT). The login-based lookups are unchanged. Existing
`UserProfileServiceIT` / `AccountManagementFacadeIT` email tests are rewritten for the new flow.

**AC10: frontend.** Dialog no longer logs out; new messages (incl. `recentChange`) in three locales; `notSameEmail`
case-insensitive; two new public pages that clear the local session on success; `gdpr.recentEmailChange` shown on the
privacy card; specs (Vitest, real enUS messages for the visible text); `localeKeyParitySpec.js` and
`i18nTemplateKeyResolutionSpec.js` green.

**AC11: copy, docs, checklist, ledger, eval.**
- `ProfileResource` Javadoc and `User.java:58` corrected; `success.emailChangeInitiated` fixed.
- **Dev docs (`docs/dev-docs`) updated.** The existing statements are wrong and must be **corrected**, not just added to
  (`security/index.html:186`, `notification/index.html:456`). At least:
  - `security/index.html`: the email-change flow end to end (request → confirm → undo), the two public endpoints and
    their rate limits, hashed single-use tokens and their lifetimes (24 h / 7 d), one change per undo window, the
    address reservation, the erasure hold, session revocation at confirm and undo, the suspended-account undo, the
    generic answer for a taken address, case-insensitive identity (lowercase storage + `lower()` unique indexes), and the
    F14 outcome; the `email_change_requests` entity in the entity table; a gotcha callout ("a new uniqueness check on an
    email must use the shared helper: it also consults the reservation").
  - `notification/index.html`: the three templates (`EMAIL_CHANGE`, `EMAIL_CHANGED_NOTICE` incl. `undone`,
    `EMAIL_CHANGE_ADDRESS_IN_USE` incl. `reserved`), who receives each, that they go through the outbox, and that
    `PROFILE_CHANGE` is no longer sent for an email change.
  - `database/index.html`: V231 (lowercase backfill, `lower()` unique indexes) and V232 (`main.email_change_requests`,
    its indexes, the template CHECK re-union).
  - `frontend/index.html`: the two new public routes/pages and the dialog's new behaviour.
  - `glossary/index.html` if it gains terms ("email-change reservation", "undo window"); any other page a `grep` for the
    touched classes finds.
  - Rebuild the search index (`node docs/dev-docs/tools/build-search-index.mjs`); `check-docs` passes.
- `docs/security-api-endpoints.md:183` corrected and the two public endpoints added.
- Provisioning checklist: correct `:569`; entries for V231 (collision check on UAT/PROD data before deploy) and V232, and
  the three templates.
- Ledger: delete RS03 (`:336`); add bullets: "lost old mailbox and forgotten password: no self-service recovery (needs a
  support/admin path) — owner decision"; "Stripe customer email is set only at creation (`StripePaymentGateway:647-648`),
  not updated on an email change"; "`FileStorageService.softDelete:328` compares `created_by` with the current login, so
  files uploaded before an email change cannot be deleted through the generic path".
- Eval case close-out (per `evals/review/README.md:110-120` and the #413/#414 precedent): move
  `_drafts/pending-login-case-normalisation.yaml` out of `_drafts/`, drop the `pending-` prefix from the file name and
  `id`, set `fix_pr` to the PR number, keep `input_rev`; after merge add `fix_merge` and remove `input_rev`. Both expected
  findings (F1 email change, F2 registration) must be fixed by this PR.

**AC12: `/pr-review`** on the final commit, run to a clean round or an owner decision. Then open the PR (not `no-build`;
add the `frontend-tests` label so the Vitest job runs).

## Tasks / Subtasks

- [x] T0 (AC9): F14 check (Design 13); fix + test if the gap exists. Record the outcome.
- [x] T1 (AC1, AC2): V231 (collision check, backfill, `lower()` indexes); `ApiAdvice` mapping; registration lowercasing
      (facade + three role services); reset `currentEmail` lowercasing; tests.
- [x] T2 (AC3-AC8): V232 table + CHECK re-union; entity (`@Version`) + repository (open-window query); token helper;
      "address is taken" helper replacing the guard's lookup (unit).
- [x] T3 (AC3, AC4): request flow (hold, supersede on every path, buckets, generic answer); `EMAIL_CHANGE` html + subject
      reword; `EMAIL_CHANGE_ADDRESS_IN_USE` (both wordings); `@NotNull` password; password change/reset supersede; tests.
- [x] T4 (AC5): new public controller (confirm + undo) + `PUBLIC_ENDPOINTS`; confirm service; session revocation;
      `EMAIL_CHANGED_NOTICE`; listener action filter; audit fields; remove the facade's `PROFILE_CHANGE` mail; tests.
- [x] T5 (AC6): undo service (incl. supersede/kill other tokens, locked/not-activated variant); tests.
- [x] T6 (AC7): reservation in `RegistrationDuplicateGuard` and `registerAccount` (`reserved=true` mail); tests.
- [x] T7 (AC8): erasure hold in `requestErasure` / `requestChildErasure`; erasure cleanup in `GdprErasureService`
      (statement count); tests.
- [x] T8 (AC9): rewrite existing email-change tests for the new flow.
- [x] T9 (AC10): frontend dialog, two pages, routes, API, privacy-card mapping, i18n (3 locales), specs.
- [x] T10 (AC11): templates' four bundles + guard tests, copy fixes, `security-api-endpoints.md`, checklist, ledger,
      eval case.
- [x] T10b (AC11): dev docs in `docs/dev-docs` (correct + extend as listed), search index rebuilt, `check-docs` passes.
- [ ] T11 (AC12): `/pr-review`, PR.

## Dev Notes

- Worktree `../skillars-deferred-168`, branch `skillars-deferred-172-account-email-change`. No local `mvn verify`;
  targeted tests with `-DskipFrontend -Djacoco.skip=true`; Vitest with `cd src/frontend && npm run test:unit -- <spec>`
  (copy `src/frontend/.quasar` into the worktree first if missing). Add files by name.
- **Template checklist** (each new template): enum constant, `mails/<lowerCamel>.html`, subject + body keys in
  `messages.properties`, `_en`, `_de`, `_fr` (no English in de/fr; MessageFormat quoting), `NotificationCatalog.of` case,
  the V232 CHECK union, and every guard test listed in Background green.
- **Transaction shape:** confirm and undo write the user row, so use `invalidateSessionsForUserInCurrentTransaction`
  (MANDATORY), after `markAllUsedByUserId` (REQUIRES_NEW on rows this transaction has not touched). Enqueue the mails in
  the same transaction (outbox). Double clicks are settled by `@Version` on the request row (loser → `linkInvalid`).
- **Enumeration:** the request endpoint's answer, status, side effects on the caller's own links, and timing must not
  depend on whether the address is taken. The password check runs first on every path; mails are enqueued (outbox),
  never sent inline; bucket exhaustion on the target side is silent.
- **IT hygiene:** writes in an IT method body must go through `transactionTemplate` (hikari auto-commit is off). Do not
  add `@MockitoBean` to IT base classes (context-cache gate). Test a fixed clock for the 24 h / 7 d boundaries.
- **Lock order:** user row before request row everywhere (confirm, undo, erasure cleanup) to avoid deadlocks.

### References

- `story-review.md` (2026-11-05, HEAD 70aae81d): findings F1-F15 this revision answers.
- `deferred-work.md:336` (RS03) and the section intro `:22-28`; `evals/review/README.md:110-120` (case close-out).
- `RegistrationDuplicateGuard`, `RegistrationDuplicateAttemptListener`, `EmailVerificationToken`, `AuthService` token
  hashing, `PasswordResetService`, `GdprErasureService:422-429`, `GdprRequestService:98,152`, `UnsubscribePage.vue`,
  V227 (template CHECK union), V220/V222/V228 (index lint markers).

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (claude-opus-5-5)

### Debug Log References

- `EmailChangeResourceIT` first failed with 403 on `PUT /api/account/email`: the test used a client id outside the
  test profile's machine-client allow-list (`ClientIdAccessDecisionManager`); switched to `testClientId`.
- `ParentRegistrationServiceTest` (2): stubs on the mixed-case address; the shared helper looks up lowercased.
- `i18nTemplateKeyResolutionSpec`: the two dynamic `t(errorMessageKey(err))` sites became one inside `errorText()`.

### Completion Notes List

- **T0 / F14 confirmed and fixed.** The DB re-check reloaded the account by the token's username with no id check;
  the chain-liveness check caught it only for tokens carrying a chain id. `JWTAuthorizationFilter.assertSameAccount`
  now refuses a token whose `busId` differs from the reloaded account's id (fails open on a blank claim, like
  `userIdOf`). Covered by a unit test in `JWTAuthorizationFilterTest` (red before the fix), not an IT.
- **R5:** lowercasing lives in the entity setters (`User.setLogin`, `Customer.setEmail`) instead of each of the five
  write sites, so every path (registration, change, admin bootstrap, erasure) is covered. V231 = collision guard +
  backfill + `ux_user_email_lower` / `ux_user_login_lower` (mapped in `ApiAdvice`); `UserIdentityCaseIT` runs the
  guard against a seeded collision inside a rolled-back transaction. Password reset matches the typed address in any
  casing.
- **Deviations from the Design text (deliberate):** public endpoints are `/api/email-change/confirm|undo` (own
  controller and prefix, not under `/api/account`); the request PK is the project's `BaseEntity` TSID `bigint`, not
  `uuid`; the double-click guard is a conditional UPDATE (`claimConfirm` / `claimUndo`) rather than `@Version` alone;
  the 429 is a `ResponseStatusException` with reason `email.change.tooManyRequests` (the `@RateLimited` aspect's own
  exception maps to 401, which the SPA treats as a session end); the reservation notice at registration goes through
  a new `ReservedAddressAttemptEvent`.
- **Verified by mutation:** removing the supersede on the taken path (F1) and disabling the reservation lookup each
  turned their `EmailChangeServiceIT` test red; restored.
- **Tests run (targeted, all green):** unit `JWTAuthorizationFilterTest`, `UserIdentityCaseTest`,
  `EmailAddressAvailabilityTest`, `Parent/Player/CoachRegistrationServiceTest`, `GdprErasureServiceTest`,
  `RegistrationDuplicateAttemptListenerTest`, `EmailTemplateFileParityTest`, `EmailTemplateSubjectKeyParityTest`,
  `MessageBundleParityTest`, `NotificationCatalogTest`, `MigrationConventionLintTest`; IT `EmailChangeServiceIT` (15),
  `EmailChangeResourceIT` (2), `UserIdentityCaseIT`, `PasswordResetIT`, `UserProfileServiceIT`,
  `AccountManagementFacadeIT`, `ParentRegistrationResourceIT`, `GdprErasureIT`, `GdprChildScopeIT`,
  `EnvelopeEntitySchemaIT`; full Vitest suite (122 files, 1368 tests), ESLint and Prettier on the changed frontend
  files. Full `mvn verify` left to CI (project policy).
- **T10b (dev docs):** `security` (corrected the "sends verification" row; new sections "Account email change" with a
  sequence diagram and "Case-insensitive identity"; entity/service/endpoint/event tables; `assertSameAccount`; the
  `EmailAddressAvailability` gotcha), `notification` (corrected "which nothing sends"; the three templates and their
  variants), `database` (V231, V232), `frontend` (routes, pages, dialog), `glossary` (email-change reservation, undo
  window); search index rebuilt; `check-docs --render` OK on 26 pages; headless-Chrome Mermaid check clean.
- Audit rows for EMAIL_CHANGED / EMAIL_CHANGE_UNDONE are recorded with `authenticated = false`; in the ITs the audit
  insert logs a `url` NOT NULL error because there is no HTTP request (same as the existing password-change path;
  the listener swallows it).
- /pr-review round 1 (ad3a389e): F1 resend lookups matched the typed casing, F2 password guessing through the request
  endpoint (per-account attempt bucket, consumed before the check), F4 concurrent requests hit an unmapped constraint;
  fixed in e7005213. Owner decision V2 (option 1): every undo also mails the `revertedAway` variant of
  `EMAIL_CHANGED_NOTICE` to the address the account left, because whoever reads the old mailbox can undo. V1 (per-JVM
  rate limits) is moot on the single-instance deploy.
- Owner decision F3 (option 1): the confirmation-mail limit is two buckets, 3 a day per (requesting account, address)
  and 10 a day per address, so one account can no longer use up an address's allowance and silently block its owner
  (replaces the Design's single per-address bucket). Mutation check: back to the per-address key, the new IT fails.
- /pr-review round 2 (09554010), owner decision C (option 1): after an undo, the revoked session could still erase the
  account for up to 5 min (the filter re-checks tokens only every `DB_REFRESH_TOKEN_INTERVAL`). New
  `CurrentSessionGuard.assertNotRevoked` (DB read: locked, or token issued before the session epoch → 401) runs first
  on both erasure endpoints. `GdprErasureIT`'s fast-path probe moved to `GET /api/gdpr/requests`, and it now also
  asserts the repeated erasure is refused. Mutation check: guard call removed → `EmailChangeResourceIT` fails.
- Owner decision D (option 1): V232's FK to `"user"` is now `ON DELETE CASCADE` (edited in place, V232 is not live), so
  `UserAdminService`'s hard delete of never-activated accounts is never blocked; the comment that the user row is never
  deleted was wrong. Pinned by `EmailChangeServiceIT.theUserForeignKey_cascades_...` (pg_constraint `confdeltype = 'c'`).

### File List

- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/skillars-deferred-171-development-data-erasure-completeness.md`
- `_bmad-output/implementation-artifacts/skillars-deferred-172-account-email-change.md` (new)
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/story-review.md`
- `docs/deployment/provisioning-checklist.md`
- `docs/security-api-endpoints.md`
- `evals/review/cases/login-case-normalisation.yaml` (renamed from `evals/review/cases/_drafts/pending-login-case-normalisation.yaml`)
- `src/frontend/src/api/emailChange.api.js` (new)
- `src/frontend/src/components/profile/PrivacyDataSection.vue`
- `src/frontend/src/components/profile/UpdateEmailDialog.vue`
- `src/frontend/src/components/profile/__tests__/PrivacyDataSectionSpec.js`
- `src/frontend/src/components/profile/__tests__/UpdateEmailDialogSpec.js` (new)
- `src/frontend/src/composables/useSession.js`
- `src/frontend/src/i18n/__tests__/i18nTemplateKeyResolutionSpec.js`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`
- `src/frontend/src/pages/EmailChangeConfirmPage.vue` (new)
- `src/frontend/src/pages/EmailChangeUndoPage.vue` (new)
- `src/frontend/src/pages/__tests__/EmailChangeLinkPagesSpec.js` (new)
- `src/frontend/src/router/routes.js`
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprRequestService.java`
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`
- `src/main/java/com/softropic/skillars/platform/notification/contract/EmailTemplate.java`
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/AccountChangeEmailListener.java`
- `src/main/java/com/softropic/skillars/platform/notification/repo/NotificationRecipientLookup.java`
- `src/main/java/com/softropic/skillars/platform/notification/service/NotificationCatalog.java`
- `src/main/java/com/softropic/skillars/platform/security/api/AccountManagementFacade.java`
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java`
- `src/main/java/com/softropic/skillars/platform/security/api/EmailChangeResource.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/api/EmailChangeResultDto.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/api/EmailChangeTokenDto.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/api/ProfileResource.java`
- `src/main/java/com/softropic/skillars/platform/security/audit/listener/AccountChangeEventListener.java`
- `src/main/java/com/softropic/skillars/platform/security/config/AppEndpoints.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/event/AccountChangeEvent.java`
- `src/main/java/com/softropic/skillars/platform/security/contract/event/ReservedAddressAttemptEvent.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/contract/exception/EmailChangeLinkInvalidException.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/contract/exception/RecentEmailChangeException.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java`
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/RegistrationDuplicateAttemptListener.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/Customer.java`
- `src/main/java/com/softropic/skillars/platform/security/repo/EmailChangeRequest.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/repo/EmailChangeRequestRepository.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/repo/User.java`
- `src/main/java/com/softropic/skillars/platform/security/service/CoachRegistrationService.java`
- `src/main/java/com/softropic/skillars/platform/security/service/EmailAddressAvailability.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/service/EmailChangeService.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/service/EmailChangeTokens.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/service/ParentRegistrationService.java`
- `src/main/java/com/softropic/skillars/platform/security/service/PasswordResetService.java`
- `src/main/java/com/softropic/skillars/platform/security/service/PlayerRegistrationService.java`
- `src/main/java/com/softropic/skillars/platform/security/service/RegistrationDuplicateGuard.java`
- `src/main/java/com/softropic/skillars/platform/security/service/UserProfileService.java`
- `src/main/resources/db/migration/V231__user_email_login_case_insensitive.sql` (new)
- `src/main/resources/db/migration/V232__email_change_requests.sql` (new)
- `src/main/resources/i18n/messages.properties`
- `src/main/resources/i18n/messages_de.properties`
- `src/main/resources/i18n/messages_en.properties`
- `src/main/resources/i18n/messages_fr.properties`
- `src/main/resources/mails/emailChange.html` (new)
- `src/main/resources/mails/emailChangeAddressInUse.html` (new)
- `src/main/resources/mails/emailChangedNotice.html` (new)
- `src/test/java/com/softropic/skillars/platform/admin/service/GdprErasureServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileSelfEditIT.java`
- `src/test/java/com/softropic/skillars/platform/notification/service/EmailTemplateFileParityTest.java`
- `src/test/java/com/softropic/skillars/platform/security/api/AccountManagementFacadeIT.java`
- `src/test/java/com/softropic/skillars/platform/security/api/EmailChangeResourceIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilterTest.java`
- `src/test/java/com/softropic/skillars/platform/security/repo/UserIdentityCaseIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/security/repo/UserIdentityCaseTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/security/service/CoachRegistrationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/security/service/EmailAddressAvailabilityTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/security/service/EmailChangeServiceIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/security/service/ParentRegistrationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/security/service/PasswordResetIT.java`
- `src/test/java/com/softropic/skillars/platform/security/service/PlayerRegistrationServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/security/service/UserProfileServiceIT.java`
- `docs/dev-docs/**` (T10b, see Completion Notes)

### Change Log

- 2026-11-05: created (ready-for-dev).
- 2026-11-05: revised after /mto-story-review: F1-F15 folded in; owner decisions R1 (one change per undo window), R2
  (erasure cleanup; suspended-account undo keeps the suspension), R3 (erasure hold replaces the 72 h cool-off), R4 (no id
  lookups; F14 check first), R5 (registration half + V231 lowercase backfill and `lower()` unique indexes).
