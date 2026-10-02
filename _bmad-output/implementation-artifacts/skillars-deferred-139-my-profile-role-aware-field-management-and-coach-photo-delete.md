# Story: Role-Aware "My Profile" Field Management (Coach/Player/Parent) and Coach Photo Delete

**Story Key:** `skillars-deferred-139-my-profile-role-aware-field-management-and-coach-photo-delete`
**Epic:** Deferred Work
**Priority:** Top (explicit user instruction, 2026-10-01: give this item top priority over all other open `deferred-work.md` work when next picking a story).
**Status:** done
**Created:** 2026-10-02

---

## Context

Sourced from the single, explicitly top-priority item at the end of `deferred-work.md` (`:3411-3473`, "Deferred from: manual testing of coach profile-builder, 2026-10-01"). A coach who skipped the photo step during onboarding had no route back to add one — investigation of that bug found `ProfilePage.vue` (the one page routed as "My Profile" for **every** role, `routes.js:321-326`, `meta: { requiresAuth: true }`, no role gate) is strictly role-agnostic account settings (email/password/2FA/name/nationalId/gender/langKey/phone/address, via the six dialogs in `src/frontend/src/components/profile/`) and exposes **none** of the fields any role's onboarding/profile-builder flow collects, in either direction.

This story's own drafting re-verified every claim in that ledger entry against current `HEAD` (not re-used from the ledger blind) and resolved its four open follow-up questions:

1. **Coach steps 1-4 are confirmed idempotent-updatable even after publish** — `CoachProfileService.saveStep1`-`saveStep4` (`src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:156-281`) call `requireProfile(userId)` (a plain lookup, no status filter) and never call `requireDraftStatus` (which only exists at `:373-382` and is only ever invoked from `publishProfile`, `:306,329`). The only status-sensitive guard across all four is `saveStep4`'s `SUSPENDED` rejection (`:263-266`) — `ACTIVE` passes straight through every save method. This story reuses them as-is for editing; AC2's tests must empirically exercise this against a real `ACTIVE` test coach, not just trust the static read.
2. **The photo-delete gap is real and needs new backend work** — `saveStep5` (`:283-300`) only ever sets `photoUrl` inside `if (req.photoUrl() != null)`; there is no `else` branch and no way to distinguish "field omitted" from "please clear it." See AC3.
3. **UI approach decided:** extend `ProfilePage.vue` with new role-gated sections (the exact approach the ledger's own "Ask" quote proposed), not a separate page or tab — this keeps "My Profile" a single destination for every role and reuses the page's existing `.glass-card.profile-section` / `.profile-row` / `edit-btn` → dialog pattern (`ProfilePage.vue:26-124,249-275`).
4. **Player/parent endpoint-reuse check done:** unlike the coach's idempotent `PUT /steps/{n}`, **neither** player-profile endpoint is updatable — `POST /api/security/players/me` (self, `ShadowAccountResource.java:46-54` → `ShadowAccountService.createSelfOwnedPlayerProfile`, `:90-111`, which explicitly rejects re-creation via `existsByUserId` at `:91-93`) and `POST /api/security/players` (parent-created child, `ShadowAccountResource.java:36-44` → `createPlayerProfile`, `:43-82`, a plain insert) are both one-time `POST`s with zero `PUT`/`PATCH` counterpart anywhere in `platform.security`. AC4/AC5 add the missing update endpoints.
   - _(Review Patch 15, re-verified post-implementation: these citations had drifted by the two new imports `AC4` added to `ShadowAccountResource.java` — corrected here to the current `HEAD` line numbers.)_

**Role scope, explicit:** Coach (AC1-AC3), Player (AC4), Parent managing each child they own (AC5). **Admin is explicitly out of scope for new fields** (AC6) — there is no admin-specific profile-completion flow anywhere in the codebase, so Admin's existing Account + Personal Info cards already are its complete editable surface.

---

## AC1: New "fetch my full coach profile" endpoint, for edit-dialog prefill

**Problem:** `ProfileBuilderResource` only exposes `GET /status` (`coachId`, `status`, `lastCompletedStep`, `profileComplete` — no field values at all, `ProfileBuilderStatusResponse.java:5-10`) and the public `GET /api/marketplace/coaches/{coachId}` (`CoachMarketplaceResource.java:68-78` → `CoachProfileService.getPublicProfile`, `:415-476`) which (a) 404s for any non-`ACTIVE`/`REDUCED`/`PENDING_REVIEW` profile (`:419-425` — excludes `DRAFT`, so a coach who hasn't published yet can't use it to prefill their own edit form), and (b) omits `canonicalTimezone`, `sessionDurationMinutes`, and availability windows entirely, and its `SessionPackDto` (`:442`) carries no identifying id. None of the six `ProfilePage.vue` dialogs' pattern of "prefill from a `current*` prop" can work for the new coach section without a real self-fetch endpoint.

**Files:**
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachProfileSelfResponse.java` (**new** record)
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachAvailabilityWindowDto.java` (**new** record)
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` (new `getOwnProfile` method)
- `src/main/java/com/softropic/skillars/platform/marketplace/api/ProfileBuilderResource.java` (new `GetMapping` on the resource's own root path)

### The fix

Add `getOwnProfile(Long userId)` to `CoachProfileService`, assembled from the same repositories `saveStep1`-`saveStep5` already use (`coachProfileRepository`, `coachSpecialtyRepository`, `coachAgeGroupRepository`, `coachPricingRepository`, `sessionPackRepository`, `coachAvailabilityWindowRepository` — all already injected fields, `:73-78`), **not** the `getPublicProfile`'s optimized-but-lossy `coachPublicProfileFactsRepository` path:

```java
public CoachProfileSelfResponse getOwnProfile(Long userId) {
    CoachProfile profile = requireProfile(userId);
    CoachPricing pricing = coachPricingRepository.findByCoachId(profile.getId()).orElse(null);
    List<String> specialties = coachSpecialtyRepository.findByCoachId(profile.getId())
        .stream().map(CoachSpecialty::getSkill).toList();
    List<AgeTier> ageGroups = coachAgeGroupRepository.findByCoachId(profile.getId())
        .stream().map(CoachAgeGroup::getAgeTier).toList();
    List<SessionPackDto> sessionPacks = sessionPackRepository.findByCoachId(profile.getId())
        .stream().map(sp -> new SessionPackDto(sp.getSessionCount(), sp.getTotalPrice(), "EUR", sp.getLabel()))
        .toList();
    List<CoachAvailabilityWindowDto> windows = coachAvailabilityWindowRepository
        .findByCoachIdOrderByDayOfWeekAscStartTimeAscIdAsc(profile.getId())
        .stream().map(w -> new CoachAvailabilityWindowDto(w.getDayOfWeek(), w.getStartTime(), w.getEndTime(), w.getCanonicalTimezone()))
        .toList();
    return new CoachProfileSelfResponse(
        profile.getId(), profile.getStatus(), profile.getDisplayName(), profile.getBio(),
        profile.getCity(), profile.getDistrict(), profile.getLanguages(), profile.getCanonicalTimezone(),
        specialties, ageGroups,
        pricing != null ? pricing.getPerSessionPrice() : null,
        pricing != null ? pricing.getSessionDurationMinutes() : null,
        "EUR", sessionPacks, windows, profile.getPhotoUrl());
}
```

`CoachProfileSelfResponse` fields: `(UUID coachId, CoachProfileStatus status, String displayName, String bio, String city, String district, List<String> languages, String canonicalTimezone, List<String> specialties, List<AgeTier> ageGroups, BigDecimal perSessionPrice, Integer sessionDurationMinutes, String currency, List<SessionPackDto> sessionPacks, List<CoachAvailabilityWindowDto> availabilityWindows, String photoUrl)`. Reuse the existing `SessionPackDto` record as-is (same shape `getPublicProfile` already builds, `:442`). `CoachAvailabilityWindowDto`: `(short dayOfWeek, LocalTime startTime, LocalTime endTime, String canonicalTimezone)`.

In `ProfileBuilderResource` (`@RequestMapping("/api/marketplace/coaches/me/profile")`, `:29`), add a `@GetMapping` with **no sub-path** (the bare root of that mapping is currently unused — `/status` and `/timezones` are the only `GET`s today):

```java
@GetMapping
@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
public ResponseEntity<CoachProfileSelfResponse> getOwnFullProfile() {
    return ResponseEntity.ok(coachProfileService.getOwnProfile(currentUserId()));
}
```

Reuse the resource's existing private `currentUserId()` helper (`:102`) — do not introduce a different current-user resolution path.

### Test plan

- `CoachProfileServiceTest`: `getOwnProfile_draftProfile_returnsAllCollectedFields` (confirms it works for `DRAFT`, unlike `getPublicProfile`), `getOwnProfile_noPricingRow_perSessionPriceNull`, `getOwnProfile_noAvailabilityWindows_emptyList`.
- `CoachProfileBuilderIT` (or a new sibling IT): `getOwnFullProfile_asCoach_returnsOwnData`, `getOwnFullProfile_asParent_returns403` (mirrors the existing `saveStep1_asParent_returns403` pattern).

---

## AC2: "My Profile" — new role-gated "Coach Profile" section, editable without the onboarding wizard

**Files:**
- `src/frontend/src/pages/ProfilePage.vue`
- `src/frontend/src/api/marketplace.api.js`
- **New:** `src/frontend/src/components/profile/EditCoachIdentityDialog.vue`
- **New:** `src/frontend/src/components/profile/EditCoachSpecialtiesDialog.vue`
- **New:** `src/frontend/src/components/profile/EditCoachPricingDialog.vue`
- **New:** `src/frontend/src/components/profile/EditCoachAvailabilityDialog.vue`

### The fix

In `ProfilePage.vue`, add a third `.glass-card.profile-section`, gated `v-if="authStore.isCoach"` (import `useAuthStore` — note `ProfilePage.vue` currently does **not** import it at all, since the existing two cards are role-agnostic; this is the first role check the page will ever make), titled `$t('profile.sectionCoachProfile')`, loaded via a new `coachProfile` ref populated from `getOwnCoachProfile()` (new `marketplace.api.js` export for `GET /api/marketplace/coaches/me/profile`, AC1) inside `loadProfile()` — call it only when `authStore.isCoach` is true, and swallow/ignore a 403 the same way the rest of the page already swallows profile-load errors (do not let an unrelated-to-account-settings failure block the two existing cards from rendering).

Four `.profile-row`s, each with an `edit-btn` opening a new dialog, matching the existing row/dialog wiring convention exactly (`v-model="showXDialog"`, a `:current-*` prefill prop, `@updated="loadProfile"`):

1. **Identity & Location** row — `$t('profile.coachIdentity')`, shows `displayName`/`city`/`district` summarized. `EditCoachIdentityDialog.vue` fields: `displayName`, `bio`, `city`, `district`, `languages`, `canonicalTimezone` — reuse `ProfileBuilderStep1.vue`'s exact field set, validation rules, and its debounced `sanitizePreview`-on-bio-change contact-detail warning (`ProfileBuilderStep1.vue:106-130`) rather than reinventing it; fetch timezone options via the already-existing `getSupportedTimezones()` (`marketplace.api.js:11`). Submits via `saveProfileBuilderStep(1, {...})` (existing, `marketplace.api.js:5-6`).
2. **Specialties & Age Groups** row — `EditCoachSpecialtiesDialog.vue`, same field set/validation as `ProfileBuilderStep2.vue` (multi-select specialties, age-tier checkboxes). Submits via `saveProfileBuilderStep(2, {...})`.
3. **Pricing & Session Packs** row — `EditCoachPricingDialog.vue`, same field set/validation/add-remove-pack-row UX as `ProfileBuilderStep3.vue` (including its "touched but invalid pack row blocks submit" guard, `ProfileBuilderStep3.vue:160-185`). Submits via `saveProfileBuilderStep(3, {...})`. **Note the replace-all semantics already established for session packs** (`deleteByCoachId` + re-insert, per `deferred-work.md:3452-3453`) — there is no per-pack id in `SessionPackDto` or `ProfileBuilderStep3Request`, so "remove one pack" is "resubmit the list without it," exactly like the builder already works. Do not invent per-pack ids.
4. **Availability** row — `EditCoachAvailabilityDialog.vue`, same field set/validation/add-remove-window UX as `ProfileBuilderStep4.vue`. Submits via `saveProfileBuilderStep(4, {...})`. Same replace-all caveat as pricing — `CoachAvailabilityWindow` rows have a real `id` at the entity level (`CoachAvailabilityWindow.java:23-24`) but `ProfileBuilderStep4Request`'s `AvailabilityWindowRequest` has none, so this dialog must resubmit the full window list, not a per-window PATCH.

Each dialog prefills from `coachProfile` (the AC1 response), not from a builder-store (`profileBuilder.store.js` is the onboarding wizard's own state and must not be reused here — this is a separate, simpler "load once, edit, resubmit" flow, consistent with how the existing six `ProfilePage.vue` dialogs already work).

Visually: match the existing two cards exactly — same `.profile-section` padding, `.profile-row` divider, `edit-btn` pencil icon (`ProfilePage.vue:239-275`) — do not introduce a new CSS pattern.

**Empirical confirmation required (Dev Agent, not skippable):** before wiring these dialogs to reuse `saveStep1`-`saveStep4` as-is, actually call each endpoint against a real `ACTIVE`-status test coach (e.g. in the new IT below) and confirm the write succeeds and the profile stays `ACTIVE` afterward — the Context section's claim 1 is a static-read conclusion, not yet an executed one. This is a hard requirement of this AC, not an optional nice-to-have — do not mark AC2 done without the new IT passing.

**Pre-existing inconsistency, explicitly out of scope — do not "fix" it as a side effect of this AC:** `saveStep1`-`saveStep3` never check for `SUSPENDED` status at all; only `saveStep4` does (`:263-266`). This means a coach suspended between completing step 1 and step 4 could still edit steps 1-3 through this new "My Profile" surface (and, identically, through the original onboarding wizard today — this is not a regression this story introduces). Leave this exactly as-is; aligning all four steps' `SUSPENDED` handling is a separate, pre-existing product decision, not part of this story's scope.

### Test plan

- New IT, e.g. `CoachProfileSelfEditIT`: `saveStep1_onActiveCoach_succeedsAndProfileStaysActive` (and the equivalent for steps 2-4) — the direct empirical confirmation the ledger asked for.
- Frontend Vitest specs (`src/frontend/src/components/profile/__tests__/`): one spec per new dialog covering prefill-from-prop, successful submit, and the specific reused validation/guard behavior (e.g. pricing dialog's touched-invalid-pack-row block).
- `ProfilePageSpec.js` (new or extended): coach section renders only when `authStore.isCoach` is true; does not render for Player/Parent/Admin.

---

## AC3: Coach profile photo — upload, replace, and (new) delete, from "My Profile"

**Files:**
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` (new `deletePhoto` method; new `FileStorageService` dependency)
- `src/main/java/com/softropic/skillars/platform/marketplace/api/ProfileBuilderResource.java` (new `DeleteMapping`)
- `src/frontend/src/api/marketplace.api.js`
- **New:** `src/frontend/src/components/profile/EditCoachPhotoDialog.vue`

### The fix

**Backend — the genuine gap, per the ledger's own framing (`deferred-work.md:3458-3464`):** `saveStep5` can only ever *set* a non-null `photoUrl` (`CoachProfileService.java:290-297`); there is no code path that clears it. Add a dedicated delete endpoint rather than overloading `saveStep5` with a clear-signal field (the ledger's own second suggested option) — this keeps `ProfileBuilderStep5Request` unchanged and matches the project's existing file-deletion security convention ("the service layer must also verify that the authenticated user owns the file via `fso.getCreatedBy()`," `project-context.md:53`), which the reused `FileStorageService.softDelete` already enforces (`FileStorageService.java:244-246`, comparing `fso.getCreatedBy()` against the caller's own username) — belt-and-suspenders with `requireProfile(userId)`'s own ownership lookup.

```java
@Transactional
public void deletePhoto(Long userId, String currentUserLogin) {
    CoachProfile profile = requireProfile(userId);
    if (profile.getPhotoUrl() != null) {
        fileStorageService.softDelete(profile.getPhotoUrl(), currentUserLogin);
        profile.setPhotoUrl(null);
        coachProfileRepository.save(profile);
    }
}
```

Inject `FileStorageService` (`platform.filestorage.service.FileStorageService`) into `CoachProfileService` as a new constructor dependency — a cross-module service call, not a direct `infrastructure.blobstore` reach-around, consistent with `project-context.md`'s module-boundary rule (`:95-106`: platform modules may depend on other platform modules' public services; only `infrastructure` must stay business-agnostic). If `profile.getPhotoUrl()` is already `null`, no-op (idempotent delete).

**Atomicity, confirmed safe as written — do not add compensation/retry logic here:** `FileStorageService.softDelete` (`:234-264`) makes **no external storage (S3) call at all** — despite the name, it is a pure DB-level soft delete: a `fileStorageObjectRepository.findByKeyAndDeletedAtIsNull` read, an in-memory `fso.getCreatedBy().equals(currentUserLogin)` ownership check (`:244` — a plain `String.equals`, not an I/O call, cannot time out), and a `fileStorageObjectRepository.softDeleteByKey` write (`:248`). Physical deletion from the blob store (if any) happens elsewhere/async, outside this call. Because `deletePhoto` is `@Transactional` and `FileStorageService` has no competing `@Transactional` boundary of its own, `softDeleteByKey`'s write and `coachProfileRepository.save(profile)`'s write both join the **same** ambient Spring/JPA transaction (default `REQUIRED` propagation) and commit or roll back together — there is no partial-failure window where one persists without the other. Do not reorder the two calls or add manual compensation; the existing `@Transactional` boundary already makes this atomic.

In `ProfileBuilderResource`:
```java
@DeleteMapping("/photo")
@PreAuthorize(SecurityConstants.HAS_COACH_ROLE)
public ResponseEntity<Void> deletePhoto() {
    coachProfileService.deletePhoto(currentUserId(), securityUtil.getCurrentUserName());
    return ResponseEntity.noContent().build();
}
```

**Frontend:** `EditCoachPhotoDialog.vue` reuses the exact upload mechanics `CoachProfileBuilderPlaceholderPage.vue.onStep5WithPhoto` already implements (`:153-181`: `signUpload({entity:'coach_profile', entityId:String(userId), ...})` → raw `fetch` PUT to the returned `uploadUrl` → `confirmUpload(key, ...)` → `saveProfileBuilderStep(5, {photoUrl: key})`) for both the initial-upload and replace-existing-photo cases (replace is just upload-then-save again, already works as-is per the ledger's own note, `deferred-work.md:3463-3464`). Add a "Remove photo" action calling a new `deleteCoachPhoto()` export (`marketplace.api.js`, `DELETE /api/marketplace/coaches/me/profile/photo`) — show a confirm step (`$t('profile.confirmDeletePhoto')`) before calling it, matching the confirm-before-destructive-action pattern `Toggle2faDialog.vue` already establishes for a different field. On success of any of the three actions, `emit('updated')` to trigger `loadProfile()`.

### Test plan

- `CoachProfileServiceTest`: `deletePhoto_withExistingPhoto_clearsFieldAndSoftDeletesStorageObject` (mock `FileStorageService`, verify both calls), `deletePhoto_noExistingPhoto_isNoOp` (verify `fileStorageService` never invoked).
- New IT case: `deletePhoto_asCoach_returns204AndPhotoUrlNullOnNextFetch` — upload a real photo via the existing sign/confirm/step5 flow first, then delete, then assert `getOwnFullProfile()`'s `photoUrl` is `null`. `deletePhoto_asParent_returns403`.
- Frontend spec: upload/replace reuses existing mechanics (smoke-test only, since the upload flow itself is already covered by the builder's own tests); delete action requires confirm and calls `deleteCoachPhoto()`.

---

## AC4: Player — "My Profile" gains an editable Position field

**Problem confirmed (not assumed):** `PlayerProfileBuilderPage.vue`'s onboarding submission (`POST /api/security/players/me` → `ShadowAccountService.createSelfOwnedPlayerProfile`) is one-time-only — it throws `security.playerProfileAlreadyExists` on a second call (`ShadowAccountService.java:84-86`) — and no `PUT`/`PATCH` counterpart exists anywhere in `platform.security`.

**Files:**
- `src/main/java/com/softropic/skillars/platform/security/contract/UpdatePlayerPositionRequest.java` (**new** record)
- `src/main/java/com/softropic/skillars/platform/security/service/ShadowAccountService.java` (new `updateOwnPosition` method)
- `src/main/java/com/softropic/skillars/platform/security/api/ShadowAccountResource.java` (new `PatchMapping`)
- `src/frontend/src/pages/ProfilePage.vue`
- `src/frontend/src/api/playerProfile.api.js` (confirmed exact path — exports `createProfile()` for `POST /api/security/players`; add the new call here, same file)
- **New:** `src/frontend/src/components/profile/EditPlayerPositionDialog.vue`

### The fix

`UpdatePlayerPositionRequest(@NotNull PlayerPosition position)` — same single-field shape as `CreateSelfPlayerProfileRequest`.

`ShadowAccountService.updateOwnPosition(Long userId, PlayerPosition position)`: look up via `playerProfileRepository.findByUserId(userId)` (existing method, used by `getSelfOwnedPlayerProfile`), 404/`ShadowAccountException` if absent, set `position`, save, return the existing `PlayerProfileResponse` mapping (reuse `playerProfileMapper`, the same mapper `getSelfOwnedPlayerProfile`/`createSelfOwnedPlayerProfile` already use — do not hand-rewrite the DTO mapping).

`ShadowAccountResource`:
```java
@PreAuthorize(SecurityConstants.HAS_PLAYER_ROLE)
@PatchMapping("/me/position")
public ResponseEntity<PlayerProfileResponse> updateOwnPosition(@RequestBody @Valid UpdatePlayerPositionRequest request) {
    Long userId = securityUtil.requireCurrentUserId();
    return ResponseEntity.ok(shadowAccountService.updateOwnPosition(userId, request.position()));
}
```
(`@PatchMapping` returning the updated resource body, not `204`, since the project's PATCH convention — "return `204 No Content` for body-less success" — applies to body-*less* success; this response carries the updated `PlayerProfileResponse`, matching the existing `GET /me` shape, so the frontend dialog can reuse it the same way the other `ProfilePage.vue` dialogs' `@updated` handlers re-fetch.)

**Frontend:** in `ProfilePage.vue`, add a `.glass-card.profile-section` gated `v-if="authStore.isPlayer"`, titled `$t('profile.sectionPlayerProfile')`, one `.profile-row` for Position with an `edit-btn` opening `EditPlayerPositionDialog.vue` (single `q-select`, same 4-value enum as `PlayerProfileBuilderPage.vue:52-57`). Load the player's own profile (`GET /api/security/players/me`, already existing) inside `loadProfile()` when `authStore.isPlayer`.

### Test plan

- `ShadowAccountServiceIT` (`src/test/java/.../platform/security/service/ShadowAccountServiceIT.java` — the existing test class for this service; confirmed, no separate `ShadowAccountServiceTest` exists today): add `updateOwnPosition_existingProfile_updatesAndReturnsResponse`, `updateOwnPosition_noProfile_throws`.
- **No resource-level IT exists yet for `ShadowAccountResource`** (confirmed — only the service-level `ShadowAccountServiceIT` exists today, unlike `PlayerRegistrationResourceIT` which does exist for the sibling registration resource). Add a new `ShadowAccountResourceIT` following that same `{ClassName}IT` convention: `updateOwnPosition_asPlayer_returns200WithNewPosition`, `updateOwnPosition_asCoach_returns403`.
- Frontend spec: dialog prefills current position, submits, `ProfilePage` section renders only for Player.

---

## AC5: Parent — "My Profile" gains a "My Children's Profiles" section for editing each child's Position

**Problem confirmed:** zero existing route (frontend or backend) lets a parent edit a child's profile after `CreatePlayerProfilePage.vue`'s initial creation — exhaustive check of `ShadowAccountResource`/`ShadowAccountService` found only create/list/get/link-parent (the last of which unconditionally throws, `ShadowAccountResource.java:95` — re-verified post-implementation; AC4's new `PATCH /me/position` block inserted above this line shifted it down from this citation's drafting-time value).

**Files:**
- `src/main/java/com/softropic/skillars/platform/security/service/ShadowAccountService.java` (new `updateChildPosition` method)
- `src/main/java/com/softropic/skillars/platform/security/api/ShadowAccountResource.java` (new `PatchMapping`)
- `src/frontend/src/pages/ProfilePage.vue`
- `src/frontend/src/api/playerProfile.api.js`
- Reuse **existing** `src/frontend/src/components/profile/EditPlayerPositionDialog.vue` (AC4) — give it an optional `player-id` prop so the same dialog serves both "player edits own position" and "parent edits a specific child's position," rather than duplicating the component.

### The fix

`ShadowAccountService.updateChildPosition(Long playerId, Long parentId, PlayerPosition position)` — same `(playerId, parentId)` parameter shape as the existing `getPlayerProfile(Long playerId, Long parentId)` (`:115`), for the same reason: look up via **plain** `playerProfileRepository.findByIdAndParentId(playerId, parentId)` (the repository's own "always use this instead of findById — parentId enforces family isolation" method), throwing/404ing if absent (defense-in-depth alongside the `@PreAuthorize` guard below, exactly like `getPlayerProfile` already layers both) — **no pessimistic lock.** Decision, made here rather than deferred: `PlayerProfileRepository.findByIdForUpdate`'s `NO_WAIT` pattern exists for the GDPR-erasure/multi-field-cascade scenarios documented on that repository, where a scheduler and a user request can race over the *same row's deletion*; a single parent editing their own child's `position` field has no other writer anywhere in the codebase to race against (no scheduler, no other endpoint touches `position`), so the lock's complexity isn't earning its keep here. If a future story adds a second writer of `position`, revisit this. Set `position`, save, return via `playerProfileMapper`.

**Authorization — reuse the existing ownership guard, do not hand-roll a new check:**
```java
@PreAuthorize("@playerOwnershipGuard.check(authentication, #playerId)")
@PatchMapping("/{playerId}/position")
public ResponseEntity<PlayerProfileResponse> updateChildPosition(
    @PathVariable Long playerId, @RequestBody @Valid UpdatePlayerPositionRequest request) {
    Long parentId = securityUtil.requireCurrentUserId();
    return ResponseEntity.ok(shadowAccountService.updateChildPosition(playerId, parentId, request.position()));
}
```
`PlayerOwnershipGuard.check` (`platform/security/service/PlayerOwnershipGuard.java`) already does exactly the right thing for this endpoint — it resolves the caller's own `businessId` as `parentId` and confirms `playerProfileRepository.findByIdAndParentId(playerId, parentId)` exists, returning `false` (→ 403) otherwise. This is the **same annotation** `getPlayerProfile` already uses (`ShadowAccountResource.java:85`, re-verified post-implementation) — no new SpEL, no new bean.

**Frontend:** in `ProfilePage.vue`, add a `.glass-card.profile-section` gated `v-if="authStore.isParent"`, titled `$t('profile.sectionMyChildren')`. Reuse `usePlayerStore()`'s already-fetched `players` list (the same store `ParentChildSwitcher.vue` uses, `playerStore.js:7`, populated via `fetchPlayers()` → `GET /api/security/players`, already called elsewhere on login) — render one `.profile-row` per child, label = child's `name`, value = current `position`, `edit-btn` opens `EditPlayerPositionDialog.vue` with `:player-id="child.id"`. Do **not** duplicate `ParentChildSwitcher.vue`'s dialog UI — this is a flat list, not a switcher, since the goal here is editing every child's field, not selecting one to navigate elsewhere.

**Explicitly out of scope for this AC, matching the ledger's own framing:** editing a child's `name`/`dateOfBirth`, and anything about the parent's *own* profile (parent has no profile-builder-equivalent flow at all per the ledger's finding, `deferred-work.md:3435-3437` — `CreatePlayerProfilePage.vue` creates a *child's* profile, not the parent's own, so there is no "Parent Profile" section to add here, only the children-management one).

### Test plan

- `ShadowAccountServiceIT`: add `updateChildPosition_ownChild_updatesAndReturnsResponse`.
- `ShadowAccountResourceIT` (new, per AC4's note — add these cases to the same new file): `updateChildPosition_ownChild_returns200`, `updateChildPosition_otherParentsChild_returns403` (the critical cross-family-isolation case — construct two parents, each with a child, assert parent A cannot update parent B's child), `updateChildPosition_asPlayer_returns403` (route is parent-only by construction of the guard, which always resolves `businessId` as a parentId).
- Frontend spec: section lists every child from `playerStore.players`; each row's dialog carries the correct `player-id`; section renders only for Parent.

---

## AC6: Admin — explicit scope confirmation, no new section

**The fix:** no code change beyond the role gates already added in AC2/AC4/AC5 naturally excluding Admin (none of `v-if="authStore.isCoach"` / `isPlayer` / `isParent` is true for an Admin user). Document this as a deliberate decision, not an oversight: Admin has no profile-builder-equivalent onboarding flow anywhere in the codebase (confirmed by this story's own research — the only profile-completion flows found are the coach builder and the two player-creation paths), so the existing Account + Personal Info cards already constitute Admin's complete "My Profile" editable surface.

### Test plan

- `ProfilePageSpec.js`: logging in as Admin (or mocking `authStore.role = 'ADMIN'`) renders exactly the two pre-existing cards and none of the three new role-gated sections.

---

## Dev Notes

- **No local `mvn verify`** — GitHub CI is the sole full-verification gate per this project's standing convention (`[[no-local-mvn-verify]]` team convention). Run targeted suites for every touched class.
- **This story's citations were verified against current `HEAD` at drafting time (2026-10-02)** — re-diff every cited line against whatever `master` actually looks like once implementation starts, per this project's own standing "diff cited lines to ensure they're still accurate" convention (see `skillars-deferred-138`'s Dev Notes for the precedent of this phrasing).
- **Replace-all semantics for specialties/age-groups/session-packs/availability-windows are a pre-existing, deliberate pattern** (`CoachProfileService.saveStep2`-`saveStep4` each do `deleteByCoachId` + re-insert) — AC2's four new dialogs must resubmit the *full* list on every save, exactly like the onboarding steps already do. Do not add per-item ids or per-item PATCH semantics; that would be new scope beyond what this story needs and would diverge from the builder's existing contract that these dialogs are deliberately reusing as-is.
- **Timezone IANA-format validation gap is CLOSED, not open — `deferred-work.md:605-611`'s claim is stale.** Re-verified directly against `HEAD`: `ProfileBuilderStep1Request.java:16` and `ProfileBuilderStep4Request.java:25` both carry `@NotBlank @IanaTimezone String canonicalTimezone`, and `@IanaTimezone` is a real, independently-tested validator (`infrastructure/validation/IanaTimezone.java` + `IanaTimezoneValidator.java`, with its own `IanaTimezoneValidatorTest`), not a no-op annotation. The ledger entry predates this. Do not re-open or re-litigate this in AC2 — the Identity dialog's timezone field is already properly validated by the existing annotation it inherits from `ProfileBuilderStep1Request`.
- **`FileStorageService` as a new `CoachProfileService` dependency (AC3):** this is `platform.filestorage`'s public service class, not `infrastructure.blobstore` — confirm this import is `com.softropic.skillars.platform.filestorage.service.FileStorageService`, never anything under `infrastructure.*`, to stay inside the module-boundary rule in `project-context.md:95-156`. Its `softDelete` method makes no external storage call at all (confirmed by reading the full method body, `:234-264`) — see AC3's own atomicity note; do not add retry/compensation logic to `deletePhoto` under the assumption that it does.
- **i18n:** this project requires every user-facing string externalized via `vue-i18n`, and recent stories (e.g. `skillars-deferred-138`) added new keys to `en-US` only when scoped that way by explicit decision — check whether this project's current convention expects `fr-FR`/`de-DE` parity for *all* new keys or whether `en-US`-only-with-fallback is acceptable today, and follow whichever is actually true of the current `i18n/` directory contents at implementation time, not an assumption from an older story.
- **Scope boundary, explicit:** this story does not touch `AuthService`, login/registration flows, `CoachPublicProfilePage.vue` (the separate, pre-existing *public* read-only profile view — AC1-AC3 are a different, authenticated-self-service surface and must not be confused with it), or the onboarding wizard pages/store themselves (`CoachProfileBuilderPlaceholderPage.vue`, `PlayerProfileBuilderPage.vue`, `CreatePlayerProfilePage.vue`, `profileBuilder.store.js`) beyond reusing their existing backend endpoints — those pages/flows continue to work exactly as before for first-time onboarding.

---

## Tasks

- [x] 1. **AC1:** Add `CoachProfileSelfResponse`/`CoachAvailabilityWindowDto` records, `CoachProfileService.getOwnProfile`, and `ProfileBuilderResource`'s new `GET` (root path). Unit + IT coverage per AC1's test plan.
- [x] 2. **AC2:** Add the role-gated "Coach Profile" section to `ProfilePage.vue` plus the four new edit dialogs (Identity, Specialties, Pricing, Availability), each reusing the existing `saveProfileBuilderStep(1-4, ...)` calls. Empirically confirm each against a real `ACTIVE` test coach (new IT). Frontend specs per dialog.
- [x] 3. **AC3:** Add `CoachProfileService.deletePhoto` (+ `FileStorageService` dependency) and `ProfileBuilderResource`'s new `DELETE /photo`. Build `EditCoachPhotoDialog.vue` (upload/replace via existing mechanics, new remove-with-confirm action). Unit + IT + frontend coverage.
- [x] 4. **AC4:** Add `UpdatePlayerPositionRequest`, `ShadowAccountService.updateOwnPosition`, `ShadowAccountResource`'s new `PATCH /me/position`. Add the role-gated "Player Profile" section + `EditPlayerPositionDialog.vue` to `ProfilePage.vue`. Unit + IT + frontend coverage.
- [x] 5. **AC5:** Add `ShadowAccountService.updateChildPosition`, `ShadowAccountResource`'s new `PATCH /{playerId}/position` (reusing `@playerOwnershipGuard.check`). Add the role-gated "My Children's Profiles" section to `ProfilePage.vue`, reusing `EditPlayerPositionDialog.vue` with a `player-id` prop. Unit + IT (including the cross-family-isolation 403 case) + frontend coverage.
- [x] 6. **AC6:** Add the Admin-renders-neither-new-section Vitest assertion; no production code change.
- [x] 7. i18n: add every new key used by the above (section titles, dialog labels, confirm-delete-photo copy, validation messages reused verbatim from the builder steps where applicable) — resolve the `fr-FR`/`de-DE` parity question from Dev Notes before finalizing.
- [x] 8. Full targeted-suite regression run for every touched class (`CoachProfileService`, `ProfileBuilderResource`, `ShadowAccountService`, `ShadowAccountResource` and their existing test classes, plus `CoachProfileBuilderIT`) — no local `mvn verify` per standing convention.
- [x] 9. Update `sprint-status.yaml` `development_status` entry and `last_updated` for this story key.

### Review Findings

_Adversarial code review via `/bmad-code-review`, 2026-10-02. Three layers over the full ~3,860-line diff: Blind Hunter (diff only), Edge Case Hunter (diff + project, ~95 branch/boundary paths walked, ~85 already handled), Acceptance Auditor (diff + this story). No layer failed. 19 patch findings (4 of which began as decisions and were resolved with Mbah on 2026-10-02), 3 deferred, 2 dismissed as verified false positives._

**Verified-and-upheld story claims** (re-checked independently, not taken on trust): the `entityManager.flush()` fix is real and load-bearing — removing it from `saveStep2` reproduces `duplicate key value violates unique constraint "uq_coach_age_group"` → 400 `generic.dataError`; `marketplace.coach_availability_windows` genuinely has no unique constraint (`V138__baseline_schema.sql:1528-1536`, pkey `:2804`; no V139–V154 adds one), so "`saveStep4` unaffected" is correct; i18n parity is real (all 99 `t()` keys used by the seven new/changed frontend files resolve in `en-US`/`fr-FR`/`de-DE`, zero missing).

**Decision needed → ALL FOUR RESOLVED 2026-10-02** (resolved with Mbah during the review walkthrough; each became a patch. The four items are stated below as raised, followed by a Resolutions block recording the decision and the agreed fix for each.)

- [ ] [Review][Decision] Availability dialog discards each window's own `canonicalTimezone` and restamps every window with the step-1 profile zone — `EditCoachAvailabilityDialog.vue:189-193` maps only `dayOfWeek`/`startTime`/`endTime`, `:194` seeds `form.canonicalTimezone` from `profile.canonicalTimezone` (the `coach_profiles` column), and `:225` stamps that one value onto every window in the `saveStep4` payload. `CoachAvailabilityWindowDto.java:9` exists precisely to carry the per-window value and `CoachProfileService.java:338-341` populates it from `w.getCanonicalTimezone()`, so as consumed the field is dead. The two columns legitimately diverge and `ProfileBuilderStep4.vue:98,105-107` documents that deliberately ("it would silently overwrite a per-window zone the coach had deliberately chosen here"), noting `deferred-17 D8` remains open. A coach whose windows are `Europe/Berlin` and whose profile zone is `America/New_York` has all bookable availability silently re-zoned by any availability save, even a no-change one. Ambiguous because the fix depends on whether this dialog's timezone picker should own the window zone, be display-only, or go per-window — which is the open D8 question. Flagged High by all three layers.
- [ ] [Review][Decision] `deletePhoto` strands `photoUrl` permanently when the storage row is missing or foreign-owned — `CoachProfileService.java:361-366` calls `fileStorageService.softDelete` *before* `setPhotoUrl(null)`, and `FileStorageService.java:236-237` throws `StorageObjectNotFoundException` when `findByKeyAndDeletedAtIsNull` is empty while `:244-246` throws `AuthorizationException` when `fso.getCreatedBy()` differs from the caller's login. Neither is caught, so the whole `@Transactional` rolls back and the clear never runs. Reachable two ways: the coach changes their email (`UserProfileService.updateUserEmail:117` calls `u.setLogin(newEmail)` while `created_by` keeps the old login — so it fails forever), or `photoUrl` points at a key with no live row (`saveStep5:308-314` validates only the `coach_profile/{userId}/` prefix and never checks for a confirmed upload; `StorageResource`'s `DELETE /api/storage/**` can soft-delete the key independently). Result: `DELETE /photo` returns 403/404 and the coach can never remove their photo — the exact capability AC3 exists to provide. Ambiguous because tolerating a foreign-owned object has a security dimension: best-effort-clear vs. hard-fail is your call.
- [ ] [Review][Decision] A player with no `PlayerProfile` row is shown an Edit Position affordance that can never succeed — `ProfilePage.vue:235` gates the section on `authStore.isPlayer` (the role) rather than on whether the row exists, and `:450` swallows the 404 into `playerProfile.value = null`, so the section renders `—` with a live Edit button. Saving reaches `ShadowAccountService.java:136`, whose `findByUserId(...).orElseThrow` yields `UserNotFoundException` → 404 `security.userNotFound` — naming the wrong entity, since the user exists and only the player profile does not. There is no route out: `createSelfOwnedPlayerProfile:85` rejects a second `POST /me`. Ambiguous between three materially different fixes (upsert in `PATCH /me/position`, render a create-profile CTA, or hide the section when the row is absent).
- [ ] [Review][Decision] Non-`ACTIVE` coach edit gating is absent and the returned `status` is never used — `saveStep4:281-284` is the only step with a status guard (`SUSPENDED` → `OperationNotAllowedException`, `booking.coachUnavailable`); `saveStep1`, `saveStep2`, `saveStep3`, `saveStep5` and the new `deletePhoto` have none. `CoachProfileSelfResponse` carries `status` for exactly this gating but `ProfilePage.vue:126` never reads it, so the section and all five edit buttons render for every one of the six `CoachProfileStatus` values. A suspended coach can still rewrite their public display name, bio, specialties and pricing, while Availability alone fails with "Coach is unavailable" — nonsense in a profile-editing context. Also covers the DRAFT mid-builder case, where `saveStep3:220-223` / `saveStep5:304-306` throw `marketplace.stepOutOfOrder` (422). Product decision: which statuses may edit which fields.

**Resolutions of the four decisions** (Mbah, 2026-10-02 — each now a patch)

Mbah's governing domain rule, stated during the walkthrough: **"Both player and coach will use the timezone of the city in which the coach resides when it comes to setting availability."** The profile-level `coach_profiles.canonical_timezone` (a Step-1 field, stored alongside `city`/`district`) is therefore the authoritative zone. Verified that booking already agrees — `ScheduleResource:65`, `BookingService:926` and `AvailabilityService:83` all read `profile.getCanonicalTimezone()`, and `AvailabilityService.addWindow:261` already stamps it onto new windows. Corrected one premise during the walkthrough: per-window divergence is currently a *deliberate documented feature* (`AvailabilityService:82-83`, citing deferred-63/-64, with `:140-152` computing each window's slots in its own zone) and `saveStep4` accepts a caller-supplied per-window zone — so the two availability writers contradict each other today, and that split is exactly the open `deferred-17 D8`. Adopting the rule in full is a separate story (deferred below); this review takes the narrow fix only.

- [x] [Review][Patch] **D1** — make the availability dialog's timezone **read-only**: render the profile zone as static text instead of an editable `q-select`, and keep stamping it onto every window on save [src/frontend/src/components/profile/EditCoachAvailabilityDialog.vue:71] — this makes the current restamp *intentional* rather than accidental, matches `AvailabilityService.addWindow:261`, keeps zone ownership with Identity/Step 1, and removes the divergence-creating vector (a picker that read the profile column but wrote only the window column, so a zone change here silently reverted on reopen). Deliberately does **not** touch `saveStep4`, `AvailabilityService`, or existing rows. Decided: stale windows after an Identity zone change are left to the D8 story, not half-solved here.
- [x] [Review][Patch] **D2** — in `deletePhoto`, catch both `StorageObjectNotFoundException` and `AuthorizationException` from `softDelete` and clear `photoUrl` regardless; warn-log the ownership-mismatch case [src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:364] — rationale: a missing storage row means there is genuinely nothing to delete so clearing is the correct idempotent outcome, and the ownership case's real-world trigger is a mundane email change (`UserProfileService.updateUserEmail:117` calls `setLogin(newEmail)` while `created_by` keeps the old login), which otherwise leaves that coach permanently unable to remove their photo. Domain ownership is already established by `requireProfile(userId)` before `softDelete` runs, and `photoUrl` is that same row's own field. Add an IT for each of the two tolerated paths.
- [x] [Review][Patch] **D3** — when `playerProfile` is null, render a "complete your player profile" action routing to the existing player builder instead of a dead Edit button, and replace `updateOwnPosition`'s `UserNotFoundException` with a player-profile-specific error key [src/frontend/src/pages/ProfilePage.vue:235] — the current message ("User not found") names the wrong entity for a user who is demonstrably logged in. Explicitly rejected: making `PATCH /me/position` upsert, which would create partial profiles bypassing builder validation and contradict `createSelfOwnedPlayerProfile:85`'s deliberate one-time-create design.
- [x] [Review][Patch] **D4** — have `ProfilePage.vue` read the `status` already carried by `CoachProfileSelfResponse` and render the coach section read-only (no edit buttons, short explanatory note) when it is `SUSPENDED` [src/frontend/src/pages/ProfilePage.vue:126] — frontend-only by decision, so this story's recorded out-of-scope ruling on the `saveStep1`-`saveStep3` vs `saveStep4` SUSPENDED backend asymmetry stands untouched. Removes the path where a suspended coach can rewrite display name/bio/specialties/pricing while Availability alone fails with the booking-domain message "Coach is unavailable". Decided *not* to also gate on `lastCompletedStep` for DRAFT coaches.

**Patch** (15 further — unambiguous fix)

- [x] [Review][Patch] A swallowed profile-fetch failure turns the Identity and Pricing dialogs into data wipes [src/frontend/src/pages/ProfilePage.vue:439-443]
- [x] [Review][Patch] All six dialogs suppress the error banner for validation errors but never render per-field errors [src/frontend/src/components/profile/EditCoachIdentityDialog.vue:75]
- [x] [Review][Patch] Unlocked full-row `save()` on `PlayerProfile` can resurrect the GDPR erasure tombstone [src/main/java/com/softropic/skillars/platform/security/service/ShadowAccountService.java:136]
- [x] [Review][Patch] `deletePhoto`'s unlocked full-row `save()` can revert a concurrent suspension [src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:364]
- [x] [Review][Patch] Save buttons sit outside `<q-form>`, so `:rules` never evaluate and invalid submits are silent dead clicks [src/frontend/src/components/profile/EditCoachIdentityDialog.vue:88]
- [x] [Review][Patch] Duplicate `sessionCount` rows violate `uq_session_pack` and surface as an unattributed `generic.dataError` [src/frontend/src/components/profile/EditCoachPricingDialog.vue:191]
- [x] [Review][Patch] Dialog local state is not reset on close, so a cancelled edit can persist against a different child [src/frontend/src/components/profile/EditPlayerPositionDialog.vue:68]
- [x] [Review][Patch] AC5 Test Plan bullet 3 unimplemented — nothing pins `player-id` routing of the single shared dialog [src/frontend/src/pages/__tests__/ProfilePageSpec.js]
- [x] [Review][Patch] `q-file` `@rejected` is unhandled, so an oversize or wrong-type pick leaves the dialog silently inert [src/frontend/src/components/profile/EditCoachPhotoDialog.vue:17]
- [x] [Review][Patch] `URL.createObjectURL` is never revoked and the component has no `onUnmounted` [src/frontend/src/components/profile/EditCoachPhotoDialog.vue:142]
- [x] [Review][Patch] Window-count label is string-concatenated, so vue-i18n pluralization can never apply ("1 weekly windows") [src/frontend/src/pages/ProfilePage.vue:196]
- [x] [Review][Patch] Extension derived via `split('.').pop()` with no dot check, so a dotless filename yields the basename [src/frontend/src/components/profile/EditCoachPhotoDialog.vue:157]
- [x] [Review][Patch] AC3's confirm-gate assertions are vacuous — they assert non-call without invoking anything that could call [src/frontend/src/components/profile/__tests__/EditCoachPhotoDialogSpec.js]
- [x] [Review][Patch] AC4's specified IT case `updateOwnPosition_asCoach_returns403` was implemented as `_asParent_returns403` [src/test/java/com/softropic/skillars/platform/security/api/ShadowAccountResourceIT.java:118]
- [x] [Review][Patch] Three of this story's own `file:line` citations are off by one or two lines [src/main/java/com/softropic/skillars/platform/security/api/ShadowAccountResource.java:68]

**Deferred** (3)

- [x] [Review][Defer] Adopt "profile zone is authoritative" and close `deferred-17 D8` [src/main/java/com/softropic/skillars/platform/booking/service/AvailabilityService.java:82] — deferred, out of scope for a code review. Mbah's domain rule (availability is always in the coach's city's zone) resolves D8, but implementing it means: stop accepting a per-window zone in `saveStep4` (stamp the profile zone server-side instead), re-stamp existing windows when `saveStep1` changes the zone, simplify `AvailabilityService:140-152`'s per-window slot computation, add a backfill migration for already-divergent rows, and reverse the deferred-63/-64 "divergence is a feature" decision. Also covers the stale-window staleness left open by D1's narrow fix.
- [x] [Review][Defer] Nothing derives or validates `canonicalTimezone` against `city` [src/main/java/com/softropic/skillars/platform/marketplace/contract/ProfileBuilderStep1Request.java:15] — deferred, pre-existing. `city` is free-text `@Size(max = 100)` and `canonicalTimezone` is picked independently from `getSupportedTimezones()`, so `city = "Paris"` with `canonicalTimezone = "America/New_York"` saves cleanly today. Under the rule that availability follows the coach's city, these two fields contradicting each other is a latent data-quality problem.
- [x] [Review][Defer] `marketplace.stepOutOfOrder` and `marketplace.profileNotFound` are absent from all three locale bundles and from `messages*.properties`, so those server errors surface as raw English to French and German users [src/frontend/src/i18n/fr-FR/index.js] — deferred, pre-existing (both keys are thrown by the existing onboarding wizard path too, not introduced here)

### Post-patch audit (2026-10-02)

All 19 patches were re-verified individually against source, plus the full suites re-run. Everything landed correctly. Verification performed: all 107 static `t()` keys from the seven changed frontend files resolved against the real imported locale bundles (not grepped); full-bundle parity re-checked (the one gap, `auth.emailVerifiedPleaseLogin`, is **pre-existing on `master`** — en-US has it, fr-FR/de-DE do not — and is unrelated to this branch); vue-i18n pluralization verified **empirically** against the project's own version (correcting the reviewer's assumption that `t(key, {count})` does not pluralize — in v9 it does); transaction boundary for the new locks confirmed real via class-level `@Transactional` at `ShadowAccountService:30`; `findByIdForUpdate` confirmed present on `PlayerProfileRepository:60-63` with the project's `NOWAIT` + `PessimisticLockRetryer` pairing; `/player/profile-builder` route confirmed at `routes.js:232`; the rewritten story citations re-verified against current `HEAD`.

The audit found **five residual items**, all Low, all since fixed:

- [x] **Audit 1 — D4 hid the coach's data instead of rendering it read-only.** The first D4 fix replaced the whole coach section with a bare note, so a suspended coach could not even see their own display name/specialties/pricing — and `ProfilePageSpec`'s D4 test *asserted* that by requiring `profile.coachIdentity` to be absent. Fixed: the suspension note now renders inside the data branch and only the five edit buttons drop out (`v-if="!isCoachSuspended"`), so every row stays visible read-only. Test updated to assert the corrected contract (labels and values present, note present, zero `.edit-btn`), plus a new regression guard that an `ACTIVE` coach still gets all five buttons — so the gate can never silently disable editing for everyone.
- [x] **Audit 2 — P6 had no server-side backstop.** The client-side duplicate guard is not a backstop: a direct API call, or a second tab resubmitting a stale pack list, still reaches `uq_session_pack`, and the flush-before-reinsert fix deliberately does not help (it orders deletes before inserts, not duplicates *within* one insert batch). Unmapped it surfaced as an untranslated `generic.dataError` with no indication which pack row was at fault. Fixed: mapped `"uq_session_pack" → "marketplace.duplicateSessionPackCount"` in `ApiAdvice.CONSTRAINT_MAPPINGS` and added the key to all three locales (`useErrorHandler:39-48` translates `errorKey` via `te(key)`/`t(key)`, falling back to the raw server message — so an unmapped key is exactly how untranslated English reaches a French user). New IT `saveStep3_twoPacksWithSameSessionCount_returnsMappedDuplicateError` pins the mapped key end-to-end.
- [x] **Audit 3 — the exception change was only half-adopted.** `getSelfOwnedPlayerProfile` still threw `UserNotFoundException` while `updateOwnPosition` threw `PlayerProfileNotFoundException`, for the identical underlying state — and the read path is the one "My Profile" actually uses to choose between the position row and the CTA. Fixed: the read path now throws `PlayerProfileNotFoundException`, with a new IT pinning it. **`updateChildPosition` deliberately keeps `UserNotFoundException`** and is now documented as such in both the Javadoc and the test: `findByIdAndParentId` returns empty for two different states ("no such profile" and "not your child"), and reporting the cross-family case as "player profile not found" would assert a negative about another family's child and would route that parent to the create-a-player-profile builder for a child that is not theirs. Not an inconsistency to "unify" later.
- [x] **Audit 4 — `deletePhoto`'s null check ran against the pre-lock snapshot.** A concurrent delete committing in between left `photoUrl` null after the refresh, so the code called `softDelete(null)` and relied on `StorageObjectNotFoundException` to carry it — which worked, but warn-logged a misleading "already gone" for what is simply a correctly idempotent second delete. Fixed: re-check `photoUrl` after the lock-and-refresh and return early.
- [x] **Audit 5 — unrelated changes in the working tree.** `.claude/skills/mto-code-review/steps/step-02-review.md`, `step-03-triage.md`, and untracked `_bmad/custom/mto-code-review.toml` are tuning edits to the **`mto-code-review`** skill (a different skill from the `bmad-code-review` one that produced this review). Deliberately **not** reverted — they are real, intentional work. Resolved as a commit-scoping matter: stage only this story's files so these do not ride along. No code change.

**Suites after the audit fixes — all green:** frontend 25 files / **176** tests; backend **74** (`CoachProfileServiceTest` 9, `CoachProfileSelfEditIT` 14, `ShadowAccountServiceIT` 12, `ShadowAccountResourceIT` 5, `CoachProfileBuilderIT` 34 — no onboarding regression). `eslint` and `prettier` clean on every touched frontend file. Locale bundles grew by exactly the one new key (1137/1136/1136 → 1138/1137/1137), parity preserved.

**Dismissed as false positives** (2, recorded so they are not re-raised)

1. _"The presigned S3 `PUT` result is never checked, so a failed upload is reported as success and overwrites the working photo."_ The next call catches it: `FileStorageService.confirmUpload:126-131` issues a `headObject` and throws `StorageObjectNotFoundException` on `NoSuchKeyException`, with further `StorageValidationException` guards on contentType/size/ETag at `:133-143`. So `confirmUpload` rejects, the catch fires, and `saveProfileBuilderStep(5, …)` is never reached — the existing `photoUrl` is not overwritten. (Raised by the blind layer, which could not see the downstream guard; the edge layer independently contradicted it and was right.) Note the unchecked-`fetch` shape is codebase-wide anyway — `WrapUpSequence.vue:301` and `CoachProfileBuilderPlaceholderPage.vue:167` do the same — so the new dialog matches the established pattern rather than diverging.
2. _"`getOwnCoachProfile` returns the raw Axios promise while sibling API methods unwrap `res.data`."_ `boot/axios.js:130-142` installs a response interceptor that returns `response.data`, so the raw-promise form is correct for the `api` instance and `coachProfile.value` really is the unwrapped body.

**Patch application** (2026-10-02, all 19 applied)

- **D1**: `EditCoachAvailabilityDialog.vue` timezone is now static text sourced from `form.canonicalTimezone`; the whole `getSupportedTimezones`/filter/picker machinery was removed from this dialog since it's no longer editable here.
- **D2+Patch4**: `CoachProfileService.deletePhoto` now locks and refreshes the profile (`findByIdForUpdate` + `entityManager.refresh(..., PESSIMISTIC_WRITE)`, mirroring `saveStep4`) before mutating — closes Patch 4's concurrent-suspension-revert gap — and catches `StorageObjectNotFoundException`/`AuthorizationException` around `softDelete`, clearing `photoUrl` regardless and warn-logging the ownership-mismatch case — closes D2. Two new ITs in `CoachProfileSelfEditIT` cover both tolerated paths; two new unit tests in `CoachProfileServiceTest` cover the same at the service layer.
- **D3**: new `PlayerProfileNotFoundException` (→ `security.playerProfileNotFound`, 404) replaces `UserNotFoundException` in `updateOwnPosition`; `ProfilePage.vue`'s Player Profile section renders a "complete your player profile" CTA (`to="/player/profile-builder"`) instead of a dead Edit button when `playerProfile` is null.
- **D4**: `ProfilePage.vue` reads `coachProfile.status`; the Coach Profile section renders a read-only note with no edit buttons when `status === 'SUSPENDED'`.
- **Patch 1**: `ProfilePage.vue` now tracks `coachProfileError` separately from "no coach role" — on a fetch failure, every coach edit button is hidden behind a retry-able error row instead of rendering the dialogs with a blank prefill.
- **Patch 2**: all six new dialogs now bind `:error`/`:error-message` to `hasFieldError`/`getFieldError` (the same `useErrorHandler` methods the six pre-existing dialogs already use) on every simple field; list-shaped fields (`sessionPacks`, `windows`) get a fallback text line.
- **Patch 3**: `ShadowAccountService.updateOwnPosition`/`updateChildPosition` now lock-and-refresh (same pattern as D2/Patch4) before mutating, closing the GDPR-tombstone lost-update window. New `EntityManager`/`PessimisticLockRetryer` constructor deps.
- **Patch 5**: in the five dialogs that have a `<q-form>` (Identity, Specialties, Pricing, Availability, Position), the Save button moved inside the form with `type="submit"` (no `@click`), so a native submit now actually runs `QForm`'s validation. Photo dialog has no form and was unaffected.
- **Patch 6**: `EditCoachPricingDialog.vue` adds a `hasDuplicateSessionCount` check (new `step3PackDuplicateSessionCount` i18n key) alongside the existing touched-invalid-pack-row guard.
- **Patch 7**: `EditPlayerPositionDialog.vue` now re-derives `position` from `currentPosition` every time the dialog is reopened (watches `modelValue`), not only on a prop-value change — a cancelled edit can no longer survive into the next open of the shared dialog instance.
- **Patch 8**: `ProfilePageSpec.js` adds a two-children test that clicks a specific child's Edit button and asserts the shared `EditPlayerPositionDialog`'s `player-id` prop matches that child, not the other one.
- **Patch 9/10/12**: `EditCoachPhotoDialog.vue` adds a `@rejected` handler (new `photoRejected` i18n key), revokes the previous blob URL before creating a new one and on `onUnmounted`, and derives the extension via `lastIndexOf('.')` instead of `split('.').pop()`.
- **Patch 11**: `profile.coachAvailabilityWindowCount` is now a vue-i18n pluralized message (`{count} ... | 1 ... | {count} ...`, matching the existing `marketplace.reviewCount` convention) instead of a concatenated string.
- **Patch 13**: `EditCoachPhotoDialogSpec.js`'s three vacuous confirm-gate tests now mount with `attachTo: document.body` (QDialog teleports) and actually click the real "Remove photo"/Cancel/Confirm buttons instead of poking `confirmingDelete` directly.
- **Patch 14**: `ShadowAccountResourceIT` gained a `COACH` test user; the test is now `updateOwnPosition_asCoach_returns403` as AC4 specified.
- **Patch 15**: the three stale `ShadowAccountResource.java` citations in this story's own Context/AC5 sections (`:34-42`/`:44-52`/`:78`/`:70`) are corrected to their current `HEAD` lines above.

Full regression after applying: `mvn compiler:compile`/`testCompile` clean; `CoachProfileServiceTest` (9), `ShadowAccountServiceIT` (11), `ShadowAccountResourceIT` (5), `CoachProfileSelfEditIT` (13), `CoachProfileBuilderIT` (34, unaffected), `CoachProfileServiceConcurrencyIT` (4) all green; frontend `npx vitest run` (25 files / 175 tests) and `npx eslint` both clean on every touched file.

---

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5), via `/bmad-dev-story`.

### Debug Log References

- `mvn -q -o compiler:compile` / `compiler:testCompile` used throughout for fast backend compile checks (the full `mvn -q -o compile` goal also runs the frontend's `npm`/Vite build via `frontend-maven-plugin`, which is unnecessary noise for a Java-only edit loop).
- Targeted backend test runs via `mvn -q -o test -Dtest=<Class>`; targeted frontend runs via `npx vitest run <path>`, `npx eslint`, `npx prettier --write` on touched files; full frontend suite via `npm run test:unit`.
- **AC2's required empirical confirmation surfaced a genuine pre-existing bug, not a false alarm**: the new `saveStep2_onActiveCoach_succeedsAndProfileStaysActive` IT (resubmitting the same `ageGroups=["ADULT"]` the initial `saveAllSteps` call already wrote) 400'd with `generic.dataError`. Root cause: Hibernate's default flush action order runs entity INSERTs before DELETEs within one flush, so `CoachProfileService.saveStep2`'s `deleteByCoachId(...)` (schedules removes) followed immediately by `saveAll(...)` (schedules inserts) — both still pending in the same transaction — let the new row's INSERT fire before the old row's DELETE, violating `uq_coach_age_group (coach_id, age_tier)` whenever a resubmission repeats an existing value. The identical `deleteByCoachId`-then-`saveAll` pattern in `saveStep3`'s session-pack branch has the same exposure against `uq_session_pack (coach_id, session_count)`; `saveStep4`'s availability windows have no such unique constraint (confirmed by reading `V138__baseline_schema.sql`) and are unaffected. **Fixed** by calling `entityManager.flush()` immediately after each `deleteByCoachId(...)` and before the matching `saveAll(...)`, in both `saveStep2` and `saveStep3` — forces the pending DELETEs to execute in their own flush, ahead of the new INSERTs. Verified both ways: red before the fix (the IT failure above), green after, plus a new dedicated `saveStep3_resubmittingSameSessionPackCount_doesNotViolateUniqueConstraint` IT pinning the `saveStep3` side. Re-ran the full pre-existing `CoachProfileBuilderIT` (34 tests) afterward to confirm no regression to the original onboarding flow.

### Completion Notes List

- **AC1** — `CoachProfileService.getOwnProfile(Long userId)` assembles the coach's full profile from the same repositories `saveStep1`-`saveStep5` already use (not the public view's `coachPublicProfileFactsRepository` path), so it works pre-publish (`DRAFT`). New `GET /api/marketplace/coaches/me/profile` (bare root of the existing `ProfileBuilderResource` mapping) exposes it, `@PreAuthorize(HAS_COACH_ROLE)`. 3 new unit tests (`CoachProfileServiceTest`) + 3 IT cases (`CoachProfileSelfEditIT`: draft-works, as-coach-200, as-parent-403).
- **AC2** — Four new dialogs (`EditCoachIdentityDialog`, `EditCoachSpecialtiesDialog`, `EditCoachPricingDialog`, `EditCoachAvailabilityDialog`) added to a new role-gated "Coach Profile" `.glass-card.profile-section` in `ProfilePage.vue` (`v-if="authStore.isCoach"`), each prefilled from the AC1 response and submitting via the existing `saveProfileBuilderStep(1-4, ...)`. Pricing dialog reuses `ProfileBuilderStep3.vue`'s touched-invalid-pack-row guard verbatim. The mandatory empirical IT confirmation (per AC2's "not skippable" requirement) found and the fix above resolved a real latent bug in `saveStep2`/`saveStep3`; `saveStep4` needed no fix. 4 new IT cases in `CoachProfileSelfEditIT` (steps 1-4 against a real `ACTIVE` coach, including the two overlapping-value regression cases) + 17 new frontend Vitest specs across the four dialogs + `ProfilePageSpec`.
- **AC3** — `CoachProfileService.deletePhoto(userId, currentUserLogin)` (new `FileStorageService` dependency, injected as `platform.filestorage.service.FileStorageService` — confirmed not `infrastructure.blobstore`) soft-deletes the storage object and clears `photoUrl`, idempotent when already null. New `DELETE /api/marketplace/coaches/me/profile/photo`. `EditCoachPhotoDialog.vue` reuses the existing sign/confirm/step5 upload mechanics verbatim and adds a confirm-before-remove action (`profile.confirmDeletePhoto`) calling the new `deleteCoachPhoto()` API export. 2 unit tests + 3 IT cases (incl. a real `main.file_storage_objects` row inserted to exercise the ownership-checked `softDelete` path) + 5 frontend specs.
- **AC4** — `UpdatePlayerPositionRequest`, `ShadowAccountService.updateOwnPosition`, new `PATCH /api/security/players/me/position` (returns the updated `PlayerProfileResponse`, matching `GET /me`'s shape). New role-gated "Player Profile" section in `ProfilePage.vue`. 2 service IT cases + a brand-new `ShadowAccountResourceIT` (no resource-level IT existed for this resource before this story) + 4 frontend specs (shared with AC5, see below).
- **AC5** — `ShadowAccountService.updateChildPosition(playerId, parentId, position)` (same `(playerId, parentId)` shape as `getPlayerProfile`, no pessimistic lock per the story's own documented rationale), new `PATCH /api/security/players/{playerId}/position` reusing `@playerOwnershipGuard.check` verbatim. New "My Children's Profiles" section renders `playerStore.players` (fetched defensively inside `loadProfile()` rather than assumed-populated) with one row per child, reusing `EditPlayerPositionDialog.vue` via an optional `player-id` prop rather than duplicating the dialog. `ShadowAccountResourceIT` includes the critical cross-family-isolation case (`updateChildPosition_otherParentsChild_returns403`).
- **AC6** — No production code: the three new sections' own role gates (`isCoach`/`isPlayer`/`isParent`) already exclude Admin by construction. Added the explicit pinning assertion in `ProfilePageSpec.js` (Admin renders exactly the two pre-existing cards, none of the three new sections) so this stays a documented decision rather than a silent gap.
- **i18n** — New `profile.*` keys (section titles, row labels, dialog titles, delete-photo confirm/remove copy) added to **all three** locale files (`en-US`, `fr-FR`, `de-DE`) in matching positions — confirmed this project's actual current convention is full three-locale parity (no key-count drift across the files, no parity-skip precedent found for a story without an explicit scoping decision), not the `en-US`-only question the Dev Notes flagged as open. Reused existing keys where possible (`auth.coach.*` field labels, `auth.player.position*`, `common.save`/`cancel`/`confirm`, `success.infoChanged`) rather than duplicating them.
- **Scope boundary respected**: `AuthService`, login/registration flows, `CoachPublicProfilePage.vue`, and the onboarding wizard pages/store (`profileBuilder.store.js`, etc.) were not touched — the new dialogs load-once-edit-resubmit against the AC1 response, never the builder store.
- **Full regression**: targeted backend suite (`CoachProfileServiceTest`, `CoachProfileBuilderIT`, `CoachProfileSelfEditIT`, `ShadowAccountServiceIT`, `ShadowAccountResourceIT`, `CoachProfileServiceConcurrencyIT`) and the complete frontend `npm run test:unit` (25 files / 165 tests) both green; `npx eslint` clean on every touched frontend file. No local `mvn verify` per this project's standing convention — GitHub CI is the full-verification gate.

### File List

**Backend — production:**
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachAvailabilityWindowDto.java` (new)
- `src/main/java/com/softropic/skillars/platform/marketplace/contract/CoachProfileSelfResponse.java` (new)
- `src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java` (modified — `getOwnProfile`, `deletePhoto`, `FileStorageService` dependency, flush-before-reinsert fix in `saveStep2`/`saveStep3`; review pass: `deletePhoto` lock-and-refresh (Patch 4) + tolerant storage-exception handling (D2))
- `src/main/java/com/softropic/skillars/platform/marketplace/api/ProfileBuilderResource.java` (modified — new `GET` root, new `DELETE /photo`)
- `src/main/java/com/softropic/skillars/platform/security/contract/UpdatePlayerPositionRequest.java` (new)
- `src/main/java/com/softropic/skillars/platform/security/contract/exception/PlayerProfileNotFoundException.java` (new — review D3)
- `src/main/java/com/softropic/skillars/platform/security/service/ShadowAccountService.java` (modified — `updateOwnPosition`, `updateChildPosition`; review pass: lock-and-refresh (Patch 3), `PlayerProfileNotFoundException` (D3), new `EntityManager`/`PessimisticLockRetryer` deps)
- `src/main/java/com/softropic/skillars/platform/security/api/ShadowAccountResource.java` (modified — new `PATCH /me/position`, new `PATCH /{playerId}/position`)
- `src/main/java/com/softropic/skillars/platform/security/api/ApiAdvice.java` (modified — review D3: new `playerProfileNotFoundHandler`)

**Backend — tests:**
- `src/test/java/com/softropic/skillars/platform/marketplace/service/CoachProfileServiceTest.java` (modified — `getOwnProfile`/`deletePhoto` unit tests, new `FileStorageService` mock; review pass: `lockRetryer` stub + 2 new D2 tolerant-path tests)
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileSelfEditIT.java` (new; review pass: 2 new D2 tolerant-path ITs)
- `src/test/java/com/softropic/skillars/platform/security/service/ShadowAccountServiceIT.java` (modified — `updateOwnPosition`/`updateChildPosition` tests; review pass: `updateOwnPosition_noProfile_throws` now expects `PlayerProfileNotFoundException`)
- `src/test/java/com/softropic/skillars/platform/security/api/ShadowAccountResourceIT.java` (new; review pass Patch 14: added a `COACH` test user, renamed `updateOwnPosition_asParent_returns403` → `updateOwnPosition_asCoach_returns403`)

**Frontend — production:**
- `src/frontend/src/api/marketplace.api.js` (modified — `getOwnCoachProfile`, `deleteCoachPhoto`)
- `src/frontend/src/api/playerProfile.api.js` (modified — `updateOwnPosition`, `updateChildPosition`)
- `src/frontend/src/pages/ProfilePage.vue` (modified — three new role-gated sections, six new dialog imports/bindings; review pass: `coachProfileError`/`isCoachSuspended` gating (Patch 1/D4), player-profile CTA (D3), pluralized window count (Patch 11))
- `src/frontend/src/components/profile/EditCoachIdentityDialog.vue` (new; review pass: per-field errors (Patch 2), Save button inside `<q-form>` (Patch 5))
- `src/frontend/src/components/profile/EditCoachSpecialtiesDialog.vue` (new; review pass: Patch 2/5)
- `src/frontend/src/components/profile/EditCoachPricingDialog.vue` (new; review pass: Patch 2/5, duplicate-sessionCount guard (Patch 6))
- `src/frontend/src/components/profile/EditCoachAvailabilityDialog.vue` (new; review pass: read-only timezone (D1), Patch 2/5)
- `src/frontend/src/components/profile/EditCoachPhotoDialog.vue` (new; review pass: `@rejected` handling (Patch 9), blob URL revocation (Patch 10), extension dot-check (Patch 12))
- `src/frontend/src/components/profile/EditPlayerPositionDialog.vue` (new; review pass: per-field errors (Patch 2), Save button inside `<q-form>` (Patch 5), reset-on-reopen (Patch 7))
- `src/frontend/src/i18n/en-US/index.js` (modified — new `profile.*` keys; review pass: `playerProfileNotFound`, pluralized `coachAvailabilityWindowCount`, `coachSuspendedNote`/`coachProfileLoadError`/`noPlayerProfile`/`completePlayerProfile`/`availabilityTimezoneReadonlyHint`/`photoRejected`, `auth.coach.step3PackDuplicateSessionCount`)
- `src/frontend/src/i18n/fr-FR/index.js` (modified — same review-pass keys as `en-US`)
- `src/frontend/src/i18n/de-DE/index.js` (modified — same review-pass keys as `en-US`)

**Frontend — tests:**
- `src/frontend/src/components/profile/__tests__/EditCoachIdentityDialogSpec.js` (new)
- `src/frontend/src/components/profile/__tests__/EditCoachSpecialtiesDialogSpec.js` (new)
- `src/frontend/src/components/profile/__tests__/EditCoachPricingDialogSpec.js` (new)
- `src/frontend/src/components/profile/__tests__/EditCoachAvailabilityDialogSpec.js` (new; review pass: removed the now-unused `getSupportedTimezones` mock, added a D1 read-only-timezone test)
- `src/frontend/src/components/profile/__tests__/EditCoachPhotoDialogSpec.js` (new; review pass Patch 13: the three vacuous confirm-gate tests now mount with `attachTo: document.body` and click the real buttons; added Patch 9/10/12 coverage)
- `src/frontend/src/components/profile/__tests__/EditPlayerPositionDialogSpec.js` (new; review pass Patch 7: added a close/reopen-does-not-leak-a-cancelled-edit test)
- `src/frontend/src/pages/__tests__/ProfilePageSpec.js` (new; review pass: added Patch 1/D3/D4 coverage and the Patch 8 two-children `player-id`-routing test)

**Docs/ledger:**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified — status `ready-for-dev` → `in-progress` → `review`, `last_updated` note)
- `_bmad-output/implementation-artifacts/skillars-deferred-139-my-profile-role-aware-field-management-and-coach-photo-delete.md` (this story file — Tasks, Dev Agent Record, Status)

### Change Log

- 2026-10-02: Story created via `/bmad-create-story`, sourced from the top-priority `deferred-work.md` item on `ProfilePage.vue`'s role-agnostic gap (manual testing, 2026-10-01). All four of the ledger's open follow-up questions resolved during drafting (idempotency confirmed by reading `CoachProfileService`, UI approach decided as role-gated sections on the existing page, player/parent endpoint-reuse check completed and found not reusable — new endpoints designed instead). Status: ready-for-dev.
- 2026-10-02: Independent story review (`story-review.md`) applied — 3 of its 4 flagged items were genuine and incorporated: (1) confirmed the timezone-validation gap is actually closed (not just "maybe," per Dev Notes' prior hedge) and tightened that note; (2) resolved AC5's deferred concurrency decision outright (plain `findByIdAndParentId`, no pessimistic lock — documented rationale in AC5 directly, no longer left for the dev agent to decide) and fixed a parameter-shape bug this introduced (service method needs `parentId` too, matching `getPlayerProfile`'s existing `(playerId, parentId)` shape); (3) added an explicit out-of-scope note for AC2's pre-existing `saveStep1`-`saveStep3` vs `saveStep4` SUSPENDED-check inconsistency, so it isn't mistaken for new-story scope. The review's 4th item — a "critical race condition" in AC3's `deletePhoto` — was a **false positive**: independently re-verified `FileStorageService.softDelete`'s full method body and found it makes no external storage call at all (its cited "S3 authorization timeout on line 244" is actually a plain in-memory `String.equals` ownership check); both DB writes already commit atomically under `deletePhoto`'s existing `@Transactional`. Added an explicit atomicity note to AC3 to preempt the same misreading during implementation, but made no design change.
- 2026-10-02: Adversarial code review (`/bmad-code-review`) applied in full — all 19 Patch findings (4 that began as Decisions, resolved with Mbah during the walkthrough, plus 15 further unambiguous patches) implemented; see the "Patch application" note under Review Findings above for a per-item summary, and the updated File List for exactly what changed. All pre-existing targeted backend suites plus two new IT/unit pairs (D2's tolerant-delete paths) and the complete frontend suite re-ran green; `npx eslint`/`npx prettier` clean on every touched file. The 3 Deferred items and 2 dismissed-false-positive findings were left exactly as the review recorded them — no changes made for those.
