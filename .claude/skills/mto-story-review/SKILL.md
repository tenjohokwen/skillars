---
name: mto-story-review
description: 'Audits a ready-for-dev story file against the ACTUAL CURRENT source before implementation starts -- hunts missed corner cases, false assumptions, and missed flows, and independently re-verifies every file:line citation, ledger reference, precedent attribution, and constraint the story claims, instead of trusting them. Built specifically because a prior story review confidently rated itself "zero false positives, HIGH confidence" while containing four real defects: a stale-commit citation trusted instead of re-checked against real HEAD, a citation the reviewer wrongly called fabricated after checking only one source, a technical claim about which code does what that was backwards, and a transaction-boundary claim asserted without tracing the actual code. Use this whenever the user says "review this story", "audit the story for corner cases/false assumptions/missed flows", asks to check a story''s citations or precedents against the real source, or runs `/mto-story-review` -- including with no arguments, which should auto-discover the latest story from sprint-status.yaml.'
---

# Story Review (mto-story-review)

**What this catches that a normal review misses:** the failure mode isn't a lazy review -- it's a
*confident* one. The review that prompted this skill opened real files, cited real line numbers, and
still shipped four defects because nothing double-checked its own claims against the code as it
actually stands right now. Every step below exists to close one specific way that happens. Don't skip
steps because a claim "looks obviously right" -- all four original defects looked obviously right on
first read.

## Step 1: Find and confirm the target story

1. If the user's prompt already names a specific story (file path, story key, or unambiguous
   description), use it directly -- skip to instruction 4, but still perform it (confirmation is not
   optional just because the target was named).
2. Otherwise, determine the latest story from `_bmad-output/implementation-artifacts/sprint-status.yaml`.
   **Do not use "last key in the `development_status` map" as your signal -- the map is not reliably
   appended in recency order** (entries for a numerically/chronologically later story can sit above an
   earlier one). Instead:
   - Read the file's top-level `last_updated:` line. By this project's own convention, its trailing
     `#`-comment is a changelog narrated newest-first ("`# <story-key>: ... status -> done. Prior note:
     <older story-key>: ...`"). Extract the **first** story key named in that comment -- that is the
     latest story.
   - Then look up that exact key in the `development_status` map and read its status **from the map**,
     not from the comment prose (the comment can lag; the map value is authoritative).
   - Only if the `last_updated` comment doesn't parse into a recognizable story key, fall back to
     comparing the numeric suffix among story-shaped keys sharing the same naming pattern (e.g. the
     highest `N` among `skillars-deferred-N-*` keys), and only as a last resort fall back to file order
     -- and if you do, tell the user explicitly that a fallback heuristic was used, since it is not
     reliable.
3. Check that entry's status:
   - **If `ready-for-dev`:** this is the target. Continue to instruction 4.
   - **If anything else** (`done`, `review`, `in-progress`, `backlog`, ...): report the latest story's
     key and its actual status plainly. If some *other* entry in the map is `ready-for-dev`, mention it
     as an available option. Then **HALT and wait for the user's input** -- do not guess, do not fall
     back to an older story automatically, do not review a non-ready-for-dev story without being told to.
4. Resolve the story key to its file: `_bmad-output/implementation-artifacts/<key>.md`. Confirm the file
   exists and is readable. Present the story key, file path, and its one-line title to the user and
   **HALT for explicit confirmation** before doing any verification work.

## Step 2: Establish ground truth before reading the story's claims

Run `git rev-parse --short HEAD` and `git log -1 --format='%h %s'` yourself, right now, before opening
the story. This is the only HEAD that matters for verification.

**Do not trust any commit SHA, line-count, or "Master is at ..." claim written inside the story's own
Context or Dev Notes section.** Those are snapshots from whenever the story was drafted -- the exact
kind of stale-but-plausible claim that caused the original defect (a reviewer verified against the
commit the story *said* was current instead of checking what actually is). Note the real HEAD at the
top of your eventual report and verify every citation against *that*, not the story's self-description.

Read the entire story file top to bottom -- Context, every AC, Dev Notes, Tasks, File List, any prior
Review Findings. Citations worth checking show up in all of these sections, not just the ACs.

## Step 3: Extract every checkable claim

Build a worklist with four buckets, quoting the story's own exact wording for each item:

1. **Code citations** -- any `path/File.java:NN-MM` or "Lines X-Y" reference, anywhere in the story.
2. **Ledger and precedent attributions** -- citations into `deferred-work.md`, and any "per
   skillars-deferred-N", "per story N", "same pattern as story N" style claim.
3. **Mechanistic claims** -- any assertion about *how* existing code behaves: which API it calls, which
   timing source it uses, whether something is synchronous, transactional, nullable, retried, etc.
4. **Every other story name mentioned** -- precedents this story claims to port, mirror, or reuse.

Don't prune this list for obviousness. Every one of the four defects in the story that prompted this
skill was a claim that read as self-evidently correct.

## Step 4: Verify against real, current source -- in parallel, then adversarially re-check

Launch four subagents in parallel (via the Agent tool), each read-only, each given the real HEAD from
Step 2, the relevant worklist slice, and the instructions below. Do not let any agent assume the
story's framing is correct -- each is verifying, not summarizing.

**Layer 1 -- Citation Verifier** (worklist bucket 1). For each citation: open the actual file at the
current HEAD, go to the cited range, and quote what is really there.
- `MATCH` -- quote the proof.
- `DRIFTED` -- the content moved; search the file/module for where it actually is now and give the
  corrected location. Don't just report "not found."
- `CANNOT_LOCATE` -- state exactly what you searched (grep terms, files checked) before giving up.

**Layer 2 -- Ledger & Precedent Attribution Verifier** (worklist bucket 2). For each ledger citation or
"per deferred-N / per story-N" claim, check **all three** of: the ledger file itself, source code
comments/Javadoc near the method being discussed, and `git log --grep`/`git blame` for that number --
before concluding anything is wrong. A single grep returning zero matches in the ledger is not proof of
fabrication; the original review's "fabricated citation" flag turned out to be a real code comment the
reviewer never checked. Report which sources were checked and what each showed.

**Layer 3 -- Mechanistic Claim Verifier** (worklist bucket 3). For each technical/behavioral claim, open
the **full body** of the specific method or field actually named -- not a snippet, not "the class in
general." Quote the deciding lines. Explicitly check whether a similarly-named or nearby method/field in
the same file could be getting confused with the one the claim is actually about (this is exactly how
the original review attributed one method's timing mechanism to a different one). For any claim that
rests on atomicity, partial-commit, or rollback behavior, trace the actual enclosing `@Transactional` /
`TransactionTemplate` / equivalent scope and state precisely what commits or rolls back together --
never assert partial-completion reasoning without having read the transaction boundary.

**Layer 4 -- Corner Case & Missed-Flow Hunter** (worklist bucket 4, plus general AC coverage). For each
AC, walk the actual current code paths it touches or will touch: boundary values, concurrency, nulls,
config min/max bounds, and whether the story's assumptions about existing behavior actually hold. Flag
genuine missed corner cases and false assumptions -- not style preferences or hypothetical risk. For
every other story this story claims to mirror or reuse, independently verify *that* story's cited
behavior against its own current source too; don't accept this story's paraphrase of another story as
ground truth.

**Step 4b -- Mandatory adversarial re-verification.** Once all four layers return, do this yourself
(don't skip it, don't delegate it away): take every finding and re-read its cited evidence completely
fresh, actively trying to refute it. A finding survives only if you can point to the exact lines that
prove it on a second, skeptical look. Anything that doesn't survive gets dropped or downgraded with a
note on why. This step is the one that was missing when the original review shipped "zero false
positives, HIGH confidence" with four real defects inside it -- a report with uniformly high confidence
and no calibration differences between claims is itself a warning sign that this step didn't happen.

## Step 5: Write the report

Write to `_bmad-output/implementation-artifacts/story-review.md`, **overwriting** any existing content
(the file is not a running log -- old content is superseded, not appended to). Structure:

1. **Header** -- story key, audit date, real HEAD SHA/message from Step 2, note that citations were
   checked against this HEAD, not any SHA the story itself claims.
2. **Citation verification** -- one row per Layer-1 item: citation, verdict, evidence/corrected location.
3. **Ledger & precedent attribution** -- one row per Layer-2 item: claim, sources checked, verdict.
4. **Mechanistic claims** -- one entry per Layer-3 item: the claim, the quoted evidence, verdict.
5. **Corner cases / false assumptions / missed flows** -- Layer-4 findings that survived Step 4b, each
   with file:line evidence.
6. **What did not survive re-verification** -- briefly list anything a layer raised that Step 4b killed,
   and why. This section is what makes the report trustworthy; don't omit it even though it makes the
   report look less clean.
7. **Recommendation** -- per-claim confidence, not a single blanket score. Avoid the phrase "zero false
   positives, HIGH confidence" as a summary line; state what was and wasn't independently re-checked.

## Step 6: Present

Tell the user, briefly: what story was reviewed, against which HEAD, how many claims were checked, how
many findings survived Step 4b vs. were killed by it, and that `story-review.md` was (over)written. Do
not restate the full report in chat -- it's in the file.
