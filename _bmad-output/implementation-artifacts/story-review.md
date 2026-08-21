# Senior-Dev Review: Story Deferred-51 (Gemini Truncation Safety, Moderation-Sweep E2E Coverage & BookingServiceTest Injection Hygiene)

Reviewed file: `_bmad-output/implementation-artifacts/skillars-deferred-51-gemini-truncation-safety-moderation-queue-e2e-coverage-and-booking-service-test-hygiene.md`

Method: every factual claim in the story (line citations, grep results, constructor arity, config
defaults, ledger text, test-class shapes) was independently re-verified against the live repo, not
trusted from the story text. Findings below are limited to things actually confirmed against the
code — no speculative "might be an issue" items.

---

## Finding 1 (Confirmed, High severity) — AC1's prescribed unit test does not test the fix

**Where:** AC1's new test `contentWithSurrogatePairAtTruncationBoundary_dropsWholePairRatherThanSplittingIt`
(story lines 133–145), targeting `GeminiModerationServiceTest.java`.

**The problem:** the test captures the *entire* prompt string (`promptTemplate + "\n\n---BEGIN USER
CONTENT---\n" + sanitizedInput + "\n---END USER CONTENT---"`) and asserts on its **last character**:

```java
Character.isHighSurrogate(sentPrompt.charAt(sentPrompt.length() - 1))  // asserted false
```

The last character of the prompt is always part of the fixed literal suffix `"\n---END USER
CONTENT---"` — a hyphen — regardless of what happened at the truncation boundary in the middle of
the string. This assertion is unconditionally true whether or not AC1's fix is applied.

The second assertion, `!sentPrompt.contains("😀")`, doesn't discriminate either. Worked through by
hand with the story's own example (`content = "x".repeat(99) + "😀" + "x".repeat(10)`,
`maxInputChars = 100`):

- **Unpatched code** (`content.substring(0, 100)`): keeps indices 0–99, i.e. 99 `x`s plus the emoji's
  lone high surrogate (`\uD83D`) — the low surrogate (`\uDE00`) is truncated away. The 2-character
  sequence `"😀"` (high+low together) is therefore **not present** in the resulting prompt either —
  only a dangling high surrogate is, followed by `\n---END USER CONTENT---`.
- **Patched code**: backs the cutoff off to 99, dropping the whole pair — `"😀"` is also absent.

Both the buggy and the fixed implementation produce a prompt containing neither `"😀"` nor a
surrogate as the *final* character, so both assertions pass identically either way. Revert AC1's
production fix and this test still goes green — it would not catch a regression, and per the
project's own established mutation-verification discipline (used throughout this same ledger, e.g.
D2/D6 entries), that means the test doesn't actually prove what AC1 claims.

**Why it's reachable, not theoretical:** this isn't a corner case in the fix — it's the primary test
the story specifies for AC1, described as mirroring `AdminQueueService.preview()`'s already-fixed
sibling bug, which is the whole point of this AC.

**Suggested correction:** mirror the file's own existing `contentExceedsMaxInputChars_
truncatedBeforeSending` test shape (which the story's own text says to mirror, but then deviates
from) — build the exact expected full prompt string with the correctly-truncated content and assert
equality via `verify(geminiClient).evaluate(expectedPrompt)`, the same pattern already used two
tests up in the same file. That pattern pins the *exact* surviving content, which would fail on the
unpatched code (expected `"x".repeat(99)` vs. actual `"x".repeat(99) + "\uD83D"`).

---

## Finding 2 (Confirmed, Medium severity) — AC1's proposed fix throws for `maxInputChars <= 0`

**Where:** AC1's code sample (story lines 111–119):

```java
int cutoff = maxInputChars;
if (content.length() > cutoff && Character.isHighSurrogate(content.charAt(cutoff - 1))) {
    cutoff--;
}
```

If `maxInputChars` is configured to `0` (or negative), and `content` is non-blank (guaranteed by the
earlier blank-check), then `content.length() > cutoff` is true and `content.charAt(cutoff - 1)`
evaluates `charAt(-1)`, throwing `StringIndexOutOfBoundsException`. This happens **before** the
`try/catch` block that already exists around the Gemini call (story/production code lines 53–67), so
the exception propagates uncaught out of `moderate()` — a regression the current (unfixed) code does
not have: `content.substring(0, 0)` for `maxInputChars = 0` simply returns `""`, no exception.

No profile in the repo currently sets `max-input-chars` to `0` (checked `application.yaml:277,282`
and `application-test.yaml:104` — 4000, 2000 and 100 respectively), so this is not reachable with
today's configuration. It is a real latent crash for any future misconfiguration of this
`@Value`-injected `int` field, which carries no validation. Worth a one-line guard (e.g. `cutoff > 0
&&` in the condition) since the fix is already touching this exact block, but not blocking if the
team accepts the config-value trust boundary as-is.

---

## Finding 3 (Confirmed, Informational) — AC4's ledger tagging is already done

**Where:** AC4 / Task 4 (story lines 269–283, 301–302).

All four `[PICKED UP by skillars-deferred-51 AC*]` tags AC4 instructs the dev to add were **already
applied to `deferred-work.md` by the story-creation commit itself** (`fdadd6e`, 8 lines changed in
that file). Verified directly: the D5, D7, and both `BookingServiceTest` bullets (review-round and
D17 patch-round) in the live `deferred-work.md` already carry their respective `[PICKED UP by
skillars-deferred-51 AC1/AC2/AC3]` tags right now, before any AC1–AC3 code exists.

This matches an established pattern in this repo — `git log -S "PICKED UP by skillars-deferred-41
AC1"` shows that tag was also introduced by its story's *creation* commit rather than its
implementation commit — so this is very likely intentional process convention, not a mistake.
Flagging it only because the story's own Task list presents Task 4 as pending work ("apply the three
`[PICKED UP]` tags"), which could cause a dev to attempt an `Edit`/find-replace against text that no
longer matches (already tagged), or to waste time double-checking whether they missed a step. Worth
a one-line Dev Note acknowledging AC4 is already satisfied at pickup and only needs verification, not
action.

---

## Everything else checked and found accurate (no false positives worth reporting)

For completeness, since the ask was explicitly to avoid false positives, here is what was verified
and confirmed *correct* rather than flagged:

- **AC1 core algorithm**: backing the cutoff off by one when `content.charAt(cutoff - 1)` is a high
  surrogate is the right and sufficient check — a pair fully inside the truncation boundary always
  has its *low* surrogate at that position, not its high one, so the check can't false-trigger.
- **AC2's chain**: independently traced `MessageModerationSweeper.sweep()` → `sweepOne()` →
  `MessageHeldForReviewEvent` → `AdminAlertEventListener.onMessageHeldForReview` (REQUIRES_NEW,
  synchronous) → `AdminQueueService.buildSummary` → the `GET /api/admin/queue` response shape, and
  `AdminMessageService.approveMessage` → `resolveOpenAlert(..., MODERATION_UNRESOLVED, ...)`. Every
  step matches the story's test code exactly, including the expected summary string
  `"MODERATION_ORPHAN_SWEPT: Stranded content"` (reason prefix + `preview()` of the content) and the
  post-approve `RESOLVED` status.
- **AC2 fixture correctness**: `sweptMessageId = 9000_001_004L` doesn't collide with any existing ID
  in the file's `9000_001_xxx` sequence; `COACH_USER_ID`/`CONVERSATION_ID` are valid FKs seeded by
  `setUp()`; the 1-hour-old `created_at` clears the 30-minute default grace period
  (`platform.messaging.moderation_orphan_grace_minutes`, confirmed default in `V91` migration, no
  test-profile override); `releaseSchedulerLock` is genuinely inherited from `AbstractIntegrationTest`
  and genuinely necessary given `sweep()`'s `lockAtLeastFor = "PT2M"`.
- **AC3 mechanics**: `BookingService` is confirmed to have exactly one Lombok
  `@RequiredArgsConstructor`-generated 15-arg constructor with types matching 1:1, uniquely, against
  the test's 15 mock/spy fields — `@InjectMocks` constructor-injection by type will resolve
  unambiguously. `BookingStateMachine.transition(...)` is confirmed to carry real, consequential state
  logic (a full `BookingStatus × BookingEvent → BookingStatus` map) that several `BookingServiceTest`
  assertions depend on (`booking.getStatus()` equality checks), so `@Spy` (not `@Mock`, unlike the
  sibling `ExpiredPackBookingValidationTest`) is the correct call — a bare mock would silently null out
  every transition. All cited grep results (`grep -c "bookingStateMachine\."` → 0 outside the deleted
  block; `new BookingService(` / `new BookingStateMachine(` → only the two lines being deleted;
  repo-wide `new BookingService(` → only this file) reproduce exactly as claimed. Line citations
  (`:89-90`, `:97-112`, `:99-107`) match the current file precisely.
- **Ledger provenance**: D5, D6, D7 (skillars-deferred-16 review section) and both `uat-3`
  review/patch-round `BookingServiceTest` bullets read exactly as the story quotes/paraphrases them,
  including D6's staleness writeup and D3/D4's product-decision framing for the deliberate exclusions.
- **`ExpiredPackBookingValidationTest`'s `@InjectMocks` pattern predates both ledger filings** — first
  committed 2026-06-25, well before the 2026-08-05 and 2026-08-11 entries the story cites.
- **Failsafe/`mvn test` split**: `pom.xml` confirms `maven-failsafe-plugin` is bound to
  `integration-test`/`verify` with no `surefire` IT wiring, matching the Dev Notes' gotcha.
- **`MessagingService.java:160`'s 2000-code-point guard** is confirmed separate and untouched by AC1's
  change, as the story claims.
