#!/usr/bin/env bash
# ==============================================================================
# Skillars — local setup wizard
# ==============================================================================
# Hand-holds a first-time run through everything docs/deployment/local/deployment.md
# describes manually: Docker, .env.local, docker-compose.local.yml, the SeaweedFS
# S3 identity file, the /etc/hosts entry, and the app image build.
#
# Safe to re-run any time — every step only acts when something is actually
# missing, and re-checks itself afterwards before moving on. On a machine that's
# already fully set up, it just prints a checklist and the one command that
# starts the stack.
#
# What it does NOT do: start the app for you (see docs/deployment/local/start.sh,
# which this script generates), or touch any file requiring root (/etc/hosts) —
# for that it tells you the exact command and waits.
#
# See docs/deployment/local/deployment.md for the full explanation behind every
# step below, and docs/deployment/local/manual-testing.md for what to do once
# the app is up.
# ==============================================================================

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

if [ ! -f "${REPO_ROOT}/docker-compose.yml" ]; then
  echo "Error: expected to find docker-compose.yml at ${REPO_ROOT} — this script"
  echo "assumes it lives at docs/deployment/local/setup.sh inside the skillars checkout."
  exit 1
fi
cd "${REPO_ROOT}"

ENV_FILE="${REPO_ROOT}/.env.local"
ENV_EXAMPLE="${REPO_ROOT}/.env.example"
COMPOSE_BASE="${REPO_ROOT}/docker-compose.yml"
COMPOSE_LOCAL="${REPO_ROOT}/docker-compose.local.yml"
SEAWEED_IDENTITY="${REPO_ROOT}/deploy/seaweedfs/s3-identity.json"
START_SCRIPT="${SCRIPT_DIR}/start.sh"
MAIL_DUMP_DIR="${REPO_ROOT}/target/mails"

# ------------------------------------------------------------------------------
# Output helpers
# ------------------------------------------------------------------------------
if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
  C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YELLOW=$'\033[33m'; C_CYAN=$'\033[36m'
else
  C_RESET=''; C_BOLD=''; C_DIM=''; C_GREEN=''; C_RED=''; C_YELLOW=''; C_CYAN=''
fi

banner()  { echo ""; echo "${C_BOLD}${C_CYAN}== $* ==${C_RESET}"; }
step()    { echo ""; echo "${C_BOLD}▶ $*${C_RESET}"; }
info()    { echo "  $*"; }
ok()      { echo "  ${C_GREEN}✓${C_RESET} $*"; }
warn()    { echo "  ${C_YELLOW}⚠${C_RESET}  $*"; }
fail()    { echo "  ${C_RED}✗${C_RESET} $*"; }

wait_for_enter() {
  # $1 = prompt. Returns 0 on plain Enter, 1 if the user typed 'skip'.
  local ans
  read -r -p "  $1 " ans
  [ "${ans}" = "skip" ] && return 1
  return 0
}

# ------------------------------------------------------------------------------
# .env.local key helpers (plain KEY=VALUE lines; comments/other keys untouched)
# ------------------------------------------------------------------------------
get_env_var() {
  local key="$1" file="$2" line
  [ -f "${file}" ] || return 1
  line="$(grep -E "^${key}=" "${file}" | tail -1)" || true
  [ -n "${line}" ] || return 1
  printf '%s\n' "${line#*=}"
}

set_env_var() {
  local key="$1" value="$2" file="$3" tmp
  tmp="$(mktemp)"
  if [ -f "${file}" ] && grep -qE "^${key}=" "${file}"; then
    awk -F'=' -v k="${key}" -v v="${value}" '
      $1==k { print k"="v; next }
      { print }
    ' "${file}" > "${tmp}"
  else
    [ -f "${file}" ] && cat "${file}" > "${tmp}"
    printf '\n%s=%s\n' "${key}" "${value}" >> "${tmp}"
  fi
  mv "${tmp}" "${file}"
}

gen_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 12
  else
    # Extremely unlikely fallback — openssl ships with macOS and every mainstream distro.
    { date +%s; echo "${RANDOM:-0}"; } | cksum | awk '{print $1}'
  fi
}

# ==============================================================================
# Step checks — each is a pure yes/no test, no side effects
# ==============================================================================

check_docker_ready() {
  command -v docker >/dev/null 2>&1 || return 1
  docker compose version >/dev/null 2>&1 || return 1
  docker info >/dev/null 2>&1 || return 1
  return 0
}

REQUIRED_ENV_KEYS=(APP_IMAGE POSTGRES_PASSWORD GF_SECURITY_ADMIN_PASSWORD MONITORING_DOMAIN GF_ALERT_NOTIFY_EMAIL GF_SLACK_WEBHOOK_URL)

needs_value() {
  local key="$1" val="$2"
  [ -z "${val}" ] && return 0
  case "${key}" in
    APP_IMAGE) [ "${val}" = "ghcr.io/tenjohokwen/skillars:sha-abc1234" ] && return 0 ;;
    POSTGRES_PASSWORD) case "${val}" in change-me*) return 0 ;; esac ;;
    GF_SECURITY_ADMIN_PASSWORD) case "${val}" in change-me*) return 0 ;; esac ;;
    MONITORING_DOMAIN) [ "${val}" = "monitoring.api.example.com" ] && return 0 ;;
    GF_ALERT_NOTIFY_EMAIL) [ "${val}" = "admin@example.com" ] && return 0 ;;
    GF_SLACK_WEBHOOK_URL) [ "${val}" = "https://hooks.slack.com/services/YOUR/SLACK/WEBHOOK" ] && return 0 ;;
  esac
  return 1
}

default_for() {
  case "$1" in
    APP_IMAGE) echo "skillars:local" ;;
    POSTGRES_PASSWORD) gen_secret ;;
    GF_SECURITY_ADMIN_PASSWORD) gen_secret ;;
    MONITORING_DOMAIN) echo "unused" ;;
    GF_ALERT_NOTIFY_EMAIL) echo "alerts@localhost" ;;
    GF_SLACK_WEBHOOK_URL) echo "https://hooks.slack.com/services/unused/unused/unused" ;;
  esac
}

check_env() {
  [ -f "${ENV_FILE}" ] || return 1
  local k v
  for k in "${REQUIRED_ENV_KEYS[@]}"; do
    v="$(get_env_var "${k}" "${ENV_FILE}" || true)"
    needs_value "${k}" "${v}" && return 1
  done
  return 0
}

check_compose_override() {
  [ -f "${COMPOSE_LOCAL}" ] || return 1
  grep -q "dockerfile: Dockerfile" "${COMPOSE_LOCAL}" || return 1
  grep -qE "^[[:space:]]*storage:" "${COMPOSE_LOCAL}" || return 1
  grep -qi "seaweedfs" "${COMPOSE_LOCAL}" || return 1
  grep -q "s3-identity.json" "${COMPOSE_LOCAL}" || return 1
  grep -qE "^[[:space:]]*storage-init:" "${COMPOSE_LOCAL}" || return 1
  grep -q "APP_STORAGE_S3_ACCESS_KEY" "${COMPOSE_LOCAL}" || return 1
  return 0
}

check_seaweed_identity() { [ -f "${SEAWEED_IDENTITY}" ]; }

check_hosts_storage() {
  grep -qE '^[^#]*127\.0\.0\.1[^#]*(^|[[:space:]])storage([[:space:]]|$)' /etc/hosts 2>/dev/null
}

check_image_built() {
  local img
  img="$(get_env_var APP_IMAGE "${ENV_FILE}" 2>/dev/null || true)"
  [ -n "${img}" ] || return 1
  docker image inspect "${img}" >/dev/null 2>&1
}

check_start_helper() {
  [ -x "${START_SCRIPT}" ] || return 1
  grep -q "storage-init" "${START_SCRIPT}" || return 1
  grep -q "target/mails" "${START_SCRIPT}" || return 1
  return 0
}

# ses-1.5: the dev profile runs app.email.transport=log, which writes each rendered email to
# /app/target/mails inside the container (bind-mounted to ./target/mails by docker-compose.local.yml).
# The container runs as a non-root user with an unpinned uid, so on Linux the host directory must exist
# beforehand (Docker would otherwise create it root-owned) and be writable by "other".
check_mail_dump_dir() { [ -d "${MAIL_DUMP_DIR}" ] && [ -w "${MAIL_DUMP_DIR}" ] && [ -x "${MAIL_DUMP_DIR}" ] && mode_is_777 "$(stat -c '%a' "${MAIL_DUMP_DIR}" 2>/dev/null || stat -f '%Lp' "${MAIL_DUMP_DIR}")"; }
# Compares only the permission digits: a setgid/sticky directory reports 2777/1777 and is just as writable.
mode_is_777() { local mode="$1"; [ "${mode: -3}" = "777" ]; }

ALL_CHECKS=(check_docker_ready check_env check_compose_override check_seaweed_identity check_hosts_storage check_mail_dump_dir check_image_built check_start_helper)

all_done() {
  local fn
  for fn in "${ALL_CHECKS[@]}"; do
    "${fn}" || return 1
  done
  return 0
}

# ==============================================================================
# Step fixers — each only acts when its check currently fails
# ==============================================================================

ensure_docker_ready() {
  if check_docker_ready; then
    ok "Docker Engine + Compose plugin found, daemon is responding."
    return 0
  fi
  step "Docker"
  info "Skillars runs entirely in Docker containers locally, so this has to work first."
  while true; do
    if ! command -v docker >/dev/null 2>&1; then
      fail "Docker is not installed."
      info "Install Docker Desktop: https://www.docker.com/products/docker-desktop/"
    elif ! docker compose version >/dev/null 2>&1; then
      fail "The 'docker compose' plugin isn't available (an old Docker install?)."
      info "Update Docker Desktop, or install the compose-plugin package for your OS."
    else
      fail "Docker is installed but the daemon isn't responding."
      info "Start Docker Desktop (or 'sudo systemctl start docker' on Linux) and wait until it's ready."
    fi
    wait_for_enter "Press Enter to re-check once that's done:"
    check_docker_ready && { ok "Docker is ready."; return 0; }
    warn "Still not ready — trying again."
  done
}

ensure_env() {
  if check_env; then
    ok ".env.local already has all required local values."
    return 0
  fi
  step "Local environment file (.env.local)"
  if [ ! -f "${ENV_FILE}" ]; then
    info "No .env.local found — this is your personal, git-ignored copy of .env.example."
    cp "${ENV_EXAMPLE}" "${ENV_FILE}"
    info "Created ${ENV_FILE}"
  else
    info "Found .env.local — filling in whatever's still a placeholder or missing."
  fi
  local k v newv
  for k in "${REQUIRED_ENV_KEYS[@]}"; do
    v="$(get_env_var "${k}" "${ENV_FILE}" || true)"
    if needs_value "${k}" "${v}"; then
      newv="$(default_for "${k}")"
      set_env_var "${k}" "${newv}" "${ENV_FILE}"
      info "Set ${k} (was empty or the .env.example placeholder)."
      if [ "${k}" = "GF_SECURITY_ADMIN_PASSWORD" ]; then
        info "  -> this is your local Grafana 'admin' login password: ${newv}"
      fi
    fi
  done
  if check_env; then
    ok ".env.local is ready. (Git-ignored — feel free to edit it by hand later.)"
  else
    fail "Something is still wrong with .env.local — check it by hand against .env.example."
    exit 1
  fi
}

write_compose_override() {
  cat > "${COMPOSE_LOCAL}" <<'YAML'
# Generated by docs/deployment/local/setup.sh (a copy of the tracked docker-compose.local.yml; keep them in step).
services:
  app:
    # Without this, `docker compose build app` exits 0 having done NOTHING. The base
    # docker-compose.yml only carries `image: ${APP_IMAGE}` (it pulls from GHCR in production), and
    # Compose silently no-ops a build for a service with no `build:` key — so you keep deploying
    # whatever image was last built by hand. That cost the deferred-17 dev significant time: a
    # shipped fix looked broken because the running jar predated it.
    # Only added to the LOCAL override. docker-compose.yml and docker-compose.uat.yml must keep
    # pulling the published image; building from source on a server is not what either does.
    build:
      context: .
      dockerfile: Dockerfile
    environment:
      - SPRING_PROFILES_ACTIVE=dev
      # docker-compose.yml (base file, always loaded alongside this override) sets
      # APP_FRONTEND_URL=https://skillars.com as its default. Spring environment variables rank
      # above application-dev.yaml in the property source order regardless of whether the YAML
      # value is a placeholder or a literal, so without this override every verification email
      # sent by Mode A links to production, not this machine.
      - APP_FRONTEND_URL=http://localhost:9990
      - APP_VIDEO_BUNNY_LIBRARY_ID=123456
      # ses-1.2 D9 + code review 2026-09-11: this knob is now DEAD — see docker-compose.uat.yml's
      # full explanation. Boot's own mail health contributor is @ConditionalOnBean(JavaMailSenderImpl),
      # a bean nothing in this codebase creates now that spring.mail.host is gone. Kept per AC7.
      - MANAGEMENT_HEALTH_MAIL_ENABLED=false
      # PaymentConfig.configureStripe() throws AppSetupException on a blank key, and blank is what
      # it resolves to locally: application.yaml defaults it to "", application-dev.yaml has no
      # payment section, and the base compose file never passes it (only docker-compose.uat.yml
      # does). Without this the whole app context refuses to start. The guard in that same method
      # rejects only ^(sk|rk)_live_ outside the prod profile, so a test-shaped placeholder is fine
      # and a real live key would still be refused here.
      - APP_PAYMENT_STRIPE_API_KEY=sk_test_local_placeholder
      # No LOKI_ENABLED override: docker-compose.yml's LOKI_ENABLED=true now works, so
      # app logs actually reach the local Loki container and are queryable in Grafana.
      # ses-1.5: dev runs app.email.transport: log (application-dev.yaml): no mail is sent; each
      # rendered email is written to /app/target/mails (bind-mounted below to ./target/mails on the
      # host), including registration/OTP mail. ses-1.1's app.ses.enabled and the DevSesEmailService/
      # @Profile("!dev") trap it used to route around are both gone.
      # ses-1.2 D9: SPRING_MAIL_PASSWORD removed — it backed Boot's own autoconfigured
      # JavaMailSenderImpl, which nothing in this codebase ever injected.
      # GMAIL_PASSWORD / GMX_PASSWORD are NOT used by dev any more (ses-1.5: transport=log). They stay
      # only because the shared base docker-compose.yml still passes them for uat, which runs the smtp
      # transport until the SES cutover (ses-1-5b, deferred) and Phase 6 (ses-1-6) deletes them.
      # The `:-` defaults restate application-dev.yaml's own fallbacks - same "EMPTY IS NOT UNSET"
      # reasoning as every other variable in this block (see the comment below): a bare `docker
      # compose -f docker-compose.local.yml up` with no `.env` would otherwise pass an empty value.
      - GMAIL_PASSWORD=${GMAIL_PASSWORD:-dev_gmail_password}
      - GMX_PASSWORD=${GMX_PASSWORD:-dev_gmx_password}
      # These restate application-dev.yaml's OWN defaults, and are required rather than tidy.
      # docker-compose.yml passes each of these variables through with a `:-` empty default so a
      # production deploy can set them. But an empty environment variable is present-and-blank,
      # not absent, so it OVERRIDES the `${VAR:dev-default}` fallbacks in application-dev.yaml —
      # the dev profile's defaults stop applying the moment the base file passes the variable.
      # Found by running it: an empty APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET aborts startup in
      # BunnyVideoProviderAdapter ("webhookSigningSecret must not be blank"), and an empty
      # APP_STORAGE_S3_ACCESS_KEY silently drops storage back onto the AWS default credential
      # chain, breaking every upload. Keep this block in step with application-dev.yaml.
      - APP_STORAGE_S3_ACCESS_KEY=minioadmin
      - APP_STORAGE_S3_SECRET_KEY=minioadmin123
      # The base file defaults the bucket to skillars-prod; local storage-init creates skillars-dev.
      - APP_STORAGE_BUCKET=skillars-dev
      # Base passes these with a prod-safe default (false / none), which overrides application-dev.yaml.
      # Dev-only redis password (S-24); DeployedSecretsValidator rejects it on prod/uat. Redis is only
      # reachable on the skillars-data network, never published to the host.
      - SPRING_DATA_REDIS_PASSWORD=dev-redis-password-NOT-for-prod
      - JWT_SIGNING_KEY=ZGV2LW9ubHktand0LXNpZ25pbmcta2V5LS1OT1QtZm9yLXByb2Qtb3ItdWF0LS1sb2NhbC1kZXZlbG9wbWVudC1vbmx5ISE=
      - ALLOWED_CLIENTS=${ALLOWED_CLIENTS:-myClientId,hisClientId,herClientId,ourClientId}
      # docker-compose.yml's APP_SES_FROM_ADDRESS carries no default at all (see the comment
      # there), so listing it in the base file makes it present-and-blank under compose —
      # overriding application-dev.yaml's ${APP_SES_FROM_ADDRESS:dev@localhost} fallback the
      # same way the other variables in this block do. Harmless today (dev runs the log
      # transport, which never reads from-address), but restated anyway so it isn't silently
      # dead the moment anyone runs dev locally against the ses transport.
      - APP_SES_FROM_ADDRESS=dev@localhost
      - APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET=dev-webhook-signing-secret
      - APP_VIDEO_PLAYBACK_SIGNING_SECRET=dGVzdC1wbGF5YmFjay1zaWduaW5nLXNlY3JldC0zMi1ieXRlcyEh
      - PLATFORM_PIN_ENCRYPTION_SECRET=S3CR3TW0RD
      - PLATFORM_REGISTRATION_VERIFICATION_SECRET=dev-registration-verification-secret-32-bytes!
      - GEMINI_API_KEY=${GEMINI_API_KEY:-dev-key}
      # App container talks to local storage over the compose network by service name.
      # The presigned upload URLs it generates also use this host — see the
      # /etc/hosts note by the `storage` service below for why that's required.
      - APP_STORAGE_ENDPOINT_URL=http://storage:9500
    # ses-1.5: host side of the email dump directory. application-dev.yaml's relative
    # app.email.log.dump-dir (target/mails) resolves to /app/target/mails in the container (WORKDIR /app).
    # The container user (busybox adduser -S, unpinned uid) can only write here if the host directory
    # already exists and is world-writable: docs/deployment/local/setup.sh creates it (mode 777, dev-only).
    # If Docker creates it instead it is root-owned and LoggingEmailSender logs a WARN and falls back to
    # log-only (the OTP is then not recoverable from a file), and a later `mvn clean` cannot delete it.
    volumes:
      - ./target/mails:/app/target/mails
    ports:
      - "9990:9990"   # main app
      - "8367:8367"   # actuator/health (management port)
    depends_on:
      storage:
        condition: service_healthy
  postgres:
    volumes:
      - skillars-local-postgres:/var/lib/postgresql/data
  # Required since UAT.2 AC6 moved redis off a Docker named volume and onto the Hetzner Volume
  # bind mount /opt/skillars/data/redis. Without this override, running the local stack would
  # create that production path on the developer's own machine.
  redis:
    environment:
      - REDIS_PASSWORD=dev-redis-password-NOT-for-prod
    volumes:
      - skillars-local-redis:/data
  loki:
    volumes:
      - skillars-local-loki:/loki
  tempo:
    volumes:
      - skillars-local-tempo:/var/tempo
  prometheus:
    volumes:
      - skillars-local-prometheus:/prometheus
  grafana:
    ports:
      - "3000:3000"   # Grafana UI
    volumes:
      - skillars-local-grafana:/var/lib/grafana

  # Local S3-compatible storage so file uploads (e.g. coach profile photos) don't
  # need real AWS credentials in dev.
  #
  # Runs SeaweedFS (see below for why) on 9500, not MinIO's old default 9000/9001 —
  # this project's frontend dev server (Quasar) already owns 9000 by convention
  # (matches app.frontend-url), so this listens on 9500 instead to avoid stealing that port.
  #
  # The `app` container reaches this over the compose network as `storage:9500`.
  # Presigned upload URLs the app hands back to the browser use that same host —
  # but your browser runs on the host machine, not inside the compose network, so
  # it can't resolve `storage` on its own. Add this one-time entry to /etc/hosts:
  #   127.0.0.1 storage
  # That plus the published port below (9500:9500) makes `http://storage:9500/...`
  # resolve to this container from both the app and your browser.
  #
  # This runs SeaweedFS, not MinIO — as of 2026-09-24, quay.io/minio/minio (both its pinned
  # tag and :latest) 401s on anonymous pulls, a widely-reported break, not something specific
  # to this project. See SharedContainers.STORAGE_IMAGE's javadoc
  # (src/test/java/com/softropic/skillars/config/SharedContainers.java) for the full story and
  # why SeaweedFS was chosen. The service/volume/hostname were briefly kept as "minio" right
  # after that migration to minimize the diff, then renamed to "storage" on 2026-09-28 once it
  # was clear that name would only confuse anyone who didn't live through the migration — nothing
  # here has been MinIO for a while. Credentials unchanged (minioadmin/minioadmin123, sourced
  # from deploy/seaweedfs/s3-identity.json rather than env vars — SeaweedFS's own env-var
  # credential path has a live upstream reliability issue, seaweedfs/seaweedfs#7311). No
  # web-console port: SeaweedFS has no MinIO-console equivalent at this scope (its filer UI on
  # 8888 is the closest thing, not wired up here).
  storage:
    image: chrislusf/seaweedfs:3.97
    # Restores the image's own entrypoint. docker-compose.yml's production `storage` replaces it
    # with a shell script that writes the identity file from STORAGE_ROOT_* — but here that file is
    # the read-only s3-identity.json mount below, so inheriting that entrypoint would fail on boot.
    entrypoint: ["/entrypoint.sh"]
    command: >
      server -s3 -dir=/data -ip.bind=0.0.0.0 -s3.port=9500
      -s3.config=/etc/seaweedfs/s3.json -master.volumeSizeLimitMB=1024
    volumes:
      - skillars-local-storage:/data
      - ./deploy/seaweedfs/s3-identity.json:/etc/seaweedfs/s3.json:ro
    ports:
      - "9500:9500"   # S3 API
    healthcheck:
      # No `mc`-equivalent ships in this image; a plain TCP probe on the S3 port is enough
      # to gate `depends_on: condition: service_healthy` below.
      test: ["CMD-SHELL", "nc -z localhost 9500"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 10s
    networks:
      - skillars-internal
    restart: unless-stopped

  # One-shot: creates the dev bucket on every startup (no-op if it already exists), and puts a
  # permissive CORS policy on it. Without the CORS step, the browser's own presigned-upload flow
  # (CoachProfileBuilder's photo upload, etc. — the browser PUTs straight to `storage:9500` with a
  # presigned URL, bypassing the app entirely) 403s at the OPTIONS preflight: S3-compatible APIs
  # (SeaweedFS included) refuse cross-origin preflights by default until a bucket CORS policy says
  # otherwise, same as real AWS S3 — found manually testing the coach profile-builder photo upload
  # (2026-10-01). amazon/aws-cli replaces quay.io/minio/mc here — same reasoning as the `storage`
  # service above (mc's own image is under the same now-blocked quay.io/minio/* namespace), and
  # aws-cli is a generic S3 client so it works against SeaweedFS's S3 gateway unmodified.
  storage-init:
    image: amazon/aws-cli:2.31.13
    depends_on:
      storage:
        condition: service_healthy
    environment:
      - AWS_ACCESS_KEY_ID=minioadmin
      - AWS_SECRET_ACCESS_KEY=minioadmin123
      - AWS_DEFAULT_REGION=us-east-1
    volumes:
      - ./deploy/seaweedfs/s3-cors.json:/etc/seaweedfs/s3-cors.json:ro
    entrypoint: >
      /bin/sh -c "
      aws --endpoint-url http://storage:9500 s3 mb s3://skillars-dev || true &&
      aws --endpoint-url http://storage:9500 s3api put-bucket-cors --bucket skillars-dev --cors-configuration file:///etc/seaweedfs/s3-cors.json
      "
    networks:
      - skillars-internal

volumes:
  skillars-local-postgres:
  skillars-local-redis:
  skillars-local-loki:
  skillars-local-tempo:
  skillars-local-prometheus:
  skillars-local-grafana:
  skillars-local-storage:
YAML
}

ensure_compose_override() {
  if check_compose_override; then
    ok "docker-compose.local.yml already has the app build, storage and secrets wired up."
    return 0
  fi
  step "Local Docker Compose override (docker-compose.local.yml)"
  info "docker-compose.yml alone doesn't publish ports to your host, build the image locally,"
  info "or start local S3-compatible storage — docker-compose.local.yml adds all three."
  info "It's a real file checked into this repo (not something you author yourself), so seeing"
  info "it missing usually means an incomplete checkout rather than a first-time setup step."
  if [ ! -f "${COMPOSE_LOCAL}" ]; then
    if git -C "${REPO_ROOT}" ls-files --error-unmatch docker-compose.local.yml >/dev/null 2>&1; then
      info "It's tracked in git but missing from your working tree — restoring it."
      git -C "${REPO_ROOT}" checkout -- docker-compose.local.yml
    else
      info "Not tracked in git either — writing the standard local override from scratch."
      write_compose_override
    fi
    ok "docker-compose.local.yml is present at the repo root."
  else
    while true; do
      warn "docker-compose.local.yml exists but looks incomplete — missing one of: the app build"
      warn "block, the storage/SeaweedFS service, or the storage env vars."
      warn "Not overwriting it automatically — it may hold changes made on purpose. If this is"
      warn "unexpected, 'git log -- docker-compose.local.yml' / 'git diff' may show what drifted."
      warn "Otherwise compare it against docs/deployment/local/deployment.md, Step 3, and add what's missing."
      if ! wait_for_enter "Press Enter once it's updated to re-check (or type 'skip' to continue anyway):"; then
        warn "Skipping — later steps (image build, start) may fail without a complete override."
        return 0
      fi
      check_compose_override && { ok "docker-compose.local.yml now looks complete."; return 0; }
      warn "Still missing something."
    done
  fi
}

ensure_seaweed_identity() {
  if check_seaweed_identity; then
    ok "deploy/seaweedfs/s3-identity.json present."
    return 0
  fi
  step "SeaweedFS S3 credentials file"
  fail "Missing deploy/seaweedfs/s3-identity.json — this file is checked into the repo and"
  fail "the local storage container needs it to boot. Your checkout may be incomplete."
  info "Restore it with:"
  echo ""
  echo "    git checkout -- deploy/seaweedfs/s3-identity.json"
  echo ""
  while true; do
    wait_for_enter "Press Enter once it's restored to re-check:"
    check_seaweed_identity && { ok "Found it."; return 0; }
    warn "Still missing."
  done
}

ensure_mail_dump_dir() {
  if check_mail_dump_dir; then
    ok "target/mails exists and is writable by the app container (local email dump directory)."
    return 0
  fi
  step "Local email dump directory"
  info "Locally the app writes each email it would send to target/mails (rendered HTML, one file per send)"
  info "instead of sending it - that is where you find registration OTP codes and verification links."
  info "The container's user can only write there if the directory pre-exists and is world-writable"
  info "(dev-only; target/ is gitignored and holds no secrets)."
  mkdir -p "${MAIL_DUMP_DIR}" && chmod 777 "${MAIL_DUMP_DIR}"
  if check_mail_dump_dir; then
    ok "Created ${MAIL_DUMP_DIR} (mode 777)."
  else
    warn "Could not prepare ${MAIL_DUMP_DIR}; emails will only appear in the app log."
    info "If Docker created it (root-owned, Linux), remove it and re-run: sudo rm -rf \"${MAIL_DUMP_DIR}\""
  fi
}

ensure_hosts_storage() {
  if check_hosts_storage; then
    ok "/etc/hosts already resolves 'storage' to 127.0.0.1."
    return 0
  fi
  step "/etc/hosts entry for 'storage'"
  info "The app hands your browser upload links pointing at http://storage:9500. Your browser"
  info "can't resolve that hostname on its own — only the app container can, over Docker's"
  info "internal network — so it needs a hosts-file entry pointing it back to your machine."
  warn "This edits a system file and needs administrator rights, so please run it yourself:"
  echo ""
  echo "    echo \"127.0.0.1 storage\" | sudo tee -a /etc/hosts"
  echo ""
  while true; do
    if ! wait_for_enter "Press Enter once you've run that (or type 'skip' to continue without file uploads working):"; then
      warn "Skipping — coach profile photo and drill video uploads will fail until this is added."
      return 0
    fi
    check_hosts_storage && { ok "Confirmed — 'storage' resolves to 127.0.0.1."; return 0; }
    warn "Still not found in /etc/hosts — double-check the command ran without error, then try again."
  done
}

ensure_image_built() {
  if check_image_built; then
    ok "Docker image '$(get_env_var APP_IMAGE "${ENV_FILE}")' already built."
    return 0
  fi
  step "App image"
  local img
  img="$(get_env_var APP_IMAGE "${ENV_FILE}")"
  info "Image '${img}' not found locally — building it now."
  info "This compiles the Java backend AND the Quasar frontend inside Docker, so a first build"
  info "can take several minutes. Subsequent runs of this script will skip it once it exists."
  echo ""
  if docker compose -f "${COMPOSE_BASE}" -f "${COMPOSE_LOCAL}" --env-file "${ENV_FILE}" build app; then
    :
  else
    fail "The build failed — scroll up for the Maven/npm error, fix it, then re-run this script."
    exit 1
  fi
  if check_image_built; then
    ok "Image '${img}' built successfully."
  else
    fail "Build finished but image '${img}' still isn't found."
    fail "Check that APP_IMAGE in .env.local matches what docker-compose.local.yml's app.build produces."
    exit 1
  fi
}

write_start_helper() {
  cat > "${START_SCRIPT}" <<'EOF'
#!/usr/bin/env bash
# Generated by docs/deployment/local/setup.sh — the single command that starts the
# local Skillars stack. Re-run setup.sh any time to verify or repair what this needs.
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../../.."
# ses-1.5: docker-compose.local.yml bind-mounts ./target/mails; `mvn clean` deletes it, and if Docker
# re-creates it the directory is root-owned (Linux) and the app can no longer write emails there.
mkdir -p target/mails && chmod 777 target/mails 2>/dev/null || echo "WARN: target/mails is not writable by the app; re-run docs/deployment/local/setup.sh" >&2
docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local \
  up -d app postgres redis storage-init grafana
cat <<'MSG'

Skillars is starting in the background. First boot can take up to ~60s while
SeaweedFS's healthcheck passes, then Flyway migrations run.

  App:      http://localhost:9990
  Health:   http://localhost:8367/manage/health
  Grafana:  http://localhost:3000

Check status:   docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local ps
Tail app logs:  docker compose -f docker-compose.yml -f docker-compose.local.yml --env-file .env.local logs -f app
Stop:           docs/deployment/local/stop.sh
MSG
EOF
  chmod +x "${START_SCRIPT}"
}

ensure_start_helper() {
  if check_start_helper; then
    ok "Start command already in place: docs/deployment/local/start.sh"
    return 0
  fi
  step "Start command"
  write_start_helper
  ok "Created docs/deployment/local/start.sh — this is the one command you'll run from now on."
}

# ==============================================================================
# Checklist + final output
# ==============================================================================

checklist_line() {
  local label="$1" fn="$2"
  if "${fn}"; then
    printf "  ${C_GREEN}[x]${C_RESET} %s\n" "${label}"
  else
    printf "  ${C_RED}[ ]${C_RESET} %s\n" "${label}"
  fi
}

print_checklist() {
  echo ""
  echo "${C_BOLD}Local setup status:${C_RESET}"
  checklist_line "Docker Engine + Compose plugin ready"       check_docker_ready
  checklist_line ".env.local configured"                      check_env
  checklist_line "docker-compose.local.yml overrides present" check_compose_override
  checklist_line "SeaweedFS S3 identity file present"         check_seaweed_identity
  checklist_line "/etc/hosts 'storage' entry present"         check_hosts_storage
  checklist_line "target/mails email dump dir writable"       check_mail_dump_dir
  checklist_line "App image built"                            check_image_built
  checklist_line "Start command generated"                    check_start_helper
}

print_start_command() {
  echo ""
  echo "${C_BOLD}Single command to start Skillars locally:${C_RESET}"
  echo ""
  echo "    ${START_SCRIPT}"
  echo ""
}

offer_to_start() {
  local ans
  read -r -p "Start it now? [y/N] " ans
  case "${ans}" in
    [yY]*) "${START_SCRIPT}" ;;
    *) echo "OK — run the command above whenever you're ready." ;;
  esac
}

main() {
  banner "Skillars — local setup"

  if all_done; then
    ok "Everything is already configured."
    print_checklist
    print_start_command
    offer_to_start
    exit 0
  fi

  info "Walking through local setup step by step. Re-run this script any time — it only"
  info "acts on what's still missing, and this same walkthrough doubles as a status check."

  ensure_docker_ready
  ensure_env
  ensure_compose_override
  ensure_seaweed_identity
  ensure_hosts_storage
  ensure_mail_dump_dir
  ensure_image_built
  ensure_start_helper

  banner "Done"
  print_checklist
  print_start_command
  offer_to_start
}

main "$@"
