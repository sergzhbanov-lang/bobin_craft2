#!/usr/bin/env bash
set -euo pipefail
smoke_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
smoke_project_dir="$(cd -- "$smoke_script_dir/../.." && pwd)"
smoke_output_dir="$smoke_project_dir/build/server-smoke"
smoke_classpath_file="${1:-$smoke_output_dir/classpath.txt}"
smoke_java_prefix="${JAVA_HOME:+$JAVA_HOME/bin/}"
mkdir -p "$smoke_output_dir/classes"
if [[ $# -eq 0 ]]; then
    "$smoke_project_dir/gradlew" -p "$smoke_project_dir" -I "$smoke_script_dir/classpath.init.gradle" bobinSmokeClasspath
fi
smoke_classpath="$(cat -- "$smoke_classpath_file")"
"${smoke_java_prefix}javac" -proc:none -encoding UTF-8 --release 17 -cp "$smoke_classpath" \
    -d "$smoke_output_dir/classes" "$smoke_script_dir"/src/com/bobincraft/smoke/*.java
cp "$smoke_script_dir/resources/fabric.mod.json" "$smoke_output_dir/classes/fabric.mod.json"
"${smoke_java_prefix}jar" --create --file "$smoke_output_dir/hub-smoke.jar" -C "$smoke_output_dir/classes" .
printf '%s\n' "$smoke_output_dir/hub-smoke.jar"
