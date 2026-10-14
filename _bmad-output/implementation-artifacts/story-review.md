# Story Review — `skillars-deferred-151`

| | |
| :--- | :--- |
| **Story** | `skillars-deferred-151-coach-ui-gaps-handler-hygiene-null-roles-fix-time-gate-and-ci-ruleset-hardening` |
| **Story status** | `ready-for-dev` (read from `sprint-status.yaml`'s `development_status` map, line 354 — not from the changelog comment) |
| **Audit date** | 2026-10-14 |
| **Real HEAD at audit time** | `34f9726a` — *"Expose coach booking cancellation and no-show reporting in the UI (#264)"* |
| **Verification basis** | Every citation was checked against the working tree at `34f9726a`. The story's own Context section also claims HEAD `34f9726a`, and that claim happens to be correct — but it was **not** taken on trust; `git rev-parse --short HEAD` was run first and all verification was anchored to that. |
| **Method** | 4 parallel read-only verification layers (citations / ledger attribution / mechanistic claims / corner cases), followed by a mandatory adversarial re-verification pass (Step 4b) in which the orchestrator personally re-read the evidence for every finding, trying to refute it. |
| **Claims checked** | 35 code citations, 17 ledger/precedent attributions, 24 mechanistic claims, 9 ACs walked against current code paths. |
| **Outcome** | **5 BLOCKING**, **13 SHOULD-FIX**, **8 NOTE** survived Step 4b. **3 findings were killed or downgraded** by Step 4b (§6). 22 story assumptions independently confirmed SOUND (§7). |

> **Headline:** the story is unusually well-researched — its `recordNoShowCoach` guard quote is character-for-character exact, its `JwtManagerImpl` drift correction is genuine and correct, and 22 separate assumptions check out. But **AC3 and AC4 cannot be implemented as written**: AC4 targets a defect that provably cannot occur (and which this codebase has already twice filed and withdrawn as factually wrong), and AC3's prescribed upload flow is guaranteed to 403 on every save because of a latent backend id-space bug the story explicitly declares absent.

---

## 1. Citation verification (Layer 1)

All 35 citations resolve to real code; **no citation was fabricated**. The failures are range drift, concentrated in ranges the implementer is told to edit.

### 1a. Consequential drifts — these would misdirect an edit

| # | Story's citation | Verdict | Real location | Evidence |
| :-- | :--- | :--- | :--- | :--- |
| 1 | `SessionPackPaymentResource.java:93-125` = GET/POST tiers + PATCH deactivate | **DRIFTED (both ends)** | extend = `:93-99`; GET = `:103-108`; POST = `:110-118`; PATCH = `:120-126` | `:93 @PostMapping("/session-packs/{purchaseId}/extend")`; `:101 // ─── Coach: manage tiers ───`; `:103 @GetMapping("/coaches/me/session-pack-tiers")`. The story cites `:93-125` for tiers **and** `:93-99` for extend — the overlap is an error, not two coexisting things. `:93-125` starts inside an unrelated method and clips the PATCH's closing brace. |
| 2 | `ci.yml:238-248` = "Build and push Docker image" step; AC7 says insert "immediately after" it | **DRIFTED (tail clipped)** | `:238-255` — and `255` is EOF | `:248 commit-sha:` is mid-step; `:249 labels: |` continues through `:255 org.opencontainers.image.created=…`. **Inserting after `:248` corrupts the step.** |
| 3 | `ReviewResource.java:44-52` = `listMyReviews` | **DRIFTED (off by 8)** | `:52-61` | `:44 public class ReviewResource {`; `:45-50` are injected fields; `:52 @GetMapping("/coaches/me")` … `:61 }`. All *content* claims (role gate, response type) correct. |
| 4 | `MainLayout.vue:144-161` = Coach nav section | **PARTIAL** | `:144-174` | Content claim exact (only Command Center `:148-155`, Revenue `:157-164`, Messaging `:166-173`). Cited range ends mid-Revenue-item — and AC1–3 all add entries into this block. |
| 5 | `ProfilePage.vue:240-260` = coach-photo section "gated `v-if="authStore.isCoach"`" | **PARTIAL + misattributed gate** | row is `:241-261`; the `isCoach` gate is at **`:126`** | Only `v-if` inside the cited range is `:251 v-if="!isCoachSuspended"`. The `isCoach` gate is the enclosing section: `:126 <div v-if="authStore.isCoach" class="glass-card profile-section">`. True of the page, false of the cited lines. |

### 1b. Minor tail-clipping drifts (content correct)

| Story | Real |
| :--- | :--- |
| `ReviewResource.java:137-146` (`submitCoachResponse`) | `:137-148` |
| `CoachBrandingResource.java:29-42` | `:29-44` (GET `:29-35`, PUT `:37-44`) |
| `payment.store.js:96-103` (tier load action) | `:95-105` |
| `BookingService.recordNoShowPlayer:837-858` | `:838-858` (`:837` is `@Transactional`) |
| `ci.yml` `:latest` guard comment `:131-133` | sub-NOTE exact at `:131-133`; full banner `:121-134` |
| `JwtManagerImpl` `ROLE_PRECEDENCE` "around `:114-134`" | decl `:133-134`; rationale comment `:114-132`; consumption `:147-150` — "around" is fair |

### 1c. Exact matches worth recording (the story is right where it matters most)

- **`BookingService.java:870-874`** — the story's fenced `recordNoShowCoach` guard quote is **character-for-character identical** to real source, modulo a uniform de-indent. Zero drift.
- **`JwtManagerImpl.java`** — all five sub-citations exact: `getAuthoritiesSilently` `:167-175`, `setSkillarsProfileCookie` `:136-153`, `refreshLoginToken` `:80-99`, `createAndSetJwt` at `:94`, `setSkillarsProfileCookie` call at `:95`, ANONYMOUS fallback `:150`. **The story's drift correction from the ledger's `:155-162` to `:167-175` is genuine and correct.**
- **`CoachCommandCenterPage.vue`** — all eight cited ranges exact, including `submitCoachReschedule`'s catch at `:739-760` and `notifyIfScheduleStale` at `:582-586`.
- **`BookingError.java:76`** (`NO_SHOW_TOO_EARLY`) and `:108` (`-> "booking.noShowTooEarly"`) — exact.
- **All three "zero X exists" negative claims verified true**: `reviews.api.js` has exactly the five named exports; `/coach/payment-settings` has zero nav links (route definition only); `.github/` has zero ruleset references.

### 1d. Two citations the story made *worse* than the ledger it "corrected"

| Story | Ledger (more accurate) | Real |
| :--- | :--- | :--- |
| `ReviewResource.java:44-52` | `ReviewResource.java:52` | `:52-61` |
| `SessionPackPaymentResource.java:93-125` | `:93,103,110,120` | `:93-99` / `:103-108` / `:110-118` / `:120-126` |

This matters only against the story's own blanket claim (Dev Notes, line 363): *"every citation in this story was independently re-verified against HEAD `34f9726a`."* Two "re-verified" citations are less accurate than the source they replaced.

---

## 2. Ledger & precedent attribution (Layer 2)

Method per layer rules: each claim checked against **all three** of (a) `deferred-work.md`, (b) source comments/Javadoc near the code, (c) `git log --grep` / `git log -S` / `git blame`. A zero-hit grep in the ledger alone was never treated as proof of fabrication.

### 2a. Confirmed

| Claim | Sources checked | Verdict |
| :--- | :--- | :--- |
| All four mined ledger section headings exist verbatim with the claimed dates | (a) `:3757` deferred-149, `:3764` deferred-150, `:3770` UI-gap scan, `:3789` cancel-no-show | **CONFIRMED** |
| Every story-named bullet is present in its claimed section | (a) | **CONFIRMED** |
| "No `[CLOSED by …]` tag exists for any of the eight" | (a) grep over `:3750-3795`; (c) `git log -1 --` shows HEAD itself last touched the file, so no post-HEAD closure is possible | **CONFIRMED 8/8** |
| Ledger's `getAuthoritiesSilently` citation is `:155-162` and is stale | (a) `:3766` quotes it; (b) real method at `:167-175`; (c) `git show c873fb6c^` → method was at `:141`, `c873fb6c` → `:167` | **CONFIRMED** (see §2b for the *cause* being misattributed) |
| `recordNoShowCoach`'s guard shipped with `skillars-deferred-63` | (b) in-source comment `BookingService.java:867-869` *"Deferred-63 AC4: …"*; (c) three independent `git log -S` searches all converge on **`b918486e`, 2026-08-25, "Story Deferred-63: Product-Directed Fairness and Consistency Fixes"** | **CONFIRMED** |
| `assert-git-provenance.sh`'s optional `expected-commit-sha` was added by deferred-150's code-review response | (c) git shows **one** squashed commit (`c873fb6c`) that both creates the script and contains the arg — git alone cannot separate story from review; (b) script header comment *"code review 2026-10-09: without it, this gate only proves…"* resolves it; also deferred-150 Change Log `:464` | **CONFIRMED via (b)+(a)** — a reviewer checking only `git log` would have wrongly called this unverifiable. This is exactly the trap this layer exists for. |
| The `findQualifyingCompletedBookings` truncation `log.warn` exists and is deferred-150's | (b) `ReviewSubmissionService.java:315-317`, comment attributes it to *"code review 2026-10-09"*; (a) deferred-150 Change Log `:464` | **CONFIRMED** |
| deferred-149 applied the ruleset out-of-band via `gh api` | (c) deferred-149 story `:492`, `:568` quote it in detail | **CONFIRMED** (with a precision error — §2b) |
| All three "Explicitly NOT picked up" characterizations (human-only translator/browser items; `[deferred-19]` as monitoring) | (a) `:3407-3447`, `:3755` — the ledger's own stated deferral reasons match word-for-word in substance | **CONFIRMED** |

### 2b. Attribution problems that survive re-verification

1. **A fabricated word inside a quotation mark.** The story (line 110) quotes the ledger as *"revisit **only** if the truncation warning ever fires."* The ledger (`deferred-work.md:3768`) says *"**Revisit if** the truncation warning ever fires."* The inserted "only" converts a prompt into an exclusive precondition — and this quote is the stated basis for a USER DECISION to leave the item deferred. **Verified personally** by extracting the sentence from `:3768`.

2. **Finding 2's heading contradicts its own body, and the Change Log credits the story with correcting a ledger error the ledger never made.**
   - Story heading (line 27): *"…one has no client function at all"*
   - Story body (line 29): `extendSessionPack` ***"does** already have a client function"*
   - Ledger (`:3777`): *"all have frontend client functions already defined in `api/payment.api.js` … but zero call sites"*
   The ledger was right. An implementer reading the heading will go write a client function that already exists at `payment.api.js:18`. **Verified personally.**

3. **Wrong store action name.** Story line 29 and Design B cite a *"`loadMySessionPackTiers`-shaped action"*. The real name is **`fetchSessionPackTiers`** (`payment.store.js:95-105`). A direct grep for the story's name returns nothing — in a story whose AC2 says to extend that exact store "following this store's existing convention."

4. **The citation-drift *explanation* blames the wrong story.** Story line 53 attributes the `:155-162` → `:167-175` shift to *"two `skillars-deferred-150`/`-149` comment-block insertions."* `git blame -L 100,166` attributes the region to Deferred-142 (27 lines), `#263`/deferred-150 (26), `init` (13), Deferred-144 (1) — **zero lines from deferred-149**. The full `141 → 167` shift is deferred-150 alone. Conclusion correct, causation wrong.

5. **"The `NoDirectPush` ruleset was applied out-of-band per deferred-149's notes"** overstates it. deferred-149 applied **only the fourth rule** (`required_status_checks`) — its own Finding 6 (`:94`) says the ruleset *"carries only `deletion`, `non_fast_forward`, and `pull_request` rules — no `required_status_checks` rule at all."* The ruleset and its other three rules pre-date deferred-149 with no traceable origin in any story file. (If anything this *strengthens* AC8's case.)

6. **Unacknowledged reversal of a predecessor's explicit decision.** deferred-150 Dev Notes `:385`, verified personally: *"**AC6 is deliberately scoped to `pr-build.yml` only — this is a decision, not an oversight to "fix" by also touching `ci.yml`.**"* Story-151's AC7 does exactly that. The reversal is **legitimate** (the ledger's own "When picked up" prescribes the `docker pull` approach, which sidesteps deferred-150's stated objection about `load:`), but the story never says it is overriding that note.

7. **The AC5 test precedent is pointed at the wrong section, and its one actionable part is omitted.** The revert-and-rerun convention lives in deferred-150's **Review Findings / Debug Log** (`:349`, `:427`) and is *named* a convention in deferred-149's Tasks `:306` — not deferred-150's "Dev Notes" as story line 199 says. More importantly, deferred-150 `:349` warns that `Principal` re-sorts authorities so **no principal-based `JwtManagerImplTest` can discriminate**, and names **`buildTokenWithCustomClaims`** as the fix. AC5's test recipe cites the precedent but neither the helper nor the trap.

8. **Low severity — dead ledger citations inherited without re-anchoring**, despite the blanket re-verification claim: ledger's `payment.api.js` *"(lines 18, 25, 27, **103**)"* in a **100-line** file (verified: `wc -l` = 100); ledger's `ReviewSubmissionService.java:308` / `:302-312` for an ownership loop now at `:321+`.

### 2c. The load-bearing exclusion claim — **CONTRADICTED**

Story line 17 (repeated at line 113):
> *"A separate audit pass covering `deferred-work.md` lines 1–3271 (everything older than the tail sections this story mines) confirmed that region is already fully pruned/closed/`[DECIDED]`… this story's scope is deliberately the unmined **2026-10-02-through-2026-10-14** tail."*

Verified personally:
- `deferred-work.md` is **3795 lines**. Line 3271 is not a section boundary (`## Last audit: 2026-09-24 (skillars-deferred-133 …)`).
- The story's *own declared scope* is "2026-10-02 onward" — which begins at **`:3407`** (deferred-140, 2026-10-02), not 3757.
- Within that self-declared scope, the story mines only `:3757`+ and excludes only two bullets (deferred-140's human-only items, deferred-146's `[deferred-19]`).
- **Five sections inside its own declared scope are neither mined nor excluded**: deferred-141 (`:3449`), -142 (`:3467`), -143 (`:3544`), manual-review-during-143 (`:3630`), -145 (`:3732`).
- Measured over `:3449-3756`: **17 untagged bullets, 0 carrying `[CLOSED]`/`[DECIDED]`/`[OBSOLETE]`, and 16 `"When picked up:"` directives.** Three are security-adjacent, e.g. `:3475` *"`URLEncoder.encode` is form-encoding, not URI-component encoding, in all three `skp` writers"*; `:3605` *"`markUsedByTokenHash`/`markAllUsedByUserId` calls are unguarded against transient DB failures"*; and — directly adjacent to AC5's own file — *"`JwtManagerImpl.deleteLoginToken()` clears six cookies but not `skp`."*

**This is a repeat of an error a prior story review already corrected once.** deferred-150's Change Log `:461`: *"the ledger-exhaustiveness claim … was an overstatement contradicted by the ledger's own header convention and corrected; two genuinely-open tail bullets (the `/dashboard` `DEFAULT_ROUTE` nav gap, `markUsedByTokenHash` unguarded-DB-failure) were previously miscategorized as 'human-only or already closed'."* **Both named bullets sit inside the range this story's audit boundary excludes** (`:3453` and `:3605`).

**Recommendation:** either audit `:3449-3756`, or delete the line-17 sentence and the line-113 bullet. Do not leave an unverified exhaustiveness claim standing — a dev will read it as "the ledger is now empty."

---

## 3. Mechanistic claims (Layer 3)

### 3a. CONTRADICTED — the two that invalidate ACs as written

#### M1. **`loadCoachSchedule` can never reject — AC4's entire stated defect is unreachable.** `BLOCKING`

Re-read personally at `src/frontend/src/stores/booking.store.js:453-467`:
```js
453	  async function loadCoachSchedule(weekStart) {
454	    coachSchedule.value = null
455	    coachScheduleLoading.value = true
456	    coachScheduleError.value = null
457	    try {
458	      const res = await getCoachSchedule(weekStart)
459	      coachSchedule.value = res
460	      return true
461	    } catch (e) {
462	      coachScheduleError.value = e
463	      return false
464	    } finally {
465	      coachScheduleLoading.value = false
466	    }
467	  }
```
The only `await` is inside the `try`. The three preceding statements are plain ref assignments. **There is no input or failure mode under which this promise rejects.** Every HTTP/network/parse failure resolves to `false`.

And the store carries a contract comment written specifically about this claim — read personally at `:360-368`:
```js
360	  // CONTRACT — loadCoachBookingRequests and loadCoachSchedule below NEVER RETHROW. Each swallows its
361	  // own failure into coachRequestsError / coachScheduleError and resolves normally, so an `await` on
362	  // either can never reject and callers need no .catch() guard. Two independent review passes on the
363	  // same day (2026-08-18) filed the same "unguarded refresh → unhandled promise rejection" defect
364	  // against call sites of these loaders; both were withdrawn as factually wrong. The real residual is
365	  // the opposite one — a failed refresh is silent, because coachRequestsError / coachScheduleError
366	  // are rendered by no component — which is what the coach pages' stale-list warnings close
367	  // (skillars-deferred-31 AC1). Do not add a rethrow here without fixing those call sites;
368	  // BookingRequestPage relies on a failed fetch being swallowed rather than blanking the slot list.
```

Both halves of Finding 4 therefore fail:
- Story line 45: *"a rejection there throws past every `$q.notify` call below it … the user gets **zero** error feedback"* — **unreachable**.
- Story line 45: *"a refresh failure *after* a successful mutation is caught and reported as 'action failed'"* — **also unreachable**. A failed refresh returns `false`, driving `notifyIfScheduleStale(false)` → stale-warning toast, then the success toast. Control never enters the `catch`. The coach sees *"succeeded"* + *"list may be out of date"* — the correct message pair.

**This would be the third filing of a defect withdrawn twice as factually wrong.** Aggravating: the story *quotes the page-side half of the very comment that disproves it* (it cites `CoachCommandCenterPage.vue:573-581`, which says `loadCoachSchedule` *"swallows its own failures into coachScheduleError"* and points at `booking.store.js`) — the pointer was followed only partway.

**Also unimplementable:** AC4's prescribed Vitest coverage (*"proves a refresh rejection inside each handler's error path no longer propagates unguarded"*) is only writable by mocking `loadCoachSchedule` to reject — asserting against a store contract the real store explicitly forbids. That test would pass forever and protect nothing.

> **Re-scope AC4** to the half that is genuine — the mutation and the refresh share one `try` (`CoachCommandCenterPage.vue:591-593`), so a throw from the mutation and a refresh problem are indistinguishable in the catch — plus handler deduplication. Add a pointer to `booking.store.js:360-368` so a fourth pass doesn't refile it.

#### M2. **`authStore.coachTier` is `null` on a cold load — AC3's gate hides the feature from entitled coaches.** `BLOCKING`

Story line 39: *"The frontend already has the coach's live tier available **without a new call**: `authStore.coachTier` … is populated from the existing subscription-fetch path."* Verified personally — this is false on both counts.

Complete set of assignments in `src/frontend/src/stores/auth.store.js`:
```
13:  const coachTier = ref(null)        ← declaration
31:    coachTier.value = null           ← clearUser()
37:      coachTier.value = response.tier ← fetchCoachTier() only
39:      coachTier.value = null         ← its catch
```
`fetchCoachTier()` hits `GET /api/marketplace/coaches/me/tier` — **not** the subscription-fetch path. Repo-wide, it has exactly **one** caller:
```
src/frontend/src/pages/player/PlayerDevelopmentDashboardPage.vue:250
```
It is not called on login (`setUser`, `:21-25`), not in `hydrateFromCookie`, not in any boot file. And `grep -n "fetchCoachTier\|coachTier" src/frontend/src/pages/ProfilePage.vue` → **no matches at all**.

**Consequence:** on any cold load of `/profile` (direct URL, hard refresh, bookmark) `coachTier === null`, so `authStore.coachTier === 'ACADEMY'` is **false for a genuine ACADEMY coach** — Design C and AC3 render the upsell/locked state to exactly the users entitled to the feature. The name collision the story fell into: `paymentStore.fetchCoachTiers()` is a different action populating the tier *catalogue*, never `authStore.coachTier`.

> **Fix:** call `authStore.fetchCoachTier()` in `ProfilePage.vue`'s `onMounted`, and three-state the gate (loading / locked / unlocked) rather than reading the ref directly. Note the existing defensive precedent: `PerformanceReportsPanel.vue:83` guards `coachTier != null` explicitly.

### 3b. CONFIRMED

| # | Claim | Evidence | Verdict |
| :-- | :--- | :--- | :--- |
| M3 | `readValue((String) null, TypeReference)` throws `IllegalArgumentException`, not `JsonProcessingException`, escaping the catch | Jackson 2.21.7 (pom `jackson-bom.version`). Subagent probe against the exact resolved jars: `THROWN: java.lang.IllegalArgumentException :: argument "content" is null`. For contrast `""` → `MismatchedInputException` (a `JsonProcessingException`, correctly caught) | **CONFIRMED** (probe not re-run by orchestrator — §6) |
| M4 | Call order: `createAndSetJwt(res, claims)` at `:94` **before** `setSkillarsProfileCookie(res, claims)` at `:95` | `JwtManagerImpl.java:94-95`, read in full | **CONFIRMED** |
| M5 | The result is an HTTP 500 | `ApiAdvice` has **no** `IllegalArgumentException` handler (grep: zero). Falls to `:127-131 @ExceptionHandler(Throwable.class) @ResponseStatus(INTERNAL_SERVER_ERROR)` → `errorKey "generic.unknown"`. Confusable neighbor checked: `illegalStateExceptionHandler` `:643-648` handles `IllegalStateException`, a **sibling** type, not a supertype | **CONFIRMED** ("uncaught" is loose — `SecurityAdviceFilter:61-63` catches and resolves it — but the HTTP outcome is as predicted) |
| M6 | `SkillarsRole.ANONYMOUS` no-match fallback exists at `:150` and an empty authorities list reaches it | `:147-150 ROLE_PRECEDENCE.stream().filter(mappedRoles::contains).findFirst().orElse(SkillarsRole.ANONYMOUS)`. **Confusable neighbor flagged:** the same file statically imports `AuthoritiesConstants.ANONYMOUS` (a `String "ROLE_ANONYMOUS"`, used at `:200`) — two different `ANONYMOUS` symbols. The claim correctly concerns the enum | **CONFIRMED** |
| M7 | `instanceof String roles` replaces rather than adds a cast; available and lint-clean | `pom.xml:30 <java.version>17</java.version>`; pattern already used at `SecurityUtil.java:66,69,111-112` etc. No Checkstyle/PMD/SpotBugs plugin or config in the repo | **CONFIRMED** |
| M8 | `getBranding` degrades to `(null, null)`; `saveBranding` throws `FeatureGatedException` | `ReportGenerationService.java:262-264` returns `new CoachBrandingResponse(null, null)`; `:275-277` throws. Three confusable neighbors checked and excluded: the identically-named controller methods (`CoachBrandingResource.java:32,40`), a **third** tier gate inside `generateReport` (`:161-162`), and `buildPdf(… Optional<CoachBranding> …)` (`:352`). **Transaction boundary:** `getBranding` is non-transactional; `saveBranding` is `@Transactional` and throws **before any write** — no partial-write hazard | **CONFIRMED** |
| M9 | → HTTP 403 with `errorKey = "security.featureGated"` | `ApiAdvice.java:388-393`. Six other `FORBIDDEN` handlers checked; none handles `FeatureGatedException` | **CONFIRMED** |
| M10 | `StorageKeyGenerator.generate` has no entity allow-list | `:20-27 entity.replaceAll("[^a-zA-Z0-9_-]", "_")`; whole file is 35 lines, no allow-list. Whole request path also checked: `SignUploadRequest` has `@NotBlank String entity` only — no `@Pattern`, no enum; validation chain never sees `entity` | **CONFIRMED** |
| M11 | `submitCoachReschedule`'s catch does not refresh, unlike the other four | `:739-760` — pure error-key branching, no `loadCoachSchedule`, no `notifyIfScheduleStale` | **CONFIRMED** — and the story **out-audited the ledger** here (see §7) |
| M12 | `notifyIfScheduleStale(false)` produces a warning | `:582-586 if (!refreshed) { $q.notify({ type: 'warning', … }) }`. `false` and `undefined` both warn | **CONFIRMED** |
| M13 | `startSession`, `initiateQuickComplete`, `recordNoShowPlayer` have no time check | Full bodies read. `startSession` (`:49-60`): `resolveCoach` + `verifyCoachOwnership` + `verifyStatus(UPCOMING)` + optimistic-lock catch. `initiateQuickComplete` (`:106-121`): `resolveCoach` + `verifyCoachOwnership` + inline `current != UPCOMING`. `recordNoShowPlayer` (`:838-858`, verified personally): ownership at `:842-844` + `rejectIfPostCompletion` at `:845`. Neighbors `endSession`/`pauseSession`/`resumeSession`/`submitWrapUp` checked and excluded | **CONFIRMED** |
| M14 | `recordNoShowCoach` **does** have the guard | `:870-874`, read back-to-back with `recordNoShowPlayer` (`:838-858`) directly above it — the one-word-different neighbor explicitly disambiguated | **CONFIRMED** |
| M15 | UPCOMING is reminder-driven, up to ~24h **before** start, per `BookingReminderScheduler` | **Hunted the transition rather than trusting the attribution:** `grep SCHEDULE_UPCOMING` → 4 hits, exactly **one** production call site: `BookingReminderScheduler.java:101-102`. Nothing sets UPCOMING on coach acceptance or payment. Window: `:90 getBoundedLong("platform.reminder_interval_primary_hours", 24L, …)`, `:94-96` selects `requestedStartTime <= now + primaryHours`. Real default `24` from both the code fallback and `V139__baseline_seed_data.sql:106` | **CONFIRMED** — the exposure window AC6's severity argument rests on is real |
| M16 | `BookingError` is an enum + exhaustive switch; `case SESSION_NOT_YET_STARTED -> "booking.sessionNotYetStarted";` matches the shape | `BookingError.java:64` enum, constants `:65-92`, switch `:94-118`, **no `default` arm** — so adding a constant without its arm is a **compile error**; the prescribed edit cannot be half-applied | **CONFIRMED** |
| M17 | `ci.yml`'s `build-and-push` passes no `load:`, so the action's `'false'` default applies and the image is never in the local daemon | Job passes only `push`/`tags`/`commit-sha`/`labels` (`:238-255`); `docker-build/action.yml:9-12 load: default: 'false'`, consumed at `:64`. Confusable neighbor: `pr-build.yml:130-135` **does** pass `load: 'true'`, which is why the assertion works there | **CONFIRMED** |
| M18 | `sha-<short>` is always published; only `:latest` is conditional | Verified from the code, not just the comment: `:231-236` emits `sha_tag` on every branch; all five guard outcomes (`:173`, `:194`, `:196`, `:218`, `:226`) still emit it, always first. Job has no job-level `if:`. The one early `exit 1` (`:170`) publishes nothing at all | **CONFIRMED** |
| M19 | `docker login` to ghcr.io runs earlier in the **same** job | `ci.yml:110-115`, inside `build-and-push` (job starts `:88`), before the build step at `:238`; job has `packages: write` | **CONFIRMED** — the pull needs no new auth |
| M20 | `assert-git-provenance.sh` needs no change and works on a **pulled** image | Full body read. Extraction is `docker create` (`:48`) → `docker cp "${CONTAINER_ID}:/app/app.jar"` (`:56`) → `unzip -p … BOOT-INF/classes/git.properties` (`:63`) — i.e. it reads git.properties from **inside the jar**, matching the project's own known gotcha. Nothing in that chain depends on how the image entered the daemon. `trap cleanup EXIT` at `:40-46`. Optional 2nd arg at `:30`, compared at `:101-107` | **CONFIRMED** |

### 3c. PARTIALLY-CONFIRMED / overstated

| # | Claim | Correction | Severity |
| :-- | :--- | :--- | :--- |
| M21 | *"a half-applied 2FA-completion (JWT cookie durably written, response never returned)"* | **Outcome right, stated mechanism wrong, and the actual durable half is unnamed.** `CookieUtil.addCookie` uses `res.addHeader(...)` — a header write, not a flush; nothing is flushed until `SecondFactorLoginFilter.successResponse` (`:96-105`), which never runs on the throw path. The `Set-Cookie` headers survive onto the 500 because `ApiAdvice` uses `@ResponseStatus`, not `sendError`/`reset()` — **not** because the response was committed. **Transaction boundary traced** (the story asserts "half-applied" without doing this): `refreshLoginToken` has **no** `@Transactional`, nor does its only production caller (`SecondFactorLoginFilter`, a servlet filter). But `LoginInfoService` is **class-level `@Transactional`** (`:31-32`), so `markLoginInfoAsConsumed` commits the OTP-consumed row **in its own transaction before `refreshLoginToken` is even entered**. That — not the cookie — is the irreversible half. | SHOULD-FIX (framing) |
| M22 | AC5 severity: *"can 500 mid-2FA-completion"*, *"**newly reachable**"* | **Unreachable in practice.** `grep -rn "put(ROLES" src/main/` → exactly one hit, unconditional: `TokenCreatorImpl.java:49 claims.put(ROLES, toJson(principal.getAuthorities(), …))` (verified personally). `getAuthorities()` is never null, and the token is HMAC-verified before `:94-95` run, so a client cannot forge a ROLES-absent variant. AC5 is **latent/defensive hardening**, not a live bug — the fix is cheap and correct, but Dev Note line 355 uses the live-bug framing to rank AC5 above the UI items. | SHOULD-FIX (framing) |
| M23 | *"coach earnings unaffected"* (Finding 6's consequence paragraph) | **The story faithfully quotes the source comment** (`CancellationRefundService.java:114`, verified personally) — so this is a defect in the *code comment*, not the story. Substantively: payout fires only on `BookingCompletedEvent` (`CoachPayoutEnqueueListener.java:32`), and `recordNoShowPlayer` publishes only `PlayerNoShowEvent` (`BookingService.java:854-857`, verified personally) — so the coach earns **nothing** either; the forfeited fee stays with the platform. The story's severity argument is better supported by **irreversibility**: `onPlayerNoShow` is `AFTER_COMMIT` + `REQUIRES_NEW`, so it can never roll the status change back. | NOTE (downgraded — §6) |
| M24 | Implied: the project uses bare `Instant.now()` for such guards, so matching it is the natural choice | Precedent is real (`:870`), but the project **does** have a clock abstraction — `infrastructure/util/ClockProvider.java` plus a `TestClockProvider` seam used by 10+ test classes. Either shape is testable; the existing precedent test moves the *booking*, not the clock (`BookingServiceTest:1517-1530`). Not an error in the story, but worth knowing. | NOTE |
| M25 | *"zero filestorage-module changes"* | True for the key generator and DTO. But `StorageResource.java:38 OWNER_BOUND_ENTITIES = Set.of("coach_profile")` is an **entity-keyed security branch**: a new `coach_branding` entity gets **no presign-time entityId↔caller binding**, unlike `coach_profile`. Not a blocker, but a deliberate security-parity decision the implementer should make knowingly. | NOTE |

---

## 4. Corner cases, false assumptions & missed flows (Layer 4, survivors of Step 4b)

### 4a. BLOCKING

#### B1. **AC3's prescribed upload flow is guaranteed to 403 on every save — an id-space mismatch in code the story declares "correct as-is."**

Re-read personally, both sides. `saveBranding` checks the blob against the **CoachProfile UUID**:
```java
// ReportGenerationService.java:279-281
279	        if (request.logoKey() != null) {
280	            fileStorageService.assertOwnership(request.logoKey(), coachId.toString());
281	        }
```
But `FileStorageService` stamps `ownerId` from the **principal's business id**:
```java
// FileStorageService.java:68-70
68	    private String getOwnerId() {
69	        return securityUtil.requireCurrentBusinessId();
70	    }
// FileStorageService.java:149   .ownerId(ownerId)
// FileStorageService.java:334-341
334	    public void assertOwnership(String key, String expectedOwnerId) {
335	        fileStorageObjectRepository.findByKey(key)
336	            .ifPresent(fso -> {
337	                if (!expectedOwnerId.equals(fso.getOwnerId())) {
338	                    throw new AuthorizationException("File does not belong to caller", SecurityError.MISSING_RIGHTS);
```
And `businessId` is literally the numeric user id — the field is self-documenting:
```java
// Principal.java:20
20	    private final String businessId; //user id
// Principal.java:181
181	                            .businessId(String.valueOf(user.getId()))
```
confirmed by `SecurityUtil.getCurrentCoachUserId()` doing `Long.parseLong(principal.getBusinessId())` (`:299`).

**A CoachProfile UUID can never equal a numeric user-id string.** So:
- Follow Design C literally (presign → PUT → `confirmUpload` → `saveCoachBranding`) → `confirmUpload` writes the FSO row with `ownerId = userId` → the subsequent PUT **403s with `MISSING_RIGHTS`, every time.**
- Skip `confirmUpload` → no FSO row → `assertOwnership`'s `.ifPresent` passes silently → save succeeds but the blob is **unregistered and unquota'd**.

`ReportGenerationService.java:280` is the **only** `assertOwnership` call site in the repo, and the only test covering `saveBranding` mocks `fileStorageService` away (`ReportGenerationServiceTest.java:348-357`) — so this path has never executed against a real `FileStorageService`.

**This directly falsifies story line 372** (*"no change to … `CoachBrandingResource.java` … the backend already exists and is correct as-is"*). **AC3 cannot be completed without a backend fix** (`assertOwnership(logoKey, userId)` or equivalent). **Confidence: HIGH** — both sides read in full, single call site, zero real-path test coverage.

#### B2. **AC4's premise is unreachable** — see §3a M1. `BLOCKING`

#### B3. **AC3's ACADEMY gate shows LOCKED to entitled coaches** — see §3a M2. `BLOCKING`

#### B4. **AC8's workflow will not parse: `administration` is not a valid Actions `permissions:` scope.**

The story's prescribed YAML (lines 250-272) declares:
```yaml
    permissions:
      administration: write
```
`permissions:` accepts a closed enum (`actions, attestations, checks, contents, deployments, discussions, id-token, issues, models, packages, pages, pull-requests, repository-projects, security-events, statuses`). `administration` is a **GitHub App / fine-grained-PAT repository** permission, not an Actions token scope — and `GITHUB_TOKEN` cannot hold repo-admin at all. Verified personally that the repo has no such precedent: every `permissions:` block uses only `contents: read` and/or `packages: write` (`ci.yml:30-31`, `:98-100`; `pr-build.yml:30-31`, `:124-125`; `frontend-unit-tests.yml:58-59`, `:89-90`, `:116-117`; `deploy.yml:17-18`). And there is no workflow linter to catch it (`grep -rniE "actionlint|yamllint" .github/` → zero; no `.pre-commit-config.yaml`).

**Consequence:** *"Invalid workflow file … Unexpected value 'administration'"* and **no job in the file runs** — including the `workflow_dispatch` that AC8 (line 294) requires to be executed for empirical verification. The story's instinct is right (*"verify by execution, not assumption"*), but the prescribed YAML prevents the execution.

**Correct form:** drop the `permissions:` block (it cannot help) and use a fine-grained PAT secret with the `Administration` repository permission — i.e. the story's own `RULESETS_PAT` fallback (line 274) is the **only viable path**, not a contingency.

**Confidence: HIGH** on both the invalid-scope and the GITHUB_TOKEN-insufficiency conclusions. *Calibration note:* these rest on the GitHub Actions permissions spec plus the absence of any repo precedent, not on a repo file that enumerates valid scopes, and the workflow was not executed.

#### B5. **AC2 is designed around a tier "catalog" the backend does not have.**

Verified personally. Creating a tier **silently deactivates every existing active tier**:
```java
// SessionPackPaymentService.java:181-186, :197
181	        // Deactivate all existing active tiers for this coach
182	        sessionPackTierRepository.findAllByCoachIdAndIsActiveTrue(coachId)
183	            .forEach(t -> { t.setActive(false); sessionPackTierRepository.saveAndFlush(t); });
197	        tier.setActive(true);
```
backed by a DB partial unique index making two active tiers impossible:
```sql
-- V138__baseline_schema.sql:3910
CREATE UNIQUE INDEX idx_spt_one_active_per_coach ON payment.session_pack_tiers USING btree (coach_id) WHERE (is_active = true);
```
And the list GET is **unbounded and unordered** (`SessionPackTierRepository.findAllByCoachId`), returning every tier the coach ever created.

AC2 (line 282) says the page *"lists the coach's own tiers, supports creating and deactivating a tier"*, and Design B (line 138) says *"a deactivate action per **active** tier."* There is only ever **one** active tier; creating a second kills the first with no confirmation; the list grows by one dead row per price change in non-deterministic order.

> Re-model AC2 as **"your current pack offer + its replaced history"**, with an explicit "this replaces your current offer" confirmation on create and a stable sort — not a catalog with per-row deactivate. **Confidence: HIGH.**

#### B6. **AC6 breaks two existing tests the story never mentions, while AC9 demands "0 failures."**

Verified personally.

**Unit test** — every booking this fixture builds starts **two hours in the future**:
```java
// BookingServiceTest.java:1663-1664
1663	        b.setRequestedStartTime(Instant.now().plusSeconds(7200));
1664	        b.setRequestedEndTime(Instant.now().plusSeconds(10800));
```
so `recordNoShowPlayer_concurrentModification_throwsRetryableException` (`:1478-1495`) throws `NO_SHOW_TOO_EARLY` before reaching the optimistic-lock path and its `satisfies(… CONCURRENT_MODIFICATION)` assertion fails.

**Integration test** — the shared fixture inserts a start time **24h out**:
```java
// SessionCompletionResourceIT.java:455, :462
455	        Instant futureStart = Instant.now().plusSeconds(86400);
462	            Timestamp.from(futureStart), Timestamp.from(futureStart.plusSeconds(3600)),
```
called unconditionally from `setUp()` (`:127`), so `startSession_upcomingBooking_returns204AndStatusIsInProgress` (`:135-148`) returns 403, not 204.

**`BookingCompletionServiceTest` survives — but only by microseconds.** Its fixture is `booking.setRequestedStartTime(Instant.now())` (`:82`, verified personally) with no offset, so the guard's later `Instant.now()` is never *before* it. That is exactly the known `Instant.now()`-pair flake class recorded for this project (`chk_bkg_end_after_start`); it should be given an explicit past offset rather than left to monotonic luck. Any IT fixture fix must also keep `requested_end_time > requested_start_time`.

AC9 (line 296) demands *"Full targeted backend suite for every touched Java class passes (0 failures)"*, and Task 6 mentions only **new** tests. **Confidence: HIGH.**

#### B7. **AC6's "whichever existing page calls `recordNoShowCoach`" targets a caller that has never existed — and deferred-63 proved so in writing.**

Verified personally: `grep -rn "recordNoShowCoach" src/frontend/src` returns **exactly one line** — the API function definition:
```js
// src/frontend/src/api/booking.api.js:89
export const recordNoShowCoach = (bookingId) => api.post(`/api/bookings/${bookingId}/no-show-coach`)
```
No store action, no page, no button. `ParentBookingsPage.vue` — the story's guess — has no no-show UI at all; its only UPCOMING-gated actions are cancel and reschedule (`:102`, `:121`).

And deferred-63 did not forget this wiring — it **ruled it out explicitly, with the reason**:
> `skillars-deferred-63-…md:152-156` — *"**No frontend change is needed or expected**: `recordNoShowCoach` (`src/frontend/src/api/booking.api.js:80`) is called from no page in the frontend today — confirmed by grep, there is currently no "report coach no-show" button anywhere in the UI — so there is no existing `errorKey`-branching call site … to extend for this new code."*

That reasoning still holds at HEAD (only the drift is `:80` → `:89`).

**Four statements rest on this non-existent caller**: story line 88, line 226, AC6 line 290, Dev Note line 361. Worse, the Change Log (line 394) presents it as *"A separate fresh gap was found and folded into AC6"* — it is neither fresh nor a gap, but a documented, reasoned, still-valid scope exclusion reframed as an oversight. A dev will either declare AC6 done while silently skipping the clause, or build an entire new parent-facing "report coach no-show" surface — unbudgeted scope creep.

> Rewrite as: *"`recordNoShowCoach` has no UI caller (per deferred-63 AC4's own documented finding); the i18n keys are added ahead of a future parent-facing surface."* **Confidence: HIGH.**

#### B8. **AC6 omits the backend message bundles — the established home for every booking error string.**

Verified personally. `ApiAdvice` resolves wire keys through `MessageSource` (`:710-719`), and `booking.noShowTooEarly` already has localized **server** copy in all four bundles:
```
src/main/resources/i18n/messages_en.properties:141
src/main/resources/i18n/messages.properties:93
src/main/resources/i18n/messages_de.properties:83
src/main/resources/i18n/messages_fr.properties:104
```
So story line 88's *"has zero frontend i18n key"* is accurate for the Quasar bundles but the story never notes the backend copy exists. Conversely the **new** `booking.sessionNotYetStarted` gets no backend entry: Project Structure Notes (line 368) and Task 6 (line 333) list only `src/frontend/src/i18n/{en-US,fr-FR,de-DE}/index.js`. Without the `.properties` entries, `errorMsg.message` degrades to the raw developer string in every locale.

> **Four more files belong in AC6 and in the Project Structure Notes.** **Confidence: HIGH.**

### 4b. SHOULD-FIX

| # | Finding | Evidence | Why it matters |
| :-- | :--- | :--- | :--- |
| S1 | **AC1's composer gate is wrong — it will render a composer whose POST is guaranteed to 403.** | Server requires **two** conditions, moderation first (verified personally, `ReviewSubmissionService.java:244-253`): `if (review.getModerationStatus() != APPROVED) throw REVIEW_NOT_APPROVED;` then `if (review.getCoachResponseBody() != null) throw RESPONSE_ALREADY_SUBMITTED;`. But the coach's own list applies **no** moderation filter: `ReviewQueryService.java:54 reviewRepository.findByCoachId(coachId, pageable)` — contrast the public path at `:34-35` which uses `findByCoachIdAndModerationStatus(… APPROVED …)` | PENDING/UNDER_REVIEW/BLOCKED rows arrive with `coachResponseBody == null`, so Design A's rule ("where `coachResponseBody` is null/blank") shows a composer that 403s. Gate must be `coachResponseBody == null && moderationStatus === 'APPROVED'`. Also "null/blank" is looser than the server's `!= null` |
| S2 | **`REJECTED` is not a real moderation status, and `moderationStatus` has no display precedent to mirror.** | `ReviewModerationStatus.java` (verified personally): `PENDING, APPROVED, BLOCKED, UNDER_REVIEW` — no `REJECTED`. The precedent the story says to mirror never renders the field (`CoachPublicProfilePage.vue:103-116` is APPROVED-only) | Story line 23's *"field-for-field identical to `AuthorReviewDto`, so the same display conventions apply"* does not hold for `moderationStatus` — this is net-new UI plus net-new coach-POV copy in three locales |
| S3 | **A COACH user with no `CoachProfile` row gets a 404 with no i18n key.** | `CoachProfileService.java:706-711 getCoachIdByUserId` → `orElseThrow(ResourceNotFoundException)` → 404 `errorKey "RESOURCE_NOT_FOUND"`, absent from all locale bundles. Fronts `listMyReviews` (`ReviewResource.java:58`) and `getMyTier` | Reachable mid-profile-builder (route exists, `routes.js:67-75`). All three new surfaces hit this resolver and show a bare generic error instead of an empty/incomplete state. Also feeds B3 — `fetchCoachTier` swallows it to `null` (`auth.store.js:38-39`) |
| S4 | **AC3: a colour-only save silently wipes an existing logo.** | `ReportGenerationService.java:282-287` is an unconditional full replace, not a merge: `b.setLogoKey(request.logoKey())`. The GET returns only `logoSignedUrl`, not the stored key | The story knows "null clears" (line 37) but Design C's dialog only produces a `logoKey` after an upload. A coach changing only the colour sends `logoKey: undefined` → null → logo destroyed. The page has nothing to re-send (no key in the response), so this needs local tracking or a backend change |
| S5 | **The three new coach routes need a `meta` block the story never mentions.** | Verified personally: all 11 protected `coach/*` routes carry `meta: { requiresAuth: true, requiresCoach: true }` (`routes.js:74, 79, 85, 166, 172, 178, 184, 190, 196, 202`), with an explicit comment at `:69-74` documenting the exact regression caused by omitting it | Design A (line 132) and AC1–3 say only "add route … alongside the other `coach/*` entries". Omitting `meta` reproduces that documented regression on three new pages at once |
| S6 | **AC4's helper signature produces untranslated strings shown to the user.** | Story's own code (line 160): `const message = errorKeyMap[errorKey] ?? t(fallbackErrorKey)` — `t()` applied **only** to the fallback. All ~25 existing branches pass keys through `t()` (`CoachCommandCenterPage.vue:606-609`) | The natural reading (`errorKeyMap = { 'booking.x': 'booking.errors.x' }`) ships the literal key as the toast message. Either hold already-`t()`'d strings and say so, or `t()` both sides |
| S7 | **AC4's extracted helper drops per-handler loading-ref and dialog state.** | Each handler owns a `finally`-cleared ref the helper has no knowledge of: `rescheduleActionId` (`:589`, `:621-622`), `coachCancelActionId` (`:654`, `:671-672`), `noShowActionId` (`:682`, `:699-700`). `submitCoachReschedule` also pre-validates (`:725-728`) and closes its dialog inside the try (`:735`). The prescribed helper (lines 153-175) has no `finally` | A literal refactor leaves spinners stuck on and dialogs open. AC4 must state that each handler keeps its own try/finally, or the helper needs a `finally` callback |
| S8 | **No `CoachCommandCenterPage` spec exists, and Vitest is not in `mvn verify` or default CI.** | 37 spec files exist; none under `src/pages/coach/`. `vitest.config.mjs:37-40`: *"NOT wired into `mvn verify` … reachable only via `npm run test:unit` locally and the opt-in `.github/workflows/frontend-unit-tests.yml` job (manual dispatch or the `frontend-tests` PR label)"* | AC4's "add/extend coverage" means authoring the **first-ever** spec for a ~900-line page with SSE and six dialogs. AC9's frontend-suite requirement needs the `frontend-tests` label on the PR or it is simply unverified by CI |
| S9 | **AC5's prescribed test is not writable as described, and its stated assertion is wrong pre-fix.** | `setSkillarsProfileCookie` is `private` (`:136`), and `JwtManagerImplTest` uses **no reflection** — every test drives the public interface and reads `Set-Cookie` off a `MockHttpServletResponse` (`:348-358`). Pre-fix, with `ROLES` absent, `:170` throws before `:150` is reached, so **no `skp` cookie is written at all** — "resolves to ANONYMOUS" does not describe pre-fix behaviour. The discriminating observable is `assertThrows(IllegalArgumentException)` **plus** "`jwt` present, `skp` absent". The claims map must also carry `BUS_ID` and `GENDER` (`:161 Gender.valueOf`) or the post-fix path NPEs for an unrelated reason | AC5 (line 288) makes pre/post-fix discrimination a completion condition. Use deferred-150's actual precedent: hand-built raw tokens via **`buildTokenWithCustomClaims`**, not a private method. Note an existing ANONYMOUS-fallback test already exists at `:644-658` |
| S10 | **AC2's extend widget will mostly fail, and one of its two failure modes cannot be branched on.** | Verified personally (`SessionPackPaymentService.java:154-174`): ownership enforced at `:154` **(so no IDOR — see §7)**; **one extend ever** (`:158` → `ResponseStatusException(CONFLICT, "payment.packAlreadyExtended")`); **only inside a 14-day pre-expiry window** (`:168-171` → `PaymentGatewayException("payment.packExtensionNotEligible")`); `+30` days hardcoded. The 409 is flattened by `ApiAdvice:115-119` to `errorKey "generic.requestError"` — **not branchable**. None of the three keys exists in any locale bundle (verified personally: `grep` → NONE) | Design B and AC2 scope the widget with no i18n and no mention of the window, the one-shot cap, or the un-branchable 409. In normal use it fails more often than it succeeds, with a generic message. The help-text caption must state the 14-day window |
| S11 | **Deactivation is a real one-way door over an unsorted unbounded list.** | No reactivate endpoint exists anywhere (`grep -rn "reactivate\|activateTier\|setActive"` over the payment package returns only the mapper, the purchase-time gate `:89-91`, the auto-deactivate loop `:181-186`, `:197`, and `deactivateTier` itself). Existing purchases are unaffected — consumption runs off `SessionPackPurchase` | Combined with B5 (one active tier), a mis-click permanently withdraws the coach's only sellable offer, recoverable only by creating a new tier. AC2 needs a confirm dialog and a stable sort |
| S12 | **AC2's create form needs a derived-price pre-check the request validation lacks.** | Verified personally: `CreateSessionPackTierRequest(@NotBlank @Size(max=200) String label, @NotNull @Min(1) Integer sessionCount, @NotNull @DecimalMin("0.01") BigDecimal totalPrice)` — no upper bound, no `@Digits`, **no currency field anywhere**, no duplicate-label rule. `pricePerSession` is derived server-side (`:188-189`, `HALF_UP` to 2dp), so `totalPrice=0.01, sessionCount=10` → `0.00` → trips `chk_spt_price_per_session_positive` (`V138:1911`) as a 500, not a 400 | Design B says only "validate against whatever constraints `CreateSessionPackTierRequest` carries". It carries less than assumed. The form should enforce `totalPrice / sessionCount >= 0.005` and cap both numbers |
| S13 | **AC2's store claims are wrong in two places, and the loading-key registry must be extended.** | Verified personally: the action is `fetchSessionPackTiers` (`:95-105`), **not** `loadMySessionPackTiers`; and the two write functions are **not imported** — the import block `:2-23` carries only `fetchMySessionPackTiers` at `:5`. New keys must also be registered in `LOADING_KEYS` (`:28-41`), which feeds `emptyKeyedState()`/`emptyKeyedErrorState()` (`:44-49`) | Design B says to add actions "calling the already-imported-but-unused API functions" — they are not imported. And "following this store's existing convention" silently requires editing `LOADING_KEYS`; a dev who misses `:28-41` writes unregistered keys that bypass the reset helpers |
| S14 | **AC7's post-push assertion is a detector, not a gate; AC8's shell has a real abort path.** | **AC7:** the push step is the **last** step of the job (`:238-255`, EOF at 255) and publishes `:latest` in the same step's tag list. Nothing declares `needs: build-and-push`; `deploy.yml` is manual and gated only on tag existence (`:38-48`). So a failed assertion yields a red job **while the unverified image stays pullable and deployable**. A real gate is `push:'false' + load:'true'` → assert → push, or a separate pre-push job. **AC8:** under `set -euo pipefail`, an **empty** jq match is harmless (jq and `head` both exit 0) — that worry is unfounded — but a **failing** `gh api` (404/SSO/rate-limit, or the 403 that B4 guarantees) aborts at the assignment before the create-if-absent branch can run, and `head -1` adds a SIGPIPE/141 surface that `pipefail` promotes to an abort. Fix: `--jq 'first(.[] \| select(.name=="NoDirectPush") \| .id) // empty'` with `\|\| existing_id=""` | Both ACs claim empirical verification as the completion gate; both have a failure mode that produces a misleading green/red. The ordering consequence is absent from Design G |
| S15 | **AC8's strip list is incomplete.** | The live ruleset carries two fields the story's list (`id`, `node_id`, `created_at`, `updated_at`, `_links`) omits: `"bypass_actors": []` (writable — committing it is a deliberate choice) and `"current_user_can_bypass": "never"` (**read-only — must be stripped or the PUT/POST is rejected**) | The story itself says to verify which fields the API accepts; this is the answer. *Not re-verified by orchestrator — §6* |
| S16 | **AC6 silently reverses a documented "Never" from the immediately preceding story.** | `spec-coach-booking-cancel-no-show.md:24`: *"**Never:** … Do not add a client-side "too early" guard on no-show reporting — the backend intentionally has none for this endpoint … adding one would be an unrequested product decision."* The ledger bullet records the same (`deferred-work.md:3795`) | The USER DECISION (line 92) **legitimately** overrides this — that is what a product decision is for — but neither AC6 nor Design F acknowledges the reversal. A dev reading both files hits a flat contradiction with no instruction on which wins. One sentence in Design F fixes it |
| S17 | **AC1–3 scope no i18n work for three brand-new pages.** | Tasks 1-3 (lines 302-318) contain no i18n subtask; AC1-3 mention no new copy; Project Structure Notes list the i18n files only in service of AC6's two keys | AC6 scopes two keys × 3 locales; AC1-3 scope **zero** while adding three pages' worth of labels, buttons, validation messages, empty states and error branches. This surfaces at implementation time as unbudgeted work or hardcoded English |

### 4c. NOTE

| # | Finding | Evidence |
| :-- | :--- | :--- |
| N1 | **`handleStartSession`/`handleQuickComplete` have no "existing catch-block branching" to extend.** | `CoachCommandCenterPage.vue:530-531` and `:546-547` are bare `catch {}` with no `err` binding. AC6 line 227 says to add an `errorKey` branch "to each handler's existing catch-block branching" — for these two the signature must first change to `catch (err)`. The underlying store actions do rethrow (`booking.store.js:499-502, 521-523, 552-554`), so the branches are feasible |
| N2 | **Branding logo's signed URL expires in 5 minutes with no re-sign path.** | `FileStorageService.java:293-300` uses `storageProperties.getPresignTtlSeconds()`; `application.yaml:320 presign-ttl-seconds: 300`. A `/profile` tab left open >5min shows a broken logo |
| N3 | **No SVG in the upload allow-list.** | `application.yaml:439-456` allows jpeg/png/gif/webp/pdf/txt/octet-stream; enforced at `MimeTypeValidationStep:21-24` and `ExtensionValidationStep:21-24`. Brand logos are commonly SVG; the dialog should say so. (The story's narrower claim — no *entity* allow-list — is correct) |
| N4 | **Replaced logo blobs are never deleted.** | `ReportGenerationService.java:282-287` overwrites `logoKey` with no cleanup; no orphan sweeper references `coach_branding`. Each replacement counts against the 10 GB per-owner quota permanently |
| N5 | **Do not copy the wrong feature-gate precedent.** | `sessionBuilder.store.js:135` branches on `helpCode === 'security.featureGated'` — a pre-existing bug (`helpCode` is a generated log reference, `ApiAdvice:705-719`). `DrillDetailPanel.vue:465-466` uses `errorKey`, which is correct. Design C's prescription (line 144) matches the **correct** pattern — flag it so the dev doesn't copy the store |
| N6 | **Committing the live ruleset verbatim enshrines a real gap.** | Live required contexts are `build` + `docker-image` only — `pr-build.yml:161`'s `frontend-quality` job (the Prettier/ESLint gate) is **not** required, so a Prettier failure does not block a PR merge; it only blocks on master via `ci.yml:93 needs: [test, frontend-quality]`. Worth a deliberate decision in AC8, not a copy-forward. **Separately, verified personally:** a new `.github/rulesets/*.json` is **not** subject to the Prettier gate — its glob runs with `working-directory: src/frontend` (`.github/actions/frontend-quality/action.yml:34-36`), so nothing outside that tree is checked. The three new **Vue/JS** files are |
| N7 | **Posting a coach response does not bump `lastModifiedAt`**, so re-fetch and optimistic patch agree. | `ReviewSubmissionService.java:254-256` sets only `coachResponseBody`/`coachResponseAt`; the coach list sorts by `lastModifiedAt DESC` (`ReviewQueryService.java:53`). Design A's "either/or" (line 130) is safe — just don't expect a re-sort. Also: **zero IT coverage** for `GET /api/reviews/coaches/me` (`CoachResponseIT`'s five tests are all POST-path) |
| N8 | **Pre-existing, adjacent: `/no-show-coach` is player-reachable but player-impossible.** | `CancellationResource.java:50-55` is gated `HAS_PARENT_OR_PLAYER_ROLE`, but `currentParentId()` is `requireCurrentUserId()` and the service compares it to `booking.getParentId()` (`BookingService:862-864`) — a PLAYER caller can never pass ownership. Out of scope, but in the same method AC6 modifies. *Confidence: MEDIUM* |

---

## 5. What did NOT survive re-verification

This section exists so the rest of the report is trustworthy. Three items a layer raised were killed or downgraded by Step 4b.

1. **KILLED — "AC8's `set -euo pipefail` + empty jq match is a failure mode."** Raised as a concern in the task framing; Layer 4 refuted it against the shell semantics and I accepted the refutation: `var=$(gh api … | head -1)` with a non-matching `select` produces empty output with **exit 0** from both jq and `head`, the assignment still happens, and `[ -n "$existing_id" ]` is safe under `set -u`. Only the **`gh api` failure** path and the `head -1` SIGPIPE surface are real; S14 was narrowed to those.

2. **DOWNGRADED (SHOULD-FIX → NOTE) — "the story's 'coach earnings unaffected' claim is materially misleading."** Layer 3 filed this against the story. On re-check I found the story is **quoting `CancellationRefundService.java:114` verbatim** — the misleading phrasing is the *code comment's*, not the story's. The substantive point stands (payout fires only on `BookingCompletedEvent`, `CoachPayoutEnqueueListener:32`; `recordNoShowPlayer` publishes only `PlayerNoShowEvent`, `BookingService:854-857` — so the coach earns nothing either), but it is not a story defect. Recorded as M23/NOTE.

3. **REFRAMED — Layer 2's "lines 3272–3756 are unaccounted because the story claims 1–3271."** Accurate on substance but mis-framed: the story's *own* declared scope is "the unmined 2026-10-02-through-2026-10-14 tail", and deferred-140 (`:3407`) is dated 2026-10-02 — so the story's self-declared scope already includes `:3407-3795`. The sharper and more damaging framing, which I verified directly, is that the story skips **five sections inside its own declared scope** (`:3449-3756`: 17 untagged bullets, 16 "When picked up" directives, zero closure tags). §2c reports the sharpened version, not Layer 2's original.

Also worth recording as a **non-finding**: Layer 4's hypothesis that the AC6 guard might be gating on the wrong `Booking` time field — the single highest-value check requested — **did not pan out**. See §7 item 1. The story's field choice is correct.

---

## 6. Claims NOT independently re-verified by the orchestrator

Disclosed so confidence is calibrated rather than uniform. These rest on a single layer's evidence:

- **M3 (Jackson null-content → `IllegalArgumentException`)** — Layer 3 compiled and ran a probe against the exact resolved `jackson-databind-2.21.7` jar and reported `java.lang.IllegalArgumentException :: argument "content" is null`. I did not re-run the probe. Confidence remains HIGH (this matches Jackson's documented `_assertNotNull("content", …)` guard), but it is subagent-verified, not orchestrator-verified.
- **M21's response-commit analysis** (`Set-Cookie` survives onto the 500 because `ApiAdvice` uses `@ResponseStatus` rather than `sendError`/`reset()`) — traced by Layer 3 through `SecurityAdviceFilter:61-63`; I did not re-walk the Spring resolver path. **MEDIUM** confidence on the mechanism; the *outcome* the story claims is not in dispute.
- **S15 (live ruleset's `bypass_actors` / `current_user_can_bypass` fields)** — read live via `gh api` by Layer 4; I did not re-run the API call (requires network + `gh` auth). The story's own description of the ruleset's `name`/`target`/`conditions`/four rules was independently confirmed by Layer 4's live read.
- **N2, N3, N4** (presign TTL, MIME allow-list, orphaned blobs) — single-layer reads of `application.yaml` and the validation steps; not re-read by me.
- **B4's "`administration` is not a valid scope"** — rests on the GitHub Actions permissions specification plus the verified absence of any repo precedent, not on a repo file enumerating valid scopes. The workflow was **not** executed. HIGH confidence, non-empirical basis.

---

## 7. Story assumptions independently confirmed SOUND

The story is right about a great deal, and this matters for calibration.

1. **`requestedStartTime` IS the authoritative field for the AC6 guard — the high-value "wrong field" hypothesis fails.** `Booking.java:39-43` has exactly two time fields (`requestedStartTime`, `requestedEndTime`); there is no `confirmedStartTime`/`actualStartTime`. Critically, accepting a reschedule writes **back into the same field** (`RescheduleService.java:449-450`), so a rescheduled session is gated correctly. `grep "setRequestedStartTime" src/main` → only four legitimate write paths.
2. **No IDOR on `extendSessionPack`.** Ownership enforced before any mutation (`SessionPackPaymentService.java:154-156`), with `coachId` resolved server-side from the JWT. `deactivateTier` is equally guarded (`:214-216`). The raw-purchase-id text box the story proposes is **safe**. *(Verified personally.)*
3. **`submitCoachResponse` has full ownership, approval and idempotency guards** under a `PESSIMISTIC_WRITE` NOWAIT read (`ReviewSubmissionService.java:236-253`, verified personally). A coach cannot respond twice or to someone else's review.
4. **`err?.response?.data?.errorMsg?.errorKey` is the real envelope shape.** `ErrorMsg.java:6 record ErrorMsg(String errorKey, String message)`, `ErrorDto.java:19-22`, populated at `ApiAdvice:333-338` → `:719`. Matches all ~25 existing destructures.
5. **`booking.errors.<suffix>` is the real i18n convention**, consumed as `t('booking.errors.X')` at `CoachCommandCenterPage.vue:607, 609, 638, 745`. And both keys really are absent from all three bundles — `grep -rn "noShowTooEarly" src/frontend/src` → **NONE** *(verified personally)*.
6. **The 24-hour UPCOMING exposure window is real**, so AC6's severity reasoning holds — and the attribution to `BookingReminderScheduler` is correct, verified by hunting the transition rather than trusting the claim (§3b M15).
7. **`submitCoachReschedule` really is the odd handler out — and the story out-audited the ledger here.** Its catch (`:739-760`) alone lacks both the refresh and the stale-warning. The story's correction of the ledger's "five handlers" wording is accurate, and it implicitly corrects a *second* ledger error: `deferred-work.md:3791` names accept/decline/**submitCoachReschedule** as the three with the in-catch refresh, when the real set is accept (`:591`)/decline (`:629`)/coachCancel (`:658`)/noShowPlayer (`:685`).
8. **The `JwtManagerImpl` drift correction is genuine and exact** — all six sub-citations line-exact, and the ledger's `:155-162` really is stale (it matched no committed state).
9. **The ACADEMY gate is real, asymmetric, and genuinely absent from the ledger** — `saveBranding` throws (`:277`) while `getBranding` degrades to `(null, null)` (`:264`), mapped to 403/`security.featureGated` (`ApiAdvice:388-393`), which exists in all three bundles (`en-US:625`, `fr-FR:627`, `de-DE:1179`). The story's "found during this story's own research" claim is **true**.
10. **`coachTier`'s string values are exactly `'ACADEMY'`** — `CoachSubscriptionTier.java:6-7` (`SCOUT, INSTRUCTOR, ACADEMY`), returned as `tier.name()`. The literal the story prescribes is right; only its *availability* is wrong (B3).
11. **No `size` param on `GET /api/reviews/coaches/me`; the story's client is correct.** `ReviewResource.java:55-57` takes only `page`; size is hard-coded 10 (`ReviewQueryService:53`); `page` floored at 0. All DTO shapes field-for-field as stated, including `CoachOwnReviewDto` ≡ `AuthorReviewDto` (byte-identical component lists).
12. **`PATCH .../deactivate` does not harm existing purchases** — consumption runs off `SessionPackPurchase`; `PackSessionService` never reads the tier table.
13. **`StorageKeyGenerator` enforces no entity allow-list** — a new `coach_branding` string needs no key-generator change. (The ownership problem in B1 is in `ReportGenerationService`, not here.)
14. **`assert-git-provenance.sh` needs no change and works against a pulled image** — including the project's own git.properties-inside-the-jar gotcha, handled by `docker create` → `docker cp app.jar` → `unzip -p BOOT-INF/classes/git.properties`. Build is single-arch (`docker-build/action.yml:13-16 default: 'linux/amd64'`), so no manifest-list concern.
15. **`docker login` precedes the build in the same job**, so the pull needs no new credentials (`ci.yml:110-115`).
16. **SHA-pinning convention matches the story's placeholder** — every third-party `uses:` is a 40-char SHA with a trailing `# vX.Y.Z`; zero tag-only refs.
17. **The live ruleset matches the story byte-for-byte on every field it names**, and `.github/` has zero ruleset references *(verified personally)*.
18. **`MainLayout.vue`'s coach nav has no tier gating the new items must respect** — the whole block is `v-if="authStore.isCoach"` with exactly the three named items *(verified personally)*. And the cautionary example holds: zero nav links to `/coach/payment-settings` *(verified personally)*.
19. **The visual precedent is exactly where the story says** (`CoachPublicProfilePage.vue:88-127`), including the `coach-response` sub-block gated on `coachResponseBody?.trim()`.
20. **The orphan-grep claims are accurate** — `sessionPackTiers` appears only inside `payment.store.js` (zero page readers); `createSessionPackTier`/`deactivateSessionPackTier`/`extendSessionPack` have exactly one source hit each, their own definitions *(verified personally)*.
21. **All four mined ledger sections exist with the claimed dates, and all eight mined items are genuinely open** (no closure tag at or after `:3751`).
22. **Cross-story attributions check out** against each predecessor's own source rather than this story's paraphrase: deferred-63's AC4 guard + its four backend message files; deferred-150's `expected-commit-sha` arg, truncation `log.warn`, and AC6 empirical method; deferred-149's `gh api --method PUT` application; deferred-140's AC4.3 walkthrough; deferred-146's "re-check on the next few PR builds."

### One correction to a predecessor's own comment, found in passing
`pr-build.yml:138-141` claims that in `ci.yml` *"a `docker create` there would fail outright with 'no such image'."* For a **registry-qualified** tag that is inaccurate — `docker create` auto-pulls. The explicit `docker pull` in AC7's design is still clearer and harmless, but the stated failure mode in that comment is wrong.

---

## 8. Recommendation

**Do not start implementation on AC3 or AC4 as written.** Everything else is workable with the corrections below.

### Per-item verdict and confidence

| Item | Verdict | Confidence | Basis |
| :--- | :--- | :--- | :--- |
| **AC4** (handler hygiene) | **Re-scope before building.** Its stated defect cannot occur; this would be the 3rd filing of a twice-withdrawn defect. Keep the genuine half (mutation+refresh share one `try`) and the dedup; drop the "unguarded rejection" framing and the test that can only pass by mocking a forbidden store contract. | **HIGH** | `booking.store.js:453-467` + the `:360-368` contract comment, both re-read personally |
| **AC3** (branding) | **Blocked on a backend fix.** The prescribed flow 403s every time (`coachId.toString()` vs `ownerId = businessId`), and the ACADEMY gate reads a ref that is `null` on cold load. | **HIGH** | `ReportGenerationService:280` + `FileStorageService:68-70,149,334-341` + `Principal:20,181` + `auth.store.js` full read + zero `fetchCoachTier` call in `ProfilePage.vue` — all re-read personally |
| **AC6** (time gate) | **Sound in design, incomplete in scope.** Correct field, correct error-code reuse, correct i18n namespace. But: 2 existing tests break unmentioned; 4 backend `.properties` files missing; the `recordNoShowCoach` caller does not exist; it silently reverses a prior story's "Never". | **HIGH** on all four | Fixtures, message bundles, and the zero-caller grep all re-read personally |
| **AC2** (session-pack tiers) | **Re-model before building.** One active tier per coach (DB-enforced), create silently replaces, no reactivate, unsorted unbounded list, extend widget fails in most states with an un-branchable 409 and zero i18n. **No IDOR** — that concern is closed. | **HIGH** | `SessionPackPaymentService:154-216` + `V138:3910` + i18n greps, re-read personally |
| **AC8** (ruleset as code) | **Prescribed YAML will not parse**; the PAT fallback is the only viable path, not a contingency. Strip list is also incomplete. | **HIGH** on the scope-invalidity; **see §6** on basis | Actions permissions spec + verified absence of repo precedent |
| **AC7** (CI provenance) | **Workable; two gaps.** Insert after `:255` (not `:248` — that corrupts the step), and note it is a post-push *detector*, not a gate. Script reuse, tag availability, and auth all verified sound. | **HIGH** | `ci.yml:238-255` and the script's full body, re-read personally |
| **AC5** (null-ROLES) | **Fix is correct; severity framing and test recipe are not.** Unreachable in practice (`TokenCreatorImpl:49` always sets ROLES), so "latent hardening" not "can 500". Test must drive the public interface via `buildTokenWithCustomClaims`, assert `IllegalArgumentException` + "jwt present / skp absent" pre-fix, and include `BUS_ID`/`GENDER`. | **HIGH** on reachability and the private-method problem | `TokenCreatorImpl:49` re-read personally; test-file structure single-layer |
| **AC1** (coach reviews) | **Closest to shippable.** Backend guards are complete and correct. Two fixes: gate the composer on `moderationStatus === 'APPROVED'` too, and add route `meta`. | **HIGH** | `ReviewSubmissionService:236-253` and `ReviewQueryService:52-54` re-read personally |
| **Ledger exhaustiveness claim** (§2c) | **Delete or do the audit.** Five sections / 16 open "When picked up" bullets inside the story's own declared scope are neither mined nor excluded — repeating an error deferred-150's review already corrected once. | **HIGH** | Section map, bullet counts and tag counts all measured personally |

### What was and was not independently re-checked

Every finding above was re-read by the orchestrator against source during Step 4b **except** the five items disclosed in §6 (the Jackson probe, the Spring response-commit path, the live `gh api` ruleset fields, and three `application.yaml`/validation-step reads). Step 4b killed one finding outright, downgraded one from SHOULD-FIX to NOTE, and reframed one — and the single highest-value hypothesis handed to the corner-case layer (wrong `Booking` time field) was disproved, leaving the story correct on that point. Confidence is deliberately **not** uniform across claims; the §6 and §5 sections exist because a report with uniformly high confidence and no self-corrections is itself evidence the adversarial pass did not happen.
