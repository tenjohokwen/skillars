# Story Review: skillars-deferred-59

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-59-radar-composite-overflow-guard-drill-video-ref-persist-fix-availability-timezone-diagnostics-and-ssh-firewall-rule-hygiene.md`

Method: every AC's "current shape" snippet was diffed against the actual source file at HEAD, and every
factual claim about test coverage, call sites, and unique-writer status was re-verified with `grep` rather
than trusted from the story text. Findings below survived that verification; several initial suspicions
(proxy/`getReferenceById` write path for `DrillVideoRef`, a second `.save()` call site, a possible narrowing
bug in `Math.round`) did not and are omitted as false leads.

## Findings

### 1. AC5 — delete-and-recreate opens a window with *zero* cloud firewall protection (Medium-High)

The fix replaces the old "delete 3 known rules, re-add 3 rules" approach with `hcloud firewall delete
"${FIREWALL_NAME}"` followed by `hcloud firewall create`. The project's own `deploy/firewall/README.md`
documents the current model precisely: *"All other inbound — Block (Hetzner implicit deny)"*. That implicit
deny is a property of the firewall **object being attached to the server** — it does not exist independent
of the firewall. Between `delete` and the point later in the script where `apply-to-server` re-attaches the
freshly-created firewall, the server has no cloud-level firewall at all, meaning every port is reachable from
the internet (not just 80/443/22 — anything else listening on the box: metrics endpoints, a directly-exposed
DB port, etc.), not merely the SSH port the original bug was about. The story's own AC5 task list correctly
flags needing to confirm whether `delete` requires detaching an attached firewall first (Task 5.1) — but even
in the best case (no detach required, `create`+`apply-to-server` execute within seconds), this trades a real
but narrow bug (stale SSH CIDR *widens* SSH access to two IPs) for a broader, if brief, one (all ports open
to all IPs). This tradeoff is never named or weighed anywhere in AC5's rationale, and the "Why not track the
previous CIDR" note only argues against a *different* alternative (a state file), not against the one that
most directly avoids the exposure window.

**Concretely worth checking before implementing**: Hetzner's Cloud API exposes a "Set Rules" action
(`POST /firewalls/{id}/actions/set_rules`), which the `hcloud` CLI has historically wrapped as something like
`hcloud firewall replace-rules <name> --rules-file <file>` — an atomic, in-place rule replacement on the
*existing* firewall object that never detaches it from the server and never creates a window with no
firewall at all. If the installed `hcloud` CLI version supports this, it would close AC5's actual gap (no
per-run knowledge of the prior CIDR required — the whole rule set is simply replaced) without introducing the
delete/recreate exposure window, and without needing Task 5.1's investigation into attached-firewall-delete
semantics at all. (Not verified against a live `hcloud` install in this environment — the CLI isn't
available here — so this is a lead to check, not a confirmed fact, but it's a materially safer shape for the
same fix if it holds.)

A second, independent consequence of delete-and-recreate not mentioned in the story: it discards **any rule
or setting on that firewall not managed by this script** — e.g., a rule an operator added by hand outside
this tooling (a monitoring allowlist, a temporary debug port) would silently vanish on the next run, whereas
today's per-rule-delete approach only ever touches the 3 rules it explicitly names. The recreated firewall
also gets a new Hetzner-assigned ID, which would break anything that references the firewall by ID rather
than by name (unlikely in this repo, since the script always looks it up by name, but worth a one-line note
if any external tooling/dashboard bookmark relies on the ID).

### 2. AC1 — "WARN log" mischaracterizes the actual catch block (Low, cosmetic)

AC1's rationale says the new `ArithmeticException` "surfaces as the existing 'composite recalculation
failed... composite is now stale' **WARN** log." The actual code (`RadarCompositeCalculationService.java:89-92`)
uses `log.error(...)`, not `log.warn(...)`. This doesn't change what to implement (the catch block is
correctly left untouched either way) but it's a factual inaccuracy in the story text — if a Dev Agent
transcribes this claim into a test assertion or an operator-facing runbook, it would assert the wrong log
level.

### 3. AC3 — test-coverage claim overstates what exists (Low)

AC3 says "`BookingServiceTest` already has unit coverage calling `isSlotWithinAvailabilityWindow` directly"
and gives a `grep -n "isSlotWithinAvailabilityWindow" ...BookingServiceTest.java` command to confirm the
exact test names. Running that exact command returns **zero matches** — the method is package-private and
current tests exercise it only *indirectly*, through `createBookingRequest(...)` with mocked
`CoachAvailabilityWindowRepository` results (e.g. `createBookingRequest_slotOutsideAvailabilityWindows_throwsOperationNotAllowedException`
at line 310). There is no existing test that calls the method directly, and none that seeds an
invalid-timezone window. This doesn't block AC3 — the story already hedges with "confirm exact existing test
names at implementation time" — but the specific grep it hands the implementer for that confirmation returns
nothing, which could read as "coverage must have moved/renamed" rather than "there was never direct coverage
of this method to begin with." Worth a one-line correction so the implementer isn't sent looking for
non-existent tests.

## Verified as accurate (no finding)

- **AC1**: The three `(int)` casts are exactly where and what the story says
  (`RadarCompositeCalculationService.java:70,74,78`), sourced from a native-query `long` via
  `((Number) row[3]).longValue()` at line 56 — `Math.round` before `Math.toIntExact` is the correct sequence
  to recover the exact integral value before narrowing. No existing test file for this service (confirmed via
  `find`), matching the story's "confirm at implementation time" hedge.
- **AC2**: `drillVideoRefRepository.save(cloneRef)` in `DrillLibraryService.cloneDrill` is confirmed the
  *only* `.save()` call against this repository anywhere in the codebase (`grep -rn
  "drillVideoRefRepository\."` across `src/main`) — every other write goes through `@Modifying`
  JPQL/native-query methods (`incrementRefCount`, `setVideoId`, `clearVideoId`, `decrementRefCount`,
  `upsertVideoId`) that bypass the entity lifecycle entirely, so `Persistable`'s `@PostLoad`-driven
  `isNew`-flip has no other write path to interact with or break — including no `getReferenceById`/proxy
  usage that could dodge `@PostLoad`. `DrillVideoRefTest` does not yet exist (confirmed via `find`), matching
  the story's hedge.
- **AC3**: All four call sites of `isSlotWithinAvailabilityWindow` (`BookingService`, `BookingBatchService`,
  `RescheduleService` ×2, `BookingDuplicationService`) do share the one helper, confirming the Dev Notes claim
  that a one-file change reaches all of them. The proposed `validWindowsEvaluated == 0` gate is correctly
  scoped to the "every window was misconfigured" case only (a coach with a mix of valid and invalid windows
  still gets per-window WARNs from the existing catch block, just not the new summary line) — this is a
  reasonable, intentional scope limit stated in the AC, not an oversight. `CoachAvailabilityWindow.getCoachId()`
  exists as claimed.
- **AC4**: Current file content matches the story's "current shape" snippet exactly (line-for-line at
  `PaymentMethodCard.vue:111-135`). The generation-counter fix is correctly threaded through both async-gap
  checkpoints in `mountCardElement` and both call sites that mutate `cardElement` state
  (`unmountCardElement`, and the `onBeforeUnmount` hook that calls it) — a toggle-to-`false` reliably
  invalidates an in-flight toggle-to-`true`, and vice versa via the synchronous guard already in place
  (`if (!cardElementRef.value || cardElement) return`). No automated frontend test harness exists in this
  repo (`package.json`'s `test` script is a no-op placeholder), confirming AC4's manual-verification-only
  plan is this project's actual constraint, not an assumption.
- **AC5**: The claim that "port 80/443 delete-rule calls correctly target `0.0.0.0/0`/`::/0` ... so they are
  not part of this bug — only the port-22 delete-rule call is wrong" is accurate against the live script
  (`deploy/firewall/apply-firewall.sh:42-47`). Only the resolution approach (Finding 1) is in question, not
  the diagnosis of the underlying bug.
- **`SessionPackPurchase.expiresAt` drop** (noted in "Examined and deliberately not picked up"): plausible on
  its face and not independently re-verified line-by-line here, but the story's own reasoning (three
  legitimate call sites write the field; the ledger item never proposes a fix beyond naming it a footgun) is
  internally consistent and the right call to leave alone rather than force a fix.

## Summary

Four of five ACs (AC1–AC4) are well-verified against the current codebase and implementable as written, with
two small textual corrections worth making before handoff (Findings 2 and 3 — neither blocks implementation).
AC5 is the one that needs a real second look before implementation: the proposed delete-and-recreate fix for
the stale-SSH-rule bug trades a narrow, real problem for a broader (if brief) one — a window with no cloud
firewall at all — and doesn't weigh that tradeoff or check whether `hcloud`'s CLI offers an atomic
rules-replacement path that would avoid it entirely (Finding 1). Recommend resolving Finding 1 — at minimum
by explicitly deciding "exposure window is acceptable because X" or by confirming/ruling out
`replace-rules`-style atomic update — before AC5 is implemented as currently written.
