# Story Deferred-59: Radar Composite Overflow Guard, Drill-Video-Ref Persist Fix, Availability-Timezone Diagnostics & SSH Firewall Rule Hygiene

Status: ready-for-dev

## Story

As an engineer operating this platform,
I want the radar composite calculator's session-count arithmetic to fail loudly instead of silently
wrapping on overflow, drill-video-ref cloning to use an INSERT-only persist instead of a wasted
existence-checking merge, a coach's mis-configured availability windows to leave an operator-visible
trace instead of reading identically to an ordinary "slot doesn't fit" rejection, the coach-subscription
payment-method card mount/unmount cycle to be safe against a rapid toggle race, and the production SSH
firewall-rule script to stop accumulating stale allowlist rules on every IP change,
so that five small, independently-verified, still-open ledger items close without needing to wait for a
sixth or seventh story to bundle them.

### Why this story exists

`_bmad-output/implementation-artifacts/deferred-work.md` (1823 lines at the time this story was created)
was re-mined end to end at commit `cfec038` (the tip of `skillars-deferred-58`, now merged), reading every
line rather than relying on section headers. Unlike the last several story-creation passes (`-49` through
`-58`), which each re-mined only the ledger's "most recently active tail," this pass deliberately covered
the **entire file**, because that recent tail is now genuinely thin: nearly every item in the span from
`skillars-deferred-40` onward is either already closed, already `[PICKED UP by ...]` a shipped story, or is
explicitly self-described as needing a product/architecture decision (`DisputeService`'s `FROZEN` filter,
the video-bandwidth dedup rule, the `jakarta.persistence.lock.timeout`-has-no-effect-on-Postgres question,
per-booking batch-accept outcome reporting's remaining edges) — the same conclusion `skillars-deferred-58`
itself already recorded about that span.

The user explicitly asked for a **larger** bundle than the ~2–3-item average this ledger's recent stories
have shipped, on the grounds that the file is growing faster than it shrinks. This pass's honest finding,
after reading the full file: **the pool of genuinely open, decision-light, low-risk items is smaller than
that target implies.** The overwhelming majority of the ~40 unclosed bullets left in the older
(pre-`skillars-deferred-40`, mostly pre-`skillars-uat-*`) sections carry their own explicit "pre-existing,
accepted, spec-designed, out of scope" reasoning — many have already been read and explicitly *not* picked
up by several earlier story-creation passes (`skillars-deferred-16`, `-18`, `-23` and others each recorded
their own "examined and deliberately left alone" list covering much of this same territory). Re-opening
those without new information would just repeat those same authors' own reasoning. A further large category
— deploy/infrastructure hardening items — this project's own convention (recorded identically across nearly
every deploy-story section in this file) treats as lowest priority.

Five items survived a live re-verification against the current tree (not merely trusted from the ledger's
own text — every one below was re-read in the actual source file before being included):

- **AC1** — two related items, same root cause, same file: `## Deferred from: code review of
  skillars-5-4-skills-radar-display-development-correlation (2026-06-19)` W8 ("`(int)` cast on `totalCount`
  in `RadarCompositeCalculationService`") and `## Deferred from: code review of
  skillars-5-3-skills-radar-assessment-entry-multi-coach-cumulation — Pass 2 (2026-06-19)` DEF6 ("`entry_count`
  long→double→int narrowing in composite calculator"). Re-verified live at
  `RadarCompositeCalculationService.java:70,74,78`: three unguarded `(int)` casts on a `double` that started
  life as a native-query `long` count, silently wrapping past `Integer.MAX_VALUE`. Same class of defensive
  fix `skillars-deferred-26` AC2 already applied to `CoachProfileService`'s `strikeCount` narrowing
  (`Math.toIntExact`).
- **AC2** — `## Deferred from: external code review of skillars-4-1-drill-library-foundation (2026-06-17)`
  D2: "`DrillVideoRef.save()` issues merge (SELECT + INSERT) instead of persist (INSERT-only)". Re-verified
  live: `DrillVideoRef.java` has a manually-assigned `@Id drillId` with no `@GeneratedValue` and does not
  implement `Persistable`, so Spring Data's default `isNew()` check treats the already-non-null id as "not
  new" and every `drillVideoRefRepository.save(cloneRef)` call in `DrillLibraryService.cloneDrill`
  (`:136-140`) issues a `merge()` — an extra `SELECT` before every clone's `INSERT` — even though the row is
  provably new (`cloneRef` is freshly `new DrillVideoRef()`'d two lines above).
- **AC3** — `## Deferred from: code review of skillars-3-3-booking-request-approval-workflow Group B
  (2026-06-15)`: "All availability windows have invalid timezone → misleading 403; add a distinct error code
  or admin-visible flag when no valid windows exist vs. slot outside valid windows". Re-verified live:
  `BookingService.isSlotWithinAvailabilityWindow` (`:827-854`) still `continue`s past any window whose
  `canonicalTimezone` fails `ZoneId.of(...)`, logging one `WARN` per skipped window but returning a plain
  `false` indistinguishable from "windows were valid but the slot just doesn't fit them" — the same
  `SLOT_OUTSIDE_AVAILABILITY` 403 either way. (The item's other named defect, midnight-crossing sessions,
  is already fixed — see the comment at `:842-843` — only the invalid-timezone half is still open.) Scoped
  to the **admin-visible-flag** half of the item's own two suggested fixes, not the **distinct wire error
  code** half — see AC3 below for why.
- **AC4** — `## Deferred from: code review of skillars-deferred-11-stripe-card-collection (2026-08-04)`:
  "`PaymentMethodCard.vue`'s `watch(showForm)` has no in-flight guard against rapid toggle races between
  async `mountCardElement()` and sync `unmountCardElement()`". Re-verified live at
  `PaymentMethodCard.vue:111-135`: unchanged since that review — `mountCardElement()` is `async` (awaits
  `ensureStripeReady()` then `nextTick()`), `unmountCardElement()` is synchronous, and `watch(showForm, ...)`
  fires both with no guard against a stale in-flight mount resolving after a newer toggle has already
  reversed it.
- **AC5** — `## Deferred from: code review of deploy-1-5-first-time-setup-documentation (2026-06-03)`:
  "`apply-firewall.sh` accumulates old SSH allowlist rules when re-run with a different
  `SSH_ALLOWLIST_IP` — delete step targets `0.0.0.0/0` source, not the previously-set specific CIDR".
  Re-verified live: `deploy/firewall/apply-firewall.sh:38-44` still deletes the port-22 rule with a
  hardcoded `--source-ips 0.0.0.0/0` guess, which can never match the actual previously-applied
  `${SSH_ALLOWLIST_IP}/32` rule, so `hcloud firewall delete-rule` silently no-ops (Hetzner's own CLI
  behavior for a non-matching rule spec) and the subsequent `add-rule` accumulates a second, stale SSH
  source alongside the new one — every re-run with a changed IP widens SSH access instead of narrowing it
  to the new operator's address, the opposite of the script's stated purpose.

**Examined and deliberately not picked up**, beyond the already-thin recent-tail exclusions
`skillars-deferred-58` already recorded: `SessionPackPurchase.expiresAt`'s "mutable, no `updatable=false`"
item (`## Deferred from: adversarial code review of skillars-7-2 Group 1 DB+Entities`) — re-verified live
that `expiresAt` is legitimately written by three separate call sites
(`PackSessionService.pausePack`/`SessionPackPaymentService.extendPack`/`purchasePack`'s 60-day initial
grant), so adding `updatable = false` as the item's own title suggests would silently break all three; the
item itself never actually proposes a concrete fix beyond naming the setter "a footgun", so there is nothing
correct to implement here — a false lead caught by re-verification rather than trusted from the ledger text.
`isSlotWithinAvailabilityWindow`'s midnight-crossing/overnight-window limitation (line 1636,
`skillars-deferred-49` review) — explicitly out of scope per that story's own Dev Notes, which direct reusing
the helper as-is. `BookingDuplicationService.duplicateNextWeek`'s DST-shift-on-168-hour-offset item (line
1635) — a real but non-mechanical fix (calendar-aware date math, not a bounded patch). The 12+ deploy/infra
items across `deploy-1-*` through `deploy-3-*` sections not named above — this project's own established
convention (recorded identically in nearly every one of those sections) treats deploy/tooling hardening as
lowest priority, and the great majority of the remaining ones there are themselves narrower or riskier than
AC5's script fix (PGPASSWORD-in-`ps aux` exposure, firewall-window-during-provisioning, Redis fstab/UID
concerns) — real, but each needs either infrastructure not available to verify here or a design call on
acceptable operational risk, not a bounded code patch.

## Acceptance Criteria

1. **AC1 — Guard `RadarCompositeCalculationService`'s three session-count narrowing casts against silent
   overflow.**
   - File: `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java:68-79`.
   - Current shape (three near-identical blocks):
     ```java
     if (types.containsKey(AssessmentType.OBJECTIVE)) {
         composite  += types.get(AssessmentType.OBJECTIVE)[0] * WEIGHT_OBJECTIVE.doubleValue();
         totalCount += (int) types.get(AssessmentType.OBJECTIVE)[1];
     }
     ```
   - Change each of the three `(int) types.get(X)[1]` narrowing casts to
     `Math.toIntExact(Math.round(types.get(X)[1]))` — `types.get(X)[1]` is a `double` that started life as
     `((Number) row[3]).longValue()` (`:56`), so `Math.round(...)` recovers the exact original `long` value
     (doubles exactly represent integers up to 2^53, far beyond any realistic assessment count) before
     `Math.toIntExact` narrows it, throwing `ArithmeticException` instead of silently wrapping past
     `Integer.MAX_VALUE`. Apply identically to all three blocks (`OBJECTIVE`, `MATCH_OBSERVATION`,
     `COACH_EVALUATION`):
     ```java
     if (types.containsKey(AssessmentType.OBJECTIVE)) {
         composite  += types.get(AssessmentType.OBJECTIVE)[0] * WEIGHT_OBJECTIVE.doubleValue();
         totalCount += Math.toIntExact(Math.round(types.get(AssessmentType.OBJECTIVE)[1]));
     }
     ```
   - **Why `Math.round` first, not a bare `Math.toIntExact((long) types.get(X)[1])`**: a plain `(long)` cast
     on a `double` truncates toward zero rather than rounding, which is unnecessary risk for a value that is
     already an exact integer stored in `double` form — `Math.round` is the safe, idiomatic way to recover
     an exact integral `double` as a `long` before narrowing.
   - **This is a defensive-only change under today's realistic data volumes** — matches this codebase's
     existing precedent (`skillars-deferred-26` AC2's identical `Math.toIntExact` fix for
     `CoachProfileService`'s `strikeCount` cast) of hardening a narrowing cast without changing any
     reachable behavior at current scale. `@Async`/`AFTER_COMMIT`-listener exceptions are already caught and
     logged by this method's own `catch (Exception e)` block (`:89-92`), so an `ArithmeticException` here
     surfaces as the existing "composite recalculation failed... composite is now stale" WARN log rather
     than a new unhandled-exception path.
   - **Test coverage.** No existing unit test file covers this service (`grep -rln
     "RadarCompositeCalculationService" src/test` — confirm at implementation time; if a test file exists,
     add a case seeding a synthetic `Object[]` row with `row[3]` set beyond `Integer.MAX_VALUE` and asserting
     `totalCount`'s overflow now throws/logs rather than wraps to a negative value; if no test file exists,
     this is a new, narrowly-scoped unit test — do not build broader coverage for the rest of the class).
     Run the new/updated test class and confirm green.

2. **AC2 — Make `DrillVideoRef` implement `Persistable<UUID>` so `drillVideoRefRepository.save(...)` on a
   freshly-constructed instance issues an INSERT-only `persist()`, not a `merge()`.**
   - File: `src/main/java/com/softropic/skillars/platform/session/repo/DrillVideoRef.java` (full current
     file, 27 lines):
     ```java
     package com.softropic.skillars.platform.session.repo;

     import jakarta.persistence.Column;
     import jakarta.persistence.Entity;
     import jakarta.persistence.Id;
     import jakarta.persistence.Table;
     import lombok.Getter;
     import lombok.NoArgsConstructor;
     import lombok.Setter;

     import java.util.UUID;

     @Entity
     @Table(schema = "session", name = "drill_video_refs")
     @Getter
     @Setter
     @NoArgsConstructor
     public class DrillVideoRef {

         @Id
         @Column(name = "drill_id")
         private UUID drillId;

         @Column(name = "video_id")
         private UUID videoId;

         @Column(name = "ref_count", nullable = false)
         private int refCount = 1;
     }
     ```
   - Implement `Persistable<UUID>` with a `@Transient` new-instance flag, flipped false on load — the
     standard Spring Data JPA idiom for a manually-assigned `@Id` with no `@Version` column:
     ```java
     package com.softropic.skillars.platform.session.repo;

     import jakarta.persistence.Column;
     import jakarta.persistence.Entity;
     import jakarta.persistence.Id;
     import jakarta.persistence.PostLoad;
     import jakarta.persistence.PostPersist;
     import jakarta.persistence.Table;
     import jakarta.persistence.Transient;
     import lombok.Getter;
     import lombok.NoArgsConstructor;
     import lombok.Setter;
     import org.springframework.data.domain.Persistable;

     import java.util.UUID;

     @Entity
     @Table(schema = "session", name = "drill_video_refs")
     @Getter
     @Setter
     @NoArgsConstructor
     public class DrillVideoRef implements Persistable<UUID> {

         @Id
         @Column(name = "drill_id")
         private UUID drillId;

         @Column(name = "video_id")
         private UUID videoId;

         @Column(name = "ref_count", nullable = false)
         private int refCount = 1;

         @Transient
         private boolean isNew = true;

         @Override
         public UUID getId() {
             return drillId;
         }

         @Override
         public boolean isNew() {
             return isNew;
         }

         @PostPersist
         @PostLoad
         void markNotNew() {
             isNew = false;
         }
     }
     ```
     `getDrillId()` (Lombok `@Getter`) and the new `getId()` both exist side by side — `getId()` is the
     `Persistable` contract method Spring Data calls internally; callers of this entity keep using
     `getDrillId()`/`setDrillId(...)` unchanged (confirm at implementation time via `grep -rn
     "\.getDrillId()\|\.setDrillId(" src/main/java src/test/java` that no call site needs updating — none is
     expected, since neither the field name nor its accessor changes).
   - **Why not `@GeneratedValue` instead**: `drill_id` is not a surrogate key — it is a 1:1 FK to
     `session.drills.id` (verified by the existing repository method names, `findByDrillId`/`findByDrillIdIn`),
     deliberately assigned by the caller (`cloneRef.setDrillId(saved.getId())`,
     `DrillLibraryService.java:137`), not generated by this table. `Persistable` is the correct fix for
     exactly this shape (assigned, not generated, primary key) — changing to `@GeneratedValue` would break
     the FK relationship entirely.
   - **Test coverage.** `DrillLibraryServiceTest` (if it exists — confirm at implementation time) or
     `DrillLibraryResourceIT`'s existing clone-drill test(s) should already exercise `cloneDrill`'s
     video-ref-copy branch; no new test is required to prove behavior (the entity's public contract and the
     rows it produces are unchanged — only the SQL Hibernate issues changes from
     `SELECT`+`INSERT`/`UPDATE` to a bare `INSERT`). Add one new unit test directly on the entity, mirroring
     `CoachMediaItemTest`'s pattern (`skillars-deferred-25` AC1 — a same-package plain JUnit test with no
     persistence context) asserting `new DrillVideoRef().isNew()` is `true` and that `markNotNew()` (called
     directly, simulating the `@PostPersist`/`@PostLoad` callback) flips it to `false`. Run the existing
     drill-clone IT/test suite (`mvn -o test -Dtest=DrillLibraryServiceTest` and/or
     `mvn -o integration-test -Dit.test=DrillLibraryResourceIT`, whichever exists) to confirm no regression,
     plus the new `DrillVideoRefTest`.

3. **AC3 — Add an operator-visible WARN log distinguishing "every availability window had an invalid
   timezone" from an ordinary out-of-window rejection, in `BookingService.isSlotWithinAvailabilityWindow`.**
   - File: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:827-854`.
   - Current shape:
     ```java
     boolean isSlotWithinAvailabilityWindow(Instant startTime, Instant endTime,
                                            List<CoachAvailabilityWindow> windows) {
         for (CoachAvailabilityWindow w : windows) {
             ZoneId zoneId;
             try {
                 zoneId = ZoneId.of(w.getCanonicalTimezone());
             } catch (DateTimeException e) {
                 log.warn("Availability window {} has invalid timezone '{}' — skipping",
                     w.getId(), w.getCanonicalTimezone());
                 continue;
             }
             // ... existing window-fit logic, unchanged ...
         }
         return false;
     }
     ```
   - Track how many windows were actually evaluated (had a valid timezone) and, only when the method is
     about to return `false` **and** at least one window existed **and** zero of them had a valid timezone,
     emit one additional summary `WARN` distinguishing this from the ordinary "windows were valid, slot just
     didn't fit" case:
     ```java
     boolean isSlotWithinAvailabilityWindow(Instant startTime, Instant endTime,
                                            List<CoachAvailabilityWindow> windows) {
         int validWindowsEvaluated = 0;
         for (CoachAvailabilityWindow w : windows) {
             ZoneId zoneId;
             try {
                 zoneId = ZoneId.of(w.getCanonicalTimezone());
             } catch (DateTimeException e) {
                 log.warn("Availability window {} has invalid timezone '{}' — skipping",
                     w.getId(), w.getCanonicalTimezone());
                 continue;
             }
             validWindowsEvaluated++;
             // ... existing window-fit logic, unchanged ...
             // (the existing `return true;` inside the fit-check stays exactly where it is)
         }
         if (!windows.isEmpty() && validWindowsEvaluated == 0) {
             log.warn("Coach {} has {} availability window(s) but none had a valid timezone — "
                     + "slot check cannot succeed against any window",
                 windows.get(0).getCoachId(), windows.size());
         }
         return false;
     }
     ```
   - **Why a log line, not a new wire error code**: the ledger item itself hedges between "add a distinct
     error code OR an admin-visible flag" — two different fixes with different blast radii. A new
     `BookingError` value is an API contract change reaching three call sites
     (`BookingService.createBookingRequest`, `RescheduleService`'s two checks, `BookingDuplicationService`)
     each of which would need a product decision about what the *parent* should be told (a coach
     misconfiguration is not the parent's fault, and telling them "the coach has no valid availability" is a
     different UX conversation than "that slot doesn't work") — exactly the kind of judgment call this
     bundled small-fix story should not make unilaterally. A WARN log satisfies the item's own
     "admin-visible flag" half at zero contract risk: an operator grepping logs (or a future log-based alert)
     can now distinguish a coach-data-quality problem from ordinary booking traffic, which the item's own
     title cites as the actual operational gap ("misleading 403" — misleading to whoever is debugging it, not
     to the parent's UX).
   - **Test coverage.** `BookingServiceTest` already has unit coverage calling
     `isSlotWithinAvailabilityWindow` directly (confirm exact existing test names at implementation time via
     `grep -n "isSlotWithinAvailabilityWindow" src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`).
     Add a new case seeding one or more windows with an unparseable `canonicalTimezone`
     (e.g. `"not-a-zone"`) and asserting, via a Logback `ListAppender` attached to `BookingService`'s logger
     (mirroring `skillars-deferred-36` AC1's established pattern for asserting on a specific log line), that
     the new summary WARN fires with the expected coach id and window count — not just that the method
     returns `false` (which existing coverage may already assert). Run `mvn -o test -Dtest=BookingServiceTest`
     and confirm green.

4. **AC4 — In-flight guard against a rapid `showForm` toggle race in `PaymentMethodCard.vue`.**
   - File: `src/frontend/src/components/payment/PaymentMethodCard.vue:111-135`.
   - Current shape:
     ```js
     async function mountCardElement() {
       const ready = await ensureStripeReady()
       if (!ready) return
       await nextTick()
       if (!cardElementRef.value || cardElement) return
       try {
         cardElement = elements.create('card')
         cardElement.mount(cardElementRef.value)
         elementsReady.value = true
       } catch {
         cardElement = null
         stripeUnavailable.value = true
       }
     }

     function unmountCardElement() {
       cardElement?.unmount()
       cardElement = null
       elementsReady.value = false
     }

     watch(showForm, (show) => {
       if (show) mountCardElement()
       else unmountCardElement()
     })
     ```
   - Add a module-scoped generation counter, incremented on every `showForm` change and checked after each
     `await` point inside `mountCardElement` before it does anything observable — the same
     supersession-guard shape this project already established in `booking.store.js`'s
     `loadCoachBookingRequests` (`skillars-deferred-38` AC1):
     ```js
     let mountGeneration = 0

     async function mountCardElement() {
       const generation = ++mountGeneration
       const ready = await ensureStripeReady()
       if (generation !== mountGeneration) return
       if (!ready) return
       await nextTick()
       if (generation !== mountGeneration) return
       if (!cardElementRef.value || cardElement) return
       try {
         cardElement = elements.create('card')
         cardElement.mount(cardElementRef.value)
         elementsReady.value = true
       } catch {
         cardElement = null
         stripeUnavailable.value = true
       }
     }

     function unmountCardElement() {
       mountGeneration++
       cardElement?.unmount()
       cardElement = null
       elementsReady.value = false
     }

     watch(showForm, (show) => {
       if (show) mountCardElement()
       else unmountCardElement()
     })
     ```
     `unmountCardElement()` also bumps the generation counter — this is what makes a toggle-to-`false`
     reliably cancel any in-flight mount from a prior toggle-to-`true`, not just a second toggle-to-`true`
     superseding a first one.
   - **Do not add an `AbortController` or cancel `ensureStripeReady()`/`nextTick()` themselves** — matches
     this project's own established precedent (`skillars-deferred-38`'s Dev Notes explicitly considered and
     rejected `AbortController`-based cancellation for the identical shape of race, in favor of the simpler
     generation-counter guard) and keeps the diff to exactly the two functions and the `watch` block above.
   - **Test coverage.** No frontend test harness exists anywhere in this repo (standing gap, accepted
     identically by `skillars-deferred-17` D6 through `skillars-deferred-38`'s own residual item) — verify by
     running the dev server, toggling "Edit payment method" rapidly (on/off/on in quick succession) with
     the browser DevTools Network tab throttled to simulate the `ensureStripeReady()` await window, and
     confirming exactly one Stripe card element ends up mounted with no console errors, matching this
     project's established frontend-only verification convention (code reading + a manual dev-server pass,
     no automated test).

5. **AC5 — Fix `apply-firewall.sh`'s SSH allowlist rule accumulation on re-run with a different
   `SSH_ALLOWLIST_IP`.**
   - File: `deploy/firewall/apply-firewall.sh:36-51`.
   - Current shape (the "update" branch, when the firewall already exists):
     ```bash
     if hcloud firewall list -o columns=name | grep -qx "${FIREWALL_NAME}"; then
       echo "[firewall] Firewall '${FIREWALL_NAME}' already exists — updating rules..."

       # Delete all existing rules first to prevent duplicates
       hcloud firewall delete-rule "${FIREWALL_NAME}" \
         --direction in --protocol tcp --port 80  --source-ips 0.0.0.0/0 --source-ips ::/0 2>/dev/null || true
       hcloud firewall delete-rule "${FIREWALL_NAME}" \
         --direction in --protocol tcp --port 443 --source-ips 0.0.0.0/0 --source-ips ::/0 2>/dev/null || true
       hcloud firewall delete-rule "${FIREWALL_NAME}" \
         --direction in --protocol tcp --port 22  --source-ips 0.0.0.0/0 2>/dev/null || true
     else
       echo "[firewall] Creating firewall '${FIREWALL_NAME}'..."
       hcloud firewall create --name "${FIREWALL_NAME}"
     fi
     ```
     Port 80/443 delete-rule calls correctly target `0.0.0.0/0`/`::/0` (that never changes run-to-run), so
     they are not part of this bug — only the port-22 delete-rule call is wrong, because the CIDR it targets
     (`${SSH_CIDR}` from a *previous* run) is exactly the one value this script cannot know without tracking
     state between invocations.
   - Replace the fragile "guess and delete the old rule" approach with **delete-and-recreate the whole
     firewall**, which needs no memory of the previous run's CIDR at all — the firewall is a cheap,
     fully-declarative resource (three rules, one attachment) and this script already regenerates every rule
     from scratch immediately after this block regardless of branch taken:
     ```bash
     if hcloud firewall list -o columns=name | grep -qx "${FIREWALL_NAME}"; then
       echo "[firewall] Firewall '${FIREWALL_NAME}' already exists — deleting and recreating to avoid stale rule accumulation..."
       hcloud firewall delete "${FIREWALL_NAME}"
     fi
     echo "[firewall] Creating firewall '${FIREWALL_NAME}'..."
     hcloud firewall create --name "${FIREWALL_NAME}"
     ```
     **Before implementing, confirm `hcloud firewall delete`'s exact behavior for an in-use (attached)
     firewall** — the CLI may require detaching from the server first, or may cascade-detach automatically;
     run `hcloud firewall delete --help` (or check the installed CLI's man page/docs) in the actual
     deployment environment to confirm, since this repo has no live Hetzner API access to test against. If
     `delete` refuses on an attached firewall, detach first via `hcloud firewall remove-from-server` (or
     equivalent for the API version in use) before the delete, then the existing "Attach to server" block
     later in the script (unchanged) reattaches it after the fresh rules are applied.
   - **Why not track the previous CIDR in a state file instead**: that would add a new persistence
     requirement (a file on the operator's local machine, per this script's own header comment "Run from
     your LOCAL machine") for a script that is otherwise fully stateless and idempotent by design — recreate
     is simpler, matches the script's existing "regenerate everything on every run" philosophy, and has no
     failure mode where a stale/corrupted state file causes a wrong deletion.
   - **Test coverage.** No CI or automated test exercises this script (confirmed — it targets a real
     Hetzner Cloud account, matching this ledger's own repeatedly-recorded "no production DB/Cloud API access
     in this environment" limitation for every prior deploy-script fix). Verify with `shellcheck
     deploy/firewall/apply-firewall.sh` (must stay clean, matching this project's other backup/restore
     scripts' established bar) and a careful manual read confirming the script still creates identical final
     rules (TCP 80 all, TCP 443 all, TCP 22 restricted to the new CIDR) on both the create-fresh and
     update-existing paths. A live run against a real Hetzner test account, if available to whoever
     implements this, is the only way to fully close the loop — note in the Dev Agent Record whether one was
     possible.

## Tasks / Subtasks

- [ ] Task 1: Radar composite overflow guard (AC: #1)
  - [ ] 1.1 Replace the three `(int) types.get(X)[1]` casts in `RadarCompositeCalculationService`'s
    `onRadarEntrySubmitted` with `Math.toIntExact(Math.round(types.get(X)[1]))`.
  - [ ] 1.2 Add/update a unit test proving the overflow guard, per AC1's Test Coverage guidance.
  - [ ] 1.3 Run the affected test class; confirm green.
- [ ] Task 2: `DrillVideoRef` persist-not-merge fix (AC: #2)
  - [ ] 2.1 Implement `Persistable<UUID>` on `DrillVideoRef`, per AC2's snippet.
  - [ ] 2.2 Confirm no call site needs updating (`getDrillId()`/`setDrillId(...)` usage unchanged).
  - [ ] 2.3 Add `DrillVideoRefTest` (new-instance/`isNew()`/`markNotNew()` unit coverage).
  - [ ] 2.4 Run the existing drill-clone test/IT coverage plus the new unit test; confirm green.
- [ ] Task 3: Availability-timezone diagnostic log (AC: #3)
  - [ ] 3.1 Add the `validWindowsEvaluated` counter and summary `WARN` to
    `BookingService.isSlotWithinAvailabilityWindow`, per AC3's snippet.
  - [ ] 3.2 Add a `BookingServiceTest` case asserting the new WARN fires via a `ListAppender`, per AC3.
  - [ ] 3.3 Run `mvn -o test -Dtest=BookingServiceTest`; confirm green.
- [ ] Task 4: `PaymentMethodCard.vue` mount/unmount race guard (AC: #4)
  - [ ] 4.1 Add the `mountGeneration` counter and checks to `mountCardElement`/`unmountCardElement`, per
    AC4's snippet.
  - [ ] 4.2 Manually verify via dev server per AC4's Test Coverage guidance (no automated frontend test
    harness exists in this repo).
- [ ] Task 5: `apply-firewall.sh` SSH rule accumulation fix (AC: #5)
  - [ ] 5.1 Confirm `hcloud firewall delete`'s behavior on an attached firewall (docs/CLI help — no live
    API access in this environment).
  - [ ] 5.2 Replace the per-rule `delete-rule` guesses with delete-and-recreate, per AC5's snippet, adding a
    detach step first only if 5.1 shows it's required.
  - [ ] 5.3 Run `shellcheck deploy/firewall/apply-firewall.sh`; confirm clean.
- [ ] Task 6: Ledger hygiene (AC: #6, implicit) — flip the `PICKED UP` tags applied at story creation to
  `CLOSED` once each AC actually lands, one closure note per AC, following the exact convention
  `skillars-deferred-58` AC3 established (only flip tags for ACs that actually shipped if a partial
  implementation lands).

## Dev Notes

- **This story bundles five independent, decision-light findings across five unrelated files/modules — it
  is explicitly not a single coherent feature**, per the user's own instruction to group unrelated small
  fixes to reduce dev-cycle overhead. AC1 (development/radar), AC2 (session/drill-library), AC3
  (booking), AC4 (frontend/payment), AC5 (deploy/ops) share no code paths and can be implemented, tested,
  and reviewed in any order or even split across separate commits within this one story if that's easier to
  verify incrementally.
- **AC5 is the one item in this bundle without CI/automated verification available.** Every other AC has a
  `mvn`-runnable test. Do not let AC5 block on a live Hetzner account if none is available in the
  implementation environment — `shellcheck` + careful manual read is this project's own established bar for
  prior deploy-script-only fixes with the same "no live endpoint to test against" constraint (e.g.
  `skillars-deferred-20`'s four backup-script hardening fixes, `skillars-uat-3`'s D10).
- **AC2's `Persistable<UUID>` implementation is a genuinely new pattern for this codebase** — grep for
  `implements Persistable` before starting to confirm no existing entity already does this differently (if
  one does, mirror its exact shape instead of inventing a second convention).
- **AC3 deliberately does not touch `RescheduleService` or `BookingDuplicationService`**, even though both
  now also call `isSlotWithinAvailabilityWindow` (added by `skillars-deferred-49`) — the new WARN lives
  inside the shared helper itself, so all three callers get the diagnostic for free with a one-file change.
- No new migrations, no new dependencies (Spring Data's `Persistable` interface is already a transitive
  dependency via `spring-boot-starter-data-jpa`), no changes to any `*Resource`/`*Controller` signature, no
  wire-contract changes on any AC.
- Per `docs/validation-strategy.md`, run targeted verification only — do not run a full `mvn verify` unless
  targeted verification proves insufficient.

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java`
  — three narrowing-cast call sites changed (AC1).
- `src/test/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationServiceTest.java`
  — new or extended (confirm which at implementation time) (AC1).
- `src/main/java/com/softropic/skillars/platform/session/repo/DrillVideoRef.java` — implements
  `Persistable<UUID>`, new `@Transient isNew` field, new `getId()`/`markNotNew()` (AC2).
- `src/test/java/com/softropic/skillars/platform/session/repo/DrillVideoRefTest.java` — new file (AC2).
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java` —
  `isSlotWithinAvailabilityWindow` gains a counter and one new summary `WARN` (AC3).
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java` — new test case
  with a `ListAppender` assertion (AC3).
- `src/frontend/src/components/payment/PaymentMethodCard.vue` — `mountGeneration` counter added to
  `mountCardElement`/`unmountCardElement` (AC4).
- `deploy/firewall/apply-firewall.sh` — update-branch rewritten from per-rule delete guesses to
  delete-and-recreate (AC5).
- `_bmad-output/implementation-artifacts/deferred-work.md` — five `PICKED UP`→`CLOSED` tag flips once their
  ACs land (Task 6).
- No frontend build/lint config changes; no new npm dependencies.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, section `## Deferred from: code review
  of skillars-5-4-skills-radar-display-development-correlation (2026-06-19)`, item W8 — AC1 source (first
  half)]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, section `## Deferred from: code review
  of skillars-5-3-skills-radar-assessment-entry-multi-coach-cumulation — Pass 2 (2026-06-19)`, item DEF6 —
  AC1 source (second half, same underlying casts)]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, section `## Deferred from: external code
  review of skillars-4-1-drill-library-foundation (2026-06-17)`, item D2 — AC2 source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, section `## Deferred from: code review
  of skillars-3-3-booking-request-approval-workflow Group B (2026-06-15)` — AC3 source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, section `## Deferred from: code review
  of skillars-deferred-11-stripe-card-collection (2026-08-04)` — AC4 source]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`, section `## Deferred from: code review
  of deploy-1-5-first-time-setup-documentation (2026-06-03)` — AC5 source]
- [Source: `src/main/java/com/softropic/skillars/platform/development/service/RadarCompositeCalculationService.java:34-93`
  — `onRadarEntrySubmitted`, AC1's target, and its existing `catch (Exception e)` block that already
  absorbs the new `ArithmeticException` path]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-26-defensive-guards-input-hardening-and-test-coverage-fixes.md`
  — AC1's `Math.toIntExact` precedent, originally applied to `CoachProfileService.strikeCount`]
- [Source: `src/main/java/com/softropic/skillars/platform/session/repo/DrillVideoRef.java` — AC2's full
  target file, current shape]
- [Source: `src/main/java/com/softropic/skillars/platform/session/service/DrillLibraryService.java:131-145`
  — `cloneDrill`, the only writer of `DrillVideoRef` via `save()`, confirming the entity is always freshly
  constructed with a pre-assigned id at this call site]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-25-jpa-annotation-hygiene-and-stripe-metadata-test-coverage.md`
  — `CoachMediaItemTest`'s same-package-plain-JUnit pattern, AC2's test-shape precedent]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:827-854` —
  `isSlotWithinAvailabilityWindow`, AC3's target, full current shape]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-36-...md` — AC1's `ListAppender`
  log-assertion precedent, originally used to assert on `ApiAdvice`'s structured log arguments]
- [Source: `src/frontend/src/components/payment/PaymentMethodCard.vue:1-189` — AC4's full target file]
- [Source: `src/frontend/src/stores/booking.store.js` (`loadCoachBookingRequests`,
  `skillars-deferred-38` AC1) — AC4's generation-counter supersession-guard precedent]
- [Source: `deploy/firewall/apply-firewall.sh` — AC5's full target file, current shape]
- [Source: `docs/validation-strategy.md` — targeted-test-only validation policy]

## Change Log

| Date | Change |
|---|---|
| 2026-08-24 | Story created via story-creation process, deliberately re-mining `deferred-work.md`'s ENTIRE history (not just the recent tail, per the user's request for a larger bundle) after confirming the recent tail (post-`skillars-deferred-40`) is already thin — nearly every remaining item there is closed, picked up, or explicitly needs a product/design decision. Five items survived live re-verification against the current tree, each independent and low-risk: AC1 (radar composite session-count overflow guard, `RadarCompositeCalculationService`), AC2 (`DrillVideoRef` persist-not-merge via `Persistable<UUID>`), AC3 (availability-timezone diagnostic WARN log, `BookingService`), AC4 (payment-method-card mount/unmount race guard, frontend), AC5 (SSH firewall allowlist rule accumulation fix, deploy script). One candidate item (`SessionPackPurchase.expiresAt` mutability) was found during re-verification to have no safe fix — `updatable = false` would break three legitimate call sites that write it — and was dropped rather than implemented incorrectly. Considered and explicitly not picked up: the `jakarta.persistence.lock.timeout`-has-no-effect-on-Postgres question (needs an architecture decision, not a patch); `DisputeService`'s dormant `FROZEN`-filter gap; the video-bandwidth dedup-rule question; `isSlotWithinAvailabilityWindow`'s midnight-crossing limitation (explicitly out of scope per its own story's Dev Notes); `BookingDuplicationService`'s DST-shift-on-168-hour-offset item (non-mechanical calendar-math fix); roughly a dozen further deploy/infrastructure items, this project's own established lowest-priority category, most needing either live infrastructure access this environment lacks or an operational-risk-acceptance decision beyond a bounded code patch. |
