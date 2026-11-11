#!/usr/bin/env bash
set -euo pipefail
image="${1:-tiket:kind}"
docker run --rm --read-only --user 1001234567:0 --tmpfs /tmp:rw,nosuid,nodev,size=64m --entrypoint java "$image" -version 2>&1 | tee build/java-version.log
grep -E 'version "25[."]' build/java-version.log
docker run --rm --read-only --user 1001234567:0 --tmpfs /tmp:rw,nosuid,nodev,size=64m --entrypoint sh "$image" -c 'test -w /tmp && test ! -w /opt/tiket/app.jar && touch /tmp/uid-test'
