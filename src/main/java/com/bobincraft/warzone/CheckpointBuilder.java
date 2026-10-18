package com.bobincraft.warzone;

import com.bobincraft.warzone.BpBuilder.Dir;

/**
 * Придорожные блокпосты одним чертежом каждый. Дорога идёт вдоль локальной оси V, ширина полотна — 2·halfWidth+1
 * (по U). Проезд/проход по полотну всегда остаётся свободным (проверяется headless: BFS по полосе дороги).
 * Три варианта:
 *  0 — шлагбаум: опущен только на половину полосы (arm на 4 блока над землёй), караулка с дверью на дорогу,
 *      мешки, зубы дракона и проволока по сторонам;
 *  1 — баррикада: две шахматные стенки из брёвен/мешков/бочек (змейка шириной 3), разбитая повозка, бивак с палаткой;
 *  2 — укреплённый кордон: зубы дракона с проходом посередине, застава на сваях (лестница к дороге), гнездо из мешков.
 * Рельеф не выравнивается: все детали ставятся на реальную высоту колонки, площадки домов — по высшей точке с
 * фундаментом до земли; всё, что не связано с землёй, удаляется.
 */
final class CheckpointBuilder {
    static final int HALF = 14;

    private CheckpointBuilder() {}

    static Blueprint build(WarzoneTerrain t, PoiPlan.Checkpoint c) {
        BpPalette pal = new BpPalette(c.region(), c.faction());
        Blueprint bp = new Blueprint("checkpoint#" + c.id(), c.cx() - HALF, c.baseY() - 8, c.cz() - HALF,
                2 * HALF + 1, 28, 2 * HALF + 1);
        BpBuilder b = new BpBuilder(t, t.seed, bp, pal, c.cx(), c.baseY(), c.cz(), c.rotation(), t.seed + 91L * c.id());
        switch (c.variant()) {
            case 0 -> gate(b, c);
            case 1 -> barricade(b, c);
            default -> cordon(b, c);
        }
        Blueprint.Ground g = (x, z) -> t.height(x, z);
        bp.addFoundations(g, BpSpec.of(pal.log).with("axis", "y"), 40);
        bp.connect(g);
        bp.pruneFloating(g);
        return bp;
    }

    // ------------------------------------------------------------------ помощники (высота по реальной земле)

    /** Высота верхнего блока земли в колонке (u, v) относительно baseY. */
    static int gy(BpBuilder b, int u, int v) {
        return b.terrain.height(b.wx(u, v), b.wz(u, v)) - b.baseY;
    }

    /** Блок на k-м уровне над землёй колонки. */
    static void put(BpBuilder b, int u, int k, int v, BpSpec spec) {
        b.set(u, gy(b, u, v) + k, v, spec);
    }

    private static int maxGy(BpBuilder b, int u0, int v0, int u1, int v1) {
        int m = Integer.MIN_VALUE;
        for (int u = u0; u <= u1; u++) for (int v = v0; v <= v1; v++) m = Math.max(m, gy(b, u, v));
        return m;
    }

    /** Подстроитель с общим уровнем пола = высшая точка земли в прямоугольнике (в координатах подстроителя). */
    static BpBuilder levelled(BpBuilder b, int u, int v, int face, int u0, int v0, int u1, int v1) {
        BpBuilder tmp = b.child(u, v, face);
        int top = Integer.MIN_VALUE;
        for (int du = u0; du <= u1; du++)
            for (int dv = v0; dv <= v1; dv++)
                top = Math.max(top, b.terrain.height(tmp.wx(du, dv), tmp.wz(du, dv)));
        return new BpBuilder(b.terrain, b.bp, b.pal, tmp.cx, top, tmp.cz, tmp.rot);
    }

    static void bagRun(BpBuilder b, int u0, int v0, int u1, int v1) {
        int i = 0;
        for (int u = Math.min(u0, u1); u <= Math.max(u0, u1); u++) {
            for (int v = Math.min(v0, v1); v <= Math.max(v0, v1); v++) {
                put(b, u, 1, v, b.pal.sandbag);
                if (i % 3 != 1) put(b, u, 2, v, b.pal.sandbag);
                i++;
            }
        }
    }

    static void teeth(BpBuilder b, int u, int v) {
        for (int i = -1; i <= 1; i++) {
            put(b, u + i, 1, v, BpSpec.of("cobblestone"));
            if (i != 0) put(b, u + i, 2, v, b.stair("cobblestone_stairs", i < 0 ? Dir.EAST : Dir.WEST, false));
        }
        put(b, u, 2, v, BpSpec.of("cobblestone"));
    }

    static void wire(BpBuilder b, int u0, int u1, int v) {
        for (int u = Math.min(u0, u1); u <= Math.max(u0, u1); u++) {
            if (Math.floorMod(u, 3) == 0) {
                put(b, u, 1, v, b.fenceAlong(b.pal.woodFence, true));
                put(b, u, 2, v, b.fenceAlong(b.pal.woodFence, true));
            } else {
                put(b, u, 1, v, ((u + v) & 1) == 0 ? BpSpec.of("iron_bars") : BpSpec.of("cobweb"));
            }
        }
    }

    static void flag(BpBuilder b, int u, int v) {
        int g = gy(b, u, v);
        for (int k = 1; k <= 5; k++) b.set(u, g + k, v, BpSpec.of(b.pal.woodFence));
        b.set(u + 1, g + 5, v, b.pal.bannerWool);
        b.set(u + 1, g + 4, v, b.pal.bannerWool);
    }

    static void crateStack(BpBuilder b, int u, int v, int salt) {
        int g = gy(b, u, v);
        b.barrel(u, g + 1, v, Dir.UP, BpBuilder.LOOT_SUPPLY, salt);
        b.set(u, g + 2, v, BpSpec.of("hay_block").with("axis", "y"));
    }

    // ------------------------------------------------------------------ караулка (бревенчатая, 5×5)

    private static void hut(BpBuilder b, int u, int v, int face, int salt) {
        BpBuilder h = levelled(b, u, v, face, -3, -3, 3, 4);
        BpPalette p = h.pal;
        for (int du = -3; du <= 3; du++)
            for (int dv = -3; dv <= 3; dv++)
                for (int dy = 1; dy <= 8; dy++) h.air(du, dy, dv);
        for (int du = -2; du <= 2; du++) {
            for (int dv = -2; dv <= 2; dv++) {
                boolean eu = Math.abs(du) == 2, ev = Math.abs(dv) == 2;
                if (eu && ev) {
                    for (int dy = 0; dy <= 3; dy++) h.set(du, dy, dv, h.logVertical());
                } else if (eu || ev) {
                    for (int dy = 0; dy <= 3; dy++) h.set(du, dy, dv, p.planks);
                } else {
                    h.set(du, 0, dv, p.planks);
                }
            }
        }
        h.set(-2, 2, 0, h.paneAlong("glass_pane", false));
        h.set(2, 2, 0, h.paneAlong("glass_pane", false));
        h.set(0, 2, -2, h.paneAlong("glass_pane", true));
        h.door(0, 1, 2, Dir.NORTH, "left");
        h.set(0, 0, 3, p.planks);
        h.set(0, 0, 4, p.planks);
        for (int du = -3; du <= 3; du++) {
            h.set(du, 3, 3, h.stair(p.woodStairs, Dir.NORTH, false));
            h.set(du, 3, -3, h.stair(p.woodStairs, Dir.SOUTH, false));
            h.set(du, 4, 2, h.stair(p.woodStairs, Dir.NORTH, false));
            h.set(du, 4, -2, h.stair(p.woodStairs, Dir.SOUTH, false));
            h.set(du, 5, 1, h.stair(p.woodStairs, Dir.NORTH, false));
            h.set(du, 5, -1, h.stair(p.woodStairs, Dir.SOUTH, false));
            h.set(du, 6, 0, p.planks);
        }
        for (int su = -2; su <= 2; su += 4) {
            for (int dv = -1; dv <= 1; dv++) h.set(su, 4, dv, p.planks);
            h.set(su, 5, 0, p.planks);
        }
        h.chest(1, 1, -1, Dir.SOUTH, BpBuilder.LOOT_ARMORY, salt);
        h.barrel(-1, 1, -1, Dir.UP, BpBuilder.LOOT_SUPPLY, salt + 1);
        h.lantern(-1, 1, 1, false);
    }

    // ------------------------------------------------------------------ 0. шлагбаум

    private static void gate(BpBuilder b, PoiPlan.Checkpoint c) {
        int hw = c.halfWidth();
        int pw = -(hw + 1), pe = hw + 1;
        for (int k = 1; k <= 5; k++) {
            put(b, pw, k, 0, b.logVertical());
            put(b, pe, k, 0, b.logVertical());
        }
        // стрела: на высоте 4 над самой высокой землёй под ней, перекрывает только западную половину полосы
        int by = maxGy(b, pw, 0, 0, 0) + 4;
        for (int u = pw + 1; u <= 0; u++) b.set(u, by, 0, ((u & 1) == 0) ? BpSpec.of("red_wool") : BpSpec.of("white_wool"));
        b.lantern(0, by - 1, 0, true);
        put(b, pe, 6, 0, BpSpec.of("lantern").with("hanging", false).with("waterlogged", false));
        flag(b, -(hw + 2), -2);

        hut(b, hw + 5, -3, 1, 31 + c.id());

        // мешки: западное гнездо с ящиками, восточная стенка
        bagRun(b, -(hw + 2), 3, -(hw + 5), 3);
        bagRun(b, -(hw + 5), 0, -(hw + 5), 2);
        crateStack(b, -(hw + 3), 1, 41 + c.id());
        bagRun(b, hw + 2, 3, hw + 6, 3);
        // зубы дракона и проволока по обочинам (полоса дороги свободна)
        teeth(b, -(hw + 2), -7);
        teeth(b, hw + 2, 7);
        wire(b, hw + 3, hw + 8, 8);
        wire(b, -(hw + 3), -(hw + 8), 8);
        wire(b, -(hw + 3), -(hw + 8), -8);
    }

    // ------------------------------------------------------------------ 1. баррикада

    static void barricadeRow(BpBuilder b, int u0, int u1, int v, int salt) {
        for (int u = u0; u <= u1; u++) {
            double r = b.noise(u, 0, v, salt);
            if (r < 0.12) continue;                                // пролом
            if (r < 0.55) {
                put(b, u, 1, v, b.logHorizontal(true));
                put(b, u, 2, v, b.logHorizontal(true));
                if (r > 0.45) put(b, u, 3, v, b.pal.sandbag);
            } else if (r < 0.75) {
                put(b, u, 1, v, b.pal.sandbag);
                put(b, u, 2, v, b.pal.sandbag);
            } else if (r < 0.88) {
                b.barrel(u, gy(b, u, v) + 1, v, Dir.UP, BpBuilder.LOOT_SUPPLY, salt + u);
            } else {
                put(b, u, 1, v, BpSpec.of("hay_block").with("axis", "y"));
                put(b, u, 2, v, BpSpec.of("hay_block").with("axis", "y"));
            }
        }
    }

    /** Разбитая повозка 3×5: пол с дырами, обломок борта, колёса-люки, груз. */
    static void wagon(BpBuilder b, int u, int v, int face, int salt) {
        BpBuilder w = levelled(b, u, v, face, -2, -2, 2, 2);
        BpPalette p = w.pal;
        for (int du = -2; du <= 2; du++)
            for (int dv = -2; dv <= 2; dv++)
                for (int dy = 1; dy <= 4; dy++) w.air(du, dy, dv);
        for (int du = -1; du <= 1; du++) {
            for (int dv = -2; dv <= 2; dv++) {
                if ((du != 0 || dv != 0) && w.noise(du, 0, dv, salt) < 0.2) continue;
                w.set(du, 0, dv, p.planks);
            }
        }
        for (int dv = -2; dv <= 2; dv++) {
            if (w.noise(1, 1, dv, salt) < 0.7) w.set(-1, 1, dv, w.fenceAlong(p.woodFence, false));
            if (w.noise(2, 1, dv, salt) < 0.4) w.set(1, 1, dv, w.fenceAlong(p.woodFence, false));
        }
        for (int du = -1; du <= 1; du++) {
            w.set(du, 1, -2, p.planks);
            if (du != 1) w.set(du, 2, -2, p.planks);
        }
        w.trapdoor(-2, 1, 1, Dir.EAST, false, true);
        w.trapdoor(2, 1, -1, Dir.WEST, false, true);
        if (w.noise(0, 0, 0, salt + 5) < 0.6) w.chest(0, 1, 1, Dir.SOUTH, BpBuilder.LOOT_SUPPLY, salt);
        else w.set(0, 1, 1, BpSpec.of("hay_block").with("axis", "y"));
        w.barrel(0, 1, 0, Dir.UP, BpBuilder.LOOT_SUPPLY, salt + 1);
    }

    private static void barricade(BpBuilder b, PoiPlan.Checkpoint c) {
        int hw = c.halfWidth();
        // змейка: стенка A закрывает запад и центр, проход — восточные 3 блока; стенка B зеркально
        barricadeRow(b, -(hw + 4), hw - 3, -4, 61 + c.id());
        barricadeRow(b, 3 - hw, hw + 4, 4, 71 + c.id());
        teeth(b, -(hw + 3), -6);
        teeth(b, hw + 3, 6);
        wire(b, -(hw + 8), -(hw + 5), -4);
        wire(b, hw + 5, hw + 8, 4);
        wire(b, -(hw + 8), -(hw + 3), 9);
        flag(b, hw + 3, -7);
        // повозка у восточной обочины, бивак у западной
        wagon(b, hw + 5, 0, 0, 81 + c.id());
        int cu = -(hw + 4);
        b.set(cu, gy(b, cu, 0), 0, BpSpec.of("cobblestone"));
        b.campfire(cu, gy(b, cu, 0) + 1, 0, false);
        put(b, cu + 2, 1, 0, b.logHorizontal(false));
        put(b, cu, 1, 2, b.logHorizontal(true));
        put(b, cu, 1, -2, b.logHorizontal(true));
        BpBuilder tent = levelled(b, -(hw + 6), 5, 0, -2, -2, 2, 2);
        for (int du = -2; du <= 2; du++)
            for (int dv = -2; dv <= 2; dv++)
                for (int dy = 1; dy <= 6; dy++) tent.air(du, dy, dv);
        SiteModules.tent(tent, 2, 2, 91 + c.id(), false);
    }

    // ------------------------------------------------------------------ 2. укреплённый кордон

    private static void cordon(BpBuilder b, PoiPlan.Checkpoint c) {
        int hw = c.halfWidth();
        // «зубы дракона»: два ряда, проход посередине (|u| <= 1) остаётся свободным
        for (int u = 2; u <= hw + 1; u += 3) {
            teeth(b, u + 1, -5);
            teeth(b, -(u + 1), 5);
        }
        teeth(b, -(hw + 3), -5);
        teeth(b, hw + 3, 5);
        wire(b, hw + 2, hw + 9, -9);
        wire(b, -(hw + 2), -(hw + 9), 9);

        // восточная застава на сваях: лестница обращена к дороге (face=2 → локальная +U смотрит на запад)
        int tu = hw + 7;
        BpBuilder tw = levelled(b, tu, -3, 2, -3, -3, 5, 3);
        for (int du = -3; du <= 5; du++)
            for (int dv = -4; dv <= 4; dv++)
                for (int dy = 1; dy <= 10; dy++) tw.air(du, dy, dv);
        HideoutBuilder.blind(tw, 200 + c.id());

        // западное гнездо из мешков с ящиками и флагом
        bagRun(b, -(hw + 2), 2, -(hw + 6), 2);
        bagRun(b, -(hw + 6), -2, -(hw + 6), 1);
        bagRun(b, -(hw + 2), -2, -(hw + 4), -2);
        crateStack(b, -(hw + 4), 0, 101 + c.id());
        b.set(-(hw + 3), gy(b, -(hw + 3), -1), -1, BpSpec.of("cobblestone"));
        b.campfire(-(hw + 3), gy(b, -(hw + 3), -1) + 1, -1, false);
        flag(b, -(hw + 2), 6);
        // ближе к дороге — перекладина-шлагбаум поднята: стойки по краям полосы без перекрытия
        put(b, hw + 2, 1, 0, b.logVertical());
        put(b, hw + 2, 2, 0, b.logVertical());
        put(b, hw + 2, 3, 0, BpSpec.of("lantern").with("hanging", false).with("waterlogged", false));
    }
}
