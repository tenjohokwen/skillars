# Story skillars-deferred-167: per-session JWT revocation and the refresh-chain follow-ups

Status: done

<!-- CREATED 2026-11-03 (/bmad-create-story), then REVISED the same day after /mto-story-review (findings
H1, H2, H3, M2, M4, the clock hook and the AC8 overclaim all applied; see the Change Log). Owner-requested
bundle of five items raised on PR #413 (skillars-deferred-166, merged as f0ad7706) — four by the reviewer
arms, the V224 VALIDATE follow-up by that story's own author — and decided by the owner on 2026-11-03; each
decision is recorded as an "OWNER DECIDED 2026-11-03" annotation in deferred-work.md, with the rejected
alternatives and the reasons. Do not re-litigate those. One story and one migration slot because all five
touch main.refresh_tokens or its callers, and parallel PRs on this table collide (see the merge-hotspots
note in deferred-work.md).

TWO DESIGN POINTS WERE CORRECTED DURING STORY CREATION, after reading the code rather than the filed
notes. Both are flagged in Dev Notes under "Corrections to the filed items"; the owner confirmed the first
and the ledger entry carries an AMENDED note recording it:
 - AC7's rule is NOT "measure the window from the chain's live end". That does not fix the reported case:
   when the client is exactly one hop behind, the live end's createdAt is the presented row's rotatedAt plus
   rotation latency, so the window fails at effectively the same moment either way. The owner re-decided:
   keep the window and the rotation untouched, and stop answering an intact-live-chain-outside-the-window
   with ACCOUNT-WIDE revocation. AC7 records what that costs: the case it downgrades was the canonical
   theft signal, so it is now a warn log and a metric only.
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
The header must state the one convention this bends: Flyway runs the migration in a single transaction, so
`ADD COLUMN`'s `ACCESS EXCLUSIVE` lock is held across the `CREATE INDEX` **and** the `VALIDATE` scan, which
defeats rule 3's stated reason for validating in a later migration (that `VALIDATE` alone takes only
`SHARE UPDATE EXCLUSIVE`, so writes continue). Acceptable here — the table is small by construction and
prod is not live — but the linter does not look for this, so the reason has to be written down.

**AC2 — the chain carries a session id.** `AuthService.login` mints a fresh `session_id` for the token row
it creates. `AuthService.decideAndRotate` copies the **rotated row's** `session_id` onto the successor it
persists, so one id spans a whole rotation chain. "Rotated row" is deliberate, not "presented row": on a
grace redirect the row that gets rotated is the chain's live end (`token`, after
`token = resolution.successor()`), not `presented`.
**When the rotated row's `session_id` is null, mint a fresh one for the successor** rather than copying the
null. Rows written before V225 have no id and AC1 adds no backfill, so copying would keep a pre-V225 chain
null through every rotation and a continuously refreshing client would never acquire one. A null
`session_id` is "unknown" and must never match a query — pass the claim only when it is non-null, never as
a `session_id = NULL` predicate, which matches no row and would turn AC5's skip into a rejection.

**AC3 — the JWT carries it.** A new claim, name **not** `sessionId` (that is taken; see Dev Notes), is
written by `TokenCreatorImpl.toClaims` from a value the caller supplies, and is:
 - set at login and at refresh rotation from the row's `session_id`;
 - **explicitly carried over** by `JwtManagerImpl.extendTtlOfToken`, the way `SESSION_ISSUED_AT` is — that
   method rebuilds the claim map from scratch, so an unforwarded claim is silently dropped;
 - set on the DB-reauth branch (`renewLoginToken`) from the just-authorized session, carrying over the
   incoming JWT's claim unchanged.
`ClaimsExtractorImpl` gains a reader that tolerates `Integer`/`Long`/`String` the way
`extractSessionIssuedAt` does, and returns null for an absent claim.

**Do NOT derive the claim from the request's `rtkn` cookie.** An earlier draft of this AC did, "so sessions
minted before this story acquire one". That is dead code: a pre-V225 row's `session_id` is null, so the
derivation yields null for exactly the population it was meant to serve. AC2's fresh-id-on-null rule is
what actually closes that gap, on the next rotation. Dropping the derivation also keeps AC4's "exactly one
query" true, keeps `JwtManagerImpl`/`LoginTokenManager` free of a repository (the filter javadoc at
`JWTAuthorizationFilter.java:83` states that `renewLoginToken` touches no repository), and avoids pairing a
`potc` from one session with an `rtkn` from another — the derived row's `userId` was never checked against
the principal.

The JWT that genuinely cannot be covered is a **pre-deploy** `potc`: it carries no claim, `extendTtlOfToken`
cannot invent one, and AC5 skips it. A post-deploy `potc` always carries the claim, so a thief holding only
that cookie **is** covered. Say exactly this in the javadoc; do not write that "a client holding only
`potc`" is uncovered, which is the opposite of the truth after deploy.

**AC4 — the filter rejects a dead chain.** `JWTAuthorizationFilter`'s DB-reauth branch, beside the existing
`assertSessionNotRevoked`, refuses a JWT whose claimed session has **no live refresh token** — no row with
that `session_id` that is `used = false AND revoked_at IS NULL AND expires_at > now()`. It throws the same
`AuthorizationException(..., SecurityError.ACCOUNT_NOT_LOGIN_ABLE)` shape. Exactly one indexed query, only
on that branch: the sliding-TTL fast path stays DB-free, which is the property
`skillars-deferred-144 AC8` deliberately bought back.

**AC5 — a missing claim fails OPEN, and that is deliberate.** A JWT with no session claim skips AC4's check.
Failing closed would sign out every live session on deploy, and `extendTtlOfToken` cannot invent the claim.
The javadoc states the limit precisely (the pattern `isBeforeSessionEpoch` now follows), and a follow-up to
flip it to fail-closed is filed in `deferred-work.md` by this story. That flip cannot ship until every
pre-deploy JWT is gone, which takes at least one `REFRESH_TOKEN_TTL` (7 days) after this story deploys —
not "one release later" unless a release is that far out.

**AC6 — logout kills the JWT.** An IT proves the end-to-end property: log in, keep the `potc` cookie, log
out, and the JWT is refused on the next DB-reauth cycle while a second device's session keeps working. The
test forces the cycle rather than waiting 5 minutes (see Dev Notes for how).

**AC7 — an intact live chain is never account-wide theft.** `resolveGraceSuccessor` distinguishes three
outcomes, not two: a live successor (redirect, unchanged), **a live chain whose rotation is outside the
window** (new), and no live chain at all (genuine reuse, unchanged). The middle case is answered with
`401 + clearAuthCookies` and **no writes at all** — not `markAllUsedByUserId` +
`invalidateSessionsForUser`. It deliberately does not revoke the chain either: the live end may be a
legitimate sibling tab, and the presented row is already `used`, so it cannot be traded in regardless.
An IT drives the token past the 30 s window (see Dev Notes — **not** via `ClockProvider`) and asserts the
other device survives and the account epoch is untouched.

**What this gives up, which must be stated and instrumented, not glossed.** That branch was the canonical
theft signal: when an attacker steals an `rtkn` and rotates it first, the victim's later replay of the now
stale cookie is exactly "used token, live chain, outside the window", and today it runs
`markAllUsedByUserId` + `invalidateSessionsForUser`, which kills the attacker's session too. After this AC
the victim gets a 401, and the attacker's chain stays live for up to `REFRESH_TOKEN_TTL` (7 days) with no
audit row, no epoch bump and no alert. What is still detected: a replay when the chain has **no** live end.
So: log that case at `warn` with the user id and the presented hash, and count it as a metric, so the loss
is observable even though nothing is written. Today's blast radius is limited because the SPA never calls
`POST /api/auth/refresh` (V224's header records this), so only API and mobile clients can reach it —
but say so in `docs/session-refresh-mechanism.md` rather than leaving the trade-off implicit.

**AC8 — the revoke-all ordering.** In `AdminUserService.suspendUser`, `AgeVerificationService.blockAccount`
and `AccountSuspensionEventListener.suspend`, the bulk revoke runs **before** the epoch stamp, so a
successor that survives the revoke was inserted before it committed and therefore carries a `created_at`
earlier than an epoch computed afterwards — which `isBeforeSessionEpoch` then refuses. The order is free:
the revoke is `REQUIRES_NEW` on `refresh_tokens`, the stamp is `MANDATORY` on the user row, so they commit
independently and touch different rows (no self-deadlock).

**Do not claim the window is closed.** Two residuals remain and the rewritten javadoc must name them:
`decideAndRotate` reads the user with a plain `findById` (`AuthService.java:322`), so it does not block on
the suspending transaction's row lock and can still read a null epoch until that transaction commits; and
the `created_at`-vs-epoch comparison assumes comparable clocks, which two app nodes with skew can invert.
This is the same residual shape `changePassword` already has. `AuthService.isBeforeSessionEpoch`'s javadoc
currently names these three callers as its documented limit; that paragraph must be rewritten, and must not
be replaced with an overclaim.

**The `reactivateUser` half is conditional on proving it catches anything.** Suspend already runs
`markAllUsedByUserId` and stamps a monotonic epoch that nothing ever clears, so every pre-suspension token
and JWT stays refused after `unlock()`. Once the statements are swapped, the only row a revoke-on-reactivate
could still catch is a race successor that the epoch already refuses. So: first write the IT
(suspend → concurrent refresh → reactivate → assert what the stale credential can do). If it shows nothing
survives, **drop the reactivate change** and record that in the Completion Notes instead of adding a write
nobody needs. `AdminUserManagementIT.reactivate_unlocksAccount_andLoginWorksAgain` (`:287`) never logs in
before suspending, so it does not settle this either way.

**AC9 — the sweep cannot deadlock the chain walks.** `RefreshTokenRepository.deleteExpiredTokens` acquires
in ascending `id` order (JPQL bulk `DELETE` cannot carry `ORDER BY`, so this becomes a native delete over an
ordered, locked subselect), which removes the lock cycle against the chain walks' ascending-id order. And
`AuthCleanupService.purgeExpiredRefreshTokens` no longer lets a lost deadlock escape the `@Scheduled`
method: it is logged at `warn` and the next hourly run retries.

**The catch cannot sit inside the current method.** `purgeExpiredRefreshTokens` is `@Transactional`
(`AuthCleanupService.java:38-45`) and `deleteExpiredTokens` is itself `@Modifying @Transactional`
(`RefreshTokenRepository.java:233-236`), so it joins that same transaction. A deadlock thrown through the
participating proxy marks the transaction rollback-only, and a `try/catch` in the method body then still
ends in `UnexpectedRollbackException` from the outer commit — it changes the exception, not the escape.
Postgres has also already aborted that transaction (40P01), so nothing further can run in it. The guard
must therefore be **outside** the transaction: either drop `@Transactional` from the scheduled method and
wrap the delete in a `TransactionTemplate` inside the try, or keep a non-transactional scheduled method
that delegates to a transactional one. `@SchedulerLock` must stay outermost —
`SchedulerLockTransactionOrderingIT:169` asserts exactly that.

Two limits to record rather than silently imply: this fixes the cycle against the **chain walks** only;
`markAllUsedByUserId` and `markAllRevokedByUserId` are still unordered bulk `UPDATE`s and can still deadlock
against the ordered sweep (the comment at `AuthService.java:225-229` already names them as a deadlock
source). And the ordered delete needs a real test against populated rows, including the
`ON DELETE SET NULL` referential action — nothing exercises that path today
(`AuthCleanupServiceTest` only verifies delegation and the lock attributes).
`NativeModifyingVersionAuditTest` skips statements beginning with `DELETE`, so a native delete passes it.

**AC10 — the 7-day cap is now real, and tested.** AC4 makes the refresh chain's life an absolute bound on
the session: an active JWT whose chain has fully expired or been swept is refused on the next cycle, where
today it slides indefinitely as long as the user keeps clicking. This is a behaviour change, intended, and
it gets its own test plus a line in the docs.

**AC11 — docs.** `docs/session-refresh-mechanism.md` (the claim, the new check, the 7-day cap, the
fail-open limit for pre-deploy JWTs, and AC7's lost theft signal), `docs/security-api-endpoints.md` where it describes logout's reach,
`docs/dev-docs/security/index.html` plus a rebuilt `search-index.js`, and
`docs/deployment/provisioning-checklist.md` for V225.

**AC12 — `/pr-review` on the final commit, to a clean round.** Run it, fix what it finds, run it again,
until a round reports nothing actionable. One round is not enough: on #413 rounds 2 and 3 each found real
defects introduced by the previous round's own fix.

## Tasks / Subtasks

- [x] Task 1: V225 migration (AC1)
  - [x] `session_id uuid` + btree index + `VALIDATE CONSTRAINT`, `SET LOCAL lock_timeout`, column comment
  - [x] Justify the `allow-blocking-index` marker with a row count and a build-time estimate
  - [x] `mvn -q -Dtest=MigrationConventionLintTest test` — 30/30
- [x] Task 2: entity + repository (AC2, AC4, AC9)
  - [x] `RefreshToken.sessionId`; `existsLiveBySessionId` (scalar, inclusive expiry bound)
  - [x] `deleteExpiredTokens` → native, ordered, locked subselect, cutoff passed in
- [x] Task 3: mint and carry the claim (AC2, AC3)
  - [x] `SecurityConstants.RTKN_SESSION = "rtsid"`; `ClaimsExtractorImpl.extractRefreshSessionId`
        (`toClaims` left unchanged — see the Completion Notes for why, and what it means for AC3)
  - [x] `LoginTokenManager`/`JwtManagerImpl` signatures; the `Rotated` record; six test call sites
  - [x] `login` mints the id; `decideAndRotate` copies the rotated row's, minting a fresh one when it is null
  - [x] `extendTtlOfToken` carries it over (no `rtkn` derivation — see AC3)
- [x] Task 4: the filter check (AC4, AC5, AC6, AC10)
  - [x] `assertSessionChainStillLive` beside `assertSessionNotRevoked`, via `SecurityUtil` rather than a
        new filter dependency; three unit tests cover refuse / allow / skip-on-null-claim
- [x] Task 5: the grace-window outcome (AC7)
  - [x] `GraceResolution.stale()` → the `StaleChain` outcome → 401 + cookies, no writes
  - [x] `warn` log carrying the user id and presented hash for the case no longer theft-detected
- [x] Task 6: the revoke-all ordering (AC8)
  - [x] Swapped in `AdminUserService.suspendUser`, `AgeVerificationService.blockAccount` and
        `AccountSuspensionEventListener.suspend`
  - [x] IT written first and it answered no: `reactivate_cannotRestoreASessionTheSuspensionEnded` passes
        without any revoke on reactivate, because the session epoch is monotonic and never cleared. The
        revoke was therefore **dropped** per AC8's own instruction, not implemented
  - [x] `isBeforeSessionEpoch`'s javadoc rewritten, naming the unblocked `findById` read and node clock
        skew as the two residuals rather than claiming the window is closed
- [x] Task 7: the sweep guard (AC9)
  - [x] `@Transactional` removed from the scheduled method so the catch sits outside the transaction;
        `@SchedulerLock` kept and still asserted (`SchedulerLockTransactionOrderingIT` flipped to
        `assertNotTransactional`)
  - [x] IT covers the ordered delete against populated rows including the `ON DELETE SET NULL` path
- [x] Task 8: tests (AC6, AC7, AC9, AC10 and regression)
  - [x] `AuthResourceIT` 43/43 (6 new: logout kills the standing JWT but not another device; the
        documented fail-open for a claimless JWT; an expired chain refuses its JWT; a stale token on a live
        chain is 401 with no account-wide revocation; the ordered sweep and its `ON DELETE SET NULL` path;
        a fresh id minted when the rotated row has none)
  - [x] `AdminUserManagementIT` 15/15, `SchedulerLockTransactionOrderingIT` 5/5,
        `RefreshTokenRepositoryIT` 1/1
  - [x] Unit: `JWTAuthorizationFilterTest` 25/25, `JwtManagerImplTest` 29/29, `AuthServiceTest` 14/14,
        `SecurityUtilTest` 8/8, `AuthCleanupServiceTest` 6/6, `MigrationConventionLintTest` 30/30,
        `NativeModifyingVersionAuditTest` 2/2, `IntegrationTestConventionTest` 6/6
- [x] Task 9: docs (AC11) — `check-docs: OK, 26 pages`
- [x] Task 10: `/pr-review` (AC12) — **seven rounds run; closed on the owner's call, NOT on a clean
      round.** Rounds 1-5 found behaviour defects; rounds 6-7 found only stale claims, which is the signal
      that the code had settled and the documentation load was where the remaining risk sat. Round 7's
      three substantive findings were two load-bearing javadoc errors and one test guard an earlier round
      had dropped — no behaviour change. Records in `.pr-review/`, one per reviewed commit: `826ac6cf` (1),
      `f5380b7a` (2), `d45d11d3` (3), `b3b7083b` (4), `c5e609bb` (5), `1eab3dc5` (6), `6920654e` (7) — the
      last three were written only when round 7 pointed out that the story claimed records that did not
      exist, found by listing `.pr-review/` rather than trusting the claim.
  - [x] **AC12's "clean round" was not reached, and this task is closed anyway.** Owner decision
        2026-11-04: stop the loop at seven rounds, record that the last round found only documentation
        drift, and let CI be the remaining gate. The reasoning on both sides is recorded rather than
        smoothed over — six of seven rounds found something the previous round left behind, so convergence
        was never demonstrated; against that, the code had not changed behaviourally since round 5 and the
        review spend on one story was already large. A future round on this branch is cheap to run if the
        PR surfaces anything.
  - [x] HEAD record written for the final commit, which the `gh pr create` hook requires. It is a
        **no-arms record**: no reviewer subagent ran at that SHA, because the reviewed code diff there is
        byte-identical to `18a899e4` (the final commit adds only story-artifact and sprint-status text).
        The record says so in those words rather than implying a round took place.

## Dev Notes

### Corrections to the filed items — read these first

**1. The grace-window rule in `deferred-work.md` would not have fixed the bug.** The entry proposed
measuring the 30 s "relative to the chain's live end rather than the presented row's `rotatedAt`". But in
the reported scenario the client is exactly **one hop behind**, and the live end was created by the rotation
of the presented row: `now` is captured at the top of `decideAndRotate` (`AuthService.java:308`, before the
lock wait) and written as `rotatedAt`, while the successor's `createdAt` is set by `@PrePersist` at
`saveAndFlush` (`RefreshToken.java:42-47`). So `createdAt = rotatedAt + rotation latency` — milliseconds
normally, seconds under lock contention — not a different clock reading in any useful sense. The window
therefore expires at effectively the same moment under either rule. It only helps when some other client keeps rotating the chain forward. The root cause is
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
design** — the comment at `:224-241` records that `skillars-deferred-144 AC8` deliberately removed a
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
**only then** tests the window at `:487-488` (`presented.getRotatedAt() + REFRESH_GRACE_WINDOW`). AC7 adds a
third outcome to the `GraceResolution` record (`:495`) for "liveEnd found, window failed" and threads it
through `decideAndRotate`'s `used` branch (`:333-345`) to a new `RefreshOutcome`. Do not reuse `Revoked`:
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

### The plumbing a new claim actually touches

Wider than "add a constant and a put", and every one of these is a compile break if missed:
- `LoginTokenManager.createLoginToken(res, principal)` and `renewLoginToken(res, principal)`
  (`LoginTokenManager.java:19` and `:29`) have no parameter for a session id. Both signatures change, and
  so do the `JwtManagerImpl` implementations at `:41` and `:54`.
- `TokenCreator.toClaims` gains a parameter; its call sites are `JwtManagerImpl:45`, `:58` and `:79`.
- `AuthService.refresh` calls `createLoginToken(res, rotated.principal())` at `:253`, and the `Rotated`
  record (`:509`) carries no session id — it has to, since the row is detached by then. `login` passes it
  from the row it just created at `:146`.
- `JWTAuthorizationFilterTest:471` and `:503` verify the exact `renewLoginToken(response, principal)`
  argument list, and `JwtManagerImplTest` calls these methods directly. Both need updating.

### What AC4 costs, and how its refusal reaches the client

- One query per authenticated user per `DB_REFRESH_TOKEN_INTERVAL` (5 min), not per request, and it is an
  index lookup on `session_id` returning a boolean. Exactly one: this is why AC3 forbids deriving the claim
  from the `rtkn` cookie, which would have added a `findByTokenHash` on the same branch. Write it as a scalar existence query
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
- **Advancing time: `ClockProvider` will NOT work from an HTTP integration test.** It is a `ThreadLocal`
  (`ClockProvider.java:7`) and the embedded server handles the request on its own worker thread, so a clock
  set on the test thread never reaches `AuthService` or `JwtManagerImpl`. `AuthResourceIT:391-401` states
  this explicitly, and the class uses `TestClockProvider` only to mint a JWT **on the test thread**. So:
  - AC7's out-of-window case: push the row's `rotated_at` into the past with `commitWrite`.
  - AC10's expired chain: push `expires_at` into the past with `commitWrite`.
  - AC6/AC10's forced DB-reauth cycle: hand-build a JWT whose `dbRToken` claim is already past and sign it
    with `tokenCreator.generateTokenFromClaims`, the pattern at `AuthResourceIT:402-415` (note the comment
    there: build the claims map by hand, because `toClaims` reads `RequestMetadataProvider.getClientInfo()`,
    which is populated only inside a live HTTP request).
  Do not `Thread.sleep` 30 s and do not wait 5 real minutes.
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
  same row. #413 hung an IT phase for 19 minutes on exactly this — there the walk
  held `PESSIMISTIC_WRITE` locks on the rows a `REQUIRES_NEW` bulk `UPDATE` then needed, so a lock held
  over the rows is enough; the caller need not have written them.
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
renamed, not rebased around — the precedent is in git, not in the ledger (`0621d23f` renumbered V216→V217,
`dce987cb` V203→V207, `67d7f945` V202→V206). The same applies to
`docs/deployment/provisioning-checklist.md`, and to `docs/dev-docs/assets/search-index.js`, which
`docs/dev-docs/README.md:13` records as generated — rebuild it rather than resolving a conflict in it.

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

Claude Opus 5 (claude-opus-5), via `/bmad-dev-story`.

### Debug Log References

- `MigrationConventionLintTest` 30/30 with V225 in the tree (the first executed `VALIDATE CONSTRAINT` in
  the repo; the linter does not recognise the statement, so the lock reasoning is in the header instead).
- Two IT invocations silently no-opped before I noticed: both were wrapped in `timeout`, which **does not
  exist on macOS** (exit 127). The same trap is recorded in this project's history. Reruns were unwrapped.
- A third IT run looked like it had passed `AuthResourceIT` at 37 tests — that was the previous session's
  stale `target/failsafe-reports` XML, since `rm` had only cleared `*.txt`. The file has 44 `@Test`s, so 37
  was the pre-change count. Report directory is now deleted wholesale before a run.
- `-Dit.test=AuthResourceIT` does not restrict this project's failsafe execution to one class.

### Completion Notes List

**Deviation from AC3, deliberate, with the reason.** AC3 says the claim is "written by
`TokenCreatorImpl.toClaims`". It is not: `toClaims` is unchanged, and each of the three mint paths puts the
claim itself. That is exactly how `SESSION_ISSUED_AT` — the only other claim not derivable from the
`Principal` — is already handled, and following it kept `toClaims`'s signature, its `generateToken`
wrapper and the tests that pin them untouched. The AC's actual requirement (the claim is written from a
value the caller supplies, carried over by `extendTtlOfToken`, and set on the DB-reauth path) is met.

**AC8's `reactivateUser` half was dropped, as AC8 instructed, because the test showed it catches nothing
— with one qualification round 6 added to the reasoning.**
`AdminUserManagementIT.reactivate_cannotRestoreASessionTheSuspensionEnded` is the test that decided it:
when `suspendUser` acts, it both revokes every refresh token and stamps `security_session_invalidated_at`,
which is monotonic and never cleared — not by `reactivateUser`, not by a later login. So a pre-suspension
refresh token stays refused by `isBeforeSessionEpoch` and a pre-suspension JWT by `assertSessionNotRevoked`,
permanently, and a revoke on reactivate would be a write that cannot change an outcome. The statement
**reorder** did ship, since that closes a real window.

**The qualification:** "suspend always revokes and stamps" is not true of `suspendUser`'s idempotent
branch. If `user.isLocked()` is already true it suspends the coach profile and returns `false` without
revoking or stamping, so for an account locked by some path that does not itself stamp the epoch, a later
`reactivateUser` would restore a live chain and `existsLiveBySessionId` would approve the standing JWT.
Latent rather than reachable today: the only path that sets `locked` without stamping is
`UserAdminService.lockUserAccount`, which has no production caller (grep: `UserServiceIT` only), and
GDPR-erased accounts are refused reactivation outright. Recorded rather than fixed because adding a
compensating write here would be guessing at a caller that does not exist — but if one is ever added, this
is the reasoning that stops being true, and the revoke-on-reactivate becomes necessary.

**AC4's check went on `SecurityUtil`, not into the filter's constructor.** The filter already depends on
`SecurityUtil`, which already delegates chain work to `RefreshTokenRevocationService`; adding a repository
to `JWTAuthorizationFilter` would have changed its constructor and every test that builds it, to ask one
boolean. The query itself lives on the revocation service, which owns the repository.

**Two defects I introduced and caught before they shipped**, both previously seen on #413:
- I left two adjacent javadoc blocks on `purgeExpiredRefreshTokens`, which orphans the first. Merged.
- `GraceResolution.staleButLive()` as a static factory collided with the record's generated accessor of
  the same name — the identical clash that forced `revokedChain()` in deferred-166. Renamed to `stale()`.

**One consistency fix of my own.** `existsLiveBySessionId` first used `expires_at > :now`, while
`decideAndRotate` treats `expiresAt == now` as still live. Changed to `>=` so the two cannot disagree
about the same row.

**AC9's guard required removing `@Transactional` from the scheduled method**, which flipped
`SchedulerLockTransactionOrderingIT`'s assertion for it from `assertShedLockOutermost` to
`assertNotTransactional` (the existing helper, already used for two other schedulers). `@SchedulerLock`
stays and is still asserted.

**One suite was not run locally at the final commit: `SchedulerLockTransactionOrderingIT`.** Its last
full green run was at `1eab3dc5` (5 tests). Round 7 then added a sixth,
`authCleanupServicePurgeOldLoginAttempts_shedLockAdvisorIsOutsideTheTransactionAdvisor`, to restore the
REQUIRED-propagation advisor-ordering guard that AC9 had removed along with the sweep's `@Transactional`.
That test compiles and its premise was verified statically — `purgeOldLoginAttempts` does carry
`@Scheduled` + `@Transactional` + `@SchedulerLock`, the stacked shape the assertion needs — but the
advisor-ordering assertion itself only exercises against the live Spring proxy, and the run that would
have exercised it was killed by host memory pressure rather than by a failure. CI runs the full failsafe
suite on the PR, so it is covered there; it is recorded here as unverified locally rather than implied to
have passed.

### File List

**Migration**
- `src/main/resources/db/migration/V225__refresh_token_session_id_and_validate_successor_fk.sql` (new)

**Main**
- `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/repo/RefreshToken.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/repo/RefreshTokenRepository.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/service/AuthService.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/service/AuthCleanupService.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/service/RefreshTokenRevocationService.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/service/SecurityUtil.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/service/LoginTokenManager.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/ClaimsExtractor.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/ClaimsExtractorImpl.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImpl.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilter.java` (modified)
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminUserService.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/service/AgeVerificationService.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/listener/AccountSuspensionEventListener.java` (modified)

**Tests**
- `src/test/java/com/softropic/skillars/platform/security/api/AuthResourceIT.java` (modified)
- `src/test/java/com/softropic/skillars/platform/admin/api/AdminUserManagementIT.java` (modified)
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/filter/JWTAuthorizationFilterTest.java` (modified)
- `src/test/java/com/softropic/skillars/platform/security/infrastructure/jwt/JwtManagerImplTest.java` (modified)
- `src/test/java/com/softropic/skillars/platform/security/service/AuthCleanupServiceTest.java` (modified)
- `src/test/java/com/softropic/skillars/platform/scheduler/SchedulerLockTransactionOrderingIT.java` (modified)

**Frontend**
- none. A rotation call was added to `auth.api.js` and `sessionManager.js` under the owner's first AC10
  decision and then fully reverted; both files are identical to master.

**Docs**
- `docs/session-refresh-mechanism.md` (modified)
- `docs/security-api-endpoints.md` (modified)
- `docs/dev-docs/security/index.html` (modified)
- `docs/dev-docs/assets/search-index.js` (regenerated)
- `docs/deployment/provisioning-checklist.md` (modified)

**Artifacts**
- `_bmad-output/implementation-artifacts/skillars-deferred-167-...md` (this file)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified)

### Change Log

- 2026-11-04 — **AC12 closed by owner decision at seven rounds, not by a clean round, and the story moves
  to `review`.** Round 7 (record `6920654e`) found eleven items, of which the three substantive ones were
  two load-bearing javadoc errors and a test guard an earlier round had dropped; all were fixed and
  committed as `18a899e4`. The owner elected to open the PR there rather than run round 8. What that trades
  away is stated plainly: six of the seven rounds found something the previous round left behind, so the
  loop was stopped while still finding things, and the honest reading of round 7 is "only documentation
  drift", not "converged". What it buys is the end of a review spend that had grown large on one story,
  against a code surface unchanged in behaviour since round 5. The HEAD record the `gh pr create` hook
  requires is a no-arms record, labelled as such.

- 2026-11-03 — owner ruled on all four open decisions; all four applied or recorded.
  - **AC7 amended (owner option b).** The stale-but-live branch now calls
    `securityUtil.terminateSession`, revoking that chain from the presented row forward, instead of
    answering 401 with no writes. Both reviewer arms had shown the original shape destroyed the victim's
    only remediation: the same branch clears the `rtkn` cookie, which is the only value `terminateSession`
    can hash to start its walk, so a victim whose token had been stolen and rotated could not revoke the
    thief's live successor by any means short of a password change. The account-wide revocation AC7 removed
    stays removed, which was always the distinction that mattered. **This supersedes AC7's written text
    (`401 + clearAuthCookies`)**; the AC itself is left as approved because this workflow may not edit it.
  - **AC10's cap: three attempts, all withdrawn, cap ships.** Option (a) (SPA rotation from
    `sessionManager.refreshSession()`) was applied and reverted — its only trigger is the idle-warning
    dialog, which a polling tab never reaches, and a failed rotation returns cookie-deletion headers so it
    could sign the user out. Option (b) was then tried as an expiry exemption for unrotated rows (dead on
    arrival: a rotated chain's live end is itself unrotated, so it exempted everything) and as a sliding
    deadline (withdrawn in round 5: the row outlived the `rtkn` cookie that `terminateSession` needs, so
    logout silently revoked nothing). The owner's final decision is to ship with the cap and fix it in its
    own story. **The frontend is unchanged from master.**
  - **Fail-closed flip decided (owner option a: an announced date) and deliberately NOT implemented.** The
    date is an operational choice and shipping it blind would sign the remaining claimless holders out at
    an unannounced moment. The ledger entry names exactly what the implementer changes.
  - **AC7 ships**, which the first item resolves: it no longer trades theft detection away, it contains the
    blast radius to one chain.
  - One process failure worth recording: the first edit script for the AC7 change aborted on its last
    replacement, so NONE of it was written while a separately-applied javadoc paragraph already described
    the new behaviour. `AuthServiceTest` caught the mismatch. Re-applied and verified statement by
    statement.

- 2026-11-03 — three `/pr-review` rounds applied (records in `.pr-review/`). 26 findings. Every round found
  at least one defect the previous round introduced or left behind, which is why AC12 asks for rounds
  rather than a round:
  - Round 1 (7): the documented 7-day bound on AC5's fail-open does not exist (both arms) and was wrong in
    four places; every rotation resets `expires_at`, so neither AC10's cap nor AC7's "up to 7 days" held;
    the `StaleChain` branch fires for any age and any depth up to the hop bound; AC7's mandated metric was
    never shipped. Fixed. Round 1 left `resolveGraceSuccessor`'s contract contradicting its own code.
  - Round 2 (12): a rolling deploy (or rollback) permanently strips the `rtsid` claim from a POST-deploy
    token, which **reversed the story review's decision to drop claim re-derivation** — that review was
    right that a pre-V225 row has nothing to derive, but a post-deploy token losing a claim it already had
    is a different case, and re-derivation is the only repair. Also scoped the liveness query to the
    authorized user, and fixed round 1's stale contract. Round 2's own fix then left
    `assertSessionChainStillLive`'s javadoc asserting the opposite, and moved the sweep's cutoff to an
    app-node clock.
  - Round 3 (7): both arms independently found that the AC7 401 clears the victim's `rtkn`, which was the
    only value `terminateSession` could walk from — so Logout can no longer revoke a thief's chain. Filed
    as a high-severity owner decision rather than changed, because AC7's text specifies that call. Fixed
    round 2's two regressions (the javadoc, the sweep clock) and pinned the user id the cross-account
    scoping depends on, which no test had constrained.

- 2026-11-03 — revised after `/mto-story-review` against HEAD b3b0d647 (report:
  `_bmad-output/implementation-artifacts/story-review.md`). Applied: AC7 now states and instruments the
  theft signal it gives up (the review showed the chosen option does weaken detection, which the option
  text had denied); AC3 drops the `rtkn`-cookie derivation as dead code and AC2 mints a fresh id when the
  rotated row's is null, so a pre-V225 chain cannot stay null forever; AC9's guard moves outside the
  transaction, because a participating `@Transactional` repository call makes it rollback-only and a
  body-level catch still throws `UnexpectedRollbackException`; the test notes drop `ClockProvider`, which
  cannot reach the server's worker thread; AC8 stops claiming the window is closed and makes the
  `reactivateUser` revoke conditional on an IT proving it catches anything; AC1 records that Flyway's
  single transaction holds `ACCESS EXCLUSIVE` across the index build and the validation scan; added the
  claim-plumbing section the task list had understated. Citation drift fixed (`:224-241`, `:487-488`,
  `:333-345`), the "same instant" claim corrected to `rotatedAt` plus rotation latency, and the
  merge-hotspot citation repointed from the ledger (where no such note exists) to the renumber commits and
  `docs/dev-docs/README.md`.
- 2026-11-03 — created (/bmad-create-story) from five owner-decided items raised on PR #413. Two filed
  design points corrected against the code during creation: the grace-window rule (the live-end measurement
  does not fix the reported case) and the claim name (`sessionId` is taken by client-fixation detection).
