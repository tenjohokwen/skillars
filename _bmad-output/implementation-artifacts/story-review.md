# Senior-Dev Review — Story Deferred-19: Integration-Test Context & Container Consolidation

**Story reviewed:** `_bmad-output/implementation-artifacts/skillars-deferred-19-test-context-container-consolidation.md`
**Tree at:** `21ef489`
**Date:** 2026-08-07
**Scope:** missed corner cases, false assumptions, missed flows, and whether the proposed solutions are enforceable by GitHub CI.

Findings are evidence-cited. A "Checked and found sound" section at the end records what was verified and cleared, so the absence of a finding is deliberate rather than an omission.

**Verdict:** AC1, AC2, AC7 and AC9 are sound and shippable. **AC5 cannot be implemented as written** — Blocker 1 breaks the suite, and Major 2/3 are consequences of AC2 that AC5 amplifies rather than mitigates. Blockers 2 and 3 mean the problem statement and the success criteria both need re-baselining on CI before work starts.

---

## BLOCKER 1 — AC5's truncate permanently destroys Flyway-seeded reference data

AC5 excludes exactly three things (`flyway_schema_history`, `main.shedlock`, `qrtz_*`) and directs the implementer to enumerate everything else from `information_schema`. It misses an entire *category*: tables that Flyway seeds with reference data.

**33 of the 91 migrations contain `INSERT INTO`.** The ones that matter:

| Migration | Seeds |
|---|---|
| V20, V25, V57, V64, V85, V90 | `main.platform_config` — age policy, moderation config, subscription tiers, phone-OTP toggle, payment-sweep config |
| V39 | `session.drills` — 20 `PLATFORM` foundation drills |
| V21 | `main.authority` |

Flyway will **not** re-insert them: the history row is intact, so the second and subsequent context runs are the no-op validation passes the story itself describes (AC1.5). The first truncated test method destroys this data for the remainder of the JVM.

Concrete breakage, not speculation:

- `DrillLibraryResourceIT.java:96` — `SELECT id FROM session.drills WHERE library_type = 'PLATFORM' AND status = 'ACTIVE' LIMIT 1`. That row exists only because of V39.
- `ConfigResourceIT`, `ConfigGuardIT`, `SubscriptionLifecycleIT`, `VideoSubscriptionLifecycleListenerIT` all read `main.platform_config`.

This works **today** precisely because no cleaner touches those tables. Every existing delete is targeted:

- `SimultaneousExpiryIT.java:74` — `DELETE FROM main.platform_config WHERE id IN (8201, 8202)`
- `YearlyExemptionRenewalIT.java:69` — `DELETE FROM main.platform_config WHERE id IN (8101, 8102)`
- `BookingBatchResourceIT.java:164` — `DELETE FROM main.platform_config WHERE id = 50`
- `DrillTagResourceIT.java:87` — deletes only drills owned by the test's own coach ids

`DbCleaner.cleanDb()` and `TestDataCleaner.wipeAll()` never mention `platform_config` or `drills`.

AC5.5's prescription — *"Fix them by adding the missing seed to the class, not by weakening the reset"* — is the wrong instruction for this case. Re-seeding migration reference data into ~130 classes is not a latent defect being surfaced; it is the reset being wrong.

**Required change.** AC5 needs a fourth rule: restore reference data, not merely exclude infrastructure tables. Cheapest correct shape is to capture the reference rows once after the first context's Flyway run (or keep a `reference-seed.sql` that the listener replays after truncation). Add an explicit AC enumerating the reference tables, plus a check that keeps the list in sync when a new seeding migration lands.

---

## BLOCKER 2 — the 30:45 baseline is a local-machine artifact; CI already runs the whole build in ~10 minutes

The story's ROI framing rests on `mvn -o verify` taking 30:45 with ~74 containers saturating the Docker daemon. On GitHub Actions that is not what happens.

PR run `28624816025` (`pr-build.yml`, `ubuntu-latest`, 4 vCPU):

- job start `22:11:23`
- Testcontainers active by `22:14:22` (MinIO health wait)
- failsafe summary at `22:21:21`: `Tests run: 823, Failures: 0, Errors: 1, Skipped: 4`
- **total job: 10m05s**, including checkout, JDK setup, the full frontend build, the integration suite at the unrefactored 37 contexts, a Docker image build, and a Trivy scan.

The un-refactored suite, with ~74 containers, already lands inside the story's projected post-refactor "8–15 min range" on CI. The 30:45 and the Docker saturation are a macOS / Docker-Desktop-VM phenomenon.

This does **not** invalidate the refactor — 37 contexts arising from 7 genuinely distinct configurations is a real defect, and AC7 is worth having regardless. It does invalidate:

- the "Why this story exists" framing and the Dev Notes projection;
- Task 1 and Task 11, which measure only locally and will therefore produce numbers nobody else can reproduce.

Note also that CI reports **823** integration tests against the story's baseline of **905**. The two baselines are not measuring the same thing. Task 11 already flags that the last three stories miscounted; this is another instance. Both counts must be pinned on CI.

---

## BLOCKER 3 — CI is red and has been for five consecutive runs

Every recent `pr-build` run failed. The failure:

```
SecurityFilterChainIT.test2FAFilterBlocksAccessUntilVerified » ScriptStatementFailed
  Failed to execute SQL script statement #1 of class path resource [sql/userData.sql]:
  INSERT INTO main."user" (id, ...) VALUES (586920556720583008, ...)
```

That is the non-idempotent fixed-PK insert AC5 describes — failing **on CI but not locally**, i.e. an ordering/timing-dependent failure the local run hides.

Consequences the story does not account for:

- "Keep the suite green at each step" (the Acceptance Criteria preamble) and every "Full verify" checkpoint are unverifiable on CI right now.
- Regressions introduced by this refactor will be indistinguishable from the pre-existing failure.

**Add a Task 0:** get `pr-build` green — or record the exact known failure set as the baseline — before AC1 lands, and push a PR at each task boundary. Otherwise the CI-only failure class stays invisible until Task 11.

Separately, the same log shows the suite making **real outbound SMTP calls**: `MailService.sendEmailFromTemplate` → `mail.gmx.net` → `jakarta.mail.AuthenticationFailedException: 535 Authentication credentials invalid`. This is independent evidence *for* AC3's global `enable.test.mail: true`, which the story currently justifies only on context-count grounds. It also means the flip may change behaviour in any test that today silently tolerates the send failure.

---

## MAJOR 1 — AC4.3 is built on a false premise

The story states:

> *"Once a mock is shared across ~90 classes in one context, a `when(...)` from one class survives into the next… This is a new requirement introduced by this story."*

It is not. In spring-test 6.2.16 (Boot 3.5.11), `MockitoBean.java:199`:

```java
MockReset reset() default MockReset.AFTER;
```

with the javadoc *"mocks are automatically reset after each test method is invoked"*, enforced by `MockitoResetTestExecutionListener` (a default listener). Cross-class stub leakage is already prevented today and remains prevented after the hoist.

The explicit `Mockito.reset(...)` is harmless, but stating it as *the* risk **displaces the real one**: hoisting a `@MockitoBean` to a base class replaces the real bean for all ~90 subclasses. AC4.2 catches this for `VideoProviderAdapter` and `FileStorageService` — but AC4.1 hoists `GeminiClient` to the root with no equivalent check, even though `application-test.yaml:52` points Gemini at `${wiremock.server.baseUrl:http://localhost:9999}`, i.e. a WireMock path exists for it.

**Required change.** Apply AC4.2's own trap-check to `GeminiClient` before hoisting. Reword AC4.3 to: *verify Spring's automatic `MockReset.AFTER` covers the shared mocks; add explicit resets only for `@MockitoSpyBean` and stateful stubs.*

---

## MAJOR 2 — consolidation unleashes 31 schedulers across the whole run; AC3 disables four

`AsyncConfig.java:25` declares `@EnableScheduling` unconditionally. `src/main` contains **31 `@Scheduled` methods and 11 `@SchedulerLock` jobs**. Several have very short delays and are *not* neutralized in `application-test.yaml`:

| Job | Delay |
|---|---|
| `OutboxPollerScheduler`, `DeletionSchedulerService` | `${app.storage.poller.fixed-delay-ms:5000}` — **5 s** |
| `MessagingEmitterRegistry`, `AlertEvaluationService` | 30 s |
| `EmailRetryScheduler`, `AlertRuleCache`, `QuotaReservationTimeoutService`, `VideoSubscriptionLifecycleListener` | 60 s |
| `QuickCompleteTimeoutService`, `BookingReminderScheduler`, `BookingExpiryScheduler` | 5 min |
| `PaymentPendingSweeper` | 15 min |
| `SessionPackForfeitureScheduler` | 60 min |

Today, context fragmentation caps each scheduler's blast radius at one context group's lifetime. After AC2/AC3, **one** context lives for the entire failsafe run with all 31 threads writing to the same database the tests assert on. AC3 moves only four *video* delays to global scope.

Worse, AC5.1 prescribes backdating **all** shedlock rows on **every** test method. That is correct as a replacement for the delete — the deferred-15 reasoning is sound — but universalizing it makes all 11 locked jobs eligible on all ~905 methods. The story's own AC5 fix amplifies the AC2 hazard.

Two further mechanical risks in the same area, unaddressed:

- `TRUNCATE` takes `ACCESS EXCLUSIVE`. Issuing it ~905 times against a database with live scheduler connections is a lock-contention and deadlock exposure, not just a cost question. AC5.5 asks only for a timing measurement.
- The story excludes `qrtz_*` because a clustered check-in thread writes concurrently (correct — `org.quartz.jobStore.isClustered: true`, 20 s check-in interval). The same argument applies to every table the 31 schedulers touch, and is not made.

**Required change.** Add an AC that globally neutralizes scheduling under the `test` profile — a conditional on `AsyncConfig`'s `@EnableScheduling`, or a systematic delay sweep in `application-test.yaml` — with the tests that need a sweeper invoking it directly.

---

## MAJOR 3 — truncate vs. in-application caches

`ConfigService` caches `main.platform_config` in-process on a `@Scheduled(fixedDelayString = "${app.config.cache-ttl-seconds:300}")` refresh; `AlertRuleCache` does the same for alert rules. Truncating the database under a **long-lived** context leaves those caches holding rows that no longer exist, and leaves a test that seeds config racing a 300-second refresh. Context churn made this self-healing today.

**Required change.** The reset listener must evict application-level caches, or the caches need a test-visible reset hook.

---

## MODERATE

### M1 — `SharedContainers` as one static initializer starts MinIO for every JVM

`MinioTestConfig`'s own javadoc states it is *"deliberately kept out of `TestConfig` so tests that never touch blob storage don't pay for a MinIO container and bucket-creation on every context startup."* A single class holding three `static final` fields discards that intent. Use separate lazy holder classes so MinIO starts only when the storage family runs — this matters for `-Dit.test=X` single-class iteration, which is the workflow the story is trying to make fast.

### M2 — the static Postgres container loses its database name

Today `postgresContainer(@Value("${spring.application.name}") String dbName)` reads the name from the Spring context. A static container cannot. Pick a fixed name explicitly and confirm nothing binds to it. AC1's task list omits this.

### M3 — AC6's verification command can never pass

AC6 says: *"Confirm with `grep -rn "ledger" src/` returning nothing."* `ledger` is a live domain concept in this codebase: `V79__credit_ledger_append_only.sql`, `V62__session_payment_credit_wallet.sql`, `CreditWalletService`, `ParentCreditLedger`, `PaymentPendingSweeper`, and `application.yaml`'s access log (`directory: /usr/local/var/ledger`, `suffix: .ledger`). The correct check is `grep -rn "ledger.database.spy" src/`.

H1's underlying claim is correct — all 13 hits for `ledger.database.spy` are in `src/test`.

### M4 — `docs/testing/` is not empty

AC8 and the Project Structure Notes both state it is empty; Task 10 says "write". It already contains `readme.md`, `container-architecture.md`, `why-inheritance-over-import.md` and `test-data-isolation.md` (untracked, written 2026-08-07, carrying a *"design documented, migration not yet applied"* banner with TODAY/TARGET markers). As written, Task 10 risks overwriting existing drafts.

**Reframe as:** update in place, replace projections with measurements, remove the banner and the TODAY/TARGET markers.

### M5 — AC3's `AbstractE2ETest` row conflates two unrelated configs

`E2ESecurityConfig` is imported by exactly two concrete ITs (`ConfigResourceIT`, `StorageResourceIT`). `TestClockConfig` is used **only** by `AbstractSkillarsE2ETest`, which has **zero subclasses** — it is dead code. Bundling both into one base would give those two ITs a fixed clock they do not have today, a behaviour change landing directly on top of deferred-17/18's timezone work.

**Required change.** Either delete the dead `AbstractSkillarsE2ETest` or keep `TestClockConfig` out of the shared base.

### M6 — AC9 side effect on the packaged artifact

`maven-resources-plugin` copies `src/frontend/dist/spa` → `target/classes/static` at `process-resources`. With `-DskipFrontend` on a clean tree that directory does not exist and the resulting jar ships with no UI. Acceptable for backend iteration, but AC9 should say so, and should state that CI must never set the flag — `pr-build` builds a Docker image from the same artifact.

### M7 — internal inconsistency in line references

The no-default Bunny property is cited as `application-test.yaml:35` in AC2 and `:31` in "What must not break". `:35` is correct (`api-base-url: ${wiremock.server.bunny-service.baseUrl}`). The surrounding claim is otherwise accurate: `:34`, `:48` and `:52` do carry defaults.

---

## CI SUPPORT — the story specifies almost nothing CI can enforce

`pr-build.yml` currently runs `mvn -B verify -q` with `timeout-minutes: 15`, then builds and scans a Docker image. Nothing else. Gaps, in priority order:

### C1 — AC3's "≤ 10 contexts" has no enforcement, by explicit design

Dev Notes directs the implementer to build the analysis script *"in the scratchpad (not in the repo)"*. An unversioned script cannot gate anything; the first `@TestPropertySource` someone adds silently regresses AC3 and nothing fails.

A CI-native alternative measures the real thing rather than statically approximating it. `DefaultContextCache.java:275-276` (spring-test 6.2.16) logs, under category `org.springframework.test.context.cache` at DEBUG:

```
Spring test ApplicationContext cache statistics: [DefaultContextCache@... size = N, maxSize = 32, ... hitCount = X, missCount = N]
```

`missCount` is exactly the number of contexts actually built. Enable that category in `logback-test.xml` and add a `pr-build` step that greps the final occurrence and fails if `missCount > 10`.

**Recommendation:** fold this into AC3 as its verification mechanism, replacing the offline script.

### C2 — AC1's container ceiling is verified by a human running `docker ps`

Encode the Given/When/Then instead: background a sampler (`docker ps --format '{{.Image}}'` on a loop writing to a file) alongside `mvn verify`, then assert the peak concurrent count is ≤ 1 per image. Cheap, and it is the acceptance criterion verbatim.

### C3 — `-q` makes Task 1 and Task 11 impossible from CI

In the run inspected, `-q` suppressed every phase marker: no per-phase wall clock, no surefire summary, no per-class times. Drop `-q`, and upload `target/surefire-reports` and `target/failsafe-reports` as build artifacts so AC8's before/after numbers are reproducible by someone other than the author.

### C4 — `timeout-minutes: 15` is the only duration guard and is not a regression detector

The current run is ~10 min. AC5 adds ~905 truncates plus ~905 Redis flushes. Tighten the timeout post-refactor to a value that actually detects regression, and record the chosen value in AC8.

### C5 — AC7 is the one AC that CI enforces for free

Provided the guardrail class is named `*Test` (surefire, `test` phase, ahead of failsafe) and not `*IT`. The story says "fast, container-free test" but never pins the name. State it explicitly.

### C6 — every task specifies `mvn -o verify`

Offline mode is a local convenience and will not work on a fresh runner. Each task checkpoint should also be validated by a pushed PR, or Blocker 3's CI-only failure class stays hidden until Task 11.

---

## Checked and found sound — not findings

Recorded so the absence of a finding is visible as a deliberate result.

- **H3 / `allowed.clients` superset is safe.** Searched for tests asserting a client-id *rejection* (`hisClientId`, `herClientId`, `ourClientId`, invalid/unknown client) — none exist. Widening the list cannot break an assertion.
- **Global `rate.limiting.enabled: false` will not neuter `RateLimitingAspectIT`.** It declares no `@ActiveProfiles`, so the `test`-profile document `application-test.yaml` never loads for it; its `@Value("${rate.limiting.enabled:true}")` default stands.
- **AC7's allowlist will not trip the 51 `*IT` files that lack `@SpringBootTest` in the file itself.** They inherit it from the payment / video / session / storage `Base*IT` classes and will be assignable to `AbstractIntegrationTest` after AC2.
- **No container leak into the surefire JVM.** The only `*Test`-named class carrying `@SpringBootTest` is `AbstractSkillarsE2ETest`, which is abstract with zero subclasses. AC1's "one container set per test JVM" framing holds.
- **AC1.2's rationale is correct.** `ConnectionDetails` beans are not `Startable`, so `TestcontainersLifecycleBeanPostProcessor` will not destroy them, and `TestConfig.hikariConfig(JdbcConnectionDetails)` continues to have its parameter satisfied. `DataSourceConfig.java:25` is indeed gated `havingValue = "false"`, so H6's expectation that `hikariConfig` and `spyDataSource` are orphaned under `datasource.container=true` is well-founded.
- **AC5.2's listener-ordering argument is correct.** `SqlScriptsTestExecutionListener` (order 5000) runs in `beforeTestMethod`, ahead of JUnit `@BeforeEach`, and `@TestExecutionListeners` does not contribute to `MergedContextConfiguration`.
- **The shedlock backdate-vs-delete reasoning is correct** (setting aside Major 2's amplification concern), as is the `qrtz_*` exclusion — Quartz runs `job-store-type: jdbc` with `isClustered: true` and a 20 s check-in interval, live during the run.
- **`frontend-maven-plugin` plugin-level `<skip>` does cover all five executions** — each mojo declares the same `skip` parameter name, so AC9's single-block approach works; the story's instruction to confirm per-execution in the log is still worth keeping.

---

## Recommended story amendments, in order

1. **Task 0 (new):** establish a green — or explicitly baselined — CI run before any refactor commit (Blocker 3).
2. **Rewrite AC5.1** to restore Flyway-seeded reference data rather than only excluding infrastructure tables; enumerate the reference tables; drop or qualify AC5.5's "add the seed to the class" instruction (Blocker 1).
3. **Add a scheduler-neutralization AC** covering all 31 `@Scheduled` jobs under the `test` profile, before AC2 consolidates everything into one long-lived context (Major 2).
4. **Add cache-eviction** to the reset listener, or a test-visible reset for `ConfigService` and `AlertRuleCache` (Major 3).
5. **Rewrite AC4.3** around `MockReset.AFTER`, and apply AC4.2's trap-check to `GeminiClient` (Major 1).
6. **Re-baseline the story's numbers on CI** — replace the 30:45 / ~74-container framing with the measured CI figures, and reconcile 823 vs 905 (Blocker 2).
7. **Move AC3's verification into CI** via the `org.springframework.test.context.cache` DEBUG line; add the `docker ps` sampler for AC1; drop `-q`; upload test reports (C1–C4).
8. **Fix the small ones:** AC6's grep command (M3), the `docs/testing/` framing (M4), the `AbstractE2ETest` / `TestClockConfig` split (M5), the static container's database name (M2), lazy MinIO (M1), AC9's artifact note (M6), the `:31` / `:35` reference (M7).
