# Deferred Work

Issues surfaced during code review that were consciously **not** fixed in the story under review.
One bullet = one open item. Grouped by the review that raised it; the heading carries that review's date.

## How to read this file

- **This is a list of open work only.** Items are deleted outright once they are implemented — this file is
  not a history of what was fixed. Absence of an item does not mean it was never raised; check git history
  of this file (or the `skillars-deferred-*.md` stories) for what has been closed.
- **Sections are not in chronological order.** Read the date in each `## Deferred from:` heading.
- **Item ids (`D1`, `W3`, `Def12`, `AD4`…) are only unique within their section.** They are the ids the
  original review used; they repeat across sections and are not global identifiers.
- **`**[AUDIT <date>: …]**` annotations are the only re-verified claims in this file.** Everything else
  — especially any "will be fixed in Story X" / "Epic Y owns this" phrasing — records what was believed at
  review time and has **not** been re-checked. Several such promises turned out to be unkept (see below).
  Verify against the code before trusting an unannotated forward-reference.
- **File paths and line numbers age fast.** They were accurate at the review date in the heading.

## Last audit: 2026-08-04

Scope of that audit, so the gaps in it are explicit:

- Removed every item closed by the shipped `skillars-deferred-1` … `skillars-deferred-10` stories, after
  verifying each fix in code rather than trusting the story's `done` status.
- Removed items whose subject code was deleted by Story 11.3 (the legacy `SessionPackService` /
  `SessionPackResource` / `SessionPackResourceIT` system) and items already marked RESOLVED inline.
- Moved 6 items into `skillars-deferred-11-stripe-card-collection.md` (Stripe card collection, the
  `loadStripe` null-key guard, `payment.store.js` shared flags, the pack-resolution TOCTOU, the
  `sessionPacks` store scoping, and coach-name negative caching).
- Re-verified 13 items that named a future story as their fixer, where that story has since shipped. **Twelve
  of the thirteen were never actually fixed** and now carry an `[AUDIT 2026-08-04: STILL OPEN …]` note; one
  was superseded by a deliberate design decision and is annotated as such.
- **Not audited:** every other forward-reference and every "pre-existing pattern" claim. Those remain
  unverified. The deployment/infrastructure sections (`deploy-*`) were not re-checked against the current
  scripts at all.

### Known tracking defect found by the audit

`skillars-deferred-5` is marked `done` in `sprint-status.yaml`, but the 2026-08-04 audit read its
**AC4 as never implemented** — `ReviewApiAdvice` not covering `platform.admin.api`. Treat other
`done` markers with corresponding care: the general lesson stands even though this specific instance
turned out to be closed.

**Resolved 2026-08-04 (deferred-13):** that reading was wrong by the time it was written. The gap was
already closed by `skillars-deferred-12` — `ReviewApiAdvice.java:26-28` carries
`assignableTypes = AdminReviewResource.class` alongside `basePackages`, confirmed by direct read
during `deferred-13` and re-confirmed by its code review. The `skillars-9-3` section that used to
hold this item (tracked there as D2) was removed by `deferred-13` as already-closed, so the
cross-reference that stood here is gone; the sentence above has been corrected to match. See the
`deferred-13` audit note below for the full list.

## Last audit: 2026-08-05 (deferred-13 code review)

Written by the `deferred-13` **code review** on 2026-08-05, superseding the dev-authored draft of
2026-08-04 that claimed this provenance before any review had run. The item-level claims below were
re-verified by three independent review layers with repository access. Scope, so gaps stay explicit:

- Closed by code in this story: `skillars-deferred-12` D3 (`blockReview()` now uses
  `findByIdForUpdate` + an `ALREADY_BLOCKED` guard, mirroring `approveReview()`); `skillars-9-3` D3
  (`ReviewFlagService.flag()`'s coach-profile lookup now fails closed with
  `COACH_PROFILE_MISSING` instead of silently skipping the self-flag guard); `skillars-10-2` D3
  (enforcement list now batches strike counts via `countByCoachIdInAndCreatedAtAfter`, mirroring
  `CoachSearchService.loadReliabilityStrikes()`).
- Closed by deletion, not by fixing: `skillars-7-3` D2 (`processAdminRefund` removed outright — it
  had zero call sites in `src/main` or `src/test`, and Epic 10 already wired the real admin-refund
  path through `DisputeService.resolveDispute()`, which has auth, amount validation, an
  already-resolved guard, and an audit log entry).
- Deleted as already-closed-or-obsolete, evidence re-verified by direct read on 2026-08-04:
  `skillars-9-3` D1 and D2 (closed by `skillars-deferred-12`: `AdminReviewService.approveReview()`
  has both the pessimistic read and the `ALREADY_APPROVED` guard; `ReviewApiAdvice.java:26-28`
  carries `assignableTypes = AdminReviewResource.class` alongside `basePackages`); the D1 under
  `## Deferred from: code review of skillars-10-1-admin-moderation-queue-message-content-actions
  (2026-06-30)` (obsolete — `V65__messaging_module_init.sql:22` declares `content TEXT NOT NULL`,
  so the null-content branch is unreachable, and `AdminQueueService.buildSummary()` already yields
  `"[message not found]"` rather than `null` via `Optional.map`); `skillars-3-11` D2 (closed by
  `V87__booking_overlap_exclusion_constraint.sql`, which added the DB-level exclusion constraint
  the item asked for — that entry also recorded two app-layer bypass paths, and the constraint
  changed their failure mode rather than fixing them, so the residue was re-opened as its own item.
  **That residue is now closed too**: `skillars-deferred-14` AC4 added the app-layer lock + overlap
  pre-check to both bypass paths, and its `deferred-13` code-review section was deleted with it —
  which is why the cross-reference that used to stand here is gone).
- **Not re-deleted / explicitly left alone:** the separate `## Deferred from: code review of
  skillars-10-1 patches (2026-06-30)` heading's own D1 and D2 (`findBeforePivot`/`findAfterPivot`
  null-pivot and exact-timestamp-collision items) — unrelated to the `buildSummary()` item deleted
  above despite both sections starting with `skillars-10-1`; still open, untouched by this story.
  `skillars-10-2` D1 (`AFTER_COMMIT` listener failure silently drops refunds) — explicitly a
  platform-wide event-reliability concern, too large for this story, left in the file.
- **Not re-checked:** every other forward-reference in the file, and the deployment/infrastructure
  (`deploy-*`) sections again — same gap the 2026-08-04 audit above already flagged.
- **Added by the code review itself (2026-08-05):** three new items under a
  `## Deferred from: code review of skillars-deferred-13-admin-moderation-action-integrity` heading.
  D1 was the substantive one — the Gemini moderation listener could silently revert a committed admin
  BLOCK, which meant `deferred-13` AC1's lock was only half the guarantee it read as.
  **All three were closed by `skillars-deferred-14` and that heading no longer exists** (see the
  deferred-14 audit block below); this bullet is kept only as the record of where they came from.
- **One AC was overridden by the review, not merely implemented:** `deferred-13` AC4 specified
  `409 CONFLICT` for `COACH_PROFILE_MISSING`; the review changed it to `500` (with an ERROR log) on
  the grounds that an orphaned `coach_profiles` row is a data-integrity failure the caller neither
  caused nor can retry away, and a 4xx would hide it from 5xx-keyed alerting. `ALREADY_BLOCKED`
  remains `409`. Recorded here because the story file's AC4 text now differs from what shipped in
  `deferred-13`'s original spec.

## Last audit: 2026-08-05 (skillars-deferred-14 story creation)

Written while scoping `skillars-deferred-14`. Unlike a code-review audit, this one had no independent
review layers — every claim below comes from a direct read of the named file during story creation.
Scope, so the gaps stay explicit:

- **Deleted as already closed, verified by direct read:**
  - `skillars-3-11` D1 (coach-suspension race in `createBookingRequest`) — closed by `skillars-deferred-12`.
    `BookingService.java:198-212` now does `findByIdForUpdate` + `entityManager.refresh(lockedCoach,
    PESSIMISTIC_WRITE)` + a re-check of `SUSPENDED` and the active-status whitelist under that lock.
    That was the only item under its heading, so the heading went with it.
  - The `canonicalTimezone not IANA-validated` bullet under `skillars-3-3` Group A — closed.
    `BookingService.java:178-183` wraps `ZoneId.of(req.canonicalTimezone())` in `try/catch
    (DateTimeException)`. The other two Group A bullets are untouched and still open.
- **Corrected, not deleted:** `skillars-deferred-13` D2's premise is wrong — the 409 mapping it asks
  for has existed since Story 3.11, and the `createBatch` path it names is not reachable by the
  constraint at all. Annotated inline and re-scoped to the real residue (the missing app-layer
  overlap pre-check). Also fixed D3's wrong test-method name (`...returns409...` → `...returns500...`).
- **Closed by shipped code in `skillars-deferred-14` (deleted 2026-08-05, after implementation):**
  `skillars-deferred-13` D1 (moderation listener now takes a locked read and writes only while the
  review is still `PENDING`; mutation-verified — with the guard removed the new
  `ReviewModerationIT#adminBlocksWhileGeminiInFlight_blockSurvivesSafeVerdict` fails with
  `expected BLOCKED but was APPROVED`), D2 (both bypass paths now run the app-layer lock + overlap
  pre-check), D3 (orphan fixture moved to `@AfterEach`), and the `skillars-3-3` Group C
  cross-field-validation bullet (`@AssertTrue isEndAfterStart()` → 400). Both headings became empty
  and were removed.
- **Correction to this audit's own earlier reading of D2.** The entry claimed no 409 mapping existed;
  that was wrong (Story 3.11 wired one). But D2's *conclusion* — that a batch-path overlap surfaces
  as a 500 — turned out to be **right for a reason neither the entry nor this audit had identified**.
  Reproduced before fixing: a batch whose second slot collided returned
  `500 generic.unknown`, because Hibernate defers the batch's UPDATEs to commit, the JDBC batch fails
  as one, and the resulting `DataIntegrityViolationException` carries `constraint [null]` — so
  `ApiAdvice`'s name-keyed `CONSTRAINT_MAPPINGS`/`CONFLICT_CONSTRAINTS` lookup cannot match it and the
  409 mapping never applies. The mapping is real but unreachable on that path. The app-layer
  pre-check now makes it moot for both bypass paths; the constraint remains the backstop.
- **Added by this audit:** one new item under `## Deferred from: skillars-deferred-14 story creation
  (2026-08-05)` — no automatic sweeper exists for bookings stranded in `PAYMENT_PENDING`, and the
  only recovery is a parent-initiated cancel, while the stranded row holds the coach's slot. Found
  while scoping `deferred-14` AC3a, which narrows that window but deliberately does not close it.
- **Examined and deliberately left alone:** `skillars-10-2` D1 (`AFTER_COMMIT` refund drop — still a
  platform-wide event-reliability concern, same reason `deferred-13` left it) and `skillars-8-2`
  D1/D2 (deleted-player `UserNotFoundException` crashing `getConversations()` — real and still open,
  but messaging-module scope).
- **Not re-checked:** every other item in this file. In particular no `deploy-*` section was read —
  the same gap the 2026-08-04 and 2026-08-05 audits below already flagged, now three audits running.

---

## Deferred from: skillars-deferred-14 story creation (2026-08-05)
- D1: **No automatic sweeper for bookings stranded in `PAYMENT_PENDING`.** `INITIATE_PAYMENT` commits the booking into `PAYMENT_PENDING`, and settlement happens in `PaymentLifecycleService.onBookingAccepted` / `onBatchBookingAccepted` — both `AFTER_COMMIT` listeners with no retry and no dead-letter queue. If the listener never runs (JVM death between commit and listener, or the listener's own `REQUIRES_NEW` transaction failing outright), the booking sits in `PAYMENT_PENDING` forever. Verified 2026-08-05: **no `@Scheduled` method anywhere in `src/main` reads `PAYMENT_PENDING`** (27 schedulers checked); `BookingExpiryScheduler.expireStaleRequests` (`:43-46`) queries `findRequestedBookingsOlderThan` and only ever declines `REQUESTED` bookings.

  Recovery today is **partial and manual only**: `deferred-12` AC4 added `CANCEL_PARENT` as a transition out of `PAYMENT_PENDING` (`BookingStateMachine.java:34-38`, with a comment naming this exact crash window), and `cancelBookingAsParent`'s refund whitelist correctly declines to refund money that was never taken. But that hatch requires **the parent** to notice and act — the transition map offers no `CANCEL_COACH`, no admin path, and no system path out of `PAYMENT_PENDING`. Meanwhile `PAYMENT_PENDING` is in `ACTIVE_SLOT_STATUSES` (`BookingService.java:118`) and in the `V87` exclusion constraint's `WHERE` clause, so a stranded booking **holds the coach's slot indefinitely** and blocks any other booking for that window, with the coach having no way to clear it.

  `BookingPaymentPersistenceService.declineBatchBooking` (`:140-158`) covers only the adjacent case where a settle attempt *ran and threw* — not the case where the listener never ran at all. Related to but distinct from `skillars-10-2` D1 (which is about the refund path specifically); this is the accept/settle path and has a concrete slot-blocking side effect that D1 does not. A sweeper would need a grace period well clear of Stripe's capture latency, and `PAYMENT_FAILED` (→ `DECLINED`) is the natural terminal transition — it already exists in the map. **Do not fold this into `skillars-deferred-14`**: that story's AC3a narrows the window it would otherwise widen, but deliberately does not close this pre-existing gap. [`PaymentLifecycleService.onBookingAccepted`, `onBatchBookingAccepted`, `BookingExpiryScheduler.java:43-46`, `BookingStateMachine.java:34-38`]

## Deferred from: code review of skillars-deferred-14-moderation-listener-batch-overlap-integrity (2026-08-05)
- D1: **`RescheduleService.acceptReschedule`'s new coach lock widens a pre-existing race against `declineReschedule`.** AC4 inserted `coachProfileRepository.findByIdForUpdate(coach.getId())` in the middle of `acceptReschedule` (`:128-129`), after the `"PENDING".equals(req.getStatus())` check (`:108`) but before the final `req.setStatus("ACCEPTED")` write (`:144`). `declineReschedule` takes no coach lock and no lock on the reschedule row itself, so if it runs and commits while `acceptReschedule` is blocked waiting on the (now newly-introduced) lock acquisition, `acceptReschedule` resumes holding a stale in-memory `req` still read as `PENDING`, proceeds through the overlap check, and overwrites the concurrent decline with `ACCEPTED` — silently reinstating a reschedule the coach just declined. The underlying TOCTOU class predates this story (status was already read-then-written-later with no re-check), but AC4's lock acquisition is a blocking DB call that can meaningfully widen the window versus the near-instant prior code path. Pre-existing defect class; not in `deferred-14`'s scope (AC4 only asked for an overlap check, not accept/decline mutual exclusion). Fix would re-verify `req.getStatus() == PENDING` (or lock the reschedule row) immediately after the coach lock is acquired. [`src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:94-145`]
- D2: **`acceptOneBooking` (batch) and `acceptReschedule` never re-check `SUSPENDED` after acquiring the coach lock**, mirroring a gap that already exists in the reference implementation they were told to copy. `BookingService.acceptBooking` (`:283-284`) takes `findByIdForUpdate` purely for the overlap-check lock and never re-reads `coach.getStatus()` under it; `deferred-14`'s Task 6 explicitly directs both new call sites to mirror that exact shape, so the gap is now present at three call sites instead of one. An admin suspending a coach in the window between the initial unlocked coach fetch and the later lock acquisition can still let a batch-accept or reschedule-accept through. Distinct from the *already-closed* `skillars-3-11` D1 (that was in `createBookingRequest`, closed by `deferred-12`'s `entityManager.refresh(..., PESSIMISTIC_WRITE)` re-check) — this is the accept-time paths, which were never in scope for that fix. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:263-265`, `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:128-129`, `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:283-284`]
- D3: **`ReviewModerationService`'s new `PENDING`-only guard (AC1) cannot distinguish a stale in-flight Gemini verdict for a superseded edit from a fresh one.** `ReviewSubmissionService.updateReview` resets a `PENDING`-or-`APPROVED` review back to `PENDING` and publishes a new `ReviewSubmittedEvent` on every edit. If a review is edited again while the *previous* edit's Gemini call is still in flight, two `ReviewSubmittedEvent` deliveries race against the same `PENDING` status with no version/timestamp to tell them apart — whichever Gemini call resolves first wins and writes its verdict (possibly evaluating stale, already-overwritten content) against the shared `PENDING` guard; the second, fresher delivery then finds a non-`PENDING` status and discards itself as "already resolved" even though the real resolution reflects stale content. AC1's guard is correctly scoped to the admin-vs-listener race it targets (three writers enumerated in the code comment: admin decisions, the flag-threshold auto-hold, and duplicate delivery of the *same* event) but does not cover a *second, different* event racing the first. **[AUDIT 2026-08-05: the scenario as written is NOT reachable today — corrected.** The finding's stated window is "editing a review again within the multi-second Gemini latency of the prior edit". `ReviewSubmissionService.updateReview:82-86` rejects any edit whose `lastModifiedAt` falls within the last 365 days, `submitReview` and `updateReview` both stamp `lastModifiedAt` at write time, and the moderation listener deliberately does not touch it (AC1). So a second `ReviewSubmittedEvent` for the same review cannot be published until 365 days after the first — by which point the first Gemini call has long returned. Two deliveries cannot be in flight concurrently. Keep the item: the **design** limitation is real and would become reachable the moment the 365-day edit rule is relaxed or an admin/GDPR path republishes the event, and the guard carries no version or nonce to survive that. Do not treat it as a live defect.**] A full fix needs a version/timestamp carried on `ReviewSubmittedEvent` and checked against the row under the same lock. [`src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java:93-124`, `ReviewSubmissionService.java:82-86`]
- D4: **`BookingBatchService.acceptAll`'s batch-status formula (`acceptedIds.size() == requestedBookings.size()`) only considers bookings that were still `REQUESTED` when `acceptAll` started**, so a booking already moved out of `REQUESTED` by some other path before `acceptAll` runs is invisible to it — pre-existing, since this exact formula shipped unchanged from before this diff. What's new: `updateBatchStatusFromBooking` (driven by `BookingBatchStatusListener`, `AFTER_COMMIT`) now fires **mid-`acceptAll`**, once per successful per-booking commit (Deferred-14 AC3's `REQUIRES_NEW` restructuring), instead of only after `acceptAll`'s own atomic write as before. On a fully-successful batch, the listener's own recompute — which reads *all* current booking rows, not just the ones `acceptAll` started with — writes a durable, more-accurate status before `acceptAll`'s trailing transaction runs its own (less-accurate) formula and overwrites it.

  **[AUDIT 2026-08-05: the finding's conclusion "both paths converge, so no new regression" is wrong — corrected, and the item is stronger than filed.** Trace a batch of 3 where one booking was declined individually before `acceptAll` runs. *Before* this diff: `acceptAll` wrote `FULLY_ACCEPTED` (2 accepted == 2 `REQUESTED` at start), committed, and the `AFTER_COMMIT` listener then recomputed over all 3 rows and **corrected** it to `PARTIALLY_ACCEPTED`. *After* this diff: the listener fires at the last per-booking commit and writes `PARTIALLY_ACCEPTED`, then the trailing transaction overwrites it with the naive `FULLY_ACCEPTED`. **The winner flipped, and it flipped toward the wrong value** — that is a behaviour change this diff introduced, not merely a pre-existing bug it left alone. It is **self-correcting in practice**: settlement transitions each booking and republishes `BookingStatusChangedEvent`, so the listener runs once more after the trailing commit and restores the correct value; the exposure is a transient wrong read between the trailing commit and settlement completing. Not patched in `deferred-14` because the real fix is to unify the two formulas, and `updateBatchStatusFromBooking` is shared with the individual accept/decline paths — genuinely outside that story. Whoever picks this up should fix the formula, not the ordering.**] [`src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:215-244,285-317`]

## Deferred from: code review of skillars-deferred-11-stripe-card-collection (2026-08-04)
- `PackSessionServiceParityTest` mocks `findActivePacks` to already return ordered results and only asserts `packs.get(0)` — doesn't exercise the real repository `ORDER BY`, so a regression to actual query ordering wouldn't be caught. [`src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServiceParityTest.java`]
- `PaymentMethodCard.vue`'s `watch(showForm)` has no in-flight guard against rapid toggle races between async `mountCardElement()` and sync `unmountCardElement()` — low-probability, self-heals on the next full remount. [`PaymentMethodCard.vue:124-127`]
- `PaymentMethodCard.vue`'s `stripeUnavailable` state has no retry affordance short of a full page reload — AC2 only requires the unavailable message + disabled submit, which is satisfied; a retry action would be a UX enhancement beyond spec scope. [`PaymentMethodCard.vue:86-106`]
- No frontend tests (Vitest/Vue Test Utils) were added for `PaymentMethodCard.vue` or the new `payment.store.js` actions (`fetchStripeConfig`, `fetchSavedPaymentMethod`) — real coverage gap on a component with non-trivial lifecycle logic. [`PaymentMethodCard.vue`, `payment.store.js`]

## Deferred from: code review of skillars-deferred-9-frontend-ux-polish (2026-07-02)
- D1: AC7 `portal` sub-block left untranslated in `de/index.js` — pre-existing (already English in both `en` and `de` before this story); explicitly out of this story's scope per the Dev Agent Record. [de/index.js]
- D2: Platform-wide i18n locale consolidation needed — `booking.*` (and likely other) keys are unreachable in the production locale (`en-US` has no `booking` block, no fallback to `en`, and `en` is never a selectable locale). Product-confirmed target state: three selectable locales — `en-US`, `fr-FR`, `de-DE` — all at full parity. `en` (889 lines) has more content than `en-US` (474 lines, including `booking`/`video` blocks) and was an experimental placeholder for American-vs-British English, now resolved in favor of `en-US`; its extra keys need merging into `en-US` before it can be retired. `de` needs renaming to `de-DE` and adding to `MainLayout.vue`'s language switcher (not selectable today). `de`'s `booking` block is currently empty (`{}`) and `fr-FR` has no `booking` block at all — both need full translation to reach parity. Recommend a dedicated follow-up story; scope exceeds a single review's patch pass. [boot/i18n.js, src/frontend/src/i18n/index.js, MainLayout.vue, en/index.js, en-US/index.js, de/index.js, fr-FR/index.js]

## Deferred from: code review of skillars-deferred-1-config-securityutil-hardening (2026-07-01)
- D1: Test coverage is thin relative to the diff's size — no unit test covers `NeglectedSkillDetectionService.isInValidRange()`'s boundary values (0, 1, and just inside/outside), and none of the 15 `Resource` classes refactored to use `SecurityUtil.requireCurrentUserId()` have a test asserting the actual 401 response when it throws; only one E2E smoke test (`ConfigGuardIT`) was added for a ~1080-line diff. [various]
- D2: `ConfigGuardIT.java` mutates the shared `main.platform_config` row directly (`setUp`/`tearDown`) and depends on `@AfterEach` running to restore the original value and invalidate the `ConfigService` cache — real but low-probability test-isolation hazard if the test run is interrupted before teardown. [ConfigGuardIT.java]

## Deferred from: code review of skillars-10-2-coach-enforcement-strike-management (2026-06-30)
- D1: `AFTER_COMMIT` listener failure silently drops refunds — `CancellationRefundService.onBookingCancelledByAdmin()` follows the same `REQUIRES_NEW` pattern as all other listeners; if the refund transaction fails after the suspension commits, the booking stays CANCELLED with no refund issued and no retry. Pre-existing pattern shared by `onBookingCancelledByCoach`, `onCoachNoShow`, etc. Address in a platform-wide payment resilience pass. [CancellationRefundService.java:onBookingCancelledByAdmin]

## Deferred from: code review of skillars-10-1 patches (2026-06-30)
- D1: `findBeforePivot`/`findAfterPivot` return empty context when pivot message `createdAt` is null at JPA layer — DB NOT NULL prevents this in production; only affects test fixtures that construct Message in-memory without setCreatedAt(). [MessageRepository.java:33-37]
- D2: Context window (`findBeforePivot`/`findAfterPivot`) excludes soft-deleted messages while `findAllForAdmin` includes them — chronological gap in admin message detail view with no indication; intentional spec asymmetry between views; UX concern for service layer mapping. [MessageRepository.java:33-40]

## Deferred from: code review of skillars-10-1-admin-moderation-queue-message-content-actions (2026-06-30)
- D2: `findBeforePivot` / `findAfterPivot` strict `<`/`>` on `createdAt` excludes messages sharing the exact pivot timestamp from context window — low-probability collision but possible when two messages arrive within the same millisecond. [MessageRepository.java:33-37]

## Deferred from: code review of skillars-8-4 (2026-06-27)
- W3: Redundant explicit indexes in V66 — `idx_message_reports_message_id` and `idx_conversation_reports_conversation_id` are covered by the unique constraint leading column. Minor storage waste. [`V66__messaging_reports.sql:27-28`]
- W4: `softDeleteMessage` ALREADY_DELETED race window — two concurrent soft-deletes by the same user both pass `deletedAt == null` at READ_COMMITTED; second silently succeeds instead of 409. Requires `@Version` on `Message`. [`MessagingService.java:263`]
- W5: `@PreAuthorize(IS_AUTHENTICATED)` on report endpoints instead of party-check annotation — consistent with module pattern; 403 preserved at service layer. Architectural note for future hardening. [`MessagingResource.java:140,151,168`]

## Deferred from: code review of skillars-8-3 (2026-06-26)
- W1: TOCTOU — age policy + party checks run without a transaction before committed message save; spec-designed (NOT_SUPPORTED), window is narrow [`MessagingService.java:129-145`]
- W2: Message orphaned in PENDING on JVM crash or `applyResult()` DB exception after initial tx commit; no cleanup path or retry mechanism [`MessagingService.java:167` / `GeminiModerationService.java:53`]
- W3: `conv.getParentId()` returned without null guard for SUPERVISED policy in `ModerationResultApplier.resolveRecipient()` — same pattern in `MessagingService.resolveRecipient()` [`ModerationResultApplier.java:104`]
- W5: `content` dereferenced before null check in `GeminiModerationService.moderate()`; guarded only at the single current call-site [`GeminiModerationService.java:35`]

## Deferred from: code review of skillars-8-2 (2026-06-26)
- D1: `resolveOtherPartyName()` COACH branch — `agePolicyService.getMessagingPolicy()` throws `UserNotFoundException` for deleted players, crashing coach's entire `getConversations()` call; blast radius expanded from send-path (8.1 deferral) to read-path [`MessagingService.java:311`]
- D2: `getConversations()` PARENT stream filter — same `UserNotFoundException` propagation for deleted players crashes parent's entire conversation list [`MessagingService.java:103-105`]

## Deferred from: adversarial code review of skillars-7-2 Group 2 Service Layer (2026-06-24)
- D1: Non-atomic idempotency check in `onBookingAccepted` (`existsById` bare SELECT outside TX) — root cause addressed by P3 (TX boundary restructure); revisit if duplicate event replay observed in production [`PaymentLifecycleService.java:52-55`]
- D2: `SessionPackExpiryNotifier` sends up to 14 daily warning emails per pack — no notification-sent guard; requires `last_warned_at` column on `session_pack_purchases` (V63+ migration) [`SessionPackExpiryNotifier.java`]
- D3: `createTier` TOCTOU under concurrent coach requests — two active tiers briefly possible; DB UNIQUE partial index `idx_spt_one_active_per_coach` enforces constraint at commit, causing one to fail with constraint violation; low probability in production [`SessionPackPaymentService.java:createTier`]

## Deferred from: adversarial code review of skillars-7-2 Group 1 DB+Entities (2026-06-24)
- D1: `parent_credit_balance` VIEW returns 0 rows (not a zero-balance row) for parents with no ledger history — safe via JPQL path; latent trap for native SQL consumers [`V62__session_payment_credit_wallet.sql`]
- D2: Duplicate expiry query methods — `findByCoachIdAndExpiresAtBetween...` (coach-scoped) and `findExpiringWithinWindowAndSessionsRemaining` (JPQL all-coaches) overlap; coach-scoped method appears unused; verify in Group 2 service review [`SessionPackPurchaseRepository.java:21-25`]
- D3: `SessionPackPurchase.expiresAt` mutable with no `updatable=false` — service-layer enforced via `extendPack()` business rules; open setter is a footgun [`SessionPackPurchase.java`]
- D5: `stripe_customers.last_payment_intent_id` not in AC 1 spec schema — intentional addition to support cash-out refund flow (Group 2 Decision D1 resolution); AC 1 should be updated to document this column [`V62__session_payment_credit_wallet.sql`, `StripeCustomer.java`]
- D6: No `CHECK (stripe_customer_id LIKE 'cus_%')` format guard on `stripe_customers` — would catch placeholder IDs at DB boundary; application-layer only today [`V62__session_payment_credit_wallet.sql`]

## Deferred from: code review of skillars-7-1-stripe-connect-onboarding-commission-engine (2026-06-24)
- D2: Session pack purchase always fails with `payment.providerUnavailable` in Story 7.1 — intentional stub behaviour per spec; Story 7.2 implements real charging [`StripePaymentGateway.java`]
- D3: Unbounded `VARCHAR` on `stripe_webhook_events.event_id` — Stripe event IDs are well-formed in practice; low B-tree risk [`V61__payment_module_init.sql`]
- D4: `acceptBooking` fires `INITIATE_PAYMENT` → `PAYMENT_CAPTURED` state transitions without performing actual payment — pre-existing state machine flow, not introduced by Story 7.1; Story 7.2 must retrofit a failure path and prevent the state being committed before capture succeeds [`BookingService.java:203-204`]

## Deferred from: adversarial code review of skillars-5-6-parent-development-portal (2026-06-19)
- AD2: MapStruct not used for `Object[]` → `CoachContributionDto` mapping — MapStruct cannot transform raw JDBC Object[] projections; inherent native query limitation [`SluContributionService.java`]
- AD3: `@Testcontainers` annotation absent from IT class — tests pass (6/6); infrastructure activated via TestConfig; add annotation for explicitness in future test-hardening pass [`ParentDevelopmentPortalResourceIT.java`]
- AD4: Instancio not used in IT — FK-constrained integration seeding requires fixed IDs; project-wide IT pattern [`ParentDevelopmentPortalResourceIT.java`]
- AD5: `SecurityConstants` not used in `@PreAuthorize` — pre-existing convention in the file; new endpoint follows the same pattern as existing endpoints [`SkillExposureResource.java`]
- AD6: Triple null guard inconsistency in service — `AND coach_id IS NOT NULL` in SQL already prevents nulls; guards are over-defensive but harmless [`SluContributionService.java`]
- AD7: `BigDecimal` vs JS number comparison in `CoachContributionSection` — speculative risk only if Jackson serialization changes to string representation [`CoachContributionSection.vue`]
- AD8: Authority row `id=9612` not cleaned up in `asCoach` test — `ON CONFLICT DO NOTHING` prevents failure on re-run [`ParentDevelopmentPortalResourceIT.java`]
- AD9: Route path naming inconsistency (`/parent/players/` plural) — cosmetic; consistent with existing sibling route in the same block [`routes.js`]
- AD10: `neglectedSkillNames` shows raw skill codes if `skillDefinitions` not yet loaded — transient window; `q-inner-loading` overlays content during load [`ParentDevelopmentPortalPage.vue`]
- AD11: Dual-counter race between `loadGeneration` (page) and `_coachContributionsSeq` (store) — `finally` in `loadPortal` always fires; loading flag cannot hang [`ParentDevelopmentPortalPage.vue`]
- AD12: Mock state override inside `transactionTemplate` in percentages test — Spring Boot 3.4+ automatically resets `@MockitoBean` between test methods [`ParentDevelopmentPortalResourceIT.java`]

## Deferred from: code review of skillars-5-6-parent-development-portal (2026-06-19)
- D1: `IllegalArgumentException` if `row[0]` (coach_id from native query) is not a valid UUID string — unlikely under DB schema constraints but unguarded [`SluContributionService.java`]
- D2: No exception wrapping around `coachProfileService.getDisplayNamesByIds` — propagates as 500 on failure; consistent with general project error-handling convention [`SluContributionService.java`]
- D3: Double iteration of `rows` list in `getCoachContributions` — could use SQL window function for in-DB percentage calculation; optimisation, not a correctness issue [`SluContributionService.java`]
- D4: `PerformanceReportsPanel` wrapped in extra `q-card` in parent portal may produce a double-card visual artifact — requires runtime visual verification [`ParentDevelopmentPortalPage.vue`]

## Deferred from: code review of skillars-5-5-pdf-performance-report-unified-player-timeline — Round 3 (2026-06-19)
- R3-D1: Orphaned `player_timeline_events` PERFORMANCE_REPORT row when outer `generateReport` transaction rolls back after `writeTimelineEvent` REQUIRES_NEW commit — timeline shows an event with a dead `referenceId`; accepted MVP trade-off per spec dev notes [`ReportGenerationService.java:144-153`]
- R3-D2: `findLastSessionDate` queries `MAX(calculated_at)` over all `player_skill_stats` rows without filtering for session-only writes — if `RadarAssessmentService` writes SLU rows to `player_skill_stats` (to be confirmed), radar assessments could reset the coach timeline-access expiry window, contradicting the design comment in `TimelineQueryService` [`SluRepository.java:43-48`]
- R3-D3: Stale branding logo key persists after coach tier downgrade from ACADEMY — re-upgrade reuses the prior logo key without re-validation; graceful try/catch fallback in `buildPdf` prevents crash [`ReportGenerationService.java:278`]

## Deferred from: code review of skillars-5-5-pdf-performance-report-unified-player-timeline (2026-06-19)
- D1: `slu_value` and `calculated_at` column names in `SluRepository` native queries not explicitly verified against the actual migration file — runtime `BadSqlGrammarException` risk; confirm column names from V-series migration before deploying [`SluRepository.java:36,42`]
- D2: S3 I/O (Academy logo download + PDF upload to S3) executes inside `@Transactional generateReport` — blocking calls hold DB connection for the duration; may exhaust connection pool under concurrent load [`ReportGenerationService.java:87-142`]
- D4: No rate limit on `POST /api/development/players/{playerId}/reports` — a coach can call in a loop; each call generates a PDF, uploads to S3, inserts a DB row, writes a timeline event, and queues a parent email; trivial cost-amplification DoS vector [`PerformanceReportResource.java`]
- D5: `nextSteps` stored permanently on `performance_reports` with no redaction or deletion API — coach cannot correct defamatory or incorrect notes after generation; GDPR erasure story must add `PerformanceReportRepository.deleteByPlayerId` + S3 object deletion for each `storage_key` [`ReportGenerationService.java`, `performance_reports` table]
- D6: `getParentEmailByPlayerId` fires 2 separate DB queries (getParentIdByPlayerId + userRepository.findById) — no single-query fetch for parent email+name; TOCTOU gap if parent account deleted between calls [`PlayerProfileService.java`]

## Deferred from: code review of skillars-6-5-video-privacy-rbac-account-deletion-cascades (2026-06-23)
- W1: Request-scoped `VideoAccessCache` in singleton `VideoAccessGuard` — standard Spring proxy pattern; correct in web context; only fails in bare non-web unit tests (mocked there anyway) [`VideoAccessGuard.java`]
- W2: `@TransactionalEventListener` partial cascade failure — if `AccountDeletionCascadeListener` dies mid-loop, videos not yet reached are silently missed; spec-acknowledged known gap; ops can refire manually [`AccountDeletionCascadeListener.java`]
- W3: Native SQL `@Modifying` queries bypass `@Version` optimistic lock — version column not incremented by native queries; pre-existing codebase-wide pattern; auditing all call sites is a separate hardening task [`VideoRepository.java` and other repos]
- W4: ownerId format ambiguity for mixed-type strings — Task 0 Identity Bridge investigation was a mandatory gate; deferred on assumption Task 0 was completed and format confirmed [`VideoAccessGuard.java`]
- W5: `PROCESSING→READY` backward-compat bypass not removed — spec Task 9 requires a separate PR with ops sign-off (7+ days of zero `video.moderation.bypass` counter); intentionally excluded from Story 6.5 [`VideoLifecycleService.java`]
- W8: `cascadeDeleteForAccount` quota reset non-atomic — JVM crash after last per-video commit but before `resetBytesForOwner()` leaves deleted account's quota row permanently non-zero; no retry path corrects this; future reconciliation job spec'd in dev notes [`VideoDeletionService.java:169`]
- W9: `canDelete(null, videoId)` belt-and-suspenders is a no-op for non-HTTP callers — null auth causes broad catch to swallow the re-check; `@PreAuthorize` is the primary enforceable gate; acceptable for current call sites [`VideoDeletionService.java:117`]
- W10: Parent-play/PURGE race — null `providerAssetId` passed to `generatePlaybackUrl()` if video is concurrently purged between `@PreAuthorize` canPlay evaluation and `PlaybackService.authorizePlayback()` call; provider should throw cleanly; very low probability in practice [`PlaybackService.java`]

## Deferred from: code review of skillars-5-4-skills-radar-display-development-correlation (2026-06-19)
- W1: No FK from `player_radar_baselines.player_id` / `coach_radar_preferences.player_id` to `main.player_profiles` — accepted limitation per spec dev notes; consistent with Stories 5.1–5.3 no-FK pattern across `development.*` tables [`V51__radar_display_correlation.sql`]
- W2: Rapid skill-toggle fires a PUT per click — no debounce; last-write-wins for fast toggling; low risk [`PlayerDevelopmentDashboardPage.vue`]
- W3: `insertBaselineIfAbsent` `@Transactional` participates in outer transaction — `ON CONFLICT DO NOTHING` cannot protect across a rollback on first-ever baseline write; documented MVP limitation in spec dev notes [`PlayerRadarBaselineRepository.java`]
- W4: Skill deactivation silently drops baseline from display — `findAllByActiveTrueOrderByDisplayOrderAsc` excludes inactive skills; baseline re-appears on reactivation [`RadarDisplayService.java:39`]
- W5: IT `assertThat(minimumSessionCount).isEqualTo(5L)` hardcodes config value — low risk with Testcontainers; `ON CONFLICT DO NOTHING` in V51 migration [`RadarDisplayResourceIT.java:333`]
- W7: `IMPROVEMENT_THRESHOLD = 3.0` hardcoded — exactly-3-point improvement classified as "no improvement"; explicitly accepted in spec dev notes; configurable in a future story [`DevelopmentCorrelationService.java:33`]
- W8: `(int)` cast on `totalCount` in `RadarCompositeCalculationService` — pre-existing silent overflow for very high entry counts; not introduced in this diff (see DEF6) [`RadarCompositeCalculationService.java`]
- W9: `SkillsRadarChartSpec.js` tests cannot run — vitest / `@vue/test-utils` not installed; explicitly accepted in story completion notes; frontend test-runner setup is a separate initiative

## Deferred from: code review of skillars-5-3-skills-radar-assessment-entry-multi-coach-cumulation — Pass 2 (2026-06-19)
- DEF6: `entry_count` long→double→int narrowing in composite calculator — count from native SQL is cast double→int; silently overflows above Integer.MAX_VALUE; irrelevant at current volumes [`RadarCompositeCalculationService.java:61-69`]
- DEF6: Orphaned `player_radar_composites` rows on player deletion — `player_id` column has no FK to `player_profiles`; deleted player leaves stale composite rows; pre-existing no-FK pattern across the development module [`player_radar_composites`, `V50__radar_assessment_entries.sql`]
- DEF7: Async composite silently stales on failure — `@Async` listener swallows all exceptions (logged only); no retry/dead-letter queue; composite frozen at prior value until next submission triggers recomputation; accepted per story dev notes [`RadarCompositeCalculationService.java:onRadarEntrySubmitted`]

## Deferred from: code review of skillars-5-3-skills-radar-assessment-entry-multi-coach-cumulation (2026-06-19)
- DEF1: `SkillDefinitionRepository` injected directly into `SkillDefinitionResource` (no service layer) — pre-existing architecture; fix at next planned touch of `SkillDefinitionResource` [`SkillDefinitionResource.java:17`]
- DEF2: `entry_count` in `player_radar_composites` stores total rows across all assessment types, not distinct coaches — semantic mismatch with Story 5.4 confidence indicator design ("3+ entries = filled dot" is misleading when all 3 rows come from one coach); 5.4 author should add a `distinct_coach_count` column or revise the confidence model [`RadarCompositeCalculationService.java`, `player_radar_composites`] — **[AUDIT 2026-08-04: STILL OPEN. Story 5.4 has shipped; no `distinct_coach_count` column exists in any migration and the confidence model was not revised.]**
- DEF3: Concurrent async composite recalculation race — two simultaneous submissions for the same player trigger two `@Async` events that can both query aggregates before either upserts; last writer wins and self-corrects on the next submission; theoretical low-probability issue [`RadarCompositeCalculationService.java:onRadarEntrySubmitted`]
- DEF4: No retry or dead-letter queue for async composite failure — failure logged but composite silently stale until next assessment triggers recomputation; accepted per story dev notes; address in an infrastructure hardening story if operational visibility requires it [`RadarCompositeCalculationService.java`]

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection — Round 2 Group C (2026-06-19)
- D3: `getNeglectedSkills` in `development.api.js` is dead code — neglected codes are bundled in the exposure response; the standalone API export is never called. Remove in a cleanup pass. [`development.api.js:11-12`]
- D5: `SluTargetEditor` `currentTargets` watcher can discard in-progress user input if `fetchTargets` resolves while the dialog is open (race between fetchExposure completing and fetchTargets completing). Low probability; fix by guarding the targets-loaded state or deferring the watcher when open. [`SluTargetEditor.vue:51`]


## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection — Round 2 Group A (2026-06-19)
- D0: Narrative sharing consent system — player or parent should be able to grant a coach access to their narrative summary, with a visible toggle to revoke it; coaches currently have unrestricted read access to all narratives via `ROLE_COACH` guard; restrict access and implement a proper permission model in a dedicated story [`SkillExposureResource.java:34-38`]
- D1: V49 `CREATE UNIQUE INDEX` blocks startup if phased deploy allowed Monday batch to create duplicate flags between V48 and V49 — mitigated by same-commit deployment of both migrations; negligible in standard CI pipeline [`V49__neglected_skill_unique_open_constraint.sql`]
- D3: All skills flagged neglected for inactive/new player — `actual=0` falls below every coach target; technically correct per AC 4 literal but causes flag-flood on first evaluation; consider a "minimum sessions in the evaluated period" guard in a future UX refinement story [`NeglectedSkillProcessor.java`]
- D5: No upper bound on `findByPlayerIdFromWeek` JPQL query — future-dated snapshot rows from a clock-skew or ingestion error inflate trend data; fix requires an upper-bound year/week filter at ingestion time [`SluWeeklySnapshotRepository.java:21-27`]
- D6: `SluCalculationService` async ISO week boundary race — `now` captured pre-`saveAll`; a session straddling Monday midnight writes SLU rows and snapshot to different ISO weeks — pre-existing design acknowledged in story dev notes [`SluCalculationService.java:177-187`]

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection (2026-06-19)
- W1: Partial snapshot missing if failure occurs between `sluRepository.saveAll` and `snapshotBatchWriter.writeAll` — acknowledged in dev notes; snapshot is eventually-consistent and does not roll back with SLU rows [`SluCalculationService.java:177-187`]
- W2: SluCalculationService week-boundary race — `now` captured before saveAll; a failure spanning midnight ISO week boundary could mismatch iso_week between SLU rows and their snapshot entry; negligible probability
- W3: V48 `INSERT INTO platform_config ON CONFLICT (key) DO NOTHING` silently preserves wrong existing value — pre-existing migration pattern across all stories [`V48__development_exposure_dashboard.sql:43`]

## Deferred from: code review of skillars-5-1-slu-engine-skill-taxonomy — Pass 2 (2026-06-18)
- D1: No retry on saveAll failure — SLU permanently lost on transient DB error; dev notes acknowledge and provide a recovery query; infrastructure-wide limitation [`SluCalculationService.java:165`]
- D2: CallerRunsPolicy can block HTTP thread under executor saturation — prior review explicit tradeoff: AbortPolicy silently drops SLU vs CallerRunsPolicy blocks request thread [`AsyncConfig.java:40`]
- D3: Duration rounding over/under-counts block time — prior review accepted as intentional approximation; documented in dev notes [`SluCalculationService.java:121`]
- D4: Thread.sleep in negative-path IT tests — prior review explicitly deferred; acceptable for negative async tests with no positive signal [`SluCalculationServiceIT.java`]
- D5: No booking_id stored in player_skill_stats — no DB-level idempotency anchor; behavioral gap addressed by idempotency pre-check patch; schema addition out of story scope [`V46__development_module_init.sql`]
- D6: No guard on zero/negative repDensity/intensity metadata fields — pre-existing drill creation validation gap; zero repDensity silently produces no SLU without warning log [`SluFormula.java`]
- D7: NUMERIC(10,4) overflow at extreme session attribute values — theoretical at realistic gameplay values with default 0.10 scales [`SluFormula.java`, `V46__development_module_init.sql`]
- D8: SluRepository inherits deleteAll/deleteById — AC 4 met; comment warns developers; runtime override-to-throw is defense-in-depth only [`SluRepository.java`]
- D9: Skill code case-sensitivity — lowercase skillWeighting keys silently dropped; fix belongs at drill creation (input normalisation), not SLU calculation [`SluCalculationService.java`]

## Deferred from: code review of skillars-5-1-slu-engine-skill-taxonomy (2026-06-18)
- W1: Negative metadata fields (repDensity/intensity/etc.) can produce corrupt SLU via double-negative — pre-existing validation gap at drill creation; fix at DrillMetadata validation layer [`SluFormula.java:45-66`]
- W2: @Async executor naming ambiguity — explicit `@Async("taskExecutor")` qualifier would eliminate uncertainty; largely covered by the AsyncUncaughtExceptionHandler patch [`SluCalculationService.java:43`]
- W4: Thread.sleep in negative-path IT tests — acceptable for negative async assertions where no positive signal exists; replace with Awaitility + log spy if flakiness is observed in CI [`SluCalculationServiceIT.java:107,125,135,171`]
- W5: Platform config IDs 70-72 skip 68-69 — intentional gap; no migration uses 68-69; ON CONFLICT DO NOTHING prevents failures [`V46__development_module_init.sql:51-55`]
- W6: player_id and coach_id have no FK constraints on player_skill_stats — intentional for immutable audit rows; cascading deletes would corrupt historical SLU [`V46__development_module_init.sql:19,21`]

## Deferred from: code review of skillars-4-6-homework-assignment-player-locker-room (2026-06-18)
- W1: `getLockerRoomDrills` calls `hasActivePack` once per unique coach (N+1 queries) — performance concern, not correctness; batch API needed; address in a performance-hardening pass [`HomeworkAssignmentService.java:getLockerRoomDrills`]
- W3: `handleBookingCompleted` stores null sessionId with no log.warn — async bean ordering is not guaranteed; add warn log if sessionId resolves null [`HomeworkAssignmentService.java:handleBookingCompleted`]
- W4: `@Size(max=2)` on `WrapUpRequest.homeworkDrillIds` not enforced on event-driven path — HTTP validation is the only entry point today; add size guard in service if other publishers emerge [`HomeworkAssignmentService.java`]

## Deferred from: code review of skillars-4-5-intelligent-drill-suggestions-session-templates — Round 2 (2026-06-18)
- W1: `deleteTemplate()` no ARCHIVED guard — idempotent re-archive silently succeeds (204) on already-archived template; acceptable behavior [`SessionTemplateService.java:deleteTemplate`]
- W2: `deployTemplate()` passes `t.getBlocks()` by reference not defensive copy — safe in current code path; no mutation after save in same transaction [`SessionTemplateService.java:deployTemplate`]
- W3: `computeFocusScore()` returns 0 for all-unsupported focus values — random subset within age-fit tier (0.10 base score); by-design stub behavior [`DrillSuggestionService.java:computeFocusScore`]
- W4: Template name inputs missing `maxlength="200"` client-side — server `@Size(max=200)` catches it; generic error is acceptable UX [`SessionTemplateVault.vue`, `SessionBuilderPage.vue`]
- W5: `createTemplate()` store action never sets `error.value` on failure — callers handle errors; minimal impact on store error state [`sessionTemplate.store.js`]
- W6: `SessionTemplate.blocks` null risk if `session.getBlocks()` null — `Session.blocks` is NOT NULL in DB so sessions should never have null blocks; constraint prevents [`SessionTemplateService.java:createTemplate`]


## Deferred from: code review of skillars-4-4-session-builder-block-structure-dna — round 2 (2026-06-18)
- W8: `isBookingPlannable` accepts `"UPCOMING"` but no known code path transitions a Booking to this status — proactive future-proofing, harmless if UPCOMING is never set [`SessionPlanService.java:167`]
- W9: `updateSession` does not re-validate booking plannable status at update time — a booking cancelled after session plan creation can still be updated freely; outside story scope [`SessionPlanService.java:updateSession`]

## Deferred from: code review of skillars-4-4-session-builder-block-structure-dna (2026-06-18)
- W1: COMPLETED status transition never wired from booking completion — `session.status` is set to `COMPLETED` on `createSession` guard but no code path (booking completion event, scheduler, or explicit endpoint) ever transitions a DRAFT/SAVED session to COMPLETED. Cross-story dependency: Story 3.6 booking completion event flow. [`SessionPlanService.java`]
- W2: IT test `updateSession_completedSession` does not assert SESSION_PLAN_LOCKED helpCode in response body — test verifies 403 status but never reads `response.body.helpCode` to confirm the correct error code is returned. Test quality improvement. [`SessionBuilderResourceIT.java:271`]
- W3: `WrapUpSequence` uses `variant="compact"` instead of spec-specified `"full"` — cosmetic deviation; DNA chart renders at 160px instead of 240px in the wrap-up overlay. [`WrapUpSequence.vue:163`]
- W4: `SessionBlockRequest.drills` has no `@Size(max=...)` upper-bound constraint — unbounded drill count per block; a malicious payload could include thousands of drills, causing runaway DNA/equipment computation. Hardening concern, not MVP-blocking. [`SessionBlockRequest.java:16`]
- W6: `buildResponse` calls `drillRepository.findAllById` twice for the same ID set — once inside `resolveMetaMap`, once inside `buildResponse` itself. Redundant DB round-trip for every read. Performance optimization. [`SessionPlanService.java:220`]
- W7: IT teardown `DELETE FROM session.sessions` runs before `DELETE FROM booking.bookings` — safe today because there is no FK between the tables; would fail if a FK is ever added. Future-proofing. [`SessionBuilderResourceIT.java:104`]

## Deferred from: code review of skillars-4-3-custom-drill-uploads — round 2 (2026-06-17)
- W10: `FeatureGatedException` error code not matched by frontend catch block — `startUpload` checks `video.quotaExceeded` and `video.constraintViolated` but not the helpCode produced by `ApiAdvice` for `FeatureGatedException`; requires stale eligibility cache + server gate both firing; generic "upload failed" is not wrong; low probability [`DrillDetailPanel.vue` — `startUpload` catch]

## Deferred from: code review of skillars-4-3-custom-drill-uploads (2026-06-17)
- W1: Concurrent `initiateUpload` on same drill — two provider video objects created by racing threads; loser's video is orphaned at provider; DataIntegrityViolationException handles DB race but provider allocation happens before the save [`DrillUploadService.java`]
- W2: `existsByVideoId` timing in concurrent `deleteVideo` for shared-video drills — both concurrent deletes may pass the check before either commits, publishing deletion event twice; double-delete is idempotent at Bunny.net; near-impossible in normal usage [`DrillUploadService.java`]
- W3: Transaction rollback after `videoService.initializeUpload` — provider video created, DB transaction rolls back (including UploadSession), so reconciliation worker cannot find the orphaned provider asset [`DrillUploadService.java`]
- W4: `resolveMinUploadTier` depends on `CoachSubscriptionTier` enum declaration order — informational only; used in error message hint, not in access control; wrong hint if enum is not declared in ascending rank order [`DrillUploadService.java`]
- W5: Signed playback URL expires in 2 h but is cached in Pinia store indefinitely — coach must reload to get fresh URL after 2+ hours of idle time; expected signed-URL behaviour [`DrillLibraryService.java`]
- W6: `@Async` on `VideoPhysicalDeletionListener` uses default `SimpleAsyncTaskExecutor` (unbounded threads) — low volume expected; add named executor if burst deletion scenarios emerge [`VideoPhysicalDeletionListener.java`]
- W7: IT test `initiateUpload_scoutCoach_returns403WithFeatureGatedCode` does not verify error code in response body — functional behaviour is correct; test hardening pass [`DrillUploadResourceIT.java`]
- W8: AC 3 "configurable 60-min timeout" not specifically wired to drill uploads — inherits pre-existing `UploadSession.expiresAt` scheduler; not changed by this story [`platform.video` scheduler]
- W9: `@TransactionalEventListener` silently drops events if called outside a transaction — hypothetical only; `DrillUploadService` is `@Transactional` so all call paths have a transaction [`VideoPhysicalDeletionListener.java`]

## Deferred from: code review of skillars-4-2-drill-card-operations (2026-06-17)
- W1: Concurrent fetch race between applyFilters and onTabChange — two in-flight API calls (searchDrills + fetchDrills) can overwrite each other's results; last response wins; address with request ID or AbortController in a UX hardening pass [`DrillLibraryPage.vue`, `session.store.js`]
- W2: sluBreakdown silent 0 for null repDensity — `null * weight = 0` in JS; renders "0 SLU" instead of "—"; Foundation 20 drills all have valid repDensity; add a null guard when coaches can upload custom drills [`DrillDetailPanel.vue`]
- W3: removeTag chip visible for any COACH drill — component assumes ownership from context (correct in PRIVATE tab); defensive concern if DrillCard is reused in a multi-coach admin context [`DrillCard.vue`]
- W4: DrillTagId @Column(name="tag") missing length=50 — JPA default column length is 255 vs DB VARCHAR(50); harmless with schema validation off; add `length=50` if ddl-auto validation is enabled [`DrillTagId.java`]
- W5: COACH vs PRIVATE naming inconsistency — entity/DB stores library_type="COACH"; API param and frontend use "PRIVATE"; pre-existing from Story 4.1; fragile on new developer additions [`DrillLibraryResource.java`, `DrillLibraryService.java`]

## Deferred from: external code review of skillars-4-1-drill-library-foundation (2026-06-17)
- D1: `resolveMinEnabledTier` returns `"NONE"` when all gate config keys are false — misleading required-tier in `FeatureGatedException`; low-probability misconfiguration edge case [`DrillLibraryService.java:103-110`]
- D2: `DrillVideoRef.save()` issues merge (SELECT + INSERT) instead of persist (INSERT-only) — extra SELECT on clone ref insert; no data corruption in normal flow; fix with `Persistable<UUID>` implementation when performance becomes a concern [`DrillLibraryService.java:82`]

## Deferred from: code review of skillars-4-1-drill-library-foundation (2026-06-17)
- D1: `session` schema name is a PostgreSQL non-reserved keyword — works on all tested PG versions; renaming after migration is written would require a destructive V40 migration [`V38__session_module_init.sql`]
- D2: V39 seed drills use `gen_random_uuid()` — non-deterministic IDs differ between environments; migration already written; deterministic UUIDs would require a V40 fix migration [`V39__session_foundation_20_drills.sql`]
- D3: Feature gate config key format relies on `tier.name()` matching DB key suffix exactly — new tier addition requires a matching migration; acceptable by convention; no compile-time enforcement [`DrillLibraryService.java:86`]
- D4: `POST /api/session/plans` returns 201 empty body — intentional stub per story dev notes; full implementation in Story 4.4 [`SessionPlanResource.java`]
- D5: `DrillLibraryPage.vue` `onMounted` no error handling — stub page; Story 4.2 builds full UI [`DrillLibraryPage.vue:15`] — **[AUDIT 2026-08-04: STILL OPEN. Story 4.2 has shipped and built the full UI, but `onMounted` still has no error branch.]**
- D6: New coach with no profile gets `ResourceNotFoundException` → 404 from `getCoachIdByUserId` on private drill list — edge case; Story 4.2 to guard on the frontend; backend always requires a complete profile [`CoachProfileService.java`]
- D7: `listPrivateDrills` no explicit `library_type = 'COACH'` filter — safe today due to DB `chk_drill_owner` constraint preventing PLATFORM drills from having a non-null `owner_coach_id` [`DrillRepository.java`]
- D8: `DrillResponse.ownerCoachId` is always null for PLATFORM drills — nullable contract undocumented; Story 4.2 frontend rendering should null-check [`DrillResponse.java`]
- D9: `ConfigService.getBoolean` no logging when returning false for a present-but-non-"true" value — operational visibility gap for misconfigured (not absent) keys [`ConfigService.java`]

## Deferred from: code review of skillars-3-7-session-pause-resume (2026-06-16)
- D1: SSE race during in-flight pause — if remote resume (SSE `IN_PROGRESS`) arrives while local pause API is in-flight, `watch` restarts timer while `pausing=true`; UI self-corrects on next event; multi-device edge case [`ActiveSessionScreen.vue`]
- D2: SSE heartbeat handler closes/reopens EventSource unconditionally, resetting retry counter while active polling is running — can cause multi-second status gaps; pre-existing in `booking.store.js`
- D3: `elapsed` resets to 0 on component remount; `sessionStartTime` prop is never consumed to reconstruct elapsed time — accumulated active time is lost on browser refresh; pre-existing [`ActiveSessionScreen.vue`]
- D4: `completionLoading` flag shared across pause/resume/end — consumers cannot distinguish which operation is in-flight; component uses local `pausing`/`resuming` refs for buttons so user-visible impact is nil; pre-existing store design [`booking.store.js`]

## Deferred from: code review of skillars-3-6-session-completion-live-mode-quick-complete (2026-06-16)
- W1: JPQL string literal `'COMPLETED'` in `findPendingQuickCompletes` is fragile against `BookingStatus` enum rename — pre-existing pattern project-wide [`SessionCompletionDataRepository.java:22`]
- W3: `BookingCompletedEvent` has no retry/DLQ mechanism if listener fails after commit — infrastructure limitation, pre-existing across all event consumers [`BookingEmailListener.java`]
- W4: `getDrillSuggestions` has no `@Max` constraint on `limit` parameter — stub endpoint fully replaced by Epic 4; guard when real implementation lands [`SessionCompletionResource.java`]
- W5: Auto-return after wrap-up reloads `selectedWeek` instead of current week — minor UX edge case when coach was browsing a different week [`CoachCommandCenterPage.vue:305`]
- W6: V33 migration uses hardcoded `id = 39` for `platform_config` insert — low collision risk given sequential pattern; validate before deploying to environments with manual config inserts [`V33__session_completion_data.sql:3`]

## Deferred from: code review of skillars-3-5-scheduling-views-timezone-management (2026-06-15)
- W1: Revenue gross calculation ignores variable session pricing (pack discounts, multi-session rates) — spec defines gross as `perSessionPrice × count` (AC 2), variable pricing is out of scope; revisit in a pricing-model story [ProjectedRevenueService.java]
- W2: N+1 DB queries in `getParentPlayerSchedule` (coachProfile + credits + in-flight count per booking) — pre-existing codebase pattern shared with `getParentBookings`; address in a performance-hardening pass [BookingService.java]

## Deferred from: code review of skillars-3-4-booking-state-machine-sse (2026-06-15)
- No optimistic/pessimistic lock on `transition()` — concurrent callers can both pass `validate()` on the same booking; add `@Lock(PESSIMISTIC_WRITE)` in a concurrency-hardening pass [BookingService.java:85]
- `getRequestedStartTime()` null not guarded before `ChronoUnit.HOURS.between()` in `applyRefundLogic` — in practice never null (set at creation); add guard if entity nullability changes [BookingService.java:256]
- SSE endpoint accepts subscriptions for terminal-state bookings — emitters accumulate for COMPLETED/CANCELLED/REFUNDED bookings; implement lifecycle-based subscription guard in a resource-management pass [BookingEventResource.java:37]
- `verifyIsParty` has no admin bypass path — no admin role exists yet; revisit when admin management stories are implemented
- Negative `hoursUntilSession` for past-session cancellations silently maps to NONE — probably correct but undocumented; add an explicit branch or comment [BookingService.java:256]
- Polling fallback has no exponential backoff — 2 s fixed interval is spec-prescribed degraded mode; add backoff if hammering becomes observable in production [booking.store.js]
- `isCoachParty()` returns generic 403 when coach profile is deleted — indistinguishable from unauthorized third-party; improve error when coach-account-deletion story is implemented [BookingEventResource.java:70-73]
- Dead `CANCELLED` entry in `BookingStateChip.statusMap` — harmless graceful-degradation fallback; clean up after data migration is confirmed complete [BookingStateChip.vue]
- `PAYMENT_FAILED` sets no `refundEligibility` — `null` is intentional; Epic 7 handles payment-failure refund logic independently [BookingService.java:applyRefundLogic]
- `useBookingSse()` not wired into `BookingStateChip` — SSE wire-up deferred to consuming page/component story; chip will be connected when the parent booking detail page is built

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group E (2026-06-15)
- Authority id 9502 leaked in `playerNotOwnedByParent_returns403` test — `finally` block cleans user + user_authority but not the authority row; `@AfterEach` only deletes ids 9500, 9501; add `DELETE FROM main.authority WHERE id = 9502` to the finally block [BookingRequestResourceIT.java:289]
- `declineBooking` unit test uses `any(BookingDeclinedEvent.class)` — `canonicalTimezone` field not captured/asserted via `ArgumentCaptor`; regression where timezone is null would pass [BookingServiceTest.java:244]

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group D (2026-06-15)
- `canonicalTimezone` sent as parent's browser timezone — session time in coach notification email shown in parent's TZ, not coach's; canonical timezone for a session should be the coach's timezone; revisit in Story 3.5 (Scheduling Views & Timezone Management) [BookingRequestPage.vue:121] — **[AUDIT 2026-08-04: STILL OPEN. Story 3.5 has shipped; `BookingRequestPage.vue:294,312` still send `Intl.DateTimeFormat().resolvedOptions().timeZone`.]**
- `formatSlot()` in BookingRequestPage uses `toLocaleString()` with no timezone — slots display in parent's local time, not coach's timezone; inconsistent with ParentBookingsPage which uses `{ timeZone: timezone }`; address in Story 3.5 [BookingRequestPage.vue:104]

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group B (2026-06-15)
- No duplicate-booking guard for same slot — multiple REQUESTED bookings for same player/coach/timeslot are possible; credit soft-reservation handles the economic constraint; add a unique partial index on (player_id, coach_id, requested_start_time) WHERE status IN (...) in a future scheduling-conflicts story [BookingService.java:createBookingRequest, V31 migration]
- N+1 player name + credit queries in `getParentBookings` — already tracked from Group A
- All availability windows have invalid timezone → misleading 403; add a distinct error code or admin-visible flag when no valid windows exist vs. slot outside valid windows [BookingService.java:isSlotWithinAvailabilityWindow]
- Midnight-crossing sessions fail/pass incorrectly in availability window check because endZdt.toLocalTime() wraps past midnight; add explicit day-boundary guard when requestedEnd < requestedStart (in LocalTime) [BookingService.java:228-232]
- DST transition can shift booking time by 1h relative to window boundary; acceptable for current scope; revisit when timezone management (Story 3.5) is implemented [BookingService.java:isSlotWithinAvailabilityWindow]
- `w.getDayOfWeek()` vs JS 0-based day format — verify that the availability-windows frontend sends ISO 1-7 (not JS 0-6); pre-existing from Story 3.1 [BookingService.java:230, CreateWindowRequest.java]

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group A (2026-06-15)
- `requestedEndTime` minimum duration not validated — 1-second bookings accepted; minimum session length not in scope for Story 3.3; add a `@PositiveDuration(min=15m)` or service-level check in a future session-constraints story [CreateBookingRequest.java:16]
- N+1 queries in `getParentBookings` — player names and effective credits each fire separate SQL per booking row; batch player name lookup the same way coach names are batched; catch when booking volume per parent grows [BookingService.java:getParentBookings]

## Deferred from: code review of skillars-3-2-session-pack-purchase-credit-dashboard (2026-06-13)
- No DB-level state machine constraints — `ACTIVE+credits=0` or `EXHAUSTED+credits>0` not prevented at DB layer; enforce with additional `CHECK` constraints when the status lifecycle is fully stable. [V30__booking_session_packs.sql]
- In-memory `coachId` filter when listing a parent's packs — loads all packs for the parent then Java-stream filters by coachId; push the filter into SQL when pack volumes grow. **[AUDIT 2026-08-04: carried over from the deleted legacy `SessionPackService.getPacksForPlayer` into the new payment path — retargeted]** [`SessionPackPaymentService.java:78-81`]

## Deferred from: code review of skillars-3-1-coach-availability-management (2026-06-13)
- Block spans midnight → negative CSS height in WeeklyCalendar overlay — multi-day block rendering is out of scope for Story 3.1 ACs; handle when calendar becomes a product priority [WeeklyCalendar.vue:1652-1668]
- `getAvailabilityCalendar` timezone-expansion logic (LocalTime + canonicalTimezone → Instant) not unit-tested — IT tests cover it implicitly; add targeted unit test when timezone bugs appear or before Story 3.5 timezone management work [AvailabilityServiceTest.java]
- No date-range guard on `weekStart` GET parameter — far past/future dates are harmless for a 7-day view; address if API is ever exposed to untrusted external callers [AvailabilityResource.java:421]

## Deferred from: code review of skillars-2-4-contact-detail-sanitization-ux (2026-06-13)
- Phone regex false positives — `PHONE_PATTERN` can match dates and numeric prose (e.g. "49-60 EUR") in bio text; no false-positive boundary test exists [ContactDetailSanitizer.java]
- `wasModified` semantics with sequential email-then-phone substitution — phone regex runs on already-redacted string; edge case may cause unexpected detection flag behavior [ContactDetailSanitizer.java]
- Duplicate i18n key `auth.coach.bioSanitizationWarning` (near-identical to `contactDetailWarning`, trailing period differs) — unused by this story but will silently diverge if either string is updated [src/frontend/src/i18n/en/index.js]

## Deferred from: code review of skillars-2-3-coach-public-profile-page (2026-06-13)
- N+1 queries — `getPublicProfile` fires 8 sequential DB round-trips; acceptable for single-entity load now, but batch loading or `@EntityGraph` should be considered before Epic 3 traffic ramp [CoachProfileService.java]
- Floating-point savings math in `SessionPackTracker.vue` — `perSessionPrice * sessionCount - totalPrice` uses IEEE 754 arithmetic; add a currency library (e.g. `currency.js`) before pack discounts are prominent in UI [SessionPackTracker.vue]
- `CoachMediaItem.uploadedAt` uses field initializer `OffsetDateTime.now()` — consistent with existing `CoachProfile.createdAt` pattern but should migrate to `@CreationTimestamp` codebase-wide [CoachMediaItem.java]
- `UNIQUE (coach_id, display_order)` makes naive gallery reorder impossible without a temp value; make the constraint `DEFERRABLE INITIALLY DEFERRED` or redesign reorder API in the media management story [V28__marketplace_coach_media.sql]
- `aggregateRating`/`reviewCount` hardcoded to `0.0`/`0` — wire to reviews aggregate in Epic 9 [CoachProfileService.java]
- `long → int` cast on `strikeCount` — replace with `Math.toIntExact()` to catch overflow explicitly if strike volume ever grows large [CoachProfileService.java]
- Test `unknownId_returns404` uses `assertThatThrownBy().isInstanceOf(HttpClientErrorException.class)` — add `satisfies()` on the outer exception to verify status 404 before the inner cast, so a 5xx surfaces a cleaner failure [CoachProfileResourceIT.java]
- `VerificationBadge.vue` tooltip presence — verify the existing component already includes tier-explanation tooltip (AC 2); if not, add it in a follow-up [CoachPublicProfilePage.vue]

## Deferred from: code review of skillars-1-6-age-tier-enforcement-family-data-isolation (2026-06-12)
- Flyway V25 hardcoded IDs 112–114 — `ON CONFLICT (key) DO NOTHING` does not guard against PK collision if those IDs are already taken by different rows with different keys; spec explicitly verified the ID range is safe; established codebase Flyway seed pattern [V25__age_policy_config_seed.sql:1–6]

## Deferred from: code review of skillars-1-5-authentication-jwt-security (2026-06-12)
- Tests use raw `jdbcTemplate` inserts instead of Instancio for test data — project rule violation but tests are functionally correct [AuthResourceIT.java]
- `AuthResourceIT` lacks `@Testcontainers` annotation — may be managed via inherited `TestConfig` or `SecurityIT` base class; verify before next review [AuthResourceIT.java]
- `@Observed` at class level vs per-method on `AuthResource` — class-level is a valid Micrometer pattern; no metric data lost [AuthResource.java]
- `refresh_alreadyUsedToken` test does not assert `Set-Cookie: rtkn=; Max-Age=0` in the 401 response — minor AC2 coverage gap [AuthResourceIT.java]
- `ROLE_ROUTES` duplicated across `LoginPage.vue` and `router/index.js` — DRY violation; divergence would cause infinite redirect loop, but no current divergence
- `fr-FR` locale may be missing `auth.coach` sub-tree — investigate whether gap is pre-existing from a prior story [i18n/fr-FR/index.js]
- `hydrated` flag in router factory is closure-scoped — SSR-unsafe but app is SPA only [src/frontend/src/router/index.js]
- Client-side `skp` clear in `auth.store.logout()` is redundant — server `logout()` already sends `Set-Cookie: skp=; Max-Age=0`; the `document.cookie` write is belt-and-suspenders [auth.store.js]

## Deferred from: code review of skillars-1-4-parent-registration-player-profiles-shadow-accounts Group 1 (2026-06-12)
- OTP hash uses `SHA-256(otp+userId)` no separator — same pre-existing pattern as CoachRegistrationService (already tracked Story 1.3 Group B D3); rate limiting is primary mitigation [ParentRegistrationService.java — hashOtp]
- `verifyEmail` saves `activated=true` before optimistic-lock check — correctly rolled back by `@Transactional`; same pattern as CoachRegistrationService; would break if called inside `REQUIRES_NEW` propagation [ParentRegistrationService.java:129–137]
- `PhoneNumber("XX")` hardcoded country placeholder — intentional per Dev Notes; same as coach flow (Story 1.3 Dev Notes) [ParentRegistrationService.java:98]
- Migration IDs 100–102 in `platform_config` — different table from V21's authority rows; `ON CONFLICT (key) DO NOTHING` is correct idempotency guard [V22__parent_player_shadow_accounts.sql]
- `dateOfBirth = LocalDate.of(1900, 1, 1)` parent user placeholder — intentional per Dev Notes; same pattern as coach (Story 1.3 Dev Notes) [ParentRegistrationService.java:102]
- Age tier snapshotted at creation, never recomputed as child ages — by design per spec; explicit consent-escalation update deferred to Story 1.6 [PlayerProfile.java; ShadowAccountService.java]
- `@Past` constraint allows 1-day-old player DOB; no minimum player age enforced — not in scope per spec; no AC addresses minimum player age [CreatePlayerProfileRequest.java:12]
- OTP rate-limit key is `userId` only — expired-OTP resubmissions drain legitimate user's budget; same pre-existing pattern as CoachRegistrationService (Story 1.3 Group B D2) [ParentRegistrationService.java:154]
- Phone-collision detection via `msg.contains("phone")` — DB-dialect fragile; same pre-existing pattern as CoachRegistrationService [ParentRegistrationService.java:100–104]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group D (2026-06-11)
- D1: `/verify-email` endpoint not rate-limited — large UUID space; already tracked Group A D6; acceptable risk [CoachRegistrationResource.java]
- D2: Rate limit consumed before user table lookup in `verifyPhone` — targeted bucket exhaustion possible; design limitation of public OTP endpoints; mitigated by per-userId keying [CoachRegistrationService.java:145–147]
- D3: `SUSPENDED` user in `EMAIL_VERIFIED` state can complete phone OTP — no suspension code exists yet; guard should be updated when suspension story is implemented [CoachRegistrationService.java]
- D4: SES failure during `/resend-verification` creates valid DB token with no email delivery — logged at ERROR; resend button available [CoachRegistrationEmailListener.java]
- D5: Frontend 60s cooldown resets on page refresh — UI-only throttle; server-side rate limit is authoritative [CoachEmailPendingPage.vue]
- D6: `ContactDetailSanitizer` double-redaction edge case — phone pattern can match trailing digits in already-redacted string; benign, no exploitable effect [ContactDetailSanitizer.java]
- D7: `ON CONFLICT (name) DO NOTHING` in authority seed does not protect against PK collision on `id` — already tracked Group A D4; id=100/101 safe for this project [V21__skillars_security_extension.sql]
- D8: `verifyEmail` response leaks internal userId as URL query param — already tracked Group C D1; spec-mandated (AC4); mitigated by per-userId rate limiting [CoachRegistrationService.java:142, CoachEmailVerifyPage.vue:72]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group C (2026-06-11)
- D1: userId in URL query param as tamper vector — spec-mandated design (AC4); mitigated by per-userId rate limiting (Group B P4) [CoachEmailVerifyPage.vue, CoachPhoneVerifyPage.vue]
- D2: GET with token in query string exposes token to server logs/Referer — spec-mandated endpoint design (AC4); single-use token mitigates
- D3: sessionStorage fragility / cross-device flow — architectural limitation of spec-prescribed flow; out of scope for story 1.3
- D4: useContactDetector PHONE_RE may false-positive on numeric strings in name fields — low practical risk in practice
- D5: OTP handlers reimplemented instead of reused from OtpPage.vue per spec Dev Notes — functionally equivalent; refactor candidate
- D6: useContactDetector not applied to phone field — less relevant; spec doesn't require it here
- D7: canResend read directly from err.response.data bypassing parseApiError — works correctly; architectural cleanup is future work
- D8: resendSuccess banner implies email was always sent — intentional anti-enumeration security design
- D9: auth.firstName/validation.* absent from en/index.js — false positive: app default is en-US; en falls back to en-US for these keys
- D10: --accent-warning CSS token confirmed present at _colors.scss lines 31 and 88

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group B (2026-06-11)
- D1: verifyPhone caller-supplied userId with no ownership binding — spec-required design; rate limiting is primary mitigation [VerifyPhoneRequest.java]
- D2: IP-keyed rate limiting timing oracle on /resend-verification — pre-existing RateLimitingService limitation [CoachRegistrationService.java]
- D3: OTP hash SHA-256(otp+userId) no random salt — spec-prescribed; already tracked as W1 [CoachRegistrationService.java:hashOtp]
- D4: Hardcoded DOB(1900,1,1) and Gender.OTHER placeholders persisted to DB — spec-acknowledged; cleaned up in Story 2.1 [CoachRegistrationService.java]
- D5: registerCoach returns void not CoachRegistrationResult — intentional simplification; void sufficient for current ACs [CoachRegistrationService.java]
- D6: resendVerificationEmail deletes unused tokens instead of marking used=true — deletion achieves invalidation intent [CoachRegistrationService.java:168]
- D7: Hardcoded BIGINT test fixture IDs risk TSID collision — low probability, acceptable in test-only code [CoachRegistrationResourceIT.java]
- D8: SecureRandom re-instantiated per generateOtp() call — low severity performance concern [CoachRegistrationService.java:generateOtp]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group A (2026-06-11)
- D1: BIGINT PK with no DB sequence — pre-existing @Tsid pattern; direct SQL inserts require manual TSID generation [V21__skillars_security_extension.sql]
- D2: verification_status unconstrained VARCHAR(20) — no CHECK constraint; pre-existing pattern for enum-backed columns [V21__skillars_security_extension.sql]
- D3: SES region hardcoded eu-west-1 in SesProperties, not overridden in application-prod.yaml — deployment config concern [SesProperties.java, application-prod.yaml]
- D4: Authority id 100/101 magic numbers — PK collision if authority sequence reaches these values; ON CONFLICT (name) DO NOTHING does not protect against PK clash with different name [V21__skillars_security_extension.sql]
- D5: phone_otp_tokens no partial unique index on active OTPs — multiple valid OTPs possible if service doesn't invalidate old tokens first; verify in Group B [V21__skillars_security_extension.sql]
- D6: verifyEmail endpoint not @RateLimited — brute-force UUID token space; Group B code [CoachRegistrationService.java]
- D7: resendVerificationEmail accepts EMAIL_VERIFIED users and re-triggers email verification instead of OTP step — flow regression; Group B code [CoachRegistrationService.java]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification (2026-06-11)
- W1: OTP hash uses `SHA-256(otp+userId)` — 6-digit OTP space vulnerable to offline pre-computation if DB is breached; hash scheme is spec-prescribed; rate limiting on `/verify-phone` is primary mitigation [CoachRegistrationService.java:hashOtp]
- W2: `verifyPhone` accepts caller-supplied `userId` with no ownership binding — spec-required field; risk mitigated by rate limiting [VerifyPhoneRequest.java]
- W3: SES conditional bean: unrecognized value for `app.ses.enabled` (e.g. `enabled: yes`) leaves `SesEmailService` unwired at startup [SesConfig.java, SesEmailServiceImpl.java]
- W4: `BaseEntity` TSID + V21 `BIGINT PRIMARY KEY` with no sequence — direct SQL inserts in future migrations or test fixtures require manual TSID generation [V21__skillars_security_extension.sql]
- W5: `ContactDetailSanitizer.PHONE_PATTERN` may redact digit-heavy name segments (e.g. "Type 2 Analyst") — pattern is spec-prescribed; refine when real-world false positives are observed [ContactDetailSanitizer.java]
- W6: `RateLimitingService` uses in-process `ConcurrentHashMap` — not cluster-safe, no eviction; pre-existing infrastructure issue not introduced by this story
- W7: `TokenErrorResponse.errorKey` field alignment with `useErrorHandler` composable — confirm when applying patches; likely aligned by naming convention [ApiAdvice.java]
- W8: `EMAIL_VERIFIED` users have no path to re-request phone OTP via `/resend-verification` — resend endpoint intentionally scoped to email verification only; add dedicated `/resend-otp` endpoint in a later story

## Deferred from: code review of skillars-1-2-skillars-design-system-foundation (2026-06-11)
- W1: `.glass-card` still uses `transition: all` — inconsistent with `.hover-lift` narrowed to `transform + box-shadow` in this story; pre-existing in glass.scss, out of story scope [src/frontend/src/css/glass.scss]
- W2: `auth`, `profile`, `session` keys missing from `en`/`de` locale stubs — pre-existing template strings not added by this story; `en-US` fallback handles them at runtime [src/frontend/src/i18n/en/index.js]
- W3: `app-bg` class has no boot-failure fallback in `App.vue` — boot file is the canonical owner per spec design; fallback in App.vue would duplicate logic; acceptable exceptional-case gap
- W4: `onSessionExpired` in MainLayout clears username but does not redirect to `/login` — pre-existing behaviour not introduced by this story
- W5: `variables.scss` dual import path — `app.scss` imports `tokens/colors` directly AND `variables.scss` also forwards to `tokens/colors`; any file that @imports `variables.scss` picks up colour tokens twice; latent build-warning risk
- W6: Rapid double-click theme toggle can briefly desync DOM attribute and `darkMode` ref — `toggleTheme` is synchronous so window is negligible in practice; acceptable
- W7: No CSP header coverage for `fonts.googleapis.com` — infrastructure/deployment concern outside story scope

## Deferred from: code review of skillars-1-1-feature-gate-configuration-layer (2026-06-11)
- `IllegalStateException` → HTTP 409 semantically wrong for missing config keys (should be 500 for misconfiguration); pre-existing ApiAdvice mapping not introduced by this story [ApiAdvice.java:existing illegalStateExceptionHandler]
- `refreshCache()` failure after `invalidate()` causes all subsequent config gets to throw 409 instead of serving stale data during DB outage; acceptable design choice for this scope [ConfigService.java]
- Scheduled refresh + lazy TTL `ensureFresh()` can both fire near-simultaneously, causing ~2x DB polls per TTL period; minor efficiency concern, spec-designed dual-refresh pattern [ConfigService.java]
- IT test fixture hardcodes bcrypt hash for test user seed SQL; follows existing project IT test pattern [ConfigResourceIT.java:setUp]

## Deferred from: code review of deploy-3-4-operational-documentation-suite (2026-06-05)
- Integrity check (table count ≥ 1) is trivially weak — a partially-loaded dump that created only one table passes; pre-existing restore-from-dump.sh limitation [docs/deployment/backup-restore.md]
- DROP DATABASE may fail if services other than `app` hold open DB connections — script stops only `app` before drop; pre-existing script limitation [docs/deployment/backup-restore.md]
- Hardcoded container UIDs (65534/10001/472) not tied to Docker image versions — upstream UID changes (historically seen with Grafana) would silently break subdirectory ownership after snapshot restore [docs/deployment/backup-restore.md]
- /tmp space check in restore-from-dump.sh validates compressed dump size only — decompressed SQL is typically 5-10x larger; mid-restore /tmp exhaustion possible; pre-existing script gap [docs/deployment/backup-restore.md]
- APP_CID capture races container registration immediately after `docker compose start app` — health-wait loop can time out on a healthy app; pre-existing script race condition [docs/deployment/backup-restore.md]
- WebhookPermanentFailure Admin API re-trigger has no endpoint or auth reference — Admin API not defined in this story's scope; needs dedicated API documentation [docs/deployment/monitoring.md]
- CallbackRateZero public callback endpoint undocumented — application-specific URL not defined in deployment docs; needs a secrets-reference or application guide entry [docs/deployment/monitoring.md]

## Deferred from: code review of deploy-3-3-external-uptime-monitoring-alert-rules (2026-06-05)
- Double notification risk if Alertmanager added later — Prometheus rules and Grafana alerting both evaluate the same infra alerts; currently no Alertmanager so only Grafana notifies, but future Alertmanager addition would cause duplicate ops notifications for every infra alert
- CallbackFailureRatioHigh divide-by-zero on zero callback traffic — pre-existing rule divides rate by rate with no zero-denominator guard; fires spuriously during quiet periods [deploy/lgtm/alerts.yml]
- node_exporter network isolation — shares `skillars-internal` network with app containers; port 9100 reachable by any compromised container; FR-9 required this topology, changing it is out of scope
- Empty notification vars cause silent delivery failure — if `GF_ALERT_NOTIFY_EMAIL` or `GF_SLACK_WEBHOOK_URL` are empty (compose defaults), Grafana provisions the contact point but notifications silently fail; intentional spec design tradeoff (`${VAR:-}`)
- DiskDataVolumeHigh requires Hetzner Volume mounted at `/opt/skillars/data` — if volume not provisioned, no metrics series exists and alert never fires; infrastructure provisioning dependency

## Deferred from: code review of deploy-3-2-scripted-restore-process (2026-06-04)
- fstab not updated after snapshot restore — new volume mounted with `mount /dev/sdb ...` but no fstab update; volume won't auto-mount on reboot if volume UUID changed. Beyond Task 2 scope [restore-from-snapshot.sh:62]
- DOMAIN sourced from .env in restore-from-snapshot.sh but never used — spec says "source .env for DOMAIN" but no reference to DOMAIN in script [restore-from-snapshot.sh:19]
- App and DB left in partial state on mid-restore failure — no recovery trap by design; operator must manually restart the app service and investigate [restore-from-dump.sh:90]
- /dev/sdb hardcoded, no filesystem UUID verification — per spec; operator confirms correct attachment at the Hetzner Console ENTER prompt [restore-from-snapshot.sh:23]

## Deferred from: code review of deploy-3-1-postgresql-backup-automation (2026-06-04)
- PGPASSWORD exposed via docker exec `-e` flag (visible in `ps aux` for duration of call) — spec-prescribed pattern; would require Docker secrets or a wrapper script to fix [deploy/backup/pg-backup.sh:22]
- Credentials visible in `/proc/<pid>/environ` when `.env` is sourced — project-wide pattern, not introduced by this story
- No retention policy — S3 dumps and Hetzner snapshots accumulate unbounded; add lifecycle rules or a rotation script in a future backup hardening story
- install-crons.sh installs cron for the invoking user with no enforcement — typically root; document the expected user or add a guard in a future hardening pass
- No upload integrity check (checksum / ETag verification after aws s3 cp) — out of scope for this story
- No handling for Hetzner API HTTP 409 (action in progress) or 422 (quota exhausted) in volume-snapshot.sh — out of scope for this story
- awscli v1 from Ubuntu apt may have `--endpoint-url` edge cases with Hetzner Object Storage — spec-approved as sufficient; revisit if upload failures occur in production

## Deferred from: code review of deploy-2-3-deployment-rollback-documentation (2026-06-04)
- No pre-deploy GHCR image existence check — no step to verify the image tag exists in GHCR before triggering deploy; typo causes mid-run failure after 2–5 min wait.
- No GHCR auth failure handling — no guidance if `docker login` fails (expired PAT, wrong token scope) before `docker compose pull`.
- Step 5 health check retry loop is manual — "retry after 10 seconds" gives no command to re-run; a simple loop would be deterministic [rollback.md:139–142].
- Partial pull failure leaves .env inconsistent — if `docker compose pull` times out, .env holds new tag but image not available; no recovery path documented [rollback.md:106].
- Auto-Revert fails if previous image deleted from GHCR — GHCR retention policies can evict old images; Auto-Revert pull then fails with `outcome=failed` and production may be in unknown state.
- `SSH_KNOWN_HOST` empty or multi-line edge cases — empty secret bypasses known-host verification; multi-line `ssh-keyscan` output is valid but undocumented [deploy.yml:27].
- Container name `skillars-app-1` hardcoded in expected output without explaining Docker Compose naming convention (project-service-index) [rollback.md:113].
- No explicit guidance if `docker compose pull app` fails mid-execution — the `&&` chain halts correctly, but no next-step is documented for auth errors, network timeout, or image-not-found.

## Deferred from: code review of deploy-2-2-manual-production-deploy-workflow-with-smoke-test-auto-revert (2026-06-04)
- `Fail workflow` step is unreachable if a notification step throws — job still fails (attributed to the notification step instead), same end outcome, low severity diagnostic issue [`.github/workflows/deploy.yml`:139-143].

## Deferred from: code review of deploy-2-1-automated-ci-build-pipeline (2026-06-04)
- No `SPRING_PROFILES_ACTIVE` in `ENTRYPOINT` — the container boots on the base profile; prod-specific beans and any config not overridden by environment variables silently use dev defaults. Recommend documenting the required env var in the Compose service definition.
- No stable/latest symbolic tag alongside the SHA tag — downstream scripts and Helm charts must be updated on every push or they silently run stale images; a `main` or `latest` tag would provide a stable pointer.

## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-04)
- Repo cloned to `/opt/skillars` before Hetzner Volume mounted — volume mount overlays `/opt/skillars/data`; benign today since repo has no `data/` content, but fragile if repo structure changes.
- `acme.json` lives on root disk — server rebuild loses all TLS certificates; Let's Encrypt rate limits make reissuance slow; no backup or restore guidance exists.
- Redis data on named Docker volume (root disk), not Hetzner persistent Volume — session/cache data lost on server rebuild [docker-compose.yml].
- No outbound firewall rules — observability containers (Prometheus, Loki, Tempo, Redis) have unrestricted internet egress; security hardening enhancement.
- Docker Hub unauthenticated pull rate limits not documented — shared Hetzner egress IPs can hit the 100/6h limit; rare but unmitigated.
- Partial `provision.sh` failure recovery undocumented — `set -euo pipefail` exits on first error; re-run may silently skip a broken install block [deploy/provision.sh].
- No rollback procedure documented for a bad `APP_IMAGE` deploy when Flyway migrations have already run — operational concern for Epic 3.
- Loki (720h), Tempo (336h), Prometheus (15d) retention periods inconsistent and undocumented — no disk sizing or tuning guidance [deploy/lgtm/].
- `docker-compose-lgtm.yaml` in repo root has anonymous Grafana auth enabled and ports exposed — not warned against production use; dev-only artifact.
- No secret rotation procedure documented (PostgreSQL password, JWT secret, Grafana admin password) — ongoing operational maintenance concern.
- JWT_SECRET minimum length stated (64+) but Spring algorithm and actual enforcement not documented — application implementation detail.
- Grafana admin initial login not explicitly verified as part of Step 7 deployment completion check.
- `provision.sh` re-run while stack is live runs `chown -R` over live data mounts — safe with current UIDs but fragile on container image UID changes.

## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)
- Firewall applied after provisioning — SSH port 22 is open to all internet IPs during the provisioning window. Deliberate ordering constraint (Hetzner firewall requires local hcloud CLI run, user may not have local clone yet). Consider documenting the exposure window or restructuring for users who already have a local clone.
- `/dev/sdb` hardcoded device path unreliable on multi-volume servers — if Hetzner changes device assignment order the mount silently fails. The doc accuracy fix is a patch (see F2); fixing the script is Story 1.1 territory [deploy/provision.sh:145].
- Repo cloned as root into `/opt/skillars` — `.git` directory sits alongside runtime data and secrets. Pre-existing architectural decision; would require a deploy-user or sparse-checkout approach to change.
- `bantime=3600s` in fail2ban is a minimal starter value — inadequate for production. 1-hour bans are bypassed by slow-rate botnets. Pre-existing Story 1.1 config [deploy/provision.sh].
- No rollback / disaster-recovery documentation — explicitly out of scope for Story 1.5; belongs to Epic 3 (Stories 3.2 and 3.4).
- git clone root (`/opt/skillars`) contains the volume data subdirectory (`/opt/skillars/data`) — `git clean` could interact with data dirs if `.gitignore` coverage lapses. Pre-existing architecture.
- `apply-firewall.sh` accumulates old SSH allowlist rules when re-run with a different `SSH_ALLOWLIST_IP` — delete step targets `0.0.0.0/0` source, not the previously-set specific CIDR. Pre-existing script bug from Story 1.1 [deploy/firewall/apply-firewall.sh].

## Deferred from: code review of deploy-1-4-security-hardening (2026-06-03)
- `err()` writes to stderr — lost in stdout-only log capture; if callers redirect stdout to a log file, error messages won't appear in it [deploy/provision.sh].
- `touch` will error without parent dir if sections are reordered — parent dir (`${DEPLOY_ROOT}/traefik`) is created in section 5 which runs first; only an issue if the script structure is modified [deploy/provision.sh].

## Deferred from: code review of deploy-1-3-lgtm-observability-stack Round 2 (2026-06-03)
- `chown` calls in provision.sh run unconditionally on every execution — safe for first provision, but re-running against a live system can interrupt in-progress container writes; document script as "first provision only" [deploy/provision.sh].
- `${MOUNT_POINT}/postgres` has no `chown` after `mkdir -p` — Postgres (UID 999) will fail to write on a fresh volume. Pre-existing from Story 1.2; fix in Story 1.4 or a dedicated housekeeping ticket [deploy/provision.sh:126].
- Duplicate logical alert definitions for PaymentFailureRateHigh, OrangeCircuitBreakerOpen, MtnCircuitBreakerOpen exist in both `alerts.yml` (Prometheus rules) and `grafana-alerts.yml` (Grafana unified alerts) — different notification paths with no Alertmanager wired; revisit when Alertmanager is added to avoid double-paging.

## Deferred from: code review of deploy-1-3-lgtm-observability-stack (2026-06-03)
- Alert rule divide-by-zero guards (CallbackFailureRatioHigh, FraudBlockRateHigh, PaymentFailureRateHigh) in `deploy/lgtm/alerts.yml` — pre-existing in root `alerts.yml`; copied per spec. Guards like `and (...) > 0` needed on all ratio denominators.
- `DbConnectionPoolHigh` alert has no label selector — pre-existing in root `alerts.yml`. Add `by (pool)` clause or label filter.
- TraceID regex `[a-f0-9]{32}` only matches lowercase hex; OTel SDKs may emit uppercase. Pre-existing in root `grafana-datasources.yml`.
- `spanStartTimeShift`/`spanEndTimeShift` of 1h creates extremely wide Loki query windows on trace drill-down. Pre-existing in root `grafana-datasources.yml`. Reduce to 1m/1m.
- Prometheus has no `depends_on: app` in compose — cold-start scrape failures on first `docker compose up`. Acceptable gap; scrapes recover once app is healthy.
- LGTM data `mkdir -p` calls gated inside Hetzner Volume device `if [ -b ]` check — consistent with existing postgres pattern. If volume is absent at provision time, Docker auto-creates dirs as root (further compounds the permission issue once it's resolved).


## Deferred from: code review of skillars-3-9-bulk-session-request-from-calendar (2026-06-16)
- W1: Race condition in `updateBatchStatusFromBooking` under concurrent coach actions — `REQUIRES_NEW` opens a fresh transaction but two concurrent individual accepts can both call this before either commits; batch status outcome is indeterminate; REQUIRES_NEW is the spec-prescribed pattern [`BookingBatchService.java`]
- W2: `bookingRepository.findById` in `BookingBatchStatusListener` runs outside explicit transaction — fires in AFTER_COMMIT context without a wrapping transaction; works under Spring Boot defaults but may fail on stricter configurations [`BookingBatchStatusListener.java`]
- W3: `parentName` null in `getParentBookings()` — pre-existing behavior, no AC requires parent name on parent's own bookings view [`BookingService.java`]
- W4: `getCoachBookingRequests` derives `parentName` from first booking in batch — data invariant guaranteed at creation; reachable only via direct DB manipulation [`BookingService.java`]
- W5: Confirm button in batch review dialog has no `hasCredits` guard — backend validates and returns error; Epic 7 will wire credit display to frontend [`BookingRequestPage.vue`] — **[AUDIT 2026-08-04: SUPERSEDED — not a defect. `BookingRequestPage.vue:249` now carries an explicit decision comment: "Do NOT gate on hasCredits: AC 3 allows booking via platform credit or full card payment." A warning banner (line 20) covers the UX. Retained only so the decision is not re-litigated.]**

## Deferred from: code review of skillars-3-8-rescheduling-duplication-reminders (2026-06-16)
- D1: `completionLoading` shared across all reschedule/duplicate store actions — consumers cannot distinguish which operation is in-flight; per-booking scoping refs partially mitigate; pre-existing [`booking.store.js`]
- D5: `COACH` value in `proposedBy` DB constraint allowed but never set — coach-initiated reschedule path not in scope this story; DB is future-proof [`BookingRescheduleRequest.java`]
- D6: Service-layer tests use Mockito unit test pattern (`@ExtendWith(MockitoExtension.class)`) — story spec Task 20/21 explicitly defined unit tests; integration coverage provided by `RescheduleResourceIT` [`RescheduleServiceTest.java`, `BookingDuplicationServiceTest.java`]
- D7: `datetime-local` input in reschedule dialog coerces proposed times to browser local timezone — the ISO-8601 string sent to the API reflects the user's local offset, not the coach's canonical timezone; browser local time intent is ambiguous (parent and coach may be in different timezones); add a visible canonical timezone hint label next to the inputs in a future UX polish story [`ParentBookingsPage.vue`]

## Deferred from: code review of skillars-3-10-session-pack-expiry-pause-management (2026-06-17)
- D2: `@TransactionalEventListener(AFTER_COMMIT)` failure silently loses coach cancellation notifications — if email dispatch fails after commit, the coach is never notified even though bookings are `CANCELLED`. Event delivery reliability (retry/DLQ) is an infrastructure-wide concern not introduced by this change. [`BookingEmailListener.java`, `SessionPackEmailListener.java`]

## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system (2026-06-20)
- Def1: `expireStaleReservations()` loop has no circuit breaker — sustained high rate of new expired reservations could delay other scheduled work indefinitely; no max-iteration or max-time guard. [`QuotaReservationTimeoutService.java:expireStaleReservations`]
- Def2: `VideoQuotaReservation.status` as raw String vs enum — intentional per story notes to avoid JPA enum binding complexity with raw SQL paths; values DB-constrained via CHECK constraint. [`VideoQuotaReservation.java:status`]
- Def3: Long arithmetic overflow in `storageUsedBytes + requestedBytes` — theoretical at practical quota sizes (max ~9.2 EB); no guard exists. [`QuotaService.java:check`, `QuotaService.java:reserve`]
- Def4: `commit()` no-op on already-COMMITTED is indistinguishable from not-found — `updated == 0` is logged as debug; callers cannot differentiate idempotent from non-existent handle; intentional idempotency design. [`QuotaService.java:commit`]
- Def5: `expireBatch()` exception mid-loop not caught — exception terminates the do-while; Spring `@Scheduled` catches it at the framework level; next firing will retry. [`QuotaReservationTimeoutService.java:expireStaleReservations`]
- Def7: `VideoConfig.quotaProviderValidator` consistency guarantee logging — AC 10 requires logging the guarantee at startup; validator not in this diff; needs verification that it calls `getConsistencyGuarantee()` and logs it. [Out-of-diff verification needed]
- Def8: `BandwidthResetService` period drift when job runs late — `bandwidth_period_start` set to `NOW()` on actual run date, not 1st of month; next period boundary shifts accordingly; acceptable drift for non-billing context. [`BandwidthResetService.java:resetMonthlyBandwidth`]

## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system Run 2 (2026-06-20)
- Def9: `sumActiveReservedBytes` includes expired-but-unreaped ACTIVE rows — brief (<60s) window between expiry and reaper firing causes conservative over-reporting; intentional design. [`VideoQuotaReservationRepository.java:22`]
- Def10: `BandwidthResetService` full-table lock risk at month boundary — single unpartitioned UPDATE locks all video_quotas rows, blocking concurrent `reserve()` calls; scaling concern. [`BandwidthResetService.java`]
- Def11: `bandwidth_used_bytes` never incremented — tracking deferred to Story 6.3 (streaming/playback pipeline); schema and reset job created now so schema is ready. [`QuotaService.java`] — **[AUDIT 2026-08-04: STILL OPEN. Story 6.3 has shipped; the column is only written by the initial INSERT in `QuotaService.java:158` and by the monthly reset — never incremented on playback.]**
- Def12: `QuotaConfigService.resolveTierKey()` exhaustive switch will throw `MatchException` if `CoachSubscriptionTier` enum grows — safe now but fragile if a new coach tier is added. [`QuotaConfigService.java:39`]
- Def13: `DrillUploadService` video replacement orphans old quota reservation — pre-existing; replacing a non-READY video's `DrillVideoRef` does not call `release()` on the old reservation; orphaned bytes held until reaper. [`DrillUploadService.java:~69-85`]
- Def14: `DrillUploadService.deleteVideo()` TOCTOU on `clearVideoId`/`existsByVideoId` — pre-existing; concurrent deletes on different drills sharing the same `videoId` can publish `VideoPhysicalDeletionEvent` twice. [`DrillUploadService.java:~104-108`]
- Def16: `AccountManagementFacade` phone registration NullPointerException — pre-existing; `getEmail().toLowerCase()` throws NPE for phone-only registrations. [`AccountManagementFacade.java:~231`]
- Def17: `AdminVideoService.deleteVideo()` — `release()` exception inside `TransactionTemplate` kills delete transaction — pre-existing. [`AdminVideoService.java`]
- Def18: V53 platform_config IDs 117-132 hardcoded — verify against all intermediate migrations (V43–V52) before deploying; any ID conflict causes Flyway failure. [`V53__video_quota_system.sql:32-50`]
- Def19: `sumActiveReservedBytes` theoretical `ClassCastException` — PostgreSQL BIGINT SUM typically maps to Long via JDBC but no compile-time guarantee. [`VideoQuotaReservationRepository.java:22`]

## Deferred from: code review of skillars-6-2 (2026-06-22)

- Def22: `UploadSessionExpiryScheduler` releases quota outside TX then marks session EXPIRED in separate TX — non-atomic; safe because `release()` is idempotent, but ordering is fragile to future refactors. Pre-existing design decision. [`UploadSessionExpiryScheduler.java`]

## Deferred from: code review of skillars-6-2 pass 5 (2026-06-22)

- Def24: `failTranscoding()` state-transition rollback on `quotaProvider.release()` exception — `failTranscoding()` is `@Transactional`; if `QuotaService.release()` throws (DB connection loss), the entire TX rolls back including `transitionOperationalState(FAILED)`, leaving the video in `PROCESSING`. Scheduler retries recover normally; only fails permanently if max-attempts exhaust during a persistent quota DB outage. Architectural fix: separate state transition and quota release into independent TXs (same pattern as `completeTranscoding()`). [`VideoService.java:failTranscoding`]

## Deferred from: code review of skillars-6-3-content-moderation-pipeline (2026-06-22)

- W1: Feature flags default false in all environments — the spec explicitly documents this as the sprint completion criterion: "story is complete for sprint purposes when the placeholder compiles and the feature flag gates it off in all environments." Deployment enablement is an ops concern outside this story's scope. [`application.yaml`, `AppFeature.java`]
- W2: VideoIntelClientImpl blocking RestTemplate thread exhaustion — if VideoIntelClientImpl is ever implemented using RestTemplate (synchronous polling of a 5-minute GCP async operation), it will hold async thread pool threads for the full duration. This concern is subsumed by D2 (VideoIntelClientImpl scope decision); if D2 resolves to implement in Story 6.3, a proper non-blocking implementation must address thread exhaustion. [`VideoIntelClientImpl.java`, `VideoIntelConfig.java`]
- W3: SLA monitor re-queues via Spring events, not outbox — `ModerationSlaMonitorService` publishes `VideoModerationRetryEvent` directly via `ApplicationEventPublisher`. If the app crashes after `findScanningOlderThan()` returns but before events publish, retry intents for that cycle are lost; next cycle recovers. Full outbox support deferred to a future hardening story. [`ModerationSlaMonitorService.java`]

## Deferred from: post-implementation review of skillars-6-3 (2026-06-22)

- RW1: SSE subscribe → onStatusChanged race — state transition committed between `videoService.findById()` and `emitter.send(currentStatus)` is missed. Polling fallback mitigates. Architectural limitation of SSE without event sourcing. [`VideoSseService.java:39`, `VideoEventResource.java:39`]
- RW2: scanned_at misleading on upsert retry path — `@Column(updatable=false)` retains original failed-attempt timestamp even when SLA retry overwrites outcome to PASSED. Fix requires append-only per-attempt rows (architectural scope beyond this story). [`VideoModerationScan.java:39`]
- RW3: Quota release outside transaction on encoding.failed in SCANNING — same pattern as Def24; `quotaProvider.release()` after committed SCANNING→FAILED transition; if release throws, quota is permanently leaked. [`WebhookEventProcessorScheduler.java:185-187`]

## Deferred from: code review of skillars-6-6-player-video-management-portal (2026-06-24)
- W2: N+1 queries in `VideoApprovalResource.listPendingApprovals()` — one `playerProfileService.getPlayerNameByPlayerId()` + one `videoRepository.findById()` per approval row; acknowledged in spec TODO; acceptable for single-family use. [`VideoApprovalResource.java`]
- W3: V60 DDL ACCESS EXCLUSIVE lock risk — `ALTER TABLE main.videos DROP CONSTRAINT / ADD CONSTRAINT` takes table-level ACCESS EXCLUSIVE lock with no `SET lock_timeout`; can cause connection pile-up under concurrent video uploads. [`V60__video_approval_portal.sql`]
- W4: `autoRejectExpired` JPQL uses `current_timestamp` (returns `java.util.Date`) on an `Instant`-typed field — type mismatch; method is explicitly NOT WIRED (comment says so); safe until a scheduler is added. [`VideoApprovalRequestRepository.java:autoRejectExpired()`]
- W5: `@GeneratedValue(AUTO)` on `VideoApprovalRequest` entity vs `UUID DEFAULT gen_random_uuid()` in SQL — Hibernate 6 AUTO may allocate a sequence-based Long for AUTO strategy on non-Long PK; pre-existing entity pattern; verify Hibernate dialect resolves UUID correctly. [`VideoApprovalRequest.java`]
- W6: `PURGED` in `VideoManagementPage.onStatusChanged()` is dead code — `VideoSseService.TERMINAL_STATES` does not include PURGED; this state is never pushed via SSE; handler branch is unreachable. [`VideoManagementPage.vue:onStatusChanged()`]
- W7: `@Observed(name = "video.approvals")` at class level on `VideoApprovalResource` — loses per-operation observability granularity vs. per-method `@Observed` pattern used throughout `VideoResource`; minor deviation from project pattern. [`VideoApprovalResource.java`]

## Deferred from: code review of skillars-7-2-session-payment-lifecycle-credit-wallet (2026-06-24)
- D4: Raw `String` fields for `type` (ParentCreditLedger) and `status` (BookingPayment) instead of Java enums — DB constraint guards correctness; higher migration cost to add enum mapping

### Group 2 deferred (Services) — 2026-06-24
- D6: `BookingDisputedEvent` handler not implemented — Dev Notes defer to Story 10.x [`PaymentLifecycleService.java`] — **[AUDIT 2026-08-04: STILL OPEN. Epic 10 has shipped; `BookingDisputedEvent` has zero references anywhere in the codebase — the event type itself was never created.]**
- D7: `SessionPackExhaustedEvent.playerId` contains parentId semantically — pre-existing event contract; Story 7.3 [`PackSessionService.java`] — **[AUDIT 2026-08-04: STILL OPEN. Story 7.3 has shipped; `SessionPackExhaustedEvent.java:10` still declares a bare `Long playerId`.]**
- D9: EUR currency hardcoded in `chargeAndCapture` — single-currency now; make configurable later [`StripePaymentGateway.java`]

### Group 4 adversarial deferred (Booking module) — 2026-06-24
- D13: `getParentBookings` does not clamp negative `effectiveCredits` to 0 — inconsistency with `getParentPlayerSchedule`; pre-existing [`BookingService.java:316`]
- D14: `CANCEL_PARENT` not permitted from `PAYMENT_PENDING` — booking stuck if Stripe webhook never fires; design gap; MVP acceptable; Story 7.3 [`BookingStateMachine.java:30`] — **[AUDIT 2026-08-04: STILL OPEN. Story 7.3 has shipped; `BookingStateMachine.java:30-32` maps PAYMENT_PENDING to PAYMENT_CAPTURED and PAYMENT_FAILED only.]**
- D15: Past-elapsed `requestedStartTime` at CANCEL_PARENT gives NONE refund eligibility — correct path is NO_SHOW_COACH event; edge case [`BookingService.java:471`]
- D16: `Booking` identity columns (`parentId`, `playerId`, `coachId`) lack `updatable = false` — defence-in-depth; no current mutation path; pre-existing [`Booking.java:31-37`]

### Group 3 adversarial deferred (API + Contracts) — 2026-06-24
- D11: `getActiveCoachTier` returns 204 No Content when no active tier found — spec says "returns empty if none" (ambiguous); 204 is unusual for a typed GET endpoint; more idiomatic would be 404 or 200/null; defer until client null-handling issues surface [`SessionPackPaymentResource.java:101-105`]

### Group 6 adversarial deferred (Tests) — 2026-06-24
- D20: `CashOutServiceTest` happy-path sets `lastPaymentIntentId` but `CashOutService.processCashOut()` may read `stripePaymentMethodId` for the refund; if field mismatch is confirmed, the happy-path test is verifying a null PI and must be corrected [`CashOutServiceTest.java:54`, `CashOutService.java`]
- D21: Pack deduction failure path (`PackSessionService.deductSession()` throws) entirely untested at unit level — requires new mock infrastructure for `persistenceService` in `CreditRoutingTest` (or a dedicated `PackBasedBookingDeclineTest`); deferred to Story 7.3 [`CreditRoutingTest.java`, `PaymentLifecycleService.java:60-64`]
- D22: Credit routing boundary: `balance == sessionPrice` (Case A/B boundary, stripeAmount=0) and cashout `amount == balance` (wallet goes to -feeAmount) — low severity, current behaviour correct; no test pins the boundary [`CreditRoutingTest.java`, `CashOutServiceTest.java`]
- D23: Unit-level idempotency ledger-count guard (`duplicateEvent_idempotencyNoOp` verifies skip but not that ledger mock was never called) — covered by `PaymentWebhookIdempotencyIT`; low value to add unit-level assertion

## Deferred from: code review of skillars-7-3-cancellation-refund-reliability-strikes (2026-06-25)
- D1: `buildSort()` has identical branches for "price" and "rating" — pre-existing; both fall back to `displayName`; price sort is applied in Java post-enrichment [`CoachSearchService.java:buildSort`]
- D3: `GET /coaches/me/strikes` has no pagination — unbounded list; low risk at current scale [`ReliabilityStrikeResource.java`]
- D4: Concurrent strike issuance race — two simultaneous events for the same coach may both read count=N and both fire the threshold event; inherent to non-locking count approach [`ReliabilityStrikeService.java:issue`]
- D5: `CoachCancellationHistory.createdAt` with `@Column(updatable=false)` + `@PrePersist` — in-memory entity is null until DB round-trip if ever used with batch `saveAll`; low risk given single-save usage [`CoachCancellationHistory.java`]

## Deferred from: code review of skillars-7-5-revenue-dashboard-financial-reporting (2026-06-26)
- D1: Running balance incorrect when two ParentCreditLedger entries share an identical createdAt instant and straddle a page boundary — the strict-less-than predicate in sumByParentIdAndCreatedAtBefore excludes the prior-page twin from the opening balance, understating the running balance for the current page by that twin's amount; extremely rare in practice; inherent in the chosen pagination anchor design [RevenueReportingService.java:211]

## Deferred from: code review of skillars-8-1-messaging-module-foundation-conversation-threads (2026-06-26)
- D1: PLAYER role identity mismatch — conv.getPlayerId() stores PlayerProfile.id (TSID Long) but callerUserId is user.id (different TSID sequence); verifyIsParty always returns false for PLAYER callers and findActiveByPlayerId always returns empty list. By-design for Story 8.1 (players are shadow accounts that don't authenticate independently); Story 8.2 must introduce a playerId→userId mapping. [MessagingService.java:verifyIsParty, getConversations] — **[AUDIT 2026-08-04: STILL OPEN. Story 8.2 has shipped; no playerId→userId mapping exists anywhere in `platform.messaging`.]**
- D2: N+1 query pattern in getConversations — 3 queries per conversation (findLastApproved, countUnread, resolveOtherPartyName); acceptable for MVP conversation volumes; batch-optimize when conversation counts grow. [MessagingService.java:toSummary]
- D3: content.length() counts UTF-16 code units, not Unicode codepoints — a 1000-emoji message uses 2000 surrogate-pair chars and passes the 2000-char guard but contains only 1000 codepoints. No downstream column length limit today; address if DB text constraints are added. [MessagingService.java:sendMessage]
- D4: Instant.EPOCH as "never read" sentinel is undocumented — all messages with createdAt > epoch count as unread, which is correct for current data; fragile if a historical data migration backfills timestamps at or before epoch. [MessagingService.java:toSummary]
- D5: Two separate Instant.now() calls in sendMessage — message.createdAt is captured before conv.lastMessageAt, creating a few-millisecond gap. Any consumer using lastMessageAt as a cursor may miss the just-sent message if createdAt < lastMessageAt. [MessagingService.java:sendMessage]
- D6: Default role arm (else/default → "PLAYER") silently absorbs unknown or null role values across verifyIsParty, resolveLastReadAt, and updateLastRead. Safe while resolveRole() is the sole producer; add an explicit throw for unknown roles in a hardening pass. [MessagingService.java]

## Deferred from: code review of skillars-10-4-gdpr-data-tools-account-deletion (2026-06-30)
- D1: DB connection held during S3 upload — `GdprExportService.buildExport()` annotated `@Transactional` keeps a DB connection checked out from the pool for the entire ZIP build + S3 put. Resolved if Patch 1 (remove `@Transactional`) is applied; defer this entry only if Patch 1 is skipped. [GdprExportService.java:180]
- D2: `.distinct()` on Booking list may silently no-op — if `Booking` entity doesn't override `equals()`/`hashCode()`, stream `.distinct()` uses object identity and won't deduplicate. Unlikely to manifest given role separation, but address in a JPA entity hygiene pass. [GdprExportService.java:250]

## Deferred from: code review of skillars-deferred-2 (2026-07-01)
- D1: `BookingExpiredEvent`/`BookingReminderEvent`/`BookingConfirmedEvent` constructors are invoked positionally with 6-8 raw same-typed arguments across new test files — pre-existing lack of a builder on these event classes; a future field reorder could silently miscompile or swap same-typed fields with no test catching it. [`src/main/java/com/softropic/skillars/platform/booking/contract/`]

## Deferred from: code review of skillars-deferred-3 (2026-07-01)
- D1: V76 partial index predicate hardcodes status literals (`'EXHAUSTED', 'EXPIRED'`) with no in-diff verification against the full status enum — a future added status could silently fall inside the "active" partial index instead of being excluded. [`src/main/resources/db/migration/V76__missing_indexes.sql`]
- D2: No test verifies NULL `provider_asset_id` videos can coexist, the actual rationale for the new partial unique index; a regression turning it into a full unique index would pass current tests undetected. [`src/test/java/com/softropic/skillars/platform/video/repo/VideoRepositoryIT.java`]
- D3: Concurrency test `deployTemplateTwiceForSameBooking_secondFails` masks `barrier.await()` failures (`catch (Exception ignored) {}`) and accepts both the pre-check 403 and DB-race 409 as valid outcomes, so it can pass without ever exercising the new `uq_sessions_booking_id` race path. [`src/test/java/com/softropic/skillars/platform/session/api/SessionTemplateResourceIT.java`]

## Deferred from: code review of skillars-deferred-4 (2026-07-02)
- D1: `lockAtMostFor` timeouts on `QuotaReservationTimeoutService` and `NeglectedSkillDetectionService` are not validated against their unbounded work loops (an un-chunked `do/while` batch drain and an un-chunked per-player loop, respectively) — under data growth, ShedLock could force-expire the lock mid-run and let a second instance start an overlapping execution, reopening the exact race AC1 exists to close. [`src/main/java/com/softropic/skillars/platform/video/service/QuotaReservationTimeoutService.java:28`, `src/main/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionService.java:58`]
- D2: No log or metric is emitted when `@SchedulerLock` skips a run because another instance already holds the lock — indistinguishable in production from a job silently failing to run due to a bug. [`src/main/java/com/softropic/skillars/platform/notification/config/AsyncConfig.java`]
- D3: `@SchedulerLock` and `@Transactional` are stacked on the same method with no explicit `@Order`, so their AOP advisor nesting order (lock vs. transaction boundary) is unspecified — plausible but unconfirmed risk that the distributed lock could release before the DB transaction commits. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java:40`, `BookingReminderScheduler.java`, `src/main/java/com/softropic/skillars/platform/video/service/BandwidthResetService.java`]

## Deferred from: code review of skillars-deferred-8 (2026-07-02)
- D1: `resolveParentName()` can render `"null null"` when a user's `first_name`/`last_name` are null — pre-existing behavior, not introduced by this diff; the new `parentName` assertion (AC5) only checks non-null/non-empty, so this garbage value would pass undetected. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`]
- D2: `declineBooking_wrongCoach_returns403` reads `createResp.getBody().get("id")` without asserting the booking-creation POST succeeded first — mirrors the same pre-existing gap in `acceptBooking_wrongCoach_returns403`; a transient creation failure surfaces as an opaque NPE/404 instead of a clear assertion failure. [`src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java`]
- D3: Hardcoded `PLAYER_ID = 9360000001L` in the new IT is reused against the shared `SecurityIT.SEC_DATA_SQL_PATH` fixture — possible cross-test collision risk if that fixture seeds rows for the same player ID outside the tables cleaned in `@AfterEach`; not provable from this diff alone. [`src/test/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionServiceIT.java:41,47`]

## Deferred from: code review of skillars-11-3-remove-legacy-session-pack-system (2026-08-04)
- D1: `V89__drop_legacy_session_packs.sql`'s `DROP TABLE` has no `IF EXISTS` guard — not blocking (Flyway won't re-run an applied migration, table confirmed empty at this dev/UAT stage), but there's no prior DROP TABLE in this codebase to establish a convention either way; adopt `IF EXISTS` for future destructive migrations. [`src/main/resources/db/migration/V89__drop_legacy_session_packs.sql`]
- D2: Code deletion and the destructive `DROP TABLE` migration ship together with no staged rollout (remove references first, verify, drop table in a later release). Not applicable now — no live/production system exists yet — but relevant once this app has real deployed traffic and rolling deploys. [`src/main/resources/db/migration/V89__drop_legacy_session_packs.sql`]
- D3: `session.homework_assignments.pack_id` has no FK and now points at nothing meaningful — pre-existing design (column never had a `REFERENCES` clause, per V45), not introduced by this diff. [`src/test/java/com/softropic/skillars/platform/session/api/HomeworkResourceIT.java`]

## Deferred from: code review of skillars-deferred-10 (2026-07-02)
- D0: `pr-build.yml`'s Docker build never runs/scans the built image (`push: false`, no `load: true`) — user decision: `deploy.yml`'s existing smoke test is the real safety net; add `load: true` + a smoke command here only if PR-time runtime validation becomes worth the added CI cost. [`.github/workflows/pr-build.yml`]
- D1: `ci.yml`'s push trigger (`branches: [main]`, untouched by this diff) has the same branch-name mismatch flagged as a patch in `pr-build.yml` — the repo's default branch is `master`. Pre-existing, not introduced by this diff, but potentially means the image-publish pipeline has never auto-triggered on push; AC1 forbids changing `ci.yml`'s behavior in this story so this needs a dedicated follow-up. [`.github/workflows/ci.yml:4`]
- D2: No Dependabot/Renovate config for the `github-actions` ecosystem — the new SHA pins won't receive automated update PRs and will silently rot over time. [`.github/workflows/`]
- D3: `ci.yml` and `pr-build.yml` duplicate the same `docker/build-push-action` SHA pin with no shared/reusable workflow — future version bumps require editing both files in lockstep. [`.github/workflows/ci.yml`, `.github/workflows/pr-build.yml`]
- D4: No vulnerability/security image scan (e.g. Trivy, Grype) in `pr-build.yml`, despite this batch of changes being framed as "hardening." [`.github/workflows/pr-build.yml`] Prefer open source
- D5: No Docker build-layer caching in `pr-build.yml` (only `~/.m2` is cached) — every PR triggers a fully cold image build. [`.github/workflows/pr-build.yml`]
- D6: The new "defence in depth" doc callout asserts Hetzner's outage behavior ("the cloud firewall remains active... during a Hetzner API outage") as fact with no citation. [`docs/deployment/first-time-setup.md`]


## Deferred from: code review of skillars-11-1-payment-path-parity-gaps (2026-08-03)
- D1: Partial/mismatched `confirmedCancellationIds` lets `PackSessionService.pausePack()` apply the pause even when not all currently-conflicting bookings are confirmed for cancellation (or the confirmed ids don't match any real conflict) — verified byte-for-byte identical to legacy `SessionPackService.pausePack()`; AC4 explicitly requires mirroring legacy here. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D2: Silent `.orElse(null)`/`.orElse("")` defaulting for missing coach/parent records in `pausePack` and `SessionPackForfeitureScheduler` (blank email, `"Coach"` placeholder) — identical to legacy's own resolution pattern. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`, `SessionPackForfeitureScheduler.java`]
- D3: `pauseStartDate` "in the past" check truncates to UTC day boundary, ignoring coach/parent timezone — byte-for-byte identical to legacy `SessionPackService.pausePack()` lines 205-216. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D4: `configService.getLong("pack.pause.maxDays")` has no defensive default if the config key is missing/non-numeric — identical usage to legacy, same pre-existing risk profile. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D5: `pausePack` holds a pessimistic row lock across booking cancellations and event publishing within one `@Transactional` method — same single-transaction shape as the legacy method this story mirrors. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D6: Inconsistent concurrency control: `pausePack` takes a pessimistic lock but `deductSession`/`restoreSession`/`extendPack` do not — those methods predate this story and are unmodified except for call-site signature changes. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`, `SessionPackPaymentService.java`]
- D7: `SessionPackForfeitureScheduler` doesn't re-verify `expiresAt` immediately before forfeiting inside the per-row transaction, leaving a window where a concurrent extension could still get forfeited — inherent to the legacy-mirrored select-then-per-row-transaction scheduler shape. [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureScheduler.java`]
- D8: TOCTOU between the conflicting-bookings query and the per-booking `cancelDueToPause` calls in `pausePack` — same risk shape as the legacy method being mirrored. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D9: Stringly-typed computed `status` field and hardcoded `CONFLICT_STATUSES` list rather than shared enums — consistent with existing codebase convention; legacy also uses string status constants. [`src/main/java/com/softropic/skillars/platform/payment/contract/SessionPackPurchaseResponse.java`, `PackSessionService.java`]

## Deferred from: code review of skillars-11-2-cutover-booking-and-frontend (2026-08-03)
- D2: Pack-selection-criteria mismatch when a player+coach pair has 2+ simultaneously-active packs: the frontend displays the soonest-expiring pack, while the backend's `getActivePackId` (duplication/homework-gating) picks the oldest-created pack FIFO — the pack shown to a parent may not be the one actually deducted. May be intentional (display urgency vs. FIFO consumption order), needs product input. [`src/frontend/src/pages/parent/ParentPlayerPortalPage.vue`, `src/frontend/src/pages/parent/SessionPackPurchasePage.vue`, `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java`]

## Deferred from: skillars-deferred-11-stripe-card-collection
- D1: Coach subscription card collection (`CoachSubscriptionPage.vue`, which still has the raw "Payment Method ID" text input this story removed from the player-subscribe path) is blocked on a real schema constraint: `StripeCustomer`'s `@Id` is `parent_id` (a `Long` user id), and `POST /api/payment/setup-intent` / `GET /api/payment/payment-method` are both `@PreAuthorize(HAS_PARENT_ROLE)`. Supporting coach card collection requires either a second customer table or re-keying `payment.stripe_customers` — a schema migration with its own design decision, out of scope here. [`src/main/java/com/softropic/skillars/platform/payment/repo/StripeCustomer.java`, `src/frontend/src/pages/coach/CoachSubscriptionPage.vue`]
- D2: 11.2 D2 (pack-selection-criteria mismatch: frontend shows soonest-expiring, backend `getActivePackId`/`findActivePackId` picks oldest-created FIFO) still needs product input — this story's AC 7 deliberately preserved the existing FIFO backend ordering rather than resolving the mismatch. [`src/frontend/src/pages/parent/SessionPackPurchasePage.vue`, `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D3: AC 5's instruction to remove the shared i18n key `subscription.paymentMethodId` (and `subscription.paymentMethodHint`) turned out to be still in active use by the explicitly out-of-scope `CoachSubscriptionPage.vue` — removing it would have broken that page's raw payment-method-id input. Kept both keys in all four locale files; only removed the confirmed-orphaned `subscription.player.paymentMethodRequired`. [`src/frontend/src/pages/coach/CoachSubscriptionPage.vue`, `src/frontend/src/i18n/{en-US,en,de,fr-FR}/index.js`]

## Deferred from: code review of skillars-deferred-12-booking-payment-review-integrity (2026-08-04)
- D1: AC3's coach-suspension race IT (`BookingServiceConcurrencyIT#createBookingRequest_coachSuspendedAfterUnlockedRead…`) orders the two threads with `Thread.sleep(1500)` rather than a real barrier. If the booking thread is slow to reach its unlocked read, the suspender commits first, the *unlocked* check rejects with the same `COACH_UNAVAILABLE`, and the test passes green even with the `entityManager.refresh(...)` line deleted. RED-then-GREEN was verified by hand at implementation time, so the fix is proven — but the test is not a durable guard. Assert on something only the locked re-read can produce. [`src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceConcurrencyIT.java:450`]
- D2: Parent-cancel can race the AFTER_COMMIT payment settle. `cancelBookingAsParent` reads `PAYMENT_PENDING`, computes `refundEligible=false` and commits `CANCELLED_PARENT` while `PaymentLifecycleService` is mid-capture; the listener's subsequent `PAYMENT_CAPTURED` from `CANCELLED_PARENT` is an illegal transition, thrown and swallowed inside the listener. Net: money captured, booking cancelled, no refund, no compensation. `transitionInternal` takes no lock and `@Version` does not help across the two commits. Narrow window, but real. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java:34`, `BookingService.java:563`]
- D4: `readStatusOrThrow` (extracted from `transitionInternal`) is now also used by `cancelBookingAsParent`, so a booking whose `status` column holds a value outside `BookingStatus` returns 404 "booking not found" to a parent who can see it in their list. A 409/5xx is the honest answer — the row exists, the server cannot interpret it. Only reachable via bad data or a rolling deploy that introduces a status the running node does not know. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:535`]
- D5: `BatchAcceptPaymentIT` teardown issues unscoped `DELETE FROM main.refresh_tokens`, `main.login_attempts` and `main.sec`, and wraps `DELETE FROM payment.parent_credit_ledger` in `SET SESSION session_replication_role = 'replica'` / `'origin'` without try/finally — an exception in between returns a pooled connection with FK enforcement disabled for whichever test picks it up next. This mirrors the existing fixture pattern across the IT suite, so it is a suite-wide cleanup rather than a defect of this story. [`src/test/java/com/softropic/skillars/platform/booking/service/BatchAcceptPaymentIT.java:784`]
