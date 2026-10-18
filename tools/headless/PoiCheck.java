import com.bobincraft.warzone.*;

import java.util.*;

/**
 * Headless-проверка дозорных блокпостов и лесных укрытий (чистая Java). Запуск: tools/headless/run.sh poi [число_сидов]
 * Для каждого объекта: детерминизм, нарезка по чанкам = целый чертёж, нет записей за bbox, ничего не висит,
 * нет «повисших» заборов/прутьев (у соединённых сторон есть сосед), укрытия не пересекают базы/дороги/воду.
 */
public class PoiCheck {
    static int fails = 0;

    static void fail(String m) {
        fails++;
        if (fails <= 400) System.out.println("FAIL: " + m);
    }

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 12;
        int towers = 0, hideouts = 0, checkpoints = 0, fields = 0;
        int[] cpVariant = new int[3], bfVariant = new int[3];
        int[] byVariant = new int[3];
        long cells = 0;
        int pruned = 0;
        long t0 = System.currentTimeMillis();
        Map<WarzoneBasePlan.SiteType, Set<Long>> uniq = new TreeMap<>();
        Map<WarzoneBasePlan.SiteType, Integer> cnt = new TreeMap<>();
        for (int si = 1; si <= seeds; si++) {
            long seed = si * 7919L + 13;
            WarzoneTerrain t = new WarzoneTerrain(seed, 1024);
            WarzoneTerrain t2 = new WarzoneTerrain(seed, 1024);
            List<Blueprint> all = new ArrayList<>();
            for (WarzoneBasePlan.Site s : t.basePlan().sites()) {
                Blueprint bp = t.blueprints().site(s);
                String msg = t.blueprints().pollMessage();
                if (bp == null) { fail("seed " + seed + " site #" + s.id + " failed: " + msg); continue; }
                Blueprint o = t2.blueprints().site(s);
                towers++;
                check(t, "seed " + seed + " " + s.type + "#" + s.id, bp, o);
                checkSpots(t, "seed " + seed + " " + s.type + "#" + s.id, bp, s);
                uniq.computeIfAbsent(s.type, k -> new HashSet<>()).add(bp.fingerprint());
                cnt.merge(s.type, 1, Integer::sum);
                all.add(bp);
                cells += bp.solidCount();
            }
            List<HideoutPlan.Hideout> hs = t.hideoutPlan().hideouts();
            if (!t2.hideoutPlan().hideouts().equals(hs)) fail("seed " + seed + ": hideout plan differs between runs");
            for (HideoutPlan.Hideout h : hs) {
                Blueprint bp = t.blueprints().hideout(h);
                String msg = t.blueprints().pollMessage();
                if (bp == null) { fail("seed " + seed + " hideout #" + h.id() + " failed: " + msg); continue; }
                hideouts++;
                byVariant[h.variant()]++;
                check(t, "seed " + seed + " hideout#" + h.id() + " v" + h.variant() + " @" + h.cx() + "," + h.cz(), bp,
                        t2.blueprints().hideout(h));
                cells += bp.solidCount();
                // место
                for (WarzoneBasePlan.Site s : t.basePlan().sites())
                    if (s.distanceTo(h.cx(), h.cz()) < s.blendRadius + 10) fail("hideout#" + h.id() + " too close to site " + s.id);
                if (t.height(h.cx(), h.cz()) < WarzoneTerrain.SEA_LEVEL + 2) fail("hideout#" + h.id() + " in water");
                if (!t.isReserved(h.cx(), h.cz())) fail("hideout#" + h.id() + " not reserved for flora");
                // пересечение bbox двух объектов
                for (Blueprint other : all) {
                    if (other == bp) continue;
                    boolean ov = bp.minX <= other.maxX() && bp.maxX() >= other.minX && bp.minZ <= other.maxZ() && bp.maxZ() >= other.minZ;
                    if (ov) fail("seed " + seed + " " + bp.name + " overlaps " + other.name);
                }
                all.add(bp);
            }
            // ---- этап (c): придорожные блокпосты и старые поля боёв
            PoiPlan pp = t.poiPlan(), pp2 = t2.poiPlan();
            if (!pp2.checkpoints().equals(pp.checkpoints()) || !pp2.battlefields().equals(pp.battlefields()))
                fail("seed " + seed + ": poi plan differs between runs");
            for (PoiPlan.Checkpoint c : pp.checkpoints()) {
                Blueprint bp = t.blueprints().checkpoint(c);
                String msg = t.blueprints().pollMessage();
                if (bp == null) { fail("seed " + seed + " checkpoint #" + c.id() + " failed: " + msg); continue; }
                checkpoints++;
                cpVariant[c.variant()]++;
                String tag = "seed " + seed + " checkpoint#" + c.id() + " v" + c.variant() + " @" + c.cx() + "," + c.cz();
                check(t, tag, bp, t2.blueprints().checkpoint(c));
                cells += bp.solidCount();
                if (t.basePlan().roadFactor(c.cx(), c.cz()) < 0.85) fail(tag + ": not on the road");
                if (t.height(c.cx(), c.cz()) < WarzoneTerrain.SEA_LEVEL + 2) fail(tag + ": in water");
                if (!t.isReserved(c.cx(), c.cz())) fail(tag + ": not reserved for flora");
                for (WarzoneBasePlan.Site site : t.basePlan().sites())
                    if (site.distanceTo(c.cx(), c.cz()) < site.blendRadius + 10) fail(tag + ": too close to site " + site.id);
                if (!lanePassable(t, bp, c)) fail(tag + ": road lane is blocked");
                overlaps(tag, bp, all);
                all.add(bp);
            }
            for (PoiPlan.Battlefield f : pp.battlefields()) {
                Blueprint bp = t.blueprints().battlefield(f);
                String msg = t.blueprints().pollMessage();
                if (bp == null) { fail("seed " + seed + " battlefield #" + f.id() + " failed: " + msg); continue; }
                fields++;
                bfVariant[f.variant()]++;
                String tag = "seed " + seed + " battlefield#" + f.id() + " v" + f.variant() + " @" + f.cx() + "," + f.cz();
                check(t, tag, bp, t2.blueprints().battlefield(f));
                cells += bp.solidCount();
                if (bp.solidCount() < 60) fail(tag + ": almost empty (" + bp.solidCount() + " solid cells)");
                if (t.height(f.cx(), f.cz()) < WarzoneTerrain.SEA_LEVEL + 2) fail(tag + ": in water");
                if (!t.isReserved(f.cx(), f.cz())) fail(tag + ": not reserved for flora");
                for (WarzoneBasePlan.Site site : t.basePlan().sites())
                    if (site.distanceTo(f.cx(), f.cz()) < site.blendRadius + 10) fail(tag + ": too close to site " + site.id);
                if (t.basePlan().roadDistance(f.cx(), f.cz()) < 14) fail(tag + ": on the road");
                overlaps(tag, bp, all);
                all.add(bp);
            }
        }
        System.out.println("seeds=" + seeds + " towers=" + towers + " hideouts=" + hideouts + " (variants " + Arrays.toString(byVariant)
                + ") solidCells=" + cells + " time=" + (System.currentTimeMillis() - t0) + "ms");
        System.out.println("  checkpoints=" + checkpoints + " (variants " + Arrays.toString(cpVariant) + "), battlefields=" + fields
                + " (variants " + Arrays.toString(bfVariant) + ")");
        for (var e : cnt.entrySet())
            System.out.println("  " + e.getKey() + ": " + e.getValue() + " built, " + uniq.get(e.getKey()).size() + " distinct layouts");
        System.out.println(fails == 0 ? "ALL CHECKS PASSED" : ("FAILURES: " + fails));
        if (fails != 0) System.exit(1);
    }

    /** Тактические точки: стоять можно (твёрдый пол под ногами, 2 свободных блока над ним). */
    static void checkSpots(WarzoneTerrain t, String tag, Blueprint bp, WarzoneBasePlan.Site s) {
        for (WarzoneBasePlan.TacticalSpot sp : s.spots) {
            int x = sp.x(), y = sp.y(), z = sp.z();
            if (!inBp(bp, x, y, z)) continue;   // вне чертежа (патруль по дороге и т.п.) — не проверяем
            boolean floor = solid(bp, t, x, y - 1, z);
            boolean feet = !solid(bp, t, x, y, z);
            boolean head = !solid(bp, t, x, y + 1, z);
            if (!(floor && feet && head)) fail(tag + ": spot " + sp.role() + " at " + x + "," + y + "," + z + " floor=" + floor + " feet=" + feet + " head=" + head);
        }
    }

    static void overlaps(String tag, Blueprint bp, List<Blueprint> all) {
        for (Blueprint other : all) {
            boolean ov = bp.minX <= other.maxX() && bp.maxX() >= other.minX && bp.minZ <= other.maxZ() && bp.maxZ() >= other.minZ;
            if (ov) fail(tag + " bbox overlaps " + other.name);
        }
    }

    static int rotX(int cx, int u, int v, int rot) {
        return switch (rot & 3) { case 1 -> cx - v; case 2 -> cx - u; case 3 -> cx + v; default -> cx + u; };
    }

    static int rotZ(int cz, int u, int v, int rot) {
        return switch (rot & 3) { case 1 -> cz + u; case 2 -> cz - v; case 3 -> cz - u; default -> cz + v; };
    }

    /** Полоса дороги (|u| <= halfWidth) проходима от одного края чертежа до другого: шаг <= 1, над полом 2 свободных блока. */
    static boolean lanePassable(WarzoneTerrain t, Blueprint bp, PoiPlan.Checkpoint c) {
        final int H = 14, hw = c.halfWidth();
        int w = 2 * hw + 1, len = 2 * H + 1;
        int[][] ground = new int[w][len];
        boolean[][] ok = new boolean[w][len];
        for (int iu = 0; iu < w; iu++) {
            for (int iv = 0; iv < len; iv++) {
                int x = rotX(c.cx(), iu - hw, iv - H, c.rotation()), z = rotZ(c.cz(), iu - hw, iv - H, c.rotation());
                int g = t.height(x, z);
                ground[iu][iv] = g;
                ok[iu][iv] = !solid(bp, t, x, g + 1, z) && !solid(bp, t, x, g + 2, z);
            }
        }
        boolean[][] seen = new boolean[w][len];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int iu = 0; iu < w; iu++) if (ok[iu][0]) { seen[iu][0] = true; q.add(new int[]{iu, 0}); }
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] cur = q.poll();
            if (cur[1] == len - 1) return true;
            for (int[] o : d) {
                int nu = cur[0] + o[0], nv = cur[1] + o[1];
                if (nu < 0 || nu >= w || nv < 0 || nv >= len || seen[nu][nv] || !ok[nu][nv]) continue;
                if (Math.abs(ground[nu][nv] - ground[cur[0]][cur[1]]) > 1) continue;
                seen[nu][nv] = true;
                q.add(new int[]{nu, nv});
            }
        }
        return false;
    }

    static boolean inBp(Blueprint bp, int x, int y, int z) {
        return x >= bp.minX && x <= bp.maxX() && y >= bp.minY && y <= bp.maxY() && z >= bp.minZ && z <= bp.maxZ();
    }

    /** Занята ли клетка (по чертежу, а вне него/без записи — по рельефу). Плиты и ступени считаем опорой. */
    static boolean solid(Blueprint bp, WarzoneTerrain t, int x, int y, int z) {
        BpSpec b = bp.get(x, y, z);
        if (b == null) return y <= t.height(x, z);
        if (b.isAir()) return false;
        String id = b.id;
        return !(id.contains("lantern") || id.contains("carpet") || id.contains("banner") || id.contains("button") || id.contains("ladder") || id.equals("snow") || id.contains("pressure_plate") || id.contains("rail"));
    }

    static void check(WarzoneTerrain t, String tag, Blueprint bp, Blueprint other) {
        Blueprint.Ground g = (x, z) -> t.height(x, z);
        if (bp.prunedCount != 0) fail(tag + ": builder left " + bp.prunedCount + " floating blocks (pruned) e.g. " + bp.prunedSample);
        if (bp.droppedWrites != 0) fail(tag + ": writes outside bbox = " + bp.droppedWrites);
        if (other == null || other.fingerprint() != bp.fingerprint()) fail(tag + ": not deterministic");
        List<int[]> fl = bp.floatingCells(g, null);
        if (!fl.isEmpty()) fail(tag + ": " + fl.size() + " floating blocks, first " + Arrays.toString(fl.get(0)));

        // нарезка по чанкам
        final long[] whole = {0};
        final int[] n = {0};
        bp.forEachCell((x, y, z, s, m) -> { whole[0] += Blueprint.hashOf(x, y, z, s, m); n[0]++; });
        final long[] sum = {0};
        final int[] cnt = {0};
        for (int cx = Math.floorDiv(bp.minX, 16); cx <= Math.floorDiv(bp.maxX(), 16); cx++) {
            for (int cz = Math.floorDiv(bp.minZ, 16); cz <= Math.floorDiv(bp.maxZ(), 16); cz++) {
                final int mnx = cx * 16, mnz = cz * 16;
                bp.forEachInChunk(mnx, mnz, (x, y, z, s, m) -> {
                    if (x < mnx || x > mnx + 15 || z < mnz || z > mnz + 15) fail(tag + ": cell outside chunk");
                    sum[0] += Blueprint.hashOf(x, y, z, s, m);
                    cnt[0]++;
                });
            }
        }
        if (sum[0] != whole[0] || cnt[0] != n[0]) fail(tag + ": chunk slices != whole");

        // заборы/прутья/стены: каждая заявленная сторона имеет соседа
        final String[] dirs = {"north", "east", "south", "west"};
        final int[][] d = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        bp.forEachCell((x, y, z, s, m) -> {
            if (!(s.isFence() || s.isPane() || s.isWall())) return;
            for (int i = 0; i < 4; i++) {
                String v = s.prop(dirs[i]);
                boolean on = v != null && !v.equals("false") && !v.equals("none");
                BpSpec nb = bp.get(x + d[i][0], y, z + d[i][1]);
                boolean has = nb != null && !nb.isAir();
                if (nb == null) has = y <= g.top(x + d[i][0], z + d[i][1]);
                if (on && !has) fail(tag + ": " + s + " at " + x + "," + y + "," + z + " joins empty " + dirs[i]);
            }
        });
    }
}
