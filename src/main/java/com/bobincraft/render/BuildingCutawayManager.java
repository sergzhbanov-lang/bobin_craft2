package com.bobincraft.render;

import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import com.bobincraft.raid.HubContent;
import com.bobincraft.camera.BobinCameraState;

import java.util.*;

/**
 * Динамический «разрез» здания (Roof Cutaway).
 *
 * Когда персонаж подходит к зданию (или находится внутри), крыша и все части здания,
 * расположенные выше потолка (верхние части стен, чердак, верхние этажи), исчезают из рендера,
 * чтобы было видно интерьер. Нижние стены (на уровне ног/головы) остаются,
 * а клики мыши проходят сквозь скрытые блоки (см. RaycastHelper).
 *
 * Потокобезопасность: чанки перестраиваются в рабочих потоках и вызывают isCutawayBlock(),
 * поэтому набор скрытых блоков хранится как неизменяемый volatile-снимок и подменяется целиком.
 */
public class BuildingCutawayManager {

    private static final int SCAN_RADIUS = 7;          // радиус поиска помещения вокруг персонажа
    private static final int APPROACH_RADIUS = 5;      // с какого расстояния крыша становится прозрачной
    private static final int LEAVE_RADIUS = 7;         // на каком расстоянии крыша возвращается (гистерезис)
    private static final int FLOOD_HORIZONTAL = 18;    // максимальный горизонтальный размер здания от персонажа
    private static final int FLOOD_HEIGHT = 24;        // максимальная высота здания над персонажем
    private static final int FLOOD_MAX_BLOCKS = 8000;  // защита от слишком больших структур
    private static final int GHOST_MAX_BLOCKS = 1500;  // максимум блоков для призрачной подсветки

    private static volatile Set<Long> hiddenBlocks = Collections.emptySet();
    private static volatile boolean cutawayActive = false;
    private static volatile List<BlockPos> ghostOutline = Collections.emptyList();

    /**
     * Режимы крыш (H): авто — крыша полностью исчезает (и модель, и текстура, без каких-либо контуров),
     * авто + призрачный контур, выключено.
     */
    public enum RoofMode { AUTO_CLEAN, AUTO_GHOST, OFF }

    // По умолчанию крыша пропадает целиком: никакого голубого «призрака» на её месте
    private static volatile RoofMode mode = RoofMode.AUTO_CLEAN;
    private static final int SCAN_INTERVAL_TICKS = 5;    // 4 скана в секунду вместо скана каждые 4 кадра
    private static final int ANCHOR_RESET_DIST = 8;      // якорь здания: пока герой рядом, область заливки не «ползёт»
    private static long lastScanTick = -1000L;
    private static BlockPos anchor = null;
    private static ClientWorld sourceWorld;

    public static RoofMode getMode() {
        return mode;
    }

    public static void cycleMode(MinecraftClient client) {
        RoofMode[] all = RoofMode.values();
        mode = all[(mode.ordinal() + 1) % all.length];
        lastScanTick = -1000L;
        if (mode == RoofMode.OFF) {
            clearCutaway(client);
        }
    }

    public static String getModeLabel() {
        return switch (mode) {
            case AUTO_CLEAN -> "§a[Крыши] Авто (крыша полностью исчезает)";
            case AUTO_GHOST -> "§a[Крыши] Авто (исчезает, остаётся призрачный контур)";
            case OFF -> "§7[Крыши] Выключено (крыши всегда видны)";
        };
    }

    public static boolean isCutawayActive() {
        return cutawayActive;
    }

    /** Вызывается из рабочих потоков построения чанков и из основного потока. */
    public static boolean isCutawayBlock(BlockPos pos) {
        return cutawayActive && hiddenBlocks.contains(pos.asLong()) || FoliageCutawayManager.isHidden(pos);
    }

    /** Блоки на поверхности скрытой области — для лёгкой призрачной подсветки вместо крыши. */
    public static List<BlockPos> getGhostOutline() {
        return (cutawayActive && mode == RoofMode.AUTO_GHOST) ? ghostOutline : Collections.emptyList();
    }

    public static void tick(MinecraftClient client) {
        if (client != null && sourceWorld != client.world) {
            FoliageCutawayManager.clear(client);
            clearCutaway(client);
            sourceWorld = client.world;
            lastScanTick = -1000L;
        }
        if (client == null || client.world == null || client.player == null) {
            sourceWorld = null;
            clearCutaway(client);
            return;
        }

        if (mode == RoofMode.OFF || !BobinCameraState.isActive()) {
            FoliageCutawayManager.clear(client);
            clearCutaway(client);
            return;
        }

        FoliageCutawayManager.tick(client);

        // Скан по игровым тикам (а не по кадрам): FPS больше не влияет на нагрузку
        long nowTick = client.world.getTime();
        if (nowTick >= lastScanTick && nowTick - lastScanTick < SCAN_INTERVAL_TICKS) {
            return;
        }
        lastScanTick = nowTick;

        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        BlockPos playerPos = player.getBlockPos();
        int playerY = playerPos.getY();

        int nearRadius = cutawayActive ? LEAVE_RADIUS : APPROACH_RADIUS;

        List<BlockPos> seeds = new ArrayList<>();
        boolean nearBuilding = false;
        int ceilingColumns = 0;
        int cutY = Integer.MAX_VALUE; // отсекаем всё, что выше этого уровня

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                int distSqH = dx * dx + dz * dz;
                if (distSqH > SCAN_RADIUS * SCAN_RADIUS) continue;

                int x = playerPos.getX() + dx;
                int z = playerPos.getZ() + dz;

                // Пол помещения может быть на блок выше (цоколь/ступенька) или ниже уровня персонажа
                for (int dy = 1; dy >= -1; dy--) {
                    int feetY = playerY + dy;
                    if (!isInteriorCell(world, x, feetY, z)) continue;

                    int roofY = -1;
                    for (int y = feetY + 2; y <= feetY + 8; y++) {
                        if (isRoofBlock(world.getBlockState(new BlockPos(x, y, z)))) {
                            roofY = y;
                            break;
                        }
                    }
                    if (roofY == -1) continue;

                    ceilingColumns++;
                    seeds.add(new BlockPos(x, roofY, z));
                    cutY = Math.min(cutY, roofY);
                    if (distSqH <= nearRadius * nearRadius) {
                        nearBuilding = true;
                    }
                    break;
                }
            }
        }

        boolean shouldCutaway = nearBuilding && ceilingColumns >= 5 && !seeds.isEmpty();
        if (!shouldCutaway) {
            clearCutaway(client);
            return;
        }

        // Стабильный якорь: область заливки привязана к зданию, а не к каждому шагу героя —
        // набор скрытых блоков не мерцает и не вызывает лишние перестроения чанков при ходьбе внутри
        if (anchor == null || !cutawayActive
                || Math.abs(anchor.getX() - playerPos.getX()) > ANCHOR_RESET_DIST
                || Math.abs(anchor.getZ() - playerPos.getZ()) > ANCHOR_RESET_DIST) {
            anchor = playerPos;
        }
        Set<Long> newHidden = floodBuilding(world, anchor, seeds, cutY);
        if (newHidden.isEmpty()) {
            clearCutaway(client);
            return;
        }

        if (cutawayActive && newHidden.equals(hiddenBlocks)) {
            return;
        }

        Set<Long> old = hiddenBlocks;
        hiddenBlocks = Collections.unmodifiableSet(newHidden);
        ghostOutline = buildGhostOutline(newHidden);
        cutawayActive = true;

        // Перестраиваем только чанки, где набор реально изменился (симметрическая разность)
        scheduleRebuild(client, symmetricDifference(old, newHidden));
    }

    private static Set<Long> symmetricDifference(Set<Long> a, Set<Long> b) {
        Set<Long> out = new HashSet<>();
        for (Long k : a) if (!b.contains(k)) out.add(k);
        for (Long k : b) if (!a.contains(k)) out.add(k);
        return out;
    }

    /** Клетка помещения: под ногами твёрдый пол, на уровне ног и головы пусто (или дверь/ковёр). */
    private static boolean isInteriorCell(ClientWorld world, int x, int feetY, int z) {
        BlockPos feet = new BlockPos(x, feetY, z);
        BlockPos floor = feet.down();
        BlockState floorState = world.getBlockState(floor);
        if (floorState.isAir() || floorState.getCollisionShape(world, floor).isEmpty()) return false;
        if (!isOpenSpace(world, feet)) return false;
        return isOpenSpace(world, feet.up());
    }

    private static boolean isOpenSpace(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof CarpetBlock) return true;
        return state.getCollisionShape(world, pos).isEmpty();
    }

    /** Естественный рельеф не считаем частью здания (чтобы не скрывать холмы и пещеры). */
    public static boolean isNaturalTerrain(BlockState state) {
        return state.isIn(BlockTags.DIRT)
                || state.isIn(BlockTags.SAND)
                || state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isIn(BlockTags.LEAVES)
                || state.isOf(Blocks.GRAVEL)
                || state.isOf(Blocks.SNOW_BLOCK)
                || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.CLAY)
                || state.isOf(Blocks.MUD);
    }

    /** Блок, который может быть потолком/крышей. */
    private static boolean isRoofBlock(BlockState state) {
        if (state == null || state.isAir() || isNaturalTerrain(state)) return false;
        Block b = state.getBlock();
        return b == HubContent.TENT_FABRIC || b == HubContent.CAMOUFLAGE_NET
                || b instanceof StairsBlock
                || b instanceof SlabBlock
                || b instanceof PillarBlock
                || state.isOpaque();
    }

    /**
     * Заливка (flood fill) от найденных кусков потолка по соединённым блокам здания
     * выше уровня потолка. Так скрывается вся крыша целиком (даже если она больше радиуса сканирования)
     * и верхние части стен, а нижние стены остаются.
     */
    private static Set<Long> floodBuilding(ClientWorld world, BlockPos origin, List<BlockPos> seeds, int cutY) {
        Set<Long> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        for (BlockPos s : seeds) {
            if (visited.add(s.asLong())) queue.add(s);
        }

        int minY = cutY;
        int maxY = origin.getY() + FLOOD_HEIGHT;
        BlockPos.Mutable m = new BlockPos.Mutable();

        while (!queue.isEmpty() && visited.size() < FLOOD_MAX_BLOCKS) {
            BlockPos cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int nx = cur.getX() + dx;
                        int ny = cur.getY() + dy;
                        int nz = cur.getZ() + dz;
                        if (ny < minY || ny > maxY) continue;
                        if (Math.abs(nx - origin.getX()) > FLOOD_HORIZONTAL || Math.abs(nz - origin.getZ()) > FLOOD_HORIZONTAL) continue;

                        m.set(nx, ny, nz);
                        long key = m.asLong();
                        if (visited.contains(key)) continue;

                        BlockState st = world.getBlockState(m);
                        if (st.isAir() || isNaturalTerrain(st)) continue;
                        // Жидкости и растения не тянем
                        if (!st.getFluidState().isEmpty()) continue;

                        visited.add(key);
                        queue.add(m.toImmutable());
                    }
                }
            }
        }
        return visited;
    }

    private static List<BlockPos> buildGhostOutline(Set<Long> blocks) {
        List<BlockPos> result = new ArrayList<>();
        for (long key : blocks) {
            BlockPos p = BlockPos.fromLong(key);
            boolean exposed = false;
            for (Direction d : Direction.values()) {
                if (!blocks.contains(p.offset(d).asLong())) {
                    exposed = true;
                    break;
                }
            }
            if (exposed) {
                result.add(p);
                if (result.size() >= GHOST_MAX_BLOCKS) break;
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static void scheduleRebuild(MinecraftClient client, Set<Long> blocks) {
        if (client == null || client.worldRenderer == null || blocks.isEmpty()) return;

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (long key : blocks) {
            int x = BlockPos.unpackLongX(key);
            int y = BlockPos.unpackLongY(key);
            int z = BlockPos.unpackLongZ(key);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        client.worldRenderer.scheduleBlockRenders(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1);
    }

    public static void clearCutaway(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null || !BobinCameraState.isActive())
            FoliageCutawayManager.clear(client);
        anchor = null;
        if (!cutawayActive && hiddenBlocks.isEmpty()) return;
        Set<Long> old = hiddenBlocks;
        cutawayActive = false;
        hiddenBlocks = Collections.emptySet();
        ghostOutline = Collections.emptyList();
        scheduleRebuild(client, old);
    }
}
