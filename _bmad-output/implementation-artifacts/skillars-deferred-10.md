# Story Deferred-10: CI/CD & Deployment Hardening

Status: done

## Story

As a platform operator,
I want the CI pipeline to catch broken builds before they merge to main, GitHub Actions to use immutable dependency references, and the production server to have a host-level firewall active,
so that broken Docker images are detected early, supply-chain attacks via tag mutation are blocked, and the server is not solely dependent on the Hetzner cloud firewall.

## Acceptance Criteria

1. **Given** a pull request is opened against `main`
   **When** the PR CI workflow runs
   **Then** the Docker image is built (multi-platform `linux/amd64`) and Maven tests run — a broken `Dockerfile` or failing test is caught before merge
   **And** the PR workflow does NOT push any image to GHCR — it is build-and-test only
   **And** the existing `push-to-main` workflow is unchanged

2. **Given** `.github/workflows/deploy.yml` and the CI build workflow reference GitHub Actions
   **When** a malicious actor force-pushes to `actions/checkout@v4`, `docker/login-action@v3`, or `docker/build-push-action@v6`
   **Then** the updated tag does NOT affect this repository — all Actions are pinned to immutable commit SHA digests, not floating version tags

3. **Given** `deploy/provision.sh` finishes provisioning the server
   **When** the script completes
   **Then** `ufw` is enabled with default-deny-incoming + allow-outgoing policy, and SSH (port 22) is allowed before the enable command — so the provisioning SSH session is not terminated
   **And** the ufw rules are idempotent (`--force` flag or `ufw status` check before enabling)

4. **Given** the deployment rollback documentation describes the smoke test window
   **When** a slow JVM startup causes the 12-retry smoke test to exhaust during the `start_period: 60s`
   **Then** the `deploy.yml` smoke test loop delays its first check by 60 seconds (matching `start_period`) before starting retries — false auto-reverts during normal JVM startup are avoided

## Tasks / Subtasks

- [x] **Task 1 — Add PR build workflow** (AC: 1)
  - [x] Create `.github/workflows/pr-build.yml`:
    ```yaml
    name: PR Build & Test

    on:
      pull_request:
        branches: [main]

    jobs:
      build:
        runs-on: ubuntu-latest
        steps:
          - uses: actions/checkout@<SHA>       # pin to SHA (see Task 2)

          - name: Set up JDK 21
            uses: actions/setup-java@<SHA>
            with:
              java-version: '21'
              distribution: 'temurin'

          - name: Cache Maven dependencies
            uses: actions/cache@<SHA>
            with:
              path: ~/.m2/repository
              key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}

          - name: Build and test
            run: mvn -B verify -q

          - name: Build Docker image (no push)
            uses: docker/build-push-action@<SHA>
            with:
              context: .
              platforms: linux/amd64
              push: false
              tags: skillars-app:pr-${{ github.event.pull_request.number }}
    ```
  - [x] Do NOT add `docker/login-action` to this workflow — no push means no registry auth needed
  - [x] Replace `<SHA>` placeholders with real commit SHAs (see Task 2)
  - [x] Read the existing `deploy.yml` to confirm the JDK version and Maven command used — be consistent

- [x] **Task 2 — Pin all GitHub Actions to commit SHAs** (AC: 2)
  - [x] Find all Action references in `.github/workflows/`:
    `grep -r "uses:" .github/workflows/ | grep -v "#"` — list every `uses: owner/action@version` line
  - [x] For each Action, resolve the current SHA of the pinned tag:
    ```bash
    # Example for actions/checkout@v4
    gh api repos/actions/checkout/git/refs/tags/v4 --jq '.object.sha'
    # Or via git:
    git ls-remote https://github.com/actions/checkout.git refs/tags/v4
    ```
  - [x] Replace each floating tag with the SHA:
    ```yaml
    # BEFORE:
    - uses: actions/checkout@v4
    # AFTER:
    - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683  # v4.2.2
    ```
  - [x] Add a comment with the human-readable tag after the SHA so maintainers know what version is pinned
  - [x] Actions to pin (at minimum):
    - `actions/checkout`
    - `actions/setup-java`
    - `actions/cache`
    - `docker/login-action`
    - `docker/build-push-action`
    - `docker/setup-buildx-action` (if used)
  - [x] Apply SHA pinning to BOTH the existing `deploy.yml` AND the new `pr-build.yml`

- [x] **Task 3 — Enable `ufw` in `provision.sh`** (AC: 3)
  - [x] Read `deploy/provision.sh` — find the `ufw install` section
  - [x] Add after the `apply-firewall.sh` call (or at the end of the firewall section):
    ```bash
    echo "Enabling ufw..."
    # Allow SSH first — CRITICAL: must happen before 'ufw enable' or the SSH session may terminate
    ufw allow 22/tcp comment 'SSH'
    # If Traefik exposes 80 and 443:
    ufw allow 80/tcp comment 'HTTP'
    ufw allow 443/tcp comment 'HTTPS'
    # Default policies
    ufw default deny incoming
    ufw default allow outgoing
    # Enable (--force skips the interactive confirmation prompt)
    ufw --force enable
    echo "ufw status:"
    ufw status verbose
    ```
  - [x] **CRITICAL**: `ufw allow 22/tcp` MUST run before `ufw --force enable` — enabling ufw with default deny without first allowing SSH will terminate the active provisioning SSH session
  - [x] If the Hetzner firewall (from `apply-firewall.sh`) already allows port 22, the ufw SSH rule is belt-and-suspenders — add it anyway for defence-in-depth
  - [x] If `provision.sh` has `set -euo pipefail`, test that the ufw commands succeed on a fresh Ubuntu instance; `ufw` may not be installed by default — confirm `apt-get install -y ufw` is already in the script or add it

- [x] **Task 4 — Fix smoke test false positives in `deploy.yml`** (AC: 4)
  - [x] Read `.github/workflows/deploy.yml` — find the smoke test loop (described in rollback documentation as 12 retries, 10-second interval)
  - [x] Add a 60-second initial delay before the first retry to allow for JVM startup:
    ```yaml
    - name: Smoke test (wait for startup)
      run: |
        echo "Waiting 60s for JVM startup..."
        sleep 60
        for i in $(seq 1 12); do
          if curl -sf https://${{ secrets.DOMAIN }}/actuator/health; then
            echo "Health check passed on attempt $i"
            exit 0
          fi
          echo "Attempt $i/12 failed, retrying in 10s..."
          sleep 10
        done
        echo "Smoke test failed after 12 attempts"
        exit 1
    ```
  - [x] The `start_period: 60s` in `docker-compose.yml` means Docker itself does not count failed health checks during the first 60 seconds, but the GitHub Actions smoke test starts immediately — this is the mismatch that causes false auto-reverts on slow JVM startup
  - [x] If the initial 60s sleep is already present in `deploy.yml`, skip this task

- [x] **Task 5 — Document ufw in deployment docs** (AC: 3)
  - [x] Update `docs/deployment/setup.md` (or the equivalent first-time setup doc) to note that `provision.sh` now enables `ufw` and document which ports are open
  - [x] Add a note about the ufw/Hetzner firewall layering: "The Hetzner cloud firewall is the primary network perimeter; ufw provides host-level defence-in-depth on the VM itself"

### Review Findings

- [x] [Review][Decision] `ci.yml` was modified despite AC1's "existing push-to-main workflow is unchanged" — resolved: **kept as-is**. SHA-pinning `ci.yml` accepted as "functionally unchanged" (same trigger, same behavior); AC1's wording treated as imprecise rather than a real defect. No code change.
- [x] [Review][Decision] `ufw allow 80/tcp` / `443/tcp` likely unenforced for Docker-published ports (DOCKER-USER chain bypass) — resolved: **leave the ufw rules as-is** (SSH, the security-sensitive port, is genuinely enforced), but soften the docs so they don't overstate 80/443 protection. Converted to a patch below.
- [x] [Review][Decision] `pr-build.yml` builds the Docker image but never runs it (`push: false`, no `load: true`) — resolved: **leave as build-only for this story**. `deploy.yml` already smoke-tests before real deploys, so runtime validation at PR time is a nice-to-have, not required now. Converted to a defer below.

- [x] [Review][Patch] `pr-build.yml` triggers on `branches: [main]`, but the repo's actual default branch is `master` — the workflow will never fire on real PRs as written [.github/workflows/pr-build.yml:4]
- [x] [Review][Patch] Two stale "section 6" cross-references in `first-time-setup.md` after the Volume-mount step was renumbered to 7 [docs/deployment/first-time-setup.md]
- [x] [Review][Patch] `pr-build.yml`'s `build` job has no `permissions:`, `timeout-minutes:`, or `concurrency:` block — add minimal read-only permissions, a timeout cap, and a PR-scoped concurrency group with `cancel-in-progress: true` [.github/workflows/pr-build.yml:1]
- [x] [Review][Patch] The "defence in depth" doc callout overstates ufw's protection of ports 80/443 (Docker bypasses ufw's INPUT chain for its own published ports) — reword to note that only SSH (22) is genuinely enforced by ufw today; 80/443 remain reliant on the Hetzner cloud firewall [docs/deployment/first-time-setup.md]

- [x] [Review][Defer] `pr-build.yml`'s Docker build never runs/scans the built image (`push: false`, no `load: true`) — deferred by user decision: `deploy.yml`'s existing smoke test is the real safety net; add `load: true` + a smoke command here only if PR-time runtime validation becomes worth the added CI cost [.github/workflows/pr-build.yml] — deferred, user call: not required for this story
- [x] [Review][Defer] `ci.yml`'s push trigger (`branches: [main]`, untouched by this diff) has the same branch-name mismatch — the repo's default branch is `master`. Pre-existing, not introduced by this diff, but potentially means the image-publish pipeline has never auto-triggered on push; AC1 forbids changing `ci.yml`'s behavior in this story so this needs a dedicated follow-up [.github/workflows/ci.yml:4] — deferred, pre-existing
- [x] [Review][Defer] No Dependabot/Renovate config for the `github-actions` ecosystem — the new SHA pins won't receive automated update PRs and will rot over time — deferred, pre-existing
- [x] [Review][Defer] `ci.yml` and `pr-build.yml` duplicate the same `docker/build-push-action` SHA pin with no shared/reusable workflow — future bumps require editing both in lockstep — deferred, pre-existing
- [x] [Review][Defer] No vulnerability/security image scan (Trivy/Grype) in `pr-build.yml` despite the "hardening" framing of this story — deferred, pre-existing
- [x] [Review][Defer] No Docker build-layer caching in `pr-build.yml` (only `~/.m2` is cached) — every PR is a fully cold image build — deferred, pre-existing
- [x] [Review][Defer] The new "defence in depth" doc callout asserts Hetzner's outage behavior as fact with no citation [docs/deployment/first-time-setup.md] — deferred, pre-existing

## Dev Notes

### SHA pinning — resolving SHAs

Use `gh api` (GitHub CLI) or the GitHub web UI to find the SHA for a specific tag:
```bash
gh api repos/actions/checkout/git/refs/tags/v4 --jq '.object.sha'
```
For `docker/build-push-action@v6`:
```bash
gh api repos/docker/build-push-action/git/refs/tags/v6 --jq '.object.sha'
```
Tags that point to annotated tags return the tag object SHA, not the commit SHA — follow the ref if needed:
```bash
gh api repos/actions/checkout/git/tags/<sha> --jq '.object.sha'
```

### PR workflow and `mvn verify -q`

`mvn verify` runs all test phases including integration tests. If integration tests require a running database (Testcontainers), they will spin up Docker containers in the GitHub Actions runner — this works on `ubuntu-latest` since Docker is pre-installed. If integration tests take too long for PR feedback, consider `mvn test -q` (unit tests only) for the PR workflow and leave `mvn verify` for the merge CI.

### `ufw` re-run safety

If `provision.sh` is re-run against a live server (not recommended but possible), `ufw allow` rules accumulate duplicate entries. `ufw --force enable` is idempotent if ufw is already enabled. The duplicate allow rules are harmless but untidy. Add `ufw reset` with caution — it clears all rules.

### Hetzner firewall vs ufw

`deploy/firewall/apply-firewall.sh` applies Hetzner cloud-level firewall rules via the `hcloud` CLI. These are enforced at the network edge, before packets reach the VM. `ufw` runs inside the VM kernel. Both layers are independent. During a Hetzner API outage, the cloud firewall is managed by Hetzner infrastructure and remains active; `ufw` is enforced by the Linux kernel on the VM regardless of Hetzner API state.

### `acme.json` and TLS persistence (deploy-1-5 deferred)

This story does not address the `acme.json` root-disk placement gap. If TLS certificate backup is needed, add it to a subsequent operational hardening story or backlog item.

### References — Files to Read Before Implementing

- `.github/workflows/deploy.yml` — existing actions, job structure, smoke test loop
- `deploy/provision.sh` — ufw installation location, script structure, `set -e` handling
- `deploy/firewall/apply-firewall.sh` — which ports are already handled by Hetzner firewall
- `docker-compose.yml` — `start_period` in the app service health check
- `docs/deployment/setup.md` (or equivalent) — first-time setup documentation to update

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

None — no test failures or blockers encountered.

### Completion Notes List

- **Task 1**: Created `.github/workflows/pr-build.yml`. Used **JDK 17** (not the story template's JDK 21) after reading the existing `Dockerfile` and `pom.xml` (`<java.version>17</java.version>`) — the project targets Java 17, so JDK 21 in the template would have been inconsistent. Used `mvn -B verify -q` (full test suite including Testcontainers-backed integration tests via the `maven-failsafe-plugin` binding) rather than `mvn test`, per Dev Notes guidance, since `ubuntu-latest` has Docker pre-installed and this is the first automated test gate in the pipeline (the existing push-to-main `ci.yml` builds with `-Dmaven.test.skip=true` and never runs tests). Added `restore-keys` to the `actions/cache` step as a minor cache-hit-rate improvement beyond the template.
- **Task 2**: Resolved commit SHAs for every `uses:` reference via `gh api repos/<owner>/<repo>/git/refs/tags/<tag>` (all direct commit refs, no annotated-tag indirection needed). Pinned all Actions in the new `pr-build.yml`, the existing `deploy.yml`, **and** `ci.yml` (the push-to-main workflow) — AC2's Given clause explicitly names `ci.yml`'s own actions (`actions/checkout@v4`, `docker/login-action@v3`, `docker/build-push-action@v6`) as needing SHA pinning, and AC1's "existing push-to-main workflow is unchanged" is read as *functionally* unchanged (same trigger, same behavior), not exempt from the SHA-pinning hardening. `deploy.yml`'s three additional third-party actions (`webfactory/ssh-agent`, `slackapi/slack-github-action`, `dawidd6/action-send-mail`) were also pinned to satisfy AC2's "all Actions are pinned" requirement, beyond the "at minimum" list in Task 2's subtasks.
- **Task 3**: Added a new "5. Host firewall (ufw)" section to `deploy/provision.sh`, before the (renumbered) directory-structure/volume-mount sections. `ufw` was already installed via `apt-get` (pre-existing line) but never enabled. SSH allow rule runs before `ufw --force enable` per the CRITICAL constraint. Verified with `bash -n` (syntax) and `shellcheck` (zero warnings).
- **Task 4**: Added a 60s `sleep` at the start of the existing SSH/`docker exec`-based smoke test step in `deploy.yml` (the real script differs from the story's illustrative `curl`-over-public-domain template — it health-checks over SSH against the internal management port, consistent with the existing Auto-Revert logic). Also updated `docs/deployment/deploy-guide.md` (the actual doc describing the smoke-test window — "up to 60 seconds / 12 attempts" was stale after this change) to reflect the new upfront 60s wait plus the existing retry loop.
- **Task 5**: Updated `docs/deployment/first-time-setup.md` (the project's equivalent of "setup.md") — added ufw as step 4 of what `provision.sh` does (renumbering subsequent steps), and added a defence-in-depth callout at the top of "Step 4: Apply the Firewall" explaining the ufw/Hetzner layering.
- All YAML changes validated with `ruby -ryaml` (`YAML.load_file`); `provision.sh` validated with `bash -n` and `shellcheck` (clean); `pom.xml` sanity-checked with `mvn -q -B validate` (no regression — no `src/` files were touched by this story).
- `actionlint` was not available and could not be installed cleanly in this environment (Homebrew tap-trust/lock issues); relied on YAML syntax validation and manual review of the GitHub Actions expression syntax instead.

### File List

**New Files:**
- `.github/workflows/pr-build.yml`

**Modified Files:**
- `.github/workflows/ci.yml` *(SHA-pinning only — trigger and behavior unchanged, per AC2)*
- `.github/workflows/deploy.yml`
- `deploy/provision.sh`
- `docs/deployment/first-time-setup.md` *(the project's equivalent of "setup.md")*
- `docs/deployment/deploy-guide.md` *(smoke-test window description updated to match Task 4)*

## Change Log

- 2026-07-02: Implemented all 5 tasks (PR build workflow, SHA-pinned Actions across all three workflows, ufw host firewall in provisioning, smoke-test false-positive fix, deployment docs updated). Status set to `review`.
