# Senior-dev audit — `skillars-deferred-109`

**Target:** `_bmad-output/implementation-artifacts/skillars-deferred-109-frontend-defect-sweep-configbounds-completeness-deploy-probe-bounding-and-cutover-decisions.md`
**Reviewed at:** HEAD `3589b514` (story-creation commit; base master `bbad7938`)
**Reviewer:** senior-dev audit pass — missed corner cases, false assumptions, missed flows
**Date:** 2026-09-10

Every finding below was verified by reading the cited code at HEAD. Claims I could not
substantiate were dropped rather than filed. A short **"Verified sound"** list at the end records
what I checked and found correct, so the absence of a finding is a positive result, not a gap in
coverage.

**Verdict:** the story is well-researched and most of its 21 ledger bullets are real defects
correctly characterised. But **three ACs prescribe fixes that are wrong or actively harmful**
(AC7, AC12.1, AC10), **four have unsafe or under-specified fix shapes** (AC2.2, AC1.1↔AC1.2,
AC4.1, AC11), and **AC15's own baseline figures are wrong** — the exact error it warns the dev
agent about. Recommend **changes required** before `ready-for-dev`.

---

## Severity summary

| # | AC | Finding | Severity |
|---|---|---|---|
| 1 | AC7 | The DST "fix" is backwards; it would make every cross-DST reschedule fail backend validation | **Blocker** |
| 2 | AC12.1 | `INFO`→`WARN` as specified mislabels every *successful* admin alert; the prescribed test passes either way | **Blocker** |
| 3 | AC10 | Premise wrong — nothing reads `video.quota.semiPro.*`/`.pro.*`; no fail-fast results; the real defect is left untouched | **Blocker** |
| 4 | AC2.2 | "expiry still absent → `refreshFailed`" breaks the documented legacy/no-`rint` fallback; ordering vs `tick()` unspecified | **High** |
| 5 | AC1.1 ↔ AC1.2 | The two ACs contradict each other on the mechanism that preserves the retry affordance | **High** |
| 6 | AC4.1 | `return selfPlayerId.value` can hand a superseded caller a *different* account's id | **High** |
| 7 | AC11 | Bounding the ERR-trap `${DC} up -d` is unsafe; two unbounded calls missed; new WARNs get swallowed | **High** |
| 8 | AC5.1 | Option (b) introduces a stale-result regression; the AC's verification window is the wrong one | Medium |
| 9 | AC5.2 | "nullish response → empty + success" is a silent-data-loss contract, asserted without justification | Medium |
| 10 | AC8.1 | The partial-row predicate still drops `0`/negative/label-only rows silently | Medium |
| 11 | AC8.2 | Premise unreachable at HEAD — there is no hydration path into this component | Medium |
| 12 | AC15 | Baseline line count and `[DECIDED]`/`[DISMISSED]` counts are all wrong | Medium |
| 13 | AC12.2 | The prescribed test seam (`@MockitoBean JavaMailSender`) does not exist; assertion target ≠ setup | Medium |
| 14 | AC14.2 | Target runbook section is topic-scoped and its "four items" sentence would be falsified | Low |
| 15 | AC9 | Only one of the two `booking-id` call sites is analysed | Low |
| 16 | AC1.4 | Misses the sibling dead-catch and the stale-saved-card path in the same function | Low |
| 17 | AC3.1 | "Parity" delivers only one of `useSession`'s two `rint` clears | Low |
| 18 | AC3.2 | Mutation check is only half-sensitive | Low |
| 19 | AC13 | Acceptance evidence is self-contradicting between the AC and Task 19 | Low |
| 20 | Header | Several "re-verified against HEAD" cites are wrong, including one that "corrects" a correct ledger cite | Low |

---

## Blockers

### 1. AC7 — the prescribed DST fix is backwards and would break reschedule submission

**The AC says:** `rescheduleProposedEnd` is wrong because it adds a fixed-instant delta and reads
the result back in wall clock; the fix is to compute *"start-wall-clock + duration-in-wall-clock-minutes"*.

**The backend disagrees.** `RescheduleService.java:176-186`:

```java
Duration originalDuration =
    Duration.between(booking.getRequestedStartTime(), booking.getRequestedEndTime());
Duration proposedDuration =
    Duration.between(req.proposedStartTime(), req.proposedEndTime());
if (!proposedDuration.equals(originalDuration)) { …reject… }
```

`Duration.between(Instant, Instant)` is an **exact elapsed-instant** duration. The reschedule is
validated as *"the same number of real minutes"*, and `ParentBookingsPage.vue:340-348` derives
`rescheduleDurationMs` the same way (`end.getTime() - start.getTime()` off the original booking's
instants). The frontend's current fixed-instant arithmetic is therefore **exactly what the backend
requires**, and the template comment at `:174-176` says so ("the backend requires a reschedule to
keep the session's original length").

**What the AC's fix would do.** For the AC's own example — a 60-minute booking moved to
`2026-03-08T01:30` in `America/New_York`:

| | proposed end shown | submitted `proposedEndTime` | `Duration.between` | backend |
|---|---|---|---|---|
| current code | `03:30` | `07:30Z` | 60 min | accepted |
| AC7's fix | `02:30` — **a local time that does not exist** | `07:30Z` (V8 normalises) or `06:30Z` | 60 or 0 min | rejected on the fall-back case |

On the fall-back example the AC also cites (`2026-11-01T01:30`), "+60 wall minutes" gives `02:30`
= `07:30Z`, i.e. **120 minutes** after the chosen start instant — a guaranteed
`Duration` mismatch rejection.

**What is actually wrong here** (and worth an AC, re-scoped):

- On fall-back, `new Date('2026-11-01T01:30')` silently resolves an **ambiguous** wall time; the
  parent has no way to express "the second 01:30". The derived end then renders as `01:30` too —
  identical to the start, which reads as a broken form.
- `new Date(rescheduleProposedStart.value)` interprets the `datetime-local` value in the
  **browser's** zone, while the session has its own `canonicalTimezone` (`:358`). The dialog
  mitigates this with hint text naming both zones (`:167-172`, `:183-188`) but the arithmetic is
  browser-zone throughout.

**Recommendation:** delete the arithmetic change. Re-scope AC7 to (a) a spec that *pins* the
current fixed-instant behaviour as correct — with the `RescheduleService:176-186` rationale in a
comment so a future reviewer does not re-file this — and (b) optionally a display affordance for
the DST-boundary cases. The mutation check as written ("revert to the fixed-ms delta → the 60-min
assertion fails, shows 120") asserts the wrong direction and would enshrine the regression.

---

### 2. AC12.1 — the `INFO`→`WARN` change as specified mislabels every successful send

**The AC says:** *"`sendAdminAlertSync` (`:107-108`) logs `log.info("[VIDEO_MODERATION_ADMIN_ALERT] delivered …")`
when `envelopeEntityRepository.findBySendId(envelope.sendId())` returns `null`"* → change it to
`log.warn("… send outcome not yet visible … envelope row not found on read-back")`.

**That is not what the code does.** `VideoModerationEmailListener.java:94-108`:

```java
EnvelopeEntity persisted = envelopeEntityRepository.findBySendId(envelope.sendId());
if (persisted != null && persisted.getStatus() == EmailDeliveryStatus.FAILED) {
    …retryable throw / permanent release…
}
log.info("[VIDEO_MODERATION_ADMIN_ALERT] delivered alert for videoId={} subject={}", …);
```

The `log.info` at `:107` is the fall-through for **two** cases:

1. `persisted == null` — the case the AC is about; and
2. `persisted != null && status != FAILED` — a **genuinely successful** send (`SENT`, stamped by
   `MailManager.toEnvelopeEntity` at `MailManager.java:130-133`).

Implementing the AC literally — rewriting that one statement to the new WARN — turns **every
successful admin alert** into `WARN … envelope row not found on read-back`, which is false, and
inverts the operator signal the AC exists to sharpen.

**It also ships green.** The prescribed test change is *"Update `VideoModerationEmailListenerTest.noPersistedEnvelope_doesNotThrow`
(currently asserts `INFO`) to assert the new `WARN`"* — that test only drives case 1, so it passes
whether or not the branch is split. Nothing in the AC exercises case 2.

**Recommendation:** rewrite AC12.1 to require an explicit split:

```java
if (persisted == null) { log.warn("[VIDEO_MODERATION_ADMIN_ALERT] send outcome not yet visible …"); return; }
log.info("[VIDEO_MODERATION_ADMIN_ALERT] delivered …");
```

and add the missing second assertion (a `SENT` envelope still logs `INFO … delivered`) as the
mutation-sensitivity pair.

---

### 3. AC10 — the premise is wrong about who reads those keys, there is no fail-fast, and the real defect is left in place

Three separate problems, all verified:

**(a) Nothing reads `video.quota.semiPro.*` / `video.quota.pro.*`.**
`QuotaConfigService.resolveTierKey` (`QuotaConfigService.java:47+`) is the only producer of the
tier segment:

```java
CoachSubscriptionTier tier = coachProfileService.getCoachSubscriptionTier(coachId);
return switch (tier) { case SCOUT -> "scout"; case INSTRUCTOR -> "instructor"; case ACADEMY -> "academy"; };
} catch (IllegalArgumentException e) {
    return "athlete";      // every non-UUID (i.e. player) ownerId
}
```

It can return only `scout` / `instructor` / `academy` / `athlete` — **never** `semiPro` or `pro`.
`grep` confirms `PlayerSubscriptionTierBilling` is referenced nowhere outside its own declaration
(`SubscriptionService.java:57,76,654` uses the string literals `"SEMI_PRO"` / `"PRO"` instead).
So the four `V53` rows (ids 121, 122, 127, 128) are **dead configuration**. D2's motivation —
*"an operator setting `video.quota.pro.storageBytes = -1` gets no fail-fast"* — describes a value
that no code path ever reads.

**(b) The prescribed fix produces no fail-fast.** The loop the AC leans on
(`ConfigBounds.java:263-271`) generates every tier bound as
`new BoundedKey(…, 0L, Long.MAX_VALUE, false)` — `failFast = false`. Per
`ConfigStartupAssertion.java:29-32`, a non-`failFast` out-of-range key logs an ERROR and increments
`config.value.misconfigured`, and **boots normally**. AC10's Test bullet ("add or extend a
`ConfigStartupAssertion` test asserting an out-of-range `video.quota.pro.storageBytes` is now
flagged") is only satisfiable as an ERROR-log/metric assertion, never as a boot refusal. The
story's language ("fail-fast") should be corrected so the dev agent does not go looking for a
`throw` that will not happen.

**(c) The live defect this AC walks past.** Because `resolveTierKey` falls through to `"athlete"`
for every player, a `SEMI_PRO` or `PRO` player receives the **ATHLETE** quota — 2 GiB storage /
10 GiB bandwidth instead of the 4 GiB / 25 GiB and 7 GiB / 30 GiB that `V53:37-38,44-45` seeds for
them. That is a real entitlement bug in shipped code; "complete the hand-list" bounds the keys
around the hole without closing it.

**(d) AC10.4 would enshrine the misunderstanding.** The AC asks to reword the
`ConfigBounds.java:193-205` comment to say the guard "cannot see a key templated over a **third
enum dimension**". There is no third dimension in the runtime key derivation — there is an
unimplemented tier mapping. The reworded comment would mislead the next reader.

**Recommendation:** keep AC10.1 (harmless, cheap) but rewrite its rationale to
*"bound four rows that are currently seeded-but-unread, so a future `resolveTierKey` that does
honour `PlayerSubscriptionTierBilling` inherits a bound"*. Correct the fail-fast language and the
AC10.4 comment text. File the player-tier quota mapping as its own ledger bullet — it is a real
defect and the story's own "genuine one-off bugs class is exhausted" claim is weakened while it
stands.

*Minor, same AC:* AC10.3 suggests cross-referencing `PlayerSubscriptionTierBilling.values()` in the
test. That would make the enum's **only** usage in the codebase a test-side drift guard, and it
makes the existing comment at `ConfigBoundsEnumCoverageTest.java:40-41` ("the player fallback tier
… is not an enum constant") stale, since `ATHLETE` is one.

---

## High

### 4. AC2.2 — the ineffective-refresh check breaks the documented legacy fallback

**The AC says:** after a resolved `sessionApi.refresh()`, read the expiry back and, *"if it did not
advance beyond where it was before the call **(or is still absent)**, set `refreshFailed.value = true`"*.

`readSessionExpiryFromCookie()` (`sessionManager.js:78-86`) returns `null` in **two** distinct
situations:

```java
if (!match) return null;                                   // cookie absent
…
if (Number.isFinite(epochMs) && epochMs >= MIN_PLAUSIBLE_EPOCH_MS) { … return epochMs; }
return null;                                               // stale pre-1.7b format
```

The module has an entire documented fallback for the second case (`:113` *"Fallback (when 'rint' is
missing or in the stale pre-1.7b format)"*) and carries `hasSeenRintThisTab()` (`:36`, used at
`:133`) precisely so it can tell **"legacy build"** from **"cleared"**. Under the AC's rule, a
successful refresh against a build that does not issue an absolute `rint` is reported as failed on
every single refresh — the user gets *"we couldn't extend your session"* while nothing failed,
which is the exact UX `deferred-90` AC4 was written to eliminate (quoted in the catch at `:266-269`).

**Second problem — ordering.** The AC does not say where the new check sits relative to
`refreshExpiryState()` (`:264`), which delegates to `tick()`. `tick()` clears the flag on the
warning-band exit edge:

```java
if (!showWarning.value && wasWarning) { stopCountdown(); refreshFailed.value = false; }   // :177-184
```

and `cleanup()` clears it again at `:286` on the expiry path. Setting `refreshFailed = true`
*before* `refreshExpiryState()` silently loses it in both cases; setting it *after* can stamp the
flag onto a session `tick()` has already torn down.

**Third — the predicate.** Strict "advanced beyond the pre-call value" false-positives on a
multi-tab race: a sibling tab's refresh can land between the pre-call read and the response, and a
second-granularity `rint` can come back **equal**. A safer criterion is *"the expiry read back is
meaningfully in the future"* (e.g. `> WARNING_THRESHOLD` remaining), not *"strictly greater than
before"*.

**Recommendation:** condition the guard on `hasSeenRintThisTab()` (or on having read a non-null
expiry *before* the call), pin the ordering explicitly relative to `refreshExpiryState()`, and use
a future-ness predicate rather than strict monotonicity. Add a spec case for the legacy path
(no `rint` ever seen → successful refresh must **not** set `refreshFailed`) — without it the
regression ships green.

---

### 5. AC1.1 and AC1.2 contradict each other

AC1.1's fix instruction ends with: *"Keep the `deferred-103` AC9 affordance semantics (a failed
retry re-raises `stripeUnavailable` so the button stays put — `:179-181`)."*

AC1.2, three paragraphs later, proves `:179-181` is **unreachable dead code**, and I confirmed it:
`payment.store.js:238-248` and `:249-259` both `catch (err) { this.error.X = err }` and resolve, so
the `Promise.all` at `PaymentMethodCard.vue:178` never rejects. Today the affordance survives only
via `ensureStripeReady()`'s `stripeUnavailable.value = true` at `:111`.

That matters because the two fixes **compose badly**:

- AC1.1's suggested shape is *"move the `stripeUnavailable.value = false` to after the `await Promise.all`"*.
- AC1.2 option (b) is *"null `this.stripeConfig` in the store's `catch`"*.

Together, on a **failed** retry: the `await` resolves (no rejection), `stripeUnavailable` is
cleared → `showForm` (`:101-103`) flips true → the card form renders → the `watch` at `:155-158`
fires `mountCardElement()` → `ensureStripeReady()` reads the now-nulled key and re-raises
`stripeUnavailable`. Net result is correct but the unavailable block flickers away and back, and
`:186`'s `if (showForm.value)` becomes order-dependent with the watcher job.

AC1.2 **option (a)** — detect `paymentStore.error.stripeConfig` after the `await` and raise
`stripeUnavailable` there — is the only shape that composes cleanly with AC1.1, because it makes
the raise happen *before* `showForm` is ever allowed to flip.

**Recommendation:** collapse AC1.1 + AC1.2 into one fix with one prescribed shape (option (a)),
delete the reference to `:179-181` as the affordance mechanism, and state that the dead `catch`
becomes a live branch (or is removed) as part of it. Leaving them as two independent "choose the
minimal shape" ACs invites a combination that regresses the `deferred-103` AC9 affordance.

---

### 6. AC4.1 — the prescribed return value can leak a *different* account's id

**The AC's fix:**
`return requestGeneration === selfPlayerIdGeneration ? profile.id : selfPlayerId.value`,
justified as *"resolving with the current cached value, which a concurrent `resetSelfPlayerId` set
to `null`, is correct"*.

`selfPlayerId.value` is a **ref read at resolution time**, not at reset time. Sequence:

1. Account A's `fetchSelfPlayerId()` is in flight (`playerStore.js:32-46`).
2. `resetSelfPlayerId()` fires (`:60-64`) — `selfPlayerId.value = null`, `selfPlayerIdRequest = null`, `selfPlayerIdGeneration++`.
3. Account B calls `fetchSelfPlayerId()`; `selfPlayerIdRequest` is null so a **new** request starts
   under the new generation.
4. B's request resolves → `selfPlayerId.value = <B's id>` (`:39-41`).
5. A's slow chain finally resolves → generation mismatch → returns `selfPlayerId.value` = **B's id**.

A's caller (`BookingRequestPage.vue:627`, `MainLayout.vue:355`) then holds another account's player
id — a *worse* outcome than the residual the AC is closing, and precisely the
cross-account misattribution `deferred-43` guarded against.

The AC's own spec instruction compounds it: *"assert the superseded call resolves with `null` (or
the post-reset cached value)"*. Those two only coincide when nothing repopulated the ref, so the
prescribed implementation and the prescribed assertion can disagree — the test is under-specified
and would be order-dependent.

**Returning a literal `null` is safe downstream** — I checked both consumers:

- `BookingRequestPage.vue:297` — `canSubmit` gates on `!!playerId.value`; `:544` re-checks
  `if (!playerId.value) return`. A null id disables submit rather than submitting a null.
- `MainLayout.vue:289` — only builds a nav link, guarded by `selfPlayerId.value ? … : null`.

**Recommendation:** `return requestGeneration === selfPlayerIdGeneration ? profile.id : null`, and
make the flipped spec assert exactly `null`.

---

### 7. AC11 — the `${DC} up -d` bound is unsafe, two calls are missed, and the new warnings get swallowed

**(a) Bounding the ERR-trap `${DC} up -d` is the wrong move.**
`restore-from-volume-backup.sh:150-154`:

```bash
restore_failed() {
  err "restore step failed — restarting services with the data currently on disk …"
  ${DC} up -d
}
trap restore_failed ERR
```

The script runs under `set -euo pipefail` (`:7`). Wrapping that `up -d` means a timeout returns 124
**from inside the ERR trap**, and the script exits with the stack half-started — the opposite of
what the trap exists for. Worse: `timeout` kills the `docker compose` **CLI**, not the daemon's
work, so a "bounded" `up -d` leaves an indeterminate, partially-orchestrated stack with no
completion signal. The AC's justification ("both are already survivable via the existing recovery /
ERR-trap path") does not apply to the recovery path itself.

**(b) Two unbounded calls are missing from the enumeration.** The AC lists "`docker image inspect`
×2, `aws s3 cp`, the two `${DC} up -d`". Also unbounded, also daemon round-trips, also inside the
outage window:

- `${DC} config --format json` — `restore-from-volume-backup.sh:83` and `provision.sh:152`, the
  **first** call in `image_runtime_uid()`, so a wedged daemon hangs there before any of the
  AC's targets is reached.
- `${DC} down` — `restore-from-volume-backup.sh:145`, the call that *opens* the outage window.

**(c) The new WARNs will be discarded by the existing redirections.** `run_bounded` reports a
timeout via `log … >&2` (`:67`). But the call sites the AC wants wrapped already redirect:

```bash
docker image inspect "$img" >/dev/null 2>&1 \                    # :87 — swallows fd1 AND fd2
user=$(docker image inspect --format '…' "$img" 2>/dev/null)     # :89 — swallows fd2
```

Wrapping the command puts `run_bounded`'s stderr *inside* those redirections. The
"distinguish a wedged daemon from a normal probe failure" goal — the AC's whole point — is
defeated unless the redirection is moved onto the inner `docker` command.

**(d) The WARN will not identify which probe timed out.** `run_bounded` sets
`local what="$1"` *after* `shift` (`:63-64`), so `what` is the **command name**. Every new call
site is `docker`, giving four indistinguishable `'docker' exceeded …` warnings.

**(e) `--cli-read-timeout` / `--cli-connect-timeout` do not bound the download.** They bound
per-request stalls, and they interact multiplicatively with the AWS CLI's own retry count and the
archive's part count. The AC presents them as the bound for `aws s3 cp` at `:158`; a `timeout`
wrapper (consistent with `run_bounded`) is the only thing that bounds total wall time. The AC's
comment update ("reflect the newly-bounded calls") would overstate what was achieved.

**Recommendation:** drop the `${DC} up -d` bounding entirely (or bound only the *final* `up -d` at
`:208`, never the trap's at `:152`, and say why in a comment). Add `${DC} config` and `${DC} down`
to the list or state explicitly why they are out of scope. Specify that the `>/dev/null 2>&1` /
`2>/dev/null` redirections move onto the inner `docker` invocation. Pass a descriptive label to
`run_bounded` rather than relying on `$1`-after-shift.

*Credit where due:* AC11's line cites are the most accurate in the story — `restore:81/87/89/152/158/208`
and `provision:150/156/158` all check out exactly.

---

## Medium

### 8. AC5.1 — option (b) introduces a stale-result regression

The AC offers *"(a) delete the `batchId` entry in the `catch`"* or *"(b) move the
`setBatchAcceptResult(batchId, null)` seed to after a successful `acceptAllBatch` resolves (it
exists to reserve the slot in insertion order …)"* as equivalents.

The seed's real job is not slot reservation — `setBatchAcceptResult` (`booking.store.js:594-603`)
already does `delete next[batchId]; next[batchId] = value` to move the key to most-recent on every
write, so insertion order is handled regardless. The seed at `:608` **clears the previous
attempt's result**. Under option (b): coach clicks accept-all → partial success → results rendered;
clicks again → this attempt **fails** → the earlier attempt's results are still in the map and keep
rendering as if current. Option (a) has no such hole.

The AC's verification instruction — *"verify nothing reads the `null` placeholder between `:608`
and `:616`"* — points at the wrong window. The risk is what is read **after a failure**, not
between the seed and the success write.

**Recommendation:** mandate option (a); drop option (b) or add the stale-result caveat.

### 9. AC5.2 — "nullish response → empty + success" is a contract decision asserted without justification

The AC's fix `const res = (await getCoachBookingRequests()) ?? {}` does more than avoid a
`TypeError`. With it, a `204` / unwrapped-empty-body response:

- sets `coachBookingRequests.value = []` and `coachBatchGroups.value = []` (`:395-396`);
- **returns `true`** (`:415`), so per the CONTRACT block at `:358-368` every caller treats the
  refresh as successful and the `deferred-31` AC1 stale-list warnings stay silent;
- and the prune at `:409-413` sees an empty `visibleBatchIds`, so **every**
  `batchAcceptResultsByBatch` entry is dropped.

That is a coach's entire request list silently blanking and reporting success. There is no
documented `204` on this endpoint; the realistic trigger is an unexpected body (proxy error page,
interceptor edge) — which is closer to an error than to "no data".

**Recommendation:** the AC should state which contract it is choosing and why. Treating a nullish
`res` as an error with a distinguishable classification (rather than as an empty success) is at
least as defensible and preserves the stale-list warning. If "empty + success" is genuinely wanted,
say so and note the prune side-effect.

### 10. AC8.1 — the partial-row predicate leaves the same silent discard for `0`, negative, and label-only rows

The AC defines a partial row as *"exactly one of `sessionCount` / `totalPrice` set"*. The filter it
guards is `.filter((p) => p.sessionCount > 0 && p.totalPrice > 0)` (`ProfileBuilderStep3.vue:151`).
Rows that are fully "set" under the AC's predicate but still silently dropped by the filter:

| row | AC predicate says | filter does | user sees |
|---|---|---|---|
| `{sessionCount: 5, totalPrice: 0}` | both set → fine | dropped | nothing |
| `{sessionCount: 0, totalPrice: 20}` | both set → fine | dropped | nothing |
| `{sessionCount: -1, totalPrice: 20}` | both set → fine | dropped | nothing |
| `{sessionCount: null, totalPrice: null, label: 'Starter'}` | "all-null, plausibly intentional" | dropped | nothing |

The last is not the intentional-empty row the AC carves out — the coach typed a label. Note also
that neither `q-input` at `:42-48` / `:51-58` carries a `:rules` (unlike `perSessionPrice` at
`:10`), so there is no existing validation to hang the message on.

**Recommendation:** define the predicate as *"any field on the row was touched, but the row would
not survive the `> 0` filter"*, and add the `:rules` the `q-form` block would need for shape (a).

### 11. AC8.2 — the premise is unreachable at HEAD

The AC states a string `sessionDurationMinutes` *"from hydration (`'60'`)"* fails the
`DURATION_CHOICES.includes(current)` check at `:131`, appends a duplicate synthetic option, and
`emit-value` submits the string.

There is no hydration path. `ProfileBuilderStep3.vue:104-112`:

```js
defineProps({ loading: Boolean })
const form = reactive({ perSessionPrice: null, sessionDurationMinutes: null, sessionPacks: [] })
```

The component owns its form, takes only a `loading` prop, and its **sole** consumer is
`CoachProfileBuilderPlaceholderPage.vue:64`. The adjacent code comment says exactly this
(`:117-118`: *"this screen is create-only today (`form.sessionDurationMinutes` always starts null)"*).
Every `durationOptions` entry carries a numeric or `null` `value` (`:123-129`), so `'60'` cannot
arise. The AC inherits this premise verbatim from the ledger bullet's "Related:" clause
(`deferred-work.md:1887-1889`) without re-checking it — while the story header asserts every cite
was re-verified against HEAD.

Also internally inconsistent: the fix is *"coerce `current` with `Number()` **and/or** normalise
`form.sessionDurationMinutes` on hydration"*, but the mutation check requires the assertion
*"…and submits the number"*, which only the second (optional) half delivers — coercing the
`includes` argument alone leaves `emit-value` emitting whatever is in the form.

**Recommendation:** re-scope to explicit defensive hardening, matching the `deferred-63` AC7
framing already in the file; drop the "submits the number" clause or make the normalisation
mandatory.

### 12. AC15 — the baseline figures are wrong, which is the exact error the AC warns about

AC15 says: *"**Re-run `wc -l` before writing the line-count figures** — the `deferred-108` pass
mis-recorded them and its own code review flagged it."* The story's own "Files being modified" table
then records:

> `deferred-work.md` | post-`deferred-108` state, **1959 lines** | … | `[DECIDED]` (**31**) / `[DISMISSED]` (**33**)

Measured at `bbad7938`:

| figure | story | actual |
|---|---|---|
| `wc -l` | 1959 | **1991** |
| `[DECIDED` occurrences (body, excl. the trailing audit block's own prose) | 31 | **33** |
| `[DISMISSED` occurrences (same basis) | 33 | **34** |
| `[DECIDED` / `[DISMISSED` across the whole file | — | 35 / 35 |

The source of the error is visible in the file itself. The previous audit block at
`deferred-work.md:1990-1991` reads:

> Line count: 1960 pre-edit → 1959 after the bullet deletion → 1991 with this block appended

The story copied the **intermediate** figure. The `31` / `33` are likewise copied from that block's
claims (`:1988`), which do not match the file either.

All three numbers are the baselines AC15's reconstruction check compares against
("`[DECIDED]` count delta; `[DISMISSED]` count unchanged"), so the check as specified cannot pass
honestly.

**Recommendation:** correct the table to 1991 lines, and either state the tag counts from a fresh
`grep -o … | wc -l` or specify the counting basis (occurrences vs. lines; whether the audit-block
prose mentions count).

*Minor, same AC:* AC15's "Leave untouched" bullet contains an unresolved self-correction
(*"— **wait**: it is in the same `deferred-108` CR section …"*) and a rhetorical question
(*"the `deferred-108` CR section keeps … `skillars-7-1` D4 references? no — `:1891` stays"*).
That is drafting noise in an AC whose whole value is mechanical precision; the dev agent should not
have to reconstruct the intent.

### 13. AC12.2 — the prescribed test seam does not exist, and the assertion target does not match the setup

**(a) There is no injectable `JavaMailSender` bean.** The AC prescribes
*"a real `MailManager` wired to a mock `JavaMailSender` … `@MockitoBean JavaMailSender`"*.
`MailService.java:41` obtains its sender from `senderProvider.nextSender()`, and
`MailSenderProvider.java:20,28-29` builds `List<JavaMailSenderImpl>` with `new JavaMailSenderImpl()`
from config. `@MockitoBean JavaMailSender` will replace nothing. The seam is `SenderProvider`
(`SenderProvider.java:6`) or `MailService` itself.

**(b) Assertion target ≠ setup.** The AC says *"Assert on the outbox-row state, not just the log"*
while describing a setup that calls `sendAdminAlertSync` directly. That path creates no outbox row —
the row lives with `ModerationAdminAlertOutboxHandler` (`:57-72`) and its drain. Asserting
retain-vs-delete requires driving the handler through the actual drain, not the sender in isolation.
(Asserting on the **envelope** row — `FAILED` + `isRetry` — *is* achievable directly, and is
probably what the AC means; it should say so.)

**(c) Retry and circuit-breaker state.** `MailManager.sendEmailSync` (`:64-104`) nests a
`retryTemplate.execute` (`:81`) inside `circuitBreaker.run` (`:76`) on a breaker named
`"emailService"` (`:72`). The retryable case will burn its full retry budget (test duration), and
both AC cases share breaker state within one Spring context, so case ordering can flip the second
case's outcome. Worth an explicit note in the AC (reset the breaker, or use distinct contexts).

*Verified sound in the same AC:* `isRetryable` (`MailManager.java:137-151`) returns `true` unless a
`NON_REPAIRABLE_ERRORS` type appears at one of three bounded depths, so the AC's transient/permanent
split is achievable in principle; and `sendEmailSync` is `@Transactional(REQUIRES_NEW)` (`:64`), so
the `FAILED` row really is committed and visible to the `findBySendId` read-back at
`VideoModerationEmailListener.java:94`.

---

## Low / accuracy

### 14. AC14.2 — the target runbook section is topic-scoped and its own text would be falsified

The AC says *"`docs/deployment/runbook.md` has a **'Pre-production release gate'** section (`:581+`)…
Add a short subsection (mirroring the existing 'outstanding migration rewrites' one)"*.

There is only **one** such section, and it is not generic — `runbook.md:579`:

```
## Pre-production release gate: outstanding migration rewrites
```

Its body says *"Before the first production deploy, **all four items below must be closed**"*
(`:588`) over a four-row migration table (`:590-595`), and its **Verification** line (`:601-603`) is
`MigrationConventionLintTest`. A queued-webhook-drain item is not a migration rewrite; adding it as
a row or subsection contradicts the heading, the "four items" sentence, and the verification method.

**Recommendation:** add a sibling `##` section (e.g. `## Pre-production release gate: queued webhook
events`) with its own owner/trigger line, or rename the existing heading to a generic one and
promote "outstanding migration rewrites" to a `###`. Also correct the cite to `:579`.

### 15. AC9 — only one of the two `booking-id` call sites is analysed

The AC asserts *"every caller passes a **numeric** id (`ParentBookingsPage.vue:134` …)"*. There are
two callers that pass `booking-id`:

- `ParentBookingsPage.vue:134` — `:booking-id="booking.id"` (the one the AC covers)
- `CoachCommandCenterPage.vue:95` — `:booking-id="booking.bookingId"` (unanalysed)

`src/pages/coach/__tests__/` does not exist, so the AC's "no `Invalid prop` warning on render"
assertion covers `ParentBookingsPage` only. The widening to `[String, Number]` is correct either
way; the AC should either check the coach payload's type or scope its claim to the one call site.

*Verified sound:* the AC's supporting claim that `useBookingSse` only interpolates the id into a URL
is exactly right — `booking.store.js:66`, `new EventSource("/api/bookings/<id>/events", …)`,
no key lookup or string comparison anywhere in `:53-100`. And the `deferred-108` NOTE it asks to
update really is there (`ParentBookingsPageSpec.js:49`, fixture `id: 42` at `:55`).

### 16. AC1.4 — misses the sibling dead-catch and the stale-saved-card path in the same function

AC1.4 enumerates three branches of `submit()`. Two more things in `PaymentMethodCard.vue:189-214`
belong in the same sweep:

- `:202-206` — the inner `try { await paymentStore.fetchSavedPaymentMethod() } catch { … }` is
  **dead for the same reason AC1.2 identifies**: the store action swallows and resolves
  (`payment.store.js:249-259`). AC1.2 catches this pattern at `:178-181` but not here.
- Consequence: after a successful save whose refresh silently fails, `savedPaymentMethod` stays
  stale/null, so `showForm` (`:101-103`) keeps the entry form mounted even though the card saved.

Related, in AC1.2's own scope: the fix is framed entirely around `error.stripeConfig`. A
`fetchSavedPaymentMethod` failure still resolves "successfully" through `loadStripeConfig`, so a
user who *has* a card on file is shown the add-card prompt (`:45-47`) with no indication anything
failed.

### 17. AC3.1 — "parity" delivers one of `useSession`'s two `rint` clears

`useSession.js:70-108` clears `rint` **twice**, with a documented `deferred-91` code-review
rationale for each:

- `:82` — **before** the race, so sibling tabs enter `computeTimeUntilExpiry`'s fast-teardown branch
  immediately rather than after the up-to-`LOGOUT_BACKEND_WAIT_MS` (3000 ms) window;
- `:104` — **after** the race, because *"every authenticated response rewrites 'rint' with path=/
  (JwtManagerImpl), so any request already in flight when the pre-race clear ran … can land
  afterwards and re-establish the cookie"*.

AC3.1 specifies one clear, *"alongside the existing `deleteUserCookie()` (`:331-333`)"* — i.e.
post-race only. Both cases the pre-race clear exists for stay open.

Separately, `useSession.handleLogout` calls `stopSessionMonitoring()` **first** (`:71`), whereas
`MainLayout` reaches `destroySession()` only after the logout await (`:338`). AC3.1's instruction to
*"preserve the ordering `logout → resetSelfPlayerId → destroySession → deleteUserCookie → router.push`"*
locks in that divergence while the AC's stated goal is to remove it. Say explicitly which parts of
the sequence are being unified and which are deliberately left different.

### 18. AC3.2 — the mutation check is only half-sensitive

The check is *"remove the `try/catch` around `changeLanguage`'s `setItem` → the new '`setItem`
throwing does not prevent `locale.value` update **or** the `lang` cookie clear' assertion fails"*.
`MainLayout.vue:303-311`:

```js
function changeLanguage(lang) {
  locale.value = lang          // :304 — already before the throw
  localStorage.setItem('locale', lang)   // :305
  …
  document.cookie = 'lang=; Max-Age=0; path=/'   // :310
}
```

`locale.value` is assigned at `:304`, *before* `setItem`, so that half of the assertion passes with
or without the guard. Only the cookie-clear half is mutation-sensitive. Reword so the RED signal is
unambiguous.

*Scope note (not a defect):* `src/boot/theme.js:28` and `:47` carry the same unguarded
`localStorage` and are reached from this component via `onToggleTheme` (`:322`) and
`onStorageThemeChange` (`:327`). Out of scope for AC3.2, but worth one sentence so the AC is not
later read as "MainLayout is now storage-safe".

### 19. AC13 — the acceptance evidence contradicts Task 19

AC13's **Test** is: *"this story's own PR is the proof — created with `--label frontend-tests`, the
`frontend-unit` check fires **on `opened`**"*. Task 19 says: *"AC13 is *in* this PR, so the label
still needs adding post-create for the **first** run; a follow-up push fires `synchronize`."*

Both cannot hold. Whichever is right, AC13 as written has no verifiable acceptance criterion inside
its own PR — it needs either a stated deferral ("verified on the next labelled PR") or a
determination of which trigger actually fires.

*Not introduced here, but adjacent:* `unlabeled` is still absent from `types:` (`:22`), so removing
the label leaves the last green result standing as the visible check state.

### 20. "Re-verified against HEAD" — several cites are wrong

The story header states *"Every line/function cite below was re-verified against HEAD `bbad7938`
during story creation."* Most check out. These do not:

| AC | story says | HEAD |
|---|---|---|
| AC2.1 | skew guard at `sessionManager.js:141`, *"ledger says `:140`"* | **`:140`** — the ledger was right; the "correction" introduced the error |
| AC2.2 | `await sessionApi.refresh()` at `:262`; `recordActivity()`+`refreshExpiryState()` at `:264-265` | `:259`; `:263-264` |
| AC1.3 | *"the adjacent 'Replace card' button (`:62`)"* | Replace-card is `:34-41`; **`:62` is the Cancel button** in the edit form |
| AC13 | job `if` at `:31-33`; trigger paragraph at `:16-18` | `:29-31`; `:14-16` |
| AC14.2 | pre-production gate at `:581+` | `:579` |
| AC6 | `slotRows` at `:456-471`, available branch `:458-461` | `:456-472`, `:457-462` |
| Files table | `deferred-work.md` 1959 lines / 31 `[DECIDED]` / 33 `[DISMISSED]` | 1991 / 33 / 34 (see finding 12) |

Individually trivial; collectively they undercut the header's guarantee, which is what Task 1
("Re-diff every cited line against HEAD") exists to backstop. Keep Task 1 and treat every cite as
advisory.

---

## Risk note (not a defect)

This is the largest **production-code** change in the `deferred-10x` series — nine `.vue`/store
files, plus `payment.store.js` and `sessionManager.js`, both of which sit on the auth and payment
critical paths. Its only automated frontend validation is `frontend-unit-tests.yml`, which owner
decisions D2/D6 keep **opt-in and non-gating**, and which AC13 touches (ergonomics) without
promoting. `mvn verify` never invokes Vitest (`frontend-unit-tests.yml:6-7`). So the sweep's whole
safety net depends on a human remembering a label. Worth an explicit acknowledgement in the story
(or a one-off "make it required for this PR only" carve-out) rather than leaving it implicit.

---

## Verified sound — checked and found correct

Recording these so the absence of a finding is a result, not a gap:

- **AC1.1** — the retry no-op is real. `stripeUnavailable.value = false` at `:176` flips `showForm`
  (`:101-103`), the `watch` at `:155-158` queues `mountCardElement()`, `ensureStripeReady()` reads
  the still-null key at `:109` and re-raises at `:111`, so `:186`'s `if (showForm.value)` is false
  when the refetch resolves. Confirmed by trace.
- **AC1.2** — both store actions swallow and resolve (`payment.store.js:238-248`, `:249-259`);
  `stripeConfig` is never nulled on error. `stripeConfig` has exactly one consumer
  (`PaymentMethodCard.vue:109,195`), so option (b) is grep-safe as the AC claims.
- **AC1.3** — `payment.card.detailsUnavailable` exists in all three bundles
  (`en-US:1224`, `de-DE:1110`, `fr-FR:991`), so the "add the key if missing" branch is a no-op.
- **AC3.3** — `MainLayoutSpec.js:40` really does default every mount to `{ role: 'PARENT' }`, and
  the helper already takes an override, so the PLAYER cases are cheap.
- **AC4.2** — the `.finally` reference-clear at `playerStore.js:53` is the only thing preventing a
  poisoned in-flight cache on a real rejection; the mutation check is correctly targeted.
- **AC5.1 core** — `setBatchAcceptResult(batchId, null)` at `:608` runs before the `try`, and
  `loadCoachBookingRequests()` at `:622` (the only pruner) is unreachable on the throw path at `:626`.
- **AC6** — `slotRows`' available branch has no NaN filter while `ownBlockingBookings` filters at
  `:437` and `:443`; the `own` branch is already protected by that upstream filter, so scoping the
  fix to `available` is right.
- **AC9 core** — the prop really is `type: String` (`BookingStateChip.vue:12`), and
  `useBookingSse` only interpolates (`booking.store.js:66`), so `[String, Number]` needs no
  companion change.
- **AC10.2** — `ConfigBoundsEnumCoverageTest.java:32` does derive via `toLowerCase(Locale.ROOT)`
  while the sibling `VideoType` test at `:50-55` uses a manual camelCase `switch`. The drift risk is
  real (if hypothetical).
- **AC11 cites** — `restore-from-volume-backup.sh:81/87/89/152/158/208` and
  `provision.sh:150/156/158` are all exact. `run_bounded`'s existing `>&2` at `:67` is already
  correct for the `uid=$(…)` case (the concern is only the *new* call sites' outer redirections).
- **AC12 cites** — every `VideoModerationEmailListener.java` cite in the AC and in D5
  (`:46-57`, `:83-89`, `:86-88`, `:95-106`, `:107-108`, `:112-117`) is exact, and the "materially
  stale" re-scope of the `deferred-94` AC15/AC16 bullet is correct: the blank-recipient path really
  is fully handled at HEAD.
- **AC14 premise** — `VideoLifecycleService.java:77-83` does throw
  `TerminalStateViolationException` and increment `video.moderation.bypass` on the plain
  `PROCESSING→READY` path, exactly as described.
- **Scope arithmetic** — the `deferred-108` CR section (`deferred-work.md:1802-1959`) holds 21
  bullets; 16 are frontend production defects, matching the story's "~16" claim. All ten
  `deferred-108` spec files named in the Global Conventions exist at the paths given.

---

## Recommended disposition

**Changes required.** Suggested order:

1. **Rewrite AC7** — remove the arithmetic change; re-scope to pinning current behaviour + the
   `RescheduleService:176-186` rationale. *(blocker)*
2. **Rewrite AC12.1** — require the explicit branch split and the missing success-path assertion.
   *(blocker)*
3. **Rewrite AC10** — correct the premise and the fail-fast language, drop or reword AC10.4, and
   file the `resolveTierKey` player-tier gap as its own ledger bullet. *(blocker)*
4. **Amend AC2.2** — condition on `hasSeenRintThisTab()`, pin ordering vs `refreshExpiryState()`,
   use a future-ness predicate, add the legacy-path spec case.
5. **Merge AC1.1 + AC1.2** into one fix with option (a) prescribed.
6. **Amend AC4.1** — return literal `null`; align the flipped spec assertion.
7. **Amend AC11** — drop the ERR-trap `${DC} up -d` bound, add or explicitly exclude
   `${DC} config` / `${DC} down`, move the redirections, label the `run_bounded` calls.
8. **Tighten** AC5.1 (mandate option (a)), AC5.2 (state the contract), AC8.1 (predicate),
   AC8.2 (defensive-only), AC12.2 (correct seam), AC14.2 (new sibling section).
9. **Correct AC15's baselines** to 1991 / 33 / 34 and clean up its self-corrections.
10. **Keep Task 1** ("re-diff every cited line") — finding 20 shows it is load-bearing.
