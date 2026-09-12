# Deferred Work

Issues surfaced during code review that were consciously **not** fixed in the story under review.
One bullet = one open item. Grouped by the review that raised it; the heading carries that review's date.

## How to read this file

- **This is a list of open work only.** Items are deleted outright once they are implemented — this file is
  not a history of what was fixed. Absence of an item does not mean it was never raised; check git history
  of this file (or the `skillars-deferred-*.md` stories) for what has been closed.
- **Sections are not in chronological order.** Read the date in each `## Deferred from:` heading.
- **Item ids (`D1`, `W3`, `Def12`, `AD4`…) are only unique within their section.** They are the ids the
  original review used; they repeat across sections and are not global identifiers.
- **`**[AUDIT <date>: …]**` annotations are the only re-verified claims in this file.** Everything else
  — especially any "will be fixed in Story X" / "Epic Y owns this" phrasing — records what was believed at
  review time and has **not** been re-checked. Several such promises turned out to be unkept (see below).
  Verify against the code before trusting an unannotated forward-reference.
- **File paths and line numbers age fast.** They were accurate at the review date in the heading.

## Last audit: 2026-09-11 (post-merge prune — deferred-work.md hygiene)

Routine sweep after `skillars-deferred-109` (PR #176) merged to master, per this file's own
delete-outright-when-closed convention. Found two live bullets that had accumulated a
`[CLOSED by ...]` / "closing" tag but were never actually removed:

- `## Deferred from: code review of skillars-deferred-94 (2026-09-07)` — the actuator
  health-endpoint bullet, tagged `[skillars-deferred-100 AC7 (2026-09-08) reframe: … Nothing left
  to do here — closing.]`. Deleted outright; it was the only bullet under that header, so the
  header was removed with it.
- `## Deferred from: skillars-deferred-104 implementation (2026-09-09)` — the "~6 frontend
  coverage gaps are now unblocked, not closed" bullet, tagged `[CLOSED by skillars-deferred-108
  AC1–AC6 (2026-09-10): …]` — `deferred-108`'s own audit added the tag but never deleted the
  bullet. Deleted outright; the sibling "Quasar Vitest AE library-only" bullet under the same
  header is untouched and stays open.

No other item in the file carries a `[CLOSED by ...]` / `[STALE ...]` / `[WITHDRAWN ...]` tag
outside historical `## Last audit` narrative text (checked by full-file grep before writing).
`[DECIDED ...]` and `[DISMISSED ...]` bullets are untouched by design — declined / decided-wont-fix,
not closures. Reconstruction check: every surviving line matches the pre-prune file, in order, with
nothing added or reworded besides the two deletions above. Baseline before this pass: 1998 lines,
88 `## Deferred from:` headers. **Measured after writing** (fresh `wc -l` / `grep -c` / `grep -o`,
this audit block itself included): **2005** lines; **87** `## Deferred from:` headers; **50** raw
`[DECIDED` tokens; **40** raw `[DISMISSED` tokens (both raw counts read higher than the real
±0 item delta because this block's own prose names both tokens, same as every prior audit block).

## Last audit: 2026-09-04 (deferred-work.md prune + first-ever `deploy-*` re-audit)

Two passes, both run during `skillars-deferred-92` story creation at `c2c47c1`.

### Pass 1 — prune, per this file's own stated convention

Every bullet carrying a `[CLOSED by ...]`, `[STALE ...]` or `[WITHDRAWN ...]` tag was deleted outright
(19 bullets), and the 12 `## Deferred from:` headers left with zero content were deleted with them.
Verified before writing by a line-for-line reconstruction check: every surviving line appears in the
pre-prune file, in the same order, with nothing added or reworded. File: 1343 -> 1295 lines.

**Deliberately NOT deleted:** `[DISMISSED ...]` and `[DECIDED ...]` bullets. These are not *done* — they
are declined or decided-wont-fix, and the file keeps them precisely so the decision is not re-litigated
(the 2026-08-24 pruning pass drew the same line for the same reason). 24 `DISMISSED` and 10 `DECIDED`
bullets remain by design. `[PICKED UP by ...]` bullets (claimed by a story, not yet shipped) also remain.

**One residual was rescued from a deleted bullet rather than lost with it** — see the new section below.

### Pass 2 — the `deploy-*` sections, re-audited against the current scripts

**Every prior full-file audit in this file (2026-08-04, three on 2026-08-05, 2026-08-24, 2026-08-24) closed
with the same sentence: the `deploy-*` sections were not read.** Six audits running. This pass reads them.
Every item below was checked against the live file at `c2c47c1`, not against the ledger's own text.

| Item | Verdict |
|---|---|
| `deploy-3-4` DROP DATABASE / open connections | ~~open, **narrowed** — `app` is the only container with a datasource; the real blocker is a human `psql` session. Citation corrected to the script.~~ **CLOSED by `skillars-deferred-101` AC6** — `restore-from-dump.sh:140-141` runs `SELECT pg_terminate_backend(pid) … WHERE datname = '<db>' AND pid <> pg_backend_pid();` immediately before `DROP DATABASE IF EXISTS`. Standalone bullet already deleted by `deferred-101`. |
| `deploy-3-4` hardcoded container UIDs 65534/10001/472 | ~~`[PICKED UP by skillars-deferred-94 AC2]` — comments added; verify no regression.~~ **CLOSED by `skillars-deferred-107` AC8** — `provision.sh` / `restore-from-volume-backup.sh` now probe each service image's real runtime uid (`image_runtime_uid` → `chown_probed`, reads `docker inspect .Config.User`); the numeric constants survive only as a WARN-on-mismatch fallback, so an upstream UID change WARNs instead of silently breaking ownership. Standalone bullet deleted by `deferred-107` post-merge prune (2026-09-10). |
| `deploy-3-4` APP_CID capture race | ~~open, **partly mitigated** — now fails fast with a diagnostic instead of a 90 s timeout. Citation corrected.~~ **CLOSED by `skillars-deferred-102` AC2** — `restore-from-dump.sh:205-222` is a 5×/2s bounded retry loop around `${DC} ps -q app`, then a clear `err` + EXIT-trap recovery. Standalone bullet already deleted by `deferred-102`. |
| `deploy-3-4` WebhookPermanentFailure Admin API | **STALE, deleted** — the alert no longer exists |
| `deploy-3-4` CallbackRateZero endpoint undocumented | **STALE, deleted** — the alert no longer exists |
| `deploy-3-3` double notification if Alertmanager added | ~~`[PICKED UP by skillars-deferred-94 AC4]` — design gate comment added to docker-compose.yml; decision-deferred (Alertmanager not deployed yet).~~ **DECIDED by `skillars-deferred-107` AC7** — Grafana-only delivery; Prometheus rules stay non-delivering; a future Alertmanager change carries a documented checklist. See `docs/deployment/monitoring.md#alerting-architecture-the-alertmanager-decision`. |
| `deploy-3-3` node_exporter network isolation | ~~open, **substantially narrowed** by `skillars-deferred-88` AC8 — see the corrected bullet~~ **DECIDED by `skillars-deferred-107` AC5** — accept & document: the only on-host peers are first-party `app`/`grafana`, a compromised `app` already holds the DB/Stripe/Bunny/OTLP credentials, a dedicated network was evaluated and rejected. Full rationale in the `docker-compose.yml` `node_exporter` comment. |
| `deploy-3-3` DiskDataVolumeHigh needs the Volume mounted | ~~`[PICKED UP by skillars-deferred-94 AC6]` — comment + docs added; volume mount prerequisite documented.~~ **CLOSED by `skillars-deferred-94` AC6** — the clarifying comment shipped (`deploy/lgtm/alerts.yml:62-65`: "DiskDataVolumeHigh requires the Hetzner volume to be mounted … If unmounted, the … metric is absent and this alert silent") and the volume-mount verification step is documented (`docs/deployment/first-time-setup.md:388`). Standalone bullet deleted 2026-09-10 (post-`deferred-108`-merge prune). |
| `deploy-3-1` PGPASSWORD via `docker exec -e` | ~~`[PICKED UP by skillars-deferred-94 AC1]`~~ **CLOSED (skillars-deferred-94 AC1, shipped 2026-09-07):** all occurrences now use `PGPASSWORD="…" docker exec -e PGPASSWORD "$CID"` env-var inheritance — no secret in `ps aux`. Bullet deleted 2026-09-10. The separate `/proc/<pid>/environ` bullet remains. |
| `deploy-3-1` credentials in `/proc/<pid>/environ` | open, unchanged, project-wide |
| `deploy-3-1` awscli v1 from Ubuntu apt | open, unchanged — `provision.sh:131` |
| `deploy-1-5` repo cloned before the Volume is mounted | open, unchanged |
| `deploy-1-5` repo cloned as root, `.git` beside runtime data | open, unchanged |
| `deploy-1-5` no rollback / DR documentation | **CLOSED, deleted** — `rollback.md`, `backup-restore.md` and `runbook.md` all shipped with Epic 3, exactly as the item predicted |
| `deploy-1-5` `git clean` vs the data subdirectory | ~~open, **narrowed** — `.gitignore` now covers `/data/`; only `git clean -fdx` still reaches it~~ **CLOSED by `skillars-deferred-102` AC6/AC7** — the checkout moved to `/opt/skillars/app`, a *sibling* of the Volume mount `/opt/skillars/data`, owned by non-root `deploy`; `.env` is at `/opt/skillars/.env`, outside the checkout. `git clean -fdx` inside the checkout reaches neither. Standalone bullets already deleted by `deferred-102`. |
| `deploy-1-3` LGTM `mkdir -p` gated inside the `[ -b ]` check | ~~`[PICKED UP by skillars-deferred-94 AC14]` — clarifying comment added; no code change.~~ **CLOSED — premise stale (skillars-deferred-103 AC12, 2026-09-09):** `mkdir -p "${DEPLOY_ROOT}/lgtm"` runs unconditionally at `provision.sh:349`, well before the `if [ -b "${VOLUME_DEVICE}" ]` gate at `:567`. Bullet deleted. |

Net: 18 items examined, **3 closed or stale and deleted**, 6 corrected in place (stale citations, narrowed or
widened scope), 9 confirmed unchanged, **0 new `deploy-*` findings filed** — the one bullet originally filed
here (the booking-reminder wiring gap below) came from the story review, not this audit, and has since
shipped. `[AUDIT 2026-09-04, chunk-5 code review: the original "2 new findings filed below" count was wrong
on both axes — it counted a non-deploy-* finding, and it counted it twice against a section that only ever
held one bullet. Corrected here rather than re-filed as its own residual.]`

**Scope of this audit, so the gaps stay explicit.** It covered every `## Deferred from: deploy-*` section
and every file those items name. It did **not** re-check the deployment items filed under non-`deploy-*`
headings (`skillars-uat-6`, `skillars-deferred-20`/`-87`/`-88`) — those were written between 2026-08-13 and
2026-08-31 and are recent enough to trust. It did not review the `deploy/` scripts for defects nobody has
filed yet; it verified the filed claims only.

## Last audit: 2026-08-24 (deferred-work.md pruning pass)

This file's own first rule above says closed items are **deleted outright, not kept with a tag** — but
recent stories (`skillars-deferred-41` onward) drifted from that: they left the original bullet in place
and appended a `[CLOSED by ...]` / `[STALE ...]` annotation instead of removing it, so the file grew even
as more items closed. This pass restores the stated convention: every bullet carrying a `[CLOSED by ...]`
or `[STALE ...]` tag (175 total — 136 `[CLOSED]`, 39 `[STALE]`, added across every story from
`skillars-deferred-16` through `skillars-deferred-60`) was deleted outright, mechanically, by a script
verified against this exact file before running (line-for-line reconstruction check: every surviving
non-blank line matches the pre-prune file's non-tagged content, in order, with nothing added or reworded).
28 `## Deferred from:`/`###` section headers that had zero items left after their tagged bullets were
removed were deleted along with them, since an empty section serves no purpose. `[PICKED UP by ...]`
bullets (claimed by a story, not yet shipped), `[DISMISSED ...]` bullets, decision-needed items, and every
untagged open item were left untouched, including their full original text, with one exception: a
section-header's non-bullet intro paragraph is deleted along with it when every bullet under that header
is tagged (one instance — the old `## Deferred from: code review of skillars-deferred-28-...` header's
"review-layer coverage was incomplete" process footnote, whose own three bullets were all `[CLOSED by
...]`-tagged and removed, collapsing the section per this pass's own stated rule). Nothing in this pass
re-verified or re-judged any open item, it only removed items already closed by a prior pass.
File size: 1854 → 1523 lines (exact). **Not touched:** the content of every other `## Last audit:` section in
this file (each is a narrative record of its own audit, not itself a set of taggable items — confirmed
none contain a `[CLOSED` or `[STALE` tag) and every item this pass didn't already find carrying one of
those two tags. Full removed text remains recoverable from git history of this file, per this file's own
first rule.

## Last audit: 2026-08-04

Scope of that audit, so the gaps in it are explicit:

- Removed every item closed by the shipped `skillars-deferred-1` … `skillars-deferred-10` stories, after
  verifying each fix in code rather than trusting the story's `done` status.
- Removed items whose subject code was deleted by Story 11.3 (the legacy `SessionPackService` /
  `SessionPackResource` / `SessionPackResourceIT` system) and items already marked RESOLVED inline.
- Moved 6 items into `skillars-deferred-11-stripe-card-collection.md` (Stripe card collection, the
  `loadStripe` null-key guard, `payment.store.js` shared flags, the pack-resolution TOCTOU, the
  `sessionPacks` store scoping, and coach-name negative caching).
- Re-verified 13 items that named a future story as their fixer, where that story has since shipped. **Twelve
  of the thirteen were never actually fixed** and now carry an `[AUDIT 2026-08-04: STILL OPEN …]` note; one
  was superseded by a deliberate design decision and is annotated as such.
- **Not audited:** every other forward-reference and every "pre-existing pattern" claim. Those remain
  unverified. The deployment/infrastructure sections (`deploy-*`) were not re-checked against the current
  scripts at all.

### Known tracking defect found by the audit

`skillars-deferred-5` is marked `done` in `sprint-status.yaml`, but the 2026-08-04 audit read its
**AC4 as never implemented** — `ReviewApiAdvice` not covering `platform.admin.api`. Treat other
`done` markers with corresponding care: the general lesson stands even though this specific instance
turned out to be closed.

**Resolved 2026-08-04 (deferred-13):** that reading was wrong by the time it was written. The gap was
already closed by `skillars-deferred-12` — `ReviewApiAdvice.java:26-28` carries
`assignableTypes = AdminReviewResource.class` alongside `basePackages`, confirmed by direct read
during `deferred-13` and re-confirmed by its code review. The `skillars-9-3` section that used to
hold this item (tracked there as D2) was removed by `deferred-13` as already-closed, so the
cross-reference that stood here is gone; the sentence above has been corrected to match. See the
`deferred-13` audit note below for the full list.

## Last audit: 2026-08-05 (deferred-13 code review)

Written by the `deferred-13` **code review** on 2026-08-05, superseding the dev-authored draft of
2026-08-04 that claimed this provenance before any review had run. The item-level claims below were
re-verified by three independent review layers with repository access. Scope, so gaps stay explicit:

- Closed by code in this story: `skillars-deferred-12` D3 (`blockReview()` now uses
  `findByIdForUpdate` + an `ALREADY_BLOCKED` guard, mirroring `approveReview()`); `skillars-9-3` D3
  (`ReviewFlagService.flag()`'s coach-profile lookup now fails closed with
  `COACH_PROFILE_MISSING` instead of silently skipping the self-flag guard); `skillars-10-2` D3
  (enforcement list now batches strike counts via `countByCoachIdInAndCreatedAtAfter`, mirroring
  `CoachSearchService.loadReliabilityStrikes()`).
- Closed by deletion, not by fixing: `skillars-7-3` D2 (`processAdminRefund` removed outright — it
  had zero call sites in `src/main` or `src/test`, and Epic 10 already wired the real admin-refund
  path through `DisputeService.resolveDispute()`, which has auth, amount validation, an
  already-resolved guard, and an audit log entry).
- Deleted as already-closed-or-obsolete, evidence re-verified by direct read on 2026-08-04:
  `skillars-9-3` D1 and D2 (closed by `skillars-deferred-12`: `AdminReviewService.approveReview()`
  has both the pessimistic read and the `ALREADY_APPROVED` guard; `ReviewApiAdvice.java:26-28`
  carries `assignableTypes = AdminReviewResource.class` alongside `basePackages`); the D1 under
  `## Deferred from: code review of skillars-10-1-admin-moderation-queue-message-content-actions
  (2026-06-30)` (obsolete — `V65__messaging_module_init.sql:22` declares `content TEXT NOT NULL`,
  so the null-content branch is unreachable, and `AdminQueueService.buildSummary()` already yields
  `"[message not found]"` rather than `null` via `Optional.map`); `skillars-3-11` D2 (closed by
  `V87__booking_overlap_exclusion_constraint.sql`, which added the DB-level exclusion constraint
  the item asked for — that entry also recorded two app-layer bypass paths, and the constraint
  changed their failure mode rather than fixing them, so the residue was re-opened as its own item.
  **That residue is now closed too**: `skillars-deferred-14` AC4 added the app-layer lock + overlap
  pre-check to both bypass paths, and its `deferred-13` code-review section was deleted with it —
  which is why the cross-reference that used to stand here is gone).
- **Not re-deleted / explicitly left alone:** the separate `## Deferred from: code review of
  skillars-10-1 patches (2026-06-30)` heading's own D1 and D2 (`findBeforePivot`/`findAfterPivot`
  null-pivot and exact-timestamp-collision items) — unrelated to the `buildSummary()` item deleted
  above despite both sections starting with `skillars-10-1`; still open, untouched by this story.
  `skillars-10-2` D1 (`AFTER_COMMIT` listener failure silently drops refunds) — explicitly a
  platform-wide event-reliability concern, too large for this story, left in the file.
- **Not re-checked:** every other forward-reference in the file, and the deployment/infrastructure
  (`deploy-*`) sections again — same gap the 2026-08-04 audit above already flagged.
- **Added by the code review itself (2026-08-05):** three new items under a
  `## Deferred from: code review of skillars-deferred-13-admin-moderation-action-integrity` heading.
  D1 was the substantive one — the Gemini moderation listener could silently revert a committed admin
  BLOCK, which meant `deferred-13` AC1's lock was only half the guarantee it read as.
  **All three were closed by `skillars-deferred-14` and that heading no longer exists** (see the
  deferred-14 audit block below); this bullet is kept only as the record of where they came from.
- **One AC was overridden by the review, not merely implemented:** `deferred-13` AC4 specified
  `409 CONFLICT` for `COACH_PROFILE_MISSING`; the review changed it to `500` (with an ERROR log) on
  the grounds that an orphaned `coach_profiles` row is a data-integrity failure the caller neither
  caused nor can retry away, and a 4xx would hide it from 5xx-keyed alerting. `ALREADY_BLOCKED`
  remains `409`. Recorded here because the story file's AC4 text now differs from what shipped in
  `deferred-13`'s original spec.

## Last audit: 2026-08-05 (skillars-deferred-14 story creation)

Written while scoping `skillars-deferred-14`. Unlike a code-review audit, this one had no independent
review layers — every claim below comes from a direct read of the named file during story creation.
Scope, so the gaps stay explicit:

- **Deleted as already closed, verified by direct read:**
  - `skillars-3-11` D1 (coach-suspension race in `createBookingRequest`) — closed by `skillars-deferred-12`.
    `BookingService.java:198-212` now does `findByIdForUpdate` + `entityManager.refresh(lockedCoach,
    PESSIMISTIC_WRITE)` + a re-check of `SUSPENDED` and the active-status whitelist under that lock.
    That was the only item under its heading, so the heading went with it.
  - The `canonicalTimezone not IANA-validated` bullet under `skillars-3-3` Group A — closed.
    `BookingService.java:178-183` wraps `ZoneId.of(req.canonicalTimezone())` in `try/catch
    (DateTimeException)`. The other two Group A bullets are untouched and still open.
- **Corrected, not deleted:** `skillars-deferred-13` D2's premise is wrong — the 409 mapping it asks
  for has existed since Story 3.11, and the `createBatch` path it names is not reachable by the
  constraint at all. Annotated inline and re-scoped to the real residue (the missing app-layer
  overlap pre-check). Also fixed D3's wrong test-method name (`...returns409...` → `...returns500...`).
- **Closed by shipped code in `skillars-deferred-14` (deleted 2026-08-05, after implementation):**
  `skillars-deferred-13` D1 (moderation listener now takes a locked read and writes only while the
  review is still `PENDING`; mutation-verified — with the guard removed the new
  `ReviewModerationIT#adminBlocksWhileGeminiInFlight_blockSurvivesSafeVerdict` fails with
  `expected BLOCKED but was APPROVED`), D2 (both bypass paths now run the app-layer lock + overlap
  pre-check), D3 (orphan fixture moved to `@AfterEach`), and the `skillars-3-3` Group C
  cross-field-validation bullet (`@AssertTrue isEndAfterStart()` → 400). Both headings became empty
  and were removed.
- **Correction to this audit's own earlier reading of D2.** The entry claimed no 409 mapping existed;
  that was wrong (Story 3.11 wired one). But D2's *conclusion* — that a batch-path overlap surfaces
  as a 500 — turned out to be **right for a reason neither the entry nor this audit had identified**.
  Reproduced before fixing: a batch whose second slot collided returned
  `500 generic.unknown`, because Hibernate defers the batch's UPDATEs to commit, the JDBC batch fails
  as one, and the resulting `DataIntegrityViolationException` carries `constraint [null]` — so
  `ApiAdvice`'s name-keyed `CONSTRAINT_MAPPINGS`/`CONFLICT_CONSTRAINTS` lookup cannot match it and the
  409 mapping never applies. The mapping is real but unreachable on that path. The app-layer
  pre-check now makes it moot for both bypass paths; the constraint remains the backstop.
- **Added by this audit:** one new item under `## Deferred from: skillars-deferred-14 story creation
  (2026-08-05)` — no automatic sweeper exists for bookings stranded in `PAYMENT_PENDING`, and the
  only recovery is a parent-initiated cancel, while the stranded row holds the coach's slot. Found
  while scoping `deferred-14` AC3a, which narrows that window but deliberately does not close it.
- **Examined and deliberately left alone:** `skillars-10-2` D1 (`AFTER_COMMIT` refund drop — still a
  platform-wide event-reliability concern, same reason `deferred-13` left it) and `skillars-8-2`
  D1/D2 (deleted-player `UserNotFoundException` crashing `getConversations()` — real and still open,
  but messaging-module scope).
- **Not re-checked:** every other item in this file. In particular no `deploy-*` section was read —
  the same gap the 2026-08-04 and 2026-08-05 audits below already flagged, now three audits running.

## Last audit: 2026-08-05 (skillars-deferred-15 story creation)

Written while scoping `skillars-deferred-15`. Like the `deferred-14` story-creation audit and unlike a
code-review audit, this one had no independent review layers — every claim comes from a direct read of
the named file at commit `cc8d2a8`. Scope, so the gaps stay explicit:

- **Five items annotated `OWNED BY skillars-deferred-15`, each naming the AC that closes it** (they are
  deleted by that story once shipped, not now): the `PAYMENT_PENDING` sweeper (`deferred-14` story
  creation D1), `deferred-14` review D1 (reschedule accept/decline TOCTOU), D2 (no suspension check on
  the accept paths) and D4 (batch-status formula), and `skillars-7-2` Group 2 D2 (pack expiry-warning
  email spam). Note that the sweeper item is closed **narrowly** — see the correction two bullets down
  and the new D1 below.
- **Two of those items were found to be mis-stated, and are corrected inline:**
  - `deferred-14` D2 says the accept paths never *re-check* `SUSPENDED` after the lock. They never check
    it **at all** — `acceptBooking`, `acceptOneBooking` and `acceptReschedule` verify ownership only.
    And `AdminCoachEnforcementService.suspendCoach:101` writes the suspension through a plain
    `findById`, so the lock the accept paths already hold serialises against nothing.
  - `skillars-7-2` Group 2 D2 says the notifier sends up to 14 warning emails per pack. Today it sends
    **zero**: `SessionPackExpiryNotifier.notifyExpiringPacks:32-65` is not `@Transactional` and opens no
    `TransactionTemplate`, while `SessionPackEmailListener.onExpiryWarning:37-38` is an
    `@TransactionalEventListener(AFTER_COMMIT)` with default `fallbackExecution = false`, so the event is
    discarded. `SessionPackForfeitureScheduler:42-56` publishes inside a transaction and works —
    the difference between the two schedulers is the bug. The 14-email behaviour is what appears the
    moment delivery is fixed, which is why the dedupe column must ship in the same change.
- **Downgraded, not deleted:** `skillars-8-2` D1/D2 (deleted-player `UserNotFoundException` crashing
  `getConversations()`). The throw is real (`AgePolicyService:52-54`, unguarded at
  `MessagingService:102-105`), but **no path in `src/main` deletes a `player_profiles` row** —
  `GdprErasureService` anonymises the `User` and deletes development data while leaving the profile, and
  `UserAdminService.deleteUserInTransaction` deletes only never-activated `User` rows. Reachable via a
  data-integrity failure, not ordinary deletion. Annotated inline; still open, still messaging scope.
- **Examined and deliberately left alone:** `deferred-14` review D3 (its own inline audit already
  establishes the scenario is unreachable behind the 365-day edit rule — a design limitation, not a live
  defect) and `skillars-10-2` D1 (`AFTER_COMMIT` refund drop — the same platform-wide event-reliability
  concern `deferred-13` and `deferred-14` both left; `deferred-15`'s sweeper is deliberately narrow and
  does not generalise into an outbox).
- **Added by this audit, after a review of the draft story caught two wrong claims in it:** one new item
  under `## Deferred from: skillars-deferred-15 story creation (2026-08-05)` — there is no durable
  pre-capture record for a Stripe charge, so a credit-funded booking stranded in `PAYMENT_PENDING`
  cannot be swept safely and `deferred-15` AC1 deliberately only reports it. The draft of that AC had
  claimed two "money-safety guards" made a full sweep safe; both were wrong (details in the new item),
  and finding that is the reason the sweeper shipped narrower than `deferred-14`'s D1 implied it could.
- **Correction to `deferred-14` story-creation D1's framing, recorded before it is deleted.** It reads as
  though grace-period length is the only design question for a sweeper. It is not: on the credit path
  the Stripe capture precedes every durable write that records it, so no grace period makes an automated
  decline safe there. The item is still closed by `deferred-15` — but by a narrower fix than it implied.
- **Not re-checked:** every other item in this file. No `deploy-*` section was read — the same gap the
  2026-08-04 and both 2026-08-05 audits flagged, now four audits running.

### Implementation outcome (2026-08-05, `skillars-deferred-15` shipped)

All five owned items are **closed by shipped code and deleted from this file**: `deferred-14` story
creation D1 (whose heading emptied and was removed with it), `deferred-14` review D1/D2/D4, and
`skillars-7-2` Group 2 D2. What implementation found that the audit above did not:

- **The sweeper premise held, with a corrected count.** The pre-fix reproduction ran before any code
  was written: a booking seeded in `PAYMENT_PENDING` with `updated_at` 30 days old survived
  `BookingExpiryScheduler.expireStaleRequests` untouched with no `booking_payments` row, and a
  `createBookingRequest` for the same coach and window was rejected with `booking.slotUnavailable` —
  the slot-blocking harm, observed rather than argued. Postgres rejected a second seed at that slot
  with `excl_bkg_coach_slot_overlap`, confirming the hold at DB level too. The scheduler audit found
  **29 `@Scheduled` methods across 27 files** (the audit above says "27 schedulers" — it was counting
  files); **zero read `PAYMENT_PENDING`**, as claimed.
- **`skillars-7-2` Group 2 D2's corrected premise is confirmed by execution, not just by reading.**
  With a pack seven days from expiry and sessions remaining, `notifyExpiringPacks` produced **zero**
  warning emails pre-fix (the probe asserted one and failed with "expected: 1L but was: 0L"). The
  original item's "up to 14 emails" was wrong; the audit's "zero" was right.
- **`deferred-14` review D2's fix shape was incomplete, and the story's AC4 was too.** Both describe a
  locked re-check on the three accept paths. That is necessary but not sufficient for the batch path:
  `acceptAll` catches per-booking exceptions to allow partial success, so a locked throw inside
  `acceptOneBooking` is swallowed and the suspended coach's `acceptAll` returns a silent no-op rather
  than a 403. Shipped fix carries **both** — an unlocked status check in `acceptAll` for the clean
  `booking.coachUnavailable`, and the locked per-booking check for the race. The unlocked one cannot
  be a locked one: taking the coach lock in `acceptAll`'s transaction would make every per-booking
  `REQUIRES_NEW` transaction block on a lock its own caller holds, on a different connection, until
  the 5s timeout.
- **`deferred-14` review D1 needed a refresh, not only a re-check.** `findByIdForUpdate` is JPQL and
  `acceptReschedule` already loads the reschedule row through `findById` earlier in the method, so the
  locked read returns the same managed instance with its stale in-memory status. Re-checking without
  `entityManager.refresh(..., PESSIMISTIC_WRITE)` would have read `PENDING` off the stale instance and
  never fired — the same trap `BookingService.createBookingRequest` documents for the coach row. Both
  the reschedule row and the coach row now refresh; `BookingBatchService.acceptOneBooking` deliberately
  does not, because its `REQUIRES_NEW` persistence context is fresh.
- **Testing trap worth knowing before writing the next scheduler test:** invoking a
  `@SchedulerLock`-annotated method from a test goes through the Spring proxy, so ShedLock applies —
  with `lockAtLeastFor = PT2M` (universal in this codebase) a second invocation in the same test class
  is silently **skipped**, and the assertions after it then pass or fail for reasons unrelated to the
  code under test. Found because the pre-fix probe's `expireStaleRequests()` call logged "held by
  another instance". `BasePaymentIT.releaseSchedulerLock(name)` now exists for this; use it before
  every invocation.
- **Still open and deliberately not closed:** `## Deferred from: skillars-deferred-15 story creation`
  D1 (no durable pre-capture record). It is what confines the sweeper to pack-funded bookings.

## Last audit: 2026-08-05 (skillars-deferred-16 story creation)

Written while scoping `skillars-deferred-16`. Like the `deferred-14` and `deferred-15` story-creation
audits and unlike a code-review audit, this one had no independent review layers — every claim comes
from a direct read of the named file at commit `0d06925`. Scope was **the messaging module and its
admin consumer only**; nothing outside `platform.messaging` / `platform.admin` was re-read except the
one booking-module item corrected below. Gaps stay explicit:

- **Ten items annotated `OWNED BY skillars-deferred-16`, each naming the AC that closes it** (they are
  deleted by that story once shipped, not now): `skillars-8-3` W2 (split across AC1 and AC2),
  `skillars-8-2` D1 and D2, `skillars-8-1` D1, D3, D5 and D6, `skillars-8-4` W3 and W4, and
  `skillars-10-1` D2 under the `…-admin-moderation-queue-message-content-actions (2026-06-30)` heading
  (**not** the `…-10-1 patches` heading's D2, which stays — two headings begin with `skillars-10-1` and
  each has a `D2`).
- **Three items deleted as already closed or unreachable, verified by direct read:**
  - `skillars-8-3` W5 (`content` dereferenced before its null check in `GeminiModerationService.moderate`)
    — **closed.** `:38-41` now opens the method with `if (content == null || content.isBlank())` before
    any dereference, returning a `SAFE` result.
  - `skillars-8-3` W3 (`conv.getParentId()` returned without a null guard for SUPERVISED in
    `ModerationResultApplier.resolveRecipient`) — **unreachable.** `Conversation.parentId` is
    `nullable = false` (`Conversation.java:29-30`) and `V65__messaging_module_init.sql` declares
    `parent_id BIGINT NOT NULL`, so `:105` cannot return null. The item's second clause ("same pattern in
    `MessagingService.resolveRecipient`") names a method that no longer exists — `resolveRecipient` lives
    only in `ModerationResultApplier` and `AdminMessageService` now. Same precedent as the null-content
    `10-1` item `deferred-13` deleted.
  - `skillars-7-2` Group 4 D14 (`CANCEL_PARENT` not permitted from `PAYMENT_PENDING`) — **closed, and its
    audit annotation was wrong.** The item carried `[AUDIT 2026-08-04: STILL OPEN … BookingStateMachine
    .java:30-32 maps PAYMENT_PENDING to PAYMENT_CAPTURED and PAYMENT_FAILED only]`. `BookingStateMachine
    .java:34-38` now maps `PAYMENT_PENDING → {PAYMENT_CAPTURED, PAYMENT_FAILED, CANCEL_PARENT}` with a
    comment naming `deferred-12` AC4 as the change. Out of this story's module, deleted anyway because a
    wrong `STILL OPEN` marker is worse than no marker in a file whose value is that its annotations can
    be trusted.
- **Three owned items were found to be mis-stated, and are corrected inline:**
  - `skillars-8-4` W4 says the soft-delete race "requires `@Version` on `Message`". It does not, and that
    fix would be harmful — five paths write the row (`ModerationResultApplier.applyResult:63`,
    `AdminMessageService.approveMessage:101`/`blockMessage:144`, `deferred-16`'s new sweeper, and
    `softDeleteMessage:281`), so optimistic locking would turn benign moderation-pipeline interleavings
    into `OptimisticLockingFailureException`s thrown from a request thread with SSE callbacks registered
    and no retry. `deferred-16` AC5 uses the codebase's established `findByIdForUpdate` instead.
  - `skillars-8-1` D1's stated blocker is gone. Its `[AUDIT 2026-08-04: STILL OPEN … no playerId→userId
    mapping exists anywhere in `platform.messaging`]` is literally true but out of date:
    `V84__player_self_registration.sql` (shipped outside the story flow, commit `1f24a5e`) added
    `main.player_profiles.user_id` with a `chk_pp_owner` CHECK, seeded `ROLE_PLAYER` as authority 102,
    and `PlayerProfileRepository.findByUserId`/`existsByUserId` now exist. The mapping the item asked for
    is available in `platform.security.repo`, which is why the item is now closable.
  - `skillars-8-1` D5's stated harm does not exist. It warns that "any consumer using `lastMessageAt` as a
    cursor may miss the just-sent message"; there is no such consumer. `countUnread`'s `:since` comes from
    `resolveLastReadAt` (the per-role `*LastReadAt` columns), and `lastMessageAt` is read only for the two
    conversation-list sorts (`MessagingService:117,:222`) and a relative-time label
    (`MessagingPage.vue:30`). `deferred-16` AC6b keeps the fix as plain correctness and drops the framing.
- **Scope correction found while drafting AC4, recorded because neither owned item mentions it:**
  `skillars-8-2` D1/D2 and `skillars-8-1` D1/D6 all name `MessagingService`, but
  `MessagingReportService.verifyIsParty:134-155` was an acknowledged hand-copy of
  `MessagingService.verifyIsParty` — its comment (`:129-133`) said "Duplicates `MessagingService.verifyIsParty()` —
  injecting `MessagingService` would create a circular dep" — and carried the identical silent
  `default -> Objects.equals(conv.getPlayerId(), callerUserId)` arm, gating the abuse-report
  endpoints. **[CLOSED by skillars-deferred-16 AC4 — both copies fixed; method is now role-aware with proper PlayerProfileRepository injection and identical logic to MessagingService. Commit `c7301e0` (shipped 2026-08-05). The circular dependency duplication remains by design — untangling it is a separate concern.]**
- **Added by this audit:** one new item under `## Deferred from: skillars-deferred-16 story creation
  (2026-08-05)` — `messaging.conversations.parent_id` is `NOT NULL` while a self-registered adult
  player's profile has `parent_id IS NULL`, so conversation creation for such a player would fail at the
  DB. Found while scoping AC4; not reachable today and deliberately not fixed by `deferred-16`.
- **Examined and deliberately left alone** (recorded so the next audit does not re-litigate them):
  `skillars-8-1` D2 (N+1 in `getConversations` — real, unchanged, an MVP-volume tradeoff; belongs with the
  other N+1 items in a performance pass, and `deferred-16` AC4 edits those exact lines without turning
  into a batching rewrite); `skillars-8-3` W1 (age-policy and party checks outside a transaction — the
  `NOT_SUPPORTED` propagation is spec-designed and load-bearing for `deferred-16` AC1, and `sendMessage`
  already re-checks `BLOCKED` inside the transaction at `:159-165`); `skillars-10-1` patches D1 (null
  pivot `createdAt` — `V65` declares `created_at … NOT NULL` and the only caller reads it off a persisted
  row, so test-fixture-only; `deferred-16` AC6c edits those same two queries and deliberately does not
  fold it in); `skillars-10-1` patches D2 (context window excludes soft-deleted messages while
  `findAllForAdmin` includes them — verified still true at `MessageRepository:32-40`, but it is a product
  call about what an admin should see, not a defect); `skillars-8-4` W5 (`IS_AUTHENTICATED` on the report
  endpoints — consistent with every other endpoint in `MessagingResource`, 403 preserved at the service
  layer); `skillars-8-1` D4 (`Instant.EPOCH` sentinel undocumented — a comment already stands at
  `MessagingService:323`).
- **Not re-checked:** every item outside `platform.messaging`/`platform.admin`. No `deploy-*` section was
  read — the same gap the 2026-08-04 and the three 2026-08-05 audits flagged, now five audits running.

### Implementation outcome (2026-08-05, `skillars-deferred-16` shipped)

All ten owned items are **closed by shipped code and deleted from this file**: `skillars-8-3` W2,
`skillars-8-2` D1/D2 (heading emptied and removed with them), `skillars-8-1` D1/D3/D5/D6 (D2 and D4
left in place), `skillars-8-4` W3/W4 (W5 left in place), and `skillars-10-1` D2 under the
`…-admin-moderation-queue-message-content-actions` heading (heading emptied and removed; the separate
`…-10-1 patches` heading's own D2 is untouched). What implementation found that the story-creation
audit above did not:

- **Task 1's `PENDING`-reader grep audit confirmed the story-creation claim exactly**, re-run
  independently before any code changed: three `PENDING` references in `src/main`
  (`ModerationResultApplier:40` fallback, `MessagingService:171` write, `Message.java:39` column
  default), all writes/defaults, and `MessageRetentionScheduler` remains the module's only
  `@Scheduled` method (age-based deletion, not status-based). No re-scope of AC2 was needed.
- **AC1 mutation-verified.** With the `PENDING`/`deletedAt` guard in `ModerationResultApplier
  .applyResult` temporarily removed, `ModerationResultApplierTest`'s two guard cases fail exactly as
  the Task 1 probe predicted: an admin `BLOCKED` message reverts to `APPROVED` (`expected: BLOCKED but
  was: APPROVED`), and a soft-deleted `PENDING` message accepts the verdict anyway. Both pass again
  with the guard restored.
- **AC2 mutation-verified.** With `MessageModerationSweeper.sweepOne`'s re-read guard removed, the
  sweeper unconditionally writes `UNDER_REVIEW` onto a message that had already resolved to
  `APPROVED` or been soft-deleted between the select and the sweep transaction opening —
  `MessageModerationSweeperTest`'s `sweep_messageAlreadyResolvedBetweenSelectAndSweep...` and
  `sweep_messageSoftDeletedBetweenSelectAndSweep...` both fail on an unwanted `save()` invocation.
  Restored, both pass.
- **AC5 mutation-verified.** Reverting `softDeleteMessage`'s `findByIdForUpdate` to `findById` makes
  `SoftDeleteIT`'s `concurrentDoubleSoftDelete_exactlyOneSucceeds_oneConflicts` fail with 2 successful
  204s instead of 1 success + 1 `409 messaging.alreadyDeleted` (`expected: 1 but was: 2`) — the lock,
  not the guard ordering, is what makes the losing caller observe the conflict. Restored, passes.
- **Correction: AC6d's premise was already false at story-creation time, found before writing `V91`.**
  The story's AC6d and Task 8(d) describe `idx_message_reports_message_id` and
  `idx_conversation_reports_conversation_id` as still present and needing a `DROP INDEX IF EXISTS` in
  the new migration. Both were **already dropped** by `V81__drop_redundant_report_indexes.sql`, which
  pre-dates this story and cites the identical justification (redundant against the leading column of
  the two `uq_*` unique constraints). `V91__messaging_moderation_recovery.sql` carries no DDL for
  AC6d — re-adding an already-applied `DROP INDEX IF EXISTS` would have been harmless but misleading
  to the next reader auditing what `V91` actually changed.
- **A pre-existing unit test, `AgeTierTransitionTest`, encoded the exact PLAYER-identity bug AC4
  fixes** — found only because `mvn -o verify` failed on it after the identity change, not by design.
  Its `sendMessage_playerRole_*` cases passed a caller id that the test treated as interchangeable
  with the player-profile id (the pre-fix bug's premise, verbatim), and its `getConversations_*`
  cases stubbed the now-dead `getMessagingPolicy` call on the read paths AC4 switched to
  `findMessagingPolicy`. Fixed alongside the identity change: 4 existing cases updated to resolve the
  caller's profile via `findByUserId` as production code now does, plus 2 new cases
  (`getConversations_playerRole_noPlayerProfile_returnsEmptyListNot404`,
  `getConversations_parentRole_orphanedPlayerProfile_excludedFromList_notThrown`) pinning the AC4
  behaviour directly. An independent confirmation, from an unrelated pre-existing test breaking, that
  the read paths previously assumed exactly the identity shape AC4 replaces.
- Full `mvn -o verify` green after every mutation guard above was restored: 0 failures, 0 errors.
  **The counts originally recorded here — "840 unit + 883 IT" — were WRONG, and wrong in exactly the
  way `skillars-deferred-15`'s correction warned about. They summed the report DIRECTORY, not the
  classes surefire ran.** `target/surefire-reports/` held 98 `.txt` files against 95 from the final
  run; all three stale ones are ITs, and `MessagingIdentityIT` (5 tests) was double-counted against
  failsafe. `target/failsafe-reports/` held 140, one being `StrandedPaymentPendingProbeIT` for a
  class that no longer exists in `src/test`. True totals were **≈816 unit + ≈881 IT**. Caught by the
  2026-08-05 code review, which re-derived them from file timestamps. The lesson `deferred-15`
  recorded was quoted in this very bullet and still not applied: filter the report files by the
  final run's timestamp, do not trust the directory listing.

## Last audit: 2026-08-06 (skillars-deferred-17 story creation)

Written while scoping `skillars-deferred-17`. Story creation set out only to verify one item —
`skillars-3-3` Group D's `canonicalTimezone` bug — before grooming a story around it. Tracing that
item's data flow through `BookingRequestPage.vue` and `booking.store.js` surfaced two live defects in
the same file that were **never tracked in this ledger at all**. The user was informed mid-investigation
(parent-facing booking-request submission, single and batch both, is currently non-functional in
production) and chose to make the newly-found breakage the anchor of `skillars-deferred-17`, with the
ledger items folded in alongside it. Every claim below comes from a direct read of the tree at commit
`8bb6c8a`, confirmed by manual data-flow tracing rather than by running the app (no reproduction harness
exists for this — see the story's Task 1).

- **New, not previously tracked here — now owned by `skillars-deferred-17` AC1.** `AvailableSlotResponse`
  (`AvailableSlotResponse.java:6-7`) serializes `startDatetime`/`endDatetime`; `BookingRequestPage.vue` and
  `booking.store.js` read `.startTime`/`.endTime` throughout (18 occurrences in the page, 4 more in the
  store) everywhere a slot object is touched. Confirmed
  by cross-reference: `WeeklyCalendar.vue:118-120,167-168` reads the sibling availability-block endpoint's
  `startDatetime`/`endDatetime` correctly, and no camelCase-transforming interceptor exists anywhere in
  `src/frontend` (grepped for `camelCase`/`transformResponse` — no hits). Effect: every rendered slot shows
  `"Invalid Date"`, and submitting sends `requestedStartTime: undefined`, which `CreateBookingRequest`'s
  `@NotNull @Future` rejects with 400. The single-booking flow cannot complete a booking today.
- **New, not previously tracked here — now owned by `skillars-deferred-17` AC2.** `git log -p` on
  `BookingRequestPage.vue` shows `submitBatchRequest()`'s call `bookingStore.submitBatch(coachId,
  playerId.value, 0)` was changed to pass `Intl.DateTimeFormat().resolvedOptions().timeZone` as the third
  argument — almost certainly an attempted fix for the same class of timezone bug `skillars-3-3` Group D
  reports, aimed at the wrong parameter. `submitBatch`'s third argument (`booking.store.js:509`) binds
  directly into `CreateBatchRequest.totalAmount` (`CreateBatchRequest.java:17`, `@NotNull BigDecimal`);
  `CreateBatchRequest`/`BatchSlot` have no `canonicalTimezone` field to receive a timezone at all, because
  `BookingBatchService.createBatch:131` already derives it server-side from `coach.getCanonicalTimezone()`
  and never needed the client to send one. Sending a zone string where Jackson expects a `BigDecimal` fails
  deserialization before validation runs. The batch-booking flow cannot complete a submission today.
- **`skillars-3-3` Group D, both bullets — owned by `skillars-deferred-17` AC3 (canonicalTimezone
  bullet) and AC4 (formatSlot bullet).** Re-verified the 2026-08-04 `STILL OPEN` annotation is still
  accurate, and additionally confirmed *why* the single-booking path is wrong while the batch path (found
  while investigating AC2 above) is already right: `BookingService.createBookingRequest` trusts
  `req.canonicalTimezone()` verbatim after only syntactic `ZoneId.of()` validation
  (`BookingService.java:181-186,253`), where `BookingBatchService.createBatch` ignores client input and
  uses `coach.getCanonicalTimezone()` (`:131`). The fix is to make the single-booking path consistent with
  the batch path that already ships correctly, not to invent new behavior.
- **`skillars-4-1` D5 — owned by `skillars-deferred-17` AC5.** Re-verified the 2026-08-04 `STILL OPEN`
  annotation is still accurate: `DrillLibraryPage.vue:288-290`'s `onMounted` still has no error branch
  around `sessionStore.fetchDrills('PLATFORM')`.
- **Examined and deliberately NOT folded in.** Whether `CoachProfile.canonicalTimezone` should itself be
  validated as a real IANA zone at profile-creation time (`ProfileBuilderStep1Request`/
  `ProfileBuilderStep4Request` are both merely `@NotBlank`) is a separate, pre-existing gap shared by both
  booking-creation paths — `skillars-deferred-17` makes the two paths *consistent*, it does not add new
  validation neither currently has. Also examined: computing a real `totalAmount` for batch submissions
  client-side, rejected as a feature (this page loads no pricing data today) rather than a bug fix — see
  the story's "Items examined and deliberately NOT folded in" for detail.
- **Not re-checked:** every other item in this file. No `deploy-*` section was read — the same gap every
  prior audit in this file has flagged, now six audits running.

**IMPLEMENTATION OUTCOME (2026-08-06, `skillars-deferred-17` dev):** All 6 ACs closed, backend `mvn -o
verify` and frontend `eslint` both green. AC1/AC2 confirmed fixed by static re-inspection (grep for
`.startTime`/`.endTime` on slot objects returns zero hits post-fix; no test framework exists for these
two — see the story's Dev Notes on why manual dev-server exercise, not an automated test, is this
project's established verification path for frontend-only fixes). AC3 was pinned with a real backend
probe (`BookingRequestResourceIT.createBookingRequest_ignoresClientTimezone_usesCoachProfileTimezone`,
sends a client `canonicalTimezone` deliberately different from the coach fixture's, asserts on the
response body value) that failed pre-fix (client's zone won) and passes post-fix (coach's zone wins) —
mutation-verified in both directions by construction, not by inspection. AC4 uncovered one story-text
inaccuracy worth recording for the next reader: `AvailabilityService.getAvailabilityCalendar` does
**not** already load a `CoachProfile` — its zone fallback (`:52`) reads only `windows.get(0)`, there is
no existing `coachProfileRepository` call in that method to reuse. Fixed by adding one new read-only
`coachProfileRepository.findById(coachId)` lookup (mirrors the endpoint's existing tolerance for an
unknown `coachId` — falls back to `"UTC"` rather than throwing, since `AvailabilityResource.getAvailability`
never validates the coach exists before calling this method). Also added
`AvailabilityResourceIT.getAvailability_windowTimezoneDivergesFromProfile_responseUsesProfileTimezone`,
seeding `coach_availability_windows.canonical_timezone` to a different zone than
`coach_profiles.canonical_timezone` and asserting the response's top-level `canonicalTimezone` follows
the profile column — the exact divergence this AC exists to prevent. Post-fix line numbers in
`BookingRequestPage.vue` drifted as expected from AC1's edits: `formatSlot()` is now at `:277`, `submit()`
at `:298`, `submitBatchRequest()` at `:318` — no `canonicalTimezone` references remain in the file
(`grep -c` confirms zero).

## Last audit: 2026-08-06 (skillars-deferred-18 story creation)

Written while scoping `skillars-deferred-18`. Like the prior story-creation audits, this one had no
independent review layers — every claim comes from a direct read of the tree at commit `f2de881`.
Scope: the four items the `skillars-deferred-17` code review deferred from `AvailabilityService` and
the coach-profile-builder write path (D2, D5, D9, D10 below). No other item in this file was re-checked.

- **Four items annotated `OWNED BY skillars-deferred-18`, each naming the AC that closes it** (they are
  deleted by that story once shipped, not now): D2 (already-booked slots visible to other
  parents/players, closed by AC1), D9 (per-window timezone using `windows.get(0)` instead of the
  window's own zone, closed by AC2), D5 (200+"UTC" for a nonexistent `coachId`, closed by AC3), and D10
  (no IANA validation on the profile-builder write path, closed by AC4).
- **All four re-verified still open and reproducible exactly as originally described** — no drift found
  between the 2026-08-06 review that raised them and this same-day story creation. Full detail of each
  re-verification is in the story file itself rather than duplicated here.
- **Deliberately not folded in:** D1 (no session-duration cap — a product decision, not a bug fix) and
  D3 (`formatSlot` hardcodes `'en'` — a systemic 4+-page i18n sweep, not an `AvailabilityService` bug),
  D4 (`AvailabilityManagerPage.vue` still reads `windows[0]`'s zone — a different bug in a different
  file, about which page reads which field, not about `AvailabilityService`'s own computation), D6
  (no frontend test coverage — blocked on the standing no-test-runner gap), D7 (`docker compose build`
  no-op — deployment/tooling, lowest priority per project convention), and D8 (reconciling the two
  `canonical_timezone` columns — needs a migration, a backfill rule, and a product decision on
  per-window zones; explicitly not a prerequisite for D9, which this story does close).
- **Not re-checked:** every other item in this file. No `deploy-*` section was read — the same gap every
  prior audit in this file has flagged, now seven audits running.

### Implementation outcome (2026-08-07, `skillars-deferred-18` shipped)

All four owned items are **closed by shipped code and deleted from this file**: D2 (AC1), D9 (AC2), D5
(AC3), D10 (AC4). A senior-dev review of the drafted story (2026-08-07) found three of the four
prescribed fixes were themselves defective before any code was written — all three were corrected in
the story text first, then implemented as corrected:

- **AC1 reuses `BookingRepository.findOverlappingBookings` instead of adding a second query.** The
  story as drafted asked for a new repository method duplicating overlap semantics that already
  existed and were already used by `BookingService.createBookingRequest` with a `null`
  `excludeBookingId` — the exact call shape this AC needed. Shipped: one call per
  `getAvailabilityCalendar` invocation, `BookingService.ACTIVE_SLOT_STATUSES` relaxed to
  package-private (same rationale as its neighbor `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED`), zero
  new queries.
- **AC2's fetch window is now padded by TWO days on each side, not left untouched.** The story as
  drafted forbade touching the outer `zoneId`'s fetch-window role; the review found that once each
  window computes its own instants in its own zone (this AC's whole point), a window far enough from
  `windows.get(0)`'s zone could have its instants fall entirely outside an unpadded fetch range —
  silently dropping that window's blocks and bookings from the fetch, reproducing AC1's exact failure
  mode through AC2. Shipped with the pad, plus a response-mapping-time filter back to the unpadded
  week so `blockResponses` (the API's `blocks` field) stays exactly week-scoped.

  **Corrected 2026-08-07 by the code review of this story, on two counts.** (1) AC2 prescribed a
  ONE-day pad, which is arithmetically too small for the divergence it exists to cover: region zones
  alone span 25h (`Pacific/Niue` at UTC-11 to `Pacific/Kiritimati` at UTC+14) and `ZoneId.of` also
  accepts fixed offsets to ±18:00 (36h), so at 24h the gap AC2 identified stayed open for the widest
  zone pairs. Widened to `minusDays(2)`/`plusDays(9)`. (2) The claim previously recorded here — that
  `AvailabilityServiceTest` gained "a regression test that fails without the pad" — was **false**.
  That test stubbed the block fetch with `any(), any()` matchers, so the mock returned the block
  whatever bounds were passed and reverting the pad changed nothing it observed; the only test that
  failed under the dev's mutation check was a separate argument-captor test that re-asserts the
  formula rather than any behaviour. Both tests were rewritten: the fetch stubs now apply the real
  queries' half-open overlap predicates, and the scenario uses a Niue/Kiritimati pair with a block
  and a booking placed beyond a one-day pad, so reverting either the pad or the per-window zone now
  genuinely fails.
- **AC4's `ProfileBuilderStep4Request.windows` gained `@Valid`, without which nothing shipped would
  have run.** The story as drafted added `@IanaTimezone` to the nested `AvailabilityWindowRequest`
  record without a `@Valid` cascade on the containing `List` — Bean Validation does not descend into
  container elements without it, so the new constraint (and the pre-existing `@NotBlank`,
  `@Min`/`@Max` on `dayOfWeek`, `@NotNull` on `startTime`/`endTime`) would all have stayed dead code
  for Step 4 payloads. Fixed to `List<@Valid AvailabilityWindowRequest>`, matching this codebase's own
  convention elsewhere. Also mandated (not merely suggested) the `CamPhoneValidator` pipe-template
  message pattern over `LangIso2`'s bare `{...}` template — `LangIso2`'s shape resolves to nothing
  without a `ValidationMessages.properties` entry this codebase has never had, which would have
  returned the literal, unresolved `{validation.timezone.invalid}` string to the client: the exact
  defect this story exists to fix, reproduced one validator later. `CoachProfileBuilderIT` proves both
  the message resolves and the three newly-activated nested constraints now return 400.
- **AC3 shipped as drafted.** `getAvailabilityCalendar` now loads `CoachProfile` once via `findById`,
  `orElseThrow`s `ResourceNotFoundException` for a nonexistent `coachId`, and reuses that same lookup
  for `coachTimezone` — no second query. A real coach with a blank/missing `canonicalTimezone` keeps
  its `"UTC"` fallback.
- **Also fixed, found during story drafting rather than during implementation:** `BookingRequestPage.vue`
  still called `bookingStore.loadParentBookings()` in `onMounted` after `bookedStartTimes` (its only
  consumer) was deleted — an orphaned network call fetching data nothing on the page used. Removed
  alongside `bookedStartTimes`.
- **Unflagged-but-intended UX change worth recording:** `BookingService.ACTIVE_SLOT_STATUSES` includes
  `REQUESTED`, so a parent's own pending request now makes a slot disappear from the list entirely
  rather than render disabled (the old `bookedStartTimes` behavior). Correct and consistent with the
  accept-path check using the same status set.
- **Residual, deliberately not closed by this story:** the availability list `BookingRequestPage.vue`
  books from is fetched once in `onMounted` and never refreshed — a booking made by another parent
  between page load and submit can still surface a `SLOT_UNAVAILABLE` error, narrower than before AC1
  but not eliminated. No new item opened for this; it is the same class as D1 above (a product-shaped
  gap, not a bug this story's scope covers).
- **No data audit of existing `canonical_timezone` rows was run**, per D10's own suggestion. AC4 guards
  new writes only. Decision recorded in the story's Dev Notes: no evidence today that a bad value is
  already stored, and the existing read-side `"UTC"` fallback/WARN already handles it if one is found
  later. Not reopened as a new item — this was D10's own suggestion, not a newly discovered gap.

## Last audit: 2026-08-24 (skillars-deferred-60 story creation)

Written while scoping `skillars-deferred-60`, at commit `a995f5d` (the tip of `master` immediately after
`skillars-deferred-59` merged). A full re-mine of the entire 1836-line file (not just the recent tail),
continuing `skillars-deferred-59`'s own full-file discipline. Every `[PICKED UP by skillars-deferred-NN
...]` tag naming a story that has since shipped was re-checked live against the current source rather
than trusted — fourteen turned out to be **already fixed, ledger not updated** (the same unannotated-fix
pattern this file's audit history has flagged repeatedly: `skillars-deferred-16`, `-34`, `-40`, `-41`,
`-43`, `-44`, `-45`, `-52`, `-56`), and are each annotated `STALE` in place below rather than duplicated
here. One item — the newest section in the file, from `skillars-deferred-59`'s own code review — was
re-verified genuinely still open and became `skillars-deferred-60`'s one Acceptance Criterion, tagged
`[PICKED UP by skillars-deferred-60 AC1]` in place.

- **Not re-checked beyond the fourteen `PICKED UP` tags and the file's newest section:** every other item
  in this file, including all older sections already flagged thin by `skillars-deferred-58`/`-59`'s own
  audits. The `deploy-*` sections were again not read — the same gap every prior full-file audit in this
  file has flagged.

---

## Deferred from: code review of skillars-deferred-80-availability-block-lock-parity-video-cascade-self-invocation-and-branding-tier-gate (2026-08-28)

- VideoDeletionService self-injection failure mode: If Spring `@Autowired @Lazy` initialization fails during bean creation, the self-reference becomes null and line 170's `self.deleteVideo()` call would NPE. However, if autowiring fails, the application won't start; this is a Spring-level concern with precedent in `RadarCompositeCalculationService`. `[DISMISSED 2026-08-29 (skillars-deferred-83 story creation): Spring-level concern, unreachable post-startup — a broken @Lazy @Autowired wiring fails the whole application at boot, not silently at runtime. Matches this exact codebase's own precedent reasoning for RadarCompositeCalculationService's identical self field.]`
- VideoDeletionService lazy-autowired initialization order: No explicit guard on self-field initialization timing. Pattern precedent exists in `RadarCompositeCalculationService.java:50-52`, confirming this pattern is established in the codebase. `[DISMISSED 2026-08-29 (skillars-deferred-83 story creation): same Spring-level reasoning as the self-injection-failure bullet above — no explicit guard is needed for a failure mode that prevents application startup entirely.]`
- VideoDeletionService self-field null check: Missing defensive null check before `self.deleteVideo()` dereference. Spring-level concern: if autowiring fails, application won't start; defensive null-check would mask configuration failure with a misleading video-deletion error. `[DISMISSED 2026-08-29 (skillars-deferred-83 story creation): same Spring-level reasoning as the self-injection-failure bullet above — a defensive null-check would mask, not fix, a startup-time configuration failure.]`

## Deferred from: code review of skillars-10-1 patches (2026-06-30)
- D1: `findBeforePivot`/`findAfterPivot` return empty context when pivot message `createdAt` is null at JPA layer — DB NOT NULL prevents this in production; only affects test fixtures that construct Message in-memory without setCreatedAt(). [MessageRepository.java:33-37]
- D2: Context window (`findBeforePivot`/`findAfterPivot`) excludes soft-deleted messages while `findAllForAdmin` includes them — chronological gap in admin message detail view with no indication; intentional spec asymmetry between views; UX concern for service layer mapping. [MessageRepository.java:33-40]

## Deferred from: code review of skillars-8-4 (2026-06-27)
- W5: `@PreAuthorize(IS_AUTHENTICATED)` on report endpoints instead of party-check annotation — consistent with module pattern; 403 preserved at service layer. Architectural note for future hardening. [`MessagingResource.java:140,151,168`] `[DECIDED 2026-09-09 (skillars-deferred-103 AC10): IS_AUTHENTICATED + MessagingReportService.verifyIsParty service-layer gate is the deliberate module-wide pattern; no reusable party-scoped method-security expression exists to swap in. Code comment added at both sites (reportMessage, reportConversation).]`

## Deferred from: code review of skillars-8-3 (2026-06-26)
- W1: TOCTOU — age policy + party checks run without a transaction before committed message save; spec-designed (NOT_SUPPORTED), window is narrow [`MessagingService.java:129-145`]

## Deferred from: adversarial code review of skillars-7-2 Group 1 DB+Entities (2026-06-24)
- D3: `SessionPackPurchase.expiresAt` mutable with no `updatable=false` — service-layer enforced via `extendPack()` business rules; open setter is a footgun [`SessionPackPurchase.java`]
- D5: `stripe_customers.last_payment_intent_id` not in AC 1 spec schema — intentional addition to support cash-out refund flow (Group 2 Decision D1 resolution); AC 1 should be updated to document this column [`V62__session_payment_credit_wallet.sql`, `StripeCustomer.java`]

## Deferred from: code review of skillars-7-1-stripe-connect-onboarding-commission-engine (2026-06-24)
- D4: `acceptBooking` fires `INITIATE_PAYMENT` → `PAYMENT_CAPTURED` state transitions without performing actual payment — pre-existing state machine flow, not introduced by Story 7.1; Story 7.2 must retrofit a failure path and prevent the state being committed before capture succeeds [`BookingService.java:203-204`]
<!-- skillars-deferred-89 code review (2026-09-01): D2 and D4 above were removed by this story's AC10 pass without any AC authorising it — restored here. They are untagged, still-open Story 7.2 follow-up work; their sibling D3 was left in place. The AC10 pass DID also delete ~34 already-closed/-tagged bullets and 7 spent section headings as ledger hygiene (the "2026-08-24 audit" delete-outright convention) — that prune is retained; only these two open items are put back. -->
<!-- skillars-deferred-100 AC7: verified stale at 8af28a42 by AC7 staleness-check — grep providerUnavailable -> src/main/java/com/softropic/skillars/platform/payment/service/StripeOnboardingService.java:46 (onboarding only, no pack-purchase path) -->
<!-- skillars-deferred-100 AC7 (2026-09-08): D2 deleted again, this time authorised — verified stale. `payment.providerUnavailable` no longer appears on any session-pack-purchase path (grep: only StripeOnboardingService.java:46,56,70 — Stripe Connect onboarding); Story 7.2's real charging shipped long ago. D3 and D4 remain: D4 (`acceptBooking` fires PAYMENT_CAPTURED without a real capture) is genuinely still open and out of scope for deferred-100. -->

## Deferred from: adversarial code review of skillars-5-6-parent-development-portal (2026-06-19)
- AD2: MapStruct not used for `Object[]` → `CoachContributionDto` mapping — MapStruct cannot transform raw JDBC Object[] projections; inherent native query limitation [`SluContributionService.java`]
- AD3: `@Testcontainers` annotation absent from IT class — tests pass (6/6); infrastructure activated via TestConfig; add annotation for explicitness in future test-hardening pass [`ParentDevelopmentPortalResourceIT.java`]
- AD4: Instancio not used in IT — FK-constrained integration seeding requires fixed IDs; project-wide IT pattern [`ParentDevelopmentPortalResourceIT.java`]
- AD5: `SecurityConstants` not used in `@PreAuthorize` — pre-existing convention in the file; new endpoint follows the same pattern as existing endpoints [`SkillExposureResource.java`]
- AD6: Triple null guard inconsistency in service — `AND coach_id IS NOT NULL` in SQL already prevents nulls; guards are over-defensive but harmless [`SluContributionService.java`]
- AD7: `BigDecimal` vs JS number comparison in `CoachContributionSection` — speculative risk only if Jackson serialization changes to string representation [`CoachContributionSection.vue`]
- AD8: Authority row `id=9612` not cleaned up in `asCoach` test — `ON CONFLICT DO NOTHING` prevents failure on re-run [`ParentDevelopmentPortalResourceIT.java`]
- AD9: Route path naming inconsistency (`/parent/players/` plural) — cosmetic; consistent with existing sibling route in the same block [`routes.js`]
- AD10: `neglectedSkillNames` shows raw skill codes if `skillDefinitions` not yet loaded — transient window; `q-inner-loading` overlays content during load [`ParentDevelopmentPortalPage.vue`]
- AD11: Dual-counter race between `loadGeneration` (page) and `_coachContributionsSeq` (store) — `finally` in `loadPortal` always fires; loading flag cannot hang [`ParentDevelopmentPortalPage.vue`]
- AD12: Mock state override inside `transactionTemplate` in percentages test — Spring Boot 3.4+ automatically resets `@MockitoBean` between test methods [`ParentDevelopmentPortalResourceIT.java`]

## Deferred from: code review of skillars-5-6-parent-development-portal (2026-06-19)
- D3: Double iteration of `rows` list in `getCoachContributions` — could use SQL window function for in-DB percentage calculation; optimisation, not a correctness issue [`SluContributionService.java`] `[AUDIT 2026-08-27: still two passes by design — buffering all rows first is required to compute per-skill totals before per-row percentages; skillars-deferred-77 AC1 consolidated the UUID conversion into one pass but kept the two aggregation passes]`
- D4: `PerformanceReportsPanel` wrapped in extra `q-card` in parent portal may produce a double-card visual artifact — requires runtime visual verification [`ParentDevelopmentPortalPage.vue`]

## Deferred from: code review of skillars-6-5-video-privacy-rbac-account-deletion-cascades (2026-06-23)
- W4: ownerId format ambiguity for mixed-type strings — Task 0 Identity Bridge investigation was a mandatory gate; deferred on assumption Task 0 was completed and format confirmed [`VideoAccessGuard.java`]
<!-- skillars-deferred-100 AC7: verified closed by AC3 at WORKTREE:src/main/java/com/softropic/skillars/platform/video/repo/VideoRepository.java:118 (version = version + 1 added; NativeModifyingVersionAuditTest guards); by AC5 at WORKTREE:src/main/java/com/softropic/skillars/platform/video/service/VideoLifecycleService.java:40 (READY removed from PROCESSING transitions) -->
<!-- skillars-deferred-100 AC7 (2026-09-08): W3 (native @Modifying bypasses @Version) closed by AC3 — audit produced (Dev Agent Record), VideoRepository.resetLifecycleLockedAt gained `version = version + 1`, RefreshTokenRepository.markAllUsedByUserId allow-listed with reason, NativeModifyingVersionAuditTest guards new cases. W5 (PROCESSING→READY backward-compat bypass) closed by AC5 — READY removed from PROCESSING in VALID_TRANSITIONS; the two legitimate reconciliation-correction callers moved to VideoLifecycleService.reconcileToReady() (no bypass counter/WARN); belt-and-suspenders ERROR+counter+throw kept on the plain path. -->
- W9: `canDelete(null, videoId)` belt-and-suspenders is a no-op for non-HTTP callers — null auth causes broad catch to swallow the re-check; `@PreAuthorize` is the primary enforceable gate; acceptable for current call sites [`VideoDeletionService.java:117`]

## Deferred from: code review of skillars-5-4-skills-radar-display-development-correlation (2026-06-19)
- W4: Skill deactivation silently drops baseline from display — `findAllByActiveTrueOrderByDisplayOrderAsc` excludes inactive skills; baseline re-appears on reactivation [`RadarDisplayService.java:39`] `[DECIDED 2026-08-30 (skillars-deferred-84 story creation): leave as-is. Confirmed with the project owner directly — this is intended design, not a bug. Deactivating a skill removes it from radar display everywhere, full stop; active is the single source of truth for what's currently tracked.]`
- W5: IT `assertThat(minimumSessionCount).isEqualTo(5L)` hardcodes config value — low risk with Testcontainers; `ON CONFLICT DO NOTHING` in V51 migration [`RadarDisplayResourceIT.java:333`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): low-risk test hygiene nit with an already-documented mitigation (Testcontainers + ON CONFLICT DO NOTHING); no live bug, not picked up.]`
- W7: `IMPROVEMENT_THRESHOLD = 3.0` hardcoded — exactly-3-point improvement classified as "no improvement"; explicitly accepted in spec dev notes; configurable in a future story [`DevelopmentCorrelationService.java:33`] `[DECIDED 2026-08-30 (skillars-deferred-84 story creation): leave hardcoded. Confirmed with the project owner directly — no concrete need has surfaced for tuning this value; making it configurable now would be speculative. Revisit only if a real need comes up.]`

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection — Round 2 Group A (2026-06-19)
- D1: V49 `CREATE UNIQUE INDEX` blocks startup if phased deploy allowed Monday batch to create duplicate flags between V48 and V49 — mitigated by same-commit deployment of both migrations; negligible in standard CI pipeline [`V49__neglected_skill_unique_open_constraint.sql`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): deploy-ordering edge case already mitigated by same-commit deployment convention; negligible in this project's standard CI pipeline, no live bug.]`

## Deferred from: code review of skillars-5-2-skill-exposure-dashboard-neglected-skill-detection (2026-06-19)
- W3: V48 `INSERT INTO platform_config ON CONFLICT (key) DO NOTHING` silently preserves wrong existing value — pre-existing migration pattern across all stories [`V48__development_exposure_dashboard.sql:43`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): pre-existing pattern used identically across every migration in this codebase, not specific to this story; not a distinct action item.]`

## Deferred from: code review of skillars-5-1-slu-engine-skill-taxonomy — Pass 2 (2026-06-18)
- D2: CallerRunsPolicy can block HTTP thread under executor saturation — prior review explicit tradeoff: AbortPolicy silently drops SLU vs CallerRunsPolicy blocks request thread [`AsyncConfig.java:40`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): explicit, deliberate tradeoff from the original review, not a bug; re-confirmed still accepted.]`
- D3: Duration rounding over/under-counts block time — prior review accepted as intentional approximation; documented in dev notes [`SluCalculationService.java:121`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): intentional approximation, re-confirmed still accepted.]`
- D4: Thread.sleep in negative-path IT tests — prior review explicitly deferred; acceptable for negative async tests with no positive signal [`SluCalculationServiceIT.java`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): explicitly deferred by the original review, re-confirmed still acceptable.]`
- D5: No booking_id stored in player_skill_stats — no DB-level idempotency anchor; behavioral gap addressed by idempotency pre-check patch; schema addition out of story scope [`V46__development_module_init.sql`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): behavioral gap already addressed by the idempotency pre-check patch; schema addition remains a deliberate scope boundary, not a live bug.]`
- D7: NUMERIC(10,4) overflow at extreme session attribute values — theoretical at realistic gameplay values with default 0.10 scales [`SluFormula.java`, `V46__development_module_init.sql`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): theoretical only at unrealistic values; re-confirmed no live risk at realistic gameplay scales.]`
- D8: SluRepository inherits deleteAll/deleteById — AC 4 met; comment warns developers; runtime override-to-throw is defense-in-depth only [`SluRepository.java`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): AC already met, defense-in-depth already in place; not a live gap.]`

## Deferred from: code review of skillars-5-1-slu-engine-skill-taxonomy (2026-06-18)
- W2: @Async executor naming ambiguity — explicit `@Async("taskExecutor")` qualifier would eliminate uncertainty; largely covered by the AsyncUncaughtExceptionHandler patch [`SluCalculationService.java:43`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): largely covered by the existing exception-handler patch; re-confirmed no live ambiguity in practice.]`
- W4: Thread.sleep in negative-path IT tests — acceptable for negative async assertions where no positive signal exists; replace with Awaitility + log spy if flakiness is observed in CI [`SluCalculationServiceIT.java:107,125,135,171`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): duplicate of D4 above; no CI flakiness observed to date, condition for revisiting not met.]`
- W5: Platform config IDs 70-72 skip 68-69 — intentional gap; no migration uses 68-69; ON CONFLICT DO NOTHING prevents failures [`V46__development_module_init.sql:51-55`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): intentional gap, re-confirmed no live risk.]`
- W6: player_id and coach_id have no FK constraints on player_skill_stats — intentional for immutable audit rows; cascading deletes would corrupt historical SLU [`V46__development_module_init.sql:19,21`] `[DISMISSED 2026-08-30 (skillars-deferred-84 story creation, ledger hygiene): deliberate design (immutable audit rows must survive a player/coach deletion), not comparable to coach_radar_preferences/player_radar_composites — those are pure derived state with no independent value, these are historical records. Re-confirmed still correct as designed.]`

## Deferred from: code review of skillars-4-5-intelligent-drill-suggestions-session-templates — Round 2 (2026-06-18)
- W4: Template name inputs missing `maxlength="200"` client-side — server `@Size(max=200)` catches it; generic error is acceptable UX [`SessionTemplateVault.vue`, `SessionBuilderPage.vue`]
- W6: `SessionTemplate.blocks` null risk if `session.getBlocks()` null — `Session.blocks` is NOT NULL in DB so sessions should never have null blocks; constraint prevents [`SessionTemplateService.java:createTemplate`]

## Deferred from: code review of skillars-4-3-custom-drill-uploads (2026-06-17)
<!-- skillars-deferred-100 AC7: verified closed by AC2 at WORKTREE:src/main/java/com/softropic/skillars/platform/video/service/ReconciliationWorkerScheduler.java:165 (sweepOrphanedProviderAssets) + src/main/java/com/softropic/skillars/platform/video/service/VideoService.java:314 (tracker.record) + src/main/resources/db/migration/V131__pending_provider_asset.sql -->
<!-- skillars-deferred-100 AC7 (2026-09-08): W3 (crash/rollback after videoService.initializeUpload orphans the provider asset — reconciliation worker cannot find it) closed by AC2. VideoService.initializeUpload / retryUpload now write a `main.pending_provider_asset` row via PendingProviderAssetTracker (REQUIRES_NEW, survives the caller rollback) the instant the Bunny asset is created, acked in the same tx as the Video persist. ReconciliationWorkerScheduler.sweepOrphanedProviderAssets() purges rows older than app.video.orphan-asset.ttl with no matching videos.provider_asset_id via videoProviderAdapter.deleteAsset() (idempotent) and writes ReconciliationIncident(ORPHANED_ASSET, …) — its first producer. Included the [Note 2026-08-27] scoping this to the crash/rollback variant. -->
- W8: AC 3 "configurable 60-min timeout" not specifically wired to drill uploads — inherits pre-existing `UploadSession.expiresAt` scheduler; not changed by this story [`platform.video` scheduler]
- W9: `@TransactionalEventListener` silently drops events if called outside a transaction — hypothetical only; `DrillUploadService` is `@Transactional` so all call paths have a transaction [`VideoPhysicalDeletionListener.java`]

## Deferred from: code review of skillars-4-1-drill-library-foundation (2026-06-17)
- D1: `session` schema name is a PostgreSQL non-reserved keyword — works on all tested PG versions; renaming after migration is written would require a destructive V40 migration [`V38__session_module_init.sql`]
- D2: V39 seed drills use `gen_random_uuid()` — non-deterministic IDs differ between environments; migration already written; deterministic UUIDs would require a V40 fix migration [`V39__session_foundation_20_drills.sql`]
- D3: Feature gate config key format relies on `tier.name()` matching DB key suffix exactly — new tier addition requires a matching migration; acceptable by convention; no compile-time enforcement [`DrillLibraryService.java:86`]
- D6: New coach with no profile gets `ResourceNotFoundException` → 404 from `getCoachIdByUserId` on private drill list — edge case; Story 4.2 to guard on the frontend; backend always requires a complete profile [`CoachProfileService.java`]

## Deferred from: code review of skillars-3-7-session-pause-resume (2026-06-16)
- D1: SSE race during in-flight pause — if remote resume (SSE `IN_PROGRESS`) arrives while local pause API is in-flight, `watch` restarts timer while `pausing=true`; UI self-corrects on next event; multi-device edge case [`ActiveSessionScreen.vue`]
- D2: SSE heartbeat handler closes/reopens EventSource unconditionally, resetting retry counter while active polling is running — can cause multi-second status gaps; pre-existing in `booking.store.js`
- D3: `elapsed` resets to 0 on component remount; `sessionStartTime` prop is never consumed to reconstruct elapsed time — accumulated active time is lost on browser refresh; pre-existing [`ActiveSessionScreen.vue`]
- D4: `completionLoading` flag shared across pause/resume/end — consumers cannot distinguish which operation is in-flight; component uses local `pausing`/`resuming` refs for buttons so user-visible impact is nil; pre-existing store design [`booking.store.js`]

## Deferred from: code review of skillars-3-6-session-completion-live-mode-quick-complete (2026-06-16)
- W3: `BookingCompletedEvent` has no retry/DLQ mechanism if listener fails after commit — infrastructure limitation, pre-existing across all event consumers [`BookingEmailListener.java`]
- W5: Auto-return after wrap-up reloads `selectedWeek` instead of current week — minor UX edge case when coach was browsing a different week [`CoachCommandCenterPage.vue:305`]
- W6: V33 migration uses hardcoded `id = 39` for `platform_config` insert — low collision risk given sequential pattern; validate before deploying to environments with manual config inserts [`V33__session_completion_data.sql:3`]

## Deferred from: code review of skillars-3-5-scheduling-views-timezone-management (2026-06-15)
- W1: Revenue gross calculation ignores variable session pricing (pack discounts, multi-session rates) — spec defines gross as `perSessionPrice × count` (AC 2), variable pricing is out of scope; revisit in a pricing-model story [ProjectedRevenueService.java]

## Deferred from: code review of skillars-3-4-booking-state-machine-sse (2026-06-15)
- Polling fallback has no exponential backoff — 2 s fixed interval is spec-prescribed degraded mode; add backoff if hammering becomes observable in production [booking.store.js]

## Deferred from: code review of skillars-2-3-coach-public-profile-page (2026-06-13)
- N+1 queries — `getPublicProfile` fires 8 sequential DB round-trips; acceptable for single-entity load now, but batch loading or `@EntityGraph` should be considered before Epic 3 traffic ramp [CoachProfileService.java] `[RE-EVALUATED by skillars-deferred-70: not a classic N+1 — getPublicProfile(coachId) is called once per single-coach page view, not once per row in a larger collection. 8 small, indexed, single-row queries for one profile view is unlikely to be the bottleneck it was originally framed as. Left open and unpicked rather than closed — a real fix (EntityGraph/batch-loading) is still reasonable if this page's latency is ever actually measured and found wanting, but should wait for that evidence rather than being done speculatively.]`

## Deferred from: code review of skillars-1-6-age-tier-enforcement-family-data-isolation (2026-06-12)
- Flyway V25 hardcoded IDs 112–114 — `ON CONFLICT (key) DO NOTHING` does not guard against PK collision if those IDs are already taken by different rows with different keys; spec explicitly verified the ID range is safe; established codebase Flyway seed pattern [V25__age_policy_config_seed.sql:1–6]

## Deferred from: code review of skillars-1-5-authentication-jwt-security (2026-06-12)
- Tests use raw `jdbcTemplate` inserts instead of Instancio for test data — project rule violation but tests are functionally correct [AuthResourceIT.java]
- `@Observed` at class level vs per-method on `AuthResource` — class-level is a valid Micrometer pattern; no metric data lost [AuthResource.java]
- `hydrated` flag in router factory is closure-scoped — SSR-unsafe but app is SPA only [src/frontend/src/router/index.js]
- Client-side `skp` clear in `auth.store.logout()` is redundant — server `logout()` already sends `Set-Cookie: skp=; Max-Age=0`; the `document.cookie` write is belt-and-suspenders [auth.store.js]

## Deferred from: code review of skillars-1-4-parent-registration-player-profiles-shadow-accounts Group 1 (2026-06-12)
- OTP hash uses `SHA-256(otp+userId)` no separator — same pre-existing pattern as CoachRegistrationService (already tracked Story 1.3 Group B D3); rate limiting is primary mitigation [ParentRegistrationService.java — hashOtp]
- `verifyEmail` saves `activated=true` before optimistic-lock check — correctly rolled back by `@Transactional`; same pattern as CoachRegistrationService; would break if called inside `REQUIRES_NEW` propagation [ParentRegistrationService.java:129–137]
- `PhoneNumber("XX")` hardcoded country placeholder — intentional per Dev Notes; same as coach flow (Story 1.3 Dev Notes) [ParentRegistrationService.java:98]
- Migration IDs 100–102 in `platform_config` — different table from V21's authority rows; `ON CONFLICT (key) DO NOTHING` is correct idempotency guard [V22__parent_player_shadow_accounts.sql]
- `dateOfBirth = LocalDate.of(1900, 1, 1)` parent user placeholder — intentional per Dev Notes; same pattern as coach (Story 1.3 Dev Notes) [ParentRegistrationService.java:102]
- Age tier snapshotted at creation, never recomputed as child ages — by design per spec; explicit consent-escalation update deferred to Story 1.6 [PlayerProfile.java; ShadowAccountService.java]
- `@Past` constraint allows 1-day-old player DOB; no minimum player age enforced — not in scope per spec; no AC addresses minimum player age [CreatePlayerProfileRequest.java:12]
- OTP rate-limit key is `userId` only — expired-OTP resubmissions drain legitimate user's budget; same pre-existing pattern as CoachRegistrationService (Story 1.3 Group B D2) [ParentRegistrationService.java:154]
- Phone-collision detection via `msg.contains("phone")` — DB-dialect fragile; same pre-existing pattern as CoachRegistrationService [ParentRegistrationService.java:100–104]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group D (2026-06-11)
- D1: `/verify-email` endpoint not rate-limited — large UUID space; already tracked Group A D6; acceptable risk [CoachRegistrationResource.java]
- D2: Rate limit consumed before user table lookup in `verifyPhone` — targeted bucket exhaustion possible; design limitation of public OTP endpoints; mitigated by per-userId keying [CoachRegistrationService.java:145–147]
- D4: SES failure during `/resend-verification` creates valid DB token with no email delivery — logged at ERROR; resend button available [CoachRegistrationEmailListener.java]
- D5: Frontend 60s cooldown resets on page refresh — UI-only throttle; server-side rate limit is authoritative [CoachEmailPendingPage.vue]
- D7: `ON CONFLICT (name) DO NOTHING` in authority seed does not protect against PK collision on `id` — already tracked Group A D4; id=100/101 safe for this project [V21__skillars_security_extension.sql]
- D8: `verifyEmail` response leaks internal userId as URL query param — already tracked Group C D1; spec-mandated (AC4); mitigated by per-userId rate limiting [CoachRegistrationService.java:142, CoachEmailVerifyPage.vue:72]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group C (2026-06-11)
- D1: userId in URL query param as tamper vector — spec-mandated design (AC4); mitigated by per-userId rate limiting (Group B P4) [CoachEmailVerifyPage.vue, CoachPhoneVerifyPage.vue]
- D2: GET with token in query string exposes token to server logs/Referer — spec-mandated endpoint design (AC4); single-use token mitigates
- D3: sessionStorage fragility / cross-device flow — architectural limitation of spec-prescribed flow; out of scope for story 1.3
- D4: useContactDetector PHONE_RE may false-positive on numeric strings in name fields — low practical risk in practice
- D5: OTP handlers reimplemented instead of reused from OtpPage.vue per spec Dev Notes — functionally equivalent; refactor candidate
- D6: useContactDetector not applied to phone field — less relevant; spec doesn't require it here
- D7: canResend read directly from err.response.data bypassing parseApiError — works correctly; architectural cleanup is future work
- D8: resendSuccess banner implies email was always sent — intentional anti-enumeration security design
- D9: auth.firstName/validation.* absent from en/index.js — false positive: app default is en-US; en falls back to en-US for these keys
- D10: --accent-warning CSS token confirmed present at _colors.scss lines 31 and 88

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group B (2026-06-11)
- D1: verifyPhone caller-supplied userId with no ownership binding — spec-required design; rate limiting is primary mitigation [VerifyPhoneRequest.java]
- D2: IP-keyed rate limiting timing oracle on /resend-verification — pre-existing RateLimitingService limitation [CoachRegistrationService.java]
- D3: OTP hash SHA-256(otp+userId) no random salt — spec-prescribed; already tracked as W1 [CoachRegistrationService.java:hashOtp]
- D4: Hardcoded DOB(1900,1,1) and Gender.OTHER placeholders persisted to DB — spec-acknowledged; cleaned up in Story 2.1 [CoachRegistrationService.java]
- D5: registerCoach returns void not CoachRegistrationResult — intentional simplification; void sufficient for current ACs [CoachRegistrationService.java]
- D6: resendVerificationEmail deletes unused tokens instead of marking used=true — deletion achieves invalidation intent [CoachRegistrationService.java:168]
- D7: Hardcoded BIGINT test fixture IDs risk TSID collision — low probability, acceptable in test-only code [CoachRegistrationResourceIT.java]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification Group A (2026-06-11)
- D1: BIGINT PK with no DB sequence — pre-existing @Tsid pattern; direct SQL inserts require manual TSID generation [V21__skillars_security_extension.sql]
- D2: verification_status unconstrained VARCHAR(20) — no CHECK constraint; pre-existing pattern for enum-backed columns [V21__skillars_security_extension.sql]
- D3: SES region hardcoded eu-west-1 in SesProperties, not overridden in application-prod.yaml — deployment config concern [SesProperties.java, application-prod.yaml]
- D4: Authority id 100/101 magic numbers — PK collision if authority sequence reaches these values; ON CONFLICT (name) DO NOTHING does not protect against PK clash with different name [V21__skillars_security_extension.sql]
- D6: verifyEmail endpoint not @RateLimited — brute-force UUID token space; Group B code [CoachRegistrationService.java]
- D7: resendVerificationEmail accepts EMAIL_VERIFIED users and re-triggers email verification instead of OTP step — flow regression; Group B code [CoachRegistrationService.java]

## Deferred from: code review of skillars-1-3-coach-account-registration-email-verification (2026-06-11)
- W1: OTP hash uses `SHA-256(otp+userId)` — 6-digit OTP space vulnerable to offline pre-computation if DB is breached; hash scheme is spec-prescribed; rate limiting on `/verify-phone` is primary mitigation [CoachRegistrationService.java:hashOtp]
- W2: `verifyPhone` accepts caller-supplied `userId` with no ownership binding — spec-required field; risk mitigated by rate limiting [VerifyPhoneRequest.java]
- W4: `BaseEntity` TSID + V21 `BIGINT PRIMARY KEY` with no sequence — direct SQL inserts in future migrations or test fixtures require manual TSID generation [V21__skillars_security_extension.sql]
- W5: `ContactDetailSanitizer.PHONE_PATTERN` may redact digit-heavy name segments (e.g. "Type 2 Analyst") — pattern is spec-prescribed; refine when real-world false positives are observed [ContactDetailSanitizer.java]
- W6: `RateLimitingService` uses in-process `ConcurrentHashMap` — not cluster-safe, no eviction; pre-existing infrastructure issue not introduced by this story
- W7: `TokenErrorResponse.errorKey` field alignment with `useErrorHandler` composable — confirm when applying patches; likely aligned by naming convention [ApiAdvice.java]

## Deferred from: code review of skillars-1-2-skillars-design-system-foundation (2026-06-11)
- W3: `app-bg` class has no boot-failure fallback in `App.vue` — boot file is the canonical owner per spec design; fallback in App.vue would duplicate logic; acceptable exceptional-case gap
- W4: `onSessionExpired` in MainLayout clears username but does not redirect to `/login` — pre-existing behaviour not introduced by this story
- W7: No CSP header coverage for `fonts.googleapis.com` — infrastructure/deployment concern outside story scope

## Deferred from: code review of skillars-1-1-feature-gate-configuration-layer (2026-06-11)
- `refreshCache()` failure after `invalidate()` causes all subsequent config gets to throw 409 instead of serving stale data during DB outage; acceptable design choice for this scope [ConfigService.java]
- Scheduled refresh + lazy TTL `ensureFresh()` can both fire near-simultaneously, causing ~2x DB polls per TTL period; minor efficiency concern, spec-designed dual-refresh pattern [ConfigService.java]
- IT test fixture hardcodes bcrypt hash for test user seed SQL; follows existing project IT test pattern [ConfigResourceIT.java:setUp]

## Deferred from: code review of deploy-3-3-external-uptime-monitoring-alert-rules (2026-06-05)
- Double notification risk if Alertmanager added later — Prometheus rules and Grafana alerting both evaluate the same infra alerts; currently no Alertmanager so only Grafana notifies, but future Alertmanager addition would cause duplicate ops notifications for every infra alert `[DECIDED 2026-09-10 (skillars-deferred-107 AC7): Grafana-managed alerting is the single delivery path; Prometheus alerts.yml rules stay non-delivering; a future Alertmanager change carries a documented 4-step checklist. See docs/deployment/monitoring.md#alerting-architecture-the-alertmanager-decision and the docker-compose.yml prometheus-service comment.]`
- node_exporter network isolation — port 9100 reachable from co-networked containers. **[AUDIT 2026-09-04: substantially narrowed by `skillars-deferred-88` AC8, not closed.** node_exporter now sits on `skillars-observability` alone (`internal: true`, no gateway attached) and publishes no host ports, so it is unreachable from off-host and a compromised prometheus/loki/tempo/redis cannot exfiltrate. But `app` and `grafana` join **both** networks by design (they need egress), so a compromised `app` container still reaches `node_exporter:9100` and its full host-level metrics. The original wording — "any compromised container" — is now wrong; the exposure is `app` and `grafana` only.]** [`docker-compose.yml` networks block] `[DECIDED 2026-09-10 (skillars-deferred-107 AC5): accept & document. Rationale corrected by that story's code review: EVERY skillars-observability member can reach node_exporter:9100 (prometheus, loki, tempo, redis, app, grafana — loki/tempo/redis are third-party), BUT the network is internal:true so only app/grafana have egress, and a compromised app already holds the DB/Stripe/Bunny/OTLP credentials — so a compromised infra container cannot exfiltrate host gauges, and app/grafana reaching them is not an escalation. A dedicated prometheus+node_exporter network was evaluated and rejected. Full rationale in the docker-compose.yml node_exporter comment. Revisit if a container with BOTH node_exporter reachability AND its own egress is added.]`

## Deferred from: code review of deploy-3-1-postgresql-backup-automation (2026-06-04)
- Credentials visible in `/proc/<pid>/environ` when `.env` is sourced — project-wide pattern, not introduced by this story `[DECIDED 2026-09-10 (skillars-deferred-107 AC6): accept as won't-fix. Mechanism corrected by that story's code review: env-guard.sh bare-sources .env with no `set -a`, so the scripts hold the creds as UNEXPORTED shell vars — NOT in their own /proc/<pid>/environ. The real surfaces are the short-lived `PGPASSWORD=… docker exec -e` child's environ and the postgres/app container envs (via `docker compose --env-file`) — all root-only, single-tenant VPS, `.env` is root:root 0600, `deploy` user has no read. PGPASSFILE evaluated and rejected. See docs/deployment/secrets-reference.md#accepted-credential-exposure-surface and the pointer comments in pg-backup.sh / restore-from-dump.sh. The ps-aux argv half WAS fixed (skillars-deferred-94 AC1).]`

## Deferred from: code review of skillars-3-9-bulk-session-request-from-calendar (2026-06-16)
<!-- skillars-deferred-100 AC7: W1 verified stale — closed by skillars-deferred-69 AC6 at 8af28a42:src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchService.java:509 (+ :407); W2 verified closed by AC4 at WORKTREE:src/main/java/com/softropic/skillars/platform/booking/service/BookingBatchStatusListener.java:31 (@Transactional REQUIRES_NEW readOnly) -->
<!-- skillars-deferred-100 AC7 (2026-09-08): W1 deleted as STALE — closed by skillars-deferred-69 AC6 (verified at HEAD 8af28a42): both writers of booking_batches.status now take batchRepository.findByIdForUpdate(batchId) under lockRetryer.withBoundedRetry and share computeBatchStatus(...) — updateBatchStatusFromBooking (BookingBatchService.java:509) and acceptAll's trailing tx (:407). W2 closed by AC4 — BookingBatchStatusListener.onBookingStatusChanged is now @Transactional(propagation = REQUIRES_NEW, readOnly = true), giving the findById lookup an explicit boundary consistent with updateBatchStatusFromBooking's own REQUIRES_NEW; the batchId != null guard is kept. -->
- W3: `parentName` null in `getParentBookings()` — pre-existing behavior, no AC requires parent name on parent's own bookings view [`BookingService.java`]
- W4: `getCoachBookingRequests` derives `parentName` from first booking in batch — data invariant guaranteed at creation; reachable only via direct DB manipulation [`BookingService.java`]
- W5: Confirm button in batch review dialog has no `hasCredits` guard — backend validates and returns error; Epic 7 will wire credit display to frontend [`BookingRequestPage.vue`] — **[AUDIT 2026-08-04: SUPERSEDED — not a defect. `BookingRequestPage.vue:249` now carries an explicit decision comment: "Do NOT gate on hasCredits: AC 3 allows booking via platform credit or full card payment." A warning banner (line 20) covers the UX. Retained only so the decision is not re-litigated.]**

## Deferred from: code review of skillars-3-8-rescheduling-duplication-reminders (2026-06-16)
- D1: `completionLoading` shared across all reschedule/duplicate store actions — consumers cannot distinguish which operation is in-flight; per-booking scoping refs partially mitigate; pre-existing [`booking.store.js`]
- D6: Service-layer tests use Mockito unit test pattern (`@ExtendWith(MockitoExtension.class)`) — story spec Task 20/21 explicitly defined unit tests; integration coverage provided by `RescheduleResourceIT` [`RescheduleServiceTest.java`, `BookingDuplicationServiceTest.java`]

## Deferred from: code review of skillars-3-10-session-pack-expiry-pause-management (2026-06-17)
- D2: `@TransactionalEventListener(AFTER_COMMIT)` failure silently loses coach cancellation notifications — if email dispatch fails after commit, the coach is never notified even though bookings are `CANCELLED`. Event delivery reliability (retry/DLQ) is an infrastructure-wide concern not introduced by this change. [`BookingEmailListener.java`, `SessionPackEmailListener.java`]

## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system (2026-06-20)
- Def2: `VideoQuotaReservation.status` as raw String vs enum — intentional per story notes to avoid JPA enum binding complexity with raw SQL paths; values DB-constrained via CHECK constraint. [`VideoQuotaReservation.java:status`]
- Def4: `commit()` no-op on already-COMMITTED is indistinguishable from not-found — `updated == 0` is logged as debug; callers cannot differentiate idempotent from non-existent handle; intentional idempotency design. [`QuotaService.java:commit`]
- Def5: `expireBatch()` exception mid-loop not caught — exception terminates the do-while; Spring `@Scheduled` catches it at the framework level; next firing will retry. [`QuotaReservationTimeoutService.java:expireStaleReservations`]
## Deferred from: code review of skillars-6-1-video-module-foundation-quota-system Run 2 (2026-06-20)
- Def9: `sumActiveReservedBytes` includes expired-but-unreaped ACTIVE rows — brief (<60s) window between expiry and reaper firing causes conservative over-reporting; intentional design. [`VideoQuotaReservationRepository.java:22`]
- Def17: `AdminVideoService.deleteVideo()` — `release()` exception inside `TransactionTemplate` kills delete transaction — pre-existing. [`AdminVideoService.java`]
- Def18: V53 platform_config IDs 117-132 hardcoded — verify against all intermediate migrations (V43–V52) before deploying; any ID conflict causes Flyway failure. [`V53__video_quota_system.sql:32-50`]
- Def19: `sumActiveReservedBytes` theoretical `ClassCastException` — PostgreSQL BIGINT SUM typically maps to Long via JDBC but no compile-time guarantee. [`VideoQuotaReservationRepository.java:22`]

## Deferred from: code review of skillars-6-2 (2026-06-22)

- Def22: `UploadSessionExpiryScheduler` releases quota outside TX then marks session EXPIRED in separate TX — non-atomic; safe because `release()` is idempotent, but ordering is fragile to future refactors. Pre-existing design decision. [`UploadSessionExpiryScheduler.java`]

## Deferred from: code review of skillars-6-3-content-moderation-pipeline (2026-06-22)

- W1: Feature flags default false in all environments — the spec explicitly documents this as the sprint completion criterion: "story is complete for sprint purposes when the placeholder compiles and the feature flag gates it off in all environments." Deployment enablement is an ops concern outside this story's scope. [`application.yaml`, `AppFeature.java`]
- W2: VideoIntelClientImpl blocking RestTemplate thread exhaustion — if VideoIntelClientImpl is ever implemented using RestTemplate (synchronous polling of a 5-minute GCP async operation), it will hold async thread pool threads for the full duration. This concern is subsumed by D2 (VideoIntelClientImpl scope decision); if D2 resolves to implement in Story 6.3, a proper non-blocking implementation must address thread exhaustion. [`VideoIntelClientImpl.java`, `VideoIntelConfig.java`]
## Deferred from: post-implementation review of skillars-6-3 (2026-06-22)

- RW1: SSE subscribe → onStatusChanged race — state transition committed between `videoService.findById()` and `emitter.send(currentStatus)` is missed. Polling fallback mitigates. Architectural limitation of SSE without event sourcing. [`VideoSseService.java:39`, `VideoEventResource.java:39`]
- RW2: scanned_at misleading on upsert retry path — `@Column(updatable=false)` retains original failed-attempt timestamp even when SLA retry overwrites outcome to PASSED. Fix requires append-only per-attempt rows (architectural scope beyond this story). [`VideoModerationScan.java:39`]

## Deferred from: code review of skillars-7-2-session-payment-lifecycle-credit-wallet (2026-06-24)
- D4: Raw `String` fields for `type` (ParentCreditLedger) and `status` (BookingPayment) instead of Java enums — DB constraint guards correctness; higher migration cost to add enum mapping

### Group 6 adversarial deferred (Tests) — 2026-06-24
- D23: Unit-level idempotency ledger-count guard (`duplicateEvent_idempotencyNoOp` verifies skip but not that ledger mock was never called) — covered by `PaymentWebhookIdempotencyIT`; low value to add unit-level assertion

## Deferred from: code review of skillars-7-3-cancellation-refund-reliability-strikes (2026-06-25)
- D1: `buildSort()` has identical branches for "price" and "rating" — pre-existing; both fall back to `displayName`; price sort is applied in Java post-enrichment [`CoachSearchService.java:buildSort`]
<!-- skillars-deferred-100 AC7: verified closed by AC1 at WORKTREE:src/main/java/com/softropic/skillars/platform/payment/service/ReliabilityStrikeService.java:85 (findByIdForUpdate under lockRetryer before the count/threshold/status decision; ReliabilityStrikeConcurrencyIT) -->
<!-- skillars-deferred-100 AC7 (2026-09-08): D4 (concurrent strike issuance race — two simultaneous issue() calls both read count=N and both fire StrikeThresholdReachedEvent) closed by AC1. ReliabilityStrikeService.issue() now takes a PESSIMISTIC_WRITE lock on the coach row via lockRetryer.withBoundedRetry(() -> coachProfileRepository.findByIdForUpdate(coachId)) before the count/threshold/status decision; the count read moved under the lock so the loser blocks, re-reads status = PENDING_REVIEW/REDUCED, and its existing guard suppresses the duplicate event. ReliabilityStrikeConcurrencyIT covers it. -->
- D5: `CoachCancellationHistory.createdAt` with `@Column(updatable=false)` + `@PrePersist` — in-memory entity is null until DB round-trip if ever used with batch `saveAll`; low risk given single-save usage [`CoachCancellationHistory.java`]

## Deferred from: code review of skillars-11-3-remove-legacy-session-pack-system (2026-08-04)
- D1: `V89__drop_legacy_session_packs.sql`'s `DROP TABLE` has no `IF EXISTS` guard — not blocking (Flyway won't re-run an applied migration, table confirmed empty at this dev/UAT stage), but there's no prior DROP TABLE in this codebase to establish a convention either way; adopt `IF EXISTS` for future destructive migrations. [`src/main/resources/db/migration/V89__drop_legacy_session_packs.sql`]
## Deferred from: code review of skillars-11-1-payment-path-parity-gaps (2026-08-03)
- D1: Partial/mismatched `confirmedCancellationIds` lets `PackSessionService.pausePack()` apply the pause even when not all currently-conflicting bookings are confirmed for cancellation (or the confirmed ids don't match any real conflict) — verified byte-for-byte identical to legacy `SessionPackService.pausePack()`; AC4 explicitly requires mirroring legacy here. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D5: `pausePack` holds a pessimistic row lock across booking cancellations and event publishing within one `@Transactional` method — same single-transaction shape as the legacy method this story mirrors. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D7: `SessionPackForfeitureScheduler` doesn't re-verify `expiresAt` immediately before forfeiting inside the per-row transaction, leaving a window where a concurrent extension could still get forfeited — inherent to the legacy-mirrored select-then-per-row-transaction scheduler shape. [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackForfeitureScheduler.java`]
- D8: TOCTOU between the conflicting-bookings query and the per-booking `cancelDueToPause` calls in `pausePack` — same risk shape as the legacy method being mirrored. [`src/main/java/com/softropic/skillars/platform/payment/service/PackSessionService.java`]
- D9: Stringly-typed computed `status` field and hardcoded `CONFLICT_STATUSES` list rather than shared enums — consistent with existing codebase convention; legacy also uses string status constants. [`src/main/java/com/softropic/skillars/platform/payment/contract/SessionPackPurchaseResponse.java`, `PackSessionService.java`]

## Deferred from: code review of skillars-deferred-15-payment-pending-sweeper-accept-path-integrity (2026-08-05)
- D2: `BookingService.acceptBooking` / `RescheduleService.acceptReschedule` call `coachProfileRepository.findByIdForUpdate` and then immediately `entityManager.refresh(lockedCoach, PESSIMISTIC_WRITE)` — two separate `SELECT ... FOR UPDATE` round trips on the same row for the same lock. Verified this is the exact idiom already established in `BookingService.createBookingRequest:201-215` (`skillars-deferred-12` AC3), which this diff was explicitly directed to mirror byte-for-byte — a pre-existing pattern, not introduced here. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:279-289`]
- D3: `BookingRepository.findPaymentPendingOlderThan`'s correctness assumes `updatedAt` reflects only the transition into `PAYMENT_PENDING`. `@PreUpdate` stamps `updatedAt` on any field change, so an unrelated write to a `PAYMENT_PENDING` booking would silently reset the stranded-booking clock and let it evade the sweep indefinitely. Verified no such write path exists anywhere in `src/main` today — speculative risk against a future path, not a live defect. [`src/main/java/com/softropic/skillars/platform/booking/repo/BookingRepository.java`]

## Deferred from: code review of skillars-deferred-16-messaging-moderation-recovery-identity-safety (2026-08-05)
- D3: AC4's orphaned-profile fail-safety is list-only, producing three different outcomes for one conversation: `getConversations` excludes it, `getMessages` returns it in full (no age-policy lookup at all — only `verifyIsParty` on `parentId`), and `sendMessage` 404s on the throwing variant. The "excluding from parent's list" ERROR log reads like an access-control decision but is not one. Resolving the inconsistency is a product call about what a parent should see for an unresolvable player, beyond AC4's stated scope. [`src/main/java/com/softropic/skillars/platform/messaging/service/MessagingService.java:108-116,168,215-228`] `[DECIDED 2026-08-29 (skillars-deferred-82 story creation): leave as-is. No live code path today can actually produce this state — nothing deletes a player_profiles row while leaving it referenced (confirmed during skillars-deferred-81 AC4's own research) — so this remains a documented, non-reachable edge case, not a fix candidate.]`

## Deferred from: skillars-uat-1-admin-bootstrap-and-onboarding-unblock (2026-08-10)

Found while implementing the story. Each was examined and deliberately left alone; none blocks UAT.

- **D2 — two of AC6's four rewritten `default` arms are unreachable and therefore untested.** `resolveLastReadAt` (`MessagingService.java:439`) and `updateLastRead` (`:456`) both sit behind a `verifyIsParty` call that throws first — `updateLastRead` runs after the guard inside `getMessages`, and `resolveLastReadAt` is reached only from the summary mapper. No caller can drive either with an unrecognised role. Changing them was still correct (all four now raise the same type, so whichever a future caller reaches first behaves identically), but `MessagingAccessControlIT.unrecognisedRole_yields403NotFatal` deliberately asserts only the two reachable arms — `MessagingService.verifyIsParty` and `MessagingReportService.verifyIsParty` — rather than faking a reachability that does not exist. Recorded so a later reviewer does not read the gap as an oversight.

## Deferred from: skillars-uat-2-session-duration-and-booking-slot-integrity (2026-08-10)

Found or deliberately left while implementing the story. None blocks UAT.

- **D3 — `AvailabilityService.computeAvailableSlots` anchors its grid per segment, which makes slot start times shift when a coach edits a block.** Intended and documented (see the method's javadoc and AC2): the post-block run starts when the coach actually becomes free, so a 12:00–12:45 block yields 12:45, 13:45, … rather than discarding the 12:45–13:00 fragment. The consequence is that adding or removing a block changes the start times of every downstream slot that day, so a parent who had a specific hour in mind may not find it after a coach edits their calendar. AC3 explicitly declines to enforce grid alignment on the write paths for this reason — an alignment rule would retroactively invalidate a booking whose slot was legal when it was made, and `RescheduleService`/`BookingDuplicationService` both write times derived from existing bookings. If UAT feedback says the shifting grid is confusing, the fix is a product decision (window-anchored grid, accepting the dropped fragments), not a bug fix.

- **D4 — the batch path still has no create-time cross-booking overlap check.** The single path has one (`BookingService.createBookingRequest`); `createBatch` does not, and AC4 deliberately left it that way. Batch rows are created `REQUESTED`, and `V87`'s exclusion constraint deliberately excludes `REQUESTED` — its own comment records that two overlapping `REQUESTED` bookings competing for a slot is expected in-band behaviour that the accept-time re-check resolves, and `acceptAll` already runs that re-check against `ACTIVE_SLOT_STATUSES_EXCLUDING_REQUESTED`. Adding a create-time check would reject legitimate competing requests. Recorded so a later reviewer does not read the asymmetry as an oversight. AC4 *did* add the intra-batch overlap check (two slots in the same batch overlapping each other), which is a different and unambiguous case.

- **D7 — `docker-compose.local.yml` now needs a `redis` override it did not need before.** AC6 replaced the `redis-data` named volume with a bind mount at `/opt/skillars/data/redis`, which is a production path. `docker-compose.local.yml` overrides the volume of every other stateful service but had never needed one for redis, so without the new `skillars-local-redis` override added here, running the local stack would have created `/opt/skillars/data/redis` on a developer's own machine. Fixed in this story; recorded because it is the general hazard of putting absolute host paths in the base compose file — any future service that moves onto the Volume needs the same treatment, and nothing enforces it.

## Deferred from: skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)

Found or deliberately left while implementing the story. The first item is an **operational finding, not a code defect, and it is the most consequential thing in this list.**

- **D3 — `CAPTURE_PENDING` has no automated exit.** A reserved row that never completes blocks the parent's cancel (AC2 returns 409 for as long as it stands), holds the coach's slot, and is refused by the sweeper. AC5 escalates it on every 15-minute sweep via `booking.payment_pending.unrecoverable{reason="CAPTURE_UNCONFIRMED"}` and Scenario 4 of `runbook.md` documents the manual resolution, but nothing times it out. **Deliberate.** Every automatic option is worse: declining could charge a parent for nothing, confirming could give away an unpaid session, and re-charging could take the money twice. Only a human can read the Stripe side. Recorded so the absence reads as a decision rather than an oversight.

- **D7 — `PaymentWebhookIdempotencyIT` seeded no `booking.bookings` rows at all.** Found in Task 0's triage; the story's regression table predicted only that it might assert "exactly N rows". The class mocks `BookingService` specifically so transitions do not need a real booking, which meant `reserveCapture`'s locked read found nothing, returned `BOOKING_NOT_PENDING`, and left zero payment rows. Fixed by seeding a real `PAYMENT_PENDING` booking in the one test that reaches Stripe — the fixture, not the check, per `uat-2`'s recorded lesson. Recorded because it generalises: **any test that drives a settlement path now needs a real booking row**, which was not true before this story, and the other two tests in that class pass only because they never reach Stripe (full credit cover and pack-funded both skip the reservation).

- **D9 — no frontend test coverage.** Standing gap (`uat-1`, `uat-2` D6, `deferred-17` D6, `deferred-18`): there is no frontend test suite in this repo. This story touches **no** `.vue` file, so unlike its two predecessors nothing here is verified by code reading alone — recorded only to note that the gap is unchanged, not that it bit this story. `[FRAMEWORK AVAILABLE 2026-09-09 (skillars-deferred-104): Vitest runner now stood up — no frontend changes in this story, so nothing to backfill; noted for completeness]` ~~there is no frontend test suite in this repo~~ `[SUPERSEDED 2026-09-10 (skillars-deferred-108 AC1–AC6): a frontend suite now exists — 12 spec files / 66 tests. The `uat-2` D6, `deferred-17` D6 and `deferred-18` cross-references above were deleted by that story's AC10 and no longer resolve; `uat-1` remains. Applied by the deferred-108 code review — AC10 specified this strike-through but it was not carried out in the implementation commit.]`

## Deferred from: code review of skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)

Adversarial code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor). 13 patch findings were resolved in the same pass; these 6 were deliberately left open. (`DisputeService`'s unguarded `findById` was independently raised by this review too — already tracked above as D5, not duplicated here.)

- **`reserveCapture`'s `REQUIRES_NEW` + `PESSIMISTIC_WRITE` opens a second pooled connection per attempted reservation, held up to the 5s `lock.timeout` under contention**, with no discussion anywhere of connection-pool sizing. A burst of concurrent settle attempts against contended booking rows is a new resource-exhaustion vector this change introduces. Speculative and load-dependent — no load test exists either way. [`src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java:73-105`]

## Deferred from: code review of skillars-uat-3-payment-capture-integrity-and-backup-retention (2026-08-11)

All 13 patch findings were applied; these are the review's own deferrals plus one item the patch round produced.

- **D11 (from the patch round, not the review) — `PaymentPendingSweeper.sweepOne`'s row lock is justified by reasoning, not by a test.** The review correctly found that `sweepOne` decided on the *absence* of a payment row and then wrote one, while `reserveCapture` concurrently decided on the absence of the same row and inserted `CAPTURE_PENDING` — so an unlocked sweeper could commit `CHARGE_FAILED` over a granted reservation (`save()` on an assigned `@Id` with no `@Version` is a `merge()`, i.e. an UPDATE, not a failing INSERT). Fixed by re-reading under `findByIdForUpdate`, the same lock `reserveCapture` takes. **The IT written to prove it was deleted for passing unchanged against the unlocked code**: both threads start on one latch, but the sweeper first reads config and runs the stranded-booking query while `reserveCapture` goes almost straight to its insert, so the reservation committed first every time and the correct outcome came from the payment-row check rather than the lock. That the six existing `PaymentPendingSweeperTest` cases all broke when the lock landed does confirm the production path changed, but a mock swap is not proof of serialisation. The lock's read-then-write window is microseconds wide and not reachable from a test at this level; proving it would need either production instrumentation (a test-only hook inside `sweepOne`) or a DB-level fault injector, both of which are their own change. Recorded rather than left implicit, because this project has now three times found a lock whose test passed without it (`deferred-13`, `deferred-15`, and this one).

- **D14 — `reserveCapture`'s `REQUIRES_NEW` + `PESSIMISTIC_WRITE` opens a second pooled connection per attempted reservation**, held up to the 5 s lock timeout under contention, with no pool-sizing analysis behind it. On the batch path that is one extra connection per credit-funded booking, taken sequentially. Load-dependent and speculative without a concurrency/load test, but it is a new resource pattern on the busiest path and should be measured before the platform carries real volume. [`BookingPaymentPersistenceService.java:73-105`]

## Deferred from: skillars-uat-5-player-self-booking story creation (2026-08-12)

Recorded per AC5 — items explicitly excluded from this story's scope, not fixed.

- **D1 — Session-pack purchase by a self-registered player.** `payment.session_pack_purchases` requires both `parent_id NOT NULL` and `player_id NOT NULL` (`V62__session_payment_credit_wallet.sql` + live entity `SessionPackPurchase.java`), and `SessionPackPaymentService.purchasePack` hard-requires `playerProfileRepository.findByIdAndParentId` ownership. Unlike the booking-ownership check this story widened, this one is not a simple XOR branch: the pack itself has no `user_id`-style self-ownership column at all. A self-booking player pays **per-session by card only** (this story's AC2); packs remain a separate, larger schema change (a new nullable `player_owner_id`-style column plus the same XOR-branch treatment `PlayerProfile` already has). [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`, `src/main/resources/db/migration/V62__session_payment_credit_wallet.sql`]

- **D2 — Credit wallet (`payment.parent_credit_ledger`) has no player-self-booking equivalent.** Keyed by `parent_id` alone with no `player_id` dimension — it is a per-parent shared balance across children, a concept that does not map onto a single self-booking adult. `CreditWalletResource` stays parent-only; a self-booking player's `effectiveCreditsRemaining` correctly reads 0/none since they will never have ledger rows. No fix needed unless/until a product decision creates a player-scoped credit concept. [`src/main/java/com/softropic/skillars/platform/payment/service/CreditWalletService.java`]

## Deferred from: code review of skillars-uat-5-player-self-booking (2026-08-12)

- **D2 — Client-side `playerId` can be overridden via `?playerId=` query param before the server-side ownership check rejects it.** `BookingRequestPage.vue`'s `playerId` computed checks `route.query.playerId` before falling back to the resolved self-player id — a player-authenticated session can construct a URL carrying someone else's `playerId`, filling out the whole form before the backend's XOR ownership check (`skillars-uat-5` AC1) rejects it server-side with no friendly client-side error. Pre-existing pattern already used identically by the parent flow (route-query-first, store-fallback), not a new gap this story introduced; the security boundary is enforced correctly server-side either way. [`src/frontend/src/pages/parent/BookingRequestPage.vue:368-378`]

## Deferred from: skillars-uat-6-coach-subscription-and-volume-backup (2026-08-13)

Recorded per AC4 — items explicitly excluded from this story's coach-subscription scope, not fixed.

- **Ops note** — `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` removed from the server's `.env.example`/`secrets-reference.md` by this story's AC8 (see below): any **already-provisioned** node's live `/opt/skillars/.env` will still carry the stale values. Harmless — nothing on the server reads them anymore after AC5/AC6 — but worth a one-line ops note for whoever next touches a live `.env` file, not a script change.

## Deferred from: code review of skillars-uat-6-coach-subscription-and-volume-backup (2026-08-13)

- **A single account holding both a parent/player role and the coach role shares one `payment.stripe_customers` row keyed only on `userId`.** A card saved under one role is silently reusable to fund a subscription under the other, with no per-role consent. This is the deliberate opaque-id design this story's own Dev Notes call out explicitly ("do not add a `payer_type` column") — not new to this story, just extended to a third role. [`src/main/java/com/softropic/skillars/platform/payment/api/SessionPackPaymentResource.java:143`] `[DISMISSED — deliberate/pre-existing, not fixed by skillars-deferred-20: deliberate opaque-id design decision, not a defect]`
- **`volume-backup.sh` has no disk-space precheck before writing a full tar archive to `/tmp`.** Identical gap exists in `pg-backup.sh`, the exact script this mirrors. [`deploy/backup/volume-backup.sh`] `[DISMISSED — deliberate/pre-existing, not fixed by skillars-deferred-20: identical gap already exists in pg-backup.sh, the script this mirrors]`
- **Removing the `attachPaymentMethod` call in `subscribeCoach` removes a redundant safety net against a saved-but-since-detached Stripe payment method.** This is the exact pattern `subscribePlayer` already ships in production; the spec explicitly directs mirroring it rather than inventing new handling here. [`src/main/java/com/softropic/skillars/platform/payment/service/SubscriptionService.java:102`] `[DISMISSED — deliberate/pre-existing, not fixed by skillars-deferred-20: mirrors subscribePlayer's already-shipped pattern per spec direction]`
- **`.env.example`/`secrets-reference.md` remove `HCLOUD_TOKEN`/`HETZNER_VOLUME_ID` from the server template, but `apply-firewall.sh` still reads `HCLOUD_TOKEN` locally on the operator's machine.** The spec explicitly scopes this out as a separate, untouched local-only concern. [`.env.example`] `[DISMISSED — deliberate/pre-existing, not fixed by skillars-deferred-20: explicitly scoped out as a separate, untouched local-operator-machine concern]`
- **`prune_volume_backups()` trusts `aws s3api`'s output shape with no independent validation.** The removed `prune_snapshots()` had a `jq -e 'has("images")'` check; identical gap exists in `prune_s3_dumps()`, the exact function this mirrors. [`deploy/backup/prune-backups.sh`] `[DISMISSED — deliberate/pre-existing, not fixed by skillars-deferred-20: identical gap already exists in prune_s3_dumps(), the function this mirrors]`

## Deferred from: code review of skillars-deferred-22-messaging-role-guard-payment-idempotency-and-resource-integrity-fixes (2026-08-14)

- **`SessionPlanService.buildResponse`'s new response-map derivation relies on `session.getBlocks()` matching the pre-save `blocks` list used to build it.** Pre-existing coupling — the pre-diff code had the same pre-save-metaMap/post-save-`session.getBlocks()` relationship; this story's refactor only changed how the drill-lookup maps are built, not this dependency. [`src/main/java/com/softropic/skillars/platform/session/service/SessionPlanService.java:186-234`]

## Deferred from: code review of skillars-deferred-24-dead-subscription-column-stripe-metadata-and-backup-guard-fixes (2026-08-15)

- **The new `GUARD_PATH` existence/readability guard block is duplicated verbatim across all 5 caller scripts** rather than factored into one shared function. Spec-directed (AC4's code block explicitly prescribes this exact per-caller shape); a DRY refactor would need its own sign-off, not a silent deviation from the story's prescribed fix. [`deploy/backup/pg-backup.sh`, `volume-backup.sh`, `restore-from-dump.sh`, `restore-from-volume-backup.sh`, `prune-backups.sh`]

## Deferred from: skillars-deferred-26-defensive-guards-input-hardening-and-test-coverage-fixes story creation (2026-08-15)

- **D1 — `DrillMetadata.repDensity` cannot represent "coach never set this" at all — it is a Java primitive `int`, not `Integer`.** Found during this story's AC4 spec audit (adversarial review of the story's own AC text, pre-implementation): a missing key in the incoming JSON payload deserializes silently to `0` via Jackson, and an explicit JSON `null` would throw a deserialization exception rather than pass through — so a `repDensity != null` guard anywhere downstream (frontend or backend) can never observe the "unset" case as `null`; it is indistinguishable from a legitimately-zero drill today and will remain so under the current contract. AC4 of this story added a frontend-only defensive guard (protects against `undefined`/`null` arriving from a stale cache, a manually-edited dev fixture, or a future API contract change) but explicitly could not close this gap — doing so needs a backend change: make `repDensity` a nullable `Integer` (propagating through `DrillMetadata`, its JSONB Hibernate mapping, and every backend site that reads it arithmetically) or add an explicit "no density data" signal, plus a decision on whether that is a real product need (do coaches uploading custom drills currently have any path that leaves `repDensity` unset, or does upload validation already require it?). [`src/main/java/com/softropic/skillars/platform/session/contract/DrillMetadata.java:12`, `src/frontend/src/components/session/DrillDetailPanel.vue`] **[AUDIT 2026-08-24: skillars-deferred-63 story creation investigated this live.** No live path constructs a `Drill` with coach-submitted metadata today — `grep -rn "new Drill(\|Drill.builder()\|new DrillMetadata(" src/main/java` finds exactly one non-test construction site, `DrillLibraryService.java:129`'s `clone.setMetadata(source.getMetadata())`, which copies an already-persisted drill's metadata rather than deserializing a fresh payload; `DrillUploadService`/`DrillUploadResource` (the only "drill upload" surface) handle the drill video file only — `DrillUploadInitiateRequest` has no `DrillMetadata` field at all. Every `Drill.metadata` is populated exclusively by migration/seed data under full app-team control. The "coach never set repDensity" scenario has no reachable trigger today — not picked up as an AC by skillars-deferred-63. Re-open if a future story adds a real coach-facing metadata-submitting endpoint.]**

## Deferred from: code review of skillars-deferred-49-reschedule-and-duplicate-current-availability-window-enforcement (2026-08-21)

- **Validation logic (fetch windows → call `isSlotWithinAvailabilityWindow` → throw `SLOT_OUTSIDE_AVAILABILITY`) is duplicated near-verbatim across three call sites** (`RescheduleService.requestReschedule`, `RescheduleService.acceptReschedule`, `BookingDuplicationService.duplicateNextWeek`) instead of being extracted into a shared helper. Matches this project's own established anti-abstraction convention for blocks this small (see `skillars-deferred-48`'s code review, which dismissed an identical DRY nit against 3 near-identical guard blocks) — not fixed here, worth revisiting if a fourth caller ever appears. [`src/main/java/com/softropic/skillars/platform/booking/service/RescheduleService.java`, `src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java`]
- **`BookingDuplicationService.duplicateNextWeek` computes `newStart`/`newEnd` as a fixed 168-hour `Instant` offset from the original booking's times; a DST transition between the original session and 7 days later can shift the duplicated slot's local wall-clock time relative to the original.** Pre-existing behavior unrelated to this story — `Instant.plus(7, DAYS)` has always been calendar-agnostic here, so a duplicate-next-week across a DST boundary has always produced a session at a shifted local hour. This story's new availability-window check (AC2) only adds a new, non-silent failure mode on top of that pre-existing quirk (an occasional clean rejection near DST boundaries) rather than introducing the shift itself. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingDuplicationService.java:56-73`] `[DECIDED 2026-08-25: accepted as a rare, low-impact edge case; no fix planned]`
- **`BookingService.isSlotWithinAvailabilityWindow` anchors both window boundaries to the proposed/accepted slot's *start* calendar date, so it can never match a coach's own overnight availability window (e.g. Mon 22:00–Tue 02:00) or a session that itself crosses midnight.** Pre-existing limitation of this shared helper, inherited unchanged from its original caller (`BookingService.createBookingRequest`) and now also reachable via two more callers this story adds (`RescheduleService`'s request- and accept-time checks, `BookingDuplicationService`). Out of scope to fix inside a story whose own Dev Notes explicitly direct reusing this helper as-is rather than modifying it. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:827-854`] `[DECIDED 2026-08-25: overnight windows remain unsupported; no fix planned]`

## Deferred from: skillars-deferred-52 story creation (2026-08-21)

Written while scoping `skillars-deferred-52`, which re-mined an older (2026-06-22 through 2026-06-30),
never-revisited section of this ledger after confirming the more recently active section
(post-`skillars-deferred-34`) is already thin per `skillars-deferred-49`/`-50`'s own creation notes. Two
items came from that older section (Def24 under `## Deferred from: code review of skillars-6-2 pass 5
(2026-06-22)`, closed by this story's AC1; D2 under `## Deferred from: code review of
skillars-10-4-gdpr-data-tools-account-deletion (2026-06-30)`, closed by AC3) — both re-tagged in place
above rather than duplicated here. This section holds only what the story-creation pass newly found:

- **Re-scoped from RW3 (`## Deferred from: post-implementation review of skillars-6-3 (2026-06-22)`):
  once state and quota release are split into separate transactions (as Def24/RW3 both asked for, and as
  AC1/AC2 now do for `VideoService.failTranscoding`/`AdminVideoService.deleteVideo`, and as an
  unannotated earlier change already did for `WebhookEventProcessorScheduler`'s `encoding.failed`/SCANNING
  branch), a release failure can no longer corrupt the state transition — but nothing retries the release
  call itself if it throws.** Four call sites now share this shape: the three above, plus
  `UploadSessionExpiryScheduler.processExpired()`, which is the only one of the four with any mitigation
  — it wraps its release call in `try { ... } catch (Exception e) { log.warn(...); continue; }`, relying
  on its own `@Scheduled` re-run to retry next cycle. The other three have no equivalent retry. Whether
  they need one is a real, undecided design question, not a mechanical fix: `QuotaService.release()`
  (`:122-137`) is idempotent (a repeat call on an already-`RELEASED`/`COMMITTED` reservation is a
  documented no-op), which suggests the *existing* webhook max-attempts/backoff machinery
  (`WebhookEventProcessorScheduler.handleFailure`) and the video-failure path's own retry surface (if
  any — not traced by this story) might already make a bare retry safe to add without new
  de-duplication logic — but whether either of those two call sites is actually re-driven by anything
  after a failure, and whether `AdminVideoService.deleteVideo` (an admin-initiated, synchronous call with
  no scheduler behind it at all) should instead surface the release failure to the caller rather than
  silently swallow it, both need a decision before a fix is written. [`src/main/java/com/softropic/skillars/platform/video/service/VideoService.java:failTranscoding`,
  `src/main/java/com/softropic/skillars/platform/video/service/AdminVideoService.java:deleteVideo`,
  `src/main/java/com/softropic/skillars/platform/video/service/WebhookEventProcessorScheduler.java:171-236`,
  `src/main/java/com/softropic/skillars/platform/video/service/UploadSessionExpiryScheduler.java:40-53`] `[DECIDED 2026-08-28 (skillars-deferred-81 story creation): leave as-is, no automatic retry infrastructure. All four call sites already fail gracefully with logging (AdminVideoService.deleteVideo's own comment documents manual retry-by-recall; the other three rely on their own scheduled re-run or accept the swallow); no live incident has been reported. Building retry infra now would be speculative engineering against a working, if imperfect, accepted pattern.]`

## Deferred from: code review of skillars-deferred-56-drill-upload-error-code-assertions-and-pack-deduction-exception-safety (2026-08-22)

- `sprint-status.yaml`'s `last_updated` field has grown into a single, unbounded YAML comment line spanning the
  cumulative history of 56+ stories — effectively unreviewable in normal diff/PR tooling and a guaranteed
  merge-conflict/diff-noise hotspot on every future story. Deferred: pre-existing repo-wide bookkeeping
  convention predating this story by dozens of prior stories, not something this one story should unilaterally
  restructure. [`_bmad-output/implementation-artifacts/sprint-status.yaml`]

## Deferred from: code review of skillars-deferred-57-gdpr-report-file-cleanup-report-generation-rate-limiting-and-stripe-customer-id-format-guard (2026-08-22)

- AC3's `V101__stripe_customer_id_format_guard_validate.sql` (`VALIDATE CONSTRAINT`) has no remediation
  path for a pre-existing non-conforming row: if any live `payment.stripe_customers` row ever failed the
  `cus_%` check, `V101` (and the deploy) would hard-fail with no backfill/quarantine step in either `V100`
  or `V101`. Story-review Finding 1 addressed this migration's lock-duration safety (the `NOT VALID`/
  `VALIDATE CONSTRAINT` split) but not this separate data-conformance risk — every current write site and
  test fixture was verified `cus_`-prefixed (see AC3's own rationale), but the table's actual live data was
  never queried, matching the same "no production DB access at authoring time" limitation Finding 1 already
  named. Deferred: no practical fix available without production DB access to verify actual conformance;
  if `V101` ever fails a real deploy, the remediation is a one-off backfill/quarantine migration for the
  specific non-conforming rows found, not a change to this story's migrations.
  [`src/main/resources/db/migration/V101__stripe_customer_id_format_guard_validate.sql`]

## Deferred from: code review of skillars-deferred-58-coach-availability-write-lock-consistency-and-pack-deduction-failure-record-transactional-safety (2026-08-24)

- `persistPaymentFailure`'s new `@Transactional(propagation = Propagation.REQUIRES_NEW)` holds two
  concurrent physical DB connections per payment-deduction failure (the suspended caller transaction plus
  this method's own new one) instead of one. Deferred: this is the same tradeoff this class's two existing
  `REQUIRES_NEW` methods (`reserveCapture`, `declineBatchBooking`) already accept, not a new risk
  introduced by this story; revisit only if connection-pool exhaustion during a real payment-gateway outage
  is ever observed in practice.
  [`src/main/java/com/softropic/skillars/platform/payment/service/BookingPaymentPersistenceService.java:206-207`]
- `deferred-work.md` and `sprint-status.yaml` accumulate indefinitely via ever-longer single lines (one
  line in each file already exceeds 40,000 characters) rather than being pruned or archived. Deferred:
  pre-existing project-wide convention followed identically by every prior story in this ledger, not
  specific to this diff; fixing it would mean changing the ledger/status-tracking convention itself across
  the whole project, out of scope for any single story.
  [`_bmad-output/implementation-artifacts/deferred-work.md`, `_bmad-output/implementation-artifacts/sprint-status.yaml`]

## Deferred from: code review of skillars-deferred-62-postgres-lock-timeout-bounded-wait-fix (2026-08-24)

- **`PessimisticLockRetryer.withBoundedRetry`'s `Supplier<T>` idempotency/side-effect-free contract is documented only in a javadoc comment, not enforced by the method signature.** Nothing stops a future caller from passing a supplier with real side effects (an external call, an event publish, a write) that would then be silently re-executed on every retry attempt. All 16 current call sites are read-only (a `findByIdForUpdate` plus optional `refresh`), confirmed by this story's own code review — speculative future-risk, not a current violation. [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java`]
- **A JDBC `setSavepoint`/rollback-to-savepoint call itself failing (e.g. genuine connection loss) propagates unretried as an opaque 500**, since `PessimisticLockRetryer` only catches `PessimisticLockingFailureException`. Arguably acceptable — this represents genuine infrastructure failure rather than lock contention — noted for awareness rather than as a defect. [`src/main/java/com/softropic/skillars/infrastructure/persistence/PessimisticLockRetryer.java:63-64,72`]

## Deferred from: code review of skillars-deferred-65-pack-selection-parity-timezone-validation-strictness-and-availability-week-scoping-fixes (2026-08-25)

- **`ZoneId.getAvailableZoneIds()` still contains DST-blind fixed-offset-equivalent forms outside `+HH:MM` notation that `@IanaTimezone`'s tightening does not reject.** AC2 tightened the validator to require `getAvailableZoneIds().contains(value)`, closing `"+01:00"`/`"GMT+2"`/`"Z"`-style forms, but the set also contains entries like `"Etc/GMT+1"` (verified: `contains("Etc/GMT+1")` is `true`) which are themselves fixed-offset, DST-blind zones under a region-shaped name — the same accepted-gap class the AC's own text already names for `"Navajo"`, just never called out for the `Etc/GMT±N` block specifically. Consistent with the tighten-only decision (an exclusion-list closing every such alias family was explicitly not what was decided), so not a defect against this story — flagged in case a future strictness pass wants a tighter allow-list (e.g. reusing `CoachProfileService`'s continent-prefix filter) instead of raw `getAvailableZoneIds()` membership. [`src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidator.java`]
- **`@IanaTimezone`'s correctness now depends on `ZoneId.getAvailableZoneIds()`'s contents, which can change across JDK/tzdata updates, with no stability-risk discussion recorded anywhere.** A `canonicalTimezone` value that validates today could in principle stop validating after a routine JDK patch that renames or drops a legacy alias. The original, looser `ZoneId.of`-only check had the same JDK-version dependency, just a laxer one — this is an architectural characteristic of the design, not something newly introduced by AC2's tightening, but worth a documented floor (or a pinned tzdata version note) if it ever causes a real incident. [`src/main/java/com/softropic/skillars/infrastructure/validation/IanaTimezoneValidator.java`]

## Deferred from: story-review and implementation of skillars-deferred-63-product-directed-fairness-and-consistency-fixes (2026-08-24)

- **A coach still cannot contest, rebut, or even view a dispute a parent already filed on a booking.** `skillars-deferred-63` AC5 gave a coach a symmetric first-raise right on a booking with no dispute yet, but `DisputeService.raiseDispute`'s `disputeRepository.findOpenByBookingId(bookingId)` check has no `raisedBy` filter — it 409s (`disputes.alreadyRaised`) on *any* open dispute regardless of who raised it — and `getDispute` 403s any caller who isn't the original raiser (`dispute.getRaisedBy().equals(requesterId)`). So a coach cannot respond to, or even read, a dispute a parent already filed against them through this API. Found during `skillars-deferred-63`'s own story-review (2026-08-24). A real fix is a genuine two-sided-dispute design question, not a mechanical change: does a second, opposing dispute on one booking need to be resolved jointly with the first? does the admin `AdminDisputeDetailDto`/UI support two open disputes on the same booking? [`src/main/java/com/softropic/skillars/platform/admin/service/DisputeService.java:76-79,107-120`] `[DECIDED 2026-08-25: keep first-raiser-wins as final; no two-sided contest mechanism planned]`
- **`CoachProfileService.saveStep4` still writes each availability window's `canonicalTimezone` from the request payload rather than from the coach's own profile, so new profile/window timezone drift can still occur.** `skillars-deferred-63` AC6 backfilled *existing* diverged rows (`V103__availability_window_timezone_backfill.sql`, closing the immediate half of the `skillars-deferred-17` D8 item above) but deliberately did not change `saveStep4`'s write behavior this round: `ProfileBuilderStep4.vue` ships a real, coach-editable per-window `TimezoneSelect` with helper copy reading "Windows above are interpreted in this timezone," and forcing `saveStep4` to silently discard that value without a coordinated frontend change would make the picker and its own helper text actively lie about what the screen does. Found during `skillars-deferred-63`'s own story-review (2026-08-24). A follow-up story needs to change both sides together: drop or make the picker read-only (e.g. "change it in Step 1"), *then* make `saveStep4` stop trusting the request's `canonicalTimezone` value. [`src/main/java/com/softropic/skillars/platform/marketplace/service/CoachProfileService.java:251`, `src/frontend/src/components/profileBuilder/ProfileBuilderStep4.vue:66-68`] `[DECIDED 2026-08-25: per-window coach timezone is a deliberate feature, not a bug; saveStep4's write behavior stays as-is; no further action planned beyond skillars-deferred-63's one-time backfill]`
## Deferred from: code review of skillars-deferred-81-parent-name-batching-cross-drill-video-lock-video-error-toast-and-self-booking-packs (2026-08-28)

Four pre-existing issues identified during code review:

- **Batch name-lookup fallback messages not localized.** `BookingService` uses hardcoded English fallback strings: "Unknown Player" (line 301), "Unknown Coach" (line 299 and multiple other sites), "Unknown Parent" (line 363). Not localized; if i18n added later, these fallbacks remain English even in German/French deployments. Story added i18n keys for videoLoadFailed toast but did not extract these fallbacks. Pre-existing localization gap; separate feature. [`src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java:multiple`] `[DECIDED 2026-08-29 (skillars-deferred-83 story creation): leave as-is. These fallbacks only render for an orphaned player_id/coach_id/parent_id reference on a booking row (no FK constraints on booking.bookings per V31__booking_requests.sql), but nothing in src/main ever deletes a player_profiles or coach_profiles row (confirmed by grep — zero hits for playerProfileRepository.delete/coachProfileRepository.delete anywhere in the codebase; GdprErasureService only anonymizes User rows). Same unreachable-orphaned-profile shape the project owner already decided "leave as-is" for the messaging module during skillars-deferred-82's own creation (line above, messaging orphaned-profile inconsistency) — applying the same precedent rather than treating this as new work.]`

- **@Transactional missing on SessionPackPaymentService.purchasePack.** Payment atomicity not guaranteed; acknowledged by story and explicitly noted as outside scope. Pre-existing; `purchasePack` was never annotated. If subsequent lines call payment gateway and persist SessionPackPurchase, concurrent failures during charge + database insert could leave inconsistent state. [`src/main/java/com/softropic/skillars/platform/payment/service/SessionPackPaymentService.java`] `[DISMISSED 2026-08-29 (skillars-deferred-83 story creation): purchasePack already has an explicit compensating-action pattern (createPurchase failure -> manual Stripe refund + PaymentGatewayException, ~lines 80-92), the correct pattern for a flow spanning an external HTTP call — wrapping the whole method in @Transactional would be counter-productive (holds a DB connection open across the Stripe call). The single DB write (sessionPackPurchaseRepository.save) already runs in its own implicit Spring Data transaction. No live atomicity bug this annotation would close.]`

- **`session.drill_video_refs.video_id` has no foreign key to `main.videos.id`.** Present since the table's own creation (`V38__session_module_init.sql`). The code review initially proposed and applied a migration (`V117__drill_video_refs_videoId_fk.sql`, `ON DELETE RESTRICT`) to close this as part of AC#3's cross-drill lock work, but it was reverted during the dev-story implementation pass (2026-08-29, confirmed with the user) for two reasons: (1) `grep`-confirmed no code path anywhere in this codebase ever issues a hard `DELETE` on a `Video` row — `VideoDeletionService`/`AdminVideoService` both only soft-transition `operationalState` to `PURGED`/`DELETED` — so the FK would close no reachable race; AC#3's own in-process pessimistic lock already fully covers the actual concurrent-request TOCTOU this story targets. (2) Applying the migration broke 4 pre-existing tests (`DrillUploadServiceConcurrencyIT.deleteVideo_concurrentCallsOnSameDrill_doesNotDoublePublishDeletionEvent` and 3 in `DrillUploadResourceIT`) that insert `drill_video_refs` rows with synthetic video ids that have no matching `main.videos` row. If a future story wants to add this FK as genuine defense-in-depth, it needs: a full audit/update of every test fixture that seeds `drill_video_refs` synthetically (at minimum the 4 named above), and a check for orphaned `video_id` values in production data before deploying the migration (an `ALTER TABLE ... ADD CONSTRAINT` on existing data fails outright if any row violates it). [`src/main/resources/db/migration/V38__session_module_init.sql`, `session.drill_video_refs`] `[DECIDED 2026-08-29 (skillars-deferred-82 story creation): keep deferring. skillars-deferred-81 AC3's in-process pessimistic lock already covers the real concurrent-request race; the FK closes no reachable bug today. Revisit only if a future change actually needs it.]`

## Deferred from: code review of skillars-deferred-87-backup-upload-verification-hardening-provision-volume-device-and-pre-volume-data-migration-ci-latest-ordering-guard (2026-08-31)

3-layer adversarial review (Blind Hunter / Edge Case Hunter / Acceptance Auditor). All 7 ACs satisfied to spec, 0 AC violations. 1 decision-needed + 7 patches handled on the story; the items below are real but pre-existing or spec-accepted design and out of this story's scope:

- **CI `:latest` freezes permanently after a `master` history rewrite.** Once the commit in `:latest`'s `org.opencontainers.image.revision` label is unreachable (force-push / rebased-away branch), `git merge-base --is-ancestor "${published_rev}" "$GITHUB_SHA"` returns 1 or 128 on *every* subsequent run, so `:latest` is never advanced again and there is no manual-override path. This is a direct consequence of AC6's explicit fail-safe design (exit ≠ 0 → publish the `sha-` tag only, never abort). `master` force-push is outside normal operations; recovery is a manual `docker buildx imagetools create`/re-tag of `:latest`. A `workflow_dispatch` "force-publish :latest" escape hatch would be a separate follow-up. [`.github/workflows/ci.yml:137-144`] `[PARTIALLY CLOSED by skillars-deferred-88 AC6 — a workflow_dispatch force_publish_latest input now force-publishes :latest for the current commit without the ancestor check (master-ref-gated: ::error:: + exit 1 on any other ref). The automatic ancestor check still cannot self-recover from a history rewrite, by design.]`

## Deferred from: code review of 1-7b-session-refresh-rint-contract-fix (2026-09-02)

<!-- The `## Deferred from: code review of 1-7-session-refresh-mechanism-fix (2026-09-02)` section
     was deleted by skillars-deferred-99 AC17 (verified stale line-for-line at master):
     rint-TTL sibling docs now say `JWT_TTL + 60s (~16 min)` absolute epoch ms
     (security-api-endpoints.md:252, frontend-integration-guide.md:1076, frontend-implementation-spec.md:243);
     `npx prettier --check src/App.vue src/boot/axios.js` passes at HEAD; `skillars-1-7`/`1-7b` are
     both `done` and all four 1-7* artifacts are git-tracked; "5 min before expiry" is now framed as
     a fixed client-side constant with an explicit ±30 s / Known-Limitations note in
     session-refresh-mechanism.md; the "story-review.md is a rotating file" process observation is
     recorded three more times later in this ledger. -->

<!-- The `sessionManager.js` state-machine "zero frontend tests" bullet was deleted by
     skillars-deferred-108 AC10 (2026-09-10). Its three named findings were closed by
     skillars-deferred-104 AC8's reference spec; the remaining clause (refreshSession
     success/failure, the deferred-90 "torn down" branch, multi-tab extension) is closed by
     skillars-deferred-108 AC5 (`src/frontend/src/plugins/__tests__/sessionManagerCoverageSpec.js`).
     The startSessionMonitoring early-return / re-arm concern is NOT closed — it is a decided item;
     see the round-2 1-7b and deferred-90 sections below (annotated by AC10, not deleted). -->

## Deferred from: code review (round 2) of 1-7b-session-refresh-rint-contract-fix (2026-09-02)

- **`startSessionMonitoring()`'s early return leaves no timer armed if the expiry navigation is swallowed.** When the first `tick()` reports an expired session, monitoring returns without arming the 30 s interval and relies entirely on `App.vue`'s `handleSessionExpired` → `router.push()`. That push is not awaited or `.catch()`ed, and Vue Router 4 rejects on an aborted/redirected navigation. If it is aborted, `cleanup()` has already reset the state to look healthy (`showWarning = false`, `timeUntilExpiry = LEGACY_SESSION_TTL`, `checkIntervalId = null`) and nothing re-arms monitoring — `startSessionMonitoring()` is only called from `App.vue` mount and `initSession()`. Deliberately left as-is rather than patched: the alternative (arm the interval anyway) makes the failure noisy instead of silent but re-dispatches `session:expired` — and therefore a backend logout call — every 30 s until navigation completes. Both options have real costs and the abort path is unverified. [`src/frontend/src/plugins/sessionManager.js:startSessionMonitoring`] `[behaviour pinned by skillars-deferred-108 AC5's characterization spec (2026-09-10); the App.vue router-abort / no-re-arm concern is unchanged and this item stays open]`
## Deferred from: skillars-deferred-90 story creation and implementation (2026-09-02)

- **`sessionManager.js` `startSessionMonitoring()`'s early-return-with-no-timer path** — left as documented (project-owner decision). See the round-2 1-7b bullet above; not re-fixed here. `[behaviour pinned by skillars-deferred-108 AC5's characterization spec (2026-09-10); decision unchanged, item stays open]`
- **The `/resend-otp` → 409 mapping is proven at the `ApiAdvice` slice level only** (AC9). No end-to-end two-connection collision harness — a sequential endpoint call can never collide and `@RateLimited` would mask it (F21).
- **Backend `messages_de` / `messages_fr` are brought to `messages_en` parity for the keys that exist at story time** (AC12). A later story that adds new `_en` keys re-opens the gap until it also adds the DE/FR rows; `MessageBundleParityTest` will fail the build if it doesn't.
- **`deferred-work.md` / `sprint-status.yaml` 40k-char single-line hygiene** — explicitly out of scope (project-owner decision 4).
- **No bulk-delete capability in the storage stack** (R1). `StorageService.java:11`, `S3StorageService.java:115-116` and `FileStorageService.java:324` are all single-key; AWS `DeleteObjects` (up to 1000 keys/call) is not wired in. Any future high-volume blob purge will want it. Still open deliberately. `[skillars-deferred-100 AC6 (2026-09-08): the bespoke PendingBlobDeletionService mini-outbox this originally pointed at is GONE — folded onto the generic platform.outbox (BlobDeletionOutboxHandler + BlobDeletionOutboxSupport). The pure DeleteObjects bulk-API gap is unchanged; AC6 did not wire it.]`

### Confirmed already-done and closed as stale by the skillars-deferred-90 senior-dev audit (2026-09-02)

The following ledger items were verified against current source and found already resolved; their bullets were deleted with this story's own closures rather than re-worked:
- `skillars-deferred-8` D2 — `*_wrongCoach_returns403` status asserts already present (`BookingRequestResourceIT.java:555, :586`).
- `skillars-deferred-18` D3 — `formatSlot` already uses `locale.value` (`BookingRequestPage.vue` → `formatInZone`); no standalone bullet remained, recorded here.
- `skillars-deferred-9` D1 + `skillars-1-2` W2 — `portal` / `auth` / `profile` / `session` stub keys: the three frontend bundles are at exact parity (de-DE 1022/1022, fr-FR 1022/1022, 0 missing / 0 extra).
- `skillars-3-3` Group A/B — N+1 in `getParentBookings`: already fully batched (`findAllById` for coach + player names, `findPendingByBookingIdIn`, `countByBatchIdIn`); no per-row effective-credit lookup exists.
- The `commissionRate` unguarded `new BigDecimal(...)` note — guarded at `StripePaymentGateway.java:43-51` (inside the `catch (IllegalStateException | NumberFormatException)`); folded into the deleted AC6 currency bullet.
- `skillars-deferred-88` fstab "no `[ -n "${VOLUME_LINK}" ]` precondition" — moot; `provision.sh:366-368` always sets a non-empty device field.
- `boot/axios.js` reference to the 401 interceptor gating only `security.sessionExpired` — the code interceptor (`:151`) already gates on both keys.

## Deferred from: skillars-deferred-91 story creation and implementation (2026-09-03)

skillars-deferred-91 closed the genuine one-off bugs (AC12–AC19), the rolling-deploy bucket (AC6–AC8), the i18n bucket (AC9–AC10), the N+1 bucket (AC11), a generic transactional outbox (AC1) wired onto the money / notification / SLU AFTER_COMMIT paths (AC2–AC4), and `CAPTURE_PENDING`'s slot-hold harm (AC5 Part A). The following residuals remain:

- **de-DE wording is AI-authored to native quality, not yet human-verified by a native German speaker.** AC9 rewrote every informal `du`/`dein…` and informal imperative to formal `Sie` across the whole `de-DE/index.js` (54 replacement groups, 0 informal forms left) and the frontend bundle parity check confirms 1022/1022 keys with 0 `{placeholder}` drift, but a native-speaker review of register/idiom is still owed. (Carried forward from skillars-deferred-90.)
- **`getPublicProfile` — leave as-is, measured.** AC11 measured `CoachProfileService.getPublicProfile` at exactly **8** JDBC round-trips, **constant** in profile size (`CoachPublicProfileQueryCountIT` doubles every collection → still 8). Every read is index-covered on `coach_id`; there is no N+1 and the endpoint is a single page view. A `JOIN FETCH` / `@EntityGraph` collapse across the six `@OneToMany` collections would risk `MultipleBagFetchException` / a cartesian row explosion for no measurable gain. The IT stays as the regression guard.
- **No frontend test framework.** ~6 recorded coverage gaps (`5-4` W9, `deferred-17`/`-18`/`-30`/`-37`/`-38`/`-43` D6) depend on standing up Vitest / Vue Test Utils. Out of scope — its own initiative. Every `.vue` / `.js` change in skillars-deferred-91 (AC14 `handleLogout`, AC9/AC10 i18n) was verified by ESLint + `quasar build` + code reading. `[FRAMEWORK AVAILABLE 2026-09-09 (skillars-deferred-104): Vitest runner now stood up (`5-4` W9 closed by its AC7); ~~the `deferred-91` `handleLogout` / i18n coverage is now in-scope for its own follow-up story~~ — **CLOSED by skillars-deferred-108 AC6 (2026-09-10)**: `src/frontend/src/composables/__tests__/useSessionSpec.js` (handleLogout order + `rint` double-clear + bounded wait) and `MainLayoutSpec.js` locale block (`changeLanguage` / `loadLanguagePreference`). The native-DE/FR register review (`:1149`) is untouched — that is not a dev-closable item.]`
- **`AFTER_COMMIT` reliability catalogue — corrected by `skillars-deferred-92` AC6 (2026-09-04).** The list below supersedes the skillars-deferred-91 version, which is now wrong in two places: it described the email listeners as merely "wired onto the outbox" (they are now `BEFORE_COMMIT`, so the enqueue is *atomic* with the business work — AC4), and it listed `ModerationSlaMonitorService` as not migrated (it now is — AC5). **On the generic outbox and enqueuing inside the producing transaction:** the refund path (`BOOKING_REFUND` — deferred-91 AC2; the enqueue moved off `CancellationRefundService`'s `AFTER_COMMIT` listeners into a dedicated `RefundEnqueueListener` `@TransactionalEventListener(BEFORE_COMMIT)` with `enqueueBookingRefund` on `Propagation.MANDATORY`, so it commits atomically with the booking `CANCELLED` write — deferred-101 AC4; the sibling `AFTER_COMMIT` listeners keep pack-restore / cancellation-history / strike issuance), transactional email (`BookingEmailListener` × 20 + `SessionPackEmailListener` × 3, all 23 now `@TransactionalEventListener(BEFORE_COMMIT)` with `enqueueEmail` on `Propagation.MANDATORY` — deferred-92 AC4/AC29), the SLU snapshot (`SnapshotPersistenceRetrier.@Recover` — deferred-91 AC4), and video-moderation retry + admin alert (`ModerationSlaMonitorService`, each enqueued inside the same `REQUIRES_NEW` chunk as the state change it describes — deferred-92 AC5). **Deliberately NOT migrated, unchanged from deferred-91:** ~~`PendingBlobDeletionService` (its own reviewed outbox; re-expressing it on the generic one is a nice-to-have)~~ `[skillars-deferred-100 AC6 (2026-09-08): DONE — PendingBlobDeletionService / PendingBlobDeletionChunkProcessor / BlobDeletionsEnqueuedEvent deleted; GdprErasureService now enqueues via BlobDeletionOutboxSupport onto the generic platform.outbox, drained by BlobDeletionOutboxHandler. PendingBlobDeletionResidualDrainRunner one-shots any residual pending_blob_deletions rows onto the generic outbox at startup. Table drop is a follow-up — see the deferred-100 AC6 follow-up bullet below.]`; `CancellationRefundService`'s `packSessionService.restoreSession(...)` calls (a pack-session-restore idempotency concern, distinct from the money refund); the registration-email listeners (`{Parent,Coach,Player}RegistrationEmailListener` — they publish inside a live transaction and work today); and the SSE / video / development / admin / reviews / session `@TransactionalEventListener` sites that are neither money- nor compliance-relevant.
- **`CancellationRefundService` credit-wallet refund via the outbox — the `uq_pcl_reference_type` partial unique index (V125) assumes one `BOOKING_REFUND` per booking across the cancellation *and* dispute paths.** `DisputeService` also writes `BOOKING_REFUND` keyed on the booking id; a booking that is both cancelled-with-refund and later dispute-refunded (not reachable today — a cancelled booking is terminal and not disputed) would collide. If a future flow legitimately double-refunds a booking, narrow the index to exclude the dispute path.

## Deferred from: skillars-deferred-92 story creation and implementation (2026-09-04)

skillars-deferred-92 closed 30 ACs: Prettier conformance + a CI gate (AC1), outbox enqueue atomicity
and executor graceful shutdown (AC2–AC5), five new migration-lint rules and the two blind spots the
conventions doc admitted (AC7–AC11), the default-message-bundle parity hole (AC12), the fr-FR idiom
pass and the frontend hardcoded-English sweep (AC13–AC14), fourteen genuine one-off bugs (AC15–AC28),
and the booking-reminder blocker (AC29). What genuinely remains:

- **de-DE wording is AI-authored to native quality, not yet human-verified by a native German speaker.**
  Unchanged and carried forward for the third story running. skillars-deferred-91 AC9 brought the whole
  bundle to consistent formal `Sie` (54 replacement groups, 0 informal forms) and parity holds at
  1083/1083 with 0 `{placeholder}` drift, but register/idiom verification needs a native speaker. **No
  dev agent can close this** — do not re-open it as an implementable item.
- **fr-FR wording is likewise AI-authored, not native-verified.** New, from AC13. That pass fixed what is
  mechanically checkable — seven accent/spelling defects, one semantic error (`Télécharger` = *download*
  on an upload button), six football-terminology errors (a "step-over" was translated as *petit pont*,
  which is a **nutmeg**), and bundle-wide consistency (coach/entraîneur, séance/session, email/e-mail,
  optionnel/facultatif). Register was already correct and was not touched: 0 informal forms before and
  after. What remains is the same class of judgement the de-DE item names: a native French speaker
  reading it as prose.
- **`skillars-4-3` W6's premise was wrong, and the correction is worth keeping** (AC17.5). That entry
  claimed a bare `@Async` falls back to `SimpleAsyncTaskExecutor` and was therefore an unbounded
  thread-creation vector. It never did here: a bean named exactly `taskExecutor` exists, so
  `AsyncExecutionAspectSupport#getDefaultExecutor` matched it at step two of its resolution. The entry was
  probably true when this codebase had fewer executor beans, which is why it is recorded rather than
  silently dropped. The real risk — correctness resting on a bean-*name* string — is closed: all 11 sites
  now carry `@Async("taskExecutor")` and `AsyncExecutorQualifierTest` fails on a new bare one.
  (project-owner decision: configurable per-market, default permissive international). `app.validation.phone`
  (`PhoneValidationProperties`) + `MarketResolver` + `PhoneRuleRegistry`; `CamPhoneValidator` rewritten
  config-driven (name kept, noted misnomer); `PhoneNumberUtil.fromString` made best-effort. Backend
  `validation.phone.{digitCount,firstDigit,operator}` neutralised + `hintDefault` added (4 bundles);
  frontend `auth.phoneHintFormat` + `auth.nationalId` reworded locale-neutral (en/de/fr). Residual:
  operator-level `Provider` detection stays CM-only.]` Found while sweeping the bundles
  for AC13. The **English** source strings said a phone number must be "exactly 9 digits", must "start with
  digit 6", and must match "(MTN, Orange, NextTel)"; `auth.nationalId` rendered as "Numéro CNI".
- **Booking rows stamped `primary_reminder_sent_at` before AC29 are known-bad history.** `V129` resets
  `secondary_reminder_sent_at` for still-future bookings (that column gates
  `findUpcomingWithin2hWindow`, so leaving it would have kept the bug alive for exactly the bookings
  closest to starting). `primary_reminder_sent_at` gates nothing and is deliberately left as the only
  record of which bookings were affected. Past bookings keep their inaccurate stamp — re-reminding
  someone about a session that already happened is worse than the missing reminder.
- **Nothing from AC4 had to stay on `AFTER_COMMIT`.** All 22 flippable listeners moved to `BEFORE_COMMIT`;
  the audit found all 28 publish sites across twelve producers already inside `@Transactional` or a
  `TransactionTemplate`, so the "fix the producer or record why" branch was never needed. Recorded because
  its *absence* is the finding: the ledger's claim that `SessionPackExpiryNotifier` is the known-bad
  transactionless producer is **stale** — deferred-15 AC6 fixed it, and its own javadoc says so.
- **AC14's sweep deliberately excluded** the brand name (`Skillars`), currency and symbol glyphs, product
  acronyms (`SLU`), `console.*` output, enum values used as lookup keys, and the illustrative figures in
  `DevelopmentCorrelationPanel`'s blurred upsell teaser (one targeted `eslint-disable` with a reason).
  `vue/no-bare-strings-in-template` is now an **error** in `eslint.config.js`, so a new bare string fails
  the build rather than joining a backlog.
- **AC15 found no live security-surface difference.** `requestMatchers(String...)` resolves to
  `PathPatternRequestMatcher` only when a unique `PathPatternRequestMatcher.Builder` bean exists
  (spring-security-config 6.5.11, `AbstractRequestMatcherRegistry:201-231`), which a context-free test
  cannot see. `AppEndpointsConventionTest` now asserts both candidate semantics agree on every pattern
  across 11 probe paths — they do — so the guarantee holds whichever Spring picks. If they ever diverge
  that assertion fails and names the pattern.
- **The new lint rules bind `V128+`, not `V122+`.** Flyway checksums whole files, so `V122`–`V127` are
  applied and cannot be edited to carry the markers `MISSING_LOCK_TIMEOUT` / `UNBATCHED_DML` /
  `DROP_WITHOUT_PRIOR_RELEASE_PREP` / `PLATFORM_CONFIG_EXPLICIT_ID` demand. `MigrationLint` carries a
  second constant (`DEFERRED_92_BASELINE`) for this. The pre-production trigger for `V60`/`V94`/`V117`
  in `docs/deployment/migration-conventions.md` is unaffected.

---

## Deferred from: code review of skillars-deferred-92 (2026-09-04)

_bmad-code-review Chunk 1 (backend reliability). Three `[Review][Defer]` findings — real but pre-existing / cross-chunk / low-impact, not blockers._

**Two `[Patch]` findings from the same chunk were dismissed as false positives on 2026-09-04.** Recorded here, with the evidence, purely so a later review does not re-file them — this file has six recorded instances of an item being re-raised after it was already settled. Neither is deferred work; there is nothing to do.

- **"`ExecutorShutdownConfigurationTest.pools()` leaks 15–20 live-thread pools"** — it does not create any threads. `ThreadPoolTaskExecutor.initialize()` only constructs a `ThreadPoolExecutor`; core threads start on the first `execute()`, or eagerly only when `prestartAllCoreThreads` is set, which defaults to `false` (spring-context 6.2.19, `ThreadPoolTaskExecutor:99,310`) and which no config here sets. The pools receive no tasks. Verified against the sources jar, not inferred.
- **"`ReportGenerationService.generateReport()` NPEs on a null `coachUserId`"** — unreachable. `PerformanceReportResource:33` is the only production caller and passes `securityUtil.getCurrentCoachUserId()`, which throws `InsufficientAuthenticationException` for a principal with no business id and otherwise returns a parsed `long` (`SecurityUtil:199-209`). A null guard there would be dead code implying a caller that does not exist.

A third, on `BandwidthResetService`'s chunk-limit ERROR, was half right: the proposed replacement condition (`chunks == MAX_CHUNKS && lastReset > 0`) is logically identical to the `i == MAX_CHUNKS - 1` test it was meant to replace, since the loop breaks on the first empty chunk. The real defect beside it — the ERROR and an unconditional `"reset complete"` INFO both firing on the same run — was fixed in the story.

---

## Deferred from: code review of skillars-deferred-92, chunk 2 (2026-09-04)

_bmad-code-review Chunk 2 (migration ordering / lock-timeout lint, AC7–AC11). Two `[Review][Defer]` findings. The chunk's other 24 findings are patch items in the story file, not deferred work._

- **`V129` does not address the rolling-deploy window it runs in.** The migration resets `secondary_reminder_sent_at` for still-future `UPCOMING` bookings so the never-sent 2-hour reminders are re-picked. During the rollout itself, old pods still run the un-annotated `onBookingReminder` and the old scheduler, so any booking they re-stamp between this `UPDATE` and the last old pod terminating is silently re-lost — the same rows the migration exists to rescue. The header's "bounded and idempotent by construction" is true of the statement and says nothing about the window. No action now: the header records that the project has no production deployment, and the remedy is a repeat run after the first real rollout completes, not a code change. Worth doing before or immediately after that rollout. `src/main/resources/db/migration/V129__reset_never_sent_secondary_reminders.sql:20`
---

## Deferred from: code review of skillars-deferred-92, chunk 3 (2026-09-04)

_bmad-code-review Chunk 3 (i18n, AC12–AC14). Three `[Review][Defer]` findings. The chunk's other 24 findings are patch items in the story file._

- **Parent-facing legal copy in all three frontend bundles is AI-authored and unreviewed.** `parentTosBody`, `parentPrivacyBody` and `parentConsentBody` (terms, privacy and guardian-consent text a parent must scroll and accept) were extracted and translated into de-DE and fr-FR during AC14 without human legal or native review. A mistranslated guardian-consent clause is a compliance exposure, not a copy nit, and no dev agent can close it. Same shape as the carried-forward de-DE native-speaker residual: **AI-authored to native quality, not human-verified.** `src/frontend/src/i18n/fr-FR/index.js:913`
---

## Deferred from: code review of skillars-deferred-93 (2026-09-05)

- **[DECIDED 2026-09-11 (skillars-deferred-109 AC12 / owner D5)] `VideoModerationEmailListener` blank-recipient behaviour is a documented decision, not a gap.** The original bullet ("silent drop, no send attempt, no ERROR") was materially stale at HEAD. The blank `platform.admin_alert_email` path now has: a `@PostConstruct checkAdminAlertConfig()` that logs `log.error("STARTUP: …")` and throws `IllegalStateException` ("STARTUP ABORTED") when `ARACHNID_ENABLED` is on; a per-send `log.error("… admin alert NOT sent")` in `adminAlertEnvelope()`; and a **deliberate documented decision** in `sendAdminAlertSync` NOT to retain the outbox row for an unset config key ("no number of re-drives fixes an unset config key, and a retained row would occupy a claim slot until a human noticed"). That behaviour is kept as-is by owner decision D5. `skillars-deferred-109` AC12.1 additionally split the read-back into explicit `persisted == null` (→ WARN "not yet visible") and `persisted == SENT` (→ INFO "delivered") branches — a single trailing `log.info` had been mislabelling a null read-back as a delivery. [`src/main/java/com/softropic/skillars/platform/notification/infrastructure/listener/VideoModerationEmailListener.java`]
- **[DECIDED 2026-09-11 (skillars-deferred-109 AC12)] the retryable/permanent send mapping is now covered.** `VideoModerationEmailListenerTest.RealMailManagerMappingAC122` drives a real `MailManager` (real Resilience4J circuit breaker + `RetryTemplate`) over a stubbed `MailService` (the seam — `@MockitoBean JavaMailSender` does not work because `MailService` gets its sender from `SenderProvider.nextSender()`), and asserts on the produced `EnvelopeEntity` row: a retryable send failure → `FAILED, isRetry=true` → `sendAdminAlertSync` rethrows; a permanent failure → `FAILED, isRetry=false` → it logs `UNDELIVERABLE` and returns. Scope: the send-mapping + listener-decision seam; the durable outbox-row retain/delete lifecycle is `ModerationAdminAlertOutboxHandler`'s and is covered by `ModerationOutboxIT`. The old bullet's note about `NotificationEmailOutboxAtomicityIT` using `TestMailManager` is correct and is why the seam was moved to `MailService`.

## Deferred from: skillars-deferred-104 implementation (2026-09-09)

skillars-deferred-104 stood up the frontend unit-test runner (Vitest + `@vue/test-utils`,
`happy-dom`), the opt-in `frontend-unit-tests.yml` CI job, and two reference specs (a `mount()`
component spec + a pure-logic `sessionManager` spec). It also deleted the former
`## Backlog:` section for this initiative outright per this file's
delete-when-closed convention, and annotated every dependent coverage-gap line above with
`[FRAMEWORK AVAILABLE 2026-09-09 (skillars-deferred-104): …]` (`skillars-5-4` W9 deleted — closed
by AC7; `deferred-90`/`-98` `sessionManager` note marked partially addressed by AC8). The
residual:

- **The Quasar Vitest App Extension is used library-only, not registered via `quasar ext add`.**
  The vitest-3.x-compatible AE line (`@quasar/quasar-app-extension-testing-unit-vitest@1.x`) pins
  its index runner to `@quasar/app-vite` `>=2.0.0 <2.4.0`; this repo is on `2.4.1`, so registering
  the extension in `quasar.config.js` would fail the compatibility check on every `quasar dev` /
  `quasar build` — including `mvn verify`'s `npx quasar build`. `vitest.config.mjs` is therefore
  the AE's scaffold placed by hand, and `quasar.config.js` is untouched. When the repo next bumps
  `@quasar/app-vite` to v3 / `quasar` to 2.24+, revisit: migrate to AE `3.x` and consider
  `quasar ext add` proper so the config can be derived from `quasar.config.js` directly rather than
  via `vite-tsconfig-paths` reading `.quasar/tsconfig.json`. [`src/frontend/vitest.config.mjs`,
  `src/frontend/package.json`]

## Deferred from: skillars-deferred-100 implementation (2026-09-08)

skillars-deferred-100 closed the last verified-genuine dev-closable reliability gaps in this ledger:
the reliability-strike threshold race (`skillars-7-3` D4 → AC1), orphaned video-provider-asset
reconciliation (`skillars-4-3` W3 → AC2), the native/`@Modifying` × `@Version` audit (`skillars-6-5`
W3 → AC3), the `BookingBatchStatusListener` transactionless lookup (`skillars-3-9` W2 → AC4), the
`PROCESSING→READY` backward-compat hole (`skillars-6-5` W5 → AC5), and the `PendingBlobDeletionService`
→ generic-outbox consolidation (AC6). It also deleted two stale lines (`skillars-3-9` W1 — closed by
`skillars-deferred-69 AC6`; `skillars-7-1` D2 — `payment.providerUnavailable` no longer on any
pack-purchase path). The follow-up it owes:

- **Drop `main.pending_blob_deletions` + `PendingBlobDeletion` entity/repo +
  `PendingBlobDeletionResidualDrainRunner` in a LATER release.** skillars-deferred-100 AC6 stopped
  writing the table (folded onto the generic `platform.outbox`) but kept the entity/repo so
  `PendingBlobDeletionResidualDrainRunner` (a startup `ApplicationRunner`) can migrate any rows a
  prior release left behind onto the generic outbox — verified as the residual-drain path (the
  one-shot re-enqueue, not a retained bespoke scheduler). `DROP TABLE` in the same release that
  stops using it is exactly the `DROP_WITHOUT_PRIOR_RELEASE_PREP` hazard
  `docs/deployment/migration-conventions.md` + `MigrationLint` exist to prevent, and `IF EXISTS`
  does not help a concurrent old-release drain mid-transaction. Once deferred-100 is confirmed
  deployed and `pending_blob_deletions` is provably empty in every environment: `DROP TABLE
  main.pending_blob_deletions` (guarded, `IF EXISTS`, its own migration `> V131`), delete
  `PendingBlobDeletion` / `PendingBlobDeletionRepository` / `PendingBlobDeletionResidualDrainRunner`.

## Deferred from: code review of skillars-deferred-100-concurrency-reconciliation-and-lifecycle-hardening (2026-09-08)

Second-run bmad-code-review of the implementation. Pre-existing / explicitly-descoped items only —
the actionable patch findings are tracked unchecked in the story's Review Findings section.

- **[DECIDED 2026-09-11 (skillars-deferred-109 AC14): accepted. No grace path or config-gated allowance will be built. Mitigation is operational — see the runbook pre-production release gate. Revisit only if a production deploy is planned with queued legacy events that cannot be drained.]** No grace path for in-flight legacy `encoding.success` webhook events during the deferred-100 cutover. With `PROCESSING→READY` removed from `VALID_TRANSITIONS` and the plain path converted from `log.warn` to `throw TerminalStateViolationException` + `video.moderation.bypass++`, any replayed/queued pre-deploy event that drives `PROCESSING→READY` on the plain path now throws and dead-letters (and raises a false-positive moderation-bypass alarm) instead of completing. AC5 verified the producer (`WebhookEventProcessorScheduler`) is already gone at HEAD, no production deploy has ever happened, and dead-lettered events are re-drivable. `docs/deployment/runbook.md` now carries a `## Pre-production release gate: queued webhook events` section requiring the queue to be drained or discarded before the first production deploy. No production code change. [`VideoLifecycleService.java:77-83`]

## Deferred from: skillars-deferred-101 story creation (2026-09-08)

### Pre-production migration rebaseline (future task, no owner)

**Before the first production deploy**, while the schema still carries no data: squash `V1`..`V<current>` into a single clean baseline migration and fold in every safe-pattern rewrite the lock-unsafe applied files (V60/V94/V97/V98/V117, see `docs/deployment/migration-conventions.md#known-lock-unsafe-applied-migrations`) could not take in place. Removes the frozen-file constraint entirely and lets `MigrationLint` bind from `V1`. Large, disruptive, must be its own story; only viable pre-data.

---

## Last audit: 2026-09-08 (skillars-deferred-101 story creation + implementation)

**Scope**: Full re-mine against HEAD (`b41d39a8`) of the "genuine one-off bugs & gaps" class declared effectively exhausted by deferred-100. Confirmed: the remaining bucket is overwhelmingly `[DECIDED]`/`[DISMISSED]` (do not re-litigate), "no dev agent can close this" (native DE/FR register review, parent legal copy), two carved-out backlog stories, speculative/load-dependent notes, and the migration `ACCESS EXCLUSIVE` lock class (frozen files).

**Bullets deleted this audit** (each verified closed against HEAD before removal, per this file's
delete-outright convention — no `[CLOSED by …]` tag left behind):

- `skillars-3-1` "no date-range guard on `weekStart`" (+ its now-empty `## Deferred from: code review
  of skillars-3-1-...` header) — verified already closed at HEAD:
  `AvailabilityResource.validateWeekStartRange` rejects dates outside a configurable
  `booking.availability.weekStartRangeYears` window. Pre-existing guard (not added by deferred-101);
  the bullet was simply stale.
- The `deploy-2-2` "`Fail workflow` unreachable" row in the 2026-09-04 `deploy-*` audit table, and the
  `deploy-1-3` "Prometheus has no `depends_on: app`" row in the same table — the `deploy-1-3` *bullet*
  under `## Deferred from: code review of deploy-1-3-...` was also removed (closed by deferred-101
  AC7). `deploy-2-2` verified closed by deferred-99 AC6 (`.github/workflows/deploy.yml:216-226`
  reachable in every failure mode).
- Six migration `ACCESS EXCLUSIVE` / unbatched-DML bullets across five migrations — `skillars-6-6`
  W3 (V60), the `skillars-uat-3` code-review V94 bullet + its D13 twin, `skillars-deferred-33`
  (V97, header emptied and removed), `skillars-deferred-40` (V98, header emptied and removed),
  `skillars-deferred-84` (V117, header emptied and removed). All consolidated into
  `docs/deployment/migration-conventions.md#known-lock-unsafe-applied-migrations` (deferred-101 AC12),
  which is the single authoritative list; the rebaseline future task above tracks the remediation.
- Bullets closed by deferred-101 implementation: `skillars-10-2` D1 (refund-drop — enqueue now
  atomic via `RefundEnqueueListener` BEFORE_COMMIT, AC4; header emptied and removed);
  `skillars-7-2` Group 3 D11 (`getActiveCoachTier` → 404, AC5); `deploy-3-4` "DROP DATABASE may
  fail" (pg_terminate_backend sweep, AC6); `skillars-4-5` Round-2 W5 (`createTemplate` sets
  `error.value`, AC8); `skillars-deferred-1` D1 (`isInValidRange` boundary tests + `requireCurrentUserId`
  401 IT, AC10) and D2 (`ConfigGuardIT` try/finally isolation, AC9; header emptied and removed);
  the `skillars-deferred-100` code-review bullets for `BookingBatchStatusListener` (AC1),
  `VideoService.retryUpload` orphan (AC3), `reconcileToReady` no-op incident (AC2), and
  `RefreshTokenRepository.markAllUsedByUserId` allow-list (AC11 — `version = version + 1` added,
  method moved to the compliant set in `NativeModifyingVersionAuditTest`, so
  `ALLOWED_WITHOUT_BUMP` is now empty and the "unenforceable invariant" is closed).

**Reconstruction check**: after removal, every surviving non-blank line in this file was matched
against the pre-prune file (git `HEAD` of this file at `b41d39a8` + the deferred-101 additions),
in order, with nothing reworded or reordered — the only differences are the deleted bullets above
and the four now-empty `## Deferred from:` headers removed with them. `[DECIDED]` / `[DISMISSED]`
bullets: none touched (24 `DISMISSED` + 10 `DECIDED` remain, same as before). `[PICKED UP by …]`
bullets: none touched.

**Items re-verified still open** (not picked up, not re-fixed, citations confirmed to still resolve):
- `deploy-3-1` awscli v1 from Ubuntu apt — ~~real, open~~ **CLOSED by skillars-deferred-102 AC9**
  (official v2 installer, signature-verified, in `provision.sh`).
- `deploy-1-3` LGTM `mkdir -p` gated inside the `[ -b ]` check — ~~real, open~~ **CLOSED by
  skillars-deferred-103 AC12 (2026-09-09):** confirmed stale — `mkdir -p "${DEPLOY_ROOT}/lgtm"` and
  `data/postgres` are created unconditionally at `provision.sh:349`, before the `[ -b ]` gate at
  `:567`. The bullet under `## Deferred from: code review of deploy-1-3-...` was deleted and its
  now-empty section header removed.
- `deploy-1-5` repo cloned as root into `/opt/skillars` beside runtime data — ~~real, open~~
  **CLOSED by skillars-deferred-102 AC6** (checkout moved to `/opt/skillars/app`, owned by a
  dedicated non-root `deploy` user; `/opt/skillars/data` is now a sibling, not a child).
- `deploy-1-5` repo cloned before the Volume is mounted — ~~real, open~~ **CLOSED by
  skillars-deferred-102 AC7** (checkout is outside `${MOUNT_POINT}`; a `mountpoint -q` assertion now
  guards the data-dir writes).
- `skillars-8-1` D2 N+1 in `getConversations` — ~~real, open~~ **CLOSED/measured by
  skillars-deferred-102 AC14**: `getConversations` is already O(1) queries (batched by deferred-90
  AC13 + deferred-91 AC19); `MessagingConversationsQueryCountIT` pins it.
- `skillars-deferred-100` code review: the legacy `encoding.success` webhook grace-path bullet
  (accepted cutover risk, kept above)

---

## Last audit: 2026-09-08 (skillars-deferred-102 implementation)

skillars-deferred-102 ("Deploy & Backup Resilience + Cross-Module Bug Sweep") shipped 19 ACs. It
absorbed the never-filed `skillars-deferred-97` stub (its four backup/deploy-smoke items became
AC1/AC2/AC4/AC5) — `skillars-deferred-97` is `withdrawn` in `sprint-status.yaml`.

**Bullets deleted this audit** (each verified against HEAD before removal, per this file's
delete-outright convention — no `[CLOSED by …]` tag left behind):

- `deploy-3-4` code review — "APP_CID capture races container registration" → **AC2**
  (bounded retry loop around the `docker compose ps -q app` capture in `restore-from-dump.sh`).
- `deploy-3-1` code review — "awscli v1 from Ubuntu apt … `--endpoint-url` edge cases" → **AC9**
  (official AWS CLI v2 installer, GPG-fingerprint-pinned + signature-verified fail-closed).
- `## Deferred from: code review of deploy-1-5-… (2026-06-04)` header + "Repo cloned to
  `/opt/skillars` before Hetzner Volume mounted" → **AC7** (header emptied and removed).
- `## Deferred from: code review of deploy-1-5-… (2026-06-03)` header + both bullets ("Repo cloned
  as root into `/opt/skillars`", "git clone root … contains the volume data subdirectory") → **AC6**
  (header emptied and removed). Decision **D3**: closed via a dedicated non-root `deploy` user owning
  a `/opt/skillars/app` checkout that is a *sibling* of `/opt/skillars/data`, not "accept + document".
- `## Deferred from: code review of skillars-deferred-37-…` header + its sole bullet
  (`batchAcceptResultsByBatch` unbounded growth on a failed-refresh streak) → **AC17** (LRU cap of
  200 entries, evicted on every write; header emptied and removed).
- `## Deferred from: code review of skillars-deferred-88-…` header + intro + its sole bullet
  (single-Volume + unresolvable `HETZNER_VOLUME_ID` warns-then-falls-back) → **AC8** (hard-fail
  regardless of Volume count; header + intro removed).
- `skillars-deferred-87` code review — "Pre-Volume migration verify is stat-only … `postgres/` …
  never verified" → **AC3**. The `postgres/`-not-verified half was already false at HEAD (the `-ni`
  dry-run covers the whole tree); AC3 added `--checksum` to that dry-run for the torn-same-size-file
  residual. The section's other bullet (CI `:latest` freeze) is untouched.
- `skillars-deferred-94` code review — "`restore-from-dump.sh` empty `APP_CID`" → **AC2**;
  "`pg-backup.sh` leaks a truncated dump" → **AC1** (trap registered before the pipeline);
  "Repo cloned as root directly into `/opt/skillars` … Needs a tracked deploy-hardening follow-up"
  → **AC6** (this story *is* that follow-up). The section's hardcoded-UID-chown and
  actuator-health bullets are untouched.
- `## Deferred from: code review of skillars-deferred-96 (2026-09-07)` — both bullets ("Deploy smoke
  poll window ~55s" → **AC4**; "Deploy smoke SSH failures swallowed to `echo 0`" → **AC5**). Section
  emptied and removed.
- `skillars-7-1` code review D3 (unbounded `VARCHAR` on `stripe_webhook_events.event_id`) → **AC10**
  (`V132`, `VARCHAR(255)` with a bounded `lock_timeout`). D4 (`acceptBooking` fires
  `PAYMENT_CAPTURED` without a real capture) **stays** — genuinely open, its own payment-architecture
  concern.
- `skillars-3-6` code review W1 (JPQL `'COMPLETED'` literal in `findPendingQuickCompletes`) → **AC12**.
  Citation had drifted — the literal at HEAD was `'COMPLETED_PENDING_CONFIRMATION'`; the concern
  (hardcoded status string in JPQL) is closed regardless (`SessionStatus` / `BookingStatus.name()`
  bound params).
- `skillars-4-1` code review D7 (`listPrivateDrills` no explicit `library_type = 'COACH'` filter)
  → **AC13**.
- `skillars-1-2` code review W6 (rapid double-click theme-toggle desync) → **AC16** (`toggleTheme`
  now returns the applied state; the caller no longer re-reads the DOM; a sync re-entrancy latch).

**Also updated (not deleted):** the `## Last audit: 2026-09-08 (skillars-deferred-101 …)` block's
"Items re-verified still open" list — the `deploy-3-1` awscli, both `deploy-1-5` clone bullets and
`skillars-8-1` D2 entries are struck through and annotated as closed by this story; the `deploy-1-3`
LGTM `mkdir -p` entry carries a note that its "gated inside `[ -b ]`" premise looks stale at HEAD.

**Not folded in / stays deferred:** ~~completion-gated coach payout (`deferred-91` AC5 Part B — decision
**D2**, its own future story)~~ (shipped by `skillars-deferred-106`, 2026-09-09 — separate charges &
transfers, `coach_payouts` ledger `V133`–`V135`, `CoachPayoutTransferHandler`); ~~the frontend
test-framework initiative backlog~~ (stood up by
`skillars-deferred-104`, 2026-09-09); the pre-production
migration rebaseline; the `main.pending_blob_deletions` table drop; `deploy-3-3` Alertmanager /
node_exporter; `deploy-3-1` `/proc/<pid>/environ`; `deploy-1-3` LGTM `mkdir -p`; and every wont-fix
decision bullet — none touched (13 `[DECIDED …]` + 27 `[DISMISSED …]` tagged bullets, same as master).

**Reconstruction check:** every surviving non-blank line matches the pre-edit file (master @
`31982170`), in order, with nothing reworded or reordered — the only differences are the bullets
listed above, the three now-empty `## Deferred from:` headers removed with them, the strike-through
annotations added to the deferred-101 audit block's still-open list, and this block. Wont-fix
decision bullets (`[DECIDED …]` / `[DISMISSED …]`): none touched. **`[PICKED UP by …]` bullets: five
removed** — the four `[PICKED UP by skillars-deferred-97 …]` bullets (that stub is now *withdrawn*
and absorbed here) and the one `[PICKED UP by skillars-deferred-94 AC9]` clone-as-root
data-subdirectory bullet — all five are the items AC1/AC2/AC4/AC5/AC6 actually closed, not
speculative claims by a still-pending story. No other `[PICKED UP]` bullet touched. One known
residual inconsistency: two `<!-- skillars-deferred-100 AC7 … -->` HTML comments in the `skillars-7-1`
section still say "D3 and D4 remain" — left as historical audit-trail comments rather than edited.

---

## Last audit: 2026-09-08 (post-skillars-deferred-102-merge prune)

Two bullets that `skillars-deferred-102` closed in code but its AC19 ledger-hygiene pass missed —
deleted here per this file's delete-outright convention (no `[CLOSED by …]` tag left behind):

- `skillars-7-5` code review **D1** ("Running balance incorrect when two `ParentCreditLedger`
  entries share an identical `createdAt` … straddle a page boundary") — **closed by
  `skillars-deferred-102` AC11**. The cited method `sumByParentIdAndCreatedAtBefore` no longer
  exists; it was replaced by `sumByParentIdBeforeAnchor(parentId, createdAt, txId)` with the
  compound `(l.createdAt < :createdAt OR (l.createdAt = :createdAt AND l.txId < :txId))` predicate
  the bullet asked for, plus a `, l.txId DESC` tiebreaker on the page query, guarded by
  `CreditStatementRunningBalanceAnchorIT` + `RevenueReportingServiceTest`. Header emptied and
  removed.
- `skillars-6-6` code review **W5** ("`@GeneratedValue(AUTO)` on `VideoApprovalRequest` … Hibernate 6
  AUTO may allocate a sequence-based Long for a non-Long PK") — **stale**. At HEAD the entity carries
  `@GeneratedValue(strategy = GenerationType.UUID)` (not `AUTO`), so the concern is moot;
  `skillars-deferred-102` AC15 verified this and recorded it as a no-op in the story rather than
  editing the ledger. Header emptied and removed.

**Reconstruction check:** every surviving non-blank line matches the pre-edit file (master @
`768a513a`), in order, with nothing reworded or reordered — the only differences are the two bullets
above, the two now-empty `## Deferred from:` headers removed with them, and this block.
`[DECIDED …]` / `[DISMISSED …]` bullets: none touched. `[PICKED UP by …]` bullets: none touched.

## Last audit: 2026-09-09 (skillars-deferred-103 story implementation + AC12)

`skillars-deferred-103` closed twelve verified-open items. Each AC deleted its own ledger bullet(s);
AC12 additionally removed the stale `deploy-1-3` bullet and ran this pass.

**Bullets deleted (closed by the code that shipped in `skillars-deferred-103`):**

- `## Deferred from: code review of deploy-1-3-lgtm-observability-stack (2026-06-03)` — the sole
  bullet (`LGTM data mkdir -p gated inside [ -b ]`). Premise false at HEAD: `mkdir -p
  "${DEPLOY_ROOT}/lgtm"` is unconditional at `provision.sh:349`. **Header removed** (section empty,
  bullet untagged).
- `## Deferred from: adversarial code review of skillars-7-2 Group 2 Service Layer (2026-06-24)` —
  **D3** (`createTier` TOCTOU — closed by AC1: DB partial unique index + 409 mapping + IT) and
  **D1** (`onBookingAccepted` `existsById` bare SELECT outside TX — stale; idempotency is now
  `isSettled()` + `hasReservation()` inside `@Transactional(REQUIRES_NEW)`). **Header removed**
  (both bullets gone, both untagged).
- `## Deferred from: adversarial code review of skillars-7-2 Group 1 DB+Entities (2026-06-24)` —
  **D1** (`parent_credit_balance` view returns no row for a zero-history parent). Closed by AC11:
  `docs/dev-docs/payment/index.html` now documents "absence = zero" for both the JPQL and native
  paths, and `CreditWalletZeroHistoryBalanceIT` pins the `getBalance` guarantee. D3, D5 kept;
  header kept.
- `## Deferred from: code review of skillars-7-3-cancellation-refund-reliability-strikes
  (2026-06-25)` — **D3** (`/coaches/me/strikes` unbounded). Closed by AC2: paginated `Page<>`
  response + `size` clamp; covered by `ReliabilityStrikePaginationIT`. D1, D5 kept; header kept.
- `## Deferred from: code review of skillars-11-1-payment-path-parity-gaps (2026-08-03)` — **D2**
  (silent `.orElse(null)`/`.orElse("")` for missing coach/parent), **D3** (`pauseStartDate` past
  check truncates to UTC day), **D4** (`pack.pause.maxDays` no defensive default). Closed by
  AC5/AC6/AC7. D1, D5, D7, D8, D9 kept (project-owner decision D1 = safe subset only); header kept.
- `## Deferred from: code review of
  skillars-deferred-15-payment-pending-sweeper-accept-path-integrity (2026-08-05)` — **D1**
  (`SessionPackExpiryNotifier` stamps `expiryWarnedAt` before an `AFTER_COMMIT` send). Verified
  already fixed by `skillars-deferred-92` (BEFORE_COMMIT + `enqueueEmail`/`MANDATORY`); AC4 was a
  no-op confirmation. D2, D3 kept; header kept.
- `## Deferred from: code review of skillars-deferred-56-… (2026-08-22)` — the
  `handlePackBasedBooking` bare-`RuntimeException` bullet. Closed by AC3 (+ code-review resolution):
  catch narrowed to `PaymentGatewayException | TransientDataAccessException`; genuine bugs
  propagate. Covered by `CreditRoutingTest`. The `sprint-status.yaml` `last_updated` line-length
  bullet kept (project-wide, out of scope); header kept.
- `## Deferred from: code review of skillars-deferred-66 (2026-08-25)` — the imperative-"retry"
  message bullet (its only bullet). Closed by AC8: throw sites use
  `BookingError.CONCURRENT_MODIFICATION_MESSAGE`; `booking.concurrentModification` reworded
  in-place across `messages{,_en,_de,_fr}.properties` + the three frontend `index.js` bundles.
  **Header removed** (section empty, bullet untagged).

**Retagged in place (not deleted):**

- `## Deferred from: code review of skillars-8-4 (2026-06-27)` **W5** — AC10 shipped as
  documentation-only (option b), so the bullet is kept and tagged `[DECIDED 2026-09-09
  (skillars-deferred-103 AC10)]`. Header kept.

**Prior audit-block references updated:** the `2026-09-04` `deploy-*` re-audit table row and the
`2026-09-08 (skillars-deferred-101 …)` "Items re-verified still open" list both had their
`deploy-1-3` LGTM entry struck through and annotated closed.

**Not folded in / still deferred:** two new pre-existing items surfaced by this story's own code
review are filed below (`pack.pause.maxDays ≤ 0`; `SessionPackExpiryNotifier` stale javadoc).

**Reconstruction check:** every surviving non-blank line matches the pre-edit file (master @
`d0be17e8`), in order, with nothing reworded or reordered — the only differences are the deleted
bullets enumerated above, the three now-empty `## Deferred from:` headers removed with them
(`deploy-1-3-lgtm-observability-stack`, `skillars-7-2 Group 2 Service Layer`, `skillars-deferred-66`),
the two strike-through annotations in prior audit blocks, the W5 retag, and this block.
`[DECIDED]` / `[DISMISSED]` bullets: none touched. `[PICKED UP by …]` bullets: none touched.

## Last audit: 2026-09-10 (post-merge prune — deferred-104 / -105 / -106)

Targeted prune only (not a full re-mine — the 2026-09-08 deferred-101 re-mine and the 2026-09-09
deferred-103 pass remain the last full audits). Three stories merged after deferred-103:
`skillars-deferred-104` (frontend test runner — already has its own section below/above),
`skillars-deferred-105` (CI build-warning cleanup — no ledger items), and `skillars-deferred-106`
(completion-gated coach payout).

**Bullets deleted (verified closed against HEAD before removal, per this file's delete-outright
convention — no `[CLOSED by …]` tag left behind):**

- `## Deferred from: skillars-deferred-91 …` — **"Completion-gated coach payout — its own story"**
  (AC5 Part B). Shipped by `skillars-deferred-106`: Stripe **separate charges & transfers**,
  `coach_payouts` ledger (`V133__coach_payouts_and_commission_rate.sql`, `V134`, `V135`),
  `CoachPayoutTransferHandler` / `CoachPayoutReversalHandler` / `CoachPayoutEnqueueListener` /
  `CoachPayoutOutboxSupport`, `CoachPayout` entity + repo. Owner sign-off on
  `payout-and-capture-pending.md` D1–D5 recorded in `sprint-status.yaml` (hold_hours=48, no
  post-completion cancel, revenue on `coach_payouts.RELEASED`). The other four deferred-91 residuals
  (de-DE native review, `getPublicProfile` measured, no-frontend-framework, `AFTER_COMMIT` catalogue,
  `uq_pcl_reference_type`) are untouched; header kept.
- `## Deferred from: code review of deploy-3-1-postgresql-backup-automation (2026-06-04)` —
  **"PGPASSWORD exposed via `docker exec -e`"**. Closed by `skillars-deferred-94` AC1 (shipped
  2026-09-07): every call site now uses `PGPASSWORD="…" docker exec -e PGPASSWORD "$CID"` env-var
  inheritance (verified at HEAD: `deploy/backup/pg-backup.sh:40`,
  `deploy/backup/restore-from-dump.sh:139,142,145,151,160`) — the secret is no longer an argv token
  visible in `ps aux`. The sibling **"Credentials visible in `/proc/<pid>/environ`"** bullet is a
  distinct, still-open project-wide concern and stays; header kept.

**Prior audit-block references updated (annotated, not deleted):** the `2026-09-04` `deploy-*`
re-audit table's PGPASSWORD row is struck through and marked CLOSED; the
`2026-09-08 (skillars-deferred-102 …)` block's "Not folded in / stays deferred" list has its
completion-gated-coach-payout entry struck through and annotated shipped by `skillars-deferred-106`.

**Reconstruction check:** every surviving non-blank line matches the pre-edit file (master @
`918ff206`), in order, with nothing reworded or reordered — the only differences are the two deleted
bullets above (no `## Deferred from:` header emptied — both sections retain other bullets), the two
strike-through annotations in prior audit blocks, and this block. `[DECIDED]` / `[DISMISSED]`
bullets: none touched. `[PICKED UP by …]` bullets: none deleted (the deleted PGPASSWORD bullet's
`[PICKED UP by skillars-deferred-94 AC1]` tag went with it as a genuine closure).

## Last audit: 2026-09-10 (skillars-deferred-107 story implementation)

Not a full re-mine — the 2026-09-08 `deferred-101` re-mine and the 2026-09-09 `deferred-103` pass
remain the last full audits, and this session's earlier post-104/-105/-106 prune (block above) is the
immediate baseline. `skillars-deferred-107` shipped the two `deferred-103` code-review items and five
long-deferred `deploy-*` items; this block records the ledger consequences.

**Decision items — accept & document (retagged `[DECIDED]` in place, per the file's decided-retention rule):**

- `## Deferred from: code review of deploy-3-3-…` — **"node_exporter network isolation"** →
  `[DECIDED 2026-09-10 (skillars-deferred-107 AC5)]`. No topology change; the `docker-compose.yml`
  `node_exporter` comment now carries the full won't-fix rationale (first-party `app`/`grafana` peers
  only; a compromised `app` already holds every provider credential; dedicated network evaluated and
  rejected).
- `## Deferred from: code review of deploy-3-3-…` — **"Double notification risk if Alertmanager
  added later"** → `[DECIDED 2026-09-10 (skillars-deferred-107 AC7)]`. Decision written into
  `docs/deployment/monitoring.md` (new **"Alerting architecture — the Alertmanager decision"**
  section): Grafana-managed alerting is the single delivery path; a future Alertmanager change
  carries a 4-step checklist. `docker-compose.yml` prometheus-service comment replaced with a pointer.
- `## Deferred from: code review of deploy-3-1-…` — **"Credentials visible in `/proc/<pid>/environ`"**
  → `[DECIDED 2026-09-10 (skillars-deferred-107 AC6)]`. No script logic change; `docs/deployment/
  secrets-reference.md` gains an **"Accepted credential-exposure surface"** section and `pg-backup.sh`
  / `restore-from-dump.sh` gain a one-line pointer comment near the `.env` load.

**Bullets deleted (genuine fixes shipped by `skillars-deferred-107`, per this file's delete-outright
convention — no `[CLOSED by …]` tag left behind):**

- `## Deferred from: code review of skillars-deferred-103-… (2026-09-09)` — **both** bullets:
  **"`pack.pause.maxDays` misconfigured `≤ 0`"** (fixed by AC1 — `PackSessionService.pausePack` now
  reads `configService.getBoundedLong("pack.pause.maxDays", 90L, 1L, 3650L)`; AC2 generalised the
  bullet's own closing sentence — *"pre-existing pattern across many `ConfigService.getLong` call
  sites"* — into a codebase-wide sweep of all 31 non-`ConfigService` `getLong`/`getInt` sites through
  the range-bounded accessors; AC3 added the `ConfigStartupAssertion` fail-fast boot check) and
  **"`SessionPackExpiryNotifier` class javadoc is stale"** (fixed by AC4 — the "Delivery." paragraph
  now describes the current `BEFORE_COMMIT` + outbox-enqueue wiring, keeping the pre-`deferred-92`
  history as one clearly-marked sentence). The section had only these two bullets — **header removed**.
- `## Deferred from: code review of skillars-deferred-94 (2026-09-07)` — **"Hardcoded container-UID
  `chown` values are never verified against the image's real runtime UID"** (`[PICKED UP by
  skillars-deferred-94 AC2: accepted mitigation for now]`). Fixed by AC8: `provision.sh` and
  `restore-from-volume-backup.sh` now probe each service's image for its real runtime uid
  (`image_runtime_ugid` → `chown_probed`), keeping the numeric constants as a WARN-on-mismatch
  fallback; redis keeps its deliberate gid-1000 override (probe the uid only). The section keeps its
  actuator-health bullet — **header kept**.

**Prior audit-block references struck through + annotated (not deleted, matching the `deploy-1-3`
LGTM-row precedent in the `2026-09-04` table):** in the `2026-09-04 (…first-ever deploy-* re-audit)`
table — `deploy-3-4` "DROP DATABASE / open connections" → CLOSED by `deferred-101` AC6; `deploy-3-4`
"APP_CID capture race" → CLOSED by `deferred-102` AC2; `deploy-3-3` "double notification if
Alertmanager added" → DECIDED by `deferred-107` AC7; `deploy-3-3` "node_exporter network isolation"
→ DECIDED by `deferred-107` AC5; `deploy-1-5` "`git clean` vs the data subdirectory" → CLOSED by
`deferred-102` AC6/AC7. (The `deploy-3-4` "hardcoded container UIDs 65534/10001/472" row and its
duplicate bullet under `## Deferred from: code review of deploy-3-4-…` were left untouched — the AC8
fix closes the underlying issue but the story's ledger instructions scoped the deletion to the
`deferred-94`-review re-filing only; flagged for a future prune.)

**Reconstruction check:** every surviving non-blank line matches the pre-edit file (master @
`918ff206` + this session's earlier post-104/-105/-106 prune), in order, with nothing reworded or
reordered. The only differences are: the three `[DECIDED 2026-09-10 …]` tags appended to existing
`deploy-3-3` / `deploy-3-1` bullets; the three deleted bullets above (one `## Deferred from:` header
removed — the now-empty `deferred-103` review section; the `deferred-94` review section keeps its
other bullet); the five struck-through rows in the `2026-09-04` audit table; and this block.
`[DISMISSED]` count: unchanged. `[DECIDED]` count: +3 (the three retags above). `[PICKED UP by …]`
bullets touched: one — the `deferred-94` hardcoded-UID `chown` bullet, deleted as a genuine closure.

## Deferred from: code review of skillars-deferred-107 (2026-09-10)

- **AC3's "do not hand-list the templated key strings" is violated in letter.** `ConfigBounds.java` hand-lists `VIDEO_QUOTA_TIER_SEGMENTS` and `VIDEO_TYPE_SEGMENTS` rather than iterating `CoachSubscriptionTier` / `VideoType`. Deferred: the documented reason — keeping the `config` module free of a dependency on `video.contract` / `marketplace.contract` — is sound, and `ConfigBoundsEnumCoverageTest` supplies exactly the drift guard the AC was reaching for (it fails if a new enum constant lands without a matching bound). Needs project-owner sign-off only because `story-review.md` deliberately hardened that wording from "enumerate or skip" to "must iterate". `[DECIDED 2026-09-10 (skillars-deferred-108 AC9): accept the hand-listed segments as-is. Iterating the enums would give the `config` module a compile dependency on two business modules; `ConfigBoundsEnumCoverageTest` is the (one-directional) drift guard — it catches an added/renamed constant with no matching bound, not a removed constant leaving a harmless stale segment. `ConfigBounds.java`'s comment reworded to say so. No behaviour change. (This bullet's cite was also shortened from `ConfigBounds.java:180-181` to `ConfigBounds.java` — the line numbers had gone stale. Recorded here by the deferred-108 code review because the AC10 reconstruction check claimed nothing was reworded beyond the enumerated retag.)]`

## Last audit: 2026-09-10 (post-merge prune — deferred-107 follow-up, hardcoded-UID row)

Targeted single-item prune, closing the loop the `2026-09-10 (skillars-deferred-107 story
implementation)` block above explicitly left open ("The `deploy-3-4` 'hardcoded container UIDs
65534/10001/472' row and its duplicate bullet … were left untouched … flagged for a future prune").

**Deleted (closed by shipped code — `skillars-deferred-107` AC8, per this file's delete-outright
convention):**

- `## Deferred from: code review of deploy-3-4-operational-documentation-suite (2026-06-05)` — its
  sole bullet, **"Hardcoded container UIDs (65534/10001/472) not tied to Docker image versions"**
  (`[PICKED UP by skillars-deferred-94 AC2: guard comment added]`). `skillars-deferred-107` AC8
  replaced the accepted-tradeoff comment with a real fix: `provision.sh` and
  `restore-from-volume-backup.sh` now probe each service image's runtime uid via
  `docker inspect .Config.User` (`image_runtime_uid` → `chown_probed`); the numeric constants remain
  only as a WARN-on-mismatch fallback, so an upstream UID drift is surfaced, not silently applied.
  **Header removed** (section empty, bullet untagged aside from the spent `[PICKED UP]`).

**Retagged in place (not deleted):** the matching `deploy-3-4` "hardcoded container UIDs" row in the
`2026-09-04` `deploy-*` re-audit table — struck through and annotated **CLOSED by
`skillars-deferred-107` AC8**, matching the five sibling rows the deferred-107 block already struck.

**Reconstruction check:** every surviving non-blank line matches the pre-edit file (master @
`918ff206` + this session's post-104/-105/-106 prune + the deferred-107 implementation commit
`dda1365`), in order, with nothing reworded or reordered — the only differences are the one deleted
bullet + its now-empty `## Deferred from:` header, the one struck-through audit-table row, and this
block. `[DISMISSED]` count: unchanged. `[DECIDED]` count: unchanged. `[PICKED UP by …]` bullets
touched: one — the `deploy-3-4` hardcoded-UID bullet, deleted as a genuine closure.

## Last audit: 2026-09-10 (skillars-deferred-108 story implementation)

Scope: close the six `skillars-deferred-104`-unblocked frontend coverage-gap bullets and the two
`skillars-deferred-107` code-review residuals; no re-mine of the broader ledger (the "genuine
one-off bugs & gaps" class stays exhausted, re-confirmed at story creation).

**Deleted outright (genuine closures, per this file's delete-when-closed convention — no
`[CLOSED by …]` tombstone left, with one disclosed exception):**

> **Exception (reworded by the skillars-deferred-108 code review, decision 5):** the `sessionManager.js`
> entry below was NOT deleted cleanly — it was replaced by an HTML-comment tombstone, following the
> precedent set by `skillars-deferred-99 AC17`. The heading originally read "no `[CLOSED by …]`
> tombstone left" without qualification, which contradicted the very list it introduced. The
> tombstone is kept rather than removed because it carries a live forward-reference to two bullets
> below it; deleting it to satisfy the heading would lose a working pointer for no gain.

- `## Deferred from: code review of skillars-deferred-11-stripe-card-collection (2026-08-04)` — its
  sole bullet (payment frontend-tests gap). **Header removed** (section empty). Closed by AC1.
- `## Deferred from: code review of skillars-deferred-17-booking-request-slot-payload-timezone-integrity (2026-08-06)`
  — its sole bullet, **D6**. **Header removed.** Closed by AC2.
- `## Deferred from: skillars-uat-2-session-duration-and-booking-slot-integrity (2026-08-10)` —
  **D6 bullet only**; D3 / D4 / D7 and the header stay. Closed by AC2.
- `## Deferred from: code review of skillars-deferred-38-coach-refresh-request-sequencing-guard (2026-08-19)`
  — its sole bullet. **Header removed.** Closed by AC3.
- `## Deferred from: code review of skillars-deferred-43-player-registration-otp-coverage-and-self-profile-fetch-caching (2026-08-20)`
  — its sole bullet. **Header removed.** Closed by AC4 (the *test-coverage* gap; the residual
  return-value leak AC4 surfaced is filed as a new bullet in the `skillars-deferred-108` section
  above, so it is not silently lost).
- The `sessionManager.js` "state machine rewritten with zero frontend tests" bullet in
  `## Deferred from: code review of 1-7b-session-refresh-rint-contract-fix (2026-09-02)` — replaced
  by an HTML-comment tombstone; the section's pre-existing HTML comment (about a different deleted
  section) survives, so the **header stays**. Closed by AC5.
- The first bullet of `## Deferred from: code review of skillars-deferred-107 (2026-09-10)`
  (`image_runtime_uid` un-timed docker calls). Header stays (the second bullet remains, retagged).
  Closed by AC8.

**Annotated, NOT deleted (decided items AC5's characterization spec pins but does not resolve):**

- `## Deferred from: code review (round 2) of 1-7b-session-refresh-rint-contract-fix (2026-09-02)`
  — the `startSessionMonitoring()` early-return bullet. Appended
  `[behaviour pinned by skillars-deferred-108 AC5's characterization spec (2026-09-10); the
  App.vue router-abort / no-re-arm concern is unchanged and this item stays open]`. Header stays.
- `## Deferred from: skillars-deferred-90 story creation and implementation (2026-09-02)` — the
  early-return-with-no-timer bullet. Appended the same "pinned … decision unchanged, item stays
  open" note. Header stays.

**Retagged in place:**

- The second bullet of `## Deferred from: code review of skillars-deferred-107 (2026-09-10)`
  (`ConfigBounds` hand-listed segments) → `[DECIDED 2026-09-10 (skillars-deferred-108 AC9)]`
  (owner accepts the hand-listing; `ConfigBounds.java`'s comment reworded; no behaviour change).

**Struck through + annotated (not deleted):**

- `## Deferred from: skillars-deferred-91 story creation and implementation (2026-09-03)` — the
  "No frontend test framework" bullet: only the trailing `[FRAMEWORK AVAILABLE …]` clause's
  "the `deferred-91` `handleLogout` / i18n coverage is now in-scope for its own follow-up story"
  struck, annotated **CLOSED by skillars-deferred-108 AC6**. The rest of the dependent list is NOT
  blanket-closed; the native-DE/FR register bullet (`:1149`) is untouched. `deferred-30` has no
  bullet anywhere (pre-existing stray reference — left as-is).
- `## Deferred from: skillars-deferred-104 implementation (2026-09-09)` — the "~6 frontend coverage
  gaps are now unblocked, not closed" bullet: the `deferred-12` typo corrected to `deferred-11`
  inline, and a `[CLOSED by skillars-deferred-108 AC1–AC6 (2026-09-10)]` tag added naming each new
  spec. The sibling "Quasar Vitest AE library-only" residual bullet is **untouched** — still
  blocked on `@quasar/app-vite` v3.

**New section added:** `## Deferred from: skillars-deferred-108 story implementation (2026-09-10)`
— one bullet (the `playerStore.js` `fetchSelfPlayerId` return-value cross-account residual).

**Reconstruction check:** every surviving non-blank line matches the pre-edit file (master @
`c74abde9` + this story's implementation commits), in order, with nothing reworded or reordered
apart from the annotations / strike-throughs / retag enumerated above. Line count: 1688 pre-edit →
1663 after the six bullet deletions + four emptied-header removals (25 lines removed) → 1778 with
this section + audit block appended (115 lines added). `## Deferred from:` header count: 89 → 85
(−4: deferred-11, -17, -38, -43) → 86 (+1: this story's new section).

**Correction (skillars-deferred-108 code review, 2026-09-10).** The three line-count figures above
were originally recorded as "1688 → 1686 … → 1775" and were wrong: the intermediate 1686 implied a
net −2 from deleting six bullets and four headers (the real figure is −25), and the final 1775 was
3 short of the file's actual 1778 at the implementation commit. `wc -l` was evidently not re-run
before the numbers were written down. They are restated correctly above. The header count
(89 → 85 → 86) was verified and was already right. Since AC10 names this reconstruction check as its
*only* verification, a mis-recorded count is that AC's own gate failing — flagged here rather than
silently overwritten so the failure mode stays visible.

**Subsequent appends (same code review).** The review then added, after that 1778-line snapshot:
the `## Deferred from: code review of skillars-deferred-108 (2026-09-10)` section below, two bullets
inside it (the `ConfigBounds` seeded-key gap filed under decision 2b, and the `BookingStateChip`
prop-type item), the `[SUPERSEDED …]` strike-through on the `deferred-90` D9 cross-reference that
AC10 specified but the implementation commit omitted, and the cite-rewording disclosure appended to
the `deferred-107` AC9 retag. Header count 86 → 87 (+1, the code-review section). Real `[DISMISSED]`-tagged *items*: unchanged (33). Real
`[DECIDED]`-tagged *items*: 30 → 31 (+1, the deferred-107 AC9 retag; raw grep counts read higher
because this audit block's own prose names both tokens, as prior audit blocks do). `[PICKED UP
by …]` item bullets touched: none.

## Deferred from: code review of skillars-deferred-108 (2026-09-10)

Pre-existing production defects and coverage gaps surfaced by the `/bmad-code-review` Edge Case
Hunter layer while auditing the deferred-108 spec backfill. None is caused by that change; all are
in code the new specs now touch, so they are cheap to close next time that surface is opened.
Decision-needed and patch findings from the same review are tracked in the story file's
`### Review Findings` section, not here.

- **The `skillars-deferred-108` AC1–AC6 specs are real but NOT merge-gating — read the six bullets
  they closed with that caveat.** `frontend-unit-tests.yml` is deliberately decoupled from the build
  gate (its own header: not referenced by `ci.yml` or `pr-build.yml`, not a required status check,
  never invoked by `mvn verify` — the Maven `npm test` execution is a no-op stub). It runs only on
  manual `workflow_dispatch`, or on a pull request carrying the `frontend-tests` label. So a future
  PR that re-breaks `slotRows`, the `loadCoachBookingRequests` sequencing guard, `handleLogout`, or
  the batch-basket `.startDatetime` mapping **can merge green** unless someone remembers the label.
  deferred-108 AC10 deleted six ledger bullets on the strength of that coverage; the specs do exist
  and are mutation-sensitive (17 of 18 reverts verified RED at implementation, the 18th closed by the
  code review's decision 1a), but "closed" here means *a spec guards this*, not *CI enforces this*.
  Owner decision D2 (2026-09-10) made the job opt-in deliberately — this bullet records the coupling,
  not a disagreement. Revisit if/when the job is promoted to a required check.
  [`.github/workflows/frontend-unit-tests.yml`]
- **[DECIDED 2026-09-11 (skillars-deferred-109 AC10)] `ConfigBounds` hand-list is now complete for
  the seeded tier keys, and the drift guard is fixed.** `VIDEO_QUOTA_TIER_SEGMENTS` gained
  `"semiPro"`, `"pro"` so the `ConfigBounds.ALL` loop generates all four `V53`-seeded
  `video.quota.semiPro.*` / `.pro.*` `BoundedKey`s (`0..Long.MAX_VALUE`, `failFast=false` — an
  out-of-range value is ERROR + `config.value.misconfigured` metric, not a boot refusal, because
  nothing reads these keys yet — see AC10.6 below). `ConfigBoundsEnumCoverageTest` now derives its
  expected segment with a `SCREAMING_SNAKE → camelCase` helper (was `toLowerCase`, which would drift
  on a future multi-word `CoachSubscriptionTier` constant), asserts `semiPro`/`pro` explicitly, and
  the `ConfigStartupAssertion` coverage for `video.quota.pro.storageBytes` is pinned. The
  `ConfigBounds.java` comment no longer frames the blind spot as "a removed constant" — it is
  "an incomplete hand-list", which is what this was. `skillars-deferred-108` AC9's keep-hand-listing
  decision is unchanged (this was a `List.of(...)` string edit, not an enum import).
  [`src/main/java/com/softropic/skillars/platform/config/service/ConfigBounds.java`]
- **[DECIDED 2026-09-11 (skillars-deferred-109 AC11)] `docker image inspect` round-trips bounded;
  `aws s3 cp` and the compose `up -d` calls deliberately left unbounded.** Both `docker image
  inspect` calls in `image_runtime_uid` (in `provision.sh` and `restore-from-volume-backup.sh`) are
  now wrapped in `run_bounded` at a short `IMAGE_INSPECT_TIMEOUT=10s` (the first inspect runs before
  the already-wrapped `docker pull`, so a wedged daemon hangs there first); `run_bounded` gained an
  explicit `<label>` arg so its timeout WARN names the real command, and its redirection now binds
  the inner `docker` call so that WARN survives. Left explicitly UNBOUNDED, each with a reason in the
  aggregate-bound comment: `aws s3 cp` (a hard wall-clock cap risks killing a slow-but-progressing
  DR download; `--cli-*-timeout` bound per-request stalls only, not total wall time); the ERR-trap
  `${DC} up -d` and the final `${DC} up -d` (a `timeout` exit 124 inside the ERR trap would exit the
  script with the stack half-started); `${DC} config` (a local compose-file parse) and `${DC} down`
  (same mid-teardown hazard as the ERR trap).

## Last audit: 2026-09-10 (post-merge prune — deferred-108 follow-up, DiskDataVolumeHigh row)

Targeted single-item prune after `skillars-deferred-108` merged (master `7a5b9528`). One
`[PICKED UP by …]` bullet that its owning story shipped but that every prior prune missed — it has
sat in the `deploy-3-3` section since 2026-06-05, tagged for `skillars-deferred-94` (merged
2026-09-07, `done`).

**Deleted (closed by shipped code — `skillars-deferred-94` AC6, per this file's delete-outright
convention):**

- `## Deferred from: code review of deploy-3-3-external-uptime-monitoring-alert-rules (2026-06-05)`
  — the **"DiskDataVolumeHigh requires Hetzner Volume mounted"** bullet
  (`[PICKED UP by skillars-deferred-94 AC6: clarifying comment added]`). Verified shipped at HEAD:
  `deploy/lgtm/alerts.yml:62-65` carries the "requires the Hetzner volume to be mounted … If
  unmounted, the `mountpoint="/opt/skillars/data"` metric is absent and this alert silent" comment
  the bullet asked for, and `docs/deployment/first-time-setup.md:388` documents the pre-deploy
  volume-mount verification step. The bullet's ask — *document the provisioning dependency* — is
  delivered. The section keeps its two `[DECIDED 2026-09-10 (skillars-deferred-107 …)]` bullets
  (Alertmanager double-notification, node_exporter isolation) — **header kept**.

**Retagged in place (not deleted):** the matching `deploy-3-3` "DiskDataVolumeHigh needs the Volume
mounted" row in the `2026-09-04` `deploy-*` re-audit table — struck through and annotated **CLOSED
by `skillars-deferred-94` AC6**, matching the sibling struck rows in that table.

**Reconstruction check:** every surviving non-blank line matches the pre-edit file (master @
`7a5b9528`), in order, with nothing reworded or reordered — the only differences are the one deleted
bullet, the one struck-through audit-table row, and this block. No `## Deferred from:` header
emptied. `[DISMISSED]` count: unchanged (33). `[DECIDED]` count: unchanged (31). `[PICKED UP by …]`
bullets touched: one — the `deploy-3-3` DiskDataVolumeHigh bullet, deleted as a genuine closure.
Line count: 1960 pre-edit → 1959 after the bullet deletion → 1991 with this block appended
(`wc -l`, re-run after writing).

## Deferred from: skillars-deferred-109 story creation (2026-09-11)

- **`QuotaConfigService.resolveTierKey` maps no player to the `semiPro` / `pro` video-quota
  segments.** `resolveTierKey` has a `switch` over `CoachSubscriptionTier {SCOUT, INSTRUCTOR,
  ACADEMY}` and a `catch` that returns `"athlete"` for every non-UUID (player) ownerId — so it can
  return only `scout` / `instructor` / `academy` / `athlete`, never `semiPro` / `pro`. A `SEMI_PRO`
  or `PRO` player therefore receives the **ATHLETE** video quota (2 GiB storage / 10 GiB bandwidth)
  regardless of billing tier, and the four `video.quota.semiPro.*` / `.pro.*` rows `V53` seeds
  (4 GiB / 25 GiB; 7 GiB / 30 GiB — `V53:37-38,44-45`) are unreachable. `PlayerSubscriptionTierBilling`
  is a dead enum (`SubscriptionService` uses the string literals `"SEMI_PRO"` / `"PRO"`). Closing
  this needs a player billing-tier → quota-segment mapping in `resolveTierKey`, plus a product
  decision on whether player video quotas are a shipped concern yet. `skillars-deferred-109` AC10
  bounded the four keys ahead of this (ERROR + `config.value.misconfigured` metric on an
  out-of-range value; no boot refusal — `failFast=false`), so the mapping inherits a range check
  when it lands. Surfaced by the `skillars-deferred-109` story-review audit.
  [`src/main/java/com/softropic/skillars/platform/video/service/QuotaConfigService.java` `resolveTierKey`]

## Last audit: 2026-09-11 (post-implementation prune — skillars-deferred-109)

`skillars-deferred-109` closed the ~16 pre-existing frontend production defects the
`skillars-deferred-108` code review filed, plus four cross-module residuals. Ledger hygiene per this
file's delete-outright-when-closed convention.

**Baselines (measured at `bbad7938`, before this story's edits):** `wc -l` **1991**;
`grep -c '^## Deferred from:'` **87**; whole-file `grep -o '\[DECIDED'` **35**;
`grep -o '\[DISMISSED'` **35**. (The creation-draft figures "1959 / 31 / 33" were wrong — corrected
by the story-review.)

**Deleted outright** (genuine closures shipped by `skillars-deferred-109`), all inside
`## Deferred from: code review of skillars-deferred-108 (2026-09-10)` unless noted:
`PaymentMethodCard.vue` retry-noop + dead-catch/stale-key (AC1.1), `brand:null` uncovered (AC1.3),
save-path stubbed (AC1.4); `sessionManager.js` skew cross-check (AC2.1), ineffective-refresh (AC2.2);
`MainLayout.vue` handleLogout parity (AC3.1), unguarded `localStorage` (AC3.2), silent-404 unpinned
(AC3.3); `playerStore.js` reject path (AC4.2) **and** the `fetchSelfPlayerId` return-value
cross-account residual in `## Deferred from: skillars-deferred-108 story implementation` (AC4.1);
`booking.store.js` handleAcceptAllBatch null-LRU-leak (AC5.1), null-response guard (AC5.2);
`BookingRequestPage.vue` `slotRows` NaN filter (AC6); `ParentBookingsPage.vue` `rescheduleProposedEnd`
(AC7 — deleted: the bullet's "breaks across DST" arithmetic claim was **backwards**, the
fixed-instant delta is what `RescheduleService.java:176-184` requires; AC7 pinned it with a spec +
comment. The remaining DST-boundary *display* ambiguity is minor, pinned by spec, and not re-filed);
`ProfileBuilderStep3.vue` silent partial-pack discard + `durationOptions` coercion (AC8);
`BookingStateChip.vue` prop-type (AC9); `ConfigBoundsEnumCoverageTest.java:32` camelCase derivation
(AC10.2); the `frontend-unit-tests.yml` `opened`-trigger bullet (AC13). The sibling
"specs are real but NOT merge-gating" bullet **stays** (B2, owner D6).

**Retagged `[DECIDED 2026-09-11 (skillars-deferred-109 …)]`** (decisions, not closures — 5
`[DECIDED` tokens added, replacing longer open-gap bullets):
the `ConfigBounds` incomplete-hand-list bullet (decision 2b) → hand-list now complete + drift guard
fixed + real entitlement gap filed separately (AC10, AC10.6); the `provision.sh`/`restore` remaining-
unbounded-calls bullet → `docker image inspect` bounded, `aws s3 cp` / `${DC} up -d` / `${DC} config`
/ `${DC} down` deliberately unbounded with reasons (AC11); the two `VideoModerationEmailListener`
`[PICKED UP by skillars-deferred-94 AC15/AC16]` bullets → blank-recipient is a documented owner
decision (D5) + the retryable/permanent mapping is now covered (AC12); the legacy `PROCESSING→READY`
webhook-cutover bullet → accepted risk + runbook mitigation (AC14).

**Added:** this section's one open bullet — the `QuotaConfigService.resolveTierKey` player-tier
entitlement gap (AC10.6) — untagged open work, **not** a `[DECIDED]`.

**Reconstruction check:** every surviving non-blank line matches the pre-edit file (`bbad7938` +
this story's implementation commits), in order, nothing reworded/reordered apart from the
deletions / retags / new section enumerated above. `## Deferred from:` header count: **87 → 88**
(the new `skillars-deferred-109 story creation` section; the `deferred-108` CR section's header
**stays** — the B2 bullet and two retagged bullets remain; the `deferred-108` story-implementation
section's header was **removed** — see the code-review correction below). Real `[DECIDED]`-tagged
*items*: **+5 retags**; real `[DISMISSED]`-tagged *items*: **35 → 35** (unchanged). Raw `grep -o`
token counts read higher — 45 / 37 after this write — because this audit block's own prose names
both tokens, as every prior audit block's does. `[PICKED UP by …]` bullets touched: **two** — the
`deferred-94` AC15/AC16 `VideoModerationEmailListener` bullets, retagged (not deleted).
`skillars-7-1` D4 and the `:1891`-region B2 note left untouched.

**Code-review correction (2026-09-11).** Three figures in this block were wrong as first written and
are corrected here rather than silently edited away:

1. **Post-write line count.** Recorded as **1936**; the file was actually **1938** — the
   post-append `wc -l` the AC demands ("re-run after writing") was never re-run. Arithmetic
   confirms it: 1991 − 152 + 99 = 1938.
2. **`[DECIDED]` item delta.** Recorded as "real items **35 → 40**", which used the raw *token*
   count (35) as the *item* baseline. Roughly 20 of the 35 pre-write `[DECIDED` tokens are audit-
   block prose, not tagged items, so the real item baseline is far lower and the "→ 40" is not
   meaningful. Only the delta is trustworthy: **+5 retags**. AC15 itself predicted "+3"; the
   implementation made 5, correctly — `:1238-1239` is two bullets and AC11 added a conditional
   fifth — so the AC's figure was the wrong one, not the code.
3. **The `deferred-108` story-implementation header.** This block claimed it "stays — other bullets
   remain". It did not: AC15 deleted its only bullet (`:1695`), leaving the header above a
   paragraph ending "One residual surfaced:" with nothing after it. The code review removed the
   header and its orphaned intro, matching this file's own convention for an emptied section
   ("header emptied and removed", used at `:1339`, `:1344`, `:1348`, `:1398`, `:1401`, `:1405`).

**Measured after the code-review edits** (fresh `wc -l` / `grep -c` / `grep -o`, run at the time of
writing, with every section below already in place): **1998** lines; **88** `## Deferred from:`
headers; **48** raw `[DECIDED` tokens; **38** raw `[DISMISSED` tokens.

## Deferred from: code review of skillars-deferred-109 (2026-09-11)

`/bmad-code-review` on the `skillars-deferred-109` working tree (Blind Hunter + Edge Case Hunter +
Acceptance Auditor, all three completed). 2 decision-needed and 18 patch findings were handled in
the story; three items are genuinely pre-existing or wider than this story and are filed here:

- `VideoModerationEmailListener.java:102-107` — the AC12.1 `persisted == null` branch WARNs and
  returns normally. A normal return is exactly the signal `ModerationAdminAlertOutboxHandler` uses
  to delete the durable outbox row, so an **unknown** send outcome (the `REQUIRES_NEW` commit not
  yet visible on read-back) is handled identically to a confirmed success — the last-resort human
  notification for a permanently-failed moderation can be dropped with only a WARN. AC12.1 changed
  the log level, not the release decision, so this is pre-existing. Options: re-read with a short
  bounded retry, or throw `IllegalStateException` (retain the row) on an unresolved read-back.
- `booking.store.js:645-651` — `handleAcceptAllBatch`'s catch calls `deleteBatchAcceptResult(batchId)`
  unconditionally before rethrowing. AC5.1 added it for the seeded-`null` case, but anything that
  throws *after* a successful `acceptAllBatch` + `setBatchAcceptResult` now also wipes the real
  per-booking results the coach needs to see which bookings in the batch were accepted. Guard the
  delete on the stored value still being the `null` seed.
- `.github/workflows/frontend-unit-tests.yml` — no frontend CI job runs the Vitest suite under a
  non-UTC timezone. `happy-dom`'s `Intl` is UTC, so every DST-named assertion in the suite is
  structurally unfalsifiable; `skillars-deferred-109` AC7.2's fall-back "pin" is the current
  example, and the same spec **fails** under `TZ=America/New_York` (reproduced during this review).
  A second matrix leg with `TZ: America/New_York`, or a per-spec `vi.stubEnv`/fake-timer zone, would
  make the DST class of assertion real. Cheap, and it is the only thing standing between the suite
  and a whole category of timezone regressions.
- `MailManager.java:136-150` (`isRetryable`) + `ComponentConfig.java:44-49` — **every permanently
  undeliverable email is classified as retryable against the real circuit-breaker configuration.**
  Found while writing the AC12.2 IT the owner asked for (decision D2 → option a), which is exactly
  the class of defect a mocked-collaborator unit test cannot reach. `isRetryable` deliberately scans
  only three depths (direct, cause, cause-of-cause) to avoid misclassifying an unrelated
  non-repairable type buried deep in some other chain. But with the real container-configured
  `CircuitBreakerFactory`, the exception that reaches `toEnvelopeEntity` is
  `RuntimeException("Email sending failed via Circuit Breaker")` caused by resilience4j's
  `TimeoutException` — the originating exception is **absent from the chain entirely**, so no
  non-repairable type is found at any depth and the row is stamped `FAILED, isRetry=true`.
  Reproduced: throwing `AddressException` (a `NON_REPAIRABLE_ERRORS` member) directly from the
  `MailService` seam still yields `isRetry=true`. Consequence for
  `VideoModerationEmailListener.sendAdminAlertSync`: the retryable arm throws, the outbox row is
  retained, and a failure no re-drive can fix re-drives until `[OUTBOX_STUCK]` summons a human —
  the precise outcome the permanent arm at `:116-119` exists to avoid. The sibling unit test
  (`VideoModerationEmailListenerTest.RealMailManagerMappingAC122`) does NOT reproduce it: its
  hand-built `Resilience4JCircuitBreakerFactory` has no TimeLimiter, so the real cause survives.
  Pre-existing — nothing in `skillars-deferred-109` caused it. Likely fix: have the circuit-breaker
  fallback preserve the original throwable rather than replacing it, and/or classify from the
  exception captured inside the retry loop instead of from whatever the CB layer surfaces. Until
  then `VideoModerationAdminAlertEnvelopeIT` carries no permanent-failure case and says why.

## Deferred from: code review of ses-1-1-introduce-outbound-email-port (2026-09-11)

- **`app.ses.max-send-rate-per-second` is validated and warned on but never enforced.** `SesPropertiesValidator:86-97` rejects non-positive values and WARNs at `>= 10`, but nothing in `SesEmailSender.send` rate-limits: no semaphore, no bucket, no scheduler gate. `grep` confirms `getMaxSendRatePerSecond()` has no other consumer. An operator on a sandboxed SES account who sets it to 1 expecting throttling gets none. §5 assigns throttling to a later phase; revisit when Phase 3/4 lands.
- **`LoggingEmailSender` collision-exhaustion is unthrottled and costs 100 syscalls per send.** The loop-exhausted `log.warn` at `LoggingEmailSender:121` sits outside the `directoryWritable` transition-throttle designed for the `IOException` branch at `:114`, and the `FileAlreadyExistsException` branch never touches that flag. A caller reusing a constant `correlationId` (the port places no uniqueness constraint on it) against an outbox already holding 100 matching files performs 100 `Files.writeString` attempts and emits one WARN per send, indefinitely. Degraded-path nuisance on a dev-only transport.
- **No non-production environment exercises the SES path.** dev, uat and test are all `app.email.transport: log`; `smtp` is deliberately rejected until Phase 2; `DevSesEmailService` (the old way to make a dev box send real mail) is deleted by this story. The first execution of `SesEmailSender` against real AWS — real credentials, real region, real verified-domain state, 3s attempt timeout, zero retries — therefore happens in production. This is the phase plan working as designed, not a defect; noted so it is not rediscovered at the Phase 5 cutover.
- **`LoggingEmailSender` silently discards the text part when both bodies are present.** `LoggingEmailSender:94-95` picks `htmlBody` when present and never writes a `.txt` companion, though `OutboundEmailRequest` explicitly supports both (`OutboundEmailRequestValidationTest.bothBodiesPresent_isAccepted`). No current caller sends both — all six registration emails are html-only — so the outbox artifact is simply not a faithful record for a shape nothing produces yet. Revisit if a multipart caller appears.
- **`NoHardcodedSenderTest` only inspects the first `from-address` match per file.** `matcher.find()` stops at the first hit, so a second hardcoded `from-address: noreply@skillars.com` added later in the same YAML — precisely the regression the test exists to catch — is never examined. The guard holds for today's single-occurrence files.

## Deferred from: code review of ses-1-2-smtp-behind-port-and-containment (2026-09-11)

- **`SmtpErrorClassifier` never inspects `MailSendException.getFailedMessages()`.** `JavaMailSenderImpl.doSend` throws `new MailSendException(failedMessages)` when individual messages fail — a 550 recipient rejection surfaces as a `SendFailedException`/`AddressException` *inside that map*, and the constructor sets no cause. `SmtpErrorClassifier.classify` walks only `direct -> getCause() -> getCause().getCause()` (`SmtpErrorClassifier.java:38-46`), so `cause` is `null` and the permanent rejection is classified `EmailTransportTransientException` -> `retry=true` -> 6 `EmailRetryScheduler` attempts plus circuit-breaker failure-rate pressure (`slidingWindowSize=5, failureRateThreshold=50%`) that a single bad address can use to trip the breaker for all email. Carried over verbatim from the pre-story `MailManager` cause-walk, which had the same hole — not introduced by ses-1.2, but `SmtpErrorClassifier` is now the class whose javadoc claims to own SMTP classification. `SmtpErrorClassifierTest` covers direct throw and one level of `MessagingException(msg, cause)` wrapping only.
- **`MailSenderProvider` round-robin counter overflow indexes a negative position.** `counter.getAndIncrement() % providers.size()` (`MailSenderProvider.java:48-52`) wraps to `Integer.MIN_VALUE` after 2^31 sends. `MIN_VALUE % 2 == 0` so today's two-provider config survives, but an odd provider count > 1 yields a negative index (`MIN_VALUE % 3 == -2`) -> `IndexOutOfBoundsException`, which `SmtpEmailSender`'s `catch (MessagingException | MailException)` does not catch. Per-JVM-lifetime counter, never reset in production. Becomes reachable the moment a third provider is added. (The dead, zero-caller `resetProviderRoundRobin()` this bullet originally cited was itself removed in the same review pass — code review 2026-09-11, patch item — so it is no longer a live escape hatch to point to either.)
- **`SmtpEmailSender`/`MailSenderProvider.nextSender()` misclassifies an empty provider list as retryable.** `nextSender()` (`MailSenderProvider.java`) throws `ArithmeticException: / by zero` when `app.email.smtp.provider-configs` binds to an empty list under `transport=smtp` — a real misconfiguration shape, not hypothetical (an operator who sets `transport: smtp` and forgets the provider list, or a YAML anchor/env-substitution that resolves to nothing). `SmtpEmailSender`'s `catch (MessagingException | MailException)` does not catch `ArithmeticException`, so it propagates raw into `MailManager`, which classifies anything that is not an `EmailTransportPermanentException` as retryable (AC4) — the same "permanent-but-unclassified failure retryable" shape code review 2026-09-11's decision (b) fixed for `OutboundEmailRequest`'s `IllegalArgumentException`, but that fix was scoped to `MailService` wrapping the request construction only and does not reach this call, which happens one layer down inside `SmtpEmailSender.send`. 6 `EmailRetryScheduler` rounds against a misconfiguration no retry can fix. Pairs with the `SmtpPropertiesValidator` gap noted below — a startup-time validator rejecting an empty provider list under `transport=smtp` would prevent this from ever being reachable at send time.
- **No `SmtpPropertiesValidator` counterpart to `SesPropertiesValidator`.** `ProviderConfig.port` is a `String` with no validation and `MailSenderProvider` is an unconditional `@Component` (no `@ConditionalOnProperty`), so `Integer.parseInt(providerConfig.getPort())` at `MailSenderProvider.java:37` runs in **every** profile — including prod, where `transport=ses` and SMTP is unused. A malformed or absent port aborts startup with a raw `NumberFormatException: For input string: "58a"` naming neither the property nor the provider. A provider entry with `host`+`port` but no `username` instead survives startup and fails per-send at `helper.setFrom(javaMailSender.getUsername())` with an `IllegalArgumentException` thrown inside `SmtpEmailSender`'s `try` but outside its catch clause. SES got a fail-fast validator in ses-1.1; SMTP has none, despite dev and uat now depending on it.
- **§7.2 item 13's adapter-wrap-depth guard is absent.** `requirements/ses-email-consolidation.md:1080-1086` requires driving at least one case through the real `SesEmailSender` + `SesErrorClassifier` and one through `SmtpEmailSender` + `SmtpErrorClassifier`, "so the test fails if either adapter's wrap depth drifts". `SmtpErrorClassifier` throws `EmailTransportPermanentException` at depth 0 and `MailManager.isRetryable`'s fixed 3-level walk handles it — but that coupling is asserted only by mocks that hand-construct the exception, which is precisely the failure mode §7.2 item 13 names. Story ses-1.2's AC4 did not carry the requirement forward from the source doc it cites.
- **`docs/dev-docs/notification/index.html:111` still places `MailSenderProvider` in the notification module reading `email.providerConfigs`.** Both facts are stale at HEAD — the class moved to `infrastructure.email.smtp` and the key moved to `app.email.smtp.provider-configs`. §5 Phase 7 is the Documentation phase, so this is in-plan rather than an omission; noted so the Phase 7 sweep has a concrete target.

## Deferred from: code review of ses-1-3-health-monitoring-rate-limiting (2026-09-12)

- **`EmailTransportPermanentException` is still retried 3x by `ComponentConfig.retryTemplate()`** (`platform/notification/config/ComponentConfig.java:62`). The ses-1.3 classifier lists only `EmailTransportRateLimitedException`; every other type keeps the `true` default. So a failure the codebase already declares non-repairable (`MailManager.NON_REPAIRABLE_ERRORS`) still burns 3 attempts with 1s fixed backoff inside the open `REQUIRES_NEW` transaction and against the breaker's 10s TimeLimiter — the exact waste AC4's own javadoc describes. The classifier is verifiably capable of handling it (blacklist mode + `traversingCauses()` walks `MailManager`'s one `RuntimeException` wrapper correctly). Fix is a one-liner: `.notRetryOn(EmailTransportPermanentException.class)`. Deferred because AC4 explicitly scoped itself to "exactly one new exception type, nothing else" — widening it is a behaviour change for every permanent failure and deserves its own decision.
- **A DOWN health result is cached for the full 60s TTL** (`infrastructure/ses/SesHealthIndicator.java:128`). Recovery after a transient SES failure is delayed up to a full TTL, and a single blip yields exactly two consecutive container-healthcheck failures (30s interval vs 60s TTL) — one short of the `retries: 3` threshold. Pre-existing pattern: `SmtpHealthIndicator` caches DOWN the same way. Worth revisiting jointly (e.g. a shorter TTL for DOWN than UP) rather than in one indicator only.
- **A mid-loop rate-limit rejection duplicates earlier recipients and never sends later ones** (`platform/notification/service/MailManager.java:74`). `sendEmailSync` loops recipients with a single row-level status and no per-recipient progress marker; a rejection at recipient *k* aborts the loop, persists the envelope FAILED/retry=true, and the re-drive restarts at recipient 1 — duplicating 1..k-1 on every one of the 6 attempts and never reaching k+1..n. Where *n* exceeds the per-second limit the envelope deterministically dies at the same index and ends `ATTEMPTS_EXHAUSTED`. Latent today: `SendMailListener` accepts an arbitrary-length `userIds` list but its only caller (`TwoFactorLoginService.java:61`) passes one id. Becomes live the moment any producer emits a multi-recipient envelope.
- **One WARN line per rejected send** (`infrastructure/ses/SesSendRateLimiter.java:52`). Rejections are the expected steady state whenever the queue drains faster than the limit, so a 10-envelope scheduler batch at a 1/s sandbox rate emits ~9 WARN lines per tick until the backlog clears. The condition is already counted by `mail.ses.rate_limiter.rejected`, which is the signal worth alerting on. Consider DEBUG, or WARN on transition into the throttled state. Coupled to the burst/attempt-exhaustion decision from the same review.
