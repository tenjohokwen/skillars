# Story Review: skillars-deferred-102

**Status:** Review complete | **Date:** 2026-09-08

---

## Executive Summary

The story is well-structured and comprehensive, with each AC grounded in verified HEAD state. However, there are **5 implementation-time gaps** that will require design decisions or clarifications during dev:

1. **AC2** — restore exit contract undefined when container registration exhausts
2. **AC5** — SSH exit-status separation lacks implementation detail; transport-failure threshold unclear
3. **AC6** — permission model for `deploy` user touching service-owned `data/**` is unspecified
4. **AC11** — pagination tiebreaker must exist; implementation is conditional on verification
5. **AC14** — entirely exploratory (measure → act on findings); AC19 ledger closure depends on outcome

Additionally, **3 minor specificity gaps** (AC3 "armed dirs", AC4 iteration count, AC18 openpdf release notes) should be resolved before implementation to avoid churn. These are flagged in the detail below.

**Recommendation:** The story is ready for dev after the dev agent resolves the 5 design gaps via CLAUDE.md clarifications or inline comments during implementation. No scope change needed.

---

## Detailed Findings

### 🔴 Critical Gaps (must resolve before / during dev)

#### AC2: `restore-from-dump.sh` — APP_CID retry exit contract

**Issue:** The AC specifies a retry loop around `docker compose ps -q app` to handle slow container registration, but leaves the exit/signal contract undefined when retries exhaust.

**Current situation (verified):**
- `:119` `RESTORE_OK=0` initially
- `:121` EXIT trap: `if [ "$RESTORE_OK" -eq 0 ]; then docker-compose start app; fi`
- `:141-142` After health wait succeeds, `RESTORE_OK=1`
- If the retry-exhausted path keeps `RESTORE_OK=0`, the trap **restarts the app**

**The gap:** AC2 says "emit a distinct diagnostic so the operator knows the DB restore itself completed" but does **not** specify whether the EXIT trap should restart the app in this case.

**Two interpretations:**
1. Keep `RESTORE_OK=0` → trap restarts app (may be wrong if container registration is just slow)
2. Set `RESTORE_OK` to a distinct value (e.g., `2`) to signal "restore done, container-register failed" → avoid restart

**Recommendation:** Dev agent should clarify with the owner or CLAUDE.md: should the app start or not if the retry exhausts? The test `bash -n + shellcheck` won't catch the logic error.

---

#### AC5: Deploy smoke — SSH/transport failure separation

**Issue:** The AC says "capture the `ssh` exit status separately from the remote script's stdout" but doesn't explain **how** given the current pipe structure.

**Current code (verified):**
```bash
ssh ... "<remote script>" 2>/dev/null || echo 0
```

**The gap:** The implementation must:
- Capture `ssh`'s exit code (255 for transport failure) **separately** from the remote script's exit code
- Skip iterations that are transport failures (don't count them as health failures)
- Exit with `result=error` (not `result=fail`) if **every** iteration is a transport failure

**Missing details:**
1. **How?** The current pipe collapses both exit codes. Suggestion: run `ssh` separately, capture $?, then check if 255. But the implementation is not spelled out.
2. **"Every iteration"?** Is this a threshold (e.g., > 20 of 24), or truly all? AC5 says "if every iteration is a transport failure" but doesn't clarify whether 1 or 2 DNS timeouts should trigger the error path.
3. **Verification:** The test says `bash -n` + re-read the workflow — but "re-read" is weaker than a test that *forces* transport failure and verifies the path is taken.

**Recommendation:** Implementation should document the SSH exit-code capture in a comment block (AC5 already asks for one) and pick a threshold for "all failed" (e.g., current iteration count > 80% failed).

---

#### AC6: Provisioning — `deploy` user permission model

**Issue:** The AC moves the checkout from `/opt/skillars` (root-owned) to `/opt/skillars/app` (deploy-owned) and moves runtime data outside. But the permission contract for the deploy user is undefined.

**Questions not answered:**
1. **Who runs the deploy scripts?** The SSH user is `deploy`, but what about cron jobs, systemd services, or manual re-deploys? Do all of these switch to `deploy`?
2. **Can `deploy` touch `data/**`?** The AC says "leave `data/**` owned by the service UIDs" (postgres, redis, etc.) but doesn't explain how `deploy` writes to those directories during deployment. Does `deploy` join those groups? Or do the service directories have broad permissions?
3. **Does `deploy` need sudo?** The AC says "add it to the `docker` group" (so it can run `docker` without sudo) but doesn't say if it needs sudo for other operations like restarting systemd services or mounting volumes.
4. **Secrets access:** Where do deployment secrets come from (SSH key, AWS creds)? Do they live in `deploy`'s home, or in a shared location? The `secrets.SSH_USER` workflow variable changes to `deploy`, but are there other secrets?

**Risk:** If the permission model is wrong, the deploy will fail at runtime on the first production deployment, which is the exact scenario AC6 aims to prevent.

**Recommendation:** The dev agent should confirm with the owner (or document in the branch) the exact permission model before writing code. Update AC6's fix approach with explicit answers to the 4 questions above.

---

#### AC11: Revenue running balance — pagination tiebreaker prerequisite

**Issue:** The AC depends on the existence of a deterministic tiebreaker in the page query, but doesn't verify it exists at HEAD.

**Relevant quote:**
> "The page is ordered by `(createdAt, <tiebreaker>)`. The opening balance must sum every row that sorts **before the first row of this page**, which means "`createdAt < oldest.createdAt`" **plus** "`createdAt = oldest.createdAt` **and** tiebreaker `<`"
> 
> "Identify the actual sort tiebreaker used by the page query (likely `id` or a sequence column)"
> 
> "If the page query has **no** deterministic tiebreaker, that is the real bug — add one (`ORDER BY created_at, id`) and make the opening-balance sum match."

**The gap:** The AC says "identify" (verify it exists) but doesn't state whether the tiebreaker **does** exist at HEAD.

**Verified at HEAD:** The story says `:233` calls `sumByParentIdAndCreatedAtBefore` but doesn't confirm the page query's order clause. This requires reading `ParentCreditLedgerRepository.findPageByParentId` (or wherever the page is fetched).

**Risk:** If the tiebreaker doesn't exist, the implementation becomes: (1) add the tiebreaker to the page query, (2) verify it's deterministic (e.g., `ORDER BY created_at, id`), (3) add the compound-predicate repo method. This is a larger change than AC11 implies.

**Recommendation:** Dev agent should verify the tiebreaker at implementation start and flag if it's missing as a scope expansion.

---

#### AC14: Messaging N+1 — entirely conditional

**Issue:** AC14 is not a concrete acceptance criterion; it's "investigate, then do one of two different things depending on findings."

**The AC:**
> "Determine the actual per-request query count of `getConversations` at HEAD; if there is a residual per-row lookup in `toSummary` / `buildSummaryContext`, batch it; if the cost is already constant, add a query-count IT as the regression guard and close the ledger item as measured."

**The gap:** This is a conditional AC with two paths:
- Path A: Residual N+1 exists → add batching
- Path B: No N+1 exists → add a test and close

The story doesn't specify which path to take. The implementation is exploratory.

**Risk:** The test bar is different for each path (Path B adds a test, but Path A doesn't explicitly say to add one). The AC19 ledger entry depends on which path was taken (close the item? Keep it?)

**Recommendation:** Implementation should read `toSummary` and `buildSummaryContext` in full at the start and decide on path. Add a test in both cases (AC19 should close the ledger regardless). The story should be clearer: add a `MessagingConversationsQueryCountIT` *always*, and conditionally add batching if it's needed.

---

### 🟡 Minor Specificity Gaps (flag but likely resolvable)

#### AC3: "armed dirs" is vague

**Quote:**
> `:417-427` does `stat -c '%a %u:%g'` ownership/mode checks on `traefik/acme.json` and each **armed dir**.

**The gap:** "Armed dir" is jargon. Which directories are these? The AC should list them explicitly (e.g., `traefik/acme.json`, `postgres/`, `redis/`) so the implementation doesn't guess.

**Recommendation:** List the directories explicitly in AC3's fix approach, or grep `provision.sh` for the current `stat` checks and cite them.

---

#### AC4: Iteration count is underspecified

**Quote:**
> "Bump the iteration count (e.g. `seq 1 24` at 5 s = ~180 s post-startup) — **pick the number** from `docker-compose.yml`'s `app` `start_period` + observed cold-start, and leave a comment with the arithmetic."

**The gap:** "Pick the number" is subjective. Does the dev agent:
- Read `docker-compose.yml`, see `start_period: 60s`, and guess "180s total is 60 + 120 = 24 × 5"?
- Run a test deploy and time the JVM cold-start?
- Ask the owner?

**Recommendation:** The AC should recommend a concrete default (e.g., "24 iterations ≈ 180s post-startup, or adjust based on observed start times"). The comment block in the code should show the math.

---

#### AC18: openpdf 3.x release notes dependency

**Quote:**
> "openpdf 3.x is API-compatible with iText 2.x / openpdf 1.x at the class level — `Document`, `PdfWriter.getInstance`, `PdfPTable`, `PdfPCell`, `FontFactory`, `Image.getInstance`, `PageSize` all keep their signatures; only the package changed. Compile-check for any method that *was* removed in 3.x and adjust."

**The gap:** The AC doesn't list which methods (if any) were removed. The implementation must check the openpdf 3.0.5 release notes separately, and the AC doesn't provide a link or guidance.

**Recommendation:** Implementation should check openpdf 3.0.5 release notes before starting. If methods were removed, the AC should have listed them. The compile-check will catch it, but a pre-compile search would be faster.

---

### 🔵 Unresolved Dependencies

#### AC6 ↔ AC7 ordering

AC7 depends on AC6's path choice:
> "After AC6 moves the checkout to its own path, confirm that path is **not** under `${MOUNT_POINT}`"

If AC6 chooses `/opt/skillars/app` (under `/opt/skillars`), and the mount point is `/opt/skillars/data`, then the checkout **is** `/opt/skillars/app` and is a sibling of `data/`, not under it. This is correct.

But if AC6 had chosen `/srv/skillars` instead, AC7 would be trivial. The story notes to "confirm details with the owner," which is good, but the implementation should prioritize the `/opt/skillars/app` choice to keep the blast radius small (as the story recommends).

**No gap here, just a dependency to watch.**

---

#### AC19 depends on AC14 outcome

AC19 says:
> "If a standalone `skillars-8-1` bullet still exists, delete it; otherwise add one line to the `deferred-101` audit block's 'Items re-verified still open' list noting D2 is now closed/measured by `deferred-102` AC14."

This means AC19's ledger entry is conditional on whether AC14 finds an N+1 or not. This is fine, but the implementation should note it.

---

### ✅ Well-Specified Areas

- **AC1, AC8, AC9:** Shell edits are clear and concise.
- **AC10:** Schema change is straightforward and follows conventions.
- **AC12, AC13:** Constants/filters are well-scoped.
- **AC15, AC16, AC17, AC18:** Verification/fix tasks are clear, even if exploratory.
- **Dev Notes (sequencing):** Correct identification of AC6/AC7 as high-risk and highest-priority.
- **Testing standards:** Good reference to project conventions.
- **Project structure notes:** Filesystem/module boundaries are clear (via memory notes).

---

## Potential False Assumptions

1. **AC1:** Assumes the trap's `rm` doesn't fail mid-file. Edge case: if `rm` fails with EROFS, the trap doesn't fail (no `-e` guard). Minor issue, but worth a comment: `trap 'rm -f "${DUMP_FILE}" || true' EXIT` is safer.

2. **AC3:** Assumes `rsync -aHAXcni` doesn't have performance impact. The AC says "small tree" so this is likely fine, but a very large `postgres/` dir could make this a bottleneck. Low probability, but worth noting in the test.

3. **AC4:** Assumes the JVM cold-start is deterministic and predictable. If it varies wildly (e.g., when under memory pressure), 24 × 5s might not be enough. The comment in the code should say "adjust if cold-start times increase."

4. **AC6:** Assumes the owner wants to *avoid* the "accept & document risk" option. The story says D3 decided on "non-root user," but there was a trade-off considered. If the owner later changes their mind, this whole AC is wasted work.

5. **AC11:** Assumes the pagination uses a deterministic order. If the current query is `ORDER BY created_at` with no tiebreaker, adding one is a behavior change (rows may reorder). Unlikely to cause data corruption, but worth verifying.

6. **AC14:** Assumes `buildSummaryContext` is where the N+1 would hide. The AC already checked and found only `toSummary` is questionable. Implementation should verify this is still true.

---

## Ledger Hygiene (AC19) — Verification Checklist

AC19 must delete bullets named by AC1–AC18. The story lists the "Ledger" line for each AC, but implementation must:

1. **Re-verify each bullet exists in `deferred-work.md` at time of implementation** (line numbers will have drifted since 2026-09-08).
2. **Check `sprint-status.yaml` for `skillars-deferred-97`** — set to `withdrawn`. (Not mentioned in "Files in play" table; should be added.)
3. **Confirm the Reconstruction check** statement is accurate after deletes.

---

## Testing & Verification Gaps

| AC | Gap | Severity |
|----|-----|----------|
| AC2 | No test that forces APP_CID retry to exhaust and verifies exit contract | Medium |
| AC5 | No test that forces SSH exit 255 and verifies transport-failure path is taken | Medium |
| AC6 | No dry-run that verifies permission model end-to-end | High |
| AC11 | No verification that tiebreaker exists before implementation | High |
| AC14 | Outcome-dependent; test plan differs by path | Medium |
| AC16 | Manual "attribute and ref agree" is subjective; no automation possible | Low |

---

## Recommendations for Dev Agent

1. **Before implementation:** Clarify AC2 exit contract, AC5 transport-failure threshold, and AC6 permission model with the owner or document in CLAUDE.md.
2. **At implementation start:** Verify AC11's pagination tiebreaker exists; if not, expand scope or flag as blocker.
3. **During AC14:** Read the full `toSummary` and `buildSummaryContext` code; decide on path (add batching vs. add test) and document.
4. **During AC3, AC4, AC18:** Use explicit lists/numbers in code comments (armed dirs, iteration formula, openpdf release notes).
5. **Final check (AC19):** Re-verify all ledger bullets exist before deletion; check `sprint-status.yaml` for `skillars-deferred-97`.

---

## Conclusion

**Ready for dev with minor clarifications.** No false positives detected; all findings are genuine implementation dependencies or specificity gaps. The story's design is sound and well-grounded in HEAD state. The 5 critical gaps above will require 30–60 minutes of clarification before code write, but no scope rework is needed.
