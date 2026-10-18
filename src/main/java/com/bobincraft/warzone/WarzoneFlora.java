package com.bobincraft.warzone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static com.bobincraft.warzone.WarzoneNoise.smoothstep;

/**
 * Процедурная флора Зоны боевых действий — ЧИСТАЯ Java (без Minecraft), проверяется headless-тестами.
 *
 * Как устроено (чтобы кроны не обрезались на границах чанков):
 *  - деревья и кусты привязаны к ячейкам сетки (деревья 4×4, кусты 3×3); положение, вид и форма каждого растения —
 *    чистая функция от (seed, ячейка), поэтому любой чанк, чью область задевает крона, независимо получает те же растения;
 *  - форма растения — {@link Shape}: набор вокселей (dx, dy, dz, код блока) в координатах «от корня»;
 *  - порядок наложения растений каноничен (деревья по ячейкам z→x, затем кусты), правила наложения локальны
 *    ({@link #canPlace}), поэтому результат в каждом блоке не зависит от того, в каком чанке он строится.
 *
 * Густота: {@link #cover} — «покров» 0..1: крупные массивы леса, внутри них клочки чащи и поляны, а на открытых
 * местах — редкие рощицы. От покрова зависят и вероятность деревьев, и подлесок (кусты, папоротники, трава).
 */
final class WarzoneFlora {

    // ------------------------------------------------------------------ коды блоков

    static final int T_LOG = 1, T_LEAF = 2, T_VINE = 3, T_ROOTS = 4, T_CARPET = 5;
    static final int AXIS_Y = 0, AXIS_X = 1, AXIS_Z = 2;
    /** Грани лианы (какую сторону блока она прижимает): север = -z, восток = +x, юг = +z, запад = -x. */
    static final int F_N = 1, F_E = 2, F_S = 4, F_W = 8;

    enum Wood { OAK, BIRCH, SPRUCE, DARK_OAK, ACACIA, JUNGLE, MANGROVE, DEAD_PALE, DEAD_GREY }

    static int log(Wood w, int axis) { return T_LOG | (w.ordinal() << 4) | (axis << 8); }
    static int leaf(Wood w) { return T_LEAF | (w.ordinal() << 4); }
    static int vine(int faces) { return T_VINE | (faces << 10); }
    static int roots() { return T_ROOTS; }
    static int carpet() { return T_CARPET; }

    static int type(int c) { return c & 15; }
    static Wood wood(int c) { return Wood.values()[(c >> 4) & 15]; }
    static int axis(int c) { return (c >> 8) & 3; }
    static int faces(int c) { return (c >> 10) & 15; }

    /** Что уже стоит в клетке (для правил наложения). */
    enum Cat { AIR, WATER, PLANT, LEAF, LOG, SOLID }

    /**
     * Правила наложения: стволы/корни занимают воздух, воду, траву и листву (но не чужие стволы и не землю);
     * листья, лианы и мох — только пустой воздух. «Первый ствол побеждает» + канонический порядок = одинаково в любом чанке.
     */
    static boolean canPlace(int code, Cat existing) {
        switch (type(code)) {
            case T_LOG:
            case T_ROOTS:
                return existing == Cat.AIR || existing == Cat.WATER || existing == Cat.PLANT || existing == Cat.LEAF;
            default:
                return existing == Cat.AIR;
        }
    }

    // ------------------------------------------------------------------ виды растений

    enum Kind {
        // равнины
        OAK_CROOKED(7, false), ELM_VASE(7, false), BIRCH_PAIR(6, false), WINDSWEPT(10, false),
        // нагорье
        SPRUCE_TIER(6, false), PINE_GNARLED(8, false), SNAG(3, false), KRUMMHOLZ(7, false),
        // болото
        WILLOW(7, false), MANGROVE_STILT(7, false), DROWNED_SNAG(3, false), HAG_OAK(10, false),
        // пустыня и меза
        DEAD_TREE(5, false), ACACIA_PARASOL(8, false), PALM(8, false),
        // подлесок
        BUSH(3, true), SWAMP_BUSH(3, true), THORN_SCRUB(3, true), FALLEN_LOG(5, true);

        final int reach;
        final boolean shrub;

        Kind(int reach, boolean shrub) {
            this.reach = reach;
            this.shrub = shrub;
        }
    }

    /** Растение: мировые координаты корня (y — первая клетка над землёй), вид, порода, seed формы. */
    static final class Plant {
        final int x, y, z;
        final Kind kind;
        final Wood wood;
        final long seed;
        final int cellX, cellZ;
        final boolean shrubLayer;
        /** Подгонка формы под реальный рельеф (считается один раз при планировании; null до расчёта). */
        Fix fix;

        Plant(int x, int y, int z, Kind kind, Wood wood, long seed, int cellX, int cellZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.kind = kind;
            this.wood = wood;
            this.seed = seed;
            this.cellX = cellX;
            this.cellZ = cellZ;
            this.shrubLayer = kind.shrub;
        }
    }

    private static final Object NONE = new Object();
    static final int TREE_CELL = 4;
    static final int SHRUB_CELL = 3;
    private static final int MAX_TREE_REACH = 10;
    private static final int MAX_SHRUB_REACH = 5;

    private final WarzoneTerrain t;
    private final WarzoneNoise nMacro, nClump, nGlade, nGrove, nMeadow;
    private final long treeSalt, shrubSalt;
    private final ConcurrentHashMap<Long, Object> treeCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Object> shrubCache = new ConcurrentHashMap<>();

    WarzoneFlora(WarzoneTerrain t) {
        this.t = t;
        long s = t.seed;
        this.nMacro = new WarzoneNoise(WarzoneNoise.mix(s + 40));
        this.nClump = new WarzoneNoise(WarzoneNoise.mix(s + 41));
        this.nGlade = new WarzoneNoise(WarzoneNoise.mix(s + 42));
        this.nGrove = new WarzoneNoise(WarzoneNoise.mix(s + 43));
        this.nMeadow = new WarzoneNoise(WarzoneNoise.mix(s + 44));
        this.treeSalt = WarzoneNoise.mix(s + 45);
        this.shrubSalt = WarzoneNoise.mix(s + 46);
    }

    // ------------------------------------------------------------------ густота

    /**
     * Покров 0..1: 0 — открытая поляна, 1 — непролазная чаща. Крупные массивы (≈85 блоков) задают леса и степи,
     * внутри леса — клочки погуще (≈22 блока) и поляны (≈30 блоков), на открытых местах — небольшие рощицы.
     */
    double cover(int x, int z) {
        double macro = smoothstep(-0.30, 0.12, nMacro.fbm(x / 85.0 + 3.0, z / 85.0 - 8.0, 3));
        double clump = smoothstep(-0.3, 0.4, nClump.fbm(x / 22.0, z / 22.0, 2));
        double glade = smoothstep(0.25, 0.50, nGlade.fbm(x / 30.0 + 9.0, z / 30.0 + 4.0, 2));
        double grove = smoothstep(0.35, 0.55, nGrove.fbm(x / 13.0, z / 13.0, 2));
        double c = macro * (0.55 + 0.45 * clump);
        c *= 1.0 - 0.9 * glade * smoothstep(0.2, 0.8, macro);
        c = Math.max(c, 0.5 * grove * (1.0 - macro));
        return Math.max(0.0, Math.min(1.0, c));
    }

    /** Цветочная поляна: >0 — внутри пятна одного вида цветов; вид — {@link #meadowKind}. */
    double meadow(int x, int z) {
        return smoothstep(0.30, 0.55, nMeadow.fbm(x / 17.0 + 2.0, z / 17.0 - 6.0, 2));
    }

    int meadowKind(int x, int z, int kinds) {
        long h = WarzoneNoise.hash(treeSalt + 5, Math.floorDiv(x, 14), Math.floorDiv(z, 14));
        return (int) ((h >>> 20) % kinds);
    }

    // ------------------------------------------------------------------ выбор растений для чанка

    /** Все растения, чья форма может задеть чанк (minX, minZ). Порядок канонический: деревья по (z, x), затем кусты по (z, x). */
    List<Plant> plantsForChunk(int minX, int minZ) {
        List<Plant> out = new ArrayList<>();
        collect(out, minX, minZ, TREE_CELL, MAX_TREE_REACH, false);
        collect(out, minX, minZ, SHRUB_CELL, MAX_SHRUB_REACH, true);
        return out;
    }

    private void collect(List<Plant> out, int minX, int minZ, int cell, int reach, boolean shrubLayer) {
        int cx0 = Math.floorDiv(minX - reach, cell);
        int cx1 = Math.floorDiv(minX + 15 + reach, cell);
        int cz0 = Math.floorDiv(minZ - reach, cell);
        int cz1 = Math.floorDiv(minZ + 15 + reach, cell);
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                Plant p = cached(cx, cz, shrubLayer);
                if (p == null) continue;
                int r = p.kind.reach;
                if (p.x + r < minX || p.x - r > minX + 15 || p.z + r < minZ || p.z - r > minZ + 15) continue;
                out.add(p);
            }
        }
    }

    private Plant cached(int cx, int cz, boolean shrubLayer) {
        ConcurrentHashMap<Long, Object> map = shrubLayer ? shrubCache : treeCache;
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Object o = map.get(key);
        if (o == null) {
            Plant p = shrubLayer ? planShrub(cx, cz) : planTree(cx, cz);
            o = p == null ? NONE : p;
            map.putIfAbsent(key, o);
        }
        return o == NONE ? null : (Plant) o;
    }

    // ------------------------------------------------------------------ планирование деревьев

    private Plant planTree(int cx, int cz) {
        long h = WarzoneNoise.hash(treeSalt, cx, cz);
        int px = cx * TREE_CELL + (int) (WarzoneNoise.unit(WarzoneNoise.mix(h + 1)) * TREE_CELL);
        int pz = cz * TREE_CELL + (int) (WarzoneNoise.unit(WarzoneNoise.mix(h + 2)) * TREE_CELL);
        if (!t.inside(px, pz) || t.edgeDistance(px, pz) < 10) return null;
        double r = WarzoneNoise.unit(h);
        double c = cover(px, pz);
        // быстрый отсев: вне оазиса вероятность деревьев не превышает этой границы
        if (r > 0.04 + 0.9 * c && !t.oasisNear(px, pz)) return null;

        WarzoneTerrain.Sample s = t.sample(px, pz);
        double p = pTree(s, c);
        if (r >= p) return null;
        if (!validSpot(s, px, pz, 2.2, true)) return null;

        double u = WarzoneNoise.unit(WarzoneNoise.mix(h + 3));
        Kind kind = pickTree(s, c, u);
        if (kind == null) return null;
        Wood wood = woodFor(kind, h);
        Plant pl = new Plant(px, s.height + 1, pz, kind, wood, WarzoneNoise.mix(h + 4), cx, cz);
        pl.fix = anchor(pl);
        return pl.fix == null ? null : pl;
    }

    private double pTree(WarzoneTerrain.Sample s, double c) {
        switch (s.region) {
            case PLAINS:
                return 0.03 + 0.95 * Math.pow(c, 1.1);
            case HIGHLANDS:
                return (0.03 + 0.85 * Math.pow(c, 1.1)) * (1.0 - smoothstep(100.0, 132.0, s.height));
            case MARSH:
                return (0.10 + 0.70 * c) * (1.0 - smoothstep(0.2, 0.5, s.pool));
            default:
                if (s.desert > 0.5) return s.oasis > 0.3 ? 0.55 * s.oasis : 0.012 + 0.03 * c;
                return 0.02 + 0.04 * c;
        }
    }

    private Kind pickTree(WarzoneTerrain.Sample s, double c, double u) {
        switch (s.region) {
            case PLAINS: {
                double wOpen = c < 0.25 ? 30 : 12;       // одинокие деревья на ветру — на открытых местах
                return pick(u, new Kind[]{Kind.OAK_CROOKED, Kind.ELM_VASE, Kind.BIRCH_PAIR, Kind.WINDSWEPT},
                        new double[]{35, 20, 25, wOpen});
            }
            case HIGHLANDS: {
                boolean high = s.height > 88;
                return pick(u, new Kind[]{Kind.SPRUCE_TIER, Kind.PINE_GNARLED, Kind.SNAG, Kind.KRUMMHOLZ},
                        new double[]{high ? 25 : 50, 20, high ? 20 : 12, high ? 35 : 18});
            }
            case MARSH: {
                boolean wet = s.height < WarzoneTerrain.SEA_LEVEL;
                return pick(u, new Kind[]{Kind.WILLOW, Kind.MANGROVE_STILT, Kind.DROWNED_SNAG, Kind.HAG_OAK},
                        wet ? new double[]{25, 45, 30, 0} : new double[]{30, 28, 12, 30});
            }
            default:
                if (s.desert > 0.5) {
                    if (s.oasis > 0.3) {
                        return pick(u, new Kind[]{Kind.PALM, Kind.ACACIA_PARASOL, Kind.DEAD_TREE}, new double[]{70, 20, 10});
                    }
                    return pick(u, new Kind[]{Kind.DEAD_TREE, Kind.ACACIA_PARASOL}, new double[]{55, 45});
                }
                return pick(u, new Kind[]{Kind.ACACIA_PARASOL, Kind.DEAD_TREE}, new double[]{40, 60});
        }
    }

    private static Kind pick(double u, Kind[] kinds, double[] weights) {
        double sum = 0;
        for (double w : weights) sum += w;
        if (sum <= 0) return null;
        double x = u * sum;
        for (int i = 0; i < kinds.length; i++) {
            x -= weights[i];
            if (x < 0) return kinds[i];
        }
        return kinds[kinds.length - 1];
    }

    private Wood woodFor(Kind kind, long h) {
        boolean alt = (WarzoneNoise.mix(h + 9) & 1) == 0;
        switch (kind) {
            case OAK_CROOKED:
            case ELM_VASE:
            case WINDSWEPT:
                return Wood.OAK;
            case BIRCH_PAIR:
                return Wood.BIRCH;
            case SPRUCE_TIER:
            case PINE_GNARLED:
            case SNAG:
            case KRUMMHOLZ:
                return Wood.SPRUCE;
            case WILLOW:
                return Wood.OAK;
            case MANGROVE_STILT:
                return Wood.MANGROVE;
            case DROWNED_SNAG:
            case HAG_OAK:
                return Wood.DARK_OAK;
            case DEAD_TREE:
                return alt ? Wood.DEAD_PALE : Wood.DEAD_GREY;
            case ACACIA_PARASOL:
                return Wood.ACACIA;
            case PALM:
                return Wood.JUNGLE;
            default:
                return Wood.OAK;
        }
    }

    // ------------------------------------------------------------------ планирование кустов (подлесок)

    private Plant planShrub(int cx, int cz) {
        long h = WarzoneNoise.hash(shrubSalt, cx, cz);
        double r = WarzoneNoise.unit(h);
        if (r > 0.55) return null;
        int px = cx * SHRUB_CELL + (int) (WarzoneNoise.unit(WarzoneNoise.mix(h + 1)) * SHRUB_CELL);
        int pz = cz * SHRUB_CELL + (int) (WarzoneNoise.unit(WarzoneNoise.mix(h + 2)) * SHRUB_CELL);
        if (!t.inside(px, pz) || t.edgeDistance(px, pz) < 10) return null;
        double c = cover(px, pz);
        if (r > 0.06 + 0.45 * c && !t.oasisNear(px, pz)) return null;

        WarzoneTerrain.Sample s = t.sample(px, pz);
        double p = pShrub(s, c);
        if (r >= p) return null;
        if (!validSpot(s, px, pz, 1.0, false)) return null;
        if (s.region == WarzoneTerrain.Region.MARSH && s.height < WarzoneTerrain.SEA_LEVEL + 1) return null;

        double u = WarzoneNoise.unit(WarzoneNoise.mix(h + 3));
        Kind kind;
        Wood wood;
        switch (s.region) {
            case PLAINS:
                kind = (c > 0.35 && u < 0.10) ? Kind.FALLEN_LOG : Kind.BUSH;
                wood = u < 0.5 ? Wood.OAK : Wood.BIRCH;
                break;
            case HIGHLANDS:
                kind = (c > 0.35 && u < 0.10) ? Kind.FALLEN_LOG : Kind.BUSH;
                wood = Wood.SPRUCE;
                break;
            case MARSH:
                kind = u < 0.12 ? Kind.FALLEN_LOG : Kind.SWAMP_BUSH;
                wood = Wood.DARK_OAK;
                break;
            default:
                kind = (s.desert > 0.5 && s.oasis > 0.3) ? Kind.BUSH : Kind.THORN_SCRUB;
                wood = (s.desert > 0.5 && s.oasis > 0.3) ? Wood.JUNGLE : (u < 0.5 ? Wood.ACACIA : Wood.DEAD_PALE);
                break;
        }
        Plant pl = new Plant(px, s.height + 1, pz, kind, wood, WarzoneNoise.mix(h + 4), cx, cz);
        pl.fix = anchor(pl);
        return pl.fix == null ? null : pl;
    }

    private double pShrub(WarzoneTerrain.Sample s, double c) {
        switch (s.region) {
            case PLAINS:
                return 0.02 + 0.45 * Math.pow(c, 1.5);
            case HIGHLANDS:
                return (0.02 + 0.32 * c) * (1.0 - smoothstep(104.0, 134.0, s.height));
            case MARSH:
                return 0.06 + 0.30 * c;
            default:
                if (s.desert > 0.5) return s.oasis > 0.3 ? 0.25 * s.oasis : 0.025;
                return 0.02;
        }
    }

    // ------------------------------------------------------------------ допустимость места

    private boolean validSpot(WarzoneTerrain.Sample s, int x, int z, double maxSlope, boolean tree) {
        int h = s.height;
        if (s.scar > 0.2 || s.channel > 0.08 || s.road > 0.1 || s.path > 0.1 || s.basePad > 0.3) return false;
        if (s.pillar > 0.05) return false;
        boolean marsh = s.region == WarzoneTerrain.Region.MARSH;
        if (marsh) {
            if (h < WarzoneTerrain.SEA_LEVEL - 2) return false;
        } else if (h < WarzoneTerrain.SEA_LEVEL + 2) {
            return false;
        }
        if (s.region == WarzoneTerrain.Region.HIGHLANDS && (h > 136 || s.mountain > 0.97)) return false;
        double slope = Math.max(Math.abs(t.height(x + 2, z) - t.height(x - 2, z)), Math.abs(t.height(x, z + 2) - t.height(x, z - 2))) * 0.5;
        if (slope > maxSlope) return false;
        if (tree) {
            // деревья не растут на обрывах и у кромки пропасти: перепад вокруг корня в радиусе 3 не больше 6 блоков
            int[][] ring = {{3, 0}, {-3, 0}, {0, 3}, {0, -3}, {2, 2}, {-2, 2}, {2, -2}, {-2, -2}};
            for (int[] o : ring) {
                if (Math.abs(t.height(x + o[0], z + o[1]) - h) > 6) return false;
            }
        }
        if (t.isReserved(x, z)) return false;
        if (tree) {
            int d = 4;
            if (t.isReserved(x + d, z) || t.isReserved(x - d, z) || t.isReserved(x, z + d) || t.isReserved(x, z - d)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ посадка на реальный рельеф

    /** Поправки к форме: какие воксели убрать и какие бревна добавить, чтобы растение стояло на земле. */
    static final class Fix {
        final long[] removed;
        final long[] addedKeys;
        final int[] addedCodes;

        Fix(long[] removed, long[] addedKeys, int[] addedCodes) {
            this.removed = removed;
            this.addedKeys = addedKeys;
            this.addedCodes = addedCodes;
        }
    }

    private static int kx(long k) { return (int) ((k >> 40) & 0xFFFFF) - 512; }
    private static int ky(long k) { return (int) ((k >> 20) & 0xFFFFF) - 512; }
    private static int kz(long k) { return (int) (k & 0xFFFFF) - 512; }

    private static boolean isWoody(int code) {
        int ty = type(code);
        return ty == T_LOG || ty == T_ROOTS;
    }

    /**
     * Подгоняет растение под реальный рельеф (чистая функция от seed и рельефа, считается один раз на растение):
     *  1) бревна внутри скалы убираются (в игре они всё равно не ставились — оставались бы «висящие» куски);
     *  2) основание ствола, оторванное от земли, достраивается вниз (до 3 блоков; у лежачего бревна — отрезается);
     *  3) всё, что не связано с землёй стволами (рёбра и грани), убирается, листья дальше 7 блоков от ствола — тоже,
     *     лианы и мох без опоры — тоже.
     * Возвращает null, если растение посадить нельзя (ствол не удаётся поставить на землю).
     */
    private Fix anchor(Plant p) {
        Shape raw = rawShape(p);
        boolean hasWood = false;
        for (int c : raw.m.values()) {
            if (isWoody(c)) { hasWood = true; break; }
        }
        if (!hasWood) return new Fix(new long[0], new long[0], new int[0]);   // кусты из одной листвы: нечего подгонять

        java.util.HashMap<Long, Integer> hc = new java.util.HashMap<>();
        java.util.function.IntBinaryOperator gh = (dx, dz) -> hc.computeIfAbsent(((long) (dx + 512) << 20) | (dz + 512), q -> t.height(p.x + dx, p.z + dz));

        LinkedHashMap<Long, Integer> w = new LinkedHashMap<>(raw.m);
        java.util.LinkedHashSet<Long> removed = new java.util.LinkedHashSet<>();
        LinkedHashMap<Long, Integer> added = new LinkedHashMap<>();

        // 1) бревна внутри рельефа; листья/лианы внутри рельефа игра и так не ставит — их просто не учитываем
        for (Map.Entry<Long, Integer> e : raw.m.entrySet()) {
            long k = e.getKey();
            int code = e.getValue();
            int ty = type(code);
            boolean inGround = p.y + ky(k) <= gh.applyAsInt(kx(k), kz(k));
            if (ty == T_LOG && inGround) {
                removed.add(k);
                w.remove(k);
            } else if (!isWoody(code) && inGround && ty != T_LEAF) {
                w.remove(k);
            }
        }

        // 2) основание: бревна на уровне корня над пустотой
        java.util.List<Long> base = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : w.entrySet()) {
            if (type(e.getValue()) == T_LOG && ky(e.getKey()) == 0) base.add(e.getKey());
        }
        for (long k : base) {
            int gap = p.y - gh.applyAsInt(kx(k), kz(k)) - 1;           // воздух между землёй и нижней гранью бревна
            if (gap <= 0) continue;
            if (p.kind == Kind.FALLEN_LOG) {
                removed.add(k);
                w.remove(k);
            } else if (gap > 3) {
                return null;
            } else {
                int code = log(wood(w.get(k)), AXIS_Y);
                for (int d = 1; d <= gap; d++) {
                    long nk = Shape.key(kx(k), -d, kz(k));
                    if (!w.containsKey(nk)) {
                        w.put(nk, code);
                        added.put(nk, code);
                    }
                }
            }
        }

        // 3) связность стволов с землёй (грани и рёбра; бревно, стоящее на земле, — опора)
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> reached = new java.util.HashSet<>();
        for (Map.Entry<Long, Integer> e : w.entrySet()) {
            long k = e.getKey();
            if (isWoody(e.getValue()) && p.y + ky(k) <= gh.applyAsInt(kx(k), kz(k)) + 1) {
                reached.add(k);
                queue.add(k);
            }
        }
        while (!queue.isEmpty()) {
            long k = queue.poll();
            int x = kx(k), y = ky(k), z = kz(k);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        int n = (ox != 0 ? 1 : 0) + (oy != 0 ? 1 : 0) + (oz != 0 ? 1 : 0);
                        if (n == 0 || n == 3) continue;
                        long nk = Shape.key(x + ox, y + oy, z + oz);
                        Integer c = w.get(nk);
                        if (c != null && isWoody(c) && reached.add(nk)) queue.add(nk);
                    }
                }
            }
        }
        if (reached.isEmpty()) return null;
        for (java.util.Iterator<Map.Entry<Long, Integer>> it = w.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Integer> e = it.next();
            if (isWoody(e.getValue()) && !reached.contains(e.getKey())) {
                if (raw.m.containsKey(e.getKey())) removed.add(e.getKey());
                it.remove();
            }
        }

        // 4) листья: не дальше 7 блоков от ствола по листве
        java.util.HashMap<Long, Integer> dist = new java.util.HashMap<>();
        for (long k : reached) {
            dist.put(k, 0);
            queue.add(k);
        }
        int[][] n6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            long k = queue.poll();
            int d = dist.get(k);
            if (d >= 7) continue;
            for (int[] o : n6) {
                long nk = Shape.key(kx(k) + o[0], ky(k) + o[1], kz(k) + o[2]);
                Integer c = w.get(nk);
                if (c != null && type(c) == T_LEAF && !dist.containsKey(nk)) {
                    dist.put(nk, d + 1);
                    queue.add(nk);
                }
            }
        }
        for (java.util.Iterator<Map.Entry<Long, Integer>> it = w.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Integer> e = it.next();
            if (type(e.getValue()) == T_LEAF && !dist.containsKey(e.getKey())) {
                removed.add(e.getKey());
                it.remove();
            }
        }

        // 5) лианы и мох: нужна опора (лист/ствол рядом, лиана выше или лист под мхом) — сверху вниз
        java.util.List<Long> hang = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : w.entrySet()) {
            int ty = type(e.getValue());
            if (ty == T_VINE || ty == T_CARPET) hang.add(e.getKey());
        }
        hang.sort((a, b) -> Integer.compare(ky(b), ky(a)));
        for (long k : hang) {
            int c = w.get(k);
            boolean ok = false;
            if (type(c) == T_CARPET) {
                Integer below = w.get(Shape.key(kx(k), ky(k) - 1, kz(k)));
                ok = below != null && type(below) == T_LEAF;
            } else {
                Integer up = w.get(Shape.key(kx(k), ky(k) + 1, kz(k)));
                ok = up != null && (type(up) == T_VINE || type(up) == T_LEAF || isWoody(up));
                for (int[] o : n6) {
                    if (ok) break;
                    Integer nb = w.get(Shape.key(kx(k) + o[0], ky(k) + o[1], kz(k) + o[2]));
                    ok = nb != null && (type(nb) == T_LEAF || isWoody(nb));
                }
            }
            if (!ok) {
                if (raw.m.containsKey(k)) removed.add(k);
                w.remove(k);
            }
        }

        long[] rem = new long[removed.size()];
        int i = 0;
        for (long k : removed) rem[i++] = k;
        long[] ak = new long[added.size()];
        int[] ac = new int[added.size()];
        i = 0;
        for (Map.Entry<Long, Integer> e : added.entrySet()) {
            ak[i] = e.getKey();
            ac[i++] = e.getValue();
        }
        return new Fix(rem, ak, ac);
    }

    // ------------------------------------------------------------------ формы

    /** Набор вокселей растения в координатах от корня. */
    static final class Shape {
        interface Visitor {
            void at(int dx, int dy, int dz, int code);
        }

        private final LinkedHashMap<Long, Integer> m = new LinkedHashMap<>();

        static long key(int x, int y, int z) {
            return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (long) (z + 512);
        }

        int size() { return m.size(); }

        boolean has(int x, int y, int z) { return m.containsKey(key(x, y, z)); }

        int get(int x, int y, int z) {
            Integer v = m.get(key(x, y, z));
            return v == null ? 0 : v;
        }

        boolean isLog(int x, int y, int z) {
            int c = get(x, y, z);
            return c != 0 && (type(c) == T_LOG || type(c) == T_ROOTS);
        }

        boolean isLeaf(int x, int y, int z) {
            int c = get(x, y, z);
            return c != 0 && type(c) == T_LEAF;
        }

        void log(int x, int y, int z, Wood w, int axis) { m.put(key(x, y, z), WarzoneFlora.log(w, axis)); }

        void root(int x, int y, int z) { m.put(key(x, y, z), WarzoneFlora.roots()); }

        void leaf(int x, int y, int z, Wood w) { m.putIfAbsent(key(x, y, z), WarzoneFlora.leaf(w)); }

        void vine(int x, int y, int z, int faces) {
            Integer ex = m.get(key(x, y, z));
            if (ex == null) m.put(key(x, y, z), WarzoneFlora.vine(faces));
            else if (type(ex) == T_VINE) m.put(key(x, y, z), WarzoneFlora.vine(faces(ex) | faces));
        }

        /** Мох на верхушке листвы в колонке (x, z): ищем самый верхний лист не выше maxY. */
        void carpetOnTop(int x, int z, int maxY) {
            for (int y = maxY; y >= -2; y--) {
                if (isLeaf(x, y, z)) { carpet(x, y + 1, z); return; }
            }
        }

        void carpet(int x, int y, int z) { m.putIfAbsent(key(x, y, z), WarzoneFlora.carpet()); }

        void forEach(Visitor v) {
            for (Map.Entry<Long, Integer> e : m.entrySet()) {
                long k = e.getKey();
                v.at((int) ((k >> 40) & 0xFFFFF) - 512, (int) ((k >> 20) & 0xFFFFF) - 512, (int) (k & 0xFFFFF) - 512, e.getValue());
            }
        }

        /** Эллипсоид листвы; hole — доля «дыр» во внешней оболочке (рваная крона). */
        void blob(int cx, int cy, int cz, double rx, double ry, double rz, Wood w, Random rnd, double hole) {
            int ax = (int) Math.ceil(rx), ay = (int) Math.ceil(ry), az = (int) Math.ceil(rz);
            for (int dx = -ax; dx <= ax; dx++) {
                for (int dy = -ay; dy <= ay; dy++) {
                    for (int dz = -az; dz <= az; dz++) {
                        double d = dx * dx / (rx * rx) + dy * dy / (ry * ry) + dz * dz / (rz * rz);
                        if (d > 1.0) continue;
                        if (d > 0.55 && rnd.nextDouble() < hole) continue;
                        leaf(cx + dx, cy + dy, cz + dz, w);
                    }
                }
            }
        }

        /** Ветка/ствол из брёвен: сплошная (6-связная) линия, ось — по преобладающему направлению. */
        void line(int x0, int y0, int z0, int x1, int y1, int z1, Wood w) {
            int ddx = x1 - x0, ddy = y1 - y0, ddz = z1 - z0;
            int ax = Math.abs(ddx), ay = Math.abs(ddy), az = Math.abs(ddz);
            int axis = ay >= ax && ay >= az ? AXIS_Y : (ax >= az ? AXIS_X : AXIS_Z);
            int n = Math.max(ax, Math.max(ay, az));
            int px = x0, py = y0, pz = z0;
            log(px, py, pz, w, axis);
            for (int i = 1; i <= n; i++) {
                int tx = x0 + Math.round((float) ddx * i / n);
                int ty = y0 + Math.round((float) ddy * i / n);
                int tz = z0 + Math.round((float) ddz * i / n);
                while (px != tx) { px += Integer.signum(tx - px); log(px, py, pz, w, axis); }
                while (pz != tz) { pz += Integer.signum(tz - pz); log(px, py, pz, w, axis); }
                while (py != ty) { py += Integer.signum(ty - py); log(px, py, pz, w, axis); }
            }
        }

        /** Корни (mangrove_roots) по прямой от точки к точке, 6-связно. */
        void rootLine(int x0, int y0, int z0, int x1, int y1, int z1) {
            int ddx = x1 - x0, ddy = y1 - y0, ddz = z1 - z0;
            int n = Math.max(Math.abs(ddx), Math.max(Math.abs(ddy), Math.abs(ddz)));
            int px = x0, py = y0, pz = z0;
            for (int i = 1; i <= n; i++) {
                int tx = x0 + Math.round((float) ddx * i / n);
                int ty = y0 + Math.round((float) ddy * i / n);
                int tz = z0 + Math.round((float) ddz * i / n);
                while (px != tx) { px += Integer.signum(tx - px); if (!has(px, py, pz)) root(px, py, pz); }
                while (pz != tz) { pz += Integer.signum(tz - pz); if (!has(px, py, pz)) root(px, py, pz); }
                while (py != ty) { py += Integer.signum(ty - py); if (!has(px, py, pz)) root(px, py, pz); }
            }
        }

        /** Лианы, свисающие с боков кроны: с каждого подходящего листа сбоку — цепочка вниз, прижатая гранью к листу. */
        void hang(Random rnd, double prob, int minLen, int maxLen, int maxY) {
            List<Long> leaves = new ArrayList<>();
            for (Map.Entry<Long, Integer> e : m.entrySet()) {
                if (type(e.getValue()) == T_LEAF) leaves.add(e.getKey());
            }
            int[][] dirs = {{0, -1, F_S}, {1, 0, F_W}, {0, 1, F_N}, {-1, 0, F_E}};
            for (long k : leaves) {
                int x = (int) ((k >> 40) & 0xFFFFF) - 512, y = (int) ((k >> 20) & 0xFFFFF) - 512, z = (int) (k & 0xFFFFF) - 512;
                if (y > maxY) continue;
                for (int[] d : dirs) {
                    int vx = x + d[0], vz = z + d[1];
                    if (has(vx, y, vz) || rnd.nextDouble() >= prob) continue;
                    // грань смотрит ОБРАТНО на лист: лист в направлении (-d), поэтому грань — противоположная d
                    int len = minLen + rnd.nextInt(Math.max(1, maxLen - minLen + 1));
                    for (int i = 0; i < len; i++) {
                        if (has(vx, y - i, vz) && type(get(vx, y - i, vz)) != T_VINE) break;
                        vine(vx, y - i, vz, d[2]);
                    }
                }
            }
        }

        /** Ствол с «изломами»: на заданных высотах ствол сдвигается на 1 в сторону (с соединяющим бревном). Возвращает позиции по высотам. */
        int[][] crookedTrunk(int height, int[] bendYs, int[][] bendDirs, Wood w, int baseX, int baseZ) {
            int[][] pos = new int[height + 1][2];
            int x = baseX, z = baseZ;
            for (int y = 0; y < height; y++) {
                log(x, y, z, w, AXIS_Y);
                pos[y][0] = x;
                pos[y][1] = z;
                for (int i = 0; i < bendYs.length; i++) {
                    if (bendYs[i] == y) {
                        int dx = bendDirs[i][0], dz = bendDirs[i][1];
                        x += dx;
                        z += dz;
                        log(x, y, z, w, dx != 0 ? AXIS_X : AXIS_Z);
                    }
                }
            }
            pos[height][0] = x;
            pos[height][1] = z;
            return pos;
        }
    }

    private static final int[][] DIR4 = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private static int[] dir4(Random r) { return DIR4[r.nextInt(4)]; }

    /** Форма растения с учётом подгонки под рельеф (детерминированно от seed и рельефа — одинакова в любом чанке). */
    Shape shape(Plant p) {
        Shape s = rawShape(p);
        Fix f = p.fix;
        if (f != null) {
            for (long k : f.removed) s.m.remove(k);
            for (int i = 0; i < f.addedKeys.length; i++) s.m.put(f.addedKeys[i], f.addedCodes[i]);
        }
        return s;
    }

    /** Строит «сырую» форму растения (детерминированно от p.seed), без учёта рельефа. */
    Shape rawShape(Plant p) {
        Random r = new Random(p.seed);
        Shape s = new Shape();
        switch (p.kind) {
            case OAK_CROOKED -> oakCrooked(s, r, p.wood);
            case ELM_VASE -> elmVase(s, r, p.wood);
            case BIRCH_PAIR -> birchPair(s, r, p.wood);
            case WINDSWEPT -> windswept(s, r, p.wood);
            case SPRUCE_TIER -> spruceTier(s, r, p.wood);
            case PINE_GNARLED -> pineGnarled(s, r, p.wood);
            case SNAG -> snag(s, r, p.wood);
            case KRUMMHOLZ -> krummholz(s, r, p.wood);
            case WILLOW -> willow(s, r, p.wood);
            case MANGROVE_STILT -> mangroveStilt(s, r, p.wood);
            case DROWNED_SNAG -> drownedSnag(s, r, p.wood);
            case HAG_OAK -> hagOak(s, r, p.wood);
            case DEAD_TREE -> deadTree(s, r, p.wood);
            case ACACIA_PARASOL -> acaciaParasol(s, r, p.wood);
            case PALM -> palm(s, r, p.wood);
            case BUSH -> bush(s, r, p.wood, false);
            case SWAMP_BUSH -> bush(s, r, p.wood, true);
            case THORN_SCRUB -> thornScrub(s, r, p.wood);
            case FALLEN_LOG -> fallenLog(s, r, p.wood);
        }
        return s;
    }

    private static int ri(Random r, int lo, int hi) { return lo + r.nextInt(hi - lo + 1); }

    // ---- равнины

    /** Кривой дуб: ствол с двумя изломами, 2–3 ветви с шапками листвы и общая крона. */
    private void oakCrooked(Shape s, Random r, Wood w) {
        int h = ri(r, 7, 10);
        int b1 = ri(r, 2, 3), b2 = h - 3;
        int[] d1 = dir4(r), d2 = dir4(r);
        int[][] pos = s.crookedTrunk(h, new int[]{b1, b2}, new int[][]{d1, d2}, w, 0, 0);
        s.log(pos[h][0], h, pos[h][1], w, AXIS_Y);
        int nb = ri(r, 2, 3);
        for (int i = 0; i < nb; i++) {
            int yb = Math.max(3, h / 2 + ri(r, -1, 2));
            double a = r.nextDouble() * Math.PI * 2;
            int len = ri(r, 3, 4);
            int ex = pos[yb][0] + (int) Math.round(Math.cos(a) * len);
            int ez = pos[yb][1] + (int) Math.round(Math.sin(a) * len);
            int ey = yb + ri(r, 1, 2);
            s.line(pos[yb][0], yb, pos[yb][1], ex, ey, ez, w);
            s.blob(ex, ey + 1, ez, 2.4, 1.8, 2.4, w, r, 0.12);
        }
        s.blob(pos[h][0], h, pos[h][1], 3.0, 2.3, 3.0, w, r, 0.12);
    }

    /** Вязоподобная ваза: прямой высокий ствол, четыре отходящие вверх-наружу ветви, шапки на концах. */
    private void elmVase(Shape s, Random r, Wood w) {
        int h = ri(r, 10, 13);
        for (int y = 0; y < h; y++) s.log(0, y, 0, w, AXIS_Y);
        // оттопыренный комель
        for (int[] d : DIR4) if (r.nextInt(3) == 0) s.log(d[0], 0, d[1], w, d[0] != 0 ? AXIS_X : AXIS_Z);
        int yb = h - 4;
        double off = r.nextDouble() * 0.8;
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2 + off;
            int ex = (int) Math.round(Math.cos(a) * 4.5);
            int ez = (int) Math.round(Math.sin(a) * 4.5);
            int ey = h - 1 + ri(r, -1, 1);
            s.line(0, yb, 0, ex, ey, ez, w);
            s.blob(ex, ey + 1, ez, 2.6, 1.7, 2.6, w, r, 0.14);
        }
        s.log(0, h, 0, w, AXIS_Y);
        s.blob(0, h + 1, 0, 2.6, 1.9, 2.6, w, r, 0.12);
    }

    /** Берёзовая семья: 2–3 тонких ствола из соседних корней, слегка расходятся, узкие кроны. */
    private void birchPair(Shape s, Random r, Wood w) {
        int n = ri(r, 2, 3);
        int[][] bases = {{0, 0}, {2, 1}, {-2, -1}};
        for (int i = 0; i < n; i++) {
            int h = ri(r, 8, 12);
            int[] lean = dir4(r);
            int[][] pos = s.crookedTrunk(h, new int[]{h / 3, 2 * h / 3}, new int[][]{lean, r.nextBoolean() ? lean : dir4(r)}, w, bases[i][0], bases[i][1]);
            s.log(pos[h][0], h, pos[h][1], w, AXIS_Y);
            s.blob(pos[h][0], h, pos[h][1], 2.1, 2.9, 2.1, w, r, 0.10);
        }
    }

    /** Ветрорез: ствол клонит по ветру, ветви и крона вытянуты в одну сторону. */
    private void windswept(Shape s, Random r, Wood w) {
        int h = ri(r, 7, 9);
        int[] wd = dir4(r);
        boolean alongX = wd[0] != 0;
        int[] bends = {2, 4, 6};
        int[][] dirs = {wd, wd, r.nextBoolean() ? wd : dir4(r)};
        int[][] pos = s.crookedTrunk(h, bends, dirs, w, 0, 0);
        s.log(pos[h][0], h, pos[h][1], w, AXIS_Y);
        for (int i = 0; i < 3; i++) {
            int yb = 3 + i * 2;
            if (yb >= h) break;
            int len = ri(r, 3, 4);
            int ex = pos[yb][0] + wd[0] * len, ez = pos[yb][1] + wd[1] * len;
            s.line(pos[yb][0], yb, pos[yb][1], ex, yb + 1, ez, w);
            s.blob(ex, yb + 2, ez, alongX ? 3.2 : 2.0, 1.4, alongX ? 2.0 : 3.2, w, r, 0.15);
        }
        int cx = pos[h][0] + wd[0] * 2, cz = pos[h][1] + wd[1] * 2;
        s.blob(cx, h + 1, cz, alongX ? 4.2 : 2.6, 1.7, alongX ? 2.6 : 4.2, w, r, 0.15);
    }

    // ---- нагорье

    /** Ярусная ель: неровные ярусы-ромбы, свисающие «юбки», сдвиги ярусов — не похожа на ванильный конус. */
    private void spruceTier(Shape s, Random r, Wood w) {
        int h = ri(r, 11, 17);
        for (int y = 0; y <= h; y++) s.log(0, y, 0, w, AXIS_Y);
        double rMax = 2.6 + r.nextDouble() * 1.8;
        int sx = 0, sz = 0;
        for (int y = h + 1; y >= 4; y--) {
            double d = (h + 1 - y) / (double) (h - 3);
            int rad = (int) Math.round(1 + d * rMax);
            if (((h + 1 - y) & 1) == 1) rad = Math.max(1, rad - 1);
            rad += ri(r, -1, 0);
            if (y == h + 1) rad = 0;
            if (r.nextInt(4) == 0) { sx = ri(r, -1, 1); sz = ri(r, -1, 1); }
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    int dist = Math.abs(dx) + Math.abs(dz);
                    if (dist > rad + (rad >= 3 ? 1 : 0)) continue;
                    if (dist == rad + (rad >= 3 ? 1 : 0) && rad >= 2 && r.nextInt(5) == 0) continue;
                    s.leaf(sx + dx, y, sz + dz, w);
                    // свисающая юбка по краю яруса
                    if (dist >= rad && rad >= 2 && r.nextInt(2) == 0) s.leaf(sx + dx, y - 1, sz + dz, w);
                }
            }
        }
    }

    /** Корявая сосна: извилистый ствол, длинные горизонтальные ветви с плоскими «тарелками» хвои. */
    private void pineGnarled(Shape s, Random r, Wood w) {
        int h = ri(r, 8, 12);
        int[][] pos = s.crookedTrunk(h, new int[]{3, 6}, new int[][]{dir4(r), dir4(r)}, w, 0, 0);
        s.log(pos[h][0], h, pos[h][1], w, AXIS_Y);
        int nb = ri(r, 3, 4);
        for (int i = 0; i < nb; i++) {
            int yb = Math.max(4, h - 1 - i * 2 - r.nextInt(2));
            double a = r.nextDouble() * Math.PI * 2;
            int len = ri(r, 3, 4);
            int ex = pos[yb][0] + (int) Math.round(Math.cos(a) * len);
            int ez = pos[yb][1] + (int) Math.round(Math.sin(a) * len);
            s.line(pos[yb][0], yb, pos[yb][1], ex, yb + 1, ez, w);
            s.blob(ex, yb + 1, ez, 2.3, 1.0, 2.3, w, r, 0.20);
        }
        s.blob(pos[h][0], h, pos[h][1], 3.0, 1.2, 3.0, w, r, 0.18);
    }

    /** Сухостой: сломанный голый ствол со штырями-обломками веток. */
    private void snag(Shape s, Random r, Wood w) {
        int h = ri(r, 5, 12);
        for (int y = 0; y < h; y++) s.log(0, y, 0, w, AXIS_Y);
        if (r.nextBoolean()) s.log(r.nextBoolean() ? 1 : -1, h - 1, 0, w, AXIS_X);
        int stubs = ri(r, 3, 5);
        for (int i = 0; i < stubs; i++) {
            int y = ri(r, 2, Math.max(2, h - 1));
            int[] d = dir4(r);
            s.log(d[0], y, d[1], w, d[0] != 0 ? AXIS_X : AXIS_Z);
            if (r.nextInt(3) == 0) s.log(d[0] * 2, y, d[1] * 2, w, d[0] != 0 ? AXIS_X : AXIS_Z);
        }
    }

    /** Кривоствольный стланик: низкий, пригнутый ветром, с широкой стелющейся кроной. */
    private void krummholz(Shape s, Random r, Wood w) {
        int h = ri(r, 3, 5);
        int[] wd = dir4(r);
        int[][] pos = s.crookedTrunk(h, new int[]{1, 3}, new int[][]{wd, wd}, w, 0, 0);
        s.log(pos[h][0], h, pos[h][1], w, AXIS_Y);
        int cx = pos[h][0] + wd[0], cz = pos[h][1] + wd[1];
        s.blob(cx, h, cz, 3.4, 1.5, 3.4, w, r, 0.15);
        s.blob(cx + wd[0] * 2, h - 1, cz + wd[1] * 2, 2.2, 1.2, 2.2, w, r, 0.15);
    }

    // ---- болото

    /** Ива: изогнутый ствол, купол кроны, длинные свисающие лианы и пряди листвы. */
    private void willow(Shape s, Random r, Wood w) {
        int h = ri(r, 7, 10);
        int[] lean = dir4(r);
        int[][] pos = s.crookedTrunk(h, new int[]{2, 4}, new int[][]{lean, r.nextBoolean() ? lean : dir4(r)}, w, 0, 0);
        s.log(pos[h][0], h, pos[h][1], w, AXIS_Y);
        int cx = pos[h][0], cz = pos[h][1];
        s.blob(cx, h, cz, 4.0, 2.6, 4.0, w, r, 0.10);
        // нижняя «бахрома» из прядей листвы
        List<int[]> rim = new ArrayList<>();
        s.forEach((dx, dy, dz, code) -> {
            if (type(code) == T_LEAF && dy <= h - 1 && !s.has(dx, dy - 1, dz)) rim.add(new int[]{dx, dy, dz});
        });
        for (int[] c : rim) {
            if (r.nextInt(4) != 0) continue;
            int len = ri(r, 1, 2);
            for (int i = 1; i <= len; i++) s.leaf(c[0], c[1] - i, c[2], w);
        }
        s.hang(r, 0.38, 3, 8, h);
    }

    /** Болотное дерево на ходулях: наклонный ствол, дугой уходящие в воду/ил корни, плоская крона. */
    private void mangroveStilt(Shape s, Random r, Wood w) {
        int h = ri(r, 5, 8);
        int[] lean = dir4(r);
        int[][] pos = s.crookedTrunk(h, new int[]{3}, new int[][]{lean}, w, 0, 0);
        s.log(pos[h][0], h, pos[h][1], w, AXIS_Y);
        int nr = ri(r, 4, 5);
        double off = r.nextDouble() * Math.PI;
        for (int k = 0; k < nr; k++) {
            double a = k * Math.PI * 2 / nr + off;
            int len = ri(r, 2, 3);
            int ex = (int) Math.round(Math.cos(a) * len);
            int ez = (int) Math.round(Math.sin(a) * len);
            // корень выходит из ствола на высоте 2–3 и дугой уходит вниз, в грунт/ил
            s.rootLine(0, ri(r, 2, 3), 0, ex, -2, ez);
        }
        int cx = pos[h][0], cz = pos[h][1];
        s.blob(cx, h, cz, 4.2, 1.6, 4.2, w, r, 0.16);
        s.blob(cx + ri(r, -1, 1), h + 1, cz + ri(r, -1, 1), 2.6, 1.3, 2.6, w, r, 0.12);
        s.hang(r, 0.18, 2, 4, h);
    }

    /** Утонувший сухой ствол: тёмный, с обломками веток и лианами по бокам. */
    private void drownedSnag(Shape s, Random r, Wood w) {
        int h = ri(r, 5, 9);
        int[] lean = dir4(r);
        int[][] pos = s.crookedTrunk(h, new int[]{h / 2}, new int[][]{lean}, w, 0, 0);
        for (int i = 0; i < ri(r, 2, 3); i++) {
            int y = ri(r, 2, Math.max(2, h - 1));
            int[] d = dir4(r);
            int x = pos[y][0] + d[0], z = pos[y][1] + d[1];
            s.log(x, y, z, w, d[0] != 0 ? AXIS_X : AXIS_Z);
            if (r.nextBoolean()) s.log(x, y + 1, z, w, AXIS_Y);
        }
        int[][] dirs = {{0, -1, F_S}, {1, 0, F_W}, {0, 1, F_N}, {-1, 0, F_E}};
        for (int y = 1; y < h; y++) {
            for (int[] d : dirs) {
                if (r.nextInt(5) != 0) continue;
                int vx = pos[y][0] + d[0], vz = pos[y][1] + d[1];
                if (!s.has(vx, y, vz)) s.vine(vx, y, vz, d[2]);
            }
        }
    }

    /** Старая коряга-дуб: низкий кривой ствол, две широкие ветви, плоская тёмная крона с «мхом» и лианами. */
    private void hagOak(Shape s, Random r, Wood w) {
        int h = ri(r, 5, 8);
        int[][] pos = s.crookedTrunk(h, new int[]{2, 4}, new int[][]{dir4(r), dir4(r)}, w, 0, 0);
        int tx = pos[h][0], tz = pos[h][1];
        s.log(tx, h, tz, w, AXIS_Y);
        double a = r.nextDouble() * Math.PI;
        for (int k = 0; k < 2; k++) {
            double aa = a + k * Math.PI;
            int ex = tx + (int) Math.round(Math.cos(aa) * 4);
            int ez = tz + (int) Math.round(Math.sin(aa) * 4);
            s.line(tx, h - 2, tz, ex, h - 1, ez, w);
            s.blob(ex, h, ez, 3.4, 1.6, 3.4, w, r, 0.15);
        }
        s.blob(tx, h + 1, tz, 4.4, 1.8, 4.4, w, r, 0.13);
        s.hang(r, 0.45, 3, 9, h + 1);
        // мох на макушке кроны
        List<int[]> tops = new ArrayList<>();
        s.forEach((dx, dy, dz, code) -> {
            if (type(code) == T_LEAF && !s.has(dx, dy + 1, dz)) tops.add(new int[]{dx, dy, dz});
        });
        for (int[] c : tops) if (r.nextInt(4) == 0) s.carpet(c[0], c[1] + 1, c[2]);
    }

    private void bush(Shape s, Random r, Wood w, boolean swamp) {
        int stub = ri(r, 1, 2);
        for (int y = 0; y < stub; y++) s.log(0, y, 0, w, AXIS_Y);
        double rx = 1.5 + r.nextDouble() * 0.9;
        s.blob(0, stub, 0, rx, 1.3, rx, w, r, 0.15);
        if (r.nextInt(3) == 0) s.blob(ri(r, -1, 1), stub - 1, ri(r, -1, 1), 1.4, 1.1, 1.4, w, r, 0.15);
        if (swamp) {
            s.hang(r, 0.30, 1, 3, stub + 1);
            if (r.nextBoolean()) s.carpetOnTop(0, 0, stub + 3);
        }
    }

    // ---- пустыня

    /** Мёртвое дерево пустыни: высохший ствол с обломанными развилками, без листвы. */
    private void deadTree(Shape s, Random r, Wood w) {
        int h = ri(r, 4, 8);
        int[][] pos = s.crookedTrunk(h, new int[]{h / 2}, new int[][]{dir4(r)}, w, 0, 0);
        s.log(pos[h][0], h, pos[h][1], w, AXIS_Y);
        int nb = ri(r, 3, 5);
        for (int i = 0; i < nb; i++) {
            int yb = Math.max(2, h / 2 + ri(r, -1, 2));
            if (yb >= h) yb = h - 1;
            double a = r.nextDouble() * Math.PI * 2;
            int len = ri(r, 2, 3);
            int ex = pos[yb][0] + (int) Math.round(Math.cos(a) * len);
            int ez = pos[yb][1] + (int) Math.round(Math.sin(a) * len);
            int ey = yb + ri(r, 1, 2);
            s.line(pos[yb][0], yb, pos[yb][1], ex, ey, ez, w);
            if (r.nextBoolean()) s.log(ex, ey + 1, ez, w, AXIS_Y);
        }
    }

    /** Акация-зонтик: наклонный ствол с развилкой, широкий плоский зонт кроны, сдвинутый в сторону. */
    private void acaciaParasol(Shape s, Random r, Wood w) {
        int h = ri(r, 5, 8);
        int[] d1 = dir4(r);
        int[][] pos = s.crookedTrunk(h, new int[]{2, 4}, new int[][]{d1, d1}, w, 0, 0);
        int tx = pos[h - 1][0], tz = pos[h - 1][1];
        int[] d2 = dir4(r);
        s.log(tx, h - 1, tz, w, AXIS_Y);
        for (int k = 0; k < 2; k++) {
            int sgn = k == 0 ? 1 : -1;
            int len = ri(r, 2, 3);
            int ex = tx + d2[0] * len * sgn, ez = tz + d2[1] * len * sgn;
            s.line(tx, h - 1, tz, ex, h, ez, w);
            s.blob(ex, h + 1, ez, 3.4, 0.95, 3.4, w, r, 0.14);
        }
        s.blob(tx, h, tz, 2.8, 0.95, 2.8, w, r, 0.14);
    }

    /** Пальма оазиса: изогнутый ствол и звезда из 8 поникших вееров. */
    private void palm(Shape s, Random r, Wood w) {
        int h = ri(r, 7, 11);
        int[] lean = dir4(r);
        double lk = 2.0 + r.nextDouble() * 1.2;
        int px = 0, pz = 0;
        for (int y = 0; y < h; y++) {
            int tx = (int) Math.round(lean[0] * lk * y * y / (double) (h * h));
            int tz = (int) Math.round(lean[1] * lk * y * y / (double) (h * h));
            while (px != tx) { px += Integer.signum(tx - px); s.log(px, y, pz, w, AXIS_X); }
            while (pz != tz) { pz += Integer.signum(tz - pz); s.log(px, y, pz, w, AXIS_Z); }
            s.log(px, y, pz, w, AXIS_Y);
        }
        s.log(px, h, pz, w, AXIS_Y);
        s.leaf(px, h + 1, pz, w);
        s.leaf(px, h + 2, pz, w);
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4 + r.nextDouble() * 0.2;
            int len = ri(r, 4, 5);
            for (int i = 1; i <= len; i++) {
                int droop = i <= 2 ? 0 : (i == 3 ? -1 : -2);
                int fx = px + (int) Math.round(Math.cos(a) * i);
                int fz = pz + (int) Math.round(Math.sin(a) * i);
                s.leaf(fx, h + 1 + droop, fz, w);
                if (i == 1 || (i == 3 && (k & 1) == 0)) s.leaf(fx, h + 2 + droop, fz, w);
            }
        }
    }

    /** Колючий кустарник пустыни: пучок коротких стволиков с редкими клочками листвы. */
    private void thornScrub(Shape s, Random r, Wood w) {
        int n = ri(r, 2, 4);
        boolean dead = w == Wood.DEAD_PALE || w == Wood.DEAD_GREY;
        for (int i = 0; i < n; i++) {
            int x = ri(r, -1, 1), z = ri(r, -1, 1);
            int hh = ri(r, 1, 2);
            for (int y = 0; y < hh; y++) s.log(x, y, z, w, AXIS_Y);
            if (!dead && r.nextInt(2) == 0) s.blob(x, hh, z, 1.3, 0.9, 1.3, Wood.ACACIA, r, 0.25);
        }
    }

    /** Поваленный ствол: 3–5 брёвен лежат на земле, иногда с мхом. */
    private void fallenLog(Shape s, Random r, Wood w) {
        int len = ri(r, 3, 5);
        boolean alongX = r.nextBoolean();
        for (int i = 0; i < len; i++) {
            s.log(alongX ? i - len / 2 : 0, 0, alongX ? 0 : i - len / 2, w, alongX ? AXIS_X : AXIS_Z);
        }
        // обломок сука сбоку
        if (r.nextInt(3) != 0) {
            int i = r.nextInt(len);
            int side = r.nextBoolean() ? 1 : -1;
            s.log(alongX ? i - len / 2 : side, 0, alongX ? side : i - len / 2, w, alongX ? AXIS_Z : AXIS_X);
        }
        if (r.nextBoolean()) {
            int i = r.nextInt(len);
            s.carpet(alongX ? i - len / 2 : 0, 1, alongX ? 0 : i - len / 2);
        }
    }
}
