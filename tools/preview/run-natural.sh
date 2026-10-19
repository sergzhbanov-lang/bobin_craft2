#!/bin/sh
# Procedural diagnostics only; no Minecraft installation or world generation required.
# Usage: sh tools/preview/run-natural.sh [output directory] [seed (42)]
set -eu
DESTINATION="${1:-${TMPDIR:-/tmp}/bobin-natural-preview}"
SEED="${2:-42}"
CLASSES=$(mktemp -d "${TMPDIR:-/tmp}/bobin-preview-classes.XXXXXX")
trap 'rm -rf "$CLASSES"' EXIT HUP INT TERM
mkdir -p "$DESTINATION"
"${JAVAC:-javac}" -d "$CLASSES" -sourcepath src/main/java \
  tools/preview/ExportNaturalTerrain.java tools/preview/ExportWaterwayStats.java
java -cp "$CLASSES" ExportNaturalTerrain "$DESTINATION/natural-terrain.csv" "$SEED" 4
java -cp "$CLASSES" ExportWaterwayStats "$DESTINATION/waterway-stats.json" 2
MPLCONFIGDIR="$CLASSES/matplotlib" XDG_CACHE_HOME="$CLASSES/cache" \
  python3 tools/preview/render_natural_terrain.py \
    "$DESTINATION/natural-terrain.csv" "$DESTINATION/natural-terrain.png"
echo "Preview: $DESTINATION/natural-terrain.png"
echo "Statistics: $DESTINATION/waterway-stats.json"
