package com.bobincraft.raid;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Coalesces muzzle and pellet messages before expensive guard spatial queries. One instance per world. */
public final class GunfireThrottle {
    private final Map<UUID, Long> lastShot = new HashMap<>();
    private long nextCleanup;

    public boolean accept(UUID player, long gameTick) {
        Long previous = lastShot.get(player);
        if (previous != null && gameTick >= previous && gameTick - previous < 4) return false;
        lastShot.put(player, gameTick);
        if (gameTick >= nextCleanup) {
            lastShot.values().removeIf(tick -> gameTick - tick > 1200);
            nextCleanup = gameTick + 200;
        }
        return true;
    }
}
