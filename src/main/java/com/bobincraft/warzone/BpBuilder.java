package com.bobincraft.warzone;

/**
 * Локальный строитель чертежа в системе координат объекта (u, dy, v) с поворотом на 0/90/180/270° вокруг (cx, baseY, cz).
 * API повторяет {@code WarzoneStructures.LocalBuilder}, но пишет в {@link Blueprint} (без Minecraft), поэтому
 * результат строится один раз на весь объект, проверяется headless и нарезается по чанкам без швов.
 * Локально: +V — главный вход (юг при rotation=0), −V — тыл, +U — восток, −U — запад.
 */
final class BpBuilder {
    /** Горизонтальные направления + вертикали (имена совпадают с ванильными свойствами). */
    enum Dir {
        NORTH("north", 0, -1), EAST("east", 1, 0), SOUTH("south", 0, 1), WEST("west", -1, 0), UP("up", 0, 0), DOWN("down", 0, 0);

        final String id;
        final int dx, dz;

        Dir(String id, int dx, int dz) {
            this.id = id;
            this.dx = dx;
            this.dz = dz;
        }

        Dir cw() {
            return switch (this) {
                case NORTH -> EAST;
                case EAST -> SOUTH;
                case SOUTH -> WEST;
                case WEST -> NORTH;
                default -> this;
            };
        }

        Dir opposite() {
            return switch (this) {
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case EAST -> WEST;
                case WEST -> EAST;
                case UP -> DOWN;
                case DOWN -> UP;
            };
        }

        boolean vertical() {
            return this == UP || this == DOWN;
        }
    }

    static final String LOOT_SUPPLY = "bobin_craft:chests/warzone_supply";
    static final String LOOT_ARMORY = "bobin_craft:chests/warzone_armory";
    static final String LOOT_COMMAND = "bobin_craft:chests/warzone_command";
    static final String LOOT_CIVILIAN = "bobin_craft:chests/warzone_civilian";
    static final String LOOT_BANDIT = "bobin_craft:chests/warzone_bandit";
    static final String LOOT_MEDICAL = "bobin_craft:chests/warzone_medical";
    static final String LOOT_TECHNICAL = "bobin_craft:chests/warzone_technical";
    static final String LOOT_BUNKER = "bobin_craft:chests/warzone_bunker";

    /** Может быть null (построители баз не обращаются к рельефу — только к seed). */
    final WarzoneTerrain terrain;
    final long seed;
    final Blueprint bp;
    final BpPalette pal;
    final int cx, baseY, cz, rot;
    /** Детерминированный ГСЧ объекта (раскладка, выбор модулей). */
    final java.util.Random rnd;
    /** Тактические точки, добавленные модулями (мировые координаты). */
    final java.util.List<WarzoneBasePlan.TacticalSpot> spots = new java.util.ArrayList<>();
    private static final int OCC = 48;
    private final boolean[][] occ = new boolean[2 * OCC + 1][2 * OCC + 1];

    BpBuilder(WarzoneTerrain terrain, Blueprint bp, BpPalette pal, int cx, int baseY, int cz, int rot) {
        this(terrain, terrain.seed, bp, pal, cx, baseY, cz, rot, terrain.seed);
    }

    BpBuilder(WarzoneTerrain terrain, long seed, Blueprint bp, BpPalette pal, int cx, int baseY, int cz, int rot, long rndSeed) {
        this.terrain = terrain;
        this.seed = seed;
        this.rnd = new java.util.Random(WarzoneNoise.mix(rndSeed));
        this.bp = bp;
        this.pal = pal;
        this.cx = cx;
        this.baseY = baseY;
        this.cz = cz;
        this.rot = rot & 3;
    }

    /** Подстроитель модуля: центр в (u, v) локальных координат этого строителя, дополнительный поворот face·90°. */
    BpBuilder child(int u, int v, int face) {
        return new BpBuilder(terrain, bp, pal, wx(u, v), baseY, wz(u, v), (rot + face) & 3);
    }

    int wx(int u, int v) {
        return WarzoneBasePlan.rotX(cx, u, v, rot);
    }

    int wz(int u, int v) {
        return WarzoneBasePlan.rotZ(cz, u, v, rot);
    }

    Dir dir(Dir local) {
        if (local.vertical()) return local;
        Dir d = local;
        for (int i = 0; i < rot; i++) d = d.cw();
        return d;
    }

    // ------------------------------------------------------------------ базовые операции

    void set(int u, int dy, int v, BpSpec spec) {
        int x = wx(u, v), y = baseY + dy, z = wz(u, v);
        if (spec.isAir()) bp.air(x, y, z);
        else bp.set(x, y, z, spec);
    }

    void air(int u, int dy, int v) {
        bp.air(wx(u, v), baseY + dy, wz(u, v));
    }

    void setMasonry(int u, int dy, int v) {
        int x = wx(u, v), y = baseY + dy, z = wz(u, v);
        bp.set(x, y, z, pal.masonry(seed, x, y, z));
    }

    void fill(int u0, int dy0, int v0, int u1, int dy1, int v1, BpSpec spec) {
        for (int u = Math.min(u0, u1); u <= Math.max(u0, u1); u++)
            for (int v = Math.min(v0, v1); v <= Math.max(v0, v1); v++)
                for (int dy = Math.min(dy0, dy1); dy <= Math.max(dy0, dy1); dy++)
                    set(u, dy, v, spec);
    }

    void fillAir(int u0, int dy0, int v0, int u1, int dy1, int v1) {
        fill(u0, dy0, v0, u1, dy1, v1, BpSpec.AIR);
    }

    // ------------------------------------------------------------------ состояния блоков

    BpSpec logVertical() {
        return BpSpec.of(pal.log).with("axis", "y");
    }

    BpSpec logHorizontal(boolean alongLocalU) {
        Dir d = dir(alongLocalU ? Dir.EAST : Dir.SOUTH);
        return BpSpec.of(pal.log).with("axis", d == Dir.EAST || d == Dir.WEST ? "x" : "z");
    }

    BpSpec stair(String id, Dir localFacing, boolean topHalf) {
        return BpSpec.of(id).with("facing", dir(localFacing).id).with("half", topHalf ? "top" : "bottom")
                .with("shape", "straight").with("waterlogged", false);
    }

    BpSpec slab(String id, String type) {
        return BpSpec.of(id).with("type", type).with("waterlogged", false);
    }

    private static BpSpec sides(BpSpec st, Dir a, Dir b) {
        st = st.with(a.id, true);
        return st.with(b.id, true);
    }

    BpSpec paneAlong(String id, boolean alongLocalU) {
        Dir a = dir(alongLocalU ? Dir.EAST : Dir.SOUTH);
        return sides(BpSpec.of(id).with("waterlogged", false), a, a.opposite());
    }

    BpSpec fenceAlong(String id, boolean alongLocalU) {
        return paneAlong(id, alongLocalU);
    }

    BpSpec fenceCorner(String id, Dir localA, Dir localB) {
        return sides(BpSpec.of(id).with("waterlogged", false), dir(localA), dir(localB));
    }

    /** Стена: соединения (none/low/tall, up) окончательно проставит {@link Blueprint#connect}. */
    BpSpec wall(String id) {
        return BpSpec.of(id).with("up", true).with("waterlogged", false);
    }

    // ------------------------------------------------------------------ двери, калитки, лестницы, свет

    void door(int u, int dy, int v, Dir localFacing, String hinge) {
        Dir f = dir(localFacing);
        BpSpec base = BpSpec.of(pal.woodDoor).with("facing", f.id).with("hinge", hinge).with("open", false).with("powered", false);
        set(u, dy, v, base.with("half", "lower"));
        set(u, dy + 1, v, base.with("half", "upper"));
    }

    void gate(int u, int dy, int v, Dir localFacing, boolean open) {
        set(u, dy, v, BpSpec.of(pal.woodGate).with("facing", dir(localFacing).id).with("open", open)
                .with("in_wall", false).with("powered", false));
    }

    void trapdoor(int u, int dy, int v, Dir localFacing, boolean topHalf, boolean open) {
        set(u, dy, v, BpSpec.of(pal.woodTrapdoor).with("facing", dir(localFacing).id)
                .with("half", topHalf ? "top" : "bottom").with("open", open)
                .with("powered", false).with("waterlogged", false));
    }

    void ladder(int u, int dy0, int dy1, int v, Dir localFacing) {
        BpSpec st = BpSpec.of("ladder").with("facing", dir(localFacing).id).with("waterlogged", false);
        for (int dy = dy0; dy <= dy1; dy++) set(u, dy, v, st);
    }

    void lantern(int u, int dy, int v, boolean hanging) {
        set(u, dy, v, BpSpec.of("lantern").with("hanging", hanging).with("waterlogged", false));
    }

    // ------------------------------------------------------------------ блок-сущности

    void chest(int u, int dy, int v, Dir localFacing, String loot, int salt) {
        int x = wx(u, v), y = baseY + dy, z = wz(u, v);
        long seed = WarzoneNoise.hash(terrain.seed + 777L + salt, x, z);
        Dir f = dir(localFacing);
        BpSpec spec = BpSpec.of("chest").with("facing", f.id).with("type", "single").with("waterlogged", false);
        bp.addBlockEntity(new Blueprint.BlockEntity(x, y, z, Blueprint.Kind.CHEST, f.id, loot, seed, false, false), spec);
    }

    void barrel(int u, int dy, int v, Dir localFacing, String loot, int salt) {
        int x = wx(u, v), y = baseY + dy, z = wz(u, v);
        long seed = WarzoneNoise.hash(terrain.seed + 888L + salt, x, z);
        Dir f = dir(localFacing);
        BpSpec spec = BpSpec.of("barrel").with("facing", f.id).with("open", false);
        bp.addBlockEntity(new Blueprint.BlockEntity(x, y, z, Blueprint.Kind.BARREL, f.id, loot, seed, false, false), spec);
    }

    void campfire(int u, int dy, int v, boolean signal) {
        int x = wx(u, v), y = baseY + dy, z = wz(u, v);
        BpSpec spec = BpSpec.of("campfire").with("lit", true).with("signal_fire", signal)
                .with("waterlogged", false).with("facing", "north");
        bp.addBlockEntity(new Blueprint.BlockEntity(x, y, z, Blueprint.Kind.CAMPFIRE, null, null, 0L, true, signal), spec);
    }

    // ------------------------------------------------------------------ объёмы, раскладка, точки

    void box(int u0, int dy0, int v0, int u1, int dy1, int v1, BpSpec spec) {
        fill(u0, dy0, v0, u1, dy1, v1, spec);
    }

    /** Полая коробка (стенки толщиной 1), без пола и потолка, если не заданы. */
    void shell(int u0, int dy0, int v0, int u1, int dy1, int v1, BpSpec spec) {
        for (int u = u0; u <= u1; u++)
            for (int v = v0; v <= v1; v++)
                if (u == u0 || u == u1 || v == v0 || v == v1)
                    for (int dy = dy0; dy <= dy1; dy++) set(u, dy, v, spec);
    }

    /** Детерминированный выбор из вариантов по мировой позиции (без ГСЧ-состояния — результат не зависит от порядка). */
    BpSpec pick(int u, int dy, int v, BpSpec... opts) {
        long h = WarzoneNoise.hash(seed + 31337L + dy * 131L, wx(u, v), wz(u, v));
        return opts[(int) ((h & 0x7FFFFFFF) % opts.length)];
    }

    /** Мировая позиция → псевдослучайное число 0..1 (стабильно для клетки). */
    double noise(int u, int dy, int v, int salt) {
        return WarzoneNoise.unit(WarzoneNoise.hash(seed + salt * 977L + dy * 131L, wx(u, v), wz(u, v)));
    }

    void reserve(int u0, int v0, int u1, int v1) {
        for (int u = Math.max(-OCC, Math.min(u0, u1)); u <= Math.min(OCC, Math.max(u0, u1)); u++)
            for (int v = Math.max(-OCC, Math.min(v0, v1)); v <= Math.min(OCC, Math.max(v0, v1)); v++)
                occ[u + OCC][v + OCC] = true;
    }

    /** Прямоугольник (с запасом margin) не пересекает уже занятое. */
    boolean isFree(int u0, int v0, int u1, int v1, int margin) {
        for (int u = Math.min(u0, u1) - margin; u <= Math.max(u0, u1) + margin; u++)
            for (int v = Math.min(v0, v1) - margin; v <= Math.max(v0, v1) + margin; v++) {
                if (u < -OCC || u > OCC || v < -OCC || v > OCC) return false;
                if (occ[u + OCC][v + OCC]) return false;
            }
        return true;
    }

    void spot(int u, int dy, int v, WarzoneBasePlan.SpotRole role, float localYaw) {
        spots.add(new WarzoneBasePlan.TacticalSpot(wx(u, v), baseY + dy, wz(u, v), role, (localYaw + rot * 90.0f) % 360.0f));
    }
}
