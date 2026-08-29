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

## Last audit: 2026-08-24 (deferred-work.md pruning pass)

This file's own first rule above says closed items are **deleted outright, not kept with a tag** — but
recent stories (`skillars-deferred-41` onward) drifted from that: they left the original bullet in place
and appended a `[CLOSED by ...]` / `[STALE ...]` annotation instead of removing it, so the file grew even
as more items closed. This pass restores the stated convention: every bullet carrying a `[CLOSED by ...]`
or `[STALE ...]` tag (175 total — 136 `[CLOSED]`, 39 `[STALE]`, added across every story from
`skillars-deferred-16` through `skillars-deferred-60`) was deleted outright, mechanically, by a script
verified against this exact file before running (line-for-line reconstruction check: every surviving
non-blank line matches the pre-prune file's non-tagged content, in order, with nothing added or reworded).
28 `## Deferred from:`/`###` section headers that had zero items left after their tagged bullets were
removed were deleted along with them, since an empty section serves no purpose. `[PICKED UP by ...]`
bullets (claimed by a story, not yet shipped), `[DISMISSED ...]` bullets, decision-needed items, and every
untagged open item were left untouched, including their full original text, with one exception: a
section-header's non-bullet intro paragraph is deleted along with it when every bullet under that header
is tagged (one instance — the old `## Deferred from: code review of skillars-deferred-28-...` header's
"review-layer coverage was incomplete" process footnote, whose own three bullets were all `[CLOSED by
...]`-tagged and removed, collapsing the section per this pass's own stated rule). Nothing in this pass
re-verified or re-judged any open item, it only removed items already closed by a prior pass.
File size: 1854 → 1523 lines (exact). **Not touched:** the content of every other `## Last audit:` section in
this file (each is a narrative record of its own audit, not itself a set of taggable items — confirmed
none contain a `[CLOSED` or `[STALE` tag) and every item this pass didn't already find carrying one of
those two tags. Full removed text remains recoverable from git history of this file, per this file's own
first rule.

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

## Last audit: 2026-08-05 (skillars-deferred-15 story creation)

Written while scoping `skillars-deferred-15`. Like the `deferred-14` story-creation audit and unlike a
code-review audit, this one had no independent review layers — every claim comes from a direct read of
the named file at commit `cc8d2a8`. Scope, so the gaps stay explicit:

- **Five items annotated `OWNED BY skillars-deferred-15`, each naming the AC that closes it** (they are
  deleted by that story once shipped, not now): the `PAYMENT_PENDING` sweeper (`deferred-14` story
  creation D1), `deferred-14` review D1 (reschedule accept/decline TOCTOU), D2 (no suspension check on
  the accept paths) and D4 (batch-status formula), and `skillars-7-2` Group 2 D2 (pack expiry-warning
  email spam). Note that the sweeper item is closed **narrowly** — see the correction two bullets down
  and the new D1 below.
- **Two of those items were found to be mis-stated, and are corrected inline:**
  - `deferred-14` D2 says the accept paths never *re-check* `SUSPENDED` after the lock. They never check
    it **at all** — `acceptBooking`, `acceptOneBooking` and `acceptReschedule` verify ownership only.
    And `AdminCoachEnforcementService.suspendCoach:101` writes the suspension through a plain
    `findById`, so the lock the accept paths already hold serialises against nothing.
  - `skillars-7-2` Group 2 D2 says the notifier sends up to 14 warning emails per pack. Today it sends
    **zero**: `SessionPackExpiryNotifier.notifyExpiringPacks:32-65` is not `@Transactional` and opens no
    `TransactionTemplate`, while `SessionPackEmailListener.onExpiryWarning:37-38` is an
    `@TransactionalEventListener(AFTER_COMMIT)` with default `fallbackExecution = false`, so the event is
    discarded. `SessionPackForfeitureScheduler:42-56` publishes inside a transaction and works —
    the difference between the two schedulers is the bug. The 14-email behaviour is what appears the
    moment delivery is fixed, which is why the dedupe column must ship in the same change.
- **Downgraded, not deleted:** `skillars-8-2` D1/D2 (deleted-player `UserNotFoundException` crashing
  `getConversations()`). The throw is real (`AgePolicyService:52-54`, unguarded at
  `MessagingService:102-105`), but **no path in `src/main` deletes a `player_profiles` row** —
  `GdprErasureService` anonymises the `User` and deletes development data while leaving the profile, and
  `UserAdminService.deleteUserInTransaction` deletes only never-activated `User` rows. Reachable via a
  data-integrity failure, not ordinary deletion. Annotated inline; still open, still messaging scope.
- **Examined and deliberately left alone:** `deferred-14` review D3 (its own inline audit already
  establishes the scenario is unreachable behind the 365-day edit rule — a design limitation, not a live
  defect) and `skillars-10-2` D1 (`AFTER_COMMIT` refund drop — the same platform-wide event-reliability
  concern `deferred-13` and `deferred-14` both left; `deferred-15`'s sweeper is deliberately narrow and
  does not generalise into an outbox).
- **Added by this audit, after a review of the draft story caught two wrong claims in it:** one new item
  under `## Deferred from: skillars-deferred-15 story creation (2026-08-05)` — there is no durable
  pre-capture record for a Stripe charge, so a credit-funded booking stranded in `PAYMENT_PENDING`
  cannot be swept safely and `deferred-15` AC1 deliberately only reports it. The draft of that AC had
  claimed two "money-safety guards" made a full sweep safe; both were wrong (details in the new item),
  and finding that is the reason the sweeper shipped narrower than `deferred-14`'s D1 implied it could.
- **Correction to `deferred-14` story-creation D1's framing, recorded before it is deleted.** It reads as
  though grace-period length is the only design question for a sweeper. It is not: on the credit path
  the Stripe capture precedes every durable write that records it, so no grace period makes an automated
  decline safe there. The item is still closed by `deferred-15` — but by a narrower fix than it implied.
- **Not re-checked:** every other item in this file. No `deploy-*` section was read — the same gap the
  2026-08-04 and both 2026-08-05 audits flagged, now four audits running.

### Implementation outcome (2026-08-05, `skillars-deferred-15` shipped)

All five owned items are **closed by shipped code and deleted from this file**: `deferred-14` story
creation D1 (whose heading emptied and was removed with it), `deferred-14` review D1/D2/D4, and
`skillars-7-2` Group 2 D2. What implementation found that the audit above did not:

- **The sweeper premise held, with a corrected count.** The pre-fix reproduction ran before any code
  was written: a booking seeded in `PAYMENT_PENDING` with `updated_at` 30 days old survived
  `BookingExpiryScheduler.expireStaleRequests` untouched with no `booking_payments` row, and a
  `createBookingRequest` for the same coach and window was rejected with `booking.slotUnavailable` —
  the slot-blocking harm, observed rather than argued. Postgres rejected a second seed at that slot
  with `excl_bkg_coach_slot_overlap`, confirming the hold at DB level too. The scheduler audit found
  **29 `@Scheduled` methods across 27 files** (the audit above says "27 schedulers" — it was counting
  files); **zero read `PAYMENT_PENDING`**, as claimed.
- **`skillars-7-2` Group 2 D2's corrected premise is confirmed by execution, not just by reading.**
  With a pack seven days from expiry and sessions remaining, `notifyExpiringPacks` produced **zero**
  warning emails pre-fix (the probe asserted one and failed with "expected: 1L but was: 0L"). The
  original item's "up to 14 emails" was wrong; the audit's "zero" was right.
- **`deferred-14` review D2's fix shape was incomplete, and the story's AC4 was too.** Both describe a
  locked re-check on the three accept paths. That is necessary but not sufficient for the batch path:
  `acceptAll` catches per-booking exceptions to allow partial success, so a locked throw inside
  `acceptOneBooking` is swallowed and the suspended coach's `acceptAll` returns a silent no-op rather
  than a 403. Shipped fix carries **both** — an unlocked status check in `acceptAll` for the clean
  `booking.coachUnavailable`, and the locked per-booking check for the race. The unlocked one cannot
  be a locked one: taking the coach lock in `acceptAll`'s transaction would make every per-booking
  `REQUIRES_NEW` transaction block on a lock its own caller holds, on a different connection, until
  the 5s timeout.
- **`deferred-14` review D1 needed a refresh, not only a re-check.** `findByIdForUpdate` is JPQL and
  `acceptReschedule` already loads the reschedule row through `findById` earlier in the method, so the
  locked read returns the same managed instance with its stale in-memory status. Re-checking without
  `entityManager.refresh(..., PESSIMISTIC_WRITE)` would have read `PENDING` off the stale instance and
  never fired — the same trap `BookingService.createBookingRequest` documents for the coach row. Both
  the reschedule row and the coach row now refresh; `BookingBatchService.acceptOneBooking` deliberately
  does not, because its `REQUIRES_NEW` persistence context is fresh.
- **Testing trap worth knowing before writing the next scheduler test:** invoking a
  `@SchedulerLock`-annotated method from a test goes through the Spring proxy, so ShedLock applies —
  with `lockAtLeastFor = PT2M` (universal in this codebase) a second invocation in the same test class
  is silently **skipped**, and the assertions after it then pass or fail for reasons unrelated to the
  code under test. Found because the pre-fix probe's `expireStaleRequests()` call logged "held by
  another instance". `BasePaymentIT.releaseSchedulerLock(name)` now exists for this; use it before
  every invocation.
- **Still open and deliberately not closed:** `## Deferred from: skillars-deferred-15 story creation`
  D1 (no durable pre-capture record). It is what confines the sweeper to pack-funded bookings.

## Last audit: 2026-08-05 (skillars-deferred-16 story creation)

Written while scoping `skillars-deferred-16`. Like the `deferred-14` and `deferred-15` story-creation
audits and unlike a code-review audit, this one had no independent review layers — every claim comes
from a direct read of the named file at commit `0d06925`. Scope was **the messaging module and its
admin consumer only**; nothing outside `platform.messaging` / `platform.admin` was re-read except the
one booking-module item corrected below. Gaps stay explicit:

- **Ten items annotated `OWNED BY skillars-deferred-16`, each naming the AC that closes it** (they are
  deleted by that story once shipped, not now): `skillars-8-3` W2 (split across AC1 and AC2),
  `skillars-8-2` D1 and D2, `skillars-8-1` D1, D3, D5 and D6, `skillars-8-4` W3 and W4, and
  `skillars-10-1` D2 under the `…-admin-moderation-queue-message-content-actions (2026-06-30)` heading
  (**not** the `…-10-1 patches` heading's D2, which stays — two headings begin with `skillars-10-1` and
  each has a `D2`).
- **Three items deleted as already closed or unreachable, verified by direct read:**
  - `skillars-8-3` W5 (`content` dereferenced before its null check in `GeminiModerationService.moderate`)
    — **closed.** `:38-41` now opens the method with `if (content == null || content.isBlank())` before
    any dereference, returning a `SAFE` result.
  - `skillars-8-3` W3 (`conv.getParentId()` returned without a null guard for SUPERVISED in
    `ModerationResultApplier.resolveRecipient`) — **unreachable.** `Conversation.parentId` is
    `nullable = false` (`Conversation.java:29-30`) and `V65__messaging_module_init.sql` declares
    `parent_id BIGINT NOT NULL`, so `:105` cannot return null. The item's second clause ("same pattern in
    `MessagingService.resolveRecipient`") names a method that no longer exists — `resolveRecipient` lives
    only in `ModerationResultApplier` and `AdminMessageService` now. Same precedent as the null-content
    `10-1` item `deferred-13` deleted.
  - `skillars-7-2` Group 4 D14 (`CANCEL_PARENT` not permitted from `PAYMENT_PENDING`) — **closed, and its
    audit annotation was wrong.** The item carried `[AUDIT 2026-08-04: STILL OPEN … BookingStateMachine
    .java:30-32 maps PAYMENT_PENDING to PAYMENT_CAPTURED and PAYMENT_FAILED only]`. `BookingStateMachine
    .java:34-38` now maps `PAYMENT_PENDING → {PAYMENT_CAPTURED, PAYMENT_FAILED, CANCEL_PARENT}` with a
    comment naming `deferred-12` AC4 as the change. Out of this story's module, deleted anyway because a
    wrong `STILL OPEN` marker is worse than no marker in a file whose value is that its annotations can
    be trusted.
- **Three owned items were found to be mis-stated, and are corrected inline:**
  - `skillars-8-4` W4 says the soft-delete race "requires `@Version` on `Message`". It does not, and that
    fix would be harmful — five paths write the row (`ModerationResultApplier.applyResult:63`,
    `AdminMessageService.approveMessage:101`/`blockMessage:144`, `deferred-16`'s new sweeper, and
    `softDeleteMessage:281`), so optimistic locking would turn benign moderation-pipeline interleavings
    into `OptimisticLockingFailureException`s thrown from a request thread with SSE callbacks registered
    and no retry. `deferred-16` AC5 uses the codebase's established `findByIdForUpdate` instead.
  - `skillars-8-1` D1's stated blocker is gone. Its `[AUDIT 2026-08-04: STILL OPEN … no playerId→userId
    mapping exists anywhere in `platform.messaging`]` is literally true but out of date:
    `V84__player_self_registration.sql` (shipped outside the story flow, commit `1f24a5e`) added
    `main.player_profiles.user_id` with a `chk_pp_owner` CHECK, seeded `ROLE_PLAYER` as authority 102,
    and `PlayerProfileRepository.findByUserId`/`existsByUserId` now exist. The mapping the item asked for
    is available in `platform.security.repo`, which is why the item is now closable.
  - `skillars-8-1` D5's stated harm does not exist. It warns that "any consumer using `lastMessageAt` as a
    cursor may miss the just-sent message"; there is no such consumer. `countUnread`'s `:since` comes from
    `resolveLastReadAt` (the per-role `*LastReadAt` columns), and `lastMessageAt` is read only for the two
    conversation-list sorts (`MessagingService:117,:222`) and a relative-time label
    (`MessagingPage.vue:30`). `deferred-16` AC6b keeps the fix as plain correctness and drops the framing.
- **Scope correction found while drafting AC4, recorded because neither owned item mentions it:**
  `skillars-8-2` D1/D2 and `skillars-8-1` D1/D6 all name `MessagingService`, but
  `MessagingReportService.verifyIsParty:127-141` is an acknowledged hand-copy of
  `MessagingService.verifyIsParty` — its own comment says "Duplicates `MessagingService.verifyIsParty()` —
  injecting `MessagingService` would create a circular dep" — carrying the identical silent
  `default -> Objects.equals(conv.getPlayerId(), callerUserId)` arm, and it gates the abuse-report
  endpoints. `deferred-16` AC4 fixes both copies and keeps the duplication (the circular dependency is
  real; untangling it is a separate job).
- **Added by this audit:** one new item under `## Deferred from: skillars-deferred-16 story creation
  (2026-08-05)` — `messaging.conversations.parent_id` is `NOT NULL` while a self-registered adult
  player's profile has `parent_id IS NULL`, so conversation creation for such a player would fail at the
  DB. Found while scoping AC4; not reachable today and deliberately not fixed by `deferred-16`.
- **Examined and deliberately left alone** (recorded so the next audit does not re-litigate them):
  `skillars-8-1` D2 (N+1 in `getConversations` — real, unchanged, an MVP-volume tradeoff; belongs with the
  other N+1 items in a performance pass, and `deferred-16` AC4 edits those exact lines without turning
  into a batching rewrite); `skillars-8-3` W1 (age-policy and party checks outside a transaction — the
  `NOT_SUPPORTED` propagation is spec-designed and load-bearing for `deferred-16` AC1, and `sendMessage`
  already re-checks `BLOCKED` inside the transaction at `:159-165`); `skillars-10-1` patches D1 (null
  pivot `createdAt` — `V65` declares `created_at … NOT NULL` and the only caller reads it off a persisted
  row, so test-fixture-only; `deferred-16` AC6c edits those same two queries and deliberately does not
  fold it in); `skillars-10-1` patches D2 (context window excludes soft-deleted messages while
  `findAllForAdmin` includes them — verified still true at `MessageRepository:32-40`, but it is a product
  call about what an admin should see, not a defect); `skillars-8-4` W5 (`IS_AUTHENTICATED` on the report
  endpoints — consistent with every other endpoint in `MessagingResource`, 403 preserved at the service
  layer); `skillars-8-1` D4 (`Instant.EPOCH` sentinel undocumented — a comment already stands at
  `MessagingService:323`).
- **Not re-checked:** every item outside `platform.messaging`/`platform.admin`. No `deploy-*` section was
  read — the same gap the 2026-08-04 and the three 2026-08-05 audits flagged, now five audits running.

### Implementation outcome (2026-08-05, `skillars-deferred-16` shipped)

All ten owned items are **closed by shipped code and deleted from this file**: `skillars-8-3` W2,
`skillars-8-2` D1/D2 (heading emptied and removed with them), `skillars-8-1` D1/D3/D5/D6 (D2 and D4
left in place), `skillars-8-4` W3/W4 (W5 left in place), and `skillars-10-1` D2 under the
`…-admin-moderation-queue-message-content-actions` heading (heading emptied and removed; the separate
`…-10-1 patches` heading's own D2 is untouched). What implementation found that the story-creation
audit above did not:

- **Task 1's `PENDING`-reader grep audit confirmed the story-creation claim exactly**, re-run
  independently before any code changed: three `PENDING` references in `src/main`
  (`ModerationResultApplier:40` fallback, `MessagingService:171` write, `Message.java:39` column
  default), all writes/defaults, and `MessageRetentionScheduler` remains the module's only
  `@Scheduled` method (age-based deletion, not status-based). No re-scope of AC2 was needed.
- **AC1 mutation-verified.** With the `PENDING`/`deletedAt` guard in `ModerationResultApplier
  .applyResult` temporarily removed, `ModerationResultApplierTest`'s two guard cases fail exactly as
  the Task 1 probe predicted: an admin `BLOCKED` message reverts to `APPROVED` (`expected: BLOCKED but
  was: APPROVED`), and a soft-deleted `PENDING` message accepts the verdict anyway. Both pass again
  with the guard restored.
- **AC2 mutation-verified.** With `MessageModerationSweeper.sweepOne`'s re-read guard removed, the
  sweeper unconditionally writes `UNDER_REVIEW` onto a message that had already resolved to
  `APPROVED` or been soft-deleted between the select and the sweep transaction opening —
  `MessageModerationSweeperTest`'s `sweep_messageAlreadyResolvedBetweenSelectAndSweep...` and
  `sweep_messageSoftDeletedBetweenSelectAndSweep...` both fail on an unwanted `save()` invocation.
  Restored, both pass.
- **AC5 mutation-verified.** Reverting `softDeleteMessage`'s `findByIdForUpdate` to `findById` makes
  `SoftDeleteIT`'s `concurrentDoubleSoftDelete_exactlyOneSucceeds_oneConflicts` fail with 2 successful
  204s instead of 1 success + 1 `409 messaging.alreadyDeleted` (`expected: 1 but was: 2`) — the lock,
  not the guard ordering, is what makes the losing caller observe the conflict. Restored, passes.
- **Correction: AC6d's premise was already false at story-creation time, found before writing `V91`.**
  The story's AC6d and Task 8(d) describe `idx_message_reports_message_id` and
  `idx_conversation_reports_conversation_id` as still present and needing a `DROP INDEX IF EXISTS` in
  the new migration. Both were **already dropped** by `V81__drop_redundant_report_indexes.sql`, which
  pre-dates this story and cites the identical justification (redundant against the leading column of
  the two `uq_*` unique constraints). `V91__messaging_moderation_recovery.sql` carries no DDL for
  AC6d — re-adding an already-applied `DROP INDEX IF EXISTS` would have been harmless but misleading
  to the next reader auditing what `V91` actually changed.
- **A pre-existing unit test, `AgeTierTransitionTest`, encoded the exact PLAYER-identity bug AC4
  fixes** — found only because `mvn -o verify` failed on it after the identity change, not by design.
  Its `sendMessage_playerRole_*` cases passed a caller id that the test treated as interchangeable
  with the player-profile id (the pre-fix bug's premise, verbatim), and its `getConversations_*`
  cases stubbed the now-dead `getMessagingPolicy` call on the read paths AC4 switched to
  `findMessagingPolicy`. Fixed alongside the identity change: 4 existing cases updated to resolve the
  caller's profile via `findByUserId` as production code now does, plus 2 new cases
  (`getConversations_playerRole_noPlayerProfile_returnsEmptyListNot404`,
  `getConversations_parentRole_orphanedPlayerProfile_excludedFromList_notThrown`) pinning the AC4
  behaviour directly. An independent confirmation, from an unrelated pre-existing test breaking, that
  the read paths previously assumed exactly the identity shape AC4 replaces.
- Full `mvn -o verify` green after every mutation guard above was restored: 0 failures, 0 errors.
  **The counts originally recorded here — "840 unit + 883 IT" — were WRONG, and wrong in exactly the
  way `skillars-deferred-15`'s correction warned about. They summed the report DIRECTORY, not the
  classes surefire ran.** `target/surefire-reports/` held 98 `.txt` files against 95 from the final
  run; all three stale ones are ITs, and `MessagingIdentityIT` (5 tests) was double-counted against
  failsafe. `target/failsafe-reports/` held 140, one being `StrandedPaymentPendingProbeIT` for a
  class that no longer exists in `src/test`. True totals were **≈816 unit + ≈881 IT**. Caught by the
  2026-08-05 code review, which re-derived them from file timestamps. The lesson `deferred-15`
  recorded was quoted in this very bullet and still not applied: filter the report files by the
  final run's timestamp, do not trust the directory listing.

## Last audit: 2026-08-06 (skillars-deferred-17 story creation)

Written while scoping `skillars-deferred-17`. Story creation set out only to verify one item —
`skillars-3-3` Group D's `canonicalTimezone` bug — before grooming a story around it. Tracing that
item's data flow through `BookingRequestPage.vue` and `booking.store.js` surfaced two live defects in
the same file that were **never tracked in this ledger at all**. The user was informed mid-investigation
(parent-facing booking-request submission, single and batch both, is currently non-functional in
production) and chose to make the newly-found breakage the anchor of `skillars-deferred-17`, with the
ledger items folded in alongside it. Every claim below comes from a direct read of the tree at commit
`8bb6c8a`, confirmed by manual data-flow tracing rather than by running the app (no reproduction harness
exists for this — see the story's Task 1).

- **New, not previously tracked here — now owned by `skillars-deferred-17` AC1.** `AvailableSlotResponse`
  (`AvailableSlotResponse.java:6-7`) serializes `startDatetime`/`endDatetime`; `BookingRequestPage.vue` and
  `booking.store.js` read `.startTime`/`.endTime` throughout (18 occurrences in the page, 4 more in the
  store) everywhere a slot object is touched. Confirmed
  by cross-reference: `WeeklyCalendar.vue:118-120,167-168` reads the sibling availability-block endpoint's
  `startDatetime`/`endDatetime` correctly, and no camelCase-transforming interceptor exists anywhere in
  `src/frontend` (grepped for `camelCase`/`transformResponse` — no hits). Effect: every rendered slot shows
  `"Invalid Date"`, and submitting sends `requestedStartTime: undefined`, which `CreateBookingRequest`'s
  `@NotNull @Future` rejects with 400. The single-booking flow cannot complete a booking today.
- **New, not previously tracked here — now owned by `skillars-deferred-17` AC2.** `git log -p` on
  `BookingRequestPage.vue` shows `submitBatchRequest()`'s call `bookingStore.submitBatch(coachId,
  playerId.value, 0)` was changed to pass `Intl.DateTimeFormat().resolvedOptions().timeZone` as the third
  argument — almost certainly an attempted fix for the same class of timezone bug `skillars-3-3` Group D
  reports, aimed at the wrong parameter. `submitBatch`'s third argument (`booking.store.js:509`) binds
  directly into `CreateBatchRequest.totalAmount` (`CreateBatchRequest.java:17`, `@NotNull BigDecimal`);
  `CreateBatchRequest`/`BatchSlot` have no `canonicalTimezone` field to receive a timezone at all, because
  `BookingBatchService.createBatch:131` already derives it server-side from `coach.getCanonicalTimezone()`
  and never needed the client to send one. Sending a zone string where Jackson expects a `BigDecimal` fails
  deserialization before validation runs. The batch-booking flow cannot complete a submission today.
- **`skillars-3-3` Group D, both bullets — owned by `skillars-deferred-17` AC3 (canonicalTimezone
  bullet) and AC4 (formatSlot bullet).** Re-verified the 2026-08-04 `STILL OPEN` annotation is still
  accurate, and additionally confirmed *why* the single-booking path is wrong while the batch path (found
  while investigating AC2 above) is already right: `BookingService.createBookingRequest` trusts
  `req.canonicalTimezone()` verbatim after only syntactic `ZoneId.of()` validation
  (`BookingService.java:181-186,253`), where `BookingBatchService.createBatch` ignores client input and
  uses `coach.getCanonicalTimezone()` (`:131`). The fix is to make the single-booking path consistent with
  the batch path that already ships correctly, not to invent new behavior.
- **`skillars-4-1` D5 — owned by `skillars-deferred-17` AC5.** Re-verified the 2026-08-04 `STILL OPEN`
  annotation is still accurate: `DrillLibraryPage.vue:288-290`'s `onMounted` still has no error branch
  around `sessionStore.fetchDrills('PLATFORM')`.
- **Examined and deliberately NOT folded in.** Whether `CoachProfile.canonicalTimezone` should itself be
  validated as a real IANA zone at profile-creation time (`ProfileBuilderStep1Request`/
  `ProfileBuilderStep4Request` are both merely `@NotBlank`) is a separate, pre-existing gap shared by both
  booking-creation paths — `skillars-deferred-17` makes the two paths *consistent*, it does not add new
  validation neither currently has. Also examined: computing a real `totalAmount` for batch submissions
  client-side, rejected as a feature (this page loads no pricing data today) rather than a bug fix — see
  the story's "Items examined and deliberately NOT folded in" for detail.
- **Not re-checked:** every other item in this file. No `deploy-*` section was read — the same gap every
  prior audit in this file has flagged, now six audits running.

**IMPLEMENTATION OUTCOME (2026-08-06, `skillars-deferred-17` dev):** All 6 ACs closed, backend `mvn -o
verify` and frontend `eslint` both green. AC1/AC2 confirmed fixed by static re-inspection (grep for
`.startTime`/`.endTime` on slot objects returns zero hits post-fix; no test framework exists for these
two — see the story's Dev Notes on why manual dev-server exercise, not an automated test, is this
project's established verification path for frontend-only fixes). AC3 was pinned with a real backend
probe (`BookingRequestResourceIT.createBookingRequest_ignoresClientTimezone_usesCoachProfileTimezone`,
sends a client `canonicalTimezone` deliberately different from the coach fixture's, asserts on the
response body value) that failed pre-fix (client's zone won) and passes post-fix (coach's zone wins) —
mutation-verified in both directions by construction, not by inspection. AC4 uncovered one story-text
inaccuracy worth recording for the next reader: `AvailabilityService.getAvailabilityCalendar` does
**not** already load a `CoachProfile` — its zone fallback (`:52`) reads only `windows.get(0)`, there is
no existing `coachProfileRepository` call in that method to reuse. Fixed by adding one new read-only
`coachProfileRepository.findById(coachId)` lookup (mirrors the endpoint's existing tolerance for an
unknown `coachId` — falls back to `"UTC"` rather than throwing, since `AvailabilityResource.getAvailability`
never validates the coach exists before calling this method). Also added
`AvailabilityResourceIT.getAvailability_windowTimezoneDivergesFromProfile_responseUsesProfileTimezone`,
seeding `coach_availability_windows.canonical_timezone` to a different zone than
`coach_profiles.canonical_timezone` and asserting the response's top-level `canonicalTimezone` follows
the profile column — the exact divergence this AC exists to prevent. Post-fix line numbers in
`BookingRequestPage.vue` drifted as expected from AC1's edits: `formatSlot()` is now at `:277`, `submit()`
at `:298`, `submitBatchRequest()` at `:318` — no `canonicalTimezone` references remain in the file
(`grep -c` confirms zero).

## Last audit: 2026-08-06 (skillars-deferred-18 story creation)

Written while scoping `skillars-deferred-18`. Like the prior story-creation audits, this one had no
independent review layers — every claim comes from a direct read of the tree at commit `f2de881`.
Scope: the four items the `skillars-deferred-17` code review deferred from `AvailabilityService` and
the coach-profile-builder write path (D2, D5, D9, D10 below). No other item in this file was re-checked.

- **Four items annotated `OWNED BY skillars-deferred-18`, each naming the AC that closes it** (they are
  deleted by that story once shipped, not now): D2 (already-booked slots visible to other
  parents/players, closed by AC1), D9 (per-window timezone using `windows.get(0)` instead of the
  window's own zone, closed by AC2), D5 (200+"UTC" for a nonexistent `coachId`, closed by AC3), and D10
  (no IANA validation on the profile-builder write path, closed by AC4).
- **All four re-verified still open and reproducible exactly as originally described** — no drift found
  between the 2026-08-06 review that raised them and this same-day story creation. Full detail of each
  re-verification is in the story file itself rather than duplicated here.
- **Deliberately not folded in:** D1 (no session-duration cap — a product decision, not a bug fix) and
  D3 (`formatSlot` hardcodes `'en'` — a systemic 4+-page i18n sweep, not an `AvailabilityService` bug),
  D4 (`AvailabilityManagerPage.vue` still reads `windows[0]`'s zone — a different bug in a different
  file, about which page reads which field, not about `AvailabilityService`'s own computation), D6
  (no frontend test coverage — blocked on the standing no-test-runner gap), D7 (`docker compose build`
  no-op — deployment/tooling, lowest priority per project convention), and D8 (reconciling the two
  `canonical_timezone` columns — needs a migration, a backfill rule, and a product decision on
  per-window zones; explicitly not a prerequisite for D9, which this story does close).
- **Not re-checked:** every other item in this file. No `deploy-*` section was read — the same gap every
  prior audit in this file has flagged, now seven audits running.

### Implementation outcome (2026-08-07, `skillars-deferred-18` shipped)

All four owned items are **closed by shipped code and deleted from this file**: D2 (AC1), D9 (AC2), D5
(AC3), D10 (AC4). A senior-dev review of the drafted story (2026-08-07) found three of the four
prescribed fixes were themselves defective before any code was written — all three were corrected in
the story text first, then implemented as corrected:

- **AC1 reuses `BookingRepository.findOverlappingBookings` instead of adding a second query.** The
  story as drafted asked for a new repository method duplicating overlap semantics that already
  existed and were already used by `BookingService.createBookingRequest` with a `null`
  `excludeBookingId` — the exact call shape this AC needed. Shipped: one call per
  `getAvailabilityCalendar` invocation, `BookingService.ACTIVE_SLOT_STATUSES` relaxed to
  package-private (same rationale as its neighbor `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED`), zero
  new queries.
- **AC2's fetch window is now padded by TWO days on each side, not left untouched.** The story as
  drafted forbade touching the outer `zoneId`'s fetch-window role; the review found that once each
  window computes its own instants in its own zone (this AC's whole point), a window far enough from
  `windows.get(0)`'s zone could have its instants fall entirely outside an unpadded fetch range —
  silently dropping that window's blocks and bookings from the fetch, reproducing AC1's exact failure
  mode through AC2. Shipped with the pad, plus a response-mapping-time filter back to the unpadded
  week so `blockResponses` (the API's `blocks` field) stays exactly week-scoped.

  **Corrected 2026-08-07 by the code review of this story, on two counts.** (1) AC2 prescribed a
  ONE-day pad, which is arithmetically too small for the divergence it exists to cover: region zones
  alone span 25h (`Pacific/Niue` at UTC-11 to `Pacific/Kiritimati` at UTC+14) and `ZoneId.of` also
  accepts fixed offsets to ±18:00 (36h), so at 24h the gap AC2 identified stayed open for the widest
  zone pairs. Widened to `minusDays(2)`/`plusDays(9)`. (2) The claim previously recorded here — that
  `AvailabilityServiceTest` gained "a regression test that fails without the pad" — was **false**.
  That test stubbed the block fetch with `any(), any()` matchers, so the mock returned the block
  whatever bounds were passed and reverting the pad changed nothing it observed; the only test that
  failed under the dev's mutation check was a separate argument-captor test that re-asserts the
  formula rather than any behaviour. Both tests were rewritten: the fetch stubs now apply the real
  queries' half-open overlap predicates, and the scenario uses a Niue/Kiritimati pair with a block
  and a booking placed beyond a one-day pad, so reverting either the pad or the per-window zone now
  genuinely fails.
- **AC4's `ProfileBuilderStep4Request.windows` gained `@Valid`, without which nothing shipped would
  have run.** The story as drafted added `@IanaTimezone` to the nested `AvailabilityWindowRequest`
  record without a `@Valid` cascade on the containing `List` — Bean Validation does not descend into
  container elements without it, so the new constraint (and the pre-existing `@NotBlank`,
  `@Min`/`@Max` on `dayOfWeek`, `@NotNull` on `startTime`/`endTime`) would all have stayed dead code
  for Step 4 payloads. Fixed to `List<@Valid AvailabilityWindowRequest>`, matching this codebase's own
  convention elsewhere. Also mandated (not merely suggested) the `CamPhoneValidator` pipe-template
  message pattern over `LangIso2`'s bare `{...}` template — `LangIso2`'s shape resolves to nothing
  without a `ValidationMessages.properties` entry this codebase has never had, which would have
  returned the literal, unresolved `{validation.timezone.invalid}` string to the client: the exact
  defect this story exists to fix, reproduced one validator later. `CoachProfileBuilderIT` proves both
  the message resolves and the three newly-activated nested constraints now return 400.
- **AC3 shipped as drafted.** `getAvailabilityCalendar` now loads `CoachProfile` once via `findById`,
  `orElseThrow`s `ResourceNotFoundException` for a nonexistent `coachId`, and reuses that same lookup
  for `coachTimezone` — no second query. A real coach with a blank/missing `canonicalTimezone` keeps
  its `"UTC"` fallback.
- **Also fixed, found during story drafting rather than during implementation:** `BookingRequestPage.vue`
  still called `bookingStore.loadParentBookings()` in `onMounted` after `bookedStartTimes` (its only
  consumer) was deleted — an orphaned network call fetching data nothing on the page used. Removed
  alongside `bookedStartTimes`.
- **Unflagged-but-intended UX change worth recording:** `BookingService.ACTIVE_SLOT_STATUSES` includes
  `REQUESTED`, so a parent's own pending request now makes a slot disappear from the list entirely
  rather than render disabled (the old `bookedStartTimes` behavior). Correct and consistent with the
  accept-path check using the same status set.
- **Residual, deliberately not closed by this story:** the availability list `BookingRequestPage.vue`
  books from is fetched once in `onMounted` and never refreshed — a booking made by another parent
  between page load and submit can still surface a `SLOT_UNAVAILABLE` error, narrower than before AC1
  but not eliminated. No new item opened for this; it is the same class as D1 above (a product-shaped
  gap, not a bug this story's scope covers).
- **No data audit of existing `canonical_timezone` rows was run**, per D10's own suggestion. AC4 guards
  new writes only. Decision recorded in the story's Dev Notes: no evidence today that a bad value is
  already stored, and the existing read-side `"UTC"` fallback/WARN already handles it if one is found
  later. Not reopened as a new item — this was D10's own suggestion, not a newly discovered gap.

## Last audit: 2026-08-24 (skillars-deferred-60 story creation)

Written while scoping `skillars-deferred-60`, at commit `a995f5d` (the tip of `master` immediately after
`skillars-deferred-59` merged). A full re-mine of the entire 1836-line file (not just the recent tail),
continuing `skillars-deferred-59`'s own full-file discipline. Every `[PICKED UP by skillars-deferred-NN
...]` tag naming a story that has since shipped was re-checked live against the current source rather
than trusted — fourteen turned out to be **already fixed, ledger not updated** (the same unannotated-fix
pattern this file's audit history has flagged repeatedly: `skillars-deferred-16`, `-34`, `-40`, `-41`,
`-43`, `-44`, `-45`, `-52`, `-56`), and are each annotated `STALE` in place below rather than duplicated
here. One item — the newest section in the file, from `skillars-deferred-59`'s own code review — was
re-verified genuinely still open and became `skillars-deferred-60`'s one Acceptance Criterion, tagged
`[PICKED UP by skillars-deferred-60 AC1]` in place.

- **Not re-checked beyond the fourteen `PICKED UP` tags and the file's newest section:** every other item
  in this file, including all older sections already flagged thin by `skillars-deferred-58`/`-59`'s own
  audits. The `deploy-*` sections were again not read — the same gap every prior full-file audit in this
  file has flagged.

---

## Deferred from: code review of skillars-deferred-80-availability-block-lock-parity-video-cascade-self-invocation-and-branding-tier-gate (2026-08-28)

- VideoDeletionService self-injection failure mode: If Spring `@Autowired @Lazy` initialization fails during bean creation, the self-reference becomes null and line 170's `self.deleteVideo()` call would NPE. However, if autowiring fails, the application won't start; this is a Spring-level concern with precedent in `RadarCompositeCalculationService`. `[DISMISSED 2026-08-29 (skillars-deferred-83 story creation): Spring-level concern, unreachable post-startup — a broken @Lazy @Autowired wiring fails the whole application at boot, not silently at runtime. Matches this exact codebase's own precedent reasoning for RadarCompositeCalculationService's identical self field.]`
- VideoDeletionServiceTest lazy-proxy validation: Unit test uses `ReflectionTestUtils.setField()` to manually wire the self field, bypassing Spring's real `@Lazy @Autowired` mechanism. Integration tests would catch Spring initialization failures if they occurred. `[PICKED UP by skillars-deferred-83 AC1: adds a new AccountDeletionCascadeIT case that runs through the real Spring-wired self proxy and proves per-video transaction isolation under a mid-cascade failure — the actual regression risk this bullet's own precedent (skillars-deferred-80 AC2's self-invocation fix) needs proof for, not just proof that the proxy resolves without NPE.]`
- AvailabilityService deleteBlock test coverage: New `deleteBlock_ownedByCallingCoach_succeeds` test covers only the happy path. Spec does not require error-case coverage (block not found, deletion failure); the new test addresses the pre-existing gap that deleteBlock had zero tests before this story. `[CLOSED by skillars-deferred-82 AC1: deleteBlock_notFoundOrNotOwned_throwsOperationNotAllowed added directly beneath the happy-path test in AvailabilityServiceTest.java — 33/33 green.]`
- VideoDeletionService lazy-autowired initialization order: No explicit guard on self-field initialization timing. Pattern precedent exists in `RadarCompositeCalculationService.java:50-52`, confirming this pattern is established in the codebase. `[DISMISSED 2026-08-29 (skillars-deferred-83 story creation): same Spring-level reasoning as the self-injection-failure bullet above — no explicit guard is needed for a failure mode that prevents application startup entirely.]`
- AvailabilityService SELECT FOR UPDATE deadlock risk: Profile lock uses pessimistic `LOCK_MODE.PESSIMISTIC_WRITE` without visible lock-ordering enforcement. Potential deadlock if other transactions lock profiles in different order. However, this is a pre-existing pattern used by `addWindow()`/`updateWindow()`/`deleteWindow()` — not introduced by this story. `[CLOSED by skillars-deferred-83 story creation (false premise): every lock-taking method in AvailabilityService.java (addWindow:255, updateWindow:269, deleteWindow:285, addBlock:310, deleteBlock:338) calls lockProfile(profile.getId()) exactly once per transaction, locking only the calling coach's own single profile row. No method in this service, or in BookingBatchService (which locks exactly one coach per batch), ever holds two different coach-profile locks in one transaction — there is no circular-wait shape possible, only ordinary single-row contention.]`
- VideoDeletionService self-field null check: Missing defensive null check before `self.deleteVideo()` dereference. Spring-level concern: if autowiring fails, application won't start; defensive null-check would mask configuration failure with a misleading video-deletion error. `[DISMISSED 2026-08-29 (skillars-deferred-83 story creation): same Spring-level reasoning as the self-injection-failure bullet above — a defensive null-check would mask, not fix, a startup-time configuration failure.]`

## Deferred from: skillars-deferred-15 story creation (2026-08-05)
- D1: ~~**No durable pre-capture record for a Stripe charge, so a stranded credit-funded booking cannot be reconciled — by a sweeper or by anything else.**~~ **CLOSED 2026-08-11 by `skillars-uat-3` AC1 + AC3 + AC5.** All three pieces this item names shipped together and were each confirmed present before closing: `BookingPaymentPersistenceService.reserveCapture` writes a `CAPTURE_PENDING` row in its own `REQUIRES_NEW` transaction before both Stripe call sites (V94 widens `chk_bp_status`); the four `existsById` idempotency guards became the status-aware `isSettled`/`hasReservation` helpers — `PaymentLifecycleService:199` was a **live break**, its `!existsById` condition becoming permanently false once reserved rows exist, which would have stranded whole batches (mutation-verified: the batch decline test fails with `expected "DECLINED" but was "PAYMENT_PENDING"`); and `PaymentPendingSweeper`'s precondition widened from pack-funded to "no payment row". Original text follows.
  ORIGINAL: **No durable pre-capture record for a Stripe charge.** On both settlement paths the capture is an irreversible external side effect that happens *before* the DB writes recording it, and those writes join a transaction that can still fail: single bookings do `paymentGateway.chargeAndCapture(...)` (`PaymentLifecycleService:102`) then `persistPaymentSuccess` (`:114`), and batches do `chargeAndCaptureForBatch` for the whole batch upfront (`:190-191`) then a per-booking `confirmCreditBatchPayment` loop (`:209-233`). A JVM death or transaction failure in between leaves **money captured at Stripe, the booking in `PAYMENT_PENDING`, and no `payment.booking_payments` row** — for a batch, potentially several such siblings while earlier ones committed normally.

  Verified 2026-08-05 that **no durable signal exists to detect this after the fact**: `StripePaymentGateway` is not `@Transactional` and its only durable write is `stripeCustomer.setLastPaymentIntentId(...)` (`:67-70`), which joins the caller's transaction (so it rolls back in precisely the window that matters) and is keyed per parent and overwritten on every charge, so it cannot identify a booking or batch; `PaymentGateway` exposes no capture-lookup method; and `StripeWebhookService.handleEventAtomically` (`:71-80`) handles only `account.updated` and the `customer.subscription.*`/invoice families — there is **no `payment_intent.*` or `charge.*` handler**, so `payment.stripe_webhook_events` holds no capture record either. Recovery today is a manual Stripe-dashboard reconciliation with no trigger telling anyone to perform it.

  **A credit-ledger check is not a workaround — this was tried in the draft and is wrong.** `CreditWalletService.writeLedgerEntry` (`:36-37`) is plain `@Transactional` (REQUIRED), so the batch-level `BOOKING_DEDUCTION` at `:182-185` joins the listener's transaction and commits *after* the per-booking `REQUIRES_NEW` confirms — the opposite of the assumed order. In the crash window it would roll back too, and the only case where it is durable is one where the payment rows exist anyway.

  Fix is a pre-capture record: write the `booking_payments` row (or an intent-outbox row) in its own `REQUIRES_NEW` transaction **before** the Stripe call and update it to `CAPTURED` after, so "no row" provably means "never reached Stripe". Two things must land with it or it makes matters worse: `bookingPaymentRepository.existsById` is the **idempotency guard** at four call sites (`PaymentLifecycleService:62,145,199,210`), so a leftover pre-capture row from a crashed attempt would make a duplicate delivery skip settlement entirely — those checks must become status-aware; and the sweeper's precondition can then be widened from "pack-funded" to "no payment row", which is the point of doing it. Related to `skillars-10-2` D1 (both are the absence of settlement retry/reconciliation) but distinct: that one is the refund path with no money at risk of being *kept*, this one is the capture path with money already taken. **Do not fold into `skillars-deferred-15`** — it modifies the live charging path rather than adding a reader beside it. [`PaymentLifecycleService.java:92-117,164-233`, `StripePaymentGateway.java:35-82`, `BookingPaymentPersistenceService.java:37-66`]

## Deferred from: code review of skillars-deferred-14-moderation-listener-batch-overlap-integrity (2026-08-05)
- D3: **`ReviewModerationService`'s new `PENDING`-only guard (AC1) cannot distinguish a stale in-flight Gemini verdict for a superseded edit from a fresh one.** `ReviewSubmissionService.updateReview` resets a `PENDING`-or-`APPROVED` review back to `PENDING` and publishes a new `ReviewSubmittedEvent` on every edit. If a review is edited again while the *previous* edit's Gemini call is still in flight, two `ReviewSubmittedEvent` deliveries race against the same `PENDING` status with no version/timestamp to tell them apart — whichever Gemini call resolves first wins and writes its verdict (possibly evaluating stale, already-overwritten content) against the shared `PENDING` guard; the second, fresher delivery then finds a non-`PENDING` status and discards itself as "already resolved" even though the real resolution reflects stale content. AC1's guard is correctly scoped to the admin-vs-listener race it targets (three writers enumerated in the code comment: admin decisions, the flag-threshold auto-hold, and duplicate delivery of the *same* event) but does not cover a *second, different* event racing the first. **[AUDIT 2026-08-05: the scenario as written is NOT reachable today — corrected.** The finding's stated window is "editing a review again within the multi-second Gemini latency of the prior edit". `ReviewSubmissionService.updateReview:82-86` rejects any edit whose `lastModifiedAt` falls within the last 365 days, `submitReview` and `updateReview` both stamp `lastModifiedAt` at write time, and the moderation listener deliberately does not touch it (AC1). So a second `ReviewSubmittedEvent` for the same review cannot be published until 365 days after the first — by which point the first Gemini call has long returned. Two deliveries cannot be in flight concurrently. Keep the item: the **design** limitation is real and would become reachable the moment the 365-day edit rule is relaxed or an admin/GDPR path republishes the event, and the guard carries no version or nonce to survive that. Do not treat it as a live defect.**] A full fix needs a version/timestamp carried on `ReviewSubmittedEvent` and checked against the row under the same lock. [`src/main/java/com/softropic/skillars/platform/reviews/service/ReviewModerationService.java:93-124`, `ReviewSubmissionService.java:82-86`]

## Deferred from: code review of skillars-deferred-11-stripe-card-collection (2026-08-04)
- `PaymentMethodCard.vue`'s `stripeUnavailable` state has no retry affordance short of a full page reload — AC2 only requires the unavailable message + disabled submit, which is satisfied; a retry action would be a UX enhancement beyond spec scope. [`PaymentMethodCard.vue:86-106`]
- No frontend tests (Vitest/Vue Test Utils) were added for `PaymentMethodCard.vue` or the new `payment.store.js` actions (`fetchStripeConfig`, `fetchSavedPaymentMethod`) — real coverage gap on a component with non-trivial lifecycle logic. [`PaymentMethodCard.vue`, `payment.store.js`]

## Deferred from: code review of skillars-deferred-9-frontend-ux-polish (2026-07-02)
- D1: AC7 `portal` sub-block left untranslated in `de/index.js` — pre-existing (already English in both `en` and `de` before this story); explicitly out of this story's scope per the Dev Agent Record. [de/index.js]

## Deferred from: code review of skillars-deferred-1-config-securityutil-hardening (2026-07-01)
- D1: Test coverage is thin relative to the diff's size — no unit test covers `NeglectedSkillDetectionService.isInValidRange()`'s boundary values (0, 1, and just inside/outside), and none of the 15 `Resource` classes refactored to use `SecurityUtil.requireCurrentUserId()` have a test asserting the actual 401 response when it throws; only one E2E smoke test (`ConfigGuardIT`) was added for a ~1080-line diff. [various]
- D2: `ConfigGuardIT.java` mutates the shared `main.platform_config` row directly (`setUp`/`tearDown`) and depends on `@AfterEach` running to restore the original value and invalidate the `ConfigService` cache — real but low-probability test-isolation hazard if the test run is interrupted before teardown. [ConfigGuardIT.java]

## Deferred from: code review of skillars-10-2-coach-enforcement-strike-management (2026-06-30)
- D1: `AFTER_COMMIT` listener failure silently drops refunds — `CancellationRefundService.onBookingCancelledByAdmin()` follows the same `REQUIRES_NEW` pattern as all other listeners; if the refund transaction fails after the suspension commits, the booking stays CANCELLED with no refund issued and no retry. Pre-existing pattern shared by `onBookingCancelledByCoach`, `onCoachNoShow`, etc. Address in a platform-wide payment resilience pass. [CancellationRefundService.java:onBookingCancelledByAdmin]

## Deferred from: code review of skillars-10-1 patches (2026-06-30)
- D1: `findBeforePivot`/`findAfterPivot` return empty context when pivot message `createdAt` is null at JPA layer — DB NOT NULL prevents this in production; only affects test fixtures that construct Message in-memory without setCreatedAt(). [MessageRepository.java:33-37]
- D2: Context window (`findBeforePivot`/`findAfterPivot`) excludes soft-deleted messages while `findAllForAdmin` includes them — chronological gap in admin message detail view with no indication; intentional spec asymmetry between views; UX concern for service layer mapping. [MessageRepository.java:33-40]

## Deferred from: code review of skillars-8-4 (2026-06-27)
- W5: `@PreAuthorize(IS_AUTHENTICATED)` on report endpoints instead of party-check annotation — consistent with module pattern; 403 preserved at service layer. Architectural note for future hardening. [`MessagingResource.java:140,151,168`]

## Deferred from: code review of skillars-8-3 (2026-06-26)
- W1: TOCTOU — age policy + party checks run without a transaction before committed message save; spec-designed (NOT_SUPPORTED), window is narrow [`MessagingService.java:129-145`]

## Deferred from: adversarial code review of skillars-7-2 Group 2 Service Layer (2026-06-24)
- D1: Non-atomic idempotency check in `onBookingAccepted` (`existsById` bare SELECT outside TX) — root cause addressed by P3 (TX boundary restructure); revisit if duplicate event replay observed in production [`PaymentLifecycleService.java:52-55`]
- D3: `createTier` TOCTOU under concurrent coach requests — two active tiers briefly possible; DB UNIQUE partial index `idx_spt_one_active_per_coach` enforces constraint at commit, causing one to fail with constraint violation; low probability in production [`SessionPackPaymentService.java:createTier`]

## Deferred from: adversarial code review of skillars-7-2 Group 1 DB+Entities (2026-06-24)
- D1: `parent_credit_balance` VIEW returns 0 rows (not a zero-balance row) for parents with no ledger history — safe via JPQL path; latent trap for native SQL consumers [`V62__session_payment_credit_wallet.sql`]
- D3: `SessionPackPurchase.expiresAt` mutable with no `updatable=false` — service-layer enforced via `extendPack()` business rules; open setter is a footgun [`SessionPackPurchase.java`]
- D5: `stripe_customers.last_payment_intent_id` not in AC 1 spec schema — intentional addition to support cash-out refund flow (Group 2 Decision D1 resolution); AC 1 should be updated to document this column [`V62__session_payment_credit_wallet.sql`, `StripeCustomer.java`]

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
- D1: `IllegalArgumentException` if `row[0]` (coach_id from native query) is not a valid UUID string — unlikely under DB schema constraints but unguarded [`SluContributionService.java`] `[CLOSED by skillars-deferred-77 AC1]`
- D2: No exception wrapping around `coachProfileService.getDisplayNamesByIds` — propagates as 500 on failure; consistent with general project error-handling convention [`SluContributionService.java`] `[CLOSED by skillars-deferred-77 AC1]`
- D3: Double iteration of `rows` list in `getCoachContributions` — could use SQL window function for in-DB percentage calculation; optimisation, not a correctness issue [`SluContributionService.java`] `[AUDIT 2026-08-27: still two passes by design — buffering all rows first is required to compute per-skill totals before per-row percentages; skillars-deferred-77 AC1 consolidated the UUID conversion into one pass but kept the two aggregation passes]`
- D4: `PerformanceReportsPanel` wrapped in extra `q-card` in parent portal may produce a double-card visual artifact — requires runtime visual verification [`ParentDevelopmentPortalPage.vue`]

## Deferred from: code review of skillars-5-5-pdf-performance-report-unified-player-timeline — Round 3 (2026-06-19)
- R3-D1: Orphaned `player_timeline_events` PERFORMANCE_REPORT row when outer `generateReport` transaction rolls back after `writeTimelineEvent` REQUIRES_NEW commit — timeline shows an event with a dead `referenceId`; accepted MVP trade-off per spec dev notes [`ReportGenerationService.java:144-153`] `[CLOSED by skillars-deferred-77 AC2 — timelineEventListener.writeTimelineEvent now runs only in the async onReportGenerated handler, after the S3 upload has already succeeded and the outer generateReport transaction has already committed, so there is no longer an outer transaction left to roll back]`
- R3-D2: `findLastSessionDate` queries `MAX(calculated_at)` over all `player_skill_stats` rows without filtering for session-only writes — if `RadarAssessmentService` writes SLU rows to `player_skill_stats` (to be confirmed), radar assessments could reset the coach timeline-access expiry window, contradicting the design comment in `TimelineQueryService` [`SluRepository.java:43-48`] `[CLOSED by skillars-deferred-77 AC6 — confirmed RadarAssessmentService writes to development.radar_assessment_entries, a disjoint table; only SluCalculationService.onBookingCompleted writes player_skill_stats, so no filtering is needed. Comment added at the query site.]`
- R3-D3: Stale branding logo key persists after coach tier downgrade from ACADEMY — re-upgrade reuses the prior logo key without re-validation; graceful try/catch fallback in `buildPdf` prevents crash [`ReportGenerationService.java:278`] `[CLOSED by skillars-deferred-77 AC3 — buildPdf only ever receives branding when tier==ACADEMY at its one call site, so the downgrade-then-reupgrade-reuses-stale-key scenario this item describes cannot occur through buildPdf. The real stale-tier gap is in getBranding() (no tier check at all), a different method — filed as a new item below.]`

## Deferred from: code review of skillars-5-5-pdf-performance-report-unified-player-timeline (2026-06-19)
- D1: `slu_value` and `calculated_at` column names in `SluRepository` native queries not explicitly verified against the actual migration file — runtime `BadSqlGrammarException` risk; confirm column names from V-series migration before deploying [`SluRepository.java:36,42`] `[CLOSED by skillars-deferred-77 AC5 — verified against V46__development_module_init.sql: development.player_skill_stats.slu_value/calculated_at both confirmed correct (existing native queries already used the right schema/column names). Added a DevelopmentConfig.validateSluRepositorySchema() @PostConstruct check that runs the same SELECT at startup so any future drift fails loud (AppSetupException) instead of surfacing as a runtime BadSqlGrammarException on first read.]`
- D2: S3 I/O (Academy logo download + PDF upload to S3) executes inside `@Transactional generateReport` — blocking calls hold DB connection for the duration; may exhaust connection pool under concurrent load [`ReportGenerationService.java:87-142`] `[CLOSED by skillars-deferred-77 AC2 — S3 upload extracted to an async @TransactionalEventListener(AFTER_COMMIT) handler (onReportGenerated); generateReport's own transaction now only does authz + PDF build (CPU-local) + one DB insert (status=PENDING_UPLOAD), no external I/O]`
- D6: `getParentEmailByPlayerId` fires 2 separate DB queries (getParentIdByPlayerId + userRepository.findById) — no single-query fetch for parent email+name; TOCTOU gap if parent account deleted between calls [`PlayerProfileService.java`] `[CLOSED by skillars-deferred-77 AC4 — replaced with PlayerProfileRepository.findParentEmailByPlayerId, a single JOIN query; self-registered adults (parentId==null) and a since-deleted parent both now fall through to no row instead of the old findById(null) IllegalArgumentException]`

## Deferred from: code review of skillars-6-5-video-privacy-rbac-account-deletion-cascades (2026-06-23)
- W1: Request-scoped `VideoAccessCache` in singleton `VideoAccessGuard` — standard Spring proxy pattern; correct in web context; only fails in bare non-web unit tests (mocked there anyway) [`VideoAccessGuard.java`] `[CLOSED by skillars-deferred-77 AC11 — re-confirmed false positive; @RequestScope's default TARGET_CLASS proxy is the standard, correct Spring mechanism for injecting a request-scoped bean into a singleton, not a workaround needing a fix]`
- W2: `@TransactionalEventListener` partial cascade failure — if `AccountDeletionCascadeListener` dies mid-loop, videos not yet reached are silently missed; spec-acknowledged known gap; ops can refire manually [`AccountDeletionCascadeListener.java`]
- W3: Native SQL `@Modifying` queries bypass `@Version` optimistic lock — version column not incremented by native queries; pre-existing codebase-wide pattern; auditing all call sites is a separate hardening task [`VideoRepository.java` and other repos]
- W4: ownerId format ambiguity for mixed-type strings — Task 0 Identity Bridge investigation was a mandatory gate; deferred on assumption Task 0 was completed and format confirmed [`VideoAccessGuard.java`]
- W5: `PROCESSING→READY` backward-compat bypass not removed — spec Task 9 requires a separate PR with ops sign-off (7+ days of zero `video.moderation.bypass` counter); intentionally excluded from Story 6.5 [`VideoLifecycleService.java`]
- W8: `cascadeDeleteForAccount` quota reset non-atomic — JVM crash after last per-video commit but before `resetBytesForOwner()` leaves deleted account's quota row permanently non-zero; no retry path corrects this; future reconciliation job spec'd in dev notes [`VideoDeletionService.java:169`] `[CLOSED by skillars-deferred-77 AC12 — quota reset is now conditional on failedIds being empty; a partial-failure cascade logs a warning and leaves quota un-reset for reconciliation instead of zeroing it unconditionally. Per-video deleteVideo() calls were already correct (soft-delete + audit log + outbox + event pipeline all preserved) — a proposed batch-DELETE rewrite would have bypassed all of that and was not applied.]`
- W9: `canDelete(null, videoId)` belt-and-suspenders is a no-op for non-HTTP callers — null auth causes broad catch to swallow the re-check; `@PreAuthorize` is the primary enforceable gate; acceptable for current call sites [`VideoDeletionService.java:117`]
- W10: Parent-play/PURGE race — null `providerAssetId` passed to `generatePlaybackUrl()` if video is concurrently purged between `@PreAuthorize` canPlay evaluation and `PlaybackService.authorizePlayback()` call; provider should throw cleanly; very low probability in practice [`PlaybackService.java`] `[CLOSED by skillars-deferred-77 AC13 — re-verified: deleteVideo() sets operationalState=PURGED synchronously, before the outbox row (which the async processor later uses to null providerAssetId) even exists. authorizePlayback re-reads video fresh and rejects PURGED/DELETED before ever reading providerAssetId, so providerAssetId is null only in states already rejected. Confirmed unreachable — no defensive code added, per this codebase's convention against guarding scenarios that can't happen.]`

## Deferred from: code review of skillars-5-4-skills-radar-display-development-correlation (2026-06-19)
- W1: No FK from `player_radar_baselines.player_id` / `coach_radar_preferences.player_id` to `main.player_profiles` — accepted limitation per spec dev notes; consistent with Stories 5.1–5.3 no-FK pattern across `development.*` tables [`V51__radar_display_correlation.sql`] `[PARTIALLY CLOSED by skillars-deferred-77 AC9 — V113 adds ON DELETE CASCADE FK to main.player_profiles(id) for both player_radar_composites AND player_radar_baselines (in-scope decision, not just player_radar_composites as AC9's own text originally hedged). coach_radar_preferences.player_id remains without an FK — out of AC9's scope, still open.]`
- W2: Rapid skill-toggle fires a PUT per click — no debounce; last-write-wins for fast toggling; low risk [`PlayerDevelopmentDashboardPage.vue`]
- W4: Skill deactivation silently drops baseline from display — `findAllByActiveTrueOrderByDisplayOrderAsc` excludes inactive skills; baseline re-appears on reactivation [`RadarDisplayService.java:39`]
- W5: IT `assertThat(minimumSessionCount).isEqualTo(5L)` hardcodes config value — low risk with Testcontainers; `ON CONFLICT DO NOTHING` in V51 migration [`RadarDisplayResourceIT.java:333`]
- W7: `IMPROVEMENT_THRESHOLD = 3.0` hardcoded — exactly-3-point improvement classified as "no improvement"; explicitly accepted in spec dev notes; configurable in a future story [`DevelopmentCorrelationService.java:33`]
- W9: `SkillsRadarChartSpec.js` tests cannot run — vitest / `@vue/test-utils` not installed; explicitly accepted in story completion notes; frontend test-runner setup is a separate initiative

## Deferred from: code review of skillars-5-3-skills-radar-assessment-entry-multi-coach-cumulation — Pass 2 (2026-06-19)
- DEF6: Orphaned `player_radar_composites` rows on player deletion — `player_id` column has no FK to `player_profiles`; deleted player leaves stale composite rows; pre-existing no-FK pattern across the development module [`player_radar_composites`, `V50__radar_assessment_entries.sql`] `[CLOSED by skillars-deferred-77 AC9]`
- DEF7: Async composite silently stales on failure — `@Async` listener swallows all exceptions (logged only); no retry/dead-letter queue; composite frozen at prior value until next submission triggers recomputation; accepted per story dev notes [`RadarCompositeCalculationService.java:onRadarEntrySubmitted`] `[CLOSED by skillars-deferred-77 AC10 Phase 2 — RadarCompositeDlqService/RadarCompositeDlqProcessor now catch the failure, persist it to development.radar_composite_dlq, and retry with exponential backoff up to a configurable max-attempts before marking DEAD]`

## Deferred from: code review of skillars-5-3-skills-radar-assessment-entry-multi-coach-cumulation (2026-06-19)
- DEF1: `SkillDefinitionRepository` injected directly into `SkillDefinitionResource` (no service layer) — pre-existing architecture; fix at next planned touch of `SkillDefinitionResource` [`SkillDefinitionResource.java:17`]
- DEF3: Concurrent async composite recalculation race — two simultaneous submissions for the same player trigger two `@Async` events that can both query aggregates before either upserts; last writer wins and self-corrects on the next submission; theoretical low-probability issue [`RadarCompositeCalculationService.java:onRadarEntrySubmitted`] `[CLOSED by skillars-deferred-77 AC10 Phase 1 — recalculateComposite now acquires a PessimisticLockRetryer-backed lock on the player's player_profiles row (findByIdForUpdate + explicit entityManager.refresh) for the whole read-aggregates-then-upsert sequence, serializing concurrent recalculations for the same player]`
- DEF4: No retry or dead-letter queue for async composite failure — failure logged but composite silently stale until next assessment triggers recomputation; accepted per story dev notes; address in an infrastructure hardening story if operational visibility requires it [`RadarCompositeCalculationService.java`] `[CLOSED by skillars-deferred-77 AC10 Phase 2 — see DEF7 above, same fix]`

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection — Round 2 Group C (2026-06-19)
- D5: `SluTargetEditor` `currentTargets` watcher can discard in-progress user input if `fetchTargets` resolves while the dialog is open (race between fetchExposure completing and fetchTargets completing). Low probability; fix by guarding the targets-loaded state or deferring the watcher when open. [`SluTargetEditor.vue:51`] `[AUDIT 2026-08-13: already fixed — SluTargetEditor.vue:58-62 has the if (open.value) return guard this item asked for; verified during skillars-deferred-21 story creation]`

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection — Round 2 Group A (2026-06-19)
- D1: V49 `CREATE UNIQUE INDEX` blocks startup if phased deploy allowed Monday batch to create duplicate flags between V48 and V49 — mitigated by same-commit deployment of both migrations; negligible in standard CI pipeline [`V49__neglected_skill_unique_open_constraint.sql`]
- D3: All skills flagged neglected for inactive/new player — `actual=0` falls below every coach target; technically correct per AC 4 literal but causes flag-flood on first evaluation; consider a "minimum sessions in the evaluated period" guard in a future UX refinement story [`NeglectedSkillProcessor.java`] `[CLOSED by skillars-deferred-76 AC9]`
- D6: `SluCalculationService` async ISO week boundary race — `now` captured pre-`saveAll`; a session straddling Monday midnight writes SLU rows and snapshot to different ISO weeks — pre-existing design acknowledged in story dev notes [`SluCalculationService.java:177-187`] `[AUDIT 2026-08-27: false premise — skillars-deferred-77 AC7 investigation confirmed SluCalculationService.java:153 captures Instant now ONCE and reuses that same value for both PlayerSkillStat.calculatedAt and the ISO week/year snapshot derivation; there is no second, independent timestamp capture to race. Not tagged CLOSED since no code changed — see W1/W2 below for the real, separate saveAll→writeAll failure-window gap this item's title conflates with.]`

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection (2026-06-19)
- W1: Partial snapshot missing if failure occurs between `sluRepository.saveAll` and `snapshotBatchWriter.writeAll` — acknowledged in dev notes; snapshot is eventually-consistent and does not roll back with SLU rows [`SluCalculationService.java:177-187`] `[PARTIALLY ADDRESSED by skillars-deferred-77 AC8 — saveAll itself now retries via SluPersistenceRetrier before SLU rows are considered lost, narrowing this window; writeAll (the snapshot write) still has no retry/compensation of its own, so a failure there specifically remains open]`
- W2: SluCalculationService week-boundary race — `now` captured before saveAll; a failure spanning midnight ISO week boundary could mismatch iso_week between SLU rows and their snapshot entry; negligible probability `[AUDIT 2026-08-27: same false premise as D6 above — skillars-deferred-77 AC7 confirmed a single now capture is reused for both, no race exists]`
- W3: V48 `INSERT INTO platform_config ON CONFLICT (key) DO NOTHING` silently preserves wrong existing value — pre-existing migration pattern across all stories [`V48__development_exposure_dashboard.sql:43`]

## Deferred from: code review of skillars-5-1-slu-engine-skill-taxonomy — Pass 2 (2026-06-18)
- D1: No retry on saveAll failure — SLU permanently lost on transient DB error; dev notes acknowledge and provide a recovery query; infrastructure-wide limitation [`SluCalculationService.java:165`] `[CLOSED by skillars-deferred-77 AC8 — new SluPersistenceRetrier @Component (separate bean, avoiding the self-invocation pitfall that would defeat @Retryable if it lived on SluCalculationService itself) wraps sluRepository.saveAll in @Retryable with exponential backoff; @Recover logs the ops-visible "manual recovery needed" message on exhaustion. spring-retry was already a pom.xml dependency with @EnableRetry already active — the AC's premise that it needed adding was stale.]`
- D2: CallerRunsPolicy can block HTTP thread under executor saturation — prior review explicit tradeoff: AbortPolicy silently drops SLU vs CallerRunsPolicy blocks request thread [`AsyncConfig.java:40`]
- D3: Duration rounding over/under-counts block time — prior review accepted as intentional approximation; documented in dev notes [`SluCalculationService.java:121`]
- D4: Thread.sleep in negative-path IT tests — prior review explicitly deferred; acceptable for negative async tests with no positive signal [`SluCalculationServiceIT.java`]
- D5: No booking_id stored in player_skill_stats — no DB-level idempotency anchor; behavioral gap addressed by idempotency pre-check patch; schema addition out of story scope [`V46__development_module_init.sql`]
- D6: No guard on zero/negative repDensity/intensity metadata fields — pre-existing drill creation validation gap; zero repDensity silently produces no SLU without warning log [`SluFormula.java`] `[CLOSED by skillars-deferred-76 AC10 — same root cause as W1 above]`
- D7: NUMERIC(10,4) overflow at extreme session attribute values — theoretical at realistic gameplay values with default 0.10 scales [`SluFormula.java`, `V46__development_module_init.sql`]
- D8: SluRepository inherits deleteAll/deleteById — AC 4 met; comment warns developers; runtime override-to-throw is defense-in-depth only [`SluRepository.java`]
- D9: Skill code case-sensitivity — lowercase skillWeighting keys silently dropped; fix belongs at drill creation (input normalisation), not SLU calculation [`SluCalculationService.java`] `[CLOSED by skillars-deferred-76 AC10 — D9's "silent" framing was also partially stale: current code does log.warn on an unrecognized skill code, but the functional gap (contribution silently dropped) is the same bucket]`

## Deferred from: code review of skillars-5-1-slu-engine-skill-taxonomy (2026-06-18)
- W1: Negative metadata fields (repDensity/intensity/etc.) can produce corrupt SLU via double-negative — pre-existing validation gap at drill creation; fix at DrillMetadata validation layer [`SluFormula.java:45-66`] `[CLOSED by skillars-deferred-76 AC10]`
- W2: @Async executor naming ambiguity — explicit `@Async("taskExecutor")` qualifier would eliminate uncertainty; largely covered by the AsyncUncaughtExceptionHandler patch [`SluCalculationService.java:43`]
- W4: Thread.sleep in negative-path IT tests — acceptable for negative async assertions where no positive signal exists; replace with Awaitility + log spy if flakiness is observed in CI [`SluCalculationServiceIT.java:107,125,135,171`]
- W5: Platform config IDs 70-72 skip 68-69 — intentional gap; no migration uses 68-69; ON CONFLICT DO NOTHING prevents failures [`V46__development_module_init.sql:51-55`]
- W6: player_id and coach_id have no FK constraints on player_skill_stats — intentional for immutable audit rows; cascading deletes would corrupt historical SLU [`V46__development_module_init.sql:19,21`]

## Deferred from: code review of skillars-4-6-homework-assignment-player-locker-room (2026-06-18)
- W1: `getLockerRoomDrills` calls `hasActivePack` once per unique coach (N+1 queries) — performance concern, not correctness; batch API needed; address in a performance-hardening pass [`HomeworkAssignmentService.java:getLockerRoomDrills`] `[CLOSED by skillars-deferred-75 AC6]`
- W4: `@Size(max=2)` on `WrapUpRequest.homeworkDrillIds` not enforced on event-driven path — HTTP validation is the only entry point today; add size guard in service if other publishers emerge [`HomeworkAssignmentService.java`] `[CLOSED by skillars-deferred-75 AC6]`

## Deferred from: code review of skillars-4-5-intelligent-drill-suggestions-session-templates — Round 2 (2026-06-18)
- W1: `deleteTemplate()` no ARCHIVED guard — idempotent re-archive silently succeeds (204) on already-archived template; acceptable behavior [`SessionTemplateService.java:deleteTemplate`] `[CLOSED by skillars-deferred-75 AC1]`
- W2: `deployTemplate()` passes `t.getBlocks()` by reference not defensive copy — safe in current code path; no mutation after save in same transaction [`SessionTemplateService.java:deployTemplate`] `[CLOSED by skillars-deferred-75 AC1]`
- W3: `computeFocusScore()` returns 0 for all-unsupported focus values — random subset within age-fit tier (0.10 base score); by-design stub behavior [`DrillSuggestionService.java:computeFocusScore`] `[CLOSED by skillars-deferred-75 AC11 (validated at the request boundary instead of left as a silent fallback; the original "by-design stub" framing was itself inaccurate — the function is a real 8-code switch, not a stub)]`
- W4: Template name inputs missing `maxlength="200"` client-side — server `@Size(max=200)` catches it; generic error is acceptable UX [`SessionTemplateVault.vue`, `SessionBuilderPage.vue`]
- W5: `createTemplate()` store action never sets `error.value` on failure — callers handle errors; minimal impact on store error state [`sessionTemplate.store.js`]
- W6: `SessionTemplate.blocks` null risk if `session.getBlocks()` null — `Session.blocks` is NOT NULL in DB so sessions should never have null blocks; constraint prevents [`SessionTemplateService.java:createTemplate`]

## Deferred from: code review of skillars-4-4-session-builder-block-structure-dna — round 2 (2026-06-18)
- W8: `isBookingPlannable` accepts `"UPCOMING"` but no known code path transitions a Booking to this status — proactive future-proofing, harmless if UPCOMING is never set [`SessionPlanService.java:167`] `[CLOSED by skillars-deferred-77 AC15 — this item's own premise was stale: BookingReminderScheduler does transition bookings to UPCOMING (~24h before session by default), so accepting it here was live, reachable behavior, not dead future-proofing. Product decision made during this story: isBookingPlannable now accepts only CONFIRMED and explicitly rejects UPCOMING with a warning log — a real behavior change (coaches can no longer create a session plan for the first time in the ~24h window before a session), applied to both duplicate copies (SessionPlanService.java:207 and SessionTemplateService.java:169; the AC's original target, BookingService, doesn't contain this method at all).]`
- W9: `updateSession` does not re-validate booking plannable status at update time — a booking cancelled after session plan creation can still be updated freely; outside story scope [`SessionPlanService.java:updateSession`] `[CLOSED by skillars-deferred-75 AC2]`

## Deferred from: code review of skillars-4-4-session-builder-block-structure-dna (2026-06-18)
- W1: COMPLETED status transition never wired from booking completion — `session.status` is set to `COMPLETED` on `createSession` guard but no code path (booking completion event, scheduler, or explicit endpoint) ever transitions a DRAFT/SAVED session to COMPLETED. Cross-story dependency: Story 3.6 booking completion event flow. [`SessionPlanService.java`] `[CLOSED by skillars-deferred-74 (verified already fixed): SessionPlanService.handleBookingCompleted, a @TransactionalEventListener(AFTER_COMMIT) on BookingCompletedEvent, transitions any DRAFT/SAVED session to COMPLETED. Shipped by skillars-deferred-70 AC4, never tagged closed on this bullet.]`
- W2: IT test `updateSession_completedSession` does not assert SESSION_PLAN_LOCKED helpCode in response body — test verifies 403 status but never reads `response.body.helpCode` to confirm the correct error code is returned. Test quality improvement. [`SessionBuilderResourceIT.java:271`] `[CLOSED by skillars-deferred-75 AC3]`
- W3: `WrapUpSequence` uses `variant="compact"` instead of spec-specified `"full"` — cosmetic deviation; DNA chart renders at 160px instead of 240px in the wrap-up overlay. [`WrapUpSequence.vue:163`] `[CLOSED by skillars-deferred-75 (verified already fixed, unannotated): WrapUpSequence.vue:167 already renders variant="full". Fixed by an unidentified prior change; never tagged.]`
- W7: IT teardown `DELETE FROM session.sessions` runs before `DELETE FROM booking.bookings` — safe today because there is no FK between the tables; would fail if a FK is ever added. Future-proofing. [`SessionBuilderResourceIT.java:104`] `[CLOSED by skillars-deferred-75 (verified stale): SessionBuilderResourceIT.java has no @AfterEach or raw DELETE FROM teardown SQL at all today; cleanup is handled by the shared Testcontainers reset mechanism. The specific ordering concern this item named no longer applies.]`

## Deferred from: code review of skillars-4-3-custom-drill-uploads — round 2 (2026-06-17)
- W10: `FeatureGatedException` error code not matched by frontend catch block — `startUpload` checks `video.quotaExceeded` and `video.constraintViolated` but not the helpCode produced by `ApiAdvice` for `FeatureGatedException`; requires stale eligibility cache + server gate both firing; generic "upload failed" is not wrong; low probability [`DrillDetailPanel.vue` — `startUpload` catch] `[CLOSED by skillars-deferred-75 (verified already fixed, unannotated): DrillDetailPanel.vue:394-395 already checks errorKey === 'security.featureGated' and shows t('security.featureGated'). Fixed by an unidentified prior change; never tagged.]`

## Deferred from: code review of skillars-4-3-custom-drill-uploads (2026-06-17)
- W1: Concurrent `initiateUpload` on same drill — two provider video objects created by racing threads; loser's video is orphaned at provider; DataIntegrityViolationException handles DB race but provider allocation happens before the save [`DrillUploadService.java`] `[CLOSED by skillars-deferred-75 AC5]`
- W2: `existsByVideoId` timing in concurrent `deleteVideo` for shared-video drills — both concurrent deletes may pass the check before either commits, publishing deletion event twice; double-delete is idempotent at Bunny.net; near-impossible in normal usage [`DrillUploadService.java`] `[CLOSED (same-drill variant only) by skillars-deferred-75 AC5 — DrillUploadServiceConcurrencyIT.deleteVideo_concurrentCallsOnSameDrill_doesNotDoublePublishDeletionEvent proves two concurrent deleteVideo calls on the SAME drillId now serialize correctly via the new per-Drill-row PessimisticWrite lock. This item's own wording ("shared-video drills", i.e. two DIFFERENT drills whose drill_video_refs rows point at the same videoId, reachable via DrillLibraryService.cloneDrill:134-136) describes a different race the per-Drill lock does NOT close — two different drillIds acquire two different row locks and do not serialize against each other. That cross-drill variant remains open; a real fix needs a videoId-scoped lock, not a drillId-scoped one.]`
- W3: Transaction rollback after `videoService.initializeUpload` — provider video created, DB transaction rolls back (including UploadSession), so reconciliation worker cannot find the orphaned provider asset [`DrillUploadService.java`] `[Note 2026-08-27: skillars-deferred-75 AC5 closed the concurrent-request race (W1/W2's same-drill variant) via PessimisticLockRetryer; this item's own scenario (a crash/rollback mid-request, not a concurrent second caller) is a different, still-open gap — needs a reconciliation worker, not a lock.]`
- W4: `resolveMinUploadTier` depends on `CoachSubscriptionTier` enum declaration order — informational only; used in error message hint, not in access control; wrong hint if enum is not declared in ascending rank order [`DrillUploadService.java`] `[CLOSED by skillars-deferred-75 AC5 (documented via a comment on the enum; not restructured)]`
- W5: Signed playback URL expires in 2 h but is cached in Pinia store indefinitely — coach must reload to get fresh URL after 2+ hours of idle time; expected signed-URL behaviour [`DrillLibraryService.java`] `[CLOSED by skillars-deferred-75 AC9]`
- W6: `@Async` on `VideoPhysicalDeletionListener` uses default `SimpleAsyncTaskExecutor` (unbounded threads) — low volume expected; add named executor if burst deletion scenarios emerge [`VideoPhysicalDeletionListener.java`]
- W8: AC 3 "configurable 60-min timeout" not specifically wired to drill uploads — inherits pre-existing `UploadSession.expiresAt` scheduler; not changed by this story [`platform.video` scheduler]
- W9: `@TransactionalEventListener` silently drops events if called outside a transaction — hypothetical only; `DrillUploadService` is `@Transactional` so all call paths have a transaction [`VideoPhysicalDeletionListener.java`]

## Deferred from: code review of skillars-4-2-drill-card-operations (2026-06-17)
- W3: removeTag chip visible for any COACH drill — component assumes ownership from context (correct in PRIVATE tab); defensive concern if DrillCard is reused in a multi-coach admin context [`DrillCard.vue`] `[CLOSED by skillars-deferred-75 AC4. Correction: this item's own framing was too soft — it called the multi-context-reuse scenario hypothetical ("if DrillCard is reused"); DrillCard was already reused on PlayerLockerRoomPlaceholderPage.vue at the time this item was originally filed.]`
- W5: COACH vs PRIVATE naming inconsistency — entity/DB stores library_type="COACH"; API param and frontend use "PRIVATE"; pre-existing from Story 4.1; fragile on new developer additions [`DrillLibraryResource.java`, `DrillLibraryService.java`] `[CLOSED by skillars-deferred-75 AC8]`

## Deferred from: code review of skillars-4-1-drill-library-foundation (2026-06-17)
- D1: `session` schema name is a PostgreSQL non-reserved keyword — works on all tested PG versions; renaming after migration is written would require a destructive V40 migration [`V38__session_module_init.sql`]
- D2: V39 seed drills use `gen_random_uuid()` — non-deterministic IDs differ between environments; migration already written; deterministic UUIDs would require a V40 fix migration [`V39__session_foundation_20_drills.sql`]
- D3: Feature gate config key format relies on `tier.name()` matching DB key suffix exactly — new tier addition requires a matching migration; acceptable by convention; no compile-time enforcement [`DrillLibraryService.java:86`]
- D4: `POST /api/session/plans` returns 201 empty body — intentional stub per story dev notes; full implementation in Story 4.4 [`SessionPlanResource.java`] `[CLOSED by skillars-deferred-74 (verified already fixed): the endpoint (now in SessionBuilderResource.createSession, the file having been renamed) returns a real SessionPlanResponse body via sessionPlanService.createSession(...), not an empty 201. Story 4.4 delivered the full implementation this item's own text anticipated.]`
- D6: New coach with no profile gets `ResourceNotFoundException` → 404 from `getCoachIdByUserId` on private drill list — edge case; Story 4.2 to guard on the frontend; backend always requires a complete profile [`CoachProfileService.java`]
- D7: `listPrivateDrills` no explicit `library_type = 'COACH'` filter — safe today due to DB `chk_drill_owner` constraint preventing PLATFORM drills from having a non-null `owner_coach_id` [`DrillRepository.java`]

## Deferred from: code review of skillars-3-7-session-pause-resume (2026-06-16)
- D1: SSE race during in-flight pause — if remote resume (SSE `IN_PROGRESS`) arrives while local pause API is in-flight, `watch` restarts timer while `pausing=true`; UI self-corrects on next event; multi-device edge case [`ActiveSessionScreen.vue`]
- D2: SSE heartbeat handler closes/reopens EventSource unconditionally, resetting retry counter while active polling is running — can cause multi-second status gaps; pre-existing in `booking.store.js`
- D3: `elapsed` resets to 0 on component remount; `sessionStartTime` prop is never consumed to reconstruct elapsed time — accumulated active time is lost on browser refresh; pre-existing [`ActiveSessionScreen.vue`]
- D4: `completionLoading` flag shared across pause/resume/end — consumers cannot distinguish which operation is in-flight; component uses local `pausing`/`resuming` refs for buttons so user-visible impact is nil; pre-existing store design [`booking.store.js`]

## Deferred from: code review of skillars-3-6-session-completion-live-mode-quick-complete (2026-06-16)
- W1: JPQL string literal `'COMPLETED'` in `findPendingQuickCompletes` is fragile against `BookingStatus` enum rename — pre-existing pattern project-wide [`SessionCompletionDataRepository.java:22`]
- W3: `BookingCompletedEvent` has no retry/DLQ mechanism if listener fails after commit — infrastructure limitation, pre-existing across all event consumers [`BookingEmailListener.java`]
- W4: `getDrillSuggestions` has no `@Max` constraint on `limit` parameter — stub endpoint fully replaced by Epic 4; guard when real implementation lands [`SessionCompletionResource.java`] `[CLOSED by skillars-deferred-74: this item's own stated precondition ("guard when real implementation lands") was met — getDrillSuggestions now calls the real DrillSuggestionService.suggest(...) — and the guard still didn't exist, converting this from deferred to a live gap. Fixed: @Validated added to the class (required for method-parameter @Min/@Max to be enforced at all — confirmed absent before this fix), limit constrained to @Min(1) @Max(10), mirroring CoachMarketplaceResource's identical size-param pattern. Max value (10) was a project-owner decision, generous headroom over the endpoint's own default of 2.]`
- W5: Auto-return after wrap-up reloads `selectedWeek` instead of current week — minor UX edge case when coach was browsing a different week [`CoachCommandCenterPage.vue:305`]
- W6: V33 migration uses hardcoded `id = 39` for `platform_config` insert — low collision risk given sequential pattern; validate before deploying to environments with manual config inserts [`V33__session_completion_data.sql:3`]

## Deferred from: code review of skillars-3-5-scheduling-views-timezone-management (2026-06-15)
- W1: Revenue gross calculation ignores variable session pricing (pack discounts, multi-session rates) — spec defines gross as `perSessionPrice × count` (AC 2), variable pricing is out of scope; revisit in a pricing-model story [ProjectedRevenueService.java]
- W2: N+1 DB queries in `getParentPlayerSchedule` (coachProfile + credits + in-flight count per booking) — pre-existing codebase pattern shared with `getParentBookings`; address in a performance-hardening pass [BookingService.java] `[CLOSED by skillars-deferred-82 story creation (false premise): getParentPlayerSchedule (BookingService.java:623-661) no longer performs any coachProfile/credits/in-flight-count lookups of this shape at all — refactored at some point without a ledger update. The coach-name lookup that does remain was already batched by skillars-deferred-81 AC1.]`

## Deferred from: code review of skillars-3-4-booking-state-machine-sse (2026-06-15)
- No optimistic/pessimistic lock on `transition()` — concurrent callers can both pass `validate()` on the same booking; add `@Lock(PESSIMISTIC_WRITE)` in a concurrency-hardening pass [BookingService.java:85] `[CLOSED by skillars-deferred-77 AC14 — reframed during story-review: @Version already prevented silent state corruption (concurrent losers got a clean OptimisticLockingFailureException at save time, not corruption), so this was a UX fix (blocking-and-retry vs. fail-fast-and-retry), not a correctness fix. transitionInternal now takes the codebase's established PessimisticLockRetryer + findByIdForUpdate + explicit entityManager.refresh pattern, not the originally-proposed (invalid) @Lock annotation on a private service method.]`
- SSE endpoint accepts subscriptions for terminal-state bookings — emitters accumulate for COMPLETED/CANCELLED/REFUNDED bookings; implement lifecycle-based subscription guard in a resource-management pass [BookingEventResource.java:37] `[CLOSED by skillars-deferred-71 AC1: subscribeToEvents now branches on BookingStateMachine.isTerminal — an already-terminal booking gets a short-lived (5s) emitter via the new BookingSseService.subscribeTerminal, never registered in the long-lived emitters map. The frontend's useBookingSse also self-closes its EventSource on receiving a terminal status (via the client's own .close(), which does not trigger EventSource's auto-reconnect), so an emitter that transitions to terminal while genuinely subscribed is no longer left open for the full 5-minute timeout in practice either.]`
- `verifyIsParty` has no admin bypass path — no admin role exists yet; revisit when admin management stories are implemented `[CLOSED by skillars-deferred-71 (verified already fixed): BookingEventResource.verifyIsParty already opens with an isAdmin() bypass. Admin-role infrastructure has existed for some time; this bullet's "no admin role exists yet" premise is long stale.]`
- Polling fallback has no exponential backoff — 2 s fixed interval is spec-prescribed degraded mode; add backoff if hammering becomes observable in production [booking.store.js]
- `isCoachParty()` returns generic 403 when coach profile is deleted — indistinguishable from unauthorized third-party; improve error when coach-account-deletion story is implemented [BookingEventResource.java:70-73] `[CLOSED by skillars-deferred-78 AC5 (confirmed during skillars-deferred-81 story creation, 2026-08-28): the method (now named verifyIsParty) already distinguishes "no coach profile" from "wrong coach profile" in its WARN-level logging, per its own inline comment. The response itself stays a generic 403 in both cases deliberately — that was this item's own actual ask ("indistinguishable... improve error"), interpreted here as improving diagnosability, not the caller-facing message.]`
- Dead `CANCELLED` entry in `BookingStateChip.statusMap` — harmless graceful-degradation fallback; clean up after data migration is confirmed complete [BookingStateChip.vue] `[CLOSED by skillars-deferred-71 (verified incorrect premise): BookingStatus.CANCELLED is the live, reachable target of every CANCEL_DUE_TO_PAUSE transition (pack-pause cancellations) — it is not a dead legacy entry. Do not remove this statusMap entry.]`
- `PAYMENT_FAILED` sets no `refundEligibility` — `null` is intentional; Epic 7 handles payment-failure refund logic independently [BookingService.java:applyRefundLogic] `[CLOSED by skillars-deferred-71 (verified stale): applyRefundLogic no longer exists — refund_eligibility/refund_amount were dropped from booking.bookings by V97. This concept was fully removed from the domain by a later story rather than fixed.]`
- `useBookingSse()` not wired into `BookingStateChip` — SSE wire-up deferred to consuming page/component story; chip will be connected when the parent booking detail page is built `[CLOSED (confirmed during skillars-deferred-81 story creation, 2026-08-28): BookingStateChip.vue now imports and calls useBookingSse(props.bookingId) directly. Landed unannotated by an intervening story.]`

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group E (2026-06-15)
- Authority id 9502 leaked in `playerNotOwnedByParent_returns403` test — `finally` block cleans user + user_authority but not the authority row; `@AfterEach` only deletes ids 9500, 9501; add `DELETE FROM main.authority WHERE id = 9502` to the finally block [BookingRequestResourceIT.java:289] `[AUDIT 2026-08-13: not reproducible — main.authority.name is UNIQUE and id=9500 already holds name='ROLE_PARENT' from this test class's own @BeforeEach, so the id=9502 INSERT ... ON CONFLICT (name) DO NOTHING this item describes silently no-ops every run; row 9502 is never actually created and nothing leaks. Verified during skillars-deferred-21 story creation.]` `[CLOSED by skillars-deferred-71: the 2026-08-13 AUDIT already established this is not reproducible; formally closing rather than leaving it as a perpetually-reopened AUDIT note.]`

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group B (2026-06-15)
- N+1 player name + credit queries in `getParentBookings` — already tracked from Group A
- Midnight-crossing sessions fail/pass incorrectly in availability window check because endZdt.toLocalTime() wraps past midnight; add explicit day-boundary guard when requestedEnd < requestedStart (in LocalTime) [BookingService.java:228-232] `[CLOSED by cross-reference (skillars-deferred-71): this is the same midnight-crossing defect independently fixed by skillars-deferred-69 AC1-AC2 — see that story's own BookingService.isSlotWithinAvailabilityWindow bullet elsewhere in this file for the fix detail. Duplicate filing, not a separate remaining gap.]`
- DST transition can shift booking time by 1h relative to window boundary; acceptable for current scope; revisit when timezone management (Story 3.5) is implemented [BookingService.java:isSlotWithinAvailabilityWindow] `[CLOSED by skillars-deferred-71 (verified stale): current isSlotWithinAvailabilityWindow/getAvailabilityCalendar derive every window boundary via a fresh per-date ZonedDateTime conversion using real IANA tz rules, correctly handling DST by construction — this predates and is unrelated to Story 3.5; the "revisit when Story 3.5 ships" condition is long since met and the concern does not reproduce.]`
- `w.getDayOfWeek()` vs JS 0-based day format — verify that the availability-windows frontend sends ISO 1-7 (not JS 0-6); pre-existing from Story 3.1 [BookingService.java:230, CreateWindowRequest.java] `[CLOSED by skillars-deferred-71 (verified correct): AvailabilityManagerPage.vue's day-selection form already uses an ISO-1-7 isoDay value, not raw JS Date.getDay(). This was a "verify" task, not a known bug — verification confirms the frontend has always sent the correct format.]`

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group A (2026-06-15)
- `requestedEndTime` minimum duration not validated — 1-second bookings accepted; minimum session length not in scope for Story 3.3; add a `@PositiveDuration(min=15m)` or service-level check in a future session-constraints story [CreateBookingRequest.java:16] `[CLOSED by skillars-deferred-71 (verified already fixed): createBookingRequest requires requestedDuration to exactly equal the coach's resolved session duration (skillars-uat-2's SessionDurationResolver-based check), which is strictly stronger than the originally-suggested minimum-duration floor. A 1-second booking has been impossible for some time.]`
- N+1 queries in `getParentBookings` — player names and effective credits each fire separate SQL per booking row; batch player name lookup the same way coach names are batched; catch when booking volume per parent grows [BookingService.java:getParentBookings]

## Deferred from: code review of skillars-3-2-session-pack-purchase-credit-dashboard (2026-06-13)
- No DB-level state machine constraints — `ACTIVE+credits=0` or `EXHAUSTED+credits>0` not prevented at DB layer; enforce with additional `CHECK` constraints when the status lifecycle is fully stable. [V30__booking_session_packs.sql] `[CLOSED by skillars-deferred-82 story creation (false premise): SessionPackPurchase (payment/repo/SessionPackPurchase.java) has no persisted status column at all — status is computed, not stored, so there is no DB state machine to constrain.]`
- In-memory `coachId` filter when listing a parent's packs — loads all packs for the parent then Java-stream filters by coachId; push the filter into SQL when pack volumes grow. **[AUDIT 2026-08-04: carried over from the deleted legacy `SessionPackService.getPacksForPlayer` into the new payment path — retargeted]** [`SessionPackPaymentService.java:78-81`] `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]`

## Deferred from: code review of skillars-3-1-coach-availability-management (2026-06-13)
- Block spans midnight → negative CSS height in WeeklyCalendar overlay — multi-day block rendering is out of scope for Story 3.1 ACs; handle when calendar becomes a product priority [WeeklyCalendar.vue:1652-1668] `[CLOSED by skillars-deferred-72 (verified already fixed): getBlockStyle already returns zero height, not negative, for a midnight-spanning block via its endMin <= startMin guard. Multi-day block rendering remains a separate, still out-of-scope, product-priority question — this closure is only about the negative-CSS-height defect the item specifically named.]`
- `getAvailabilityCalendar` timezone-expansion logic (LocalTime + canonicalTimezone → Instant) not unit-tested — IT tests cover it implicitly; add targeted unit test when timezone bugs appear or before Story 3.5 timezone management work [AvailabilityServiceTest.java] `[CLOSED by skillars-deferred-72 (verified already fixed): AvailabilityServiceTest now carries five dedicated unit tests of the LocalTime+canonicalTimezone→Instant expansion logic (DST-gap, padding, zone-divergence, and list-order-invariance cases) — the gap this item named no longer exists.]`
- No date-range guard on `weekStart` GET parameter — far past/future dates are harmless for a 7-day view; address if API is ever exposed to untrusted external callers [AvailabilityResource.java:421]

## Deferred from: code review of skillars-2-4-contact-detail-sanitization-ux (2026-06-13)
- Phone regex false positives — `PHONE_PATTERN` can match dates and numeric prose (e.g. "49-60 EUR") in bio text; no false-positive boundary test exists [ContactDetailSanitizer.java] `[CLOSED by skillars-deferred-72: PHONE_PATTERN's unconditional replaceAll replaced with a post-match filter requiring at least one unbroken 5+-digit run inside the candidate — reproduced and confirmed real false positives on year ranges ("2020-2026"), time ranges ("09.00-17.00"), and reference/license numbers ("2023-04-15-001", "100-200-300") no longer match, while both of this class's existing true-positive phone tests still pass.]`
- `wasModified` semantics with sequential email-then-phone substitution — phone regex runs on already-redacted string; edge case may cause unexpected detection flag behavior [ContactDetailSanitizer.java] `[CLOSED by skillars-deferred-72 (verified no defect): wasModified is a single final-state !result.equals(input) comparison computed once after both substitution stages, not a per-stage flag — the order of email-then-phone substitution cannot affect its correctness regardless of what either stage matches or replaces.]`

## Deferred from: code review of skillars-2-3-coach-public-profile-page (2026-06-13)
- N+1 queries — `getPublicProfile` fires 8 sequential DB round-trips; acceptable for single-entity load now, but batch loading or `@EntityGraph` should be considered before Epic 3 traffic ramp [CoachProfileService.java] `[RE-EVALUATED by skillars-deferred-70: not a classic N+1 — getPublicProfile(coachId) is called once per single-coach page view, not once per row in a larger collection. 8 small, indexed, single-row queries for one profile view is unlikely to be the bottleneck it was originally framed as. Left open and unpicked rather than closed — a real fix (EntityGraph/batch-loading) is still reasonable if this page's latency is ever actually measured and found wanting, but should wait for that evidence rather than being done speculatively.]`
- Floating-point savings math in `SessionPackTracker.vue` — `perSessionPrice * sessionCount - totalPrice` uses IEEE 754 arithmetic; add a currency library (e.g. `currency.js`) before pack discounts are prominent in UI [SessionPackTracker.vue] `[CLOSED by skillars-deferred-72 (verified stale): SessionPackTracker.vue was fully rewritten since this item was filed — it contains no savings/currency arithmetic of any kind today, only a credits-remaining progress indicator. The concept this item named no longer exists.]`
- `UNIQUE (coach_id, display_order)` makes naive gallery reorder impossible without a temp value; make the constraint `DEFERRABLE INITIALLY DEFERRED` or redesign reorder API in the media management story [V28__marketplace_coach_media.sql] `[CLOSED by skillars-deferred-70 (verified moot): no gallery-reorder feature exists anywhere in current code — displayOrder is read-only. Re-open if/when a reorder feature is actually built, and apply the DEFERRABLE INITIALLY DEFERRED fix (or redesign the reorder API) at that time, not before.]`
- `VerificationBadge.vue` tooltip presence — verify the existing component already includes tier-explanation tooltip (AC 2); if not, add it in a follow-up [CoachPublicProfilePage.vue] `[CLOSED by skillars-deferred-72 (verified correct): VerificationBadge.vue already renders a per-tier explanation tooltip via a q-tooltip bound to marketplace.tierTooltip{tier}. This was a "verify" task, not a known gap.]`

## Deferred from: code review of skillars-1-6-age-tier-enforcement-family-data-isolation (2026-06-12)
- Flyway V25 hardcoded IDs 112–114 — `ON CONFLICT (key) DO NOTHING` does not guard against PK collision if those IDs are already taken by different rows with different keys; spec explicitly verified the ID range is safe; established codebase Flyway seed pattern [V25__age_policy_config_seed.sql:1–6]

## Deferred from: code review of skillars-1-5-authentication-jwt-security (2026-06-12)
- Tests use raw `jdbcTemplate` inserts instead of Instancio for test data — project rule violation but tests are functionally correct [AuthResourceIT.java]
- `AuthResourceIT` lacks `@Testcontainers` annotation — may be managed via inherited `TestConfig` or `SecurityIT` base class; verify before next review [AuthResourceIT.java] `[CLOSED by skillars-deferred-73 (verified working as designed): AuthResourceIT extends AbstractIntegrationTest, which deliberately uses JVM-static SharedContainers instead of @Testcontainers — an established pattern, not a gap. The item's own hedge ("may be managed via inherited base class") was correct.]`
- `@Observed` at class level vs per-method on `AuthResource` — class-level is a valid Micrometer pattern; no metric data lost [AuthResource.java]
- `ROLE_ROUTES` duplicated across `LoginPage.vue` and `router/index.js` — DRY violation; divergence would cause infinite redirect loop, but no current divergence
- `fr-FR` locale may be missing `auth.coach` sub-tree — investigate whether gap is pre-existing from a prior story [i18n/fr-FR/index.js] `[CLOSED by skillars-deferred-73 (verified present): fr-FR's auth.coach sub-tree exists with full translated content at index.js:155. The "may be missing" premise is stale.]`
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
- D6: `ContactDetailSanitizer` double-redaction edge case — phone pattern can match trailing digits in already-redacted string; benign, no exploitable effect [ContactDetailSanitizer.java] `[CLOSED by skillars-deferred-73 (verified unreachable): REDACTION ("[contact details removed]") contains zero digits, and PHONE_PATTERN requires a leading/trailing digit — it structurally cannot match inside already-redacted text. Not merely benign, actually impossible.]`
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
- Hardcoded container UIDs (65534/10001/472) not tied to Docker image versions — upstream UID changes (historically seen with Grafana) would silently break subdirectory ownership after snapshot restore [docs/deployment/backup-restore.md] `[AUDIT 2026-08-27: re-verified, still open, still a legitimate low-probability accepted tradeoff — hardcoded UIDs remain untied to image versions in provision.sh/restore-from-volume-backup.sh; monitor upstream image changelogs rather than fix now]`
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
- App and DB left in partial state on mid-restore failure — no recovery trap by design; operator must manually restart the app service and investigate [restore-from-dump.sh:90] `[AUDIT 2026-08-27: re-verified, still open — restore-from-dump.sh still has no trap on mid-restore failure. Note: its sibling restore-from-volume-backup.sh (which replaced restore-from-snapshot.sh) was given a trap ... ERR when rewritten; that improvement was never ported back here, so this is now an inconsistency between the two restore scripts rather than a uniform gap]`

## Deferred from: code review of deploy-3-1-postgresql-backup-automation (2026-06-04)
- PGPASSWORD exposed via docker exec `-e` flag (visible in `ps aux` for duration of call) — spec-prescribed pattern; would require Docker secrets or a wrapper script to fix [deploy/backup/pg-backup.sh:22]
- Credentials visible in `/proc/<pid>/environ` when `.env` is sourced — project-wide pattern, not introduced by this story
- ~~No retention policy — S3 dumps and Hetzner snapshots accumulate unbounded; add lifecycle rules or a rotation script in a future backup hardening story~~ **CLOSED 2026-08-11 by `skillars-uat-3` AC6.** New `deploy/backup/prune-backups.sh`, installed by `install-crons.sh` at `30 3 * * *` (clear of both producers): `BACKUP_RETENTION_DAYS` (default 14) for the Object Storage dumps and `SNAPSHOT_RETENTION_DAYS` (default 7) for Hetzner snapshots, with a `BACKUP_RETENTION_MIN_KEEP` floor (default 8) that survives any age miscalculation, a `--dry-run` mode, key-stamp-derived ages rather than object mtimes, and a fatal error on an empty listing or an unparseable API response. Documented in `backup-restore.md` (Retention), `secrets-reference.md` and `runbook.md`. **But see the new `volume-snapshot.sh` item below — the snapshot half currently has nothing to prune, because Hetzner Cloud has no volume snapshot at all.**
- install-crons.sh installs cron for the invoking user with no enforcement — typically root; document the expected user or add a guard in a future hardening pass `[CLOSED by skillars-deferred-76 AC1]`
- No upload integrity check (checksum / ETag verification after aws s3 cp) — out of scope for this story
- awscli v1 from Ubuntu apt may have `--endpoint-url` edge cases with Hetzner Object Storage — spec-approved as sufficient; revisit if upload failures occur in production

## Deferred from: code review of deploy-2-3-deployment-rollback-documentation (2026-06-04)
- No pre-deploy GHCR image existence check — no step to verify the image tag exists in GHCR before triggering deploy; typo causes mid-run failure after 2–5 min wait. `[CLOSED by skillars-deferred-76 AC4]`
- No GHCR auth failure handling — no guidance if `docker login` fails (expired PAT, wrong token scope) before `docker compose pull`. `[CLOSED by skillars-deferred-76 AC4]`
- Step 5 health check retry loop is manual — "retry after 10 seconds" gives no command to re-run; a simple loop would be deterministic [rollback.md:139–142]. `[CLOSED by skillars-deferred-76 AC4]`
- Partial pull failure leaves .env inconsistent — if `docker compose pull` times out, .env holds new tag but image not available; no recovery path documented [rollback.md:106]. `[CLOSED by skillars-deferred-76 AC4]`
- Auto-Revert fails if previous image deleted from GHCR — GHCR retention policies can evict old images; Auto-Revert pull then fails with `outcome=failed` and production may be in unknown state. `[CLOSED by skillars-deferred-76 AC4]`
- `SSH_KNOWN_HOST` empty or multi-line edge cases — empty secret bypasses known-host verification; multi-line `ssh-keyscan` output is valid but undocumented [deploy.yml:27]. `[AUDIT 2026-08-27: the "bypasses verification" premise is backwards — no StrictHostKeyChecking=no exists, so an empty value fails closed, not open. The real, separate bug (secrets-reference.md's "single line" instruction contradicts ssh-keyscan's real multi-line output) is closed by skillars-deferred-76 AC4]`
- Container name `skillars-app-1` hardcoded in expected output without explaining Docker Compose naming convention (project-service-index) [rollback.md:113]. `[CLOSED by skillars-deferred-76 AC4]`
- No explicit guidance if `docker compose pull app` fails mid-execution — the `&&` chain halts correctly, but no next-step is documented for auth errors, network timeout, or image-not-found. `[CLOSED by skillars-deferred-76 AC4]`

## Deferred from: code review of deploy-2-2-manual-production-deploy-workflow-with-smoke-test-auto-revert (2026-06-04)
- `Fail workflow` step is unreachable if a notification step throws — job still fails (attributed to the notification step instead), same end outcome, low severity diagnostic issue [`.github/workflows/deploy.yml`:139-143].

## Deferred from: code review of deploy-2-1-automated-ci-build-pipeline (2026-06-04)
- No `SPRING_PROFILES_ACTIVE` in `ENTRYPOINT` — the container boots on the base profile; prod-specific beans and any config not overridden by environment variables silently use dev defaults. Recommend documenting the required env var in the Compose service definition. **RE-SCOPED TO PRODUCTION ONLY by `skillars-uat-1` (2026-08-10):** `docker-compose.uat.yml:4` sets `SPRING_PROFILES_ACTIVE=uat`, so this no longer applies to a UAT deploy. It remains open for the base `docker-compose.yml` and the `Dockerfile` `ENTRYPOINT` — i.e. for a plain production deploy, which is also why `PaymentConfig`'s live-key guard is written as opt-in on non-prod profiles rather than opt-out on prod. `[CLOSED by skillars-deferred-76 AC3]`
- ~~No stable/latest symbolic tag alongside the SHA tag~~ **CLOSED by `skillars-uat-2` (2026-08-10):** `ci.yml` now pushes `ghcr.io/<repo>:latest` alongside `:sha-<short>`. The SHA tag stays and stays first — `docs/deployment/rollback.md` pins to it and a rollback still requires it explicitly.

## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-04)
- Repo cloned to `/opt/skillars` before Hetzner Volume mounted — volume mount overlays `/opt/skillars/data`; benign today since repo has no `data/` content, but fragile if repo structure changes.
- ~~`acme.json` lives on root disk~~ **CLOSED by `skillars-uat-2` (2026-08-10):** moved to `/opt/skillars/data/traefik/acme.json` on the Hetzner Volume. `provision.sh` creates it in a new section 7.5 placed *after* the mount (creating it from the old section 6.5 would have written to the root disk and had the mount hide it) and outside the `if [ -b /dev/sdb ]` branch so a no-volume host still gets one. `docs/deployment/uat-deployment.md` Step 6 carries the in-place `mv` an existing box needs.
- ~~Redis data on named Docker volume (root disk)~~ **CLOSED by `skillars-uat-2` (2026-08-10):** bind-mounted at `/opt/skillars/data/redis`, uid 999 verified from the image itself. The `redis-data` named volume is gone, leaving `docker-compose.yml` with no top-level `volumes:` key at all; `docker-compose.local.yml` gained a `redis` override so a dev machine does not get the production path.
- No outbound firewall rules — observability containers (Prometheus, Loki, Tempo, Redis) have unrestricted internet egress; security hardening enhancement.
- Docker Hub unauthenticated pull rate limits not documented — shared Hetzner egress IPs can hit the 100/6h limit; rare but unmitigated.
- Partial `provision.sh` failure recovery undocumented — `set -euo pipefail` exits on first error; re-run may silently skip a broken install block [deploy/provision.sh].
- No rollback procedure documented for a bad `APP_IMAGE` deploy when Flyway migrations have already run — operational concern for Epic 3. `[CLOSED by skillars-deferred-76 AC5]`
- Loki (720h), Tempo (336h), Prometheus (15d) retention periods inconsistent and undocumented — no disk sizing or tuning guidance [deploy/lgtm/]. `[CLOSED by skillars-deferred-76 AC2]`
- No secret rotation procedure documented (PostgreSQL password, JWT secret, Grafana admin password) — ongoing operational maintenance concern.
- JWT_SECRET minimum length stated (64+) but Spring algorithm and actual enforcement not documented — application implementation detail. `[CLOSED by skillars-deferred-76 AC6 — corrected: the real gap was that JWT_SECRET is dead configuration the app never reads at all, not merely "undocumented enforcement"]`
- Grafana admin initial login not explicitly verified as part of Step 7 deployment completion check. `[CLOSED by skillars-deferred-76 AC8]`
- `provision.sh` re-run while stack is live runs `chown -R` over live data mounts — safe with current UIDs but fragile on container image UID changes. `[CLOSED by skillars-deferred-76 AC1]`

## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)
- Firewall applied after provisioning — SSH port 22 is open to all internet IPs during the provisioning window. Deliberate ordering constraint (Hetzner firewall requires local hcloud CLI run, user may not have local clone yet). Consider documenting the exposure window or restructuring for users who already have a local clone.
- `/dev/sdb` hardcoded device path unreliable on multi-volume servers — if Hetzner changes device assignment order the mount silently fails. The doc accuracy fix is a patch (see F2); fixing the script is Story 1.1 territory [deploy/provision.sh:145].
- Repo cloned as root into `/opt/skillars` — `.git` directory sits alongside runtime data and secrets. Pre-existing architectural decision; would require a deploy-user or sparse-checkout approach to change.
- `bantime=3600s` in fail2ban is a minimal starter value — inadequate for production. 1-hour bans are bypassed by slow-rate botnets. Pre-existing Story 1.1 config [deploy/provision.sh]. `[CLOSED by skillars-deferred-76 AC1]`
- No rollback / disaster-recovery documentation — explicitly out of scope for Story 1.5; belongs to Epic 3 (Stories 3.2 and 3.4).
- git clone root (`/opt/skillars`) contains the volume data subdirectory (`/opt/skillars/data`) — `git clean` could interact with data dirs if `.gitignore` coverage lapses. Pre-existing architecture.

## Deferred from: code review of deploy-1-4-security-hardening (2026-06-03)
- `err()` writes to stderr — lost in stdout-only log capture; if callers redirect stdout to a log file, error messages won't appear in it [deploy/provision.sh].

## Deferred from: code review of deploy-1-3-lgtm-observability-stack Round 2 (2026-06-03)
- `chown` calls in provision.sh run unconditionally on every execution — safe for first provision, but re-running against a live system can interrupt in-progress container writes; document script as "first provision only" [deploy/provision.sh]. `[CLOSED by skillars-deferred-76 AC1]`
- Duplicate logical alert definitions for PaymentFailureRateHigh, OrangeCircuitBreakerOpen, MtnCircuitBreakerOpen exist in both `alerts.yml` (Prometheus rules) and `grafana-alerts.yml` (Grafana unified alerts) — different notification paths with no Alertmanager wired; revisit when Alertmanager is added to avoid double-paging. `[CLOSED by skillars-deferred-76 AC7 — the underlying alerts never existed at all; superseded by real Stripe-based alerting]`

## Deferred from: code review of deploy-1-3-lgtm-observability-stack (2026-06-03)
- Alert rule divide-by-zero guards (CallbackFailureRatioHigh, FraudBlockRateHigh, PaymentFailureRateHigh) in `deploy/lgtm/alerts.yml` — pre-existing in root `alerts.yml`; copied per spec. Guards like `and (...) > 0` needed on all ratio denominators. `[CLOSED by skillars-deferred-76 AC7]`
- `DbConnectionPoolHigh` alert has no label selector — pre-existing in root `alerts.yml`. Add `by (pool)` clause or label filter. `[CLOSED by skillars-deferred-76 AC2]`
- TraceID regex `[a-f0-9]{32}` only matches lowercase hex; OTel SDKs may emit uppercase. Pre-existing in root `grafana-datasources.yml`. `[CLOSED by skillars-deferred-76 AC2]`
- `spanStartTimeShift`/`spanEndTimeShift` of 1h creates extremely wide Loki query windows on trace drill-down. Pre-existing in root `grafana-datasources.yml`. Reduce to 1m/1m. `[CLOSED by skillars-deferred-76 AC2]`
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
- D5: `COACH` value in `proposedBy` DB constraint allowed but never set — coach-initiated reschedule path not in scope this story; DB is future-proof [`BookingRescheduleRequest.java`] `[CLOSED by skillars-deferred-69 AC5: RescheduleService gained requestRescheduleAsCoach/acceptRescheduleAsParent/declineRescheduleAsParent, mirroring this codebase's established per-actor-separate-method convention. BookingError.CANNOT_RESPOND_TO_OWN_PROPOSAL guards every accept/decline method against the proposer responding to their own proposal. New endpoints POST /{id}/reschedule/coach, PUT /{id}/reschedule/{rescheduleId}/accept-parent, PUT /{id}/reschedule/{rescheduleId}/decline-parent. Frontend: ParentBookingsPage.vue gained accept/decline buttons for coach-proposed reschedules; CoachCommandCenterPage.vue (the coach booking page, not previously identified) gained a "Propose New Time" dialog and its existing accept/decline buttons are now gated on proposedBy === 'PARENT'.]`
- D6: Service-layer tests use Mockito unit test pattern (`@ExtendWith(MockitoExtension.class)`) — story spec Task 20/21 explicitly defined unit tests; integration coverage provided by `RescheduleResourceIT` [`RescheduleServiceTest.java`, `BookingDuplicationServiceTest.java`]
- D7: `datetime-local` input in reschedule dialog coerces proposed times to browser local timezone — the ISO-8601 string sent to the API reflects the user's local offset, not the coach's canonical timezone; browser local time intent is ambiguous (parent and coach may be in different timezones); add a visible canonical timezone hint label next to the inputs in a future UX polish story [`ParentBookingsPage.vue`] `[CLOSED by skillars-deferred-65 AC4: added a :hint to the reschedule dialog's proposed-start q-input on ParentBookingsPage.vue, stating the browser-local interpretation and the session's own canonical timezone, mirroring the endDerivedHint pattern]`

## Deferred from: code review of skillars-3-10-session-pack-expiry-pause-management (2026-06-17)
- D2: `@TransactionalEventListener(AFTER_COMMIT)` failure silently loses coach cancellation notifications — if email dispatch fails after commit, the coach is never notified even though bookings are `CANCELLED`. Event delivery reliability (retry/DLQ) is an infrastructure-wide concern not introduced by this change. [`BookingEmailListener.java`, `SessionPackEmailListener.java`]

## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system (2026-06-20)
- Def2: `VideoQuotaReservation.status` as raw String vs enum — intentional per story notes to avoid JPA enum binding complexity with raw SQL paths; values DB-constrained via CHECK constraint. [`VideoQuotaReservation.java:status`]
- Def3: Long arithmetic overflow in `storageUsedBytes + requestedBytes` — theoretical at practical quota sizes (max ~9.2 EB); no guard exists. [`QuotaService.java:check`, `QuotaService.java:reserve`]
- Def4: `commit()` no-op on already-COMMITTED is indistinguishable from not-found — `updated == 0` is logged as debug; callers cannot differentiate idempotent from non-existent handle; intentional idempotency design. [`QuotaService.java:commit`]
- Def5: `expireBatch()` exception mid-loop not caught — exception terminates the do-while; Spring `@Scheduled` catches it at the framework level; next firing will retry. [`QuotaReservationTimeoutService.java:expireStaleReservations`]
- Def8: `BandwidthResetService` period drift when job runs late — `bandwidth_period_start` set to `NOW()` on actual run date, not 1st of month; next period boundary shifts accordingly; acceptable drift for non-billing context. [`BandwidthResetService.java:resetMonthlyBandwidth`]

## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system Run 2 (2026-06-20)
- Def9: `sumActiveReservedBytes` includes expired-but-unreaped ACTIVE rows — brief (<60s) window between expiry and reaper firing causes conservative over-reporting; intentional design. [`VideoQuotaReservationRepository.java:22`]
- Def10: `BandwidthResetService` full-table lock risk at month boundary — single unpartitioned UPDATE locks all video_quotas rows, blocking concurrent `reserve()` calls; scaling concern. [`BandwidthResetService.java`]
- Def14: `DrillUploadService.deleteVideo()` TOCTOU on `clearVideoId`/`existsByVideoId` — pre-existing; concurrent deletes on different drills sharing the same `videoId` can publish `VideoPhysicalDeletionEvent` twice. [`DrillUploadService.java:~104-108`] `[CLOSED (same-drill variant only) by skillars-deferred-75 AC5 — see the fuller note on this same item's mirror bullet under skillars-4-3-custom-drill-uploads' 2026-06-17 review. The different-drills-sharing-videoId variant this item's own wording names remains open — a per-Drill-row lock does not serialize two different drill rows against each other.]`
- Def17: `AdminVideoService.deleteVideo()` — `release()` exception inside `TransactionTemplate` kills delete transaction — pre-existing. [`AdminVideoService.java`]
- Def18: V53 platform_config IDs 117-132 hardcoded — verify against all intermediate migrations (V43–V52) before deploying; any ID conflict causes Flyway failure. [`V53__video_quota_system.sql:32-50`]
- Def19: `sumActiveReservedBytes` theoretical `ClassCastException` — PostgreSQL BIGINT SUM typically maps to Long via JDBC but no compile-time guarantee. [`VideoQuotaReservationRepository.java:22`]

## Deferred from: code review of skillars-6-2 (2026-06-22)

- Def22: `UploadSessionExpiryScheduler` releases quota outside TX then marks session EXPIRED in separate TX — non-atomic; safe because `release()` is idempotent, but ordering is fragile to future refactors. Pre-existing design decision. [`UploadSessionExpiryScheduler.java`]

## Deferred from: code review of skillars-6-3-content-moderation-pipeline (2026-06-22)

- W1: Feature flags default false in all environments — the spec explicitly documents this as the sprint completion criterion: "story is complete for sprint purposes when the placeholder compiles and the feature flag gates it off in all environments." Deployment enablement is an ops concern outside this story's scope. [`application.yaml`, `AppFeature.java`]
- W2: VideoIntelClientImpl blocking RestTemplate thread exhaustion — if VideoIntelClientImpl is ever implemented using RestTemplate (synchronous polling of a 5-minute GCP async operation), it will hold async thread pool threads for the full duration. This concern is subsumed by D2 (VideoIntelClientImpl scope decision); if D2 resolves to implement in Story 6.3, a proper non-blocking implementation must address thread exhaustion. [`VideoIntelClientImpl.java`, `VideoIntelConfig.java`]
- W3: SLA monitor re-queues via Spring events, not outbox — `ModerationSlaMonitorService` publishes `VideoModerationRetryEvent` directly via `ApplicationEventPublisher`. If the app crashes after `findScanningOlderThan()` returns but before events publish, retry intents for that cycle are lost; next cycle recovers. Full outbox support deferred to a future hardening story. [`ModerationSlaMonitorService.java`]

## Deferred from: post-implementation review of skillars-6-3 (2026-06-22)

- RW1: SSE subscribe → onStatusChanged race — state transition committed between `videoService.findById()` and `emitter.send(currentStatus)` is missed. Polling fallback mitigates. Architectural limitation of SSE without event sourcing. [`VideoSseService.java:39`, `VideoEventResource.java:39`]
- RW2: scanned_at misleading on upsert retry path — `@Column(updatable=false)` retains original failed-attempt timestamp even when SLA retry overwrites outcome to PASSED. Fix requires append-only per-attempt rows (architectural scope beyond this story). [`VideoModerationScan.java:39`]

## Deferred from: code review of skillars-6-6-player-video-management-portal (2026-06-24)
- W2: N+1 queries in `VideoApprovalResource.listPendingApprovals()` — one `playerProfileService.getPlayerNameByPlayerId()` + one `videoRepository.findById()` per approval row; acknowledged in spec TODO; acceptable for single-family use. [`VideoApprovalResource.java`]
- W3: V60 DDL ACCESS EXCLUSIVE lock risk — `ALTER TABLE main.videos DROP CONSTRAINT / ADD CONSTRAINT` takes table-level ACCESS EXCLUSIVE lock with no `SET lock_timeout`; can cause connection pile-up under concurrent video uploads. [`V60__video_approval_portal.sql`]
- W4: `autoRejectExpired` JPQL uses `current_timestamp` (returns `java.util.Date`) on an `Instant`-typed field — type mismatch; method is explicitly NOT WIRED (comment says so); safe until a scheduler is added. [`VideoApprovalRequestRepository.java:autoRejectExpired()`]
- W5: `@GeneratedValue(AUTO)` on `VideoApprovalRequest` entity vs `UUID DEFAULT gen_random_uuid()` in SQL — Hibernate 6 AUTO may allocate a sequence-based Long for AUTO strategy on non-Long PK; pre-existing entity pattern; verify Hibernate dialect resolves UUID correctly. [`VideoApprovalRequest.java`]

## Deferred from: code review of skillars-7-2-session-payment-lifecycle-credit-wallet (2026-06-24)
- D4: Raw `String` fields for `type` (ParentCreditLedger) and `status` (BookingPayment) instead of Java enums — DB constraint guards correctness; higher migration cost to add enum mapping

### Group 4 adversarial deferred (Booking module) — 2026-06-24
- D13: `getParentBookings` does not clamp negative `effectiveCredits` to 0 — inconsistency with `getParentPlayerSchedule`; pre-existing [`BookingService.java:316`] `[CLOSED by skillars-deferred-72 (verified stale): the effectiveCredits concept no longer exists anywhere in getParentBookings or the codebase — superseded by later work, not merely fixed.]`
- D15: Past-elapsed `requestedStartTime` at CANCEL_PARENT gives NONE refund eligibility — correct path is NO_SHOW_COACH event; edge case [`BookingService.java:471`] `[CLOSED by skillars-deferred-64 AC4 — refund eligibility for a past-elapsed CANCEL_PARENT is now widened directly (refund-only), without converting to NO_SHOW_COACH, which is the option this item's own text proposed and the project owner declined during skillars-deferred-64's story-review]`

### Group 3 adversarial deferred (API + Contracts) — 2026-06-24
- D11: `getActiveCoachTier` returns 204 No Content when no active tier found — spec says "returns empty if none" (ambiguous); 204 is unusual for a typed GET endpoint; more idiomatic would be 404 or 200/null; defer until client null-handling issues surface [`SessionPackPaymentResource.java:101-105`]

### Group 6 adversarial deferred (Tests) — 2026-06-24
- D20: `CashOutServiceTest` happy-path sets `lastPaymentIntentId` but `CashOutService.processCashOut()` may read `stripePaymentMethodId` for the refund; if field mismatch is confirmed, the happy-path test is verifying a null PI and must be corrected [`CashOutServiceTest.java:54`, `CashOutService.java`] `[CLOSED by skillars-deferred-82 story creation (false premise): CashOutService.processCashOut reads getLastPaymentIntentId(), and the test already stubs exactly that field — no mismatch exists.]`
- D23: Unit-level idempotency ledger-count guard (`duplicateEvent_idempotencyNoOp` verifies skip but not that ledger mock was never called) — covered by `PaymentWebhookIdempotencyIT`; low value to add unit-level assertion

## Deferred from: code review of skillars-7-3-cancellation-refund-reliability-strikes (2026-06-25)
- D1: `buildSort()` has identical branches for "price" and "rating" — pre-existing; both fall back to `displayName`; price sort is applied in Java post-enrichment [`CoachSearchService.java:buildSort`]
- D3: `GET /coaches/me/strikes` has no pagination — unbounded list; low risk at current scale [`ReliabilityStrikeResource.java`]
- D4: Concurrent strike issuance race — two simultaneous events for the same coach may both read count=N and both fire the threshold event; inherent to non-locking count approach [`ReliabilityStrikeService.java:issue`]
- D5: `CoachCancellationHistory.createdAt` with `@Column(updatable=false)` + `@PrePersist` — in-memory entity is null until DB round-trip if ever used with batch `saveAll`; low risk given single-save usage [`CoachCancellationHistory.java`]

## Deferred from: code review of skillars-7-5-revenue-dashboard-financial-reporting (2026-06-26)
- D1: Running balance incorrect when two ParentCreditLedger entries share an identical createdAt instant and straddle a page boundary — the strict-less-than predicate in sumByParentIdAndCreatedAtBefore excludes the prior-page twin from the opening balance, understating the running balance for the current page by that twin's amount; extremely rare in practice; inherent in the chosen pagination anchor design [RevenueReportingService.java:211]

## Deferred from: code review of skillars-8-1-messaging-module-foundation-conversation-threads (2026-06-26)
- D2: N+1 query pattern in getConversations — 3 queries per conversation (findLastApproved, countUnread, resolveOtherPartyName); acceptable for MVP conversation volumes; batch-optimize when conversation counts grow. [MessagingService.java:toSummary]

## Deferred from: code review of skillars-deferred-3 (2026-07-01)
- D1: V76 partial index predicate hardcodes status literals (`'EXHAUSTED', 'EXPIRED'`) with no in-diff verification against the full status enum — a future added status could silently fall inside the "active" partial index instead of being excluded. [`src/main/resources/db/migration/V76__missing_indexes.sql`]

## Deferred from: code review of skillars-deferred-4 (2026-07-02)
- D3: `@SchedulerLock` and `@Transactional` are stacked on the same method with no explicit `@Order`, so their AOP advisor nesting order (lock vs. transaction boundary) is unspecified — plausible but unconfirmed risk that the distributed lock could release before the DB transaction commits. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java:40`, `BookingReminderScheduler.java`, `src/main/java/com/softropic/skillars/platform/video/service/BandwidthResetService.java`]

## Deferred from: code review of skillars-deferred-8 (2026-07-02)
- D2: `declineBooking_wrongCoach_returns403` reads `createResp.getBody().get("id")` without asserting the booking-creation POST succeeded first — mirrors the same pre-existing gap in `acceptBooking_wrongCoach_returns403`; a transient creation failure surfaces as an opaque NPE/404 instead of a clear assertion failure. [`src/test/java/com/softropic/skillars/platform/booking/api/BookingRequestResourceIT.java`]
- D3: Hardcoded `PLAYER_ID = 9360000001L` in the new IT is reused against the shared `SecurityIT.SEC_DATA_SQL_PATH` fixture — possible cross-test collision risk if that fixture seeds rows for the same player ID outside the tables cleaned in `@AfterEach`; not provable from this diff alone. [`src/test/java/com/softropic/skillars/platform/development/service/NeglectedSkillDetectionServiceIT.java:41,47`]

## Deferred from: code review of skillars-11-3-remove-legacy-session-pack-system (2026-08-04)
- D1: `V89__drop_legacy_session_packs.sql`'s `DROP TABLE` has no `IF EXISTS` guard — not blocking (Flyway won't re-run an applied migration, table confirmed empty at this dev/UAT stage), but there's no prior DROP TABLE in this codebase to establish a convention either way; adopt `IF EXISTS` for future destructive migrations. [`src/main/resources/db/migration/V89__drop_legacy_session_packs.sql`]
- D2: Code deletion and the destructive `DROP TABLE` migration ship together with no staged rollout (remove references first, verify, drop table in a later release). Not applicable now — no live/production system exists yet — but relevant once this app has real deployed traffic and rolling deploys. [`src/main/resources/db/migration/V89__drop_legacy_session_packs.sql`]
- D3: `session.homework_assignments.pack_id` has no FK and now points at nothing meaningful — pre-existing design (column never had a `REFERENCES` clause, per V45), not introduced by this diff. [`src/test/java/com/softropic/skillars/platform/session/api/HomeworkResourceIT.java`] `[CLOSED by skillars-deferred-75 AC7. Correction: this item's premise was wrong — pack_id is not dead; HomeworkAssignmentService.resolvePackId still writes a real, live payment.session_pack_purchases.purchase_id on every assignment. The FK was simply never added; V109 adds it.]`

## Deferred from: code review of skillars-deferred-10 (2026-07-02)
- D0: `pr-build.yml`'s Docker build never runs/scans the built image (`push: false`, no `load: true`) — user decision: `deploy.yml`'s existing smoke test is the real safety net; add `load: true` + a smoke command here only if PR-time runtime validation becomes worth the added CI cost. [`.github/workflows/pr-build.yml`]
- ~~D1: `ci.yml`'s push trigger branch-name mismatch (`branches: [main]` vs the `master` default).~~ **DELETED as CLOSED by `skillars-uat-1` (2026-08-10).** `.github/workflows/ci.yml:4` reads `branches: [master]`, and CI has been publishing to GHCR on every push to `master`. The premise no longer holds.
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
- D6: Inconsistent concurrency control: `pausePack` takes a pessimistic lock but `deductSession`/`restoreSession`/`extendPack` do not — those methods predate this story and are unmodified except for call-site signature changes. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`, `SessionPackPaymentService.java`] `[CLOSED by skillars-deferred-82 story creation (already fixed, unannotated): all four now call findByIdForUpdate under lockRetryer.withBoundedRetry (PackSessionService.java:56,76,128; SessionPackPaymentService.java:122).]`
- D7: `SessionPackForfeitureScheduler` doesn't re-verify `expiresAt` immediately before forfeiting inside the per-row transaction, leaving a window where a concurrent extension could still get forfeited — inherent to the legacy-mirrored select-then-per-row-transaction scheduler shape. [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureScheduler.java`]
- D8: TOCTOU between the conflicting-bookings query and the per-booking `cancelDueToPause` calls in `pausePack` — same risk shape as the legacy method being mirrored. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D9: Stringly-typed computed `status` field and hardcoded `CONFLICT_STATUSES` list rather than shared enums — consistent with existing codebase convention; legacy also uses string status constants. [`src/main/java/com/softropic/skillars/platform/payment/contract/SessionPackPurchaseResponse.java`, `PackSessionService.java`]

## Deferred from: code review of skillars-11-2-cutover-booking-and-frontend (2026-08-03)
- D2: Pack-selection-criteria mismatch when a player+coach pair has 2+ simultaneously-active packs: the frontend displays the soonest-expiring pack, while the backend's `getActivePackId` (duplication/homework-gating) picks the oldest-created pack FIFO — the pack shown to a parent may not be the one actually deducted. May be intentional (display urgency vs. FIFO consumption order), needs product input. [`src/frontend/src/pages/parent/ParentPlayerPortalPage.vue`, `src/frontend/src/pages/parent/SessionPackPurchasePage.vue`, `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java`] `[CLOSED by skillars-deferred-65 AC1: SessionPackPurchaseRepository.findActivePacks now orders ORDER BY p.expiresAt ASC, p.createdAt DESC, matching the frontend's soonest-expiring-first display, with a secondary tiebreak matching the frontend's own newest-created-wins tiebreak]`

## Deferred from: skillars-deferred-11-stripe-card-collection
- D1: ~~Coach subscription card collection is blocked on a real schema constraint.~~ **CLOSED 2026-08-13 by `skillars-uat-6` AC1-AC3.** Both premises were stale by the time this story scoped the work: (a) the endpoints were already widened to `HAS_PARENT_OR_PLAYER_ROLE` by `skillars-uat-5`, not still `HAS_PARENT_ROLE`; (b) no schema change was ever needed — `CoachProfile` (`marketplace.coach_profiles`) already carries a `Long userId` column in the exact same id space `StripeCustomer.parentId` uses, the identical opaque-id shortcut `skillars-uat-5` used for self-registered players. `skillars-uat-6` AC1 widened the three card-collection endpoints to a new `HAS_PARENT_PLAYER_OR_COACH_ROLE`; AC2 made `subscribeCoach` resolve the payment method from the coach's own `StripeCustomer` row (mirroring `subscribePlayer`) instead of the never-written `PaymentCoachSubscription.stripeCustomerId` column; AC3 replaced `CoachSubscriptionPage.vue`'s raw pm-id input with the same `PaymentMethodCard`/`hasCard` pattern players and parents use. ORIGINAL: Coach subscription card collection (`CoachSubscriptionPage.vue`, which still has the raw "Payment Method ID" text input this story removed from the player-subscribe path) is blocked on a real schema constraint: `StripeCustomer`'s `@Id` is `parent_id` (a `Long` user id), and `POST /api/payment/setup-intent` / `GET /api/payment/payment-method` are both `@PreAuthorize(HAS_PARENT_ROLE)`. Supporting coach card collection requires either a second customer table or re-keying `payment.stripe_customers` — a schema migration with its own design decision, out of scope here. [`src/main/java/com/softropic/skillars/platform/payment/repo/StripeCustomer.java`, `src/frontend/src/pages/coach/CoachSubscriptionPage.vue`]
- D2: 11.2 D2 (pack-selection-criteria mismatch: frontend shows soonest-expiring, backend `getActivePackId`/`findActivePackId` picks oldest-created FIFO) still needs product input — this story's AC 7 deliberately preserved the existing FIFO backend ordering rather than resolving the mismatch. [`src/frontend/src/pages/parent/SessionPackPurchasePage.vue`, `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`] `[CLOSED by skillars-deferred-65 AC1: SessionPackPurchaseRepository.findActivePacks now orders ORDER BY p.expiresAt ASC, p.createdAt DESC, matching the frontend's soonest-expiring-first display, with a secondary tiebreak matching the frontend's own newest-created-wins tiebreak]`
- D3: ~~AC 5's instruction to remove the shared i18n key `subscription.paymentMethodId` (and `subscription.paymentMethodHint`) turned out to be still in active use.~~ **CLOSED 2026-08-13 by `skillars-uat-6` AC3.** `CoachSubscriptionPage.vue`'s raw pm-id input is gone (replaced by the `PaymentMethodCard`/`hasCard` pattern), so `subscription.paymentMethodId` and `subscription.paymentMethodHint` are now fully orphaned and were removed from all three locale bundles (`en-US`, `de-DE`, `fr-FR` — the redundant fourth `en` bundle this annotation referenced no longer exists, removed by `skillars-uat-4`). `subscription.coach.paymentMethodRequired` was also confirmed orphaned and removed alongside them. ORIGINAL: AC 5's instruction to remove the shared i18n key `subscription.paymentMethodId` (and `subscription.paymentMethodHint`) turned out to be still in active use by the explicitly out-of-scope `CoachSubscriptionPage.vue` — removing it would have broken that page's raw payment-method-id input. Kept both keys in all four locale files; only removed the confirmed-orphaned `subscription.player.paymentMethodRequired`. [`src/frontend/src/pages/coach/CoachSubscriptionPage.vue`, `src/frontend/src/i18n/{en-US,en,de,fr-FR}/index.js`] **ANNOTATED by `skillars-uat-1` (2026-08-10): do not schedule this as independent work.** It is a symptom, not a cause — the page renders a raw `pm_...` input only because coach self-serve card collection does not exist (`StripeCustomer`'s `@Id` is `parent_id`, and both `POST /api/payment/setup-intent` and `GET /api/payment/payment-method` are `HAS_PARENT_ROLE`). These keys disappear on their own the moment that story ships. Fold this into the coach-subscription story rather than tracking it separately.

## Deferred from: code review of skillars-deferred-12-booking-payment-review-integrity (2026-08-04)
- D2: ~~Parent-cancel can race the AFTER_COMMIT payment settle.~~ **CLOSED 2026-08-11 by `skillars-uat-3` AC2 + AC3.** The fix is a `PESSIMISTIC_WRITE` re-read of the booking row (`BookingRepository.findByIdForUpdate`, bounded 5 s lock wait) plus a `CAPTURE_PENDING` interlock: `cancelBookingAsParent` now does unlocked read -> ownership check -> **locked** re-read (that order deliberately, so a stranger cannot pin a row on their way to a 403 — the `deferred-16` D2 finding), and refuses with 409 `booking.paymentInProgress` while a reservation stands. The mirror half is `reserveCapture`, which takes the same lock and returns `BOOKING_NOT_PENDING` if the cancel won, so Stripe is never reached. Mutation-verified: deleting the interlock makes `BookingServiceTest#cancelBookingAsParent_captureInFlight_refuses409AndLeavesBookingPending` fail with "Expecting code to raise a throwable" — i.e. the cancel *succeeds*, not merely that a different exception surfaced. **Severity correction recorded honestly:** `POST /api/bookings/{id}/cancel` has **no frontend caller** (`booking.api.js:64` is the only hit for `cancelBooking` in all of `src/frontend/src`), so this was reachable by direct API call only, not by a UAT tester clicking through the app — see the new item below. Original text follows.
  ORIGINAL: Parent-cancel can race the AFTER_COMMIT payment settle. `cancelBookingAsParent` reads `PAYMENT_PENDING`, computes `refundEligible=false` and commits `CANCELLED_PARENT` while `PaymentLifecycleService` is mid-capture; the listener's subsequent `PAYMENT_CAPTURED` from `CANCELLED_PARENT` is an illegal transition, thrown and swallowed inside the listener. Net: money captured, booking cancelled, no refund, no compensation. `transitionInternal` takes no lock and `@Version` does not help across the two commits. Narrow window, but real. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingStateMachine.java:34`, `BookingService.java:563`]

## Deferred from: code review of skillars-deferred-15-payment-pending-sweeper-accept-path-integrity (2026-08-05)
- D1: `SessionPackExpiryNotifier` stamps `expiryWarnedAt` in the same transaction that publishes the warning event, before the `AFTER_COMMIT` listener actually attempts delivery — a mail-send failure in the listener permanently loses the warning with no retry. Mirrors the identical accepted tradeoff already in `SessionPackForfeitureScheduler`, and is the same platform-wide `AFTER_COMMIT`-listener-reliability gap `skillars-10-2` D1 already tracks generally; not a new pattern introduced by this diff. [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java:72-87`]
- D2: `BookingService.acceptBooking` / `RescheduleService.acceptReschedule` call `coachProfileRepository.findByIdForUpdate` and then immediately `entityManager.refresh(lockedCoach, PESSIMISTIC_WRITE)` — two separate `SELECT ... FOR UPDATE` round trips on the same row for the same lock. Verified this is the exact idiom already established in `BookingService.createBookingRequest:201-215` (`skillars-deferred-12` AC3), which this diff was explicitly directed to mirror byte-for-byte — a pre-existing pattern, not introduced here. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:279-289`]
- D3: `BookingRepository.findPaymentPendingOlderThan`'s correctness assumes `updatedAt` reflects only the transition into `PAYMENT_PENDING`. `@PreUpdate` stamps `updatedAt` on any field change, so an unrelated write to a `PAYMENT_PENDING` booking would silently reset the stranded-booking clock and let it evade the sweep indefinitely. Verified no such write path exists anywhere in `src/main` today — speculative risk against a future path, not a live defect. [`src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`]
- D4: The batch-status trailing transaction in `BookingBatchService.acceptAll` and the `AFTER_COMMIT` listener's `updateBatchStatusFromBooking` both read-then-write `computeBatchStatus` unlocked — a last-writer-wins race remains between them, though both now compute from the same correct formula (unlike the pre-`skillars-deferred-15` AC5 disagreement between two different formulas). This residual non-atomicity was already explicitly documented and accepted in `skillars-deferred-14`'s own commit message ("acceptAll is explicitly documented as no longer atomic"). [`src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:249-258,308-321`] `[CLOSED by skillars-deferred-69 AC6: BookingBatchRepository gained findByIdForUpdate (PESSIMISTIC_WRITE + NO_WAIT + PessimisticLockRetryer's bounded retry, mirroring the established findByIdForUpdate pattern); both write sites now take this lock instead of the unlocked findById, eliminating the last-writer-wins race rather than narrowing it.]`

## Deferred from: code review of skillars-deferred-16-messaging-moderation-recovery-identity-safety (2026-08-05)
- D3: AC4's orphaned-profile fail-safety is list-only, producing three different outcomes for one conversation: `getConversations` excludes it, `getMessages` returns it in full (no age-policy lookup at all — only `verifyIsParty` on `parentId`), and `sendMessage` 404s on the throwing variant. The "excluding from parent's list" ERROR log reads like an access-control decision but is not one. Resolving the inconsistency is a product call about what a parent should see for an unresolvable player, beyond AC4's stated scope. [`src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java:108-116,168,215-228`] `[DECIDED 2026-08-29 (skillars-deferred-82 story creation): leave as-is. No live code path today can actually produce this state — nothing deletes a player_profiles row while leaving it referenced (confirmed during skillars-deferred-81 AC4's own research) — so this remains a documented, non-reachable edge case, not a fix candidate.]`
- D4: Rolling-deploy ordering for the new `AdminAlertType.MODERATION_UNRESOLVED`. `V91` correctly widens the CHECK before the enum ships, but nothing gates *creation* of the new alert type until every instance carries the new enum — an older instance reading such a row hits `Enum.valueOf` and 500s the whole `GET /api/admin/queue` and `/queue/summary` page, not just the affected row. The sweeper's first tick fires at startup of the first upgraded instance. Not reachable on the current single-instance Docker Compose deployment. [`src/main/java/com/softropic/skillars/platform/admin/contract/AdminAlertType.java:4`, `AdminQueueService.java:50-58,128-133`]
- D5: AC6a's code-point guard admits 2000 code points = up to **4000** UTF-16 chars, which is exactly `platform.messaging.moderation.gemini.max-input-chars`. `GeminiModerationService` truncates with `content.substring(0, maxInputChars)` only when `length() > maxInputChars`, so at 4000 == 4000 the branch is not taken and no surrogate is split — safe by exact coincidence. Lowering `max-input-chars` (the reviews profile already uses 2000) or raising the code-point limit ships an unpaired surrogate into the moderation prompt. Nothing in code or tests ties the two constants together. Separately, `codePointCount` validates count but not validity: a lone surrogate escape in the JSON body passes the guard and is silently mangled to `?` by the JDBC UTF-8 encoder. [`src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java:150-151`, `GeminiModerationService.java:43-45`, `application.yaml:263,268`] `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]`
- D6: `SoftDeleteIT.concurrentDoubleSoftDelete_exactlyOneSucceeds_oneConflicts` synchronises the *start* of two HTTP round-trips (TCP connect, auth filter chain, controller dispatch) rather than the read-check-write critical section, so it can pass by request serialisation rather than by the lock. The dev recorded a successful mutation verification (reverting to `findById` produced two 204s), so the fix is proven — but the test is not a durable guard, the same class of finding `skillars-deferred-13` and `-15` reviews both raised. Its `SELECT COUNT(*) ... WHERE id = ? AND deleted_at IS NOT NULL` assertion is additionally tautological over a primary key. [`src/test/java/com/softropic/skillars/platform/messaging/api/SoftDeleteIT.java`]
- D7: No test walks AC3's chain end to end — sweep → `MessageHeldForReviewEvent` → alert → queue listing → approve → alert RESOLVED. `AdminQueueIT`, `MessageApproveIT` and `MessageBlockIT` all hand-insert the `MODERATION_UNRESOLVED` alert row via `jdbcTemplate`, and `MessageModerationSweeperIT` stops at asserting the alert exists. Each link is covered in isolation; the seam between the sweeper's event and the admin queue's rendering and resolution is unpinned. [`src/test/java/com/softropic/skillars/platform/admin/api/AdminQueueIT.java:130-140`, `MessageApproveIT.java:127-134`, `MessageModerationSweeperIT.java`] `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]`

## Deferred from: code review of skillars-deferred-17-booking-request-slot-payload-timezone-integrity (2026-08-06)

- **D6 — Zero frontend regression coverage for deferred-17's headline fix.** There is no frontend test suite in this repo (no `*.spec.js` outside `node_modules`, no `src/frontend/test`). Every test added or touched by deferred-17 is backend-only, so reverting the `.vue` and `booking.store.js` changes — the edits that actually made booking submission work at all — would leave the entire suite green. The story explicitly forbids introducing a test framework as part of its scope; this restates the standing gap already recorded for `skillars-5-4` W9.
- **D8 — Reconcile the two `canonical_timezone` columns to a single source of truth.** `coach_profiles.canonical_timezone` (`V26__marketplace_coach_profiles.sql:11`) and `coach_availability_windows.canonical_timezone` (`V26:55`) are independently writable — `CoachProfileService.java:90` sets the profile's from Step 1 and `:173` sets each window's from its own Step 4 payload, and `AvailabilityService.updateWindow` never re-syncs a window after a profile-level change. Deferred-17 AC4 worked around the divergence for one page by making display read the profile column; it did not remove the divergence. Deferred from the deferred-17 code review's D1 decision (Mbah, 2026-08-06: accept and document). Real fix needs a migration, a backfill rule for which value wins on existing rows, and a product decision on whether per-window zones are a deliberate feature (a coach who coaches across zones) or an accident of the profile-builder form. `[PARTIALLY ADDRESSED by skillars-deferred-63 AC6, still open]` `[CLOSED by cross-reference (skillars-deferred-70): the "still open" write-path half of this item was decided one day later under the skillars-deferred-63 story-review heading below — see that section's own CoachProfileService.saveStep4 bullet, tagged DECIDED 2026-08-25. Per-window coach timezone is a deliberate feature; saveStep4 keeps trusting the request payload; no further action planned.]` Product decision made (profile is sole source of truth, not a deliberate per-window feature) and existing diverged rows backfilled via `V103__availability_window_timezone_backfill.sql` — but `saveStep4` still writes each window's `canonicalTimezone` from the request payload rather than the profile, so new divergence can still occur going forward; story-review found `ProfileBuilderStep4.vue` ships a real, coach-editable per-window timezone picker that a `saveStep4` write-path fix would need to be coordinated with (see the new item filed below). Note the display-side risk is largely contained once the slot label carries `timeZoneName` — instants are absolute, so no wrong moment is ever transmitted, only a differently-labelled one.

## Deferred from: code review of skillars-deferred-18-availability-slot-timezone-integrity (2026-08-07)

- **D2 — `blocks` week-scoping and the fetch bounds both hang off an unordered `findByCoachId`.** `AvailabilityService.java:85-86` derives `weekStartExact`/`weekEndExact` from `zoneId`, i.e. from `windows.get(0)`, and `CoachAvailabilityWindowRepository.findByCoachId` issues no `ORDER BY`. For a coach with windows in two zones, two identical requests can therefore return different `blocks` sets and use different fetch bounds purely from row-order variation; and a block that visibly punches a hole in a divergent-zone window's slot is absent from the response's `blocks` array, so `AvailabilityManagerPage.vue` renders a gap it cannot explain. This story narrowed the outer zone's role from a fetch bound to a response filter but did not remove the arbitrariness, which is the same divergence class as D8 below and is not independently fixable — picking a deterministic zone requires deciding *which* column is authoritative. Adding `ORDER BY id` to `findByCoachId` would make it deterministic-but-still-arbitrary, which is arguably worse (it hides the problem). Blocked on D8. `[CLOSED by skillars-deferred-65 AC3, for week-scoping only: AvailabilityService.getAvailabilityCalendar now derives weekStartInstant/weekEndInstant/weekStartExact/weekEndExact from the coach profile's own canonical_timezone instead of windows.get(0), so week-scoping bounds are no longer row-order-dependent. CoachAvailabilityWindowRepository.findByCoachId still issues no ORDER BY, so per-window display order in the response's windowResponses is unaffected and remains a separate, still-open display-order nondeterminism — not fully resolved by this fix]`
- **D4 — `@IanaTimezone` accepts fixed offsets, so its name and message overstate what it enforces.** `IanaTimezoneValidator.java:19` uses `ZoneId.of(value)`, which also accepts `"+05:00"`, `"Z"`, `"GMT+2"`, `"UTC+02:00"`. Those are not IANA region IDs and are DST-blind: a coach whose `canonical_timezone` is `"+01:00"` gets wall-clock times an hour wrong for half the year, and every consumer (`AvailabilityService`, `BookingService.isSlotWithinAvailabilityWindow`, the frontend `Intl` formatting) silently agrees on the wrong time. AC4 declared strictness out of scope and the 2026-08-07 review decision (Mbah) kept `ZoneId.of` and reworded the message/Javadoc instead of tightening, so the honesty gap is closed but the behaviour gap is not. Real fix is `ZoneId.of(v) instanceof java.time.ZoneRegion` (or `getAvailableZoneIds().contains(v)`), which needs its own test surface *and* an answer for values already stored — see the D10 audit-query decision in the story's Dev Notes, which deliberately ran no audit. Pairs with D5: tightening this makes D5 strictly worse. `[CLOSED by skillars-deferred-65 AC2: IanaTimezoneValidator now also requires ZoneId.getAvailableZoneIds().contains(value) in addition to ZoneId.of parseability, rejecting fixed offsets like "+01:00"/"GMT+2"/"Z" while still accepting real IANA region ids; tighten-only, no audit/backfill of already-stored non-conforming values]`

## Deferred from: skillars-uat-1-admin-bootstrap-and-onboarding-unblock (2026-08-10)

Found while implementing the story. Each was examined and deliberately left alone; none blocks UAT.

- **D2 — two of AC6's four rewritten `default` arms are unreachable and therefore untested.** `resolveLastReadAt` (`MessagingService.java:439`) and `updateLastRead` (`:456`) both sit behind a `verifyIsParty` call that throws first — `updateLastRead` runs after the guard inside `getMessages`, and `resolveLastReadAt` is reached only from the summary mapper. No caller can drive either with an unrecognised role. Changing them was still correct (all four now raise the same type, so whichever a future caller reaches first behaves identically), but `MessagingAccessControlIT.unrecognisedRole_yields403NotFatal` deliberately asserts only the two reachable arms — `MessagingService.verifyIsParty` and `MessagingReportService.verifyIsParty` — rather than faking a reachability that does not exist. Recorded so a later reviewer does not read the gap as an oversight.

- **D3 — `AdminBootstrapRunner` cannot elevate an existing account, by design.** If the configured email already belongs to a coach, parent or player, the runner skips rather than granting `ROLE_ADMIN` to that row. This is deliberate (an "upgrade this user to admin" path is a different and riskier feature), but it means an operator who typos the address into an existing user's email gets a silent skip with only an INFO line to explain it. If admin provisioning ever becomes routine, it needs a real admin-management surface, not a wider bootstrap.

## Deferred from: skillars-uat-2-session-duration-and-booking-slot-integrity (2026-08-10)

Found or deliberately left while implementing the story. None blocks UAT.

- **D1 — `RescheduleService` performs no availability-window check at all.** Found while scoping AC3. `RescheduleService.requestReschedule` validates only future-start, end-after-start and (now) same-as-original duration; nothing checks that the proposed range falls inside a `coach_availability_windows` row, so a parent can move a session to 03:00 on a day the coach does not work. This is a distinct pre-existing defect from the duration one, and AC3 deliberately added duration enforcement here **without** the window check: adding it changes reschedule semantics — a coach who narrows their availability after accepting would retroactively block a legitimate reschedule of an already-agreed session — and it needs its own regression pass across `RescheduleServiceTest` and `RescheduleResourceIT`, neither of which seeds a window today. The same question governs `BookingDuplicationService`, which is exempt from AC3 for the identical reason. Decide the semantics first (does a reschedule have to fit *current* availability, or only *availability as it stood when the booking was made*?), then fix both together. `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]`

- **D3 — `AvailabilityService.computeAvailableSlots` anchors its grid per segment, which makes slot start times shift when a coach edits a block.** Intended and documented (see the method's javadoc and AC2): the post-block run starts when the coach actually becomes free, so a 12:00–12:45 block yields 12:45, 13:45, … rather than discarding the 12:45–13:00 fragment. The consequence is that adding or removing a block changes the start times of every downstream slot that day, so a parent who had a specific hour in mind may not find it after a coach edits their calendar. AC3 explicitly declines to enforce grid alignment on the write paths for this reason — an alignment rule would retroactively invalidate a booking whose slot was legal when it was made, and `RescheduleService`/`BookingDuplicationService` both write times derived from existing bookings. If UAT feedback says the shifting grid is confusing, the fix is a product decision (window-anchored grid, accepting the dropped fragments), not a bug fix.

- **D4 — the batch path still has no create-time cross-booking overlap check.** The single path has one (`BookingService.createBookingRequest`); `createBatch` does not, and AC4 deliberately left it that way. Batch rows are created `REQUESTED`, and `V87`'s exclusion constraint deliberately excludes `REQUESTED` — its own comment records that two overlapping `REQUESTED` bookings competing for a slot is expected in-band behaviour that the accept-time re-check resolves, and `acceptAll` already runs that re-check against `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED`. Adding a create-time check would reject legitimate competing requests. Recorded so a later reviewer does not read the asymmetry as an oversight. AC4 *did* add the intra-batch overlap check (two slots in the same batch overlapping each other), which is a different and unambiguous case.

- **D5 — `ConfigService`'s 5-minute cache TTL makes a platform-default change look like it did nothing.** `booking.session.defaultDurationMinutes` is read through `ConfigService.getBoundedLong`, and `ConfigService` refreshes on a `@Scheduled(fixedDelayString = "${app.config.cache-ttl-seconds:300}")`. An admin who changes the value and immediately reloads the availability calendar sees the old slot length and will reasonably conclude the setting is broken. `updateConfig` does call `invalidate()`, so a change made *through the config API* is immediate; a change made directly in the database is not. Documented in the V93 migration comment; a real fix is either an admin-UI note or narrowing the TTL, both of which are wider than this story.

- **D6 — no frontend test coverage for any of this story's `.vue` changes.** Standing gap (`skillars-deferred-17` D6, `skillars-5-4` W9, `skillars-uat-1`): there is no frontend test suite in this repo, so `ProfileBuilderStep3.vue`'s duration select, `BookingRequestPage.vue`'s merged own-booking rows and coach-timezone week bounds, and `ParentBookingsPage.vue`'s derived read-only reschedule end are verified by code reading and a successful production build only. Reverting all three would leave the entire suite green.

- **D7 — `docker-compose.local.yml` now needs a `redis` override it did not need before.** AC6 replaced the `redis-data` named volume with a bind mount at `/opt/skillars/data/redis`, which is a production path. `docker-compose.local.yml` overrides the volume of every other stateful service but had never needed one for redis, so without the new `skillars-local-redis` override added here, running the local stack would have created `/opt/skillars/data/redis` on a developer's own machine. Fixed in this story; recorded because it is the general hazard of putting absolute host paths in the base compose file — any future service that moves onto the Volume needs the same treatment, and nothing enforces it.

## Deferred from: code review of skillars-uat-2-session-duration-and-booking-slot-integrity — Group A (2026-08-10)

Backend duration-model files only (`SessionDurationResolver`, `V93` migration, `AvailabilityService`, `BookingService`, `BookingBatchService`, `RescheduleService`, `BookingError`, `ProfileBuilderStep3Request`, `CoachPricing`, `CoachProfileService`), reviewed against AC1–AC4. Group B (backend tests) also reviewed, no additional defers there. Group D (ops/infra/docs) pending.

- **Session duration / availability windows are resolved once and not re-validated before persistence.** `[CLOSED (partially) by skillars-deferred-69 AC7: BookingBatchService.createBatch now re-fetches requiredDuration and windows fresh and re-validates every slot's duration+availability immediately before the batch/booking rows are persisted, inside the same transaction — this narrows the window from "the whole request" to "between the two resolves," it does NOT eliminate it. CoachAvailabilityWindow/CoachAvailabilityBlock carry no @Version and no locked-read method exists for them anywhere in the codebase, so a coach edit landing between the re-check and the actual commit remains unseen; full elimination would require lock support on CoachAvailabilityWindow, out of this story's scope. The separate GET-availability-calendar-vs-POST-booking staleness (AvailabilityService.getAvailabilityCalendar) is unrelated and remains fully open.]` `BookingBatchService.createBatch` resolves `requiredDuration` and fetches `windows` once before the per-slot loop and reuses both for the whole batch; the actual writes happen afterward via `TransactionTemplate`. Separately, `AvailabilityService.getAvailabilityCalendar`'s resolved `slotLength` and the later `BookingService`/`BookingBatchService` calls that independently re-resolve `requiredDuration` have no version or ETag tying a GET availability response to a subsequent POST booking — a coach changing their session length in between can turn a slot the parent just saw into a rejection. Both are the same underlying class of read-then-write staleness that already exists elsewhere in the booking module (e.g. availability windows/blocks changing between GET and POST); not newly introduced by this story and not actionable within its scope. `[CLOSED (further) by skillars-deferred-71 AC2, for single-booking creation only: CoachAvailabilityResponse now carries an availabilitySignature (a deterministic string built from the current windows + resolved session duration, no schema change), which CreateBookingRequest can optionally echo back; BookingService.createBookingRequest compares it against a freshly-recomputed signature before the existing window/duration checks and throws a dedicated BookingError.AVAILABILITY_CHANGED (distinct from the generic rejection) on mismatch, and the frontend re-fetches availability on that specific error. RescheduleService and BookingBatchService remain unwired — see this AC's own scope note for why — so the GET-vs-POST staleness gap for those two paths is not part of this closure.]` `[CLOSED (further, for BookingBatchService specifically) by skillars-deferred-72 (confirmed during skillars-deferred-81 story creation, 2026-08-28): CreateBatchRequest carries availabilitySignature, BookingBatchService.createBatch:142-147 compares it and throws AVAILABILITY_CHANGED on mismatch, and booking.store.js's submitBatch (:558) sends the value end-to-end from the frontend. This landed unannotated over a week before skillars-deferred-81's creation pass found it live in code rather than trusting this ledger entry's stale "unwired" text. RescheduleService remains genuinely out of scope per AC2's own scope note (no GET-then-POST seam exists for reschedule) — not a gap, by design.]`
- **`V93__session_duration.sql` validates its new `CHECK` constraint in the same `ALTER TABLE` as the `ADD COLUMN`,** taking an `ACCESS EXCLUSIVE` lock on `marketplace.coach_pricing` for the scan rather than splitting into `ADD CONSTRAINT ... CHECK (...) NOT VALID` followed by a separate `VALIDATE CONSTRAINT`. Low practical impact at `coach_pricing`'s expected row count (one row per coach) and consistent with this repo's other migrations, but worth a shared migration-pattern policy if a future migration targets a larger table. `[CLOSED by skillars-deferred-72 (verified already fixed): skillars-deferred-70 AC3 already split this exact constraint into V107 (DROP + re-ADD ... NOT VALID) + V108 (VALIDATE CONSTRAINT), mirroring V105/V106's precedent. Never tagged closed on this bullet at the time.]`

## Deferred from: code review of skillars-uat-2-session-duration-and-booking-slot-integrity — Group C (2026-08-11)

Frontend + i18n files only (`ProfileBuilderStep3.vue`, `BookingRequestPage.vue`, `ParentBookingsPage.vue`, four i18n bundles), reviewed against AC1/AC3/AC5.

- **`BookingRequestPage.vue`'s `OWN_BLOCKING_STATUSES` duplicates the backend's `ACTIVE_SLOT_STATUSES` with no shared source of truth.** Verified byte-for-byte identical to `BookingService.ACTIVE_SLOT_STATUSES` today, but nothing keeps the two lists in step — a future change to the backend set silently desyncs the frontend's own-booking carve-out (a slot either wrongly shows as available or stays wrongly greyed out). Not fixable within a frontend-only diff; would need the status set exposed via an API contract or generated from a shared source. `[PICKED UP by skillars-deferred-47 AC1, AC2]` `[CLOSED by skillars-deferred-71 (verified complete): BookingRequestPage.vue now fetches the active-slot-status set from the backend config endpoint rather than duplicating it — skillars-deferred-47's pickup is confirmed shipped. Graduating from PICKED UP to CLOSED.]`
- **The session-duration `q-select` (`ProfileBuilderStep3.vue`) offers only 5 discrete values (30/45/60/90/120 minutes, plus "platform default") while the backend/DB accept any integer 15–240** (`chk_coach_pricing_session_duration`, V93). A coach whose `session_duration_minutes` was set outside that set — via a direct API call rather than this dropdown — would see the select render unselected. Narrow and low-probability; needs a product decision on how to surface an out-of-list value (show the raw number? add a synthetic option?) rather than a unilateral fix. `[CLOSED by skillars-deferred-63 AC7 — product decision made: add the out-of-list value as a synthetic option (raw number as its own label) rather than rendering unselected. Still unreachable via any live UI path today (create-only screen, no coach-facing edit path) — shipped as defensive hardening]`

## Deferred from: code review of skillars-uat-2-session-duration-and-booking-slot-integrity — Group D (2026-08-11)

Ops/infra/docs files only (`ci.yml`, `docker-compose.yml`, `docker-compose.local.yml`, `provision.sh`, `deploy/traefik/README.md`, `docs/deployment/*.md`, `sprint-status.yaml`, `uat-readiness-priorities.md`), reviewed against AC6/AC7. `deferred-work.md` itself excluded (reviewed separately, see Groups A–C above).

- **If `provision.sh` is run once before the Hetzner Volume is attached, then the volume is attached and the script rerun later, the pre-volume `acme.json` (created on the root disk by section 7.5's unconditional path) is orphaned rather than migrated onto the newly-mounted volume.** The story's own migration note in `uat-deployment.md` covers the pre-story path (`/opt/skillars/traefik/acme.json` → `/opt/skillars/data/traefik/acme.json`), not this specific "provisioned without volume, then volume attached later" sequence. Narrow, low-probability ops scenario — only bites an operator who provisions before attaching the Volume.

## Deferred from: skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)

Found or deliberately left while implementing the story. The first item is an **operational finding, not a code defect, and it is the most consequential thing in this list.**

- **D3 — `CAPTURE_PENDING` has no automated exit.** A reserved row that never completes blocks the parent's cancel (AC2 returns 409 for as long as it stands), holds the coach's slot, and is refused by the sweeper. AC5 escalates it on every 15-minute sweep via `booking.payment_pending.unrecoverable{reason="CAPTURE_UNCONFIRMED"}` and Scenario 4 of `runbook.md` documents the manual resolution, but nothing times it out. **Deliberate.** Every automatic option is worse: declining could charge a parent for nothing, confirming could give away an unpaid session, and re-charging could take the money twice. Only a human can read the Stripe side. Recorded so the absence reads as a decision rather than an oversight.

- **D4 — `POST /api/bookings/{id}/cancel` has no frontend caller: a parent cannot cancel through the app at all.** `[CLOSED by skillars-deferred-69 AC3: booking.store.js gained handleCancelBooking, and ParentBookingsPage.vue gained a Cancel button (gated on the same CONFIRMED/UPCOMING status set as Request-Change) plus a confirmation dialog with a generic warning — no refund-eligibility preview endpoint was built, deliberately out of scope. No backend change needed; the endpoint was already correct and complete.]` `booking.api.js:64` exports `cancelBooking` and grepping `cancelBooking` across `src/frontend/src` returns that one line and nothing else — no page, no store, no component invokes it (`ParentBookingsPage.vue`'s only `cancel` hit is a `q-btn` closing a dialog). This is a parent-journey gap with its own design (confirmation step, refund preview showing whether the >24h rule applies, coach notification), not a wiring fix, which is why AC2 hardened the endpoint without building the UI. It also means the race AC2 closes was reachable by direct API call only — recorded on `deferred-12` D2 above as a correction to `uat-readiness-priorities.md`'s framing.

- **D7 — `PaymentWebhookIdempotencyIT` seeded no `booking.bookings` rows at all.** Found in Task 0's triage; the story's regression table predicted only that it might assert "exactly N rows". The class mocks `BookingService` specifically so transitions do not need a real booking, which meant `reserveCapture`'s locked read found nothing, returned `BOOKING_NOT_PENDING`, and left zero payment rows. Fixed by seeding a real `PAYMENT_PENDING` booking in the one test that reaches Stripe — the fixture, not the check, per `uat-2`'s recorded lesson. Recorded because it generalises: **any test that drives a settlement path now needs a real booking row**, which was not true before this story, and the other two tests in that class pass only because they never reach Stripe (full credit cover and pack-funded both skip the reservation).

- **D9 — no frontend test coverage.** Standing gap (`uat-1`, `uat-2` D6, `deferred-17` D6, `deferred-18`): there is no frontend test suite in this repo. This story touches **no** `.vue` file, so unlike its two predecessors nothing here is verified by code reading alone — recorded only to note that the gap is unchanged, not that it bit this story.

## Deferred from: code review of skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)

Adversarial code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 13 patch findings were resolved in the same pass; these 6 were deliberately left open. (`DisputeService`'s unguarded `findById` was independently raised by this review too — already tracked above as D5, not duplicated here.)

- **V94's `DROP CONSTRAINT`/`ADD CONSTRAINT` pair on `payment.booking_payments.chk_bp_status` takes an `ACCESS EXCLUSIVE` lock and revalidates the CHECK against every existing row**, blocking reads and writes to the table every settlement path writes to, for the duration. Acceptable at the current UAT-stage row count; worth an online-migration strategy (`ADD CONSTRAINT ... NOT VALID` + separate `VALIDATE CONSTRAINT`) before this table is large in production. [`src/main/resources/db/migration/V94__booking_payment_capture_pending.sql`]

- **`reserveCapture`'s `REQUIRES_NEW` + `PESSIMISTIC_WRITE` opens a second pooled connection per attempted reservation, held up to the 5s `lock.timeout` under contention**, with no discussion anywhere of connection-pool sizing. A burst of concurrent settle attempts against contended booking rows is a new resource-exhaustion vector this change introduces. Speculative and load-dependent — no load test exists either way. [`src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java:73-105`]

- **`BookingServiceTest` still constructs `BookingService` positionally**, grown by one more parameter in this diff. The project's own tracking docs already flag this constructor-positional pattern as a recurring compile-break risk across stories; this story grew it rather than fixing it. Test-hygiene debt, not a functional defect. [`src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`] `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]`

## Deferred from: code review of skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)

All 13 patch findings were applied; these are the review's own deferrals plus one item the patch round produced.

- **D11 (from the patch round, not the review) — `PaymentPendingSweeper.sweepOne`'s row lock is justified by reasoning, not by a test.** The review correctly found that `sweepOne` decided on the *absence* of a payment row and then wrote one, while `reserveCapture` concurrently decided on the absence of the same row and inserted `CAPTURE_PENDING` — so an unlocked sweeper could commit `CHARGE_FAILED` over a granted reservation (`save()` on an assigned `@Id` with no `@Version` is a `merge()`, i.e. an UPDATE, not a failing INSERT). Fixed by re-reading under `findByIdForUpdate`, the same lock `reserveCapture` takes. **The IT written to prove it was deleted for passing unchanged against the unlocked code**: both threads start on one latch, but the sweeper first reads config and runs the stranded-booking query while `reserveCapture` goes almost straight to its insert, so the reservation committed first every time and the correct outcome came from the payment-row check rather than the lock. That the six existing `PaymentPendingSweeperTest` cases all broke when the lock landed does confirm the production path changed, but a mock swap is not proof of serialisation. The lock's read-then-write window is microseconds wide and not reachable from a test at this level; proving it would need either production instrumentation (a test-only hook inside `sweepOne`) or a DB-level fault injector, both of which are their own change. Recorded rather than left implicit, because this project has now three times found a lock whose test passed without it (`deferred-13`, `deferred-15`, and this one).

- **D13 — V94's `DROP CONSTRAINT`/`ADD CONSTRAINT` pair takes an `ACCESS EXCLUSIVE` lock and revalidates the CHECK against every existing row** on what will become the busiest settlement table. Acceptable at current UAT-stage size (the table is small and the platform is not live), but revisit with an online-migration strategy — `ADD CONSTRAINT ... NOT VALID` followed by `VALIDATE CONSTRAINT` — before this table is large in production. [`V94__booking_payment_capture_pending.sql`]

- **D14 — `reserveCapture`'s `REQUIRES_NEW` + `PESSIMISTIC_WRITE` opens a second pooled connection per attempted reservation**, held up to the 5 s lock timeout under contention, with no pool-sizing analysis behind it. On the batch path that is one extra connection per credit-funded booking, taken sequentially. Load-dependent and speculative without a concurrency/load test, but it is a new resource pattern on the busiest path and should be measured before the platform carries real volume. [`BookingPaymentPersistenceService.java:73-105`]

- **D17 — `BookingServiceTest` still constructs `BookingService` positionally**, and this story grew that argument list by one more parameter (the 14th). Every story touching `BookingService`'s constructor pays a compile break here first; `uat-2` and this story both did. Switching to `@InjectMocks` — as the sibling `ExpiredPackBookingValidationTest` already does — would end it, but that changes how ~26 tests obtain their collaborators and wants its own pass. Test-hygiene debt, already flagged in prior tracking. [`BookingServiceTest.java:94-97`] `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]`

## Deferred from: skillars-uat-4-i18n-locale-and-message-resolution-integrity (2026-08-12)

- **D3 — `CoachCommandCenterPage.vue:267`'s `getDayIndex` matches a hardcoded-English `Intl.DateTimeFormat('en', {weekday:'long'})` result against a hardcoded English array (`['Monday','Tuesday',...]`) via `.indexOf`.** Deliberately left hardcoded by AC1 (see its "Do NOT touch" table) — localizing the formatter alone without also rewriting the matching array would make every non-English user's schedule silently misbucket into the wrong day column. Flagged here so whoever eventually fixes D2's hardcoded day-name arrays does not localize this formatter in isolation and introduce that regression. [`CoachCommandCenterPage.vue:266-272`]

## Deferred from: code review of skillars-uat-4-i18n-locale-and-message-resolution-integrity (2026-08-12)

- **D2 — Zero automated test coverage for any of the 10 `Intl.DateTimeFormat`/`toLocaleString` call sites this story's AC1 changed from hardcoded `'en'` to the active vue-i18n `locale.value`.** Systemic, not specific to this story: there is no frontend test suite anywhere in this repo (no `*.spec.js` outside `node_modules`, no `src/frontend/test`), a gap already recorded by prior reviews (e.g. `deferred-17` D6) and explicitly out of scope per the `uat-1`/`uat-2`/`uat-3` precedent this story's own Task 5 follows (code-reading and build/test results only, no live browser run). [8 touched `.vue`/`.js` files under AC1]

- **D3 — `de-DE` bundle ships with 55 `// TODO: native review` markers, unlike the already-shipped `fr-FR` bundle (0 such markers).** This story's AC3 makes `de-DE` reachable/selectable for the first time via `MainLayout.vue`'s language switcher; the bundle is structurally complete (same key set as `en-US`/`fr-FR`) but ~4.5% of its lines (55/1209) are still flagged as machine-translated pending native review. **Decision (Mbah, 2026-08-12): ship as-is** — some German is better than none, and this story's scope was reachability/correctness, not translation-quality review. Native review of these 55 strings is a real, open follow-up item, not yet scheduled. [`src/frontend/src/i18n/de-DE/index.js`]

## Deferred from: skillars-uat-5-player-self-booking story creation (2026-08-12)

Recorded per AC5 — items explicitly excluded from this story's scope, not fixed.

- **D1 — Session-pack purchase by a self-registered player.** `payment.session_pack_purchases` requires both `parent_id NOT NULL` and `player_id NOT NULL` (`V62__session_payment_credit_wallet.sql` + live entity `SessionPackPurchase.java`), and `SessionPackPaymentService.purchasePack` hard-requires `playerProfileRepository.findByIdAndParentId` ownership. Unlike the booking-ownership check this story widened, this one is not a simple XOR branch: the pack itself has no `user_id`-style self-ownership column at all. A self-booking player pays **per-session by card only** (this story's AC2); packs remain a separate, larger schema change (a new nullable `player_owner_id`-style column plus the same XOR-branch treatment `PlayerProfile` already has). [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`, `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql`]

- **D2 — Credit wallet (`payment.parent_credit_ledger`) has no player-self-booking equivalent.** Keyed by `parent_id` alone with no `player_id` dimension — it is a per-parent shared balance across children, a concept that does not map onto a single self-booking adult. `CreditWalletResource` stays parent-only; a self-booking player's `effectiveCreditsRemaining` correctly reads 0/none since they will never have ledger rows. No fix needed unless/until a product decision creates a player-scoped credit concept. [`src/main/java/com/softropic/skillars/platform/payment/service/CreditWalletService.java`]

- **D3 — Player self-cancel / reschedule / no-show / dispute all stay parent-only.** `[CLOSED by skillars-deferred-69 AC4: 5 backend @PreAuthorize gates widened HAS_PARENT_ROLE → HAS_PARENT_OR_PLAYER_ROLE (CancellationResource POST /{id}/cancel and POST /{id}/no-show-coach, RescheduleResource POST /{id}/reschedule, SessionCompletionResource PUT /{id}/confirm-completion, ScheduleResource GET /parents/me/schedule — the last one needed a real fix, not just a gate widen: getParentPlayerSchedule's ownership check assumed every caller has a parent and was unreachable for a self-booking player until corrected to branch on player.getParentId() == null the same way createBookingRequest already does), plus the 2 frontend v-if gates on ParentBookingsPage.vue's Request-Change and Confirm-Completion buttons widened to authStore.isParent || authStore.isPlayer. no-show-player and coach-cancel correctly stay coach-only. ActorRole was NOT given a PLAYER value, as this item anticipated — a self-booking player's actions are still recorded as ActorRole.PARENT, matching how parent_id already represents them. Dispute-raising's PLAYER fallback (DisputeResource.resolveCurrentRole) was independently re-verified during AC4 and confirmed already correct, no change needed.]` `CancellationResource.java` (`POST /{id}/cancel`) is unreachable by any frontend caller today, parent or player — not a new regression (per `uat-readiness-priorities.md` P1 #2). `RescheduleResource.java` (`POST /{id}/reschedule`, "Request Change") and `SessionCompletionResource.java` (`PUT /{id}/confirm-completion`, "Confirm Completion") ARE live buttons on `ParentBookingsPage.vue`, now reused under the dual-role bookings-list route this story's AC4 added — both are guarded behind `v-if="authStore.isParent"` so a player caller sees a read-only list rather than a button that 403s on click, but the backend itself was deliberately not widened. `ActorRole` (`{COACH, PARENT, SYSTEM}`) was deliberately not given a `PLAYER` value — nothing in scope needs the booking state machine to distinguish a self-booking player from a parent, since the opaque-id design (this story's central decision) already routes their `userId` through the existing `parent_id`-keyed checks. [`src/main/java/com/softropic/skillars/platform/booking/api/CancellationResource.java`, `RescheduleResource.java`, `SessionCompletionResource.java`, `src/frontend/src/pages/parent/ParentBookingsPage.vue`]

## Deferred from: code review of skillars-uat-5-player-self-booking (2026-08-12)

- **D2 — Client-side `playerId` can be overridden via `?playerId=` query param before the server-side ownership check rejects it.** `BookingRequestPage.vue`'s `playerId` computed checks `route.query.playerId` before falling back to the resolved self-player id — a player-authenticated session can construct a URL carrying someone else's `playerId`, filling out the whole form before the backend's XOR ownership check (`skillars-uat-5` AC1) rejects it server-side with no friendly client-side error. Pre-existing pattern already used identically by the parent flow (route-query-first, store-fallback), not a new gap this story introduced; the security boundary is enforced correctly server-side either way. [`src/frontend/src/pages/parent/BookingRequestPage.vue:368-378`]

## Deferred from: skillars-uat-6-coach-subscription-and-volume-backup (2026-08-13)

Recorded per AC4 — items explicitly excluded from this story's coach-subscription scope, not fixed.

- **Ops note** — `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` removed from the server's `.env.example`/`secrets-reference.md` by this story's AC8 (see below): any **already-provisioned** node's live `/opt/skillars/.env` will still carry the stale values. Harmless — nothing on the server reads them anymore after AC5/AC6 — but worth a one-line ops note for whoever next touches a live `.env` file, not a script change.

## Deferred from: code review of skillars-uat-6-coach-subscription-and-volume-backup (2026-08-13)

- **A single account holding both a parent/player role and the coach role shares one `payment.stripe_customers` row keyed only on `userId`.** A card saved under one role is silently reusable to fund a subscription under the other, with no per-role consent. This is the deliberate opaque-id design this story's own Dev Notes call out explicitly ("do not add a `payer_type` column") — not new to this story, just extended to a third role. [`src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java:143`] `[DISMISSED — deliberate/pre-existing, not fixed by skillars-deferred-20: deliberate opaque-id design decision, not a defect]`
- **`volume-backup.sh` has no disk-space precheck before writing a full tar archive to `/tmp`.** Identical gap exists in `pg-backup.sh`, the exact script this mirrors. [`deploy/backup/volume-backup.sh`] `[DISMISSED — deliberate/pre-existing, not fixed by skillars-deferred-20: identical gap already exists in pg-backup.sh, the script this mirrors]`
- **Removing the `attachPaymentMethod` call in `subscribeCoach` removes a redundant safety net against a saved-but-since-detached Stripe payment method.** This is the exact pattern `subscribePlayer` already ships in production; the spec explicitly directs mirroring it rather than inventing new handling here. [`src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:102`] `[DISMISSED — deliberate/pre-existing, not fixed by skillars-deferred-20: mirrors subscribePlayer's already-shipped pattern per spec direction]`
- **`.env.example`/`secrets-reference.md` remove `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` from the server template, but `apply-firewall.sh` still reads `HCLOUD_TOKEN` locally on the operator's machine.** The spec explicitly scopes this out as a separate, untouched local-only concern. [`.env.example`] `[DISMISSED — deliberate/pre-existing, not fixed by skillars-deferred-20: explicitly scoped out as a separate, untouched local-operator-machine concern]`
- **`prune_volume_backups()` trusts `aws s3api`'s output shape with no independent validation.** The removed `prune_snapshots()` had a `jq -e 'has("images")'` check; identical gap exists in `prune_s3_dumps()`, the exact function this mirrors. [`deploy/backup/prune-backups.sh`] `[DISMISSED — deliberate/pre-existing, not fixed by skillars-deferred-20: identical gap already exists in prune_s3_dumps(), the function this mirrors]`

## Deferred from: code review of skillars-deferred-21-silent-failure-logging-dead-code-backup-guard-hardening (2026-08-14)

- **`ConfigService.getBoolean` fails open (returns `false`) for a misconfigured-but-present value guarding security-sensitive gates** (e.g. `AuthService.java:99`'s `security.registration.phone-otp-required`) — now logged at WARN but not alertable. Pre-existing behavior, unchanged by this story's diff (only the log line is new). [`src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java:106-125`] `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]`

## Deferred from: code review of skillars-deferred-22-messaging-role-guard-payment-idempotency-and-resource-integrity-fixes (2026-08-14)

- **`initiateUpload`'s new orphaned-reservation release doesn't confirm the video row itself still exists before publishing `VideoPhysicalDeletionEvent`.** Pre-existing pattern — mirrors `deleteVideo`'s own unmodified check-and-publish shape exactly, which has always published on `!existsByVideoId(...)` alone. [`src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java:92-93`, `:118-119`]
- **`initiateUpload`'s new check-then-act on `existsByVideoId` opens the same TOCTOU window as `deleteVideo`'s already-deferred `Def14` race** — two concurrent calls can both observe `existsByVideoId()==false` and double-publish `VideoPhysicalDeletionEvent` for the same video. Structurally identical to Def14; this story's own Dev Notes explicitly scoped Def14 as untouched, and the new site inherits the same accepted risk by mirroring its pattern. [`src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java:92-93`] `[CLOSED (same-drill variant only) by skillars-deferred-75 AC5: both initiateUpload and deleteVideo now take the same per-Drill-row PessimisticWrite lock around their entire check-then-act sequence, so any combination of the two methods racing on the SAME drillId is serialized — DrillUploadServiceConcurrencyIT proves this for deleteVideo-vs-deleteVideo; the identical lock now also covers initiateUpload-vs-initiateUpload and initiateUpload-vs-deleteVideo on one drill. The cross-drill variant (two different drillIds whose drill_video_refs rows share one videoId, e.g. via DrillLibraryService.cloneDrill) remains open — two different drill rows acquire two different locks and do not serialize against each other.]`
- **`SessionPlanService.buildResponse`'s new response-map derivation relies on `session.getBlocks()` matching the pre-save `blocks` list used to build it.** Pre-existing coupling — the pre-diff code had the same pre-save-metaMap/post-save-`session.getBlocks()` relationship; this story's refactor only changed how the drill-lookup maps are built, not this dependency. [`src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java:186-234`]

## Deferred from: code review of skillars-deferred-23-flaky-perf-test-dead-code-and-ops-hygiene-fixes (2026-08-14)

- **AC5's `concurrency` group doesn't guarantee the newest-pushed commit wins `:latest`.** `build-and-push` still declares `needs: test`, so concurrency-slot readiness tracks `test`-job duration, not push order — a slower older push's job can still complete its `build-and-push` after a faster newer push's, overwriting `:latest` with the older commit. The `concurrency` block does prevent two `build-and-push` runs from interleaving mid-upload, a real improvement; it just doesn't close the ordering race the item was originally filed against. Already surfaced and accepted as a deliberate scope decision in this story's own AC5 and the `deferred-work.md` entry it closed (line 1285) — recorded here only per this review's standard defer-logging convention, not a new finding. A stronger fix (`build-and-push` checking `git merge-base --is-ancestor` against the currently-published `:latest` digest before overwriting) remains out of scope. [`.github/workflows/ci.yml:49-53`]
- **`PlaybackServiceIT.authorizePlayback_performance_p99Under200ms` still asserts a hard wall-clock latency bound inside a correctness-gating integration test.** AC1 fixed the two proximate bugs (wrong percentile index, no JIT/connection-pool warmup) that made the assertion flaky, but the underlying design choice — gating build correctness on a millisecond latency threshold in CI, where JIT, GC, and Testcontainers jitter are inherently variable — remains structurally fragile. Pre-existing pattern, not introduced by this story, and fixing it (e.g. moving to a dedicated non-gating perf-tracking job, or asserting a much looser bound) is its own design decision outside AC1's scope. [`src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java:105-125`]

## Deferred from: code review of skillars-deferred-24-dead-subscription-column-stripe-metadata-and-backup-guard-fixes (2026-08-15)

- **The new `GUARD_PATH` existence/readability guard block is duplicated verbatim across all 5 caller scripts** rather than factored into one shared function. Spec-directed (AC4's code block explicitly prescribes this exact per-caller shape); a DRY refactor would need its own sign-off, not a silent deviation from the story's prescribed fix. [`deploy/backup/pg-backup.sh`, `volume-backup.sh`, `restore-from-dump.sh`, `restore-from-volume-backup.sh`, `prune-backups.sh`]

## Deferred from: skillars-deferred-26-defensive-guards-input-hardening-and-test-coverage-fixes story creation (2026-08-15)

- **D1 — `DrillMetadata.repDensity` cannot represent "coach never set this" at all — it is a Java primitive `int`, not `Integer`.** Found during this story's AC4 spec audit (adversarial review of the story's own AC text, pre-implementation): a missing key in the incoming JSON payload deserializes silently to `0` via Jackson, and an explicit JSON `null` would throw a deserialization exception rather than pass through — so a `repDensity != null` guard anywhere downstream (frontend or backend) can never observe the "unset" case as `null`; it is indistinguishable from a legitimately-zero drill today and will remain so under the current contract. AC4 of this story added a frontend-only defensive guard (protects against `undefined`/`null` arriving from a stale cache, a manually-edited dev fixture, or a future API contract change) but explicitly could not close this gap — doing so needs a backend change: make `repDensity` a nullable `Integer` (propagating through `DrillMetadata`, its JSONB Hibernate mapping, and every backend site that reads it arithmetically) or add an explicit "no density data" signal, plus a decision on whether that is a real product need (do coaches uploading custom drills currently have any path that leaves `repDensity` unset, or does upload validation already require it?). [`src/main/java/com/softropic/skillars/platform/session/contract/DrillMetadata.java:12`, `src/frontend/src/components/session/DrillDetailPanel.vue`] **[AUDIT 2026-08-24: skillars-deferred-63 story creation investigated this live.** No live path constructs a `Drill` with coach-submitted metadata today — `grep -rn "new Drill(\|Drill.builder()\|new DrillMetadata(" src/main/java` finds exactly one non-test construction site, `DrillLibraryService.java:129`'s `clone.setMetadata(source.getMetadata())`, which copies an already-persisted drill's metadata rather than deserializing a fresh payload; `DrillUploadService`/`DrillUploadResource` (the only "drill upload" surface) handle the drill video file only — `DrillUploadInitiateRequest` has no `DrillMetadata` field at all. Every `Drill.metadata` is populated exclusively by migration/seed data under full app-team control. The "coach never set repDensity" scenario has no reachable trigger today — not picked up as an AC by skillars-deferred-63. Re-open if a future story adds a real coach-facing metadata-submitting endpoint.]**

## Deferred from: skillars-deferred-28-booking-error-messaging-subscription-coverage-and-media-timestamp-test story creation (2026-08-17)

- **Product question: should a parent cancelling a booking after its session start time has already passed settle as a coach no-show instead of an ordinary `CANCEL_PARENT`?** `BookingService.cancelBookingAsParent` has no guard against this — it checks ownership and refuses only a `PAYMENT_PENDING`+`CAPTURE_PENDING` booking, otherwise transitions unconditionally, which is why `applyRefundLogic`'s `hoursUntilSession` can go negative and fall through to `"NONE"` refund eligibility (documented, not changed, by `skillars-deferred-28` AC4). If the answer is yes, this needs a different `BookingEvent` (`NO_SHOW_COACH`) with different refund semantics, decided by product, not a mechanical fix. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:611-661,757-772`] **Still open — not resolved by `skillars-deferred-63` AC4** (story-review, 2026-08-24: corrects this item's own prior mis-annotation). AC4 added a time guard to `recordNoShowCoach` — a parent explicitly *reporting* a no-show — which is a different, already-fixed gap (see the new item filed below); `cancelBookingAsParent`'s unconditional-transition behavior this item is actually about is completely untouched by that story. The late-`CANCEL_PARENT`-auto-becomes-no-show product question remains undecided. `[CLOSED by skillars-deferred-64 AC4: refund-only, confirmed by the project owner during story-review 2026-08-25 — a late cancel does NOT convert into a no-show and does NOT issue a coach strike, only widens refund eligibility]`

## Deferred from: code review of skillars-deferred-30-error-toast-mapping-and-repository-boundary-test-coverage-fixes (2026-08-18)

- **[OWNED BY skillars-deferred-31 AC7] `CoachBookingRequestsPage.vue`'s `handleAccept` calls its post-failure refresh unguarded — a rejecting refresh throws an unhandled promise rejection out of the catch block.** Found by a second, independent code review pass (2026-08-18): `await bookingStore.loadCoachBookingRequests()` at `CoachBookingRequestsPage.vue:164` sits inside `handleAccept`'s `catch` block with no try/catch of its own. If the accept call fails AND the immediately-following refresh call also fails (e.g. a transient network blip right after a real rejection), the refresh's rejection propagates unhandled rather than being absorbed — the coach's toast for the original failure still fires (it's already been queued before the `await`), but the unhandled rejection is a latent robustness gap a browser console/error-monitoring integration would surface as noise at best, an uncaught exception at worst. Pre-existing pattern, not introduced by `skillars-deferred-30`'s edits (which only added `errorKey` branching before this line). A one-line `.catch(() => {})` closes it. [`src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:164`] `[WITHDRAWN by skillars-deferred-31 AC7 — the described defect cannot occur]` The item rests on the premise that `loadCoachBookingRequests()` can reject. It cannot: `booking.store.js:302-314` wraps the whole call in `try { … } catch (e) { coachRequestsError.value = e }` with **no rethrow**, so the `await` at `CoachBookingRequestsPage.vue:164` always resolves and no unhandled rejection is possible. The prescribed `.catch(() => {})` would be dead code, and a future reader would have to re-derive why it is there. The identical unguarded pattern in `handleDecline` (`:176`) is harmless for exactly the same reason and was likewise not "fixed". This is the **same false premise** the `skillars-deferred-30` code review withdrew a sibling item for on the same day, reached independently by a different review pass hours later — the store's swallow-and-never-rethrow contract is evidently not obvious from the call sites, so AC7 also added a CONTRACT comment above `loadCoachBookingRequests`/`loadCoachSchedule` in `booking.store.js` stating that neither loader ever rethrows and callers therefore need no guard. The live residual these two withdrawn items were both circling — a silent failed refresh — is closed by AC1.

## Deferred from: code review of skillars-deferred-33-messaging-lock-ordering-booking-status-mapping-and-german-video-i18n-gap (2026-08-18)

- **`V97__drop_booking_refund_eligibility_and_amount.sql` drops two columns with a bare, uncommented `DROP COLUMN` pair under an `ACCESS EXCLUSIVE` lock, and its "nothing reads these columns" justification was verified only via grep of `src/main/java`**, not any SQL views/reporting/export tooling outside the Java codebase. Deferred rather than patched: `V96` (this story's own cited precedent) has identical zero commentary, and the `ACCESS EXCLUSIVE`-lock-class concern is already tracked in this ledger against `V94` as an accepted, not-yet-actionable gap at current table size. [`src/main/resources/db/migration/V97__drop_booking_refund_eligibility_and_amount.sql`]
- **Dropping `booking.bookings.refund_eligibility`/`refund_amount` in the same change as removing the fields from `Booking.java` carries the standard rolling-deploy column-drop ordering risk** — an old-code instance still mapping the dropped columns would fail on INSERT/UPDATE if it ran concurrently against the migrated schema. Same risk class this project already accepts for its current single-instance Docker Compose deployment model (matches this ledger's existing `AdminAlertType` rolling-deploy precedent); not reachable today. [`src/main/resources/db/migration/V97__drop_booking_refund_eligibility_and_amount.sql`, `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java:67-73`]

## Deferred from: code review of skillars-deferred-37-batch-accept-result-map-pruning-and-rebuild-cost-bound (2026-08-19)

- **`batchAcceptResultsByBatch` pruning (`skillars-deferred-37` AC1) only runs on `loadCoachBookingRequests`'s success path, per AC1's own explicit requirement mirroring the function's existing stale-on-failure CONTRACT — so a streak of failed refreshes lets the map keep growing unboundedly for as long as the failures persist.** The exact growth this story exists to bound is not airtight under a specific real-world condition (a flaky network/backend). Spec-intentional, not an oversight in the diff; low priority, consistent with this story's own documented tradeoffs. [`src/frontend/src/stores/booking.store.js:321-353`]

## Deferred from: code review of skillars-deferred-38-coach-refresh-request-sequencing-guard (2026-08-19)

- **No automated test coverage for `loadCoachBookingRequests()`'s concurrency/request-sequencing guard.** Standing repo-wide gap — no frontend test harness exists for `booking.store.js` (same accepted gap `skillars-deferred-35`/`36`/`37` recorded). [`src/frontend/src/stores/booking.store.js:326-371`]

## Deferred from: code review of skillars-deferred-40-coach-action-timeout-hardening-radar-confidence-accuracy-and-video-bandwidth-tracking (2026-08-20)

- **Bandwidth is charged per `authorizePlayback` call, not per unique viewing session.** Every
  re-authorization of the same video (token refresh, page reload, retry, a second concurrent viewer)
  re-charges the owner's bandwidth counter the video's full `storageBytes` again, with no dedup window —
  can overcount real usage well beyond the single-charge-per-view approximation AC4's Dev Notes describe.
  **Deferred, needs a full design review before fixing:** `bandwidth_used_bytes` feeds an enforced
  bandwidth **quota**, not just a reporting number, so overcounting/undercounting has real product
  consequences. Whoever picks this up must check what the spec actually wants, evaluate each candidate
  dedup rule (per playback token? per viewer+video+time-bucket?) against spec intent, weigh its resistance
  to gaming (a viewer deliberately re-triggering re-authorization to inflate or evade the owner's usage),
  and weigh the tradeoffs each option brings — not be patched ad hoc as part of a review.
  [`src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java:111-118`] `[CLOSED by
  skillars-deferred-63 AC3 — product decision made: dedup per viewer+video+time-bucket, reusing the
  existing PlaybackToken table's TTL. Closes the sequential re-authorization case (same viewer+video
  within an active token's lifetime no longer re-charges). The check-then-charge sequence is not locked,
  so a genuinely concurrent race can still double-charge — accepted as a known, low-stakes gap rather than
  fixed; see the new item filed below]`
- **The new `findDistinctCoachCountsByPlayerAndSkills` query, run alongside the existing `findAggregatesByPlayerAndSkills` query inside the same `@Async`/`AFTER_COMMIT` listener, marginally widens the already-documented DEF2-section DEF3 concurrent-recalculation race.** Two simultaneous submissions for the same player can now each read two independent, non-atomic snapshots before either upserts, instead of one. DEF3 already accepts this general shape as a "theoretical low-probability issue" that self-corrects on the next submission — this diff widens, but does not introduce, that accepted risk. [`src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java:onRadarEntrySubmitted`]
- **Migration `V98`'s backfill is an unbatched, unchunked full-table `UPDATE` joined against `radar_assessment_entries`, grouped by `(player_id, skill_code)`.** Same scaling shape as `Def10`'s already-accepted "full-table lock risk" concern for `video_quotas`, applied here to `player_radar_composites`. Not blocking at current expected table size; worth tracking if the table grows large enough for migration-time locking to matter. [`src/main/resources/db/migration/V98__player_radar_composites_distinct_coach_count.sql`]

## Deferred from: code review of skillars-deferred-42-otp-secure-random-reuse-session-pack-dead-query-and-duplicate-i18n-key-cleanup (2026-08-20)

- **`PlayerRegistrationService.generateOtp()`'s `SecureRandom` reuse fix has zero automated test coverage anywhere in this repo.** `grep -rln "PlayerRegistrationService" src/test` returns zero hits — no integration or unit test of any kind references this service, unlike its `CoachRegistrationService`/`ParentRegistrationService` siblings, which are covered by `CoachRegistrationResourceIT`/`ParentRegistrationResourceIT`. Pre-existing gap, not introduced by this diff (the change is a mechanical, behavior-preserving one-liner identical to the other two files, verified only by code-reading); explicitly flagged and accepted by the story's own Task 1.3 and Dev Agent Record. Standing candidate for whenever backend test coverage for this service is added. [`src/main/java/com/softropic/skillars/platform/security/service/PlayerRegistrationService.java:generateOtp`] `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]`

## Deferred from: code review of skillars-deferred-43-player-registration-otp-coverage-and-self-profile-fetch-caching (2026-08-20)

- **No automated test coverage added for `playerStore.js`'s new `fetchSelfPlayerId()`/`resetSelfPlayerId()` caching logic, or for `MainLayout.vue`'s new `resetSelfPlayerId()` call in `handleLogout()`.** Pre-existing gap — matches this repo's standing, repeatedly-accepted absence of frontend test infrastructure (the same reasoning `skillars-deferred-35`/`36`/`37`/`38` have left in place for `booking.store.js`). Ships with zero coverage despite this diff's own stated framing of the cache carrying a cross-account booking-misattribution risk. [`src/frontend/src/stores/playerStore.js:24-33`, `src/frontend/src/layouts/MainLayout.vue:301`]
- **`PlayerHomeRedirectPage.vue`'s bare `catch` treats any error — a 500, a network failure, not just a 404 "no profile yet"** — as reason to silently redirect to `/player/profile-builder`. Pre-existing behavior, not introduced by this diff (the `try`/`catch`/redirect shape was explicitly left untouched per this story's own AC2 instruction, only the fetch call inside it was swapped). A genuine backend/network failure is indistinguishable from "player hasn't finished onboarding" from the player's point of view. [`src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue:19-22`] `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]`

## Deferred from: code review of skillars-deferred-44-video-approval-observability-granularity-and-player-redirect-error-differentiation (2026-08-20)

- **`playerStore.fetchSelfPlayerId()` can resolve successfully with a null/undefined id, producing a broken `/player/locker-room/undefined` navigation.** `playerStore.js:39`'s `fetchSelfPlayerId()` returns `profile?.id` with no null-check on the success path — if the API responds 200 with a profile whose `id` is null/undefined, no error is thrown, so none of the three call sites' `catch` blocks fire. In `PlayerHomeRedirectPage.vue` specifically, this means the success path (`router.replace(\`/player/locker-room/${id}\`)`) fires with a literal `"undefined"` in the URL. Pre-existing, unchanged by `skillars-deferred-44`'s own diff (identical behavior before and after — the diff only changed try/catch scoping and error differentiation, not the store's resolve contract), and it's a property of the shared store contract used by all three `fetchSelfPlayerId()` call sites (`PlayerHomeRedirectPage.vue`, `CoachPublicProfilePage.vue`, `BookingRequestPage.vue`), not something a narrowly-scoped single-page AC should fix unilaterally. Found by this story's own code review (Blind Hunter + Edge Case Hunter, independently converging on the same gap). [`src/frontend/src/stores/playerStore.js:39`, `src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue:32`] `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]`

## Deferred from: code review of skillars-deferred-45-self-player-id-resolution-guard-and-drill-library-request-sequencing (2026-08-20)

- **`resetSelfPlayerId()` doesn't clear the in-flight `selfPlayerIdRequest` dedup cache, so a request from a superseded generation can still settle for a new caller.** `resetSelfPlayerId()` (`playerStore.js:48-51`) increments `selfPlayerIdGeneration` and clears `selfPlayerId.value`, but leaves `selfPlayerIdRequest` untouched. If a caller invokes `fetchSelfPlayerId()` while a previous generation's `getMyProfile()` call is still in flight (e.g. logout/relogin racing a slow fetch), the `if (!selfPlayerIdRequest)` guard (`:28`) reuses the stale in-flight promise instead of starting a fresh request for the new generation. Two concrete consequences, both found independently by this story's own code review (Edge Case Hunter, converging with Blind Hunter on the same root cause): (1) if the stale response resolves with a *valid* id, the existing generation check only gates the cache write (`:36-38`), not the return value — a caller in the new generation receives and can act on the *previous* player's id; (2) if the stale response resolves with no id, `skillars-deferred-45` AC1's new throw fires unconditionally (not gated on generation match), rejecting the shared promise for the new generation's caller with an error that has nothing to do with their own request. Pre-existing architecture gap in the dedup/generation-guard interaction — the return-value half of case (1) exists unchanged before and after this story's diff, and case (2) is a new expression of the same root cause rather than a new bug this diff introduces. Needs a design decision (e.g. also clearing `selfPlayerIdRequest` in `resetSelfPlayerId()`, or gating the throw/return on `requestGeneration === selfPlayerIdGeneration` the same way the cache write already is) before a fix is unambiguous. [`src/frontend/src/stores/playerStore.js:11,26-30,48-51`] `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]`

## Deferred from: code review of skillars-deferred-49-reschedule-and-duplicate-current-availability-window-enforcement (2026-08-21)

- **Validation logic (fetch windows → call `isSlotWithinAvailabilityWindow` → throw `SLOT_OUTSIDE_AVAILABILITY`) is duplicated near-verbatim across three call sites** (`RescheduleService.requestReschedule`, `RescheduleService.acceptReschedule`, `BookingDuplicationService.duplicateNextWeek`) instead of being extracted into a shared helper. Matches this project's own established anti-abstraction convention for blocks this small (see `skillars-deferred-48`'s code review, which dismissed an identical DRY nit against 3 near-identical guard blocks) — not fixed here, worth revisiting if a fourth caller ever appears. [`src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`, `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java`]
- **`BookingDuplicationService.duplicateNextWeek` computes `newStart`/`newEnd` as a fixed 168-hour `Instant` offset from the original booking's times; a DST transition between the original session and 7 days later can shift the duplicated slot's local wall-clock time relative to the original.** Pre-existing behavior unrelated to this story — `Instant.plus(7, DAYS)` has always been calendar-agnostic here, so a duplicate-next-week across a DST boundary has always produced a session at a shifted local hour. This story's new availability-window check (AC2) only adds a new, non-silent failure mode on top of that pre-existing quirk (an occasional clean rejection near DST boundaries) rather than introducing the shift itself. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java:56-73`] `[DECIDED 2026-08-25: accepted as a rare, low-impact edge case; no fix planned]`
- **`BookingService.isSlotWithinAvailabilityWindow` anchors both window boundaries to the proposed/accepted slot's *start* calendar date, so it can never match a coach's own overnight availability window (e.g. Mon 22:00–Tue 02:00) or a session that itself crosses midnight.** Pre-existing limitation of this shared helper, inherited unchanged from its original caller (`BookingService.createBookingRequest`) and now also reachable via two more callers this story adds (`RescheduleService`'s request- and accept-time checks, `BookingDuplicationService`). Out of scope to fix inside a story whose own Dev Notes explicitly direct reusing this helper as-is rather than modifying it. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:827-854`] `[DECIDED 2026-08-25: overnight windows remain unsupported; no fix planned]`

## Deferred from: skillars-deferred-52 story creation (2026-08-21)

Written while scoping `skillars-deferred-52`, which re-mined an older (2026-06-22 through 2026-06-30),
never-revisited section of this ledger after confirming the more recently active section
(post-`skillars-deferred-34`) is already thin per `skillars-deferred-49`/`-50`'s own creation notes. Two
items came from that older section (Def24 under `## Deferred from: code review of skillars-6-2 pass 5
(2026-06-22)`, closed by this story's AC1; D2 under `## Deferred from: code review of
skillars-10-4-gdpr-data-tools-account-deletion (2026-06-30)`, closed by AC3) — both re-tagged in place
above rather than duplicated here. This section holds only what the story-creation pass newly found:

- **Re-scoped from RW3 (`## Deferred from: post-implementation review of skillars-6-3 (2026-06-22)`):
  once state and quota release are split into separate transactions (as Def24/RW3 both asked for, and as
  AC1/AC2 now do for `VideoService.failTranscoding`/`AdminVideoService.deleteVideo`, and as an
  unannotated earlier change already did for `WebhookEventProcessorScheduler`'s `encoding.failed`/SCANNING
  branch), a release failure can no longer corrupt the state transition — but nothing retries the release
  call itself if it throws.** Four call sites now share this shape: the three above, plus
  `UploadSessionExpiryScheduler.processExpired()`, which is the only one of the four with any mitigation
  — it wraps its release call in `try { ... } catch (Exception e) { log.warn(...); continue; }`, relying
  on its own `@Scheduled` re-run to retry next cycle. The other three have no equivalent retry. Whether
  they need one is a real, undecided design question, not a mechanical fix: `QuotaService.release()`
  (`:122-137`) is idempotent (a repeat call on an already-`RELEASED`/`COMMITTED` reservation is a
  documented no-op), which suggests the *existing* webhook max-attempts/backoff machinery
  (`WebhookEventProcessorScheduler.handleFailure`) and the video-failure path's own retry surface (if
  any — not traced by this story) might already make a bare retry safe to add without new
  de-duplication logic — but whether either of those two call sites is actually re-driven by anything
  after a failure, and whether `AdminVideoService.deleteVideo` (an admin-initiated, synchronous call with
  no scheduler behind it at all) should instead surface the release failure to the caller rather than
  silently swallow it, both need a decision before a fix is written. [`src/main/java/com/softropic/skillars/platform/video/service/VideoService.java:failTranscoding`,
  `src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java:deleteVideo`,
  `src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java:171-236`,
  `src/main/java/com/softropic/skillars/platform/video/service/UploadSessionExpiryScheduler.java:40-53`] `[DECIDED 2026-08-28 (skillars-deferred-81 story creation): leave as-is, no automatic retry infrastructure. All four call sites already fail gracefully with logging (AdminVideoService.deleteVideo's own comment documents manual retry-by-recall; the other three rely on their own scheduled re-run or accept the swallow); no live incident has been reported. Building retry infra now would be speculative engineering against a working, if imperfect, accepted pattern.]`

## Deferred from: code review of skillars-deferred-53-concurrency-race-test-determinism-and-hardcoded-currency-configuration (2026-08-21)

- No format validation exists on the `platform.payment.currency` config value (case-sensitivity, ISO 4217 well-formedness) before it reaches Stripe's API — a stray admin typo (e.g. uppercase `EUR`, trailing whitespace) fails only at Stripe's own API boundary, with no earlier signal. Matches this project's existing convention of validating `platform_config` values downstream in each consumer rather than at read time (e.g. `commissionRate`'s `new BigDecimal(...)` has the same unguarded-parse risk). [`src/main/resources/db/migration/V99__payment_currency_config.sql`]
- `main.platform_config.id` remains a hand-assigned `PRIMARY KEY` with no sequence/identity column, requiring every migration that seeds this table (including `V99`) to manually track and document the next free id — a systemic schema design decision pre-dating this story (established at `V20__platform_config.sql`), out of scope for a bundled small-fix story to redesign. [`src/main/resources/db/migration/V20__platform_config.sql`, `src/main/resources/db/migration/V99__payment_currency_config.sql`]

## Deferred from: code review of skillars-deferred-56-drill-upload-error-code-assertions-and-pack-deduction-exception-safety (2026-08-22)

- `handlePackBasedBooking`'s catch clause widening from `PaymentGatewayException` to bare `RuntimeException`
  (rather than a narrower type) will also silently absorb unrelated programming bugs from `deductSession` (NPE,
  `IllegalStateException`, etc.), funneling them into the same "expected business failure" `persistPaymentFailure`
  + `log.error` path already used for pack-exhausted/not-found — collapsing the distinction between an expected
  business failure and an unexpected system defect into one code path and one log signature, which could make
  future regressions harder to triage from logs/alerts alone. Deferred: pre-existing design trade-off the
  story's own Dev Notes already explicitly reasoned through and defended (narrowest common supertype covering
  both known throw sites and any future unchecked throw, deliberately excluding `Error`); revisit only if a
  future pass needs finer-grained handling of this call's failure categories.
  [`src/main/java/com/softropic/skillars/platform/payment/service/PaymentLifecycleService.java:167`]
- `sprint-status.yaml`'s `last_updated` field has grown into a single, unbounded YAML comment line spanning the
  cumulative history of 56+ stories — effectively unreviewable in normal diff/PR tooling and a guaranteed
  merge-conflict/diff-noise hotspot on every future story. Deferred: pre-existing repo-wide bookkeeping
  convention predating this story by dozens of prior stories, not something this one story should unilaterally
  restructure. [`_bmad-output/implementation-artifacts/sprint-status.yaml`]

## Deferred from: code review of skillars-deferred-57-gdpr-report-file-cleanup-report-generation-rate-limiting-and-stripe-customer-id-format-guard (2026-08-22)

- Rate-limit bucket for the new `report_generate` key is IP-keyed, not per-coach — coaches sharing an
  office/NAT IP share one 10/minute bucket, and any caller whose IP lookup fails collapses onto a shared
  `"report_generate:unknown"` bucket. Deferred: inherited from the established `@RateLimited` IP-keying
  mechanism already in use at 6 other call sites (`CoachRegistrationService`,
  `PlayerRegistrationService`, `ParentRegistrationService`); not introduced by this story, and changing
  the keying strategy is a shared-aspect change with a wider blast radius than one call site.
  [`src/main/java/com/softropic/skillars/infrastructure/security/RateLimitingAspect.java`]
- AC1's new S3-delete loop in `GdprErasureService.deletePlayerDevelopmentData` runs unbounded, sequential,
  blocking `fileStorageService.deleteRawBytes(...)` calls inside `erase()`'s `REQUIRES_NEW` transaction —
  one S3 round-trip per performance report, no cap, no batching, no async offload. For any player/parent
  with a large report history this extends the held DB connection's lifetime proportionally. Deferred:
  fixing this properly needs batching and/or moving the S3 cleanup outside the transactional boundary
  (e.g. an async post-commit step), which is new infrastructure beyond this story's bounded-fix scope, not
  a mechanical change; today's report volume per player is small in practice, so this is a scaling risk
  rather than a live bug. Revisit if report-history size per player grows materially.
  [`src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`]
- AC3's `V101__stripe_customer_id_format_guard_validate.sql` (`VALIDATE CONSTRAINT`) has no remediation
  path for a pre-existing non-conforming row: if any live `payment.stripe_customers` row ever failed the
  `cus_%` check, `V101` (and the deploy) would hard-fail with no backfill/quarantine step in either `V100`
  or `V101`. Story-review Finding 1 addressed this migration's lock-duration safety (the `NOT VALID`/
  `VALIDATE CONSTRAINT` split) but not this separate data-conformance risk — every current write site and
  test fixture was verified `cus_`-prefixed (see AC3's own rationale), but the table's actual live data was
  never queried, matching the same "no production DB access at authoring time" limitation Finding 1 already
  named. Deferred: no practical fix available without production DB access to verify actual conformance;
  if `V101` ever fails a real deploy, the remediation is a one-off backfill/quarantine migration for the
  specific non-conforming rows found, not a change to this story's migrations.
  [`src/main/resources/db/migration/V101__stripe_customer_id_format_guard_validate.sql`]

## Deferred from: code review of skillars-deferred-58-coach-availability-write-lock-consistency-and-pack-deduction-failure-record-transactional-safety (2026-08-24)

- `duplicateNextWeek` never re-checks `CoachProfileStatus.SUSPENDED` after its new
  `findByIdForUpdate`+`entityManager.refresh(..., PESSIMISTIC_WRITE)` lock, unlike
  `RescheduleService.acceptReschedule`'s identical lock pattern, which does re-check `SUSPENDED` against
  the freshly-locked row. Deferred: pre-existing gap, not introduced by this diff — `duplicateNextWeek`
  never checked `SUSPENDED` before this change either, and AC2 explicitly scoped out any new business rule
  ("AC2 does not add any new business rule — it only adds serialization"). A future story adding this check
  would need a product decision on whether duplicating a completed booking for a since-suspended coach
  should be blocked. `[CLOSED by skillars-deferred-63 AC2 — product decision made (block it); duplicateNextWeek
  now re-checks CoachProfileStatus.SUSPENDED against the locked, refreshed coach row, mirroring
  acceptReschedule's identical check exactly (same BookingError.COACH_UNAVAILABLE code)]`
  [`src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java:63-65`]
- `saveStep4` has the same gap as `duplicateNextWeek` above — its new locked refresh never re-checks
  `CoachProfileStatus.SUSPENDED` either, for the identical reason (pre-existing, out of AC2's explicit
  scope).
  [`src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:239-242`]
  `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names — CoachProfileService.java:258
  now carries `if (profile.getStatus() == CoachProfileStatus.SUSPENDED) { throw ... }` right after the locked
  refresh, with a comment citing "Deferred-64 AC1: mirrors RescheduleService.acceptReschedule's and
  BookingDuplicationService.duplicateNextWeek's identical check", verified live during skillars-deferred-66
  story creation, 2026-08-25)]`
- `persistPaymentFailure`'s new `@Transactional(propagation = Propagation.REQUIRES_NEW)` holds two
  concurrent physical DB connections per payment-deduction failure (the suspended caller transaction plus
  this method's own new one) instead of one. Deferred: this is the same tradeoff this class's two existing
  `REQUIRES_NEW` methods (`reserveCapture`, `declineBatchBooking`) already accept, not a new risk
  introduced by this story; revisit only if connection-pool exhaustion during a real payment-gateway outage
  is ever observed in practice.
  [`src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java:206-207`]
- Lock-ordering safety across the three coach-profile-lock-acquiring methods (`RescheduleService
  .acceptReschedule`, `BookingDuplicationService.duplicateNextWeek`, `CoachProfileService.saveStep4`) is
  documented in prose (this story's own Dev Notes) rather than enforced by any code invariant or test — a
  future fourth caller acquiring a different contended resource before the coach lock, in the reverse
  order, would deadlock silently with nothing today to prevent or detect it. `[CLOSED by skillars-deferred-69
  AC8 — lightweight enforced test added (RescheduleServiceTest#acceptReschedule_lockAcquisitionOrder_rescheduleRequestBeforeCoachProfile,
  a Mockito InOrder call-order assertion), not a generic lock-ordering lint/test harness. Confirmed during
  AC8's own research: no live deadlock scenario exists among these three methods today —
  BookingDuplicationService.duplicateNextWeek and CoachProfileService.saveStep4 each take exactly one lock
  (the coach-profile row only); only acceptReschedule takes two in sequence. This guards the convention
  going forward, it does not prove a bug existed.]` Deferred: broad architectural
  concern spanning all three existing methods, not introduced by this diff; a real fix would mean either a
  documented/enforced lock-acquisition-order convention or a lock-ordering lint/test harness, both larger
  than a single bounded fix.
  [`src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`,
  `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java`,
  `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java`]
- `deferred-work.md` and `sprint-status.yaml` accumulate indefinitely via ever-longer single lines (one
  line in each file already exceeds 40,000 characters) rather than being pruned or archived. Deferred:
  pre-existing project-wide convention followed identically by every prior story in this ledger, not
  specific to this diff; fixing it would mean changing the ledger/status-tracking convention itself across
  the whole project, out of scope for any single story.
  [`_bmad-output/implementation-artifacts/deferred-work.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml`]

## Deferred from: code review of skillars-deferred-59-radar-composite-overflow-guard-drill-video-ref-persist-fix-availability-timezone-diagnostics-and-ssh-firewall-rule-hygiene (2026-08-24)

- `BookingService.isSlotWithinAvailabilityWindow`'s new all-invalid-timezone summary WARN reads
  `windows.get(0).getCoachId()`, assuming every element in the `windows` list belongs to the same coach —
  nothing in the method signature (`List<CoachAvailabilityWindow>`) enforces this. Verified all 5 current
  call sites (`BookingService.createBookingRequest`, `RescheduleService` ×2, `BookingDuplicationService`,
  `BookingBatchService`) fetch `windows` via `coachAvailabilityWindowRepository.findByCoachId(...)`, so a
  mixed-coach list is unreachable today. Deferred: not exploitable via any current caller, and fixing it
  would mean either passing `coachId` as an explicit separate parameter or documenting/enforcing a
  single-coach-list invariant — a small design choice, not urgent given the unreachability, but worth a
  guard if a future caller ever merges windows across coaches before calling this method.
  [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`] `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names)]`

## Deferred from: skillars-deferred-63 story creation (2026-08-24)

Filed during a multi-round product-decision discussion (2026-08-24) covering nine `deferred-work.md`
items the project owner explicitly decided, split across `skillars-deferred-62` (the
`jakarta.persistence.lock.timeout` fix, too large to bundle) and `skillars-deferred-63` (the other seven).
Two items surfaced during that discussion that neither story implements — one filed fresh here and picked
up in the same pass per the `skillars-deferred-53` precedent (found while verifying, filed and picked up
immediately), one filed fresh and deliberately left open:

- **`DisputeService.raiseDispute` had no coach-eligible path before this discussion surfaced it** — only
  the parent/player could raise a dispute, even though `NO_SHOW_COACH` is already an eligible status a
  coach might reasonably want to contest, and `DisputeResource.resolveCurrentRole()` would have silently
  mis-recorded any coach caller as `"PLAYER"`. Not a mis-stated existing item — no prior ledger entry named
  this gap explicitly. `[src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java:76`,
  `src/main/java/com/softropic/skillars/platform/admin/api/DisputeResource.java:74-81]`
  `[CLOSED by skillars-deferred-63 AC5 — ships a symmetric first-raise right only, not a contest/rebuttal
  mechanism (story-review, 2026-08-24: findOpenByBookingId still blocks a second dispute on a booking that
  already has one open, regardless of who raises it first, and getDispute still 403s any non-raiser). A
  coach can now raise their own, independent dispute on a booking with none yet, and raiseDispute/
  resolveCurrentRole() correctly record raisedByRole="COACH" (needed a new V102 migration widening
  admin.disputes' raised_by_role CHECK constraint, found necessary during implementation). What a coach
  still cannot do — contest, rebut, or even view a dispute a parent already filed — is tracked as a new,
  separate item filed below]`
- **Payment capture happens at booking confirmation, well before the session occurs, and no code path
  gates coach payout on session completion or parent confirmation** — `BookingPaymentPersistenceService
  .reserveCapture` captures the charge during the accept/payment flow; `BookingCompletedEvent` has no
  payment-side listener (confirmed by grep across all its consumers — only timeline/homework/notification
  listeners exist); Stripe Connect settles the already-captured charge independently of any in-app
  completion signal. Raised while scoping the no-show/dispute items above (the idea that a coach shouldn't
  be paid until the parent confirms a session took place) and found to be a genuine escrow/delayed-payout
  architecture change, not a bundled fix — needs its own design pass on fund-hold duration, interaction
  with `QuickCompleteTimeoutService`'s auto-completion-on-the-parent's-behalf
  (`src/main/java/com/softropic/skillars/platform/booking/service/QuickCompleteTimeoutService.java:36-61`),
  and interaction with the dispute system (still one-sided even after `skillars-deferred-63` AC5 — a coach
  can now raise a dispute, but there is still no coach-side rebuttal *before* an automatic no-show refund
  fires). Also note: extending `NO_SHOW_COACH` to be raisable from `IN_PROGRESS` (so a coach can't dodge a
  no-show claim just by pressing "start") was explicitly considered and declined by the project owner this
  same round — `BookingService.recordNoShowCoach`
  (`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:739-758`) remains
  `UPCOMING`-only. Deliberately not picked up by any story from this discussion — needs a dedicated design
  pass.

## Deferred from: code review of skillars-deferred-62-postgres-lock-timeout-bounded-wait-fix (2026-08-24)

- **`BookingService.cancelBookingAsParent`'s locked `findByIdForUpdate` read has no `entityManager.refresh(...)`, unlike its sibling call sites.** Pre-existing — confirmed via the diff hunk that this story's code review examined: only the new `lockRetryer.withBoundedRetry(...)` wrap was added here, the missing refresh predates this story. `booking` is already loaded into the persistence context by the earlier unlocked `getBookingOrThrow` read (done first, deliberately, to authorise the caller before taking a row lock); `findByIdForUpdate`'s JPQL query then returns that same managed instance without refreshing its fields — exactly the stale-read trap this story's own Dev Notes describe for the four repositories it changed. `statusBeforeCancel`, used to decide refund eligibility, could reflect a status from before a concurrent status change committed elsewhere. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:645`] `[CLOSED by skillars-deferred-64 (verified already fixed by the story the tag names — BookingService.java:650 now carries entityManager.refresh(booking, LockModeType.PESSIMISTIC_WRITE) with a comment citing "Deferred-64 AC2", verified live during skillars-deferred-66 story creation, 2026-08-25)]`
- **`PessimisticLockRetryer`'s retry loop sleeps while still holding the transaction's pooled JDBC connection.** Up to ~3.2s (the documented default retry budget) of connection-pool time is spent purely sleeping under contention per call. A consciously-chosen tradeoff of the savepoint-based retry-in-place design — the Dev Agent Record documents that Spring's declarative `Propagation.NESTED` was investigated and found unavailable (`DefaultJpaDialect` has no savepoint support), which is why retry happens in-place rather than via a connection-releasing mechanism. Worth tracking if connection-pool pressure becomes visible under real load. [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java`]
- **Only `SessionPackPurchaseRepository` has full IT-level proof that `NO_WAIT` + retry actually works end-to-end; the other three repositories (`CoachProfileRepository`, `BookingRescheduleRequestRepository`, `BookingRepository`) rely on their consuming services' unit/targeted test coverage only**, not a dedicated concurrency IT each — notably including `RescheduleService.acceptReschedule`, which takes multiple sequential locks in one transaction, a more complex shape than the proven single-lock case. Within this story's own AC3 discretion ("unless the dev agent judges the shared-helper coverage insufficient for the other three's specific call-site wiring") — flagged here as a residual coverage gap for future awareness, not a violation. `[CLOSED (for RescheduleService.acceptReschedule specifically, the one this item names) by skillars-deferred-69 AC9: RescheduleServiceConcurrencyIT.java added, mirroring SessionPackPurchaseLockContentionIT's structure — brief-contention (succeeds via bounded retry) and prolonged-contention (fails with PessimisticLockingFailureException, bounded well under the full hold time) cases, contending on the reschedule-request row via real Postgres SELECT ... FOR UPDATE. CoachProfileRepository and BookingRepository's own findByIdForUpdate coverage remains the shared-helper-only gap this item originally flagged — not addressed by this closure.]` `[CLOSED (fully) by skillars-deferred-72: BookingServiceConcurrencyIT gained cancelBookingAsParent_briefContentionOnBookingRow_succeedsAfterBoundedRetry, cancelBookingAsParent_prolongedContentionOnBookingRow_failsWithBoundedPessimisticLockingFailure (closing BookingRepository's zero-coverage gap), and saveStep4_coachRowLockedByAnotherSession_prolongedContentionFailsWithBoundedPessimisticLockingFailure (closing CoachProfileRepository's missing prolonged-contention half — the brief half already existed). All four repositories named in the original item now have both contention-shape halves proven by a dedicated IT.]` [`src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPurchaseLockContentionIT.java`]
- **`PessimisticLockRetryer.withBoundedRetry`'s `Supplier<T>` idempotency/side-effect-free contract is documented only in a javadoc comment, not enforced by the method signature.** Nothing stops a future caller from passing a supplier with real side effects (an external call, an event publish, a write) that would then be silently re-executed on every retry attempt. All 16 current call sites are read-only (a `findByIdForUpdate` plus optional `refresh`), confirmed by this story's own code review — speculative future-risk, not a current violation. [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java`]
- **A JDBC `setSavepoint`/rollback-to-savepoint call itself failing (e.g. genuine connection loss) propagates unretried as an opaque 500**, since `PessimisticLockRetryer` only catches `PessimisticLockingFailureException`. Arguably acceptable — this represents genuine infrastructure failure rather than lock contention — noted for awareness rather than as a defect. [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:63-64,72`]

## Deferred from: code review of skillars-deferred-65-pack-selection-parity-timezone-validation-strictness-and-availability-week-scoping-fixes (2026-08-25)

- **`ZoneId.getAvailableZoneIds()` still contains DST-blind fixed-offset-equivalent forms outside `+HH:MM` notation that `@IanaTimezone`'s tightening does not reject.** AC2 tightened the validator to require `getAvailableZoneIds().contains(value)`, closing `"+01:00"`/`"GMT+2"`/`"Z"`-style forms, but the set also contains entries like `"Etc/GMT+1"` (verified: `contains("Etc/GMT+1")` is `true`) which are themselves fixed-offset, DST-blind zones under a region-shaped name — the same accepted-gap class the AC's own text already names for `"Navajo"`, just never called out for the `Etc/GMT±N` block specifically. Consistent with the tighten-only decision (an exclusion-list closing every such alias family was explicitly not what was decided), so not a defect against this story — flagged in case a future strictness pass wants a tighter allow-list (e.g. reusing `CoachProfileService`'s continent-prefix filter) instead of raw `getAvailableZoneIds()` membership. [`src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidator.java`]
- **`@IanaTimezone`'s correctness now depends on `ZoneId.getAvailableZoneIds()`'s contents, which can change across JDK/tzdata updates, with no stability-risk discussion recorded anywhere.** A `canonicalTimezone` value that validates today could in principle stop validating after a routine JDK patch that renames or drops a legacy alias. The original, looser `ZoneId.of`-only check had the same JDK-version dependency, just a laxer one — this is an architectural characteristic of the design, not something newly introduced by AC2's tightening, but worth a documented floor (or a pinned tzdata version note) if it ever causes a real incident. [`src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidator.java`]
- **The reschedule dialog's "New session end" field still has no timezone-clarifying hint, unlike its sibling "New session start" field.** AC4 added a `startTimezoneHint` to the proposed-start `q-input` only; `proposedEnd` is shown in the same ambiguous browser-local `datetime-local` rendering and carries only its pre-existing `endDerivedHint` (explaining that it's auto-derived from start + duration, not which timezone it's displayed in). A parent could still misread which timezone the derived end time represents. Real potential UX-consistency gap, but explicitly outside AC4's scope as written (named only the start field). [`src/frontend/src/pages/parent/ParentBookingsPage.vue:105-107`] `[CLOSED by skillars-deferred-66 AC1: added endDerivedHintWithTimezone i18n key (en-US/de-DE/fr-FR) combining the existing auto-derived explanation with the timezone statement; proposedEnd q-input in ParentBookingsPage.vue now uses it, interpolating browserTimezone/rescheduleBookingTimezone]`

## Deferred from: story-review and implementation of skillars-deferred-63-product-directed-fairness-and-consistency-fixes (2026-08-24)

- **`recordNoShowCoach` accepted a no-show claim reported before the booking's scheduled start time.** `BookingService.recordNoShowCoach` checked only that the caller owns the booking as parent — a parent could call `POST /api/bookings/{id}/no-show-coach` at any point while the booking was still `UPCOMING`, including well before `requestedStartTime`, firing the same automatic full-refund + coach-strike consequence a genuine no-show gets. Distinct from the still-open "should a late `CANCEL_PARENT` auto-become a no-show?" question tracked under the `skillars-deferred-28` heading above — that question is about `cancelBookingAsParent`, untouched here. `[CLOSED by skillars-deferred-63 AC4]` A new `BookingError.NO_SHOW_TOO_EARLY` code (`booking.noShowTooEarly`) now rejects the claim if `Instant.now().isBefore(booking.getRequestedStartTime())`. `IN_PROGRESS`-raisable `NO_SHOW_COACH` (so a coach can't dodge a claim by pressing "start") was explicitly considered and declined by the project owner this same round — remains open, see the payment-capture/escrow item above. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:747-758`] `[DECIDED 2026-08-25: stays UPCOMING-only; the IN_PROGRESS dodge-by-pressing-start gap is accepted]`
- **A coach still cannot contest, rebut, or even view a dispute a parent already filed on a booking.** `skillars-deferred-63` AC5 gave a coach a symmetric first-raise right on a booking with no dispute yet, but `DisputeService.raiseDispute`'s `disputeRepository.findOpenByBookingId(bookingId)` check has no `raisedBy` filter — it 409s (`disputes.alreadyRaised`) on *any* open dispute regardless of who raised it — and `getDispute` 403s any caller who isn't the original raiser (`dispute.getRaisedBy().equals(requesterId)`). So a coach cannot respond to, or even read, a dispute a parent already filed against them through this API. Found during `skillars-deferred-63`'s own story-review (2026-08-24). A real fix is a genuine two-sided-dispute design question, not a mechanical change: does a second, opposing dispute on one booking need to be resolved jointly with the first? does the admin `AdminDisputeDetailDto`/UI support two open disputes on the same booking? [`src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java:76-79,107-120`] `[DECIDED 2026-08-25: keep first-raiser-wins as final; no two-sided contest mechanism planned]`
- **`CoachProfileService.saveStep4` still writes each availability window's `canonicalTimezone` from the request payload rather than from the coach's own profile, so new profile/window timezone drift can still occur.** `skillars-deferred-63` AC6 backfilled *existing* diverged rows (`V103__availability_window_timezone_backfill.sql`, closing the immediate half of the `skillars-deferred-17` D8 item above) but deliberately did not change `saveStep4`'s write behavior this round: `ProfileBuilderStep4.vue` ships a real, coach-editable per-window `TimezoneSelect` with helper copy reading "Windows above are interpreted in this timezone," and forcing `saveStep4` to silently discard that value without a coordinated frontend change would make the picker and its own helper text actively lie about what the screen does. Found during `skillars-deferred-63`'s own story-review (2026-08-24). A follow-up story needs to change both sides together: drop or make the picker read-only (e.g. "change it in Step 1"), *then* make `saveStep4` stop trusting the request's `canonicalTimezone` value. [`src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:251`, `src/frontend/src/components/profileBuilder/ProfileBuilderStep4.vue:66-68`] `[DECIDED 2026-08-25: per-window coach timezone is a deliberate feature, not a bug; saveStep4's write behavior stays as-is; no further action planned beyond skillars-deferred-63's one-time backfill]`
- **`PlaybackService.authorizePlayback`'s bandwidth-dedup check (`skillars-deferred-63` AC3) has no locking around its exists-then-charge sequence, so two genuinely concurrent authorizations for the same `(viewerId, videoId)` can both charge before either commits its new `PlaybackToken` row.** Accepted as a known, low-stakes gap (bandwidth-quota over-count by at most one extra `storageBytes` per race, not a money-movement bug) rather than fixed — row-level locking was explicitly ruled out of scope for this story. Found during `skillars-deferred-63`'s own story-review (2026-08-24). A future fix would need a `(viewer_id, video_id)`-scoped lock around the exists-check, mirroring this codebase's `PessimisticLockRetryer` pattern elsewhere. [`src/main/java/com/softropic/skillars/platform/video/service/PlaybackService.java:111-132`] `[CLOSED by skillars-deferred-82 story creation (already fixed, unannotated): PlaybackService.java:127-133 now takes videoRepository.findByIdForUpdate under lockRetryer.withBoundedRetry, per its own inline comment — "Deferred-64 AC3: serializes the exists-check + conditional charge below... closing the check-then-act race skillars-deferred-63 AC3 explicitly deferred."]`

## Deferred from: skillars-deferred-66 story creation (2026-08-25)

Written while scoping `skillars-deferred-66`, which re-mined the Booking/Availability/Reschedule module
(and, on finding it thin, the Marketplace/Coach-profile module too) for open items. Two of the three
originally-scoped items turned out to be already fixed, unannotated — closed above rather than picked up
(`skillars-deferred-62` review's `cancelBookingAsParent` missing-refresh item, `skillars-deferred-58`
review's `saveStep4` missing-`SUSPENDED`-check item, both confirmed fixed by `skillars-deferred-64` by
direct read of current source). One new item, found by direct code reading rather than from any prior
ledger entry, is picked up immediately per the `skillars-deferred-63` precedent (found while verifying,
filed and picked up in the same pass):

- **`BookingCompletionService` handles `OptimisticLockingFailureException` inconsistently across its own sibling methods.** `Booking` carries `@Version` (optimistic locking). `endSession`, `pauseSession`, `resumeSession`, and `confirmCompletion` each wrap their `bookingService.transition(...)` call in `try { ... } catch (OptimisticLockingFailureException e) { throw new OperationNotAllowedException(...) }`, converting a concurrent-modification conflict into a clean, retry-able 403. `startSession` (`:54`), `initiateQuickComplete` (`:111`), and `submitWrapUp`'s `LIVE`-mode branch (`:148`) call `bookingService.transition(...)` with no such guard — a concurrent double-click on "Start Session" or "Quick Complete" surfaces as a raw, unhandled 500 instead. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java:48-55,101-112,139-165`] `[CLOSED by skillars-deferred-66 AC2: startSession, initiateQuickComplete, and submitWrapUp's LIVE-mode transition() call are now each wrapped in the same try/catch (OptimisticLockingFailureException) shape as endSession/pauseSession/resumeSession/confirmCompletion, throwing OperationNotAllowedException("Booking status changed concurrently — retry", SecurityError.MISSING_RIGHTS)]`

## Deferred from: code review of skillars-deferred-66 (2026-08-25)

Two pre-existing issues identified during code review but deferred as out-of-scope for this story:

- **`confirmCompletion()` has a misleading exception message for `OptimisticLockingFailureException`.** The method re-throws the exception with message "Session already confirmed", but `OptimisticLockingFailureException` signals concurrent modification, not a confirmed session. The four other guarded methods (`endSession`, `pauseSession`, `resumeSession`, `submitWrapUp`) correctly use "Booking status changed concurrently — retry". Found during review of skillars-deferred-66 AC2. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java:189`] Deferred: `confirmCompletion()` was not modified by this story; fixing this inconsistency is a separate pass. `[CLOSED by skillars-deferred-67 AC2: confirmCompletion's message changed to "Booking status changed concurrently — retry", matching the other six sites]`

- **Exception messages use imperative "retry" language that could confuse users.** All `OptimisticLockingFailureException` handlers throw with message "Booking status changed concurrently — retry", which uses imperative language ("retry") that might mislead end-users into thinking they should manually retry (click buttons again) rather than understanding the system will auto-retry. Found during review of skillars-deferred-66 AC2. This is a pre-existing message pattern already used consistently across endSession/pauseSession/resumeSession/confirmCompletion, not introduced by this story. Deferred: This message design is a pre-existing choice; consistency with existing code is the point for now.

- **`BookingCompletionService`'s seven `OptimisticLockingFailureException` catch blocks all re-throw with `SecurityError.MISSING_RIGHTS`, an authorization-failure code, for what is actually a concurrency conflict.** Found during review of skillars-deferred-66 AC2, which added three new catches mirroring the four pre-existing ones — the finding applies to all seven equally, since the new code was written to match the old exactly (this story's own Dev Notes: "match what endSession/pauseSession/resumeSession already do exactly"). The three new sites (`startSession`, `initiateQuickComplete`, `submitWrapUp` LIVE-mode) now carry a one-line comment explaining the choice, as a documentation-only partial fix; the four pre-existing sites remain uncommented. A real fix — introducing a dedicated `ErrorCode` for optimistic-lock conflicts and applying it uniformly across all seven sites — is out of skillars-deferred-66's bounded scope (it would mean redesigning the concurrency-handling pattern the story was explicitly told to mirror, not extend). [`src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java:56-60,75-77,88-90,101-103,118-120,159-162,189`] `[CLOSED by skillars-deferred-67 AC1: added a dedicated BookingError.CONCURRENT_MODIFICATION code and routed all seven catch blocks through it instead of SecurityError.MISSING_RIGHTS]`

- **Four of `BookingCompletionService`'s seven `OptimisticLockingFailureException` catch blocks (the pre-existing `endSession`/`pauseSession`/`resumeSession`/`confirmCompletion`) still discard the original exception instead of chaining it as cause, losing stack-trace context for debugging.** Found during review of skillars-deferred-66 AC2. The three new sites this story added (`startSession`, `initiateQuickComplete`, `submitWrapUp` LIVE-mode) now chain the cause via a new `OperationNotAllowedException(String, Throwable, ErrorCode)` constructor; the four pre-existing sites were left unchanged as out of this story's scope (untouched methods, same "separate pass" reasoning already applied elsewhere in this story). A future story should extend the same chaining to the remaining four, ideally alongside the `MISSING_RIGHTS` item above since both touch the same seven blocks. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingCompletionService.java:75-77,88-90,101-103,189`] `[CLOSED by skillars-deferred-67 AC2: all four sites now chain the caught exception as cause via the existing 3-arg OperationNotAllowedException constructor]`

## Deferred from: code review of skillars-deferred-67 (2026-08-25)

One pre-existing issue identified during code review:

- **Verify that other services in the booking module do not reuse authorization error codes for concurrency conflicts.** skillars-deferred-67's code review confirmed that BookingCompletionService is now fully corrected (dedicated BookingError.CONCURRENT_MODIFICATION code, all 7 catch blocks identical, all 7 paths tested). Recommend checking if the same pattern (reusing SecurityError.MISSING_RIGHTS for OptimisticLockingFailureException) exists elsewhere in BookingService, AvailabilityService, RescheduleService, or other booking-module services. This story scoped to BookingCompletionService only; if other services need the same fix, they should be picked up in a future audit pass. `[CLOSED by skillars-deferred-68: audited by direct source read — no code literally reuses MISSING_RIGHTS for this exception outside BookingCompletionService (already fixed by deferred-66/67); AvailabilityService cannot throw it at all (no @Version entities); but 6 BookingService methods (acceptBooking, declineBooking, cancelBookingAsCoach, recordNoShowPlayer, recordNoShowCoach, cancelDueToPause) + RescheduleService.acceptReschedule + BookingBatchService's resolveFailureCode fallback all let it propagate as an unclassified 500/generic.unknown instead — fixed by wrapping each write call in the identical OptimisticLockingFailureException → BookingError.CONCURRENT_MODIFICATION catch shape (AC1-AC2) and adding a CONCURRENT_MODIFICATION branch to resolveFailureCode (AC3); 8 new concurrency tests added (AC4)]`

## Deferred from: skillars-deferred-68 CI run (2026-08-25)

Found while diagnosing an unrelated CI failure on skillars-deferred-68's own PR — a real, pre-existing production bug, not caused by that story's diff (which only touches `OptimisticLockingFailureException` handling, nowhere near this code path):

- **`BookingService.isSlotWithinAvailabilityWindow` rejects a session that crosses midnight even against a coach with a wide-open, every-day-of-week availability window.** [CLOSED by skillars-deferred-69 AC1-AC2: `isSlotWithinAvailabilityWindow` now throws `BookingError.SESSION_CROSSES_MIDNIGHT` for a session whose end falls on a different calendar day than its start against an otherwise-matching window, instead of silently falling through to a generic `false`/`SLOT_OUTSIDE_AVAILABILITY`; `RescheduleResourceIT`'s wall-clock-relative `Instant.now().plus(N, DAYS)` time helper (the root cause of the original CI flake) was replaced with a fixed-safe-hour `safeProposedStart(int)` helper across every call site in that file.] The method anchors both `windowStart` and `windowEnd` to the session's own start date (`w.getStartTime().atDate(startZdt.toLocalDate())` / `w.getEndTime().atDate(startZdt.toLocalDate())`), so a window declared `00:00:00`–`23:59:59` still has its upper bound fixed at that same calendar day's `23:59:59`. A session starting late at night (e.g. 23:32 in the coach's canonical timezone) with any duration that pushes its end time past midnight fails `!endZdt.isAfter(windowEnd)`, and no other day-of-week window entry can rescue it either, since the day-of-week match (`w.getDayOfWeek() == startZdt.getDayOfWeek().getValue()`) only tries the start day's own window. Reproduced directly: `RescheduleResourceIT` (`acceptReschedule_asOwningCoach_returns204AndUpdatesBookingAndStatus`, `acceptReschedule_proposedSlotTakenByAnotherBooking_returns403AndLeavesBookingUnchanged`, `requestReschedule_asParentWithConfirmedBooking_returns204AndCreatesRecord`, `requestReschedule_pendingRequestAlreadyExists_returns403WithRescheduleAlreadyPendingKey`, `duplicateNextWeek_asOwningCoachWithCompletedBooking_returns204`) all use `Instant.now().plus(N, DAYS)` proposals against a fixture explicitly documented as day-agnostic wide-open availability (`RescheduleResourceIT.java:125-137`, comment: "every day of week, so every existing test's day-agnostic ... proposal keeps landing inside SOME window regardless of when CI runs") — the comment's premise holds for day-of-week but not for hour-of-day: any CI run between roughly 23:00 and 24:00 in `Europe/Berlin` (this fixture's canonical timezone) reliably fails these five tests, and any real coach/parent proposing a late-night reschedule, duplicate-next-week, or initial booking request hits the identical `booking.slotOutsideAvailability` false rejection in production. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:856-890`] Deferred: real production bug and a real CI-flakiness source, but unrelated to and out of scope for skillars-deferred-68 (which only touches `OptimisticLockingFailureException` handling); a real fix needs a product decision on how a midnight-crossing session should be validated against a per-day window model (e.g., split the check across the start day's window and the next day's window, or reject cross-midnight sessions outright with a dedicated error) — worth a future story, and worth checking whether the test fixture's own "day-agnostic" comment should be corrected to "day-of-week-agnostic, not hour-of-day-agnostic" regardless of which fix is chosen.

## Deferred from: code review of skillars-deferred-69 (2026-08-26)

Code review identified 4 pre-existing or acknowledged issues not actionable in this story:

- **Unprotected Booking Status Check Before Reschedule Accept** — `acceptRescheduleAsParent` checks booking status with unlocked read before `acceptRescheduleShared()`, allowing status to transition between check and modification. Mitigated by OptimisticLockingFailureException on save, which is the established pattern in this codebase for transient states. [`BookingService.java:248-253` pattern] `[CLOSED by skillars-deferred-72 (verified already mitigated by design): the unlocked status check is documented in-code as a cheap early-out only; the real correctness guard is acceptRescheduleShared's own locked, refreshed re-read immediately following it. Not a live gap.]`

- **Batch Service Staleness Window Not Fully Closed** — Code acknowledges that the validation window between re-checking coach availability and actually committing batch bookings is narrowed but not eliminated. `CoachAvailabilityWindow` has no `@Version` and no locked-read method, so coach edits landing between the re-check and the transaction commit remain unseen. This is a documented and accepted limitation. [`BookingBatchService.java:167-195`] `[PARTIALLY ADDRESSED by skillars-deferred-72 AC4: an adjacent-but-distinct gap — the GET-availability-calendar-vs-POST-batch-submit informational staleness (not this bullet's write-side TOCTOU window) — is now closed, mirroring skillars-deferred-71 AC2's single-booking signature exactly (a batch has exactly one coachId for its whole basket, so the same signature applies unchanged). This bullet's own write-side concern — no @Version/lock support on CoachAvailabilityWindow between the fresh re-check and the actual commit — remains fully open and is not what AC4 addresses.]` `[CLOSED by skillars-deferred-78 AC1 (confirmed during skillars-deferred-80 story creation, 2026-08-28): createBatch now acquires the same per-coach PessimisticLockRetryer lock immediately before the fresh pre-commit re-check (BookingBatchService.java:190-202), and holds it through the batch/booking writes — the code's own comment at that call site states this explicitly. This item's own write-side TOCTOU window (distinct from AC4's read-side signature check above) is fully closed; the ledger was never updated when deferred-78 shipped.]`

- **Batch Lock Retry Timeout Lacks Graceful Degradation** — Calls to `lockRetryer.withBoundedRetry()` assume lock acquisition succeeds within bounds. If the batch row is locked by a concurrent transaction, the entire operation times out without graceful handling or user-friendly error messages. This is a pre-existing codebase pattern, not introduced by this story. [`BookingBatchService.java:358,407`] `[CLOSED (already handled, confirmed during skillars-deferred-79 story creation, 2026-08-28): ApiAdvice.pessimisticLockExceptionHandler (ApiAdvice.java:580-586) maps any PessimisticLockingFailureException — exactly what an exhausted withBoundedRetry() throws — to a clean 409 with user-facing "This resource is busy — please retry" copy, application-wide. skillars-deferred-78's own Dev Notes had already privately confirmed this same finding but never reflected it back into this ledger; this pass closes that gap.]`

- **Frontend Error Handling Delegated Without Contracts** — AC10 removed `completionLoading` and `completionError` state tracking, delegating error handling to calling components. If callers forget `try`/`catch`, errors silently propagate without logging or user feedback. This is an intentional design decision (moving toward caller-owned error handling); changing it would require a spec decision. [`booking.store.js` AC10 changes] `[CLOSED (for the two live cases) by skillars-deferred-72 AC5: this item's hypothesis was verified for real — a full audit of every booking.store.js action-handler call site found 11 of 13 already wrapped in try/catch with user feedback, but CoachCommandCenterPage.vue's handleStartSession and handleQuickComplete had zero error handling at all (bare @click-bound await, no catch anywhere in the call chain) — a genuinely reachable silent-failure bug, not just a hypothetical. Both fixed with the same scoped-loading-id + try/catch/finally + toast shape every sibling handler in the same file already uses. No store-level enforcement mechanism was added — AC10's caller-owned design decision stands, and this codebase's own established idiom for this problem class is a per-caller try/catch, not automated enforcement; a future new caller could still forget. That residual risk is accepted, matching this project's anti-speculative-engineering convention, rather than building lint-rule/wrapper infrastructure for a problem now reduced to zero known live instances.]`

## Deferred from: code review of skillars-deferred-75 (2026-08-27)

Code review (parallel adversarial layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor) identified:

- **Video Error Handling UX** — When a video load fails (e.g., expired signed URL), code silently refetches drill data in background. Video element remains broken with no user notification. May be intentional design; requires product/UX decision. [`DrillCard.vue`, `DrillDetailPanel.vue`, and page handlers]

- **SessionPlanService Terminal Booking Check Order** — Code allows DRAFT session paired with terminal (cancelled/completed) booking to exist. Unclear if intentional edge-case recovery path or design gap. Async `handleBookingCompleted()` should transition sessions to COMPLETED, but if that fails, gap remains. Needs design intent clarification. [`SessionPlanService.java:127-139`] `[CLOSED by skillars-deferred-78 AC8 (confirmed during skillars-deferred-79 story creation, 2026-08-28): handleBookingTerminalNonCompletion listener (SessionPlanService.java:194-211) now transitions any DRAFT/SAVED session plan to CANCELLED on the same BookingStatusChangedEvent chokepoint every terminal-status consumer uses, closing this gap for every terminal status except COMPLETED (which handleBookingCompleted already covered).]`

## Deferred from: code review of skillars-deferred-77 (2026-08-27)

Items found during this story's own investigation or during its full-module regression sweep, not fixed because they are out of the story's ACs' scope:

- **`ReportGenerationService.getBranding()` has no tier check at all — a coach downgraded from ACADEMY still gets their old logo/brand colour served on any report request.** AC3's own investigation found this: `buildPdf` is correctly gated (only fetches branding when `tier == ACADEMY`), so the AC3 ledger item's original downgrade-then-reupgrade-reuses-stale-key scenario can't occur through `buildPdf` — but `getBranding()` (lines 196-202) returns whatever `CoachBranding` row exists for the coachId with zero tier gating, and logo S3 objects are never deleted on tier downgrade. A real fix needs a decided definition of "invalid" for a post-downgrade logoKey before it can be written (e.g., add a tier check mirroring `buildPdf`'s, and decide whether to also delete/orphan the S3 object on downgrade). [`ReportGenerationService.java:196-202`] `[CLOSED by skillars-deferred-80 AC3: getBranding() now tier-gates on ACADEMY, returning an empty CoachBrandingResponse without querying brandingRepository for a downgraded coach — matching saveBranding's own gate on the same row. Does not throw FeatureGatedException since this is a settings-page GET, not a write; does not address the separate S3-object-orphaning half of this item, which remains a design question if it's ever prioritized.]`

- **`VideoDeletionService.cascadeDeleteForAccount`'s own doc comment claims "each deleteVideo() runs in its own transaction," but this is false due to self-invocation.** `cascadeDeleteForAccount` is not itself `@Transactional` and calls `deleteVideo(video.getId(), ...)` as a plain same-class `this.deleteVideo(...)` call — this bypasses deleteVideo's own `@Transactional` proxy entirely (the exact self-invocation pitfall documented elsewhere in this codebase on `BookingService.acceptAndInitiatePayment` and `TimelineEventListener`'s `@Lazy @Autowired self` field). In practice this likely still behaves reasonably (each deleteVideo's individual repository saves still auto-commit), but the method does NOT get the atomicity the comment promises, and this was not introduced by — nor is it fixed by — AC12 (which only made the quota-reset conditional). A real fix would inject a `@Lazy` self-reference or extract deleteVideo's core into a separate bean, mirroring `SluPersistenceRetrier`/`RadarCompositeCalculationService.self`. [`VideoDeletionService.java:145,158`] `[CLOSED by skillars-deferred-80 AC2: VideoDeletionService gained a @Lazy @Autowired self field mirroring RadarCompositeCalculationService.self, so cascadeDeleteForAccount's per-video deleteVideo(...) call now goes through self.deleteVideo(...) and genuinely runs in its own transaction, making the method's own doc comment true.]`

- **Pre-existing, unrelated test failures found during this story's full-module regression sweep — both from `skillars-deferred-76`, not touched by this story's diff.** `BookingPaymentPersistenceServiceTest` (3/3 tests) fails with `NullPointerException: this.settleSuccessCounter is null` / `this.settleFailedCounter is null` — these `Counter` fields are populated by a method never invoked in this pure-Mockito unit test's setup. `StripeWebhookVerificationTest.processWebhook_invoicePaymentFailed_noSubscription_doesNotIncrementCounter` fails asserting a counter is `null` when it isn't — looks like Micrometer counter state leaking across test methods sharing a registry. Both introduced by `skillars-deferred-76`'s real Stripe payment-alerting work (`settle_success`/`settle_failed`/`invoice_failed` counters); confirmed via `git log` that neither file was touched by this story. [`BookingPaymentPersistenceServiceTest.java`, `StripeWebhookVerificationTest.java`] `[CLOSED (already fixed, confirmed during skillars-deferred-79 story creation, 2026-08-28): both classes pass individually and under a full payment-module bundle run (mvn -o test -Dtest="com.softropic.skillars.platform.payment.**", zero failing reports). BookingPaymentPersistenceServiceTest's @BeforeEach already calls service.initializeCounters() with a comment explaining this exact root cause — git log confirms the fix landed in the same commit (959a0e2, skillars-deferred-77's own PR #121) that this ledger item was filed from, meaning it was stale from the moment it was written.]`

- **`LoginAttemptsServiceTest` (14/14 green in isolation) fails 12/14 when run together with the marketplace+security module bundle via a wildcard `-Dtest` pattern — confirmed pre-existing (reproduces identically on master with this story's entire diff stashed, including untracked files).** Looks like test-order-dependent state pollution from a shared cache/clock singleton another test class in that combined run leaves dirty; not caused by, and out of scope for, this story (which touches `PlayerProfileService`/`PlayerProfileRepository` in the same modules but not login-attempt tracking). [`LoginAttemptsServiceTest.java`]

## Deferred from: story-review audit of skillars-deferred-78-availability-write-lock-parity-reschedule-signature-wiring-and-session-cancellation-guard (2026-08-28)

- **`CoachAvailabilityBlock` is never enforced on any booking-write path — a coach's manual block-out (e.g. marking a vacation day) only hides slots from the displayed calendar, not from actual booking creation.** Confirmed by direct source read: `CoachAvailabilityBlock`/`blockRepository` is referenced only inside `AvailabilityService.getAvailabilityCalendar` (read-side slot computation, lines 113-217) and `AvailabilityService`'s own `addBlock`/`deleteBlock` CRUD methods. `BookingService.createBookingRequest`, `BookingBatchService.createBatch`, and `RescheduleService.validateRescheduleProposal` all validate a proposed slot only against `CoachAvailabilityWindow` (via `isSlotWithinAvailabilityWindow`) and existing-booking overlap — never against blocks. A parent working off a stale calendar view, or any client calling the booking API directly, can successfully book a slot the coach has explicitly blocked out; the block is purely cosmetic server-side. Found during `skillars-deferred-78`'s own post-implementation story-review audit (2026-08-28). AC1's own scope note — "Do not extend this to `addBlock`/`deleteBlock` — out of scope; the deferred item and the batch re-check both concern windows only" — covers the lock-parity question this story was actually about, but does not address this separate, pre-existing gap that blocks are never checked at write time at all, lock or no lock. A real fix needs `isSlotWithinAvailabilityWindow` (or a sibling check) extended to also reject a slot overlapping an active `CoachAvailabilityBlock`, applied at all three booking-creation write paths, plus a product decision on whether any already-booked slot that now falls inside a newly-added block needs retroactive handling (notify the parent? auto-decline? leave it, since the coach created the conflict after the fact?). [`src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java:113-217`, `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:259-272`, `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:130-157`, `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:200-226`] `[CLOSED by skillars-deferred-79: isSlotBlocked wired into all five booking-write call sites (createBookingRequest, batch validateSlotDurationAndAvailability, both reschedule checks, duplicateNextWeek), plus addBlock now rejects up front when it would conflict with an active booking (AC2). This ledger entry was never annotated when the story shipped — closed here during skillars-deferred-80's creation pass.]`

## Deferred from: code review of skillars-deferred-79-coach-availability-block-booking-enforcement (2026-08-28)

- **Race Condition in addBlock() Check-Then-Act Window** — Between checking for overlapping bookings and saving the block, concurrent booking creation can slip through. Spec explicitly defers this as out-of-scope; lockable concurrency treatment of addBlock/deleteBlock flagged for future story only if observed in practice. `[CLOSED by skillars-deferred-80 AC1: addBlock/deleteBlock now call lockProfile(profile.getId()) — the same per-coach PessimisticLockRetryer lock addWindow/updateWindow/deleteWindow already have — before the overlap check and the save, closing the check-then-act window.]`

- **Missing Pre-Commit Re-Check on Single Booking Creation** — Single bookings check availability blocks once; batch operations re-check at pre-commit stage. Not required by spec—batch re-check exists due to separate batch-staleness handling in skillars-deferred-69; single booking architecture is single-transaction validation, which matches existing pattern. `[CLOSED by skillars-deferred-80 AC1: out-of-scope finding confirmed still correct as originally reasoned — no code change needed for this bullet; closed alongside its two siblings in the same code-review section since the section itself is now fully resolved.]`

- **Missing Pessimistic Lock on Booking Overlap Check in addBlock()** — Two concurrent addBlock calls could both pass overlap check before either persists. Identical scope as race-condition finding; spec acknowledges but defers lock additions to addBlock/deleteBlock as follow-on work only if needed in practice. `[CLOSED by skillars-deferred-80 AC1: same fix as the race-condition finding above — the overlap check inside addBlock now runs under the per-coach lock, so two concurrent addBlock calls against the same coach can no longer both pass the check before either persists.]`

## Deferred from: code review of skillars-deferred-81-parent-name-batching-cross-drill-video-lock-video-error-toast-and-self-booking-packs (2026-08-28)

Four pre-existing issues identified during code review:

- **PlayerProfile XOR ownership check doesn't forbid both parentId AND userId being null.** `SessionPackPaymentService.purchasePack` line 463-471 checks `if (player.getParentId() != null)` then compares parentId, else compares userId. If a PlayerProfile row exists with *both* `parentId` and `userId` null (violating schema's expected `chk_pp_owner` XOR), the code takes the else branch and compares `player.getUserId()` (null) against `parentId` (caller's id), always failing with "Player does not own this profile." While the error is correct, code silently tolerates schema-level invariant violation instead of surfacing it as data corruption. Pre-existing invariant gap; does not corrupt data but masks violations. [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java:463-471`] `[CLOSED by skillars-deferred-83 story creation (false premise): V84__player_self_registration.sql:14-18 adds chk_pp_owner CHECK ((parent_id IS NOT NULL AND user_id IS NULL) OR (parent_id IS NULL AND user_id IS NOT NULL)) — a genuine DB-level XOR. A row with both null (or both non-null) cannot exist; the application-level branch this item worries about is unreachable by construction, not merely by convention.]`

- **Batch name-lookup fallback messages not localized.** `BookingService` uses hardcoded English fallback strings: "Unknown Player" (line 301), "Unknown Coach" (line 299 and multiple other sites), "Unknown Parent" (line 363). Not localized; if i18n added later, these fallbacks remain English even in German/French deployments. Story added i18n keys for videoLoadFailed toast but did not extract these fallbacks. Pre-existing localization gap; separate feature. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:multiple`] `[DECIDED 2026-08-29 (skillars-deferred-83 story creation): leave as-is. These fallbacks only render for an orphaned player_id/coach_id/parent_id reference on a booking row (no FK constraints on booking.bookings per V31__booking_requests.sql), but nothing in src/main ever deletes a player_profiles or coach_profiles row (confirmed by grep — zero hits for playerProfileRepository.delete/coachProfileRepository.delete anywhere in the codebase; GdprErasureService only anonymizes User rows). Same unreachable-orphaned-profile shape the project owner already decided "leave as-is" for the messaging module during skillars-deferred-82's own creation (line above, messaging orphaned-profile inconsistency) — applying the same precedent rather than treating this as new work.]`

- **Concurrency test doesn't verify lock actually prevented race, only end result.** `DrillUploadServiceConcurrencyIT.deleteVideo_concurrentCallsOnTwoClonesSharingOneVideoId_doesNotDoublePublishDeletionEvent` sets up CyclicBarrier and two threads calling deleteVideo on clones sharing one videoId, then checks that exactly one `VideoPhysicalDeletionEvent` was published. Only confirms *end result* is correct; does not confirm the lock *caused* the serialization. If lock were removed, test might still pass if both threads happened to commit sequentially by chance. True concurrency test should also verify that without lock, race is observable. Pre-existing test-coverage gap; test passes but could be stronger. [`src/test/java/com/softropic/skillars/platform/session/api/DrillUploadServiceConcurrencyIT.java`] `[PICKED UP by skillars-deferred-83 AC2: adds a new test that injects a controlled delay while the lock is held on one thread's findByIdForUpdate(videoId) call, records wall-clock timestamps of both threads' successful lock acquisitions, and asserts they are genuinely separated in time — proving the lock caused the serialization rather than merely correlating with the right end state.]`

- **@Transactional missing on SessionPackPaymentService.purchasePack.** Payment atomicity not guaranteed; acknowledged by story and explicitly noted as outside scope. Pre-existing; `purchasePack` was never annotated. If subsequent lines call payment gateway and persist SessionPackPurchase, concurrent failures during charge + database insert could leave inconsistent state. [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`] `[DISMISSED 2026-08-29 (skillars-deferred-83 story creation): purchasePack already has an explicit compensating-action pattern (createPurchase failure -> manual Stripe refund + PaymentGatewayException, ~lines 80-92), the correct pattern for a flow spanning an external HTTP call — wrapping the whole method in @Transactional would be counter-productive (holds a DB connection open across the Stripe call). The single DB write (sessionPackPurchaseRepository.save) already runs in its own implicit Spring Data transaction. No live atomicity bug this annotation would close.]`

- **`session.drill_video_refs.video_id` has no foreign key to `main.videos.id`.** Present since the table's own creation (`V38__session_module_init.sql`). The code review initially proposed and applied a migration (`V117__drill_video_refs_videoId_fk.sql`, `ON DELETE RESTRICT`) to close this as part of AC#3's cross-drill lock work, but it was reverted during the dev-story implementation pass (2026-08-29, confirmed with the user) for two reasons: (1) `grep`-confirmed no code path anywhere in this codebase ever issues a hard `DELETE` on a `Video` row — `VideoDeletionService`/`AdminVideoService` both only soft-transition `operationalState` to `PURGED`/`DELETED` — so the FK would close no reachable race; AC#3's own in-process pessimistic lock already fully covers the actual concurrent-request TOCTOU this story targets. (2) Applying the migration broke 4 pre-existing tests (`DrillUploadServiceConcurrencyIT.deleteVideo_concurrentCallsOnSameDrill_doesNotDoublePublishDeletionEvent` and 3 in `DrillUploadResourceIT`) that insert `drill_video_refs` rows with synthetic video ids that have no matching `main.videos` row. If a future story wants to add this FK as genuine defense-in-depth, it needs: a full audit/update of every test fixture that seeds `drill_video_refs` synthetically (at minimum the 4 named above), and a check for orphaned `video_id` values in production data before deploying the migration (an `ALTER TABLE ... ADD CONSTRAINT` on existing data fails outright if any row violates it). [`src/main/resources/db/migration/V38__session_module_init.sql`, `session.drill_video_refs`] `[DECIDED 2026-08-29 (skillars-deferred-82 story creation): keep deferring. skillars-deferred-81 AC3's in-process pessimistic lock already covers the real concurrent-request race; the FK closes no reachable bug today. Revisit only if a future change actually needs it.]`

## Deferred from: code review of skillars-deferred-83-video-deletion-transaction-isolation-cross-drill-lock-causality-and-review-submission-ui (2026-08-30)

- **`formatDate` renders review dates in the visitor's browser locale, not the active app locale.** `CoachPublicProfilePage.vue`'s new `formatDate(isoString)` calls bare `new Date(isoString).toLocaleDateString()` with no locale argument, so a French-locale app session still shows dates in whatever locale the visitor's browser reports. Deferred rather than fixed: this exactly mirrors `CoachReliabilityPage.vue:121-124`'s own pre-existing `formatDate`, the established codebase pattern this story's own AC3 was explicitly directed to mirror — not a defect newly introduced by this diff, but a pattern already present elsewhere. [`src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue`]
- **`app.bootstrap.jwt-secret.enabled`'s only protection against accidental enablement in a real environment is a code comment, not an enforced guard.** `JwtSecretBootstrapRunner`'s javadoc states the flag "MUST stay unset (or false) in every real environment," and (per its own javadoc) is deliberately not `@Profile`-gated since production boots with no active Spring profile at all — so the property gate is the only gate, by design. Nothing in code prevents a misconfigured non-dev deployment from setting it `true`. Already disclosed in `skillars-deferred-83`'s own Dev Agent Record as infrastructure/tooling added at the project owner's explicit request, outside that story's scope. [`src/main/resources/application-dev.yaml`, `src/main/java/com/softropic/skillars/platform/security/service/JwtSecretBootstrapRunner.java`]
- **`DrillUploadServiceConcurrencyIT`'s new lock-causality test asserts on wall-clock timing.** `deleteVideo_videoRowHeldByAnotherTransaction_waitsOutTheLockBeforeCompleting` asserts `elapsedMillis >= holdMillis - 200` after a hardcoded ~1200ms external lock hold — a known CI-timing-flakiness risk class. Deferred, not fixed: this was a documented, deliberate trade-off after the story's originally spec'd Mockito-based approach proved technically infeasible against a Spring Data JPA repository interface (confirmed by two live failures), and the 200ms tolerance matches this same test file's own pre-existing timing-based tests (`initiateUpload_briefContention_succeedsAfterBoundedRetry` et al.). [`src/test/java/com/softropic/skillars/platform/session/api/DrillUploadServiceConcurrencyIT.java`]
- **`CoachPublicProfilePage.vue` redundantly re-fetches the first page of reviews that `getCoachProfile` already returned.** `CoachProfileDto.reviews` (built server-side via `reviewQueryService.getFirstPageForCoach`, `CoachMarketplaceResource.java:70-81`) is byte-for-byte the same data `listCoachReviews(coachId)`'s default call (`page=0, sort='newest'`) fetches — but the new `onMounted` hook ignores `profile.value.reviews` and fires a second, wholly redundant `GET /api/reviews/coaches/{coachId}` request on every page view. Deferred, not fixed: AC3 explicitly directed calling `listCoachReviews(coachId)` on mount, so the diff is spec-compliant; a fix would mean reading `profile.value.reviews` directly (or dropping the backend's own now-redundant enrichment field) — a small architectural call worth a future cleanup pass rather than a blocking defect. [`src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue`, `src/main/java/com/softropic/skillars/platform/marketplace/api/CoachMarketplaceResource.java`]
