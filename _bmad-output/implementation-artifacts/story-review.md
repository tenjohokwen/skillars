# skillars-deferred-143 — Pre-Implementation Story Review

**Story key:** `skillars-deferred-143-account-lock-enforcement-and-forced-logout-session-termination`
**Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-143-account-lock-enforcement-and-forced-logout-session-termination.md`
**Audit date:** 2026-10-05
**Real HEAD at audit time:** `3a62698c` — *"Story Deferred-142: OTP skp-Cookie Gap, Video-Page Redirect Fix, and Stale-Env Ops Note (#249)"* (obtained via `git rev-parse --short HEAD` / `git log -1`, not read from the story).

Every citation below was re-opened at this HEAD. The story's own "verified against HEAD `3a62698c`" header happens to be accurate this time — but it was not trusted; it was independently confirmed.

**Headline:** the story's *remediation design* is sound in intent and its citations are unusually accurate (19/24 exact). But three claims about how the existing system behaves are wrong, and two of them are load-bearing:

- **B1 (blocker)** — AC2's core promise ("the presented refresh token is revoked") is defeated by `AuthService`'s own transaction boundary. The design cannot deliver it as written.
- **B2 (blocker)** — AC3 attaches an aggressive teardown to a catch block that also fires on ordinary 15-minute idle-outs and tokenless requests, which the codebase explicitly classifies as expected traffic and has already engineered against.
- **B3 (false premise)** — Finding 3's exploit chain names the wrong endpoint. `sessionManager.js:265` calls `GET /refresh`, not `POST /api/auth/refresh`, and `POST /api/auth/refresh` has **zero** frontend callers.

---

## 1. Citation verification (Layer 1)

**19 MATCH · 4 DRIFTED · 1 FALSE**

| # | Story claim | Verdict | Evidence at HEAD `3a62698c` |
|---|---|---|---|
| 1 | `AuthService.java:95-97` checks only `user.isActivated()` | **MATCH** | Lines 95-97 are exactly `if (!user.isActivated()) { throw new DisabledException("Account is not activated"); }` |
| 2 | `AuthService.refresh()` spans `:139-223`, loads user at `:188` | **MATCH** | Method signature line 139, closing brace 223; `var user = userRepository.findById(token.getUserId())` at 188 |
| 3 | `User.lock()` at `User.java:382-386`, doc'd *"Locks this user account, preventing login."* | **DRIFTED** | Javadoc is 380-383 (the quoted sentence is **line 381**); `lock()` body is 384-386. Correct range: `User.java:380-386` |
| 4 | `User.isLocked()` at `:246-248` | **MATCH** | Exact |
| 5 | `UserAdminService.lockUserAccount()` at `:116-122`, `@PreAuthorize(HAS_ADMIN_ROLE)`, calls `u.lock()` | **MATCH** | `@PreAuthorize(SecurityConstants.HAS_ADMIN_ROLE)` at 115, method 116-122, `u.lock()` at 119. (Path is `platform/security/service/`, not `platform/admin/service/` — the story never states a path, so no error.) |
| 6 | Repo-wide grep: `lockUserAccount` referenced only by the service + `UserServiceIT` | **MATCH** | `UserAdminService.java:116`, `UserServiceIT.java:207,209`. No controller, no frontend. |
| 7 | `GdprErasureService` `:277-278` sets `activated=false` + `locked=true` | **MATCH** | `user.setActivated(false);` 277, `user.setLocked(true);` 278 |
| 8 | `Principal.java:148-151` wires `.enabled(user.isActivated())` / `.accountNonLocked(!user.isLocked())` | **MATCH** | `.enabled(...)` 148, `.accountNonLocked(...)` 151 |
| 9 | `authApi.login()` has zero callers in `src/frontend/src` | **MATCH** | Only `verifyOtp`, `skillarsLogin`, `skillarsLogout` are called. `login()` is dead. |
| 10 | `LoginPage.vue:167` → `authApi.skillarsLogin()` | **MATCH** | Exact |
| 11 | `JWTAuthorizationFilter.java:152` is `securityUtil.logout(res)`; catch at `:150` | **MATCH** | Catch `AccountStatusException \| AuthorizationException \| AccessDeniedException` at 150, comment at 151, call at 152 |
| 12 | `SecurityUtil.logout()` at `:137-140` | **MATCH** | Exact |
| 13 | `SecurityUtil` depends only on `LoginTokenManager` (`:40,43`); constructor hand-written at `:43-45` | **MATCH** | Field 40, ctor 43-45. Also confirmed: **zero** `new SecurityUtil(...)` sites anywhere in `src/`, so the signature change has no direct instantiation blast radius. |
| 14 | `JwtManagerImpl.deleteLoginToken()` `:182-190` clears exactly `potc`, `bcookie`, `user`, `admin`, `ION`, `rint` | **MATCH** | Six `removeCookie` calls, 184-189. Neither `rtkn` nor `skp`. |
| 15 | `AuthService.logout()` `:225-239`, marks token used `:227-234` | **MATCH** | Method 225-239; the `if (rawToken != null)` revocation block is 227-235 (the story's 234 stops one line short of the closing `}`, substantively correct) |
| 16 | `clearAuthCookies()` at `:241-245` | **MATCH** | Exact |
| 17 | `clearAuthCookies` called from **three** places: `:162, :166/:173, :184, :189` | **FALSE** | There are **five** call sites: **162, 167, 173, 184, 189**. Line 166 is `refreshTokenRepository.markAllUsedByUserId(ownerId);`, not a `clearAuthCookies` call. Both the count ("three") and one line number (166→167) are wrong. See F6. |
| 18 | `sha256Hex` helper at `AuthService.java:259-267` | **MATCH** | Exact, `private static String sha256Hex(String raw)` |
| 19 | `ApiAdvice.java:274-279` → `security.accNotEnabled`; `:281-286` → `security.accLocked` | **MATCH** | Both exact, both `@ResponseStatus(HttpStatus.UNAUTHORIZED)` |
| 20 | i18n copy for both keys in `en-US`, `fr-FR`, `de-DE` | **MATCH** | `en-US:617-618`, `fr-FR:618-619`, `de-DE:1182-1183` |
| 21 | `sessionManager.js:265` calls `sessionApi.refresh()` | **MATCH (line)** | Line 265 is `await sessionApi.refresh()`. **But the story's parenthetical "(→ `POST /api/auth/refresh`)" is false** — see F3. |
| 22 | `boot/axios.js` 401 handler redirects to `/login` on `security.sessionExpired`/`security.unauthorized`, never calls refresh | **MATCH** | `axios.js:163-164`; no refresh call in the interceptor |
| 23 | `JWTAuthorizationFilterTest`'s seven `verify(securityUtil).logout(response)` at `:200, 226, 244, 270, 292, 314, 351` | **MATCH** | All seven line numbers exact |
| 24 | `AuthResourceIT`: `logout_marksTokenUsedAndClearsCookies` `:382-412`; `refresh_*` `:261-380`; `insertUser` `:449-465` hardcoding `locked=false` | **MATCH** | `@Test` 382 → `}` 412; `refresh_validUnusedToken` 261 → `refresh_missingCookie` ends 380; `insertUser` 449-465, SQL literal `false` for `locked` at 457 |
| 25 | `JWTAuthorizationFilter.java:215-227` `isRefreshTokenRevoked()` | **DRIFTED (trivial)** | Method is **214-227** (signature on 214). Content as described. |
| 26 | `JwtManagerImpl.java:90-102` comment on role derivation | **MATCH** | Comment block 90-102, exactly the cited rationale |
| 27 | `architecture.md#Authentication & Security` | **MATCH** | `### Authentication & Security` at `architecture.md:136` |
| 28 | `project-context.md`: business logic lives in `platform.{module}.service` | **MATCH (paraphrase)** | Rule supported by `project-context.md:108, 124, 139, 154`. Not a verbatim quote but substantively correct. `platform.security.service` is a legal home for a repository dependency (the "no repositories here" rule at `:165` applies to `infrastructure`, not `platform`). |

---

## 2. Ledger & precedent attribution (Layer 2)

Checked against: the referenced story files themselves, source comments/Javadoc near the named code, and `git log`/`grep` for the story numbers.

| Claim | Sources checked | Verdict |
|---|---|---|
| Story is **not** sourced from `deferred-work.md` or a code-review run (line 8) | `deferred-work.md` grep for `deferred-143` / lock-enforcement; `sprint-status.yaml:2` | **TRUE** — no ledger entry; sprint-status confirms manual-security-analysis sourcing |
| *"No pre-implementation `story-review.md` has been run against this story yet."* (line 8) | `sprint-status.yaml:2`; the existing `story-review.md` on disk | **FALSE** — `sprint-status.yaml:2` documents a full pre-implementation review dated 2026-10-05 against this same HEAD, whose three accepted findings are already folded into the story (GdprErasure `:277-278`, `InvalidJWTDataException` added to AC3, three AuthResourceIT ranges corrected). The prior `story-review.md` for deferred-143 was on disk when this audit started. See F9. |
| deferred-142 **Context section**, *"three of the four paths"* discussion | `skillars-deferred-142-....md:252`, under `## Context: Current State` → `### AC2 — The skp cookie gap…` | **TRUE** — line 252: *"Three of the four paths into the shared `createLoginCookies` helper have no `skp` today."* Correct section, correct phrase. |
| deferred-142 **Review Findings DEFER** item on `URLEncoder.encode` affecting *"all three `skp` writers"* | `skillars-deferred-142-....md:179` (`### DEFER` heading) and `:185` (the item); grep `writers` across that file | **PARTLY FALSE** — the DEFER item exists, in the right section, and is about `URLEncoder.encode`. But its text says *"Identical at **both** `AuthService` sites"*; the string `writers` appears **nowhere** in deferred-142. The quoted phrase is the story's own paraphrase presented as a quotation. The underlying *fact* is true today (three writers: `AuthService.java:133`, `:219`, `JwtManagerImpl.java:121` — the third added by deferred-142 itself). See F10. |
| `RefreshTokenRepository.markAllUsedByUserId` carries deferred-100 AC3 / deferred-101 AC11 provenance | `RefreshTokenRepository.java:20-28` Javadoc | **TRUE** — and materially relevant to F1; see below |
| `deleteLoginToken` never clearing `skp` is pre-existing and known | `skillars-deferred-142-....md:211` | **TRUE** — *"`skp` is never cleared by `deleteLoginToken`, but that gap is pre-existing"* |
| `SecurityUtil` gaining `RefreshTokenRepository` carries **no circular-dependency risk** | `JwtManagerImpl.java` / `LoginTokenManager` grep for `AuthService`/`SecurityUtil` (only comment mentions); `AuthService` import list | **TRUE** — `LoginTokenManager`/`JwtManagerImpl` reference neither type in code. The story's claim is correct; this was actively tested for refutation and survived. |

---

## 3. Mechanistic claims (Layer 3)

### M1. *"`AuthService.refresh()` loads the user at `:188` and checks nothing about account status"* — **TRUE**
Full body of `refresh()` (139-223) read. Between `findById` at 188 and `createLoginToken` at 203 there is only `Principal.instanceFrom(user)` (193) and the new-token mint (195-201). No status check. The `Principal` built at 193 *carries* `accountNonLocked`/`enabled`, but nothing ever asserts on them — the principal is handed straight to `createLoginToken`.

### M2. *"`isLocked()` itself is checked nowhere"* (story line 36, bolded) — **FALSE**
`grep -rn "isLocked()" src/main/java` returns **13 call sites** outside the getter:
`Principal.java:151`, `RegistrationOtpResendSupport.java:71`, `PlayerRegistrationService.java:144,218,245`, `ParentRegistrationService.java:133,207,234`, `CoachRegistrationService.java:129,203,230`, `UserErasedEventListener.java:25`. The field is additionally checked at `User.java:409, 426, 457` (`canInitiatePasswordReset()`, the reset-password guards). The story contradicts itself: two paragraphs earlier (line 38) it correctly states `Principal.instanceFrom` wires `.accountNonLocked(!user.isLocked())`. See F4.

### M3. *"`/authenticate` … correctly rejects locked accounts; the live path … bypasses Spring Security's provider pipeline"* — **TRUE but materially misleading**
Traced in full: `DaoAuthProvider.authorize()` (`DaoAuthProvider.java:28-59`) calls `retrieveUser(...)` → `LoadUserByUserNameService.loadUserByUsername` → `Principal.instanceFrom(user)` (`LoadUserByUserNameService.java:35`) → `getPreAuthenticationChecks().check(user)` at `:36`, which rejects `accountNonLocked == false`.

**But `authorize()` is not confined to the dead `/authenticate` path.** It is called twice by the *live* `JWTAuthorizationFilter.attemptAuthorization`: at `:189` (DB-refresh-token lapsed) and `:200` (all refresh tokens revoked). `hasDbRefreshTokenExpired` gates that on `DB_REFRESH_TOKEN_INTERVAL = 5 min` (`SecurityConstants.java:120`), and the filter's own class Javadoc says so explicitly (`:80-85`: *"`daoAuthProvider.authorize(...)` re-checks the account against the DB so locked / deactivated / force-logged-out users are caught"*). See F5.

### M4. *"`AuthService.logout()` … marks the presented refresh token used"* — **TRUE**
`:226-235`: `getCookieValue` → `sha256Hex` → `findByTokenHash` → `.filter(t -> !t.isUsed())` → `setUsed(true)` + `save`. Returns normally, so the class-level `@Transactional` commits.

### M5. *"Ordering in `refresh()`: after the reuse/expiry checks (`:145-186`), before the new row (`:195-201`) and `createLoginToken` (`:203`)"* — **TRUE as stated, but see F1**
Ranges verified exactly. The *placement* is right; the *transactional consequence* of throwing from there is not what AC2 assumes.

### M6. Transaction boundary — traced explicitly (this is the load-bearing one)
- `AuthService` carries class-level `@Transactional` (`AuthService.java:47`, `org.springframework.transaction.annotation.Transactional`, default rollback-on-`RuntimeException`).
- `AuthResource` is **not** `@Transactional` (full file read; `@RestController @RequestMapping @Observed @RequiredArgsConstructor` only). So `AuthService` methods are the outermost transaction boundary.
- `spring.jpa.open-in-view: false` (`application.yaml:158`) — no view-level transaction extends it.
- `LockedException` → `AccountStatusException` → `AuthenticationException` → `RuntimeException`. Throwing it from `refresh()` **rolls the whole transaction back.**
- The existing code demonstrably knows this: the only DB write on a throwing branch, `markAllUsedByUserId`, is annotated `@Transactional(propagation = Propagation.REQUIRES_NEW)` (`RefreshTokenRepository.java:30-33`) precisely so the revocation survives the rollback — documented at `:20-28` citing deferred-100 AC3 / deferred-101 AC11. Every other throwing branch (`:173`, `:184`, `:189`) calls only the cookie-clearing helper, never a DB write.
- The story's proposed `terminateSession` uses a **plain** `refreshTokenRepository.save(t)` (story line 100), which joins the caller's transaction.

**Conclusion: a plain `save()` inside a branch that throws out of `AuthService` is discarded.** See F1.

---

## 4. Corner cases, false assumptions and missed flows (survived adversarial re-verification)

### F1 — **BLOCKER.** AC2's "the presented refresh token is revoked" cannot happen: the enclosing transaction rolls it back
*Story:* AC2 bullet 2 (line 129) — *"On that rejection, the refresh token presented in the `rtkn` cookie for this request is revoked (marked used)"*; design line 83 — *"fall through to Part B's teardown so the presented refresh token is revoked along with the denial, not left alive for a retry."*

*Reality:* by the time `ensureAccountIsLive(user)` fires (story line 83: immediately after `AuthService.java:188`), the request has already executed `token.setUsed(true); token.setRotatedAt(...); saveAndFlush(token)` at `:177-180`. Throwing `LockedException` from `:188`+ rolls back the entire `AuthService.refresh()` transaction (`AuthService.java:47`; `AuthResource` non-transactional; OSIV off), which **un-does the `used = true` flush at `:177-180` and discards `terminateSession`'s own `save(t)`**. Net DB effect: zero. The presented refresh token is left fully usable — the exact outcome AC2 exists to prevent, and the exact resurrection vector Finding 3 complains about.

*Proof the codebase already knows this:* `RefreshTokenRepository.java:30-33` uses `REQUIRES_NEW` for `markAllUsedByUserId` for this precise reason, documented at `:20-28`. Compare the three existing throwing branches in `refresh()` (`:172-175`, `:179-186`, `:188-191`) — all cookie-only, no DB write.

*Same defect, second site:* AC3 bullet 2 (line 133) — *"`AuthService.logout()` and `AuthService.refresh()`'s internal failure branches produce the **exact same** cookie/DB outcome as the filter's forced-logout path"* — is unachievable as designed. From the filter (`JWTAuthorizationFilter.java:152`) there is no ambient transaction, so `SimpleJpaRepository.save` opens and commits its own; from inside a throwing `AuthService` branch it joins and rolls back. Identical code, opposite DB outcomes.

*Note on the obvious fix:* annotating `terminateSession` `@Transactional(REQUIRES_NEW)` **will not work** — `SecurityUtil` is declared `public final class` (`SecurityUtil.java:38`) and implements no interface, so Spring cannot create a proxy for it; the context will fail to start. Workable options: (a) add a `REQUIRES_NEW` revocation query to `RefreshTokenRepository` mirroring `markAllUsedByUserId:30-33` and call that from `terminateSession`; or (b) move the account-status check *before* `:177` and revoke via `markAllUsedByUserId` (which already commits independently); or (c) perform the teardown outside the transaction (in `AuthResource` or an advice). Option (a) also needs care: after `markAllUsedByUserId`'s bulk `UPDATE … version = version + 1`, the first-level cache still holds the stale managed `RefreshToken` loaded at `:146`, so a subsequent managed `save()` on it is version-stale.

*Confidence: HIGH.* Every link (annotation, exception hierarchy, controller non-transactionality, OSIV flag, the contrasting `REQUIRES_NEW` precedent) was read directly.

---

### F2 — **BLOCKER.** AC3 attaches refresh-token revocation to a catch block that fires on ordinary 15-minute idle-outs and tokenless requests
*Story:* AC3 bullet 1 (line 132) — the catch-all path *"clears `rtkn` and `skp` … **and marks the presented refresh token (if any) used in the DB**"*, for *everything* caught at `:150`.

*What is actually caught there:*
- `JWTExpiredException extends AuthorizationException` (`JWTExpiredException.java:8`) — thrown by `ClaimsExtractorImpl.java:47` whenever the JWT is past its 15-minute TTL.
- `MissingAuthenticationException extends AuthorizationException` (`MissingAuthenticationException.java:8`) — thrown by `JWTAuthorizationFilter.getAuthentication:233` whenever `potc` is absent. `potc`'s cookie `maxAge` **is** `JWT_TTL` (`JwtManagerImpl.java:249`; `SecurityConstants.java:102` = 15 min), so it is absent on every request after a 15-minute idle.

The filter's own Javadoc classifies both as routine, twice, in this exact file:
> `:263-270` — *"A tokenless request (`MissingAuthenticationException` — crawlers, stale bookmarks, pre-login SPA routes) and an ordinary idle-out (`JWTExpiredException`) are **expected traffic** on an unauthenticated, unrate-limited path"*
> `:304-311` — *"a `SecurityAlertEvent` writes one `AuditTrail` DB row per event, so it is fired ONLY for genuine denial signals. It is deliberately NOT fired for `MissingAuthenticationException` … nor for `JWTExpiredException` — alerting on those would turn an unauthenticated, unrate-limited path into an audit-trail flood / **DB-write amplifier**."*

**AC3 reintroduces precisely the DB-write amplification that deferred-90 AC5/F22 and the `SecurityAlertThrottle` (`:117, :326-350`) were built to remove** — a `findByTokenHash` lookup plus a conditional `save` on every expired-JWT and every tokenless request to a secured URL, unthrottled, on an unauthenticated path. Today that branch does zero DB work (`SecurityUtil.logout` = `clearContext()` + six `removeCookie` calls).

**Second consequence — the change is self-defeating against the story's own goal.** `rtkn` has a 7-day TTL (`SecurityConstants.java:105`) deliberately outliving the 15-minute JWT, and `POST /api/auth/refresh` exists to trade it for a new session (`AuthResource.java:25-28`). Revoking it on every idle-out means that once anyone wires the SPA to `POST /api/auth/refresh` — which Finding 3 assumes is already the case — the endpoint is permanently unusable: the token is always already dead by the time it is needed. The *user-facing* breakage is latent today only because nothing calls that endpoint (F3); the DB-write regression is immediate.

**Third: AC3 is wider than the story's own stated intent.** The user story (line 16) enumerates four causes — *"stolen/fixed token, locked account, disabled account, expired credentials"*. None is "expired JWT" or "missing token". AC3 silently widens from those four to the whole catch block. This reads as an unnoticed over-reach rather than a decision.

*Recommended narrowing:* gate the refresh-token revocation on the genuine-denial set the file already defines — `JWTTheftException`, `InvalidJWTDataException`, `AccountStatusException`, `AuthorizationException` with `SecurityError.ACCOUNT_NOT_LOGIN_ABLE` — mirroring `maybePublishSecurityAlert`'s `genuineDenial` predicate at `:312-319`, and leave `JWTExpiredException`/`MissingAuthenticationException` on today's cookie-only teardown. (Note: locked/disabled accounts reaching the filter arrive **wrapped** as `AuthorizationException` with `SecurityError.ACCOUNT_NOT_LOGIN_ABLE`, not as raw `AccountStatusException` — `DaoAuthProvider.java:47-51` — so a naive `instanceof AccountStatusException` test would miss them. The same wrapping already makes `maybePublishSecurityAlert` miss them today; pre-existing, out of scope, but it will bite whoever writes this predicate.)

*Confidence: HIGH* on the mechanism and the DB-write regression (all three Javadoc passages and both exception hierarchies read directly). *MEDIUM* on end-user impact, which is latent today — stated honestly rather than inflated.

---

### F3 — **FALSE PREMISE.** Finding 3's exploit chain names the wrong endpoint; `POST /api/auth/refresh` has no frontend caller at all
*Story (line 59):* *"`src/frontend/src/plugins/sessionManager.js:265` calls `sessionApi.refresh()` (→ `POST /api/auth/refresh`) **proactively** … so a session the filter just forcibly denied … can be **fully resurrected** by the next proactive refresh."*

*Reality:*
1. `src/frontend/src/api/session.api.js:4-6` — `refresh() { return api.get('/refresh') }`. The proactive call is **`GET /refresh`**, not `POST /api/auth/refresh`.
2. `POST /api/auth/refresh` is `authApi.skillarsRefresh()` (`auth.api.js:45-47`), and it has **zero callers** in `src/frontend/src` — the same dead-code condition the story correctly identifies for `authApi.login()`. Independently corroborated by the checked-in Istanbul report: `src/frontend/coverage/coverage-final.json` records `skillarsRefresh` (fn index 5) with hit count `0`.
3. Three separate source Javadocs document the distinction the story collapses: `AuthResource.java:23-35`, `JWTAuthorizationFilter.java:90-97` (*"This is why `GET /refresh` keeps a session alive … For full token rotation … use `POST /api/auth/refresh` instead"*), `SessionRefreshFilter.java:31`.
4. `GET /refresh` is a **secured** endpoint (`AppEndpoints.java:19, 67` — mapped into `SECURED_MAPPINGS`), so it passes through `JWTAuthorizationFilter.attemptAuthorization`, which performs the `daoAuthProvider.authorize` DB re-auth. A locked account calling it is **denied, not resurrected**. `/api/auth/refresh` is the one in `PUBLIC_ENDPOINTS` (`AppEndpoints.java:44`).

*What this invalidates:* Finding 3's HIGH severity and its entire stated chain; the second user-story paragraph's rationale (*"cannot be silently undone by the browser's own proactive session-refresh call"*); and part of AC3's justification.
*What survives:* `AuthService.refresh()` genuinely performs no account-status check, and hardening it is correct defence-in-depth for whenever that endpoint is wired up. AC1 and AC2 remain worth doing — on honest grounds, not this one.

*Confidence: HIGH.* Four independent sources (the API module, the grep, the coverage artifact, three source Javadocs).

---

### F4 — **FALSE CLAIM.** *"`isLocked()` itself is checked nowhere"* (story line 36, bolded)
13 call sites in `src/main/java` (full list in M2), plus three direct field checks in `User.java` (`:409, 426, 457`). The story contradicts its own line 38. The accurate statement is: *"`isLocked()` is never checked in `AuthService.login()` or `refresh()`."*

Beyond accuracy, this matters for implementation: the three registration services already implement the "reject a locked user" shape (e.g. `CoachRegistrationService.java:129`), so `ensureAccountIsLive` has in-repo precedent the story does not reference. *Confidence: HIGH (mechanical grep).*

---

### F5 — **OVERSTATED FRAMING.** Locking is not cosmetic on the live request path; it is already enforced within ~5 minutes
*Story (line 38):* *"The live path … hand-rolls its own password check and bypasses Spring Security's provider pipeline entirely — which is how the lock check got silently dropped from the pipeline that's actually in use."* Story (line 34): *"The moment that admin action is wired to an endpoint, locking a user will **silently do nothing** on the live login/refresh path."*

`DaoAuthProvider.authorize()` — the lock-enforcing call — runs inside the **live** `JWTAuthorizationFilter` at `:189` and `:200`, not only on the dead `/authenticate` path. Because `hasDbRefreshTokenExpired` is gated on `DB_REFRESH_TOKEN_INTERVAL = 5 min` (`SecurityConstants.java:120`), **a locked account is force-denied on any secured request within ~5 minutes of being locked**, with no code change at all. The filter's Javadoc states this at `:80-85`, and the comment at `:110-116` notes a locked account *"emits one [alert] on EVERY request once its … DB refresh token lapses."*

So the real, accurate gap is narrower but still worth fixing:
- a locked user can obtain a **brand-new** session via `POST /api/auth/login` indefinitely (genuine, unbounded, and the strongest case for AC1); and
- an existing session survives up to 5 minutes after locking (bounded, by design — the 5-minute window is the documented revocation latency of the whole scheme, not a defect of `AuthService`).

*Recommendation:* reword Findings 1 and 3 and the severity labels to this. The fix itself stays exactly as designed. *Confidence: HIGH* — the `authorize()` → `LoadUserByUserNameService:35` → `Principal.instanceFrom:151` → `accountNonLocked` chain and both filter call sites were read end-to-end.

---

### F6 — **CITATION / SCOPE ERROR.** `clearAuthCookies` has five call sites, not three; one cited line is wrong
Story line 116 says *"called from three places inside `refresh()` (`:162`, `:166`/`:173`, `:184`, `:189`)"* — the prose count ("three") disagrees with its own list, and `:166` is `markAllUsedByUserId`, not a call site. Actual: **162, 167, 173, 184, 189**. Task 4 instructs threading `req` through "every internal call site"; a dev working from the "three" count will leave two un-updated — a compile error, so not silently dangerous, but it will stall the task. *Confidence: HIGH (mechanical).*

---

### F7 — **FACTUAL ERROR in Task 6.** `insertUser` has three existing call sites, not two
Story line 152: *"it needs a new overload (or an added `boolean locked` parameter, updating its two existing call sites)"*. `AuthResourceIT.java` calls `insertUser(...)` at **94, 95 and 96** (COACH, PARENT, UNVERIFIED). *Confidence: HIGH (mechanical).*

---

### F8 — **SCOPE UNDERSTATED.** Four `AuthService` methods change, not two
Header line 6 says *"two methods changed in `AuthService`"*. Tasks 1 and 4 together change `login()`, `refresh()`, `logout()` **and** `clearAuthCookies()` (whose signature changes, forcing five call-site edits per F6), plus the constructor via a new `SecurityUtil` dependency. Minor on its own; combined with F6 it means a dev sizing the task from the header will under-plan. *Confidence: HIGH.*

---

### F9 — **STALE SELF-DESCRIPTION.** Line 8's *"No pre-implementation `story-review.md` has been run against this story yet"* is false
`sprint-status.yaml:2` records a completed pre-implementation review dated 2026-10-05 against this same HEAD, and the story already contains that review's three accepted corrections (the GdprErasure `:277-278` fix, `InvalidJWTDataException` in AC3's list, and the three corrected `AuthResourceIT` ranges — all three re-verified as correct in §1 above). Leaving the line in risks a second reviewer re-deriving settled ground, or a dev discounting a review that did in fact happen. *Confidence: HIGH.*

---

### F10 — **MISQUOTATION (low severity).** *"all three `skp` writers"* is not deferred-142's wording
deferred-142's DEFER item (`:185`) reads *"Identical at **both** `AuthService` sites"*; `writers` appears nowhere in that file. The surrounding attribution (Review Findings DEFER section, `URLEncoder.encode`) is correct, and the underlying fact is true today — there are three `skp` writers (`AuthService.java:133`, `:219`, `JwtManagerImpl.java:121`), the third added by deferred-142 after that DEFER item was written. Drop the quotation marks or quote accurately. *Confidence: HIGH.*

---

### F11 — **IMPLEMENTATION BLOCKER (latent).** `SecurityUtil` is `final`, so no Spring proxy — no `@Transactional`, and `@Cacheable`-style fixes are equally unavailable
`public final class SecurityUtil` (`SecurityUtil.java:38`) with no implemented interface. Any attempt to resolve F1 by annotating `terminateSession` transactionally will fail at context startup. Flagged here because it is the first thing a dev will reach for. (Mockito mocking is unaffected — the inline mock-maker already mocks this final class in `JWTAuthorizationFilterTest.java:83`.) *Confidence: HIGH.*

---

### F12 — **DESIGN-PATTERN OBSERVATION (judgment, not a defect).** Giving `SecurityUtil` a JPA repository widens the project's most broadly-injected security helper
`SecurityUtil` is referenced across **52** main-source files as the stateless `SecurityContext` accessor (`getCurrentUserName`, `requireCurrentUserId`, `isAdmin`, …). Its only current collaborator is `LoginTokenManager`. Adding `RefreshTokenRepository` makes every module's security helper DB-aware and transaction-sensitive, and F1 shows the transactional semantics of `terminateSession` differ by caller — a sharp edge on a class used that widely.

This does **not** violate `project-context.md` (the no-repositories rule at `:165` scopes to `infrastructure`; `platform.security.service` may hold repositories), and the story's no-circular-dependency analysis is correct. But `AuthService` already owns `RefreshTokenRepository`, already contains the exact code being promoted (`:225-245`), and is already the transaction owner — so `AuthService.terminateSession(req, res)`, with `JWTAuthorizationFilter` calling `authService` instead of `securityUtil`, is the lower-blast-radius placement. (The filter would need `AuthService` injected; no cycle — `AuthService` does not reference the filter.) Note this does **not** by itself fix F1: a self-invocation from `refresh()` still joins the same transaction.

*Confidence: MEDIUM — this is an architectural preference with a concrete rationale, not a correctness defect. The story's chosen placement is defensible; raised for an explicit decision rather than as a required change.*

---

## 5. What did NOT survive re-verification

These were raised during the pass and then **killed or downgraded** on a second, skeptical read. Listing them is the point — a report with no casualties did not actually re-check itself.

| Raised | Why it was dropped |
|---|---|
| *"`terminateSession`'s `SecurityContextHolder.clearContext()` will break `AuthService.logout()`"* | **Dropped.** Read `AuthResource.logout()` (`:68-73`) in full: it calls `authService.logout(req, res)` and immediately returns `noContent()`. Nothing downstream reads the context, and Spring Security's `SecurityContextHolderFilter` clears it at request end anyway. No impact. |
| *"Adding `RefreshTokenRepository` to `SecurityUtil` risks a circular dependency"* | **Dropped — the story is right.** Actively tried to refute its claim: grepped `JwtManagerImpl` and `LoginTokenManager` for `AuthService`/`SecurityUtil` (only prose comments, no code references) and confirmed `RefreshTokenRepository` is a leaf. No cycle exists. Recorded as a verified-correct claim in §2. |
| *"The lock check enables user enumeration at login"* | **Dropped.** `ensureAccountIsLive` replaces the block at `:95-97`, which sits **after** the password check at `:90-93`. A locked-account response is only reachable with correct credentials, so no enumeration oracle. The story's placement is correct; noting it here as actively verified, not assumed. |
| *"`LockedException` → `security.accLocked` won't trigger the axios `/login` redirect (`axios.js:164` matches only `sessionExpired`/`unauthorized`)"* | **Downgraded to a note, not a finding.** True of the interceptor, but the only two routes that can emit `accLocked` are `POST /api/auth/login` (`LoginPage.vue` handles its own errors and the i18n copy exists in all three locales) and `POST /api/auth/refresh` (no caller — F3). Not a defect introduced by this story. Worth a glance if `skillarsRefresh` is ever wired up. |
| *"`CookieUtil.removeCookie(name, res, httpOnly, sameSite)` as used in the design sketch may not exist"* | **Dropped.** `CookieUtil.java:45-46` declares exactly that 4-arg overload. `getCookieValue(req, name)` at `:28`. The design sketch compiles against real signatures. |
| *"The pre-existing `markAllUsedByUserId` revocation in `refresh()` is also rolled back by the subsequent throw"* | **Dropped — and this is the one that nearly became a false positive.** It *looks* identical to F1. It is not: `RefreshTokenRepository.java:30-33` annotates it `@Transactional(propagation = Propagation.REQUIRES_NEW)`, so it commits in its own transaction and survives. Reading only `AuthService` would have produced a confident, wrong finding; the repository interface is what settles it. |
| *"Revoking `rtkn` on idle-out will visibly log users out who are currently kept alive by a silent refresh"* | **Downgraded (folded into F2 with honest calibration).** Initially stated as immediate user-facing breakage. On re-check, F3 shows nothing calls `POST /api/auth/refresh`, and `axios.js:163-164` already redirects to `/login` on these 401s today — so there is no silent recovery to break *yet*. The DB-write amplification is immediate and real; the session-breakage is latent. F2 now says exactly that instead of overclaiming. |
| *"`GET /refresh` resurrects locked sessions too"* | **Dropped.** `/refresh` is in `SECURED_MAPPINGS` (`AppEndpoints.java:67`), so it runs through `attemptAuthorization` and its `daoAuthProvider.authorize` DB re-auth. It denies locked accounts rather than resurrecting them. |

---

## 6. Recommendation

**Per-claim confidence, not a blanket score.** Citations and precedent attributions were checked mechanically against files opened at HEAD `3a62698c` and are near-certain. The transaction and dead-endpoint findings were traced end-to-end through annotations, exception hierarchies, config flags and a coverage artifact, and are high-confidence. The two judgment calls (F12 placement, F2's end-user severity) are labelled as such. Eight candidate findings were killed or downgraded in re-verification — one of which (`markAllUsedByUserId`) would have been a textbook false positive had the repository interface not been opened.

**Verdict: send back for revision before implementation.** The design is 80% right and the citation discipline is genuinely good, but two defects would survive into merged code.

**Must fix before dev starts:**
1. **F1** — redesign AC2's revocation so it survives the rollback. Preferred: a `REQUIRES_NEW` revocation query on `RefreshTokenRepository` mirroring `markAllUsedByUserId:30-33`, invoked by `terminateSession`. Note F11 (`SecurityUtil` is `final` — no `@Transactional` on it) and the stale-first-level-cache/version interaction documented at `RefreshTokenRepository.java:20-28`. Then rewrite AC3 bullet 2, which currently promises an outcome identical across transactional and non-transactional callers — not achievable with one plain `save()`.
2. **F2** — narrow AC3's DB revocation to the genuine-denial set, excluding `JWTExpiredException` and `MissingAuthenticationException`. Mirror `maybePublishSecurityAlert`'s `genuineDenial` predicate (`JWTAuthorizationFilter.java:312-319`), and account for `DaoAuthProvider.java:47-51` wrapping `AccountStatusException` into `AuthorizationException`. Cookie-clearing for `rtkn`/`skp` on idle-out is defensible; the per-request DB write is not.
3. **F3** — rewrite Finding 3 and the second user-story paragraph. `sessionManager.js:265` → `GET /refresh`; `POST /api/auth/refresh` has no frontend caller. Re-justify AC2 as defence-in-depth and drop the HIGH label, or re-rate it against the real chain.

**Fix while editing (mechanical, each would otherwise cost dev time):**
4. **F6** — `clearAuthCookies` call sites are **162, 167, 173, 184, 189** (five, not three; `:166` is wrong).
5. **F7** — `insertUser` has **three** call sites (`AuthResourceIT.java:94, 95, 96`), not two.
6. **F4** — delete or correct *"`isLocked()` itself is checked nowhere"*; 13 call sites exist, and three registration services already implement this check shape as precedent worth citing.
7. **F5** — reword Findings 1/3: locking **is** enforced on the live request path within ~5 min via `daoAuthProvider.authorize` (`JWTAuthorizationFilter.java:189, 200`). The real gap is new logins, plus a bounded 5-minute window.
8. **F8** — header scope: four `AuthService` methods plus the constructor, not two.
9. **F9** — remove the stale *"no story-review has been run"* line; one has, and its corrections are already in the file.
10. **F3 citation** — `User.lock()` is `:380-386` (doc sentence at 381), `isRefreshTokenRevoked` is `:214-227`.
11. **F10** — drop the quotation marks around *"all three `skp` writers"*; deferred-142 says *"both `AuthService` sites"*.

**Decide explicitly (not blocking):**
12. **F12** — `SecurityUtil` (52 injection sites, currently DB-free) vs. `AuthService` (already owns `RefreshTokenRepository` and the identical code at `:225-245`) as the home for `terminateSession`. Either is legal under `project-context.md`; the story should state why it chose the wider-blast-radius one.

**Unchanged and sound:** AC1's design and placement (after the password check — no enumeration); the `ensureAccountIsLive` helper shape; reuse of the existing `ApiAdvice` 401 mappings and three-locale i18n copy (all verified present); the no-circular-dependency analysis; the Dev Notes' ordering constraint within `refresh()`; AC4's test-update list (all seven `JWTAuthorizationFilterTest` line numbers and all three `AuthResourceIT` ranges verified exact); and the "don't touch `JwtManagerImpl`/the `/authenticate` pipeline/the `skp` duplication" scope fences.
