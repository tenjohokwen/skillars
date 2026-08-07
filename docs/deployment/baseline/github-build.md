# GitHub CI/CD — Build & Deploy Pipeline

This document describes the three GitHub Actions workflows that take a code change from pull request
to running production container: `pr-build.yml`, `ci.yml`, and `deploy.yml`. It covers what each one
does, how they hand off to each other, the secrets they require, and the known gaps a developer needs
to close before a production deploy will succeed.

---

## Overview

The three workflows form one pipeline, split by **what triggers them**:

| Workflow | Trigger | Tests | Builds image | Pushes to GHCR | Deploys |
|---|---|---|---|---|---|
| `pr-build.yml` | Pull request → `master` | yes | yes | no | no |
| `ci.yml` | Push to `master` | yes | yes | yes | no |
| `deploy.yml` | Manual (`workflow_dispatch`) | no | no | no | yes |

The flow:

```
PR opened ──► pr-build.yml ──► gate: tests + CVE scan, image discarded
                  │
              (merge)
                  ▼
push to master ──► ci.yml ──► ghcr.io/tenjohokwen/skillars:sha-abc1234
                                        │
                              (human copies the tag)
                                        ▼
                              deploy.yml ──► production node
```

The handoff from `ci.yml` to `deploy.yml` is **manual and deliberate**. `ci.yml` produces a tagged
image; a human then triggers `deploy.yml` and types that tag into the input box. Nothing deploys
automatically on merge.

---

## Shared component: `.github/actions/docker-build`

Both `pr-build.yml` and `ci.yml` build the image through a shared composite action at
`.github/actions/docker-build/action.yml`, so build behaviour stays identical between the PR gate and
the master build. It wraps `docker/setup-buildx-action` and `docker/build-push-action` with GitHub
Actions layer caching (`cache-from: type=gha`, `cache-to: type=gha,mode=max`).

Key inputs:

- `push` — push to the registry (`ci.yml` sets `'true'`, `pr-build.yml` sets `'false'`)
- `load` — load into the local Docker daemon so the image can be scanned or run in the same job
  (`pr-build.yml` sets `'true'`; required for the Trivy step to see the image)
- `tags`, `labels`, `platforms` (defaults to `linux/amd64`)

All third-party actions across all three workflows are **pinned to a full commit SHA** with the
version in a trailing comment. Keep it that way — a mutable tag like `@v4` is a supply-chain risk.

---

## `pr-build.yml` — the gate

**Trigger:** `pull_request` targeting `master`.

Answers the question "would merging this break things?" It produces no lasting artifact — everything
it builds is discarded when the runner is destroyed.

Steps:

1. Checkout, set up JDK 17 (Temurin), restore the Maven cache keyed on `hashFiles('**/pom.xml')`
2. `mvn -B verify -q` — full compile and test, including Testcontainers integration tests
3. Build the Docker image with `push: 'false'`, `load: 'true'`, tagged `skillars-app:pr-<number>`
4. Scan that image with Trivy at `severity: CRITICAL,HIGH` and `exit-code: '1'`

A CRITICAL or HIGH CVE fails the PR. The `concurrency` block cancels an in-flight run when new
commits are pushed to the same PR, since only the latest commit matters.

**Secrets required: none.** This is the only workflow of the three that has never been blocked on
missing configuration.

---

## `ci.yml` — the builder

**Trigger:** `push` to `master`.

Turns a merged commit into a durable, addressable artifact in GitHub Container Registry.

Two jobs:

**`test`** — checkout, JDK 17, Maven cache, `mvn -B verify -q`, 15-minute timeout. Mirrors
`pr-build.yml` step-for-step so the master gate and the PR gate cannot drift apart.

**`build-and-push`** — declares `needs: test`, so a failing test blocks the push rather than shipping
an untested image. It logs into GHCR, computes a 7-character short SHA, and pushes:

```
ghcr.io/tenjohokwen/skillars:sha-abc1234
```

That tag is the handoff token to the deploy stage. Three OCI labels are attached:
`image.source` (which links the package to the repo in the GitHub UI), `image.revision`, and
`image.created`.

### GHCR authentication

The GHCR login uses the built-in `secrets.GITHUB_TOKEN`, not a personal access token:

```yaml
- name: Log in to GHCR
  uses: docker/login-action@...  # v3.7.0
  with:
    registry: ghcr.io
    username: ${{ github.actor }}
    password: ${{ secrets.GITHUB_TOKEN }}
```

This works because the job declares `packages: write` and pushes to the repository's *own* package.
`GITHUB_TOKEN` is minted per-run, expires when the job ends, and needs no rotation.

> **History:** this step previously used `secrets.GHCR_PAT`, which was never configured on the
> repository. The workflow failed on six consecutive runs with `##[error]Password required` — the
> login action refuses to run with an empty password. Do not reintroduce a PAT here; there is no
> scenario in which this workflow needs one.

---

## `deploy.yml` — the shipper

**Trigger:** `workflow_dispatch` only, with a required `image_tag` input (e.g. `sha-abc1234`).

Deploys a previously-built GHCR image to the production node over SSH. It is a safe-deploy state
machine with automatic rollback:

1. **Load SSH key** into an agent (`webfactory/ssh-agent`) and append the node's host key to
   `known_hosts`.
2. **Validate `image_tag`** against `^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$`. This is not cosmetic — the
   tag is interpolated into shell commands executed over SSH, so this is injection defense.
3. **Capture the pre-deploy image** by inspecting the running `app` container, saving it to
   `steps.pre_deploy.outputs.image` as the rollback target (or `none` if nothing is running).
4. **Authenticate to GHCR on the node** by piping a credential over SSH into `docker login --password-stdin`.
5. **Deploy** — `docker compose pull app`, rewrite the `APP_IMAGE=` line in `.env` via `sed`, then
   `docker compose up -d --no-deps app`.
6. **Smoke test** — sleep 60s for JVM startup (matching the compose `start_period`), then poll
   `/manage/health` inside the container up to 12 times at 5s intervals, looking for `"status":"UP"`.
7. **Auto-revert** if the smoke test failed: restore the previous image and bring it back up. `$PREV`
   is re-validated against a regex before use, for the same injection reason as step 2.
8. **Notify** via Slack and email, with distinct success and failure payloads.
9. **Fail the workflow** with `exit 1` if the smoke test failed, so the run shows red even though the
   revert succeeded.

`concurrency: deploy-production` with `cancel-in-progress: false` means concurrent deploys **queue**
rather than interrupt each other. A half-finished deploy must never be cancelled mid-flight.

### Secrets required

`deploy.yml` depends on eleven secrets. **[`secrets-reference.md`](../secrets-reference.md) is the
authoritative source** for their formats and how to generate each one — this list is only a map of
which step consumes what:

| Secret | Consumed by |
|---|---|
| `SSH_DEPLOY_KEY` | Step 1 — private key loaded into the SSH agent |
| `SSH_KNOWN_HOST` | Step 1 — node host key, appended to `known_hosts` |
| `SSH_USER`, `SSH_HOST` | Steps 3–7 — SSH target for every remote command |
| `GHCR_PAT` | Step 4 — `docker login` on the node (see caveat below) |
| `SLACK_WEBHOOK_URL` | Step 8 — success and failure notifications |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `NOTIFY_EMAIL` | Step 8 — email notifications |

Note that `GHCR_PAT` needs only `read:packages` for this use — the node pulls, it never pushes.

---

## Known gaps

These must be resolved before a production deploy will succeed.

### 1. No production node exists yet — `deploy.yml` is untested

No server has been provisioned. `deploy.yml` has therefore **never run**, and none of it — the SSH
connection, the compose invocation, the health-check path, the revert logic — has been executed
against a real host. Treat it as unverified code, not as a working deploy path.

Consequently the repository has **zero** secrets and zero environments:

```bash
gh secret list --json name              # → []
gh api repos/:owner/:repo/environments  # → {"total_count":0,"environments":[]}
```

This is a *pending* state, not a misconfiguration: eight of the eleven secrets (`SSH_*`, `GHCR_PAT`)
cannot be generated until a node exists and its host key is known. `ci.yml` and `pr-build.yml` need
no secrets and run fine today.

**When a node is provisioned**, work through
[`first-time-setup.md`](../first-time-setup.md) and
[`secrets-reference.md`](../secrets-reference.md), then expect the first `deploy.yml` run to surface
problems that static review cannot catch.

### 2. `GHCR_PAT` on the deploy node cannot simply be swapped for `GITHUB_TOKEN`

The fix applied to `ci.yml` does **not** transfer to `deploy.yml` step 4. The credential piped over
SSH is persisted into `~/.docker/config.json` on the node, and `GITHUB_TOKEN` is revoked when the
workflow job ends — leaving a stale credential behind on the host. It happens to work for the
`docker compose pull` in the very next step (same job, token still valid), but it is fragile.

For a long-lived deploy node, use a real PAT with `read:packages` scope, or a GitHub App installation
token.

### 3. Duplicated test job

The four test steps are now duplicated verbatim between `ci.yml` and `pr-build.yml`. This is
acceptable at two call sites and keeps both files readable standalone. If a third caller appears, or
the two copies start to drift, collapse them into a reusable workflow via `workflow_call`.

---

## Common tasks

**Find the image tag for a commit:**

```bash
git rev-parse --short=7 HEAD    # → abc1234, so the tag is sha-abc1234
```

**Trigger a production deploy:**

```bash
gh workflow run deploy.yml -f image_tag=sha-abc1234
gh run watch                    # follow it live
```

**Inspect a failed CI run:**

```bash
gh run list --workflow=ci.yml --limit 10
gh run view <run-id> --log-failed
```

**Add a missing secret:**

```bash
gh secret set SSH_HOST          # prompts for the value
gh secret set SSH_DEPLOY_KEY < ~/.ssh/deploy_key
```
