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

**Distinct keys in the failsafe JVM today: 48** (50 total; `AdminLoginResourceTest` and
`SluRetrierProxyRetryTest` already run in surefire). 48 > `maxSize = 32`, so LRU eviction is
guaranteed and `missCount` (contexts *built*, rebuilds included) depends on class order — which is
exactly the deferred-156 (48 vs 45) and deferred-159 (47 vs 44) flakes recorded in
`.github/scripts/assert-context-count.sh`. That script's own header already says the real fix is
"remove a context configuration … so the suite sits below 32 distinct keys with room to spare".
This story is that fix.

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
| K07 | SimultaneousExpiryIT, YearlyExemptionRenewalIT | `@TestPropertySource platform.video.lifecycle.outbox_max_attempts=3, batch_size=50` + mock PlayerSubscriptionQueryPort, VideoLifecycleService | L5 |
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
| K47 | MessageModerationSweeperIT | standalone **full** `@SpringBootTest` (no WireMock, own ShedLock helper) | L8 |
| K48 | RateLimitingAspectIT | sliced `@SpringBootTest(classes=…)` + `@DirtiesContext(AFTER_CLASS)` | keep |
| K49 | PropertiesFeatureToggleServiceIT | sliced `@SpringBootTest(classes=…)` | keep |
| K50 | SesEmailEndToEndIT | sliced `@SpringBootTest` + own WireMock `ses-service` | keep |
| (surefire) | AdminLoginResourceTest, SluRetrierProxyRetryTest | slice / `@SpringJUnitConfig` | already in surefire |

`EmailTransportBootIT` builds its own `SpringApplicationBuilder` contexts — they bypass the test
context cache and are not counted.

### Lever budget (estimated failsafe distinct keys: 48 → ~17)

| Lever | Change | Keys saved | Running total |
|---|---|---|---|
| L1 | rename the 14 `@WebMvcTest` `*IT` slices to `*Test` (surefire JVM) | −14 | 34 |
| L2 | one shared event captor in `TestConfig` replaces the 4 per-class capture configs | −4 | 30 |
| L3 | drop the redundant `@Import(E2ESecurityConfig)` from ConfigResourceIT / StorageResourceIT | −2 | 28 |
| L4 | hoist `@AutoConfigureMockMvc` onto `AbstractIntegrationTest` | −1 | 27 |
| L5 | delete the dead `platform.video.lifecycle.*` `@TestPropertySource`s (K07+K16 merge) | −1 | 26 |
| L6 | hoist the 7 `@MockitoSpyBean` types onto `AbstractIntegrationTest` | −6 | 20 |
| L7 | unify the VPA-only / FSS-only / VPA+FSS mock sets into one {VPA, FSS} set | −2 | 18 |
| L8 | move MessageModerationSweeperIT onto `AbstractIntegrationTest` | −1 | 17 |

These are estimates from static analysis; the CI `missCount` and cache `size` are the authority.
L1–L5 and L8 are near-mechanical; L6 and L7 need the per-class trap-checks below. **The hard AC is
≤ 24, not 17** — if a lever's trap-check fails, record why and drop that lever rather than force it.

## Acceptance Criteria

1. **AC1 — Below the cap with headroom.** In the `pr-build` CI log, the *last* Spring
   `DefaultContextCache` statistics line for the failsafe JVM shows `size ≤ 24` and
   `size < maxSize`, and `missCount == size` (no eviction rebuilds). Record the measured
   `size`/`missCount` from three consecutive CI runs of the final commit in Completion Notes; all
   three must be identical (determinism is the point).
2. **AC2 — Slice tests leave the failsafe JVM (L1).** The 14 `@WebMvcTest` classes listed under
   K31–K37/K40–K46 are renamed `*IT → *Test` (file + class name; update the cross-class reference
   `SubscriptionResourceIT` → `PlayerSubscriptionOwnershipIT.TestSecurityConfig`). They run and pass
   under surefire (`mvn test`). Test count per class is unchanged (compare `target/surefire-reports`
   after vs `target/failsafe-reports` before).
3. **AC3 — Shared event capture (L2).** A single test-scoped captor bean (e.g.
   `config/RecordedEvents`, declared in `TestConfig`) records every published event into a
   thread-safe list and is cleared before each test method (via `DatabaseResetTestExecutionListener`
   or a dedicated listener registered on `AbstractIntegrationTest`'s `@TestExecutionListeners`, which
   does **not** contribute to the cache key). `ReliabilityStrikeConcurrencyIT`,
   `AdminCoachEnforcementConcurrencyIT`, `ModerationFailClosedIT` and `UserProfileServiceIT` use it and
   lose their own `@Import`/nested `@TestConfiguration`. Their existing assertions are unchanged in
   meaning (they still filter by event type and coach/user id).
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
   RANDOM_PORT tests). `IntegrationTestConventionTest` is unaffected (it checks declared, not inherited,
   annotations).
6. **AC6 — Dead `@TestPropertySource` removed (L5).** `platform.video.lifecycle.outbox_max_attempts`
   and `platform.video.lifecycle.batch_size` are **DB-backed** (`ConfigService.getBoundedLong`/
   `getBoundedInt` read `main.platform_config`; no `@Value`/`@ConfigurationProperties`/`Environment`
   binding exists — `grep -rn "platform.video.lifecycle" src/main/java`), and all three tests already
   write the value into `platform_config` themselves. Delete the `@TestPropertySource` from
   `SimultaneousExpiryIT`, `YearlyExemptionRenewalIT`, `VideoSubscriptionLifecycleListenerIT`; drop
   `EXPECTED_TEST_PROPERTY_SOURCE_COUNT` 7 → 4 (its javadoc's list loses "video-lifecycle outbox
   attempts").
7. **AC7 — Spies hoisted to the base (L6), each trap-checked.** Move these `@MockitoSpyBean`
   declarations to `AbstractIntegrationTest` (protected fields, same names where possible; remove the
   local ones): `VideoDeletionOutboxRepository`, `DrillVideoRefRepository`,
   `VideoPhysicalDeletionListener`, `PaymentGateway`, `SessionPackPurchaseRepository`,
   `RefreshTokenRepository`, `CoachProfileRepository`. Rationale: a spy delegates to the real bean, so
   unlike a mock it cannot replace the system under test; `MockReset.AFTER` is the default for
   `@MockitoSpyBean` too (deferred-19 AC4 verified), so stubbing cannot leak between tests.
   Per-type trap-check, recorded in Completion Notes:
   - the spy class's `doReturn/doThrow` stubbing in its original test still takes effect (run it);
   - no other IT asserts on the bean's concrete class or uses `AopUtils`/`AopTestUtils` against it;
   - `PaymentGateway`: `TestConfig` already provides `StubPaymentGateway` as `@Primary` — the spy
     must wrap that bean, confirm with `CaptureReservationIT` + one payment-family IT;
   - `VideoPhysicalDeletionListener` is an event listener: confirm events still reach the spied bean
     in a K01 class that triggers physical deletion (not only in `VideoPurgedEventIT`).
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
   `BaseSessionIT` subclasses (single inheritance), so declare the missing field per class. Use the
   **same declaration form** (field-level `@MockitoBean`, no qualifier, default reset) in every class —
   a type-level `@MockitoBean(types=…)` in one class and a field in another are not proven to
   produce equal `BeanOverrideHandler`s (by-type handlers may also compare field annotations) — K02
   proves only that identical field declarations in different classes do share a key.
   Trap-check per class: an added `FileStorageService` mock must not sit under a code path the test
   asserts on (e.g. a presigned URL or stored object). Run each touched class.
9. **AC9 — MessageModerationSweeperIT joins the base (L8).** It extends `AbstractIntegrationTest`,
   drops its own `@SpringBootTest`/`@ActiveProfiles`/`@Import(TestConfig)`, uses the inherited
   `releaseSchedulerLock` instead of its copy, and is removed from the convention test's `ALLOWLIST`
   (whose comment wrongly calls it "sliced"). Check that its seeding still works under the base's
   per-method truncate (seed in `@BeforeEach`/test body, not `@BeforeAll`).
10. **AC10 — Guardrail closes the mock-set gap.** `IntegrationTestConventionTest` gains:
    a) a test that computes, by reflection over the compiled concrete `*IT` classes extending
       `AbstractIntegrationTest`, each class's context-key model — declared class-level context
       annotations along the hierarchy (`@Import`, `@TestPropertySource`, `@DynamicPropertySource`
       method presence, `@AutoConfigure*`, `@DirtiesContext`, nested `@TestConfiguration`) plus the
       *set of field types* annotated `@MockitoBean`/`@MockitoSpyBean` along the hierarchy — and pins
       the number of distinct models at `EXPECTED_DISTINCT_CONTEXT_KEYS`. On mismatch the failure
       message prints every key with its classes (sorted), so the offending class is obvious without
       any offline script;
    b) a test that fails if any `*IT` class carries a slice annotation (`isSlice`) — slices must be
       named `*Test` so they stay out of the failsafe JVM.
    The pinned count's javadoc follows the existing `EXPECTED_TEST_PROPERTY_SOURCE_COUNT` style
    ("If you are here because this assertion failed …").
11. **AC11 — CI gate tightened and taught about capacity.**
    `.github/scripts/assert-context-count.sh`: `CEILING` set to the measured `missCount` from AC1
    (both the script default and `pr-build.yml`'s explicit argument), with a header entry in the
    existing style (before/after numbers, run IDs). The script additionally parses
    `size = N, maxSize = M` from the same last statistics line and **fails if `N >= M`** with a
    message explaining eviction. The failure text's reference to the non-existent
    `python3 scratchpad/ctxkeys.py` is replaced by a pointer to the AC10a test.
12. **AC12 — Docs and stale comments.** Update `docs/testing/readme.md` (context numbers, the slice
    rule), `docs/testing/why-inheritance-over-import.md` (mock-set guard now exists; slices are
    `*Test`), the `pom.xml` failsafe comment ("~26 contexts … nothing is ever evicted" is false today),
    and `AbstractIntegrationTest`'s javadoc where it lists what the base carries.
13. **AC13 — No assertion weakened.** Same rule as deferred-19 line ~600: this story changes how tests
    are *configured*, never what they *assert*. Test counts per class, before and after, are recorded
    (surefire and failsafe separately). A failing test is fixed by seed data or a corrected
    assumption, or the lever is dropped — never by deleting/weakening an assertion.

## Tasks / Subtasks

- [ ] T1 Baseline (AC1, AC13): record from the latest master `pr-build` log the last cache statistics
      line (`grep -aoE 'size = [0-9]+, maxSize = [0-9]+.*missCount = [0-9]+' build.log | tail -1`) and
      per-class test counts from `failsafe-reports`/`surefire-reports` artifacts.
- [ ] T2 L1 rename slices (AC2) — `git mv`, class rename, fix the PlayerSubscriptionOwnership cross-ref;
      `mvn test -Dtest='*ResourceTest,VideoSseTest,PlayerSubscriptionOwnershipTest,…'`.
- [ ] T3 L2 shared event captor (AC3).
- [ ] T4 L3 E2ESecurityConfig (AC4).
- [ ] T5 L4 `@AutoConfigureMockMvc` (AC5).
- [ ] T6 L5 dead `@TestPropertySource` (AC6) + pinned count 7 → 4.
- [ ] T7 L6 spy hoist with per-type trap-check (AC7).
- [ ] T8 L7 provider mock-set unification with per-class trap-check (AC8).
- [ ] T9 L8 MessageModerationSweeperIT (AC9).
- [ ] T10 Guardrail tests (AC10) — pin `EXPECTED_DISTINCT_CONTEXT_KEYS` to the post-change number;
      cross-check it against the CI `size` (it covers AIT keys only; the 3 sliced standalone classes
      + SmtpTransportBootIT etc. account for the difference — document the arithmetic).
- [ ] T11 Gate (AC11) — after the first green CI run, set CEILING from the measured number; re-run
      twice more for AC1's determinism evidence.
- [ ] T12 Docs/comments (AC12).
- [ ] T13 Scoped local runs of every touched IT class (`mvn -Dit.test=<Class> -Dtest=none verify`
      style, scoped failsafe) — **no full local `mvn verify`** (project rule; GitHub CI is the gate).

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
- Near cache capacity, one fork can cost +2 (`assert-context-count.sh` header); once this story lands
  `size` should be far from 32 and `missCount == size`, so the gate becomes exact. If CI ever shows
  `missCount > size` after this story, something reintroduced `@DirtiesContext` or crossed capacity.
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
  AIT keys *without* touching mock sets per family; flagged slices inflating `missCount`.
- **deferred-83 / -123 / -124 / -128 / ses-1.2 / -159**: each bumped CEILING by +1/+2 for a new
  mock/spy/property fork — the unguarded-mock-set pattern this story closes (AC10a).
- **deferred-156 / -159**: CEILING flaked on order alone at `size = maxSize = 32` — the symptom AC1
  removes.

### References

- `.github/scripts/assert-context-count.sh` (header history, CEILING=47)
- `docs/testing/why-inheritance-over-import.md` (appendix: reproducing the numbers)
- `src/test/java/com/softropic/skillars/config/DatabaseResetTestExecutionListener.java:528-545`
  (`main.sec` not Flyway-seeded)
- `src/main/java/com/softropic/skillars/platform/video/service/VideoSubscriptionLifecycleListener.java:109,125`
  (DB-backed lifecycle config)
- Static analysis used for the inventory: comment-stripped, hierarchy-resolved grouping by
  (class-level context annotations, override field types). Re-derive with the AC10a test once it exists.

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
