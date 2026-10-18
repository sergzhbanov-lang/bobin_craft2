#!/usr/bin/env python3
"""Check bundled faction coverage, registered adapter names and balancing bounds."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'src/main/java/com/bobincraft/warzone/Faction.java').read_text()
factions = re.findall(r'^    ([A-Z_]+)\("', source, re.M)
profiles = json.loads((ROOT / 'src/main/resources/data/bobin_craft/warzone/faction_loadouts.json').read_text())
weapons = {'AK47', 'SNIPER_RIFLE', 'BREACHER', 'SPOTTER', 'CRYO', 'MEDIC'}
assert set(profiles) == set(factions), 'Profiles must cover exactly the existing factions'
for faction, profile in profiles.items():
    assert set(profile) == {'primary', 'sentry', 'elite', 'reactionTicks', 'spreadDegrees',
                            'burstSize', 'burstPauseTicks', 'reserveMagazines', 'detectionRange', 'armored'}
    for role in ('primary', 'sentry', 'elite'):
        assert profile[role] in weapons, (faction, role, 'Unknown firearm adapter')
    assert 10 <= profile['reactionTicks'] <= 60
    assert 2 <= profile['spreadDegrees'] <= 12
    assert 1 <= profile['burstSize'] <= 5
    assert 8 <= profile['burstPauseTicks'] <= 80
    assert 1 <= profile['reserveMagazines'] <= 5
    assert 20 <= profile['detectionRange'] <= 48
    assert isinstance(profile['armored'], bool)
    for variant in range(3):
        assert (ROOT / 'src/main/resources/assets/bobin_craft/textures/entity/faction' /
                f'{faction.lower()}_{variant}.png').is_file(), (faction, variant, 'Missing uniform')
print(f'Faction loadouts: {len(factions)} profiles, all firearm names and 21 texture paths valid')
