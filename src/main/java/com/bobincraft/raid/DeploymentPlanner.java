package com.bobincraft.raid;

import com.bobincraft.warzone.HideoutPlan;
import com.bobincraft.warzone.PoiPlan;
import com.bobincraft.warzone.WarzoneBasePlan;
import com.bobincraft.warzone.WarzoneTerrain;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/** Plans insertions from terrain and POI metadata, without loading or modifying chunks. */
public final class DeploymentPlanner {
    public static final int GENERATION_VERSION = 1;
    private DeploymentPlanner() {}

    public record Point(int id, int x, int y, int z, int danger, String label) {}

    private record Candidate(int x, int y, int z, int danger, double quality, long tie) {}
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Landing pad doubles as the permanent, easily recognizable extraction location. */
    public static Point extraction(WarzoneTerrain t) {
        return new Point(0, t.landingX(), WarzoneTerrain.LANDING_Y + 1, t.landingZ(), 0, "Landing extraction");
    }

    public static int minimumSeparation(WarzoneTerrain t) {
        return Math.max(32, Math.min(180, (int) (t.size * 0.16)));
    }

    public static int extractionSeparation(WarzoneTerrain t) {
        return Math.max(40, Math.min(140, (int) (t.size * 0.12)));
    }

    /** Threat follows nearby garrisons and their type; it does not follow map-center rings. */
    public static int danger(WarzoneTerrain t, int x, int z) {
        double strongest = 0, supporting = 0;
        for (WarzoneBasePlan.Site s : t.basePlan().sites()) {
            double strength = switch (s.type) {
                case FORTRESS -> 92;
                case MOUNTAIN_FORT -> 100;
                case OUTPOST -> 70;
                case BUNKER_COMPLEX -> 78;
                case BANDIT_CAMP -> 54;
                case WATCHTOWER -> 43;
            };
            double outside = Math.max(0, s.distanceTo(x, z) - s.flatRadius);
            double influence = strength * Math.max(0, 1.0 - outside / 190.0);
            if (influence > strongest) {
                supporting += strongest;
                strongest = influence;
            } else supporting += influence;
        }
        for (PoiPlan.Checkpoint p : t.poiPlan().checkpoints()) {
            supporting += 25 * Math.max(0, 1 - Math.hypot(x - p.cx(), z - p.cz()) / 85.0);
        }
        return Math.max(0, Math.min(100, (int) Math.round(strongest + Math.min(18, supporting * 0.12))));
    }

    /** Checks dry footing, local slope, POI clearance, and at least three usable escape directions. */
    public static boolean isSafeCandidate(WarzoneTerrain t, int x, int z) {
        return inspect(t, x, z, 0) != null;
    }

    public static List<Point> plan(WarzoneTerrain t, long rotation) {
        List<Candidate> candidates = new ArrayList<>();
        int stride = Math.max(4, t.size / 64);
        int margin = WarzoneTerrain.RIM_WIDTH + 7;
        // A rotated, jittered lattice changes insertion choices without depending on Random call order.
        int shiftX = (int) Math.floorMod(mix(t.seed ^ rotation), stride);
        int shiftZ = (int) Math.floorMod(mix(t.seed + rotation + 41), stride);
        collect(t, rotation, stride, margin, shiftX, shiftZ, candidates);
        List<Candidate> selected = choose(t, candidates);
        if (selected == null) {
            // Expand the search; clearance and footing rules are deliberately never relaxed.
            candidates.clear();
            collect(t, rotation, Math.max(2, stride / 2), margin, 0, 0, candidates);
            selected = choose(t, candidates);
        }
        if (selected == null) {
            throw new IllegalStateException("No safe, separated deployment triple for seed " + t.seed + " size " + t.size);
        }
        selected.sort(Comparator.comparingInt(Candidate::danger).thenComparingLong(Candidate::tie));
        String[] labels = {"Low profile", "Balanced route", "High stakes"};
        List<Point> result = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            Candidate c = selected.get(i);
            result.add(new Point(i + 1, c.x, c.y, c.z, c.danger, labels[i]));
        }
        return List.copyOf(result);
    }

    private static void collect(WarzoneTerrain t, long rotation, int stride, int margin,
                                int shiftX, int shiftZ, List<Candidate> out) {
        for (int x = t.min + margin + shiftX; x < t.max - margin; x += stride) {
            for (int z = t.min + margin + shiftZ; z < t.max - margin; z += stride) {
                Candidate c = inspect(t, x, z, mix(t.seed ^ rotation ^ ((long) x << 32) ^ (z & 0xffffffffL)));
                if (c != null) out.add(c);
            }
        }
    }

    private static Candidate inspect(WarzoneTerrain t, int x, int z, long tie) {
        if (!t.inside(x, z) || t.edgeDistance(x, z) < WarzoneTerrain.RIM_WIDTH + 7) return null;
        Point extraction = extraction(t);
        if (distanceSquared(x, z, extraction.x, extraction.z) < square(extractionSeparation(t))) return null;
        if (t.isReserved(x, z)) return null;
        for (WarzoneBasePlan.Site s : t.basePlan().sites()) {
            if (s.padDistance(x, z) < s.flatRadius + 12) return null;
        }
        for (HideoutPlan.Hideout h : t.hideoutPlan().hideouts()) {
            if (distanceSquared(x, z, h.cx(), h.cz()) < square(HideoutPlan.RESERVE_RADIUS + 7)) return null;
        }
        for (PoiPlan.Checkpoint p : t.poiPlan().checkpoints()) {
            if (distanceSquared(x, z, p.cx(), p.cz()) < square(PoiPlan.CHECKPOINT_RESERVE + 8)) return null;
        }
        for (PoiPlan.Battlefield p : t.poiPlan().battlefields()) {
            if (distanceSquared(x, z, p.cx(), p.cz()) < square(PoiPlan.BATTLEFIELD_RESERVE + 10)) return null;
        }
        WarzoneTerrain.Sample center = t.sample(x, z);
        if (!dry(center) || center.height > 115 || center.mountain > 0.48 || t.forest(x, z) > 0.80) return null;
        int escape = 0, roughness = 0;
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        for (int[] d : DIRECTIONS) {
            t.sample(x + d[0] * 2, z + d[1] * 2, sample);
            if (!dry(sample) || Math.abs(center.height - sample.height) > 1 || t.isReserved(x + d[0] * 2, z + d[1] * 2)) return null;
            roughness += Math.abs(center.height - sample.height);
            int previous = center.height;
            boolean walkable = true;
            for (int step = 1; step <= 7; step++) {
                int px = x + d[0] * step, pz = z + d[1] * step;
                t.sample(px, pz, sample);
                if (!dry(sample) || Math.abs(sample.height - previous) > 1 || t.isReserved(px, pz)) {
                    walkable = false;
                    break;
                }
                previous = sample.height;
            }
            if (walkable) escape++;
        }
        if (escape < 3) return null;
        double roadDistance = t.basePlan().roadDistance(x, z);
        if (roadDistance < 7) return null;
        double roadAccess = Double.isFinite(roadDistance) ? Math.max(0, 18 - Math.abs(roadDistance - 26) * 0.25) : 0;
        double quality = escape * 3 - roughness + roadAccess - t.forest(x, z) * 5;
        return new Candidate(x, center.height + 1, z, danger(t, x, z), quality, tie);
    }

    private static boolean dry(WarzoneTerrain.Sample s) {
        return s.height >= WarzoneTerrain.SEA_LEVEL + 3 && s.channel < 0.015 && s.pool < 0.02
                && s.oasis < 0.05 && s.pillar < 0.01 && s.scar < 0.35 && s.roadBroken < 0.01;
    }

    private static List<Candidate> choose(WarzoneTerrain t, List<Candidate> all) {
        if (all.size() < 3) return null;
        List<Candidate> byRisk = new ArrayList<>(all);
        byRisk.sort(Comparator.comparingInt(Candidate::danger));
        int[] targets = {byRisk.get(0).danger, byRisk.get(byRisk.size() / 2).danger, byRisk.get(byRisk.size() - 1).danger};
        List<List<Candidate>> ranked = new ArrayList<>(3);
        for (int target : targets) {
            List<Candidate> order = new ArrayList<>(all);
            order.sort(Comparator.comparingDouble((Candidate c) -> Math.abs(c.danger - target) * 4.0 - c.quality)
                    .thenComparingLong(Candidate::tie));
            ranked.add(order);
        }
        int separation = minimumSeparation(t);
        int alternatives = Math.min(48, all.size());
        for (int i = 0; i < alternatives; i++) {
            Candidate a = ranked.get(0).get(i);
            for (int j = 0; j < alternatives; j++) {
                Candidate b = ranked.get(1).get(j);
                if (!separated(a, b, separation)) continue;
                for (Candidate c : ranked.get(2)) {
                    if (separated(a, c, separation) && separated(b, c, separation)) return new ArrayList<>(List.of(a, b, c));
                }
            }
        }
        // Exact triangle search over the candidate compatibility graph prevents a greedy dead end.
        BitSet[] neighbors = new BitSet[all.size()];
        for (int i = 0; i < all.size(); i++) {
            neighbors[i] = new BitSet(all.size());
            for (int j = i + 1; j < all.size(); j++) {
                if (separated(all.get(i), all.get(j), separation)) neighbors[i].set(j);
            }
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = neighbors[i].nextSetBit(i + 1); j >= 0; j = neighbors[i].nextSetBit(j + 1)) {
                BitSet common = (BitSet) neighbors[i].clone();
                common.and(neighbors[j]);
                int k = common.nextSetBit(j + 1);
                if (k >= 0) return new ArrayList<>(List.of(all.get(i), all.get(j), all.get(k)));
            }
        }
        return null;
    }

    private static boolean separated(Candidate a, Candidate b, int distance) {
        return distanceSquared(a.x, a.z, b.x, b.z) >= square(distance);
    }

    private static long distanceSquared(int ax, int az, int bx, int bz) {
        long dx = ax - (long) bx, dz = az - (long) bz;
        return dx * dx + dz * dz;
    }

    private static long square(int n) { return (long) n * n; }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
