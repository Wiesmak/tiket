#!/usr/bin/env bash
# Shared compilation budget for validation and trusted publication; local defaults stay unchanged.
set -euo pipefail
cd "$(dirname "$0")/.."
printf '%s\n' 'CI Gradle: heap=1536m metaspace=1024m workers=1 Kotlin=in-process configuration-cache=off'
# The -D property configures the build JVM, not just the Gradle launcher.
# Keep each caller argument intact (notably -PreleaseVersion).
if [[ "${OS:-}" == Windows_NT ]]; then
  wrapper=./gradlew.bat
else
  wrapper=./gradlew
fi
exec "$wrapper" "$@" \
  --init-script scripts/ci-memory.init.gradle \
  --no-configuration-cache --max-workers=1 \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8' \
  --console=plain --stacktrace
