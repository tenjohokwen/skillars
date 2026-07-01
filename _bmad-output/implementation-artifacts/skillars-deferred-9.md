# Story Deferred-9: Frontend UX Polish

Status: backlog

## Story

As a coach or parent using the platform,
I want error states to be visible, player navigation to load fresh data, and batch booking to not carry stale state,
so that I always have accurate feedback and never see stale or missing content after navigating the app.

## Acceptance Criteria

1. **Given** a coach navigates between players on the development dashboard
   **When** the player route param changes (`route.params.playerId` watch fires)
   **Then** `useDevelopmentStore` state (`exposure`, `targets`, `narrative`, `neglectedSkills`) is cleared to null/empty before the new player's data is loaded — stale data from the previous player is not shown during the load
   **File**: `PlayerDevelopmentDashboardPage.vue`

2. **Given** a coach clicks Accept or Decline on a booking request
   **When** the API call fails (network error or 4xx/5xx)
   **Then** the page displays an error notification (Quasar `$q.notify` or equivalent) — the button spinner stops and an error message is shown; the coach can distinguish failure from success
   **File**: `CoachBookingRequestsPage.vue` — `handleAccept()` and `handleDecline()` error paths

3. **Given** a parent views their bookings page
   **When** the API call for `getParentBookings()` fails
   **Then** an error message is displayed in the template (the `bookingsError` ref is already captured but never rendered)
   **File**: `ParentBookingsPage.vue` — add an error alert to the template conditioned on `bookingsError`

4. **Given** a parent submits a booking request
   **When** the API call fails with 400, 403, or a network error
   **Then** a user-visible error notification is shown on `BookingRequestPage.vue` — the user is not left on the page with no feedback
   **File**: `BookingRequestPage.vue` — `submitBookingRequest()` error path (lines 112-128)

5. **Given** a coach is on the wrap-up sequence (step 1 or step 4) and `fetchSessionDna` fires
   **When** the request is in flight
   **Then** a loading indicator is shown over the DNA chart area
   **And** if the fetch fails, a placeholder message ("Unable to load session DNA") is shown rather than a silently absent chart
   **File**: `WrapUpSequence.vue` — add `dnaLoading` ref and `dnaError` ref

6. **Given** a parent or coach toggles batch mode on `BookingRequestPage.vue`
   **When** `toggleBatchMode()` sets `batchMode = true`
   **Then** `selectedSlot` is cleared to null so `canSubmit` is not immediately true from a prior single-slot selection
   **File**: `BookingRequestPage.vue:toggleBatchMode()`

7. **Given** the app displays development module text for German-locale users
   **When** `de/index.js` is loaded
   **Then** the development block contains German translations for all keys in the `en-US` development block (skills, radar, exposure, targets, narrative) — the current `de/index.js` development block contains English strings

## Tasks / Subtasks

- [ ] **Task 1 — Clear development store on player navigation** (AC: 1)
  - [ ] Read `PlayerDevelopmentDashboardPage.vue` — find the `watch` on `route.params.playerId` and the `onMounted` hook
  - [ ] At the top of the watcher (before `loadPortal()` or equivalent):
    ```javascript
    watch(() => route.params.playerId, async (newPlayerId) => {
        // Clear stale state before loading new player
        developmentStore.exposure = null;
        developmentStore.targets = [];
        developmentStore.narrative = null;
        developmentStore.neglectedSkills = [];
        // then load
        await loadPortal(newPlayerId);
    }, { immediate: false });
    ```
  - [ ] Verify the exact store property names from `useDevelopmentStore()` / `development.store.js`
  - [ ] Also apply the same clear in `onMounted` before the initial load (from 5.2 AD1 — stale error state between player switches)

- [ ] **Task 2 — Booking accept/decline error notification** (AC: 2)
  - [ ] Read `CoachBookingRequestsPage.vue:75-92` — find `handleAccept()` and `handleDecline()`
  - [ ] Add error handling in each:
    ```javascript
    async handleAccept(bookingId) {
        this.accepting[bookingId] = true;
        try {
            await bookingStore.acceptBooking(bookingId);
            this.$q.notify({ type: 'positive', message: 'Booking accepted' });
        } catch (err) {
            this.$q.notify({
                type: 'negative',
                message: err?.response?.data?.message || 'Failed to accept booking. Please try again.'
            });
        } finally {
            this.accepting[bookingId] = false;
        }
    }
    ```
  - [ ] Same pattern for `handleDecline()`
  - [ ] If `this.$q` is not accessible (component uses Composition API), use `useQuasar()`:
    ```javascript
    const $q = useQuasar();
    $q.notify({ type: 'negative', message: '...' });
    ```

- [ ] **Task 3 — Render `bookingsError` in `ParentBookingsPage`** (AC: 3)
  - [ ] Read `ParentBookingsPage.vue` — find where `bookingsError` is set and the current template
  - [ ] Add to the template, adjacent to the bookings list:
    ```html
    <q-banner v-if="bookingsError" class="bg-negative text-white" rounded>
        Unable to load your bookings. Please refresh the page.
    </q-banner>
    ```
  - [ ] Or use the project's existing error alert component if one exists

- [ ] **Task 4 — Booking request submission error feedback** (AC: 4)
  - [ ] Read `BookingRequestPage.vue:112-128` — find `submitBookingRequest()` error path
  - [ ] In the catch block:
    ```javascript
    } catch (err) {
        const msg = err?.response?.data?.message
            || err?.response?.data?.helpCode
            || 'Booking request failed. Please check your details and try again.';
        this.$q.notify({ type: 'negative', message: msg });
    }
    ```
  - [ ] Ensure the button's loading state is reset in a `finally` block so the button is re-enabled after failure

- [ ] **Task 5 — WrapUpSequence DNA loading and error state** (AC: 5)
  - [ ] Read `WrapUpSequence.vue:309` — find `fetchSessionDna()` and where the DNA chart is rendered
  - [ ] Add refs:
    ```javascript
    const dnaLoading = ref(false);
    const dnaError = ref(null);
    ```
  - [ ] Wrap the fetch:
    ```javascript
    async function fetchSessionDna() {
        dnaLoading.value = true;
        dnaError.value = null;
        try {
            sessionDna.value = await sessionStore.fetchDna(bookingId.value);
        } catch (err) {
            dnaError.value = 'Unable to load session DNA';
        } finally {
            dnaLoading.value = false;
        }
    }
    ```
  - [ ] In the template, wrap the DNA chart component:
    ```html
    <q-inner-loading :showing="dnaLoading" />
    <div v-if="dnaError" class="text-grey-6 text-center q-pa-md">{{ dnaError }}</div>
    <SkillsDnaChart v-else-if="sessionDna" :data="sessionDna" />
    ```

- [ ] **Task 6 — Clear `selectedSlot` on batch mode toggle** (AC: 6)
  - [ ] Read `BookingRequestPage.vue` — find `toggleBatchMode()`
  - [ ] Add:
    ```javascript
    function toggleBatchMode() {
        batchMode.value = !batchMode.value;
        if (batchMode.value) {
            selectedSlot.value = null;  // clear stale single-slot selection
        }
    }
    ```

- [ ] **Task 7 — German translations for development module** (AC: 7)
  - [ ] Read `src/frontend/src/i18n/en/index.js` (or `en-US/index.js`) — find the `development` block with all keys for skills, radar, exposure, targets, narrative
  - [ ] Read `src/frontend/src/i18n/de/index.js` — find the `development` block that currently contains English strings
  - [ ] Translate each key to German. Keys expected (verify from `en` source):
    - Skills taxonomy: skill names, categories
    - Radar: "Skills Radar", "Assessment", axis labels
    - Exposure: "Skill Exposure", "Neglected Skills", "Weekly Target"
    - Targets: "Set Target", "Current Target"
    - Narrative: "Development Narrative", "This Week", "Trend"
  - [ ] If the development block in `de` is entirely missing (not just English strings), add the full block
  - [ ] Machine-translate as a first pass; mark each string with `// TODO: native review` comment for a human translation pass later

## Dev Notes

### Quasar `$q.notify` vs `useQuasar()`

If components use Options API, `this.$q.notify(...)` is available. If they use Composition API `<script setup>`, use:
```javascript
import { useQuasar } from 'quasar';
const $q = useQuasar();
```
Check the component's script style before choosing which pattern to apply.

### Store property names — verify before clearing

The exact property names in `useDevelopmentStore()` may differ from `exposure`, `targets`, `narrative`, `neglectedSkills`. Read `development.store.js` (or the relevant Pinia store file) to get the exact state property names before Task 1.

### `SluTargetEditor` race (5.2 D5)

While working on Task 1, also check `SluTargetEditor.vue:51` — a targets-loaded watcher can discard user input if `fetchTargets` resolves while the dialog is open. Consider adding a guard: `if (dialogOpen.value) return;` in the watcher. This is a low-probability UX issue but simple to fix while in the component.

### German translation scope

Task 7 is a content translation task, not a code change. Focus on getting the key structure correct in `de/index.js` so Vue-i18n lookups succeed — exact wording can be refined in a follow-up native-speaker review. Do not leave any key missing (missing key falls back to `en-US` which is English — defeat the purpose of the German locale).

### `variant="compact"` in WrapUpSequence (4.4 W3)

Story 4.4 noted `WrapUpSequence` uses `variant="compact"` instead of `variant="full"` for the DNA chart — while in this file (Task 5), also correct this to `variant="full"` as specified in the spec.

### References — Files to Read Before Implementing

- `PlayerDevelopmentDashboardPage.vue` — `route.params.playerId` watcher and `onMounted`
- `development.store.js` — state property names for Task 1 clear
- `CoachBookingRequestsPage.vue:75-92` — `handleAccept()` and `handleDecline()` error paths
- `ParentBookingsPage.vue` — `bookingsError` ref and template structure
- `BookingRequestPage.vue:112-128` — `submitBookingRequest()` and `toggleBatchMode()`
- `WrapUpSequence.vue:309` — `fetchSessionDna()` and DNA chart rendering
- `src/frontend/src/i18n/de/index.js` — current German development block
- `src/frontend/src/i18n/en/index.js` (or `en-US`) — reference keys for translation

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

**Modified Files:**
- `src/frontend/src/pages/PlayerDevelopmentDashboardPage.vue`
- `src/frontend/src/pages/CoachBookingRequestsPage.vue`
- `src/frontend/src/pages/ParentBookingsPage.vue`
- `src/frontend/src/pages/BookingRequestPage.vue`
- `src/frontend/src/components/WrapUpSequence.vue`
- `src/frontend/src/i18n/de/index.js`
