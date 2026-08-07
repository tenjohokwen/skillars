# Skillars Integration Testing

How the integration-test suite is wired, why it is wired that way, and what you must do when you add a test.

> ### ⚠️ Status: design documented, migration not yet applied
>
> These documents describe a **target architecture** that is specified but **not yet implemented in the
> codebase**. The migration is tracked as story
> `skillars-deferred-19-test-context-container-consolidation`.
>
> Every **measurement, defect and collision** recorded here is real and was taken from the tree at
> commit `21ef489`. Every **prescription** ("extend `AbstractIntegrationTest`", "containers are
> JVM-static") describes the state *after* deferred-19 lands. Sections describing the current tree are
> marked **TODAY**; sections describing the target are marked **TARGET**.
>
> When deferred-19 is complete, delete this banner, replace the projections with measurements, and drop
> the TODAY/TARGET markers.

---

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
get **37 Spring contexts and ~74 Docker containers** for a suite that needs about seven distinct
configurations. The fix is two-part: containers become JVM-static so context count stops driving Docker
pressure, and configuration moves into a shared base class so context count stops growing by accident.

---

## Writing a new integration test — TARGET

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

1. **Extend `AbstractIntegrationTest`** (or `AbstractVideoIT` / `AbstractPaymentIT` / `AbstractStorageIT`
   / `AbstractE2ETest` if you need that family's collaborators).
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
mvn -o verify -DskipFrontend     # backend + tests only  (TARGET — added by deferred-19)
mvn -o verify -Dit.test=MyIT     # one integration test class
```

## Known gaps

- **The frontend has no test runner.** `src/frontend/package.json` maps `npm test` to
  `echo "No test specified" && exit 0`, and the `frontend-maven-plugin` runs it every build. Standing
  gap, tracked separately.
- **Test PostgreSQL is behind production.** See [container-architecture.md](container-architecture.md#image-versions).
