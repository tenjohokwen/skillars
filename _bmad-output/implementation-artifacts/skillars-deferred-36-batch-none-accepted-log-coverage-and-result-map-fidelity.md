# Story Deferred-36: Batch-None-Accepted Log-Context HTTP Coverage & Result-Map Accepted-Status Fidelity

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want `skillars-deferred-35` AC1's `getLogContext()` diagnostic payload proven end-to-end over a real HTTP
request rather than only at the `BookingBatchServiceTest` unit level, and the coach requests page's memoized
per-batch result lookup to retain every booking's outcome (accepted and failed) instead of silently discarding
which bookings were accepted,
so that a future regression in `ApiAdvice`'s structured-logging pipeline is caught by a test rather than
silently shipping, and `failedResultByBatch` stops narrowing itself to a shape only its one current caller can
use.

### Why this story exists

Drawn from `_bmad-output/implementation-artifacts/deferred-work.md`'s
`## Deferred from: code review of skillars-deferred-35-batch-failure-diagnostics-lookup-perf-and-refund-docs
(2026-08-19)` section (lines 1557-1560) — the two `[Review][Defer]` findings `skillars-deferred-35`'s own code
review filed rather than fixed, both explicitly out of that story's bar. `skillars-deferred-34`'s creation read
the ledger's ~1550 lines end to end (plus an independent fork covering the previously-unaudited
`skillars-1`–`skillars-10`/`deploy-*` sections) and found nothing else small and decision-free at that time.
`skillars-deferred-35`'s creation re-read the file end to end again and found exactly three qualifying items,
all closed by that story. This story's creation re-read the file end to end a third time (all 1561 lines) and
confirms via `git diff 9b7f6b5 36abf0c -- _bmad-output/implementation-artifacts/deferred-work.md` (the
`skillars-deferred-34` → `skillars-deferred-35` merge commits) that the only change to this file since
`skillars-deferred-34`'s exhaustive pass is the three `[CLOSED by skillars-deferred-35 ACn]` annotations plus
the new two-item section at the end — every other line in the file is byte-identical to what two independent
prior exhaustive passes already vetted as either shipped, a product/design decision, or an accepted
low-priority tradeoff. Both new items qualify:

1. **`BookingBatchService.acceptAll`'s total-failure exception's `getLogContext()` payload
   (`skillars-deferred-35` AC1) is verified only at the `BookingBatchServiceTest` unit level.** Nothing
   exercises `ApiAdvice.logError`'s unconditional read of it via a real HTTP request/integration test, so a
   future regression in `ApiAdvice` that stops reading `getLogContext()` would not be caught by anything in
   that diff.

   **Re-verified against current code during this story's creation.** `BookingBatchServiceTest.java:691,740`
   still only assert `getLogContext()` at the Mockito unit level (`:721-724,756-758`); `grep -n
   "getLogContext" src/test` returns zero hits outside `BookingBatchServiceTest.java`. The one existing
   HTTP-level test of this path,
   `BookingBatchResourceIT.acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey`
   (`BookingBatchResourceIT.java:501-521`), asserts the 403 status and the wire `errorKey` only — it never
   inspects server-side log output, so it cannot and does not prove `ApiAdvice.logError` actually read
   `getLogContext()` for this exception. **One correction to the ledger item's own file citation, found during
   this re-verification: it cites `src/main/java/com/softropic/skillars/infrastructure/exception/ApiAdvice.java`
   — that package/path is wrong.** `ApiAdvice` lives at
   `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` (confirmed via
   `find src/main -name ApiAdvice.java`); `infrastructure.exception` holds `ApplicationException` (the
   `getLogContext()` field/accessor's own home), not `ApiAdvice`. File paths age fast, per this ledger's own
   `## How to read this file` warning — this story's References section below uses the correct path
   throughout.

2. **`failedResultByBatch` (the `skillars-deferred-35` AC2 replacement for the old linear scan) only stores
   failed entries, permanently discarding which `bookingId`s were accepted, and rebuilds a fresh `Map` for
   every batch in `bookingStore.batchAcceptResultsByBatch` whenever *any* batch's results change.**

   **Re-verified against current code during this story's creation** (`CoachBookingRequestsPage.vue:208-233`):
   unchanged since filed. `failedResultByBatch` (`:214-225`) iterates every batch in
   `bookingStore.batchAcceptResultsByBatch` and, within each batch, keeps only entries where `!r.accepted`
   (`:220`); `failureReasonFor` (`:227-233`) does `failedResultByBatch.value[batchId]?.get(bookingId)` and
   returns `null` if nothing is found — a missing (never-attempted) `bookingId` and an accepted `bookingId` are
   indistinguishable to any reader of this computed. This story closes only the first half — the
   accepted-status data loss, which has no design decision behind it (the pre-refactor `Array.find()`
   implementation found *any* result, accepted or not, and only then checked `.accepted`; the O(1) refactor
   narrowed that for no documented reason). The second half — the aggregate rebuild-on-any-batch-change cost
   and `bookingStore.batchAcceptResultsByBatch` never being pruned within a page session — is **not** closed
   here; see "Explicitly NOT in this story" below.

Both items are unrelated in subsystem (a backend/test-infrastructure IT-coverage gap vs. a frontend memoized
lookup's data fidelity) and unrelated to each other's fix — exactly the shape prior bundling stories
(`skillars-deferred-31`/`32`/`33`) grouped together. No other newly-open or previously-missed item of
comparable size surfaced during this re-read; the ledger remains mined thin for anything beyond these two.

## Deferred Item(s) Closed

| Source | Item | Current location (re-verified 2026-08-19) | AC | Planned outcome |
|---|---|---|---|---|
| `skillars-deferred-35` code review (2026-08-19), Finding "[Review][Defer]" #2 | `getLogContext()` payload verified only at the unit level, no IT proves `ApiAdvice.logError` reads it over real HTTP | `BookingBatchService.java:298-300`, `platform/security/api/ApiAdvice.java:636-651` | 1 | `BookingBatchResourceIT`'s existing total-failure test extended with a Logback `ListAppender` proving the structured log context is actually emitted for a real request |
| `skillars-deferred-35` code review (2026-08-19), Finding "[Review][Defer]" #1 (accepted-status half only) | `failedResultByBatch` discards which `bookingId`s were accepted | `CoachBookingRequestsPage.vue:214-233` | 2 | Renamed `resultByBatch` stores every result (accepted and failed); `failureReasonFor` restores the pre-refactor `!result \|\| result.accepted` guard, behavior-preserving |

**Explicitly NOT in this story** (considered during story creation and rejected):

- **The aggregate rebuild-on-any-batch-change cost and the fact `bookingStore.batchAcceptResultsByBatch` is
  never pruned within a page session** (the second half of Finding #1). The ledger item's own framing —
  "worth revisiting if either grows" — and `skillars-deferred-35`'s own closure note both already characterize
  this as a low-priority, not-yet-actionable tradeoff, not a defect. A real fix needs a design decision (a
  memoization keyed per-batch-array-reference rather than one computed rebuilding every batch, and/or a
  pruning policy for stale batch results) that this bundled small-fix story does not make. Re-filed to
  `deferred-work.md` as its own item once this story closes the accepted-status half — see AC2's Dev Notes.
- **Putting `results`/`getLogContext()` on the HTTP response body.** Unchanged from `skillars-deferred-35`'s
  own scoping: `ErrorDto` has no field for arbitrary per-item detail, and adding one is a wire-contract change
  touching every other error response in the app. AC1 below proves the *existing* server-side log pipeline
  works over real HTTP — it does not add anything new to what is logged or returned to the client.
- **Any change to `BookingBatchService.acceptAll`'s production code.** Both `results` and the
  `Map.of("batch id", batchId, "per-booking results", results)` call at `:298-300` are already correct and
  shipped by `skillars-deferred-35` AC1 — this story adds test coverage only, no production diff in
  `BookingBatchService.java`.
- **Rewriting `failureReasonFor`'s `errorKey` branching, its i18n key choices, or `batchIsActionable`.** AC2 is
  a pure data-fidelity restoration to the memoized lookup's storage; for every input `failureReasonFor` can
  receive, its return value is unchanged from what `skillars-deferred-35` shipped.

## Acceptance Criteria

1. **A `BookingBatchResourceIT` test proves `ApiAdvice.logError` actually reads
   `OperationNotAllowedException.getLogContext()` for the `booking.batchNoneAccepted` total-failure path, over
   a real HTTP request — not only at the `BookingBatchServiceTest` unit level.**

   Verified current state (`BookingBatchResourceIT.java:492-521`):
   ```java
   @Test
   void acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey() {
       UUID batchId = createBatchInDb(2);
       transactionTemplate.execute(status -> {
           jdbcTemplate.update("UPDATE booking.bookings SET status = 'DECLINED' WHERE batch_id = ?", batchId);
           return null;
       });

       String coachCookies = loginAndGetCookies(COACH_EMAIL);
       assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
           baseUrl() + "/api/bookings/batches/" + batchId + "/accept-all",
           HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
       ))
           .isInstanceOf(HttpClientErrorException.class)
           .satisfies(e -> {
               HttpClientErrorException ex = (HttpClientErrorException) e;
               assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
               assertThat(ex.getResponseBodyAsString())
                   .contains("\"errorKey\":\"booking.batchNoneAccepted\"")
                   .doesNotContain("MISSING_RIGHTS");
           });

       assertThat(jdbcTemplate.queryForObject(
           "SELECT status FROM booking.booking_batches WHERE id = ?", String.class, batchId))
           .isEqualTo("PENDING");
   }
   ```
   This test drives `acceptedIds.isEmpty()` path (b) (`BookingBatchService.java:286-300`) over real HTTP and
   already asserts the wire-visible half (403 + `errorKey`). It asserts nothing about server-side logging.
   `ApiAdvice.logError` (`platform/security/api/ApiAdvice.java:636-651`) is:
   ```java
   private String logError(Throwable throwable, String msgTemplate)  {
       String errorCode;
       Map<String, Object> ctx = new HashMap<>();
       if(throwable instanceof ApplicationException applicationException) {
           errorCode = applicationException.getSupportId();
           //Get and log the context as well
           ctx = applicationException.getLogContext();
       } else {
           errorCode = SQIDS.encode(List.of(Integer.toUnsignedLong(UUID.randomUUID().hashCode())));
       }

       final String templateWithSupportId = msgTemplate + " SUPPORT_ID: %s";
       final String fullMsg = String.format(templateWithSupportId, errorCode);
       log.error(fullMsg, entries(ctx), throwable);
       return errorCode;
   }
   ```
   reached from `operationDeniedHandler` (`:267-277`) via `handleSecErrorAndReturnDTO(AuthorizationException, ...)`
   (`:601-605`, since `OperationNotAllowedException extends AuthorizationException`) →
   `logErrorAndReturnDTO` (`:614-617`) → `logError`. `entries(ctx)` is
   `net.logstash.logback.argument.StructuredArguments.entries(Map)`, which returns a
   `MapEntriesAppendingMarker` (`net.logstash.logback.marker` package, `logstash-logback-encoder:8.1`, already
   a project dependency per `pom.xml`); its `toString()` is inherited unmodified from `LogstashMarker` and — for
   a marker with no chained `.and()`/`.with()` references, which this one has none of — resolves to exactly
   `toStringSelf()`, which is `String.valueOf(map)`: ordinary Java `Map.toString()` output, e.g.
   `{batch id=<uuid>, per-booking results=[...]}`. `AbstractIntegrationTest` runs `@SpringBootTest(webEnvironment
   = RANDOM_PORT)` (`AbstractIntegrationTest.java:60`) — a real embedded server in the same JVM as the test —
   so a Logback appender attached directly to `LoggerFactory.getLogger(ApiAdvice.class)` before the HTTP call
   observes the exact `ILoggingEvent` `ApiAdvice.logError` produces while handling that real request.
   `src/test/resources/logback-test.xml`'s `<root level="WARN">` (`:27`) does not suppress this — `ERROR` is
   more severe than `WARN` and there is no `ApiAdvice`-specific level override, so the logger's effective level
   already permits the call.

   **Required:** extend this test in place (no new test method) to attach a `ListAppender<ILoggingEvent>` to
   `ApiAdvice`'s logger before the HTTP call, and assert on it after the existing HTTP-response assertions:
   ```java
   @Test
   void acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey() {
       UUID batchId = createBatchInDb(2);
       transactionTemplate.execute(status -> {
           jdbcTemplate.update("UPDATE booking.bookings SET status = 'DECLINED' WHERE batch_id = ?", batchId);
           return null;
       });

       // skillars-deferred-36 AC1: prove ApiAdvice.logError actually reads
       // OperationNotAllowedException.getLogContext() over a real HTTP request — skillars-deferred-35 AC1
       // only mutation-verified the exception's own getLogContext() at the BookingBatchServiceTest unit
       // level; nothing before this test exercised ApiAdvice's read of it (skillars-deferred-35 code
       // review, [Review][Defer] #2, closed here).
       Logger apiAdviceLogger = (Logger) LoggerFactory.getLogger(ApiAdvice.class);
       ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
       logCapture.start();

       String coachCookies = loginAndGetCookies(COACH_EMAIL);
       try {
           apiAdviceLogger.addAppender(logCapture);

           assertThatThrownBy(() -> httpTestClient.makeHttpRequest(
               baseUrl() + "/api/bookings/batches/" + batchId + "/accept-all",
               HttpMethod.POST, null, authenticatedHeaders(coachCookies), Void.class
           ))
               .isInstanceOf(HttpClientErrorException.class)
               .satisfies(e -> {
                   HttpClientErrorException ex = (HttpClientErrorException) e;
                   assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                   assertThat(ex.getResponseBodyAsString())
                       .contains("\"errorKey\":\"booking.batchNoneAccepted\"")
                       .doesNotContain("MISSING_RIGHTS");
               });

           assertThat(logCapture.list)
               .as("ApiAdvice.logError must have logged the batch id / per-booking results structured context")
               .anySatisfy(event -> assertThat(event.getArgumentArray()[0].toString())
                   .contains("batch id=" + batchId)
                   .contains("per-booking results="));
       } finally {
           apiAdviceLogger.detachAppender(logCapture);
       }

       assertThat(jdbcTemplate.queryForObject(
           "SELECT status FROM booking.booking_batches WHERE id = ?", String.class, batchId))
           .isEqualTo("PENDING");
   }
   ```
   New imports needed in `BookingBatchResourceIT.java`: `ch.qos.logback.classic.Logger`,
   `ch.qos.logback.classic.spi.ILoggingEvent`, `ch.qos.logback.core.read.ListAppender`, `org.slf4j.LoggerFactory`,
   `com.softropic.skillars.platform.security.api.ApiAdvice`. `logback-classic` is already on the classpath
   transitively via `spring-boot-starter-logging`/`spring-boot-starter-test` — no `pom.xml` change. The
   appender is attached and detached inside this one test method only (no `@BeforeEach`/`@AfterEach` addition,
   no shared state with other tests, no risk from other tests running against the same logger since this test
   suite runs JUnit 5's default sequential execution — no `junit-platform.properties` parallel config exists in
   this repo). The `try`/`finally` guarantees the appender is detached even if an assertion fails, so a failing
   run does not leak a stale appender into subsequent tests in the same JVM. No production code in
   `BookingBatchService.java` or `ApiAdvice.java` changes — both already do exactly what this test proves.

2. **`CoachBookingRequestsPage.vue`'s memoized per-batch lookup retains every booking's outcome (accepted and
   failed), not only failures — with no change to `failureReasonFor`'s output for any input.**

   Verified current state (`CoachBookingRequestsPage.vue:208-233`):
   ```js
   // O(1) per-row lookup instead of a linear Array.find() scan per call — the template calls
   // failureReasonFor twice per pending row, once per re-render (skillars-deferred-34 code review
   // Decision→Defer, closed here). Vue's computed cache means this only rebuilds when
   // bookingStore.batchAcceptResultsByBatch itself changes, not on every unrelated re-render. Only
   // failed entries are stored — an accepted result and a missing result both correctly resolve to
   // "not found" below, matching the prior implementation's `!result || result.accepted` check.
   const failedResultByBatch = computed(() => {
     const byBatch = {}
     for (const [batchId, results] of Object.entries(bookingStore.batchAcceptResultsByBatch)) {
       if (!results) continue
       const byBookingId = new Map()
       for (const r of results) {
         if (!r.accepted && !byBookingId.has(r.bookingId)) byBookingId.set(r.bookingId, r)
       }
       byBatch[batchId] = byBookingId
     }
     return byBatch
   })

   function failureReasonFor(batchId, bookingId) {
     const result = failedResultByBatch.value[batchId]?.get(bookingId)
     if (!result) return null
     if (result.errorKey === 'booking.slotUnavailable') return t('booking.errors.slotUnavailable')
     if (result.errorKey === 'booking.coachUnavailable') return t('booking.errors.coachUnavailable')
     return t('booking.batch.itemNotAccepted')
   }
   ```
   `failedResultByBatch` only stores entries where `!r.accepted` (`:220`) — the pre-`skillars-deferred-35`
   implementation instead did `results.find((r) => r.bookingId === bookingId)` (finding *any* result, accepted
   or not) and only then checked `if (!result || result.accepted) return null`. The O(1) rewrite folded the
   "accepted" filter into the `Map`'s construction instead of into `failureReasonFor`'s own guard, which is
   behavior-preserving for this function's one caller (a missing entry and an accepted entry both correctly
   resolve to "no caption" either way) but means `failedResultByBatch` itself can no longer answer "was this
   booking accepted?" for anything else that might read it later — a real narrowing with no documented reason
   behind it (unlike the "Explicitly NOT in this story" rebuild-cost/pruning half, which the ledger item itself
   frames as an accepted low-priority tradeoff).

   **Required:** rename the computed to `resultByBatch`, store every result keyed by `bookingId` (not only
   failed ones), and move the "was this accepted?" check into `failureReasonFor`, restoring the guard the
   pre-refactor `Array.find()` version used:
   ```js
   // O(1) per-row lookup instead of a linear Array.find() scan per call — the template calls
   // failureReasonFor twice per pending row, once per re-render (skillars-deferred-34 code review
   // Decision→Defer, closed by skillars-deferred-35 AC2). Vue's computed cache means this only rebuilds
   // when bookingStore.batchAcceptResultsByBatch itself changes, not on every unrelated re-render. Stores
   // EVERY result (accepted and failed) — skillars-deferred-35's first cut stored only failed entries,
   // which was behavior-preserving for failureReasonFor's one caller but discarded which bookingIds were
   // accepted, narrowing this computed's usefulness for any future caller (skillars-deferred-35 code
   // review, closed here). failureReasonFor below restores the `!result || result.accepted` guard the
   // pre-refactor Array.find() version used.
   const resultByBatch = computed(() => {
     const byBatch = {}
     for (const [batchId, results] of Object.entries(bookingStore.batchAcceptResultsByBatch)) {
       if (!results) continue
       const byBookingId = new Map()
       for (const r of results) {
         if (!byBookingId.has(r.bookingId)) byBookingId.set(r.bookingId, r)
       }
       byBatch[batchId] = byBookingId
     }
     return byBatch
   })

   function failureReasonFor(batchId, bookingId) {
     const result = resultByBatch.value[batchId]?.get(bookingId)
     if (!result || result.accepted) return null
     if (result.errorKey === 'booking.slotUnavailable') return t('booking.errors.slotUnavailable')
     if (result.errorKey === 'booking.coachUnavailable') return t('booking.errors.coachUnavailable')
     return t('booking.batch.itemNotAccepted')
   }
   ```
   The only other reference to `failedResultByBatch` in the codebase is `failureReasonFor`'s own read at
   `:228` (`grep -rn "failedResultByBatch" src/frontend/` returns exactly the two lines this AC touches, both
   in this file) — no other component, store, or test references the old name, so the rename has no other
   call site to update. Behavior-preserving by construction: for every combination of inputs
   (`batchAcceptResultsByBatch[batchId]` absent/`null`/`[]`/populated; `bookingId` present-and-accepted/
   present-and-failed/absent), `resultByBatch.value[batchId]?.get(bookingId)` now returns the *same* result
   object the old `Array.find()` version would have found (accepted results are no longer filtered out at
   construction time), and `failureReasonFor`'s restored `!result || result.accepted` guard converts an
   accepted result back to `null` exactly as `Array.find()` + the old inline check did — verify this by
   inspection against all input-shape combinations before marking this AC done, since no automated frontend
   test exists to pin it (same standing gap `skillars-deferred-35` AC2 recorded). `handleAcceptAll`'s own
   direct read of `bookingStore.batchAcceptResultsByBatch[batchId] ?? []` (`:248`) is untouched — it does not
   reference `resultByBatch`/`failedResultByBatch` at all.

3. **Ledger hygiene.** In `deferred-work.md`:
   - Annotate the `getLogContext()` unit-only-coverage finding under `## Deferred from: code review of
     skillars-deferred-35-batch-failure-diagnostics-lookup-perf-and-refund-docs (2026-08-19)` (deferred-work.md
     line 1560) `[CLOSED by skillars-deferred-36 AC1]`, describing what shipped.
   - Annotate the `failedResultByBatch` finding under the same heading (deferred-work.md line 1559)
     `[CLOSED by skillars-deferred-36 AC2 — accepted-status half only]`, describing what shipped, and re-file
     the un-closed rebuild-cost/pruning half as its own new item under a new
     `## Deferred from: skillars-deferred-36 implementation (2026-08-19)` heading — **not** a "code review of
     X" heading, since this hygiene runs during the story's own AC3 step, before any code review of
     `skillars-deferred-36` itself has happened; a "code review of skillars-deferred-36" heading would claim a
     review that has not occurred. This matches the ledger's own established convention (~100 existing uses):
     "code review of X" headings are reserved for items surfaced by an actual subsequent review pass on
     already-shipped work, while items found during a story's own implementation/closure step use an
     "X implementation" or "X story creation" heading — see `## Deferred from: skillars-deferred-31
     implementation (2026-08-18)` (deferred-work.md line 1521), `## Deferred from: skillars-deferred-33
     implementation (2026-08-18)` (line 1543), and `## Deferred from: skillars-deferred-30 story creation and
     review (2026-08-18)` (line 1494) for the precedent. This still matches the split-closure *pattern* this
     ledger already uses (see the `skillars-deferred-31` AC2 entry at deferred-work.md line 1506, which closes
     only the "false-success" half of its item and re-files the per-booking-outcome-reporting half as its own
     item under exactly this heading style, later closed by `skillars-deferred-34`) — only the heading name in
     this story's original draft was wrong, not the pattern itself.
   - Do **not** re-verify or touch anything else in the file — the rest of the ledger was re-read during this
     story's creation and everything else open is either already owned by a shipped story, needs a decision
     this story does not make, or is an accepted low-priority tradeoff its own section already argues for.
   - `sprint-status.yaml`: add the
     `skillars-deferred-36-batch-none-accepted-log-coverage-and-result-map-fidelity` entry (already added at
     story-creation time by this workflow) and its `last_updated` note.

## Tasks / Subtasks

- [x] **Task 1 — AC1: prove `getLogContext()` is read by `ApiAdvice.logError` over real HTTP**
  - [x] Add the five new imports to `BookingBatchResourceIT.java`
  - [x] Attach a `ListAppender<ILoggingEvent>` to `LoggerFactory.getLogger(ApiAdvice.class)` inside
        `acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey`, before the HTTP call
  - [x] Add the post-HTTP-call assertion on the captured log event's `getArgumentArray()[0].toString()`
  - [x] Wrap the appender attach, the HTTP call, and the new assertion together in one `try`/`finally` —
        `addAppender` must be the first statement inside the `try`, so the entire attached-appender lifetime is
        covered and `finally` always detaches it, even if an earlier call (e.g. `loginAndGetCookies`, which
        runs before the `try`) or the HTTP call itself throws
  - [x] No change to any existing assertion in this test, and no change to `BookingBatchService.java` or
        `platform/security/api/ApiAdvice.java`
  - [x] Run `mvn -Dtest=BookingBatchResourceIT test` (per `docs/validation-strategy.md`'s smallest-relevant-scope
        policy), confirm green including the new log-capture assertion
- [x] **Task 2 — AC2: `resultByBatch` retains accepted-status fidelity**
  - [x] Rename `failedResultByBatch` → `resultByBatch`
  - [x] Drop the `!r.accepted` filter from the computed's inner loop — store every result, first-match-wins
        (keep the existing `!byBookingId.has(r.bookingId)` duplicate guard)
  - [x] Move the accepted/missing check into `failureReasonFor`: `if (!result || result.accepted) return null`
  - [x] Update the doc comment above the computed to describe the new behavior and cite this story
  - [x] Confirm by inspection that `failureReasonFor`'s return value is unchanged for every input-shape
        combination (see AC2's own text for the full enumeration) — this is the *only* verification available;
        no automated test exists for `CoachBookingRequestsPage.vue` in this repo (standing gap, not a
        regression in rigor since nothing pinned this behavior before either)
- [x] **Task 3 — AC3: ledger hygiene**
  - [x] `[CLOSED by skillars-deferred-36 AC1]` on the `getLogContext()` unit-only-coverage finding
  - [x] `[CLOSED by skillars-deferred-36 AC2 — accepted-status half only]` on the `failedResultByBatch`
        finding, plus a new re-filed item for the un-closed rebuild-cost/pruning half
  - [x] `sprint-status.yaml` entry

## Dev Notes

### Established conventions this story must follow

- **Extend an existing test in place rather than adding a new fixture, when the existing test already drives
  the exact code path.** `skillars-deferred-32` AC6 and `skillars-deferred-35` AC4 both did this; AC1 here
  extends `acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey` rather than writing a
  second test that re-creates the same batch/decline setup.
- **A diagnostic-only (non-wire) field's coverage gap is closed by proving the logging pipeline itself, not by
  putting the field on the wire.** AC1 does not change what `ErrorDto` carries — it proves the existing,
  already-shipped `getLogContext()`/`ApiAdvice.logError` mechanism actually fires over a real request, matching
  the same "diagnostic-only field" scoping `skillars-deferred-35` AC1 itself established.
- **`resultByBatch`'s rename and behavior change in AC2 must be behavior-preserving for `failureReasonFor`'s
  one caller.** No template change, no i18n key change, no `errorKey` branching change — only where the
  "accepted?" check lives moved from inside the computed to inside the function that reads it.
- **Lean, decision-free bundling.** Per `skillars-deferred-31`/`32`/`33`'s precedent and `skillars-deferred-
  34`/`35`'s own explicit finding that the ledger is "mined thin," this story bundles two small, unrelated,
  mechanical items rather than forcing in anything requiring a product/design decision or a larger scoped
  change (see "Explicitly NOT in this story" above for what was deliberately excluded).
- **Split-closure ledger annotation.** When a story closes only part of a bundled ledger finding, the
  established pattern (see `skillars-deferred-31` AC2 at deferred-work.md line 1506) is to annotate the closed
  part inline and re-file the residual as its own new item, rather than leaving the whole finding open or
  silently dropping the unclosed half.

### Files being modified — current state and what must be preserved

- **`BookingBatchResourceIT.java`** (`:1-36` imports, `:492-524`
  `acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey`) — AC1 adds five imports and
  extends this one test method with a `ListAppender` setup/assert/teardown. No other test in this file, and no
  other part of this method's existing assertions, changes.
- **`CoachBookingRequestsPage.vue`** (`:208-233`) — AC2 renames one `computed`, changes one line inside it (drop
  the `!r.accepted` filter), and changes one line in `failureReasonFor` (restore the `!result ||
  result.accepted` guard). The template (`:42-90`), `handleAcceptAll` (`:243-288`), `batchIsActionable`
  (`:239-241`), and every other function in the file are unchanged.

### Project Structure Notes

- No new REST endpoint, no DTO, no migration, no new i18n keys, no production Java changes — this story is a
  test-coverage addition and a frontend lookup-fidelity restoration only.
- No new files. Both edits are to files already tracked by prior stories in this same module family
  (`skillars-deferred-34`/`35`).

### References

- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:237-300`
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java:267-277,595-651` (correct
  package — NOT `infrastructure.exception.ApiAdvice`, which does not exist; see "Why this story exists" for
  the ledger citation this corrects)
- `src/main/java/com/softropic/skillars/infrastructure/exception/ApplicationException.java` (`getLogContext()`
  accessor)
- `src/main/java/com/softropic/skillars/platform/security/contract/exception/OperationNotAllowedException.java`
  (`extends AuthorizationException`)
- `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java:1-36,492-524`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java:690-764`
  (existing unit-level `getLogContext()` coverage, unchanged by this story)
- `src/test/resources/logback-test.xml:27` (`<root level="WARN">`)
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue:208-248`
- `src/frontend/src/stores/booking.store.js:571` (`batchAcceptResultsByBatch` shape, unchanged by this story)
- `pom.xml:328-332` (`net.logstash.logback:logstash-logback-encoder:8.1`, already a project dependency)
- `_bmad-output/implementation-artifacts/deferred-work.md` (`## Deferred from: code review of
  skillars-deferred-35-batch-failure-diagnostics-lookup-perf-and-refund-docs (2026-08-19)`)
- `_bmad-output/implementation-artifacts/skillars-deferred-35-batch-failure-diagnostics-lookup-perf-and-refund-docs.md`
  (methodology and format this story replicates; also the AC1/AC2 diffs this story extends)
- `_bmad-output/implementation-artifacts/skillars-deferred-34-batch-accept-per-booking-outcome-reporting.md`
  (split-closure ledger-annotation precedent)
- `_bmad-output/project-context.md`
- `docs/validation-strategy.md` (smallest-relevant-scope test policy)

## Dev Agent Record

### Implementation Plan

Implemented exactly as specified in the story — both ACs already had the required diff fully worked out in the
story text, so no design decisions were needed beyond applying it.

- **AC1:** Extended `acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey` in
  `BookingBatchResourceIT.java` with a Logback `ListAppender<ILoggingEvent>` attached to
  `LoggerFactory.getLogger(ApiAdvice.class)` before the HTTP call, asserting after the existing HTTP-response
  assertions that the captured log event's `getArgumentArray()[0].toString()` contains both `"batch id="` and
  `"per-booking results="`. Added the five imports the story specified
  (`ch.qos.logback.classic.Logger`, `ch.qos.logback.classic.spi.ILoggingEvent`,
  `ch.qos.logback.core.read.ListAppender`, `org.slf4j.LoggerFactory`,
  `com.softropic.skillars.platform.security.api.ApiAdvice`). No production code changed.
- **AC2:** Renamed `failedResultByBatch` → `resultByBatch` in `CoachBookingRequestsPage.vue`, dropped the
  `!r.accepted` filter from the computed's inner loop (kept the existing duplicate-`bookingId` guard), and moved
  the accepted/missing check into `failureReasonFor` as `if (!result || result.accepted) return null`. Updated
  the doc comment above the computed to describe the new behavior. Confirmed by inspection (no automated
  frontend test exists for this file, a standing repo-wide gap) that `failureReasonFor`'s return value is
  unchanged for every input-shape combination the AC enumerates. Confirmed via
  `grep -rn "failedResultByBatch" src/frontend/` that no other call site referenced the old name.
- **AC3:** Annotated both closed findings in `deferred-work.md` under the existing
  `## Deferred from: code review of skillars-deferred-35-...` heading, and re-filed the un-closed
  rebuild-cost/pruning half of the `failedResultByBatch` finding as its own item under a new
  `## Deferred from: skillars-deferred-36 implementation (2026-08-19)` heading, matching the
  `skillars-deferred-31`/`34` split-closure precedent the story cites. `sprint-status.yaml`'s
  `skillars-deferred-36` entry was already present from story creation; updated to `in-progress` at the start of
  this session and to `review` at completion (Step 9).

### Completion Notes

- All 3 ACs done. No production Java or DTO/migration changes (AC1 is test-only, matching the story's explicit
  scope note); AC2 is a frontend-only rename plus moving one guard.
- **AC1 validation:** `mvn -Dtest=BookingBatchResourceIT test` — 11/11 tests green, including the extended
  `acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey` with its new log-capture
  assertion. Per `docs/validation-strategy.md`'s smallest-relevant-scope policy, the full `mvn verify` suite was
  not run.
- **AC2 validation:** `npx eslint src/pages/coach/CoachBookingRequestsPage.vue` — clean, no findings.
  `grep -rn "failedResultByBatch" src/frontend/` — zero hits, confirming the rename left no stale references.
  Behavior-preservation verified by inspection only, as the story itself specifies (no automated frontend test
  exists for this component in this repo — a standing gap this story does not close).
- **AC3 validation:** `grep -n "skillars-deferred-36" deferred-work.md` confirms both `[CLOSED by ...]`
  annotations and the new re-filed item landed as specified.

### Senior Developer Review (AI)

**Date:** 2026-08-19 · **Outcome:** Approve · **Layers:** Blind Hunter + Edge Case Hunter + Acceptance Auditor

**Acceptance Auditor:** 0 AC violations. All 3 ACs verified to match the story's own "Required" code blocks
character-for-character (`git diff HEAD` compared directly against the story text); no production code touched
outside `BookingBatchResourceIT.java`/`CoachBookingRequestsPage.vue`/`deferred-work.md`/`sprint-status.yaml`;
`grep` confirmed zero stray `failedResultByBatch` references and zero other ledger lines touched.

**Blind Hunter:** 16 findings raised, all dismissed after verification against the actual codebase:
- False positives disproven by reading the real source: the SLF4J-argument-array assumption (`ApiAdvice.logError`
  calls `log.error(fullMsg, entries(ctx), throwable)` — the trailing `Throwable` is stripped by SLF4J convention,
  leaving `entries(ctx)` as the sole `getArgumentArray()[0]` element); the concurrency/appender-isolation risk (no
  `junit-platform.properties` or parallel-execution config exists — tests run sequentially); the stale
  `ApiAdvice.java` path citation left un-rewritten (matches the ledger's own documented convention of correcting
  via appended notes rather than rewriting history — see `deferred-work.md`'s "How to read this file" section);
  no stray renamed-symbol references (independently `grep`-verified).
- Already-addressed / explicit-scope, not defects: the unpruned result-map memory growth (this is precisely the
  ledger item AC3 re-files as its own tracked item, not an omission); the missing automated frontend test (a
  standing, already-documented repo-wide gap predating this story); the weak substring assertion, single-method
  scope, Logback coupling, missing `logCapture.stop()`, ledger prose style, and narrative code comments (each
  matches an explicit story instruction — exact required code, "extend in place" — or established codebase
  precedent).

**Edge Case Hunter:** 3 findings raised, all dismissed as unreachable given the actual production code: a
duplicate-`bookingId`-in-one-batch scenario (disproven — `BookingBatchService.acceptAll` builds `results` from one
iteration per distinct `Booking` row returned by the repository, so a duplicate is structurally impossible); a
null/empty log-argument-array scenario and its `anySatisfy`-propagation consequence (disproven — every log
statement in `ApiAdvice.java` was `grep`-checked and passes at least one argument, so `getArgumentArray()[0]`
never throws for any event this appender can capture).

**Result:** 0 decision-needed, 0 patch, 0 defer (net-new), 19 dismissed. Clean review — story moved to `done`.

## File List

- `src/test/java/com/softropic/skillars/platform/booking/api/BookingBatchResourceIT.java` (modified — 5 new
  imports, `acceptAll_everySiblingDeclinedBeforehand_returns403WithBatchNoneAcceptedKey` extended with
  `ListAppender` log-capture setup/assert/teardown)
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue` (modified — `failedResultByBatch` renamed to
  `resultByBatch`, accepted-status fidelity restored, doc comment updated)
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified — 2 findings annotated `[CLOSED ...]`, 1
  new item re-filed under a new `skillars-deferred-36 implementation` heading)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified — `skillars-deferred-36` status
  transitioned `ready-for-dev` → `in-progress` → `review` → `done`)
- `_bmad-output/implementation-artifacts/skillars-deferred-36-batch-none-accepted-log-coverage-and-result-map-fidelity.md`
  (modified — this file: Tasks/Subtasks checked off, Dev Agent Record, File List, Change Log, Status)

## Change Log

| Date | Change |
|---|---|
| 2026-08-19 | Story implemented: AC1 (BookingBatchResourceIT ListAppender log-capture coverage), AC2 (resultByBatch accepted-status fidelity restoration), AC3 (ledger hygiene — 2 findings closed, 1 re-filed). Targeted `mvn -Dtest=BookingBatchResourceIT test` green (11/11); eslint clean on the modified Vue file. |
| 2026-08-19 | Code review complete (Blind Hunter + Edge Case Hunter + Acceptance Auditor): 0 AC violations, 19 findings raised and dismissed after verification against actual code, 0 patches needed. Status moved to done. |
