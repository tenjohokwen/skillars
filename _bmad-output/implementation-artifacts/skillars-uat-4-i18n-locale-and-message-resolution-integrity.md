# Story UAT.4: I18n Locale & Message Resolution Integrity

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **Read the "Do NOT touch" table in AC1 before editing any `Intl.DateTimeFormat` call.** Several
> hardcoded-`'en'`/`'en-CA'`/`'en-US'` call sites in this codebase look identical to the display bug
> this story fixes but are actually **computational** — they extract date/time parts for offset math,
> ISO-sortable comparison, or numeric parsing, and changing them to the active locale will corrupt
> timezone math or break `Number()` parsing on a non-Latin-digit locale. This distinction was verified
> by reading every call site, not assumed from the deferred-work.md citation, which is stale.

## Story

As a German- or French-speaking user of the app (or an operator validating a non-English UAT run),
I want dates/times to render in my selected UI language and validation error messages to come back in
that language,
so that the app is not silently English-only underneath its own language switcher.

### Why this story exists

Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md` (2026-08-09 ranking).
`skillars-uat-1`, `-2` and `-3` each touched files adjacent to this defect and each deliberately left it
— the priorities doc's own suggested sequence (item 9) names it as **"the last non-decision *code*
items on the list."** Everything else still open in that doc (P0-2 player self-booking, P0-4 coach
subscription, P1 #9 the volume-backup replacement) is a product or ops decision Mbah has to make first,
not a dev pass. This is the only remaining unclaimed item that is just code.

The doc frames P1 #7/#8 as "only worth doing for a non-English UAT," but that undersells it: the app
already ships fully-translated `de` and `fr-FR` bundles, already lets a user pick a language, and the
bug means picking German gets you German *labels* around English *dates* and English *validation
errors* — a real defect for any real non-English user post-UAT, not just a UAT nicety. It is grouped
into one story here because all three items are small, touch the same feature area, and the same
`deferred-work.md` entry (`deferred-9` D2) explicitly says fixing the language switcher without fixing
the backend message bug (D6) "gives a German user German UI text and English field errors" — they were
always meant to land together.

| AC | Source item | Verified current state (2026-08-12, `6d1180c`) |
|---|---|---|
| AC1 | `deferred-17` D3 — `formatSlot` hardcodes `'en'` | **CONFIRMED, and the deferred-work.md file list is stale.** Re-verified by grepping every `Intl.DateTimeFormat`/`toLocaleString` call in `src/frontend/src` today (not from the 2026-08-06 citation, which predates three stories' worth of edits). 8 files carry real display-bug call sites (10 individual `Intl.DateTimeFormat`/`toLocaleString` expressions across them — `useTimezone.js` and `BookingRequestPage.vue` each need two edits, see the table below), none of which is `SessionPackDashboardPage.vue` (the deferred item's own example — it already uses `toLocaleString(undefined, …)`, i.e. it is *not* broken). See the AC1 table below for the corrected list, and the "Do NOT touch" table for sites that look identical but must not change. |
| AC2 | `deferred-18` D6 — `ApiAdvice` can never resolve a non-English bundle | **CONFIRMED by reading the whole chain end to end.** The frontend already sends the right thing (`axios.js:94` sets `Accept-Language: <i18n locale>`, e.g. `de`); Spring's `CookieLocaleResolver` (`MvcConfig.java:19-24`) has no cookie ever written to it (`SecurityConstants.LOCALE_COOKIE = "lang"`, grepped, never set anywhere), so it silently falls back to `request.getLocale()` — the servlet container's own correct parse of that header. The single break is `SecurityAdviceFilter.java:59`: `locale.getDisplayLanguage()` turns a correctly-resolved `Locale` into its **English display name** ("German", "French"), which `Locale.forLanguageTag(...)` then fails to parse back into anything but a garbage locale, at all 4 read sites (`ApiAdvice.java:434,461` / `:498` / `:620,626`, `VideoApiAdvice.java:155,158`). One-line fix at the write site closes all 4 read sites at once. |
| AC3 | `deferred-9` D2 (re-scoped by `uat-1` AC9) | **CONFIRMED, structural only — the translation-parity half is already closed.** All four bundles (`en`, `en-US`, `de`, `fr-FR`) carry the same key set today. What's left: `de` is fully translated but unreachable (`MainLayout.vue:236-239`'s `languages` array offers only `en-US`/`fr-FR`), `de` isn't renamed `de-DE` like its two siblings, and the standalone `en` bundle is dead weight (an abandoned American-vs-British experiment, never selectable, one line different from `en-US`). Verified `en` has zero importers outside `i18n/index.js` — safe to delete outright. |
| AC4 | Ledger hygiene | Close `deferred-17` D3, `deferred-18` D6, `deferred-9` D2 in `deferred-work.md`; update the priorities doc's Story claims / Still unclaimed / Suggested sequence. |

## Acceptance Criteria

### AC1 — Display-only date/time formatting follows the active vue-i18n locale

**The bug is narrower than `deferred-17` D3's own file list.** Its citation of
`SessionPackDashboardPage.vue:175-182` is wrong today — that function already calls
`toLocaleString(undefined, …)`, which correctly defers to the browser locale. Do not "fix" it; there is
nothing to fix there. The real, currently-verified list, split into what changes and what must not:

**Fix — display-only, currently hardcoded to a fixed locale:**

| File | Function | Current | Change |
|---|---|---|---|
| `composables/useTimezone.js:5,13` | `formatInPitchTimezone`, `formatInBrowserTimezone` | `new Intl.DateTimeFormat('en', {...})` | active `locale` |
| `components/availability/WeeklyCalendar.vue:98` | `weekDays` day label (e.g. "Aug 12") | `new Intl.DateTimeFormat('en', {month:'short', day:'numeric', ...})` | active `locale` |
| `pages/coach/CoachBookingRequestsPage.vue:148` | `formatDateTime` | `new Date(isoString).toLocaleString('en', {...})` | active `locale` |
| `pages/coach/CoachCommandCenterPage.vue:310` | `slotLabel` | `new Intl.DateTimeFormat('en', {weekday:'short', hour:'2-digit', minute:'2-digit', ...})` | active `locale` |
| `pages/coach/AvailabilityManagerPage.vue:182` | `weekLabel` ("Aug 12 – Aug 18") | `new Intl.DateTimeFormat('en', {month:'short', day:'numeric'})` | active `locale` |
| `pages/parent/ParentPlayerPortalPage.vue:92` | `formatInTz` | `new Intl.DateTimeFormat('en', {...})` | active `locale` |
| `pages/parent/ParentBookingsPage.vue:221` | `formatDateTime` | `new Intl.DateTimeFormat('en', {...})` | active `locale` |
| `pages/parent/BookingRequestPage.vue:283,293` | `formatInZone` (the original `deferred-17` D3 citation, function renamed and drifted since) | both branches use `new Intl.DateTimeFormat('en', {...})` | active `locale` |

**`useI18n` import status differs per file — verify before editing, do not assume the pattern is uniform:**

- `CoachBookingRequestsPage.vue`, `CoachCommandCenterPage.vue`, `AvailabilityManagerPage.vue`,
  `ParentPlayerPortalPage.vue`, `ParentBookingsPage.vue`, `BookingRequestPage.vue` **already** import
  `useI18n` and destructure `const { t } = useI18n()` — just add `locale` to the existing destructure
  (`const { t, locale } = useI18n()`).
- `composables/useTimezone.js` is a plain JS function (no existing `useI18n` import), called from within
  component `setup()` scope — add `import { useI18n } from 'vue-i18n'` + `const { locale } = useI18n()`
  inside the function body, same as any other composable in this codebase.
- **`components/availability/WeeklyCalendar.vue` has NO `useI18n()` call at all** — its one translated
  string (`$t('booking.availability.blocked')` in the template, line 59) resolves through Quasar's
  `globalInjection`, not a script-level `t`. Add a **fresh** `import { useI18n } from 'vue-i18n'` +
  `const { locale } = useI18n()` — destructure **`locale` only, not `t`**. Copying the `const { t, locale }`
  pattern from the other files here would leave `t` unused and trip the Task 5 ESLint gate
  (`no-unused-vars`), since the template already uses global `$t`.

Pass `locale.value` as the first `Intl.DateTimeFormat`/`toLocaleString` argument at every fix-table site.

**Do NOT touch — same-looking call, different purpose (locale-invariant on purpose):**

| File | Call | Why it must stay fixed |
|---|---|---|
| `components/availability/WeeklyCalendar.vue:122,126` | `Intl.DateTimeFormat('en-CA', {...})` in `blocksForDay` | `en-CA` is a deliberate trick for `YYYY-MM-DD` output, used to string-compare against `dayDate`. Localizing it breaks the day-bucketing entirely. |
| `components/availability/WeeklyCalendar.vue:170` | `Intl.DateTimeFormat('en', {hour/minute: 'numeric'})` in `getBlockStyle`, fed through `Number(...)` | Extracts a numeric hour/minute for CSS positioning math. A locale using non-Latin digits (e.g. Arabic-indic) would make `Number()` return `NaN`. Hardcoded on purpose. |
| `pages/coach/AvailabilityManagerPage.vue:272` | `Intl.DateTimeFormat('en-CA', {...})` in `localDateTimeToUtc` | Same `en-CA` offset-calculation trick, used to convert a coach's wall-clock input to UTC. Not display. |
| `pages/parent/BookingRequestPage.vue:327` | `Intl.DateTimeFormat('en-US', {...})` in `zoneOffsetMs` | `formatToParts` numeric offset calculation feeding week-boundary math (the same instant-anchored week-bounds logic `uat-2` AC5 added). Not display. |
| `pages/coach/CoachCommandCenterPage.vue:267` | `Intl.DateTimeFormat('en', {weekday:'long'})` in `getDayIndex` | Result is matched against a **hardcoded English weekday array** (`['Monday','Tuesday',...]`) via `.indexOf(day)`. Localizing the formatter without also rewriting the matching array would make every non-English user's schedule silently group into the wrong day column. Out of scope — flag as a new deferred item (AC4), do not fix here. |

**Explicitly out of scope, record as new deferred items (AC4), do not fix:**
- `composables/useTimezone.js` has **zero callers anywhere in `src/frontend/src`** (grepped). It is dead
  code. Fixing its locale bug is harmless but pointless — record it as unused rather than spend a task
  wiring it up or deleting it (deleting unrelated dead code is its own diff with its own risk).
- Hardcoded English UI text not run through vue-i18n at all (`WeeklyCalendar.vue`'s `dayNames` array,
  `AvailabilityManagerPage.vue`'s `dayOptions` labels, `CoachCommandCenterPage.vue`'s `dayLabel` array) is
  a **different class of bug** — missing translation keys, not a locale-formatting defect — and is
  systemic well beyond this story's file list. Do not expand AC1 to cover it.
- Every `toLocaleDateString()`/`toLocaleString()` call already using `undefined` or no locale argument
  (`SessionPackDashboardPage.vue`, `CreditStatementPage.vue`, `ReceiptView.vue`, `RevenueDashboardPage.vue`,
  `ParentReceiptView.vue`, `SessionTemplateVault.vue`, `PlayerSubscriptionPage.vue`,
  `CoachSubscriptionPage.vue`, `CoachReliabilityPage.vue`, `ParentApprovalPage.vue`,
  `SkillsRadarChart.vue`) already defers to the browser's own locale — correct, working behaviour, not
  part of this defect. Leave alone. (`TenantListPage.vue`/`TenantDetailPage.vue` are dead routes per
  `uat-1` D4 — the tenant module was removed — doubly not worth touching.)

### AC2 — Backend resolves a non-English `Accept-Language` to the real bundle

**Root cause, verified end to end, not assumed:**

1. `src/frontend/src/boot/axios.js:94` already sets `config.headers['Accept-Language'] = getCurrentLocale()`
   on every request — `getCurrentLocale()` (`boot/i18n.js:22-24`) returns the exact vue-i18n locale value
   (`'de'`, `'en-US'`, `'fr-FR'`).
2. `MvcConfig.java:19-24` wires `CookieLocaleResolver` reading cookie `lang`
   (`SecurityConstants.LOCALE_COOKIE`). **Nothing in `src/main` ever sets this cookie** (grepped) — so
   `resolveLocale` always falls through to its built-in default, `request.getLocale()`, which is the
   servlet container's own standards-correct parse of the `Accept-Language` header. This part already
   works correctly and needs no change.
3. `SecurityAdviceFilter.java:59` — **the actual bug**:
   ```java
   RequestMetadataProvider.setChosenLang(locale.getDisplayLanguage());
   ```
   `getDisplayLanguage()` returns the language's **English name** ("German", "French", "English"), not a
   language tag. Change to:
   ```java
   RequestMetadataProvider.setChosenLang(locale.toLanguageTag());
   ```
4. `RequestMetadata.java:39` — `private String chosenLang = "English";` is the same bug as a default
   value (would fail `Locale.forLanguageTag` identically before any request sets it). Change the default
   to `"en-US"`, matching the frontend's own default locale (`boot/i18n.js:7`).
5. **No change needed at the 4 read sites** — `ApiAdvice.java:434(+461)`, `:498`, `:620(+626)`, and
   `VideoApiAdvice.java:155(+158)` already call `Locale.forLanguageTag(chosenLang)` correctly; they were
   only ever fed a broken input. Do not touch them.
6. **No change needed to the message bundle files.** `src/main/resources/i18n/messages_de.properties`
   and `messages_fr.properties` already exist and are keyed on the bare language (`de`, `fr`), and
   Spring's `ReloadableResourceBundleMessageSource` resolution already falls back from a full tag
   (`de-DE`) to the bare language (`de`) within one `getMessage()` call — confirmed by reading
   `MvcConfig.java:26-33`'s bundle wiring, no code change required there.

**Test to add** — an end-to-end IT proving the full chain, not just the isolated `ApiAdvice` unit. Use
`CoachProfileBuilderIT`'s existing `saveStep1_invalidTimezone_returns400WithResolvedMessage`
(`CoachProfileBuilderIT.java:192-218`) as the pattern (same `authenticatedHeaders(cookies)` helper at
`:809-813`, same endpoint, same `"Not/AZone"` invalid-timezone payload, same
`validation.timezone.invalid` key). Add a sibling test that also sets
`headers.add(HttpHeaders.ACCEPT_LANGUAGE, "de")` and asserts the response body contains the German
sentence from `messages_de.properties:61` — `"Die Zeitzone muss eine bekannte Zeitzonenkennung sein"` —
**not** the English one. This is the assertion the deferred-18 review recorded as impossible to write
before this fix ("no request shape reaches the German bundle"); its passing is the proof this AC works.

**Mutation-check required (Task, see below):** revert the `SecurityAdviceFilter.java:59` change alone and
confirm the new test fails — it must not be possible to pass by accident (e.g. by the fallback bundle
also containing similar English words).

### AC3 — Locale bundle selector: make `de` reachable, rename it, delete the dead `en` bundle

Three small, coupled changes — do them together, they touch the same 4 files:

1. **Rename the `de` bundle folder to `de-DE`** (`src/frontend/src/i18n/de/` → `src/frontend/src/i18n/de-DE/`,
   contents unchanged) for consistency with its siblings (`en-US`, `fr-FR` are both country-qualified;
   `de` alone was not).
2. **`src/frontend/src/i18n/index.js`** — update the import and export key: `de-DE` instead of `de`.
   Delete the `en` import and export entirely (`import en from './en'` and the `en,` export line) —
   confirmed zero importers of the standalone `en` bundle outside this file, so this is a clean delete,
   not a content merge. Delete the now-orphaned `src/frontend/src/i18n/en/` directory.
3. **`src/frontend/src/boot/i18n.js`** — update `fallbackLocale`:
   ```js
   fallbackLocale: {
     'de-DE': ['en-US'],
     default: ['en-US'],
   },
   ```
   (drops the now-deleted `'en'` key and the `'en': ['en-US']` entry it needed).
4. **`src/frontend/src/layouts/MainLayout.vue:236-239`** — add German to the switcher:
   ```js
   const languages = [
     { label: 'English', value: 'en-US' },
     { label: 'Français', value: 'fr-FR' },
     { label: 'Deutsch', value: 'de-DE' },
   ];
   ```
   `currentLanguageLabel`, `changeLanguage`, `loadLanguagePreference` (`:241-256`) all key off this array
   already and need no further changes — German becomes selectable and persists via the same
   `localStorage` round-trip the other two languages use.

**Note the interaction with AC2:** after this AC, a German-selecting user's browser sends
`Accept-Language: de-DE` (not `de`) — confirm AC2's fix handles this too (it does: `Locale.forLanguageTag("de-DE")`
resolves to language `de`, and Spring's bundle fallback from `de-DE` → `de` picks up
`messages_de.properties` the same as a bare `de` tag would).

**No migration needed for a stale `localStorage` value.** A returning browser with an old saved `'de'`
preference simply fails `languages.some(l => l.value === savedLocale)` in `loadLanguagePreference` and
falls back to the `en-US` default — graceful, and `de` was never selectable before this story anyway, so
no user has ever actually had it saved.

### AC4 — Ledger and priorities hygiene

**`uat-readiness-priorities.md` was already updated during story creation — do not repeat this half.**
Story creation for this file directly edited `uat-readiness-priorities.md` to add the Story-claims row,
strike "P1 #7, #8 (the i18n pair)" from "Still unclaimed," and strike item 9 in "Suggested sequence."
**Diff that file against its state before this story existed before touching it** (`git log -p -- 
_bmad-output/implementation-artifacts/uat-readiness-priorities.md` or `git diff` if still uncommitted) —
if those three edits are already present, the only remaining work is `deferred-work.md`, below. Applying
them again would duplicate the Story-claims row or re-strike already-struck text.

**`_bmad-output/implementation-artifacts/deferred-work.md` — confirmed NOT yet edited, this is real,
outstanding work.** Dated one-line closure notes (the `deferred-13`/`-14`/`-16`/`uat-1`/`uat-2`/`uat-3`
convention), not deletions:

| Item | Record |
|---|---|
| `deferred-17` D3 (`formatSlot` hardcodes `'en'`) | **Closed** by AC1. Note the corrected file list (`SessionPackDashboardPage.vue` was never actually affected) and the "Do NOT touch" table of locale-invariant computational call sites this story deliberately left alone. |
| `deferred-18` D6 (`ApiAdvice` can never resolve a non-English bundle) | **Closed** by AC2. Root cause was a single `getDisplayLanguage()` call, not a bundle or message-source problem. |
| `deferred-9` D2 (re-scoped by `uat-1` to structural residue) | **Closed** by AC3 — `de` renamed `de-DE` and made selectable, redundant `en` bundle deleted. |

**New items to record** (under a `## Deferred from: skillars-uat-4-...` heading):
- `composables/useTimezone.js` has zero callers — dead code, left in place (AC1).
- Hardcoded English day-name/weekday arrays used as **display labels**, not just formatter locale codes
  (`WeeklyCalendar.vue` `dayNames`, `AvailabilityManagerPage.vue` `dayOptions`, `CoachCommandCenterPage.vue`
  `dayLabel`) — a distinct "missing translation keys" problem, systemic beyond this story's scope, not
  fixed here (AC1).
- `CoachCommandCenterPage.vue:267`'s `getDayIndex` matches an `Intl.DateTimeFormat('en', {weekday:'long'})`
  result against a hardcoded English array via `.indexOf` — fragile (would silently misbucket if ever
  localized without also rewriting the array), currently correct because it stays hardcoded, flagged for
  whoever eventually tackles the item above (AC1).

## Tasks / Subtasks

- [x] **Task 0 — Confirm the AC1 file list before editing (AC: 1)**
  - [x] Re-grep `Intl.DateTimeFormat\|toLocaleString\|toLocaleDateString` across `src/frontend/src` and
        diff against the two tables in AC1 (fix list vs. do-not-touch list) — confirm no drift since this
        story was written before touching any file.
- [x] **Task 1 — Frontend display formatting (AC: 1)**
  - [x] Fix the 8 files / 10 call sites in the AC1 "Fix" table: add `locale` to each file's existing
        `useI18n()` destructure, except `useTimezone.js` (fresh `useI18n` import + call) and
        `WeeklyCalendar.vue` (fresh `useI18n` import, but destructure `locale` only — no existing `t` to
        add it to, and adding an unused `t` fails ESLint). Pass `locale.value` as the format locale.
  - [x] Leave every site in the "Do NOT touch" table untouched — verify by re-reading each after your
        edits, not just by memory of the list.
  - [x] Record the two new deferred items (dead `useTimezone.js`, hardcoded day-name arrays) — do not fix
        either.
- [x] **Task 2 — Backend locale resolution (AC: 2)**
  - [x] `SecurityAdviceFilter.java:59`: `locale.getDisplayLanguage()` → `locale.toLanguageTag()`.
  - [x] `RequestMetadata.java:39`: default `chosenLang` `"English"` → `"en-US"`.
  - [x] Add the German-resolved-message IT sibling to `CoachProfileBuilderIT` as specified in AC2.
  - [x] **Mutation-check**: revert the `SecurityAdviceFilter` change alone, confirm the new test fails.
        Record the failure message in Completion Notes.
- [x] **Task 3 — Locale bundle selector (AC: 3)**
  - [x] Rename `src/frontend/src/i18n/de/` → `src/frontend/src/i18n/de-DE/`.
  - [x] `i18n/index.js`: swap `de` → `de-DE` key, delete the `en` import/export, delete
        `src/frontend/src/i18n/en/`.
  - [x] `boot/i18n.js`: update `fallbackLocale` map per AC3.
  - [x] `MainLayout.vue:236-239`: add the German `languages` entry.
- [x] **Task 4 — Ledger and priorities (AC: 4)**
  - [x] Diff `uat-readiness-priorities.md` first — the Story-claims row, "Still unclaimed" update and
        sequence strike-through were already applied during story creation. Skip re-applying them; only
        fix if the diff shows they are missing or wrong.
  - [x] Three closures + three new deferred items in `deferred-work.md` (confirmed not yet done).
- [x] **Task 5 — Verify**
  - [x] `mvn -o verify`, `0F/0E`. This story adds exactly one new IT method and touches no other Java
        test — a full-suite failure means something outside this story's files broke, not this story's
        own logic.
  - [x] `npx eslint` clean on all touched `.vue`/`.js` files.
  - [x] `quasar build` succeeds (no build-time reference to the deleted `en` bundle or the renamed `de`
        directory anywhere — grep for `from '\.\./en'`/`i18n/en'`/`i18n/de'` after the rename/delete to
        confirm no dangling import).
  - [x] No live browser run exists in this pipeline (per `uat-1`/`uat-2`/`uat-3` precedent) — the German
        language switcher, the renamed bundle, and all 8 AC1 display sites are verified by code reading
        and the build/test results only. Record this explicitly in Completion Notes as still needing a
        human or browser-tooled spot-check, matching the pattern of the prior three stories.

### Review Findings

_Code review 2026-08-12 (bmad-code-review, 3-layer: Blind Hunter + Edge Case Hunter + Acceptance Auditor).
13 unified findings: 1 decision-needed, 3 patch, 2 deferred, 7 dismissed as noise (verified false positives
or matching established codebase convention). Edge Case Hunter returned zero findings._

- [x] [Review][Decision→Defer] `de-DE` bundle carries 55 unreviewed "TODO: native review" translations vs.
      0 in the already-shipped `fr-FR` bundle, and AC3 makes it reachable/selectable for the first time
      [`src/frontend/src/i18n/de-DE/index.js`] — **Resolved by Mbah (2026-08-12): ship as-is.**
      Machine-translated German (55/1209 lines, ~4.5%) is acceptable to ship now — some German is better
      than none, and this story's job was reachability/correctness, not translation-quality review. Native
      review to follow as a separate, later pass; not blocking this story. Deferred, tracked in
      `deferred-work.md`.

- [x] [Review][Patch] `getDayIndex` has no in-code guardrail against localizing it in isolation
      [`src/frontend/src/pages/coach/CoachCommandCenterPage.vue:266-272`] — **Fixed:** added a comment
      directly above `getDayIndex` explaining the hardcoded `'en'` is deliberate and paired with the
      hardcoded English weekday array it's matched against via `.indexOf`.
- [x] [Review][Patch] Stale `chosenLang` field comment ("Language set in the UI by user") no longer
      matches reality — value is now server-resolved from the `Accept-Language` header via
      `SecurityAdviceFilter`, not read from a UI payload
      [`src/main/java/com/softropic/skillars/infrastructure/security/RequestMetadata.java:38`] —
      **Fixed:** comment now reads "Resolved server-side from the request's Accept-Language header (see
      SecurityAdviceFilter)".
- [x] [Review][Patch] `sprint-status.yaml` was modified (status → `review`, new AC-mapping comment block)
      but is not listed in the Dev Agent Record's File List
      [`_bmad-output/implementation-artifacts/sprint-status.yaml`] — **Fixed:** added to the Dev Agent
      Record's File List under Docs / ledger.

- [x] [Review][Defer] `useTimezone.js`'s `useI18n()` call happens inside a plain exported function, not
      guaranteed `setup()` scope — currently inert since the composable has zero callers
      [`src/frontend/src/composables/useTimezone.js:3-4`] — deferred, pre-existing (already recorded as
      `deferred-work.md` D1 under this story; dead code, harmless until wired up)
- [x] [Review][Defer] Zero automated test coverage for any of the 10 changed
      `Intl.DateTimeFormat`/`toLocaleString` locale-formatting call sites — deferred, pre-existing
      (systemic: no frontend test framework exists anywhere in this repo, explicitly out of scope per
      `uat-1`/`uat-2`/`uat-3` precedent) [8 touched `.vue`/`.js` files under AC1]

**Dismissed (7, verified false positives or matching established convention, not repeated here):** no
evidence frontend sends a locale-matching `Accept-Language` header (verified: `axios.js:94` already does
this, pre-existing and unrelated to this diff); the 4 unseen `ApiAdvice`/`VideoApiAdvice` read sites
(verified via grep: all correctly call `Locale.forLanguageTag(chosenLang)`, unchanged); `RequestMetadata`'s
new default risking other consumers (verified via grep: no other consumers exist besides the same
`forLanguageTag` sites and a log-only `toString`); the new IT test only covering German, not French
(deliberate — proves the language-agnostic mechanism once, no pre-existing French test either); the new IT
test's hardcoded literal message string and its defensive cast pattern (both verified to exactly match the
pre-existing sibling test `saveStep1_invalidTimezone_returns400WithResolvedMessage` in the same file); and
`fallbackLocale` dropping the bare `'de'` key (verified: `locale.value` is only ever set from the
`languages` array or validated `localStorage`, `'de'` alone is unreachable).

## Dev Notes

### Baseline

`HEAD` is `6d1180c` (Story UAT.3, merged). `src/test/java` holds 139 `*IT.java` sources, 4 of them
abstract bases (`BaseVideoIT`, `BasePaymentIT`, `BaseSessionIT`, `BaseStorageIT` — `AbstractIntegrationTest.java`
does **not** match the `*IT.java` glob, it ends `...IntegrationTest.java`), so ~135 concrete IT classes
are expected before this story adds one more test method (not a new class) to `CoachProfileBuilderIT`.

**Do not quote a test-count delta by subtracting totals from a CI run** — `uat-1`, `uat-2` and `uat-3` all
recorded this producing wrong numbers (stale report files, another commit's deletions attributed to this
one). This story is small enough that the safer check is simpler: confirm `CoachProfileBuilderIT`'s
`@Test` count grew by exactly 1, and that no other `*IT.java`/`*Test.java` file changed its count at all.

### Project Structure Notes

- No backend migration, no new REST endpoint, no schema change — this story is a pure bug-fix pass over
  existing files. No `platform_config`/Flyway version conflict risk with any parallel story.
- Frontend changes are confined to `Intl`/`toLocaleString` call sites (AC1) and the i18n bundle wiring
  (AC3) — no store, no API contract, no route changes.
- Backend changes are confined to two single-line edits (`SecurityAdviceFilter`, `RequestMetadata`) plus
  one new IT method — no service, repository, or controller logic changes.

### References

- [Source: `_bmad-output/implementation-artifacts/uat-readiness-priorities.md` — P1 #7, #8, and the
  `deferred-9` D2 ledger-hygiene row]
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md` — `deferred-17` D3, `deferred-18` D6,
  `deferred-9` D2 (re-scoped by `skillars-uat-1`)]
- [Source: `src/frontend/src/boot/axios.js:94`, `src/frontend/src/boot/i18n.js`,
  `src/frontend/src/layouts/MainLayout.vue:225-256` — frontend locale plumbing, read in full for this story]
- [Source: `src/main/java/com/softropic/skillars/platform/security/infrastructure/filter/SecurityAdviceFilter.java`,
  `src/main/java/com/softropic/skillars/infrastructure/security/RequestMetadata.java`,
  `src/main/java/com/softropic/skillars/platform/security/config/MvcConfig.java` — backend locale
  resolution chain, read in full for this story]
- [Source: `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java:192-218,809-813`
  — existing test pattern to extend for AC2]

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

- Task 0 re-grep of `Intl.DateTimeFormat\|toLocaleString\|toLocaleDateString` across `src/frontend/src`
  matched the AC1 fix table (8 files / 10 call sites) and do-not-touch table (5 sites) exactly — no drift
  since story creation.
- Mutation-check (Task 2): reverted `SecurityAdviceFilter.java:59` alone (`locale.toLanguageTag()` →
  `locale.getDisplayLanguage()`) and re-ran `saveStep1_invalidTimezone_germanAcceptLanguage_returns400WithGermanResolvedMessage`.
  It failed as required:
  ```
  Expecting actual:
    "...\"errorMsg\":{\"errorKey\":\"validation.timezone.invalid\",\"message\":\"Timezone must be a recognized timezone identifier, for example Europe/Berlin\"}..."
  to contain:
    "Die Zeitzone muss eine bekannte Zeitzonenkennung sein"
  ```
  Restored the fix and re-ran the full `CoachProfileBuilderIT` class green (31/31). Note: the first
  restore attempt appeared to still fail because `mv` on the `sed -i.bak` backup preserved the original
  file's older mtime, so Maven's timestamp-based staleness check skipped recompilation — `touch`ing the
  file before the next `mvn -o test` run resolved it. Not a code defect; recorded so a future dev doesn't
  misread a stale-`.class` artifact as a regression.
- Full `mvn -o verify`: **BUILD SUCCESS**, 860 unit tests + 876 IT tests, 0 failures, 0 errors (1 + 4
  skipped respectively — pre-existing, unrelated to this story). Verified via `git diff --stat -- 'src/test/**/*.java'`
  that `CoachProfileBuilderIT.java` is the only test file this story touched (26 lines added, one new
  `@Test` method) — per Dev Notes guidance, not by subtracting CI totals.
- `npx eslint src/` (frontend, full tree): clean, zero errors/warnings.
- `npx quasar build`: **Build succeeded**. Confirmed `de-DE`/`Deutsch` present in the built bundle and
  zero references to `i18n/en'` in `dist/spa/assets/*.js`.

### Completion Notes List

- All 4 ACs implemented exactly as specified — no scope drift found during Task 0's re-verification pass.
- AC1: 8 files / 10 `Intl.DateTimeFormat`/`toLocaleString` call sites switched from hardcoded `'en'` to
  the active vue-i18n `locale.value`. All 5 "Do NOT touch" sites (two `en-CA` offset tricks, one
  `Number()`-parsing numeric extraction, one `en-US` week-boundary offset calc, one English-weekday-array
  match) re-verified untouched after edits. Two new deferred items recorded (dead `useTimezone.js`,
  hardcoded day-name arrays) plus the existing `CoachCommandCenterPage.vue:267` flag, none fixed per
  scope.
- AC2: Root cause was a single line (`SecurityAdviceFilter.java:59`'s `locale.getDisplayLanguage()`)
  feeding `Locale.forLanguageTag()` an English display name instead of a language tag. Fixed at the one
  write site plus the matching `RequestMetadata` default; all 4 downstream read sites needed no change.
  Proved end-to-end (not just at the `ApiAdvice` unit) with a new `CoachProfileBuilderIT` test sending
  `Accept-Language: de` and asserting the German sentence from `messages_de.properties`. Mutation-checked
  per Task 2 — see Debug Log.
- AC3: `de` bundle renamed to `de-DE`, made selectable in `MainLayout.vue`, and the dead `en` bundle
  (zero importers, confirmed by grep) deleted outright. `fallbackLocale` updated accordingly. No
  migration needed for a stale `localStorage` value — `de` was never selectable before this story, so no
  user could have it saved.
- AC4: `uat-readiness-priorities.md`'s three edits (Story-claims row, "Still unclaimed" strike, sequence
  strike-through) were confirmed already present from story creation — not re-applied, avoiding
  duplication. `deferred-work.md` updated with three dated closure notes (`deferred-17` D3, `deferred-18`
  D6, `deferred-9` D2) and a new `## Deferred from: skillars-uat-4-...` section recording 3 new items
  (dead `useTimezone.js`, hardcoded day-name arrays, `CoachCommandCenterPage.vue` day-index fragility).
- **Review follow-ups resolved (2026-08-12):** 3 of 3 `[Review][Patch]` items fixed —
  ✅ `getDayIndex` (`CoachCommandCenterPage.vue:266-272`) now carries an in-code comment explaining the
  deliberate hardcoded `'en'` pairing with the hardcoded English weekday array;
  ✅ `RequestMetadata.chosenLang`'s stale field comment (`:38`) updated to describe the current
  server-side `Accept-Language` resolution instead of the pre-AC2 UI-payload behavior;
  ✅ `sprint-status.yaml` added to the Dev Agent Record File List under Docs / ledger. Both code patches
  are comment-only — re-verified with `npx eslint` (clean) and `mvn -o compile` (exit 0) rather than a
  full re-run of the suite, since no executable logic changed. The 1 `[Review][Decision→Defer]` item
  (55 machine-translated strings in `de-DE`) was resolved by Mbah as ship-as-is and is tracked as
  `deferred-work.md` D3 under this story's new section. The 2 `[Review][Defer]` items were pre-existing
  and already recorded (`deferred-work.md` D1/D2 under this story).
- **Still needs a human or browser-tooled spot-check** (per `uat-1`/`uat-2`/`uat-3` precedent — no live
  browser run exists in this pipeline): the German language switcher's actual UI behavior, the 8 AC1
  display sites rendering correctly in German/French, and the German validation-error toast end-to-end in
  a real browser. Everything above is verified by code reading, `mvn -o verify`, ESLint, and a successful
  `quasar build` only.

### File List

**Frontend:**
- `src/frontend/src/composables/useTimezone.js` (modified — AC1)
- `src/frontend/src/components/availability/WeeklyCalendar.vue` (modified — AC1)
- `src/frontend/src/pages/coach/CoachBookingRequestsPage.vue` (modified — AC1)
- `src/frontend/src/pages/coach/CoachCommandCenterPage.vue` (modified — AC1)
- `src/frontend/src/pages/coach/AvailabilityManagerPage.vue` (modified — AC1)
- `src/frontend/src/pages/parent/ParentPlayerPortalPage.vue` (modified — AC1)
- `src/frontend/src/pages/parent/ParentBookingsPage.vue` (modified — AC1)
- `src/frontend/src/pages/parent/BookingRequestPage.vue` (modified — AC1)
- `src/frontend/src/i18n/de/index.js` → `src/frontend/src/i18n/de-DE/index.js` (renamed — AC3)
- `src/frontend/src/i18n/en/index.js` (deleted — AC3)
- `src/frontend/src/i18n/index.js` (modified — AC3)
- `src/frontend/src/boot/i18n.js` (modified — AC3)
- `src/frontend/src/layouts/MainLayout.vue` (modified — AC3)

**Backend:**
- `src/main/java/com/softropic/skillars/platform/security/infrastructure/filter/SecurityAdviceFilter.java` (modified — AC2)
- `src/main/java/com/softropic/skillars/infrastructure/security/RequestMetadata.java` (modified — AC2)
- `src/test/java/com/softropic/skillars/platform/marketplace/api/CoachProfileBuilderIT.java` (modified — AC2, +1 test method)

**Docs / ledger:**
- `_bmad-output/implementation-artifacts/deferred-work.md` (modified — AC4)
- `_bmad-output/implementation-artifacts/uat-readiness-priorities.md` (already modified during story creation — AC4, no further change)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified — status transitions `ready-for-dev` → `in-progress` → `review` and matching `last_updated` comment; omitted from the original File List, added per review finding)
