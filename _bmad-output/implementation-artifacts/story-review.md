# Story Review: Deferred-48 — Booking Config-Fetch Negative-Auth Coverage & Response-Shape Hardening

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-48-booking-config-fetch-negative-auth-coverage-and-response-shape-hardening.md`

Method: every factual claim in the story (line numbers, "no other call site" claims, the security-annotation
behavior AC1 rests on, and the AC3 ledger-tag state) was re-verified against the current code on this branch,
not trusted from the story's own prose. Read in full: `BookingResource.java`, `BookingRequestResourceIT.java`
(all `@Test` methods, `setUp`, helpers), `SecurityConstants.java`, `ApplicationAccessDeniedHandler.java`,
`ApiAdvice.java`'s `AccessDeniedException` handler, `BookingRequestPage.vue` (full `<script>` block), and
`deferred-work.md`'s two cited items. Specifically checked and ruled out as non-issues:

- **AC1's 403 mechanism.** `AccessDeniedException` (thrown by Spring Security when `@PreAuthorize`'s
  `HAS_PARENT_OR_PLAYER_ROLE` expression fails) is mapped to `HttpStatus.FORBIDDEN` by
  `ApiAdvice.java:249-257`, routed there via `ApplicationAccessDeniedHandler`. A coach caller genuinely gets
  403, not 401 or 500 — AC1's expected outcome is correct.
- **AC1's "mirrors existing pattern" framing.** `acceptBooking_wrongCoach_returns403` /
  `declineBooking_wrongCoach_returns403` (the cited precedent) actually assert a different mechanism — a
  second *same-role* coach rejected by `BookingService.acceptBooking`'s ownership check
  (`OperationNotAllowedException` → 403), not a `@PreAuthorize` role rejection. The assertion *shape*
  (`assertThatThrownBy(...).isInstanceOf(HttpClientErrorException.class)...isEqualTo(HttpStatus.FORBIDDEN)`)
  is identical and the new test is correct regardless, so this doesn't change AC1's outcome — noted only
  because the story's "mirrors this file's own established wrong-role negative-test shape" description
  slightly overstates how analogous the two mechanisms are. Not worth a story edit.
- **AC1's "no PLAYER-role fixture" claim.** Grep-confirmed: no `PLAYER_EMAIL`/`PLAYER` role user constant
  exists anywhere in `BookingRequestResourceIT.java`. Correct basis for scoping a `PLAYER`-role test out.
- **AC1 test's setup dependencies.** `getConfig()` takes no booking-specific arguments
  (`bookingService.getActiveSlotStatuses()` is parameterless); `@PreAuthorize` short-circuits before the
  controller method body runs. The new test needs nothing beyond `COACH_EMAIL`'s existing `setUp()` insert —
  no additional fixture data required, contrary to a plausible-looking concern that a coach with no
  associated booking data might hit a different failure mode.
- **AC2's guard logic.** Both snippets correctly reject non-integer/non-positive `maxSize` and non-array
  `activeSlotStatuses`, falling back to the pre-fetch `ref` default exactly as specified. `res` here is
  already `response.data` (axios interceptor unwraps it — confirmed unchanged from the prior story's own
  review), so `res.activeSlotStatuses`/`res.maxSize` remain the correct access shape.
- **AC3's ledger tags.** Both `[PICKED UP by skillars-deferred-48 AC1]` / `AC2` tags are already present on
  `deferred-work.md`'s cited lines (1625-1626) — consistent with this project's established convention (seen
  in every prior `skillars-deferred-*` review) of applying the tag at story-creation time, not dev time. Not
  a defect.
- **All cited line numbers** (`BookingResource.java:49-53`, `BookingRequestPage.vue:622-633,436`) match the
  current file contents exactly.

One real functional gap was found in AC2's scope. No issues survived in AC1 or AC3.

## Findings

### 1. AC2 hardens two of `getBatchConfig()`'s three call sites — the third, in `submitBatchRequest()`'s error-recovery branch, keeps the exact unguarded assignment the story exists to fix

**Severity: Medium (confirmed) — not a crash, but silently breaks basket-add gating and renders a broken
user-facing toast, in exactly the malformed-response scenario this story is hardening against.**

**Where:** `BookingRequestPage.vue:556-557`, inside `submitBatchRequest()`'s `catch` block, `errorKey ===
'booking.batchSizeExceeded'` branch:

```js
const res = await getBatchConfig()
maxBatchSize.value = res.maxSize
```

This is the *same* `getBatchConfig()` call and the *same* `maxBatchSize.value = res.maxSize` unguarded
assignment AC2 patches at `:623-624` (the `onMounted` copy). The story's own AC2 text scopes itself explicitly
to "the two `try`/`catch` blocks added by `skillars-deferred-47`... immediately after
`bookingStore.loadPlayerPacks`" — i.e. only the two `onMounted` fetches — and Dev Notes states "AC2 is not
behavior-changing on the happy path" without acknowledging this third call site exists at all. The source
deferred item this story is bundled from also only names the `:622` `onMounted` copy as the "identical
unvalidated-trust pattern," missing this one.

**Consequence of a malformed/contract-drifted `getBatchConfig()` response reaching this branch specifically**
(e.g. `res.maxSize` missing or non-numeric): `maxBatchSize.value` becomes `undefined`, and two other reactive
sites read it immediately afterward, both in this same file:
- `batchAtMax` (`:266`, `bookingStore.batchBasketSize >= maxBatchSize.value`) becomes permanently `false`
  regardless of basket size, since any comparison against `undefined` is `false`.
- `toggleSlotInBasket` (`:304`, `bookingStore.batchBasketSize < maxBatchSize.value`) becomes permanently
  `false` the same way — silently blocking the parent from adding *any* further slot to the basket for the
  rest of the session, with no error shown for the block itself.
- The toast fired one line later (`:559`, `t('booking.errors.batchSizeExceeded', { max: maxBatchSize.value
  })`) renders the literal word "undefined" in the user-facing message.

This branch is reached less often than the `onMounted` fetches (only when a parent's submit is rejected for
exceeding the batch size), but it is a real, reachable path with the identical shape-drift exposure the story
is otherwise closing, using the same API call.

**Recommendation:** apply AC2's same `Number.isInteger(res.maxSize) && res.maxSize > 0` guard (warning and
keeping the current value on failure) to this third call site before considering the `getBatchConfig()`
hardening complete, or explicitly re-scope AC2's text/Dev Notes to acknowledge this call site is left open and
why.
