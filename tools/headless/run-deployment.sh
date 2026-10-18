#!/bin/sh
# Pure Java planner validation: sh tools/headless/run-deployment.sh [seeds-per-size]
set -eu
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d "${TMPDIR:-/tmp}/bobin-deployment.XXXXXX")
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
W=src/main/java/com/bobincraft/warzone
${JAVAC:-javac} -d "$OUT" \
  "$W/Faction.java" "$W/WarzoneNoise.java" "$W/WarzoneTerrain.java" "$W/WarzoneBasePlan.java" "$W/WarzoneFlora.java" \
  "$W/BpSpec.java" "$W/BpPalette.java" "$W/BpBuilder.java" "$W/SiteDesign.java" "$W/SiteModules.java" "$W/SiteBuilder.java" \
  "$W/HideoutPlan.java" "$W/HideoutBuilder.java" "$W/PoiPlan.java" "$W/CheckpointBuilder.java" "$W/BattlefieldBuilder.java" \
  "$W/Blueprint.java" "$W/BridgeBlueprint.java" "$W/BridgeBuilder.java" "$W/WarzoneBlueprints.java" \
  src/main/java/com/bobincraft/raid/DeploymentPlanner.java tools/headless/DeploymentCheck.java
java -Xmx768m -cp "$OUT" DeploymentCheck "$@"
