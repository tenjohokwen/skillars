# Story Deferred-33: Messaging Soft-Delete Lock Ordering, Booking Corrupted-Status Mapping, Video Quota Status, Dead Refund-Field Removal, German Video i18n Gap & Ledger Hygiene

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Skillars maintainer,
I want six small, independently-verified deferred items closed — a messaging soft-delete that takes a
pessimistic row lock before checking whether the caller is even allowed to touch the row, a booking-status
read that answers a corrupted-data case with the wrong HTTP semantics, a video-quota rejection that returns
a retry-suggesting status for a condition that will never clear on retry, a write-only booking field whose
stored value silently disagrees with the boolean that actually moves money, a German i18n bundle missing 7
of 8 video-error translations, and one stale line inside a previously-merged story's own Completion Notes —
so that each of six unrelated, previously-deferred defects, spanning the messaging, booking, video and i18n
surfaces, gets fixed without bundling any of them into a larger story that would need its own design pass.

### Why this story exists

Drawn directly from `_bmad-output/implementation-artifacts/deferred-work.md`, per Mbah's direction to
group small, unrelated, already-deferred items into one story to reduce dev overhead — the same spirit as
`skillars-deferred-11` through `skillars-deferred-32`. All items below were independently re-verified
against **current** code (post-merge of PR #62, `master` after `skillars-deferred-32`) during this story's
creation on 2026-08-18, by reading the actual method bodies, the actual test files, and the actual ledger
sections directly rather than trusting the ledger's own citations (several of its line numbers have
drifted, though its factual claims held up in every case used below).

The ledger has been mined by 32 prior bundling stories and is now thin on small, mechanical,
decision-free items. This story's creation pass read the entire ~1550-line file and found 4 solid,
decision-free candidates, plus 2 more items that looked decision-gated on the ledger but turned out to
have a clear, small answer once traced through the actual code — Mbah resolved both during this story's
creation (see AC6 and AC7 below) rather than leaving them open. It also found 2 items that are stale
ledger noise rather than live defects, folded into AC5 (ledger hygiene) rather than given their own AC.

## Deferred Items Closed

| Source | Item | Current location (re-verified 2026-08-18) | AC | Planned outcome |
|---|---|---|---|---|
| code review of `skillars-deferred-16-messaging-moderation-recovery-identity-safety` (2026-08-05), D2 | `MessagingService.softDeleteMessage` takes the pessimistic row lock before running any authorization check, so any authenticated caller can pin an arbitrary message row for the duration of the transaction on their way to a 403 | `MessagingService.java:293-321` | 1 | Reorder to unlocked read → conversation/sender authorization → locked re-read → moderation/already-deleted checks → write, mirroring the identical fix already shipped in `BookingService.cancelBookingAsParent` |
| code review of `skillars-deferred-12-booking-payment-review-integrity` (2026-08-04), D4 | `readStatusOrThrow` maps a corrupted/unrecognised `status` column value to a 404 "booking not found", even though the row exists and the caller can see it in their list | `BookingService.java:594-601` | 2 | Map to a 409 via `ResponseStatusException`, mirroring the existing `booking.paymentInProgress` precedent two methods away |
| code review of `skillars-deferred-32-...` (2026-08-18) | `messages_de.properties` has only 1 of 8 `video.*` keys present; the other 7 are silently missing from the German bundle | `messages_de.properties:79-80` | 3 | Add the 7 missing German translations, in the same order as the `en-US`/`fr-FR` backend bundles |
| `skillars-deferred-32` implementation (2026-08-18) | `skillars-deferred-31`'s own Completion Notes still claim a fixture block of `9630000001`–`9630000003`, one id past what the story's own later review-follow-up narrowed it to and what the shipped test actually seeds | `skillars-deferred-31-....md:601` | 4 | One-line text correction, `003` → `002` |
| `skillars-deferred-32` implementation (2026-08-18) | `videoQuotaExceededHandler` returns 429 (Too Many Requests) for a hard storage-quota rejection, a condition that does not clear on retry | `VideoApiAdvice.java:73-79` | 6 | **Decided by Mbah 2026-08-18:** change to 403 Forbidden |
| code review of `skillars-deferred-28-...story creation` (2026-08-17) | `refundEligible` boolean (the real, money-moving signal) and `applyRefundLogic`'s three-tier `refundEligibility` string (write-only, unread anywhere) disagree in the 6-24h window and across the 24-25h boundary | `BookingService.java:648-649,780-787` | 7 | **Decided by Mbah 2026-08-18:** delete the dead `refundEligibility`/`refundAmount` fields, `applyRefundLogic`, and its call site entirely |

**Explicitly NOT in this story** (considered during story creation and rejected — do not implement):

- **`BookingServiceConcurrencyIT`'s `Thread.sleep(1500)`-based thread ordering** (ledger: `## Deferred from:
  code review of skillars-deferred-12-...`, D1). Re-examined during this story's creation: this is the
  same `CountDownLatch`/`Thread.sleep` pattern this codebase has repeatedly, deliberately chosen for
  DB-lock-interleaving tests (most recently re-affirmed by `skillars-deferred-32` AC5, which explicitly
  declined to convert an equivalent test to a different assertion style specifically to keep its lock
  timing deterministic). Making it more rigorous would mean adding a test-only synchronization hook inside
  production code — an engineering-approach decision, not a mechanical patch. Left open on the ledger.
- **The per-booking outcome reporting from `acceptAll`** and **all other open ledger items** — every one
  inspected during this story's creation either needed a product/design decision, targeted work already
  explicitly scoped out by a prior story, or was found to be stale (see AC5).

## Acceptance Criteria

1. **`MessagingService.softDeleteMessage` takes the pessimistic row lock before checking whether the
   caller may touch the row at all.**

   Verified current state (`MessagingService.java:293-321`): the very first statement is
   `messageRepository.findByIdForUpdate(messageId)`. Only after that lock is held does the method check
   conversation membership, sender ownership, moderation status, and whether the message is already
   deleted. Any authenticated caller — including one with no relationship to the message at all — can
   therefore acquire the row lock and hold it for the rest of the transaction before receiving their 403,
   blocking `ModerationResultApplier`, the moderation sweeper, or the legitimate sender for that window.

   This was flagged in `skillars-deferred-16`'s own code review (D2), which noted the story's AC5 had
   explicitly prescribed this exact order ("take the locked read first, keep every existing guard in its
   current order after it") — so the ordering was a deliberate choice at the time, not an oversight. It has
   since been identified as the wrong choice: `BookingService.cancelBookingAsParent` (added by
   `skillars-uat-3` AC2/AC3) deliberately does it the other way, and its in-code comment
   (`BookingService.java:617-620`) names this exact `MessagingService.softDeleteMessage` finding as the
   reason:
   > "Unlocked read + ownership check FIRST, locked re-read second. Deliberately in this order: taking a
   > row lock before authorising the caller lets any authenticated user pin an arbitrary booking row for
   > the duration of the transaction before receiving their 403 — the exact finding Deferred-16 D2 raised
   > against MessagingService.softDeleteMessage. One extra SELECT is cheap."

   The pattern this story must copy already exists and is already proven correct in production.

   **Required:** restructure `softDeleteMessage` into two phases, exactly mirroring
   `cancelBookingAsParent`'s shape:
   - **Unlocked read** via the repository's inherited `findById(Long)` (from `JpaRepository`; no new
     repository method needed).
   - **Authorization checks against the unlocked read**, in their current order: conversation-membership
     check, then sender-ownership check. Both currently throw `OperationNotAllowedException` /
     `MessagingErrorCode.NOT_A_PARTY` — keep that exactly.
   - **Locked re-read** via the existing `findByIdForUpdate(Long)`.
   - **Business-state checks against the locked re-read**, in their current order: moderation-status check
     (`UNDER_REVIEW`/`BLOCKED` → `MessagingErrorCode.MODERATION_PENDING`), then the already-deleted check
     (`deletedAt != null` → `MessagingErrorCode.ALREADY_DELETED`). These stay under the lock — the
     already-deleted check in particular is what makes a concurrent double-delete lose cleanly, per the
     existing code comment at `MessagingService.java:294-297`, which must be preserved (adjusted only for
     the new two-read shape).
   - `message.setDeletedAt(...)` / `messageRepository.save(message)` unchanged, at the end.
   - Do **not** move the moderation-status or already-deleted checks ahead of the lock — only the two pure
     identity/authorization checks move. This mirrors `cancelBookingAsParent`, where business-state checks
     (the `CAPTURE_PENDING` interlock) stay after the locked re-read and only the ownership check moves
     ahead of it.
   - No change to any exception type, error code, or HTTP status. This is a reordering, not a behavior
     change — every existing 403/409/204 outcome must be identical after the change.

2. **`BookingService.readStatusOrThrow` maps a corrupted `status` column value to 404 instead of 409.**

   Verified current state (`BookingService.java:594-601`): the `catch (IllegalArgumentException e)` block
   throws `ResourceNotFoundException("Booking " + booking.getId() + " has unrecognised status '" +
   booking.getStatus() + "'", "booking")`, which `ApiAdvice` maps to 404. The row exists and the caller
   (a parent who can see it in their own bookings list) is not asking about a booking that doesn't exist —
   the server simply cannot interpret a value it holds. A 404 tells the caller to stop looking for
   something that is, in fact, right there. Only reachable via bad data or a rolling deploy that introduces
   a status value the running node's `BookingStatus` enum does not yet know.

   The fix has an exact precedent two methods away, already wired through `ApiAdvice`: `cancelBookingAsParent`
   throws `new ResponseStatusException(HttpStatus.CONFLICT, "booking.paymentInProgress")` at
   `BookingService.java:650` for a different bad-state case, and `ApiAdvice.responseStatusExceptionHandler`
   (`ApiAdvice.java:118-121`) already maps any `ResponseStatusException` to its own status code with
   `errorKey = "generic.requestError"` and the exception's `getReason()` as the message — no new exception
   class, no new i18n key, no `ApiAdvice` change needed.

   **Required:** change `readStatusOrThrow`'s catch block to throw
   `new ResponseStatusException(HttpStatus.CONFLICT, "Booking " + booking.getId() + " has unrecognised status '" + booking.getStatus() + "'")`
   instead of `ResourceNotFoundException`. Import `org.springframework.web.server.ResponseStatusException`
   (already imported in this file, used by `cancelBookingAsParent`) and `org.springframework.http.HttpStatus`
   if not already imported. No other line in the method changes.

3. **`messages_de.properties` has only 1 of the 8 `video.*` keys the `en-US`/`fr-FR` backend bundles carry.**

   Verified current state: `grep -n "^video\." src/main/resources/i18n/messages_de.properties` returns
   exactly one hit — `video.rateLimitExceeded` (line 80, added by `skillars-deferred-32` AC2, under a
   `# Video-Fehler` comment it introduced). `messages_en.properties` and `messages_fr.properties` each
   carry all 8: `video.notFound`, `video.validationFailed`, `video.quotaExceeded`, `video.rateLimitExceeded`,
   `video.playbackDenied`, `video.providerError`, `video.sessionExpired`, `video.terminalStateViolation`
   (lines 111-118 in `messages_en.properties`). This is masked in practice because the frontend renders its
   own i18n string from `errorKey` rather than `errorMsg.message` for video errors — but any future caller
   that reads `errorMsg.message` directly for a German-locale video error other than the rate-limit one
   silently gets English text with no signal that translation is missing.

   **Required:** add the 7 missing keys to `messages_de.properties`, in the same relative order as the
   `en-US`/`fr-FR` bundles (i.e. `notFound`, `validationFailed`, `quotaExceeded` before the existing
   `rateLimitExceeded`; `playbackDenied`, `providerError`, `sessionExpired`, `terminalStateViolation`
   after it), under the existing `# Video-Fehler` comment. Use idiomatic German in the same formal
   ("Sie") register the rest of the German bundle uses (see nearby `booking.*` keys for tone). Suggested
   starting-point translations (verify accuracy/tone before shipping, this story's creation pass is not a
   certified translation review):
   - `video.notFound=Das angeforderte Video konnte nicht gefunden werden.`
   - `video.validationFailed=Videovalidierung fehlgeschlagen: {0}`
   - `video.quotaExceeded=Der Upload würde Ihr Video-Speicherkontingent überschreiten.`
   - `video.playbackDenied=Der Zugriff auf die Wiedergabe dieses Videos wurde verweigert.`
   - `video.providerError=Bei einem Videoanbieter ist ein Fehler aufgetreten. Bitte versuchen Sie es erneut oder kontaktieren Sie den Support.`
   - `video.sessionExpired=Die Upload-Sitzung ist abgelaufen. Bitte starten Sie einen neuen Upload.`
   - `video.terminalStateViolation=Diese Aktion ist im aktuellen Videostatus nicht zulässig.`

   No code changes — this AC is properties-file-only.

4. **`skillars-deferred-31`'s own Completion Notes contain a stale fixture-count claim.**

   Verified current state: `skillars-deferred-31-coach-accept-flow-refresh-reschedule-error-split-and-slu-repository-coverage.md:601`
   (inside "Completion Notes List", the AC5 entry) still reads *"fixture block `9630000001`–`9630000003`"*.
   The same file's later review-follow-up section (around line 650) already narrowed this to
   `9630000001`–`9630000002` after a review finding removed a dead id, and the actually-shipped test
   (`SluWeeklySnapshotRepositoryIT.java:34`, comment: *"Fixture id range 9630000001-9630000002, claimed in
   docs/testing/test-data-isolation.md"*) and `docs/testing/test-data-isolation.md:207` both already agree
   on the 2-id range. Only the one paragraph inside the merged story's own Completion Notes still has the
   stale 3-id count — the registry and the code already agree with each other and need no change.

   **Required:** edit `skillars-deferred-31-....md:601`, changing `9630000001`–`9630000003` to
   `9630000001`–`9630000002`. Documentation-only, zero code risk, no other files touched.

5. **Ledger hygiene.** Annotate each of the four items in the **Deferred Items Closed** table above with
   `[CLOSED by skillars-deferred-33 ACn]` plus a description of what actually shipped, in
   `deferred-work.md`, at the item's existing location — the format every prior `skillars-deferred-*` story
   used.

   Additionally, two items found to be stale ledger noise during this story's creation, corrected here
   rather than filed as new work:
   - **`## Deferred from: code review of skillars-deferred-12-booking-payment-review-integrity` (2026-08-04),
     D5** (`BatchAcceptPaymentIT` unscoped teardown / unsafe `session_replication_role` toggling, citing
     `BatchAcceptPaymentIT.java:784`). Verified: the current file is 273 lines total, has no `@AfterEach`, no
     manual `DELETE FROM`, and no `session_replication_role` toggling anywhere — it now extends
     `AbstractIntegrationTest` and relies entirely on fixture-id-scoped `JdbcTemplate` inserts, per the
     `test-data-isolation.md` convention formalized since this item was filed. The premise no longer matches
     the file at all. Mark this item `[STALE — verified against current code by skillars-deferred-33 story
     creation, 2026-08-18: BatchAcceptPaymentIT no longer has any unscoped teardown; superseded by a later
     fixture-isolation refactor]` rather than deleting it outright, so the history of why it's gone is kept.
   - **The two near-duplicate `cancelBookingAsParent` lock-timeout-hint items** under
     `## Deferred from: code review of skillars-uat-3-payment-capture-integrity-and-backup-retention`
     (2026-08-11) — the unlabeled bullet and "D12" (both describing a settle-side write racing the cancel
     path's locked read with no effective timeout bound). Verified: `BookingRepository.findByIdForUpdate`
     already carries the `jakarta.persistence.lock.timeout` hint both items worry is missing — but
     `skillars-deferred-23`'s own later, empirically-verified finding (`## Deferred from:
     skillars-deferred-23-...`, 2026-08-14) proves that hint has **zero effect** on this Hibernate/Postgres
     combination for any of the four repositories that carry it, `BookingRepository` included. These two
     uat-3-era items and the deferred-23 item describe the same underlying gap; the uat-3 pair's framing
     (worried about an absent hint) is superseded by the deferred-23 item's correct diagnosis (the hint is
     present but inert). Annotate both uat-3 bullets: `[SUPERSEDED by the more precise diagnosis at
     ` + "`## Deferred from: skillars-deferred-23-flaky-perf-test-dead-code-and-ops-hygiene-fixes` (2026-08-14)" + ` —
     same underlying gap, tracked there with the correct root cause and the decision-needed fix options.
     Not a separate item. — noted by skillars-deferred-33 story creation, 2026-08-18]`.

   Also annotate the two decision-gated items closed by AC6 and AC7 (the `videoQuotaExceededHandler`
   status item under `## Deferred from: skillars-deferred-32 implementation`, and the refund-eligibility
   disagreement item under `## Deferred from: code review of skillars-deferred-28-...story creation`) with
   `[CLOSED by skillars-deferred-33 AC6]` and `[CLOSED by skillars-deferred-33 AC7]` respectively, each
   recording the decision Mbah made during this story's creation and what shipped.

   File no new items under a `## Deferred from: skillars-deferred-33 implementation` section unless the dev
   agent defers something while implementing — if nothing is deferred during implementation, state that
   explicitly in the Completion Notes rather than omitting the section.

6. **`videoQuotaExceededHandler` returns 429 (Too Many Requests) for a hard storage-quota rejection.**

   Verified current state (`VideoApiAdvice.java:73-79`): `QuotaExceededException` — thrown at all three
   remaining storage-quota sites since `skillars-deferred-32` AC2 split the transient rate-limit cause off
   into its own exception — is still handled with `@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)`. 429
   correctly describes the rate-limit case (retry after the window clears) but not this one: a full storage
   quota does not clear by retrying, only by upgrading the plan or freeing space. `boot/axios.js` has no
   429-specific interception (`grep -n "429" src/frontend/src/boot/axios.js` returns nothing), so changing
   the status carries no frontend-retry-logic risk.

   **Decided by Mbah during this story's creation (2026-08-18):** change to `403 Forbidden` — "you may not
   upload more right now," reusing a status already used elsewhere in this app for authorization-style
   rejections, with no new semantics to teach the frontend.

   **Required:**
   - Change `videoQuotaExceededHandler`'s `@ResponseStatus` from `HttpStatus.TOO_MANY_REQUESTS` to
     `HttpStatus.FORBIDDEN`. No other line in the handler changes — same `errorKey`, same message key, same
     `videoMetrics.recordError` call.
   - `videoRateLimitExceededHandler` (the sibling handler `skillars-deferred-32` AC2 added) is **not**
     touched — it keeps 429, which is correct for its transient cause.
   - Add a new `VideoUploadResourceIT` case proving the new status, mirroring the pattern
     `skillars-deferred-32`'s code review already established for `UPLOAD_RATE_LIMITED`
     (`VideoUploadResourceIT.initiateUpload_rateLimited_returns429WithRateLimitedKey`): mock
     `videoService.initializeUpload(...)` to throw `QuotaExceededException`, assert
     `status().isForbidden()` and `jsonPath("$.errorMsg.errorKey").value("QUOTA_EXCEEDED")`.
   - Mutation-verify: temporarily revert the `@ResponseStatus` to `TOO_MANY_REQUESTS`, confirm the new test
     fails, restore byte-identical.
   - `videoQuotaExceededHandler`'s existing behavior for the filestorage module's own, unrelated
     `QuotaExceededException` (`platform.filestorage.contract.exception`, handled separately by
     `ApiAdvice:516` with `FileStorageErrorCode.QUOTA_EXCEEDED`) is untouched — this AC only touches the
     video module's handler.

7. **The `refundEligibility` string field is write-only and disagrees with the real refund logic.**

   Verified current state: `Booking.refundEligibility` (`Booking.java:71-72`, `@Column(name =
   "refund_eligibility", length = 10)`) is set by `BookingService.applyRefundLogic` (`BookingService.java:765-796`)
   to `"FULL"`/`"PARTIAL"`/`"NONE"`, but a full-repo grep for `getRefundEligibility` returns **zero hits** —
   no DTO, mapper, or frontend file ever reads it. `applyRefundLogic` has no side effect other than this
   setter call across all four of its `switch` cases; it exists solely to populate a field nothing reads.

   The actual, money-moving refund decision is a *different* signal: `cancelBookingAsParent`'s
   `refundEligible` boolean (`BookingService.java:648-649`, exact `isAfter(now.plus(24h))` comparison),
   published on `BookingCancelledByParentEvent` and consumed by
   `CancellationRefundService.onBookingCancelledByParent` (`CancellationRefundService.java:34-49`), which is
   strictly binary — full refund/pack-session-restore or nothing. There is no code path anywhere that
   issues a partial refund; `"PARTIAL"` is unreachable in practice, and the dead field's 6-24h/24-25h
   disagreement with the real boolean (documented in `BookingService.java:780-787`'s own comment, and in the
   ledger item this AC closes) has zero live product impact today — but it is dead, disagreeing scaffolding
   that should not linger.

   `Booking.refundAmount` (`Booking.java:74-75`, `@Column(name = "refund_amount")`) was found to be equally
   dead during this AC's verification — grepped for `refundAmount`/`setRefundAmount`/`getRefundAmount`
   across `src/main/java`: the only hit is the field declaration itself. Nothing ever sets or reads it. Not
   named in the original ledger item, but the same cleanup applies for the same reason.

   **Decided by Mbah during this story's creation (2026-08-18):** delete both dead fields entirely, rather
   than fixing their math to match the real boolean or building real partial-refund support.

   **Required:**
   - Remove `applyRefundLogic(booking, event, currentStatus)`'s call site inside `transitionInternal`
     (`BookingService.java:151`). `currentStatus` remains needed by the `bookingStateMachine.validate`/
     `targetStatus` calls immediately above it — do not remove the variable, only the one call line.
   - Delete the `applyRefundLogic` private method entirely (`BookingService.java:765-797`).
   - Delete `Booking.refundEligibility` and `Booking.refundAmount` fields and their `@Column` annotations
     (`Booking.java:71-75`).
   - New Flyway migration dropping both columns from `booking.bookings`, following the existing
     single-statement drop-column style (`V96__drop_coach_subscription_stripe_customer_id.sql`):
     `V97__drop_booking_refund_eligibility_and_amount.sql`:
     ```sql
     ALTER TABLE booking.bookings DROP COLUMN refund_eligibility;
     ALTER TABLE booking.bookings DROP COLUMN refund_amount;
     ```
   - Grep-verify after: `grep -rn "refundEligibility\|refundAmount\|applyRefundLogic" src/main/java` returns
     zero hits.
   - No test currently references `applyRefundLogic`, `refundEligibility`, or `refundAmount` directly
     (confirmed: none of the existing `BookingServiceTest` cases assert on these) — removal needs no test
     updates, only confirmation that the full `BookingServiceTest`/`BookingServiceConcurrencyIT`/relevant
     ITs still pass, proving nothing else depended on the removed code path.
   - Do **not** touch `refundEligible` (the boolean), `BookingCancelledByParentEvent`, or
     `CancellationRefundService` — those are the live, correct refund mechanism and are unaffected by this
     AC.

## Tasks / Subtasks

- [x] **Task 1 — AC1: reorder `softDeleteMessage`'s lock vs. authorization checks**
  - [x] Restructure `MessagingService.softDeleteMessage` per AC1's exact required shape: unlocked
        `findById` → membership check → sender check → locked `findByIdForUpdate` → moderation check →
        already-deleted check → write
  - [x] Preserve the existing code comment's intent (adjust wording for the new two-read shape) explaining
        why the already-deleted check must run under the lock
  - [x] Confirm `SoftDeleteIT`'s existing 5 tests (`softDeleteMessage_bySender_returns204`,
        `softDeleteMessage_byNonSender_returns403`, `softDeleteMessage_alreadyDeleted_returns409`,
        `softDeleteMessage_underReview_returns403WithCode`, `concurrentDoubleSoftDelete_exactlyOneSucceeds_oneConflicts`)
        all still pass unchanged — this is a behavior-preserving reorder, not a behavior change
  - [x] Create `src/test/java/com/softropic/skillars/platform/messaging/service/MessagingServiceTest.java`
        (new file — none currently exists), `@ExtendWith(MockitoExtension.class)`, mocking all 9
        constructor dependencies (`ConversationRepository`, `MessageRepository`, `BookingRepository`,
        `PlayerProfileRepository`, `CoachProfileRepository`, `ModerationService`,
        `MessagingEmitterRegistry`, `ConversationCreationHelper`, `AgePolicyService`)
  - [x] Add two tests mirroring `BookingServiceTest.cancelBookingAsParent_wrongParent_isRejectedBeforeTakingTheRowLock`
        (`BookingServiceTest.java:666`) exactly: `softDeleteMessage_wrongConversation_isRejectedBeforeTakingTheRowLock`
        and `softDeleteMessage_wrongSender_isRejectedBeforeTakingTheRowLock`, each asserting the thrown
        `OperationNotAllowedException` and `verify(messageRepository, never()).findByIdForUpdate(any())`
  - [x] Mutation-verify: temporarily revert to the single-`findByIdForUpdate`-first shape, confirm both new
        tests fail, restore byte-identical
- [x] **Task 2 — AC2: `readStatusOrThrow` 404 → 409**
  - [x] Change the catch block's thrown exception from `ResourceNotFoundException` to
        `new ResponseStatusException(HttpStatus.CONFLICT, ...)`, same message text
  - [x] Add a new `BookingServiceTest` case (e.g. `cancelBookingAsParent_corruptedStatusColumn_returns409NotResourceNotFound`):
        mock `bookingRepository.findById` and `findByIdForUpdate` to both return a `Booking` owned by the
        calling parent with a `status` value that is not a valid `BookingStatus` constant (e.g.
        `"BOGUS_STATUS"`), call `cancelBookingAsParent`, assert the thrown exception `isInstanceOf(ResponseStatusException.class)`
        and `.extracting(ResponseStatusException::getStatusCode).isEqualTo(HttpStatus.CONFLICT)` — not
        `ResourceNotFoundException`
  - [x] Mutation-verify: temporarily revert to `ResourceNotFoundException`, confirm the new test fails,
        restore byte-identical
- [x] **Task 3 — AC3: German video i18n gap**
  - [x] Add the 7 missing `video.*` keys to `messages_de.properties`, matching `en-US`/`fr-FR` key order
  - [x] Grep-verify: `grep -c "^video\." src/main/resources/i18n/messages_de.properties` returns 8,
        matching the count in `messages_en.properties`/`messages_fr.properties`
- [x] **Task 4 — AC4: deferred-31 doc fix**
  - [x] Edit `skillars-deferred-31-....md:601`, `9630000001`–`9630000003` → `9630000001`–`9630000002`
- [x] **Task 5 — AC5: ledger hygiene**
  - [x] Six `[CLOSED by skillars-deferred-33 ACn]` annotations in `deferred-work.md`, at each item's
        existing location (four from the original bundle, plus AC6's and AC7's decision-gated items)
  - [x] `[STALE — verified ...]` annotation on the `BatchAcceptPaymentIT` D5 item (deferred-12 review
        section)
  - [x] `[SUPERSEDED by ...]` annotations on both uat-3 lock-timeout-hint bullets, pointing at the
        deferred-23 item
  - [x] `sprint-status.yaml` entry for this story
- [x] **Task 6 — AC6: video quota status 429 → 403**
  - [x] Change `videoQuotaExceededHandler`'s `@ResponseStatus` to `HttpStatus.FORBIDDEN`
  - [x] New `VideoUploadResourceIT` case asserting 403 + `"errorKey":"QUOTA_EXCEEDED"`, mirroring the
        existing `initiateUpload_rateLimited_returns429WithRateLimitedKey` pattern
  - [x] Mutation-verify: revert to `TOO_MANY_REQUESTS`, confirm the new test fails, restore byte-identical
- [x] **Task 7 — AC7: delete dead refund fields**
  - [x] Remove the `applyRefundLogic(...)` call in `transitionInternal`
  - [x] Delete the `applyRefundLogic` method entirely
  - [x] Delete `Booking.refundEligibility` and `Booking.refundAmount` fields
  - [x] New migration `V97__drop_booking_refund_eligibility_and_amount.sql`
  - [x] Grep-verify zero remaining hits for `refundEligibility`/`refundAmount`/`applyRefundLogic` in
        `src/main/java`
  - [x] Confirm `BookingServiceTest`/`BookingServiceConcurrencyIT`/relevant booking ITs still pass unchanged
- [x] **Task 8 — verification**
  - [x] Full `mvn -o verify` green (record surefire/failsafe counts, compare against `skillars-deferred-32`'s
        baseline: surefire 890, failsafe 932)
  - [x] `npx eslint` — no frontend files are touched by this story, so this is a no-op check; confirm no
        frontend file appears in the diff

## Dev Notes

### Established conventions this story must follow

- **The unlocked-read-then-authorize-then-locked-reread pattern is this codebase's established answer to
  "don't let an unauthorized caller pin a row lock."** `BookingService.cancelBookingAsParent` is the
  reference implementation (`BookingService.java:617-650`) and its own code comment names the exact
  `MessagingService.softDeleteMessage` finding AC1 closes. Copy its shape, not just its intent — same two
  reads, same "authorization before the lock, business-state checks after it" split.
- **`ResponseStatusException` + `ApiAdvice.responseStatusExceptionHandler`** is this codebase's established
  way to return an ad-hoc status/message pair without a new exception class or i18n key.
  `cancelBookingAsParent`'s `booking.paymentInProgress` throw (`BookingService.java:650`) is the reference
  usage; AC2 follows the identical shape.
- **`JpaRepository`'s inherited `findById(Long)`** is always available alongside a repository's own
  `findByIdForUpdate` custom query method — no new repository method is needed for AC1's unlocked read
  (confirmed: `MessageRepository extends JpaRepository<Message, Long>`).
- **Backend messages:** every wire message key needs a line in all four of `messages.properties`,
  `messages_en.properties`, `messages_de.properties`, `messages_fr.properties`. AC3 is the unusual case
  where the *English and French default* are already correct and only the German bundle has a gap — do
  not touch `messages.properties` or `messages_en.properties`/`messages_fr.properties`, only
  `messages_de.properties`.
- **Tests:** Mockito unit tests for `*Service` classes use `@ExtendWith(MockitoExtension.class)` with
  `@Mock`/`@InjectMocks` or explicit constructor injection (check `BookingServiceTest`'s own top-of-class
  setup for the pattern this project uses) — AC1's new `MessagingServiceTest` should match
  `BookingServiceTest`'s existing style exactly, not invent a new one. AssertJ `assertThat`. Do not mock
  the database in an IT (n/a here — both new tests are unit-level, no IT is added by this story).
- **No frontend files are touched by any AC in this story** — AC1/AC2/AC6/AC7 are backend-only, AC3 is a
  backend properties file, AC4 is a doc file. `npx eslint`/`npx quasar build` are not exercised meaningfully
  by this diff; Task 8's frontend check is a confirmation, not real verification work.
- **The `skillars-deferred-32`-established pattern of mocking the service layer in a `@WebMvcTest` to prove
  a wire-contract mapping** (`VideoUploadResourceIT`, `VideoService` mocked but the real `VideoApiAdvice`
  loaded) is AC6's reference test pattern — do not write a full `@SpringBootTest`/Testcontainers IT for a
  status-code-only change when the existing slice-test class already proves the same class of thing for
  `UPLOAD_RATE_LIMITED`.
- **Deleting a field is not the same as deleting a migration.** AC7 adds a new migration
  (`V97__...`) rather than editing any historical one — Flyway migrations already applied to any
  environment are immutable. `V96__drop_coach_subscription_stripe_customer_id.sql` is the reference example
  of this exact pattern (a later migration dropping a column an earlier one added).

### Files being modified — current state and what must be preserved

- **`MessagingService.softDeleteMessage`** (`:293-321`) — currently: locked read first, then conversation
  check, then sender check, then moderation-status check, then already-deleted check, then write. AC1
  changes **only the ordering of reads/checks**, not any exception type, error code, HTTP status, or the
  final write. The existing code comment at `:294-297` explains why the already-deleted check needs the
  lock — preserve that reasoning, adapted for the new unlocked-then-locked shape.
- **`BookingService.readStatusOrThrow`** (`:594-601`) — a private method called from two sites
  (`transitionInternal` at `:148`, `cancelBookingAsParent` at `:639`). AC2 changes **only the exception
  type thrown in the catch block** — the `try`/`BookingStatus.valueOf` line, the method signature, and both
  call sites are unchanged.
- **`messages_de.properties`** — AC3 is a pure addition of 7 lines under the existing `# Video-Fehler`
  comment (line 79-80). No existing line in this file changes.
- **`skillars-deferred-31-....md`** — AC4 changes exactly one line (601). Do not touch any other part of
  that story's Completion Notes, Dev Agent Record, or Change Log — it is a merged, closed story; this is a
  factual correction only, not a re-opening of its scope.
- **`VideoApiAdvice.videoQuotaExceededHandler`** (`:73-79`) — AC6 changes only the `@ResponseStatus` value.
  Do not touch `videoRateLimitExceededHandler` (the sibling handler, stays 429) or the filestorage module's
  separate, unrelated `QuotaExceededException` handling in `ApiAdvice:516`.
- **`BookingService.transitionInternal`/`applyRefundLogic`** (`:144-155,765-797`) — AC7 removes one call
  line and one entire private method. `transitionInternal`'s other lines (`validateActorAuthorization`,
  `getBookingOrThrow`, `readStatusOrThrow`, `bookingStateMachine.validate`/`targetStatus`,
  `booking.setStatus`, `bookingRepository.save`, the `publishEvent` branch) are unrelated to refund logic
  and unchanged. `cancelBookingAsParent`'s own `refundEligible` boolean computation (`:648-649`) and the
  `BookingCancelledByParentEvent` it publishes are the *live* refund mechanism and are untouched by this AC.

### Why the "picked-up" markers matter

This story's four items are marked `[PICKED UP by skillars-deferred-33-... story creation, 2026-08-18]`
in `deferred-work.md` at story-creation time (not at closure time, unlike the `[CLOSED by ...]` pattern
used elsewhere) — per explicit instruction, so a future bundling-story-creation pass does not re-select
the same items while this one is still in flight. If this story is abandoned rather than shipped, remove
the picked-up markers so the items become selectable again.

### Project Structure Notes

- AC7 needs **one** Flyway migration (`V97__drop_booking_refund_eligibility_and_amount.sql`) — the only
  schema change in this story; AC1-AC6 need none. No REST endpoint, DTO, or mapper changes anywhere in this
  story. No `@PreAuthorize` question arises (AC1 does not change who is authorized, only when the check
  runs relative to the lock).
- `MessagingServiceTest` is a new file; it belongs beside the package's other service-layer tests at
  `src/test/java/com/softropic/skillars/platform/messaging/service/`.

### References

- `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java:293-321`
- `src/main/java/com/softropic/skillars/platform/messaging/repo/MessageRepository.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:144-155,594-601,617-650,765-797`
- `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java:71-75`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BookingCancelledByParentEvent.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/CancellationRefundService.java:34-49`
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:118-121`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java:73-87`
- `src/main/resources/db/migration/V96__drop_coach_subscription_stripe_customer_id.sql` (reference
  drop-column pattern)
- `src/main/resources/i18n/messages_de.properties:74-81`
- `src/main/resources/i18n/messages_en.properties:111-118`
- `src/main/resources/i18n/messages_fr.properties:101-108`
- `src/test/java/com/softropic/skillars/platform/messaging/api/SoftDeleteIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java:660-674`
- `src/test/java/com/softropic/skillars/platform/video/api/VideoUploadResourceIT.java` (reference
  slice-test pattern for AC6, from `skillars-deferred-32`'s code review)
- `_bmad-output/implementation-artifacts/skillars-deferred-31-coach-accept-flow-refresh-reschedule-error-split-and-slu-repository-coverage.md:601`
- `_bmad-output/implementation-artifacts/deferred-work.md` (sections dated 2026-08-04 through 2026-08-18)
- `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

None — no failures required debugging beyond the deliberate mutation-verification reverts recorded in
Completion Notes below.

### Completion Notes List

- **AC1.** Restructured `softDeleteMessage` to unlocked `findById` → conversation-membership check →
  sender-ownership check → locked `findByIdForUpdate` → moderation check → already-deleted check → write,
  mirroring `cancelBookingAsParent`'s shape exactly. **Discovered beyond the story's literal spec, via the
  existing `SoftDeleteIT#concurrentDoubleSoftDelete_exactlyOneSucceeds_oneConflicts` regressing from 1
  success/1 conflict to 2 successes/0 conflicts against a live Postgres (Testcontainers):** the naive
  two-read port stales the locked entity's field values through Hibernate's persistence-context identity
  map — `findByIdForUpdate`'s JPQL `@Lock` query takes the DB row lock but, because the row is already
  managed in the persistence context from the earlier unlocked `findById`, returns that same cached Java
  object without re-populating its fields from the fresh `SELECT`. A concurrent second deleter therefore
  read a stale (pre-commit) `deletedAt == null` after acquiring the lock and proceeded to delete again.
  Fixed with `entityManager.refresh(message, LockModeType.PESSIMISTIC_WRITE)` immediately after the locked
  read — an identical idiom already established and code-commented in
  `BookingService.createBookingRequest:233-237` for the exact same Hibernate gotcha on `CoachProfile`. This
  required adding `EntityManager` as a 10th constructor dependency to `MessagingService` (`@RequiredArgsConstructor`
  auto-wires it; production behavior unaffected — Spring already provides it). New
  `src/test/java/.../messaging/service/MessagingServiceTest.java` (file did not exist before), 2 tests
  (`softDeleteMessage_wrongConversation_isRejectedBeforeTakingTheRowLock`,
  `softDeleteMessage_wrongSender_isRejectedBeforeTakingTheRowLock`), both mutation-verified against the
  single-locked-read-first shape (both fail as expected) and again against a version missing the
  `EntityManager` constructor param (compile failure, proving the dependency is load-bearing, not just the
  reorder). All 5 pre-existing `SoftDeleteIT` tests re-run against a live Testcontainers Postgres and pass
  unchanged, including the concurrency test that surfaced the gotcha.
- **AC2.** `readStatusOrThrow`'s catch block now throws `ResponseStatusException(HttpStatus.CONFLICT, ...)`
  instead of `ResourceNotFoundException`, reusing `ApiAdvice.responseStatusExceptionHandler` — no new
  exception class or i18n key. New `BookingServiceTest#cancelBookingAsParent_corruptedStatusColumn_returns409NotResourceNotFound`,
  mutation-verified (reverted, confirmed failure, restored byte-identical). All 30 `BookingServiceTest`
  cases pass.
- **AC3.** Added the 7 missing `video.*` keys to `messages_de.properties` in the same order as the
  `en-US`/`fr-FR` bundles. `grep -c "^video\."` now returns 8 in all three bundles. Properties-only, no
  code changed.
- **AC4.** One-line fix: `skillars-deferred-31-....md:601` `9630000001`–`9630000003` →
  `9630000001`–`9630000002`. No other line touched.
- **AC5.** All 6 items from the Deferred Items Closed table annotated `[CLOSED by skillars-deferred-33 ACn]`
  with a description of what shipped, at their existing `deferred-work.md` locations — the `[PICKED UP]`
  markers story creation had left on them are now fully resolved. The `[STALE ...]` annotation on the
  `BatchAcceptPaymentIT` D5 item and the two `[SUPERSEDED ...]` annotations on the uat-3 lock-timeout-hint
  bullets were already applied at story-creation time (2026-08-18) and needed no further change. One new
  item filed under a new `## Deferred from: skillars-deferred-33 implementation (2026-08-18)` section,
  discovered during AC7 verification: `docs/dev-docs/booking/index.html` and
  `docs/business-docs/money/index.html` (generated onboarding docs, out of this story's File List) still
  describe the now-deleted `applyRefundLogic`/`refundEligibility` mechanism as if live — left for a future
  docs-regeneration pass.
- **AC6.** `videoQuotaExceededHandler`'s `@ResponseStatus` changed from `TOO_MANY_REQUESTS` to `FORBIDDEN`;
  the sibling `videoRateLimitExceededHandler` untouched (still 429). New
  `VideoUploadResourceIT#initiateUpload_quotaExceeded_returns403WithQuotaExceededKey`, mutation-verified
  (reverted, confirmed "expected:<403> but was:<429>", restored byte-identical). All 11 `VideoUploadResourceIT`
  cases pass (10 pre-existing + 1 new).
- **AC7.** Deleted `applyRefundLogic`'s call site in `transitionInternal`, the method itself, and
  `Booking.refundEligibility`/`Booking.refundAmount` (the latter confirmed equally dead — zero read/write
  sites anywhere in `src/main/java` beyond its own field declaration — during this AC's own verification,
  though only `refundEligibility` was named in the original ledger item). New migration
  `V97__drop_booking_refund_eligibility_and_amount.sql`. `grep -rn "refundEligibility|refundAmount|applyRefundLogic"
  src/main/java` returns zero hits (including a stale comment reference at `BookingService.java:635` that
  the AC's own grep requirement forced a rewrite of, since a comment naming a deleted method would have
  failed the check). **One live test broke as a direct, necessary consequence and was fixed, not just
  confirmed:** `PaymentPendingSweeperIT#packFundedStrandedBooking_pastGrace_isSweptToDeclined` queried the
  now-dropped `refund_eligibility` column directly via raw SQL (`jdbcTemplate.queryForObject("SELECT
  refund_eligibility FROM booking.bookings WHERE id = ?", ...)`) — that assertion was removed since both the
  column and the mechanism it verified ("a swept booking must not imply a refund") no longer exist. This
  file was not in the story's predicted File List; added below. `BookingServiceTest` (30), `BookingServiceConcurrencyIT`
  (4) and `PaymentPendingSweeperIT` (8, post-fix) all pass.
- **Task 8.** Full `mvn -o verify` green. **Surefire: 893 run, 0 failures, 0 errors, 1 skipped** (baseline
  890 + 3 = exactly the 2 new `MessagingServiceTest` cases + 1 new `BookingServiceTest` case). **Failsafe:
  934 run, 0 failures, 0 errors, 4 skipped** (baseline 932 in this story's Dev Notes was the count recorded
  at story-creation time, before `skillars-deferred-32`'s own code review added 1 more IT bringing the
  actual pre-this-story baseline to 933; 933 + 1 new `VideoUploadResourceIT` case = 934 — reconciles
  exactly). Both skip counts (1 surefire, 4 failsafe) are unchanged from baseline. Counts were computed from
  the authoritative `target/{surefire,failsafe}-reports/*.txt` files filtered to this run's timestamp window,
  after the raw terminal capture of one run was corrupted mid-stream by `InputValidatorTest`'s
  binary-artifact debug printing (a pre-existing, unrelated test-output quirk, not a build failure — the
  build's own exit code was 0 throughout). No frontend file appears in the diff — `npx eslint`/`npx quasar
  build` are confirmed no-ops for this story as the Dev Notes predicted.
- **Nothing was deferred during implementation** beyond the one docs-drift item filed under AC5 above — all
  7 ACs shipped exactly as scoped, with the two necessary corrections (AC1's `EntityManager.refresh`, AC7's
  `PaymentPendingSweeperIT` fix) required to make the story's own stated acceptance bar (behavior-preserving,
  all existing tests green) actually true rather than merely claimed.
- **Review follow-up (`bmad-code-review`, 2026-08-18).** 17 raw findings → 2 patch, 2 defer, 12 dismissed, 0
  AC violations. Both patch items resolved: added an in-file comment to
  `PaymentPendingSweeperIT#packFundedStrandedBooking_pastGrace_isSweptToDeclined` explaining the dropped
  `refund_eligibility` assertion (previously explained only in `deferred-work.md`, not in the test itself);
  strengthened `BookingServiceTest#cancelBookingAsParent_corruptedStatusColumn_returns409NotResourceNotFound`
  with `ResponseStatusException.getReason()` assertions (booking id, "unrecognised status", the bogus value)
  so the test actually proves AC2's "honest answer" claim rather than only the status code, mutation-verified
  by reverting to `ResourceNotFoundException` and confirming the strengthened test fails. Both deferred items
  (bare `DROP COLUMN` migration commentary; rolling-deploy column-drop ordering risk) left open, matching
  this codebase's existing accepted-risk precedents (`V96`, `V94`, the `AdminAlertType` rolling-deploy item)
  at current single-instance deployment scale. All 30 `BookingServiceTest` and 8 `PaymentPendingSweeperIT`
  cases re-run and pass after the fixes.

### File List

**Backend — source:**
- `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java`
- `src/main/resources/i18n/messages_de.properties`
- `src/main/resources/db/migration/V97__drop_booking_refund_eligibility_and_amount.sql` (new)

**Backend — tests:**
- `src/test/java/com/softropic/skillars/platform/messaging/service/MessagingServiceTest.java` (new)
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/video/api/VideoUploadResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeperIT.java` (not
  predicted by the story — required by AC7's column drop; see Completion Notes)

**Docs / tracking:**
- `_bmad-output/implementation-artifacts/skillars-deferred-31-coach-accept-flow-refresh-reschedule-error-split-and-slu-repository-coverage.md`
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Change Log

| Date | Change | Author |
|---|---|---|
| 2026-08-18 | AC1: reordered `MessagingService.softDeleteMessage` to unlocked-read-then-authorize-then-locked-reread; added `EntityManager.refresh` fix for a discovered Hibernate identity-map staleness bug; new `MessagingServiceTest`. | dev-story (Claude Sonnet 5) |
| 2026-08-18 | AC2: `BookingService.readStatusOrThrow` now throws `ResponseStatusException(CONFLICT)` instead of `ResourceNotFoundException` for a corrupted status column. | dev-story (Claude Sonnet 5) |
| 2026-08-18 | AC3: added 7 missing `video.*` keys to `messages_de.properties`. | dev-story (Claude Sonnet 5) |
| 2026-08-18 | AC4: corrected stale fixture-count claim in `skillars-deferred-31`'s Completion Notes. | dev-story (Claude Sonnet 5) |
| 2026-08-18 | AC5: ledger hygiene — 6 `[CLOSED by skillars-deferred-33 ACn]` annotations in `deferred-work.md`; 1 new deferred item filed for stale generated-docs references. | dev-story (Claude Sonnet 5) |
| 2026-08-18 | AC6: `VideoApiAdvice.videoQuotaExceededHandler` now returns 403 instead of 429; new `VideoUploadResourceIT` case. | dev-story (Claude Sonnet 5) |
| 2026-08-18 | AC7: deleted dead `applyRefundLogic`/`Booking.refundEligibility`/`Booking.refundAmount`; new `V97` migration; fixed `PaymentPendingSweeperIT` assertion that queried the dropped column. | dev-story (Claude Sonnet 5) |
| 2026-08-18 | Story status: ready-for-dev → in-progress → review. | dev-story (Claude Sonnet 5) |
| 2026-08-18 | Review follow-up: added explanatory comment to `PaymentPendingSweeperIT`'s dropped-column assertion; strengthened `BookingServiceTest`'s AC2 test with reason-message assertions, mutation-verified. | dev-story (Claude Sonnet 5) |

### Review Findings

Reviewed via `bmad-code-review` (Blind Hunter + Edge Case Hunter + Acceptance Auditor, `review_mode=full`), 2026-08-18. 17 raw findings (14 Blind Hunter, 3 Edge Case Hunter, 0 Acceptance Auditor — 0 AC violations). After dedup and verification against the actual diff/code: 0 decision-needed, 2 patch, 2 defer, 12 dismissed as noise/false-positive/matches-precedent.

- [x] [Review][Patch] `PaymentPendingSweeperIT#packFundedStrandedBooking_pastGrace_isSweptToDeclined` drops its `refund_eligibility` column assertion with no in-file comment explaining why — the only explanation lives in `deferred-work.md`. Add a one-line comment noting the column was dropped by Deferred-33 AC7. [`src/test/java/com/softropic/skillars/platform/payment/service/PaymentPendingSweeperIT.java:149`] — **Resolved:** added an in-file comment at the end of the test explaining the column was dropped by Deferred-33 AC7. All 8 `PaymentPendingSweeperIT` cases re-run and pass.
- [x] [Review][Patch] New `BookingServiceTest#cancelBookingAsParent_corruptedStatusColumn_returns409NotResourceNotFound` asserts only the HTTP status code, never the exception's reason message — add a message-content assertion so the test actually proves AC2's "honest answer" claim, not just the status code. [`src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java:311-320`] — **Resolved:** added `rse.getReason()` assertions for the booking id, "unrecognised status", and the bogus status value. Mutation-verified (reverted to `ResourceNotFoundException`, confirmed the strengthened test fails, restored byte-identical). All 30 `BookingServiceTest` cases re-run and pass.
- [x] [Review][Defer] `V97__drop_booking_refund_eligibility_and_amount.sql` drops two columns with a bare, uncommented `DROP COLUMN` pair under an `ACCESS EXCLUSIVE` lock; the "nothing reads these columns" justification was verified only via grep of `src/main/java`, not any SQL views/reporting/export tooling. [`src/main/resources/db/migration/V97__drop_booking_refund_eligibility_and_amount.sql`] — deferred, pre-existing pattern: `V96` (this story's own cited precedent) has identical zero commentary, and the `ACCESS EXCLUSIVE`-lock-class concern is already tracked in the ledger against `V94` as an accepted, not-yet-actionable gap at current table size.
- [x] [Review][Defer] Dropping `booking.bookings.refund_eligibility`/`refund_amount` in the same change as removing the fields from `Booking.java` carries the standard rolling-deploy column-drop ordering risk — an old-code instance still mapping the dropped columns would fail on INSERT/UPDATE if it ran concurrently against the migrated schema. [`src/main/resources/db/migration/V97__drop_booking_refund_eligibility_and_amount.sql`, `src/main/java/com/softropic/skillars/platform/booking/repo/Booking.java:67-73`] — deferred, same risk class this project already accepts for its current single-instance Docker Compose deployment model (matches the ledger's existing `AdminAlertType` rolling-deploy precedent); not reachable today.

**Dismissed (12), with verification:**
- New 409's lack of an `errorKey`/i18n key, and its raw (non-templated) message text — matches the existing `cancelBookingAsParent`/`booking.paymentInProgress` precedent exactly; `ApiAdvice.responseStatusExceptionHandler` treats every `ResponseStatusException` the same way (`generic.requestError` key, raw reason as message) regardless of content, and AC2 mandates this exact string verbatim.
- "No new imports shown for `ResponseStatusException`/`HttpStatus`" — verified both already imported (`BookingService.java:54,58`), pre-existing from `cancelBookingAsParent`'s established use of the same pattern.
- `EntityManager.refresh` after `findByIdForUpdate` called a "leaky abstraction"/"double-locking" — verified this is an established codebase idiom, used identically twice already in `BookingService` (`createBookingRequest:235`, `suspendCoach:328`) for the same documented Hibernate identity-map gotcha; not novel to this diff.
- New `MessagingServiceTest` "only covers negative paths, not the refresh fix or the double-delete race" — the double-delete race is covered by the pre-existing `SoftDeleteIT#concurrentDoubleSoftDelete_exactlyOneSucceeds_oneConflicts` IT (Task 1 explicitly requires confirming it still passes unchanged); the new unit test correctly doesn't duplicate IT-level coverage.
- Deleted `applyRefundLogic`'s other three event branches (`CANCEL_COACH`/`NO_SHOW_PLAYER`/`NO_SHOW_COACH`) not individually re-justified — moot, since `Booking.refundEligibility` (the only thing any branch wrote to) no longer exists as a field at all.
- German `video.validationFailed={0}` placeholder not verified against en/fr — verified: all three bundles (`en`, `fr`, `de`) use exactly one `{0}` placeholder.
- 429→403 status change "unverified frontend risk" — verified during story creation per the spec text (`grep -n "429" boot/axios.js` returns nothing); decision was explicitly made by Mbah, not inferred.
- Ticket-style comments (`Deferred-33 AC7`, `Deferred-16 D2`) embedded in source — established codebase convention, e.g. pre-existing unchanged `// UAT.3 AC2` comment at `BookingService.java:634`.
- `ChronoUnit` possibly left unused — verified false, still used at `BookingService.java:629,654`.
- `BookingStatus.valueOf(null)` NPE path uncaught — verified unreachable: `booking.bookings.status` is `VARCHAR(30) NOT NULL` at the DB level (`V31__booking_requests.sql:13`), and both call sites pass an already-persisted `Booking`.
- Raw booking id/status embedded in the new `ResponseStatusException`'s message (i18n leak) — AC2 mandates this exact string verbatim; not a deviation.
- (Acceptance Auditor layer: 0 findings — full pass against all 7 ACs.)
