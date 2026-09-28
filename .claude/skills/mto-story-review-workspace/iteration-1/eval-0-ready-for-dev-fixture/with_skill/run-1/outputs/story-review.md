# Story Review: skillars-deferred-137-gdpr-erase-ceiling-payment-pool-fix

**Audit date:** 2026-09-28
**Real HEAD at audit time:** `0d5897a8` — `skillars-deferred-137: GDPR erasure lock-wait ceiling + payment REQUIRES_NEW pool isolation (#233)`
(confirmed via `git rev-parse --short HEAD` / `git log -1`, not taken from the story's own text)

**Critical context established before any citation was checked:** the story file under review is
staged as **pre-implementation** ("ready-for-dev") content, and its own Context section claims
`"Master is at c9a2691d"`. That claim is stale. The *real* current HEAD (`0d5897a8`) is the exact
commit that already merges this story's own implementation (PR #233) — `cumulativeLockWaitBudget`,
`CumulativeLockBudgetExhaustedException`, the four-group statement batching, and the
`PAYMENT_REQUIRES_NEW_DATASOURCE_KEY` pool all already exist in the working tree. Every citation
below was checked against the file **exactly as it exists at `0d5897a8`**, not against `c9a2691d` and
not against the story's own self-description. This produces a large, structurally-expected batch of
"DRIFTED" verdicts in Layer 1/3 below — that volume is a consequence of the fix having landed, not a
sign of a sloppy story. What matters is which claims are wrong *in substance*, not just wrong in line
number; both kinds are called out separately below.

A pre-existing `story-review.md` was already present in this worktree from a prior audit pass. It was
treated as one input to cross-check, never as ground truth, and is fully superseded by this report.

---

## 1. Citation verification (Layer 1)

One row per `path/File.java:NN-MM` citation in the story. MATCH = content at that exact location today
matches the claim. DRIFTED = the content has moved/changed; corrected current location given.

| # | Citation | Verdict | Evidence |
|---|---|---|---|
| 1 | `GdprErasureService.java` `deletePlayerDevelopmentDataInDedicatedPool` `:1040-1112`; Javadoc `:980-1024` | **DRIFTED** | Method body now at **:1125-1225**; its Javadoc now at **:1103-1124**. `:1040-1112`/`:980-1024` now fall inside a *different* method's (`deletePlayerDevelopmentData`) Javadoc/body. |
| 2 | Issues exactly ONE `set_config('lock_timeout',...)` call, `:1049-1051` | **DRIFTED (and substantively changed)** | `:1049-1051` now holds unrelated Javadoc prose. The real mechanism now issues the call via a new `setStatementLockTimeout()` helper (`:1228-1232`), called **up to four times** (once per statement group) at lines **1152, 1165, 1179, 1194** — not once. |
| 3 | Bounds ~12 statements at `:1065-1080` INDEPENDENTLY | **DRIFTED (and substantively changed)** | `:1065-1080` now discusses parameter-passing, unrelated. Statements are now bounded in **4 groups** (6/3/3/M statements), each sharing one shrunk `set_config` call — the opposite of "independently." |
| 4 | Blob-enqueue at `:1086` | **DRIFTED** | `:1086` now unrelated prose. Real blob-enqueue is now **Group 4**, call at **:1196**. |
| 5 | `gdprEraseLockBudget` field `:149`, `Duration.ofSeconds(10)` default, skillars-deferred-128 AC2 | **MATCH** | Line 149: `private volatile Duration gdprEraseLockBudget = Duration.ofSeconds(10);`; attribution comment confirmed nearby. |
| 6 | Sampled only BETWEEN children, `:637` | **DRIFTED** | `:637` now unrelated. The exact phrase exists verbatim at **line 1057** (cross-reference in a different method's Javadoc); the loop's own description is now at **line 629**. |
| 7 | `RadarCompositeCalculationService.recalculateComposite`'s spend-down loop, `:260-324` | **MATCH** | Lines 260-324 match: budget init (262), per-skill shrink/throw/spend-down loop (264-324). |
| 8 | `reserveCapture` `:91-92`, `@Transactional(REQUIRES_NEW)` | **DRIFTED (and substantively changed)** | `:91-92` now unrelated. `reserveCapture` is now a thin, non-`@Transactional` wrapper at **:115-123**; `@Transactional(REQUIRES_NEW)` now sits on the split-out `reserveCaptureTransactional` at **:125-126**. |
| 9 | `reserveCapture`'s Javadoc `:76-85` (REQUIRES_NEW / second pooled connection) | **DRIFTED** | `:76-85` now holds the start of a different part of the Javadoc. The REQUIRES_NEW/second-connection content is now at **:93-102**. |
| 10 | `persistPaymentFailure` `:246-247` | **DRIFTED (and substantively changed)** | `:246-247` now belongs to an unrelated method (`persistPaymentSuccess`). `persistPaymentFailure` is now a wrapper at **:285-295**; REQUIRES_NEW on split-out `persistPaymentFailureTransactional` at **:297-298**. |
| 11 | `declineBatchBooking` `:326-327` | **DRIFTED (and substantively changed)** | `:326-327` now falls inside an unrelated method (`confirmPackBatchPayment`). `declineBatchBooking` is now a wrapper at **:381-388**; REQUIRES_NEW on split-out `declineBatchBookingTransactional` at **:390-391**. |
| 12 | `DataSourceConfig.dataSource()` `:48-53`, single-entry `namedTargets` map | **DRIFTED (and substantively changed)** | `:48-53` now unrelated comment text. `dataSource()` now at **:60-68**, and the map now has **two entries** (`GDPR_ERASURE_DATASOURCE_KEY` + `PAYMENT_REQUIRES_NEW_DATASOURCE_KEY`) — independently confirmed by direct read. |
| 13 | `erase()`'s Javadoc `:224-227` (routing key before proxy advice) | **DRIFTED** | `:224-227` now discusses an unrelated design choice (pool-saturation pre-check mechanism). The actual routing-key-timing explanation is now an inline comment inside `erase()`'s body at **:237-241**. |

**Layer 1 total: 2 match, 11 drifted, 0 cannot-locate.** Independently re-confirmed by me directly for
items 2, 5, 8, 10, 11, 12 (grep + direct file reads); all matched the subagent's report exactly.

---

## 2. Ledger & precedent attribution (Layer 2)

Checked against `deferred-work.md` (3409 lines at current HEAD), source comments, and `git log`/`git blame`.

| # | Claim | Sources checked | Verdict |
|---|---|---|---|
| 1 | `ses-1-4` guard: stale block `:109-119` vs correction `:192-215`, fixed by skillars-deferred-111 AC6 | Ledger direct read, git log | **VERIFIED** (substance correct — real fix commit `4a3f218d` confirmed; cited line ranges are themselves stale due to a later ledger prune, matching the story's own disclosed "re-diff before implementing" caveat) |
| 2 | `deploy-1-5`/`deploy-1-3` rows `:364-365`→closed per `:1625-1638`, "AC6/AC7/AC9" | Ledger direct read | **WRONG** — both cited line ranges hold unrelated content; the real rows are at :1434-1446. Two of three close correctly (AC6, AC7); the third (`deploy-1-3`) was actually closed by **skillars-deferred-103 AC12**, not skillars-deferred-102 AC9. Low-severity: this is incidental "considered and excluded" background prose, not a claim about AC1/AC2 itself. |
| 3 | `AdminVideoService.deleteVideo` Def17 fixed, quota release Phase 2 outside `TransactionTemplate`, per deferred-64 AC5 comment | Code read (`AdminVideoService.java:45-100`), ledger grep, git log | **VERIFIED** — release (lines 76-96) strictly after `transactionTemplate.execute()` (line 74); comment reads "Deferred-64 AC5" verbatim. |
| 4 | `UploadSessionExpiryScheduler` Def22, AC-5 design (release OUTSIDE `@Transactional`) | Code read | **VERIFIED** — `quotaProvider.release()` (line 45) precedes the separate transactional block (lines 52-62); comment matches verbatim. |
| 5 | Ledger's own D1 from skillars-deferred-129 review, cited at `:3259-3277` | Ledger direct read | **VERIFIED** (content matches exactly — owner-decided 2026-09-22, `(12+M)×seconds` arithmetic — but actually at lines **2963-3000** now, not 3259-3277; same ledger-prune drift as item 1) |
| 6 | Owner-decided 2026-09-22 (AskUserQuestion): per-statement bound chosen over porting Radar's mechanism | Ledger read | **VERIFIED** — near-verbatim match at ledger line 2972-2974. |
| 7 | `gdprEraseLockBudget` attributed to skillars-deferred-128 AC2 | Code comment, git log | **VERIFIED** — comment at line 132 matches verbatim; skillars-deferred-128 is a real merged story (`ff49c148`). |
| 8 | `RoutingDataSource`/`RoutingDataSourceContext` "business-agnostic, reusable..." — "its own Javadoc" | Full read of both source files | **PARTIALLY WRONG** — the phrase does not actually appear in `RoutingDataSource.java`/`RoutingDataSourceContext.java`'s own Javadoc. Its real origin is skillars-deferred-136's **story markdown prose**, echoed (also inaccurately, as "that mechanism's own Javadoc") inside `DataSourceConfig.java`'s comments. This story **inherits** a pre-existing misattribution rather than introducing a new one; design intent/reuse itself is real. |
| 9 | "Flagged twice in old reviews (2026-06-25 D14, 2026-08-24)" | Ledger direct read (lines ~1005-1160) | **WRONG** — matching bullets are dated **2026-08-11** (×2, including D14) and 2026-08-24. No bullet anywhere is dated 2026-06-25. |
| 10 | 5 booking/messaging classes "already meticulously hardened by skillars-deferred-118, zero deferred residue" | Story 118's own file, git log, ledger grep | **VERIFIED** (minor wording looseness: 2 of the 5 classes were found already-correct reference patterns rather than code-modified, but the "zero open residue" claim holds for all 5) |
| 11 | 6 security/admin/filestorage/dev classes hardened by skillars-deferred-119/120/123 | git log per-file, ledger grep | **VERIFIED** — all six confirmed touched by the cited stories; no open residue found. |
| 12 | `PackSessionService.pausePack`'s self-invocation split, precedent for story 136 | Story 136's own file | **VERIFIED** — explicit "mirroring GdprErasureService.erase/eraseTransactional" language confirmed. |
| 13 | sprint-status.yaml's own claimed ledger-closeout self-correction of the D14 date | sprint-status.yaml, deferred-work.md | **VERIFIED** — sprint-status.yaml's text ("actual dates are 2026-08-11 (x2) and 2026-08-24") matches deferred-work.md's real dates exactly, cross-validating item 9. |

**Layer 2 total: 10 verified, 2 partially/fully wrong on peripheral attribution details (items 2, 8),
1 wrong on a factual date (item 9) — 13 items.** Items 2 and 8 are low-severity/inherited and do not
touch AC1/AC2's actual scope or correctness. Item 9 is real but is itself already caught and disclosed
by this story's own ledger-closeout note (see item 13) — i.e., the story's *draft* had the wrong date,
but the *implementation record* self-corrected it. This is disclosed transparently, not swept under.

---

## 3. Mechanistic claims (Layer 3)

Full method bodies read for each claim; checked against current HEAD, not the story's claimed baseline.

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | `deletePlayerDevelopmentDataInDedicatedPool` issues exactly ONE `set_config` call | **FALSE at current HEAD** | 4 calls now (one per statement group), via `setStatementLockTimeout()`. |
| 2 | Single `set_config` call bounds all statements INDEPENDENTLY | **FALSE at current HEAD** | Bounded in 4 groups sharing a shrinking cumulative budget, not independently. |
| 3 | "Real worst case `(12+M)×seconds` — not a method-level ceiling" | **STALE-BUT-WAS-TRUE-PRE-IMPLEMENTATION** | A method-level ceiling (`cumulativeLockWaitBudget` + `CumulativeLockBudgetExhaustedException`) now exists — this is precisely what AC1 shipped. |
| 4 | `GdprEventListener.onErasureRequested` is plain, non-`@Async`, `AFTER_COMMIT` | **TRUE** | `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`, no `@Async`. |
| 5 | `gdprEraseLockBudget` sampled only BETWEEN children; cannot interrupt an in-progress child; does not apply to `role==PLAYER` direct call site | **TRUE** | `deadlineNanos` checked at top of each loop iteration only (line 685), never mid-call; `role==PLAYER` branch in `eraseTransactional` never references `gdprEraseLockBudget`. |
| 6 | Radar's spend-down is `System.nanoTime()`-based monotonic, "immune to NTP clock steps" | **FALSE (independently confirmed by me directly)** | `RadarCompositeCalculationService.recalculateComposite` uses **`Instant.now()`** (`Instant skillStartedAt = Instant.now();` and `Duration.between(skillStartedAt, Instant.now())`) — wall-clock, not monotonic. The `System.nanoTime()`-based mechanism the story is describing actually belongs to a **different** method entirely: `GdprErasureService.eraseParentChildren`'s own PARENT-loop deadline (`long deadlineNanos = System.nanoTime() + ...`, confirmed by me directly at line ~677). The shipped code's own comment on `spendDownLockBudget` explicitly states Radar's own mechanism is "wall-clock, not monotonic" and has this gap "unguarded — not fixed here." This is a genuine method-confusion in the story's own text — exactly the failure mode this skill exists to catch. |
| 7 | `reserveCapture` carries `@Transactional(REQUIRES_NEW)` | **PARTIALLY TRUE / stale attribution** | Directionally right (second pooled connection), but at HEAD the annotation is on the split-out `reserveCaptureTransactional`, not on `reserveCapture` itself (now a thin wrapper). |
| 8 | `persistPaymentFailure`/`declineBatchBooking` are REQUIRES_NEW | **PARTIALLY TRUE / stale attribution** | Same wrapper/Transactional-sibling split as #7 for both methods. |
| 9 | `DataSourceConfig.dataSource()`'s `namedTargets` map has a single entry | **FALSE at current HEAD (independently confirmed by me)** | Two entries at HEAD: `GDPR_ERASURE_DATASOURCE_KEY` + `PAYMENT_REQUIRES_NEW_DATASOURCE_KEY`, added by this very story. |
| 10 | Re-drivability means "a mid-child bail-out is safe to leave partially applied" | **FALSE — and the shipped code explicitly refutes the story's own premise (independently confirmed by me)** | `deletePlayerDevelopmentDataInDedicatedPool`'s entire body (all 4 statement groups + tombstone save/flush) runs inside a single `requiresNewTemplate.executeWithoutResult(...)` transaction. A bail-out rolls back **everything** run so far in that child — nothing is ever "partially applied" within one child. The shipped method's own Javadoc states this directly: *"there is no partial-commit risk to reason about (unlike a naive reading of 'mid-child bail-out' might suggest)."* The story's own worry about statement-ordering dependencies among the 12 deletes was built on a false premise — partial application within a child was never possible once wrapped in one REQUIRES_NEW scope. |

**Layer 3 total (10 items): 2 TRUE, 2 PARTIALLY TRUE (stale method attribution, substance directionally
correct), 2 STALE-BUT-WAS-TRUE-PRE-IMPLEMENTATION (expected — this is what AC1/AC2 shipped), 4 FALSE.**
Of the 4 FALSE, items 1/2/9 are structural consequences of the fix having landed (expected). **Item 6
(Radar's clock mechanism) and item 10 (partial-commit reasoning) are substantive, non-drift errors in
the story's own reasoning** — independently confirmed by me by reading the full method bodies myself,
not merely trusting the subagent.

---

## 4. Corner cases / false assumptions / missed flows (Layer 4, survived Step 4b)

1. **Story's Fix instructions rest on the same false Radar-clock premise as §3 item 6.** AC1's "The
   fix" section explicitly instructs the implementer to "mirror that same monotonic-clock choice
   [`System.nanoTime()`]... rather than `Instant.now()`" — based on a false description of Radar's own
   code. The actual shipped implementation used `Instant.now()` with an explicit floor guard instead
   (correctly diverging from the story's own flawed instruction), and left Radar's own equivalent gap
   genuinely unguarded, exactly as `sprint-status.yaml`'s dev-record independently states. Had a future
   implementer followed the story's literal instruction to use `nanoTime()`, it would have been a
   pointless deviation from the actual precedent it claims to mirror.

2. **Real, unaddressed cross-pool connection-budget risk (confirmed independently by me).** This
   codebase has a documented, load-bearing convention: `application.yaml:181` sizes the primary pool at
   `maximum-pool-size: 25` specifically because "postgres puts a limit of 100 connections (so we can
   have up to 4 nodes, i.e. 25 for each)" — i.e., the primary pool alone already fully consumes the
   100-connection budget across 4 nodes with zero headroom. Independently verified pool sizes at HEAD:
   GDPR pool `maximumPoolSize=3` (`DataSourceConfig.java:109`), payment pool `maximumPoolSize=10`
   (`DataSourceConfig.java:161`). Per node: 25+3+10 = **38**; across 4 nodes: **152** — 52% over the
   documented 100-connection ceiling in a full-saturation scenario. Neither AC1's predecessor
   (deferred-136) nor this story's AC2 sizing rationale (`DataSourceConfig.java:129-150`) re-derives or
   even mentions the cluster-wide `4×25=100` invariant, despite AC2 explicitly drawing the "identical
   class of problem story 136 just solved" parallel. Unlikely to be hit in practice (admin-only GDPR
   traffic + fail-fast 5s payment timeout), but it is a genuine unexamined capacity assumption.

3. **Ledger citation for the D1 bullet is drifted** (story cites `:3259-3277`; real current location is
   `:2963-2989`/`:2963-3000` per Layer 2/Layer 4 cross-check) — same ledger-prune drift pattern as
   Layer 2 items 1 and 5. Low severity, already covered by the story's own "re-diff before
   implementing" disclosure.

4. **Minor, likely-intentional asymmetry not discussed by the story:** GDPR's REQUIRES_NEW acquisitions
   get a same-thread `HikariPoolMXBean`-based saturation pre-check (`GdprErasureService.java:921-949`)
   before attempting acquisition; the three payment methods have no equivalent pre-check, relying solely
   on the pool's own 5s `connectionTimeout`. Since AC2 explicitly analogizes to the GDPR fix, this
   asymmetry is worth a sentence of disclosure even if the shorter timeout is an adequate substitute.

**Clean checks (13, confirmed genuinely clean, not merely unexamined):** negative-elapsed clock-regression
floor guard present and tested; mid-child bail-out atomicity (see §3 item 10); Test Plan (b) partway-trip
test exists (`GdprErasureServiceTest.java:281-329`); Test Plan (c) re-drive IT exists
(`GdprErasureIT.java:1697`); config-floor/shrink-division math sound; both call sites (PARENT loop and
PLAYER-direct) route through the same budget-checked method; AC2 self-invocation split present and
correct for all three payment methods; `finally`-block context-clearing correct on exception; pool
sizing rationale is genuinely independently-derived, not copy-pasted from GDPR's; `RoutingDataSource`
confirmed genuinely business-agnostic in actual implementation (despite the Javadoc-attribution slip in
§2 item 8); `PackSessionService.pausePack` precedent genuinely exists; concurrency IT for AC2 genuinely
proves both routing and fail-fast-at-dedicated-timeout; no async/thread-handoff violation in the
payment listeners.

---

## 5. What did not survive re-verification

Nothing from the four layers was dropped outright — every finding above was re-checked against fresh
reads of the cited evidence (not the subagents' paraphrase alone), and the highest-stakes claims (the
transaction-boundary/partial-commit claim, the Radar clock-mechanism claim, and the cross-pool capacity
arithmetic) were independently re-derived by me directly from the source files rather than taken on the
subagents' word. Two items were **downgraded in severity** during this re-check rather than dropped:

- Layer 2 item 2 (`deploy-1-3` misattributed to AC9 instead of AC12) — real, but confined to incidental
  "considered and excluded" background prose, not to AC1/AC2's actual scope or fix. Not independently
  re-verified by me beyond the subagent's cited line range and quote.
- Layer 2 item 8 (RoutingDataSource "business-agnostic" phrase misattributed to code Javadoc) — real,
  but this story **inherits** the misattribution from skillars-deferred-136's own story prose rather
  than introducing it; the underlying design property (genuine business-agnosticism) checks out fine in
  the actual `RoutingDataSource.java` source.

The large volume of Layer 1/Layer 3 "DRIFTED"/"FALSE (at current HEAD)" verdicts is **not** treated as a
defect list — it is the direct, expected consequence of auditing a pre-implementation story file against
a HEAD that already contains its own shipped implementation. That distinction is preserved throughout
this report rather than collapsed into a single "N citations wrong" count.

---

## 6. Recommendation

This is a per-claim calibration, not a single blanket score:

- **Citations (Layer 1):** low confidence if read literally against current HEAD (11/13 drifted), but
  **high confidence that the drift is fully explained** (the story's own fix already shipped) rather than
  indicative of carelessness. A developer picking this story up for real implementation work would need
  to re-derive every line number from scratch regardless — which the story's own Dev Notes already
  anticipate ("re-diff every cited line... by the time implementation starts").
- **Ledger/precedent attributions (Layer 2):** high confidence overall (10/13 clean verifies), with two
  low-severity, peripheral attribution slips (one inherited, one original but tangential) and one
  factual date error that the project's own implementation record already caught and disclosed.
- **Mechanistic claims (Layer 3):** **mixed, and this is the most load-bearing finding of this audit.**
  The Radar clock-mechanism claim (item 6) and the partial-commit/re-drivability claim (item 10) are
  substantive errors in the story's own reasoning, independently re-confirmed by me from full method
  bodies — not artifacts of the pre/post-implementation gap. Both happened to be caught (and diverged
  from) during actual implementation, per `sprint-status.yaml`'s dev record, but the story **document**
  as staged still contains both false premises for any future reader relying on it as a design record.
- **Corner cases (Layer 4):** one real, independently-verified, unaddressed capacity-planning gap (cross-
  pool connection budget, finding 2) that neither this story nor its predecessor (deferred-136)
  discusses, plus three lower-severity items.

Overall: this story's **AC framing and precedent-mining are solid** (Layer 2's 10/13 clean rate, Layer
4's 13 clean checks), but it contains **two genuine mechanistic errors** or false assumptions about
existing code (Radar's clock choice; partial-commit risk) that a naive implementer following the story
literally could have acted on incorrectly, and it **leaves an unexamined cluster-wide connection-budget
risk** that a "reuse the GDPR pattern" AC should have re-derived rather than assumed away. Do not treat
this report's high citation-drift count as the headline risk — that part is fully explained and
expected. The Radar-clock and partial-commit claims are the parts worth a human's attention.
