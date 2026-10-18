package com.bobincraft.warzone;

import com.bobincraft.warzone.BpBuilder.Dir;

/**
 * Дозорный блокпост (WATCHTOWER) одним чертежом: вышка с внешней лестницей и снайперской площадкой, мешки с песком,
 * противотанковые ежи, полевая палатка и костёр. Содержимое — точный перенос прежнего чанкового построителя
 * ({@code WarzoneStructures.buildWatchtowerCheckpoint}), но строится ОДИН раз в мировых координатах, после чего
 * соединения заборов/прутьев/стен считаются по всему чертежу, фундамент доводится до реального рельефа, а всё, что
 * не связано с землёй, удаляется. Рельеф под площадкой уже выровнен до baseY.
 */
final class WatchtowerBuilder {
    static final int HALF = 12;      // половина стороны bbox (хватает при любом повороте)
    private static final int R = 9;

    private WatchtowerBuilder() {}

    static Blueprint build(WarzoneTerrain t, WarzoneBasePlan.Site site) {
        BpPalette pal = new BpPalette(site.region, site.faction);
        Blueprint bp = new Blueprint("watchtower#" + site.id, site.cx - HALF, site.baseY - 5, site.cz - HALF,
                2 * HALF + 1, 20, 2 * HALF + 1);
        BpBuilder b = new BpBuilder(t, bp, pal, site.cx, site.baseY, site.cz, site.rotation);

        prepareGround(b, R);
        tower(b, 0, -2, Dir.SOUTH, 1);
        sandbagPosition(b, -5, 0, 5, Dir.SOUTH);
        sandbagPosition(b, 5, 0, 5, Dir.SOUTH);
        tankTrap(b, -7, 0, 3);
        tankTrap(b, 7, 0, 3);

        int m = site.variant == 1 ? -1 : 1;
        fieldTent(b, 6 * m, -1, 10);
        b.campfire(-6 * m, 1, -1, false);
        b.set(-6 * m, 0, -1, BpSpec.of("cobblestone"));
        b.barrel(-5 * m, 1, -3, Dir.UP, BpBuilder.LOOT_SUPPLY, 15);

        return finish(t, bp, pal);
    }

    /** Общая «сборка» чертежа: соединения, фундамент до земли, удаление висящего. */
    static Blueprint finish(WarzoneTerrain t, Blueprint bp, BpPalette pal) {
        Blueprint.Ground g = (x, z) -> t.height(x, z);
        bp.addFoundations(g, pal.wallMain, 40);
        bp.connect(g);
        bp.pruneFloating(g);
        return bp;
    }

    // ------------------------------------------------------------------ площадка

    static void prepareGround(BpBuilder b, int radius) {
        for (int u = -radius - 2; u <= radius + 2; u++) {
            for (int v = -radius - 2; v <= radius + 2; v++) {
                if (Math.hypot(u, v) > radius + 2.5) continue;
                int x = b.wx(u, v), z = b.wz(u, v);
                for (int dy = 1; dy <= 13; dy++) b.air(u, dy, v);
                for (int dy = -3; dy <= -1; dy++) b.set(u, dy, v, b.pal.wallMain);

                long h = WarzoneNoise.hash(b.terrain.seed + 303, x, z) & 7;
                boolean onMainAxis = Math.abs(u) <= 1 || Math.abs(v) <= 1;
                BpSpec ground;
                if (onMainAxis && Math.abs(u) <= radius && Math.abs(v) <= radius) {
                    ground = (h == 0) ? BpSpec.of("gravel") : b.pal.yardPath;
                } else if (h <= 2) {
                    ground = BpSpec.of("coarse_dirt");
                } else if (h <= 4) {
                    ground = BpSpec.of("gravel");
                } else if (h == 5) {
                    ground = b.pal.yardPath;
                } else {
                    ground = BpSpec.of("andesite");
                }
                b.set(u, 0, v, ground);
            }
        }
    }

    // ------------------------------------------------------------------ вышка

    static void tower(BpBuilder b, int cu, int cv, Dir doorSide, int salt) {
        BpPalette p = b.pal;
        b.fill(cu - 3, 0, cv - 3, cu + 3, 0, cv + 3, p.floorAccent);
        b.fillAir(cu - 3, 1, cv - 3, cu + 3, 11, cv + 3);

        for (int du = -2; du <= 2; du++) {
            for (int dv = -2; dv <= 2; dv++) {
                boolean edgeU = Math.abs(du) == 2;
                boolean edgeV = Math.abs(dv) == 2;
                int u = cu + du, v = cv + dv;
                if (edgeU && edgeV) {
                    for (int dy = 1; dy <= 9; dy++) b.set(u, dy, v, dy <= 5 ? p.pillar : b.logVertical());
                } else if (edgeU || edgeV) {
                    for (int dy = 1; dy <= 5; dy++) {
                        if (dy == 2 && (du == 0 || dv == 0)) b.set(u, dy, v, b.paneAlong("iron_bars", edgeV));
                        else b.setMasonry(u, dy, v);
                    }
                } else {
                    b.set(u, 0, v, p.planks);
                }
            }
        }

        int doorU = cu + doorSide.dx * 2;
        int doorV = cv + doorSide.dz * 2;
        b.door(doorU, 1, doorV, doorSide.opposite(), "left");
        b.set(cu + doorSide.dx * 3, 0, cv + doorSide.dz * 3, p.floorMain);

        b.barrel(cu - 1, 1, cv - 1, Dir.UP, BpBuilder.LOOT_SUPPLY, salt * 11 + 1);
        b.lantern(cu, 5, cv, true);

        // внешняя лестница: +1 блок на шаг, 3 блока воздуха над каждой ступенью
        int[][] stairPath = {{-3, 2, 1}, {-3, 1, 2}, {-3, 0, 3}, {-3, -1, 4}, {-3, -2, 5}, {-3, -3, 6}};
        b.set(cu - 3, 0, cv + 3, p.floorMain);
        for (int[] st : stairPath) {
            int su = cu + st[0], sv = cv + st[1], sdy = st[2];
            for (int y = 1; y < sdy; y++) b.set(su, y, sv, p.pillar);
            b.set(su, sdy, sv, sdy < 6 ? b.stair(p.stoneStairs, Dir.NORTH, false) : p.planks);
            b.air(su, sdy + 1, sv);
            b.air(su, sdy + 2, sv);
            b.air(su, sdy + 3, sv);
        }

        // снайперская площадка
        for (int du = -2; du <= 2; du++)
            for (int dv = -2; dv <= 2; dv++) b.set(cu + du, 6, cv + dv, p.planks);
        b.set(cu - 2, 6, cv - 3, p.planks);
        b.set(cu - 3, 7, cv - 3, b.fenceCorner(p.woodFence, Dir.SOUTH, Dir.EAST));

        for (int du = -2; du <= 2; du++) {
            for (int dv = -2; dv <= 2; dv++) {
                boolean edgeU = Math.abs(du) == 2;
                boolean edgeV = Math.abs(dv) == 2;
                if (edgeU && edgeV) continue;
                if (!edgeU && !edgeV) continue;
                if (du == -2 && (dv == -1 || dv == -2)) continue;
                int u = cu + du, v = cv + dv;
                if (du == 0 || dv == 0) b.set(u, 7, v, b.slab(p.roofSlab, "bottom"));
                else b.set(u, 7, v, b.fenceAlong(p.woodFence, edgeV));
            }
        }
        b.air(cu - 2, 7, cv - 2);
        b.air(cu - 2, 8, cv - 2);
        b.air(cu - 2, 7, cv - 3);
        b.air(cu - 2, 8, cv - 3);

        b.chest(cu + 1, 7, cv + 1, Dir.NORTH, BpBuilder.LOOT_ARMORY, salt * 17 + 3);
        b.lantern(cu, 9, cv, true);

        // крыша-навес на dy = 10 (над площадкой 3 блока воздуха)
        for (int du = -3; du <= 3; du++) {
            for (int dv = -3; dv <= 3; dv++) {
                boolean outer = Math.abs(du) == 3 || Math.abs(dv) == 3;
                b.set(cu + du, 10, cv + dv, b.slab(p.roofSlab, outer ? "bottom" : "top"));
            }
        }
        b.set(cu, 11, cv, p.pillar);
        b.set(cu + 1, 11, cv, p.bannerWool);
        b.set(cu, 12, cv, BpSpec.of("lightning_rod").with("facing", "up").with("powered", false).with("waterlogged", false));
    }

    // ------------------------------------------------------------------ мелкие укрепления

    static void sandbagPosition(BpBuilder b, int cu, int dy, int cv, Dir facing) {
        boolean alongU = facing == Dir.NORTH || facing == Dir.SOUTH;
        for (int i = -2; i <= 2; i++) {
            int u = alongU ? cu + i : cu;
            int v = alongU ? cv : cv + i;
            b.set(u, dy + 1, v, b.pal.sandbag);
            if (Math.abs(i) == 1) b.set(u, dy + 2, v, b.pal.sandbag);
            else if (i == 0) b.set(u, dy + 2, v, b.slab(b.pal.woodSlab, "bottom"));
        }
    }

    static void tankTrap(BpBuilder b, int u, int dy, int v) {
        b.set(u, dy + 1, v, b.wall("cobblestone_wall"));
        b.set(u - 1, dy + 1, v, b.paneAlong("iron_bars", true));
        b.set(u + 1, dy + 1, v, b.paneAlong("iron_bars", true));
        b.set(u, dy + 1, v - 1, b.paneAlong("iron_bars", false));
        b.set(u, dy + 1, v + 1, b.paneAlong("iron_bars", false));
        b.set(u, dy + 2, v, BpSpec.of("iron_bars"));
    }

    static void fieldTent(BpBuilder b, int cu, int cv, int salt) {
        b.fill(cu - 2, 0, cv - 2, cu + 2, 0, cv + 2, b.pal.planks);
        for (int dv = -2; dv <= 2; dv++) {
            b.set(cu - 2, 1, cv + dv, b.pal.bannerWool);
            b.set(cu + 2, 1, cv + dv, b.pal.bannerWool);
            b.set(cu - 2, 2, cv + dv, b.pal.bannerWool);
            b.set(cu + 2, 2, cv + dv, b.pal.bannerWool);
            b.set(cu - 1, 3, cv + dv, b.pal.bannerWool);
            b.set(cu + 1, 3, cv + dv, b.pal.bannerWool);
            b.set(cu, 4, cv + dv, b.slab(b.pal.woodSlab, "bottom"));
        }
        b.set(cu, 1, cv - 2, b.logVertical());
        b.set(cu, 2, cv - 2, b.logVertical());
        b.set(cu, 3, cv - 2, b.logVertical());
        b.chest(cu + 1, 1, cv - 1, Dir.WEST, BpBuilder.LOOT_SUPPLY, salt + 1);
        b.lantern(cu, 3, cv, true);
    }
}
