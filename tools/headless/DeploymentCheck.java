import com.bobincraft.raid.DeploymentPlanner;
import com.bobincraft.warzone.WarzoneBasePlan;
import com.bobincraft.warzone.WarzoneTerrain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Property checks against real procedural terrain, including constrained 256-block arenas. */
public final class DeploymentCheck {
    private static int assertions;

    private static void require(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        int seeds = args.length == 0 ? 16 : Integer.parseInt(args[0]);
        int[] sizes = {256, 512, 1024, 2048};
        long started = System.nanoTime();
        int maps = 0, varied = 0, nonFlatRisk = 0;
        for (int size : sizes) {
            for (int n = 0; n < seeds; n++) {
                long seed = n == 0 ? 0 : n == 1 ? Long.MIN_VALUE : n * 7919L + 13;
                WarzoneTerrain t = new WarzoneTerrain(seed, size);
                List<DeploymentPlanner.Point> first = DeploymentPlanner.plan(t, 0);
                List<DeploymentPlanner.Point> replay = DeploymentPlanner.plan(t, 0);
                require(first.equals(replay), "nondeterministic plan " + seed + "/" + size);
                validate(t, first);
                List<DeploymentPlanner.Point> rotated = DeploymentPlanner.plan(t, 1);
                validate(t, rotated);
                if (!first.equals(rotated)) varied++;
                if (first.get(0).danger() < first.get(2).danger()) nonFlatRisk++;
                if (n == 0) {
                    WarzoneTerrain restarted = new WarzoneTerrain(seed, size);
                    require(first.equals(DeploymentPlanner.plan(restarted, 0)), "restart changed plan");
                    require(DeploymentPlanner.extraction(t).equals(DeploymentPlanner.extraction(restarted)), "restart changed extraction");
                }
                // The landing pad is safe for extraction but must never be offered as insertion.
                require(!DeploymentPlanner.isSafeCandidate(t, t.landingX(), t.landingZ()), "insertion overlaps extraction");
                require(!DeploymentPlanner.isSafeCandidate(t, t.min, t.min), "rim accepted");
                for (WarzoneBasePlan.Site site : t.basePlan().sites()) {
                    require(!DeploymentPlanner.isSafeCandidate(t, site.cx, site.cz), "insertion inside POI");
                    require(DeploymentPlanner.danger(t, site.cx, site.cz) >= 40, "garrison has no danger");
                }
                maps++;
            }
        }
        require(varied > maps * 0.75, "rotation seldom changes deployment choices");
        require(nonFlatRisk > 0, "danger categories never reflect nearby garrisons");
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.out.printf("Deployment checks passed: %d seeds × %d sizes = %d maps, %d assertions, rotation changes %d, risk spread %d, %.2fs%n",
                seeds, sizes.length, maps, assertions, varied, nonFlatRisk, seconds);
    }

    private static void validate(WarzoneTerrain t, List<DeploymentPlanner.Point> points) {
        require(points.size() == 3, "expected exactly three insertions");
        Set<String> unique = new HashSet<>();
        DeploymentPlanner.Point extraction = DeploymentPlanner.extraction(t);
        int previousRisk = -1;
        for (int i = 0; i < points.size(); i++) {
            DeploymentPlanner.Point point = points.get(i);
            require(point.id() == i + 1, "unstable id");
            require(unique.add(point.x() + ":" + point.z()), "duplicate insertion");
            require(DeploymentPlanner.isSafeCandidate(t, point.x(), point.z()), "unsafe insertion " + point);
            require(point.y() == t.height(point.x(), point.z()) + 1, "wrong terrain height");
            require(point.y() >= WarzoneTerrain.SEA_LEVEL + 4, "insertion below dry land");
            int escapeDirections = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int oldHeight = point.y() - 1;
                    boolean escape = true;
                    for (int step = 1; step <= 7; step++) {
                        WarzoneTerrain.Sample sample = t.sample(point.x() + step * dx, point.z() + step * dz);
                        if (sample.height <= WarzoneTerrain.SEA_LEVEL + 2 || Math.abs(sample.height - oldHeight) > 1
                                || t.isReserved(point.x() + step * dx, point.z() + step * dz)) escape = false;
                        oldHeight = sample.height;
                    }
                    if (escape) escapeDirections++;
                }
            }
            require(escapeDirections >= 3, "no walkable escape corridors");
            require(point.danger() >= previousRisk && point.danger() <= 100, "risk order/range");
            require(distance(point, extraction) >= DeploymentPlanner.extractionSeparation(t), "extraction too close");
            require(t.inside(point.x(), point.z()), "outside map");
            previousRisk = point.danger();
            for (int j = 0; j < i; j++) {
                require(distance(point, points.get(j)) >= DeploymentPlanner.minimumSeparation(t), "insertions too close");
            }
        }
    }

    private static double distance(DeploymentPlanner.Point a, DeploymentPlanner.Point b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }
}
