# Story skillars-deferred-166: Logout revokes the whole rotation chain; the refresh grace window follows the token's own successor

Status: ready-for-dev

<!-- CREATED 2026-11-03 (/bmad-create-story). Owner-requested bundle of exactly two ledger items,
     RS01 and RS02, from deferred-work.md "Deferred from: review-evals baseline real-sample,
     owner-verified (2026-11-03)". Both were found by the AI reviewer arms, judged real, and
     confirmed by the owner against HEAD on 2026-11-03. Every citation below was re-read at HEAD
     c7cb601b by an Explore pass before drafting; two ledger claims turned out stale (see
     "Verified state at drafting"). Each item is also an eval case under evals/review/cases/_drafts/
     (pending-*.yaml); AC9 flips them.
     REVISED 2026-11-03 after /mto-story-review (story-review.md): the first pinned design held a row
     lock across the whole of refresh, which self-deadlocks every REQUIRES_NEW revoke on that row (F1)
     and whose in-transaction twin is rolled back by the denial throw (F2). Design items 3-6 replaced;
     AC1/AC3/AC5 cookie wording fixed (F3); AC9 ordering fixed with a validator tweak (F4); revoked
     tokens get a plain 401 instead of the theft path (F5); chain revoke made idempotent (F6); deploy
     window and null-epoch rules written down (F7, F8); minor citation drifts corrected. -->

## Story

As a Skillars user who logs out, or who uses the app from more than one device,
I want logout to end the session I am logging out of even if its refresh token was rotated a
moment earlier, and I want a stale refresh presented within the multi-tab grace window to be
redirected only to that token's own successor, never to another device's live token,
so that "Logout" actually ends the session and one device's refresh can never hijack or
mass-revoke another device's session.

## Ledger mapping (so future passes do not re-pick these)

| Ledger item | This story | Eval case (flipped by AC9) |
|---|---|---|
| RS01 (deferred-work.md "Deferred from: review-evals baseline real-sample" section, `[high/concurrency]` logout does not revoke the live refresh token when the presented token was already rotated) | AC1, AC2, AC3, AC5 | `evals/review/cases/_drafts/pending-logout-successor-revocation.yaml` |
| RS02 (same section, `[high/logic]` refresh grace window consumes the user's newest live token from any device) | AC1, AC4, AC5 | `evals/review/cases/_drafts/pending-refresh-grace-window-account-wide.yaml` |

Tag both bullets `**[PICKED UP skillars-deferred-166]**` at story start (AC10); delete them when the PR merges, per the ledger's own "deleted outright once implemented" rule.

## Verified state at drafting (HEAD c7cb601b, 2026-11-03)

Read before implementing; the ledger text is partly stale.

- **RS01's quoted line is gone.** `AuthService.logout` (`AuthService.java:303-305`) is a single call to `SecurityUtil.terminateSession` (`SecurityUtil.java:168-184`), which reads `rtkn`, calls `RefreshTokenRepository.markUsedByTokenHash(sha256Hex(raw))` and clears the cookies. `markUsedByTokenHash` (`RefreshTokenRepository.java:117-121`) is `@Modifying`, `REQUIRES_NEW`, `UPDATE ... WHERE r.tokenHash = :tokenHash AND r.used = false`; the `used = false` predicate was added by skillars-deferred-144 AC6.6. The effect is exactly the ledger's: a token already flipped by a concurrent rotation matches zero rows, and if the rotation's UPDATE is still uncommitted, Postgres waits, re-checks the predicate, and still matches zero rows. Either way the successor minted by the rotation survives. `RefreshToken` (`repo/RefreshToken.java`) has `version`, `userId`, `tokenHash`, `expiresAt`, `used`, `createdAt`, `rotatedAt` and **no successor, family, device or revocation column**.
- **The SPA never calls `POST /api/auth/refresh`.** `src/frontend/src/api/auth.api.js:3-5` records its removal; keep-alive is `GET /refresh` via `sessionApi.refresh()` (`api/session.api.js:4-6`, `plugins/sessionManager.js:251-310`), which re-mints the JWT in `JWTAuthorizationFilter.java:206-247` and never touches `refresh_tokens`; the `boot/axios.js:162-215` 401 interceptor tears down, it does not retry. So the "user is logged in again right after clicking Logout" sentence in the eval case cannot happen from the SPA alone. RS01 remains a real defect of the endpoint contract: any second client, a replayed cookie, or an attacker holding a just-rotated token keeps a live session through logout. Severity for the SPA user is lower than the ledger's `high`; for the API contract it is not.
- **RS02 is exactly as described.** `AuthService.refresh` (`AuthService.java:153-301`): grace branch at :169-192 takes `token = findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(ownerId, now)` (`RefreshTokenRepository.java:66-67`, whose javadoc at :59-65 wrongly calls the result "the live successor"); rotation at :262-280 sets `used=true`, `rotatedAt=now` and `saveAndFlush`s under the `@Version` lock (an `ObjectOptimisticLockingFailureException` becomes a 401 "Concurrent refresh" at :273-279, deliberately without a REQUIRES_NEW revoke because that would deadlock against this transaction's own row lock); the new token is saved at :284-298 and **nothing links old to new**.
- **Two more exits inherit RS02.** The missing-user (:246) and account-locked (:257) exits call `markUsedByTokenHash(resolvedTokenHash)` on whatever row the grace branch picked, so on a stale replay they revoke another session's token and leave the real successor alive. skillars-deferred-143's patch (its story :220-223) assumed "newest live" is the successor; RS02 disproves that assumption, so that patch is in scope here, not a settled design.
- **Revoke-all has a snapshot gap.** Every revoke-all caller (`UserProfileService.changePassword` :175-178, `PasswordResetService.completePasswordReset` :74-76, `AccountSuspensionEventListener.suspend` :80-99, `AdminUserService` :164-165, `AgeVerificationService` :263-265, `GdprErasureService` :810) runs `markAllUsedByUserId` (`RefreshTokenRepository.java:79-82`, bulk UPDATE, REQUIRES_NEW); all but `GdprErasureService` (which locks the user instead) also bump `security_session_invalidated_at` (V158) via `UserRepository.invalidateSessionsForUser` (:161-164) / `...InCurrentTransaction` (:209-213). A rotation that commits after the bulk UPDATE's snapshot leaves a successor the UPDATE never saw. AC6 closes it cheaply on the refresh side.
- **Tests that exist and what is missing.** `AuthResourceIT` (`src/test/java/.../security/api/AuthResourceIT.java`, extends `AbstractIntegrationTest`, `@Sql(SecurityIT.SEC_DATA_SQL_PATH)` at :62-63, no extra annotations or mocks so it shares the context): `refresh_validUnusedToken_rotatesTokenAndReturns200` (:314), `refresh_expiredToken_returns401` (:490), `logout_marksTokenUsedAndClearsCookies` (:541), the account-liveness trio (:635, :686, :718), `refresh_accountLockedAndStaleCookieReplayedInGraceWindow_revokesTheSuccessorToken` (:748, the only grace-window IT and the model for new ones), `markUsedByTokenHash_commitsEvenWhenTheCallingTransactionRollsBack` (:811), CSAM (:839-887), `springSecurityLogoutEndpoint_*` (:947, :967). **Missing:** a grace-window success test, a two-session test, a logout-after-rotation test; `refresh_alreadyUsedToken_revokesAllAndReturns401` is commented out (TODO at :444, `/* @Test` at :445); its stated reason, that the SPA dropped `POST /refresh`, does not block reviving it, because the IT calls the endpoint directly. Seeding goes through `transactionTemplate.execute` (:124, :169) and `commitWrite(sql, args...)` (:1060-1062); helpers `usedFlagOf(hash)` (:1065), `cookieHeaders(rtkn)` (:1078), `extractCookieValue` (:1084), `sha256Hex` (:1094), `insertUser` (:1108). `RefreshTokenRepositoryIT.java:37` asserts `markUsedByTokenHash` is a no-op on a used row: that assertion stays true (AC2 adds a new method, it does not change this one). `AuthServiceTest` (Mockito) covers grace-with-no-successor (:72), never-rotated (:99), rotated-long-ago (:119). `SecurityUtilTest` has five `terminateSession_*` tests (:71, :82, :106, :118, :128; range :71-139). `AuthServiceTest` stubs the newest-live query at :80, :92, :114 and :138; deleting that query (design item 5) breaks those stubs, so they are rewritten, not just left to fail.
- **Stale comments to fix while there:** the `AuthService` comment block at :227-238 cites `:158-165` (on :229) for the grace branch (it is :169-192); `AuthResourceIT.java:750` repeats it.
- **Docs that describe the old behaviour:** `docs/session-refresh-mechanism.md` :114-126 (claims `authApi.skillarsRefresh()` exists; it does not), :253, :476-483; `docs/dev-docs/security/index.html` :133, :146, :172-176, the sequence diagram :417-452 (:445 "use newest live successor"), :590-591, footer stamp :610; `docs/security-api-endpoints.md` :32-34, :61-72, :187-199.
- **Migrations.** `refresh_tokens` is created in `V138__baseline_schema.sql:1089-1098` (PK :2586, `token_hash` UNIQUE :2593, `idx_rt_token_hash` :3580, `idx_rt_user_id` :3586, FK `user_id -> main."user"(id) ON DELETE CASCADE` :4314). No later migration alters it. Latest migration is `V223__coach_safeguarding_check.sql`; this story's is **V224**.

## Design (pinned; do not re-derive)

The root cause of both items is the same: a rotated token has no link to its successor, so every path that needs "the token this one became" guesses ("newest live for the user") or gives up ("only if still unused"). The fix is the link plus a distinction the code does not have today between **rotated** (replaced, successor live) and **revoked** (session ended).

1. **Schema (V224, additive, schema-qualified `main.refresh_tokens`):** `replaced_by_id bigint NULL` (the successor's id, set in the same transaction as the rotation) and `refresh_tokens.revoked_at timestamptz NULL`. Self-referential FK `replaced_by_id -> main.refresh_tokens(id) ON DELETE SET NULL`, added `NOT VALID` (table grows; `docs/deployment/migration-conventions.md` rule at :88-89) with the `VALIDATE` in a later migration (file it as a ledger bullet, same as prior NOT VALID constraints). No new index: every lookup walks predecessor to successor by primary key. `MigrationConventionLintTest` must pass.
2. **Rotation writes the link.** Inside the locked block, after the successor row is saved, set `old.replacedBy = successor.id` in the same transaction, so a reader who can see the successor can always find it from the predecessor. **Deploy window (accepted trade-off):** tokens rotated before V224, or by an old instance during the rolling deploy, have no link; a replay of one of those inside the 30 s grace window is REUSE and signs out every device. The SPA never calls this endpoint, so the exposure is API clients only, for 30 s per token, once.
3. **Lock scope: one short transaction, released before any denial write.** `refresh` holds no lock across its denial exits. Restructure it as: (a) a `TransactionTemplate` block (precedent: the `transactionTemplate` blocks in `MessagingService`; a `TransactionTemplate` rather than a self-invoked `@Transactional` method, because Spring's proxy does not intercept internal calls) that locks the presented row with `findByTokenHashForUpdate` (`@Lock(PESSIMISTIC_WRITE)` on the derived query), evaluates every branch under the lock, and returns an **outcome** without throwing: `ROTATED(newToken, user)`, `REDIRECTED_AND_ROTATED(newToken, user)`, `REUSE(ownerId)`, `REVOKED`, `EXPIRED(tokenHash)`, `STALE_EPOCH(tokenHash)`, `USER_MISSING(tokenHash)`, `ACCOUNT_NOT_LIVE(tokenHash, exception)`; the block writes only in the two ROTATED outcomes. (b) After the block has committed and released the lock, `refresh` acts on the outcome: ROTATED outcomes set the JWT, `rtkn` and `skp` cookies as today (:284-298) and return; every other outcome does its revoke in a **separate transaction** (REQUIRES_NEW exactly as today, which no longer deadlocks because the lock is gone), clears cookies, and throws the same exception type as today. The public `refresh` method is `@Transactional(propagation = NOT_SUPPORTED)` so the class-level `@Transactional` (:46) does not wrap the whole thing. Two concurrent refreshes of one token serialize on the lock: the second sees `used=true`, a fresh `rotatedAt` and `replacedBy`, and takes the grace redirect to the real successor inside the same locked block. Logout versus refresh serialize in both orders: refresh first, then logout's chain walk reaches the successor; logout first, then refresh sees `revokedAt != null` and returns REVOKED. The `@Version` handling at :273-279 stays as a backstop.
4. **Chain revocation for logout, idempotent.** New `RefreshTokenRepository`/service method `revokeChainByTokenHash(String)` running in its own transaction (REQUIRES_NEW, as `markUsedByTokenHash` today and for the same reason deferred-143 made revocation commit independently): lock the presented row with `findByTokenHashForUpdate`; for that row and then each `replacedBy` successor (bound the walk at 8 hops and log if hit), lock it and, **only if `revokedAt IS NULL`**, set `used=true`, `revokedAt=now`, bump `version`. Already-revoked rows are skipped, so a replayed stale JWT hitting `JWTAuthorizationFilter.java:160-161` costs a read, not a rewrite. `SecurityUtil.terminateSession` (:168-184) calls this instead of `markUsedByTokenHash`; `markUsedByTokenHash` itself is unchanged (other callers; `RefreshTokenRepositoryIT:37` keeps passing). The version bump keeps `NativeModifyingVersionAuditTest` green. **Self-deadlock rule (project memory, 55P03 -> 409):** this method is never called from inside the locked block of item 3; the outcome pattern is what guarantees that.
5. **Grace branch follows the successor only.** Inside the locked block, a presented token that is `used`, has `revokedAt == null`, `rotatedAt + REFRESH_GRACE_WINDOW > now` and a non-null `replacedBy` is redirected to its successor (one further hop allowed if that successor is itself rotated within the window; bounded), provided the successor is unused, unrevoked and unexpired; the block then rotates the successor and returns REDIRECTED_AND_ROTATED. A used token with `revokedAt == null` that fails those conditions is REUSE: outside the block, keep today's theft response (`markAllUsedByUserId`, `invalidateSessionsForUser`, `clearAuthCookies`, 401). A token with `revokedAt != null` is REVOKED: outside the block, `clearAuthCookies` and 401 only, **no revoke-all** (the session was ended deliberately; a stale tab replaying it must not sign out the user's other devices; this is a deliberate change from today's :195-203, where a logged-out token with null `rotatedAt` takes the theft branch). Delete `findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc` and rewrite the `AuthServiceTest` stubs that use it. The missing-user and account-not-live outcomes revoke the chain of the **presented** token (REQUIRES_NEW, outside the block), never a row chosen by "newest live"; the existing IT at `AuthResourceIT:748` is updated accordingly.
6. **Refresh honours the session epoch.** Inside the locked block, after the redirect is resolved and the user row is loaded (:243-245), if `user.securitySessionInvalidatedAt` is non-null and the **resolved** token's `createdAt` is before it, return STALE_EPOCH; outside the block that is `clearAuthCookies` and 401 only, no revoke-all. A null epoch skips the check (the column is nullable `timestamp without time zone`, mapped `insertable=false, updatable=false` at `User.java:166`; compare as `Instant` in UTC, as the JWT filter's DB-reauth branch does). This closes the revoke-all snapshot gap for refresh tokens the way V158 closed it for JWTs, without touching the six revoke-all callers. `GdprErasureService` does not bump the epoch, but it locks the user, so the account-not-live outcome covers it.
7. **Frontend: no change.** `MainLayout.handleLogout` (:707-752) already stops monitoring before the backend call (PR #242) and clears `rint` after; `useSession.handleLogout` (:73-100) likewise. Do not add a `POST /api/auth/refresh` caller.

## Acceptance Criteria

1. **Given** a user logs in and the refresh endpoint rotates their token (T0 -> T1), **when** `POST /api/auth/logout` is called with the old `rtkn` cookie T0, **then** both T0 and T1 are `used` with `revoked_at` set, the logout response carries deletion headers (`Max-Age=0`) for `rtkn`, `skp` and the JWT cookies, and a subsequent `POST /api/auth/refresh` with T1 answers 401 whose only `rtkn` Set-Cookie, if any, is a deletion header (never a non-empty value). Tests must distinguish a non-empty `rtkn` value from a deletion header; `clearAuthCookies` (`SecurityUtil.java:215-219`, `CookieUtil.removeCookie` :45-55) always emits the deletion header. (RS01)
2. **Given** a user with two live sessions A and B on different devices, **when** A's token is rotated (A0 -> A1) and within `REFRESH_GRACE_WINDOW` a request still carrying A0 calls refresh, **then** it is redirected to A1 (A1 becomes used, a new A2 is issued) and B's token is untouched and still refreshes successfully afterwards. (RS02)
3. **Given** a token that was revoked by logout, **when** it is presented to refresh within what would have been its grace window, **then** the response is 401 with deletion headers only (no non-empty `rtkn`), the grace path does not run, and **neither does revoke-all**: the user's other sessions stay live and `securitySessionInvalidatedAt` is unchanged (design item 5, REVOKED outcome).
4. **Given** the account-locked and missing-user outcomes, **when** a stale cookie is replayed in the grace window, **then** only the presented token's own chain is revoked, durably: an IT (not a mock assertion) checks the DB rows after the 401 and shows the chain `used`/`revoked_at` set and a second device's token untouched (`AuthResourceIT:748` updated), and no self-deadlock or hang occurs because the revoke runs after the locked block committed (design item 3). A unit test on the locked block asserts it never calls a REQUIRES_NEW repository method.
5. **Given** logout and refresh race on the same token, **when** both run concurrently (two threads, `CountDownLatch` start gate, precedent `BookingCancellationRefundIT` from PR #353), **then** in every observed order no live, unrevoked token for that chain remains, at most one of the two responses carries a **non-empty** `rtkn` value (the logout response always carries a deletion header), and that value is rejected by a follow-up refresh.
6. **Given** a revoke-all has run for a user (use `changePassword`, which bumps the epoch), **when** a refresh token created before `securitySessionInvalidatedAt` is presented, directly or as the successor a grace redirect resolves to, **then** refresh answers 401 with deletion headers only and does not run revoke-all again; a user with a null epoch is unaffected.
7. **Given** two concurrent refreshes of the same unused token (multi-tab), **when** they run under the new row lock, **then** exactly one rotation happens and the second caller receives the successor via the grace branch (or 401 "Concurrent refresh" from the `@Version` backstop), never a 500 and never another session's token.
8. **Given** the V224 migration, **when** `MigrationConventionLintTest` runs and the app boots against a database that already holds rotated tokens, **then** the lint passes, existing rows have `replaced_by_id = NULL` and `revoked_at = NULL`, and the grace branch treats a pre-migration rotated token (no successor link) as REUSE, not as a redirect; the 30 s deploy-window trade-off in design item 2 is recorded in the PR description.
9. **Given** the fix PR number is known, **when** the PR is opened, **then** (a) `evals/review/bin/validate.py` accepts a case with a numeric `fix_pr`, a pinned `input_rev` and no `fix_merge` as "fix PR open, not merged" (same handling as `pending`; one-line change in the pending/pinned branch), (b) both eval cases carry `fix_pr: <PR number>`, keep `input_rev`, and are promoted to `evals/review/cases/` renamed without the `pending-` prefix (the case `id` must equal the filename), with `validate.py` reporting 0 problems on the promoted set; and (c) a docs-only follow-up commit after the squash merge adds `fix_merge: <squash sha>` and drops `input_rev`, validated the same way. The squash sha does not exist before merge, so (c) cannot be in the PR.
10. **Given** the ledger, **when** the PR merges, **then** the RS01 and RS02 bullets (already tagged `**[PICKED UP skillars-deferred-166]**` at story creation) are deleted, and a new bullet records the deferred `VALIDATE CONSTRAINT` for the V224 FK.
11. **Given** the docs, **when** the PR is opened, **then** `docs/session-refresh-mechanism.md` (:114-126 no longer claims `authApi.skillarsRefresh()` exists; the grace window is described as successor-only; the table at :476-483), `docs/security-api-endpoints.md` (:61-72, :187-199) and `docs/dev-docs/security/index.html` (:133, :146, :172-176, the :417-452 diagram, :590-591, footer stamp :610 updated to the merge sha) describe the new behaviour; `RefreshTokenRepository`'s class javadoc (:13-53) lists the new methods and drops the deleted one.
12. **Given** `/pr-review`, **when** the branch is ready, **then** it has been run on the final commit and its findings fixed or consciously deferred to the ledger; the record for HEAD exists (the hook enforces it).

## Tasks / Subtasks

- [x] Task 1: Migration V224 (AC8) — columns, NOT VALID self-FK with ON DELETE SET NULL, header comment citing this story; run `MigrationConventionLintTest`.
- [x] Task 2: Entity + repository (AC1-AC5) — `replacedBy` (Long id, not a relation, matching `userId`'s style) and `revokedAt` on `RefreshToken`; `findByTokenHashForUpdate`; idempotent chain revoke (REQUIRES_NEW, `revoked_at IS NULL` guard, version bump); delete the newest-live query; update the class javadoc (:13-53, :38-47).
- [x] Task 3: `AuthService.refresh` (AC2, AC3, AC4, AC6, AC7) — restructure into the locked `TransactionTemplate` block returning an outcome, and the post-block handling (cookies on ROTATED; REQUIRES_NEW revoke + `clearAuthCookies` + throw on the others); `@Transactional(propagation = NOT_SUPPORTED)` on `refresh`; epoch check on the resolved token; successor-only grace with bounded walk; link written at rotation; REVOKED -> 401 without revoke-all; fix the stale `:158-165` comment at :229.
- [x] Task 4: `SecurityUtil.terminateSession` (AC1, AC5) — call the chain revoke; keep cookie clearing order; update `SecurityUtilTest`.
- [x] Task 5: Tests — `AuthServiceTest` branches (grace -> successor, grace -> revoked, no successor on a pre-migration token, epoch rejection); `AuthResourceIT` new tests for AC1, AC2, AC3, AC6, AC7 and the concurrency test for AC5 (no new Spring context: no annotations, no `@MockitoBean`; `IntegrationTestConventionTest` enforces it); revive `refresh_alreadyUsedToken_revokesAllAndReturns401` (:446) if its TODO reason no longer holds, else say why in Completion Notes; adjust `AuthResourceIT:748`.
- [x] Task 6: Docs (AC11) and ledger (AC10).
- [ ] Task 7: Eval cases (AC9): the one-line `validate.py` change, `fix_pr: <n>`, promotion with rename; the `fix_merge` follow-up commit after merge.
- [ ] Task 8: `/pr-review` on the final commit (AC12); PR with the `Tests` and `Database migrations` checklist items filled in. No local `mvn verify` (project rule); CI is the gate.

## Dev Notes

- **Transactions, and why the first design was wrong.** `AuthService` is `@Transactional` at class level (:46) and `refresh` has no `noRollbackFor` (only `login` has one, :71), so every denial exit's throw rolls back the surrounding transaction. A row lock held across those exits would (F1) deadlock `markAllUsedByUserId` (bulk UPDATE with no `used` predicate, so it matches the locked row), `terminateSession` from the expiry exit (:207) and from the :247/:258 exits, all REQUIRES_NEW, with Postgres unable to see the cycle because the outer transaction waits in Java; and (F2) any MANDATORY-propagation revoke would be rolled back by the throw. Hence the outcome pattern in design item 3: the lock lives in one short `TransactionTemplate` block that never throws and writes only on rotation; every revoke runs after it, in its own transaction, exactly as today. Three alternatives were weighed: `noRollbackFor` plus MANDATORY twins of every revoke (keeps one transaction but makes the exception list load-bearing and still needs the bulk revoke-all rewritten); lock only for the happy-path rotation (cannot serve the multi-tab grace redirect, which must see the committed successor under the same lock); and the chosen one. The owner may still choose differently; the story is written for the chosen one.
- **Why not a family id instead of a successor link.** A family id would make logout "revoke the family" in one UPDATE, but the snapshot gap in item "Revoke-all has a snapshot gap" applies to that UPDATE too; the successor walk under the row lock is what actually serializes against a concurrent rotation. A family id can be added later if bulk operations need it.
- **Why not revoke all of the user's tokens on logout.** It would log out every device on one device's logout, a product change the owner has not asked for; the ledger item is about the session being logged out.
- **`deleteExpiredTokens`** (`RefreshTokenRepository.java:123-126`, hourly via `AuthCleanupService`) deletes predecessors before successors in practice (a predecessor is created earlier and has the same TTL); `ON DELETE SET NULL` makes the other order harmless.
- **Cookies.** `clearAuthCookies` (`SecurityUtil.java:215-219`) clears the JWT cookies via `loginTokenManager.deleteLoginToken` (`JwtManagerImpl.java:101`), `rtkn` via `CookieUtil.removeCookie(..., "Lax")`, and `skp` via `SkillarsProfileCookie.removeFrom` (`contract/SkillarsProfileCookie.java:57`). Keep the order; AC1's assertion is on these three.
- **Spring Security's own logout** (`SecurityConfiguration.java:172-182`, `POST /api/logout`) and the JWT filter's denial teardown (`JWTAuthorizationFilter.java:160-161`) both go through `terminateSession`, so they get the chain revoke for free; the `springSecurityLogoutEndpoint_*` ITs (:947, :967) must stay green.
- **Testing rules** (project-context.md): `@SpringBootTest` + Testcontainers, no DB mocks, Instancio for data, AssertJ, Awaitility for async; IT writes through `transactionTemplate`/`commitWrite` (bare `jdbcTemplate` writes never commit, Hikari auto-commit is off). Context ceiling is 17 (`.github/scripts/assert-context-count.sh`); adding a mock or annotation to `AuthResourceIT` forks a context and fails the gate.
- **Booking-fixture CHECK and other project gotchas** do not apply here; the only CHECK-like risk is none (no status column changes).

### Project Structure Notes

- All backend changes stay in `platform.security.{service,repo,api}` and `src/main/resources/db/migration`; no new module, no new endpoint, no frontend change.
- Follows `docs/deployment/migration-conventions.md` (additive first; NOT VALID FK; VALIDATE later).

### References

- `AuthService.java:46, :51, :153-301 (refresh), :303-305 (logout)`; `SecurityUtil.java:168-184, :215-219`; `RefreshToken.java`; `RefreshTokenRepository.java:57, :59-67, :79-82, :117-121, :123-126`; `UserRepository.java:161-164, :209-213`; `V138__baseline_schema.sql:1089-1098, :2586, :2593, :3580, :3586, :4314`.
- `AuthResourceIT.java:62-63, :124, :169, :314, :446, :490, :541, :748, :811, :947, :967, :1060-1108`; `RefreshTokenRepositoryIT.java:37`; `AuthServiceTest.java:72, :99, :119`; `SecurityUtilTest.java:71-118`; `AbstractIntegrationTest.java:29-80`.
- `docs/session-refresh-mechanism.md:114-126, :253, :476-483`; `docs/security-api-endpoints.md:32-34, :61-72, :187-199`; `docs/dev-docs/security/index.html:133, :146, :172-176, :417-452, :590-591, :610`; `docs/deployment/migration-conventions.md:88-89`.
- Prior stories: skillars-deferred-143 (liveness before rotation; REQUIRES_NEW revocation; :220-223 grace patch), skillars-deferred-144 (AC6.2, AC6.4, AC6.6, AC8), skillars-deferred-149 (AC3: epoch bump on theft), PR #242 (frontend logout ordering), PR #319 (`/api/logout` -> `terminateSession`), PR #384 (revoke-all on password change/reset).
- Eval cases: `evals/review/cases/_drafts/pending-logout-successor-revocation.yaml`, `pending-refresh-grace-window-account-wide.yaml`; `evals/review/README.md` "Open-bug cases".

## Dev Agent Record

### Agent Model Used

Story drafted 2026-11-03 by Claude Fable 5.1 (claude-fable-5-1); implemented 2026-11-03 by Claude Opus 5 (claude-opus-5).

### Debug Log References

Failures found and fixed during implementation, each listed because the cause was not what the story
predicted:

1. **`GraceResolution.chainRevoked()` would not compile.** A record component named `chainRevoked`
   generates an accessor of that name, which collided with the static factory. Factory renamed to
   `revokedChain()`.
2. **`AuthServiceTest.refresh_tokenOlderThanSessionEpoch` failed with "Token reuse detected".**
   `RefreshToken.createdAt` is assigned by `@PrePersist`, so a hand-built fixture has none, and the
   first version of `isBeforeSessionEpoch` skipped the check when `createdAt` was null — which made it
   fall through to the reuse branch. Resolved by making the check **fail closed**: once an account
   carries a revocation epoch, a token that cannot prove it was minted after it is refused. The column
   is `NOT NULL` in V138, so no loaded row reaches that branch; the test's value was in showing the
   fail-open default was wrong on principle.
3. **The revived `refresh_alreadyUsedToken_revokesAllAndReturns401` returned 200.** Its setup used a
   bare `jdbcTemplate.update` to consume the successor, and with Hikari `auto-commit = false` that write
   is rolled back when the connection is released, so the successor stayed live and the replay was
   legitimately redirected to it. Switched to this class's own `commitWrite`. Same defect
   skillars-deferred-144 AC5 found in the sibling tests; the test had been commented out since before
   that fix, so it never got the correction.
4. **The integration phase hung for 19 minutes, and it was my own fix.** The hop-bound fail-safe added in
   response to the code review called `markAllUsedByUserId` — a `REQUIRES_NEW` bulk `UPDATE` over the
   user's rows — from inside the locked walk, which still held `MAX_CHAIN_HOPS` of those rows. The inner
   transaction waited for locks only the outer one could release, and with no `lock_timeout` on that
   plain `UPDATE` it waited forever rather than erroring. Exactly the hazard this story's design exists to
   avoid, re-introduced while hardening it. The walk now reports the overrun and
   `SecurityUtil.terminateSession` runs the fallback after the walk's transaction has committed.
   (`timeout` does not exist on macOS, so two attempts to bound the run silently no-opped first.)
5. **`logoutAndRefreshRacingOnTheSameToken` returned 409 instead of 204, and the cause was the fast path
   I had just added.** The unlocked "already revoked?" pre-check loaded the entity, putting it in the
   persistence context; the `findByTokenHashForUpdate` that followed in the same transaction then
   returned that managed instance with its ORIGINAL state, because Hibernate takes the row lock but does
   not overwrite a managed entity from a new query. The `save()` therefore wrote a stale `@Version` and
   failed — a 409 on logout, and, worse, a lock that protected nothing. Replaced with a scalar
   `existsByTokenHashAndRevokedAtIsNotNull` projection so the locked read is the first and only load of
   that row; both the trap and the symptom are documented on that repository method. The AC5 race test is
   what caught it.

### Completion Notes List

**What shipped.** V224 adds `main.refresh_tokens.replaced_by_id` (self-referential FK, `NOT VALID`) and
`revoked_at`. Rotation writes the successor link. `AuthService.refresh` was restructured into one short
`TransactionTemplate` block that holds a `PESSIMISTIC_WRITE` lock on the presented row, decides
everything, returns a sealed `RefreshOutcome` without throwing, and writes only when it rotates; every
revoke, cookie clear and throw happens after that block commits. The grace window follows
`replaced_by_id` and nothing else. A token carrying `revoked_at` gets a plain 401 with no account-wide
revocation. A new `RefreshTokenRevocationService.revokeChainByTokenHash` (`REQUIRES_NEW`, idempotent via
a `revoked_at IS NULL` guard, bounded at 8 hops) is what `SecurityUtil.terminateSession` now calls, so
logout, Spring Security's `/api/logout`, the JWT filter's denial teardown and every refresh denial end
the whole chain.

**Four things the story did not foresee, decided here:**

1. **`User.authorities` is `FetchType.LAZY` and `spring.jpa.open-in-view` is `false`.** Building the
   `Principal` after the transaction closed would have thrown `LazyInitializationException` on the happy
   path — the most-travelled path in the method. The `Rotated` outcome therefore carries a pre-built
   `Principal` and `LoginResponse`, assembled while the user row is still managed. Had the outcome record
   carried the `User` instead, as the story's sketch implied, every successful refresh would have 500'd.
2. **The epoch check had to move ahead of the used/grace decision.** AC6 asks for a 401 on a token older
   than `security_session_invalidated_at`, but a revoke-all leaves every row `used`, so reached in the
   story's order such a token hit the reuse branch first and ran a *second* account-wide revocation. The
   user row is now loaded before the grace decision (one extra query on a path that loads it anyway).
3. **A revoked *successor* also means "session ended", not theft.** Logout walks forward from the
   cookie's row, so a predecessor a background tab still holds keeps `revoked_at = null`. Reading that as
   reuse would have signed the user out of every other device for clicking Logout — the exact failure AC3
   exists to prevent, arriving through a path AC3 did not describe. `resolveGraceSuccessor` reports it
   separately (`GraceResolution.revokedChain()`).
4. **The four denial outcomes collapse into one.** Expiry, missing user, stale epoch and a non-live
   account all do the same thing: revoke this session's chain, clear cookies, throw. One `Denied(failure)`
   record carries the exception. They route through `terminateSession`, which revokes the chain reachable
   from the *cookie's* row — so the explicit `markUsedByTokenHash(resolvedTokenHash)` that the
   skillars-deferred-143 code review added is gone: it existed only because a single-row revoke could not
   reach the successor the grace window had resolved to, and the walk reaches it from the predecessor now.

**Applied from this branch's own `/pr-review` (two arms, 14 findings).** The two that were genuine
regressions against pre-change behaviour: `resolveGraceSuccessor` stopped after two hops, so three or more
clients refreshing one cookie were answered as theft and the account revoked — the query it replaced found
the live token however deep the chain went, so the walk now runs to the chain's live end; and the 30 s
window was tested before the chain was walked, so a predecessor replayed 31 s after a logout also looked
like theft — the walk now runs first, because "was this chain revoked?" is true whatever the clock says
while the window only gates handing over a live successor. Also applied: both locking queries gained a
bounded 5 s `lock.timeout` (every other `*ForUpdate` query in the repo pairs its lock with one; these are
reachable from two `permitAll` endpoints) with a bounded retry in `terminateSession` so a contended logout
still revokes; a real deadlock or lock timeout now answers 401 instead of 500
(`PessimisticLockingFailureException`); V224 gained a partial index on `replaced_by_id`, because
`ON DELETE SET NULL` makes PostgreSQL search that column once per row the hourly sweep deletes and the
migration's own comment claimed the opposite; the hop bound fails safe instead of leaving a rotatable
token; the JWT filter's per-request teardown costs one scalar read again rather than up to eight row
locks; `validate.py` rejects a malformed `fix_pr`; the provisioning checklist carries V224 and its
outstanding `VALIDATE`; and a test comment that claimed a negative assertion proved a branch ran was
corrected, with the positive counterpart added.

**One finding corrected rather than fixed.** The security arm showed that `isBeforeSessionEpoch` closes the
revoke-all snapshot gap only for the two callers that stamp the epoch AFTER the bulk revoke
(`changePassword`, `completePasswordReset`). `AdminUserService.suspendUser`, `AgeVerificationService` and
`AccountSuspensionEventListener` stamp it first, so a successor committed between their two statements
passes the check; `locked = true` covers it until an admin reactivation clears that without revoking. The
javadoc now states the limit precisely instead of overclaiming, and the ordering plus the reactivate hole
are filed in `deferred-work.md` rather than fixed by reordering three services outside this story's scope.

**Deliberately not done.** `markAllUsedByUserId` still does not set `revoked_at` (six callers; the epoch
check masks the consequence), and the V224 FK's `VALIDATE` ships later. Both filed in `deferred-work.md`.
No frontend change: the SPA does not call `POST /api/auth/refresh` at all.

**Validation.** Targeted per `docs/validation-strategy.md`; no local `mvn verify` (project rule, CI is the
gate). Final run, all green: `AuthResourceIT` 37/37 (11 new, the revived test, and the updated
grace-window one), `RefreshTokenRepositoryIT` 1/1, `AuthServiceTest` 14/14 (9 new), `SecurityUtilTest`
8/8 (3 new), `MigrationConventionLintTest` 30/30, `NativeModifyingVersionAuditTest` 2/2,
`IntegrationTestConventionTest` 6/6 (the context ceiling is unaffected: the new `@Autowired` field does
not fork a context), `check-docs` OK after rebuilding the search index.

### File List

- `src/main/resources/db/migration/V224__refresh_token_successor_link_and_revoked_at.sql` (new)
- `src/main/java/com/softropic/skillars/platform/security/repo/RefreshToken.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/repo/RefreshTokenRepository.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/service/RefreshTokenRevocationService.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/service/AuthService.java` (modified)
- `src/main/java/com/softropic/skillars/platform/security/service/SecurityUtil.java` (modified)
- `src/test/java/com/softropic/skillars/platform/security/api/AuthResourceIT.java` (modified)
- `src/test/java/com/softropic/skillars/platform/security/service/AuthServiceTest.java` (modified)
- `src/test/java/com/softropic/skillars/platform/security/service/SecurityUtilTest.java` (modified)
- `docs/session-refresh-mechanism.md` (modified)
- `docs/security-api-endpoints.md` (modified)
- `docs/dev-docs/security/index.html` (modified)
- `docs/dev-docs/assets/search-index.js` (regenerated)
- `evals/review/bin/validate.py` (modified)
- `evals/review/README.md` (modified)
- `evals/review/cases/logout-successor-revocation.yaml` (moved from `_drafts/pending-`, AC9)
- `evals/review/cases/refresh-grace-window-account-wide.yaml` (moved from `_drafts/pending-`, AC9)
- `docs/deployment/provisioning-checklist.md` (modified)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified)

### Change Log

- 2026-11-03 — applied this branch's own `/pr-review` findings: chain-depth and revoked-chain ordering
  regressions, bounded lock timeouts with a logout retry, lock-failure to 401, the FK index, a fail-safe
  hop bound, the persistence-context trap in the fast path, validator and checklist gaps.
- 2026-11-03 — implemented all 12 ACs. V224 + successor link + `revoked_at`; refresh restructured onto a
  locked decide-then-act block; chain revocation on logout; successor-only grace window; session-epoch
  check; docs, ledger and eval cases updated.
