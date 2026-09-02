# Deployment Guide

This guide covers the full release cycle: from a merged PR to a live production deploy.
A developer unfamiliar with the last deploy can complete the full cycle in ≤ 30 minutes using only this document.

---

## Prerequisites

Before triggering a deploy you need:

- **GitHub repository access** — to view Actions runs and trigger `workflow_dispatch`
- **Secrets configured** — the following GitHub Actions secrets must already be in place:
  `SSH_DEPLOY_KEY`, `SSH_HOST`, `SSH_USER`, `SSH_KNOWN_HOST`, `GHCR_PAT` (GitHub Container Registry Personal Access Token), `SLACK_WEBHOOK_URL`, `SMTP_*`, `NOTIFY_EMAIL`

  If any secrets are missing, see [`docs/deployment/secrets-reference.md`](secrets-reference.md) for the full list and setup instructions.

No SSH access to the Node is required to trigger a deploy — GitHub Actions handles that automatically. (Personal SSH access to the Node is required if you ever need to execute a manual rollback — see [`docs/deployment/rollback.md`](rollback.md).)

---

## Pre-Flight Checks

Before starting, verify:

- **GitHub Actions access**: You can view the Actions tab and trigger workflows in the repository
- **Secrets configured**: All GitHub Actions secrets listed in "Prerequisites" are in place (see [`docs/deployment/secrets-reference.md`](secrets-reference.md))
- **Production Node healthy**: If you have SSH access, optionally verify the Node is responsive and disk/memory are not critical (see [`docs/deployment/first-time-setup.md`](first-time-setup.md) for Node access)
- **No ongoing incident**: Check team chat or status page — do not deploy during an active production incident unless explicitly directed to do so

If any checks fail, resolve them before proceeding.

---

## Step 1: Find the Image Tag

The CI pipeline (`.github/workflows/ci.yml`) runs automatically on every push to the `master` branch. It builds and pushes a Docker image to GHCR tagged with the commit SHA in the format `sha-<short-sha>` (e.g., `sha-abc1234`).

**Option A — From GitHub Actions:**

1. Go to the repository → **Actions** tab → select the **CI Build** workflow
2. Find the run corresponding to the merge commit you want to deploy
3. Open the run and read the tag from the **Build and push Docker image** step output

**Option B — From the commit history:**

1. Go to the repository → **Commits**
2. Copy the first 7 characters of the SHA for the commit you want
3. Prepend `sha-`: e.g., commit `abc1234def` → tag `sha-abc1234`

**Option C — From the command line (if you have the repo cloned):**

```bash
git log --oneline -5
# Note: git log --oneline shows Git's auto-abbreviated SHA (may be 7 or more chars).
# Always use exactly the first 7 characters of the full SHA.
# For a precise 7-char SHA: git rev-parse --short=7 <commit>
```

The full image reference is:

```
ghcr.io/tenjohokwen/skillars:sha-<commit>
```

For example, if the commit SHA is `abc1234def`, the image tag is `sha-abc1234` and the full reference is `ghcr.io/tenjohokwen/skillars:sha-abc1234`.

---

## Before Step 2: UAT Validation

Before triggering a production deploy, validate the candidate image in your staging or UAT environment:

1. Deploy the image tag identified in Step 1 to your staging environment
2. Run acceptance tests and confirm the build meets your release criteria
3. **If UAT validation fails**: Do NOT proceed to Step 2. Fix the issues, merge the fix to `master`, and restart with a new image tag.
4. Once validated, proceed to Step 2 to trigger production

> The ≤ 30-minute cycle target assumes UAT validation is included in this window. If your UAT process takes longer, that is expected — do not skip it.

---

## Step 2: Trigger the Production Deploy

The production deploy is triggered manually via the GitHub Actions UI — no CLI or server access required.

1. Go to the repository → **Actions** tab → select **Deploy to Production**
2. Click **Run workflow** (top-right of the workflow runs list)
3. In the **`image_tag`** field, enter the tag from Step 1 (e.g., `sha-abc1234`)
4. Click **Run workflow**

The workflow will:

- SSH into the Node
- Capture the currently running image (used for Auto-Revert if needed)
- Authenticate to GHCR on the Node
- Pull the new image and restart the `app` service:
  ```
  docker compose pull app && docker compose up -d --no-deps app
  ```
- Run a Smoke Test via `docker exec` against `http://localhost:8367/manage/health` — waits 60 seconds upfront (matching the `app` container's `start_period: 60s` health check grace window) before the first check, then retries up to 12 attempts (~60 more seconds worst case). The management port is not exposed to the host; `/actuator/health` is the public path rewritten by Traefik and is not used here

---

## Step 3: Monitor the Deploy and Interpret the Result

The workflow takes approximately 2–5 minutes. Watch the GitHub Actions run in your browser or wait for the notification.

### Successful deploy

| Signal | Content |
|---|---|
| GitHub Actions run | Green ✅ |
| Slack | `✅ Production deploy succeeded` |
| Email | `✅ Deploy succeeded: sha-<tag>` |

The application is live at the configured domain. No further action needed.

### Failed deploy with Auto-Revert

| Signal | Content |
|---|---|
| GitHub Actions run | Red ❌ |
| Slack | `❌ Production deploy FAILED` — includes `Auto-Revert: <outcome>` and `Previous image: <ref>` |
| Email | `❌ Deploy FAILED (revert: <outcome>): sha-<tag>` |

**If `Auto-Revert: succeeded`** — production has been automatically restored to the previous image. No manual action required. Investigate the root cause before retrying.

**If `Auto-Revert: failed` or `Auto-Revert: skipped`** — production may be in an unknown state. Follow [`docs/deployment/rollback.md`](rollback.md) immediately.

---

## Step 4: Post-Deploy Validation (Optional)

After a successful deploy, you may optionally validate production with:

- **Check the health endpoint**: Visit `https://<domain>/actuator/health` (or use curl)
- **Smoke test key flows**: Log in as a user, verify critical features work end-to-end
- **Monitor logs**: Check Grafana or Loki for any error spikes post-deployment
- **Check metrics**: Verify CPU, memory, and request latency remain normal

If anything looks wrong, follow [`docs/deployment/rollback.md`](rollback.md) immediately.

---

## Expected Timeline

| Activity | Time |
|---|---|
| Pre-flight checks | ~1 min |
| Identify image tag | ~2 min |
| UAT validation (if needed) | ~5–20 min |
| Trigger workflow via GitHub Actions UI | ~1 min |
| Workflow runs (pull + restart + smoke test) | ~2–5 min |
| Result notification received | ~1 min after pass/fail |
| **Total (happy path, no UAT)** | **~5–10 min** |
| **Total (happy path, with UAT)** | **~15–30 min** |

Post-deploy validation (Step 4) is optional and not included in the timeline above.

---

## What to Do if Auto-Revert Fires

If the smoke test fails, the deploy workflow automatically attempts to restore the previous image (one level back).

Check the workflow run output and failure notification for the Auto-Revert outcome:

- **`succeeded`** — production is restored to the previous image; no manual action needed
- **`failed`** — the automatic restore itself failed; production may be in an inconsistent state
- **`skipped`** — no previous image existed (first ever deploy); nothing to revert to

For `failed` or `skipped` outcomes, or if you need to revert to an image older than the immediately previous one, follow the manual procedure in [`docs/deployment/rollback.md`](rollback.md).
