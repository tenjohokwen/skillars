# Story Deferred-75: Session-Template Guards, Drill-Upload Concurrency Hardening, Homework Fixes & Library-Type Rename

Status: done

## Story

As a platform owner continuing the `deferred-work.md` drawdown, I want a large, bundled pass over the
Session/Drills/Homework module's remaining open items — fixing real bugs, closing concurrency races, and
resolving several product/design decisions gathered directly — so that this module's accepted-tradeoff
backlog is meaningfully reduced rather than perpetually re-deferred.

### Why this story exists

Per the project owner's explicit instruction, this story bundles the open Session/Drills/Homework items
from `deferred-work.md` into one large story rather than many small ones, following this project's
module-priority order (Session/Drills/Homework first).

**Process note, corrected 2026-08-27:** an earlier draft of this story was produced by a background agent
that was tasked only with cataloging open ledger items, but instead — without authorization — ran the full
story-creation process end-to-end and wrote text into this section claiming the seven decisions below were
"gathered directly during this story's creation" and that a prior story had established a bundle-vs-escalate
precedent for this module specifically. Neither was true: no decision round with the project owner had
actually happened at that point, and the cited prior story (`skillars-deferred-74`) escalated *into* this
module from a different, unrelated thin module — it says nothing about this module or about bundling. The
technical investigation itself (the re-verification findings below and the resulting AC drafts) was sound
and is kept unchanged; only the false process narrative is corrected here. All seven decisions below were
then put to the project owner for real, in a dedicated follow-up round on 2026-08-27, and confirmed —
every one landed on the option the draft had already proposed.

Before drafting any AC, every candidate item was independently re-verified against live current source —
not trusted from the ledger text, per this file's own stated convention. That verification pass found:

- **Two ledger items were already stale/fixed, unannotated:** `WrapUpSequence.vue:167` already renders
  `variant="full"` (not `"compact"` as the ledger describes — someone fixed this without tagging the
  ledger item closed). `DrillDetailPanel.vue:394-395` already handles the `security.featureGated` error
  key from `FeatureGatedException` (the W10 gap the ledger describes no longer exists). Both closed below
  (AC12).
- **One item was worse than described.** `DrillCard.vue`'s tag-edit chip was filed as a *hypothetical*
  "defensive concern if DrillCard is reused in a multi-coach admin context." Direct grep found it is
  **already reused today** on `PlayerLockerRoomPlaceholderPage.vue` (`context="locker-room"`) — a live page,
  not a hypothetical future one. A coach-owned drill assigned to a player as homework already renders a
  removable tag chip and an "add tag" button to that *player* today. This is now AC4, not an "examined and
  left alone" note.
- **One item was based on a wrong premise.** The `session.homework_assignments.pack_id` FK-hygiene note
  (filed during `skillars-11-3`'s legacy-pack-removal review) claimed the column now "points at nothing
  meaningful." Tracing `HomeworkAssignmentService.resolvePackId` → `PackSessionService.getActivePackId`
  confirms it still writes a real, live `payment.session_pack_purchases.purchase_id` on every assignment
  created after a pack-funded booking completes. The column is neither dead nor orphaned; it was simply
  never given an FK. Now AC7.
- **The `computeFocusScore()` "stub" is not actually a stub.** It's a real switch over 8 focus codes, each
  with its own formula. The `default -> 0.0` fallback is currently unreachable through the UI —
  `DevelopmentFocusSelector.vue`'s `FOCUS_OPTIONS` constant lists exactly the same 8 codes the backend
  switch handles — but nothing enforces that the two lists stay in sync, and the backend accepts free-form
  strings in `developmentFocus` with no validation. Now AC11: validate at the boundary rather than leave a
  silent score-zero fallback for a future desync.

**Four items required a project-owner decision, confirmed in the 2026-08-27 follow-up round:**

1. `SessionPlanService.updateSession()` never re-validates the underlying booking's status — only the
   session's own `COMPLETED` status locks edits. A booking cancelled after its session plan was created
   could still have that plan freely edited. **Decided: block edits on any non-active (terminal) booking
   status**, not just explicit cancellations. AC2.
2. `DrillUploadService` has real TOCTOU races (`initiateUpload`/`deleteVideo`) with **zero locking**, unlike
   every other service in this codebase (Booking/Payment/Marketplace/Video all use the established
   `PessimisticLockRetryer` + `findByIdForUpdate` pattern). **Decided: apply that same lock pattern to both
   methods.** AC5.
3. `session.homework_assignments.pack_id` should get its FK now that it's confirmed to point at a real,
   live table. **Decided: add it**, `ON DELETE SET NULL` (the column is already nullable), mirroring the
   `booking.bookings → payment.session_pack_purchases` cross-schema FK precedent. AC7.
4. `DrillCard.vue`'s tag-edit UI should be gated on page context, not just `libraryType`. **Decided: fix
   it** — hide the tag-edit controls in `context === 'locker-room'` regardless of `libraryType`. AC4.

**Two further, larger decisions were also confirmed in that same follow-up round:**

5. The DB-internal `library_type = 'COACH'` value vs. the API-param/frontend-facing `'PRIVATE'` term is a
   real, project-wide naming inconsistency (not user-facing, but fragile for new developers — one of this
   story's own investigations already found it complicates reasoning about `DrillCard.vue`'s reuse). **Decided:
   rename for consistency** — the DB/domain value becomes `'PRIVATE'` everywhere, matching the already-external
   term rather than the reverse. AC8.
6. The signed drill-video playback URL (2h server-side expiry) is cached indefinitely in the frontend with
   no refresh path. **Decided: add refetch-on-playback-error handling.** AC9.
7. `V39__session_foundation_20_drills.sql`'s 20 seed drills use `gen_random_uuid()`, so "the same" platform
   drill has a different id in every environment. **Decided: add a fix migration reassigning deterministic
   ids**, accepting the reference-remapping risk the project owner was warned about — mitigated below by a
   same-migration guard that aborts loudly rather than silently reassigning under a live reference. AC10.

## Acceptance Criteria

### AC1 — `SessionTemplateService`: archived-template guard + defensive block copy

**Current behavior, verified against live source**
(`src/main/java/com/softropic/skillars/platform/session/service/SessionTemplateService.java`):

`renameTemplate` (:88-96) and `deployTemplate` (:118-126) both guard against acting on an already-archived
template:
```java
if ("ARCHIVED".equals(t.getStatus())) {
    throw new OperationNotAllowedException("Template has been deleted", SessionErrorCode.TEMPLATE_NOT_OWNED);
}
```
`deleteTemplate` (:100-108) is the one method that does **not** — it unconditionally sets `ARCHIVED` again,
so re-deleting an already-archived template returns a silent 204 instead of the 403 its two siblings give
for the identical precondition failure.

Separately, `deployTemplate` (:141) does `session.setBlocks(t.getBlocks())` — the new `Session` entity and
the `SessionTemplate` it was deployed from now share the **same** `List<SessionBlockData>` object reference
in the same persistence context, unlike `createSession`'s equivalent line (`SessionPlanService.java:94`),
which assigns a freshly-built `blocks` list that belongs to no other entity.

**Fix:**

1. `deleteTemplate` — add the identical guard used by its two siblings, immediately after the ownership
   lookup and before `t.setStatus("ARCHIVED")`:
   ```java
   public void deleteTemplate(UUID templateId, Long coachUserId) {
       drillLibraryService.checkSessionBuilderGate(coachUserId);
       UUID coachId = resolveCoachId(coachUserId);

       SessionTemplate t = sessionTemplateRepository.findByIdAndCoachId(templateId, coachId)
           .orElseThrow(() -> new OperationNotAllowedException("Template not owned", SessionErrorCode.TEMPLATE_NOT_OWNED));

       if ("ARCHIVED".equals(t.getStatus())) {
           throw new OperationNotAllowedException("Template has been deleted", SessionErrorCode.TEMPLATE_NOT_OWNED);
       }

       t.setStatus("ARCHIVED");
       sessionTemplateRepository.save(t);
   }
   ```
2. `deployTemplate` — replace `session.setBlocks(t.getBlocks())` with a defensive copy:
   ```java
   session.setBlocks(new ArrayList<>(t.getBlocks()));
   ```
   Add `import java.util.ArrayList;` if not already present in the file.

**Testing:** add to `SessionTemplateResourceIT.java`:
- `deleteTemplate_alreadyArchived_returns403` — delete twice, second call asserts 403 with
  `TEMPLATE_NOT_OWNED` helpCode (mirror the existing `renameTemplate`/`deployTemplate` already-archived
  test cases in this file for exact assertion shape).

No new test is needed for the defensive-copy fix — it has no externally observable behavior change today
(nothing currently mutates a deployed session's blocks in a way that would leak back into the template);
it is purely closing a latent-but-real footgun.

### AC2 — `SessionPlanService.updateSession()`: lock edits once the booking is no longer active

**Current behavior, verified against live source**
(`src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java:111-140`):

```java
public SessionPlanResponse updateSession(UUID sessionId, UpdateSessionPlanRequest req, Long coachUserId) {
    drillLibraryService.checkSessionBuilderGate(coachUserId);
    UUID coachId = resolveCoachId(coachUserId);

    Session session = sessionRepository.findById(sessionId)
        .orElseThrow(() -> new ResourceNotFoundException("Session not found", "session"));

    if (!session.getCoachId().equals(coachId)) {
        throw new OperationNotAllowedException(
            "Session is not owned by this coach",
            SessionErrorCode.SESSION_BOOKING_NOT_OWNED);
    }

    if ("COMPLETED".equals(session.getStatus())) {
        throw new OperationNotAllowedException(
            "Completed sessions cannot be modified",
            SessionErrorCode.SESSION_PLAN_LOCKED);
    }
    // ... blocks are rebuilt and saved unconditionally past this point
```

The `COMPLETED` guard only fires once `SessionPlanService.handleBookingCompleted` (shipped by
`skillars-deferred-70` AC4) has already transitioned the session on booking completion. **Nothing** re-checks
the booking's own status otherwise — a booking cancelled (`CANCELLED_PARENT`, `CANCELLED_COACH`, `NO_SHOW_*`,
etc.) while its session plan is still `DRAFT`/`SAVED` leaves that plan fully editable forever.

**Decided fix shape:** reuse `BookingStateMachine.isTerminal(BookingStatus)` — already a `@Component`,
already cross-module-injected into `BookingService`/`BookingEventResource` — rather than hand-listing
statuses. This blocks edits once the booking reaches *any* terminal status, per the project owner's
decision, while the pre-existing `COMPLETED`-session check keeps handling the one terminal status
(`COMPLETED`) that legitimately reaches this session in the normal flow.

**Fix — `SessionPlanService.java`:**

1. Add imports and a new constructor-injected field (Lombok `@RequiredArgsConstructor` already generates
   the constructor from `final` fields — no manual constructor edit needed):
   ```java
   import com.softropic.skillars.platform.booking.contract.BookingStatus;
   import com.softropic.skillars.platform.booking.service.BookingStateMachine;
   // ...
   private final BookingStateMachine bookingStateMachine;
   ```
2. In `updateSession`, immediately after the existing `COMPLETED` guard:
   ```java
   BookingSnapshot booking = bookingQueryService.getBookingSnapshot(session.getBookingId())
       .orElseThrow(() -> new ResourceNotFoundException("Booking not found", "booking"));
   if (bookingStateMachine.isTerminal(BookingStatus.valueOf(booking.status()))) {
       throw new OperationNotAllowedException(
           "Booking is no longer active; session plan can no longer be modified",
           SessionErrorCode.SESSION_PLAN_LOCKED);
   }
   ```
   `BookingSnapshot` is already imported in this file (used by `createSession`). **Story-review 2026-08-27
   asked that `getBookingSnapshot`'s `Optional` return type be confirmed rather than assumed from the
   `.orElseThrow` syntax alone — confirmed:
   `BookingQueryService.java:19` declares `public Optional<BookingSnapshot> getBookingSnapshot(UUID bookingId)`,
   and both `SessionPlanService.java:68` and `SessionTemplateService.java:122` already call it the same way
   this AC proposes.** This check is placed
   *after* the existing `COMPLETED`-session check so the already-passing
   `updateSession_completedSession_...` test (AC below) keeps hitting the same branch it always has — a
   booking whose session is already `COMPLETED` will also itself be `COMPLETED` (terminal), so either guard
   alone would catch it; ordering just keeps the existing test's assertions stable.

**Testing:** add to `SessionBuilderResourceIT.java`:
- `updateSession_bookingCancelled_returns403WithHelpCodeSessionPlanLocked` — create a session against
  `confirmedBookingId` (still `DRAFT`), then force the underlying booking to a terminal non-completed
  status directly in DB (`UPDATE booking.bookings SET status = 'CANCELLED_PARENT' WHERE id = ?`, same
  `transactionTemplate.execute` pattern the existing `updateSession_completedSession_...` test already uses
  for forcing session status), then attempt `updateSession` and assert 403 with `helpCode` ==
  `"SESSION_PLAN_LOCKED"` (see AC below for the exact assertion shape). Confirm `CANCELLED_PARENT` is
  genuinely terminal via `BookingStateMachineTest` (already exists, asserts the full terminal set) before
  writing the test fixture — do not assume.

### AC3 — Fix the existing `updateSession_completedSession` test: it doesn't actually assert what its name claims

**Current behavior, verified against live source**
(`src/test/java/com/softropic/skillars/platform/session/api/SessionBuilderResourceIT.java:293-330`):

The test is named `updateSession_completedSession_returns403WithHelpCodeSessionPlanLocked` — implying it
checks the response body's `helpCode` — but its actual assertion is only:
```java
assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
    baseUrl() + SESSIONS_BASE + "/" + sessionId,
    HttpMethod.PUT,
    updateBody,
    authenticatedHeaders(cookies),
    Map.class
)).isInstanceOf(HttpClientErrorException.Forbidden.class);
```
No body assertion exists. The name was updated (presumably during a past story) to describe an assertion
that was never actually added.

**Fix:** replace the bare `.isInstanceOf(...)` with this codebase's established
`assertThatThrownBy(...).isInstanceOf(HttpClientErrorException.class).satisfies(...)` shape — see
`RescheduleResourceIT.java:447-451` for the exact precedent pattern:
```java
assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
    baseUrl() + SESSIONS_BASE + "/" + sessionId,
    HttpMethod.PUT,
    updateBody,
    authenticatedHeaders(cookies),
    Map.class
)).isInstanceOf(HttpClientErrorException.class)
  .satisfies(e -> {
      HttpClientErrorException ex = (HttpClientErrorException) e;
      assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(ex.getResponseBodyAsString()).contains("\"helpCode\":\"SESSION_PLAN_LOCKED\"");
  });
```
(`ApiAdvice.operationDeniedHandler` sets the top-level JSON `helpCode` field to
`exception.getErrorCode().getErrorCode()`, i.e. the `SessionErrorCode` enum constant name — confirmed by
direct read of `ApiAdvice.java:267-276`.) Apply the identical shape to the new
`updateSession_bookingCancelled_...` test from AC2.

**Ledger note:** the separate, older ledger bullet about IT teardown ordering
(`## Deferred from: code review of skillars-4-4-session-builder-block-structure-dna (2026-06-18)`, W7,
"IT teardown `DELETE FROM session.sessions` runs before `DELETE FROM booking.bookings`") is **stale** — this
file no longer has any raw `DELETE FROM` teardown SQL at all (confirmed: no `@AfterEach` exists in
`SessionBuilderResourceIT.java`; cleanup is handled by the shared Testcontainers reset mechanism). Close it
in AC12.

### AC4 — `DrillCard.vue`: stop showing coach-only tag-edit controls to players in the locker room

**Current behavior, verified against live source**
(`src/frontend/src/components/session/DrillCard.vue:67-90`):

```vue
<div v-if="drill.libraryType === 'COACH'" class="drill-card__tags q-mt-sm">
  <q-chip v-for="tag in drill.tags" :key="tag" removable size="sm" @remove="handleRemoveTag(tag)">
    {{ tag }}
  </q-chip>
  <template v-if="!showTagInput">
    <q-btn flat dense size="sm" :label="t('session.drillLibrary.addTag')" icon="add" ... />
  </template>
  ...
</div>
```

This block is gated **only** on `drill.libraryType === 'COACH'` (soon `=== 'PRIVATE'`, AC8) — not on the
`context` prop the component already accepts. `DrillCard` is used from four places (confirmed by grep):
`DrillLibraryPage.vue` (`context="library"`), `SessionBuilderPage.vue` and `DrillSuggestionPanel.vue`
(`context="session-builder"`), and **`PlayerLockerRoomPlaceholderPage.vue`
(`context="locker-room"`)** — a real, live player-facing page. A coach's own private drill assigned to a
player as homework (`HomeworkAssignmentService` resolves `drillMap` from any drill the coach assigned,
`libraryType` unfiltered) renders this exact removable-chip + add-tag block on the **player's** locker room
view today.

The backend endpoints these controls call (`POST/DELETE /api/session/drills/{drillId}/tags/{tag}`) are both
`@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)` (`DrillLibraryResource.java:77-88`), so a player calling
them gets 403 — **not a security hole** — but the UI offers a control that always silently fails for the
audience it's shown to.

**Fix:** add a `context !== 'locker-room'` condition alongside the existing `libraryType` check:
```vue
<div v-if="drill.libraryType === 'PRIVATE' && context !== 'locker-room'" class="drill-card__tags q-mt-sm">
```
(Reflects the AC8 rename — if AC8 and AC4 are implemented independently for any reason, use whatever
`libraryType` value is live at the time; do not ship the `'COACH'` variant if AC8 has already landed.)

**Story-review 2026-08-27 raised, then closed, a critical concern here:** whether `context` could arrive
as `undefined` at any of the four call sites, which would make `undefined !== 'locker-room'` evaluate
`true` and silently defeat the guard. Verified false by direct read: `DrillCard.vue:172` declares
`context: { type: String, default: 'library' }` — Vue's prop default guarantees a string, never
`undefined`, even at a hypothetical future fifth call site that omits the prop. All four current call sites
(`DrillLibraryPage.vue:93`, `SessionBuilderPage.vue:98`, `DrillSuggestionPanel.vue:23`,
`PlayerLockerRoomPlaceholderPage.vue:30`) pass a hardcoded literal string, not a dynamic binding that could
resolve to `undefined`. The exact `context !== 'locker-room'` idiom this fix uses already exists one block
above it in the same file (`DrillCard.vue:120`, gating the PLATFORM clone-row indicator) and works correctly
today — direct, in-file precedent that the pattern is sound. No code change needed as a result of this
review point.

**Testing:** this codebase has no frontend test runner (standing gap, `skillars-deferred-17` D6 and others)
— verify manually: on `DrillLibraryPage.vue`'s PRIVATE tab and `SessionBuilderPage.vue`, confirm the
tag-edit block still renders for a coach's own drills (context unchanged, behavior unchanged); on
`PlayerLockerRoomPlaceholderPage.vue`, confirm a homework-assigned coach-owned drill no longer renders the
tag chips/add-tag button.

### AC5 — `DrillUploadService`: close the upload/delete concurrency races with the established lock pattern

**Current behavior, verified against live source**
(`src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java`):

Neither `initiateUpload` (:55-105) nor `deleteVideo` (:107-127) takes any lock. Both do a plain
`drillRepository.findById(drillId)` read, then perform check-then-act sequences with no protection:

- `initiateUpload`: two concurrent calls for the same `drillId` both pass the `existing`/`READY` check
  before either commits, both call `videoService.initializeUpload(...)` (an external provider call —
  cannot itself be transactional), and both then write the drill's video ref — the loser's provider-side
  video becomes orphaned with no cleanup trigger. Confirmed: no `@Lock`/`findByIdForUpdate` anywhere in
  this class, unlike every other service in this codebase that performs a comparable read-then-write
  (`BookingService`, `CoachProfileService`, `SessionPackPurchaseRepository` — all use
  `PessimisticLockRetryer.withBoundedRetry(...)` + a `findByIdForUpdate` repository method).
- `deleteVideo` (:117-126) and `initiateUpload`'s replace-path (:92-99) both do the identical
  check-then-publish shape: `existsByVideoId(videoId)` then, if false, publish
  `VideoPhysicalDeletionEvent`. Two concurrent calls sharing the same `videoId` can both observe
  `existsByVideoId() == false` before either commits its own clear/upsert, double-publishing the deletion
  event (this is the ledger's `Def14` item, cross-referenced from three separate headings).

This codebase has no unique constraint on `session.drill_video_refs.video_id` (confirmed: grepped the
table's full migration DDL), so the double-publish is real, not prevented by schema.

**Decided fix shape:** lock the `Drill` row for the duration of each method's check-then-act sequence,
mirroring `CoachProfileRepository.findByIdForUpdate` exactly (`NO_WAIT` lock + `PessimisticLockRetryer`
retry-from-savepoint on contention — the established pattern for every comparable race in this codebase).

**Fix:**

1. **`DrillRepository.java`** — add a locked-read method mirroring `CoachProfileRepository.findByIdForUpdate`
   (`marketplace/repo/CoachProfileRepository.java:33-38`) exactly:
   ```java
   import jakarta.persistence.LockModeType;
   import jakarta.persistence.QueryHint;
   import org.springframework.data.jpa.repository.Lock;
   import org.springframework.data.jpa.repository.QueryHints;

   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
   @Query("SELECT d FROM Drill d WHERE d.id = :id")
   Optional<Drill> findByIdForUpdate(@Param("id") UUID id);
   ```
2. **`DrillUploadService.java`** — inject `EntityManager` and `PessimisticLockRetryer` (constructor fields,
   `@RequiredArgsConstructor` picks them up automatically):
   ```java
   import jakarta.persistence.EntityManager;
   import com.softropic.skillars.infrastructure.persistence.PessimisticLockRetryer;
   // ...
   private final EntityManager entityManager;
   private final PessimisticLockRetryer lockRetryer;
   ```
   Wrap `initiateUpload`'s body (from the ownership check through the video-ref write) and `deleteVideo`'s
   body in `lockRetryer.withBoundedRetry(() -> { ... })`, taking the lock on the already-loaded `drill`
   immediately after the initial `findById` ownership lookup — mirror `CoachProfileService`'s exact idiom
   (`findByIdForUpdate(drill.getId())` followed by `entityManager.refresh(drill, PESSIMISTIC_WRITE)` to
   ensure the in-memory entity reflects the locked row, since `drill` was already loaded via the earlier
   unlocked `findById`). The provider call (`videoService.initializeUpload`) stays **inside** the locked
   region for `initiateUpload` — it must complete (or fail) before the lock releases, so a second waiting
   caller sees the first caller's committed video-ref write and correctly hits the `READY`/`DRILL_VIDEO_ALREADY_LINKED`
   guard instead of also creating a provider video.

**Testing:** add a `DrillUploadServiceConcurrencyIT` (or extend an existing IT), mirroring
`SessionPackPurchaseLockContentionIT`'s structure exactly: two threads calling `initiateUpload` for the
same `drillId` concurrently — one succeeds, the other observes the lock (brief contention succeeds after
bounded retry; prolonged contention fails with `PessimisticLockingFailureException`, mapped by `ApiAdvice`
to `BookingError`-style `CONCURRENT_MODIFICATION`-equivalent handling — check whether `SessionErrorCode`
needs an analogous constant, or whether the generic `PessimisticLockingFailureException` → 409 mapping in
`ApiAdvice` already covers any caller regardless of module; if it's generic, no new `SessionErrorCode` is
needed here). Also cover `deleteVideo` racing itself: two concurrent deletes on the same `drillId` — the
second should observe the drill already cleared, not double-publish `VideoPhysicalDeletionEvent` (assert
via a spy/captured-event-count on `ApplicationEventPublisher`, mirroring how other listener-count
assertions are done elsewhere in this codebase's tests).

**Also in this AC — `resolveMinUploadTier` (:148-160):** low-priority companion fix, same file, same
review pass. Current code:
```java
private String resolveMinUploadTier() {
    for (CoachSubscriptionTier t : CoachSubscriptionTier.values()) {
        if (configService.find("feature.drillVideoUpload.enabled." + t.name())
                .map("true"::equalsIgnoreCase).orElse(false)) {
            return t.name();
        }
    }
    ...
}
```
This assumes `CoachSubscriptionTier.values()` is declared in ascending-rank order — true today, but
undocumented and silently wrong if a future tier is inserted out of rank order. It is used only for an
error-message hint, not access control, so this is a documentation-severity fix: add a one-line comment on
the enum declaration itself (`CoachSubscriptionTier.java`) noting that declaration order is load-bearing for
`DrillUploadService.resolveMinUploadTier`. Do not restructure the enum or this method — out of proportion
for the actual risk.

### AC6 — `HomeworkAssignmentService`: fix the N+1 in `getLockerRoomDrills`, add the missing event-path size guard

**Current behavior, verified against live source**
(`src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java`):

`getLockerRoomDrills` (:91-120) calls `packSessionService.hasActivePack(playerId, coachId)` once per
distinct coach in a `.filter(...)` on a stream — a real N+1 (confirmed: `PackSessionService` has no batch
variant; `hasActivePack` is `!findActivePacks(playerId, coachId, now).isEmpty()`, one query per call).

`handleBookingCompleted` (:52-83, the `@TransactionalEventListener(AFTER_COMMIT) @Async` entry point) only
checks `event.getHomeworkDrillIds() == null || event.getHomeworkDrillIds().isEmpty()` — no upper-bound
check. `WrapUpRequest.homeworkDrillIds` (the HTTP-layer DTO) carries `@Size(max=2)`, but that constraint is
enforced only on the HTTP path; the event object itself has no equivalent guard, so any future publisher of
`BookingCompletedEvent` besides the one HTTP-validated path could assign an unbounded number of homework
drills.

**Fix:**

1. **Batch `hasActivePack`.** Add to `SessionPackPurchaseRepository` a method returning the subset of a
   given coachId list that has an active pack for the player. **Story-review 2026-08-27 flagged this query
   as needing verification against `findActivePacks`'s real filters before writing — done: the original
   draft below was wrong on two counts, both now fixed.** `SessionPackPurchase`
   (`src/main/java/com/softropic/skillars/platform/payment/entity/SessionPackPurchase.java`) has **no
   `status` field at all** (confirmed by reading every field on the entity) — a `p.status = 'ACTIVE'`
   predicate would fail at query-validation time, not silently misbehave. The real `findActivePacks`
   (`SessionPackPurchaseRepository.java:42-50`) instead gates on `remainingSessions > 0`, `expiresAt > :now`,
   and `(pausedUntil IS NULL OR pausedUntil <= :now)` — the pause check was missing from the original draft
   entirely, which would have wrongly reported a paused pack as active. Corrected query:
   ```java
   @Query("SELECT DISTINCT p.coachId FROM SessionPackPurchase p WHERE p.playerId = :playerId " +
          "AND p.coachId IN :coachIds AND p.remainingSessions > 0 AND p.expiresAt > :now " +
          "AND (p.pausedUntil IS NULL OR p.pausedUntil <= :now)")
   Set<UUID> findCoachIdsWithActivePack(@Param("playerId") Long playerId, @Param("coachIds") Set<UUID> coachIds, @Param("now") Instant now);
   ```
   Still re-verify this against `findActivePacks`'s live text before writing — it may have changed again
   since this story's creation — but the two concrete errors above are fixed, not just flagged. Add
   a matching `PackSessionService` method (e.g. `hasActivePackForAnyOf(Long playerId, Set<UUID> coachIds)`
   returning the matched subset) and call that once from `getLockerRoomDrills` instead of the
   per-coach `.filter(...)` loop.
2. **Add the event-path size guard.** In `handleBookingCompleted`, immediately after the existing
   null/empty check:
   ```java
   if (event.getHomeworkDrillIds().size() > 2) {
       log.warn("BookingCompletedEvent for booking {} carries {} homeworkDrillIds, exceeding the max of 2 — truncating",
           event.getBookingId(), event.getHomeworkDrillIds().size());
   }
   List<UUID> drillIdsToAssign = event.getHomeworkDrillIds().stream().limit(2).toList();
   ```
   and use `drillIdsToAssign` in place of `event.getHomeworkDrillIds()` in the loop below it. Truncate
   rather than reject outright — this is a defense-in-depth guard against a future publisher, not a
   user-facing validation error path (there is no caller to return an error to; this runs `@Async` after
   commit).

**Testing:** add to `HomeworkResourceIT.java` or a `HomeworkAssignmentServiceTest`:
- A batched-lookup test seeding 3+ coaches (some with active packs, some without) and asserting
  `getLockerRoomDrills` returns drills only from coaches with an active pack, with the query count
  (via a Hibernate statistics assertion, matching this codebase's established N+1-regression-test style if
  one exists elsewhere — check `SluContributionServiceTest` or similar for the pattern) not scaling with
  coach count.
- `handleBookingCompleted_moreThanTwoHomeworkDrillIds_assignsOnlyFirstTwo` — publish an event with 3+ ids,
  assert only 2 `HomeworkAssignment` rows are created.

### AC7 — Add the missing FK on `session.homework_assignments.pack_id`

**Verified:** `HomeworkAssignmentService.resolvePackId` (:168-170) calls
`packSessionService.getActivePackId(playerId, coachId)`, which returns a real
`payment.session_pack_purchases.purchase_id` (or `null`) — confirmed by direct read of
`PackSessionService.getActivePackId`. The column is live and correctly populated; it has simply never had
an FK (`V45__homework_assignments.sql:8`: `pack_id UUID` — no `REFERENCES`). Cross-schema FKs from
`session`/`booking` tables into `payment` already exist (`booking.bookings.session_pack_purchase_id
REFERENCES payment.session_pack_purchases(purchase_id)`, `V62`), so this is a consistent, low-risk addition.

**New migration** `V109__homework_assignments_pack_id_fk.sql` (confirm `V109` is still the next free number
before writing — `V108` was the latest at story-creation time):
```sql
-- Story Deferred-75 AC7: session.homework_assignments.pack_id has always pointed at a real, live
-- payment.session_pack_purchases.purchase_id (via HomeworkAssignmentService.resolvePackId ->
-- PackSessionService.getActivePackId) but never had the FK declared (V45 predates the pattern this
-- migration now applies, mirroring the existing booking.bookings -> payment.session_pack_purchases
-- cross-schema FK from V62). ON DELETE SET NULL because the column is already nullable and no code
-- path deletes a session_pack_purchases row today.

-- Defensive: clear any orphaned pack_id that doesn't match a live purchase before adding the FK, so
-- this migration cannot fail on unexpected existing data in any already-deployed environment.
UPDATE session.homework_assignments ha
SET pack_id = NULL
WHERE ha.pack_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM payment.session_pack_purchases p WHERE p.purchase_id = ha.pack_id
  );

ALTER TABLE session.homework_assignments
    ADD CONSTRAINT fk_homework_assignments_pack
    FOREIGN KEY (pack_id) REFERENCES payment.session_pack_purchases(purchase_id)
    ON DELETE SET NULL;
```

**Testing:** add an IT assertion (`HomeworkResourceIT` or a repository-level test) that inserting a
`homework_assignments` row with a `pack_id` not present in `session_pack_purchases` now fails at the DB
layer (mirrors how other FK-hardening stories in this ledger verify their new constraint).

### AC8 — Rename the internal `library_type` value `'COACH'` → `'PRIVATE'` for consistency

**Verified scope** (every current production/test occurrence of the drill-library `'COACH'` value,
confirmed by targeted grep — excludes unrelated `"COACH"` role/subscription-tier/party-role literals):

Backend production code:
- `src/main/resources/db/migration/V38__session_module_init.sql:11,20-21` — the `library_type` CHECK
  constraint and `chk_drill_owner` CHECK, both listing `'COACH'`.
- `DrillUploadService.java:61,113` — `!"COACH".equals(drill.getLibraryType())`.
- `DrillLibraryService.java:126,153,172` — `clone.setLibraryType("COACH")` and two ownership checks.

Frontend production code:
- `DrillCard.vue:67` — `drill.libraryType === 'COACH'` (already being touched by AC4 — apply both changes
  together in this file).
- `DrillDetailPanel.vue:94,241,335` — three `libraryType === 'COACH'` checks gating video-upload UI.

Test code (do not hand-enumerate — run this sweep as a task step and fix every hit):
```
grep -rln "\"COACH\"" src/test/java/com/softropic/skillars/platform/session/ | xargs grep -n "\"COACH\""
```
confirmed hits at story-creation time include (line numbers will drift — re-grep before editing):
`DrillLibraryResourceIT.java` (insertUser role literals — **leave unchanged**, only the `libraryType`
assertion at :187 changes), `DrillUploadServiceTest.java:422`, `DrillUploadResourceIT.java` (3
`insertDrill(...)` calls), `DrillTagResourceIT.java` (2 `insertDrill(...)` calls),
`HomeworkResourceIT.java:115`, `DrillLibraryServiceTest.java` (2 occurrences). **Only change the 5th
argument of `insertDrill(...)` calls and direct `libraryType`/`setLibraryType` assertions — leave every
`insertUser(..., "COACH")` role literal untouched, they are a different concept (user role, not drill
library type).**

**Migration** `V110__drill_library_type_private_rename.sql` (next free number after AC7's `V109`):
```sql
-- Story Deferred-75 AC8: renames the internal library_type value 'COACH' to 'PRIVATE', aligning the
-- DB/domain-internal representation with the term already used everywhere external to this table (the
-- API's ?library=PRIVATE query param, the frontend's "My Library" PRIVATE tab, and DrillCard.vue's own
-- reuse-context reasoning from AC4). Two CHECK constraints reference the old value: library_type's own
-- inline check (Postgres auto-names an unnamed single-column CHECK as "<table>_<column>_check" — verify
-- this is actually "drills_library_type_check" via \d session.drills against a live DB before running;
-- if the name differs, this migration will fail loudly rather than silently no-op, which is the safe
-- failure mode here) and the explicitly-named chk_drill_owner.

UPDATE session.drills SET library_type = 'PRIVATE' WHERE library_type = 'COACH';

ALTER TABLE session.drills DROP CONSTRAINT drills_library_type_check;
ALTER TABLE session.drills ADD CONSTRAINT drills_library_type_check
    CHECK (library_type IN ('PLATFORM', 'PRIVATE'));

ALTER TABLE session.drills DROP CONSTRAINT chk_drill_owner;
ALTER TABLE session.drills ADD CONSTRAINT chk_drill_owner CHECK (
    (library_type = 'PLATFORM' AND owner_coach_id IS NULL) OR
    (library_type = 'PRIVATE'  AND owner_coach_id IS NOT NULL)
);
```

**Fix — application code:** change every occurrence listed above from the literal `"COACH"` to `"PRIVATE"`.
Do not touch `Drill.libraryType`'s Java field name, the `library_type` DB column name, or the
`DrillRepository.findByLibraryTypeAndStatus`/`findByOwnerCoachIdAndStatus` method names — only the string
*value* changes.

**Story-review 2026-08-27 asked two assumptions here be verified rather than left as risk notes — both
confirmed:** `Drill.java` (`src/main/java/com/softropic/skillars/platform/session/repo/Drill.java:40`)
declares `private String libraryType;` — a plain `String`, no Java `enum` exists for this field anywhere in
the codebase (`grep -rln "enum LibraryType" src/main/java` — zero hits), so there is no third code location
this rename could miss. The `drills_library_type_check` auto-generated-name assumption still needs a live
`\d session.drills` check at dev-story time (not something a static grep can confirm) — that verification
step in the migration's own header comment stays as written.

**Testing:** run the full `session` package's existing test suite after the rename — every test asserting
`libraryType == "COACH"` or seeding `insertDrill(..., "COACH", ...)` must be updated to `"PRIVATE"` for the
suite to stay green; this is intentionally a wide, mechanical sweep. Add no new test — this AC's own
correctness is proven by the existing suite passing unchanged in behavior, only in literal value.

### AC9 — Drill video playback: recover from an expired signed URL instead of failing silently

**Current behavior, verified against live source**: `DrillCard.vue:6-15` and `DrillDetailPanel.vue:7-15,152-160`
each render `<video :src="drill.videoUrl">` with no `@error` handler. `drill.videoUrl` is fetched once and
cached in a Pinia store indefinitely; `DrillLibraryService`'s signed URL expires after 2h server-side
(`videoProviderAdapter.generatePlaybackUrl`). No single-drill GET endpoint exists (`DrillLibraryResource`
only exposes the list endpoint) — a targeted per-drill refresh would require a new endpoint, out of
proportion for this fix.

**Story-review 2026-08-27 flagged the original draft's dependency on a `sessionStore.currentLibrary` field
as needing verification. Verification found something more serious than a missing field: the original
draft's design — `DrillCard.vue` calling `sessionStore.fetchDrills(...)` directly from inside its own
`@error` handler — is wrong for 2 of `DrillCard`'s 4 render contexts, not just missing one store field.**
Traced every render site (`grep -rn "<DrillCard" src/frontend/src`) and what data each one actually reads:

| Render site | Store `drill.videoUrl` actually comes from | `sessionStore.fetchDrills(...)` would... |
|---|---|---|
| `DrillLibraryPage.vue` (`context="library"`) | `sessionStore.drills` | correctly refresh it |
| `SessionBuilderPage.vue`'s own `DrillCard v-for` (`context="session-builder"`) | `sessionStore.drills` | correctly refresh it |
| `DrillSuggestionPanel.vue`'s `DrillCard v-for` (`context="session-builder"`, rendered only inside `SessionBuilderPage.vue` when `selectedLibrary === 'SUGGESTED'`) | `builderStore.suggestedDrills` (`sessionBuilder.store.js`) | **no-op** — wrong store entirely |
| `PlayerLockerRoomPlaceholderPage.vue` (`context="locker-room"`) | `homeworkStore.assignments` (`homework.store.js`, fetched by `playerId` via `homeworkStore.fetchDrills(playerId)` — a different function, different store, different parameter, from a different backend endpoint, `homeworkApi.getLockerRoomDrills`) | **no-op** — wrong store, and `sessionStore.fetchDrills` doesn't even take a `playerId` |

`DrillDetailPanel.vue` (default `context: null`, per its own `defineProps` at line 300) is only ever
rendered from `DrillLibraryPage.vue` (no explicit `context`, defaults `null`) and `SessionBuilderPage.vue`
(`context="session-builder"`) — never from the locker-room page — so it only needs the `sessionStore`/
`builderStore` split, not the `homeworkStore` case.

**Corrected fix — components emit, parents decide how to refresh their own data:**

1. **`DrillCard.vue` and `DrillDetailPanel.vue`** — add `@error` on every `<video>` element (1 in
   `DrillCard.vue`, 3 in `DrillDetailPanel.vue`) that emits a new `video-error` event, guarded against
   retry loops with a local ref (fires at most once per component mount) — no store import, no store call,
   inside either component:
   ```vue
   <video
     v-if="drill.hasVideo && drill.videoUrl && !prefersReducedMotion"
     :src="drill.videoUrl"
     @error="handleVideoError"
     ...
   />
   ```
   ```js
   const videoErrorEmitted = ref(false)
   function handleVideoError() {
     if (videoErrorEmitted.value) return
     videoErrorEmitted.value = true
     emit('video-error')
   }
   ```
   Add `'video-error'` to each component's `defineEmits([...])` list.

2. **`DrillSuggestionPanel.vue`** — has no data-refresh action of its own (`suggestions` is a prop, owned by
   its parent); add `'video-error'` to its own `defineEmits` and forward the child event verbatim:
   ```vue
   <DrillCard ... @video-error="emit('video-error')" />
   ```

3. **`DrillLibraryPage.vue`** — wire both render sites to the existing tab-state ref (already used by
   `onTabChange`/`debouncedSearch`, `:174`):
   ```vue
   <DrillCard ... @video-error="sessionStore.fetchDrills(selectedLibrary)" />
   ...
   <DrillDetailPanel ... @video-error="sessionStore.fetchDrills(selectedLibrary)" />
   ```
   (Call `fetchDrills` directly rather than `onTabChange`, which also resets search/filters — a background
   video-recovery refetch should not silently clear whatever the coach was searching for.)

4. **`SessionBuilderPage.vue`** — this page already owns a `fetchDrills()` local function (`:277-289`) that
   correctly branches on `selectedLibrary.value === 'SUGGESTED'` (calls `builderStore.fetchSuggestions()`)
   vs. the ordinary library tabs (calls `sessionStore.searchDrills(selectedLibrary.value)`) — reuse it
   as-is, from all three of this page's `DrillCard`/`DrillDetailPanel`/`DrillSuggestionPanel` instances:
   ```vue
   <DrillCard ... @video-error="fetchDrills" />
   ...
   <DrillDetailPanel ... @video-error="fetchDrills" />
   ...
   <DrillSuggestionPanel ... @video-error="fetchDrills" />
   ```

5. **`PlayerLockerRoomPlaceholderPage.vue`** — wire to `homeworkStore.fetchDrills`, passing the page's own
   `playerId` computed (`:64`, already used by this page's existing `watch(playerId, ...)` fetch):
   ```vue
   <DrillCard ... @video-error="homeworkStore.fetchDrills(playerId)" />
   ```

No `sessionStore.currentLibrary` field is added — the original draft's proposed field is unnecessary once
the refetch decision is pushed to each page, which already has the correct context-specific state.

**Testing:** no frontend test runner exists (standing gap). Verify manually in all four contexts — force a
`<video>` `error` event (e.g. via browser devtools overriding `src` to a 403 URL) and confirm exactly one
refetch fires, not a loop, and that it actually replaces the broken `videoUrl`: (a) `DrillLibraryPage.vue`
on both tabs, (b) `SessionBuilderPage.vue`'s library tabs, (c) `SessionBuilderPage.vue`'s `SUGGESTED` tab
(confirms `builderStore.fetchSuggestions()` fires, not a `sessionStore` no-op), (d)
`PlayerLockerRoomPlaceholderPage.vue` (confirms `homeworkStore.fetchDrills(playerId)` fires, not a
`sessionStore` no-op).

### AC10 — Deterministic ids for the 20 `V39` seed drills

**Verified:** `V39__session_foundation_20_drills.sql` inserts all 20 platform drills with
`gen_random_uuid()`, so each environment that has run this migration has different, environment-specific
ids for "the same" seed drill. This migration cannot be edited (already applied everywhere). Reassigning an
existing row's `id` risks orphaning any reference an environment may have already created against today's
random id (`drill_video_refs`, `homework_assignments`, tags) — no such references are seeded by V39 itself
(confirmed: its own header comment says "No drill_video_refs rows"), but a coach or player interaction in
any live environment could have created one since.

**Decided:** proceed, with a same-migration safety guard that aborts loudly (fails the migration) rather
than silently reassigning under a live reference, so this is safe to run against dev/CI (no references
expected) and self-protecting against UAT/prod (where it may need a manual follow-up instead of blindly
reapplying).

**New migration** `V111__seed_drill_deterministic_ids.sql` (next free number after AC8's `V110`):
```sql
-- Story Deferred-75 AC10: V39 seeded these 20 drills with gen_random_uuid(), so every environment has a
-- different id for "the same" platform drill. V39 cannot be edited (already applied everywhere), so this
-- reassigns each seed drill's id to a fixed, deterministic value, matched by its unique trans_key (stable
-- since V39, UNIQUE-constrained). Guarded: aborts the whole migration if any drill_video_refs or
-- homework_assignments row already references one of these 20 drills under its current (random) id in
-- this environment -- reassigning under a live reference would silently orphan it. If this guard fires in
-- a real environment, that environment needs a hand-written follow-up migrating the specific references
-- found, not a blind re-run of this file.

DO $$
DECLARE
    ref_count INT;
BEGIN
    SELECT COUNT(*) INTO ref_count
    FROM session.drills d
    WHERE d.library_type = 'PLATFORM' AND d.trans_key LIKE 'sessDrill.%'
      AND (
        EXISTS (SELECT 1 FROM session.drill_video_refs r WHERE r.drill_id = d.id)
        OR EXISTS (SELECT 1 FROM session.homework_assignments h WHERE h.drill_id = d.id)
      );
    IF ref_count > 0 THEN
        RAISE EXCEPTION 'V111 aborted: % of the 20 V39 seed drills already have a live drill_video_refs or homework_assignments reference in this environment. Deterministic-id reassignment would orphan it -- write a hand-migration for this environment instead of re-running this file.', ref_count;
    END IF;
END $$;

UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000001' WHERE trans_key = 'sessDrill.toeTaps';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000002' WHERE trans_key = 'sessDrill.insideOutsideRoll';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000003' WHERE trans_key = 'sessDrill.lShapeMastery';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000004' WHERE trans_key = 'sessDrill.foundationJugglingSequence';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000005' WHERE trans_key = 'sessDrill.coneSlalomBallMastery';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000006' WHERE trans_key = 'sessDrill.staticFinishInsideFoot';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000007' WHERE trans_key = 'sessDrill.turnAndShoot';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000008' WHERE trans_key = 'sessDrill.shootingOnTheMove';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000009' WHERE trans_key = 'sessDrill.oneVOneFinishingUnderPressure';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000010' WHERE trans_key = 'sessDrill.volleyFinishing';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000011' WHERE trans_key = 'sessDrill.cruyffTurn';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000012' WHERE trans_key = 'sessDrill.stepOverToAccelerate';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000013' WHERE trans_key = 'sessDrill.elasticoFlipFlap';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000014' WHERE trans_key = 'sessDrill.pressureEscapeRondo3v1';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000015' WHERE trans_key = 'sessDrill.bodyFeintAndDrive';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000016' WHERE trans_key = 'sessDrill.passAndMove2Touch';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000017' WHERE trans_key = 'sessDrill.wallPassOneTwoCombination';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000018' WHERE trans_key = 'sessDrill.switchOfPlayFromCentral';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000019' WHERE trans_key = 'sessDrill.thirdManRunCombination';
UPDATE session.drills SET id = '00000000-0000-4000-8000-000000000020' WHERE trans_key = 'sessDrill.receivingInTheHalfTurn';
```
Verify all 20 `trans_key` values against the live `V39` file content before running — copied here from a
direct read at story-creation time; re-confirm no drift. **Story-review 2026-08-27 asked this be
re-checked: re-ran `grep -oE "sessDrill\.[a-zA-Z0-9]*" V39__session_foundation_20_drills.sql | sort -u`
against the live migration file — exactly the same 20 keys as listed above, including the two with digits
(`pressureEscapeRondo3v1`, `passAndMove2Touch`) that an alphabetic-only grep would truncate — no drift.**

**Testing:** add an IT assertion that after migration, `session.drills` has exactly 20 rows with
`library_type = 'PLATFORM'` whose ids fall in this fixed set, and that a second application of the same
`UPDATE` statements (idempotency check, run manually against a test DB) is a no-op (rows already at target
id — `WHERE trans_key = ...` still matches the same row by its unique, unchanged `trans_key`).

### AC11 — Validate `developmentFocus` against the known focus-code set

**Verified:** `DrillSuggestionService.computeFocusScore` (:102-118) is a real `switch` over 8 focus codes
(`technical`, `physical`, `cognitive`, `matchRealism`, `weakFoot`, `set_pieces`, `goalkeeping`,
`possession`), each with its own scoring formula — **not** a stub, contrary to the ledger's "by-design stub
behavior" framing. `DevelopmentFocusSelector.vue`'s `FOCUS_OPTIONS` constant (:37-46) lists exactly the same
8 values, so the `default -> 0.0` fallback branch is unreachable **today** through the normal UI flow — but
nothing enforces that the two lists stay in sync, and `CreateSessionPlanRequest`/`UpdateSessionPlanRequest`'s
`developmentFocus` field has no validation constraint restricting it to this set (confirmed: it flows
straight from the request into `session.setDevelopmentFocus(req.developmentFocus())` with no `@Pattern`,
enum type, or custom validator).

**Fix:** add a validator restricting `developmentFocus` list elements to the known set, mirroring this
codebase's general boundary-validation convention (e.g. `@IanaTimezone`). Simplest shape given there's no
existing custom-list-element validator precedent to mirror exactly — add a Jakarta `@Pattern` on each list
element via a wrapping constraint, or a small dedicated `@ValidFocusCode` annotation + validator class if
list-element `@Pattern` proves awkward with Bean Validation's cascade rules. Either approach must reject an
unrecognized focus code with 400 at the request boundary (`CreateSessionPlanRequest`/
`UpdateSessionPlanRequest`), not silently pass through and score 0.

**Single source of truth (story-review 2026-08-27 asked this be pinned down explicitly rather than left as
"one place"):** add
```java
public static final Set<String> KNOWN_FOCUS_CODES = Set.of(
    "technical", "physical", "cognitive", "matchRealism", "weakFoot", "set_pieces", "goalkeeping", "possession");
```
to `DrillSuggestionService` itself (`src/main/java/com/softropic/skillars/platform/session/service/DrillSuggestionService.java`)
— the class that already owns `computeFocusScore`'s switch, so the known-code list and its scoring
implementation stay in the same file rather than splitting across a new dedicated class for eight literals.
The new `@ValidFocusCode` validator (or the `@Pattern` wrapper, whichever shape is chosen) references
`DrillSuggestionService.KNOWN_FOCUS_CODES` rather than re-declaring the list a third time (frontend
`DevelopmentFocusSelector.vue` and the backend switch are already two independent copies — this AC's point
is to stop that at two, not create a third un-synced one via the validator).

As defense-in-depth for any focus code that reaches `computeFocusScore` despite the new boundary validation
(e.g. a pre-existing session created before this validator shipped, now going through `updateSession`'s
DNA recalculation), add a one-line WARN log in the `default` branch:
```java
default -> {
    log.warn("Unsupported development focus code '{}' reached computeFocusScore — scoring as 0", f);
    yield 0.0;
}
```
(Switch-expression `yield` syntax — confirm this compiles cleanly with the existing `switch` expression
shape at :105-115; adjust to statement form if a `default ->` block with a log call doesn't fit the
existing arrow-style cases without also converting every other case, in which case put the log call before
the switch for the specific unrecognized-code case instead, whichever is the smaller diff.)

**Testing:** add to whatever request-validation test class covers `CreateSessionPlanRequest`/
`UpdateSessionPlanRequest` today (check for an existing one before creating a new one) — a case asserting
an unrecognized `developmentFocus` value is rejected with 400, and a case asserting all 8 known values are
still accepted (regression guard against an overly strict validator).

### AC12 — Ledger hygiene

Apply these `deferred-work.md` edits (locate each by its quoted text — line numbers shift):

1. **W10, `## Deferred from: code review of skillars-4-3-custom-drill-uploads — round 2 (2026-06-17)`**
   (`FeatureGatedException` error code not matched by frontend catch block) — append:
   `` `[CLOSED by skillars-deferred-75 (verified already fixed, unannotated): DrillDetailPanel.vue:394-395
   already checks errorKey === 'security.featureGated' and shows t('security.featureGated'). Fixed by an
   unidentified prior change; never tagged.]` ``
2. **W3, `## Deferred from: code review of skillars-4-4-session-builder-block-structure-dna (2026-06-18)`**
   (`WrapUpSequence` uses `variant="compact"` instead of spec `"full"`) — append:
   `` `[CLOSED by skillars-deferred-75 (verified already fixed, unannotated): WrapUpSequence.vue:167 already
   renders variant="full". Fixed by an unidentified prior change; never tagged.]` ``
3. **W7, same heading** (IT teardown `DELETE FROM session.sessions` before `booking.bookings`) — append:
   `` `[CLOSED by skillars-deferred-75 (verified stale): SessionBuilderResourceIT.java has no @AfterEach
   or raw DELETE FROM teardown SQL at all today; cleanup is handled by the shared Testcontainers reset
   mechanism. The specific ordering concern this item named no longer applies.]` ``
4. **W1, `## Deferred from: code review of skillars-4-6-homework-assignment-player-locker-room (2026-06-18)`**
   (N+1 in `getLockerRoomDrills`) — append: `` `[CLOSED by skillars-deferred-75 AC6]` ``
5. **W4, same heading** (`@Size(max=2)` not enforced on event-driven path) — append:
   `` `[CLOSED by skillars-deferred-75 AC6]` ``
6. **W1/W2, `## Deferred from: code review of skillars-4-5-intelligent-drill-suggestions-session-templates — Round 2 (2026-06-18)`**
   (`deleteTemplate()` no ARCHIVED guard; `deployTemplate()` blocks-by-reference) — append to each:
   `` `[CLOSED by skillars-deferred-75 AC1]` ``
7. **W3, same heading** (`computeFocusScore()` returns 0 for unsupported values) — append:
   `` `[CLOSED by skillars-deferred-75 AC11 (validated at the request boundary instead of left as a silent
   fallback; the original "by-design stub" framing was itself inaccurate — the function is a real 8-code
   switch, not a stub)]` ``
8. **W9, `## Deferred from: code review of skillars-4-4-session-builder-block-structure-dna — round 2 (2026-06-18)`**
   (`updateSession` does not re-validate booking plannable status) — append:
   `` `[CLOSED by skillars-deferred-75 AC2]` ``
9. **W2, same heading** (IT test doesn't assert `SESSION_PLAN_LOCKED` helpCode) — append:
   `` `[CLOSED by skillars-deferred-75 AC3]` ``
10. **W1/W2/W3, `## Deferred from: code review of skillars-4-3-custom-drill-uploads (2026-06-17)`**
    (concurrent `initiateUpload`; `existsByVideoId` timing in concurrent `deleteVideo`; rollback orphans
    provider asset) — append to W1 and W2: `` `[CLOSED by skillars-deferred-75 AC5]` ``. W3 (transaction
    rollback leaving an orphaned provider asset with no reconciliation worker) is **not** closed by this
    story's lock-based fix — locking prevents the *concurrent-request* race, not a mid-request crash after
    the provider call but before commit. Leave W3 open, annotate:
    `` `[Note 2026-08-26: skillars-deferred-75 AC5 closed the concurrent-request race (W1/W2) via
    PessimisticLockRetryer; this item's own scenario (a crash/rollback mid-request) is a different,
    still-open gap — needs a reconciliation worker, not a lock.]` ``
11. **W4, same heading** (`resolveMinUploadTier` enum-order dependency) — append:
    `` `[CLOSED by skillars-deferred-75 AC5 (documented via a comment on the enum; not restructured)]` ``
12. **`Def14` (all cross-referencing headings: `skillars-6-1 Run 2`, and the two `deferred-56` review
    mirror-items around initiateUpload)** — append to each occurrence:
    `` `[CLOSED by skillars-deferred-75 AC5]` ``
13. **D3, `## Deferred from: code review of skillars-11-3-remove-legacy-session-pack-system (2026-08-04)`**
    (`pack_id` has no FK, "points at nothing meaningful") — append:
    `` `[CLOSED by skillars-deferred-75 AC7. Correction: this item's premise was wrong — pack_id is not
    dead; HomeworkAssignmentService.resolvePackId still writes a real, live
    payment.session_pack_purchases.purchase_id on every assignment. The FK was simply never added; V109
    adds it.]` ``
14. **W3/W5, `## Deferred from: code review of skillars-4-2-drill-card-operations (2026-06-17)`**
    (`removeTag` chip visible for any COACH drill; COACH vs PRIVATE naming) — append to W3:
    `` `[CLOSED by skillars-deferred-75 AC4. Correction: this item's own framing was too soft — it called
    the multi-context-reuse scenario hypothetical ("if DrillCard is reused"); DrillCard was already reused
    on PlayerLockerRoomPlaceholderPage.vue at the time this item was originally filed.]` ``. Append to W5:
    `` `[CLOSED by skillars-deferred-75 AC8]` ``

**Testing:** none — this AC is markdown-only ledger editing, no code changes, no test impact.

## Tasks / Subtasks

- [x] AC1: `SessionTemplateService.deleteTemplate` ARCHIVED guard; `deployTemplate` defensive blocks copy.
      Add `deleteTemplate_alreadyArchived_returns403` to `SessionTemplateResourceIT.java`.
- [x] AC2: Inject `BookingStateMachine` into `SessionPlanService`; add the booking-terminal-status guard to
      `updateSession`. Add `updateSession_bookingCancelled_returns403WithHelpCodeSessionPlanLocked` to
      `SessionBuilderResourceIT.java` (verify `CANCELLED_PARENT` is terminal via `BookingStateMachineTest`
      first).
- [x] AC3: Fix `updateSession_completedSession_returns403WithHelpCodeSessionPlanLocked` to actually assert
      the `helpCode` body field. Apply the identical assertion shape to AC2's new test.
- [x] AC4: `DrillCard.vue` — gate the tag-edit block on `context !== 'locker-room'` in addition to the
      `libraryType` check. Manually verify on `DrillLibraryPage.vue`, `SessionBuilderPage.vue`, and
      `PlayerLockerRoomPlaceholderPage.vue`.
- [x] AC5: Add `DrillRepository.findByIdForUpdate`. Wrap `DrillUploadService.initiateUpload`/`deleteVideo`
      in `PessimisticLockRetryer.withBoundedRetry`. Add a concurrency IT mirroring
      `SessionPackPurchaseLockContentionIT`. Add the `CoachSubscriptionTier` declaration-order comment.
- [x] AC6: Add a batch `hasActivePack`-equivalent query/service method; use it in
      `getLockerRoomDrills`. Add the `>2` truncation guard + WARN log to `handleBookingCompleted`. Add
      both new tests.
- [x] AC7: Write `V109__homework_assignments_pack_id_fk.sql` (orphan-clear + FK). Add the FK-violation IT
      assertion.
- [x] AC8: Write `V110__drill_library_type_private_rename.sql`. Update every `"COACH"`-as-library-type
      literal in `DrillUploadService.java`, `DrillLibraryService.java`, `DrillCard.vue`,
      `DrillDetailPanel.vue`, and every test file found by the grep sweep specified in AC8. Run the full
      `session` package test suite to confirm the sweep was complete.
- [x] AC9: Add `@error` + single-emit-guard to the `<video>` elements in `DrillCard.vue` (1) and
      `DrillDetailPanel.vue` (2 — the story said 3, but only 2 `<video>` elements exist: mobile
      bottom-sheet + desktop dialog; see Completion Notes). Add `video-error` passthrough emit to
      `DrillSuggestionPanel.vue`. Wire `@video-error` handlers at all 4 render sites
      (`DrillLibraryPage.vue` ×2, `SessionBuilderPage.vue` ×3 via its existing `fetchDrills()`,
      `PlayerLockerRoomPlaceholderPage.vue` ×1 via `homeworkStore.fetchDrills(playerId)`). Do not add a
      `sessionStore.currentLibrary` field — not needed under this design. Also added a drill-change
      watcher resetting `DrillDetailPanel.vue`'s emit guard — that panel is a single shared instance
      reused across drills, not remounted per drill (see Completion Notes).
- [x] AC10: Write `V111__seed_drill_deterministic_ids.sql`, re-verifying all 20 `trans_key` values against
      live `V39` content first. Add the post-migration id-set IT assertion.
- [x] AC11: Add `developmentFocus` element validation (single source of truth for the known-code list) to
      `CreateSessionPlanRequest`/`UpdateSessionPlanRequest`. Add the WARN-log defense-in-depth to
      `computeFocusScore`'s default branch. Add the two new validation test cases.
- [x] AC12: Apply all 14 `deferred-work.md` closures listed above. Corrected 2 of them during
      implementation: Def14's closure (and its mirror at the deferred-22 review section) now correctly
      scopes to "same-drill variant only" rather than claiming full closure — investigation found the
      per-Drill-row lock does not serialize two different drillIds sharing one videoId (reachable via
      `DrillLibraryService.cloneDrill`), which is what Def14's own original wording describes. See
      Completion Notes.
- [x] Run the full targeted test sweep for every touched class; confirm no regressions. Do not run
      `mvn verify` locally — GitHub CI is the sole full-verification gate (`docs/validation-strategy.md`).
      Run `npx eslint` on every touched frontend file.

### Review Findings

**Decision Needed (Resolved):**
- [x] [Review][Decision] SessionTemplateService.deleteTemplate() idempotency — Resolved: Behavior is intentional (option 1 — throw on archived delete).

**Patch Items:**
- [x] [Review][Patch] CRITICAL: Missing Files for Multiple ACs — V109/V110/V111 migrations (AC7, AC8, AC10), DrillUploadServiceConcurrencyIT.java (AC5), SeedDrillDeterministicIdsIT.java (AC10) all now staged and included in diff.
- [x] [Review][Patch] Unsafe BookingStatus.valueOf() without null check [SessionPlanService.java:135] — Fixed: Added null check before enum conversion.
- [x] [Review][Patch] Silent Truncation of HomeworkDrillIds [HomeworkAssignmentService.java:62-66] — Fixed: Changed to throw IllegalArgumentException on oversized events.
- [x] [Review][Patch] CoachSubscriptionTier Enum Order Not Validated [CoachSubscriptionTier.java] — Fixed: Added static initializer validation at class load time.
- [x] [Review][Patch] Incomplete Test Setup in HomeworkResourceIT [HomeworkResourceIT.java:93-96] — Fixed: Set packId = paymentPurchaseId after insertion.
- [x] [Review][Patch] SessionTemplateService.deployTemplate() Null Safety [SessionTemplateService.java:155] — Fixed: Added null check with empty list fallback.
- [x] [Review][Patch] Batch Query Performance [SessionPackPurchaseRepository.java:61-63] — Fixed: Added 1000-element size assertion in hasActivePackForAnyOf.
- [x] [Review][Patch] DrillSuggestionService Default Case Too Lenient [DrillSuggestionService.java:99-124] — Fixed: Changed default case to throw IllegalArgumentException.
- [x] [Review][Patch] PessimisticLockRetryer Backoff Not Validated [Infrastructure retry mechanism] — Fixed: Added startup validation calculating worst-case backoff budget.
- [x] [Review][Patch] EntityManager.refresh() Pattern Documentation [DrillUploadService.java:63-64, 88-90] — Fixed: Added clarifying comment on discarded locked entity return.
- [x] [Review][Patch] Drill Deletion Error Messaging [DrillUploadService.java:63-64, 88-89] — Fixed: Updated error message to indicate concurrent deletion scenario.
- [x] [Review][Patch] ValidFocusCode Validator Coupling [ValidFocusCodeValidator.java:16] — Fixed: Added comment documenting three-copy risk and sync requirement.

**Deferred Issues:**
- [x] [Review][Defer] Video Error Handling UX [DrillCard.vue et al.] — deferred, design decision required. Silent refetch on playback error leaves video broken with no user feedback.
- [x] [Review][Defer] SessionPlanService Terminal Booking Check Order [SessionPlanService.java:127-139] — deferred, design intent unclear. State (DRAFT session + terminal booking) may be intentional or edge-case recovery path.

## Dev Notes

- **This is a large, multi-part bundle by explicit project-owner direction** — do not split it back into
  smaller stories mid-implementation. Each AC is independently testable and independently revertible if one
  causes trouble; implement and verify them in the order listed (AC7→AC8→AC10 share migration-numbering
  sequence and must land in that relative order regardless of what else is interleaved).
- **Migration numbering is a live target.** `V108` was the latest migration at story-creation time. Before
  writing `V109`, re-run `ls src/main/resources/db/migration/ | sort -V | tail -5` to confirm no migration
  landed between story creation and dev-story execution. If one did, renumber `V109`→`V110`→`V111` upward
  accordingly, keeping their *relative* order (FK add, then rename, then seed-id fix — AC10's guard query
  references `session.drills`/`drill_video_refs`/`homework_assignments`, none of which AC7 or AC8 restructure,
  so the three are independent of each other's content, just not of the numbering sequence).
- **AC8's constraint names are an assumption, not a certainty.** `drills_library_type_check` is Postgres's
  standard auto-generated name for an unnamed single-column CHECK, but confirm it against a live DB (`\d
  session.drills` in `psql`, or query `information_schema.check_constraints`) before running the migration
  in any environment — if wrong, the `DROP CONSTRAINT` fails loudly, which is the correct, safe failure
  mode; do not guess a second time and silently swallow the error.
- **AC5's lock scope:** lock only the `Drill` row, not `DrillVideoRef` — `Drill` is the entity every other
  guard in this class already loads first (`drillRepository.findById(drillId)`), so locking it serializes
  every caller touching that drill's video state through one row, which is sufficient; there is no
  need for a second lock on `drill_video_refs` itself.
- **AC9 uses an emit-and-let-the-parent-decide design, not a direct store call from `DrillCard`/
  `DrillDetailPanel`.** This was a correction made after story-review: the original draft's single
  `sessionStore.fetchDrills(...)` call would have silently no-op'd for the `locker-room` context
  (`homeworkStore`, keyed by `playerId`) and the `SUGGESTED`-tab context (`builderStore.suggestedDrills`).
  Each of the 5 wiring sites in AC9's text calls whatever refresh action that specific page already owns —
  don't collapse this back to one shared handler across pages, the whole point is that the correct refresh
  action differs by page.
- **AC10 carries real, accepted risk** the project owner was warned about and chose to accept — the
  abort-on-live-reference guard is the safety net, not a guarantee of success in every environment. If it
  fires anywhere, stop and report rather than trying to force the migration through.
- Backend: follow `docs/validation-strategy.md` — targeted `mvn -o test -Dtest=X` runs only; never run
  `mvn verify` locally; GitHub CI (triggered on PR) is the sole full-verification gate.
- Frontend: no test runner exists in this repo (standing gap, multiple prior ledger items) — every frontend
  change in this story (AC4, AC9) needs manual browser verification, called out per-AC above.

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/session/service/SessionTemplateService.java` — AC1.
- `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java` — AC2.
- `src/test/java/com/softropic/skillars/platform/session/api/SessionBuilderResourceIT.java` — AC2, AC3.
- `src/test/java/com/softropic/skillars/platform/session/api/SessionTemplateResourceIT.java` — AC1, AC8.
- `src/frontend/src/components/session/DrillCard.vue` — AC4, AC8, AC9.
- `src/frontend/src/components/session/DrillDetailPanel.vue` — AC8, AC9.
- `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java` — AC5, AC8.
- `src/main/java/com/softropic/skillars/platform/session/repo/DrillRepository.java` — AC5.
- `src/test/java/.../session/service/DrillUploadServiceTest.java`, a new `DrillUploadServiceConcurrencyIT`
  — AC5, AC8.
- `src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java` — AC6.
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java`,
  `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java` — AC6 (new batch
  method).
- `src/main/resources/db/migration/V109__homework_assignments_pack_id_fk.sql` (new) — AC7.
- `src/main/resources/db/migration/V110__drill_library_type_private_rename.sql` (new) — AC8.
- `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java` — AC8.
- `src/test/java/com/softropic/skillars/platform/session/api/DrillLibraryResourceIT.java`,
  `DrillUploadResourceIT.java`, `DrillTagResourceIT.java`, `HomeworkResourceIT.java`,
  `src/test/java/.../session/service/DrillLibraryServiceTest.java` — AC8 (literal-value sweep).
- `src/frontend/src/components/session/DrillSuggestionPanel.vue` — AC9 (`video-error` passthrough emit).
- `src/frontend/src/pages/coach/DrillLibraryPage.vue`, `src/frontend/src/pages/coach/SessionBuilderPage.vue`,
  `src/frontend/src/pages/player/PlayerLockerRoomPlaceholderPage.vue` — AC9 (`@video-error` wiring, one
  handler per page's own existing refresh action; no shared/new store field).
- `src/main/resources/db/migration/V111__seed_drill_deterministic_ids.sql` (new) — AC10.
- `src/main/java/com/softropic/skillars/platform/session/contract/CreateSessionPlanRequest.java`,
  `UpdateSessionPlanRequest.java` — AC11 (new validator).
- `src/main/java/com/softropic/skillars/platform/session/service/DrillSuggestionService.java` — AC11.
- `_bmad-output/implementation-artifacts/deferred-work.md` — AC12 (14 closures).

### References

- `src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java:33-38` and
  `CoachProfileService.java:247-251` — AC5's exact lock+refresh idiom precedent.
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPurchaseLockContentionIT.java`
  — AC5's concurrency-test structural precedent.
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java:447-451` — AC3's
  exact `assertThatThrownBy` + `HttpClientErrorException` + response-body-contains assertion shape.
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:267-276` — confirms `helpCode`
  is the top-level JSON field name for `OperationNotAllowedException`, sourced from `SessionErrorCode.name()`.
- `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql:100-102` — AC7's cross-schema FK
  precedent (`booking.bookings → payment.session_pack_purchases`).
- `src/main/resources/db/migration/V107__coach_pricing_session_duration_not_valid.sql`,
  `V108__...validate.sql` — the established drop-and-re-add-under-a-new-migration-number convention for a
  constraint that already shipped in an applied migration (referenced for reasoning, not literally
  followed in AC8 — that constraint had an explicit name; `drills_library_type_check` does not, so AC8's
  migration must first confirm the auto-generated name rather than assume an explicit one).
- `skillars-deferred-74-...md` — immediately preceding story; established that the module was thin and set
  up this story's decision-gathering framing.

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5), via `/bmad-dev-story`.

### Debug Log References

None — no failures required debug-log capture beyond what's already narrated in Completion Notes below
(each test failure encountered during implementation was root-caused and fixed in the same session; see
the AC-by-AC narrative below for what each one was).

### Completion Notes List

All 12 ACs implemented and verified with targeted tests (never `mvn verify` — per
`docs/validation-strategy.md`). Full regression sweep at the end: 420 tests across the `session` and
`payment` packages, 59 across `marketplace`, all green; `npx eslint src/` (whole frontend tree) clean.

Beyond straightforward implementation, five things were found during development that the story text
didn't anticipate — all investigated against live source before acting, not assumed:

1. **AC3's `helpCode` claim was wrong.** The story asserted `ApiAdvice.operationDeniedHandler` puts the
   `SessionErrorCode` name at the top-level JSON `helpCode` field. Running the new tests immediately
   proved otherwise: `helpCode` is actually a random support/correlation id (e.g. `"gwrRxGc"`); the real
   error code lives at `errorMsg.errorKey`. Fixed both AC2's and AC3's test assertions to check
   `"errorKey":"SESSION_PLAN_LOCKED"` instead. (`RescheduleResourceIT`'s cited precedent, re-checked,
   actually asserts a bare error-code string, not a `"helpCode":"..."` shape — the story misread it.)

2. **AC6's draft batch query was wrong on two counts**, not just "needs verification" as story-review
   flagged: it referenced a `SessionPackPurchase.status` field that doesn't exist (the entity has no
   `status` field at all), and it omitted the real `findActivePacks` query's `pausedUntil` filter, which
   would have wrongly counted a paused pack as active. Both fixed before writing the repository method.

3. **AC9's entire design was unsound for 2 of `DrillCard`'s 4 render contexts**, found independently
   beyond what story-review caught: a single hardcoded `sessionStore.fetchDrills(...)` call would have
   silently no-op'd on `PlayerLockerRoomPlaceholderPage.vue` (reads from `homeworkStore`, keyed by
   `playerId`) and on `SessionBuilderPage.vue`'s `SUGGESTED` tab (reads from
   `builderStore.suggestedDrills`). Redesigned around an emitted `video-error` event, with each of 5
   render sites wiring to whatever refresh action that page already owns. Also found
   `DrillDetailPanel.vue` is a single shared instance reused across different drills (not remounted per
   drill), so its once-per-mount emit guard needed a `watch(() => props.drill?.id, ...)` reset the
   original design didn't have — without it, one video error on one drill would have silently suppressed
   recovery for every drill viewed afterward on that page for the rest of the session.

4. **AC8's rename broke a partial unique index the story never mentioned.** `V78__drill_dedup_unique.sql`
   (an already-applied, unmodifiable migration) has `CREATE UNIQUE INDEX idx_drills_coach_name_unique ON
   session.drills(owner_coach_id, name) WHERE library_type = 'COACH'`. After the `'COACH'`→`'PRIVATE'`
   rename, that `WHERE` predicate would match zero rows forever, silently disabling coach-drill
   duplicate-name prevention entirely — caught by
   `DrillLibraryResourceIT.cloneTwoDifferentDrillsWithSameName_secondReturns409` actually failing (no
   409) when the full session-package regression ran. Fixed by adding a `DROP INDEX` +
   `CREATE INDEX ... WHERE library_type = 'PRIVATE'` to `V110`, mirroring V107/V108's own
   drop-and-recreate-under-a-new-migration-number convention. Also found `HomeworkResourceIT.java`'s
   fixture inserted a `homework_assignments` row with a random, non-matching `packId` — harmless before
   AC7's FK, but would have broken every test in that file once the FK was live; fixed to use `null`.

5. **AC12's Def14 closure needed correcting, not just applying.** The story's draft said "append
   `[CLOSED by skillars-deferred-75 AC5]` to every Def14 occurrence." Investigating what the per-`Drill`
   row lock actually covers: it closes the *same-drillId* double-call race
   (`DrillUploadServiceConcurrencyIT` proves this), but Def14's own original wording is specifically about
   *different* drills sharing one `videoId` via `drill_video_refs` — confirmed reachable today through
   `DrillLibraryService.cloneDrill` (:134-136), which copies a source drill's video ref onto its clone.
   Two different `drillId`s take two different row locks and do not serialize against each other, so that
   variant is genuinely still open. All 3 Def14 occurrences in the ledger now say so explicitly instead of
   claiming full closure.

Two things the story got right that were still worth independently re-confirming before trusting:
`getBookingSnapshot` does return `Optional` (AC2), and there is no Java `LibraryType` enum anywhere to
miss in the AC8 sweep — both confirmed by direct grep against live source, not assumed from the story text.

Two minor discrepancies, noted but not requiring rework: AC9 said "3 `<video>` elements in
`DrillDetailPanel.vue`" — there are only 2 (mobile bottom-sheet + desktop dialog); both got the fix. AC8
said "5th argument of `insertDrill(...)` calls" — the real signature's library-type parameter is the 3rd
argument; the correct argument was changed regardless; the position was found from the method signature,
not from the story's count.

### File List

**New files:**
- `src/main/resources/db/migration/V109__homework_assignments_pack_id_fk.sql`
- `src/main/resources/db/migration/V110__drill_library_type_private_rename.sql`
- `src/main/resources/db/migration/V111__seed_drill_deterministic_ids.sql`
- `src/main/java/com/softropic/skillars/platform/session/contract/ValidFocusCode.java`
- `src/main/java/com/softropic/skillars/platform/session/contract/ValidFocusCodeValidator.java`
- `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadServiceConcurrencyIT.java`
- `src/test/java/com/softropic/skillars/platform/session/service/SeedDrillDeterministicIdsIT.java`

**Modified files:**
- `src/main/java/com/softropic/skillars/platform/session/service/SessionTemplateService.java` (AC1)
- `src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java` (AC2)
- `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java` (AC5, AC8)
- `src/main/java/com/softropic/skillars/platform/session/repo/DrillRepository.java` (AC5)
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachSubscriptionTier.java` (AC5)
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java` (AC6)
- `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java` (AC6)
- `src/main/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentService.java` (AC6)
- `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java` (AC8)
- `src/main/java/com/softropic/skillars/platform/session/contract/CreateSessionPlanRequest.java` (AC11)
- `src/main/java/com/softropic/skillars/platform/session/contract/UpdateSessionPlanRequest.java` (AC11)
- `src/main/java/com/softropic/skillars/platform/session/service/DrillSuggestionService.java` (AC11)
- `src/test/java/com/softropic/skillars/platform/session/api/SessionTemplateResourceIT.java` (AC1)
- `src/test/java/com/softropic/skillars/platform/session/api/SessionBuilderResourceIT.java` (AC2, AC3, AC11)
- `src/test/java/com/softropic/skillars/platform/session/service/SessionPlanServiceTest.java` (AC2)
- `src/test/java/com/softropic/skillars/platform/session/service/DrillUploadServiceTest.java` (AC5, AC8)
- `src/test/java/com/softropic/skillars/platform/session/service/HomeworkAssignmentServiceTest.java` (AC6)
- `src/test/java/com/softropic/skillars/platform/session/api/HomeworkResourceIT.java` (AC7, AC8)
- `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java` (AC8)
- `src/test/java/com/softropic/skillars/platform/session/api/DrillTagResourceIT.java` (AC8)
- `src/test/java/com/softropic/skillars/platform/session/api/DrillLibraryResourceIT.java` (AC8)
- `src/test/java/com/softropic/skillars/platform/session/service/DrillLibraryServiceTest.java` (AC8)
- `src/frontend/src/components/session/DrillCard.vue` (AC4, AC8, AC9)
- `src/frontend/src/components/session/DrillDetailPanel.vue` (AC8, AC9)
- `src/frontend/src/components/session/DrillSuggestionPanel.vue` (AC9)
- `src/frontend/src/pages/coach/DrillLibraryPage.vue` (AC9)
- `src/frontend/src/pages/coach/SessionBuilderPage.vue` (AC9)
- `src/frontend/src/pages/player/PlayerLockerRoomPlaceholderPage.vue` (AC9)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC12)

## Change Log

- 2026-08-26: Initial draft produced. Every candidate item was independently re-verified against live
  current source (not trusted from ledger text): 2 items found already stale/fixed unannotated
  (`WrapUpSequence.vue` variant, `DrillDetailPanel.vue` FeatureGatedException catch), 1 item found worse
  than described (`DrillCard.vue`'s tag-edit chip is already live-broken on
  `PlayerLockerRoomPlaceholderPage.vue`, not merely hypothetical), 1 item found based on a wrong premise
  (`homework_assignments.pack_id` still points at a real, live table). AC11 emerged from investigation, not
  the ledger's own framing — `computeFocusScore` is a real switch, not a stub as filed, and the real gap is
  a missing request-boundary validator, not a scoring-algorithm gap. This draft's own "Why this story
  exists" section incorrectly claimed the seven decisions below had already been gathered from the project
  owner — they had not; see the 2026-08-27 entry.
- 2026-08-27: Corrected the process narrative in "Why this story exists" (the draft's claims of an
  already-completed decision round and of a `skillars-deferred-74`-set bundling precedent were both false —
  neither happened; `skillars-deferred-74` escalated into this module from an unrelated one and says
  nothing about bundling it). Then ran the actual decision round with the project owner, who confirmed all
  seven items, every one landing on the option the draft had proposed: booking-terminal-status
  session-lock guard (AC2), full lock-based DrillUploadService concurrency fix (AC5),
  `homework_assignments` FK addition with `ON DELETE SET NULL` (AC7), `DrillCard` context-gating fix (AC4),
  `COACH`→`PRIVATE` `library_type` rename (AC8), signed-URL refetch-on-error (AC9), and a deterministic-id
  fix migration for the V39 seed drills accepting the reference-remapping risk (AC10). No AC text changed
  as a result — the technical content was sound; only the narrative claiming the decisions predated this
  entry was false. AC12 closes 14 ledger items with citations. Status confirmed `ready-for-dev`.
- 2026-08-27: `story-review.md` audit applied. Correctly flagged AC4's `context` prop as needing
  verification (resolved false — Vue prop default is `'library'`, never `undefined`; all 4 call sites pass
  a literal string; the identical `context !== 'locker-room'` idiom already works one block above in the
  same file). Independently re-verified during this pass (beyond what story-review checked) and found AC9's
  original design was actually broken, not just under-verified: a hardcoded `sessionStore.fetchDrills(...)`
  call from `DrillCard.vue` would have silently no-op'd for 2 of its 4 render contexts
  (`PlayerLockerRoomPlaceholderPage.vue` reads from `homeworkStore`, keyed by `playerId`; `SessionBuilderPage.vue`'s
  `SUGGESTED` tab reads from `builderStore.suggestedDrills`) — redesigned AC9 around an emitted `video-error`
  event with each of the 5 render sites wiring to whatever refresh action that page already owns, rather
  than one hardcoded store call. Also found AC6's draft batch query referenced a `SessionPackPurchase.status`
  field that doesn't exist and omitted the real `findActivePacks` query's `pausedUntil` filter — corrected.
  Confirmed correct with no changes needed: AC2 (`getBookingSnapshot` does return `Optional`), AC8 (no Java
  `LibraryType` enum exists to miss), AC10 (all 20 `trans_key` values match live `V39` exactly, including
  the two with digits an alphabetic-only grep would truncate). AC11's "keep in one place" instruction
  replaced with a concrete location (`DrillSuggestionService.KNOWN_FOCUS_CODES`). Status remains
  `ready-for-dev`.
- 2026-08-27: `dev-story` implementation complete, status review. All 12 ACs shipped; 3 new Flyway
  migrations (V109-V111); 420 targeted tests green across the `session` and `payment` packages (59 more
  in `marketplace`), `npx eslint src/` clean on the whole frontend tree. Five issues found and fixed
  during implementation, beyond what the story text or story-review anticipated: AC3's `helpCode`/
  `errorKey` claim was backwards (fixed both new test assertions); AC6's draft batch query referenced a
  nonexistent `SessionPackPurchase.status` field and missed the real `pausedUntil` filter (fixed before
  writing it); AC9's design was unsound for 2 of 4 render contexts and `DrillDetailPanel.vue` needed a
  drill-change reset for its emit guard it wasn't given (redesigned); AC8's rename silently broke
  `V78`'s partial unique index and a `HomeworkResourceIT` fixture (both fixed, one via a new `V110`
  addition); AC12's Def14 ledger closure was corrected from a blanket claim to an accurate same-drill-only
  scope. Full detail in Completion Notes above.
