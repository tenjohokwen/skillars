# Senior Dev Review: skillars-deferred-43 (Player Registration OTP Test Coverage & Shared Self-Profile Fetch Caching)

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-43-player-registration-otp-coverage-and-self-profile-fetch-caching.md`

Method: every factual claim was re-verified against current code, not taken on the story's word. Read in
full: `PlayerRegistrationService.java`, `PlayerRegistrationResource.java`, `PlayerRegistrationRequest.java`,
`AgePolicyService.java`, `CoachRegistrationResourceIT.java`, `AbstractIntegrationTest.java`,
`DatabaseResetTestExecutionListener.java`, `V84__player_self_registration.sql`,
`V21__skillars_security_extension.sql`, `playerStore.js`, `CoachPublicProfilePage.vue`,
`BookingRequestPage.vue`, `PlayerHomeRedirectPage.vue`, `MainLayout.vue`, `auth.store.js`,
`src/frontend/eslint.config.js`, and `deferred-work.md`'s D7/D8/D1/generateOtp items. AC1's line-number
citations, the `ROLE_PLAYER` seeding claim, and AC3's two stale-item verifications all checked out exactly.
The problems found are in AC2's scope and self-consistency, plus one factual error in AC1's own rationale
that (harmlessly) doesn't affect the actual instruction given.

---

## Finding 1 (Medium, confirmed): AC2 undercounts the duplication it claims to fix — a third page, `PlayerHomeRedirectPage.vue`, independently calls the same `getMyProfile()` and is left untouched

**Where:** AC2's framing ("`CoachPublicProfilePage.vue` and `BookingRequestPage.vue` each independently call
`playerRegistrationApi.getMyProfile()` on mount for the same logical self-booking-player session") and the
Project Structure Notes' file list, which names only those two `.vue` files as touched.

`grep -rn "getMyProfile" src/frontend/src` returns **three** hits, not two:

```
src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue:16:    const profile = await playerRegistrationApi.getMyProfile()
src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue:310:        const profile = await playerRegistrationApi.getMyProfile()
src/frontend/src/pages/parent/BookingRequestPage.vue:600:      const profile = await playerRegistrationApi.getMyProfile()
```

`PlayerHomeRedirectPage.vue` (lines 14-22) calls `getMyProfile()` on mount to resolve the player's own
profile id and redirect to `/player/locker-room/<id>` (or `/player/profile-builder` on a 404) — the exact
same "resolve this self-booking player's own id" purpose AC2 describes, for the exact same session. It's
not an incidental omission either: `CoachPublicProfilePage.vue:263-265`'s own comment explicitly names this
page as the precedent being followed — *"the player's own player-profile id, via the same GET
`/api/security/players/me` call `PlayerHomeRedirectPage.vue` already uses"* — so whoever wrote AC2's source
material had this file in view and didn't carry it into scope.

Practically: `PlayerHomeRedirectPage.vue` runs on essentially every player login (it's the home redirect), so
it's the *first* of the three calls in a typical session, and after this story ships it will still be an
uncached, un-consolidated call sitting right next to two now-cached ones. The story's own justification for
AC2 — "a minor redundant network round-trip... a shared cache would be a clean follow-up" — applies to this
file at least as much as to the two it names.

**Recommendation:** Either fold `PlayerHomeRedirectPage.vue` into AC2 (replace its `getMyProfile()` call with
`playerStore.fetchSelfPlayerId()` too — same one-line shape, and it would also mean the cache is usually
already warm by the time the player reaches `CoachPublicProfilePage`/`BookingRequestPage`), or explicitly
narrow AC2's own scope statement to acknowledge a third, deliberately-deferred caller exists, so a future
audit doesn't rediscover this as a "new" gap.

---

## Finding 2 (Medium, confirmed): AC2's cache has no invalidation on logout, and this app's logout is an in-SPA navigation, not a full reload — a second player logging in on the same tab can inherit the first player's cached `selfPlayerId`

**Where:** AC2's `fetchSelfPlayerId()` spec — "returns `selfPlayerId.value` immediately if already non-null
(cache hit — no network call)" — with no accompanying reset on logout, and no mention of this edge case
anywhere in Dev Notes (which does cover the symmetric "cache must not persist a failure" case in detail, but
not this one).

`MainLayout.vue:297-301`'s `handleLogout()`:

```js
async function handleLogout() {
  await authStore.logout();
  destroySession();
  deleteUserCookie();
  router.push('/login');
}
```

uses `router.push('/login')` — an SPA route change, not `window.location` — and `authStore.logout()`
(`auth.store.js:43-55`) only clears the cookie and calls the logout API; neither it nor `handleLogout` resets
any Pinia store. Grepping the frontend for a store-reset-on-logout mechanism (`$reset`, a router
`beforeEach` guard, a full-reload redirect) turns up nothing. That means `playerStore` — a singleton for the
life of the SPA — survives a logout/login cycle in the same browser tab.

Today this is latent but self-limiting: `players`/`activePlayerId` (the existing pattern AC2 is told to
mirror) has the same lack-of-reset, but `fetchPlayers()` has no cache-hit branch at all — it unconditionally
re-fetches and overwrites `players.value` on every call (see Finding 4), so the next page that calls it
self-heals regardless of what account was previously logged in. `fetchSelfPlayerId()` as specified is
different by design: it deliberately *skips* the network call once `selfPlayerId.value` is set, specifically
to avoid the round-trip. That means if Player A logs in, visits `CoachPublicProfilePage` or
`BookingRequestPage` (caching Player A's id), logs out, and Player B logs in on the same tab and visits
either page, `fetchSelfPlayerId()` returns Player A's cached id — Player B's own booking-request page would
resolve `playerId` to the wrong player. This isn't a hypothetical UI glitch: `BookingRequestPage.vue:246-249`
feeds this value straight into the `playerId` used to submit the booking request.

**Recommendation:** Either reset `playerStore.selfPlayerId` (and arguably `players`/`activePlayerId` too,
while touching this) in `authStore.logout()`/`handleLogout()`, or scope AC2 to accept this as a known,
pre-existing multi-account-same-tab limitation being extended rather than introduced — but say so explicitly
in Dev Notes rather than leaving it unaddressed, since the consequence here (a booking silently attributed to
the wrong player) is more serious than the "which child is selected" staleness `players`/`activePlayerId`
already tolerates.

---

## Finding 3 (Medium, confirmed): Task 2.4's "confirm eslint clean" directly conflicts with AC2's own instruction to leave the now-dead `playerRegistrationApi` import in place

**Where:** AC2's parenthetical — *"`playerRegistrationApi` stays imported in both pages regardless (each
still uses other exports from it, or — if it turns out `getMyProfile` was the only export either page used —
leave the import as dead-import cleanup is not part of this AC's scope; verify via grep before removing
anything)"* — versus Task 2.4: *"Run `npx eslint` on all three touched frontend files and confirm clean."*

`grep -n "playerRegistrationApi" CoachPublicProfilePage.vue BookingRequestPage.vue` confirms the second
branch of that parenthetical is what actually happens: `getMyProfile()` is the **only** use of
`playerRegistrationApi` in both files (one import line, one call site, each). Once AC2's replacement removes
that call site, the import becomes a genuinely unused binding in both files.

`src/frontend/eslint.config.js:21` includes `js.configs.recommended` with no override for `no-unused-vars`
anywhere in the file (confirmed by reading the full config and grepping for the rule). `eslint:recommended`
enables `no-unused-vars` as an **error**, and it flags unused import bindings, not just unused local
variables. So `npx eslint` on these two files after AC2's change as literally specified (import kept, call
site removed) will report `'playerRegistrationApi' is defined but never used` and exit non-zero — Task 2.4
cannot both "confirm clean" and honor AC2's explicit "leave the import" instruction.

**Recommendation:** Resolve the conflict explicitly rather than leaving it for `dev-story` to discover at the
lint step: either drop the now-genuinely-unused import in both files (the "dead-import cleanup is out of
scope" hedge doesn't hold once it's confirmed, via the grep AC2 itself asks for, that there's nothing else in
the file using it), or soften Task 2.4 to acknowledge the two `playerRegistrationApi` import lines as an
expected, deliberate lint exception if the import is kept.

---

## Finding 4 (Low, confirmed): AC1's rationale for skipping a `@BeforeEach` authority seed is factually wrong about *why* `CoachRegistrationResourceIT` has one — though the actual instruction given (skip it) is still correct

**Where:** AC1 — *"Unlike `CoachRegistrationResourceIT`'s `@BeforeEach` (which manually seeds `ROLE_COACH`
because nothing else seeds it), `ROLE_PLAYER` (id 102) is already seeded by
`V84__player_self_registration.sql` — no `@BeforeEach` authority seed is needed."*

`ROLE_COACH` is **also** already seeded by a Flyway migration — `V21__skillars_security_extension.sql:35-39`:

```sql
INSERT INTO main.authority (id, name, status, created_by, created_date)
VALUES
    (100, 'ROLE_COACH',  'ACTIVE', 'system', NOW()),
    (101, 'ROLE_PARENT', 'ACTIVE', 'system', NOW())
ON CONFLICT (name) DO NOTHING;
```

— in exactly the same `INSERT INTO main.authority` shape `DatabaseResetTestExecutionListener` scans for
(`INSERT_TARGET` regex, `flywaySeededTables()`) to build its snapshot-and-restore reference-data set, which
runs `restoreReferenceData()` **before every test method**, ahead of both `@Sql` scripts and `@BeforeEach`
(documented ordering: `reset (3000) -> @Sql (5000) -> @BeforeEach -> test`). So `ROLE_COACH` is already
present in `main.authority` by the time `CoachRegistrationResourceIT`'s `@BeforeEach` runs its
`ON CONFLICT (name) DO NOTHING` insert — that insert is a harmless no-op, not something "nothing else seeds."
It's legacy code, most likely predating this reference-data-restore mechanism (which the class's own Javadoc
describes as a fairly recent consolidation), left behind rather than cleaned up.

This doesn't change what AC1 actually asks the implementer to do — `ROLE_PLAYER` genuinely is covered by the
same restore mechanism via `V84`, so skipping `@BeforeEach` in the new `PlayerRegistrationResourceIT` is
correct — but the stated justification ("unlike Coach, which needs it") is wrong and could mislead a future
reader into thinking Coach's `@BeforeEach` is load-bearing when it isn't.

**Recommendation:** Correct the parenthetical to something like "Coach's `@BeforeEach` insert is actually
redundant too (`ROLE_COACH` is seeded by `V21` and restored the same way `ROLE_PLAYER` is by `V84`) — this
new IT simply doesn't copy that now-unnecessary pattern," so the reasoning matches the mechanism rather than
implying an asymmetry that doesn't exist. No code or task change needed.

---

## Finding 5 (Low, confirmed): AC2's cited precedent — "`fetchPlayers()`'s fetch-once-cache-in-store shape" — doesn't actually cache; it unconditionally re-fetches on every call

**Where:** AC2's rationale, twice: *"this codebase already has an established, directly-mirrorable pattern
for exactly this: `playerStore.js`'s existing `fetchPlayers()`/`players` pair (fetch-once, cache-in-store,
re-read from state on subsequent calls) is the shape a `fetchSelfPlayerId()`/`selfPlayerId` pair on the same
store should copy,"* and again in the Task list ("mirroring the `players`/`fetchPlayers()` fetch-once-cache-
in-store shape").

`playerStore.js:10-16`:

```js
async function fetchPlayers() {
  const data = await playerProfileApi.listProfiles()
  players.value = data
  if (data.length > 0 && !activePlayerId.value) {
    activePlayerId.value = data[0].id
  }
}
```

There is no cache-hit check here at all — no `if (players.value.length) return`. Every call to
`fetchPlayers()` makes a fresh network call and overwrites `players.value` unconditionally; only
`activePlayerId` has a (different) once-set guard. So "fetch-once, cache-in-store, re-read from state on
subsequent calls" describes behavior this function doesn't have — the actual precedent in this codebase is
"always refetch, unconditionally overwrite."

This is low-stakes because it doesn't misdirect the actual implementation: AC2 independently and explicitly
spells out the cache-hit/no-cache-on-failure logic `fetchSelfPlayerId()` must have, in enough detail that an
implementer doesn't need to infer it from `fetchPlayers()`. But the claimed precedent doesn't exist as
described, and citing it as justification ("this codebase already has an established... pattern for exactly
this") overstates how settled this shape is here — this would be the *first* fetch-once-cache function on
this store, not a mirror of an existing one.

**Recommendation:** Rephrase the rationale to drop the "already established... exactly this" framing — the
explicit behavior spec in AC2's bullet list stands on its own and doesn't need (incorrect) precedent to
justify it.
