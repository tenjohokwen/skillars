# Story Deferred-23: Flaky Perf Test, Dead Frontend Code, Lock-Timeout Gap & Ops-Doc Fixes

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Skillars maintainer,
I want six small, independently-verified deferred items closed — a structurally-flaky performance
assertion that measures the wrong percentile, two dead admin pages calling REST endpoints that no
longer exist, a dead composable with a latent Vue calling-convention trap, a disaster-recovery runbook
command that targets Redis filenames Redis 7 no longer creates, a CI race that can leave the `:latest`
image tag pointing at an older commit, and a payment-adjacent repository lock method missing the
timeout hint all three of its siblings carry,
so that each of six unrelated, previously-deferred defects — spanning testing, frontend, ops docs,
CI and payment-module locking — gets fixed without bundling any of them into a larger story that
would need its own design pass.

### Why this story exists

Drawn directly from `_bmad-output/implementation-artifacts/deferred-work.md`, per Mbah's direction to
group small, unrelated, already-deferred items into one story to reduce dev overhead — the same spirit
as `skillars-deferred-11`, `-20`, `-21` and `-22`. All six UAT stories are `done`, `skillars-deferred-22`
is in `review`, and `uat-readiness-priorities.md`'s P0/P1/P2 rows are fully claimed; only P3
(chronically re-deferred, explicitly out of scope) remains unclaimed from that document. This story
draws from ledger items P3 does not cover: two surfaced during `skillars-uat-1`/`-3`/`-4` story creation
(2026-08-10/11/12) that no subsequent audit has re-examined, one from a `skillars-uat-2` code review
group, and three older items independently re-verified against **current** code during this story's
creation — not trusted from the ledger's text, which the ledger's own header warns can be stale.

**Three stale ledger entries found and corrected during this story's creation** (already fixed in code,
never marked closed — see AC7): `skillars-deferred-17` review D4 (`AvailabilityManagerPage.vue`
`windows[0]` timezone read), `skillars-deferred-18` review D1 (DST-gap inverted-slot guard in
`AvailabilityService`), and `skillars-deferred-16` review D1 (`MessagingService.verifyIsParty`'s
`IllegalArgumentException`-to-500 gap). None of the six items this story implements were affected by
that discovery — it surfaced while scanning the same sections for open work.

## Deferred Items Closed

| Source | Item | Current location (re-verified) | AC |
|---|---|---|---|
| code review of `skillars-deferred-16-messaging-moderation-recovery-identity-safety` (2026-08-05), downgraded by `skillars-uat-1` (2026-08-10) | D8 — `PlaybackServiceIT.authorizePlayback_performance_p99Under200ms` computes the max of 100 samples, not the p99, and has no warmup | `PlaybackServiceIT.java:105-120` | 1 |
| `skillars-uat-1-admin-bootstrap-and-onboarding-unblock` story creation (2026-08-10) | D4 — dead Tenant admin pages call REST endpoints removed with the tenant module | `TenantListPage.vue`, `TenantDetailPage.vue`, `routes.js:328-336`, `MainLayout.vue:210-216`, `admin.api.js:15-71` | 2 |
| `skillars-uat-4-i18n-locale-and-message-resolution-integrity` story creation (2026-08-12) D1, plus its own code review (2026-08-12) D1 | `useTimezone.js` has zero callers and an unsafe `useI18n()`-outside-`setup()` calling convention | `src/frontend/src/composables/useTimezone.js` | 3 |
| code review of `skillars-uat-2-session-duration-and-booking-slot-integrity` — Group D (2026-08-11) | `runbook.md`'s AOF-corruption recovery command targets pre-Redis-7 filenames | `docs/deployment/runbook.md:216` | 4 |
| code review of `skillars-uat-2-session-duration-and-booking-slot-integrity` — Group D (2026-08-11) | `ci.yml`'s `:latest` tag push has no `concurrency` group | `.github/workflows/ci.yml:49-83` | 5 |
| `skillars-uat-3-payment-capture-integrity-and-backup-retention` code review (2026-08-11) D6 | `SessionPackPurchaseRepository.findByIdForUpdate` missing `jakarta.persistence.lock.timeout` hint carried by its three siblings | `SessionPackPurchaseRepository.java:17-20` | 6 |

**Explicitly NOT in this story** (considered during story creation and rejected — do not implement):

- **`skillars-10-2` D1** (`AFTER_COMMIT` refund drop) and **`skillars-7-2` Group 2 D6/D7**
  (`BookingDisputedEvent`/`SessionPackExhaustedEvent.playerId`) — all four are on
  `uat-readiness-priorities.md`'s explicit P3 "chronically re-deferred" list; three or more separate
  audits have already decided the cost exceeds the benefit. Not revisited.
- **`skillars-deferred-16` review D2** (`softDeleteMessage` takes its `PESSIMISTIC_WRITE` lock before
  the conversation-membership/sender-ownership checks run) — real, still open, but the lock-before-authz
  ordering was a deliberate AC5 design choice (closing the double-delete race), so "fixing" it risks
  reopening that race. Needs its own regression pass proving both properties hold together, not a
  bundled slot.
- **`skillars-uat-3` D5/D18** (`DisputeService`'s payment lookups are a bare, status-unguarded
  `findById`) — recorded as a tripwire against a future `BookingStateMachine` change, not a live defect;
  correctness today depends only on `DISPUTED` staying unreachable from `PAYMENT_PENDING`, which it is.
  No action needed unless the state machine changes.
- **`skillars-uat-3` D12 / code review** (`cancelBookingAsParent`'s locked read races a settle-side plain
  `UPDATE` with no lock-timeout hint of its own) — architectural, spans both the cancel and settle paths
  on the payment-critical booking-cancellation flow; explicitly flagged as needing its own regression
  pass, unlike AC6's narrower, already-established three-sibling pattern.
- **V94's `ACCESS EXCLUSIVE` migration lock** and **`reserveCapture`'s connection-pool sizing** — both
  explicitly recorded as "acceptable at current UAT-stage size," ops/perf concerns to revisit before
  production load, not defects.
- **`BookingServiceTest`'s positional constructor** (D17) — real test-hygiene debt, but changing how
  ~26 tests obtain their collaborators is its own pass, not a bundled slot.
- The broad body of 2026-06-era Story 1.x–10.x deferred items not listed above, and all `deploy-1`/
  `deploy-2`/`deploy-3` infra items outside the two `skillars-uat-2` Group D items picked here — never
  re-verified against current scripts by any audit outside the UAT stories' own narrow scope.

## Acceptance Criteria

1. **`PlaybackServiceIT.authorizePlayback_performance_p99Under200ms` measures a real p99 with a
   warmup, not the max of 100 un-warmed samples.** Today (`PlaybackServiceIT.java:105-120`) the test
   runs exactly 100 iterations, sorts the latencies, and reads `latencies[(int) (iterations * 0.99)]`
   = `latencies[99]` — the **last** element of a 0-indexed 100-element array, i.e. the single slowest
   call, not the 99th percentile. (Nearest-rank p99 of 100 samples is `latencies[98]`.) The assertion
   is also dominated by JIT warmup, GC and Testcontainers connection-pool latency on the very first
   calls, since nothing runs before the timed loop. Fix both: add a short discard-the-results warmup
   phase (e.g. 20 untimed calls to `playbackService.authorizePlayback(...)` before the 100 measured
   ones) so the timed loop reflects steady-state latency, and correct the percentile index to
   `latencies[98]`. Keep the 100-iteration count and the `< 200ms` threshold — this AC fixes what is
   measured, not the bar it is measured against.

2. **Dead Tenant admin pages, their routes, nav entry and API client calls are deleted.** The backend
   tenant module was removed in commit `a170e69` ("Remove the tenant module") — confirmed by `grep -rn
   "tenants" src/main/java/` returning zero matches. `src/frontend/src/pages/admin/TenantListPage.vue`
   and `TenantDetailPage.vue` still exist and are still routed (`routes.js:328-336`, the `admin` children
   array) and still linked from the admin nav (`MainLayout.vue:210-216`, the "Tenants" `q-item`), so an
   admin who clicks it gets a page that calls dead endpoints. Delete both `.vue` files, the two route
   entries, the nav `q-item`, and the entire `// --- Tenant Management (Phase 33) ---` block in
   `admin.api.js` (`createTenant`, `listTenants`, `getTenantDetail`, `getWebhookSecret`,
   `updateTenantName`, `updateTenantEmail`, `updateTenantWebhookUrl`, `suspendTenant`,
   `reactivateTenant`, `regenerateWebhookSecret`, `generateKey`, `rotateKey`, `revokeKey`,
   `reactivateKey` — **14** functions, all calling `/v1/admin/tenants/**`; `revokeKey` (`:65`, called
   from the `TenantDetailPage.vue` being deleted) was missed by an earlier count of this block). Leave
   `HealthDashboardPage.vue` and its route/nav
   entry untouched — it is the one admin page with a live backend. Grep for any other `tenant*` import
   or reference in `src/frontend/src` after deleting, to confirm nothing else references the removed
   files (the only other hits found during story creation — `fr-FR/index.js`'s "maintenant" — are
   substring false positives, not real references; re-verify this at implementation time rather than
   trusting it).

3. **The dead `useTimezone.js` composable is deleted.** `grep -rn "useTimezone" src/frontend/src
   --include="*.vue" --include="*.js"` returns only its own definition — zero callers anywhere in the
   frontend, confirmed during story creation. It also carries a latent Vue trap flagged by the
   `skillars-uat-4` code review: `useI18n()` is called inside a plain exported function, not a
   guaranteed component `setup()` scope, which would break the moment a future caller invokes it
   outside `setup()`. Since nothing calls it, delete the file outright rather than fix the calling
   convention — there is no behavior to preserve. Confirm no `.vue` file's `<script setup>` block
   destructures or imports from `composables/useTimezone` before deleting.

4. **`docs/deployment/runbook.md`'s Redis AOF-corruption recovery command targets files Redis 7
   actually creates.** `docker-compose.yml:90` runs `redis:7-alpine` with `command: redis-server
   --appendonly yes` and no `--appenddirname` override, so Redis 7's default Multi-Part AOF layout
   applies: AOF data lives under `/opt/skillars/data/redis/appendonlydir/` (containing
   `appendonly.aof.*.base.rdb`, `appendonly.aof.*.incr.aof` and `appendonly.aof.manifest`), not in a
   single flat `appendonly.aof` file — that filename is a pre-Redis-7 layout. `runbook.md:225` currently
   runs `rm -f /opt/skillars/data/redis/appendonly.aof /opt/skillars/data/redis/dump.rdb`, which
   silently no-ops on the AOF half (the file it targets does not exist) while still removing
   `dump.rdb` (Redis's default periodic RDB save points are unmodified by `--appendonly yes` alone, so
   a `dump.rdb` snapshot can genuinely exist alongside the AOF directory). Fix: change the command to
   `rm -rf /opt/skillars/data/redis/appendonlydir /opt/skillars/data/redis/dump.rdb`, removing the
   whole AOF directory (safe and complete — the operator is already clearing all Redis data per the
   surrounding `CAUTION` block) alongside the RDB file. Do not touch any other Redis path reference in
   the file (e.g. the OOM/restart-loop scenario above it, which never mentions AOF filenames).

5. **`.github/workflows/ci.yml`'s image-publish job gets a `concurrency` group.** `build-and-push`
   (`ci.yml:49-83`) pushes both a SHA tag and `:latest` on every push to `master`, with no
   `concurrency:` block anywhere in the file. Two `master` pushes whose `build-and-push` jobs complete
   out of trigger order could, in theory, leave `:latest` pointing at an older commit than the newest
   push (the SHA tags are unaffected — each is unique per commit and `rollback.md` pins to those, not
   to `:latest`). Add a job-level `concurrency` block to `build-and-push` keyed on something that
   serializes same-branch runs without cancelling an in-flight image push mid-upload (e.g. `group:
   build-and-push-${{ github.ref }}`, `cancel-in-progress: false` — cancelling a `docker/build-push-action`
   invocation mid-push is worse than letting it finish and queuing the next one). Do not add
   `cancel-in-progress: true` — that would abort a legitimate in-progress GHCR push.
   **Known residual limitation, found by code review — do not claim this fully closes the race
   described above.** `build-and-push` declares `needs: test` (`ci.yml:50`), so a run only requests the
   concurrency slot once its own `test` job finishes, not at push time. If an older push's `test` job
   happens to run slower than a newer push's, the newer commit's `build-and-push` can become
   queue-ready and complete first, and the older push's `build-and-push` — becoming ready later — then
   runs second and overwrites `:latest` with the *older* commit. `concurrency` with
   `cancel-in-progress: false` still prevents two `build-and-push` runs from interleaving mid-upload
   (a real improvement, keep it), but it does not guarantee the newest-pushed commit wins `:latest` —
   ready-order tracks `test`-job duration, not commit chronology. A stronger fix (e.g. having
   `build-and-push` check `git merge-base --is-ancestor` against the currently-published `:latest`
   digest before overwriting it) is out of this AC's scope; record the residual gap in the ledger
   annotation (AC7) rather than asserting it is closed.

6. **`SessionPackPurchaseRepository.findByIdForUpdate` gets the same `lock.timeout` hint as its three
   siblings.** `CoachProfileRepository.findByIdForUpdate` (`:28-34`), `BookingRescheduleRequestRepository
   .findByIdForUpdate` (`:23-31`, whose own comment says the annotation stack was "copied from
   CoachProfileRepository...including the bounded lock wait — without it, contention blocks the caller
   indefinitely instead of surfacing as ApiAdvice's 409") and `BookingRepository.findByIdForUpdate`
   (`:188-193`) all carry `@Lock(PESSIMISTIC_WRITE)` plus `@QueryHints(@QueryHint(name =
   "jakarta.persistence.lock.timeout", value = "5000"))`. `SessionPackPurchaseRepository
   .findByIdForUpdate` (`:17-20`) carries `@Lock(PESSIMISTIC_WRITE)` and an extra, inconsistent
   `@Transactional` the other three don't have, but no `@QueryHints` at all — so contention on a
   session-pack-purchase row blocks the caller's connection indefinitely instead of surfacing
   `ApiAdvice.pessimisticLockExceptionHandler`'s existing 409 `generic.conflict` mapping.
   **`findByIdForUpdate` has four call sites across two files, not three in one file** — corrected by
   code review: `PackSessionService.deductSession`/`restoreSession`/`pausePack` (`:53,73,112`, all
   `@Transactional`), plus `SessionPackPaymentService.extendPack` (`:99-104`, itself `@Transactional`)
   — a *different* class the original draft mis-attributed the call to. `PackSessionService` has no
   `extendPack` method at all. Fix: add the identical `@QueryHints(@QueryHint(name =
   "jakarta.persistence.lock.timeout", value = "5000"))` annotation, and remove the redundant
   `@Transactional` on the method (Spring Data derives transactionality from the calling service's own
   `@Transactional`, matching all three sibling repositories' shape exactly). Import
   `jakarta.persistence.QueryHint` and `org.springframework.data.jpa.repository.QueryHints` as the
   siblings do. Verify all four call sites (not three) are themselves `@Transactional` before removing
   the method-level annotation.

7. **Ledger hygiene in `deferred-work.md`.** Annotate every item this story closes (see **Deferred
   Items Closed** table) with `[CLOSED by skillars-deferred-23 ACn]` at its current ledger location
   once implemented. Additionally, correct the three stale entries found during this story's creation
   (already fixed in code, never marked) with a `[CLOSED — verified already fixed in code, found during
   skillars-deferred-23 story creation]` annotation, following this file's own established
   strikethrough-plus-`ORIGINAL:` convention: `skillars-deferred-17` review D4
   (`AvailabilityManagerPage.vue` reads `store.coachTimezone`, not `windows[0]`, at `:337` — comment
   there cites the exact fix), `skillars-deferred-18` review D1 (`AvailabilityService.java:158-165`
   already carries the `!windowEnd.isAfter(windowStart)` guard with a WARN log, verbatim matching the
   item's own described fix), and `skillars-deferred-16` review D1 (`MessagingService.verifyIsParty`'s
   `default` arm — comment at `:345-350`, `throw` at `:351-353` at the time of this story's creation —
   already throws `OperationNotAllowedException`/`NOT_A_PARTY` with a comment explaining the fix;
   `MessagingApiAdvice` maps it to 403). **Line numbers drift fast in this file — `skillars-deferred-22`
   alone shifted this method's lines by ~6 while this story was being written.** Re-verify the exact
   line range at implementation time rather than trusting the numbers above; the point being cited
   (the `default` arm exists and throws the right exception) is what matters, not the line count.

## Tasks / Subtasks

- [x] Task 1 — Fix `PlaybackServiceIT`'s p99 measurement (AC: #1)
  - [x] Add a warmup loop before the timed loop in `authorizePlayback_performance_p99Under200ms`
    (`PlaybackServiceIT.java:105-120`) — e.g. 20 untimed calls to `playbackService.authorizePlayback(...)`
    with results discarded, using a distinct viewer-id prefix so they don't collide with the timed
    iterations' `"perf-viewer-" + i` ids
  - [x] Change `latencies[(int) (iterations * 0.99)]` to the correct nearest-rank p99 index for a
    100-element sorted array (`latencies[98]`) — do not just recompute the formula generically unless
    you also verify it still yields 98 for `iterations = 100`
  - [x] Run the test standalone at least 3 times locally (`mvn -o test -Dtest=PlaybackServiceIT#authorizePlayback_performance_p99Under200ms`)
    to confirm it is no longer flaky before considering this AC done — a single green run does not
    prove the fix given the item's own history of "passes in isolation, fails under load"
  - [x] `mvn -o test -Dtest=PlaybackServiceIT` full class green

- [x] Task 2 — Delete dead Tenant admin pages and their wiring (AC: #2)
  - [x] Delete `src/frontend/src/pages/admin/TenantListPage.vue` and `TenantDetailPage.vue`
  - [x] Remove the two `tenants`/`tenants/:tenantRef` route entries from `routes.js`'s `admin` children
    array (`:328-336`), leaving `health-dashboard` as the sole child
  - [x] Remove the "Tenants" `q-item` nav entry from `MainLayout.vue` (`:210-216`)
  - [x] Remove the `// --- Tenant Management (Phase 33) ---` block from `admin.api.js` (`:15-71`, all
    14 functions listed in AC2, including `revokeKey`)
  - [x] Grep `src/frontend/src` for any remaining `Tenant`/`tenant` reference after deleting (excluding
    incidental substring matches like "maintenant" in `fr-FR/index.js`) — confirm zero real hits
  - [x] `npx eslint src/frontend/src` clean; `quasar build` (or the project's standard frontend build
    check) succeeds with the deleted files/routes

- [x] Task 3 — Delete the dead `useTimezone.js` composable (AC: #3)
  - [x] Confirm zero callers via `grep -rn "useTimezone" src/frontend/src --include="*.vue" --include="*.js"`
    (should return only the file's own definition) — re-verify at implementation time, not from this
    story's research alone
  - [x] Delete `src/frontend/src/composables/useTimezone.js`
  - [x] `npx eslint src/frontend/src` clean; `quasar build` succeeds

- [x] Task 4 — Fix `runbook.md`'s Redis AOF recovery command (AC: #4)
  - [x] Change the `rm -f ... appendonly.aof ... dump.rdb` line (`runbook.md:225`) to `rm -rf
    /opt/skillars/data/redis/appendonlydir /opt/skillars/data/redis/dump.rdb`
  - [x] Read the surrounding "If Redis cannot start due to AOF corruption" block (`runbook.md:214-227`)
    in full to confirm no other line references the old filename shape
  - [x] No test to run — this is a docs-only change; proofread the edited block for consistency with
    the `CAUTION` note directly below it (unchanged)

- [x] Task 5 — Add a concurrency group to `ci.yml`'s `build-and-push` job (AC: #5)
  - [x] Add a job-level `concurrency:` block to `build-and-push` (`ci.yml:49-51`) with `group:
    build-and-push-${{ github.ref }}` and `cancel-in-progress: false`
  - [x] Confirm the `test` job (`ci.yml:8`) is left untouched — this AC only concerns the image-publish
    job
  - [x] Validate YAML syntax (`yamllint .github/workflows/ci.yml` if available, or a manual review) —
    no CI run is triggerable from this story-creation context, so this cannot be proven green by a live
    workflow run before merge; note this honestly in Dev Notes rather than claiming it was verified

- [x] Task 6 — Add lock-timeout hint to `SessionPackPurchaseRepository` (AC: #6)
  - [x] Add `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))` to
    `findByIdForUpdate` (`SessionPackPurchaseRepository.java:17-20`), importing
    `org.springframework.data.jpa.repository.QueryHints` and `jakarta.persistence.QueryHint`
  - [x] Remove the redundant `@Transactional` from the same method — confirm removing it doesn't change
    behavior by checking every call site is itself already `@Transactional`: `PackSessionService
    .deductSession`/`restoreSession`/`pausePack` (`:53,73,112`) **and** `SessionPackPaymentService
    .extendPack` (`:99-104`) — four call sites across two files, not three in `PackSessionService` alone
  - [x] Check whether an existing test (e.g. `PackSessionServiceParityTest`, `PackSessionServicePauseTest`,
    `SessionPackPaymentServiceTest`, or a concurrency IT) already exercises `findByIdForUpdate`'s lock
    behavior; if a lock-contention test exists for one of the three sibling repositories (e.g.
    `BookingServiceConcurrencyIT`), use it as the pattern for a new test asserting a
    `PessimisticLockingFailureException`/409 surfaces within a bounded time rather than blocking
    indefinitely — do not skip this because "it's just an annotation," per this project's own repeated
    finding that untested locks are indistinguishable from absent ones.
    **Finding during implementation (user-directed resolution — see Dev Notes/Completion Notes):** wrote
    an IT asserting exactly that bounded-timeout claim and it failed — a contended `findByIdForUpdate`
    call blocked for the full duration a competing lock was held (proven up to 12s) and then completed
    normally, no `PessimisticLockingFailureException` ever raised. Traced to Hibernate 6.6.53's
    `PostgreSQLDialect.withTimeout()`, which only special-cases `NO_WAIT`/`SKIP_LOCKED` — any finite
    `jakarta.persistence.lock.timeout` value is a no-op on Postgres. This affects all three sibling
    repositories too, not just this one. Per user direction, added the annotation anyway (for
    consistency with the sibling shape) and replaced the bounded-timeout test with a real
    lock-**contention** (mutex) test proving the `PESSIMISTIC_WRITE` lock itself still serializes
    concurrent access without a lost update — mutation-verified by temporarily removing the lock and
    confirming the test fails. Logged as a new `deferred-work.md` item (see AC7/Completion Notes) rather
    than fixed in this story, which is out of AC6's narrow scope.
  - [x] `mvn -o test -Dtest=PackSessionServiceParityTest,PackSessionServicePauseTest,SessionPackPaymentServiceTest`
    (plus whatever new test is added) green — note `PackSessionServiceTest` does **not** exist as a
    class in this repo; these three are the real test files covering `findByIdForUpdate`'s four callers.
    New test: `SessionPackPurchaseLockContentionIT` (see above); also re-ran `PackExtensionIT` (the
    existing concurrency IT for the fourth call site, `extendPack`) to confirm no regression.

- [x] Task 7 — Ledger hygiene (AC: #7)
  - [x] Annotate all 6 closed items per the **Deferred Items Closed** table in `deferred-work.md` with
    `[CLOSED by skillars-deferred-23 ACn]`. The AC6 item's closure note is honest about the residual
    finding: the annotation was added for sibling-consistency, but implementing this AC's own test
    requirement disproved the "surfaces 409" premise — see the new ledger item filed below for it,
    and Task 6/Dev Notes above for the full trace.
  - [x] Annotate the 3 stale-but-already-fixed entries found during story creation (`deferred-17` review
    D4, `deferred-18` review D1, `deferred-16` review D1) with the correction noted in AC7 — do not
    delete the original text; follow the file's established strikethrough + `ORIGINAL:` convention.
    Found already annotated by the story-creation pass; verified the annotations are present and correct.
  - [x] Update `sprint-status.yaml`'s `skillars-deferred-23-...` entry status as this story progresses
    (`ready-for-dev` → `in-progress` → `review` → `done`), per this repo's established convention
  - [x] (New, beyond the story's original text) Filed a new `deferred-work.md` item, "Deferred from:
    skillars-deferred-23-flaky-perf-test-dead-code-and-ops-hygiene-fixes", documenting the systemic
    Hibernate/Postgres `jakarta.persistence.lock.timeout` no-op finding across all four
    `findByIdForUpdate` repositories, per user direction during Task 6 (see Dev Notes)

### Review Findings

Pre-dev senior review (2026-08-14), all 4 findings verified against source and folded into the story
above before implementation began:

1. **AC6/Task 6 misattributed `extendPack`.** It lives in `SessionPackPaymentService.java:99-104`, not
   `PackSessionService` (which has no such method) — a fourth call site across a second file. Task 6's
   test command named a nonexistent class, `PackSessionServiceTest`; corrected to the three real files
   (`PackSessionServiceParityTest`, `PackSessionServicePauseTest`, `SessionPackPaymentServiceTest`).
   **Applied.**
2. **AC5's fix doesn't fully close the race it names.** `build-and-push` depends on `needs: test`, so
   concurrency-slot readiness tracks `test`-job duration, not push order — a slower older push's job can
   still overwrite `:latest` after a faster newer push's job completes. The `concurrency` block still
   prevents mid-upload interleaving; it does not guarantee commit-chronological `:latest` ownership.
   **Applied** — AC5 now states this residual limitation explicitly rather than claiming full closure.
3. **AC7's `MessagingService.java:344-347` citation was already stale** at story-creation time —
   `skillars-deferred-22` shifted the method's lines by ~6 in the interim; the real citation is
   comment `:345-350`, throw `:351-353`. **Applied**, plus an explicit re-verify-at-implementation-time
   caveat matching AC2/AC3's existing pattern, since this file's lines drift fast.
4. **AC2 undercounted `admin.api.js`'s Tenant block by one function.** `revokeKey` (`:65`, called from
   `TenantDetailPage.vue:364`) exists in code but was missing from AC2's 13-function list. Task 2's
   line-range instruction (`:15-71`) would have caught it regardless, so risk was low, but the count and
   named list are now correct (14 functions). **Applied.**

**Post-implementation code review (2026-08-14)** — Blind Hunter + Edge Case Hunter + Acceptance Auditor,
14 raw findings triaged to 0 decision-needed, 4 patch, 2 defer, 8 dismissed (refuted on verification or
already fully documented):

- [x] [Review][Patch] Hardcoded p99 index (`latencies[98]`) breaks if `iterations` ever changes without
  updating the literal — the same class of "quietly wrong percentile" bug AC1 set out to eliminate, just
  relocated. [`src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java:123`]
  **Applied.** Replaced the literal with `(iterations * 99 + 99) / 100 - 1` — an integer-arithmetic
  nearest-rank ceiling division that avoids floating-point rounding pitfalls in `iterations * 0.99`.
  Verified it still yields index 98 for `iterations = 100`; `PlaybackServiceIT` 4/4 green.
- [x] [Review][Patch] `SessionPackPurchaseLockContentionIT`'s executor pool isn't cleaned up in a
  `finally`, and the test method has no `@Timeout` backstop — if `awaitTermination` times out or the
  barrier fails, worker threads (and the DB row lock one may hold) can outlive the test.
  [`src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPurchaseLockContentionIT.java:64-81`]
  **Applied.** Added `@Timeout(45)` on the test method and moved the executor usage into a `try/finally`
  with `pool.shutdownNow()`. Re-ran the mutation-kill check (removing `@Lock(PESSIMISTIC_WRITE)`) to
  confirm the `finally` doesn't mask the failure — still errors at `f.get()` as before; test green with
  the lock restored.
- [x] [Review][Patch] `OneTimeKeyModal.vue` is orphaned dead code — its only two callers
  (`TenantListPage.vue`, `TenantDetailPage.vue`) were deleted by AC2, but the modal component itself was
  not; verified zero remaining references anywhere in `src/frontend/src`.
  [`src/frontend/src/components/admin/OneTimeKeyModal.vue`]
  **Applied.** Re-confirmed zero references via grep, then deleted the file. `npx eslint`/`quasar build`
  clean.
- [x] [Review][Patch] `SessionPackPurchaseLockContentionIT`'s class javadoc and assertion message
  describe the failure mode as "a lost update leaving 4 instead of 3," but `SessionPackPurchase` carries
  a `@Version` column, so removing `@Lock(PESSIMISTIC_WRITE)` actually surfaces
  `ObjectOptimisticLockingFailureException` on the losing thread rather than a silent wrong count — the
  test still proves the mutex works, just via a different, undocumented failure signature.
  [`src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPurchaseLockContentionIT.java:29-44,87-90`]
  **Applied.** Rewrote the javadoc's "what this test proves" paragraph and the final assertion's message
  to describe the actual mutation-verified failure mode (an `ObjectOptimisticLockingFailureException`
  surfacing at `f.get()`, not a silent count corruption).
- [x] [Review][Defer] AC5's `concurrency` group prevents `build-and-push` runs from interleaving
  mid-upload but does not guarantee the newest-pushed commit wins `:latest` (`needs: test` means slot
  readiness tracks test-job duration, not push order) — deferred, pre-existing, already explicitly
  documented as a residual gap in this story's own AC5 and in `deferred-work.md`; a stronger fix
  (ancestor-check before overwrite) is a deliberate scope decision, not an oversight.
  [`.github/workflows/ci.yml:49-53`]
- [x] [Review][Defer] `PlaybackServiceIT.authorizePlayback_performance_p99Under200ms` still asserts a
  hard wall-clock latency bound inside a correctness-gating integration test, which remains structurally
  fragile regardless of AC1's warmup+index fixes — deferred, pre-existing design choice, not introduced
  by this story and out of AC1's scope (AC1 fixed the two proximate bugs: wrong index, no warmup).
  [`src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java:105-125`]

## Dev Notes

- **Scope discipline.** Six small, independently-safe items across six different files/mechanisms —
  a flaky perf test, dead frontend pages, a dead composable, an ops-doc command, a CI config gap, and a
  repository lock-timeout hint. Do not use this as a pretext to "clean up while you're in there" on
  adjacent code. If something adjacent looks wrong, note it as a new `deferred-work.md` item; don't fix
  it here.

- **AC1's fix must be proven flake-resistant, not just green once.** This exact test has a documented
  history (`skillars-deferred-16`'s original code review) of failing outside the full suite and passing
  inside it, and of failing on a clean `git worktree` at HEAD with no code changes at all. A single
  passing run after the fix proves nothing; run it standalone several times as Task 1 specifies.

- **AC2 and AC3 are both dead-code deletions with no behavioral fix — verify "dead" before deleting,
  don't trust this story's own research as the final word.** Re-run the greps in Task 2/3 at
  implementation time. If either turns out to have a caller this story's research missed, stop and
  reconsider — do not force a deletion through by also deleting the caller unless that caller is
  itself obviously dead by the same standard.

- **AC5 cannot be verified by a live CI run from this story-creation context.** The fix is a small,
  well-understood YAML addition (this exact `concurrency`/`cancel-in-progress: false` shape is standard
  GitHub Actions practice for "don't cancel an in-flight publish, just don't let two run at once"), but
  Task 5 explicitly calls for honest reporting that no live workflow run was used to confirm it, rather
  than claiming false verification.

- **AC6 touches the payment module's session-pack locking, not the payment/settlement path itself.**
  `findByIdForUpdate` is used by `PackSessionService.deductSession`/`restoreSession`/`pausePack` **and**
  `SessionPackPaymentService.extendPack` — four call sites across two files (corrected by code review;
  the original draft mis-attributed `extendPack` to `PackSessionService`, which has no such method) —
  all inventory/state mutations on a `SessionPackPurchase` row, not Stripe calls. This is a lower-risk
  surface than `BookingPaymentPersistenceService`/`PaymentLifecycleService`, but this project has
  **three times** (`skillars-deferred-13`, `-15`, and `skillars-uat-3`'s own review) found a lock whose
  test passed unchanged with the lock removed — do not skip adding real lock-contention coverage for
  this AC on the assumption that "it's the same annotation as three other places that are tested" is
  itself proof this usage is tested.

- **AC7's three "already fixed" corrections were found by re-reading the same file sections this story's
  research already had to read for its own six items** — this is bookkeeping, not new investigation
  work. Do not expand Task 7 into a fresh audit of the rest of the file; that is out of this story's
  scope.

- **This story touches Java, Vue/JS, one Markdown doc, and one GitHub Actions workflow — no schema
  change, no new migration.** `mvn -o test` (targeted per-AC, or a full run before marking done) plus
  `npx eslint`/`quasar build` for the frontend deletions is the verification bar; no `mvn -o verify`/IT
  run is required unless the new lock-timeout test (Task 6) needs a real DB, in which case scope that
  test appropriately (existing sibling lock tests in this codebase are ITs, e.g.
  `BookingServiceConcurrencyIT` — follow that precedent if a unit-level mock can't prove the timeout).

- **File paths this story touches:**
  - `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java` (AC1)
  - `src/frontend/src/pages/admin/TenantListPage.vue`, `TenantDetailPage.vue` (deleted, AC2)
  - `src/frontend/src/router/routes.js`, `src/frontend/src/layouts/MainLayout.vue`,
    `src/frontend/src/api/admin.api.js` (AC2)
  - `src/frontend/src/composables/useTimezone.js` (deleted, AC3)
  - `docs/deployment/runbook.md` (AC4)
  - `.github/workflows/ci.yml` (AC5)
  - `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java`
    (AC6)
  - `src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`,
    `SessionPackPaymentService.java` (AC6, read-only — confirming call sites are `@Transactional`)
  - `src/test/java/com/softropic/skillars/platform/payment/service/PackSessionServiceParityTest.java`,
    `PackSessionServicePauseTest.java`, `SessionPackPaymentServiceTest.java` + new lock-contention test
    (AC6)
  - `_bmad-output/implementation-artifacts/deferred-work.md` (AC7)
  - `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC7, status line only)

### Project Structure Notes

- All six ACs are same-file-or-narrower fixes/deletions to existing files — no new files expected
  except AC6's test additions.
- Follows the same flat, non-epic-nested tracking convention every other `skillars-deferred-N` story
  uses in `sprint-status.yaml` (the "DEFERRED WORK" block).

### References

- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — "## Deferred from: code review of
  skillars-deferred-16-messaging-moderation-recovery-identity-safety (2026-08-05)" D8, downgraded by the
  "## Last audit" note under `skillars-uat-1` (AC1); "## Deferred from: skillars-uat-1-admin-bootstrap-and-onboarding-unblock
  (2026-08-10)" D4 (AC2); "## Deferred from: skillars-uat-4-i18n-locale-and-message-resolution-integrity
  (2026-08-12)" D1 and its code review's D1 (AC3); "## Deferred from: code review of
  skillars-uat-2-session-duration-and-booking-slot-integrity — Group D (2026-08-11)" (AC4, AC5);
  "## Deferred from: code review of skillars-uat-3-payment-capture-integrity-and-backup-retention
  (2026-08-11)" D6 (AC6)
- [Source: _bmad-output/implementation-artifacts/uat-readiness-priorities.md] — P3 exclusion list
  (lines 271-292); confirms P0/P1/P2 fully claimed
- [Source: _bmad-output/implementation-artifacts/skillars-deferred-22-messaging-role-guard-payment-idempotency-and-resource-integrity-fixes.md]
  — precedent for the "Deferred Items Closed"/"Explicitly NOT in this story" format and the
  scope-discipline Dev Note
- [Source: src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java:105-120]
  — current p99 test shape confirming AC1's premise
- [Source: src/frontend/src/pages/admin/TenantListPage.vue, TenantDetailPage.vue;
  src/frontend/src/router/routes.js:328-336; src/frontend/src/layouts/MainLayout.vue:210-216;
  src/frontend/src/api/admin.api.js:15-71] — confirms AC2's dead-code scope; zero backend `tenants`
  matches confirmed via `grep -rn "tenants" src/main/java/` and commit `a170e69`
- [Source: src/frontend/src/composables/useTimezone.js] — confirms AC3's dead-code scope; zero callers
  confirmed via grep
- [Source: docs/deployment/runbook.md:214-227; docker-compose.yml:88-94] — confirms AC4's premise:
  `redis:7-alpine` with `--appendonly yes` and no `--appenddirname` override means the default
  `appendonlydir/` layout applies
- [Source: .github/workflows/ci.yml:49-83] — confirms AC5's premise: no `concurrency` block anywhere
  in the file
- [Source: src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java:17-20;
  src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java:28-34;
  src/main/java/com/softropic/skillars/platform/booking/repo/BookingRescheduleRequestRepository.java:23-31;
  src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java:188-193] —
  confirms AC6's premise and the exact sibling annotation stack to mirror
- [Source: src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java:53,73,112;
  src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java:99-104] —
  code review correction: `findByIdForUpdate`'s four real call sites, spanning two classes, not three
  in one as originally drafted
- [Source: src/frontend/src/pages/coach/AvailabilityManagerPage.vue:333-337;
  src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java:144-165;
  src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java:335-353] —
  confirms the three stale-ledger corrections in AC7 (line numbers re-verified by code review; drift
  fast in this file, re-check again at implementation time)
- [Source: src/frontend/src/api/admin.api.js:15-71] — code review correction: 14 functions in the
  Tenant Management block, not 13; `revokeKey` (`:65`) was missing from the original count

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

One HALT-worthy discovery mid-Task 6, resolved by asking the user rather than proceeding silently: implementing AC6's own test requirement ("prove the lock.timeout hint bounds the wait") disproved the premise, not just for `SessionPackPurchaseRepository` but for all four `findByIdForUpdate` repositories in this codebase. Traced to Hibernate 6.6.53's `PostgreSQLDialect.withTimeout()`, which only special-cases `NO_WAIT`/`SKIP_LOCKED` — any finite `jakarta.persistence.lock.timeout` value is silently a no-op on Postgres. Confirmed empirically with a real IT (written, then discarded once understood) that held a competing lock for 12s against a `findByIdForUpdate` call carrying the 5000ms hint; the call blocked the full 12s and completed normally, no `PessimisticLockingFailureException` ever thrown. Presented the finding and three options to the user via AskUserQuestion; user chose "add the annotation for sibling-consistency, document the gap, don't expand scope to fix it here." See Completion Notes AC6 and the new `deferred-work.md` entry filed under this story's own "Deferred from" heading for full detail.

### Completion Notes List

- **AC1** — Added a 20-iteration untimed warmup loop (distinct `"warmup-viewer-"` id prefix, so it can't collide with the timed loop's `"perf-viewer-" + i` ids) before the 100 measured iterations in `PlaybackServiceIT.authorizePlayback_performance_p99Under200ms`, and corrected the percentile index from `latencies[(int)(iterations*0.99)]` (`= latencies[99]`, the max) to `latencies[98]` (true nearest-rank p99 of 100 samples). Ran the method standalone 3 times (all green) plus a full-class run (4/4 green) to prove non-flakiness, per the item's own "passes in isolation, fails under load" history.
- **AC2** — Deleted `TenantListPage.vue`, `TenantDetailPage.vue`, their two `routes.js` route entries (leaving `health-dashboard` as the sole `admin` child), the `MainLayout.vue` "Tenants" nav `q-item`, and the entire 14-function Tenant Management block in `admin.api.js` (confirmed 14, not 13 — `revokeKey` was in the `:15-71` range the deletion covered). Post-delete grep for `Tenant`/`tenant` across `src/frontend/src` returned only the `fr-FR` "maintenant" false positives the story predicted. `npx eslint src/frontend/src` clean; `quasar build` succeeded.
- **AC3** — Re-confirmed zero callers via the exact grep the story specified, then deleted `useTimezone.js` outright (no calling-convention fix needed — nothing to preserve). `npx eslint`/`quasar build` clean.
- **AC4** — Changed `runbook.md:225`'s `rm -f .../appendonly.aof .../dump.rdb` to `rm -rf .../appendonlydir .../dump.rdb`, matching Redis 7's default Multi-Part AOF layout (confirmed via `docker-compose.yml:88-94` — no `--appenddirname` override). Docs-only; no test to run.
- **AC5** — Added `concurrency: {group: build-and-push-${{ github.ref }}, cancel-in-progress: false}` to `ci.yml`'s `build-and-push` job. `test` job untouched. Validated YAML syntax via Ruby's `YAML.load_file` (no `yamllint`/`pyyaml` available in this environment) — no live CI run possible from this context, as the story itself acknowledges.
- **AC6** — Added `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))` to `SessionPackPurchaseRepository.findByIdForUpdate` and removed its redundant `@Transactional`, matching the three sibling repositories' shape exactly; confirmed all four real call sites (`PackSessionService.deductSession`/`restoreSession`/`pausePack`, `SessionPackPaymentService.extendPack`) are themselves `@Transactional`. **Discovered while proving this AC's own bounded-timeout requirement that the hint has zero effect on Postgres under this Hibernate version** (see Debug Log References) — user-directed resolution: keep the annotation for sibling-consistency, replace the (now-impossible-to-honestly-write) bounded-timeout test with a real lock-**contention** test, and log the systemic finding to `deferred-work.md` rather than fix it here. Added `SessionPackPurchaseLockContentionIT` (new IT) proving `@Lock(PESSIMISTIC_WRITE)` still correctly serializes two concurrent `deductSession` calls with no lost update — mutation-verified by temporarily removing the `@Lock`/`@QueryHints` pair and confirming the test fails (`ObjectOptimisticLockingFailureException` on the loser, 2/2 runs). Re-ran `PackExtensionIT` (existing concurrency IT covering the fourth call site, `extendPack`) to confirm no regression: 6/6 green. Targeted run: `PackSessionServiceParityTest`, `PackSessionServicePauseTest`, `SessionPackPaymentServiceTest`, `SessionPackPurchaseLockContentionIT`, `PackExtensionIT` — 26/26 green.
- **AC7** — All 6 closed items annotated `[CLOSED by skillars-deferred-23 ACn]` at their ledger locations (all 6 already carried a story-creation-time `[OWNED BY ...]` placeholder, now flipped). The 3 stale-but-already-fixed entries (`deferred-17` review D4, `deferred-18` review D1, `deferred-16` review D1) were found already correctly annotated by the story-creation pass — verified, no further edit needed. Filed one new `deferred-work.md` entry (under a new "Deferred from: skillars-deferred-23-..." heading) documenting the systemic Hibernate/Postgres `lock.timeout` no-op finding from AC6, since fixing it is out of this story's scope. `sprint-status.yaml` progressed `ready-for-dev` → `in-progress` → (this update) `review`.
- **Full regression**: `mvn -o verify` (unit + all integration tests via failsafe, matching `ci.yml`'s own `mvn -B verify` — required here since AC6 added a new DB-backed IT): **882 unit tests + 897 integration tests, 0 failures, 0 errors** (1 pre-existing unrelated unit skip, 4 pre-existing unrelated IT skips), **BUILD SUCCESS**. Note: an initial plain `mvn -o test` run silently excluded all `*IT.java` classes (Surefire's default include pattern is `*Test.java` only; this project's integration tests run via the failsafe plugin bound to `verify`) — corrected by re-running the true full suite before relying on it.
- **No scope creep**: no adjacent-code cleanup performed beyond each AC's stated fix, per Dev Notes' scope-discipline instruction. The one exception — filing the new `deferred-work.md` systemic-gap entry — was explicit user direction during Task 6, not unilateral scope expansion.

### Post-Review Fixes (2026-08-14)

Post-implementation code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor) surfaced 14 raw
findings, triaged to 0 decision-needed, 4 patch, 2 defer, 8 dismissed. All 4 patches applied:

- Replaced `PlaybackServiceIT`'s hardcoded `latencies[98]` with an integer-arithmetic nearest-rank
  formula (`(iterations * 99 + 99) / 100 - 1`) so the index no longer silently drifts if `iterations`
  ever changes — the same class of bug AC1 fixed, just relocated. Re-verified: yields 98 for
  `iterations = 100`; `PlaybackServiceIT` 4/4 green.
- Added `@Timeout(45)` and a `try/finally { pool.shutdownNow(); }` to
  `SessionPackPurchaseLockContentionIT` so a stuck `awaitTermination`/barrier can't leak worker threads
  (and a held DB row lock) past the test. Re-ran the mutation-kill (temporarily removing
  `@Lock(PESSIMISTIC_WRITE)`) to confirm the `finally` doesn't mask the failure — still errors at
  `f.get()` as before.
- Deleted `OneTimeKeyModal.vue`, orphaned when AC2 deleted its only two callers
  (`TenantListPage.vue`/`TenantDetailPage.vue`) but not itself. Re-confirmed zero references first;
  `eslint`/`quasar build` clean after.
- Corrected `SessionPackPurchaseLockContentionIT`'s javadoc and assertion message, which described the
  unlocked-race failure mode as "a lost update leaving 4 instead of 3." `SessionPackPurchase` carries an
  `@Version` column, so the actual mutation-verified failure is an unhandled
  `ObjectOptimisticLockingFailureException` surfacing at `f.get()`, not a silent wrong count — the prose
  now matches what was actually observed.

The 2 deferred findings (AC5's `needs: test` residual race, `PlaybackServiceIT`'s structural
wall-clock-assertion fragility) were both already explicitly documented as accepted, pre-existing
limitations in this story's own ACs and Dev Notes — no further action taken, per the review's own
disposition.

Full `mvn -o verify` re-run after all 4 patches: **882 unit tests + 897 integration tests, 0 failures,
0 errors, BUILD SUCCESS** (same 5 pre-existing unrelated skips as before).

### File List

- `src/test/java/com/softropic/skillars/platform/video/service/PlaybackServiceIT.java` (AC1)
- `src/frontend/src/pages/admin/TenantListPage.vue` (AC2 — deleted)
- `src/frontend/src/pages/admin/TenantDetailPage.vue` (AC2 — deleted)
- `src/frontend/src/router/routes.js` (AC2)
- `src/frontend/src/layouts/MainLayout.vue` (AC2)
- `src/frontend/src/api/admin.api.js` (AC2)
- `src/frontend/src/composables/useTimezone.js` (AC3 — deleted)
- `docs/deployment/runbook.md` (AC4)
- `.github/workflows/ci.yml` (AC5)
- `src/main/java/com/softropic/skillars/platform/payment/repo/SessionPackPurchaseRepository.java` (AC6)
- `src/test/java/com/softropic/skillars/platform/payment/service/SessionPackPurchaseLockContentionIT.java` (AC6 — new file)
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC7)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (AC7, status line + header comment)
- `src/frontend/src/components/admin/OneTimeKeyModal.vue` (review follow-up — deleted, orphaned by AC2)

## Change Log

| Date | Change |
|---|---|
| 2026-08-14 | All 7 ACs implemented. AC6 surfaced a systemic Hibernate/Postgres finding beyond this story's scope (see Debug Log References) — resolved per user direction, documented in a new `deferred-work.md` entry. Full `mvn -o verify` green (882 unit + 897 integration tests, 0 failures/errors, 5 pre-existing unrelated skips). Story moved to `review`. |
| 2026-08-14 | Addressed code review findings — 4 patches applied (p99-index hardcoding, executor cleanup + `@Timeout`, orphaned `OneTimeKeyModal.vue` deleted, corrected failure-mode documentation), 2 items deferred as pre-existing/already-documented, 8 dismissed. Full `mvn -o verify` re-run green (882 unit + 897 integration tests, 0 failures/errors, 5 pre-existing unrelated skips). |
