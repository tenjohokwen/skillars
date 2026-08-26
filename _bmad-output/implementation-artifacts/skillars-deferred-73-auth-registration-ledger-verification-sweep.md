# Story Deferred-73: Auth/Registration Ledger Verification Sweep

Status: done

## Story

As an engineer operating this platform,
I want the Auth/Registration section of `deferred-work.md` re-mined for the first time since it was filed
(2026-06-11/12, never touched by any of the 72 prior `skillars-deferred-*` stories),
so that stale/already-fixed items are closed and the ledger accurately reflects what remains genuinely open.

### Why this story exists

Continuing the module-priority re-mining order: `skillars-deferred-72` confirmed Booking/Availability/
Reschedule thin (one item) then escalated to Marketplace/Coach-profile (one more item). This pass
re-checked Marketplace/Coach-profile first and found it now fully exhausted — every section under
`## Deferred from: code review of skillars-3-1-coach-availability-management`,
`skillars-2-4-contact-detail-sanitization-ux`, and `skillars-2-3-coach-public-profile-page` carries either
a `[CLOSED by skillars-deferred-72 ...]` tag or an item already explicitly self-dismissed at review time
(the `getPublicProfile` N+1 candidate, `[RE-EVALUATED by skillars-deferred-70]` and deliberately left open
pending real latency evidence, not speculative work). Escalated to Auth/Registration per the established
order.

Auth/Registration's six sections (`deferred-work.md:841-928`, stories 1.1-1.6, all dated 2026-06-11/12)
total roughly 70 untagged bullets — never re-mined by any of the 72 prior stories in this series. A
representative sample, picked by staleness signal (items whose premise references a "not yet implemented"
or "may be missing" condition that later platform work plausibly closed), was checked directly against
live source. **Every item actually checked turned out stale or already-fixed — zero live bugs, zero items
needing a product decision.** The remaining ~65 unchecked bullets, by their own original filed text, read
as already-accepted tradeoffs at filing time ("spec-mandated," "intentional," "acceptable," "pre-existing
... not introduced by this story," "same pattern as X, already tracked") — not live-bug candidates, though
a full exhaustive pass was not performed this round (see Dev Notes for the explicit scope boundary and the
project owner's own decision on this point).

One additional candidate was investigated directly and confirmed still-correctly-open, not a missed
closure: `## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification
Group D` D3 ("`SUSPENDED` user in `EMAIL_VERIFIED` state can complete phone OTP — no suspension code
exists yet") was worth re-checking since coach-suspension infrastructure (`CoachProfileStatus.SUSPENDED`,
`AdminCoachEnforcementService`) has existed for some time now, unlike when this item was filed. Verified:
`CoachRegistrationService` (the class this item names) never references `CoachProfile`/
`coachProfileRepository` at all — confirmed by direct read, zero hits. At phone-OTP-verification time
during initial account registration, no `CoachProfile` row exists yet (it is created later, in a separate
profile-builder flow) — so there is nothing for `CoachProfileStatus.SUSPENDED` to apply to at this point in
the flow, regardless of how much suspension infrastructure now exists elsewhere. No user-account-level
suspension field exists on `User` either (confirmed: zero `status`/suspension field hits in
`security/repo/User.java`). The item remains correctly open, for a more precise reason than originally
filed — not picked up as a fix (there is nothing to guard against yet), not closed either (the underlying
question — should account-level suspension gate registration completion? — is still a real, undecided
product question, just not one this narrow ledger-hygiene pass is positioned to answer unilaterally).

## Acceptance Criteria

None — this is a ledger-hygiene-only pass; no source code changes. The three closures below were applied
directly during this story's own creation, exactly as the equivalent AC in `skillars-deferred-61`,
`-70` (AC5), and others in this series have done for pure ledger-hygiene findings.

### Closures applied to `deferred-work.md`

All three under `## Deferred from: code review of skillars-1-5-authentication-jwt-security (2026-06-12)`:

1. **`AuthResourceIT` lacks `@Testcontainers` annotation — verified working as designed, not a gap.**
   Original bullet: `` `AuthResourceIT` lacks `@Testcontainers` annotation — may be managed via inherited
   `TestConfig` or `SecurityIT` base class; verify before next review [AuthResourceIT.java] ``. Verified:
   `AuthResourceIT extends AbstractIntegrationTest`
   (`src/test/java/com/softropic/skillars/config/AbstractIntegrationTest.java:81`). That base class's own
   javadoc (`:57`) explicitly documents why: `@see SharedContainers for why the containers are JVM-static
   and not beans` — this codebase deliberately uses JVM-static shared Testcontainers instances instead of
   the per-class `@Testcontainers` annotation, an established architectural pattern applied uniformly, not
   a gap specific to this file. The item's own hedge ("may be managed via inherited... base class") was
   correct. Applied: `` `[CLOSED by skillars-deferred-73 (verified working as designed): AuthResourceIT
   extends AbstractIntegrationTest, which deliberately uses JVM-static SharedContainers instead of
   @Testcontainers — an established pattern, not a gap. The item's own hedge ("may be managed via
   inherited base class") was correct.]` ``

2. **`fr-FR` locale's `auth.coach` sub-tree — confirmed present, not missing.** Original bullet: `` `fr-FR`
   locale may be missing `auth.coach` sub-tree — investigate whether gap is pre-existing from a prior
   story [i18n/fr-FR/index.js] ``. Verified: `src/frontend/src/i18n/fr-FR/index.js:155` has a populated
   `coach:` block nested under `auth:`, with real, translated content (`registerTitle: 'Créer un compte
   coach'`, `registerSubtitle`, `emailPendingTitle`, `emailPendingBody`, `resendEmail`, `resendCooldown`,
   `phoneVerifyTitle`, `phoneVerifySubtitle`, `tosLabel`, and more) — not a stub, not missing. Applied:
   `` `[CLOSED by skillars-deferred-73 (verified present): fr-FR's auth.coach sub-tree exists with full
   translated content at index.js:155. The "may be missing" premise is stale.]` ``

Plus one, under `## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification
Group D (2026-06-11)`:

3. **`ContactDetailSanitizer` "double-redaction edge case" — verified structurally unreachable, not just
   benign.** Original bullet (D6): `` `ContactDetailSanitizer` double-redaction edge case — phone pattern
   can match trailing digits in already-redacted string; benign, no exploitable effect
   [ContactDetailSanitizer.java] ``. Verified: `sanitize()` runs `EMAIL_PATTERN` first, replacing any match
   with the literal `REDACTION` constant, `"[contact details removed]"` — confirmed via direct read
   (`ContactDetailSanitizer.java:21`) that this string contains **zero digits**. `PHONE_PATTERN` (both
   before and after `skillars-deferred-72`'s AC2 fix) structurally requires a leading and trailing digit to
   match at all (`[\d][\d\s\-().]{6,14}[\d]`) — it therefore cannot match anywhere inside already-redacted
   text, regardless of what the original input contained. True before AND after `skillars-deferred-72`'s
   regex change; unaffected by it. Applied: `` `[CLOSED by skillars-deferred-73 (verified unreachable):
   REDACTION ("[contact details removed]") contains zero digits, and PHONE_PATTERN requires a leading/
   trailing digit — it structurally cannot match inside already-redacted text. Not merely benign, actually
   impossible.]` ``

## Tasks / Subtasks

- [x] Task 1: Re-mine Marketplace/Coach-profile — confirm fully exhausted after `skillars-deferred-72`'s
      closures (no code change; verification only).
- [x] Task 2: Escalate to Auth/Registration; sample-check bullets by staleness signal against live source.
- [x] Task 3: Apply the three confirmed closures to `deferred-work.md` (all done directly at story
      creation).
- [x] Task 4: Investigate the `SUSPENDED`-during-OTP candidate (D3) directly; confirm it remains correctly
      open for a more precise reason, not a missed closure — recorded in "Why this story exists" above, no
      ledger edit needed since the item's premise (no guard exists) remains accurate.

## Dev Notes

**Why this story has no Acceptance Criteria.** Every genuinely-open item found was a ledger-hygiene
closure, not a code change — matching `skillars-deferred-61`'s precedent for a thin-pass result. Status is
set directly to `done`; there is nothing for a `dev-story` pass to implement.

**Explicit scope boundary — not a full audit.** This pass sample-checked roughly 5 of Auth/Registration's
~70 untagged bullets, chosen by staleness signal (items whose premise plausibly changed since filing), not
an exhaustive read of every bullet. This was a deliberate choice, confirmed with the project owner directly
when the initial thin-pass finding was reported: given (a) every item actually checked turned out stale,
not a live bug, and (b) the remaining unchecked items read as already-accepted-by-design at filing time
even in their own original text, the project owner chose to ship this smaller, verified 3-item closure now
rather than commit to the larger, uncertain-yield effort of a full ~65-item pass. A full pass remains
available as a future option if the project owner wants it.

**Scope discipline.** This story does not resolve the one real open question it surfaced (D3's underlying
"should account-level suspension gate registration completion?" question) — that is a product decision for
the project owner to make deliberately, not a call this ledger-hygiene pass is positioned to make
unilaterally. It is also not "closed" as stale, since the guard genuinely does not exist yet; it is simply
left open with a more precise diagnosis than its original filing had.

### Project Structure Notes

No production or test source files touched. One file modified: `deferred-work.md` (three `[CLOSED by
skillars-deferred-73 ...]` tags appended, no bullets deleted, no sections emptied).

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` — the file re-mined by this story's creation
  pass.
- `skillars-deferred-72-booking-lock-contention-test-coverage-contact-sanitizer-false-positives-and-ledger-hygiene.md`
  — the immediately-prior story, whose own AC5 ledger-hygiene closures (also individually re-verified
  against live source before applying) this story's own methodology directly mirrors.
- `skillars-deferred-61-ledger-verification-sweep-no-actionable-items-found.md` — the precedent this
  story's "no Acceptance Criteria, status done immediately" shape follows.

## Dev Agent Record

### Agent Model Used

claude-sonnet-5 (Claude Code)

### Debug Log References

None — no code was written, so there was nothing to debug. All live-source verification is recorded
inline in "Why this story exists" and the "Closures applied" section above, each citing the exact file:line
checked.

### Completion Notes List

- Marketplace/Coach-profile re-checked and confirmed fully exhausted after `skillars-deferred-72`'s
  closures.
- Escalated to Auth/Registration; sample-checked roughly 5 of ~70 untagged bullets by staleness signal
  (not an exhaustive pass — see Dev Notes for the explicit, project-owner-confirmed scope boundary).
- Three stale/already-fixed items found and closed, each independently re-verified against live source
  before applying the closure tag (not trusted from a prior research pass without independent
  confirmation): `AuthResourceIT`'s `@Testcontainers` non-gap, `fr-FR`'s present `auth.coach` sub-tree, and
  `ContactDetailSanitizer`'s structurally-unreachable double-redaction concern.
- One candidate (D3, `SUSPENDED`-during-OTP) investigated directly and confirmed to remain correctly open,
  for a more precise reason (no `CoachProfile` exists yet at that point in the registration flow) than
  originally filed — not closed, not picked up as a code fix, since the underlying product question is
  unresolved.
- Zero live bugs found; zero items requiring a product decision were resolved unilaterally. Status set
  directly to `done` — no Acceptance Criteria, nothing for a `dev-story` pass to pick up.

### File List

- `_bmad-output/implementation-artifacts/deferred-work.md`

## Change Log

- 2026-08-26: Story created via story-creation process. Marketplace/Coach-profile re-confirmed exhausted
  after `skillars-deferred-72`; escalated to Auth/Registration (never re-mined by any of the 72 prior
  stories in this series). Sample-checked ~5 of ~70 untagged bullets by staleness signal; every one
  checked turned out stale or already-fixed (three closed: `AuthResourceIT`'s `@Testcontainers` non-gap,
  `fr-FR`'s present `auth.coach` sub-tree, `ContactDetailSanitizer`'s structurally-unreachable
  double-redaction concern). One further candidate (D3, `SUSPENDED`-during-OTP) investigated and confirmed
  still correctly open for a more precise reason. Presented to the project owner directly: ship this
  smaller, fully-verified 3-item closure now, do a full ~65-item exhaustive pass, or skip the cycle —
  project owner chose to ship now. Status set directly to `done`; no Acceptance Criteria, nothing for a
  `dev-story` pass.
