#!/usr/bin/env bash
set -euo pipefail

WINDOW_QUERY="${1:-SwingComposeWindow}"
OUTPUT="${2:-/tmp/jbr-skia-window.png}"

WINDOW_METADATA="$(/usr/bin/swift - "${WINDOW_QUERY}" <<'SWIFT'
import CoreGraphics
import Foundation

let query = CommandLine.arguments.dropFirst().joined(separator: " ")
let options = CGWindowListOption(arrayLiteral: .optionAll, .excludeDesktopElements)
let windows = CGWindowListCopyWindowInfo(options, kCGNullWindowID) as? [[String: Any]] ?? []

for window in windows {
    let owner = window[kCGWindowOwnerName as String] as? String ?? ""
    let name = window[kCGWindowName as String] as? String ?? ""
    let alpha = window[kCGWindowAlpha as String] as? Double ?? 0.0
    let layer = window[kCGWindowLayer as String] as? Int ?? 0
    if owner.contains(query) || name.contains(query) {
        if alpha <= 0.0 || layer != 0 {
            continue
        }
        if let id = window[kCGWindowNumber as String],
           let bounds = window[kCGWindowBounds as String] as? [String: Any] {
            let x = bounds["X"] ?? "unknown"
            let y = bounds["Y"] ?? "unknown"
            let width = bounds["Width"] ?? "unknown"
            let height = bounds["Height"] ?? "unknown"
            print("id=\(id) owner=\(owner) name=\(name) x=\(x) y=\(y) width=\(width) height=\(height)")
            exit(0)
        }
    }
}

fputs("No captureable window matched '\(query)'\n", stderr)
exit(1)
SWIFT
)"

echo "window=${WINDOW_METADATA}" >&2
WINDOW_ID="$(printf '%s\n' "${WINDOW_METADATA}" | sed -n 's/^id=\([^ ]*\).*/\1/p')"
screencapture -x -o -l"${WINDOW_ID}" "${OUTPUT}"
echo "${OUTPUT}"
