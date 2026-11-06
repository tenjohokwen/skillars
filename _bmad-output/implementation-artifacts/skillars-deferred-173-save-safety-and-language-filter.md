# Story skillars-deferred-173: Saves that lose data, and the coach language filter

Status: done

<!-- CREATED 2026-11-06 (/bmad-create-story) from ledger items RS10, RS11 and RS04, split out of
     skillars-deferred-172 by owner choice. Citations read at origin/master 6a19e395. skillars-deferred-172
     (PR #466) is not merged on this base: its sprint-status / deferred-work edits will conflict textually
     (sprint-status last_updated block, the RS03 ledger line) and are resolved at rebase. Owner chose option 1
     on D1-D4 on 2026-11-06. -->

## Story

As **a family or coach using messaging**, I want reporting a conversation to stay in effect, so that a report
cannot be silently undone by the other party reading or sending at the same moment.

As **an admin**, I want editing an alert rule to never lose the rule, so that a bad edit leaves the old rule
in place.

As **a parent searching for a coach**, I want the language filter to find coaches who speak that language,
so that filtering by "English" does not return nobody.

## Ledger mapping (so future passes do not re-pick this)

| Ledger id | Severity | What | Eval |
|---|---|---|---|
| RS10 | high / concurrency | Full-row `Conversation` save undoes a concurrent BLOCKED | expected finding F6 on merged case `386-messaging-contact-detail-hardening` (no case edit needed) |
| RS11 | medium / database | `AlertRuleAdminResource.update` deletes, then saves in a separate transaction | expected finding on merged case `398-ltd-admin-full-admin-rights` (no case edit needed) |
| RS04 | medium / logic | `findIdsByLanguage` lowercases only the input | `_drafts/pending-language-filter-case.yaml` → promote to `cases/language-filter-case.yaml` with `fix_pr: <this PR>` |

All three lines are deleted from `deferred-work.md` in this PR (the file's "deleted outright once implemented"
rule), and the "RS10 + RS11 … and RS03 + RS04 … stay queued" sentence in its header paragraph is updated.

## Background (code at 6a19e395)

### RS10: Conversation lost update

- `messaging/repo/Conversation.java`: extends `BaseEntity` (id only, no `@Version`), no `@DynamicUpdate`.
  Columns: `coach_id`, `player_id`, `parent_id`, `status`, `created_at`, `last_message_at`,
  `coach_last_read_at`, `parent_last_read_at`, `player_last_read_at`. Every `save()` of a managed row is a
  static UPDATE of all of them.
- Writers of an existing row (all `findById` then `save`, no lock):
  - `MessagingService.getMessages` (class-level `@Transactional`, ~line 339): sets the caller role's
    last-read column via `updateLastRead`, saves at ~line 356.
  - `MessagingService.sendMessage` (`NOT_SUPPORTED`; the `transactionTemplate` block at ~line 267): re-reads,
    re-checks BLOCKED, inserts the message, sets `lastMessageAt`, saves at ~line 287.
  - `MessagingReportService.reportConversation` (class `@Transactional`, ~line 92): sets BLOCKED, saves at ~line 105.
  - `AdminConversationService.unblockConversation` (`@Transactional`, ~line 70): sets ACTIVE, saves at ~line 80.
  - `ConversationCreationHelper` only inserts (`saveAndFlush` of a new row), not affected.
- Race: a reader's `getMessages` loads ACTIVE; the other party's `reportConversation` commits BLOCKED; the
  reader's flush writes `status = 'ACTIVE'` back. The report row stays OPEN but the conversation is usable
  again, and `findActive*` lists show it. Mirror races: a stale `getMessages` on a BLOCKED row written back
  after an admin unblock re-blocks it; `reportConversation` clobbers a concurrent `lastMessageAt` / last-read;
  coach and parent reading together overwrite each other's last-read column.
- Precedent: `User` got `@DynamicUpdate` for exactly this (comment at `security/repo/User.java:50-53`: a stale
  save "silently lifting the suspension"). `CoachProfile` instead got `@Version` (V188) with 409 on conflict,
  which fits a form edit, not a GET that marks messages read.

### RS11: AlertRule update

- `notification/api/AlertRuleAdminResource.update` (PUT `/v1/admin/alerts/{id}`): `findById` (404 if absent),
  `deleteById(id)` (commits in its own repository transaction), builds a NEW `AlertRule` (new TSID id) and
  `save`s it in another, then `alertRuleCache.refresh()`. Not `@Transactional`; `@RequestBody` is not `@Valid`
  and `AlertRuleRequest` has no constraints. A PUT with a null `metricName` / `threshold` fails the insert
  (NOT NULL) after the delete committed: the rule is gone. Even on success the rule's id changes, so the
  `PUT /{id}` URL the admin used no longer names it (docs `notification/index.html:505` notes this).
- `AlertRule` (`notification/repo/AlertRule.java`): `@Getter` + `@SuperBuilder`, no setters; columns
  `metric_name varchar(100) NOT NULL`, `threshold numeric(10,4) NOT NULL`, `window_seconds int NOT NULL`,
  `notification_channel varchar(50) NOT NULL`, `enabled boolean NOT NULL`, `description varchar(500)`
  (V138 baseline ~line 480). Not `@Audited`.
- `create` (POST) has the same missing validation; a bad POST just fails, nothing is lost.
- `AlertEvaluationService.computeMetricValue` currently returns -1 for every metric (stub), so no rule fires
  today. Low live impact; the fix is still owed because the endpoint exists.
- `AdminTierWebMvcTest` slices `AlertRuleAdminResource` with `@MockitoBean AlertRuleRepository` and
  `AlertRuleCache`. Its LTD_ADMIN test PUTs body `{"metricName":"m","threshold":0.5,"windowSeconds":60,"enabled":true}`
  and expects 403. `@PreAuthorize` is method security, so it runs AFTER `@Valid` body validation: an invalid
  body from a non-admin would answer 400, not 403. That body is valid under the constraints below, so the
  test keeps passing; do not add a new bean to that slice (the context-cache gate counts contexts).

### RS04: language filter

- `marketplace/repo/CoachProfileRepository.findIdsByLanguage` (~line 73): native
  `... AND lower(city) = lower(:city) AND lower(:lang) = ANY(languages)`.
- `CoachProfile.languages`: `varchar[]` (`ListArrayType`), stored verbatim from
  `ProfileBuilderStep1Request.languages` by `CoachProfileService.saveStep1` (~line 210).
- Frontend: `ProfileBuilderStep1.vue:85` and `EditCoachIdentityDialog.vue:157` offer
  `['English','German','French','Spanish','Arabic','Portuguese']`; `MarketplacePage.vue:182` (the search
  filter) offers `['German','English','Turkish','Arabic']`. So every stored value is capitalised, the search
  sends a capitalised value, `lower('English') = 'english'` never equals `'English'`, and any language search
  returns no coach (`CoachSearchService` ~line 57 returns empty on an empty id list).
- Missed flow: the two lists differ. A coach can pick French, Spanish or Portuguese but no one can search for
  them; search offers Turkish, which no coach can pick.

## Decisions (owner, 2026-11-06: option 1 on each)

- **D1 (RS10) — `@DynamicUpdate` on `Conversation`** (OWNER DECIDED 2026-11-06: option 1). Each save writes only the columns that
  transaction changed: `getMessages` writes one last-read column, `sendMessage` writes `last_message_at`, the
  report and the unblock write `status`. Closes every cross-column race with one annotation and no migration,
  and matches the `User` precedent. Leaves same-column races as last-writer-wins (two tabs of one role reading;
  a report and an admin unblock at the same instant), both acceptable.
  Alternatives: `@Version` + 409 (a GET that marks read would fail whenever the other party sends; needs
  retries); targeted `@Modifying` UPDATEs per writer (most precise, four new queries, and future writers can
  regress by using `save`).
- **D2 (RS11) — update in place + validate** (OWNER DECIDED 2026-11-06: option 1). Load, apply the new values to the same row, save:
  one UPDATE, id kept, nothing deleted. Add `@Valid` and constraints to `AlertRuleRequest` for both POST and
  PUT. Alternative: keep delete-then-save but make it `@Transactional` (no loss, but the id still changes).
- **D3 (RS04) — compare case-insensitively in the query** (OWNER DECIDED 2026-11-06: option 1):
  `EXISTS (SELECT 1 FROM unnest(languages) AS l WHERE lower(l) = lower(:lang))`. Stored values keep their
  display casing; no migration. Alternative: lowercase on save plus a data migration (profiles would then
  show "english").
- **D4 (RS04 missed flow) — one shared language list** (OWNER DECIDED 2026-11-06: option 1): a single frontend constant used by
  the profile builder, the edit dialog and the search filter, = the builder's six plus Turkish (seven).
  Alternatives: the builder's six only (drop Turkish from search); leave the lists alone.

## Acceptance Criteria

1. **AC1 (RS10, per D1)** `Conversation` is `@DynamicUpdate`, with a comment naming the race (mirror the
   `User` comment). A concurrency IT proves, with real interleaved transactions against Postgres:
   a. a transaction that loaded the conversation ACTIVE, then set a last-read column and committed AFTER
      another transaction committed BLOCKED, leaves the row BLOCKED;
   b. a report committed after a concurrent `lastMessageAt` write leaves that `lastMessageAt` in place;
   c. coach and parent last-read writes from overlapping transactions both survive.
   Each sub-case is red with the annotation removed (record the mutation run in Completion Notes).
2. **AC2 (RS10)** Existing messaging behaviour unchanged: `AbuseReportIT`, `ConversationResourceIT`,
   `MessagingServiceTest`, `MessagingAccessControlIT`, `SoftDeleteIT`, `MinorContactDetailsHoldIT`,
   `ParentalOversightResourceIT` and the admin unblock tests stay green.
3. **AC3 (RS11, per D2)** `PUT /v1/admin/alerts/{id}` updates the existing row in place: the response and the
   DB row keep `id`; no `deleteById` remains in the resource. `AlertRule` gains one method that applies the
   request's values (no public setters per field). The cache is refreshed after the save as today.
4. **AC4 (RS11)** `AlertRuleRequest` is validated on POST and PUT (`@Valid`): `metricName` `@NotBlank`
   `@Size(max = 100)`; `threshold` `@NotNull`, `@DecimalMin("0.0")`, `@DecimalMax("1.0")`,
   `@Digits(integer = 6, fraction = 4)`; `windowSeconds` `@Positive`; `notificationChannel` `LOG` or `EMAIL`
   (after the existing null→LOG default); `description` `@Size(max = 500)`. An invalid PUT answers 400 and the
   stored rule is unchanged (asserted against the DB in an IT, not only the slice). A PUT to an unknown id
   still answers 404.
5. **AC5 (RS11)** `AdminTierWebMvcTest` passes unchanged in bean set (no new `@MockitoBean`); the LTD_ADMIN
   PUT still answers 403.
6. **AC6 (RS04, per D3)** `findIdsByLanguage` matches stored languages case-insensitively. IT: a coach
   stored with `['English','German']` is found by `english`, `ENGLISH` and `English`; a coach without the
   language is not; the city and ACTIVE/REDUCED filters still apply. Red against the old query.
7. **AC7 (RS04, per D4)** One shared frontend language list (e.g. `src/frontend/src/constants/languages.js`)
   is used by `ProfileBuilderStep1.vue`, `EditCoachIdentityDialog.vue` and `MarketplacePage.vue`; Vitest
   covers that the search filter offers every language a coach can pick.
8. **AC8 (ledger, eval, docs)**
   - `deferred-work.md`: RS10, RS11, RS04 lines deleted; header paragraph updated.
   - Eval: `_drafts/pending-language-filter-case.yaml` promoted to `cases/language-filter-case.yaml`
     (`id` = filename, `fix_pr` = this PR, `input_rev` kept); validator reports 0 problems. After merge:
     `fix_merge` added, `input_rev` dropped.
   - Docs: `docs/dev-docs/notification/index.html` (PUT row ~207 and the note ~505: no longer
     delete-then-save, id kept, validated), `docs/dev-docs/messaging/index.html` (Conversation row: partial
     updates and why), `docs/dev-docs/marketplace/index.html` (~214 and ~422: case-insensitive match);
     search index rebuilt; `check-docs` OK.

## Tasks / Subtasks

- [x] T1 (AC1, AC2) `@DynamicUpdate` + comment on `Conversation`; new `ConversationLostUpdateIT` with the three
  interleavings (two threads + latches, or sequential `TransactionTemplate`s holding a loaded entity across a
  committed JDBC write); mutation run; rerun the messaging ITs in AC2.
- [x] T2 (AC3) `AlertRule.apply(...)` (or similarly named) and in-place `update`; drop `deleteById`.
- [x] T3 (AC4, AC5) Constraints + `@Valid`; `AlertRuleAdminIT` (HTTP, real DB): valid PUT keeps id and
  changes values; invalid PUT → 400 and row unchanged; unknown id → 404; invalid POST → 400. Run
  `AdminTierWebMvcTest`.
- [x] T4 (AC6) Query change + IT (extend an existing marketplace search IT if one seeds coach profiles,
  else a new `CoachLanguageSearchIT`).
- [x] T5 (AC7) Shared language constant, three components switched to it, Vitest; Prettier + ESLint.
- [x] T6 (AC8) Ledger, eval case promotion + validator, docs + search index + check-docs.
- [x] T7 `/pr-review`, fix, PR (`PR_REVIEW_SKIP=1 gh pr create ...` first in the command; `frontend-tests` label).

## Dev Notes

- Targeted tests only (`-DskipFrontend -Djacoco.skip=true`); no local full `mvn verify`.
- IT writes in a test body must go through `transactionTemplate` (Hikari auto-commit is off).
- `@DynamicUpdate` works off Hibernate's loaded snapshot: `save()` on a managed entity is a no-op merge and
  the flush writes only dirty columns. A detached entity passed to `save()` is merged onto a fresh load, so
  its dirty set is computed against current DB values. Check no Conversation writer relies on saving a
  detached copy (none at 6a19e395).
- `NativeModifyingVersionAuditTest` concerns `@Version` tables only; not touched by D1.
- New worktree needs `src/frontend/.quasar` copied in (done when the worktree was created).

### References

- `_bmad-output/implementation-artifacts/deferred-work.md` RS10, RS11, RS04 lines and header paragraph.
- `evals/review/results/baseline-2026-11-02/real-sample.md` S05, S18, S20.
- `evals/review/README.md` "Open-bug cases".

## Dev Agent Record

### Agent Model Used

Claude Opus 5.5 (claude-opus-5-5)

### Debug Log References

### Completion Notes List

- Ultimate context engine analysis completed - comprehensive developer guide created.
- T1 (AC1, AC2): `Conversation` is `@DynamicUpdate` with a comment naming the race. `ConversationLostUpdateIT`
  (3 cases) loads the row, commits a change to another column in a REQUIRES_NEW transaction, then saves:
  BLOCKED survives a read-mark save, `last_message_at` survives a report, coach and parent read marks both
  survive. Mutation (annotation removed): all 3 red (status ACTIVE written back; the other two columns
  overwritten with null). AbuseReportIT 6, ConversationResourceIT 17, MessagingServiceTest 22 green.
- T2/T3 (AC3-AC5): `AlertRule.apply(...)`; `update` loads, applies, saves (no `deleteById`); `AlertRuleRequest`
  constraints + `@Valid` on POST and PUT. `AlertRuleAdminIT` (HTTP, real DB, 4 tests): edit keeps the id and
  writes every field; four invalid bodies (blank metric, threshold 1.5, channel SMS, window 0) answer 400 and
  leave the row unchanged; unknown id 404; invalid POST 400 and nothing stored. Mutation (old resource): the
  edit and invalid-edit tests red. `AdminTierWebMvcTest` 8 green with an unchanged bean set. Note: ids are
  serialised as strings (TSID), so the IT compares the id as a string.
- T4 (AC6): query uses `EXISTS (SELECT 1 FROM unnest(languages) AS l WHERE lower(l) = lower(:lang))`.
  `CoachMarketplaceResourceIT`: its seed stored `{english}` in lowercase, which is why the existing language
  test never caught the bug; it now stores `{English}` as the picker does, and a new test searches english /
  ENGLISH / English (found) and French (none). Mutation (old query): both language tests red. 19 green.
- T5 (AC7): `src/frontend/src/utils/coachLanguages.js` (`COACH_LANGUAGES`, seven) used by
  `ProfileBuilderStep1.vue`, `EditCoachIdentityDialog.vue`, `MarketplacePage.vue`. `coachLanguagesSpec.js`
  checks the list and scans the three components for the import and for any inline list (red with the old
  MarketplacePage). Full Vitest 1360/1360, ESLint and Prettier clean. The worktree's `node_modules` is a
  symlink to the main checkout's (git-ignored).
- T6 (AC8): ledger lines deleted and header sentence updated; docs updated (notification PUT row and callout,
  messaging Conversation row, marketplace search row, sequence diagram and callout); search index rebuilt;
  check-docs OK on 26 pages. Eval case promoted to `cases/language-filter-case.yaml` with `fix_pr: 469`.
- T7: /pr-review at 955e4011, both arms 0 findings. PR #469.

### File List

- src/main/java/com/softropic/skillars/platform/messaging/repo/Conversation.java
- src/main/java/com/softropic/skillars/platform/notification/repo/AlertRule.java
- src/main/java/com/softropic/skillars/platform/notification/api/AlertRuleAdminResource.java
- src/main/java/com/softropic/skillars/platform/marketplace/repo/CoachProfileRepository.java
- src/test/java/com/softropic/skillars/platform/messaging/repo/ConversationLostUpdateIT.java (new)
- src/test/java/com/softropic/skillars/platform/notification/api/AlertRuleAdminIT.java (new)
- src/test/java/com/softropic/skillars/platform/marketplace/api/CoachMarketplaceResourceIT.java
- src/frontend/src/utils/coachLanguages.js (new)
- src/frontend/src/utils/__tests__/coachLanguagesSpec.js (new)
- src/frontend/src/pages/marketplace/MarketplacePage.vue
- src/frontend/src/components/profileBuilder/ProfileBuilderStep1.vue
- src/frontend/src/components/profile/EditCoachIdentityDialog.vue
- docs/dev-docs/notification/index.html, docs/dev-docs/messaging/index.html, docs/dev-docs/marketplace/index.html,
  docs/dev-docs/assets/search-index.js
- _bmad-output/implementation-artifacts/deferred-work.md, sprint-status.yaml, this story
- evals/review/cases/language-filter-case.yaml (promoted from _drafts/pending-language-filter-case.yaml)

### Change Log

- 2026-11-06: created (ready-for-dev); owner decided D1-D4 (option 1 each).
- 2026-11-06: T1-T7 done; /pr-review clean; PR #469; status review.
- 2026-11-06: PR #469 merged as cfbf2cf0 (CI green); eval case language-filter-case gets fix_merge; status done.
