import com.bobincraft.warzone.NaturalLandscape;
import com.bobincraft.warzone.WarzoneTerrain;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;

/** Headless area statistics. Run without Minecraft; deep means at least three water blocks. */
public final class ExportWaterwayStats {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Expected output JSON path and optional sampling step (default 2)");
        }
        int step = args.length == 2 ? Integer.parseInt(args[1]) : 2;
        if (step < 1 || 2048 % step != 0) throw new IllegalArgumentException("Step must divide 2048");
        long[] seeds = {0, 1, 42, 123456789, -734842};
        try (PrintWriter out = new PrintWriter(args[0], StandardCharsets.UTF_8)) {
            out.println("{\n  \"description\": \"Procedural diagnostics; not an in-game screenshot. Surface area, not water volume.\",");
            out.println("  \"size\": 2048, \"generationVersion\": 4, \"samplingStep\": " + step + ", \"shallowDepthBlocks\": [1, 2], \"seeds\": [");
            for (int seedIndex = 0; seedIndex < seeds.length; seedIndex++) {
                long seed = seeds[seedIndex];
                NaturalLandscape terrain = new WarzoneTerrain(seed, 2048, 4).naturalLandscape();
                WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
                EnumMap<WarzoneTerrain.WaterState, Integer> states = new EnumMap<>(WarzoneTerrain.WaterState.class);
                EnumMap<NaturalLandscape.RouteStyle, Integer> routes = new EnumMap<>(NaturalLandscape.RouteStyle.class);
                int[] depths = new int[64];
                int wet = 0, shallow = 0, elevated = 0;
                int minWaterLevel = Integer.MAX_VALUE, maxWaterLevel = Integer.MIN_VALUE;
                boolean finite = true, stillZero = true;
                for (int z = -1024; z < 1024; z += step) for (int x = -1024; x < 1024; x += step) {
                    terrain.sample(x, z, sample);
                    finite &= Double.isFinite(sample.flowX) && Double.isFinite(sample.flowZ);
                    int depth = sample.waterLevel - sample.height;
                    if (depth <= 0) continue;
                    wet++;
                    if (sample.waterLevel > WarzoneTerrain.SEA_LEVEL) elevated++;
                    minWaterLevel = Math.min(minWaterLevel, sample.waterLevel);
                    maxWaterLevel = Math.max(maxWaterLevel, sample.waterLevel);
                    if (depth <= 2) shallow++;
                    depths[Math.min(depths.length - 1, depth)]++;
                    states.merge(sample.waterState, 1, Integer::sum);
                    if (sample.waterState == WarzoneTerrain.WaterState.STILL) {
                        stillZero &= sample.flowX == 0 && sample.flowZ == 0;
                    }
                }
                double narrowest = Double.POSITIVE_INFINITY, widest = 0;
                for (NaturalLandscape.RouteSegment route : terrain.routes()) {
                    routes.merge(route.style, 1, Integer::sum);
                    narrowest = Math.min(narrowest, route.halfWidth * 2);
                    widest = Math.max(widest, route.halfWidth * 2);
                }
                double shallowFraction = shallow / (double) wet;
                out.println("    {\"seed\": " + seed + ", \"sampledWaterColumns\": " + wet
                        + ", \"elevatedWaterColumns\": " + elevated
                        + ", \"waterLevelRange\": [" + minWaterLevel + ", " + maxWaterLevel + "]"
                        + ", \"shallowColumns\": " + shallow + ", \"deepColumns\": " + (wet - shallow)
                        + ", \"shallowFraction\": " + shallowFraction + ", \"tributaries\": " + terrain.tributaryCount()
                        + ", \"finiteFlow\": " + finite + ", \"stillWaterHasZeroFlow\": " + stillZero + ",");
                out.println("     \"depthHistogram\": " + Arrays.toString(depths) + ",");
                out.println("     \"waterStates\": " + jsonMap(states) + ", \"routeSegmentsByStyle\": " + jsonMap(routes) + ",");
                out.println("     \"nominalRouteWidthRange\": [" + narrowest + ", " + widest + "]}"
                        + (seedIndex + 1 == seeds.length ? "" : ","));
                System.out.printf(Locale.ROOT,
                        "seed=%d shallow=%.2f%% deep=%.2f%% wet=%d tributaries=%d states=%s routes=%s width=%.2f..%.2f finite=%s stillZero=%s%n",
                        seed, shallowFraction * 100, (1 - shallowFraction) * 100, wet,
                        terrain.tributaryCount(), states, routes, narrowest, widest, finite, stillZero);
                out.flush();
            }
            out.println("  ]\n}");
        }
    }

    private static String jsonMap(EnumMap<?, Integer> values) {
        StringBuilder result = new StringBuilder("{");
        values.forEach((key, value) -> {
            if (result.length() > 1) result.append(", ");
            result.append('"').append(key).append("\": ").append(value);
        });
        return result.append('}').toString();
    }
}
