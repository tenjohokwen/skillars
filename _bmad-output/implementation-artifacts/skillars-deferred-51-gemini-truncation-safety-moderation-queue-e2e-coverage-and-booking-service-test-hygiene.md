# Story Deferred-51: Gemini Moderation Truncation Safety, Moderation-Sweep Admin-Queue End-to-End Coverage & BookingServiceTest Injection Hygiene

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want `GeminiModerationService`'s content-truncation to be surrogate-pair-safe the same way
`AdminQueueService`'s own content-preview truncation already is, the moderation sweeper's alert to be
proven end to end through the real admin queue and approve endpoints rather than hand-inserted by every
test that needs one, and `BookingServiceTest` to stop hand-wiring `BookingService`'s positional
constructor every time it grows a parameter,
so that three concrete, decision-light gaps sitting in `deferred-work.md` since the `skillars-deferred-16`
and `skillars-uat-3` code reviews are closed before they age further into the ledger's backlog.

### Why this story exists

Two of the three items below were filed by the `skillars-deferred-16` code review
(`_bmad-output/implementation-artifacts/deferred-work.md`, section `## Deferred from: code review of
skillars-deferred-16-messaging-moderation-recovery-identity-safety (2026-08-05)`) and have sat unpicked
for 46 story-cycles. The third was filed twice, independently, by two different code-review passes on
`skillars-uat-3` (`## Deferred from: code review of skillars-uat-3-payment-capture-integrity-and-backup-retention
(2026-08-11)`, both the "review round" and "patch round" sections). All three were re-verified against the
live repo during this story's creation, not just trusted from the ledger text — all three still hold
exactly as described, and one sibling item from the same `skillars-deferred-16` section was found to have
already been silently fixed by intervening work (see below).

- **D5 under the `skillars-deferred-16` review section (this story's AC1) —
  `GeminiModerationService.moderate`'s content truncation can split a UTF-16 surrogate pair.**
  `GeminiModerationService.java:43-45` truncates on a raw UTF-16 char-count boundary
  (`content.substring(0, maxInputChars)`), which can cut a message in half of an astral character (e.g. an
  emoji), emitting a prompt that ends in an unpaired high surrogate — invalid UTF-16, and not safely
  encodable as UTF-8. This is not a hypothetical: `AdminQueueService.java:116-128` (`preview()`) already
  had, and already fixed, the **identical** bug class for its own content-preview truncation, and
  `AdminQueueIT.java`'s `queuePageSurvivesContentThatWouldSplitASurrogatePairAtTheTruncationPoint` test
  (added by that fix, code review 2026-08-05) documents in its own comment that a raw `substring(0,
  maxInputChars)`-style cut on emoji-bearing content is "reachable content, not a theoretical edge" — messages
  can carry up to 2000 code points (up to 4000 UTF-16 chars), and `GeminiModerationService.maxInputChars`
  defaults to exactly 4000. `GeminiModerationService`'s own truncation was never given the same fix.
- **D7 under the same `skillars-deferred-16` review section (this story's AC2) — no test walks the
  moderation-sweep-to-resolution chain end to end.** `AdminQueueIT`, `MessageApproveIT` and `MessageBlockIT`
  all hand-insert their `MODERATION_UNRESOLVED` `admin.admin_alerts` row directly via `jdbcTemplate`, and
  `MessageModerationSweeperIT` stops at asserting the alert row exists in the database — nothing drives a
  message from a real sweep, through the real `MessageHeldForReviewEvent` → alert-creation listener, into
  the real `GET /api/admin/queue?type=MODERATION_UNRESOLVED` response, and through a real approve call that
  resolves it. Each link is covered in isolation; the seam between them is unpinned.
- **The `BookingServiceTest` positional-constructor item (this story's AC3), filed twice** — once under
  `## Deferred from: code review of skillars-uat-3-payment-capture-integrity-and-backup-retention
  (2026-08-11)` (the review-round section, "`BookingServiceTest` still constructs `BookingService`
  positionally") and again, verbatim in substance, under the patch-round section of the same date as D17
  ("`BookingServiceTest` still constructs `BookingService` positionally, and this story grew that argument
  list by one more parameter (the 14th)"). `BookingServiceTest.java:100-107` still hand-lists all 15
  constructor arguments in `setUp()`; the sibling test `ExpiredPackBookingValidationTest` already uses
  `@InjectMocks` instead and has done so since before either of these ledger entries was filed. Every story
  that adds a constructor parameter to `BookingService` — three have since `skillars-uat-3`
  (`skillars-deferred-33` dropped two fields but did not change parameter count; the constructor is
  currently 15 parameters, matching both ledger entries' count at time of filing) — pays a compile break
  here first.

**One item from the same `skillars-deferred-16` review section was re-verified and found already fixed,
unannotated, by intervening work — not picked up here because there is nothing left to do:** D6
("`SoftDeleteIT.concurrentDoubleSoftDelete_exactlyOneSucceeds_oneConflicts` synchronises the *start* of two
HTTP round-trips... rather than the read-check-write critical section... not a durable guard") describes a
test that has since been rewritten. The current `SoftDeleteIT.java:245-289` drives two real concurrent HTTP
`DELETE` calls off one shared `CountDownLatch`, and asserts on the **independently observed** outcomes —
`successCount == 1`, `conflictCount == 1` — read back from the actual HTTP responses each thread received,
not from a tautological primary-key `COUNT(*)`. The method's own doc comment (`:240-244`) explicitly cites
`deferred-15`'s finding that a lock-shaped stand-in can pass against a plain `findById`, and records that
this version was mutation-verified against reverting `findByIdForUpdate` to `findById`. No production or
test change is needed; D6 is stale.

**Other items examined from the same two review sections and deliberately not picked up:**

- **D3 (`skillars-deferred-16` review) — AC4's orphaned-profile fail-safety produces three different
  outcomes for one conversation.** The ledger text itself says "resolving the inconsistency is a product
  call about what a parent should see for an unresolvable player" — a design decision this bundled
  small-fix story should not make ad hoc.
- **D4 (`skillars-deferred-16` review) — rolling-deploy ordering risk for the `MODERATION_UNRESOLVED`
  admin-alert enum value.** The ledger text itself notes this is "not reachable on the current
  single-instance Docker Compose deployment" — speculative against a deployment topology this project does
  not currently run, with no test surface to pin it against today.
- **The `jakarta.persistence.lock.timeout` hint's zero effect on Postgres** (`skillars-deferred-23` story
  creation section) — re-checked: still explicitly "not fixed here... a real fix needs a decision, not a
  patch" per that item's own text (a choice between `PESSIMISTIC_WRITE` + `NO_WAIT` with
  application-level retry, or an explicit `SET LOCAL lock_timeout` statement, both spanning four
  repositories). Design-decision-needed, unchanged since `skillars-deferred-49`/`-50` both declined it for
  the same reason.
- **The `RescheduleService`/`BookingDuplicationService` unlocked-read TOCTOU race and the
  `duplicateNextWeek` DST-shift quirk** (`skillars-deferred-49` review section) — already correctly scoped
  out by `skillars-deferred-50`'s own "Why this story exists," for the same reasons given there
  (locking-strategy decision; pre-existing behavior with no proposed fix). Not revisited here.
- **The standing "no frontend test infrastructure" gap**, recorded against `booking.store.js` most recently
  by `skillars-deferred-38`'s own review section ("Standing repo-wide gap... same accepted gap
  skillars-deferred-35/36/37 recorded") and by half a dozen other sections throughout the file — a
  deliberately, repeatedly accepted standing gap this project has never closed inside a bundled small-fix
  story, and this one does not either.

## Acceptance Criteria

1. **AC1 — `GeminiModerationService.moderate`'s content truncation never splits a UTF-16 surrogate pair,
   mirroring `AdminQueueService.preview()`'s already-shipped fix for the identical bug class.**
   - In `src/main/java/com/softropic/skillars/platform/messaging/service/GeminiModerationService.java`,
     replace the truncation block at `:43-45`:
     ```java
     String input = content.length() > maxInputChars
         ? content.substring(0, maxInputChars)
         : content;
     ```
     with:
     ```java
     int cutoff = maxInputChars;
     if (cutoff > 0 && content.length() > cutoff && Character.isHighSurrogate(content.charAt(cutoff - 1))) {
         // A char-count cutoff can land between a surrogate pair's two halves — the same class of
         // bug AdminQueueService.preview() already fixes for its own content-preview truncation
         // (deferred-work.md, "code review of skillars-deferred-16" D5). Back off by one so the
         // emitted prompt never ends in an unpaired high surrogate; the incomplete pair is dropped
         // entirely rather than kept half-corrupted.
         cutoff--;
     }
     String input = content.length() > maxInputChars
         ? content.substring(0, cutoff)
         : content;
     ```
     **`cutoff > 0` guard — story-review.md Finding 2 (Medium):** without it, a misconfigured
     `max-input-chars` of `0` (or negative) combined with non-blank `content` reaches
     `content.charAt(cutoff - 1)` → `charAt(-1)` → uncaught `StringIndexOutOfBoundsException`,
     propagating out of `moderate()` *before* the existing `try/catch` around the Gemini call (a
     regression versus today's code, where `substring(0, 0)` just returns `""`). No profile in this
     repo currently sets `max-input-chars` to `0` (`application.yaml:277,282` = 4000/2000,
     `application-test.yaml:104` = 100 — checked directly), so this isn't reachable today, but the fix
     is already touching this exact line and the guard is one clause.
     `maxInputChars` is a UTF-16-char-count config value (`platform.messaging.moderation.gemini.max-input-chars`,
     default 4000), not a code-point count, so this mirrors `AdminQueueService.preview()`'s
     *intent* (never split a pair) using a shape adapted to that difference — `preview()`'s own
     `codePointCount`/`offsetByCodePoints` approach is written for a code-point-based limit
     (`PREVIEW_CODE_POINTS`) and does not translate directly to a char-count-based one. Do not change
     `maxInputChars`'s semantics, its default value, or `MessagingService.java:160`'s separate
     2000-code-point guard — the two constants' current numeric coincidence (2000 code points = up to
     4000 UTF-16 chars = `maxInputChars`'s default) is a pre-existing, out-of-scope observation the
     ledger item also raises, not something this AC resolves.
   - **Unit test** in
     `src/test/java/com/softropic/skillars/platform/messaging/service/GeminiModerationServiceTest.java`:
     add `contentWithSurrogatePairAtTruncationBoundary_dropsWholePairRatherThanSplittingIt`, mirroring the
     existing `contentExceedsMaxInputChars_truncatedBeforeSending` test's shape (stub `geminiClient.evaluate`
     to return `SAFE`, `maxInputChars` is already set to `100` by this class's `setUp()`).
     **Assert via exact `verify(geminiClient).evaluate(expectedPrompt)`, not a last-character/`contains`
     check — story-review.md Finding 1 (High).** The story's original draft asserted
     `!Character.isHighSurrogate(sentPrompt.charAt(sentPrompt.length() - 1))` and
     `!sentPrompt.contains("😀")`; both pass identically whether or not the fix is applied, because the
     prompt's last character is always the fixed literal suffix's `-`, and the *unpatched* code
     (`substring(0, 100)`) already drops the emoji's low surrogate as collateral — so `"😀"` (the full
     pair) is absent either way, and neither assertion pins the dangling high surrogate the bug
     actually leaves behind. Use this shape instead, built the same way
     `contentExceedsMaxInputChars_truncatedBeforeSending` already does:
     ```java
     @Test
     void contentWithSurrogatePairAtTruncationBoundary_dropsWholePairRatherThanSplittingIt() {
         when(geminiClient.evaluate(any())).thenReturn(ModerationVerdict.SAFE);
         String content = "x".repeat(99) + "😀" + "x".repeat(10); // 99 + 2 + 10 = 111 chars

         service.moderate(1L, content);

         String expectedTruncated = "x".repeat(99); // whole pair dropped, not split
         String expectedPrompt = "Test prompt:\n"
             + "\n\n---BEGIN USER CONTENT---\n"
             + expectedTruncated
             + "\n---END USER CONTENT---";
         verify(geminiClient).evaluate(expectedPrompt);
     }
     ```
     `content.charAt(99)` (index `maxInputChars - 1`) is the emoji's high surrogate, exactly the
     boundary a raw `substring(0, 100)` would split — on unpatched code this test's expected value
     would need to be `"x".repeat(99) + "\uD83D"` instead, so this pins the exact surviving content
     and fails on the unpatched code, unlike the last-character/`contains` checks. Do not modify
     `contentExceedsMaxInputChars_truncatedBeforeSending` or any other existing test — none of them touch a
     surrogate pair, so their expected output is unchanged by this fix.

2. **AC2 — a new integration test drives a real message from `MessageModerationSweeper.sweep()` through
   the real `GET /api/admin/queue?type=MODERATION_UNRESOLVED` response and a real approve call, proving the
   sweep → alert → queue-listing → resolution chain end to end.**
   - Add this new test to `src/test/java/com/softropic/skillars/platform/admin/api/AdminQueueIT.java` (this
     file already extends `AbstractIntegrationTest`, already has an admin login helper and the
     `coachProfileId`/`CONVERSATION_ID`/`COACH_USER_ID` fixture from `setUp()`, and already asserts against
     the same `GET /api/admin/queue` response shape this AC needs — reuse all of it rather than standing up
     a new fixture or a new IT class). `MessageModerationSweeperIT` (a different package,
     `platform.messaging.service`, using plain `@SpringBootTest` rather than `AbstractIntegrationTest`) is
     not the right home for this test: it has no `httpTestClient`, no admin login helper, and per
     `AbstractIntegrationTest`'s own Javadoc, adding one class-level annotation there that doesn't already
     exist would fork a new Spring context — do not do that.
   - Add the field `@Autowired private MessageModerationSweeper sweeper;` (import
     `com.softropic.skillars.platform.messaging.service.MessageModerationSweeper`) alongside this file's
     existing `@Autowired` fields.
   - New test:
     ```java
     // Deferred-16 code review D7: no test previously drove this chain past a hand-inserted alert row.
     @Test
     void sweepThenApprove_endToEndChain_alertAppearsInQueueThenResolves() {
         long sweptMessageId = 9000_001_004L; // next id in this file's own 9000_001_xxx local sequence
         transactionTemplate.execute(status -> {
             jdbcTemplate.update(
                 "INSERT INTO messaging.messages (id, conversation_id, sender_id, sender_role, content, " +
                 "moderation_status, created_at) VALUES (?, ?, ?, 'COACH', 'Stranded content', 'PENDING', ?)",
                 sweptMessageId, CONVERSATION_ID, COACH_USER_ID,
                 Timestamp.from(Instant.now().minusSeconds(3600)));
             return null;
         });

         releaseSchedulerLock("MessageModerationSweeper_sweep");
         sweeper.sweep();

         assertThat(jdbcTemplate.queryForObject(
             "SELECT moderation_status FROM messaging.messages WHERE id = ?", String.class, sweptMessageId))
             .isEqualTo("UNDER_REVIEW");

         String cookies = loginAndGetCookies(ADMIN_EMAIL);
         ResponseEntity<Map> queueResp = httpTestClient.makeHttpRequest(
             baseUrl() + QUEUE_URL + "?type=MODERATION_UNRESOLVED",
             HttpMethod.GET, null, authenticatedHeaders(cookies), Map.class);
         assertThat(queueResp.getStatusCode()).isEqualTo(HttpStatus.OK);
         @SuppressWarnings("unchecked")
         List<Map<String, Object>> content = (List<Map<String, Object>>) queueResp.getBody().get("content");
         Map<String, Object> entry = content.stream()
             .filter(e -> String.valueOf(sweptMessageId).equals(e.get("referenceId")))
             .findFirst()
             .orElseThrow(() -> new AssertionError("Swept message's alert not found in the real queue response"));
         assertThat(entry.get("summary")).isEqualTo("MODERATION_ORPHAN_SWEPT: Stranded content");

         ResponseEntity<Void> approveResp = httpTestClient.makeHttpRequest(
             baseUrl() + "/api/admin/messages/" + sweptMessageId + "/approve",
             HttpMethod.POST, null, authenticatedHeaders(cookies), Void.class);
         assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);

         assertThat(jdbcTemplate.queryForObject(
             "SELECT moderation_status FROM messaging.messages WHERE id = ?", String.class, sweptMessageId))
             .isEqualTo("APPROVED");
         assertThat(jdbcTemplate.queryForObject(
             "SELECT status FROM admin.admin_alerts WHERE reference_id = ? AND type = 'MODERATION_UNRESOLVED'",
             String.class, String.valueOf(sweptMessageId)))
             .isEqualTo("RESOLVED");
     }
     ```
   - **Grace period:** `MessageModerationSweeper.sweep()` reads
     `platform.messaging.moderation_orphan_grace_minutes` (default 30 minutes) and sweeps anything `PENDING`
     older than that. Seeding `created_at` one hour in the past mirrors
     `MessageModerationSweeperIT`'s own seeding convention and clears the default grace comfortably.
   - **`releaseSchedulerLock` is inherited from `AbstractIntegrationTest`** (do not redeclare it) — required
     because invoking a `@SchedulerLock`-annotated method from a test goes through the Spring proxy, and
     `MessageModerationSweeper.sweep()` carries `lockAtLeastFor = "PT2M"`.
   - **`"MODERATION_ORPHAN_SWEPT"` is hardcoded as a literal, not referenced via
     `MessageModerationSweeper.HELD_REASON_SWEPT`** — that constant is package-private in
     `platform.messaging.service`, not visible from `platform.admin.api`. This file already hardcodes the
     identical literal in `moderationUnresolvedSummary_leadsWithTheReason` (`:212`), so this is consistent
     with the file's own existing convention, not a new one.
   - **No teardown needed.** `AdminQueueIT` has no `@AfterEach` today — cleanup relies entirely on
     `AbstractIntegrationTest`'s `DatabaseResetTestExecutionListener` truncating between tests. Do not add
     manual `DELETE` statements for this test's fixture row.

3. **AC3 — `BookingServiceTest` stops hand-listing `BookingService`'s positional constructor arguments,
   switching to `@InjectMocks`, matching the pattern its sibling `ExpiredPackBookingValidationTest` already
   uses — with one deliberate difference to preserve this file's own test behavior.**
   - In `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`, replace the
     two plain field declarations at `:89-90`:
     ```java
     private BookingStateMachine bookingStateMachine;
     private BookingService bookingService;
     ```
     with:
     ```java
     @Spy
     private BookingStateMachine bookingStateMachine = new BookingStateMachine();

     @InjectMocks
     private BookingService bookingService;
     ```
     and add `import org.mockito.InjectMocks;` and `import org.mockito.Spy;` alongside this file's existing
     `import org.mockito.Mock;`.
   - In `setUp()` (`:97-112`), delete the `bookingStateMachine = new BookingStateMachine();` line and the
     entire `bookingService = new BookingService(...)` block (`:99-107`), leaving only the
     `lenient().when(sessionDurationResolver.resolve(COACH_ID)).thenReturn(Duration.ofHours(1));` line (and
     its preceding comment) inside `setUp()`.
   - **Use `@Spy` for `bookingStateMachine`, not `@Mock`, even though `ExpiredPackBookingValidationTest`
     mocks it.** `ExpiredPackBookingValidationTest` only exercises `createBookingRequest`, which never calls
     `bookingStateMachine.transition(...)`, so a bare mock is sufficient there. `BookingServiceTest` is
     confirmed (`grep -c "bookingStateMachine\." BookingServiceTest.java` returns `0` — no direct references
     outside the deleted constructor call) to exercise `BookingService`'s accept/decline/reschedule paths,
     all of which call `bookingStateMachine.transition(...)` for real state-machine validation; a `@Mock`
     here would silently return `null`/default answers for every transition call unless every one of those
     tests were individually re-stubbed, which is a behavior change this AC does not make. `@Spy` on an
     already-constructed real `BookingStateMachine` preserves the exact current behavior — `@InjectMocks`'s
     constructor-injection matching treats a `@Spy` field the same as a `@Mock` field for type-matching
     purposes, so this is a drop-in replacement, not a partial one.
   - **Verify no other test in the file constructs a second `BookingService` or `BookingStateMachine`
     instance** (`grep -n "new BookingService\|new BookingStateMachine"` should return only the two lines
     being deleted) — confirmed already true as of this story's creation; re-check is a cheap guard against
     drift before landing the change.
   - **Do not touch any other file.** `ExpiredPackBookingValidationTest.java` already uses this pattern and
     needs no change; no other test file in the repo constructs `BookingService` via `new BookingService(...)`
     (`grep -rl "new BookingService("`  src/test/java` returns only `BookingServiceTest.java` today).

4. **AC4 — Ledger hygiene.** In `deferred-work.md`:
   - Tag the D5 bullet under `## Deferred from: code review of
     skillars-deferred-16-messaging-moderation-recovery-identity-safety (2026-08-05)` (the code-point/max-input-chars
     item) → `` `[PICKED UP by skillars-deferred-51 AC1]` ``.
   - Tag the D7 bullet in the same section (the "no test walks AC3's chain end to end" item) →
     `` `[PICKED UP by skillars-deferred-51 AC2]` ``.
   - Tag the `BookingServiceTest` positional-constructor bullet under **both** of its two locations —
     `## Deferred from: code review of skillars-uat-3-payment-capture-integrity-and-backup-retention
     (2026-08-11)` (review-round section) and the second `## Deferred from: code review of
     skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)` section further down (the
     patch-round section, item D17) → `` `[PICKED UP by skillars-deferred-51 AC3]` `` on both.
   - Leave D3, D4 and D6 in the `skillars-deferred-16` review section untouched (D6's staleness is recorded
     in this story's "Why this story exists" above, not marked `[PICKED UP]` or `[CLOSED]` in the ledger
     itself — this story does not implement anything for D6, so tagging it as picked-up would misrepresent
     what was done; a future audit pass, not this story, should annotate it `[AUDIT ...: already fixed]`).

## Tasks / Subtasks

- [ ] Task 1: `GeminiModerationService` surrogate-safe truncation (AC: #1)
  - [ ] 1.1 Replace the truncation block at `GeminiModerationService.java:43-45` per AC1.
  - [ ] 1.2 Add `contentWithSurrogatePairAtTruncationBoundary_dropsWholePairRatherThanSplittingIt` to
    `GeminiModerationServiceTest.java`.
  - [ ] 1.3 Run targeted verification and confirm green, including all pre-existing tests in the same file.
- [ ] Task 2: Messaging moderation-sweep-to-queue-to-approve end-to-end IT (AC: #2)
  - [ ] 2.1 Add the `MessageModerationSweeper` autowiring and the new test to `AdminQueueIT.java`.
  - [ ] 2.2 Run the full `AdminQueueIT` suite (not just the new test) and confirm every pre-existing test
    still passes — the new fixture row must be additive-only.
- [ ] Task 3: `BookingServiceTest` constructor-injection hygiene (AC: #3)
  - [ ] 3.1 Switch `bookingStateMachine`/`bookingService` to `@Spy`/`@InjectMocks` per AC3, deleting the
    positional constructor call in `setUp()`.
  - [ ] 3.2 Run the full `BookingServiceTest` suite and confirm every existing test still passes unchanged —
    this is a pure test-harness refactor, no test behavior should change.
- [ ] Task 4: Ledger hygiene (AC: #4) — verify the three `[PICKED UP]` tags (four bullet locations)
  specified above. **Already done — story-review.md Finding 3 (Informational).** All four tags were
  already applied to `deferred-work.md` by this story's own creation commit (`fdadd6e`); no `Edit` is
  needed, only a re-check that the tags are present (matches this project's established pattern of
  applying ledger tags at story-creation time, e.g. `skillars-deferred-41`).

## Dev Notes

- **This story bundles three independent, decision-light findings from two different prior code
  reviews — it is not a single coherent feature.** AC1 and AC2 both touch the messaging moderation module
  but are otherwise unrelated (a production truncation-safety fix vs. a new integration test); AC3 is an
  unrelated booking-module test-harness refactor. There is no cross-AC dependency; task order above is a
  convenience, not a requirement.
- **AC1 reuses an established, already-shipped pattern rather than inventing a new one.**
  `AdminQueueService.preview()` (`AdminQueueService.java:116-128`) is the precedent for "don't split a
  surrogate pair," adapted here for a char-count-based limit instead of a code-point-based one — read that
  method's own doc comment before implementing AC1, it explains the exact failure mode (`substring` on a
  raw UTF-16 index vs. a code-point boundary) this AC fixes in a sibling class.
- **AC2 deliberately extends `AdminQueueIT`, not `MessageModerationSweeperIT` or a new file.** This
  codebase's `AbstractIntegrationTest` Javadoc is explicit that adding a class-level annotation that isn't
  already shared forks a new Spring Boot context (each context costs a fresh Spring Boot startup plus a
  pair of Testcontainers) — read that Javadoc before creating any new IT class. `AdminQueueIT` already has
  every fixture and helper this AC needs.
- **`IT`-execution gotcha (recorded by `skillars-deferred-47`'s dev pass, still applies):** this project's
  `*IT` classes run under `maven-failsafe-plugin`, bound to `integration-test`/`verify`, **not** `mvn test`.
  Use `mvn -o integration-test -Dit.test=AdminQueueIT` and confirm a `target/failsafe-reports/...txt` report
  was actually written.
- Per `docs/validation-strategy.md`, run targeted verification only: `mvn test`/`mvn integration-test`
  scoped to the touched backend classes/ITs — do not run a full `mvn verify` unless targeted verification
  proves insufficient.
- **No frontend changes in this story.** All three ACs are backend-only (production code + tests).

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/messaging/service/GeminiModerationService.java` — one
  truncation-logic change in `moderate`, no new field/import beyond what's already in scope (AC1).
- `src/test/java/com/softropic/skillars/platform/messaging/service/GeminiModerationServiceTest.java` — one
  new test (AC1).
- `src/test/java/com/softropic/skillars/platform/admin/api/AdminQueueIT.java` — one new `@Autowired` field,
  one new test method, additive only (AC2).
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java` — two field
  declarations changed, `setUp()` shortened, no new test added or removed (AC3).
- `_bmad-output/implementation-artifacts/deferred-work.md` — four `[PICKED UP]` tags across three bullet
  locations (AC4).
- No changes to `AdminQueueService.java`, `MessageModerationSweeper.java`, `BookingService.java`,
  `ExpiredPackBookingValidationTest.java`, any frontend file, or any i18n bundle.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, section `## Deferred from: code review
  of skillars-deferred-16-messaging-moderation-recovery-identity-safety (2026-08-05)` — this story's AC1/AC2
  source items (D5, D7), and D6's staleness finding]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, both `## Deferred from: code review of
  skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)` sections — AC3's source item,
  filed twice]
- [Source: `src/main/java/com/softropic/skillars/platform/messaging/service/GeminiModerationService.java:26-50`
  — `moderate`, AC1's target]
- [Source: `src/main/java/com/softropic/skillars/platform/admin/service/AdminQueueService.java:116-128` —
  `preview()`, AC1's mirrored (adapted) pattern]
- [Source: `src/test/java/com/softropic/skillars/platform/admin/api/AdminQueueIT.java:189-256` — the
  existing `MODERATION_UNRESOLVED` tests this file's fixture already supports, AC2's target]
- [Source: `src/main/java/com/softropic/skillars/platform/messaging/service/MessageModerationSweeper.java:41-141`
  — `sweep()`/`sweepOne()`, AC2's driven-through production code]
- [Source: `src/test/java/com/softropic/skillars/platform/messaging/service/MessageModerationSweeperIT.java`
  — the sweep-only IT this story extends the coverage past, without modifying]
- [Source: `src/test/java/com/softropic/skillars/platform/admin/api/MessageApproveIT.java:208-246` — the
  existing `approveMessage_setsApprovedDeliveredAt_resolvesReportsAndAlert` test, AC2's approve-side
  precedent]
- [Source: `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java:70-112` —
  the fields and `setUp()` AC3 targets]
- [Source: `src/test/java/com/softropic/skillars/platform/payment/service/ExpiredPackBookingValidationTest.java:52-77`
  — the sibling test's already-shipped `@InjectMocks` pattern AC3 mirrors]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy]

## Change Log

| Date | Change |
|---|---|
| 2026-08-21 | Story created via story-creation process, bundling two items filed by `skillars-deferred-16`'s code review (unpicked for 46 story-cycles) with one item filed twice by `skillars-uat-3`'s code review — per explicit instruction not to create another small story. All three re-verified against live code at creation time rather than trusted from ledger text: `GeminiModerationService.java:43-45` still truncates on a raw char index with no surrogate-pair guard (read directly, confirmed against the already-fixed sibling `AdminQueueService.preview()`); `AdminQueueIT`/`MessageApproveIT`/`MessageBlockIT` still hand-insert their `MODERATION_UNRESOLVED` alert row via `jdbcTemplate` rather than driving it through a real sweep (grepped for `INSERT INTO admin.admin_alerts` across all four messaging/admin IT files — no sweep-driven path found); `BookingServiceTest.java:100-107` still hand-lists all 15 constructor arguments, confirmed against the sibling `ExpiredPackBookingValidationTest.java`'s already-shipped `@InjectMocks` pattern. One sibling item from the same `skillars-deferred-16` review section (D6, `SoftDeleteIT`'s concurrency-test weakness) was found already fixed by intervening work and is documented as stale in "Why this story exists" rather than picked up — the current `SoftDeleteIT.java:245-289` already drives real concurrent HTTP calls and asserts on independently-observed outcomes, not a tautological primary-key count. Two other items from the same `skillars-deferred-16` section (D3, D4) and the `jakarta.persistence.lock.timeout`/booking-locking-strategy items already excluded by `skillars-deferred-49`/`-50` were deliberately not picked up — all need a design/product decision this bundled small-fix story should not make ad hoc, per the reasoning recorded in "Why this story exists" above. |
| 2026-08-21 | `story-review.md` applied: 3 findings, all addressed before dev starts. Finding 1/High: AC1's prescribed unit test asserted on the prompt's last character and a `contains("😀")` check, both of which pass identically on unpatched and patched code (the unpatched `substring(0, 100)` already drops the emoji's low surrogate as collateral, so the full pair is absent either way, and the prompt's last character is always the fixed literal suffix) — replaced with an exact `verify(geminiClient).evaluate(expectedPrompt)` pin, mirroring the file's own existing `contentExceedsMaxInputChars_truncatedBeforeSending` pattern, which does fail on the unpatched code. Finding 2/Medium: AC1's code sample threw `StringIndexOutOfBoundsException` for a `maxInputChars <= 0` misconfiguration (`charAt(-1)`), uncaught by the existing try/catch — not reachable with any current profile's config, but fixed with a one-clause `cutoff > 0 &&` guard since the change was already touching the line. Finding 3/Informational: AC4/Task 4's four ledger tags were already applied by this story's own creation commit; Task 4 reworded to "verify" rather than "apply," matching this project's established pattern of tagging at story-creation time. No other issues found — story-review.md's "Everything else checked" section independently confirmed AC1's core algorithm, AC2's full sweep→queue→approve chain and fixture correctness, and AC3's constructor/spy mechanics all accurate against the live repo. |
