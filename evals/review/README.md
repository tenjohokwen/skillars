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

Rules for `expected_findings`:

- One entry per independent defect. A fix PR that closes five gaps yields five entries,
  or several cases if they live in unrelated areas.
- `description` must be verifiable against the input revision alone. If you cannot point
  at the lines, it is not a finding; move it to `notes`.
- `anchors` use paths as they exist at the input revision, not at HEAD.
- Do not leak the fix. "Missing idempotency key" is a finding; "should add
  `Idempotency-Key` header like PR #359 did" is the answer sheet.

## Scoring (phase 2)

Recall: share of `expected_findings` the reviewer reported. Precision: share of the
reviewer's findings that match an expected finding or are judged real. Both per case
and per category.
