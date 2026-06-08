# Story Deploy-2.3: Deployment & Rollback Documentation

Status: done

## Story

As a developer who needs to ship a release or recover from a failed deploy,
I want an ongoing deployment guide and a manual rollback procedure in `docs/deployment`,
so that I can complete the full deploy cycle in ≤ 30 minutes and manually revert production to any previous image without improvising steps.

## Acceptance Criteria

**AC-1: `docs/deployment/deploy-guide.md` covers the full deploy cycle**
- Given `docs/deployment/deploy-guide.md` exists
- When a developer follows it for a regular deploy (CI trigger → UAT validation → production deploy trigger → result interpretation)
- Then the full cycle completes within the ≤ 30-minute target without consulting any source outside the repository
- And the guide covers: how to identify the correct GHCR image tag, how to trigger the production deploy workflow via GitHub Actions UI, and how to interpret a successful deploy vs. an Auto-Revert result

**AC-2: `docs/deployment/rollback.md` covers manual rollback beyond Auto-Revert**
- Given `docs/deployment/rollback.md` exists
- When a developer needs to manually revert production beyond what Auto-Revert covers
- Then the procedure covers: locating the previous image digest in GHCR, re-pinning it in the deployment configuration, and executing the SSH deploy command — without server-side improvisation
- And a developer with no prior context on the current incident can follow the procedure to restore production to the previous image

---

## Tasks / Subtasks

- [x] Task 1: Create `docs/deployment/deploy-guide.md` (AC-1)
  - [x] Section: Prerequisites (secrets already in place, GHCR access)
  - [x] Section: Step 1 — Find the image tag from a CI run (how to locate `sha-<commit>` in GitHub Actions)
  - [x] Section: Step 2 — Trigger the production deploy via GitHub Actions UI (`workflow_dispatch`)
  - [x] Section: Step 3 — Monitor the deploy and interpret the result (success vs. Auto-Revert)
  - [x] Section: Expected timeline (≤ 30 minutes)
  - [x] Section: What to do if Auto-Revert fires (cross-link to `rollback.md`)

- [x] Task 2: Create `docs/deployment/rollback.md` (AC-2)
  - [x] Section: When to use this guide (Auto-Revert is one level only; this covers deeper rollbacks)
  - [x] Section: Step 1 — Identify the target image tag to roll back to (from GHCR, GitHub Actions CI history, or `git log`)
  - [x] Section: Step 2 — SSH into the Node
  - [x] Section: Step 3 — Update `APP_IMAGE` in `.env`
  - [x] Section: Step 4 — Pull and restart the `app` service
  - [x] Section: Step 5 — Verify the rollback succeeded (health check)

---

## Dev Notes

### This story is documentation-only — no code changes

**Files to CREATE:**
- `docs/deployment/deploy-guide.md`
- `docs/deployment/rollback.md`

**Files to UPDATE:**
- None required. Optionally cross-link from `docs/deployment/first-time-setup.md` if a natural reference point exists, but do NOT modify it unless it directly improves clarity.

**Do NOT touch:**
- `.github/workflows/deploy.yml` — already complete from deploy-2-2; do NOT modify
- `.github/workflows/ci.yml` — already complete from deploy-2-1; do NOT modify
- `docker-compose.yml` — no changes required
- `.env.example` — no changes required
- Any Java source file
- `docs/deployment/secrets-reference.md` — already updated in deploy-2-2; do NOT re-add existing entries

---

### Task 1 Detail: deploy-guide.md

The deploy guide covers the regular release cycle: from a merged PR to a live production deploy. The target is that a developer unfamiliar with the last deploy can complete the full cycle in ≤ 30 minutes using only this document.

#### Structure

```
# Deployment Guide

## Prerequisites
## Step 1: Find the Image Tag
## Step 2: Trigger the Production Deploy
## Step 3: Monitor the Deploy
## What to Do if Auto-Revert Fires
```

#### Section: Prerequisites

The reader should have:
- Access to the GitHub repository (to see Actions runs and trigger `workflow_dispatch`)
- The GitHub Actions secrets already configured (SSH_DEPLOY_KEY, SSH_HOST, SSH_USER, SLACK_WEBHOOK_URL, SMTP_*, NOTIFY_EMAIL, SSH_KNOWN_HOST) — refer reader to `secrets-reference.md` if not set up

No SSH access to the Node is needed to trigger a deploy.

#### Section: Step 1 — Find the Image Tag

The CI pipeline (`.github/workflows/ci.yml`) runs automatically on every merge to `main`. It builds and pushes a Docker image to GHCR tagged with the commit SHA in the format `sha-<short-sha>` (e.g., `sha-abc1234`).

To find the tag for a specific commit:

1. Go to the repository → **Actions** tab → select the **CI Build** workflow
2. Find the run corresponding to the merge commit you want to deploy
3. The image tag is `sha-<commit SHA>` — either read it from the CI run logs (the `Build and push Docker image` step output) or construct it from the commit SHA: go to **Commits** history, copy the first 7 characters of the SHA, and prepend `sha-`

Alternatively, from the command line:
```bash
git log --oneline -5
# Find the commit SHA, then construct: sha-<first-7-chars>
```

The full image reference is: `ghcr.io/<org>/javatemplate:sha-<commit>`

#### Section: Step 2 — Trigger the Production Deploy

The production deploy workflow is triggered manually via GitHub Actions UI — no CLI or server access required.

1. Go to the repository → **Actions** tab → select **Deploy to Production**
2. Click **Run workflow** (top-right of the workflow runs list)
3. In the `image_tag` field, enter the tag identified in Step 1 (e.g., `sha-abc1234`)
4. Click **Run workflow**

The workflow will:
- SSH into the Node
- Capture the currently running image (used for Auto-Revert if needed)
- Authenticate to GHCR on the Node
- Pull the new image and restart the `app` service using: `docker compose pull app && docker compose up -d --no-deps app`
- Run a Smoke Test against `/actuator/health` within 60 seconds

#### Section: Step 3 — Monitor the Deploy and Interpret the Result

The workflow takes approximately 2–5 minutes. Watch the GitHub Actions run or wait for the notification.

**Successful deploy:**
- GitHub Actions run: green ✅
- Slack: `✅ Production deploy succeeded`
- Email: `✅ Deploy succeeded: sha-<tag>`
- The application is live at the configured domain

**Failed deploy with Auto-Revert:**
- GitHub Actions run: red ❌ (marked failed)
- Slack: `❌ Production deploy FAILED` (includes Auto-Revert outcome and previous image)
- Email: `❌ Deploy FAILED (revert: succeeded/failed/skipped): sha-<tag>`
- Production has been restored to the previous image (if Auto-Revert succeeded)
- See `docs/deployment/rollback.md` if Auto-Revert failed or you need to go further back

#### Section: Expected Timeline

| Activity | Time |
|---|---|
| Identify image tag | ~2 min |
| Trigger workflow via GitHub Actions UI | ~1 min |
| Workflow runs (pull + restart + smoke test) | ~2–5 min |
| Result notification received | ~1 min after pass/fail |
| **Total (happy path)** | **~5–10 min** |

Maximum cycle with UAT validation: ≤ 30 minutes.

#### Section: What to Do if Auto-Revert Fires

If the smoke test fails, Auto-Revert automatically restores the previous image (one level back). Check the workflow run and notification for `Auto-Revert: succeeded`.

If Auto-Revert also failed, or you need to revert to an image older than the last one, follow `docs/deployment/rollback.md` for a manual rollback.

---

### Task 2 Detail: rollback.md

The rollback guide covers manual reversion to any previous image. It is used when:
- Auto-Revert failed (revert outcome was `failed` or `skipped`)
- You need to revert to an image more than one deploy back

**Critical context the developer must understand about Auto-Revert limits:**
- The deploy workflow captures the running image via `docker inspect` immediately before deploying
- Auto-Revert can only restore to that one previously running image
- If that image itself was a broken deploy, or if you need an older version, the manual procedure is required

#### Structure

```
# Manual Rollback Procedure

## When to Use This Guide
## Step 1: Identify the Target Image Tag
## Step 2: SSH Into the Node
## Step 3: Update APP_IMAGE in .env
## Step 4: Pull and Restart the Service
## Step 5: Verify the Rollback
```

#### Section: When to Use This Guide

Use this guide when:
- Auto-Revert outcome was `failed` or `skipped` (shown in the GitHub Actions run log and failure notification)
- You need to revert to an image that is not the immediately previous one
- The application is in a broken state and the automated pipeline cannot fix it

For a standard failed deploy where Auto-Revert succeeded, no manual action is required — production is already restored.

#### Section: Step 1 — Identify the Target Image Tag

The image tag is in the format `sha-<commit-sha>` (e.g., `sha-abc1234`).

**Option A — From GitHub Actions CI history:**
1. Go to repository → **Actions** → **CI Build** workflow
2. Find the run for the commit you want to roll back to
3. Read the image tag from the `Build and push Docker image` step output
4. Full image reference: `ghcr.io/<org>/javatemplate:sha-<commit>`

**Option B — From git log:**
```bash
git log --oneline -10
# Identify the commit to roll back to; use first 7 chars of SHA
# Image tag: sha-<first-7-chars-of-sha>
```

**Option C — From GHCR package page:**
1. Go to `https://github.com/orgs/<org>/packages/container/javatemplate/versions` (or the equivalent packages page for your repository)
2. Find the image version by tag or date
3. Copy the tag (e.g., `sha-abc1234`)

Note the full image reference: `ghcr.io/<org>/javatemplate:<tag>`

#### Section: Step 2 — SSH Into the Node

Connect using the SSH deploy key:
```bash
ssh <SSH_USER>@<SSH_HOST>
```

Where `SSH_USER` and `SSH_HOST` are the values from `docs/deployment/secrets-reference.md` (same secrets used by the GitHub Actions deploy workflow).

All subsequent commands run on the Node.

#### Section: Step 3 — Update APP_IMAGE in .env

The `.env` file lives at `/opt/skillars/.env`. The `APP_IMAGE` line controls which image the `app` service uses. Update it to the target image:

```bash
cd /opt/skillars
sed -i "s|^APP_IMAGE=.*|APP_IMAGE=ghcr.io/<org>/javatemplate:<tag>|" .env
# Verify the change:
grep '^APP_IMAGE=' .env
```

Replace `<org>` and `<tag>` with your actual values.

**Do not edit the file with a text editor unless `sed` fails** — manual editing of `.env` risks accidentally changing other values. The `sed` command matches only the `APP_IMAGE=` line.

#### Section: Step 4 — Pull and Restart the Service

Authenticate to GHCR and run the deploy commands:

```bash
cd /opt/skillars
# Authenticate to GHCR (required to pull from private registry)
echo "<GHCR_PAT>" | docker login ghcr.io -u <github-username> --password-stdin

# Pull the target image and restart only the app service
docker compose pull app && docker compose up -d --no-deps app
```

**CRITICAL:** Use `--no-deps app` — do NOT run `docker compose up -d` (full stack). Only the `app` service should be restarted; PostgreSQL, Redis, Traefik, and the LGTM stack must not be disrupted.

Expected output: Docker pulls the image layers, then `Container skillars-app-1  Started`.

#### Section: Step 5 — Verify the Rollback

Wait ~30 seconds for the application to start, then check health:

```bash
# Get the running app container ID
CID=$(docker compose ps -q --status running app 2>/dev/null | head -1)

# Run health check from inside the container
docker exec "$CID" wget -qO- http://localhost:8367/manage/health
```

Expected response contains: `"status":"UP"`

If the health check returns `"status":"DOWN"` or fails, the container may still be starting. Retry after 10 seconds. If still failing after 60 seconds, check container logs:
```bash
docker compose logs --tail=50 app
```

Once the health check passes, production is restored. No additional steps required — Traefik will automatically route traffic to the healthy container.

---

### Architecture & Deployment Context (for both documents)

**Server directory:** `/opt/skillars` — all compose and `.env` operations run from here

**Service name:** `app` — used in all compose commands

**Deploy command pattern (must be exact):**
```
docker compose pull app && docker compose up -d --no-deps app
```
This is the PRD-prescribed command (from `epics.md` Additional Requirements). Do NOT use variants like `docker compose restart` or `docker compose up -d` (full stack).

**`.env` `APP_IMAGE` line format** (from `.env.example`):
```
APP_IMAGE=ghcr.io/your-org/javatemplate:sha-abc1234
```
The `sed` pattern `^APP_IMAGE=.*` matches exactly this one line — safe to use.

**Health check endpoint:**
- Internal (from inside container): `http://localhost:8367/manage/health`
- Public path: `/actuator/health` — this is rewritten by Traefik middleware → it is NOT accessible directly on port 8367
- Use `docker exec` for health checks from the Node — the management port 8367 is not exposed to the host

**GitHub Actions workflow trigger:**
- Workflow name: "Deploy to Production"
- Trigger: `workflow_dispatch`
- Required input: `image_tag` (e.g., `sha-abc1234`)

**Auto-Revert depth:** one level only — captures image via `docker inspect` immediately before pull; if that image is also bad, use the manual rollback procedure.

**Image tag format:** `sha-<7-char-commit-sha>` — produced by `ci.yml`, consistent and deterministic.

---

### Existing Documents (Do Not Duplicate Content)

- `docs/deployment/first-time-setup.md` — first-time provisioning from zero; deploy guide does NOT duplicate first-time setup steps
- `docs/deployment/secrets-reference.md` — all secrets with format and placement; both new docs can cross-link here but must NOT re-document secrets

---

### Previous Story Intelligence (deploy-2-2 learnings)

**Confirmed correct deploy command:** `docker compose pull app && docker compose up -d --no-deps app` — doc must use this exact form.

**Smoke test behavior:**
- Polls `localhost:8367/manage/health` via `docker exec` (12 attempts × 5 seconds = max 60 seconds)
- Produces `result=pass` or `result=fail`

**Auto-Revert outcomes (from `steps.revert.outputs.outcome`):**
- `succeeded` — revert completed; production restored to previous image
- `failed` — revert SSH command failed; production may be in unknown state → use rollback.md
- `skipped` — no previous image existed (first ever deploy); nothing to revert to → use rollback.md

**Failure notification content:**
- Slack: `❌ Production deploy FAILED` includes `Auto-Revert: <outcome>` and `Previous image: <ref>`
- Email subject: `❌ Deploy FAILED (revert: <outcome>): sha-<tag>`
- Developer can read these to decide whether manual rollback is needed

**SSH known host:** From deploy-2-2 review: uses `SSH_KNOWN_HOST` GitHub secret (not `ssh-keyscan` TOFU) — deploy guide should note this is already set up; the reader does not need to re-run `ssh-keyscan`.

---

### References

- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Story 2.3 — Deployment & Rollback Documentation]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-22 — Ongoing deployment guide]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-23 — Manual rollback procedure]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Additional Requirements — "Deploy command pattern MUST use docker compose pull app && docker compose up -d --no-deps app"]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Additional Requirements — "Auto-Revert depth: one level back only"]
- [Source: .github/workflows/deploy.yml — full workflow: workflow_dispatch, image_tag input, pre-deploy capture, deploy command, smoke test, auto-revert, notifications]
- [Source: .github/workflows/ci.yml — CI builds image tagged sha-<commit> and pushes to GHCR on merge to main]
- [Source: .env.example — APP_IMAGE=ghcr.io/your-org/javatemplate:sha-abc1234]
- [Source: docker-compose.yml — app healthcheck: wget -qO- http://localhost:8367/manage/health]
- [Source: docker-compose.yml — app service name used in all compose commands]
- [Source: docs/deployment/secrets-reference.md — SSH_DEPLOY_KEY, SSH_HOST, SSH_USER, GHCR_PAT, SSH_KNOWN_HOST already documented]
- [Source: _bmad-output/implementation-artifacts/deploy-2-2-*.md#Dev Notes — server path /opt/skillars, exact deploy command, auto-revert outcome values]
- [Source: _bmad-output/implementation-artifacts/deploy-2-2-*.md#Review Findings — ssh-keyscan TOFU fixed: now uses SSH_KNOWN_HOST secret]

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

_No debug issues encountered._

### Completion Notes List

- Created `docs/deployment/deploy-guide.md` covering the full deploy cycle: Prerequisites, Steps 1–3 (find tag, trigger workflow_dispatch, monitor result), Expected Timeline table, and What to Do if Auto-Revert Fires — with cross-link to rollback.md.
- Created `docs/deployment/rollback.md` covering manual rollback beyond Auto-Revert: When to Use, Steps 1–5 (identify tag via 3 options, SSH to node, update APP_IMAGE via sed, pull/restart with --no-deps app, verify via docker exec health check).
- Both documents reflect confirmed technical details from deploy-2-2: exact deploy command (`docker compose pull app && docker compose up -d --no-deps app`), health check via `docker exec` on internal port 8367, Auto-Revert outcomes (succeeded/failed/skipped), server path `/opt/skillars`, and `sha-<7-char-sha>` image tag format.
- No code files were modified — documentation-only story.

### File List

- docs/deployment/deploy-guide.md (created)
- docs/deployment/rollback.md (created)

### Review Findings

- [x] [Review][Decision] GHCR_PAT unrecoverability during outage — resolved: added fresh-PAT generation instruction to rollback.md Step 4
- [x] [Review][Decision] UAT validation guidance — resolved: added "Before Step 2: UAT Validation" section to deploy-guide.md
- [x] [Review][Patch] GHCR_PAT missing from Prerequisites secrets list [deploy-guide.md:14]
- [x] [Review][Patch] Empty $CID guard missing — silent failure if container not in running state [rollback.md:125]
- [x] [Review][Patch] No .env backup before in-place sed mutation [rollback.md:75]
- [x] [Review][Patch] `skipped` Auto-Revert is a dead end not acknowledged in rollback.md [rollback.md:11]
- [x] [Review][Patch] Deploy guide "No SSH needed" misleads — developer may skip SSH setup only to find it required for manual rollback [deploy-guide.md:19]
- [x] [Review][Patch] SHA 7-char construction — `git log --oneline` shows auto-abbreviated SHA that may not be exactly 7 chars [deploy-guide.md:39, rollback.md:38]
- [x] [Review][Defer] No pre-deploy GHCR image existence check — deferred, pre-existing
- [x] [Review][Defer] No GHCR auth failure handling after `docker login` — deferred, pre-existing
- [x] [Review][Defer] Step 5 retry loop is manual and vague — deferred, enhancement
- [x] [Review][Defer] Partial pull failure leaves .env inconsistent — deferred, pre-existing
- [x] [Review][Defer] 60s smoke test window vs. `start_period: 60s` causes false Auto-Revert on slow JVM startup — deferred, pre-existing architecture
- [x] [Review][Defer] Auto-Revert fails if previous image deleted from GHCR by retention policy — deferred, pre-existing
- [x] [Review][Defer] `SSH_KNOWN_HOST` empty or multi-line edge cases — deferred, pre-existing
- [x] [Review][Defer] Container name `skillars-app-1` hardcoded in expected output without naming-convention explanation — deferred, pre-existing
- [x] [Review][Defer] No explicit guidance if `docker compose pull app` fails mid-execution — deferred, enhancement

### Change Log

- 2026-06-04: Created docs/deployment/deploy-guide.md and docs/deployment/rollback.md (deploy-2-3)
