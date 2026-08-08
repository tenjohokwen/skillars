# Did the PR build actually test my change?

A green PR check does **not** mean your change was tested. It means the checks that ran, passed —
which is a different claim, and sometimes a much weaker one.

This document explains how to tell the difference, and what to do when the answer is "no, nothing
tested it."

For what the three workflows *do*, see [`github-build.md`](./github-build.md). This document is only
about **reading** their results.

---

## The one fact everything follows from

Each workflow declares its own trigger, and **a pull request only ever runs `pr-build.yml`**:

| Workflow | Trigger | Fires when |
|---|---|---|
| `pr-build.yml` | `pull_request` → `master` | You open a PR, **and on every push to that branch** |
| `ci.yml` | `push` → `master` | Something lands on master (i.e. a merge) |
| `deploy.yml` | `workflow_dispatch` | You click "Run workflow". Never automatically. |

A PR never runs `ci.yml`. So **anything that exists only in `ci.yml` is invisible to every PR
check you will ever see.** The same goes, more strongly, for `deploy.yml`.

> `pr-build.yml` also sets `concurrency: cancel-in-progress`. Pushing twice in quick succession
> cancels the first run, so you get one result, not two. If a run vanishes from the list, that is
> usually why.

---

## Three ways a change escapes being tested

### 1. It lives in a workflow the PR doesn't fire

The most common case. `docker/login-action` appears exactly once in this repo, at `ci.yml:61`.
No pull request can execute it.

### 2. The step ran, but only because an earlier one did

`ci.yml`'s `build-and-push` job declares `needs: test`. If the test job fails, the entire
`build-and-push` job — including the GHCR login and the image push — is **skipped**, not failed.

This is why a red run tells you *less* than a green one: everything downstream of the failure is
simply unknown.

### 3. Same action, different inputs

The subtle one. Both workflows call the repo's own `./.github/actions/docker-build`, but not the
same way:

```yaml
# pr-build.yml:89-93   "Build Docker image (no push)"
  uses: ./.github/actions/docker-build
  with:
    push: 'false'
    load: 'true'

# ci.yml:71-74         "Build and push Docker image"
  uses: ./.github/actions/docker-build
  with:
    push: 'true'
```

Same composite action, different code path. A PR proves the image **builds**. It proves nothing
about the **push**, because the push branch never executed.

---

## How to check — before you merge

Two commands. First, find every place the thing you changed is used:

```bash
grep -rn "docker/login-action" .github/
```

Then, for each file that turns up, check whether a PR fires it:

```bash
grep -A3 "^on:" .github/workflows/ci.yml
```

If every hit is in a file a pull request doesn't trigger, the green tick on your PR is about other
things entirely. Merge that change **alone**, and watch the first master run.

---

## How to check — after a run

The PR checks summary shows one line per workflow: `build — pass`. That is not enough resolution,
because **a step that is missing and a step that passed look identical from there.**

Ask what actually executed:

```bash
gh run view <run-id> --json jobs \
  --jq '.jobs[] | "JOB \(.name): \(.conclusion)", (.steps[] | "  \(.conclusion) \(.name)")'
```

Two things to look for:

1. **Is the step you care about present at all?**
2. **Did it run, or is it `skipped`?**

### Worked example

This is a real PR build — the one for the `docker/login-action` bump (PR #13):

```
PR #13, run 31251298777  (pr-build.yml)
  JOB build: success
    success  Run actions/checkout@34e114876
    success  Set up JDK 17
    success  Cache Maven dependencies
    success  Start container sampler
    success  Build and test
    success  Assert container ceiling (AC1)
    success  Upload test reports
    success  Build Docker image (no push)
    success  Scan image for vulnerabilities
```

Every step succeeded. **And `Log in to GHCR` is not in the list** — the step that PR modifies does
not exist in this workflow. The green tick is entirely about unrelated work.

Now the same repo on master, where `ci.yml` runs:

```
master, run 31252433836  (ci.yml)
  JOB test: success
    success  Checkout
    success  Set up JDK 17
    success  Cache Maven dependencies
    success  Build and test
    success  Upload test reports
  JOB build-and-push: success
    success  Checkout
    success  Log in to GHCR          <-- only ever runs here
    success  Compute short SHA
    success  Build and push Docker image
```

`Log in to GHCR` and `Build and push Docker image` exist only on master. That is the whole
argument, visible in two lists.

---

## What to do about it

The practical rule:

> **Serialize when a change's own build cannot validate it** — different trigger, different
> workflow, or a manual-only path. **Batch freely** when the PR build genuinely exercised the
> changed step.

Waiting for master between every single merge is cargo cult. Each wait costs a full pipeline run
(~10–15 min here, because `build-and-push` sits behind `needs: test`) and buys nothing when the PR
build already ran the step. What waiting actually buys is **attribution**: if master goes red, you
know which merge did it. Spend that only where the change is genuinely unvalidated, and merge the
unvalidated one **last** so the attribution is unambiguous.

### Worked example — four real PRs

| PR | Step it changes | Present in a PR run? | Action |
|---|---|---|---|
| #18 `dependabot.yml` | none — config only | N/A, GitHub validates the file directly | Merge freely |
| #11 `actions/checkout` 4→7 | `Checkout` | **Yes** — ran and passed on v7 | Merge freely |
| #13 `docker/login-action` 3→4 | `Log in to GHCR` | **No** — absent from `pr-build.yml` | Merge alone, watch master |
| #1 `webfactory/ssh-agent` | `deploy.yml` only | **No** — absent from master too | Only a real deploy tests it |

#1 is worth dwelling on: it is in `deploy.yml`, which is `workflow_dispatch`. Neither PR builds nor
master pushes touch it. **No amount of CI will ever validate that bump.** Either merge it accepting
that your next deploy is the real test, or dispatch `deploy.yml` deliberately against a non-production
target first. Decide consciously — do not let a stale red tick sit there implying something is broken
when the check never had anything to say.

---

## Applying this to your own changes

This is not a Dependabot-specific concern. The same gap applies whenever you touch:

- anything in `ci.yml` or `deploy.yml`
- the GHCR push path or its credentials
- a `docker-build` input that only `ci.yml` sets
- anything gated behind an `if:` that is false on pull requests

All of these land on master untested by definition. That is precisely when you merge alone and
watch — not because merging is risky in general, but because for *those* changes the first real
execution is the one on master.
