# Story Deploy-2.2: Manual Production Deploy Workflow with Smoke Test & Auto-Revert

Status: done

## Story

As a developer who has validated a release on the UAT server,
I want to trigger a production deploy via the GitHub Actions UI by selecting the target image tag — and have the pipeline automatically run a Smoke Test, alert me on the result, and revert to the previous image if the test fails,
so that I can ship confidently knowing production is always left in a known-good state.

## Acceptance Criteria

**AC-1: Workflow is triggerable via GitHub Actions UI with image tag input**
- Given the production deploy workflow exists
- When a developer navigates to GitHub Actions
- Then the workflow is triggerable via `workflow_dispatch` with the target GHCR image tag as an explicit input — no CLI access or direct SSH to the Node is required to initiate the deploy

**AC-2: SSH deploy uses the exact prescribed command and records pre-deploy image**
- Given a deploy is triggered with a valid image tag
- When the workflow runs
- Then it connects to the Node via SSH and executes `docker compose pull app && docker compose up -d --no-deps app` with the specified image
- And the workflow records the current running image reference from `docker inspect` before pulling the new image

**AC-3: Smoke Test runs against `/actuator/health` within 60 seconds**
- Given the new image has been started
- When the Smoke Test runs
- Then it checks the application health endpoint within 60 seconds of the service restart
- And a healthy response marks the deploy as successful and sends an alert to the deployer via email and Slack

**AC-4: Failed Smoke Test triggers Auto-Revert, alerts, and marks workflow failed**
- Given the Smoke Test returns an unhealthy response or times out
- When Auto-Revert is triggered
- Then the workflow re-pins the previously recorded image reference and restarts the service
- And the deployer receives an alert via email and Slack identifying the deploy as failed and reverted
- And the workflow run is marked as failed in GitHub Actions

---

## Tasks / Subtasks

- [x] Task 1: Create `.github/workflows/deploy.yml` — the `workflow_dispatch`-triggered deploy workflow (AC-1 through AC-4)
  - [x] Add `workflow_dispatch` trigger with `image_tag` (required string) input
  - [x] Add `permissions: contents: read` at job level (consistent with `ci.yml` pattern)
  - [x] Step: Load SSH key via `webfactory/ssh-agent@v0.9.0`
  - [x] Step: Add Node to known hosts via `ssh-keyscan`
  - [x] Step: Capture pre-deploy image via `docker inspect` before any changes (AC-2)
  - [x] Step: Update `.env` `APP_IMAGE` line on the Node via `sed` and run `docker compose pull app && docker compose up -d --no-deps app` (AC-2)
  - [x] Step: Smoke test loop — poll `docker exec` wget for up to 60 s; output `result=pass` or `result=fail` (AC-3, AC-4)
  - [x] Step: Auto-Revert (runs only on `result=fail`) — restore old `APP_IMAGE` in `.env` and restart (AC-4)
  - [x] Step: Slack success notification via `slackapi/slack-github-action@v2.0.0` (AC-3)
  - [x] Step: Slack failure notification via `slackapi/slack-github-action@v2.0.0` (AC-4)
  - [x] Step: Email success notification via `dawidd6/action-send-mail@v3` (AC-3)
  - [x] Step: Email failure notification via `dawidd6/action-send-mail@v3` (AC-4)
  - [x] Step: Final `exit 1` on smoke test failure to mark workflow as failed (AC-4)

- [x] Task 2: Update `docs/deployment/secrets-reference.md` — add new GitHub Actions secrets required by this workflow
  - [x] Add `SLACK_WEBHOOK_URL` entry under GitHub Actions Secrets section
  - [x] Add `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` entries
  - [x] Add `NOTIFY_EMAIL` entry

### Review Findings

- [x] [Review][Patch] Add `docker login ghcr.io` step before deploy — SSH into server and authenticate using `GHCR_PAT` so `docker compose pull` works reliably without depending on pre-cached credentials [`.github/workflows/deploy.yml`]
- [x] [Review][Patch] `.env` corrupted when `docker compose pull` fails mid-deploy — `sed -i` writes new `APP_IMAGE` before pull; if pull fails, `.env` has new image but container never started [`.github/workflows/deploy.yml`:53-56]
- [x] [Review][Patch] Auto-Revert unverified — SSH revert command has no error capture; notifications still say "Auto-Revert initiated" even if revert itself fails, leaving production down with operators misled [`.github/workflows/deploy.yml`:78-86]
- [x] [Review][Patch] `ssh-keyscan` TOFU — blindly trusts returned host key; on-path attacker can substitute their own key and intercept all SSH deploy/revert commands [`.github/workflows/deploy.yml`:27]
- [x] [Review][Patch] `PREV` image unvalidated before `sed` in auto-revert — `PREV` comes from `docker inspect` on remote host; an image name containing `|` breaks the sed expression [`.github/workflows/deploy.yml`:83]
- [x] [Review][Patch] Smoke test `== "1"` strict equality — if health response is multi-line and `"status":"UP"` appears more than once, `grep -c` returns 2+, check never passes, spurious revert fires [`.github/workflows/deploy.yml`:68]
- [x] [Review][Patch] `docker compose ps -q` without `--status running` — returns stopped/exited containers; `docker exec` on a stopped container silently fails, wasting all 12 poll slots [`.github/workflows/deploy.yml`:64]
- [x] [Review][Patch] Failure notification says "Auto-Revert initiated" when PREV=none — when no prior container existed, revert is skipped but both Slack and email messages still claim revert was triggered [`.github/workflows/deploy.yml`:97-109]
- [x] [Review][Patch] `PREV` could be empty string if SSH to capture step fails — empty `PREV` passes the `[ -n "$PREV" ]` check and triggers revert with a blank image reference [`.github/workflows/deploy.yml`:40-45]
- [x] [Review][Patch] `SMTP_PASSWORD`/`SMTP_USERNAME` row order — already correct in file; finding was based on a transposed diff; no change needed [`docs/deployment/secrets-reference.md`]
- [x] [Review][Defer] `Fail workflow` step unreachable if a notification step throws — job still fails, just attributed to the notification step; low severity diagnostic issue [`.github/workflows/deploy.yml`:139-143] — deferred, low severity; same end outcome (job fails), triage only

---

## Dev Notes

### This story is infrastructure-only — no Java source code changes

**Files to CREATE:**
- `.github/workflows/deploy.yml`

**Files to UPDATE:**
- `docs/deployment/secrets-reference.md` — add five new GitHub Actions secrets

**Do NOT touch:**
- `.github/workflows/ci.yml` — CI build pipeline; separate concern
- `docker-compose.yml` — no changes required
- `.env.example` — no changes required
- Any Java source file

---

### Task 1 Detail: deploy.yml

#### Trigger and Permissions

```yaml
name: Deploy to Production

on:
  workflow_dispatch:
    inputs:
      image_tag:
        description: 'GHCR image tag to deploy (e.g. sha-abc1234)'
        required: true
        type: string

jobs:
  deploy:
    runs-on: ubuntu-latest
    permissions:
      contents: read
```

No other permissions needed — this workflow does not push to GHCR or write to the repository.

#### SSH Setup

Use `webfactory/ssh-agent@v0.9.0` to load the private key, then add the Node to known hosts before any SSH command. Without the `ssh-keyscan` step, the first SSH command will fail interactively on a host-key prompt.

```yaml
- name: Load SSH key
  uses: webfactory/ssh-agent@v0.9.0
  with:
    ssh-private-key: ${{ secrets.SSH_DEPLOY_KEY }}

- name: Add Node to known hosts
  run: ssh-keyscan -H "${{ secrets.SSH_HOST }}" >> ~/.ssh/known_hosts
```

#### Pre-Deploy Image Capture (AC-2)

Capture the currently running `app` container's image reference before deploying. This is used exclusively for Auto-Revert if the smoke test fails. Use `docker inspect --format='{{.Config.Image}}'` which returns the full image reference (e.g. `ghcr.io/org/javatemplate:sha-abc1234`) that was used to start the container.

The `|| echo 'none'` guard handles the edge case where no `app` container is running (first-ever deploy).

```yaml
- name: Capture pre-deploy image
  id: pre_deploy
  run: |
    PREV=$(ssh "${{ secrets.SSH_USER }}@${{ secrets.SSH_HOST }}" \
      "cd /opt/skillars && \
       CID=\$(docker compose ps -q app 2>/dev/null) && \
       [ -n \"\$CID\" ] && docker inspect --format='{{.Config.Image}}' \"\$CID\" || echo 'none'")
    echo "image=$PREV" >> $GITHUB_OUTPUT
```

#### Deploy Step (AC-2)

The exact deploy command required by the PRD is `docker compose pull <service> && docker compose up -d --no-deps <service>`. The service name in `docker-compose.yml` is `app`.

Update the `.env` `APP_IMAGE` line on the Node **before** running compose, so that if the container restarts later (e.g. after a Node reboot), it uses the correct image. Then run the prescribed commands:

```yaml
- name: Deploy
  run: |
    IMAGE="ghcr.io/${{ github.repository }}:${{ inputs.image_tag }}"
    ssh "${{ secrets.SSH_USER }}@${{ secrets.SSH_HOST }}" "
      cd /opt/skillars
      sed -i \"s|^APP_IMAGE=.*|APP_IMAGE=${IMAGE}|\" .env
      docker compose pull app
      docker compose up -d --no-deps app
    "
```

**Note:** `APP_IMAGE` is on its own line in `.env` as confirmed by `.env.example` (`APP_IMAGE=ghcr.io/...`). The `sed` pattern `^APP_IMAGE=.*` matches exactly one line.

**CRITICAL:** Do NOT use `docker compose up -d` (full stack restart) — only `--no-deps app` to avoid touching other services.

#### Smoke Test (AC-3, AC-4)

The app container does not expose its management port (8367) to the host network — no `ports:` in `docker-compose.yml` for the `app` service. Therefore the smoke test must run from **inside** the container using `docker exec`.

The management endpoint is `/manage/health` (not `/actuator/health` — Traefik rewrites the public path). The existing healthcheck in `docker-compose.yml` confirms: `wget -qO- http://localhost:8367/manage/health`. `wget` is available because `eclipse-temurin:17-jre-alpine` is Alpine-based and includes busybox.

Poll every 5 seconds for a maximum of 60 seconds (12 attempts):

```yaml
- name: Smoke test
  id: smoke
  run: |
    RESULT="fail"
    for i in $(seq 1 12); do
      STATUS=$(ssh "${{ secrets.SSH_USER }}@${{ secrets.SSH_HOST }}" "
        CID=\$(cd /opt/skillars && docker compose ps -q app 2>/dev/null)
        [ -n \"\$CID\" ] && docker exec \"\$CID\" wget -qO- http://localhost:8367/manage/health 2>/dev/null \
          | grep -c '\"status\":\"UP\"' || echo 0
      " 2>/dev/null || echo 0)
      if [ "$STATUS" = "1" ]; then
        RESULT="pass"
        break
      fi
      sleep 5
    done
    echo "result=$RESULT" >> $GITHUB_OUTPUT
```

#### Auto-Revert (AC-4)

Runs only when smoke test fails. Reverts `.env` to the previously captured image and restarts only the `app` service. If the pre-deploy capture returned `none` (first deploy, no previous image), skip the revert — the container will simply be left in its failed state.

```yaml
- name: Auto-Revert on smoke test failure
  if: steps.smoke.outputs.result == 'fail'
  run: |
    PREV="${{ steps.pre_deploy.outputs.image }}"
    if [ "$PREV" != "none" ] && [ -n "$PREV" ]; then
      ssh "${{ secrets.SSH_USER }}@${{ secrets.SSH_HOST }}" "
        cd /opt/skillars
        sed -i \"s|^APP_IMAGE=.*|APP_IMAGE=${PREV}|\" .env
        docker compose up -d --no-deps app
      "
    fi
```

#### Notifications (AC-3, AC-4)

**Slack** — use `slackapi/slack-github-action@v2.0.0` with `incoming-webhook` type. Run success notification only if smoke result is `pass`; failure notification only if `fail`. Both notifications must run even if prior steps failed, so use `if:` conditions rather than relying on step status.

```yaml
- name: Notify Slack — success
  if: steps.smoke.outputs.result == 'pass'
  uses: slackapi/slack-github-action@v2.0.0
  with:
    webhook: ${{ secrets.SLACK_WEBHOOK_URL }}
    webhook-type: incoming-webhook
    payload: |
      {"text": "✅ *Production deploy succeeded*\nImage: `ghcr.io/${{ github.repository }}:${{ inputs.image_tag }}`\nTriggered by: ${{ github.actor }}"}

- name: Notify Slack — failure & revert
  if: steps.smoke.outputs.result == 'fail'
  uses: slackapi/slack-github-action@v2.0.0
  with:
    webhook: ${{ secrets.SLACK_WEBHOOK_URL }}
    webhook-type: incoming-webhook
    payload: |
      {"text": "❌ *Production deploy FAILED — Auto-Revert initiated*\nFailed image: `ghcr.io/${{ github.repository }}:${{ inputs.image_tag }}`\nReverted to: `${{ steps.pre_deploy.outputs.image }}`\nTriggered by: ${{ github.actor }}"}
```

**Email** — use `dawidd6/action-send-mail@v3`. Same success/fail branching as Slack. Use `secure_connection: starttls` (SMTP port 587) or `secure_connection: ssl` (port 465) depending on provider. Since SMTP_PORT is injected as a secret, default to `starttls` and let the port drive protocol selection.

```yaml
- name: Email — success
  if: steps.smoke.outputs.result == 'pass'
  uses: dawidd6/action-send-mail@v3
  with:
    server_address: ${{ secrets.SMTP_HOST }}
    server_port: ${{ secrets.SMTP_PORT }}
    username: ${{ secrets.SMTP_USERNAME }}
    password: ${{ secrets.SMTP_PASSWORD }}
    to: ${{ secrets.NOTIFY_EMAIL }}
    from: ${{ secrets.SMTP_USERNAME }}
    subject: "✅ Deploy succeeded: ${{ inputs.image_tag }}"
    body: |
      Production deploy of ghcr.io/${{ github.repository }}:${{ inputs.image_tag }} succeeded.
      Triggered by: ${{ github.actor }}
      Workflow run: ${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}

- name: Email — failure & revert
  if: steps.smoke.outputs.result == 'fail'
  uses: dawidd6/action-send-mail@v3
  with:
    server_address: ${{ secrets.SMTP_HOST }}
    server_port: ${{ secrets.SMTP_PORT }}
    username: ${{ secrets.SMTP_USERNAME }}
    password: ${{ secrets.SMTP_PASSWORD }}
    to: ${{ secrets.NOTIFY_EMAIL }}
    from: ${{ secrets.SMTP_USERNAME }}
    subject: "❌ Deploy FAILED and reverted: ${{ inputs.image_tag }}"
    body: |
      Production deploy of ghcr.io/${{ github.repository }}:${{ inputs.image_tag }} FAILED.
      Auto-Revert to: ${{ steps.pre_deploy.outputs.image }}
      Triggered by: ${{ github.actor }}
      Workflow run: ${{ github.server_url }}/${{ github.repository }}/actions/runs/${{ github.run_id }}
```

#### Final Failure Gate (AC-4)

Must be the very last step. Marks the workflow run as failed in GitHub Actions when smoke test fails.

```yaml
- name: Fail workflow on smoke test failure
  if: steps.smoke.outputs.result == 'fail'
  run: |
    echo "Smoke test failed — deploy reverted. Marking workflow as failed."
    exit 1
```

---

### Task 2 Detail: Updating secrets-reference.md

Add the following five entries to the **GitHub Actions Secrets** section table:

| Secret name | Format | How to obtain or generate |
|---|---|---|
| `SLACK_WEBHOOK_URL` | HTTPS URL | Slack → Your workspace → Apps → Incoming Webhooks → Add to Slack → select channel → copy Webhook URL |
| `SMTP_HOST` | Hostname | Your SMTP provider (e.g. `smtp.gmail.com`, `smtp.sendgrid.net`) |
| `SMTP_PORT` | Integer | From your SMTP provider — `587` for STARTTLS, `465` for SSL/TLS |
| `SMTP_USERNAME` | Email address | Your SMTP username or sending address |
| `SMTP_PASSWORD` | String | App password or SMTP credential from your email provider |
| `NOTIFY_EMAIL` | Email address | Address to receive deploy and revert notifications |

---

### GitHub Actions Action Versions

Pinned major versions (consistent with the pattern in `ci.yml`):
- `webfactory/ssh-agent@v0.9.0`
- `slackapi/slack-github-action@v2.0.0`
- `dawidd6/action-send-mail@v3`

Do NOT use `@latest` or unpinned tags.

---

### Previous Story Intelligence (deploy-2-1 learnings)

**Patterns established in `ci.yml` to follow consistently:**
- `permissions: contents: read` at job level — already enforced in CI; do the same in deploy workflow
- Quote `$GITHUB_SHA` and shell variables: use `"$VAR"` not `$VAR` in `run:` steps
- `actions/checkout@v4` is in scope for CI but NOT needed for deploy workflow (no source code access required)
- OCI labels were added to CI build; deploy workflow does not interact with image labels

**Review findings from deploy-2-1 that apply here:**
- **No `continue-on-error: true` anywhere** — failure of any step must propagate (same principle as CI)
- **No credential values in logs** — `SLACK_WEBHOOK_URL`, `SMTP_PASSWORD`, etc. are passed via `secrets.*` only; never echo them
- **`permissions:` block is mandatory** — the review found CI was missing it; add it from the start here

---

### Architecture & Deployment Context

**Server path:** All docker compose operations on the Node run from `/opt/skillars` where `docker-compose.yml` and `.env` live. All SSH commands must `cd /opt/skillars` first.

**`.env` `APP_IMAGE` line format** (from `.env.example`):
```
APP_IMAGE=ghcr.io/your-org/javatemplate:sha-abc1234
```
The `sed` command must match `^APP_IMAGE=` to avoid partial-line matches.

**Application ports (from project-context.md and docker-compose.yml):**
- Port `9990` — main application (not exposed to host; routes through Traefik)
- Port `8367` — management/actuator (`management.server.port`) — not exposed to host
- Management path is `/manage/health` on port 8367 (confirmed by docker-compose.yml healthcheck)
- Public path `/actuator/health` is a Traefik middleware rewrite → not directly accessible on 8367

**Service name in compose:** `app` (used in all compose commands: `docker compose pull app`, `docker compose up -d --no-deps app`, `docker compose ps -q app`)

**Compose project name:** Not explicitly set → defaults to directory name `skillars` (from `/opt/skillars`). This affects container names but not service names in compose commands.

---

### Project Structure Notes

- `.github/workflows/` already exists (contains `ci.yml`) — no `mkdir` needed
- `docs/deployment/secrets-reference.md` already exists and documents the GHCR_PAT, SSH_DEPLOY_KEY, SSH_HOST, SSH_USER secrets that this workflow depends on
- No new directories or Java modules required

---

### References

- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Story 2.2 — Acceptance Criteria]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-5 — Manual production deploy trigger via GitHub Actions]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-6 — Smoke Test + Auto-Revert]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Additional Requirements — "Deploy command pattern MUST use docker compose pull <service> && docker compose up -d --no-deps <service>"]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Additional Requirements — "Health endpoint path: Smoke Test MUST target /actuator/health specifically"]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#NFR-6 — Secrets hygiene: no secret in committed file or CI output]
- [Source: docker-compose.yml#Lines 19-23 — app healthcheck: wget -qO- http://localhost:8367/manage/health]
- [Source: docker-compose.yml#Lines 39-47 — Traefik middleware: /actuator/(.*) → /manage/$$1 on port 8367]
- [Source: docker-compose.yml#Line 9 — app service: image: ${APP_IMAGE}]
- [Source: .env.example#Line 15 — APP_IMAGE=ghcr.io/your-org/javatemplate:sha-abc1234]
- [Source: docs/deployment/secrets-reference.md#GitHub Actions Secrets — SSH_DEPLOY_KEY, SSH_HOST, SSH_USER already documented]
- [Source: .github/workflows/ci.yml — permissions: contents: read pattern to follow]
- [Source: _bmad-output/implementation-artifacts/deploy-2-1-automated-ci-build-pipeline.md#Review Findings — permissions block required, no continue-on-error]

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

_No debug issues encountered._

### Completion Notes List

- Created `.github/workflows/deploy.yml` — full `workflow_dispatch` deploy pipeline with SSH deploy, pre-deploy image capture, smoke test (12×5s poll via `docker exec wget` against `localhost:8367/manage/health`), auto-revert on failure, Slack+email notifications for both success and failure, and terminal `exit 1` to mark the workflow failed when smoke test fails.
- Updated `docs/deployment/secrets-reference.md` — added 6 new entries to the GitHub Actions Secrets table: `SLACK_WEBHOOK_URL`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `NOTIFY_EMAIL`.
- All 4 ACs satisfied: AC-1 (workflow_dispatch with image_tag input), AC-2 (exact deploy commands + pre-deploy capture), AC-3 (60s smoke test + success alerts), AC-4 (auto-revert + failure alerts + workflow marked failed).
- YAML validated with Ruby yaml parser — no syntax errors.
- No Java source files were touched; infrastructure-only story.

### File List

- `.github/workflows/deploy.yml` (created)
- `docs/deployment/secrets-reference.md` (modified)

## Change Log

- 2026-06-04: Story created — deploy-2-2-manual-production-deploy-workflow-with-smoke-test-auto-revert
- 2026-06-04: Implementation complete — created deploy.yml workflow and updated secrets-reference.md with 6 new secrets
