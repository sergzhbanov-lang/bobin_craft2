package com.bobincraft.warzone;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

import java.util.Random;

/**
 * Руды, растительность (напочвенный покров + деревья/подлесок из WarzoneFlora) и посадочная площадка. Всё пишется только внутри текущего чанка
 * (деревья сажаются с отступом от границ), поэтому генерация чанков остаётся независимой друг от друга.
 */
final class WarzoneDecor {
    private WarzoneDecor() {}

    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    // ------------------------------------------------------------------ руды

    static void ores(WarzoneTerrain t, ChunkWriter w, WarzoneTerrain.Sample[][] samples) {
        Random rnd = new Random(WarzoneNoise.hash(t.seed + 31, w.minX >> 4, w.minZ >> 4));
        ore(w, rnd, Blocks.COAL_ORE.getDefaultState(), Blocks.DEEPSLATE_COAL_ORE.getDefaultState(), 16, 5, 95, 6);
        ore(w, rnd, Blocks.IRON_ORE.getDefaultState(), Blocks.DEEPSLATE_IRON_ORE.getDefaultState(), 10, 5, 70, 5);
        ore(w, rnd, Blocks.COPPER_ORE.getDefaultState(), Blocks.DEEPSLATE_COPPER_ORE.getDefaultState(), 6, 10, 60, 5);
        ore(w, rnd, Blocks.GOLD_ORE.getDefaultState(), Blocks.DEEPSLATE_GOLD_ORE.getDefaultState(), 4, 5, 32, 4);
        ore(w, rnd, Blocks.REDSTONE_ORE.getDefaultState(), Blocks.DEEPSLATE_REDSTONE_ORE.getDefaultState(), 5, 5, 16, 4);
        ore(w, rnd, Blocks.DIAMOND_ORE.getDefaultState(), Blocks.DEEPSLATE_DIAMOND_ORE.getDefaultState(), 2, 3, 15, 3);
        ore(w, rnd, Blocks.LAPIS_ORE.getDefaultState(), Blocks.DEEPSLATE_LAPIS_ORE.getDefaultState(), 2, 5, 28, 4);
    }

    private static void ore(ChunkWriter w, Random rnd, BlockState ore, BlockState deepOre, int tries, int minY, int maxY, int size) {
        for (int i = 0; i < tries; i++) {
            int x = w.minX + rnd.nextInt(16);
            int z = w.minZ + rnd.nextInt(16);
            int y = minY + rnd.nextInt(maxY - minY + 1);
            for (int k = 0; k < size; k++) {
                BlockState cur = w.get(x, y, z);
                if (cur.isOf(Blocks.STONE)) w.set(x, y, z, ore);
                else if (cur.isOf(Blocks.DEEPSLATE)) w.set(x, y, z, deepOre);
                switch (rnd.nextInt(3)) {
                    case 0 -> x += rnd.nextBoolean() ? 1 : -1;
                    case 1 -> y += rnd.nextBoolean() ? 1 : -1;
                    default -> z += rnd.nextBoolean() ? 1 : -1;
                }
            }
        }
    }

    // ------------------------------------------------------------------ растительность

    /**
     * Растительность чанка: сначала напочвенный покров (трава, цветы, папоротники, кусты ягод, камыш, кувшинки),
     * затем деревья и подлесок из {@link WarzoneFlora}. Деревья привязаны к ячейкам сетки и строятся каждым чанком,
     * которого касается крона, — поэтому кроны не обрезаются на границах чанков.
     */
    static void vegetation(WarzoneTerrain t, ChunkWriter w, WarzoneTerrain.Sample[][] samples) {
        WarzoneFlora flora = t.flora();
        groundCover(t, flora, w, samples);
        plants(flora, w);
    }

    private static void groundCover(WarzoneTerrain t, WarzoneFlora flora, ChunkWriter w, WarzoneTerrain.Sample[][] samples) {
        Random rnd = new Random(WarzoneNoise.hash(t.seed + 21, w.minX >> 4, w.minZ >> 4));
        final int sea = WarzoneTerrain.SEA_LEVEL;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = w.minX + lx;
                int z = w.minZ + lz;
                if (!t.inside(x, z)) continue;
                WarzoneTerrain.Sample s = samples[lx + 1][lz + 1];
                int h = s.height;
                double r = rnd.nextDouble();
                double r2 = rnd.nextDouble();
                double r3 = rnd.nextDouble();

                // ---- подводные и плавающие растения
                if (h < sea) {
                    if (!w.get(x, sea, z).isOf(Blocks.WATER)) continue;
                    boolean marsh = s.region == WarzoneTerrain.Region.MARSH;
                    if (marsh && h >= sea - 2 && r < 0.07 * (1.0 - s.pool)) {
                        w.setIfAir(x, sea + 1, z, Blocks.LILY_PAD.getDefaultState());
                    } else if (s.oasis > 0.05 && r < 0.04) {
                        w.setIfAir(x, sea + 1, z, Blocks.LILY_PAD.getDefaultState());
                    }
                    if (h >= sea - 4 && r2 < (marsh ? 0.10 : 0.03) * (1.0 - s.pool)
                            && w.get(x, h + 1, z).isOf(Blocks.WATER) && s.channel < 0.7) {
                        w.set(x, h + 1, z, Blocks.SEAGRASS.getDefaultState());
                    }
                    continue;
                }
                if (t.isReserved(x, z) || s.scar > 0.2 || s.edge < 8 || s.channel > 0.05 || s.pillar > 0.05) continue;

                BlockState top = w.get(x, h, z);
                boolean onGrass = top.isOf(Blocks.GRASS_BLOCK);
                int y = h + 1;

                switch (s.region) {
                    case PLAINS -> {
                        if (!onGrass) break;
                        double c = flora.cover(x, z);
                        if (r < 0.12 + 0.28 * c) {
                            if (r2 < 0.25) tall(w, x, y, z, Blocks.TALL_GRASS); else w.setIfAir(x, y, z, Blocks.GRASS.getDefaultState());
                        } else if (c > 0.45 && r2 < 0.05 * c) {
                            if (r3 < 0.25) tall(w, x, y, z, Blocks.LARGE_FERN); else w.setIfAir(x, y, z, Blocks.FERN.getDefaultState());
                        } else if (c > 0.40 && r3 < 0.004) {
                            w.setIfAir(x, y, z, Blocks.SWEET_BERRY_BUSH.getDefaultState().with(SweetBerryBushBlock.AGE, 2 + rnd.nextInt(2)));
                        } else if (c > 0.45 && r3 > 0.9975) {
                            w.setIfAir(x, y, z, r2 < 0.5 ? Blocks.RED_MUSHROOM.getDefaultState() : Blocks.BROWN_MUSHROOM.getDefaultState());
                        } else if (flora.meadow(x, z) > 0.5 && r3 < 0.35 * (1.0 - c)) {
                            flowerPatch(w, x, y, z, flora.meadowKind(x, z, 7));
                        }
                    }
                    case MARSH -> {
                        if (top.isOf(Blocks.MOSS_BLOCK)) {
                            if (r < 0.55) w.setIfAir(x, y, z, Blocks.MOSS_CARPET.getDefaultState());
                            break;
                        }
                        if (!onGrass) break;
                        double c = flora.cover(x, z);
                        if (r < 0.20 + 0.20 * c) {
                            if (r2 < 0.30) tall(w, x, y, z, Blocks.TALL_GRASS); else w.setIfAir(x, y, z, Blocks.GRASS.getDefaultState());
                        } else if (r2 < 0.05 + 0.05 * c) {
                            if (r3 < 0.3) tall(w, x, y, z, Blocks.LARGE_FERN); else w.setIfAir(x, y, z, Blocks.FERN.getDefaultState());
                        } else if (r3 < 0.015) {
                            w.setIfAir(x, y, z, Blocks.BLUE_ORCHID.getDefaultState());
                        }
                    }
                    case HIGHLANDS -> {
                        if (onGrass && s.mountain < 0.95) {
                            double c = flora.cover(x, z);
                            if (r < 0.10 + 0.20 * c) {
                                w.setIfAir(x, y, z, r2 < 0.35 ? Blocks.FERN.getDefaultState() : Blocks.GRASS.getDefaultState());
                            } else if (r2 < 0.02 + 0.06 * c) {
                                if (r3 < 0.3) tall(w, x, y, z, Blocks.LARGE_FERN); else w.setIfAir(x, y, z, Blocks.FERN.getDefaultState());
                            } else if (c > 0.3 && r3 < 0.003) {
                                w.setIfAir(x, y, z, Blocks.SWEET_BERRY_BUSH.getDefaultState().with(SweetBerryBushBlock.AGE, 2 + rnd.nextInt(2)));
                            }
                        } else if (r < 0.0012 && top.isOf(Blocks.STONE)) {
                            boulder(w, x, h, z, rnd);
                        }
                    }
                    case BADLANDS -> {
                        if (onGrass && s.oasis > 0.1) {
                            // зелёное кольцо оазиса
                            if (r < 0.30) {
                                if (r2 < 0.25) tall(w, x, y, z, Blocks.TALL_GRASS); else w.setIfAir(x, y, z, Blocks.GRASS.getDefaultState());
                            } else if (r2 < 0.05) {
                                w.setIfAir(x, y, z, Blocks.FERN.getDefaultState());
                            }
                            break;
                        }
                        boolean sand = top.isOf(Blocks.SAND) || top.isOf(Blocks.RED_SAND);
                        if (!sand) break;
                        // камыш на берегу оазиса: песок у самой воды (рядом колонка ниже уровня моря)
                        if (h == sea && s.oasis > 0.1 && r < 0.14 && shoreWater(samples, lx + 1, lz + 1)) {
                            int hh = 1 + rnd.nextInt(3);
                            for (int i = 0; i < hh; i++) w.setIfAir(x, y + i, z, Blocks.SUGAR_CANE.getDefaultState());
                            break;
                        }
                        if (r < 0.011) {
                            w.setIfAir(x, y, z, Blocks.DEAD_BUSH.getDefaultState());
                        } else if (r2 < 0.0035 && s.oasis < 0.05 && w.get(x + 1, y, z).isAir() && w.get(x - 1, y, z).isAir()
                                && w.get(x, y, z + 1).isAir() && w.get(x, y, z - 1).isAir()) {
                            int ch = 1 + rnd.nextInt(3);
                            for (int i = 0; i < ch; i++) w.setIfAir(x, y + i, z, Blocks.CACTUS.getDefaultState());
                        }
                    }
                }
            }
        }
    }

    private static boolean shoreWater(WarzoneTerrain.Sample[][] samples, int i, int j) {
        int sea = WarzoneTerrain.SEA_LEVEL;
        return samples[i + 1][j].height < sea || samples[i - 1][j].height < sea
                || samples[i][j + 1].height < sea || samples[i][j - 1].height < sea;
    }

    private static void tall(ChunkWriter w, int x, int y, int z, net.minecraft.block.Block b) {
        if (!w.get(x, y, z).isAir() || !w.get(x, y + 1, z).isAir()) return;
        w.set(x, y, z, b.getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.LOWER));
        w.set(x, y + 1, z, b.getDefaultState().with(TallPlantBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** Цветок пятна: каждое пятно (≈14×14) — один вид, чтобы поляны были «цветными лоскутами». */
    private static void flowerPatch(ChunkWriter w, int x, int y, int z, int kind) {
        switch (kind) {
            case 0 -> w.setIfAir(x, y, z, Blocks.DANDELION.getDefaultState());
            case 1 -> w.setIfAir(x, y, z, Blocks.POPPY.getDefaultState());
            case 2 -> w.setIfAir(x, y, z, Blocks.CORNFLOWER.getDefaultState());
            case 3 -> w.setIfAir(x, y, z, Blocks.AZURE_BLUET.getDefaultState());
            case 4 -> w.setIfAir(x, y, z, Blocks.OXEYE_DAISY.getDefaultState());
            case 5 -> tall(w, x, y, z, Blocks.LILAC);
            default -> tall(w, x, y, z, Blocks.PEONY);
        }
    }

    // ---- деревья и подлесок

    private static void plants(WarzoneFlora flora, ChunkWriter w) {
        for (WarzoneFlora.Plant p : flora.plantsForChunk(w.minX, w.minZ)) {
            WarzoneFlora.Shape shape = flora.shape(p);
            final int ox = p.x, oy = p.y, oz = p.z;
            shape.forEach((dx, dy, dz, code) -> place(w, ox + dx, oy + dy, oz + dz, code));
        }
    }

    private static void place(ChunkWriter w, int x, int y, int z, int code) {
        if (!w.contains(x, y, z)) return;
        BlockState existing = w.get(x, y, z);
        if (!WarzoneFlora.canPlace(code, categorize(existing))) return;
        w.set(x, y, z, stateFor(code, existing));
    }

    private static WarzoneFlora.Cat categorize(BlockState st) {
        if (st.isAir()) return WarzoneFlora.Cat.AIR;
        if (st.isOf(Blocks.WATER)) return WarzoneFlora.Cat.WATER;
        Block b = st.getBlock();
        if (b instanceof LeavesBlock) return WarzoneFlora.Cat.LEAF;
        if (st.isIn(BlockTags.LOGS) || st.isOf(Blocks.MANGROVE_ROOTS) || st.isOf(Blocks.MUDDY_MANGROVE_ROOTS)) return WarzoneFlora.Cat.LOG;
        if (b instanceof VineBlock || b instanceof PlantBlock) return WarzoneFlora.Cat.PLANT;
        return WarzoneFlora.Cat.SOLID;
    }

    private static final BlockState[] LEAF_STATES = new BlockState[WarzoneFlora.Wood.values().length];
    private static final BlockState[][] LOG_STATES = new BlockState[WarzoneFlora.Wood.values().length][3];
    private static final BlockState[] VINE_STATES = new BlockState[16];

    static {
        for (WarzoneFlora.Wood wd : WarzoneFlora.Wood.values()) {
            Block log;
            Block leaves;
            switch (wd) {
                case BIRCH -> { log = Blocks.BIRCH_LOG; leaves = Blocks.BIRCH_LEAVES; }
                case SPRUCE -> { log = Blocks.SPRUCE_LOG; leaves = Blocks.SPRUCE_LEAVES; }
                case DARK_OAK -> { log = Blocks.DARK_OAK_LOG; leaves = Blocks.DARK_OAK_LEAVES; }
                case ACACIA -> { log = Blocks.ACACIA_LOG; leaves = Blocks.ACACIA_LEAVES; }
                case JUNGLE -> { log = Blocks.JUNGLE_LOG; leaves = Blocks.JUNGLE_LEAVES; }
                case MANGROVE -> { log = Blocks.MANGROVE_LOG; leaves = Blocks.MANGROVE_LEAVES; }
                case DEAD_PALE -> { log = Blocks.STRIPPED_BIRCH_LOG; leaves = Blocks.ACACIA_LEAVES; }
                case DEAD_GREY -> { log = Blocks.ACACIA_LOG; leaves = Blocks.ACACIA_LEAVES; }
                default -> { log = Blocks.OAK_LOG; leaves = Blocks.OAK_LEAVES; }
            }
            int i = wd.ordinal();
            LEAF_STATES[i] = leaves.getDefaultState().with(LeavesBlock.PERSISTENT, true);
            LOG_STATES[i][WarzoneFlora.AXIS_Y] = log.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Y);
            LOG_STATES[i][WarzoneFlora.AXIS_X] = log.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.X);
            LOG_STATES[i][WarzoneFlora.AXIS_Z] = log.getDefaultState().with(PillarBlock.AXIS, Direction.Axis.Z);
        }
        for (int f = 0; f < 16; f++) {
            VINE_STATES[f] = Blocks.VINE.getDefaultState()
                    .with(VineBlock.NORTH, (f & WarzoneFlora.F_N) != 0)
                    .with(VineBlock.EAST, (f & WarzoneFlora.F_E) != 0)
                    .with(VineBlock.SOUTH, (f & WarzoneFlora.F_S) != 0)
                    .with(VineBlock.WEST, (f & WarzoneFlora.F_W) != 0);
        }
    }

    private static BlockState stateFor(int code, BlockState existing) {
        switch (WarzoneFlora.type(code)) {
            case WarzoneFlora.T_LOG:
                return LOG_STATES[WarzoneFlora.wood(code).ordinal()][WarzoneFlora.axis(code)];
            case WarzoneFlora.T_LEAF:
                return LEAF_STATES[WarzoneFlora.wood(code).ordinal()];
            case WarzoneFlora.T_VINE:
                return VINE_STATES[WarzoneFlora.faces(code)];
            case WarzoneFlora.T_ROOTS:
                return Blocks.MANGROVE_ROOTS.getDefaultState().with(Properties.WATERLOGGED, existing.isOf(Blocks.WATER));
            default:
                return Blocks.MOSS_CARPET.getDefaultState();
        }
    }

    /** Валун из замшелого булыжника. */
    private static void boulder(ChunkWriter w, int x, int groundY, int z, Random rnd) {
        int rad = 1 + rnd.nextInt(2);
        for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
                for (int dy = 0; dy <= rad; dy++) {
                    if (dx * dx + dz * dz + dy * dy * 2 > rad * rad + 1) continue;
                    BlockState st = rnd.nextInt(3) == 0 ? Blocks.MOSSY_COBBLESTONE.getDefaultState() : Blocks.COBBLESTONE.getDefaultState();
                    w.setIfAir(x + dx, groundY + 1 + dy, z + dz, st);
                }
            }
        }
    }

    // ------------------------------------------------------------------ посадочная площадка

    /** Площадка высадки игрока: круг из каменного кирпича, по краю — полированный андезит, по углам — фонари. */
    static void landingPad(WarzoneTerrain t, ChunkWriter w) {
        int cx = t.landingX();
        int cz = t.landingZ();
        int y = WarzoneTerrain.LANDING_Y;
        if (Math.abs(w.minX + 8 - cx) > 24 || Math.abs(w.minZ + 8 - cz) > 24) return;

        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                int x = cx + dx;
                int z = cz + dz;
                if (d > 6.6) continue;
                BlockState floor;
                long h = WarzoneNoise.hash(t.seed + 41, x, z) & 7;
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) floor = Blocks.CHISELED_STONE_BRICKS.getDefaultState();
                else if (d > 5.5) floor = Blocks.POLISHED_ANDESITE.getDefaultState();
                else if (h == 0) floor = Blocks.MOSSY_STONE_BRICKS.getDefaultState();
                else if (h == 1) floor = Blocks.CRACKED_STONE_BRICKS.getDefaultState();
                else floor = Blocks.STONE_BRICKS.getDefaultState();
                w.set(x, y, z, floor);
                for (int up = 1; up <= 6; up++) w.set(x, y + up, z, AIR);
            }
        }
        // Фонарные столбы по диагоналям
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                int x = cx + sx * 4;
                int z = cz + sz * 4;
                w.set(x, y + 1, z, Blocks.COBBLESTONE_WALL.getDefaultState());
                w.set(x, y + 2, z, Blocks.COBBLESTONE_WALL.getDefaultState());
                w.set(x, y + 3, z, Blocks.COBBLESTONE_WALL.getDefaultState());
                w.set(x, y + 4, z, Blocks.LANTERN.getDefaultState());
            }
        }
    }
}
