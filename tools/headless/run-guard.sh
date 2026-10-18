#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
test_output="$(mktemp -d "${TMPDIR:-/tmp}/bobin-guard.XXXXXX")"
trap 'rm -rf -- "$test_output"' EXIT

"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 17 -encoding UTF-8 -d "$test_output" \
    "$repo_root/src/main/java/com/bobincraft/raid/GunfireThrottle.java" \
    "$repo_root/src/main/java/com/bobincraft/raid/guard/GuardBrain.java" \
    "$repo_root/src/main/java/com/bobincraft/raid/guard/NpcFireControl.java" \
    "$repo_root/src/test/java/com/bobincraft/raid/guard/NpcFireControlTest.java" \
    "$repo_root/src/test/java/com/bobincraft/raid/guard/GuardBrainTest.java"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$test_output" com.bobincraft.raid.guard.GuardBrainTest

"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$test_output" com.bobincraft.raid.guard.NpcFireControlTest
