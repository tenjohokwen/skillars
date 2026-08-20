# Story Deferred-44: Video Approval Observability Granularity & Player-Redirect Error Differentiation

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer operating this platform,
I want two independently-real, independently-small, decision-light hygiene gaps found by re-mining the
full `deferred-work.md` ledger closed in one pass — `VideoApprovalResource`'s class-only `@Observed`
annotation gaining the same per-method observability granularity its sibling `VideoResource` already has,
and `PlayerHomeRedirectPage.vue`'s bare `catch` gaining the same 404-vs-genuine-failure differentiation the
two sibling self-profile-fetch call sites already ship (added by `skillars-deferred-43`, the immediately
preceding story) —
so that these gaps stop accumulating as separate single-item stories, matching the bundling convention every
prior `skillars-deferred-*` pass has followed.

### Why this story exists

This story's creation was explicitly instructed to **bundle several small, unrelated, decision-light
items into one story rather than create another narrow 1-2 AC story** — the pattern every prior
`skillars-deferred-*` pass has followed, most recently `skillars-deferred-43`.

`_bmad-output/implementation-artifacts/deferred-work.md` (1612 lines as of this story's creation) was
re-mined end to end, section by section, following the file's own documented protocol near its top: an item
is non-actionable (skipped) if it already carries `[CLOSED ...]`, `[PICKED UP ...]`, `[STALE ...]`,
`[DISMISSED ...]`, `[WITHDRAWN ...]`, `[SUPERSEDED ...]`, `[MITIGATED ...]`, `[OWNED BY ...]`, `[AUDIT ...]`,
or prose framing it as an accepted tradeoff, a by-design decision, or something needing a product/design
call before any fix is possible. The remaining untagged bullets were triaged by section, and every genuine
candidate was re-verified against the **live code** — not trusted from the ledger's own prose — before being
used, including two items discovered during that re-verification to already be resolved or based on an
incorrect technical premise (bundled as this story's own hygiene AC, mirroring `skillars-deferred-42` AC4's
and `skillars-deferred-43` AC3's pattern).

**Two genuinely open, decision-light items were found and are bundled here:**

- **`VideoApprovalResource`'s `@Observed` annotation is class-level only, unlike its sibling `VideoResource`,
  which carries both a class-level annotation and a finer-grained annotation on every individual endpoint.**
  `## Deferred from: code review of skillars-6-6-player-video-management-portal (2026-06-24)`, W7: "`
  @Observed(name = "video.approvals")` at class level on `VideoApprovalResource` — loses per-operation
  observability granularity vs. per-method `@Observed` pattern used throughout `VideoResource`; minor
  deviation from project pattern." Re-verified today: `VideoApprovalResource.java:26-31` still carries only
  the single class-level `@Observed(name = "video.approvals")`; none of its three endpoints
  (`listPendingApprovals` at `:38-40`, `approveVideo` at `:61-64`, `rejectVideo` at `:68-71`) has its own
  `@Observed`. Direct read of `VideoResource.java` (the module's other resource class, confirmed still
  matching the item's own characterization) confirms the pattern this item names: a class-level
  `@Observed(name = "video.upload")` (`:44`) **plus** a distinct, more specific `@Observed` on every method —
  `video.upload.initiate` (`:69`), `video.player.upload.initiate` (`:96`), `video.quota.query` (`:116`),
  `video.list.my` (`:131`), `video.delete` (`:154`). This is a real, still-open deviation from an established,
  same-module convention, not a case of "two equally valid patterns" — unlike the superficially similar,
  already-dismissed `skillars-1-5` item about `AuthResource` ("class-level is a valid Micrometer pattern; no
  metric data lost"), which explicitly frames the class-level-only choice as acceptable rather than as a
  named deviation from a sibling's convention. **Candidate for this story (AC1).**

- **`PlayerHomeRedirectPage.vue`'s bare `catch` treats every error — a 404 "no profile yet" and a genuine
  network/500 failure alike — identically, redirecting to the profile-builder either way with no signal to
  the player that anything went wrong.** `## Deferred from: code review of
  skillars-deferred-43-player-registration-otp-coverage-and-self-profile-fetch-caching (2026-08-20)`: "
  `PlayerHomeRedirectPage.vue`'s bare `catch` treats any error — a 500, a network failure, not just a 404
  'no profile yet' — as reason to silently redirect to `/player/profile-builder`. Pre-existing behavior, not
  introduced by this diff (the `try`/`catch`/redirect shape was explicitly left untouched per this story's
  own AC2 instruction, only the fetch call inside it was swapped). A genuine backend/network failure is
  indistinguishable from 'player hasn't finished onboarding' from the player's point of view." Re-verified
  today: `PlayerHomeRedirectPage.vue:19-22`'s `catch { router.replace('/player/profile-builder') }` still has
  no error-shape check of any kind. The immediately-preceding story, `skillars-deferred-43`, shipped the
  exact fix pattern needed one file over: `CoachPublicProfilePage.vue:310-317` and
  `BookingRequestPage.vue`'s equivalent block both already do `if (profileErr.response?.status !== 404) {
  $q.notify({ type: 'negative', message: t('common.errorGeneric') }) }` around the identical
  `playerStore.fetchSelfPlayerId()` call this page also makes — confirmed by direct read of both files, and
  confirmed `common.errorGeneric` already exists in all three locale bundles (`en-US/index.js:532`,
  `de-DE/index.js:13`, `fr-FR/index.js:550`), so no new i18n key is needed. This is a real, small,
  decision-light gap with an already-shipped, directly-mirrorable fix pattern one file away — not a case
  needing a fresh UX decision. **Candidate for this story (AC2).**

**Two additional items were found to be stale or based on an incorrect premise during this research — not
story material, but corrected here as a hygiene by-product (AC3):**

- **`## Deferred from: code review of skillars-8-1-messaging-module-foundation-conversation-threads
  (2026-06-26)` D4, never tagged before now — found already resolved.** "Instant.EPOCH as 'never read'
  sentinel is undocumented — all messages with createdAt > epoch count as unread, which is correct for
  current data; fragile if a historical data migration backfills timestamps at or before epoch."
  Re-verified today: `MessagingService.java:391` already carries the comment `// Null means never read: treat
  all messages as unread by using epoch as sentinel` immediately above the `Instant.EPOCH` usage at `:392` —
  exactly the documentation this item asked for. This was actually already noticed once before: the
  `skillars-deferred-16` story-creation audit (2026-08-05) recorded, under its own "Examined and
  deliberately left alone" section, "`skillars-8-1` D4 (`Instant.EPOCH` sentinel undocumented — a comment
  already stands at `MessagingService:323`)" — but that audit note was never used to tag the original bullet
  itself, so it has stood untagged through 15 further story-creation passes since. Superseded by shipped
  code, not a gap.
- **`## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system (2026-06-20)` Def12,
  never audited before now — found to rest on an incorrect technical premise, not merely a stale one.**
  "`QuotaConfigService.resolveTierKey()` exhaustive switch will throw `MatchException` if
  `CoachSubscriptionTier` enum grows — safe now but fragile if a new coach tier is added." Re-verified
  today: `QuotaConfigService.java:34-49`'s `resolveTierKey()` uses a Java **switch expression** (arrow
  syntax, no `default` arm) over `CoachSubscriptionTier`, whose only three constants — `SCOUT`,
  `INSTRUCTOR`, `ACADEMY` — are confirmed via direct read of `CoachSubscriptionTier.java` to be exactly the
  three cases the switch covers. Per JLS 14.11.1, a `default`-less switch expression must be exhaustive at
  **compile time**: adding a fourth enum constant without also updating this switch fails the build, it does
  not throw a runtime `MatchException` (a Java 21+ pattern-matching-switch concept that does not apply to
  this Java 17 codebase's plain enum switch expression at all). The scenario the item warns about is
  therefore not merely low-probability — it cannot silently ship, ever, on this construct. Not a gap that
  code closed later; the item's own technical premise was incorrect from the moment it was written.

**Decision made during this story's creation — why these two and not others:** the ledger was triaged in
full (every section across all 1612 lines, not just the tail); the overwhelming majority of untagged bullets
either (a) already carry their own "examined and deliberately left alone" / "accepted tradeoff" / "by
design" / "spec-intentional" reasoning on record (e.g. the `PaymentPendingSweeper` `D3` no-automated-exit
decision, the batch path's deliberate absence of a create-time cross-booking overlap check, the several
`resultByBatch`/`batchAcceptResultsByBatch` rebuild-cost tradeoffs across `skillars-deferred-35` through
`-38`), (b) explicitly need a product or design decision before any fix is possible (e.g. `DisputeService`'s
`FROZEN`-payment-status gap left open by `skillars-deferred-41`'s own review, needing a coordinated design
call spanning both `DisputeService` and `RevenueReportingService`; the video-bandwidth per-call-vs-per-session
charging question left open by `skillars-deferred-40`'s own review, explicitly flagged as needing a full
design review before any fix), or (c) are restatements of the standing, repeatedly-declined
frontend-test-infrastructure investment (every `skillars-deferred-*` pass since `-17` has left this alone for
the same reason, most recently `skillars-deferred-43`'s own AC2 review deferral, and this pass leaves it
alone too). AC1 and AC2 are the only two items found that are simultaneously real, small, decision-light, and
directly mirror an already-shipped pattern in this same codebase — bundled here purely because both clear
that bar and this pass was asked to bundle rather than defer them a further time. As with `skillars-deferred-
43`, the ledger continues to run thin after 43 prior passes — only two substantive items cleared the bar this
time, the same count as the immediately preceding story.

## Acceptance Criteria

1. **AC1 — `VideoApprovalResource` gains a per-method `@Observed` annotation on each of its three endpoints,
   mirroring `VideoResource`'s already-established class-plus-method observability naming convention.** In
   `src/main/java/com/softropic/skillars/platform/video/api/VideoApprovalResource.java`, keep the existing
   class-level `@Observed(name = "video.approvals")` (`:26`) unchanged, and add:
   - `@Observed(name = "video.approvals.list")` immediately above `listPendingApprovals()` (`:38-40`,
     alongside its existing `@GetMapping`/`@PreAuthorize`).
   - `@Observed(name = "video.approvals.approve")` immediately above `approveVideo()` (`:61-64`, alongside
     its existing `@PutMapping`/`@PreAuthorize`/`@ResponseStatus`).
   - `@Observed(name = "video.approvals.reject")` immediately above `rejectVideo()` (`:68-71`, alongside its
     existing `@PutMapping`/`@PreAuthorize`/`@ResponseStatus`).

   Naming mirrors `VideoResource`'s exact `<class-scope>.<method-scope>` dot-hierarchy (e.g.
   `video.upload` class-level + `video.upload.initiate` method-level) applied to `video.approvals`'s own
   scope. `io.micrometer.observation.annotation.Observed` is already imported in
   `VideoApprovalResource.java` (used by the existing class-level annotation) — no new import needed. This
   is a pure, additive, non-functional annotation change: no method body, signature, `@PreAuthorize`, or
   `@ResponseStatus` is touched, and annotation ordering on each method mirrors `VideoResource`'s own
   convention of placing `@Observed` first, above the mapping annotation.

2. **AC2 — `PlayerHomeRedirectPage.vue`'s `catch` differentiates a 404 ("no profile yet", the expected,
   silent case) from any other error (surfaced via a toast), mirroring the pattern
   `skillars-deferred-43` already shipped one file over — the redirect destination itself is unchanged in
   both cases.** In `src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue`:
   - Add `import { useQuasar } from 'quasar'` and `import { useI18n } from 'vue-i18n'`, and inside
     `<script setup>` (alongside the existing `const router = useRouter()` / `const playerStore =
     usePlayerStore()`), add `const $q = useQuasar()` and `const { t } = useI18n()` — mirroring
     `CoachPublicProfilePage.vue`'s own top-of-`<script setup>` declarations for the identical helpers.
   - Change the `catch` block (`:19-22`) from the current unconditional
     `catch { router.replace('/player/profile-builder') }` to a named-parameter `catch (err) { ... }` that:
     (a) checks `if (err.response?.status !== 404) { $q.notify({ type: 'negative', message:
     t('common.errorGeneric') }) }` — the exact expression and message key `CoachPublicProfilePage.vue:315-
     317` already uses around the identical `playerStore.fetchSelfPlayerId()` call — then (b)
     **unconditionally** still calls `router.replace('/player/profile-builder')`, exactly as today, for
     **both** the 404 and the non-404 case. The redirect destination and control flow are deliberately
     **not** changed for the non-404 case: profile-builder is a safe, harmless landing spot regardless of
     which error occurred (a player who already has a profile and hit a transient network/500 error simply
     sees their existing info, or a chance to retry, not a security or data-loss issue), and inventing a new
     fallback destination for the error case would be exactly the kind of unscoped UX decision this pass is
     not making. Only the **notification**, not the **navigation**, is new.
   - No new i18n key is needed — `common.errorGeneric` is already defined in all three locale bundles.

3. **AC3 — Ledger hygiene.** In `deferred-work.md`:
   - Tag the `## Deferred from: code review of skillars-6-6-player-video-management-portal (2026-06-24)` W7
     item (`VideoApprovalResource`'s class-level-only `@Observed`) with
     `` `[PICKED UP by skillars-deferred-44 AC1]` ``.
   - Tag the `## Deferred from: code review of
     skillars-deferred-43-player-registration-otp-coverage-and-self-profile-fetch-caching (2026-08-20)`
     `PlayerHomeRedirectPage.vue` bare-`catch` item with `` `[PICKED UP by skillars-deferred-44 AC2]` ``.
   - Tag the `## Deferred from: code review of skillars-8-1-messaging-module-foundation-conversation-threads
     (2026-06-26)` D4 item (`Instant.EPOCH` sentinel undocumented) with `` `[STALE — verified against current
     code by skillars-deferred-44 story creation, 2026-08-20: already resolved, ledger not updated.
     MessagingService.java:391 already carries the comment "// Null means never read: treat all messages as
     unread by using epoch as sentinel" immediately above the Instant.EPOCH usage at :392. Already noticed
     once before by the skillars-deferred-16 story-creation audit's "Examined and deliberately left alone"
     section, but never tagged onto this original bullet — tagged now.]` `` — do not delete the item, per
     this file's own "delete only once genuinely implemented, not once merely annotated" convention.
   - Tag the `## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system
     (2026-06-20)` Def12 item (`QuotaConfigService.resolveTierKey()` `MatchException` concern) with
     `` `[STALE — verified against current code by skillars-deferred-44 story creation, 2026-08-20: the
     premise is incorrect, not merely outdated. QuotaConfigService.java:34-49's resolveTierKey() is a
     default-less Java switch EXPRESSION over CoachSubscriptionTier (exactly 3 constants: SCOUT, INSTRUCTOR,
     ACADEMY, confirmed by direct read). Per JLS 14.11.1 a default-less switch expression must be exhaustive
     at COMPILE TIME — a fourth enum constant added without updating this switch fails the build, it cannot
     throw a runtime MatchException (a Java 21+ pattern-matching-switch concept, inapplicable to this Java 17
     plain-enum switch expression). Not a gap later code closed; the item's technical premise was wrong from
     the start.]` ``.

## Tasks / Subtasks

- [ ] Task 1: `VideoApprovalResource` per-method `@Observed` granularity (AC: #1)
  - [ ] 1.1 Add `@Observed(name = "video.approvals.list")` above `listPendingApprovals()`.
  - [ ] 1.2 Add `@Observed(name = "video.approvals.approve")` above `approveVideo()`.
  - [ ] 1.3 Add `@Observed(name = "video.approvals.reject")` above `rejectVideo()`.
  - [ ] 1.4 Confirm the backend still compiles (`mvn -o -q clean compile`) — no test is added or expected for
    this annotation-only change (see Dev Notes).
- [ ] Task 2: `PlayerHomeRedirectPage.vue` error differentiation (AC: #2)
  - [ ] 2.1 Add `useQuasar`/`useI18n` imports and their `$q`/`t` declarations.
  - [ ] 2.2 Change the bare `catch` to a named `catch (err)`, add the non-404 `$q.notify(...)` branch, keep
    the unconditional `router.replace('/player/profile-builder')` call unchanged for both branches.
  - [ ] 2.3 Run `npx eslint` on the touched file and confirm clean.
- [ ] Task 3: Ledger hygiene (AC: #3) — apply the two `[PICKED UP]` tags and two `[STALE]` annotations
  specified in AC3 above.

### Review Findings

## Dev Notes

- **This story bundles two unrelated fixes across two areas (backend video-module observability,
  frontend auth-redirect error handling) by explicit instruction — do not look for a unifying theme beyond
  "small, real, decision-light, and this pass was asked to bundle."**
- **AC1 needs no new automated test.** This codebase does not test `@Observed` annotation presence anywhere
  (`grep -rn "@Observed" src/test` returns zero hits) — Micrometer's own aspect wiring reads the annotation
  at runtime via AOP, already proven to work by every other `@Observed`-annotated endpoint in this codebase,
  and the change here is annotation-only with zero behavioral surface to assert against. A successful build
  is the complete verification bar for this AC, matching how `skillars-1-5`'s own equivalent
  class-vs-per-method `@Observed` discussion was framed (no test was ever proposed for it there either).
- **AC2's fix is deliberately narrow: notify-on-non-404 only, no navigation change.** Do not invent a new
  redirect destination for the non-404 case, and do not add a "retry" affordance or loading-state change —
  those are the kind of unscoped UX decisions this pass is explicitly not making. The existing
  `router.replace('/player/profile-builder')` call already runs unconditionally today for every error shape;
  it must keep running unconditionally after this change too. If a future story wants a different fallback
  destination for a genuine failure, that is its own product decision, not a unilateral expansion of this AC.
- **AC2's `$q.notify` call and message key are a direct, byte-for-byte-in-spirit copy of
  `CoachPublicProfilePage.vue:315-317`'s existing pattern**, not a new design. Do not invent new wording,
  a new i18n key, or a different notify `type`/style — reuse `common.errorGeneric` and `type: 'negative'`
  exactly as the precedent does.
- **AC3's ledger hygiene (Task 3) should be applied as part of this story's own creation, per the established
  `skillars-deferred-43` precedent** (that story's own Dev Notes record its tags as "already present in
  `deferred-work.md` as committed alongside this story file"). Confirm the two `[PICKED UP]` tags and two
  `[STALE]` annotations are present verbatim in `deferred-work.md` before marking Task 3 complete, rather than
  re-applying them if `dev-story` finds them already there.
- Per `docs/validation-strategy.md`, run targeted verification only (`mvn -o -q clean compile` for AC1,
  `npx eslint` on the one touched frontend file for AC2) — do not run `mvn verify` unless targeted
  verification proves insufficient or this is final pre-PR validation.

### Project Structure Notes

- `src/main/java/com/softropic/skillars/platform/video/api/VideoApprovalResource.java` — three new
  method-level `@Observed` annotations added (AC1). No other line changes.
- `src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue` — two new imports, two new top-of-`<script setup>`
  declarations, `catch` block changed from bare/unconditional to named-parameter with a non-404 notify branch
  (AC2). The `onMounted`/`try` block, the success-path `router.replace` call, and the 404-path redirect
  destination are all unchanged.
- `_bmad-output/implementation-artifacts/deferred-work.md` — two `[PICKED UP]` tags + two `[STALE]`
  corrections (AC3).
- No new backend or frontend files. No changes to `VideoResource.java`, `CoachPublicProfilePage.vue`,
  `BookingRequestPage.vue`, `MessagingService.java`, `QuotaConfigService.java`, `CoachSubscriptionTier.java`,
  or `playerStore.js` — all are read-only precedents or (for the two `[STALE]` items' source files) content
  this story confirms is already correct and does not modify.

### References

- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-6-6-player-video-management-portal (2026-06-24)` W7 — AC1's source]
- [Source: `src/main/java/com/softropic/skillars/platform/video/api/VideoApprovalResource.java` lines 26-31,
  38-40, 61-64, 68-71 — AC1's target]
- [Source: `src/main/java/com/softropic/skillars/platform/video/api/VideoResource.java` lines 44, 69, 96,
  116, 131, 154 — AC1's mirrored naming convention]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-deferred-43-player-registration-otp-coverage-and-self-profile-fetch-caching (2026-08-20)` — AC2's
  source]
- [Source: `src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue` lines 1-24 — AC2's target]
- [Source: `src/frontend/src/pages/marketplace/CoachPublicProfilePage.vue` lines 235-236, 249-250, 309-318 —
  AC2's mirrored pattern (imports, declarations, and the notify branch itself)]
- [Source: `src/frontend/src/i18n/en-US/index.js:532`, `de-DE/index.js:13`, `fr-FR/index.js:550` — confirms
  `common.errorGeneric` already exists in all three bundles, no new key needed]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-8-1-messaging-module-foundation-conversation-threads (2026-06-26)` D4 — AC3's stale item]
- [Source: `src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java` lines
  390-393 — AC3's D4 stale-item verification]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `## Deferred from: code review of
  skillars-6-1-video-module-foundation-quota-system (2026-06-20)` Def12 — AC3's other stale item]
- [Source: `src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java` lines 34-49
  — AC3's Def12 stale-item verification]
- [Source: `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachSubscriptionTier.java` —
  confirms exactly 3 enum constants (SCOUT, INSTRUCTOR, ACADEMY)]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Change |
|---|---|
| 2026-08-20 | Story created via story-creation process: bundled 2-item story per explicit instruction not to create another small story. Re-mined `deferred-work.md` end to end (1612 lines), re-verifying every candidate against current code rather than trusting ledger text. AC1 closes `VideoApprovalResource`'s class-level-only `@Observed` gap (flagged by `skillars-6-6`'s own code review, 2026-06-24) by mirroring `VideoResource`'s already-established per-method observability naming convention within the same video module. AC2 closes `PlayerHomeRedirectPage.vue`'s bare-`catch`-treats-every-error-identically gap (flagged by `skillars-deferred-43`'s own code review, the immediately preceding story) by mirroring the 404-vs-genuine-failure notify pattern `skillars-deferred-43` itself already shipped one file over, in `CoachPublicProfilePage.vue`. AC3 additionally closes 2 stale/incorrect-premise ledger items found during the full re-mine — a messaging-module `Instant.EPOCH` documentation item that was already resolved but never tagged (noticed once before by the `skillars-deferred-16` audit, never applied to the original bullet), and a video-quota-module `MatchException` concern whose technical premise was wrong from the start (a default-less Java switch expression is exhaustive at compile time, not runtime). Ledger remains thin after 43 prior passes — only two substantive items cleared the real/small/decision-light/directly-mirrorable bar this pass, matching the prior pass's count. |
