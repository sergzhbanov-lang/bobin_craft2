#!/bin/sh
# Headless-проверки (чистая Java, Minecraft не нужен). Нужен JDK 17+.
# Запуск из корня проекта:
#   sh tools/headless/run.sh [число_сидов]         — чертежи и мосты
#   sh tools/headless/run.sh poi [число_сидов]     — базы, вышки, укрытия, блокпосты и поля боёв
#   sh tools/headless/run.sh flora [число_сидов]   — деревья/кусты на реальном рельефе
set -e
OUT="${TMPDIR:-/tmp}/bobin_headless"
rm -rf "$OUT"; mkdir -p "$OUT"
W=src/main/java/com/bobincraft/warzone
CORE="$W/Faction.java $W/WarzoneNoise.java $W/WarzoneTerrain.java $W/WarzoneBasePlan.java $W/WarzoneFlora.java \
  $W/BpSpec.java $W/BpPalette.java $W/BpBuilder.java $W/SiteDesign.java $W/SiteModules.java $W/SiteBuilder.java $W/HideoutPlan.java $W/HideoutBuilder.java $W/PoiPlan.java $W/CheckpointBuilder.java $W/BattlefieldBuilder.java $W/Blueprint.java $W/BridgeBlueprint.java $W/BridgeBuilder.java $W/WarzoneBlueprints.java"
if [ "$1" = "poi" ]; then
  shift
  ${JAVAC:-javac} -d "$OUT" $CORE tools/headless/PoiCheck.java
  java -cp "$OUT" PoiCheck "$@"
elif [ "$1" = "flora" ]; then
  shift
  ${JAVAC:-javac} -d "$OUT" $CORE tools/headless/com/bobincraft/warzone/FloraProbe.java tools/headless/FloraCheck.java
  java -cp "$OUT" FloraCheck "$@"
else
  ${JAVAC:-javac} -d "$OUT" $CORE tools/headless/BlueprintCheck.java
  java -cp "$OUT" BlueprintCheck "$@"
fi
