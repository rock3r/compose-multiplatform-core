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
SKIKO_PICTURE_MARKER="SKIKO_JBR_INTEROP_PICTURE_FRAME"
JBR_PICTURE_MARKER="JBR_SKIA_INTEROP_PICTURE_FRAME"
SCREENSHOT_COUNTS_MARKER="JBR_SKIA_SCREENSHOT_COUNTS"

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
  SKIKO_VERSION            Optional Skiko version override, for example 0.0.0-SNAPSHOT.
  NEW_JVM_ARGS             Optional JVM args passed to runSwingJbrSkiaInterop through -PjbrSkiaInteropJvmArgs.
  CAPTURE_WINDOW_QUERY     Optional window title/owner to capture during new mode.
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
  local screenshot="${OUT_DIR}/${mode}-window.png"
  local screenshot_assertion="${OUT_DIR}/${mode}-screenshot-assertion.log"

  printf 'timestamp,mode,pid,cpu_percent,rss_kb\n' > "${csv}"

  if [[ "${DRY_RUN:-false}" == "true" ]]; then
    echo "Would run ${GRADLE} --no-daemon ${task}" > "${log}"
    return
  fi

  (
    cd "${ROOT_DIR}"
    if [[ "${mode}" == "new" && -n "${NEW_JVM_ARGS:-}" ]]; then
      "${GRADLE}" --no-daemon "${task}" "-PjbrSkiaInteropJvmArgs=${NEW_JVM_ARGS}"
    else
      "${GRADLE}" --no-daemon "${task}"
    fi
  ) > "${log}" 2>&1 &

  local root_pid="$!"
  local end_time=$(( $(date +%s) + DURATION_SECONDS ))
  local screenshot_done=false

  set +e
  while kill -0 "${root_pid}" 2>/dev/null && [[ "$(date +%s)" -lt "${end_time}" ]]; do
    sample_process_tree "${mode}" "${root_pid}" "${csv}"
    if [[ "${mode}" == "new"
        && "${screenshot_done}" == "false"
        && -n "${CAPTURE_WINDOW_QUERY:-}"
        && $(grep -c "${SKIKO_PICTURE_MARKER}" "${log}" 2>/dev/null) -gt 0 ]]; then
      if "${SCRIPT_DIR}/capture-macos-window.sh" "${CAPTURE_WINDOW_QUERY}" "${screenshot}" > "${OUT_DIR}/${mode}-capture.log" 2>&1; then
        if "${SCRIPT_DIR}/assert-jbr-skia-window-screenshot.sh" "${screenshot}" > "${screenshot_assertion}" 2>&1; then
          screenshot_done=true
        fi
      fi
    fi
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

picture_marker_summary() {
  local marker="$1"
  local log="$2"
  awk -v marker="${marker}" '
    index($0, marker) {
      frames++
      for (i = 1; i <= NF; i++) {
        if ($i ~ /^bytes=/) {
          split($i, value, "=")
          bytes += value[2]
          if (value[2] > maxBytes) maxBytes = value[2]
        }
      }
    }
    END {
      if (frames == 0) {
        printf "frames=0 avg_bytes=0 max_bytes=0"
      } else {
        printf "frames=%d avg_bytes=%.0f max_bytes=%.0f", frames, bytes / frames, maxBytes
      }
    }
  ' "${log}"
}

write_report() {
  local report="${OUT_DIR}/report.md"
  local old_summary
  local new_summary
  local old_markers
  local new_markers
  local skiko_picture_summary
  local jbr_picture_summary
  local screenshot_counts

  old_summary="$(summarize_csv "${OUT_DIR}/old-ps.csv")"
  new_summary="$(summarize_csv "${OUT_DIR}/new-ps.csv")"
  old_markers="$(grep -c "${FALLBACK_MARKER}" "${OUT_DIR}/old.log" 2>/dev/null || true)"
  new_markers="$(grep -c "${FALLBACK_MARKER}" "${OUT_DIR}/new.log" 2>/dev/null || true)"
  skiko_picture_summary="$(picture_marker_summary "${SKIKO_PICTURE_MARKER}" "${OUT_DIR}/new.log")"
  jbr_picture_summary="$(picture_marker_summary "${JBR_PICTURE_MARKER}" "${OUT_DIR}/new.log")"
  screenshot_counts="$(grep "${SCREENSHOT_COUNTS_MARKER}" "${OUT_DIR}/new-screenshot-assertion.log" 2>/dev/null || true)"

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
    echo "## Picture Replay Markers"
    echo
    echo "- Skiko picture frames: ${skiko_picture_summary}"
    echo "- JBR picture replays: ${jbr_picture_summary}"
    echo
    echo "## Screenshot Assertion"
    echo
    if [[ -n "${screenshot_counts}" ]]; then
      echo "- ${screenshot_counts}"
      echo "- screenshot: new-window.png"
      echo "- assertion log: new-screenshot-assertion.log"
    else
      echo "- not run"
    fi
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
    echo "Picture marker counts come from structured Skiko/JBR logs. They are the primary signal that the JBR-owned replay path was used."
    echo "The serialized picture byte counts are expected to be high in this probe and should be treated as a performance risk."
  } > "${report}"

  echo "${report}"
}

if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=true
fi

run_mode old "${TASK_PREFIX}:runSwing"
run_mode new "${TASK_PREFIX}:runSwingJbrSkiaInterop"
write_report
