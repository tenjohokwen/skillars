# Story: GDPR Erasure Alerting, Config-Bounds Default Registry & Stripe Orphan Reconciliation

**Story Key:** `skillars-deferred-133-gdpr-alerting-config-bounds-stripe-reconciliation`
**Epic:** Deferred Work
**Priority:** Medium (one silent-failure alerting gap on a compliance-adjacent path, a config-bounds
drift-detection residual carried across three prior stories, and a live billing/marketplace
data-integrity gap with an already-instrumented-but-ignored detection signal).
**Status:** done
**Created:** 2026-09-24

---

## Context

Sourced from a full front-to-back audit of `deferred-work.md`, run immediately after
`skillars-deferred-132` (PR #220) merged to master at `71caec86625662e027a4ac832658074c11ba1a13`.
Scope requested: **Genuine one-off bugs & gaps** (mandatory), optionally widened with **Lock
contention & deadlock risks, Transactional safety, Config bounds & enforcement, Pre-existing design
issues** — per this project's standing "do not create small stories" convention.

**This story is smaller than skillars-deferred-132 (3 substantive ACs vs. 5/12), by design, not by
oversight.** The 128→132 audit series was a dedicated five-story sweep of exactly the lock-contention/
transactional-safety/config-bounds space; post-132, that space is genuinely thin. A background audit
covering all five requested categories plus a scan for orphaned TODOs/design issues found exactly one
mandatory-category candidate, two optional-category candidates that both needed an owner decision, and
some ledger-hygiene corrections — everything else remaining in `deferred-work.md` is already
`[DECIDED]`/`[DISMISSED]`, blocked on a production deploy that has never happened, or not something a
dev-story can close (native-speaker i18n review, etc.). Padding further would mean re-litigating
settled decisions rather than doing new work — not done here.

All line citations below were re-verified directly against `HEAD = 71caec866...` while drafting this
story (several call sites and ledger line numbers had drifted from their original citations,
corrected inline).

**Owner decisions taken live (AskUserQuestion) before drafting:**

1. `ConfigBounds.BoundedKey`'s missing `default` field (raised at `skillars-deferred-130`, re-declined
   at `skillars-deferred-132` as "narrower to fix only the two review call sites") — **add the field,
   registry-only**: populate `default` on every `HAS_CODE_DEFAULT` key, but leave the deliberate
   call-site literal-retyping convention (`ConfigBounds`'s own class Javadoc, "kept in both places, if
   you change a bound change it in both") untouched. Not the full call-site migration (which would
   remove that convention's own drift-detector value across ~18 call sites — a much larger,
   out-of-theme change), and not a third formal decline.
2. The Stripe → payment reconciliation residual (ledger-flagged since `skillars-deferred-131`,
   re-confirmed still-open by `skillars-deferred-132`'s own AC5 closeout) — **extend the existing
   webhook orphan-detection branches** (`StripeWebhookService.handleSubscriptionUpdated:158-161`,
   `handleSubscriptionDeleted:174-177`, currently `log.warn` + silent no-op) rather than build a new
   scheduled Stripe-API-polling sweep, on the grounds that the detection point already exists.
   **A senior-dev review of the drafted story (`story-review.md`, finding H4) then found this decision
   was taken on an inaccurate premise** — the "orphan" branch is not an orphan detector, it is also the
   normal state immediately after any successful cancellation (`SubscriptionService.
   handleSubscriptionDeleted` deliberately nulls `stripeSubscriptionId` on both the coach and player
   side, `:690`/`:703`), and there is no generic way to clear an `AdminAlert` once raised
   (`AdminAlertRepository.java:64-75`) — so the fix as originally specified would have generated
   permanently-unresolvable false-positive alerts on every routine coach cancellation. **Re-confirmed
   live (AskUserQuestion) after the corrected picture was presented:** keep the webhook-extension
   mechanism (do not fall back to a scheduled sweep), but **restrict alerting to events carrying a
   live/non-terminal Stripe status** (`active`/`trialing`/`past_due`) — this directly targets the
   ledger's actual concern ("a coach with a live, billing Stripe subscription and no local record")
   without touching the normal cancellation path at all. See Fix 3's Context for the full corrected
   design, including the race-window and false-positive mitigations this second pass also required.
3. Story-scope check: with the above two plus the one mandatory genuine-bug fix, this is a 3-AC story.
   **Ship it lean** — don't reach into `[DECIDED]`/deploy-blocked items or invent unrelated tasks just
   to hit a fix count closer to `skillars-deferred-132`'s.

**Pre-implementation fact-check on Decision 2 (done before asking, not guessed):**
`SubscriptionService.subscribeCoach`'s `findOrCreateCoachSubscription` (`SubscriptionService.java:
750-757`) always eagerly commits a placeholder `payment.coach_subscriptions` row (`coachId` set,
`tier`/`status`/`stripeSubscriptionId` all null) in its own auto-transaction — **before** the Stripe
call (`:148`) ever runs, since neither `subscribeCoach` (`:111`) nor `findOrCreateCoachSubscription`
itself carry `@Transactional`. This means the ledger's literal framing — "a coach with a live Stripe
subscription and **no local row at all**" — is not quite reachable through `subscribeCoach`: what
actually happens is `persistCoachSubscription`'s `@Transactional` method (`:158-188`) rolls back
*after* Stripe already succeeded (any exception from `syncMarketplaceTier` other than the one caught
`PessimisticLockingFailureException`, or a failure in `paymentCoachSubscriptionRepository.save(sub)`
itself), leaving that earlier placeholder row stale — `stripeSubscriptionId` still null — while Stripe
has an active, already-charged subscription. Functionally the same operational problem (the local
system has no record linking the coach to their live subscription), but the precise mechanism matters
for the fix's shape — see Fix 3's Context below. `SubscriptionService.java:663`'s own comment ("do NOT
sync tier from Stripe (no priceId→tier map)") is a pre-existing, deliberate architectural constraint
that also directly shapes Fix 3 — see its own "Weigh, don't ignore" note.

---

## AC1: Genuine One-off Bug — `GdprErasureService.markFailed` has no alert on unclassified failure

### Fix 1 — Alert on the failure paths `raiseErasureAlert` doesn't already cover

**Context:** `markFailed` (`GdprErasureService.java:391-398`) is the terminal catch-all every erasure
failure routes through — `GdprEventListener.onErasureRequested`'s `catch (Exception e)`
(`GdprEventListener.java:36-40`) calls it for **any** exception `erase()` throws, then only
`log.error`s. First flagged as `[DECIDED: accepted risk — skillars-deferred-127]`
(`deferred-work.md:2985-3001`, re-verified at those exact lines) when AC1 of that story gave
`deletePlayerDevelopmentData` a `player_profiles` lock it didn't take before — accepted then as "a
separate, larger concern for a future story." That trigger has since widened further:
`skillars-deferred-132` AC1 Fix 4 added a **new**, unalerted failure path — `erase()`'s own
`assertConnectionPoolNotSaturated(requestId, "erase")` pre-check (`:181-182`) throws
`PessimisticLockingFailureException` directly, **before** `eraseTransactional` (and therefore before
any of the four `raiseErasureAlert` call sites inside it) ever runs.

**Four of the known failure reasons are already alerted, and must stay exactly as-is:**
`eraseParentChildren`'s deadline-exceeded throw (`:463`, reason `DEADLINE_EXCEEDED`) and its two
per-child catches (`:494`, `:516`, reasons `CHILD_VANISHED`/`CHILD_CONTENDED`/
`CHILD_DELETE_LOCK_TIMEOUT`), plus the PLAYER-branch's mirrored catches (`:292`, `:316`), all already
call `raiseErasureAlert(requestId, reason)` (`:560-575`) — deduplicated per `(requestId, reason)` via
`AdminAlertRepository.findFirstByReferenceIdAndTypeAndReasonAndStatus` (`:58-59`). The genuinely
unalerted paths are the ones `raiseErasureAlert` never sees at all: the top-level pool-saturation
check above, plus any other truly unexpected exception (a `RuntimeException("GdprRequest not found")`/
`"User not found"` at `:195`/`:200`, or anything not one of the two typed catches).

**Corrected during story review (senior-dev pass, `story-review.md`) — the original code sketch had
two live bugs, both source-verified against `HEAD`:**

1. **The alert must not live inside `gdprRequestRepository.findById(requestId).ifPresent(...)`.**
   `eraseTransactional`'s own `orElseThrow(() -> new RuntimeException("GdprRequest not found: " +
   requestId))` (`:194-195`) fires exactly when that same finder (`markFailed` calls it with the same
   `requestId`, `:393`) returns empty — so an `ifPresent`-scoped alert never runs for the one path this
   fix was explicitly written to cover. The status-update half (`setStatus("FAILED")`) is correctly
   `ifPresent`-guarded (can't set status on a nonexistent row), but the alert itself needs a requestId
   **string**, not a loaded entity — it must run unconditionally.
2. **The alert must not call the existing `raiseErasureAlert` as-is.** `raiseErasureAlert` runs on
   `requiresNewTemplate` (`PROPAGATION_REQUIRES_NEW`, `:560-561`) — calling it from inside `markFailed`
   (itself already `@Transactional(REQUIRES_NEW)`) suspends `markFailed`'s transaction and opens a
   **second**, concurrently-held connection from the same pool. The headline trigger for this whole fix
   is `erase()`'s own `assertConnectionPoolNotSaturated` pre-check (`:181-182`) tripping — i.e. the pool
   was JUST reported saturated. `markFailed` (`REQUIRES_NEW`, connection #1) calling `raiseErasureAlert`
   (`REQUIRES_NEW`, connection #2, held concurrently by the same thread) doubles the connections the
   least-connection-rich path of this whole flow needs, right when it can least afford it.

**Fix:** extract `raiseErasureAlert`'s dedup-check-and-insert body into a new private,
**non-transactional** helper (e.g. `insertErasureAlertIfAbsent(UUID requestId, String reason)`) that
both the existing `raiseErasureAlert` (unchanged — still wraps the helper in
`requiresNewTemplate.executeWithoutResult(...)`, since its own callers inside `eraseTransactional` genuinely
need isolation from a transaction about to roll back) and the new `markFailed` code call directly
(`markFailed` calls the helper inline, inside its own already-`REQUIRES_NEW` transaction — one
connection, not two). Guard on **any** `GDPR_ERASURE_DEADLINE` alert already open for this request
(regardless of reason) via the existing
`adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(requestId.toString(),
AdminAlertType.GDPR_ERASURE_DEADLINE, AdminAlertStatus.OPEN)` (`AdminAlertRepository.java:48-49` —
already exists, no new repository method needed) — **the real reason for this reason-blind check is
the DB, not redundant alerting**: `admin_alerts_unique_open_per_ref` (`V138__baseline_schema.sql:3166`)
is a unique index on `(reference_id, type)` — **`reason` is not part of it** — so a second `OPEN` row
for the same `(requestId, GDPR_ERASURE_DEADLINE)` throws `DataIntegrityViolationException` at flush
regardless of whether the `reason` differs. Add a new reason constant (alongside
`CHILD_CONTENDED`/`CHILD_DELETE_LOCK_TIMEOUT`/`CHILD_VANISHED` at `:147-151`):

```java
private void insertErasureAlertIfAbsent(UUID requestId, String reason) {
    boolean alreadyOpen = adminAlertRepository.findFirstByReferenceIdAndTypeAndStatus(
            requestId.toString(), AdminAlertType.GDPR_ERASURE_DEADLINE, AdminAlertStatus.OPEN)
        .isPresent();
    if (alreadyOpen) {
        return;
    }
    try {
        AdminAlert alert = new AdminAlert();
        alert.setType(AdminAlertType.GDPR_ERASURE_DEADLINE);
        alert.setReferenceId(requestId.toString());
        alert.setReferenceType(AdminAlertReferenceType.GDPR_REQUEST);
        alert.setReason(reason);
        adminAlertRepository.save(alert);
    } catch (DataIntegrityViolationException e) {
        // Concurrent/overlapping insert won the (referenceId, type) OPEN slot — see the pre-existing
        // multi-reason bug fixed alongside this (below): without this catch, a PARENT erasure that
        // raises two different reasons for two different children hits this exact race non-concurrently.
        log.debug("[GDPR_ERASURE_ALERT_DUPLICATE_SUPPRESSED] requestId={} reason={}", requestId, reason);
    }
}

private void raiseErasureAlert(UUID requestId, String reason) {
    requiresNewTemplate.executeWithoutResult(status -> insertErasureAlertIfAbsent(requestId, reason));
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void markFailed(UUID requestId) {
    gdprRequestRepository.findById(requestId).ifPresent(r -> {
        r.setStatus("FAILED");
        gdprRequestRepository.save(r);
    });
    log.error("[GDPR_ERASURE_MARKED_FAILED] requestId={}", requestId);
    insertErasureAlertIfAbsent(requestId, UNCLASSIFIED_FAILURE);
}
```

**A third, pre-existing live bug this analysis surfaced (fold into this fix, not a separate ledger
item — the code is already open for exactly this reason):** `raiseErasureAlert`'s per-`(requestId,
reason)` dedup (`:562-564`, added by the 2026-09-23 code review) is incompatible with the
`(reference_id, type)`-only unique index above. A PARENT erasure that raises `CHILD_VANISHED` for one
child (`:494`) and `CHILD_CONTENDED`/`CHILD_DELETE_LOCK_TIMEOUT` for another (`:516`) — both real,
independently reachable per-child catches inside the same `eraseParentChildren` loop — passes the
reason-aware dedup check for the second alert (different reason) and then violates the DB's
reason-blind unique index. Today that `DataIntegrityViolationException` is uncaught, propagates out of
`eraseParentChildren`, and converts a designed skip-and-continue into a full rollback + `FAILED` —
losing the very alert it was trying to raise. The `catch (DataIntegrityViolationException e)` added to
`insertErasureAlertIfAbsent` above (mirroring `AdminAlertEventListener.insertAlert:123-126`'s
established pattern for exactly this race) fixes both this fix's own new call site AND this
pre-existing one, since `raiseErasureAlert` now routes through the same helper.

Reuses the existing `AdminAlertType.GDPR_ERASURE_DEADLINE` / `AdminAlertReferenceType.GDPR_REQUEST`
pair — no migration needed (both already exist as of `V152__admin_alerts_gdpr_erasure_deadline_type
.sql`). **Correction to the original rationale:** this is not "reusing an existing type" per a stated
codebase preference — `V152`'s own migration comment argues the *opposite* ("reusing an existing value
... would misrepresent this alert's real subject"). The honest reason to reuse here is narrower:
`UNCLASSIFIED_FAILURE` is deadline-*budget*-adjacent (a connection-pool pre-check that exists to keep
erasure within its lock budget, and a missing-row case reached only via the same erasure flow), a new
type is a full migration for one reason value, and the `reason`-prefixed rendering in
`AdminQueueService.buildSummary` (`:177-180`) already disambiguates it in the queue UI. Note explicitly
in the AC4 closeout: `AdminQueueSummaryDto.gdprErasureDeadlines` and the `/queue?type=
GDPR_ERASURE_DEADLINE` filter now also count non-deadline failures (pool saturation, missing rows)
under that name — an accepted, disclosed semantic widening, not an oversight.

**No auto-retry, by design — narrower than the ledger's original framing:** the `[DECIDED]` bullet
above bundled "AdminAlert/auto-retry" as one ask. Auto-retry is a materially bigger change (needs a
re-drive scheduler, and `GdprRequestService.requestErasure`'s own "only blocks on PENDING/PROCESSING"
guard — cited in the same ledger bullet — already lets a user manually re-submit a `FAILED` request
today). This fix closes the alerting half only; leave auto-retry as still explicitly open in the AC4
ledger closeout, not silently implied as resolved.

**`AdminQueueService.buildSummary`'s `GDPR_ERASURE_DEADLINE` case (`AdminQueueService.java:177-180`)
needs no code change** — it already prefixes generically with `reason + ": "` for any reason string,
no switch/case over specific values. Its own comment (`:173-176`, "reason distinguishes ... DEADLINE_
EXCEEDED / CHILD_VANISHED / CHILD_CONTENDED / CHILD_DELETE_LOCK_TIMEOUT") goes stale once a 5th reason
exists — update it to mention `UNCLASSIFIED_FAILURE` alongside the other four. While touching
`AdminAlert`-adjacent comments: `AdminAlert.reason`'s own Javadoc (`AdminAlert.java:57`, "Populated
only by the messaging moderation path; null elsewhere") is already stale as of `skillars-deferred-128`
— fold a one-line correction into this same task rather than leaving it for someone else to notice.

**Test:** `GdprErasureServiceTest` unit test(s) covering `markFailed` directly (none exist today for
`markFailed` itself — the file has four `@Test` methods total: a bounds-literal pin at `:138` plus
three `erase_parentUser_*_readsLockTimeoutConfigExactlyOnce` tests at `:174`/`:185`/`:197` added by
`skillars-deferred-132` AC2 Fix 9, none of which touch `markFailed`) — mock a `GdprRequest` present
with no prior alert, verify `insertErasureAlertIfAbsent`'s effect (an `AdminAlert` saved with
`type=GDPR_ERASURE_DEADLINE`, `reason=UNCLASSIFIED_FAILURE`); a second case with a prior
`DEADLINE_EXCEEDED` alert already `OPEN` for the same `requestId`, verify **no** second alert is
raised; a third case where `gdprRequestRepository.findById` returns empty (the `GdprRequest not
found` path) — verify the alert still fires even though there is no row to set `FAILED` on. The file
constructs `GdprErasureService` via a 27-argument positional constructor (`:118-126`) and wires `self`
by reflection (`:134`) — no new constructor dependency needed here since `insertErasureAlertIfAbsent`
reuses fields the class already has.

**Do not extend `GdprErasureIT.erase_connectionPoolSaturated_failsFastInsteadOfBlockingForTheFullConnectionTimeout`
(`:1063-1102`) as originally proposed — it cannot exercise this path.** That IT calls
`gdprErasureService.erase(...)` **directly** (`:1081`), bypassing `GdprEventListener` entirely, so
`markFailed` is never invoked; it also passes a random unseeded `UUID` as the requestId, and holds
every pool connection for the test's own duration (so even routed through the listener, the new alert
write would itself block on connection acquisition and blow the test's own `< 5s` assertion). Write a
**new** `GdprErasureIT` case instead: seed a real `GdprRequest` row, publish the erasure-requested
event through `GdprEventListener` (not calling `erase()` directly) so `markFailed` is reached, saturate
the pool leaving at least two connections free, and assert an `OPEN` `AdminAlert` with
`reason=UNCLASSIFIED_FAILURE` is left behind.

---

## AC2: Config Bounds & Enforcement — `ConfigBounds.BoundedKey` gains a registry-only `default` field

### Fix 2 — Add `default` to the `BoundedKey` record, populated for all 18 `HAS_CODE_DEFAULT` keys

**Context:** `ConfigBounds.BoundedKey` (`ConfigBounds.java:60-61`) is `record BoundedKey(String key,
long min, long max, boolean failFast, String note)` — no `default`. Every `HAS_CODE_DEFAULT` key's
actual code default (`:330-348`, 18 keys) lives **only** at its call site, re-typed as a literal — so
an operator (or a future story) raising `ConfigBounds`'s own `max` for a key has no registry-level
record of what the call site falls back to. Raised at `skillars-deferred-130`, re-declined at
`skillars-deferred-132` ("narrower to fix only the two review call sites than widen `BoundedKey`
itself").

**Correction to the motivation (senior-dev review caught an inverted claim):** the original framing
said the registry "only" records what a present-but-out-of-range value clamps to. That is backwards.
`ConfigService.getBoundedLong(key, defaultValue, min, max)` — the 4-arg overload 17 of the 18 keys use
— does **not** clamp: out-of-range falls back to `defaultValue` (`ConfigService.java:110-118`).
Clamping is the **3-arg** overload's behavior (`:128-141`) — the one key that does *not* fit the
4-arg pattern (see below). So for 17 of the 18 keys the registry's `min`/`max` today tell you nothing
about what actually happens on an out-of-range value — the (unregistered) default does. This makes
the fix **more** valuable than originally argued, not less; the field's own Javadoc should carry the
corrected framing so this isn't miscopied forward.

**Fix:** add `long default_` (or `defaultValue` — `default` is a reserved word, confirm the exact
field name at implementation time; the record's accessor becomes `.defaultValue()` either way) to the
`BoundedKey` record, and populate it for every one of the 18 `HAS_CODE_DEFAULT` keys with the literal
each call site actually passes today (verified against current `HEAD`, not the ledger's own claims):

| Key | Default | Call site(s) |
|---|---|---|
| `PACK_PAUSE_MAX_DAYS` | 90 | `PackSessionService.java:173` (via `DEFAULT_PACK_PAUSE_MAX_DAYS` constant, `:50`) |
| `DISPUTES_SUBMISSION_WINDOW_DAYS` | 14 | `DisputeService.java:110` |
| `VIDEO_LIFECYCLE_BLOCKED_TO_ARCHIVED_DAYS` | 30 | `VideoLifecycleScheduler.java:84` |
| `VIDEO_LIFECYCLE_ARCHIVED_TO_DELETED_DAYS` | 90 | `VideoLifecycleScheduler.java:89` |
| `VIDEO_LIFECYCLE_BATCH_SIZE` | 100 | `VideoLifecycleScheduler.java:90` **and** `VideoSubscriptionLifecycleListener.java:125` (two call sites, same literal — confirm both stay in sync) |
| `MODERATION_SLA_BATCH_SIZE` | 50 | `ModerationSlaMonitorService.java:107` |
| `VIDEO_PLAYBACK_SIGNED_URL_TTL_MINUTES` | 120 | `PlaybackService.java:111` |
| `VIDEO_ACCESS_COACH_WINDOW_DAYS` | 90 | `VideoAccessGuard.java:98` |
| `VIDEO_DELETION_MAX_ATTEMPTS` | 5 | `VideoDeletionOutboxProcessor.java:372` |
| `RADAR_COMPOSITE_DLQ_MAX_ATTEMPTS` | 5 | `RadarCompositeDlqProcessor.java:274` |
| `GDPR_EXPORT_URL_EXPIRY_HOURS` | 48 | `GdprExportService.java:87` |
| `MESSAGE_RETENTION_MONTHS` | 24 | `MessageRetentionScheduler.java:52` |
| `REVIEWS_SUBMISSION_WINDOW_DAYS` | 14 | `ReviewSubmissionService.java:201` |
| `REVIEWS_AUTO_HOLD_FLAG_THRESHOLD` | 3 | `ReviewFlagService.java:157` |
| `TIMELINE_COACH_ACCESS_EXPIRY_DAYS` | 90 | `TimelineQueryService.java:33-36` — **see the correction below, this one is not a plain 4-arg default** |
| `RATE_LIMIT_BUCKET_TTL_HOURS` | 24 | `RateLimitingService.java:123` |
| `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS` | 5 | `RadarCompositeCalculationService.java:212` |
| `GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS` | 5 | `GdprErasureService.java:269` and `:435` (two call sites, same literal) |

**One key doesn't fit the pattern cleanly — corrected during story drafting, do not gloss over it:**
`TIMELINE_COACH_ACCESS_EXPIRY_DAYS`'s call site (`TimelineQueryService.java:33`) uses the **3-arg**
`getBoundedLong(key, min, max)` overload (`ConfigService.java:128`), not the 4-arg
`(key, default, min, max)` one every other `HAS_CODE_DEFAULT` key uses — its `90L` fallback
(`TimelineQueryService.java:36`) is a manual `catch (Exception e)` block around the call, triggered
only on a **missing/throwing** key, not embedded in the bounds call itself. This is the second shape
`HAS_CODE_DEFAULT`'s own Javadoc already documents ("passes a code default ... or catches the missing-
key IllegalStateException", `ConfigBounds.java:321-328`) — populate its registry `default` as `90`
too (the value is genuine and correct), but note in the field's own Javadoc that for this one key the
value is a catch-block fallback semantically distinct from the other 17 keys' embedded 4-arg default,
so a future reader doesn't assume every `HAS_CODE_DEFAULT` key's registry default is reachable the same
way at its call site.

**Registry-only, per the owner decision — do not touch any call site's own literal.** This closes the
"is there a single source of truth for what each key's default *should* be" gap without reversing this
codebase's established call-site-literal-as-drift-detector convention (`ConfigBounds.java:33-35`'s own
class Javadoc). A future story that wants to go further (call sites reading `default`/`min`/`max` off
`BoundedKey` itself) is a distinct, larger, separately-scoped change — do not fold it in here.

**Scope correction: this touches 32 constructor call sites, not 18.** `ConfigBounds.java` has 32 total
`new BoundedKey(...)` calls (verified: no `new BoundedKey(` exists anywhere else in `src/main` or
`src/test`) — the 18 named `HAS_CODE_DEFAULT` constants plus 14 other named constants plus 18 more
generated in the static block (6 tier segments × 2 + 3 type segments × 2, `:384-398`). All 32 need the
new record component added to their constructor call (only the 18 `HAS_CODE_DEFAULT` ones get a real
value — the other 14 named + 18 generated entries pass the same not-applicable sentinel described
below). Blast radius is contained to this one file either way. Also note: `VIDEO_LIFECYCLE_BATCH_SIZE`,
`MODERATION_SLA_BATCH_SIZE`, `MESSAGE_RETENTION_MONTHS`, `REVIEWS_SUBMISSION_WINDOW_DAYS` and
`REVIEWS_AUTO_HOLD_FLAG_THRESHOLD` are read via `getBoundedInt`, not `getBoundedLong` — the new `long`
field stores their `int` defaults fine, just widened, note it in the Javadoc.

**Drift detection — make an explicit, documented choice, don't leave it implicit.** `ConfigBounds`'s
class Javadoc (`:33-35`) justifies duplicating `min`/`max` literals because a `verify(...)` in each
call site's unit test pins the exact numbers, so drift between the registry and the code is caught.
Only 4 of the 18 keys reference their constant via `.key()` at the call site
(`ReviewSubmissionService:201`, `ReviewFlagService:157`, `RadarCompositeCalculationService:212`,
`GdprErasureService:269`/`:435`), and only a subset of the 18 even have a `verify(...)` pinning the
default literal — so a lone `default() >= min() && default() <= max()` range-check (see Test below)
would silently pass even if the registry's default drifts away from what the call site actually does
(e.g. someone changes `PlaybackService.java:111`'s `120L` and forgets the registry). Given the owner
decision already excludes the larger call-site-migration fix that would close this properly, state
plainly in the new field's Javadoc that **the registry default is documentation, not a verified
contract** — the call-site literal remains the sole source of truth, same as `min`/`max` already are.
Cheap, honest, and consistent with the existing convention rather than silently implying a guarantee
this fix doesn't provide.

**Test:** extend `ConfigBoundsEnumCoverageTest.boundsAreInternallyConsistent`
(`ConfigBoundsEnumCoverageTest.java:102-109`) to also assert, for every key in `ConfigBounds.ALL`
that is also in `HAS_CODE_DEFAULT`, that `default() >= min() && default() <= max()` (a default outside
its own key's bounds would be a self-contradictory registry entry, and this is the one drift class a
range-check *can* catch — see above for what it can't). For keys **not** in `HAS_CODE_DEFAULT` (the
`failFast`, no-code-default 1-arg call sites, and the other 14 named + 18 generated non-`HAS_CODE_DEFAULT`
entries from the 32-site count above), use a plain `0L` sentinel — none of the 18 real `HAS_CODE_DEFAULT`
defaults is `0`, so this is unambiguous today; note it as an assumption that would need revisiting if a
future `0`-default key is ever added to `HAS_CODE_DEFAULT`. No production call-site behavior changes —
this is a registry-only addition, so no call-site unit test needs updating.

---

## AC3: Transactional Safety / Data Integrity — Stripe → payment orphan detection for live subscriptions only

### Fix 3 — `StripeWebhookService`'s orphan-detection branch alerts on live, unlinked subscriptions instead of silently dropping every unmatched event

**Context:** `StripeWebhookService.handleSubscriptionUpdated` (`:151-165`) and
`handleSubscriptionDeleted` (`:167-181`) each already check whether the incoming Stripe subscription
event's `stripeSubscriptionId` matches a local `payment.coach_subscriptions` or
`payment.player_subscriptions` row (`:156-157`, `:172-173`); if neither matches, both just
`log.warn("[STRIPE_WEBHOOK_ORPHANED_SUBSCRIPTION stripeSubId={}]", stripeSubId)` and `return`
(`:158-161`, `:174-177`). This is the detection point for the ledger's still-open Stripe → payment
residual (`deferred-work.md:3356-3379`, re-confirmed still-open by `skillars-deferred-132`'s own AC5
closeout at `:3688-3697`).

**This "orphan" branch is not actually an orphan detector — it is also the normal state after every
successful cancellation, and this reshapes the whole fix (senior-dev review finding H4, confirmed
against source; see the Context section above for the resulting owner decision).**
`SubscriptionService.handleSubscriptionDeleted` deliberately **nulls** `stripeSubscriptionId` on
cancellation, both sides (`:690` coach, `:703` player). So after any normal cancellation, every
subsequent Stripe event carrying that `sub_...` id — including a delayed `.updated` that arrives after
an earlier `.deleted` (Stripe does not guarantee delivery order, and `handleEventAtomically`'s
`insertIfAbsent` idempotency, `:83-87`, dedupes by **event** id, not subscription id, so this is a
distinct event, not a suppressed duplicate) — lands in this branch. There is no generic way to clear
an `AdminAlert` once raised (the only four `RESOLVED` writers in the codebase are each bound to a
specific domain action, `AdminAlertRepository.java:64-75`), so alerting on the branch as-is would
produce permanently-open false positives on every routine cancellation, which (via
`admin_alerts_unique_open_per_ref`, see Fix 1) would also block any *genuine* later alert for that same
reference. **Fix scope, corrected:** alert only when the incoming event reports a **live/non-terminal**
Stripe subscription status — `active`, `trialing`, or `past_due` — never on `.deleted` and never on a
`.updated` carrying a terminal status (`canceled`, `incomplete_expired`, or anything else outside the
allowlist). This directly targets the ledger's actual concern — a coach being billed by a live Stripe
subscription with no local record — without touching the cancellation path at all.

**Correction during drafting (unchanged from the original draft) — full tier backfill is not
achievable, and this fix does not attempt it.** `SubscriptionService.java:663`'s own comment states
this codebase deliberately does **not** maintain a Stripe-priceId → tier reverse map —
`handleSubscriptionUpdated` (the `SubscriptionService` one, `:662-674`, distinct from
`StripeWebhookService`'s dispatcher of the same name) only ever syncs `status`/`period`/
`cancelAtPeriodEnd` from Stripe, never `tier`. This fix is **alert-only, not auto-heal**: it identifies
which coach the orphaned subscription likely belongs to and raises a targeted alert for a human to
reconcile, rather than guessing a tier.

**A second false-positive source this review pass found and closes: the `subscribeCoach` provisioning
race.** `subscribeCoach` (`SubscriptionService.java:111`) is not itself `@Transactional`. It calls
Stripe at `:148`, then commits the local link in a **separate, later** transaction
(`persistCoachSubscription:158-188`, setting `stripeSubscriptionId` at `:162`/`save` at `:167`). A
`customer.subscription.updated` firing when Stripe transitions the new subscription to `active` — the
same status this fix now alerts on — can be delivered inside that window, finding no local match for a
subscription that is, in fact, healthy and settling normally. Mitigate with a short grace check rather
than alerting immediately: `PaymentCoachSubscriptionRepository.findByCoachId(UUID)` already exists
(`:13`) and `PaymentCoachSubscription` already has a `createdAt`/`updatedAt` pair
(`PaymentCoachSubscription.java:62-66`, set by `@PrePersist`/`@PreUpdate`) — after resolving the
coach's identity (below), look up their row by `coachId` (not `stripeSubscriptionId`); if a row exists
and was touched within the last 10 minutes (`Duration.ofMinutes(10)` — deliberately generous, since a
genuine happy-path settle completes in well under a second and this only needs to rule out the
provisioning race, not compress it), treat it as still-settling and skip alerting for this event — a
subsequent event will re-check. Only alert when no row exists for the coach at all, or the existing row
is stale (untouched for 10+ minutes) and still doesn't carry a matching `stripeSubscriptionId`.

**Fix:**
1. Add `List<StripeCustomer> findByStripeCustomerId(String stripeCustomerId)` to
   `StripeCustomerRepository` (currently `extends JpaRepository<StripeCustomer, Long>` with no
   finder methods at all, `StripeCustomerRepository.java:5`) — deliberately `List`, not `Optional`:
   `payment.stripe_customers` (`V138__baseline_schema.sql:1919-1926`) has `PRIMARY KEY (parent_id)` but
   **no unique constraint or index on `stripe_customer_id`**, so an `Optional`/single-result finder
   risks `IncorrectResultSizeDataAccessException` if two `parent_id` rows ever share a Stripe customer
   id — which, inside this webhook's own `@Transactional` (below), would roll back the idempotency
   record and put Stripe into a retry loop. Take the first result if present. `StripeCustomer.parentId`
   (`StripeCustomer.java:21-23`, the `@Id`) is actually the coach/parent **user id**, not literally a
   parent-only field (per `subscribeCoach`'s own `stripeCustomerRepository.findById(coachUserId)` call,
   `SubscriptionService.java:130-133`).
2. In `handleSubscriptionUpdated` only (not `handleSubscriptionDeleted` — see above), when the existing
   local-match check finds nothing AND `sub.getStatus()` is one of `active`/`trialing`/`past_due`:
   resolve `sub.getCustomer()` (the Stripe `Subscription` object already deserialized,
   `deserializeSubscription`, `:204+`) through the new finder → `StripeCustomer.parentId` (userId) →
   `CoachProfileRepository.findByUserId(userId)` (`:24`, already exists). If no `CoachProfile` resolves
   (a player, or a genuinely unrecognized customer), fall through to the existing `log.warn` + `return`
   with **no** alert — this fix is scoped to coaches only, matching `syncMarketplaceTier`'s own scope;
   there is no "Stripe → payment" ledger item for players, and this is a final decision, not a residual
   left open. If a `CoachProfile` resolves, apply the grace check above; if it doesn't skip, publish a
   new `CoachSubscriptionOrphanedEvent(coachProfile.getId(), stripeSubId)` via the already-injected
   `ApplicationEventPublisher` (`eventPublisher`, `:43`) — do **not** write the `AdminAlert` directly
   from `StripeWebhookService` (see next point) — then wrap steps 2's entire resolve-and-publish block
   in `catch (Exception e) { log.warn(...); }` around the new logic, falling through to the pre-existing
   `log.warn` + `return` on any failure. This is deliberate double protection, not redundant: it keeps
   `handleEventAtomically`'s `@Transactional` (`:81-82`) commit-and-200-to-Stripe behavior unconditional
   on the alerting path succeeding, given a deterministic failure here (an enum/CHECK-constraint
   mismatch, an unexpected exception from either repository) would otherwise roll back the
   `insertIfAbsent` idempotency row and put Stripe into an indefinite retry loop on every delivery of
   that event.
3. Add the listener in `platform.admin`, not a direct write from `platform.payment` — reuse the
   established seam. `AdminAlertRepository` today is referenced only from `platform.admin`; this fix
   should not be the first thing outside it to write `admin_alerts` when an existing pattern already
   does exactly what's needed. Add a `CoachSubscriptionOrphanedEvent` (mirrors the shape of
   `StrikeThresholdReachedEvent`/`DisputeRaisedEvent`) and a handler in `AdminAlertEventListener`:
   ```java
   @EventListener
   @Transactional(propagation = Propagation.REQUIRES_NEW)
   public void onCoachSubscriptionOrphaned(CoachSubscriptionOrphanedEvent event) {
       insertAlert(AdminAlertType.SUBSCRIPTION_ORPHANED,
           event.getCoachProfileId().toString(),
           AdminAlertReferenceType.COACH);
   }
   ```
   This reuses `insertAlert`'s existing `(referenceId, type, OPEN)` dedup check **and** its
   `DataIntegrityViolationException` catch (`:104-127`) for free — no hand-written dedup logic needed
   (see point 5, below, on what this dedup granularity means). `REQUIRES_NEW` isolates the write's own
   connection from the webhook's transaction (belt-and-suspenders with the `catch` in point 2 above,
   not a substitute for it — either alone is sufficient to prevent the retry-loop risk; both together
   is cheap and this path is not connection-pool-constrained the way `GdprErasureService`'s is, so the
   two-connections-per-call cost here is acceptable, unlike Fix 1's H2).
4. New `AdminAlertType.SUBSCRIPTION_ORPHANED` (21 chars, fits the `type varchar(25)` column,
   `AdminAlert.java:33-34`). Needs a new migration widening only the `type` CHECK constraint, mirroring
   `V152__admin_alerts_gdpr_erasure_deadline_type.sql`'s exact pattern (`SET LOCAL lock_timeout`,
   drop/re-add `admin_alerts_type_check` with the new value appended) — next migration number is `V153`
   (confirm nothing else lands first). **No** `reference_type` migration needed (`COACH` already exists
   in that CHECK constraint). Per `V152`'s own precedent and `skillars-deferred-117`'s re-confirmed
   owner decision (no production deploy has happened), the widening and its first write can ship in
   this same story.
5. `AdminQueueService.buildSummary` (`:125-181`) needs a new `case SUBSCRIPTION_ORPHANED` branch —
   render the Stripe subscription id (from the alert's context — confirm whether `AdminAlert` needs a
   free-text field for it or the coach id alone is sufficient given the per-coach dedup below) and the
   coach id, mirroring the existing `GDPR_ERASURE_DEADLINE` case's shape (`:177-180`). **Also add a new
   `AdminQueueSummaryDto` record component and populate it in `getSummary`** (`:220-242`) — this is,
   verbatim, the defect `skillars-deferred-128` fixed for `GDPR_ERASURE_DEADLINE`: without its own
   bucket, `total` (summed over *all* enum-mapped types) silently exceeds the sum of the reported
   buckets. Note this changes the `/queue/summary` response shape (no frontend directory in this repo,
   so no frontend change needed, but any external consumer sees a new field).
6. **Dedup granularity, stated explicitly:** `insertAlert`'s dedup is `(referenceId, type, OPEN)` —
   with `referenceId = coachProfile.getId().toString()`, that means at most one `OPEN`
   `SUBSCRIPTION_ORPHANED` alert per **coach** at a time, not per orphaned Stripe subscription id. This
   mirrors `STRIKE_THRESHOLD`'s own existing per-coach (not per-booking) granularity — an accepted,
   precedented choice, not a gap: two genuinely distinct orphaned subscriptions for the same coach are
   rare (a coach has one active marketplace tier subscription at a time in this domain) and would
   collapse into one alert, which a human reconciling the first one will surface regardless.
7. **Explicitly out of scope, recorded as a residual in AC4, not silently dropped:**
   `handleInvoicePaymentFailed` (`StripeWebhookService.java:183-202`) has the identical no-op shape (no
   orphan check at all — it no-ops silently via `.ifPresent` inside `SubscriptionService.
   handleInvoicePaymentFailed`, `:711-730`) and is untouched by this fix. A payment failing against an
   orphaned subscription is arguably the most actionable signal of all, but this story's scope is the
   two `customer.subscription.*` branches only.

**This narrows the ledger's original ask, and that should be stated plainly, not implied as fully
closed** (matching the discipline Fix 1 already applies to its own auto-retry residual):
`handleEventAtomically` dispatches only four event types (`:88-98`) and `customer.subscription.created`
is not one of them, so the moment a coach's subscription is actually created triggers nothing — the
earliest signal is the next live-status `.updated` (often the `incomplete` → `active` transition
immediately after, so in practice fast, but not guaranteed). Detection latency is therefore "whenever
Stripe next sends a handled live-status event for this subscription," not "one sweep interval." Record
this, and the `handleInvoicePaymentFailed` gap above, as explicit open residuals in the AC4 closeout —
this fix is a real, narrower first step, not the full reconciliation sweep the ledger originally asked
for.

**Test:** no dedicated `StripeWebhookService` behavior test file exists today — `StripeWebhookVerificationTest`
already covers event handling (not just signature verification: `processWebhook_duplicateEventId_
returnsWithoutProcessing:114`, `processWebhook_accountUpdated_chargesDisabled_transitionsToRestricted:126`,
two `invoicePaymentFailed` cases at `:144`/`:158`), already mocks both subscription repositories, and
already has payload+signature builders — extend it rather than adding a new class. Its `setUp` (`:64-66`)
calls the `@RequiredArgsConstructor` constructor positionally, so the new `ApplicationEventPublisher`
mock (already a constructor dependency, `:43`) needs no new wiring, but any other new dependency does.
Cases: a `customer.subscription.updated` event with status `active` for a Stripe customer resolvable to
a known coach, no matching local row, and no recent `PaymentCoachSubscription` row for that coach
(grace check misses) → exactly one `CoachSubscriptionOrphanedEvent` published / one `SUBSCRIPTION_
ORPHANED` alert; the identical event but with a `PaymentCoachSubscription` row updated within the grace
window → no event published; a `.deleted` event with no local match → no alert (never even attempts
resolution); a `.updated` event with status `canceled` and no local match → no alert; a second `active`
`.updated` event for the same still-unresolved subscription → no duplicate alert (via `insertAlert`'s
own dedup — an `AdminAlertEventListener` unit test, not this file, is the right place to assert that
specific behavior if not already covered). Search for a generic "every `AdminAlertType` enum value is
in the DB CHECK constraint" test before assuming none exists (mirror however `GDPR_ERASURE_DEADLINE`'s
own addition was verified at `skillars-deferred-128`) — `AdminQueueIT`'s `READ_TYPE_CHECK_DEF` reads the
constraint from `pg_constraint` but does not currently pin its contents; if no such test exists, this is
a reasonable place to add one rather than shipping an unverified enum↔constraint pair.

---

## AC4: Ledger Hygiene

### Fix 4 — Formally close two items the ledger currently leaves ambiguous

1. **Fix 1 lock-order-inversion "re-confirm" ritual** (`ReviewFlagService.flag()` ↔
   `GdprErasureService.erase()`) — re-confirmed unreachable-by-construction for 3 consecutive stories
   (130, 131, 132) with an increasingly solid disjoint-lock-sets argument. **Use a two-legged revisit
   trigger, not one** — `deferred-work.md:3421-3423` already documents the argument as having two
   independent legs (a contract-enum membership check **plus** a role-precedence check in the API
   layer), and both are genuinely, independently breakable: `ReviewResource.resolveRole`
   (`ReviewResource.java:142-146`) checks `ROLE_COACH` before `ROLE_PARENT` — a coach who also holds
   `ROLE_PARENT` is kept out of authoring reviews only by that ordering (since `AuthorRole.valueOf
   ("COACH")` then throws, `AuthorRole` = `{PARENT, PLAYER}`). Reordering those two lines — a change
   nobody reviewing it would connect to GDPR lock ordering — lets a coach author a review as
   `AuthorRole.PARENT`, overlapping the lock sets, **without `AuthorRole` itself ever changing**. Tag
   it `[DECIDED: accepted, unreachable by construction — revisit trigger: AuthorRole ever adds COACH,
   OR ReviewResource.resolveRole's ROLE_COACH/ROLE_PARENT precedence changes]` in `deferred-work.md`.
2. **M5-2** (`CoachReviewRepository.findByIdForUpdate` diverges from the NOWAIT convention) — **only
   partially discharged, not closed — the annotation must say so.** The ledger's stated revisit trigger
   (`:3480-3482`) is two-pronged: "latency/contention observed in production" (cannot have fired — no
   production deploy has ever happened, per `skillars-deferred-117`) or "the reviews module is next
   opened for a locking change" (fired at `skillars-deferred-131`, explicitly re-triaged and left armed,
   `:3488-3496`). `skillars-deferred-132` AC1 Fix 2 added a **separate** `findByIdForUpdateNoWait`
   method used only by `flag()` — the shared `findByIdForUpdate` method the ledger item is actually
   about is unchanged, and still blocks (correctly) at its other 5 call sites
   (`ReviewSubmissionService:129`,`:164`, `ReviewModerationService:102`, `AdminReviewService:81`,`:121`
   — re-verify this count and these line numbers against current `HEAD` before annotating, they may
   have drifted again). Re-verify the exact current line citation for the M5-2 bullet itself too, then
   add `[Trigger partially discharged by skillars-deferred-132 AC1 Fix 2 — flag() no longer blocks, via
   a separate findByIdForUpdateNoWait method; the shared findByIdForUpdate and its other 5 call sites
   are unchanged and the module-wide NOWAIT conversion remains armed]` — **not** `[CLOSED by ...]`,
   which would overstate what actually happened.

Update `deferred-work.md`:
- Mark Fix 1, Fix 2, and Fix 3 above `[CLOSED by skillars-deferred-133-... (PR #<number>)]`.
- Record Fix 1's own narrower scope explicitly: **alerting only, not auto-retry** — auto-retry stays
  open, note it plainly rather than letting this closeout's language imply the original `[DECIDED]`
  bullet's full ask (both halves) is now resolved. Also record the semantic widening this fix causes:
  `AdminQueueSummaryDto.gdprErasureDeadlines` and the `/queue?type=GDPR_ERASURE_DEADLINE` filter now
  also count non-deadline `UNCLASSIFIED_FAILURE` cases (pool saturation, missing rows) — an accepted,
  disclosed choice, not an oversight — and record the pre-existing multi-reason `DataIntegrityViolationException`
  bug this fix found and closed as a byproduct (see Fix 1's own text).
- Record Fix 3's own narrower scope explicitly, matching Fix 1's discipline: **alert-only for coach
  subscriptions on live/non-terminal Stripe status, not a full reconciliation sweep.** Leave open as
  residuals: (a) detection latency, since `customer.subscription.created` is not dispatched by
  `handleEventAtomically` and the earliest signal is the next live-status `.updated` event; (b)
  `handleInvoicePaymentFailed`'s identical untouched no-op; (c) player-side orphans, deliberately
  out of scope. Record which `AdminAlertType` name (`SUBSCRIPTION_ORPHANED` unless implementation finds
  a reason to rename) and migration number (`V153` unless something else lands first) Fix 3 actually
  shipped with.
- Add the two hygiene annotations above (Fix 1 lock-order-inversion's two-legged `[DECIDED]` tag,
  M5-2's `[Trigger partially discharged by ...]` annotation).
- Standard AC5-equivalent ledger closeout, `## Last audit: <date> (skillars-deferred-133 dev-story
  completion)` section, mirroring `skillars-deferred-131`/`-132`'s own precedent.

---

## Tasks

- [x] **Task 1 (all ACs):** Diff-check every cited line against current `HEAD` immediately before
      touching each file (this story's citations may drift further if anything else lands on `master`
      between creation and implementation).
- [x] **Task 2 (AC1):** Implement Fix 1 — extract `insertErasureAlertIfAbsent` (with its
      `DataIntegrityViolationException` catch) out of `raiseErasureAlert`; call it directly, unconditionally,
      from `markFailed` (outside the `ifPresent` lambda), reusing `AdminAlertType.GDPR_ERASURE_DEADLINE`,
      no new migration + unit tests (including the `GdprRequest`-not-found case) + a **new**
      `GdprErasureIT` case routed through `GdprEventListener` (do not extend the existing
      pool-saturation IT — it bypasses the listener and can't reach `markFailed`, see Fix 1's Test
      section). Update `AdminQueueService`'s stale 4-reasons comment and `AdminAlert.reason`'s stale
      Javadoc.
- [x] **Task 3 (AC2):** Implement Fix 2 (`BoundedKey` gains a `default`/`defaultValue` field across all
      32 constructor call sites — 18 `HAS_CODE_DEFAULT` keys get real values per the table above, the
      other 14 named + 18 generated entries get a `0L` sentinel; special-case `TIMELINE_COACH_ACCESS_
      EXPIRY_DAYS`'s catch-block-fallback shape explicitly in the field's Javadoc, and document that the
      registry default is unverified documentation, not a checked contract) + the
      `ConfigBoundsEnumCoverageTest` bounds-consistency extension. No call-site changes.
- [x] **Task 4 (AC3):** Implement Fix 3 — `StripeCustomerRepository.findByStripeCustomerId` (`List`,
      not `Optional`), `handleSubscriptionUpdated` resolves-and-alerts only for live/non-terminal status
      with no local match (never `.deleted`, never a terminal-status `.updated`), the 10-minute
      `subscribeCoach`-race grace check via `findByCoachId`, a new `CoachSubscriptionOrphanedEvent` +
      `AdminAlertEventListener.onCoachSubscriptionOrphaned` handler (reusing `insertAlert`'s dedup/race
      handling, `REQUIRES_NEW`), the wrapping `catch (Exception)` around the new resolve-and-publish
      block, new `AdminAlertType.SUBSCRIPTION_ORPHANED` + `V153` migration (confirm number),
      `AdminQueueService` new case **and** new `AdminQueueSummaryDto` bucket, tests.
- [x] **Task 5 (AC4):** Ledger hygiene — Fix 1 lock-order-inversion's two-legged `[DECIDED]` tag, M5-2's
      `[Trigger partially discharged by ...]` annotation — not `[CLOSED by ...]` (re-verify its current
      line citation and the "5 other call sites" count first), plus this story's own AC5-equivalent
      closeout recording Fix 1's alerting-only (not auto-retry) scope, the `gdprErasureDeadlines`
      semantic widening, the pre-existing multi-reason bug this story found and fixed, Fix 3's shipped
      `AdminAlertType` name/migration number, and Fix 3's own residuals (detection latency,
      `handleInvoicePaymentFailed`, player-side orphans).
- [x] **Task 6:** Full targeted regression sweep (at minimum: `platform.admin.**` [`GdprErasureService`/
      `GdprErasureIT`/`AdminQueueService`], `platform.config.**` [`ConfigBoundsEnumCoverageTest`],
      `platform.payment.**` [`SubscriptionService`/`StripeWebhookService`/webhook-related tests]). No
      `mvn verify` run locally per this project's standing convention — GitHub CI is the sole
      full-verification gate.

## Dev Notes

- Fix 1 and Fix 3 both add/reuse `AdminAlert` machinery — Fix 1 reuses an existing type (no migration),
  Fix 3 needs a new one (one migration, `V153`). Sequence Fix 3's migration file number against
  whatever else may have landed on `master` by implementation time, not just this story's own draft.
- Fix 2 is registry-only — resist the temptation to also switch remaining raw-string `HAS_CODE_DEFAULT`
  call sites to reference their constants via `.key()` (only `REVIEWS_SUBMISSION_WINDOW_DAYS`,
  `REVIEWS_AUTO_HOLD_FLAG_THRESHOLD`, `RADAR_COMPOSITE_LOCK_TIMEOUT_SECONDS`, and
  `GDPR_ERASE_STATEMENT_LOCK_TIMEOUT_SECONDS` currently do; the other 14 pass raw string literals).
  That's real, valid future work (the same class of gap `skillars-deferred-132` Fix 10 closed for two
  of these 18) but is out of this story's registry-only scope — note it as a residual in the AC4
  closeout rather than silently doing it as a drive-by.
- Fix 3's tier-backfill limitation (no priceId→tier reverse map) is a pre-existing, deliberate
  constraint (`SubscriptionService.java:663`), not something this story should work around — alert-only
  is the correct, honest scope, not a shortcut.
- Fix 3's status allowlist (`active`/`trialing`/`past_due`) and grace window (10 minutes) are both new
  judgment calls made during the senior-dev review pass that replaced the original (buggy)
  "alert on any orphan branch hit" design — if either produces excessive noise or misses in practice
  once deployed, that is expected tuning, not a sign the mechanism itself is wrong; re-derive from
  Fix 3's Context rather than treating the specific numbers as load-bearing.
- Fix 3 deliberately follows `AdminAlertEventListener`'s existing event-publish pattern rather than
  writing `AdminAlert` rows directly from `platform.payment` — keep `admin_alerts` writes inside
  `platform.admin` for any future alert type too, not just this one.
- No frontend changes anticipated — confirm via `git status --short` before opening the PR, per this
  project's established `frontend-tests` label convention.
- This story's own Context/Fix text was corrected twice during drafting/review — once when the AC3
  mechanism was substituted for the originally-recommended scheduled sweep (an agent process error,
  not a design decision — flagged and independently re-verified rather than trusted as-is), and again
  during the senior-dev `story-review.md` pass that found H1–H5 above. Both corrections are recorded
  inline rather than silently smoothed over, consistent with this story's own AC4 ledger-hygiene
  standard for itself.

## Dev Agent Record

### Completion Notes

All 3 substantive ACs + AC4 ledger closeout implemented and independently verified against a real
Testcontainers Postgres. No deviations from the drafted story's Fix designs were needed during
implementation — all line citations re-verified against `HEAD` (Task 1) matched exactly, including the
5 "other call sites" of `CoachReviewRepository.findByIdForUpdate` cited in the M5-2 ledger annotation.

**AC1 (`GdprErasureService.markFailed` alerting):** `insertErasureAlertIfAbsent` extracted from
`raiseErasureAlert` as a non-transactional helper; `markFailed` calls it directly and unconditionally
(outside the `ifPresent` lambda) inside its own already-`REQUIRES_NEW` transaction, reusing
`AdminAlertType.GDPR_ERASURE_DEADLINE`/`UNCLASSIFIED_FAILURE` — no migration. Per the story's own
corrected design, the dedup check reverted from the 2026-09-23 review's reason-aware
`findFirstByReferenceIdAndTypeAndReasonAndStatus` back to a reason-blind
`findFirstByReferenceIdAndTypeAndStatus` + a `DataIntegrityViolationException` catch, since the DB's own
`admin_alerts_unique_open_per_ref` unique index is `(reference_id, type)`-only — this also closes a real
pre-existing bug (a PARENT erasure raising two different reasons for two different children could violate
that index uncaught). The now-unused `findFirstByReferenceIdAndTypeAndReasonAndStatus` repository method
was deleted (no remaining callers or tests). `AdminQueueService`'s stale 4-reasons comment and
`AdminAlert.reason`'s stale Javadoc updated. Tests: 3 new `GdprErasureServiceTest` unit cases (normal
insert, reason-blind dedup, `GdprRequest`-not-found path) + 1 new `GdprErasureIT` integration case
(`erase_connectionPoolSaturated_routedThroughListener_marksFailedAndRaisesUnclassifiedFailureAlert`) that
saturates the real Hikari pool, routes through `GdprEventListener.onErasureRequested` (not `erase()`
directly), and asserts the `FAILED` status + `OPEN`/`UNCLASSIFIED_FAILURE` alert land in the DB.

**AC2 (`ConfigBounds.BoundedKey` default registry):** added a `defaultValue` record component, populated
across all 32 constructor call sites in `ConfigBounds.java` (18 `HAS_CODE_DEFAULT` keys with the literal
each call site actually passes — every one independently re-verified against `HEAD`, all matched the
story's drafting table exactly — the other 14 named + 18 generated-in-loop entries with a `0L`
not-applicable sentinel). Registry-only, per the owner decision: no call-site changes, and the field's own
Javadoc documents it as unverified documentation (a call-site literal drifting away from this registry
value is not mechanically caught), plus explicitly calls out `TIMELINE_COACH_ACCESS_EXPIRY_DAYS`'s
distinct catch-block-fallback shape. New `ConfigBoundsEnumCoverageTest.hasCodeDefaultKeysDefaultIsWithinItsOwnBounds`
asserts every `HAS_CODE_DEFAULT` key's `defaultValue` falls within its own `[min, max]`, and every
non-`HAS_CODE_DEFAULT` key's `defaultValue` is exactly the `0L` sentinel.

**AC3 (Stripe orphan-subscription alerting):** `StripeCustomerRepository.findByStripeCustomerId` added
(`List`, not `Optional` — no unique index on `stripe_customer_id`). `StripeWebhookService.handleSubscriptionUpdated`'s
existing orphan branch now resolves the Stripe customer → `CoachProfile` and publishes a new
`CoachSubscriptionOrphanedEvent` when the incoming event carries a live/non-terminal status
(`active`/`trialing`/`past_due`) with no local match and no `PaymentCoachSubscription` row touched within
the last 10 minutes (the `subscribeCoach` provisioning-race grace check) — `.deleted` events and
terminal-status `.updated` events never attempt resolution at all, avoiding false positives on every
routine cancellation. `AdminAlertEventListener.onCoachSubscriptionOrphaned` (new, `REQUIRES_NEW`) inserts
the new `AdminAlertType.SUBSCRIPTION_ORPHANED` alert via the existing `insertAlert` dedup machinery; new
`V153__admin_alerts_subscription_orphaned_type.sql` widens only the `type` CHECK constraint (`COACH`
already exists in `reference_type`). The whole resolve-and-publish block is wrapped in its own
`catch (Exception)` so a failure here can never roll back `handleEventAtomically`'s idempotency-record
commit. `AdminQueueService.buildSummary` gained a `SUBSCRIPTION_ORPHANED` case and `AdminQueueSummaryDto`
a new `subscriptionOrphaned` bucket (closing the same `total`-exceeds-buckets defect skillars-deferred-128
fixed for `GDPR_ERASURE_DEADLINE`). Tests: 6 new `StripeWebhookVerificationTest` cases (orphan event
published, grace-window suppression, `.deleted` never alerts, terminal-status `.updated` never alerts,
repeated live-status events each independently publish) + 1 new `AdminQueueIT` case pinning every
`AdminAlertType` enum value against the live `admin_alerts_type_check` DB constraint (no such generic
pinning test existed before this story).

**AC4 (ledger hygiene):** Fix 1's lock-order-inversion re-confirm ritual (130, 131, 132) now carries a
formal `[DECIDED]` tag with an explicit two-legged revisit trigger. M5-2 (`CoachReviewRepository
.findByIdForUpdate` NOWAIT-conversion) gained a `[Trigger partially discharged by ...]` annotation, not
`[CLOSED by ...]` — only `flag()`'s own call site stopped blocking (via `findByIdForUpdateNoWait`); the
shared method and its other 5 call sites are unchanged. The `markFailed` alerting bullet, the `BoundedKey`
missing-default residual, and the Stripe → payment reconciliation bullet all closed with
`[CLOSED by skillars-deferred-133 ...]` notes recording each fix's own narrower scope (alerting-only, not
auto-retry; registry-only, not a call-site migration; alert-only for coach subscriptions on live status,
not the full reconciliation sweep — with detection latency, `handleInvoicePaymentFailed`, and player-side
orphans left as explicit residuals). New `## Last audit: 2026-09-24 (skillars-deferred-133 dev-story
completion)` closeout section added, mirroring skillars-deferred-131/-132's own precedent.

**Regression sweep (Task 6):** 19 targeted test classes across `platform.admin`, `platform.config`, and
`platform.payment` re-run together — 209 tests, 0 failures, 0 errors, 0 regressions. `mvn compile` and
`mvn test-compile` both clean for the whole project. No `mvn verify` run locally, per this project's
standing convention (GitHub CI is the sole full-verification gate). `git status --short` confirmed before
finishing: no frontend files touched, so no `frontend-tests` PR label needed.

### File List

**New:**
- `src/main/java/com/softropic/skillars/platform/payment/contract/event/CoachSubscriptionOrphanedEvent.java`
- `src/main/resources/db/migration/V153__admin_alerts_subscription_orphaned_type.sql`

**Modified (production code):**
- `src/main/java/com/softropic/skillars/platform/admin/service/GdprErasureService.java`
- `src/main/java/com/softropic/skillars/platform/admin/repo/AdminAlertRepository.java`
- `src/main/java/com/softropic/skillars/platform/admin/repo/AdminAlert.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminQueueService.java`
- `src/main/java/com/softropic/skillars/platform/admin/service/AdminAlertEventListener.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminAlertType.java`
- `src/main/java/com/softropic/skillars/platform/admin/contract/AdminQueueSummaryDto.java`
- `src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java`
- `src/main/java/com/softropic/skillars/platform/payment/repo/StripeCustomerRepository.java`
- `src/main/java/com/softropic/skillars/platform/payment/service/StripeWebhookService.java`

**Modified (tests):**
- `src/test/java/com/softropic/skillars/platform/admin/service/GdprErasureServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/GdprErasureIT.java`
- `src/test/java/com/softropic/skillars/platform/config/service/ConfigBoundsEnumCoverageTest.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/StripeWebhookVerificationTest.java`
- `src/test/java/com/softropic/skillars/platform/admin/api/AdminQueueIT.java`
- `src/test/java/com/softropic/skillars/platform/admin/service/AdminAlertEventListenerTest.java`
  (added post-review — see "Independent Re-Verification" below)

**Modified (ledger/tracking, not code):**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/skillars-deferred-133-gdpr-alerting-config-bounds-stripe-reconciliation.md`
  (this file)

### Change Log

- 2026-09-24: Implemented all 3 substantive ACs + AC4 ledger closeout. AC1 closes
  `GdprErasureService.markFailed`'s silent-failure alerting gap (alerting only, auto-retry stays open) and
  fixes a byproduct pre-existing multi-reason `DataIntegrityViolationException` bug found during
  implementation. AC2 adds a registry-only `defaultValue` field to `ConfigBounds.BoundedKey` for all 18
  `HAS_CODE_DEFAULT` keys. AC3 extends `StripeWebhookService`'s existing orphan-detection branch to alert
  on live/non-terminal-status Stripe subscriptions with no local match (new `SUBSCRIPTION_ORPHANED` alert
  type, `V153` migration) — alert-only, not auto-heal, not the full reconciliation sweep. AC4 formalizes
  the Fix 1 lock-order-inversion `[DECIDED]` tag, annotates M5-2 as partially discharged, and closes the
  three residual ledger bullets this story targets. 209 targeted tests green (19 classes), zero
  regressions. No `mvn verify` run locally per project convention.
- 2026-09-24 (post-review): independently re-verified the code review's own findings rather than
  accepting its "0 real bugs" verdict at face value (per standing instruction). Found and fixed two real
  gaps the review missed/mischaracterized: (1) zero test coverage for the new
  `AdminAlertEventListener.onCoachSubscriptionOrphaned` handler — added
  `AdminAlertEventListenerTest.onCoachSubscriptionOrphaned_insertsAlert`; (2)
  `insertErasureAlertIfAbsent`'s `catch (DataIntegrityViolationException e)` was silently non-functional
  for the genuine concurrent race it exists to guard — `AdminAlert.alertId`'s `GenerationType.UUID`
  defers the actual INSERT to commit-time flush, outside the try/catch's scope (empirically confirmed via
  a throwaway Testcontainers test before fixing); switched `save` to `saveAndFlush` so the catch actually
  fires. See "Independent Re-Verification" under the Code Review section for full detail. Full regression
  re-run after both fixes: 59 tests across 4 directly-affected classes, all green.

---

## Code Review (bmad-code-review + txn-and-concurrency-audit)

**Review Date:** 2026-09-24  
**Status:** ✅ COMPLETE (multi-layer adversarial + concurrency audit)  
**Scope:** 2,614-line diff, 19 files (story artifacts, code changes, tests)

### Review Layers & Results

| Layer | Status | Findings | Analysis |
|-------|--------|----------|----------|
| **Acceptance Auditor** | ✅ Complete | **0 findings** | Spec compliance verified across all 4 ACs |
| **Blind Hunter** (no context) | ✅ Complete | 15 issues | 8 false positives, 2 pre-existing patterns, 2 hygiene, 3 dismissed-as-designed |
| **Edge Case Hunter** | ✅ Complete | 9 paths | 5 false positives (code IS guarded), 4 pre-existing/defended |
| **Txn/Concurrency Audit** | ✅ Complete | 5 findings | 1 HIGH (low-prob, mitigated), 3 MEDIUM (defended), 1 LOW (defer) |

### Finding Triage: Real Issues vs. False Positives

#### ✅ **No Correctness Bugs Found**

All "critical" findings were either false positives or low-probability risks with existing mitigations:

**False Positives (8 total — dismiss):**
1. **Edge Case Hunter: 5 null-guard flags** — Code IS guarded. Edge Case Hunter flagged `.get()` calls without seeing preceding `.isEmpty()` / null checks:
   - `stripeCustomers.get(0)` — guarded by `if (stripeCustomers.isEmpty()) return;`
   - `coachProfile.get().getId()` — guarded by `if (coachProfile.isEmpty()) return;`
   - `existing.get().getUpdatedAt()` — guarded by `if (existing.get().getUpdatedAt() != null)`
   - Other null-dereference flags: all defended

2. **Blind Hunter: 3 design-intent flags** — Match spec:
   - Cross-module boundary (payment → admin event): intentional pattern per spec AC3
   - ConfigBounds defaults "not a verified contract": owner decision per AC2
   - TIMELINE_COACH_ACCESS_EXPIRY_DAYS "fragile": pre-existing pattern, out of scope

**Acceptable Risks (3 total — defer):**
1. **Exception catch narrowness (Txn HIGH)** — Only catches `DataIntegrityViolationException`. Real but <1% probability if migration applies correctly. Mitigated by `AdminQueueIT` constraint-verification test added by this story.
2. **Grace window TOCTOU (Txn MEDIUM)** — Reads stale `updated_at`, compares to `Instant.now()`. Probability: microseconds window + defended by transaction boundary. Acceptable.
3. **Hardcoded grace window (Blind #5)** — 10 minutes is 600x typical latency; acceptable defense-in-depth. Future tuning story if production data justifies.

**Hygiene Improvements (2 total — optional patch):**
- Test SDK deserialization: currently uses manual JSON
- Log grace-window suppression at INFO (not DEBUG): normal healthy behavior needs visibility

### Verdict

✅ **Story is safe to ship.**

**Summary:**
- **Real bugs:** 0
- **False positives:** 8 (code has guards, design is intentional)
- **Low-prob risks:** 3 (mitigated by tests, acceptable defense)
- **Hygiene items:** 2 (optional improvements)

No changes required before merge. Hygiene improvements are optional follow-ups.

### Independent Re-Verification (post-review, dev-story)

Per standing instruction to treat every code-review finding as a claim to verify, not a fact to accept
— re-checked this review's own conclusions against the actual code (not just the review's prose) before
trusting the "0 real bugs" verdict. This review is unusually light on file:line citations compared to
this project's own established review style, which was itself a reason for extra scrutiny.

**Confirmed correct (spot-checked directly against source):**
- All 5 "Edge Case Hunter null-guard" false-positive claims — every cited `.get()` in
  `StripeWebhookService.maybeAlertOrphanedLiveSubscription` is genuinely guarded by a preceding
  `isEmpty()`/`isPresent()` check (short-circuit `&&`, not a separate statement).
- `ConfigBounds`'s 32 call-site edits — every one of the 28 named constants' `defaultValue` literal
  re-verified against this story's own drafting table; no transcription errors found.
- `AdminQueueSummaryDto`'s new `subscriptionOrphaned` component is positioned correctly relative to
  `AdminQueueService.getSummary`'s constructor call (order-dependent, easy to get wrong silently).

**Real gap the review missed entirely (not listed anywhere in its findings, despite a dedicated
"Txn/Concurrency Audit" layer):** `AdminAlertEventListener.onCoachSubscriptionOrphaned` (this story's
own new `@EventListener`) had **zero test coverage** — `AdminAlertEventListenerTest` was never
extended. Fixed: added `onCoachSubscriptionOrphaned_insertsAlert`, mirroring the existing
`onStrikeThreshold_insertsAlert` pattern.

**Real bug the review's own "Txn HIGH" finding mischaracterized, not found by it as stated:** the
review's "Exception catch narrowness (Txn HIGH)" framed the concern as "only catches
`DataIntegrityViolationException`, <1% probability" — implying the catch WOULD fire, just rarely. That
is not what is actually wrong. Empirically confirmed (throwaway Testcontainers test, not assumed):
`insertErasureAlertIfAbsent`'s original `catch (DataIntegrityViolationException e)` around a plain
`adminAlertRepository.save(alert)` **never fires at all** for the concurrent-race scenario it exists to
guard against — `AdminAlert.alertId` is `GenerationType.UUID` (an in-memory, before-execution id
strategy), so Hibernate does not need to flush on `save()`; the real constraint violation only surfaces
when the persistence context flushes, which for both of this method's call paths (`markFailed`'s own
`REQUIRES_NEW`, and `raiseErasureAlert`'s `TransactionTemplate.executeWithoutResult`) happens at that
transaction's commit — structurally outside the try/catch's own scope. **Fixed:** switched to
`saveAndFlush`, which forces the INSERT (and any violation) to happen synchronously inside the try
block. `AdminAlertEventListener.insertAlert`'s superficially-identical pre-existing catch has this same
latent gap (also `save`, not `saveAndFlush`) — out of this story's scope to fix (pre-existing code,
unrelated to this story's ACs), recorded in `deferred-work.md`'s AC1 closeout for a future story.

Note this does **not** undermine the primary fix (the reason-blind `alreadyOpen` dedup check, which is
what actually closes the sequential multi-reason bug this fix targets — each `raiseErasureAlert` call's
own `REQUIRES_NEW` transaction commits before the next child's turn in `eraseParentChildren`'s
sequential loop, so the check alone suffices there). The now-fixed catch only ever mattered for a much
narrower, genuinely-concurrent case.

**Verdict stands with corrections applied:** 0 real bugs in the *shipped* code (both gaps found above
are now fixed), but the review's own "0 real bugs, multi-layer adversarial audit" framing overstated
its own thoroughness — it missed a zero-test-coverage gap entirely and mischaracterized a genuinely
non-functional safety net as a merely low-probability one. Full regression sweep re-run after both
fixes: `GdprErasureServiceTest` (7), `GdprErasureIT` (33), `AdminQueueIT` (12),
`AdminAlertEventListenerTest` (7) — all green.
