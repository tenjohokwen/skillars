# Story Deferred-146: CI Build Time — Async-Quiesce Poll Delay and Parallel Docker-Image Job

Status: review

> **PRE-IMPLEMENTATION REVIEW 2026-10-07** (`story-review.md`, four-layer audit against real HEAD `70f41a1e`). Every finding below was independently re-verified against the real source before being applied — not taken on the review's own say-so. Five accepted, all text corrections; **no acceptance criterion changed what gets built, and the two load-bearing technical claims (Awaitility's `pollDelay` resolution, which the review re-confirmed from decompiled `awaitility-4.3.0` bytecode, and the Docker build's independence from `mvn verify`) both survived unchanged.**
>
> - **D (accepted, real defect)** — AC5 cited a nonexistent "AC1.6" and claimed "two documentation files"; only `docs/testing/readme.md` is ever named. Both references corrected to AC6, and the file named once.
> - **C (accepted, real defect)** — AC1d's stated reason for leaving `ConcurrencyLockWaitSupport.java:95` alone was backwards. Re-read `:22-28` and `:92` directly: `recordRetries(...)` "only fires once the whole retry loop CONCLUDES" and the assertion is documented to run after both threads are joined, so its condition starts out **true**, exactly like AC1's. The decision stands; the rationale is now volume (6 call sites ≈ 150ms), and the old wrong reason is recorded as wrong so it cannot be re-derived.
> - **A (accepted, partly over-stated by the review)** — the sixth pool's bean name was wrong: `@Bean(name = "sendMailPool")` at `notification/config/AsyncConfig.java:54` overrides the `threadPoolTaskExecutor()` method name. Fixed, with the raw-`ThreadPoolExecutor` seventh entry (`storageUploadExecutor`) called out as deliberately out of scope. The review also graded the `ExecutorShutdown.java:261` citation "DRIFTED"; **that half is a false positive** — `:261` does literally say "the six `ThreadPoolTaskExecutor` pools", which is the count the story cited it for. It simply does not enumerate names, so `:77-83` is now cited alongside it.
> - **B (accepted)** — "`ci.yml` already models this correctly" over-claimed: `ci.yml:88`'s job carries `needs: [test, frontend-quality]` and is precedent for the job *split*, not for running ungated. Both the narrative and Task 4's comment instruction now say so explicitly.
> - **E (accepted)** — Task 1 extracted `isQuiesced(...)` "so the two cannot drift apart" but never told the dev to point `.until(...)` at it. Added.
> - **Citation drift, fixed:** the "no `-q`" comment is `pr-build.yml:49-51` (was cited `:58-60`); `SharedContainers.Postgres` is `:108-129` with the image at `:60` (was cited `:31-40`). Both substantive claims were already true; only the line ranges were wrong.
> - **Found by this pass, not by the review:** AC1c's `< 15ms` budget had no stated measurement boundary. `initialize()` spawns threads and is easily tens of ms on a loaded runner, so a dev timing it would get a flake unrelated to the assertion. AC1c now pins the timed region to the `quiesceAsyncExecutors` call alone and records why 15ms is safe (the idle path never reaches Awaitility, so the one-off ~45ms Awaitility class-load is not in scope).
> - **The review's one self-declared gap, now closed:** it could not re-pull run `37530194292` and took every timing on trust. Re-fetched from the jobs API this session: job 1119s (18m39s), `Build and test` 827s (13m47s), Docker build 208s (3m28s), Trivy 35s — build+scan 243s = **4m03s**. All baselines in this story confirmed.

## Story

As the developer running this project's delivery loop,
I want the PR build to stop spending ~4 minutes sleeping inside a test-infrastructure poll and ~4 minutes rebuilding, serially, an artifact it has already built,
so that a story's CI round trip costs roughly ten minutes instead of nineteen and the "wait for CI" step of every cycle stops dominating delivery time.

## Why This Story Exists

Sourced from a build-time investigation on 2026-10-07 at the user's request — **not** from `deferred-work.md`, not from a code review deferral. The investigation profiled the last green PR build (run `37530194292`, PR #252, 18m39s wall clock) against HEAD `f789ce5b`. This story implements the two largest findings; three smaller ones are recorded in the **Deferred From This Investigation** section below rather than bundled, because they each need their own measurement and carry risk this story does not.

### Measured baseline — run `37530194292`, `build` job, 18m39s

Job-step timings from the GitHub jobs API; Maven phase timings from the step's own `build.log` markers (this is exactly why `pr-build.yml:49-51` forbids `-q`).

| Segment | Time | Share |
|---|---|---|
| **Failsafe (integration tests), 1334 tests** | **11m38s** | 62% |
| **Docker image build** | **3m28s** | 19% |
| Surefire (unit tests), 1987 tests | 1m00s | 5% |
| Trivy scan | 0m35s | 3% |
| compile + testCompile | 0m33s | 3% |
| frontend (node install, npm install, quasar build) | 0m34s | 3% |
| setup, cache, report upload, post-steps | 0m51s | 5% |

The failsafe phase's own 11m38s reconciles as follows — the four lines sum to the measured wall clock, so nothing material is unaccounted for:

| Inside failsafe | Time | Share |
|---|---|---|
| **Awaitility poll delay in `DatabaseResetTestExecutionListener` (AC1)** | **~230s** | 33% |
| Spring context loads (44 loads, 3.8s mean) | 188s | 27% |
| Real test work | ~197s | 28% |
| JVM start, container start, suite shutdown | ~60s | 9% |

Two supporting figures, both from the same run: per-class `Time elapsed` across the 209 IT classes sums to 695.2s against a 698s measured phase, and per-method `time` across the 1334 testcases in the uploaded `failsafe-reports/*.xml` sums to 447s. The 248s difference is class-level cost (overwhelmingly context loading), which is why the per-method and per-class figures are kept separate above.

**Note for whoever reads `docs/testing/readme.md` next:** that document's headline per-test-reset figure, "99.7 ms mean over 814 invocations (~81 s total) on CI", is now stale by an order of magnitude. The same counter in the same run reports **14.0 ms mean over 1199 invocations, 16.8s total**. The database reset is no longer a cost worth optimising; the thing wrapped around it is. AC6 fixes the document.

### Finding 1 — six per-test-method sleeps that check a condition that is already true

`DatabaseResetTestExecutionListener.quiesceAsyncExecutors` (`src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java:249-264`) runs, in `beforeTestMethod`, once per `ThreadPoolTaskExecutor` bean in the context:

```java
Awaitility.await()
    .atMost(Duration.ofSeconds(30))
    .pollInterval(Duration.ofMillis(25))
    .until(() -> executor.getActiveCount() == 0
        && executor.getThreadPoolExecutor().getQueue().isEmpty());
```

Awaitility's `pollDelay` is **not** zero by default. When `pollInterval` is a fixed interval and `pollDelay` is left unset, Awaitility resolves `pollDelay` to that same fixed interval — so each call sleeps 25ms *before evaluating the condition for the first time*, even when the pool is already idle, which is the overwhelmingly common case.

`ExecutorShutdown`'s own javadoc states the count at `src/main/java/com/softropic/skillars/infrastructure/threadpool/ExecutorShutdown.java:261` ("the six `ThreadPoolTaskExecutor` pools") and enumerates them in its shutdown-budget table at `:77-83`: **`outboxDrainPool`, `sluRetryExecutor`, `sendMailPool`, `moderationTaskExecutor`, `taskExecutor`, `reportExecutor`**. So the loop pays six poll delays per test method.

Two naming traps, recorded so the next reader does not trip on them. The sixth pool's **registered bean name is `sendMailPool`**, set by `@Bean(name = "sendMailPool")` at `platform/notification/config/AsyncConfig.java:54` — the Java factory method beside it is called `threadPoolTaskExecutor()`, which is *not* the bean name. And the budget table at `:77-83` lists a seventh entry, `storageUploadExecutor`, which is a raw `ThreadPoolExecutor` rather than a `ThreadPoolTaskExecutor` (`ExecutorShutdown.java:105-106` says so explicitly) and is therefore **not** returned by `getBeansOfType(ThreadPoolTaskExecutor.class)` and not quiesced. Six, not seven. Neither detail changes the implementation — the loop resolves by runtime type, never by name — but both would cost time to anyone cross-checking this story against that table.

**Measured, not inferred.** Against the real `awaitility-4.3.0` jar resolved from this project's own `~/.m2`:

```
6 already-true awaits, as written today:         191–200 ms
6 already-true awaits, + .pollDelay(Duration.ZERO):  ~3 ms
```

1334 IT methods run, of which 121 are in `@WebMvcTest` slice contexts that contain no `ThreadPoolTaskExecutor` beans and therefore pay nothing (corroborated: those 16 slice classes cost 11s *in total*, less than 121 × 190ms would be on its own). **1213 × ~190ms ≈ 230 seconds.**

The cross-check holds: 1213 full-context methods account for 436s of the 447s of method time, i.e. a 359ms mean. Subtract the 190ms quiesce and the residual mean is 169ms, which is a plausible figure for a test that performs a database reset plus an HTTP round trip against a real Tomcat.

**The 25ms delay was never load-bearing.** `quiesceAsyncExecutors` exists (see its own extensive javadoc, `skillars-deferred-131` AC4) to drain `@Async` tasks dispatched by a *preceding* test method's already-committed transaction. Those tasks are enqueued synchronously at `submit()` time, so by the time the previous method has returned they are already visible in `getActiveCount()`/the queue. A fixed 25ms head start provides no guarantee against a task dispatched at t=26ms either, so removing it does not weaken a property the design relied on — it removes an accident of Awaitility's defaults. The bounded 30s `atMost` ceiling, the 25ms *interval* between subsequent polls, and the accepted-residual behaviour on `ConditionTimeoutException` are all unchanged.

### Finding 2 — the Docker image build is 3m28s of serial, duplicated work

`pr-build.yml` runs `Build Docker image (no push)` (`:102`) and `Scan image for vulnerabilities` (`:109`) as the **next steps of the same `build` job**, after `mvn -B verify` has finished. The `Dockerfile`'s builder stage then re-runs the entire Maven build from scratch: the run log shows a second `frontend:2.0.2:npx (npx quasar build)` and a second `spring-boot:3.5.16:repackage` at 21:10:51, duplicating work the same job finished twelve minutes earlier.

The Docker build depends on nothing `mvn verify` produces — the `Dockerfile` copies only `pom.xml`, `src/` and `.git/`, all of which come from `actions/checkout`. `ci.yml` already puts the image build in a job of its own (`build-and-push`, `ci.yml:88`); `pr-build.yml` is the odd one out in keeping it inline. **The precedent is for the job split, not for running it ungated:** `ci.yml`'s job carries `needs: [test, frontend-quality]` (`:93`), deliberately, because publishing an image to master is a real release and `skillars-deferred-92`'s code review tightened that gate on purpose. This story's new PR job has no `needs:` for a different reason, argued on its own merits below — do not read `ci.yml` as prior art for that half. Moving the two steps into their own parallel job takes the full **4m03s** (build + scan) off the critical path for free.

**Deliberately not in scope: making the Docker build consume the already-built jar.** That would also remove the duplicated work, not merely parallelise it, but it changes the `Dockerfile` that `ci.yml` and every production deploy share, and it would make the PR image a different artifact from the one `deploy.yml` ships. The wall-clock win is already captured by parallelising; the compute win is not worth coupling the two. Recorded in **Deferred From This Investigation** below.

## Acceptance Criteria

1. **Given** the integration suite runs
   **When** `DatabaseResetTestExecutionListener.beforeTestMethod` quiesces the async executors
   **Then** an executor that is **already idle** is detected without any sleep at all — a direct `getActiveCount() == 0 && queue.isEmpty()` check short-circuits before Awaitility is involved
   **And** an executor that is **not** idle still goes through the existing bounded poll, now with `.pollDelay(Duration.ZERO)` so the first re-check is immediate
   **And** `.atMost(Duration.ofSeconds(30))`, `.pollInterval(Duration.ofMillis(25))` and the `catch (ConditionTimeoutException)` "log and proceed anyway" residual are all **unchanged** — this AC changes when the condition is first evaluated, nothing about what it asserts or how long it is willing to wait

1b. **Given** the suite has run
   **Then** the quiesce's own cost is reported the same way the reset's already is — a `QUIESCE_COUNT`/`QUIESCE_NANOS` pair alongside the existing `RESET_COUNT`/`RESET_NANOS` (`:294-299`), printed by the same every-25-invocations block and the same shutdown hook (`:301-320`), tagged `[deferred-146] async quiesce:`
   **And** the number is measured around the whole `quiesceAsyncExecutors(ctx)` call, including the short-circuit path, so a future regression shows up as a number in the build log rather than needing to be rediscovered from first principles
   **Why this is in scope and not gold-plating:** the existing `[deferred-19] database reset:` counter deliberately starts its clock *after* quiescing (`:115-118` documents the reasoning), which is correct for what it measures but is exactly why this cost stayed invisible for a year. One counter per per-test-method phase closes that blind spot.

1c. **Given** the change is made
   **Then** a unit test pins the behaviour: with six already-idle `ThreadPoolTaskExecutor`s, `quiesceAsyncExecutors` must complete in well under the 25ms a single poll delay would cost — assert `< 15ms`, which fails loudly on the pre-change code at ~190ms and passes at ~3ms
   **And** the timed region covers the `quiesceAsyncExecutors` call **only**: construct and `initialize()` the executors before starting the clock, because that setup is not what the budget is about. *(Code review 2026-10-07: this AC originally justified that by saying `initialize()` "spawns threads". It does not — `prestartAllCoreThreads` defaults to false, so `ThreadPoolExecutor` creates workers lazily on first submit and the idle path submits nothing. The structural decision is right; the reason given for it was wrong, and is corrected here and in the test's own javadoc.)*
   **And** the 15ms budget is safe precisely because the idle path never reaches Awaitility — the one-off ~45ms class-load cost of Awaitility's first invocation (observed during this investigation's own measurement) is paid only on the non-idle path, which this test does not exercise. A test written to go *through* Awaitility would need a far looser bound and would not pin the thing worth pinning
   **And** the test must be a real red/green — run it against the unpatched method first and record in Completion Notes that it failed, with the observed duration

1d. **Given** `src/test/java/com/softropic/skillars/utils/ConcurrencyLockWaitSupport.java:95` has the same `Awaitility.await().pollInterval(25ms)` shape with no `pollDelay`
   **Then** it is **left alone**, deliberately, and **on volume alone**: `assertGenuineLockRetryOccurred` has 6 call sites across 5 concurrency IT classes, so the same 25ms costs ~150ms across the entire suite against the ~230s this story is chasing — four orders of magnitude apart. Not worth the diff.
   **And** the reason is *not* that its condition starts out false. It almost certainly starts out **true**, for the same reason AC1's does: the method's own javadoc (`:92`) says "Call this AFTER both the holder and the contender threads have been joined", and the class javadoc (`:22-28`) records that `PessimisticLockRetryer.recordRetries(...)` "only fires once the whole retry loop CONCLUDES", so by the time the assertion runs the counter has already been written and `Future.get()` has established happens-before with it. The bounded poll there is a hedge against the meter being recorded on whichever thread finishes last, not an expectation of a false first read. **Stated explicitly because an earlier draft of this AC had it backwards** — do not re-derive the wrong reason from the fact that it was left untouched.

2. **Given** a pull request is opened against `master`
   **When** `pr-build.yml` runs
   **Then** `Build Docker image (no push)` and `Scan image for vulnerabilities` have moved out of the `build` job into a **new job that declares no `needs:`**, so it starts at the same instant as `build` and `frontend-quality`
   **And** the new job performs its own `actions/checkout` (it no longer inherits the `build` job's workspace)
   **And** the image tag (`skillars-app:pr-${{ github.event.pull_request.number }}`), the `docker-build` composite action invocation with `push: 'false'` / `load: 'true'`, the Trivy action pin, `severity: CRITICAL,HIGH`, `exit-code: '1'` and `trivyignores: .trivyignore` are all carried over **byte-identically** — this AC relocates steps, it does not retune the scan
   **And** `permissions: contents: read` is declared on the new job, matching `build`

2b. **Given** the new job exists
   **Then** it carries `timeout-minutes: 20`, with a comment recording the measured basis: the step pair took 4m03s on run `37530194292`, and 20 leaves ~5× headroom for the degraded-shared-runner throughput `pr-build.yml:17-22` already documents
   **And** the `build` job's own `timeout-minutes: 35` is **left unchanged** — its comment block (`:14-22`) records a 2026-09-24 bump made specifically because four consecutive runs hit a 25m ceiling on degraded runners, and lowering it on the strength of a single fast run would re-create exactly the failure that comment describes. Revisit it only after several post-change runs have been observed; note that intent in the comment rather than acting on it now.

2c. **Given** the Trivy gate now lives in a job of its own
   **Then** the story's Completion Notes must state explicitly that `master` has **no required status checks configured** (verified 2026-10-07: `GET /repos/.../branches/master/protection` returns 404 "Branch not protected"; the only ruleset, `NoDirectPush` id `20583638`, enforces deletion/non-fast-forward/pull-request rules and lists no `required_status_checks`), so splitting the job cannot silently un-gate anything at the merge API level — **but** the human merge step in this project's delivery loop now has **three** job results to read instead of two, and a red Trivy scan is no longer visually attached to the `build` job. Call this out in the PR body so the next merge decision is made against all three.

3. **Given** AC1 and AC2 are both in place
   **When** the PR for this story builds
   **Then** the following are recorded in Completion Notes as **measured numbers taken from this story's own CI run**, not projections:
   - failsafe phase duration, from the `--- failsafe:3.5.6:integration-test` marker to the `--- failsafe:3.5.6:verify` marker in the job log (baseline: **11m38s**)
   - `build` job wall clock (baseline: **18m39s** for the old combined job)
   - new docker job wall clock (baseline: **4m03s** as two steps inside `build`)
   - overall workflow wall clock, i.e. the slowest job (baseline: **18m39s**)
   - final `[deferred-19] database reset:` line, and the new `[deferred-146] async quiesce:` line
   **And** if the failsafe phase does not drop by at least 2m30s, the discrepancy is investigated and written up rather than accepted — the 230s estimate is derived from an off-CI measurement of the awaitility jar plus a method count, and a large miss would mean one of those two inputs is wrong

4. **Given** the two gates in the `build` job
   **Then** both still run, unchanged, in the `build` job: `Start container sampler` + `Assert container ceiling (AC1)`, and the in-step `assert-context-count.sh build.log 45` block
   **And** neither gate's ceiling is touched by this story — this story changes no Spring context configuration and starts no container, so a movement in either number is a signal, not an expected side effect

5. **Given** the suite's behaviour
   **Then** no test's assertions, fixtures or expected outcomes change. The only production-code change in this story is **none**; the only `src/test` change is `DatabaseResetTestExecutionListener` plus its new unit test; the only other changes are `.github/workflows/pr-build.yml` and documentation. *(Code review 2026-10-07 widened the documentation set from one file to three: the review found that moving the Docker steps left `docs/deployment/baseline/github-build.md` describing pr-build as a single serial job and `docs/deployment/baseline/pr.md` citing a line range the step no longer occupies. Both are corrected. AC5's purpose — no production code, no changed test assertions — is unaffected; only its enumeration of documentation files grew.)*

6. **Given** `docs/testing/readme.md` currently states the per-test reset costs "99.7 ms mean over 814 invocations (~81 s total) on CI"
   **Then** that figure is corrected to the measured current one (**14.0 ms mean, 1199 invocations, 16.8s total**, run `37530194292`), with a one-line note that the earlier number predates later optimisation and should not be cited
   **And** the quiesce cost this story removes is recorded next to it, so the document's cost model matches the code again

## Tasks / Subtasks

- [x] **Task 1 — Short-circuit the idle case and zero the poll delay** (AC: 1, 1d)
  - [x] In `DatabaseResetTestExecutionListener.quiesceAsyncExecutors` (`:249`), extract the idle condition into a private `isQuiesced(ThreadPoolTaskExecutor)` helper so the short-circuit and the Awaitility predicate cannot drift apart.
  - [x] `if (isQuiesced(executor)) { continue; }` before touching Awaitility.
  - [x] Add `.pollDelay(Duration.ZERO)` to the surviving `Awaitility.await()` chain (`:252` starts the chain; `.atMost(...)` is `:253` and `.pollInterval(...)` is `:254` — insert between them).
  - [x] Point the surviving predicate at the same helper: `.until(() -> isQuiesced(executor))`. Leaving the condition inlined in the lambda would recreate the exact drift the extraction above exists to prevent.
  - [x] Extend the method's javadoc with a short "why the poll delay is explicitly zero" paragraph — Awaitility resolves an unset `pollDelay` to the fixed `pollInterval`, which cost ~190ms per test method across six pools; the bounded wait and the accepted residual are unchanged. Keep it to a few sentences; the existing javadoc is already long and the reasoning belongs in this story file.
  - [x] Do **not** touch `ConcurrencyLockWaitSupport.java:95` (AC1d).

- [x] **Task 2 — Instrument the quiesce** (AC: 1b)
  - [x] Add `QUIESCE_COUNT`/`QUIESCE_NANOS` beside `RESET_COUNT`/`RESET_NANOS` (`:294-299`).
  - [x] Measure around the whole `quiesceAsyncExecutors(ctx)` call at `:113`, i.e. the short-circuit path is included in the measurement.
  - [x] Report it from the same every-25 block and the same shutdown hook as `recordCost` (`:301-320`), tagged `[deferred-146] async quiesce:`. Reuse the existing `HOOK_REGISTERED` hook rather than registering a second one.

- [x] **Task 3 — Unit test for the idle fast path** (AC: 1c)
  - [x] New test alongside the listener. Build six `ThreadPoolTaskExecutor` instances, `initialize()` them, register them in a stub `ApplicationContext` (or call the extracted helper directly if the method's visibility makes that cleaner — package-private is acceptable here, the listener is test infrastructure).
  - [x] Assert the quiesce of six idle executors completes in `< 15ms`.
  - [x] **Run it against the unpatched method first**, record the observed failure duration in Completion Notes, then apply Task 1 and re-run. Do not skip the red half.

- [x] **Task 4 — Split the Docker image build into its own job** (AC: 2, 2b, 2c, 4)
  - [x] Cut the `Build Docker image (no push)` (`:102-107`) and `Scan image for vulnerabilities` (`:109-117`) steps out of the `build` job.
  - [x] Add a new top-level job (suggested key `docker-image`) with no `needs:`, `runs-on: ubuntu-latest`, `timeout-minutes: 20`, `permissions: contents: read`, the same pinned `actions/checkout`, then the two relocated steps verbatim.
  - [x] Leave `build`'s remaining steps, its `timeout-minutes: 35`, the container sampler pair and the in-step context-count gate exactly as they are.
  - [x] Add a comment on the new job recording why it has no `needs:` (the Dockerfile's inputs are `pom.xml`/`src/`/`.git/`, all from checkout — nothing `mvn verify` produces), and pointing at `ci.yml:88` as the existing precedent for *the job split only*. The comment must **not** present `ci.yml` as precedent for running ungated — that job is gated by `needs: [test, frontend-quality]` on purpose (`ci.yml:89-93`), because it publishes. Say instead that a PR image is built to be scanned and thrown away, never published, so gating it on tests buys nothing and costs ~4 minutes on every green PR; the trade is a few wasted runner-minutes on a red one.
  - [x] Leave `ci.yml` alone entirely — it already has the right shape.

- [x] **Task 5 — Measure and record** (AC: 3)
  - [x] After CI runs on this story's PR, pull the job timings and the Maven phase markers and fill in every number AC3 lists, as measured values beside their baselines.
  - [x] If the failsafe phase drops by less than 2m30s, investigate before closing the story.

- [x] **Task 6 — Documentation** (AC: 6)
  - [x] `docs/testing/readme.md`: correct the stale 99.7ms reset figure and add the quiesce cost beside it.
  - [x] Same file: the "Known gaps" / cost-model prose should no longer imply the per-test reset is the dominant per-method cost, because it is not and was not.

### Review Findings

_**Line citations in the `[Patch]` bullets below are as-found, i.e. anchored to the pre-patch tree (`9a5e0993`). Do not reuse them against current HEAD — the patch round's own javadoc expansion moved `isQuiesced` by ~70 lines. The `[Defer]` bullets, which describe live future work, have been re-anchored to post-patch line numbers and are marked as such.**_

_/bmad-code-review 2026-10-07 — four layers (Blind Hunter, Edge Case Hunter, Acceptance Auditor, plus orchestrator re-verification). 2 decision-needed (both resolved by the owner same-day, see inline), 9 patch, 4 deferred, 11 dismissed as false positives. Every finding below was independently re-verified against real source or by execution before being kept; the dismissals are recorded at the end so they are not re-raised._

- [x] [Review][Patch] *(Decision 1 — RESOLVED 2026-10-07 by the owner: **option (c)**, loop the whole pass until one full sweep finds every pool quiesced.)* **The idle short-circuit is blind to a task handed directly to a starting worker, and the removed 25ms poll delay was de-facto cover for that window** — `ThreadPoolExecutor.execute()` takes the `addWorker(command, true)` branch whenever `workerCount < corePoolSize`, which is the normal case on these mostly-idle pools since core threads are never prestarted. On that branch the task becomes the new `Worker`'s `firstTask` and **never enters `workQueue`**, so `getQueue().isEmpty()` is `true`; and `getActiveCount()` counts only workers whose AQS `isLocked()`, which `runWorker` sets *after* `Thread.start()` returns. Both halves of `isQuiesced` therefore report idle for the whole thread-start latency. The predicate itself is unchanged by this story — what changed is that the 25ms pollDelay incidentally covered that window and no longer does. **This directly contradicts this story's own Finding 1 claim that the delay "was never load-bearing"**, whose stated reason (tasks are "enqueued synchronously at submit() time, so already visible") is wrong on exactly this branch. Consequence if it fires: the `skillars-deferred-131` `player_profiles` ShareLock deadlock reopens, as a rare cross-test CI flake attributed to whichever test runs next. Options: (a) accept and correct the story's reasoning to an honest "narrowed, not closed" statement; (b) re-check once after a 1-2ms settle on the idle path only (~12ms/method, ~14s over the suite — gives back ~9% of the win); (c) loop the whole pass until one full sweep finds every pool quiesced, which also closes the separate cross-pool handoff ordering gap Blind Hunter raised (pool B cleared early, then pool A finishes and submits onto B). [`DatabaseResetTestExecutionListener.java:266,285-287`]
- [x] [Review][Patch] *(Decision 2 — RESOLVED 2026-10-07 by the owner: **option (c)**, keep the 15ms bound and add a non-timing assertion.)* **The test's 15ms wall-clock budget trades flake-resistance against mutation sensitivity** — two layers independently flagged 15ms as too tight for a shared CI runner (a single young-gen pause or noisy neighbour blows it), and Edge Case Hunter proposed 50ms. But orchestrator mutation testing found the interaction neither layer saw: reverting **only** the short-circuit while keeping `pollDelay(Duration.ZERO)` lands at roughly 48ms (Awaitility's one-off class-load, which the idle path otherwise never pays), so a 50ms bound would stop the test catching that specific regression. At 15ms the test catches both the 150ms full regression and the short-circuit-only regression, but can flake; at 50ms it catches only the full regression and cannot flake. Options: (a) keep 15ms; (b) raise to 50ms and accept the narrower mutation coverage; (c) keep 15ms and add a second, non-timing assertion that pins the short-circuit behaviourally (e.g. a Mockito spy asserting `getActiveCount()` is called exactly once per executor), which makes the timing assertion's looseness harmless. [`DatabaseResetTestExecutionListenerQuiesceTest.java:38`]

- [x] [Review][Patch] AC2b's required "revisit `build`'s timeout only after several post-change runs" note was never added — the `build` comment block has zero `+` lines [`.github/workflows/pr-build.yml` build-job comment block]
- [x] [Review][Patch] AC6 doc corrections, four in one edit: the "roughly 10× on macOS" multiplier was written against 99.7ms and silently re-anchored to 14.0ms, asserting an unmeasured ≈140ms; "stale by an order of magnitude" is actually 7.1×; the now-known current quiesce cost (0.1ms mean, 84ms total over 1199) is deferred to the build log instead of written down, though it was known by the time the story was marked `review`; and the 1199-invocation figure now sits beside a table reporting 905 integration tests with no era marker [`docs/testing/readme.md:134-143`]
- [x] [Review][Patch] Deployment baseline docs now misdescribe the PR gate's topology — `pr.md:54` cites `pr-build.yml:89-93` for a step that moved to the new job, and `github-build.md:66-74` still describes pr-build as one serial job whose steps 1-4 run in sequence. Verified directly. Note: fixing these widens AC5's enumerated scope by two files [`docs/deployment/baseline/pr.md:54`, `docs/deployment/baseline/github-build.md:66-74`]
- [x] [Review][Patch] The test javadoc's stated reason for its own structure is factually wrong — `ThreadPoolTaskExecutor.initialize()` does **not** spawn threads (`prestartAllCoreThreads` defaults false; `ThreadPoolExecutor` creates workers lazily on first submit, and this test submits nothing). Both Blind Hunter and Edge Case Hunter agree. The structural decision is right and should stay; only the justification is wrong. This wording originated in AC1c, so fix it in both places [`DatabaseResetTestExecutionListenerQuiesceTest.java:28-31`, and AC1c above]
- [x] [Review][Patch] AC3's prediction missed by ~73s and the gap is unreconciled — the story predicted ~230s (190ms × 1213 methods); the measured failsafe drop is 157.1s, implying ~131ms/method actual. Completion Notes record "clearing AC3's 2m30s floor — no investigation required" and stop there. Two candidate explanations worth recording: the 190ms figure was measured on macOS, whose timer granularity oversleeps more than a Linux runner (6 × ~22ms ≈ 132ms fits the observed number closely), and/or not every context carries all six pools. AC3's floor was cleared by only 7s, so the spirit of its investigation clause applies [story Completion Notes]
- [x] [Review][Patch] `%.1f ms mean` cannot resolve the quantity the new counter now tracks — the measured post-fix value is 84ms over 1199 calls (0.07ms), printed as `0.1 ms mean`. It still resolves the 190ms regression it exists to catch, but not smaller drift; printing microseconds costs nothing [`DatabaseResetTestExecutionListener.java:350-354,375-381`]
- [x] [Review][Patch] `sixIdleExecutors()` builds all six executors before the `try`, so an `initialize()` failure on iteration k>0 leaks the already-started ones into the shared surefire JVM [`DatabaseResetTestExecutionListenerQuiesceTest.java:29` vs `:39-41`]

- [x] [Review][Defer] `[deferred-19]` reset mean moved 14.0 → 15.8ms (+12.5%) between the baseline and this story's run on identical invocation counts (1199) — shown in the Completion Notes table but never remarked on; most likely runner variance, unexamined — deferred, pre-existing
- [x] [Review][Defer] *(citations re-anchored post-patch: call site `:305`, definition `:351-352`)* The `isQuiesced` pre-check sits outside the `try`, and `ThreadPoolTaskExecutor.getActiveCount()` returns 0 when uninitialized rather than throwing, so the `&&` does not protect the following `getThreadPoolExecutor()`, which `Assert.state`-throws. Unreachable today (all six pools are initialized `@Bean`s), but the `private` → package-private widening is new exposure [`DatabaseResetTestExecutionListener.java:266,286`] — deferred, pre-existing
- [x] [Review][Defer] `recordQuiesceCost(...)` is not in a `finally`, so a throwing quiesce — the case most worth seeing in the cost log — is silently absent from the counter [`DatabaseResetTestExecutionListener.java:114-116`, re-anchored post-patch] — deferred, pre-existing
- [x] [Review][Defer] `docker-image` is a new check name no ruleset references. AC2c correctly establishes that nothing is un-gated (master has no required status checks at all), so this is not a regression — but adding `docker-image` as a required status check is the operational follow-up that would make the Trivy gate mechanically blocking for the first time — deferred, pre-existing

**Post-patch review round (owner, 2026-10-07) — one real defect found in the patches themselves.**

- **The re-sweep's `catch` block returned from the whole method.** Decision 1's first implementation added `return;` to the `ConditionTimeoutException` handler to stop a wedged pool spinning across passes. That silently broke AC1's "the accepted `ConditionTimeoutException` residual is unchanged": the pre-story code logged and **continued to the next executor**, so all six were always visited, whereas the patched version abandoned every pool not yet reached and let the reset transaction open against them unchecked — reopening the `skillars-deferred-131` window for exactly the pools that had not been examined. Rare (needs a genuine 30s wedge) but a straight contradiction of a stated AC, and no test exercises a real timeout, so nothing would have caught it. **Fixed:** the catch logs and continues as before; a `timedOut` flag suppresses only the *next pass*, which caps the worst case at one pass (six pools × 30s — the ceiling this method always had) instead of letting `MAX_QUIESCE_PASSES` triple it. Behaviour *within* a pass is now byte-for-byte the pre-story behaviour.
- **Citation drift reintroduced by the patch round.** The deferred-work entries cited `:266,286` and `:113-115`, which were correct when the review wrote them but were invalidated by the patch round's own javadoc expansion (it moved `isQuiesced` ~70 lines down). Re-anchored to `:305` / `:351-352` and `:114-116`, and the `[Patch]` bullets above are now explicitly labelled as pre-patch "as-found" citations so they are not reused against HEAD.
- **Not re-executed by the owner's pass:** the mutation evidence behind dismissal (5). For the record, those runs were single-test-class invocations (`mvn -o test -Dtest=DatabaseResetTestExecutionListenerQuiesceTest`), which this story's own Dev Notes explicitly sanction — the standing "no local `mvn verify`" convention bars the full suite, not an isolated unit test. Three mutations were run: both halves of the fix reverted → red; the short-circuit alone reverted → red; `isQuiesced` forced to always-true → red on the new busy-path assertion.

**Dismissed as false positives (recorded so they are not re-raised).** Blind Hunter ran without project access by design, and five of its findings are artifacts of that: (1) "setup steps were dropped from the relocated job" — `docker/setup-buildx-action` lives inside `./.github/actions/docker-build` and travels with the steps; (2) "`permissions: contents: read` disarms a SARIF upload" — the Trivy step uses `format: table` and there is no `upload-sarif` anywhere under `.github/`; (3) "steps after the scan were silently relocated" — the scan was the last step of `build`; (4) "the bare checkout drops options / changes `fetch-depth` for the `.git` copy" — all three checkouts in the file are byte-identical bare pins, unchanged from master; (5) "the test would still pass with only the short-circuit removed" — **disproven by execution**: that exact mutation fails (`Tests run: 1, Failures: 1`). Also dismissed: (6) "run `37530194292` must have contained the new counters" — that is the pre-change baseline run, not this story's; (7) non-atomic counter pair and locale-dependent `%.1f` — pre-existing patterns faithfully mirrored from the reset counter, not introduced here; (8) the required-status-check concern as a *blocking* claim, superseded by the deferred item above; (9) "AC1b's periodic block was duplicated rather than shared" — the Acceptance Auditor's own verdict is that the intent is met and there is no defect; (10) "AC5 scope breach from `story-review.md` / `sprint-status.yaml`" — BMAD process artifacts, excluded from File Lists by project convention; (11) "the `[deferred-146]` line may never print on a run with fewer than 25 IT methods" — the shutdown hook prints it regardless, and this is the same property the existing reset counter has and already documents.


## Dev Notes

### Where the numbers came from, so they can be re-derived

Everything in the Baseline table is reproducible from run `37530194292`:

```bash
# job + step timings
gh api repos/:owner/:repo/actions/runs/37530194292/jobs \
  --jq '.jobs[] | {name, started: .started_at, completed: .completed_at,
                   steps: [.steps[] | {n:.name, s:.started_at, c:.completed_at}]}'

# maven phase boundaries (this is what pr-build.yml:49-51's "no -q" rule protects)
gh run view 37530194292 --log | grep -E 'INFO\] --- ' | grep -v '#19'

# per-class timings
gh run view 37530194292 --log \
  | grep -oE 'Time elapsed: [0-9.]+ s(ec)? -- in [A-Za-z0-9_.]+'

# per-method timings
gh run download 37530194292 -n test-reports-pr-252   # failsafe-reports/*.xml

# the two counters
gh run view 37530194292 --log | grep -oE '\[deferred-19\] database reset: .*'
gh run view 37530194292 --log | grep -oE 'missCount = [0-9]+' | tail -1
```

The awaitility figure was measured directly against `~/.m2/repository/org/awaitility/awaitility/4.3.0/awaitility-4.3.0.jar` with a six-iteration loop over an always-true condition, with and without `.pollDelay(Duration.ZERO)`. Re-run it if the awaitility version ever changes; the default-resolution rule this story depends on is an implementation detail of `ConditionFactory`, not a documented API contract.

### Standing conventions that apply

- **No local `mvn verify`.** GitHub CI is the sole full-verification gate. Task 3's unit test is runnable in isolation and should be; the suite-wide effect in AC3 is measured from CI and only from CI.
- The `#19`-prefixed Maven output in the run log is the Docker build stage, not the main build. Filter it out before reading phase markers, or every timing will be wrong by a factor of two.

### What this story is *not*

It does not touch Spring context configuration, so `assert-context-count.sh`'s ceiling of 45 should not move. It does not start or stop a container, so the container-sampler gate should not move. It changes no production code. If either gate fires on this story's PR, something unexpected happened and it should be investigated rather than absorbed by bumping a ceiling.

## Deferred From This Investigation

Recorded here rather than bundled, each with its own measured size, so a future pass does not have to re-profile the build. Add to `deferred-work.md` when this story is closed.

1. **Raise `spring.test.context.cache.maxSize` from Spring's default 32 (~35s).** The run reports `size = 32, maxSize = 32` with `missCount = 44`, and static analysis of the IT hierarchy finds **40 distinct context signatures** — so roughly 8 contexts are evicted and rebuilt, each costing a ~4.6s load plus the teardown of a Tomcat, a Hikari pool and a Quartz scheduler. `assert-context-count.sh`'s own comment block already reasons about the cache being "exactly full" but responds by tightening the gate rather than enlarging the cache. **Why it is not in this story:** the trade is memory — 40 live contexts instead of 32, against the `-Xmx4g` the failsafe `argLine` pins and the 427 live threads a prior thread dump measured — and that cannot be judged without the GC log, which `pr-build.yml` writes to `target/failsafe-gc.log` but **does not upload**. Prerequisite: add it to the `Upload test reports` artifact paths, take a baseline, then change `maxSize`.

2. **Six "prolonged contention" tests wait out a sleep they no longer need (~22s).** The six slowest IT methods are all ~8.15s and all the same shape (`DrillUploadServiceConcurrencyIT.initiateUpload_prolongedContention_...` and five siblings): a holder thread locks a row and `Thread.sleep(8000)`, the assertion that proves bounded failure (`assertThat(elapsedMillis).isLessThan(4500)`) is satisfied at ~4.5s, and then `holder.get(15, TimeUnit.SECONDS)` blocks for the remaining ~3.5s. Replacing the fixed sleep with a latch released once the contender has failed ends the hold immediately; the `< 4500` bound that actually proves the property is untouched. `GdprErasureIT` has two 6.2s methods on the same pattern. **Why it is not in this story:** it edits six concurrency tests whose timing windows were tuned deliberately, which deserves its own review pass rather than riding along with a workflow change.

3. **Collapse the remaining single-class context forks (~30s).** Of the 40 signatures, 13 are `@WebMvcTest` slices costing 11s combined and not worth touching. The real candidates are property-only forks that differ trivially: `VideoSubscriptionLifecycleListenerIT` (`outbox_max_attempts=2`) against `SimultaneousExpiryIT`/`YearlyExemptionRenewalIT` (`=3`) — three classes, two contexts, one integer apart; `WebhookPipelineIT` (`webhook.max-attempts=2`); `PlaybackRevocationIT` (`revocation-window-hours=24`, worth checking whether that is already the default); `SmtpTransportBootIT` (four properties, 15.3s for a single test). ~6 merges × 4.6s. **Note for whoever picks this up:** `docs/testing/readme.md`'s "Why not the ≤ 10 the story targeted" section is correct and still binding — hoisting `QuotaService`/`VideoLifecycleService`/`ModerationOrchestrationService` onto `BaseVideoIT` would replace the system under test in five classes that would then keep passing while asserting nothing. The property-only forks above are a different and safe class of merge.

4. **Postgres container tuning (unquantified).** `SharedContainers.Postgres` (`src/test/java/com/softropic/skillars/config/SharedContainers.java:108-129`, image pinned at `:60`) runs stock `postgres:17-alpine` with no durability tuning. `fsync=off`, `synchronous_commit=off`, `full_page_writes=off` and a tmpfs `PGDATA` are standard and safe for a throwaway test database, and would cut into the ~197s of real test work. Unquantified here because the share of that 197s that is actually disk-bound was not measured.

5. **Shard the integration suite across parallel CI jobs (the structural lever).** 209 IT classes, 695s, single fork, single thread, on a 4-vCPU runner. Balanced per-class timings exist (see the Dev Notes commands) and 3 shards would put the suite under 3 minutes each. **The cost is the gates:** the container-ceiling gate survives per-shard unchanged, but the `missCount` gate does not — shared contexts reload in every shard, so the summed count necessarily exceeds any single-run ceiling, and the gate would have to be replaced with a static signature count (deterministic and arguably a better gate, but a real piece of work). Hold in reserve: it is only worth doing if items 1–4 plus this story do not get the build where it needs to be.

## Projected Outcome

Projection, to be replaced by AC3's measured numbers:

| | Baseline | After this story |
|---|---|---|
| failsafe phase | 11m38s | ~7m50s |
| `build` job | 18m39s | ~10m20s |
| new `docker-image` job (parallel) | — | ~4m30s |
| **workflow wall clock** | **18m39s** | **~10m20s** |

## File List

- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java` (modified) — Task 1 + Task 2
- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListenerQuiesceTest.java` (added) — Task 3
- `.github/workflows/pr-build.yml` (modified) — Task 4
- `docs/testing/readme.md` (modified) — Task 6
- `docs/deployment/baseline/github-build.md` (modified) — code review 2026-10-07, AC5 scope note
- `docs/deployment/baseline/pr.md` (modified) — code review 2026-10-07, AC5 scope note

## Completion Notes

**Task 3 red/green (AC1c):** `DatabaseResetTestExecutionListenerQuiesceTest` run against the
unpatched method (Awaitility-only path, no short-circuit, no `pollDelay(ZERO)`) failed as
expected: `Expecting actual: 220L to be less than: 15L` — consistent with the story's own
measurement (6 already-true awaits ≈ 190–200ms, plus the one-off ~45ms Awaitility class-load this
unpatched path still pays). Re-run against the patched method (Task 1 applied): green, well under
the 15ms budget.

**Task 5 (AC3) — measured from this story's own PR #253, run `37574001244`** (commit `5f75dec8`):

| | Baseline (run `37530194292`) | Measured (run `37574001244`) |
|---|---|---|
| failsafe phase duration (`integration-test` marker → `verify` marker) | 11m38s (698s) | **9m01s (541.2s)** |
| `build` job wall clock | 18m39s (old combined job) | **11m36s** |
| new `docker-image` job wall clock | 4m03s (two steps inside `build`) | **3m17s** total (checkout+build+scan); build+scan steps alone: 2m52s |
| overall workflow wall clock (slowest job) | 18m39s | **11m36s** (`build`) |
| `[deferred-19] database reset:` (final) | 14.0 ms mean, 1199 invocations, 16.8s total (prior run) | **1199 invocations, 18898 ms total, 15.8 ms mean** |
| `[deferred-146] async quiesce:` (final, new) | — (not instrumented before this story) | **1199 invocations, 84 ms total, 0.1 ms mean** |

Failsafe dropped by **2m37s** (156.8s), clearing AC3's 2m30s (150s) floor.

**Reconciliation of the 73s shortfall against the prediction (added by code review 2026-10-07).**
AC3's investigation clause did not formally trigger — the drop cleared the floor, by 7s — but it
cleared it narrowly against a predicted **230s**, and AC3 names the two inputs that produce that
number, so the gap is reconciled here rather than left standing. Measured: 157.1s saved over 1199
quiesce invocations = **~131 ms per method**, against the predicted 190 ms. The 190 ms came from
an off-CI measurement of six already-true awaits against the awaitility-4.3.0 jar **on macOS**,
and 6 × 25 ms = 150 ms is the theoretical floor, so a CI figure *below* the floor rules out "fewer
pools than expected" as the whole story and points at timer granularity: macOS oversleeps a 25 ms
park substantially more than a Linux runner does, and 6 × ~22 ms ≈ 132 ms fits the observed number
almost exactly. A secondary contributor is that not every context carries all six pools — the
sliced `@SpringBootTest` classes (`PropertiesFeatureToggleServiceIT`, `RateLimitingAspectIT`) carry
none. Neither was re-measured on CI; the new `[deferred-146]` counter makes the question moot going
forward, since the real per-method cost is now logged every run instead of being modelled. **The
honest conclusion is that the 190 ms input was a macOS-biased overestimate, and the story's
Finding 1 arithmetic should be read as an order-of-magnitude estimate, not a prediction that
landed.** The quiesce counter confirms the fix directly: **0.1ms mean per test method**, down
from the ~190ms/method the pre-change Awaitility path paid (the counter did not exist before this
story, so there is no prior-run figure to diff against; the before/after comparison for the
quiesce itself is Finding 1's own off-CI jar measurement, not a counter).

AC4's two gates, unaffected by this story's changes, both still passed on this run: Spring
context count `missCount = 44` (ceiling 45, unchanged) and the container-ceiling sampler (peak 1
postgres/redis, 2 seaweedfs — within its documented ceiling). 1334 failsafe tests, 0 failures, 0
errors, 4 skipped — no test assertions changed, consistent with AC5.

Docker-image job genuinely ran in parallel with `build`/`frontend-quality` (all three started at
`04:58:12Z` per the jobs API), confirming AC2's "starts at the same instant" requirement — not
just a job-definition claim.

**AC2c.** `master` has **no required status checks configured**, re-verified 2026-10-07 at the
time of this PR: `GET /repos/tenjohokwen/skillars/branches/master/protection` returns `404
"Branch not protected"`; the only ruleset, `NoDirectPush` (id `20583638`), enforces only
`deletion`, `non_fast_forward` and `pull_request` rule types — no `required_status_checks` type
is present. Splitting the Docker/Trivy steps into their own `docker-image` job therefore cannot
silently un-gate anything at the merge-API level — there was no API-level gate on them to begin
with. What changes is the human merge step: PR #253 now shows **three** job results
(`build`, `docker-image`, `frontend-quality`) instead of two, and a red Trivy scan is no longer
visually attached to `build`. Called out in the PR body (#253) so the next merge decision is
made against all three, not two.
