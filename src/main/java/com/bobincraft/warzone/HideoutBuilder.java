package com.bobincraft.warzone;

import com.bobincraft.warzone.BpBuilder.Dir;

/**
 * Лесные укрытия одним чертежом каждое. Три варианта:
 *  0 — бревенчатая хижина с двускатной крышей (в склон врезается: слой выше baseY срезается);
 *  1 — засидка на сваях: настил на высоте 4, перила, крыша, лестница со ступенями +1 блок на шаг и 3 блоками воздуха;
 *  2 — бивак: полевая палатка, костёр с брёвнами-скамьями, бочка, тюк сена с мишенью.
 * Крыши — из неприродных блоков (для Roof Cutaway), двери деревянные. Рельеф не выравнивается: нижние части
 * (сваи, настил) доводятся до земли фундаментом, всё, что не связано с землёй, удаляется.
 */
final class HideoutBuilder {
    static final int HALF = 9;
    private static final int CLEAR_R = 6;       // радиус расчистки (воздух до +10)
    private static final double GROUND_R = 5.5; // радиус «лесной подстилки» вокруг

    private HideoutBuilder() {}

    static Blueprint build(WarzoneTerrain t, HideoutPlan.Hideout h) {
        BpPalette pal = new BpPalette(h.region(), h.faction());
        Blueprint bp = new Blueprint("hideout#" + h.id(), h.cx() - HALF, h.baseY() - 6, h.cz() - HALF,
                2 * HALF + 1, 19, 2 * HALF + 1);
        BpBuilder b = new BpBuilder(t, bp, pal, h.cx(), h.baseY(), h.cz(), h.rotation());

        prepare(b, h);
        switch (h.variant()) {
            case 0 -> cabin(b, h);
            case 1 -> blind(b, h.id());
            default -> bivouac(b, h);
        }
        bushes(b, h);

        Blueprint.Ground g = (x, z) -> t.height(x, z);
        bp.addFoundations(g, BpSpec.of(pal.log).with("axis", "y"), 40);
        bp.connect(g);
        bp.pruneFloating(g);
        return bp;
    }

    private static int groundTop(BpBuilder b, int u, int v) {
        return b.terrain.height(b.wx(u, v), b.wz(u, v));
    }

    // ------------------------------------------------------------------ площадка

    private static void prepare(BpBuilder b, HideoutPlan.Hideout h) {
        boolean marsh = h.region() == WarzoneTerrain.Region.MARSH;
        for (int u = -CLEAR_R - 1; u <= CLEAR_R + 1; u++) {
            for (int v = -CLEAR_R - 1; v <= CLEAR_R + 1; v++) {
                double d = Math.hypot(u, v);
                if (d > CLEAR_R + 0.5) continue;
                for (int dy = 1; dy <= 10; dy++) b.air(u, dy, v);
                if (d > GROUND_R) continue;
                if (groundTop(b, u, v) < b.baseY) continue;       // ниже по склону — природная поверхность остаётся
                long r = WarzoneNoise.hash(b.terrain.seed + 5150, b.wx(u, v), b.wz(u, v)) & 7;
                BpSpec ground;
                if (marsh) ground = r < 3 ? BpSpec.of("mud") : (r < 6 ? BpSpec.of("podzol") : BpSpec.of("moss_block"));
                else ground = r < 3 ? BpSpec.of("podzol") : (r < 5 ? BpSpec.of("coarse_dirt") : BpSpec.of("grass_block").with("snowy", false));
                b.set(u, 0, v, ground);
            }
        }
    }

    /** Кусты-маскировка (листва с persistent): только там, где под ними есть земля на уровне площадки. */
    private static void bushes(BpBuilder b, HideoutPlan.Hideout h) {
        String leaves = b.pal.log.replace("_log", "_leaves");
        BpSpec leaf = BpSpec.of(leaves).with("persistent", true).with("distance", "7").with("waterlogged", false);
        int[][] spots = {{-4, -4}, {4, -4}, {-5, 1}, {5, 2}, {-3, 5}, {4, 5}, {0, -6}};
        for (int[] s : spots) {
            if (groundTop(b, s[0], s[1]) < b.baseY) continue;
            long r = WarzoneNoise.hash(b.terrain.seed + 5151, b.wx(s[0], s[1]), b.wz(s[0], s[1])) & 3;
            if (r == 0) continue;
            if (b.bp.get(b.wx(s[0], s[1]), b.baseY + 1, b.wz(s[0], s[1])) != null
                    && !b.bp.get(b.wx(s[0], s[1]), b.baseY + 1, b.wz(s[0], s[1])).isAir()) continue;
            b.set(s[0], 1, s[1], leaf);
            if (r == 3) b.set(s[0], 2, s[1], leaf);
        }
    }

    // ------------------------------------------------------------------ 0. хижина

    private static void cabin(BpBuilder b, HideoutPlan.Hideout h) {
        BpPalette p = b.pal;
        // пол и стены
        for (int u = -2; u <= 2; u++) {
            for (int v = -2; v <= 2; v++) {
                boolean eu = Math.abs(u) == 2, ev = Math.abs(v) == 2;
                if (eu && ev) {
                    for (int dy = 0; dy <= 3; dy++) b.set(u, dy, v, b.logVertical());
                } else if (eu || ev) {
                    b.set(u, 0, v, p.planks);
                    for (int dy = 1; dy <= 3; dy++) b.set(u, dy, v, p.planks);
                } else {
                    b.set(u, 0, v, p.planks);
                }
            }
        }
        // окна и дверь
        b.set(-2, 2, 0, b.paneAlong("glass_pane", false));
        b.set(2, 2, 0, b.paneAlong("glass_pane", false));
        b.set(0, 2, -2, b.paneAlong("glass_pane", true));
        b.door(0, 1, 2, Dir.NORTH, "left");
        b.set(0, 0, 3, p.planks);
        b.set(0, 0, 4, p.planks);

        // двускатная крыша вдоль U: нижний край (v=±3) на dy3, конёк (v=0) на dy6
        for (int u = -3; u <= 3; u++) {
            b.set(u, 3, 3, b.stair(p.woodStairs, Dir.NORTH, false));
            b.set(u, 3, -3, b.stair(p.woodStairs, Dir.SOUTH, false));
            b.set(u, 4, 2, b.stair(p.woodStairs, Dir.NORTH, false));
            b.set(u, 4, -2, b.stair(p.woodStairs, Dir.SOUTH, false));
            b.set(u, 5, 1, b.stair(p.woodStairs, Dir.NORTH, false));
            b.set(u, 5, -1, b.stair(p.woodStairs, Dir.SOUTH, false));
            b.set(u, 6, 0, p.planks);
        }
        // фронтоны
        for (int su = -2; su <= 2; su += 4) {
            for (int v = -1; v <= 1; v++) b.set(su, 4, v, p.planks);
            b.set(su, 5, 0, p.planks);
        }
        // внутри: сундук, бочка, фонарь на полу
        b.chest(1, 1, -1, Dir.SOUTH, BpBuilder.LOOT_SUPPLY, 31 + h.id());
        b.barrel(-1, 1, -1, Dir.UP, BpBuilder.LOOT_SUPPLY, 32 + h.id());
        b.lantern(-1, 1, 1, false);
        // снаружи: поленница и костёр перед входом
        for (int v = -1; v <= 1; v++) b.set(3, 1, v, b.logHorizontal(false));
        b.set(3, 2, 0, b.logHorizontal(false));
        b.set(-3, 0, 4, BpSpec.of("cobblestone"));
        b.campfire(-3, 1, 4, false);
        b.set(-5, 1, 4, b.logHorizontal(false));
        b.set(-3, 1, 5, b.logHorizontal(true));
    }

    // ------------------------------------------------------------------ 1. засидка на сваях

    /** Засидка на сваях; id — соль для лута (используется и блокпостами {@link CheckpointBuilder}). */
    static void blind(BpBuilder b, int id) {
        BpPalette p = b.pal;
        // настил и угловые сваи
        for (int u = -2; u <= 2; u++) {
            for (int v = -2; v <= 2; v++) {
                boolean corner = Math.abs(u) == 2 && Math.abs(v) == 2;
                if (corner) {
                    for (int dy = 1; dy <= 7; dy++) b.set(u, dy, v, b.logVertical());
                } else {
                    b.set(u, 4, v, p.planks);
                }
            }
        }
        // перила (вход с +U по центру)
        for (int i = -1; i <= 1; i++) {
            if (i != 0) b.set(2, 5, i, b.fenceAlong(p.woodFence, false));
            b.set(-2, 5, i, b.fenceAlong(p.woodFence, false));
            b.set(i, 5, 2, b.fenceAlong(p.woodFence, true));
            b.set(i, 5, -2, b.fenceAlong(p.woodFence, true));
        }
        // лестница: ступени +1 на шаг, над каждой 3 блока воздуха (расчищено заранее)
        int[][] steps = {{5, 0, 1}, {4, 0, 2}, {3, 0, 3}};
        for (int[] s : steps) {
            for (int y = 1; y < s[2]; y++) b.set(s[0], y, s[1], b.logVertical());
            b.set(s[0], s[2], s[1], b.stair(p.stoneStairs, Dir.WEST, false));
        }
        b.set(5, 0, 0, p.floorMain);
        // крыша: плиты на dy=8 (над настилом 3 блока воздуха), вымпел
        for (int u = -3; u <= 3; u++) {
            for (int v = -3; v <= 3; v++) {
                boolean outer = Math.abs(u) == 3 || Math.abs(v) == 3;
                b.set(u, 8, v, b.slab(p.woodSlab, outer ? "bottom" : "top"));
            }
        }
        // маскировочная бахрома из листвы под свесом (кроме подхода к лестнице)
        String leaves = p.log.replace("_log", "_leaves");
        BpSpec leaf = BpSpec.of(leaves).with("persistent", true).with("distance", "7").with("waterlogged", false);
        for (int u = -3; u <= 3; u++) {
            for (int v = -3; v <= 3; v++) {
                boolean ring = Math.abs(u) == 3 || Math.abs(v) == 3;
                if (!ring) continue;
                if (u == 3 && Math.abs(v) <= 1) continue;
                if ((u + v) % 2 == 0) b.set(u, 7, v, leaf);
            }
        }
        b.set(0, 9, 0, p.bannerWool);
        b.chest(-1, 5, -1, Dir.SOUTH, BpBuilder.LOOT_ARMORY, 41 + id);
        b.barrel(1, 5, -1, Dir.UP, BpBuilder.LOOT_SUPPLY, 42 + id);
        b.lantern(0, 7, 0, true);
    }

    // ------------------------------------------------------------------ 2. бивак

    private static void bivouac(BpBuilder b, HideoutPlan.Hideout h) {
        SiteModules.fieldTent(b, -3, -2, 50 + h.id());
        // костровая зона с брёвнами-скамьями
        int cu = 3, cv = 2;
        b.set(cu, 0, cv, BpSpec.of("cobblestone"));
        b.campfire(cu, 1, cv, false);
        b.set(cu - 2, 1, cv, b.logHorizontal(false));
        b.set(cu - 2, 1, cv + 1, b.logHorizontal(false));
        b.set(cu + 2, 1, cv, b.logHorizontal(false));
        b.set(cu + 2, 1, cv - 1, b.logHorizontal(false));
        b.set(cu, 1, cv - 2, b.logHorizontal(true));
        b.barrel(cu - 2, 2, cv, Dir.UP, BpBuilder.LOOT_SUPPLY, 60 + h.id());
        b.set(cu + 2, 1, cv + 2, BpSpec.of("hay_block").with("axis", "y"));
        b.set(cu + 2, 2, cv + 2, BpSpec.of("target").with("power", "0"));
    }
}
