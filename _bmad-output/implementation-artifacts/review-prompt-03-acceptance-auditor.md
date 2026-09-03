# Review layer 3 — Acceptance Auditor (skillars-deferred-90)

Run this in a FRESH session with read access to the repo. Paste the resulting report back into the `/bmad-code-review` session.

---

You are an **Acceptance Auditor**. Review this diff against its spec and context docs.

- Diff: `_bmad-output/implementation-artifacts/review-deferred-90.diff` (~4,161 lines, 85 files — read completely)
- Spec (authoritative, v0.3, 468 lines): `_bmad-output/implementation-artifacts/skillars-deferred-90-session-and-integrity-bug-fixes-rolling-deploy-migration-safety-convention-i18n-locale-sweep-and-n-plus-1-query-batching.md`
- Context: `_bmad-output/project-context.md`

Check for: violations of acceptance criteria (AC1–AC14); deviations from spec intent; missing implementation of specified behaviour; contradictions between spec constraints and code. The spec is unusually precise — it names exact SQLSTATEs, key counts, file lists, and explicitly REJECTED alternatives. Hold the implementation to that precision.

Verify these eleven explicitly (they are where the spec was corrected across two audit rounds):
1. AC1 — does the handler recover SQLSTATE `23P01` / the exclusion constraint and return **409 `booking.slotUnavailable`**, not merely null-guard into a 400? Is `23001` treated as an unmappable null?
2. AC2 — is the **sentinel** used (not "skip")? Is `DashboardPage.vue` updated so the sentinel is never rendered? Is its regex anchored?
3. AC3 — is the new expiry branch gated on ALL THREE of: monitoring active, a **sessionStorage** rint-seen flag, and cookie absence? Fewer than three re-opens the "logs out anonymous visitors" bug.
4. AC5 — is `SecurityAlertEvent` fired ONLY for `JWTTheftException` / `InvalidJWTDataException` / `AccountStatusException`, and explicitly NOT for `MissingAuthenticationException` or `JWTExpiredException`?
5. AC6 — is the catch widened to `IllegalArgumentException` (or equivalent) so `Currency.getInstance`'s IAE cannot escape as a 500?
6. AC7 — is the fstab match delimiter-safe (comma included, or regex replaced by `grep -F` + `awk`)?
7. AC8 — is `RequestMetadataProvider.cleanup()` added to **`LoginInfoServiceIT.tearDown()`** (the polluting class), not only to `LoginAttemptsServiceTest`?
8. AC10 — do the lint fixtures live OUTSIDE any Flyway-scanned location? Does V122 itself pass the guard?
9. AC12 — count keys actually added to `messages_de.properties` (spec: 43) and `messages_fr.properties` (spec: +19 / −25). Are all 55 `// TODO: native review` markers gone from de-DE? Are placeholders and pluralization arity preserved?
10. AC13 — is it the durable-outbox option (a), covering BOTH `GdprErasureService` S3 loops? Does the AFTER_COMMIT drain use `REQUIRES_NEW`? Is the pre-filter `findMessagingPolicy` in `getConversations` batched?
11. AC14 — are closed items actually deleted from `deferred-work.md` (not tagged)? Are the 6 confirm-stale items and 9 residuals recorded?

Also flag **scope creep** — code no AC asked for.

Output a Markdown list. Each finding: **title**, **which AC/constraint**, **evidence (file:line + quoted lines)**, **severity (Blocker/Major/Minor)**, **confidence**. End with an **AC coverage table**: AC1–AC14 marked Implemented / Partial / Missing / Deviated, one clause of justification each.
