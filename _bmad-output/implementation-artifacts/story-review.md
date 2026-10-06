# Story Review: skillars-deferred-146 — CI Build Time: Async-Quiesce Poll Delay and Parallel Docker-Image Job

**Audited:** 2026-10-07
**Story file:** `_bmad-output/implementation-artifacts/skillars-deferred-146-ci-build-time-async-quiesce-poll-delay-and-parallel-docker-image-job.md`
**Status at audit time:** `ready-for-dev`
**Real HEAD at audit time:** `70f41a1e` ("Story Deferred-146: CI build time — async-quiesce poll delay and parallel Docker-image job") — this commit only *adds the story file itself* to `sprint-status.yaml` and the artifacts directory; no implementation exists yet, so every citation below was checked against the actual pre-implementation source tree, not any SHA the story describes as its baseline (`f789ce5b`, an ancestor of HEAD).

All citations, ledger/precedent attributions, and mechanistic claims were independently re-read against real files (and, in two cases, decompiled bytecode and a live `gh api` call) rather than trusted from the story's own framing. Four parallel verification passes ran (citation check, ledger/precedent check, mechanistic-claim check, corner-case/missed-flow hunt), followed by my own adversarial re-check of every finding against the cited evidence before anything below was kept.

---

## 1. Citation verification

| # | Citation | Verdict | Evidence |
|---|---|---|---|
| 1 | `DatabaseResetTestExecutionListener.java:249-264` (quiesce method + Awaitility shape) | **MATCH** | Lines 249-256 contain `quiesceAsyncExecutors`, exact `.atMost(30s).pollInterval(25ms).until(...)` chain as quoted in the story. |
| 2 | `ExecutorShutdown.java:261` (claimed to list six bean names) | **DRIFTED** | Line 261 is a sentence about `GracefulShutdownTaskExecutor`'s shutdown escalation, not a name enumeration. The real budget table listing all seven pools (six `ThreadPoolTaskExecutor` + one raw `ThreadPoolExecutor`) is at lines 77-83. See Finding A below — one of the six names in the story's own text is also wrong. |
| 3 | `DatabaseResetTestExecutionListener.java:294-299` (`RESET_COUNT`/`RESET_NANOS`) | **MATCH** | `AtomicLong RESET_COUNT`, `AtomicLong RESET_NANOS`, `AtomicBoolean HOOK_REGISTERED` declared exactly there. |
| 4 | Same file, `:301-320` (every-25 block + shutdown hook calling `recordCost`) | **MATCH**, minor framing nuance | Lines 301-320 are `recordCost`'s body and the hook it registers. The hook doesn't literally re-invoke `recordCost` — it inlines equivalent reporting logic using the same `RESET_COUNT`/`RESET_NANOS` fields. Content and location are otherwise exactly as described. |
| 5 | Same file, `:115-118` (reset clock starts after quiesce, documented) | **MATCH** | Quoted directly: "measured from AFTER quiescing, not before... would make the reported metric mostly measure unrelated async-pool drain time." |
| 6 | Same file, `:113` (`quiesceAsyncExecutors(ctx)` call site) | **MATCH** | `quiesceAsyncExecutors(ctx);` is exactly there. |
| 7 | Same file, `:252` ("between `.atMost(...)` and `.pollInterval(...)`") | **Off-by-one, non-blocking** | Line 252 is `Awaitility.await()` itself; `.atMost(...)` is 253, `.pollInterval(...)` is 254. The cited line correctly marks where the chain *starts*; the actual insertion point for `.pollDelay(Duration.ZERO)` is between 253 and 254. A developer reading the real code will find this immediately — kept as a minor citation imprecision, not a defect. |
| 8 | `ConcurrencyLockWaitSupport.java:95` (same Awaitility shape, no pollDelay) | **MATCH** | Lines 95-98 confirmed: `.atMost(5s).pollInterval(25ms).until(...)`, no `.pollDelay(...)` anywhere in the block. |
| 9 | `pr-build.yml:58-60` ("no `-q`" reasoning) | **DRIFTED** | Lines 58-60 are unrelated `set +e` commentary. The actual "No -q: it suppresses phase markers..." comment is at lines 49-51. Content exists, line range is wrong by ~9 lines. |
| 10 | `pr-build.yml:102-107` (Docker build step) | **MATCH** | Exact step, `push: 'false'`, `load: 'true'`, tag templated from PR number. |
| 11 | `pr-build.yml:109-117` (Trivy scan step) | **MATCH** | Exact action pin, `severity: CRITICAL,HIGH`, `exit-code: '1'`, `trivyignores: .trivyignore` all present verbatim. |
| 12 | `pr-build.yml:17-22` (degraded-runner throughput note) | **MATCH** | Confirmed verbatim. |
| 13 | `pr-build.yml:14-22` (2026-09-24 bump, 4 runs hit 25m ceiling) | **MATCH** | Confirmed verbatim, including the specific PR (#228) and run-duration figures. |
| 14 | `ci.yml:88-93` (claimed precedent for "a new job with no `needs:`, starts at the same instant") | **Real nuance — see Finding B** | `build-and-push` at line 88 IS a separate top-level job (supporting the narrower claim "`ci.yml` already models this correctly... as a separate job"), but it carries `needs: [test, frontend-quality]` (line 93) — it is gated, not parallel-from-the-start. It is precedent for "lives in its own job," not for "no `needs:`." |
| 15 | Context-count gate, `assert-context-count.sh build.log 45` | **MATCH** | `pr-build.yml:78`, ceiling 45, invocation shape exact; comment at :69 attributes the ceiling to skillars-deferred-128. |
| 16 | `SharedContainers.java:31-40` (stock postgres:17-alpine, no durability tuning) | **Citation mis-ranged, substance true** | Lines 31-40 are prose about container-vs-bean lifecycle, not the `Postgres` class (actually at lines 108-129). The underlying factual claim — stock image, no `fsync=off`/`synchronous_commit=off`/`full_page_writes=off`/tmpfs — is independently confirmed true at lines 60 and 108-129. |
| 17 | `docs/testing/readme.md` stale figure + "Why not the ≤ 10" section | **MATCH** | "99.7 ms mean over 814 invocations (~81 s total) on CI" confirmed at line 134; the ≤10 section confirmed at line 186 with the exact `QuotaService`/`VideoLifecycleService`/`ModerationOrchestrationService` reasoning. |
| 18 | Container-sampler + container-ceiling gate steps | **MATCH** | `Start container sampler` (:46-47) and `Assert container ceiling (AC1)` (:87-89) both present in `build` job exactly as described. |

---

## 2. Ledger & precedent attribution

| Claim | Sources checked | Verdict |
|---|---|---|
| `quiesceAsyncExecutors` exists per `skillars-deferred-131` AC4, to drain `@Async` tasks from a preceding test's committed transaction | Listener javadoc (lines 145-247), `skillars-deferred-131-...md` AC4 (line 511), `deferred-work.md`, `git log --grep` (commit `2842df52`) | **MATCH** — faithful paraphrase. (The javadoc's deeper reason is closing a specific deadlock with the reset transaction, not draining "for its own sake" — doesn't contradict the story's narrower use of the citation.) |
| `[deferred-19] database reset:` tag and its "clock starts after quiesce" precedent | grep in listener (lines 308, 319), `skillars-deferred-19-...md` (AC5.6, 814 invocations/99.7ms), in-code comment :115-118 | **MATCH** — tag, story, and figure all consistent, including the same 814/99.7ms figure the deferred-146 story separately flags as stale. |
| `sprint-status.yaml` entry and `last_updated` consistency | `sprint-status.yaml:2,337` | **MATCH** — `ready-for-dev`, consistent with story header and with being the latest entry. |
| Five "Deferred From This Investigation" items not already in `deferred-work.md` | grep for all five topics across `deferred-work.md` | **MATCH** — zero matches for all five; genuinely new, not redundant. |
| AC2c: master has no required status checks (404 + ruleset 20583638, no `required_status_checks` rule) | Live `gh api repos/tenjohokwen/skillars/branches/master/protection` → 404 "Branch not protected"; `gh api .../rulesets` → one ruleset, `NoDirectPush` id 20583638; `gh api .../rulesets/20583638` → rules are `deletion`/`non_fast_forward`/`pull_request` only | **MATCH** — independently reconfirmed live, same-day, exact match to the story's claim. |
| `docs/testing/readme.md`'s "Why not the ≤ 10" section is "correct and still binding" | `docs/testing/readme.md:170-192` | **MATCH** — section exists verbatim with the stated reasoning. |

All six ledger/precedent/external claims survived independent re-verification.

---

## 3. Mechanistic claims

| Claim | Evidence | Verdict |
|---|---|---|
| Awaitility resolves an unset `pollDelay` to the fixed `pollInterval` | Decompiled `awaitility-4.3.0.jar` directly (no sources jar available): `Awaitility.DEFAULT_POLL_DELAY` is `null`; `ConditionFactory.definePollDelay(null, FixedPollInterval(25ms))` → `pollInterval.next(1, ZERO)` → `FixedPollInterval.next()` ignores both args and returns the fixed duration. | **CONFIRMED**, from the actual bytecode the build resolves, not from general Awaitility knowledge. |
| Preceding test method's `@Async` task is already enqueued/visible by the time the next method's `quiesceAsyncExecutors` runs | Listener's own javadoc + `pom.xml` (single forked JVM, no parallel JUnit config) confirm sequential test execution; `AsyncExecutionAspectSupport.doSubmit()` (from `spring-aop-6.2.6-sources.jar`) calls `executor.submit()` synchronously on the calling thread, not via a handoff thread; neither project `AsyncConfig` overrides executor resolution in a way that changes this. | **CONFIRMED** — the ordering guarantee the story leans on is real. |
| Reset's clock starts after quiesce | `:111-119` quoted directly: `quiesceAsyncExecutors(ctx)` call precedes `long startNanos = System.nanoTime();` | **CONFIRMED.** |
| Docker build depends on nothing `mvn verify` produces (`pom.xml`/`src/`/`.git/` only) | Full `Dockerfile` read: builder stage `COPY pom.xml .` / `COPY src/ src/` / `COPY .git/ .git/`, then its own `mvn package` — nothing from `target/` on the host is referenced. | **CONFIRMED.** |
| `ci.yml:88` as precedent for a standalone, un-gated image job | See Finding B — it's precedent for "separate job," not "no `needs:`" | **PARTIALLY CONFIRMED, with the caveat already noted in §1 item 14.** |
| AC4: no Spring context configuration changes, context-count gate cannot move | Proposed touch points are a private-method extraction + fluent-chain edit inside one existing class; `assert-context-count.sh` counts `missCount` from `DefaultContextCache`, driven by test-class-level annotations only. | **CONFIRMED.** |
| Six named `ThreadPoolTaskExecutor` beans | See Finding A below | **One of six names wrong; no functional impact (resolution is by type, not name).** |

---

## 4. Corner cases, false assumptions, and missed flows (survived adversarial re-check)

### Finding A — One of the "six `ThreadPoolTaskExecutor` beans" named in the story is wrong

The story (Finding 1 narrative, line 56) names: `outboxDrainPool`, `sluRetryExecutor`, `reportExecutor`, `moderationTaskExecutor`, `threadPoolTaskExecutor`, `taskExecutor`.

I independently reread `ExecutorShutdown.java:77-83` (own budget table) and confirmed it lists the bean **`sendMailPool`**, not `threadPoolTaskExecutor`, as the sixth pool. Tracing the actual `@Bean` declaration: `notification/config/AsyncConfig.java` has a factory method *named* `threadPoolTaskExecutor()`, but it carries `@Bean(name = "sendMailPool")`, which overrides the registered Spring bean name. The story's list substitutes the Java method name for the real bean name.

**Impact:** None on the implementation — `quiesceAsyncExecutors` enumerates `ctx.getBeansOfType(ThreadPoolTaskExecutor.class).values()`, which resolves by runtime type, and `GracefulShutdownTaskExecutor extends ThreadPoolTaskExecutor`, so `sendMailPool` is found and quiesced regardless of what the story calls it. Task 3's unit test doesn't depend on bean names either. This is a narrative/documentation inaccuracy in the story, not a defect a developer implementing the tasks would propagate into code — but it would mislead anyone using the story's bean list to cross-check `ExecutorShutdown`'s own table later.

### Finding B — AC2/Task 4's `ci.yml` precedent is narrower than the surrounding text implies

AC2's task instructions say to point the new job's comment "at `ci.yml:88` as the existing precedent for a standalone image job." `ci.yml`'s `build-and-push` job is indeed structurally separate (a real precedent for "Docker build lives in its own top-level job") — but it carries `needs: [test, frontend-quality]` (confirmed live at `ci.yml:93`), deliberately gating image publish on tests passing, because publishing to `:latest`/master is a real release. The new `pr-build.yml` job the story proposes deliberately has **no** `needs:`, trading wasted runner-minutes on a red PR for wall-clock savings on green ones (a tradeoff Task 4 itself discloses explicitly). The precedent is valid for "separate job is an established pattern in this codebase," but a developer citing `ci.yml:88` as prior art for "runs ungated in parallel" would be citing it for something it doesn't actually do. Low risk in practice since Task 4's own text separately states the "no needs" rationale directly rather than relying on the precedent to carry it — but the comment Task 4 asks the dev to write should not conflate the two job's gating semantics.

### Finding C — AC1d's stated justification for leaving `ConcurrencyLockWaitSupport.java:95` untouched is backwards

AC1d says that call site is left alone because, among other reasons, "its condition is genuinely expected to be false at first call." I reread `ConcurrencyLockWaitSupport.java:81-99` directly: `assertGenuineLockRetryOccurred`'s own javadoc states explicitly, **"Call this AFTER both the holder and the contender threads have been joined"** (line 92), and its one caller pattern across all 6 real call sites (`ReviewFlagServiceConcurrencyIT`, `SubscriptionServiceConcurrencyIT`, `CoachProfileServiceConcurrencyIT` ×2, `AdminReviewQueueIT`, `ReviewModerationServiceConcurrencyIT`) is to call it only after `Future.get(...)` has already returned for the thread that performs the retry. `PessimisticLockRetryer.withBoundedRetry` records the retry counter synchronously on the retrying thread before that thread's task completes, and `Future.get()` establishes happens-before with task completion — so in the normal passing case, the counter has *already* incremented and the condition is **already true on the first Awaitility evaluation**, the same already-true-condition situation AC1/Task 1 fixes everywhere else in this story, not a "genuinely false at first call" case.

**Impact:** The "leave it alone" *decision* is still almost certainly fine — 6 call sites × ~25ms ≈ 150ms total, versus the ~230s this story is otherwise chasing, so there's no practical reason to touch it. But the *reason* AC1d records for the next reader is factually backwards, and AC1d exists specifically so a future reader doesn't have to re-derive this. The correct reason is closer to "negligible volume (6 sites, not 1213×6), not an already-true-condition case" — this should be corrected in the AC text before implementation, or at minimum the dev should not copy the current wording into the method's extended javadoc per Task 1's own instruction to explain "why the poll delay is explicitly zero."

### Finding D — AC5 cites a nonexistent "AC1.6"

AC5 (line 128) reads: "...the only other changes are `.github/workflows/pr-build.yml` and two documentation files (AC1.6, AC6)." I reread the story's own "## Acceptance Criteria" section top to bottom: the enumerated criteria are exactly AC1, AC1b, AC1c, AC1d, AC2, AC2b, AC2c, AC3, AC4, AC5, AC6 — there is no "AC1.6" anywhere. The only other occurrence of the string is in the Finding-1 narrative (line 40: "AC1.6 fixes the document"), which is the same dangling reference, not a definition of it. Every other mention of documentation work in the story — the Finding 1 note, AC6's full text, Task 6, and the File List — names exactly one file, `docs/testing/readme.md`.

**Impact:** As written, AC5's "two documentation files" claim cannot be satisfied or verified, because no second document is ever named anywhere in the story. This reads as a numbering artifact left over from an earlier draft. A developer treating AC5 literally as a completion gate could reasonably believe a file was dropped from scope. Should be corrected to name `docs/testing/readme.md` once (via AC6) before implementation starts.

### Finding E (minor, non-blocking) — Task 1's helper-extraction rationale is only half-wired

Task 1 extracts `isQuiesced(ThreadPoolTaskExecutor)` explicitly "so the short-circuit and the Awaitility predicate cannot drift apart," then instructs `if (isQuiesced(executor)) { continue; }` before the Awaitility call — but does not explicitly instruct the surviving `.until(() -> executor.getActiveCount() == 0 && ...)` predicate to also call through the same `isQuiesced()` helper. Followed literally, the condition would exist in two places (the helper, and the inlined `.until()` lambda), which is exactly the drift risk the extraction was supposed to close. Worth a one-line addition to Task 1 (`.until(() -> isQuiesced(executor))`) before implementation, though a competent developer would likely notice and do this anyway given the stated rationale.

---

## 5. What did not survive adversarial re-verification

- **Item 14 / Finding B initially read as a flat contradiction** ("the story claims `ci.yml` has no `needs:`, but it does") — on rereading the story's exact wording, the claim is narrower than that: it only asserts `ci.yml` already uses "a separate job," which is true. Downgraded from "contradicted citation" to "precedent is narrower than a developer might assume from the surrounding AC2/Task 4 framing" (kept as Finding B, not as a hard defect).
- **Item 7 (line 252 vs. 253/254)** — initially looked like a wrong insertion-point instruction. On rereading, `:252` correctly identifies the start of the `Awaitility.await()` chain, which is what the task text is actually citing; the fluent-chain insertion point is self-evident from the quoted code immediately above it in the story. Kept as a one-line note in §1, not promoted to a Finding — no developer following the task would be misled.
- **Items 9 and 16 (mis-ranged line citations for the `-q` comment and `SharedContainers.Postgres`)** — both are genuine citation drift, but in both cases the substance of the claim being cited is independently true elsewhere in the same file. Kept as citation notes in §1; not promoted to Findings, since nothing about the engineering decision depends on the exact line range being right.
- **AC3's "investigated and written up" closure criterion** — flagged by the corner-case pass as potentially under-specified, but on review this is a normal, acceptable level of looseness for a measurement-based AC in this project's stories; not a defect.
- **Thread-safety of the new QUIESCE_COUNT/QUIESCE_NANOS counters** — checked and confirmed no race exists (single forked JVM, no parallel JUnit execution, existing counters already `AtomicLong`); nothing to report here.
- **Docker-job hidden coupling, new-job permissions, and timeout math (AC2/2b/Task 4)** — all checked in detail (composite action, Trivy action, Buildx cache backend) and found sound; no findings survived.

---

## 6. Recommendation

Per-claim confidence, not a blanket score:

- **High confidence, no changes needed:** the core technical thesis (Awaitility pollDelay resolution, confirmed via decompiled bytecode; the async-task-visibility ordering argument; the reset/quiesce transaction-boundary claims; the Docker-build input-independence claim; the branch-protection claim, independently reconfirmed live). These are the load-bearing claims for AC1 and AC2, and all of them hold up under direct, adversarial re-verification against real artifacts, not just plausible-sounding prose.
- **Needs a pre-implementation fix, low engineering risk:** Finding D (phantom "AC1.6" in AC5) and Finding C (AC1d's backwards rationale) should both be corrected in the story text before a developer starts — not because either changes what gets built, but because both are exactly the kind of "documented reasoning that saves the next reader from re-deriving it" the story is otherwise careful about, and right now that reasoning is wrong or undefined in two places.
- **Cosmetic, optional:** Finding A (bean name), Finding B (precedent framing), Finding E (helper-extraction completeness), and the three mis-ranged line citations (items 7, 9, 16 in §1). None of these would cause an implementer to build the wrong thing, but Finding A and the citation drifts would cost a future reader time if they tried to verify the story's claims directly (as this review did).
- **Not independently re-executable by this review:** the measured timing figures themselves (230s awaitility estimate, 4m03s docker/trivy baseline, 18m39s run total) — these come from a specific GitHub Actions run (`37530194292`) this review did not re-pull via `gh api`/`gh run view`. The reasoning connecting them (six pools × ~190ms × 1213 methods ≈ 230s) is internally consistent and was not challenged by anything found in source, but the raw run-log numbers themselves were taken on trust, not re-fetched.

No finding in this report rises to "this story should not proceed as ready-for-dev" — the two pre-implementation fixes (C, D) are both narrow text corrections, not redesigns.
