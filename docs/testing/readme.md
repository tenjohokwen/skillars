# Skillars Integration Testing

How the integration-test suite is wired, why it is wired that way, and what you must do when you add a test.

## The documents

| Document | What it answers |
|---|---|
| [Why inheritance, not `@Import(TestConfig.class)`](why-inheritance-over-import.md) | Why ~130 test classes moved from copy-pasted annotation headers to a shared base class, what it cost us not to, and the honest case against the choice. **Start here.** |
| [Container architecture](container-architecture.md) | Why PostgreSQL/Redis/MinIO are JVM-static and deliberately *not* Spring beans. Read before touching `TestConfig`. |
| [Test data isolation](test-data-isolation.md) | How each test gets a clean database, which tables must never be truncated and why, and the fixture-id registry. |

---

## The one-paragraph version

The Spring TestContext Framework caches an `ApplicationContext` per distinct *configuration*, and
Testcontainers containers declared as `@Bean`s live and die with the context that owns them. Combine
those two facts with ~130 test classes that each hand-copied their own configuration header, and you
got **37 Spring contexts and ~74 Docker containers** for a suite that needs about seven distinct
configurations. The fix was two-part: containers became JVM-static so context count stops driving Docker
pressure, and configuration moved into a shared base class so context count stops growing by accident.

Measured outcome: **3 containers instead of ~74**, context cache keys **39 → 20**, and the suite's
integration failures went from **17 to 3** — the reset fixed pre-existing breakage as well as the
regressions consolidation introduced.

---

## Writing a new integration test

```java
class MyFeatureResourceIT extends AbstractIntegrationTest {

    // Claim an unused id range and register it in test-data-isolation.md
    private static final long COACH_ID  = 9640000001L;
    private static final long PARENT_ID = 9640000002L;

    @BeforeEach
    void setUp() {
        // Seed EVERYTHING this class needs. The database is empty when you get here.
    }

    @Test
    void myBehaviour() { ... }
}
```

**Rules:**

1. **Extend `AbstractIntegrationTest`** (or `BaseVideoIT` / `BasePaymentIT` / `BaseSessionIT` /
   `BaseStorageIT` if you need that family's collaborators).
2. **Do not add `@SpringBootTest`, `@ActiveProfiles`, `@Import(TestConfig.class)` or `@EnableWireMock`
   to your class.** The base carries them. A guardrail test fails the build if you do.
3. **Do not add a `@MockitoBean` that another class also needs.** One extra mocked type on your class
   forks a whole new Spring context. If the collaborator should be mocked everywhere, hoist it to the
   base; if only you need it, keep it and accept that your class pays a context. See
   [why-inheritance-over-import.md](why-inheritance-over-import.md#the-mockitobean-trap).
4. **Seed your own data.** Every table is empty at the start of every test method. Do not rely on rows
   another class left behind — there won't be any.
5. **No teardown needed.** Do not write `@AfterEach` row deletions; the reset listener handles it.
6. **Claim an id range** and add it to the [registry](test-data-isolation.md#fixture-id-registry).
7. **Need a property no other test needs?** Add `@TestPropertySource` and update the pinned count in the
   guardrail test, with a comment saying why. It is allowed — it just has to be deliberate.

## Running the suite

```bash
mvn -o verify                    # everything, including the frontend build
mvn -o verify -DskipFrontend     # backend + tests only
mvn -o verify -Dit.test=MyIT     # one integration test class
```

## Known gaps

- **The frontend has no test runner.** `src/frontend/package.json` maps `npm test` to
  `echo "No test specified" && exit 0`, and the `frontend-maven-plugin` runs it every build. Standing
  gap, tracked separately.
- ~~Test PostgreSQL is behind production.~~ **Closed.** Tests now run `postgres:17-alpine`, matching
  `docker-compose.yml:64`. See [container-architecture.md](container-architecture.md#image-versions).
- **`ModerationFailClosedIT` has no reset listener.** It is allowlisted out of
  `AbstractIntegrationTest` to keep its own `FailureEventCapture` config, which means it does not get
  `DatabaseResetTestExecutionListener` and still depends on ambient database state. Known-failing.
- **`@DirtiesContext(AFTER_EACH_TEST_METHOD)` on `ConfigResourceIT`** rebuilds its context on every
  test method, inflating the context count the CI gate measures.

---

## Before and after — measured

All figures from CI (`ubuntu-latest`, 4 vCPU) unless marked local. Story `deferred-19`.

| | Before (`21ef489`) | After |
|---|---|---|
| Docker containers | ~74 (one postgres + one redis per context) | **3** (1 postgres, 1 redis, 1 minio per JVM) |
| Distinct context cache keys (offline analysis) | 39 | **20** |
| Contexts actually built (`missCount`) | not instrumented | **34** |
| Contexts serving exactly one class | 24 | 12 |
| Largest shared context | 30 classes | **82 classes** |
| Unit tests | 825 — 0F 0E | 828 — 0F 0E |
| Integration tests | 905 — 1F **16E** | **905 — 0F 0E** |
| CI wall clock | 10m10s | **10m48s** |
| Local wall clock | 30:45 | **8:05** (`-DskipFrontend`, warm caches) |
| Test PostgreSQL | `14.18` | **`17-alpine`** (matches production) |

**Read the wall clock honestly — in both directions.**

An earlier revision of this table reported 41:32 locally and called the story a wall-clock
regression. That was accurate when written and is now wrong, for a reason worth recording: the
regression was a **bug introduced by this story**, not a property of the design.
`spring.datasource.hikari.maximum-pool-size` had been set to `4`, which starved tests that use
concurrency *inside* a single test method; they then blocked for the full 30 s
`connection-timeout`. Two classes alone (`BatchAcceptPaymentIT`, `BookingBatchResourceIT`) were
392 s of 724 s of integration time. Raising the pool to 16 took them to 0.4 s and 2.1 s. The tell
was per-test times quantised at exactly 30.1 s and 60.1 s — **round numbers are a timeout, not
work.**

Two caveats on the numbers above, so they are not over-read:

1. The **10m10s baseline was a red run** in which 17 tests errored early and a whole family of
   contexts never finished starting. The current run executes strictly more work, so CI is not
   "flat" — it does more in the same time.
2. The **8:05 local figure is not comparable to the 30:45 baseline**: it was measured with
   `-DskipFrontend`, warm Maven and Docker caches, and the tenant tests disabled. Treat it as a
   lower bound for a warm backend-only loop. A like-for-like local number has never been taken,
   and the story's original 8–15 min projection should still not be treated as verified — it was
   built on a macOS/Docker-Desktop baseline that was never representative.

The per-test database reset costs **99.7 ms mean over 814 invocations (~81 s total) on CI**, and
roughly 10× that on macOS/Docker Desktop, where the VM boundary makes each round trip far dearer.
That per-method cost is real and was not modelled by the original projection.

What did improve, and was the actual point: **the container count is now bounded and cannot grow
with the test suite**, the context count is bounded and enforced, and the suite is meaningfully
more correct — integration errors fell from 16 to 2, including pre-existing failures this story
did not set out to fix.

## The remaining contexts

**Two different numbers get quoted here; they are not the same measurement.**

- **20** — distinct context *configurations* as counted by the offline analysis script. The script
  **skips Boot slices**, so this is "how many full `@SpringBootTest` shapes do we have".
- **~32** — actual Spring cache keys. Both CI runs report `size = 32, maxSize = 32`. The extra
  ~11 are `@WebMvcTest` and friends, which build their own cut-down contexts: cheap, no
  containers, but real cache entries. 20 + ~11 ≈ 32 reconciles the two.
- **34** — `missCount` from the CI log: the number of context *loads*. This is what
  `.github/scripts/assert-context-count.sh` gates on, because it is the number that actually costs
  time.

**The cache is exactly full.** Spring's default `maxSize` is 32 and we have ~32 keys, so one more
context configuration does not cost +1 load — it also evicts something still in use, which is then
rebuilt. That is the thrashing this story existed to remove, and it is why the gate ceiling is
**36** rather than something comfortable.

The 34-vs-32 gap is `@DirtiesContext` plus eviction. `RateLimitingAspectIT:32` still uses
`AFTER_CLASS`. `ConfigResourceIT` used `AFTER_EACH_TEST_METHOD` and rebuilt its context on **every
test method** — removing it took CI from 37 to 34, verified in isolation and in the full suite.
The reset listener already provided that isolation by evicting `ConfigService` and `AlertRuleCache`
per test, which is what AC5.1b was added for.

> Local runs report 32 where CI reports 34, on the same 905 tests with the same 53 skipped. This
> is **not fully explained** — most likely execution order interacting with eviction and with the
> remaining `AFTER_CLASS` dirtying. The gate runs in CI and is set from the CI number.

The 20 configurations, each deliberate:

| Configuration | Classes | Why it exists |
|---|---|---|
| `AbstractIntegrationTest` | 81 | The default. Anything that needs no special collaborator. |
| `BaseVideoIT` / `BaseSessionIT` (+ `VideoProviderAdapter`) | 16 | Video/session families mock the outbound video adapter. |
| `BaseStorageIT` (+ `MinioTestConfig`) | 7 | The only family that starts MinIO. |
| `FileStorageService` mocked | 4 | Classes that must *not* hit real blob storage. |
| `+ QuotaService` | 3 | Quota-mocking video classes. |
| `E2ESecurityConfig` | 2 | `ConfigResourceIT`, `StorageResourceIT`. |
| `+ BookingService` | 2 | Booking-mocking classes. |
| `+ PlayerSubscriptionQueryPort, VideoLifecycleService` | 3 | Two lifecycle property variants. |
| Sliced `@SpringBootTest` (no containers) | 2 | `RateLimitingAspectIT`, `PropertiesFeatureToggleServiceIT`. |
| Single-class forks (`@TestPropertySource` or own config) | 5 | Properties that genuinely change behaviour; `ModerationFailClosedIT`'s `FailureEventCapture`. |

**Why not the ≤ 10 the story targeted.** Reaching 10 would require hoisting `QuotaService`,
`VideoLifecycleService` and `ModerationOrchestrationService` onto `BaseVideoIT`. That family
**contains the real integration tests for those services** — `QuotaServiceConcurrencyIT`,
`VideoRetryUploadIT`, `WebhookPipelineIT`, `VideoLifecycleLogIT`, `MinorSafetyGateIT`. Hoisting
would replace the system under test in five classes, and they would keep passing while asserting
nothing. 20 is the correct floor for this hierarchy; going lower needs per-service sub-bases, not
a bigger mock set.
