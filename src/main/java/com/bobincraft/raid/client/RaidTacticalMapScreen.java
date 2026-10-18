package com.bobincraft.raid.client;

import com.bobincraft.raid.TacticalMapData;

/** Read-only field interface. Its packet contains no deployment options or session token. */
public final class RaidTacticalMapScreen extends TacticalMapScreen {
    public RaidTacticalMapScreen(TacticalMapData map) {
        super(map, true);
    }
}
