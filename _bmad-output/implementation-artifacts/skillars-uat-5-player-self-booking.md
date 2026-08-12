# Story UAT.5: Player Self-Booking

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **The central design decision in this story: `parent_id` columns are reused as an opaque "requester id," not replaced.**
> `booking.bookings.parent_id` (`V31__booking_requests.sql:8`) and `payment.stripe_customers.parent_id`
> (`V62__session_payment_credit_wallet.sql:36`, the table's `@Id`) are both bare `BIGINT` columns with
> **no FK and no role constraint** — every consumer that reads them (`PaymentLifecycleService`,
> `CreditWalletService`, `BookingEmailListener`, `BookingService`'s own cancel/no-show/dispute checks)
> already treats the value as "whoever is paying/requesting," not as a verified `ROLE_PARENT` account.
> This story exploits that: a self-registered adult player's own `userId` is written into exactly the
> same columns a parent's `userId` would be written into today. **Do not add a `requester_type` column,
> a second booking table, or a new `ActorRole.PLAYER` enum value** — none of the downstream code needs
> it, and inventing one multiplies the surface area this story has to touch for no behavioural gain. The
> one column that genuinely cannot take this shortcut is `messaging.conversations.parent_id`, which is
> `NOT NULL` with **no legal non-null value to substitute** (there is no real parent) — that one gets a
> real nullable-column migration (AC3), not the opaque-id trick.

## Story

As a self-registered adult PLAYER account holder,
I want to browse coaches, save a payment card, and submit a booking request directly — without needing
a parent account —
so that the "create a player account → search a coach → pay → book a lesson" leg of the UAT journey
actually completes, instead of stopping at "register and browse."

### Why this story exists

Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md`, **P0-2**. The doc ranks this
as one of three items left unclaimed after `skillars-uat-1` through `-4`, and frames it as a product
decision, not a dev pass: *"scope the player journey to register-and-browse, or build player
self-booking."* **Decision made 2026-08-12 (Mbah, via this story's creation): build real self-booking.**
The doc's own note applies now that the decision is made: *"the D1 messaging item stops being
theoretical and must ship alongside"* (`deferred-work.md` line 553, `skillars-deferred-16` D1) — this
story's AC3 is that shipment.

Every fact below was re-verified against the tree at `c950c30` (post `skillars-uat-4`) by direct read of
the named file, not carried forward from the priorities doc's citations.

| AC | What it does | Verified current-state anchor |
|---|---|---|
| AC1 | Player can create/list bookings (single + batch) for themselves | `BookingResource.java:36` `@PreAuthorize(HAS_PARENT_ROLE)`; `BookingService.java:163-167` ownership check requires `player.getParentId() == callerId`, which is always false for a self-registered player (`PlayerProfile.parentId IS NULL` per `V84`'s `chk_pp_owner`) |
| AC2 | Player can save a card and pay per-session | `SessionPackPaymentResource.java:142-143,162-163,195-196` (`/setup-intent`, `/save-payment-method`, `/payment-method`) all `@PreAuthorize(HAS_PARENT_ROLE)`; `StripeCustomer.parentId` (`StripeCustomer.java`) is a bare `@Id BIGINT`, no FK, no role check |
| AC3 | Coach can message a self-booking player once a booking exists | `messaging.conversations.parent_id BIGINT NOT NULL` (`V65__messaging_module_init.sql:7`); `MessagingResource.initiateConversation:56-77` only recognizes coach-or-owning-parent, no self-player branch |
| AC4 | Player can discover, book, and view bookings in the UI | `CoachPublicProfilePage.vue`'s `handleCta()` sends any non-parent (including a logged-in player) to `/login`; `BookingRequestPage.vue` sources `playerId` from the parent-scoped `playerStore`; router `meta.role` is single-value, no dual-role route exists today |
| AC5 | Ledger hygiene | Close `skillars-deferred-16` D1 in `deferred-work.md`; update `uat-readiness-priorities.md`'s Story claims / Still unclaimed / Suggested sequence |

**Explicitly out of scope for this story** (record as new deferred items under AC5, do not build):
- **Session-pack purchase by a self-registered player.** `payment.session_pack_purchases` requires both
  `parent_id NOT NULL` **and** `player_id NOT NULL` (`V62` + live entity `SessionPackPurchase.java`), and
  `SessionPackPaymentService.purchasePack` hard-requires `playerProfileRepository.findByIdAndParentId`
  ownership — unlike the booking-ownership check, this one is not a simple XOR branch, because the pack
  itself has no `user_id`-style self-ownership column at all. A self-booking player pays **per-session by
  card only** in this story; packs are a separate, larger schema change.
- **Credit wallet (`payment.parent_credit_ledger`).** Keyed by `parent_id` alone, no `player_id`
  dimension — it is a per-parent shared balance across children, a concept that doesn't map onto a
  single self-booking adult. `CreditWalletResource` stays parent-only; a self-booking player's
  `effectiveCreditsRemaining` will correctly show 0/none since they'll never have ledger rows.
- **Player self-cancel / reschedule / no-show / dispute — backend stays parent-only, but two of these
  have LIVE frontend callers that AC4 is about to expose to players; read this before touching
  `ParentBookingsPage.vue`.** `CancellationResource.java:28-29` (`POST /{id}/cancel`) stays
  `@PreAuthorize(HAS_PARENT_ROLE)`; per `uat-readiness-priorities.md` P1 #2 this one **has zero frontend
  callers today** even for parents (verified: `booking.api.js` exports `cancelBooking`, grepping the name
  across `src/frontend/src` returns that one export and nothing else) — leaving it parent-only is not a
  new regression. **`RescheduleResource.java:33` (`POST /{id}/reschedule`, "Request Change" button,
  `ParentBookingsPage.vue:64-71`) and `SessionCompletionResource.java:90` (`PUT /{id}/confirm-completion`,
  "Confirm Completion" button, `ParentBookingsPage.vue:75-84`) are the opposite case — both are live,
  unconditionally-rendered buttons on the exact page AC4 step 4 reuses under the new player route, and
  neither is widened here.** Leaving the backend parent-only is still the right call (out of scope), but
  the buttons must be hidden from a player caller or they ship as dead 403s — see AC4 step 4's guard
  requirement. `ActorRole` (`ActorRole.java`) stays `{COACH, PARENT, SYSTEM}` — no `PLAYER` value is
  added, because nothing in this story's scope needs the state machine to distinguish a self-booking
  player from a parent (see the opaque-id note above).
- **`PlayerOwnershipGuard`'s missing self-owned branch** (`PlayerOwnershipGuard.java`, only checks
  `findByIdAndParentId`) — pre-existing gap, unrelated to booking, not touched here.

## Acceptance Criteria

### AC1 — A self-registered adult PLAYER can create and list their own bookings

**Backend, single-booking path:**
1. `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java` — add a
   composite constant next to `HAS_PARENT_ROLE`/`HAS_PLAYER_ROLE` (line 33-37), following the existing
   `HAS_ANY_ROLE` pattern:
   ```java
   public static final String HAS_PARENT_OR_PLAYER_ROLE = "hasRole('ROLE_PARENT') or hasRole('ROLE_PLAYER')";
   ```
2. `BookingResource.java:36` (`createBookingRequest`) and `:43` (`getParentBookings`) — change
   `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` to `HAS_PARENT_OR_PLAYER_ROLE`. Leave
   `currentParentId()`/`currentUserId()` helpers unchanged — they already just extract the caller's own
   id (`securityUtil.requireCurrentUserId()`), which is exactly what's needed for either role.
3. `BookingBatchResource.java:34` (`/config`) and `:40` (`createBatch`) — same widening.
4. `BookingService.createBookingRequest` (`BookingService.java:163-167`) — the ownership check must
   branch on which half of `PlayerProfile`'s XOR pair (`chk_pp_owner`, `V84__player_self_registration.sql`)
   is populated:
   ```java
   PlayerProfile player = playerProfileRepository.findById(req.playerId())
       .orElseThrow(() -> new ResourceNotFoundException("Player not found", "player_profile"));
   if (player.getParentId() != null) {
       if (!Objects.equals(player.getParentId(), callerId)) {
           throw new OperationNotAllowedException("Parent does not own this player", ...);
       }
   } else {
       if (!Objects.equals(player.getUserId(), callerId)) {
           throw new OperationNotAllowedException("Player does not own this profile", ...);
       }
   }
   ```
   This is safe regardless of caller role: a parent can never satisfy the `else` branch (their id is never
   a player's `userId`), and a self-registered player can never satisfy the `if` branch (their profile's
   `parentId` is always null by the CHECK constraint). Precedent for this exact XOR branch:
   `ShadowAccountService.createSelfOwnedPlayerProfile` (`ShadowAccountService.java:83-104`) already
   populates the two columns this way; `MessagingService.verifyIsParty` (`MessagingService.java:329-354`)
   and `MessagingService.getConversations` (`:95-140`) are the existing precedent for branching
   parent-vs-player identity resolution in a sibling module.
5. `Booking.setParentId(parentId)` (`BookingService.java:273`) stays **unchanged** — `parentId` here is
   already just `callerId`. When the caller is a self-booking player, their own `userId` lands in
   `booking.bookings.parent_id`, which is exactly the opaque-id behavior described in the callout above.
6. Session-pack branch inside the same method (`BookingService.java:251-269`,
   `pack.getParentId().equals(parentId)`) — **leave this branch's check as-is**, but it will naturally
   always reject for a self-booking player, since packs are out of scope (see "Explicitly out of scope").
   No code change needed here beyond confirming the request DTO's `sessionPackPurchaseId` stays optional
   (it already is — `CreateBookingRequest.java` does not `@NotNull` it) so a player can omit it and pay
   per-session instead.

**Backend, batch-booking path:**
7. `BookingBatchService.createBatch` (`BookingBatchService.java:101-105`) — identical XOR branch as
   step 4.

**Testing (mirror the project's established mutation-check convention — see `skillars-uat-2`/`-3` Dev
Notes on why "something threw" isn't sufficient):**
- `BookingServiceTest` — add cases: self-registered player books for themselves (succeeds, writes their
  own `userId` as `parent_id`); self-registered player attempts to book using someone else's `playerId`
  (rejected); a parent attempts to book using a self-owned player's `playerId` (rejected, exercises the
  `else` branch from a parent caller). Mutation check: deleting the `else` branch must fail a named test
  asserting on the specific rejection reason, not merely "something threw" (the exact discrimination bug
  `uat-2`'s Dev Notes flagged and fixed).
- `BookingBatchServiceTest` — same three cases for the batch path.
- `BookingRequestResourceIT` / a new `PlayerBookingRequestIT` — full-stack: register a player via
  `PlayerRegistrationService` + `ShadowAccountService.createSelfOwnedPlayerProfile`, issue a JWT with
  `ROLE_PLAYER`, POST `/api/bookings/requests`, assert `201` and the persisted row's `parent_id` equals
  the player's own `userId`.

### AC2 — A self-registered adult PLAYER can save a card and pay per-session

**Backend:**
1. `SessionPackPaymentResource.java:142-143` (`POST /setup-intent`), `:162-163`
   (`POST /save-payment-method`), `:195-196` (`GET /payment-method`) — change `@PreAuthorize` from
   `HAS_PARENT_ROLE` to the new `HAS_PARENT_OR_PLAYER_ROLE` constant from AC1. **No other code in these
   three methods changes** — `securityUtil.getCurrentCoachUserId()` (the misleadingly-named but
   role-agnostic current-user-id resolver, confirmed used identically across parent/coach/admin contexts)
   already returns the right id for either caller, and `StripeCustomer.parentId` (`StripeCustomer.java`,
   `@Id`, no FK, no role CHECK) will happily accept a player's own `userId` as its primary key — this was
   independently confirmed as the exact same structural shape `payment.stripe_customers` would need for
   the still-unresolved `P0-4` coach-subscription item, **except** a player's `userId` is a `BIGINT` from
   `main."user"` (same type/table `parent_id` already references informally), whereas a coach's id is a
   `UUID` from a different table — that type mismatch is what makes P0-4 structurally hard and this AC
   structurally easy. Do not conflate the two; this AC does not touch coach subscription at all.
2. Do **not** touch `session-packs/purchase` (`SessionPackPaymentResource.java:60-61`) or any
   `SessionPackPaymentService` pack-purchase method — out of scope, see the story-level "Explicitly out
   of scope" list.
3. Confirm (read, don't change) that `PaymentLifecycleService`'s capture path
   (`PaymentLifecycleService.java:178,206,319`, all keyed off `event.getParentId()` /
   `booking.getParentId()`) needs no change: once AC1 writes a player's own `userId` into
   `booking.parent_id`, capture will look up the `StripeCustomer` row this AC lets them create, under the
   same key. This is the payoff of the opaque-id design — verify it with an IT, don't assume it silently.

**Testing:**
- `SessionPackPaymentResourceIT` — add a case: a `ROLE_PLAYER` caller hits `/setup-intent` and
  `/save-payment-method`, then a booking-accept flow (reuse the fixture pattern from
  `skillars-uat-3`'s payment-capture tests) successfully captures against that player's own
  `StripeCustomer` row.
- Mutation check: reverting the `@PreAuthorize` change on any one of the three endpoints must fail a
  named IT asserting `403` becomes `200`/`204` for a player caller — not just "some test fails."

### AC3 — Coach can open a conversation with a self-booking player once a booking exists

**This is the messaging fix `skillars-deferred-16` D1 deferred, now unblocked by AC1.** Full trigger
chain, already verified end-to-end by research (not assumed): a coach accepting/holding a booking with a
self-registered player calls `POST /api/messaging/conversations`
(`MessagingResource.java:56-77`) → `MessagingService.initiateConversation` (`:63-93`) →
`ConversationCreationHelper.tryCreate(coachId, playerId, player.getParentId())` (`:85`, `parentId` is
`null`) → `messaging.conversations.parent_id BIGINT NOT NULL` (`V65__messaging_module_init.sql:7`)
rejects the insert → `DataIntegrityViolationException` escapes the `REQUERES_NEW` sub-transaction,
`MessagingService.java:86-89`'s catch-and-requery finds nothing (insert never committed) and rethrows the
original exception → falls through to `ApiAdvice.integrityViolationHandler`
(`ApiAdvice.java:155-173`), which has no `CONSTRAINT_MAPPINGS`/`CONFLICT_CONSTRAINTS` entry for this
constraint, so it returns a generic `400 generic.dataError` — an opaque, useless error for an operation
that should simply succeed.

1. **New migration** `src/main/resources/db/migration/V95__messaging_conversations_nullable_parent.sql`
   (V94 is the current highest — confirm before writing in case another story lands V95 first):
   ```sql
   ALTER TABLE messaging.conversations ALTER COLUMN parent_id DROP NOT NULL;
   ```
   No CHECK constraint is needed here (unlike `player_profiles.chk_pp_owner`) — `conversations` has no
   second "owner" column to XOR against; `parent_id IS NULL` simply means "no parent to notify," which is
   exactly the `ADULT`-tier reality this AC enables.
2. `Conversation.java:29-30` — `@Column(name = "parent_id", nullable = false)` → `nullable = true`.
3. `MessagingResource.initiateConversation` (`:56-77`) — add a third authorization branch alongside
   `isCoach`/`isParent` (`:63-68`): the caller **is** the self-registered player being messaged.
   ```java
   boolean isSelf = !isCoach && !isParent &&
       playerProfileRepository.findByUserId(userId)
           .map(p -> Objects.equals(p.getId(), request.playerId()))
           .orElse(false);
   if (!isCoach && !isParent && !isSelf) {
       throw new OperationNotAllowedException(...);
   }
   ```
   Without this, AC1+AC2 let a player book and pay, but only the **coach** can ever open the resulting
   conversation (confirmed: today a self-registered player calling this endpoint for themselves 403s
   before reaching the DB, because `findByIdAndParentId` can never match a null `parent_id`).
4. **Verify, do not modify, these downstream consumers** — all three were independently confirmed
   null-safe for the `ADULT`-tier case *before* writing any code, and re-verifying this in a passing IT is
   the AC's actual bar, not re-deriving it by reading again:
   - `MessagingService.verifyIsParty:335` — `Objects.equals(conv.getParentId(), callerUserId)` is
     null-safe by construction (returns `false`, never NPEs).
   - `ModerationResultApplier.resolveRecipient` (`:141-163`) — the `COACH`-sender branch only reads
     `conv.getParentId()` when `AgeMessagingPolicy.parentIsBlocked()` is `false`; for `ADULT` tier
     `parentIsBlocked()` is `true` (confirmed in `skillars-1-6`'s `AgeMessagingPolicy`), so the branch
     routes to `resolvePlayerUserId(...)` instead and **never dereferences the null `parent_id`**. A
     self-registered player is always `ADULT` tier (`PlayerRegistrationService.java:81-84` rejects
     minors), so this is unreachable-by-construction, not a lucky coincidence — but confirm
     `AdminMessageService.resolveRecipient` (`platform/admin/service/AdminMessageService.java`, "kept in
     sync deliberately" per the doc comment on `ModerationResultApplier.java:174`) actually matches this
     shape before shipping — it was not read line-by-line during story creation, only located.
   - `MessagingService.getConversationsForPlayer` / `getMessagesForPlayerConversation`
     (`:230-283`, the parental-oversight endpoints) — both open with
     `playerProfileRepository.findByIdAndParentId(playerId, parentUserId)` as an ownership guard, which
     can never match a self-registered player's null `parent_id`. No **real** parent user exists who
     could call these endpoints for such a player, so they stay unreachable — no code change, just a
     regression test proving it.
5. Do **not** touch `ActorRole`, the booking cancel/no-show/dispute paths, or add a `parent_id`-adjacent
   CHECK — none of them are in this AC's blast radius per the trigger chain above.

**Testing:**
- New `SelfPlayerMessagingIT` (or extend an existing messaging IT class): coach + self-registered adult
  player + a `CONFIRMED`-status booking between them (the precondition `initiateConversation` already
  requires, `MessagingService.java:64-69`) → coach calls `POST /api/messaging/conversations` → assert
  `200` and a persisted row with `parent_id IS NULL`. Second case: the player themselves calls the same
  endpoint for the same coach/player pair → assert `200` (exercises the new `isSelf` branch). Third case:
  a message send + moderation pass on that conversation completes without any 500/NPE (exercises
  `resolveRecipient`'s `ADULT`-tier branch under real null data, not just a unit mock).
- Mutation check: reverting the migration (restoring `NOT NULL`) must fail the first IT with the exact
  constraint-violation-turned-400 described above — proving the test actually exercises the fixed path.

### AC4 — Frontend: a logged-in player can discover, book, and view their bookings

1. **`CoachPublicProfilePage.vue`** — `handleCta()` (lines 307-323) and `ctaLabel`
   (lines 274-277) currently branch only on `authStore.isParent`, sending every other authenticated user
   (including a logged-in `ROLE_PLAYER`) to `/login`. Add an `authStore.isPlayer` branch that navigates
   into the booking-request flow using the player's own id (see step 3) instead of a `playerId` query
   param sourced from `playerStore`. `onMounted` (`:285-297`)'s `if (authStore.isParent)` pack-loading
   block should gain a parallel `else if (authStore.isPlayer)` branch that skips pack loading (packs are
   out of scope, AC-level) but still lets the page render coach pricing for a per-session quote.
2. **Router** (`src/frontend/src/router/routes.js`, `src/frontend/src/router/index.js`) — the
   `coaches/:coachId/request-booking` route (`routes.js:136-139`) is `meta: { role: 'PARENT' }`, and the
   guard (`index.js:53-83`) checks `meta.role === 'PARENT'` with strict equality — there is **no existing
   precedent for a dual-role route** in this codebase. Introduce `meta.roles: ['PARENT', 'PLAYER']` on
   this route (and the bookings-list route from step 4), and extend the guard to check
   `to.matched.some(r => r.meta.roles?.includes(authStore.role))` when `meta.roles` is present, falling
   back to the existing single-`meta.role` check for every other route (do not touch unrelated routes'
   `meta.role`/`requiresParent`/`requiresCoach` — this codebase mixes those two gating styles
   deliberately across different pages and normalizing them is out of scope here).
3. **`BookingRequestPage.vue`** — `playerId` (lines 214-216) currently resolves from
   `route.query.playerId` or `playerStore.activePlayerId` (a parent's linked-children store, meaningless
   for a self-registered player). Add a branch: when `authStore.isPlayer`, resolve `playerId` via
   `playerRegistrationApi.getMyProfile()` (`GET /api/security/players/me` — the same call
   `PlayerHomeRedirectPage.vue:14-22` already uses to resolve "my own player profile"), not via
   `playerStore`. The batch-submit guard at `:451-454` (`if (!playerId.value)`) stays as the correct
   safety net for both roles. Post-submit navigation (`:442`, hardcoded `router.push('/parent/bookings')`)
   needs **no branch** — step 4 reuses that same route path for both roles via `meta.roles`, so the
   destination is already correct as written for a player caller; do not build a second route here.
   Also extend step 1's pack-loading guard to this file: `onMounted` (line ~474) unconditionally calls
   `bookingStore.loadPlayerPacks(playerId.value)` → `GET /api/payment/session-packs`, which stays
   `@PreAuthorize(HAS_PARENT_ROLE)` (packs are out of scope, not widened by AC2). It's caught internally
   today (degrades to 0 credits, no crash), but skip the call entirely when `authStore.isPlayer` — same
   guard shape as `CoachPublicProfilePage.vue`'s `onMounted`, avoids a needless guaranteed-403 request on
   every player visit to this page.
4. **Player bookings list** — no player-facing booking-list page exists today (only
   `PlayerLockerRoomPlaceholderPage.vue`, `PlayerDevelopmentDashboardPage.vue`,
   `pages/VideoManagementPage.vue` under `role: 'PLAYER'`). Reuse `ParentBookingsPage.vue`
   under the same dual-role `meta.roles` pattern from step 2 (it already renders off
   `getParentBookings()`/`bookingStore` data, which AC1 makes work for a player caller unchanged) rather
   than building a parallel page. **This page has no child-switcher, but it does have two live,
   unconditionally-rendered write-action buttons that must be guarded**, confirmed by reading the
   template, not assumed: "Request Change" (`:64-71`, visible for any `CONFIRMED`/`UPCOMING` booking,
   calls `POST /{id}/reschedule`) and "Confirm Completion" (`:75-84`, visible for
   `COMPLETED_PENDING_CONFIRMATION`, calls `PUT /{id}/confirm-completion`) — both endpoints stay
   parent-only per the story-level scope note above and are **not** widened by this story. Wrap both
   `q-btn`s in `v-if="authStore.isParent"` (composed with each button's existing status `v-if`) so a
   player caller sees a read-only bookings list instead of buttons that 403 on click.
5. **`MainLayout.vue`** — there is currently **no `v-if="authStore.isPlayer"` nav section at all**
   (only Coach `:129-149`, Parent `:152-172`, Admin `:175-195` exist). Add a Player section mirroring
   that pattern, linking to the marketplace (`/marketplace` or wherever `CoachPublicProfilePage.vue` is
   reached from — confirm the existing coach-search entry route), to the bookings-list route from
   step 4, **and to `/messaging`** (both Coach `:141` and Parent `:164` nav sections already link there).
   Without this third link, AC3's entire messaging capability is unreachable from the UI — the
   `/messaging` route itself is already role-agnostic (`meta: { requiresAuth: true }`, no role
   restriction, `routes.js:277-280`) and `MessagingPage.vue`/`messaging.store.js` have no
   PARENT/COACH-specific branching, so this is a nav-link-only addition, not a new page or guard.
6. **Card save UI** — AC2 opens `/setup-intent`/`/save-payment-method` to players; the frontend needs
   *some* UI for a player to enter a card (Stripe Elements), analogous to whatever `ParentPlayerPortalPage`
   or the session-pack purchase flow uses for parents today. Reuse that existing card-entry component
   under the player role rather than building a new one — identify it during implementation (it was not
   pinned down by this story's research) and confirm it isn't itself pack-purchase-specific before
   reusing it wholesale.

**Testing:** no frontend automated test suite exists in this codebase (consistent with every prior UAT
story's Dev Notes — `skillars-uat-1`/`-2`/`-3` all recorded the same gap). Verify by successful
`quasar build` plus a manual/browser-tooled spot-check of: player login → coach search → book → pay →
booking appears in the player's own bookings list, with **no "Request Change" or "Confirm Completion"
buttons rendered** for the player-owned bookings on that list (step 4's guard) → coach opens a
conversation with that player, reachable from the new player nav link (step 5) without typing the URL
directly. Record this as **STILL OPEN** in Dev Agent Record, matching house convention, if a live browser
check isn't possible in this environment.

### AC5 — Ledger hygiene

1. `_bmad-output/implementation-artifacts/deferred-work.md` — close the `skillars-deferred-16 story
   creation (2026-08-05)` D1 item (line 553-557, "`messaging.conversations.parent_id` is `NOT NULL`...")
   with a `[CLOSED 2026-08-12 by skillars-uat-5 AC3]` annotation in the same style as `skillars-uat-4`'s
   closures (see `deferred-work.md` line ~1222 for the exact annotation format to match).
2. Record new deferred items for everything under "Explicitly out of scope" above (session-pack
   self-purchase, credit-wallet, player self-cancel/reschedule) — one entry each, same terse
   file:line-cited style as the rest of the ledger, so a future audit doesn't have to re-derive why they
   were skipped.
3. `_bmad-output/implementation-artifacts/uat-readiness-priorities.md` — add a row to the "Story claims"
   table for `skillars-uat-5-player-self-booking.md` claiming P0-2, remove it from "Still unclaimed," and
   update the "Suggested sequence" item 3 (currently "Decide P0-2...") to reflect the decision made and
   this story shipping it. Leave P0-4 and P1 #9 exactly as still-unclaimed/decision-pending — this story
   does not touch either.

## Tasks / Subtasks

- [x] **Task 1 — Backend: player can create/list own bookings, single + batch (AC: 1)**
  - [x] `SecurityConstants.java`: add `HAS_PARENT_OR_PLAYER_ROLE`.
  - [x] `BookingResource.java` `createBookingRequest`/`getParentBookings`: widen `@PreAuthorize`.
  - [x] `BookingBatchResource.java` `/config`/`createBatch`: widen `@PreAuthorize`.
  - [x] `BookingService.createBookingRequest`: XOR-branch ownership check on `PlayerProfile.parentId`/`userId`.
  - [x] `BookingBatchService.createBatch`: identical XOR-branch ownership check.
  - [x] `BookingServiceTest`/`BookingBatchServiceTest`: self-player books self (succeeds), self-player books
    someone else's playerId (rejected), parent books a self-owned player's playerId (rejected). Mutation
    check on the `else` branch.
  - [x] `PlayerBookingRequestIT` (or extend `BookingRequestResourceIT`): full-stack self-player booking,
    assert `201` and persisted `parent_id == player's own userId`.

- [x] **Task 2 — Backend: player can save a card and pay per-session (AC: 2)**
  - [x] `SessionPackPaymentResource.java` `/setup-intent`, `/save-payment-method`, `/payment-method`:
    widen `@PreAuthorize` to `HAS_PARENT_OR_PLAYER_ROLE`.
  - [x] Confirm (no code change) `PaymentLifecycleService` capture path needs no change — verify via IT.
  - [x] `SessionPackPaymentResourceIT`: `ROLE_PLAYER` caller hits setup-intent + save-payment-method, then
    a booking-accept flow captures against that player's own `StripeCustomer` row. Mutation check on the
    `@PreAuthorize` widening (403 → 200/204).

- [x] **Task 3 — Backend: coach can message a self-booking player (AC: 3)**
  - [x] New migration `V95__messaging_conversations_nullable_parent.sql` (confirm V95 is free): drop
    `NOT NULL` on `messaging.conversations.parent_id`.
  - [x] `Conversation.java`: `parent_id` column `nullable = true`.
  - [x] `MessagingResource.initiateConversation`: add `isSelf` branch alongside `isCoach`/`isParent`.
  - [x] Verify (regression tests, no code change) `verifyIsParty`, `ModerationResultApplier.resolveRecipient`
    (and confirm `AdminMessageService.resolveRecipient` matches), `getConversationsForPlayer`/
    `getMessagesForPlayerConversation` stay null-safe.
  - [x] New `SelfPlayerMessagingIT`: coach opens conversation with self-player (200, `parent_id IS NULL`);
    player opens conversation for themselves (200, exercises `isSelf`); message send + moderation pass
    completes without 500/NPE. Mutation check: revert migration, first IT must fail with the constraint
    violation.

- [x] **Task 4 — Frontend: player discover/book/view flow (AC: 4)**
  - [x] `CoachPublicProfilePage.vue`: `handleCta()`/`ctaLabel`/`onMounted` gain an `authStore.isPlayer`
    branch (book via own id, skip pack loading).
  - [x] Router (`routes.js`, `index.js`): add `meta.roles: ['PARENT', 'PLAYER']` to the
    request-booking and bookings-list routes; extend the guard to check `meta.roles` additively.
  - [x] `BookingRequestPage.vue`: resolve `playerId` via `playerRegistrationApi.getMyProfile()` when
    `authStore.isPlayer`; skip `loadPlayerPacks` for a player caller.
  - [x] Reuse `ParentBookingsPage.vue` under the dual-role route; guard the "Request Change" and
    "Confirm Completion" buttons with `v-if="authStore.isParent"`.
  - [x] `MainLayout.vue`: add a Player nav section (marketplace/coach-search, bookings list, `/messaging`).
  - [x] Card save UI: identify and reuse the existing Stripe Elements card-entry component under the
    player role.
  - [x] Verify: `quasar build` succeeds; `npx eslint` clean; manual/browser-tooled spot-check recorded as
    STILL OPEN if unavailable in this environment.

- [x] **Task 5 — Ledger hygiene (AC: 5)**
  - [x] `deferred-work.md`: close `skillars-deferred-16` D1 with a `[CLOSED 2026-08-12 by skillars-uat-5 AC3]`
    annotation.
  - [x] `deferred-work.md`: record new deferred items for session-pack self-purchase, credit wallet,
    player self-cancel/reschedule (out-of-scope items).
  - [x] `uat-readiness-priorities.md`: add Story-claims row for this story (P0-2), remove from "Still
    unclaimed," update "Suggested sequence" item 3. (Already reflected in the file from story creation —
    verified current, no further edit needed.)

- [x] **Task 6 — Verify**
  - [x] `mvn -o verify` full regression, 0F/0E.
  - [x] `npx eslint` clean on touched frontend files.
  - [x] `quasar build` succeeds.

### Review Findings

- [x] [Review][Patch] Single-booking submit has no `playerId` guard, contradicting its own comment [`src/frontend/src/pages/parent/BookingRequestPage.vue:279-283,459-477`] — fixed: `canSubmit` now also requires `!!playerId.value`, matching the safety net `submitBatchRequest` already had.
- [x] [Review][Defer→Patch] `getMyProfile()` non-404 failures are silently swallowed with no user feedback [`src/frontend/src/pages/parent/BookingRequestPage.vue:496-504`, `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue:298-311`] — fixed in both pages: a 404 stays silent (expected — no profile yet), any other error now surfaces `common.errorGeneric` via `$q.notify`.
- [x] [Review][Patch] No regression test proves PLAYER role stays rejected on endpoints kept parent-only (session-pack purchase, cancel, reschedule/confirm-completion) [`src/test/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResourceIT.java`] — fixed: added `purchaseSessionPack_playerRole_returns403`/`getMySessionPacks_playerRole_returns403` to `SessionPackPaymentResourceIT`, and a new `PlayerRoleLockoutIT` (WebMvcTest slice, exempt from the context-fragmentation guardrail) covering `/cancel`, `/reschedule`, `/confirm-completion` — the last had zero prior test coverage of any kind.
- [x] [Review][Patch] Batch review dialog shows credit-oriented copy to a self-booking player who has no credit concept [`src/frontend/src/pages/parent/BookingRequestPage.vue:190-197`] — fixed: new `booking.batch.sessionCountPreview` i18n key (all 3 bundles) renders instead of `creditPreview` when `authStore.isPlayer`.
- [x] [Review][Defer] Duplicated, uncached self-profile fetch across two pages [`src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue`, `src/frontend/src/pages/parent/BookingRequestPage.vue`] — deferred, pre-existing pattern (each page independently calls `playerRegistrationApi.getMyProfile()` on mount; a shared composable/store would remove the redundant round-trip, but this is a minor optimization outside this story's narrowly-scoped diff, not a regression it introduced)
- [x] [Review][Defer] Client-side `playerId` can be overridden via `?playerId=` query param before the server-side ownership check rejects it [`src/frontend/src/pages/parent/BookingRequestPage.vue:368-378`] — deferred, pre-existing pattern already used by the parent flow (route-query-first, store-fallback); server-side XOR ownership check already rejects it, a friendlier client-side error message is a UX nice-to-have, not a security gap

## Dev Notes

- **Module layout**: `platform.booking` (`BookingResource`, `BookingBatchResource`, `BookingService`,
  `BookingBatchService`, `CancellationResource` — untouched), `platform.payment`
  (`SessionPackPaymentResource`, `StripeCustomer`, `PaymentLifecycleService`), `platform.messaging`
  (`MessagingResource`, `MessagingService`, `ConversationCreationHelper`, `ModerationResultApplier`),
  `platform.security` (`PlayerRegistrationService`, `ShadowAccountService`, `PlayerProfile`,
  `PlayerProfileRepository`, `SecurityConstants`). No new module.
- **The XOR-ownership pattern to replicate everywhere in this story**: `PlayerProfile` already encodes
  "parent-owned OR self-owned, never both, never neither" via `chk_pp_owner`
  (`V84__player_self_registration.sql:14-17`) and the paired `parentId`/`userId` columns
  (`PlayerProfile.java:39-47`). Every ownership check this story touches (`BookingService`,
  `BookingBatchService`) should branch on that same pair, not introduce a parallel concept.
- **The opaque-id shortcut is deliberate and load-bearing** — re-read the callout at the top of this
  file before touching `Booking`, `StripeCustomer`, or any payment/notification code that reads
  `parentId`/`getParentId()`. It is the reason this story does not need a migration on `booking.bookings`
  or `payment.stripe_customers`, only on `messaging.conversations` (which has no legal substitute value).
- **Email/notification copy risk (low, flag don't fix blind)**: `BookingEmailListener.java` and
  `BookingService.resolveParentName` (`BookingService.java:806`) will resolve a self-booking player's own
  name/email into fields literally named `parentName`/`parentEmail` — functionally correct (the email
  goes to the right person), but check the actual email template copy (subject lines, "Dear Parent"-style
  salutations) for anything that would read oddly when the recipient is the player themselves, not their
  parent. Not a blocking AC; note what you find in Completion Notes.
- **Two role/authority sources exist in parallel** — `SecurityConstants` (used by every `@PreAuthorize`
  in this story) and `AuthoritiesConstants` (`platform/security/contract/util/AuthoritiesConstants.java`,
  used by `PlayerRegistrationService`) both duplicate `ROLE_PLAYER`/`ROLE_PARENT` string constants. Use
  `SecurityConstants` for all annotation work in this story (it's what every other resource in scope
  already uses) — do not introduce a third source or attempt to consolidate the two, that's a separate
  cleanup.
- **Router guard architecture**: this is the first dual-role route in the codebase (per AC4 step 2's
  research) — keep the new `meta.roles` mechanism additive and narrowly scoped to the two routes this
  story needs, rather than migrating every existing `meta.role`/`requiresParent`/`requiresCoach` route to
  it in the same diff. That's a larger, separate refactor with its own regression surface.

### Project Structure Notes

- All backend changes are widenings of existing `@PreAuthorize` annotations and branches inside existing
  service methods — no new resource classes, no new service classes, no new module.
  One new migration file: `V95__messaging_conversations_nullable_parent.sql` (confirm V95 is still free
  at implementation time — `skillars-uat-4` was the last story to land, at V94).
- Frontend: no new page component is strictly required if `ParentBookingsPage.vue` is successfully
  reused under a dual-role route (AC4 step 4); a new nav section in `MainLayout.vue` and role branches in
  two existing pages (`CoachPublicProfilePage.vue`, `BookingRequestPage.vue`) are the extent of the UI
  surface, plus whatever existing card-entry component gets reused for AC2's UI.
- No conflicts detected with `skillars-uat-1` through `-4`'s changes — none of those stories touched
  `BookingResource`, `BookingBatchResource`, `SessionPackPaymentResource`, or
  `messaging.conversations`'s schema. `skillars-uat-2` touched `BookingService`/`BookingBatchService` for
  session-duration enforcement (separate methods/branches from the ownership check this story edits) —
  read the current state of both files fresh rather than assuming line numbers from that story's Dev
  Notes, since this story's own line citations were re-verified at `c950c30`, one commit past `uat-4`.

### References

- [Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md#P0-2`]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` lines 553-557, `skillars-deferred-16` D1]
- [Source: `_bmad-output/implementation-artifacts/skillars-1-6-age-tier-enforcement-family-data-isolation.md`]
- [Source: `_bmad-output/implementation-artifacts/skillars-deferred-16-messaging-moderation-recovery-identity-safety.md`]
- [Source: `src/main/resources/db/migration/V84__player_self_registration.sql`]
- [Source: `src/main/resources/db/migration/V31__booking_requests.sql`]
- [Source: `src/main/resources/db/migration/V65__messaging_module_init.sql`]
- [Source: `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql`]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/api/BookingResource.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/api/BookingBatchResource.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/payment/repo/StripeCustomer.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/messaging/api/MessagingResource.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/messaging/service/ConversationCreationHelper.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/messaging/service/ModerationResultApplier.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/security/service/ShadowAccountService.java`]
- [Source: `src/main/java/com/softropic/skillars/platform/security/repo/PlayerProfile.java`]
- [Source: `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java`]
- [Source: `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue`]
- [Source: `src/frontend/src/pages/parent/BookingRequestPage.vue`]
- [Source: `src/frontend/src/router/routes.js`, `src/frontend/src/router/index.js`]
- [Source: `src/frontend/src/layouts/MainLayout.vue`]
- [Source: `src/frontend/src/stores/auth.store.js`]

## Dev Agent Record

### Agent Model Used

claude-sonnet-5

### Debug Log References

- `mvn -o verify` (full regression, post-implementation): BUILD SUCCESS — 866 unit (0F/0E, 1 skipped)
  + 886 IT (0F/0E, 4 skipped). Concrete-IT sanity check: 142 `*IT.java` − 4 abstract bases = 138
  concrete, exactly 138 failsafe report files — no class silently stopped running.
- Contribution measured by diff against HEAD, not by subtracting totals: +6 unit `@Test` methods
  (`BookingServiceTest` +3, `BookingBatchServiceTest` +3), +10 IT `@Test` methods
  (`SessionPackPaymentResourceIT` +3, `PlayerBookingRequestIT` 3 new, `PlayerCardCaptureIT` 1 new,
  `SelfPlayerMessagingIT` 3 new).
- `npx eslint` clean on all 9 touched frontend files (3 i18n bundles, 2 router files, 4 pages/layout).
- **Code review, post-patch full re-verification (2026-08-12):** `mvn -o compile test-compile` clean;
  full `mvn -o verify` re-run BUILD SUCCESS — 866 unit (0F/0E, 1 skipped) + 891 IT (0F/0E, 4 skipped,
  +5 vs pre-patch: `PlayerRoleLockoutIT`'s 3 new tests + `SessionPackPaymentResourceIT`'s 2 new
  `playerRole_returns403` tests). `npx eslint` clean on all touched files; `npx quasar build`
  succeeded. All 4 patch findings independently verified present and correct in the diff before
  marking done.
- `npx quasar build` succeeded (SPA mode) with the new frontend code included.
- Post-review-patch re-run of `mvn -o verify`: BUILD SUCCESS — 866 unit (unchanged) + 891 IT
  (0F/0E, 4 skipped) — the +5 IT delta over the pre-patch 886 matches exactly the 5 new
  PLAYER-lockout tests (`PlayerRoleLockoutIT` 3 new + `SessionPackPaymentResourceIT` +2).

### Completion Notes List

- **AC1 (backend):** `SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE` added; `BookingResource`/
  `BookingBatchResource` `@PreAuthorize` widened; `BookingService.createBookingRequest` and
  `BookingBatchService.createBatch` both gained the XOR-branch ownership check
  (`player.getParentId() != null` → parent-owns check, else → `player.getUserId()` self-owns
  check) exactly as prescribed, reusing `PlayerProfile`'s existing `chk_pp_owner` pair rather than
  introducing a parallel concept. `Booking.setParentId`/`BookingBatch.setParentId` needed **no
  change** — both already just persist the caller's own id, which is the opaque-id shortcut this
  story is built on: a self-booking player's own `userId` lands in `parent_id` unchanged.
  `PlayerBookingRequestIT` proves this end to end against a real DB (persisted `parent_id ==` the
  player's own `userId`, single + batch).
- **AC2 (backend):** the three `SessionPackPaymentResource` card endpoints widened identically. No
  change was needed to `PaymentLifecycleService`'s capture path or `StripePaymentGateway` — both
  already key off `event.getParentId()`/`booking.getParentId()`, which AC1 now populates with the
  player's own id, so a `StripeCustomer` row created via the widened `/setup-intent`/
  `/save-payment-method` resolves under the same key at capture time. Verified end to end by a new
  `PlayerCardCaptureIT` against a real DB (seeded `StripeCustomer` keyed on a self-registered
  player's `userId`; `PaymentLifecycleService.onBookingAccepted` settles the booking to `CONFIRMED`
  with a `CAPTURED` payment row) rather than assumed from reading the code, per the AC's own
  instruction.
- **AC3 (backend):** `V95__messaging_conversations_nullable_parent.sql` drops the `NOT NULL` (V94
  was the prior high-water mark, confirmed free before writing). `Conversation.parentId` marked
  nullable. `MessagingResource.initiateConversation` gained the `isSelf` branch. All three
  downstream consumers the story named (`verifyIsParty`, `ModerationResultApplier.resolveRecipient`
  + confirmed `AdminMessageService.resolveRecipient` matches it, `getConversationsForPlayer`/
  `getMessagesForPlayerConversation`) needed **no code change** — confirmed null-safe by a new
  `SelfPlayerMessagingIT` exercising a real null `parent_id` end to end (coach opens conversation →
  200 + persisted `parent_id IS NULL`; player opens it themselves → 200, exercising `isSelf`;
  message send + moderation pass completes with no 500/NPE). `skillars-deferred-16` D1 closed in
  `deferred-work.md`.
- **AC4 (frontend):** `CoachPublicProfilePage.vue`, router (`meta.roles` dual-role mechanism,
  additive and scoped to exactly the two routes this story needs), `BookingRequestPage.vue`
  (playerId resolution via `playerRegistrationApi.getMyProfile()`, pack-loading skipped for a
  player caller), `ParentBookingsPage.vue` (reused under the dual-role route; "Request Change" and
  "Confirm Completion" buttons guarded `v-if="authStore.isParent"` since neither endpoint is
  widened by this story), `MainLayout.vue` (new Player nav section: marketplace, bookings list,
  `/messaging`). Card-entry UI: identified `PaymentMethodCard.vue` as the existing reusable Stripe
  Elements component (confirmed not pack-purchase-specific — it only calls
  `setup-intent`/`save-payment-method`/`confirm-card-setup` and reads a per-user
  `paymentStore.savedPaymentMethod`), reused wholesale in `BookingRequestPage.vue` under a
  player-only card section. Beyond the story's explicit step list: also hid the
  `SessionPackTracker`/no-credits-warning banner for a player caller on `BookingRequestPage.vue` —
  both are pack-purchase UI, and the banner's "buy sessions" action targets a parent-only route
  that would 403/redirect a player, a dead-end this story's own scope note (packs out of scope)
  argued against shipping. New i18n key `player.nav` added to all 3 bundles (en-US/de-DE/fr-FR).
- **AC5:** `skillars-deferred-16` D1 closed with a `[CLOSED 2026-08-12 by skillars-uat-5 AC3]`
  annotation matching `skillars-uat-4`'s format. Three new deferred items recorded for the
  explicitly out-of-scope items (session-pack self-purchase, credit wallet, player
  self-cancel/reschedule/no-show/dispute). `uat-readiness-priorities.md` was already fully updated
  during this story's creation (Story-claims row, "Still unclaimed" cleared of P0-2, "Suggested
  sequence" item 3 marked decided) — verified current, no further edit needed.
- **Email/notification copy risk (Dev Notes flag, not a blocking AC):** checked
  `BookingEmailListener.java`'s three `parentName`-carrying templates
  (`bookingRescheduleRequested.html`, `bookingBatchRequested.html`, `bookingCancelledDueToPause.html`).
  All three are sent **to the coach**, not to the parent/player, and `parentName` is used only as a
  display label ("`<parentName>` has requested N session(s)") — there is no "Dear Parent"-style
  salutation anywhere. No fix needed; reads correctly for a self-booking player's own name too.
- **STILL OPEN:** no frontend automated test suite exists in this codebase (consistent with every
  prior UAT story). Per this story's own AC4 testing note, a live browser spot-check of the full
  player journey (login → coach search → book → pay → own bookings list with no write-action
  buttons → coach opens a conversation reachable via the new player nav link) was not run in this
  environment — verified by code reading, `quasar build`, and `npx eslint` only.

### File List

**Backend — main:**
- `src/main/java/com/softropic/skillars/infrastructure/security/SecurityConstants.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/api/BookingBatchResource.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java`
- `src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java`
- `src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java`
- `src/main/java/com/softropic/skillars/platform/messaging/repo/Conversation.java`
- `src/main/java/com/softropic/skillars/platform/messaging/api/MessagingResource.java`
- `src/main/resources/db/migration/V95__messaging_conversations_nullable_parent.sql` (new)

**Backend — test:**
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/service/BookingBatchServiceTest.java`
- `src/test/java/com/softropic/skillars/platform/booking/api/PlayerBookingRequestIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResourceIT.java`
- `src/test/java/com/softropic/skillars/platform/payment/service/PlayerCardCaptureIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/messaging/api/SelfPlayerMessagingIT.java` (new)
- `src/test/java/com/softropic/skillars/platform/booking/api/PlayerRoleLockoutIT.java` (new — review finding)

**Frontend:**
- `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue`
- `src/frontend/src/router/routes.js`
- `src/frontend/src/router/index.js`
- `src/frontend/src/pages/parent/BookingRequestPage.vue`
- `src/frontend/src/pages/parent/ParentBookingsPage.vue`
- `src/frontend/src/layouts/MainLayout.vue`
- `src/frontend/src/i18n/en-US/index.js`
- `src/frontend/src/i18n/de-DE/index.js`
- `src/frontend/src/i18n/fr-FR/index.js`

**Docs / ledger:**
- `_bmad-output/implementation-artifacts/deferred-work.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/skillars-uat-5-player-self-booking.md` (this story file)

## Change Log

| Date | Change |
|---|---|
| 2026-08-12 | Story implemented: all 5 ACs done, full `mvn -o verify` green (866 unit + 886 IT, 0F/0E). AC1 widens booking creation to self-registered players via the opaque-id XOR-branch ownership check; AC2 widens card save/pay identically, verified against a real `StripeCustomer` row; AC3 ships `V95` (nullable `messaging.conversations.parent_id`) plus the `isSelf` authorization branch, closing `skillars-deferred-16` D1; AC4 adds the first dual-role frontend route (`meta.roles`) and a Player nav section; AC5 records 3 new deferred items for the explicitly out-of-scope pieces (pack self-purchase, credit wallet, player self-cancel/reschedule). |
| 2026-08-12 | Review findings addressed — 4 patches applied, 2 pre-existing deferrals confirmed as-is: (1) `canSubmit` now also requires `playerId`, closing the gap between it and `submitBatchRequest`'s existing guard; (2) `getMyProfile()` failures now distinguish an expected 404 (silent) from any other error (surfaced via `$q.notify`) in both `BookingRequestPage.vue` and `CoachPublicProfilePage.vue`; (3) new `PlayerRoleLockoutIT` plus two new `SessionPackPaymentResourceIT` cases prove PLAYER stays 403'd on `/cancel`, `/reschedule`, `/confirm-completion`, `/session-packs/purchase` and `GET /session-packs` — `/cancel` had zero prior test coverage of any kind; (4) new `booking.batch.sessionCountPreview` i18n key (3 bundles) replaces the credit-oriented batch-review copy for a player caller. Full `mvn -o verify` re-run on the touched test classes green (14/14); `npx eslint` clean; `quasar build` succeeded. |
