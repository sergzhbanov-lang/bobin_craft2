import com.bobincraft.warzone.NaturalLandscape;
import com.bobincraft.warzone.WarzoneTerrain;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

/** Export procedural diagnostics without loading Minecraft or generating chunks. */
public final class ExportNaturalTerrain {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException("Expected output CSV path, optional seed (42), optional step (4)");
        }
        long seed = args.length >= 2 ? Long.parseLong(args[1]) : 42;
        int step = args.length >= 3 ? Integer.parseInt(args[2]) : 4;
        if (step < 1 || 2048 % step != 0) throw new IllegalArgumentException("Step must divide 2048");
        NaturalLandscape terrain = new WarzoneTerrain(seed, 2048, 4).naturalLandscape();
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        int[][][] waterPatches = new int[8][8][WarzoneTerrain.WaterState.values().length];
        try (PrintWriter out = new PrintWriter(args[0], StandardCharsets.UTF_8)) {
            header(out);
            for (int z = -1024; z < 1024; z += step) for (int x = -1024; x < 1024; x += step) {
                terrain.sample(x, z, sample);
                row(out, terrain, x, z, sample);
                if (sample.height < sample.waterLevel) {
                    waterPatches[(x + 1024) / 256][(z + 1024) / 256][sample.waterState.ordinal()]++;
                }
            }
        }
        String prefix = Path.of(args[0]).toString().replaceFirst("\\.csv$", "");
        int detailX = 0, detailZ = 0;
        double best = -1;
        for (int px = 1; px < 7; px++) for (int pz = 1; pz < 7; pz++) {
            int count = 0, variety = 0;
            for (int state = 1; state < waterPatches[px][pz].length; state++) {
                count += waterPatches[px][pz][state];
                if (waterPatches[px][pz][state] > 2) variety++;
            }
            double score = variety * 100 + Math.sqrt(count);
            if (score > best) {
                best = score;
                detailX = -1024 + px * 256;
                detailZ = -1024 + pz * 256;
            }
        }
        try (PrintWriter out = new PrintWriter(prefix + "-water-detail.csv", StandardCharsets.UTF_8)) {
            header(out);
            for (int z = detailZ; z < detailZ + 256; z++) for (int x = detailX; x < detailX + 256; x++) {
                terrain.sample(x, z, sample);
                row(out, terrain, x, z, sample);
            }
        }
        try (PrintWriter out = new PrintWriter(prefix + "-routes.csv", StandardCharsets.UTF_8)) {
            out.println("x1,z1,x2,z2,style,width,wear,id");
            for (NaturalLandscape.RouteSegment route : terrain.routes()) {
                out.printf(Locale.ROOT, "%d,%d,%d,%d,%d,%.4f,%.4f,%d%n", route.x1, route.z1,
                        route.x2, route.z2, route.style.ordinal(), route.halfWidth * 2, route.wear, route.id);
            }
        }
        try (PrintWriter out = new PrintWriter(prefix + "-metadata.json", StandardCharsets.UTF_8)) {
            out.println("{\"seed\": " + seed + ", \"size\": 2048, \"generationVersion\": 4, \"step\": " + step + "}");
        }
    }

    private static void header(PrintWriter out) {
        out.println("x,z,height,forest,road,trail,water,rock,marsh,depth,state,flowX,flowZ,routeStyle,routeWear,waterLevel");
    }

    private static void row(PrintWriter out, NaturalLandscape terrain, int x, int z, WarzoneTerrain.Sample sample) {
        out.println(x + "," + z + "," + sample.height + "," + terrain.forest(x, z)
                + "," + sample.road + "," + sample.path + "," + sample.channel
                + "," + sample.mountain + "," + sample.bog
                + "," + Math.max(0, sample.waterLevel - sample.height)
                + "," + sample.waterState.ordinal() + "," + sample.flowX + "," + sample.flowZ
                + "," + sample.routeStyle + "," + sample.routeWear + "," + sample.waterLevel);
    }
}
