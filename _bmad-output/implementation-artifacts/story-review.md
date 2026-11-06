# Story review: skillars-deferred-172 (account email change: verified, reversible, safe)

- **Audit date:** 2026-11-05
- **Real HEAD:** `70aae81d docs(story): deferred-172 spells out the dev-docs updates` (worktree `skillars-deferred-168`, branch
  `skillars-deferred-172-account-email-change`). `src` is identical to master `b80569ec` (`git diff --quiet origin/master HEAD -- src`).
- Every citation was checked against this HEAD, not against any SHA the story names.
- Method: four read-only verification agents (citations, ledger/precedent, mechanistic, corner cases), then my own
  adversarial re-read of the findings that change the story (section 6).

## 1. Citation verification (Layer 1: 60 citations)

56 MATCH, 4 DRIFTED by a few lines, 0 missing.

| Citation | Verdict | Note |
|---|---|---|
| `AccountManagementFacade.registerAccount:129-150` | DRIFTED | Method runs to `:158` |
| `changeEmail:171-188` | MATCH (ends `:187`) | `@RateLimited(key="change_email", capacity=5, duration=60)` |
| `UserProfileService.updateUserEmail:115-150` | MATCH (ends `:151`) | |
| `UpdateEmailDialog.vue:156-171` | MATCH (ends `:172`) | |
| All others (`:231`, `:143`, `:218`, `:81-98`, `ParentRegistrationService:94`, `LoadUserByUserNameService:31-32`, `ApiAdvice:154-155`, `ProfileResource:85/89-99`, the 5 `findOneByLogin` sites, `:175-178`, `AccountChangeEmailListener:40-77`, `User.java:58`, `:445-456`, `RegistrationDuplicateGuard`, its listener, `enqueueEmail`, `AuthService:156/224/742-750`, `RandomUtil`, `EmailVerificationToken`, `PasswordResetService:41-52/74-76`, `RefreshTokenRepository`/`UserRepository` revocation methods, `DB_REFRESH_TOKEN_INTERVAL`, `UnsubscribePage`, `AppEndpoints`, `EmailTemplate:41`, `EmailTemplateFileParityTest:42`, V227, `SecurityUtil:381`, `FrontendLinkBuilder`, V230, `deferred-work.md:22-28/:336`, the eval case, i18n keys) | MATCH | |

**Factual claims the citations disprove (story text to correct):**
- Background says the account facade lowercases `login`. It does not: `:140` sets login to the typed email, `toUser:227`
  copies it raw; only `email` is lowercased (`:231`). `RegistrationDuplicateGuard:76` says so itself.
- The template checklist says to add an `EMAIL_CHANGE` `NotificationCatalog.of` case. It exists (`NotificationCatalog:73`), and
  the subject key `email.change.title` exists in all four bundles with the wrong wording for a confirm mail ("Email Address
  Changed", `messages.properties:55`).

## 2. Ledger and precedent attribution (Layer 2)

| Claim | Sources checked | Verdict |
|---|---|---|
| RS03 at `deferred-work.md:336`, tagged picked up | ledger, git log | Accurate |
| `:22-28` "flip `fix_pr: pending` to the PR number" | ledger `:22-32`, `evals/review/README.md:110-120`, `validate.py`, #413/#414 precedent | **Incomplete**: closing a case also moves it out of `_drafts/`, drops the `pending-` prefix (file and `id`), keeps `input_rev` until merge, then adds `fix_merge` and removes `input_rev` |
| The eval case is RS03's | case file, `real-sample.md` S04/S16 | Accurate, but it expects **two** findings: F1 email change (this story) and **F2 `registerAccount`/`toUser` raw-case login** (not in the story) |
| RS10+RS11 / RS03+RS04 queued behind payments (`:35-40`) | ledger, sprint-status | Accurate ("Bundle 1" is conversation wording, not ledger) |
| No other open ledger bullet to bundle or that contradicts | wide ledger greps | Accurate (`:3586` supports D1; `:307-323` supports the revocation order; `:324-334` related note) |
| `CREATION_DUP` owner notice precedent | guard + listener, git log (#441) | Accurate paraphrase |
| sha256 token hashing, `EmailVerificationToken` shape, revocation order, V227, `UnsubscribePage` | source, git log | Accurate |
| New public endpoints go in `PUBLIC_ENDPOINTS` (`AppEndpointsConventionTest`) | `AppEndpoints`, test | Partly: the test only checks pattern anchoring; the class-level `@PreAuthorize` on `ProfileResource` is the real obstacle (F6) |
| "New table, empty, additive: no lint opt-out needed" | `MigrationLint:793-809`, rules 1/3/5/7, V220/V222/V227/V228 | **False** (F5) |

## 3. Mechanistic claims (Layer 3)

| Claim | Verdict | Evidence |
|---|---|---|
| Two `PROFILE_CHANGE` mails today, neither via the outbox | HOLDS | `AccountChangeEmailListener:40-42` is `@Transactional @EventListener` (synchronous, no action filter); facade `buildRecipient:218` reads the mutated entity |
| Mixed-case login locks the user out | HOLDS | `AuthService.login:113` and `LoadUserByUserNameService:31-32` both lowercase then exact-match; no case-insensitive lookup exists; registration services store `req.email()` raw, so such accounts already exist |
| `businessId` = user id, `requireCurrentUserId` works | HOLDS | `Principal.instanceFrom:185` |
| The 5 `UserProfileService` lookups are the stale-login sites | PARTIAL | Also `UserService:56` (`GET /v1/account`) and `:65` (`GET /api/account/profile`) |
| Revocation order and propagation; no self-deadlock | HOLDS | `markAllUsedByUserId` REQUIRES_NEW touches only `refresh_tokens.used/version` (no FK re-check on `main.user`); `suspendUser` precedent locks, writes the user row, then revokes |
| Access tokens die at the next DB re-check (≤ 5 min) | HOLDS | `JWTAuthorizationFilter` re-check every `DB_REFRESH_TOKEN_INTERVAL` |
| Reset destination is `user.getEmail()` via the facade; completion looks up by key only | HOLDS | `sendPasswordResetMail:87` → `buildRecipient`; `completePasswordReset:63-65` |
| `RegistrationDuplicateGuard` reusable | PARTIAL | Same package as `UserProfileService` but wired to registration (bcrypt timing, `flow`, phone, hard-coded bucket, mails `user.getEmail()`, exact-case lookup); `AccountManagementFacade` (`security.api`) cannot reach it |
| `enqueueEmail` usable from the public endpoints | HOLDS | `MANDATORY`, no principal dependency. Note: raw tokens end up in the outbox payload JSON (no purge), so "only the hash is stored" is not true there |
| A random encoded password is harmless | HOLDS, with a trap | No password history or counters; but `preparePasswordReset` throws for a locked or not-activated user (F3) |
| Envelope CHECK re-union method | PARTIAL | `EMAIL_CHANGE` already present; V227 used a single DROP/ADD with `allow-validating-constraint` and `SET LOCAL lock_timeout`, not NOT VALID + VALIDATE |

## 4. Findings that survived re-verification

### F1 (HIGH): the request endpoint leaks whether an address is registered
Design 5: a free address supersedes the user's open request and writes a new one; a taken address writes nothing and
supersedes nothing. A signed-in user who owns mailbox X requests X (gets a link), then requests Y, then clicks the X link: if it
still works, Y is registered. Re-verified from the story's own Design 5 text. **Fix:** supersede any open request on every
non-mismatch path (taken or free), and make the taken path's work (row write vs not) indistinguishable in effect.

### F2 (HIGH): an undo does not cancel the attacker's other requests, so they can take the account back
Design 6 never checks that the request's `old_email` still equals the user's current email; Design 7 restores email and login
but leaves other open requests and other confirmed requests' undo tokens alive; the partial unique index covers only
unconfirmed rows. Trigger: attacker (knows the password) confirms A→B, requests B→C, owner undoes the first change (back to
A), attacker confirms C; or attacker confirms A→B→C and, after the owner's undo, uses the second change's undo token (mailed
to B) to restore B with the reset mail going to B. Re-verified from Design 6/7 text. **Fix (needs an owner rule):** undo
supersedes every open request and invalidates every other undo token of the user; confirm refuses when `old_email` ≠ the
current email; and either allow only one change per undo window or make the reservation/cool-off use the latest request.

### F3 (HIGH): GDPR erasure and locked accounts are not handled
Erasure keeps the user row and tombstones it (`GdprErasureService:422-429`: login and email `deleted.<id>@erased.io`,
`locked`), so `ON DELETE CASCADE` never fires. An open request could then be confirmed, writing the erased person's real
address back over the tombstone; `old_email`/`new_email`/`old_login` survive in the table and keep reserving the address. Undo
on a locked account throws (`User.preparePasswordReset:450-452`), so an admin-suspended owner can never undo. **Fix:** confirm
and undo refuse locked/erased users (owner decides what undo does for a suspended account); erasure deletes or scrubs the
user's rows (V222 precedent).

### F4 (MEDIUM): the 72 h cool-off protects very little as designed
Re-verified: `changePassword` needs only the current password (`UserProfileService:166`), so an attacker who switched the
email (they had to know the password) does not need a reset at all. The owner who knows only the old address cannot start a
reset either: `prepareForPasswordReset` finds the user by `currentEmail` (`PasswordResetService:42`) and the frontend sends the
typed value as `currentEmail` (`account.api.js:43-47`), which is now the new address. The real protection is the undo link
(which randomises the password and sends the reset to the old address). Also reset matching is exact-case, so AC1's "any
casing" does not carry over. **Owner decision:** keep D4 as is, drop it, or replace it (e.g. block `changePassword` and reset
for 72 h after a change unless initiated from the undo flow).

### F5 (MEDIUM): V231 needs lint opt-outs and a lock timeout
`MigrationLint` flags each non-concurrent `CREATE INDEX` without `-- migration-lint: allow-blocking-index` (V220/V222/V228
precedent, even on a new table) and the validating `CHECK` re-union without `allow-validating-constraint` (V227); `SET LOCAL
lock_timeout = '5s'` is needed (the FK locks `main.user`; `EmailRetryScheduler` polls `envelope_entity`); the header must state
the "prod not live, table and code in one release" waiver (rules 1 and 5). The re-union must keep V227's values plus the two
new ones.

### F6 (MEDIUM): the public endpoints cannot live in `ProfileResource`
It has a class-level `@PreAuthorize(HAS_ANY_ROLE)` (`ProfileResource:47-48`), so listing the paths in `PUBLIC_ENDPOINTS` is not
enough. Put confirm and undo in their own controller. "The public axios client" does not exist: use the one `api` instance
(`boot/axios.js:94`), as `UnsubscribePage` does.

### F7 (MEDIUM): `AccountChangeEmailListener` mails `PROFILE_CHANGE` for every action
It has no action filter, so re-publishing `EMAIL_CHANGED` at confirm and adding `EMAIL_CHANGE_UNDONE` would still send
`PROFILE_CHANGE` (with an empty body for the new action, `profileChange.html:10-17`). The story must say the listener skips both.
The audit listener records `login = userInfo.email` and `authenticated = true`; say which address to record and that confirm
and undo are anonymous.

### F8 (MEDIUM): design 9 (id lookups) works against session revocation
Stale tokens only exist after confirm or undo, when the user's sessions were deliberately revoked. Today the login lookups fail
closed for them; id lookups would let a revoked session keep editing the profile (including `changePassword`) for up to 5
minutes. The list of 5 also misses `UserService:56/65`. **Recommendation:** drop design 9 (and its AC8 test) and let the
login lookups fail; or, if kept, apply it everywhere and add a revocation check.

### F9 (MEDIUM): RS03's second half (registration) is not in the story
The eval case's F2 and the ledger bullet (`also S16: AccountManagementFacade.java`) cover `registerAccount`/`toUser` storing the
login as typed; the role registration services do the same. The fix is small (lowercase at `:140`/`toUser`, and in the three
registration services) and shares the root cause; without it the eval case cannot be closed as fixed. The guard's exact-case
lookup also lets `Anna@X.com` register next to `anna@x.com` (case-sensitive constraints).

### F10 (MEDIUM): the shared "taken" helper must not expect one row, and must replace the guard's lookup
Mixed-case duplicates can already exist (case-sensitive constraints, raw storage), so a single-result `lower(email)` query can
throw `IncorrectResultSizeDataAccessException`; use an exists/limit-1 query. Design 10 adds only the reservation check; say the
helper replaces the guard's exact-case lookup, and that a reservation notice goes to the **reserved old address** (the guard
and `notifyUserExists` both mail `user.getEmail()`, which after the change is the new, possibly attacker's, address), with
wording fit for an address the account no longer uses.

### F11 (MEDIUM): rate limiting and the user's own reservation
- `change_email` is per IP and in memory per server; a signed-in user can send confirmation mails to many addresses. Add a
  per-user (and per-target-address) bucket, as registration does.
- A user who changed A→B and wants A back by a new request (not undo) is blocked by their own reservation and mailed the "in
  use" notice; design 3 exempts only the current address. Exempt the user's own reservations.

### F12 (MEDIUM): after confirm or undo the browser may still think it is signed in
`/login` and `/reset-password` are guest-only routes; with the JWT fast path the old session lives up to 5 minutes, so the
"sign in with the new address" step or the undo's reset link redirects to the dashboard and drops the reset key. Both new pages
should clear the local session on success.

### F13 (MEDIUM): AC9's "no page describes the email change today" is false
Re-verified: `docs/dev-docs/security/index.html:186` ("Change email (sends verification)") and
`docs/dev-docs/notification/index.html:456` (`EMAIL_CHANGE`, "which nothing sends"); also `docs/security-api-endpoints.md:183`
and `docs/deployment/provisioning-checklist.md:569`. These must be corrected, not just added to.

### F14 (MEDIUM, pre-existing, needs a check): a stale token can re-authorise as another account
On the DB re-check the filter reloads the user by the token's username (`DaoAuthProvider:34`, `retrieveUser(username)`);
Layer 3 found no comparison of the reloaded user id with the token's `busId`. Once an old address is free again (after undo, or
after the 7-day reservation) and someone registers it, a stale token carrying that login would re-authorise as the new account.
I re-read the username-based retrieval but did not trace the whole filter, so treat this as **verify before dev**: if
confirmed, add a `busId` equality check (small, security-relevant).

### F15 (LOW, story precision)
- AC2 says the owner is notified "once"; with capacity 2 the third attempt is the first dropped.
- Missing guard tests: `MessageBundleParityTest` (all bundles, no English in de/fr, MessageFormat quoting), frontend
  `localeKeyParitySpec.js` and `i18nTemplateKeyResolutionSpec.js`, `NotificationCatalogTest:48`.
- `ChangeEmailDto.password` has no `@NotNull` (BCrypt 500 on null; pre-existing).
- The dialog's `notSameEmail` check is case-sensitive.
- Unspecified: HTTP status for `linkInvalid`, masking of a one-character local part, what an open request does on a password
  change or reset, the request's answer when the notice bucket is exhausted.
- Not covered, worth a ledger line: the Stripe customer email is set only at creation (`StripePaymentGateway:647-648`);
  `FileStorageService.softDelete:328` compares `created_by` with the current login, so pre-change files can't be deleted
  through the generic path after a change.

## 5. Corrections to the story text
- Background: the facade does not lowercase `login`.
- Template checklist: `EMAIL_CHANGE`'s catalog case and subject key exist; reword the subject, don't add.
- AC9 docs: correct the existing statements (F13).
- Eval case: the full close-out procedure (Layer 2 row 2), and it needs F2 too (F9).

## 6. What did not survive re-verification, or was downgraded
- **"Timing difference on the taken path"** (Layer 4 low): the notice is enqueued via the outbox in both cases' transaction; the
  remaining difference is one INSERT. Folded into F1's fix rather than a separate finding.
- **"Outbox keeps raw tokens"** (Layer 3): true, but the same already holds for OTPs and the outbox rows are internal. Kept as a
  note in section 3, not a finding.
- **"NOWAIT lock on double-click confirm"** (Layers 3 and 4): a story-precision point (wrap in `PessimisticLockRetryer` or map
  the error to `linkInvalid`); folded into F15's "unspecified" list.
- **"No FK self-deadlock"**: Layer 3 raised it as a risk to check and refuted it itself; confirmed by the `suspendUser`
  precedent. Not a finding.
- **Queued mails to the old address / opt-outs** (Layer 4 low): pre-existing behaviour of any email change; not specific to
  this design. Dropped.
- **Admin bootstrap does not check reservations** (Layer 4 low): only the bootstrap admin, at boot, with a fixed address. Dropped.

## 7. Recommendation

- **Re-read by me against HEAD:** F1 and F2 (from the story's own Design text), F3 (erasure tombstone code), F4
  (`changePassword` and the reset request inputs), F7 (listener has no filter), F13 (both doc lines). High confidence.
- **Taken from the agents, not re-read by me:** F5 lint rules, F6 `@PreAuthorize`, F9/F10 registration storage and guard
  lookup, F11, F12 route guards, F15 items. Medium-high (two agents agreed on F5, F6, F9).
- **Needs a check before dev:** F14.
- **Verdict:** do not start implementation as written. F1-F3 change the design; F4 and F8 need an owner call; F9 is a scope
  call (recommend including it); F5-F7 and F10-F13 are corrections the story must carry.
