# Story Review: skillars-deferred-36-batch-none-accepted-log-coverage-and-result-map-fidelity

Reviewed as a senior dev before dev-story execution. Every claim below was checked against the current
working tree (not just the story's own text) — file paths, line numbers, class hierarchies, Maven/Failsafe
config, and the deferred-work.md ledger's actual heading conventions. Items that turned out to be accurate
on inspection are not listed as findings.

## Findings

### 1. AC3's prescribed heading name for the re-filed ledger item contradicts its own cited precedent

**Where:** AC3 / Task 3, and the "Explicitly NOT in this story" section.

The story instructs filing the un-closed rebuild-cost/pruning half under a new
`## Deferred from: code review of skillars-deferred-36-...` heading, citing "the `skillars-deferred-31` AC2
entry at deferred-work.md line 1506" as the precedent for this split-closure pattern.

Line 1506 is indeed the right item to point at for the *split-closure* pattern (partial close + re-file the
residual) — but its residual half was **not** filed under a "code review of X" heading. It landed at
deferred-work.md line 1521 under `## Deferred from: skillars-deferred-31 implementation (2026-08-18)`. The
same is true for every other same-story (pre-review) re-filing in this ledger: `skillars-deferred-32
implementation`, `skillars-deferred-33 implementation`, `skillars-deferred-26-...-fixes story creation`,
`skillars-deferred-28-...-test story creation`, `skillars-deferred-30 story creation and review`. The
`## Deferred from: code review of X` heading form is reserved, throughout this file's ~100 uses of it, for
items surfaced by an actual subsequent code-review pass on already-shipped work — not for hygiene performed
during a story's own AC3/closure step, which is what this story's AC3 is.

Since `skillars-deferred-36` has not been code-reviewed yet at the point AC3 runs, filing under
`## Deferred from: code review of skillars-deferred-36-...` would itself be a factually wrong heading
(claiming a review that hasn't happened), and breaks the ledger's own established naming convention that a
future story-creation pass (which this project's own workflow relies on to mine the ledger) uses to tell
"found during implementation" apart from "found during review." The correct heading, matching the actual
precedent, is `## Deferred from: skillars-deferred-36 implementation (2026-08-19)`.

**Fix:** change AC3 / Task 3's heading instruction to `## Deferred from: skillars-deferred-36 implementation
(2026-08-19)`.

### 2. Tasks/Subtasks omit the "run targeted tests" step every recent sibling story includes

**Where:** Task 1 and Task 2 checklists.

`docs/validation-strategy.md` (cited in this story's own References) establishes "run targeted tests" as
this project's standard validation policy, and the Dev Notes' "Established conventions this story must
follow" section commits generally to following established conventions. Every directly preceding sibling
story's task list makes this an explicit, separate checklist line: `skillars-deferred-35`'s Task 4 has
"Targeted `BookingBatchServiceTest` suite green (26/26) per `docs/validation-strategy.md`'s
smallest-relevant-scope policy," and `skillars-deferred-31`/`32`/`34` follow the same pattern.

This story's Task 1 (AC1, backend) and Task 2 (AC2, frontend) both end without any subtask instructing a
targeted test run — e.g. `mvn -Dtest=BookingBatchResourceIT test` for Task 1, or a frontend lint/build check
for Task 2. Nothing about AC1 or AC2's prose substitutes for this — AC1's "Required" section describes what
to add to the test, not that it must be run and confirmed green, and AC2 explicitly says its correctness
must be confirmed "by inspection" (no automated frontend test exists), which makes an explicit backend
test-run task even more load-bearing here since it's the *only* automated check this story gets.

**Fix:** add a subtask under Task 1 ("Run `mvn -Dtest=BookingBatchResourceIT test`, confirm green including
the new assertion") and note under Task 2 that AC2's correctness is confirmed by inspection only, matching
how `skillars-deferred-35`'s Task 4 stated the same limitation explicitly rather than leaving it implicit.

### 3. AC1's sample code: the appender-attach happens outside the `try`/`finally` it's claimed to be inside

**Where:** AC1 "Required" code sample, and the surrounding prose's guarantee claim.

The prose states: "The `try`/`finally` guarantees the appender is detached even if an assertion fails, so a
failing run does not leak a stale appender into subsequent tests in the same JVM." Looking at the actual
sample:

```java
Logger apiAdviceLogger = (Logger) LoggerFactory.getLogger(ApiAdvice.class);
ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
logCapture.start();
apiAdviceLogger.addAppender(logCapture);

String coachCookies = loginAndGetCookies(COACH_EMAIL);   // <-- outside the try
try {
    ...
} finally {
    apiAdviceLogger.detachAppender(logCapture);
}
```

`addAppender` runs before the `try` opens, and `loginAndGetCookies(COACH_EMAIL)` — which can throw — sits
between the attach and the `try`. If that call throws, `logCapture` is never detached: it stays attached to
`ApiAdvice`'s logger (a singleton for the rest of the JVM run) and silently keeps accumulating every
subsequent `ApiAdvice.logError` event system-wide, for the remainder of this Failsafe run — this project's
own pom.xml documents that all ~135 IT classes share one JVM fork with no `forkCount`/`reuseForks`, so this
is not a contained blast radius. `loginAndGetCookies` is an established, normally-reliable helper used
throughout this test class, so the odds of it throwing here are low, but the code as written does not back
the strength of the claim made about it, and the fix is one line.

**Fix:** move `apiAdviceLogger.addAppender(logCapture)` to the first line inside the `try` block (after
`loginAndGetCookies`, before the HTTP call), so the entire attached-appender lifetime is covered by the
`finally`.

## Verified as accurate — no finding

For transparency, the following claims were specifically checked and confirmed correct, so they are not
findings:

- `ApiAdvice`'s real path (`platform/security/api/ApiAdvice.java`, public class, `@RestControllerAdvice`) and
  the ledger's stale `infrastructure.exception.ApiAdvice` citation.
- The exception chain `OperationNotAllowedException extends AuthorizationException extends
  ApplicationException`, and that `operationDeniedHandler` → `handleSecErrorAndReturnDTO(AuthorizationException,
  ...)` → `logErrorAndReturnDTO` → `logError` is the actual call path.
- `ApiAdvice.logError`'s body, including `entries(ctx)`/`Map.of("batch id", ..., "per-booking results",
  ...)` at `BookingBatchService.java:298-300`, matches the story's quoted code exactly.
- No `junit-platform.properties` exists and Failsafe's `argLine` sets no JUnit 5 parallel-execution
  properties — the "sequential execution, no cross-test appender interference" claim holds.
- `grep -n "getLogContext" src/test` returns exactly the two hits in `BookingBatchServiceTest.java` the
  story cites (lines 721, 756) — confirmed both are covered by mutation-verified unit assertions across
  *both* `acceptedIds.isEmpty()` branches (non-empty `results` on path (a), empty `results` on path (b)).
  This also confirms AC1's IT deliberately targeting only path (b) (empty `results`) is not a coverage gap:
  payload *content* correctness for non-empty `results` is already unit-tested elsewhere: AC1's IT exists
  only to prove `ApiAdvice` actually reads and logs whatever `getLogContext()` returns over a real request,
  which it does regardless of whether `results` is empty.
- `grep -rn "failedResultByBatch" src/frontend/` returns exactly the two lines AC2 touches — the rename has
  no other call site, and `resultByBatch` collides with no existing identifier in either touched file.
- AC2's behavior-preservation table (accepted/failed/absent × `null`/`[]`/populated) checks out by
  inspection, including the one subtle case worth naming: if a batch's `results` array ever contained a
  duplicate `bookingId` with one entry accepted and another failed, the currently-shipped code and the
  pre-refactor `Array.find()` version diverge (shipped code's `!r.accepted` prefilter can surface a *later*
  failed duplicate that `Array.find()` would never have reached), while AC2's fix removes exactly that
  prefilter and restores byte-for-byte positional parity with `Array.find()`. Not a shipped defect (batch
  results don't produce duplicate `bookingId`s in practice) and not a finding, but confirms AC2's fix is
  correct on this axis rather than merely "probably fine."
- No automated test currently exists for `CoachBookingRequestsPage.vue`, so AC2's "verify by inspection"
  requirement is not a regression in rigor — there was nothing pinning this behavior before either.
