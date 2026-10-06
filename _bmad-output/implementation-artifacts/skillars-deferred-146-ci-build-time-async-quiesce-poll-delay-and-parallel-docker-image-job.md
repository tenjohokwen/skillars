# Story Deferred-146: CI Build Time — Async-Quiesce Poll Delay and Parallel Docker-Image Job

Status: ready-for-dev

## Story

As the developer running this project's delivery loop,
I want the PR build to stop spending ~4 minutes sleeping inside a test-infrastructure poll and ~4 minutes rebuilding, serially, an artifact it has already built,
so that a story's CI round trip costs roughly ten minutes instead of nineteen and the "wait for CI" step of every cycle stops dominating delivery time.

## Why This Story Exists

Sourced from a build-time investigation on 2026-10-07 at the user's request — **not** from `deferred-work.md`, not from a code review deferral. The investigation profiled the last green PR build (run `37530194292`, PR #252, 18m39s wall clock) against HEAD `f789ce5b`. This story implements the two largest findings; three smaller ones are recorded in the **Deferred From This Investigation** section below rather than bundled, because they each need their own measurement and carry risk this story does not.

### Measured baseline — run `37530194292`, `build` job, 18m39s

Job-step timings from the GitHub jobs API; Maven phase timings from the step's own `build.log` markers (this is exactly why `pr-build.yml:58-60` forbids `-q`).

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

**Note for whoever reads `docs/testing/readme.md` next:** that document's headline per-test-reset figure, "99.7 ms mean over 814 invocations (~81 s total) on CI", is now stale by an order of magnitude. The same counter in the same run reports **14.0 ms mean over 1199 invocations, 16.8s total**. The database reset is no longer a cost worth optimising; the thing wrapped around it is. AC1.6 fixes the document.

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

`ExecutorShutdown`'s own javadoc (`src/main/java/com/softropic/skillars/infrastructure/threadpool/ExecutorShutdown.java:261`) records that there are **six** `ThreadPoolTaskExecutor` pools: `outboxDrainPool`, `sluRetryExecutor`, `reportExecutor`, `moderationTaskExecutor`, `threadPoolTaskExecutor`, `taskExecutor`. So the loop pays six poll delays per test method.

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

The Docker build depends on nothing `mvn verify` produces — the `Dockerfile` copies only `pom.xml`, `src/` and `.git/`, all of which come from `actions/checkout`. `ci.yml` already models this correctly, with `build-and-push` as a separate job (`ci.yml:88-93`); `pr-build.yml` is the odd one out. Moving the two steps into their own parallel job takes the full **4m03s** (build + scan) off the critical path for free.

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
   **Then** a unit test pins the behaviour: with a `ThreadPoolTaskExecutor` that is already idle, `quiesceAsyncExecutors` must complete in well under the 25ms a single poll delay would cost (assert `< 15ms` for six executors, which fails loudly on the pre-change code at ~190ms and passes at ~3ms, while leaving generous headroom for a slow CI runner)
   **And** the test must be a real red/green — run it against the unpatched method first and record in Completion Notes that it failed, with the observed duration

1d. **Given** `src/test/java/com/softropic/skillars/utils/ConcurrencyLockWaitSupport.java:95` has the same `Awaitility.await().pollInterval(25ms)` shape with no `pollDelay`
   **Then** it is **left alone**, deliberately: it runs once per contention assertion in a handful of concurrency tests (not once per test method per executor), its condition is genuinely expected to be false at first call, and a 25ms settle there is harmless. Recorded here so the next reader does not have to re-derive why only one of the two call sites was touched.

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
   **Then** no test's assertions, fixtures or expected outcomes change. The only production-code change in this story is **none**; the only `src/test` change is `DatabaseResetTestExecutionListener` plus its new unit test; the only other changes are `.github/workflows/pr-build.yml` and two documentation files (AC1.6, AC6).

6. **Given** `docs/testing/readme.md` currently states the per-test reset costs "99.7 ms mean over 814 invocations (~81 s total) on CI"
   **Then** that figure is corrected to the measured current one (**14.0 ms mean, 1199 invocations, 16.8s total**, run `37530194292`), with a one-line note that the earlier number predates later optimisation and should not be cited
   **And** the quiesce cost this story removes is recorded next to it, so the document's cost model matches the code again

## Tasks / Subtasks

- [ ] **Task 1 — Short-circuit the idle case and zero the poll delay** (AC: 1, 1d)
  - [ ] In `DatabaseResetTestExecutionListener.quiesceAsyncExecutors` (`:249`), extract the idle condition into a private `isQuiesced(ThreadPoolTaskExecutor)` helper so the short-circuit and the Awaitility predicate cannot drift apart.
  - [ ] `if (isQuiesced(executor)) { continue; }` before touching Awaitility.
  - [ ] Add `.pollDelay(Duration.ZERO)` to the surviving `Awaitility.await()` chain (`:252`), between `.atMost(...)` and `.pollInterval(...)`.
  - [ ] Extend the method's javadoc with a short "why the poll delay is explicitly zero" paragraph — Awaitility resolves an unset `pollDelay` to the fixed `pollInterval`, which cost ~190ms per test method across six pools; the bounded wait and the accepted residual are unchanged. Keep it to a few sentences; the existing javadoc is already long and the reasoning belongs in this story file.
  - [ ] Do **not** touch `ConcurrencyLockWaitSupport.java:95` (AC1d).

- [ ] **Task 2 — Instrument the quiesce** (AC: 1b)
  - [ ] Add `QUIESCE_COUNT`/`QUIESCE_NANOS` beside `RESET_COUNT`/`RESET_NANOS` (`:294-299`).
  - [ ] Measure around the whole `quiesceAsyncExecutors(ctx)` call at `:113`, i.e. the short-circuit path is included in the measurement.
  - [ ] Report it from the same every-25 block and the same shutdown hook as `recordCost` (`:301-320`), tagged `[deferred-146] async quiesce:`. Reuse the existing `HOOK_REGISTERED` hook rather than registering a second one.

- [ ] **Task 3 — Unit test for the idle fast path** (AC: 1c)
  - [ ] New test alongside the listener. Build six `ThreadPoolTaskExecutor` instances, `initialize()` them, register them in a stub `ApplicationContext` (or call the extracted helper directly if the method's visibility makes that cleaner — package-private is acceptable here, the listener is test infrastructure).
  - [ ] Assert the quiesce of six idle executors completes in `< 15ms`.
  - [ ] **Run it against the unpatched method first**, record the observed failure duration in Completion Notes, then apply Task 1 and re-run. Do not skip the red half.

- [ ] **Task 4 — Split the Docker image build into its own job** (AC: 2, 2b, 2c, 4)
  - [ ] Cut the `Build Docker image (no push)` (`:102-107`) and `Scan image for vulnerabilities` (`:109-117`) steps out of the `build` job.
  - [ ] Add a new top-level job (suggested key `docker-image`) with no `needs:`, `runs-on: ubuntu-latest`, `timeout-minutes: 20`, `permissions: contents: read`, the same pinned `actions/checkout`, then the two relocated steps verbatim.
  - [ ] Leave `build`'s remaining steps, its `timeout-minutes: 35`, the container sampler pair and the in-step context-count gate exactly as they are.
  - [ ] Add a comment on the new job recording why it has no `needs:` (the Dockerfile's inputs are `pom.xml`/`src/`/`.git/`, all from checkout — nothing `mvn verify` produces), and pointing at `ci.yml:88` as the existing precedent for a standalone image job. Note in the same comment that this trades a few wasted runner-minutes on a red PR for ~4 minutes off every green one.
  - [ ] Leave `ci.yml` alone entirely — it already has the right shape.

- [ ] **Task 5 — Measure and record** (AC: 3)
  - [ ] After CI runs on this story's PR, pull the job timings and the Maven phase markers and fill in every number AC3 lists, as measured values beside their baselines.
  - [ ] If the failsafe phase drops by less than 2m30s, investigate before closing the story.

- [ ] **Task 6 — Documentation** (AC: 6)
  - [ ] `docs/testing/readme.md`: correct the stale 99.7ms reset figure and add the quiesce cost beside it.
  - [ ] Same file: the "Known gaps" / cost-model prose should no longer imply the per-test reset is the dominant per-method cost, because it is not and was not.

## Dev Notes

### Where the numbers came from, so they can be re-derived

Everything in the Baseline table is reproducible from run `37530194292`:

```bash
# job + step timings
gh api repos/:owner/:repo/actions/runs/37530194292/jobs \
  --jq '.jobs[] | {name, started: .started_at, completed: .completed_at,
                   steps: [.steps[] | {n:.name, s:.started_at, c:.completed_at}]}'

# maven phase boundaries (this is what pr-build.yml:58-60's "no -q" rule protects)
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

4. **Postgres container tuning (unquantified).** `SharedContainers.Postgres` (`src/test/java/com/softropic/skillars/config/SharedContainers.java:31-40`) runs stock `postgres:17-alpine` with no durability tuning. `fsync=off`, `synchronous_commit=off`, `full_page_writes=off` and a tmpfs `PGDATA` are standard and safe for a throwaway test database, and would cut into the ~197s of real test work. Unquantified here because the share of that 197s that is actually disk-bound was not measured.

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

_To be completed by the dev._

Expected scope:
- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java` (modified)
- a new unit test for the idle fast path (added)
- `.github/workflows/pr-build.yml` (modified)
- `docs/testing/readme.md` (modified)

## Completion Notes

_To be completed by the dev._
