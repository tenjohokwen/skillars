# Story Review — skillars-deferred-153

**Story key:** `skillars-deferred-153-wrapup-abandonment-roster-fanout-relationship-consistency-and-recency-policy-fixes`
**Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-153-…-fixes.md` (286 lines, untracked/new)
**Status in `sprint-status.yaml` map:** `ready-for-dev`
**Audit date:** 2026-10-17
**Real HEAD at audit time:** `d9d9002b` — *Merge pull request #277 from tenjohokwen/story/skillars-deferred-152-store-error-i18n-sweep-contribution-threshold-dedup-and-coach-roster-fixes*
**Branch:** `master`. Working tree: `deferred-work.md` and `sprint-status.yaml` modified (uncommitted); the story file itself untracked.

**Every citation below was checked against this HEAD and this working tree — not against any SHA, line number, or "master is at…" claim written inside the story.** The story happens to name `d9d9002b` itself, and that is correct; it was nevertheless re-derived independently via `git rev-parse` before any verification work began. Ledger citations were checked against the **working-tree** (post-prune) `deferred-work.md`, which is what the story claims to target.

Four parallel verification layers ran (citations / ledger+precedent / mechanistic / corner-case), followed by a mandatory adversarial re-read in which every finding's evidence was opened fresh with the goal of refuting it. Four layer claims died in that pass and are listed in §6.

---

## 1. Citation verification

| # | Citation | Verdict | Evidence / corrected location |
|---|---|---|---|
| 1 | `QuickCompleteTimeoutService.java:49-72` | DRIFTED (minor) | Real span `:50-79`. `:62-63` transition w/ `ActorRole.SYSTEM`, `:65` publish. Note it **reads** existing rows (`findPendingQuickCompletes`, `:57`) — it never builds one. |
| 2 | `ConfigBounds.java:99-103` (`quick_complete_timeout_hours`) | DRIFTED | Real `:97-100`. Bounds `1L..168L`, `failFast=false`, `defaultValue = 0L` **sentinel**; key is **not** in `HAS_CODE_DEFAULT`. |
| 3 | `ConfigBounds.java:163-166` (`dormantAfterDays`) | DRIFTED | Real `:162-164`. Bounds `1L..3650L`, default `30L`, **is** in `HAS_CODE_DEFAULT` (`:401`). |
| 4 | `BookingService.java:104` (`EVENT_ROLES[COMPLETE]`) | MATCH | `:104 Map.entry(BookingEvent.COMPLETE, EnumSet.of(ActorRole.PARENT, ActorRole.SYSTEM)),` |
| 5 | `ActorRole.java` = `COACH, PARENT, SYSTEM` only | MATCH | 7-line file, three constants. |
| 6 | `DisputeService.java:384` (`logAdminAction`) | MATCH | `:384 private void logAdminAction(Long adminId, AdminActionType, String referenceId, String reason)`; callers `:281`, `:306`. |
| 7 | `V153__admin_alerts_subscription_orphaned_type.sql` | MATCH | `SET LOCAL lock_timeout = '5s'` (`:22`), `DROP CONSTRAINT IF EXISTS` (`:25`), `-- migration-lint: allow-validating-constraint` (`:26`), re-`ADD CONSTRAINT` (`:29`). All three stated reasons present verbatim. |
| 8 | `V159__…recap.sql` as source of all nullable recap fields | PARTIALLY DRIFTED | V159 adds only `what_we_did`/`whats_next` (`varchar(500)`, nullable, `:9-10`). **`voice_note_text varchar(2000)` is from `V138__baseline_schema.sql:276`.** Nullability conclusion still correct. |
| 9 | `CoachRosterService.getRoster:67-99` | DRIFTED | Real `:68-104`. Cited range cuts the dormant-config read (`:86-90`), the deferred-152 missing-profile filter (`:97-98`) and the `attentionScore` sort (`:100`) — all relevant to AC3. |
| 10a | `BookingRepository.java:224-240` (roster query) | DRIFTED | `@Query` `:228-236`, signature `:237-240`; file ends at 241. |
| 10b | `:226-230` reshuffle comment | DRIFTED | Real `:223-227`. |
| 10c | `:218-223` "no cap/limit" comment | MATCH | `:219-221` verbatim. |
| 11 | `PlayerTimelineRepository:14` no `Pageable` | MATCH | `:14 List<PlayerTimelineEvent> findByPlayerIdOrderByOccurredAtDesc(Long playerId);` |
| 12 | `PlayerTimelineResource.java:26-36` single `@PathVariable` | MATCH | `:29 getTimeline(@PathVariable Long playerId)`. |
| 13 | `AdminReviewResource.java:38-44` — "clamp `size` to `Math.min(Math.max(size,1),200)`" | **FALSE** | `:44` has only `Math.max(0, page)`. **No `size` param and no `200` ceiling exist** in the file or the codebase at that site; page size is hardcoded `20` in `AdminReviewService:48`. See F9. |
| 14a | `CoachPlayerAuthorizationService:20-21`, 4 statuses | MATCH | `ACCEPTED, CONFIRMED, COMPLETED, UPCOMING`. **`private static final`** (see F1). |
| 14b | `:23-24` only two ctor deps | MATCH | `BookingRepository`, `CoachProfileRepository`; `@RequiredArgsConstructor`, class-level `@Transactional(readOnly = true)`. |
| 14c | `:30` / `:43` two overloads | MATCH | `(Long coachUserId, …)` and `(UUID coachProfileId, …)`. Throws `OperationNotAllowedException`. |
| 15 | `CoachRosterService:53-56`, 12 statuses | MATCH | Count and membership exactly as stated. |
| 16 | `RadarDisplayService.java:46-50` inline | DRIFTED (±1) | Real `:45-48`, inline in `getRadarDisplay`, no named helper. |
| 17 | `SluDashboardService.java:132-138` | MATCH | Named private `requireCoachPlayerRelationshipIfCoach`. |
| 18 | `SluContributionService.java:100-106` | MATCH | Same named private helper. |
| 19 | `SluTargetService.java:66-72` | MATCH | Same named private helper. |
| 20 | `ReportGenerationService.java:239-244`, inside `listReports` not `generateReport` | MATCH | `:239 listReports`, predicate `:240-243`. **Story is right** — and it *corrected* the ledger, which names `:231`. |
| 21 | `ReportGenerationService:156-158` admits any non-null lastSession | MATCH | `:156` resolve, `:157-158` null-check-and-throw only. |
| 22a | `TimelineQueryService.getTimeline:31-53` | DRIFTED | Real `:31-64`. Cited range stops before the unbounded repo read (`:55-56`) and `redactRecapIfNotAuthoringCoach` (`:61`) — the two lines AC4 most depends on. |
| 22b | `:36-40` bounds `1L..3650L`, 90L fallback | MATCH | `:36` bounded read inside `try`; `:37 catch (Exception e)` → `:39 expiryDays = 90L`. |
| 23a | `RosterAttentionCalculator:70-71` | MATCH | Clamp to `dormantAfterDays`, divide by `dormantAfterDays`. |
| 23b | `:64` dormant boolean | MATCH | `:64-65`, `>=` comparison. |
| 24 | `EditCoachBrandingDialog.vue:191-193`, `:208-210`, `setError(err)` at `:210` | MATCH | Character-exact at all three. |
| 25 | `CoachPlayerDetailPage.vue:107-112` | MATCH | `:107-111` (112 blank). |
| 26 | `ParentDevelopmentPortalPage.vue:146-150` | **DRIFTED** | Real `:157-161`. `:146-150` is `loading`/`hasActivePacks`. See F16 — this is the one citation taken on the ledger's word. |
| 27 | `MessagingService.java:71` separate status list | MATCH | `:71` with own `CONFIRMED_STATES` (`:53-54`) — excludes `ACCEPTED`, includes `IN_PROGRESS`. |
| 28 | `BookingCompletedEvent.java:24-29` defaults recap to null | MATCH (±1) | Overload `:24-30`, delegates with `null, null`. |
| 29 | `CoachRosterServiceTest:96`, `:217` | MATCH | Exactly two stub sites. |
| 30 | `ReportGenerationServiceTest:91`, `:436` | MATCH | Exactly two construction sites. |
| 31 | `src/utils/coachContributions.js` exports | MATCH | `CONTRIBUTION_THRESHOLD_PCT`, `topAttributions`, `hasTopAttributions`. No selection-rule helper — AC10's premise holds. |
| 32 | V162 is the next free migration | MATCH | Highest is `V161__development_player_timeline_events_coach_lookup_index.sql`; no V16x claimed, no pending migration. |

---

## 2. Ledger & precedent attribution

`deferred-work.md` working tree: **3531 lines** (HEAD version: 3821; diff `7 insertions, 297 deletions`).

### Source-table ledger citations — 10/10 correct

| AC | Line | Bullet at that line | Section | Open? | Matches AC? |
|----|------|---------------------|---------|-------|-------------|
| 1 | `:3517` | wrap-up abandonment strands booking in `COMPLETED_PENDING_CONFIRMATION` | `:3515` 12-2 | yes | yes |
| 2 | `:3518` | recap text survives GDPR erasure | `:3515` 12-2 | yes | yes |
| 3 | `:3511` | unbounded per-player roster fan-out | `:3509` 12-1 | yes | yes |
| 4 | `:3519` | unbounded timeline query, ~1 KB recap/session | `:3515` 12-2 | yes | yes |
| 5 | `:3512` | roster membership wider than auth predicate | `:3509` 12-1 | yes (width half explicitly still open) | yes |
| 6 | `:3523` | self-owner arm omitted, "now in four copies" | `:3521` 12-4 | yes | yes — story's "four/five" hedge is accurate; the bullet's title says four while its body lists five |
| 7 | `:3529` | report generation unbounded vs timeline's 90d | `:3527` d-152 | yes | yes |
| 8 | `:3513` | `dormantAfterDays` doubles as recency normalizer | `:3509` 12-1 | yes | yes |
| 9 | `:3530` | failed logo upload reported as connectivity, status discarded | `:3527` d-152 | yes | yes |
| 10 | `:3531` | contribution empty-state *selection rule* duplicated | `:3527` d-152 | yes | yes |

All ten resolve to the right bullets post-prune, all genuinely open. The story also **corrects** two ledger errors rather than copying them (12 statuses not 10; `ReportGenerationService` predicate at `:239ff` not `:231`; 5 copies not 4) — real evidence that HEAD re-verification was performed on most body citations.

### Precedent claims

| Claim | Sources checked | Verdict |
|---|---|---|
| d-152 AC2 extracted the threshold but not the selection rule | d-152 story `:256`; `coachContributions.js` at HEAD | **TRUE** |
| d-152 left two `AvailabilityManagerPage.vue` duplicates out of scope "because optional" | d-152 story `:191`, `:256` | **TRUE on fact, mischaracterized reason** — d-152's stated reason is *store-wide scope framing*, and it calls the fix "a one-line swap". LOW. |
| 12-1's Performance note already rejected a bespoke aggregate query | `skillars-12-1-….md:99` | **TRUE, verbatim** |
| V153 is the CHECK-widening precedent incl. lint comment + reasoning | V153 file; `V138:128` | **TRUE** (V153 widens a *different* constraint, `admin_alerts_type_check`, so it is a pattern precedent — which is how AC2 words it) |
| `CoachReviewRepository.anonymiseApprovedReviews` = "null a free-text column in place" idiom | `CoachReviewRepository.java:103-106`; repo-wide grep | **FALSE on mechanism** — `SET author_id = 0`, an integer sentinel on an id column; nulls nothing. See F13. |
| `generateReport_coachWithNoPlayerRelationship_throwsAccessDeniedException` exists | `ReportGenerationServiceTest.java:193` | **TRUE** |
| `AdminDisputeResource`/`DisputeService` pattern: `HAS_ADMIN_ROLE`, `@Observed`, literal `resolveAdminId()`, `Principal.getBusinessId()` via `SecurityUtil` | AdminDisputeResource read in full | **TRUE, 4/4** — but see F18 on where `resolveAdminId()` lives |
| "`findRosterPlayerActivityByCoachIdAndStatusIn`'s / `findPendingQuickCompletes`'s JPQL-derived-query house style" | both repositories | **Terminology wrong, locus wrong** — both are hand-written `@Query` JPQL (not derived queries, a self-contradictory label), and `findPendingQuickCompletes` lives in **`SessionCompletionDataRepository`**, not `BookingRepository`. LOW. |
| d-152 touched `RosterAttentionCalculator` | `git show --stat ec70ef4c` | **TRUE** |
| Ledger's revisit trigger is "~100 players" | ledger `:3511` | **TRUE** — default 150 is a sound margin |

### Two ledger-hygiene defects

- **Task 3 (story `:103`) cites `deferred-work.md:3797` — a line that does not exist** in a 3531-line file. It is the bullet's *pre-prune* number (confirmed: `git show HEAD:…deferred-work.md | grep -n "survives GDPR erasure"` → 3797). The Source table's `:3518` is right. This falsifies the story's own line-7 blanket claim that **all** citations in the Source table *and body* were re-verified post-prune. (The prune shifted numbers **down**, not "upward" as that note says.)
- **"13 stale, already-closed bullets" understates the prune by half.** The diff removes **~26 top-level bullets across 7 section headers** (−290 net lines). The prune's *substance* is clean — every removed bullet carried an explicit closure marker (`CLOSED by …` / `RESOLVED by …`), and nothing still-open was lost; "13" is exactly the count of one cluster (`CLOSED by skillars-deferred-144`) reported as the whole pass.

---

## 3. Mechanistic claims

| Claim | Verdict | Evidence |
|---|---|---|
| All recap/rating/voice-note fields nullable; placeholder row needs no migration | **TRUE** | `SessionCompletionData.java` — no `nullable=false` and zero validation annotations on those fields; rating CHECKs evaluate `NULL` (not `FALSE`) so they pass; `'QUICK'` is in the `completion_mode` allowlist. Two omissions: `coach_id`/`player_id` are `NOT NULL` (F12), and `session_completion_data_booking_id_key UNIQUE (booking_id)` exists (F10). |
| `ActorRole` never persisted; `TransitionContext` plain record; zero-migration | **TRUE** | `grep actor_role` over `src/main/java` + `src/main/resources`: **zero hits**. No booking transition/audit table exists at all. No `switch` on `ActorRole`, no `ActorRole.values()` — membership tested only via `EnumSet.contains` (`BookingService:719-727`). |
| `BOOKING_FORCE_COMPLETE` is 22 chars, fits `varchar(25)` | **TRUE** | `V138:124 action_type character varying(25) NOT NULL`; 22 ≤ 25. Existing CHECK `admin_action_log_action_type_check` (`V138:128`) has 10 values — which is why AC2 correctly plans V162. |
| `Pageable` on the GROUP BY JPQL works, no rewrite | **PARTIALLY-TRUE** | With the existing `List<>` return: **works** (no count query derived). With a `Page<>` return it **breaks** — count derivation over `GROUP BY` yields one row per group → `IncorrectResultSizeDataAccessException`. Also the sort must live in the query text, not on the `PageRequest` (Spring Data *appends* `Pageable` sort after the method's existing `ORDER BY`, making it a no-op). Spring Boot 3.5.16 / Spring Data JPA 3.5.13. |
| Derived timeline query accepts a trailing `Pageable` | **TRUE** — but `hasMore` does not follow | See F5. `OrderByOccurredAtDesc` takes precedence over any `PageRequest` sort, so pass an **unsorted** `PageRequest`. |
| "All three real call sites … are mutations, not reads" | **FALSE** | See F1. 11 call sites; 8 are reads. |
| Five identical predicate copies; 5th in `listReports`; all missing the self-owner arm | **TRUE** | Predicate text character-identical at all five. `PlayerOwnershipGuard:32-34` holds the `existsByIdAndParentId(...) \|\| existsByIdAndUserId(...)` arm. Repo-wide grep finds **no 6th or 7th copy**. Shape nuance the story omits: three are already extracted into an identically-named private helper, two are inlined. |
| `getBoundedLong` 1..3650 with try/catch → 90L on any failure, incl. missing key | **TRUE** | `ConfigService:133` → `getLong` → `getString:68-75` throws `IllegalStateException` when absent. Nuance: an **out-of-range** value is *clamped*, not defaulted — the 90 fallback fires only for missing/non-numeric. |
| AC8 arithmetic: `RECENCY_WEIGHT` makes d=30 → ≈0.133, d=31 → ≈0.138, d=90 → 0.4 | **TRUE** | `RECENCY_WEIGHT = 0.4` (`:19`). All three values check out. Both named tests exist (`:60`, `:97`) and assert `≈0.4` exactly as the story says. Latent hazard the story doesn't flag: `dormantAfterDays` is the raw divisor at `:71` — a `0` would yield `NaN` and poison every downstream sort. |
| "No repo-wide `Pageable`/`@PageableDefault` controller-param convention"; AdminReviewResource is the one precedent | **FALSE** (except `@PageableDefault`) | See F9. |
| New deps on `CoachPlayerAuthorizationService` are additive; every proposed name exists | **TRUE** | `isCurrentUserInRole` (`SecurityUtil:126`), `requireCurrentUserId` (`:263`), `getCurrentCoachUserId` (`:293`), `existsByIdAndParentId` (`PlayerProfileRepository:37`), `existsByIdAndUserId` (`:46`), `AuthoritiesConstants.COACH` (`:16`). **Nothing in the proposed snippet fails to exist.** |
| `RadarDisplayService` has zero coverage; `CoachPlayerAuthorizationService` has no dedicated test | **FALSE both halves** | See F14. |
| No path grants both `ROLE_COACH` and a self-owned `PlayerProfile` | **TRUE** | `ShadowAccountResource:46-52` is `HAS_PLAYER_ROLE`; `CoachRegistrationService:101` uses `setAuthorities(Set.of(coachAuthority))`, which *replaces*. Only `ShadowAccountService:105` writes a non-null `PlayerProfile.userId`; no path adds `ROLE_COACH` to an existing user. The AC6 fix is genuinely defense-in-depth, not a live bug. |
| `QuickCompleteTimeoutService` structure + transaction shape | **TRUE** | `:50-52` annotations exact. Method is **not** `@Transactional`; config read and batch query run outside any tx; per booking, `transactionTemplate.executeWithoutResult` begins one physical tx that `bookingService.transition` (REQUIRED) **joins**. One booking's status write + `BEFORE_COMMIT` payout enqueue commit together; a failure never rolls back an earlier booking. The catches sit **outside** the template — load-bearing, or commit throws `UnexpectedRollbackException`. |
| `BookingCompletionService` "already owns `confirmCompletion`'s structurally similar sequence" | **MISATTRIBUTED** | See F11. |
| Recap fields nullable-safe for all `BookingCompletedEvent` consumers | **TRUE on nullability** | Five listeners; only `TimelineEventListener:56-58` touches the recap fields and null-guards both. No consumer requires a non-null recap. The real risk is elsewhere — F3 and F8. |
| `session_completion_data` is in the `booking` schema; keep it out of the dev lock-budget groups | **TRUE** | `V138:267 CREATE TABLE booking.session_completion_data`. Both method names exist (`deletePlayerDevelopmentData:1088` wrapper, `…InDedicatedPool:1125`); the `(12 + M)` lock arithmetic is on the **wrapper's** Javadoc (`:1031-1045`), not the dedicated-pool method's. Keeping the new statement out of the hardcoded per-group counts (`:1149-1180`) is the right call. |
| `quick_complete_timeout_hours` is "1–168h / default-1h" | **PARTIALLY-TRUE** | Bounds right; **default is 24h** (`V139:59`), not 1h. See F4. |

---

## 4. Corner cases, false assumptions, missed flows

Ordered by severity. All evidence below was re-opened and re-quoted during the adversarial pass.

### F1 — HIGH — AC5's scope exclusion rests on a false premise; three coach read endpoints stay 403'd (AC5)

The story: *"AC5's research confirmed all three of its real call sites (`RadarAssessmentService.submitAssessment`, `ReportGenerationService.generateReport`, `SluTargetService.setTargets`) are mutations, not reads."* `ACTIVE_RELATIONSHIP_STATUSES` is `private static final` (`CoachPlayerAuthorizationService:20-21`), so the real question is the callers of the guard method — and there are **11**, of which 8 are reads:

```
RadarAssessmentService.java:48   submitAssessment            write
RadarAssessmentService.java:95   getMyEntries                READ   @Transactional(readOnly = true)
ReportGenerationService.java:138 generateReport              write
ReportGenerationService.java:242 listReports                 READ   (one of AC6's five)
SluTargetService.java:32         getTargets                  READ   @Transactional(readOnly = true)
SluTargetService.java:41         setTargets                  write
SluTargetService.java:69         (helper → getNeglectedSkills) READ  (one of AC6's five)
DevelopmentCorrelationService.java:50 getInsights            READ
RadarDisplayService.java:47      getRadarDisplay             READ   (one of AC6's five)
SluDashboardService.java:135     (helper → 3 entry points)   READ   (one of AC6's five)
SluContributionService.java:103  (helper → :42)              READ   (one of AC6's five)
```

**Three read sites are in neither AC5 nor AC6's five** and will keep the narrow 4-status check: `RadarAssessmentService.getMyEntries`, `SluTargetService.getTargets`, `DevelopmentCorrelationService.getInsights`. All three are coach-gated GETs — `@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)` at `RadarAssessmentResource:37`, `SluTargetResource:32`, and `RadarDisplayResource` (correlation).

**Failure scenario:** a coach whose only booking with a roster player is `PAUSED`/`NO_SHOW_PLAYER`/`DISPUTED`/`REFUND_PENDING` still gets `OperationNotAllowedException` on `GET …/radar/entries`, `GET …/targets`, and `GET …/radar/correlation` — so AC5's stated outcome ("a coach is never 403'd viewing detail for a player who is legitimately on their own roster") is not achieved. Worse, the story instructs the dev to leave `ACTIVE_RELATIONSHIP_STATUSES` alone *because* its callers are all writes, planting a false premise for the next reader.

*Partial mitigation (does not fix the premise):* the four components calling those endpoints are used only by `pages/player/PlayerDevelopmentDashboardPage.vue`, whose route is `meta: { role: 'PLAYER' }` (`router/routes.js:284-288`), so no coach UI reaches them today. The owner decision behind AC5 needs re-taking on correct facts.

### F2 — HIGH — Both new config keys as specified fail an existing test (AC1, AC3)

```java
// ConfigBoundsEnumCoverageTest.java — hasCodeDefaultKeysDefaultIsWithinItsOwnBounds
} else {
    assertThat(k.defaultValue())
        .as("defaultValue is the not-applicable 0L sentinel for non-HAS_CODE_DEFAULT key %s", k.key())
        .isEqualTo(0L);
```

The story says to declare `booking.stuck_completion_timeout_hours` (**default `24L`**) and `development.roster.maxPlayers` (**default `150L`**) *"the same way as the existing `booking.quick_complete_timeout_hours` key"*. That key carries the **`0L` sentinel** and is **not** in `HAS_CODE_DEFAULT` — it works only because `V139:59` seeds it.

**Failure scenario:** declare either key with a real default and omit it from `HAS_CODE_DEFAULT` (`ConfigBounds:384-404`) → `ConfigBoundsEnumCoverageTest` fails. Add it to `HAS_CODE_DEFAULT` but read it with the 2-arg `getBoundedLong(key, min, max)` (the quick-complete pattern the story points at) → `IllegalStateException` on the absent key at the first sweeper tick. Omit it from the hand-maintained `ConfigBounds.ALL` list (`:409-440`) → invisible to `ConfigStartupAssertion`. Rely on a seed instead → a **third** migration the story doesn't plan. Exactly one coherent path exists (`HAS_CODE_DEFAULT` + the 4-arg read, as `DEVELOPMENT_ROSTER_DORMANT_AFTER_DAYS`/`CoachRosterService:86-90` do) and the story prescribes the other. The story never mentions `HAS_CODE_DEFAULT` or `ALL` at all.

### F3 — HIGH — `playerAttended = true` silently grants a fresh 90-day coach access clock (AC1, interacts with AC7)

`playerAttended` is a gate, not inert metadata:

```java
// TimelineEventListener.java:60-62
// skillars-12-3 AC1: a non-attended wrap-up must not earn coach timeline access any
// more than it earns an SLU row today — mirrors SluCalculationService.onBookingCompleted's
// own !event.isPlayerAttended() early return exactly.
UUID coachIdForClock = event.isPlayerAttended() ? event.getCoachId() : null;
```

`coachIdForClock` becomes the `coach_id` on the `SESSION_COMPLETED` timeline row that `CoachPlayerLastSessionResolver.resolve` reads — the sole input to `TimelineQueryService`'s access clock (`:47-49`) and, per AC7, to report generation's new recency bound.

**Failure scenario:** a coach abandons the wizard; the sweeper writes `playerAttended = true`; the coach's 90-day window into that player's full development timeline resets from a session the coach never confirmed happened — and under AC7 the same coach simultaneously regains report-generation rights. The Dev Notes justify `true` purely as "every existing downstream consumer keeps working unmodified" and never surface that two consumers **branch** on it. `false` is the conservative value and matches the quoted skillars-12-3 principle; this needs a product decision, not a default.

### F4 — HIGH — AC1's default-sizing rationale is factually wrong, and the cutoff can destroy an in-flight recap (AC1)

The story: *"intentionally shorter than `quick_complete_timeout_hours`'s 1–168h/**default-1h** window's purpose"*. There is no 1h default:

```sql
-- V139__baseline_seed_data.sql:59
... ('booking.quick_complete_timeout_hours', '24', 'LONG', 'Hours before un-confirmed Quick Complete auto-confirms') ...
```

(`QuickCompleteTimeoutServiceTest:61` stubs `24L` too.) So the proposed new default **equals** the live one — "intentionally shorter" is false, and both sweepers would run on the same status at the same 24h cutoff.

On `updatedAt` semantics: `Booking` has `@PreUpdate onUpdate()` (`Booking.java:86-89`), and wizard progress writes **nothing** to the booking row — `submitWrapUp` only saves `SessionCompletionData` then transitions. So `updatedAt` on a row-less `COMPLETED_PENDING_CONFIRMATION` booking is exactly the transition time, i.e. the cutoff measures "time since the session ended", not "time since the coach went idle".

**Failure scenario:** at the `1L` min bound — or plausibly at 24h for a coach who ends a session and writes the recap the next evening — the sweeper fires mid-wizard. The coach's later `submitWrapUp` hits `verifyStatus(booking, COMPLETED_PENDING_CONFIRMATION)` (`BookingCompletionService:132`) and is rejected with `"expected COMPLETED_PENDING_CONFIRMATION"`; the recap is lost, and the payout has already been released (F8).

### F5 — HIGH — AC4's `hasMore` is unimplementable as specified (AC4)

```java
// PlayerTimelineRepository.java:14
List<PlayerTimelineEvent> findByPlayerIdOrderByOccurredAtDesc(Long playerId);
```

AC4 says *"Add a trailing `Pageable` parameter — … no `@Query` rewrite needed"* **and** *"`TimelineResponse` gains one new field, `boolean hasMore` (from `Page.hasNext()`)"*. A derived query with a `Pageable` and a `List<T>` return applies limit/offset but produces no `Page`, so `hasNext()` does not exist. The return type must become `Page<>` (adds a COUNT query) or `Slice<>` (no COUNT, `hasNext()` via n+1) — a decision the story never makes, and that neither Task 5 nor the File List mentions. Cheap to fix: the only caller is `TimelineQueryService:55-56`.

### F6 — HIGH — AC4's "append" is impossible against the current store, and two frontend files are missing from the File List (AC4)

```js
// development.store.js:218-227
async function fetchTimeline(playerId) {
  if (!playerId) return
  const seq = ++_timelineSeq
  timeline.value = null          // ← wipes the accumulated events
  timelineLoading.value = true
  ...
  const response = await getTimeline(playerId)
  if (seq === _timelineSeq) { timeline.value = response }
```

`timeline` is a single `ref(null)` holding the whole response (`:38`) — there is no separate events array to append to. Page 2 nulls it first, and `timelineLoading = true` simultaneously hides the entire panel (`PlayerTimelinePanel.vue:7` `<q-inner-loading :showing="store.timelineLoading" />`, `:29` `v-else-if="store.timeline && !store.timelineLoading …"`). The store needs a distinct append path and a distinct loading flag.

Also missing from the File List: `src/frontend/src/api/development.api.js` (`:39 getTimeline = (playerId) => api.get(…)` must take `page`/`size`) and `stores/__tests__/developmentStoreSpec.js:26`, which mocks `getTimeline`.

### F7 — HIGH — AC7's test instruction will red every `generateReport` happy path (AC7, AC11)

AC7 says to duplicate `TimelineQueryService`'s try/catch-with-90-fallback, and scopes the test work to *"update **both** existing `ReportGenerationServiceTest` construction sites, `:91` and `:436`"* (both citations verified exact). A bare Mockito `@Mock ConfigService` does not throw — `getBoundedLong` returns `0L`. Then `expiryDays = 0` and `lastSession.isBefore(Instant.now().minus(0, DAYS))` is true for every fixture, including the happy-path one:

```java
// ReportGenerationServiceTest.java:111-112
when(coachPlayerLastSessionResolver.resolve(PLAYER_ID, COACH_ID)).thenReturn(
    new CoachPlayerLastSessionResolver.LastCoachSessionSignal(Instant.now().minusSeconds(3600), null));
```

**Failure scenario:** followed literally, every `generateReport` success test throws `AccessDeniedException`. AC11 must also require a `getBoundedLong` stub in `setUp`/`stubGenerateReport`.

### F8 — HIGH — AC1/AC2 move money, and falsify an audited invariant (AC1, AC2)

```java
// CoachPayoutEnqueueListener.java:19-23
 * <p>All three {@code BookingCompletedEvent} publish sites
 * ({@code BookingCompletionService.submitWrapUp} LIVE path, {@code confirmCompletion}, and
 * {@code QuickCompleteTimeoutService.processExpiredQuickCompletes}) publish inside an active
 * transaction, so {@code BEFORE_COMMIT} + {@code MANDATORY} enqueue is atomic in every case
 * (verified during skillars-deferred-106 impl — no fourth publisher, no out-of-transaction path).
```
```java
@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
public void onBookingCompleted(BookingCompletedEvent event) {
    coachPayoutOutboxSupport.enqueuePayout(event.getBookingId(), event.getCoachId());
}
```

**The sweeper and the admin force-complete release the coach's payout** for a session with no coach-authored wrap-up. AC1/AC2 never state this. They also become publish sites **#4 and #5**, so: (a) both **must** publish inside an active transaction or `MANDATORY` throws `IllegalTransactionStateException` (satisfied if the shared method keeps the file's `@Transactional` and the sweeper's `TransactionTemplate` joins it — one physical transaction, no nesting); and (b) this Javadoc must be updated in the same commit or it becomes an actively false audited claim. Worth an explicit AC.

### F9 — MEDIUM — AC4's cited pagination precedent does not exist, and the "no convention" claim is false (AC4)

`AdminReviewResource.getQueue` (`:38-45`) takes `status` + `page` only and clamps `Math.max(0, page)`; **there is no `size` param and no `200` ceiling** anywhere in that file, and page size is hardcoded `20` in `AdminReviewService:48`. Separately, the story's *"there is no repo-wide `Pageable`/`@PageableDefault` controller-param convention to follow instead, confirmed by a repo-wide search"* is false as to `Pageable`:

```java
// AdminGdprResource.java:28-32
public ResponseEntity<Page<AdminGdprRequestDto>> listRequests(
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String status,
        Pageable pageable) {
```

Eleven REST pagination sites exist. The best model for a *bounded* `size` is `CoachMarketplaceResource:58-59` (`@Min(0) int page`, `@Min(1) @Max(50) int size`) or `ReliabilityStrikeResource:47` (`MAX_PAGE_SIZE = 100`). Only the `@PageableDefault` half of the claim holds (zero occurrences).

### F10 — MEDIUM — AC1's TOCTOU guard doesn't close the race; the UNIQUE index does, and the sweeper will log it as ERROR (AC1)

AC1's order is pre-check (unlocked) → persist row → `transition` — but the row lock is taken inside `transition` (`BookingService:163-166`, `findByIdForUpdate` + `refresh(PESSIMISTIC_WRITE)`), i.e. *after* the insert. A concurrent `submitWrapUp` landing in between collides with `session_completion_data_booking_id_key UNIQUE (booking_id)` → `DataIntegrityViolationException`. That is the real guard, and `submitWrapUp:155-159` already treats it as a benign idempotency signal (logs and returns). But it is **not** in the catch list AC1 says to mirror:

```java
// QuickCompleteTimeoutService.java:73-77
} catch (BookingStateTransitionException | OptimisticLockingFailureException e) { log.warn("… already completed by another instance — skipping");
} catch (Exception e) { log.error("Failed to auto-confirm Quick Complete for booking {}", …); }
```

So every benign race emits an ERROR. Note also a plain non-locking re-read is **not** sufficient on its own: under READ COMMITTED it sees a *committed* competitor but does not block an in-flight one. Going through `transition()` gets the pessimistic lock for free. AC1 should name `DataIntegrityViolationException` as both the real guard and a benign skip.

### F11 — MEDIUM — the shared-method precedent names the wrong method (AC1)

Dev Notes: *"Writing it once on `BookingCompletionService` — which already owns `confirmCompletion`'s structurally similar sequence."* `confirmCompletion:187-209` is **transition → read existing row → publish**, and it *hard-fails* when no row exists (`:200-201 .orElseThrow(… "Session completion data not found")`). The method that builds and saves a row is **`submitWrapUp:127-170`**, in the order **save → transition → publish**.

This matters beyond pedantry: `submitWrapUp` is the actual template for AC1's sequence, and it already contains the `DataIntegrityViolationException` idempotency guard F10 needs — and its handling (log + return, not throw) is the right shape for the *sweeper*, whereas AC1's "throw otherwise" instruction is right only for the *admin* action. The story's one-size instruction conflates them.

### F12 — MEDIUM — the placeholder field list omits both NOT NULL columns (AC1)

AC1 enumerates `playerAttended = true`, `completionMode = "QUICK"`, *"every rating/recap/voice-note field left `null`"* — never mentioning `coachId`/`playerId`, which are `nullable = false` (`SessionCompletionData.java:31-35`; `V138:270-271`). Both must be set from `booking.getCoachId()`/`booking.getPlayerId()` as `submitWrapUp:136-137` does. `coachId` is also precisely what drives the payout (F8) and the access clock (F3).

### F13 — MEDIUM — the GDPR repository instruction mirrors an idiom that doesn't exist (AC2 / Task 3)

```java
// CoachReviewRepository.java:103-106
// Sets author_id = 0 (sentinel for "deleted user") on APPROVED reviews — authorId is BIGINT, not UUID
@Query(value = "UPDATE reviews.coach_reviews SET author_id = 0 WHERE author_id = :authorId AND moderation_status = 'APPROVED'", nativeQuery = true)
int anonymiseApprovedReviews(@Param("authorId") Long authorId);
```

Task 3 says to null the three recap columns *"mirroring `CoachReviewRepository.anonymiseApprovedReviews`'s existing **null-in-place idiom**"*. That query writes an **integer sentinel to an id column** and nulls nothing. No null-a-free-text-column precedent exists in this repo. Also note the spelling split: existing code is `anonymise`, the story proposes `anonymizeByPlayerId`.

*The underlying plan is still sound:* `SessionCompletionData` does carry `private Long playerId` (`:35`) populated from `booking.getPlayerId()` — a `PlayerProfile` PK, the same id space `GdprErasureService` locks (`:1127-1129`, fed from `.map(PlayerProfile::getId)` at `:453`). So `anonymizeByPlayerId(Long)` is viable; only the cited precedent is wrong. The three named columns are also the complete free-text set (`voice_note_text`, `what_we_did`, `whats_next`; the only other text column is `homework_drill_ids`, a UUID array).

### F14 — MEDIUM — two "no coverage" claims are wrong, and the natural test home goes unmentioned (AC6, AC11)

- *"`RadarDisplayService` … currently has zero test coverage of any kind"* — **false.** `src/test/java/…/development/api/RadarDisplayResourceIT.java` has ~12 tests driving the endpoint end to end, including the authorization paths AC6 touches (`getDisplay_asParentForOtherPlayersChild_returns403:137`, `getDisplay_asCoach_returns200WithAllSkills:116`). No *unit* test, which is not the same thing.
- *"`CoachPlayerAuthorizationService` has **no dedicated unit test today**"* — literally true, materially misleading. `src/test/java/…/development/api/CoachPlayerAuthorizationIT.java` is its dedicated existing coverage, and one test is directly on point for AC6: `coachRoleParentOwnsPlayer_withNoCoachingRelationship_stillSucceeds:137`, which seeds a dual-role coach-parent and asserts the parent arm works. That is the natural home for the self-owner-arm regression and AC5's widening cases, and the story never names it. *(Checked for breakage: its only 403 case inserts no booking, so AC5's widening does not flip it.)*

### F15 — MEDIUM — two named-as-existing test files do not exist (AC11 / File List)

- `src/test/java/…/admin/service/GdprErasureServiceIT.java` — **does not exist**. Present: `GdprErasureServiceTest.java`, `GdprErasureRetryIT.java`, `GdprErasureServiceConcurrencyIT.java`, `GdprErasureDataSourceRoutingIT.java`. Listed as "(modified, AC2)".
- `src/frontend/src/components/development/__tests__/PlayerTimelinePanelSpec.js` — **does not exist** (that directory holds only `SkillsRadarChartSpec.js`). Listed under "Frontend tests — **modified**".

The five files marked "new" are correctly marked.

### F16 — MEDIUM — one citation was taken on the ledger's word, against the story's own self-certification (AC10)

The story cites `ParentDevelopmentPortalPage.vue:146-150`; the real location is `:157-161` (`:146-150` is `loading` + `hasActivePacks`). Decisively, **ledger `:3531` carries the identical `ParentDevelopmentPortalPage.vue:146-150`** — so this range was copied, not re-derived. That directly contradicts the story's prominent claim that every citation was independently re-verified against HEAD `d9d9002b`. (The substantive observation that the parent copy's comment still reads "for a coach" is correct, at `:154-155`.)

### F17 — MEDIUM — AC4 misses a `TimelineResponse` construction site and under-scopes the blast radius (AC4)

`TimelineResponse` has two construction sites, both in `TimelineQueryService`: `:63` and — unmentioned — `:51 new TimelineResponse(true, expiryDays, List.of())`, the access-expired early return, which also needs a `hasMore` value. No test constructs it directly, so the record change is otherwise contained. But `PlayerTimelinePanel.vue` has **three** page consumers, not one: `CoachPlayerDetailPage.vue:68`, `ParentDevelopmentPortalPage.vue:118`, `PlayerDevelopmentDashboardPage.vue:115` — so both the 50-event truncation and the new Load-more affordance land on parent and player surfaces too.

### F18 — MEDIUM — AC2 places `resolveAdminId()` opposite to the precedent it cites (AC2)

AC2 has `AdminBookingCompletionService` resolve the admin id *"the same `resolveAdminId()` idiom … via `SecurityUtil`"*. In the cited precedent — and in all four admin resources — the idiom is a private method on the **resource**, with the id passed into the service as a parameter: `AdminDisputeResource:62` (used `:48`, `:58`), `AdminReviewResource:67`, `AdminModerationResource:104`, `AdminCoachEnforcementResource:100`. `DisputeService` receives `adminId`.

### F19 — MEDIUM — `Page<>` must not be used for the roster query, and the sort must live in the JPQL (AC3)

AC3's `ORDER BY MAX(b.requestedStartTime) DESC, b.playerId ASC` is correct and legal, and it correctly preserves the tie-break. Two unstated constraints: the return type must stay `List<>` (a `Page<>` return derives a count query over `GROUP BY b.playerId` → one row per group → `IncorrectResultSizeDataAccessException`), and the ordering must be written into the `@Query` text with an **unsorted** `PageRequest` (Spring Data appends a `Pageable` sort *after* the method's existing `ORDER BY`, making it a silent no-op).

### F20 — MEDIUM — Task 3 cites a nonexistent ledger line (AC2)

`Task 3: GDPR erasure of recap free text (AC: 2 — ledger line ':3797')` — the file is **3531 lines**. Correct is `:3518`, which the Source table has right. Low implementation risk (unambiguous bullet), but it is the concrete counterexample to the story's blanket re-verification claim.

### LOW

- **L1 — `BookingStateTransitionException` has no message constructor.** Only `(BookingStatus from, BookingEvent event)`. AC1's "throw a `BookingStateTransitionException`/`OperationNotAllowedException`" for the row-already-exists case can't use the former meaningfully; `OperationNotAllowedException` with a `BookingError` code is the real option — and a new code needs entries in all three message bundles (`MessageBundleParityTest`).
- **L2 — AC3's cap is consumed by missing-profile rows.** `CoachRosterService:97-98` filters activities whose `PlayerProfile` is missing *after* the query, so under `PageRequest.of(0, maxPlayers)` such a row occupies a budget slot and silently shrinks the roster.
- **L3 — `completionMode = "QUICK"` is the only legal non-LIVE value** (`V138:280` CHECK allows `LIVE`/`QUICK` only), so the "no migration needed" claim is right, but force-completed rows become permanently indistinguishable from genuine quick-completes in every query, audit, and analytic.
- **L4 — two "deliberately independent" comments go stale.** `CoachRosterService:43-45` ("Never widen that constant to share this one") and `BookingRepository:217-221` both explain why the roster list is kept separate from `ACTIVE_RELATIONSHIP_STATUSES`. AC5 respects the *letter* (it adds a new `READ_RELATIONSHIP_STATUSES` rather than widening the old constant) but inverts the *rationale*; AC3 plans to update only the cap half of one of them. Both need rewriting.
- **L5 — `dormantAfterDays` is the raw divisor** at `RosterAttentionCalculator:71`; a `0` yields `NaN` and poisons every downstream comparator. The config bound floors it at 1 today, so this is latent, not live — worth a note where AC8 touches those lines.
- **L6 — `getBoundedLong` clamps out-of-range values** rather than falling back to 90; AC7's "bounded-read-with-90-day-fallback" phrasing is only accurate for missing/non-numeric values.
- **L7 — pre-existing, not caused by this story:** `GdprErasureService` never deletes `session_completion_data` rows at all (grep for the repository/table under `admin/`: zero hits). AC2 anonymizes three columns, which is the right call, but the row itself survives erasure.
- **L8 — terminology/locus:** "JPQL-derived-query house style" is self-contradictory (both cited methods are hand-written `@Query` JPQL, not derived queries), and `findPendingQuickCompletes` lives in `SessionCompletionDataRepository`, not `BookingRepository`.
- **L9 — prune accounting:** "13 stale bullets" vs an actual ~26 bullets across 7 removed sections (−290 lines). The prune itself is clean; only the count is wrong.
- **L10 — d-152 attribution:** its reason for deferring the two `AvailabilityManagerPage.vue` duplicates was store-wide scope framing, not optionality.
- **L11 — citation drift roll-up** (all located, none fatal): `QuickCompleteTimeoutService` `49-72`→`50-79`; `ConfigBounds` `99-103`→`97-100` and `163-166`→`162-164`; `CoachRosterService.getRoster` `67-99`→`68-104`; `BookingRepository` `224-240`→`228-240` and `226-230`→`223-227`; `RadarDisplayService` `46-50`→`45-48`; `TimelineQueryService.getTimeline` `31-53`→`31-64`; `BookingCompletedEvent` `24-29`→`24-30`; `ParentDevelopmentPortalPage.vue` `146-150`→`157-161` (F16); V159 as the source of `voice_note_text` → `V138:276`.

---

## 5. Verified correct — do not re-litigate

These were checked against real current source and hold. Several are claims a lazier audit would have flagged:

- **`COMPLETED_PENDING_CONFIRMATION --COMPLETE--> COMPLETED` is a legal transition** (`BookingStateMachine:68-72`), and `EVENT_ROLES[COMPLETE] = {PARENT, SYSTEM}` already admits the sweeper with no change.
- **No sweeper overlap or conflict.** The only other sweeper on this status requires `completionMode = 'QUICK'` **and** an existing row (`SessionCompletionDataRepository:17-33`), so AC1's `NOT EXISTS` predicate is disjoint; and because the placeholder row commits in the same transaction as the `COMPLETED` transition, it can never be re-picked up. The legitimate "awaiting parent confirmation" state is precisely the row-*exists* case (`BookingCompletionService:174-184`), so a row-less sweeper does not pre-empt a real parent window. **The abandonment premise itself holds** — both `showWrapUp = true` writers are in-session only (`CoachCommandCenterPage.vue:596`, `:612`) and `v-if="showWrapUp"` unmounts on reload, so there is genuinely no resumable path back into the wizard.
- **`ActorRole.ADMIN` is a zero-risk, zero-migration addition** — no `switch`, no `values()`, no persistence, no CHECK constraint (see §3).
- **V162 is the next free migration number**; `admin_action_log` claims all check out (`varchar(25)`, 10-value CHECK, only V138 touches the table), and V153's pattern is exactly as described.
- **GDPR id spaces line up** — the known `Booking.playerId` = `PlayerProfile` PK gotcha was checked explicitly and is consistent end to end (F13).
- **AC6's identity semantics are safe.** All five call sites already read the caller via `SecurityUtil` with no path-variable identity, so moving the read inside the shared method is behavior-preserving; `existsByIdAndParentId`/`existsByIdAndUserId` and the `getCurrentCoachUserId()` → `Long` overload all line up on id space.
- **AC5's type claim holds** — both constants are `List<String>`, both classes are in `platform.development.service`, so referencing the shared constant compiles with no circular dependency.
- **AC7's parent/admin false-denial risk does not exist** — `PerformanceReportResource:28-35` is `@PreAuthorize(HAS_COACH_ROLE)` and derives `coachUserId` from the caller; there is no parent or admin entry point to `generateReport`. Existing fixtures (`minusSeconds(3600)`) sit well inside 90 days.
- **AC8's arithmetic is right, and only the two named tests break.** `maximalSignals_scoreIsOne` still yields exactly `1.0`; the `hasUpcomingBooking`/equality cases are unaffected; `CoachRosterServiceTest:162`/`:182` both have `daysSinceLastActivity ≈ 0`. **No hidden consumer of the score scale** — every read is relative (a sort, an `isGreaterThan`, a fixture value); no hardcoded cutoff in backend or frontend.
- **AC9 is clean.** Lines are character-exact, and there is **no `no-console` ESLint rule** (`eslint.config.js` sets only `prefer-promise-reject-errors`, `vue/no-bare-strings-in-template`, `no-debugger`); `console.error` is already used in 6+ source files.
- **AC10 is accurate.** The duplication is as described, all four i18n keys exist in all three locales (en-US/de-DE/fr-FR), and there is **no third consumer** — `CoachContributionSection.vue` imports only `topAttributions` and has no empty-state branch.
- **AC3 breaks no existing ordering assertion.** `CoachRosterResourceIT` uses `containsExactlyInAnyOrder`/`containsAll`/relative index comparisons; no `ORDER BY b.playerId` tie-break is pinned. No `RosterResponse`/`RosterEntryResponse` count or page field exists, and the frontend carries no roster count or completeness affordance.
- **`PlayerTimelinePanel.vue` is the real component name/path** — the story's "confirm at implementation time" hedge was unnecessary. No `PlayerTimelineResourceIT` test pins a full event-list length, so pagination breaks nothing there. `/api/admin/bookings` is unclaimed.
- **All ten Source-table ledger citations are correct post-prune**, and the story *corrects* three ledger errors rather than copying them.

---

## 6. What did not survive re-verification

Four layer claims were dropped or downgraded in the adversarial pass:

1. **"AC2's `fits varchar(25)` implies no migration is needed, which is wrong" — killed.** The inference was never made: AC2 explicitly plans `V162` to widen `admin_action_log_action_type_check`, and names the constraint, the pattern, and the lint comment. The length claim is simply a separate, true statement about the column.
2. **"Both 'deliberately independent' comments *directly contradict* AC5" — downgraded to L4.** AC5 does **not** widen `ACTIVE_RELATIONSHIP_STATUSES`; it adds a separate `READ_RELATIONSHIP_STATUSES` and points `CoachRosterService` at that. The letter of "Never widen that constant to share this one" is respected. What survives is narrower: the comments' *rationale* goes stale and needs rewriting.
3. **"AC3's new `ORDER BY` loses the pagination tie-breaker" — killed.** AC3 explicitly specifies `ORDER BY MAX(b.requestedStartTime) DESC, b.playerId ASC`. The real residual risks (no `Page<>`, unsorted `PageRequest`) are kept as F19.
4. **"The `confirmCompletion` precedent is backwards" — downgraded to F11.** The story said "structurally similar", not identical, and `confirmCompletion` does share transition→publish. What survives is that `submitWrapUp` is the correct template, carries the `DataIntegrityViolationException` guard F10 needs, and handles the collision by returning rather than throwing — which the story's single "throw otherwise" instruction conflates across the sweeper and admin paths.

Also set aside: a layer-3 objection that calling `ACTIVE_RELATIONSHIP_STATUSES` (a `private` field) something with "call sites" is a category error — true but pedantic; the substance is folded into F1 without it. And layer 4's mitigation framing on F1 ("no coach UI reaches those endpoints") was only partly upheld: the three endpoints are `HAS_COACH_ROLE` backend reads, so frontend routing limits today's exposure but does not make the story's premise true.

---

## 7. Recommendation

**Do not start implementation as written.** The story is unusually well-researched — 10/10 ledger citations, most precedents verified verbatim, three ledger errors actively corrected, and a long list of claims in §5 that held up under deliberate attack. But eight findings are blocking, and they are not cosmetic: two ACs (F2, F5) **cannot be implemented as specified**, one (F7) reds the build if followed literally, one rests on a **false premise that drove an explicit scope exclusion** (F1), two need an **owner decision the story forecloses** (F3, F4), one has **unstated money movement** (F8), and one frontend AC is specified against a store that cannot do what it asks (F6).

Suggested sequence before `in-progress`:

1. **Re-take two owner decisions** on correct facts: AC5's read/write split (F1 — three coach read endpoints are missed, and the "all three are mutations" premise is false), and AC1's `playerAttended` value plus its timeout default (F3, F4 — the 24h/"intentionally shorter" rationale does not survive, and `true` silently resets a 90-day access clock).
2. **Fix the three unimplementable-as-written specs:** AC4's return type (F5) and store-append path (F6), and AC1/AC3's `ConfigBounds` declaration path (F2).
3. **Add the missing ACs:** payout consequence + the `CoachPayoutEnqueueListener` Javadoc update (F8); the `DataIntegrityViolationException` guard and benign-skip (F10); the `coachId`/`playerId` placeholder fields (F12).
4. **Correct the citations and test plan:** F7's stub requirement, F9's nonexistent precedent, F13's nonexistent idiom, F14/F15's test-file facts, F16/F20's line numbers, F17's second construction site and three consumers, F18's `resolveAdminId()` placement.

**Per-claim confidence.** F1–F8, F10, F12–F18, F20 were each independently re-opened and re-quoted by me in the adversarial pass — HIGH confidence, with the quoted evidence in this report being what I read, not what a layer reported. F9 and F19 rest on Spring Data behavior plus a code read: HIGH on the code facts (the absent `size` param, the `AdminGdprResource` `Pageable`), MEDIUM on the predicted `Page<>`/count-derivation failure mode, which is reasoned from framework behavior rather than executed here. F11's severity is a judgment call, stated as such. L2, L5, L6, L7, L8, L9, L10 come from single-layer reads that I spot-checked but did not fully re-derive — treat as MEDIUM confidence and verify at implementation time. **No test suite, build, or migration was executed as part of this audit** (per this project's CI-is-the-gate convention); F2 and F7 are predicted failures read off the assertion source, not observed ones.
