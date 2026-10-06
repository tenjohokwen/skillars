# Story Review — skillars-deferred-144

**Story:** `skillars-deferred-144-skp-cookie-consolidation-open-redirect-guard-dedup-and-auth-test-cleanup.md`
**Story status (sprint-status.yaml):** `ready-for-dev`
**Audit date:** 2026-10-06
**Verified against real HEAD:** `a0d39f83` — *"Story Deferred-143: Account-Lock Enforcement and Forced-Logout Session Termination (#250)"*
(obtained from `git rev-parse --short HEAD` / `git log -1` at audit time, **not** taken from the story's own
Source line. The story claims the same SHA, and that claim is correct — but every citation below was
re-opened against the working tree regardless.)

**Method:** four verification passes run directly by the reviewer (citations, ledger/precedent attributions,
mechanistic claims, corner-case/missed-flow hunt), followed by a mandatory adversarial re-check of every
finding. Section 6 lists what the re-check killed — read it, it is the calibration for the rest.

**Scale:** ~50 code citations, 9 ledger/precedent attributions, 18 mechanistic claims, 7 ACs walked against
live code paths. **12 findings survived re-verification; 9 candidate findings were killed by it.**

One claim was verified by *running* code rather than reading it (Finding 1 — Vue Router resolution against
the real route table). That is the highest-confidence item in this report. Several others are high-confidence
reads of live source. Two are judgment calls and are labelled as such. Confidence is stated per finding, not
as a blanket score.

---

## 1. Citation verification (Layer 1)

| # | Story citation | Verdict | Evidence at HEAD `a0d39f83` |
|---|---|---|---|
| 1 | `AuthService.java:122-135` — login skp write | MATCH | `:122` role, `:123-131` the 9-line quoted-id comment, `:132` json, `:133` `URLEncoder.encode`, `:134-135` `addCookie` |
| 2 | `AuthService.java:132-135` — the 4 lines to replace | MATCH | exactly those 4 |
| 3 | `AuthService.java:260-273` — refresh skp write | MATCH | `:260` role, `:261-269` comment, `:270` json, `:271` encode, `:272-273` addCookie |
| 4 | `AuthService.java:270-273` — the 4 lines to replace | MATCH | exactly those 4 |
| 5 | "two `AuthService` sites are byte-for-byte identical, incl. 9-line comment" | MATCH | diffed `:122-135` vs `:260-273`; identical, comment is 9 lines in both |
| 6 | `AuthService.java:148` — not-found `BadCredentialsException` | MATCH | `.orElseThrow(() -> new BadCredentialsException("Invalid refresh token"))` (story paraphrases the message as "refresh token not found"; the line is right) |
| 7 | `AuthService.java:175` / `:173-176` — expiry branch | MATCH | `:173` `isBefore(now)`, `:174` terminateSession, `:175` `"Refresh token has expired"` |
| 8 | `AuthService.java:161-163`, `:166-169` — reuse-detection branches | MATCH | `:162`/`:167` `markAllUsedByUserId`, `:163`/`:168` `terminateSession`, `:164`/`:169` throw |
| 9 | `AuthService.java:239-241` — optimistic-lock comment, quoted verbatim | MATCH | the quoted sentence spans exactly `:239-241` |
| 10 | `AuthService.java:178-209` — self-deadlock comments | MATCH | the deadlock/55P03 narrative occupies that block |
| 11 | `AuthService.java:212-216` — `findById` rejection | MATCH | `:212` findById, `:213` markUsedByTokenHash, `:214` terminateSession, `:215` throw |
| 12 | `JwtManagerImpl.java:103-122` — `setSkillarsProfileCookie` | MATCH | `:103` signature → `:122` close |
| 13 | `JwtManagerImpl.java:95-102` — ANONYMOUS-vs-ADMIN load-bearing comment | MATCH | exactly that passage |
| 14 | `JwtManagerImpl.java:119-121` — lines to replace | MATCH | json / skpValue / addCookie |
| 15 | `JwtManagerImpl.java:182-190` — `deleteLoginToken`, six cookies | MATCH | `:182` `@Override` … `:184-189` the six named constants, in the story's order |
| 16 | "`deleteLoginToken` has exactly two callers" | MATCH | `SecurityUtil.java:188`, `JWTAuthorizationFilter.java:164` (repo-wide grep; `LoginTokenManager.java:72` is the interface decl) |
| 17 | `SecurityUtil.java:187-191` — `clearAuthCookies` | MATCH | `:188` deleteLoginToken, `:189` rtkn, `:190` skp |
| 18 | `SecurityUtil.java:190` — the line to delete | MATCH | `CookieUtil.removeCookie(SKILLARS_PROFILE_COOKIE, response, false, "Lax")` |
| 19 | `SecurityUtil.java:171` — `markUsedByTokenHash` in `terminateSession` | MATCH | guarded by the `:170` non-blank rtkn check |
| 20 | `JWTAuthorizationFilter.java:158-165` — routine-denial branch | MATCH (one nuance) | `:163` also calls `SecurityContextHolder.clearContext()`; the story says the branch "calls `deleteLoginToken` **alone**" — true for cookies, imprecise for the branch |
| 21 | `JWTAuthorizationFilter.java:348-354` — `isGenuineDenial`, 4 clauses | MATCH | verbatim |
| 22 | `JWTAuthorizationFilter.java:351` — `AccountStatusException` disjunct | MATCH | that exact line |
| 23 | `JWTAuthorizationFilter.java:340-346` — predicate javadoc on the rewrap | MATCH | the passage spans `:337-346`; cited range is inside it |
| 24 | `JWTAuthorizationFilter.java:150` — "its one call site" | MATCH (nuance) | `:150` is the `catch (AccountStatusException \| AuthorizationException \| AccessDeniedException e)` that *establishes* the union; the invocation is `:156`. Fine for the claim being made |
| 25 | `JWTAuthorizationFilter.java:378-403` — `SecurityAlertThrottle` | MATCH | class body exactly `:378-403` |
| 26 | "`maybePublishSecurityAlert`'s `genuineDenial`: 3 clauses, no `ACCOUNT_NOT_LOGIN_ABLE`" | MATCH | `:365-367` |
| 27 | `DaoAuthProvider.java:47-51` — `AccountStatusException` → `AuthorizationException(ACCOUNT_NOT_LOGIN_ABLE)` | MATCH | verbatim |
| 28 | `OtpPage.vue:158-162` — inline guard | MATCH | exact |
| 29 | `OtpPage.vue:163` — `router.push(safePath)` | MATCH | exact |
| 30 | `LoginPage.vue:170-174` — inline guard | MATCH | exact |
| 31 | `LoginPage.vue:175` — `router.push(safePath)` | MATCH | exact |
| 32 | "the two guards are byte-identical" | MATCH as annotated | they differ in the fallback argument (`authStore.role` vs `response.role`); the story's own code block annotates this |
| 33 | `VideoManagementPage.vue:108` — `router.replace` | MATCH (precedent mischaracterised) | line is right; it is a 403 access-denied bounce, not a post-auth redirect → **Finding 11** |
| 34 | `routes.js` catch-all → `ErrorNotFound.vue` | MATCH | `routes.js:351-355`, `path: '/:catchAll(.*)*'` → **and this is what breaks AC3, Finding 1** |
| 35 | `roleRoutes.js` header: "failure mode … infinite redirect loop", deferred-92 AC16 | MATCH | `roleRoutes.js:2`, `:6-7` |
| 36 | `quasar.config.js:40` — `vueRouterMode: 'hash'` (story flags as *not* re-verified) | MATCH | `:40` exactly. The hedge was unnecessary — it is correct |
| 37 | `auth.store.js` `hydrateFromCookie` decodes with `decodeURIComponent` | MATCH | `auth.store.js:76` |
| 38 | `AuthResourceIT.java:365-385` — `refresh_expiredToken_returns401` | MATCH | `:365` `@Test` → `:385` close; test is **live** (the preceding block `:321-363` is commented out) |
| 39 | `AuthResourceIT.java:368-372` — bare `jdbcTemplate.update` seed | MATCH | exact |
| 40 | `AuthResourceIT.java:373` — `fakeRaw` | MATCH | exact literal |
| 41 | `AuthResourceIT.java:705-707` — `commitWrite` helper | MATCH | exact |
| 42 | `AuthResourceIT.java:739-747` — `sha256Hex` helper | MATCH | exact |
| 43 | `application.yaml:183` — `auto-commit: false` + grouping rationale | MATCH | `:183`, comment reads "needed to be false in order to group statements in a single txn" |
| 44 | `JwtManagerImplTest.java:483-505` — `testDeleteLoginToken_success` | MATCH | `:488-493` the 6-name list, `:500` `hasSize(loginCookieNames.size())` — so adding the constant auto-bumps to 7, as the story says |
| 45 | `JwtManagerImplTest.java:622-636`, `:641-655` — the two skp payload assertions | MATCH | exact |
| 46 | `SecurityConstants`: `SKILLARS_PROFILE_COOKIE`, `REFRESH_TOKEN_TTL` | MATCH | `:104`, `:105` |
| 47 | `CookieUtil.addCookie(res,name,value,httpOnly,maxAge,sameSite)` / `removeCookie(name,res,httpOnly,sameSite)` | MATCH | `:33` and `:45`; both set `path("/")`, both emit via `res.addHeader("Set-Cookie", …)` — so AC7's attribute assertions are reachable from a plain unit test |
| 48 | `skillars-deferred-143…md` Review Findings "lines 230-237" | MATCH | that range holds **eight** DEFER bullets: six become AC6.1-6.6, two are the story's declared exclusions. Internally consistent |
| 49 | `deferred-work.md` three source section headings | MATCH | `:3471`, `:3572`, `:3649` |
| 50 | `project-context.md`, "Architecture & Module Design (DDD)" | EXISTS, but does not support the use made of it | `:89`; see **Finding 5** |

**No citation in the story was found fabricated, and none was found stale.** The story's drift-correction
claims also check out independently: ledger `AuthService.java:218`→`:270`, ledger `JwtManagerImpl.java:108`→`:119`,
ledger `OtpPage.vue:151-155`→`:158-162`, ledger `SecurityUtil.java:192`→`:190`, 143-review `AuthService.java:216-220`→`:239-241`,
143-review `AuthService.java:194-197`→`:212-216`, 143-review `JWTAuthorizationFilter.java:312-319`→`:378-403`.
Citation hygiene in this story is genuinely good; the defects below are **design and coverage** defects, not
citation defects.

---

## 2. Ledger & precedent attribution (Layer 2)

Each row was checked against **three** sources before any verdict: the ledger file, code comments/javadoc near
the method, and `git log --grep`/surrounding history.

| Claim | Sources checked | Verdict |
|---|---|---|
| Findings 1/3/4 come from `deferred-work.md` "code review of skillars-deferred-142 (2026-10-05)" | ledger `:3471`, items at `:3488-3495`, `:3503-3510`, `:3512-3519`, `:3521-3526`, `:3528-3539` | **TRUE** — all four items present, wording matches |
| Findings 1(umbrella)/2/5 come from "manual review during skillars-deferred-143" | ledger `:3649`, items at `:3655-3693`, `:3695-3739`, `:3741-3758` | **TRUE** |
| Finding 6 comes from "code review of skillars-deferred-143", 8 bullets, 6 closed + 2 excluded | ledger `:3572`; 143 story `:230-237` | **TRUE** |
| "`skillars-deferred-142` AC3 required this exact verbatim copy" | ledger `:3504-3505`; `OtpPageSpec.js:1-2` header | **TRUE** |
| "`roleRoutes.js` exists for precisely this failure shape (deferred-92 AC16)" | `roleRoutes.js:1-12` | **TRUE** — the header states it in those terms |
| "`isGenuineDenial` deliberately excludes the two routine types per deferred-90 AC5/F22 and deferred-143 AC3" | `JWTAuthorizationFilter.java:152-155`, `:320-335`, `:357-362` | **TRUE** |
| "`commitWrite` was added by deferred-143 specifically to fix this class of bug" | `AuthResourceIT.java:697-707` javadoc | **TRUE** |
| "`project-context.md` justifies `platform.security.contract` over `infrastructure.security`" | `project-context.md:89-129`, esp. `:104` and `:115` | **HALF-TRUE** → **Finding 5**. `:104`'s business-agnostic rule does rule out `infrastructure.security`. `:115` defines `contract` as "DTO records, Events, Exceptions" — which does **not** cover a servlet-writing helper. The story cites the document as justifying the placement; it only justifies the exclusion |
| "`VideoManagementPage.vue:108` … as does the rest of the codebase for post-auth navigation" (inherited from ledger `:3523-3524`) | repo-wide grep: 9 `router.replace` vs 105 `router.push`; `PlayerHomeRedirectPage.vue:29/40/46`; `ParentApprovalPage.vue:63` | **OVERSTATED** → **Finding 11**. The story faithfully reproduces the ledger; the ledger itself is wrong |

---

## 3. Mechanistic claims (Layer 3)

Each claim was checked by opening the **full body** of the specific method/field named, and by checking
whether a similarly-named neighbour could be getting confused with it.

| Claim | Verdict | Deciding evidence |
|---|---|---|
| `URLEncoder.encode` is form encoding; `decodeURIComponent` leaves `+` literal; today's payload can't contain a space | **TRUE** | `auth.store.js:76`; payload is `user.getId()` + `SkillarsRole.name()` |
| `.replace("+","%20")` is a correct fix and cannot corrupt a literal `+` | **TRUE** | `URLEncoder` emits `%2B` for a literal `+`, so only space-derived `+` is rewritten. All remaining output chars are valid cookie-octets, so `ResponseCookie.from(name,value)` accepts it |
| `skp` is `httpOnly=false`; `hydrateFromCookie()` runs on page load | **TRUE** | `AuthService.java:135`/`:273` and `JwtManagerImpl.java:121` pass `false`; `router/index.js:42-45` calls it on first navigation |
| `skp` survives a routine 401 on the filter's else branch today | **TRUE** | `JWTAuthorizationFilter.java:164` calls `deleteLoginToken` only; `:184-189` never touches `skp` |
| Not an auth bypass (server authz runs off `@PreAuthorize` + JWT `ROLES`) | **TRUE** | no `main` code reads `skp`; also the same branch already clears `potc`, so the session is dead server-side regardless |
| `rtkn` must stay out of `deleteLoginToken` | **TRUE** | `JWTAuthorizationFilter.java:330-332` states the 7-day-TTL rationale directly |
| Bare `jdbcTemplate.update` in an IT method body never commits (hikari `auto-commit=false`) | **TRUE** | `application.yaml:183`; `AuthResourceIT.java:697-707` records the measured probe |
| `fakeRaw` does not hash to the seeded `token_hash`, so the expiry branch has never run | **TRUE** | `:367` seeds a hardcoded hex literal; `:373`'s raw value is unrelated. Request therefore dies at `AuthService.java:148` |
| After AC5's fix the expiry branch *is* reached | **TRUE** | traced `:141→:146-148→:150 (used=false, skip)→:173 (expired) → :174-175`. No earlier branch intercepts |
| `markAllUsedByUserId` has already revoked the token `terminateSession` would re-mark (AC6.2's premise) | **TRUE** | `ownerId = token.getUserId()` where `token = findByTokenHash(sha256(cookie))` — same row, same user. `markAllUsedByUserId` (`WHERE userId = :userId`) strictly covers `markUsedByTokenHash` |
| AC6.2 does not lose revocation durability despite the branch throwing | **TRUE** | `RefreshTokenRepository.java:30-33` — `markAllUsedByUserId` is `@Transactional(REQUIRES_NEW)`, so it commits independently of the caller's rollback. *(This was the single most dangerous assumption in the story; it holds.)* |
| AC6.2 introduces no self-deadlock | **TRUE** | on every path reaching `:162`/`:167` the outer transaction has issued no write against `refresh_tokens` (`:147`, `:150`, `:156-160` are all reads), so the `REQUIRES_NEW` update takes an uncontended lock |
| `used` is monotonic, so double revocation is idempotent | **TRUE** | `RefreshTokenRepository.java:23-28` documents it; both queries only ever `SET used = true` |
| `isGenuineDenial`'s `AccountStatusException` disjunct is unreachable in production | **TRUE** | `DaoAuthProvider.java:47-51` rewraps before the filter sees it; the four tests that hit it mock `daoAuthProvider` directly |
| "`isGenuineDenial`'s `ACCOUNT_NOT_LOGIN_ABLE` **branch**'s revocation write" | **IMPRECISE** | `isGenuineDenial` is a pure predicate (`:348-354`) and performs no write. The write is `securityUtil.terminateSession` at `:157`, behind a single `if` covering **all four** disjuncts. AC6.6 therefore requires inventing a new cause-discriminating conditional that the story never describes → feeds **Findings 3, 4, 6** |
| `router.resolve(path).matched.length > 0` is the Vue Router 4 idiom for "does this path match a route" | **FALSE against this route table** | see **Finding 1** — empirically disproven |
| `JwtManagerImpl` does not use `StandardCharsets` elsewhere (drop it); `AuthService` still needs it for `sha256Hex` (keep it) | **TRUE** | `JwtManagerImpl`: only `:120`. `AuthService`: `:133`, `:271`, **and `:322`** (`sha256Hex`) |
| `contract` test package already exists; no `__tests__` under `src/router/`; no `LoginPageSpec.js` | **TRUE** | `SecurityPropertiesValidationTest.java` is in the contract test pkg; `src/frontend/src/router/` holds only `index.js`/`roleRoutes.js`/`routes.js`; `pages/auth/__tests__/` has 5 specs, none for LoginPage |

---

## 4. Findings that survived adversarial re-verification

### Finding 1 — BLOCKER: AC3's resolvability check is a no-op in the real app, and all three prescribed tests would false-pass
**Confidence: HIGHEST — verified by executing the installed `vue-router` (4.6.4), not by reading it.**

Design C and the ledger (`deferred-work.md:3518`) both specify:

```js
return router.resolve(path).matched.length > 0
```

`routes.js:351-355` registers a catch-all:

```js
// Catch-all 404
{ path: '/:catchAll(.*)*', component: () => import('pages/ErrorNotFound.vue') },
```

Every `/`-prefixed path matches it, so `matched.length` is never 0. Probe run against the real
`src/frontend/node_modules/vue-router` (4.6.4) with the production route shape:

```
WITH catch-all     resolve(/typo          ) matched.length=1  matchedPaths=["/:catchAll(.*)*"]
WITH catch-all     resolve(/nope/deep/path) matched.length=1  matchedPaths=["/:catchAll(.*)*"]
WITHOUT catch-all  resolve(/typo          ) matched.length=0  matchedPaths=[]
WITHOUT catch-all  resolve(/nope/deep/path) matched.length=0  matchedPaths=[]
```

Consequences:
- **AC3 bullet 3 is unachievable as designed.** `isSafeRedirect('/typo', router)` returns `true`, and the
  just-authenticated user still lands on `ErrorNotFound.vue` — the exact outcome AC3 exists to prevent.
- **The story's own test plan cannot detect this.** AC7 prescribes `safeRedirectSpec.js` "against a small
  `createRouter`/`createMemoryHistory` instance" and page specs using "the mock router's route list". The
  existing mock route list (`OtpPageSpec.js:31-41`) has **no catch-all** — confirmed by reading it — so it
  lands in the `WITHOUT catch-all` column above and the new test goes green while production is broken.
  All of AC3, AC7's three new specs, and the ledger share one unexamined assumption.
- The story's Source line claims citations were "re-verified directly against HEAD … not copied from the
  ledger". This particular mechanism **was** carried over from `deferred-work.md:3518` unexamined — two lines
  after the same ledger bullet cites `routes.js:352-355`, the catch-all that defeats it.

**Fix.** `matched.length` cannot distinguish "real route" from "404 route". Give the catch-all an identity and
test for it — this matches the idiom `router/index.js:47-56` already uses (`to.matched.some(r => r.meta.X)`):

```js
// routes.js
{ path: '/:catchAll(.*)*', component: () => import('pages/ErrorNotFound.vue'), meta: { notFound: true } },

// safeRedirect.js
const resolved = router.resolve(path)
return resolved.matched.length > 0 && !resolved.matched.some((r) => r.meta.notFound)
```

`routes.js` must then be added to AC3's scope and to the story's File List (it is currently absent), and
`safeRedirectSpec.js` must assert against the **real** `routes` array (`import routes from '../routes'`),
not an ad-hoc list — otherwise the test still cannot catch this class of defect.

---

### Finding 2 — AC2/Task 3 breaks a live test, and contradicts the ledger's own recommendation
**Confidence: HIGH — read directly from the test file.**

AC2 bullet 2 / Task 3 delete `SecurityUtil.java:190`
(`CookieUtil.removeCookie(SKILLARS_PROFILE_COOKIE, response, false, "Lax")`) as "now-redundant … covered
transitively via `deleteLoginToken`". That is true **in production** and false **under the existing unit test**:

- `SecurityUtilTest.java:45-46` — `@Mock private LoginTokenManager loginTokenManager;`
- `SecurityUtilTest.java:58` — `securityUtil = new SecurityUtil(loginTokenManager, refreshTokenRepository);`
- `SecurityUtilTest.java:89` — `verify(loginTokenManager).deleteLoginToken(response);` (mock records, emits nothing)
- `SecurityUtilTest.java:96-99` — asserts a **real** `Set-Cookie` header on the `MockHttpServletResponse`:
  ```java
  assertThat(setCookies)
          .as("skp must be expired")
          .anyMatch(c -> c.startsWith(SecurityConstants.SKILLARS_PROFILE_COOKIE + "=")
                  && c.contains("Max-Age=0"));
  ```

With `:190` deleted, nothing emits that header in this test → **`terminateSession_clearsRefreshTokenAndProfileCookies` fails.**
Also invalidated: the `@DisplayName` at `:81` and the comment at `:87-88`.

The story does not anticipate this: `SecurityUtilTest.java` is **absent from "Changed backend files"**
(Project Structure Notes), Task 4 updates only `JwtManagerImplTest`, and AC7 lists `SecurityUtilTest` under
*"Zero regressions."*

The ledger explicitly recommended the opposite, twice:
- `deferred-work.md:3689-3690` — *"Both callers tolerate it; `SecurityUtil.clearAuthCookies` **would emit a harmless duplicate removal header**."*
- `deferred-work.md:3729` — *"`SecurityUtil.clearAuthCookies` **delegating**"* (i.e. call `SkillarsProfileCookie.removeFrom(res)`, keep the call).

Either option resolves it; the story should pick one explicitly rather than inherit a test break:
- **(a)** Keep the removal in `clearAuthCookies`, switched to `SkillarsProfileCookie.removeFrom(response)` —
  the ledger's design. Zero test churn, and `SecurityUtil`'s own contract stays independent of which
  `LoginTokenManager` implementation is wired in. Cost: one duplicate `Set-Cookie: skp=; Max-Age=0` on the
  `clearAuthCookies` path, which is idempotent.
- **(b)** Delete it as the story says, and add updating `SecurityUtilTest` (test, `@DisplayName`, comment) to
  AC2/Task 3/the File List.

I lean (a): it is what the ledger designed, it keeps `clearAuthCookies`'s documented postcondition literally
true, and it removes a hidden dependency of a unit test on a collaborator's internals.

---

### Finding 3 — AC6.6's throttled path is the only route through the filter's catch block that never clears the SecurityContext
**Confidence: HIGH on the invariant; MEDIUM on blast radius (stated below). This is the claim I most actively tried to refute.**

AC6.6: *"when throttled, call `securityUtil.clearAuthCookies(res)` instead of `securityUtil.terminateSession(req, res)` (cookies still clear every time; the DB write is what gets bounded)."*

`terminateSession` does **three** things (`SecurityUtil.java:167-174`), not the one the story's Finding 6.2
describes:

```java
public void terminateSession(final HttpServletRequest request, final HttpServletResponse response) {
    SecurityContextHolder.clearContext();                       // :168
    final String rawToken = CookieUtil.getCookieValue(request, REFRESH_TOKEN_COOKIE);
    if (rawToken != null && !rawToken.isBlank()) {
        refreshTokenRepository.markUsedByTokenHash(sha256Hex(rawToken));   // :171
    }
    clearAuthCookies(response);                                 // :173
}
```

`clearAuthCookies` does **not** clear the context. And on this path the context **is** populated — I traced
the ordering rather than assuming it:

- `JWTAuthorizationFilter.java:193` — `SecurityContextHolder.getContext().setAuthentication(authentication);`
- `JWTAuthorizationFilter.java:202` / `:213` — `daoAuthProvider.authorize(...)`, **after** `:193`, and this is
  the call that raises `AuthorizationException(ACCOUNT_NOT_LOGIN_ABLE)` for a locked/deactivated account
  (`DaoAuthProvider.java:47-51`).

So a real authenticated token sits in the holder when the throttled branch writes its 401. `clearContext()`
appears exactly **once** in the whole filter — `:163`, the routine-denial branch — and its comment states the
requirement explicitly:

> `// ... attemptAuthorization set an authenticated context at the top, so it must still be cleared before the 401 is written.` (`:161-162`)

The genuine-denial branch satisfies the same requirement *only* via `terminateSession`. AC6.6 removes that
without replacing it. `writeUnauthorized` (`:289-…`) does not clear it either.

**Blast radius, honestly bounded:** the filter `return`s without `chain.doFilter`, so no downstream handler
runs in that request, and `SecurityConfiguration.java:200` registers this filter *inside* the Spring Security
chain, so `SecurityContextHolderFilter` clears the holder in its own `finally`. I could not demonstrate a
cross-request leak. The defect is an invariant violation on a security path — one that `skillars-deferred-143`'s
review already litigated and added assertions for — not a proven exploit.

**Fix:** add `SecurityContextHolder.clearContext();` to the throttled path, or prefer the alternative in
Finding 4 which removes the branch entirely.

---

### Finding 4 — AC6.6 repurposes an audit-volume control as a security control, against that class's own javadoc, with a key that collides for the exact adversary it names
**Confidence: HIGH on the mechanism; MEDIUM on severity.**

`SecurityAlertThrottle`'s own javadoc (`JWTAuthorizationFilter.java:373-376`):

> *"Bounded, self-evicting, and deliberately tiny — **this is volume control, not a security decision**: every denial is still logged by `mintHelpCode`, only the audit-trail row is collapsed."*

AC6.6 makes it a security decision: it gates whether a since-locked account's refresh token gets revoked.
The key (`:387-390`) is `causeClassName + "|" + client`, where `client` resolves through
`RequestMetadata.getClientIdentifier()` (`RequestMetadata.java:138-144`): `apiKey` → `browserCookie` (`bcookie`)
→ `fingerprintCookie` → else `getIpAddress()` → else `"unknown"`.

Two concrete collisions:

1. **The named adversary defeats the key it is keyed on.** AC6.6's scenario is *"a client that ignores
   `Set-Cookie` and keeps replaying the same stale JWT"*. Such a client has no `bcookie`/`fcookie`, so
   `client` degrades to the IP. The project already documents this hazard in its own code —
   `LoginAttemptsService.java:205`: *"The getClientIdentifier here may not exist e.g for a browser (or else
   many users will share the same key)."*
2. **Shared egress IP merges distinct users.** `AuthorizationException` is the class for *every*
   `SecurityError`, so the key is effectively `AuthorizationException|<ip>`. Two different locked accounts
   behind one NAT/corporate IP within a 60 s window land in one bucket: the first is revoked, the second
   is **not**. That is a security regression relative to today's unconditional revocation.
   (`ipAddress` is also taken from an unvalidated `Forwarded` header first — `RequestMetadataProvider.java:49-51`
   — so the key is client-influenceable, though only in the permissive direction for the attacker's own row.)

**Recommended alternative — bound the write instead of gating the teardown.** `used` is a monotonic terminal
flag (`RefreshTokenRepository.java:23-28`), so narrowing the predicate is semantically equivalent and makes
repeat revocations cost zero row writes with no new state, no repurposed security control, and no
clearContext hole (Finding 3 disappears too):

```java
@Query("UPDATE RefreshToken r SET r.used = true, r.version = r.version + 1 "
     + "WHERE r.tokenHash = :tokenHash AND r.used = false")
```

Caveat to weigh before adopting: this also skips the `version` bump on an already-revoked row, and the
existing javadoc (`:26-28`) leans on that bump to fail concurrent stale writes touching *other* columns
(`rotatedAt`, `expiresAt`). Since `used` can never move back to `false`, the residual risk is cosmetic — but
it is a real delta and should be a recorded decision, not a silent one.

If the throttle is kept regardless, AC6.6 must additionally specify (a) the new cause-discriminating
conditional at `:156-157` (see §3 — `isGenuineDenial` has no "`ACCOUNT_NOT_LOGIN_ABLE` branch" to hang a
throttle on; one has to be created), and (b) the clearContext fix from Finding 3.

---

### Finding 5 — `platform.security.contract` contradicts the project's own definition of a `contract` package
**Confidence: MEDIUM — this is a design judgment call, flagged as such.**

`project-context.md:115` defines the layer the story is placing this type in:

| **Contract** | `contract` | Public API of the module: **DTO records, Events, Exceptions.** |

`SkillarsProfileCookie` is none of those. It is a cookie writer with servlet side effects — its entire
surface is `writeTo(HttpServletResponse)` and `static removeFrom(HttpServletResponse)`, and it imports
`jakarta.servlet.http.HttpServletResponse` plus `infrastructure.security.CookieUtil`. Verified by grep: **no
class in the top-level `platform/security/contract/` package imports `jakarta.servlet` or
`infrastructure.security` today** (only the `contract/exception/` subpackage does, for `SecurityError`). The
27 files there are DTOs, enums, records, and `@ConfigurationProperties`.

The story's References entry claims `project-context.md` "Architecture & Module Design (DDD)" provides "the
layering justification for placing `SkillarsProfileCookie` in `platform.security.contract`, not
`infrastructure.security`". What that document actually supports is only the **negative** half: `:104`'s
business-agnostic rule correctly rules out `infrastructure.security` (the type knows `SkillarsRole`). It
offers nothing for `contract`, and `:115` argues against it. The ledger's reasoning (`deferred-work.md:3719-3723`)
is the same shape — "`SkillarsRole` already lives in that package" — which establishes that the *import* is
legal, not that the *responsibility* fits.

Better-fitting home that already exists and already does exactly this work:
**`com.softropic.skillars.platform.security.infrastructure`** — the module-local infrastructure package that
holds `SecuredHttpEndpointGuard`, `filter/`, and `jwt/JwtManagerImpl` (which is one of the three current `skp`
writers). The module already uses the pattern "contract/interface in `service`, servlet implementation in
`infrastructure`" (`service/LoginTokenManager` ↔ `infrastructure/jwt/JwtManagerImpl`), and
`platform.security.service.AuthService` → `platform.security.infrastructure.*` is an existing, legal direction.

Not a correctness defect — the code compiles and works either way. But the story presents the placement as
settled *by* `project-context.md`, and it is not. Record it as a decision with its real reasoning, or move it.

---

### Finding 6 — the only two functional changes in AC6 ship with no test, and AC6.6 ships stateful security behaviour untested
**Confidence: HIGH — read from AC7 and the test tree.**

AC6 breaks down as: 6.1 comment-only, 6.3 comment-only, 6.4 comment-only, 6.5 type-narrowing (compile-only),
and **6.2 + 6.6 functional**. AC7's testing list covers AC1 (`SkillarsProfileCookieTest`), AC2
(`JwtManagerImplTest.testDeleteLoginToken_success`, with a mutation check), AC3/AC4 (three frontend specs),
AC5 (`AuthResourceIT`) — and **nothing for AC6.2 or AC6.6**. They appear only inside "re-run the suite, zero
regressions", which by construction cannot cover behaviour that does not exist yet.

AC6.6 is the one that matters: it adds a 60-second, per-client, mutable gate in front of a security-relevant
DB write, plus (per Findings 3 and 4) a new conditional in the filter's catch block. Shipping that with no
test of either the allowed or the throttled path is out of step with the rest of this story, which is
otherwise careful about coverage (AC7 even demands a mutation check for AC2's one-liner).

Note for whoever writes it: a two-request throttle test is viable because the filter is reconstructed per
test (`JWTAuthorizationFilterTest.java:116-133`), so each test starts with a fresh throttle map — but that
also means the throttled path can **only** be observed by issuing two denials inside one test method.
Existing `verify(securityUtil).terminateSession(request, response)` assertions
(`JWTAuthorizationFilterTest.java:269, 297, 321, 345, 426`) are unaffected, since each is the first denial in
its own test.

---

### Finding 7 — AC5 bullet 2 and Task 8 both state the fix backwards (hash inversion)
**Confidence: HIGH.**

> AC5: *"The raw cookie value sent (`fakeRaw`, `:373`) is replaced with a raw value whose `sha256Hex(...)` … **equals the seeded `token_hash`**"*
> Task 8: *"compute a raw token whose hash matches the seeded `token_hash`"*

`AuthResourceIT.java:367` seeds a hardcoded hex literal:

```java
String expiredHash = "deadbeef01234567890123456789012345678901234567890123456789012345";
```

That is not the SHA-256 of any known input, so finding a raw value that hashes to it is a preimage attack.
The direction must be reversed: pick the raw value, then **derive** the seed with the class's own helper
(`:739-747`):

```java
String rawToken = "expired-refresh-token-raw-value";
String expiredHash = sha256Hex(rawToken);
commitWrite("INSERT INTO main.refresh_tokens (id, user_id, token_hash, expires_at, used) "
          + "VALUES (990001, ?, ?, ?, false)",
            COACH_USER_ID, expiredHash, Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)));
// ... cookieHeaders(rawToken)
```

A competent implementer will reach this anyway; it is logged because the AC as written is literally
impossible, and the story's text is the spec of record.

Two related notes on AC5 while here:
- AC5's third bullet correctly refuses to let the dev claim the branch distinction is tested when it isn't.
  It *is* observably distinguishable: both branches throw `BadCredentialsException`, so the HTTP status and
  `errorKey` are identical — but `usedFlagOf(expiredHash)` (the class's existing helper, `:710-713`) returns
  `true` only on the expiry branch, because `:174`'s `terminateSession` revokes in `REQUIRES_NEW` and
  therefore commits. That is a real assertion, available at no extra cost.
- Fixing the seed to actually commit turns a never-persisted row into live shared state for the rest of the
  class. `tearDown` (`:139-152`) does `DELETE FROM main.refresh_tokens` per test, so cross-test leakage is
  covered — worth confirming rather than assuming, since the hardcoded `id = 990001` would otherwise collide.

---

### Finding 8 — two explicit ledger companion-asks are dropped, contrary to the story's "re-confirmed not silently dropped" framing
**Confidence: HIGH on (a) and (b) being absent; deliberately no claim made about whether (b) would find anything.**

The story's exclusions list (Finding 6) names exactly two items, both from the deferred-143 code review. These
two, both from the ledger items this story *is* implementing, appear nowhere — not implemented, not excluded:

**(a) `deferred-work.md:3691-3692`** — *"Note this **is** a behaviour change on the routine denial path, which
`skillars-deferred-143` AC3 deliberately froze, so it needs its own AC **and a test asserting an expired-JWT
401 now expires `skp`**."*
The story gives it its own AC (AC2 — good) but **not** the test. AC7's AC2 regression test is
`JwtManagerImplTest.testDeleteLoginToken_success`, a unit test of `deleteLoginToken` in isolation; it proves
the cookie list changed, not that the filter's expired-JWT 401 now expires `skp`. The behaviour change lands
on a path a previous story deliberately froze, and gets no test at the level where it changes. A filter-level
test or a `SecurityIT`/`AuthResourceIT` assertion on the 401's `Set-Cookie` headers would close it.

**(b) `deferred-work.md:3757-3758`** — *"Worth a wider grep at the same time: any other IT seeding state with a
bare `jdbcTemplate` write in a test method body has the same silent no-op."*
Absent from every AC and task. I ran a coarse pass over ~160 IT classes and deliberately make **no claim**
that another is broken — most bare writes sit inside a class-level or `setUp` transaction where they are
visible to the test, and distinguishing those needs per-file reading. The point is procedural: the story
asserts nothing was silently dropped, and this was. Either scope it (even as "grep only, report, fix nothing")
or list it as excluded with a reason.

This is a known recurring class in this repo, which is why the ledger asked.

---

### Finding 9 — AC2 invalidates five comment/javadoc passages, none covered by any AC
**Confidence: HIGH.**

The story spends two ACs (6.3, 6.4) on comment accuracy, so the standard is explicit. AC2 then falsifies these
and requires no update:

1. `JWTAuthorizationFilter.java:159-162` — *"No DB read or write, and **no rtkn/skp clearing**"*. After AC2 this
   branch **does** clear `skp`. Directly false, on the branch AC2 changes.
2. `JWTAuthorizationFilter.java:152-155` — *"Routine, expected traffic keeps **exactly its previous behaviour**"*.
   It no longer does.
3. `SecurityUtil.java:177-179` — *"drops **the six cookies** `deleteLoginToken` owns plus `rtkn` and `skp`"*.
   After AC2 `deleteLoginToken` owns seven, and (under the story's variant) `clearAuthCookies` no longer drops
   `skp` itself.
4. `SecurityUtil.java:181-185` — *"Exists for **the one caller** that must not revoke … **Prefer `terminateSession`
   everywhere else**; this is not a general-purpose 'log out'."* AC6.2 adds two callers and AC6.6 a third, each
   for a different reason than the documented one. This javadoc becomes actively misleading.
5. `SecurityUtilTest.java:81` `@DisplayName` and `:87-88` comment (see Finding 2).

Cheap to fix, but they are exactly the drift AC6.3/AC6.4 exist to prevent, created by this same story.

---

### Finding 10 — AC2's required disclosure under-enumerates the affected causes
**Confidence: HIGH on the enumeration; deliberately downgraded on impact — see below.**

AC2 bullet 3 discloses the change as affecting *"`JWTExpiredException`/`MissingAuthenticationException`"*. The
else branch at `:158-165` actually receives **everything** caught at `:150` minus the four genuine-denial
shapes — i.e. also:

- `AuthorizationException` with any error code other than `ACCOUNT_NOT_LOGIN_ABLE`: `MISSING_RIGHTS`
  (an authenticated user hitting a route their role does not permit — the filter calls this out at `:333-335`),
  `USER_NOT_FOUND`, `UNKNOWN`, `JWT_PARSE_ERROR` (`DaoAuthProvider.java:38-55`);
- `AccessDeniedException`.

The ledger itself named this set — `deferred-work.md:3676` lists *"(expired JWT, tokenless request,
`AuthorizationException(MISSING_RIGHTS)`)"*. The story narrowed it.

**I am explicitly not calling this a regression.** I initially flagged it as one and the re-check knocked it
down: `MISSING_RIGHTS` reaches the same branch that already clears `potc` via `deleteLoginToken`, so the
session is dead server-side regardless, and `isAuthenticated` is `!!userId` (`auth.store.js:15`) fed only from
`skp` — so clearing `skp` there makes the SPA's state *more* truthful, which is AC2's whole point. The defect
is in the disclosure AC2 itself demands ("call it out explicitly in the Dev Agent Record, don't let it read as
a silent side effect"), being made from an incomplete list.

---

### Finding 11 — Finding 4's precedent attribution is inaccurate (the change is still right)
**Confidence: HIGH.**

> *"Every other terminal post-auth redirect in this codebase (e.g. `VideoManagementPage.vue:108`) uses `router.replace`."*

`VideoManagementPage.vue:101-111` is a **403 access-denied bounce** inside `fetchVideos()`'s catch, not a
post-auth redirect. Repo-wide: 9 `router.replace` vs 105 `router.push`. The other `replace` sites are
`PlayerHomeRedirectPage.vue:29/40/46` (a dedicated one-shot redirect page), `ParentApprovalPage.vue:63`
(another error bounce), and `MarketplacePage.vue:201-217` (query-only replaces). The only two *actual*
terminal post-auth redirects are `LoginPage.vue:175` and `OtpPage.vue:163` — **both `push`**, and the story
deliberately leaves the first alone.

The story reproduces the ledger faithfully (`deferred-work.md:3523-3524` says the same); the ledger is the one
that overstates. AC4 is still the right change for the right reason (a consumed `loginInfoId` makes `/otp`
genuinely non-returnable), and `PlayerHomeRedirectPage.vue` is a real precedent for exactly that shape — it
just isn't the one cited. Fix the sentence so the next reader doesn't inherit a false "the rest of the
codebase already does this".

---

### Finding 12 — Finding 5's "every other write goes through `commitWrite`" is inaccurate
**Confidence: HIGH. No impact on the fix.**

> *"Every other write in this same class already goes through the file's own `commitWrite(String sql, Object... args)` helper (`:705-707`)"*

`insertUser` (`AuthResourceIT.java:753-756`) uses a bare `jdbcTemplate.update`, and so do `setUp`'s authority
and `user_authority` inserts (`:97, :102, :117, :123, :129`). They are correct because their caller wraps them
in `transactionTemplate` (`:96-136`) — which is what `commitWrite`'s own javadoc says (`:702-703`: *"Every write
in this class therefore goes through `transactionTemplate`, as `setUp()` already does"*). The accurate claim is
"every write is committed, via `transactionTemplate` or `commitWrite`; this one test method is the only live
exception" — which, having checked every live `jdbcTemplate.update` in the file, is true.

---

## 5. What did NOT survive re-verification

Listed so the findings above can be trusted. Each of these looked real on first pass; the cited evidence
killed it.

1. **"AC6.2 destroys revocation durability."** Reasoning was: the branch throws, the outer `@Transactional`
   rolls back, and removing `terminateSession` removes the only `REQUIRES_NEW` write. **Wrong** —
   `RefreshTokenRepository.java:30-33` shows `markAllUsedByUserId` is itself
   `@Transactional(propagation = REQUIRES_NEW)`, so it commits independently. AC6.2 is durable. I checked the
   annotation rather than inferring from the method name, because this is the exact shape that produced a
   BLOCKER in the deferred-143 review.
2. **"AC6.2 loses `SecurityContextHolder.clearContext()` and that matters."** Downgraded to the Finding 9
   accuracy note. `/api/auth/refresh` is in `AppEndpoints.ALL_UNRESTRICTED` (`AppEndpoints.java:44`), so the
   filter installs only an *anonymous* token (`JWTAuthorizationFilter.java:178-182`) — nothing authenticated is
   left behind. And `AuthService.java:242`'s optimistic-lock branch already calls `clearAuthCookies` with the
   identical delta, reviewed and accepted. **Note this is why Finding 3 is a separate finding and not the same
   one:** on the *filter* path the context genuinely is authenticated (`:193` precedes the throwing
   `authorize()` at `:202`/`:213`), so the same swap has a different consequence there.
3. **"AC6.2 misses a token `markAllUsedByUserId` doesn't cover."** No — `ownerId` comes from the very row the
   cookie hashes to, so the per-user bulk update strictly covers the per-hash one.
4. **"AC6.2 risks the 55P03 self-deadlock."** No — traced `:141`→`:168`; every statement before the
   `REQUIRES_NEW` write on that path is a read, so the lock is uncontended.
5. **"`AuthResourceIT.java:343`'s bare `jdbcTemplate.update` is a second instance of the Finding 5 bug."**
   Looked compelling (its own comment says it must exhaust the successor token). **Dead code** — `/*` opens at
   `:321` and `*/` closes at `:363`; the whole test is commented out. Would have been a false positive.
6. **"`String.valueOf(claims.get(BUS_ID))` turns a null `BUS_ID` into literal `"null"`."** Identical to
   today's string concatenation at `JwtManagerImpl.java:119`, so it is not a change. Also already adjudicated
   a false positive in the deferred-142 review (sprint-status note) — not re-litigated.
7. **"`.replace("+", "%20")` corrupts a literal `+` in the payload."** No — `URLEncoder` emits `%2B` for a
   literal `+`, so only space-derived `+` characters are rewritten. The fix is correct as written.
8. **"The new encoding breaks `JwtManagerImplTest:622-636`/`:641-655` (they use `URLDecoder`)."** No — the
   payload (`{"id":"<long>","role":"<ENUM>"}`) contains no space, so output bytes are unchanged and both still
   pass. The story's "those stay as-is" is right. (Side note, not a defect: those tests and
   `AuthResourceIT:185`/`:310` decode with `URLDecoder` — the *form* decoder — so none of them could ever
   detect the mismatch AC1 fixes. AC7's new `SkillarsProfileCookieTest` is the only thing that will.)
9. **"AC6.6's new throttle will cross-contaminate `JWTAuthorizationFilterTest`."** No — the filter is rebuilt
   in `@BeforeEach` (`:116-133`), so every test gets a fresh throttle map. This is also how the existing
   `alertThrottle` tests (`:478-498`) pass.

Two more checked and cleared: `SkillarsProfileCookieTest` will not NPE on `RequestMetadataProvider`
(`getClientInfo()` lazily creates, `:28-33`; `isHttps()` defaults false), and
`JwtManagerImplTest:501-503`'s blanket per-cookie assertions (`value` blank, `maxAge` zero) are satisfied by
the new 7th cookie, since `CookieUtil.removeCookie` builds `ResponseCookie.from(name)` with no value.

---

## 6. Recommendation

**Do not start implementation on AC3 or AC6.6 as written.** Everything else is implementable; several items
need a sentence corrected or a decision recorded first.

**Must fix before dev (design is wrong, not just imprecise):**
- **Finding 1** — AC3's `matched.length > 0` cannot work against `routes.js:353`'s catch-all, and the three
  prescribed tests would all false-pass. Needs a different predicate, `routes.js` added to scope/File List, and
  `safeRedirectSpec.js` pointed at the real route table. *Highest confidence in this report — proven by execution.*
- **Finding 2** — AC2/Task 3 breaks `SecurityUtilTest:96-99`. Pick the ledger's delegate-don't-delete option, or
  add the test update to the AC, Task 3, and the File List.
- **Findings 3 + 4** — AC6.6 needs either the `clearContext` fix plus the missing cause-discriminating
  conditional, or (preferred) replacement by the `AND r.used = false` predicate, which deletes the whole
  problem class. Record whichever as a decision.

**Should fix before dev (spec says something false or impossible):**
- **Finding 7** — AC5 bullet 2 / Task 8 state hash inversion; reverse the direction and consider the
  `usedFlagOf(...)` assertion.
- **Finding 6** — give AC6.2 and AC6.6 a test each.
- **Finding 8** — implement or explicitly exclude the two dropped ledger companion-asks.

**Fix while in the files (accuracy, cheap):**
- **Finding 9** (five stale passages AC2 creates), **Finding 10** (AC2's disclosure list),
  **Finding 11** (AC4's precedent sentence), **Finding 12** (Finding 5's "every other write").

**Judgment call, record a decision either way:**
- **Finding 5** — `platform.security.contract` vs `platform.security.infrastructure` for `SkillarsProfileCookie`.
  Not a correctness issue; the story just shouldn't present `project-context.md` as having settled it.

**What was independently re-checked, and what wasn't.** Every file:line in §1 was opened at HEAD `a0d39f83`;
every mechanistic claim in §3 was checked against the full body of the specific method named, including
transaction annotations and the `setAuthentication`-before-`authorize()` ordering that Findings 3 and 10 turn
on; Finding 1 was verified by running the installed `vue-router` 4.6.4 against the production route shape.
Ledger and precedent claims were each checked against the ledger file, nearby code comments, and the
referencing story before a verdict. **Not** independently verified: whether any IT *other* than
`AuthResourceIT` actually has the bare-write bug (Finding 8b is scoped as a procedural gap, deliberately
making no such claim), and the runtime behaviour of hash-history `resolve()` (the probe ran under
`createMemoryHistory`, since `createWebHashHistory` needs a DOM — route matching is history-independent, but
that is reasoning, not measurement).

The story's citation discipline is the best part of it: nothing fabricated, nothing stale, and its
drift-correction claims (seven of them) all check out independently. The defects cluster in **design
mechanisms adopted without being tested against the real system** (Finding 1), **test-double behaviour
assumed to match production** (Finding 2), and **a teardown method characterised by one of its three effects**
(Findings 3 and 4).
