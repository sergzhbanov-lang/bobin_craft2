package com.bobincraft.warzone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;

/**
 * Чертёж сооружения: плотный массив палитровых индексов в мировых координатах (bbox фиксирован при создании).
 * Строится ОДИН раз (детерминированно из seed + плана) и затем только «нарезается» по чанкам
 * ({@link #forEachInChunk}) — результат не зависит от порядка генерации чанков, швов на границах нет.
 * Нет зависимостей от Minecraft: проверяется headless.
 */
public final class Blueprint {
    public enum Mode { SET, IF_AIR }

    public enum Kind { CHEST, BARREL, CAMPFIRE }

    public interface CellVisitor {
        void accept(int x, int y, int z, BpSpec spec, Mode mode);
    }

    /** Высота верхнего твёрдого блока естественного рельефа в колонке. */
    public interface Ground {
        int top(int x, int z);
    }

    public record BlockEntity(int x, int y, int z, Kind kind, String facing, String loot, long seed,
                              boolean lit, boolean signal) {}

    public final String name;
    public final int minX, minY, minZ, sizeX, sizeY, sizeZ;
    private final short[] cells;
    private final List<BpSpec> specs = new ArrayList<>();
    private final List<Mode> modes = new ArrayList<>();
    private final Map<String, Short> palette = new HashMap<>();
    private final List<BlockEntity> blockEntities = new ArrayList<>();
    /** Записи за пределы bbox (должно быть 0 — это ошибка построителя). */
    public int droppedWrites;
    /** Сколько блоков удалила {@link #pruneFloating} (для самопроверок: ждём 0 у вышек и укрытий). */
    public int prunedCount;
    /** Первые удалённые висящие блоки (для диагностики). */
    public final List<String> prunedSample = new ArrayList<>();

    public Blueprint(String name, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
        if ((long) sizeX * sizeY * sizeZ > 12_000_000L) throw new IllegalArgumentException("blueprint too large: " + name);
        this.name = name;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = new short[sizeX * sizeY * sizeZ];
        specs.add(null);
        modes.add(null);
    }

    public int maxX() { return minX + sizeX - 1; }
    public int maxY() { return minY + sizeY - 1; }
    public int maxZ() { return minZ + sizeZ - 1; }

    public boolean inBounds(int x, int y, int z) {
        return x >= minX && x < minX + sizeX && y >= minY && y < minY + sizeY && z >= minZ && z < minZ + sizeZ;
    }

    private int idx(int x, int y, int z) {
        return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
    }

    public void set(int x, int y, int z, BpSpec spec) {
        set(x, y, z, spec, Mode.SET);
    }

    public void set(int x, int y, int z, BpSpec spec, Mode mode) {
        if (!inBounds(x, y, z)) {
            droppedWrites++;
            return;
        }
        String key = mode.ordinal() + spec.toString();
        Short p = palette.get(key);
        if (p == null) {
            if (specs.size() >= Short.MAX_VALUE) throw new IllegalStateException("palette overflow: " + name);
            p = (short) specs.size();
            specs.add(spec);
            modes.add(mode);
            palette.put(key, p);
        }
        cells[idx(x, y, z)] = p;
    }

    public void air(int x, int y, int z) {
        set(x, y, z, BpSpec.AIR, Mode.SET);
    }

    public void clear(int x, int y, int z) {
        if (inBounds(x, y, z)) cells[idx(x, y, z)] = 0;
    }

    /** Спецификация блока или null, если клетка не тронута чертежом (или вне bbox). */
    public BpSpec get(int x, int y, int z) {
        if (!inBounds(x, y, z)) return null;
        short c = cells[idx(x, y, z)];
        return c == 0 ? null : specs.get(c);
    }

    public Mode modeAt(int x, int y, int z) {
        if (!inBounds(x, y, z)) return null;
        short c = cells[idx(x, y, z)];
        return c == 0 ? null : modes.get(c);
    }

    /** В клетке стоит что-то кроме воздуха. */
    public boolean solidAt(int x, int y, int z) {
        BpSpec s = get(x, y, z);
        return s != null && !s.isAir();
    }

    public boolean hasColumn(int x, int z) {
        if (x < minX || x >= minX + sizeX || z < minZ || z >= minZ + sizeZ) return false;
        for (int y = minY; y < minY + sizeY; y++) if (solidAt(x, y, z)) return true;
        return false;
    }

    public int count() {
        int n = 0;
        for (short c : cells) if (c != 0) n++;
        return n;
    }

    public int solidCount() {
        int n = 0;
        for (short c : cells) if (c != 0 && !specs.get(c).isAir()) n++;
        return n;
    }

    // ------------------------------------------------------------------ нарезка по чанкам

    public boolean intersectsChunk(int chunkMinX, int chunkMinZ) {
        return chunkMinX + 15 >= minX && chunkMinX < minX + sizeX && chunkMinZ + 15 >= minZ && chunkMinZ < minZ + sizeZ;
    }

    /** Все клетки чертежа, попадающие в чанк (chunkMinX/Z — мировые координаты его угла). */
    public void forEachInChunk(int chunkMinX, int chunkMinZ, CellVisitor v) {
        int x0 = Math.max(chunkMinX, minX);
        int x1 = Math.min(chunkMinX + 15, minX + sizeX - 1);
        int z0 = Math.max(chunkMinZ, minZ);
        int z1 = Math.min(chunkMinZ + 15, minZ + sizeZ - 1);
        if (x0 > x1 || z0 > z1) return;
        for (int y = minY; y < minY + sizeY; y++) {
            for (int z = z0; z <= z1; z++) {
                int row = ((y - minY) * sizeZ + (z - minZ)) * sizeX - minX;
                for (int x = x0; x <= x1; x++) {
                    short c = cells[row + x];
                    if (c != 0) v.accept(x, y, z, specs.get(c), modes.get(c));
                }
            }
        }
    }

    /** Обход всех клеток чертежа (без привязки к чанкам). */
    public void forEachCell(CellVisitor v) {
        for (int y = minY; y < minY + sizeY; y++) {
            for (int z = minZ; z < minZ + sizeZ; z++) {
                int row = ((y - minY) * sizeZ + (z - minZ)) * sizeX - minX;
                for (int x = minX; x < minX + sizeX; x++) {
                    short c = cells[row + x];
                    if (c != 0) v.accept(x, y, z, specs.get(c), modes.get(c));
                }
            }
        }
    }

    // ------------------------------------------------------------------ блок-сущности (сундуки, бочки, костры)

    public void addBlockEntity(BlockEntity be, BpSpec spec) {
        set(be.x(), be.y(), be.z(), spec, Mode.SET);
        blockEntities.add(be);
    }

    public List<BlockEntity> blockEntities() {
        return Collections.unmodifiableList(blockEntities);
    }

    // ------------------------------------------------------------------ ототпечаток для самопроверок

    private static long cellHash(int x, int y, int z, BpSpec s, Mode m) {
        long h = x * 0x9E3779B97F4A7C15L + y * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L
                + s.toString().hashCode() * 0xFF51AFD7ED558CCDL + m.ordinal() * 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }

    /** Коммутативная сумма хэшей клеток (порядок обхода не важен). */
    public static long hashOf(int x, int y, int z, BpSpec s, Mode m) {
        return cellHash(x, y, z, s, m);
    }

    public long fingerprint() {
        final long[] acc = {0};
        forEachCell((x, y, z, s, m) -> acc[0] += cellHash(x, y, z, s, m));
        for (BlockEntity b : blockEntities) {
            acc[0] += (b.x() * 31L + b.y()) * 131L + b.z() + b.kind().ordinal() + b.seed() + String.valueOf(b.loot()).hashCode();
        }
        return acc[0];
    }

    // ------------------------------------------------------------------ соединения заборов и панелей (один раз на весь чертёж)

    private static final int[][] DIRS = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
    private static final String[] DIR_NAMES = {"north", "east", "south", "west"};

    /**
     * Проставляет north/east/south/west у заборов и панелей по реальным соседям — в границах всего чертежа,
     * а не чанка. Клетка вне чертежа считается твёрдой, если она ниже поверхности рельефа.
     */
    public void connect(Ground g) {
        for (int y = minY; y < minY + sizeY; y++) {
            for (int z = minZ; z < minZ + sizeZ; z++) {
                for (int x = minX; x < minX + sizeX; x++) {
                    short c = cells[idx(x, y, z)];
                    if (c == 0) continue;
                    BpSpec s = specs.get(c);
                    if (s.isWall()) {
                        BpSpec nw = connectWall(s, x, y, z, g);
                        if (!nw.equals(s)) set(x, y, z, nw, modes.get(c));
                        continue;
                    }
                    if (!s.isFence() && !s.isPane()) continue;
                    BpSpec ns = s;
                    for (int d = 0; d < 4; d++) {
                        ns = ns.with(DIR_NAMES[d], joins(s, x + DIRS[d][0], y, z + DIRS[d][1], g));
                    }
                    if (!ns.equals(s)) set(x, y, z, ns, modes.get(c));
                }
            }
        }
    }

    /** Стена: стороны none/low/tall по соседям (стены, панели, полные блоки), столб up — как в ванили. */
    private BpSpec connectWall(BpSpec s, int x, int y, int z, Ground g) {
        BpSpec above = get(x, y + 1, z);
        boolean aboveFull = above != null && !above.isAir() && (above.isWall() || above.isFullBlock());
        boolean[] c = new boolean[4];
        BpSpec ns = s;
        for (int d = 0; d < 4; d++) {
            c[d] = joins(s, x + DIRS[d][0], y, z + DIRS[d][1], g);
            ns = ns.with(DIR_NAMES[d], c[d] ? (aboveFull ? "tall" : "low") : "none");
        }
        boolean straight = (c[0] && c[2] && !c[1] && !c[3]) || (c[1] && c[3] && !c[0] && !c[2]);
        boolean aboveSolid = above != null && !above.isAir();
        return ns.with("up", !straight || aboveSolid);
    }

    private boolean joins(BpSpec self, int nx, int ny, int nz, Ground g) {
        BpSpec n = get(nx, ny, nz);
        if (n == null) return ny <= g.top(nx, nz);
        if (n.isAir()) return false;
        if (self.isFence()) return n.isFence() || n.isFullBlock();
        if (self.isWall()) return n.isWall() || n.isPane() || n.isFullBlock();
        return n.isPane() || n.isWall() || n.isFullBlock();
    }

    // ------------------------------------------------------------------ опора: ничто не должно висеть в воздухе

    /** true для клеток, связанных гранями или рёбрами с землёй: y ≤ поверхность+1. */
    private boolean[] grounded(Ground g) {
        int n = cells.length;
        boolean[] vis = new boolean[n];
        int[] queue = new int[n];
        int head = 0, tail = 0;
        for (int y = minY; y < minY + sizeY; y++) {
            for (int z = minZ; z < minZ + sizeZ; z++) {
                for (int x = minX; x < minX + sizeX; x++) {
                    int i = idx(x, y, z);
                    short c = cells[i];
                    if (c == 0 || specs.get(c).isAir()) continue;
                    if (y <= g.top(x, z) + 1) {
                        vis[i] = true;
                        queue[tail++] = i;
                    }
                }
            }
        }
        int layer = sizeX * sizeZ;
        while (head < tail) {
            int i = queue[head++];
            int ix = i % sizeX;
            int iz = (i / sizeX) % sizeZ;
            int iy = i / layer;
            // соседи по граням и по рёбрам (ступени настила соприкасаются ребром — это тоже «прикреплено»)
            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        int nonZero = (ox != 0 ? 1 : 0) + (oy != 0 ? 1 : 0) + (oz != 0 ? 1 : 0);
                        if (nonZero == 0 || nonZero == 3) continue;          // сам блок и угловые соседи не считаются
                        int nx = ix + ox, ny = iy + oy, nz = iz + oz;
                        if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeZ) continue;
                        int j = (ny * sizeZ + nz) * sizeX + nx;
                        if (vis[j]) continue;
                        short c = cells[j];
                        if (c == 0 || specs.get(c).isAir()) continue;
                        vis[j] = true;
                        queue[tail++] = j;
                    }
                }
            }
        }
        return vis;
    }

    /**
     * Фундамент: под каждой колонкой, чей нижний блок (кроме фонарей и костров) висит выше поверхности рельефа,
     * достраивает столб {@code fill} вниз до реальной земли (но не глубже maxDepth блоков и не ниже bbox).
     * Возвращает число добавленных блоков.
     */
    public int addFoundations(Ground g, BpSpec fill, int maxDepth) {
        int added = 0;
        for (int z = minZ; z < minZ + sizeZ; z++) {
            for (int x = minX; x < minX + sizeX; x++) {
                int low = Integer.MIN_VALUE;
                for (int y = minY; y < minY + sizeY; y++) {
                    BpSpec s = get(x, y, z);
                    if (s == null || s.isAir()) continue;
                    low = y;
                    break;
                }
                if (low == Integer.MIN_VALUE) continue;
                BpSpec ls = get(x, low, z);
                if (ls.id.contains("lantern") || ls.id.contains("campfire")) continue;
                int top = g.top(x, z);
                if (low <= top + 1) continue;
                int bottom = Math.max(Math.max(minY, top + 1), low - maxDepth);
                for (int y = low - 1; y >= bottom; y--) {
                    if (get(x, y, z) != null && !get(x, y, z).isAir()) break;
                    set(x, y, z, fill);
                    added++;
                }
            }
        }
        return added;
    }

    /** Удаляет все блоки, не связанные с землёй. Возвращает их число. */
    public int pruneFloating(Ground g) {
        boolean[] vis = grounded(g);
        int removed = 0;
        for (int i = 0; i < cells.length; i++) {
            short c = cells[i];
            if (c == 0 || vis[i] || specs.get(c).isAir()) continue;
            if (prunedSample.size() < 12) {
                prunedSample.add(specs.get(c) + " @" + (minX + i % sizeX) + "," + (minY + i / (sizeX * sizeZ)) + "," + (minZ + (i / sizeX) % sizeZ));
            }
            cells[i] = 0;
            removed++;
        }
        prunedCount += removed;
        return removed;
    }

    /** Координаты (x,y,z) висящих блоков (кроме исключений, напр. обломки на воде). */
    public List<int[]> floatingCells(Ground g, LongPredicate exempt) {
        boolean[] vis = grounded(g);
        List<int[]> out = new ArrayList<>();
        int layer = sizeX * sizeZ;
        for (int i = 0; i < cells.length; i++) {
            short c = cells[i];
            if (c == 0 || vis[i] || specs.get(c).isAir()) continue;
            int ix = i % sizeX;
            int iz = (i / sizeX) % sizeZ;
            int iy = i / layer;
            int x = minX + ix, y = minY + iy, z = minZ + iz;
            if (exempt != null && exempt.test(BridgeBlueprint.key(x, y, z))) continue;
            out.add(new int[]{x, y, z});
        }
        return out;
    }
}
