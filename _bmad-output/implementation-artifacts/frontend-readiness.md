# Frontend Readiness — Defects and Missing UI

Task list of (a) defects found in the shipped frontend and (b) backend capability that has **no UI at
all**, so it cannot be seen or triggered by any user.

Written 2026-08-09 against commit `71fc164`. Sources: the code itself (backend `@*Mapping`
endpoints diffed against every frontend `api.*` / `axios.*` / `EventSource` call site),
`_bmad-output/planning-artifacts/prds/prd-skillars-2026-06-08/prd.md`,
`_bmad-output/planning-artifacts/skillars-epics.md`, and `requirements/skillars/`.

**Relationship to `uat-readiness-priorities.md`:** that file ranks the open items in
`deferred-work.md` against the UAT journey. This file is the complement — it covers what the ledger
does *not* track, because none of it was ever written down as deferred work. Where the two overlap
it is called out. Read that file first for deployment and payment-integrity risk; read this one for
"the feature exists but nobody can reach it."

## Method and what "missing" means here

Two passes, because the first one is not sufficient on its own.

**Pass 1 — endpoint diff.** 214 backend endpoints enumerated and diffed against every frontend
`api.*` / `axios.*` / `EventSource` call site. ~78 came back uncalled, of which roughly 30 were
false positives: SSE via `EventSource`, registration flows using bare `axios`, server-to-server
webhooks, the Stripe OAuth browser redirect, and endpoints the first regex missed because they use
`@GetMapping(value = "…")`. Each survivor was then re-verified by hand.

**Pass 2 — dead-export sweep.** Pass 1 systematically *under*-reports, because a URL string sitting
in `api/*.api.js` counts as "called" even when nothing ever invokes the function wrapping it. So
every exported function in `src/frontend/src/api/*.js` was checked for a real reference outside the
api layer. This is what surfaced **A5** (no cancellation UI at all) and the true extent of **B3.6**
— both of which pass 1 scored as "wired".

Anything reported below was confirmed by hand under whichever pass found it.

Counts of note:
- **Epics 9 and 10 are marked `done` in `sprint-status.yaml`** (all 3 review stories, all 4 admin
  stories) and have **zero frontend**. The i18n bundles contain no `reviews:`, `dispute:`, `gdpr:`
  or `moderation:` block, which is corroborating evidence that no UI was ever started.
- The admin surface is 29 endpoints across 8 controllers. **One** admin screen exists (health
  dashboard), and it calls none of them.
- 13 exported API functions are dead code — built, exported, never referenced.

---

## A. Defects — things that are wired wrong, not merely unbuilt

### A1. [P0] A coach cannot create the session pack that parents buy — the two ends write different tables

**This breaks the primary revenue flow end-to-end and is the highest-impact item in this file.**

- Coach profile builder step 3 collects session packs and `CoachProfileService.saveStep3`
  (`:142-152`) writes them to **`marketplace.SessionPack`**.
- `marketplace.SessionPack` is read in exactly one place — `CoachProfileService:249`, building
  `SessionPackDto` for the **public profile display** ("Session packs" on the coach card). Nothing
  else reads it. It is an advertisement, not an offer.
- The parent purchase page reads `GET /api/payment/coaches/{coachId}/session-pack-tiers`, i.e.
  **`payment.SessionPackTier`** — a different table.
- `payment.SessionPackTier` rows can only be created by
  `POST /api/payment/coaches/me/session-pack-tiers`. `payment.api.js` exports
  `createSessionPackTier` and `deactivateSessionPackTier`, but **no store and no component imports
  either**. `fetchMySessionPackTiers` is imported into `payment.store.js:99` and the action it backs
  has no callers.

Net effect: a coach completes the builder, sees their packs advertised on their own public profile,
and every parent who clicks "Buy sessions" gets *"This coach has no session pack available for
purchase."* Since booking requires credits and credits require a purchasable tier, **no coach made
through the UI is bookable.**

- [ ] **A1.1** Decide the owning table. `payment.SessionPackTier` is the live one (purchase,
      expiry, forfeiture, pause all key off it); `marketplace.SessionPack` looks vestigial.
- [ ] **A1.2** Make profile-builder step 3 create/replace the coach's `SessionPackTier` — or drop
      packs from step 3 and build the management UI in A1.3.
- [ ] **A1.3** Build the coach pack-tier management UI (list / create / deactivate). The API client
      functions already exist and are dead code; wiring them is most of the work.
- [ ] **A1.4** Reconcile the "one active tier per coach" backend rule with step 3's
      `@Size(max = 5) List<SessionPackRequest>`. The builder offers up to five packs; the purchase
      page renders exactly one (`v-if="tier"`, singular). Decide which is the product.
- [ ] **A1.5** Remove `marketplace.SessionPack` and its DTO once step 3 is repointed, or keep it
      explicitly as display-only and comment it as such so this cannot recur.

### A2. [P0] `/admin/tenants` calls endpoints that no longer exist

`a170e69` removed the tenant backend but left the entire frontend behind: routes `/admin/tenants`
and `/admin/tenants/:tenantRef` (`router/routes.js:323-331`), `TenantListPage.vue`,
`TenantDetailPage.vue`, and **all 14 tenant calls in `admin.api.js`** — every call in that file
except the single health check. All still ship and are still reachable; all now 404.

- [ ] **A2.1** Delete both routes, both page components, and the 14 dead calls in `admin.api.js`.
- [ ] **A2.2** Remove any nav entry pointing at them and the associated i18n keys.

### A3. [P1] The three-tier refund policy does not exist — `PARTIAL` refunds nothing

`BookingService.applyRefundLogic` (`:709`) stamps `Booking.refundEligibility` as
`FULL` (>24h) / `PARTIAL` (6–24h) / `NONE` (<6h). **Nothing reads that column** —
`getRefundEligibility()` has no callers and the string `"PARTIAL"` appears exactly once in the
codebase, on the line that assigns it. What actually moves money is a separate boolean on the
event: `paymentWasCaptured && requestedStartTime.isAfter(now + 24h)`, consumed by
`payment.CancellationRefundService`. A parent cancelling 8 hours out is therefore recorded as
`PARTIAL` and refunded **nothing**.

Not strictly a frontend defect, but it is a customer-facing promise the UI and any marketing copy
would inherit, so it needs a product decision before launch.

- [ ] **A3.1** Product call: implement partial refunds, or collapse the policy to the binary the
      code actually enforces.
- [ ] **A3.2** Whichever is chosen, make `refundEligibility` agree with it or drop the column.
- [ ] **A3.3** Surface the applicable outcome in the cancellation confirmation dialog — a parent
      currently cancels with no indication of what they will get back.

### A4. [P0] Nobody can cancel a booking or report a no-show — the API layer is written, nothing calls it

`booking.api.js` exports `cancelBooking`, `coachCancelBooking`, `recordNoShowCoach` and
`recordNoShowPlayer`. **None of the four is referenced anywhere outside that file.**
`booking.store.js:3-31` imports 30 functions from it and these are not among them.

So in the shipped UI: a parent cannot cancel, a coach cannot cancel, and neither party can report a
no-show. That leaves the entire downstream machinery unreachable — the refund matrix, pack-session
restoration, credit-wallet refunds, coach cancellation history, reliability strikes, and every
cancellation/no-show email. A large, tested, and genuinely complete slice of backend has **no
trigger**.

This also makes the reliability-strike system inert end to end: strikes are only issued from
unexcused coach cancellations and no-shows, so no coach can ever accrue one, so
`/coach/reliability` will always be empty and the visibility-reduction and suspension thresholds
can never fire.

- [ ] **A4.1** Parent cancellation — dialog on `/parent/bookings` calling `cancelBooking`. Must show
      the refund outcome before confirming (see A3.3).
- [ ] **A4.2** Coach cancellation — calling `coachCancelBooking`, with an explicit reason picker.
      The excused set (`MUTUAL_AGREEMENT`, `HEALTH_MEDICAL`, `FAMILY_EMERGENCY`, `WEATHER`) avoids a
      strike; every other reason issues one. The coach must be told which they are choosing.
- [ ] **A4.3** No-show reporting for both sides — `recordNoShowCoach` (parent-reported) and
      `recordNoShowPlayer` (coach-reported), surfaced from the session after its scheduled end.
- [ ] **A4.4** Re-test the strike thresholds once A4.2 lands; they have never been exercisable from
      the UI.

### A5. [P1] Other dead API exports — built, exported, never referenced

Same pattern as A4, found by the pass-2 sweep. Each is a feature whose client layer was written and
then never wired to a screen.

- [ ] **A5.1** `payment.api.js` — `createSessionPackTier`, `deactivateSessionPackTier` (see **A1**),
      `extendSessionPack` (the coach's one-time 30-day pack extension has no UI, so a parent's pack
      can only ever expire), `confirmPackPayment`.
- [ ] **A5.2** `messaging.api.js` — `fetchPlayerConversations`, `fetchPlayerConversationMessages`
      (see **B3.6**; this is the parent-visibility safety control).
- [ ] **A5.3** `development.api.js` — `getCoachBranding`, `saveCoachBranding`. A coach cannot
      customise the branding on the PDF performance reports the feature generates.
- [ ] **A5.4** Add a lint rule or CI check for unreferenced exports in `src/frontend/src/api/`. This
      pattern hid a P0 (A4) behind a file that looks complete on inspection; it will recur.

*(Also flagged by the sweep but **not** a gap: `development.api.js` `getNeglectedSkills` is dead,
but neglected skills do render — the pages read `store.neglectedCodes`, populated from the exposure
endpoint. The dedicated endpoint is redundant, not missing. Left here so nobody re-reports it.)*

---

## B. Backend capability with no UI at all

Grouped by who is blocked. Each item lists the confirmed-uncalled endpoints.

### B1. [P0] Admin console — 29 endpoints, 0 screens

Epic 10 is `done` on the backend and entirely invisible. Note this compounds
`uat-readiness-priorities.md` **P0-1** (no admin account can be created at all) — even once an admin
exists, there is nothing for them to open.

- [ ] **B1.1 Moderation queue** — the central admin screen. `GET /api/admin/queue`,
      `GET /api/admin/queue/summary`, `GET /api/admin/messages/{id}`,
      `POST /api/admin/messages/{id}/approve`, `POST /api/admin/messages/{id}/block`,
      `GET /api/admin/conversations/{id}`, `POST /api/admin/conversations/{id}/unblock`.
      Must handle the `MODERATION_UNRESOLVED` alert type and display `admin_alerts.reason` so
      "AI was unsure" is distinguishable from "never moderated at all".
- [ ] **B1.2 Coach enforcement** — `GET /api/admin/coaches`,
      `GET /api/admin/coaches/{id}/enforcement`, `POST /api/admin/coaches/{id}/suspend`,
      `POST /api/admin/coaches/{id}/reinstate`, `POST /api/admin/coaches/{id}/strikes`,
      `DELETE /api/admin/coaches/{id}/strikes/{strikeId}`. Without this a coach auto-suspended at
      5 strikes can never be reinstated.
- [ ] **B1.3 Dispute resolution** — `GET /api/admin/disputes/{id}`,
      `POST /api/admin/disputes/{id}/resolve`, `POST /api/admin/disputes/{id}/dismiss`.
      FR-ADM-003 requires the session audit trail be viewable here.
- [ ] **B1.4 Review moderation** — `GET /api/admin/reviews/queue`,
      `POST /api/admin/reviews/{id}/approve`, `POST /api/admin/reviews/{id}/block`.
- [ ] **B1.5 GDPR request queue** — `GET /api/admin/gdpr/requests`. Legally time-boxed work with no
      screen; see also B3.2.
- [ ] **B1.6 Financial oversight** — `GET /api/admin/payment/overview`,
      `GET /api/admin/payment/coaches/{id}/revenue`. Covers FR-ADM-005's reporting half.
- [ ] **B1.7 Admin video tools** — `GET /api/video/admin/videos/{id}/sessions`,
      `PATCH /api/video/admin/videos/{id}/access-state`,
      `POST /api/video/admin/videos/{id}/reconcile`, `DELETE /api/video/admin/videos/{id}`.
- [ ] **B1.8 Runtime config editor** — `GET/PUT /api/config/values/{key}`. Commission rate, strike
      thresholds, pack pause limits, age-tier boundaries and every sweeper's grace period are all
      config-driven and currently only changeable by hand-written SQL. Note the ~300s cache when
      designing the feedback.
- [ ] **B1.9 Operational alert rules** — `GET/POST /v1/admin/alerts`, `PUT /v1/admin/alerts/{id}`.
- [ ] **B1.10 Login-lock release** — `DELETE /v1/admin/users/{username}/login-lock`. Directly
      mitigates the coach-lockout risk in `uat-readiness-priorities.md` P0-3; cheap and worth doing
      even if the rest of the console slips.

### B2. [P0] Reviews — the whole feature, 7 endpoints, 0 screens

Epic 9 is `done` on the backend. Coach ratings *display* on the marketplace (`aggregateRating`,
`reviewCount`, "No reviews yet"), so the product visibly promises reviews it cannot collect. There
is no `reviews.api.js` and no `reviews:` i18n block.

- [ ] **B2.1** Review submission — `POST /api/reviews/coaches/{coachId}`, plus
      `GET /api/reviews/me/coaches/{coachId}` to detect an existing review. Eligibility is a
      completed booking, so the natural entry point is the post-session flow and/or
      `/parent/bookings`.
- [ ] **B2.2** Review editing — `PATCH /api/reviews/{reviewId}`. Note the 365-day edit cooldown must
      be explained in the UI or it reads as a bug.
- [ ] **B2.3** Public review list on the coach profile — `GET /api/reviews/coaches/{coachId}`.
      Currently only the aggregate is shown; individual reviews are unreachable.
- [ ] **B2.4** Coach's own reviews + right of reply — `GET /api/reviews/coaches/me`,
      `POST /api/reviews/{reviewId}/response`. FR-REV-002 requires the coach reply to render below
      the review.
- [ ] **B2.5** Community flagging — `POST /api/reviews/{reviewId}/flag`. This is the input that
      drives auto-hold at threshold; without it the moderation path is only ever AI-triggered.

### B3. [P0] User-facing rights and safety actions with no trigger

These are the ones with legal or trust weight — the backend is complete and the user cannot invoke
any of it.

- [ ] **B3.1 Raise a dispute** — `POST /api/disputes`, `GET /api/disputes/{disputeId}`.
      `BookingStateChip.vue` can already *render* a `DISPUTED` booking, so the UI displays a state
      no user can cause. FR-ADM-003 assumes disputes arrive.
- [ ] **B3.2 GDPR self-service** — `POST /api/gdpr/export`, `GET /api/gdpr/export/{requestId}`,
      `POST /api/gdpr/erasure`. The only "gdpr" string in the frontend is in the parent registration
      consent text. A user who has been asked to consent has no way to exercise the rights.
- [ ] **B3.3 Report a message** — `POST /api/messaging/conversations/{cid}/messages/{mid}/report`.
      FR-MSG-003 requires user reporting; the reporting half of abuse detection is unreachable.
- [ ] **B3.4 Report a conversation** — `POST /api/messaging/conversations/{cid}/report`.
- [ ] **B3.5 Delete own message** — `DELETE /api/messaging/conversations/{cid}/messages/{mid}`.
- [ ] **B3.6 Parent oversight of a child's messaging — entirely absent.**
      `GET /api/messaging/players/{playerId}/conversations` and
      `GET /api/messaging/players/{playerId}/conversations/{cid}/messages`. Both client functions
      exist in `messaging.api.js` and **neither is referenced anywhere** (see A5.2), so there is no
      conversation list *and* no message view. This is the mandatory parent-visibility control that
      FR-MSG-002 requires for every minor tier — the 10–12 tier is defined as "parent-visible only"
      and 13–17 as "all messages mandatory-visible to parent". **A minor can currently message a
      coach with no parental visibility whatsoever**, which is a safety and compliance gap, not a
      convenience one. Treat as P0 within this group.

### B4. [P1] Coach-facing gaps

- [ ] **B4.1** Coach subscription payment method — tracked as `uat-readiness-priorities.md` **P0-4**;
      `CoachSubscriptionPage.vue:118` still asks the coach to paste a raw Stripe `pm_...` id.
      Structural: `StripeCustomer` is keyed on `parent_id` and the setup-intent endpoints are
      parent-only. Listed here for completeness; own that item in the other file.
- [ ] **B4.2** Session-pack tier management — see **A1.3**; the same work.

### B5. [P2] Player-facing gap

- [ ] **B5.1** Player self-booking — tracked as `uat-readiness-priorities.md` **P0-2**. Every
      booking-creation endpoint is `HAS_PARENT_ROLE`, and the marketplace CTA pushes a logged-in
      adult player to `/login?returnUrl=…`, which loops. This is a product decision (scope the
      player journey to register-and-browse, or build it) rather than a missing screen.

---

## C. Requirements with neither backend nor frontend

Found by walking the PRD's FR list against the tree. These are not "UI missing" — nothing exists.

- [ ] **C1. FR-ADM-004 Appeals.** Users may submit one appeal within 14 days; the decision is final.
      **Zero occurrences of "appeal" anywhere in `src/main/java`.** Not built, not deferred, not
      tracked. Epic 10 is marked `done` while carrying this FR.
- [ ] **C2. FR-ADM-001 coach verification upgrades (Trusted / Featured).** `CoachProfile` has a
      `verification_tier` column defaulting to `"BASIC"`, the marketplace renders it via
      `VerificationBadge.vue`, and the i18n has `tierBASIC`/`tierTRUSTED`/`tierFEATURED` with
      tooltips reading "Admin-verified trusted coach" / "Admin-featured top coach". **No endpoint
      and no service can ever change the value** — `TRUSTED` and `FEATURED` do not appear in
      `src/main/java` at all. The badge is permanently "Basic" for every coach.
- [ ] **C3. FR-ADM-005 manual refunds.** The reporting half is B1.6. The manual-refund half has no
      implementation — `processAdminRefund` existed as an unwired, unauthenticated stub and was
      removed by `deferred-13`. An admin resolving a dispute in the parent's favour has no way to
      actually move the money.

---

## Suggested sequencing

Ordered by what unblocks the most, not by size.

1. **A1 — the session-pack disconnect.** Nothing else in the commercial flow can be demoed or
   UAT'd until a coach can publish a purchasable pack. Start with the product call in A1.1/A1.4.
2. **A4 — cancellation and no-show UI.** Second because it is the other half of a working booking
   loop: today a booking can be created and completed but never cancelled, and the whole
   reliability-strike system is unreachable. Pair with A3.3 so the refund outcome is shown at the
   point of cancelling.
3. **B3.6 — parent visibility of a child's messaging.** Ranked this high on compliance grounds
   rather than size. The age-tier design and the parental-consent copy both promise it and it does
   not exist. Small — both client functions are already written.
4. **A2 — delete the dead tenant frontend.** Ten minutes, removes a guaranteed broken screen from
   any demo.
5. **B1.10 + `uat-readiness-priorities.md` P0-1** — seed `ROLE_ADMIN`, bootstrap the first admin,
   and ship the login-lock release. Small, and it makes every later admin screen testable.
6. **B3.1–B3.5 — the remaining user-facing rights and safety actions.** Reporting, disputes, GDPR.
7. **B1.1–B1.3 — the moderation console core.** Queue, enforcement, disputes. Until this lands the
   reporting mechanisms in step 6 accumulate into a queue nobody can read, which is arguably worse
   than not having them. B1.2 also becomes load-bearing the moment A4 makes strikes reachable —
   without it a coach auto-suspended at 5 strikes can never be reinstated.
8. **B2 — reviews.** Largest single greenfield UI block, and the product already advertises it.
   Sequence B2.1/B2.3 (submit + display) ahead of B2.4/B2.5.
9. **A5 — the remaining dead exports**, and A5.4's lint rule so the class of bug that hid A4 cannot
   recur.
10. **B1.4–B1.9** — the remaining admin screens, in whatever order operations actually needs them.
    B1.8 (config editor) rises sharply the first time someone needs to tune a threshold in a live
    environment.
11. **C1/C2/C3** — genuinely new features. Decide whether they are launch scope; if Epic 10 is to be
    called complete, they belong in a follow-up epic rather than being quietly dropped.

## A note on process, not code

Epics 9 and 10 are recorded `done` with no frontend, and Epic 10 carries two FRs (**C1**, **C2**)
with no implementation on either side. Separately, four features have a fully written API client
that nothing calls (**A4**, **A5**). Both patterns point the same way: "done" has been assessed
against backend acceptance criteria, and the UI half of a story has been able to fall off without
anything catching it. Worth a retro item — the individual gaps above are cheap to fix, but they will
keep reappearing if the definition of done does not include a reachable UI path.
