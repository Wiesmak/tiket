#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
npm ci --prefix scripts
exec node scripts/test-kind.mjs "$@"
