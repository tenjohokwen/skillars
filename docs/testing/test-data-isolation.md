# Test data isolation

> **Status:** target design for `skillars-deferred-19`, except the **fixture-id registry** and the
> **collisions** section, which are a factual record of the tree at commit `21ef489`. See
> [readme.md](readme.md).

## The rule

> **Every test method starts with an empty database and an empty Redis. Seed everything you need.
> Clean up nothing.**

## Why this had to change

Once all integration tests share one Spring context, they share one database. That is the direct
consequence of [consolidating contexts](why-inheritance-over-import.md) and it is the thing most likely
to bite — so it is worth being precise about what actually changed.

**Cross-class database sharing was already the operating model.** TODAY, the largest Spring context
serves **30 test classes against one database**, and the second-largest serves 15. Consolidating widens
the blast radius from 30 classes to ~130. It does not introduce a new mechanism.

**What was fragile was the cleanup.** `src/test/resources/sql/secData.sql` inserts a fixed primary key
`659287191260154475` with no `ON CONFLICT`, and it is applied `BEFORE_TEST_METHOD` by 65+ classes via
`@Sql({SecurityIT.SEC_DATA_SQL_PATH})`. On the second test method in any such class that insert would
violate the primary key — so it only works because individual classes hand-write a compensating
`DELETE FROM main.sec` in `@AfterEach` (e.g. `AvailabilityResourceIT.java:120`), alongside
`DbCleaner.cleanDb()` and `TestDataCleaner.wipeAll()`.

Every one of those is a per-class, hand-maintained, silently-incomplete list. `DbCleaner` still carries
four commented-out `delete` statements left over from a different project. Nothing verifies that a
class's teardown covers the tables its test actually wrote.

The replacement is one deterministic reset that cannot drift — and as a side effect it makes
`secData.sql` idempotent-by-construction, which is what makes deleting all that hand-written teardown
safe rather than reckless.

## How it works — TARGET

`DatabaseResetTestExecutionListener` runs before every test method and:

1. Issues a **single** `TRUNCATE <all tables> RESTART IDENTITY CASCADE`, with the table list built from
   `information_schema.tables` across every application schema (`main`, `booking`, `marketplace`,
   `messaging`, `payment`, `video`, …). One statement, `CASCADE`, so it is order-independent and immune
   to the FK-ordering drift the hand-written cleaners suffer from. **Do not hard-code the schema list** —
   enumerate it.
2. Backdates the ShedLock rows (see below).
3. Flushes Redis: `RedisConnectionFactory.getConnection().serverCommands().flushDb()`.

### Ordering is the critical detail

`@Sql` scripts are executed by `SqlScriptsTestExecutionListener` (order **5000**) during
`beforeTestMethod`, which runs **before** JUnit `@BeforeEach` callbacks.

A `@BeforeEach` truncate would therefore wipe the `@Sql`-seeded data. The reset **must** be a
`TestExecutionListener` with an order **below 5000**, registered on `AbstractIntegrationTest` via
`@TestExecutionListeners(mergeMode = MERGE_WITH_DEFAULTS)`.

Verify the ordering empirically — run one `@Sql`-driven class and assert the seeded row is visible in the
test body. Do not assume.

`@TestExecutionListeners` does **not** contribute to `MergedContextConfiguration`, so this adds no
Spring contexts.

---

## Flyway-seeded reference data must be restored after every truncate

Excluding infrastructure tables is **not** sufficient, and this is the easiest way to break the whole suite.

**33 of the 91 migrations contain `INSERT INTO`.** Flyway will not replay them after a truncate — the
`flyway_schema_history` row is intact, so the next context's run is a no-op validation pass. The first
truncated test method destroys that data for the rest of the JVM.

| Table | Seeded by | Read by |
|---|---|---|
| `main.platform_config` | **30 migrations** — V20, V25, V57, V64, V85, V90 and others: age policy, moderation config, subscription tiers, phone-OTP toggle, payment-sweep config | `ConfigResourceIT`, `ConfigGuardIT`, `SubscriptionLifecycleIT`, `VideoSubscriptionLifecycleListenerIT`, `ConfigService`'s cache |
| `session.drills` | V39 — 20 `PLATFORM` foundation drills | `DrillLibraryResourceIT.java:96` selects a `PLATFORM` drill that exists *only* because of V39 |
| `main.authority` | V21, V84 | broadly |

This is safe **today** only because no existing cleaner touches these tables — every current delete is
narrowly targeted (`SimultaneousExpiryIT.java:74` deletes `platform_config WHERE id IN (8201, 8202)`;
`BookingBatchResourceIT.java:164` deletes `id = 50`). Neither `DbCleaner` nor `TestDataCleaner` mentions
`platform_config` or `drills` at all.

**The reset therefore restores reference rows after truncating** — captured once per JVM after the first
context's Flyway run, and re-inserted after each truncate.

**If a test fails because reference data is missing, the reset is wrong — not the test.** Re-seeding
migration reference data into individual test classes is never the right fix. See the triage rule under
[What this means when you write a test](#what-this-means-when-you-write-a-test).

## In-application caches are evicted too

`ConfigService.java:47` caches `main.platform_config` on a 300-second scheduled refresh, and
`AlertRuleCache.java:43` does the same for alert rules. Under a long-lived context, truncating the
database leaves both holding rows that no longer exist. The reset listener evicts them; do not rely on
the refresh schedule.

## Three tables must never be truncated

Getting these wrong produces **silent, suite-wide** failures, not loud ones. All three are exclusions,
not preferences.

### `flyway_schema_history`

Truncating it makes the next context's Flyway run re-apply all 91 migrations against an
already-populated schema.

### `main.shedlock` — the dangerous one

**Truncating `main.shedlock` silently disables every `@SchedulerLock` job for the rest of the JVM.**

`ShedLockConfig.lockProvider` (`infrastructure/config/ShedLockConfig.java:21-29`) is a
`JdbcTemplateLockProvider` against `main.shedlock`. That provider **caches the lock names it has already
inserted**, and thereafter issues only `UPDATE`. Delete the row and the `UPDATE` matches zero rows,
`lock()` returns `Optional.empty()`, and the run is skipped — logged at INFO as *"held by another
instance"*, which reads like normal cluster behaviour rather than a bug.

This is not hypothetical. `deferred-15`'s code review hit it and recorded it verbatim:

> *"the first fix (DELETE the shedlock row) is WORSE than nothing … 5 of 6 sweeps were skipped and the
> three negative cases passed vacuously."*

The provider instance is per-context, and one context now serves ~90 classes — so the poisoning would
span the whole suite, and the affected tests would **pass**, asserting nothing.

**Reset by backdating instead**, which is the approach `BasePaymentIT.releaseSchedulerLock`
(`BasePaymentIT.java:87-93`) already proved correct:

```sql
UPDATE main.shedlock SET lock_until = now() - interval '1 minute'
```

### The `qrtz_*` tables

Quartz runs with `job-store-type: jdbc` and `org.quartz.jobStore.isClustered: true`
(`application.yaml:18-31`), so a cluster check-in thread is writing to those tables concurrently with the
tests. Truncating under a live clustered scheduler is a data race with a background thread, not a clean
reset.

---

## What this means when you write a test

**Seed everything.** The database is empty when your `@BeforeEach` runs. If your test needs a
`ROLE_COACH` authority row, insert it — do not assume another class left one behind.

**Delete nothing.** No `@AfterEach` teardown, no `cleanup.sql`, no `DbCleaner`. The reset handles it.

**Expect the migration to surface real bugs — but triage them.** The first full run after the reset
listener lands will fail in two distinct ways, and they have opposite fixes:

| Symptom | Cause | Fix |
|---|---|---|
| Missing a **user, coach, booking** — anything a test creates | The class was depending on another test's leftovers | Add the seed to that class. Never weaken the reset, never skip the class. |
| Missing **`platform_config`, a `PLATFORM` drill, an authority row** | Reference data was destroyed | Fix the reset (reference-data restoration). **Do not** seed migration data into the class. |

Get the triage right before fixing anything — the second category masquerades as the first, and
"just add the seed" applied to reference data means re-seeding V20/V39 into ~130 classes.

---

## Fixture id registry

Test users are inserted with hard-coded `long` ids. Each class claims a range so its rows are
identifiable. **Claim an unused prefix and add it here before using it.**

The truncate removes the *cross-class collision hazard* — every class starts empty regardless — so this
registry exists for **readability and debuggability**, not correctness. It is still the difference between
"I know which test wrote this row" and a forensic grep.

### Shared fixture scripts

| Range | Owner |
|---|---|
| `586920556720583008`, `586920556720583111`, `675373350208068096`, `31620716521543010`, `12780121221323583` | `sql/userData.sql` — the standard user set (`me@`, `blockme@`, admin, not-activated, locked) |
| `6747751741842104908` (`ROLE_ADMIN`), `5418719445932238328` (`ROLE_USER`) | `sql/authorityData.sql` |
| `659287191260154475` | `sql/secData.sql` — the JWT signing secret row |
| `3318719445932238111`, `1238719445932238123`, `2228719445932238222`, `3338719445932238333` | `sql/initTestData.sql` — extra roles, local-dev seeding only |

### Per-class ranges — as found at `21ef489`

| Prefix | Class |
|---|---|
| `9000000001`–`9000000003` | `AuthResourceIT` |
| `9000000010`–`9000000021` | `FamilyDataIsolationIT` |
| `9100000001`–`9100000002` | `CoachProfileBuilderIT` |
| `9100000003` | `SanitizePreviewResourceIT` |
| `9200000001`–`9200000005` | `CoachMarketplaceResourceIT` |
| `9300000001`–`9300000002` | `AvailabilityResourceIT` **and** `CoachProfileResourceIT` ⚠ |
| `9360000001` | `NeglectedSkillDetectionServiceIT` |
| `9500000001`–`9500000099` | `BookingRequestResourceIT` |
| `9511000001`–`9511000021` | `BookingServiceConcurrencyIT` |
| `9540000001`–`9540000020` | `HomeworkResourceIT` |
| `9550000010`–`9550000050` | `DrillLibraryResourceIT` |
| `9560000010`–`9560000030` | `DrillUploadResourceIT` **and** `DrillTagResourceIT` ⚠ |
| `9570000001`–`9570000021` | `SkillExposureResourceIT` **and** `SessionBuilderResourceIT` ⚠ |
| `9580000001`–`9580000030` | `RadarAssessmentResourceIT` **and** `SessionTemplateResourceIT` ⚠ |
| `9590000001`–`9590000020` | `RadarDisplayResourceIT` |
| `9600000001`–`9600000099` | `PlayerTimelineResourceIT`, `ScheduleResourceIT`, `SessionCompletionResourceIT`, `BookingSseIT` ⚠ |
| `9610000001`–`9610000021` | `ParentDevelopmentPortalResourceIT` |
| `9611000001`–`9611000021` | `BatchAcceptPaymentIT` |
| `9700000001`–`9700000011` | `RescheduleResourceIT` **and** `ConversationResourceIT` ⚠ |
| `9800000001`–`9800000020` | `BookingBatchResourceIT` **and** `MessagingAccessControlIT` ⚠ |
| `9810000001`–`9810000200` | `ParentalOversightResourceIT` |
| `9820000001`–`9820000010` | `ModerationFailClosedIT` |
| `9830000001`–`9830000010` | `BlockedMessageContentHidingIT` |
| `9900000001` | `SluCalculationServiceIT` |
| `555000000000000001`–`…002` | `ShadowAccountServiceIT` |

The claimed four-digit prefixes at `21ef489` are: `9000`, `9070`, `9100`, `9200`, `9300`, `9360`, `9399`,
`9500`, `9511`, `9540`, `9550`, `9560`, `9570`, `9580`, `9590`, `9600`, `9610`, `9611`, `9700`, `9800`,
`9810`, `9820`, `9830`, `9900`.

**Free blocks** for new classes: `9310`–`9350`, `9370`–`9390`, `9400`–`9490`, `9520`–`9530`,
`9620`–`9690`, `9710`–`9790`, `9840`–`9890`, `9910`–`9990`.

*(This registry covers ten-digit ids beginning with `9`, which is the convention for per-class fixtures.
The `5550…`, `5869…`, `6747…` and `6592…` families are the shared fixture scripts above and are not
per-class ranges.)*

### ⚠ The collisions are real

Nine groups of test classes use **the same exact id literals** — 18 colliding literals in total. The
worst case is `9600000001` / `9600000002` / `9600000010` / `9600000099`, claimed by **four** classes:
`BookingSseIT`, `PlayerTimelineResourceIT`, `ScheduleResourceIT` and `SessionCompletionResourceIT`.

All four already share the same Spring context, and therefore already share one database. It works today
only because classes run sequentially and each happens to delete its own rows in `@AfterEach`. Reorder
the suite, add a class that forgets its teardown, or leave one row behind, and you get a
`duplicate key`/FK failure in a class that did nothing wrong.

The reset listener removes the hazard. The collisions are recorded here anyway, because a shared id space
still makes failures harder to read — if you touch one of the ⚠ classes, moving it to a free prefix is a
cheap improvement.

---

## Files this replaces

| File | Fate |
|---|---|
| `src/test/java/com/softropic/skillars/utils/DbCleaner.java` | delete (note its four commented-out `delete` lines — dead code from another project) |
| `src/test/java/com/softropic/skillars/config/TestDataCleaner.java` | delete |
| `src/test/resources/sql/cleanup.sql` + its `AFTER_TEST_METHOD` usages | delete |
| Per-class `@AfterEach` row deletions (~40 classes) | delete |

Do the deletions in a **separate commit** from the listener, so that if a class turns out to depend on
cleanup ordering, `git bisect` isolates it immediately.
