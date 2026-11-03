# Story skillars-deferred-167: per-session JWT revocation and the refresh-chain follow-ups

Status: ready-for-dev

<!-- CREATED 2026-11-03 (/bmad-create-story). Owner-requested bundle of five items filed by the reviewer
arms on PR #413 (skillars-deferred-166, merged as f0ad7706) and decided by the owner on 2026-11-03; each
decision is recorded as an "OWNER DECIDED 2026-11-03" annotation in deferred-work.md, with the rejected
alternatives and the reasons. Do not re-litigate those. One story and one migration slot because all five
touch main.refresh_tokens or its callers, and parallel PRs on this table collide (see the merge-hotspots
note in deferred-work.md).

TWO DESIGN POINTS WERE CORRECTED DURING STORY CREATION, after reading the code rather than the filed
notes. Both are flagged in Dev Notes under "Corrections to the filed items" and the owner confirmed the
first:
 - AC7's rule is NOT "measure the window from the chain's live end". That does not fix the reported case:
   when the client is exactly one hop behind, the live end's createdAt IS the presented row's rotatedAt,
   so the window fails at the same instant either way. The owner re-decided: keep the window and the
   rotation untouched, and stop answering an intact-live-chain-outside-the-window with ACCOUNT-WIDE
   revocation.
 - AC3's claim cannot be called SESSION_ID: that name is taken by the browser session id and is compared
   by isTokenFixed. -->

## Story

As the platform's security owner,
I want a logout (or any deliberate chain revocation) to kill that session's standing access token within
one DB-reauth cycle, without signing the user out of their other devices,
so that a copied `potc` cookie stops being a session that outlives the logout that was meant to end it.

## Acceptance Criteria

**AC1 — V225 migration.** One migration, `V225__refresh_token_session_id_and_validate_successor_fk.sql`,
that: adds `main.refresh_tokens.session_id uuid` (nullable, no backfill, no default); creates a plain
btree index on it; and runs `ALTER TABLE main.refresh_tokens VALIDATE CONSTRAINT
fk_refresh_tokens_replaced_by`. It opens with `SET LOCAL lock_timeout = '5s'`, carries a
`-- migration-lint: allow-blocking-index` marker with a row-count/build-time reason immediately above the
`CREATE INDEX`, and `COMMENT ON COLUMN` for the new column. `MigrationConventionLintTest` stays green.

**AC2 — the chain carries a session id.** `AuthService.login` mints a fresh `session_id` for the token row
it creates. `AuthService.decideAndRotate` copies the **predecessor's** `session_id` onto the successor it
persists — never a new one — so one id spans a whole rotation chain. A row whose `session_id` is null (every
row written before V225) is treated as "unknown", never as a match.

**AC3 — the JWT carries it.** A new claim, name **not** `sessionId` (that is taken; see Dev Notes), is
written by `TokenCreatorImpl.toClaims` from a value the caller supplies, and is:
 - set at login and at refresh rotation from the row's `session_id`;
 - **explicitly carried over** by `JwtManagerImpl.extendTtlOfToken`, the way `SESSION_ISSUED_AT` is — that
   method rebuilds the claim map from scratch, so an unforwarded claim is silently dropped;
 - set on the DB-reauth branch (`renewLoginToken`) from the request's `rtkn` cookie when the incoming JWT
   has no such claim, which is what lets sessions minted before this story acquire one.
`ClaimsExtractorImpl` gains a reader that tolerates `Integer`/`Long`/`String` the way
`extractSessionIssuedAt` does, and returns null for an absent claim. When the DB-reauth branch has no
`rtkn` cookie to derive from either — a client holding only `potc` — the claim stays absent and AC5
applies; that is the one case this story cannot close, and it is exactly the attacker AC4 targets, so say
so in the javadoc rather than implying full coverage.

**AC4 — the filter rejects a dead chain.** `JWTAuthorizationFilter`'s DB-reauth branch, beside the existing
`assertSessionNotRevoked`, refuses a JWT whose claimed session has **no live refresh token** — no row with
that `session_id` that is `used = false AND revoked_at IS NULL AND expires_at > now()`. It throws the same
`AuthorizationException(..., SecurityError.ACCOUNT_NOT_LOGIN_ABLE)` shape. Exactly one indexed query, only
on that branch: the sliding-TTL fast path stays DB-free, which is the property
`skillars-deferred-144 AC8` deliberately bought back.

**AC5 — a missing claim fails OPEN, and that is deliberate.** A JWT with no session claim skips AC4's check.
Failing closed would sign out every live session on deploy, and `extendTtlOfToken` cannot invent the claim.
The javadoc states the limit precisely (the pattern `isBeforeSessionEpoch` now follows), and a follow-up to
flip it to fail-closed one release later is filed in `deferred-work.md` by this story.

**AC6 — logout kills the JWT.** An IT proves the end-to-end property: log in, keep the `potc` cookie, log
out, and the JWT is refused on the next DB-reauth cycle while a second device's session keeps working. The
test forces the cycle rather than waiting 5 minutes (see Dev Notes for how).

**AC7 — an intact live chain is never account-wide theft.** `resolveGraceSuccessor` distinguishes three
outcomes, not two: a live successor (redirect, unchanged), **a live chain whose rotation is outside the
window** (new), and no live chain at all (genuine reuse, unchanged). The middle case is answered with
`401 + clearAuthCookies` and **no writes at all** — not `markAllUsedByUserId` +
`invalidateSessionsForUser`. It deliberately does not revoke the chain either: the live end may be a
legitimate sibling tab, and the presented row is already `used`, so it cannot be traded in regardless.
An IT waits out the 30 s window (via the injectable clock, not a sleep) and asserts the other device
survives and the account epoch is untouched.

**AC8 — the revoke-all ordering.** In `AdminUserService.suspendUser`, `AgeVerificationService.blockAccount`
and `AccountSuspensionEventListener.suspend`, the bulk revoke runs **before** the epoch stamp, closing the
two-statement window in which a concurrently committed successor carries a `created_at` after the epoch and
passes `isBeforeSessionEpoch`. The order is free: the revoke is `REQUIRES_NEW` on `refresh_tokens`, the
stamp is `MANDATORY` on the user row. `AdminUserService.reactivateUser` additionally revokes the account's
refresh tokens, so a suspend-then-reactivate cannot restore the session the suspension ended.
`AuthService.isBeforeSessionEpoch`'s javadoc is updated: it currently names these three callers as the
documented limit, and that paragraph becomes wrong once this AC lands.

**AC9 — the sweep cannot deadlock the chain walks.** `RefreshTokenRepository.deleteExpiredTokens` acquires
in ascending `id` order (JPQL bulk `DELETE` cannot carry `ORDER BY`, so this becomes a native delete over an
ordered, locked subselect), which removes the lock cycle against the chain walks' ascending-id order. And
`AuthCleanupService.purgeExpiredRefreshTokens` no longer lets a lost deadlock escape the `@Scheduled`
method: it is logged at `warn` and the next hourly run retries. `@SchedulerLock` semantics and the
annotation order asserted by `SchedulerLockTransactionOrderingIT` are preserved, and
`NativeModifyingVersionAuditTest` stays green (see Dev Notes — a native modifying query on a versioned
entity is audited).

**AC10 — the 7-day cap is now real, and tested.** AC4 makes the refresh chain's life an absolute bound on
the session: an active JWT whose chain has fully expired or been swept is refused on the next cycle, where
today it slides indefinitely as long as the user keeps clicking. This is a behaviour change, intended, and
it gets its own test plus a line in the docs.

**AC11 — docs.** `docs/session-refresh-mechanism.md` (the claim, the new check, the 7-day cap, the
fail-open limit), `docs/security-api-endpoints.md` where it describes logout's reach,
`docs/dev-docs/security/index.html` plus a rebuilt `search-index.js`, and
`docs/deployment/provisioning-checklist.md` for V225.

**AC12 — `/pr-review` on the final commit, to a clean round.** Run it, fix what it finds, run it again,
until a round reports nothing actionable. One round is not enough: on #413 rounds 2 and 3 each found real
defects introduced by the previous round's own fix.

## Tasks / Subtasks

- [ ] Task 1: V225 migration (AC1)
  - [ ] `session_id uuid` + btree index + `VALIDATE CONSTRAINT`, `SET LOCAL lock_timeout`, column comment
  - [ ] Justify the `allow-blocking-index` marker with a row count and a build-time estimate
  - [ ] `mvn -q -Dtest=MigrationConventionLintTest test`
- [ ] Task 2: entity + repository (AC2, AC4, AC9)
  - [ ] `RefreshToken.sessionId`; a finder for "a live row exists for this session id"
  - [ ] `deleteExpiredTokens` → native, ordered, locked subselect
- [ ] Task 3: mint and carry the claim (AC2, AC3)
  - [ ] `SecurityConstants`: the new claim name; `toClaims` parameter; `ClaimsExtractorImpl` reader
  - [ ] `login` mints the id; `decideAndRotate` copies the predecessor's
  - [ ] `extendTtlOfToken` carries it over; `renewLoginToken` derives it from the `rtkn` cookie when absent
- [ ] Task 4: the filter check (AC4, AC5, AC6, AC10)
  - [ ] The chain-liveness refusal beside `assertSessionNotRevoked`, with a precise javadoc
- [ ] Task 5: the grace-window outcome (AC7)
  - [ ] Third outcome from `resolveGraceSuccessor`; map it to 401 + cookies, no writes
- [ ] Task 6: the revoke-all ordering (AC8)
  - [ ] Swap the two statements in the three callers; revoke on `reactivateUser`
  - [ ] Correct `isBeforeSessionEpoch`'s javadoc
- [ ] Task 7: the sweep guard (AC9)
- [ ] Task 8: tests (AC6, AC7, AC9, AC10 and regression)
- [ ] Task 9: docs (AC11)
- [ ] Task 10: `/pr-review` to a clean round (AC12)

## Dev Notes

### Corrections to the filed items — read these first

**1. The grace-window rule in `deferred-work.md` would not have fixed the bug.** The entry proposed
measuring the 30 s "relative to the chain's live end rather than the presented row's `rotatedAt`". But in
the reported scenario the client is exactly **one hop behind**, and the live end was created by the rotation
of the presented row — the two timestamps are the same instant, so the window expires at the same moment
under either rule. It only helps when some other client keeps rotating the chain forward. The root cause is
divergence, not the clock: both responses carry `Set-Cookie: rtkn`, the jar keeps whichever arrived last,
and when that is the winner's the client is left permanently one hop behind. The owner re-decided on
2026-11-03: keep the window and the rotation exactly as they are, and remove the account-wide blast radius
(AC7). The harm in the report was `markAllUsedByUserId` + `invalidateSessionsForUser`, not the 401.

**2. `SESSION_ID` is taken.** `SecurityConstants:145` `SESSION_ID = "sessionId"` is the **browser** session
id, sourced from `RequestMetadataProvider.getClientInfo().getSessionId()` (the `ION` cookie, falling back to
`nym`, then a random `ShortCode`), and `TokenValidatorImpl.isTokenFixed` compares it on every request —
a mismatch throws `JWTTheftException`. Reusing or shadowing that name would break client-fixation
detection. Pick a new, unmistakable name (e.g. `RTKN_SESSION = "rtsid"`) and do not touch `SESSION_ID`.
Names already in use, none of which the new claim may collide with: `sub`, `iat`, `exp`, `roles`,
`dbRToken`, `sessIat`, `sessionId`, `clientId`, `userAgent`, `displayName`, `gender`, `busId`,
`otpEnabled`, `opfSeed`.

### How the JWT is actually minted (verified at HEAD, not assumed)

One builder, three paths — `TokenCreatorImpl.toClaims(Principal, Long dbRefreshToken, boolean isLoggedIn,
String seed)` at `TokenCreatorImpl.java:46`, then `generateTokenFromClaims` at `:36`:

| path | `JwtManagerImpl` | has a Principal? | has the request? |
|---|---|---|---|
| login | `:41` `createLoginToken(res, principal)` | yes (from the DB) | **no** |
| DB-reauth re-mint | `:54` `renewLoginToken(res, principal)` | yes (just authorized) | **no** — the filter has `req` and must pass what is needed |
| sliding TTL | `:74` `extendTtlOfToken(req, res)` | only one **reconstructed from the incoming JWT** | yes |

- `extendTtlOfToken` **re-signs from a fresh map** (`:79`). Only `DB_REFRESH_TOKEN` (`:77`) and
  `SESSION_ISSUED_AT` (`:87-90`, when non-null) are carried over by hand; everything else is either
  re-derived by `toClaims` or **silently dropped**. `OPF_SEED` is the live proof: no main-code caller sets
  it and nothing notices. A new claim MUST be forwarded here explicitly, or it evaporates on the first
  request after a mint.
- The Principal inside `extendTtlOfToken` comes from `ClaimsExtractorImpl.extractPrincipal` (`:70`), which
  hardcodes `password("protectedPassword")`, `enabled(true)`, `phone(" ")` and **never** sets
  `securitySessionInvalidatedAt`. It carries no DB state, so nothing on that path can look the session up.
- `hasDbRefreshTokenExpired` (`TokenValidatorImpl.java:48`) is a pure **claim** test — `DB_REFRESH_TOKEN`
  (`"dbRToken"`) vs now — not a DB read and nothing to do with the `rtkn` row. `createLoginToken` and
  `renewLoginToken` set that claim to `now + DB_REFRESH_TOKEN_INTERVAL` (5 min) and `extendTtlOfToken`
  deliberately does not advance it. That is precisely why the DB-reauth branch runs at most once per
  5 minutes, and why AC4's query is affordable there and nowhere else.
- Nothing validates the claim set: the only parse site is `ClaimsExtractorImpl.extractClaims` (`:41`),
  which verifies the signature and `exp` and then reads key-by-key off a `Map`. `Jwts.builder().claims(map)`
  accepts arbitrary keys, and no test pins an exact key set. An extra claim is safe — but it rides in the
  `potc` cookie on every request, so keep the value compact (a uuid string, not a JSON blob).
- Read-side precedent to copy exactly: `extractSessionIssuedAt` (`ClaimsExtractorImpl.java:113`) casts via
  `(Number)` because JJWT deserializes an integral claim as `Integer` or `Long` depending on magnitude. A
  `claims.get(name, Long.class)` would throw. A uuid-as-String claim avoids the issue entirely — prefer it.

### The filter branch AC4 lands in

`JWTAuthorizationFilter.attemptAuthorization` (`:194`): `isTokenFixed` → `hasDbRefreshTokenExpired` splits
the two branches. The DB branch (`:206-222`) does `daoAuthProvider.authorize(...)`, then
`assertSessionNotRevoked(req, authorizedPrincipal)` (`:218`, defined `:259`), then `renewLoginToken`
(`:221`). The fast path (`:223-248`) does `checkAuthorities` + `extendTtlOfToken` and **no DB work by
design** — the comment at `:228-246` records that `skillars-deferred-144 AC8` deliberately removed a
per-request `refresh_tokens` query from it and that bounding exposure to `DB_REFRESH_TOKEN_INTERVAL` is the
accepted contract, the same bound a LOCKED account already relies on. Do not add a query to the fast path.

`assertSessionNotRevoked` is the model for AC4's javadoc: a null epoch returns early, and a missing or stale
`SESSION_ISSUED_AT` fails closed. AC4 inverts the latter on purpose (AC5) and must say why.

### The refresh decision as it stands (AuthService at HEAD)

`refresh` (`:205`) runs `decideAndRotate` (`:307`) inside `transactionTemplate`, catches
`ObjectOptimisticLockingFailureException | PessimisticLockingFailureException` → 401, then acts on a sealed
`RefreshOutcome permits Rotated, Unknown, Reuse, Revoked, Denied` (`:505`) **outside** the transaction, so
every revoke, cookie clear and throw happens after the row locks are released. Keep that shape: it exists
because a `REQUIRES_NEW` revoke self-deadlocks (55P03) against a row the caller already wrote, and because
an in-transaction revoke was rolled back by the throw.

`decideAndRotate` order: lock the presented row → `revokedAt != null` → `Revoked` → load the user → epoch →
`used?` → `resolveGraceSuccessor` → expiry → `ensureAccountIsLive` → rotate, building the `Principal` while
the row is still managed (`User.authorities` is LAZY and `open-in-view` is false).

`resolveGraceSuccessor` (`:450`) walks `replacedById` up to `MAX_GRACE_HOPS = 8`, checks each successor
belongs to the same account, returns `revokedChain()` on a revoked hop, stops at the first unused row, and
**only then** tests the window at `:485` (`presented.getRotatedAt() + REFRESH_GRACE_WINDOW`). AC7 adds a
third outcome to the `GraceResolution` record (`:495`) for "liveEnd found, window failed" and threads it
through `decideAndRotate`'s `used` branch (`:332-344`) to a new `RefreshOutcome`. Do not reuse `Revoked`:
its message and semantics ("session has ended") are wrong here, and its branch is asserted by
`refresh_withATokenRevokedByLogout_returns401AndLeavesOtherSessionsAlive` (`AuthResourceIT:927`).

### The revoke-all callers, exactly as they are today (AC8)

Epoch **first**, revoke second — the three this story fixes:
 - `AdminUserService.java:164-165` (`@Transactional` plain): `invalidateSessionsForUserInCurrentTransaction`
   then `markAllUsedByUserId`
 - `AgeVerificationService.java:263-265` (private `blockAccount`, inherits `applyOutcome`'s tx): same order
 - `AccountSuspensionEventListener.java:97-99` (both entry points `REQUIRES_NEW`): same order
Revoke **first** already, leave alone: `UserProfileService.java:175-178`,
`PasswordResetService.java:74-76`, `AuthService.java:274-275` (the only caller of the `REQUIRES_NEW` epoch
variant). `GdprErasureService.java:810` revokes and **never** stamps the epoch — out of scope, it also sets
`locked = true` and `activated = false`, which `ensureAccountIsLive` catches.

The two epoch writers are `UserRepository.invalidateSessionsForUser` (`:161-164`, `REQUIRES_NEW`, void) and
`invalidateSessionsForUserInCurrentTransaction` (`:209-213`, **`MANDATORY`**, returns `int`). The column is
mapped `insertable = false, updatable = false` on `User.java:166-167`, so it can only be written by those
queries — a managed `setSecuritySessionInvalidatedAt` is a no-op. `reactivateUser`
(`AdminUserService.java:181-212`) writes only `user.unlock()` + `saveAndFlush` + `audit`.

### The sweep (AC9)

`AuthCleanupService.java:38-45`: `@Scheduled(cron = "0 0 * * * *")` + `@Transactional` + `@SchedulerLock`
(`lockAtMostFor = PT10M`, `lockAtLeastFor = PT1M`), **no try/catch anywhere in the class**, and no global
scheduler error handler exists (`ShedLockConfig` only supplies the `LockProvider`). The delete is JPQL at
`RefreshTokenRepository.java:233-236`: `DELETE FROM RefreshToken r WHERE r.expiresAt < CURRENT_TIMESTAMP` —
no ORDER BY, no LIMIT, no batching. JPQL bulk DELETE cannot take an ORDER BY, hence AC9's native rewrite.
Two things to respect: `SchedulerLockTransactionOrderingIT:169` asserts the annotation order, and
`NativeModifyingVersionAuditTest` audits native modifying queries against versioned entities — a DELETE
needs no version bump, but the audit may need the new query acknowledged rather than silently added.
`V224`'s header explains why the `idx_rt_replaced_by` partial index exists: `ON DELETE SET NULL` runs a
referential-action search per deleted row, and that path is **currently untested** against a populated
`replaced_by_id`.

### What AC4 costs, and how its refusal reaches the client

- One query per authenticated user per `DB_REFRESH_TOKEN_INTERVAL` (5 min), not per request, and it is an
  index lookup on `session_id` returning a boolean. Write it as a scalar existence query
  (`existsBySessionIdAnd...`), never as a fetch of the row: the persistence-context trap in the hard
  constraints below is what a fetch here would reintroduce.
- It throws the same `AuthorizationException(..., SecurityError.ACCOUNT_NOT_LOGIN_ABLE)` that
  `assertSessionNotRevoked` already throws on this branch, so the HTTP status, the error key and the SPA's
  handling are unchanged and **no frontend work is in scope**. If you find yourself adding a new
  `SecurityError`, stop: the client cannot distinguish "epoch revoked" from "chain dead" and does not need
  to.
- AC4 needs **no** change to `markAllUsedByUserId`, even though it stamps `used` without `revoked_at` (a
  separate low item in `deferred-work.md`). AC4's liveness predicate requires `used = false`, so a bulk
  revoke already makes every row non-live. Do not widen the scope to fix that item here.
- `session_id` is **not** a credential and must never be treated as one: it identifies a chain, it is
  readable in any JWT, and AC4 only ever asks "does a live row exist for it". Nothing may be authorised by
  possession of it.

### Testing

- `AuthResourceIT` extends `AbstractIntegrationTest` and declares only `@Sql({SecurityIT.SEC_DATA_SQL_PATH})`
  (`:63`). Adding test methods, plain `@Autowired` fields and private helpers does **not** fork a Spring
  context — the comment at `:114-119` already records that. Adding a class-level `@TestPropertySource`,
  `@Import`, `@DirtiesContext`, `@DynamicPropertySource`, a nested `@TestConfiguration`, or **any**
  `@MockitoBean`/`@MockitoSpyBean` field **would**: the cache key includes each override field's
  annotation, type **and name**. `RefreshTokenRepository` is already an inherited `@MockitoSpyBean` on
  `AbstractIntegrationTest:148-170` — use the inherited field, never re-declare it.
- The gate: `.github/scripts/assert-context-count.sh build.log 17` (`pr-build.yml:127`), ceiling **17** =
  14 pinned `AbstractIntegrationTest` configurations + 3 allowlisted slices. It fails on a full cache, on
  `missCount - size > 1`, and on `missCount > 17`. `IntegrationTestConventionTest` pins
  `EXPECTED_DISTINCT_CONTEXT_KEYS = 14` and `EXPECTED_TEST_PROPERTY_SOURCE_COUNT = 4`.
- **Every write inside a test method body must go through `commitWrite`** (`AuthResourceIT:1472`,
  `transactionTemplate.execute(status -> jdbcTemplate.update(...))`). `spring.datasource.hikari.auto-commit`
  is `false`, so a bare `jdbcTemplate` write reports a row count and is then rolled back on connection
  release — invisible to the app under test. This has bitten this story's predecessor.
- Helpers already there to reuse, not re-invent: `revokedAtOf` (`:1477`), `liveTokenCountFor` (`:1484`),
  `sessionEpochOf` (`:1491`), `loginAndGetRtkn` (`:1499`), `rtknValuesIn` (`:1515`), `usedFlagOf` (`:1528`),
  `cookieHeaders` (`:1541`), `extractCookieValue` (`:1547`), `sha256Hex` (`:1557`).
- The two tests AC7 must not break, and must extend:
  `refresh_staleCookieInGraceWindow_redirectsToItsOwnSuccessorAndLeavesTheOtherDeviceAlone` (`:888`) and
  `refresh_accountLockedAndStaleCookieReplayedInGraceWindow_revokesTheSuccessorToken` (`:766`). Neither
  re-checks after the window closes — that gap is AC7's new test.
- Advancing time: the codebase injects the clock through `ClockProvider`, which both `AuthService` and the
  JWT paths read. Use it for AC7's out-of-window case and for AC6/AC10's forced DB-reauth cycle; do not
  `Thread.sleep` 30 s and do not wait 5 real minutes. Confirm how existing tests pin `ClockProvider` before
  writing these — if no IT-level hook exists, mutate `rotated_at` / `db_refresh_token` state via
  `commitWrite` instead, and say so in the Completion Notes.
- `AuthCleanupServiceTest` is a Mockito unit test that only verifies delegation and the `@SchedulerLock`
  attributes; AC9's ordering and the swallowed-deadlock guard need real coverage, not another `verify`.
- `AdminUserServiceTest` has **no** test for `suspendUser` (only two `reactivateUser` tests);
  `AdminUserManagementIT` covers suspend/reactivate end to end, including
  `suspend_locksAccount_revokesSessions_andAudits` (`:147`) and `reactivate_unlocksAccount_andLoginWorksAgain`
  (`:287`) — the latter is the test AC8's reactivate change will alter.

### Hard constraints (each one has already caused a defect in this area)

- A finite `jakarta.persistence.lock.timeout` is **inert on PostgreSQL**: Hibernate's dialect special-cases
  only `NO_WAIT` (0) and `SKIP_LOCKED` (-2). A 5 s hint was added and removed on #413 after both reviewer
  arms caught it. Do not reintroduce one; `findByTokenHashForUpdate`/`findByIdForUpdate` wait deliberately.
- A `REQUIRES_NEW` revoke **self-deadlocks** (55P03 → 409) if the calling transaction already wrote the
  same row. #413 hung an IT phase for 19 minutes on exactly this.
- A locked read returns an **already-managed** instance without refreshing it, so an unlocked pre-check
  that loads the entity makes the later locked read stale and the save fails its version check — the lock
  then protects nothing. Use a scalar existence query for pre-checks (the `existsByTokenHashAndRevokedAtIsNotNull`
  pattern).
- Java 17: no switch patterns over the sealed interface; `instanceof` chains only.
- Migrations: additive first, FK/CHECK `NOT VALID` then `VALIDATE` later, `SET LOCAL lock_timeout` (not
  plain `SET`), `allow-*` markers immediately above their own statement with a real reason. `VALIDATE
  CONSTRAINT` is **not a token the linter knows**, so `MISSING_LOCK_TIMEOUT` will not fire on it — the
  `SET LOCAL` is a rule-7 obligation you must honour anyway. V225 would be the repo's **first** executed
  `VALIDATE CONSTRAINT`; there is no in-repo precedent to copy, only V224's prose promise to ship it.
- `docs/deployment/provisioning-checklist.md` must be updated for a risky-migration or config change.

### Project Structure Notes

Everything lands in existing packages: `platform.security.{repo,service,infrastructure.jwt,infrastructure.jwt.filter}`,
`platform.admin.service`, plus `infrastructure.security.SecurityConstants` for the claim name. No new module,
no new endpoint, so no new `@PreAuthorize` surface. `SecurityConstants` sits in `infrastructure` and stays
business-agnostic — a claim name is a transport constant, consistent with `SESSION_ISSUED_AT` living there.

`V225` is a known parallel-PR collision point: if another branch merges a `V225` first, this migration is
renamed, not rebased around. The same applies to `docs/dev-docs/assets/search-index.js` and
`docs/deployment/provisioning-checklist.md`, which conflict on almost every parallel docs change — rebuild
and re-apply rather than resolving them by hand.

### References

- Decisions and rejected alternatives: `_bmad-output/implementation-artifacts/deferred-work.md`
  ("OWNER DECIDED 2026-11-03" annotations, skillars-deferred-166 section)
- Predecessor: `_bmad-output/implementation-artifacts/skillars-deferred-166-logout-successor-revocation-and-refresh-grace-window-scope.md`
- `docs/deployment/migration-conventions.md` rules 1, 3, 4, 7 and the PR checklist
- `docs/session-refresh-mechanism.md`, `docs/security-api-endpoints.md`
- Eval cases for the predecessor's defects: `evals/review/cases/logout-successor-revocation.yaml`,
  `evals/review/cases/refresh-grace-window-account-wide.yaml` (both `fix_merge: f0ad7706`)

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

### Change Log

- 2026-11-03 — created (/bmad-create-story) from five owner-decided items filed on PR #413. Two filed
  design points corrected against the code during creation: the grace-window rule (the live-end measurement
  does not fix the reported case) and the claim name (`sessionId` is taken by client-fixation detection).
