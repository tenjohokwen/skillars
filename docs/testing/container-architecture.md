# Container architecture

## The rule

> **Testcontainers containers are JVM-static singletons. They are never Spring beans.**

One PostgreSQL, one Redis, one MinIO per test JVM, shared by every Spring context, for the whole run.

## Why — the failure this prevents

### Before (commit `21ef489`)

`TestConfig` declares the containers as context beans:

```java
// src/test/java/com/softropic/skillars/config/TestConfig.java:43-57
@Bean @ServiceConnection(name = "redis")
GenericContainer<?> redisContainer() { ... }

@Bean @ServiceConnection
PostgreSQLContainer<?> postgresContainer(@Value("${spring.application.name}") String dbName) { ... }
```

`MinioTestConfig.java:26` has the same shape for MinIO.

Spring Boot's `TestcontainersLifecycleBeanPostProcessor` starts a `Startable` bean when its context
refreshes and stops it when that context closes. **Container lifetime is therefore bound 1:1 to the
`ApplicationContext`.**

That binding is a *multiplier*. It converts any amount of Spring context fragmentation — however
accidental — directly into Docker pressure. With
[37 contexts](why-inheritance-over-import.md#what-it-actually-cost--today-measured-at-21ef489), that
meant **37 PostgreSQL + 37 Redis containers**, capped in practice at ~30 of each by the context cache
ceiling, with the overflow being evicted and rebuilt mid-run.

Nothing about the test *code* had to change to fix this. The containers simply should not have been
beans.

### Now

```java
public final class SharedContainers {
    static final PostgreSQLContainer<?> POSTGRES = ...;
    static final GenericContainer<?>    REDIS    = ...;
    static final MinIOContainer         MINIO    = ...;
    static { POSTGRES.start(); REDIS.start(); MINIO.start(); }   // started once, never stopped
}
```

Started in a static initializer, **never stopped** — Testcontainers' Ryuk reaper removes them at JVM
exit.

## Why `ConnectionDetails` beans and not `@ServiceConnection`

The containers still have to be reachable from Spring. The obvious move is to keep the
`@ServiceConnection @Bean` methods and just return the static instance. **Do not do this.**

`TestcontainersLifecycleBeanPostProcessor` treats any `Startable` bean as its own to destroy. Handing it
a shared static instance means **the first context to close stops the container every other context is
still using** — a failure that surfaces as a connection error in an unrelated test, minutes later, with
no obvious cause.

There is a carve-out in that post-processor for containers marked reusable, but it is version-sensitive
and depends on a flag we do not set. This must not be subtle. Instead, expose the containers through
beans that are **not** `Startable`, which the post-processor therefore never touches:

```java
@Bean
JdbcConnectionDetails jdbcConnectionDetails() { /* from SharedContainers.POSTGRES */ }

@Bean
RedisConnectionDetails redisConnectionDetails() { /* from SharedContainers.REDIS */ }
```

For MinIO, keep the `DynamicPropertyRegistrar` idiom already in `MinioTestConfig.java` and simply point
it at `SharedContainers.MINIO`.

`TestConfig.hikariConfig(JdbcConnectionDetails)` continues to resolve against the new bean.

## Consequences to be aware of

**Schema init runs once.** `.withInitScript("sql/createSchema.sql")` now executes once per JVM instead
of once per context.

**Flyway runs once per Spring context, against the same database.** The second and subsequent runs are
no-op validation passes over the 91 migrations in `src/main/resources/db/migration`. Contexts are created
sequentially within a single Failsafe fork, so there is no concurrent-migration hazard.

**That sequencing is load-bearing.** Do **not** set `forkCount > 1` or enable parallel test execution.
Either would put concurrent Flyway runs and concurrent tests on one shared database, and is fundamentally
incompatible with the global truncate described in
[test-data-isolation.md](test-data-isolation.md).

**Cross-*run* reuse is deliberately not enabled.** `withReuse(true)` plus
`testcontainers.reuse.enable=true` would keep containers alive between `mvn verify` invocations, which is
attractive on a developer machine — but it carries its own correctness question (a container carrying the
schema of a different branch), and it is out of scope here. It is recorded as deferred work rather than
quietly switched on.

## Image versions

Image tags and credentials live as constants on `SharedContainers`, each carrying a comment naming the
production compose file and line it must track.

| Service | Test image | Production (`docker-compose.yml`) |
|---|---|---|
| PostgreSQL | `postgres:17-alpine` | `postgres:17-alpine` (`:64`) — **now matched** |
| Redis | `redis:7-alpine` | `redis:7-alpine` (`:89`) |
| MinIO | `minio/minio:RELEASE.2024-01-13T07-53-03Z` | same (`docker-compose.uat.yml:74`) |

**The PostgreSQL gap is a real risk, not a cosmetic one:** every integration test currently validates
against a database three major versions behind the one the product runs on. Deferred-19 bumps it to
`17-alpine` as a separate, individually-verifiable commit. If the bump breaks something, the version
reverts but the constant stays and the divergence gets recorded — it does not go back to being invisible.

`CustomPostgresContainer` sets `TZ` and `PGTZ` to UTC, and Failsafe passes `-Duser.timezone=UTC`. Both
must survive any change here — the timezone correctness work in `deferred-17` and `deferred-18` depends
on them.

## Things in `TestConfig` that must not be broken

| Bean | Why it matters |
|---|---|
| `@Primary RestTemplate` (`TestConfig.java:116`) | Disables Apache HttpClient cookie management **on purpose**. Without it, HttpClient 5's default cookie store persists login cookies across tests and silently injects a previous test's JWT into a request that was meant to be unauthenticated. Preserve it verbatim, comment included. |
| `@Primary PaymentGateway` → `StubPaymentGateway` | Unconditional — every integration test already runs against the stub gateway. This is the model to follow when a collaborator should be replaced everywhere: a `@Primary` stub bean in `TestConfig` costs no context fork, where a `@MockitoBean` on 20 classes costs several. |
| `MailManager` → `TestMailManager` | Conditional on `enable.test.mail`. Verify `MailManagerIT` before making this global — it may be asserting against the real implementation. |

### Dead weight to remove

- `TestConfig.spyDataSource` (`:59-75`) — gated on `log.database.spy=true`, which no test sets.
- `TestConfig.hikariConfig` (`:77-86`) — gated on `datasource.container=true` (which *is* true), but its
  only consumer `DataSourceConfig.dataSource(HikariConfig)` is disabled under exactly that same condition
  (`DataSourceConfig.java:25`). Built every time, used never.
- `TestConfig.provideListener()` (`:94-99`) — private, uncalled.

Verify each is unreferenced by deleting it and running the suite, not by reading.

## Test-scope beans are component-scanned

`HttpTestClient` (`e2e/HttpTestClient.java:23`), `DbCleaner`, `TestDataCleaner` and `EntityFetchAsserter`
are picked up by the **application's own** component scan, because `src/test/java` shares the
`com.softropic.skillars` root package and is on the test classpath.

Two consequences:

- Deleting `DbCleaner`/`TestDataCleaner` removes real beans — confirm nothing `@Autowired`s them first.
- `HttpTestClient` must **not** be moved into `TestConfig`. It is already available everywhere, and an
  explicit `@Bean` would create a duplicate-bean conflict.
