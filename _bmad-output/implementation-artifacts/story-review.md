# Story Review: Deferred-47 — Booking Active-Slot-Status Config Endpoint & Frontend Wiring

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-47-booking-active-slot-status-config-endpoint-and-frontend-wiring.md`

Method: every factual claim in the story (line numbers, "no other call site" claims, the semantic choice of
which backend constant to expose, the endpoint-ordering rationale, and the AC3 ledger-tag state) was
re-verified against the current code on this branch, not trusted from the story's own prose. Read in full:
`BookingService.java` (128-138, 419-607), `BookingResource.java`, `BookingBatchResource.java`,
`BatchConfigResponse.java`, `ConfigResource.java`, `BookingRequestResourceIT.java`,
`BookingBatchResourceIT.java`, `BookingRequestPage.vue`, `booking.api.js`, `booking.store.js`,
`boot/axios.js`, `SecurityConstants.java`, and `deferred-work.md`'s cited line. Specifically checked and
ruled out as non-issues:

- **Which backend constant AC1 exposes.** `AvailabilityService.java:110` confirms `computedSlots` excludes
  bookings via `BookingService.ACTIVE_SLOT_STATUSES` (the full 7-status set), not
  `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED` (the other package-private list in the same class, used only for
  accept-time overlap checks). AC1's getter returns the correct one — the one that actually governs why a
  booking disappears from the calendar, which is the semantic `OWN_BLOCKING_STATUSES` needs to match.
- **Response-shape unwrapping.** `boot/axios.js:112-124`'s response interceptor returns `response.data`
  directly, confirming AC2's `res.activeSlotStatuses` (mirroring the existing `res.maxSize` pattern at
  `BookingRequestPage.vue:622`) is the correct access shape, not `res.data.activeSlotStatuses`.
- **Jackson field naming.** No `property-naming-strategy`/`PropertyNamingStrategy` config exists anywhere
  under `src/main/resources` or `src/main/java` — confirms the record's `activeSlotStatuses` accessor
  serializes as camelCase `"activeSlotStatuses"` in the JSON body, matching what AC2's frontend code reads.
- **"No other call site" claim (AC2).** Grep-confirmed: `OWN_BLOCKING_STATUSES` appears only at its
  declaration (`:355`) and the one usage site (`:434`), both in `BookingRequestPage.vue`. No other file
  references it.
- **Route collision / path-matching risk.** `BookingResource.java` has no plain `GET /{id}` mapping (only
  `PUT /{id}/accept` and `PUT /{id}/decline}`), and no other file outside the `booking.api`/`booking.service`/
  `booking.contract` packages references `/api/bookings/...` paths (no separate security-filter allowlist or
  gateway route table to update). The new `GET /config` cannot collide with anything regardless of where in
  the class it's declared.
- **AC1's negative-auth-test hedge.** Checked both `BookingRequestResourceIT.java` and
  `BookingBatchResourceIT.java` for an existing role-rejection test on a `GET` endpoint specifically (as
  opposed to the `POST`/`PUT` role-rejection tests both files already have) — none exists, including on
  `BookingBatchResource.getConfig()` itself, the exact sibling endpoint AC1 mirrors. AC1's "if the file's
  existing tests already establish a role-rejection convention for this resource — check before adding a new
  one" hedge is accurate: no such convention exists yet, so the story correctly leaves this optional rather
  than mandating a test pattern that isn't established.
- **AC3's ledger tag.** `git show 834a3f0` (the story-creation commit itself) confirms
  `` `[PICKED UP by skillars-deferred-47 AC1, AC2]` `` was already appended to `deferred-work.md`'s line-1276
  item in that same commit. This matches the precedent already recorded in the `skillars-deferred-44/45/46`
  reviews — this project's story-creation step applies the ledger tag at story-creation time, not dev time —
  so Task 3 describing it as pending work for the dev agent is expected process, not a defect.
- **All cited line numbers** (`BookingService.java:131-132`, `BookingRequestPage.vue:355-363,434,596-626,228`,
  `booking.api.js:32,68`, `BookingBatchResource.java:35-39`) were checked against the current file contents
  and are exact.

One low-severity, purely cosmetic inconsistency was found. No functional gaps, missed corner cases, or false
assumptions with real consequences survived verification.

## Findings

### 1. AC1's endpoint-placement rationale cites a precedent that doesn't actually apply to the new endpoint

**Severity: Low (confirmed) — cosmetic only; the placement itself is harmless regardless of where in the class it lands.**

**Where:** AC1's third bullet:

> "...matches the file's own existing precedent of ordering literal-path routes before any `/{id}/...` routes
> to avoid Spring path-matching ambiguity, noted in the file's `/coach` comment"

The `/coach` comment being invoked (`BookingResource.java:48`, `// Declared before /{id}/accept and
/{id}/decline to avoid Spring path-matching ambiguity`) exists because `GET /coach` needed to be visually
grouped ahead of `PUT /{id}/accept` and `PUT /{id}/decline` in that specific file layout. But Spring MVC
dispatches on HTTP method + path together — a `GET` mapping can never actually collide with a `PUT
/{id}/...` mapping regardless of declaration order, and this class has no plain `GET /{id}` mapping for the
new `GET /config` to collide with either way. So there is no real path-matching ambiguity this story's
placement choice is protecting against — the cited justification doesn't govern anything for this particular
addition, even though the placement itself (between `getParentBookings()` and `getCoachBookingRequests()`) is
perfectly fine on its own merits (grouping the two parent-facing GETs together).

**Recommendation:** either drop the "to avoid Spring path-matching ambiguity" clause from AC1's rationale (the
grouping is just for readability, which is reason enough), or note explicitly that it's precautionary/stylistic
consistency rather than a functional requirement — so a dev reading it doesn't spend time worrying about a
non-existent ambiguity risk before treating grouping as flexible.
