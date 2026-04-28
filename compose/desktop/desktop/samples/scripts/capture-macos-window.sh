#!/usr/bin/env bash
set -euo pipefail

WINDOW_QUERY="${1:-SwingComposeWindow}"
OUTPUT="${2:-/tmp/jbr-skia-window.png}"

WINDOW_ID="$(/usr/bin/swift - "${WINDOW_QUERY}" <<'SWIFT'
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
        if let id = window[kCGWindowNumber as String] {
            print(id)
            exit(0)
        }
    }
}

fputs("No captureable window matched '\(query)'\n", stderr)
exit(1)
SWIFT
)"

screencapture -x -l"${WINDOW_ID}" "${OUTPUT}"
echo "${OUTPUT}"
