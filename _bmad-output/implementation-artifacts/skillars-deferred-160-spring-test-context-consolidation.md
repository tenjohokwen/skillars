# Story skillars-deferred-160: Spring test-context consolidation — get the failsafe JVM below the context cache cap

Status: done

<!-- Created 2026-10-24 (/bmad-create-story). NOT mined from deferred-work.md: user-requested after a
     context-count investigation ("what makes up to 47 spring contexts?"). No ledger items consumed. -->

## Story

As a developer on the Skillars backend,
I want the integration suite's failsafe JVM to build well under Spring's 32-entry test-context cache,
so that contexts stop being evicted and rebuilt (CI `missCount` 47, gate ceiling 47, flaky on class
order alone), CI gets faster and deterministic, and new forks are caught by the build instead of by
an after-the-fact ceiling bump.

## Background — what the 47 actually is (verified 2026-10-24 against `d60635b1`)

Almost every IT does extend `AbstractIntegrationTest` (141 concrete classes share the one base key,
K01 below). The count comes from **what subclasses add on top**, plus classes that are not
`@SpringBootTest` integration tests at all:

1. **Field-level `@MockitoBean` / `@MockitoSpyBean` in subclasses.** Each distinct *set* of overridden
   bean types is a distinct `MergedContextConfiguration` (via `BeanOverrideContextCustomizer`).
   `IntegrationTestConventionTest` does not check these at all — it only pins `@TestPropertySource`
   (7) and class-level `@SpringBootTest`/`@ActiveProfiles`/`@Import(TestConfig)`. **This is the
   single biggest source and the unguarded one.**
2. **Class-level `@Import`** of per-class event-capture configs and `E2ESecurityConfig`.
3. **14 `@WebMvcTest` slice classes named `*IT`**, so they run in the *failsafe* JVM and occupy cache
   slots there next to the full contexts (deferred-19 already flagged this at its line ~884:
   "21 + slices lands at or above `maxSize = 32`, so the LRU cache evicts and rebuilds").
4. 4 standalone `@SpringBootTest` classes (3 genuinely sliced, 1 — `MessageModerationSweeperIT` — is
   actually a **full** context mis-described as "sliced" in the allowlist).

**Live distinct keys in the failsafe JVM today: 47** (50 keys total; `AdminLoginResourceTest` and
`SluRetrierProxyRetryTest` already run in surefire; K07's two classes are class-level `@Disabled`, so
JUnit skips them before Spring loads a context). 47 > `maxSize = 32`, so LRU eviction is
guaranteed and `missCount` (contexts *built*, rebuilds included) depends on class order. Two flakes
came from this: deferred-159 (47 vs master's 44, recorded in `.github/scripts/assert-context-count.sh`'s
header) and deferred-156 (PR #282: 48 vs ceiling 45, a re-run gave 44; recorded in the project's
release-workflow notes, **not** in the script header). That script's own header already says the
real fix is "remove a context configuration … so the suite sits below 32 distinct keys with room to
spare". This story is that fix.

**Measured, deferred-159's green `pr-build` run 40016101569 (2026-10-23):** final failsafe
statistics line `size = 32, maxSize = 32, parentContextCount = 0, hitCount = 32936, missCount = 47`
(cache full). Early lines read `size = 6 … missCount = 7`, `size = 7 … missCount = 8`: once
`RateLimitingAspectIT`'s `@DirtiesContext(AFTER_CLASS)` has run, its context leaves the cache while
`missCount` keeps counting it, so **`size = missCount − 1` is the no-eviction steady state**, not
`size == missCount`. Surefire ran 2253 tests, failsafe 1491.

**Spring's cache key compares field names, not only types** (`BeanOverrideHandler.equals`, spring-test
6.2.19, checked with `javap`): bean type, bean name, context name and strategy, and for by-type
overrides also the **field name** and the qualifier-annotation set. Today every
`@MockitoBean VideoProviderAdapter` is named `videoProviderAdapter` (27 declarations) and every
`@MockitoBean FileStorageService` is named `fileStorageService` (7), which is why K02's 16 classes
share one key. Any added or moved declaration must keep those exact names.

> Correction recorded for future readers: a first-pass grep suggested `RescheduleResourceIT`,
> `ConfigStartupAssertionTest`, `ExecutorShutdownConfigurationTest`, `SesPropertiesValidationTest`
> and the two `*SchedulerLockTest`s declare `@SpringBootTest`. They do not — the hits were in
> comments/javadoc. Strip comments before counting (as `why-inheritance-over-import.md`'s appendix
> already says).

### Per-key inventory (comment-stripped, `extends` chains resolved, base annotations subtracted)

AIT = extends `AbstractIntegrationTest` (directly or via a `Base*IT`). Mocks are *in addition to*
the base's `GeminiClient`.

| Key | Classes | Delta from base | Lever |
|---|---|---|---|
| K01 | 141 | — | target |
| K02 | AdminVideoIT, DrillUploadResourceIT, DrillUploadServiceConcurrencyIT, HomeworkResourceIT, MinorSafetyGateIT, OrphanedProviderAssetSweepIT, PlaybackServiceConcurrencyIT, PlaybackServiceIT, RadarAssessmentResourceIT, RadarDisplayResourceIT, ReconciliationWorkerIT, SessionBuilderResourceIT, SessionTemplateResourceIT, SkillExposureResourceIT, SluCalculationServiceIT, VideoApprovalServiceIT (16) | mock VideoProviderAdapter | L7 |
| K03 | FileStorageConfirmUploadIT, FileStorageDeletionIT, FileStorageDownloadIT, FileStorageServiceIT, OutboxReplicationJobRepositoryIT, ReportGenerationServiceIT, S3StorageServiceIT, StorageMigrationServiceIT (8, `BaseStorageIT`) | `@Import(StorageTestConfig)` | keep |
| K04 | ActiveBookingsErasureBlockIT, CoachMarketplaceResourceIT, CoachProfileResourceIT, GdprExportIT | mock FileStorageService | L7 |
| K05 | VideoRetryUploadIT, VideoUploadConfirmationIT, VideoUploadInitializationIT | mock QuotaService, VideoProviderAdapter | keep (QuotaService = SUT elsewhere) |
| K06 | ModerationOutboxIT, RegistrationEmailDurabilityIT | `@TestPropertySource enable.test.mail=false` (real MailManager) | keep |
| K07 | SimultaneousExpiryIT, YearlyExemptionRenewalIT — **both class-level `@Disabled`, never loaded** | `@TestPropertySource platform.video.lifecycle.outbox_max_attempts=3, batch_size=50` + mock PlayerSubscriptionQueryPort, VideoLifecycleService | L5 (hygiene only) |
| K08 | BatchPaymentIT, PaymentWebhookIdempotencyIT | mock BookingService | keep |
| K09 | SubscriptionLifecycleIT, SubscriptionServiceStripeReconciliationIT | mock StripeClient | keep |
| K10 | VideoWebhookResourceIT | `@AutoConfigureMockMvc` | L4 |
| K11 | AccountDeletionCascadeIT | mock VPA + spy VideoDeletionOutboxRepository | L6 |
| K12 | QuotaServiceConcurrencyIT | mock QuotaConfigService | keep |
| K13 | VideoDeletionOutboxProcessorIT | mock VPA + spy DrillVideoRefRepository, VideoDeletionOutboxRepository | L6 |
| K14 | PlaybackRevocationIT | `@TestPropertySource app.video.playback.revocation-window-hours=24` + mock VPA | keep |
| K15 | WebhookPipelineIT | `@TestPropertySource app.video.webhook.max-attempts=2` + mock ModerationOrchestrationService, VPA | keep |
| K16 | VideoSubscriptionLifecycleListenerIT | `@TestPropertySource outbox_max_attempts=2, batch_size=50` + same mocks as K07 | L5 |
| K17 | VideoUploadPipelineIT | mock ModerationOrchestrationService, QuotaService, VPA | keep |
| K18 | VideoPurgedEventIT | mock VPA + spy VideoPhysicalDeletionListener | L6 |
| K19 | ConfigResourceIT | `@Import(E2ESecurityConfig)` | L3 |
| K20 | UserProfileServiceIT | nested `@TestConfiguration` (AccountChangeEventCaptor) | L2 |
| K21 | ParentDevelopmentPortalResourceIT | mock CoachProfileService, FileStorageService, VPA | keep (optional) |
| K22 | PlayerTimelineResourceIT | mock FileStorageService, VPA | L7 |
| K23 | CaptureReservationIT | spy PaymentGateway, SessionPackPurchaseRepository | L6 |
| K24 | ReliabilityStrikeConcurrencyIT | `@Import(StrikeEventCapture)` | L2 |
| K25 | StorageResourceIT | `@Import(E2ESecurityConfig)` on top of `BaseStorageIT` | L3 |
| K26 | GdprErasureIT | mock FileStorageService + spy RefreshTokenRepository | L6 |
| K27 | AdminCoachEnforcementConcurrencyIT | `@Import(ReinstateEventCapture)` | L2 |
| K28 | AdminCoachEnforcementIsolationRuntimeIT | spy CoachProfileRepository | L6 |
| K29 | ModerationFailClosedIT | `@Import(FailureEventCapture)` | L2 |
| K30 | SmtpTransportBootIT | `@DynamicPropertySource` (deliberate, ses-1.2) | keep |
| K31–K37 | VideoApprovalResourceIT, VideoUploadResourceIT, VideoPlayResourceIT, PlayerUploadResourceIT, VideoListQuotaResourceIT, VideoDeleteResourceIT, VideoSseIT | `@WebMvcTest` slices | L1 |
| K40–K46 | SesCutoverPreflightResourceIT, StripeOnboardingResourceIT, SessionPackPaymentResourceIT, AdminFinanceResourceIT, SubscriptionResourceIT, CreditWalletResourceIT, PlayerSubscriptionOwnershipIT | `@WebMvcTest` slices | L1 |
| K47 | MessageModerationSweeperIT | standalone **full** `@SpringBootTest` (no WireMock, own ShedLock helper) + fully-qualified `@MockitoSpyBean MessageRepository` (lines 48–49) | L6 + L8 |
| K48 | RateLimitingAspectIT | sliced `@SpringBootTest(classes=…)` + `@DirtiesContext(AFTER_CLASS)` | keep |
| K49 | PropertiesFeatureToggleServiceIT | sliced `@SpringBootTest(classes=…)` | keep |
| K50 | SesEmailEndToEndIT | sliced `@SpringBootTest` + own WireMock `ses-service` | keep |
| (surefire) | AdminLoginResourceTest, SluRetrierProxyRetryTest | slice / `@SpringJUnitConfig` | already in surefire |

`EmailTransportBootIT` builds its own `SpringApplicationBuilder` contexts — they bypass the test
context cache and are not counted.

### Lever budget (live failsafe distinct keys: 47 → ~17)

| Lever | Change | Keys saved | Running total |
|---|---|---|---|
| L1 | rename the 14 `@WebMvcTest` `*IT` slices to `*WebMvcTest` (surefire JVM) | −14 | 33 |
| L2 | one shared event captor in `TestConfig` replaces the 4 per-class capture configs | −4 | 29 |
| L3 | drop the redundant `@Import(E2ESecurityConfig)` from ConfigResourceIT / StorageResourceIT | −2 | 27 |
| L4 | hoist `@AutoConfigureMockMvc` onto `AbstractIntegrationTest` | −1 | 26 |
| L5 | delete the dead `platform.video.lifecycle.*` `@TestPropertySource`s (hygiene: K07 is `@Disabled`, K16 stays unique on its mocks) | 0 | 26 |
| L6 | hoist the 8 `@MockitoSpyBean` types onto `AbstractIntegrationTest` | −6 | 20 |
| L7 | unify the VPA-only / FSS-only / VPA+FSS mock sets into one {VPA, FSS} set | −2 | 18 |
| L8 | move MessageModerationSweeperIT onto `AbstractIntegrationTest` (shares K01 only because L6 hoists its `MessageRepository` spy) | −1 | 17 |

These are estimates from static analysis; the CI statistics line is the authority. With 17 live keys
and `RateLimitingAspectIT` dirtying its own, expect a final line of about `size = 16, missCount = 17`.
L1–L5 are near-mechanical; L6–L8 need the trap-checks below. **The hard AC is ≤ 24 live keys, not
17.** L1–L5 alone reach only 26, so **L6 or L7 is required**, and losing L3 as well leaves no
margin. If a lever's trap-check fails, record why and drop it rather than force it.

## Acceptance Criteria

1. **AC1 — Below the cap with headroom.** In the `pr-build` CI log, the *last* Spring
   `DefaultContextCache` statistics line for the failsafe JVM shows `missCount ≤ 24`,
   `size < maxSize`, and `missCount − size ≤ 1`, meaning no eviction rebuilds. The allowed 1 is
   `RateLimitingAspectIT`'s `@DirtiesContext(AFTER_CLASS)`, which removes its context from the cache
   but stays counted in `missCount`; it is 0 only if that class happens to run last. Record
   `size`/`missCount` from three consecutive CI runs of the final commit in Completion Notes.
   `missCount` must be identical across all three, since determinism is the point. Removing
   `RateLimitingAspectIT`'s `@DirtiesContext` is out of scope.
2. **AC2 — Slice tests leave the failsafe JVM (L1).** The 14 `@WebMvcTest` classes listed under
   K31–K37/K40–K46 are renamed `*IT → *WebMvcTest` (file and class name, e.g.
   `VideoApprovalResourceIT → VideoApprovalResourceWebMvcTest`). Plain `*Test` names would collide
   with existing files: `VideoApprovalResourceTest.java` and `SesCutoverPreflightResourceTest.java`
   already exist. `*WebMvcTest` still matches surefire's default `**/*Test.java` include. Also update:
   - the cross-class reference `SubscriptionResourceIT:38` → `PlayerSubscriptionOwnershipIT.TestSecurityConfig`;
   - the javadoc `{@link SesCutoverPreflightResourceIT}` in `SesCutoverPreflightResourceTest`;
   - any other `grep -rn '<OldName>'` hits in `src/` and `docs/`.

   They run and pass under surefire (`mvn test`). Test count per class is unchanged: compare
   `target/surefire-reports` after against `target/failsafe-reports` before.
3. **AC3 — Shared event capture (L2).** A single test-scoped captor bean (e.g.
   `config/RecordedEvents`, declared in `TestConfig`) records every published event into a
   thread-safe list and is cleared before each test method (via `DatabaseResetTestExecutionListener`
   or a dedicated listener registered on `AbstractIntegrationTest`'s `@TestExecutionListeners`, which
   does **not** contribute to the cache key). `ReliabilityStrikeConcurrencyIT`,
   `AdminCoachEnforcementConcurrencyIT`, `ModerationFailClosedIT` and `UserProfileServiceIT` use it and
   lose their own `@Import`/nested `@TestConfiguration`. Their existing assertions are unchanged in
   meaning (they still filter by event type and coach/user id). The captor exposes a public `clear()`:
   `UserProfileServiceIT`'s `AccountChangeEventCaptor` is cleared mid-test (around line 188), and
   that call must keep working.
   **Do NOT use Spring's `@RecordApplicationEvents`/`ApplicationEvents`:** it records per *thread*
   (`ApplicationEventsHolder` is ThreadLocal), and two of these four are concurrency tests whose
   events are published on worker threads — the assertions would see nothing.
4. **AC4 — Redundant `E2ESecurityConfig` import removed (L3).** Both users also carry
   `@Sql(SecurityIT.SEC_DATA_SQL_PATH)`, which inserts the same `main.sec` row (id
   `659287191260154475`), and `E2ESecurityConfig` only seeds on `ContextRefreshedEvent` — i.e. before
   `DatabaseResetTestExecutionListener.beforeTestMethod` truncates `main.sec` (not Flyway-seeded, see
   that listener's `flywaySeededTables()` javadoc). Verify that reasoning by running both classes
   without the import; if green, remove the import from both. If `E2ESecurityConfig` then has no
   users, delete it. If either class fails, keep its import and record why.
5. **AC5 — MockMvc on the base (L4).** `@AutoConfigureMockMvc` moves from `VideoWebhookResourceIT`
   to `AbstractIntegrationTest` (adds a `MockMvc` bean to every context; no behaviour change for
   RANDOM_PORT tests).
   **This breaks `IntegrationTestConventionTest` unless `isSlice` is fixed in the same commit.**
   `isSlice` (`IntegrationTestConventionTest.java:92-101`) reads `c.getAnnotations()`, which includes
   inherited annotations. It treats anything in `org.springframework.boot.test.autoconfigure.*` as a
   slice, and `@AutoConfigureMockMvc` is `@Inherited` (checked with `javap`, spring-boot-test-autoconfigure
   3.5.16). After L4, every AIT subclass would be classified as a slice and filtered out, and the
   `hasSizeGreaterThan(100)` guard (line ~132) would fail. Fix: drop the package-prefix rule and match
   only the slice *test* annotations (`WebMvcTest`, `DataJpaTest`, `JsonTest`, `WebFluxTest`,
   `JdbcTest`, `RestClientTest`, …). This also fixes a live gap: `VideoWebhookResourceIT` is excluded
   from every convention check today for this same reason.
6. **AC6 — Dead `@TestPropertySource` removed (L5).** `platform.video.lifecycle.outbox_max_attempts`
   and `platform.video.lifecycle.batch_size` are **DB-backed** (`ConfigService.getBoundedLong`/
   `getBoundedInt` read `main.platform_config`; no `@Value`/`@ConfigurationProperties`/`Environment`
   binding exists — `grep -rn "platform.video.lifecycle" src/main/java`).
   `VideoSubscriptionLifecycleListenerIT` already writes the value into `platform_config` itself
   (`UPDATE … SET value = '2'`, restored to '5'). Delete the `@TestPropertySource` from
   `SimultaneousExpiryIT`, `YearlyExemptionRenewalIT` and `VideoSubscriptionLifecycleListenerIT`, and drop
   `EXPECTED_TEST_PROPERTY_SOURCE_COUNT` 7 → 4 (its javadoc's list loses "video-lifecycle outbox
   attempts"). This is hygiene and **saves no live key**:
   - The two K07 classes are class-level `@Disabled` ("Deferred from 6.4 …") and never load a context.
   - Their `@BeforeEach` SQL uses a `config_key` column that does not exist (the real column is `key`,
     `V138__baseline_schema.sql:2678`), so they could not pass if re-enabled.

   Leave them disabled and untouched beyond the annotation removal, and add a `deferred-work.md` entry
   ("SimultaneousExpiryIT/YearlyExemptionRenewalIT: disabled since 6.4, broken `config_key` SQL —
   fix or delete").
7. **AC7 — Spies hoisted to the base (L6), each trap-checked.** Move these `@MockitoSpyBean`
   declarations to `AbstractIntegrationTest` (protected fields, same names where possible; remove the
   local ones): `VideoDeletionOutboxRepository`, `DrillVideoRefRepository`,
   `VideoPhysicalDeletionListener`, `PaymentGateway`, `SessionPackPurchaseRepository`,
   `RefreshTokenRepository`, `CoachProfileRepository`, `MessageRepository` (from
   `MessageModerationSweeperIT:48-49`, which uses a fully-qualified declaration; without it L8 saves
   nothing). Keep each spy's existing field name, because the key compares field names. Where two
   classes would need different names for the same type, rename the local uses instead.
   Rationale: a spy delegates to the real bean, so unlike a mock it cannot replace the system under
   test. `@MockitoSpyBean.reset()` defaults to `MockReset.AFTER`, so stubbing cannot leak between tests
   (checked 2026-10-24 with `javap -v` on spring-test 6.2.19: `AnnotationDefault = MockReset.AFTER`).
   deferred-19 only *asked* for this check (its line ~270) and recorded the spy audit as not done, so
   don't cite it as the source.
   Per-type trap-check, recorded in Completion Notes:
   - the spy class's `doReturn/doThrow` stubbing in its original test still takes effect (run it);
   - no other IT asserts on the bean's concrete class or uses `AopUtils`/`AopTestUtils` against it;
   - `PaymentGateway`: `TestConfig` already provides `StubPaymentGateway` as `@Primary` — the spy
     must wrap that bean, confirm with `CaptureReservationIT` + one payment-family IT;
   - `VideoPhysicalDeletionListener` is an event listener (`@Async` + `AFTER_COMMIT`): confirm events
     still reach the spied bean in a K01 class that triggers physical deletion, not only in
     `VideoPurgedEventIT`. Also watch for the after-test `MockReset.AFTER` racing an async invocation
     left over from the previous test (speculative, no failure observed; if it shows up, keep this spy
     local).
   If any type fails its check, leave it local and record why.
8. **AC8 — One external-provider mock set (L7).** Classes currently mocking only
   `VideoProviderAdapter` (K02, and K11/K13/K18 after AC7) or only `FileStorageService` (K04, and K26
   after AC7) converge on the same set {VideoProviderAdapter, FileStorageService} as
   `PlayerTimelineResourceIT`. Implement **per class or per family base — never on the
   `AbstractIntegrationTest` root**: deferred-19 tried the VPA root hoist and broke
   `VideoWebhookResourceIT` (5 failures, reverted); `VideoLifecycleLogIT`/`VideoRepositoryIT` reference the
   Bunny/WireMock path without mocking the adapter (verify whether they drive it), and `CoachProfileSelfEditIT` + the K03 storage family use the real
   `FileStorageService` (SeaweedFS). Preferred shape: a new abstract
   `AbstractMockedProvidersIT extends AbstractIntegrationTest` is not possible for `BaseVideoIT`/
   `BaseSessionIT` subclasses (single inheritance), so declare the missing field per class. Use
   **exactly** `@MockitoBean VideoProviderAdapter videoProviderAdapter;` and
   `@MockitoBean FileStorageService fileStorageService;`: field-level, these names, no qualifier,
   default attributes. By-type `BeanOverrideHandler.equals` compares the field name and the qualifier
   set (see Background), so a different name or a type-level `@MockitoBean(types=…)` forks a key.
   Trap-check per class: an added `FileStorageService` mock must not sit under a code path the test
   asserts on (e.g. a presigned URL or stored object). Run each touched class.
9. **AC9 — MessageModerationSweeperIT joins the base (L8).** It extends `AbstractIntegrationTest`,
   drops its own `@SpringBootTest`/`@ActiveProfiles`/`@Import(TestConfig)`, uses the inherited
   `releaseSchedulerLock` instead of its copy, and is removed from the convention test's `ALLOWLIST`
   (whose comment wrongly calls it "sliced"). Its seeding already sits in `@BeforeEach`, so it
   survives the base's per-method truncate. Its private `releaseSchedulerLock` is identical to the
   base's, so delete it. It reaches K01 only if its `@MockitoSpyBean MessageRepository` is hoisted by
   AC7; if that spy fails its trap-check, L8 still removes a *full* standalone context, but it then
   saves no key.
10. **AC10 — Guardrail closes the mock-set gap.** `IntegrationTestConventionTest` gains:
    a) a test that computes, by reflection over the compiled concrete `*IT` classes extending
       `AbstractIntegrationTest` that are **not** class-level `@Disabled`, each class's context-key
       model, and pins the number of distinct models at `EXPECTED_DISTINCT_CONTEXT_KEYS`. The model:
       - class-level context annotations along the hierarchy, including all attribute values
         (`@Import`, `@TestPropertySource`, `@AutoConfigure*`, `@DirtiesContext`), plus
         `@DynamicPropertySource` method presence and nested `@TestConfiguration` classes;
       - for every `@MockitoBean`/`@MockitoSpyBean` field along the hierarchy: annotation type, all
         attribute values (`name`, `reset`, `answers`, `extraInterfaces`, …), field type, field name and
         qualifier annotations — mirroring `BeanOverrideHandler.equals`;
       - type-level `@MockitoBean(types=…)` declarations, if any appear.

       Over-distinguishing is acceptable (the CI gate cross-checks reality); under-distinguishing is
       not. On mismatch the failure message prints every key with its classes (sorted), so the
       offending class is obvious without any offline script;
    b) a test that fails if any `*IT` class carries a slice annotation (`isSlice`) — slices must be
       named `*Test` so they stay out of the failsafe JVM.
    The pinned count's javadoc follows the existing `EXPECTED_TEST_PROPERTY_SOURCE_COUNT` style
    ("If you are here because this assertion failed …").
11. **AC11 — CI gate tightened and taught about capacity.**
    `.github/scripts/assert-context-count.sh`: `CEILING` set to the measured `missCount` from AC1
    (both the script default and `pr-build.yml`'s explicit argument), with a header entry in the
    existing style (before/after numbers, run IDs). The script additionally parses
    `size = N, maxSize = M` from the same last statistics line and **fails if `N >= M`** with a
    message explaining eviction. It also fails if `missCount − size > 1`, which means rebuilds
    happened (the 1 is `RateLimitingAspectIT`'s dirtied context, see AC1). The failure text's
    reference to the non-existent `python3 scratchpad/ctxkeys.py` is replaced by a pointer to the
    AC10a test.
12. **AC12 — Docs and stale comments.** Update:
    - `docs/testing/readme.md`: context numbers at lines ~102 and ~175 ("34"), the slice rule, and the
      "known gaps" at ~86–90, which are already closed (`ModerationFailClosedIT` extends the base;
      `ConfigResourceIT`'s `AFTER_EACH_TEST_METHOD` was removed);
    - `docs/testing/why-inheritance-over-import.md`: the mock-set guard now exists, and slices are
      `*WebMvcTest`;
    - `docs/deployment/baseline/github-build.md:78`: still quotes `build.log 45`;
    - the `pom.xml` failsafe comment (~688–690): "~26 contexts … nothing is ever evicted" is false today;
    - the convention test's `isSlice` javadoc: "129 of the 144 `*IT` files";
    - the `application-test.yaml:86` comment: "ComponentConfig:27" should be `:34`;
    - `AbstractIntegrationTest`'s javadoc where it lists what the base carries.
13. **AC13 — No assertion weakened.** Same rule as deferred-19 line ~600: this story changes how tests
    are *configured*, never what they *assert*. Test counts per class, before and after, are recorded
    (surefire and failsafe separately). A failing test is fixed by seed data or a corrected
    assumption, or the lever is dropped — never by deleting/weakening an assertion.

## Tasks / Subtasks

- [x] T1 Baseline (AC1, AC13): record from the latest master `pr-build` log the last cache statistics
      line (`grep -aoE 'size = [0-9]+, maxSize = [0-9]+.*missCount = [0-9]+' build.log | tail -1`) and
      per-class test counts from `failsafe-reports`/`surefire-reports` artifacts.
- [x] T2 L1 rename slices to `*WebMvcTest` (AC2): `git mv`, class rename, fix the
      PlayerSubscriptionOwnership cross-ref and the `SesCutoverPreflightResourceTest` javadoc link;
      `mvn -o test -DskipFrontend -Dtest='*WebMvcTest'`.
- [x] T3 L2 shared event captor (AC3).
- [x] T4 L3 E2ESecurityConfig (AC4).
- [x] T5 L4 `@AutoConfigureMockMvc` (AC5), in the same commit as the `isSlice` fix.
- [x] T6 L5 dead `@TestPropertySource` (AC6) + pinned count 7 → 4.
- [x] T7 L6 spy hoist with per-type trap-check (AC7).
- [x] T8 L7 provider mock-set unification with per-class trap-check (AC8).
- [x] T9 L8 MessageModerationSweeperIT (AC9).
- [x] T10 Guardrail tests (AC10) — pin `EXPECTED_DISTINCT_CONTEXT_KEYS` to the post-change number;
      cross-check it against CI. The model covers live AIT keys only, so the expected
      `missCount` = model + 3 (K48–K50, the sliced standalone classes), and `size` = `missCount` − 1
      (RateLimitingAspectIT dirtied). SmtpTransportBootIT is an AIT class, so it is already in the
      model. Document the arithmetic in Completion Notes.
- [x] T11 Gate (AC11)
  - [x] T11.1 Script: `size >= maxSize` and `missCount − size > 1` checks, the `ctxkeys.py` pointer
        replaced, CEILING 47 → 17 (script default + `pr-build.yml`), all four outcomes exercised
        locally against deferred-159's real CI log and synthetic lines.
  - [x] T11.2 Push and confirm the first green `pr-build` measures `missCount = 17` (size 16 or 17);
        correct CEILING to the measured number if it differs. **Measured 17 / size 16 — no correction.**
  - [x] T11.3 Re-run `pr-build` twice more; record the three statistics lines here (AC1).
- [x] T12 Docs/comments (AC12).
- [x] T13 Scoped local runs of every touched IT class, e.g.
      `mvn -o verify -DskipFrontend -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<Class>`.
      These single-class failsafe runs follow the precedent of deferred-155/-156/-157 ("scoped
      failsafe"). **No full local `mvn verify`**: that is what the project rule forbids, and GitHub CI
      is the gate.

### Review Findings

Code review 2026-10-24 (`/bmad-code-review`, uncommitted changes vs HEAD `1b37d174`, Blind + Edge + Acceptance layers). 0 decision-needed, 7 patch, 2 defer, 19 dismissed.

- [x] [Review][Patch] Gate derives `missCount` and `size` from two different greps; derive `last` from the same final statistics line and guard non-numeric [.github/scripts/assert-context-count.sh:~187-197] — **fixed**: `last`, `size` and `maxSize` now all come from the one final statistics line; `sed -n …p` yields empty, not the raw line, on a mismatch, so the existing empty-checks fire. Five gate scenarios re-run, all correct.
- [x] [Review][Patch] Script header calls run 40016101569 "steady state: size = 6/missCount = 7 onwards" but that run's final line is `size = 32 … missCount = 47`; those were early lines — reword [.github/scripts/assert-context-count.sh:~29-30] — **fixed**: reworded as early lines before the cache filled, and the final 32/47 line is named as the state this story removes.
- [x] [Review][Patch] Ceiling 17 / "took it to 17" is documented as measured while T11.2/T11.3 are open; mark as predicted until CI measures it, and fix stale tracking text (sprint-status says 48 keys / "CREATED") [assert-context-count.sh, pr-build.yml, docs/testing/readme.md, docs/deployment/baseline/github-build.md, sprint-status.yaml] — **fixed**: marked PREDICTED in the script header, `pr-build.yml`, `docs/testing/readme.md` and `github-build.md`; sprint-status entry corrected (47 live keys, status in-progress).
- [x] [Review][Patch] `@Autowired CoachProfileRepository coachProfileRepository` hides the inherited hoisted spy of the same name; remove the local field or rename [ReliabilityStrikeConcurrencyIT.java:48, AdminCoachEnforcementConcurrencyIT.java:87] — **fixed, wider than flagged**: the hiding was harmless (the same spy bean was injected), but it was misleading, so all 9 such fields were removed, not just the 2 named: AuthResourceIT, AuthServiceSessionInvalidationIT, RefreshTokenRepositoryIT (refreshTokenRepository); ReliabilityStrikeConcurrencyIT, CoachVisibilitySuppressionIT, AdminCoachEnforcementConcurrencyIT, SessionPackPurchaseRepositoryIT (coachProfileRepository); SessionPackPurchaseRepositoryIT (sessionPackPurchaseRepository); MessageRetentionRepositoryIT (messageRepository). 47 tests green, one context.
- [x] [Review][Patch] Unused `SpringBootTest` import [VideoWebhookResourceIT.java:13] — **fixed**. Note: pre-existing (present and unused at HEAD), not introduced by this story.
- [x] [Review][Patch] AC12 doc gaps: `docs/testing/readme.md` still presents "34", "ceiling 36", the `E2ESecurityConfig` row and two sliced classes as current (:106, :216-247); `AbstractIntegrationTest` class javadoc does not list `@AutoConfigureMockMvc` among what the base carries [docs/testing/readme.md, AbstractIntegrationTest.java] — **partly a false positive, the rest fixed.** The readme's "34", "ceiling 36", `E2ESecurityConfig` row and two-sliced-classes text (:106, :216-247) already sit under explicit "Superseded"/"Historical (as of deferred-19)" banners added by this story. They are deferred-19's record and were kept deliberately. Fixed instead: a real gap the review missed — the readme's *current* "Writing a new integration test" rule 3 still said "accept that your class pays a context" — rewritten for the pinned guard, and a rule 8 (slice naming) added; `AbstractIntegrationTest` javadoc now lists what the base supplies (MockMvc, GeminiClient mock, eight spies, reset listener).
- [x] [Review][Patch] AC8/AC13 record thin: per-class FSS/VPA mock trap-check is one sentence and per-class (or post-change surefire/failsafe) test counts are not listed in Completion Notes [story Dev Agent Record] — **fixed**: per-class AC8 trap-check and counts added to Completion Notes.
- [x] [Review][Defer] Convention test swallows class-load failures (`catch (Throwable ignored)`), so a non-loading IT silently drops out of the pinned key count [IntegrationTestConventionTest.java:~169] — deferred, pre-existing pattern
- [x] [Review][Defer] Gate takes the last statistics line of the whole log; if failsafe never logs, surefire's line is evaluated against the failsafe ceiling [assert-context-count.sh:~187-197] — deferred, pre-existing `tail -1` design

## Dev Notes

### Guardrails (read before touching anything)

- **Do not hoist `VideoProviderAdapter` or `FileStorageService` to the root.** See AC8 and
  deferred-19 story lines ~263–266 and ~1178 (`_bmad-output/implementation-artifacts/skillars-deferred-19-test-context-container-consolidation.md`).
- **Do not hoist internal services** (`BookingService`, `CoachProfileService`, `QuotaService`,
  `QuotaConfigService`, `VideoLifecycleService`, `ModerationOrchestrationService`, `StripeClient`,
  `PlayerSubscriptionQueryPort`) — each has real ITs; a root mock would make them assert nothing.
  K05/K08/K09/K12/K15/K17/K21 stay as they are.
- `K06` (`enable.test.mail=false`) is genuine: it selects the real `MailManager`
  (`platform/notification/config/ComponentConfig.java:34`, `@ConditionalOnProperty … matchIfMissing = true`
  vs `application-test.yaml:93 enable.test.mail: true`). Keep.
- `@TestExecutionListeners` does not contribute to `MergedContextConfiguration` (base class comment) —
  safe place for the captor reset.
- Event captor: listen with `@EventListener` on `Object` (or `ApplicationEvent` + unwrap
  `PayloadApplicationEvent`), store in `CopyOnWriteArrayList`, expose `ofType(Class<T>)`. Existing
  captors use plain `@EventListener` (publish-time, not `@TransactionalEventListener`) — keep that
  semantics so the concurrency tests see the same events at the same moment.
- Near cache capacity, one fork can cost +2 (`assert-context-count.sh` header). Once this story lands,
  `size` should be far from 32 and `missCount − size ≤ 1`, so the gate becomes exact. If CI ever shows
  `missCount − size > 1` after this story, a new `@DirtiesContext` appeared or the suite crossed capacity.
- A red surefire (which now also holds the 14 slices) stops the build before failsafe runs, so
  `build.log` then contains only surefire statistics lines. The gate reading them is harmless, since
  the build is already red.
- `RateLimitingAspectIT` keeps `@DirtiesContext(AFTER_CLASS)` (out of scope); it rebuilds nothing
  shared because its key is unique.
- Moving slices to surefire removes them from the gate's population (the gate reads the *last*
  statistics line, which is failsafe's). That is intended: the gate protects the expensive JVM.
  Surefire will hold ~16 cheap slice contexts — far below 32.

### Files to touch

- `src/test/java/com/softropic/skillars/config/AbstractIntegrationTest.java` — L4, L6, javadoc.
- `src/test/java/com/softropic/skillars/config/TestConfig.java` — L2 captor bean.
- `src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java` — AC6 count, AC9
  allowlist, AC10 tests.
- `src/test/java/com/softropic/skillars/config/E2ESecurityConfig.java` — possibly deleted (AC4).
- The 14 slice classes (rename), ~30 IT classes for L2/L3/L5–L8 (see inventory table).
- `.github/scripts/assert-context-count.sh`, `.github/workflows/pr-build.yml` (line ~75–85).
- `pom.xml` (failsafe comment ~line 688), `docs/testing/readme.md`,
  `docs/testing/why-inheritance-over-import.md`.

### Testing

- Scoped local runs per touched class; surefire `mvn test` for the renamed slices and the convention
  test. Full suite only in CI ([feedback: no local mvn verify]).
- Budget for 2–3 CI round trips: the first to measure, then CEILING update, then determinism re-runs.
- IT write-commit gotcha if a test seeds via `jdbcTemplate`: wrap in `transactionTemplate`
  (hikari auto-commit=false).

### Previous-story intelligence

- **deferred-19** (`skillars-deferred-19-test-context-container-consolidation.md`): built
  `AbstractIntegrationTest`, the convention test, the CI gate; established that 20 was the floor for
  `@SpringBootTest` configurations *without* per-family sub-bases; flagged slices inflating `missCount`.
- **deferred-83 / -123 / -124 / -128 / ses-1.2**: each bumped CEILING by +1/+2 for a new
  mock/spy/property fork — the unguarded-mock-set pattern this story closes (AC10a).
- **deferred-156 (PR #282) / deferred-159 (PR #296)**: the count flaked on class order alone, with
  zero context-affecting changes, at `size = maxSize = 32`. 159 raised CEILING 45 → 47 for it; 156
  passed on a re-run. This is the symptom AC1 removes.

### References

- `.github/scripts/assert-context-count.sh` (header history, CEILING=47)
- `docs/testing/why-inheritance-over-import.md` (appendix: reproducing the numbers)
- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java:528-545`
  (`main.sec` not Flyway-seeded)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListener.java:109,125`
  (DB-backed lifecycle config)
- Static analysis used for the inventory: comment-stripped, hierarchy-resolved grouping by
  (class-level context annotations, override field types). Re-derive with the AC10a test once it exists.

## Story review response (2026-10-24)

Reviewed against `_bmad-output/implementation-artifacts/story-review.md`. Every finding was
re-verified at HEAD before acting, including checks the review marked [agent-only] or left
unconfirmed (`javap` on spring-test 6.2.19 and spring-boot-test-autoconfigure 3.5.16, and the real
CI log of run 40016101569).

| Finding | Verdict | Change |
|---|---|---|
| F1 `isSlice` + inherited `@AutoConfigureMockMvc` | Confirmed (`@Inherited` in bytecode) | AC5 rewritten; `isSlice` fix in the same commit |
| F2 K07 `@Disabled`, `config_key` | Confirmed | Baseline 48 → 47 live; L5 = hygiene, 0 keys; deferred-work entry |
| F3 `MessageRepository` spy missed | Confirmed (fully-qualified declaration slipped past the analyzer's regex) | 8th spy in AC7; AC9 dependency stated |
| F4 rename collisions | Confirmed (both files exist) | `*WebMvcTest` suffix |
| F5 `missCount == size` unreachable | Confirmed **empirically** from the CI log (`size = missCount − 1` after RateLimitingAspectIT) | AC1/AC11 use `missCount − size ≤ 1` |
| F6 budget thinner | Confirmed | Lever table recomputed (47 → 17; L6 or L7 mandatory) |
| F7 missed stale locations | Confirmed | AC12 list extended |
| F8 T13 vs no-local-verify rule | **Partly a false positive.** The rule forbids *full* local `mvn verify` as pre-push validation. Scoped single-class failsafe runs are the established practice (deferred-155/-156/-157) | T13 command made concrete; no user sign-off needed |
| F9 side effects | Accepted as low/speculative | Noted in AC7 / Dev Notes |
| deferred-159 listed as fork-bumper | Confirmed wrong | Fixed |
| deferred-156 "48 vs 45" "likely wrong" | **False positive.** PR #282 measured 48 against ceiling 45 (a re-run gave 44); the source is the release-workflow notes, not the script header the review searched | Kept; source cited correctly |
| deferred-19 credited with the spy reset check | Attribution wrong, **claim true**: `MockitoSpyBean.reset()` `AnnotationDefault = MockReset.AFTER` (javap) | Re-sourced to the bytecode check |
| AC8 "may compare field annotations" understated | Confirmed: `equals` compares field **name** and qualifiers | Background + AC8 require exact field names. Also checked that every existing VPA/FSS declaration already uses the same name, so the K02 grouping stands |
| AC10a model insufficient | Confirmed | Model now mirrors `BeanOverrideHandler.equals` and excludes `@Disabled` classes |
| "20 is the floor for AIT keys" wording | Confirmed imprecise | Reworded |

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (1M context), /bmad-dev-story, 2026-10-24.

### Debug Log References

- Baseline: `pr-build` run 40016101569 (deferred-159, green): last failsafe line
  `size = 32, maxSize = 32, parentContextCount = 0, hitCount = 32936, missCount = 47`; surefire
  2253 tests, failsafe 1491. Per-class counts extracted from that log were compared after every
  scoped run below: no class changed its test count.
- Scoped runs (all `BUILD SUCCESS`, all per-class counts equal to baseline):
  - T2: `mvn -o test -Dtest='*WebMvcTest'` → 14 classes, 121 tests.
  - T3: 4 classes, 24 tests, `missCount = 1`.
  - T4: 2 classes, 11 tests, `missCount = 2` (base and BaseStorageIT).
  - T5: VideoWebhookResourceIT + AuthResourceIT + SecurityIT, 32 tests, `missCount = 1`, convention test green.
  - T6: VideoSubscriptionLifecycleListenerIT 6/6.
  - T7: 18 classes, 170 tests, `missCount = 3` (base, VPA group, GdprErasureIT's FSS group, before T8).
  - T8: 25 classes, 288 tests, `missCount = 1` — the whole provider-mock group shares one context.
  - T9: MessageModerationSweeperIT 6/6 + AuthResourceIT 18/18, `missCount = 1`.
  - Final: `mvn -o test -Dtest='IntegrationTestConventionTest,*WebMvcTest,VideoSubscriptionLifecycleListenerTest'` → 128 tests.
- T10 red check: with `EXPECTED_DISTINCT_CONTEXT_KEYS = 13` the test failed and printed all 14
  configurations with their classes (base-only: 150 classes); reset to 14.
- One shell mishap, no effect: a heredoc whose body contained `EOF` lines was rejected by zsh at
  parse time, so nothing ran; the gate edit was redone from a script file.

### Completion Notes List

- **AC1 — met.** PR #297, `pr-build` run 40063190835, three attempts of the `build` job. Each ended
  on the identical final line
  `size = 16, maxSize = 32, parentContextCount = 0, hitCount = 30808, missCount = 17`.
  - The prediction matched exactly: 14 modelled configurations + 3 sliced standalone classes = 17,
    and `size` = 16 because RateLimitingAspectIT was dirtied.
  - Identical `hitCount` shows the run is fully deterministic.
  - Every attempt was green with surefire 2376 tests (1 skipped) and failsafe 1370 (4 skipped). That
    reconciles with the baseline: surefire 2253 + 121 renamed slices + 2 new convention tests = 2376,
    and failsafe 1491 − 121 = 1370.
  - The `build` job took 11m53s / 10m56s / 11m11s, against 13m29s for the deferred-159 baseline (run
    40016101569; one sample).
  - CEILING needed no correction.
- **AC2** — 14 slices renamed `*WebMvcTest`, 121/121 under surefire, counts unchanged. References
  fixed in `SesCutoverPreflightResourceTest` (javadoc link), `SubscriptionResourceWebMvcTest`
  (TestSecurityConfig import) and `SubscriptionServiceOwnershipIT` (javadoc). The historical
  CEILING entries in `assert-context-count.sh` keep the old names on purpose.
- **AC3** — `config/RecordedEvents` (TestConfig bean) listens on `ApplicationEvent` and unwraps
  `PayloadApplicationEvent`. With an `Object` parameter Spring would hand over the wrapper, and
  `ModerationFailureEvent` is a record, so it would never match. Cleared by
  `DatabaseResetTestExecutionListener.beforeTestMethod` right after the async quiesce, so a late
  async event from the previous test cannot leak in. The four tests keep their own `clear()` calls
  and assertions; only the lookup changed (`ofType(...)` + the same coach/action filters).
- **AC4** — confirmed by running both classes without the import: green. `E2ESecurityConfig.java`
  deleted (no users left); `AuthServiceSessionInvalidationIT`'s comment that named it was updated.
- **AC5** — `@AutoConfigureMockMvc` on the base. `isSlice` now matches slice *test* annotation
  names only, which also brings `VideoWebhookResourceIT` back under the convention checks.
- **AC6** — three dead `@TestPropertySource`s removed, count 7 → 4 (the remaining four:
  ModerationOutboxIT, RegistrationEmailDurabilityIT, PlaybackRevocationIT, WebhookPipelineIT). The
  two `@Disabled` classes were left otherwise untouched; ledger entry added to `deferred-work.md`.
- **AC7** — 8 spies on the base. Trap-check results:
  - Original stubbing classes all green: GdprErasureIT 44, AdminCoachEnforcementIsolationRuntimeIT 3,
    CaptureReservationIT 8, AccountDeletionCascadeIT 8, VideoDeletionOutboxProcessorIT 15,
    VideoPurgedEventIT 2, MessageModerationSweeperIT 6.
  - K01 classes exercising the same beans, all green: SessionPackPurchaseIT, PackExtensionIT,
    CoachPayoutOutboxIT (PaymentGateway); BookingServiceConcurrencyIT, CoachProfileServiceConcurrencyIT,
    ReinstateIT (CoachProfileRepository); ConversationResourceIT, MessageApproveIT (MessageRepository);
    AuthResourceIT (RefreshTokenRepository); DrillUploadResourceIT, DrillUploadServiceConcurrencyIT
    (DrillVideoRefRepository / physical deletion).
  - `SchedulerLockTransactionOrderingIT`, the one IT using `AopUtils` on beans, is green.
  - **Deviation:** the VideoDeletionOutboxRepository spy is named `videoDeletionOutboxRepository`,
    not `outboxRepository`. Six classes already `@Autowire` unrelated `OutboxMessageRepository`/
    `SubscriptionLifecycleOutboxRepository` fields called `outboxRepository`, which would hide the
    base field. The two users were updated. The other seven keep their original names.
  - The `@Async` listener reset race (speculative) was not observed.
- **AC8** — `@MockitoBean FileStorageService fileStorageService` added to 19 classes and
  `@MockitoBean VideoProviderAdapter videoProviderAdapter` to 5. All 25 classes (288 tests) run green
  in one context. No class reached a code path where a null from the new mock broke an assertion.
- **AC8 trap-check, per class (code-review follow-up).** Method: for each class that gained a mock,
  grep for any use of the new mock's field (none: 0 `fileStorageService.` uses in the 19 classes that
  gained it, 0 `videoProviderAdapter.` uses in the 5), then run it. A null from the new mock only
  matters on a code path the class asserts on, and every class passed with its baseline count. Test
  counts (T8 run, all 0F/0E/0S, equal to baseline):
  - gained `FileStorageService` (19): AdminVideoIT 10, DrillUploadResourceIT 13,
    DrillUploadServiceConcurrencyIT 5, HomeworkResourceIT 9, MinorSafetyGateIT 5,
    OrphanedProviderAssetSweepIT 5, PlaybackServiceConcurrencyIT 2, PlaybackServiceIT 4,
    RadarAssessmentResourceIT 9, RadarDisplayResourceIT 13, ReconciliationWorkerIT 5,
    SessionBuilderResourceIT 17, SessionTemplateResourceIT 22, SkillExposureResourceIT 13,
    SluCalculationServiceIT 17, VideoApprovalServiceIT 12, AccountDeletionCascadeIT 8,
    VideoDeletionOutboxProcessorIT 15, VideoPurgedEventIT 2;
  - gained `VideoProviderAdapter` (5): ActiveBookingsErasureBlockIT 3, CoachMarketplaceResourceIT 17,
    CoachProfileResourceIT 10, GdprExportIT 7, GdprErasureIT 44;
  - unchanged member: PlayerTimelineResourceIT 21.
  Total 288 tests, one context (`missCount = 1`). Post-change suite-wide surefire/failsafe totals
  come from CI (T11.2).
- **AC9** — MessageModerationSweeperIT extends the base, its copy of `releaseSchedulerLock` was
  deleted, and it was removed from ALLOWLIST. Its own `@AfterEach` cleanup was kept: it is harmless
  under the reset.
- **AC10** — `distinctContextKeyCountIsPinned` (14) and `noSliceTestIsNamedIT` added. The model
  mirrors `BeanOverrideHandler.equals` (annotation attributes, field type, field name, other field
  annotations) and skips class-level `@Disabled` classes. A discovery helper (`allConcreteItClasses`)
  was split out so the slice check can see `*IT` slices.
- **AC11** — met. Script done (T11.1). CEILING 17 confirmed by CI (T11.2/T11.3); the gate printed
  "OK" on all three attempts.
- **AC12** — readme "known gaps" closed, round-two section added with the rules, and deferred-19's
  sections marked historical. Also updated: why-inheritance (field-name rule, guard, appendix), the
  github-build CEILING reference, the pom failsafe comment, the application-test.yaml
  `ComponentConfig:34` pointer, the `isSlice` javadoc, and the AbstractIntegrationTest javadoc.
- **AC13** — no assertion was removed or weakened. Event assertions changed only how events are
  looked up. Per-class test counts are identical to baseline for every class run.
- **Local-only caveat:** a non-clean `target/test-classes` kept the old `*IT.class` files after the
  renames. They were deleted by hand. CI builds clean, so this does not affect it.

### File List

Added
- src/test/java/com/softropic/skillars/config/RecordedEvents.java

Deleted
- src/test/java/com/softropic/skillars/config/E2ESecurityConfig.java

Renamed (`*IT` → `*WebMvcTest`, contents: class name only, plus the cross-ref in SubscriptionResourceWebMvcTest)
- src/test/java/com/softropic/skillars/platform/notification/api/SesCutoverPreflightResourceWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/payment/api/AdminFinanceResourceWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/payment/api/CreditWalletResourceWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/payment/api/PlayerSubscriptionOwnershipWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResourceWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/payment/api/StripeOnboardingResourceWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/payment/api/SubscriptionResourceWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/video/api/PlayerUploadResourceWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/video/api/VideoApprovalResourceWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/video/api/VideoDeleteResourceWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/video/api/VideoListQuotaResourceWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/video/api/VideoPlayResourceWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/video/api/VideoUploadResourceWebMvcTest.java
- src/test/java/com/softropic/skillars/platform/video/service/VideoSseWebMvcTest.java

Modified — test infrastructure
- src/test/java/com/softropic/skillars/config/AbstractIntegrationTest.java
- src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java
- src/test/java/com/softropic/skillars/config/IntegrationTestConventionTest.java
- src/test/java/com/softropic/skillars/config/TestConfig.java
- src/test/resources/application-test.yaml

Modified — test classes
- src/test/java/com/softropic/skillars/platform/admin/api/ActiveBookingsErasureBlockIT.java
- src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java
- src/test/java/com/softropic/skillars/platform/admin/api/GdprExportIT.java
- src/test/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementConcurrencyIT.java
- src/test/java/com/softropic/skillars/platform/admin/service/AdminCoachEnforcementIsolationRuntimeIT.java
- src/test/java/com/softropic/skillars/platform/config/api/ConfigResourceIT.java
- src/test/java/com/softropic/skillars/platform/development/api/RadarAssessmentResourceIT.java
- src/test/java/com/softropic/skillars/platform/development/api/RadarDisplayResourceIT.java
- src/test/java/com/softropic/skillars/platform/development/api/SkillExposureResourceIT.java
- src/test/java/com/softropic/skillars/platform/development/service/SluCalculationServiceIT.java
- src/test/java/com/softropic/skillars/platform/filestorage/api/StorageResourceIT.java
- src/test/java/com/softropic/skillars/platform/marketplace/CoachProfileResourceIT.java
- src/test/java/com/softropic/skillars/platform/marketplace/api/CoachMarketplaceResourceIT.java
- src/test/java/com/softropic/skillars/platform/messaging/api/ModerationFailClosedIT.java
- src/test/java/com/softropic/skillars/platform/messaging/service/MessageModerationSweeperIT.java
- src/test/java/com/softropic/skillars/platform/notification/api/SesCutoverPreflightResourceTest.java
- src/test/java/com/softropic/skillars/platform/payment/service/CaptureReservationIT.java
- src/test/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeConcurrencyIT.java
- src/test/java/com/softropic/skillars/platform/payment/service/SubscriptionServiceOwnershipIT.java
- src/test/java/com/softropic/skillars/platform/security/service/AuthServiceSessionInvalidationIT.java
- src/test/java/com/softropic/skillars/platform/security/service/UserProfileServiceIT.java
- src/test/java/com/softropic/skillars/platform/session/api/DrillUploadResourceIT.java
- src/test/java/com/softropic/skillars/platform/session/api/DrillUploadServiceConcurrencyIT.java
- src/test/java/com/softropic/skillars/platform/session/api/HomeworkResourceIT.java
- src/test/java/com/softropic/skillars/platform/session/api/SessionBuilderResourceIT.java
- src/test/java/com/softropic/skillars/platform/session/api/SessionTemplateResourceIT.java
- src/test/java/com/softropic/skillars/platform/video/api/VideoWebhookResourceIT.java
- src/test/java/com/softropic/skillars/platform/video/service/AccountDeletionCascadeIT.java
- src/test/java/com/softropic/skillars/platform/video/service/AdminVideoIT.java
- src/test/java/com/softropic/skillars/platform/video/service/MinorSafetyGateIT.java
- src/test/java/com/softropic/skillars/platform/video/service/OrphanedProviderAssetSweepIT.java
- src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceConcurrencyIT.java
- src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java
- src/test/java/com/softropic/skillars/platform/video/service/ReconciliationWorkerIT.java
- src/test/java/com/softropic/skillars/platform/video/service/SimultaneousExpiryIT.java
- src/test/java/com/softropic/skillars/platform/video/service/VideoApprovalServiceIT.java
- src/test/java/com/softropic/skillars/platform/video/service/VideoDeletionOutboxProcessorIT.java
- src/test/java/com/softropic/skillars/platform/video/service/VideoPurgedEventIT.java
- src/test/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListenerIT.java
- src/test/java/com/softropic/skillars/platform/video/service/YearlyExemptionRenewalIT.java
- src/test/java/com/softropic/skillars/platform/security/api/AuthResourceIT.java (code-review P4)
- src/test/java/com/softropic/skillars/platform/security/repo/RefreshTokenRepositoryIT.java (code-review P4)
- src/test/java/com/softropic/skillars/platform/payment/service/CoachVisibilitySuppressionIT.java (code-review P4)
- src/test/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepositoryIT.java (code-review P4)
- src/test/java/com/softropic/skillars/platform/messaging/repo/MessageRetentionRepositoryIT.java (code-review P4)

Modified — CI, build, docs, tracking
- .github/scripts/assert-context-count.sh
- .github/workflows/pr-build.yml
- pom.xml (comment only)
- docs/testing/readme.md
- docs/testing/why-inheritance-over-import.md
- docs/deployment/baseline/github-build.md
- _bmad-output/implementation-artifacts/deferred-work.md
- _bmad-output/implementation-artifacts/sprint-status.yaml
- _bmad-output/implementation-artifacts/skillars-deferred-160-spring-test-context-consolidation.md

## Change Log

- 2026-10-24 — Implemented L1–L8 and the guardrails (/bmad-dev-story). The failsafe JVM's distinct
  context configurations go from 47 to a predicted 17. CI confirmation (T11.2/T11.3, AC1) is pending.
- 2026-10-24 — Code-review follow-up: 7 patches resolved (P6 partly a false positive, see Review
  Findings). P4 was applied to all 9 hiding fields, not the 2 named. Re-verified: 9 classes / 47 tests
  plus the convention test (5/5), one context; five gate scenarios.
- 2026-10-24 — CI measured (PR #297, run 40063190835 ×3): `missCount = 17`, `size = 16`, identical
  on all three. "Predicted" wording replaced with the measurement; status → done.
