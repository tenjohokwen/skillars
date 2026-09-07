# Senior Dev Audit — skillars-deferred-95: Deployment Visibility & Messaging Identity Inheritance Fixes

**Reviewed:** `_bmad-output/implementation-artifacts/skillars-deferred-95-deployment-visibility-and-messaging-identity-inheritance-fixes.md`
**Reviewed against:** working tree at `HEAD` (branch `story/deferred-95-visibility-messaging-fixes`, parent merge `28354e7` = deferred-94 PR #151)
**Date:** 2026-09-07

---

## Verdict: DO NOT IMPLEMENT AS WRITTEN — both acceptance criteria describe bugs that are already fixed in `master`.

This story was scoped from a stale slice of `deferred-work.md`. Both underlying defects were closed by prior, already-merged stories:

| AC | Claimed defect | Actual state | Closed by |
|----|----------------|--------------|-----------|
| AC1 | `Fail workflow` step unreachable when a notification step throws | **Fixed.** All 4 notify steps carry `continue-on-error: true`; `Fail workflow` step carries `if: always() && steps.smoke.outputs.result == 'fail'` | skillars-deferred-94 AC12 — commits `c4e9366`, `71b08b7` (merged in #151, `28354e7`) |
| AC2 | `MessagingReportService.verifyIsParty()` is a pre-fix hand-copy with the userId==playerProfileId bug | **Fixed.** Method is already role-aware and logically identical to `MessagingService.verifyIsParty()`; `PlayerProfileRepository` already injected; covered by an IT | skillars-deferred-16 AC4 — commit `c7301e0` (shipped 2026-08-05) |

If merged, a developer would open both files, find the fix already present, change nothing, and tick the boxes. Net effect: wasted cycle plus a non-zero risk of harmful "cleanup" (see Finding 5).

---

## Findings

### F1 — AC1 premise is false: the deploy-workflow fix is already in `master` (CONFIRMED, blocker)

`.github/workflows/deploy.yml` at HEAD:

- Line 130 — `Notify Slack — success`: `continue-on-error: true`
- Line 142 — `Notify Slack — failure & revert`: `continue-on-error: true`
- Line 154 — `Email — success`: `continue-on-error: true`
- Line 171 — `Email — failure & revert`: `continue-on-error: true`
- Lines 188–194 — `Fail workflow on smoke test failure`: `if: always() && steps.smoke.outputs.result == 'fail'`, with an in-file comment stating the exact invariant the story asks to establish ("no step between `smoke` and this marker may fail the job without `continue-on-error: true`").

The story's own "Fix pattern" section recommends "Combine both — mark notifications as non-blocking AND ensure failure step runs regardless." That is precisely what is already committed. `git show c4e9366 -- .github/workflows/deploy.yml` and `git log -S 'continue-on-error' -- .github/workflows/deploy.yml` confirm deferred-94 introduced the four `continue-on-error` lines; `71b08b7` added `always()` to the marker.

**Nothing in AC1 remains to be done.**

### F2 — AC2 premise is false: `MessagingReportService.verifyIsParty()` was already fixed by deferred-16 (CONFIRMED, blocker)

`src/main/java/com/softropic/skillars/platform/messaging/service/MessagingReportService.java` at HEAD:

- Line 43 — `private final PlayerProfileRepository playerProfileRepository;` is already injected.
- Lines 134–155 — `verifyIsParty()` is already role-aware:
  - `COACH` → `coachProfileRepository.findByUserId(callerUserId)` then compare to `conv.getCoachId()`
  - `PARENT` → `Objects.equals(conv.getParentId(), callerUserId)`
  - `PLAYER` → `playerProfileRepository.findByUserId(callerUserId)` then compare to `conv.getPlayerId()`
  - `default` → throws `OperationNotAllowedException(NOT_A_PARTY)` (unrecognised role is rejected, not silently defaulted)
- Lines 129–133 — the class comment already documents the deferred-16 co-fix: "both fixed together for skillars-deferred-16 (silent default->player-id-vs-user-id compare, and no rejection of an unrecognised role)."

This is logically identical to `MessagingService.verifyIsParty()` (`MessagingService.java:367–395`). `git log -- MessagingReportService.java` shows the last functional change was `c7301e0` (Story Deferred-16). The story's AC2 verification step ("Compare … they should be identical") already passes.

**Nothing in AC2 remains to be done.**

### F3 — AC2's "test may not be needed" reasoning is wrong: the test already exists and passes (CONFIRMED)

The story says: "if `MessagingReportService` has its own tests for `verifyIsParty()`, verify they pass; if not, no new test is needed."

`src/test/java/com/softropic/skillars/platform/messaging/api/MessagingAccessControlIT.java:234 unrecognisedRole_yields403NotFatal()` already drives `messagingReportService.reportConversation(...)` with an unrecognised role and asserts a 403 (`OperationNotAllowedException`), lines 252–262. deferred-16 code review D2 (`deferred-work.md:981`) explicitly names `MessagingReportService.verifyIsParty` as one of "the two reachable arms" the IT asserts. The presence of a green, mutation-sensitive test targeting this exact method is independent confirmation that AC2 is already delivered.

### F4 — Every line-number citation in the story is stale (CONFIRMED)

The citations were lifted verbatim from the 2026-09-04 `deferred-work.md` audit snapshot and never re-verified against HEAD. deferred-94's own `continue-on-error` additions shifted the workflow down ~4 lines after that snapshot.

| Story says | Actual at HEAD |
|---|---|
| `deploy.yml:184-188` (`Fail workflow`) | `deploy.yml:188-194` |
| notify steps at `:128`/`:139`, `:150`/`:166` | `:128`/`:140`, `:152`/`:169` |
| `MessagingReportService.java:127-141` (method) | method body `:134-155`, comment `:129-133` |

The `deferred-work.md` ledger itself flags this pattern repeatedly ("citation stale"). A story should re-anchor citations at creation time, not copy them from a dated audit.

### F5 — AC2's rationale contains a claim the codebase explicitly contradicts (MINOR, but a real trap for an implementer)

The story's "Fix approach" says: *"Inject `PlayerProfileRepository` (no circular dep because `MessagingReportService` doesn't inject `MessagingService`)."* This phrasing implies injecting `MessagingService` would be a safe alternative. The in-file comment (`MessagingReportService.java:129-133`) says the opposite in bold terms: *"Do not 'solve' the duplication by injecting `MessagingService` or extracting a shared bean — the circular dependency is real."* An implementer who takes the story's rationale at face value and "improves" the design by consolidating the two copies would reintroduce a real cycle and undo a deliberate decision. Because the actual work is a no-op, this trap is the story's main residual risk.

### F6 — Scope was drawn from an outdated view of the ledger; the genuinely-open adjacent items are omitted (MEDIUM — process)

The story's "Deferred Items NOT Included" section lists deferred-94 AC6/AC15 exclusions but does not mention the deferred-94 **code-review deferrals** recorded in the same file at `deferred-work.md:1412-1420` (dated 2026-09-07, the same day). Had the author read that section they would have found:

1. **The real still-open deploy gap** (`deferred-work.md:1414`): if the `Smoke test` step itself *errors* (ssh/sleep dies) rather than completing with `result=fail`, `steps.smoke.outputs.result` is never written — so the `pass`/`fail` branches, `Auto-Revert` (`:109`), and the `Fail workflow` marker (`:189`) all skip, and the pre-smoke early-failure notifications (`:196`/`:207`) don't fire because they gate on `steps.smoke.outcome == 'skipped'`, not `'failure'`. Result: a red run with no notification and no auto-revert. This is a legitimate, unclaimed "deployment visibility" bug — arguably what AC1 *should* have been.
2. **The actuator-health follow-up** (`deferred-work.md:1420`): because `continue-on-error: true` now means a failed Slack/email notify no longer fails the run, a down channel is invisible until the next incident. A `HealthIndicator` for SMTP/Slack reachability was proposed as follow-up.

Neither is picked up, and AC1 as written adds nothing on top of what's already merged.

### F7 — Priority/severity is inconsistent with AC2's framing (MINOR)

The story is `Priority: medium`, yet AC2 is framed as an authorization defect ("abuse-report endpoints now have the exact same bug the main messaging endpoints fixed", "a parent … would be silently treated as if they were a player"). A live silent-authz-bypass in abuse reporting would not be medium. The mismatch is a tell that severity was inherited from the ledger without re-assessment — consistent with the whole story not having been re-validated against current code.

---

## False-positive guard (what I checked and found NOT to be a problem)

- **Is any part of AC1's "combine both" still missing?** No. Both halves (non-blocking notifies + `always()` marker) are present at HEAD.
- **Does the `Fail workflow` step's `if: always() && ...` correctly stay green on success?** Yes — it gates on `steps.smoke.outputs.result == 'fail'`, so it's a no-op on a passing deploy.
- **Is `MessagingReportService.verifyIsParty()` actually equivalent to the reference, or just superficially similar?** Equivalent branch-by-branch (COACH/PARENT/PLAYER/default), same exception type and error code. Verified against `MessagingService.java:367-395`.
- **Could deferred-16's fix have regressed since 2026-08-05?** `git log -- MessagingReportService.java` shows no functional change since `c7301e0`; the only later commit (`8a76652`, UAT.1) does not touch `verifyIsParty`.
- **Is the circular-dependency claim in the story's Dev Notes itself wrong?** The claim that a cycle exists is correct and matches the in-file comment; only the AC2 parenthetical (F5) muddies it.

---

## Recommendation

1. **Close this story as "already resolved"** (or withdraw it). Record in `deferred-work.md` that ledger line ~878 (`deploy-2-2` "Fail workflow unreachable") and lines ~377-381 (`MessagingReportService.verifyIsParty` hand-copy) are **closed** — by deferred-94 AC12 and deferred-16 AC4 respectively — so the next scoping pass doesn't regenerate this story.
2. **If a story is still wanted here,** re-scope it around the genuinely-open items from deferred-94's code review (`deferred-work.md:1412-1420`): the smoke-step-errors-vs-fails gap (F6.1) and the notification-channel `HealthIndicator` (F6.2). Those are real, unclaimed, and thematically identical ("deployment visibility").
3. **Process fix:** story creation must diff cited line numbers and "open" ledger items against `HEAD` (not against the last dated audit block), and must read any same-file "Deferred from: code review of <most-recent-story>" section before drawing scope.
