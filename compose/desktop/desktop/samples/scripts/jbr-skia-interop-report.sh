#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
ROOT_DIR="${ROOT_DIR:-$(cd -- "${SCRIPT_DIR}/../../../../.." >/dev/null && pwd)}"
OUT_DIR="${OUT_DIR:-${ROOT_DIR}/out/jbr-skia-interop-report/$(date +%Y%m%d-%H%M%S)}"
DURATION_SECONDS="${DURATION_SECONDS:-20}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-1}"
GRADLE="${GRADLE:-${ROOT_DIR}/gradlew}"
TASK_PREFIX=":compose:desktop:desktop:desktop-samples"
FALLBACK_MARKER="SKIKO_JBR_INTEROP_FALLBACK"

mkdir -p "${OUT_DIR}"

usage() {
  cat <<EOF
Usage: $0 [--dry-run]

Runs the Compose Desktop Swing sample in old and JBR-Skia-interoperability modes,
captures coarse process CPU/RSS samples, parses fallback markers, and writes a
Markdown report.

Environment:
  ROOT_DIR                 Repository root. Defaults to auto-detected CMP root.
  OUT_DIR                  Report directory. Defaults under out/jbr-skia-interop-report/.
  DURATION_SECONDS         Seconds to keep each sample run alive. Default: 20.
  SAMPLE_INTERVAL_SECONDS  Seconds between ps samples. Default: 1.
  GRADLE                   Gradle executable. Defaults to ROOT_DIR/gradlew.
  JAVA_HOME                Optional local JBR to use for both sample modes.
EOF
}

if [[ "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

descendants_of() {
  local parent="$1"
  local child
  pgrep -P "${parent}" 2>/dev/null | while read -r child; do
    echo "${child}"
    descendants_of "${child}"
  done
}

process_tree() {
  local root_pid="$1"
  {
    echo "${root_pid}"
    descendants_of "${root_pid}"
  } | sort -u
}

kill_process_tree() {
  local root_pid="$1"
  local pids
  pids="$(process_tree "${root_pid}" | tr '\n' ' ')"
  if [[ -n "${pids// /}" ]]; then
    kill ${pids} 2>/dev/null || true
  fi
}

sample_process_tree() {
  local mode="$1"
  local root_pid="$2"
  local csv="$3"
  local timestamp
  local pids

  timestamp="$(date +%s)"
  pids="$(process_tree "${root_pid}" | tr '\n' ',' | sed 's/,$//')"
  if [[ -n "${pids}" ]]; then
    ps -o pid= -o pcpu= -o rss= -p "${pids}" 2>/dev/null | while read -r pid cpu rss; do
      [[ -n "${pid:-}" ]] || continue
      printf '%s,%s,%s,%s,%s\n' "${timestamp}" "${mode}" "${pid}" "${cpu}" "${rss}" >> "${csv}"
    done
  fi
}

run_mode() {
  local mode="$1"
  local task="$2"
  local log="${OUT_DIR}/${mode}.log"
  local csv="${OUT_DIR}/${mode}-ps.csv"

  printf 'timestamp,mode,pid,cpu_percent,rss_kb\n' > "${csv}"

  if [[ "${DRY_RUN:-false}" == "true" ]]; then
    echo "Would run ${GRADLE} --no-daemon ${task}" > "${log}"
    return
  fi

  (
    cd "${ROOT_DIR}"
    "${GRADLE}" --no-daemon "${task}"
  ) > "${log}" 2>&1 &

  local root_pid="$!"
  local end_time=$(( $(date +%s) + DURATION_SECONDS ))

  set +e
  while kill -0 "${root_pid}" 2>/dev/null && [[ "$(date +%s)" -lt "${end_time}" ]]; do
    sample_process_tree "${mode}" "${root_pid}" "${csv}"
    sleep "${SAMPLE_INTERVAL_SECONDS}"
  done

  kill_process_tree "${root_pid}"
  wait "${root_pid}" >/dev/null 2>&1
  set -e
}

summarize_csv() {
  local csv="$1"
  awk -F, '
    NR > 1 {
      cpu += $4
      rss += $5
      if ($4 > maxCpu) maxCpu = $4
      if ($5 > maxRss) maxRss = $5
      count++
    }
    END {
      if (count == 0) {
        printf "samples=0 avg_cpu=0 max_cpu=0 avg_rss_kb=0 max_rss_kb=0"
      } else {
        printf "samples=%d avg_cpu=%.2f max_cpu=%.2f avg_rss_kb=%.0f max_rss_kb=%.0f", count, cpu / count, maxCpu, rss / count, maxRss
      }
    }
  ' "${csv}"
}

write_report() {
  local report="${OUT_DIR}/report.md"
  local old_summary
  local new_summary
  local old_markers
  local new_markers

  old_summary="$(summarize_csv "${OUT_DIR}/old-ps.csv")"
  new_summary="$(summarize_csv "${OUT_DIR}/new-ps.csv")"
  old_markers="$(grep -c "${FALLBACK_MARKER}" "${OUT_DIR}/old.log" 2>/dev/null || true)"
  new_markers="$(grep -c "${FALLBACK_MARKER}" "${OUT_DIR}/new.log" 2>/dev/null || true)"

  {
    echo "# JBR Skia Interop Sample Report"
    echo
    echo "- Generated: $(date -Iseconds)"
    echo "- Duration per mode: ${DURATION_SECONDS}s"
    echo "- Root: ${ROOT_DIR}"
    echo "- JAVA_HOME: ${JAVA_HOME:-<default>}"
    echo
    echo "## Modes"
    echo
    echo "- old: ${TASK_PREFIX}:runSwing"
    echo "- new: ${TASK_PREFIX}:runSwingJbrSkiaInterop"
    echo
    echo "## Process Samples"
    echo
    echo "- old: ${old_summary}"
    echo "- new: ${new_summary}"
    echo
    echo "## Fallback Markers"
    echo
    echo "- old marker count: ${old_markers}"
    echo "- new marker count: ${new_markers}"
    echo
    echo "## Files"
    echo
    echo "- old log: old.log"
    echo "- new log: new.log"
    echo "- old ps samples: old-ps.csv"
    echo "- new ps samples: new-ps.csv"
    echo
    echo "## Notes"
    echo
    echo "CPU and RSS samples are coarse process-tree samples from ps. They are useful as a smoke signal only."
    echo "Until the native JBR Skia/Metal scope exists, zero-copy frame counts are unavailable and the expected new-mode result is a structured fallback marker."
  } > "${report}"

  echo "${report}"
}

if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
fi

run_mode old "${TASK_PREFIX}:runSwing"
run_mode new "${TASK_PREFIX}:runSwingJbrSkiaInterop"
write_report
