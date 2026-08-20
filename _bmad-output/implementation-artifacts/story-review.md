# Story Review: Deferred-46 — Self-Player-Id Dedup Reset Guard & Drill-Request Sequencing Guard Extraction

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-46-self-player-id-dedup-reset-guard-and-drill-request-sequencing-guard-extraction.md`

Method: every factual claim in the story (line numbers, "no other change needed", call-site lists, the
AC3 ledger-tag state) was re-verified against the current code on this branch, not trusted from the story's
own prose. Read in full: `src/frontend/src/stores/playerStore.js`, `src/frontend/src/stores/session.store.js`,
`MainLayout.vue`, `App.vue`, `useSession.js`, `DrillLibraryPage.vue`, `SessionBuilderPage.vue`, and the
`deferred-work.md` heading both ACs cite. AC1's and AC2's line numbers, the three `resetSelfPlayerId()` call
sites, the three `fetchSelfPlayerId()` call sites, and AC2's proposed helper body (verified byte-for-byte
against `fetchDrills()`'s current body) all checked out exactly. AC3's two ledger tags are also already
present in `deferred-work.md` verbatim (lines 1620-1621), matching the story's own established precedent
(confirmed by `skillars-deferred-44`'s and `-45`'s own reviews) that these are applied at story-creation time,
not a defect. One real gap was found in AC1's fix, plus one low-severity internal inconsistency in AC1's own
prose.

## Findings

### 1. AC1's fix is incomplete: `fetchSelfPlayerId()`'s `.finally()` unconditionally nulls the module-scoped cache, so a stale generation's settlement can still clobber a newer generation's in-flight request once `resetSelfPlayerId()` also clears it out-of-band

**Severity: Medium (confirmed) — a real, plausible race the AC's own "no other change needed" claim misses; consequence is a duplicated in-flight request, not corrupted data.**

**Where:** `src/frontend/src/stores/playerStore.js:44-46`, unmodified by this story's AC1 as scoped:

```js
.finally(() => {
  selfPlayerIdRequest = null
})
```

This callback clears the module-scoped `selfPlayerIdRequest` **unconditionally** — it never checks whether
`selfPlayerIdRequest` still refers to *this* promise chain before nulling it. Before AC1's fix, that's safe:
the only way `selfPlayerIdRequest` ever becomes falsy again is via this exact `.finally()`, so at most one
promise chain can ever be "the current" one at a time, and its own `.finally()` is always the correct owner of
the clear.

AC1 changes that invariant by making `resetSelfPlayerId()` **also** null `selfPlayerIdRequest`, out-of-band,
while a request may still be in flight. That reopens a window the `.finally()` was never written to handle:

1. Caller A (generation 0) calls `fetchSelfPlayerId()` → creates request R0, `selfPlayerIdRequest = R0`
   (R0's underlying `getMyProfile()` call is slow — the story's own motivating scenario, "logout/relogin
   racing a slow fetch").
2. `resetSelfPlayerId()` fires (logout) → generation becomes 1, and per AC1's fix, `selfPlayerIdRequest = null`.
3. Caller B (generation 1, e.g. a different page's `onMounted` after re-login) calls `fetchSelfPlayerId()` →
   sees `!selfPlayerIdRequest` (true, just reset) → creates a fresh request R1, `selfPlayerIdRequest = R1`.
   This part is exactly what AC1 intends and correctly fixes.
4. R0 (still generation-0, still pending from step 1) now settles — resolve or reject, doesn't matter,
   `.finally()` always runs. Its generation check (`:36`) correctly skips writing `selfPlayerId.value` (0 ≠
   1), but its `.finally()` callback still executes `selfPlayerIdRequest = null` **unconditionally** —
   wiping out the reference to R1, which is still pending, not R0.
5. Caller C (still generation 1 — e.g. a third page, since this store has three independent
   `fetchSelfPlayerId()` call sites: `PlayerHomeRedirectPage.vue`, `CoachPublicProfilePage.vue`,
   `BookingRequestPage.vue`) calls `fetchSelfPlayerId()` before R1 settles → sees `!selfPlayerIdRequest`
   (wrongly true, clobbered by R0's stale `.finally()`) → starts a second, redundant concurrent request R2 for
   the same generation, defeating the dedup cache's entire purpose.

This can't happen in the current (pre-AC1) code, because `resetSelfPlayerId()` never touches
`selfPlayerIdRequest` out-of-band today — it's a window newly opened by AC1's own fix, not a pre-existing gap.
It doesn't corrupt displayed data (R1 and R2 both eventually resolve to the same correct id, both correctly
generation-gated), but it does mean AC1's stated goal — "clearing `selfPlayerIdRequest` inside
`resetSelfPlayerId()` closes both consequences at once, with no change needed anywhere else" and "No other
change to `resetSelfPlayerId()` or `fetchSelfPlayerId()` is needed" — is not quite true: the dedup guarantee
itself can still be broken by a stale request's `.finally()` firing after a newer one has already started,
producing extra `getMyProfile()` network calls (and, if a caller happens to land in the gap right after R0's
throw-on-missing-id fires per `skillars-deferred-45` AC1, an extra spurious rejection surface, though that
part is unlikely to matter in practice since R2 would still resolve correctly for its own caller).

**Recommendation:** either accept this residual race explicitly as an out-of-scope tradeoff (the same way
several other ledger items in this story's own "why only these two" section were left alone with recorded
reasoning), or close it by having the `.finally()` clear the cache only if it still owns the reference, e.g.
capturing the promise in a local before assigning it to the module variable and checking identity:

```js
const request = playerRegistrationApi.getMyProfile()
  .then((profile) => { /* unchanged */ })
  .finally(() => {
    if (selfPlayerIdRequest === request) selfPlayerIdRequest = null
  })
selfPlayerIdRequest = request
```

This is a slightly larger change than AC1's current "one added line" framing (it touches
`fetchSelfPlayerId()`, not just `resetSelfPlayerId()`), so if this is to be fixed within this story, AC1's
Dev Notes claim that "No other change to `resetSelfPlayerId()` or `fetchSelfPlayerId()` is needed" would need
updating either way.

---

### 2. AC1's placement instruction contradicts its own stated justification

**Severity: Low (confirmed) — purely cosmetic; the AC itself says ordering has no functional effect.**

**Where:** AC1's second bullet:

> "...for readability place it after the generation increment, matching the order the three
> module-scoped/ref declarations already appear in at the top of the store (`selfPlayerId`, then
> `selfPlayerIdRequest`, then `selfPlayerIdGeneration`)."

The declared order at the top of the store (`playerStore.js:10-12`) is: `selfPlayerId` → `selfPlayerIdRequest`
→ `selfPlayerIdGeneration`. To actually match that order, the three statements in `resetSelfPlayerId()` would
need to read: `selfPlayerId.value = null`, then `selfPlayerIdRequest = null`, then
`selfPlayerIdGeneration++` — i.e. the new statement placed **before** the generation increment.

But the instruction's literal directive is to place the new statement **after** the generation increment,
which produces the opposite order (`selfPlayerId`, `selfPlayerIdGeneration`, `selfPlayerIdRequest`) — the
reverse of the declaration order the same sentence claims it's matching. The two halves of this instruction
disagree with each other.

Functionally moot either way (the AC's own text says "order does not matter functionally," and both readings
are correct), but a dev following the stated *justification* ("matching declaration order") would write
different code than a dev following the literal *directive* ("after the generation increment") — worth
tightening so the two don't point in different directions.

**Recommendation:** either fix the directive to say "before the generation increment" (matching the stated
declaration-order justification), or fix the justification to describe the order actually being produced
(`selfPlayerId`, `selfPlayerIdGeneration`, `selfPlayerIdRequest`) rather than claiming it mirrors the
declaration order.
