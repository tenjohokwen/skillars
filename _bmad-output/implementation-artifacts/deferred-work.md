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
- VideoDeletionService lazy-autowired initialization order: No explicit guard on self-field initialization timing. Pattern precedent exists in `RadarCompositeCalculationService.java:50-52`, confirming this pattern is established in the codebase. `[DISMISSED 2026-08-29 (skillars-deferred-83 story creation): same Spring-level reasoning as the self-injection-failure bullet above — no explicit guard is needed for a failure mode that prevents application startup entirely.]`
- VideoDeletionService self-field null check: Missing defensive null check before `self.deleteVideo()` dereference. Spring-level concern: if autowiring fails, application won't start; defensive null-check would mask configuration failure with a misleading video-deletion error. `[DISMISSED 2026-08-29 (skillars-deferred-83 story creation): same Spring-level reasoning as the self-injection-failure bullet above — a defensive null-check would mask, not fix, a startup-time configuration failure.]`

## Deferred from: code review of skillars-deferred-11-stripe-card-collection (2026-08-04)
- `PaymentMethodCard.vue`'s `stripeUnavailable` state has no retry affordance short of a full page reload — AC2 only requires the unavailable message + disabled submit, which is satisfied; a retry action would be a UX enhancement beyond spec scope. [`PaymentMethodCard.vue:86-106`]
- No frontend tests (Vitest/Vue Test Utils) were added for `PaymentMethodCard.vue` or the new `payment.store.js` actions (`fetchStripeConfig`, `fetchSavedPaymentMethod`) — real coverage gap on a component with non-trivial lifecycle logic. [`PaymentMethodCard.vue`, `payment.store.js`]

## Deferred from: code review of skillars-deferred-9-frontend-ux-polish (2026-07-02)

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
<!-- skillars-deferred-89 code review (2026-09-01): D2 and D4 above were removed by this story's AC10 pass without any AC authorising it — restored here. They are untagged, still-open Story 7.2 follow-up work; their sibling D3 was left in place. The AC10 pass DID also delete ~34 already-closed/-tagged bullets and 7 spent section headings as ledger hygiene (the "2026-08-24 audit" delete-outright convention) — that prune is retained; only these two open items are put back. -->

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
- D3: Double iteration of `rows` list in `getCoachContributions` — could use SQL window function for in-DB percentage calculation; optimisation, not a correctness issue [`SluContributionService.java`] `[AUDIT 2026-08-27: still two passes by design — buffering all rows first is required to compute per-skill totals before per-row percentages; skillars-deferred-77 AC1 consolidated the UUID conversion into one pass but kept the two aggregation passes]`
- D4: `PerformanceReportsPanel` wrapped in extra `q-card` in parent portal may produce a double-card visual artifact — requires runtime visual verification [`ParentDevelopmentPortalPage.vue`]

## Deferred from: code review of skillars-6-5-video-privacy-rbac-account-deletion-cascades (2026-06-23)
- W2: `@TransactionalEventListener` partial cascade failure — if `AccountDeletionCascadeListener` dies mid-loop, videos not yet reached are silently missed; spec-acknowledged known gap; ops can refire manually [`AccountDeletionCascadeListener.java`]
- W3: Native SQL `@Modifying` queries bypass `@Version` optimistic lock — version column not incremented by native queries; pre-existing codebase-wide pattern; auditing all call sites is a separate hardening task [`VideoRepository.java` and other repos]
- W4: ownerId format ambiguity for mixed-type strings — Task 0 Identity Bridge investigation was a mandatory gate; deferred on assumption Task 0 was completed and format confirmed [`VideoAccessGuard.java`]
- W5: `PROCESSING→READY` backward-compat bypass not removed — spec Task 9 requires a separate PR with ops sign-off (7+ days of zero `video.moderation.bypass` counter); intentionally excluded from Story 6.5 [`VideoLifecycleService.java`]
- W9: `canDelete(null, videoId)` belt-and-suspenders is a no-op for non-HTTP callers — null auth causes broad catch to swallow the re-check; `@PreAuthorize` is the primary enforceable gate; acceptable for current call sites [`VideoDeletionService.java:117`]

## Deferred from: code review of skillars-5-4-skills-radar-display-development-correlation (2026-06-19)
- W4: Skill deactivation silently drops baseline from display — `findAllByActiveTrueOrderByDisplayOrderAsc` excludes inactive skills; baseline re-appears on reactivation [`RadarDisplayService.java:39`] `[DECIDED 2026-08-30 (skillars-deferred-84 story creation): leave as-is. Confirmed with the project owner directly — this is intended design, not a bug. Deactivating a skill removes it from radar display everywhere, full stop; active is the single source of truth for what's currently tracked.]`
- W5: IT `assertThat(minimumSessionCount).isEqualTo(5L)` hardcodes config value — low risk with Testcontainers; `ON CONFLICT DO NOTHING` in V51 migration [`RadarDisplayResourceIT.java:333`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): low-risk test hygiene nit with an already-documented mitigation (Testcontainers + ON CONFLICT DO NOTHING); no live bug, not picked up.]`
- W7: `IMPROVEMENT_THRESHOLD = 3.0` hardcoded — exactly-3-point improvement classified as "no improvement"; explicitly accepted in spec dev notes; configurable in a future story [`DevelopmentCorrelationService.java:33`] `[DECIDED 2026-08-30 (skillars-deferred-84 story creation): leave hardcoded. Confirmed with the project owner directly — no concrete need has surfaced for tuning this value; making it configurable now would be speculative. Revisit only if a real need comes up.]`
- W9: `SkillsRadarChartSpec.js` tests cannot run — vitest / `@vue/test-utils` not installed; explicitly accepted in story completion notes; frontend test-runner setup is a separate initiative `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): duplicate of this project's standing "no frontend test framework" convention, already accepted and re-affirmed across ~15 other stories; not a distinct action item.]`

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection — Round 2 Group A (2026-06-19)
- D1: V49 `CREATE UNIQUE INDEX` blocks startup if phased deploy allowed Monday batch to create duplicate flags between V48 and V49 — mitigated by same-commit deployment of both migrations; negligible in standard CI pipeline [`V49__neglected_skill_unique_open_constraint.sql`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): deploy-ordering edge case already mitigated by same-commit deployment convention; negligible in this project's standard CI pipeline, no live bug.]`

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection (2026-06-19)
- W3: V48 `INSERT INTO platform_config ON CONFLICT (key) DO NOTHING` silently preserves wrong existing value — pre-existing migration pattern across all stories [`V48__development_exposure_dashboard.sql:43`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): pre-existing pattern used identically across every migration in this codebase, not specific to this story; not a distinct action item.]`

## Deferred from: code review of skillars-5-1-slu-engine-skill-taxonomy — Pass 2 (2026-06-18)
- D2: CallerRunsPolicy can block HTTP thread under executor saturation — prior review explicit tradeoff: AbortPolicy silently drops SLU vs CallerRunsPolicy blocks request thread [`AsyncConfig.java:40`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): explicit, deliberate tradeoff from the original review, not a bug; re-confirmed still accepted.]`
- D3: Duration rounding over/under-counts block time — prior review accepted as intentional approximation; documented in dev notes [`SluCalculationService.java:121`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): intentional approximation, re-confirmed still accepted.]`
- D4: Thread.sleep in negative-path IT tests — prior review explicitly deferred; acceptable for negative async tests with no positive signal [`SluCalculationServiceIT.java`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): explicitly deferred by the original review, re-confirmed still acceptable.]`
- D5: No booking_id stored in player_skill_stats — no DB-level idempotency anchor; behavioral gap addressed by idempotency pre-check patch; schema addition out of story scope [`V46__development_module_init.sql`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): behavioral gap already addressed by the idempotency pre-check patch; schema addition remains a deliberate scope boundary, not a live bug.]`
- D7: NUMERIC(10,4) overflow at extreme session attribute values — theoretical at realistic gameplay values with default 0.10 scales [`SluFormula.java`, `V46__development_module_init.sql`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): theoretical only at unrealistic values; re-confirmed no live risk at realistic gameplay scales.]`
- D8: SluRepository inherits deleteAll/deleteById — AC 4 met; comment warns developers; runtime override-to-throw is defense-in-depth only [`SluRepository.java`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): AC already met, defense-in-depth already in place; not a live gap.]`

## Deferred from: code review of skillars-5-1-slu-engine-skill-taxonomy (2026-06-18)
- W2: @Async executor naming ambiguity — explicit `@Async("taskExecutor")` qualifier would eliminate uncertainty; largely covered by the AsyncUncaughtExceptionHandler patch [`SluCalculationService.java:43`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): largely covered by the existing exception-handler patch; re-confirmed no live ambiguity in practice.]`
- W4: Thread.sleep in negative-path IT tests — acceptable for negative async assertions where no positive signal exists; replace with Awaitility + log spy if flakiness is observed in CI [`SluCalculationServiceIT.java:107,125,135,171`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): duplicate of D4 above; no CI flakiness observed to date, condition for revisiting not met.]`
- W5: Platform config IDs 70-72 skip 68-69 — intentional gap; no migration uses 68-69; ON CONFLICT DO NOTHING prevents failures [`V46__development_module_init.sql:51-55`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): intentional gap, re-confirmed no live risk.]`
- W6: player_id and coach_id have no FK constraints on player_skill_stats — intentional for immutable audit rows; cascading deletes would corrupt historical SLU [`V46__development_module_init.sql:19,21`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): deliberate design (immutable audit rows must survive a player/coach deletion), not comparable to coach_radar_preferences/player_radar_composites — those are pure derived state with no independent value, these are historical records. Re-confirmed still correct as designed.]`

## Deferred from: code review of skillars-4-5-intelligent-drill-suggestions-session-templates — Round 2 (2026-06-18)
- W4: Template name inputs missing `maxlength="200"` client-side — server `@Size(max=200)` catches it; generic error is acceptable UX [`SessionTemplateVault.vue`, `SessionBuilderPage.vue`]
- W5: `createTemplate()` store action never sets `error.value` on failure — callers handle errors; minimal impact on store error state [`sessionTemplate.store.js`]
- W6: `SessionTemplate.blocks` null risk if `session.getBlocks()` null — `Session.blocks` is NOT NULL in DB so sessions should never have null blocks; constraint prevents [`SessionTemplateService.java:createTemplate`]

## Deferred from: code review of skillars-4-3-custom-drill-uploads (2026-06-17)
- W2: `existsByVideoId` timing in concurrent `deleteVideo` for shared-video drills — both concurrent deletes may pass the check before either commits, publishing deletion event twice; double-delete is idempotent at Bunny.net; near-impossible in normal usage [`DrillUploadService.java`] `[CLOSED (same-drill variant only) by skillars-deferred-75 AC5 — DrillUploadServiceConcurrencyIT.deleteVideo_concurrentCallsOnSameDrill_doesNotDoublePublishDeletionEvent proves two concurrent deleteVideo calls on the SAME drillId now serialize correctly via the new per-Drill-row PessimisticWrite lock. This item's own wording ("shared-video drills", i.e. two DIFFERENT drills whose drill_video_refs rows point at the same videoId, reachable via DrillLibraryService.cloneDrill:134-136) describes a different race the per-Drill lock does NOT close — two different drillIds acquire two different row locks and do not serialize against each other. That cross-drill variant remains open; a real fix needs a videoId-scoped lock, not a drillId-scoped one.]` `[CLOSED by skillars-deferred-81 AC3 — DrillUploadService.initiateUpload AND .deleteVideo both take videoRepository.findByIdForUpdate(videoId) inside the existing lockRetryer.withBoundedRetry block, lock order Drill→Video documented in-code; proven by DrillUploadServiceConcurrencyIT.deleteVideo_concurrentCallsOnTwoClonesSharingOneVideoId_doesNotDoublePublishDeletionEvent, strengthened with lock-causality timing by skillars-deferred-83 AC2. Found stale during skillars-deferred-87 creation.]`
- W3: Transaction rollback after `videoService.initializeUpload` — provider video created, DB transaction rolls back (including UploadSession), so reconciliation worker cannot find the orphaned provider asset [`DrillUploadService.java`] `[Note 2026-08-27: skillars-deferred-75 AC5 closed the concurrent-request race (W1/W2's same-drill variant) via PessimisticLockRetryer; this item's own scenario (a crash/rollback mid-request, not a concurrent second caller) is a different, still-open gap — needs a reconciliation worker, not a lock.]`
- W6: `@Async` on `VideoPhysicalDeletionListener` uses default `SimpleAsyncTaskExecutor` (unbounded threads) — low volume expected; add named executor if burst deletion scenarios emerge [`VideoPhysicalDeletionListener.java`]
- W8: AC 3 "configurable 60-min timeout" not specifically wired to drill uploads — inherits pre-existing `UploadSession.expiresAt` scheduler; not changed by this story [`platform.video` scheduler]
- W9: `@TransactionalEventListener` silently drops events if called outside a transaction — hypothetical only; `DrillUploadService` is `@Transactional` so all call paths have a transaction [`VideoPhysicalDeletionListener.java`]

## Deferred from: code review of skillars-4-1-drill-library-foundation (2026-06-17)
- D1: `session` schema name is a PostgreSQL non-reserved keyword — works on all tested PG versions; renaming after migration is written would require a destructive V40 migration [`V38__session_module_init.sql`]
- D2: V39 seed drills use `gen_random_uuid()` — non-deterministic IDs differ between environments; migration already written; deterministic UUIDs would require a V40 fix migration [`V39__session_foundation_20_drills.sql`]
- D3: Feature gate config key format relies on `tier.name()` matching DB key suffix exactly — new tier addition requires a matching migration; acceptable by convention; no compile-time enforcement [`DrillLibraryService.java:86`]
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
- W5: Auto-return after wrap-up reloads `selectedWeek` instead of current week — minor UX edge case when coach was browsing a different week [`CoachCommandCenterPage.vue:305`]
- W6: V33 migration uses hardcoded `id = 39` for `platform_config` insert — low collision risk given sequential pattern; validate before deploying to environments with manual config inserts [`V33__session_completion_data.sql:3`]

## Deferred from: code review of skillars-3-5-scheduling-views-timezone-management (2026-06-15)
- W1: Revenue gross calculation ignores variable session pricing (pack discounts, multi-session rates) — spec defines gross as `perSessionPrice × count` (AC 2), variable pricing is out of scope; revisit in a pricing-model story [ProjectedRevenueService.java]

## Deferred from: code review of skillars-3-4-booking-state-machine-sse (2026-06-15)
- Polling fallback has no exponential backoff — 2 s fixed interval is spec-prescribed degraded mode; add backoff if hammering becomes observable in production [booking.store.js]

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group B (2026-06-15)

## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group A (2026-06-15)

## Deferred from: code review of skillars-3-1-coach-availability-management (2026-06-13)
- No date-range guard on `weekStart` GET parameter — far past/future dates are harmless for a 7-day view; address if API is ever exposed to untrusted external callers [AvailabilityResource.java:421]

## Deferred from: code review of skillars-2-3-coach-public-profile-page (2026-06-13)
- N+1 queries — `getPublicProfile` fires 8 sequential DB round-trips; acceptable for single-entity load now, but batch loading or `@EntityGraph` should be considered before Epic 3 traffic ramp [CoachProfileService.java] `[RE-EVALUATED by skillars-deferred-70: not a classic N+1 — getPublicProfile(coachId) is called once per single-coach page view, not once per row in a larger collection. 8 small, indexed, single-row queries for one profile view is unlikely to be the bottleneck it was originally framed as. Left open and unpicked rather than closed — a real fix (EntityGraph/batch-loading) is still reasonable if this page's latency is ever actually measured and found wanting, but should wait for that evidence rather than being done speculatively.]`

## Deferred from: code review of skillars-1-6-age-tier-enforcement-family-data-isolation (2026-06-12)
- Flyway V25 hardcoded IDs 112–114 — `ON CONFLICT (key) DO NOTHING` does not guard against PK collision if those IDs are already taken by different rows with different keys; spec explicitly verified the ID range is safe; established codebase Flyway seed pattern [V25__age_policy_config_seed.sql:1–6]

## Deferred from: code review of skillars-1-5-authentication-jwt-security (2026-06-12)
- Tests use raw `jdbcTemplate` inserts instead of Instancio for test data — project rule violation but tests are functionally correct [AuthResourceIT.java]
- `@Observed` at class level vs per-method on `AuthResource` — class-level is a valid Micrometer pattern; no metric data lost [AuthResource.java]
- `ROLE_ROUTES` duplicated across `LoginPage.vue` and `router/index.js` — DRY violation; divergence would cause infinite redirect loop, but no current divergence
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
- D4: SES failure during `/resend-verification` creates valid DB token with no email delivery — logged at ERROR; resend button available [CoachRegistrationEmailListener.java]
- D5: Frontend 60s cooldown resets on page refresh — UI-only throttle; server-side rate limit is authoritative [CoachEmailPendingPage.vue]
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
- D6: verifyEmail endpoint not @RateLimited — brute-force UUID token space; Group B code [CoachRegistrationService.java]
- D7: resendVerificationEmail accepts EMAIL_VERIFIED users and re-triggers email verification instead of OTP step — flow regression; Group B code [CoachRegistrationService.java]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification (2026-06-11)
- W1: OTP hash uses `SHA-256(otp+userId)` — 6-digit OTP space vulnerable to offline pre-computation if DB is breached; hash scheme is spec-prescribed; rate limiting on `/verify-phone` is primary mitigation [CoachRegistrationService.java:hashOtp]
- W2: `verifyPhone` accepts caller-supplied `userId` with no ownership binding — spec-required field; risk mitigated by rate limiting [VerifyPhoneRequest.java]
- W4: `BaseEntity` TSID + V21 `BIGINT PRIMARY KEY` with no sequence — direct SQL inserts in future migrations or test fixtures require manual TSID generation [V21__skillars_security_extension.sql]
- W5: `ContactDetailSanitizer.PHONE_PATTERN` may redact digit-heavy name segments (e.g. "Type 2 Analyst") — pattern is spec-prescribed; refine when real-world false positives are observed [ContactDetailSanitizer.java]
- W6: `RateLimitingService` uses in-process `ConcurrentHashMap` — not cluster-safe, no eviction; pre-existing infrastructure issue not introduced by this story
- W7: `TokenErrorResponse.errorKey` field alignment with `useErrorHandler` composable — confirm when applying patches; likely aligned by naming convention [ApiAdvice.java]
- W8: `EMAIL_VERIFIED` users have no path to re-request phone OTP via `/resend-verification` — resend endpoint intentionally scoped to email verification only; add dedicated `/resend-otp` endpoint in a later story `[CLOSED by skillars-deferred-89 AC7 — `/resend-otp` + `resendPhoneOtp(userId)` added to coach and player registration (resource + service), mirroring the parent implementation from skillars-deferred-43 exactly: HTTP 200, OTP delivered by email (`*OtpEmailEvent`, no SMS path), `@RateLimited(capacity 3 / 30min, per-role key)`, `saveAndFlush` so a V121 `uq_pot_one_active_per_user` collision → 409 `security.otpResendInProgress` via `ApiAdvice`, locked user → 400 `security.accountLocked`, bad/`!= EMAIL_VERIFIED` → 400 `security.otpMismatch`. `AppEndpoints.PUBLIC_ENDPOINTS` extended with both new paths. Frontend still has no resend-OTP button (standing coverage gap).]`

## Deferred from: code review of skillars-1-2-skillars-design-system-foundation (2026-06-11)
- W1: `.glass-card` still uses `transition: all` — inconsistent with `.hover-lift` narrowed to `transform + box-shadow` in this story; pre-existing in glass.scss, out of story scope [src/frontend/src/css/glass.scss]
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
- DROP DATABASE may fail if services other than `app` hold open DB connections — script stops only `app` before drop; pre-existing script limitation [docs/deployment/backup-restore.md]
- Hardcoded container UIDs (65534/10001/472) not tied to Docker image versions — upstream UID changes (historically seen with Grafana) would silently break subdirectory ownership after snapshot restore [docs/deployment/backup-restore.md] `[AUDIT 2026-08-27: re-verified, still open, still a legitimate low-probability accepted tradeoff — hardcoded UIDs remain untied to image versions in provision.sh/restore-from-volume-backup.sh; monitor upstream image changelogs rather than fix now]`
- APP_CID capture races container registration immediately after `docker compose start app` — health-wait loop can time out on a healthy app; pre-existing script race condition [docs/deployment/backup-restore.md]
- WebhookPermanentFailure Admin API re-trigger has no endpoint or auth reference — Admin API not defined in this story's scope; needs dedicated API documentation [docs/deployment/monitoring.md]
- CallbackRateZero public callback endpoint undocumented — application-specific URL not defined in deployment docs; needs a secrets-reference or application guide entry [docs/deployment/monitoring.md]

## Deferred from: code review of deploy-3-3-external-uptime-monitoring-alert-rules (2026-06-05)
- Double notification risk if Alertmanager added later — Prometheus rules and Grafana alerting both evaluate the same infra alerts; currently no Alertmanager so only Grafana notifies, but future Alertmanager addition would cause duplicate ops notifications for every infra alert
- node_exporter network isolation — shares `skillars-internal` network with app containers; port 9100 reachable by any compromised container; FR-9 required this topology, changing it is out of scope
- DiskDataVolumeHigh requires Hetzner Volume mounted at `/opt/skillars/data` — if volume not provisioned, no metrics series exists and alert never fires; infrastructure provisioning dependency

## Deferred from: code review of deploy-3-1-postgresql-backup-automation (2026-06-04)
- PGPASSWORD exposed via docker exec `-e` flag (visible in `ps aux` for duration of call) — spec-prescribed pattern; would require Docker secrets or a wrapper script to fix [deploy/backup/pg-backup.sh:22]
- Credentials visible in `/proc/<pid>/environ` when `.env` is sourced — project-wide pattern, not introduced by this story
- awscli v1 from Ubuntu apt may have `--endpoint-url` edge cases with Hetzner Object Storage — spec-approved as sufficient; revisit if upload failures occur in production

## Deferred from: code review of deploy-2-2-manual-production-deploy-workflow-with-smoke-test-auto-revert (2026-06-04)
- `Fail workflow` step is unreachable if a notification step throws — job still fails (attributed to the notification step instead), same end outcome, low severity diagnostic issue [`.github/workflows/deploy.yml`:139-143].

## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-04)
- Repo cloned to `/opt/skillars` before Hetzner Volume mounted — volume mount overlays `/opt/skillars/data`; benign today since repo has no `data/` content, but fragile if repo structure changes.

## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)
- Firewall applied after provisioning — SSH port 22 is open to all internet IPs during the provisioning window. Deliberate ordering constraint (Hetzner firewall requires local hcloud CLI run, user may not have local clone yet). Consider documenting the exposure window or restructuring for users who already have a local clone. `[CLOSED by skillars-deferred-89 AC8 — the Step 3 → Step 4 window is documented in `first-time-setup.md` (`#### SSH exposure window`); `provision.sh` section 5 accepts an optional `SSH_ALLOWLIST_IP`, validated with `apply-firewall.sh`'s exact IPv4 regex, cross-checked against the live SSH session's client IP (`$SSH_CLIENT`/`$SSH_CONNECTION`), with explicit `ufw delete allow 22/tcp` before adding the scoped rule for re-run idempotency. Any malformed/mismatched/absent case fails OPEN to `ufw allow 22/tcp` with a warning — never bricks the session. Re-run asymmetry documented.]`
- Repo cloned as root into `/opt/skillars` — `.git` directory sits alongside runtime data and secrets. Pre-existing architectural decision; would require a deploy-user or sparse-checkout approach to change.
- No rollback / disaster-recovery documentation — explicitly out of scope for Story 1.5; belongs to Epic 3 (Stories 3.2 and 3.4).
- git clone root (`/opt/skillars`) contains the volume data subdirectory (`/opt/skillars/data`) — `git clean` could interact with data dirs if `.gitignore` coverage lapses. Pre-existing architecture.

## Deferred from: code review of deploy-1-3-lgtm-observability-stack (2026-06-03)
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
- D6: Service-layer tests use Mockito unit test pattern (`@ExtendWith(MockitoExtension.class)`) — story spec Task 20/21 explicitly defined unit tests; integration coverage provided by `RescheduleResourceIT` [`RescheduleServiceTest.java`, `BookingDuplicationServiceTest.java`]

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
- W3: V60 DDL ACCESS EXCLUSIVE lock risk — `ALTER TABLE main.videos DROP CONSTRAINT / ADD CONSTRAINT` takes table-level ACCESS EXCLUSIVE lock with no `SET lock_timeout`; can cause connection pile-up under concurrent video uploads. [`V60__video_approval_portal.sql`]
- W4: `autoRejectExpired` JPQL uses `current_timestamp` (returns `java.util.Date`) on an `Instant`-typed field — type mismatch; method is explicitly NOT WIRED (comment says so); safe until a scheduler is added. [`VideoApprovalRequestRepository.java:autoRejectExpired()`]
- W5: `@GeneratedValue(AUTO)` on `VideoApprovalRequest` entity vs `UUID DEFAULT gen_random_uuid()` in SQL — Hibernate 6 AUTO may allocate a sequence-based Long for AUTO strategy on non-Long PK; pre-existing entity pattern; verify Hibernate dialect resolves UUID correctly. [`VideoApprovalRequest.java`]

## Deferred from: code review of skillars-7-2-session-payment-lifecycle-credit-wallet (2026-06-24)
- D4: Raw `String` fields for `type` (ParentCreditLedger) and `status` (BookingPayment) instead of Java enums — DB constraint guards correctness; higher migration cost to add enum mapping

### Group 3 adversarial deferred (API + Contracts) — 2026-06-24
- D11: `getActiveCoachTier` returns 204 No Content when no active tier found — spec says "returns empty if none" (ambiguous); 204 is unusual for a typed GET endpoint; more idiomatic would be 404 or 200/null; defer until client null-handling issues surface [`SessionPackPaymentResource.java:101-105`]

### Group 6 adversarial deferred (Tests) — 2026-06-24
- D23: Unit-level idempotency ledger-count guard (`duplicateEvent_idempotencyNoOp` verifies skip but not that ledger mock was never called) — covered by `PaymentWebhookIdempotencyIT`; low value to add unit-level assertion

## Deferred from: code review of skillars-7-3-cancellation-refund-reliability-strikes (2026-06-25)
- D1: `buildSort()` has identical branches for "price" and "rating" — pre-existing; both fall back to `displayName`; price sort is applied in Java post-enrichment [`CoachSearchService.java:buildSort`]
- D3: `GET /coaches/me/strikes` has no pagination — unbounded list; low risk at current scale [`ReliabilityStrikeResource.java`]
- D4: Concurrent strike issuance race — two simultaneous events for the same coach may both read count=N and both fire the threshold event; inherent to non-locking count approach [`ReliabilityStrikeService.java:issue`]
- D5: `CoachCancellationHistory.createdAt` with `@Column(updatable=false)` + `@PrePersist` — in-memory entity is null until DB round-trip if ever used with batch `saveAll`; low risk given single-save usage [`CoachCancellationHistory.java`]

## Deferred from: code review of skillars-7-5-revenue-dashboard-financial-reporting (2026-06-26)
- D1: Running balance incorrect when two ParentCreditLedger entries share an identical createdAt instant and straddle a page boundary — the strict-less-than predicate in sumByParentIdAndCreatedAtBefore excludes the prior-page twin from the opening balance, understating the running balance for the current page by that twin's amount; extremely rare in practice; inherent in the chosen pagination anchor design [RevenueReportingService.java:211]

## Deferred from: code review of skillars-8-1-messaging-module-foundation-conversation-threads (2026-06-26)

## Deferred from: code review of skillars-deferred-3 (2026-07-01)
- D1: V76 partial index predicate hardcodes status literals (`'EXHAUSTED', 'EXPIRED'`) with no in-diff verification against the full status enum — a future added status could silently fall inside the "active" partial index instead of being excluded. [`src/main/resources/db/migration/V76__missing_indexes.sql`] `[CLOSED by skillars-deferred-89 AC6 (generalised). The original target is gone: `V89__drop_legacy_session_packs.sql` (Story Deferred-15 / skillars-11-3) dropped `booking.session_packs_purchased` along with `chk_spp_status` and `idx_session_packs_purchased_coach_expires` (V76, whitelist-converted by V83); the replacement `payment.session_pack_purchases` has no status column. Story creation and the dab5b88 audit both missed V88/V89/V90. Rather than guard a dropped object, AC6 delivers `StatusScopedPartialIndexConventionIT` — a live-schema sweep asserting every status-scoped partial index is a whitelist (`=` / `= ANY`), with an explicit allowlist for V74's intentional `idx_disputes_unique_open_per_booking` blacklist. Project-owner decision 2026-09-01.]`

## Deferred from: code review of skillars-deferred-4 (2026-07-02)
- D3: `@SchedulerLock` and `@Transactional` are stacked on the same method with no explicit `@Order`, so their AOP advisor nesting order (lock vs. transaction boundary) is unspecified — plausible but unconfirmed risk that the distributed lock could release before the DB transaction commits. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingExpiryScheduler.java:40`, `BookingReminderScheduler.java`, `src/main/java/com/softropic/skillars/platform/video/service/BandwidthResetService.java`] `[STALE — the advisor order is already pinned in production by `AsyncConfig.@EnableSchedulerLock(order = Ordered.LOWEST_PRECEDENCE - 100)` against a bare `@EnableTransactionManagement` in `DataSourceConfig`, shipped `7e697d4` (2026-07-02), the same day as this review, with an 8-line comment stating the exact guarantee. ShedLock sorts outermost → lock released only after commit. All three methods also carry `lockAtLeastFor` PT1M–PT2M. skillars-deferred-89 AC4 adds `SchedulerLockTransactionOrderingIT` as a regression guard (asserts a `net.javacrumbs.shedlock` advisor precedes `TransactionInterceptor` on each of the 3 beans); no production change.]`

## Deferred from: code review of skillars-deferred-8 (2026-07-02)

## Deferred from: code review of skillars-11-3-remove-legacy-session-pack-system (2026-08-04)
- D1: `V89__drop_legacy_session_packs.sql`'s `DROP TABLE` has no `IF EXISTS` guard — not blocking (Flyway won't re-run an applied migration, table confirmed empty at this dev/UAT stage), but there's no prior DROP TABLE in this codebase to establish a convention either way; adopt `IF EXISTS` for future destructive migrations. [`src/main/resources/db/migration/V89__drop_legacy_session_packs.sql`]
- D2: Code deletion and the destructive `DROP TABLE` migration ship together with no staged rollout (remove references first, verify, drop table in a later release). Not applicable now — no live/production system exists yet — but relevant once this app has real deployed traffic and rolling deploys. [`src/main/resources/db/migration/V89__drop_legacy_session_packs.sql`]

## Deferred from: code review of skillars-11-1-payment-path-parity-gaps (2026-08-03)
- D1: Partial/mismatched `confirmedCancellationIds` lets `PackSessionService.pausePack()` apply the pause even when not all currently-conflicting bookings are confirmed for cancellation (or the confirmed ids don't match any real conflict) — verified byte-for-byte identical to legacy `SessionPackService.pausePack()`; AC4 explicitly requires mirroring legacy here. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D2: Silent `.orElse(null)`/`.orElse("")` defaulting for missing coach/parent records in `pausePack` and `SessionPackForfeitureScheduler` (blank email, `"Coach"` placeholder) — identical to legacy's own resolution pattern. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`, `SessionPackForfeitureScheduler.java`]
- D3: `pauseStartDate` "in the past" check truncates to UTC day boundary, ignoring coach/parent timezone — byte-for-byte identical to legacy `SessionPackService.pausePack()` lines 205-216. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D4: `configService.getLong("pack.pause.maxDays")` has no defensive default if the config key is missing/non-numeric — identical usage to legacy, same pre-existing risk profile. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D5: `pausePack` holds a pessimistic row lock across booking cancellations and event publishing within one `@Transactional` method — same single-transaction shape as the legacy method this story mirrors. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D7: `SessionPackForfeitureScheduler` doesn't re-verify `expiresAt` immediately before forfeiting inside the per-row transaction, leaving a window where a concurrent extension could still get forfeited — inherent to the legacy-mirrored select-then-per-row-transaction scheduler shape. [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureScheduler.java`]
- D8: TOCTOU between the conflicting-bookings query and the per-booking `cancelDueToPause` calls in `pausePack` — same risk shape as the legacy method being mirrored. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D9: Stringly-typed computed `status` field and hardcoded `CONFLICT_STATUSES` list rather than shared enums — consistent with existing codebase convention; legacy also uses string status constants. [`src/main/java/com/softropic/skillars/platform/payment/contract/SessionPackPurchaseResponse.java`, `PackSessionService.java`]

## Deferred from: code review of skillars-deferred-15-payment-pending-sweeper-accept-path-integrity (2026-08-05)
- D1: `SessionPackExpiryNotifier` stamps `expiryWarnedAt` in the same transaction that publishes the warning event, before the `AFTER_COMMIT` listener actually attempts delivery — a mail-send failure in the listener permanently loses the warning with no retry. Mirrors the identical accepted tradeoff already in `SessionPackForfeitureScheduler`, and is the same platform-wide `AFTER_COMMIT`-listener-reliability gap `skillars-10-2` D1 already tracks generally; not a new pattern introduced by this diff. [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackExpiryNotifier.java:72-87`]
- D2: `BookingService.acceptBooking` / `RescheduleService.acceptReschedule` call `coachProfileRepository.findByIdForUpdate` and then immediately `entityManager.refresh(lockedCoach, PESSIMISTIC_WRITE)` — two separate `SELECT ... FOR UPDATE` round trips on the same row for the same lock. Verified this is the exact idiom already established in `BookingService.createBookingRequest:201-215` (`skillars-deferred-12` AC3), which this diff was explicitly directed to mirror byte-for-byte — a pre-existing pattern, not introduced here. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:279-289`]
- D3: `BookingRepository.findPaymentPendingOlderThan`'s correctness assumes `updatedAt` reflects only the transition into `PAYMENT_PENDING`. `@PreUpdate` stamps `updatedAt` on any field change, so an unrelated write to a `PAYMENT_PENDING` booking would silently reset the stranded-booking clock and let it evade the sweep indefinitely. Verified no such write path exists anywhere in `src/main` today — speculative risk against a future path, not a live defect. [`src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`]

## Deferred from: code review of skillars-deferred-16-messaging-moderation-recovery-identity-safety (2026-08-05)
- D3: AC4's orphaned-profile fail-safety is list-only, producing three different outcomes for one conversation: `getConversations` excludes it, `getMessages` returns it in full (no age-policy lookup at all — only `verifyIsParty` on `parentId`), and `sendMessage` 404s on the throwing variant. The "excluding from parent's list" ERROR log reads like an access-control decision but is not one. Resolving the inconsistency is a product call about what a parent should see for an unresolvable player, beyond AC4's stated scope. [`src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java:108-116,168,215-228`] `[DECIDED 2026-08-29 (skillars-deferred-82 story creation): leave as-is. No live code path today can actually produce this state — nothing deletes a player_profiles row while leaving it referenced (confirmed during skillars-deferred-81 AC4's own research) — so this remains a documented, non-reachable edge case, not a fix candidate.]`
- D4: Rolling-deploy ordering for the new `AdminAlertType.MODERATION_UNRESOLVED`. `V91` correctly widens the CHECK before the enum ships, but nothing gates *creation* of the new alert type until every instance carries the new enum — an older instance reading such a row hits `Enum.valueOf` and 500s the whole `GET /api/admin/queue` and `/queue/summary` page, not just the affected row. The sweeper's first tick fires at startup of the first upgraded instance. Not reachable on the current single-instance Docker Compose deployment. [`src/main/java/com/softropic/skillars/platform/admin/contract/AdminAlertType.java:4`, `AdminQueueService.java:50-58,128-133`]

## Deferred from: code review of skillars-deferred-17-booking-request-slot-payload-timezone-integrity (2026-08-06)

- **D6 — Zero frontend regression coverage for deferred-17's headline fix.** There is no frontend test suite in this repo (no `*.spec.js` outside `node_modules`, no `src/frontend/test`). Every test added or touched by deferred-17 is backend-only, so reverting the `.vue` and `booking.store.js` changes — the edits that actually made booking submission work at all — would leave the entire suite green. The story explicitly forbids introducing a test framework as part of its scope; this restates the standing gap already recorded for `skillars-5-4` W9.
- **D8 — Reconcile the two `canonical_timezone` columns to a single source of truth.** `coach_profiles.canonical_timezone` (`V26__marketplace_coach_profiles.sql:11`) and `coach_availability_windows.canonical_timezone` (`V26:55`) are independently writable — `CoachProfileService.java:90` sets the profile's from Step 1 and `:173` sets each window's from its own Step 4 payload, and `AvailabilityService.updateWindow` never re-syncs a window after a profile-level change. Deferred-17 AC4 worked around the divergence for one page by making display read the profile column; it did not remove the divergence. Deferred from the deferred-17 code review's D1 decision (Mbah, 2026-08-06: accept and document). Real fix needs a migration, a backfill rule for which value wins on existing rows, and a product decision on whether per-window zones are a deliberate feature (a coach who coaches across zones) or an accident of the profile-builder form. `[PARTIALLY ADDRESSED by skillars-deferred-63 AC6, still open]` `[CLOSED by cross-reference (skillars-deferred-70): the "still open" write-path half of this item was decided one day later under the skillars-deferred-63 story-review heading below — see that section's own CoachProfileService.saveStep4 bullet, tagged DECIDED 2026-08-25. Per-window coach timezone is a deliberate feature; saveStep4 keeps trusting the request payload; no further action planned.]` Product decision made (profile is sole source of truth, not a deliberate per-window feature) and existing diverged rows backfilled via `V103__availability_window_timezone_backfill.sql` — but `saveStep4` still writes each window's `canonicalTimezone` from the request payload rather than the profile, so new divergence can still occur going forward; story-review found `ProfileBuilderStep4.vue` ships a real, coach-editable per-window timezone picker that a `saveStep4` write-path fix would need to be coordinated with (see the new item filed below). Note the display-side risk is largely contained once the slot label carries `timeZoneName` — instants are absolute, so no wrong moment is ever transmitted, only a differently-labelled one.

## Deferred from: code review of skillars-deferred-18-availability-slot-timezone-integrity (2026-08-07)

- **D2 — `blocks` week-scoping and the fetch bounds both hang off an unordered `findByCoachId`.** `AvailabilityService.java:85-86` derives `weekStartExact`/`weekEndExact` from `zoneId`, i.e. from `windows.get(0)`, and `CoachAvailabilityWindowRepository.findByCoachId` issues no `ORDER BY`. For a coach with windows in two zones, two identical requests can therefore return different `blocks` sets and use different fetch bounds purely from row-order variation; and a block that visibly punches a hole in a divergent-zone window's slot is absent from the response's `blocks` array, so `AvailabilityManagerPage.vue` renders a gap it cannot explain. This story narrowed the outer zone's role from a fetch bound to a response filter but did not remove the arbitrariness, which is the same divergence class as D8 below and is not independently fixable — picking a deterministic zone requires deciding *which* column is authoritative. Adding `ORDER BY id` to `findByCoachId` would make it deterministic-but-still-arbitrary, which is arguably worse (it hides the problem). Blocked on D8. `[CLOSED by skillars-deferred-65 AC3, for week-scoping only: AvailabilityService.getAvailabilityCalendar now derives weekStartInstant/weekEndInstant/weekStartExact/weekEndExact from the coach profile's own canonical_timezone instead of windows.get(0), so week-scoping bounds are no longer row-order-dependent. CoachAvailabilityWindowRepository.findByCoachId still issues no ORDER BY, so per-window display order in the response's windowResponses is unaffected and remains a separate, still-open display-order nondeterminism — not fully resolved by this fix]`

## Deferred from: skillars-uat-1-admin-bootstrap-and-onboarding-unblock (2026-08-10)

Found while implementing the story. Each was examined and deliberately left alone; none blocks UAT.

- **D2 — two of AC6's four rewritten `default` arms are unreachable and therefore untested.** `resolveLastReadAt` (`MessagingService.java:439`) and `updateLastRead` (`:456`) both sit behind a `verifyIsParty` call that throws first — `updateLastRead` runs after the guard inside `getMessages`, and `resolveLastReadAt` is reached only from the summary mapper. No caller can drive either with an unrecognised role. Changing them was still correct (all four now raise the same type, so whichever a future caller reaches first behaves identically), but `MessagingAccessControlIT.unrecognisedRole_yields403NotFatal` deliberately asserts only the two reachable arms — `MessagingService.verifyIsParty` and `MessagingReportService.verifyIsParty` — rather than faking a reachability that does not exist. Recorded so a later reviewer does not read the gap as an oversight.

- **D3 — `AdminBootstrapRunner` cannot elevate an existing account, by design.** If the configured email already belongs to a coach, parent or player, the runner skips rather than granting `ROLE_ADMIN` to that row. This is deliberate (an "upgrade this user to admin" path is a different and riskier feature), but it means an operator who typos the address into an existing user's email gets a silent skip with only an INFO line to explain it. If admin provisioning ever becomes routine, it needs a real admin-management surface, not a wider bootstrap.

## Deferred from: skillars-uat-2-session-duration-and-booking-slot-integrity (2026-08-10)

Found or deliberately left while implementing the story. None blocks UAT.

- **D3 — `AvailabilityService.computeAvailableSlots` anchors its grid per segment, which makes slot start times shift when a coach edits a block.** Intended and documented (see the method's javadoc and AC2): the post-block run starts when the coach actually becomes free, so a 12:00–12:45 block yields 12:45, 13:45, … rather than discarding the 12:45–13:00 fragment. The consequence is that adding or removing a block changes the start times of every downstream slot that day, so a parent who had a specific hour in mind may not find it after a coach edits their calendar. AC3 explicitly declines to enforce grid alignment on the write paths for this reason — an alignment rule would retroactively invalidate a booking whose slot was legal when it was made, and `RescheduleService`/`BookingDuplicationService` both write times derived from existing bookings. If UAT feedback says the shifting grid is confusing, the fix is a product decision (window-anchored grid, accepting the dropped fragments), not a bug fix.

- **D4 — the batch path still has no create-time cross-booking overlap check.** The single path has one (`BookingService.createBookingRequest`); `createBatch` does not, and AC4 deliberately left it that way. Batch rows are created `REQUESTED`, and `V87`'s exclusion constraint deliberately excludes `REQUESTED` — its own comment records that two overlapping `REQUESTED` bookings competing for a slot is expected in-band behaviour that the accept-time re-check resolves, and `acceptAll` already runs that re-check against `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED`. Adding a create-time check would reject legitimate competing requests. Recorded so a later reviewer does not read the asymmetry as an oversight. AC4 *did* add the intra-batch overlap check (two slots in the same batch overlapping each other), which is a different and unambiguous case.

- **D5 — `ConfigService`'s 5-minute cache TTL makes a platform-default change look like it did nothing.** `booking.session.defaultDurationMinutes` is read through `ConfigService.getBoundedLong`, and `ConfigService` refreshes on a `@Scheduled(fixedDelayString = "${app.config.cache-ttl-seconds:300}")`. An admin who changes the value and immediately reloads the availability calendar sees the old slot length and will reasonably conclude the setting is broken. `updateConfig` does call `invalidate()`, so a change made *through the config API* is immediate; a change made directly in the database is not. Documented in the V93 migration comment; a real fix is either an admin-UI note or narrowing the TTL, both of which are wider than this story.

- **D6 — no frontend test coverage for any of this story's `.vue` changes.** Standing gap (`skillars-deferred-17` D6, `skillars-5-4` W9, `skillars-uat-1`): there is no frontend test suite in this repo, so `ProfileBuilderStep3.vue`'s duration select, `BookingRequestPage.vue`'s merged own-booking rows and coach-timezone week bounds, and `ParentBookingsPage.vue`'s derived read-only reschedule end are verified by code reading and a successful production build only. Reverting all three would leave the entire suite green.

- **D7 — `docker-compose.local.yml` now needs a `redis` override it did not need before.** AC6 replaced the `redis-data` named volume with a bind mount at `/opt/skillars/data/redis`, which is a production path. `docker-compose.local.yml` overrides the volume of every other stateful service but had never needed one for redis, so without the new `skillars-local-redis` override added here, running the local stack would have created `/opt/skillars/data/redis` on a developer's own machine. Fixed in this story; recorded because it is the general hazard of putting absolute host paths in the base compose file — any future service that moves onto the Volume needs the same treatment, and nothing enforces it.

## Deferred from: code review of skillars-uat-2-session-duration-and-booking-slot-integrity — Group A (2026-08-10)

Backend duration-model files only (`SessionDurationResolver`, `V93` migration, `AvailabilityService`, `BookingService`, `BookingBatchService`, `RescheduleService`, `BookingError`, `ProfileBuilderStep3Request`, `CoachPricing`, `CoachProfileService`), reviewed against AC1–AC4. Group B (backend tests) also reviewed, no additional defers there. Group D (ops/infra/docs) pending.

- **Session duration / availability windows are resolved once and not re-validated before persistence.** `[CLOSED (partially) by skillars-deferred-69 AC7: BookingBatchService.createBatch now re-fetches requiredDuration and windows fresh and re-validates every slot's duration+availability immediately before the batch/booking rows are persisted, inside the same transaction — this narrows the window from "the whole request" to "between the two resolves," it does NOT eliminate it. CoachAvailabilityWindow/CoachAvailabilityBlock carry no @Version and no locked-read method exists for them anywhere in the codebase, so a coach edit landing between the re-check and the actual commit remains unseen; full elimination would require lock support on CoachAvailabilityWindow, out of this story's scope. The separate GET-availability-calendar-vs-POST-booking staleness (AvailabilityService.getAvailabilityCalendar) is unrelated and remains fully open.]` `BookingBatchService.createBatch` resolves `requiredDuration` and fetches `windows` once before the per-slot loop and reuses both for the whole batch; the actual writes happen afterward via `TransactionTemplate`. Separately, `AvailabilityService.getAvailabilityCalendar`'s resolved `slotLength` and the later `BookingService`/`BookingBatchService` calls that independently re-resolve `requiredDuration` have no version or ETag tying a GET availability response to a subsequent POST booking — a coach changing their session length in between can turn a slot the parent just saw into a rejection. Both are the same underlying class of read-then-write staleness that already exists elsewhere in the booking module (e.g. availability windows/blocks changing between GET and POST); not newly introduced by this story and not actionable within its scope. `[CLOSED (further) by skillars-deferred-71 AC2, for single-booking creation only: CoachAvailabilityResponse now carries an availabilitySignature (a deterministic string built from the current windows + resolved session duration, no schema change), which CreateBookingRequest can optionally echo back; BookingService.createBookingRequest compares it against a freshly-recomputed signature before the existing window/duration checks and throws a dedicated BookingError.AVAILABILITY_CHANGED (distinct from the generic rejection) on mismatch, and the frontend re-fetches availability on that specific error. RescheduleService and BookingBatchService remain unwired — see this AC's own scope note for why — so the GET-vs-POST staleness gap for those two paths is not part of this closure.]` `[CLOSED (further, for BookingBatchService specifically) by skillars-deferred-72 (confirmed during skillars-deferred-81 story creation, 2026-08-28): CreateBatchRequest carries availabilitySignature, BookingBatchService.createBatch:142-147 compares it and throws AVAILABILITY_CHANGED on mismatch, and booking.store.js's submitBatch (:558) sends the value end-to-end from the frontend. This landed unannotated over a week before skillars-deferred-81's creation pass found it live in code rather than trusting this ledger entry's stale "unwired" text. RescheduleService remains genuinely out of scope per AC2's own scope note (no GET-then-POST seam exists for reschedule) — not a gap, by design.]`

## Deferred from: skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)

Found or deliberately left while implementing the story. The first item is an **operational finding, not a code defect, and it is the most consequential thing in this list.**

- **D3 — `CAPTURE_PENDING` has no automated exit.** A reserved row that never completes blocks the parent's cancel (AC2 returns 409 for as long as it stands), holds the coach's slot, and is refused by the sweeper. AC5 escalates it on every 15-minute sweep via `booking.payment_pending.unrecoverable{reason="CAPTURE_UNCONFIRMED"}` and Scenario 4 of `runbook.md` documents the manual resolution, but nothing times it out. **Deliberate.** Every automatic option is worse: declining could charge a parent for nothing, confirming could give away an unpaid session, and re-charging could take the money twice. Only a human can read the Stripe side. Recorded so the absence reads as a decision rather than an oversight.

- **D7 — `PaymentWebhookIdempotencyIT` seeded no `booking.bookings` rows at all.** Found in Task 0's triage; the story's regression table predicted only that it might assert "exactly N rows". The class mocks `BookingService` specifically so transitions do not need a real booking, which meant `reserveCapture`'s locked read found nothing, returned `BOOKING_NOT_PENDING`, and left zero payment rows. Fixed by seeding a real `PAYMENT_PENDING` booking in the one test that reaches Stripe — the fixture, not the check, per `uat-2`'s recorded lesson. Recorded because it generalises: **any test that drives a settlement path now needs a real booking row**, which was not true before this story, and the other two tests in that class pass only because they never reach Stripe (full credit cover and pack-funded both skip the reservation).

- **D9 — no frontend test coverage.** Standing gap (`uat-1`, `uat-2` D6, `deferred-17` D6, `deferred-18`): there is no frontend test suite in this repo. This story touches **no** `.vue` file, so unlike its two predecessors nothing here is verified by code reading alone — recorded only to note that the gap is unchanged, not that it bit this story.

## Deferred from: code review of skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)

Adversarial code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 13 patch findings were resolved in the same pass; these 6 were deliberately left open. (`DisputeService`'s unguarded `findById` was independently raised by this review too — already tracked above as D5, not duplicated here.)

- **V94's `DROP CONSTRAINT`/`ADD CONSTRAINT` pair on `payment.booking_payments.chk_bp_status` takes an `ACCESS EXCLUSIVE` lock and revalidates the CHECK against every existing row**, blocking reads and writes to the table every settlement path writes to, for the duration. Acceptable at the current UAT-stage row count; worth an online-migration strategy (`ADD CONSTRAINT ... NOT VALID` + separate `VALIDATE CONSTRAINT`) before this table is large in production. [`src/main/resources/db/migration/V94__booking_payment_capture_pending.sql`]

- **`reserveCapture`'s `REQUIRES_NEW` + `PESSIMISTIC_WRITE` opens a second pooled connection per attempted reservation, held up to the 5s `lock.timeout` under contention**, with no discussion anywhere of connection-pool sizing. A burst of concurrent settle attempts against contended booking rows is a new resource-exhaustion vector this change introduces. Speculative and load-dependent — no load test exists either way. [`src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java:73-105`]

## Deferred from: code review of skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)

All 13 patch findings were applied; these are the review's own deferrals plus one item the patch round produced.

- **D11 (from the patch round, not the review) — `PaymentPendingSweeper.sweepOne`'s row lock is justified by reasoning, not by a test.** The review correctly found that `sweepOne` decided on the *absence* of a payment row and then wrote one, while `reserveCapture` concurrently decided on the absence of the same row and inserted `CAPTURE_PENDING` — so an unlocked sweeper could commit `CHARGE_FAILED` over a granted reservation (`save()` on an assigned `@Id` with no `@Version` is a `merge()`, i.e. an UPDATE, not a failing INSERT). Fixed by re-reading under `findByIdForUpdate`, the same lock `reserveCapture` takes. **The IT written to prove it was deleted for passing unchanged against the unlocked code**: both threads start on one latch, but the sweeper first reads config and runs the stranded-booking query while `reserveCapture` goes almost straight to its insert, so the reservation committed first every time and the correct outcome came from the payment-row check rather than the lock. That the six existing `PaymentPendingSweeperTest` cases all broke when the lock landed does confirm the production path changed, but a mock swap is not proof of serialisation. The lock's read-then-write window is microseconds wide and not reachable from a test at this level; proving it would need either production instrumentation (a test-only hook inside `sweepOne`) or a DB-level fault injector, both of which are their own change. Recorded rather than left implicit, because this project has now three times found a lock whose test passed without it (`deferred-13`, `deferred-15`, and this one).

- **D13 — V94's `DROP CONSTRAINT`/`ADD CONSTRAINT` pair takes an `ACCESS EXCLUSIVE` lock and revalidates the CHECK against every existing row** on what will become the busiest settlement table. Acceptable at current UAT-stage size (the table is small and the platform is not live), but revisit with an online-migration strategy — `ADD CONSTRAINT ... NOT VALID` followed by `VALIDATE CONSTRAINT` — before this table is large in production. [`V94__booking_payment_capture_pending.sql`]

- **D14 — `reserveCapture`'s `REQUIRES_NEW` + `PESSIMISTIC_WRITE` opens a second pooled connection per attempted reservation**, held up to the 5 s lock timeout under contention, with no pool-sizing analysis behind it. On the batch path that is one extra connection per credit-funded booking, taken sequentially. Load-dependent and speculative without a concurrency/load test, but it is a new resource pattern on the busiest path and should be measured before the platform carries real volume. [`BookingPaymentPersistenceService.java:73-105`]

## Deferred from: skillars-uat-4-i18n-locale-and-message-resolution-integrity (2026-08-12)

## Deferred from: code review of skillars-uat-4-i18n-locale-and-message-resolution-integrity (2026-08-12)

## Deferred from: skillars-uat-5-player-self-booking story creation (2026-08-12)

Recorded per AC5 — items explicitly excluded from this story's scope, not fixed.

- **D1 — Session-pack purchase by a self-registered player.** `payment.session_pack_purchases` requires both `parent_id NOT NULL` and `player_id NOT NULL` (`V62__session_payment_credit_wallet.sql` + live entity `SessionPackPurchase.java`), and `SessionPackPaymentService.purchasePack` hard-requires `playerProfileRepository.findByIdAndParentId` ownership. Unlike the booking-ownership check this story widened, this one is not a simple XOR branch: the pack itself has no `user_id`-style self-ownership column at all. A self-booking player pays **per-session by card only** (this story's AC2); packs remain a separate, larger schema change (a new nullable `player_owner_id`-style column plus the same XOR-branch treatment `PlayerProfile` already has). [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`, `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql`]

- **D2 — Credit wallet (`payment.parent_credit_ledger`) has no player-self-booking equivalent.** Keyed by `parent_id` alone with no `player_id` dimension — it is a per-parent shared balance across children, a concept that does not map onto a single self-booking adult. `CreditWalletResource` stays parent-only; a self-booking player's `effectiveCreditsRemaining` correctly reads 0/none since they will never have ledger rows. No fix needed unless/until a product decision creates a player-scoped credit concept. [`src/main/java/com/softropic/skillars/platform/payment/service/CreditWalletService.java`]

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

## Deferred from: code review of skillars-deferred-22-messaging-role-guard-payment-idempotency-and-resource-integrity-fixes (2026-08-14)

- **`initiateUpload`'s new orphaned-reservation release doesn't confirm the video row itself still exists before publishing `VideoPhysicalDeletionEvent`.** Pre-existing pattern — mirrors `deleteVideo`'s own unmodified check-and-publish shape exactly, which has always published on `!existsByVideoId(...)` alone. [`src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java:92-93`, `:118-119`] `[CLOSED by skillars-deferred-89 AC3 — `lockedVideo.isPresent()` added to the publish condition in both `initiateUpload` (hoisted `Optional`) and `deleteVideo` (captured `findByIdForUpdate` result). Defence-in-depth: `drill_video_refs.video_id` has no FK (V38). The motivating "already physically deleted" scenarios are in fact unreachable today — `AdminVideoService.deleteVideo` soft-deletes and there is no hard-delete of `videos` in `src/main`; today's worst case was a caught `VideoNotFoundException` + log noise.]`
- **`SessionPlanService.buildResponse`'s new response-map derivation relies on `session.getBlocks()` matching the pre-save `blocks` list used to build it.** Pre-existing coupling — the pre-diff code had the same pre-save-metaMap/post-save-`session.getBlocks()` relationship; this story's refactor only changed how the drill-lookup maps are built, not this dependency. [`src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java:186-234`]

## Deferred from: code review of skillars-deferred-23-flaky-perf-test-dead-code-and-ops-hygiene-fixes (2026-08-14)

## Deferred from: code review of skillars-deferred-24-dead-subscription-column-stripe-metadata-and-backup-guard-fixes (2026-08-15)

- **The new `GUARD_PATH` existence/readability guard block is duplicated verbatim across all 5 caller scripts** rather than factored into one shared function. Spec-directed (AC4's code block explicitly prescribes this exact per-caller shape); a DRY refactor would need its own sign-off, not a silent deviation from the story's prescribed fix. [`deploy/backup/pg-backup.sh`, `volume-backup.sh`, `restore-from-dump.sh`, `restore-from-volume-backup.sh`, `prune-backups.sh`]

## Deferred from: skillars-deferred-26-defensive-guards-input-hardening-and-test-coverage-fixes story creation (2026-08-15)

- **D1 — `DrillMetadata.repDensity` cannot represent "coach never set this" at all — it is a Java primitive `int`, not `Integer`.** Found during this story's AC4 spec audit (adversarial review of the story's own AC text, pre-implementation): a missing key in the incoming JSON payload deserializes silently to `0` via Jackson, and an explicit JSON `null` would throw a deserialization exception rather than pass through — so a `repDensity != null` guard anywhere downstream (frontend or backend) can never observe the "unset" case as `null`; it is indistinguishable from a legitimately-zero drill today and will remain so under the current contract. AC4 of this story added a frontend-only defensive guard (protects against `undefined`/`null` arriving from a stale cache, a manually-edited dev fixture, or a future API contract change) but explicitly could not close this gap — doing so needs a backend change: make `repDensity` a nullable `Integer` (propagating through `DrillMetadata`, its JSONB Hibernate mapping, and every backend site that reads it arithmetically) or add an explicit "no density data" signal, plus a decision on whether that is a real product need (do coaches uploading custom drills currently have any path that leaves `repDensity` unset, or does upload validation already require it?). [`src/main/java/com/softropic/skillars/platform/session/contract/DrillMetadata.java:12`, `src/frontend/src/components/session/DrillDetailPanel.vue`] **[AUDIT 2026-08-24: skillars-deferred-63 story creation investigated this live.** No live path constructs a `Drill` with coach-submitted metadata today — `grep -rn "new Drill(\|Drill.builder()\|new DrillMetadata(" src/main/java` finds exactly one non-test construction site, `DrillLibraryService.java:129`'s `clone.setMetadata(source.getMetadata())`, which copies an already-persisted drill's metadata rather than deserializing a fresh payload; `DrillUploadService`/`DrillUploadResource` (the only "drill upload" surface) handle the drill video file only — `DrillUploadInitiateRequest` has no `DrillMetadata` field at all. Every `Drill.metadata` is populated exclusively by migration/seed data under full app-team control. The "coach never set repDensity" scenario has no reachable trigger today — not picked up as an AC by skillars-deferred-63. Re-open if a future story adds a real coach-facing metadata-submitting endpoint.]**

## Deferred from: code review of skillars-deferred-30-error-toast-mapping-and-repository-boundary-test-coverage-fixes (2026-08-18)

- **[OWNED BY skillars-deferred-31 AC7] `CoachBookingRequestsPage.vue`'s `handleAccept` calls its post-failure refresh unguarded — a rejecting refresh throws an unhandled promise rejection out of the catch block.** Found by a second, independent code review pass (2026-08-18): `await bookingStore.loadCoachBookingRequests()` at `CoachBookingRequestsPage.vue:164` sits inside `handleAccept`'s `catch` block with no try/catch of its own. If the accept call fails AND the immediately-following refresh call also fails (e.g. a transient network blip right after a real rejection), the refresh's rejection propagates unhandled rather than being absorbed — the coach's toast for the original failure still fires (it's already been queued before the `await`), but the unhandled rejection is a latent robustness gap a browser console/error-monitoring integration would surface as noise at best, an uncaught exception at worst. Pre-existing pattern, not introduced by `skillars-deferred-30`'s edits (which only added `errorKey` branching before this line). A one-line `.catch(() => {})` closes it. [`src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:164`] `[WITHDRAWN by skillars-deferred-31 AC7 — the described defect cannot occur]` The item rests on the premise that `loadCoachBookingRequests()` can reject. It cannot: `booking.store.js:302-314` wraps the whole call in `try { … } catch (e) { coachRequestsError.value = e }` with **no rethrow**, so the `await` at `CoachBookingRequestsPage.vue:164` always resolves and no unhandled rejection is possible. The prescribed `.catch(() => {})` would be dead code, and a future reader would have to re-derive why it is there. The identical unguarded pattern in `handleDecline` (`:176`) is harmless for exactly the same reason and was likewise not "fixed". This is the **same false premise** the `skillars-deferred-30` code review withdrew a sibling item for on the same day, reached independently by a different review pass hours later — the store's swallow-and-never-rethrow contract is evidently not obvious from the call sites, so AC7 also added a CONTRACT comment above `loadCoachBookingRequests`/`loadCoachSchedule` in `booking.store.js` stating that neither loader ever rethrows and callers therefore need no guard. The live residual these two withdrawn items were both circling — a silent failed refresh — is closed by AC1.

## Deferred from: code review of skillars-deferred-33-messaging-lock-ordering-booking-status-mapping-and-german-video-i18n-gap (2026-08-18)

- **`V97__drop_booking_refund_eligibility_and_amount.sql` drops two columns with a bare, uncommented `DROP COLUMN` pair under an `ACCESS EXCLUSIVE` lock, and its "nothing reads these columns" justification was verified only via grep of `src/main/java`**, not any SQL views/reporting/export tooling outside the Java codebase. Deferred rather than patched: `V96` (this story's own cited precedent) has identical zero commentary, and the `ACCESS EXCLUSIVE`-lock-class concern is already tracked in this ledger against `V94` as an accepted, not-yet-actionable gap at current table size. [`src/main/resources/db/migration/V97__drop_booking_refund_eligibility_and_amount.sql`]

## Deferred from: code review of skillars-deferred-37-batch-accept-result-map-pruning-and-rebuild-cost-bound (2026-08-19)

- **`batchAcceptResultsByBatch` pruning (`skillars-deferred-37` AC1) only runs on `loadCoachBookingRequests`'s success path, per AC1's own explicit requirement mirroring the function's existing stale-on-failure CONTRACT — so a streak of failed refreshes lets the map keep growing unboundedly for as long as the failures persist.** The exact growth this story exists to bound is not airtight under a specific real-world condition (a flaky network/backend). Spec-intentional, not an oversight in the diff; low priority, consistent with this story's own documented tradeoffs. [`src/frontend/src/stores/booking.store.js:321-353`]

## Deferred from: code review of skillars-deferred-38-coach-refresh-request-sequencing-guard (2026-08-19)

- **No automated test coverage for `loadCoachBookingRequests()`'s concurrency/request-sequencing guard.** Standing repo-wide gap — no frontend test harness exists for `booking.store.js` (same accepted gap `skillars-deferred-35`/`36`/`37` recorded). [`src/frontend/src/stores/booking.store.js:326-371`]

## Deferred from: code review of skillars-deferred-40-coach-action-timeout-hardening-radar-confidence-accuracy-and-video-bandwidth-tracking (2026-08-20)

- **Migration `V98`'s backfill is an unbatched, unchunked full-table `UPDATE` joined against `radar_assessment_entries`, grouped by `(player_id, skill_code)`.** Same scaling shape as `Def10`'s already-accepted "full-table lock risk" concern for `video_quotas`, applied here to `player_radar_composites`. Not blocking at current expected table size; worth tracking if the table grows large enough for migration-time locking to matter. [`src/main/resources/db/migration/V98__player_radar_composites_distinct_coach_count.sql`]

## Deferred from: code review of skillars-deferred-43-player-registration-otp-coverage-and-self-profile-fetch-caching (2026-08-20)

- **No automated test coverage added for `playerStore.js`'s new `fetchSelfPlayerId()`/`resetSelfPlayerId()` caching logic, or for `MainLayout.vue`'s new `resetSelfPlayerId()` call in `handleLogout()`.** Pre-existing gap — matches this repo's standing, repeatedly-accepted absence of frontend test infrastructure (the same reasoning `skillars-deferred-35`/`36`/`37`/`38` have left in place for `booking.store.js`). Ships with zero coverage despite this diff's own stated framing of the cache carrying a cross-account booking-misattribution risk. [`src/frontend/src/stores/playerStore.js:24-33`, `src/frontend/src/layouts/MainLayout.vue:301`]

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

- `persistPaymentFailure`'s new `@Transactional(propagation = Propagation.REQUIRES_NEW)` holds two
  concurrent physical DB connections per payment-deduction failure (the suspended caller transaction plus
  this method's own new one) instead of one. Deferred: this is the same tradeoff this class's two existing
  `REQUIRES_NEW` methods (`reserveCapture`, `declineBatchBooking`) already accept, not a new risk
  introduced by this story; revisit only if connection-pool exhaustion during a real payment-gateway outage
  is ever observed in practice.
  [`src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java:206-207`]
- `deferred-work.md` and `sprint-status.yaml` accumulate indefinitely via ever-longer single lines (one
  line in each file already exceeds 40,000 characters) rather than being pruned or archived. Deferred:
  pre-existing project-wide convention followed identically by every prior story in this ledger, not
  specific to this diff; fixing it would mean changing the ledger/status-tracking convention itself across
  the whole project, out of scope for any single story.
  [`_bmad-output/implementation-artifacts/deferred-work.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml`]

## Deferred from: skillars-deferred-63 story creation (2026-08-24)

Filed during a multi-round product-decision discussion (2026-08-24) covering nine `deferred-work.md`
items the project owner explicitly decided, split across `skillars-deferred-62` (the
`jakarta.persistence.lock.timeout` fix, too large to bundle) and `skillars-deferred-63` (the other seven).
Two items surfaced during that discussion that neither story implements — one filed fresh here and picked
up in the same pass per the `skillars-deferred-53` precedent (found while verifying, filed and picked up
immediately), one filed fresh and deliberately left open:

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

- **`PessimisticLockRetryer`'s retry loop sleeps while still holding the transaction's pooled JDBC connection.** Up to ~3.2s (the documented default retry budget) of connection-pool time is spent purely sleeping under contention per call. A consciously-chosen tradeoff of the savepoint-based retry-in-place design — the Dev Agent Record documents that Spring's declarative `Propagation.NESTED` was investigated and found unavailable (`DefaultJpaDialect` has no savepoint support), which is why retry happens in-place rather than via a connection-releasing mechanism. Worth tracking if connection-pool pressure becomes visible under real load. [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java`]
- **`PessimisticLockRetryer.withBoundedRetry`'s `Supplier<T>` idempotency/side-effect-free contract is documented only in a javadoc comment, not enforced by the method signature.** Nothing stops a future caller from passing a supplier with real side effects (an external call, an event publish, a write) that would then be silently re-executed on every retry attempt. All 16 current call sites are read-only (a `findByIdForUpdate` plus optional `refresh`), confirmed by this story's own code review — speculative future-risk, not a current violation. [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java`]
- **A JDBC `setSavepoint`/rollback-to-savepoint call itself failing (e.g. genuine connection loss) propagates unretried as an opaque 500**, since `PessimisticLockRetryer` only catches `PessimisticLockingFailureException`. Arguably acceptable — this represents genuine infrastructure failure rather than lock contention — noted for awareness rather than as a defect. [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:63-64,72`]

## Deferred from: code review of skillars-deferred-65-pack-selection-parity-timezone-validation-strictness-and-availability-week-scoping-fixes (2026-08-25)

- **`ZoneId.getAvailableZoneIds()` still contains DST-blind fixed-offset-equivalent forms outside `+HH:MM` notation that `@IanaTimezone`'s tightening does not reject.** AC2 tightened the validator to require `getAvailableZoneIds().contains(value)`, closing `"+01:00"`/`"GMT+2"`/`"Z"`-style forms, but the set also contains entries like `"Etc/GMT+1"` (verified: `contains("Etc/GMT+1")` is `true`) which are themselves fixed-offset, DST-blind zones under a region-shaped name — the same accepted-gap class the AC's own text already names for `"Navajo"`, just never called out for the `Etc/GMT±N` block specifically. Consistent with the tighten-only decision (an exclusion-list closing every such alias family was explicitly not what was decided), so not a defect against this story — flagged in case a future strictness pass wants a tighter allow-list (e.g. reusing `CoachProfileService`'s continent-prefix filter) instead of raw `getAvailableZoneIds()` membership. [`src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidator.java`]
- **`@IanaTimezone`'s correctness now depends on `ZoneId.getAvailableZoneIds()`'s contents, which can change across JDK/tzdata updates, with no stability-risk discussion recorded anywhere.** A `canonicalTimezone` value that validates today could in principle stop validating after a routine JDK patch that renames or drops a legacy alias. The original, looser `ZoneId.of`-only check had the same JDK-version dependency, just a laxer one — this is an architectural characteristic of the design, not something newly introduced by AC2's tightening, but worth a documented floor (or a pinned tzdata version note) if it ever causes a real incident. [`src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidator.java`]

## Deferred from: story-review and implementation of skillars-deferred-63-product-directed-fairness-and-consistency-fixes (2026-08-24)

- **A coach still cannot contest, rebut, or even view a dispute a parent already filed on a booking.** `skillars-deferred-63` AC5 gave a coach a symmetric first-raise right on a booking with no dispute yet, but `DisputeService.raiseDispute`'s `disputeRepository.findOpenByBookingId(bookingId)` check has no `raisedBy` filter — it 409s (`disputes.alreadyRaised`) on *any* open dispute regardless of who raised it — and `getDispute` 403s any caller who isn't the original raiser (`dispute.getRaisedBy().equals(requesterId)`). So a coach cannot respond to, or even read, a dispute a parent already filed against them through this API. Found during `skillars-deferred-63`'s own story-review (2026-08-24). A real fix is a genuine two-sided-dispute design question, not a mechanical change: does a second, opposing dispute on one booking need to be resolved jointly with the first? does the admin `AdminDisputeDetailDto`/UI support two open disputes on the same booking? [`src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java:76-79,107-120`] `[DECIDED 2026-08-25: keep first-raiser-wins as final; no two-sided contest mechanism planned]`
- **`CoachProfileService.saveStep4` still writes each availability window's `canonicalTimezone` from the request payload rather than from the coach's own profile, so new profile/window timezone drift can still occur.** `skillars-deferred-63` AC6 backfilled *existing* diverged rows (`V103__availability_window_timezone_backfill.sql`, closing the immediate half of the `skillars-deferred-17` D8 item above) but deliberately did not change `saveStep4`'s write behavior this round: `ProfileBuilderStep4.vue` ships a real, coach-editable per-window `TimezoneSelect` with helper copy reading "Windows above are interpreted in this timezone," and forcing `saveStep4` to silently discard that value without a coordinated frontend change would make the picker and its own helper text actively lie about what the screen does. Found during `skillars-deferred-63`'s own story-review (2026-08-24). A follow-up story needs to change both sides together: drop or make the picker read-only (e.g. "change it in Step 1"), *then* make `saveStep4` stop trusting the request's `canonicalTimezone` value. [`src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:251`, `src/frontend/src/components/profileBuilder/ProfileBuilderStep4.vue:66-68`] `[DECIDED 2026-08-25: per-window coach timezone is a deliberate feature, not a bug; saveStep4's write behavior stays as-is; no further action planned beyond skillars-deferred-63's one-time backfill]`

## Deferred from: code review of skillars-deferred-66 (2026-08-25)

Two pre-existing issues identified during code review but deferred as out-of-scope for this story:

- **Exception messages use imperative "retry" language that could confuse users.** All `OptimisticLockingFailureException` handlers throw with message "Booking status changed concurrently — retry", which uses imperative language ("retry") that might mislead end-users into thinking they should manually retry (click buttons again) rather than understanding the system will auto-retry. Found during review of skillars-deferred-66 AC2. This is a pre-existing message pattern already used consistently across endSession/pauseSession/resumeSession/confirmCompletion, not introduced by this story. Deferred: This message design is a pre-existing choice; consistency with existing code is the point for now.

## Deferred from: code review of skillars-deferred-75 (2026-08-27)

Code review (parallel adversarial layers: Blind Hunter, Edge Case Hunter, Acceptance Auditor) identified:

- **Video Error Handling UX** — When a video load fails (e.g., expired signed URL), code silently refetches drill data in background. Video element remains broken with no user notification. May be intentional design; requires product/UX decision. [`DrillCard.vue`, `DrillDetailPanel.vue`, and page handlers]

## Deferred from: code review of skillars-deferred-77 (2026-08-27)

Items found during this story's own investigation or during its full-module regression sweep, not fixed because they are out of the story's ACs' scope:

## Deferred from: code review of skillars-deferred-81-parent-name-batching-cross-drill-video-lock-video-error-toast-and-self-booking-packs (2026-08-28)

Four pre-existing issues identified during code review:

- **Batch name-lookup fallback messages not localized.** `BookingService` uses hardcoded English fallback strings: "Unknown Player" (line 301), "Unknown Coach" (line 299 and multiple other sites), "Unknown Parent" (line 363). Not localized; if i18n added later, these fallbacks remain English even in German/French deployments. Story added i18n keys for videoLoadFailed toast but did not extract these fallbacks. Pre-existing localization gap; separate feature. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:multiple`] `[DECIDED 2026-08-29 (skillars-deferred-83 story creation): leave as-is. These fallbacks only render for an orphaned player_id/coach_id/parent_id reference on a booking row (no FK constraints on booking.bookings per V31__booking_requests.sql), but nothing in src/main ever deletes a player_profiles or coach_profiles row (confirmed by grep — zero hits for playerProfileRepository.delete/coachProfileRepository.delete anywhere in the codebase; GdprErasureService only anonymizes User rows). Same unreachable-orphaned-profile shape the project owner already decided "leave as-is" for the messaging module during skillars-deferred-82's own creation (line above, messaging orphaned-profile inconsistency) — applying the same precedent rather than treating this as new work.]`

- **@Transactional missing on SessionPackPaymentService.purchasePack.** Payment atomicity not guaranteed; acknowledged by story and explicitly noted as outside scope. Pre-existing; `purchasePack` was never annotated. If subsequent lines call payment gateway and persist SessionPackPurchase, concurrent failures during charge + database insert could leave inconsistent state. [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`] `[DISMISSED 2026-08-29 (skillars-deferred-83 story creation): purchasePack already has an explicit compensating-action pattern (createPurchase failure -> manual Stripe refund + PaymentGatewayException, ~lines 80-92), the correct pattern for a flow spanning an external HTTP call — wrapping the whole method in @Transactional would be counter-productive (holds a DB connection open across the Stripe call). The single DB write (sessionPackPurchaseRepository.save) already runs in its own implicit Spring Data transaction. No live atomicity bug this annotation would close.]`

- **`session.drill_video_refs.video_id` has no foreign key to `main.videos.id`.** Present since the table's own creation (`V38__session_module_init.sql`). The code review initially proposed and applied a migration (`V117__drill_video_refs_videoId_fk.sql`, `ON DELETE RESTRICT`) to close this as part of AC#3's cross-drill lock work, but it was reverted during the dev-story implementation pass (2026-08-29, confirmed with the user) for two reasons: (1) `grep`-confirmed no code path anywhere in this codebase ever issues a hard `DELETE` on a `Video` row — `VideoDeletionService`/`AdminVideoService` both only soft-transition `operationalState` to `PURGED`/`DELETED` — so the FK would close no reachable race; AC#3's own in-process pessimistic lock already fully covers the actual concurrent-request TOCTOU this story targets. (2) Applying the migration broke 4 pre-existing tests (`DrillUploadServiceConcurrencyIT.deleteVideo_concurrentCallsOnSameDrill_doesNotDoublePublishDeletionEvent` and 3 in `DrillUploadResourceIT`) that insert `drill_video_refs` rows with synthetic video ids that have no matching `main.videos` row. If a future story wants to add this FK as genuine defense-in-depth, it needs: a full audit/update of every test fixture that seeds `drill_video_refs` synthetically (at minimum the 4 named above), and a check for orphaned `video_id` values in production data before deploying the migration (an `ALTER TABLE ... ADD CONSTRAINT` on existing data fails outright if any row violates it). [`src/main/resources/db/migration/V38__session_module_init.sql`, `session.drill_video_refs`] `[DECIDED 2026-08-29 (skillars-deferred-82 story creation): keep deferring. skillars-deferred-81 AC3's in-process pessimistic lock already covers the real concurrent-request race; the FK closes no reachable bug today. Revisit only if a future change actually needs it.]`

## Deferred from: code review of skillars-deferred-83-video-deletion-transaction-isolation-cross-drill-lock-causality-and-review-submission-ui (2026-08-30)

- **`app.bootstrap.jwt-secret.enabled`'s only protection against accidental enablement in a real environment is a code comment, not an enforced guard.** `JwtSecretBootstrapRunner`'s javadoc states the flag "MUST stay unset (or false) in every real environment," and (per its own javadoc) is deliberately not `@Profile`-gated since production boots with no active Spring profile at all — so the property gate is the only gate, by design. Nothing in code prevents a misconfigured non-dev deployment from setting it `true`. Already disclosed in `skillars-deferred-83`'s own Dev Agent Record as infrastructure/tooling added at the project owner's explicit request, outside that story's scope. [`src/main/resources/application-dev.yaml`, `src/main/java/com/softropic/skillars/platform/security/service/JwtSecretBootstrapRunner.java`]
- **`DrillUploadServiceConcurrencyIT`'s new lock-causality test asserts on wall-clock timing.** `deleteVideo_videoRowHeldByAnotherTransaction_waitsOutTheLockBeforeCompleting` asserts `elapsedMillis >= holdMillis - 200` after a hardcoded ~1200ms external lock hold — a known CI-timing-flakiness risk class. Deferred, not fixed: this was a documented, deliberate trade-off after the story's originally spec'd Mockito-based approach proved technically infeasible against a Spring Data JPA repository interface (confirmed by two live failures), and the 200ms tolerance matches this same test file's own pre-existing timing-based tests (`initiateUpload_briefContention_succeedsAfterBoundedRetry` et al.). [`src/test/java/com/softropic/skillars/platform/session/api/DrillUploadServiceConcurrencyIT.java`]
- **`CoachPublicProfilePage.vue` redundantly re-fetches the first page of reviews that `getCoachProfile` already returned.** `CoachProfileDto.reviews` (built server-side via `reviewQueryService.getFirstPageForCoach`, `CoachMarketplaceResource.java:70-81`) is byte-for-byte the same data `listCoachReviews(coachId)`'s default call (`page=0, sort='newest'`) fetches — but the new `onMounted` hook ignores `profile.value.reviews` and fires a second, wholly redundant `GET /api/reviews/coaches/{coachId}` request on every page view. Deferred, not fixed: AC3 explicitly directed calling `listCoachReviews(coachId)` on mount, so the diff is spec-compliant; a fix would mean reading `profile.value.reviews` directly (or dropping the backend's own now-redundant enrichment field) — a small architectural call worth a future cleanup pass rather than a blocking defect. [`src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue`, `src/main/java/com/softropic/skillars/platform/marketplace/api/CoachMarketplaceResource.java`]

## Deferred from: code review of skillars-deferred-84-coach-radar-preferences-fk-hardening-slu-snapshot-persistence-resilience-and-skill-toggle-debounce (2026-08-31)

- **V117 (like the `V113` / `V109` precedents it mirrors) is not written for online-safe deploys.** `ADD CONSTRAINT fk_crp_player_id FOREIGN KEY (player_id) REFERENCES main.player_profiles(id)` takes a validating lock that scans `development.coach_radar_preferences` and locks `main.player_profiles` (a core table) for the scan; `CREATE INDEX ix_crp_player_id` (no `CONCURRENTLY` — Flyway's transactional migration can't use it anyway) then blocks writes to `coach_radar_preferences`. The lower-impact pattern (`ADD CONSTRAINT ... NOT VALID`, then `VALIDATE CONSTRAINT` in a later migration; `CREATE INDEX CONCURRENTLY` outside a transaction) is not used anywhere in this project's migration history. Low impact for this specific small table, but a codebase-wide convention worth revisiting if any of these late-FK migrations ever targets a large table. [`src/main/resources/db/migration/V117__coach_radar_preferences_player_fk.sql`]

## Deferred from: code review of skillars-deferred-86-slu-snapshot-write-idempotency-async-retry-bulkhead-and-coach-radar-preferences-coach-fk (2026-08-31)

- **`development.player_skill_stats` has no unique constraint on `(session_id, skill_code)`, so the SLU *detail* write is only idempotent against the `@Retryable` retry, not against concurrent duplicate event delivery.** Two `BookingCompletedEvent` deliveries for the same session can both pass `SluCalculationService.onBookingCompleted`'s `findBySessionId(...).isEmpty()` guard (line 80) before either commits, both `dispatchSluPersistence` tasks run concurrently on `sluRetryExecutor`, and both `SluPersistenceRetrier.saveSluWithRetry` calls see `existsBySessionId(...) == false` (each in its own tx) and call `saveAll` — two full sets of `player_skill_stats` rows. The new V119 marker table serializes the *snapshot* writers, so `player_slu_weekly_snapshot.total_slu` is applied once while the detail rows are doubled: `SUM(player_skill_stats.slu_value)` and the snapshot total diverge permanently for that player. Pre-existing (the line-80 TOCTOU exists today); skillars-deferred-86 AC2 was explicitly scoped by the project owner as intent-documentation + a saved round-trip + a future backstop, **not** a bug fix, and the merge-on-detached retry path is genuinely safe. Closing the concurrency gap would need a DB uniqueness key on the detail side (mirroring the snapshot marker) — a follow-up story on its own merits. Flagged independently by all three review layers. [`src/main/java/com/softropic/skillars/platform/development/service/SluPersistenceRetrier.java`, `src/main/java/com/softropic/skillars/platform/development/service/SluCalculationService.java`] `[STALE — the `(session_id, skill_code)` partial unique index already exists as `V47__player_skill_stats_unique_constraint.sql` (Story 5.1 review): column-for-column and predicate-for-predicate. Never dropped. Story creation and all three deferred-86 review layers missed it, so the "would need a DB uniqueness key … a follow-up story" clause was already satisfied when it was written. The permanent double-write cannot happen — the losing `saveAll` hits PG 23505. skillars-deferred-89 AC1 instead makes that collision a clean idempotent no-op: a constraint-name-scoped catch in `SluPersistenceRetrier.saveSluWithRetry` (no migration) — no retry storm, no false "rows lost" @Recover ERROR.]`
- **`sluRetryExecutor` has no graceful-drain configuration.** The bean sets core/max/queue/timeout/decorator/rejection but not `setWaitForTasksToCompleteOnShutdown(true)` / `setAwaitTerminationSeconds(...)`, so on context close `ThreadPoolTaskExecutor.destroy()` calls `shutdownNow()`: up to 10 queued + 4 in-flight SLU+snapshot batches are abandoned, in-flight `@Backoff` sleeps get `InterruptedException` (matches no `retryFor` / `@Recover`), and `MdcDecorator` swallows the result with a generic "Exception thrown from detached thread" log — no `@Recover`, no re-drive. This exactly mirrors the shared `taskExecutor` / `moderationTaskExecutor` / `sendMailPool` (none of which set graceful-shutdown either), so it is a pre-existing project-wide convention now extended to a fourth pool, not a regression. Worth a single project-wide pass if any of these pools is ever made durable. [`src/main/java/com/softropic/skillars/platform/development/config/DevelopmentConfig.java`, `src/main/java/com/softropic/skillars/infrastructure/config/AsyncConfig.java`]
- **`SluPersistenceDispatcher.dispatchSluPersistence` runs `writeAllWithRetry` even when `saveSluWithRetry` exhausted its retries.** `SluPersistenceRetrier`'s `@Recover` returns `void`, so the chained method falls through to the snapshot write: `player_slu_weekly_snapshot` (and its V119 marker rows) can gain deltas for a session that has **no** rows in `player_skill_stats` — the dashboard fast-path then shows SLU the detail queries cannot reproduce. Pre-existing sequencing: the two calls were already sequential-with-no-guard in `onBookingCompleted` before the dispatcher extracted them; carried across unchanged. A fix would gate the snapshot write on the SLU save having actually succeeded (return value / flag from the retrier). [`src/main/java/com/softropic/skillars/platform/development/service/SluPersistenceDispatcher.java`] `[CLOSED by skillars-deferred-89 AC2 — `saveSluWithRetry` now returns `boolean` (false only from its `@Recover` paths); `dispatchSluPersistence` calls `writeAllWithRetry` only when it returned true, else ERROR-logs the skip. Closes the *over-report* direction only; the *under-report* direction (detail rows saved, snapshot write exhausts) is filed as a new residual below.]`
- **`development.player_slu_weekly_snapshot_applied` has no retention or pruning tied to session lifecycle.** Its only non-PK relationship is `player_id -> main.player_profiles(id) ON DELETE CASCADE`; there is no FK to the session and no job that trims markers when a session is deleted or ages out. Rows (5-column PK led by a random session UUID) accumulate for the lifetime of the deployment, removed only by the per-player GDPR-erasure `deleteAllByPlayerId` cascade. Growth rate is low (one row per session × skill × ISO-week) and matches the append-only pattern of the sibling `player_skill_stats` / `player_slu_weekly_snapshot` tables, so this is a long-horizon housekeeping item, not a defect. [`src/main/resources/db/migration/V119__player_slu_weekly_snapshot_applied.sql`]

## Deferred from: code review of skillars-deferred-87-backup-upload-verification-hardening-provision-volume-device-and-pre-volume-data-migration-ci-latest-ordering-guard (2026-08-31)

3-layer adversarial review (Blind Hunter / Edge Case Hunter / Acceptance Auditor). All 7 ACs satisfied to spec, 0 AC violations. 1 decision-needed + 7 patches handled on the story; the items below are real but pre-existing or spec-accepted design and out of this story's scope:

- **Pre-Volume migration does not run on a host where a prior run already mounted the Volume over the pre-Volume `data/` tree.** `provision.sh` section 7's `mountpoint -q "${MOUNT_POINT}"` short-circuit skips the staging branch entirely and `${STAGING}` is never created, so `settle_pre_volume_migration` finds nothing and returns — the root-disk tree (TLS certs, pre-Volume LGTM/Redis data) stays shadowed under the mount forever. The realistic path this AC targets (new script runs *before* the Volume, Volume attached, script re-run) is handled; an already-shadowed host is not. AC5 step 6 explicitly accepts already-shadowed data as manual-reclaim-only (unmount `${MOUNT_POINT}`, `rm -rf` its root-disk contents, remount), and `first-time-setup.md` documents that manual reclaim. [`deploy/provision.sh:289-311`] `[CLOSED by skillars-deferred-89 AC9(a) — re-scoped to the genuinely-uncovered case: the Volume mounted OUTSIDE provision.sh (manual `mount` / `/etc/fstab`) before the script ever ran, so nothing was ever staged. (A prior provision.sh run instead leaves a verified duplicate safely on the Volume — not loss.) `provision.sh` now logs a one-line pointer on the already-mounted path to `first-time-setup.md` → "Reclaim shadowed pre-Volume data" (new stable heading, 5-step procedure). No bind-mount probe, no steady-state multi-line WARNING.]`
- **CI `:latest` freezes permanently after a `master` history rewrite.** Once the commit in `:latest`'s `org.opencontainers.image.revision` label is unreachable (force-push / rebased-away branch), `git merge-base --is-ancestor "${published_rev}" "$GITHUB_SHA"` returns 1 or 128 on *every* subsequent run, so `:latest` is never advanced again and there is no manual-override path. This is a direct consequence of AC6's explicit fail-safe design (exit ≠ 0 → publish the `sha-` tag only, never abort). `master` force-push is outside normal operations; recovery is a manual `docker buildx imagetools create`/re-tag of `:latest`. A `workflow_dispatch` "force-publish :latest" escape hatch would be a separate follow-up. [`.github/workflows/ci.yml:137-144`] `[PARTIALLY CLOSED by skillars-deferred-88 AC6 — a workflow_dispatch force_publish_latest input now force-publishes :latest for the current commit without the ancestor check (master-ref-gated: ::error:: + exit 1 on any other ref). The automatic ancestor check still cannot self-recover from a history rewrite, by design.]`
- **Pre-Volume migration verify is stat-only on the armed paths; `postgres/` is migrated but never verified before `rm -rf "${STAGING}"`.** `migrate_pre_volume_data` compares only `stat -c '%a %u:%g'` on each armed top-level dir (`traefik/acme.json` + `redis|grafana|loki|tempo|prometheus/`) — never inner files, never `postgres/`, never a content/checksum re-check. A torn `rsync` (SIGKILL / Volume ENOSPC after the dirs exist but before their contents finish) passes verification, `${STAGING}` is deleted, and the incomplete copy is the only one left. Matches the story-review-approved AC5 scope ("verify only source paths"). Surfaced as a decision-needed item on the story; the project owner's resolution is recorded there. [`deploy/provision.sh:233-264`]
- **A `build-and-push` run cancelled while *pending* in the `build-and-push-${{ github.ref }}` concurrency group publishes no tags at all — not even `sha-<short>`.** GitHub keeps only one run pending per group and cancels a previously-pending one when a newer queues, so in a ≥3-push burst the middle commit's image is never built and `docs/deployment/rollback.md` has no rollback target for it until its CI is manually re-run. `cancel-in-progress: false` protects only the *running* run. Acknowledged in the shipped `ci.yml` comment and the story's M2 caveat; AC6 neither introduces nor fixes it. [`.github/workflows/ci.yml:52-54`] `[CLOSED by skillars-deferred-89 AC9(b) — `docs/deployment/rollback.md` gains a "If the commit has no `sha-<short>` image in GHCR" subsection with the *Re-run all jobs* remedy + a roll-back-to-nearest-older-image fallback. The `ci.yml` comment this originally asked for already existed at `ci.yml:103-105` and `:217-218` (confirmed). A code fix (a separate always-runs `sha-`-only job outside the concurrency group) is explicitly out of scope.]`
- **`chown_if_needed`'s `find -maxdepth 2` walks grandchildren, not just the "immediate children" the comment describes, and only `-quit`s on a mismatch.** On a clean idempotent re-run it stat-walks the directory plus two levels of each observability dir (`loki/chunks/*`, tempo blocks, prometheus WAL) with no early exit. Matches the spec's literal `-maxdepth 2`; revisit if provision runtime regresses on a Volume with real retention. [`deploy/provision.sh:43`] `[CLOSED by skillars-deferred-89 AC9(c) — comment-only fix: `-maxdepth 2` KEPT (narrowing to `-maxdepth 1` would skip the repair for a single-child dir like `loki/` → `chunks/` that `chown -R` descended into before an interruption); the comment now reads "the directory, its children and its grandchildren", and the LIMITATION sentence + `-quit` + the deferred-87 AC3 recovery logic are unchanged. Matching wording fix in `first-time-setup.md`. Perf-watch note stands: revisit if provision runtime regresses.]`
- **First-ever `:latest` publish where GHCR returns `denied` / `name unknown` instead of a `not found` / `MANIFEST_UNKNOWN` message.** `grep -qiE 'not ?found|MANIFEST_UNKNOWN|manifest unknown'` misses those phrasings → the step falls to the generic "read failed, publish `sha-` only" branch → `:latest` is not created on the first push, appearing only on a later run once the package exists and the registry returns a true 404. Self-heals; one-run delay on a brand-new repo. [`.github/workflows/ci.yml:109`] `[CLOSED by skillars-deferred-88 AC6 — for the `name unknown` phrasing only; the grep now matches not ?found|manifest ?unknown|MANIFEST_UNKNOWN|name ?unknown. `denied` is deliberately NOT reclassified as absent — GHCR also returns it for a permission failure on an existing package, so moving it would let a transient denied trigger an older-overwrites-newer :latest overwrite.]`

## Deferred from: code review of skillars-deferred-88-review-moderation-epoch-provision-concurrency-and-fstab-safety-ci-latest-recovery-grafana-single-channel-receiver-egress-firewall-and-auth-otp-hardening (2026-08-31)

3-layer adversarial review (Blind Hunter / Edge Case Hunter / Acceptance Auditor). 0 AC violations, all 12 ACs implemented. 1 decision-needed + 10 patches handled on the story; the items below are real but pre-existing or spec-sanctioned and out of this story's scope:

- **AC5 single-Volume + explicit-but-unresolvable `HETZNER_VOLUME_ID` still warns-then-falls-back to the lone attached Volume.** When exactly one `scsi-0HC_Volume_*` symlink exists and `HETZNER_VOLUME_ID` is set but does not resolve (typo / wrong host / detached), the script warns and proceeds with the single attached Volume — and would `mkfs.ext4` it if unformatted. This matches AC5 spec case (h) exactly ("single-Volume is unambiguous"), but an operator who explicitly pinned a device by id and got it wrong is the case most likely to be on the wrong host. Promoting this to a hard fail is a follow-up hardening decision. [`deploy/provision.sh` device resolution, ~L194-218]

## Deferred from: skillars-deferred-89 story creation (2026-08-31)

Surfaced by the story-creation audit and by implementation (2026-09-01). Two residuals + one observation, none owed an AC by this story:

- **SLU weekly-snapshot *under*-report direction.** skillars-deferred-89 AC2 (and its code review — see below) gates the snapshot write on detail-save success, closing the *over*-report direction in full. The reverse is untouched: detail rows saved, `SnapshotPersistenceRetrier.writeAllWithRetry` then exhausts its retries, its `@Recover` logs "rows lost" and returns — the detail rows exist with **no** snapshot delta, so the dashboard fast-path (`player_slu_weekly_snapshot`) *under*-reports for that session, with no reconciliation job. `SnapshotPersistenceRetrier` was explicitly off-limits for AC2 (the story statement is scoped to "cannot over-report"). `deferred-work.md`'s earlier note on the two-transaction split as accepted eventual consistency is the softer framing; this is the sharper still-open residual. **Additionally**, the AC2 code-review fix made `saveSluWithRetry` tri-state (`SAVED` / `ALREADY_PERSISTED` / `FAILED`) so the dispatcher skips the snapshot write on the `existsBySessionId` / V47-collision path — which also means a redelivery no longer *opportunistically* re-drives a snapshot write that the original delivery's `@Recover` swallowed. Net effect on this residual: unchanged (that repair was never guaranteed), but noted so a reader does not expect it. [`src/main/java/com/softropic/skillars/platform/development/service/SluPersistenceDispatcher.java`, `SnapshotPersistenceRetrier.java`]
- **[CLOSED by skillars-deferred-89 code review, 2026-09-01] SLU over-report via a concurrent-delivery collision across a week boundary.** The AC2 review found a second over-report mechanism the original AC missed: on the V47 collision path (and the pre-existing `existsBySessionId` short-circuit) `saveSluWithRetry` returned `true`, so the dispatcher still ran `writeAllWithRetry` with the losing delivery's list and *its own* per-invocation `isoYear`/`isoWeek` — two deliveries seconds apart across Monday 00:00 UTC land in different week buckets, and the loser's delta hit a bucket the winner never marked (plus the skill-subset trigger AC1 had recorded as residual). Fixed by making `saveSluWithRetry` return `SluSaveOutcome` and having the dispatcher run the snapshot write **only** on `SAVED`. Guarded by `SluPersistenceDispatcherTest.dispatchSluPersistence_rowsAlreadyPersistedByConcurrentDelivery_skipsSnapshotWrite_noError` + `SluPersistenceRetrierTest.saveSluWithRetry_isNotTransactional_…`.
- **No non-gating perf-trend signal for `authorizePlayback`.** skillars-deferred-89 AC5 removed the flaky `p99 < 200ms` merge gate and left a `log.info` of p50/p95/p99 in Failsafe output that nothing scrapes. The deferred-23 bullet's own suggested remedy — a dedicated non-gating perf-tracking job that records the distribution over time — is not built. [`src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java`]
- **Observation (not a residual):** skillars-deferred-89 AC7 takes the count of `permitAll()` OTP-email endpoints from 1 (parent) to 3 (parent + coach + player). Each is `@RateLimited(capacity = 3, duration = 30)` (per client IP) and V121's `uq_pot_one_active_per_user` blocks a concurrent second active token. The AC7 code review flagged that the IP bucket alone lets a distributed caller who knows a victim's `userId` repeatedly delete their in-flight OTP / bomb their inbox — so a **per-user** `rateLimitingService.tryConsume(userId, "<role>_resend_otp_user", 3, 30, MINUTES)` guard was added to `resendPhoneOtp` in all three services (parent included, to keep parity), mirroring the existing `verifyPhone` per-user limit. Cross-role misuse (`/player/resend-otp` with a coach's id) is still possible but low-value — both endpoints require a valid id + `EMAIL_VERIFIED` and only ever mail the account's own address; left as-is (parent has no role check either). [`CoachRegistrationService.java`, `PlayerRegistrationService.java`, `ParentRegistrationService.java`, `AppEndpoints.java`]

## Deferred from: code review of skillars-deferred-89-slu-detail-uniqueness-snapshot-write-gating-drill-video-deletion-existence-guard-scheduler-lock-transaction-ordering-perf-test-deflake-resend-otp-parity-and-provisioning-ops-doc-hardening (2026-09-01)

Six items triaged `defer` by `bmad-code-review` (Blind Hunter / Edge Case Hunter / Acceptance Auditor). All were verified against source; each is either pre-existing or explicitly sanctioned by an AC.

- **`resendPhoneOtp` is now a third verbatim copy across the registration services.** `Parent`/`Coach`/`PlayerRegistrationService` carry byte-identical implementations differing only in the `@RateLimited` key and the OTP-email event type. Every future fix (per-user rate limit, role assertion, null-constraint handling) must land in three places, and the "mirrors parent exactly" property is asserted in comments, not enforced by any test. Pre-existing triplication pattern across these services, widened — not introduced — by AC7. [`ParentRegistrationService.java:226-252`, `CoachRegistrationService.java:230-248`, `PlayerRegistrationService.java:256-274`]
- **`resend-otp**` matches same-segment siblings.** The pattern has no `/` before `**`, so `/api/security/coach/resend-otp**` also matches `/api/security/coach/resend-otp-admin` and similar; a future controller mapped under that prefix would be silently unauthenticated with no review step. Nested-path leakage is **not** possible — `SecuredHttpEndpointGuard` uses `PathPatternRequestMatcher`, under which a trailing `**` inside a segment does not cross `/`. Pre-existing convention shared by the neighbouring `register**` and `verify-email**` entries; AC7 propagated it rather than introducing it. Fixing means auditing all such patterns at once. [`src/main/java/com/softropic/skillars/platform/security/config/AppEndpoints.java:30,36`]
- **AC7's 409 path is never proven through the endpoint.** The V121 `uq_pot_one_active_per_user` → 409 `security.otpResendInProgress` mapping is covered only by raw-JDBC tests asserting `DataIntegrityViolationException`; no test drives `/api/security/coach/resend-otp` or `/api/security/player/resend-otp` into the collision and asserts the HTTP status. The `ApiAdvice` `CONSTRAINT_MAPPINGS` entry is verified present, and AC7 explicitly sanctioned reusing the spy-free `secondActiveOtpInsert_…` shape rather than adding a `@MockitoSpyBean` (which would fork a Spring context against the CI ceiling of 37). [`src/test/java/com/softropic/skillars/platform/security/api/CoachRegistrationResourceIT.java`, `PlayerRegistrationResourceIT.java`]

## Deferred from: code review of 1-7-session-refresh-mechanism-fix (2026-09-02)

- **Three sibling docs still assert `rint` TTL = 15 min.** The rewritten `docs/session-refresh-mechanism.md` correctly documents `rint` as a browser-session cookie (`maxAge=-1`) holding a fixed 600000 ms, but the surrounding doc set was not updated and still carries the old claim. `frontend-integration-guide.md:3684` links straight to the rewritten doc, so a reader lands on two contradictory TTLs in one hop. Outside AC scope, which named only `session-refresh-mechanism.md`. [`docs/frontend-integration-guide.md:1164`, `docs/security-api-endpoints.md:252`, `docs/frontend-implementation-spec.md:243`]
- **`dev-docs` index summary is stale.** Still describes `session-refresh-mechanism.md` as a "deep dive on the JWT refresh-token rotation flow" — the rewritten doc is about the sliding-window keep-alive mechanism; rotation is `POST /api/auth/refresh`, a different endpoint the doc now explicitly distinguishes. [`docs/dev-docs/index.html:152`]
- **Prettier fails on the two touched frontend files at `HEAD` as well as in the working tree.** `project-context.md` makes Prettier mandatory for all `.js`/`.vue`/`.scss`/`.json`. Not introduced by this change (verified failing at `HEAD` too), and disclosed in the story's Completion Notes, but the repo-wide rule stays violated. [`src/frontend/src/App.vue`, `src/frontend/src/boot/axios.js`]
- **"5 minutes before expiry" is stated as exact but the monitor ticks every 30 s.** `SESSION_CHECK_INTERVAL` = 30 s, so both the warning and the client-side expiry fire up to 30 s late. The doc's key-timeouts table and the new `SecurityConstants` JavaDoc both present the figure as precise, which makes any acceptance test written against it flaky by construction. [`docs/session-refresh-mechanism.md:67`, `plugins/sessionManager.js`]
- **1.7b promoted to `ready-for-dev` with its stated prerequisite unclosed.** `1-7-REVIEW-UPDATES.md:165` requires a manual JWT-expiry observation "before 1.7b starts — 5 minute check de-risks B3", and it is not recorded as performed. B3's diagnosis was independently confirmed correct during this review (`JWTAuthorizationFilter.java:115-119` sends a bodyless `sendError(SC_UNAUTHORIZED)`; the only producer of `security.sessionExpired` is `ApiAdvice.java:303-308`, which a servlet filter never reaches), so the premise holds — only the checklist item is open. [`_bmad-output/implementation-artifacts/1-7-REVIEW-UPDATES.md:165`]
- **Story file and all three 1.7b artifacts are untracked while `sprint-status.yaml` already references them.** `development_status` lists `skillars-1-7b-session-refresh-rint-contract-fix: ready-for-dev`, but `1-7b-session-refresh-rint-contract-fix.md`, `1-7b-ARCHITECTURAL-DECISION.md`, `1-7-REVIEW-UPDATES.md` and the 1.7a story file itself are all `??`. The ledger points at files that are not in the commit. [`_bmad-output/implementation-artifacts/`]
- **`story-review.md` is a rotating single file and each review erases its predecessor.** This change replaces the 358-line `skillars-deferred-89` senior-dev audit (blockers B1–B4, findings H1–H4/M1–M9, all with file:line citations) with the Story 1.7 review. `skillars-deferred-89` is `done`/merged so this follows established convention, but the audit trail now exists only in git history, and 422 lines of unrelated churn are attributed to a "documentation-and-comments-only" commit. Process fix would be per-story review files. [`_bmad-output/implementation-artifacts/story-review.md`]

## Deferred from: code review of 1-7b-session-refresh-rint-contract-fix (2026-09-02)

- **401 JSON body carries no `Cache-Control: no-store`.** `writeUnauthorized` sets status, content-type and charset but no cache directives, so a misconfigured intermediary could in principle cache the 401 response. The body contains no secrets (an `errorKey` and a generic message), so impact is low. Pre-existing shape — `ApiAdvice` responses rely on Spring's defaults. [`JWTAuthorizationFilter.java:writeUnauthorized`]
- **`sessionManager.js` state machine rewritten with zero frontend tests.** The diff replaces the whole expiry/warning/countdown state machine (`computeTimeUntilExpiry`, the legacy-fallback branch, `tick()`'s warning edges and expiry dispatch, the `refreshSession()` interaction) and adds backend tests only. Three of the confirmed patch findings in this review — the stale-`rint` parse, the countdown-timer leak, and the unevaluated-state window — are each a one-line unit test with a faked `document.cookie` and `Date.now()`. The repo has no frontend unit-test infrastructure, which the story disclosed, so this is a standing gap rather than a story miss. [`src/frontend/src/plugins/sessionManager.js`]

## Deferred from: code review (round 2) of 1-7b-session-refresh-rint-contract-fix (2026-09-02)

- **`startSessionMonitoring()`'s early return leaves no timer armed if the expiry navigation is swallowed.** When the first `tick()` reports an expired session, monitoring returns without arming the 30 s interval and relies entirely on `App.vue`'s `handleSessionExpired` → `router.push()`. That push is not awaited or `.catch()`ed, and Vue Router 4 rejects on an aborted/redirected navigation. If it is aborted, `cleanup()` has already reset the state to look healthy (`showWarning = false`, `timeUntilExpiry = LEGACY_SESSION_TTL`, `checkIntervalId = null`) and nothing re-arms monitoring — `startSessionMonitoring()` is only called from `App.vue` mount and `initSession()`. Deliberately left as-is rather than patched: the alternative (arm the interval anyway) makes the failure noisy instead of silent but re-dispatches `session:expired` — and therefore a backend logout call — every 30 s until navigation completes. Both options have real costs and the abort path is unverified. [`src/frontend/src/plugins/sessionManager.js:startSessionMonitoring`]
- **[CLOSED 2026-09-02]** ~~Three sibling docs still describe `rint` as a countdown with a 15-minute TTL.~~ Fixed in the same session: all three cookie tables corrected (absolute epoch ms, `maxAge` 960 s) and each now defers to `session-refresh-mechanism.md` instead of restating the contract. Three further errors were found and fixed while there: `frontend-integration-guide.md` carried a ~120-line `sessionManager.js` listing that never matched the real module (hardcoded `SESSION_TTL`, 2-minute warning, a `readonly` `sessionState` object and a `resetTimers()` helper — none of which exist), replaced with a summary plus pointer; the `admin` cookie was compared against `'true'` when its value is the literal `"admin"`; and the per-row `Secure` column was wrong for every row, since `CookieUtil` derives `Secure` from the request scheme. Two reference 401 interceptors that gated only on `security.sessionExpired` now gate on `security.unauthorized` too, matching the real code. Original entry: **Three sibling docs still describe `rint` as a countdown with a 15-minute TTL.** `docs/security-api-endpoints.md:252`, `docs/frontend-integration-guide.md:1164` and `docs/frontend-implementation-spec.md:243` all predate the contract change; both facts are now wrong (`rint` is absolute epoch ms with `maxAge` 960 s). Flagged once during the 1.7a review and not re-recorded for 1.7b, which made them more wrong. Outside this story's AC scope, which named only `docs/session-refresh-mechanism.md`. [`docs/security-api-endpoints.md`, `docs/frontend-integration-guide.md`, `docs/frontend-implementation-spec.md`]
- **Prettier still fails on every changed frontend file, and this story widened the gap.** `project-context.md` makes Prettier mandatory for `.js`/`.vue`/`.scss`/`.json`; `.prettierrc` sets `semi: false` while the files use semicolons, so `npx prettier --check` warns on `sessionManager.js`, `useSession.js`, `boot/axios.js` and `App.vue`. Verified failing at `HEAD` too, so not introduced here — but 1.7b added roughly 100 net new non-conforming lines to `sessionManager.js`, and neither the story's Verification section nor the review claimed or checked Prettier (only ESLint). Already recorded for 1.7a; re-recorded because the surface grew. [`src/frontend/src/`]

## Deferred from: skillars-deferred-90 story creation and implementation (2026-09-02)

- **`sessionManager.js` `startSessionMonitoring()`'s early-return-with-no-timer path** — left as documented (project-owner decision). See the round-2 1-7b bullet above; not re-fixed here.
- **CAPTURE_PENDING has no automated exit / no escrow-style payout-on-completion gate** — owed a dedicated design pass (`deferred-work.md` ~line 1218 context, `skillars-uat-3` D3). Out of scope for this bundle.
- **`getPublicProfile`'s 8 sequential round-trips (§ skillars-2-3)** — left pending real latency measurement per the ledger's own re-evaluation; not part of AC13's four hotspots.
- **de-DE 55-string native pass is AI-authored to native quality, not yet human-verified by a native German speaker** (AC12). The `// TODO: native review` markers were removed and `MessageBundleParityTest` guards placeholder/pluralization integrity, but a native-speaker review of wording/register is still owed.
- **The `/resend-otp` → 409 mapping is proven at the `ApiAdvice` slice level only** (AC9). No end-to-end two-connection collision harness — a sequential endpoint call can never collide and `@RateLimited` would mask it (F21).
- **AC10's migration-lint guard's known blind spots**: a backported lower-version migration (below the `V121` baseline), an `R__` repeatable migration, and an inline `ALTER TABLE … ADD COLUMN … REFERENCES x(y)` FK (no literal `ADD CONSTRAINT` text). Text-level linting is a backstop, not a proof — documented in `docs/deployment/migration-conventions.md`.
- **Backend `messages_de` / `messages_fr` are brought to `messages_en` parity for the keys that exist at story time** (AC12). A later story that adds new `_en` keys re-opens the gap until it also adds the DE/FR rows; `MessageBundleParityTest` will fail the build if it doesn't.
- **`deferred-work.md` / `sprint-status.yaml` 40k-char single-line hygiene** — explicitly out of scope (project-owner decision 4).
- **No bulk-delete capability in the storage stack** (R1). `StorageService.java:11`, `S3StorageService.java:115-116` and `FileStorageService.java:324` are all single-key; AWS `DeleteObjects` (up to 1000 keys/call) is not wired in. AC13's option (a) does not need it — the `PendingBlobDeletionService` drain is off the request path — but any future high-volume blob purge will. Left open deliberately.

### Confirmed already-done and closed as stale by the skillars-deferred-90 senior-dev audit (2026-09-02)

The following ledger items were verified against current source and found already resolved; their bullets were deleted with this story's own closures rather than re-worked:
- `skillars-deferred-8` D2 — `*_wrongCoach_returns403` status asserts already present (`BookingRequestResourceIT.java:555, :586`).
- `skillars-deferred-18` D3 — `formatSlot` already uses `locale.value` (`BookingRequestPage.vue` → `formatInZone`); no standalone bullet remained, recorded here.
- `skillars-deferred-9` D1 + `skillars-1-2` W2 — `portal` / `auth` / `profile` / `session` stub keys: the three frontend bundles are at exact parity (de-DE 1022/1022, fr-FR 1022/1022, 0 missing / 0 extra).
- `skillars-3-3` Group A/B — N+1 in `getParentBookings`: already fully batched (`findAllById` for coach + player names, `findPendingByBookingIdIn`, `countByBatchIdIn`); no per-row effective-credit lookup exists.
- The `commissionRate` unguarded `new BigDecimal(...)` note — guarded at `StripePaymentGateway.java:43-51` (inside the `catch (IllegalStateException | NumberFormatException)`); folded into the deleted AC6 currency bullet.
- `skillars-deferred-88` fstab "no `[ -n "${VOLUME_LINK}" ]` precondition" — moot; `provision.sh:366-368` always sets a non-empty device field.
- `boot/axios.js` reference to the 401 interceptor gating only `security.sessionExpired` — the code interceptor (`:151`) already gates on both keys.

## Deferred from: code review of skillars-deferred-90 (2026-09-03)

- `ErrorLog.logError` builds its log line with `String.format(msgTemplate + " SUPPORT_ID: %s", helpCode)`, and `msgTemplate` is frequently an exception message (`ApiAdvice` passes `ex.getMessage()` at ~20 call sites). An exception message containing a `%` — e.g. user input quoted back by a validation message, or a driver error — throws `UnknownFormatConversionException`/`MissingFormatArgumentException` *inside the exception handler*, producing a bare 500 with no ErrorDto body. Same failure class as the `skillars-deferred-90` AC1 NPE. Pre-existing: moved verbatim out of `ApiAdvice.logError` into `infrastructure/message/ErrorLog.java` by AC5, not introduced there. Fix is to log the template as a parameterised argument rather than formatting it (`log.error("{} SUPPORT_ID: {}", msgTemplate, helpCode, entries(ctx), throwable)`), which also removes the `%` hazard entirely. [`ErrorLog.java:44`]

## Deferred from: 3-layer adversarial code review of skillars-deferred-90 (2026-09-03)

- **Cross-tab logout is undetected when the backend `/logout` call stalls or fails.** The AC3 fast-teardown branch in `computeTimeUntilExpiry()` needs `rint` absent, but nothing on the client clears `rint` — it is removed only by the backend logout `Set-Cookie`, which is `Promise.race`d against `LOGOUT_BACKEND_WAIT_MS`. If that request stalls or errors, sibling tabs keep `rint` and keep rendering an authenticated UI until the stale `rint` deadline fires. Bounded by the `rint` TTL; a client-side `rint` clear inside `handleLogout` would close it. [`src/frontend/src/plugins/sessionManager.js:119-137`, `src/frontend/src/composables/useSession.js:71-90`]
- **AC8's before/after "12/14" wildcard-bundle reproduction was never run.** `LoginInfoServiceIT`, `LoginAttemptsServiceTest`, `NeglectedSkillDetectionServiceIT` and `AccountManagementFacadeIT` were each run in isolation (green) but never together in the order that produced the reported failure, so the "bug reproduced / bug gone in the same run" evidence the AC asks for does not exist. The fix itself (`RequestMetadataProvider.cleanup()` in both `tearDown()`s + defensive cleanup in `LoginAttemptsServiceTest`) is sound. Author verification task, not a code change. [`skillars-deferred-90` Task 8]
- **AC12 register mismatch: the de-DE frontend bundle pervasively uses informal `du`/`dein`.** AC12 mandates formal *Sie*, but large untouched sections of `de-DE/index.js` (onboarding, dashboard, reviews, video) are informal, and the story's v0.2 note asserts those 53 unchanged strings were "already native-quality". A `Sie`-consistency pass across the whole bundle is a translation-scope decision beyond this diff. The one new string this story added in informal voice (`booking.requests.bookingsLoadError`) is tracked as a patch in the story's Review Findings. [`src/frontend/src/i18n/de-DE/index.js`]

- `VideoDeletionService.cascadeDeleteForAccount` (`:186`) throws `jakarta.persistence.TransactionRequiredException: Executing an update/delete query` on the account-deletion cascade path (`AccountDeletionCascadeListener.onAccountDeleted:46`, reached from `GdprErasureService.erase()` via `AccountDeletionRequestedEvent`). Observed repeatedly in a clean `GdprErasureIT` run on 2026-09-03; all 16 tests still pass, so the failure is caught and logged somewhere upstream rather than surfacing. A `@Modifying` query is executing without a transaction — either the listener needs `@Transactional`, or the cascade is silently not deleting what it claims to. Unrelated to skillars-deferred-90 (found while verifying the AC13 outbox rework); needs its own look because a silently-failing GDPR cascade delete is a compliance issue, not just log noise.
