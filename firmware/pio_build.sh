#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

echo "Running PlatformIO build..."
pio run

echo "Build finished. To upload run: pio run -t upload"
