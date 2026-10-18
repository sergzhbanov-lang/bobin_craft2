#!/usr/bin/env bash
# Run from any directory; preserve every suite's failure status.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
python3 tools/validate_resources.py
python3 tools/validate_faction_loadouts.py
python3 tools/generate_faction_textures.py --check
bash tools/headless/run-guard.sh
sh tools/headless/run.sh 3
sh tools/headless/run.sh poi 3
sh tools/headless/run.sh flora 3
sh tools/headless/run-deployment.sh "${1:-2}"
