# Senior-Dev Audit: skillars-deferred-137-gdpr-erase-ceiling-payment-pool-fix

**Auditor stance:** independent, skeptical re-verification. Every file:line citation, precedent, test
count, and ledger reference below was re-opened and re-checked against the actual working tree in this
worktree (HEAD = `0d5897a8`, the story's own squash-merge commit) rather than trusted from the story's
own prose. No `mvn verify`/test execution was run (per this project's own standing "no local mvn verify"
convention) — test-behavior claims are verified by reading the test code and tracing it against the
production code's actual logic, not by executing the suite.

**Bottom line:** This is an unusually well-verified story. Of roughly 40 discrete, checkable claims
(commit citations, file:line references, config bounds, test names/counts, precedent code shapes,
ledger dates), **all but one held up exactly as stated.** The one confirmed defect is real and worth
fixing, but it is a documentation-integrity gap, not a code-correctness bug — the shipped code itself is
sound everywhere it was checked.

---

## Confirmed real issue

### The ledger's own closure record for AC2's pool sizing is stale — states `maximumPoolSize = 5`, the code ships `10`

- `_bmad-output/implementation-artifacts/deferred-work.md:1029` (the `D14` `[CLOSED by skillars-deferred-137 AC2 ...]` annotation) reads: *"backed by a new `paymentRequiresNewHikariConfig` bean (`maximumPoolSize = 5`, anchored to this codebase's own seeded `booking.batch.maxSize = 5` concurrency-scale convention since these three methods run sequentially within one batch listener invocation..."*
- The actual shipped code — `src/main/java/com/softropic/skillars/infrastructure/config/DataSourceConfig.java:161` (`paymentRequiresNewHikariConfig`) and `src/test/java/com/softropic/skillars/config/TestConfig.java:175` (its test-side mirror) — both set `maximumPoolSize = 10`, not `5`. The bean's own Javadoc (`DataSourceConfig.java:129-149`) explicitly explains this was sized at **2x** `booking.batch.maxSize`, specifically because the "sequential within one batch" reasoning the stale ledger text repeats **under-counts concurrent independent request threads** — i.e. the ledger text preserves the exact reasoning the implementation deliberately moved away from.
- The same stale `maximumPoolSize=5` value is also baked into `_bmad-output/implementation-artifacts/sprint-status.yaml:317`, inside the `skillars-deferred-137` `development_status` entry's "Prior note" section.
- The story's own `Review Findings` section (story file, "Payment Pool Sizing (re-triaged from Decision-Needed)") claims this exact discrepancy was caught and fixed: *"corrected the Completion Notes prose below to describe the actual, already-shipped `10` sizing and disclose the correction, rather than leaving stale text describing a value the code no longer has."* That correction reached the **story file's own** Completion Notes prose (verified — `AC2` Completion Notes at story line ~363 does correctly say `maximumPoolSize = 10`). It did **not** reach either of the two ledger files that this story's own Task 4 ("Ledger closeout... annotate both 2026-06-25/2026-08-24 `BookingPaymentPersistenceService` pool-pressure bullets `[CLOSED by skillars-deferred-137 AC2 — <summary>]`") was specifically responsible for updating.
- **Why this matters:** this project's whole story series treats `deferred-work.md` as the authoritative record of what was found, decided, and closed — multiple stories in this same ledger (128, 129, 135, 136) explicitly re-verify closure claims against `deferred-work.md`'s own text rather than against story files. A future engineer (or a future story's own "ledger-mining pass," the exact mechanism this story itself used to find its own AC2) reading this closure bullet would learn a materially wrong number for a real production connection-pool sizing decision — precisely the kind of ledger drift this codebase's own convention (own admission at `sprint-status.yaml`: *"deferred-work.md and sprint-status.yaml accumulate indefinitely... rather than being pruned or archived"*) is supposed to guard against via accurate-at-close-time annotations.
- **Not a false positive precaution taken:** I confirmed the sizing value is genuinely different in three independent ways — reading the actual `@Bean` methods in both `DataSourceConfig.java` and `TestConfig.java` (both `10`), reading the routing IT test's real captured Hikari-pool-exhaustion behavior (`BookingPaymentDataSourceRoutingIT.reserveCapture_dedicatedPoolExhausted_...` loops `payment.getMaximumPoolSize()` times to exhaust it — this is value-agnostic and doesn't independently prove the number, but is consistent with a pool sized `10`), and grepping the whole repo (`grep -o "maximumPoolSize = [0-9]*\|maximumPoolSize=[0-9]*"`) which surfaces exactly one `5` (the stale ledger bullet) against the `10` in both live config classes.
- **Suggested fix:** amend the `D14` bullet's `[CLOSED ...]` annotation in `deferred-work.md` (and the matching prose fragment in `sprint-status.yaml`'s `development_status` entry, if this project's convention treats that as authoritative too) to state `maximumPoolSize = 10`, and to drop or correct the "anchored to `booking.batch.maxSize = 5`... run sequentially" reasoning, since that's exactly the reasoning the real fix rejected in favor of "MULTIPLE independent request threads" sizing.

---

## Everything else re-verified accurate (no false positives introduced)

To avoid the trap the `mto-story-review` skill's own design note warns about (a review that says "zero
false positives" while missing real defects, or that flags something as fabricated after checking only
one source), every item below was independently re-opened at its cited location, not sampled from the
story's own self-description.

**Baseline and provenance**
- `Master is at c9a2691d` — confirmed: `c9a2691d` is exactly the merge commit for PR #231 (skillars-deferred-136). Confirmed via `git show --stat c9a2691d`.
- HEAD (`0d5897a8`) is exactly this story's own squash-merge commit ("skillars-deferred-137: GDPR erasure lock-wait ceiling + payment REQUIRES_NEW pool isolation (#233)"), confirmed via `git log`.

**AC1 — code correctness, traced statement-by-statement**
- `GdprErasureService.java`'s `deletePlayerDevelopmentDataInDedicatedPool` (current lines ~1125-1225) implements exactly the four-group batching (timeline/SLU=6, radar=3, performance-report/homework=3, blob-enqueue=M) the story and its own Completion Notes describe, each group re-issuing `set_config('lock_timeout', ...)` via `setStatementLockTimeout`, shrunk by `nextGroupLockTimeoutSecondsOrThrow` (`Math.min(lockTimeoutSeconds, remainingBudget.toSeconds()/statementsInGroup)`, floor-checked against `ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS.min()` = 2), and spent down by `spendDownLockBudget` using real elapsed time.
- `ConfigBounds.GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS` (`ConfigBounds.java:302-306`) is confirmed `min=2, max=120, default=5` exactly as cited.
- `RadarCompositeCalculationService.recalculateComposite`'s cumulative spend-down loop is at exactly the cited lines (`260-324`), confirmed by direct read — same per-skill halving shape, same `IllegalStateException` bail-out pattern, same `Duration.between`-based spend-down.
- **The dismissed "Integer Division Rounding" finding is correctly dismissed**: traced the exact code path — a near-zero `shrunk` value is always caught by the immediately-following `< min()` floor check before it could ever be used as a live `lock_timeout`, in both `GdprErasureService`'s new code and Radar's precedent.
- **The dismissed "Pathological lockTimeoutSeconds Bypasses Budget" finding is correctly dismissed** — `Math.min(lockTimeoutSeconds, ...)` genuinely discards the larger of the two arguments; an oversized `lockTimeoutSeconds` cannot bypass the budget shrink.
- **The "self field unguarded"/"Missing Null-Safety on Payment self Reference" dismissal's "wrong citation" claim is correct** — `BookingPaymentPersistenceService.java:69-71` is confirmed to be the `Counter` field declarations, not the `self` field (which is at `63-65`).
- **The "Radar Pattern Untested" deferred claim is confirmed accurate by direct search**: no test file anywhere under `src/test/java` (checked `RadarCompositeCalculatorTest.java`, `RadarCompositeCalculationServiceConcurrencyIT.java`, and a full-repo grep for `CUMULATIVE_LOCK_WAIT_BUDGET`/`cumulative`/`budget`/`IllegalStateException` in those files) exercises Radar's own cumulative-exhaustion bail-out path.
- **The "Radar's negative-elapsed clock-regression gap, inherited unfixed" claim is confirmed accurate**: `RadarCompositeCalculationService.java:317-324` still has no `isNegative()` guard on `elapsed` — only `GdprErasureService.spendDownLockBudget` (lines 1275-1281) got the new floor.
- **The "genuine test-fixture bug" claim (unstubbed `configService.getBoundedLong` defaulting to Mockito's `0L`) is confirmed**: `GdprErasureServiceTest.java:139` has exactly the described `lenient().when(configService.getBoundedLong(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(5L);` fix in its shared `@BeforeEach`.

**AC1 — test coverage, counted directly**
- `GdprErasureServiceTest.java`: 14 `@Test` methods (matches "14/14 pass" in the final Review Findings). Confirmed the specific new test names exist verbatim: `deletePlayerDevelopmentData_wellWithinCumulativeBudget_happyPath_...`, `..._bailsOutBeforeAnyStatement_...`, `..._bailsOutPartwayAfterGroups1Through3_...`, `spendDownLockBudget_negativeElapsedFromBackwardClockStep_flooredAtZero_...`.
- `GdprErasureIT.java`: 34 `@Test` methods (matches "all 34 GdprErasureIT... pass"). The new `erase_selfRegisteredPlayer_cumulativeLockWaitBudgetExhausted_bailsOutThenReDriveCompletesCleanly` test genuinely asserts real-Postgres state: seeds a real `player_timeline_events` row, shrinks the budget via `ReflectionTestUtils`, confirms the row survives untouched and the profile is not tombstoned after the bail-out, then restores the budget and confirms a re-drive both deletes the row and sets the tombstone. This is a real, not simulated, proof of the re-drivability claim.
- Traced the "partway bail-out" test's own arithmetic by hand: budget=20s, groups 1-3 use fixed divisors 6/3/3 (20/6=3, 20/3=6, both ≥ the 2s floor — pass), group 4 uses `M=15` mocked storage keys (20/15=1 < 2s floor — trips). This is exactly what the test's own inline comment claims, and the math checks out.

**AC2 — code correctness**
- `BookingPaymentPersistenceService.java`: confirmed all three methods (`reserveCapture`, `persistPaymentFailure`, `declineBatchBooking`) have the claimed thin-wrapper/`*Transactional`-suffixed-inner-method self-invocation split, each setting `RoutingDataSourceContext.set(DataSourceConfig.PAYMENT_REQUIRES_NEW_DATASOURCE_KEY)` before calling `self.xTransactional(...)` and clearing it in `finally`.
- Confirmed via grep that **no code anywhere calls the `*Transactional`-suffixed methods directly**, i.e. nothing can bypass the routing wrapper.
- Confirmed via grep that all three public methods are called **only** from `PaymentLifecycleService` (`onBookingAccepted`/`handleCreditBasedBooking`/`onBatchBookingAccepted`), consistent with the story's stated call-site inventory.
- `PaymentLifecycleService.onBookingAccepted`/`onBatchBookingAccepted` are confirmed `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` with **no** `@Async` annotation — confirms the "runs synchronously on the HTTP request thread, so a fast 5s pool-timeout keeps the accept responsive" reasoning in `DataSourceConfig.paymentRequiresNewHikariConfig`'s Javadoc is accurate, not asserted without basis.
- `DataSourceConfig.java`/`TestConfig.java`: confirmed `maximumPoolSize=10`, `minimumIdle=1`, `connectionTimeout=5_000` in both production and test beans, matching the story's Completion Notes (not the stale ledger text — see the confirmed issue above).
- `booking.batch.maxSize` is confirmed seeded to `5` in `src/main/resources/db/migration/V139__baseline_seed_data.sql:57` (the `ConfigBounds` compile-time fallback default is actually `0`, a fail-closed safety default, not the real production value — the story's "seeded" framing is accurate and not misleading).
- `RoutingDataSourceContext` (`infrastructure/config/RoutingDataSourceContext.java`) is confirmed a flat, non-nesting `ThreadLocal` with simple `set`/`get`/`clear` — confirms the "Routing Context Potential Leak" dismissal's premise. Traced the actual call graph for both GDPR and payment call chains and found no nested-different-key scenario that this flat design would mishandle.

**AC2 — test coverage, counted directly**
- `BookingPaymentDataSourceRoutingIT.java` exists (146 lines) with exactly the two tests described: a direct-routing proof via `HikariPoolMXBean` active-connection deltas, and an end-to-end `reserveCapture` dedicated-pool-exhaustion-fails-fast-under-15s proof (the pool's own `connectionTimeout=5_000`).
- Regression test counts were individually counted and match the story's claims **exactly**: `CaptureReservationIT` (8), `BatchPaymentIT` (3), `PaymentPendingSweeperIT` (9), `PaymentWebhookIdempotencyIT` (3), `BatchAcceptPaymentIT` (8), `BookingPaymentPersistenceServiceTest` (3), `CaptureReservationTest` (6).
- `ReflectionTestUtils.setField(service, "self", service)` confirmed present in both `BookingPaymentPersistenceServiceTest.java:67` and `CaptureReservationTest.java:71`, matching the claimed fix for the self-invocation-split regression in existing Mockito-based unit tests.

**Ledger closeout — dates and counts**
- Exactly 4 `[CLOSED by skillars-deferred-137 ...]` annotations exist in `deferred-work.md` (1 for AC1, 3 for AC2) — matches the story's own "found and annotated three (not two)" claim.
- The three AC2 bullets' actual dates, re-read directly from their section headers, are `2026-08-11` (twice — the `skillars-uat-3` code-review pass and its own patch-round deferral) and `2026-08-24` (from `skillars-deferred-58`'s code review) — confirms the story's own disclosed correction of its citation ("2026-06-25 D14, 2026-08-24" → actually "2026-08-11 x2, 2026-08-24").
- The AC1 `D1` bullet's citation drift (story cites `deferred-work.md:3259-3277`; current location is ~`2963-2988`) is a real line-number difference, but it is fully explained and non-suspicious: `git show c9a2691d:.../deferred-work.md` confirms the D1 bullet genuinely sat at lines 3259-3277 at the story's stated baseline commit; the shift is caused entirely by the intervening `c9550a46` ("docs: prune deferred-work.md") ledger-cleanup commit deleting unrelated earlier content. The story's own Dev Notes explicitly anticipate this ("Re-diff every cited line against whatever master actually looks like by the time implementation starts").
- `sprint-status.yaml` confirmed updated with a `last_updated: '2026-09-26'` entry and `status -> done` for this story key.

**Not investigated to the same depth (lower-priority, time-boxed):** the Context section's claims about the two `/txn-and-concurrency-audit` rounds coming back "completely clean" against 10 named classes across 6 modules (`BookingExpiryScheduler`, `AuthCleanupService`, etc.) were not independently re-audited class-by-class — verifying "an audit found nothing" is not practically falsifiable by re-reading the target classes in the time available, and doing so would not itself confirm or deny the audit was run as described. This is flagged as an explicit scope limit of this review, not as a finding either way.

---

## Verdict

One real, fixable issue (stale pool-sizing value in two ledger files' closure records — `deferred-work.md`
and `sprint-status.yaml`, both stating `maximumPoolSize = 5` against actually-shipped `10`). Everything
else checked — code logic, test behavior traced by hand, precedent citations, line numbers, test counts,
ledger dates, call-graph completeness — held up under independent re-verification. The implementation
itself (both ACs) is correct and matches its own documentation with that one exception.
