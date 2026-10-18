package com.bobincraft.weapon;

import net.minecraft.item.Item;

/**
 * Новое оружие с той же механикой стрельбы (лазерный прицел, разброс, физические пули, патроны/перезарядка),
 * но у каждого — своя уникальная роль. Параметры задаются здесь, логика эффектов — в {@link GunServer}.
 */
public enum GunType {
    //        id              ammo reload interval auto   range  minSp maxSp move  shot  recover delay pellets speed dmg   color
    BREACHER("breacher",       6,   70,   900L,    false, 14.0,  12f,  12f,  0.0f, 0.0f, 0.0f,  0,    7,      400.0 / WeaponStats.TICKS_PER_SECOND, 2.5f, 0xFF9A2E,
            "§6[Взломщик] §f",
            "§7Дробовик: 7 картечин в конусе 12°, урон падает с расстоянием.",
            "§bОсобенность: §fвыбивает двери, люки, калитки и стёкла (до 10 блоков) — вход в любое здание."),
    SPOTTER("spotter",         8,   45,   450L,    false, 40.0,  0f,   8f,   0.30f, 2.0f, 0.20f, 10,   1,      330.0 / WeaponStats.TICKS_PER_SECOND, 1.5f, 0xB070FF,
            "§d[Разведчик] §f",
            "§7Тихий пистолет-маркер, быстрая пуля.",
            "§bОсобенность: §fпопадание метит цель на 10 с — свечение сквозь стены и +30% урона от любого оружия Bobin_craft."),
    CRYO("cryo_carbine",      20,   55,   250L,    true,  22.0,  1f,   9f,   0.30f, 1.0f, 0.15f, 15,   1,      240.0 / WeaponStats.TICKS_PER_SECOND, 2.0f, 0x5CE1FF,
            "§b[Крио] §f",
            "§7Автоматический карабин с хладагентом.",
            "§bОсобенность: §fкаждое попадание копит холод (замедление до IV). 5-е попадание замораживает цель на 1.5 с, замороженные получают +50% урона."),
    MEDIC("medic_gun",        12,   50,   500L,    false, 26.0,  0f,   6f,   0.25f, 1.5f, 0.15f, 10,   1,      120.0 / WeaponStats.TICKS_PER_SECOND, 1.0f, 0x5CFF7A,
            "§a[Медик] §f",
            "§7Дротики с лечебной сывороткой.",
            "§bОсобенность: §fлечит (+4 HP) и очищает яд/замедление у питомцев, жителей, големов и других игроков. Враги получают лишь 1 урона.");

    public final String id;
    public final int maxAmmo;
    public final int reloadTicks;
    public final long intervalMs;
    public final boolean automatic;
    public final double range;
    public final float minSpread;
    public final float maxSpread;
    public final float moveGain;      // рост разброса за тик при ходьбе
    public final float shotGain;      // рост разброса за выстрел
    public final float recoverPerTick;
    public final int recoverDelay;
    public final int pellets;
    public final double bulletSpeed;  // блоков за тик
    public final float damage;
    public final int color;           // 0xRRGGBB — цвет лазера и пули
    public final String hudTag;
    public final String[] tooltip;

    /** Заполняется при регистрации предметов в {@link com.bobincraft.BobinCraft#onInitialize()}. */
    public Item item;

    public int intervalTicks() {
        return Math.max(1, (int) Math.ceil(intervalMs * WeaponStats.TICKS_PER_SECOND / 1000.0));
    }

    GunType(String id, int maxAmmo, int reloadTicks, long intervalMs, boolean automatic, double range,
            float minSpread, float maxSpread, float moveGain, float shotGain, float recoverPerTick, int recoverDelay,
            int pellets, double bulletSpeed, float damage, int color, String hudTag, String... tooltip) {
        this.id = id;
        this.maxAmmo = maxAmmo;
        this.reloadTicks = reloadTicks;
        this.intervalMs = intervalMs;
        this.automatic = automatic;
        this.range = range;
        this.minSpread = minSpread;
        this.maxSpread = maxSpread;
        this.moveGain = moveGain;
        this.shotGain = shotGain;
        this.recoverPerTick = recoverPerTick;
        this.recoverDelay = recoverDelay;
        this.pellets = pellets;
        this.bulletSpeed = bulletSpeed;
        this.damage = damage;
        this.color = color;
        this.hudTag = hudTag;
        this.tooltip = tooltip;
    }
}
