#!/usr/bin/env bash
# Restores /opt/skillars/data (excluding postgres/, which is not archived by volume-backup.sh —
# restore the database separately via restore-from-dump.sh) from the latest, or an explicitly
# named, file-level volume backup in Object Storage. Replaces restore-from-snapshot.sh, which
# restored from Hetzner Cloud volume snapshots that were never actually created — see
# deferred-work.md, skillars-uat-3 D1.
set -euo pipefail

log() { echo "[restore-from-volume-backup] $*"; }
err() { echo "[restore-from-volume-backup][error] $*" >&2; }

GUARD_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env-guard.sh"
if [ -d "$GUARD_PATH" ]; then
  echo "[restore-from-volume-backup][error] ${GUARD_PATH} is a directory, not a file — cannot load credential guard" >&2
  exit 1
fi
if [ ! -r "$GUARD_PATH" ]; then
  echo "[restore-from-volume-backup][error] cannot read ${GUARD_PATH} — required for credential loading" >&2
  exit 1
fi
# shellcheck source=env-guard.sh
. "$GUARD_PATH"
require_env_vars "restore-from-volume-backup" "restore" HOS_ACCESS_KEY HOS_SECRET_KEY HOS_BUCKET HOS_ENDPOINT

# skillars-deferred-102 AC6: checkout moved to /opt/skillars/app; .env stays at /opt/skillars/.env.
COMPOSE_FILE="/opt/skillars/app/docker-compose.yml"
DC="docker compose --env-file /opt/skillars/.env -f ${COMPOSE_FILE}"
DATA_DIR="/opt/skillars/data"

# skillars-deferred-108 AC8 (deferred-107 code review): the image_runtime_uid probe below runs
# INSIDE the `${DC} down` → `${DC} up -d` outage window, on exactly the host conditions a
# disaster restore implies (no / slow egress, pruned images). `|| return 0` only fires once a
# `docker pull` / `docker run` has FINISHED failing, so an unreachable registry could stretch
# the outage indefinitely. Bound both remote calls.
#
# A timeout (exit 124) is indistinguishable from any other probe failure: it falls through the
# existing `|| return 0`, and chown_probed then takes its `[ -z "$probed_uid" ]` arm, which chowns
# to the hardcoded fallback SILENTLY (safe under set -e). There is NO WARN on this path — the WARN
# fires only when the probe SUCCEEDS and disagrees with the constant. During a restore, read a
# missing "image now runs as uid" line as "the probe did not run", not as confirmation.
#
# The probe runs once per service (5×). skillars-deferred-109 AC11 additionally bounds the two
# `docker image inspect` daemon round-trips inside image_runtime_uid (a wedged dockerd otherwise
# hangs there indefinitely — and the FIRST inspect runs before the wrapped `docker pull`, so it is
# the first place a wedged daemon stalls). Worst-case ADDED outage is now
# 5 × (2×IMAGE_INSPECT_TIMEOUT + IMAGE_PULL_TIMEOUT + IMAGE_PROBE_TIMEOUT) ≈ 325s.
#
# STILL UNBOUNDED, each for a stated reason (so this comment is not read as "everything in the
# window is bounded"):
# (Line cites below were re-derived after this story's own edits shifted them — code review.)
#   • `aws s3 cp` (:201) — a hard wall-clock `timeout` on a DR-archive download risks killing a
#     slow-but-progressing restore; `--cli-*-timeout` bound only per-request stalls, not total
#     wall time, so they would NOT make it "bounded" (skillars-deferred-109 AC11, finding 7e).
#   • the ERR-trap `${DC} up -d` (:195) and the final `${DC} up -d` (:251) — under `set -euo
#     pipefail` a `timeout` exit 124 *inside the ERR trap* would exit the script with the stack
#     half-started (the opposite of the trap's purpose), and `timeout` kills the compose CLI, not
#     the daemon's orchestration. The ERR trap IS the recovery path for the final `up -d`.
#   • `${DC} config` (:111, first call in image_runtime_uid) — a local compose-file parse, already
#     `|| return 0`, not a daemon round-trip. `${DC} down` (:188) — bounding it carries the same
#     mid-teardown-timeout hazard as the ERR trap.
#
# 30s / 10s, NOT provision.sh's 300s / 10s -- IMAGE_PULL_TIMEOUT is deliberately different and must
# not be copied between the scripts (skillars-deferred-108 code review, decision 3;
# skillars-deferred-109 AC11 extends the rule to IMAGE_INSPECT_TIMEOUT). Here the probe runs inside
# a live outage window, so giving up fast and using the hardcoded constant is the correct trade; in
# provision.sh nothing is down and a cold pull deserves room to finish.
readonly IMAGE_PULL_TIMEOUT=30    # a reachable registry answers well inside this; a dead one is what we bound
readonly IMAGE_PROBE_TIMEOUT=15   # local `docker run … id -u` is fast unless the daemon is wedged
readonly IMAGE_INSPECT_TIMEOUT=10 # local metadata read; only a wedged dockerd makes it hang (skillars-deferred-109 AC11)
readonly IMAGE_TIMEOUT_KILL_AFTER=5  # SIGTERM alone does not bound a client wedged on the docker socket

# skillars-deferred-108 code review: `timeout` is new to this script. A missing coreutils exits 127,
# which `|| return 0` cannot tell from a failed pull — silently disabling the probe on every restore.
# Degrade to unwrapped docker so that costs us the bound, not the feature.
#
# Exit 124 / 137 is WARNed: a timeout otherwise reaches the same silent chown_probed arm as a
# confirmed constant, and during a restore that difference matters. stderr, not stdout -- this runs
# inside `uid=$(...)`.
# skillars-deferred-109 AC11: signature is run_bounded <dur> <label> <cmd…> — the label is now an
# explicit arg (previously it read $1 AFTER shift, so every wrapped call was labelled 'docker').
# The wrapped command's OWN stderr is swallowed inside run_bounded (bound to the `timeout`
# invocation), so run_bounded's timeout WARN — emitted afterwards, to fd2 — survives even when the
# caller redirects stdout. Callers that also discard stdout add `>/dev/null` on the run_bounded
# call; callers capturing stdout ($(...)) leave it alone. (finding 7c: do NOT put `2>&1` on the
# run_bounded call itself — that buries the WARN.)
if command -v timeout >/dev/null 2>&1; then
  run_bounded() {
    local dur="$1" what="$2"; shift 2
    local rc=0
    timeout -k "${IMAGE_TIMEOUT_KILL_AFTER}s" "$dur" "$@" 2>/dev/null || rc=$?
    if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
      # Accurate as written: every timeout now ends the probe — the first `docker image inspect`
      # no longer falls through to a pull on 124/137 (code review). Absence of this line still means
      # "the probe did not time out", never "the probe did not run".
      log "⚠️  image probe: '${what}' exceeded ${dur} — using the hardcoded uid:gid fallback for this service" >&2
    fi
    return "$rc"
  }
else
  log "⚠️  timeout(1) not found — image-probe docker calls will run unbounded"
  run_bounded() { shift 2; "$@" 2>/dev/null; }
fi

# skillars-deferred-107 AC8 (+ code review): echo the numeric uid the given compose service's image
# declares (its Config.User), or "" on any failure — same probe as provision.sh's image_runtime_uid.
# `docker inspect .Config.User`, not `docker run … id -u`: images that drop privileges in their
# entrypoint (redis: root → gosu 999) report the pre-drop uid from `id -u`. Names are resolved
# inside the image; "" / 0 / unresolvable all yield "".
image_runtime_uid() {
  local svc="$1" img user uid
  img=$(${DC} config --format json 2>/dev/null \
        | jq -r --arg s "$svc" '.services[$s].image // empty' 2>/dev/null) || return 0
  [ -n "${img:-}" ] || return 0
  # skillars-deferred-108 AC8 + skillars-deferred-109 AC11: bound every daemon round-trip here —
  # both `docker image inspect` calls (a wedged dockerd hangs on the first one, which runs BEFORE
  # the pull), the `docker pull`, and the `docker run … id -u`.
  # skillars-deferred-109 code review: branch on WHY the first inspect failed. run_bounded returns
  # the raw rc, so 124/137 (timed out) previously reached the same `|| docker pull` arm as docker's
  # own exit 1 ("No such image") — i.e. a slow-but-alive daemon, whose images are almost certainly
  # already local, triggered a full registry re-pull inside the outage window this file's header
  # exists to keep short. A timeout is not evidence the image is missing, so it ends the probe and
  # takes the hardcoded fallback instead; only a genuine "not present" falls through to the pull.
  local irc=0
  run_bounded "${IMAGE_INSPECT_TIMEOUT}s" "docker image inspect ${img}" docker image inspect "$img" >/dev/null || irc=$?
  if [ "$irc" -eq 124 ] || [ "$irc" -eq 137 ]; then
    return 0
  elif [ "$irc" -ne 0 ]; then
    run_bounded "${IMAGE_PULL_TIMEOUT}s" "docker pull ${img}" docker pull "$img" >/dev/null || return 0
  fi
  user=$(run_bounded "${IMAGE_INSPECT_TIMEOUT}s" "docker image inspect --format ${img}" \
         docker image inspect --format '{{.Config.User}}' "$img") || return 0
  user="${user%%:*}"
  [ -n "$user" ] || return 0
  case "$user" in
    ''|*[!0-9]*) uid=$(run_bounded "${IMAGE_PROBE_TIMEOUT}s" "docker run id -u ${img}" \
                       docker run --rm --entrypoint sh "$img" -c "id -u '$user'") || return 0 ;;
    *) uid="$user" ;;
  esac
  case "${uid:-}" in ''|*[!0-9]*|0) return 0 ;; esac
  printf '%s' "$uid"
}

# chown -R $dir to the probed image uid, keeping the hardcoded $3's GID pinned (the data dir group is
# deliberate and independent of the image's declared group — redis wants gid 1000 while its process
# is gid 999; grafana's image declares gid 0). Falls back to $3 silently when the probe can't
# determine a non-root uid (normal for redis / root-group images); WARNs only on a real drift — a
# probed non-zero uid that differs from the constant. skillars-deferred-107 AC8 + code review.
chown_probed() {
  local svc="$1" dir="$2" fallback="$3"
  local fb_uid="${fallback%%:*}" fb_gid="${fallback##*:}" probed_uid
  probed_uid="$(image_runtime_uid "$svc")"
  if [ -z "$probed_uid" ]; then
    chown -R "$fallback" "$dir"
  elif [ "$probed_uid" != "$fb_uid" ]; then
    log "⚠️  ${svc}: image now runs as uid ${probed_uid}, hardcoded constant is ${fb_uid} — using ${probed_uid}:${fb_gid} for ${dir}; update the constant in provision.sh and restore-from-volume-backup.sh"
    chown -R "${probed_uid}:${fb_gid}" "$dir"
  else
    chown -R "$fallback" "$dir"
  fi
}
PREFIX="${HOS_VOLUME_BACKUP_PREFIX:-volume-backups/}"
PREFIX="${PREFIX%/}/"
KEY="${1:-}"   # optional: exact object key to restore; default = most recently modified
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)

# The non-postgres top-level directories volume-backup.sh archives — same set the ownership
# restoration below applies to. postgres/ is never touched by this script.
VOLUME_SUBDIRS="redis prometheus loki tempo grafana traefik"

echo "This will OVERWRITE non-postgres data under ${DATA_DIR}. Press ENTER to continue, Ctrl+C to abort."
read -r _

if [ -z "$KEY" ]; then
  # Selected by the embedded skillars-volume-<stamp>.tar.gz filename timestamp (lexical sort ==
  # chronological order for this fixed-width format), NOT by S3 LastModified — consistent with
  # prune_volume_backups() in prune-backups.sh, which deliberately distrusts object mtime.
  keys=$(AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
    aws s3api list-objects-v2 --bucket "${HOS_BUCKET}" --prefix "${PREFIX}" \
    --endpoint-url "${HOS_ENDPOINT}" --query 'Contents[].Key' --output text)
  KEY=$(printf '%s' "$keys" | tr '\t' '\n' | sed '/^$/d' | sort | tail -n1)
fi
if [ -z "$KEY" ] || [ "$KEY" = "None" ]; then
  err "no volume backup found under ${PREFIX}"
  exit 1
fi

log "Restoring ${KEY}..."
${DC} down

# From here on, any failure must not leave services stopped indefinitely — restart with
# whatever data is currently on disk (pre-restore, or partially restored) rather than leaving an
# incident silent.
restore_failed() {
  err "restore step failed — restarting services with the data currently on disk so the app does not stay down"
  ${DC} up -d
}
trap restore_failed ERR

ARCHIVE_FILE="/tmp/$(basename "$KEY")"
AWS_ACCESS_KEY_ID="${HOS_ACCESS_KEY}" AWS_SECRET_ACCESS_KEY="${HOS_SECRET_KEY}" \
  aws s3 cp "s3://${HOS_BUCKET}/${KEY}" "${ARCHIVE_FILE}" --endpoint-url "${HOS_ENDPOINT}"

# Pre-restore safety net: move the directories this restore is about to overwrite aside instead
# of extracting straight over them, so a bad archive (wrong key, corrupt file) can be manually
# rolled back. postgres/ is never moved — it is not in the archive and must stay untouched.
PRERESTORE_DIR="${DATA_DIR}.pre-restore-${TIMESTAMP}"
mkdir -p "${PRERESTORE_DIR}"
for d in $VOLUME_SUBDIRS; do
  if [ -d "${DATA_DIR}/${d}" ]; then
    mv "${DATA_DIR}/${d}" "${PRERESTORE_DIR}/${d}"
  fi
done
log "Pre-restore data moved aside to ${PRERESTORE_DIR} — delete manually once the restore is confirmed good."

tar -xzf "${ARCHIVE_FILE}" -C "${DATA_DIR}"
rm -f "${ARCHIVE_FILE}"

# Ownership restoration — same values provision.sh sections 7/7.5 set on first provisioning.
# restore-from-snapshot.sh (the script this replaces) omitted redis/traefik; do not repeat that.
# Only directories that actually extracted from this archive are fixed up — an archive taken
# before a service was added to VOLUME_SUBDIRS, or a service that was never provisioned, is a
# legitimate absence, not a failure.
# skillars-deferred-107 AC8: the numeric uid:gid below are FALLBACKS — chown_probed reads the
# runtime uid from each service's image first (image_runtime_uid) and only uses these if the probe
# can't determine a non-root uid. The GID is always the pinned constant.
for d in $VOLUME_SUBDIRS; do
  if [ ! -d "${DATA_DIR}/${d}" ]; then
    log "skipping ownership fix for ${d} — not present in this archive"
    continue
  fi
  case "$d" in
    redis)      chown_probed redis      "${DATA_DIR}/${d}" 999:1000 ;;
    prometheus) chown_probed prometheus "${DATA_DIR}/${d}" 65534:65534 ;;
    loki)       chown_probed loki       "${DATA_DIR}/${d}" 10001:10001 ;;
    tempo)      chown_probed tempo      "${DATA_DIR}/${d}" 10001:10001 ;;
    grafana)    chown_probed grafana    "${DATA_DIR}/${d}" 472:472 ;;
    traefik)
      chmod 700 "${DATA_DIR}/${d}"
      if [ -f "${DATA_DIR}/${d}/acme.json" ]; then
        chmod 600 "${DATA_DIR}/${d}/acme.json"
      else
        log "skipping ownership fix for ${d}/acme.json — not present in this archive"
      fi
      ;;
    *)
      log "no ownership fix defined for ${d} — skipping (VOLUME_SUBDIRS added a subdir with no matching case arm)"
      ;;
  esac
done

${DC} up -d
trap - ERR

APP_CID=$(${DC} ps -q app 2>/dev/null | head -1)
log "Waiting for app health (up to 120s)..."
DEADLINE=$(($(date +%s) + 120))
until [ "$(docker inspect --format '{{.State.Health.Status}}' "${APP_CID}" 2>/dev/null)" = "healthy" ]; do
  if [ "$(date +%s)" -ge "${DEADLINE}" ]; then
    err "App did not become healthy within 120s."
    err "Check logs: ${DC} logs app --tail=50"
    exit 1
  fi
  sleep 5
done

log "Restore complete. App is healthy. Postgres was NOT restored by this script — use restore-from-dump.sh separately if needed. $(date -u)"
