# Story Deploy-2.1: Automated CI Build Pipeline

Status: done

## Story

As a developer who has merged a change to `main`,
I want the pipeline to automatically build an immutable Docker image tagged with the commit SHA and push it to GHCR,
so that every merged commit produces a traceable, deployable artifact without any manual build step.

## Acceptance Criteria

**AC-1: Image built and pushed on merge to main**
- Given a pull request is merged to `main`
- When the GitHub Actions CI workflow runs
- Then a Docker image is built and pushed to GHCR tagged with the short commit SHA (`sha-<7-char-sha>`)
- And the image tag is deterministic — the same commit always produces the same tag
- And the image is traceable to its source commit via the tag

**AC-2: Build failure blocks pipeline; no partial push**
- Given the CI workflow runs
- When the Docker build step fails (compilation error, test failure, resource limit, etc.)
- Then the workflow is marked as failed in GitHub Actions and no image is pushed to GHCR
- And the failure is visible in the GitHub Actions UI without needing to inspect logs manually

**AC-3: No credentials in committed files or CI output**
- Given secrets are required for GHCR authentication
- When the workflow pushes to GHCR
- Then registry credentials are read from GitHub Actions secrets (`GHCR_PAT`)
- And no credential value appears in any committed workflow file or in CI output logs

---

## Tasks / Subtasks

- [x] Task 1: Create `Dockerfile` at project root (AC-1)
  - [x] Multi-stage build: builder stage (`maven:3.9-eclipse-temurin-17`) + runtime stage (`eclipse-temurin:17-jre-alpine`)
  - [x] Builder stage: run `mvn package -Dmaven.test.skip=true -B` — this also builds the Quasar frontend via `frontend-maven-plugin`
  - [x] Runtime stage: copy `target/skillars-0.0.1-SNAPSHOT.jar` as `app.jar`, expose ports 9990 and 8367, set ENTRYPOINT
  - [x] Optimize layer order: copy `pom.xml` first and run `mvn dependency:go-offline -B -q || true` before copying `src/` to improve cache hits
  - [x] Add `.dockerignore` to exclude `target/`, `node_modules/`, `.git/`, and `src/frontend/node/`

- [x] Task 2: Create `.github/workflows/ci.yml` (AC-1, AC-2, AC-3)
  - [x] Trigger: `on: push: branches: [main]`
  - [x] Job: `build-and-push` running on `ubuntu-latest`
  - [x] Step: `actions/checkout@v4`
  - [x] Step: `docker/login-action@v3` using `ghcr.io`, `github.actor` as username, `secrets.GHCR_PAT` as password
  - [x] Step: Compute short SHA (`echo $GITHUB_SHA | head -c 7`) and output it
  - [x] Step: `docker/build-push-action@v6` with `push: true`, tag `ghcr.io/${{ github.repository }}:sha-<short-sha>`
  - [x] Ensure no `continue-on-error: true` anywhere — build failure must fail the workflow

---

## Dev Notes

### This story is infrastructure-only — no Java source code changes

**Files to CREATE:**
- `Dockerfile` (project root)
- `.dockerignore` (project root)
- `.github/workflows/ci.yml`

**Files to READ (no changes expected):**
- `pom.xml` — Maven/Node build setup; understand build output
- `docker-compose.yml` — confirms `app` service uses `image: ${APP_IMAGE}` (the CI image must match this convention)
- `.env.example` — confirms image naming convention: `ghcr.io/<org>/javatemplate:sha-<commit>`
- `docs/deployment/secrets-reference.md` — confirms `GHCR_PAT` is the documented secret name

---

### Task 1 Detail: Dockerfile

The Maven build uses `frontend-maven-plugin` 1.15.1 which **downloads Node.js v22.16.0 and npm 11.4.2 automatically** during `generate-resources` phase. No separate Node.js stage is needed — Maven handles the full build including the Quasar SPA (`src/frontend/dist/spa`), which is copied to `target/classes/static` by `maven-resources-plugin`.

**Critical build flag:** Use `-Dmaven.test.skip=true` (not just `-DskipTests`). The `npm test` goal is bound to Maven's `test` phase via `frontend-maven-plugin`; `-Dmaven.test.skip=true` skips the entire test phase, including npm tests. The integration tests use Testcontainers which require a Docker daemon — unavailable inside a Docker build.

**Output JAR:** `target/skillars-0.0.1-SNAPSHOT.jar` — confirmed from `pom.xml` `<artifactId>skillars</artifactId>` + `<version>0.0.1-SNAPSHOT</version>`.

**Ports:**
- `9990` — main application port (from `application.yaml`: `server.port: "${port:9990}"`)
- `8367` — Spring Boot management/actuator port (from `application.yaml`: `management.server.port: 8367`)

**Dockerfile structure:**
```dockerfile
# Stage 1: Build (Java + Quasar frontend via frontend-maven-plugin)
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Layer: Java dependencies (cached until pom.xml changes)
COPY pom.xml .
RUN mvn dependency:go-offline -B -q || true

# Layer: Full source (Java + frontend — node binary downloaded here by frontend-maven-plugin)
COPY src/ src/
RUN mvn package -Dmaven.test.skip=true -B

# Stage 2: Runtime (minimal JRE image)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/skillars-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 9990 8367
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**`.dockerignore` must include:**
```
target/
.git/
src/frontend/node/
src/frontend/node_modules/
src/frontend/dist/
**/.DS_Store
```
This prevents large/irrelevant directories from entering the build context.

**Note on `dependency:go-offline`:** This caches Maven (Java) artifacts in the `~/.m2` layer, reducing subsequent build times when only `src/` changes. It cannot cache the Node.js binary (downloaded by `frontend-maven-plugin` during `generate-resources`). The `|| true` prevents failures if the goal exits non-zero due to plugin resolution quirks.

---

### Task 2 Detail: CI Workflow

**Image naming convention** (from `.env.example`): `ghcr.io/<org>/javatemplate:sha-<7-char-commit-sha>`

In GitHub Actions: `ghcr.io/${{ github.repository }}` produces `ghcr.io/<owner>/javatemplate` (since `github.repository` is `<owner>/<repo>`). Full tag: `ghcr.io/${{ github.repository }}:sha-<short-sha>`.

**Short SHA computation:** GitHub Actions provides `${{ github.sha }}` as the full 40-char SHA. Extract first 7 characters with:
```bash
echo "SHORT_SHA=$(echo $GITHUB_SHA | head -c 7)" >> $GITHUB_ENV
```
or via an output step — either approach is acceptable.

**GHCR authentication:** Use `docker/login-action@v3`. Credentials:
- `registry: ghcr.io`
- `username: ${{ github.actor }}`
- `password: ${{ secrets.GHCR_PAT }}`

The `GHCR_PAT` secret is already documented in `docs/deployment/secrets-reference.md` — must have `write:packages` scope. **Do NOT use `secrets.GITHUB_TOKEN`** — the repository uses an explicit PAT as per documented secrets.

**Workflow structure:**
```yaml
name: CI

on:
  push:
    branches: [main]

jobs:
  build-and-push:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GHCR_PAT }}

      - name: Compute short SHA
        id: sha
        run: echo "short=$(echo $GITHUB_SHA | head -c 7)" >> $GITHUB_OUTPUT

      - name: Build and push Docker image
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: ghcr.io/${{ github.repository }}:sha-${{ steps.sha.outputs.short }}
```

**AC-2 compliance:** Do NOT add `continue-on-error: true` to any step. GitHub Actions marks the job as failed when any step fails without `continue-on-error`. `docker/build-push-action` fails before pushing if the build fails, satisfying AC-2.

**AC-3 compliance:** The `GHCR_PAT` value is passed via `secrets.*` reference — it is never echoed, logged, or written to a file. GitHub Actions automatically masks secrets from log output.

---

### Relationship to Other Stories

**Epic 2 Story 2.2 (Manual Production Deploy)** will use the image produced by this workflow. The tag format `sha-<7-char>` must match the value expected in `APP_IMAGE` in the server's `.env` file. The deployer selects the image tag from GHCR when triggering Story 2.2's workflow.

**docker-compose.yml** (Story 1.2) uses `image: ${APP_IMAGE}` — no changes needed here. The CI image tag replaces the `APP_IMAGE` placeholder at deploy time.

---

### GitHub Actions Action Versions

Use these pinned major versions (latest stable as of 2026-06):
- `actions/checkout@v4`
- `docker/login-action@v3`
- `docker/build-push-action@v6`

Do NOT use `@latest` or unpinned tags — major version pins (`@v4`, `@v6`) are the standard practice.

---

### Project Structure Notes

- No `.github/` directory exists yet — create it with `mkdir -p .github/workflows/`
- No `Dockerfile` exists yet — create at project root alongside `docker-compose.yml`
- No `.dockerignore` exists yet — create at project root

---

### References

- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Story 2.1 — Acceptance Criteria]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#FR-4 — Automated image build + GHCR push on merge]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#Additional Requirements — "Deploy command pattern MUST use docker compose pull"]
- [Source: _bmad-output/planning-artifacts/deployment/epics.md#NFR-6 — Secrets hygiene: no secret in committed file or CI output]
- [Source: docker-compose.yml#Line 9 — app service: `image: ${APP_IMAGE}` — the expected image ref]
- [Source: .env.example#Line 8 — APP_IMAGE format: `ghcr.io/your-org/javatemplate:sha-abc1234`]
- [Source: docs/deployment/secrets-reference.md#GitHub Actions Secrets — GHCR_PAT with `write:packages` scope]
- [Source: pom.xml#Lines 393-452 — frontend-maven-plugin 1.15.1, Node v22.16.0, npm 11.4.2, workingDirectory: src/frontend]
- [Source: pom.xml#Lines 457-482 — maven-resources-plugin: copies src/frontend/dist/spa → target/classes/static]
- [Source: pom.xml#Lines 381-393 — spring-boot-maven-plugin produces fat JAR: target/skillars-0.0.1-SNAPSHOT.jar]
- [Source: src/main/resources/application.yaml#Line 3 — server.port: 9990]
- [Source: src/main/resources/application.yaml#Line 257 — management.server.port: 8367]

---

## Dev Agent Record

### Agent Model Used

claude-sonnet-4-6

### Debug Log References

_No debug issues encountered._

### Completion Notes List

- Created `Dockerfile` at project root: two-stage build (maven:3.9-eclipse-temurin-17 builder → eclipse-temurin:17-jre-alpine runtime). Layer order optimized — `pom.xml` + `dependency:go-offline` cached before copying `src/`. Uses `-Dmaven.test.skip=true` to bypass Testcontainers-dependent tests inside the Docker daemon-less build context.
- Created `.dockerignore`: excludes `target/`, `.git/`, `src/frontend/node/`, `src/frontend/node_modules/`, `src/frontend/dist/` to keep build context lean.
- Created `.github/workflows/ci.yml`: triggers on push to `main`, logs in to GHCR via `secrets.GHCR_PAT`, computes 7-char short SHA via `$GITHUB_OUTPUT`, builds and pushes image tagged `ghcr.io/${{ github.repository }}:sha-<short>`. No `continue-on-error` anywhere — AC-2 compliance guaranteed.
- All three ACs satisfied: deterministic `sha-<7>` tag (AC-1), build failure blocks push (AC-2), credentials via secrets only (AC-3).

### File List

- `Dockerfile`
- `.dockerignore`
- `.github/workflows/ci.yml`

## Change Log

- 2026-06-03: Created `Dockerfile` (multi-stage maven→jre-alpine), `.dockerignore`, and `.github/workflows/ci.yml` for automated CI build pipeline. All three ACs satisfied.

---

### Review Findings

- [x] [Review][Patch] No `permissions:` block on workflow job — job runs with default (potentially over-broad) token permissions; add `permissions: contents: read` at job level [`.github/workflows/ci.yml`]
- [x] [Review][Patch] OCI label `org.opencontainers.image.source` is bare repo name, not full HTTPS URL — breaks GHCR "Source" link; change to `https://github.com/${{ github.repository }}` [`.github/workflows/ci.yml:30`]
- [x] [Review][Patch] `COPY` after `USER appuser` has no `--chown` — jar is owned by root; add `--chown=appuser:appgroup` to the `COPY` instruction [`Dockerfile:21`]
- [x] [Review][Patch] `$GITHUB_SHA` unquoted in shell run step — bad practice; quote as `"$GITHUB_SHA"` [`.github/workflows/ci.yml:24`]
- [x] [Review][Patch] Hardcoded `skillars-0.0.1-SNAPSHOT.jar` in COPY will silently fail on next version bump — use `target/skillars-*.jar` or a Maven property to future-proof [`Dockerfile:21`]
- [x] [Review][Defer] No PR trigger — a broken Dockerfile is only discovered after merge to `main`, not during PR review [`.github/workflows/ci.yml`] — deferred, pre-existing
- [x] [Review][Defer] Actions pinned by floating tag not SHA digest — supply-chain risk; pin to immutable commit SHA in a future hardening pass [`.github/workflows/ci.yml`] — deferred, pre-existing
- [x] [Review][Defer] ~~No `HEALTHCHECK` in Dockerfile~~ — resolved 2026-06-04: added `HEALTHCHECK` using actuator endpoint [`Dockerfile`]
- [x] [Review][Defer] ~~No `--platform` flag on FROM~~ — resolved 2026-06-04: added `--platform=linux/amd64` to both FROM stages [`Dockerfile`]
- [x] [Review][Defer] No `SPRING_PROFILES_ACTIVE` in ENTRYPOINT — app boots on default profile; prod-specific beans may miss env-var overrides [`Dockerfile:23`] — deferred, pre-existing
- [x] [Review][Defer] No stable/latest tag alongside SHA tag — downstream consumers cannot reference a symbolic stable tag [`.github/workflows/ci.yml`] — deferred, pre-existing
