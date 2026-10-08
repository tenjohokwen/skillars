# Pre-implementation story review — `skillars-deferred-149`

| | |
|---|---|
| **Story** | `skillars-deferred-149-subscription-ownership-session-revocation-review-eligibility-gap-and-ci-build-hardening.md` |
| **Status in `sprint-status.yaml`** | `ready-for-dev` (line 347) |
| **Audit date** | 2026-10-08 |
| **Real HEAD verified against** | `df07a842` — *"Story Deferred-148: Player-ID Corruption Fixes, Dispute Cross-ID-Space Bug, and Router Role-Gate Hardening (#255)"* |
| **Working tree at audit time** | `deferred-work.md` + `sprint-status.yaml` modified; the story file itself untracked |

Every citation below was re-opened at this HEAD. The story's own header claims **"All citations below
were independently re-verified directly against HEAD `df07a842` … not copied from the ledger."** That
claim is mostly borne out — several ledger line numbers were correctly re-anchored (e.g. the ledger's
`AuthService.java:153,:160` → the story's correct `:157,:166`) — but it fails in at least two places
where a ledger range was reproduced verbatim and is stale at HEAD (see **C-14**, **F15**).

Headline: **two blocking defects in AC3** (the revocation write would be silently rolled back; the new
`User` column breaks Envers auditing), **one CI-failing migration**, and **one AC8 item silently
dropped** while AC8 claims to close "all ~9 items".

---

## 1. Citation verification (Layer 1)

### 1.1 Matches — verified exactly as written

| Citation | Evidence at `df07a842` |
|---|---|
| `SubscriptionResource.java:91-92` | `:92` = `@PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")`, `:93` = `getMyPlayerSubscription` ✔ |
| `SubscriptionResource.java:134-136` | `:134` `private Long currentParentId()`, `:135` `return securityUtil.requireCurrentUserId();` ✔ |
| `SubscriptionService.java:899-904` | `assertPlayerOwnership` body, exactly 899→904 ✔ |
| `SubscriptionService` `:113, :319, :382, :457` | all four `assertPlayerOwnership(parentUserId, playerId);` call sites ✔ |
| `routes.js:205-209`, `:208` | subscription route block; `:208` = `meta: { requiresAuth: true, role: 'PARENT' }` ✔ |
| `routes.js:124,132,151,160` | all four = `roles: ['PARENT', 'PLAYER']` ✔ |
| `CoachRegistrationResourceIT:205-238` seed `:221`; `:243-273` seed `:259` | tests at 205 / 243; bare `jdbcTemplate.update(` at 221 / 259 ✔ (closing braces land at 239 / 274 — one line past the quoted ranges, immaterial) |
| `ParentRegistrationResourceIT:198-236` seed `:214`; `:237-271` seed `:253` | same shape ✔ |
| `application.yaml:183` | `auto-commit: false` ✔ |
| `AuthResourceIT.java:822-824` | `private void commitWrite(...) { transactionTemplate.execute(...) }` — byte-for-byte the snippet in Design B ✔ |
| `CoachRegistrationService.java:112-121` | `verifyEmail`: `findByToken…orElseThrow` → `isUsed()` → expiry, all three `EmailTokenException` ✔ |
| `ApiAdvice.java:555-562` | `emailTokenExceptionHandler`, `@ResponseStatus(BAD_REQUEST)`, `canResend` passed through ✔ |
| `AuthService.java:157`, `:166` | both `refreshTokenRepository.markAllUsedByUserId(ownerId);` ✔ (ledger said `:153/:160` — story correctly re-anchored) |
| `AuthService.java:97` | `ensureAccountIsLive(user);` ✔ |
| `AuthService.java:87` | `userRepository.findOneByLogin(email.toLowerCase())` ✔ |
| `JWTAuthorizationFilter.java:342-357` | `isGenuineDenial` method, exactly 342→357 ✔ |
| `JWTAuthorizationFilter.java:354` | the `ACCOUNT_NOT_LOGIN_ABLE` disjunct ✔ |
| `JWTAuthorizationFilter.java:206-207` | `daoAuthProvider.authorize(authentication, httpEndpointGuard.requiredAuthorities(req))` ✔ |
| `JWTAuthorizationFilter.java:69-88` / `:69-91` | the fast-path / DB-reauth `<ul>` javadoc; it even states the gap AC3 exists to close ✔ |
| `TokenCreatorImpl.java:50` | `claims.put(Claims.ISSUED_AT, …Instant.now…)` ✔ |
| `JwtManagerImpl.java:166` | inside `extendTtlOfToken`: `tokenCreator.toClaims(principal, dbRefreshToken, true, null)` ✔ |
| `DaoAuthProvider.java:36` | `getPreAuthenticationChecks().check(user);` ✔ |
| `DaoAuthProvider.java:47-51` | `catch (AccountStatusException ase)` → `ACCOUNT_NOT_LOGIN_ABLE` ✔ |
| `Principal.java:140-160`, `:150` | `instanceFrom` 140→160; `.credentialsNonExpired(true)` at `:150` ✔ |
| `SecurityConstants.java:102,105,120` | `JWT_TTL` 15 min / `REFRESH_TOKEN_TTL` 7 days / `DB_REFRESH_TOKEN_INTERVAL` 5 min ✔ (all three exact) |
| `ConfigStartupAssertion.java:182` | `if (minSessionAgeDays >= updateCooldownDays) {` ✔ |
| `ConfigStartupAssertion.java:178-181` | `getBoundedInt(…, 7, 1, 365)` / `(…, 30, 1, 365)` ✔ |
| `ConfigService.java:321` | `if (minSessionAgeDays >= updateCooldownDays) {` in `rejectReviewEligibilityWindowOrdering` ✔ |
| `pr-build.yml:12`, `:118` | `build:` and `docker-image:` job ids, no `name:` override → check-run contexts are exactly those strings ✔ |
| `pr-build.yml:55-57` | the `# No -q:` comment ✔ (AC8 item 2) |
| `ci.yml:238-239` | `- name: Build and push Docker image` / `uses: ./.github/actions/docker-build` ✔ |
| `ci.yml:71-74` | mid-comment in the `frontend-quality` rationale, as the story says ✔ |
| `docs/deployment/baseline/pr.md:65` | `# ci.yml:71-74   "Build and push Docker image"` ✔ |
| `docs/testing/readme.md:152-156` | the `0.07 ms mean (84 ms total over 1199 invocations)` / run `37574001244` text ✔ — and the same paragraph contradicts itself at `:156-157` ("the quiesce counter reports microseconds") ✔ |
| `.dockerignore:43-70` | exactly `docs/` (43) → `.gitattributes` (70) ✔ |
| `pom.xml:733-748` | `git-commit-id-maven-plugin` `10.0.0`, `<phase>initialize</phase>` at `:743`, `generateGitPropertiesFilename = ${project.build.outputDirectory}/git.properties` at `:748` ✔ |
| deferred-146 story file `:112` | the "6 call sites across 5 concurrency IT classes" text ✔ (line correct, **section label wrong** — see F14) |
| deferred-146 story file `:228` | the Dev Notes runnable-command `pr-build.yml:49-51` citation ✔ |
| deferred-146 story file `~:82` | `**The 25ms delay was never load-bearing.**` is exactly `:82` ✔ |
| `DatabaseResetTestExecutionListener.java:266-277` | the "On the hot path they are not… never enters `workQueue`" javadoc ✔ (block runs ~268-278; immaterial) |
| AC8 item 1's 7 call sites | `SubscriptionServiceConcurrencyIT:172`, `CoachProfileServiceConcurrencyIT:219,335`, `AdminReviewQueueIT:635`, `ReviewFlagServiceConcurrencyIT:236,353`, `ReviewModerationServiceConcurrencyIT:170` — **exactly 7 sites / 6 classes**, and `AdminReviewQueueIT` is indeed not a concurrency IT ✔ |
| AC8 item 1's 4 extra sites | `PlayerRegistrationResourceIT:425-430` + `CoachRegistrationResourceIT:732-737` (both `pollInterval(100ms)`, no `pollDelay`), `RefundOutboxIT:126` + `SluSnapshotOutboxIT:222` (bare `.atMost(20s)`, default 100 ms) ✔ |
| AC8 item 8's comment | `:336-338` "That caps the worst case at one pass -- six pools x 30s…" + `if (!waited \|\| timedOut) { return; }` at `:339`; `MAX_QUIESCE_PASSES = 3` at `:351`; `atMost(30s)` at `:314`. The story's 3 × 6 × ~30 s ≈ 540 s reasoning is correct ✔ |
| `V158` is the next free version | highest existing is `V157__coach_reviews_author_last_edited_at.sql` ✔ |
| `routerGuardSpec.js` still exists | `src/frontend/src/router/__tests__/routerGuardSpec.js`, already structured around role gates ✔ |
| `transactionTemplate` already autowired in both IT files | `CoachRegistrationResourceIT:60`, `ParentRegistrationResourceIT:53`; neither has a `commitWrite` helper ✔ |
| `existsByIdAndUserId` exists | `PlayerProfileRepository:46`, added by deferred-147 for exactly this purpose ✔ |
| `PlayerOwnershipGuard.check` dual disjunct | `:33-34` `existsByIdAndParentId(...) \|\| existsByIdAndUserId(...)` ✔ |

### 1.2 Drifted

| Citation | Verdict | Corrected location |
|---|---|---|
| `UserRepository.java:71-74` (`changeAccountLockStatus`) | **DRIFTED** | `:73-75` (`:71-72` are blank). The method also has **zero callers** in `src/main` or `src/test`. |
| `UserRepository.java:104-106` (`markCleanupFailed`) | **DRIFTED (minor)** | `:103-106` — `:103` is the `@Query` the story's "`@Modifying @Query` precedent" framing depends on. |
| `DaoAuthProvider.java:42-56` ("`authorize()`") | **DRIFTED** | `authorize()` is `:28-59`. `:42-56` covers only three of its four catch blocks and omits the `InternalAuthenticationServiceException → UNKNOWN` wrap at `:37-41` — which matters for F4. |
| `DatabaseResetTestExecutionListener.java:118-121` (AC8 item 7) | **DRIFTED** | The "10s bound" text is at `:121-124`. `:118-119` is `recordQuiesceCost(...)` and its closing brace. This range is reproduced verbatim from `deferred-work.md:3920`. |
| References: `platform/security/repo/PlayerOwnershipGuard.java` | **DRIFTED (path)** | Actual: `src/main/java/com/softropic/skillars/platform/security/service/PlayerOwnershipGuard.java`. |

### 1.3 Cannot verify from this repository

- **AC8 items 4, 5, 9's CI figures** (`44.9 µs mean`, `53776 µs`, run `37584051185`, `132.2s`, `12m06s`, `3m21s`). These come from GitHub Actions logs, not the tree. I confirmed only that the *stale* side is present as cited, and that the arithmetic the story states internally is self-consistent (150 − 132.2 = 17.8 ✔). The new numbers are reproduced faithfully from `deferred-work.md:3893-3895`, where they are recorded as log-verified.
- **AC6's live GitHub state** (404 branch protection, ruleset `20583638` carrying only `deletion`/`non_fast_forward`/`pull_request`, PR #255 head `0592d33b` check-run names). Not checkable offline. The repo-side half *is* confirmed: `build` and `docker-image` are the literal job ids with no `name:` override, so the check-run contexts will be exactly those strings.

---

## 2. Ledger & precedent attribution (Layer 2)

All eight cited `deferred-work.md` section headers exist with the exact titles and dates the References
section gives:

| Cited section | Line | Verdict |
|---|---|---|
| code review of skillars-deferred-142 (2026-10-05) | 3471 | ✔ |
| code review of skillars-deferred-143 (2026-10-05) | 3579 | ✔ |
| manual review during skillars-deferred-143 (2026-10-05) | 3674 | ✔ — the "wider grep" companion ask is real and explicitly still open (`:3791-3796`) |
| code review of skillars-deferred-144 (2026-10-06) | 3800 | ✔ — and its "When picked up" literally proposes *"add a `sessions_invalid_after` column the pre-authentication checks consult"*, which is Design C |
| code review of skillars-deferred-145, round 2 (2026-10-06) | 3856 | ✔ — the 29/30 example, `:182`, `:321` all match |
| code review of skillars-deferred-146 (2026-10-07) | 3884 | ✔ — the `docker-image`/ruleset item |
| post-implementation story audit of skillars-deferred-146 (2026-10-07) | 3891 | ✔ — **9 bullets**, see F5 |
| code review of skillars-deferred-148 (2026-10-07) | 3922 | ✔ — Finding 1 reproduces it accurately and refines it correctly |

Sources checked for each precedent claim: the ledger file, the source-code comments/javadoc beside the
method under discussion, and `grep` across `src/main` + `src/test`.

| Precedent claim | Sources checked | Verdict |
|---|---|---|
| "the same dual-check shape `skillars-deferred-147` already added to `PlayerOwnershipGuard`" | `PlayerOwnershipGuard.java:26-34`, `PlayerProfileRepository.java:39-46` | **TRUE** — the javadoc names deferred-147 and `PlayerOwnershipGuard` by hand |
| "the bug class `skillars-deferred-144` already fixed once in `AuthResourceIT`" | `AuthResourceIT:475-482`, `:813-824` | **TRUE** — the comment states the auto-commit mechanism verbatim |
| "mirroring the existing `changeAccountLockStatus` / `markCleanupFailed` `@Modifying @Query` precedent" | `UserRepository.java:73-75`, `:103-106` | **TRUE as to shape**, but see **F1** — `changeAccountLockStatus` is dead code (0 callers) and is the *wrong* precedent for a revoke-then-throw site |
| "the same shape `skillars-deferred-143` already established for account locking" | `AuthService.ensureAccountIsLive:288-295`, `Principal.instanceFrom:151`, `JWTAuthorizationFilter:342-357`, `deferred-work.md:3579-3600` | **TRUE in spirit** (enforcement via a `User` boolean read on the pre-auth check), but deferred-143 did not use `changeAccountLockStatus`; its mechanism is `accountNonLocked` + the filter's teardown |
| "follow `skillars-deferred-122`'s `cleanupFailedAt`/`cleanupLastAttemptedAt` precedent" | `V143__user_cleanup_failed_at.sql`, `User.java:100-133` | **TRUE and load-bearing — and AC3 contradicts it on three counts.** See **F2** and **F3** |
| "`skillars-deferred-148`'s own stated rationale for the identical `DisputeService` change" (field appended last) | `DisputeServiceTest.java` exists; `grep "new SubscriptionService("` → 0 hits | **FALSE as applied** — see **F6** |
| "the `.dockerignore` `DO NOT add .git here` comment block" | `.dockerignore:15-18` | **TRUE** — it exists and says exactly that |
| "`skillars-deferred-136` AC4 raised the quiesce bound 10s → 30s" | `DatabaseResetTestExecutionListener:244` (`<h2>… atMost raised 10s -> 30s`), `:314` (live `30`) | **TRUE** |
| "`POST /api/auth/refresh` has no caller yet" | `grep skillarsRefresh` → defined at `auth.api.js:45`, **zero** call sites; live keep-alive is `sessionApi.refresh()` → `GET /refresh` (`session.api.js:5`, `sessionManager.js:265`) | **TRUE** (see §5 K1 — I nearly flagged this and it does not hold up as a finding) |
| "`SecurityError.ACCOUNT_NOT_LOGIN_ABLE`'s enum comment already reads 'Maybe credentials/account expired, account locked or so'" | `DaoAuthProvider.java:48` message + the `isGenuineDenial` javadoc at `:309-340` | **TRUE in substance** — the generic framing is real and documented |

---

## 3. Mechanistic claims (Layer 3)

Claims confirmed by reading the full named method and its enclosing transaction boundary:

1. **"`currentParentId()` is actually `securityUtil.requireCurrentUserId()`, i.e. 'the caller's own id'"** — `SubscriptionResource.java:134-136`. **CONFIRMED.**
2. **"only `getPlayerSubscription`'s controller method uses the dual-check `@PreAuthorize`; the other three are `HAS_PARENT_ROLE`"** — `SubscriptionResource.java:101,111,118`. **CONFIRMED.**
3. **"every `verifyEmail` branch throws `EmailTokenException`, and `ApiAdvice` maps every one to 400 uniformly"** — `CoachRegistrationService:112-121`, `ApiAdvice:555-562`. **CONFIRMED**, and `ParentRegistrationService:116-128` is byte-identical.
4. **"`extendTtlOfToken` resets `iat` on every fast-path request, so `iat` cannot carry an original-issuance signal"** — `JwtManagerImpl:161-174` → `TokenCreatorImpl:50` sets `Claims.ISSUED_AT = now()` unconditionally; `extendTtlOfToken` re-passes the *incoming* `dbRefreshToken` (`:164,:166`) rather than regenerating it. **CONFIRMED on both halves**, including the subtle "`dbRefreshToken` is the one claim that survives" point. Checked that no sibling method in `JwtManagerImpl` was confused for `extendTtlOfToken`.
5. **"the DB re-auth path never reads `refresh_tokens`"** — `JWTAuthorizationFilter:205-212` plus the filter's own javadoc at `:78-88` and its inline comment at `:212-227`, both of which state the theft-revocation gap explicitly. **CONFIRMED** — Finding 3's diagnosis is correct and corroborated by two existing comments.
6. **"`Principal extends` Spring's `UserDetails` base, so `credentialsNonExpired=false` makes `AccountStatusUserDetailsChecker` throw `CredentialsExpiredException`, which `DaoAuthProvider:47-51` rewraps as `ACCOUNT_NOT_LOGIN_ABLE`, already an `isGenuineDenial` trigger"** — `DaoAuthProvider:36,47-51`, `JWTAuthorizationFilter:354`, `:158-160` (`terminateSession`). **CONFIRMED.** The enforcement chain really does already exist end-to-end; Design C's "zero changes needed to `DaoAuthProvider.java` or `JWTAuthorizationFilter.java`" is right.
7. **"`login()`'s `user` is a managed entity, so a plain setter flushes on commit"** — `AuthService` is `@Transactional` (`:48`), `findOneByLogin` at `:87`, no throw on the success path. **CONFIRMED.** Also checked the inverse risk: `login()` does **not** run Spring's pre-authentication checks (`ensureAccountIsLive:288-295` only tests `activated`/`locked`), so a flagged user can still log in and clear the flag. Design C's clear-on-login is reachable.
8. **"the new disjunct subsumes the old strict-ordering check"** — mathematically, `{gap ≤ 0} ⊂ {gap < 7}`. **CONFIRMED** (the parenthetical justifying it is garbled — F18).
9. **"`git-commit-id-maven-plugin` writes `target/classes/git.properties`"** — `pom.xml:748`. **CONFIRMED**, so Design G's write target is right.
10. **"`management.info.git.mode: full` is not configured anywhere, so the dirty flag is not surfaced today"** — no `info.git` key in any `application*.yaml`; `management.endpoints.web.exposure.include: health,info,env,metrics,prometheus` (`:477`). **CONFIRMED** (Boot's default `simple` mode exposes branch/commit.id/commit.time only).

Claims that did **not** survive:

11. **"both call sites already run inside `AuthService`'s class-level `@Transactional`, so the `@Modifying` query joins the ambient transaction"** → **F1, backwards.**
12. **"the other two wrap codes `DaoAuthProvider.authorize()` can produce"** → **F4, there are four.**
13. **"appended last … before the separate `@Autowired @Lazy stripeWebhookService` field"** → **F6, misreads the field block.**
14. **"`.dockerignore`'s exclusions make `git.dirty=true` permanent"** → **F10, wrong cause.**
15. **"`.github/actions/docker-build/action.yml`'s existing `build-args` input"** → **F19, not an input.**

---

## 4. Findings that survived adversarial re-verification

### F1 — BLOCKING. AC3's revocation write would be silently rolled back; Design C's transaction claim is backwards

**Design C, line 162:** *"No explicit `@Transactional` needed (unlike `markCleanupFailed`) — both call sites (`AuthService.refresh()`'s two theft branches) already run inside `AuthService`'s class-level `@Transactional`, so the `@Modifying` query joins the ambient transaction, exactly like `changeAccountLockStatus` does."*

Both theft branches **throw immediately after the revocation**:

- `AuthService.java:157-158` → `markAllUsedByUserId(ownerId); securityUtil.clearAuthCookies(res); throw new BadCredentialsException(...)`
- `AuthService.java:166-168` → identical shape

`BadCredentialsException` is a `RuntimeException`, `AuthService` carries a bare class-level
`@Transactional` (`:48`) with default rollback rules, and there is **no `noRollbackFor` anywhere in
`src/main`**. So the ambient transaction rolls back — which is precisely why the sibling write is
*not* ambient:

> `RefreshTokenRepository.java:79-82` — `@Modifying` **`@Transactional(propagation = Propagation.REQUIRES_NEW)`** on `markAllUsedByUserId`
> `RefreshTokenRepository.java:86-96` — *"a caller that revokes-then-throws must not have the revocation undone by its own rollback. The concrete case: `AuthService.refresh()` is its own outermost transaction boundary (`AuthService` is `@Transactional`, `AuthResource` is not, and `spring.jpa.open-in-view` is `false`) … A plain managed `save()` … would therefore be silently discarded."*
> `AuthService.java:216-218` — *"Rolls back this method's transaction, which is exactly why the revocation inside `terminateSession` (and the explicit one here) is `REQUIRES_NEW`."*

Independently confirmed: `AuthResource.java:62-65` has no `@Transactional`; `application.yaml:158` has
`open-in-view: false`. Every premise the existing javadoc rests on still holds at this HEAD.

**Consequence if built as written:** `invalidateSessionsForUser` executes, the transaction rolls back,
`security_session_invalidated_at` stays `NULL`, and **AC3 ships as a complete no-op** — the one
behaviour the story calls "the largest, highest-risk item" would never fire. Worse, the AC3 unit test
as specified ("a theft-detection branch firing sets the flag") would pass, because a mocked
`UserRepository` cannot observe a rollback.

**Fix:** give `invalidateSessionsForUser` `@Modifying @Transactional(propagation = REQUIRES_NEW)`,
following `markAllUsedByUserId`/`markUsedByTokenHash` — not `changeAccountLockStatus` (which, checked
separately, has **zero callers** in `src/main` or `src/test`, so it is not a live precedent for
anything). The self-deadlock hazard that normally accompanies `REQUIRES_NEW` here does **not** apply:
neither theft branch writes the `user` row before this point, so the inner transaction takes an
uncontended lock — the same argument `AuthService.java:205-208` already makes for `refresh_tokens`.
Say so in the code comment, because it is the non-obvious half.

Also correct AC3's integration bullet to assert durability (flag still set **after** the 401 returns),
since that is the assertion that distinguishes a working fix from this bug.

---

### F2 — BLOCKING. AC3 adds a column to an Envers-audited entity with no `user_aud` column and no `@NotAudited`

`User` is `@Audited` (`User.java:45`), `hibernate-envers` is a real dependency (`pom.xml:410`), and
`main.user_aud` is a real table (`V138__baseline_schema.sql:…`) that carries **no** `cleanup_*` columns
and gained `skillars_role`/`verification_status` only retroactively.

This repo has exactly two sanctioned ways to add a `User` column, and has already been burned once:

- **V143** (the precedent AC3's Dev Notes tells the dev to follow) marks the new fields `@NotAudited`
  and documents it: *"User is Envers-@Audited with a real main.user_aud table, but all four new/existing columns here are annotated `@NotAudited` on the entity … so no matching user_aud column is needed."* (`User.java:103,118,123,132`)
- **V146:25** takes the other path: *"main.user_aud gets the column too: User is @Audited and the embedded field carries no @NotAudited"* → `ALTER TABLE main.user_aud ADD COLUMN …` (`:37`)
- **V145** exists *only* because `skillars_role`/`verification_status` were added to the audited entity without a matching `user_aud` column — i.e. this exact mistake, already paid for once.

AC3 does **neither**. Bullet 1 adds the column to `main."user"` only; bullet 2 adds the entity field
with "getter/setter, per Design C"; Design C says place it *"near the existing `locked` field"* — which
is `User.java:71-72`, squarely inside the audited region, nowhere near the `@NotAudited` cluster at
`:100-133`.

**Consequence:** with Envers active and no `user_aud.security_session_invalidated_at`, the first
audited write to a `user` row fails on the revision insert. `AuthService.login()`'s own
`setSecuritySessionInvalidatedAt(null)` is such a write — so **AC3 would break login**, not just the
theft path. And `hibernate.ddl-auto: none` (`application.yaml:157`) means nothing catches it at boot;
it surfaces as a runtime failure.

**Fix:** decide and state which path AC3 takes — `@NotAudited` (this is operational session state, so
V143's reasoning applies cleanly and is the cheaper option) or a matching `user_aud` ADD COLUMN in
V158. Add it as an explicit AC3 bullet; do not leave it to be discovered.

---

### F3 — HIGH. V158 as specified fails `MigrationConventionLintTest` in CI

AC3 bullet 1 specifies, verbatim:

```sql
ALTER TABLE main."user" ADD COLUMN security_session_invalidated_at TIMESTAMPTZ;
```

`MigrationConventionLintTest.realMigrations_aboveBaseline_areClean` (`:86-97`) asserts **zero**
violations for every migration above `GRANDFATHER_BASELINE = 139` (`MigrationLint.java:112`). V158
is above both relevant baselines, so two rules bite:

- **`MISSING_LOCK_TIMEOUT`** (`MigrationLint.java:203`, `:1107`) — lock-taking DDL with no
  `SET lock_timeout` in effect. The statement above has none.
- **`SESSION_SCOPED_LOCK_TIMEOUT`** (`:225`, baseline `150` at `:149`) — above V150 a *plain*
  `SET lock_timeout` is itself a violation; it must be `SET LOCAL`.

`docs/deployment/migration-conventions.md:45` states the rule directly: *"Every lock-taking DDL has
`SET LOCAL lock_timeout` in effect at that point in the file."* The story cites that very document to
call the migration "trivially safe" — the *classification* is right (additive nullable, expand/contract
rule 1), but the specified SQL omits a mandatory element of the same document.

Two further deviations from house shape, not lint-enforced but uniform across every recent migration
(`V157`, `V151`, `V143`, `V146`, `V140`):

- **`TIMESTAMPTZ` is the wrong type for this table.** `V143`'s header says it in as many words:
  *"timestamp without time zone, not timestamptz: matches every other nullable timestamp column already on this table (activation_date, reset_expiration, account_expiration, created_date, last_modified_date), consistent with hibernate.jdbc.time_zone: UTC and this codebase's Instant-typed fields throughout."* Every existing `main."user"` timestamp is `timestamp without time zone`.
- **No `ADD COLUMN IF NOT EXISTS`** and no rationale header.

Note the trap: following `V143` *literally* also fails, because V143 is below baseline 150 and uses a
plain `SET lock_timeout`. The correct model is **V157** (`SET LOCAL lock_timeout = '5s';` + rationale
header + `ADD COLUMN IF NOT EXISTS`).

---

### F4 — HIGH. AC4 is not "zero-risk": widening to `UNKNOWN` makes a transient DB fault revoke refresh tokens

Finding 4 calls this *"a one-line, zero-risk completeness fix"* and says `USER_NOT_FOUND`/`UNKNOWN` are
*"the other two wrap codes `DaoAuthProvider.authorize()` can produce."*

Reading the full method (`DaoAuthProvider.java:28-59`), `authorize()` can produce **four** other codes:

| Code | Site |
|---|---|
| `UNKNOWN` | `:37-41`, `catch (InternalAuthenticationServiceException)` |
| `USER_NOT_FOUND` | `:42-46` |
| `UNKNOWN` | `:52-56`, `catch (Exception exception)` — the catch-all |
| `MISSING_RIGHTS` | `:72-74`, via `checkAuthorities(...)`, called from `authorize()` at `:57` |
| `MISSING_USERNAME` | `:79-81`, via `determineUsername(...)`, called at `:30` |

`UNKNOWN` is the **generic infrastructure-failure code**: `catch (Exception)` at `:52-56` absorbs
anything thrown while loading the account, and `InternalAuthenticationServiceException` at `:37` is
exactly how Spring's `DaoAuthenticationProvider` wraps a repository/DataSource failure. Making it a
genuine denial routes it to the full teardown — `securityUtil.terminateSession(req, res)`
(`JWTAuthorizationFilter:158-160`), i.e. **refresh-token revocation plus `rtkn`/`skp` clearing**.

So a connection-pool exhaustion, lock timeout, or query timeout at the 5-minute DB-reauth boundary
would no longer produce a recoverable 401 — it would revoke the user's 7-day refresh token and force a
full re-login, for every user who happens to cross the boundary during the blip. That is a materially
different risk profile from the story's "zero-risk" framing, and it cuts against the design intent the
`isGenuineDenial` javadoc states at `:309-340` (routine causes must not reach the DB-write path).

The `MISSING_RIGHTS` omission is also already known inside this project: `sprint-status.yaml`'s
deferred-144 note records *"the review also omitted a real member of the same list, MISSING_USERNAME
(DaoAuthProvider.determineUsername)"*. The story reproduced the ledger's "other two" phrasing
(`deferred-work.md:3663-3664`) rather than re-deriving it.

**Recommendation:** split the two codes. `USER_NOT_FOUND` is a genuine denial — take it. For `UNKNOWN`,
either leave it routine, or (better) narrow `DaoAuthProvider`'s catch-all so infrastructure failures get
their own code and only the genuinely-unauthenticatable cases map to `UNKNOWN`. Either way AC4 needs a
sentence acknowledging the blast radius, and the AC should pin the decision rather than presenting the
widening as cost-free.

---

### F5 — HIGH. AC8 claims to close "all ~9 items" but drops one

The `## Deferred from: post-implementation story audit of skillars-deferred-146 (2026-10-07)` section
(`deferred-work.md:3891-3920`) contains **exactly 9 bullets** (counted: 9 lines matching `^- \*\*`):

| # | Ledger bullet | AC8 item |
|---|---|---|
| 1 | AC3 satisfied against an unmerged commit | item 9 |
| 2 | `docs/testing/readme.md:152-156` stale units/run | items 4 **and** 5 |
| 3 | Finding 1 narrative still asserts the refuted reasoning | item 6 |
| 4 | AC1d undercounts: 7 sites not 6, 1 of 5 Awaitility sites | item 1 |
| 5 | idle re-sweep tripled the worst case | item 8 |
| 6 | `docs/deployment/baseline/pr.md:65` → `ci.yml:238-239` | item 3 |
| 7 | `pr-build.yml:49-51` → `:55-57` | item 2 |
| 8 | **"Deferred From This Investigation" item 1 (`spring.test.context.cache.maxSize`) arithmetic does not close against its own measurement; may be ~2× oversized** | **— none —** |
| 9 | `DatabaseResetTestExecutionListener:118-121` 10s → 30s | item 7 |

Bullet 8 is **not** in AC8, and it is **not** in Dev Notes' "Do not pick up" list either — so it is
neither closed nor deliberately deferred, while AC8's own framing ("nine independent edits", Source
line: "AC8, all ~9 items") asserts completeness. The substance of bullet 8: *"40 signatures with 8
rebuilds implies 48 loads, not 44; the measured 44 implies ~4 rebuilds, i.e. ~4 × 4.6 s ≈ 18 s, roughly
half the headline saving… the static count was not re-derived by this audit, so which one is open."*

The proximate cause is visible in **F14**: AC8 item 1 attaches bullet 8's *section label*
("`## Deferred From This Investigation` item 1") to bullet 4's *content* (the 7-call-site inventory).
The two bullets were conflated and one was lost.

**Fix:** add a tenth AC8 item for the `maxSize` arithmetic (text-only: correct the eviction inference
and the ~35 s sizing, or mark the signature count as un-re-derived), or move it explicitly to "Do not
pick up" with a reason. Also drop the "all ~9 items" / "nine independent edits" phrasing in favour of an
exact count after the fix.

---

### F6 — MEDIUM. `SubscriptionServiceTest` does not exist, and Design A's field-ordering rationale rests on it

The story references it four times as an existing artefact:

- AC1: *"`SubscriptionServiceTest.setUp()`'s constructor call and mock field list updated for the new constructor parameter."*
- Design A: *"appending last minimizes `SubscriptionServiceTest`'s constructor-call diff"*
- Project Structure Notes, "Changed test files": `src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionServiceTest.java`
- AC9: re-run `SubscriptionServiceTest`

At `df07a842` there is **no such file**. `find src/test -iname "*Subscription*"` returns
`PlayerSubscriptionQueryAdapterTest`, `PlayerSubscriptionOwnershipIT`, `SubscriptionResourceIT`,
`SubscriptionLifecycleIT`, `SubscriptionSchedulerIsolationTest`, `SubscriptionSchedulerLockTest`,
`SubscriptionServiceConcurrencyIT`, `SubscriptionServiceStripeReconciliationIT` — and no
`SubscriptionServiceTest`. `grep -rn "new SubscriptionService(" src/` returns **zero hits** repo-wide.

Two consequences:

1. **Design A's entire field-placement rationale is vacuous.** Nothing constructs `SubscriptionService`
   manually, so constructor-parameter position has no diff to minimise. (Contrast `DisputeServiceTest`,
   which *does* exist — the deferred-148 precedent is real, it just does not transfer here.)
2. **The placement instruction is wrong on its own terms.** Design A says to append *"after `lockRetryer`,
   before the separate `@Autowired @Lazy stripeWebhookService` field."* But `stripeWebhookService`
   (`SubscriptionService.java:68`) is a plain `private final` field — the **last** `@RequiredArgsConstructor`
   parameter. The `@Autowired @Lazy` member is `private SubscriptionService self` (`:71-72`), non-final
   and field-injected, not a constructor parameter at all. Following the instruction literally inserts the
   new parameter *second-to-last*, contradicting "appended last".

**Fix:** declare the new field after `stripeWebhookService` (`:68`) if "last" is wanted; drop the
diff-minimisation rationale; and change AC1's `setUp()` bullet plus the File List from "update" to
"create (does not exist today)" — or retarget the coverage per **F9**.

---

### F7 — MEDIUM. Widening the route to `PLAYER` exposes three parent-only actions with no UI gate

AC1 widens `parent/player/:playerId/subscription` to `roles: ['PARENT', 'PLAYER']` while Design A
explicitly says: *"**Do not** touch `subscribePlayer`/`changePlayerTier`/`cancelPlayerSubscription`'s
controller-level `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)`"* — and calls the result *"the
complete, correctly-scoped fix."*

`PlayerSubscriptionPage.vue` is not read-only. It exposes all three mutations:

- `:107` cancel button (`t('subscription.cancel')`) → `:280` `paymentStore.cancelPlayerSubscription(playerId)`
- `:124` subscribe dialog → `:259` `paymentStore.subscribePlayer({...})`
- `:257` `paymentStore.changePlayerTier({ playerId, newTier })`

and `grep isParent src/frontend/src/pages/parent/PlayerSubscriptionPage.vue` returns **nothing**. So a
self-registered PLAYER lands on a page whose primary buttons 403 at the backend and surface as the
generic `subscription.player.subscribeError` / `cancelError` notifications.

The codebase's own dual-role precedent is the other half of the pattern the story cites. `routes.js:129-131`
says so: *"reused by a self-registered PLAYER's own bookings list … — see ParentBookingsPage.vue's
`authStore.isParent` button guards"*, and `ParentBookingsPage.vue:101,120,140` gate on
`(authStore.isParent || authStore.isPlayer)`. AC1 ports the route half of the precedent and omits the
button half.

**Fix:** add an AC1 bullet gating the three mutating controls on `authStore.isParent` (with a read-only
message or hidden state for PLAYER), or state explicitly in AC1 that the PLAYER view is read-only by
design and that the 403s are accepted.

---

### F8 — MEDIUM. AC3's integration-test instruction cites a precedent that does not exist

AC3: *"New `AuthResourceIT` (or `JWTAuthorizationFilterIT`, whichever this codebase's existing precedent
uses for filter-level integration coverage — confirm at implementation time) case … **force that boundary
in the test the same way other tests in this file already force it (an already-elapsed `dbRefreshToken`
claim)**, rather than waiting 5 real minutes."*

- `JWTAuthorizationFilterIT` does not exist. Only `JWTAuthorizationFilterTest` (a Mockito unit test).
- `AuthResourceIT` contains **zero** occurrences of `dbRefreshToken` or `dbRToken`. The only test file
  in the repo that touches that claim is `JwtManagerImplTest`.

The class name is hedged; the *mechanism* is asserted as established fact and is false. There is no
existing pattern to copy for forcing the DB-reauth boundary through a real HTTP round trip — the work is
larger than the AC implies. (At unit level it is trivial: `JWTAuthorizationFilterTest` mocks
`loginTokenManager.hasDbRefreshTokenExpired(request)` at `:171,:314,:338,:364,:421`, and
`testWrappedAccountNotLoginAble_terminatesSession` at `:410-435` is a ready-made template.)

**Fix:** drop the false "already force it" clause; either specify the integration mechanism concretely
(mint a `potc` whose `dbRToken` is already past, via `JwtManagerImplTest`'s approach) or scope AC3's
end-to-end proof to `JWTAuthorizationFilterTest` + a durability assertion per F1.

---

### F9 — MEDIUM. AC1 has no test that can fail if the bug is left unfixed — and the story never mentions the file that should carry it

`PlayerSubscriptionOwnershipIT` already contains
`getPlayerSubscription_selfRegisteredPlayer_returns200` (`:108-132`), added by deferred-147, which
**passes today with the bug present** — because the class is `@WebMvcTest(SubscriptionResource.class)`
with `@MockitoBean SubscriptionService` (`:71`), so `assertPlayerOwnership` never executes.
`SubscriptionResourceIT` has the same shape (`:37`, `:49`), and its own header comment at `:34` points
at `PlayerSubscriptionOwnershipIT` for `/player/me` coverage. No IT in the repo exercises
`getPlayerSubscription` against a real `SubscriptionService`.

The story never names `PlayerSubscriptionOwnershipIT` at all, and the coverage it does specify is two
mock-based `SubscriptionServiceTest` cases in a file that does not exist (F6). A Mockito test that stubs
`playerProfileRepository.existsByIdAndUserId(...) → true` proves only that the disjunct was written, not
that the route works end-to-end — which is exactly the gap that let deferred-148 ship the route-level
assumption that deferred-149 is now fixing.

**Fix:** keep the unit cases, and add one of: (a) a `SubscriptionService`-level test with a real/stubbed
repository pair covering `(parentLink=false, selfOwned=true) → pass` and
`(false, false) → payment.subscription.playerOwnership`; or (b) extend `PlayerSubscriptionOwnershipIT`
with a non-mocked service slice. Mention `PlayerSubscriptionOwnershipIT` in Project Structure Notes
either way, since a reviewer will otherwise assume its green self-registered test already proves AC1.

---

### F10 — MEDIUM. Finding 7 misattributes the cause of `git.dirty=true`; removing the `.dockerignore` exclusions would change nothing

Finding 7's heading and body claim *"`.dockerignore`'s exclusions make `git.dirty=true` permanent"* —
that `.dockerignore` *"excludes several git-tracked paths from that same copied `.git/`"*, so JGit reports
them as deletions.

`.dockerignore`'s own header (`:3-4`) contradicts this: *"the Dockerfile only consumes three paths:
`pom.xml`, `src/`, and `.git/`."* And the Dockerfile confirms it — `COPY pom.xml .` (`:6`),
`COPY src/ src/` (`:10`), `COPY .git/ .git/` (`:11`), and nothing else. `docs/`, `requirements/`,
`deploy/`, `.github/`, `mvnw`, `.gitignore`, `.gitattributes` are absent from the in-container working
tree **because they are never `COPY`ed**, not because `.dockerignore` filters them out of the context.
Deleting every exclusion at `.dockerignore:43-70` would leave `git.dirty=true` exactly as it is.

The symptom is real and the chosen fix (feed the SHA in as a build arg) is right. But a dev who reads
Finding 7 as written may try to narrow the exclusions instead, and will get nowhere.

**Fix:** restate Finding 7's cause as "the Dockerfile copies only `pom.xml`/`src/`/`.git/`, so every
other tracked path reads as deleted against the copied index" and keep `.dockerignore` out of the causal
chain (it remains in scope only for AC7's last bullet, removing the now-obsolete `DO NOT add .git here`
block at `:15-18`).

---

### F11 — MEDIUM. AC5 changes the rule but leaves four other statements of the old rule standing

AC5 names only the two `if` conditions plus "log message"/"exception message". The old rule is written
out in four more places, all of which become false:

| Location | Text |
|---|---|
| `docs/deployment/monitoring.md:145` | *"also cross-checked against `reviews.updateCooldownDays` (**must stay strictly less**, at both boot and `PUT /api/config` …)"* |
| `ConfigService.java:302` | javadoc: *"**strictly less than** reviews.updateCooldownDays, or the maturity floor silently supersedes the…"* |
| `ConfigStartupAssertion.java:170-177` | the comment block above the guard, framed entirely as `minSessionAgeDays >= updateCooldownDays` |
| `ConfigStartupAssertionTest:324-325` | *"Equal is still a violation here … **must be STRICTLY less than** updateCooldownDays"*, plus the now-misleading method names `reviewWindowOrderingEqualValues_flagsAsSuperseded` and `reviewWindowOrderingMinAgeBelowCooldown_doesNotFlag`, and `ConfigServiceTest`'s `…NotLessThanCooldownDefault_…` / `…NotGreaterThanMinAgeDefault_…` |

This matters more than usual in this story, whose AC8 exists for precisely this class of drift — AC5
would create nine-ish new instances of it in the same commit that fixes nine old ones.

**Fix:** add these four sites to AC5's bullet list. `monitoring.md:145` is the one with real operator
consequences.

---

### F12 — MEDIUM. AC5's "cannot break the current running configuration" argues from coded defaults, but both guards read stored config

Finding 5: *"Current coded defaults (`getBoundedInt(key, 7, 1, 365)` / `getBoundedInt(key, 30, 1, 365)` — `ConfigStartupAssertion.java:178-181`) have a 23-day gap, comfortably clearing a 7-day minimum; the fix cannot break the current running configuration."*

The coded values are the **fallback**. `ConfigStartupAssertion:178-181` calls
`configService.getBoundedInt(key, default, 1, 365)`, which reads the stored `platform_config` row;
`ConfigService.rejectReviewEligibilityWindowOrdering` reads the partner key's row directly
(`readStoredBoundedInt`, per its own R2 javadoc at `:329-340`). `V156:7-8` seeds `7` / `30` with
`ON CONFLICT (key) DO NOTHING`, so the seed only applies to a fresh database.

The *old* guard admitted any gap ≥ 1. So a pair an admin set through `PUT /api/config` — `7`/`10`,
`20`/`25`, anything with a gap of 1–6 — is legal today and becomes a **fail-fast boot violation outside
the `dev` profile** the moment this change deploys. The story's safety argument never looks at stored
state, and no AC checks it.

**Fix:** add an AC5 bullet (or an AC9 deployment-precondition bullet): before/with the change, read the
two stored rows in every deployed environment and normalise any pair with a gap < 7 — or ship a V-numbered
data migration that clamps `updateCooldownDays` to `minSessionAgeDays + 7`. The code change itself is
sound; the rollout is the unguarded part.

---

### F13 — LOW. AC8 item 5's figure is not what the target file says

AC8 item 5 / Finding 8 item 5: *"`skillars-deferred-146`'s own story file Completion Notes table carries the **identical stale `0.07 ms` / `37574001244` pair** for the `[deferred-146] async quiesce:` row — same fix, same source numbers."*

The table row (`…parallel-docker-image-job.md:315`) actually reads:

> `| [deferred-146] async quiesce: (final, new) | — (not instrumented before this story) | **1199 invocations, 84 ms total, 0.1 ms mean** |`

**`0.1 ms`, not `0.07 ms`.** The `0.07 ms` rendering exists only in `docs/testing/readme.md:152` (AC8
item 4's target) and in that story's own Review Findings bullet at `:199`. The ledger's wording
("carries the identical stale pair") is about the stale *run id and ms-era units*, which is true; the
story turned that into a specific numeral that is not present.

**Fix:** reword item 5 to "the stale `84 ms` / `0.1 ms` / run `37574001244` triple", so a dev applying
the edit searches for text that exists.

---

### F14 — LOW. AC8 item 1's section attribution is wrong (and is how F5's item got lost)

Finding 8 item 1 / AC8 item 1 attribute the inventory error to *"`skillars-deferred-146`'s own story
file, `## Deferred From This Investigation` item 1 (`…:112`)"*.

Line `112` is correct, but it sits inside **AC1d of the Acceptance Criteria section**
(`## Acceptance Criteria` begins at `:92`): *"1d. **Given** `ConcurrencyLockWaitSupport.java:95` has the
same `Awaitility.await().pollInterval(25ms)` shape … **Then** it is left alone, deliberately, and on
volume alone: `assertGenuineLockRetryOccurred` has 6 call sites across 5 concurrency IT classes…"*

`## Deferred From This Investigation` is at `:254-266` and its five items are about
`spring.test.context.cache.maxSize`, prolonged-contention sleeps, context forks, Postgres tuning, and
suite sharding — none mentions `assertGenuineLockRetryOccurred`. The ledger itself labels this bullet
correctly ("skillars-deferred-146's **AC1d** undercounts its own evidence", `deferred-work.md:3900`);
the "Deferred From This Investigation item 1" label belongs to the *different* ledger bullet that F5
shows was dropped.

**Fix:** re-label AC8 item 1 as "AC1d, line 112". That also makes the F5 gap visible.

---

### F15 — LOW. AC8 item 7's own citation is stale, contradicting the story's independent-re-verification claim

Finding 8 opens: *"All verified still present at the cited locations on HEAD `df07a842`."* For item 7
that is not so: the "`Quiescing can legitimately wait up to its own 10s bound`" comment is at
`DatabaseResetTestExecutionListener.java:121-124`. `:118` is `recordQuiesceCost(System.nanoTime() - quiesceStartNanos);`
and `:119` its closing brace.

`:118-121` is the range printed in `deferred-work.md:3920` — i.e. copied from the ledger rather than
re-anchored, which is the one thing the story's header promises did not happen. Note the story *did*
avoid this on the adjacent item 8, where it described the comment's position in prose instead of reusing
the ledger's `:331-335` (actual: `:336-338`).

**Fix:** `:121-124`. Worth fixing precisely because AC8 is a citation-hygiene AC.

---

### F16 — LOW. Four further citation drifts

- `UserRepository.java:71-74` → `changeAccountLockStatus` is `:73-75`.
- `UserRepository.java:104-106` → `markCleanupFailed` is `:103-106` (`:103` is the `@Query` the
  "`@Modifying @Query` precedent" claim depends on).
- `DaoAuthProvider.java:42-56` → `authorize()` is `:28-59`; see F4.
- References list `PlayerOwnershipGuard.java` under `platform/security/repo/`; it lives in
  `platform/security/service/`.

---

### F17 — LOW. The Design A snippet will not compile as written

Both the "current state" quote in Finding 1 and the replacement in Design A use the short name
`PaymentGatewayException`. The real code uses the fully-qualified
`com.softropic.skillars.platform.payment.contract.exception.PaymentGatewayException` inline
(`SubscriptionService.java:901-902`), and
`grep "import.*PaymentGatewayException" SubscriptionService.java` returns nothing — the class is **not
imported**. Copying Design A's block verbatim produces a compile error, and the "current state" quote is
a paraphrase presented as source.

---

### F18 — LOW. Design E's justification is logically inverted (the change itself is fine)

Design E: *"This single change subsumes the old strict-ordering check (**a gap `< 7` can never be `<= 0`**)."*

A gap of `0` is both `< 7` and `<= 0`, so the parenthetical is false as stated. The intended claim —
`{gap ≤ 0} ⊂ {gap < 7}`, so replacing the old condition loses no coverage — is correct, and the code
change is right. Reword so a reviewer is not left checking an argument that does not hold.

---

### F19 — LOW. The composite action has no `build-args` input

Design G: *"extending `.github/actions/docker-build/action.yml`'s existing `build-args` input, which already carries `APK_UPGRADE_CACHE_BUST` the same way."*

`action.yml`'s `inputs:` block (`:4-23`) declares `push`, `load`, `platforms`, `tags`, `labels` — no
`build-args`. The `build-args:` at `:55` is a parameter the composite passes **into**
`docker/build-push-action`, hard-coded to `APK_UPGRADE_CACHE_BUST=${{ github.run_id }}-${{ github.run_attempt }}`,
and no caller sets it.

AC7's deliverable is stated correctly (*"gains a new input (e.g. `commit-sha`) threaded into `build-args`"*),
so this costs the dev only a moment of confusion — but "extending an existing input" and "adding the
first-ever caller-settable build arg, appended to a hard-coded list" are different jobs.

---

### F20 — LOW (observation, not a defect in AC1 as scoped). Nothing in the UI links to the route being widened

`grep -rn "player-subscription" src/frontend/src` returns two hits only: `routes.js:206` and
`src/frontend/src/pages/parent/__tests__/PlayerSubscriptionPageSpec.js:42`. There is no nav entry,
button, or `router.push` to this route anywhere — for PARENT either. So AC1's user-visible outcome
("a self-registered adult player can read their own player subscription") is reachable only by typing
the URL.

AC1 is scoped to the API + route gate, so this is not a miss against its own text. It is flagged because
it is the same bug class deferred-147 was created for (a correct backend with no UI entry point), and
because AC1's user story is phrased as an end-user capability. Worth either a nav link or an explicit
"no nav entry yet, deliberately" note.

---

## 5. What did **not** survive re-verification

These were raised during the pass and killed on a second, skeptical read. Recorded so they are not
re-raised as findings.

**K1 — "`POST /api/auth/refresh` has no caller yet" is false.**
`src/frontend/src/api/auth.api.js:45-47` defines `skillarsRefresh()` → `api.post('/api/auth/refresh')`,
which looked like a direct contradiction. But `grep -rn "skillarsRefresh" src/frontend/src src/test`
returns the definition and **nothing else** — it is dead code. The live session keep-alive is
`sessionApi.refresh()` → `api.get('/refresh')` (`session.api.js:5`, called from
`sessionManager.js:265`), a different endpoint per `AuthResource`'s own class javadoc (`:23-33`).
**The story's claim stands.** Worth one clause in the story noting the unused wrapper exists.

**K2 — "AC5 breaks existing tests."**
I checked every affected case under the new `gap < 7` rule rather than assuming:
`ConfigStartupAssertionTest` — setUp default stub 7/30 (gap 23, no trip ✔), `30/7` → throws ✔,
`30/30` → throws ✔, `7/30` → no throw ✔. `ConfigServiceTest` — write min=30 vs stored cooldown default
30 (gap 0) → throws ✔, write cooldown=7 vs min default 7 (gap 0) → throws ✔, write min=10 vs cooldown
default 30 (gap 20) → persists ✔, stale-cache case min=100 vs stored cooldown 50 (gap −50) → throws ✔.
**Every verdict is unchanged. AC5's "existing tests continue to pass" claim is correct.**

**K3 — "AC4 breaks `testAuthorizationExceptionBubblesUp`."**
That test (`JWTAuthorizationFilterTest:370-406`) asserts the *routine* branch
(`verify(securityUtil, never()).terminateSession(...)`). It uses `SecurityError.MISSING_RIGHTS`, which
AC4 does not touch. **No breakage.** (The widening's real cost is F4, not test fallout.)

**K4 — a cross-ID-space bug in Design A's new disjunct.**
Given this project's history (deferred-148's dispute bug; `Booking.playerId` being a profile PK, not a
user id), I checked whether `existsByIdAndUserId(playerId, parentUserId)` mixes id spaces.
`V138__baseline_schema.sql:4230-4231` FKs `parent_player_links.player_id → main.player_profiles(id)`
and `:4223-4224` FKs `parent_id → main."user"(id)`. So the existing check and the new disjunct consume
the same two id spaces, and the new disjunct matches `PlayerOwnershipGuard:34` exactly.
**No bug. Design A is ID-space-correct.**

**K5 — "the guard checks `player_profiles.parent_id` while the service checks `parent_player_links`, so parents can diverge too."**
Structurally true, but `ShadowAccountService.createPlayerProfile` (`:55-78`) writes
`profile.setParentId(parentId)` and the `ParentPlayerLink` row in the same transaction, and
`linkAdditionalParent` (`:206-233`) is the only other writer. Not a live divergence. **Dropped** — it
would be speculation, not a finding.

**K6 — "Design B's new `.contains("security.emailTokenUsed")` assertion will fail because `ApiAdvice` localises the message."**
Refuted at `ApiAdvice.java:559-561`: `messageSource.getMessage(...)` produces the human message, but the
DTO is `new EmailTokenErrorDto(helpCode, new ErrorMsg(ex.getErrorCode(), message), ex.isCanResend())` —
the **raw error code is in the body**. **The assertion will work.**

**K7 — "the second seeded `email_verification_tokens` row will violate a constraint once it actually commits."**
Refuted: the table has only `PRIMARY KEY (id)` (`V138:2341-2342`) and `UNIQUE (token)` (`:2348-2349`),
plus an FK on `user_id`. No per-user uniqueness, `version`/`created_at` both defaulted. **The commit fix
is safe**, and the expired-token test's `canResend`/`true` assertions still hold on the expiry branch
(`CoachRegistrationService:119-120` passes `true`).

**K8 — "a flagged user could never log in again, so Design C's clear-on-login is unreachable."**
This would have made F1/F2 moot by making AC3 a lockout. Refuted by reading `AuthService.login()`
end-to-end: it checks `passwordEncoder.matches` then `ensureAccountIsLive(user)` (`:288-295`, which
tests only `activated` and `locked`) and **never** invokes Spring's `AccountStatusUserDetailsChecker`.
`credentialsNonExpired` is consulted only on `DaoAuthProvider.authorize()`'s path (`:36`).
**Design C's clear-on-login is reachable and correct.**

---

## 6. Recommendation — per-claim confidence

**Do not start AC3 as written.** F1 and F2 are each sufficient on their own to make it fail: F1 silently
(the flag never persists, and the specified mock-based unit test cannot detect it), F2 loudly but late
(Envers revision insert fails on the first `user` write, including `login()`'s own flag clear). F3 fails
CI. All three are cheap to fix in the spec — three or four extra AC bullets — and expensive to find
during implementation. AC3's own Dev Note calls it *"the largest, highest-risk item in this story"*; the
risk is concentrated in exactly the three places the spec is wrong.

| Finding | Severity | Confidence | Basis |
|---|---|---|---|
| **F1** rollback kills AC3's write | Blocking | **Very high** | Existing javadoc states the rule (`RefreshTokenRepository:86-96`), `AuthService:216-218` repeats it, both premises re-verified (`AuthResource` not transactional; `open-in-view: false`; no `noRollbackFor` in `src/main`) |
| **F2** Envers / `user_aud` | Blocking | **Very high** | `@Audited` at `User.java:45`, `hibernate-envers` at `pom.xml:410`, both sanctioned paths read in V143/V146, and V145 exists because of this exact mistake |
| **F3** V158 fails migration lint | High | **Very high** | Rules + baselines read in `MigrationLint.java` (`:112,:149,:203,:225`), gate read in `MigrationConventionLintTest:86-97`, type convention quoted from V143's own header |
| **F4** `UNKNOWN` ≠ zero-risk; four wrap codes not two | High | **High** on the enumeration (read the whole method); **medium-high** on the operational severity (depends on real DB-fault frequency, which I did not measure) |
| **F5** AC8 drops the `maxSize` item | High | **Very high** | 9 ledger bullets counted mechanically; 8 mapped; the unmapped one is absent from both AC8 and "Do not pick up" |
| **F6** `SubscriptionServiceTest` absent; field-order rationale void | Medium | **Very high** | `find` + `grep "new SubscriptionService("` → 0; field block read at `:49-72` |
| **F7** PLAYER gets ungated parent-only actions | Medium | **High** | Page handlers at `:257,259,280`; no `isParent`; precedent read in `ParentBookingsPage.vue:101,120,140` |
| **F8** no `dbRefreshToken` precedent in `AuthResourceIT` | Medium | **Very high** | `grep` across `src/test` → only `JwtManagerImplTest` |
| **F9** AC1 coverage cannot catch the bug | Medium | **High** | `PlayerSubscriptionOwnershipIT:71` mocks the service; `SubscriptionResourceIT:34,49` defers to it |
| **F10** `.dockerignore` is not the cause of `git.dirty` | Medium | **High** | Dockerfile `:6,10,11` + `.dockerignore:3-4`, which says so itself |
| **F11** four surviving statements of the replaced rule | Medium | **Very high** | All four quoted |
| **F12** stored-config rollout risk | Medium | **Medium-high** on severity (no visibility into what any deployed environment actually stores); **high** that the story's argument is incomplete |
| **F13**–**F19** citation / wording defects | Low | **High** each | Each re-read directly; F13, F14, F15 are the ones with practical consequences |
| **F20** no nav entry for the widened route | Low (observation) | **High** | `grep` → `routes.js` + its own spec only |

**What I independently re-checked, and what I did not.** Re-checked at `df07a842`: every `file:line` in
the story; both registration services' `verifyEmail`; the full bodies of `AuthService.login`/`refresh`,
`DaoAuthProvider.authorize`, `JWTAuthorizationFilter.doFilterInternal`/`attemptAuthorization`/`isGenuineDenial`,
`Principal.instanceFrom`, `assertPlayerOwnership`, `PlayerOwnershipGuard.check`,
`ConfigStartupAssertion`/`ConfigService` guards, `quiesceAsyncExecutors`; the transaction annotations on
`AuthService`, `RefreshTokenRepository`, `UserRepository`; the Envers setup; the Flyway baseline and
migration lint rules; `ConfigStartupAssertionTest`/`ConfigServiceTest`/`JWTAuthorizationFilterTest`
existing cases; `PlayerSubscriptionOwnershipIT` in full; the Dockerfile, `.dockerignore`, both workflows,
the composite action, the git plugin config; all eight cited `deferred-work.md` sections; the
deferred-146 story file's lines 82, 112, 228, 254-266, 296-325; `docs/testing/readme.md`,
`docs/deployment/baseline/pr.md`, `docs/deployment/monitoring.md`,
`docs/deployment/migration-conventions.md`. Verified by `grep`/`find`, not inference: the existence or
absence of `SubscriptionServiceTest`, `DaoAuthProviderTest`, `PrincipalTest`, `JWTAuthorizationFilterIT`,
`routerGuardSpec.js`, `changeAccountLockStatus` callers, `skillarsRefresh` callers,
`new SubscriptionService(` call sites, `player-subscription` nav references, `noRollbackFor` anywhere.

**Not verified, and not verifiable here:** the GitHub Actions figures in AC8 items 4/5/9
(run `37584051185` and its µs/failsafe numbers) and all of AC6's live GitHub state (branch protection,
ruleset `20583638`'s contents, PR #255's check-run list). These are reproduced faithfully from
`deferred-work.md:3893-3895` and `:3888`, where they are recorded as log- and API-verified on 2026-10-07,
but nothing in this repository can confirm them. Treat them as second-hand. No test or mutation run was
executed as part of this review — every finding above is from reading current source.
