# Story skillars-deferred-160: Spring test-context consolidation — get the failsafe JVM below the context cache cap

Status: ready-for-dev

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

- [ ] T1 Baseline (AC1, AC13): record from the latest master `pr-build` log the last cache statistics
      line (`grep -aoE 'size = [0-9]+, maxSize = [0-9]+.*missCount = [0-9]+' build.log | tail -1`) and
      per-class test counts from `failsafe-reports`/`surefire-reports` artifacts.
- [ ] T2 L1 rename slices to `*WebMvcTest` (AC2): `git mv`, class rename, fix the
      PlayerSubscriptionOwnership cross-ref and the `SesCutoverPreflightResourceTest` javadoc link;
      `mvn -o test -DskipFrontend -Dtest='*WebMvcTest'`.
- [ ] T3 L2 shared event captor (AC3).
- [ ] T4 L3 E2ESecurityConfig (AC4).
- [ ] T5 L4 `@AutoConfigureMockMvc` (AC5), in the same commit as the `isSlice` fix.
- [ ] T6 L5 dead `@TestPropertySource` (AC6) + pinned count 7 → 4.
- [ ] T7 L6 spy hoist with per-type trap-check (AC7).
- [ ] T8 L7 provider mock-set unification with per-class trap-check (AC8).
- [ ] T9 L8 MessageModerationSweeperIT (AC9).
- [ ] T10 Guardrail tests (AC10) — pin `EXPECTED_DISTINCT_CONTEXT_KEYS` to the post-change number;
      cross-check it against CI. The model covers live AIT keys only, so the expected
      `missCount` = model + 3 (K48–K50, the sliced standalone classes), and `size` = `missCount` − 1
      (RateLimitingAspectIT dirtied). SmtpTransportBootIT is an AIT class, so it is already in the
      model. Document the arithmetic in Completion Notes.
- [ ] T11 Gate (AC11) — after the first green CI run, set CEILING from the measured number; re-run
      twice more for AC1's determinism evidence.
- [ ] T12 Docs/comments (AC12).
- [ ] T13 Scoped local runs of every touched IT class, e.g.
      `mvn -o verify -DskipFrontend -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=<Class>`.
      These single-class failsafe runs follow the precedent of deferred-155/-156/-157 ("scoped
      failsafe"). **No full local `mvn verify`**: that is what the project rule forbids, and GitHub CI
      is the gate.

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

### Debug Log References

### Completion Notes List

### File List
