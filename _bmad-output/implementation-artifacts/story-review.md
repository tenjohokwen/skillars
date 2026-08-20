# Senior Dev Review: skillars-deferred-44 (Video Approval Observability Granularity & Player-Redirect Error Differentiation)

Reviewed: `_bmad-output/implementation-artifacts/skillars-deferred-44-video-approval-observability-granularity-and-player-redirect-error-differentiation.md`

Method: every factual claim was re-verified against current code, not taken on the story's word. Read in
full: `VideoApprovalResource.java`, `VideoResource.java`, `PlayerHomeRedirectPage.vue`,
`CoachPublicProfilePage.vue`, `BookingRequestPage.vue`, `playerStore.js`, `playerRegistration.api.js`,
`boot/axios.js`, `router/routes.js`, `ShadowAccountResource.java`, `ShadowAccountService.java`,
`ApiAdvice.java`, `QuotaConfigService.java`, `MessagingService.java`, and all four `deferred-work.md` sections
AC3 targets. AC1's three method locations, AC2's precedent citations (`CoachPublicProfilePage.vue:309-317`,
`BookingRequestPage.vue:598-607`), the `common.errorGeneric` presence in all three locale bundles, and both
AC3 stale-item technical claims (the `Instant.EPOCH` comment and the switch-expression-vs-`MatchException`
reasoning) all checked out exactly — including confirming `resolveTierKey()` really is a `default`-less
switch *expression*, which JLS 14.11.1 does require to be exhaustive at compile time. AC3's four ledger tags
are also already present in `deferred-work.md` verbatim, matching the story's own stated precedent that these
are applied at story-creation time (not a defect — this mirrors `skillars-deferred-42`/`-43`'s corrected
convention, already confirmed working as intended). Two problems were found: a real behavioral gap in AC2's
catch-block scope that the story's own mirrored precedent doesn't share, and a factual error in AC1's prose
about `VideoResource`'s annotation ordering convention.

---

## Finding 1 (Medium, confirmed): AC2's `catch` also wraps the success-path redirect, so a navigation/chunk-load failure after a *successful* profile fetch will be misreported as a profile error and wrongly send an onboarded player to the profile-builder

**Where:** AC2's instruction to change `catch { router.replace('/player/profile-builder') }` to
`catch (err) { if (err.response?.status !== 404) { $q.notify(...) } router.replace('/player/profile-builder') }`,
applied to `PlayerHomeRedirectPage.vue:16-22` as-is (the story explicitly says the surrounding
`try`/`catch`/redirect shape is unchanged, only the catch body is edited).

The current (and, per AC2, still-unchanged) shape is:

```js
onMounted(async () => {
  try {
    const id = await playerStore.fetchSelfPlayerId()
    router.replace(`/player/locker-room/${id}`)   // line 18 — inside the try
  } catch {
    router.replace('/player/profile-builder')
  }
})
```

The `try` block covers **two** statements, not one: the `fetchSelfPlayerId()` call *and* the success-path
`router.replace('/player/locker-room/${id}')` navigation. `player/locker-room/:playerId`
(`router/routes.js:234-238`) is a lazy-loaded route (`component: () => import('pages/player/PlayerLockerRoomPlaceholderPage.vue')`), so `router.replace()` here can reject on its own — most plausibly from a dynamic
`import()` failure (a stale JS chunk hash after a deploy is a common real-world SPA failure mode, not a
contrived one).

Contrast this with the exact precedent AC2 cites: in `CoachPublicProfilePage.vue:308-318` and
`BookingRequestPage.vue:598-607`, the inner `try` wraps **only** the `fetchSelfPlayerId()` call — nothing
that runs after a successful fetch is inside that same `try`. AC2 copies the precedent's `if
(err.response?.status !== 404) { $q.notify(...) }` condition verbatim, but doesn't account for the fact that
`PlayerHomeRedirectPage.vue`'s pre-existing `try` is scoped more broadly than the pattern it's copying from.

Consequence once AC2 ships: if `router.replace('/player/locker-room/${id}')` throws for any reason (the
fetch itself already having succeeded — the player *does* have a profile), the rejection has no
`.response` property, so `err.response?.status !== 404` evaluates to `true`. The new code will (a) show a
"Something went wrong" toast that misattributes the failure to the profile fetch, which actually succeeded,
and (b) still unconditionally redirect the player to `/player/profile-builder` — a page with no guard against
an already-existing profile (`PlayerProfileBuilderPage.vue`'s submit just calls
`playerRegistrationApi.createProfile()`, and `ShadowAccountService.createSelfOwnedPlayerProfile()` only
rejects the *second* POST with a 409-mapped `ShadowAccountException` after the player has already been shown
the builder form). This is worse than today's bare-catch behavior for this specific scenario: today, that
same navigation failure silently redirects with no message at all; after AC2, it silently redirects **plus**
shows a toast actively telling an onboarded player something is wrong with their profile, which isn't true.

**Recommendation:** Narrow the `try` to wrap only `await playerStore.fetchSelfPlayerId()`, moving the
success-path `router.replace(...)` outside the `try`/`catch` (mirroring the precedent's scoping exactly, not
just its condition) — or, if the broader `try` is kept deliberately, note in AC2/Dev Notes that a
post-fetch-success navigation failure will be misclassified as a non-404 profile error and accept that
tradeoff explicitly rather than leaving it unaddressed.

---

## Finding 2 (Low, confirmed): AC1's prose claims `@Observed` mirrors `VideoResource`'s convention of being placed "first, above the mapping annotation" — the actual convention is the opposite

**Where:** AC1's closing sentence: *"Naming mirrors `VideoResource`'s exact `<class-scope>.<method-scope>`
dot-hierarchy... applied to `video.approvals`'s own scope... and annotation ordering on each method mirrors
`VideoResource`'s own convention of placing `@Observed` first, above the mapping annotation."*

Direct read of every one of `VideoResource`'s five annotated methods shows `@Observed` placed **last**,
immediately above the method signature — after the mapping annotation, after `@PreAuthorize`, and after
`@ResponseStatus` where present, never first:

```
67  @PostMapping("/uploads/initiate")
68  @PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
69  @Observed(name = "video.upload.initiate")        // last, not first
70  public ResponseEntity<...> initiateUpload(

151 @DeleteMapping("/{id}")
152 @PreAuthorize("@videoAccessGuard.canDelete(authentication, #id)")
153 @ResponseStatus(HttpStatus.NO_CONTENT)
154 @Observed(name = "video.delete")                  // last, not first
155 public void deleteVideo(@PathVariable UUID id) {
```

All five methods (`:69`, `:96`, `:116`, `:131`, `:154`) follow this same last-position pattern with zero
exceptions. AC1's own concrete per-bullet instructions ("add `@Observed(...)` immediately above
`listPendingApprovals()`", i.e., last, right before the method) actually get the position right — it's only
the summary sentence describing *why* that's correct that states the rule backwards. Low functional risk
(annotation order doesn't affect Spring AOP/Micrometer semantics either way), but a dev or agent trusting the
prose over the positional instruction could place the three new annotations first, immediately above
`@GetMapping`/`@PutMapping`, which would not actually match `VideoResource`'s convention and would partially
defeat this AC's stated purpose of mirroring it.

**Recommendation:** Correct the sentence to say `@Observed` is placed **last**, immediately above the method
declaration (after the mapping/`@PreAuthorize`/`@ResponseStatus` annotations), matching what the per-bullet
instructions already correctly specify.
