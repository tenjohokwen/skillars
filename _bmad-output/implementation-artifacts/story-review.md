# Story Review — skillars-deferred-160-spring-test-context-consolidation

- **Audit date:** 2026-10-24
- **Real HEAD (checked `git rev-parse` / `git log -1` before opening the story):** `8942eee6 docs(story): create skillars-deferred-160 spring test-context consolidation story`
- All citations were checked against this HEAD, not against the `d60635b1` the story's Background names.
- Method: four parallel read-only layers, then a manual adversarial re-check (Step 4b). Items marked **[re-checked]** were re-read by me at HEAD; items marked **[agent-only]** rest on one layer's evidence that I did not independently re-open.

## 1. Citation verification

| Citation | Verdict | Evidence |
|---|---|---|
| `DatabaseResetTestExecutionListener.java:528-545` | MATCH | `flywaySeededTables()` javadoc: `main.sec` "not by any migration" |
| `VideoSubscriptionLifecycleListener.java:109,125` | MATCH | `getBoundedLong(...outbox_max_attempts, 1L, 100L)`, `getBoundedInt(...batch_size, 100, 1, 10000)` |
| `ComponentConfig.java:34` | MATCH | `@ConditionalOnProperty(name="enable.test.mail", havingValue="false", matchIfMissing=true)` |
| `application-test.yaml:93` | MATCH (nested) | `enable:`/`test:`/`mail: true` at 84/92/93, not a literal `enable.test.mail: true`; the yaml's own comment says "ComponentConfig:27" (stale) |
| deferred-19 "~884" | MATCH (actually 885) | "21 + slices lands at or above `maxSize = 32`…" (later reconciled to 20) |
| deferred-19 "~600" | MATCH | "configured, never what they assert" |
| deferred-19 "~263–266", "~1178" | MATCH | 263–266 do-not-hoist rules; 1177–1179 VPA hoist broke `VideoWebhookResourceIT`, 5 failures, reverted |
| `pom.xml` ~688 | MATCH | 689–690: "~26 contexts … nothing is ever evicted" (stale, as story says) |
| `pr-build.yml` ~75–85 / script header | MATCH | line 83 `assert-context-count.sh build.log 47`; script `CEILING="${2:-47}"` |
| `python3 scratchpad/ctxkeys.py` "non-existent" | MATCH (absent) | `find` returns nothing |
| `EXPECTED_TEST_PROPERTY_SOURCE_COUNT == 7`, ALLOWLIST "sliced" comment, `isSlice`, base `GeminiClient`, `releaseSchedulerLock`, `@TestExecutionListeners` comment | MATCH | lines 79 / 54 / 92 / 101 / 141 / 75–77 |
| 14 `@WebMvcTest` classes named `*IT` | MATCH | all 14 confirmed after comment-stripping |
| `SubscriptionResourceIT` → `PlayerSubscriptionOwnershipIT.TestSecurityConfig` | MATCH (direction) | `SubscriptionResourceIT:38` imports it |
| "141 concrete classes" on base | MATCH — see §6 | 141 = K01 only, not all AIT subclasses |
| Stale figures the story did not mention | note | convention-test javadoc "129 of 144 `*IT`", `docs/deployment/baseline/github-build.md:78` (`build.log 45`), `docs/testing/readme.md:102,175` ("34") |

## 2. Ledger & precedent attribution

Sources checked per item: story file / ledger, source comments (script header, javadoc), `git log`.

| Claim | Verdict |
|---|---|
| deferred-19 flagged slices push past `maxSize = 32` | CONFIRMED (story line 885; script header) |
| deferred-19 VPA root hoist broke `VideoWebhookResourceIT` ×5, reverted | CONFIRMED (story 1177–1179) |
| deferred-19 "20 is the floor" | CONFIRMED, but the floor is for `@SpringBootTest` configurations, not "AIT keys" |
| deferred-83/-123/-124/-128/ses-1.2 each bumped CEILING for a fork | CONFIRMED (header entries + commits) |
| **deferred-159 listed among the fork-bumpers** | **WRONG attribution.** Header says "ZERO context-affecting test changes"; its +2 (45→47) was order-vs-eviction. [agent-only; header read by agent] |
| deferred-159 "47 vs 44" flake | CONFIRMED |
| **deferred-156 "48 vs 45"** | **UNVERIFIABLE / likely wrong.** No 156 entry in the script header; the 156 commit didn't touch the script or workflow; 156 story has no missCount data; "45" is the deferred-128 ceiling. [re-checked: header grep shows 45 only under deferred-128] |
| **"deferred-19 AC4 verified `MockReset.AFTER` for `@MockitoSpyBean`"** | **WRONG.** Story line 270 only says "confirm the same default applies"; its task table (line ~1049) lists the spy audit as "Not done". Bytecode verification was for `@MockitoBean` only. [re-checked] |
| ses-1.2 `SmtpTransportBootIT` deliberate fork | CONFIRMED |
| No overlapping open ledger item | CONFIRMED |

## 3. Mechanistic claims

| Claim | Verdict / evidence |
|---|---|
| AC4: `E2ESecurityConfig` seeds only on `ContextRefreshedEvent`; reset listener (ORDER 3000) truncates `main.sec` before `@Sql` (5000); both classes carry class-level `@Sql(SEC_DATA_SQL_PATH)`, no `@BeforeAll`; only these 2 classes import it | CONFIRMED [agent-only, thorough trace] |
| AC3: all four captors use plain `@EventListener`; two are real multi-thread tests; `ApplicationEventsHolder` is ThreadLocal | CONFIRMED. Extra: `AccountChangeEventCaptor` also clears mid-test (line ~188), so the shared bean needs a public `clear()`. |
| AC3: `@TestExecutionListeners(MERGE_WITH_DEFAULTS)` doesn't change the key | CONFIRMED |
| AC5: "`IntegrationTestConventionTest` is unaffected" | **WRONG.** See F1. |
| AC6: lifecycle config is DB-backed, no `@Value`/Environment binding | CONFIRMED |
| AC6: "all three tests already write the value into `platform_config`" | **PARTIAL/WRONG.** See F2. |
| AC6: pinned count 7 → 4 | CONFIRMED (counter uses declared annotation; disabled classes still counted by reflection) |
| AC7: seven spies exist as stated, unqualified, unreset; `StubPaymentGateway` is `@Primary` in `TestConfig`; no conflicting `@MockitoBean` in any AIT class | CONFIRMED |
| AC7: spy `MockReset.AFTER` default | UNVERIFIED by the cited precedent (see §2) |
| AC8: "by-type handlers *may also* compare field annotations" | UNDERSTATED. `BeanOverrideHandler.equals` (spring-test 6.2.19) compares beanType, beanName, contextName, strategy and, for by-type, the **field name** and qualifier set. [agent-only] |
| AC9: seeding is in `@BeforeEach`, survives truncate; private `releaseSchedulerLock` is identical to base's | CONFIRMED, but see F3 |
| AC10a: "set of field types" is the key model | INSUFFICIENT. Real key also includes field name, qualifiers, `name=` (used by `VideoPlayResourceIT`/`VideoDeleteResourceIT`), mock-vs-spy, `reset`, type-level `@MockitoBean(types=…)`, `@SpringBootTest` attrs, merged profiles, WireMock extension. |

## 4. (merged into §3)

## 5. Corner cases / false assumptions / missed flows (survived Step 4b)

- **F1 (HIGH) — AC5 breaks `IntegrationTestConventionTest`.** `isSlice` (`IntegrationTestConventionTest.java:92-101`) uses `c.getAnnotations()` (includes inherited) and treats any annotation in `org.springframework.boot.test.autoconfigure.*` as a slice. `@AutoConfigureMockMvc` is `@Inherited` [re-checked with `javap` on spring-boot-test-autoconfigure 3.5.3 and 3.3.6]. After L4 every AIT subclass becomes a "slice", `integrationTestClasses()` (line 122) filters them all out and the `hasSizeGreaterThan(100)` guard at line 132 fails. The story states the opposite. Fix: make `isSlice` check declared annotations / explicit `@WebMvcTest`-family only. Today this same bug already silently excludes `VideoWebhookResourceIT` from the convention tests, and AC10b would trip on it until L4 lands.
- **F2 (HIGH) — K07 is not a live context, so L5 saves 0.** `SimultaneousExpiryIT:34` and `YearlyExemptionRenewalIT:30` are class-level `@Disabled` [re-checked]. Real baseline is therefore ~47, not 48; K16 survives as the only `{VideoLifecycleService, PlayerSubscriptionQueryPort}` key, so "K07+K16 merge −1" is illusory. The AC10a reflection model will count disabled classes unless it filters `@Disabled`. Also, those two classes' `@BeforeEach` inserts use column `config_key`; the real column is `key` (V138), so they could not work if enabled — AC6's "all three already write the value" is false for them. [agent-only on the V138 column]
- **F3 (HIGH) — L8 saves 0 unless `MessageRepository` is also hoisted.** `MessageModerationSweeperIT:48-49` has a fully-qualified `@MockitoSpyBean MessageRepository` [re-checked]; it is absent from the K47 row and AC9. Moving the class onto the base leaves it on its own key `{GeminiClient, spy MessageRepository}`. AC7 lists seven spies, there are eight.
- **F4 (HIGH) — AC2 rename collisions.** `VideoApprovalResourceTest.java` and `SesCutoverPreflightResourceTest.java` already exist [re-checked: both files found]. Renaming `VideoApprovalResourceIT` / `SesCutoverPreflightResourceIT` per AC2 collides. `SesCutoverPreflightResourceTest` also has a javadoc `{@link SesCutoverPreflightResourceIT}`.
- **F5 (MEDIUM-HIGH) — AC1 `missCount == size` is structurally unreachable while `RateLimitingAspectIT` keeps `@DirtiesContext(AFTER_CLASS)`** [re-checked: line 33]. A dirtied context leaves the cache but `missCount` is not decremented, so `size = missCount − 1` (unless that class happens to load last). The Dev Notes line "if `missCount > size` something reintroduced `@DirtiesContext`" is false at baseline. Mechanism reasoned from Spring's `DefaultContextCache` semantics by the agent; not confirmed against a real CI log. Fix: remove that `@DirtiesContext` (key is already unique), or gate on `missCount − size ≤ 1`/`missCount` alone.
- **F6 (MEDIUM) — Budget is thinner than stated.** Recomputed against live contexts: 47 → L1 33 → L2 29 → L3 27 → L4 26 → L5 26 → L6 20 → L7 18 → L8 18 (17 only with the MessageRepository spy hoisted). L1–L5 alone cannot reach ≤24; at least one of L6/L7 is required, and if L3 fails the margin is gone. Model-vs-CI arithmetic (T10): model 14 + K48/K49/K50 = 17, minus 1 for the dirtied `RateLimitingAspectIT` context.
- **F7 (MEDIUM) — AC11/AC12 miss CEILING locations.** Stale mentions also in `docs/deployment/baseline/github-build.md:78` (45), `docs/testing/readme.md:102,175` (34), `docs/testing/readme.md:86-90` ("known gaps" already fixed). The `application-test.yaml` comment "ComponentConfig:27" is stale.
- **F8 (MEDIUM) — T13 vs project rule.** Memory rule: never run `mvn verify` locally. T13 prescribes scoped `mvn … verify`; that is a local `verify` and needs the user's explicit blessing. Also needs `-DskipFrontend`/`-Dsurefire.failIfNoSpecifiedTests=false`-style flags or it runs the frontend build / fails on "no tests". [agent-only on flags]
- **F9 (LOW) — Side effects.** A red surefire (now holding the 14 slices) stops failsafe from running, so `build.log` has only surefire statistics lines; harmless but the gate would read a red build. `@MockitoSpyBean` on `VideoPhysicalDeletionListener` (`@Async` + `AFTER_COMMIT`) globally raises odds of `MockReset.AFTER` racing a previous test's async call — speculative, no evidence of a failure. Spy on `CoachProfileRepository` adds invocation recording to ~43 test files (perf only).

## 6. What did not survive re-verification

- **"141 vs 201 concrete ITs" (Layer 1 flagged DRIFTED): KILLED.** The story says 141 classes share the *one base key K01*, not that 141 is the total. Layer 4's independent grouping reproduced exactly 141 on K01; 201 total AIT subclasses (K01 + K02..K29 deltas) is consistent. My own count (205 / 148 with comments, no stripping) is not comparable and doesn't contradict.
- **deferred-19 "263–266" DRIFTED (minor): downgraded to MATCH.** Layer 1 gave the same range as corrected.
- **"Different mock sets for K21/K05/K14/K15/K17" mistakes: none found.** Layer 4 confirmed every K02–K50 class list matches the story's table.
- **Parallel-execution risk for the shared captor:** no parallelism configured (no `junit-platform.properties`, no `forkCount`/`parallel`); downgraded to a non-finding.
- **Slice runtime/Postgres dependency under surefire, JaCoCo/Sonar impact:** none found (no JaCoCo/Sonar in `pom.xml` or `.github`; `AdminLoginResourceTest` is a precedent `@WebMvcTest` under surefire).
- **K48/K49/K50 flagged by AC10b:** not flagged (`@SpringBootTest` is outside the `autoconfigure` package).

## 7. Recommendation (per-claim confidence)

Do not start dev as written. Fix before implementation:

1. **F1** (high confidence, re-checked): make `isSlice` declared-only; do it in the same task as L4/AC10b.
2. **F2/F3** (high, re-checked): L5 and L8 save 0 as written; either hoist the `MessageRepository` spy and drop the disabled-class counting, or revise the lever table to ~18 and the ≤24 AC margin. Decide whether to delete/fix the two dead `@Disabled` ITs.
3. **F4** (high, re-checked): choose non-colliding names for two renames.
4. **F5** (medium-high, mechanism reasoned): resolve the `@DirtiesContext`/`missCount` vs `size` contradiction in AC1/AC11.
5. Correct the precedent text: drop deferred-159 from fork-bumpers, drop/replace deferred-156 "48 vs 45", and stop crediting deferred-19 with verifying the spy reset default (AC7's trap-check must verify it).
6. Strengthen AC10a's key model (field names, qualifiers, `name=`, strategy, `@Disabled`) and extend AC11/AC12 file lists (F7); confirm T13 with the user (F8).

Independently re-checked by me: F1, F2 (disabled), F3, F4, F5 (annotation present only), the 141 refutation, the spy-reset attribution, the deferred-156 header absence. Taken from a single layer without my re-reading: the E2ESecurityConfig/@Sql ordering trace, `BeanOverrideHandler.equals` contents, V138 column name, surefire/CI side-effects, and all F5 mechanism claims about `DefaultContextCache` logging. No CI log was available to confirm the real `size`/`missCount` line format.
