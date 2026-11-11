#!/usr/bin/env bash
# Raw daemon/JVM crash logs may contain the process environment. Never upload them.
set -euo pipefail
mkdir -p build/ci-diagnostics
{
  date -u
  free -h
  nproc
  df -h
  for f in /sys/fs/cgroup/memory.events /sys/fs/cgroup/memory.peak /sys/fs/cgroup/memory.max; do
    if [[ -r "$f" ]]; then printf '%s\n' "$f"; cat "$f"; fi
  done
  ps -eo pid,comm,rss --sort=-rss | head -20 || true
} > build/ci-diagnostics/resources-final.txt
node scripts/ci-diagnostics.mjs
