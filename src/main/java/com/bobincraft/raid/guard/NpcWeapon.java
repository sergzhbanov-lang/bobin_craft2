package com.bobincraft.raid.guard;

import com.bobincraft.BobinCraft;
import com.bobincraft.weapon.BulletPenetration;
import com.bobincraft.weapon.GunType;
import com.bobincraft.weapon.WeaponStats;
import net.minecraft.item.Item;

/** Adapts registered Bobin firearms; special effects and damage stay in GunServer/ShotServer. */
public enum NpcWeapon {
    AK47(null), SNIPER_RIFLE(null), BREACHER(GunType.BREACHER), SPOTTER(GunType.SPOTTER),
    CRYO(GunType.CRYO), MEDIC(GunType.MEDIC);
    private final GunType special;
    NpcWeapon(GunType special) { this.special = special; }
    public GunType special() { return special; }
    public Item item() { return special != null ? special.item : this == AK47 ? BobinCraft.AK47 : BobinCraft.SNIPER_RIFLE; }
    public int magazineSize() { return special != null ? special.maxAmmo : this == AK47 ? WeaponStats.AK_MAGAZINE : WeaponStats.SNIPER_MAGAZINE; }
    public int reloadTicks() { return special != null ? special.reloadTicks : this == AK47 ? WeaponStats.AK_RELOAD_TICKS : WeaponStats.SNIPER_RELOAD_TICKS; }
    public int intervalTicks() { return special != null ? Math.max(1, (int) ((special.intervalMs + 49) / 50)) : this == AK47 ? WeaponStats.AK_INTERVAL_TICKS : WeaponStats.SNIPER_INTERVAL_TICKS; }
    public double range() { return special != null ? special.range : this == AK47 ? WeaponStats.AK_RANGE : WeaponStats.SNIPER_RANGE; }
    public int pellets() { return special != null ? special.pellets : 1; }
    public BulletPenetration.Caliber caliber() { return BulletPenetration.getCaliber(this == SNIPER_RIFLE, special); }
    public static NpcWeapon fromName(String name) {
        try { return valueOf(name); } catch (IllegalArgumentException ignored) { return AK47; }
    }
}
