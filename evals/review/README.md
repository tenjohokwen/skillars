# Review evals

Tests for the AI code reviewer. Each **case** is a piece of this repo's real history
where a bug existed, together with the findings a good reviewer should have raised.
Running the reviewer over the cases and scoring the result tells us whether a prompt
change, a new specialised reviewer or a model change made the reviewer better or worse.

Nothing here runs in `mvn verify` or in CI. Everything runs locally on the developer's
own Claude Code login (`claude -p`); no API key is involved.

Requirements: git, bash, python3 with PyYAML (`python3 -m pip install --user pyyaml`).

## Layout

```
evals/review/
  README.md            this file
  cases/               one YAML per case (see schema below)
  cases/_drafts/       candidate cases awaiting curation; not run
  bin/materialize.sh   exports a case's reviewer input OUTSIDE the repo (see below)
  bin/validate.py      schema + anchor check; run before committing any case change
  bin/run.py           runs one reviewer arm over cases, stores findings
  bin/judge.py         LLM judge: matches reported findings to expected ones
  bin/scorecard.py     aggregates a run's verdicts into results/<run-id>/scorecard.md
  prompts/             reviewer arms, input-mode fragments, judge prompt
  results/<run-id>/    committed: findings, verdicts, scorecard; git-ignored: raw/
```

Exports land in `${XDG_CACHE_HOME:-$HOME/.cache}/skillars-review-evals/<id>/`
(override with `SKILLARS_REVIEW_EVALS_WORK`; the script refuses a path inside the repo).

## Where the cases came from

The v0 set was mined from the 53 fix PRs merged in October 2026 (the security audit S-xx
round and three booking/payment audits). Each PR body states the defects it closed, so a
case is the state of the code *before* that PR with the PR's own defect list as the
expected findings. 52 PRs yielded 62 draft cases; 25 were promoted to `cases/` for v0
(all four confirmed introducing-diff pairs, plus snapshots chosen for category spread,
high curator confidence and a scope a reviewer can read in one sitting). The other 37
stay in `cases/_drafts/` as a backlog: valid, validated, not yet run.

## Adding a case

1. Draft it in `cases/_drafts/` following the schema below. The usual trigger is a merged
   fix PR that closes a defect an earlier PR shipped.
2. Run `evals/review/bin/validate.py evals/review/cases/_drafts/<id>.yaml`. Zero problems
   is the bar; the validator checks that every anchor file exists at the input revision
   and that every named symbol occurs in it as a whole word (for a YAML anchor, that every
   segment of the dotted key path occurs as a key or a `- KEY=` list entry).
3. Promote with `git mv` once a second person has read the descriptions for fix leakage.
4. `evals/review/bin/validate.py` with no arguments validates the whole promoted set.

## Two kinds of case

| kind | reviewer input | run the reviewer from | models |
|---|---|---|---|
| `introducing-diff` | `input.diff`: the diff of the PR that introduced the bug (`<merge>^1..<merge>`) | `<work>/<id>/` | a PR review |
| `pre-fix-snapshot` | `tree/`: the source tree at the commit *before* the fix merged, with `scope.txt` listing the files to audit | `<work>/<id>/tree/` | a focused audit of existing code |

Prefer `introducing-diff` when the fixing PR names the PR that introduced the bug and
that diff really contains it. Otherwise use `pre-fix-snapshot`.

### The reviewer must never see the fix

`materialize.sh` exports with `git archive`, so the export has no `.git` directory and no
history, and it lives outside the repository, so no parent folder holds the HEAD checkout
or the answer-sheet YAML. The repo's own `.claude/`, `_bmad*/` and `evals/` are stripped
from the export, so the reviewer under test does not inherit this project's skills, which
by now encode what these very fixes taught.

Two rules follow for whoever writes the runner:

- Start `claude -p` with the export directory as its working directory, never the repo,
  and never pass the repo via `--add-dir`.
- Pass `--setting-sources project` so user-level `~/.claude` skills and settings are not
  loaded either; the export has no project settings, so the reviewer starts from exactly
  the prompt and agents the runner gives it.

## Case schema

```yaml
id: 353-agreed-price-null-fallback       # <fix PR>-<slug>
kind: introducing-diff | pre-fix-snapshot
fix_pr: 353
fix_merge: b6a7945f                       # squash/merge commit of the fix PR on master
introducing_pr: 335                       # introducing-diff only
introducing_merge: e60141fc               # introducing-diff only; input = introducing_merge^1..introducing_merge
scope:                                    # pre-fix-snapshot only: files/dirs the reviewer is pointed at
  - src/main/java/com/softropic/skillars/platform/booking/service/BookingService.java
summary: one line, what area this case exercises
confidence: high | medium | low          # curator's confidence that anchors and descriptions are accurate
expected_findings:
  - id: F1
    title: Accept falls back to the coach's current price when agreed_price is null
    category: logic            # security | logic | concurrency | database | edge-case | architecture | performance
    severity: high             # high | medium | low
    description: >
      Two to four sentences stating the defect as a reviewer would see it at the input
      revision. Describe the bug, not the fix. Say what input or state triggers it and
      what goes wrong.
    anchors:                   # where the bug lives at the INPUT revision
      - file: src/main/java/.../BookingService.java
        symbol: resolveSessionPrice
non_findings:                  # optional: things that look wrong but are intended (precision checks)
  - title: ...
    reason: ...
source: PR #353 description, "Gap 1"
notes: anything a future curator should know
```

**Open-bug cases.** A defect found by a reviewer and confirmed by a human before any fix
exists (see `results/*/real-sample.md`) is still a case: set `fix_pr: pending`, omit
`fix_merge`, and pin `input_rev:` to the commit the reviewer saw. When the fix merges,
replace `pending` with the PR number, add `fix_merge`, drop `input_rev`.

Rules for `expected_findings`:

- One entry per independent defect. A fix PR that closes five gaps yields five entries,
  or several cases if they live in unrelated areas.
- `description` must be verifiable against the input revision alone. If you cannot point
  at the lines, it is not a finding; move it to `notes`.
- `anchors` use paths as they exist at the input revision, not at HEAD.
- Do not leak the fix. "Missing idempotency key" is a finding; "should add
  `Idempotency-Key` header like PR #359 did" is the answer sheet.

## How this fits the dev lifecycle

1. **Before a PR, run `/pr-review`** on the branch (skill in `.claude/skills/pr-review/`). It
   fans out the measured reviewer agents (`.claude/agents/general-reviewer.md` and
   `security-reviewer.md`) over the branch diff as parallel subagents, merges and filters
   their findings with the rules the evals established, writes `.pr-review/<HEAD sha>.md`
   (git-ignored) and presents what to fix. Re-run after every new commit; the record is
   keyed to the commit.
2. **`gh pr create` is gated** by `.claude/hooks/require-pr-review.sh` (wired in
   `.claude/settings.json`): no record for HEAD, no PR. A branch whose changed files are
   all on the CI classifier's non-code allowlist passes automatically (decided from git, not
   from the command text); starting the command with `PR_REVIEW_SKIP=1` skips deliberately
   and visibly. The hook fails closed when it cannot parse the command or resolve HEAD, and
   matches only a real invocation at a command boundary, not the phrase inside text.
3. **Change a reviewer only with an eval run.** The agents' bodies are the arms the runner
   measures, so a prompt edit is a reviewer change: run the arm over the promoted cases,
   judge, build the scorecard, and compare with the previous run in `results/` before
   merging the edit. To measure an edit before it lands, point `SKILLARS_REVIEW_EVALS_DIR`
   at a staging copy that carries the edited agent as `agents/<name>.md`; the runner reads
   that before the committed `.claude/agents/<name>.md`. The acceptance rule is in "Arms"
   below.
4. **Grow the dataset from real bugs.** A merged fix PR that closes a defect an earlier PR
   shipped is a new case (see "Adding a case"). A reviewer finding the judge calls `real`
   and a human confirms is a new `pending` case or an added expected finding, and the
   fix PR later flips `pending` to its number.

None of this runs in CI. Everything is the developer's own Claude Code login.

## Running a baseline

```
RUN=baseline-$(date +%F)
evals/review/bin/run.py   $RUN --arm control        # all promoted cases; --cases a,b to limit
evals/review/bin/run.py   $RUN --arm blind-hunter
evals/review/bin/judge.py $RUN --arm control
evals/review/bin/judge.py $RUN --arm blind-hunter
evals/review/bin/scorecard.py $RUN                  # writes results/$RUN/scorecard.md
```

Runs resume: a case whose findings (or verdict) file already exists is skipped, so a
run interrupted halfway is restarted with the same command. `--parallel N` (default 3)
bounds concurrent `claude -p` processes. `--model` and `--effort` pass through to the
reviewer and are recorded in `results/<run-id>/run.json`.

### Arms

| arm | prompt | diff cases | snapshot cases |
|---|---|---|---|
| `control` | one plain review prompt, no layers | diff inlined, no tools | runs inside the exported tree with Read/Grep/Glob; scope list inlined |
| `blind-hunter` | the Blind Hunter layer from `mto-code-review` | diff inlined, no tools | scoped files inlined, no tools |
| `security` | the `.claude/agents/security-reviewer.md` agent | diff inlined, no tools | Read/Grep/Glob inside the tree; scope list inlined |

`control` is the floor every later reviewer must beat. Specialised reviewers are real
Claude Code agents under `.claude/agents/`; an arm uses the agent file's body as its prompt
(frontmatter stripped, input-mode fragment and input appended), so what is measured is
exactly what runs in an interactive session. A specialised arm reports only its own kind
of defect by design, so judge it on two numbers: recall in its category must rise over
`control`, and its own precision must not fall below `control`'s. Compare arms only within
one run (same cases, same judge version).

### Scoring

An expected finding scores 1 for a `full` match, 0.5 for `partial`, 0 for `none`.

- recall = matched points / expected findings
- precision = reported findings that matched something or were judged `real` / reported findings

Both are reported per arm, per category, per case kind and per case. The judge runs in
two passes. Pass 1 is a tool-less `claude -p` call that sees the case YAML (the answer key)
and the reported findings and does the matching. Pass 2 (judge v2) re-checks each unmatched
report graded `real` that carries a `verification` caveat, with one tool-less call that gets
the cited file (or, for a diff case, that file's hunks) inlined, and downgrades refuted ones
to `noise`. A check that cannot settle it (the caveat points outside what the reviewer saw)
yields `unverified`: not credited to precision, not called noise, reported in its own
column. The judge never sees the fix. A 20-item owner sample of the v1 judge's `real` verdicts held up at 90%, and both
misses were findings whose own caveat named the check that refutes them, which is what pass 2
runs. Matching is identical across versions, so recall is comparable; precision is not,
and verdict files record `judge_version`.

### Cost and isolation

Every reviewer and judge call runs on the developer's own Claude Code login. The runner
strips `ANTHROPIC_API_KEY` from the environment, runs from the export directory with
`--setting-sources project`, and disables session persistence. A full two-arm baseline
over 25 cases is roughly 50 reviewer calls plus 50 judge calls; smoke-test with
`--cases` on four or five cases before committing to that.
