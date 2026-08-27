# Senior Dev Review: skillars-deferred-77-slu-radar-robustness-and-cross-module-hardening

**Method:** Every AC below was checked against the actual current source (not the deferred-work.md
descriptions alone) — the service classes, repositories, entities, and migration files it names.
Only findings with direct code/schema evidence are included; anything I couldn't confirm is marked
explicitly as "needs verification" rather than stated as fact.

**Bottom line:** This story has three critical defects that will break things if implemented
literally as written (AC5/AC6 wrong schema qualifier crashes app startup; AC9 migration references
a non-existent column; AC15 targets the wrong file with statuses that don't exist and would break
session planning). Three more ACs (AC3, AC6, AC7, AC11) are chasing bugs that don't exist in the
current code — the premises don't hold up. AC16's ledger tags have three misattributions. Details
below, ordered by AC.

---

## AC1 — SluContributionService: fix code doesn't match actual code shape

**Actual code** (`SluContributionService.java:43-46,62`) does not "cast `row[0]` to UUID" — it does
`UUID.fromString(row[0].toString())`, at **two separate call sites** (once building `coachIds`,
once in the DTO-building loop). The story's fix snippet (`if (!(row[0] instanceof UUID)) throw ...`)
guards a cast that doesn't exist in the code; it needs to instead wrap both `UUID.fromString` calls,
or better, convert once into a `Map<Object, UUID>` up front.

Also: `coach_id` is a native Postgres `UUID` column (`V46__development_module_init.sql:21`), which
the pgjdbc driver returns as a `java.util.UUID` object for native queries in this codebase's
configuration — meaning `row[0].toString()` is already guaranteed to produce a valid UUID string in
practice, and `UUID.fromString` on it can't fail. deferred-work.md's own original D1 entry already
noted this ("unlikely under DB schema constraints but unguarded"). The guard is still reasonable
defense-in-depth, but implementers should know the failure mode this targets is largely
theoretical, not the "500 on every malformed row" risk the AC's phrasing implies.

**"Optimize double iteration"** — the fix says "calculate percentages in-line during DTO building,
not separately," but this isn't achievable without buffering: you cannot compute a coach's
percentage-of-skill-total without first knowing every coach's contribution to that skill, and rows
for a skill are not guaranteed to be fully seen before you need the total unless you either (a) rely
on the query's `ORDER BY skill_code` to group consecutive rows per skill (fragile, implicit
contract) or (b) still do two passes. The AC gives no concrete mechanism; as written, this is not
actionable — flag for the dev to either drop this sub-item or specify the grouping approach
explicitly.

**Severity:** Low. **Action:** Rewrite the fix snippet to match the actual two-call-site shape;
either drop the double-iteration "optimization" or specify how it's achieved without breaking
correctness.

---

## AC2 — ReportGenerationService: the fix introduces a worse bug than the one it targets

**The stated "timeline orphaning" bug does not appear reachable via the current code path.**
`writeTimelineEvent` (`TimelineEventListener.java`) is `@Transactional(REQUIRES_NEW)` — confirmed —
so it does commit independently of the outer `generateReport` transaction. But in
`ReportGenerationService.generateReport` (lines 156-167), the timeline-event call is already wrapped
in its own try/catch that swallows all exceptions, and the only code that runs after it
(`notifyParent`) *also* swallows all its own exceptions internally. There is no code path left in
`generateReport` after a successful timeline-event write that can throw and force the outer
transaction to roll back. The orphaning scenario the AC describes (REQUIRES_NEW commits, then outer
rolls back) doesn't have a live trigger in this method as currently written. (Moderate confidence —
I did not check whether `@RateLimited` AOP or a deferred DB constraint could still abort post-return;
worth a 10-minute check before treating this as load-bearing justification for the rewrite.)

**The proposed fix creates a new, worse race.** Today, S3 upload happens *before* the report row is
saved (`generateReport` lines 132-154) — if the DB save fails, the code explicitly cleans up the
orphan S3 object. Every persisted report row is therefore guaranteed to have a real PDF in storage.
The AC's restructuring inverts this: publish `ReportGeneratedEvent` and commit the report row
*before* the async S3 upload has even been attempted. If the async upload fails, or simply hasn't
run yet, `listReports()` (which calls `fileStorageService.signedDownloadUrl(r.getStorageKey())` for
every persisted report) will hand a parent a signed URL to a PDF that doesn't exist yet — or ever, if
the async step fails and there's no compensating cleanup of the report row shown in the fix. This is
user-visible breakage (a 404 on a "ready" report), not a background inconsistency like the timeline
event case. **This needs a redesign**, not just implementation: at minimum, the report row needs a
status field (e.g., `PENDING_UPLOAD` → `READY`) so `listReports` can hide/report unavailable PDFs
until the async upload actually succeeds.

**Severity:** High (the fix as specified creates user-facing broken links). **Action:** Verify the
orphaning scenario is actually reachable before using it as the rationale; if the async
restructuring proceeds, add a report status/visibility gate so unreachable-PDF rows are never
surfaced to `listReports`.

---

## AC3 — Stale branding logo: targets a method that can't reach the described state

`ReportGenerationService.generateReport` only fetches `branding` when `tier == ACADEMY` (line
120-122: `tier == CoachSubscriptionTier.ACADEMY ? brandingRepository.findById(coachId) :
Optional.empty()`). This means `buildPdf` — the method AC3's fix targets — **never receives branding
data unless the coach is currently ACADEMY tier at the moment of report generation.** By definition,
if `buildPdf` sees a non-empty `branding`, the tier check has already passed. The "downgrade then
re-upgrade reuses a stale key inside buildPdf" scenario the AC describes cannot occur through this
call site — there's no re-upgrade-specific staleness for `buildPdf` to detect, because a downgraded
coach's report generation never reaches the branding-using code at all.

Additionally, `CoachBranding` (`CoachBranding.java`) has no tier field and no history of what tier a
`logoKey` was uploaded under — only `coachId`, `logoKey`, `brandColour`, `updatedAt`. The proposed
`checkCoachLogoIsCurrentlyValid(coachId, logoKey)` ("verify the logo key matches the coach's current
tier") has nothing to compare against except the coach's *current* tier — which, per the point
above, is already guaranteed ACADEMY at the only call site that would invoke this check. The
function as specified would always return true when called from `buildPdf`.

The one place a downgraded coach's branding is actually still reachable is `getBranding(coachId)`
(`ReportGenerationService.java:196-202`), which returns the logo/colour with **no tier check at
all** — a genuinely open gap, but a different method than the one this AC's fix touches.

**Severity:** Medium (the AC targets the wrong method and the fix can't do what it claims to do).
**Action:** Either drop AC3, or retarget it at `getBranding()` and define what "invalid" actually
means for a logoKey (nothing in the current code deletes or invalidates the S3 object on downgrade,
so "staleness" needs a concrete definition before a fix can be written).

---

## AC4 — Minor: existing null-parent-id path not called out

`getParentEmailByPlayerId` → `getParentIdByPlayerId` returns `null` (not an exception) for a
self-registered adult player with no parent (`PlayerProfile.parentId == null`, the same case
`BookingService` handles explicitly at lines 170-176). The current code then calls
`userRepository.findById(null)`, which Spring Data JPA rejects via `Assert.notNull` — an
`IllegalArgumentException`, not a graceful `null`. The AC's testing section does list "player
without parent" as a case to cover, and the proposed single-query JOIN fix happens to sidestep this
(no match → no row → null), so this will likely get fixed as a side effect — but the "Current
behavior" section doesn't name this as the actual defect, so a dev implementing something other than
the exact suggested JOIN could easily reintroduce it. Worth a one-line callout.

**Severity:** Low. **Action:** Add a line noting the self-registered-player (`parentId == null`)
case as the actual defect motivating the fix, not just "TOCTOU."

---

## AC5 / AC6 — Wrong schema qualifier will crash the app; AC6's premise is false

**AC5 will break application startup if implemented literally.** `PlayerSkillStat.java` and
`SluRepository.java` both confirm the table is `development.player_skill_stats` (schema
`development`), not `main`. AC5's proposed `@PostConstruct` validation query is written against
`main.player_skill_stats`:
```java
entityManager.createNativeQuery("SELECT slu_value, calculated_at FROM main.player_skill_stats LIMIT 0")
```
This table does not exist in the `main` schema. If implemented as literally specified, this
`@PostConstruct` method will **always throw**, and per the AC's own code, that throw becomes
`AppSetupException` — which will prevent the application from starting in every environment. This
is the single highest-severity issue in this story.

**AC6 is based on a premise that direct inspection shows is false.** The AC asks the implementer to
"verify whether RadarAssessmentService actually writes to player_skill_stats." It does not: writing
`radar_assessment_entries` rows via `RadarAssessmentRepository` (`RadarAssessmentService.java:87`,
confirmed table `development.radar_assessment_entries` in `V50__radar_assessment_entries.sql`) is a
completely separate table from `player_skill_stats`. Only `SluCalculationService.onBookingCompleted`
writes to `player_skill_stats`, and it does so only for actual completed sessions
(`BookingCompletedEvent`), never for radar assessment submissions. `findLastSessionDate` is already
correctly scoped to session-only SLU rows — there is nothing to filter. This matches
`TimelineQueryService`'s own existing code comment: *"Report generation and radar assessments do NOT
reset the access clock"* — i.e., the invariant the AC worries about violating is already upheld.
deferred-work.md's own source item (R3-D2) hedges this exact point with "(to be confirmed)" — the
re-audit this story claims to have performed should have resolved it as not applicable, but the AC
still asks for a schema change that isn't needed.

**Severity:** Critical (AC5 as written breaks startup); AC6 should be dropped as a false positive.
**Action:** Fix AC5's schema qualifier to `development.player_skill_stats` before any
implementation. Drop AC6 entirely, or replace it with a comment documenting *why* no filtering is
needed (the two tables are already disjoint).

---

## AC7 — Already implemented; the fix restates existing code as if it were missing

`SluCalculationService.java` already captures `Instant now = Instant.now()` **once** (line 153) and
reuses that exact same value both for every `PlayerSkillStat.calculatedAt` (line 167) and for
deriving the ISO week/year passed to `snapshotBatchWriter.writeAll` (lines 182-185). This is
precisely what AC7's "fix" proposes (`Instant processedAt = Instant.now(); ... reuse`). There is no
second, independent timestamp capture to unify — the code already does this.

The AC's stated failure mode — "failure between saveAll and snapshotBatchWriter.writeAll leaves
snapshot stale" — is real as a *reliability* concern, but it's not a timestamp-consistency bug, and
the AC's own proposed fix doesn't address it either (still two separate, unlinked calls with no
retry or transactional coupling between them). This is the same underlying issue already tracked as
`W1`/`W2` in deferred-work.md's skillars-5-2 section ("negligible probability," "eventually
consistent by design").

**Severity:** Low — no code change needed for what AC7 literally describes. **Action:** Drop AC7, or
retarget it explicitly at the saveAll→writeAll failure gap (which needs retry/coupling, not
timestamp changes) and say so.

---

## AC8 — Self-invocation will silently defeat @Retryable; new dependency not flagged

`@Retryable` is a Spring AOP proxy-based annotation, identical in mechanism to `@Transactional`. If
`saveSluWithRetry` is added as a method on `SluCalculationService` and called from
`onBookingCompleted` within the *same class* (`this.saveSluWithRetry(...)` or a bare unqualified
call), the call bypasses the Spring proxy entirely and **the retry logic will never fire** — this is
the exact self-invocation pitfall this codebase is already careful about elsewhere (see
`BookingService.acceptAndInitiatePayment`'s doc comment, and `TimelineEventListener`'s explicit
`@Lazy @Autowired TimelineEventListener self` field used specifically to route through the proxy for
its own `@Async`/`@TransactionalEventListener` methods). The AC's code sample doesn't show which
class `saveSluWithRetry` lives in or how it's invoked, so as given it's likely to be implemented as
a same-class private/direct call and quietly not retry anything.

Also: `spring-retry` does not currently appear as a dependency anywhere in `pom.xml` or the codebase
(no `@EnableRetry`, no existing `@Retryable` usage found). This AC introduces a new third-party
dependency that isn't called out in the story's Dev Notes ("Cross-module coordination" section lists
only AC2 and AC10 as needing coordination) — worth a one-line flag so it doesn't surprise a reviewer.

**Severity:** Medium. **Action:** Specify that the retryable method must live in a separate
`@Component`/bean (not the same class as the caller) and be invoked through injection, not
self-invocation. Note the new `spring-retry` dependency in Dev Notes.

---

## AC9 — Real gap, but the migration SQL references a wrong schema and a wrong column

The underlying gap is real: `V50__radar_assessment_entries.sql` confirms `player_radar_composites`
has a FK on `skill_code` but none on `player_id`. However, AC9's proposed migration:
```sql
ALTER TABLE main.player_radar_composites
ADD CONSTRAINT fk_prc_player_id FOREIGN KEY (player_id) REFERENCES main.player_profiles(player_id) ON DELETE CASCADE;
```
has two concrete errors that will fail at migration time:
1. **Wrong schema**: the table is `development.player_radar_composites`, confirmed by the entity
   (`@Table(schema = "development", name = "player_radar_composites")`) and by `V98` (which already
   does `ALTER TABLE development.player_radar_composites ...`). `main.player_radar_composites` does
   not exist.
2. **Wrong referenced column**: `main.player_profiles`' primary key is `id`, not `player_id`
   (confirmed by the existing FK pattern in `V22__parent_player_shadow_accounts.sql`:
   `REFERENCES main.player_profiles(id)`). `main.player_profiles(player_id)` is not a valid FK
   target — no unique/PK constraint exists on that name.

**Also incomplete in scope:** the sibling table `development.player_radar_baselines`
(`V51__radar_display_correlation.sql`) has the exact same missing-FK problem on `player_id`, and is
explicitly tracked as open in deferred-work.md's skillars-5-4 section ("W1: No FK from
`player_radar_baselines.player_id` ... to `main.player_profiles`"). AC9 only touches composites, not
baselines. If leaving baselines unfixed is intentional (matching AC16 item 4's evident intent to
mark that item "remains open"), the story should say so explicitly — right now it reads as an
oversight rather than a decision. (See AC16 below — the ledger tag for this decision is also
misfiled.)

**Severity:** High (migration will fail as written). **Action:** Correct schema and column name;
state explicitly whether `player_radar_baselines` is in- or out-of-scope for this story.

---

## AC10 — Phase 1 lock example doesn't use this codebase's established lock-retry pattern

The core gap is real and confirmed: `RadarCompositeCalculationService.onRadarEntrySubmitted` has no
locking at all today. But the AC's example —
```java
playerProfileRepository.findByIdForUpdate(playerId);
```
used bare, without a subsequent `entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE)` —
is exactly the pattern this codebase has repeatedly documented as broken elsewhere in extensive
in-code comments (`BookingService.java:244-253`, `:337-349`, `:675-680`; `PlaybackService.java:130-136`):
when a JPQL locked query returns an entity **already present in the persistence context** (loaded
earlier via an unlocked read), Hibernate returns the existing in-memory instance without
re-reading it — so a bare `findByIdForUpdate` call can silently take the DB lock without ever
refreshing the values you then read. Every other pessimistic-lock call site in this codebase pairs
`lockRetryer.withBoundedRetry(...)` with an explicit `entityManager.refresh(...)`. AC10's example
should either use the same `PessimisticLockRetryer` + refresh pattern for consistency and to avoid
lock-wait-timeout handling being reinvented, or explicitly justify why it's safe to omit here (e.g.,
if this is guaranteed to be a fresh transaction/persistence context with no prior read of the same
row — plausible but not stated).

**Severity:** Medium. **Action:** Point the implementer at the existing `PessimisticLockRetryer`
pattern used in `BookingService`/`PlaybackService` rather than a bare repository call.

---

## AC11 — Already correct, already reviewed and accepted; no bug to fix

`VideoAccessCache` is annotated `@RequestScope` (Spring's standard request-scope annotation, default
`ScopedProxyMode.TARGET_CLASS`), which is precisely the mechanism that makes request-scoped-bean
injection into a singleton (`VideoAccessGuard`) safe — Spring auto-generates a CGLIB proxy that
transparently delegates to the real per-request instance. This is standard, correct Spring practice,
not a workaround needing "fixing." deferred-work.md's own source item (W1, skillars-6-5 section)
already says exactly this: *"standard Spring proxy pattern; correct in web context; only fails in
bare non-web unit tests (mocked there anyway)."* That's a description of expected, already-mitigated
test-setup friction, not a production defect. Implementing AC11's proposed
`ApplicationContext.getBean()` lookup would be a step backwards (loses Spring's built-in proxy
caching/thread-safety guarantees for no benefit) to solve a problem that's already solved.

**Severity:** N/A — false positive. **Action:** Drop AC11.

---

## AC12 — Proposed fix bypasses the real deletion pipeline (outbox, audit log, event)

The gap is real, and actually broader than described: today, `cascadeDeleteForAccount` calls
`videoQuotaRepository.resetBytesForOwner(ownerId)` **unconditionally after the loop**, even when
`failedIds` is non-empty (line 170) — so *any* single video deletion failure (not just a JVM crash
mid-way, which is what the AC's "Current behavior" frames as the trigger) already zeroes the quota
while a failed video's row and its accounted storage remain. This is a more mundane and more likely
trigger than the AC describes.

**The proposed fix is unsafe as written.** It replaces the per-video `deleteVideo(...)` calls with:
```java
videoRepository.deleteAllByOwnerId(accountId);
quotaRepository.resetBytesForOwner(accountId);
```
But `deleteVideo()` (the method actually used today, `VideoDeletionService.java:52-91`) is not a row
delete — it's a soft-delete state transition that also (a) writes a `VideoDeletionLog` audit row per
video, (b) inserts a `VideoDeletionOutbox` row that the async processor uses to call the Bunny.net
provider's `deleteAsset()` for the real remote asset, and (c) publishes `VideoPurgedEvent`
after-commit. A raw `deleteAllByOwnerId` batch DELETE skips **all three** — meaning the external
video-hosting assets would never get cleaned up (no outbox row created), there'd be no deletion
audit trail, and no downstream listeners of `VideoPurgedEvent` would fire. This isn't a minor
implementation detail; implementing AC12 as literally specified would silently orphan every video's
remote Bunny.net asset on account deletion.

**Severity:** High (fix as written breaks the external-asset cleanup pipeline). **Action:** Rewrite
the fix around the existing `deleteVideo()` per-video call (keep that), and instead make the
**quota reset conditional on `failedIds.isEmpty()`** (or otherwise reconcile it against actual
un-deleted videos), not a single global re-transaction replacing the whole method.

---

## AC13 — Race window may already be closed by the existing PURGED check; needs verification

`VideoDeletionService.deleteVideo` sets `operationalState = PURGED` synchronously in the same
transaction that starts the deletion; `providerAssetId` is explicitly **not** nulled at that point —
per its own comment, it's nulled later, "in `completeRowWithNullAsset()` after confirmed deletion"
(a later, asynchronous outbox-processing step). `PlaybackService.authorizePlayback` already
re-reads the video's `operationalState` fresh via its own `findById` (line 67-68) and rejects
`PURGED`/`DELETED` before ever calling `generatePlaybackUrl` with `providerAssetId` (lines 70-101).
Since `providerAssetId` only ever goes null strictly *after* `operationalState` becomes `PURGED`
(never independently or before), a null `providerAssetId` implies `PURGED` was already set — and
that state is already caught by the existing ineligibility check before `generatePlaybackUrl` is
reached. I could not find a code path where `providerAssetId` is null while `operationalState` is
still eligible. (I did not read the outbox-processor / `completeRowWithNullAsset` implementation
directly — flagging this as needing five minutes of verification, not asserting it as certainly
dead code.) deferred-work.md's own source item (W10) already assessed this as "very low probability
in practice."

**Severity:** Low, pending verification. **Action:** Before implementing, confirm
`completeRowWithNullAsset()`'s ordering relative to `operationalState`; if it confirms the ordering
above, this AC adds defensive code for an already-unreachable path — fine to keep as belt-and-braces
but shouldn't be billed as closing a live race.

---

## AC14 — Proposed primary fix is not valid Spring/JPA; understates existing protection

**`@Lock(LockModeType.PESSIMISTIC_WRITE)` is a Spring Data JPA repository-interface annotation** —
it only has meaning on a method declared in a `JpaRepository`/`Repository` interface, where Spring
Data generates the query implementation and can attach a lock hint to it. It has no effect placed on
an arbitrary service class method. Compounding this, the AC's example puts it on a **private**
method (`private void transitionInternal(...)`) — even if `@Lock` worked outside a repository, AOP
proxying (which is what would need to intercept the call) cannot apply to private methods regardless
of framework, for the same reason `@Transactional` doesn't work on private methods in this codebase.
As written, this primary suggestion will not compile with the intended semantics, or will silently
no-op. The AC's own alternative #2 — take the lock at the call site via
`bookingRepository.findByIdForUpdate(bookingId)` (matching the `PessimisticLockRetryer` +
`entityManager.refresh(...)` pattern already used everywhere else in this exact file) — is the only
viable option and should be the one specified, not offered as an equal alternative to an invalid one.

**The "Current behavior" framing overstates the actual exposure.** `Booking` already carries
`@Version` (confirmed, `Booking.java:54`) — optimistic locking is active — and the majority of
`transition()`/`transitionInternal()` call sites in `BookingService.java` already wrap their calls
in `try { ... } catch (OptimisticLockingFailureException e) { throw new
OperationNotAllowedException(..., BookingError.CONCURRENT_MODIFICATION); }` (visible at
`declineBooking`, `cancelDueToPause`, `cancelBookingAsCoach`, `recordNoShowPlayer`,
`recordNoShowCoach`, and `acceptBooking`'s wrapping of `acceptAndInitiatePayment`). Concurrent
callers do not silently corrupt booking state today — the loser of a race gets a clean, already-
handled 409-equivalent retry error. What pessimistic locking changes is UX (blocking/waiting instead
of fail-fast-and-retry), not "prevents corruption that currently happens" — that's a real
consideration the story should weigh explicitly rather than imply the current state is unguarded.

**Severity:** Medium-High (invalid primary fix code; overstated risk framing). **Action:** Specify
only the call-site-lock approach (matching this file's established pattern); reframe "Current
behavior" to acknowledge existing `@Version`-based protection and state the actual motivation
(fail-fast-with-retry vs. block-and-wait) rather than implying unguarded corruption.

---

## AC15 — Wrong file, wrong module, and the fix breaks working functionality

This is the most significant defect in the story.

**`isBookingPlannable` is not in `BookingService`.** It doesn't exist anywhere in the `booking`
module. It exists as two **separate, independent private methods** — one in
`SessionPlanService.java:207` and one in `SessionTemplateService.java:169` (both in the `session`
module) — each with the identical body:
```java
private boolean isBookingPlannable(String status) {
    return "CONFIRMED".equals(status) || "UPCOMING".equals(status);
}
```
deferred-work.md's own source item for this (W8, filed under the skillars-4-4/4-5 session-builder
review sections) correctly attributes it to `SessionPlanService.java:167`, not `BookingService`. The
story's AC15 header ("BookingService.isBookingPlannable status guard (Booking/Availability)") and
its ledger tag in AC16 ("Section on BookingService: — W8 (isBookingPlannable UPCOMING status)") are
both wrong about the location.

**The proposed fix's status set doesn't match reality and would break session planning for
confirmed bookings.** The AC's fix code:
```java
Set<String> supportedStates = Set.of("ACTIVE", "PENDING");
```
`"ACTIVE"` and `"PENDING"` are not booking statuses used anywhere in this codebase.
`BookingService.ACTIVE_SLOT_STATUSES` (the authoritative list of live booking statuses) is
`REQUESTED, ACCEPTED, PAYMENT_PENDING, CONFIRMED, UPCOMING, IN_PROGRESS, PAUSED` — neither `ACTIVE`
nor `PENDING` appears in it. The actual current, working, correct statuses accepted by
`isBookingPlannable` are `CONFIRMED` and `UPCOMING`. If the AC's fix is implemented literally, the
guard would return `false` for `"CONFIRMED"` — the status coaches are actually in when building a
session plan for a confirmed booking today — **breaking session-plan and session-template creation
for every confirmed booking**, not just guarding against the hypothetical unused `UPCOMING` state.

**The fix also doesn't account for the duplication.** Two independent copies of this method exist;
a fix needs to update both (or, better, deduplicate them into one shared location) — the AC's
single-method code sample gives no indication a second copy exists.

**Severity:** Critical (implementing literally as specified breaks live functionality).
**Action:** Retarget this AC at `SessionPlanService.java` and `SessionTemplateService.java`
(session module, not booking). If a guard against `UPCOMING` is still wanted, the correct
"supported" set is `Set.of("CONFIRMED")` (rejecting `UPCOMING` specifically, since that's the status
with no transition path — matching the AC's own stated rationale), not `Set.of("ACTIVE",
"PENDING")`. Address both duplicate copies.

---

## AC16 — Ledger tags: three misattributions

1. **Item 4 targets the wrong deferred-work.md section.** The AC says: *"Section 'Deferred from:
   code review of skillars-5-1...' — W1 (no FK on radar baselines): append `[AUDIT ...]`"*. But the
   W1 item under the **skillars-5-1** section is about negative `SluFormula` metadata fields (already
   tagged `[CLOSED by skillars-deferred-76 AC10]`) — an unrelated, already-closed item. The actual
   "no FK from `player_radar_baselines.player_id`" item is **W1 under the skillars-5-4 section**
   (`## Deferred from: code review of skillars-5-4-skills-radar-display-development-correlation`).
   Applying this AC's note to skillars-5-1's W1 would attach a nonsensical annotation to an unrelated,
   already-resolved entry, while leaving the correct item (5-4's W1) untouched.

2. **Item 6 is incomplete.** It tags only `D6` (skillars-5-2 "Round 2 Group A" section) for the ISO
   week boundary race. But the *same* underlying concern is independently tracked twice more in the
   base skillars-5-2 section: `W1` ("Partial snapshot missing if failure occurs between
   sluRepository.saveAll and snapshotBatchWriter.writeAll") and `W2` ("SluCalculationService
   week-boundary race — now captured before saveAll..."). If AC7 is kept (see above — recommend
   dropping it), all three should be resolved together or the ledger will show the issue as both
   "closed" (D6) and still-open (W1/W2) simultaneously.

3. **Item 10's first sub-item is filed under the wrong module entirely.** As established in AC15
   above, "isBookingPlannable" is not in `BookingService` or any BookingService-titled deferred
   section — its actual entry (W8) is filed under the skillars-4-4/4-5 session-builder/session-
   templates review sections. Tagging a "Section on BookingService" that doesn't contain this item
   will either silently fail to find a match or get attached to the wrong text during manual editing.

**Severity:** Medium (ledger corruption, not runtime-breaking, but defeats the purpose of the
ledger — future re-audits will trust these tags). **Action:** Correct all three section references
before editing deferred-work.md.

---

## Summary table

| AC | Verdict | Severity |
|----|---------|----------|
| AC1 | Valid but fix snippet mismatched to real code shape; "optimize" sub-item underspecified | Low |
| AC2 | Orphaning premise likely unreachable (verify); proposed fix creates a new user-facing broken-link race | High |
| AC3 | False premise — `buildPdf` is gated so it can never see a stale-tier logo; targets wrong method | Medium |
| AC4 | Valid; missing root-cause callout (null parentId) | Low |
| AC5 | **Wrong schema qualifier will crash app startup as written** | **Critical** |
| AC6 | **False premise** — RadarAssessmentService doesn't write player_skill_stats; drop | N/A (false positive) |
| AC7 | **Already implemented** — code already reuses one timestamp; drop or retarget | N/A (false positive) |
| AC8 | Valid gap; self-invocation will silently defeat @Retryable; new dependency not flagged | Medium |
| AC9 | Valid gap; migration SQL has wrong schema + wrong FK target column; scope excludes sibling table | High |
| AC10 | Valid gap; example lock code omits this codebase's required refresh pattern | Medium |
| AC11 | **False positive** — current design is correct, standard Spring, already reviewed/accepted; drop | N/A (false positive) |
| AC12 | Valid gap (broader than described); proposed fix bypasses outbox/audit-log/event pipeline | High |
| AC13 | Likely already covered by existing PURGED check (verify); low value if so | Low |
| AC14 | Valid gap; primary fix code is invalid Spring/JPA; risk framing overstates lack of protection | Medium-High |
| AC15 | **Wrong file/module; fix uses non-existent statuses and would break confirmed-booking session planning; misses a duplicate copy** | **Critical** |
| AC16 | Three section misattributions (items 4, 6, 10) | Medium |
