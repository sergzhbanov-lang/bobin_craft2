package com.bobincraft.warzone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.bobincraft.warzone.WarzoneNoise.smoothstep;

/**
 * Pure, bounded forest plans for the natural Warzone. Every touched chunk replays the same
 * ordered plants, including roots outside that chunk, and clips only its own block writes.
 * Nothing here loads chunks or runs after generation. Legacy forests use WarzoneFlora.
 */
public final class NaturalForest {
    static final int LOG = 1, LEAF = 2, ROCK = 3;
    static final int BARK = 0, PINE = 1, ROTTEN = 2, BIRCH = 3;
    static final int Y = 0, X = 1, Z = 2;
    static final int TREE_CELL = 6, FLOOR_CELL = 8, MAX_REACH = 10;
    private static final int PLAN_CACHE_LIMIT = 4096, SHAPE_CACHE_LIMIT = 128;
    private static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] FOUR = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    public enum Kind { PINE, SPRUCE, TWISTED, DEAD, YOUNG_PINE, BIRCH, FALLEN, STUMP, BUSH, BOULDER, BRANCH }

    public record Plant(int x, int y, int z, Kind kind, long seed, int cellX, int cellZ, boolean floor) { }

    @FunctionalInterface
    public interface Visitor { void at(int dx, int dy, int dz, int code); }

    /** Coordinates relative to one root; insertion order is stable across JVMs and chunks. */
    public static final class Shape {
        private final LinkedHashMap<Long, Integer> blocks = new LinkedHashMap<>();
        public int size() { return blocks.size(); }
        public void forEach(Visitor visitor) {
            blocks.forEach((k, c) -> visitor.at(dx(k), dy(k), dz(k), c));
        }
        private void log(int x, int y, int z, int material, int axis) {
            blocks.put(key(x, y, z), LOG | material << 4 | axis << 8);
        }
        private void leaf(int x, int y, int z, int material) {
            blocks.putIfAbsent(key(x, y, z), LEAF | material << 4);
        }
        private void line(int x0, int y0, int z0, int x1, int y1, int z1, int material) {
            int ax = Math.abs(x1 - x0), ay = Math.abs(y1 - y0), az = Math.abs(z1 - z0);
            int axis = ay >= ax && ay >= az ? Y : ax >= az ? X : Z;
            int steps = Math.max(ax, Math.max(ay, az));
            log(x0, y0, z0, material, axis);
            int px = x0, py = y0, pz = z0;
            for (int i = 1; i <= steps; i++) {
                int tx = x0 + (int) Math.round((x1 - x0) * i / (double) steps);
                int ty = y0 + (int) Math.round((y1 - y0) * i / (double) steps);
                int tz = z0 + (int) Math.round((z1 - z0) * i / (double) steps);
                while (px != tx) { px += Integer.signum(tx - px); log(px, py, pz, material, axis); }
                while (pz != tz) { pz += Integer.signum(tz - pz); log(px, py, pz, material, axis); }
                while (py != ty) { py += Integer.signum(ty - py); log(px, py, pz, material, axis); }
            }
        }
        private void crown(int cx, int cy, int cz, double rx, double ry, double rz, int material, Random random) {
            for (int x = -(int) Math.ceil(rx); x <= (int) Math.ceil(rx); x++) {
                for (int y = -(int) Math.ceil(ry); y <= (int) Math.ceil(ry); y++) {
                    for (int z = -(int) Math.ceil(rz); z <= (int) Math.ceil(rz); z++) {
                        double d = x * x / (rx * rx) + y * y / (ry * ry) + z * z / (rz * rz);
                        if (d > 1 || d > 0.60 && random.nextDouble() < 0.17) continue;
                        leaf(cx + x, cy + y, cz + z, material);
                    }
                }
            }
        }
    }

    static int type(int code) { return code & 15; }
    static int material(int code) { return code >> 4 & 15; }
    static int axis(int code) { return code >> 8 & 3; }
    static long key(int x, int y, int z) { return (long) (x + 512) << 40 | (long) (y + 512) << 20 | z + 512L; }
    static int dx(long k) { return (int) (k >> 40 & 0xfffff) - 512; }
    static int dy(long k) { return (int) (k >> 20 & 0xfffff) - 512; }
    static int dz(long k) { return (int) (k & 0xfffff) - 512; }

    private record Cell(int x, int z, boolean floor) { }
    private record Planned(Plant plant) { }
    private final WarzoneTerrain terrain;
    private final long salt;
    private final WarzoneNoise corridors, undergrowth;
    private final Map<Cell, Planned> plans = new LinkedHashMap<>(256, .75f, true);
    private final Map<Plant, Shape> shapes = new LinkedHashMap<>(32, .75f, true);

    public NaturalForest(WarzoneTerrain terrain) {
        this.terrain = terrain;
        salt = WarzoneNoise.mix(terrain.seed + 510);
        corridors = new WarzoneNoise(WarzoneNoise.mix(terrain.seed + 511));
        undergrowth = new WarzoneNoise(WarzoneNoise.mix(terrain.seed + 512));
    }

    /** Natural winding gaps supplement explicit roads without cutting regular grid lanes. */
    public boolean movementCorridor(int x, int z) {
        return Math.abs(corridors.fbm(x / 39.0, z / 39.0, 2)) < .045;
    }

    public double undergrowth(int x, int z) {
        if (movementCorridor(x, z) || terrain.isReserved(x, z)) return 0;
        return terrain.forest(x, z) * smoothstep(-.23, .37, undergrowth.fbm(x / 15.0, z / 15.0, 2));
    }

    /** Stable tree order, followed by sparse floor obstacles. No neighboring chunks are accessed. */
    public List<Plant> plantsForChunk(int minX, int minZ) {
        List<Plant> result = new ArrayList<>();
        collect(result, minX, minZ, false);
        collect(result, minX, minZ, true);
        return result;
    }

    private void collect(List<Plant> out, int minX, int minZ, boolean floor) {
        int cell = floor ? FLOOR_CELL : TREE_CELL;
        for (int z = Math.floorDiv(minZ - MAX_REACH, cell); z <= Math.floorDiv(minZ + 15 + MAX_REACH, cell); z++) {
            for (int x = Math.floorDiv(minX - MAX_REACH, cell); x <= Math.floorDiv(minX + 15 + MAX_REACH, cell); x++) {
                Plant p = planned(x, z, floor);
                if (p != null && p.x + MAX_REACH >= minX && p.x - MAX_REACH <= minX + 15
                        && p.z + MAX_REACH >= minZ && p.z - MAX_REACH <= minZ + 15) out.add(p);
            }
        }
    }

    private Plant planned(int cx, int cz, boolean floor) {
        Cell cell = new Cell(cx, cz, floor);
        synchronized (plans) {
            Planned found = plans.get(cell);
            if (found != null) return found.plant;
        }
        Plant plan = plan(cx, cz, floor);
        synchronized (plans) {
            plans.put(cell, new Planned(plan));
            if (plans.size() > PLAN_CACHE_LIMIT) plans.remove(plans.keySet().iterator().next());
        }
        return plan;
    }

    private Plant plan(int cx, int cz, boolean floor) {
        int cell = floor ? FLOOR_CELL : TREE_CELL;
        long hash = WarzoneNoise.hash(salt + (floor ? 1 : 0), cx, cz);
        int x = cx * cell + (int) (unit(hash, 1) * cell);
        int z = cz * cell + (int) (unit(hash, 2) * cell);
        if (!terrain.inside(x, z) || terrain.edgeDistance(x, z) < 14 || terrain.isReserved(x, z)
                || movementCorridor(x, z)) return null;
        double density = terrain.forest(x, z);
        if (unit(hash, 3) > (floor ? .05 + .17 * density * density : .035 + .94 * density)) return null;
        // A lower-priority root yields to its immediate neighbors, avoiding adjacent trunk walls.
        if (!floor && !spaced(cx, cz, x, z, hash)) return null;
        if (floor && undergrowth(x, z) < .24) return null;
        WarzoneTerrain.Sample sample = terrain.sample(x, z);
        if (sample.height <= WarzoneTerrain.SEA_LEVEL + 1 || sample.channel > .06 || sample.pool > .1
                || sample.road > .01 || sample.path > .01 || sample.mountain > .82) return null;
        int h = sample.height;
        for (int[] d : FOUR) if (Math.abs(terrain.height(x + d[0] * 3, z + d[1] * 3) - h) > (floor ? 2 : 3)) return null;
        Kind kind;
        double choice = unit(hash, 4);
        if (floor) {
            kind = choice < .28 ? Kind.BUSH : choice < .47 ? Kind.FALLEN : choice < .64 ? Kind.STUMP
                    : choice < .84 ? Kind.BOULDER : Kind.BRANCH;
        } else {
            boolean wet = sample.region == WarzoneTerrain.Region.MARSH || sample.bog > .3;
            kind = choice < (wet ? .34 : .43) ? Kind.PINE : choice < .73 ? Kind.SPRUCE
                    : choice < .84 ? Kind.TWISTED : choice < .90 ? Kind.DEAD
                    : choice < .96 ? Kind.YOUNG_PINE : Kind.BIRCH;
        }
        return new Plant(x, h + 1, z, kind, WarzoneNoise.mix(hash + 7), cx, cz, floor);
    }

    private boolean spaced(int cx, int cz, int x, int z, long hash) {
        double priority = WarzoneNoise.unit(hash);
        for (int oz = -1; oz <= 1; oz++) for (int ox = -1; ox <= 1; ox++) {
            if (ox == 0 && oz == 0) continue;
            long other = WarzoneNoise.hash(salt, cx + ox, cz + oz);
            if (WarzoneNoise.unit(other) >= priority) continue;
            int nx = (cx + ox) * TREE_CELL + (int) (unit(other, 1) * TREE_CELL);
            int nz = (cz + oz) * TREE_CELL + (int) (unit(other, 2) * TREE_CELL);
            if ((nx - x) * (nx - x) + (nz - z) * (nz - z) < 16) return false;
        }
        return true;
    }

    private static double unit(long hash, int offset) { return WarzoneNoise.unit(WarzoneNoise.mix(hash + offset)); }
    private static int between(Random random, int low, int high) { return low + random.nextInt(high - low + 1); }

    /** Shapes are cached only near active generation, rather than retaining an entire forest. */
    public Shape shape(Plant plant) {
        synchronized (shapes) {
            Shape found = shapes.get(plant);
            if (found != null) return found;
        }
        Shape result = rawShape(plant);
        anchor(plant, result);
        synchronized (shapes) {
            shapes.put(plant, result);
            if (shapes.size() > SHAPE_CACHE_LIMIT) shapes.remove(shapes.keySet().iterator().next());
        }
        return result;
    }

    Shape rawShape(Plant plant) {
        Shape s = new Shape();
        Random r = new Random(plant.seed);
        switch (plant.kind) {
            case PINE -> pine(s, r);
            case SPRUCE -> spruce(s, r, false);
            case TWISTED -> twisted(s, r, false);
            case DEAD -> twisted(s, r, true);
            case YOUNG_PINE -> spruce(s, r, true);
            case BIRCH -> birch(s, r);
            case FALLEN, BRANCH -> fallen(s, r, plant);
            case STUMP -> {
                s.line(0, 0, 0, 0, between(r, 1, 2), 0, ROTTEN);
                int[] d = FOUR[r.nextInt(4)];
                s.log(d[0], 0, d[1], BARK, d[0] == 0 ? Z : X);
            }
            case BUSH -> {
                s.log(0, 0, 0, BARK, Y);
                s.crown(0, 1, 0, 1.7, 1.2, 1.5, PINE, r);
            }
            case BOULDER -> {
                if (terrain.usesLandscapeRivers()) {
                    boulder(s, r);
                    break;
                }
                double rx = 1.4 + r.nextDouble() * .9, rz = 1.2 + r.nextDouble() * .8;
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 0; y <= 2; y++) {
                    if (x * x / (rx * rx) + z * z / (rz * rz) + y * y / 3.0 <= 1)
                        s.blocks.put(key(x, y, z), ROCK);
                }
            }
        }
        return s;
    }

    /** Low glacial slabs, upright split outcrops and rounded boulders share no fixed silhouette. */
    private static void boulder(Shape shape, Random random) {
        int form = random.nextInt(3);
        double rx = form == 0 ? 2.2 + random.nextDouble() : 1.2 + random.nextDouble() * 1.2;
        double rz = form == 0 ? 1.5 + random.nextDouble() : 1.1 + random.nextDouble();
        double ry = form == 0 ? .7 + random.nextDouble() * .4 : form == 1 ? 2.1 + random.nextDouble() : 1.3 + random.nextDouble();
        boolean alongX = random.nextBoolean();
        double lean = random.nextDouble() * .45;
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y = 0; y <= 3; y++) {
            double xx = x - y * lean, zz = z + y * lean;
            double d = xx * xx / (rx * rx) + zz * zz / (rz * rz) + y * y / (ry * ry);
            // Chip the outer corners, keeping a solid connected core and a broad supported base.
            if (d <= 1 && (d < .66 || random.nextDouble() > .20))
                shape.blocks.put(key(alongX ? x : z, y, alongX ? z : x), ROCK);
        }
    }

    /** Tall open lower trunks and asymmetrical branch clusters distinguish pine from spruce. */
    private void pine(Shape s, Random r) {
        int h = between(r, 17, 24), bend = between(r, 7, 11);
        int[] lean = FOUR[r.nextInt(4)];
        s.line(0, 0, 0, 0, bend, 0, BARK);
        s.line(0, bend, 0, lean[0], bend + 1, lean[1], BARK);
        s.line(lean[0], bend + 1, lean[1], lean[0], h, lean[1], BARK);
        double phase = r.nextDouble() * Math.PI * 2;
        for (int i = 0, count = between(r, 5, 7); i < count; i++) {
            int y = h - 2 - i * 2;
            if (y < 9) break;
            double a = phase + i * 2.4 + r.nextDouble() * .6;
            int len = between(r, 2, 4), tx = lean[0] + (int) Math.round(Math.cos(a) * len);
            int tz = lean[1] + (int) Math.round(Math.sin(a) * len);
            s.line(lean[0], y, lean[1], tx, y + 1, tz, PINE);
            s.crown(tx, y + 1, tz, 2.1 + r.nextDouble() * .5, 1.2, 2.0 + r.nextDouble() * .5, PINE, r);
        }
        s.crown(lean[0], h, lean[1], 2.5, 1.7, 2.1, PINE, r);
        if (r.nextBoolean()) s.line(0, bend - 1, 0, -lean[1] * 2, bend - 1, lean[0] * 2, ROTTEN);
    }

    /** Uneven layered whorls, small branch tips and shifted skirts avoid a repeated solid cone. */
    private void spruce(Shape s, Random r, boolean young) {
        int h = young ? between(r, 5, 8) : between(r, 14, 21);
        s.line(0, 0, 0, 0, h, 0, young ? PINE : BARK);
        int bottom = young ? 2 : between(r, 5, 7);
        double max = young ? 2.0 : 3.5 + r.nextDouble();
        double phase = r.nextDouble() * Math.PI * 2;
        for (int y = bottom; y < h; y += 2) {
            double radius = Math.max(1, max * (h - y) / (h - bottom));
            for (int branch = 0; branch < 4; branch++) {
                if (branch == 3 && r.nextDouble() < .3) continue;
                double angle = phase + branch * Math.PI / 2 + r.nextDouble() * .45;
                int tx = (int) Math.round(Math.cos(angle) * radius);
                int tz = (int) Math.round(Math.sin(angle) * radius);
                s.line(0, y, 0, tx, y - 1, tz, PINE);
                s.crown(tx, y, tz, young ? 1.15 : 1.8, 1.1, young ? 1.15 : 1.8, PINE, r);
            }
            s.crown(0, y + 1, 0, Math.max(1.0, radius * .72), 1.15, Math.max(1.0, radius * .72), PINE, r);
            phase += .8;
        }
        s.leaf(0, h + 1, 0, PINE);
        s.leaf(0, h + 2, 0, PINE);
    }

    private void twisted(Shape s, Random r, boolean dead) {
        int h = dead ? between(r, 8, 17) : between(r, 10, 16);
        int[] lean = FOUR[r.nextInt(4)];
        int bend1 = h / 3, bend2 = 2 * h / 3;
        s.line(0, 0, 0, 0, bend1, 0, BARK);
        s.line(0, bend1, 0, lean[0] * 2, bend2, lean[1] * 2, BARK);
        s.line(lean[0] * 2, bend2, lean[1] * 2, lean[0] * 3, h, lean[1] * 3, BARK);
        int[] positions = {bend1 + 1, bend2, h - 1};
        double phase = r.nextDouble() * Math.PI * 2;
        for (int i = 0; i < positions.length; i++) {
            int y = positions[i], bx = i == 0 ? lean[0] : lean[0] * 2, bz = i == 0 ? lean[1] : lean[1] * 2;
            // Explicitly connect each branch to the bent trunk at its actual height.
            s.line(bx, y, bz, lean[0] * 2, bend2, lean[1] * 2, BARK);
            double angle = phase + i * 2.25;
            int len = dead ? between(r, 2, 4) : between(r, 3, 5);
            int tx = bx + (int) Math.round(Math.cos(angle) * len), tz = bz + (int) Math.round(Math.sin(angle) * len);
            s.line(bx, y, bz, tx, y + (dead ? 0 : 2), tz, dead ? ROTTEN : PINE);
            if (!dead || i == 2 && r.nextDouble() < .35)
                s.crown(tx, y + 2, tz, dead ? 1.4 : 2.7, 1.3, dead ? 1.4 : 2.3, PINE, r);
        }
        if (!dead) s.crown(lean[0] * 3, h, lean[1] * 3, 2.2, 1.4, 2.6, PINE, r);
    }

    private void birch(Shape s, Random r) {
        int h = between(r, 10, 15);
        int[] lean = FOUR[r.nextInt(4)];
        s.line(0, 0, 0, 0, h / 2, 0, BIRCH);
        s.line(0, h / 2, 0, lean[0], h / 2 + 1, lean[1], BIRCH);
        s.line(lean[0], h / 2 + 1, lean[1], lean[0], h, lean[1], BIRCH);
        s.crown(lean[0], h - 1, lean[1], 2.3, 3.1, 2.0, BIRCH, r);
        s.line(lean[0], h - 4, lean[1], -lean[1] * 2, h - 2, lean[0] * 2, BIRCH);
        s.crown(-lean[1] * 2, h - 2, lean[0] * 2, 1.7, 1.8, 1.7, BIRCH, r);
    }

    private void fallen(Shape s, Random r, Plant plant) {
        int[] d = FOUR[r.nextInt(4)];
        int length = plant.kind == Kind.BRANCH ? between(r, 2, 4) : between(r, 5, 9);
        int lastY = 0;
        s.log(0, 0, 0, ROTTEN, d[0] == 0 ? Z : X);
        for (int i = 1; i <= length; i++) {
            int x = d[0] * i, z = d[1] * i;
            int y = terrain.height(plant.x + x, plant.z + z) + 1 - plant.y;
            if (Math.abs(y - lastY) > 1 || Math.abs(y) > 3) break;
            s.line(d[0] * (i - 1), lastY, d[1] * (i - 1), x, y, z, ROTTEN);
            if (plant.kind == Kind.FALLEN && i == length / 2) s.line(x, y, z, x - d[1], y + 1, z + d[0], ROTTEN);
            lastY = y;
        }
    }

    /** Prune terrain collisions, reserved columns and unsupported fragments before chunk clipping. */
    private void anchor(Plant plant, Shape shape) {
        Map<Long, Integer> heights = new HashMap<>();
        Set<Long> reserved = new HashSet<>();
        boolean mature = !plant.floor && plant.kind != Kind.YOUNG_PINE;
        shape.blocks.entrySet().removeIf(e -> {
            long k = e.getKey();
            int x = plant.x + dx(k), z = plant.z + dz(k), y = plant.y + dy(k);
            if (Math.abs(dx(k)) > MAX_REACH || Math.abs(dz(k)) > MAX_REACH || !terrain.inside(x, z)) return true;
            long column = (long) x << 32 ^ z & 0xffffffffL;
            int h = heights.computeIfAbsent(column, ignored -> {
                if (terrain.isReserved(x, z)) reserved.add(column);
                return terrain.height(x, z);
            });
            if (reserved.contains(column) || y <= h || y >= WarzoneTerrain.HEIGHT) return true;
            if (y < h + 4 && movementCorridor(x, z)) return true;
            return mature && type(e.getValue()) == LEAF && y < h + 4;
        });
        // Every log must connect by faces to an actual supported root.
        ArrayDeque<Long> queue = new ArrayDeque<>();
        Set<Long> connected = new HashSet<>();
        for (var entry : shape.blocks.entrySet()) {
            long k = entry.getKey();
            if (type(entry.getValue()) != LOG) continue;
            long column = (long) (plant.x + dx(k)) << 32 ^ (plant.z + dz(k)) & 0xffffffffL;
            if (plant.y + dy(k) == heights.get(column) + 1) { queue.add(k); connected.add(k); }
        }
        while (!queue.isEmpty()) {
            long k = queue.remove();
            for (int[] d : SIX) {
                long next = key(dx(k) + d[0], dy(k) + d[1], dz(k) + d[2]);
                Integer code = shape.blocks.get(next);
                if (code != null && type(code) == LOG && connected.add(next)) queue.add(next);
            }
        }
        shape.blocks.entrySet().removeIf(e -> type(e.getValue()) == LOG && !connected.contains(e.getKey()));
        Map<Long, Integer> distance = new HashMap<>();
        for (long k : connected) { distance.put(k, 0); queue.add(k); }
        while (!queue.isEmpty()) {
            long k = queue.remove();
            int depth = distance.get(k);
            if (depth >= 6) continue;
            for (int[] d : SIX) {
                long next = key(dx(k) + d[0], dy(k) + d[1], dz(k) + d[2]);
                Integer code = shape.blocks.get(next);
                if (code != null && type(code) == LEAF && !distance.containsKey(next)) {
                    distance.put(next, depth + 1); queue.add(next);
                }
            }
        }
        shape.blocks.entrySet().removeIf(e -> type(e.getValue()) == LEAF && !distance.containsKey(e.getKey()));
        if (terrain.usesLandscapeRivers() && plant.kind == Kind.BOULDER) {
            Set<Long> rockSupport = new HashSet<>();
            for (var entry : shape.blocks.entrySet()) {
                long k = entry.getKey();
                long column = (long) (plant.x + dx(k)) << 32 ^ (plant.z + dz(k)) & 0xffffffffL;
                if (type(entry.getValue()) == ROCK && plant.y + dy(k) == heights.get(column) + 1) {
                    rockSupport.add(k);
                    queue.add(k);
                }
            }
            while (!queue.isEmpty()) {
                long k = queue.remove();
                for (int[] d : SIX) {
                    long next = key(dx(k) + d[0], dy(k) + d[1], dz(k) + d[2]);
                    Integer code = shape.blocks.get(next);
                    if (code != null && type(code) == ROCK && rockSupport.add(next)) queue.add(next);
                }
            }
            shape.blocks.entrySet().removeIf(e -> type(e.getValue()) == ROCK && !rockSupport.contains(e.getKey()));
        }
    }
}
