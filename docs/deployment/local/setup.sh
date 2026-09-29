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
  return 0
}

ALL_CHECKS=(check_docker_ready check_env check_compose_override check_seaweed_identity check_hosts_storage check_image_built check_start_helper)

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
# Generated by docs/deployment/local/setup.sh.
# Full explanation of every line here: docs/deployment/local/deployment.md, Step 3.
services:
  app:
    # Without this, `docker compose build app` would exit 0 having built nothing —
    # the base docker-compose.yml only carries `image: ${APP_IMAGE}`.
    build:
      context: .
      dockerfile: Dockerfile
    environment:
      - SPRING_PROFILES_ACTIVE=dev
      - APP_VIDEO_BUNNY_LIBRARY_ID=123456
      - MANAGEMENT_HEALTH_MAIL_ENABLED=false
      - APP_PAYMENT_STRIPE_API_KEY=sk_test_local_placeholder
      - GMAIL_PASSWORD=${GMAIL_PASSWORD:-dev_gmail_password}
      - GMX_PASSWORD=${GMX_PASSWORD:-dev_gmx_password}
      - APP_STORAGE_S3_ACCESS_KEY=minioadmin
      - APP_STORAGE_S3_SECRET_KEY=minioadmin123
      - APP_STORAGE_ENDPOINT_URL=http://storage:9500
      - APP_SES_FROM_ADDRESS=dev@localhost
      - APP_VIDEO_BUNNY_WEBHOOK_SIGNING_SECRET=dev-webhook-signing-secret
      - APP_VIDEO_PLAYBACK_SIGNING_SECRET=dGVzdC1wbGF5YmFjay1zaWduaW5nLXNlY3JldC0zMi1ieXRlcyEh
      - PLATFORM_PIN_ENCRYPTION_SECRET=S3CR3TW0RD
      - PLATFORM_REGISTRATION_VERIFICATION_SECRET=dev-registration-verification-secret-32-bytes!
      - GEMINI_API_KEY=${GEMINI_API_KEY:-dev-key}
    ports:
      - "9990:9990"   # main app
      - "8367:8367"   # actuator/health (management port)
    depends_on:
      storage:
        condition: service_healthy
  postgres:
    volumes:
      - skillars-local-postgres:/var/lib/postgresql/data
  redis:
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

  # Local S3-compatible storage (SeaweedFS — MinIO's Docker image stopped being
  # pullable anonymously in 2026-09). Named "storage", not "minio" — nothing
  # here has run MinIO since that migration.
  storage:
    image: chrislusf/seaweedfs:3.97
    command: >
      server -s3 -dir=/data -ip.bind=0.0.0.0 -s3.port=9500
      -s3.config=/etc/seaweedfs/s3.json -master.volumeSizeLimitMB=1024
    volumes:
      - skillars-local-storage:/data
      - ./deploy/seaweedfs/s3-identity.json:/etc/seaweedfs/s3.json:ro
    ports:
      - "9500:9500"   # S3 API
    healthcheck:
      test: ["CMD-SHELL", "nc -z localhost 9500"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 10s

  # One-shot: creates the dev bucket on every startup (no-op if it already exists).
  storage-init:
    image: amazon/aws-cli:2.31.13
    depends_on:
      storage:
        condition: service_healthy
    environment:
      - AWS_ACCESS_KEY_ID=minioadmin
      - AWS_SECRET_ACCESS_KEY=minioadmin123
      - AWS_DEFAULT_REGION=us-east-1
    entrypoint: >
      /bin/sh -c "
      aws --endpoint-url http://storage:9500 s3 mb s3://skillars-dev || true
      "

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
  ensure_image_built
  ensure_start_helper

  banner "Done"
  print_checklist
  print_start_command
  offer_to_start
}

main "$@"
