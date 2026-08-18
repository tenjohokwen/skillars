# Story Deferred-32: Drill-Upload & Video-Quota Error-Code Splits, Authorization Toast Copy, Batch-Limit Accuracy & Error-Key Integration Coverage

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Skillars maintainer,
I want six small, independently-verified deferred items closed — two wire-contract error codes that each
collapse unrelated rejection causes into one string so the frontend cannot tell them apart, an
authorization toast whose wording names the wrong object on four of its five call sites, a batch-limit
toast that quotes a number it cannot know is current, and two acknowledged test-coverage gaps left behind
by `skillars-deferred-31`'s own error-code split — so that each of six unrelated, previously-deferred
defects, spanning the session, video and booking modules plus the frontend i18n bundles, gets fixed
without bundling any of them into a larger story that would need its own design pass.

### Why this story exists

Drawn directly from `_bmad-output/implementation-artifacts/deferred-work.md`, per Mbah's direction to
group small, unrelated, already-deferred items into one story to reduce dev overhead — the same spirit as
`skillars-deferred-11/20/21/22/23/24/25/26/27/28/29/30/31`. All items below were independently
re-verified against **current** code (post-merge of PR #61, `master` at `51b7593`) during this story's
creation on 2026-08-18, by reading the throw sites, the `@RestControllerAdvice` handlers, the frontend
`errorKey` chains and the existing IT classes directly rather than trusting the ledger's own citations.

**Three of the ledger's own claims were wrong and are corrected in the ACs below.** They are called out
explicitly because a dev agent that trusts the ledger text over the code will ship the wrong fix:

1. The `QUOTA_EXCEEDED` item names **two** throw sites of the video-module `QuotaExceededException`;
   there are **four** — `VideoService.java:135` (`retryUpload`) and, in a different class entirely,
   `QuotaService.java:76`, which is the **authoritative** locked enforcement point. Splitting "one code
   per site" off that enumeration would miscode the retry path and miss the site real concurrent traffic
   is most likely to hit.
2. The same item quotes the toast as *"Storage quota exceeded. Upgrade your plan to upload more videos."*
   That string no longer exists — `skillars-deferred-29` replaced it with a hedge naming **both** causes.
   The defect is therefore milder than filed (nobody is actively misled today) but not closed: the hedge
   is imprecise for both causes rather than wrong for one.
3. The `batchSizeExceeded` item prescribes *"the backend passes the real limit as a message argument."*
   That alone cannot work. `ErrorMsg` is `record ErrorMsg(String errorKey, String message)` — it carries
   the **resolved** message, not the arguments — and `BookingRequestPage.vue` renders its own frontend
   i18n string from `errorKey`, never `errorMsg.message`. See AC4 for the fix that actually closes it.

Additionally, the `DRILL_UPLOAD_NOT_ALLOWED` item was already corrected once in the ledger (two sites → 
three, split by **cause** not by site). AC1 implements that correction, not the original filing.

## Deferred Items Closed

| Source | Item | Current location (re-verified 2026-08-18) | AC | Planned outcome |
|---|---|---|---|---|
| `skillars-deferred-30` story creation (2026-08-18), as corrected by that story's code review | `SessionErrorCode.DRILL_UPLOAD_NOT_ALLOWED` is thrown by two unrelated causes across three sites; the frontend cannot distinguish them from the wire `errorKey` | `DrillUploadService.java:57,76-78,109`, `SessionErrorCode.java:9`, `DrillDetailPanel.vue:388` | 1 | Split by **cause** into `DRILL_NOT_OWNED` (`:57`, `:109`) and `DRILL_VIDEO_ALREADY_LINKED` (`:78`); upload chain maps both; errorKey assertions added to 3 existing ITs |
| code review of `skillars-deferred-29-...` (2026-08-17) | `VideoErrorCode.QUOTA_EXCEEDED` cannot distinguish a transient upload rate limit from a hard storage quota | `VideoService.java:135,231,249`, `VideoApiAdvice.java:72-77`, `DrillDetailPanel.vue:384` | 2 | New `UPLOAD_RATE_LIMITED` for the rate-limit site only; `QUOTA_EXCEEDED` keeps the two storage sites; two precise toasts replace one hedge |
| `skillars-deferred-31` implementation (2026-08-18) | `booking.errors.requestNotAllowed` is worded for the parent booking-request path but is now the `MISSING_RIGHTS` message on four coach-/reschedule-side paths too | `i18n/{en-US,de-DE,fr-FR}` + 5 call sites across 4 pages | 3 | One object-agnostic authorization string in all three bundles; no key rename, no call-site change |
| code review of `skillars-deferred-28-...` (2026-08-17) | `booking.errors.batchSizeExceeded`'s `{max}` quotes the client's cached batch limit, which is by definition not the limit that rejected the request | `BookingRequestPage.vue:543,599-600`, `BookingBatchService.java:96-99` | 4 | Re-fetch `getBatchConfig()` on that branch, update the ref, quote the fresh number; new no-number fallback key if the re-fetch fails |
| code review of `skillars-deferred-31-...` (2026-08-18) | Thin IT coverage for the `RescheduleService` throw sites `skillars-deferred-31` AC3 re-coded off `MISSING_RIGHTS` | `RescheduleResourceIT.java`, `RescheduleService.java:79-183` | 5 | errorKey assertions for the uncovered sites (**8**, not the 7 the ledger claims — see AC5) |
| code review of `skillars-deferred-31-...` (2026-08-18) | `BATCH_NONE_ACCEPTED` is verified only at Mockito level, never through `ApiAdvice` to an HTTP body | `BookingBatchServiceTest.java:560-615`, `BookingBatchResourceIT.java` | 6 | New `BookingBatchResourceIT` case asserting 403 + `"errorKey":"booking.batchNoneAccepted"` |

**Explicitly NOT in this story** (considered during story creation and rejected — do not implement):

- **Per-booking outcome reporting from `acceptAll`** (which bookings failed and why) — re-filed by
  `skillars-deferred-31` AC2 and still open. It needs a result DTO, a REST contract change (`void`/204 →
  a body), a store change and new partial-outcome rendering on `CoachBookingRequestsPage`. Out of a
  bundled-fix story's bar. AC6 covers only the *existing* `BATCH_NONE_ACCEPTED` code's IT gap.
- **Changing the HTTP status of `videoQuotaExceededHandler`.** It returns `429 TOO_MANY_REQUESTS` for
  both causes today. 429 is right for the rate limit and arguably wrong for a storage quota, but changing
  it is a wire-contract change with its own blast radius. AC2 splits the **code only** and keeps 429 for
  both; AC7 files the status-code question as a new ledger item.
- **Branching `DrillDetailPanel`'s remove-video `catch`** (`:426`). It is a bare `catch { … removeFailed }`
  by design and never inspects `errorKey`, so `deleteVideo`'s ownership throw shows a generic toast
  regardless of AC1. Adding a branch there is a UX task, not part of the wire-contract fix. AC1's value
  at `:109` is that the contract stops lying, not that a new toast appears.
- **Renaming `booking.errors.requestNotAllowed` or splitting it per object type** (booking / batch /
  player+pack). AC3 deliberately re-words the one existing key rather than shipping three near-duplicates
  with no rule for choosing between them — the same reasoning `skillars-deferred-30` AC2 applied to the
  `payment.sessionPack.*` keys. If Mbah prefers per-object strings, that is a larger i18n task.
- **The two refund/no-show product questions** (`skillars-deferred-28` story-creation section) — both
  need a product decision on refund semantics, not a mechanical fix.
- **All other open ledger items** — every one inspected during this story's creation either needed a
  product/design decision, targeted an unreachable or already-mitigated code path, or duplicated a fix a
  prior story already made.

## Acceptance Criteria

1. **`SessionErrorCode.DRILL_UPLOAD_NOT_ALLOWED` is thrown by two unrelated causes across three sites, and
   the wire `errorKey` is identical for all of them.**

   Verified current state — `SessionErrorCode.getErrorCode()` returns `this.name()`, so the wire
   `errorKey` is literally `DRILL_UPLOAD_NOT_ALLOWED` at every site:
   - `DrillUploadService.java:56-58` — `initiateUpload`'s ownership check
     (`!"COACH".equals(drill.getLibraryType()) || !coachId.equals(drill.getOwnerCoachId())`), message
     `"Drill upload not allowed"`.
   - `DrillUploadService.java:74-79` — a `READY` video is already linked to this drill, message
     `"A video is already linked to this drill. Remove it before uploading a new one."`
   - `DrillUploadService.java:108-110` — the **same ownership check again**, in `deleteVideo`.

   `DrillDetailPanel.vue:388` maps the single code to
   `session.drillLibrary.upload.videoAlreadyLinked` — so an ownership rejection would render
   "A video is already linked to this drill." That path is not reachable from the panel *today*
   (`DrillLibraryService.listDrills` scopes non-`PLATFORM` results to the calling coach via
   `findByOwnerCoachIdAndStatus`, and the template independently gates on `libraryType === 'COACH'`), but
   that is an accident of today's data flow, not a guarantee the wire contract makes.

   **Required:** split by **cause**, not by site — three sites collapse to two causes:
   - New `SessionErrorCode.DRILL_NOT_OWNED` at `:57` **and** `:109` (both ownership).
   - New `SessionErrorCode.DRILL_VIDEO_ALREADY_LINKED` at `:78`.
   - Remove `DRILL_UPLOAD_NOT_ALLOWED` from the enum once it has zero references. Do **not** leave it as a
     dead constant.
   - `DrillDetailPanel.vue`'s upload chain maps `DRILL_VIDEO_ALREADY_LINKED` → the existing
     `session.drillLibrary.upload.videoAlreadyLinked` (string unchanged) and `DRILL_NOT_OWNED` → a new
     `session.drillLibrary.upload.notOwned` key in all three bundles.

   Both codes travel on `OperationNotAllowedException`, which `ApiAdvice` maps to **403 regardless of
   code** — no status-code change.

2. **`VideoErrorCode.QUOTA_EXCEEDED` conflates a transient per-minute rate limit with a hard storage
   quota.**

   Verified current state — the video-module `QuotaExceededException` is thrown at **four** sites across
   **two** classes (the ledger item names two of them; `VideoService.java:135` and all of `QuotaService`
   were missed). **One is transient, three are hard:**

   | Site | Cause | Nature |
   |---|---|---|
   | `VideoService.java:230-232` | `initializeUpload`'s per-`ownerId` rate-limit check (`rateLimitingService.tryConsume(..., "video.upload.init", rpm, 1, MINUTES)`), via the `QuotaExceededException(String ownerId, String reason)` constructor with `"rate limit exceeded"` | **Transient** — clears within a minute |
   | `VideoService.java:248-250` | `initializeUpload`'s `quotaProvider.check` storage test, via `QuotaExceededException(ownerId, 0L, fileSizeBytes)` | Hard — needs a plan upgrade |
   | `VideoService.java:134-136` | `retryUpload`'s identical `quotaProvider.check`, same constructor | Hard |
   | `QuotaService.java:76` | `reserve(...)`'s post-`SELECT FOR UPDATE` check, via `QuotaExceededException(ownerId, storageQuota, bytes)` | Hard — **and authoritative** |

   **`QuotaService.java:76` is the site that matters most and the one the ledger never mentions.** Both
   `VideoService` storage checks call `quotaProvider.check`, whose own comment says *"ADVISORY ONLY — no
   lock held. A concurrent `reserve()` may drain quota between this check and the caller's subsequent
   `reserve()` call. `reserve()` is the authoritative gate."* `reserve` is then called at
   `VideoService.java:148` and `:257`, immediately after each advisory check passes. Under concurrent
   uploads racing for the last bytes of quota, `QuotaService.java:76` is the throw that actually fires.

   All four carry `VideoErrorCode.QUOTA_EXCEEDED`, all land in `VideoApiAdvice.java:72-77`, and all emit
   `errorKey = "QUOTA_EXCEEDED"`. `DrillDetailPanel.vue:384` renders one hedged string that names both
   causes ("Upload limit reached. Try again in a moment, or upgrade your plan for more storage.") — added
   by `skillars-deferred-29`, which is why nobody is actively misled today and why this is a precision
   fix, not an urgent one.

   **Required:**
   - New `VideoErrorCode.UPLOAD_RATE_LIMITED`. `QUOTA_EXCEEDED` **stays** and keeps **all three** storage
     sites, `QuotaService.java:76` included. No change is needed at any of the three — this is stated so
     that whoever writes the verification does not stub only `quotaProvider.check` and believe the
     storage path is covered.
   - New `RateLimitExceededException` in `platform/video/contract/exception/` carrying
     `UPLOAD_RATE_LIMITED`, thrown at `VideoService.java:231`. Remove the now-unused
     `QuotaExceededException(String ownerId, String reason)` constructor — it exists only for that site.
   - New `@ExceptionHandler` in `VideoApiAdvice` for it, `@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)`
     (**unchanged status**, see "Explicitly NOT in this story"), message key `video.rateLimitExceeded`,
     and `videoMetrics.recordError(operationFromMdc(), UPLOAD_RATE_LIMITED.getErrorCode())` mirroring the
     existing handler exactly.
   - `video.rateLimitExceeded` in **all four** `messages*.properties`.
   - `DrillDetailPanel.vue` gains an `UPLOAD_RATE_LIMITED` branch → new
     `session.drillLibrary.upload.rateLimited` key in all three bundles ("Too many uploads. Wait a moment
     and try again." — no upgrade advice). The existing `quotaExceeded` key drops its rate-limit hedge and
     becomes storage-only ("Upload would exceed your video storage quota. Upgrade your plan to upload
     more.") in all three bundles.

3. **`booking.errors.requestNotAllowed` names the wrong object on four of its five call sites.**

   The string is *"You do not have access to the player or session pack in this request."* — accurate only
   for `BookingRequestPage.submit()` (`:512`), where `MISSING_RIGHTS` genuinely means the caller does not
   own the player profile or the session pack. `skillars-deferred-30` AC3 and `skillars-deferred-31` AC3
   reused the same key at four more sites where "player or session pack" names the wrong object:
   - `BookingRequestPage.vue:561` (`submitBatchRequest`) — parent does not own the *player*
   - `CoachBookingRequestsPage.vue:226` (`handleAcceptAll`) — coach does not own the *batch*
   - `CoachCommandCenterPage.vue:412` (`handleAcceptReschedule`) — coach does not own the *booking*
   - `ParentBookingsPage.vue:226` (`submitReschedule`) — parent does not own the *booking*

   Every one is a genuine authorization failure and the wording misleads nobody into a bad action, so this
   is a copy task, not a defect.

   **Required:** re-word the existing `booking.errors.requestNotAllowed` to one object-agnostic
   authorization string in all three bundles — English: *"You do not have permission to perform this
   action."* — with idiomatic de-DE and fr-FR equivalents. **Do not rename the key, do not add sibling
   keys, do not touch any of the five call sites.** Add a comment above the key in `en-US` recording that
   it is deliberately object-agnostic because five call sites across four pages share it.

4. **`booking.errors.batchSizeExceeded`'s `{max}` quotes a number the client cannot know is current.**

   `BookingRequestPage.vue:543` passes `{ max: maxBatchSize.value }`, populated from `getBatchConfig()` at
   `:599-600` on mount and defaulting to `ref(5)` at `:258`. But `toggleSlotInBasket` (`:300`) already caps
   the basket at that same cached value, so the **only** way to reach a server-side
   `booking.batchSizeExceeded` is for the server's `booking.batch.maxSize` (seeded `'5'` by
   `V36__booking_batches.sql:19`, read live by `BookingBatchService.java:96`) to have dropped **below** the
   client's cached copy since mount — exactly the case in which the toast confidently states the wrong
   number. The backend's own `messages*.properties` copy deliberately names no figure for this reason.

   **The ledger's prescribed fix does not work and must not be implemented.** It says "the backend passes
   the real limit as a message argument." `ApiAdvice.logErrorAndReturnDTO` does accept varargs and would
   resolve them — but `ErrorMsg` is `record ErrorMsg(String errorKey, String message)`, carrying the
   *resolved* message only, and this call site renders its own frontend i18n string keyed off `errorKey`
   and never reads `errorMsg.message`. A backend argument would be invisible here.

   **Required:** in `BookingRequestPage.vue`'s `booking.batchSizeExceeded` branch, `await getBatchConfig()`
   first, assign the result to `maxBatchSize.value` (which also re-syncs the basket cap for the rest of the
   session), then toast with the refreshed number. Wrap the re-fetch in its own `try`; on failure toast a
   new no-number key `booking.errors.batchSizeExceededUnknownMax` ("You have reached the maximum number of
   sessions allowed in one batch.", matching the backend's figure-free wording) in all three bundles.
   Add a comment naming why the re-fetch is there, so the next author does not "simplify" it away.

5. **8 of the 10 `RescheduleService` throw sites `skillars-deferred-31` AC3 re-coded have no IT proving
   their wire `errorKey`.**

   Verified by reading `RescheduleResourceIT` end to end. Only three errorKey assertions exist:
   `booking.slotUnavailable` (`:383`, pre-existing), `booking.notReschedulable` (`:439`) and
   `booking.rescheduleNotPending` (`:470`) — the last two added by `skillars-deferred-31` AC3, whose Task 3
   explicitly scoped itself to "at least two assertions", making this an acknowledged residual rather than
   an oversight.

   **The ledger says 7 uncovered sites; the code says 8.** The enumeration below is the authority — it was
   built by reading every `throw` in the file and cross-checking each against the IT's assertions:

   | Method | Line | Code | Wire key | Covered today? |
   |---|---|---|---|---|
   | `requestReschedule` | `:74-76` | `BOOKING_NOT_RESCHEDULABLE` | `booking.notReschedulable` | ✅ `:422` |
   | `requestReschedule` | `:79-80` | `START_TIME_IN_PAST` | `booking.startTimeInPast` | ❌ |
   | `requestReschedule` | `:83-84` | `INVALID_TIME_RANGE` | `booking.invalidTimeRange` | ❌ |
   | `requestReschedule` | `:102-106` | `INVALID_SESSION_DURATION` | `booking.invalidSessionDuration` | ❌ |
   | `requestReschedule` | `:110-112` | `RESCHEDULE_ALREADY_PENDING` | `booking.rescheduleAlreadyPending` | ❌ |
   | `acceptReschedule` | `:153-154` | `RESCHEDULE_NOT_PENDING` | `booking.rescheduleNotPending` | ❌ |
   | `acceptReschedule` | `:157-159` | `BOOKING_NOT_RESCHEDULABLE` | `booking.notReschedulable` | ❌ |
   | `acceptReschedule` | `:162-163` | `START_TIME_IN_PAST` | `booking.startTimeInPast` | ❌ |
   | `acceptReschedule` | `:181-182` | `RESCHEDULE_NOT_PENDING` (locked re-read) | `booking.rescheduleNotPending` | ❌ |
   | `declineReschedule` | `:252-253` | `RESCHEDULE_NOT_PENDING` | `booking.rescheduleNotPending` | ✅ `:451` |

   `INVALID_SESSION_DURATION` (`:102-106`) is included deliberately: `skillars-deferred-31`'s own Dev Notes
   record it as already-correctly-coded and therefore untouched by AC3, but it is on the same `errorKey`
   contract, in the same method, with the same zero coverage — closing 7 of 8 and leaving one would be an
   arbitrary line.

   **Required:** an IT case per uncovered row asserting **403 + the exact `"errorKey":"…"` substring**, in
   the assertion style already used at `:437-440`. Reuse the class's existing fixture helpers and id
   block; do **not** claim a new prefix (see Dev Notes). **`:181-182` is the one exception to that assertion style,
   deliberately.** The locked re-read inside `acceptReschedule` is reached by the two lock-interleaving
   tests the class already has (`:537`, `:629`) — extend `:537` rather than writing a third — but those
   tests call `rescheduleService.acceptReschedule(...)` **directly on the service** inside an
   `ExecutorService` `Runnable`, capturing a raw `Throwable`, precisely so the `CountDownLatch` /
   `Thread.sleep(1500)` timing around the 5-second DB lock timeout stays controllable. There is no HTTP
   response and no JSON body to substring-match. Assert at the **Java level** instead —
   `assertThat(acceptOutcome.get()).asInstanceOf(throwable(OperationNotAllowedException.class))
   .extracting(OperationNotAllowedException::getErrorCode).isEqualTo(BookingError.RESCHEDULE_NOT_PENDING)`
   or equivalent. **Do not rewrite these tests to go through `httpTestClient`** to make the assertion
   style uniform: that would inject session-cookie handling and network latency into a test whose whole
   point is deterministic lock timing, trading a real assertion for a flaky one. If the race still cannot
   be pinned, leave the row uncovered with an in-test comment saying so. Do not fake it with a mock.

   Each new assertion must be **mutation-verified**: change the `BookingError` constant at the throw site,
   watch the new test fail, restore it byte-identical.

6. **`BATCH_NONE_ACCEPTED` is verified only at Mockito level, never through `ApiAdvice` to an HTTP body.**

   `BookingBatchServiceTest:560-615` has two mutation-verified cases asserting the Java exception and its
   `ErrorCode` enum directly against a mocked service. Nothing exercises the real serialization path, so
   nothing proves `booking.batchNoneAccepted` actually appears in a 403 body — unlike AC3's own codes in
   the same diff, which got IT coverage.

   **Required:** a new `BookingBatchResourceIT` case that drives a real `PENDING` batch to zero accepted
   bookings over HTTP and asserts **403 + `"errorKey":"booking.batchNoneAccepted"`**. The cheapest
   deterministic route is path (b) of the two the production comment names: a batch that is still `PENDING`
   but whose every booking has left `REQUESTED` — the class already builds exactly that shape in
   `acceptAll_withASiblingDeclinedBeforehand_endsPartiallyAccepted` (`:422`); decline **every** sibling
   instead of one. Reuse the class's existing fixture helpers and id block. Mutation-verify by reverting
   `BookingBatchService.java:292-293` to a bare `return` and watching the new test fail.

7. **Ledger hygiene.** Annotate each of the six items in the **Deferred Items Closed** table above with
   `[CLOSED by skillars-deferred-32 ACn]` plus a description of what actually shipped, in
   `deferred-work.md`, at the item's existing location — the format every prior `skillars-deferred-*`
   story used. Where this story's verification **contradicts** the item's own text (the three corrections
   listed in "Why this story exists", plus the `DRILL_UPLOAD_NOT_ALLOWED` site count), say so explicitly in
   the closure note rather than silently shipping something different from what the item asked for.

   File these **new** items in a `## Deferred from: skillars-deferred-32 implementation (2026-08-18)`
   section:
   - **`videoQuotaExceededHandler` returns 429 for a hard storage quota.** AC2 split the code but kept the
     status. 429 means "retry later"; a storage quota does not clear on retry. Correct status is arguably
     `413`/`507`/`403`. A status-code change is a wire-contract change with its own blast radius, which is
     why AC2 scoped itself to the code.
   - **`skillars-deferred-31`'s Completion Notes claim `SluWeeklySnapshotRepositoryIT`'s fixture block is
     `9630000001`–`9630000003`.** The shipped IT uses only `9630000001`–`9630000002`, and
     `docs/testing/test-data-isolation.md:207` records the correct range. Documentation-only inaccuracy in
     a completed story's notes; the registry and the code agree with each other.
   - Anything the dev agent defers while implementing, with the same "do not fix here" rationale.

   A third candidate was found during this story's creation and **fixed directly instead of filed**:
   `sprint-status.yaml` recorded `skillars-deferred-31` as `review` although PR #61 had merged and its
   code review had completed. Flipped to `done` on 2026-08-18 on Mbah's instruction. Do not re-file it.

## Tasks / Subtasks

- [x] **Task 1 — AC1: split `DRILL_UPLOAD_NOT_ALLOWED` by cause**
  - [x] `SessionErrorCode`: add `DRILL_NOT_OWNED`, `DRILL_VIDEO_ALREADY_LINKED`; delete
        `DRILL_UPLOAD_NOT_ALLOWED` **after** the last reference is gone
  - [x] `DrillUploadService.java:57` and `:109` → `DRILL_NOT_OWNED`; `:78` → `DRILL_VIDEO_ALREADY_LINKED`
  - [x] `grep -rn "DRILL_UPLOAD_NOT_ALLOWED" src/` returns zero hits when done (this is the miss check)
  - [x] `DrillDetailPanel.vue:388` → two branches; add `session.drillLibrary.upload.notOwned` to all three
        bundles beside the existing `videoAlreadyLinked`
  - [x] Add `"errorKey":"DRILL_NOT_OWNED"` assertions to the three existing ITs that already prove 403:
        `initiateUpload_platformDrill_returns403` (`:175`), `initiateUpload_otherCoachDrill_returns403`
        (`:194`), `deleteVideo_platformDrill_returns403` (`:383`) — no new fixtures needed
  - [x] Add one IT (or extend `initiateUpload_replacesProcessingVideo_…`'s fixture shape) proving a `READY`
        linked video returns `"errorKey":"DRILL_VIDEO_ALREADY_LINKED"`
  - [x] Mutation-verify: swap the two constants at `:57`/`:78`, watch the ITs fail, restore
- [x] **Task 2 — AC2: split video rate limit off `QUOTA_EXCEEDED`**
  - [x] `VideoErrorCode`: add `UPLOAD_RATE_LIMITED` (keep `QUOTA_EXCEEDED`)
  - [x] New `platform/video/contract/exception/RateLimitExceededException` carrying `UPLOAD_RATE_LIMITED`,
        same `ApplicationException` + log-context shape as `QuotaExceededException`
  - [x] `VideoService.java:231` throws it; delete the now-unused
        `QuotaExceededException(String ownerId, String reason)` constructor
  - [x] `VideoApiAdvice`: new handler, `@ResponseStatus(TOO_MANY_REQUESTS)`, key `video.rateLimitExceeded`,
        `videoMetrics.recordError(...)` mirroring `videoQuotaExceededHandler`
  - [x] `video.rateLimitExceeded` in all four `messages*.properties`
  - [x] `DrillDetailPanel.vue`: `UPLOAD_RATE_LIMITED` branch → new `…upload.rateLimited`; re-word
        `…upload.quotaExceeded` to storage-only. Both in all three bundles
  - [x] Verify `VideoMetricsTest` still passes (`:88,:91` assert the `QUOTA_EXCEEDED` tag — that path is
        unchanged, but read it before assuming)
  - [x] Miss check (Task 1 has one, this task needs the equivalent):
        `grep -rn "new QuotaExceededException" src/main/java` must return **four** hits — one in
        `filestorage` (unrelated, do not touch) and three in `video` (`VideoService:135,249`,
        `QuotaService:76`). `VideoService:231` must no longer be among them
- [x] **Task 3 — AC3: object-agnostic authorization copy**
  - [x] Re-word `booking.errors.requestNotAllowed` in `en-US`, `de-DE`, `fr-FR`; add the why-comment in
        `en-US`
  - [x] Grep-verify the five call sites are unchanged and no sixth exists
- [x] **Task 4 — AC4: batch-limit accuracy**
  - [x] `BookingRequestPage.vue`: re-fetch `getBatchConfig()` inside the `booking.batchSizeExceeded` branch,
        assign `maxBatchSize.value`, toast with the fresh number; inner `try` → fallback key on failure
  - [x] `booking.errors.batchSizeExceededUnknownMax` in all three bundles
  - [x] Comment naming why the re-fetch exists
- [x] **Task 5 — AC5: reschedule errorKey ITs**
  - [x] One case per uncovered row in AC5's table, asserting 403 + exact `"errorKey":"…"`
  - [x] `:181-182` — extend `:537` with a **Java-level** `getErrorCode()` assertion, NOT an HTTP-body
        `errorKey` substring: that test calls the service directly to keep the lock timing deterministic
        and has no HTTP response. Do not convert it to `httpTestClient`. If the race cannot be pinned,
        leave uncovered **with an in-test comment**
  - [x] Mutation-verify each new assertion; restore each throw site byte-identical
- [x] **Task 6 — AC6: `batchNoneAccepted` IT**
  - [x] New `BookingBatchResourceIT` case: decline every sibling in a `PENDING` batch, then `acceptAll`;
        assert 403 + `"errorKey":"booking.batchNoneAccepted"`
  - [x] Mutation-verify against a reverted bare `return`
- [x] **Task 7 — AC7: ledger + docs**
  - [x] Six `[CLOSED by skillars-deferred-32 ACn]` annotations, each naming any contradiction found
  - [x] New `## Deferred from: skillars-deferred-32 implementation (2026-08-18)` section with the three
        items named in AC7 plus anything deferred during dev
  - [x] `sprint-status.yaml` entry for this story
- [x] **Task 8 — verification**
  - [x] Full `mvn -o verify` green (record surefire/failsafe counts)
  - [x] `npx eslint` exit 0 on every changed frontend file; `npx quasar build` compiles
  - [x] Every new i18n key resolves in **all three** frontend bundles and **all four** backend bundles
        (grep each key name, count the hits)

## Dev Notes

### Established conventions this story must follow

- **Error codes:** `SessionErrorCode` and `VideoErrorCode` are plain enums implementing `ErrorCode` whose
  `getErrorCode()` returns `this.name()` — so the wire `errorKey` is the **bare enum name in
  SCREAMING_SNAKE**, unlike `BookingError`, whose `getErrorCode()` is an exhaustive `switch` returning a
  dotted string. Do not "harmonise" them in this story; AC1 and AC2 add constants to the `name()`-style
  enums and the frontend branches must compare against the SCREAMING_SNAKE literal, exactly as
  `DrillDetailPanel.vue:384,388` already does.
- **`OperationNotAllowedException` → HTTP 403** regardless of the `ErrorCode` it carries. AC1 therefore
  changes no status code.
- **Backend messages:** every wire message key needs a line in **all four** of `messages.properties`,
  `messages_en.properties`, `messages_de.properties`, `messages_fr.properties`. `messages.properties` is
  the default bundle and duplicates the English text. Note AC1's codes need **no** backend message: they
  travel through `ApiAdvice.operationDeniedHandler`, whose `msgKey` is the error code itself, and the
  frontend renders from `errorKey` — check how the existing `DRILL_UPLOAD_NOT_ALLOWED` behaves before
  adding properties lines it does not need. AC2's `video.rateLimitExceeded` **does** need all four, because
  `VideoApiAdvice` passes an explicit message key.
- **Frontend i18n:** three bundles, `en-US` / `de-DE` / `fr-FR`. All user-facing text externalized
  (project-context rule). A key present in one bundle and missing from another renders the raw key path.
- **Frontend style:** `<script setup>`, `async`/`await` (never `.then()`), Prettier mandatory, API calls
  only via `src/api/*.api.js` (AC4 uses the already-imported `getBatchConfig` from
  `src/api/booking.api.js:60` — do not add a new axios call), shared state via Pinia.
- **Tests:** `@SpringBootTest` + Testcontainers via `AbstractIntegrationTest`; AssertJ `assertThat`;
  Instancio for generated data; Awaitility for async. Do not mock the database in an IT.
- **No frontend test infrastructure exists** — `package.json`'s `test` script is
  `echo "No test specified" && exit 0`. AC1's/AC2's/AC3's/AC4's frontend halves are therefore verified by
  build + eslint + code reading, and their behavioural claims flagged for human spot-check. This is a
  standing project gap, not a shortcut taken here.

### Test fixture ids — do not claim a new prefix

`docs/testing/test-data-isolation.md:190-220` is the registry. AC5 extends `RescheduleResourceIT`
(`9700000001`–`9700000011`, ⚠ shared with `ConversationResourceIT`) and AC6 extends
`BookingBatchResourceIT` (`9800000001`–`9800000020`, ⚠ shared with `MessagingAccessControlIT`); AC1
extends `DrillUploadResourceIT` (`9560000010`–`9560000030`, ⚠ shared with `DrillTagResourceIT`). **All
three classes already own their block and already build the fixtures these tests need** — reuse the
existing `setUp`/helper methods and add no new id literals. If a new literal proves unavoidable, take it
from a free block (`9310`–`9350`, `9370`–`9390`, `9400`–`9490`, `9520`–`9530`, `9640`–`9690`,
`9710`–`9790`, `9840`–`9890`, `9910`–`9990`) **and update the registry table, the claimed-prefixes
paragraph and the free-blocks paragraph together** — `skillars-deferred-30` AC6 had to reconcile those
three after they contradicted each other.

### Files being modified — current state and what must be preserved

- **`DrillUploadService.initiateUpload`** (`:50-101`) — dense ordering that other stories depend on: the
  ownership check precedes `checkDrillUploadGate`, the `videoTypeConstraints.validate` call is wrapped so
  `VideoValidationException` becomes a `DrillConstraintViolationException` rather than falling to the
  catch-all handler, and the `existing.isPresent()` branch publishes `VideoPhysicalDeletionEvent` for a
  replaced **non-`READY`** ref (a `READY` one has already thrown). AC1 changes **only the `ErrorCode`
  argument at three `throw` sites** — not the order, not the messages, not the event logic.
- **`VideoService.initializeUpload`** (`:222-…`) — a numbered five-step preamble (rate limit → validate →
  type constraints → quota → reserve). The rate-limit check is **step 1, before any other work**, and that
  position is deliberate. AC2 changes the exception **type** thrown at `:231`, nothing else. `retryUpload`
  (`:135`) and `initializeUpload` (`:249`) keep `QuotaExceededException` unchanged.
- **`VideoApiAdvice`** — has its own private `logErrorAndReturnDTO`/`toErrorDTO`/`logError` trio
  (`:149-171`), separate from `ApiAdvice`'s, and its `toErrorDTO` passes `null` args to `messageSource`.
  AC2's new handler must use **that** local trio, not `ApiAdvice`'s.
- **`DrillDetailPanel.vue:382-398`** — an `errorKey` chain in the upload `catch`. AC1 replaces one branch
  with two; AC2 adds one. Keep the existing branch order semantics (`security.featureGated` and the
  generic `else` stay last). The remove-video `catch` at `:426` is a bare `catch {}` and stays that way.
- **`BookingRequestPage.vue`** — `maxBatchSize` (`:258`) is read by `batchAtMax` (`:262`),
  `toggleSlotInBasket` (`:300`) and the `max` prop at `:107`, so AC4's re-assignment is load-bearing beyond
  the toast: it re-syncs the basket cap. That is intended. Do not make the re-fetch conditional on the
  toast succeeding.
- **`RescheduleService`** — three public methods: `requestReschedule` (`:54-120`), `acceptReschedule`
  (`:123-217`), `declineReschedule` (`:218-250`). The `:153` and `:181` `PENDING` checks and the `:252` one
  sit around `findByIdForUpdate` locks and `entityManager.refresh` calls (the `deferred-14`/`deferred-15`
  race guards, each with a comment saying so). **AC5 is test-only — it must not touch this file at all**,
  except transiently during mutation verification, restored byte-identical.
- **`BookingBatchService.acceptAll`** (`:235-300`) — per-booking `REQUIRES_NEW` via `perBookingTx`, an
  unlocked suspension pre-flight, a trailing batch+event transaction, and a 10-line comment on the
  `acceptedIds.isEmpty()` branch naming **both** paths that reach it. **AC6 is test-only — do not touch
  this file**, except transiently during mutation verification.
- **`BookingBatchResourceIT` / `RescheduleResourceIT` / `DrillUploadResourceIT`** — each has an
  `@AfterEach`/`tearDown` that deletes its own rows. The registry notes these classes share id literals
  with others and that teardown is what keeps them from colliding. Any new test must clean up on the same
  path as its siblings.

### Why the "ledger says N, code says M" corrections matter

Four separate claims in `deferred-work.md` disagreed with the code when re-read for this story (three
listed in "Why this story exists", plus the already-corrected drill site count). Every one of them would
have produced a subtly wrong fix if trusted: a miscoded retry path, a backend argument nothing renders, a
re-worded toast for a string that no longer exists, and a two-way split that leaves `deleteVideo`
ambiguous. The ledger's own header says forward-references and citations are unverified and age fast —
AC5's throw-site table and AC2's site list are in this story precisely so the dev agent does not have to
re-derive them, but **if the code disagrees with this story, the code wins** — say so in the Completion
Notes rather than bending the code to match the table.

### Project Structure Notes

- Backend packages follow `com.softropic.skillars.platform.{module}.{layer}`. AC2's new exception belongs
  in `platform/video/contract/exception/` beside `QuotaExceededException` and `PlaybackDeniedException`.
- No Flyway migration is needed by any AC. No new REST endpoint is added, so no `@PreAuthorize` question
  arises. No entity, DTO or mapper changes.
- `SessionErrorCode` and `VideoErrorCode` are contract-layer enums; adding constants there is the only
  contract-package change.
- Note the **two distinct `QuotaExceededException` classes** in this codebase —
  `platform.video.contract.exception` (AC2's) and `platform.filestorage.contract.exception` (handled by
  `ApiAdvice:516` with `FileStorageErrorCode.QUOTA_EXCEEDED` and the `storage.quotaExceeded` message).
  AC2 touches **only the video one** — and within the video module it touches only the `:231` rate-limit
  throw, leaving `QuotaService.java:76` and both `VideoService` storage throws on `QUOTA_EXCEEDED`
  unchanged. Check the import before editing.

### References

- `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java:50-115`
- `src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java`
- `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java:130-140,222-255`
- `src/main/java/com/softropic/skillars/platform/video/contract/VideoErrorCode.java`
- `src/main/java/com/softropic/skillars/platform/video/service/QuotaService.java:48-90` (the authoritative locked quota gate — AC2's fourth throw site)
- `src/main/java/com/softropic/skillars/platform/video/contract/exception/QuotaExceededException.java`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java:64-80,149-171`
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:258-275,510-520,614-630`
- `src/main/java/com/softropic/skillars/infrastructure/message/ErrorMsg.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:90-100,235-300`
- `src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java:54-253`
- `src/main/java/com/softropic/skillars/platform/booking/contract/BatchRuleViolationException.java`
- `src/frontend/src/components/session/DrillDetailPanel.vue:368-430`
- `src/frontend/src/pages/parent/BookingRequestPage.vue:107,258,262,297-302,505-565,595-602`
- `src/frontend/src/pages/parent/ParentBookingsPage.vue:226`
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:226`
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue:412`
- `src/frontend/src/api/booking.api.js:60`
- `src/frontend/src/boot/axios.js:130-176` (no 429 interception — AC2's status stays usable)
- `src/frontend/src/i18n/{en-US,de-DE,fr-FR}/index.js` (`en-US` anchors: `:330-346`, `:924-933`)
- `src/main/resources/i18n/messages{,_en,_de,_fr}.properties`
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java:163-660`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java:228-460`
- `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java:132-400`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java:555-615`
- `src/test/java/com/softropic/skillars/config/AbstractIntegrationTest.java`
- `docs/testing/test-data-isolation.md:190-225`
- `_bmad-output/implementation-artifacts/deferred-work.md` (sections dated 2026-08-17 → 2026-08-18)
- `_bmad-output/project-context.md`

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5), via the `bmad-dev-story` workflow.

### Debug Log References

- Initial draft of AC1's new IT (`initiateUpload_readyVideoAlreadyLinked_returns403WithVideoAlreadyLinkedKey`)
  reused the existing `insertTestVideo` helper, which inserts via a bare `jdbcTemplate.update` outside
  any transaction — per `DatabaseResetTestExecutionListener`'s own javadoc, Hikari's `auto-commit: false`
  means that insert is silently rolled back when the connection returns to the pool. Diagnosed via
  `EmptyResultDataAccessException` on a follow-up `SELECT`, not a symptom the assertion itself surfaced.
  Fixed by wrapping the video insert in the same `transactionTemplate.execute(...)` block as the
  `drill_video_refs` insert, inline in the new test, rather than changing the shared helper (which other,
  unrelated tests rely on behaving the same way it always has).
- All 9 mutation-verification passes (AC1: 2 constants; AC5: 8 throw sites; AC6: 1 throw site) were done
  by editing `RescheduleService.java`/`BookingBatchService.java`/`DrillUploadService.java` in place,
  confirming the specific new test fails, then `git checkout --` to restore byte-identical — verified via
  `git diff --stat` returning empty after each restore.

### Completion Notes List

- **AC1**: `SessionErrorCode.DRILL_UPLOAD_NOT_ALLOWED` removed; `DRILL_NOT_OWNED` (ownership, `:57`/`:109`)
  and `DRILL_VIDEO_ALREADY_LINKED` (`:78`) added. `grep -rn "DRILL_UPLOAD_NOT_ALLOWED" src/main src/test
  src/frontend/src` returns zero hits (the only surviving hits are in the gitignored
  `src/frontend/dist/` build output, which is stale and rebuilt by Task 8's `quasar build`).
  `DrillDetailPanel.vue` gained two branches; `session.drillLibrary.upload.notOwned` added to all three
  bundles. Three existing `DrillUploadResourceIT` cases gained `errorKey` assertions; one new IT proves the
  `DRILL_VIDEO_ALREADY_LINKED` path. Mutation-verified by swapping the two constants at `:57`/`:78` and
  confirming all four ITs fail, then restored byte-identical.
- **AC2**: `VideoErrorCode.UPLOAD_RATE_LIMITED` added; new `RateLimitExceededException` thrown only at
  `VideoService.java:231`; the now-unused `QuotaExceededException(String, String)` constructor removed.
  New `VideoApiAdvice.videoRateLimitExceededHandler` (429, unchanged), key `video.rateLimitExceeded` in all
  four backend bundles. `DrillDetailPanel.vue` gained a `rateLimited` branch; `quotaExceeded` reworded
  storage-only in all three bundles. Miss-check `grep -rn "new QuotaExceededException" src/main/java`
  returns exactly four hits (filestorage's unrelated one, plus `VideoService:135,249` and
  `QuotaService:76`) — `VideoService:231` no longer among them. `VideoMetricsTest` and `VideoServiceTest`
  verified still green (unaffected — the `QUOTA_EXCEEDED` metrics-tag path and rate-limit throw site
  itself have no prior test coverage to regress).
- **AC3**: `booking.errors.requestNotAllowed` reworded to one object-agnostic string in all three bundles,
  with a why-comment in `en-US`. Key not renamed, no siblings added. Grep-verified exactly five call sites,
  unchanged, no sixth.
- **AC4**: `BookingRequestPage.vue`'s `booking.batchSizeExceeded` branch now re-`await`s `getBatchConfig()`
  before toasting, re-syncing `maxBatchSize.value`; a new inner `try`/`catch` falls back to
  `booking.errors.batchSizeExceededUnknownMax` (all three bundles) if the re-fetch itself fails. Comment
  explains why the re-fetch is not optional.
- **AC5**: 8 uncovered `RescheduleService` throw sites closed (the story's corrected count, not the
  ledger's original 7): 7 new `RescheduleResourceIT` HTTP cases plus one Java-level `getErrorCode()`
  assertion extending the existing `:537` lock-interleaving test for the `:181-182` locked re-read (no
  HTTP response exists in that test to substring-match — per the AC, not converted to `httpTestClient`).
  Added a general-purpose `insertReschedule(status, proposedStart, proposedEnd)` helper generalizing the
  existing `insertPendingReschedule`, same `UUID.randomUUID()` id scheme, no new fixture-id-registry entry
  needed. All 8 new assertions mutation-verified individually (swap constant, confirm the specific new
  test fails, `git checkout --` to restore) — `RescheduleService.java` carries no diff.
- **AC6**: New `BookingBatchResourceIT` case `acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey`,
  adapted from the existing `acceptAll_withASiblingDeclinedBeforehand_endsPartiallyAccepted` fixture shape
  (declines every sibling instead of one), asserting 403 + `"errorKey":"booking.batchNoneAccepted"` over
  real HTTP. Mutation-verified against a reverted bare `return` at `BookingBatchService.java:292-293`,
  then restored byte-identical.
- **AC7**: All six ledger items annotated `[CLOSED by skillars-deferred-32 ACn]` in `deferred-work.md`,
  each closure note stating any contradiction found (AC1's site count, AC2's site count and toast text,
  AC4's non-working prescribed fix, AC5's 7-vs-8 count). New `## Deferred from: skillars-deferred-32
  implementation (2026-08-18)` section files the 429-status question and the `skillars-deferred-31`
  fixture-count documentation inaccuracy. The `sprint-status.yaml` `review`→merged tracking-lag flip was
  already applied before this dev-story run started (during story creation, per Mbah's instruction) and is
  not re-done here.
- **Task 8 verification**: `mvn -o verify` run in full — green: surefire 890 tests / 0 failures / 0 errors /
  1 skipped, failsafe 932 tests / 0 failures / 0 errors / 4 skipped (failsafe's +9 over the
  `skillars-deferred-31` baseline of 923 is exactly this story's new IT count: 1 AC1 + 7 AC5 + 1 AC6).
  `npx eslint` exit 0 on every changed frontend file. `npx quasar build` compiled successfully. Every new
  i18n key grep-verified present in all three frontend bundles and (for `video.rateLimitExceeded`) all four
  backend bundles.
- **Standing gap, unchanged by this story**: no frontend test infrastructure exists (`package.json`'s
  `test` script is a no-op), so AC1–AC4's frontend halves are verified by build + eslint + code reading,
  per Dev Notes; their toast-rendering behaviour is not manually spot-checked (no browser tooling
  available in this environment).

### File List

**Backend — source:**
- `src/main/java/com/softropic/skillars/platform/session/contract/SessionErrorCode.java`
- `src/main/java/com/softropic/skillars/platform/session/service/DrillUploadService.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/VideoErrorCode.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/exception/QuotaExceededException.java`
- `src/main/java/com/softropic/skillars/platform/video/contract/exception/RateLimitExceededException.java` (new)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoService.java`
- `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java`
- `src/main/resources/i18n/messages.properties`
- `src/main/resources/i18n/messages_en.properties`
- `src/main/resources/i18n/messages_de.properties`
- `src/main/resources/i18n/messages_fr.properties`

**Backend — tests:**
- `src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/RescheduleResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/video/api/VideoUploadResourceIT.java` (code-review patch)

**Frontend:**
- `src/frontend/src/components/session/DrillDetailPanel.vue`
- `src/frontend/src/pages/parent/BookingRequestPage.vue`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

**Docs / tracking:**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Change Log

- 2026-08-18: Implemented all seven ACs (AC1–AC6 code/test changes, AC7 ledger hygiene). Full
  `mvn -o verify` green: surefire 890/0/0/1(skipped), failsafe 932/0/0/4(skipped). `npx eslint` clean,
  `npx quasar build` compiles. Status set to `review`.

### Review Findings

- [x] [Review][Patch] `UPLOAD_RATE_LIMITED`/`RateLimitExceededException` has zero test coverage anywhere in the repo — `grep -rn "UPLOAD_RATE_LIMITED\|RateLimitExceededException" src/test` returns nothing except an unrelated `AuthResourceIT.login_rateLimitExceeded_returns429`. Decision (2026-08-18): add IT coverage now. Fixed by adding `VideoUploadResourceIT.initiateUpload_rateLimited_returns429WithRateLimitedKey`, mocking `videoService.initializeUpload(...)` to throw `RateLimitExceededException` (this class is a `@WebMvcTest` slice with `VideoService` mocked but the real `VideoApiAdvice` loaded, so it deterministically proves the wire mapping without needing to trip the real Bucket4j limiter) and asserting 429 + `$.errorMsg.errorKey == "UPLOAD_RATE_LIMITED"`. Mutation-verified: swapped the handler's error-code argument to `QUOTA_EXCEEDED`, confirmed the new test fails with the expected assertion mismatch, restored byte-identical (`git diff --stat` back to the pre-mutation +9/-0 AC2 diff). [`src/test/java/com/softropic/skillars/platform/video/api/VideoUploadResourceIT.java`, `src/main/java/com/softropic/skillars/platform/video/api/VideoApiAdvice.java`]
- [x] [Review][Patch] `BookingRequestPage.vue`'s new `getBatchConfig()` re-fetch inside the `booking.batchSizeExceeded` catch logs nothing on failure, unlike the sibling `getBatchConfig()` call at `onMounted` (`:614-617`) which does `console.warn('Could not load batch config, using default max size')` in its catch. Fixed: added a matching `console.warn('Could not re-fetch batch config, using previous max size')` to the catch. [`src/frontend/src/pages/parent/BookingRequestPage.vue`]
- [x] [Review][Patch] Double-click race on the batch-submit dialog: `bookingStore.batchSubmitting` resets to `false` in `submitBatch`'s `finally` (`booking.store.js:561`) before `BookingRequestPage.vue`'s catch handler runs its own `await getBatchConfig()` re-fetch, and the dialog's "Confirm requests" button has no `:disable` tied to that window (only `:disable="bookingStore.batchBasketSize === 0"`). A second click during the re-fetch launches a second overlapping `getBatchConfig()` call; an out-of-order response can leave `maxBatchSize.value` — which also gates `batchAtMax` and the basket cap for the rest of the session — set from the stale response. Fixed: added a local `refetchingBatchMaxSize` ref, set around the re-fetch, bound to the dialog button's `:loading`/`:disable`. [`src/frontend/src/pages/parent/BookingRequestPage.vue`, `src/frontend/src/stores/booking.store.js:542-561`]
- [x] [Review][Defer] `messages_de.properties` has only one `video.*` key at all (`video.rateLimitExceeded`, this diff's new line) — the other 7 `video.*` keys present in `messages_en.properties`/`messages_fr.properties` (`video.notFound`, `video.quotaExceeded`, `video.playbackDenied`, `video.providerError`, `video.sessionExpired`, `video.terminalStateViolation`, `video.validationFailed`) are entirely absent from the German bundle. Confirmed pre-existing: this diff's `messages_de.properties` change is a 3-line pure addition, not a reorganization, so it didn't remove or fail to add the missing siblings — they were already missing before this story. AC2 itself is satisfied (the one key it requires is present and correctly translated). [`src/main/resources/i18n/messages_de.properties:79-80`] — deferred, pre-existing
