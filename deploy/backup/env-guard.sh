# shellcheck shell=bash
# Sourced by every deploy/backup/*.sh script — do not execute directly.
# SKILLARS_ENV_FILE may be overridden (e.g. by a test) to point at a throwaway file instead
# of the real /opt/skillars/.env; production callers never set it.
require_env_vars() {
  local tag="$1" action="$2"
  shift 2
  local env_file="${SKILLARS_ENV_FILE:-/opt/skillars/.env}"

  if [ -d "$env_file" ]; then
    echo "[${tag}][error] ${env_file} is a directory, not a file — cannot source credentials" >&2
    exit 1
  fi
  if [ ! -r "$env_file" ]; then
    echo "[${tag}][error] cannot read ${env_file} — ${action} cannot run without credentials" >&2
    exit 1
  fi
  # shellcheck source=/dev/null
  . "$env_file"

  local missing=() var
  for var in "$@"; do
    [ -z "${!var:-}" ] && missing+=("$var")
  done
  if [ "${#missing[@]}" -gt 0 ]; then
    echo "[${tag}][error] ${env_file} is missing required value(s): ${missing[*]}" >&2
    exit 1
  fi
}
