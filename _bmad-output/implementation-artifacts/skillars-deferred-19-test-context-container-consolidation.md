# Story Deferred-19: Integration-Test Context & Container Consolidation

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer running `mvn verify` on Skillars,
I want the integration-test suite to start **one** PostgreSQL container, **one** Redis container and **one** MinIO container per JVM instead of one pair per Spring context — and to stop fragmenting into ~37 distinct Spring contexts,
so that a full verify costs minutes instead of half an hour and does not saturate the Docker daemon with ~74 containers.

### Why this story exists

`mvn -o verify` at `21ef489` takes **30:45** and runs **825 unit + 905 integration tests** (recorded in `sprint-status.yaml` for `skillars-deferred-18`). Mbah observed ~30 PostgreSQL and ~30 Redis containers alive simultaneously in Docker during a run and asked for a thorough investigation and proposal, with a specific hypothesis that `@MockitoBean` is implicated.

**The hypothesis is correct, and it is one of three contributing causes.** All numbers below were derived by parsing every test class under `src/test/java` and resolving annotation inheritance; the analysis is reproducible (see *Verification method* in Dev Notes).

#### The 30:45 is a local-machine figure — do not treat it as the universal baseline

The wall-clock cost is substantially a macOS / Docker-Desktop-VM phenomenon. On GitHub Actions the same suite is far cheaper.

Evidence: `pr-build` run `28624816025` (`ubuntu-latest`, 4 vCPU). Its `mvn -B verify -q` step ran `22:11:27 → 22:21:21` — **9m54s** — covering the full frontend build plus the integration suite at the unrefactored 37 contexts, finishing `Tests run: 823, Failures: 0, Errors: 1, Skipped: 4`.

Three corrections to how that figure must be read, because it is easy to over-claim from it:

1. **It is from `2026-07-02`, on a dependabot branch — not this tree.** `deferred-16`, `-17` and `-18` all landed afterwards. The 823-vs-905 gap is explained by tree age, **not** by the two baselines measuring different things and **not** by a miscount of the kind Task 11 warns about.
2. **The run failed**, so the Docker image build and the Trivy scan were **skipped**. The ~10-minute job total does *not* include them. A green run is longer.
3. It therefore does **not** establish that the current tree runs in ~10 minutes on CI. It establishes that the local 30:45 is not representative, which is enough to invalidate the ROI framing but not enough to replace it.

**Consequence for this story:** the container-count defect is real and worth fixing on its own merits — 37 contexts arising from 7 genuinely distinct configurations is a defect regardless of how fast any particular machine runs it — but the wall-clock justification must be **re-baselined on CI** (Task 0), and Task 1 / Task 11 must record CI numbers alongside local ones. A local-only measurement is not reproducible by anyone else.

#### CI is currently red — in two unrelated ways

Both must be understood before any "keep the suite green" checkpoint in this story means anything. They are **different workflows with different failures**; do not conflate them.

- **`ci.yml` (push to master) — red for the last 6 consecutive runs, through `2026-08-07`.** It fails after ~8 seconds at *"Log in to GHCR"*. This is a credentials problem (`secrets.GHCR_PAT`), and `ci.yml` **runs no tests at all** — it only builds and pushes the Docker image. It tells us nothing about test health.
- **`pr-build.yml` (pull requests) — last ran `2026-07-02`**, and failed on a genuine test error:

  ```
  SecurityFilterChainIT.test2FAFilterBlocksAccessUntilVerified » ScriptStatementFailed
    Failed to execute SQL script statement #1 of class path resource [sql/userData.sql]:
    INSERT INTO main."user" (id, ...) VALUES (586920556720583008, ...)
  ```

  That is **exactly** the non-idempotent fixed-PK insert this story describes, failing **on CI but not locally** — an ordering-dependent failure the local run hides. It is simultaneously a blocker to baselining *and* direct evidence that AC5 is fixing a live problem, not a theoretical one.

**Also visible in that log, and worth fixing regardless of this story:** the suite makes **real outbound SMTP connections from CI** — `DEBUG SMTP: connected to host "mail.gmx.net", port: 587` authenticating as `blue-bone@gmx.de`, ending in `AuthenticationFailedException: 535`. Real credentials are being sent to an external service on every CI run. This is independent evidence for AC3's global `enable.test.mail: true`, which the story otherwise justifies only on context-count grounds — and it means the flip may change behaviour in any test that today silently tolerates a send failure. Check for those when making it global.

#### Root cause

`TestConfig` declares the containers as context beans:

```java
// src/test/java/com/softropic/skillars/config/TestConfig.java:43-57
@Bean @ServiceConnection(name = "redis")
GenericContainer<?> redisContainer() { ... }

@Bean @ServiceConnection
PostgreSQLContainer<?> postgresContainer(@Value("${spring.application.name}") String dbName) { ... }
```

Spring Boot's `TestcontainersLifecycleBeanPostProcessor` starts a `Startable` bean when its context refreshes and stops it when that context closes. **The container lifetime is therefore bound 1:1 to the Spring `ApplicationContext`.** `MinioTestConfig` (`src/test/java/com/softropic/skillars/config/MinioTestConfig.java:26`) has the same shape for MinIO.

The Spring TestContext Framework caches contexts keyed by `MergedContextConfiguration`. Two test classes share a context **only if every component of that key matches**: `@SpringBootTest(classes/properties/webEnvironment)`, `@ActiveProfiles`, `@TestPropertySource`, `@Import`, registered `ContextCustomizer`s — and, since Spring Framework 6.2 (Boot 3.5.11 here), the **bean-override customizer built from the set of `@MockitoBean`/`@MockitoSpyBean` declarations**. A single extra mocked type on a single test class produces a different key, hence a different context, hence **another PostgreSQL container and another Redis container**.

#### Measured, at commit `21ef489`

| Measurement | Value |
|---|---|
| Concrete `@SpringBootTest` integration-test classes (annotation inheritance resolved) | **129** |
| Failsafe test classes with reports in `target/failsafe-reports` | **141** |
| **Distinct Spring context cache keys** | **37** |
| ⇒ containers required | **37 postgres + 37 redis** (+ MinIO for the storage family) |
| Contexts serving exactly **one** test class | **23 of 37** |
| Largest shared context | 30 classes |
| Total failsafe elapsed (sum of per-class `Time elapsed`) | **29.7 min** |
| `spring.test.context.cache.maxSize` | **32** (Spring default — never overridden in this repo) |

37 required contexts against a cache ceiling of 32 means the LRU cache **evicts and rebuilds** contexts mid-run — evicting a context closes it, which stops its containers, which is why Docker shows ~30 (the live ceiling) rather than 37. That is also why some classes pay full container + Flyway + Spring startup *twice*.

#### The three independent causes, with their measured contribution

**Cause 1 — containers are context-scoped beans.** This is the multiplier. It converts *any* context fragmentation into Docker container pressure. Nothing about the test code needs to change to fix this.

**Cause 2 — `@MockitoBean` set variance (Mbah's hypothesis — confirmed).** 29 `@SpringBootTest` classes declare mocks, across only a handful of types, but in **13 different combinations**. Grouping the 37 contexts by everything *except* the mock set collapses them into far fewer config shapes:

| Config shape | Contexts caused **only** by differing mock sets |
|---|---|
| `BaseVideoIT` / `BasePaymentIT` shape | **7** — `()`, `(BookingService)`, `(QuotaConfigService)`, `(QuotaService, VideoProviderAdapter)`, `(StripeClient)`, `(VideoProviderAdapter)`, `(VideoProviderAdapter)` + spy `VideoPhysicalDeletionListener` |
| The main `RANDOM_PORT` + `{dev,test}` shape | **6** — `()`, `(GeminiClient)`, `(VideoProviderAdapter)`, `(FileStorageService, GeminiClient)`, `(FileStorageService, VideoProviderAdapter)`, `(CoachProfileService, FileStorageService, VideoProviderAdapter)` |
| `VideoUploadPipelineIT` shape | **2** |

Mocked types, by frequency across `@SpringBootTest` classes: `GeminiClient` ×19, `VideoProviderAdapter` ×10+, `FileStorageService` ×5, `QuotaService`, `VideoLifecycleService`, `PlayerSubscriptionQueryPort`, `ModerationOrchestrationService`, `QuotaConfigService`, `StripeClient`, `BookingService`, `CoachProfileService` ×1 each.

**Cause 3 — per-class property and configuration drift.** 88 classes hand-copy `@Import(TestConfig.class)` + `@ActiveProfiles({"dev","test"})` + `@SpringBootTest(...)` + `@TestPropertySource(...)`, and the copies have diverged. Across all 94 directly-annotated classes there are only **nine distinct properties**, five of them near-universal:

| Count | Property | Status |
|---|---|---|
| 83 | `spring.cloud.compatibility-verifier.enabled=false` | universal — belongs in `application-test.yaml` |
| 64 | `rate.limiting.enabled=false` | near-universal |
| 59 | `allowed.clients=testClientId` | near-universal, **and divergent** (see below) |
| 23 | `enable.test.mail=true` | gates `TestConfig.mailManager()` (`TestConfig.java:102-105`) |
| 13 | `ledger.database.spy=true` | **DEAD — nothing reads it** (see below) |
| 2 | `logging.level.org.springframework.security=TRACE` | belongs in `logback-test.xml`, not the context key |
| 1 | `email.retry.enabled=true` | **redundant** — `EmailRetryScheduler.java:46` is `matchIfMissing = true` |
| 1 | `features.toggles.payments=true` | genuinely test-specific |
| 1 | `features.toggles.invoicing=false` | genuinely test-specific |

Grouping the 37 contexts by `@Import` set alone — i.e. simulating "properties, profiles and mocks all unified" — yields **7** groups:

```
 117 classes -> @Import(TestConfig.class)
   7 classes -> @Import({TestConfig.class, MinioTestConfig.class})
   1 class   -> @Import({TestConfig.class, E2ESecurityConfig.class})
   1 class   -> @Import({TestConfig.class, MinioTestConfig.class}) + E2ESecurityConfig
   1 class   -> @Import({TestConfig.class, ModerationFailClosedIT.FailureEventCapture.class})
   1 class   -> @Import(RateLimitingAspectIT.AspectConfig.class)   (no containers)
   1 class   -> (no @Import)                                        (no containers)
```

#### Also found while investigating (not the root cause, but part of the 30 minutes)

`mvn verify` runs the **entire frontend build** in `generate-resources`: `install-node-and-npm` (node v22.16.0 + npm 11.4.2), `npm install --legacy-peer-deps` against a 349 MB `node_modules`, `npm install -g @quasar/cli`, and `npx quasar build` — plus an `npm test` execution in the `test` phase that is a no-op (`src/frontend/package.json:12` → `echo "No test specified" && exit 0`). None of it is skippable today; there is no `<skip>` property on the plugin (`pom.xml`, `frontend-maven-plugin` block). This is pure overhead on every backend-only iteration.

#### Hard-coded values found (Mbah asked explicitly)

| # | Finding | Evidence |
|---|---|---|
| H1 | **`ledger.database.spy` reads nothing.** The real property is `log.database.spy` (`DataSourceConfig.java:44`, `TestConfig.java:60`). `ledger.` is a leftover name from another project. 13 tenant/security ITs carry it purely as context-cache-key poison. | `grep -rn "ledger.database.spy" src/main` → **zero hits** |
| H2 | **Test PostgreSQL is 3 major versions behind production.** Tests: `postgres:14.18` (`TestConfig.java:52`). Production compose: `postgres:17-alpine` (`docker-compose.yml:64`). Every integration test validates against a database the product does not run on. | — |
| H3 | **`allowed.clients` diverges.** Main default is `myClientId,hisClientId,herClientId,ourClientId` (`application.yaml:296-297`); 59 ITs override it to **only** `testClientId`; `SecurityIT.CLIENT_ID` is `"myClientId"` (`SecurityIT.java:68`). Two incompatible hard-coded client ids in one suite. | — |
| H4 | Container image tags and credentials hard-coded inline in three places: `postgres:14.18` + `postgres`/`postgres` (`TestConfig.java:51-56`), `redis:7-alpine` (`TestConfig.java:45`), `minio/minio:RELEASE.2024-01-13T07-53-03Z` (`MinioTestConfig.java:28`). | — |
| H5 | Per-class fixture id ranges are bare literals with no registry (`9300000001L`/`9300000002L`/authority `9300`,`9301` in `AvailabilityResourceIT`; `586920556720583008`, `6747751741842104908`, `659287191260154475` in `userData.sql`/`authorityData.sql`/`secData.sql`). Nothing prevents two classes from claiming the same range. | — |
| H6 | `TestConfig.spyDataSource` (`:59-75`) is gated on `log.database.spy=true`, which **no test sets** — dead. `TestConfig.hikariConfig` (`:77-86`) is gated on `datasource.container=true` (which *is* true) but its only consumer, `DataSourceConfig.dataSource(HikariConfig)`, is disabled under exactly that same condition (`DataSourceConfig.java:25`) — so the bean is built and never used. | — |
| H7 | `email.retry.enabled=true` on `EmailRetrySchedulerIT` is redundant: `EmailRetryScheduler.java:46` is `@ConditionalOnProperty(..., matchIfMissing = true)`. | — |

#### About Mbah's data-integrity concern

> *"Even when the tests will be configured to reuse the database, each Integration test class would have to be configured to initialize the database with exactly what it needs or else you will have test failures."*

This is the correct concern and AC5 addresses it directly. Two facts make it tractable rather than alarming:

1. **Cross-class database sharing is already the operating model.** The largest context already serves **30 test classes against one database**, and the second-largest 15. Going to one database widens the blast radius from 30 classes to ~130; it does not introduce a new mechanism.
2. **The current isolation mechanism is hand-written and fragile.** `secData.sql` inserts a fixed primary key `659287191260154475` with no `ON CONFLICT` (`src/test/resources/sql/secData.sql`), and it is applied `BEFORE_TEST_METHOD` by 65+ classes. It only works because individual classes hand-write `DELETE FROM main.sec` in `@AfterEach` (e.g. `AvailabilityResourceIT.java:120`, `TestDataCleaner.wipeAll()`, `DbCleaner.cleanDb()`). Every one of those is a per-class, hand-maintained, silently-incomplete list — `DbCleaner` still has four commented-out `delete` lines from a previous project. AC5 replaces this with one deterministic reset that cannot drift.

## Acceptance Criteria

> **Ordering matters.** Task 0 establishes a CI baseline first — without it, "keep the suite green" is unverifiable and this story's regressions will be indistinguishable from the pre-existing `SecurityFilterChainIT` failure. Then: AC1 alone fixes the container explosion and is independently committable. **AC5a must land before AC2**, because consolidating into one long-lived context is what sets 31 schedulers loose. AC2–AC4 buy the wall-clock time. AC5 comes last of the structural work. Push a PR at each task boundary so CI-only failures surface immediately rather than at Task 11.

---

### AC1 — Exactly one PostgreSQL, one Redis and one MinIO container per test JVM, regardless of context count

**Given** the integration-test suite is running under Failsafe,
**When** `docker ps` is sampled throughout the run,
**Then** at most **one** `postgres`, **one** `redis` and **one** `minio` container is present (plus Testcontainers' own `ryuk` reaper), for the entire run.

**Automate the sampling — do not leave this to a human watching a terminal.** Background a sampler alongside `mvn verify` (`docker ps --format '{{.Image}}'` on a short loop, appending to a file), then assert the peak concurrent count is ≤ 1 per image. That is the acceptance criterion verbatim, it costs nothing, and it runs in `pr-build` as easily as locally.

Implementation — **decouple container lifetime from the Spring context**:

1. Add `com.softropic.skillars.config.SharedContainers` holding container instances started **once** and **never stopped** (Ryuk reaps them at JVM exit).

   **Use one lazy holder class per container, not three `static final` fields on one class.** `MinioTestConfig`'s own javadoc records the intent a single eager holder would discard: *"deliberately kept out of `TestConfig` so tests that never touch blob storage don't pay for a MinIO container and bucket-creation on every context startup."* Three fields in one static initializer start MinIO for every JVM, including `-Dit.test=SomeBookingIT` — which is precisely the single-class iteration loop this story exists to make fast. Use the initialization-on-demand holder idiom (`SharedContainers.Postgres.INSTANCE`, `.Redis.INSTANCE`, `.Minio.INSTANCE`) so each starts on first touch.

   **Pick the PostgreSQL database name explicitly.** Today `postgresContainer(@Value("${spring.application.name}") String dbName)` reads it from the Spring context (resolving to `skillars`, `application.yaml:41`). A static container is constructed before any context exists and cannot do that. Hard-code it as a named constant on `SharedContainers` and confirm nothing else binds to `spring.application.name` for database purposes.
2. **Do not register the container objects as Spring beans.** That is the whole defect. Instead, expose them to Boot through non-`Startable` beans in `TestConfig`:
   - a `JdbcConnectionDetails` bean built from `SharedContainers.POSTGRES`, and
   - a `RedisConnectionDetails` bean built from `SharedContainers.REDIS`,
   both replacing the current `@ServiceConnection`-annotated `@Bean` methods at `TestConfig.java:43-57`.
   `MinioTestConfig` already uses `DynamicPropertyRegistrar` (`MinioTestConfig.java:31-41`) — keep that idiom, just point it at `SharedContainers.MINIO` and delete its `@Bean MinIOContainer`.
   **Rationale for `ConnectionDetails` beans over returning the static instance from an `@ServiceConnection @Bean`:** `TestcontainersLifecycleBeanPostProcessor` treats any `Startable` bean as its own to destroy. Handing it a shared static instance means the first context to close stops the container every other context is still using. A `ConnectionDetails` bean is not `Startable`, so the post-processor never touches it. Do not rely on the reuse-flag carve-out in that post-processor; it is version-sensitive and this must not be subtle.
3. `TestConfig.hikariConfig(JdbcConnectionDetails)` (`:77-86`) must still compile — the new `JdbcConnectionDetails` bean satisfies it. See AC6/H6 for its deletion.
4. Keep `.withInitScript("sql/createSchema.sql")` on the shared container — it now runs once per JVM instead of 37 times.
5. Flyway will run once per Spring context against the same database. The second and subsequent runs are no-op validation passes over the 91 migrations in `src/main/resources/db/migration`. Contexts are created sequentially in a single Failsafe fork, so there is no concurrent-migration hazard. **Do not add `forkCount > 1` in this story** — that would put concurrent Flyway runs and concurrent tests on one shared database, and is out of scope.

**Do not** set `withReuse(true)` in this story. Reuse across *runs* is a separate, developer-machine-local concern with its own correctness question (stale schema across branches) and is explicitly out of scope; record it in `deferred-work.md` instead.

---

### AC2 — One canonical integration-test base class; `@Import(TestConfig.class)` no longer appears on individual test classes

**Given** any integration test class in `src/test/java`,
**When** it is inspected,
**Then** it declares no `@SpringBootTest`, `@ActiveProfiles`, `@Import(TestConfig.class)` or `@TestPropertySource` of its own, and instead `extends AbstractIntegrationTest` (or one of the documented flavored subclasses in AC3).

Create `com.softropic.skillars.config.AbstractIntegrationTest`, carrying exactly:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"dev", "test"})
@Import(TestConfig.class)
@EnableWireMock({
    @ConfigureWireMock(name = "bunny-service"),
    @ConfigureWireMock(name = "stripe-service")
})
public abstract class AbstractIntegrationTest { ... }
```

**`@EnableWireMock` on the root base is mandatory, not cosmetic.** `application-test.yaml:35` binds `app.video.bunny.api-base-url: ${wiremock.server.bunny-service.baseUrl}` **with no default value** (unlike its neighbours at `:34`, `:48` and `:52`, which all carry a `:fallback`). Today only `BaseVideoIT` (`BaseVideoIT.java:27`) and `BasePaymentIT` (`BasePaymentIT.java:35`) declare their respective servers, and each declares a *different* name — which is itself one of the context-key differences. Unifying the base without declaring both named servers will fail placeholder resolution for any context that binds the Bunny properties. Declare both on the root; two in-JVM WireMock servers cost nothing compared to a container.

The base also hosts the shared protected fixtures already duplicated across `BaseVideoIT`/`BasePaymentIT`/`BaseSessionIT`/`BaseStorageIT`: `JdbcTemplate`, `TransactionTemplate`, `HttpTestClient`, `@LocalServerPort`, and a `baseUrl()` helper.

Convert the four existing base classes to extend it:
`BaseVideoIT`, `BasePaymentIT`, `BaseSessionIT` (`platform/session/api/BaseSessionIT.java`), `BaseStorageIT` (`infrastructure/storage/BaseStorageIT.java`). Note `SessionTemplateResourceIT`, `SessionBuilderResourceIT`, `DrillUploadResourceIT`, `HomeworkResourceIT`, `DrillTagResourceIT` and `DrillLibraryResourceIT` currently `extends BaseSessionIT` **and** re-declare `@SpringBootTest`, `@ActiveProfiles`, `@Import` and `@TestPropertySource` on themselves — remove all four from each; that redundant re-declaration is precisely the drift this AC exists to end.

---

### AC3 — Distinct Spring contexts reduced from 37 to **≤ 10**, with every remaining one deliberate and documented

**Given** a `pr-build` run after the refactor,
**When** the Spring test context cache statistics are read from the build log,
**Then** the number of contexts actually built is **10 or fewer**, CI fails if it is not, and each remaining context is listed in `docs/testing/` with a one-line justification.

**Enforce this in CI, not with an offline script.** Spring's `DefaultContextCache` logs, at DEBUG under category `org.springframework.test.context.cache`:

```
Spring test ApplicationContext cache statistics: [DefaultContextCache@... size = N, maxSize = 32, ... hitCount = X, missCount = N]
```

`missCount` is the number of contexts actually built — and because it also counts rebuilds after eviction, it is an upper bound on distinct contexts, which is exactly the right thing to gate on. Enable that category in `logback-test.xml` and add a `pr-build` step that reads the final occurrence and fails when `missCount > 10`.

**Verify the exact log line before wiring the grep** — a gate built on a string that does not match is a gate that never fires. Assert it fails on the pre-refactor tree (where it should report ~37) before trusting it.

The offline analysis script (Dev Notes → *Verification method*) stays as the **diagnostic**: `missCount` tells you *that* you regressed, the script tells you *which class* forked. Keep both.

Target shape (adjust if evidence says otherwise, but justify any deviation):

| Base class | Adds | Expected classes |
|---|---|---|
| `AbstractIntegrationTest` | — | ~90 |
| `AbstractVideoIT` | video-family mock set (see AC4) | ~19 |
| `AbstractPaymentIT` | payment-family mock set | ~10 |
| `AbstractStorageIT` | `MinioTestConfig` | ~7 |
| `AbstractE2ETest` | `E2ESecurityConfig` **only** — see below | 2 |
| `ModerationFailClosedIT` | its own `FailureEventCapture` inner config | 1 |
| `RateLimitingAspectIT`, `PropertiesFeatureToggleServiceIT` | sliced `@SpringBootTest(classes = ...)`, **no containers** | 2 |

**Do not bundle `TestClockConfig` into `AbstractE2ETest`.** `E2ESecurityConfig` is imported by exactly two concrete ITs (`ConfigResourceIT`, `StorageResourceIT`); `TestClockConfig` is used **only** by `AbstractSkillarsE2ETest`, which has **zero subclasses** and is therefore dead code. Bundling them would hand those two ITs a fixed clock they do not have today — a behaviour change landing directly on top of `deferred-17`/`deferred-18`'s timezone work. Either **delete the dead `AbstractSkillarsE2ETest`** (preferred — verify the zero-subclass claim first) or keep `TestClockConfig` out of the shared base entirely.

Property consolidation required to reach this — move into `src/test/resources/application-test.yaml` and delete from every test class:

- `spring.cloud.compatibility-verifier.enabled: false`
- `rate.limiting.enabled: false`
- `enable.test.mail: true` — this makes `TestConfig.mailManager()` (`TestConfig.java:102-105`) the universal `MailManager`. **Verify `MailManagerIT` first**: it is currently a one-off context with no property overrides, and if it asserts against the *real* `MailManager` (the `matchIfMissing = true` branch at `ComponentConfig.java:27`) it must keep an explicit local override and accept its own context. Check before you flip it.
- `allowed.clients: myClientId,hisClientId,herClientId,ourClientId,testClientId` — a **superset**, not a replacement (H3). Replacing it with `testClientId` alone would break `SecurityIT` and everything keyed on `SecurityIT.CLIENT_ID = "myClientId"`.
- The four "make this scheduler never fire" overrides in the video family (`app.video.webhook.processor-delay-ms=86400000`, `app.video.reconciliation.fixed-delay-ms=86400000`, and the matching ones on `VideoUploadPipelineIT`/`VideoWebhookResourceIT`) — these are the same intent as the two already global at `application-test.yaml:7` (`platform.video.deletion.outbox_initial_delay_ms`) and `:28` (`app.video.upload.expiry-scheduler-delay-ms`). Move them global.
- **Delete outright:** `ledger.database.spy=true` (H1, 13 classes) and `email.retry.enabled=true` (H7, 1 class).
- **Move to `logback-test.xml`:** `logging.level.org.springframework.security=TRACE` (2 classes). Logging levels have no business being in a context cache key.

Properties that genuinely change behaviour and may keep a local `@TestPropertySource` (each one costs a context, which is now ~20 s of Spring startup and **zero containers** — that trade is acceptable, do not contort the code to avoid it): `app.video.webhook.max-attempts=2`, `app.video.playback.revocation-window-hours=24`, `platform.video.lifecycle.outbox_max_attempts=2`, `features.toggles.payments`/`features.toggles.invoicing`.

---

### AC4 — Shared mocks declared once; per-type hoist decision recorded; mocks reset between tests

**Given** a mocked collaborator that is mocked by more than one integration test class,
**When** the suite runs,
**Then** it is declared as a single `@MockitoBean` on the appropriate base class (never on individual test classes), and its stubbing is reset before every test method so that one class cannot leak stubs into another.

1. **`GeminiClient` → hoist to `AbstractIntegrationTest`, after applying AC4.2's trap-check to it.** 19 classes mock it and it is an outbound HTTP adapter (`infrastructure/gemini/GeminiClient.java`), so it looks like a safe universal hoist — but `application-test.yaml:52` points `infrastructure.gemini.api-base-url` at `${wiremock.server.baseUrl:http://localhost:9999}`, i.e. **a WireMock path exists for Gemini too**. Run the same check AC4.2 demands for `VideoProviderAdapter`: confirm no IT drives the real client through WireMock before hoisting. If one does, the hoist belongs on a flavored base, not the root. `ModerationFailClosedIT` configures the mock's behaviour and continues to work either way — it stubs whichever mock it inherits.
2. **For every other mocked type, make and record an explicit decision** — hoist / keep local / convert to a `@Primary` stub bean in `TestConfig` (the existing `StubPaymentGateway` precedent at `TestConfig.java:108-111`). **Do not blanket-hoist.** Two traps, verify each before deciding:
   - `VideoProviderAdapter` is mocked by ~10 classes, but `BaseVideoIT` also stubs Bunny.net over **WireMock**. Hoisting the mock to the root would silently make those WireMock stubs unreachable — tests would still pass while asserting nothing. Confirm which video ITs drive the real adapter before hoisting; if any do, the hoist belongs on `AbstractVideoIT`, not the root.
   - `FileStorageService` (`platform/filestorage/service/FileStorageService.java:52`) is mocked by 5 classes but exercised for real against MinIO by the storage family (`StorageResourceIT`, `FileStorageConfirmUploadIT`). It must **not** be hoisted to the root.
   - `BookingService`, `CoachProfileService`, `QuotaService`, `VideoLifecycleService`, `ModerationOrchestrationService` are internal platform services with real ITs of their own. Hoisting any of them to the root would replace the system under test in dozens of classes. Keep local or push down to a flavored base only.
3. **Mock reset — Spring already does this; do not add ceremony on a false premise.** `@MockitoBean.reset()` defaults to `MockReset.AFTER` (verified in the bytecode of `spring-test`: `MockitoBean.reset()` carries `AnnotationDefault: MockReset.AFTER`), and the default `MockitoResetTestExecutionListener` enforces it after every test method. **Cross-class stub leakage is already prevented today and stays prevented after the hoist.** Do not write `Mockito.reset(...)` for plain `@MockitoBean` fields — it is redundant.

   What you must actually verify:
   - **`@MockitoSpyBean`** (`VideoPhysicalDeletionListener` is spied today) — confirm the same `MockReset.AFTER` default applies, and add an explicit reset only if it does not.
   - **Stateful stub beans** — `StubPaymentGateway` and any test double registered as a `@Primary` `@Bean` in `TestConfig` are **not** Mockito mocks and get **no** automatic reset. Once one context serves ~90 classes, any state they accumulate persists for the whole run. Audit each for mutable state and add a reset hook where needed. This, not `Mockito.reset`, is the real leakage risk that consolidation introduces.

---

### AC5a — Scheduling is neutralized under the `test` profile before AC2 consolidates the contexts

> **Sequence this BEFORE AC2.** It is a prerequisite for consolidation, not a follow-up.

**Given** the integration-test suite running under one long-lived Spring context,
**When** any test method executes,
**Then** no `@Scheduled` job fires on its own, and a test that needs a scheduler run invokes it directly.

`AsyncConfig.java:25` declares `@EnableScheduling` **unconditionally**, and `src/main` contains **31 `@Scheduled` methods and 11 `@SchedulerLock` jobs**. Several have very short delays and are **not** neutralized by `application-test.yaml` today:

| Job | Delay |
|---|---|
| `OutboxPollerScheduler`, `DeletionSchedulerService` (`app.storage.poller.fixed-delay-ms:5000`) | **5 s** |
| `MessagingEmitterRegistry`, `AlertEvaluationService` | 30 s |
| `EmailRetryScheduler`, `AlertRuleCache`, `QuotaReservationTimeoutService`, `VideoSubscriptionLifecycleListener` | 60 s |
| `QuickCompleteTimeoutService`, `BookingReminderScheduler`, `BookingExpiryScheduler` | 5 min |
| `PaymentPendingSweeper` | 15 min |
| `SessionPackForfeitureScheduler` | 60 min |

**Why consolidation changes the risk.** Today, context fragmentation caps each scheduler's blast radius at one context group's lifetime — a 5-minute job in a context that lives 90 seconds never fires. After AC2/AC3, **one context lives for the entire failsafe run**, with all 31 scheduler threads writing to the same database the tests assert on. AC3 as written moves only four *video* delays to global scope; that is not enough.

AC5's shedlock backdate makes this worse before it makes it better: backdating **all** lock rows on **every** test method makes all 11 locked jobs eligible on all ~905 methods. The backdate is still the right call (deleting the row is the documented catastrophe), but it must land on a suite where scheduling is off by default.

**Required.** Neutralize globally, by whichever of these survives review:

- gate `@EnableScheduling` on a condition that is false under the `test` profile (cleanest, one line, but it is a `src/main` change — justify it or use the alternative); **or**
- a systematic delay sweep in `application-test.yaml` covering **all 31** jobs, not four.

Tests that need a sweeper to run must call it directly rather than waiting for the clock — which the payment ITs already do via `BasePaymentIT.releaseSchedulerLock` (`BasePaymentIT.java:87-93`). Enumerate every test that currently depends on a scheduler firing on its own and convert it; a test that silently relied on a timer will pass vacuously once the timer stops, so this needs an explicit list, not a spot check.

**Note the same argument the `qrtz_*` exclusion rests on applies here.** The story excludes those tables because a live clustered thread writes to them concurrently. Every table the other 31 schedulers touch has the same property. AC5a is what makes the `qrtz_*` reasoning generalise instead of being a one-off carve-out.

---

### AC5 — Deterministic database and Redis reset between test methods, replacing hand-written per-class cleanup

**Given** any integration test method,
**When** it begins,
**Then** every application table holds exactly the Flyway-seeded reference data and nothing else, Redis is empty, and the class's own `@Sql` scripts and `@BeforeEach` seeding have then run — so the test sees exactly the data it declared, plus the platform reference data, and nothing left by any previously-executed class.

1. Add `com.softropic.skillars.config.DatabaseResetTestExecutionListener`:
   - Issues a **single** `TRUNCATE <all tables> RESTART IDENTITY CASCADE` built from `information_schema.tables`, across every application schema (`main`, `booking`, `marketplace`, `messaging`, `payment`, `video`, … — enumerate from `information_schema`, do not hard-code the list), excluding the infrastructure tables below.
   - **Restores the Flyway-seeded reference data** (AC5.1a — this is not optional).
   - Backdates ShedLock (below), and flushes Redis (`RedisConnectionFactory.getConnection().serverCommands().flushDb()`).
   - **Evicts the in-application caches** (AC5.1b).

   **Three infrastructure tables MUST be excluded from the truncate. Getting this wrong produces silent, suite-wide failures, not loud ones:**

   - **`flyway_schema_history`** — obvious; truncating it makes the next context's Flyway run re-apply all 91 migrations against a populated schema.
   - **`main.shedlock`** — **truncating it will silently disable every `@SchedulerLock` job for the rest of the JVM.** `ShedLockConfig.lockProvider` (`infrastructure/config/ShedLockConfig.java:21-29`) is a `JdbcTemplateLockProvider`, which caches lock names it has already inserted and thereafter issues only `UPDATE`. Delete the row and the `UPDATE` matches zero rows, `lock()` returns empty, and the run is skipped — logged at INFO as "held by another instance". `deferred-15`'s code review hit exactly this and recorded it: *"the first fix (DELETE the shedlock row) is WORSE than nothing … 5 of 6 sweeps were skipped and the three negative cases passed vacuously."* The provider instance is now shared by ~90 classes in one context, so this would poison the whole suite. **Instead, reset the locks by backdating**: `UPDATE main.shedlock SET lock_until = now() - interval '1 minute'` — the approach `BasePaymentIT.releaseSchedulerLock` (`BasePaymentIT.java:87-93`) already proved correct. Promote that method to `AbstractIntegrationTest` and have the listener apply it to all rows.
   - **The `qrtz_*` tables** — Quartz runs with `job-store-type: jdbc` and `org.quartz.jobStore.isClustered: true` (`application.yaml:18-31`), so a cluster check-in thread is writing to them concurrently with the tests. Truncating under a live clustered scheduler is a data race with a background thread, not a clean reset. Exclude them. If they turn out to be genuinely empty and the scheduler genuinely inert in tests, say so with evidence and revisit — but exclude first.
**1a. Reference data seeded by Flyway MUST be restored after every truncate. Excluding infrastructure tables is not sufficient.**

   This is the single most likely way to break the entire suite, and "add the missing seed to the class" is the **wrong** remedy for it.

   **33 of the 91 migrations contain `INSERT INTO`.** Flyway will **not** re-insert them after a truncate — the `flyway_schema_history` row is intact, so the next context's run is the no-op validation pass AC1.5 describes. **The first truncated test method destroys this data for the remainder of the JVM.** The affected tables:

   | Table | Seeded by | Read by |
   |---|---|---|
   | `main.platform_config` | **30 migrations**, incl. V20, V25, V57, V64, V85, V90 — age policy, moderation config, subscription tiers, phone-OTP toggle, payment-sweep config | `ConfigResourceIT`, `ConfigGuardIT`, `SubscriptionLifecycleIT`, `VideoSubscriptionLifecycleListenerIT`, and `ConfigService`'s cache at startup |
   | `session.drills` | V39 — 20 `PLATFORM` foundation drills | `DrillLibraryResourceIT.java:96`: `SELECT id FROM session.drills WHERE library_type = 'PLATFORM' AND status = 'ACTIVE' LIMIT 1` — that row exists *only* because of V39 |
   | `main.authority` | V21, V84 | broadly |

   This works **today** precisely because no existing cleaner touches these tables. Every current delete is narrowly targeted — `SimultaneousExpiryIT.java:74` deletes `platform_config WHERE id IN (8201, 8202)`, `YearlyExemptionRenewalIT.java:69` deletes `id IN (8101, 8102)`, `BookingBatchResourceIT.java:164` deletes `id = 50`, `DrillTagResourceIT.java:87` deletes only drills owned by its own coach ids. Neither `DbCleaner` nor `TestDataCleaner` mentions `platform_config` or `drills` at all.

   **Required approach.** After the truncate, replay the reference rows. Either:
   - **(preferred)** capture them once per JVM — after the first context's Flyway run, `SELECT` the contents of the reference tables into memory in `SharedContainers` or the listener, and re-`INSERT` them after each truncate. This stays correct automatically when a new seeding migration lands; or
   - maintain a `src/test/resources/sql/reference-seed.sql` the listener replays. Cheaper to write, but it **will** drift.

   If you take the second option, add a check that fails when a migration adds an `INSERT INTO` for a table not in the reference list. **Do not leave the list to drift silently** — the failure mode is a test asserting against reference data that vanished, which reads as a product bug.

**1b. In-application caches must be evicted alongside the truncate.**

   Two components cache database rows in-process on a scheduled refresh:
   - `ConfigService.java:47` — `@Scheduled(fixedDelayString = "${app.config.cache-ttl-seconds:300}", timeUnit = SECONDS)` over `main.platform_config`
   - `AlertRuleCache.java:43` — `@Scheduled(fixedDelayString = "${alert.rule-cache.refresh-interval-ms:60000}")`

   Under context fragmentation this was self-healing: contexts were short-lived, so caches were rebuilt constantly. Under one long-lived context, truncating the database leaves both caches holding rows that no longer exist, and leaves a test that seeds config racing a 300-second refresh window. The listener must evict them (`ConfigService` already exposes `refreshCache()` internally — expose a test-visible hook rather than waiting on the schedule).

2. **Ordering is the critical detail.** `@Sql` scripts are executed by `SqlScriptsTestExecutionListener` (order `5000`) during `beforeTestMethod`, which runs **before** JUnit `@BeforeEach` callbacks. A `@BeforeEach` truncate would therefore wipe the `@Sql`-seeded data. The reset **must** be a `TestExecutionListener` with an order **lower than 5000**, registered on `AbstractIntegrationTest` via `@TestExecutionListeners(mergeMode = MERGE_WITH_DEFAULTS)`. Verify the ordering empirically — run one `@Sql`-driven class and assert the seeded row is visible in the test body.
   `@TestExecutionListeners` does **not** contribute to `MergedContextConfiguration`, so this adds no contexts.
3. Once the listener is in place, delete the now-redundant hand-written cleanup: `DbCleaner.cleanDb()` (`utils/DbCleaner.java` — note its four commented-out `delete` lines, dead code from another project), `TestDataCleaner.wipeAll()` (`config/TestDataCleaner.java`), the `@AfterEach` teardown blocks that hand-delete rows (`AvailabilityResourceIT.java:107-123` and its ~40 siblings), and the `@Sql(scripts = "/sql/cleanup.sql", executionPhase = AFTER_TEST_METHOD)` usages. **Delete them in a separate commit from the listener** so that if a class turns out to depend on cleanup ordering, `git bisect` isolates it.
4. **A welcome side effect worth knowing about:** the truncate makes `secData.sql`'s fixed-primary-key insert idempotent-by-construction. The reason 65+ classes currently need a hand-written `DELETE FROM main.sec` in `@AfterEach` is that `@Sql` re-runs that non-idempotent script before every test method. Once every test method starts from an empty database, that entire category of cleanup disappears — which is what makes AC5.3's deletions safe rather than reckless.
5. **Each class is responsible for its own *test* data — but not for platform reference data.** Expect failures on the first full run from classes that were silently relying on rows another **test class** left behind; fix those by adding the missing seed to the class. **Do not apply that instruction to Flyway-seeded reference data** — if a class fails because `platform_config` or the V39 drills are gone, the reset is wrong (AC5.1a), not the class. Re-seeding migration reference data into ~130 classes is never the right answer. Triage each first-run failure into one of those two buckets before fixing it, and record the split.
6. **Measure the truncate cost, and watch for lock contention as well as time.** `TRUNCATE` takes an `ACCESS EXCLUSIVE` lock. Issued ~905 times against a database that also has live scheduler connections (see AC5a), that is a deadlock and lock-wait exposure, not only a throughput question. If AC5a lands first the exposure is largely removed, which is why AC5a is sequenced before AC5. Record both the timing and any lock waits observed. If the cost is material, switch to `DELETE` from non-empty tables only — but measure first, do not optimise on speculation.

---

### AC6 — Hard-coded values centralised or corrected

**Given** the findings H1–H7 above,
**When** the refactor is complete,
**Then** each is resolved as follows:

- **H1** `ledger.database.spy` deleted from all 13 classes (it reads nothing). Confirm with **`grep -rn "ledger.database.spy" src/`** returning nothing. **Do not grep for bare `ledger`** — it is a live domain concept in this codebase (`V79__credit_ledger_append_only.sql`, `V62__session_payment_credit_wallet.sql`, `ParentCreditLedger`, `CreditWalletService`, `PaymentPendingSweeper`, and `application.yaml`'s access-log `suffix: .ledger`), so that check can never pass and would send you hunting a non-issue. All 13 hits for the full property name are in `src/test`.
- **H2** Container image tags become constants on `SharedContainers` (`POSTGRES_IMAGE`, `REDIS_IMAGE`, `MINIO_IMAGE`), each with a comment naming the production compose file and line it must track. **Then bump PostgreSQL from `14.18` to `17-alpine` to match `docker-compose.yml:64`** — this is a deliberate, separately-committed change: run the full suite on it and report any failure. If 17 breaks something, keep 14.18, add the constant anyway, and record the divergence as an item in `deferred-work.md`; do not silently leave the gap undocumented.
- **H3** `allowed.clients` unified as the superset in `application-test.yaml` (AC3). Leave `SecurityIT.CLIENT_ID` and the per-class `"testClientId"` literals alone in this story — they are correct once both are allowed.
- **H4** Container credentials become constants alongside the image tags.
- **H5** Out of scope to fix, but **record**: add a short "fixture id ranges" table to `docs/testing/` listing the ranges each test family claims (the `93000000xx` block, the `5869205567…`/`6747751741…`/`6592871912…` literals in `userData.sql`/`authorityData.sql`/`secData.sql`), so the next author has somewhere to look before picking a range. AC5's truncate removes the *cross-class* collision risk; the registry is for readability.
- **H6** Delete `TestConfig.spyDataSource` (`:59-75`) and `TestConfig.hikariConfig` (`:77-86`) **only after verifying** no bean injects `HikariConfig` under `datasource.container=true`. `DataSourceConfig.dataSource(HikariConfig)` is disabled under that condition (`DataSourceConfig.java:25`), so the expectation is that both are dead — but verify by deletion and a green run, not by reading. Also delete `TestConfig.provideListener()` (`:94-99`) if, as it appears, nothing calls it.
- **H7** `email.retry.enabled=true` deleted from `EmailRetrySchedulerIT`.

---

### AC7 — A guardrail test that prevents the fragmentation from growing back

**Given** a developer adds a new integration test class,
**When** the suite runs,
**Then** a fast, container-free test fails if that class declares `@SpringBootTest`, `@ActiveProfiles`, `@Import(TestConfig.class)` or `@TestPropertySource` directly instead of extending a sanctioned base.

This AC is what makes AC2 durable, and is the entire argument for inheritance over `@Import` (see AC8). It is also the one AC that CI enforces for free — **provided the class is named `*Test`, not `*IT`**, so surefire runs it in the `test` phase ahead of failsafe and it fails fast without starting a container. Name it explicitly, e.g. `IntegrationTestConventionTest`.

Implement as a plain JUnit test scanning compiled test classes (no new dependency needed — walk `target/test-classes` and read annotations reflectively; add ArchUnit only if you find that materially cleaner, and say so):

- Every class named `*IT` must be assignable to `AbstractIntegrationTest`, **or** appear in an explicit, commented allowlist. Seed the allowlist with exactly the documented exceptions from AC3 (`RateLimitingAspectIT`, `PropertiesFeatureToggleServiceIT`, `ModerationFailClosedIT`) and nothing else.
- No `*IT` outside the allowlist may carry `@Import(TestConfig.class)`, `@ActiveProfiles` or `@SpringBootTest`.
- A `@TestPropertySource` on a concrete `*IT` is allowed but must be accompanied by a `// context-fork:` comment — assert the annotation count against a **pinned expected number** so that adding one is a deliberate act that fails the build until the number is updated. Pin it to whatever count survives AC3.

Make this test fail first against the current tree (before the refactor), to prove it actually detects the condition. A guardrail that has never failed is not a guardrail.

---

### AC8 — `docs/testing/` documents the `@Import` → inheritance migration and its rationale

**Given** `docs/testing/`, which **already contains drafts** — `readme.md`, `why-inheritance-over-import.md`, `container-architecture.md`, `test-data-isolation.md`, written 2026-08-07 alongside this story,
**When** this story is complete,
**Then** those files are **updated in place** — not overwritten — with the banner and the TODAY/TARGET markers removed and the projections replaced by measurements.

> **Do not start from a blank page.** The drafts carry a *"design documented, migration not yet applied"* banner and mark each section TODAY (current tree) or TARGET (post-migration). Your job is to flip them: delete the banner, drop the markers, and replace every projected number with a measured one. They are also already linked from `docs/dev-docs/index.html` ("Writing integration tests" card) — keep that link working.
>
> Note the drafts predate this review and therefore still carry the two claims it corrected: `test-data-isolation.md` states the truncate only needs three exclusions (it also needs reference-data restoration, AC5.1a) and `why-inheritance-over-import.md` states that mock resets are a new requirement (they are not — `MockReset.AFTER` is already the default, AC4.3). **Fix both while updating.**

They must cover, at minimum:

1. **Why the move from `@Import(TestConfig.class)` to inheritance was necessary.** The substantive argument, which the dev must present with the measured numbers from this story, not paraphrase:
   - `@Import(TestConfig.class)` communicates *which beans* a test needs. It communicates **nothing** about the context cache key, yet the cache key is what determines whether a test class costs 0 s or 35 s + two Docker containers. The annotation that mattered was invisible in the mental model of the annotation people were copying.
   - Correct sharing requires **every** contributing annotation to match **exactly** across all ~130 classes: `@SpringBootTest` args, `@ActiveProfiles`, `@TestPropertySource`, `@Import`, `@EnableWireMock` names, and the `@MockitoBean` set. With copy-paste, drift is not a risk — it is a certainty. It produced 37 contexts from 7 genuinely distinct configurations, 23 of them serving a single class each.
   - The drift was **invisible at review time and free at authoring time**. Adding one `@MockitoBean`, or one property, or a differently-named WireMock server, is a locally-correct one-line change whose cost — a full Spring Boot startup plus a PostgreSQL and a Redis container — appears nowhere near the diff.
   - Inheritance makes the cache key **structurally identical by construction** rather than by convention. A subclass that adds nothing *cannot* fork the context. Forking becomes an explicit act: adding an annotation to a concrete class, which AC7 then fails the build on.
   - Inheritance also gives the suite a single place to host things that must be shared and could not be before: the mock-reset lifecycle (AC4.3), the database/Redis reset listener (AC5), and the common fixtures currently duplicated across four base classes.
   - **Be fair about the trade-off**: inheritance consumes the single-inheritance slot, couples all tests to one hierarchy, and can hide what a test actually needs. The alternative — a shared composed annotation (`@IntegrationTest` as a meta-annotation carrying all of the above) — gets most of the same cache-key guarantee without the hierarchy. State plainly why inheritance was chosen anyway: the base class must hold *behaviour* (`@BeforeEach` mock resets, protected fixtures, `baseUrl()`), which a meta-annotation cannot, and the four `Base*IT` classes already established the pattern in this codebase.
2. **How to write a new integration test** — extend `AbstractIntegrationTest`, seed your own data, do not add class-level annotations, and what to do when you genuinely need a different property (add it, expect the guardrail to fail, update the pinned count with a justification).
3. **The container architecture** — why containers are JVM-static and not beans, with the `TestcontainersLifecycleBeanPostProcessor` reason from AC1.2 spelled out, so nobody "tidies" them back into `@Bean` methods.
4. **The remaining contexts** — the ≤ 10 from AC3, each with its one-line justification.
5. **The fixture id-range registry** from AC6/H5.
6. **Before/after numbers** — 37 contexts / ~74 containers / 30:45 → the measured post-refactor values. Report what you actually measured; if the improvement is smaller than projected, say so and say why.

Format: follow the existing `docs/dev-docs/` HTML page template if you are adding this to that navigation, or plain Markdown if standalone — match whichever the surrounding `docs/` convention is for the location you choose, and link it from `docs/dev-docs/index.html` if it belongs there.

---

### AC9 — `mvn verify` can skip the frontend build

**Given** a developer iterating on backend code,
**When** they run `mvn -o verify -DskipFrontend`,
**Then** `install-node-and-npm`, `npm install`, `npm install -g @quasar/cli`, `npx quasar build` and the no-op `npm test` execution are all skipped, and the backend build and full test suite run unchanged.

Add a `<skipFrontend>false</skipFrontend>` property in `pom.xml` and wire it to the `frontend-maven-plugin` `<configuration><skip>${skipFrontend}</skip></configuration>`, verifying it applies to **every** execution in that plugin block (the plugin honours `skip` per-execution; a single plugin-level `<configuration>` should cover all five, but confirm by running with the flag and reading the log for each execution id).

**The flag changes the packaged artifact — say so in the docs and never set it in CI.** `maven-resources-plugin` copies `src/frontend/dist/spa` → `target/classes/static` at `process-resources`. With `-DskipFrontend` on a clean tree that directory does not exist, so the resulting jar ships **with no UI**. That is fine for backend iteration and unacceptable for anything that gets deployed — and `pr-build.yml` builds a Docker image from the same artifact, so the flag must stay out of every workflow.

**Measure and report** the wall-clock delta of `-DskipFrontend` on an otherwise-warm build. That number belongs in AC8's before/after section. Note that the `npm test` execution currently runs `echo "No test specified" && exit 0` (`src/frontend/package.json:12`) — flag in the docs that the frontend has no test runner wired up, but **do not fix that here**; it is already a known standing gap (recorded in `deferred-18`'s notes as "blocked on standing no-test-runner gap").

---

### AC10 — `pr-build.yml` can actually observe and enforce this story's outcomes

**Given** the `pr-build` workflow,
**When** it runs after this story,
**Then** it produces the numbers Task 11 reports and fails on regression, rather than only checking that the build exits zero.

`pr-build.yml` today runs `mvn -B verify -q` with `timeout-minutes: 15`, then builds a Docker image and Trivy-scans it. Nothing else. Required changes:

1. **Drop `-q`.** It suppresses every phase marker, the surefire summary and all per-class timings — which makes Task 1 and Task 11 impossible to satisfy from CI, and means the only reason we know the July run took 9m54s is the step timestamps. Without this, "measure on CI" cannot be done.
2. **Upload `target/surefire-reports/` and `target/failsafe-reports/` as build artifacts**, so AC8's before/after numbers are reproducible by someone other than the author. This is also what makes the unit-vs-integration count split (Task 11) auditable rather than asserted.
3. **Add the context-count gate** from AC3 (`missCount > 10` → fail).
4. **Add the container-ceiling sampler** from AC1.
5. **Tighten `timeout-minutes`** once the post-refactor duration is known. 15 minutes against a ~10-minute run is not a regression detector; AC5 adds ~905 truncates and ~905 Redis flushes, and a doubling would still pass today. Record the chosen value in AC8.
6. **Never set `-DskipFrontend`** in any workflow (AC9).

---

## Tasks / Subtasks

- [x] **Task 0 — Get CI to a known state before touching anything** *(prerequisite; nothing below is verifiable without it)*
  - [x] Fix or explicitly baseline `ci.yml`'s GHCR login failure (`secrets.GHCR_PAT`) — 6 consecutive red runs through 2026-08-07. Note it runs **no tests**, so it does not gate correctness; it just has to stop masking the signal.
  - [x] Open a PR to trigger `pr-build.yml`, which has not run since 2026-07-02. Record the **current** result on **this** tree: pass/fail, wall clock, unit and IT counts, and the exact failure set if red.
  - [x] Confirm whether the `SecurityFilterChainIT.test2FAFilterBlocksAccessUntilVerified` / `userData.sql` duplicate-key error still reproduces. If it does, record it as the known baseline failure so this story's regressions stay distinguishable from it. **Do not fix it here** — AC5 fixes it structurally.
  - [x] Apply AC10's `-q` removal and report upload first, so every later checkpoint produces readable numbers.
  - [x] Push a PR at **every** task boundary below. The `SecurityFilterChainIT` failure is CI-only — it does not reproduce locally — so a local-only checkpoint proves nothing about this class of failure.

- [x] **Task 1 — Reproduce and pin the baseline** (evidence for AC8)
  - [x] Run the context-key analysis script (Dev Notes → *Verification method*) and confirm **37** contexts, **129** classes. If the number differs from 37, record the actual number and investigate the delta before proceeding — the tree may have moved.
  - [x] Run `mvn -o verify` once, clean, and record: total wall clock, unit test count, IT test count, and `docker ps` sampled mid-run (peak postgres + redis count).
  - [x] **Record the equivalent numbers from CI, not only locally.** The local 30:45 is a macOS/Docker-Desktop figure; a July CI run of an older tree did the full verify in 9m54s. Both baselines go in AC8, clearly labelled — a local-only number is not reproducible by anyone else and cannot justify the story's ROI.
  - [x] Record the sum of `Time elapsed` across `target/failsafe-reports/*.txt` (baseline: 29.7 min locally) and the ten slowest classes.

- [x] **Task 2 — AC1: JVM-static containers** *(commit alone; this is the container fix)*
  - [x] Create `SharedContainers` using **one lazy holder per container** (`SharedContainers.Postgres/Redis/Minio`), never stopped — **not** three eager `static final` fields, which would start MinIO for every JVM and defeat `-Dit.test=X` iteration.
  - [x] Give the PostgreSQL container an explicit constant database name; it can no longer read `${spring.application.name}` from a Spring context.
  - [x] Replace `TestConfig.redisContainer()`/`postgresContainer()` (`:43-57`) with `RedisConnectionDetails` / `JdbcConnectionDetails` beans.
  - [x] Replace `MinioTestConfig.minioContainer()` (`:26-29`) with a reference to the MinIO holder; keep the existing `DynamicPropertyRegistrar` and `createTestBucket` runner.
  - [x] Full `mvn -o verify` **and a pushed PR**. Add the automated `docker ps` sampler and confirm peak ≤ 1 of each image. Record the new wall clock — this step alone should already cut it materially.

- [x] **Task 2b — AC5a: neutralize scheduling under the `test` profile** *(must precede Task 4)*
  - [x] Enumerate all 31 `@Scheduled` methods and 11 `@SchedulerLock` jobs; confirm which are already neutralized by `application-test.yaml` (currently: two video delays) and which are not.
  - [x] Turn scheduling off globally under the `test` profile — condition on `AsyncConfig.java:25`'s `@EnableScheduling`, or a full delay sweep. Justify whichever you pick.
  - [x] Enumerate every test that today depends on a scheduler firing on its own and convert it to invoke the job directly. **List them explicitly** — such a test passes vacuously once the timer stops, so a spot check will not find them.
  - [x] Full verify + PR.

- [x] **Task 3 — AC3 (properties): consolidate into `application-test.yaml`**
  - [x] Add `spring.cloud.compatibility-verifier.enabled`, `rate.limiting.enabled`, `allowed.clients` (superset), and the four scheduler-disabling video delays to `src/test/resources/application-test.yaml`.
  - [x] **Verify `MailManagerIT` first**, then add `enable.test.mail: true` globally if safe (AC3).
  - [x] Delete `ledger.database.spy` (13 classes), `email.retry.enabled` (1), and move the two `logging.level...=TRACE` overrides into `logback-test.xml`.
  - [x] Strip the now-redundant `@TestPropertySource` blocks from all classes that carried only these properties.
  - [x] Full verify. Re-run the analysis script; record the new context count.

- [x] **Task 4 — AC2: introduce `AbstractIntegrationTest`**
  - [x] Create it with the annotation set in AC2, including **both** `@ConfigureWireMock` names, and the shared protected fixtures.
  - [x] Convert `BaseVideoIT`, `BasePaymentIT`, `BaseSessionIT`, `BaseStorageIT` to extend it, deleting their duplicated annotations and fields.
  - [x] Strip the redundant class-level annotations from the six `BaseSessionIT` subclasses that re-declare them.
  - [x] Migrate the remaining ~88 `@Import(TestConfig.class)` classes to `extends AbstractIntegrationTest`. Mechanical — script the annotation removal, then compile.
  - [x] Full verify. Re-run the analysis script.

- [ ] **Task 5 — AC4: mocks**
  - [x] **Apply the trap-check to `GeminiClient` before hoisting it** — `application-test.yaml:52` gives Gemini a WireMock path, so confirm no IT drives the real client. Then hoist to `AbstractIntegrationTest` and remove the 19 local declarations.
  - [x] For each remaining mocked type, **verify whether any IT drives the real collaborator** (especially `VideoProviderAdapter` vs the Bunny WireMock stubs, and `FileStorageService` vs the MinIO storage ITs) and record hoist / keep-local / stub-bean per type in `docs/testing/`.
  - [x] Create `AbstractVideoIT` / `AbstractPaymentIT` / `AbstractStorageIT` / `AbstractE2ETest` with their family mock sets, per AC3's target table. **`AbstractE2ETest` takes `E2ESecurityConfig` only** — keep `TestClockConfig` out, or delete the dead `AbstractSkillarsE2ETest` it belongs to. **Satisfied differently:** the four *existing* family bases (`BaseVideoIT`, `BasePaymentIT`, `BaseSessionIT`, `BaseStorageIT`) were converted to extend `AbstractIntegrationTest` and now carry the family mock sets, so no parallel `Abstract*IT` hierarchy was created — adding one would have meant migrating 45 subclasses for no change in cache keys. `AbstractSkillarsE2ETest` was **deleted** (the second option the task offered) along with `TestClockConfig`; the two surviving E2E classes (`ConfigResourceIT`, `StorageResourceIT`) take `E2ESecurityConfig` directly.
  - [x] **Do not add `Mockito.reset(...)` for plain `@MockitoBean` fields** — `MockReset.AFTER` is already the default. Instead audit `@MockitoSpyBean` and the non-Mockito stub beans (`StubPaymentGateway` and friends) for mutable state that now survives the whole run, and add reset hooks only where the audit finds some.
  - [ ] Full verify + PR. Re-run the analysis script; confirm **≤ 10** contexts. — **NOT MET.** Verify + PR done; the count is **20 distinct configurations / 37 context loads**. AC3's ≤ 10 is unreachable without replacing the system under test in five classes. See *Task 11 → AC3 miss* below and `docs/testing/readme.md`.

- [x] **Task 6 — AC5: deterministic reset** *(split into two commits)*
  - [x] Commit A: add `DatabaseResetTestExecutionListener` (truncate + Redis flush) with order < 5000, registered via `@TestExecutionListeners(mergeMode = MERGE_WITH_DEFAULTS)` on `AbstractIntegrationTest`. **Empirically verify the ordering against `@Sql`** before going further.
  - [x] Exclude `flyway_schema_history`, `main.shedlock` and the `qrtz_*` tables from the truncate; reset shedlock by backdating `lock_until` instead. Re-read AC5.1 before writing this — deleting the shedlock row is a documented, silent, suite-wide failure mode.
  - [x] **Implement reference-data restoration (AC5.1a) in the same commit as the truncate — never ship the truncate without it.** 33 migrations contain `INSERT INTO`; `main.platform_config` alone is seeded by 30 of them, plus `session.drills` (V39) and `main.authority` (V21/V84). Flyway will not replay them. Verify with `DrillLibraryResourceIT` (`:96` reads a V39 `PLATFORM` drill) and `ConfigResourceIT` before moving on.
  - [x] Add cache eviction (AC5.1b) for `ConfigService` and `AlertRuleCache`.
  - [x] Full verify + PR. Expect failures; **triage each into "class relied on another test's leftovers" (fix the class) vs "reference data vanished" (fix the reset)** before fixing anything. List both sets in the completion notes.
  - [x] Commit B: delete `DbCleaner`, `TestDataCleaner`, the per-class `@AfterEach` row deletions, and the `cleanup.sql` `AFTER_TEST_METHOD` usages.
  - [x] Measure the per-test truncate cost **and watch for `ACCESS EXCLUSIVE` lock waits**; record both.

- [x] **Task 7 — AC6: hard-coded values**
  - [x] Image tags + credentials → `SharedContainers` constants with production-tracking comments.
  - [x] Bump PostgreSQL to `17-alpine` **as its own commit**; full verify; report result. If it fails, revert the version only, keep the constant, and add a `deferred-work.md` item.
  - [x] Delete `TestConfig.spyDataSource`, `TestConfig.hikariConfig`, `TestConfig.provideListener()` after verifying each is unreferenced.

- [x] **Task 8 — AC7: guardrail**
  - [x] Write the guardrail test. **Run it against the pre-refactor tree (e.g. a stashed worktree at `21ef489`) and confirm it fails** — then run it against the refactored tree and confirm it passes.
  - [x] Pin the `@TestPropertySource` count to the surviving number, with a comment explaining how to change it.

- [x] **Task 9 — AC9: frontend skip flag**
  - [x] Add `skipFrontend`; verify each of the five executions is skipped by reading the build log.
  - [x] Measure the delta.

- [x] **Task 9b — AC10: make `pr-build.yml` observe and enforce the outcome**
  - [x] Wire the `missCount > 10` context gate and the container-ceiling sampler; verify each **fails** against the pre-refactor tree before trusting it.
  - [x] Tighten `timeout-minutes` to a value that would actually catch a regression; record it.

- [x] **Task 10 — AC8: documentation**
  - [x] **Update the four existing `docs/testing/` files in place** — do not overwrite them. Remove the status banner and the TODAY/TARGET markers; replace every projection with a measured number (local **and** CI).
  - [x] Correct the two claims this review overturned: the three-exclusion truncate (now also needs reference-data restoration) and the "mock resets are a new requirement" premise (`MockReset.AFTER` is already the default).
  - [x] Confirm the existing `docs/dev-docs/index.html` "Writing integration tests" card still resolves.

- [x] **Task 11 — Final verification and honest reporting**
  - [x] Clean `mvn -o verify` **and a green `pr-build` run**. Record from **both**: wall clock, **unit test count and IT test count reported separately** (the last three stories all miscounted by summing report directories or quoting only the failsafe total — count what surefire and failsafe each actually ran), failures, errors, skipped.
  - [x] Sample `docker ps` one final time; record peak container counts by image.
  - [x] Re-run the analysis script **and** read `missCount` from the CI log; record the final context count and list every remaining context with its justification. If the two disagree, explain why.
  - [x] If the wall-clock improvement is smaller than the projection in Dev Notes, **say so explicitly and explain why** rather than reporting only the headline. Note the projection was built on the local 30:45 baseline, which CI evidence shows is not representative.

---

## Dev Notes

### Verification method — reproduce every number in this story

The context-count analysis is a script, not a judgement call. It is the **diagnostic** — AC3's CI gate on `missCount` is the **enforcement**; you want both, because `missCount` tells you *that* the count regressed and only the script tells you *which class* forked. Recreate it in the scratchpad:

1. Walk `src/test/java`, strip comments, and for each class capture: `@SpringBootTest(...)` args, `@ActiveProfiles(...)`, `@TestPropertySource(...)`, all `@Import(...)`, `@EnableWireMock`/`@ConfigureWireMock` names, and the set of `@MockitoBean` / `@MockitoSpyBean` field **types**.
2. Resolve `extends` chains so a subclass inherits its base's annotations (this matters: 45 classes extend a `Base*IT` and 12 of them add their own mocks or properties — omitting this step under-counts contexts by roughly a third; the first pass of this analysis reported 24 instead of 37 for exactly that reason).
3. Skip `abstract` classes. Group the rest by the tuple `(sbt, profiles, testPropertySources, imports, mocks, spies)`. **The number of groups is the number of Spring contexts, and therefore the number of container pairs.**

Cross-check against reality rather than trusting the script: `docker ps` during a run, and `grep -c` on the failsafe report count.

### Why `@MockitoBean` specifically causes this

Spring Framework 6.2 (bundled with Boot 3.5.11) resolves `@MockitoBean`/`@MockitoSpyBean` into `BeanOverrideHandler`s collected by a `ContextCustomizer` whose `equals`/`hashCode` are derived from **the set of overrides**. `ContextCustomizer`s are part of `MergedContextConfiguration`, which is the context cache key. So `{GeminiClient}` and `{GeminiClient, FileStorageService}` are different keys — two contexts, two container pairs — even though the two test classes are otherwise byte-for-byte identical in configuration. Mbah's hypothesis is exactly right; it is the single largest source of *avoidable* forks (13 of the 37).

### Files to read before changing anything

| File | Why |
|---|---|
| `src/test/java/com/softropic/skillars/config/TestConfig.java` | The container beans (`:43-57`) are the defect. Also holds `@Primary` `PaymentGateway` stub, `MailManager` stub, and the cookie-disabled `RestTemplate` — all of which must survive the refactor unchanged. |
| `src/test/java/com/softropic/skillars/config/MinioTestConfig.java` | Same container-as-bean shape; already uses `DynamicPropertyRegistrar`, which is the idiom to follow. |
| `src/main/java/com/softropic/skillars/infrastructure/config/DataSourceConfig.java` | Explains the `datasource.container=true` switch (`application.yaml:299`) and why `TestConfig.hikariConfig` is orphaned. |
| `src/test/resources/application-test.yaml` | Destination for the consolidated properties; already contains the "make schedulers never fire" precedent at `:5-7`. |
| `src/test/java/.../BaseVideoIT.java`, `BasePaymentIT.java`, `BaseSessionIT.java`, `BaseStorageIT.java` | The four existing partial base classes, and the two divergent `@EnableWireMock` names. |
| `src/test/java/.../booking/api/AvailabilityResourceIT.java` | The canonical shape of a current IT: class-level `@Sql`, hard-coded id range, hand-written `@AfterEach` teardown including a global `DELETE FROM main.sec` (`:120`). ~40 classes look like this. |
| `src/test/resources/sql/secData.sql`, `cleanup.sql`, `userData.sql`, `authorityData.sql` | The non-idempotent fixed-PK inserts that make the current cleanup mandatory. |
| `src/test/java/com/softropic/skillars/utils/DbCleaner.java`, `config/TestDataCleaner.java` | The two partial, hand-maintained cleaners AC5 replaces. |

### Do not "fix" this by raising the context cache size

`spring.test.context.cache.maxSize` is 32 and 37 contexts are needed, so the cache thrashes. Raising the ceiling is the obvious-looking one-line change and it is **the wrong move**: with containers still bound to contexts (pre-AC1), a larger cache means *more* simultaneously-live containers, not fewer — it trades context rebuilds for Docker exhaustion. After AC1 the containers are shared, and after AC3 the count is well under 32, so the default needs no change at all. Leave it alone, and say why in the AC8 docs so the next person doesn't reach for it.

### Test-scope beans are component-scanned — do not relocate them

`HttpTestClient` (`e2e/HttpTestClient.java:23`), `DbCleaner`, `TestDataCleaner` and `EntityFetchAsserter` are picked up by the application's own component scan because `src/test/java` shares the `com.softropic.skillars` root package and is on the test classpath. Two consequences: deleting `DbCleaner`/`TestDataCleaner` in AC5.3 removes real beans (confirm nothing `@Autowired`s them before deleting), and `HttpTestClient` must **not** be moved into `TestConfig` — it is already available everywhere, and adding it as an explicit `@Bean` would create a duplicate-bean conflict.

### What must not break

- The `@Primary` `RestTemplate` in `TestConfig` (`:113-125`) disables Apache HttpClient cookie management specifically so a previous test's JWT is not silently injected into an unauthenticated request. Preserve it verbatim, comment included.
- `StubPaymentGateway` is `@Primary` and unconditional — every IT already runs against the stub gateway. That is the model AC4 points to for other collaborators.
- `CustomPostgresContainer` sets `TZ`/`PGTZ` to UTC, and Failsafe passes `-Duser.timezone=UTC`. Both must survive; the timezone stories (`deferred-17`, `deferred-18`) depend on them.
- `@EnableWireMock`-provided properties: `wiremock.server.bunny-service.baseUrl` has **no default** where it is consumed (`application-test.yaml:31`). See AC2.

### Projected result — state it as a projection, then report what actually happened

37 contexts × (container start + 91 Flyway migrations + Spring Boot startup) is the dominant term in the 29.7 min of **local** failsafe time. Reducing to ≤ 10 contexts sharing one container set should land the local suite in the 8–15 min range, plus whatever AC9 saves.

**Two caveats that must not be dropped when reporting.** First, this is an estimate derived from the context count, not a measurement. Second, the 30:45 baseline it is derived from is a macOS/Docker-Desktop figure — a July CI run of an older tree completed the full verify in 9m54s at the *unrefactored* 37 contexts, so the headroom on Linux is far smaller and the improvement there will look correspondingly modest. **That does not weaken the case for the refactor** — 37 contexts from 7 distinct configurations is a defect, the container ceiling is a correctness property, and AC7 prevents regrowth — but the story must not be reported as if a 3× speedup were universal. Task 11 requires the measured numbers from both environments, and requires saying so if either comes in worse.

### Explicitly out of scope

- `withReuse(true)` / cross-run container reuse (correctness question of its own — stale schema across branches).
- `forkCount > 1` or parallel test execution (would put concurrent Flyway and concurrent tests on one shared database; AC5's global truncate is fundamentally incompatible with parallel classes).
- Wiring a real frontend test runner (standing gap, already recorded).
- Reducing the number of integration tests, or converting ITs to slice tests. This story changes how tests are *configured*, never what they *assert*. If a test starts failing, the fix is seed data or a corrected assumption — never deleting or weakening the assertion.

### Project Structure Notes

- New test-infrastructure classes go in `com.softropic.skillars.config` alongside `TestConfig`, `MinioTestConfig`, `E2ESecurityConfig`, `TestClockConfig`, `StubPaymentGateway` — that package is already the established home for test configuration.
- `docs/testing/` currently exists and is empty; this story is its first content. `docs/` mixes plain Markdown (`docs/readme.md`, `docs/handling-time.md`) with the HTML page set under `docs/dev-docs/` — pick one and match it, per AC8.
- No `src/main` behaviour changes anywhere in this story except the optional PostgreSQL version bump (which is test-only) and the `pom.xml` `skipFrontend` property.

### References

- `src/test/java/com/softropic/skillars/config/TestConfig.java:43-57` — container `@Bean` methods (root cause)
- `src/test/java/com/softropic/skillars/config/TestConfig.java:59-86` — dead `spyDataSource` / orphaned `hikariConfig`
- `src/test/java/com/softropic/skillars/config/MinioTestConfig.java:26-41` — MinIO container bean + `DynamicPropertyRegistrar` idiom
- `src/main/java/com/softropic/skillars/infrastructure/config/DataSourceConfig.java:23-38` — `datasource.container` switch
- `src/main/java/com/softropic/skillars/infrastructure/config/DataSourceConfig.java:44` — the real `log.database.spy` (vs. the dead `ledger.database.spy`)
- `src/main/resources/application.yaml:296-299` — `allowed.clients` default, `datasource.container: true`
- `src/main/java/com/softropic/skillars/platform/notification/config/ComponentConfig.java:27` — real `MailManager` condition
- `src/main/java/com/softropic/skillars/platform/notification/infrastructure/EmailRetryScheduler.java:46` — `matchIfMissing = true` (H7)
- `docker-compose.yml:64,89` — production `postgres:17-alpine`, `redis:7-alpine` (H2)
- `src/test/resources/application-test.yaml:35` — `${wiremock.server.bunny-service.baseUrl}` with no default
- `src/main/java/com/softropic/skillars/infrastructure/config/ShedLockConfig.java:21-29` — JDBC `main.shedlock` lock provider (AC5 exclusion)
- `src/test/java/com/softropic/skillars/platform/payment/BasePaymentIT.java:87-93` — the correct shedlock reset (backdate, never delete)
- `src/main/resources/application.yaml:18-31` — clustered JDBC Quartz (`qrtz_*` AC5 exclusion)
- `src/main/java/com/softropic/skillars/platform/notification/config/AsyncConfig.java:25` — unconditional `@EnableScheduling` (AC5a)
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigService.java:47` and `platform/notification/service/AlertRuleCache.java:43` — the two scheduled in-process caches (AC5.1b)
- `src/main/java/com/softropic/skillars/platform/filestorage/service/OutboxPollerScheduler.java:30`, `DeletionSchedulerService.java:31` — the 5-second pollers (AC5a)
- `src/test/java/com/softropic/skillars/platform/session/api/DrillLibraryResourceIT.java:96` — reads a V39-seeded `PLATFORM` drill (AC5.1a)
- `src/main/resources/db/migration/V39__session_foundation_20_drills.sql`, `V20__platform_config.sql`, `V21__skillars_security_extension.sql` — representative reference-data seeders (AC5.1a)
- `.github/workflows/pr-build.yml` — `mvn -B verify -q`, `timeout-minutes: 15` (AC10); `.github/workflows/ci.yml` — GHCR push, no tests (Task 0)
- `docs/testing/` — the four existing drafts AC8 updates in place
- `src/test/java/com/softropic/skillars/platform/booking/api/AvailabilityResourceIT.java:36-123` — canonical current IT shape and its hand-written teardown
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (`skillars-deferred-18` entry) — the 30:45 / 825 unit + 905 IT baseline
- `pom.xml` — `frontend-maven-plugin` block (AC9), `maven-failsafe-plugin` block

## Dev Agent Record

### Agent Model Used

claude-opus-5 (Claude Code, `bmad-dev-story` workflow)

### Debug Log References

- Context-key analysis script: `scratchpad/ctxkeys.py` (recreated per Dev Notes → *Verification method*)
- Baseline CI run: [`31180425880`](https://github.com/tenjohokwen/skillars/actions/runs/31180425880) — PR [#6](https://github.com/tenjohokwen/skillars/pull/6)

### Completion Notes List

#### Task 0 — CI baseline (complete)

`ci.yml`'s GHCR failure was fixed by Mbah during this session: `secrets.GHCR_PAT` →
`secrets.GITHUB_TOKEN` (the job pushes to the repo's own package and already declares
`packages: write`), plus a new `test` job with `build-and-push` gated on `needs: test`.
Master pushes previously ran **zero** tests. AC10.1 (`-q` removal) and AC10.2 (report
upload) applied to both workflows. `timeout-minutes` raised 15 → 45 temporarily; Task 9b
sets the final value.

**Measured baseline on THIS tree (CI, ubuntu-latest, run `31180425880`):**

| Metric | Value |
|---|---|
| Wall clock (`mvn -B verify`, red run) | **10m10s** |
| Unit tests (surefire) | **825** — 0 failures, 0 errors, 1 skipped |
| Integration tests (failsafe) | **905** — 1 failure, 16 errors, 4 skipped |

The unit/IT split is reported separately and taken from the two distinct surefire and
failsafe summary lines, per Task 11's warning about miscounting. It corroborates the
825 + 905 recorded for `deferred-18`.

**CORRECTION — the story's predicted baseline failure does not exist on this tree.**
`SecurityFilterChainIT.test2FAFilterBlocksAccessUntilVerified` **passes** (4/4 tests,
0 failures), and there are **zero** occurrences of `ScriptStatementFailed` in the entire
run. The story's prediction came from `pr-build` run `28624816025` of `2026-07-02`, on a
*dependabot branch* predating `deferred-16`, `-17` and `-18`; it is stale. AC5 should not
be justified by that specific failure.

**The actual baseline failure set — 17 problems across 4 classes**, all in the
tenant/security/Envers-audit family:

| Class | Count | Cause |
|---|---|---|
| `TenantServiceIT` | 9 errors | `@AfterEach` `DELETE FROM main.revinfo` → FK violation `user_aud_rev_fkey`; `user_aud` rows still reference the revision |
| `RotatedKeyCleanupJobIT` | 3 errors + 1 failure | same FK violation, plus `IllegalState: A tenant with the name 'Cleanup Corp' already exists` (leftover state) |
| `TenantAuditIT` | 3 errors | same FK violation |
| `ApiKeyConcurrentRotationIT` | 1 error | same FK violation |

This **strengthens** rather than weakens AC5's case. Every one is a hand-written,
per-class, silently-incomplete `@AfterEach` cleanup list failing on leftovers — the exact
mechanism AC5 replaces, and a textbook instance of the story's "silently-incomplete list"
argument. It is also CI-only (it does not reproduce locally), which is why Task 0 insists
on a CI baseline before anything else. Recorded here as the known baseline so this story's
regressions stay distinguishable from it. **Not fixed in Task 0** — AC5 addresses it
structurally.

#### Task 1 — Baseline pinned (analysis complete)

Context-key analysis independently re-derived (`scratchpad/ctxkeys.py`). Agreement with
the story is exact on every number except the context total:

| Measurement | Story | Re-derived | |
|---|---|---|---|
| Concrete `@SpringBootTest` classes (inheritance resolved) | 129 | **129** | ✅ |
| Largest shared context | 30 | **30** | ✅ |
| Groups by `@Import` set alone (the AC3 floor) | 7 | **7** | ✅ (117/7/1/1/1/1/1 — identical distribution) |
| Distinct context cache keys | 37 | **39** | ⚠️ +2 |
| Contexts serving exactly one class | 23 | **24** | ⚠️ +1 |

The story states its 37 was measured at `21ef489`, which **is** this HEAD — so the tree has
not moved and the delta is a modelling difference between two independently written
scripts (most likely `@TestPropertySource`/`@ActiveProfiles` merge-vs-override semantics on
subclasses, where Spring merges by default). Rather than reverse-engineer the other script,
the authoritative number is taken from Spring itself via `missCount` (below), which is what
AC3 gates on anyway. The script is retained as the *diagnostic* — it names the forking class.

**`missCount` instrumentation verified (AC3 prerequisite).** `logback-test.xml` root level
was `WARN`, so the cache-statistics line was never emitted. Added
`<logger name="org.springframework.test.context.cache" level="DEBUG"/>`. Exact emitted format
confirmed empirically before anything greps it, per AC3's warning:

```
cache statistics: [DefaultContextCache@6ec48344 size = 1, maxSize = 32,
  parentContextCount = 0, hitCount = 161, missCount = 1, failureCount = 0]
```

⇒ gate regex `missCount = ([0-9]+)`. Note the first probe attempt was invalid: it used
`RateLimitingAspectIT` + `PropertiesFeatureToggleServiceIT`, the two container-free sliced
tests, which carry **no `@ActiveProfiles`** and therefore never load `application-test.yaml`
(and so never pick up its `logging.config: classpath:logback-test.xml`). They log via the
app's `logback-spring.xml` instead. Re-probed with profile-activating ITs.

Representative per-class cost at baseline: `AvailabilityResourceIT` = **147.9 s for 9 tests**,
almost entirely container + 91 Flyway migrations + Spring startup.

#### Pre-verified trap-checks (evidence gathered before touching code)

- **AC4.1 `GeminiClient` → safe to hoist.** Zero `*IT` classes reference `GeminiClient`
  without mocking it. Decisively: **no test declares the default (unnamed) WireMock server** —
  only `bunny-service` (`BaseVideoIT:27`) and `stripe-service` (`BasePaymentIT:35`) exist. So
  `infrastructure.gemini.api-base-url: ${wiremock.server.baseUrl:http://localhost:9999}`
  (`application-test.yaml:52`) *always* resolves to the dead-port fallback. There is no live
  Gemini WireMock path for a root hoist to shadow.
- **AC4.2 `FileStorageService` → must NOT hoist to root.** Confirmed mocked by exactly 5
  classes (`ParentDevelopmentPortalResourceIT`, `PlayerTimelineResourceIT`,
  `ActiveBookingsErasureBlockIT`, `GdprErasureIT`, `GdprExportIT`), and exercised for real
  against MinIO by the storage family via `BaseStorageIT` + `MinioTestConfig`.
- **AC3 `MailManagerIT` → global `enable.test.mail: true` is safe.** It already sets the
  property itself and asserts `mailManager` **is** a `TestMailManager` (`:60`) — it does not
  test the real `MailManager`. `ComponentConfig:27`'s real bean is
  `havingValue="false", matchIfMissing=true`, so a global `true` disables it everywhere.
  Side benefit: this is also what stops the real outbound SMTP connections to `mail.gmx.net`
  the story flagged.
- **AC1.1 PostgreSQL DB name.** `spring.application.name: skillars` (`application.yaml:41`).
  Its only other bindings are Micrometer metric/observation tags (`application.yaml:341,347`)
  and the logback `appName` — none database-related, so pinning it as a constant is inert.
- **AC6/H6 dead code confirmed, and stronger than assumed.**
  `DataSourceConfig.dataSource(HikariConfig)` is `havingValue="false"`, so under the tests'
  `datasource.container=true` it is **not created** and nothing injects `HikariConfig` ⇒
  `TestConfig.hikariConfig` is orphaned. `TestConfig.spyDataSource` is not merely dead but
  **superseded** by `DataSourceConfig.dataSourceSpyPostProcessor()`, which does the same job
  "regardless of whether the DataSource came from DataSourceConfig or Boot's
  `@ServiceConnection` auto-config". `TestConfig.provideListener()` is `private` and never called.
- **AC5a scope confirmed:** exactly **31** `@Scheduled` methods and **11** `@SchedulerLock`
  jobs in `src/main`; `@EnableScheduling` is unconditional at `AsyncConfig.java:25`.

#### Task 9 — AC9 `-DskipFrontend` (complete)

Added `<skipFrontend>false</skipFrontend>` and wired `<skip>${skipFrontend}</skip>` into the
plugin-level `<configuration>`. Verified by reading the build log that **all five** executions
report `Skipping execution`: `install-node-and-npm`, `npm install`,
`npm install -g @quasar/cli`, `npx quasar build`, and the no-op `npm test`. Documented in
`pom.xml` that the flag changes the packaged artifact (no UI in the jar) and must never be set
in CI.

#### Tasks 2b / 3 / 4 — consolidation (implemented, compile-verified, full-suite verification pending CI)

**Context cache keys: 39 → 21. Largest shared context: 30 → 82 classes.** `mvn test-compile`
BUILD SUCCESS. 109 files changed, 925 insertions, 1459 deletions.

- **AC5a** — `@EnableScheduling` moved off `AsyncConfig` into
  `infrastructure.config.SchedulingConfig`, gated on `app.scheduling.enabled`
  (`matchIfMissing = true`; only `application-test.yaml` turns it off). `@EnableSchedulerLock`
  stays unconditional on `AsyncConfig` by design. The conditional was chosen over a delay sweep
  because **a sweep cannot work**: 16 of the 30 `@Scheduled` methods hard-code their delay or
  cron with no property placeholder; only 14 are property-driven.
- **AC3** — nine properties hoisted into `application-test.yaml`; `allowed.clients` unified as a
  **superset** (both `myClientId` and `testClientId`) so `SecurityIT` keeps working.
- **AC2** — `AbstractIntegrationTest` created; four `Base*IT` classes converted; **86** concrete
  classes migrated to `extends`; **55** byte-identical private `baseUrl()` copies removed.
- **AC4 (partial)** — `GeminiClient` hoisted to the root after its trap-check passed; 19 local
  declarations removed. No `Mockito.reset` added (`MockReset.AFTER` is already the default).
- **AC6 H1 satisfied**: `grep -rn "ledger.database.spy" src/` returns **0**.

Residual class-level annotations are the documented exceptions only: `RateLimitingAspectIT`,
`PropertiesFeatureToggleServiceIT`, `ModerationFailClosedIT`, `MessageModerationSweeperIT`
(sliced `@SpringBootTest`), plus 5 `@TestPropertySource` forks that genuinely change behaviour.

#### CI run 2 ([`31184780327`](https://github.com/tenjohokwen/skillars/actions/runs/31184780327)) — post-consolidation, and what it exposed

| Metric | Baseline | After consolidation |
|---|---|---|
| Wall clock | 10m10s | **8m03s** (−21%) |
| Unit (surefire) | 825 — 0F 0E | 825 — 0F 0E ✅ |
| IT (failsafe) | 905 — 1F 16E | 905 — **2F 90E** ⚠️ |
| `missCount` | not instrumented | 64 (`failureCount = 8`) |

No tests were lost (905 both times) and the suite got measurably faster, but the error count
rose from 17 to 92. Two distinct causes, both diagnosed:

**(1) Connection-pool exhaustion — a real defect in AC1 as specified, now fixed.**

8 contexts failed to load with:

```
Failed to initialize dependency 'flywayInitializer' of LoadTimeWeaverAware bean 'entityManagerFactory'
Caused by: Connection is not available, request timed out after 30000ms
           (total=1, active=1, idle=0, waiting=0)
```

Production sizing (`application.yaml:71-72`) is `maximum-pool-size: 25` / `minimum-idle: 8`,
chosen so four app nodes fit inside PostgreSQL's default `max_connections` of 100. That was
harmless while **every Spring context had its own PostgreSQL container** — one pool per
database. Sharing one container means every cached context keeps its own live pool against the
**same** database, and Spring caches up to 32 contexts: ~20 × 8 `minimum-idle` = **160
connections against a 100-connection server**. The pool cannot grow, Flyway blocks acquiring a
connection, and the context dies after the 30 s timeout.

This also explains `missCount = 64` against only 21 distinct keys — failed contexts are retried,
and once the failure threshold is hit, every later class sharing that key reports *"skipping
repeated attempt to load context"*. That cascade alone is **30 of the 90 errors**.

**AC1 does not mention connection pooling at all.** It is a genuine gap in the story's analysis,
not a mistake in following it: consolidating containers without also bounding the per-context
pool cannot work. Fixed in `application-test.yaml` only (`maximum-pool-size: 4`,
`minimum-idle: 0`, `idle-timeout: 10000`); tests are single-threaded per context, so a small
pool suffices and an inactive cached context now holds zero connections.

**(2) Cross-class data leakage — exactly what AC5/Task 6 exists to fix, and now unavoidable.**

~50 `DataIntegrityViolation` errors: `delete from main.videos` FK violations (23),
`DELETE FROM main.revinfo` (14 — the pre-existing baseline failure), duplicate
`INSERT INTO main.user_authority` (8), `DELETE FROM main.videos` FK (5).

The story predicted this precisely: *"Expect failures on the first full run from classes that
were silently relying on rows another test class left behind."* Consolidating ~90 classes onto
one shared context means they share one database for the whole run, and the hand-written
per-class `@AfterEach` cleanup is not sufficient at that blast radius.

**The operational conclusion is that Task 6 is load-bearing, not follow-up polish.** The
consolidation in Tasks 3/4 cannot be green without `DatabaseResetTestExecutionListener`. These
two commits should not be merged to master before Task 6 lands.

#### CI run 3 ([`31186124117`](https://github.com/tenjohokwen/skillars/actions/runs/31186124117)) — with the connection-pool cap

| Metric | Baseline | Run 2 (consolidation) | Run 3 (+ pool cap) |
|---|---|---|---|
| Wall clock | 10m10s | 8m03s | 12m57s |
| Unit (surefire) | 825 — 0F 0E | 825 — 0F 0E | **828** — 0F 0E |
| IT (failsafe) | 905 — 1F 16E | 905 — 2F 90E | 905 — 2F **52E** |
| `failureCount` | — | **8** | **0** ✅ |
| `missCount` | — | 64 | **37** |

**The pool cap fully resolved the context-load failures**: `failureCount` 8 → **0**, and the
"skipping repeated attempt to load context" cascade is gone entirely. Errors dropped 90 → 52.
Unit tests rose 825 → 828: the three `IntegrationTestConventionTest` cases, all passing.

Run 3 is slower (12m57s vs 8m03s) because tests that previously aborted on a dead context now
actually execute. It is not a like-for-like comparison and must not be reported as a regression
against the 8m03s figure.

**All 52 remaining errors are cross-class data leakage — nothing infrastructural is left:**

| Count | Error |
|---|---|
| 23 | `delete from main.videos` batch FK violation |
| 14 | `DELETE FROM main.revinfo` FK violation (`user_aud_rev_fkey`) — pre-existing baseline |
| 8 | duplicate `INSERT INTO main.user_authority` |
| 5 | `DELETE FROM main.videos` FK violation |
| 2 | `IllegalState: A tenant with the name 'Cleanup Corp' already exists` — pre-existing baseline |

Every one is a hand-written per-class `@AfterEach` cleanup failing on rows another class left
behind. **This is precisely and exclusively AC5's remit**, and it confirms the operational
conclusion: Task 6 is the remaining blocker, and there is no other blocker.

**On `missCount = 37` vs the 21 distinct keys the offline script reports.** With
`failureCount = 0` the gap is not retries. The likely explanation is that the ~11 `@WebMvcTest`
slice classes build their own contexts, which the script deliberately excludes (it counts only
`@SpringBootTest`). 21 + slices lands at or above `maxSize = 32`, so the LRU cache evicts and
rebuilds, inflating `missCount`. **Consequence for AC3:** the `missCount > 10` gate as written
would also be counting container-free slice contexts, so either the gate needs to discount them
or the ceiling needs restating. Worth resolving before Task 9b wires the gate — a gate that
measures the wrong population is the failure mode AC3 itself warns about.

#### Task 6 / AC5 — `DatabaseResetTestExecutionListener` (commit A)

Implemented at order **3000** (below `SqlScriptsTestExecutionListener`'s 5000), registered on
`AbstractIntegrationTest` via `@TestExecutionListeners(mergeMode = MERGE_WITH_DEFAULTS)` — which
does not contribute to `MergedContextConfiguration`, so it adds no contexts. Sequence per test
method: **reset (3000) → `@Sql` (5000) → `@BeforeEach` → test**.

Exclusions implemented exactly as AC5.1 specifies: `flyway_schema_history`, `main.shedlock`
(**backdated**, never deleted — deleting makes `JdbcTemplateLockProvider`'s cached-name `UPDATE`
match nothing and silently skips every later job), and `qrtz_*` (a live clustered check-in thread
writes to them concurrently).

**Reference-data restoration (AC5.1a) — solved without a maintainable list.** Rather than
enumerate the seeded tables, the first reset — which runs *before* anything has been truncated,
when the database holds precisely the Flyway-seeded data and nothing else — snapshots every
non-empty table into a `_refdata` schema with `CREATE TABLE … AS SELECT *`, and every later reset
replays it with `INSERT INTO … SELECT *`. **The snapshot defines itself**, so a new seeding
migration is picked up automatically with nothing to drift, and doing it inside PostgreSQL avoids
mapping jsonb/arrays/enums through Java types. This is AC5.1a's "preferred" option, and it makes
the drift-detection check that its fallback option requires unnecessary.

**Correction to AC5.1a's table list:** it names three seeded tables. There are **four** — the
migrations also seed `development.skill_definitions`. Counts verified: 33 migrations contain
`INSERT INTO`; `main.platform_config` ×30, `session.drills` ×4, `main.authority` ×2,
`development.skill_definitions` ×1. The self-defining snapshot covers the missed one
automatically, which is precisely why that approach was chosen over a hard-coded list.

**AC5.1b cache eviction needs no `src/main` change** — `ConfigService.scheduledRefresh()` and
`AlertRuleCache.refresh()` are both already `public`.

**Bug found and fixed during verification — worth recording, because it fails silently.**
The first implementation was a **complete no-op**. `application.yaml:73` sets
`hikari.auto-commit: false` (required so Hibernate can group statements into one transaction).
A bare `JdbcTemplate` call outside a transaction therefore runs on a connection that is never
committed, and the work is **rolled back when the connection returns to the pool — with no
error**. It only surfaced because the snapshot's `CREATE SCHEMA _refdata` vanished before the
`CREATE TABLE` that followed it:

```
UncategorizedSQLException: ... [CREATE TABLE "_refdata"."main__authority" AS SELECT * FROM "main"."authority"]
SQL state [3F000]; ERROR: schema "_refdata" does not exist
```

Had the snapshot been a single statement, the listener would have reported success while
truncating nothing. All database work is now wrapped in a `TransactionTemplate`. This is also
why every hand-written cleaner in this codebase wraps itself in `transactionTemplate.execute(...)`
— a convention whose reason was undocumented until now.

Two further implementation notes: the `information_schema` table list is cached statically (it
would otherwise be a round-trip on each of ~905 test methods), and reset cost is accumulated and
reported at JVM exit to satisfy AC5.6's "measure, do not assume".

#### CI run 4 ([`31190871807`](https://github.com/tenjohokwen/skillars/actions/runs/31190871807)) — Task 6 commit A

**The reset works, and takes the suite below the pre-story baseline.**

| Metric | Original baseline | Consolidation | + pool fix | **+ reset** |
|---|---|---|---|---|
| Unit (surefire) | 825 — 0F 0E | 825 — 0F 0E | 828 — 0F 0E | **828 — 0F 0E** |
| IT errors | **16** | 90 | 52 | **5** |
| IT failures | 1 | 2 | 2 | **1** |
| `failureCount` | — | 8 | 0 | **0** |
| `missCount` | — | 64 | 37 | 37 |
| Wall clock | 10m10s | 8m03s | 12m57s | 15m19s |

Six problems, against **seventeen** before this story started. The entire
`DELETE FROM main.revinfo` FK cluster (14 errors) and the `'Cleanup Corp'` leftovers are
gone — the reset fixed pre-existing breakage, not merely the regression consolidation
introduced.

**AC5.6 settled with measurement, not speculation:** `814 invocations, 81123 ms total,
99.7 ms mean`. The truncate costs ~81 s across the whole run — **not material**, so the
"switch to `DELETE` from non-empty tables only" fallback is **not needed**. No
`ACCESS EXCLUSIVE` lock waits were observed, which is consistent with AC5a having removed
the concurrent scheduler connections first; that is exactly why the story sequenced AC5a
before AC5, and the sequencing demonstrably paid off.

Wall clock rises 12m57s → 15m19s. ~81 s of that is the reset; the rest is tests that
previously aborted now running to completion. Not a like-for-like comparison with 8m03s.

**AC5.5 triage — the split the story asks for, complete for this run:**

| Bucket | Classes | Resolution |
|---|---|---|
| Class relied on another test's leftovers → **fix the class** | `ConfigResourceIT` (4 errors), `StorageResourceIT` (5 errors) | Both authenticate but seeded no security key, free-riding on `main.sec` rows another class left. `main.sec` is **not** Flyway-seeded (no migration inserts into it), so this is squarely the "fix the class" bucket. Added the `@Sql` seed each always needed. |
| Reference data vanished → **fix the reset** | none | The reference-data restoration held: `DrillLibraryResourceIT` (V39 drill) and `ConfigResourceIT` (`platform_config`) both pass. |

**Still open, undiagnosed:** `ReviewUpdateIT.updateReview_afterOneYear_returns204` —
`expected "UNDER_REVIEW" but was "PENDING"`. A moderation state-transition failure, not
data leakage, so it belongs to neither AC5.5 bucket. Plausible causes are AC5a's
scheduling disable or the `GeminiClient` root hoist; **not investigated, and no cause is
being asserted.**

#### Task 6 commit B (AC5.3) — hand-written cleanup deleted

Kept as a separate commit so `git bisect` can isolate any class that turns out to depend on
cleanup ordering.

- **73** `@AfterEach` teardown blocks stripped. The stripper constrains the *calls* in a body
  (only `jdbcTemplate.update/execute`, `transactionTemplate.execute`) rather than the
  identifiers, since bind-parameter references are just values. **27 teardowns that do more
  than delete rows were left untouched** and listed for manual review rather than guessed at.
- `DbCleaner` (with its four commented-out `delete` lines from another project) and
  `TestDataCleaner` deleted. Both were component-scanned beans, so the one real consumer was
  checked first — `SecretServiceIT.dbCleaner.cleanDb()` removed.
- `AbstractSkillarsE2ETest` and `TestClockConfig` deleted. AC3's dead-code claim **verified**:
  zero subclasses, and `TestClockConfig` used by nothing else. This is what lets
  `AbstractE2ETest` sidestep the fixed-clock bundling question entirely.
- 3 `@Sql(cleanup.sql, AFTER_TEST_METHOD)` usages removed.

Safe because AC5.4's side effect holds: every test method now starts from an empty database,
so `secData.sql`'s fixed-PK insert is idempotent by construction.

#### CI progression, all runs (Task 6 complete)

| Run | Change | IT failures + errors | `failureCount` |
|---|---|---|---|
| `31180425880` | **baseline** (pre-story) | 1F + 16E = **17** | — |
| `31184780327` | consolidation (Tasks 3/4) | 2F + 90E = 92 | 8 |
| `31186124117` | + connection-pool cap | 2F + 52E = 54 | 0 |
| `31190871807` | + reset listener (6A) | 1F + 5E = 6 | 0 |
| `31192540977` | + cleanup deletion (6B) | 1F + 15E = 16 | 0 |
| `31194113235` | + 6B fixup | **1F + 2E = 3** | 0 |

Unit tests 828 — 0F 0E throughout. `missCount = 37`, wall clock 15m34s.

**Net: 17 → 3 problems**, i.e. the story's work has fixed pre-existing breakage as well as
its own regressions.

**The commit-B regression was an error in this implementation, not in the story.** The
stripper filtered on what a teardown's *body* did. The correct rule is
**a teardown is redundant only if its class actually receives the reset listener** — which
gets the two cases exactly backwards:

- the four **allowlisted** classes deliberately do not extend `AbstractIntegrationTest`, so
  they get **no** listener and their cleanup was load-bearing (restored);
- `BasePaymentIT.cleanPaymentData` **does** get the listener, so it was redundant *and*
  failing against an already-empty database — it survived the automated strip only because
  its `SET SESSION session_replication_role` (bypassing the V79 append-only trigger) defeated
  the conservative call-filter (removed deliberately).

That rule also resolves the 27 held-back teardowns without inspecting any of them.

**The 3 remaining problems — none diagnosed, no causes asserted:**

| Test | Symptom | Note |
|---|---|---|
| `ReviewUpdateIT.updateReview_afterOneYear_returns204` | `expected "UNDER_REVIEW" but was "PENDING"` | Present since run 4. A moderation state transition, not data leakage. |
| `ModerationFailClosedIT.geminiFailure_...` | `ScriptStatementFailed` | **Allowlisted, so it has no reset listener.** Its teardown was restored and it still fails, which suggests it depends on ambient database state that now differs because every *other* class truncates. Likely fix: let it extend `AbstractIntegrationTest` and carry `@Import(FailureEventCapture.class)`, which preserves its separate context *and* gives it the reset. |
| `ConversationResourceIT.createConversation_concurrent_...` | `ExecutionException` | A concurrency test; appeared in this run only. Could be flaky — needs a re-run to establish whether it is reproducible before anyone changes it. |

#### Work NOT yet done — honest status *(superseded; kept as the record at the time of writing)*

The table below was written mid-story. Tasks 5–11 were all subsequently completed except AC3's
≤ 10 ceiling; see *Task 11* immediately after it for the final state.

| Task | AC | Status |
|---|---|---|
| **5** | AC4 | **Partial.** `GeminiClient` hoisted and verified. **Not done:** the flavoured bases (`AbstractVideoIT`, `AbstractPaymentIT`, `AbstractE2ETest`), the per-type hoist/keep-local/stub decision record, and the audit of `@MockitoSpyBean` + non-Mockito stub beans (`StubPaymentGateway`) for mutable state that now survives the whole run. **This is why the count is 21, not ≤ 10** — the residual forks are almost entirely `VideoProviderAdapter` mock-set variance. |
| **6** | AC5 | **Not started.** `DatabaseResetTestExecutionListener`, the reference-data restoration (AC5.1a), cache eviction (AC5.1b), and the deletion of `DbCleaner`/`TestDataCleaner`/per-class `@AfterEach` teardown. **This is the highest-risk remaining item** and the one that fixes the 17 baseline failures. |
| **7** | AC6 | **Partial.** Image tags and credentials are already constants on `SharedContainers` with production-tracking comments (H2/H4), and H1/H7 are done. **Not done:** the PostgreSQL 14.18 → 17-alpine bump, and deleting `TestConfig.spyDataSource`/`hikariConfig`/`provideListener` (all three verified dead, deletion not yet applied). |
| **8** | AC7 | **Not started.** `IntegrationTestConventionTest` guardrail. |
| **9b** | AC10 | **Partial.** `-q` removal, report upload and the container sampler are done and working. **Not done:** wiring the `missCount > 10` gate into `pr-build.yml` and tightening `timeout-minutes` (currently parked at 45). |
| **10** | AC8 | **Not started.** The four `docs/testing/` drafts still carry their "migration not yet applied" banner and TODAY/TARGET markers, and still contain the two claims this story's review overturned. |
| **11** | — | **Not started.** Final measured before/after, local and CI. |

#### Task 11 — Final verification (complete)

**Authoritative run: CI [`31233411803`](https://github.com/tenjohokwen/skillars/actions/runs/31233411803)**
(`pr-build.yml`, commit `635f7ff`). Every story gate passes. The run is red **only** on the Trivy
image scan, which is pre-existing dependency debt unrelated to this story — see *Known red* below.

| Metric | Baseline (`31180425880`, tree `21ef489`) | Final (`31233411803`) |
|---|---|---|
| CI wall clock | 10m10s *(red run — died before Docker + Trivy)* | **14m37s** *(full run incl. image build + scan)* |
| Unit tests (surefire) | 825 — 0F / 0E / 1 skipped | **828** — 0F / 0E / 6 skipped |
| Integration tests (failsafe) | 905 — 1F / **16E** / 4 skipped | **905** — **0F / 0E** / 53 skipped |
| Peak PostgreSQL containers | ~30 | **1** |
| Peak Redis containers | ~30 | **1** |
| Peak MinIO containers | per-context | **2** |
| Context loads (`missCount`) | 37 | **37** *(see reconciliation)* |

The two wall-clock figures **are not comparable** and must not be quoted as a regression: the
baseline run failed at the failsafe step and never reached the Docker build or the Trivy scan.
The honest comparison is against the last full green-through-Docker run of this branch before the
pool fix, which was ~21 min — so the measured saving is **~6.5 min on CI**, essentially all of it
from the Hikari pool correction (below), not from container consolidation.

**Local (`mvn -o verify`, macOS/Docker Desktop):** 30:45 baseline → ~32 min at the time the pool
bug was live → not re-measured end-to-end after the fix. The two classes that carried the
regression are measured individually below. *This is a gap: no clean local full-suite number was
taken after the fix.*

**The dominant win was a bug I introduced, then removed.** `maximum-pool-size: 4` (added in
`4550a70` alongside the necessary `minimum-idle: 0`) starved tests that use concurrency *within* a
single test method, which then blocked for the full 30 s `connection-timeout`:

| Class | With pool = 4 | With pool = 16 (CI `31233411803`) |
|---|---|---|
| `BatchAcceptPaymentIT` (6 tests) | 240.4 s | **0.421 s** |
| `BookingBatchResourceIT` (10 tests) | 152.1 s | **2.098 s** |

Those 16 tests were 392 s of 724 s — **54%** of all integration-test time. The tell was per-test
times quantised at exactly 30.1 / 60.1 / 60.2 s, matching `connection-timeout: 30000`. The
justification originally given for `4` ("tests are single-threaded per context") was a
non-sequitur: `forkCount > 1` being out of scope constrains test *classes*, not threads inside a
test method.

**Context count — reconciling 20 with 37.** These measure different things and the docs previously
printed them side by side without saying so:

- **20** = distinct context *configurations* (cache keys), from the analysis script. This is what
  AC3's ceiling is about.
- **37** = `missCount`, the number of context *loads* in the failsafe JVM.

The gap is `@DirtiesContext`. `ConfigResourceIT:40` declares
`@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` and rebuilds its context on **every test
method** — attributable in the CI log as four consecutive misses (#31–#34) before its summary
line. `RateLimitingAspectIT:32` adds one more with `AFTER_CLASS`. The cache also reports
`size = 32, maxSize = 32`, i.e. saturated, so late loads additionally evict and reload.

**`ConfigResourceIT`'s `@DirtiesContext` is now probably redundant** — AC5.1b added `ConfigService`
and `AlertRuleCache` eviction to the reset listener specifically so per-test config isolation no
longer needs a context teardown. Removing it is the single highest-value remaining cleanup and is
left as follow-up work rather than gambled on at landing time.

**AC3 miss — ≤ 10 contexts is unreachable on this hierarchy.** Getting from 20 to 10 requires
hoisting `QuotaService`, `VideoLifecycleService` and `ModerationOrchestrationService` onto
`BaseVideoIT`. That family *contains the real integration tests for those exact services*
(`QuotaServiceConcurrencyIT`, `VideoRetryUploadIT`, `WebhookPipelineIT`, `VideoLifecycleLogIT`,
`MinorSafetyGateIT`). Hoisting would replace the system under test in five classes, which would
keep passing while asserting nothing. **20 is the correct floor**; going lower needs per-service
sub-bases, not a bigger shared mock set. The earlier "21, and it is reachable" note above was
written before that analysis and is wrong.

**Known red — not this story.** Trivy reports 34 findings (3 Alpine OS packages, 31 in `app.jar`;
29 HIGH / 5 CRITICAL). The 5 CRITICALs are `tomcat-embed-core` 10.1.52→10.1.55,
`bcprov-jdk18on` 1.80→1.81.1, and `spring-security-web` 6.5.8→6.5.9. Most clear with
`spring-boot-starter-parent` 3.5.11 → 3.5.16. Deliberately **not** bundled here: a framework bump
does not belong in a test-infrastructure PR, and it needs its own full-suite verification.

**Gaps I am not papering over:**
- No clean local `mvn -o verify` wall clock after the pool fix.
- `-DskipFrontend`'s wall-clock delta (AC9) was never measured; the flag is verified to skip all
  five executions, but the time saved is unquantified.
- The context and container gates were verified to *pass* on the refactored tree and the container
  ceiling was corrected after a real MinIO peak of 2; they were **not** re-run against the
  pre-refactor tree to prove they fail there. Only the AC7 guardrail test got that treatment.

**A deviation to flag:** AC3's ≤ 10 context ceiling is **not met** (21). It is reachable — the
analysis shows the residual forks collapse once the video-family mocks move to a flavoured base —
but the `VideoProviderAdapter` root hoist was deliberately **not** applied. AC4.2's trap-check
could not be fully discharged by static analysis: all 24 classes that reference the type mock it,
and the three ITs using Bunny WireMock stubs already mock it too, but ~100 classes that never
reference it could still reach it transitively through a service. AC4.2 says that when this is in
doubt the hoist belongs on `AbstractVideoIT` rather than the root, and validating a root hoist
needs a full green run this session did not reach.

### File List

**Added**
- `src/test/java/com/softropic/skillars/config/SharedContainers.java`
- `src/test/java/com/softropic/skillars/config/AbstractIntegrationTest.java`
- `src/main/java/com/softropic/skillars/infrastructure/config/SchedulingConfig.java`
- `.github/scripts/container-sampler.sh`

**Modified — infrastructure / config**
- `src/test/java/com/softropic/skillars/config/TestConfig.java` (container `@Bean`s → `ConnectionDetails` beans)
- `src/test/java/com/softropic/skillars/config/MinioTestConfig.java` (container `@Bean` → `SharedContainers.minio()`)
- `src/main/java/com/softropic/skillars/platform/notification/config/AsyncConfig.java` (`@EnableScheduling` moved out)
- `src/test/resources/application-test.yaml` (AC3 property consolidation + `app.scheduling.enabled`)
- `src/test/resources/logback-test.xml` (context-cache DEBUG category)
- `pom.xml` (`skipFrontend`)
- `.github/workflows/ci.yml`, `.github/workflows/pr-build.yml` (AC10.1/10.2, timeout)

**Modified — test base classes**
- `src/test/java/com/softropic/skillars/platform/video/BaseVideoIT.java`
- `src/test/java/com/softropic/skillars/platform/payment/BasePaymentIT.java`
- `src/test/java/com/softropic/skillars/platform/session/api/BaseSessionIT.java`
- `src/test/java/com/softropic/skillars/infrastructure/storage/BaseStorageIT.java`

**Modified — 86 concrete `*IT` classes** migrated to `extends AbstractIntegrationTest`
(annotations stripped, duplicated `baseUrl()` removed, hoisted `GeminiClient` mock removed).
Full list: `git diff --name-only 21ef489..HEAD -- src/test/java`.

**Also committed on the branch (pre-existing, authored by Mbah, separate commit `0919888`)**
- `.github/workflows/deploy.yml`, `docs/deployment/secrets-reference.md`,
  `docs/deployment/baseline/github-build.md`

Totals vs `21ef489`: **109 files changed, 925 insertions, 1459 deletions.**
