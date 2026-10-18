import com.bobincraft.warzone.*;

import java.util.*;

/**
 * Headless-самопроверка чертежей (чистая Java, без Minecraft). Запуск: tools/headless/run.sh [число_сидов]
 * Проверяет для КАЖДОГО моста на нескольких сидах:
 *  1. детерминизм: чертёж, построенный на свежем WarzoneTerrain, побитово совпадает;
 *  2. нарезка по чанкам: срезы всех чанков в двух разных (перемешанных) порядках дают один и тот же мир,
 *     равный целому чертежу; каждая клетка попадает ровно в один чанк (в т.ч. при отрицательных координатах);
 *  3. ничего не висит: каждый блок связан с землёй (кроме досок на воде);
 *  4. концы моста стоят на суше вровень с землёй;
 *  5. целый мост: настил непрерывен; боевой: РОВНО ОДИН провал, обломки только на воде;
 *  6. бык доходит до реального дна (нижний блок на дне или выше него на 1), настил не ниже SEA_LEVEL+2;
 *  7. заборы: свойства north/east/south/west согласованы с соседями; запись за bbox = 0.
 */
public class BlueprintCheck {
    static int fails = 0;

    static void fail(String msg) {
        fails++;
        if (fails <= 40) System.out.println("FAIL: " + msg);
    }

    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 40;
        int total = 0, built = 0, rejected = 0, damaged = 0, intact = 0, floatingBlocks = 0;
        Map<String, Integer> reasons = new TreeMap<>();
        long cells = 0;
        int maxLen = 0;
        long t0 = System.currentTimeMillis();
        for (int si = 1; si <= seeds; si++) {
            long seed = si * 7919L + 13;
            WarzoneTerrain t = new WarzoneTerrain(seed, 1024);
            WarzoneTerrain t2 = new WarzoneTerrain(seed, 1024);       // «другой запуск сервера»
            List<WarzoneBasePlan.Bridge> list = t.basePlan().bridges();
            // другой порядок построения у второго терреина
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) order.add(i);
            Collections.shuffle(order, new Random(seed));
            for (int i : order) t2.blueprints().bridge(i);

            for (int i = 0; i < list.size(); i++) {
                total++;
                BridgeBlueprint bb = t.blueprints().bridge(i);
                String msg = t.blueprints().pollMessage();
                if (bb == null) {
                    rejected++;
                    if (msg != null) reasons.merge(msg.replaceAll("bridge #\\d+", "bridge"), 1, Integer::sum);
                    if (msg != null && msg.startsWith("ERR")) fail("seed " + seed + " " + msg);
                    continue;
                }
                built++;
                if (bb.damaged) damaged++; else intact++;
                maxLen = Math.max(maxLen, bb.length);
                String tag = "seed " + seed + " bridge#" + i + (bb.damaged ? " (damaged)" : "") + " L=" + bb.length;
                checkOne(t, t2, i, bb, tag);
                cells += bb.blueprint.solidCount();
            }
        }
        System.out.println("seeds=" + seeds + " bridges=" + total + " built=" + built + " (intact " + intact + ", damaged " + damaged
                + ") rejected=" + rejected + " maxLen=" + maxLen + " solidCells=" + cells
                + " time=" + (System.currentTimeMillis() - t0) + "ms");
        for (var e : reasons.entrySet()) System.out.println("  rejected: " + e.getKey() + " x" + e.getValue());
        System.out.println(fails == 0 ? "ALL CHECKS PASSED" : ("FAILURES: " + fails));
        if (fails != 0) System.exit(1);
    }

    static void checkOne(WarzoneTerrain t, WarzoneTerrain t2, int idx, BridgeBlueprint bb, String tag) {
        Blueprint bp = bb.blueprint;
        final int SEA = WarzoneTerrain.SEA_LEVEL;
        Blueprint.Ground ground = (x, z) -> t.height(x, z);

        if (bp.droppedWrites != 0) fail(tag + ": writes outside bbox = " + bp.droppedWrites);

        // 1. детерминизм
        BridgeBlueprint other = t2.blueprints().bridge(idx);
        if (other == null || other.blueprint.fingerprint() != bp.fingerprint()
                || other.blueprint.solidCount() != bp.solidCount()) fail(tag + ": blueprint differs between two terrain instances");

        // 2. нарезка по чанкам, два порядка
        long whole = 0;
        Map<Long, String> world = new HashMap<>();
        final long[] sum = {0};
        bp.forEachCell((x, y, z, s, m) -> {
            sum[0] += Blueprint.hashOf(x, y, z, s, m);
            world.put(BridgeBlueprint.key(x, y, z), s + "/" + m);
        });
        whole = sum[0];
        int cx0 = Math.floorDiv(bp.minX, 16) - 1, cx1 = Math.floorDiv(bp.maxX(), 16) + 1;
        int cz0 = Math.floorDiv(bp.minZ, 16) - 1, cz1 = Math.floorDiv(bp.maxZ(), 16) + 1;
        List<int[]> chunks = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) chunks.add(new int[]{cx, cz});
        for (int pass = 0; pass < 2; pass++) {
            Collections.shuffle(chunks, new Random(idx * 31L + pass));
            Map<Long, String> rebuilt = new HashMap<>();
            final long[] chunkSum = {0};
            final int[] dup = {0};
            for (int[] c : chunks) {
                final int mnx = c[0] * 16, mnz = c[1] * 16;
                bp.forEachInChunk(mnx, mnz, (x, y, z, s, m) -> {
                    if (x < mnx || x > mnx + 15 || z < mnz || z > mnz + 15) dup[0]++;       // вышли за чанк
                    String prev = rebuilt.put(BridgeBlueprint.key(x, y, z), s + "/" + m);
                    if (prev != null) dup[0]++;                                              // клетка в двух чанках
                    chunkSum[0] += Blueprint.hashOf(x, y, z, s, m);
                });
            }
            if (dup[0] != 0) fail(tag + ": chunk slicing produced " + dup[0] + " out-of-chunk/duplicate cells");
            if (!rebuilt.equals(world) || chunkSum[0] != whole) fail(tag + ": chunk slices (order " + pass + ") != whole blueprint");
        }

        // 3. ничего не висит
        List<int[]> fl = bp.floatingCells(ground, bb.debris::contains);
        if (!fl.isEmpty()) fail(tag + ": " + fl.size() + " floating blocks, first at " + Arrays.toString(fl.get(0)));
        for (long k : bb.debris) {
            int y = (int) (k & 0xFFF), z = (int) ((k >> 12) & 0x3FFFFFF), x = (int) ((k >> 38) & 0x3FFFFFF);
            if (x >= (1 << 25)) x -= (1 << 26);
            if (z >= (1 << 25)) z -= (1 << 26);
            if (y != SEA) fail(tag + ": debris not on water level");
            if (t.height(x, z) > SEA - 2) fail(tag + ": debris over shallow/land at " + x + "," + z);
        }
        if (!bb.damaged && !bb.debris.isEmpty()) fail(tag + ": intact bridge has debris");

        // 4. концы: настил на оси вровень с землёй (профиль стыкуется с дорогой), берег — суша над водой
        for (int e = 0; e < 2; e++) {
            int x = e == 0 ? bb.anchorAX : bb.anchorBX, z = e == 0 ? bb.anchorAZ : bb.anchorBZ, dy = e == 0 ? bb.deckYA : bb.deckYB;
            int g = e == 0 ? bb.axisGroundA : bb.axisGroundB, ady = e == 0 ? bb.axisDeckYA : bb.axisDeckYB;
            BpSpec s = bp.get(x, dy, z);
            if (s == null || !(s.id.endsWith("planks") || s.id.endsWith("slab"))) fail(tag + ": no deck block at anchor " + e);
            if (g < SEA + 1) fail(tag + ": anchor " + e + " is not on dry ground (h=" + g + ")");
            if (Math.abs(ady - g) > 1) fail(tag + ": anchor " + e + " axis deck y=" + ady + " not flush with ground " + g);
        }

        // 5. настил: целый непрерывен, боевой — один провал
        int runs = 0;
        boolean prev = true;
        for (int j = 1; j < bb.length; j++) {          // крайние отметки оси могут не иметь центра колонки на диагонали
            if (!bb.arcPresent[j] && prev) runs++;
            prev = bb.arcPresent[j];
        }
        if (!(bb.arcPresent[0] || bb.arcPresent[1]) || !(bb.arcPresent[bb.length] || bb.arcPresent[bb.length - 1])) fail(tag + ": bridge end rows missing");
        // настил по плану: связные куски (8-соседство, перепад ≤ 1) — целый мост = 1 кусок, боевой = РОВНО 2 (по одному у каждого берега)
        Map<Long, Integer> deckAt = new HashMap<>();
        bp.forEachCell((x, y, z, sp, md) -> {
            boolean plank = sp.id.endsWith("planks") || sp.id.endsWith("_slab");
            boolean wet = "true".equals(sp.prop("waterlogged"));
            if (plank && !wet && y >= SEA + 2) deckAt.put(BridgeBlueprint.key(x, 0, z), y);
        });
        Set<Long> seen = new HashSet<>();
        int comps = 0;
        boolean[] anchorInComp = new boolean[2];
        for (long start : deckAt.keySet()) {
            if (seen.contains(start)) continue;
            comps++;
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(start);
            seen.add(start);
            while (!q.isEmpty()) {
                long k = q.poll();
                int z = (int) ((k >> 12) & 0x3FFFFFF), x = (int) ((k >> 38) & 0x3FFFFFF);
                if (x >= (1 << 25)) x -= (1 << 26);
                if (z >= (1 << 25)) z -= (1 << 26);
                if (x == bb.anchorAX && z == bb.anchorAZ) anchorInComp[0] = true;
                if (x == bb.anchorBX && z == bb.anchorBZ) anchorInComp[1] = true;
                int y = deckAt.get(k);
                for (int ox = -1; ox <= 1; ox++) for (int oz = -1; oz <= 1; oz++) {
                    if (ox == 0 && oz == 0) continue;
                    long nk = BridgeBlueprint.key(x + ox, 0, z + oz);
                    Integer ny = deckAt.get(nk);
                    if (ny != null && Math.abs(ny - y) <= 1 && seen.add(nk)) q.add(nk);
                }
            }
        }
        if (bb.damaged) {
            if (comps != 2) fail(tag + ": damaged bridge deck has " + comps + " pieces (expected exactly 2: one per bank)");
            if (bb.gapStart < 0 || bb.gapEnd - bb.gapStart < 3) fail(tag + ": bad gap bounds");
            for (int j = bb.gapStart + 2; j <= bb.gapEnd - 2; j++) if (bb.arcPresent[j]) fail(tag + ": deck inside gap at " + j);
        } else {
            if (comps != 1) fail(tag + ": intact bridge deck has " + comps + " pieces");
            if (runs != 0) fail(tag + ": intact bridge has a gap");
        }

        // 6/7. быки, высота настила, заборы
        final int[] pillarColsBelowDeck = {0};
        bp.forEachCell((x, y, z, s, m) -> {
            if (s.id.endsWith("planks") || s.id.endsWith("_slab")) {
                if (s.prop("waterlogged") == null || s.prop("waterlogged").equals("false")) {
                    if (y < SEA + 2 && !bb.debris.contains(BridgeBlueprint.key(x, y, z)) && y > t.height(x, z) + 2)
                        fail(tag + ": deck too low at " + x + "," + y + "," + z);
                }
            }
            if (s.isFence()) {
                String[] dn = {"north", "east", "south", "west"};
                int[][] dd = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
                for (int d = 0; d < 4; d++) {
                    BpSpec n = bp.get(x + dd[d][0], y, z + dd[d][1]);
                    boolean expect = n != null ? (n.isFence() || n.isFullBlock()) : y <= t.height(x + dd[d][0], z + dd[d][1]);
                    if (!String.valueOf(expect).equals(s.prop(dn[d]))) fail(tag + ": fence at " + x + "," + y + "," + z + " side " + dn[d] + " inconsistent");
                }
            }
            if (s.id.equals("spruce_log") && "y".equals(s.prop("axis")) && bp.get(x, y - 1, z) == null) {
                // нижний блок быка обязан лежать на/в земле
                if (y > t.height(x, z) + 1) fail(tag + ": pillar bottom floats at " + x + "," + y + "," + z);
            }
        });
    }
}
