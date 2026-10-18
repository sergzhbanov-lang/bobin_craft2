package com.bobincraft.warzone;

import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import com.bobincraft.BobinCraft;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.WallShape;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Процедурный строитель вражеских баз, аванпостов, бункерных узлов, лагерей мародёров,
 * дозорных вышек, окопов и переправ в измерении «Зона боевых действий».
 *
 * Архитектура спроектирована специально под механики Bobin_craft:
 *  - Крыши казарм, штабов и бараков строятся из не-природных блоков и отделены от внешних стен двора
 *    зазором >= 2 блоков, поэтому система прозрачности крыш ({@code BuildingCutawayManager}) аккуратно
 *    скрывает крышу конкретного здания при подходе героя.
 *  - Все двери — деревянные ({@code PathFinder} и {@code BobinMovementController} распознают их как проходы
 *    и автоматически открывают на ходу, а Дробовик-Взломщик может их выбивать).
 *  - На вышках и в штабе есть полноценные пошаговые лестницы (+1 блок высоты на шаг с запасом высоты 3 блока
 *    над ступенью), по которым проходят и герой по клику ПКМ, и будущие ИИ-враги (этап 3).
 *  - В стенах и ДОТах предусмотрены бойницы из железных прутьев, простреливаемые окна из стекла,
 *    перегородки из сухого бетона (гипсокартона), брустверы из мешков с песком и тюков сена под систему
 *    пробития {@code BulletPenetration}.
 */
final class WarzoneStructures {
    private WarzoneStructures() {}

    /** Таблицы добычи для ящиков и сундуков на базах. */
    public static final Identifier LOOT_SUPPLY = new Identifier(BobinCraft.MOD_ID, "chests/warzone_supply");
    public static final Identifier LOOT_ARMORY = new Identifier(BobinCraft.MOD_ID, "chests/warzone_armory");
    public static final Identifier LOOT_COMMAND = new Identifier(BobinCraft.MOD_ID, "chests/warzone_command");

    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    /** Свойства соединений заборов, прутьев и стен (ставятся при пересчёте соединений в чанке). */
    private static final class Conn {
        /** Свойство «соединено с соседом» для стёкол, прутьев и заборов (FACING_PROPERTIES у них protected). */
        static BooleanProperty sideProp(Direction d) {
            return switch (d) {
                case NORTH -> Properties.NORTH;
                case EAST -> Properties.EAST;
                case SOUTH -> Properties.SOUTH;
                default -> Properties.WEST;
            };
        }

        static BlockState withWallSide(BlockState st, Direction d, WallShape shape) {
            return switch (d) {
                case NORTH -> st.with(WallBlock.NORTH_SHAPE, shape);
                case SOUTH -> st.with(WallBlock.SOUTH_SHAPE, shape);
                case EAST -> st.with(WallBlock.EAST_SHAPE, shape);
                case WEST -> st.with(WallBlock.WEST_SHAPE, shape);
                default -> st;
            };
        }
    }

    // ================================================================== Точка входа для чанка

    static void generate(WarzoneTerrain t, ChunkWriter w, WarzoneTerrain.Sample[][] samples) {
        WarzoneBasePlan plan = t.basePlan();
        if (plan == null) return;

        w.setTrackConnectables(true);

        // 1. Мосты через реки (приподнятые, 40% разрушены), дорожные фонари, горные серпантины и тропы
        boolean[][] covered = new boolean[16][16];
        try {
            buildBridges(t, plan, w, samples, covered);
        } catch (Throwable e) {
            org.slf4j.LoggerFactory.getLogger("bobin_craft/warzone")
                    .error("[Warzone] Ошибка постройки мостов в чанке {}, {}", w.minX >> 4, w.minZ >> 4, e);
        }
        roadsAndBridges(t, plan, w, samples, covered);
        try {
            IceTunnel.carve(t, plan, w);
        } catch (Throwable e) {
            org.slf4j.LoggerFactory.getLogger("bobin_craft/warzone")
                    .error("[Warzone] Ошибка прокладки ледяного тоннеля в чанке {}, {}", w.minX >> 4, w.minZ >> 4, e);
        }
        try {
            pathDecor(t, plan, w);
        } catch (Throwable e) {
            org.slf4j.LoggerFactory.getLogger("bobin_craft/warzone")
                    .error("[Warzone] Ошибка обустройства горных путей в чанке {}, {}", w.minX >> 4, w.minZ >> 4, e);
        }

        // 2. Вражеские базы, аванпосты, бункеры, лагеря, крепости, блокпосты и лесные укрытия:
        //    один чертёж на объект (SiteBuilder / HideoutBuilder), чанк лишь вставляет свой срез
        try {
            WarzoneBlueprints bps = t.blueprints();
            for (Blueprint bp : bps.poisForChunk(w.minX, w.minZ)) {
                try {
                    BlueprintBlitter.blit(bp, w);
                } catch (Throwable e) {
                    org.slf4j.LoggerFactory.getLogger("bobin_craft/warzone")
                            .error("[Warzone] Ошибка вставки {} в чанке {}, {}", bp.name, w.minX >> 4, w.minZ >> 4, e);
                }
            }
            for (String msg = bps.pollMessage(); msg != null; msg = bps.pollMessage()) {
                if (msg.startsWith("ERR")) {
                    org.slf4j.LoggerFactory.getLogger("bobin_craft/warzone").error("[Warzone] Ошибка чертежа: {}", msg);
                } else {
                    org.slf4j.LoggerFactory.getLogger("bobin_craft/warzone").info("[Warzone] {}", msg);
                }
            }
        } catch (Throwable e) {
            org.slf4j.LoggerFactory.getLogger("bobin_craft/warzone")
                    .error("[Warzone] Ошибка вставки баз и укрытий в чанке {}, {}", w.minX >> 4, w.minZ >> 4, e);
        }

        // 3. Пересчёт соединений заборов/прутьев/стен, поставленных дорогами и мостами, по реальным соседям
        try {
            reconnect(w);
        } catch (Throwable e) {
            org.slf4j.LoggerFactory.getLogger("bobin_craft/warzone")
                    .error("[Warzone] Ошибка пересчёта соединений в чанке {}, {}", w.minX >> 4, w.minZ >> 4, e);
        } finally {
            w.setTrackConnectables(false);
        }
    }

    // ================================================================== Дороги, мосты и указатели

    private static void roadsAndBridges(WarzoneTerrain t, WarzoneBasePlan plan,
                                        ChunkWriter w, WarzoneTerrain.Sample[][] samples, boolean[][] covered) {
        List<WarzoneBasePlan.RoadSegment> segs = plan.roadsForChunk(w.minX, w.minZ, 6);
        if (segs.isEmpty()) return;

        BlockState bridgeDeck = Blocks.SPRUCE_PLANKS.getDefaultState();
        BlockState bridgeTrim = Blocks.SPRUCE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM);
        BlockState bridgeLog = Blocks.SPRUCE_LOG.getDefaultState();
        int deckY = WarzoneTerrain.SEA_LEVEL + 1;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = w.minX + lx;
                int z = w.minZ + lz;
                if (!t.inside(x, z)) continue;

                WarzoneTerrain.Sample s = samples[lx + 1][lz + 1];
                if (s.road <= 0.05) continue;
                if (covered[lx][lz]) continue;   // здесь уже стоит приподнятый мост из плана

                int h = s.height;
                // Если военная дорога пересекает реку или озеро (или берег вплотную к воде) — строим деревянный мост
                if (h <= WarzoneTerrain.SEA_LEVEL && s.road >= 0.25) {
                    w.set(x, deckY, z, s.road >= 0.50 ? bridgeDeck : bridgeTrim);
                    for (int y = deckY + 1; y <= deckY + 4; y++) {
                        w.set(x, y, z, AIR);
                    }
                    // Опорные сваи моста каждые 4 блока
                    if (((x & 3) == 0 && (z & 3) == 0) && s.road >= 0.45) {
                        for (int y = Math.max(4, h); y <= deckY; y++) {
                            w.set(x, y, z, bridgeLog);
                        }
                    } else if (s.road < 0.48 && ((x + z) & 1) == 0) {
                        // Невысокие перила по краю моста
                        w.set(x, deckY + 1, z, Blocks.COBBLESTONE_WALL.getDefaultState());
                    }
                } else if (h == WarzoneTerrain.SEA_LEVEL + 1 && s.road >= 0.45) {
                    // Плавный заезд на мост с берега
                    w.set(x, deckY, z, Blocks.COBBLESTONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
                }
            }
        }

        // Дорожные фонарные столбы вдоль трактов (ориентиры в Тумане войны)
        for (WarzoneBasePlan.RoadSegment seg : segs) {
            int mx = (seg.x0() + seg.x1()) >> 1;
            int mz = (seg.z0() + seg.z1()) >> 1;
            if ((WarzoneNoise.hash(t.seed + 511, mx, mz) & 1) != 0) continue;
            double dx = seg.x1() - seg.x0();
            double dz = seg.z1() - seg.z0();
            double len = Math.hypot(dx, dz);
            if (len < 6.0) continue;
            int px = (int) Math.round(mx - (dz / len) * 3.5);
            int pz = (int) Math.round(mz + (dx / len) * 3.5);
            if (!w.contains(px, 70, pz) || !t.inside(px, pz)) continue;
            if (plan.nearestSite(px, pz) != null && plan.nearestSite(px, pz).distanceTo(px, pz) < plan.nearestSite(px, pz).flatRadius + 4) {
                continue;
            }
            int py = t.height(px, pz);
            if (py <= WarzoneTerrain.SEA_LEVEL + 1) continue;
            w.set(px, py, pz, Blocks.COBBLESTONE.getDefaultState());
            w.set(px, py + 1, pz, Blocks.COBBLESTONE_WALL.getDefaultState());
            w.set(px, py + 2, pz, Blocks.COBBLESTONE_WALL.getDefaultState());
            w.set(px, py + 3, pz, Blocks.LANTERN.getDefaultState());
        }
    }

    // ================================================================== Пересчёт соединений заборов, прутьев и стен

    /**
     * После постройки всех объектов чанка пересчитывает соединения заборов, железных прутьев, стёкол-панелей и стен
     * по реальным соседям. Убирает «ступеньки» и обрубки линий (торчащие в пустоту отростки). Соседи за границей
     * чанка недоступны — там сохраняется заданное при постройке соединение, поэтому линии через границу чанков целы.
     */
    private static void reconnect(ChunkWriter w) {
        w.setTrackConnectables(false);
        List<BlockPos> list = new ArrayList<>(w.connectables());
        for (BlockPos p : list) {
            BlockState st = w.get(p.getX(), p.getY(), p.getZ());
            Block blk = st.getBlock();
            BlockState ns;
            if (blk instanceof WallBlock) {
                ns = reconnectWall(w, p, st);
            } else if (blk instanceof FenceBlock || blk instanceof PaneBlock) {
                ns = reconnectSimple(w, p, st);
            } else {
                continue;
            }
            if (ns != st) w.set(p.getX(), p.getY(), p.getZ(), ns);
        }
    }

    private static boolean joins(ChunkWriter w, Block self, BlockState n, BlockPos np, Direction d) {
        Block nb = n.getBlock();
        if (self instanceof FenceBlock) {
            if (nb instanceof FenceBlock) return true;
        } else if (nb instanceof PaneBlock || nb instanceof WallBlock) {
            return true;
        }
        if (nb instanceof FenceGateBlock) {
            Direction gateFacing = n.get(HorizontalFacingBlock.FACING);
            return gateFacing.rotateYClockwise().getAxis() == d.getAxis();
        }
        return n.isOpaqueFullCube(w.chunk, np);
    }

    private static BlockState reconnectSimple(ChunkWriter w, BlockPos p, BlockState st) {
        BlockState out = st;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BooleanProperty prop = Conn.sideProp(d);
            if (!st.contains(prop)) continue;
            int nx = p.getX() + d.getOffsetX();
            int nz = p.getZ() + d.getOffsetZ();
            if (!w.contains(nx, p.getY(), nz)) continue;      // сосед в другом чанке — оставляем как построено
            BlockPos np = new BlockPos(nx, p.getY(), nz);
            boolean connect = joins(w, st.getBlock(), w.get(nx, p.getY(), nz), np, d);
            out = out.with(prop, connect);
        }
        return out;
    }

    private static BlockState reconnectWall(ChunkWriter w, BlockPos p, BlockState st) {
        BlockState out = st;
        BlockState above = w.get(p.getX(), p.getY() + 1, p.getZ());
        boolean tall = above.getBlock() instanceof WallBlock || above.isOpaqueFullCube(w.chunk, p.up());
        boolean n = false, s = false, e = false, we = false;
        for (Direction d : Direction.Type.HORIZONTAL) {
            int nx = p.getX() + d.getOffsetX();
            int nz = p.getZ() + d.getOffsetZ();
            WallShape cur = wallSide(st, d);
            boolean connect;
            if (!w.contains(nx, p.getY(), nz)) {
                connect = cur != WallShape.NONE;
            } else {
                BlockPos np = new BlockPos(nx, p.getY(), nz);
                connect = joins(w, st.getBlock(), w.get(nx, p.getY(), nz), np, d);
            }
            out = Conn.withWallSide(out, d, connect ? (tall ? WallShape.TALL : WallShape.LOW) : WallShape.NONE);
            switch (d) {
                case NORTH -> n = connect;
                case SOUTH -> s = connect;
                case EAST -> e = connect;
                default -> we = connect;
            }
        }
        boolean straight = (n && s && !e && !we) || (e && we && !n && !s);
        return out.with(WallBlock.UP, !straight || !above.isAir());
    }

    private static WallShape wallSide(BlockState st, Direction d) {
        return switch (d) {
            case NORTH -> st.get(WallBlock.NORTH_SHAPE);
            case SOUTH -> st.get(WallBlock.SOUTH_SHAPE);
            case EAST -> st.get(WallBlock.EAST_SHAPE);
            default -> st.get(WallBlock.WEST_SHAPE);
        };
    }

    // ================================================================== Мосты

    private static final BlockState B_LOG = Blocks.SPRUCE_LOG.getDefaultState();

    /**
     * Мосты по чертежам {@link BridgeBlueprint}: каждый мост строится ОДИН раз целиком ({@link BridgeBuilder},
     * детерминированно из seed и плана), а чанк лишь вставляет свой срез ({@link BlueprintBlitter}). Поэтому на границах
     * чанков нет швов, а порядок генерации чанков не влияет на результат. В covered[lx][lz] отмечаются колонки,
     * занятые мостом (старый «запасной» настил дороги над водой их пропускает).
     */
    private static void buildBridges(WarzoneTerrain t, WarzoneBasePlan plan, ChunkWriter w,
                                     WarzoneTerrain.Sample[][] samples, boolean[][] covered) {
        WarzoneBlueprints bps = t.blueprints();
        List<BridgeBlueprint> list = bps.bridgesForChunk(w.minX, w.minZ);
        for (String msg = bps.pollMessage(); msg != null; msg = bps.pollMessage()) {
            if (msg.startsWith("ERR")) {
                org.slf4j.LoggerFactory.getLogger("bobin_craft/warzone").error("[Warzone] Ошибка чертежа моста: {}", msg);
            } else {
                org.slf4j.LoggerFactory.getLogger("bobin_craft/warzone").info("[Warzone] {}", msg);
            }
        }
        if (list.isEmpty()) return;

        for (BridgeBlueprint bb : list) {
            try {
                BlueprintBlitter.blit(bb.blueprint, w);
            } catch (Throwable e) {
                org.slf4j.LoggerFactory.getLogger("bobin_craft/warzone")
                        .error("[Warzone] Ошибка вставки моста #{} в чанке {}, {}", bb.index, w.minX >> 4, w.minZ >> 4, e);
            }
        }

        double[] q = new double[2];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = w.minX + lx;
                int z = w.minZ + lz;
                boolean cov = false;
                for (BridgeBlueprint bb : list) {
                    if (bb.reserves(x, z)) {
                        cov = true;
                        break;
                    }
                }
                if (!cov) {
                    WarzoneBasePlan.Bridge pb = plan.bridgeAt(x, z, q);
                    cov = pb != null && bps.bridge(pb) != null;
                }
                covered[lx][lz] = cov;
            }
        }
    }

    // ================================================================== Горные дороги и тропы

    private static void pathDecor(WarzoneTerrain t, WarzoneBasePlan plan, ChunkWriter w) {
        List<WarzoneBasePlan.TrailPath> list = plan.pathsForChunk(w.minX, w.minZ, 6);
        for (WarzoneBasePlan.TrailPath p : list) {
            if (p.tunnel) continue;   // у тоннеля свой интерьер (IceTunnel)
            int len = p.length();
            for (int i = 0; i + 1 < len; i++) {
                double dx = p.px[i + 1] - p.px[i];
                double dz = p.pz[i + 1] - p.pz[i];
                double d = Math.hypot(dx, dz);
                if (d < 1e-6) continue;
                double nx = -dz / d;
                double nz = dx / d;
                boolean lastPoint = i + 2 >= len;
                if (!p.trail) {
                    // фонарные столбы вдоль серпантина: раз в ~15 блоков, стороны чередуются
                    if (i % 3 != 1 || Double.isNaN(p.py[i])) continue;
                    double side = ((i / 3) & 1) == 0 ? 1 : -1;
                    int px = (int) Math.round(p.px[i] + nx * side * (p.halfWidth + 1.4));
                    int pz = (int) Math.round(p.pz[i] + nz * side * (p.halfWidth + 1.4));
                    if (!w.contains(px, 70, pz) || !t.inside(px, pz)) continue;
                    int py = t.height(px, pz);
                    w.set(px, py, pz, Blocks.COBBLESTONE.getDefaultState());
                    w.set(px, py + 1, pz, Blocks.COBBLESTONE_WALL.getDefaultState());
                    w.set(px, py + 2, pz, Blocks.COBBLESTONE_WALL.getDefaultState());
                    w.set(px, py + 3, pz, Blocks.LANTERN.getDefaultState());
                } else {
                    // скрытая тропа: редкие едва заметные пирамидки из камней; на стыке с дорогой — всегда
                    long hh = WarzoneNoise.hash(t.seed + 612, (int) p.px[i], (int) p.pz[i]);
                    if (!(lastPoint || (i % 6 == 3 && (hh & 3) == 0))) continue;
                    double side = (hh & 8) == 0 ? 1 : -1;
                    int px = (int) Math.round(p.px[i] + nx * side * (p.halfWidth + 1.2));
                    int pz = (int) Math.round(p.pz[i] + nz * side * (p.halfWidth + 1.2));
                    if (!w.contains(px, 70, pz) || !t.inside(px, pz)) continue;
                    int py = t.height(px, pz);
                    if (py <= WarzoneTerrain.SEA_LEVEL) continue;
                    w.set(px, py + 1, pz, Blocks.COBBLESTONE.getDefaultState());
                    w.set(px, py + 2, pz, Blocks.MOSSY_COBBLESTONE.getDefaultState());
                    w.set(px, py + 3, pz, Blocks.COBBLESTONE_WALL.getDefaultState());
                }
            }
            if (!p.trail && !Double.isNaN(p.py[len - 1])) {
                footArch(t, w, p);
            }
        }
    }

    /**
     * Въездная арка у подножия горной дороги: два бревенчатых столба, балка и фонарь.
     * Каждый столб стоит на РЕАЛЬНОЙ земле своей колонки (а не на высоте центра арки), поэтому на склоне он не
     * зависает и не уходит в скалу; если склон слишком крутой, арка не строится вовсе. Все высоты — чистая функция
     * от terrain, поэтому каждый чанк строит одну и ту же арку.
     */
    private static void footArch(WarzoneTerrain t, ChunkWriter w, WarzoneBasePlan.TrailPath p) {
        int n = p.length();
        double dx = p.px[n - 1] - p.px[n - 2];
        double dz = p.pz[n - 1] - p.pz[n - 2];
        double d = Math.hypot(dx, dz);
        if (d < 1e-6) return;
        double nx = -dz / d;
        double nz = dx / d;
        int half = (int) Math.ceil(p.halfWidth) + 1;
        int fy = t.height(p.footX, p.footZ);
        boolean alongX = Math.abs(nx) >= Math.abs(nz);
        BlockState beam = B_LOG.with(PillarBlock.AXIS, alongX ? Direction.Axis.X : Direction.Axis.Z);

        int span = 2 * half + 1;
        int[] cx = new int[span];
        int[] cz = new int[span];
        int[] cg = new int[span];
        for (int k = -half; k <= half; k++) {
            int i = k + half;
            cx[i] = (int) Math.round(p.footX + nx * k);
            cz[i] = (int) Math.round(p.footZ + nz * k);
            cg[i] = t.height(cx[i], cz[i]);
            // проход под балкой должен быть свободен, столбы — вставать на землю не слишком далеко от уровня дороги
            if (cg[i] > fy + 3) return;
            if (Math.abs(k) == half && cg[i] < fy - 5) return;
        }
        for (int k = -half; k <= half; k++) {
            int i = k + half;
            int x = cx[i];
            int z = cz[i];
            if (Math.abs(k) == half) {
                for (int y = cg[i] + 1; y <= fy + 5; y++) w.set(x, y, z, B_LOG);      // от земли своей колонки до балки
                w.set(x, fy + 6, z, Blocks.LANTERN.getDefaultState());
            } else {
                for (int y = Math.max(cg[i], fy) + 1; y <= fy + 4; y++) w.set(x, y, z, Blocks.AIR.getDefaultState());
                w.set(x, fy + 5, z, beam);
                if (k == 0) w.set(x, fy + 4, z, Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, true));
            }
        }
    }
}
