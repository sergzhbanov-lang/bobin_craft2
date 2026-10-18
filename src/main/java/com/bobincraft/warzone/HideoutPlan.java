package com.bobincraft.warzone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Лесные укрытия (хижина, засидка на сваях, бивак) — лёгкие точки интереса: отдельный список, не {@link WarzoneBasePlan.Site}
 * (ни фракций, ни тактических точек до этапа 3). Место выбирается детерминированно из seed: лес, ровная сухая земля,
 * вдали от баз, дорог, рек и друг от друга. Каждое укрытие — один чертёж ({@link HideoutBuilder}).
 */
public final class HideoutPlan {
    /** Радиус зоны, закрытой от случайной растительности. */
    public static final int RESERVE_RADIUS = 11;
    static final int CELL = 140;

    public record Hideout(int id, int cx, int cz, int baseY, int rotation, int variant,
                          WarzoneTerrain.Region region, Faction faction, String name) {}

    private static final String[] VARIANT_NAMES = {"Лесная хижина", "Засидка на сваях", "Бивак в чаще"};

    private final List<Hideout> list;

    private HideoutPlan(List<Hideout> list) {
        this.list = Collections.unmodifiableList(list);
    }

    static HideoutPlan empty() {
        return new HideoutPlan(new ArrayList<>());
    }

    public List<Hideout> hideouts() {
        return list;
    }

    public Hideout byId(int id) {
        for (Hideout h : list) if (h.id() == id) return h;
        return null;
    }

    /** Колонка попадает в зону укрытия (с запасом). */
    public boolean reserves(int x, int z) {
        for (Hideout h : list) {
            int dx = x - h.cx(), dz = z - h.cz();
            if (dx * dx + dz * dz <= RESERVE_RADIUS * RESERVE_RADIUS) return true;
        }
        return false;
    }

    public List<Hideout> forChunk(int chunkMinX, int chunkMinZ, int margin) {
        List<Hideout> out = null;
        int r = HideoutBuilder.HALF + margin;
        for (Hideout h : list) {
            if (h.cx() + r >= chunkMinX && h.cx() - r <= chunkMinX + 15 && h.cz() + r >= chunkMinZ && h.cz() - r <= chunkMinZ + 15) {
                if (out == null) out = new ArrayList<>(2);
                out.add(h);
            }
        }
        return out == null ? Collections.emptyList() : out;
    }

    static HideoutPlan generate(WarzoneTerrain t) {
        List<Hideout> out = new ArrayList<>();
        {
            WarzoneBasePlan plan = t.basePlan();
            int lo = t.min + 56, hi = t.max - 56;
            WarzoneTerrain.Sample tmp = new WarzoneTerrain.Sample();
            for (int gx = lo; gx < hi; gx += CELL) {
                for (int gz = lo; gz < hi; gz += CELL) {
                    long h0 = WarzoneNoise.hash(t.seed + 4401, gx, gz);
                    if (WarzoneNoise.unit(h0) > 0.80) continue;
                    for (int attempt = 0; attempt < 8; attempt++) {
                        long h = WarzoneNoise.hash(t.seed + 4402 + attempt, gx, gz);
                        int x = gx + (int) (WarzoneNoise.unit(h) * CELL);
                        int z = gz + (int) (WarzoneNoise.unit(WarzoneNoise.mix(h)) * CELL);
                        int base = validBase(t, plan, out, x, z, tmp);
                        if (base < 0) continue;
                        t.sample(x, z, tmp);
                        long hv = WarzoneNoise.mix(h + 17);
                        int variant = (int) (hv & 0xFFFF) % 3;
                        int rot = (int) ((hv >>> 20) & 3);
                        Faction f = tmp.region == WarzoneTerrain.Region.MARSH ? Faction.MARSH_REAVERS
                                : (((hv >>> 30) & 1) == 0 ? Faction.BLACK_CROWS : Faction.ASH_RAIDERS);
                        out.add(new Hideout(out.size() + 1, x, z, base, rot, variant, tmp.region, f, VARIANT_NAMES[variant]));
                        break;
                    }
                }
            }
        }
        return new HideoutPlan(out);
    }

    /** baseY укрытия или -1, если место не подходит. */
    private static int validBase(WarzoneTerrain t, WarzoneBasePlan plan, List<Hideout> placed, int x, int z,
                                 WarzoneTerrain.Sample s) {
        final int sea = WarzoneTerrain.SEA_LEVEL;
        if (!t.inside(x, z) || t.edgeDistance(x, z) < 48) return -1;
        if (Math.hypot(x - t.landingX(), z - t.landingZ()) < 70) return -1;
        t.sample(x, z, s);
        if (s.region == WarzoneTerrain.Region.BADLANDS) return -1;
        if (s.region == WarzoneTerrain.Region.HIGHLANDS && (s.height > 108 || s.mountain > 0.5)) return -1;
        double need = s.region == WarzoneTerrain.Region.MARSH ? 0.25 : 0.55;
        if (t.forest(x, z) < need) return -1;
        // рельеф в диске радиуса 8: сухо, ровно, без рек, воронок, дорог, площадок баз
        int[] hs = new int[17];
        int n = 0, mn = 999, mx = -999;
        int[][] offs = {{0, 0}, {4, 0}, {-4, 0}, {0, 4}, {0, -4}, {3, 3}, {-3, 3}, {3, -3}, {-3, -3},
                {8, 0}, {-8, 0}, {0, 8}, {0, -8}, {6, 6}, {-6, 6}, {6, -6}, {-6, -6}};
        for (int[] o : offs) {
            t.sample(x + o[0], z + o[1], s);
            if (s.channel > 0.02 || s.scar > 0.1 || s.road > 0.0 || s.path > 0.0 || s.basePad > 0.0
                    || s.pool > 0.0 || s.pillar > 0.0 || s.oasis > 0.0 || s.mountain > 0.6) return -1;
            if (s.height < sea + 2) return -1;
            hs[n++] = s.height;
            mn = Math.min(mn, s.height);
            mx = Math.max(mx, s.height);
        }
        if (mx - mn > 4) return -1;
        for (WarzoneBasePlan.Site site : plan.sites()) {
            if (site.distanceTo(x, z) < site.blendRadius + 18) return -1;
        }
        if (plan.roadDistance(x, z) < 18.0) return -1;
        for (Hideout o : placed) {
            if (Math.hypot(o.cx() - x, o.cz() - z) < 70.0) return -1;
        }
        java.util.Arrays.sort(hs);
        return hs[hs.length / 2];
    }
}
