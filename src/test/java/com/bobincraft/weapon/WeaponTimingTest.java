package com.bobincraft.weapon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Also runs without a Minecraft client, so timing regressions can be checked headlessly. */
class WeaponTimingTest {
    @Test
    void fullRangeRifleFlightsAreRealTimeWithoutChangingReloadsOrCooldowns() {
        assertTrue(WeaponStats.AK_RANGE / (WeaponStats.AK_BULLET_SPEED * WeaponStats.TICKS_PER_SECOND) < 0.05);
        assertTrue(WeaponStats.SNIPER_RANGE / (WeaponStats.SNIPER_BULLET_SPEED * WeaponStats.TICKS_PER_SECOND) < 0.15);
        assertEquals(60, WeaponStats.AK_RELOAD_TICKS);
        assertEquals(70, WeaponStats.SNIPER_RELOAD_TICKS);
        assertEquals(4, WeaponStats.AK_INTERVAL_TICKS);
        assertEquals(40, WeaponStats.SNIPER_INTERVAL_TICKS);
        assertTrue(WeaponStats.SNIPER_AIM_TICKS <= WeaponStats.TICKS_PER_SECOND);
    }
}
