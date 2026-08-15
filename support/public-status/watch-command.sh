#!/bin/sh
set -eu
status_file=${QITS_STATUS_FILE:-/root/qits/bootstrap-public-status/status.json}
log_file=${QITS_STATUS_LOG:-/root/qits/bootstrap-public-status/command.log}
phase=${QITS_STATUS_PHASE:-Focused bootstrap verification}
command=$*
started=$(date -Iseconds)
"$@" >"$log_file" 2>&1 &
pid=$!
write_status() {
  state=$1
  heartbeat=$(date -Iseconds)
  detail=$(tail -n 1 "$log_file" 2>/dev/null | sed -E 's/([Pp]assword|[Ss]ecret|[Tt]oken)=[^[:space:]]+/\1=[redacted]/g' | tr '"' "'")
  printf '{"state":"%s","command":"%s","pid":"%s","started":"%s","heartbeat":"%s","phase":"%s","detail":"%s"}\n' \
    "$state" "$command" "$pid" "$started" "$heartbeat" "$phase" "${detail:-running}" >"$status_file"
}
while kill -0 "$pid" 2>/dev/null; do write_status RUNNING; sleep 10; done
wait "$pid" && write_status SUCCEEDED || write_status FAILED
