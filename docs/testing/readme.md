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
| Contexts actually built (`missCount`) | not instrumented | **37** |
| Contexts serving exactly one class | 24 | 12 |
| Largest shared context | 30 classes | **82 classes** |
| Unit tests | 825 — 0F 0E | 828 — 0F 0E |
| Integration tests | 905 — 1F **16E** | 905 — 1F **2E** |
| CI wall clock | 10m10s | 15m34s |
| Local wall clock | 30:45 | **41:32 — WORSE** |
| Test PostgreSQL | `14.18` | **`17-alpine`** (matches production) |

**Read the wall clock honestly. The suite got slower in both environments, and locally it got
much slower — 30:45 to 41:32.** The story's headline projection (8-15 min locally) was not
achieved and was never achievable from this design: it assumed container-per-context startup
dominated the local runtime, and the reset added a per-method cost the projection did not model. Three reasons, none of them a
regression in the sense that matters:

1. The 10m10s baseline was a **red** run in which 17 tests errored early and a whole family of
   contexts never finished starting. The 15m34s run executes strictly more work.
2. The per-test database reset costs **99.7 ms mean over 814 invocations — ~81 s total** on CI.
   **On macOS/Docker Desktop the same 814 invocations cost 828 s — ~1018 ms each, 10x the CI
   figure, i.e. ~14 of the local 41:32.** Docker Desktop's VM boundary makes each round trip an
   order of magnitude dearer.
3. The local 30:45 figure that motivated this story was a macOS/Docker-Desktop artefact. On Linux
   the container-per-context cost was never the dominant term, so removing it does not produce the
   3× speedup the story projected. **The story projected 8–15 min locally; that projection was
   built on a baseline that is not representative and should not be treated as achieved.**

What did improve, and was the actual point: **the container count is now bounded and cannot grow
with the test suite**, the context count is bounded and enforced, and the suite is meaningfully
more correct — integration errors fell from 16 to 2, including pre-existing failures this story
did not set out to fix.

## The remaining contexts

20 distinct configurations, each deliberate:

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
