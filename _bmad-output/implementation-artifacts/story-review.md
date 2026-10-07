# Story Review — skillars-deferred-148

**Story:** `_bmad-output/implementation-artifacts/skillars-deferred-148-player-id-corruption-dispute-id-space-and-router-role-gate-fixes.md`
**Title:** Player-ID Corruption Fixes, Dispute Cross-ID-Space Bug, and Router Role-Gate Hardening
**Story status:** ready-for-dev
**Audit date:** 2026-10-07
**Verified against real HEAD:** `ef2daac9` — *Story Deferred-147: Player Development Dashboard — Missing Nav Link (#254)*

HEAD was obtained with `git rev-parse --short HEAD` / `git log -1` **before** the story was opened. The story's own Context header asserts HEAD `ef2daac9`; that assertion happens to be correct, but every citation below was still re-derived from the current on-disk file contents rather than trusted. Working tree is clean apart from files under `_bmad-output/implementation-artifacts/`, so no source file differs from HEAD.

**Method:** four parallel read-only verification layers (citations / ledger & precedent / mechanistic claims / corner cases), followed by a mandatory adversarial re-verification pass in which every candidate finding was re-read against the source with the explicit goal of refuting it. Section 6 lists what that pass killed — it removed more candidate findings than it kept.

---

## 1. Headline findings

| # | Severity | Finding |
|---|---|---|
| **C2** | **High** | A fourth `Number(...)`-corrupts-a-Tsid site exists and is missed: `BookingRequestPage.vue:247`. It corrupts an id used to **write** bookings, and is reachable from a live, linked parent CTA. |
| **C1** | **Medium** | AC4's `role: 'PLAYER'` on `player/locker-room/:playerId` contradicts the backend's own authorization model — `PlayerOwnershipGuard` explicitly authorizes a PARENT for a managed child's player resources — and breaks a live parent-only button. Downgraded from High because the button's host route turns out to be unlinked. |
| **C3** | **Medium** | AC6's third bullet is not implementable as written: `isQuiesced` is `private`, not package-private. Finding 6 attributes a visibility change to the wrong method. |
| **C4** | **Medium** | Finding 4's "six routes ... confirmed by direct read of the full route table" is not exhaustive — there are 11, and a 4th inert role-gate spelling exists on a route this story edits. |
| **C5** | Med-Low | Finding 5's "no existing test depends on the current ordering" was established from 2 test files; a third (`ReviewUpdateIT`) asserts on both error codes and is absent from AC8's suite list. |
| C6–C12 | Low | See §5. |

---

## 2. Citation verification

Every `file:NN` reference in the story was opened at HEAD. **Of 39 checked citations, 30 are exact matches.** `.dockerignore`'s nine line numbers are all exactly right, as are all of `DisputeService.java`, `Booking.java`, `ReviewSubmissionService.java`, `DatabaseResetTestExecutionListener.java`, and 11 of 12 `routes.js` ranges.

### Matches (spot-verified, representative)

| Citation | Evidence |
|---|---|
| `ParentDevelopmentPortalPage.vue:123-126` | L123 `const playerId = computed(() => {`, L124 `const id = Number(route.params.playerId)`, L125 `return isNaN(id) ? null : id` |
| `ParentPlayerPortalPage.vue:76` | `const playerId = Number(route.params.playerId)` |
| `ParentPlayerPortalPage.vue:88` | `if (newId && newId !== playerId) {` |
| `PlayerSubscriptionPage.vue:189` | `const playerId = computed(() => Number(route.params.playerId))` |
| `PlayerSubscriptionPage.vue:215,257,260,280` | all four reads confirmed; grep shows **exactly** four `.value` reads — the enumeration is complete |
| `router/index.js:89` | `if (to.path === '/coach/command-center' && authStore.isCoach) {` |
| `routes.js:115-117 / 239-241 / 244-247 / 250-253 / 338-341 / 342-344` | all confirmed `meta: { requiresAuth: true }` |
| `routes.js:124,132,151,160` | all `meta: { requiresAuth: true, roles: ['PARENT','PLAYER'] }` |
| `routes.js:256-259, 264-267, 295-298, 307-310` | sibling `role:'PLAYER'` / `role:'COACH'` gates confirmed |
| `DisputeService.java:91` | exact text match |
| `DisputeService.java:74` | `private final ApplicationEventPublisher eventPublisher;` — **is** the last field; all 12 fields are `final` |
| `Booking.java:31,34` | `@Column(..., nullable = false, updatable = false) private Long parentId/playerId;` |
| `ReviewSubmissionService.java:253-264` | inside `checkEligibility` (starts L243); L253 `playerProfileRepository.findById(booking.getPlayerId())` |
| `ReviewSubmissionService.java:129-141 / 181-194` | both guard pairs exactly as described, cooldown-before-status at both |
| `DatabaseResetTestExecutionListener.java:114-116` | the `quiesceStartNanos` / `quiesceAsyncExecutors` / `recordQuiesceCost` triple |
| `DatabaseResetTestExecutionListener.java:351-353` | `isQuiesced` signature, body, close brace |
| `.dockerignore:29,30,31,32,66,67,68,73,74` | `**/node_modules/`, `**/.vite/`, `*.jar`, `*.war`, `*.iml`, `.DS_Store`, `Thumbs.db`, `*.log`, `*.diff` — **all nine exact** |
| `AppEndpoints.java` | `ACTUATOR = "/manage/**"` mapped to `AuthoritiesConstants.ADMIN` — `/manage/**` is ADMIN-only, as claimed |
| `axiosSpec.js:16-20` | the `defineBoot`-is-an-identity-wrapper note, verbatim |
| `pages/parent/__tests__/` | contains only `BookingRequestPageSpec.js`, `ParentBookingsPageSpec.js` — no spec for the three pages, as claimed |

### Drifted

| Citation | Real location | Note |
|---|---|---|
| `router/index.js:48-49` — "`rolesMeta`/`requiresOneOfRoles`" | **L55-56** | L48-49 are `requiresGuest`/`requiresCoach`. The cited lines point at *neighbouring gates*, which is the dangerous kind of drift — a dev agent editing by line number lands on working code. |
| `router/index.js:80-83` — the `requiresOneOfRoles` redirect branch | **L84-87** | L80-83 are the `requiresPlayer` branch. |
| `router/index.js:44-50` — the `beforeEach` derivations | **L47-56** | `requiresPlayer` (L51) and `rolesMeta`/`requiresOneOfRoles` (L55-56) fall outside the cited range. |
| `router/index.js:28-35` — the `createRouter` call | **L29-37** | The `history:` line (L36) and closing `})` (L37) are outside the range. Content claim ("passes neither `strict` nor `sensitive`") is correct — `grep -n "strict\|sensitive"` returns zero hits in the file. |
| `roleRoutes.js:14 / :15 / :16` = PARENT / PLAYER / ADMIN | **:15 / :16 / :17** | Systematic off-by-one; L14 is `COACH`. |
| `AdminReviewService.java:144-145` sets **both** status and `lastModifiedAt` | `setModerationStatus(BLOCKED)` at **L143**; L144 is `setHeldReason(null)`; `setLastModifiedAt` at L145 | The cited 2-line range contains only one of the two claimed assignments. |
| `ReviewSubmissionServiceConcurrencyIT:288` | **L289** | L288 is the `@Test` annotation; the method name is on L289. |
| References: `DisputeService.java:90` | **:91** | The body and Design B correctly say `:91`; the References section copied the ledger's stale `:90`. Internal inconsistency within the story. |
| `ParentDevelopmentPortalPage.vue:185-192` — includes the "Kept narrow" comment | comment is at **L184** | The watcher block itself is exactly L185-192; only the quoted comment sits one line above. |

None of the drifts invalidates a conclusion, and the story's own Dev Notes do instruct re-verification. The `index.js` cluster is worth correcting in place because it is the largest drift and lands on plausible-looking wrong code.

---

## 3. Ledger & precedent attribution

Checked against all three required sources for each claim: the ledger file (working tree **and** `git show HEAD:...` — the local diff is additive only and touches none of the five cited sections), source comments/Javadoc near the code, and `git log --grep` / `git blame` / `git show` for each story number.

| Claim | Sources checked | Verdict |
|---|---|---|
| Sourced from the 5 named `deferred-work.md` sections | ledger headings + item counts | **CONFIRMED.** Item counts match exactly: deferred-145 §=3 items (1 taken), §round 2=3 (1 taken), deferred-144 §=3 (2 taken), deferred-146 §=4 (2 taken). |
| deferred-147 "explicitly recommended a small dedicated follow-up ... to all three" | ledger §; deferred-147 story file; commit message | **CONFIRMED.** The quoted sentence is not in the ledger but is verbatim in deferred-147's own story file (`:7`) — and the story attributes it to "that story's own manual-testing notes," not the ledger. Correctly attributed. The commit message at `ef2daac9` independently names the same three files. |
| Finding 4's ledger item names exactly `/player/home`, `/player/locker-room/:playerId`, `/player/development/:playerId`, `/parent/dashboard` | ledger text | **CONFIRMED verbatim**, with the admin pair as the item's lead example. |
| Ledger names `createRouter({ strict: true, sensitive: true })` as the alternative for Finding 3 | ledger text | **CONFIRMED verbatim.** |
| Finding 5's ledger item requires reordering **both** sites together | ledger text | **CONFIRMED** — "reorder the status guard ahead of the cooldown guard at **both** sites together, so the two paths stay mirrored". |
| `checkEligibility:253-264` was shipped by deferred-145 | `git blame` | **CONFIRMED** — every line blames to `f789ce5b` (Deferred-145). |
| deferred-143 implemented forced-logout/session-termination | commit `a0d39f83` | **CONFIRMED** — its own title, AC3 introduces `SecurityUtil.terminateSession`. |
| `chk_pp_owner` guarantees **exactly one** of `parent_id`/`user_id` | `V138__baseline_schema.sql:911`; `PlayerProfile.java:42` Javadoc | **CONFIRMED, and precisely "exactly one"** — a true XOR: `CHECK ((parent_id IS NOT NULL AND user_id IS NULL) OR (parent_id IS NULL AND user_id IS NOT NULL))`. |
| `defineRouter`/`defineBoot` are identity wrappers | installed `@quasar/app-vite/exports/wrappers/wrappers.js` | **CONFIRMED** — `const wrapper = callback => callback; export const defineBoot = wrapper; export const defineRouter = wrapper`. Resolved version 2.4.1 (`package.json` says `^2.1.0`, a caret range — "pinned" is loose wording, behavior confirmed regardless). |
| "No local `mvn verify`" is a standing convention | `docs/validation-strategy.md` | **CONFIRMED as documented repo policy**, not merely habit. |
| `DatabaseResetTestExecutionListenerQuiesceTest` exists, added by deferred-146 | file + `git show b27ce6f1` | **CONFIRMED.** |
| `MAX_QUIESCE_PASSES` and `ConditionTimeoutException` handling exist | source | **CONFIRMED** (L348 and L229-335). |
| deferred-144's "predecessor inline guard accepted the same strings" | `git show 74d405d2 --stat`; `git blame index.js:89` | **FAITHFUL BUT AMBIGUOUS.** deferred-144 touched `safeRedirect.js`, `routes.js`, `LoginPageSpec.js`, `OtpPageSpec.js` — **never `router/index.js`**; `index.js:89` blames to `4dd4b013` (2026-06-12), untouched since. The sentence is about `isSafeRedirect`'s predecessor, not the command-center check, and mirrors the ledger's own equally loose phrasing. Not a fabrication; a reader could misread it as claiming deferred-144 edited `index.js`. |
| `isQuiesced` was widened from private → package-private by deferred-146 | `git show b27ce6f1`; current source; the test class | **WRONG — see C3.** |
| `project-context.md`'s module-layering rules bless the cross-module repo dependency | `project-context.md`; `PlayerProfileRepository` package; `ReviewSubmissionService` fields | **PARTIALLY OVERSTATED.** (b) and (c) confirmed: the repo is in `platform.security.repo` and `ReviewSubmissionService` does inject it. But `project-context.md`'s only cross-*module* statement is "Prefer Domain Events for cross-module communication to maintain loose coupling" — which, read literally, argues *against* direct cross-module repository injection. Nothing is violated, but the precedent is established by existing code, not by the document. |
| Finding 7's ledger source | ledger | **REAL BUT UNCITED.** Finding 7's item lives in "code review of skillars-deferred-142" — correctly identified in the body/Dev Notes, but that section is never listed in the story's References (see C11). |

---

## 4. Mechanistic claims

### Confirmed

| Claim | Evidence |
|---|---|
| `893573203704173564` → `893573203704173600` | `node -e "console.log(String(Number('893573203704173564')))"` → `893573203704173600`. Reproduced. |
| vue-router 4.6.4 defaults `strict: false, sensitive: false` | installed `vue-router/dist/vue-router.mjs:407,582` — `sensitive: false` in both defaults objects. |
| `/COACH/COMMAND-CENTER` resolves (case-insensitive) | `vue-router.mjs:486` — `const re = new RegExp(pattern, options.sensitive ? "" : "i")`. The `i` flag is applied whenever `sensitive` is falsy. |
| **Design C is sound** — a matched record's `.path` is the full absolute path | `vue-router.mjs:645-646` — `normalizedRecord.path = parent.record.path + (path && connectingSlash + path)`. `routes.js:77` declares `path: 'coach/command-center'` as a relative child of the `path: '/'` parent (`:4`), so the normalized record path is `/coach/command-center`. `to.matched.some(r => r.path === '/coach/command-center')` therefore matches, and `to.matched.length === 2` as claimed. **This was the single highest-stakes claim in the story and it holds.** |
| `requiresAuth`/`requiresGuest`/`requiresCoach`/`requiresParent`/`requiresPlayer` are all `to.matched.some(...)` | `index.js:47-51` — all five confirmed. |
| No `meta.role === 'ADMIN'` and no `meta.role === 'COACH'` check in the guard | full read of `index.js` (102 lines); only `'PARENT'` (L50) and `'PLAYER'` (L51) are compared. |
| `rolesMeta = to.matched.flatMap((r) => r.meta.roles \|\| [])` | `index.js:55`, quoted exactly right. `r.meta` is always normalized to an object by vue-router, so no guard is needed. |
| **Setting `roles: ['ADMIN']` on the parent `admin` route alone is sufficient** | `flatMap` over `to.matched` collects from every matched ancestor; the consuming branch is `index.js:84` `if (requiresOneOfRoles && isAuthenticated && !rolesMeta.includes(authStore.role))`. |
| **`roles: ['ADMIN']` will actually fire** (no `ROLE_` prefix mismatch) | `auth.store.js:16-19` — `isAdmin = computed(() => role.value === 'ADMIN')`; `role.value` is set from the `skp` cookie's bare `"role":"COACH"`-style value. The existing working `['PARENT','PLAYER']` precedent uses the same bare form. |
| No admin redirect loop | `roleRoutes.js:17` `ADMIN: '/admin/health-dashboard'`; an ADMIN passes the new gate, a non-ADMIN is sent to their own landing route. `routeForRole` falls back to `DEFAULT_ROUTE = '/dashboard'`, which is deliberately role-agnostic. |
| Finding 4's planted-`?redirect=` premise | `safeRedirect.js:32-37` — checks shape and resolvability only, **no role check at all**; consumed at `LoginPage.vue:172` and `OtpPage.vue:160`. A PARENT/PLAYER/COACH really can be landed on `/admin/health-dashboard` today. |
| `Booking.playerId` is **not** nullable | `Booking.java:33-34` — `@Column(name = "player_id", nullable = false, updatable = false)`. So `playerProfileRepository.findById(booking.getPlayerId())` can never be called with `null`. |
| `raisedByRole` does **not** gate the `ownerEligible` branch | full read of `raiseDispute`: `raisedByRole` is only stored (`dispute.setRaisedByRole(...)`). AC2's `raisedByRole = "PLAYER"` test does reach the disjunct. |
| `eventPublisher` is the last field and all 12 fields are `final` | `DisputeService.java:63-74`. No `@AllArgsConstructor`, no explicit constructor. Design B's "add it last" advice is correct — the new parameter appends as the 13th. |
| `isQuiesced` cited code matches reality | `L351-353`: `return executor.getActiveCount() == 0 && executor.getThreadPoolExecutor().getQueue().isEmpty();` |
| Nested `.DS_Store` files really exist under `src/` | `find src -name .DS_Store` → `src/.DS_Store`, `src/main/.DS_Store`, `src/main/resources/.DS_Store`, `src/main/java/.DS_Store`, `src/main/java/com/.DS_Store`. Finding 7's practical motivation is real, not theoretical. |
| `**/` prefixes are safe for the deliberately-included `.git/` | `find .git -name "*.log" -o -name "*.diff" -o -name "*.jar" -o -name "*.iml"` → **zero matches**. AC7 cannot break `git-commit-id-maven-plugin`. |
| The AC3 test vehicle resolves | `vitest.config.mjs` carries a hand-maintained `{ find: '#q-app/wrappers', replacement: '@quasar/app-vite/wrappers' }` alias, and that target exports **both** `defineBoot` and `defineRouter` as identity wrappers. `index.js:22`'s factory destructures nothing, so `createRouterFactory({})` is fine. The Dev Notes' claim holds statically. |
| **Finding 6's two Spring behavior claims** — `getActiveCount()` returns 0 on a null delegate; `getThreadPoolExecutor()` `Assert.state`-throws | Verified against the real `spring-context-6.2.19-sources.jar`, not from memory: `getActiveCount()` is `if (this.threadPoolExecutor == null) { // Not initialized yet: assume no active threads. return 0; }` and `getThreadPoolExecutor()` is `Assert.state(this.threadPoolExecutor != null, "ThreadPoolTaskExecutor not initialized")`. **Both halves exact, including the comment wording the story quotes.** The `&&` short-circuit genuinely does not protect the second operand. |
| `CommonConfig.longToStringModule` quotes `Long` as a JSON string | `CommonConfig.java:41-51` — `module.addSerializer(Long.class, ToStringSerializer.instance)` plus a matching `LongFromStringDeserializer`. Scoped to boxed `Long.class` (not primitive `long`), which is immaterial to the story's point. |
| `raisedBy` is a User id | `DisputeResource.java:38-41` — `Long userId = resolveCurrentUserId(); ... disputeService.raiseDispute(..., userId, role)`, reading the authenticated `Principal`. No `@PreAuthorize` blocks a PLAYER caller (`IS_AUTHENTICATED` only), so Finding 2's premise is reachable at the API boundary too. |
| `ReviewErrorCode` / `ConfigBounds` literals | `ReviewErrorCode.java:10-11` — `UPDATE_TOO_SOON("reviews.updateTooSoon")`, `EDIT_NOT_PERMITTED("reviews.editNotPermitted")`. `ConfigBounds.java:197-199` — `REVIEWS_UPDATE_COOLDOWN_DAYS` with bounds `1L, 365L` and default `30L`, matching both guard sites' literal `30, 1, 365`. |
| `updateReview`'s transaction and lock boundary | Class-level `@Transactional` (`:39`, REQUIRED); the method itself carries no annotation and `ReviewResource` opens no transaction, so that is the outermost boundary. The lock is a real Postgres row lock — `findByIdForUpdateNoWait` under `lockRetryer.withBoundedRetry`, then `entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE)` (`:160-172`) — held until the transaction commits. |

### Wrong or imprecise

**M1 — `isQuiesced` was never widened; the widened method was `quiesceAsyncExecutors`.** *(basis of C3)*
Finding 6 says "`skillars-deferred-146` widened this method from `private` to package-private for its own new unit test ... new exposure on a method that can now be called directly."
Reality:
- `DatabaseResetTestExecutionListener.java:351` — `private static boolean isQuiesced(ThreadPoolTaskExecutor executor) {` — still `private`.
- `git show b27ce6f1` shows `isQuiesced` was **introduced by that commit** as `private static`. There is no prior state to have been widened from.
- What that commit did widen is a **different method**: `- private void quiesceAsyncExecutors(ApplicationContext ctx)` → package-private, live now at `:294` as `void quiesceAsyncExecutors(ApplicationContext ctx)`.
- `DatabaseResetTestExecutionListenerQuiesceTest` confirms it independently: its only two tests are `quiesceAsyncExecutors_sixAlreadyIdleExecutors_completesInUnder15Millis` (`:52`) and `quiesceAsyncExecutors_busyExecutor_blocksUntilItDrains` (`:76`). **It never calls `isQuiesced`.**
- The story's own Design F code block writes `private static boolean isQuiesced(...)` — i.e. the Design is correct and silently contradicts its own Finding two sections earlier.
- The ledger contains the same error, so the story propagated it rather than catching it.

**M2 — "None of the three passes `playerId` as a prop to a child component ... no matches" is false.**
`ParentDevelopmentPortalPage.vue:87` — `<PerformanceReportsPanel :player-id="playerId" :is-coach="false" />` and `:97` — `<PlayerTimelinePanel :player-id="playerId" />`. The story's own next sentence confirms those two components declare a `playerId` prop, so the claim contradicts itself. The giveaway is the phrase "in both files" in a finding about three files.
**The conclusion is nevertheless correct:** every `playerId` prop in the codebase is already `[Number, String]` — `PerformanceReportsPanel.vue:74`, `PlayerTimelinePanel.vue:61`, `GenerateReportDialog.vue:47` (the grandchild reached via `PerformanceReportsPanel.vue:59`), `SkillsRadarAssessmentPanel.vue:96`, `WrapUpSequence.vue:243`, `EditPlayerPositionDialog.vue:63`. Both panels also guard with `if (!props.playerId) return` before using it, and only pass it through to store fetches. No prop-type change is needed. Correct conclusion, false premise — fix the premise so the dev agent doesn't skip the check.

**M3 — Design B is not "the exact shape" of the `ReviewSubmissionService` precedent.**
`ReviewSubmissionService.java:264` — `if (authorId.equals(player.getUserId()) || authorId.equals(player.getParentId()))`. It checks **both** `userId` and `parentId`. Design B maps only `getUserId()`. In `DisputeService` the parent case is nominally covered by `booking.getParentId()`, but that is the *booking's* parent, not the *profile's* parent — they can differ. The narrowing is defensible; calling it "exact" is not, and the story should state the deviation deliberately.

**M4 — "exactly as `skillars-deferred-147` did" overstates the match.**
`git show ef2daac9` shows the real fix was `const playerId = computed(() => route.params.playerId)` — bare, with no `?? null`. Design A adds `?? null` for `ParentDevelopmentPortalPage.vue`. That is a reasonable adaptation of that file's different pre-fix null handling (`isNaN(id) ? null : id`), but it is a deviation, and it has a behavioral consequence (see C7).

**M5 — "a *third*, currently-inert spelling" undercounts; there is a fourth.** *(see C4)*

**M6 — "the existing coach lookup three lines below it."** The coach lookup is at `DisputeService.java:97`, inside the `if (!ownerEligible)` block that opens at `:92` after a four-line comment — roughly six lines below, not three. Cosmetic; the substance (a lazy lookup on the non-parent path) is right.

---

## 5. Corner cases, false assumptions, missed flows

### C1 — MEDIUM: AC4's `role: 'PLAYER'` on `player/locker-room/:playerId` contradicts the backend's authorization model and breaks a parent-only button

`src/frontend/src/pages/parent/ParentPlayerPortalPage.vue:10-15` renders, with **no `v-if` role guard**:

```html
<q-btn
  flat
  icon="sports_soccer"
  :to="{ name: 'player-locker-room', params: { playerId: route.params.playerId } }"
  :label="t('player.viewLockerRoom')"
/>
```

That page's own route is `parent/players/:playerId/sessions` with `meta: { requiresAuth: true, role: 'PARENT' }` (`routes.js:136-139`) — so **only a PARENT can ever see this button.** The button is live, not dead code: `player.viewLockerRoom` exists in all three locales (`en-US:257`, `fr-FR:21`, `de-DE:287`).

AC4 adds `role: 'PLAYER'` to `routes.js:247`. Then `index.js:51` sets `requiresPlayer = true`, and `index.js:79-82` fires:

```js
if (requiresPlayer && isAuthenticated && !authStore.isPlayer) {
  next(routeForRole(authStore.role))   // → '/parent/dashboard' for a PARENT
  return
}
```

**Failure scenario:** a parent opens their child's session portal and clicks "Locker Room". Instead of the locker room they are bounced to `/parent/dashboard`. Silent, no error.

**The decisive evidence is at the authorization layer, not the UI.** `PlayerOwnershipGuard.check` — the guard that gates every player-scoped resource, including `HomeworkResource`, which is what the locker-room page reads — is:

```java
Long callerId = Long.parseLong(skillarsP.getBusinessId());
return playerProfileRepository.existsByIdAndParentId(playerId, callerId)
    || playerProfileRepository.existsByIdAndUserId(playerId, callerId);
```

The backend deliberately authorizes **both** a parent on a parent-owned profile **and** a self-registered player on their own — the `existsByIdAndUserId` half was added by deferred-147 precisely because it was missing. So `player/locker-room/:playerId` is a dual-persona route by design, and Finding 4's persona table ("PLAYER") is wrong for this row. A singular `role: 'PLAYER'` gate puts the router in direct conflict with the authorization model the previous story just finished fixing.

The story reached "persona: PLAYER" from `ROLE_ROUTES.PLAYER`'s redirect chain alone (`roleRoutes.js:10-11`, `/player/home` → `/player/locker-room/:playerId`) and never grepped for inbound navigation. A full sweep of `player-locker-room` references finds four callers: `PlayerHomeRedirectPage.vue:46` (player), `PlayerProfileBuilderPage.vue:66` (player), `roleRoutes.js:11` (comment), and **`ParentPlayerPortalPage.vue:13` (parent)**.

**Why this is Medium and not High.** I initially rated it High, then found that the button's host route is itself unlinked: `parent/players/:playerId/sessions` (`routes.js:135-138`) has **no `name`**, and no `router.push` / `:to` / `<router-link>` anywhere in the frontend navigates to that path. The parent dashboard's player cards are plain `<div>`s, not links (`ParentDashboardPlaceholderPage.vue:19-31`), and the only `/parent/players/` navigation in the app is `MainLayout.vue:327` → `.../packs`, a different route. So today the button is reachable only by direct URL or a bookmark. The regression is real and the code is live (the `player.viewLockerRoom` key exists in all three locales), but the blast radius is small.

**Resolution options:** gate the route `roles: ['PLAYER', 'PARENT']` (the existing dual-role mechanism, already proven on four routes, and the option that matches `PlayerOwnershipGuard`); or drop `locker-room` from AC4 and defer it. Adding `v-if="authStore.isPlayer"` to the button would also stop the bounce but removes a parent affordance the backend supports — a product decision, not a mechanical fix.

`player/development/:playerId` and `parent/dashboard` were checked the same way and are **safe** — see §6.

### C2 — HIGH: a fourth Tsid-corruption site is missed, and it corrupts a write path

`src/frontend/src/pages/parent/BookingRequestPage.vue:245-251`:

```js
const playerId = computed(() => {
  if (route.query.playerId && !authStore.isPlayer) {
    const parsed = Number(route.query.playerId)
    if (Number.isFinite(parsed) && parsed > 0) return parsed
  }
  ...
})
```

Same defect, on `route.query` rather than `route.params` — which is why the story's grep (and deferred-147's) missed it. The `Number.isFinite(parsed) && parsed > 0` guard does **not** catch a rounded Tsid: `893573203704173600` is finite and positive.

**Reachable from a live parent CTA.** `CoachPublicProfilePage.vue:516-522` (the `authStore.isParent` branch of `handleCta`):

```js
const playerId = playerStore.activePlayerId
const params = new URLSearchParams()
if (playerId) params.set('playerId', playerId)
...
router.push(`/parent/coaches/${coachId}/request-booking${qs ? `?${qs}` : ''}`)
```

`playerStore.activePlayerId` is the real Tsid string; the query param carries it intact; `BookingRequestPage` then rounds it. Unlike C1, this entry point is fully linked and live: `CoachPublicProfilePage` is the public marketplace route `coaches/:coachId` (`routes.js:319-321`), and `handleCta` is its primary call-to-action.

**Consequences are worse than the three sites in scope**, because this id is written, not just read:
- `:491` — `playerId: playerId.value` in the single booking-request submit
- `:558` — `bookingStore.submitBatch(coachId, playerId.value, 0)`
- `:481` — propagates the corrupted id onward: `router.push(\`/parent/coaches/${coachId}/purchase-sessions?playerId=${playerId.value}\`)`
- `:652` — `bookingStore.loadPlayerPacks(playerId.value)`

AC1's stated goal is "**every** parent-facing page targets the real player id instead of a silently-rounded one." That is not achieved while `BookingRequestPage.vue` remains. Either add it to AC1 (it is a one-line fix of the same shape) or state explicitly that it is deferred and why — but the story should not assert the enumeration is complete.

Note `BookingRequestPage.vue` already has a spec (`pages/parent/__tests__/BookingRequestPageSpec.js`), so this one needs an added case, not a new file.

### C3 — MEDIUM: AC6's third bullet is not implementable as specified

AC6: *"New test in `DatabaseResetTestExecutionListenerQuiesceTest`: a `ThreadPoolTaskExecutor` that has never had `initialize()` called is passed to `isQuiesced` (via whatever access the existing test class already uses for this package-private method)."*

Per M1 there is no such access. `isQuiesced` is `private static` (`:351`) and the existing test class never touches it — it only calls `listener.quiesceAsyncExecutors(ctx)`. A dev agent following this bullet will go looking for a pattern that does not exist. The three real options are: widen `isQuiesced` to package-private (a source change **no AC authorizes**, and AC6's fourth bullet explicitly says "confirm the diff touches only the two spots named above"); use reflection; or test through `quiesceAsyncExecutors` with an uninitialized executor in the context.

The last option is probably what is wanted, but it changes the test's shape and is worth deciding before implementation rather than during. Finding 6's rationale sentence ("new exposure on a method that can now be called directly") should also be struck — `isQuiesced` has no new exposure.

### C4 — MEDIUM: Finding 4's route enumeration is presented as exhaustive and is not

Finding 4 opens: *"six routes with `meta: { requiresAuth: true }` and no role gate ... confirmed by direct read of the full route table."* There are **eleven**:

| Line | Route | In AC4? |
|---|---|---|
| 112 | `parent/create-player` | **No — same defect class, unmentioned** |
| 117 | `parent/dashboard` | Yes |
| 234 | `player/profile-builder` | **No — same defect class, unmentioned** |
| 241 | `player/home` | Yes |
| 247 | `player/locker-room/:playerId` | Yes (but see C1) |
| 253 | `player/development/:playerId` | Yes |
| 304 | `messaging` | No — legitimately shared |
| 327 | `dashboard` | No — legitimately shared, and is `DEFAULT_ROUTE` |
| 333 | `profile` | No — legitimately shared |
| 339 | `admin` | Yes |
| 344 | `admin/health-dashboard` | Yes |

Scoping AC4 to the ledger's named routes is a defensible decision. Asserting the set is complete after a "direct read of the full route table" is not, and `parent/create-player` / `player/profile-builder` are persona-specific — they belong either in AC4 or in the "out of scope, deliberately" note, not unmentioned.

**And a fourth inert spelling exists, on a route this story edits.** `routes.js:204-209`:

```js
{
  path: 'parent/player/:playerId/subscription',
  name: 'player-subscription',
  component: () => import('pages/parent/PlayerSubscriptionPage.vue'),
  meta: { requiresAuth: true, requiresParent: true },
}
```

The guard derives `requiresParent` from `meta.role === 'PARENT'` (`index.js:50`) and never reads `meta.requiresParent`. So this gate is **inert** and `PlayerSubscriptionPage` — one of the three pages AC1 is fixing — is reachable by any authenticated role today. Finding 4's "Out of scope" note counts only `role: 'COACH'` as the third spelling and misses this one. Worth at least naming; arguably worth fixing in AC4 since the story is already in this file for AC1.

### C5 — MED-LOW: Finding 5's regression-surface claim is narrower than stated, and AC8's suite list is short one IT

Finding 5: *"Confirmed no existing test depends on the current ordering"* — established from `ReviewSubmissionServiceTest` and `ReviewSubmissionServiceConcurrencyIT` only. A third file asserts on both error codes: `src/test/java/com/softropic/skillars/platform/reviews/api/ReviewUpdateIT.java` — `reviews.updateTooSoon` at `:156` and `:270`, `reviews.editNotPermitted` at `:436`.

**I verified the claim still holds**, which is why this is not rated higher:
- `updateReview_blockedStatus_returns403` (`:417`) only runs `UPDATE ... SET moderation_status = 'BLOCKED'`; `last_modified_at` stays at the fixture's 400-days-ago value (`:119-131`), far outside the 30-day cooldown. The cooldown guard never trips, so the reorder does not change the outcome.
- Both `updateTooSoon` cases leave `moderation_status = 'APPROVED'`, so the status guard never trips.

But AC8's targeted backend list is `DisputeServiceTest, ReviewSubmissionServiceTest, ReviewSubmissionServiceConcurrencyIT, DatabaseResetTestExecutionListenerQuiesceTest` — **`ReviewUpdateIT` is absent**, and it is the only API-level coverage of both error codes for `updateReview`. Add it to AC8.

### C6 — LOW: Design B's new block lands immediately before an existing `if (!ownerEligible)`

The real code at `DisputeService.java:91-102` is already:

```java
boolean ownerEligible = raisedBy.equals(booking.getParentId()) || raisedBy.equals(booking.getPlayerId());
if (!ownerEligible) {
    // Code review (2026-08-25): a suspended coach must not be able to raise a dispute either ...
    ownerEligible = coachProfileRepository.findById(booking.getCoachId())
        .filter(cp -> cp.getStatus() != CoachProfileStatus.SUSPENDED)
        ...
}
```

Inserting Design B verbatim produces two consecutive `if (!ownerEligible)` blocks. That is **correct** — the second only runs when the first left it false, so there is no overwrite — but the Design never shows the interaction, and it does not say to preserve the four-line Deferred-63 comment attached to the existing block. Worth one sentence so the dev agent doesn't "tidy" them into one block or drop the comment.

Minor side effect worth noting in the Design: every **coach**-raised dispute now pays one extra `playerProfileRepository.findById` before reaching the coach lookup. Negligible, but it makes "only on the non-parent path" slightly rosier than reality.

### C7 — LOW: `?? null` changes the garbage-param path, not just the Tsid path

Old: `isNaN(Number('abc'))` → `null` → `loadPortal`'s `if (!id) return` (`ParentDevelopmentPortalPage.vue:152`) short-circuits, page renders empty.
New: `'abc' ?? null` → `'abc'` → truthy → six API calls fire (`fetchSkillDefinitions`, `fetchRadarDisplay`, `fetchExposure`, `fetchNarrative`, `fetchCoachContributions`, `loadPlayerPacks`) and 400/403.

Low severity: the param comes from the router and is normally a Tsid, and deferred-147 accepted the same trade. But it is a real behavior change the story describes as "for free," and worth one line in Design A.

### C8 — LOW: `ParentPlayerPortalPage.vue`'s non-reactive `playerId` leaves component reuse unhandled

`:76` is a plain `const`, and that file has **no** `watch(() => route.params.playerId, ...)` — unlike the other two pages (`ParentDevelopmentPortalPage.vue:174-177` has one; the subscription page's `playerId` is a `computed`). Design A keeps it a plain `const`, so param-only navigation between two children still won't reload.

Currently dormant for the same reason that downgraded C1: the route is unlinked, so every arrival is a full page load that re-mounts cleanly. (The same is true of `PlayerSubscriptionPage`'s route, `parent/player/:playerId/subscription` — also unreferenced by any in-app navigation.) Pre-existing and genuinely out of scope, but AC1's framing ("every parent-facing page targets the real player id") reads as if this file ends up fully correct. One sentence acknowledging it avoids a false sense of completeness.

### C9 — LOW: the frontend half of AC8 is verified by a non-gating job

AC8 leans on "GitHub CI is the sole full-verification gate." `.github/workflows/frontend-unit-tests.yml`'s own header states it is "NOT referenced by ci.yml or pr-build.yml", "NOT a required status check", and "a red frontend suite still does not block a merge." It **does** auto-trigger on a PR touching `src/frontend/**` (deferred-129 AC2), so it will run for this story — but its result is advisory. Four of this story's new test files are frontend. AC8 should say explicitly that the dev agent must read that job's result rather than relying on merge-blocking.

### C10 — LOW: AC5's new IT is achievable, but only via a mechanism AC5 doesn't state

The choreography works (see §6 — I initially believed it did not), and it works for a specific reason worth writing into the AC, because a dev agent is unlikely to get it right from AC5's current wording alone.

- **The hold-open wrapper is mandatory, not incidental.** `AdminReviewService.blockReview` is plain `@Transactional` (`:129`) with default REQUIRED propagation and no `REQUIRES_NEW`, so when the test wraps the call in `transactionTemplate.execute(...)` it **joins** that outer physical transaction and its row lock stays held until the outer lambda returns. That is exactly how the existing IT holds caller A open. A naive "two independent concurrent service calls" race does **not** work: whichever caller arrives second exhausts its own NOWAIT bounded retry and throws a lock-conflict error instead of ever reaching the re-check. AC5 should say "mirror the existing test's `transactionTemplate.execute` + `CountDownLatch` hold-open wrapper, substituting `blockReview` for `updateReview` as caller A."
- **Retry budget.** B reaches `coachReviewRepository.findByIdForUpdateNoWait` while A holds the lock, so B depends on `lockRetryer.withBoundedRetry` outlasting A's hold. The existing IT manages this by releasing A after `Thread.sleep(300)`. Hold A longer and B fails with a lock conflict rather than `EDIT_NOT_PERMITTED` — a flaky third outcome AC5 doesn't anticipate. Mirror the 300ms release.
- **`blockReview` does far more than bump two fields.** Inside A's held transaction it also calls `reviewFlagRepository.resolveAllOpenFlags`, `coachRatingService.recompute(...)` (whenever `previousStatus == APPROVED` — which the existing fixture seeds), writes a `ReviewModerationLog`, and publishes an event. It also throws `ALREADY_BLOCKED` if the row is already `BLOCKED`, so the fixture must seed `APPROVED`/`PENDING`. Finding 5's "sets both ... in the same write" is true but incomplete.

The project's known `REQUIRES_NEW` row-lock self-deadlock hazard does **not** apply here — neither method uses `REQUIRES_NEW` on this row.

### C11 — LOW: Finding 7 has no entry in the References section

References lists sources for Findings 1, 2, 5, 3/4 and 6. Finding 7's source ("code review of skillars-deferred-142") is correctly identified in the body and Dev Notes but never added to References — the one finding with no reference line. Also fix the References entry for Finding 2, which says `DisputeService.java:90` while the body correctly says `:91`.

### C12 — LOW: Design B uses `findById` against the repository's own written guidance

`PlayerProfileRepository.java:35` carries an explicit instruction: `/** Always use this instead of findById — parentId enforces family isolation. */` above `findByIdAndParentId(Long id, Long parentId)`. Design B uses plain `findById`.

This is **not** a new violation — the precedent the story cites does the same thing (`ReviewSubmissionService.java:253`), and the usage is legitimate: both are resolving an id the caller already supplied via a trusted booking row in order to *decide* ownership, not fetching a profile to expose its data. `findByIdAndParentId` cannot express the question Design B is asking (it needs `userId`, not `parentId`). Worth one sentence in Design B acknowledging the deviation so a reviewer doesn't flag it later, since the comment reads as absolute.

---

## 6. What did not survive re-verification

These were raised by a layer or by my own first pass and were **killed or downgraded** in the adversarial re-check. Listing them is the point of that pass.

| Candidate finding | Why it died |
|---|---|
| **"AC5's concurrency IT is impossible — B holds a `PESSIMISTIC_WRITE` lock, so A's `blockReview` can never commit while B waits."** I believed this for a while: `blockReview` (`AdminReviewService:129-135`) is `@Transactional` and its first read is `findByIdForUpdateNoWait` on the same row. | **Refuted by reading the existing IT in full.** `ReviewSubmissionServiceConcurrencyIT:289-375` runs the race the other way: **A** holds its transaction open via `transactionTemplate` + latch, **B** starts and its `findByIdForUpdateNoWait` retries against A's held lock via `lockRetryer`, then A commits and B's retry succeeds — at which point `entityManager.refresh(locked, PESSIMISTIC_WRITE)` sees A's fresh state. Substituting `blockReview` for A works identically: B's refresh sees `BLOCKED` **and** a bumped `lastModifiedAt`, so both guards trip and the reorder decides the error. The test is well-formed and red/green-able. Downgraded to the two practical hazards in C10. |
| "Design B will throw `InvalidDataAccessApiUsageException` on `findById(null)`." | **Refuted.** `Booking.java:33-34` — `@Column(name = "player_id", nullable = false, updatable = false)`. Not nullable. |
| "AC4's `role: 'PLAYER'` on `player/development/:playerId` breaks the nav link deferred-147 just shipped." | **Refuted.** `MainLayout.vue:330`'s `developmentRoute` is driven by `selfPlayerId`, which is fetched only under `watch(() => authStore.isPlayer, ...)`, and `MainLayoutSpec.js` has an explicit case: *"does not show the Development Dashboard link for a non-PLAYER role"*. The link is PLAYER-only. A full sweep finds `MainLayout.vue:330` is the **only** inbound navigation to that route. Safe. (The parent equivalent is a separate route, `parent/players/:playerId/development`, already `role: 'PARENT'`.) |
| "AC4's `role: 'PARENT'` on `parent/dashboard` breaks the MainLayout nav item." | **Refuted.** `MainLayout.vue:180`'s `<q-item clickable to="/parent/dashboard">` sits inside `<template v-if="authStore.isParent">` (`:177`). The other two callers (`CreatePlayerProfilePage.vue:202`, `ParentApprovalPage.vue:63`) are parent-only flows, and it is `ROLE_ROUTES.PARENT`, so `routeForRole` is self-consistent. |
| "Design C may be a no-op (or may break the gate) if `matched[i].path` is the relative child segment `command-center`." | **Refuted at the source.** `vue-router.mjs:645-646` mutates the normalized record's `path` to the full absolute path. With parent `'/'` and child `'coach/command-center'`, the record path is `/coach/command-center`. Design C is correct as written. |
| "`roles: ['ADMIN']` will silently no-op because the store holds `ROLE_ADMIN`." | **Refuted.** `auth.store.js:16-19` compares bare strings, and the cookie carries `"role":"COACH"`-style bare values. |
| "`**/*.jar` will exclude `.mvn/wrapper/maven-wrapper.jar` and break the Docker build." | **Refuted three ways.** `.mvn/wrapper/` does not exist in this repo; `mvnw`/`mvnw.cmd` are already excluded at `.dockerignore:60-61` with the comment "the builder stage uses the maven base image's own `mvn`"; `Dockerfile:2` is `FROM maven:3.9-eclipse-temurin-17`. A repo-wide `find` turns up no `.jar` outside `target/` and `node_modules/`. The runtime stage's `COPY --from=builder /app/target/skillars-*.jar` reads from the builder image, not the build context, so `.dockerignore` does not apply. |
| "`**/*.log` / `**/*.diff` could exclude something inside the deliberately-included `.git/`." | **Refuted.** `find .git -name "*.log" -o -name "*.diff" -o -name "*.jar" -o -name "*.iml"` → zero matches. (`.git/logs/HEAD` has no extension, so `*.log` does not match it.) |
| "Finding 1's claim that the watcher guard 'is unconditionally true whenever `newId` is truthy' is imprecise." | **Refuted — the story is exactly right.** `ParentDevelopmentPortalPage.vue:188` is `if (newId && newId !== playerId.value) {`. With a `string` left side and a `number` right side, `!==` never coerces, so the condition reduces to `newId` being truthy. Same at `ParentPlayerPortalPage.vue:88`. |
| "`PlayerSubscriptionPage.vue` has more than the four cited `playerId` reads." | **Refuted.** Grep confirms exactly four `.value` reads (`:215`, `:257`, `:260`, `:280`) and no template or numeric use. The enumeration is complete. |
| "Prop types need widening somewhere, as in deferred-147's `SkillsRadarAssessmentPanel`." | **Refuted.** Every `playerId` prop in `src/frontend/src/components/` is already `[Number, String]`, including the grandchild `GenerateReportDialog.vue:47`. The story's conclusion is right even though its supporting premise (M2) is false. |
| "Design C will newly fire the profile-builder gate for child routes of `/coach/command-center`." | **Refuted as moot.** `routes.js:77` declares it as a flat child of `'/'` with no `children`, so `to.matched.some(...)` matches exactly the same navigations `to.path ===` did, plus the two non-canonical spellings this story is closing. |
| "The frontend Vitest suite never runs in CI, so AC1/AC3/AC4's tests are unverified." | **Refuted, downgraded to C9.** The workflow auto-triggers on any PR touching `src/frontend/**` (deferred-129 AC2). What survives is only that the job is non-gating. |
| "AC2's new test will fail on unlisted preconditions (`VALID_REASONS`, `ELIGIBLE_STATUSES`, the dispute window's `configService.getBoundedLong` stub, `findOpenByBookingId`)." | **Downgraded to a non-finding.** All of these are real preconditions, but AC2 says to mirror `raiseDispute_coachOwnsBooking_isEligible`, which already satisfies every one of them. The instruction is adequate. |
| "`ReviewUpdateIT` will break on the reorder." | **Refuted** by reading each of the three cases' fixture state — see C5. The claim survives only as an incomplete-verification and missing-from-AC8 note. |
| "`DisputeServiceTest.setUp()`'s constructor-arg math is wrong." | **Refuted.** 12 `final` fields, 12 mocks, constructor call in matching order; appending the field last appends the 13th parameter. Design B and the Dev Notes are correct. |
| "The `.DS_Store` cache-bust mechanism is wrong because BuildKit keys on content, not mtime." | **Downgraded to not worth reporting.** The story says "changing its mtime," which is imprecise under BuildKit, but Finder rewrites `.DS_Store`'s *contents* too, so the cache does bust and the conclusion holds. Five nested `.DS_Store` files were confirmed present under `src/`. |
| **C1 rated High.** | **Downgraded to Medium.** The button's host route `parent/players/:playerId/sessions` (`routes.js:135-138`) has no `name` and no inbound navigation anywhere in the frontend; the parent dashboard's player cards are plain `<div>`s, not links. Reachable only by direct URL/bookmark. The defect in Finding 4's persona premise is unchanged; only the blast radius shrank. |
| "Reordering the guards changes NPE exposure on a null `lastModifiedAt`." | **Refuted.** `CoachReview.lastModifiedAt` is `@Column(nullable = false)` with a field initialiser, and legacy NULLs were backfilled by migration V157. No NPE exists to change. |
| "`quiesceAsyncExecutors` might enumerate a lazily-created executor bean that is genuinely uninitialized, making AC6's `isQuiesced` guard reachable after all." | **Refuted.** It enumerates via `ctx.getBeansOfType(ThreadPoolTaskExecutor.class)` (`:295-296`), and `getBeansOfType` eagerly initializes any matching bean before returning it — so anything it yields has already run `afterPropertiesSet()`. All six production pools are `@Bean`-declared `GracefulShutdownTaskExecutor`s (`OutboxConfig:39`, `DevelopmentConfig:66,105`, `notification/AsyncConfig:38,54`, `infrastructure/AsyncConfig:34`). The story's "unreachable today" is correct, and AC6's guard is genuinely defensive. |
| "The reorder changes behavior for `UNDER_REVIEW` in a way nothing covers, or some frontend code branches on which error code comes back." | **Refuted on the second half, immaterial on the first.** `UNDER_REVIEW` is reachable in production (`ReviewFlagService.java:161`), but no frontend code branches on `UPDATE_TOO_SOON` vs `EDIT_NOT_PERMITTED` — both are flat i18n lookups (`en-US:396-397` and the two sibling locales). No consumer can regress on the swap. |
| "There is a second `booking.getPlayerId()`-compared-to-a-User-id site the story missed." | **Refuted.** A backend-wide sweep of `getPlayerId()` comparison sites returns only `DisputeService.java:91` plus `MessagingService.java:299,378` and `MessagingReportService.java:142` — and all three messaging sites compare a PlayerProfile PK to a PlayerProfile PK, the same ID space. Finding 2 is the only instance. |
| "Finding 3's `to.matched.length === 2` is misleading because there is no `/coach` parent route." | **Not a finding.** True that `coach/command-center` is a flat relative child of the single `path: '/'` wrapper (`routes.js:4,77`) rather than a child of a `/coach` parent — but the story never claims otherwise, and the number it states is correct. |

---

## 7. Recommendation

**Fix before implementation (blocking):**
- **C1** — decide the `player/locker-room/:playerId` gate. `roles: ['PLAYER','PARENT']` is the lowest-risk option, uses the already-proven mechanism, and is the only one that matches `PlayerOwnershipGuard`. Shipping singular `role: 'PLAYER'` puts the router in conflict with the authorization model deferred-147 just fixed.
- **C3** — rewrite AC6's third bullet and strike M1's false rationale from Finding 6. As written the bullet cannot be satisfied without a source change no AC authorizes — and AC6's own fourth bullet forbids one.

**Fix before implementation (cheap, prevents wrong work):**
- **C2** — either fold `BookingRequestPage.vue:247` into AC1 or state explicitly that it is deferred; stop claiming the sweep found all sites. This is the highest-impact defect in the set: it corrupts an id on a write path reachable from a linked, primary CTA.
- **C4** — correct "six" and add the `routes.js:208` inert `requiresParent` to the out-of-scope note (or to AC4).
- **C5** — add `ReviewUpdateIT` to AC8's targeted list.
- **C10** — write the `transactionTemplate` hold-open mechanism into AC5's IT bullet; the current wording does not describe a test that works.
- The `router/index.js:48-49` / `:80-83` drift (§2) — these land on neighbouring working code.
- **M2**, **M3**, **M4** — correct the three overstated claims. Each has the right conclusion but a wrong premise, which is exactly the shape that makes a dev agent skip a real check.

**Worth a sentence each, not blocking:** C6, C7, C8, C9, C11, C12, M6.

**Confidence, per claim rather than overall.** Highest confidence on the verdicts I re-derived from primary sources myself: the `isQuiesced` visibility (read the signature, the introducing commit's diff, and the test class — three independent confirmations), Design C's correctness (read vue-router 4.6.4's own matcher at `vue-router.mjs:645-646` and `:486`), C2's end-to-end reachability (read both ends of the navigation and the query-string construction), C1's authorization-model conflict (read `PlayerOwnershipGuard.check` in full), the 11-route enumeration in C4, and the `.dockerignore` safety analysis (inventoried what the build context actually contains rather than reasoning from the patterns alone).

Lower confidence, flagged as such: **C5**'s "still harmless" rests on reading all three `ReviewUpdateIT` cases' fixture state — solid — but its DoD-gap framing is a judgment call. **C10**'s retry-budget hazard is inferred from the existing IT's own 300ms comment rather than from a failing run. **C1**'s severity downgrade rests on a negative result (no inbound navigation found); negative greps are the weakest evidence in this report, so if any navigation into `parent/players/:playerId/sessions` is added later, C1 returns to High.

What was **not** independently executed: no tests were run, no `docker build` was performed (AC7 itself permits reasoning from documented semantics), and AC3's router-factory test vehicle was verified only statically. For that last one the three things that could have broken it all check out — the hand-maintained `#q-app/wrappers` alias in `vitest.config.mjs`, that target exporting `defineRouter` as well as `defineBoot`, and `index.js:22`'s factory taking no destructured parameters, so `createRouterFactory({})` is fine — and under Vitest `process.env.VUE_ROUTER_MODE` is unset so the factory falls to `createWebHashHistory`, which coincidentally matches `quasar.config.js:40`'s real `vueRouterMode: 'hash'`. But nobody has booted it under happy-dom, so treat Task 7 as the one task with unverified feasibility.

The story is well above average for this repo: citation accuracy is high (30 of 39 exact, including all nine `.dockerignore` line numbers), its highest-stakes technical claim — Design C's `to.matched.some(...)` rewrite — is correct for the right reason, its scoping discipline is genuine, and its most load-bearing mechanistic claims about Spring and vue-router internals hold up against the actual installed sources. The defects cluster in two specific habits: asserting an enumeration is complete after a grep narrower than the claim (C2, C4, C5, M2), and carrying a ledger assertion forward without re-deriving it (M1, and the `:90` References drift) — the latter being the exact failure mode this review process exists to catch, and in M1's case the story's own Design section already contradicted the error it inherited.

What I did **not** independently execute: no tests were run, and AC3's router-factory test vehicle was verified only statically (the `#q-app/wrappers` alias, the `defineRouter` export, and `quasar.config.js:40`'s `vueRouterMode: 'hash'` all check out, but nobody has actually booted the factory under happy-dom). AC7's Docker behavior was reasoned from pattern semantics plus a full inventory of what the context contains, not from a `docker build` size comparison — which is what AC7 itself permits.

The story is well above average for this repo: its citation accuracy is high, its highest-stakes technical claim (Design C) is correct for the right reason, and its scoping discipline is genuine. The defects cluster in two specific habits — asserting an enumeration is complete after a grep that was narrower than the claim (C2, C4, C5, M2), and carrying a ledger assertion forward without re-deriving it (M1, and the `:90` References drift).

---

# Round 2 — re-review of the updated story

**Date:** 2026-10-07 · **HEAD:** still `ef2daac9` · **Scope:** verification that the 12 findings above were applied correctly, plus a fresh check of the *new* text for defects the fixes themselves introduced.

All 12 were applied, and three deserve credit for being handled better than the finding asked:

- **C3** — resolved by explicitly authorizing the `isQuiesced` visibility bump, *and* AC6's fourth bullet was reworded to reconcile with it ("confirm the diff touches only `beforeTestMethod`'s `try`/`finally` wrap and `isQuiesced`'s signature/body (the one-line visibility change is part of this AC, not scope creep beyond it)"). That contradiction was my main concern with this resolution and it is closed.
- **C1** — fixed to `roles: ['PLAYER', 'PARENT']`, with a new AC4 bullet that tests the regression directly ("a PARENT navigating to `/player/locker-room/:playerId` is **not** redirected") and a new Dev Note naming `PlayerOwnershipGuard` as the source of truth for this route. The fix is load-bearing and is now documented as such.
- **C7** — the `?? null` deviation was dropped entirely in favour of deferred-147's verbatim bare form, with the garbage-param trade-off disclosed rather than silently accepted.

## R2-1 — HIGH: the C2 fix drops a validation gate that was load-bearing, on the story's own false premise

**This is a new defect, introduced by the fix for C2 — not a pre-existing one.**

Design A's `BookingRequestPage.vue` rewrite drops the `Number`/`isFinite`/`> 0` check and justifies it:

> "Drops the `Number`/`isFinite`/`> 0` validation entirely — it only ever existed to sanity-check a parsed number, which no longer exists once the value stays a string, and **it never actually excluded a non-numeric garbage value any more strictly than a bare truthy check does** (`Number('abc')` is already `NaN`, already fails `isFinite`, same outcome either way)."

The claim in bold is **false**. The `return` sits *inside* the inner `if`, so a value that fails validation does not return — it **falls through to the safe fallback**:

```js
const playerId = computed(() => {
  if (route.query.playerId && !authStore.isPlayer) {
    const parsed = Number(route.query.playerId)
    if (Number.isFinite(parsed) && parsed > 0) return parsed   // ← only valid input returns here
  }
  if (authStore.isPlayer) return selfPlayerId.value
  return playerStore.activePlayerId                            // ← garbage lands HERE today
})
```

Traced against the real logic (`BookingRequestPage.vue:245-252`):

| `?playerId=` | Today | After Design A as written |
|---|---|---|
| `abc` | falls through → `playerStore.activePlayerId` | returns `'abc'` |
| `0` | falls through → `activePlayerId` | returns `'0'` |
| `-5` | falls through → `activePlayerId` | returns `'-5'` |
| `1&playerId=2` | falls through → `activePlayerId` | returns the **array** `['1','2']` |

So the outcomes are not "the same either way" — they are opposite. The dropped check was a *gate* whose failure path was the fallback, not a redundant assertion.

**Why it matters here specifically**, and more than at the three `route.params` sites: this value is user-controllable via a query string rather than router-validated, there is a meaningful fallback branch to fall through to, and it feeds a **write** path. The page's own comment at `:295-296` names exactly this threat:

> "playerId IS gated: without it, single-booking submit had no safety net ... a self-booking player whose profile hasn't resolved yet (**or a malformed query string**) must not be able to submit with an undefined playerId."

And `canSubmit` (`:297`) is `!!playerId.value` — a truthy `'abc'` or `['1','2']` sails through it, so the guard that comment describes no longer fires. `playerId: 'abc'` then reaches `submitBookingRequest`'s payload (`:491`).

**Failure scenario:** a parent opens `/parent/coaches/<id>/request-booking?playerId=abc` (hand-edited, stale bookmark, or a truncated share link). Today the page quietly books for their active player. After this fix, `canSubmit` lets the submit through with `playerId: 'abc'` and the backend 400s — a working flow becomes a broken one. The array case is worse: `?playerId=1&playerId=2` posts a JSON array where a `Long` is expected.

**Fix — keep the string, keep the gate.** Replace the numeric parse with a shape check instead of deleting it:

```js
if (route.query.playerId && !authStore.isPlayer) {
  const raw = String(route.query.playerId)
  if (/^[1-9]\d*$/.test(raw)) return raw
}
```

`String(...)` collapses the duplicate-param array to `'1,2'`, which fails the test and falls through correctly. `^[1-9]\d*$` preserves the original `> 0` semantics exactly (use `^\d+$` if rejecting `'0'` is not wanted). Either way the fall-through to `playerStore.activePlayerId` is retained, which is the whole point.

AC1's `BookingRequestPage` bullet should also gain a negative case — `?playerId=abc` falls back to `playerStore.activePlayerId` and does **not** submit a garbage id — since the existing `BookingRequestPageSpec.js` case being added only covers the happy path.

## R2-2 — note: the four AC1 sites are no longer one uniform shape

The updated story describes C2 as folded in "since it is the same one-line shape" (header note) and Task 1 treats all four files as one edit. Three of them genuinely are a one-line `Number(...)` → bare-param swap. `BookingRequestPage.vue` is not: it is a three-branch computed with a validation gate and a fallback (see R2-1). Worth one sentence in Task 1 so the dev agent does not apply the uniform transformation mechanically — that is precisely how R2-1 would ship.

## Round 2 — what did not survive re-verification

| Candidate | Why it died |
|---|---|
| "AC6's new visibility bump contradicts AC6's own 'diff touches only the two spots' bullet." | **Refuted** — the updated bullet explicitly reconciles it (quoted above). |
| "Widening `isQuiesced` to package-private won't let the test call it, since the test is in a different package." | **Refuted.** `DatabaseResetTestExecutionListenerQuiesceTest` is in `com.softropic.skillars.config`, the same package as `DatabaseResetTestExecutionListener`. A package-private static method is directly callable. |
| "`roles: ['PLAYER','PARENT']` might double-gate against the singular `requiresPlayer` derivation and bounce a PARENT anyway." | **Refuted.** `requiresPlayer` derives from `meta.role === 'PLAYER'` (`index.js:51`); the corrected meta sets `roles` only, with no `role` key, so only the `rolesMeta.includes(authStore.role)` branch (`:84`) applies — and it accepts both. |
| "Replacing the inert `requiresParent: true` on `parent/player/:playerId/subscription` newly gates a route that was open, which could break an inbound link." | **Refuted as moot** — that route has no in-app navigation either (same orphan check as C1/C8), so there is no link to break. Gating it is a strict improvement. |

## Round 2 recommendation

One blocking item: **R2-1**. The C2 fix is correct in intent and the site genuinely belonged in AC1, but as written it trades a read-path id corruption for a write-path validation hole, on a stated premise that the code contradicts. The corrected form is three lines and keeps everything the finding wanted.

R2-2 is a one-sentence clarification.

Everything else in the updated story holds. Confidence on R2-1 is high: the fall-through behavior was confirmed by reading the real computed at `BookingRequestPage.vue:245-252` and then executing the branch logic over the five input shapes in the table above, rather than reasoning about it.
