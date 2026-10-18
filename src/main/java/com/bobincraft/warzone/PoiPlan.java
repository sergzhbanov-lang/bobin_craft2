package com.bobincraft.warzone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Лёгкие точки интереса этапа (c): придорожные блокпосты/баррикады и старые поля боёв. Как и {@link HideoutPlan}, это
 * отдельный список, не {@link WarzoneBasePlan.Site}: ни фракционных тактических точек, ни гарнизона до этапа 3.
 * Место выбирается детерминированно из seed; каждый объект — один чертёж ({@link CheckpointBuilder},
 * {@link BattlefieldBuilder}).
 */
public final class PoiPlan {
    public static final int CHECKPOINT_RESERVE = 10;
    public static final int BATTLEFIELD_RESERVE = 15;
    static final int ROAD_SEARCH_MIN_LEN = 12;
    static final int FIELD_CELL = 170;

    /** Придорожный пост: дорога идёт вдоль локальной оси V, барьер поперёк (U). */
    public record Checkpoint(int id, int cx, int cz, int baseY, int rotation, int variant, int halfWidth,
                             WarzoneTerrain.Region region, Faction faction, String name) {}

    /** Старое поле боя: воронки, остовы техники, окопы, могилы. */
    public record Battlefield(int id, int cx, int cz, int baseY, int rotation, int variant,
                              WarzoneTerrain.Region region, Faction faction, String name) {}

    private static final String[] CHECKPOINT_NAMES = {"Блокпост со шлагбаумом", "Дорожная баррикада", "Укреплённый кордон"};
    private static final String[] FIELD_NAMES = {"Поле танкового боя", "Окопная линия", "Разбитый обоз"};

    private final List<Checkpoint> checkpoints;
    private final List<Battlefield> fields;

    private PoiPlan(List<Checkpoint> c, List<Battlefield> f) {
        this.checkpoints = Collections.unmodifiableList(c);
        this.fields = Collections.unmodifiableList(f);
    }

    static PoiPlan empty() {
        return new PoiPlan(new ArrayList<>(), new ArrayList<>());
    }

    public List<Checkpoint> checkpoints() {
        return checkpoints;
    }

    public List<Battlefield> battlefields() {
        return fields;
    }

    public Checkpoint checkpointById(int id) {
        for (Checkpoint c : checkpoints) if (c.id() == id) return c;
        return null;
    }

    public Battlefield battlefieldById(int id) {
        for (Battlefield b : fields) if (b.id() == id) return b;
        return null;
    }

    /** Колонка в зоне, закрытой от случайной растительности. */
    public boolean reserves(int x, int z) {
        for (Checkpoint c : checkpoints) {
            int dx = x - c.cx(), dz = z - c.cz();
            if (dx * dx + dz * dz <= CHECKPOINT_RESERVE * CHECKPOINT_RESERVE) return true;
        }
        for (Battlefield b : fields) {
            int dx = x - b.cx(), dz = z - b.cz();
            if (dx * dx + dz * dz <= BATTLEFIELD_RESERVE * BATTLEFIELD_RESERVE) return true;
        }
        return false;
    }

    public List<Checkpoint> checkpointsForChunk(int chunkMinX, int chunkMinZ, int margin) {
        List<Checkpoint> out = null;
        int r = CheckpointBuilder.HALF + margin;
        for (Checkpoint c : checkpoints) {
            if (c.cx() + r >= chunkMinX && c.cx() - r <= chunkMinX + 15 && c.cz() + r >= chunkMinZ && c.cz() - r <= chunkMinZ + 15) {
                if (out == null) out = new ArrayList<>(2);
                out.add(c);
            }
        }
        return out == null ? Collections.emptyList() : out;
    }

    public List<Battlefield> battlefieldsForChunk(int chunkMinX, int chunkMinZ, int margin) {
        List<Battlefield> out = null;
        int r = BattlefieldBuilder.HALF + margin;
        for (Battlefield b : fields) {
            if (b.cx() + r >= chunkMinX && b.cx() - r <= chunkMinX + 15 && b.cz() + r >= chunkMinZ && b.cz() - r <= chunkMinZ + 15) {
                if (out == null) out = new ArrayList<>(2);
                out.add(b);
            }
        }
        return out == null ? Collections.emptyList() : out;
    }

    // ------------------------------------------------------------------ планирование

    static PoiPlan generate(WarzoneTerrain t, HideoutPlan hideouts) {
        WarzoneBasePlan plan = t.basePlan();
        WarzoneTerrain.Sample tmp = new WarzoneTerrain.Sample();
        List<Checkpoint> cps = new ArrayList<>();
        List<Battlefield> bfs = new ArrayList<>();

        // --- блокпосты на дорогах
        int idx = 0;
        for (WarzoneBasePlan.RoadSegment seg : plan.roads()) {
            idx++;
            double len = Math.hypot(seg.x1() - seg.x0(), seg.z1() - seg.z0());
            if (len < ROAD_SEARCH_MIN_LEN) continue;
            long h = WarzoneNoise.hash(t.seed + 4601, seg.x0() + idx, seg.z0());
            if (WarzoneNoise.unit(h) > 0.85) continue;
            for (int attempt = 0; attempt < 8; attempt++) {
                long ha = WarzoneNoise.mix(h + attempt * 7919L);
                double k = 0.15 + 0.70 * WarzoneNoise.unit(ha);
                int x = (int) Math.round(seg.x0() + (seg.x1() - seg.x0()) * k);
                int z = (int) Math.round(seg.z0() + (seg.z1() - seg.z0()) * k);
                int[] res = validCheckpoint(t, plan, hideouts, cps, x, z, tmp);
                if (res == null) continue;
                long hv = WarzoneNoise.mix(ha + 33);
                int variant = (int) ((hv & 0xFFFF) % 3);
                int hw = (int) Math.max(2, Math.min(4, Math.round(seg.halfWidth())));
                Faction f = tmp.region == WarzoneTerrain.Region.MARSH ? Faction.MARSH_REAVERS
                        : (((hv >>> 30) & 1) == 0 ? Faction.BLACK_CROWS : Faction.ASH_RAIDERS);
                cps.add(new Checkpoint(cps.size() + 1, x, z, res[0], res[1], variant, hw, tmp.region, f, CHECKPOINT_NAMES[variant]));
                break;
            }
        }

        // --- старые поля боёв
        int lo = t.min + 70, hi = t.max - 70;
        for (int gx = lo; gx < hi; gx += FIELD_CELL) {
            for (int gz = lo; gz < hi; gz += FIELD_CELL) {
                long h0 = WarzoneNoise.hash(t.seed + 4701, gx, gz);
                if (WarzoneNoise.unit(h0) > 0.78) continue;
                for (int attempt = 0; attempt < 8; attempt++) {
                    long h = WarzoneNoise.hash(t.seed + 4702 + attempt, gx, gz);
                    int x = gx + (int) (WarzoneNoise.unit(h) * FIELD_CELL);
                    int z = gz + (int) (WarzoneNoise.unit(WarzoneNoise.mix(h)) * FIELD_CELL);
                    int base = validField(t, plan, hideouts, cps, bfs, x, z, tmp);
                    if (base < 0) continue;
                    t.sample(x, z, tmp);
                    long hv = WarzoneNoise.mix(h + 29);
                    int variant = (int) ((hv & 0xFFFF) % 3);
                    int rot = (int) ((hv >>> 20) & 3);
                    Faction f = tmp.region == WarzoneTerrain.Region.MARSH ? Faction.MARSH_REAVERS
                            : (((hv >>> 30) & 1) == 0 ? Faction.BLACK_CROWS : Faction.ASH_RAIDERS);
                    bfs.add(new Battlefield(bfs.size() + 1, x, z, base, rot, variant, tmp.region, f, FIELD_NAMES[variant]));
                    break;
                }
            }
        }
        return new PoiPlan(cps, bfs);
    }

    /** {baseY, rotation} или null. Дорога должна реально идти вдоль локальной V (проверяется по roadFactor). */
    private static int[] validCheckpoint(WarzoneTerrain t, WarzoneBasePlan plan, HideoutPlan hideouts,
                                         List<Checkpoint> placed, int x, int z, WarzoneTerrain.Sample s) {
        final int sea = WarzoneTerrain.SEA_LEVEL;
        if (!t.inside(x, z) || t.edgeDistance(x, z) < 40) return null;
        if (Math.hypot(x - t.landingX(), z - t.landingZ()) < 60) return null;
        if (plan.roadFactor(x, z) < 0.85) return null;
        // направление дороги: перебор четырёх поворотов, V должна лежать вдоль дороги
        int rot = -1;
        for (int r = 0; r < 4 && rot < 0; r++) {
            boolean ok = true;
            for (int d = -8; d <= 8 && ok; d += 4) {
                if (plan.roadFactor(WarzoneBasePlan.rotX(x, 0, d, r), WarzoneBasePlan.rotZ(z, 0, d, r)) < 0.35) ok = false;
            }
            // поперёк дороги на расстоянии 7 блоков дороги быть не должно (иначе перекрёсток/диагональ)
            for (int sgn = -1; sgn <= 1 && ok; sgn += 2) {
                if (plan.roadFactor(WarzoneBasePlan.rotX(x, sgn * 7, 0, r), WarzoneBasePlan.rotZ(z, sgn * 7, 0, r)) > 0.05) ok = false;
            }
            if (ok) rot = r;
        }
        if (rot < 0) return null;
        if (!laneWalkable(t, x, z, rot, (int) Math.max(2, Math.min(4, Math.round(laneHalfWidth(plan, x, z)))))) return null;
        int[] hs = new int[7 * 7];
        int n = 0, mn = 999, mx = -999;
        for (int u = -12; u <= 12; u += 4) {
            for (int v = -12; v <= 12; v += 4) {
                int wx = WarzoneBasePlan.rotX(x, u, v, rot), wz = WarzoneBasePlan.rotZ(z, u, v, rot);
                t.sample(wx, wz, s);
                if (s.channel > 0.02 || s.scar > 0.1 || s.pool > 0.0 || s.pillar > 0.0 || s.oasis > 0.0
                        || s.mountain > 0.6 || s.basePad > 0.0 || s.roadBroken > 0.0) return null;
                if (s.height < sea + 2 || (s.region == WarzoneTerrain.Region.HIGHLANDS && s.height > 118)) return null;
                hs[n++] = s.height;
                mn = Math.min(mn, s.height);
                mx = Math.max(mx, s.height);
            }
        }
        if (mx - mn > 6) return null;
        t.sample(x, z, s);
        for (WarzoneBasePlan.Site site : plan.sites()) {
            if (site.distanceTo(x, z) < site.blendRadius + 14) return null;
        }
        for (Checkpoint o : placed) {
            if (Math.hypot(o.cx() - x, o.cz() - z) < 110.0) return null;
        }
        for (HideoutPlan.Hideout hd : hideouts.hideouts()) {
            if (Math.hypot(hd.cx() - x, hd.cz() - z) < 40.0) return null;
        }
        java.util.Arrays.sort(hs, 0, n);
        return new int[]{hs[n / 2], rot};
    }

    /** Полуширина дороги в точке: по ближайшему отрезку. */
    private static double laneHalfWidth(WarzoneBasePlan plan, int x, int z) {
        double best = Double.POSITIVE_INFINITY, hw = 2.2;
        for (WarzoneBasePlan.RoadSegment seg : plan.roads()) {
            double d = seg.rawDistanceTo(x, z);
            if (d < best) {
                best = d;
                hw = seg.halfWidth();
            }
        }
        return hw;
    }

    /** По полосе дороги (|u| <= hw) можно пройти вдоль V от края до края: перепад соседних колонок не больше 1. */
    private static boolean laneWalkable(WarzoneTerrain t, int x, int z, int rot, int hw) {
        final int H = CheckpointBuilder.HALF;
        int w = 2 * hw + 1, len = 2 * H + 1;
        int[][] g = new int[w][len];
        for (int iu = 0; iu < w; iu++)
            for (int iv = 0; iv < len; iv++)
                g[iu][iv] = t.height(WarzoneBasePlan.rotX(x, iu - hw, iv - H, rot), WarzoneBasePlan.rotZ(z, iu - hw, iv - H, rot));
        boolean[][] seen = new boolean[w][len];
        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
        for (int iu = 0; iu < w; iu++) {
            seen[iu][0] = true;
            q.add(new int[]{iu, 0});
        }
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (c[1] == len - 1) return true;
            for (int[] o : d) {
                int nu = c[0] + o[0], nv = c[1] + o[1];
                if (nu < 0 || nu >= w || nv < 0 || nv >= len || seen[nu][nv]) continue;
                if (Math.abs(g[nu][nv] - g[c[0]][c[1]]) > 1) continue;
                seen[nu][nv] = true;
                q.add(new int[]{nu, nv});
            }
        }
        return false;
    }

    /** baseY поля боя или -1. */
    private static int validField(WarzoneTerrain t, WarzoneBasePlan plan, HideoutPlan hideouts, List<Checkpoint> cps,
                                  List<Battlefield> placed, int x, int z, WarzoneTerrain.Sample s) {
        final int sea = WarzoneTerrain.SEA_LEVEL;
        final int R = BattlefieldBuilder.HALF - 3;
        if (!t.inside(x, z) || t.edgeDistance(x, z) < 60) return -1;
        if (Math.hypot(x - t.landingX(), z - t.landingZ()) < 90) return -1;
        t.sample(x, z, s);
        if (s.region == WarzoneTerrain.Region.HIGHLANDS && (s.height > 105 || s.mountain > 0.4)) return -1;
        if (t.forest(x, z) > 0.65) return -1;
        int[] hs = new int[25];
        int n = 0, mn = 999, mx = -999;
        for (int a = 0; a < 24; a++) {
            double ang = a * Math.PI / 12.0;
            double rr = (a & 1) == 0 ? R : R * 0.5;
            int sx = x + (int) Math.round(Math.cos(ang) * rr), sz = z + (int) Math.round(Math.sin(ang) * rr);
            if (!sample(t, sx, sz, s)) return -1;
            hs[n++] = s.height;
            mn = Math.min(mn, s.height);
            mx = Math.max(mx, s.height);
        }
        if (!sample(t, x, z, s)) return -1;
        hs[n++] = s.height;
        mn = Math.min(mn, s.height);
        mx = Math.max(mx, s.height);
        if (mx - mn > 8) return -1;
        for (WarzoneBasePlan.Site site : plan.sites()) {
            if (site.distanceTo(x, z) < site.blendRadius + R + 8) return -1;
        }
        if (plan.roadDistance(x, z) < R + 6) return -1;
        for (Battlefield o : placed) {
            if (Math.hypot(o.cx() - x, o.cz() - z) < 110.0) return -1;
        }
        for (Checkpoint c : cps) {
            if (Math.hypot(c.cx() - x, c.cz() - z) < R + CHECKPOINT_RESERVE + 14) return -1;
        }
        for (HideoutPlan.Hideout hd : hideouts.hideouts()) {
            if (Math.hypot(hd.cx() - x, hd.cz() - z) < R + HideoutPlan.RESERVE_RADIUS + 10) return -1;
        }
        java.util.Arrays.sort(hs, 0, n);
        return hs[n / 2];
    }

    private static boolean sample(WarzoneTerrain t, int x, int z, WarzoneTerrain.Sample s) {
        if (!t.inside(x, z)) return false;
        t.sample(x, z, s);
        if (s.channel > 0.02 || s.pool > 0.0 || s.pillar > 0.0 || s.oasis > 0.0 || s.mountain > 0.5
                || s.road > 0.0 || s.path > 0.0 || s.basePad > 0.0) return false;
        return s.height >= WarzoneTerrain.SEA_LEVEL + 2;
    }
}
