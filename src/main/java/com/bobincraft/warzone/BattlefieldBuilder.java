package com.bobincraft.warzone;

import com.bobincraft.warzone.BpBuilder.Dir;

import static com.bobincraft.warzone.CheckpointBuilder.gy;
import static com.bobincraft.warzone.CheckpointBuilder.put;

import java.util.Random;

/**
 * Старые поля боёв одним чертежом каждое: воронки, остовы танков, разбитые повозки, окопы, могилы, мёртвые деревья.
 * Рельеф не выравнивается — всё врезается/ставится по реальной высоте колонки; крупные остовы стоят на общем уровне
 * (высшая точка под ними) с земляной подсыпкой до грунта. Три варианта: 0 — танковый бой, 1 — окопная линия,
 * 2 — разбитый обоз. Лут: по одному сундуку в танках и повозках (оружейный/припасы).
 */
final class BattlefieldBuilder {
    static final int HALF = 17;
    private static final int R = 13;

    private BattlefieldBuilder() {}

    static Blueprint build(WarzoneTerrain t, PoiPlan.Battlefield f) {
        BpPalette pal = new BpPalette(f.region(), f.faction());
        Blueprint bp = new Blueprint("battlefield#" + f.id(), f.cx() - HALF, f.baseY() - 14, f.cz() - HALF,
                2 * HALF + 1, 34, 2 * HALF + 1);
        BpBuilder b = new BpBuilder(t, t.seed, bp, pal, f.cx(), f.baseY(), f.cz(), f.rotation(), t.seed + 137L * f.id());
        Random rnd = b.rnd;
        int id = f.id();

        switch (f.variant()) {
            case 0 -> {
                tanks(b, 2 + rnd.nextInt(2), id);
                wagons(b, rnd.nextInt(2), id);
                craters(b, 6 + rnd.nextInt(4));
                graves(b, 1, id);
                snags(b, 3);
                barriers(b, 2);
                debris(b, 18);
            }
            case 1 -> {
                trenches(b, 2, 6 + rnd.nextInt(2));
                craters(b, 3 + rnd.nextInt(3));
                graves(b, 2, id);
                foxholes(b, 2);
                snags(b, 2);
                barriers(b, 2);
                debris(b, 14);
            }
            default -> {
                wagons(b, 3 + rnd.nextInt(2), id);
                tanks(b, 1, id);
                craters(b, 3 + rnd.nextInt(3));
                graves(b, 1, id);
                foxholes(b, 1);
                snags(b, 3);
                debris(b, 22);
            }
        }

        Blueprint.Ground g = (x, z) -> t.height(x, z);
        bp.addFoundations(g, BpSpec.of("coarse_dirt"), 40);
        bp.connect(g);
        bp.pruneFloating(g);
        return bp;
    }

    // ------------------------------------------------------------------ размещение

    /** Свободный квадрат радиуса r внутри диска R; резервирует его. null — не нашли место. */
    private static int[] spot(BpBuilder b, int r) {
        return spotRect(b, r, r);
    }

    private static int[] spotRect(BpBuilder b, int ru, int rv) {
        for (int i = 0; i < 40; i++) {
            double ang = b.rnd.nextDouble() * Math.PI * 2.0;
            double dist = Math.sqrt(b.rnd.nextDouble()) * Math.max(0, R - Math.max(ru, rv) + 2);
            int u = (int) Math.round(Math.cos(ang) * dist), v = (int) Math.round(Math.sin(ang) * dist);
            if (Math.abs(u) + ru > HALF || Math.abs(v) + rv > HALF) continue;
            if (b.isFree(u - ru, v - rv, u + ru, v + rv, 0)) {
                b.reserve(u - ru, v - rv, u + ru, v + rv);
                return new int[]{u, v};
            }
        }
        return null;
    }

    private static BpSpec scorch(BpBuilder b, int u, int v, double r) {
        double n = b.noise(u, 0, v, 7);
        if (r < 0.4 && n < 0.45) return BpSpec.of("blackstone");
        return n < 0.55 ? BpSpec.of("coarse_dirt") : (n < 0.85 ? BpSpec.of("gravel") : BpSpec.of("podzol"));
    }

    // ------------------------------------------------------------------ воронки

    private static void craters(BpBuilder b, int count) {
        for (int n = 0; n < count; n++) {
            int r = 2 + b.rnd.nextInt(3);
            int[] p = spot(b, r + 1);
            if (p == null) continue;
            crater(b, p[0], p[1], r);
        }
    }

    private static void crater(BpBuilder b, int cu, int cv, int r) {
        int maxDepth = r >= 4 ? 3 : (r >= 3 ? 2 : 1);
        for (int du = -r - 1; du <= r + 1; du++) {
            for (int dv = -r - 1; dv <= r + 1; dv++) {
                int u = cu + du, v = cv + dv;
                double d = Math.hypot(du, dv);
                int g = gy(b, u, v);
                if (d <= r) {
                    double q = d / r;
                    int depth = Math.max(1, (int) Math.round(maxDepth * (1.0 - q * q) + 0.3));
                    for (int k = 0; k < depth; k++) b.air(u, g - k, v);
                    b.set(u, g - depth, v, scorch(b, u, v, q));
                } else if (d <= r + 1.3) {
                    b.set(u, g, v, scorch(b, u, v, 1.0));
                }
            }
        }
    }

    private static void foxholes(BpBuilder b, int count) {
        for (int n = 0; n < count; n++) {
            int[] p = spot(b, 3);
            if (p == null) continue;
            for (int du = -3; du <= 3; du++) {
                for (int dv = -3; dv <= 3; dv++) {
                    double d = Math.hypot(du, dv);
                    int u = p[0] + du, v = p[1] + dv;
                    int g = gy(b, u, v);
                    if (d <= 1.5) {
                        b.air(u, g, v);
                        b.air(u, g - 1, v);
                        b.set(u, g - 2, v, BpSpec.of("coarse_dirt"));
                    } else if (d <= 2.6 && b.noise(u, 0, v, 11) < 0.7) {
                        put(b, u, 1, v, b.pal.sandbag);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ техника

    private static void tanks(BpBuilder b, int count, int id) {
        for (int n = 0; n < count; n++) {
            int[] p = spot(b, 4);
            if (p == null) continue;
            tank(b, p[0], p[1], b.rnd.nextInt(4), 300 + id * 7 + n);
        }
    }

    /** Подбитый танк 3×5: корпус, гусеницы, башня со сбитым стволом; лут в моторном отсеке. */
    private static void tank(BpBuilder b, int u, int v, int face, int salt) {
        BpBuilder h = CheckpointBuilder.levelled(b, u, v, face, -1, -2, 1, 2);
        BpSpec hull = BpSpec.of("gray_concrete");
        BpSpec track = BpSpec.of("black_concrete");
        for (int du = -3; du <= 3; du++)
            for (int dv = -3; dv <= 5; dv++)
                for (int dy = 1; dy <= 6; dy++) h.air(du, dy, dv);
        for (int du = -1; du <= 1; du++) {
            for (int dv = -2; dv <= 2; dv++) {
                h.set(du, 0, dv, du == 0 ? hull : track);
                if (du == 0) {
                    if (b.noise(du, 1, dv, salt) > 0.15 || dv == 0) h.set(du, 1, dv, hull);
                } else if (b.noise(du, 1, dv, salt) > 0.2) {
                    h.set(du, 1, dv, track);
                }
            }
        }
        for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
                if (du == 0 || b.noise(du, 2, dv, salt) > 0.35) h.set(du, 2, dv, hull);
            }
        }
        h.set(0, 3, 0, hull);
        if (b.noise(1, 3, 0, salt) > 0.4) h.set(1, 3, 0, hull);
        if (b.noise(-1, 3, 0, salt) > 0.4) h.set(-1, 3, 0, hull);
        // сбитый ствол
        h.set(0, 2, 2, h.paneAlong("iron_bars", false));
        h.set(0, 2, 3, h.paneAlong("iron_bars", false));
        h.set(0, 1, 4, h.paneAlong("iron_bars", false));
        // моторный отсек: сундук на корпусе сзади
        h.chest(0, 2, -2, Dir.NORTH, BpBuilder.LOOT_ARMORY, salt);
        // гарь вокруг
        for (int du = -3; du <= 3; du++) {
            for (int dv = -3; dv <= 5; dv++) {
                if (Math.abs(du) <= 1 && dv >= -2 && dv <= 2) continue;
                if (h.noise(du, 0, dv, salt + 3) < 0.3) {
                    int g = gy(h, du, dv);
                    if (g <= 1) h.set(du, g, dv, BpSpec.of("blackstone"));
                }
            }
        }
    }

    private static void wagons(BpBuilder b, int count, int id) {
        for (int n = 0; n < count; n++) {
            int[] p = spot(b, 3);
            if (p == null) continue;
            CheckpointBuilder.wagon(b, p[0], p[1], b.rnd.nextInt(4), 400 + id * 7 + n);
            // выпавший груз рядом
            int cu = p[0] + (b.rnd.nextBoolean() ? 3 : -3), cv = p[1] + b.rnd.nextInt(3) - 1;
            if (Math.abs(cu) <= HALF - 1 && Math.abs(cv) <= HALF - 1) {
                put(b, cu, 1, cv, BpSpec.of("hay_block").with("axis", "y"));
                if (b.rnd.nextBoolean()) b.barrel(cu + 1, gy(b, cu + 1, cv) + 1, cv, Dir.UP, BpBuilder.LOOT_SUPPLY, 450 + n);
            }
        }
    }

    // ------------------------------------------------------------------ окопы

    private static void trenches(BpBuilder b, int count, int len) {
        for (int n = 0; n < count; n++) {
            int face = b.rnd.nextInt(4);
            boolean alongV = (face & 1) == 0;
            int[] p = alongV ? spotRect(b, 4, len + 1) : spotRect(b, len + 1, 4);
            if (p == null) continue;
            trench(b, p[0], p[1], face, len);
        }
    }

    /** Окоп со сломом оси, дощатым полом, бруствером из мешков и обвалами. Ось — локальная V подстроителя. */
    private static void trench(BpBuilder b, int u, int v, int face, int len) {
        BpBuilder tb = b.child(u, v, face);
        int n = 2 * len + 1;
        int[] center = new int[n];
        int off = 0;
        for (int i = 0; i < n; i++) {
            if (i % 5 == 0 && i > 0) off = Math.max(-2, Math.min(2, off + b.rnd.nextInt(3) - 1));
            center[i] = off;
        }
        boolean[][] carved = new boolean[9][n];
        for (int i = 0; i < n; i++) {
            int vv = i - len;
            boolean collapsed = b.rnd.nextDouble() < 0.10 && i > 1 && i < n - 2;
            int prev = i > 0 ? center[i - 1] : center[i];
            int lo = Math.min(prev, center[i]) - 1, hi = Math.max(prev, center[i]) + 1;
            for (int lat = lo; lat <= hi; lat++) {
                if (collapsed) continue;
                int g = gy(tb, lat, vv);
                tb.air(lat, g + 2, vv);
                tb.air(lat, g + 1, vv);
                tb.air(lat, g, vv);
                tb.air(lat, g - 1, vv);
                tb.set(lat, g - 2, vv, tb.noise(lat, 0, vv, 5) < 0.4 ? tb.pal.planks : BpSpec.of("coarse_dirt"));
                if (tb.noise(lat, 1, vv, 6) < 0.05) tb.lantern(lat, g - 1, vv, false);
                carved[lat + 4][i] = true;
            }
        }
        for (int i = 0; i < n; i++) {
            int vv = i - len;
            for (int lat = -4; lat <= 4; lat++) {
                if (carved[lat + 4][i]) continue;
                boolean edge = (lat > -4 && carved[lat + 3][i]) || (lat < 4 && carved[lat + 5][i]);
                if (!edge) continue;
                double r = tb.noise(lat, 2, vv, 8);
                if (r < 0.30) continue;
                put(tb, lat, 1, vv, tb.pal.sandbag);
                if (r > 0.75) put(tb, lat, 2, vv, tb.pal.sandbag);
            }
        }
    }

    // ------------------------------------------------------------------ могилы, деревья, заграждения, мусор

    private static void graves(BpBuilder b, int clusters, int id) {
        for (int n = 0; n < clusters; n++) {
            int[] p = spotRect(b, 5, 3);
            if (p == null) continue;
            int count = 3 + b.rnd.nextInt(4);
            for (int i = 0; i < count; i++) {
                int gu = p[0] + (i % 3 - 1) * 4, gv = p[1] + (i / 3) * 3 - 1;
                int g = gy(b, gu, gv);
                b.set(gu, g, gv, BpSpec.of("coarse_dirt"));
                double r = b.noise(gu, 0, gv, 13);
                if (r < 0.65) {
                    BpSpec fence = BpSpec.of(b.pal.woodFence).with("waterlogged", false);
                    b.set(gu, g + 1, gv, fence);
                    b.set(gu, g + 2, gv, fence);
                    b.set(gu - 1, g + 2, gv, fence);
                    b.set(gu + 1, g + 2, gv, fence);
                } else if (r < 0.85) {
                    b.set(gu, g + 1, gv, BpSpec.of(b.pal.woodFence).with("waterlogged", false));
                } else {
                    b.set(gu, g + 1, gv, BpSpec.of("bone_block").with("axis", ((gu + gv) & 1) == 0 ? "x" : "z"));
                }
            }
        }
    }

    private static void snags(BpBuilder b, int count) {
        BpSpec trunk = BpSpec.of("stripped_" + b.pal.log).with("axis", "y");
        for (int n = 0; n < count; n++) {
            int[] p = spot(b, 2);
            if (p == null) continue;
            int u = p[0], v = p[1], g = gy(b, u, v);
            int h = 3 + b.rnd.nextInt(4);
            for (int k = 1; k <= h; k++) b.set(u, g + k, v, trunk);
            int branches = 1 + b.rnd.nextInt(2);
            for (int i = 0; i < branches; i++) {
                int dir = b.rnd.nextInt(4);
                int du = dir == 0 ? 1 : (dir == 1 ? -1 : 0), dv = dir == 2 ? 1 : (dir == 3 ? -1 : 0);
                String axis = du != 0 ? "x" : "z";
                b.set(u + du, g + h - 1 - i, v + dv, BpSpec.of("stripped_" + b.pal.log).with("axis", axis));
            }
        }
    }

    private static void barriers(BpBuilder b, int count) {
        for (int n = 0; n < count; n++) {
            int[] p = spotRect(b, 4, 1);
            if (p == null) continue;
            if (b.rnd.nextBoolean()) {
                CheckpointBuilder.wire(b, p[0] - 3, p[0] + 3, p[1]);
            } else {
                CheckpointBuilder.teeth(b, p[0] - 2, p[1]);
                CheckpointBuilder.teeth(b, p[0] + 2, p[1]);
            }
        }
    }

    private static void debris(BpBuilder b, int count) {
        for (int n = 0; n < count; n++) {
            int[] p = spot(b, 0);
            if (p == null) continue;
            int u = p[0], v = p[1], g = gy(b, u, v);
            double r = b.rnd.nextDouble();
            if (r < 0.28) b.set(u, g, v, scorch(b, u, v, 0.2));
            else if (r < 0.45) b.set(u, g + 1, v, BpSpec.of("cobweb"));
            else if (r < 0.62) b.set(u, g + 1, v, BpSpec.of("bone_block").with("axis", b.rnd.nextBoolean() ? "x" : "z"));
            else if (r < 0.78) b.set(u, g + 1, v, BpSpec.of("cobblestone"));
            else if (r < 0.90) b.set(u, g + 1, v, b.pal.sandbag);
            else b.set(u, g + 1, v, BpSpec.of("iron_bars"));
        }
    }
}
