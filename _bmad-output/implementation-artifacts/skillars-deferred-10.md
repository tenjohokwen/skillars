# Story Deferred-10: CI/CD & Deployment Hardening

Status: backlog

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

- [ ] **Task 1 — Add PR build workflow** (AC: 1)
  - [ ] Create `.github/workflows/pr-build.yml`:
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
  - [ ] Do NOT add `docker/login-action` to this workflow — no push means no registry auth needed
  - [ ] Replace `<SHA>` placeholders with real commit SHAs (see Task 2)
  - [ ] Read the existing `deploy.yml` to confirm the JDK version and Maven command used — be consistent

- [ ] **Task 2 — Pin all GitHub Actions to commit SHAs** (AC: 2)
  - [ ] Find all Action references in `.github/workflows/`:
    `grep -r "uses:" .github/workflows/ | grep -v "#"` — list every `uses: owner/action@version` line
  - [ ] For each Action, resolve the current SHA of the pinned tag:
    ```bash
    # Example for actions/checkout@v4
    gh api repos/actions/checkout/git/refs/tags/v4 --jq '.object.sha'
    # Or via git:
    git ls-remote https://github.com/actions/checkout.git refs/tags/v4
    ```
  - [ ] Replace each floating tag with the SHA:
    ```yaml
    # BEFORE:
    - uses: actions/checkout@v4
    # AFTER:
    - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683  # v4.2.2
    ```
  - [ ] Add a comment with the human-readable tag after the SHA so maintainers know what version is pinned
  - [ ] Actions to pin (at minimum):
    - `actions/checkout`
    - `actions/setup-java`
    - `actions/cache`
    - `docker/login-action`
    - `docker/build-push-action`
    - `docker/setup-buildx-action` (if used)
  - [ ] Apply SHA pinning to BOTH the existing `deploy.yml` AND the new `pr-build.yml`

- [ ] **Task 3 — Enable `ufw` in `provision.sh`** (AC: 3)
  - [ ] Read `deploy/provision.sh` — find the `ufw install` section
  - [ ] Add after the `apply-firewall.sh` call (or at the end of the firewall section):
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
  - [ ] **CRITICAL**: `ufw allow 22/tcp` MUST run before `ufw --force enable` — enabling ufw with default deny without first allowing SSH will terminate the active provisioning SSH session
  - [ ] If the Hetzner firewall (from `apply-firewall.sh`) already allows port 22, the ufw SSH rule is belt-and-suspenders — add it anyway for defence-in-depth
  - [ ] If `provision.sh` has `set -euo pipefail`, test that the ufw commands succeed on a fresh Ubuntu instance; `ufw` may not be installed by default — confirm `apt-get install -y ufw` is already in the script or add it

- [ ] **Task 4 — Fix smoke test false positives in `deploy.yml`** (AC: 4)
  - [ ] Read `.github/workflows/deploy.yml` — find the smoke test loop (described in rollback documentation as 12 retries, 10-second interval)
  - [ ] Add a 60-second initial delay before the first retry to allow for JVM startup:
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
  - [ ] The `start_period: 60s` in `docker-compose.yml` means Docker itself does not count failed health checks during the first 60 seconds, but the GitHub Actions smoke test starts immediately — this is the mismatch that causes false auto-reverts on slow JVM startup
  - [ ] If the initial 60s sleep is already present in `deploy.yml`, skip this task

- [ ] **Task 5 — Document ufw in deployment docs** (AC: 3)
  - [ ] Update `docs/deployment/setup.md` (or the equivalent first-time setup doc) to note that `provision.sh` now enables `ufw` and document which ports are open
  - [ ] Add a note about the ufw/Hetzner firewall layering: "The Hetzner cloud firewall is the primary network perimeter; ufw provides host-level defence-in-depth on the VM itself"

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

### Debug Log References

### Completion Notes List

### File List

**New Files:**
- `.github/workflows/pr-build.yml`

**Modified Files:**
- `.github/workflows/deploy.yml`
- `deploy/provision.sh`
- `docs/deployment/setup.md` *(or equivalent first-time setup doc)*
