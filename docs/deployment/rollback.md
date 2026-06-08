# Manual Rollback Procedure

This guide covers manual reversion of production to any previous image. It is used when the automated
Auto-Revert mechanism is insufficient or unavailable.

---

## When to Use This Guide

Use this guide when:

- The Auto-Revert outcome was **`failed`** or **`skipped`** (shown in the GitHub Actions run log and failure notification)
  > **Note on `skipped`:** This means the deploy workflow had no previous image to restore (first-ever deploy). There is no previous image to roll back to in the manual procedure either — Step 1 below will yield no valid prior tag. In this case the correct action is to re-deploy a known-good image tag or fix the application issue and re-deploy.
- You need to revert to an image that is **not the immediately previous one** (Auto-Revert can only restore one level back)
- The application is in a broken state and the automated pipeline cannot fix it

**For a standard failed deploy where Auto-Revert succeeded** — production is already restored automatically. This guide is not needed.

### Understanding Auto-Revert limits

The deploy workflow captures the running image (via `docker inspect`) immediately before deploying the new image. Auto-Revert can only restore to that one captured image. If that image was itself a broken deploy, or if you need an older version, you must use this manual procedure.

---

## Step 1: Identify the Target Image Tag

Image tags follow the format `sha-<7-char-commit-sha>` (e.g., `sha-abc1234`). Identify the tag for the commit you want to run.

**Option A — From GitHub Actions CI history:**

1. Go to repository → **Actions** → **CI Build** workflow
2. Find the run for the commit you want to roll back to
3. Open the run and read the image tag from the **Build and push Docker image** step output
4. Full image reference: `ghcr.io/<org>/javatemplate:sha-<commit>`

**Option B — From git log:**

```bash
git log --oneline -10
# Identify the commit to roll back to.
# Note: git log --oneline shows Git's auto-abbreviated SHA (may be 7 or more chars).
# Always use exactly the first 7 characters of the full SHA.
# For a precise 7-char SHA: git rev-parse --short=7 <commit>
# Image tag: sha-<first-7-chars-of-full-sha>
```

**Option C — From the GHCR package page:**

1. Go to the packages page for your repository (e.g., `https://github.com/orgs/<org>/packages/container/javatemplate/versions`)
2. Find the image version by tag or date
3. Copy the tag (e.g., `sha-abc1234`)

Record the full image reference before proceeding:

```
ghcr.io/<org>/javatemplate:<tag>
```

---

## Step 2: SSH Into the Node

Connect to the production server using the SSH deploy key:

```bash
ssh <SSH_USER>@<SSH_HOST>
```

Where `SSH_USER` and `SSH_HOST` are the values from [`docs/deployment/secrets-reference.md`](secrets-reference.md) — the same credentials used by the GitHub Actions deploy workflow.

All subsequent commands in Steps 3–5 run on the Node.

---

## Step 3: Update APP_IMAGE in .env

The `.env` file lives at `/opt/skillars/.env`. The `APP_IMAGE` line controls which image the `app` service uses.

```bash
cd /opt/skillars

# Back up .env before modifying it
cp .env .env.bak

# Update APP_IMAGE to the target image
sed -i "s|^APP_IMAGE=.*|APP_IMAGE=ghcr.io/<org>/javatemplate:<tag>|" .env

# Verify the change before proceeding
grep '^APP_IMAGE=' .env
```

Replace `<org>` and `<tag>` with your actual values. The expected output is:

```
APP_IMAGE=ghcr.io/<org>/javatemplate:sha-abc1234
```

> **Note:** The `sed` command matches only the `APP_IMAGE=` line — it is safe to run. Do not edit `.env` manually with a text editor unless `sed` fails, as manual edits risk accidentally altering other values.

---

## Step 4: Pull and Restart the Service

Authenticate to GHCR and restart only the `app` service:

`GHCR_PAT` and `github-username` are stored as GitHub Actions secrets. Retrieve the values from [`docs/deployment/secrets-reference.md`](secrets-reference.md) before proceeding — you will need them for the `docker login` command below.

> **If you cannot retrieve `GHCR_PAT`** (GitHub Actions secrets are write-only after creation and cannot be viewed): generate a new Personal Access Token at [github.com/settings/tokens](https://github.com/settings/tokens) with `read:packages` scope. Use the new token below, then after the incident update the `GHCR_PAT` GitHub Actions secret with the new value.

```bash
cd /opt/skillars

# Authenticate to GHCR (required to pull from the private registry)
echo "<GHCR_PAT>" | docker login ghcr.io -u <github-username> --password-stdin

# Pull the target image and restart only the app service
docker compose pull app && docker compose up -d --no-deps app
```

> **CRITICAL:** Always use `--no-deps app` — do **not** run `docker compose up -d` (without specifying the service). Only the `app` service should be restarted. PostgreSQL, Redis, Traefik, and the LGTM observability stack must not be disrupted.

Expected output: Docker pulls the image layers, followed by:

```
Container skillars-app-1  Started
```

---

## Step 5: Verify the Rollback

Wait approximately 30 seconds for the application to start, then run the health check from inside the container:

```bash
# Get the running app container ID
CID=$(docker compose ps -q --status running app 2>/dev/null | head -1)
if [ -z "$CID" ]; then
  echo "ERROR: app container is not in running state — check: docker compose ps app"
  exit 1
fi

# Run health check from inside the container
docker exec "$CID" wget -qO- http://localhost:8367/manage/health
```

> The management port 8367 is not exposed to the host. Use `docker exec` — do not attempt to curl port 8367 from the Node directly.

**Expected response** (contains):

```json
{"status":"UP",...}
```

If the health check returns `"status":"DOWN"` or the command fails, the container may still be starting up. Retry after 10 seconds. If still failing after 60 seconds, check container logs:

```bash
docker compose logs --tail=50 app
```

Once the health check passes, production is restored. Traefik automatically routes traffic to the healthy container — no additional steps required.
