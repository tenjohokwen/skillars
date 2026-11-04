---
name: pr-review
description: Review the current branch's committed changes against master with the measured reviewer agents (general-reviewer and security-reviewer) in parallel, merge and filter their findings, write a review record for HEAD, and present what to fix before opening the PR. Use when the user says "/pr-review", "review this branch", or "review before the PR". The gh-pr-create hook requires this record.
---

# /pr-review

Runs the same reviewers the evals measure (`evals/review/`) over this branch's diff, as subagents on the developer's own login. Nothing leaves the machine except the model calls. Measured cost: about 35k–75k tokens per arm per pass on a 15–20 KB diff, so a typical two-arm review is around 100k tokens; the fan-out table below keeps off-topic diffs to one arm or none.

## 1. Find the change

```bash
git fetch -q origin master
git rev-parse --abbrev-ref HEAD            # must not be master
git rev-parse HEAD                          # SHA, used as the record key
git diff --stat origin/master...HEAD
git status --short                          # uncommitted changes are NOT reviewed; warn if any
```

If the diff is empty, say so and stop. Write the diff the reviewers will see, with generated and documentation files dropped (same filter as the evals' runner):

```bash
mkdir -p .pr-review
git diff origin/master...HEAD -- . ':!docs' ':!**/search-index.js' ':!**/package-lock.json' ':!**/dist/**' ':!**/*.min.js' ':!**/*.min.css' > .pr-review/<SHA>.diff
wc -c .pr-review/<SHA>.diff
```

## 2. Fan out

Decide which arms the diff needs from the changed paths (`git diff --name-only origin/master...HEAD`), so a small or off-topic diff does not pay for reviewers that have nothing to find:

| changed paths include | arms |
|---|---|
| anything under `src/main/`, `src/frontend/src/` (not `__tests__`), `src/main/resources/db/migration/`, `docker-compose*.yml`, `deploy/` | `general-reviewer` |
| additionally `**/api/**`, `**/security/**`, `**/config/**`, `src/frontend/src/router/**`, `src/frontend/src/stores/**`, `src/main/resources/application*.yaml`, `docker-compose*.yml`, `deploy/`, anything named `*Resource.java`, `*Filter*.java`, `*Security*`, `*Auth*` | `general-reviewer` + `security-reviewer` |
| only tests, docs, `evals/`, `.claude/` markdown, root `*.md` | no arms: write a record that says "no reviewable change" and stop |

When in doubt, run both. Launch the chosen subagents in the same message so they run in parallel: `subagent_type: general-reviewer` and `subagent_type: security-reviewer`. If the Agent tool answers that a type is not found (agents added under `.claude/agents/` register a little after a session starts), use `subagent_type: general-purpose` instead and begin the prompt with: "First read `.claude/agents/<name>.md` and adopt everything below its frontmatter as your role and instructions for this task. Use only Read, Grep and Glob." Give each exactly this prompt, with the path filled in:

> Review the pull request diff in `.pr-review/<SHA>.diff` (read it in full; use offset/limit if it is large). You are in the repository at the branch's HEAD, so read callers, callees, guards, constants, migrations and tests as needed to confirm or refute a suspicion. Review the change itself: what it adds, what it changes, what it fails to handle, and what it newly exposes. Report only defects with a concrete trigger and consequence; do not propose fixes.
>
> Output ONLY a JSON object, no prose before or after:
> `{"findings":[{"title":"...","file":"...","symbol":"...","severity":"high|medium|low","category":"security|logic|concurrency|database|edge-case|architecture|performance","description":"2-4 sentences: trigger and consequence","verification":"only if you could not confirm it: the exact check that would settle it"}]}`
>
> An empty `findings` array is a valid answer.

If a subagent returns no parseable JSON, re-ask it once for the JSON only; if it still fails, record "arm failed" in the record and continue with the other arm. If **both** arms fail, do not write the record: the hook must keep blocking until a review has actually happened.

## 3. Merge and filter

Apply these rules, which come from the eval results in `evals/review/results/phase3-security-2026-11-03/NOTES.md`:

1. Keep every `general-reviewer` finding.
2. From `security-reviewer`, keep only findings whose `category` is `security`. Drop the rest (its precision outside its category is below the general reviewer's).
3. Merge duplicates: same file and symbol describing the same defect, or the same defect described from two places. Keep the higher severity and the fuller description; note "both reviewers" on it.
4. Split: a finding with a non-empty `verification` goes to the **Verify before merging** list, not the findings list. These are unsettled, not refuted; the human checks the named thing.
5. Sort findings high, medium, low.

## 4. Write the record

Write `.pr-review/<SHA>.md` (git-ignored; the PR-creation hook checks that this file exists for HEAD; a branch that changes only docs, `evals/`, `.claude/` or root `*.md` passes the hook without one):

```
# PR review: <branch> @ <SHA>
Date: <ISO date>  Base: origin/master @ <base SHA>  Diff: <n> files, <bytes>
Reviewers: general-reviewer (<n> findings), security-reviewer (<n> findings, <n> kept as security)

## Findings
| # | sev | category | file :: symbol | title |
...
### F1 <title>
<description>  (both reviewers)

## Verify before merging
| # | file :: symbol | claim | check |
...

## Dropped
<count> security-reviewer findings outside the security category; <count> merged as duplicates.
```

Also save the two raw JSON replies as `.pr-review/<SHA>.general.json` and `.pr-review/<SHA>.security.json`.

## 5. Present

Show the findings grouped by severity with file and symbol, then the verify list. For each finding say in one line what the reader should look at; do not start fixing. End with: fixes are the developer's call; after fixing and committing, run `/pr-review` again, because the record is keyed to the commit. If there are no findings and no verify items, say so plainly and still write the record.
