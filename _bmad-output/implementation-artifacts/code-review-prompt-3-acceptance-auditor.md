# Code Review Layer 3 — Acceptance Auditor

**Story:** `skillars-deferred-28-booking-error-messaging-subscription-coverage-and-media-timestamp-test`
**Diff to review:** `code-review-diff-deferred-28.patch` (same directory as this file)
**Needs read access to the repo.** Run in a separate session; paste findings back into the Claude Code session.

---

You are an **Acceptance Auditor**. Review the diff against its story spec and project context docs. Check
for: violations of acceptance criteria, deviations from spec intent, missing implementation of specified
behavior, and contradictions between spec constraints and actual code.

**Inputs (read all):**

- **Diff:** `_bmad-output/implementation-artifacts/code-review-diff-deferred-28.patch`
- **Spec (the story):**
  `_bmad-output/implementation-artifacts/skillars-deferred-28-booking-error-messaging-subscription-coverage-and-media-timestamp-test.md`
- **Context doc (project rules all code must follow):** `_bmad-output/project-context.md`
- **A senior-dev audit of the spec itself, written before implementation:**
  `_bmad-output/implementation-artifacts/story-review.md`

**Project root:** `/Users/mokwen/dev/gitrepos/bluegithub/skillars` — full read access, modify nothing.

**Important nuance.** The spec was audited before implementation, and that audit (`story-review.md`) found
several defects **in the spec itself** — notably that AC2's instruction to branch on six error codes in the
batch flow was wrong (the batch flow can only throw four), and that AC2's prescribed English copy hardcoded
a batch limit of 10 when the configured limit is 5. The implementer appears to have followed the audit's
corrections rather than the letter of the spec. So where the diff deviates from the spec text, decide which
is right on the evidence and say so explicitly. **A deviation that corrects a spec defect is not a
violation — but an undocumented deviation, or one that goes further than the audit sanctioned, is worth
reporting.** Verify the audit's claims yourself where they matter rather than trusting them.

Audit every acceptance criterion (AC1–AC6) and every task in Tasks/Subtasks. For each, determine: fully
implemented / partially implemented / not implemented / implemented differently than specified. Be concrete
about what is missing.

**Pay particular attention to:**

- **AC1** — the new `CoachMediaItemTest`: does it match the specified two cases and placement?
- **AC2** — the largest item. Verify: all six keys present in all four backend `.properties` bundles AND all
  three frontend bundles; the exact set of codes branched in each of the three catch blocks matches what the
  corresponding backend flow can actually throw; the spec's "do not touch any other catch block" constraint
  honoured; no shared composable/helper introduced (the spec forbids one); the `catch {` → `catch (err) {`
  change made at exactly the three sites and nowhere else; and whether backend and frontend message wording
  agree in meaning per language.
- **AC3** — the new `SubscriptionResourceIT`: does it cover every endpoint in the spec's minimum-coverage
  list, with the specified status assertions? Does it avoid duplicating `PlayerSubscriptionOwnershipIT`'s
  existing `GET /player/me` coverage, as the spec requires? Does it follow the prescribed `@WebMvcTest` +
  imported `TestSecurityConfig` + `@MockitoBean` shape?
- **AC4** — the comment: doc-only with zero behavior change, and does it state something factually true?
- **AC5** — the two boundary tests: correct names, correct mirrored structure, correct assertions, and the
  spec's "do not touch production code" constraint honoured?
- **AC6** — ledger hygiene: all five closed items annotated, the two new items logged, sprint-status updated.
- **`project-context.md` compliance** — especially: Prettier mandatory for `.js`/`.vue`; never hardcode
  localized strings; all user-facing text externalized via vue-i18n; every resource method has
  `@PreAuthorize`; AssertJ for assertions; records for DTOs.
- **Scope discipline** — the spec's Dev Notes forbid a list of specific "while you're in there" changes. Did
  any land?
- **Dev Agent Record** — the spec requires the dev to fill in Agent Model Used / Debug Log References /
  Completion Notes / File List, and specifically requires an explicit flag that no live browser check was
  performed. Is that section complete?

**Already verified mechanically by the orchestrator — do not re-run, but do factor in:**

- `mvn -o test`: `CoachMediaItemTest` 2/2, `CreditRoutingTest` 9/9, `CashOutServiceTest` 5/5,
  `SubscriptionResourceIT` 13/13 — all green, context starts cleanly.
- `npx eslint` on all five touched frontend files: clean.
- `npx prettier --check`: only `ParentBookingsPage.vue` fails, with the **identical 4 hunks at identical
  offsets** as at HEAD — pre-existing template debt, zero regression from this change.
- The frontend `npm test` script is a stub (`echo "No test specified" && exit 0`), confirming the story's
  "no frontend test suite exists" premise.

**Output:** a Markdown list of findings. Each: one-line title, which AC or constraint it violates, evidence
quoted from the diff (with file and line), and severity (Critical / Major / Minor). Then a compact per-AC
status table (AC / verdict / one-line note). Report only defensible findings grounded in evidence you
verified — no speculation. Where the implementation is correct and complete, say so plainly rather than
manufacturing concerns.
