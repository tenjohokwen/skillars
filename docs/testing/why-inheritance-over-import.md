# Why inheritance, not `@Import(TestConfig.class)`

> **Status:** design rationale for `skillars-deferred-19`. The measurements are real (commit `21ef489`);
> the base-class architecture is the target state, not yet in the tree. See [readme.md](readme.md).

For most of this project's life, an integration test began with a hand-copied header:

```java
@ActiveProfiles({"dev", "test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestConfig.class)
@TestPropertySource(properties = {
    "spring.cloud.compatibility-verifier.enabled=false",
    "rate.limiting.enabled=false",
    "allowed.clients=testClientId"
})
class SomeResourceIT { ... }
```

Eighty-eight classes carried some version of that block. It is being replaced by:

```java
class SomeResourceIT extends AbstractIntegrationTest { ... }
```

This document explains why that was necessary, because on the face of it the `@Import` version is
*better* — it is explicit, it is composable, it does not consume the single-inheritance slot, and it
tells you at a glance what the test needs.

---

## The problem: the annotation that mattered was invisible

`@Import(TestConfig.class)` communicates **which beans** a test needs. It communicates **nothing** about
the Spring **context cache key** — and the cache key is what actually decides whether a test class costs
zero seconds or a full Spring Boot startup plus two Docker containers.

The Spring TestContext Framework caches an `ApplicationContext` keyed by `MergedContextConfiguration`.
Two test classes share a cached context **only if every component of that key matches exactly**:

- `@SpringBootTest` — `classes`, `properties`, `webEnvironment`
- `@ActiveProfiles`
- `@TestPropertySource`
- `@ContextConfiguration` / `@Import`
- every registered `ContextCustomizer` — which includes `@EnableWireMock` server names **and the set of
  `@MockitoBean` / `@MockitoSpyBean` declarations**

So the cost model is:

> **one distinct configuration = one Spring context = one PostgreSQL container + one Redis container**

(the container half of that equation is explained in [container-architecture.md](container-architecture.md))

None of that is visible in the header you are copying. Adding one property, or one mocked collaborator,
is a locally-correct one-line change whose real cost — a container pair and ~35 seconds — appears
nowhere near the diff and nowhere in review.

## What it actually cost — TODAY, measured at `21ef489`

| | |
|---|---|
| Concrete `@SpringBootTest` integration-test classes | **129** |
| **Distinct Spring context cache keys** | **37** |
| ⇒ containers required | **37 postgres + 37 redis** |
| Contexts serving **exactly one** test class | **23 of 37** |
| Largest shared context | 30 classes |
| `spring.test.context.cache.maxSize` (Spring default, never overridden) | **32** |
| Full `mvn -o verify` | **30:45** — of which 29.7 min is failsafe |

Thirty-seven required contexts against a cache ceiling of thirty-two means the LRU cache **evicts and
rebuilds contexts mid-run**. Eviction closes the context, which stops its containers — which is why
`docker ps` showed about thirty of each rather than thirty-seven, and why some classes paid full
container + Flyway + Spring startup **twice** in a single run.

Now the punchline. Group those same 37 contexts by `@Import` set alone — that is, pretend properties,
profiles and mocks were all unified — and you get **seven**:

```
 117 classes -> @Import(TestConfig.class)
   7 classes -> @Import({TestConfig.class, MinioTestConfig.class})
   1 class   -> @Import({TestConfig.class, E2ESecurityConfig.class})
   1 class   -> @Import({TestConfig.class, MinioTestConfig.class}) + E2ESecurityConfig
   1 class   -> @Import({TestConfig.class, ModerationFailClosedIT.FailureEventCapture.class})
   1 class   -> @Import(RateLimitingAspectIT.AspectConfig.class)   (no containers)
   1 class   -> (no @Import)                                        (no containers)
```

**Seven genuinely distinct configurations produced thirty-seven contexts.** The other thirty were
accidents of copy-paste. That gap is the entire argument.

## Where the thirty accidents came from

### The `@MockitoBean` trap

Since Spring Framework 6.2 (Boot 3.5.11 here), `@MockitoBean` and `@MockitoSpyBean` are resolved into
`BeanOverrideHandler`s collected by a `ContextCustomizer` whose `equals`/`hashCode` derive from **the set
of overrides**. `ContextCustomizer`s are part of `MergedContextConfiguration`.

So `{GeminiClient}` and `{GeminiClient, FileStorageService}` are **different cache keys** — two contexts,
two container pairs — even when the two test classes are otherwise byte-for-byte identical in
configuration.

Twenty-nine classes declared mocks, across only about eleven distinct types, in **thirteen different
combinations**:

| Config shape | Contexts caused **only** by a differing mock set |
|---|---|
| `BaseVideoIT` / `BasePaymentIT` shape | **7** — `()`, `(BookingService)`, `(QuotaConfigService)`, `(QuotaService, VideoProviderAdapter)`, `(StripeClient)`, `(VideoProviderAdapter)`, and `(VideoProviderAdapter)` + spy `VideoPhysicalDeletionListener` |
| The main `RANDOM_PORT` + `{dev,test}` shape | **6** — `()`, `(GeminiClient)`, `(VideoProviderAdapter)`, `(FileStorageService, GeminiClient)`, `(FileStorageService, VideoProviderAdapter)`, `(CoachProfileService, FileStorageService, VideoProviderAdapter)` |
| `VideoUploadPipelineIT` shape | **2** |

Thirteen of the thirty-seven contexts existed for no reason other than that different authors mocked
different subsets of the same handful of collaborators.

### Property drift

Across all directly-annotated classes there were only **nine distinct properties**, five of them
near-universal:

| Count | Property |
|---|---|
| 83 | `spring.cloud.compatibility-verifier.enabled=false` |
| 64 | `rate.limiting.enabled=false` |
| 59 | `allowed.clients=testClientId` |
| 23 | `enable.test.mail=true` |
| 13 | `ledger.database.spy=true` |
| 2 | `logging.level.org.springframework.security=TRACE` |
| 1 | `email.retry.enabled=true` |
| 1 | `features.toggles.payments=true` |
| 1 | `features.toggles.invoicing=false` |

Nearly all of these belong in `application-test.yaml`, where they cost nothing. Worse, drift had already
produced real defects that copy-paste hid:

- **`ledger.database.spy` reads nothing.** The real property is `log.database.spy`
  (`DataSourceConfig.java:44`). `ledger.` is a leftover name from a different project. Thirteen classes
  carried it — thirteen classes paying a context fork for a property with no consumer.
- **`allowed.clients` had two incompatible hard-coded values in one suite.** The application default is
  `myClientId,hisClientId,herClientId,ourClientId` (`application.yaml:296-297`); 59 tests overrode it to
  `testClientId` *only*; and `SecurityIT.CLIENT_ID` is `"myClientId"`. Both are needed, so the fix is a
  superset — but nothing about the copied header made the conflict visible.
- **`email.retry.enabled=true` was redundant** — `EmailRetryScheduler.java:46` is
  `matchIfMissing = true`. One class, one context fork, zero effect.
- **`logging.level.org.springframework.security=TRACE`** on two classes. A logging level has no business
  being in a context cache key; it belongs in `logback-test.xml`.

### Divergent WireMock server names

`BaseVideoIT` declared `@EnableWireMock(@ConfigureWireMock(name = "bunny-service"))`; `BasePaymentIT`
declared `name = "stripe-service"`. Both correct in isolation, and each one a separate context.

---

## Why inheritance fixes it

A base class makes the cache key **structurally identical by construction** rather than by convention.

- A subclass that adds nothing **cannot** fork the context. That is not a guideline; it is what the
  annotation-resolution algorithm does.
- Forking becomes an **explicit act** — you must add an annotation to a concrete class — which a
  guardrail test can then detect and fail on.
- The four `Base*IT` classes that already existed (`BaseVideoIT`, `BasePaymentIT`, `BaseSessionIT`,
  `BaseStorageIT`) had already discovered this locally. Deferred-19 generalises it to one root instead of
  four islands with subtly different headers.

Inheritance also buys something `@Import` structurally cannot: the base class holds **behaviour**, not
just configuration. Three things must live there and have nowhere else to go:

1. **Reset hooks for the test doubles Spring does *not* reset for you.** `@MockitoBean` defaults to
   `MockReset.AFTER` and the framework already clears those after every test method — so Mockito mocks
   are safe. But the non-Mockito stub beans (`StubPaymentGateway` and any other `@Primary` test double in
   `TestConfig`) get no such treatment, and once one context serves ~90 classes, any state they
   accumulate persists for the whole run. The base class is where that reset belongs.
2. **The database/Redis reset lifecycle** (see [test-data-isolation.md](test-data-isolation.md)).
3. **Shared fixtures** — `JdbcTemplate`, `TransactionTemplate`, `HttpTestClient`, `@LocalServerPort`,
   `baseUrl()` — currently duplicated across the four base classes.

## The honest case against

Inheritance is not free, and the alternative is real:

- It consumes the **single-inheritance slot**. A test that wants to extend something else cannot.
- It **couples every test to one hierarchy**. A change to the root touches ~130 classes.
- It **hides what a test actually needs**. `@Import(TestConfig.class)` told you, at the top of the file,
  that this test needs a database. `extends AbstractIntegrationTest` tells you nothing until you open
  the base.

The credible alternative is a **composed meta-annotation** — a single `@IntegrationTest` carrying
`@SpringBootTest`, `@ActiveProfiles`, `@Import`, `@EnableWireMock` and the shared `@MockitoBean`
declarations. It delivers essentially the same cache-key guarantee, stays composable, and leaves the
inheritance slot free.

**We chose inheritance anyway, for one reason:** the three behaviours listed above — mock resets,
reset lifecycle, shared fixtures — cannot live on an annotation. Splitting configuration onto a
meta-annotation and behaviour onto a base class would mean two things to remember instead of one, and
"remember to apply both" is the exact failure mode this whole exercise is correcting. If the behaviour
ever moves out (into a JUnit extension, say), revisit this decision — the meta-annotation becomes the
better answer the moment the base class has nothing but annotations on it.

## What keeps it from growing back

A guardrail test (container-free, runs in the unit phase) asserts:

- every `*IT` class is assignable to `AbstractIntegrationTest`, or appears in an explicit allowlist;
- no allowlisted-exempt `*IT` carries `@Import(TestConfig.class)`, `@ActiveProfiles` or `@SpringBootTest`;
- the total number of `@TestPropertySource` annotations on concrete `*IT` classes matches a **pinned
  expected count**, so adding one fails the build until someone updates the number and says why.

The allowlist is seeded with exactly the documented exceptions and nothing else. The guardrail was
verified by running it against the pre-migration tree and confirming it **fails** there — a guardrail
that has never failed is not a guardrail.

## Two things not to do

**Do not raise `spring.test.context.cache.maxSize`.** It is the obvious-looking one-line fix for the
eviction thrash and it makes things worse: while containers are still bound to contexts, a larger cache
means *more simultaneously live containers*, trading context rebuilds for Docker exhaustion. After the
containers are shared and the contexts consolidated, the default of 32 is comfortably sufficient.

**Do not "tidy" containers back into `@Bean` methods.** See
[container-architecture.md](container-architecture.md).

---

## Appendix: reproducing the numbers

The context count is a measurement, not a judgement call. To re-derive it:

1. Walk `src/test/java`, strip comments, and capture per class: `@SpringBootTest(...)` arguments,
   `@ActiveProfiles(...)`, `@TestPropertySource(...)`, every `@Import(...)`, `@ConfigureWireMock` names,
   and the set of `@MockitoBean` / `@MockitoSpyBean` field **types**.
2. **Resolve `extends` chains** so a subclass inherits its base's annotations. This step is not optional:
   45 classes extend a `Base*IT`, and 12 of them add their own mocks or properties. Skipping it
   under-counts by roughly a third — the first pass of this analysis reported 24 contexts instead of 37
   for exactly that reason.
3. Skip `abstract` classes. Group the remainder by the tuple
   `(springBootTest, activeProfiles, testPropertySources, imports, mocks, spies)`.

**The number of groups is the number of Spring contexts, and therefore the number of container pairs.**

Cross-check the result against reality rather than trusting the script — run `docker ps` during a
`mvn verify` and count, and compare the class count against `target/failsafe-reports/`.
