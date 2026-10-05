# Story Review — skillars-deferred-142

**Story:** `skillars-deferred-142-otp-skp-cookie-gap-video-redirect-and-stale-env-ops-note`
**Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-142-otp-skp-cookie-gap-video-redirect-and-stale-env-ops-note.md`
**Status at audit:** `ready-for-dev` (`sprint-status.yaml:325`) — not yet implemented
**Audit date:** 2026-10-04
**Real HEAD:** `9abe7058 mto-code-review: replace exhortation with mechanical verification (#248)`
**Branch:** `story/deferred-142-otp-skp-cookie-and-redirect-fixes`

> Every citation below was verified against **HEAD `9abe7058` as it actually stands right now**, not
> against any commit, line count, or "verified against HEAD, 2026-10-04" claim the story makes about
> itself. The story file is **untracked** (`git log` on its path returns nothing) and
> `sprint-status.yaml` is uncommitted — nothing in this story has been through CI or review.

**Verification method:** 53 checkable claims extracted into four buckets and verified by four parallel
read-only layers, followed by a mandatory adversarial re-verification pass in which every surviving
finding's evidence was re-read fresh with intent to refute it. That pass **killed four claims** the
layers raised (§6) — including three citation-drift claims where two layers contradicted each other and
the story turned out to be right.

---

## 1. Citation verification (Layer 1)

| Citation | Verdict | Evidence / corrected location |
|---|---|---|
| `secrets-reference.md:398-429` "Accepted credential-exposure surface" | **DRIFTED** | Heading at `:398`; section ends `:428`. **File is 428 lines — `:429` does not exist.** Corrected: `:398-428` |
| `AuthService.authenticate()`, `:90-131` | **DRIFTED (material)** | **No `authenticate()` method exists.** Real: `public LoginResponse login(String email, String rawPassword, String clientIp, HttpServletResponse res)` at `:61-137`; skp block `:121`+`:131-134`. See §5-F9 |
| `AuthService.refresh()`, `:133-220` | **DRIFTED** | Real `:139-223`; skp block `:207`+`:217-220`. Cited `:133` lands mid-`login()` |
| 4-line skp snippet attributed to both paths | **MATCH (text)** | Verbatim in both; but not contiguous — a 10-line comment separates `:121` from `:131`, and `addCookie` wraps two lines |
| `SecondFactorLoginFilter.java:67-76` → `refreshLoginToken(response, loginData.getToken())` | MATCH | Call at `:75` |
| `JwtManagerImpl.refreshLoginToken()` `:68-83` | **MATCH (exact)** | Signature `:68`, closing brace `:83`. (A layer claimed `:67-83`; refuted — `:67` is `@Override`) |
| `createAndSetJwt` `:203-211` | MATCH | Exact |
| `createLoginCookies` `:213-245` | **DRIFTED** | Real `:213-249`; cited end cuts the `SESSION_REFRESH_COUNTDOWN` write (`:244-248`) |
| `TokenCreatorImpl.toClaims(...)` `:46-66` | MATCH | Exact |
| `Principal.java:21` carries `skillarsRole` | **DRIFTED** | `:21` is `private final String phone;`. Field is `:23`; getter `:178-180`; `instanceFrom` sets it `:157` |
| `JwtManagerImpl.java:72` `new HashMap<>(extractedClaims)` | MATCH | Exact |
| `SecurityConstants.java:105` `REFRESH_TOKEN_TTL = Duration.ofDays(7)` | MATCH | Exact |
| `JwtManagerImpl.java:26` static-imports `SecurityConstants.*` | **DRIFTED** | Import is `:27` (`:26` blank). Substantive claim holds |
| `CookieUtil.java:17-24` "4-arg overload" defaults `Lax` | **DRIFTED (label)** | Body `:17-26`; `.sameSite("Lax")` at `:23`. Signature takes **5** params, not 4; the other takes 6. Behavioral claim correct |
| `SecurityIT.loginWith2FAWhenAccountEnabled()` `:186-286` | **DRIFTED** | `@Test` `:187`, signature `:189`, closes `:287` |
| …asserts `JWT_COOKIE_NAME` at `:284-285` | **DRIFTED** | Real `:285-286` (`:284` is the status assert) |
| `JwtManagerImplTest.createPrincipal(...)` `:169-182` | MATCH | Exact; no `.skillarsRole(...)` in the builder chain — story's derived claim correct |
| `routes.js:24-28` `/otp` `requiresGuest` | **MATCH (exact)** | Route object `:24-28`, `path: 'otp'` at `:25`. (A layer claimed `:22-26`; refuted) |
| `OtpPage.vue:96` `redirectPath` computed | **MATCH (exact)** | Verbatim; read once at `:148` |
| `LoginPage.vue:168-174` + snippet | **DRIFTED (minor)** | Snippet verbatim but spans `:168-175` (`router.push(safePath)` at `:175`) |
| "mirrors `LoginPage.vue:174`" | MATCH | `routeForRole(response.role)` at `:174`; import at `:133` as `'src/router/roleRoutes'` (no `.js`) |
| `SecondFactorLoginFilter.successResponse()` `:88-96` | **DRIFTED** | Real `:96-105`; cited range is a *different block* (the `LOGIN_ID_MISMATCH` throw). Substantive claim holds (`:97`, no role) |
| `VideoManagementPage.vue:101-115`, `router.replace('/dashboard')` `:108` | **MATCH (exact)** | `fetchVideos()` `:101-115`; 403 branch `:106`; `:108` exact |
| `routes.js:255-259` `player/videos` `role: 'PLAYER'` | **DRIFTED (minor)** | Object `:255-260`; `path` `:256`, `meta` `:259`. The ledger's own citation says `:255-260` |
| `routes.js:262-267` `/player/dashboard` | **DRIFTED (minor)** | Object `:261-268`; `path` `:264`. Cited range is the comment-to-`meta` interior |
| `docs/dev-docs/notification/index.html:373-388` | MATCH | Exactly one complete `<div class="callout">` block |
| "`loginData`, an opaque `LoginInfo` projection — see `LoginData.java`" | **MATCH (types conflated)** | In-scope type is `LoginData` (interface projection, `contract/LoginData.java`); `LoginInfo` is the separate JPA entity (`repo/LoginInfo.java`). **Neither has a role field**, so the load-bearing conclusion holds |
| `src/frontend/src/pages/player/` has `/player/dashboard` page | MATCH | `pages/player/PlayerDashboardPage.vue` exists |
| "no existing spec file for this page" | MATCH | No OtpPage spec anywhere; proposed path matches the repo's `<dir>/__tests__/<Name>Spec.js` convention and the configured vitest glob |
| `SecurityIT` path | MATCH | `src/test/java/com/softropic/skillars/platform/security/SecurityIT.java` (372 lines) |

**Pattern:** a consistent off-by-one-to-two cluster across six citations, plus one method that does not
exist. None of the range drift changes behavior; the `authenticate()` error does change what a dev finds.

---

## 2. Ledger & precedent attribution (Layer 2)

| Claim | Sources checked | Verdict |
|---|---|---|
| Bundle precedent `-100`/`-102`/`-124` | (a) ledger (b) story files (c) — | **CONFIRMED** — all three exist and are genuine multi-AC bundles (14/19/9 ACs) |
| AC1/AC3/AC4 trace to real ledger bullets | (a) `:1067` (HCLOUD ops note), `:3607` (OtpPage), `:3621` (VideoManagementPage) — all untagged/open (b) source matches verbatim (c) section added by `c8588a32` | **CONFIRMED** — no AC falsely attributed; AC2's backend half correctly disclosed as newly discovered |
| Sourcing bullet was frontend-only | (a) quoted verbatim `:3607-3619` (b) `OtpPage.vue:96` matches (c) — | **CONFIRMED** — frontend-only, and it names neither AC1 nor AC4 |
| `skillars-uat-6` AC8 (2026-08-13) removed both vars | (a) ledger `:1067` (b) uat-6 story `:322` AC8 item 3 + Dev Record `:489` (c) `6a8a3bd4` (2026-08-13) is the sole removing commit; absent at HEAD | **CONFIRMED** (AC number, date, commit, current absence) |
| `ses-1-7` envelope bullet "still tagged `[Rewritten, still open]`" | (a) **no `## Deferred from: … ses-1-7-documentation` section exists**; sole match is `:2079`, inside the `## Last audit: 2026-09-14 (post-merge prune after skillars-deferred-110)` narrative (b) `notification/index.html:386-388` states the section "**was pruned once closed**" (c) `git log -S` → added `1c40d6bf`, removed `6fdc6b90` (deferred-112, 2026-09-15) | **WRONG** — see §5-H4 |
| `c7a327d2` = deferred-113, last touch, 2026-09-15 | (c) `git log -1 --` → `c7a327d2` confirmed last touch; `skillars-deferred-113` confirmed | **CONFIRMED** (hash, story, last-touch). Date is 2026-09-16 author-local / 2026-09-15 UTC — immaterial |
| Doc names `V136` + deferred-112 fold-in; plain callout; links `../database/index.html` | (b) `notification/index.html:373-388` — all three confirmed verbatim | **CONFIRMED** — the doc side of AC5 is genuinely fully resolved, as the story says |
| "delete-outright-on-closure convention" | (a) `## How to read this file`: "Items are deleted outright once they are implemented" (b) the notification doc cites it by name (c) `c9550a46`, `badfcb00`, `6fdc6b90` all delete outright | **CONFIRMED** — nuance: *declined* items are annotated `[DECIDED]`/`[DISMISSED]` and retained; *implemented* ones are deleted. Story's usage is correct |
| `/dashboard` nav admin-only since deferred-141 | (b) `MainLayout.vue:126` `v-if="authStore.isAdmin"` (c) `git blame` → `c8588a32` (deferred-141) | **CONFIRMED** |
| `/player/dashboard` created by deferred-141 **AC4.2** | (b) in-source comment `routes.js:262-263` literally says "skillars-deferred-141 AC4.2" (c) blame `:261-268` → `c8588a32` | **CONFIRMED** — note this is exactly the citation class a single ledger grep would have miscalled fabricated |
| deferred-work.md documents a re-verify-against-HEAD house style | (a) "File paths and line numbers age fast… **Verify against the code before trusting an unannotated forward-reference**" | **CONFIRMED** |

---

## 3. Mechanistic claims (Layer 3)

| # | Claim | Verdict |
|---|---|---|
| 1 | `toClaims` is the single shared claims-builder; pre-OTP token flows through it | **PARTLY WRONG** — single builder and pre-OTP flow CONFIRMED (`TwoFactorLoginService:55` → `JwtManagerImpl.generateToken:108` → `TokenCreatorImpl:31` → `toClaims`). But the caller list omits a 4th: `extendTtlOfToken` (`JwtManagerImpl:133`). "Refresh" is ambiguous — `refreshLoginToken` does **not** call `toClaims` |
| 2 | `toClaims` omits the role; `ROLES` holds fine-grained permission names, **not** the role | **PARTLY WRONG — load-bearing.** Omission CONFIRMED (`toClaims:47-65` puts 12 claims, no role). But authorities **are** role names: `V139__baseline_seed_data.sql:51-55` seeds exactly `ROLE_COACH/PARENT/PLAYER/ADMIN/LTD_ADMIN`, and every registration service sets the matching single authority beside the enum. See §5-M5 |
| 3 | The new claim survives unfiltered to `refreshLoginToken` | **CONFIRMED** — `ClaimsExtractorImpl:43` parses with no `.require(...)`/allow-list; `:72` copies the whole map; token is DB-persisted (`LoginInfo:36` `columnDefinition="text"`) so no truncation. **But see §5-H1** |
| 4 | `createLoginCookies` sets `bcc`/`user`/`ION`/`admin`/`rint`, never `skp` | **CONFIRMED** — `:217,224,227,231,244-248`. `SKILLARS_PROFILE_COOKIE` appears in only `AuthService` + `SecurityConstants` repo-wide. Cosmetic: constant is `B_COOKIE = "bcookie"`, not `bcc` |
| 5 | `createLoginToken`/`renewLoginToken` only ever invoked from `AuthService`, which already sets `skp` | **WRONG — load-bearing.** See §5-H2 |
| 6 | `refreshLoginToken` called exclusively from `SecondFactorLoginFilter` | **CONFIRMED** — sole main-source call `SecondFactorLoginFilter:75`; two test calls; single interface impl |
| 7 | `Principal.skillarsRole` populated via `instanceFrom` on the pre-OTP path | **CONFIRMED** — pre-OTP Principal comes from `LoadUserByUserNameService:35` → `Principal.instanceFrom(user)` → `:157`. AC2's load-bearing assumption holds. Three *other* construction sites leave it null |
| 8 | `hydrateFromCookie` would read a stale pre-2FA `skp` | **CONFIRMED** — `auth.store.js:72-86` parses URL-encoded `{"id","role"}`, exactly AC2's proposed shape; its own docstring (`:59-65`) independently confirms the quoted-id requirement |
| 9 | The two `addCookie` overloads are behaviorally identical here | **CONFIRMED** — attribute-by-attribute identical (`path` `/`, no domain, same `secure` expression, params passthrough); `Lax` hardcoded vs passed |
| 10 | `REFRESH_TOKEN_TTL` shared; wildcard static import already present | **CONFIRMED** — `SecurityConstants:105`; `JwtManagerImpl:27`. `SKILLARS_PROFILE_COOKIE` (`:104`) also covered. No new import needed |
| 11 | Filter has no `User`/role in scope | **CONFIRMED** — `SecondFactorLoginFilter:40-42` injects only `TwoFactorLoginService`, `LoginTokenManager`, `ApplicationEventPublisher`; `LoginData`'s 13 accessors carry no role |
| 12 | `successResponse()` carries no role | **CONFIRMED** — `:97` `new Success("", "login.success", "Login was successful", Map.of())` |
| 13 | Nothing in the frontend navigates to `/otp` | **CONFIRMED** — only non-test hits are `auth.api.js:22` (the POST) and `routes.js:25` (the path). The route has **no `name`**, so name-based navigation is impossible. The live login path (`LoginPage.vue:167` → `/api/auth/login` → `AuthService.login`) has no OTP branch at all |
| 14 | No fixture sets `skillarsRole`; the existing test "exercises the `null → ADMIN` fallback path" | **PARTLY WRONG** — first half CONFIRMED (both Principal sites in the 940-line class omit it). Second half incoherent: that fallback **does not exist yet**. See §5-M4 |
| 15 | Nothing on the server reads the two vars; both absent from `.env.example`/`secrets-reference.md` | **PARTLY WRONG** — absence CONFIRMED (0 hits). "Nothing reads them" is false. See §5-M2 |

**Transaction boundaries (traced, not assumed):** no claim in this story rests on atomicity and the change
introduces none. `SecondFactorLoginFilter` is a servlet filter outside any transaction;
`TwoFactorLoginService` is class-level `@Transactional` (`:24`), so `fetchFor2FA`'s consume commits before
`refreshLoginToken` runs at `:75` — the in-code comment at `:68` ("the transaction is already terminated")
is accurate. The proposed `skp` write is pure response-header mutation with no DB participation.

---

## 4. Response-commit ordering and size budget (traced)

Both checked because they are the classic ways a "just add a cookie" change silently fails:

- **Ordering is safe.** `SecondFactorLoginFilter:75` (`refreshLoginToken`, where cookies are added) runs
  **before** `:78` `successResponse(response)`, the only thing that touches `getWriter()`/`flush()`
  (`:96-105`). The `Set-Cookie` header ships.
- **Size is a non-issue.** No `max-http-header-size` anywhere in `src/main/resources`; no nginx config in
  the repo (the proxy is Traefik, `deploy/traefik/traefik.yml`, ~1MB default). The added claim is ~30
  bytes against an 8KB Tomcat default.
- **`BUS_ID` really is `user.getId()`.** `Principal.java:155` `.businessId(String.valueOf(user.getId()))`
  → `toClaims:60`. Identical decimal digits to `AuthService`'s `"{\"id\":\"" + user.getId()`. **No**
  UUID-vs-Long or business-id-vs-DB-id mismatch — the story's assumption holds.

---

## 5. Findings that survived adversarial re-verification

### HIGH — will produce wrong code if a dev agent implements the story as written

**H1 — The new claim does *not* "ride along on every token": `extendTtlOfToken` re-stamps it `"ADMIN"` for every non-admin.**
Story line 77 asserts the claim rides on *every* token `toClaims` builds. But `toClaims` is also fed by
Principals **reconstructed from the JWT**, and that reconstruction drops the field:

- `ClaimsExtractorImpl.java:76-85` — builder has `.gender(...)`, `.businessId(...)`, `.displayName(...)`,
  `.phone(" ")` and **no `.skillarsRole(...)`** → `getSkillarsRole()` is always `null`.
- `JwtManagerImpl.java:129-133` — `extendTtlOfToken` does
  `final Principal principal = claimsExtractor.extractPrincipal(req); … tokenCreator.toClaims(principal, dbRefreshToken, true, null)`.
- `JWTAuthorizationFilter.java:209` calls `extendTtlOfToken(req, res)` on the **dominant** path — every
  authenticated request inside the `DB_REFRESH_TOKEN_INTERVAL` sliding window.

**Trigger:** a COACH makes any second request inside the sliding window → the re-minted JWT carries
`skillarsRole="ADMIN"` (via the story's own mandated `null → "ADMIN"` fallback at Task 2 bullet 2), and
stays wrong for the rest of the session. Nothing reads the claim today, so nothing breaks *now* — but the
story's framing invites a future reader to trust a claim that is only correct on freshly-minted tokens.
**Fix the spec:** either populate `skillarsRole` in `ClaimsExtractorImpl.extractPrincipal` from the new
claim, or **omit** the claim when the Principal's role is null rather than defaulting to `"ADMIN"`.

**H2 — AC2's third acceptance bullet asserts a falsehood; the stated scoping rationale is broken.**
Story line 79 / AC2 bullet 3: *"`createLoginToken`/`renewLoginToken` (and their only caller, `AuthService`)
are untouched"*. Exhaustive caller sweep (main + test; `LoginTokenManager` has exactly one implementation,
so no indirection escapes):

```
createLoginToken:  AuthService.java:117      (login)   → skp set at :133  ✓
                   AuthService.java:203      (refresh) → skp set at :219  ✓
                   JWTAuthenticationFilter.java:100    → NO skp anywhere in that class
renewLoginToken:   JWTAuthorizationFilter.java:193     → no skp
                   JWTAuthorizationFilter.java:202     → no skp
```

`AuthService` **never calls `renewLoginToken` at all** (zero occurrences in the file). And
`createLoginCookies` has a 4th upstream path the story omits entirely (`extendTtlOfToken` → `createAndSetJwt`).
So on **three of the four** `createLoginCookies` paths there is nothing to double-set — the story's reason
for keeping the fix out of the shared helper ("those paths already set it") is factually wrong. The
*conclusion* (minimal blast radius → patch `refreshLoginToken`) may still be right, but the rationale must
be rewritten, and AC2 bullet 3 states a fact a dev agent will act on and that is not true.

**Second missed flow of exactly the AC2 class, surfaced by the same sweep:** a login through
`/authenticate` with `otpEnabled == false` (`JWTAuthenticationFilter:99-103`) gets **no `skp` at all**.
The in-code comment at `:93` says *"At the moment the flow will always enter here"*, so the branch is
currently cold — but that is the justification, and the story never makes it.

**H3 — Missing-claim handling is unspecified, and the literal spec re-creates the exact dead end AC3/AC4 exist to remove.**
Task 2 bullet 3 says build `skp` from `claims.get(BUS_ID)` + `claims.get(SKILLARS_ROLE_CLAIM)` with no
missing-claim handling anywhere in the story. But `refreshLoginToken` reads a **DB-persisted** pre-OTP
token (`TwoFactorLoginService:55-58`), redeemable for `OTP_TTL = Duration.ofMinutes(30)`
(`SecurityConstants:122`) — so a token minted *before* the deploy stays redeemable for up to 30 minutes
*after* it. The literal implementation then emits `{"id":"123","role":"null"}` (Java string concatenation
of `null`), and:

- `auth.store.js:77` `if (parsed.id && parsed.role)` — `"null"` is a **truthy** string, so it hydrates;
- `roleRoutes.js:29` `Object.hasOwn(ROLE_ROUTES, role) ? … : DEFAULT_ROUTE` with `:21`
  `DEFAULT_ROUTE = '/dashboard'` → the admin-only-nav page.

**It also falsifies a standing ledger verification.** `deferred-work.md:3629-3639` — the *third* bullet in
the same deferred-141 review section this story sources from — records `/dashboard`-as-`DEFAULT_ROUTE` as
*"**Currently unreachable, by verification**"*, reasoning in part that "`hydrateFromCookie` requires both
`id` and `role`". AC2 as specified makes it reachable. The story cites bullets 1 and 2 of that section and
never mentions bullet 3.

**H4 — AC5's delete target does not exist; executing Task 5 would destroy audit history.**
No `## Deferred from: … ses-1-7-documentation` section exists at HEAD. `grep "Rewritten"` returns exactly
one line — `deferred-work.md:2079` — and it sits **inside a historical audit record**
(`## Last audit: 2026-09-14 (post-merge prune after skillars-deferred-110)`), as a category heading in that
audit's narrative, not as a `[...]` tag on an open-work bullet:

```
2079: - **Rewritten, still open:** `ses-1-7-documentation`'s `envelope_entity` Flyway-callout-severity bullet — the
```

`git log -S "code review of ses-1-7-documentation"` shows the real open-work section was added by
`1c40d6bf` and **removed by `6fdc6b90`** (deferred-112, 2026-09-15) — three weeks before this story was
drafted. Decisively, the story's **own cited source** says so: `notification/index.html:386-388`, inside
the exact `:373-388` range the story quotes, reads *"The `ses-1-7-documentation` ledger section this
paragraph used to cross-reference **was pruned once closed**, per `deferred-work.md`'s own
delete-outright-when-closed convention — this paragraph is now the only surviving record of that history."*

**Consequence:** a dev agent either finds nothing actionable, or applies AC5's "delete outright" to a line
of audit history — which contradicts the file's convention (audit blocks are retained history; only
*implemented open items* are deleted). AC5 should be dropped, or rewritten as "confirm already pruned; no
edit."

### MEDIUM — real gaps in the spec

**M1 — `SecurityIT`'s own fixture has `skillarsRole == null`, so AC2's headline IT assertion cannot prove what AC2 claims.**
AC2 requires the cookie carry *"the caller's real `id`/`role`"*. But `SecurityIT:351-366` `getUserData(...)`
sets email/login/phone/activated/langKey/gender/dob/password/otpEnabled/firstName/lastName and **never a
role**, and the `/v1/account/register` path it uses calls none of the four `setSkillarsRole` sites
(`PlayerRegistrationService:114`, `ParentRegistrationService:99`, `CoachRegistrationService:99`,
`AdminBootstrapRunner:202`). So the IT observes the `"ADMIN"` fallback, not a real role — and a regression
that drops the claim **entirely** still passes it, because `claims.get(...) == null` → `"ADMIN"` → cookie
present. AC2 should either state the IT assertion as presence-only, or require the fixture to set a role.

**M2 — AC1's note content is factually wrong, and a stale value can hard-fail a provision re-run.**
Story line 56 (*"Nothing on the server reads those two variables anymore"*) and AC1 (*"this is harmless
(nothing reads them)"*) are false:

```
provision.sh:591  if [ -n "${HETZNER_VOLUME_ID:-}" ]; then
provision.sh:597    err "HETZNER_VOLUME_ID=${HETZNER_VOLUME_ID} does not resolve to an attached Volume"
provision.sh:600    exit 1
```

(`git blame`: the hard-fail at `:597` is `skillars-deferred-88`, added *after* uat-6 removed the vars from
the template — which is exactly why the ledger's inherited "harmless" phrasing went stale.)
`deploy/firewall/apply-firewall.sh:18` likewise requires `HCLOUD_TOKEN`, though its header scopes it to
the operator's local machine.

The *conclusion* that a stale `.env` is inert still holds, but for a reason the story does not give:
**nothing sources `.env` wholesale into `provision.sh`** — it reads individual keys via a targeted
`grep` (`read_env_value`, `:940`), used only for `GF_*` values. The conditional trigger is an operator who
exports from `.env` themselves (`deploy/backup/env-guard.sh:27` does `. "$env_file"` in its own process
tree) before a re-run that `provision.sh:1082` itself invites. The ops note should say *why* it is inert
rather than an unqualified "nothing reads them" that a future operator falsifies in one grep — and then
distrusts the whole note.

**M3 — The post-2FA `Authentication`'s Principal still lacks the role, one line below where AC2 adds the claim.**
`JwtManagerImpl:87-93` `authentication(claims)` builds `new Principal.Builder()….businessId((String) claims.get(BUS_ID))…build()`
with no `.skillarsRole(...)`, even though `claims` (`:72`) would now carry it. That Principal is what
`SecondFactorLoginFilter:79` publishes as `new AuthEvent(auth, AuthenticationAction.SUCCESSFUL_2FA)`. No
current listener reads the field, so this is a gap rather than a break — but it is an obvious half-fix
immediately adjacent to the lines AC2 touches, and the story doesn't mention it.

**M4 — The story describes a not-yet-existing code path as currently under test, under a "confirmed by direct reading" banner.**
Story line 85 claims the existing `testRefreshLoginToken_success()` *"exercises the `null → \"ADMIN\"`
fallback path."* That fallback does not exist in production code — **this story is proposing to add it.**
The test (`:558-615`) asserts only `SUBJECT, ROLES, GENDER, DISPLAY_NAME, BUS_ID, OPF_SEED, iat, exp,
DB_REFRESH_TOKEN, CLIENT_ID`. The paragraph is prefixed *"Confirmed via direct decompilation-equivalent
reading, not assumed"*, which lends false weight to the incoherent half. Reword. (The useful half — no
fixture sets `skillarsRole` — is verified true at both Principal construction sites.)

**M5 — AC2's design rationale rests on a wrong premise about the `ROLES` claim.**
Story line 77 characterizes `ROLES` as *"fine-grained permission names, **not** the single `SkillarsRole`
enum value."* They are role names in 1:1 correspondence with the enum:
`V139__baseline_seed_data.sql:51-55` seeds exactly `ROLE_COACH/ROLE_PARENT/ROLE_PLAYER/ROLE_ADMIN/ROLE_LTD_ADMIN`;
`SkillarsRole` is `{COACH, PARENT, PLAYER, ADMIN}`; and each registration service sets the single matching
authority beside the enum in the same method (e.g. `CoachRegistrationService:99,101`). `createLoginCookies`
**already derives a role decision from this claim one screen away** (`:229-230`
`StringUtils.containsIgnoreCase(roles, "ADMIN")`).

So the role *is* recoverable inside `refreshLoginToken` with no new claim, no new constant, and no change
to `toClaims` — which would also sidestep H1 entirely. **Calibration:** this does not automatically make
the story's design wrong. Derivation has real fragility (`ROLE_LTD_ADMIN` contains the substring `ADMIN`
and has no `SkillarsRole` counterpart; a legacy `/v1/account/register` user gets `ROLE_USER` with a null
enum). The defect is that the story **forecloses the cheaper option on a false factual premise** instead of
choosing deliberately between them. Make it an explicit, stated decision.

**M6 — AC3's acceptance text is satisfiable while still landing on `/dashboard`.**
AC3 reads *"the fallback landing page is `routeForRole(authStore.role)` … not a hardcoded `/dashboard`."*
But `roleRoutes.js:21` `DEFAULT_ROUTE = '/dashboard'`, so `routeForRole(null)` **returns `/dashboard`** —
the acceptance criterion passes while the user lands exactly where AC3/AC4 are trying to stop them (the
live path to this is H3). AC3 should additionally require a spec case for `skp`-absent/unparseable, so the
degradation is covered rather than latent.

**M7 — The sourcing bullet's own stated precondition is dropped without acknowledgment.**
`deferred-work.md:3617-3619`: *"**When picked up:** change the fallback to `routeForRole(authStore.role)`
**at the same time the OTP login flow is actually wired into navigation.**"* Story line 89 explicitly
scopes that wiring out while implementing the fix anyway, and separately concedes AC2/AC3 cannot be
browser-verified. Defensible as a decision — but the story never addresses that it is departing from the
ledger's own sequencing, which is the kind of thing this project's conventions expect to be stated.

### LOW — citation hygiene (full table in §1)

**L1 — `AuthService.authenticate()` does not exist** (story lines 30, 64, 124, 153). The method is
`login()` (`:61-137`); `refresh()` is `:139-223`. Both cited ranges are wrong, and Task 2 instructs the dev
to *"copy `AuthService`'s existing null-fallback exactly"* and replicate *"`AuthService.authenticate()`'s
exact JSON-building"* — a dev grepping for `authenticate()` finds nothing. Rated LOW only because the
snippet the story inlines is verbatim correct, so the intent survives; fix the name regardless.

**L2 — Range/label drift, none behavior-changing:** `Principal.java:21`→`:23`; `JwtManagerImpl.java:26`→`:27`;
`createLoginCookies :213-245`→`:213-249`; `successResponse :88-96`→`:96-105` (cited range is a different
block); `SecurityIT :186-286`→`:187-287` and `:284-285`→`:285-286`; `secrets-reference.md :398-429`→`:398-428`
(`:429` is past EOF); `routes.js` `player/videos :255-259`→`:255-260` (the ledger's own citation is correct);
`routes.js` `/player/dashboard :262-267`→`:261-268`; `LoginPage.vue :168-174`→`:168-175`; `CookieUtil`
"4-arg overload" is **5**-arg; `bcc`→`B_COOKIE = "bcookie"`; `LoginData`/`LoginInfo` conflated (both lack a
role field, so the conclusion stands); `toClaims` caller list omits `extendTtlOfToken`.

---

## 6. What did not survive re-verification

Four claims the layers raised were **killed or downgraded** in the adversarial pass. Recording them is the
point of this section — a report with uniformly high confidence is a report whose re-check did not happen.

1. **KILLED — "the router guard bounces the user to `/login` regardless, so AC3 cannot work."**
   A layer argued that because `router/index.js:39` hydrates only once per page load (`let hydrated = false`),
   the post-OTP `router.push` hits `:59-62` (`requiresAuth && !isAuthenticated`) and redirects to `/login`.
   **Refuted:** `auth.store.js:15` defines `isAuthenticated = computed(() => !!userId.value)`, and
   `hydrateFromCookie()` sets `userId.value` (`:78`). AC3 **explicitly specifies adding that call** after
   `initSession()`, which makes `isAuthenticated` true and the guard pass. The ledger bullet at `:3613-3615`
   describes this bounce as *today's* behavior, which is correct and is precisely what AC3 fixes.
   **Downgraded survivor:** AC3's acceptance bullets assert only resulting paths, so a spec that stubs the
   store could pass without `hydrateFromCookie()` ever being added — a test-design gap, folded into M6, not
   the structural defect claimed.

2. **KILLED — three citation-drift claims where the story turned out to be right.** A layer reported
   `/otp` at `routes.js:22-26`, `player/videos` at `:256-261`, and `refreshLoginToken` at `:67-83`. All three
   are wrong on direct read: the `/otp` route object is `:24-28` (`path: 'otp'` at `:25`, exactly as the
   story and the ledger both say), `player/videos` is `:255-260`, and `refreshLoginToken`'s signature is
   `:68` (`:67` is `@Override`). Two layers contradicted each other here; the conflict was resolved by
   reading the files directly rather than by preferring either layer.

3. **DOWNGRADED — "there is nothing at all for AC5 to delete."** Literally true of *open-work* bullets, but
   imprecise: text matching the story's description **does** exist at `deferred-work.md:2079`. The accurate
   and more useful finding is that it is **audit history, not an open item** — which is what makes executing
   Task 5 actively harmful rather than merely a no-op. Restated as H4.

4. **DROPPED — "`V136__pin_envelope_entity_schema.sql` does not exist on disk."** True (only `V138`/`V139`
   remain after the deferred-112 squash), but the story never claims the file is present — it accurately
   reports that the *doc names it*, including the fold-in. Not a defect.

Also checked and found clean, so not reported as findings: JWT/cookie/header size budget; strict claims
validator or allow-list (none exists); `BUS_ID` vs `user.getId()` identity; `Set-Cookie` vs response-commit
ordering; `CookieUtil` overload equivalence; `REFRESH_TOKEN_TTL` availability and the existing wildcard
import; `SkillarsRole`'s four values; `hydrateFromCookie` synchronicity and parse shape; `redirectPath`
computed reactivity (read once, after the proposed hydration — safe); vitest glob coverage of the proposed
spec path; `/player/dashboard` reachability and loop-freedom for a 403'd PLAYER; absence of sibling
`/dashboard` dead-ends (the story fixes the only one); `VideoManagementPage`'s other error handlers;
`skp`'s single consumer; AC1 not duplicating an existing section; uat-6 AC8's removal; both deferred-141
cross-story claims; and "nothing navigates to `/otp`".

---

## 7. Recommendation

**Per-claim confidence, not a blanket score.**

| Finding | Confidence | Basis |
|---|---|---|
| H2 (caller claim false) | **Highest** | Exhaustive grep re-run by me; single interface impl; `AuthService` has zero `renewLoginToken` occurrences |
| H4 (AC5 target absent) | **Highest** | Re-run grep; `git log -S` provenance; contradicted by the story's own cited doc line |
| H1 (`extendTtlOfToken` clobber) | **High** | All three deciding files re-read by me (`ClaimsExtractorImpl:76-85`, `JwtManagerImpl:129-133`, `JWTAuthorizationFilter:209`) |
| M2 (AC1 factually wrong) | **High** on the fact; **Medium** on the trigger | `provision.sh:591-600` + blame re-read by me; the stale-`.env`→provision path is conditional on operator behavior |
| M5 (`ROLES` premise wrong) | **High** on the fact; **judgment** on the implication | Seed data + registration services verified; whether to switch design is a deliberate call, not a defect |
| H3 (missing-claim → `/dashboard`) | **High** | Every link re-read (`OTP_TTL:122`, `auth.store.js:77`, `roleRoutes.js:21,29`); rests on the story's *literal* spec, which a careful dev might fix unprompted |
| M1, M3, M4, M6, M7 | **High** on evidence; **Medium** on severity | All file:line evidence verified; each is a spec/coverage gap rather than a wrong-code generator |
| L1, L2 (citation drift) | **Highest** on facts; **Low** severity | Every range re-read; three competing drift claims refuted (§6-2) |

**What was independently re-checked by me, not just by a layer:** the `AuthService` method inventory;
all callers of `createLoginToken`/`renewLoginToken`/`refreshLoginToken`/`extendTtlOfToken`;
`SKILLARS_PROFILE_COOKIE`'s full repo footprint; the `ses-1-7`/`Rewritten` ledger state and its git
provenance; the `notification/index.html` callout and its last-touch commit; `provision.sh`'s env-var reads
and blame; `ClaimsExtractorImpl.extractPrincipal`'s builder; `toClaims`' and `createLoginCookies`' full
bodies; `auth.store.js` and `roleRoutes.js` in full; `SecurityIT`'s fixture; the `setSkillarsRole` call
sites; the authority seed data; `OTP_TTL`; and all three contested citation ranges.
**What I did not independently re-run:** the size-budget/Traefik survey, the vitest glob check, the
`PlayerDashboardPage.vue` loop-freedom read, and the uat-6 AC8 story-text quotes — all §6 "clean" items
where a layer's negative result changes no recommendation.

**Verdict: do not start implementation as written.** AC1 and AC5 both need rewriting before they are
actionable (AC5 arguably deleted), and AC2 carries one false acceptance bullet (H2), one unhandled
deploy-window case (H3), and one design rationale built on a wrong premise (M5) plus a real latent
mis-stamp (H1). AC3 and AC4 are sound in substance — AC4 is clean and AC3's only issues are coverage gaps
(M6) and an unacknowledged departure from its source bullet (M7).

**Minimum edits before `ready-for-dev` holds:**
1. **AC2** — rewrite bullet 3 (H2); specify missing-claim behavior explicitly, and prefer *omitting* `skp`
   over emitting `role:"null"` (H3); decide deliberately between a new claim and local `ROLES` derivation
   and say why (M5); if the claim stays, handle `ClaimsExtractorImpl.extractPrincipal` (H1); restate the IT
   assertion as presence-only or give the fixture a role (M1).
2. **AC5** — drop it, or restate as "verify already pruned, no edit." Do **not** delete
   `deferred-work.md:2079` (H4).
3. **AC1** — replace "nothing reads them" with the accurate mechanism: `provision.sh` does read
   `HETZNER_VOLUME_ID` and hard-fails on a bad one, but nothing sources `.env` wholesale, which is what
   makes a leftover inert (M2).
4. **AC3** — add a `skp`-absent/unparseable spec case (M6); note the departure from the ledger's
   "when picked up" sequencing (M7).
5. **Citations** — fix `AuthService.authenticate()` → `login()` and the range drift in §1/§5-L2.
