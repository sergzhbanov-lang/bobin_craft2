import com.bobincraft.warzone.*;

import java.util.*;

/**
 * Headless-проверка флоры на РЕАЛЬНОМ рельефе: стволы не должны уходить в скалу, обрываться в воздухе или висеть на обрывах.
 * Запуск: tools/headless/run.sh flora [число_сидов]
 */
public class FloraCheck {
    public static void main(String[] args) {
        int seeds = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        long totalPlants = 0, badPlants = 0, inRock = 0, orphan = 0, steep = 0, treesTotal = 0;
        Map<String, int[]> byKind = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (int si = 1; si <= seeds; si++) {
            WarzoneTerrain t = new WarzoneTerrain(si * 7919L + 13, 1024);
            for (int cz = 4; cz < 60; cz += 2) {
                for (int cx = 4; cx < 60; cx += 2) {
                    for (var rep : FloraProbe.check(t, cx * 16, cz * 16)) {
                        totalPlants++;
                        int[] k = byKind.computeIfAbsent(rep.kind, x -> new int[4]);
                        k[0]++;
                        if (rep.inRock > 0) { k[1]++; inRock += rep.inRock; }
                        if (rep.orphan > 0) { k[2]++; orphan += rep.orphan; }
                        if (rep.steep) { k[3]++; steep++; }
                        if (rep.inRock > 0 || rep.orphan > 0) {
                            badPlants++;
                            if (samples.size() < 8) samples.add(rep.toString());
                        }
                    }
                }
            }
        }
        System.out.println("plants=" + totalPlants + " bad=" + badPlants + " (" + (totalPlants == 0 ? 0 : 100 * badPlants / totalPlants) + "%) logsInRock=" + inRock
                + " orphanLogs=" + orphan + " steepRoots=" + steep + " time=" + (System.currentTimeMillis() - t0) + "ms");
        for (var e : byKind.entrySet()) System.out.println(String.format("  %-16s n=%-6d badInRock=%-5d badOrphan=%-5d steep=%d", e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]));
        for (String s : samples) System.out.println("  e.g. " + s);
        if (badPlants != 0) { System.out.println("FLORA FAILURES: " + badPlants); System.exit(1); }
        System.out.println("FLORA CHECKS PASSED");
    }
}
