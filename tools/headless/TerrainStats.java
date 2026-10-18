import com.bobincraft.warzone.*;
import java.util.*;

/** Статистика рельефа: регионы, вода, глубины, уклоны, число баз по типам. Запуск: DBG=tools/headless/TerrainStats.java sh tools/headless/run.sh */
public class TerrainStats {
    public static void main(String[] a) {
        int seeds = 6;
        for (int si = 1; si <= seeds; si++) {
            long seed = si * 7919L + 13;
            WarzoneTerrain t = new WarzoneTerrain(seed, 1024);
            Map<WarzoneTerrain.Region, int[]> reg = new EnumMap<>(WarzoneTerrain.Region.class);
            int n = 0, water = 0, deep = 0, deeper = 0, steepW = 0, maxH = 0, minH = 999;
            long sumSlope = 0; int slopeN = 0, cliff = 0;
            int marshWater = 0, marshN = 0, desertN = 0, badN = 0;
            int[] hist = new int[26];
            WarzoneTerrain.Sample s = new WarzoneTerrain.Sample();
            for (int x = -440; x < 440; x += 3) {
                for (int z = -440; z < 440; z += 3) {
                    t.sample(x, z, s);
                    n++;
                    reg.computeIfAbsent(s.region, k -> new int[1])[0]++;
                    int h = s.height;
                    maxH = Math.max(maxH, h); minH = Math.min(minH, h);
                    hist[Math.min(25, h / 10)]++;
                    if (h < 62) { water++; if (62 - h >= 6) deep++; if (62 - h >= 10) deeper++; }
                    if (s.region == WarzoneTerrain.Region.MARSH) { marshN++; if (h < 62) marshWater++; }
                    WarzoneTerrain.Sample s2 = t.sample(x + 3, z);
                    WarzoneTerrain.Sample s3 = t.sample(x, z + 3);
                    int d = Math.max(Math.abs(s2.height - h), Math.abs(s3.height - h));
                    sumSlope += d; slopeN++;
                    if (d >= 9) cliff++;
                    if (h < 62 && d >= 4) steepW++;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (var e : reg.entrySet()) sb.append(e.getKey()).append('=').append(100 * e.getValue()[0] / n).append("% ");
            Map<String, Integer> types = new TreeMap<>();
            for (WarzoneBasePlan.Site st : t.basePlan().sites()) types.merge(st.type.name(), 1, Integer::sum);
            System.out.println("seed " + seed + ": " + sb + "water=" + 100 * water / n + "% deep>=6:" + 100 * deep / n + "% deep>=10:" + 100 * deeper / n
                    + "% marshWater=" + (marshN == 0 ? 0 : 100 * marshWater / marshN) + "% h=[" + minH + ".." + maxH + "] avgSlope3=" + (sumSlope / (double) slopeN)
                    + " cliff3>=9:" + 100.0 * cliff / n + "% steepWater:" + 100 * steepW / Math.max(1, water) + "% bridges=" + t.basePlan().bridges().size()
                    + " sites=" + types);
        }
    }
}
