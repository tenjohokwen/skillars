# Story Review — skillars-deferred-154

**Story key:** `skillars-deferred-154-report-access-scoping-coach-erasure-recap-cleanup-roster-review-query-fix-and-nav-fallback`
**Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-154-…-nav-fallback.md` (142 lines, **untracked/new**)
**Status in `sprint-status.yaml` map:** *absent* — the story has **no entry at all** in `development_status`. Its own header says `**Status:** ready for dev`. The skill's prescribed discovery path (latest key named in `last_updated`) resolves to `skillars-deferred-153`, which is `done`; this story was selected on the user's explicit instruction after that was reported.
**Audit date:** 2026-10-17
**Real HEAD at audit time:** `9ebaec14` — *Merge pull request #278 from tenjohokwen/story/skillars-deferred-153-wrapup-abandonment-roster-fanout-relationship-consistency-and-recency-policy-fixes*
**Branch:** `master`. Working tree: only `deferred-work.md` modified (uncommitted) and the story file untracked — **all `src/` files on disk are identical to HEAD**, so source citations were verified against the working tree directly.

**Every citation below was checked against this HEAD — not against any SHA, line number, or "re-verified against real HEAD" claim written inside the story.** The story names `9ebaec14` itself and that is correct; it was nevertheless re-derived independently via `git rev-parse` before any verification began. Ledger citations were checked against **both** the working-tree (post-prune) `deferred-work.md` and `git show HEAD:` (pre-prune), because the story's own uncommitted ledger edit could have removed a bullet the story then cites.

Four parallel verification layers ran (citations / ledger+precedent / mechanistic / corner-case) over **~90 checkable claims**, followed by a mandatory adversarial re-read in which I personally re-opened the deciding evidence for every finding with the goal of refuting it. **Twelve layer claims died or were downgraded in that pass** and are listed in §6 — including the one hypothesis I had flagged to the layers as the single most likely defect.

---

## 1. Citation verification

| # | Citation | Verdict | Evidence / corrected location |
|---|---|---|---|
| 1 | `ReportGenerationService.listReports` `:253-274` | **MATCH** (exact) | `:253` signature, `:254` `requireCoachPlayerRelationshipIfCoach`, `:255-256` `findByPlayerIdAndStatusOrderByGeneratedAtDesc(playerId, READY)`. `:275` begins `getBranding` — range is exact, not approximate. Story correctly *corrects* the ledger's stale `:252-272`. |
| 2 | `READ_RELATIONSHIP_STATUSES` is "a 12-value list" incl. 5 named statuses | **MATCH — real count = 12** | `CoachPlayerAuthorizationService.java:35-38`. All five named (`DISPUTED`, `REFUND_PENDING`, `NO_SHOW_PLAYER`, `NO_SHOW_COACH`, `PAYMENT_PENDING`) present. |
| 3 | `PerformanceReportResource.listReports` `:39-44` + `@PreAuthorize` string | **MATCH** (character-for-character) | `:40 @PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)")`. No `GrantedAuthorityDefaults` bean exists, so the expression is live, not dead. |
| 4 | `ReportGenerationServiceTest` has exactly 2 construction sites, `:89` + `:485` | **MATCH — real count = 2** | `:89 service = new ReportGenerationService(`; `:485 ReportGenerationService realService = new ReportGenerationService(`. The second is a *local* variable, not the shared field — both must be updated. `SecurityUtil` is already `mock(...)`-ed in this file at `:483`. |
| 5 | `GdprErasureService` coach branch `:285-290` | **MATCH** (exact) | `:284` comment, `:285` `findByUserId(userId).ifPresent(cp -> {`, `:286-288` bio/city/district nulls, `:289` save, `:290` close. |
| 6 | `SessionCompletionDataRepository.anonymizeByPlayerId` `:36-43` | **MATCH** (exact) | `:41 @Modifying`, `:42` JPQL, `:43` signature; `:44` closing brace. No `clearAutomatically`/`flushAutomatically`. |
| 7 | `routes.js` — "seven `:playerId` params" at `130,135,140,169,236,278,284` with 7 named paths | **MATCH — real count = 7**, all line numbers and all path spellings individually correct | Raw grep yields 8 hits; the 8th (`:269`) is inside a comment, not a route. **No route in the file constrains any param**; the only parenthesised param is `:389 '/:catchAll(.*)*'`. |
| 8 | `BookingRepository.findQualifyingCompletedBookings` `:145-156` | **MATCH on query text; range truncated** | `@Query` `:145-155` verbatim as quoted (incl. lowercase `limit 50` at `:154`, `ORDER BY b.playerId` at `:153`); signature runs to **`:160`**, and the justifying comment block the story tells the dev to edit is at **`:128-144`**, outside the cited range. |
| 9 | `ReviewSubmissionService.evaluateEligibility` `:307-337`, sub-cites `:321-325`, `:323-325`, `:333` | **MATCH on all three sub-cites; outer range truncated** | Method really runs `:307-348`; the tail checks (`!eligible` return, active-dispute gate) at `:339-345` are outside the cited range. `:333` is exactly `authorId.equals(player.getUserId()) \|\| authorId.equals(player.getParentId())`. |
| 10 | `ReviewResource` `resolveUserId()` at `:83,98` | **DRIFTED (off-by-one)** | `:83` exact. **`:98` → corrected to `:99`** (`:98` is `submitReview`'s last parameter line). Still inside the right method. Other call sites: `:58,113,122,132,144`; declaration `:150`. |
| 11 | `BookingService.createBookingRequest` `:179-190,330` | **MATCH** | Ownership branch `:181-188` (parent-owned **or** self-owned), then `:330 booking.setParentId(parentId)` with no branch. `parentId` is the caller's own id via `BookingResource:39 → currentParentId() → requireCurrentUserId()`. |
| 12 | `CoachProfileRepository.java:72` ad-hoc-join precedent | **MATCH** | `:72 @Query("SELECT p, pr FROM CoachProfile p LEFT JOIN CoachPricing pr ON pr.coachId = p.id WHERE p.id = :coachId")`. No `nativeQuery` attribute ⇒ genuine JPQL. |
| 13 | `roleRoutes.js` `DEFAULT_ROUTE` = `'/dashboard'` at `:21` | **MATCH** (exact) | `:21 export const DEFAULT_ROUTE = '/dashboard'`. |
| 14 | `MainLayout.vue:126` `/dashboard` `q-item` gated `v-if="authStore.isAdmin"` | **MATCH** (exact) | `:126 <q-item v-if="authStore.isAdmin" clickable to="/dashboard" class="nav-item">`. |
| 15 | `MainLayout.vue:296-300` separate "Admin section" w/ `/admin/health-dashboard` | **MATCH** | `:296` comment, `:297 <template v-if="authStore.isAdmin">`, `:300 to="/admin/health-dashboard"`. |
| 16 | Drawer gated `v-if="authStore.isAuthenticated"` | **MATCH** | `q-drawer` `:109-110`, closes `:322`. |
| 17 | `/dashboard` route `meta: { requiresAuth: true }`, no role restriction | **MATCH** | `routes.js:358-362` — no `role`, no `roles` key. |
| 18 | `DashboardPage.vue` = "generic, role-agnostic placeholder page with static '—' metric cards and no admin-specific content" | **PARTIALLY INACCURATE** | "No admin-specific content" ✅ (nothing admin-related in the file). "Static cards" ✅ as to data sourcing, but 1 of 4 values is a hardcoded `'99.9%'`, not `'—'` (`:67-72`). **"Role-agnostic" is false**: `:56-60` calls `bookingStore.loadParentBookings()` gated on `authStore.isParent`, and `:3-6` renders `<TimezoneNotice>`. See §6 for why AC5's operative premise nonetheless survives. |

**Zero `CANNOT_LOCATE`.** Incidental stale reference found while verifying #11: `BookingService.java:630` cites "`createBookingRequest`'s own parentId-vs-userId branch (`:170-176`)" — the real branch is `:182-190`. Pre-existing, not introduced by this story.

---

## 2. Ledger & precedent attribution

| # | Claim | Sources checked | Verdict |
|---|---|---|---|
| 1 | AC1 ← deferred-153 code-review section, cross-coach report-PDF bullet | working-tree ledger `:3270-3273`; `git show HEAD:` (identical) | **CONFIRMED** — bullet exists verbatim; is genuinely in the file's last section; its own suggested fix matches AC1. |
| 2 | AC2 ← same section, coach-side GDPR recap bullet | ledger `:3272`; HEAD identical | **CONFIRMED** as a bullet — but see §5/F5: the bullet prescribes "**with its own lock-budget accounting**", which AC2 silently contradicts. |
| 3 | AC3 ← skillars-12-4 section; AC4 ← deferred-150; AC5 ← deferred-141 | ledger `:3265-3268`, `:3251-3253`, `:3205-3221` | **Bullets CONFIRMED; the Source table's location column is WRONG** — it labels all five "last section of the file"/"same section"; they are in **four distinct sections** spanning 2026-10-03 → 2026-10-17. The column header promises "Line(s) at drafting" and supplies no line numbers. |
| 4 | "worth folding into any future pass that adds param constraints across the router" | ledger `:3268` | **CONFIRMED verbatim.** |
| 5 | Ledger suggested "join `player_profiles`" for AC4 | ledger `:3253` | **CONFIRMED** — "pushing the parent/self ownership predicate into the query (join `player_profiles`, or pass the caller's owned playerIds in)". |
| 6 | "only worth it if observed in production" = "the ledger's own stated bar" | ledger `:3238-3239` | **PARTIALLY-WRONG** — paraphrase presented in quotation marks. Real text: "only worth adding bespoke handling if this class of DB failure is ever observed in production for an auth-adjacent write." Substance identical; bullet genuinely still open. |
| 7 | `markFailed` auto-retry "as the ledger's own prior entries on this already noted" | `git show HEAD:` ledger `:2726-2765`; the uncommitted diff; `GdprErasureRetryScheduler.java`; `GdprErasureService:571` | **WRONG — see §5/F3. Auto-retry is already built and shipped.** |
| 8 | `findQualifyingCompletedBookings` comment documents "the now-removed `OR` clause's rationale" | `BookingRepository.java:128-144`; exhaustive grep of `src/main` | **WRONG** — the block documents only the `ORDER BY`/`limit` residual. No comment anywhere explains the `OR` branch (only hits: the query line `:149` and the `@Param` `:158`). AC4 asks the dev to update text that does not exist. |
| 9 | `READ_RELATIONSHIP_STATUSES` 12-value, added by deferred-153 **AC5** | `CoachPlayerAuthorizationService.java:26-38` (comment names AC5); deferred-153 story `:67` | **CONFIRMED** — AC number correct, count correct. |
| 10 | deferred-153 AC5/AC6 "widened … matching the roster's own membership width" | `CoachPlayerAuthorizationService.java:26-34`, `:95-104`; deferred-153 AC5/AC6 | **CONFIRMED** — incl. that `listReports` routes through the AC6 helper. |
| 11 | Recap columns coach-authored "per that table's own column comment, added by deferred-153 AC2" | all 7 `COMMENT ON` statements in the migration tree; `V159__…recap.sql`; `git log` on it; entity Javadoc | **WRONG as cited** (substance survives) — **zero `COMMENT ON COLUMN` on `booking.session_completion_data`**; all 7 live in `V138` (5 on `main.alert_rule`). The columns were added by **skillars-12-2** (`756bca37`), not deferred-153. The real artifact is a **Java** comment at `SessionCompletionDataRepository.java:36-40`, which *is* deferred-153 AC2's and *does* say "coach-authored". |
| 12 | `ANONYMOUS` `SkillarsRole` added by deferred-142 **AC2** (cited twice) | `SkillarsRole.java:19`; deferred-142 Task 2/AC2; `git log -- */SkillarsRole.java` → `3a62698c` | **CONFIRMED** — enum value and AC number both correct. |
| 13 | deferred-140 **AC4.3** is a manual browser walkthrough | deferred-140 story `:178`, `:391`, `:456` | **CONFIRMED** — heading is literally "AC4.3: Manual Verification", self-disclosed as still outstanding. |
| 14 | 4 orphan comments "all traced to the same deferred-100 **AC7** ledger-hygiene pass" | deferred-100 `:419-430`; the 8 removed comment lines | **CONFIRMED** — AC7 is titled "Ledger hygiene…" and mandates the exact comment format; all 8 removed lines carry it. |
| 15 | `[CLEANUP 2026-09-26: …]` precedent exists | working-tree ledger lines 33, 198, 3016, 3150 | **CONFIRMED** — 4 pre-existing instances; new annotations copy the shape faithfully. |
| 16 | "existing **daily** `SubscriptionService.reconcileStripeSubscriptions()` sweep … within 24h" | `SubscriptionService.java:708`; `StripeSubscriptionReconciliationScheduler.java:38-41` | **CONFIRMED** — `@Scheduled(cron = "0 0 5 * * *")`, daily 05:00. |
| 17 | New call is "at the same weight as the pre-existing `anonymiseApprovedReviews(userId)` call a few lines below" | `GdprErasureService.java:300`; `CoachReviewRepository.java:104-106`; `V138:3922`, `V138:3280` | **PARTIALLY-WRONG** — located correctly (`:300`, 10 lines below, same `REQUIRES_NEW` tx), but the *weight* comparison is false. See §5/F5. deferred-153 AC2 also explicitly disclaims this method as a shape-precedent ("writes an integer sentinel, `0`, to an **id** column"). |
| 18 | `getCoachIdByUserId` throws `ResourceNotFoundException`, and is "the same method `CoachPlayerAuthorizationService` itself uses" | `CoachProfileService.java:706-711`; `CoachPlayerAuthorizationService.java:40-43,50-53,76-79`; 26-hit call-site grep | **First half CONFIRMED; second half WRONG.** See §5/F6. |
| 19 | Ledger-hygiene self-claim: "pruned **16** bullets … (**12** `[CLOSED by`-tagged, 2026-08-11 → 2026-09-23) … (**4** orphaned comment instances)" | my own `git diff` counts (see §5/F11) | **WRONG on counts and date floor** (4-instance figure is right). |
| 20 | "**Three** … corrected with `[CLEANUP 2026-10-17: …]` annotations"; M5-2 NOWAIT + `BoundedKey` default genuinely closed | the diff (3 additions); `CoachReviewRepository.java:43`; 5 converted call sites; `ConfigBounds.java:59-82` | **CONFIRMED** — exactly 3 annotations added, and both underlying closures independently verified in source. |
| 21 | Did the prune delete any bullet the Source table then cites? | all five bullet phrases grepped in HEAD **and** working tree | **CONFIRMED CLEAN — no self-inflicted dangling citation.** Prune touched HEAD lines ~787–3110; the five cited bullets live at `:3205-3273`. |

---

## 3. Mechanistic claims

All 25 verified. Condensed; the six I flagged as highest-risk are marked ⚑.

| # | Claim | Verdict | Deciding evidence |
|---|---|---|---|
| 1 | `@RequiredArgsConstructor` makes `SecurityUtil` additive | **CONFIRMED** | `ReportGenerationService.java:72-94`; no explicit ctor anywhere; 15 current deps, no `SecurityUtil`. |
| 2 | `isCurrentUserInRole(AuthoritiesConstants.COACH)` works | **CONFIRMED** | `SecurityUtil.java:126-133` compares `new SimpleGrantedAuthority(authority)` with no prefix manipulation; `AuthoritiesConstants.COACH = "ROLE_COACH"` — prefix included, so it matches. Already the live pattern at `CoachPlayerAuthorizationService.java:106`. |
| 3 ⚑ | `SecurityUtil.getCurrentCoachUserId()` exists | **CONFIRMED** | `SecurityUtil.java:293-303`, returns `Long`. Note: despite the name it returns a **`main."user".id`**, not a coach-profile id. Confusable neighbour `requireCurrentUserId()` (`:263-273`) resolves the same value. |
| 4 | `getCoachIdByUserId` returns `UUID`, throws `ResourceNotFoundException` | **CONFIRMED** | `CoachProfileService.java:707-711`. Confusable neighbour `requireProfile(Long)` (`:713-717`) throws `MarketplaceException` instead — story picked the right one. |
| 5 | "the same method `CoachPlayerAuthorizationService` itself uses" | **WRONG** | See §5/F6. |
| 6 ⚑ | `r.getCoachId().equals(currentCoachId)` compares like-for-like | **CONFIRMED** | `PerformanceReport.java:29 private UUID coachId;` (repo returns **entities**, not a projection); `getCoachIdByUserId` returns `UUID`. Both sides `java.util.UUID`. `nullable = false` (`:28`) ⇒ NPE-safe. |
| 7 | Order is query → `coachNames` → signed URLs; filtering first is safe | **CONFIRMED** | `:255-258` then `:259-273`. `coachIds` derives purely from `reports`; `coachNames.getOrDefault(…, "Unknown Coach")` tolerates a shrunken map. A filter inserted after `:254` is clean. |
| 8 ⚑ | `SessionCompletionData.coachId` is the `CoachProfile` PK — so `cp.getId()` is right | **CONFIRMED** | `SessionCompletionData.java:31-32` `UUID` (rules out a `bigint` user id). Both write sites: `BookingCompletionService:152-154` `scd.setCoachId(coach.getId())` where `coach = resolveCoach(coachUserId)`; `:255-257` `scd.setCoachId(booking.getCoachId())`, itself a `findById`-validated `CoachProfile` PK. |
| 9 | `eraseTransactional` is `@Transactional(REQUIRES_NEW)` | **CONFIRMED** | `GdprErasureService.java:258-259`. Commits together: `GdprRequest` status writes, user anonymisation `:270-282`, coach branch `:285-290`, message deletes `:293-296`, review deletes/anonymise `:299-300`, `markAllUsedByUserId` `:412`, export enqueue `:423-424`. **Not** included: per-child development deletions, which run in their own `requiresNewTemplate` tx (`:1127-1128`). |
| 10 | Lock-budget machinery is in `deletePlayerDevelopmentData` | **PARTIALLY-WRONG (attribution)** | The machinery is entirely in **`deletePlayerDevelopmentDataInDedicatedPool`** (`:1127-1211`); `deletePlayerDevelopmentData` (`:1090-1104`) is a 5-line routing wrapper, not even `@Transactional`. Mechanism conclusion still sound. |
| 11 | `anonymizeByPlayerId` is player-side only; coach erasure never touches the table | **CONFIRMED (both halves)** | Exactly 2 grep hits: declaration `:43`, one call at `GdprErasureService:1199`. Role dispatch `:327-408` has a `PLAYER` arm and a `PARENT` arm and **no `COACH` arm**. The gap AC2 describes is real. |
| 12 | No flush/clear hazard with the preceding `save(cp)` | **CONFIRMED** | JPQL (not native) ⇒ auto-flush only on intersecting query spaces, and `session_completion_data` doesn't intersect `coach_profiles`; same tx either way; no managed `SessionCompletionData` instance exists to go stale. Framing correction: the repo is *mixed* (`CoachProfileRepository:57` uses `clearAutomatically = true`), but the GDPR-anonymisation convention is bare `@Modifying`, which the story's snippet matches. |
| 13 | `playerId` is "a plain auto-generated numeric id" | **PARTIALLY-WRONG; conclusion holds** | `BaseEntity.java:27-29` is `@Id @Tsid … Long id` (`io.hypersistence.utils.hibernate.id.Tsid`) — a client-side 64-bit time-sorted id, not a sequence. `\d+` matches it fine. See §5/F13 for why the wording still matters. |
| 14 | All seven routes uniformly carry numeric PlayerProfile PKs | **CONFIRMED** | All seven components consume the param as a raw string fed to a PlayerProfile-PK API (none parses it); backend declares `@PathVariable Long playerId`. Every navigation call site interpolates a PlayerProfile PK, and both unguarded-looking ones are truthiness-guarded (`MainLayout:355-360`) or null-guarded (`PlayerHomeRedirectPage:39-46`). Coverage note: `routes.js:135` and `:236` have **zero** in-app navigation call sites — deep-link-only, which strengthens rather than weakens AC3. |
| 15 ⚑ | Vue Router 4 supports `(\d+)`; `'\\d+'` correct in context | **CONFIRMED (both halves)** | `package.json:32 "vue-router": "^4.0.0"`, locked at **4.6.4**. `routes.js` uses plain single-quoted strings throughout ⇒ `'\\d+'` evaluates to `\d+`, which is what the matcher needs (a single `'\d+'` would silently mean literal `d+`). In-file precedent: `:389 '/:catchAll(.*)*'`. |
| 16 | A catch-all 404 exists to fall through to | **CONFIRMED** | `routes.js:385-391`, declared last, outside the authenticated layout's `children`. |
| 17 | `Booking.playerId` is a PlayerProfile PK | **CONFIRMED** | `Booking.java:33-34`; every creation path sources it from a `playerProfileRepository.findById`-validated value (`BookingService:180-184,331`; `BookingBatchService:117-118,241`; `BookingDuplicationService:128`). |
| 18 | `authorId` is a `User` id | **CONFIRMED** | `ReviewResource:150-160` `Long.parseLong(p.getBusinessId())`. |
| 19 | `createBookingRequest` sets `parentId` to the caller's own user id unconditionally | **CONFIRMED (nuance)** | `:330`, one unconditional assignment, column `NOT NULL`. Across all paths: `BookingBatchService:240` identical; `BookingDuplicationService:129` copies `original.getParentId()` (inherited, still a `User` id). No coach- or admin-created booking path exists. |
| 20 | `booking.bookings` has no FK on `player_id` | **CONFIRMED** | `V138:227-247` declares `player_id bigint NOT NULL` with no `REFERENCES`; the only two FKs ever added are `batch_id` and `session_pack_purchase_id` (`:4055`, `:4062`). Contrast `:4084`, which *does* constrain a `player_id` elsewhere. |
| 21 ⚑ | `limit` in JPQL + ad-hoc `JOIN … ON` are supported; precedent valid | **CONFIRMED (a–d)** | (a) lowercase `limit 50` at `:154`, no `Pageable` — deliberate, per `:141-143` ("Pageable would break ReviewSubmissionServiceTest's existing mock stub at compile time"). (b) no `nativeQuery` attribute ⇒ JPQL. (c) `pom.xml:5-10` Spring Boot **3.5.16** ⇒ **Hibernate 6.6** (`hibernate-envers` pinned 6.6.58.Final) — HQL `limit` is a Hibernate 6 feature, and the existing query already proves it works at runtime. (d) precedent is non-native, same ad-hoc join shape. |
| 22 | The Java loop checks exactly `userId` OR `parentId` | **CONFIRMED** | `:333`, no age/status/active-flag/null predicate (age-tier discriminator deliberately removed, `:326-332`). Semantically equivalent to the SQL form: Java `.equals` on a null field yields `false`; SQL `= NULL` yields `UNKNOWN` — both filter out. |
| 23 | The `OR b.playerId = :authorId` branch is dead/wrong | **CONFIRMED** | Three independent lines: `@Tsid` generator with **no** `setId` call on a `PlayerProfile` anywhere in `src/main`; `GdprErasureService:314-317` documents this exact hazard ("**a TSID essentially never equals a user id**"); ownership is modelled by dedicated `parentId`/`userId` columns (`PlayerProfile:39-44`, "exactly one of the two is ever set"). A coincidental hit would *grant* eligibility for an unrelated booking — removal is a correctness fix, not cleanup. |
| 24 ⚑ | Removing the `:126` `v-if` is sufficient; item is inside the auth-gated drawer | **CONFIRMED** | Structural trace: `q-drawer v-if="isAuthenticated"` `:109` → `q-list` `:122` → `nav.sectionMain` label `:124` → **target `:126`** → unconditional `/profile` sibling `:135`. The role sections open later (`:145` coach, `:206` parent, `:238` player, `:297` admin). The target is **not** inside any role `<template v-if>`. |
| 25 | `DEFAULT_ROUTE` is the automatic fallback; `ANONYMOUS` genuinely unmapped | **CONFIRMED** | `roleRoutes.js:13-18` has exactly 4 own keys (no `ANONYMOUS`); `routeForRole` uses `Object.hasOwn(...) ? … : DEFAULT_ROUTE` (`:28-30`). Corroborated by `JwtManagerImpl:112-113`. **No change needed in `roleRoutes.js` — the story is right that the gap is purely nav visibility.** |

---

## 4. Corner cases / false assumptions / missed flows

Findings that survived §6's adversarial re-read, most severe first. Evidence re-opened and re-quoted by me personally for every one.

### F1 — BLOCKING. AC5 turns three existing frontend assertions red, and AC6 asserts no such test exists

`src/frontend/src/layouts/__tests__/MainLayoutSpec.js:353-396` contains a dedicated `describe` block for exactly this behaviour:

```js
:362   expect(wrapper.find('[href="/dashboard"]').exists()).toBe(true)    // ADMIN
:371   expect(wrapper.find('[href="/dashboard"]').exists()).toBe(false)   // COACH
:383   expect(wrapper.find('[href="/dashboard"]').exists()).toBe(false)   // PARENT
:393   expect(wrapper.find('[href="/dashboard"]').exists()).toBe(false)   // PLAYER
```

and at `:363-364` the spec **literally predicts this story's change**:

> `// Mutation: remove v-if="authStore.isAdmin" from the /dashboard q-item → still true for every role below, so those three negative assertions would be the ones to go RED instead.`

AC6 states: *"No backend test exists for AC3 (pure router config) or AC5 (pure template-condition change) — both are small enough that a manual check … is sufficient; do not add a new test file solely for either."* That is correct that no *new* file is needed, and wrong that nothing covers it: an **existing** spec must be updated, and `MainLayoutSpec.js` appears in neither AC6, the Tasks, nor any File List. **Failure scenario:** apply Task 5 → CI frontend job → 3 assertions fail. **Confidence: HIGH** (assertions and the mutation note are verbatim; I re-read the whole block).

### F2 — BLOCKING-adjacent. AC5 creates two nav items both labelled "Dashboard" for PARENT and for PLAYER

- `MainLayout.vue:126` (the item AC5 ungates) → `to="/dashboard"`, `name="dashboard"` icon, label `{{ $t('nav.dashboard') }}`
- `:209-215` Parent section → `to="/parent/dashboard"`, **same icon, same label**
- `:241-248` Player section → `to="/player/dashboard"`, **same icon, same label**
- `i18n/en-US/index.js:1582` → `dashboard: 'Dashboard'`

**Failure scenario:** a PARENT opens the drawer and sees two adjacent entries, identical label and identical icon, pointing at different pages — one of which is a four-`—`-metric placeholder. Same for PLAYER.

AC5's justification inverts this: it says the admin *"sees this generic one redundantly"* while having their own entry — but the admin's own entry is labelled `$t('nav.healthDashboard')` (`:300`), a **different** label, so the admin has no collision. The roles AC5 claims to help (PARENT, PLAYER) are precisely the ones that get a genuine label collision; the only role with no collision is the `ANONYMOUS` case, which has no section at all. **Confidence: HIGH.**

### F3 — BLOCKING. The `markFailed` auto-retry the story declines as open work is already built and shipped

The story's "Explicitly NOT picked up" item #1 and Owner decision #3 both treat GDPR erasure auto-retry for `markFailed` as live open work, declined in favour of "admin-alert-plus-manual-reconcile", on the grounds that auto-retry "is a materially larger change … as the ledger's own prior entries on this already noted".

It exists:

```java
// GdprErasureRetryScheduler.java:32-38
@SchedulerLock(name = "GdprErasureRetryScheduler_retryFailedErasures",
               lockAtMostFor = "PT30M", lockAtLeastFor = "PT2M")
@Scheduled(cron = "0 0 6 * * *")
public void retryFailedErasures() {
    log.info("[GDPR_ERASURE_RETRY_SWEEP] Running scheduled GDPR erasure retry sweep");
    gdprErasureService.retryFailedErasures();
}
```

with `GdprErasureService.retryFailedErasures()` at `:571` and its sweep-sizing comment at `:197` crediting `skillars-deferred-136 AC2`; `GdprRequestRepository:24` names the same AC. The pre-prune ledger bullet (`git show HEAD:…:2726-2765`) carries both annotations — first `[CLOSED by skillars-deferred-133 AC1 — alerting only, auto-retry stays open]`, then **`[CLOSED by skillars-deferred-136 AC2 — the "auto-retry stays explicitly open" residual this bullet named is now built…]`**.

**The story contradicts itself in its own document:** its ledger-hygiene edit writes *"a third, the `markFailed` alerting/auto-retry gap, was itself fully closed by skillars-deferred-133 AC1 then skillars-deferred-136 AC2, and has been pruned."* **Failure scenario:** the owner was asked to decide, and recorded a decision, about work that does not exist; a reader of the story's exclusion list is told a shipped capability is an accepted gap. **Confidence: HIGH** — scheduler, service method, cron, and both ledger annotations re-read directly.

### F4 — AC4's inner JOIN breaks the existing `BookingRepositoryIT` test, and AC6 misdiagnoses why

`BookingRepositoryIT.java:18-20` (class javadoc): *"bookings.coach_id/parent_id/player_id carry no FK constraints, so this seeds Booking rows directly **with no coach/player/parent fixture data required**."*

`findQualifyingCompletedBookings_ordersAndCapsDeterministically` (`:211-299`) seeds 55 bookings with `setPlayerId(1..55)` plus four negatives at `-4..-1`, **creates zero `main.player_profiles` rows** (grep for `player_profile` in the file: no hits), and asserts:

```java
:296   assertThat(playerIds).hasSize(50);
:299   assertThat(playerIds).isEqualTo(LongStream.rangeClosed(1, 50).boxed().toList());
```

With `JOIN PlayerProfile pp ON pp.id = b.playerId`, every seeded row is excluded → result size **0** → both assertions fail.

AC6 anticipates only *"fix any fixture that relied on the removed `OR b.playerId = :authorId` branch"* — but **no fixture here relies on that branch** (`authorId = 1L`, playerIds `1..55`; only `playerId = 1` collides, and it already matches via `b.parentId`). The real cause is the missing `player_profiles` rows. Fixing it means inserting 59 profile rows satisfying `name`/`date_of_birth`/`position`/`age_tier` NOT NULL plus `chk_pp_owner`, and extending the teardown (`:39-42`, bookings only) — substantially more than AC6 describes. **Confidence: HIGH.**

*Fixture caveat for the new rows:* the existing test correctly derives start/end from one `Instant now` (`:220`, `:226-227`). Two separate `Instant.now()` calls flake `chk_bkg_end_after_start` in CI — a known recurring failure mode in this repo.

### F5 — AC2's "needs no lock_timeout" contradicts the ledger bullet it cites, its own nearest precedent, and the schema

AC2 asserts the new UPDATE *"does **not** need the per-statement `lock_timeout`/cumulative-lock-budget machinery"* and is *"at the same weight as"* `anonymiseApprovedReviews`. Three independent contradictions:

1. **The sibling statement carries one.** `GdprErasureService.java:1188-1199` — deferred-153 AC2 deliberately kept `anonymizeByPlayerId` *"as its own independently-bounded step … same per-statement lock_timeout convention (`setStatementLockTimeout`), but using the full (unshrunk) `lockTimeoutSeconds` and NOT counted against `remainingLockBudget`"*. Being outside the cumulative budget is the reason it **gets** its own timeout, not a reason to skip one — AC2 draws the opposite inference.
2. **The cited ledger bullet prescribes one.** `deferred-work.md:3272`: *"the fix is a second `@Modifying` statement keyed on `coach_id`, inserted into the coach branch **with its own lock-budget accounting**."* AC2 departs from the text it cites without naming the departure.
3. **The weight comparison is factually wrong.** `anonymiseApprovedReviews` is `WHERE author_id = … AND moderation_status = 'APPROVED'`, index-backed by `idx_coach_reviews_author_id` (`V138:3922`). `booking.session_completion_data` has **exactly one index** — `idx_scd_booking_id` (`V138:3280`) — and **nothing on `coach_id`** (I grepped every index statement on that table; one hit). So `anonymizeByCoachId` is a seq scan + bulk UPDATE of every session the coach ever completed.

**Failure scenario:** a coach with a long history is erased; `eraseTransactional` has no `lock_timeout` (Postgres default `0` = wait forever); a concurrent `submitWrapUp`/`QuickCompleteTimeoutService` holds a row lock on one of those rows → the UPDATE blocks indefinitely, pinning the connection rather than failing cleanly into `markFailed`. **Confidence: HIGH** on all three factual claims; **MEDIUM** on reachability at current data volumes.

### F6 — AC1's precedent attribution is wrong, and following it literally yields the wrong HTTP status

AC1 says to resolve the coach id *"via `coachProfileService.getCoachIdByUserId(...)` (**the same method `CoachPlayerAuthorizationService` itself uses** to resolve a coach profile from a user id … matching this codebase's existing precedent for that edge case)"*.

`CoachPlayerAuthorizationService` does not use it, does not inject `CoachProfileService` (fields `:40-43`), and appears in none of the method's 26 call sites. Both its resolution points inline the repository call with a **different exception**:

```java
// CoachPlayerAuthorizationService.java:50-53 (identically at :76-79)
UUID coachProfileId = coachProfileRepository.findByUserId(coachUserId)
    .map(CoachProfile::getId)
    .orElseThrow(() -> new OperationNotAllowedException(
        "Coach profile not found", SecurityError.MISSING_RIGHTS));
```

`OperationNotAllowedException`/`MISSING_RIGHTS` → **403**, vs `ResourceNotFoundException` → **404**. So for "authenticated coach with no profile row", the gate at `:254` returns 403 while AC1's new line two statements later would return 404. The *correct* same-class precedent for `ResourceNotFoundException` is `ReportGenerationService.generateReport:136`, which already calls `getCoachIdByUserId`. **Two layers reached this independently; I verified the absence myself (grep: no hits).** **Confidence: HIGH** on the misattribution; the fix AC1 wants still works, so this is a citation defect with a behavioural tail, not a blocker.

### F7 — AC1's role-keyed filter does not mirror the gate's parent/self-owner escape arm

The gate AC1 sits behind has an ownership escape arm — `CoachPlayerAuthorizationService.java:105-111`:

```java
public void requireCoachPlayerRelationshipIfCoach(Long playerId) {
    if (securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH)
            && !playerProfileRepository.existsByIdAndParentId(playerId, securityUtil.requireCurrentUserId())
            && !playerProfileRepository.existsByIdAndUserId(playerId, securityUtil.requireCurrentUserId())) {
        requireCoachPlayerRelationshipForRead(securityUtil.getCurrentCoachUserId(), playerId);
    }
}
```

A caller holding `ROLE_COACH` who is *also* the player's parent/self-owner passes **as an owner**, with no relationship check. AC1 specifies an **unconditional** `if (isCurrentUserInRole(COACH))` filter, which would then violate AC1's own hard requirement (*"a parent/player caller … must keep seeing every coach's reports for their own child, unfiltered"*) for that caller — and would in fact **throw** 404, since `getCoachIdByUserId` finds no profile.

Not reachable today, and the codebase says so in the very Javadoc above that method: *"Zero-behavior-change-risk for the self-owner-arm fix today — **no production path grants a user both ROLE_COACH and a self-owned PlayerProfile** — but is free defense-in-depth once centralized here."* But deferred-153 AC6 added that arm deliberately after finding all five copies missing it; AC1 reintroduces exactly the divergence that consolidation removed. **Fix:** mirror the gate's condition, or resolve via `findByUserId` and skip filtering when absent. **Confidence: HIGH** on the divergence, **LOW** on triggerability today.

### F8 — AC4 leaves the service-side cap warning asserting something that becomes false

`ReviewSubmissionService.java:314-318`:

```java
if (qualifying.size() >= QUALIFYING_BOOKINGS_CAP) {
    log.warn("findQualifyingCompletedBookings hit its {}-row cap for coachId={}, authorId={} -- "
            + "the owned playerId may have been truncated if it sorts above the cap",
```

After AC4 every returned row is owned by construction, so "the owned playerId may have been truncated" is false; the real residual becomes "this author has >50 distinct owned children with this coach". AC4 instructs updating only *"the method's own comment block"* (the repository's). The service-side log string, and its 8-line rationale comment at `:298-305`, are in no AC, Task, or File List. Note also `QUALIFYING_BOOKINGS_CAP = 50` at `:305` is a hand-duplicated literal that `:298-304` explicitly says must be kept in sync with `BookingRepository.java:154`. **Failure scenario:** the warning fires and sends an engineer hunting a false-deny that can no longer happen. **Confidence: HIGH.**

### F9 — AC4's orphaned-row problem is unreachable; one of AC6's required tests targets an unproducible state

AC4's second "distinct problem" is a booking whose `PlayerProfile` has been deleted occupying a cap slot. **Nothing deletes a `player_profiles` row.** `PlayerProfileRepository` declares no `delete*` method; `playerProfileRepository.delete*` has **zero call sites** in `src/main`; there is **no** native `DELETE … player_profiles` in `src/main/java` or `src/main/resources` (all three greps re-run by me, all empty). `GdprErasureService` deletes development data and sets a `developmentDataErasedAt` tombstone — `PlayerProfile.java:55-70` documents that *"a `player_profiles` row is never 'un-erased'"*.

Consequences: AC4's net *production* behaviour change is zero (the dead `OR` clause removal remains a genuine correctness fix — see §3 #23); `ReviewSubmissionService:323-325` is already dead code for the same reason; and **AC6's required test "(a) an orphaned `playerId` … no longer occupies a cap slot" tests a state no code path can produce** — it needs a hand-planted raw-SQL fixture. The ledger bullet (`:3253`) makes the same overstatement, so this is not the story mis-paraphrasing its source. Keeping the fix as defense-in-depth is defensible; asserting a live cap-slot-waste risk is not. **Confidence: HIGH.**

### F10 — AC2 nulls the source columns but leaves a verbatim copy in `player_timeline_events.metadata`

`TimelineEventListener.java:56-57`:

```java
if (event.getWhatWeDid() != null) metadata.put(METADATA_KEY_WHAT_WE_DID, event.getWhatWeDid());
if (event.getWhatsNext() != null) metadata.put(METADATA_KEY_WHATS_NEXT, event.getWhatsNext());
```

written into every `SESSION_COMPLETED` timeline row. Coach erasure never touches `player_timeline` — only `deletePlayerDevelopmentData` (the **player** branch) deletes timeline rows. So after AC2, `session_completion_data.{what_we_did, whats_next}` are NULL while the identical strings persist indefinitely in `development.player_timeline_events.metadata`. The story's user-story promise — *"an erased coach's authored recap notes cannot outlive their own erasure"* — is not fully met.

Mitigating, and why this is MEDIUM not HIGH: `TimelineQueryService.redactRecapIfNotAuthoringCoach` (`:76-89`) strips both keys for every viewer except the authoring coach, whose account is now locked. So the surviving data is **inaccessible, not exposed** — an Art. 17 completeness gap, not a leak. **Confidence: HIGH** the data survives; **MEDIUM** that the owner treats inaccessible-but-retained as a defect.

### F11 — The ledger-hygiene self-report's counts are inflated

Dev Notes claim: *"pruned **16** bullets … tagged `[CLOSED by …]` with the tombstone never deleted (**12** bullets, spanning sections from **2026-08-11** through 2026-09-23), or orphaned HTML-comment closure notes (**4** instances)"*.

My own counts on the uncommitted diff (`17 insertions / 261 deletions`):

| Claimed | Actual |
|---|---|
| 12 `[CLOSED by`-tagged bullets | **10 `[CLOSED by` tags removed** (file total 31 → 21), carried on **6–8 bullets** depending on how a multi-line bullet is counted |
| 4 orphaned comment instances | **4 instances** ✅ (8 comment lines, all `skillars-deferred-100 AC7`) |
| 16 bullets total | **~10–12 items total** |
| floor 2026-08-11 | **2026-08-03** (`skillars-11-1-payment-path-parity-gaps`) |
| 3 `[CLEANUP 2026-10-17]` annotations | **3** ✅ |

Also undisclosed: the diff deletes an intro paragraph and merges two identically-titled `skillars-uat-3` section headings — structural edits, not prunes of resolved bullets. And **21 `[CLOSED by` tombstones remain** after a sweep described as "full" (some are narrowing annotations on still-open bullets, so this is a caveat on the word "full", not 21 misses). *Note: Layers 2 and 4 disagreed here (8 bullets vs ~9 tombstones); I counted myself and report the range honestly rather than pinning a number the evidence doesn't pin.* **Confidence: HIGH** that the claimed figures are overstated; **MEDIUM** on the exact bullet count.

### F12 — AC2 closes one coach-authored-free-text family and leaves a direct sibling open

`PerformanceReport.java:42-43`:

```java
@Column(name = "next_steps", nullable = false, updatable = false, length = 500)
private String nextSteps;
```

Coach-authored (`ReportGenerationService:206 report.setNextSteps(nextSteps)`), and `PerformanceReportRepository` exposes only player-keyed access (`findStorageKeysByPlayerId:20`, `deleteAllByPlayerId:28`) — the **identical** "keyed on playerId only" pattern AC2 names as its own root cause. A coach's erasure never touches it, so their authored next-steps text (and the PDF in S3) survive indefinitely. `updatable = false` means an anonymisation UPDATE needs a native query or the flag dropped — not a drop-in copy of AC2's shape.

Also in the same branch: `CoachProfile.displayName` is **not** anonymised (only `bio`/`city`/`district`), and is surfaced by `getDisplayNamesByIds` on every `listReports` response — an erased coach's real name still renders. Arguably outside AC2's "free text" framing.

AC6 instructs deleting the deferred-153 ledger bullet on completion; that bullet's wording maps to `whats_next`, so deletion is technically satisfied — but the sibling above would be **closed-by-deletion without being closed**. **Confidence: HIGH** the data survives; **MEDIUM** on whether the owner scopes it in.

### F13 — AC3's stated rationale is wrong about the id type (instruction still correct)

AC3: *"`playerId` is always a `PlayerProfile` primary key — **a plain auto-generated numeric id** (`PlayerProfile extends BaseEntity`, no custom `@Id`)"*. `BaseEntity.java:27-29` is `@Id @Tsid … Long id` — an 18–19-digit client-side time-sorted id, not a sequence. `\d+` matches it, so **the instruction is safe and the AC's conclusion holds**. It matters because the TSID fact is load-bearing elsewhere: `CoachPlayerDetailPage.vue:89-91` calls `Number(route.params.playerId)` *"a twice-fixed production bug"* (deferred-147/-148, TSIDs exceed `Number.MAX_SAFE_INTEGER`), and `skillars-12-4` AC5 — the story AC3 cites as its source — is emphatic about it. A future reader reasoning from "plain auto-generated numeric id" could reintroduce that bug. **Confidence: HIGH.**

### F14 — AC5 reverses a deliberate prior AC and forecloses that story's own open question

`skillars-deferred-141` AC1 is titled *"Gate the generic 'Dashboard' nav item to Admin only"* — the admin-only state is a deliberate AC, not an oversight. The source ledger bullet (`:3209-3219`) says the gap is *"**Currently unreachable, by verification**"*, concludes *"**No action needed while the enum holds at four**"*, and prescribes *"either give it a `ROLE_ROUTES` entry or restore a **role-appropriate** nav entry"* — AC5 does neither, which is what produces F2. Meanwhile deferred-141's own Future Follow-Ups (`:210`) leave open the **opposite** direction: *"Whether `/dashboard` itself should become route-guarded to Admin only (not just hidden from the menu)"*. AC5 answers that standing question "looser" without acknowledging it exists.

Owner decision #2 covers the product call, so this is a framing defect, not an unauthorised change: the story presents AC5 as closing a *gap* rather than reversing a prior decision and foreclosing a recorded follow-up. **Trigger sourcing:** the ledger bullet contains **no mention of `ANONYMOUS`**; the Source table presents *"revisit trigger fired by the `ANONYMOUS` … value"* as ledger-derived. It is the story's own inference — and it is **sound** (the enum now holds five values and `JwtManagerImpl:136-152` does emit `ANONYMOUS`, so the ledger's "never emitted" is stale), but it is an inference presented as a citation. **Confidence: HIGH.**

### F15 — Smaller scoping gaps

- **`GdprErasureIT` fixture.** The file seeds exactly one coach profile (`coachProfileId`, `:146-147`). AC6's required case *"unaffected for an unrelated coach's sessions"* needs a **second** `marketplace.coach_profiles` row — new fixture work no Task mentions.
- **AC1 has no UI surface.** `PerformanceReportsPanel.vue` mounts only on `ParentDevelopmentPortalPage:108` (`:is-coach="false"`) and `PlayerDevelopmentDashboardPage:104-107` (a `role: 'PLAYER'` route). **No coach-facing page calls `listReports`** — the exposure is API-direct only, so AC6's "manual check" has nothing to look at for AC1. The fix is still worth making.
- **`isSweeperPlaceholder` collision.** `BookingCompletionService:325-330` tests `!playerAttended && whatWeDid == null && whatsNext == null && voiceNoteText == null`. After `anonymizeByCoachId`, a genuine no-show session becomes indistinguishable from a sweeper placeholder. Unreachable in practice (erased coach's account is locked); noted for completeness.
- **Citation hygiene** (from §1): `ReviewResource` `:98` → `:99`; the `BookingRepository` and `evaluateEligibility` ranges are truncated such that the comment block AC4 tells the dev to edit (`:128-144`) falls outside the cited span.

---

## 5. What did not survive re-verification

Listed because a report with uniformly high confidence and no calibration differences is itself evidence that this step was skipped. Twelve items died or were downgraded.

1. **The admin over-filter bug — KILLED.** I briefed Layer 4 that this was the single most likely defect: `@PreAuthorize` allows `ROLE_COACH` *or* the ownership guard, so an admin might carry `ROLE_COACH` and be filtered to zero reports. It does not happen. Registration assigns exactly one authority (`AdminBootstrapRunner:204 Set.of(adminAuthority)`; same shape in `Coach`/`Parent`/`PlayerRegistrationService`), nothing mutates authorities later, and there is **no `RoleHierarchy` bean anywhere**. An admin already fails both branches and gets 403 today — AC1 changes nothing for them.
2. **A "second door" leaving AC1's exposure open — KILLED.** This would have made AC1 incomplete. `PerformanceReportResource` has exactly **two** mappings (`POST` generate, `GET` list — verified by grepping every annotation in the file); there is no `/reports/{id}` or download-by-id endpoint; `PerformanceReportResponse` does not expose `storageKey`; `listReports` has exactly one backend caller. **AC1 closes the only door.**
3. **AC3 breaking live navigation — KILLED.** Every entry point passes digits or is guarded: `MyPlayersPage:111-116`, `ParentChildSwitcher:68-72`, `ParentDevelopmentPortalPage:221-227`, `ParentPlayerPortalPage:13`, `MainLayout:355-360` (truthiness-guarded), `PlayerProfileBuilderPage:66`, `PlayerHomeRedirectPage:39-46` (null-guarded). No `localStorage`-sourced ids, no backend-generated deep links to any of the seven paths.
4. **AC3 breaking an existing test — KILLED.** `routerGuardSpec.js` uses `893573203704173564` at `:136,144,152,164,172` — all digits, passes `\d+`. Page specs build their own local routers.
5. **AC3 changing route-match order — KILLED.** No sibling under the four parents can swallow a previously-matched path; the catch-all picks up the miss as designed.
6. **AC4 regressing a legitimately-entitled author — DOWNGRADED TO NONE.** My hypothesis was that switching from the booking's `parentId` to the profile's *current* `userId`/`parentId` would deny eligibility where ownership changed. Ownership is **immutable**: `setParentId`/`setUserId` on `PlayerProfile` are called only at creation (`ShadowAccountService:60,105`), never reassigned or nulled, under `chk_pp_owner`; and `createBookingRequest:181-188` validates `b.parentId` equals one of them before writing. The two predicates are equivalent for every legitimate author.
7. **Row multiplicity from the new join — KILLED.** `PlayerProfile → Booking` is one-to-many on `pp.id = b.playerId`, so `DISTINCT`/`ORDER BY`/`limit 50` behave identically. Cross-schema join within one Postgres DB is fine.
8. **Hibernate not supporting HQL `limit` or ad-hoc `JOIN … ON` — KILLED.** Hibernate **6.6** (Spring Boot 3.5.16), and the *existing* query already contains `limit 50` and runs today, which settles it empirically as well as by version.
9. **`'\\d+'` being wrongly escaped — KILLED.** `routes.js` uses plain single-quoted strings, so `'\\d+'` is exactly right; a single backslash would have been the bug. Vue Router locked at 4.6.4.
10. **`getCurrentCoachUserId()` not existing — KILLED.** It exists (`SecurityUtil:293-303`). I had flagged this as high-risk because the name implies a coach-profile id; it returns a user id, which is what AC1 needs.
11. **Type mismatch / flush hazard / wrong `coachId` id-space in AC2 — ALL KILLED.** Both sides of the `.equals()` are `UUID`; the JPQL UPDATE's query spaces don't intersect the dirty `CoachProfile`; `SessionCompletionData.coachId` is confirmed a `CoachProfile` PK at both write sites, so `cp.getId()` is correct.
12. **`DashboardPage.vue` being admin-specific or 403-prone — KILLED, with a wording caveat.** Layers 1 and 4 disagreed here. Resolution: the page has **no** admin content and makes **no** admin-only call, so AC5's operative premise (a non-admin can land there harmlessly) **holds**; but the story's word "role-agnostic" is literally false (`:56-60` parent-gated `loadParentBookings()`, `:3-6` `TimezoneNotice`) and one of four metrics is `'99.9%'`, not `'—'`. Recorded as §1 #18, not as a corner-case finding.

Also downgraded: Layer 2's precise prune counts (8 bullets / 12 items). My own count gives 10 `[CLOSED by` tags and a 6–8 bullet range; the *substance* (the story's figures are inflated) stands, the exact number does not.

---

## 6. Recommendation

**Per-claim confidence, not a blanket score.** What was independently re-checked by me in Step 4b, versus what rests on a single layer's reading, is stated explicitly below.

### Fix before implementation starts (3)

| # | Finding | Why it blocks | Re-verified by me |
|---|---|---|---|
| F1 | `MainLayoutSpec.js:353-396` — 3 assertions go red; AC6 says nothing covers AC5 | CI frontend job fails on Task 5 | ✅ full block re-read, incl. the mutation note that predicts this change |
| F4 | `BookingRepositoryIT` returns 0 rows under the new inner join; AC6 names the wrong cause | CI backend job fails on Task 4, and the stated diagnosis won't lead the dev to the fix | ✅ fixture and both assertions re-read; confirmed zero `player_profiles` rows |
| F3 | `markFailed` auto-retry already shipped (deferred-136 AC2) | the story records an owner decision about non-existent open work and contradicts its own ledger edit | ✅ scheduler, cron, service method, and both ledger annotations re-read |

### Resolve with the owner before or during implementation (3)

- **F2** — two identical "Dashboard" nav items for PARENT and PLAYER. AC5's stated rationale is inverted; the source ledger prescribed a *role-appropriate* entry, which would avoid this. Needs a product call (relabel, reposition, or restrict to the `ANONYMOUS` case).
- **F5** — AC2's "no `lock_timeout` needed" contradicts the ledger bullet it cites, deferred-153 AC2's precedent, and the schema (no index on `coach_id`). Either add `setStatementLockTimeout(...)` as the sibling statement does, or record an explicit decision to override the cited guidance.
- **F9** — AC4's orphan premise is unreachable (nothing deletes a `PlayerProfile`), so AC6's test case (a) targets an unproducible state. Keep the fix as defense-in-depth; fix the framing and drop or re-scope that test.

### Correct in the story text — no code impact (6)

F6 (precedent misattribution + 403/404 inconsistency; use `ReportGenerationService:136` instead), F7 (mirror the gate's self-owner arm), F8 (service-side cap warning + hand-duplicated constant are out of scope as written), F13 (TSID, not "plain auto-generated numeric id"), F11 (prune counts inflated; date floor 2026-08-03), F14 (AC5 reverses deferred-141 AC1 and forecloses its follow-up; the ANONYMOUS trigger is the story's own sound inference, not ledger text). Plus §2 #6 and #11 (paraphrase-as-quote; non-existent column comment) and §1 #10/#18 (`:98`→`:99`; "role-agnostic" is false).

### Scope additions worth a decision (2)

F10 (recap text survives in `player_timeline_events.metadata` — inaccessible, but AC2's stated promise is unmet), F12 (`PerformanceReport.nextSteps` and `CoachProfile.displayName` survive coach erasure via the same pattern AC2 names as root cause; the ledger bullet would be closed-by-deletion without being closed).

### What is solid

The five ACs' **mechanical foundations all hold.** All six claims I flagged as highest-risk — `getCurrentCoachUserId()`'s existence, the `UUID`/`UUID` comparison, `SessionCompletionData.coachId`'s id space, the Vue Router version and backslash escaping, Hibernate 6's support for HQL `limit` plus ad-hoc `JOIN … ON`, and the `:126` template nesting — came back **CONFIRMED with exact types, versions, and structure verified rather than assumed**. The dead `OR b.playerId = :authorId` clause is genuinely dead *and* a real correctness risk if it ever coincidence-matched (§3 #23). AC1 closes the only door to report data. AC3 breaks no navigation and no test. AC5's landing page is harmless for non-admins. 12 of 18 citations are exact matches, including all three count claims (12 statuses, 2 construction sites, 7 routes), and the prune did not orphan any bullet the story cites.

**Not independently re-checked by me** (single-layer reading, reported as such): the exhaustive "no backend-generated deep link to the seven routes" sweep; the 26-site `getCoachIdByUserId` call-site census; the full enumeration of `player_timeline` read paths; and the claim that no other repository query confuses the two id spaces. Each was asserted by one layer with grep evidence, and none is load-bearing for the blocking findings above.

**Verdict: the story is well-built and its core design decisions are sound — but it should not go to a dev as-is.** Two ACs break existing CI tests with no acknowledgement (F1, F4), and one exclusion decision was taken against already-shipped work (F3). Those three are mechanical fixes to the story, not redesigns. The AC5 nav duplication (F2) is the one item that likely needs the owner to revisit a product choice.
