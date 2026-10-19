package com.bobincraft.warzone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bobincraft.warzone.WarzoneNoise.lerp;
import static com.bobincraft.warzone.WarzoneNoise.smoothstep;

/**
 * A bounded, immutable river plan. Every reach has one downstream end and tributaries share an
 * actual trunk vertex, including its elevation. The construction-time plan is spatially indexed;
 * sampling uses only primitive values and never allocates or depends on loaded chunks.
 */
final class NaturalDrainage {
    private static final int BUCKET = 64;
    private static final double STEP = 8;
    private final int size;
    private final double cos, sin, phase, offset, upstream, downstream, scale;
    private final double terminalX, terminalZ, terminalRadius;
    private final WarzoneNoise banks;
    private final double[] sourceX, sourceZ, sourceRadius, sourceRise;
    private final double[] ax, az, bx, bz, levelA, levelB, widthA, widthB, depthA, depthB, fordA, fordB;
    private final int[][] buckets;
    private final int bucketMinX, bucketMinZ, bucketSideX, bucketSideZ;

    private record Point(double x, double z, double level, double width, double depth, double ford) { }
    private record Segment(Point a, Point b) { }

    NaturalDrainage(long seed, int size, double cos, double sin, double phase, double offset) {
        this.size = size;
        this.cos = cos;
        this.sin = sin;
        this.phase = phase;
        this.offset = offset;
        scale = Math.min(1, size / 1024.0);
        upstream = -size * .36;
        terminalRadius = Math.max(12, Math.min(32, size * .016));
        double endpoint = size * .73;
        double terminalLimit = size * .5 - Math.min(100, size * .15);
        for (int attempt = 0; attempt < 80; attempt++) {
            double v = center(endpoint);
            double x = endpoint * cos - v * sin, z = endpoint * sin + v * cos;
            if (Math.max(Math.abs(x), Math.abs(z)) < terminalLimit) break;
            endpoint *= .97;
        }
        downstream = endpoint;
        double terminalV = center(downstream);
        terminalX = downstream * cos - terminalV * sin;
        terminalZ = downstream * sin + terminalV * cos;
        banks = new WarzoneNoise(WarzoneNoise.mix(seed + 181));
        int tributaries = 4 + (int) (WarzoneNoise.mix(seed + 182) & 1);
        sourceX = new double[tributaries + 1];
        sourceZ = new double[tributaries + 1];
        sourceRadius = new double[tributaries + 1];
        sourceRise = new double[tributaries + 1];
        List<Segment> segments = new ArrayList<>();
        int steps = (int) Math.ceil((downstream - upstream) / STEP);
        Point[] trunk = new Point[steps + 1];
        for (int i = 0; i <= steps; i++) {
            double u = lerp(upstream, downstream, i / (double) steps);
            double downstreamFraction = i / (double) steps;
            double elevation = trunkLevel(u);
            double width = 5.0 + 10.5 * smoothstep(0, .62, downstreamFraction)
                    + Math.sin(u / 121.0 + phase) * 1.7
                    + Math.sin(u / 53.0 - phase) * .7;
            double depth = 5.0 + 4.3 * smoothstep(0, .52, downstreamFraction)
                    + Math.sin(u / 97.0 + phase) * .65;
            trunk[i] = avoidLanding(point(u, center(u), elevation, width, depth,
                    ford(u, size * .12, Math.max(130, size * .17))), 1);
            if (i > 0) segments.add(new Segment(trunk[i - 1], trunk[i]));
        }
        // The spring sits below its source ridge; a finite upstream endpoint forms a round pool.
        setSource(0, trunk[0], Math.max(80, size * .075), 42);
        for (int branch = 0; branch < tributaries; branch++) {
            long hash = WarzoneNoise.mix(seed + 190 + branch);
            double joinFraction = .18 + branch * .53 / (tributaries - 1)
                    + (WarzoneNoise.unit(hash) - .5) * .025;
            int joinIndex = (int) Math.round(joinFraction * steps);
            Point join = trunk[joinIndex];
            double joinU = lerp(upstream, downstream, joinIndex / (double) steps);
            double side = branch % 2 == 0 ? 1 : -1;
            double length = size * (.22 + WarzoneNoise.unit(WarzoneNoise.mix(hash + 1)) * .09);
            double bend = -.21 + WarzoneNoise.unit(WarzoneNoise.mix(hash + 2)) * .42;
            // Keep the sources inside the playable terrain rather than terminating behind the rim.
            double sourceLimit = size * .5 - Math.min(110, size * .12);
            for (int attempt = 0; attempt < 24; attempt++) {
                double endU = joinU + length * bend + Math.sin(length / 91.0) * 15
                        + Math.sin(length / 43.0) * 4;
                double endV = center(endU) + side * length;
                double endX = endU * cos - endV * sin, endZ = endU * sin + endV * cos;
                if (Math.max(Math.abs(endX), Math.abs(endZ)) < sourceLimit) break;
                length *= .86;
            }
            double headRise = 6 + WarzoneNoise.unit(WarzoneNoise.mix(hash + 3)) * 8;
            int branchSteps = Math.max(2, (int) Math.ceil(length / STEP));
            Point previous = join;
            for (int i = 1; i <= branchSteps; i++) {
                double t = i / (double) branchSteps;
                double distance = t * length;
                // The taper makes bends and elevation derivatives vanish at the confluence.
                double lateral = distance * bend
                        + Math.sin(distance / 91.0) * 15 * smoothstep(0, .18, t)
                        + Math.sin(distance / 43.0) * 4 * smoothstep(0, .12, t);
                double u = joinU + lateral;
                double v = center(u) + side * distance;
                double elevation = join.level + headRise * t * t;
                double width = lerp(Math.min(join.width * .68, 9.5), 3.5, t)
                        + Math.sin(distance / 61.0) * .65 * Math.sin(t * Math.PI);
                double depth = lerp(6.8, 3.8, t);
                Point next = avoidLanding(point(u, v, elevation, width, depth, ford(distance, 112, 220)),
                        join.x >= 0 ? 1 : -1);
                // Segments are always oriented downstream, including those on opposite riverbanks.
                segments.add(new Segment(next, previous));
                previous = next;
            }
            setSource(branch + 1, previous, Math.max(60, size * .052), 18 + headRise);
        }

        int count = segments.size();
        ax = new double[count]; az = new double[count]; bx = new double[count]; bz = new double[count];
        levelA = new double[count]; levelB = new double[count];
        widthA = new double[count]; widthB = new double[count];
        depthA = new double[count]; depthB = new double[count];
        fordA = new double[count]; fordB = new double[count];
        Map<Long, List<Integer>> index = new HashMap<>();
        int indexMinX = Integer.MAX_VALUE, indexMinZ = Integer.MAX_VALUE;
        int indexMaxX = Integer.MIN_VALUE, indexMaxZ = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            Segment segment = segments.get(i);
            Point a = segment.a, b = segment.b;
            ax[i] = a.x; az[i] = a.z; bx[i] = b.x; bz[i] = b.z;
            levelA[i] = a.level; levelB[i] = b.level;
            widthA[i] = a.width; widthB[i] = b.width;
            depthA[i] = a.depth; depthB[i] = b.depth;
            fordA[i] = a.ford; fordB[i] = b.ford;
            double margin = Math.max(Math.max(a.width, b.width) + 48, terminalRadius + 30);
            int minX = (int) Math.floor((Math.min(a.x, b.x) - margin) / BUCKET);
            int maxX = (int) Math.floor((Math.max(a.x, b.x) + margin) / BUCKET);
            int minZ = (int) Math.floor((Math.min(a.z, b.z) - margin) / BUCKET);
            int maxZ = (int) Math.floor((Math.max(a.z, b.z) + margin) / BUCKET);
            indexMinX = Math.min(indexMinX, minX); indexMaxX = Math.max(indexMaxX, maxX);
            indexMinZ = Math.min(indexMinZ, minZ); indexMaxZ = Math.max(indexMaxZ, maxZ);
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    index.computeIfAbsent(bucket(x, z), unused -> new ArrayList<>()).add(i);
                }
            }
        }
        bucketMinX = indexMinX; bucketMinZ = indexMinZ;
        bucketSideX = indexMaxX - indexMinX + 1; bucketSideZ = indexMaxZ - indexMinZ + 1;
        buckets = new int[bucketSideX * bucketSideZ][];
        // Dense bounded lookup avoids boxing a Long key for every sampled column.
        index.forEach((key, values) -> {
            int x = (int) (key >> 32), z = (int) (long) key;
            buckets[(z - bucketMinZ) * bucketSideX + x - bucketMinX] =
                    values.stream().mapToInt(Integer::intValue).toArray();
        });
    }

    int tributaryCount() { return sourceX.length - 1; }

    private double center(double u) {
        return offset + scale * (Math.sin(u / 240.0 + phase) * 56
                + Math.sin(u / 103.0 - phase * .7) * 18
                + Math.sin(u / 49.0 + phase * 1.3) * 5);
    }

    private double trunkLevel(double u) {
        double rise = 1 - smoothstep(upstream, -size * .06, u);
        return WarzoneTerrain.SEA_LEVEL + 24 * rise;
    }

    private Point point(double u, double v, double level, double width, double depth, double ford) {
        return new Point(u * cos - v * sin, u * sin + v * cos, level, width, depth, ford);
    }

    private Point avoidLanding(Point point, double side) {
        // Route the planned centerline around the protected landing rather than letting the later
        // flat-pad blend dam a stream, flood the player spawn, or split its water into two pieces.
        double radius = Math.min(140, size * .22);
        double dz = Math.abs(point.z - size * .34);
        double transition = Math.min(75, size * .10);
        if (dz >= radius + transition) return point;
        double minimumX = dz < radius ? Math.sqrt(radius * radius - dz * dz) : 0;
        double alongSide = point.x * side;
        double target = Math.max(alongSide, minimumX);
        double blend = 1 - smoothstep(radius, radius + transition, dz);
        double x = lerp(point.x, target * side, blend);
        return new Point(x, point.z, point.level, point.width, point.depth, point.ford);
    }

    private void setSource(int i, Point point, double radius, double rise) {
        sourceX[i] = point.x; sourceZ[i] = point.z;
        sourceRadius[i] = radius; sourceRise[i] = rise;
    }

    double mountainLift(int x, int z) {
        double lift = 0;
        for (int i = 0; i < sourceX.length; i++) {
            double dx = (x - sourceX[i]) / sourceRadius[i];
            double dz = (z - sourceZ[i]) / sourceRadius[i];
            double r2 = dx * dx + dz * dz;
            if (r2 < 9) lift += sourceRise[i] * Math.exp(-r2 * .72);
        }
        return lift;
    }

    double mountainMask(int x, int z) {
        double mask = 0;
        for (int i = 0; i < sourceX.length; i++) {
            double distance = Math.hypot(x - sourceX[i], z - sourceZ[i]) / sourceRadius[i];
            mask = Math.max(mask, (1 - smoothstep(.28, 1.7, distance)) * (i == 0 ? .90 : .68));
        }
        return mask;
    }

    double carve(int x, int z, double small, double height, WarzoneTerrain.Sample out) {
        int bucketX = Math.floorDiv(x, BUCKET) - bucketMinX;
        int bucketZ = Math.floorDiv(z, BUCKET) - bucketMinZ;
        if (bucketX < 0 || bucketZ < 0 || bucketX >= bucketSideX || bucketZ >= bucketSideZ) return height;
        int[] candidates = buckets[bucketZ * bucketSideX + bucketX];
        if (candidates == null) return height;
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY, nearestT = 0;
        for (int i : candidates) {
            double dx = bx[i] - ax[i], dz = bz[i] - az[i];
            double t = Math.max(0, Math.min(1, ((x - ax[i]) * dx + (z - az[i]) * dz) / (dx * dx + dz * dz)));
            double distance = Math.hypot(x - lerp(ax[i], bx[i], t), z - lerp(az[i], bz[i], t));
            double score = distance / lerp(widthA[i], widthB[i], t);
            if (score < bestDistance) { best = i; bestDistance = score; nearestT = t; }
        }
        int i = best;
        double t = nearestT;
        double width = lerp(widthA[i], widthB[i], t);
        double signed = (bx[i] - ax[i]) * (z - az[i]) - (bz[i] - az[i]) * (x - ax[i]);
        double bankBias = banks.noise(x / 67.0 + 8, z / 67.0 - 4);
        double asymmetry = signed >= 0 ? 1 + bankBias * .12 : 1 - bankBias * .12;
        double roughness = banks.noise(x / 19.0, z / 19.0) * .55;
        double distance = Math.max(0, bestDistance * width * asymmetry + roughness);
        double level = lerp(levelA[i], levelB[i], t);
        int waterLevel = (int) Math.floor(level + 1e-6);
        double valleySpan = width + 34 + Math.abs(bankBias) * 10;
        double valley = 1 - smoothstep(width * .7, valleySpan, distance);
        double lakeDistance = Math.hypot(x - terminalX, z - terminalZ)
                + banks.noise(x / 23.0 + 7, z / 23.0 - 5) * .6;
        if (valley <= 0 && lakeDistance >= terminalRadius + 28) return height;
        double channel = 1 - smoothstep(width * .52, width + 1.7, distance);
        double bankRise = smoothstep(width * .88, valleySpan, distance) * 4.5;
        double bank = waterLevel + .75 + bankRise + small * .28;
        // Elevated headwaters have their own sloping valley. A gentle raised bank also prevents
        // local terrain hollows from letting a mountain stream hang above the surrounding ground.
        height = lerp(height, bank, valley * .98);
        double normalized = Math.min(1, distance / width);
        double profile = Math.pow(1 - normalized * normalized, .72);
        double depth = 2.4 + (lerp(depthA[i], depthB[i], t) - 2.4) * profile;
        double ford = lerp(fordA[i], fordB[i], t);
        double bed = waterLevel - depth + ford * (depth - .45) + small * .16 * (1 - ford);
        height = lerp(height, Math.min(height, bed), channel);
        out.channel = channel;
        out.valley = Math.max(out.valley, valley);
        // Only columns close enough to the channel receive an elevated water surface; the valley
        // skirt and ordinary ponds retain the global sea level.
        if (height < waterLevel && channel > .05) out.waterLevel = waterLevel;
        double length = Math.hypot(bx[i] - ax[i], bz[i] - az[i]);
        double grade = (levelA[i] - levelB[i]) / length;
        WarzoneTerrain.WaterState state = grade > .045 ? WarzoneTerrain.WaterState.RAPIDS
                : ford > .75 || width > 14.2 ? WarzoneTerrain.WaterState.SLOW
                : WarzoneTerrain.WaterState.FLOWING;
        out.waterState = state;
        double speed = state == WarzoneTerrain.WaterState.RAPIDS ? 1
                : state == WarzoneTerrain.WaterState.SLOW ? .22 : .58;
        out.flowX = (bx[i] - ax[i]) / length * speed;
        out.flowZ = (bz[i] - az[i]) / length * speed;
        if (lakeDistance < terminalRadius + 28) {
            // The enclosed arena has no sea outlet. Its river therefore ends in a real lowland
            // catchment lake before the rock rim, where the current visibly settles to still water.
            double basin = 1 - smoothstep(terminalRadius * .80, terminalRadius + 28, lakeDistance);
            double lakeChannel = 1 - smoothstep(terminalRadius * .58, terminalRadius + 1.5, lakeDistance);
            double lakeBank = WarzoneTerrain.SEA_LEVEL + .8
                    + smoothstep(terminalRadius * .9, terminalRadius + 28, lakeDistance) * 4;
            // A lake may lower its shoreline, but must never fill the incoming channel or a ford.
            height = lerp(height, Math.min(height, lakeBank), basin * .98);
            double core = 1 - smoothstep(0, terminalRadius, lakeDistance);
            double lakeBed = WarzoneTerrain.SEA_LEVEL - 3.0 - core * 8 + small * .2;
            height = lerp(height, Math.min(height, lakeBed), lakeChannel);
            out.channel = Math.max(out.channel, lakeChannel);
            out.valley = Math.max(out.valley, basin);
            if (height < WarzoneTerrain.SEA_LEVEL) out.waterLevel = WarzoneTerrain.SEA_LEVEL;
            if (lakeDistance < terminalRadius * .88) {
                out.waterState = WarzoneTerrain.WaterState.STILL;
                out.flowX = out.flowZ = 0;
            }
        }
        return height;
    }

    private static double ford(double coordinate, double offset, double spacing) {
        double nearest = Math.rint((coordinate - offset) / spacing) * spacing + offset;
        return 1 - smoothstep(12, 26, Math.abs(coordinate - nearest));
    }

    private static long bucket(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
