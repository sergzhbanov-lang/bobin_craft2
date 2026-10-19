package com.bobincraft.warzone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import static com.bobincraft.warzone.WarzoneNoise.lerp;
import static com.bobincraft.warzone.WarzoneNoise.smoothstep;

/**
 * Versioned natural Warzone landscapes. Sampling is independent of chunks, order and Minecraft state.
 * A small, temporary planning lattice finds existing suitable ground and routes; it never levels
 * that ground. Only the established player landing receives a flat pad.
 */
public final class NaturalLandscape {
    public enum ReservationType { COMPOUND, FOREST_OUTPOST, CHECKPOINT, INDUSTRY, SETTLEMENT }

    /** A future placement opportunity, not a generated structure or a cleared building pad. */
    public static final class Reservation {
        public final ReservationType type;
        public final int x, z, radius, height;
        public final double relief;

        private Reservation(ReservationType type, int x, int z, int radius, int height, double relief) {
            this.type = type;
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.height = height;
            this.relief = relief;
        }
    }

    public enum RouteStyle { WAGON_TRACK, GRAVEL_ROAD, WOODLAND_TRAIL, WORN_FOOTPATH }

    /** An ordinary surface route; its elevation always comes from the underlying landscape. */
    public static final class RouteSegment {
        public final int x1, z1, x2, z2;
        public final boolean trail;
        public final RouteStyle style;
        /** Nominal outer half-width, before the small continuous verge variation. */
        public final double halfWidth, wear;
        public final int id;

        private RouteSegment(int x1, int z1, int x2, int z2, boolean trail) {
            this(x1, z1, x2, z2, trail ? RouteStyle.WOODLAND_TRAIL : RouteStyle.GRAVEL_ROAD,
                    trail ? 2.2 : 4.2, 0.8, -1);
        }

        private RouteSegment(int x1, int z1, int x2, int z2, RouteStyle style,
                             double halfWidth, double wear, int id) {
            this.x1 = x1;
            this.z1 = z1;
            this.x2 = x2;
            this.z2 = z2;
            this.style = style;
            this.trail = style == RouteStyle.WOODLAND_TRAIL || style == RouteStyle.WORN_FOOTPATH;
            this.halfWidth = halfWidth;
            this.wear = wear;
            this.id = id;
        }

        private double distanceSquared(int x, int z) {
            double dx = x2 - x1, dz = z2 - z1;
            double denominator = dx * dx + dz * dz;
            double t = denominator == 0 ? 0 : Math.max(0, Math.min(1,
                    ((x - x1) * dx + (z - z1) * dz) / denominator));
            double ox = x - x1 - dx * t, oz = z - z1 - dz * t;
            return ox * ox + oz * oz;
        }
    }

    private record Pond(double x, double z, double radius, double stretch) { }
    private record QueueNode(int index, double cost) implements Comparable<QueueNode> {
        @Override public int compareTo(QueueNode other) {
            int result = Double.compare(cost, other.cost);
            return result != 0 ? result : Integer.compare(index, other.index);
        }
    }

    private static final int PLAN_STEP = 16;
    private static final int ROUTE_BUCKET = 64;
    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};
    private final long seed;
    private final boolean revised;
    private final boolean landscapeRivers;
    private final NaturalWaterways waterways;
    private final double waterDepthCutoff;
    private final int size, half, min, max, landingZ;
    private final WarzoneNoise broad, hills, detail, rocks, wet, forest, rim, water;
    private final double riverCos, riverSin, riverPhase, riverOffset;
    private final List<Pond> ponds;
    private final List<Reservation> reservations;
    private final List<RouteSegment> routes;
    private final Map<Long, int[]> routeBuckets;
    private final int connectedReservationCount;

    public NaturalLandscape(long seed, int size) {
        this(seed, size, true, true);
    }

    /** The original natural profile is retained for generation-version-two saves. */
    public NaturalLandscape(long seed, int size, boolean revised) {
        this(seed, size, revised, false);
    }

    public NaturalLandscape(long seed, int size, boolean revised, boolean landscapeRivers) {
        if (size < 256 || size % 16 != 0) {
            throw new IllegalArgumentException("Warzone size must be at least 256 and divisible by 16: " + size);
        }
        this.seed = seed;
        this.revised = revised;
        this.landscapeRivers = landscapeRivers;
        this.size = size;
        half = size / 2;
        min = -half;
        max = half;
        landingZ = (int) (half * 0.68);
        broad = noise(101);
        hills = noise(102);
        detail = noise(103);
        rocks = noise(104);
        wet = noise(105);
        forest = noise(106);
        rim = noise(107);
        water = noise(108);
        double angle = 0.20 + unit(110) * 0.65;
        riverCos = Math.cos(angle);
        riverSin = Math.sin(angle);
        riverPhase = unit(111) * Math.PI * 2;
        riverOffset = (unit(112) - 0.5) * size * 0.12;
        waterways = revised ? new NaturalWaterways(seed, size, riverCos, riverSin, riverPhase, riverOffset, landscapeRivers) : null;
        ponds = planPonds();
        waterDepthCutoff = revised && !landscapeRivers ? calibrateWaterDepths() : 0;
        reservations = Collections.unmodifiableList(planReservations());
        RoutePlanner planner = new RoutePlanner();
        routes = Collections.unmodifiableList(planner.plan());
        connectedReservationCount = planner.connectedCount;
        routeBuckets = indexRoutes();
    }

    private WarzoneNoise noise(int salt) { return new WarzoneNoise(WarzoneNoise.mix(seed + salt)); }
    private double unit(int salt) { return WarzoneNoise.unit(WarzoneNoise.mix(seed + salt)); }

    public List<Reservation> reservations() { return reservations; }
    public List<RouteSegment> routes() { return routes; }
    public int connectedReservationCount() { return connectedReservationCount; }

    public WarzoneTerrain.Region sampleRegion(int x, int z) {
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        naturalSample(x, z, sample, true);
        return sample.region;
    }

    /** Broad woodland patches contain smaller irregular clearings and thinning along their edges. */
    public double forest(int x, int z) {
        double woodland = 0.15 + 0.85 * smoothstep(-0.34, 0.22,
                forest.fbm(x / 177.0 + 18.7, z / 177.0 - 9.1, 2));
        double clearing = smoothstep(0.24, 0.48, forest.noise(x / 96.0 - 31.0, z / 96.0 + 17.0));
        return woodland * (1.0 - 0.91 * clearing);
    }

    /** Future opportunities do not clear forest disks; only the landing and inner route cores do. */
    public boolean isReserved(int x, int z) {
        double dz = z - landingZ;
        if ((double) x * x + dz * dz <= 24 * 24) return true;
        if (!revised) return routeFactor(x, z, false) > 0.02 || routeFactor(x, z, true) > 0.02;
        // Reserve only the inner traveled core so trees and undergrowth hug irregular path verges.
        return routeFactor(x, z, false) > 0.36 || routeFactor(x, z, true) > 0.48;
    }

    public void sample(int x, int z, WarzoneTerrain.Sample out) {
        naturalSample(x, z, out, true);
        if (out.edge < 0 || out.height < out.waterLevel - 1) return;
        out.road = routeFactor(x, z, false);
        out.path = routeFactor(x, z, true);
        out.trail = out.path > out.road && out.path > 0;
        if (revised) sampleRouteAppearance(x, z, out);
    }

    private double naturalSample(int x, int z, WarzoneTerrain.Sample out, boolean includePonds) {
        out.edge = Math.min(Math.min(x - min, max - 1 - x), Math.min(z - min, max - 1 - z));
        out.height = 0;
        out.region = WarzoneTerrain.Region.PLAINS;
        out.channel = out.valley = out.scar = out.mountain = 0;
        out.road = out.basePad = out.roadBroken = out.path = 0;
        out.desert = out.dune = out.pillar = out.oasis = out.bog = out.pool = 0;
        out.trail = false;
        out.routeStyle = 0;
        out.routeWear = 0;
        out.waterState = WarzoneTerrain.WaterState.NONE;
        out.flowX = out.flowZ = 0;
        out.waterLevel = WarzoneTerrain.SEA_LEVEL;
        if (out.edge < 0) return 0;

        double wx = x + detail.noise(x / 285.0, z / 285.0) * 18;
        double wz = z + detail.noise(x / 285.0 + 43.0, z / 285.0 - 21.0) * 18;
        double small = detail.noise(wx / 43.0 - 17.0, wz / 43.0 + 8.0);
        double rocky = smoothstep(0.24, 0.49, rocks.fbm(wx / 265.0, wz / 265.0, 2));
        double scarp = smoothstep(-0.07, 0.10, rocks.noise(wx / 78.0 + 18.0, wz / 78.0 - 6.0));
        double height = 76 + broad.fbm(wx / 470.0 + 3.0, wz / 470.0 - 5.0, 2) * 16
                + hills.fbm(wx / 153.0 - 11.0, wz / 153.0 + 4.0, 3) * 12
                + small * 0.85 + rocky * (3.0 + scarp * 9.0);
        double marsh = smoothstep(0.19, 0.46, wet.fbm(wx / 265.0 - 8.0, wz / 265.0 + 19.0, 2))
                * (1 - smoothstep(83, 96, height));
        height = lerp(height, 63.4 + small * 0.75, marsh * 0.94);

        if (landscapeRivers) height += waterways.mountainLift(x, z);

        double valley, channel;
        if (revised) {
            height = waterways.carve(x, z, small, height, out);
            valley = out.valley;
            channel = out.channel;
        } else {
        // One meandering drainage with two narrower tributaries, rather than unrelated noise cuts.
        double u = x * riverCos + z * riverSin;
        double v = -x * riverSin + z * riverCos;
        double center = riverCenter(u);
        double derivative = Math.cos(u / 230.0 + riverPhase) * 0.248
                + Math.cos(u / 94.0 - riverPhase * 0.7) * 0.170;
        double distance = Math.abs(v - center) / Math.sqrt(1 + derivative * derivative);
        double width = 3.0 + 0.7 * Math.sin(u / 121.0 + riverPhase);
        double ford = ford(u, size * 0.19, 370.0);
        double depth = 3.0;
        for (int branch = 0; branch < 2; branch++) {
            double side = branch == 0 ? 1 : -1;
            double t = (v - center) * side;
            double join = (branch == 0 ? -0.19 : 0.23) * size;
            double branchU = join + t * (branch == 0 ? 0.28 : -0.23)
                    + Math.sin(t / 116.0) * 24;
            if (t >= 0 && t < half * 1.45) {
                double streamDistance = Math.abs(u - branchU) / 1.06;
                if (streamDistance / 1.7 < distance / width) {
                    distance = streamDistance;
                    width = 1.7;
                    depth = 1.8;
                    ford = ford(t, 86, 210);
                }
            }
        }
        valley = 1 - smoothstep(width + 3, width + 32, distance);
        channel = 1 - smoothstep(width * 0.53, width + 1.4, distance);
        double bank = 64.5 + distance * 0.025 + small * 0.35;
        height = lerp(height, Math.min(height, bank), valley * 0.94);
        double bed = 62 - depth + ford * (depth + 0.12) + small * 0.12 * (1 - ford);
        height = lerp(height, Math.min(height, bed), channel);

        }

        // Small ponds are selected once, on low ground, with wide, soft banks.
        double pondWeight = landscapeRivers ? 1 - smoothstep(.03, .25, valley) : 1;
        double pool = 0;
        if (includePonds) {
            for (Pond pond : ponds) {
                double dx = (x - pond.x) / pond.stretch, dz = (z - pond.z) * pond.stretch;
                if (Math.abs(dx) > pond.radius + 24 || Math.abs(dz) > pond.radius + 24) continue;
                double radius = Math.sqrt(dx * dx + dz * dz) + small * 3.0;
                double basin = 1 - smoothstep(pond.radius * 0.58, pond.radius + 24, radius);
                basin *= pondWeight;
                double bottom = 59.8 + 1.4 * smoothstep(pond.radius * 0.25, pond.radius, radius);
                if (revised) {
                    // Rounded bays and uneven shorelines break up the former elliptical ponds.
                    bottom = 56.8 + 4.4 * smoothstep(pond.radius * 0.20, pond.radius + 4, radius);
                    if (basin > 0.20 && bottom < height && channel < 0.40) {
                        out.waterState = WarzoneTerrain.WaterState.STILL;
                        out.flowX = out.flowZ = 0;
                        if (landscapeRivers) out.waterLevel = WarzoneTerrain.SEA_LEVEL;
                    }
                }
                height = lerp(height, Math.min(height, bottom), basin);
                pool = Math.max(pool, (1 - smoothstep(pond.radius * 0.55, pond.radius + 2, radius)) * pondWeight);
                valley = Math.max(valley, basin * 0.75);
            }
        }
        double wetHollow = marsh * smoothstep(0.12, 0.40, water.noise(x / 28.0 + 6, z / 28.0 - 7));
        if (landscapeRivers) wetHollow *= pondWeight * (1 - smoothstep(0, 8, waterways.mountainLift(x, z)));
        double hollowFloor = revised ? 59.4 + small * .5 : 61.3 + small * .35;
        double hollowHeight = lerp(height, Math.min(height, hollowFloor), wetHollow * .88);
        if (revised && hollowHeight < height - .3 && channel < .20) {
            out.waterState = WarzoneTerrain.WaterState.SLOW;
            out.flowX = out.flowZ = 0;
        }
        height = hollowHeight;

        double landingDistance = Math.hypot(x, z - landingZ);
        double landing = 1 - smoothstep(WarzoneTerrain.LANDING_FLAT,
                landscapeRivers ? 104 : WarzoneTerrain.LANDING_BLEND, landingDistance);
        height = lerp(height, WarzoneTerrain.LANDING_Y, landing);
        double boundary = 1 - smoothstep(0, 44, out.edge);
        height = lerp(height, 114 + rim.noise(x / 48.0, z / 48.0) * 9, boundary);
        double rawDepth = Math.max(0, out.waterLevel - height);
        if (revised && rawDepth > 0 && waterDepthCutoff > 0) {
            // A per-seed quantile creates a narrow shallow shelf around the deeper channel.
            // Both pieces meet at depth 2, so the shelf introduces no artificial bed step.
            double depth = rawDepth <= waterDepthCutoff
                    ? 2 * rawDepth / waterDepthCutoff
                    : 2 + Math.min(6, (rawDepth - waterDepthCutoff) * 1.10);
            height = WarzoneTerrain.SEA_LEVEL - depth;
        }
        out.height = (int) Math.floor(height);
        if (landscapeRivers && out.height < out.waterLevel
                && out.waterState == WarzoneTerrain.WaterState.NONE) {
            out.waterState = marsh > .35 ? WarzoneTerrain.WaterState.SLOW : WarzoneTerrain.WaterState.STILL;
            out.flowX = out.flowZ = 0;
        }
        if (revised && out.height >= out.waterLevel) {
            out.waterState = WarzoneTerrain.WaterState.NONE;
            out.flowX = out.flowZ = 0;
        }
        out.channel = channel * (1 - landing) * (1 - boundary);
        out.valley = valley * (1 - landing) * (1 - boundary);
        out.mountain = Math.max(rocky * (0.35 + scarp * 0.65) * (1 - landing), boundary);
        if (landscapeRivers) out.mountain = Math.max(out.mountain, waterways.mountainMask(x, z) * (1 - landing));
        out.bog = marsh * (1 - channel) * (1 - landing) * (1 - boundary);
        out.pool = Math.max(pool, wetHollow) * (1 - landing) * (1 - boundary);
        out.region = out.bog > 0.54 ? WarzoneTerrain.Region.MARSH
                : out.mountain > 0.42 ? WarzoneTerrain.Region.HIGHLANDS : WarzoneTerrain.Region.PLAINS;
        return rawDepth;
    }

    public int tributaryCount() { return revised ? waterways.tributaryCount() : 2; }

    /**
     * One bounded construction-time scan; sampling remains independent of chunks and their order.
     * Rank actual wet columns (including ponds and marsh hollows), not river lengths or water volume.
     */
    private double calibrateWaterDepths() {
        int[] histogram = new int[2048];
        int count = 0;
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        for (int z = min + 2; z < max; z += 4) {
            for (int x = min + 2; x < max; x += 4) {
                double depth = naturalSample(x, z, sample, true);
                if (depth <= 0) continue;
                histogram[Math.min(histogram.length - 1, (int) (depth * 128))]++;
                count++;
            }
        }
        int target = Math.max(1, (int) Math.round(count * .20)), cumulative = 0;
        for (int i = 0; i < histogram.length; i++) {
            cumulative += histogram[i];
            if (cumulative >= target) return Math.max(1.0 / 128, (i + .5) / 128);
        }
        return 1;
    }

    private double riverCenter(double u) {
        return riverOffset + Math.sin(u / 230.0 + riverPhase) * 57
                + Math.sin(u / 94.0 - riverPhase * 0.7) * 16;
    }

    /** Gravel bars provide occasional shallow crossings without constructing bridges or causeways. */
    private static double ford(double coordinate, double offset, double spacing) {
        double nearest = Math.rint((coordinate - offset) / spacing) * spacing + offset;
        return 1 - smoothstep(12, 29, Math.abs(coordinate - nearest));
    }

    private List<Pond> planPonds() {
        List<Pond> result = new ArrayList<>();
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        int cell = 288;
        for (int cz = Math.floorDiv(min, cell); cz <= Math.floorDiv(max - 1, cell); cz++) {
            for (int cx = Math.floorDiv(min, cell); cx <= Math.floorDiv(max - 1, cell); cx++) {
                long hash = WarzoneNoise.hash(WarzoneNoise.mix(seed + 120), cx, cz);
                if (WarzoneNoise.unit(hash) > 0.47) continue;
                int x = cx * cell + 64 + (int) (WarzoneNoise.unit(WarzoneNoise.mix(hash + 1)) * (cell - 128));
                int z = cz * cell + 64 + (int) (WarzoneNoise.unit(WarzoneNoise.mix(hash + 2)) * (cell - 128));
                naturalSample(x, z, sample, false);
                if (sample.edge < 100 || sample.height < 65 || sample.height > 80
                        || sample.channel > 0.1 || Math.hypot(x, z - landingZ) < 115) continue;
                double radius = 15 + WarzoneNoise.unit(WarzoneNoise.mix(hash + 3)) * 17;
                result.add(new Pond(x, z, radius, 0.80 + WarzoneNoise.unit(WarzoneNoise.mix(hash + 4)) * 0.45));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private List<Reservation> planReservations() {
        List<Reservation> result = new ArrayList<>();
        int sectors = size >= 1024 ? 3 : 2;
        int margin = Math.min(144, size / 5);
        double span = (size - margin * 2.0) / sectors;
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        ReservationType[] types = ReservationType.values();
        for (int sz = 0; sz < sectors; sz++) {
            for (int sx = 0; sx < sectors; sx++) {
                ReservationType type = types[Math.floorMod(sx + sz * sectors, types.length)];
                int radius = switch (type) {
                    case COMPOUND -> 54;
                    case INDUSTRY -> 44;
                    case SETTLEMENT -> 46;
                    case FOREST_OUTPOST -> 25;
                    case CHECKPOINT -> 15;
                };
                radius = Math.min(radius, Math.max(12, size / 24));
                Reservation best = null;
                double bestScore = Double.POSITIVE_INFINITY;
                for (int attempt = 0; attempt < (landscapeRivers ? 128 : 48); attempt++) {
                    long hash = WarzoneNoise.hash(WarzoneNoise.mix(seed + 130 + attempt), sx, sz);
                    int x = snap(min + margin + (sx + 0.12 + WarzoneNoise.unit(hash) * 0.76) * span);
                    int z = snap(min + margin + (sz + 0.12 + WarzoneNoise.unit(WarzoneNoise.mix(hash + 1)) * 0.76) * span);
                    if (Math.hypot(x, z - landingZ) < radius + 100) continue;
                    naturalSample(x, z, sample, true);
                    if (sample.height < 65 || sample.edge < radius + 48 || sample.bog > 0.45) continue;
                    int centerHeight = sample.height, low = centerHeight, high = centerHeight;
                    boolean suitable = true;
                    for (int direction = 0; direction < 8; direction++) {
                        double angle = direction * Math.PI / 4;
                        naturalSample(x + (int) (Math.cos(angle) * radius), z + (int) (Math.sin(angle) * radius), sample, true);
                        low = Math.min(low, sample.height);
                        high = Math.max(high, sample.height);
                        if (sample.height < 64 || sample.channel > 0.15 || sample.pool > 0.30 || sample.bog > 0.6) suitable = false;
                    }
                    // Include the interior: a dry perimeter can still surround a wet hollow.
                    for (int gz = -3; gz <= 3; gz++) {
                        for (int gx = -3; gx <= 3; gx++) {
                            if (gx * gx + gz * gz > 9) continue;
                            naturalSample(x + gx * radius / 3, z + gz * radius / 3, sample, true);
                            low = Math.min(low, sample.height);
                            high = Math.max(high, sample.height);
                            if (sample.height < 65 || sample.channel > 0.10 || sample.pool > 0.15 || sample.bog > 0.45) suitable = false;
                        }
                    }
                    if (landscapeRivers && suitable && high - low <= Math.max(6, radius * 0.17)) {
                        // Source hills and raised streams can hide between the coarse perimeter
                        // probes. Verify the whole potential footprint before promising gentle ground.
                        for (int dz = -radius; dz <= radius && suitable; dz += 6) {
                            for (int dx = -radius; dx <= radius; dx += 6) {
                                if (dx * dx + dz * dz > radius * radius) continue;
                                naturalSample(x + dx, z + dz, sample, true);
                                low = Math.min(low, sample.height);
                                high = Math.max(high, sample.height);
                                if (sample.height < sample.waterLevel + 2 || sample.channel > 0.10
                                        || sample.pool > 0.15 || sample.bog > 0.45
                                        || high - low > Math.max(6, radius * 0.17)) {
                                    suitable = false;
                                    break;
                                }
                            }
                        }
                    }
                    double relief = high - low;
                    if (!suitable || relief > Math.max(6, radius * 0.17)) continue;
                    boolean overlaps = false;
                    for (Reservation prior : result) {
                        if (Math.hypot(x - prior.x, z - prior.z) < radius + prior.radius + 90) overlaps = true;
                    }
                    if (overlaps) continue;
                    double score = relief + Math.abs(centerHeight - 75) * 0.08
                            + WarzoneNoise.unit(WarzoneNoise.mix(hash + 3)) * 0.4;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Reservation(type, x, z, radius, centerHeight, relief);
                    }
                }
                if (best != null) result.add(best);
            }
        }
        return result;
    }

    private static int snap(double value) { return (int) Math.round(value / PLAN_STEP) * PLAN_STEP; }

    private static long bucket(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private Map<Long, int[]> indexRoutes() {
        Map<Long, List<Integer>> temporary = new HashMap<>();
        for (int i = 0; i < routes.size(); i++) {
            RouteSegment route = routes.get(i);
            int margin = revised ? (int) Math.ceil(route.halfWidth * 1.3 + 1) : 5;
            for (int z = Math.floorDiv(Math.min(route.z1, route.z2) - margin, ROUTE_BUCKET);
                 z <= Math.floorDiv(Math.max(route.z1, route.z2) + margin, ROUTE_BUCKET); z++) {
                for (int x = Math.floorDiv(Math.min(route.x1, route.x2) - margin, ROUTE_BUCKET);
                     x <= Math.floorDiv(Math.max(route.x1, route.x2) + margin, ROUTE_BUCKET); x++) {
                    temporary.computeIfAbsent(bucket(x, z), ignored -> new ArrayList<>()).add(i);
                }
            }
        }
        Map<Long, int[]> result = new HashMap<>();
        temporary.forEach((key, value) -> result.put(key, value.stream().mapToInt(Integer::intValue).toArray()));
        return result;
    }

    private double routeFactor(int x, int z, boolean trail) {
        int[] candidates = routeBuckets.get(bucket(Math.floorDiv(x, ROUTE_BUCKET), Math.floorDiv(z, ROUTE_BUCKET)));
        if (candidates == null) return 0;
        if (revised) {
            double strongest = 0;
            for (int candidate : candidates) {
                RouteSegment route = routes.get(candidate);
                if (route.trail == trail) strongest = Math.max(strongest, routeInfluence(route, x, z));
            }
            return strongest;
        }
        double best = Double.POSITIVE_INFINITY;
        for (int candidate : candidates) {
            RouteSegment route = routes.get(candidate);
            if (route.trail == trail) best = Math.min(best, route.distanceSquared(x, z));
        }
        double inner = trail ? 0.75 : 2.1, outer = trail ? 2.2 : 4.2;
        return best >= outer * outer ? 0 : 1 - smoothstep(inner, outer, Math.sqrt(best));
    }

    private double routeWidth(RouteSegment route, int x, int z) {
        // Multi-scale coordinate fields shared by neighboring segments prevent width jumps at joins.
        double meander = landscapeRivers
                ? detail.fbm(x / 34.0 + 7.3, z / 34.0 - 4.1, 2)
                : detail.fbm(x / 34.0 + route.id * 0.73, z / 34.0 - route.id * 0.41, 2);
        double verge = detail.noise(x / 11.0 - 38.0, z / 11.0 + 13.0) * 0.65
                + hills.noise(x / 4.5 + 19.0, z / 4.5 - 7.0) * 0.35;
        return route.halfWidth * (1 + meander * 0.22) + verge * (route.trail ? 0.38 : 0.56);
    }

    private double routeInfluence(RouteSegment route, int x, int z) {
        double outer = routeWidth(route, x, z);
        double inner = Math.max(0.85, outer * (route.trail ? 0.28 : 0.38));
        return 1 - smoothstep(inner, outer, Math.sqrt(route.distanceSquared(x, z)));
    }

    /** Surface exposure includes two wheel ruts for wagon tracks and softer worn trail edges. */
    private void sampleRouteAppearance(int x, int z, WarzoneTerrain.Sample out) {
        if (!revised) return;
        int[] candidates = routeBuckets.get(bucket(Math.floorDiv(x, ROUTE_BUCKET), Math.floorDiv(z, ROUTE_BUCKET)));
        if (candidates == null) return;
        RouteSegment dominant = null;
        double strongest = 0;
        for (int candidate : candidates) {
            RouteSegment route = routes.get(candidate);
            double influence = routeInfluence(route, x, z);
            if (influence <= 0) continue;
            if (influence > strongest || (influence == strongest && dominant != null
                    && ((dominant.trail && !route.trail) || (dominant.trail == route.trail
                    && route.distanceSquared(x, z) < dominant.distanceSquared(x, z))))) {
                dominant = route;
                strongest = influence;
            }
        }
        if (dominant == null) return;
        out.routeStyle = dominant.style.ordinal();
        double distance = Math.sqrt(dominant.distanceSquared(x, z));
        double crossSection = distance / Math.max(0.5, routeWidth(dominant, x, z));
        double alongPatch = landscapeRivers
                ? detail.fbm(x / 16.0 + 17, z / 16.0 - 13, 2)
                : detail.fbm(x / 16.0 + dominant.id * 1.7, z / 16.0 - dominant.id * 1.3, 2);
        double micro = hills.noise(x / 5.5 - 12.0, z / 5.5 + 9.0);
        double weathering = alongPatch * 0.11 + micro * 0.05;
        double exposure = dominant.wear;
        if (dominant.style == RouteStyle.WAGON_TRACK) {
            double rutDrift = micro * 0.04;
            double rut = (crossSection - (0.52 + rutDrift)) / 0.19;
            exposure = 0.19 + 0.73 * Math.exp(-rut * rut);
        } else if (dominant.style == RouteStyle.WOODLAND_TRAIL) {
            exposure *= (1 - smoothstep(0.18, 0.95, crossSection) * 0.62) * (0.86 + alongPatch * 0.22);
        } else if (dominant.style == RouteStyle.WORN_FOOTPATH) {
            exposure *= (1 - smoothstep(0.22, 0.92, crossSection) * 0.56) * (0.84 + alongPatch * 0.24);
        } else {
            exposure *= 1 - smoothstep(0.40, 1.0, crossSection) * 0.48;
        }
        out.routeWear = Math.max(0, Math.min(1, exposure + weathering));
    }

    /** Bounded construction-only A*: 123 by 123 cells for the default map, no world/chunk scans. */
    private final class RoutePlanner {
        private final int start = min + 48;
        private final int side = (size - 96) / PLAN_STEP + 1;
        private final int count = side * side;
        private final int[] heights = new int[count];
        private final double[] wetness = new double[count];
        private final byte[] edges = new byte[count * 8];
        private final boolean[] used = new boolean[count];
        // Once built, a shared planning edge has one centerline in either travel direction.
        private final Map<Long, List<int[]>> builtEdges = new HashMap<>();
        private int connectedCount;
        private int routeSequence;
        private RouteStyle planningStyle = RouteStyle.GRAVEL_ROAD;

        private RoutePlanner() {
            WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
            for (int i = 0; i < count; i++) {
                naturalSample(x(i), z(i), sample, true);
                heights[i] = sample.height;
                wetness[i] = sample.bog + sample.pool;
            }
        }

        private int x(int index) { return start + index % side * PLAN_STEP; }
        private int z(int index) { return start + index / side * PLAN_STEP; }
        private int index(int x, int z) {
            int gx = Math.max(0, Math.min(side - 1, (int) Math.round((x - start) / (double) PLAN_STEP)));
            int gz = Math.max(0, Math.min(side - 1, (int) Math.round((z - start) / (double) PLAN_STEP)));
            return gz * side + gx;
        }

        private List<RouteSegment> plan() {
            if (landscapeRivers) return planTerrainRoutes();
            List<RouteSegment> result = new ArrayList<>();
            int nodes = reservations.size() + 1;
            int[] xs = new int[nodes], zs = new int[nodes];
            xs[0] = 0;
            zs[0] = landingZ;
            for (int i = 1; i < nodes; i++) {
                xs[i] = reservations.get(i - 1).x;
                zs[i] = reservations.get(i - 1).z;
            }
            boolean[] connected = new boolean[nodes], attempted = new boolean[nodes];
            boolean[][] links = revised ? new boolean[nodes][nodes] : null;
            connected[0] = true;
            for (int step = 1; step < nodes; step++) {
                int from = -1, to = -1;
                double nearest = Double.POSITIVE_INFINITY;
                for (int a = 0; a < nodes; a++) if (connected[a]) {
                    for (int b = 1; b < nodes; b++) if (!connected[b] && !attempted[b]) {
                        double distance = Math.hypot(xs[a] - xs[b], zs[a] - zs[b]);
                        if (distance < nearest) { nearest = distance; from = a; to = b; }
                    }
                }
                if (to < 0) break;
                attempted[to] = true;
                if (revised) planningStyle = styleFor(reservations.get(to - 1), to);
                List<Integer> path = search(index(xs[from], zs[from]), index(xs[to], zs[to]), true);
                if (path.isEmpty()) continue;
                connected[to] = true;
                connectedCount++;
                boolean trail = reservations.get(to - 1).type == ReservationType.FOREST_OUTPOST;
                append(result, path, xs[from], zs[from], xs[to], zs[to], trail);
                if (revised) links[from][to] = links[to][from] = true;
            }
            if (revised) {
                appendAlternateLinks(result, xs, zs, connected, links);
                return result;
            }
            // Short extra links create junctions and alternate routes without a full road grid.
            int extras = 0;
            for (int a = 1; a < nodes && extras < 2; a++) if (connected[a]) {
                int b = a + 2;
                if (b >= nodes || !connected[b] || Math.hypot(xs[a] - xs[b], zs[a] - zs[b]) > size * 0.46) continue;
                List<Integer> path = search(index(xs[a], zs[a]), index(xs[b], zs[b]), false);
                if (!path.isEmpty()) {
                    append(result, path, xs[a], zs[a], xs[b], zs[b], true);
                    extras++;
                }
            }
            return result;
        }

        private List<RouteSegment> planTerrainRoutes() {
            List<RouteSegment> result = new ArrayList<>();
            int nodes = reservations.size() + 1;
            int[] xs = new int[nodes], zs = new int[nodes];
            xs[0] = 0;
            zs[0] = landingZ;
            for (int i = 1; i < nodes; i++) {
                xs[i] = reservations.get(i - 1).x;
                zs[i] = reservations.get(i - 1).z;
            }
            boolean[] connected = new boolean[nodes];
            boolean[][] attempted = new boolean[nodes][nodes], links = new boolean[nodes][nodes];
            connected[0] = true;
            // A failed origin never rules out connecting the same destination from another branch.
            for (;;) {
                int from = -1, to = -1;
                double nearest = Double.POSITIVE_INFINITY;
                for (int a = 0; a < nodes; a++) if (connected[a]) {
                    for (int b = 1; b < nodes; b++) if (!connected[b] && !attempted[a][b]) {
                        double distance = Math.hypot(xs[a] - xs[b], zs[a] - zs[b]);
                        if (distance < nearest) { nearest = distance; from = a; to = b; }
                    }
                }
                if (to < 0) break;
                attempted[from][to] = true;
                planningStyle = styleFor(reservations.get(to - 1), to);
                List<Integer> path = search(index(xs[from], zs[from]), index(xs[to], zs[to]), true);
                if (path.isEmpty() || !appendTerrainRoute(result, path, xs[from], zs[from], xs[to], zs[to])) continue;
                connected[to] = true;
                connectedCount++;
                links[from][to] = links[to][from] = true;
            }
            appendAlternateLinks(result, xs, zs, connected, links);
            return result;
        }

        private RouteStyle styleFor(Reservation reservation, int ordinal) {
            return switch (reservation.type) {
                case INDUSTRY -> RouteStyle.GRAVEL_ROAD;
                case SETTLEMENT -> RouteStyle.WAGON_TRACK;
                case FOREST_OUTPOST -> RouteStyle.WOODLAND_TRAIL;
                case CHECKPOINT -> RouteStyle.WORN_FOOTPATH;
                case COMPOUND -> unit(200 + ordinal) < 0.5 ? RouteStyle.WAGON_TRACK : RouteStyle.GRAVEL_ROAD;
            };
        }

        private void appendAlternateLinks(List<RouteSegment> result, int[] xs, int[] zs,
                                          boolean[] connected, boolean[][] links) {
            int target = Math.max(2, Math.min(4, xs.length / 2));
            for (int extra = 0; extra < target; extra++) {
                int from = -1, to = -1;
                double best = Double.POSITIVE_INFINITY;
                for (int a = 1; a < xs.length; a++) if (connected[a]) {
                    for (int b = a + 1; b < xs.length; b++) if (connected[b] && !links[a][b]) {
                        double distance = Math.hypot(xs[a] - xs[b], zs[a] - zs[b]);
                        if (distance < size * 0.09 || distance > size * 0.55) continue;
                        double score = distance * (0.7 + WarzoneNoise.unit(
                                WarzoneNoise.hash(seed + 219, a, b)) * 0.6);
                        if (score < best) { best = score; from = a; to = b; }
                    }
                }
                if (from < 0) break;
                links[from][to] = links[to][from] = true;
                planningStyle = extra % 2 == 0 ? RouteStyle.WOODLAND_TRAIL : RouteStyle.WORN_FOOTPATH;
                List<Integer> path = search(index(xs[from], zs[from]), index(xs[to], zs[to]), false);
                if (!path.isEmpty()) {
                    if (landscapeRivers) appendTerrainRoute(result, path, xs[from], zs[from], xs[to], zs[to]);
                    else append(result, path, xs[from], zs[from], xs[to], zs[to], true);
                }
            }
        }

        private List<Integer> search(int from, int to, boolean preferExisting) {
            if (heights[from] < 61 || heights[to] < 61) return List.of();
            double[] cost = new double[count];
            Arrays.fill(cost, Double.POSITIVE_INFINITY);
            int[] previous = new int[count];
            Arrays.fill(previous, -1);
            boolean[] closed = new boolean[count];
            PriorityQueue<QueueNode> open = new PriorityQueue<>();
            cost[from] = 0;
            open.add(new QueueNode(from, 0));
            while (!open.isEmpty()) {
                int current = open.remove().index;
                if (closed[current]) continue;
                if (current == to) {
                    List<Integer> path = new ArrayList<>();
                    for (int node = to; node != -1; node = previous[node]) path.add(node);
                    Collections.reverse(path);
                    if (!landscapeRivers) for (int node : path) used[node] = true;
                    return path;
                }
                closed[current] = true;
                int gx = current % side, gz = current / side;
                for (int direction = 0; direction < 8; direction++) {
                    int nx = gx + DX[direction], nz = gz + DZ[direction];
                    if (nx < 0 || nz < 0 || nx >= side || nz >= side) continue;
                    int next = nz * side + nx;
                    if (closed[next] || !passable(current, next, direction)) continue;
                    double distance = direction % 2 == 0 ? PLAN_STEP : PLAN_STEP * Math.sqrt(2);
                    double grade = Math.abs(heights[current] - heights[next]);
                    double stepCost = distance * (1 + wetness[next] * 1.9 + grade * grade * 0.10);
                    if (revised) {
                        double woodland = forest(x(next), z(next));
                        // Foot routes seek cover; maintained gravel takes the gentler, more direct ground.
                        double character = switch (planningStyle) {
                            case WOODLAND_TRAIL -> 1.22 - woodland * 0.28;
                            case WORN_FOOTPATH -> 1.05 + detail.noise(x(next) / 89.0, z(next) / 89.0) * 0.16;
                            case WAGON_TRACK -> 1.0 + grade * 0.035;
                            case GRAVEL_ROAD -> 1.0 + grade * 0.07;
                        };
                        stepCost *= character;
                        if (!preferExisting && used[next]) stepCost *= 1.28;
                        if (landscapeRivers && previous[current] >= 0) {
                            int prior = previous[current];
                            double ax = x(current) - x(prior), az = z(current) - z(prior);
                            double bx = x(next) - x(current), bz = z(next) - z(current);
                            double turn = 1 - (ax * bx + az * bz) / (Math.hypot(ax, az) * distance);
                            stepCost += turn * (planningStyle == RouteStyle.GRAVEL_ROAD ? 10 : 5);
                        }
                    }
                    if (preferExisting && used[next]) stepCost *= 0.72;
                    double proposed = cost[current] + stepCost;
                    if (proposed >= cost[next]) continue;
                    cost[next] = proposed;
                    previous[next] = current;
                    double heuristic = Math.hypot(x(next) - x(to), z(next) - z(to)) * (landscapeRivers ? 0.55 : 0.70);
                    open.add(new QueueNode(next, proposed + heuristic));
                }
            }
            return List.of();
        }

        private boolean passable(int from, int to, int direction) {
            int key = from * 8 + direction;
            if (edges[key] != 0) return edges[key] == 1;
            boolean allowed = heights[from] >= 61 && heights[to] >= 61
                    && Math.abs(heights[from] - heights[to]) <= 5
                    && linePassable(x(from), z(from), x(to), z(to));
            edges[key] = (byte) (allowed ? 1 : 2);
            edges[to * 8 + (direction + 4) % 8] = edges[key];
            return allowed;
        }

        private boolean linePassable(int x1, int z1, int x2, int z2) {
            // Check every surface column: even a one-column deep channel must not be skipped.
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(x2 - x1, z2 - z1)));
            WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
            int prior = Integer.MIN_VALUE;
            double length = Math.hypot(x2 - x1, z2 - z1);
            double acrossX = length == 0 ? 0 : -(z2 - z1) / length * 5.5;
            double acrossZ = length == 0 ? 0 : (x2 - x1) / length * 5.5;
            for (int step = 0; step <= steps; step++) {
                int x = (int) Math.round(lerp(x1, x2, step / (double) steps));
                int z = (int) Math.round(lerp(z1, z2, step / (double) steps));
                naturalSample(x, z, sample, true);
                if (sample.edge < 46 || sample.height < 61 || sample.mountain > 0.90
                        || (prior != Integer.MIN_VALUE && Math.abs(sample.height - prior) > 2)) return false;
                if (landscapeRivers) {
                    if (sample.height < sample.waterLevel - 1
                            || (prior != Integer.MIN_VALUE && Math.abs(sample.height - prior) > 1)) return false;
                    int height = sample.height;
                    // Check the usable carriageway as well as its center: a bank-side road must not
                    // paint wheel tracks over deep water or overhang the neighboring rock face.
                    if (step % 4 == 0 || step == steps) {
                        for (int side = -1; side <= 1; side += 2) {
                            naturalSample((int) Math.round(x + acrossX * side),
                                    (int) Math.round(z + acrossZ * side), sample, true);
                            if (sample.edge < 46 || sample.height < sample.waterLevel - 1
                                    || Math.abs(sample.height - height) > 3) return false;
                        }
                    }
                    prior = height;
                } else prior = sample.height;
            }
            return true;
        }

        /** Commits only complete connections; repeated trunk edges reuse the exact same curve. */
        private boolean appendTerrainRoute(List<RouteSegment> result, List<Integer> path,
                                           int fromX, int fromZ, int toX, int toZ) {
            int first = path.get(0), last = path.get(path.size() - 1);
            if (!linePassable(fromX, fromZ, x(first), z(first))
                    || !linePassable(x(last), z(last), toX, toZ)) return false;
            List<int[]> centerline = new ArrayList<>();
            centerline.add(new int[]{fromX, fromZ});
            addPoint(centerline, x(first), z(first));
            for (int i = 0; i < path.size() - 1; i++) {
                int a = path.get(i), b = path.get(i + 1);
                long key = bucket(Math.min(a, b), Math.max(a, b));
                List<int[]> curve = builtEdges.get(key);
                if (curve == null) {
                    int before = path.get(Math.max(0, i - 1));
                    int after = path.get(Math.min(path.size() - 1, i + 2));
                    curve = roundedEdge(before, a, b, after);
                    // Store every edge in ascending index order so opposite traffic has identical geometry.
                    if (a > b) Collections.reverse(curve);
                    builtEdges.put(key, curve);
                }
                if (a < b) {
                    for (int[] point : curve) addPoint(centerline, point[0], point[1]);
                } else {
                    for (int point = curve.size() - 1; point >= 0; point--) {
                        addPoint(centerline, curve.get(point)[0], curve.get(point)[1]);
                    }
                }
            }
            addPoint(centerline, toX, toZ);
            int id = routeSequence++;
            double variation = unit(240 + id);
            double halfWidth = switch (planningStyle) {
                case WAGON_TRACK -> 3.1 + variation * 1.5;
                case GRAVEL_ROAD -> 2.8 + variation * 1.3;
                case WOODLAND_TRAIL -> 1.65 + variation * 0.85;
                case WORN_FOOTPATH -> 1.2 + variation * 0.65;
            };
            double wear = planningStyle == RouteStyle.WOODLAND_TRAIL ? 0.57 + variation * 0.2 : 0.76 + variation * 0.17;
            for (int i = 1; i < centerline.size(); i++) {
                int[] a = centerline.get(i - 1), b = centerline.get(i);
                result.add(new RouteSegment(a[0], a[1], b[0], b[1], planningStyle, halfWidth, wear, id));
            }
            for (int node : path) used[node] = true;
            return true;
        }

        private List<int[]> roundedEdge(int before, int from, int to, int after) {
            List<int[]> curve = new ArrayList<>();
            curve.add(new int[]{x(from), z(from)});
            int steps = Math.max(2, (int) Math.ceil(Math.hypot(x(to) - x(from), z(to) - z(from)) / 4));
            for (int step = 1; step <= steps; step++) {
                double t = step / (double) steps;
                int px = (int) Math.round(catmullRom(x(before), x(from), x(to), x(after), t));
                int pz = (int) Math.round(catmullRom(z(before), z(from), z(to), z(after), t));
                int[] previous = curve.get(curve.size() - 1);
                if (!linePassable(previous[0], previous[1], px, pz)) {
                    return new ArrayList<>(List.of(new int[]{x(from), z(from)}, new int[]{x(to), z(to)}));
                }
                addPoint(curve, px, pz);
            }
            return curve;
        }

        private void append(List<RouteSegment> result, List<Integer> path, int fromX, int fromZ,
                            int toX, int toZ, boolean trail) {
            if (revised) {
                appendCurved(result, path, fromX, fromZ, toX, toZ);
                return;
            }
            int first = path.get(0);
            if (fromX != x(first) || fromZ != z(first)) {
                if (!linePassable(fromX, fromZ, x(first), z(first))) return;
                result.add(new RouteSegment(fromX, fromZ, x(first), z(first), trail));
            }
            for (int i = 0; i < path.size() - 1;) {
                int next = Math.min(path.size() - 1, i + 4);
                while (next > i + 1 && !linePassable(x(path.get(i)), z(path.get(i)), x(path.get(next)), z(path.get(next)))) next--;
                result.add(new RouteSegment(x(path.get(i)), z(path.get(i)), x(path.get(next)), z(path.get(next)), trail));
                i = next;
            }
            int last = path.get(path.size() - 1);
            if (toX != x(last) || toZ != z(last)) {
                if (linePassable(x(last), z(last), toX, toZ)) result.add(new RouteSegment(x(last), z(last), toX, toZ, trail));
            }
        }

        private void appendCurved(List<RouteSegment> result, List<Integer> path, int fromX, int fromZ,
                                  int toX, int toZ) {
            List<int[]> anchors = new ArrayList<>();
            int first = path.get(0);
            if (!linePassable(fromX, fromZ, x(first), z(first))) return;
            anchors.add(new int[]{fromX, fromZ});
            addPoint(anchors, x(first), z(first));
            for (int i = 0; i < path.size() - 1;) {
                int next = Math.min(path.size() - 1, i + (planningStyle == RouteStyle.GRAVEL_ROAD ? 5 : 3));
                while (next > i + 1 && !linePassable(x(path.get(i)), z(path.get(i)),
                        x(path.get(next)), z(path.get(next)))) next--;
                addPoint(anchors, x(path.get(next)), z(path.get(next)));
                i = next;
            }
            int last = path.get(path.size() - 1);
            if (linePassable(x(last), z(last), toX, toZ)) addPoint(anchors, toX, toZ);
            int id = routeSequence++;
            double variation = unit(240 + id);
            double halfWidth = switch (planningStyle) {
                case WAGON_TRACK -> 3.1 + variation * 1.5;
                case GRAVEL_ROAD -> 2.8 + variation * 1.3;
                case WOODLAND_TRAIL -> 1.65 + variation * 0.85;
                case WORN_FOOTPATH -> 1.2 + variation * 0.65;
            };
            double wear = planningStyle == RouteStyle.WOODLAND_TRAIL ? 0.57 + variation * 0.2 : 0.76 + variation * 0.17;
            double wander = switch (planningStyle) {
                case GRAVEL_ROAD -> 2.5;
                case WAGON_TRACK -> 5;
                case WOODLAND_TRAIL -> 8;
                case WORN_FOOTPATH -> 6;
            };
            // Gentle coordinate-based wander breaks the planning lattice without moving a route into a ravine.
            for (int i = 1; i < anchors.size() - 1; i++) {
                int[] point = anchors.get(i), prior = anchors.get(i - 1), next = anchors.get(i + 1);
                int px = point[0] + (int) Math.round(detail.noise(point[0] / 74.0 + id * 3.7,
                        point[1] / 74.0 - 14.0) * wander);
                int pz = point[1] + (int) Math.round(detail.noise(point[0] / 74.0 - 28.0,
                        point[1] / 74.0 + id * 2.1) * wander);
                if (linePassable(prior[0], prior[1], px, pz) && linePassable(px, pz, next[0], next[1])) {
                    anchors.set(i, new int[]{px, pz});
                }
            }
            for (int i = 0; i < anchors.size() - 1; i++) {
                int[] p0 = anchors.get(Math.max(0, i - 1)), p1 = anchors.get(i);
                int[] p2 = anchors.get(i + 1), p3 = anchors.get(Math.min(anchors.size() - 1, i + 2));
                List<int[]> curve = new ArrayList<>();
                curve.add(p1);
                int steps = Math.max(2, (int) Math.ceil(Math.hypot(p2[0] - p1[0], p2[1] - p1[1]) / 5.0));
                boolean passable = true;
                for (int step = 1; step <= steps; step++) {
                    double t = step / (double) steps;
                    int px = (int) Math.round(catmullRom(p0[0], p1[0], p2[0], p3[0], t));
                    int pz = (int) Math.round(catmullRom(p0[1], p1[1], p2[1], p3[1], t));
                    int[] prior = curve.get(curve.size() - 1);
                    if (!linePassable(prior[0], prior[1], px, pz)) { passable = false; break; }
                    addPoint(curve, px, pz);
                }
                if (passable) {
                    for (int segment = 0; segment < curve.size() - 1; segment++) {
                        int[] a = curve.get(segment), b = curve.get(segment + 1);
                        result.add(new RouteSegment(a[0], a[1], b[0], b[1], planningStyle, halfWidth, wear, id));
                    }
                } else {
                    // An unsafe corner keeps its already checked straight connection, never an unchecked curve.
                    result.add(new RouteSegment(p1[0], p1[1], p2[0], p2[1], planningStyle, halfWidth, wear, id));
                }
            }
        }

        private void addPoint(List<int[]> points, int x, int z) {
            int[] previous = points.get(points.size() - 1);
            if (previous[0] != x || previous[1] != z) points.add(new int[]{x, z});
        }

        private double catmullRom(double p0, double p1, double p2, double p3, double t) {
            return 0.5 * ((2 * p1) + (-p0 + p2) * t
                    + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
                    + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t);
        }
    }
}
