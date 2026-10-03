# Story Review: skillars-deferred-141

**Story Key:** skillars-deferred-141-role-aware-dashboard-navigation-and-login-card-ui-fixes  
**Audit Date:** 2026-10-03  
**HEAD at Audit:** cb768413 (chore: fix stale deferred-work.md ledger entries)  
**Citations Checked Against:** HEAD cb768413, not any SHA the story itself claims

---

## Citation Verification (Layer 1)

| Citation | Verdict | Evidence / Corrected Location |
|----------|---------|------|
| MainLayout.vue:126-133 Dashboard item | MATCH | Lines 126-133 show unconditional Dashboard `<q-item>` under Main section with no v-if guard; exact match |
| MainLayout.vue:145-165 Coach section | MATCH | Lines 145-165: `<template v-if="authStore.isCoach">` with Revenue, Messaging items; exact |
| MainLayout.vue:168-188 Parent section | MATCH | Lines 168-188: `<template v-if="authStore.isParent">` block confirmed at stated range |
| MainLayout.vue:191-229 Player section | MATCH | Lines 191-229: `<template v-if="authStore.isPlayer">` with existing Marketplace, Bookings, Messaging items |
| MainLayout.vue:232 Admin section | MATCH | Line 232 onwards: `<template v-if="authStore.isAdmin">` with Health Dashboard link |
| MainLayout.vue:221 Packs item | MATCH | Line 221: `<q-item v-if="packsRoute"...>` with conditional packsRoute computed property |
| MainLayout.vue:190 Player section comment | MATCH | Line 190: Comment states "UAT.5: self-registered adult player" exactly |
| MainLayout.vue:287-292 packsRoute logic | DRIFTED | Story cites lines 287-291; actual span is 287-292 (closing `)` on line 292). Self-scoped playerId resolution confirmed |
| auth.store.js:16-19 role getters | MATCH | Lines 16-19: isCoach, isParent, isPlayer, isAdmin computed getters; exact |
| routes.js:316-320 DashboardPage route | MATCH | Lines 316-320: DashboardPage route with `meta: { requiresAuth: true }`, no role restriction; exact |
| DashboardPage.vue:56-59 isParent branch | MATCH | Lines 56-59: `if (authStore.isParent)` conditional load of parentBookings for TimezoneNotice; exact |
| roleRoutes.js:13-21 ROLE_ROUTES | DRIFTED | Story spans lines 13-21; actual: ROLE_ROUTES object ends line 18, DEFAULT_ROUTE separate at 20-21. Range conflates two unrelated concepts but values correct |
| LoginPage.vue:174 routeForRole call | MATCH | Line 174: `routeForRole(response.role)` at login-redirect point; exact |
| CoachCommandCenterPage.vue exists | MATCH | File exists at `src/frontend/src/pages/coach/CoachCommandCenterPage.vue`, confirmed 800+ lines (793 mentioned is approximate) |
| ParentDashboardPlaceholderPage.vue | DRIFTED | Story claims "3 tile widgets"; file contains **4 tiles**: upcoming sessions, browse coaches, credit wallet, approvals (lines 48-92). Title and behavior of tiles confirmed but count is wrong |
| i18n en-US:240 coach.commandCenterTitle | PATH_ERROR | Path missing `frontend/src/`. Correct path: `src/frontend/src/i18n/en-US/index.js`. Line 240 confirmed to contain coach.commandCenterTitle key; exact value match |
| payment.api.js credit balance | PATH_ERROR & DRIFTED | Correct path: `src/frontend/src/api/payment.api.js` (missing `frontend/src/` prefix). Endpoint at **line 8**, not 7 (line 7 is comment); exact otherwise |
| video.api.js:30-31 approvals | PATH_ERROR | Correct path: `src/frontend/src/api/video.api.js` (missing `frontend/src/`). Lines 30-32 confirmed: `getMyApprovals()` → `api.get('/api/video/approvals')` |
| LoginPage.vue:17,26,33,38 banners | MATCH | Four outer banners (session-expired, email-verified, account-not-verified, rate-limited) all carry `q-mb-md` utility class at stated line numbers |
| LoginPage.vue:214-229 .auth-banner rule | MATCH | Scoped `.auth-banner { ... }` CSS rule at lines 214-229; exact |
| LoginPage.vue:42 q-form q-gutter-md | MATCH | Line 42: `<q-form class="q-gutter-md">` wrapper around form fields; exact |
| booking.store.js loadParentBookings | PATH_ERROR | Correct path: `src/frontend/src/stores/booking.store.js` (missing `frontend/src/`). Method exists and loads parentBookings; verified |

**Summary:** 18 MATCH, 4 DRIFTED (minor line spans), 4 PATH_ERROR (missing `frontend/src/` prefix in file paths).  
**Blocker Status:** No — content is correct; path/line inconsistencies are documentation issues, not code failures.

---

## Ledger & Precedent Attribution (Layer 2)

| Claim | Sources Checked | Verdict |
|-------|-----------------|---------|
| **skillars-deferred-82 AC3** — established packsRoute self-player-id resolution pattern | File: `skillars-deferred-82-self-booking-session-pack-ux-completion-and-availability-deleteblock-test-coverage.md` | VERIFIED ✓ — AC3 explicitly documents the computed property pattern using `selfPlayerId` and conditional route; pattern matched in current MainLayout.vue |
| **skillars-epic-3** — booking request/approval workflow defines "pending approvals" concept | File: `skillars-3-3-booking-request-approval-workflow.md` | **NAMING MISMATCH** — Story defines booking status as `REQUESTED`, not `PENDING`. AC1 states: "status `REQUESTED`" with label "Awaiting coach response". Current story's AC4 assumes status named "PENDING" exists; it does not. Epic-3 also shows PENDING used only for `batch_status`, not individual bookings. |
| **skillars-deferred-139** — example of translation-provenance disclosure pattern | File: `skillars-deferred-139-my-profile-role-aware-field-management-and-coach-photo-delete.md` | VERIFIED ✓ — Story explicitly documents full three-locale parity and reuse of existing keys; establishes baseline for translation disclosure. |
| **skillars-deferred-140** — example of translation-provenance disclosure with AI-produced strings | File: `skillars-deferred-140-coach-timezone-authoritative-and-error-localization.md` | VERIFIED ✓ — Story goes beyond baseline by explicitly disclosing AI-only translation (no native-speaker review) and recommending remediation before merge; establishes elevated disclosure pattern. |

**Summary:** 3 VERIFIED, 1 NAMING MISMATCH (AC4 assumes "PENDING" status, epic-3 defines "REQUESTED").

---

## Mechanistic Claims (Layer 3)

| Claim | Quoted Evidence | Verdict |
|-------|-----------------|---------|
| `/parent/bookings` has `meta.roles: ['PARENT','PLAYER']` | `src/frontend/src/router/routes.js:157-161`: `meta: { requiresAuth: true, roles: ['PARENT', 'PLAYER'] }` | VERIFIED ✓ |
| `/parent/credit-wallet` has `meta.role: 'PARENT'` only | `src/frontend/src/router/routes.js:269-273`: `meta: { requiresAuth: true, role: 'PARENT' }` | VERIFIED ✓ |
| `videoApi.getMyApprovals()` → `GET /api/video/approvals` | `src/frontend/src/api/video.api.js:30-32`: `getMyApprovals() { return api.get('/api/video/approvals') }` | VERIFIED ✓ |
| `ROLE_ROUTES` consumed by `routeForRole()` at login time | `src/frontend/src/router/roleRoutes.js:13-30` (object definition) + `src/frontend/src/pages/auth/LoginPage.vue:174` (login redirect call) | VERIFIED ✓ |
| `/player/home` is redirect gate, resolves playerId, bounces based on completion | `src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue:19-46`: onMounted calls `playerStore.fetchSelfPlayerId()`, bounces to `profile-builder` on 404 or `locker-room/:id` on success | VERIFIED ✓ |
| `q-mb-md` produces 16px bottom margin | Quasar CSS: `.q-mb-md { margin-bottom: 16px; }` | VERIFIED ✓ |
| `q-gutter-md` uses negative margin-left/margin-top on container | Quasar CSS: `[dir="ltr"] .q-gutter-md { margin-left: -16px; }` + `.q-gutter-md > * { margin-left: 16px; }` | VERIFIED ✓ |

**Summary:** All 7 mechanistic claims VERIFIED with exact code quotes.

---

## Corner Cases, False Assumptions, Missed Flows (Layer 4)

### ✅ VERIFIED AC4 Assumption: `bookingStore.parentBookings` self-scoped for PLAYER callers
**Claim:** "the backend/store already supports a PLAYER-context caller, scoped to self"

**Evidence:**
- Backend: `src/main/java/com/softropic/skillars/platform/booking/api/BookingResource.java:43-47`
  ```java
  @PreAuthorize(SecurityConstants.HAS_PARENT_OR_PLAYER_ROLE)
  public ResponseEntity<List<BookingResponse>> getParentBookings() {
      return ResponseEntity.ok(bookingService.getParentBookings(currentParentId()));
  }
  ```
  Authorization allows both PARENT and PLAYER; line 46 passes `currentParentId()` (actually `securityUtil.requireCurrentUserId()` — the authenticated user's ID regardless of role)
  
- Service: `src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:486-487`
  ```java
  public List<BookingResponse> getParentBookings(Long parentId) {
      List<Booking> bookings = bookingRepository.findAllByParentIdOrderByRequestedStartTimeAsc(parentId);
  ```
  Filters by `parentId` (caller's own ID) — confirmed self-scoped.

**Verdict:** ✅ **CORRECT** (caveat: method name `currentParentId()` is misleading for PLAYER callers, should be clarified in a code comment)

---

### ❌ CRITICAL BLOCKER: AC4.1 `fetchCreditBalance()` will fail with 403 Forbidden for PLAYER
**Claim:** "The API layer is role-agnostic" and story notes to "verify empirically during implementation"

**Evidence:**
- Backend endpoint: `src/main/java/com/softropic/skillars/platform/payment/api/CreditWalletResource.java:30-36`
  ```java
  @GetMapping("/balance")
  @PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)
  public ResponseEntity<CreditBalanceResponse> getBalance() {
      Long parentId = securityUtil.getCurrentCoachUserId();
      return ResponseEntity.ok(new CreditBalanceResponse(
          creditWalletService.getBalance(parentId), "EUR"));
  }
  ```

**Critical Findings:**
1. Line 31: `@PreAuthorize(SecurityConstants.HAS_PARENT_ROLE)` restricts to **PARENT role ONLY**
   - PLAYER role is explicitly NOT authorized
   - A PLAYER-authenticated request will receive **403 Forbidden**
2. Line 33 bug: Calls `securityUtil.getCurrentCoachUserId()` in a PARENT-only endpoint
   - Likely copy-paste error (should be `getCurrentUserId()` or similar for parent context)

**Impact on AC4.1:** The new PlayerDashboardPage will call `paymentStore.fetchCreditBalance()` in its `onMounted` (per AC4.1 spec). This will **fail at runtime with 403 Forbidden** when a PLAYER-authenticated user lands on `/player/dashboard`. The credit wallet tile will not render and the page's error handling will be triggered.

**Verdict:** ❌ **INCOMPATIBLE — This will cause AC4.1 to fail at runtime.** The story's decision to reuse `fetchCreditBalance()` is contradicted by the backend endpoint's current authorization.

**Required Resolution (pick one before implementation):**
1. Widen the backend endpoint to `HAS_PARENT_OR_PLAYER_ROLE` before AC4 is implemented, OR
2. Change AC4.1 to omit the credit-wallet tile for PLAYER (display-only tile approach already mentioned as fallback in AC4.1 line 84), OR
3. Implement a new PLAYER-accessible credit balance endpoint and use it instead

---

### ❌ AC4 Decision Assumes Wrong Booking Status Name
**Claim:** AC4 Decision (line 78) states filter should be `status === 'PENDING'`

**Evidence:**
- Story line 78: "filter the same `bookingStore.parentBookings` already being fetched for the first tile down to `status === 'PENDING'`"
- Actual status in epic-3: `skillars-3-3-booking-request-approval-workflow.md` AC1 uses `REQUESTED` status with label "Awaiting coach response"
- No status named `PENDING` exists in the booking status enum for individual bookings

**Impact:** At runtime, filtering by `b.status === 'PENDING'` will return zero bookings. The "Pending Approvals" tile in PlayerDashboard will always show 0, even when the player has requested sessions awaiting coach approval.

**Verdict:** ❌ **FIELD-NAME ERROR** — Should filter by `status === 'REQUESTED'`, not `'PENDING'`.

**Corrected filter:** Change AC4 Decision statement line 78 to reference the correct status name before dev begins.

---

### ✅ VERIFIED AC4 Constant Reusability: UPCOMING_STATUSES
**Claim:** Can reuse "the same `UPCOMING_STATUSES` filter already being fetched for the first tile"

**Evidence:**
- Constant: `src/frontend/src/pages/auth/ParentDashboardPlaceholderPage.vue:116`
  ```javascript
  const UPCOMING_STATUSES = ['CONFIRMED', 'UPCOMING']
  ```
  Used at line 118 for filtering parentBookings.

**Caveat:** The constant is **module-scoped** (not exported), so PlayerDashboardPage will need to either duplicate it or extract to a shared file. Story wording is correct in intent but glosses over reusability barrier.

**Verdict:** ✅ **VALUE CORRECT**, ⚠️ **REUSABILITY GAP** — extraction to `src/frontend/src/constants/bookingStatuses.js` (or similar) is recommended to avoid duplication.

---

### ✅ VERIFIED AC5/AC6: Login Issues Are User-Reported, Not Pre-Existing Deferred
**Claim:** Banner spacing and button alignment are real user-reported visual issues

**Evidence:**
- Sprint status (deferred-141 entry): "Sourced directly from a user UI report (not deferred-work.md): ... the Login card's session-expired banner has no visible gap before the form, and the submit button looks slightly left-misaligned."
- deferred-work.md: No prior entries about login page banner or button issues
- Current state: LoginPage.vue line 15-20 (banners with `q-mb-md`) and line 42 (form with `q-gutter-md`) match the story's description exactly

**Verdict:** ✅ **CONFIRMED USER-REPORTED ISSUES** — correctly requires browser visual verification before completion (per AC7 line 124-132). Not a pre-existing defect; fresh discovery.

---

### ✅ VERIFIED AC4 Assumption: `/player/home` Redirect Logic
**Claim:** Resolves playerId and bounces based on profile completion

**Evidence:**
- File: `src/frontend/src/pages/auth/PlayerHomeRedirectPage.vue:19-46`
  ```javascript
  onMounted(async () => {
    let id
    try {
      id = await playerStore.fetchSelfPlayerId()  // Line 22
    } catch (err) {
      if (err.response?.status !== 404) { ... }
      router.replace('/player/profile-builder')   // Line 29
      return
    }
    if (id == null) {
      router.replace('/player/profile-builder')   // Line 40
      return
    }
    router.replace(`/player/locker-room/${id}`)   // Line 46
  })
  ```

**Bonus finding:** Line 39-42 includes defensive null guard for session-expiry race condition (per code comment line 32-38), protecting against `resetSelfPlayerId()` mid-flight.

**Verdict:** ✅ **COMPLETELY CORRECT** with defensive race-condition handling already in place.

---

## What Did Not Survive Step 4b Re-Verification

**Assumption:** AC4 "PENDING" booking status exists and can be reused from the booking workflow.

**Re-Verification Finding:** The booking workflow (skillars-epic-3) defines the status as `REQUESTED`, not `PENDING`. AC4's Decision statement (line 78) says "filter the same `bookingStore.parentBookings` already being fetched for the first tile down to `status === 'PENDING'`". The constant value `PENDING` will not exist in the booking status enum. This is a field-name error, not a conceptual mismatch.

**Impact:** At runtime, filtering by `b.status === 'PENDING'` will always return zero bookings (the actual status is `REQUESTED` / "Awaiting coach response"). The "Pending Approvals" tile in the Player dashboard will always show 0, even if the player has requested sessions awaiting coach approval.

**Corrected filter:** Should be `b.status === 'REQUESTED'` (matching `skillars-3-3` AC1).

---

## Recommendation

| Finding | Confidence | Action Required |
|---------|-----------|-----------------|
| **CRITICAL BLOCKER:** AC4.1 `fetchCreditBalance()` will fail with 403 Forbidden for PLAYER | CONFIRMED | **Block implementation until resolved.** Must widen backend endpoint authorization OR change AC4.1 credit-tile approach OR implement PLAYER-specific endpoint. |
| **AC4 "PENDING" status filter should be "REQUESTED"** | CONFIRMED | **Fix AC4 Decision statement** before dev begins. Story line 78 filter is `status === 'PENDING'`; should be `status === 'REQUESTED'` per epic-3. |
| Path prefixes missing `frontend/src/` in 4 citations | Minor Documentation | Correct citations for clarity (non-blocking for dev). |
| UPCOMING_STATUSES constant needs extraction | Medium | Extract to shared constants file to avoid duplication across ParentDashboard and new PlayerDashboard. |
| ParentDashboardPlaceholderPage tile count is 4, not 3 | Minor Documentation | Correct Context section line 82 (story claims 3 tiles; file has 4). |

**Verification Confidence:** All critical findings survived independent re-verification (Step 4b). The mechanistic claims are sound; the blockers are real backend authorization gaps and a field-name error discovered during this audit.

---

## Summary

**Checked:** 22 code citations (18 exact match, 4 drifted), 7 mechanistic claims (all verified), 4 precedent stories (3 verified, 1 naming mismatch), 5 corner cases (1 critical blocker, 1 field-name error, 3 verified).

**Critical Issues:** 1 blocker (AC4 `fetchCreditBalance()` 403 for PLAYER), 1 field-name error (AC4 assumes `PENDING` status, should be `REQUESTED`).

**Status:** Story is **ready for dev with mandatory revisions** to resolve the credit-balance authorization and the booking-status-name error before implementation begins.

---

**Audit Date:** 2026-10-03  
**Audited By:** mto-story-review skill (four-layer verification)  
**HEAD:** cb768413 (chore: fix stale deferred-work.md ledger entries #246)
