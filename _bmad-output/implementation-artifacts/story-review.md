# Senior-dev audit — `skillars-deferred-89`

**Story reviewed:** `_bmad-output/implementation-artifacts/skillars-deferred-89-slu-detail-uniqueness-snapshot-write-gating-drill-video-deletion-existence-guard-scheduler-lock-transaction-ordering-perf-test-deflake-resend-otp-parity-and-provisioning-ops-doc-hardening.md`
**Reviewed at:** commit `dab5b88` (working tree)
**Reviewer:** senior-dev audit pass (adversarial read of every AC against `src/main`, `src/test`, `deploy/`, `docs/`, `.github/workflows/`)
**Verdict:** **Do not start as written.** Two of the ten ACs (AC1's migration, AC4 in full) specify work that is **already in the repository**, and their problem statements are factually false against source. Four more (AC3, AC6, AC7, AC8) rest on premises that do not survive a read of the code they cite. AC2 is sound and is the strongest item in the bundle.

Every finding below is backed by a direct file/line citation gathered during this pass. Claims I could not confirm are not included.

---

## Severity summary

| # | AC | Severity | Finding |
|---|----|----------|---------|
| B1 | AC1 | **Blocker** | The `V122` index already exists as `V47`. AC1's problem statement is false. |
| B2 | AC4 | **Blocker** | Already implemented and documented in `AsyncConfig.java:34`. AC4 is a no-op refactor with real regression risk. |
| B3 | AC1↔AC2 | **Blocker** | A blanket `DataIntegrityViolationException` catch re-creates the exact divergence AC2 exists to prevent. |
| B4 | AC7 | **Blocker** | Parent returns **200**, not 204; OTP is delivered by **email**, not SMS. Following AC7 breaks the parity it seeks. |
| H1 | AC6 | High | `computeStatus` returns **four** values incl. `PAUSED`, and is never persisted. AC6's model of "the de-facto enum" is wrong. |
| H2 | AC8 | High | ufw scoping is non-idempotent across re-runs and can drop the live provisioning SSH session. |
| H3 | AC8 | High | Firewall is **Step 4**, not Step 6. AC8's doc instruction points operators at "Deploy the Stack". |
| H4 | AC9(c) | High | The preferred `-maxdepth 1` regresses the `deferred-87` AC3 recovery coverage it is told to preserve. |
| M1 | AC3 | Medium | Both motivating scenarios are unreachable — the deletion path soft-deletes; no hard-delete of `videos` exists. |
| M2 | AC3 | Medium | The `initiateUpload` snippet does not compile — `lockedVideo` is out of scope at the publish site. |
| M3 | AC2 | Medium | Closes only one direction; the story's headline "can never permanently disagree" is not achieved. |
| M4 | AC2 | Medium | The `rows.isEmpty()` return value is unspecified under the new `boolean` contract. |
| M5 | AC6 | Medium | The catalog-parse spec does not match Postgres's normalized `indexdef` / `constraintdef` output → vacuous test. |
| M6 | AC6 | Medium | The sweep is already exhausted; the guard is far narrower than the AC advertises. |
| M7 | AC5 | Medium | Removes the only automated latency signal with no replacement and no follow-up ledger entry. |
| M8 | AC9(b) | Medium | The `ci.yml` comment already exists at `:103-105` and `:217-218`. Only the `rollback.md` half is new. |
| M9 | AC9(a) | Medium | `findmnt`/`lsblk` cannot see shadowed content; and the scenario leaves a *duplicate*, not lost data. |
| L1–L8 | various | Low | Mutation-check invalidity, transaction-boundary assumption, partial-overlap swallow, path nits, unauthenticated OTP-email surface. |

---

## Blockers

### B1 — AC1's `V122` migration already exists as `V47`; the AC's problem statement is false

`src/main/resources/db/migration/V47__player_skill_stats_unique_constraint.sql`:

```sql
-- Story 5.1 review: prevent duplicate SLU rows if BookingCompletedEvent fires more than once for the same session.
-- Partial (WHERE session_id IS NOT NULL) because PostgreSQL treats NULLs as distinct in unique constraints, …
CREATE UNIQUE INDEX uq_player_skill_stats_session_skill
    ON development.player_skill_stats (session_id, skill_code)
    WHERE session_id IS NOT NULL;
```

That is, column-for-column and predicate-for-predicate, the index AC1 specifies as `V122__player_skill_stats_session_skill_unique.sql`. It has been in the schema since Story 5.1's review and is never dropped (`grep -rn "uq_player_skill_stats_session_skill\|DROP INDEX.*player_skill" src/main/resources/db/migration/*.sql` returns only V47's own `CREATE`).

Consequences:

1. **AC1's premise — "`development.player_skill_stats` has no unique constraint on `(session_id, skill_code)`" — is false.** The double-write it describes ("two full sets of detail rows … `SUM(player_skill_stats.slu_value)` and the snapshot total diverge permanently") **cannot happen**. The second delivery's `saveAll` is rejected by V47.
2. **Creating `V122` would add a second, redundant unique index on the same columns.** Postgres permits it; it is pure write amplification and a schema-hygiene defect.
3. The inherited ledger bullet (`deferred-work.md:1302`) is itself wrong. It says "Closing the concurrency gap would need a DB uniqueness key on the detail side (mirroring the snapshot marker) — a follow-up story on its own merits." That key already existed when the deferred-86 review wrote that line, and all three review layers missed it. AC10 must tag it **`[STALE — the index already exists as V47]`**, not `[CLOSED by … V122]`.

**What is still genuinely open, and worth keeping:** the *behaviour* when V47 fires. Today the losing delivery's `saveAll` throws `DataIntegrityViolationException`, which propagates into `@Retryable(retryFor = {DataAccessException, …})` (`SluPersistenceRetrier.java:59-67`) — three attempts, each failing identically, each with a `@Backoff` sleep on the bounded `sluRetryExecutor` — then lands in `recoverSluSaveFailure(DataAccessException, …)` and logs a **false** `"… rows lost for session … manual recovery needed"` ERROR for rows that are, in fact, persisted. And then (today) `writeAllWithRetry` still runs. So:

- **Keep** the collision-catch half of AC1 (narrowed — see B3), reframed as "make the existing V47 collision a clean idempotent no-op instead of 3 wasted retries and a false alarm".
- **Delete** the `V122` migration task entirely.
- **Rewrite** AC1's mutation check — see L1.

### B2 — AC4 is already implemented, explicitly and with a comment stating the exact guarantee it asks for

`src/main/java/com/softropic/skillars/platform/notification/config/AsyncConfig.java:27-34`:

```java
// order: ShedLock's default InterceptMode.PROXY_METHOD wraps @SchedulerLock methods with a genuine
// AOP advisor on the same proxy chain as @Transactional (see DataSourceConfig's @EnableTransactionManagement,
// also un-ordered). Both default to Ordered.LOWEST_PRECEDENCE, so without an explicit order their relative
// nesting is unspecified. Setting a lower (higher-precedence) order here forces the lock advisor outermost,
// so proceed() always runs the transaction to completion (commit/rollback) before the lock is released —
// the lock can never be freed while the DB transaction is still open.
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M", order = Ordered.LOWEST_PRECEDENCE - 100)
```

`DataSourceConfig.java:20` carries a bare `@EnableTransactionManagement` (order defaults to `Ordered.LOWEST_PRECEDENCE`). `LOWEST_PRECEDENCE - 100` sorts ahead of `LOWEST_PRECEDENCE` → **the ShedLock advisor is already outermost**, which is precisely AC4's stated goal ("Make ShedLock's advice outermost so the lock spans the whole transaction including commit").

Shipped by `7e697d4` (2026-07-02) — *"ensures shedlock is not released before transaction completes, log metric added for schedulerlock"* — the same day as the `skillars-deferred-4` review that raised D3. The ledger bullet (`deferred-work.md:936`) itself only claims a **"plausible but unconfirmed risk"**; story creation upgraded it to a confirmed defect without verifying.

Additional facts AC4's risk analysis omits: all three methods carry `lockAtLeastFor` (`BookingExpiryScheduler:41-42` `PT2M`, `BookingReminderScheduler:43-44` `PT2M`, `BandwidthResetService:19-20` `PT1M`). Even in the hypothetical inverted-order world, ShedLock would hold the lock for at least 1–2 minutes past acquisition, while the commit completes in milliseconds — the harm window the AC describes is bounded to nil by configuration that already exists.

**Risk of doing AC4 as written.** The "preferred" delegate-bean split would move the entire transactional body of `BookingExpiryScheduler.expireStaleRequests` and `BookingReminderScheduler.processReminderWindows` onto a new bean. Both bodies mutate managed entities and rely on dirty-checking inside the scheduler transaction (e.g. `BookingReminderScheduler:58` `b.setPrimaryReminderSentAt(now)` with no explicit save), and both have existing direct-invocation tests (`BookingExpirySchedulerTest`, `BookingReminderSchedulerTest`). This is a real-regression-risk refactor delivering zero behaviour change. For `BandwidthResetService` it is worse than pointless — its body is a single `jdbcTemplate.update(...)`, atomic without any transaction advisor at all (L6).

**Recommended replacement for AC4:** keep only the `SchedulerLockTransactionOrderingTest` the AC already specifies, as a **regression guard** on the existing `AsyncConfig` order (assert the ShedLock interceptor precedes `TransactionInterceptor` in each of the three advised chains). Change no production code. Tag `deferred-4` D3 `[STALE — closed by 7e697d4 (2026-07-02); regression guard added by deferred-89]`.

### B3 — a blanket `DataIntegrityViolationException` catch (AC1) re-creates the phantom-snapshot bug AC2 removes

AC1 instructs: *"catch `DataIntegrityViolationException` (or `DuplicateKeyException`) from `saveAll`, `log.info` that the session's SLU detail rows were already persisted by a concurrent delivery, and **return normally**"* — which under AC2 means returning `true`, which gates `writeAllWithRetry` **on**.

`player_skill_stats` (`V46__development_module_init.sql:17-25`) has more than one integrity constraint:

```sql
id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
player_id       BIGINT        NOT NULL,
coach_id        UUID          NOT NULL,
skill_code      VARCHAR(10)   NOT NULL REFERENCES development.skill_definitions(code),
slu_value       NUMERIC(10,4) NOT NULL,
```

An FK violation on `skill_code` (a real, live risk — `SluCalculationService.java:120-122` pre-filters against `skill_definitions.active`, and the code's own comment says the filter exists "to prevent FK violation", i.e. this is the guarded-against case), a PK collision, or a NOT NULL violation all surface as `DataIntegrityViolationException`. Under the AC as written, **any** of them would be swallowed as "already persisted by a concurrent delivery", `saveSluWithRetry` would return `true`, and `writeAllWithRetry` would apply weekly-snapshot deltas for a session with **zero** `player_skill_stats` rows — the exact divergence AC2 is written to eliminate. AC1 and AC2 actively conflict.

**Required change:** narrow the catch to the specific constraint. The codebase already has the pattern — `ApiAdvice.java:163-169`:

```java
final String constraintName = cve.getConstraintName();
final String messageKey = CONSTRAINT_MAPPINGS.getOrDefault(constraintName, "generic.dataError");
…
HttpStatus status = CONFLICT_CONSTRAINTS.contains(constraintName) …
```

Unwrap to `org.hibernate.exception.ConstraintViolationException`, compare `getConstraintName()` against `uq_player_skill_stats_session_skill`, and **re-throw anything else** so it retries and recovers as it does today.

**Also correct the AC's stated rationale.** AC1 says the exception *"is not in `retryFor` today; adding it would retry a guaranteed-to-fail insert"*. That is wrong: `DataIntegrityViolationException extends NonTransientDataAccessException extends DataAccessException`, and `retryFor` already contains `DataAccessException.class` (`SluPersistenceRetrier.java:60`). It **is** retried today. The "Do not widen `retryFor`" instruction is therefore a no-op, and the false premise misleads AC2's `@Recover` test design ("drive the `@Recover` with a mocked repo throwing a `retryFor` exception"). If a hard non-retry is wanted for the collision, `noRetryFor` — not `retryFor` — is the lever; but the in-method catch is the better fix and needs no annotation change.

### B4 — AC7's parent-parity facts are wrong; following the AC breaks the parity it is creating

Both errors are in the code AC7 tells dev to mirror "exactly".

**(a) Parent returns HTTP 200, not 204.** `ParentRegistrationResource.java:59-64`:

```java
@PreAuthorize("permitAll()")
@PostMapping("/resend-otp")
public ResponseEntity<Void> resendOtp(@RequestBody @Valid ResendOtpRequest request) {
    parentRegistrationService.resendPhoneOtp(request.userId());
    return ResponseEntity.ok().build();          // ← 200
}
```

AC7's snippet uses `ResponseEntity.noContent().build()` and its test spec asserts *"EMAIL_VERIFIED user → 204"*. AC7's own hedge ("parent returns `noContent()` / 204 — confirm and mirror") states the wrong value as the thing to confirm. Shipping the AC verbatim gives coach/player a different status code from parent — the opposite of the AC's goal — and produces ITs that fail on the first run. **Use `ResponseEntity.ok().build()` and assert 200.**

**(b) The OTP is delivered by email, not SMS.** `ParentRegistrationService.java:251` calls `sendOtpEmail(user, otp)` → `:268-271` publishes `ParentOtpEmailEvent`. Coach and player are identical: `CoachRegistrationService.java:236-238` → `CoachOtpEmailEvent`; `PlayerRegistrationService.java:262-264` → `PlayerOtpEmailEvent`. No SMS path exists in any of the three.

The story says "SMS" in the Story statement (item 7: *"a coach or player whose single OTP SMS never arrived"*), in the AC7 fix bullet (*"SMS send"*), and in the i18n bullet (*"if the coach/player SMS body template key differs from parent's, add the coach/player keys to `messages_en.properties` / `messages_de.properties`"*). The i18n subtask targets something that does not exist — coach and player already own `sendOtpEmail`, `generateOtp`, and `hashOtp` privately, so the copied `resendPhoneOtp` needs **zero** i18n work. Delete Task 7's i18n subtask and fix the rationale prose.

**What AC7 gets right** (verified, no change needed): `OtpVerificationException` → **400** (`ApiAdvice.java:506-507` `@ResponseStatus(HttpStatus.BAD_REQUEST)`), so the "locked user → 400 `security.accountLocked`" expectation is correct; `ResendOtpRequest` exists and is reusable; parent's anti-enumeration behaviour is a throw (`OtpVerificationException("security.otpMismatch")` at `:229`), unambiguous to mirror; coach and player already inject `PhoneOtpTokenRepository` (`:61` / `:71`), so the copy is mechanical.

---

## High

### H1 — AC6's premise is wrong: `computeStatus` returns four values, and is never persisted

AC6 asserts: *"`SessionPackPaymentService:270-278` returns those same three literals (there is no Java enum — the CHECK constraint is the de-facto enum)."*

`SessionPackPaymentService.java:267-279`:

```java
private String computeStatus(SessionPackPurchase purchase) {
    Instant now = Instant.now();
    if (purchase.getRemainingSessions() == 0)                                     return "EXHAUSTED";
    if (purchase.getPausedUntil() != null && purchase.getPausedUntil().isAfter(now)) return "PAUSED";   // ← 4th
    if (purchase.getExpiresAt().isBefore(now))                                    return "EXPIRED";
    return "ACTIVE";
}
```

Two errors:

1. It returns **four** values. `PAUSED` is not in `chk_spp_status` (`V30__booking_session_packs.sql:10` — `CHECK (status IN ('ACTIVE', 'EXHAUSTED', 'EXPIRED'))`).
2. It is **never persisted**. Its only call site is `:263`, building a response DTO. `session_packs_purchased.status` is written elsewhere (`V37__session_pack_expiry_pause.sql:11` is the only migration that UPDATEs it).

So the service's status and the column's status are **different concepts** — a derived display status computed from `remainingSessions`/`pausedUntil`/`expiresAt`, versus a persisted column. The chain of reasoning AC6 rests on ("the service returns the same literals, therefore the CHECK constraint is the de-facto enum, therefore freeze it") does not hold. A pack with `pausedUntil` in the future displays as `PAUSED` while remaining `status = 'ACTIVE'` in the DB and therefore **inside** the `WHERE status = 'ACTIVE'` partial index — which is arguably the live modelling question worth examining, and AC6 does not touch it.

**Action:** rewrite AC6's Problem section against what the code actually does, and decide explicitly whether "the display status can drift from the persisted status" is in scope. If not, say so; do not encode the false model into a test.

### H2 — AC8's ufw scoping is non-idempotent and can drop the live provisioning SSH session

`deploy/provision.sh:162-176` today:

```sh
# Allow SSH first — CRITICAL: must happen before 'ufw enable' or the SSH session may terminate
ufw allow 22/tcp comment 'SSH'
…
ufw --force enable
```

Three defects in the AC's replacement plan:

**(i) ufw rules persist across runs; "replace" is not a thing.** AC8 says *"if `SSH_ALLOWLIST_IP` is set … **replace** `ufw allow 22/tcp` with `ufw allow from …`"*, but specifies no `ufw delete allow 22/tcp`. `provision.sh` is explicitly designed to be re-run (`:50-57` — "provision.sh is idempotent and re-run-safe"). On a host provisioned once without the variable, a later scoped run **leaves the broad rule in place** — ufw is additive, the broad rule still matches, and the scoping has **no effect whatsoever** while appearing to have worked. The reverse is equally bad: a re-run *without* the variable on a scoped host silently re-adds `allow 22/tcp` and re-opens the port to the internet.

**(ii) The AC only guards a malformed value; the dangerous case is a well-formed wrong one.** AC8's verification hand-trace covers unset / valid / `"; rm -rf /"`. It does not cover a **syntactically valid but incorrect** IP — the operator behind NAT or a VPN whose egress differs from what they typed, a dynamic IP, or an operator connected over IPv6. In every one of those, `ufw default deny incoming` + `ufw --force enable` (both run seconds later, `:170-173`) drops the live SSH session **mid-provision**, on the exact critical path the existing comment calls out. `first-time-setup.md:170` already carries this warning for the Hetzner step (*"If the IP is wrong or key-based login is not working, you will be locked out. Recovery requires the Hetzner web console."*); AC8 introduces the same hazard one step earlier with no equivalent guard. Worse, `first-time-setup.md:160` records that ufw's port-22 rule is *"the port genuinely enforced host-side"* — this is the rule that actually bites.

  **Recommendation:** cross-check the passed value against the live session before applying it — `${SSH_CLIENT%% *}` / `${SSH_CONNECTION%% *}` — and refuse to scope (falling back to today's behaviour with a loud warning) on a mismatch or when the variables are absent.

**(iii) The proposed validator is both too loose and too strict.** `case "${SSH_ALLOWLIST_IP}" in *[!0-9./]*) …` accepts `1.2.3.4/24`, `....`, `///`, and rejects every IPv6 address. `deploy/firewall/apply-firewall.sh:29` already validates the **same variable** with a strict IPv4 regex and appends `/32` itself. Reuse that regex verbatim rather than inventing a looser second contract for the same env var.

### H3 — AC8 cites the wrong `first-time-setup.md` step

`docs/deployment/first-time-setup.md` headings: `:73` "## Step 3: Provision the Server", **`:158` "## Step 4: Apply the Firewall"**, `:195` "## Step 5: Prepare Secrets", `:234` "## Step 6: Deploy the Stack".

AC8 says the firewall is applied by *"`first-time-setup.md` Step 6"* and instructs the new doc paragraph to advise *"minimise the window by running **Step 6** immediately after Step 3"* — which, followed literally, tells the operator to deploy the stack. Also note `:180` already reads *"**Run this AFTER Step 3 (provisioning).** The Hetzner default allows SSH from all IPs"* — the AC's new paragraph should extend that existing note rather than add a competing one elsewhere.

### H4 — AC9(c)'s preferred `-maxdepth 1` regresses the recovery coverage it is told to preserve

AC9(c) says *"the comment says 'immediate children' but `-maxdepth 2` walks grandchildren"* and states **"Preferred: `-maxdepth 1`"**. The actual comment (`deploy/provision.sh:27-38`) is a deliberate, fully-reasoned design note:

```
# skillars-deferred-87 AC3 — partial-completion second tier: … So when the top-level owner matches, also scan the
# dir and its immediate children (`-maxdepth 2`) for a uid OR gid mismatch … GNU `chown -R` traverses pre-order
# (each directory is chowned before its contents), so an interruption always leaves the top level done and some
# descendants not; `-maxdepth 2` catches the common early-interruption case … without a full metadata scan of a
# Volume carrying real observability retention on every idempotent re-run. LIMITATION: a mismatch buried more than
# two levels deep is NOT caught here — docs/deployment/first-time-setup.md documents the manual … remediation.
```

There *is* a genuine off-by-one (`-maxdepth 2` from `$dir` = dir + children + **grandchildren**), so the comment understates the scope by one level. But the AC's preferred resolution — narrowing the code to match the understated comment — **loses real detection coverage**, and the AC's justification (*"one level is enough to catch that"*) is not universally true:

Because `chown -R` is pre-order, an interruption while descending into a directory's **only** child leaves that child correct and everything beneath it wrong. For any target with a single subdirectory (`prometheus/` → the Prometheus data dir, `loki/` → `chunks/`), `-maxdepth 1` finds no mismatch and **skips the repair**; `-maxdepth 2` catches it. The ledger itself files this as a perf-watch item, not a defect (`deferred-work.md:1315` — *"Matches the spec's literal `-maxdepth 2`; revisit if provision runtime regresses on a Volume with real retention"*).

**Recommendation:** take the AC's *second* option — keep `-maxdepth 2`, fix the comment to say "the directory, its children and its grandchildren", and keep the existing LIMITATION sentence. This aligns code and comment without losing a level of the `deferred-87` AC3 recovery the AC explicitly instructs dev to preserve.

---

## Medium

### M1 — AC3's two motivating scenarios are both unreachable

AC3 justifies the fix with *"the `videos` row was already physically deleted (e.g. a prior `VideoPhysicalDeletionEvent` already processed, or an admin hard-delete)"*. Neither exists:

- `VideoPhysicalDeletionEvent` → `VideoPhysicalDeletionListener.java:21-23` → `AdminVideoService.deleteVideo(videoId)`, which **soft-deletes**: `AdminVideoService.java:64` `v.setOperationalState(OperationalState.DELETED); videoRepository.save(v);`. The row survives. A second event therefore still sees `lockedVideo.isPresent() == true`.
- There is **no hard-delete of `videos` anywhere in `src/main`** — `grep -rn "videoRepository.delete\|videoRepository.deleteById" src/main/java/` returns zero hits.

Today's actual worst case is a spurious `VideoNotFoundException` (`AdminVideoService.java:50-51`) caught and logged by `VideoPhysicalDeletionListener.java:23-25`: log noise, not the data defect the AC describes. The originating ledger bullet (`deferred-work.md:1068`) is more honest — it calls it a "pre-existing pattern", claims no harm.

The guard remains cheap, correct, and defensible as defence-in-depth — a `drill_video_refs` row *can* point at a videoId with no `videos` row, since `V38__session_module_init.sql:26-30` declares `video_id UUID` with **no FK** (which is also why AC3's test fixture is constructible). But the AC should state the real justification instead of a false one, and drop the implied severity.

### M2 — AC3's `initiateUpload` code snippet does not compile

`lockedVideo` is declared at `DrillUploadService.java:111`, inside `if (existingVideoId != null) { … }` (`:99-116`). The publish site is at `:128`, inside a **different** block — `if (existing.isPresent()) { … }` (`:123-133`). `lockedVideo` is out of scope there. AC3's primary code block uses it directly; the AC's own parenthetical hedges this (*"confirm the variable scope during dev; if it is scoped narrower, hoist…"*), but the block dev will copy is wrong. Specify the hoist in the snippet itself.

Note also that the AC's other suggestion — *"or add a fresh `videoRepository.existsById(existingVideoId)` check under the lock"* — issues a redundant query; the hoisted `Optional` is strictly better and the AC's own Dev Notes already say so.

### M3 — AC2 closes only one direction of the divergence the story claims to eliminate

AC2 is the strongest AC in the bundle and its analysis is correct: `SluPersistenceRetrier`'s `@Recover` methods are `void` and return normally, so `SluPersistenceDispatcher.java:52-53` falls straight into `writeAllWithRetry` after a permanently-lost detail save. Gate it. Agreed.

But the reverse asymmetry is untouched and AC2 forbids touching it (*"Do not change `SnapshotPersistenceRetrier`"*): if the detail save succeeds and `writeAllWithRetry` exhausts its retries, `SnapshotPersistenceRetrier.java:60-64` logs *"…rows lost … manual recovery needed"* and returns — detail rows exist with **no** snapshot delta, so the dashboard fast-path **under**-reports. There is no reconciliation job. The story's headline claim — *"so that the SLU dashboard fast-path and the detail queries can never permanently disagree"* — is therefore not achieved by this bundle.

**Action:** either narrow the story statement to "cannot over-report", or file the under-report direction as a new ledger item under a `deferred-89` story-creation heading. (`deferred-work.md:680` already records the two-transaction split as accepted eventual consistency; this is the sharper, still-open residual of it.)

### M4 — AC2 leaves the empty-batch return value unspecified

`SluPersistenceRetrier.saveSluWithRetry` opens with `if (rows.isEmpty()) { return; }`. AC2 defines the new `boolean` contract as *"`true` = rows are persisted, either by this call, the `existsBySessionId` short-circuit, or the AC1 concurrent-collision catch; `false` = **only** from the `@Recover` paths"* — and never says which branch the empty-list early return takes. Returning `false` would fire the new ERROR (*"SLU detail save exhausted its retries…"*) on a benign empty batch.

Production cannot reach it today (`SluCalculationService.java:169-173` returns before dispatching when `stats.isEmpty()`), but the method is called directly by `SluPersistenceRetrierTest` and `SluRetrierProxyRetryTest`. **Specify `true`** and add the case to AC2's test list.

### M5 — AC6's catalog-parse spec will not match Postgres's normalized output

AC6 tells dev to *"parse the status literal set"* and assert *"the partial index predicate is a **whitelist** (`status = '…'` or `status IN ('…')`), not a blacklist (`status NOT IN (...)`)"*.

Postgres does not store or render the predicate that way. `pg_indexes.indexdef` renders V83's predicate as `WHERE (((status)::text = 'ACTIVE'::text))`, and `pg_get_constraintdef` renders `chk_spp_status` as `CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'EXHAUSTED'::character varying, 'EXPIRED'::character varying])::text[])))` — the `IN` list is normalized to `= ANY (ARRAY[...])`, and every literal carries a cast suffix. A matcher written to AC6's literal spec matches nothing and the test passes **vacuously**.

Worse, the AC's own mutation check (*"temporarily change the expected CHECK set to include a bogus 4th status → the test fails"*) only exercises the CHECK half, so it would **not** detect a vacuous index-predicate assertion. Add a second mutation check that inverts the index predicate.

### M6 — AC6's sweep is already exhausted, and the guard is narrower than the AC advertises

`grep -rn "WHERE.*status" src/main/resources/db/migration/*.sql` over the index-creating lines yields exactly four live status-scoped partial indexes:

| Migration | Predicate | Shape |
|---|---|---|
| `V53__video_quota_system.sql:28` | `WHERE status = 'ACTIVE'` | whitelist |
| `V59__deletion_infrastructure.sql:49` | `WHERE status = 'PENDING'` | whitelist |
| `V59:50` | `WHERE status = 'CLAIMED'` | whitelist |
| `V59:51` | `WHERE status = 'PENDING'` (unique) | whitelist |
| `V83:22` | `WHERE status = 'ACTIVE'` | whitelist |

`V76:15`'s blacklist is dead — V83 opens with `DROP INDEX IF EXISTS booking.idx_session_packs_purchased_coach_expires`. So the sweep will find nothing, and the "flag any live blacklist as decision-needed" branch will not fire. Fine — but say so, and drop the framing that implies discovery work.

More substantively: the AC's stated goal is a guard that *"keeps it — **and any sibling status-scoped partial index** — aligned"*. The specified test does not do that. It hard-codes one CHECK constraint's value set and one index name; it cannot detect a **new** blacklist partial index added later on a different table, which is the actual failure mode the convention exists to prevent. It is a tripwire that fires when someone legitimately adds a fourth status, not a consistency check. Either state that honestly in the AC, or generalise the assertion — "every partial index in `pg_indexes` whose predicate references a column named `status` must use `=` / `= ANY`, never `<>` / `NOT`" — which is both closer to the convention and immune to M5's parse fragility.

### M7 — AC5 deletes the only automated latency signal with no replacement and no follow-up

The de-flake decision itself is sound: `PlaybackServiceIT.java:105-126` does 20 warmup + 100 measured calls and asserts `assertThat(p99).isLessThan(200L)` inside a failsafe IT that gates merges. Removing the gate is the right call.

But note what each option actually costs:

- **Option 1** (measurement-only) logs p50/p95/p99 into failsafe output that nothing scrapes. In practice the check ceases to exist.
- **Option 2** (`@Tag("perf")` + exclusion) requires new config: `grep -n "excludedGroups\|<groups>" pom.xml` returns nothing, so no tag-exclusion mechanism exists today. The AC's own gate for option 2 (*"only viable if a tag-exclusion mechanism already exists"*) therefore rules it out — option 1 is the only live path. Say so and drop option 2.

Either way there is no non-gating perf trend afterwards. **AC10 should file that as a new ledger item** rather than tagging the `deferred-23` bullet (`deferred-work.md:1073`) a plain `[CLOSED]`, since the bullet's own suggested remedy was *"moving to a dedicated non-gating perf-tracking job"* — which this story does not do.

### M8 — AC9(b) is largely already in the repo

`.github/workflows/ci.yml:103-105`:

```yaml
# NOTE: the `sha-<short>` tag is published by every run that *executes*; a run cancelled
# while still pending in the concurrency group (possible in a >=3-push burst) publishes
# nothing. This step does not change that.
```

and again at `:217-218`. That is precisely the comment AC9(b) asks dev to add. What is genuinely missing is only the **`rollback.md` subsection** — confirmed absent (`docs/deployment/rollback.md` contains no "cancelled" / "Re-run" / missing-image content; it documents `sha-<short>` as the rollback pin at `:27`, `:34`, `:44`, `:51`, `:95` and assumes the tag exists).

**Action:** rescope AC9(b) to (a) the `rollback.md` subsection with the `Re-run all jobs` remedy, and optionally (b) relocating/duplicating the existing note to the `concurrency:` block at `:67-69` so it is visible where the mechanism lives. Do not present the comment as new work.

### M9 — AC9(a)'s detection method is partly infeasible, and the scenario is milder than stated

Two problems:

**(i) `findmnt`/`lsblk` cannot do what the AC asks.** AC9(a) offers *"the mount source path, inspected via a bind mount to a temp dir, or `findmnt`/`lsblk` to reach the shadowed content — pick the simplest reliable method"*. `findmnt` and `lsblk` report mount topology; neither can read a directory whose contents are hidden beneath a mount. Only a bind mount of the underlying filesystem (or an unmount) reaches it. Listing them as alternatives sends dev down a dead end.

**(ii) The stated scenario leaves a duplicate, not lost data.** `provision.sh` always stages before mounting: the mount branch (`:369-405`) runs `pre_volume_payload_present` → free-space guard → `rsync -aHAX --numeric-ids "${MOUNT_POINT}/" "${STAGING}/"` → `mount`, and `settle_pre_volume_migration` (`:349-366`) then migrates the staged tree onto the Volume. `migrate_pre_volume_data` already logs, at `:341-342`:

```
NOTE: the original pre-Volume copy still sits on the ROOT DISK, hidden under ${MOUNT_POINT}.
      Reclaim it manually if needed: unmount ${MOUNT_POINT}, rm -rf its root-disk contents, remount.
```

and `first-time-setup.md:110` documents the same. So a host shadowed **by a prior `provision.sh` run** holds a *duplicate* of data already safely on the Volume — the AC's framing (*"its contents (possibly old TLS certs / LGTM / Redis data) are inaccessible"*, implying loss) overstates it.

The genuinely uncovered case is narrower: a Volume mounted **outside this script** (manual `mount`, or an `/etc/fstab` entry) before provisioning ever ran, so nothing was ever staged. **Scope AC9(a) to that**, and prefer the AC's own degraded option — an unconditional pointer on the already-mounted path — over a bind-mount probe. Note that a "prominent multi-line WARNING" that fires on every steady-state re-run is log noise that trains operators to ignore it; a one-line pointer to the existing `first-time-setup.md` procedure is the better shape.

---

## Low / nits

- **L1 — AC1's mutation check is invalid.** *"Mutation check: drop `V122` (or point the test at a schema without it) → two row sets → `SUM` doubles → assertion fails"* cannot work: `V47` still enforces the same key (B1). Any AC1 concurrency test must assert on the **collision-handling behaviour** (one clean `log.info`, no retry storm, no false `@Recover` ERROR, `saveSluWithRetry` returns `true`), not on the index's existence.
- **L2 — state the transaction-boundary assumption.** Catching the violation *at the `saveAll` call* works only because `saveSluWithRetry` is **not** `@Transactional` and `SimpleJpaRepository.saveAll` opens and commits its own transaction, so the constraint fires inside the call. Record this in the AC and in the class javadoc, so a later `@Transactional` on the retrier does not silently move the failure to commit time — outside the catch — and quietly reintroduce B3's failure mode.
- **L3 — the catch swallows a *partial* overlap.** If a concurrent delivery persisted a subset of skills (reachable when `slu.*.scale` config or `skill_definitions.active` changed between deliveries — both are read per-invocation, `SluCalculationService.java:88-95` and `:120-122`), the first collision rolls back the whole insert and the extra skills are silently dropped. Same shape as the existing `existsBySessionId` short-circuit, so not a regression — but the new log line should read "skipped, rows already present" rather than assert equivalence.
- **L4 — path nit.** `DrillUploadServiceConcurrencyIT` lives in `src/test/java/…/platform/session/**api**/`, not `…/session/service/` (`deferred-work.md:1293` confirms). The story's Project Structure Notes imply the service package.
- **L5 — AC4's config-level wording is inverted.** *"set … **below** `Ordered.LOWEST_PRECEDENCE`"* is ambiguous about direction. The shipped value is `LOWEST_PRECEDENCE - 100` — numerically lower, higher precedence, outer. Moot given B2, but do not carry the phrasing into a future story.
- **L6 — AC4 on `BandwidthResetService` is pointless.** Its body is one `jdbcTemplate.update(...)` (`:23-29`), atomic without any transaction advisor. A delegate-bean split there adds a class for nothing.
- **L7 — AC7 triples an unauthenticated OTP-email surface with no rate limit; record it.** `resendPhoneOtp` is reachable via `@PreAuthorize("permitAll()")` with no throttle, and no rate-limit infrastructure exists in `src/main/java` (no bucket4j / `RateLimit` / filter). V121's `uq_pot_one_active_per_user` is **not** a throttle: `ParentRegistrationService.java:239` deletes the prior unused token *before* `:250` inserts the new one, so sequential resends always succeed — the index blocks only genuinely concurrent ones. Parent has the identical gap, so this is not a regression the story introduces, and AC7's *"copy parent's rate-limit check if it has one"* correctly resolves to "there is none". But going from one such endpoint to three warrants a ledger entry rather than silence.
- **L8** — AC7's remaining expectations check out and need no change: 400 for `OtpVerificationException` (`ApiAdvice.java:506-507`), 409 for the V121 collision via `ApiAdvice`'s constraint mapping, `ResendOtpRequest` reusable as-is, and the spy-free IT shape.

---

## What the story gets right

Worth stating, since most of this review is corrective:

- **AC2 is correct, well-scoped, and the most valuable item in the bundle.** The `void` `@Recover` → unconditional `writeAllWithRetry` fall-through is real (`SluPersistenceDispatcher.java:52-53`), the `void`→`boolean` contract change is minimal (one production caller), and the Spring Retry constraint the Dev Notes cite (`@Recover` return types must be assignable to the retried method's) is accurate. Fix M3/M4 and it is ready.
- **The V119 marker reasoning is accurate.** `SnapshotPersistenceRetrier`'s javadoc confirms `writeAll` is idempotent per `(session_id, weekly-bucket)`, so the snapshot total genuinely is applied once — which is why the story's asymmetry analysis (detail can double, snapshot cannot) was the right thing to look at, even though V47 turns out to close the detail side already.
- **The one-session-one-player assumption behind a `(session_id, skill_code)` key is sound** — `Session` carries `booking_id NOT NULL` + `player_id NOT NULL` and is resolved via `Optional<Session> findByBookingId(...)`, so a session serves exactly one player and the key cannot collide across players. (This is why V47 was safe to add in the first place.)
- **The context-ceiling discipline in the Dev Notes is right and should be kept** — reuse existing IT bases, no `@MockitoSpyBean`, no new `@SpringBootTest` config.
- **AC5's judgement is correct** even though its option list needs trimming: a millisecond wall-clock p99 in a merge-gating IT should not gate correctness.
- **The `deleteVideo` half of AC3 is a genuine small improvement** — `DrillUploadService.java:168` currently discards the `findByIdForUpdate` result entirely, and capturing it costs nothing.

---

## Recommended disposition before dev starts

| AC | Action |
|----|--------|
| **AC1** | **Drop the `V122` migration.** Rewrite the Problem section against `V47`. Keep the collision handling, **narrowed to the `uq_player_skill_stats_session_skill` constraint name** (B3), and correct the `retryFor` claim. Rewrite the mutation check (L1). |
| **AC2** | **Keep.** Specify the `rows.isEmpty()` return as `true` (M4). Narrow the story's "can never disagree" claim or file the under-report residual (M3). |
| **AC3** | **Keep, reframed as defence-in-depth.** Correct the motivating scenarios (M1) and fix the non-compiling snippet (M2). |
| **AC4** | **Cut the production change.** Keep only the advisor-order regression test against the existing `AsyncConfig` order. Tag D3 `[STALE]`, not `[CLOSED]`. |
| **AC5** | **Keep option 1 only.** Remove option 2 (no tag-exclusion mechanism exists). File the "no perf trend" follow-up (M7). |
| **AC6** | **Rework or defer.** The premise is wrong (H1), the parse spec is unworkable (M5), and the guard is narrower than claimed (M6). If kept, generalise the assertion and re-baseline it on what the code actually does. |
| **AC7** | **Keep.** Fix 200-vs-204 and the email-vs-SMS error (B4); delete the i18n subtask; add the ledger note on the unauthenticated OTP surface (L7). |
| **AC8** | **Rework.** Fix the step reference (H3). Add explicit rule removal for idempotency, an `$SSH_CLIENT` cross-check, and `apply-firewall.sh`'s existing IPv4 regex (H2) — or cut the `provision.sh` change and ship the documentation half alone. |
| **AC9** | **(a)** rescope to the mount-outside-provision case and prefer the degraded pointer (M9). **(b)** rescope to `rollback.md` only (M8). **(c)** take the `-maxdepth 2` + fixed-comment option, not `-maxdepth 1` (H4). |
| **AC10** | **Rewrite the tags** to reflect the above — `deferred-86` uniqueness bullet and `deferred-4` D3 are `[STALE]`, not `[CLOSED]`; `deferred-23` needs a residual; two new items to file (AC2's under-report direction, AC7's OTP surface). |
