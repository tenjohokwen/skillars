# Story Deferred-52: Video Quota-Release Transaction Isolation & GDPR Export Booking De-Duplication

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want `VideoService.failTranscoding()` and `AdminVideoService.deleteVideo()` to release quota outside
the transaction that commits their state change (mirroring the already-established split-transaction
pattern this codebase uses elsewhere), and `GdprExportService.buildBookings()` to deduplicate bookings
by id rather than by Java object identity,
so that a transient quota-release failure can no longer roll back a video's terminal state transition,
and a self-registered player's GDPR export can no longer silently double-list their own bookings.

### Why this story exists

`_bmad-output/implementation-artifacts/deferred-work.md` (1641 lines at the time this story was created)
was re-mined end to end. The most recently active area of the ledger — everything from
`skillars-deferred-34` onward — has been repeatedly re-mined by each immediately-preceding story's own
code review and is confirmed thin: `skillars-deferred-49`'s own creation note states the ledger was
"re-mined in full" and found dry of small/decision-light items, and `skillars-deferred-50`'s code review
left only two residuals, one needing a `CoachProfileService` locking-strategy decision (not picked up
here — see "Deliberately not picked up" below) and one single-line test-argument-verification nit (too
small alone to justify a story). This story instead draws from an **older, never-revisited** part of the
ledger (pre-`skillars-deferred-34`, spanning 2026-06-22 through 2026-06-30) that no recent pass had
touched. Both source items were re-verified against the live repository during this story's creation, not
trusted from ledger text — full detail in each AC below.

**Numbering note:** `skillars-deferred-51` already exists (Gemini truncation safety, moderation-queue E2E
coverage, and `BookingServiceTest` injection hygiene — sourced from the `skillars-deferred-16`-era review
section around `deferred-work.md`'s D4–D7 items). This story is numbered 52 to avoid colliding with it.

- **D1 (this story's AC1) — `VideoService.failTranscoding()` is `@Transactional`, so a `quotaProvider
  .release()` failure rolls back the FAILED state transition it just committed in the same method.**
  Sourced from `deferred-work.md`, section `## Deferred from: code review of skillars-6-2 pass 5
  (2026-06-22)`, item **Def24**: *"`failTranscoding()` state-transition rollback on
  `quotaProvider.release()` exception — `failTranscoding()` is `@Transactional`; if `QuotaService.release()`
  throws (DB connection loss), the entire TX rolls back including `transitionOperationalState(FAILED)`,
  leaving the video in `PROCESSING`. ... Architectural fix: separate state transition and quota release
  into independent TXs (same pattern as `completeTranscoding()`)."* Re-verified live at
  `VideoService.java:391-411`: the method still carries `@Transactional` and the quota release still
  happens inside it, unchanged since the item was filed.
- **D2 (this story's AC2) — `AdminVideoService.deleteVideo()` has the identical anti-pattern, not
  previously tracked under this name.** Found independently while verifying D1 above: `deleteVideo`
  (`AdminVideoService.java:45-80`) calls `transactionTemplate.execute(...)` to atomically write the
  `DELETED` state and expire any `PENDING` upload session — and calls `quotaProvider.release(...)`
  **inside that same transaction block** (`:68`). A release failure there rolls back the DELETED write
  the same way Def24 describes for `failTranscoding`. This is the same bug class, in a second file, that
  the original ledger item never named.
- **D3 (this story's AC3) — `GdprExportService.buildBookings()`'s `.stream().distinct()` relies on
  `Booking`'s absent `equals()`/`hashCode()` override, so it silently fails to deduplicate.** Sourced
  from `deferred-work.md`, section `## Deferred from: code review of skillars-10-4-gdpr-data-tools-account-deletion
  (2026-06-30)`, item **D2**: *"`.distinct()` on Booking list may silently no-op — if `Booking` entity
  doesn't override `equals()`/`hashCode()`, stream `.distinct()` uses object identity and won't
  deduplicate. Unlikely to manifest given role separation..."* Re-verified live: `Booking.java` (`:19,24`)
  has no `@EqualsAndHashCode`/`@Data`/manual override — it is a bare `@Entity`, so `equals()`/`hashCode()`
  are `Object`'s reference-identity defaults. **The ledger's own "unlikely to manifest given role
  separation" framing is outdated**: this codebase now has a real self-registration flow
  (`skillars-uat-5`) where a self-booking adult player's bookings carry `parentId == playerId == their own
  userId` (the established "opaque id" design — the player's own userId routes through the existing
  `parent_id`-keyed checks). For such a user, `buildBookings` (`GdprExportService.java:115-126`) calls
  **both** `findAllByParentIdOrderByRequestedStartTimeAsc(userId)` and, since
  `user.getSkillarsRole() == SkillarsRole.PLAYER`, `findAllByPlayerId(userId)` — two separate repository
  calls (no shared `@Transactional`/persistence context anywhere in this class — confirmed zero
  `@Transactional` annotations in the file by grep) each returning a **new, distinct Java object** for
  the same underlying booking row. Reference-identity `.distinct()` cannot catch this: a self-registered
  player's GDPR export would list every one of their own bookings **twice**.

**Deliberately not picked up in this pass** (found while re-mining but out of this story's scope):
- `skillars-deferred-6-3`'s companion item **RW3** (`WebhookEventProcessorScheduler.java`, the
  `encoding.failed`-while-`SCANNING` branch) named the *same* anti-pattern Def24 describes, but at a
  different call site. Re-verified live: that branch (`WebhookEventProcessorScheduler.java:171-190`
  today) **already** separates the state transition (its own `transactionTemplate.execute(...)` call)
  from the quota release (`releaseQuota(videoId, freshAssetId)` at `:213`, called after the transaction
  returns, with no `@Transactional` wrapper) — this specific call site was already fixed by an earlier,
  unannotated change. RW3's own text raises a second, narrower concern this existing split does **not**
  address: *"if release throws, quota is permanently leaked"* — once state and release are split, a
  release failure no longer corrupts the state, but nothing retries the release itself (unlike
  `UploadSessionExpiryScheduler.processExpired()`, which explicitly catches and retries the release next
  cycle). This residual applies equally to the newly-split code this story's AC1/AC2 produce. It is a
  resilience/retry design question (should every split-quota-release site get the same catch-and-retry
  wrapper `UploadSessionExpiryScheduler` uses, and where does that retry loop live?) rather than a
  three-line mechanical fix, so it is **not** decided here — see AC4 for how it is left in the ledger.
- The `skillars-deferred-50` residuals (`acceptReschedule`'s unlocked-read TOCTOU race needing a
  `CoachProfileService` locking-strategy decision; `duplicateNextWeek`'s DST-shift-of-duplicated-time
  quirk with no proposed fix) — both explicitly flagged in that story's own Change Log as needing a
  design decision this kind of bundled small-fix story should not make ad hoc. Still open, still
  untouched.
- The `skillars-deferred-49`/`-50` review's DRY-duplication nit and `isSlotWithinAvailabilityWindow`'s
  cross-midnight-window limitation — both already reasoned as accepted/out-of-scope by those stories.

## Acceptance Criteria

1. **AC1 — `VideoService.failTranscoding()` releases quota outside the transaction that commits the
   FAILED state transition.**
   - File: `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java:391-411`.
   - Current shape (method-level `@Transactional`, quota release inside it):
     ```java
     @Observed(name = "video.transcoding.failed")
     @Transactional
     public void failTranscoding(UUID videoId) {
         String providerAssetId = videoRepository.findById(videoId)
             .map(Video::getProviderAssetId)
             .orElse(null);

         videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);

         UploadSession session = (providerAssetId != null)
             ? uploadSessionRepository.findFirstByVideoIdAndProviderUploadIdOrderByCreatedAtDesc(videoId, providerAssetId).orElse(null)
             : uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId).orElse(null);
         if (session != null && session.getReservationHandle() != null) {
             quotaProvider.release(session.getReservationHandle());
         } else {
             log.warn("No reservation handle found for videoId={} during transcoding failure — quota not released", videoId);
         }
     }
     ```
   - Change to (remove the method-level `@Transactional`; wrap only the read + state transition in
     `transactionTemplate.execute(...)`, the field already present on this class and already used by
     `completeTranscoding()` for the identical phase-split shape at `:339-408`):
     ```java
     @Observed(name = "video.transcoding.failed")
     public void failTranscoding(UUID videoId) {
         // Phase 1: read providerAssetId and transition to FAILED, in one short transaction.
         String providerAssetId = transactionTemplate.execute(status -> {
             String assetId = videoRepository.findById(videoId)
                 .map(Video::getProviderAssetId)
                 .orElse(null);
             videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED);
             return assetId;
         });

         // Phase 2: release quota OUTSIDE any transaction — mirrors the pattern already established by
         // completeTranscoding() and by UploadSessionExpiryScheduler.processExpired() (explicitly
         // commented there as "AC-5: QuotaProvider.release() OUTSIDE any @Transactional boundary"), so a
         // release() failure (e.g. QuotaService.release()'s IllegalArgumentException on a malformed
         // reservation handle, or a transient DB error) can no longer roll back the FAILED transition
         // committed above. Anchor to providerAssetId to avoid releasing the retry session's quota when a
         // late webhook fires for the original failed upload after a retryUpload() has started.
         UploadSession session = (providerAssetId != null)
             ? uploadSessionRepository.findFirstByVideoIdAndProviderUploadIdOrderByCreatedAtDesc(videoId, providerAssetId).orElse(null)
             : uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId).orElse(null);
         if (session != null && session.getReservationHandle() != null) {
             quotaProvider.release(session.getReservationHandle());
         } else {
             log.warn("No reservation handle found for videoId={} during transcoding failure — quota not released", videoId);
         }
     }
     ```
   - No new fields/imports: `transactionTemplate` is already a constructor-injected field on this class
     (`VideoService.java:55`, used by `completeTranscoding`).
   - **Unit test** in `src/test/java/com/softropic/skillars/platform/video/service/VideoServiceTest.java`
     (this file exists; it currently has **zero** tests for `failTranscoding` — confirmed by grep). Add
     a test proving the split: mock `transactionTemplate.execute(any())` to actually invoke the passed
     `TransactionCallback` (mirroring however this file's existing `completeTranscoding` tests already
     stub `transactionTemplate` — check that pattern first and reuse it, do not invent a new one), then
     assert `videoLifecycleService.transitionOperationalState(videoId, OperationalState.FAILED)` was
     called before `quotaProvider.release(...)` (an `InOrder` verification — this file already imports
     `org.mockito.InOrder`, confirming that pattern is established here) and that both happen even when
     mocked separately. Also add a regression test that specifically proves the fix: stub
     `quotaProvider.release(...)` to throw, and assert `videoLifecycleService.transitionOperationalState`
     was still invoked with `FAILED` — the pre-fix code could never prove this (an exception thrown from
     inside a real `@Transactional` method propagates and the caller never observes the state as
     committed; the point of this test is to show the two are no longer coupled, not to simulate a real
     rollback with Mockito).

2. **AC2 — `AdminVideoService.deleteVideo()` gets the identical fix: `quotaProvider.release()` moves
   outside the `transactionTemplate.execute(...)` block that writes `DELETED` and expires the pending
   session.**
   - File: `src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java:45-80`.
   - Current shape (release call at `:68`, inside the same `transactionTemplate.execute` block as the
     `DELETED` write):
     ```java
     transactionTemplate.execute(status -> {
         Video v = videoRepository.findById(videoId)
                 .orElseThrow(() -> new VideoNotFoundException(videoId));
         v.setOperationalState(OperationalState.DELETED);
         videoRepository.save(v);

         uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)
                 .filter(s -> s.getStatus() == UploadSessionStatus.PENDING)
                 .ifPresent(s -> {
                     quotaProvider.release(s.getReservationHandle());
                     s.setStatus(UploadSessionStatus.EXPIRED);
                     uploadSessionRepository.save(s);
                 });
         return null;
     });
     ```
   - Change to (transaction returns the pending session it just expired, if any; release happens after
     the transaction returns):
     ```java
     // Phase 1 (atomic): set DELETED + mark any PENDING session EXPIRED. No quotaProvider call inside
     // this transaction, so a release() failure can no longer roll back the DELETED/EXPIRED writes.
     UploadSession expiredSession = transactionTemplate.execute(status -> {
         Video v = videoRepository.findById(videoId)
                 .orElseThrow(() -> new VideoNotFoundException(videoId));
         v.setOperationalState(OperationalState.DELETED);
         videoRepository.save(v);

         return uploadSessionRepository.findFirstByVideoIdOrderByCreatedAtDesc(videoId)
                 .filter(s -> s.getStatus() == UploadSessionStatus.PENDING)
                 .map(s -> {
                     s.setStatus(UploadSessionStatus.EXPIRED);
                     uploadSessionRepository.save(s);
                     return s;
                 })
                 .orElse(null);
     });

     // Phase 2: release quota OUTSIDE any transaction — same pattern as AC1's VideoService.failTranscoding
     // fix and the already-established UploadSessionExpiryScheduler convention.
     if (expiredSession != null) {
         quotaProvider.release(expiredSession.getReservationHandle());
     }
     ```
   - No new fields/imports: `transactionTemplate`, `quotaProvider`, `uploadSessionRepository`,
     `videoRepository` are all already constructor-injected fields on this class.
   - **Test coverage:** there is currently **no** `AdminVideoServiceTest.java` (confirmed — file does not
     exist) and no unit test anywhere covers `deleteVideo`'s quota-release ordering at all (the existing
     `AdminVideoIT.java` and `VideoPurgedEventIT.java` cover `deleteVideo` end-to-end but were not written
     to assert internal transaction-boundary ordering). Do **not** attempt to prove the rollback bug via
     an `IT` (would need a way to make a real Postgres-backed quota release throw mid-transaction, which
     this codebase has no harness for). Instead, create a new
     `src/test/java/com/softropic/skillars/platform/video/service/AdminVideoServiceTest.java`
     (`@ExtendWith(MockitoExtension.class)`, `@InjectMocks AdminVideoService`, `@Mock` for every
     constructor-injected field this class has), mirroring `VideoServiceTest`'s
     `transactionTemplate.execute(any())`-invokes-the-real-callback stubbing style, with one test proving
     `quotaProvider.release(...)` is called with the expired session's reservation handle **after** the
     transaction returns (an `InOrder` check against the mocked `videoRepository.save(...)` write and the
     `quotaProvider.release(...)` call), and one test proving `deleteVideo` on a video with **no**
     `PENDING` session never calls `quotaProvider.release(...)` at all (`verify(quotaProvider,
     never()).release(any())`) — the existing `.ifPresent(...)`/`.filter(...)` guard's behavior must
     survive the refactor unchanged.

3. **AC3 — `GdprExportService.buildBookings()` deduplicates bookings by id, not by default Java object
   identity.**
   - File: `src/main/java/com/softropic/skillars/platform/admin/service/GdprExportService.java:115-126`.
   - Current shape:
     ```java
     private List<Booking> buildBookings(User user, Long userId) {
         List<Booking> bookings = new java.util.ArrayList<>(
             bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(userId));

         if (user.getSkillarsRole() == SkillarsRole.PLAYER) {
             bookings.addAll(bookingRepository.findAllByPlayerId(userId));
         }

         coachProfileRepository.findByUserId(userId).ifPresent(cp ->
             bookings.addAll(bookingRepository.findAllByCoachId(cp.getId())));

         return bookings.stream().distinct().collect(Collectors.toList());
     }
     ```
   - Change the visibility from `private` to package-private (drop the `private` modifier — needed for
     the direct unit test below; this file has no other package-private methods to mirror, but this
     project's established convention elsewhere is to relax visibility exactly this much for
     testability rather than testing only through a much heavier end-to-end path — e.g.
     `BookingService.isSlotWithinAvailabilityWindow`) and replace the final line's identity-based
     `.distinct()` with an id-keyed dedupe that preserves first-seen order (parent bookings first,
     matching the method's existing sequencing — do not reorder):
     ```java
     java.util.Map<UUID, Booking> byId = new java.util.LinkedHashMap<>();
     for (Booking b : bookings) {
         byId.putIfAbsent(b.getId(), b);
     }
     return new java.util.ArrayList<>(byId.values());
     ```
     Use the file's own existing fully-qualified-inline-class style (`java.util.ArrayList`,
     `java.util.Map` are already used this way elsewhere in this file, e.g. `buildProfile`) rather than
     adding new `import` lines — no new imports are needed for this fix. The unused `Collectors` import
     may become dead after this change; check for other `Collectors` usages in the file before removing
     it (`buildPayments`/`buildMessages` likely still use it — verify, do not remove blindly).
   - **Unit test**: create a new
     `src/test/java/com/softropic/skillars/platform/admin/service/GdprExportServiceTest.java`
     (`@ExtendWith(MockitoExtension.class)`, `@Mock` for `BookingRepository`/`CoachProfileRepository`,
     `@InjectMocks GdprExportService` — the class has many constructor-injected fields via
     `@RequiredArgsConstructor`; only `bookingRepository` and `coachProfileRepository` need real stubbing
     for this method, the rest can stay as Mockito's default nulls/mocks since `buildBookings` never
     touches them). Cover:
     - `buildBookings_selfRegisteredPlayer_sameBookingFromParentAndPlayerQuery_dedupedToOne` — stub
       `findAllByParentIdOrderByRequestedStartTimeAsc(userId)` and `findAllByPlayerId(userId)` to each
       return a **separate `Booking` object instance with the same id** (simulating two distinct
       persistence-context reads of the same row — do not reuse the same Java reference in the stub, or
       the test would pass even without this fix), with `user.getSkillarsRole() ==
       SkillarsRole.PLAYER`, and assert the result contains exactly one entry for that id.
     - `buildBookings_noOverlap_allBookingsPreserved` — distinct ids from each of the three sources,
       assert all survive and none are dropped (guards against an over-aggressive fix that dedupes too
       much).
     - `buildBookings_coachProfileBookingsSameIdAsParentBookings_dedupedToOne` — same shape as the first
       test but via the `coachProfileRepository.findByUserId(...).ifPresent(...)` branch instead of the
       player branch, since a user could in principle also be a coach on the same account (opaque-id
       design, same reasoning) — separate scenario from the player one, do not assume proving one proves
       both.
     - Mutation-check by construction: temporarily reverting the fix to `.stream().distinct()` must make
       the first and third new tests fail (two distinct object instances with the same id are not
       `.equals()` under Java's default identity semantics) while the second continues to pass — confirm
       this before finalizing, per this project's established red-then-green verification convention.

4. **AC4 — Ledger hygiene.** In `deferred-work.md`:
   - Tag Def24 (`## Deferred from: code review of skillars-6-2 pass 5 (2026-06-22)`) →
     `` `[CLOSED by skillars-deferred-52 AC1]` `` with a one-line closure note (matching this ledger's
     established `[CLOSED by ... ACn]` annotation convention used throughout the file), and keep the
     original text below it per convention.
   - Add a new item to that same section (or a new one directly below it) recording D2's fix:
     `AdminVideoService.deleteVideo()`'s identical anti-pattern, found during this story's creation and
     closed by AC2 — since it was never previously tracked under its own entry, do not retroactively
     invent a fictitious original filing date; date it to this story's creation.
   - Tag RW3 (`## Deferred from: post-implementation review of skillars-6-3 (2026-06-22)`) with an
     honest, split annotation: the "outside transaction" half is `` `[CLOSED — already fixed at an
     unannotated earlier point, confirmed by skillars-deferred-52 story creation]` ``; the "if release
     throws, quota is permanently leaked" half is **not** closed by anything in this story and should be
     **re-filed** as its own new item (not deleted) under a new heading
     `## Deferred from: skillars-deferred-52 story creation (<this story's creation date>)`, worded to
     cover all four now-split-or-already-split call sites uniformly (`VideoService.failTranscoding`,
     `AdminVideoService.deleteVideo`, `WebhookEventProcessorScheduler`'s SCANNING branch, and the existing
     `UploadSessionExpiryScheduler` — the last one already has a catch-and-retry-next-cycle mitigation the
     other three lack) so a future pass can decide, as a real design question, whether all four need the
     same retry treatment or whether `release()`'s own idempotency (confirmed at
     `QuotaService.java:122-137`: a repeat `release()` call on an already-`RELEASED`/`COMMITTED`
     reservation is a safe no-op) already makes the existing webhook-retry/scheduler-retry machinery
     sufficient without new code.
   - Tag D2 (`## Deferred from: code review of skillars-10-4-gdpr-data-tools-account-deletion
     (2026-06-30)`) → `` `[CLOSED by skillars-deferred-52 AC3]` ``, and correct its own text inline: the
     item's "unlikely to manifest given role separation" framing is outdated (superseded by
     `skillars-uat-5`'s self-registration flow) — note this in the closure annotation so a future reader
     does not re-read the stale framing as still accurate.

## Tasks / Subtasks

- [ ] Task 1: `VideoService.failTranscoding()` transaction split (AC: #1)
  - [ ] 1.1 Remove the method-level `@Transactional`, wrap the read + state transition in
    `transactionTemplate.execute(...)`, move the quota-release block after it returns, per AC1's snippet.
  - [ ] 1.2 Add the new `VideoServiceTest` coverage described in AC1 (InOrder assertion; regression test
    proving the state transition and the release are no longer coupled).
  - [ ] 1.3 Run `mvn -o test -Dtest=VideoServiceTest` and confirm green.
- [ ] Task 2: `AdminVideoService.deleteVideo()` transaction split (AC: #2)
  - [ ] 2.1 Refactor `deleteVideo` per AC2's snippet — transaction returns the expired session (or
    `null`), release happens after.
  - [ ] 2.2 Create `AdminVideoServiceTest.java` with the two tests described in AC2.
  - [ ] 2.3 Run `mvn -o test -Dtest=AdminVideoServiceTest` and confirm green. Also run
    `mvn -o integration-test -Dit.test=AdminVideoIT,VideoPurgedEventIT` to confirm the existing
    end-to-end `deleteVideo` coverage still passes unchanged (behavior-preserving refactor).
- [ ] Task 3: `GdprExportService.buildBookings()` id-based dedupe (AC: #3)
  - [ ] 3.1 Drop `buildBookings`'s `private` modifier; replace `.stream().distinct()` with the
    `LinkedHashMap`-based dedupe per AC3's snippet. Verify whether `Collectors` is still used elsewhere
    in the file before touching its import.
  - [ ] 3.2 Create `GdprExportServiceTest.java` with the three tests described in AC3. Follow the
    red-then-green check: confirm the first and third tests fail against the pre-fix `.distinct()` code,
    then pass once the fix lands.
  - [ ] 3.3 Run `mvn -o test -Dtest=GdprExportServiceTest` and confirm green. Also run
    `mvn -o integration-test -Dit.test=GdprExportIT` to confirm the existing REST-layer coverage is
    unaffected (that IT does not exercise `buildBookings`'s content directly, so no behavior change is
    expected there — it exists to catch any accidental compile/wiring break from the visibility change).
- [ ] Task 4: Ledger hygiene (AC: #4) — apply all four annotations described in AC4 to
  `deferred-work.md`, including filing the new re-scoped RW3 residual item.

## Dev Notes

- **This story bundles three independent, decision-light findings — it is not a single coherent
  feature.** AC1 and AC2 share a pattern (and could be implemented in either order, or in parallel) but
  touch different files with no code dependency between them. AC3 is fully independent of AC1/AC2 (a
  different module, a different bug class — dedup, not transaction boundaries).
- **Reuse existing patterns, do not invent new ones.** AC1/AC2 both mirror
  `VideoService.completeTranscoding()`'s already-shipped phase-split shape
  (`transactionTemplate.execute(...)` for the atomic DB phase, plain sequential code for the
  no-transaction phase) and `UploadSessionExpiryScheduler.processExpired()`'s explicit
  "AC-5: `QuotaProvider.release()` OUTSIDE any `@Transactional` boundary" convention — do not design a
  new transaction-splitting idiom. AC3's dedupe-by-id approach matches how JPA entities without
  `equals()`/`hashCode()` overrides are conventionally deduplicated in this codebase's style (plain
  `LinkedHashMap`, no Lombok `@EqualsAndHashCode` added to the shared `Booking` entity — changing that
  entity's equality semantics has a much wider blast radius across the codebase than this story's scope
  justifies, and was deliberately not chosen for that reason).
- **Do not add `@EqualsAndHashCode`/`equals()`/`hashCode()` to `Booking.java` itself.** This was
  considered and rejected: `Booking` is used across many services or repositories where identity-based
  semantics (or field-based semantics using mutable fields) could have unreviewed consequences (e.g. any
  `Set<Booking>` usage, JPA dirty-checking assumptions). Fixing the one call site that actually needs
  id-based comparison (`buildBookings`) is the narrower, safer fix, matching this project's repeated
  preference for scoped local fixes over entity-wide changes (e.g. the `deferred-27`/`-29` immutable
  identity-columns work went the other direction — narrow, targeted changes, not broad entity redesigns).
- **`IT`-execution gotcha (recorded by prior stories, still applies):** `*IT` classes run under
  `maven-failsafe-plugin`, bound to `integration-test`/`verify`, **not** `mvn test`. Use
  `mvn -o integration-test -Dit.test=<ClassName>` and confirm a `target/failsafe-reports/...txt` report
  was actually written.
- Per `docs/validation-strategy.md`, run targeted verification only — do not run a full `mvn verify`
  unless targeted verification proves insufficient.
- **No frontend changes in this story.** All three ACs are backend-only (production code + tests).
- **AC4's new ledger item (the re-scoped RW3 residual) is intentionally left as an open question, not a
  decision.** Do not resolve "should all four quota-release sites get a catch-and-retry wrapper" as part
  of this story — it spans a scheduler, a webhook processor, and two newly-split service methods, and the
  right answer may differ per call site (e.g. `WebhookEventProcessorScheduler` likely already gets a
  retry via its own webhook max-attempts/backoff machinery in `handleFailure` — verify this claim before
  a future story assumes it, this story's own scope did not require chasing that thread to ground).

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java` — `failTranscoding`
  refactored to the phase-split shape; no new fields/imports (AC1).
- `src/test/java/com/softropic/skillars/platform/video/service/VideoServiceTest.java` — new test(s) for
  `failTranscoding` (file exists, currently has none) (AC1).
- `src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java` — `deleteVideo`
  refactored to the phase-split shape; no new fields/imports (AC2).
- `src/test/java/com/softropic/skillars/platform/video/service/AdminVideoServiceTest.java` — **new file**
  (AC2).
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprExportService.java` — `buildBookings`
  visibility relaxed to package-private, dedupe logic replaced; verify `Collectors` import is still used
  elsewhere in the file before touching it (AC3).
- `src/test/java/com/softropic/skillars/platform/admin/service/GdprExportServiceTest.java` — **new file**
  (AC3).
- `_bmad-output/implementation-artifacts/deferred-work.md` — four annotations/additions (AC4).
- No changes to `WebhookEventProcessorScheduler.java`, `QuotaService.java`, `Booking.java`, or any
  frontend file.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1079, section `## Deferred from:
  code review of skillars-6-2 pass 5 (2026-06-22)`, item Def24 — this story's AC1 source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1091, section `## Deferred from:
  post-implementation review of skillars-6-3 (2026-06-22)`, item RW3 — related but deliberately not
  fully picked up, see "Deliberately not picked up in this pass"]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` line 1138, section `## Deferred from:
  code review of skillars-10-4-gdpr-data-tools-account-deletion (2026-06-30)`, item D2 — this story's AC3
  source]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java:339-411` —
  `completeTranscoding` (the phase-split pattern AC1 mirrors) and `failTranscoding` (AC1's target)]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/UploadSessionExpiryScheduler.java:40-46`
  — the already-shipped "release outside any transactional boundary" convention, with its own AC-5
  comment, that both AC1 and AC2 mirror]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java:45-80` —
  `deleteVideo`, AC2's target]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java:171-236`
  — the `encoding.failed`/SCANNING branch, confirmed already split; `releaseQuota` helper]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java:122-137` —
  `release()`'s idempotency (only transitions ACTIVE→RELEASED, silently ignores COMMITTED/RELEASED),
  relevant to AC4's re-filed residual item]
- [Source: `src/main/java/com/softropic/skillars/platform/admin/service/GdprExportService.java:57-126` —
  `buildExport`/`buildBookings`, AC3's target]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java:19-28` — confirms no
  `equals()`/`hashCode()` override]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy]

## Dev Agent Record

### Agent Model Used

_To be filled in by the dev agent._

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Change |
|---|---|
| 2026-08-21 | Story created via story-creation process, bundling three items re-mined from an older, never-revisited section of `deferred-work.md` (2026-06-22 through 2026-06-30) after confirming the more recently active section (post-`skillars-deferred-34`) is already thin per `skillars-deferred-49`/`-50`'s own creation notes. All three re-verified against live code at creation time: `VideoService.failTranscoding` (`:391-411`) still carries method-level `@Transactional` with the quota release inside it; `AdminVideoService.deleteVideo` (`:45-80`) was found, independently of any ledger entry, to have the identical anti-pattern; `Booking.java` (`:19-28`) confirmed to have no `equals()`/`hashCode()` override, and `GdprExportService.buildBookings` (`:115-126`) confirmed to call both `findAllByParentIdOrderByRequestedStartTimeAsc` and `findAllByPlayerId` for a `PLAYER`-role caller, which collide for a self-registered player whose `parentId == playerId`. One related item (RW3, a different call site with the same pattern) was found already fixed at an unannotated earlier point; its own narrower residual concern (no retry if `release()` itself throws post-split) is re-filed rather than silently closed or silently ignored. Two other candidate areas surfaced during re-mining but explicitly not picked up: `skillars-deferred-40`'s `NeglectedSkillDetectionService` unchunked-loop item (a deliberate, already-reasoned detective-control decision from that story, not a fresh bug) and `ConfigGuardIT`'s shared-row test-isolation hazard (real but low-value/low-probability, not substantial enough to justify inclusion). |
