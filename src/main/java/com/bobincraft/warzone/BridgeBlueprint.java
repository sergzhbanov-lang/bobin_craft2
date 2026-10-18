package com.bobincraft.warzone;

import java.util.Set;

/** Готовый чертёж одного моста + данные для самопроверок и для зарезервированной под мост территории. */
public final class BridgeBlueprint {
    public final int index;
    public final Blueprint blueprint;
    public final boolean damaged;
    /** Длина настила по оси (в блоках). */
    public final int length;
    /** Колонки въезда/съезда, высота блока настила на них. */
    public final int anchorAX, anchorAZ, anchorBX, anchorBZ, deckYA, deckYB;
    /** Есть ли настил на отметке оси j (0..length) после повреждений. */
    /** Высота земли и блока настила ровно на оси в начале и конце моста (профиль стыкуется с дорогой). */
    public final int axisGroundA, axisGroundB, axisDeckYA, axisDeckYB;
    public final boolean[] arcPresent;
    /** Границы провала по оси (−1, если мост цел). */
    public final int gapStart, gapEnd;
    /** Обломки на воде (ключи {@link #key}); единственные блоки, которым не нужна опора. */
    public final Set<Long> debris;
    private final boolean[] reserved;
    private final int rMinX, rMinZ, rSizeX, rSizeZ;

    BridgeBlueprint(int index, Blueprint blueprint, boolean damaged, int length,
                    int anchorAX, int anchorAZ, int anchorBX, int anchorBZ, int deckYA, int deckYB,
                    int axisGroundA, int axisGroundB, int axisDeckYA, int axisDeckYB,
                    boolean[] arcPresent, int gapStart, int gapEnd, Set<Long> debris,
                    boolean[] reserved, int rMinX, int rMinZ, int rSizeX, int rSizeZ) {
        this.index = index;
        this.blueprint = blueprint;
        this.damaged = damaged;
        this.length = length;
        this.anchorAX = anchorAX;
        this.anchorAZ = anchorAZ;
        this.anchorBX = anchorBX;
        this.anchorBZ = anchorBZ;
        this.deckYA = deckYA;
        this.deckYB = deckYB;
        this.axisGroundA = axisGroundA;
        this.axisGroundB = axisGroundB;
        this.axisDeckYA = axisDeckYA;
        this.axisDeckYB = axisDeckYB;
        this.arcPresent = arcPresent;
        this.gapStart = gapStart;
        this.gapEnd = gapEnd;
        this.debris = debris;
        this.reserved = reserved;
        this.rMinX = rMinX;
        this.rMinZ = rMinZ;
        this.rSizeX = rSizeX;
        this.rSizeZ = rSizeZ;
    }

    /** Колонка принадлежит мосту (целому, до разрушения) с запасом в 1 блок — здесь не рисуем «запасной» настил дороги. */
    public boolean reserves(int x, int z) {
        int lx = x - rMinX, lz = z - rMinZ;
        if (lx < 0 || lz < 0 || lx >= rSizeX || lz >= rSizeZ) return false;
        return reserved[lz * rSizeX + lx];
    }

    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | (y & 0xFFFL);
    }
}
