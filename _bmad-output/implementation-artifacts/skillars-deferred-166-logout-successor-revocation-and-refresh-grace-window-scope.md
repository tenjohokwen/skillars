# Story skillars-deferred-166: Logout revokes the whole rotation chain; the refresh grace window follows the token's own successor

Status: ready-for-dev

<!-- CREATED 2026-11-03 (/bmad-create-story). Owner-requested bundle of exactly two ledger items,
     RS01 and RS02, from deferred-work.md "Deferred from: review-evals baseline real-sample,
     owner-verified (2026-11-03)". Both were found by the AI reviewer arms, judged real, and
     confirmed by the owner against HEAD on 2026-11-03. Every citation below was re-read at HEAD
     c7cb601b by an Explore pass before drafting; two ledger claims turned out stale (see
     "Verified state at drafting"). Each item is also an eval case under evals/review/cases/_drafts/
     (pending-*.yaml); AC9 flips them. -->

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
- **Revoke-all has a snapshot gap.** Every revoke-all caller (`UserProfileService.changePassword` :175-178, `PasswordResetService.completePasswordReset` :74-76, `AccountSuspensionEventListener.suspend` :80-99, `AdminUserService` :164-165, `AgeVerificationService` :263-265, `GdprErasureService` :810) runs `markAllUsedByUserId` (`RefreshTokenRepository.java:79-82`, bulk UPDATE, REQUIRES_NEW) plus `UserRepository.invalidateSessionsForUser` (:161-164) / `...InCurrentTransaction` (:209-213), which bump `security_session_invalidated_at` (V158). A rotation that commits after the bulk UPDATE's snapshot leaves a successor the UPDATE never saw. AC6 closes it cheaply on the refresh side.
- **Tests that exist and what is missing.** `AuthResourceIT` (`src/test/java/.../security/api/AuthResourceIT.java`, extends `AbstractIntegrationTest`, `@Sql(SecurityIT.SEC_DATA_SQL_PATH)` at :62-63, no extra annotations or mocks so it shares the context): `refresh_validUnusedToken_rotatesTokenAndReturns200` (:314), `refresh_expiredToken_returns401` (:490), `logout_marksTokenUsedAndClearsCookies` (:541), the account-liveness trio (:635, :686, :718), `refresh_accountLockedAndStaleCookieReplayedInGraceWindow_revokesTheSuccessorToken` (:748, the only grace-window IT and the model for new ones), `markUsedByTokenHash_commitsEvenWhenTheCallingTransactionRollsBack` (:811), CSAM (:839-887), `springSecurityLogoutEndpoint_*` (:947, :967). **Missing:** a grace-window success test, a two-session test, a logout-after-rotation test; `refresh_alreadyUsedToken_revokesAllAndReturns401` (:446) is commented out with a TODO. Seeding goes through `transactionTemplate.execute` (:124, :169) and `commitWrite(sql, args...)` (:1060-1062); helpers `usedFlagOf(hash)` (:1065), `cookieHeaders(rtkn)` (:1078), `extractCookieValue` (:1084), `sha256Hex` (:1094), `insertUser` (:1108). `RefreshTokenRepositoryIT.java:37` asserts `markUsedByTokenHash` is a no-op on a used row: that assertion stays true (AC2 adds a new method, it does not change this one). `AuthServiceTest` (Mockito) covers grace-with-no-successor (:72), never-rotated (:99), rotated-long-ago (:119). `SecurityUtilTest` has four `terminateSession_*` tests (:71-118).
- **Stale comments to fix while there:** the `AuthService` comment near :243-260 cites `:158-165` for the grace branch (it is :169-192); `AuthResourceIT.java:750` repeats it.
- **Docs that describe the old behaviour:** `docs/session-refresh-mechanism.md` :114-126 (claims `authApi.skillarsRefresh()` exists; it does not), :253, :476-483; `docs/dev-docs/security/index.html` :133, :146, :172-176, the sequence diagram :417-452 (:445 "use newest live successor"), :590-591, footer stamp :610; `docs/security-api-endpoints.md` :32-34, :61-72, :187-199.
- **Migrations.** `refresh_tokens` is created in `V138__baseline_schema.sql:1089-1098` (PK :2586, `token_hash` UNIQUE :2593, `idx_rt_token_hash` :3580, `idx_rt_user_id` :3586, FK `user_id -> main."user"(id) ON DELETE CASCADE` :4314). No later migration alters it. Latest migration is `V223__coach_safeguarding_check.sql`; this story's is **V224**.

## Design (pinned; do not re-derive)

The root cause of both items is the same: a rotated token has no link to its successor, so every path that needs "the token this one became" guesses ("newest live for the user") or gives up ("only if still unused"). The fix is the link plus a distinction the code does not have today between **rotated** (replaced, successor live) and **revoked** (session ended).

1. **Schema (V224, additive):** `refresh_tokens.replaced_by_id bigint NULL` (the successor's id, set in the same transaction as the rotation) and `refresh_tokens.revoked_at timestamptz NULL`. Self-referential FK `replaced_by_id -> refresh_tokens(id) ON DELETE SET NULL`, added `NOT VALID` (table grows; `docs/deployment/migration-conventions.md` rule at :88-89) with the `VALIDATE` in a later migration (file it as a ledger bullet, same as prior NOT VALID constraints). No new index: every lookup walks predecessor to successor by primary key. `MigrationConventionLintTest` must pass.
2. **Rotation writes the link.** In `refresh` (:284-298), after the successor row is saved, set `old.replacedBy = successor.id` in the same transaction, so a reader who can see the successor can always find it from the predecessor.
3. **A row lock on the presented token in both `refresh` and logout.** Add `RefreshTokenRepository.findByTokenHashForUpdate(String)` (`@Lock(PESSIMISTIC_WRITE)` on the derived query). `refresh` uses it at :154-161 instead of `findByTokenHash`; the chain-revoke in item 4 uses it too. Two concurrent refreshes of one token then serialize (the second sees `used=true`, `rotatedAt` fresh, `replacedBy` set, and takes the grace branch to the real successor), and logout versus refresh serialize in both orders: refresh first, then logout walks to the successor and revokes it; logout first, then refresh finds `revokedAt != null` and answers 401 without any Set-Cookie. The existing `@Version` handling at :273-279 stays as a backstop; it should no longer be the first line of defence.
4. **Chain revocation for logout.** New `RefreshTokenRepository.revokeChainByTokenHash(String)` (or a service method; REQUIRES_NEW like `markUsedByTokenHash`, for the same reason deferred-143 made revocation commit independently): lock the presented row with `findByTokenHashForUpdate`, set `used=true`, `revokedAt=now`, bump `version`, then follow `replacedBy` and do the same to each successor until a row has no successor; bound the walk (e.g. 8 hops) and log if the bound is hit. `SecurityUtil.terminateSession` (:168-184) calls this instead of `markUsedByTokenHash`. `markUsedByTokenHash` itself is unchanged (other callers; `RefreshTokenRepositoryIT:37` keeps passing). **Self-deadlock rule (project memory, 55P03 -> 409):** a REQUIRES_NEW method that locks a row must never be called by a transaction that already holds that row's lock. `refresh` holds the presented row's lock from item 3, so its own revoke-on-denial exits (:246, :257) must use an in-transaction variant (precedent: `UserRepository.invalidateSessionsForUserInCurrentTransaction`, MANDATORY propagation), not the REQUIRES_NEW one.
5. **Grace branch follows the successor only.** Replace the :169-192 lookup: a presented token that is `used`, not `revoked`, with `rotatedAt + REFRESH_GRACE_WINDOW > now` and a non-null `replacedBy` is redirected to `replacedBy` (walk at most one further hop if that successor is itself rotated within the window, to cover a rapid double rotation; bound it), provided the successor is unused and unexpired. Any other used token, including one with `revokedAt != null`, is reuse: keep the existing theft response (`markAllUsedByUserId`, `invalidateSessionsForUser`, `clearAuthCookies`, 401). Delete `findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc` once nothing calls it. The denial exits at :246 and :257 now revoke the chain of the presented token (in-transaction variant), never a row chosen by "newest live".
6. **Refresh honours the session epoch.** Before rotating, `refresh` rejects (401, cookies cleared, no revoke-all) any token whose `createdAt` is before the user's `securitySessionInvalidatedAt`. That closes the revoke-all snapshot gap for refresh tokens the same way V158 closed it for JWTs, without touching the six revoke-all callers.
7. **Frontend: no change.** `MainLayout.handleLogout` (:707-752) already stops monitoring before the backend call (PR #242) and clears `rint` after; `useSession.handleLogout` (:73-100) likewise. Do not add a `POST /api/auth/refresh` caller.

## Acceptance Criteria

1. **Given** a user logs in and the refresh endpoint rotates their token (T0 -> T1), **when** `POST /api/auth/logout` is called with the old `rtkn` cookie T0, **then** both T0 and T1 are `used` with `revoked_at` set, the response clears `rtkn`, `skp` and the JWT cookies, and a subsequent `POST /api/auth/refresh` with T1 answers 401 with no Set-Cookie. (RS01)
2. **Given** a user with two live sessions A and B on different devices, **when** A's token is rotated (A0 -> A1) and within `REFRESH_GRACE_WINDOW` a request still carrying A0 calls refresh, **then** it is redirected to A1 (A1 becomes used, a new A2 is issued) and B's token is untouched and still refreshes successfully afterwards. (RS02)
3. **Given** a token that was revoked by logout, **when** it is presented to refresh within what would have been its grace window, **then** the response is 401, no cookies are set, and the reuse (theft) path runs, not the grace path.
4. **Given** the account-locked and missing-user exits in `refresh`, **when** a stale cookie is replayed in the grace window, **then** only the presented token's own chain is revoked (the existing IT at `AuthResourceIT:748` is updated to assert the chain and that a second device's token survives), and no self-deadlock occurs (the exits use the in-transaction variant; a test proves a REQUIRES_NEW call is not made while the row lock is held, e.g. by asserting the repository method used).
5. **Given** logout and refresh race on the same token, **when** both run concurrently (two threads, `CountDownLatch` start gate, precedent `BookingCancellationRefundIT` from PR #353), **then** in every observed order no live, unrevoked token for that chain remains and at most one of the two responses carries a Set-Cookie for `rtkn`, which is then rejected by a follow-up refresh.
6. **Given** a revoke-all has run for a user (any of the six callers; use `changePassword`), **when** a refresh token created before `securitySessionInvalidatedAt` is presented, **then** refresh answers 401 and clears cookies without running revoke-all again.
7. **Given** two concurrent refreshes of the same unused token (multi-tab), **when** they run under the new row lock, **then** exactly one rotation happens and the second caller receives the successor via the grace branch (or 401 "Concurrent refresh" from the `@Version` backstop), never a 500 and never another session's token.
8. **Given** the V224 migration, **when** `MigrationConventionLintTest` runs and the app boots against a database that already holds rotated tokens, **then** the lint passes, existing rows have `replaced_by_id = NULL`, and the grace branch treats a pre-migration rotated token (no successor link) as reuse, not as a redirect.
9. **Given** the fix PR number is known, **when** the PR is opened, **then** both eval cases `pending-logout-successor-revocation.yaml` and `pending-refresh-grace-window-account-wide.yaml` have `fix_pr: <PR number>`, a `fix_merge:` line (the squash commit sha, filled in after merge or on the merge commit), `input_rev` removed, and `evals/review/bin/validate.py evals/review/cases/_drafts/*.yaml` reports 0 problems. Promote them to `evals/review/cases/` in the same PR (they are owner-verified, confidence high).
10. **Given** the ledger, **when** the story starts, **then** RS01 and RS02 carry `**[PICKED UP skillars-deferred-166]**`; when the PR merges they are deleted; a new bullet records the deferred `VALIDATE CONSTRAINT` for the V224 FK.
11. **Given** the docs, **when** the PR is opened, **then** `docs/session-refresh-mechanism.md` (:114-126 no longer claims `authApi.skillarsRefresh()` exists; the grace window is described as successor-only; the table at :476-483), `docs/security-api-endpoints.md` (:61-72, :187-199) and `docs/dev-docs/security/index.html` (:133, :146, :172-176, the :417-452 diagram, :590-591, footer stamp :610 updated to the merge sha) describe the new behaviour; `RefreshTokenRepository`'s class javadoc (:13-53) lists the new methods and drops the deleted one.
12. **Given** `/pr-review`, **when** the branch is ready, **then** it has been run on the final commit and its findings fixed or consciously deferred to the ledger; the record for HEAD exists (the hook enforces it).

## Tasks / Subtasks

- [ ] Task 1: Migration V224 (AC8) — columns, NOT VALID self-FK with ON DELETE SET NULL, header comment citing this story; run `MigrationConventionLintTest`.
- [ ] Task 2: Entity + repository (AC1-AC5) — `replacedBy` (Long id, not a relation, matching `userId`'s style) and `revokedAt` on `RefreshToken`; `findByTokenHashForUpdate`; chain revoke (REQUIRES_NEW) and its in-transaction twin; delete the newest-live query; update the class javadoc.
- [ ] Task 3: `AuthService.refresh` (AC2, AC3, AC4, AC6, AC7) — lock on read; epoch check; successor-only grace with bounded walk; link written at rotation; denial exits use the in-transaction revoke; fix the stale `:158-165` comment.
- [ ] Task 4: `SecurityUtil.terminateSession` (AC1, AC5) — call the chain revoke; keep cookie clearing order; update `SecurityUtilTest`.
- [ ] Task 5: Tests — `AuthServiceTest` branches (grace -> successor, grace -> revoked, no successor on a pre-migration token, epoch rejection); `AuthResourceIT` new tests for AC1, AC2, AC3, AC6, AC7 and the concurrency test for AC5 (no new Spring context: no annotations, no `@MockitoBean`; `IntegrationTestConventionTest` enforces it); revive `refresh_alreadyUsedToken_revokesAllAndReturns401` (:446) if its TODO reason no longer holds, else say why in Completion Notes; adjust `AuthResourceIT:748`.
- [ ] Task 6: Docs (AC11) and ledger (AC10).
- [ ] Task 7: Eval cases (AC9) and `validate.py`.
- [ ] Task 8: `/pr-review` on the final commit (AC12); PR with the `Tests` and `Database migrations` checklist items filled in. No local `mvn verify` (project rule); CI is the gate.

## Dev Notes

- **Transactions.** `AuthService` is `@Transactional` at class level (:46). The row lock from `findByTokenHashForUpdate` is held for the whole `refresh`; everything that must commit independently of `refresh`'s outcome (the theft revoke-all, the chain revoke from logout) is REQUIRES_NEW and must not touch the locked row from inside `refresh`. The denial exits inside `refresh` therefore use the MANDATORY-propagation twin. Precedent for the pair: `UserRepository.invalidateSessionsForUser` (:161-164, REQUIRES_NEW) and `invalidateSessionsForUserInCurrentTransaction` (:209-213, MANDATORY).
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
- Prior stories: skillars-deferred-143 (liveness before rotation; REQUIRES_NEW revocation; :220-223 grace patch), skillars-deferred-144 (AC6.2, AC6.4, AC6.6, AC8), skillars-deferred-149 (:77 epoch bump on theft), PR #242 (frontend logout ordering), PR #319 (`/api/logout` -> `terminateSession`), PR #384 (revoke-all on password change/reset).
- Eval cases: `evals/review/cases/_drafts/pending-logout-successor-revocation.yaml`, `pending-refresh-grace-window-account-wide.yaml`; `evals/review/README.md` "Open-bug cases".

## Dev Agent Record

### Agent Model Used

Claude Fable 5.1 (claude-fable-5-1), story drafted 2026-11-03

### Debug Log References

### Completion Notes List

### File List
