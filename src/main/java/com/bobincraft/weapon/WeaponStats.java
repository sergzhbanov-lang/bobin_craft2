package com.bobincraft.weapon;

/** Shared, server-safe stats for the two original firearms. Special weapons use GunType. */
public final class WeaponStats {
    public static final int TICKS_PER_SECOND = 20;
    public static final int AK_MAGAZINE = 30;
    public static final int AK_RELOAD_TICKS = 60;
    public static final int AK_INTERVAL_TICKS = 4;
    public static final double AK_RANGE = 28.0;
    public static final float AK_DAMAGE = 3.0F;
    // One block represents one metre; convert muzzle velocity to blocks per game tick once.
    public static final double AK_BULLET_SPEED = 715.0 / TICKS_PER_SECOND;
    public static final int SNIPER_MAGAZINE = 5;
    public static final int SNIPER_RELOAD_TICKS = 70;
    public static final int SNIPER_INTERVAL_TICKS = 40;
    public static final double SNIPER_RANGE = 120.0;
    public static final float SNIPER_DAMAGE = 50.0F;
    public static final double SNIPER_BULLET_SPEED = 850.0 / TICKS_PER_SECOND;
    public static final int SNIPER_AIM_TICKS = 20;
    private WeaponStats() {}
}
