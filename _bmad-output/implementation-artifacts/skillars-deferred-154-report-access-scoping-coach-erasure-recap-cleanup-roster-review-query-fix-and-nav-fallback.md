# Story: Report-Access Scoping, Coach-Erasure Recap Cleanup, Review-Eligibility Query Correctness, and Navigation Fallback Fix

**Status:** done

**Scope Level:** Medium (five independently-verified, currently-open items across `development`/`admin`/`booking`/`reviews` modules and the frontend router/layout; one new repository method, one `@Query` rewrite adding a `PlayerProfile` join, two constructor-dependency additions, one router-config change, one template-condition change. Several product/scope decisions were resolved directly with the project owner before this story was drafted, and the story was then independently reviewed — see Dev Notes for both the decisions and the corrections the review produced.)

---

## User Story

As **a coach, parent, and the developer maintaining the `development`/`admin`/`booking`/`reviews` modules' authorization and query correctness**, I want five independently-verified, currently-open defects fixed — a coach-authored performance-report PDF no longer visible to every other coach who merely has a billing-status relationship (not an active coaching one) with the same child, a coach's own session-recap free text anonymised when *they* (not just the player) are erased under GDPR, every numeric `:playerId` route parameter rejecting a malformed value at the router instead of reaching the page with a garbage id, a review-eligibility query's ownership check pushed from a loose/dead filter into the query itself so it can never admit a row that was never actually eligible, and the `/dashboard` fallback nav link made visible to the one role that currently has no landing nav of its own, without duplicating or disturbing the three roles that already have one — so that **a coach can no longer read another coach's report PDF for a child they merely have a billing dispute with; an erased coach's authored recap notes cannot outlive their own erasure; a typo'd `/coach/players/abc` falls through to a 404 instead of hitting the detail page with `playerId=NaN`; a review-eligibility query can never grant eligibility via a numeric coincidence between an author's user id and an unrelated child's profile id; and an account with no mapped role (today, `ANONYMOUS`) gets a way back to `/dashboard` from the drawer, while coach, parent, and player keep exactly the dedicated nav entries they already have.**

---

## Source

All five items are mined from `_bmad-output/implementation-artifacts/deferred-work.md`, each independently re-verified against real HEAD `9ebaec14` (not taken on the ledger's word — citations below correct line-number drift from the ledger's own text, and all were re-checked a second time after this same session's own ledger-hygiene pass pruned roughly 10–12 unrelated, already-closed items elsewhere in the file — see Dev Notes "Ledger hygiene" for the exact, independently-recounted figures). All scope decisions needed to bound these items were resolved directly with the project owner before drafting, and the whole story was then independently re-reviewed before implementation (see Dev Notes "Owner decisions" for both the original decisions and the corrections that review produced).

| AC | `deferred-work.md` section (own date) | Note |
|----|------|---------|
| 1 | "Deferred from: code review of skillars-deferred-153" (2026-10-17) — cross-coach report-PDF exposure bullet | newest section in the file |
| 2 | Same section (2026-10-17) — coach-side GDPR recap-anonymisation gap bullet | newest section in the file |
| 3 | "Deferred from: code review of skillars-12-4-coach-facing-player-detail-page" (2026-10-16) — no param constraint on `coach/players/:playerId` | distinct, earlier section |
| 4 | "Deferred from: code review of skillars-deferred-150" (2026-10-09) — `findQualifyingCompletedBookings` cap-vs-correctness bullet | distinct, earlier section |
| 5 | "Deferred from: code review of skillars-deferred-141" (2026-10-03) — `/dashboard` nav gap; `skillars-deferred-142` AC2 (2026-10-05) later added the `ANONYMOUS` role this AC's fix responds to | distinct, earlier section — the `ANONYMOUS` trigger is this story's own inference from the current code, not ledger text (the bullet itself never mentions `ANONYMOUS`) |

Four distinct sections, spanning 2026-10-03 → 2026-10-17 — not, as an earlier draft of this table implied, one shared "last section."

### Explicitly NOT picked up (re-verified, excluded — do not re-add to this story)

- **GDPR erasure auto-retry for `markFailed` — correction, not a decision.** An earlier draft of this story asked the owner to decide between auto-retry and the existing admin-alert-plus-manual-reconcile design, and recorded a decision to keep the latter. That question rested on a false premise: auto-retry is **already built and shipped** — `GdprErasureRetryScheduler.retryFailedErasures()` (`@Scheduled(cron = "0 0 6 * * *")`) calls `GdprErasureService.retryFailedErasures()` (`:571`), delivered by `skillars-deferred-136` AC2, and the pre-prune ledger bullet this story's own ledger-hygiene pass pruned carried exactly that `[CLOSED by skillars-deferred-136 AC2 ...]` annotation. There was nothing open to decide. Struck from this story entirely — not re-added as "not picked up," since that would still misrepresent it as live open work.
- **Real-time `customer.subscription.created` webhook handling.** Owner decision: the existing daily `SubscriptionService.reconcileStripeSubscriptions()` sweep already closes this gap within 24h; that latency is accepted, not worth a new webhook handler.
- **Defensive `DataAccessException` handling around `markUsedByTokenHash`/`markAllUsedByUserId`.** Owner decision: no incident has ever been observed; the ledger's own stated bar (adding bespoke handling is only worth it if this class of DB failure is actually observed in production for an auth-adjacent write) has not been met.
- **Removing the handful of zero-usage orphaned endpoints/wrapper functions found by the 2026-10-14 `bmad-quick-dev` coach UI-gap scan** (`POST/GET /api/disputes`, `/reviews/{id}/flag`, messaging delete/report endpoints, `/slu/skill-trends`, `/session/sessions/{sessionId}`). Owner decision: these look like intentionally-built-ahead, not-yet-wired features, not accidental cruft; leaving them alone is the safer call.
- **Native-speaker review of the `fr-FR`/`de-DE` `marketplace.*` error strings, and the `skillars-deferred-140` AC4.3 manual browser walkthrough.** Both are pure human/QA/translator tasks with no code change attached — out of scope for a dev story; flagged here so they are not lost, not because they were evaluated and declined.

---

## Acceptance Criteria

1. **`listReports` scopes a coach caller to their own authored reports only.** `ReportGenerationService.listReports(Long playerId)` (`:253-274`) calls `coachPlayerAuthorizationService.requireCoachPlayerRelationshipIfCoach(playerId)` (gating on `CoachPlayerAuthorizationService.READ_RELATIONSHIP_STATUSES`, a 12-value list added by `skillars-deferred-153` AC5 that includes `DISPUTED`, `REFUND_PENDING`, `NO_SHOW_PLAYER`, `NO_SHOW_COACH`, `PAYMENT_PENDING`) and then queries `reportRepository.findByPlayerIdAndStatusOrderByGeneratedAtDesc(playerId, READY)` — player-keyed only, never filtered by the requesting coach's own id. A coach whose only relationship with a player is e.g. `DISPUTED` can now pass the relationship gate and read every `READY` report PDF ever generated for that child by *any* coach, not just their own. `PerformanceReportResource.listReports` (`:39-44`) is reachable by `@PreAuthorize("hasRole('ROLE_COACH') or @playerOwnershipGuard.check(authentication, #playerId)")` — **this AC must only narrow the `ROLE_COACH` branch; a parent/player caller (the ownership-guard branch) must keep seeing every coach's reports for their own child, unfiltered.**

   Inject `SecurityUtil` **and** `PlayerProfileRepository` as new constructor dependencies on `ReportGenerationService` (`@RequiredArgsConstructor` makes this additive — update **both** existing `ReportGenerationServiceTest` construction sites, `:89` and `:485`). In `listReports`, after the existing authorization call, mirror `CoachPlayerAuthorizationService.requireCoachPlayerRelationshipIfCoach`'s own condition shape **exactly**, including its self-owner escape arm — not a bare role check:
   ```java
   Long currentUserId = securityUtil.getCurrentCoachUserId();
   boolean isCoachOwner = playerProfileRepository.existsByIdAndParentId(playerId, currentUserId)
           || playerProfileRepository.existsByIdAndUserId(playerId, currentUserId);
   if (securityUtil.isCurrentUserInRole(AuthoritiesConstants.COACH) && !isCoachOwner) {
       UUID currentCoachId = coachProfileService.getCoachIdByUserId(currentUserId);
       reports = reports.stream().filter(r -> r.getCoachId().equals(currentCoachId)).toList();
   }
   ```
   **Why the escape arm matters even though it is unreachable today:** a bare `isCurrentUserInRole(COACH)` check would filter a caller who holds `ROLE_COACH` *and* is also this player's own parent/self-owner — a case no production path can currently create (confirmed: registration grants exactly one authority, nothing mutates authorities later, no `RoleHierarchy` bean exists), but one `requireCoachPlayerRelationshipIfCoach`'s own escape arm already defends against, specifically because `skillars-deferred-153` AC6 centralized that defense after finding it missing in five other copies. Omitting it here would silently reopen the same gap AC6 just closed, in a sixth copy. `getCoachIdByUserId` resolves to `UUID`, throws `ResourceNotFoundException` (→ 404) if the authenticated coach somehow has no profile row — the correct same-class precedent for this is `ReportGenerationService.generateReport:136`, which already calls it identically, right after its own `requireCoachPlayerRelationship` check. Filter the `reports` list to `r.getCoachId().equals(currentCoachId)` **before** building `coachNames`/signed URLs — do not sign a download URL for a report that will be filtered out. A parent/player caller (and the unreachable coach-owner edge case) skips the filter entirely and sees every coach's `READY` reports unchanged.

2. **GDPR erasure anonymises the coach's own authored session-recap free text, not just the player's.** `GdprErasureService.eraseTransactional`'s coach branch (`:285-290`) anonymises only `coach_profiles.{bio,city,district}` when the erased user is a coach. `booking.session_completion_data.{voice_note_text, what_we_did, whats_next}` are coach-authored free text — the columns themselves were added by `skillars-12-2` (migration `V159`), and their "coach-authored, not player-authored" characterization comes from `SessionCompletionDataRepository.java:36-40`'s own Java comment, added by `skillars-deferred-153` AC2 (there is no SQL `COMMENT ON COLUMN` on this table) — a coach writes them about a session, not the player. Yet the only existing anonymisation path, `SessionCompletionDataRepository.anonymizeByPlayerId` (`:36-43`), is keyed on `playerId` and runs only from the PLAYER-side erasure branch (`deletePlayerDevelopmentDataInDedicatedPool`, called for a PARENT's children or a self-registered PLAYER). A coach's own erasure never touches this table at all, so their authored recap text survives their own erasure indefinitely and is never captured by a coach's own DSAR export.

   Add `SessionCompletionDataRepository.anonymizeByCoachId(UUID coachId)` — identical shape to `anonymizeByPlayerId`, keyed on `s.coachId` instead of `s.playerId`:
   ```java
   @Modifying
   @Query("UPDATE SessionCompletionData s SET s.voiceNoteText = NULL, s.whatWeDid = NULL, s.whatsNext = NULL WHERE s.coachId = :coachId")
   void anonymizeByCoachId(@Param("coachId") UUID coachId);
   ```
   Call it inside `eraseTransactional`'s existing coach-branch `ifPresent` lambda (`:285-290`), using the already-resolved `cp.getId()` (the `CoachProfile` id, the same UUID stored in `SessionCompletionData.coachId`), **and set a per-statement `lock_timeout` immediately before it** — mirroring `anonymizeByPlayerId`'s own call site exactly (`:1198-1199`, `setStatementLockTimeout(lockTimeoutSeconds)` before the call), not the weaker "no bound needed" framing an earlier draft of this AC used: `booking.session_completion_data` carries exactly one index, `idx_scd_booking_id` — nothing on `coach_id` — so this UPDATE is a sequential scan, and `eraseTransactional`'s own `@Transactional(REQUIRES_NEW)` boundary sets no `lock_timeout` of its own (Postgres defaults to waiting forever on a contended row).
   ```java
   long coachRecapLockTimeoutSeconds = configService.getBoundedLong(
       ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.key(), 5L, 2L, 120L);
   coachProfileRepository.findByUserId(userId).ifPresent(cp -> {
       cp.setBio(null);
       cp.setCity(null);
       cp.setDistrict(null);
       coachProfileRepository.save(cp);
       setStatementLockTimeout(coachRecapLockTimeoutSeconds);
       sessionCompletionDataRepository.anonymizeByCoachId(cp.getId());
   });
   ```
   This single per-statement bound is all that's needed — this call site does **not** need the cumulative-lock-budget machinery `deletePlayerDevelopmentData` uses (there is no per-child loop here, just one statement), only the one-line timeout every other statement in this class already sets.

   **Explicitly out of scope, found during an independent review of this story and confirmed with the owner (not oversights):** `TimelineEventListener` copies `whatWeDid`/`whatsNext` verbatim into `development.player_timeline_events.metadata` at write time, and this AC does not purge that copy — it stays inaccessible (`TimelineQueryService.redactRecapIfNotAuthoringCoach` hides it from every viewer except the authoring coach, whose account this very erasure just locked) but not deleted. Likewise, `PerformanceReport.nextSteps` (coach-authored, `updatable = false`) and `CoachProfile.displayName` (not free text, but still an erased coach's real name, rendered on every report `listReports` returns) share AC2's exact "keyed on playerId only, coach erasure never reaches it" root cause and are not touched here either. All three are real, left for a future pass.

3. **Constrain every `:playerId` route parameter in `routes.js` to digits only.** `routes.js` has seven `:playerId` route params — `parent/players/:playerId/packs` (`:130`), `parent/players/:playerId/sessions` (`:135`), `parent/players/:playerId/development` (`:140`), `coach/players/:playerId` (`:169`), `parent/player/:playerId/subscription` (`:236`), `player/locker-room/:playerId` (`:278`), `player/development/:playerId` (`:284`) — none constrained, so a typo'd or malformed sub-path (e.g. `/coach/players/abc`) resolves to the detail page and issues API calls with a garbage id rather than falling through to a 404 at the router. `playerId` is always a `PlayerProfile` primary key — a client-side-generated `@Tsid` `Long` (`BaseEntity.java:27-29`, an 18–19-digit time-sorted id, **not** a sequential/auto-increment integer — worth being precise about, since `CoachPlayerDetailPage.vue:89-91`'s own `Number(route.params.playerId)` conversion has already been a twice-fixed production bug, `skillars-deferred-147`/`-148`, from treating a TSID as small enough for JS `Number` precision; describing it as "plain" risks a future reader repeating that mistake) — in every one of these seven routes. It is still always digits-only, so a `(\d+)` constraint is safe and uniform across all seven; this is a repo-wide pass closing the gap completely rather than fixing the one route the originating review happened to flag, which the ledger's own text explicitly suggested ("worth folding into any future pass that adds param constraints across the router"). Change each of the seven route paths from `:playerId` to `:playerId(\\d+)` (Vue Router 4's inline regex-constraint syntax). **Do not** extend this to any other id-shaped param in the file (`bookingId`, `coachId`, etc.) — those are UUIDs, not digit strings, and constraining them correctly is a separate, differently-shaped change outside what this AC's own citation covers.

4. **Push the real ownership predicate into `findQualifyingCompletedBookings`, fixing one live correctness bug and closing one currently-unreachable gap defensively, in a single query change.** `BookingRepository.findQualifyingCompletedBookings` (`:145-156`) filters `WHERE b.coachId = :coachId AND (b.parentId = :authorId OR b.playerId = :authorId) AND b.status = 'COMPLETED' AND ...`, capped at a literal `limit 50`, consumed by `ReviewSubmissionService.evaluateEligibility` (`:307-337`) to find any one child the calling author (a parent, or a self-booking player) has a completed, matured session with for a given coach. Two distinct findings, confirmed by re-tracing the full call path (not taken on the ledger's "join `player_profiles`" suggestion alone):

   - **The `OR b.playerId = :authorId` branch is dead/wrong.** `authorId` is always a `User` id (`ReviewResource.checkEligibility`/`submitReview` resolve it via `resolveUserId()`, `:83,99`), while `Booking.playerId` is a `PlayerProfile` primary key — a different id space entirely. It can only ever "match" by numeric coincidence between two unrelated sequences, never by a real relationship.
   - **`booking.bookings` has no FK on `player_id`, so an orphaned row could in principle occupy a cap slot — currently unreachable, but worth closing as defense-in-depth.** `BookingService.createBookingRequest` (`:179-190,330`) sets `booking.parentId` to the caller's own user id unconditionally, for both a parent-created and a self-booking-player-created booking — so `b.parentId = :authorId` *is* a correct filter on the **booking**, but it says nothing about whether the `PlayerProfile` that booking's `playerId` points to **still exists**. `ReviewSubmissionService.evaluateEligibility`'s own Java loop (`:321-325`) already defends against exactly this (`playerProfileRepository.findById(...).orElse(null)` → `continue` on a miss) — but **verified there is currently no path that could ever produce it**: `PlayerProfileRepository` declares no `delete*` method, nothing in `src/main` calls one, and there is no native `DELETE` against `main.player_profiles` anywhere; GDPR erasure only anonymises/tombstones a player's development data, it never deletes the `PlayerProfile` row itself (`PlayerProfile.java:55-70`'s own Javadoc: "a `player_profiles` row is never 'un-erased'"). So today this half of the fix changes no production behaviour — it closes a structural gap (no FK) defensively, for a scenario that cannot currently occur, not a live cap-slot-waste bug.

   Fix both by joining to `PlayerProfile` directly and checking its **current** ownership fields — the same ad-hoc JPQL `JOIN ... ON` shape this codebase already uses (`CoachProfileRepository.java:72`, `LEFT JOIN CoachPricing pr ON pr.coachId = p.id`):
   ```java
   @Query("""
       SELECT DISTINCT b.playerId AS playerId
       FROM Booking b
       JOIN PlayerProfile pp ON pp.id = b.playerId
       WHERE b.coachId = :coachId
         AND (pp.userId = :authorId OR pp.parentId = :authorId)
         AND b.status = 'COMPLETED'
         AND b.updatedAt <= :maturedBefore
         AND b.updatedAt > :sinceAfter
       ORDER BY b.playerId
       limit 50
       """)
   ```
   The plain (inner) `JOIN` would also exclude a booking whose `PlayerProfile` no longer exists, if that ever became possible — defense-in-depth, not a fix to a reachable bug today (see above) — and `pp.userId`/`pp.parentId` is the exact same pair `evaluateEligibility`'s own Java loop already checks (`:333`), now enforced before truncation instead of after. Keep the method's signature, `ORDER BY b.playerId`, and `limit 50` unchanged — this is a `WHERE`-clause correction, not a shape change. `ReviewSubmissionService.evaluateEligibility`'s `if (player == null) continue;` branch (`:323-325`) becomes unreachable through this query going forward — leave it in place; it is cheap, and nothing about this fix guarantees no *other* future caller of this repository method could reintroduce orphaned rows.

   **Two comment blocks need updating, not one** — the repository method's own comment (`BookingRepository.java:128-144`, outside this method's previously-cited `:145-156` range) *and* `ReviewSubmissionService`'s separate cap-warning log and its 8-line rationale comment (`:298-305`, `:314-318`). After this fix, every row in the capped result is correctly-owned by construction, so the warning's current wording — *"the owned playerId may have been truncated if it sorts above the cap"* — becomes false; the one remaining accepted residual is a single author genuinely exceeding 50 distinct, currently-owned children with the same coach. Update both comments to say so; the `QUALIFYING_BOOKINGS_CAP = 50` literal at `ReviewSubmissionService.java:305` stays a hand-duplicated second copy of the repository's `limit 50` (unchanged by this AC, already documented as such).

5. **Show the `/dashboard` fallback nav link only to a role with no dedicated landing nav of its own — not to every authenticated role.** `roleRoutes.js`'s `DEFAULT_ROUTE` (`'/dashboard'`, `:21`) is already the correct, automatic fallback for any role with no dedicated landing route — no change needed there. `MainLayout.vue`'s drawer (`v-if="authStore.isAuthenticated"`, so already gated to any logged-in user) has a nav item for `/dashboard` at `:126`, itself gated `v-if="authStore.isAdmin"`.

   **This gate is a deliberate prior decision, not an oversight — confirmed via `skillars-deferred-141` AC1, titled "Gate the generic 'Dashboard' nav item to Admin only," and `MainLayoutSpec.js:353-396`'s own dedicated test block, which pins exactly this: `/dashboard` visible for ADMIN, hidden for COACH/PARENT/PLAYER, each of whom instead sees their own dedicated link** (`/coach/command-center`, `/parent/dashboard`, `/player/dashboard` — all three already rendered, each with the identical `dashboard` icon and the identical `$t('nav.dashboard')` label `/dashboard`'s own item uses). A blanket "show it to everyone" change — this AC's own earlier draft — would therefore (a) turn three of `MainLayoutSpec.js`'s existing assertions red, since all three currently assert the link is **absent** for COACH/PARENT/PLAYER, and (b) put a second, visually-identical "Dashboard" entry in the drawer for PARENT and PLAYER, right next to their own already-correct one, pointing at a different, generic placeholder page — a real UX regression for exactly the two roles already fully served.

   The narrower, actually-open gap — unchanged since `deferred-141`'s own conclusion of "no action needed while the enum holds at four" — is that `SkillarsRole` now holds a **fifth** value, `ANONYMOUS` (`skillars-deferred-142` AC2, for a JWT whose `ROLES` claim maps to no business role; `JwtManagerImpl:136-152` does emit it), which has no `ROLE_ROUTES` entry, no dedicated nav section, and — because the `:126` item is gated to `isAdmin` alone — no way back to `/dashboard` from the drawer at all. Fix only that: widen the `:126` item's condition to also cover "no role-specific section rendered," rather than "every role":
   ```vue
   <q-item v-if="authStore.isAdmin || (!authStore.isCoach && !authStore.isParent && !authStore.isPlayer)"
           clickable to="/dashboard" class="nav-item">
   ```
   This leaves all four of `MainLayoutSpec.js`'s existing assertions unchanged (ADMIN still `true`; COACH/PARENT/PLAYER still `false`, since each still fails its own `isCoach`/`isParent`/`isPlayer` check) and introduces no duplicate label for any of the three already-served roles — the generic link becomes visible only for `ANONYMOUS` today, and automatically for any future role this project adds without first giving it a dedicated nav section.

   `DashboardPage.vue` itself (`meta: { requiresAuth: true }`, no role restriction) has no admin-specific content and makes no call that would fail for a non-admin — it is **not**, however, fully role-agnostic as an earlier draft of this AC claimed: it renders `<TimezoneNotice>` unconditionally and calls `bookingStore.loadParentBookings()` when `authStore.isParent` (both harmless no-ops for `ANONYMOUS`/admin). This matters only as a correction to this AC's own description, not to the fix.

6. **Testing & regression.**

   - **AC1 —** `ReportGenerationServiceTest`: both constructor sites (`:89`, `:485`) updated for the two new dependencies (`SecurityUtil`, `PlayerProfileRepository`). New cases: a coach caller sees only their own reports; a parent/player caller is unaffected (sees every coach's reports); the unreachable coach-owner-escape-arm case does not throw/filter (document as a defensive case, not a reachable one).
   - **AC2 —** `GdprErasureIT`: the fixture currently seeds exactly **one** `marketplace.coach_profiles` row (`coachProfileId`, `:146-147`) — add a **second** coach profile fixture so the "unaffected for an unrelated coach's sessions" case has a real unrelated coach to assert against, not just a different `coachId` value with no backing row. New case asserting `session_completion_data.{voice_note_text,what_we_did,whats_next}` are null after a *coach's own* erasure for sessions they authored, and unchanged for the second, unrelated coach's sessions.
   - **AC4 —** `BookingRepositoryIT.findQualifyingCompletedBookings_ordersAndCapsDeterministically` (`:211-301`) must be **substantially rewritten, not patched** — it currently seeds zero `main.player_profiles` rows and hardcodes `booking.player_id` to small sequential integers (`1..55`), which the new inner `JOIN PlayerProfile pp ON pp.id = b.playerId` would exclude entirely (`PlayerProfile.id` is a TSID, `@Tsid`-generated — not client-settable to an arbitrary small value). The rewritten fixture must: persist real `PlayerProfile` rows (letting the TSID generator assign real ids) owned by `authorId` (via `parentId` or `userId`), use their **actual generated ids** as each booking's `playerId`, and assert against those captured ids (sorted ascending) rather than the literal `1..50`. Required new case: a booking whose `playerId` numerically coincides with `authorId` (constructed via a raw `jdbcTemplate` insert directly into `booking.bookings`, bypassing the entity — this project's existing not-null-but-no-FK convention makes that legitimate) with a **non-matching** `parentId`, proving it is excluded now that the dead `OR b.playerId = :authorId` branch is gone. **Optional, not required:** a hand-planted orphaned-`playerId` case (insert a `PlayerProfile`, capture its id, create its booking, then raw-`DELETE` the `PlayerProfile` row) — include only if cheap; the scenario it tests is unreachable via any real application code path (see AC4's own note), so this is pure defense-in-depth verification, not a regression guard for a live bug.
   - **AC4 (comment-only) —** no new test needed for the two comment-block updates (`BookingRepository.java:128-144`, `ReviewSubmissionService.java:298-305,314-318`) — verify by reading, not by test.
   - **AC5 —** `MainLayoutSpec.js:353-396`'s four existing assertions (ADMIN sees `/dashboard`; COACH/PARENT/PLAYER do not) must **still pass unchanged** — they do, under the corrected `v-if` (see AC5). Add **one new case**: mount with an unmapped role (e.g. `{ role: 'ANONYMOUS', userId: <n> }`, matching the existing per-role mount pattern at `:359-396`) and assert `wrapper.find('[href="/dashboard"]').exists()` is `true` — this is the only behavior AC5 actually changes, and it has no existing coverage.
   - AC3 (pure router config) has no backend/frontend test to update — `routerGuardSpec.js`'s existing numeric fixtures (`:136,144,152,164,172`) already pass `\d+` unchanged. A manual check (malformed `/coach/players/abc` 404s instead of rendering) is sufficient; do not add a new test file.
   - No `mvn verify` run locally, per this project's standing CI-is-the-gate convention.
   - After implementation, delete the five resolved bullets listed in the Source table above from `deferred-work.md` in the same commit.

---

## Tasks / Subtasks

- [x] Task 1: Scope `listReports` to the requesting coach's own reports (AC: 1)
  - [x] Inject `SecurityUtil` and `PlayerProfileRepository` into `ReportGenerationService`; update both `ReportGenerationServiceTest` construction sites
  - [x] Add the mirrored-escape-arm filter (`isCurrentUserInRole(COACH) && !isCoachOwner`) resolving `currentCoachId` via `coachProfileService.getCoachIdByUserId(...)` and filtering `reports` before building the response
  - [x] New `ReportGenerationServiceTest` cases: coach sees only own reports; parent/player caller unaffected; coach-owner escape arm does not filter

- [x] Task 2: Anonymise a coach's own session-recap free text on their own GDPR erasure (AC: 2)
  - [x] Add `SessionCompletionDataRepository.anonymizeByCoachId(UUID coachId)`
  - [x] Call it from `eraseTransactional`'s coach-branch `ifPresent` lambda, using `cp.getId()`, with its own `setStatementLockTimeout(...)` call immediately before it (mirroring `anonymizeByPlayerId`'s call site)
  - [x] Add a second coach-profile fixture to `GdprErasureIT`; new case: coach's own erasure nulls their authored recap text, unrelated coach's rows untouched

- [x] Task 3: Constrain all seven `:playerId` route params to digits (AC: 3)
  - [x] Change `:playerId` → `:playerId(\\d+)` at `routes.js:130,135,140,169,236,278,284`
  - [x] Manual check: malformed sub-path under any of the seven falls through to a 404

- [x] Task 4: Push the ownership predicate into `findQualifyingCompletedBookings` via a `PlayerProfile` join (AC: 4)
  - [x] Replace the `(b.parentId = :authorId OR b.playerId = :authorId)` filter with a `JOIN PlayerProfile pp ON pp.id = b.playerId` + `(pp.userId = :authorId OR pp.parentId = :authorId)` check
  - [x] Update **both** comment blocks — `BookingRepository.java:128-144` and `ReviewSubmissionService.java:298-305,314-318` — to reflect the corrected residual-risk framing
  - [x] Rewrite `BookingRepositoryIT.findQualifyingCompletedBookings_ordersAndCapsDeterministically` to seed real `PlayerProfile` rows (TSID-generated, not hardcoded `1..55`) owned by `authorId`, and assert against their captured ids; add the raw-SQL numeric-coincidence exclusion case; optionally add the hand-planted orphaned-row case (defense-in-depth only)

- [x] Task 5: Show the `/dashboard` nav link only to a role with no dedicated landing nav (AC: 5)
  - [x] Change `MainLayout.vue:126`'s `v-if` from `authStore.isAdmin` to `authStore.isAdmin || (!authStore.isCoach && !authStore.isParent && !authStore.isPlayer)`
  - [x] Confirm `MainLayoutSpec.js`'s four existing ADMIN/COACH/PARENT/PLAYER assertions still pass unchanged; add the new unmapped-role (`ANONYMOUS`) case

- [x] Task 6: Testing & ledger cleanup (AC: 6)
  - [x] All targeted backend/frontend tests above pass
  - [x] Delete the five resolved `deferred-work.md` bullets listed in the Source table

---


### Review Findings

_Code review 2026-10-17 (`/bmad-code-review`), three parallel layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor). Every finding below was independently re-verified against real HEAD before being recorded — that re-verification refuted 13 of the 25 raised findings, including four the Blind Hunter rated HIGH (see the dismissal notes inline). 2 blocking, both found only by layers with real source access and each corroborated independently by a second layer._

_**Verified by the reviewer, not taken on the story's word:** `mvn -o test-compile` BUILD SUCCESS (exit 0, zero javac errors); `ReportGenerationServiceTest` 25/25, `GdprErasureServiceTest` 14/14, `ReviewSubmissionServiceTest` 3/3; `ReportGenerationServiceIT` 2/2, `GdprErasureIT` 36/36, `BookingRepositoryIT` 11/11 against real Postgres via Testcontainers; full frontend Vitest 50 files/378 tests; Prettier clean on all three touched frontend files. Finding 1 was additionally mutation-verified: with the pre-fix ordering recompiled, `ReportGenerationServiceIT` fails `IllegalStateException: User not found!` at `:93`. No `mvn verify` was run, per this project's standing CI-is-the-gate convention — CI remains the full-verification gate._

- [x] [Review][Decision → Deferred] RESOLVED 2026-10-17 by the owner: ledger it as a known residual — consistent with AC2's own recorded scoping decision, which already deferred three same-root-cause gaps; closing it properly needs a Flyway migration and a write-path guard, i.e. its own story. Original finding: **A wrap-up submitted after a coach's erasure survives it permanently — the coach side has no tombstone** — `BookingCompletionService.submitWrapUp` (`BookingCompletionService.java:129-174`) is a plain `@Transactional` that INSERTs a fresh `session_completion_data` row with `voiceNoteText`/`whatWeDid`/`whatsNext`, taking no lock on `coach_profiles` and consulting no erasure state. AC2's bulk UPDATE cannot see a row committed after it runs, and unlike the player side there is no `coach_profiles` equivalent of `player_profiles.development_data_erased_at` (`PlayerProfile.java:66-68`, `V151__player_profiles_development_data_erased_at.sql:31`) for anything to re-check. The erased coach's **access** token also stays valid — `refreshTokenRepository.markAllUsedByUserId` invalidates only refresh tokens — and `coachProfileRepository.findByUserId` keeps resolving because the erasure nulls only `bio`/`city`/`district` (`GdprErasureService.java:284-288`). So for the remaining JWT TTL the coach can submit a wrap-up whose free text then persists indefinitely: the exact failure mode AC2 exists to close. Options: (a) accept as a known residual and ledger it alongside the three siblings AC2 already deferred (timeline metadata copy, `PerformanceReport.nextSteps`, `CoachProfile.displayName`); (b) add a coach-side erased-at tombstone + migration and have `submitWrapUp` reject against it; (c) re-run `anonymizeByCoachId` from an `AFTER_COMMIT` sweep.

- [x] [Review][Patch] APPLIED 2026-10-17 — **BLOCKING: `getCurrentCoachUserId()` ran before the role check that gates it, breaking `ReportGenerationServiceIT`.** The role test now comes first and gates both the principal resolution and the two ownership exists-checks, matching `CoachPlayerAuthorizationService.requireCoachPlayerRelationshipIfCoach:107-111`'s own ordering — which is what "mirrors its condition shape exactly" has to mean. `SecurityUtil.getCurrentCoachUserId` has no null-returning branch (`SecurityUtil.java:293-303`); it throws, via `getCurrentUser()` → `IllegalStateException("User not found!")` (`:108-116`), and `ReportGenerationServiceIT:93` calls `listReports` on the bare test thread with no `SecurityContext` (verified: no `@WithMockUser`, no `SecurityContextHolder` setup, no `SecurityUtil` mock in that IT, `BaseStorageIT`, or `AbstractIntegrationTest`). **Mutation-verified, not asserted:** with the pre-fix ordering recompiled, `ReportGenerationServiceIT` fails `IllegalStateException: User not found!` at `:93`; restored, it is 2/2 green. Also removes up to two discarded DB round-trips per parent/player call, on top of the identical pair `PlayerOwnershipGuard.check` already runs in `@PreAuthorize`. [src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java:272]
- [x] [Review][Patch] APPLIED 2026-10-17 — **BLOCKING: the coach-branch `lock_timeout` was transaction-scoped, not per-statement, and leaked into every remaining write in `eraseTransactional`.** `setStatementLockTimeout` issues `set_config('lock_timeout', ?, true)` — `is_local = true` is transaction-local, as both that helper's own Javadoc (`:1257`, "transaction-scoped") and `deletePlayerDevelopmentData`'s (`:1077-1079`, "covers every statement through commit, this one included") state outright. Set inline in the outer `@Transactional(REQUIRES_NEW) eraseTransactional`, the 5s bound silently governed ~15 later statements — the message/alert/review deletes, `markAllUsedByUserId`, `deleteExpiredByUserId`, the terminal `gdprRequestRepository.save`, and the commit-time flush of the `main."user"` and `coach_profiles` UPDATEs — none of which previously carried any `lock_timeout` on a COACH erasure. Fixed by moving the UPDATE into its own `requiresNewTemplate` boundary, which is exactly what confines the identical setting on the PLAYER side. The AC's prescribed "per-statement" framing was simply wrong about Postgres semantics; the code comment asserting it has been corrected rather than preserved. [src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:302]
- [x] [Review][Patch] APPLIED 2026-10-17 — **`anonymizeByCoachId` had no `try`/`catch`, so a lock-timeout trip rolled back the whole erasure instead of the classified skip-and-alert the player path uses.** `session_completion_data` carries no index on `coach_id` (only `idx_scd_booking_id`, `V138__baseline_schema.sql:3280`), so the UPDATE is a sequential scan locking every matching row — contention is the stated reason it needs a bound at all. Uncaught, a 55P03 propagated out of `eraseTransactional` and rolled back the entire erasure (user + coach-profile anonymisation, message/review deletes, the `PROCESSING` write), landing in `GdprEventListener.onErasureRequested`'s generic catch, which only `markFailed` + `log.error`s: no reason code, no `AdminAlert`. Now mirrors the PLAYER branch's own skip-and-alert, with two new reason constants (`COACH_RECAP_LOCK_TIMEOUT` / `COACH_RECAP_CONTENDED`) classified by catch type so a genuine deadlock (`CannotAcquireLockException`) is not reported as a timeout; `raiseErasureAlert` is called outside the `requiresNewTemplate` boundary per its own Javadoc. [src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java:303]
- [x] [Review][Patch] APPLIED 2026-10-17 — **`BookingRepository`'s comment block was only half-updated: its first paragraph still asserted the superseded pre-AC4 mechanism as current fact.** `:131-133` claimed "the ownership check (ReviewSubmissionService's Java loop) runs strictly AFTER this query's own truncation, so ordering cannot protect any specific row" — false as of AC4, which moved the ownership predicate into this query's own WHERE clause, i.e. before truncation. The old premise is now quoted and explicitly retired in place, and the `ORDER BY` rationale narrowed to what it actually still buys (determinism, and only that). This is the exact block AC4 cited by line range and required to be corrected. [src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java:131]
- [x] [Review][Patch] APPLIED 2026-10-17 — **the rewritten cap warning claimed "more than 50" on a `>= 50` guard that can only ever fire at exactly 50.** `limit 50` means `qualifying.size()` can never exceed 50, so the branch fires at exactly 50 — in fact the only value it can ever observe — and reported it as "more than 50". Changed to "at least", in both the log line and the comment block above it. (The Blind Hunter's companion claim that this rewrite "removed the only warning" was dismissed: the warning still fires, with corrected wording.) [src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java:322]
- [x] [Review][Patch] APPLIED 2026-10-17 — **`BookingRepositoryIT`'s four negative fixtures had lost their discriminating power: their TSIDs sorted above the cap instead of below it.** The pre-rewrite fixture hardcoded playerIds `-4..-1` precisely so the negative rows sorted BELOW every matching row and would land inside the 50-row window if the query ever regressed to "order+cap first, filter after" — the whole bug those four rows exist to catch. The rewrite created their profiles *after* the 55-row loop, and `@Tsid` ids are time-sorted, making them the four highest of 59 and pushing them past the cap. Their creation is now hoisted above the loop, and a new assertion pins the ordering property outright (`allSatisfy(negativeId -> isLessThan(lowestMatchingPlayerId))`) so the fixtures can no longer be disarmed by a silent block reorder — the ordering was previously enforced by comment alone. That assertion passing also empirically confirms both the TSID-monotonicity premise and that the finding was real. [src/test/java/com/softropic/skillars/platform/booking/repo/BookingRepositoryIT.java:262]
- [x] [Review][Patch] APPLIED 2026-10-17 — **`MainLayoutSpec.js`'s mutation note described a `v-if` that no longer exists.** The note quoted the condition as `v-if="authStore.isAdmin"`, which AC5 widened. Reworded to name the mutant that does still exist (deleting the condition entirely, which still turns the three negative assertions red) and to record why the quoted text went stale. Separately verified and dismissed the Blind Hunter's claim that the four existing assertions do not pin `/dashboard` absence for COACH/PARENT/PLAYER — they do, explicitly, at `:371`, `:383` and `:393`. [src/frontend/src/layouts/__tests__/MainLayoutSpec.js:363]
- [x] [Review][Patch] APPLIED 2026-10-17 — **three story-metadata inaccuracies corrected.** (a) File List now includes `_bmad-output/implementation-artifacts/story-review.md`, this story's own review artifact, rewritten in the same change (+235/-284) but omitted. (b) The Completion Notes' removed-heading count corrected from two to **three** — the duplicate `skillars-uat-3-payment-capture-integrity-and-backup-retention` heading was disclosed in the Dev Notes "Ledger hygiene" paragraph but missing from that tally. (c) The Dev Notes' "source of truth" ledger-diff figure corrected from 17/261 to 17/**282**; the `[CLOSED by` 31→21 count was re-verified and was accurate. All five Source-table bullets were confirmed deleted and every other deleted bullet carries a terminal `[CLOSED by …]` annotation, so nothing still-open was silently dropped. [_bmad-output/implementation-artifacts/skillars-deferred-154-report-access-scoping-coach-erasure-recap-cleanup-roster-review-query-fix-and-nav-fallback.md:253]

- [x] [Review][Patch] APPLIED 2026-10-18 — **AC5's `MainLayout.vue` change failed the CI Prettier gate, which blocks `build-and-push`.** Found while verifying the patches above, not by any review layer: `npx prettier --check src/layouts/MainLayout.vue` reported "Code style issues found" on the story's own AC5 edit, whose hand-wrapped multi-line `q-item` attributes do not match Prettier's Vue template formatting. This project's Prettier gate lives in `.github/actions/` and a failure blocks the `build-and-push` job, so this would have failed CI regardless of every test passing. Fixed with `prettier --write` (Prettier reflowed the attributes onto their own lines; the `v-if` expression itself is byte-identical). Re-verified: all three touched frontend files now pass `--check`, and `MainLayoutSpec.js` 21/21 plus the full 50-file/378-test Vitest suite stay green. [src/frontend/src/layouts/MainLayout.vue:126]

- [x] [Review][Defer] **The seven new `(\d+)` route constraints have zero test coverage, and seven existing page specs build test routers with the params still unconstrained** [src/frontend/src/router/routes.js:130] — deferred, pre-existing. AC6 explicitly waived a new test for AC3 ("A manual check ... is sufficient; do not add a new test file"), so this is a resolved scoping decision for this story, not a missed requirement. The residual: `MainLayoutSpec.js:87-88`, `MyPlayersPageSpec.js:36`, `CoachPlayerDetailPageSpec.js:44`, `ParentDevelopmentPortalPageSpec.js:39,71`, `PlayerSubscriptionPageSpec.js:41`, `ParentPlayerPortalPageSpec.js:39,43` and `PlayerDevelopmentDashboardPageSpec.js:34` all declare their own unconstrained `:playerId` routes, so a future regression emitting a non-digit segment stays green in every spec and 404s in production. (`routerGuardSpec.js` is the exception — it imports the real router, so it does exercise the constraint.)
- [x] [Review][Defer] **`PlayerProfileBuilderPage` now 404s instead of rendering the locker room if `profile.id` is ever absent** [src/frontend/src/pages/auth/PlayerProfileBuilderPage.vue:66] — deferred, pre-existing. The only one of the four path-template call sites with no truthiness guard on the id; `MainLayout.vue:357,360` guard with `selfPlayerId.value ? … : null` and `PlayerHomeRedirectPage.vue:39-42` has an explicit `if (id == null)` added by a prior review for exactly this reason. Low likelihood: `boot/axios.js:141-142` unwraps `response.data` and `CommonConfig.java:48` serialises `Long` via `ToStringSerializer`, so a 200 always yields a digit string.
- [x] [Review][Defer] **The widened nav condition briefly shows the generic "Dashboard" link to COACH/PARENT/PLAYER during `logout()`** [src/frontend/src/layouts/MainLayout.vue:126] — deferred, pre-existing shape. `authStore.logout()` calls `clearUser()` synchronously before awaiting the network call (`auth.store.js:41-53`), setting `role.value = null`, so for that await window all four role computeds are false and the negative-space condition is true. Not reachable during initial load: `router/index.js:40-45` hydrates synchronously inside `beforeEach` before the first navigation resolves, and `auth.store.js:77-80` sets `userId` and `role` together. An allow-list (`isAdmin || isAnonymousRole`) would express AC5's intent without the negative-space side effect, and would also stop silently opting in any future `SkillarsRole` value.

## Dev Notes

### Owner decisions (resolved via `AskUserQuestion`, this story's own creation session, 2026-10-17)

1. **Report-PDF cross-coach exposure (AC1):** scope `listReports` to the requesting coach's own authored reports, rather than narrowing the relationship-status gate back down (which would reintroduce the roster/report-panel inconsistency `skillars-deferred-153` AC5 was fixing) or leaving it as-is.
2. **`/dashboard` nav gap (AC5), revised after an independent story review:** the original decision — "show a generic nav entry to every authenticated role" — turned out to rest on an incomplete picture of the surrounding code and was narrowed before implementation, not reversed by a second owner round: `skillars-deferred-141` AC1 had already deliberately gated `/dashboard` to admin-only (with its own pinned `MainLayoutSpec.js` test), and COACH/PARENT/PLAYER each already have their own dedicated, identically-labelled landing-nav entry. A blanket unhide would have broken that test and put a duplicate "Dashboard" item in the drawer for two of the three roles already fully served. The fix actually needed — and implemented — is narrower and strictly dominates the original choice on every axis the owner cared about: the generic link becomes visible exactly when no role-specific nav section renders (today, only `ANONYMOUS`), leaving every already-served role untouched. See AC5 for the corrected condition and rationale.
3. **`customer.subscription.created` real-time webhook handling:** the existing daily reconciliation sweep's latency is accepted; not picked up.
4. **`findQualifyingCompletedBookings`'s cap-vs-correctness concern (AC4):** fix proactively now rather than continuing to wait for the truncation-warning log to actually fire in production. Investigation during drafting confirmed the ledger's own suggested fix shape (join `player_profiles`) and additionally found the existing `OR b.playerId = :authorId` clause is independently dead/wrong (compares two unrelated id spaces). A later independent review further found the orphaned-row half of this fix is currently unreachable (nothing in this codebase can delete a `PlayerProfile` row) — kept as defense-in-depth, reframed in AC4 as such rather than as a live risk.
5. **`markUsedByTokenHash`/`markAllUsedByUserId` `DataAccessException` guard:** not picked up — no observed incident, matching the ledger's own stated bar.
6. **Zero-usage orphaned endpoints found by the 2026-10-14 scan:** left alone — read as intentionally-built-ahead features, not cruft.
7. **GDPR `markFailed` auto-retry was never actually a live decision** — see "Explicitly NOT picked up" above. An earlier draft asked the owner to choose between auto-retry and the existing manual-reconcile design; an independent story review found auto-retry was already shipped (`skillars-deferred-136` AC2), so the question itself was based on a false premise. Corrected, not re-asked.
8. **Recap-text copy in `player_timeline_events.metadata` (found by the same review, AC2):** accept current scope — the source table is anonymised, the inaccessible timeline copy is left for a future pass.
9. **`PerformanceReport.nextSteps` and `CoachProfile.displayName` siblings (same review, AC2):** accept current scope — both left for a future pass; `nextSteps` would need a native-query workaround for its `updatable = false` flag, and `displayName` is a separate product question (should an erased coach's historical reports display an anonymized name?), not a mechanical fix.

### Ledger hygiene performed during this story's own creation (not part of any AC above)

Before drafting, a sweep of `deferred-work.md` found and pruned roughly 10–12 items that were already fully resolved: ten `[CLOSED by ...]`-tagged tombstones (carried across 6–8 distinct bullets, some nested with more than one sequential closure annotation — the exact bullet count depends on how a multi-closure bullet is counted) spanning sections dated 2026-08-03 through 2026-09-23, plus four orphaned HTML-comment closure notes left behind after their own bullet was already deleted in an earlier pass (all traced to the same `skillars-deferred-100` AC7 ledger-hygiene pass). One pair of duplicate, identically-titled section headings (both "code review of skillars-uat-3-payment-capture-integrity-and-backup-retention," one of which held only a single, now-fully-closed bullet) was merged by deleting the now-empty duplicate heading along with its bullet — a structural simplification, not itself a bullet prune. Three additional stale "remains open" claims in later stories' own closeout summaries (naming `M5-2`'s NOWAIT-conversion trigger and the `BoundedKey` missing-`default` residual as still open, when both were in fact closed by subsequent stories) were corrected with `[CLEANUP 2026-10-17: ...]` annotations, following this file's own established convention for that situation (see the existing `[CLEANUP 2026-09-26: ...]` notes for precedent) rather than being silently rewritten. *(These counts were corrected after an independent story review found the first draft's "16 bullets... 12 `[CLOSED by`-tagged... floor 2026-08-11" figures overstated; the `git diff` on the pruned file is the source of truth: 17 insertions / **282** deletions, `[CLOSED by` tag count 31→21. Code review 2026-10-17 re-measured both: the tag count was accurate, the deletion figure was stale — "261" predated the removal of the five Source-table bullets themselves. Note that `git diff --numstat` on this file now reports a higher insertion count than 17, because this story's own code review subsequently appended a `Deferred from: code review of skillars-deferred-154` section to the same file.)*

### Why `requireCoachPlayerRelationshipIfCoach` itself is not touched by AC1

AC1's fix is a filter applied *after* that authorization check, not a change to the check itself. The authorization check answers "may this coach see *any* report for this player at all" (correctly widened by `skillars-deferred-153` AC5/AC6 to the 12-status `READ_RELATIONSHIP_STATUSES`, matching the roster's own membership width); AC1 answers the separate question "*which* of this player's reports may this specific coach see" (their own, not every coach's). Narrowing the first question's status list back down would undo AC5's own fix and reintroduce the inconsistency it was removing.

---

## Dev Agent Record

### Debug Log

- Found during Task 2: `GdprErasureIT`'s raw `INSERT INTO marketplace.coach_profiles` for the
  second ("unrelated") coach fixture failed `coach_profiles_user_id_fkey` — `coach_profiles.user_id`
  has an FK to `main.user`, so a real user row (`insertUser` + `grantAuthority`) had to be seeded
  underneath the profile, not just the profile row itself.
- Found during Task 4: `BookingRepositoryIT`'s rewritten fixture failed
  `player_profiles_parent_id_fkey` the first time — `player_profiles.parent_id` has an FK to
  `main.user` that the pre-existing `authorId = 1L` literal never satisfied (the old test never
  needed a real `PlayerProfile` row at all). Added a minimal raw `main.user` insert
  (`insertAuthorUser`) for `authorId` before seeding any `PlayerProfile` rows.
- All five touched test classes verified individually and then together post-implementation,
  per `docs/validation-strategy.md` (targeted tests only, no local `mvn verify`):
  `ReportGenerationServiceTest` (25 tests), `ReviewSubmissionServiceTest` (3 tests),
  `GdprErasureIT` (36 tests), `BookingRepositoryIT` (11 tests) — all green, zero regressions.
  `MainLayoutSpec.js` (21 tests) and the full frontend Vitest suite (50 files / 378 tests) also
  green; `routerGuardSpec.js` (14 tests) re-verified unaffected by the new `(\d+)` route
  constraints.

### Completion Notes

All 6 tasks / 5 ACs implemented:

- **AC1** — `ReportGenerationService.listReports` now filters a `ROLE_COACH` caller's results to
  their own authored reports only, mirroring `CoachPlayerAuthorizationService
  .requireCoachPlayerRelationshipIfCoach`'s own condition shape (including its unreachable-today
  self-owner escape arm). A parent/player caller is unaffected. New constructor dependencies
  (`SecurityUtil`, `PlayerProfileRepository`) — both `ReportGenerationServiceTest` construction
  sites updated. Three new test cases: coach-only filtering, parent/player unaffected, coach-owner
  escape arm not filtered.
- **AC2** — `SessionCompletionDataRepository.anonymizeByCoachId` added and wired into
  `GdprErasureService.eraseTransactional`'s coach branch, with its own per-statement
  `lock_timeout` set immediately before the call (mirroring `anonymizeByPlayerId`'s own call
  site). `GdprErasureIT` gained a second, real `coach_profiles` fixture (with a backing
  `main.user` row) and a new test proving the erased coach's own recap text is nulled while the
  unrelated coach's is untouched.
- **AC3** — All seven `:playerId` route params in `routes.js` constrained to `(\d+)`.
  `routerGuardSpec.js`'s existing numeric fixtures pass unchanged; no new test file added, per the
  story's own AC6 guidance (malformed-path 404 is a manual-check case).
- **AC4** — `BookingRepository.findQualifyingCompletedBookings` now inner-JOINs `PlayerProfile`
  and checks its current `userId`/`parentId` ownership, dropping the dead
  `OR b.playerId = :authorId` id-space-coincidence branch and closing the (currently unreachable)
  orphaned-row gap defensively. Both comment blocks (`BookingRepository` and
  `ReviewSubmissionService`) updated to reflect that every capped row is now correctly-owned by
  construction. `BookingRepositoryIT`'s fixture rewritten to seed real, TSID-generated
  `PlayerProfile` rows instead of hardcoded small integers, plus the required numeric-coincidence
  exclusion case (raw SQL, no backing profile). The optional hand-planted orphaned-row case was
  not added — the scenario is unreachable via any real code path today (see AC4's own note) and
  the required case already proves the fix.
- **AC5** — `MainLayout.vue`'s `/dashboard` nav item's `v-if` widened from `authStore.isAdmin`
  alone to also show for any role with no dedicated nav section of its own (today, only
  `ANONYMOUS`). All four existing ADMIN/COACH/PARENT/PLAYER `MainLayoutSpec.js` assertions pass
  unchanged; one new case added for the unmapped-role fallback.
- **AC6** — All targeted tests above pass. The five resolved `deferred-work.md` bullets listed in
  the Source table were deleted; **three** now-empty section headings
  (`skillars-uat-3-payment-capture-integrity-and-backup-retention`, `skillars-deferred-150`,
  `skillars-deferred-153`) were removed along with them, consistent with this file's own established
  "merge/remove an emptied heading" convention. (Code review 2026-10-17 corrected this count from
  "two": the third, the duplicate `skillars-uat-3-...` heading, *is* disclosed in the Dev Notes
  "Ledger hygiene" paragraph below, but was omitted from this sentence's tally.) The
  `skillars-deferred-141` section's unrelated `[CLOSED by skillars-deferred-142: ...]` tombstone
  was left in place — it documents two already-closed items unrelated to this story's AC5 fix.

No `mvn verify` was run locally, per `docs/validation-strategy.md`'s standing CI-is-the-gate
convention — only the classes above (plus a full frontend Vitest pass, since this story touches
both router config and a layout component) were run.

## File List

- `src/main/java/com/softropic/skillars/platform/development/service/ReportGenerationService.java`
- `src/test/java/com/softropic/skillars/platform/development/service/ReportGenerationServiceTest.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/SessionCompletionDataRepository.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`
- `src/main/java/com/softropic/skillars/platform/reviews/service/ReviewSubmissionService.java`
- `src/test/java/com/softropic/skillars/platform/booking/repo/BookingRepositoryIT.java`
- `src/frontend/src/router/routes.js`
- `src/frontend/src/layouts/MainLayout.vue`
- `src/frontend/src/layouts/__tests__/MainLayoutSpec.js`
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/story-review.md` _(added by code review 2026-10-17 — this
  story's own independent story-review artifact, rewritten in the same change (+235/-284) but omitted
  from this list)_
- `_bmad-output/implementation-artifacts/skillars-deferred-154-report-access-scoping-coach-erasure-recap-cleanup-roster-review-query-fix-and-nav-fallback.md`

## Change Log

- 2026-10-17: Implemented all 5 ACs (report-access scoping, coach-side GDPR recap anonymisation,
  router `:playerId` digit constraints, `findQualifyingCompletedBookings` ownership-predicate fix,
  `/dashboard` nav fallback for unmapped roles). All 6 tasks complete, targeted backend/frontend
  regression green, five resolved `deferred-work.md` bullets removed. Status: ready for dev →
  review.
