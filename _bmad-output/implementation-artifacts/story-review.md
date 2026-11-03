# Story Review: skillars-deferred-166

- **Story:** `skillars-deferred-166-logout-successor-revocation-and-refresh-grace-window-scope` (status in sprint-status map: `ready-for-dev`)
- **Audit date:** 2026-11-03
- **Real HEAD:** `275c272d skillars-deferred-166: create story (logout chain revocation; successor-only refresh grace window)`
- All citations were checked against this HEAD. The story's own claim of `c7cb601b` (the parent commit) was not trusted.
- **Method:** four read-only layers (citations, ledger/precedent, mechanism, corner cases), then a manual adversarial re-read of the load-bearing findings by the orchestrator (Step 4b).

## Verdict in one line

The citations and precedents are sound, but the core design (a row lock held for the whole of `refresh`) **deadlocks, or silently loses its writes, on more exits than the story names**. It needs a design amendment before dev starts. Several ACs also contradict the code's cookie-clearing and the eval tooling.

---

## 1. Citation verification (Layer 1)

About 75 citations checked. About 70 MATCH, including all `AuthService`, `SecurityUtil`, `RefreshToken`, `RefreshTokenRepository`, `UserRepository`, V138, frontend, IT/unit test and docs line numbers. V223 is the latest migration and V224 is free.

| Citation | Verdict | Evidence / correction |
|---|---|---|
| Stale comment "near :243-260 cites `:158-165`" | DRIFTED (minor) | The comment text is at `AuthService.java:227-238` (the `:158-165` cite is on :229). `:243-260` is the code below it. `AuthResourceIT.java:750` does repeat the stale cite. |
| `SecurityUtilTest` "four `terminateSession_*` tests (:71-118)" | DRIFTED (minor) | Five tests, at :71, :82, :106, :118 and :128. The range is :71-139. |
| "Tag RS01/RS02 `[PICKED UP]` at story start" (AC10) | DRIFTED (minor) | `deferred-work.md:19-20` already carries the tag. |
| V224 self-FK `refresh_tokens(id)` | DRIFTED (minor) | The table is `main.refresh_tokens`; the migration must be schema-qualified. |
| `refresh_alreadyUsedToken_revokesAllAndReturns401` :446 "commented out with a TODO" | MATCH, imprecise | The TODO is on :444 and the `/* @Test` opens at :445. Its stated reason (the SPA dropped `POST /refresh`) does not block reviving it, since the IT calls the endpoint directly. |
| Not covered by the story | gap | `AuthServiceTest` stubs `findFirstByUserIdAndUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc` at :80, :92, :114 and :138. Deleting that method (design item 5) breaks those tests, and Task 5 does not say so. |
| `RefreshTokenRepository` javadoc :38-47 also describes the newest-live query | gap | AC11 already says to update the class javadoc, so this is covered. |

## 2. Ledger & precedent attribution (Layer 2)

All three source kinds (ledger/story files, code comments, git log/blame) were checked per claim.

| Claim | Verdict |
|---|---|
| RS01 `[high/concurrency]` and RS02 `[high/logic]` exist in `deferred-work.md` as quoted. The "deleted outright once implemented" rule exists. No existing V224 or `VALIDATE CONSTRAINT` bullet. | CONFIRMED |
| Draft eval cases exist with `fix_pr: pending` and `input_rev`. `validate.py` and the README "Open-bug cases" section exist. Both drafts validate with 0 problems today. | CONFIRMED |
| deferred-143 `:220-223` grace patch. | CONFIRMED. The "newest live" wording is the story's inference from the code, not a quote from 143. |
| 143 liveness-before-rotation and REQUIRES_NEW revocation; 144 AC6.2, AC6.4 and AC6.6 (the `used = false` predicate, PR #251 / `74d405d2`). | CONFIRMED |
| 144 AC8. | UNVERIFIABLE as a precedent (the story states no claim; AC8 is the filter fast-path removal). |
| 149 ":77 epoch bump on theft". | MISLABELED. The behaviour is real, but the bump is AC3 and its snippet; :77 is a Dev Notes bullet. |
| PR #242 (logout ordering), #319 (`/api/logout` → `terminateSession`), #384 (revoke-all on password change/reset), #353 (`BookingCancellationRefundIT`, `CountDownLatch`) | CONFIRMED via git history and code |
| `migration-conventions.md:88-89` NOT VALID rule. `IntegrationTestConventionTest`, `assert-context-count.sh` (ceiling 17), `MigrationConventionLintTest`. | CONFIRMED |
| Ledger gives RS02's input rev as `651ed1effc04`; the eval case pins `fa62da9dc634`. | Minor ledger inconsistency, probably the ledger is wrong. Not a story defect. |

## 3. Mechanistic claims (Layer 3, deciding lines re-read by me for the load-bearing ones)

| Claim | Verdict |
|---|---|
| `logout` is one call to `terminateSession`, which clears the context, reads `rtkn`, calls `markUsedByTokenHash`, then clears cookies (`SecurityUtil.java:168-184`). | CONFIRMED. |
| `markUsedByTokenHash` is `REQUIRES_NEW` with `AND used = false`. Against an uncommitted rotation it waits, re-checks and matches zero rows, so the successor survives. | CONFIRMED (Postgres READ COMMITTED semantics; successor INSERT is deferred to commit). |
| `RefreshToken` has `@Version` and no successor/family/revocation column. | CONFIRMED. |
| Grace branch uses the newest live token for the user (any device). Rotation writes nothing linking old to new. The :246/:257 exits revoke `resolvedTokenHash`, which is the row the grace branch picked. | CONFIRMED (`AuthService.java:169-192`, `:243`). |
| Both theft branches revoke via `REQUIRES_NEW`, then the method throws and its own transaction rolls back (no `noRollbackFor` on `refresh`; only `login` has it, :71). | CONFIRMED by me. |
| `invalidateSessionsForUser` (REQUIRES_NEW) and `...InCurrentTransaction` (MANDATORY) exist as cited. | CONFIRMED. |
| Refresh does not currently check `securitySessionInvalidatedAt`. The JWT filter does, but only in the DB-reauth branch. | CONFIRMED. Types are compatible (`Instant`, UTC session); the column is nullable. |
| "Six revoke-all callers also bump the epoch." | PARTIALLY. `GdprErasureService:810` shows no epoch bump (Layer 3). I did not independently re-check this one. |
| `deleteExpiredTokens` removes predecessors first; `ON DELETE SET NULL` makes the other order harmless; the user FK cascade works with a self-FK. | CONFIRMED. |
| Spring Security `/api/logout` and the JWT filter's denial teardown both go through `terminateSession`. | CONFIRMED. |

## 4. Findings that survived Step 4b

### F1 (HIGH). The lock in design item 3 self-deadlocks on more exits than the story names
- The story holds `findByTokenHashForUpdate` for the whole of `refresh` (class-level `@Transactional`, `AuthService.java:46`) and flags only the :246 and :257 exits.
- These other `REQUIRES_NEW` writes also hit the locked row from inside the same transaction:
  - Both theft branches call `markAllUsedByUserId(ownerId)` (`AuthService.java:183` and `:198`). It is a bulk `UPDATE ... WHERE user_id = :userId` with no `used` predicate (`RefreshTokenRepository.java:79-82`), so it matches the locked row and blocks. AC3 and AC8 both rely on the theft path working.
  - The expiry exit calls `terminateSession` at :207. After item 4, `terminateSession` calls the REQUIRES_NEW chain revoke on the locked, unused row.
  - The :247 and :258 exits also call `terminateSession` right after their explicit revoke. Swapping only the explicit call changes nothing.
- Today none of this blocks, because `refresh` holds no lock before these calls (the comment block at `AuthService.java:225-242` says so).
- Postgres cannot see the cycle, because the outer transaction waits in Java on the inner one. I grepped `src/main/resources` and found no application-level `lock_timeout` (only migration `SET LOCAL`). I did not check JDBC URL or test-profile settings, so "hangs rather than 409" is medium confidence. The deadlock itself is high confidence.

### F2 (HIGH). The "in-transaction variant" does not survive the throw that follows it
- Every denial exit throws a `RuntimeException` (`BadCredentialsException`, `LockedException`, `DisabledException`), and `refresh` has no `noRollbackFor`. The outer transaction rolls back, so a MANDATORY-propagation revoke is undone.
- That is exactly why 143 made the revoke `REQUIRES_NEW` (javadoc at `RefreshTokenRepository.java:88-96`).
- The cited precedent, `invalidateSessionsForUserInCurrentTransaction`, works for admin suspension only because that caller commits.
- The design needs a mechanism it does not pin. Options: `noRollbackFor` on `refresh`; releasing the lock before a REQUIRES_NEW revoke; or splitting lock-holding rotation from denial handling. AC4's "assert which repository method is called" test cannot detect a rolled-back write; it needs an IT that checks the DB row afterwards.

### F3 (MEDIUM). AC1, AC3, AC5 and design item 3 contradict the code's own cookie clearing
- `clearAuthCookies` always adds `Set-Cookie: rtkn=; Max-Age=0` (`SecurityUtil.java:215-219`; `CookieUtil.removeCookie` adds the header at :45-55). Every logout response therefore carries an `rtkn` Set-Cookie.
- AC1 ("401 with no Set-Cookie") is false: T1 is used, so refresh takes the theft branch, which calls `clearAuthCookies` (:186 and :201).
- AC3 ("no cookies are set" while "the theft path runs") and item 3 ("answers 401 without any Set-Cookie") are false for the same reason.
- AC5 ("at most one response carries a Set-Cookie for `rtkn`") is false whenever logout is one of the two responses. The tests must distinguish a non-empty `rtkn` value from a deletion header.

### F4 (MEDIUM). AC9 cannot be satisfied inside the PR
- `validate.py:83-87` requires `fix_merge` to resolve in git for any non-pending case, and derives `input_rev = fix_merge^1` (:103). A squash sha does not exist before merge.
- "0 problems" with `fix_pr: <number>` is only reachable after merge. The story's text ("filled in after merge" and "in the same PR") is self-contradictory; it needs a follow-up commit or ordering change.
- Promotion must also rename the files away from `pending-*` (the case id must match the filename).

### F5 (MEDIUM). Revoked-equals-theft makes a logout race a full account logout, and the story does not say so
- Item 5 and AC3 send any used, revoked token to the theft path (revoke-all plus epoch bump). A stale tab refreshing just after Logout therefore signs out the user's other devices.
- This is not a regression: today a logged-out token with a null `rotatedAt` already takes that branch (`AuthService.java:195-203`). It should be accepted explicitly or replaced with a plain 401 for revoked tokens.

### F6 (LOW-MEDIUM). Chain revoke loses the idempotence of `markUsedByTokenHash`
- `markUsedByTokenHash` has `AND used = false`, so repeats are zero-row. Item 4 has no such guard, so every call locks and rewrites up to 8 rows and overwrites `revokedAt`.
- `terminateSession` also runs on every genuine-denial request in `JWTAuthorizationFilter.java:160-161`, so a client replaying a stale JWT repeats this per request.
- A new bulk revoke must also bump `version`; `NativeModifyingVersionAuditTest` audits that.

### F7 (LOW). AC8 understates the deploy-time effect
- `REFRESH_GRACE_WINDOW` is 30 s (`AuthService.java:51`). Pre-migration rotated tokens (and tokens rotated by old instances during a rolling deploy) have no `replaced_by_id`, so a replay in that window goes to the theft path and signs out every device.
- The impact is small, because the SPA never calls `POST /api/auth/refresh` (`auth.api.js:3-5`; verified). It should still be written down as an accepted trade-off.

### F8 (LOW). AC6 epoch check under-specified
- The column is nullable `timestamp without time zone` and the entity maps it `insertable=false, updatable=false` (`User.java:166`). The story does not say a null epoch skips the check.
- The user row is only loaded after the grace and expiry branches (:243-245), so the check must run on the resolved successor, not only the presented token, and must be a cookies-only exit.
- `GdprErasureService:810` may not bump the epoch (see section 3). It locks the user, so the practical gap is small.

## 5. What did not survive re-verification

- **Layer 1's D1/D2 and Layer 4's #1/#2** are the same defect seen twice. I merged them into F1 and F2 and confirmed them against the code myself.
- **"Rolling-deploy window is a large exposure"** (Layer 4 #6) was downgraded to F7 because the SPA does not use the endpoint.
- **"`used=true` plus `revoked_at` is redundant"**: dropped. Revoke-all leaves `revoked_at` null, so the pair is the only way to tell rotated from ended. It is safe only because the grace branch also requires the successor to be unused.
- **Second concurrent refresh sees stale state, or the 8-hop chain bound is too small**: dropped. `open-in-view` is false, there is no earlier read of the row, lock order T0 then T1 is consistent, and chain depth is bounded by rotations since the holder's last cookie.
- **Frontend "no change" claim**: held up. I found no other in-repo client of `POST /api/auth/refresh`.

## 6. Recommendation (per claim)

| Area | Confidence | Basis |
|---|---|---|
| Problem statement (RS01, RS02) | High | Re-read in code by me and three layers. |
| Citations and precedents | High | About 75 citations and 12 precedents checked against the real HEAD. The minor drifts above are cosmetic. |
| Core design as written (item 3 + item 4) | **Do not start dev** | F1 and F2 were re-read by me and hold. The story needs a pinned design for how `refresh` takes the lock and still revokes durably on denial. |
| AC1, AC3, AC5 wording | Fix before dev | F3 is verifiable from `clearAuthCookies`. |
| AC9 | Fix before dev | F4 is verifiable from `validate.py`. |
| F5, F6, F7, F8 | Decide or document | Lower severity; each needs one line in the story. |

**Not independently re-checked by me:** the GDPR epoch-bump claim; whether any JDBC/test-profile `lock_timeout` exists (which decides hang versus 409); the exact `NativeModifyingVersionAuditTest` rules; PR numbers beyond what git log showed.

Suggested design amendment to hand back to story creation: take the row lock only for the happy-path rotation, and route every denial exit through a path that has released the lock or does not hold it, using REQUIRES_NEW as today. Alternatively, add `noRollbackFor` on the denial exceptions plus MANDATORY variants of both `markAllUsedByUserId` and `terminateSession`. This is the owner's call, with pros/cons to weigh.
