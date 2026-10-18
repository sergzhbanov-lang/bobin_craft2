package com.bobincraft.raid;

import com.bobincraft.BobinCraft;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.FurnaceBlock;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.SlabType;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Constructs a fresh forest camp once. RaidManager must never call this for a saved/customized hub. */
public final class HubBuilder {
    // Keep these anchors stable: old saved bunkers, table validation and player storage depend on them.
    public static final BlockPos SPAWN = new BlockPos(0, 71, 3);
    public static final BlockPos TABLE = new BlockPos(0, 71, -2);
    public static final BlockPos STORAGE = new BlockPos(-6, 71, -3);
    public static final BlockPos STARTER_SUPPLIES = new BlockPos(-6, 71, 3);
    private static final int GROUND = HubLayout.FLOOR_Y;
    private static final int FEET = HubLayout.PLAYER_Y;
    // No neighbor cascades during bulk construction. States that need connections set them explicitly.
    private static final int FLAGS = Block.NOTIFY_LISTENERS;

    private HubBuilder() { }

    public static void build(ServerWorld world) {
        HubLayout.validate();
        earthworks(world);
        paths(world);
        perimeter(world);
        containment(world);
        for (int x : new int[]{-41, 41}) for (int z : new int[]{-41, 41}) tower(world, x, z);
        central(world);
        sleeping(world);
        kitchen(world);
        deployment(world);
        trading(world);
        cargoYard(world);
        checkpoint(world);
        signalMast(world);
        interiorVegetation(world);
        fallenTrees(world);
        ridgePost(world);
        verify(world);
        HubNpcs.spawn(world);
        world.setSpawnPos(SPAWN, 180.0f);
    }

    private static void earthworks(ServerWorld world) {
        for (HubLayout.Mound mound : HubLayout.MOUNDS) {
            for (int x = mound.x() - mound.radius(); x <= mound.x() + mound.radius(); x++) {
                for (int z = mound.z() - mound.radius(); z <= mound.z() + mound.radius(); z++) {
                    if (mound.rise(x, z) == 0) continue;
                    int top = HubLayout.groundHeight(x, z);
                    column(world, x, GROUND + 1, top - 1, z, Blocks.DIRT.getDefaultState());
                    int wear = Math.floorMod(x * 17 + z * 31, 11);
                    set(world, x, top, z, (wear == 0 ? Blocks.COARSE_DIRT
                            : wear < 3 ? Blocks.MOSS_BLOCK : Blocks.GRASS_BLOCK).getDefaultState());
                }
            }
        }
    }

    private static void paths(ServerWorld world) {
        for (int x = -49; x <= 49; x++) {
            for (int z = -49; z <= 49; z++) {
                if (!HubLayout.isPath(x, z)) continue;
                int wear = Math.floorMod(x * 31 + z * 17 + x * z, 13);
                BlockState ground = wear < 2 ? Blocks.MUD.getDefaultState()
                        : wear < 5 ? Blocks.ROOTED_DIRT.getDefaultState() : Blocks.COARSE_DIRT.getDefaultState();
                set(world, x, GROUND, z, ground);
            }
        }
        // Lighting marks route junctions rather than flooding the forest.
        for (int[] point : new int[][]{{-4, 10}, {4, 34}, {-24, 12}, {22, 8}, {-21, -18}}) {
            postLight(world, point[0], point[1]);
        }
    }

    private static void perimeter(ServerWorld world) {
        int radius = HubLayout.HUB_RADIUS;
        for (int x = -radius; x <= radius; x++) {
            wallColumn(world, x, -radius);
            wallColumn(world, x, radius);
        }
        for (int z = -radius + 1; z < radius; z++) {
            wallColumn(world, -radius, z);
            wallColumn(world, radius, z);
        }
        // The only entrance is a clearly different reinforced, closed checkpoint gate.
        // A closed physical gate and the server boundary keep players off the finite forest edge.
        for (int x = -4; x <= 4; x++) {
            for (int y = FEET; y <= 75; y++) {
                set(world, x, y, radius, (x == -4 || x == 4 || y == 75)
                        ? HubContent.REINFORCED_WOOD.getDefaultState() : HubContent.PALISADE.getDefaultState());
            }
            set(world, x, 76, radius, HubContent.REINFORCED_WOOD.getDefaultState());
        }
        for (int x : new int[]{-5, 5}) {
            set(world, x, 75, 51, HubContent.REINFORCED_WOOD.getDefaultState());
            set(world, x, 74, 51, Blocks.LANTERN.getDefaultState().with(Properties.HANGING, true));
        }
        for (int x : new int[]{-7, 7}) {
            set(world, x, 72, 51, facing(HubContent.WARNING_SIGN, Direction.NORTH));
        }
        // Exterior braces remain below the cap and cannot become a staircase over it.
        for (int n = -48; n <= 48; n += 8) {
            for (int y = FEET; y <= 73; y++) {
                set(world, n, y, -53, HubContent.REINFORCED_WOOD.getDefaultState());
                set(world, n, y, 53, HubContent.REINFORCED_WOOD.getDefaultState());
                set(world, -53, y, n, HubContent.REINFORCED_WOOD.getDefaultState());
                set(world, 53, y, n, HubContent.REINFORCED_WOOD.getDefaultState());
            }
        }
    }

    private static void wallColumn(ServerWorld world, int x, int z) {
        boolean post = Math.floorMod(x + z, 7) == 0;
        for (int y = FEET; y <= 75; y++) {
            set(world, x, y, z, (post ? HubContent.REINFORCED_WOOD : HubContent.PALISADE).getDefaultState());
        }
        BlockState cap = Blocks.SPRUCE_FENCE.getDefaultState()
                .with(Properties.NORTH, Math.abs(x) == HubLayout.HUB_RADIUS)
                .with(Properties.SOUTH, Math.abs(x) == HubLayout.HUB_RADIUS)
                .with(Properties.EAST, Math.abs(z) == HubLayout.HUB_RADIUS)
                .with(Properties.WEST, Math.abs(z) == HubLayout.HUB_RADIUS);
        set(world, x, 76, z, cap);
    }

    /** Closed physical shell: cover, trees, digging and projectiles cannot bypass the timber wall. */
    private static void containment(ServerWorld world) {
        int r = HubLayout.BARRIER_RADIUS;
        int top = world.getTopY() - 1;
        BlockState barrier = Blocks.BARRIER.getDefaultState();
        for (int n = -r; n <= r; n++) {
            column(world, n, HubLayout.BARRIER_BASE_Y, top, -r, barrier);
            column(world, n, HubLayout.BARRIER_BASE_Y, top, r, barrier);
            if (Math.abs(n) == r) continue;
            column(world, -r, HubLayout.BARRIER_BASE_Y, top, n, barrier);
            column(world, r, HubLayout.BARRIER_BASE_Y, top, n, barrier);
        }
        // The island's existing bedrock is the floor; an invisible ceiling closes the flight route.
        for (int x = -r + 1; x < r; x++) for (int z = -r + 1; z < r; z++) {
            set(world, x, top, z, barrier);
        }
    }

    private static void tower(ServerWorld world, int cx, int cz) {
        // Seven blocks of clear ground separate each platform from the palisade.
        for (int dx : new int[]{-3, 3}) for (int dz : new int[]{-3, 3}) {
            column(world, cx + dx, FEET, 80, cz + dz, Blocks.STRIPPED_SPRUCE_LOG.getDefaultState());
        }
        for (int x = cx - 3; x <= cx + 3; x++) {
            for (int z = cz - 3; z <= cz + 3; z++) {
                if (x != cx - 2 || z != cz) set(world, x, 76, z, Blocks.SPRUCE_PLANKS.getDefaultState());
                if (Math.abs(x - cx) == 3 || Math.abs(z - cz) == 3) {
                    set(world, x, 77, z, Blocks.SPRUCE_FENCE.getDefaultState()
                            .with(Properties.NORTH, Math.abs(x - cx) == 3)
                            .with(Properties.SOUTH, Math.abs(x - cx) == 3)
                            .with(Properties.EAST, Math.abs(z - cz) == 3)
                            .with(Properties.WEST, Math.abs(z - cz) == 3));
                    set(world, x, 80, z, HubContent.REINFORCED_WOOD.getDefaultState());
                } else set(world, x, 80, z, HubContent.CAMOUFLAGE_NET.getDefaultState());
            }
        }
        column(world, cx - 3, FEET, 76, cz, Blocks.STRIPPED_SPRUCE_LOG.getDefaultState());
        for (int y = FEET; y <= 76; y++) {
            set(world, cx - 2, y, cz, Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, Direction.EAST));
        }
        set(world, cx + 2, 77, cz + 2, Blocks.LANTERN.getDefaultState());
        set(world, cx + 2, 77, cz - 2, facing(HubContent.RADIO, Direction.SOUTH));
    }

    private static void central(ServerWorld world) {
        // The social fire sits beside the main route so the route remains clear for multiplayer traffic.
        for (int x = 5; x <= 11; x++) for (int z = 15; z <= 21; z++) {
            set(world, x, GROUND, z, Blocks.COARSE_DIRT.getDefaultState());
        }
        set(world, 8, FEET, 18, Blocks.CAMPFIRE.getDefaultState());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx != 0 || dz != 0) set(world, 8 + dx, GROUND, 18 + dz, Blocks.COBBLESTONE.getDefaultState());
        }
        bench(world, 8, 14, 5, Direction.SOUTH);
        bench(world, 8, 22, 5, Direction.NORTH);
        bench(world, 4, 18, 3, Direction.EAST);
        table(world, -9, 16, 3);
        barrel(world, -13, FEET, 16);
        crate(world, -12, FEET, 19);
        tent(world, -11, 26, 4, 5);
        crate(world, -13, FEET, 24);
        barrel(world, -9, FEET, 24);
        tent(world, 13, 28, 4, 4);
        crate(world, 11, FEET, 26);
        crate(world, 12, FEET + 1, 26);
        postLight(world, 19, 21);
    }

    private static void sleeping(ServerWorld world) {
        sleepingTent(world, -34, -29, Direction.NORTH);
        sleepingTent(world, -32, -13, Direction.NORTH);
        sleepingTent(world, -39, 2, Direction.NORTH);
        crate(world, -25, FEET, -31);
        crate(world, -25, FEET, -30);
        set(world, -25, FEET + 1, -31, HubContent.AMMO_CRATE.getDefaultState());
        postLight(world, -22, 2);
    }

    private static void sleepingTent(ServerWorld world, int cx, int cz, Direction direction) {
        tent(world, cx, cz, 4, 5);
        bed(world, cx - 2, cz, direction);
        bed(world, cx + 2, cz, direction);
        chest(world, new BlockPos(cx - 2, FEET, cz + 3), Direction.SOUTH);
        crate(world, cx + 2, FEET, cz + 3);
        set(world, cx - 3, FEET, cz - 3, Blocks.LANTERN.getDefaultState());
    }

    private static void kitchen(ServerWorld world) {
        floor(world, -43, 23, -24, 37, Blocks.SPRUCE_PLANKS.getDefaultState());
        canopy(world, -43, 23, -24, 37, 76);
        for (int x = -42; x <= -36; x++) {
            set(world, x, FEET, 24, Blocks.SMOOTH_STONE.getDefaultState());
        }
        set(world, -41, FEET, 24, Blocks.SMOKER.getDefaultState().with(FurnaceBlock.FACING, Direction.SOUTH));
        set(world, -39, FEET, 24, Blocks.FURNACE.getDefaultState().with(FurnaceBlock.FACING, Direction.SOUTH));
        set(world, -37, FEET + 1, 24, Blocks.CAULDRON.getDefaultState());
        set(world, -37, FEET, 25, Blocks.CRAFTING_TABLE.getDefaultState());
        chest(world, new BlockPos(-42, FEET, 27), Direction.EAST);
        stock(world, new BlockPos(-42, FEET, 27), new ItemStack(Items.BREAD, 32),
                new ItemStack(Items.BAKED_POTATO, 24), new ItemStack(Items.COOKED_BEEF, 16), new ItemStack(Items.COAL, 16));
        barrel(world, -42, FEET, 30);
        barrel(world, -42, FEET, 31);
        table(world, -30, 29, 5);
        table(world, -30, 34, 5);
        bench(world, -30, 27, 5, Direction.SOUTH);
        bench(world, -30, 31, 5, Direction.NORTH);
        bench(world, -30, 32, 5, Direction.SOUTH);
        bench(world, -30, 36, 5, Direction.NORTH);
        for (int x = -43; x <= -39; x++) {
            set(world, x, FEET, 36, Blocks.SPRUCE_LOG.getDefaultState().with(Properties.AXIS, Direction.Axis.X));
        }
        set(world, -41, FEET + 1, 36, HubContent.SUPPLY_CRATE.getDefaultState());
        hangingLight(world, -35, 75, 24);
        hangingLight(world, -26, 75, 35);
    }

    private static void deployment(ServerWorld world) {
        floor(world, -12, -12, 12, 4, Blocks.COARSE_DIRT.getDefaultState());
        canopy(world, -11, -12, 11, 1, 76);
        // Large briefing tabletop: the center remains the existing functional deployment block.
        world.setBlockState(TABLE, HubContent.MAP_TABLE.getDefaultState(), FLAGS);
        // A broad tabletop surrounds the single server-validated interaction anchor.
        for (int x = -1; x <= 1; x++) for (int z = -3; z <= -1; z++) {
            if (x != 0 || z != -2) set(world, x, FEET, z, Blocks.SPRUCE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
        }
        chest(world, STORAGE, Direction.EAST);
        chest(world, STARTER_SUPPLIES, Direction.EAST);
        stock(world, STARTER_SUPPLIES, new ItemStack(BobinCraft.AK47), new ItemStack(Items.BREAD, 16),
                new ItemStack(Items.LEATHER_CHESTPLATE), new ItemStack(Items.LEATHER_LEGGINGS),
                new ItemStack(Items.LEATHER_BOOTS), new ItemStack(Items.TORCH, 16), new ItemStack(Items.EMERALD, 16));
        for (int x : new int[]{-8, -4, 4, 8}) {
            set(world, x, FEET, -10, facing(HubContent.WEAPON_RACK, Direction.SOUTH));
            set(world, x + 1, FEET, -10, HubContent.AMMO_CRATE.getDefaultState());
        }
        set(world, -9, FEET, -6, facing(HubContent.EQUIPMENT_LOCKER, Direction.EAST));
        set(world, -9, FEET, -5, facing(HubContent.EQUIPMENT_LOCKER, Direction.EAST));
        set(world, 8, FEET, -6, Blocks.CRAFTING_TABLE.getDefaultState());
        set(world, 8, FEET, -4, Blocks.ANVIL.getDefaultState());
        set(world, 8, FEET, -2, Blocks.GRINDSTONE.getDefaultState());
        for (int x = -4; x <= 4; x++) {
            set(world, x, FEET, -13, HubContent.REINFORCED_WOOD.getDefaultState());
            set(world, x, FEET + 1, -13, HubContent.REINFORCED_WOOD.getDefaultState());
        }
        set(world, 0, 73, -13, facing(HubContent.WARNING_SIGN, Direction.SOUTH));
        set(world, 3, FEET + 1, -13, facing(HubContent.RADIO, Direction.SOUTH));
        for (int x : new int[]{-13, 13}) for (int z = -10; z <= -7; z++) {
            set(world, x, FEET, z, HubContent.SAND_BAGS.getDefaultState());
        }
        hangingLight(world, 0, 75, -10);
    }

    private static void trading(ServerWorld world) {
        floor(world, 25, -29, 42, -10, Blocks.SPRUCE_PLANKS.getDefaultState());
        canopy(world, 25, -29, 42, -10, 76);
        for (int x = 25; x <= 42; x++) for (int y = FEET; y <= 74; y++) {
            set(world, x, y, -29, HubContent.REINFORCED_WOOD.getDefaultState());
        }
        for (int z = -28; z <= -10; z++) for (int y = FEET; y <= 74; y++) {
            set(world, 42, y, z, HubContent.REINFORCED_WOOD.getDefaultState());
            if (z < -19 || z > -13) set(world, 25, y, z, HubContent.REINFORCED_WOOD.getDefaultState());
        }
        for (int x = 29; x <= 36; x++) set(world, x, FEET, -18, HubContent.REINFORCED_WOOD.getDefaultState());
        for (int x : new int[]{29, 33, 37}) set(world, x, FEET, -28, facing(HubContent.WEAPON_RACK, Direction.SOUTH));
        for (int z : new int[]{-26, -23, -20}) set(world, 41, FEET, z, facing(HubContent.EQUIPMENT_LOCKER, Direction.WEST));
        crate(world, 27, FEET, -26);
        crate(world, 27, FEET + 1, -26);
        set(world, 28, FEET, -26, HubContent.AMMO_CRATE.getDefaultState());
        set(world, 36, FEET + 1, -18, facing(HubContent.RADIO, Direction.SOUTH));
        for (int x : new int[]{26, 40}) set(world, x, FEET, -11, HubContent.SAND_BAGS.getDefaultState());
        hangingLight(world, 33, 75, -17);
        set(world, 25, 73, -14, facing(HubContent.WARNING_SIGN, Direction.WEST));
    }

    private static void cargoYard(ServerWorld world) {
        // A loading shelter links the quartermaster to the central road, leaving its aisle open.
        floor(world, 28, -3, 40, 5, Blocks.COARSE_DIRT.getDefaultState());
        canopy(world, 28, -3, 40, 5, 75);
        for (int x = 29; x <= 31; x++) {
            crate(world, x, FEET, -2);
            set(world, x, FEET + 1, -2, HubContent.AMMO_CRATE.getDefaultState());
        }
        crate(world, 29, FEET + 1, -2);
        barrel(world, 39, FEET, -2);
        barrel(world, 39, FEET, 0);
        crate(world, 38, FEET, 3);
        crate(world, 39, FEET, 3);
        set(world, 38, FEET + 1, 3, HubContent.AMMO_CRATE.getDefaultState());
        hangingLight(world, 34, 74, -2);
    }

    private static void signalMast(ServerWorld world) {
        column(world, 14, FEET, 84, -14, Blocks.IRON_BARS.getDefaultState());
        for (int x = 12; x <= 16; x++) {
            set(world, x, 81, -14, Blocks.IRON_BARS.getDefaultState()
                    .with(Properties.EAST, x < 16).with(Properties.WEST, x > 12));
        }
        set(world, 15, 80, -14, Blocks.GREEN_WOOL.getDefaultState());
        set(world, 16, 80, -14, Blocks.GREEN_WOOL.getDefaultState());
        set(world, 15, 79, -14, Blocks.WHITE_WOOL.getDefaultState());
        set(world, 16, 79, -14, Blocks.GREEN_WOOL.getDefaultState());
        set(world, 13, FEET, -14, facing(HubContent.RADIO, Direction.SOUTH));
        set(world, 15, FEET, -14, HubContent.SAND_BAGS.getDefaultState());
    }

    private static void checkpoint(ServerWorld world) {
        for (int cx : new int[]{-13, 13}) {
            floor(world, cx - 3, 40, cx + 3, 47, Blocks.COARSE_DIRT.getDefaultState());
            canopy(world, cx - 3, 40, cx + 3, 47, 75);
            for (int z = 41; z <= 46; z++) {
                set(world, cx + (cx < 0 ? -2 : 2), FEET, z, HubContent.SAND_BAGS.getDefaultState());
            }
            table(world, cx, 42, 3);
            set(world, cx, FEET + 1, 42, facing(HubContent.RADIO, Direction.NORTH));
            hangingLight(world, cx, 74, 45);
        }
        for (int x = -15; x <= 15; x++) {
            if (Math.abs(x) >= 5 && Math.abs(x) <= 7) continue;
            if (Math.abs(x) < 3) continue;
            set(world, x, FEET, 49, HubContent.SAND_BAGS.getDefaultState());
        }
        for (int x : new int[]{-6, 6}) set(world, x, FEET, 37, facing(HubContent.WARNING_SIGN, Direction.NORTH));
        crate(world, -16, FEET, 38);
        set(world, 16, FEET, 38, HubContent.AMMO_CRATE.getDefaultState());
    }

    private static void interiorVegetation(ServerWorld world) {
        // Irregular remnants of the original forest break up the clearing, away from roads and walls.
        for (int[] tree : new int[][]{{-9, -29, 9}, {-4, -42, 8}, {33, 25, 9}, {39, 15, 7}}) {
            int cx = tree[0], cz = tree[1], height = tree[2];
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                if (dx * dx + dz * dz > 16 || HubLayout.isReserved(cx + dx, cz + dz)) continue;
                set(world, cx + dx, HubLayout.groundHeight(cx + dx, cz + dz), cz + dz, ((dx + dz) & 1) == 0
                        ? Blocks.PODZOL.getDefaultState() : Blocks.MOSS_BLOCK.getDefaultState());
            }
            int ground = HubLayout.groundHeight(cx, cz);
            BlockState leaves = Blocks.SPRUCE_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
            for (int y = 3; y <= height; y++) {
                int radius = Math.min(3, 1 + (height - y) / 3);
                if (y == height) radius = 0;
                for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) == radius && Math.abs(dz) == radius && radius > 0) continue;
                    set(world, cx + dx, ground + y, cz + dz, leaves);
                }
            }
            column(world, cx, ground + 1, ground + height - 1, cz, Blocks.SPRUCE_LOG.getDefaultState());
        }
        BlockState shrub = Blocks.OAK_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
        for (int[] patch : new int[][]{{-16, -25}, {21, 26}, {35, 34}, {-44, 15}, {18, -23}}) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 && dz != 0) continue;
                int x = patch[0] + dx, z = patch[1] + dz;
                if (!HubLayout.isReserved(x, z)) set(world, x, HubLayout.groundHeight(x, z) + 1, z, shrub);
            }
        }
    }

    private static void fallenTrees(ServerWorld world) {
        BlockState foliage = Blocks.SPRUCE_LEAVES.getDefaultState().with(LeavesBlock.PERSISTENT, true);
        for (HubLayout.FallenTree tree : HubLayout.FALLEN_TREES) {
            BlockState log = Blocks.SPRUCE_LOG.getDefaultState()
                    .with(Properties.AXIS, tree.alongX() ? Direction.Axis.X : Direction.Axis.Z);
            for (int n = 0; n < tree.length(); n++) {
                int x = tree.x() + (tree.alongX() ? n : 0);
                int z = tree.z() + (tree.alongX() ? 0 : n);
                set(world, x, HubLayout.groundHeight(x, z) + 1, z, log);
                if (n < tree.length() - 3) continue;
                for (int side : new int[]{-1, 1}) {
                    int px = x + (tree.alongX() ? 0 : side);
                    int pz = z + (tree.alongX() ? side : 0);
                    if (!HubLayout.isReserved(px, pz)) {
                        set(world, px, HubLayout.groundHeight(px, pz) + 1, pz, foliage);
                    }
                }
            }
            set(world, tree.x(), HubLayout.groundHeight(tree.x(), tree.z()) + 2, tree.z(),
                    Blocks.SPRUCE_LOG.getDefaultState());
        }
    }

    private static void ridgePost(ServerWorld world) {
        for (int x = 6; x <= 11; x++) {
            set(world, x, HubLayout.groundHeight(x, -35) + 1, -35, HubContent.SAND_BAGS.getDefaultState());
        }
        for (int z = -34; z <= -32; z++) {
            set(world, 6, HubLayout.groundHeight(6, z) + 1, z, HubContent.SAND_BAGS.getDefaultState());
        }
        int y = HubLayout.groundHeight(9, -34) + 1;
        barrel(world, 9, y, -34);
        set(world, 9, y + 1, -34, facing(HubContent.RADIO, Direction.SOUTH));
        set(world, 11, HubLayout.groundHeight(11, -34) + 1, -34, HubContent.AMMO_CRATE.getDefaultState());
    }

    private static void tent(ServerWorld world, int cx, int cz, int halfWidth, int halfDepth) {
        floor(world, cx - halfWidth, cz - halfDepth, cx + halfWidth, cz + halfDepth, Blocks.COARSE_DIRT.getDefaultState());
        for (int x = cx - halfWidth; x <= cx + halfWidth; x++) {
            int rise = Math.min(2, (halfWidth - Math.abs(x - cx)) / 2);
            for (int z = cz - halfDepth; z <= cz + halfDepth; z++) {
                set(world, x, 74 + rise, z, HubContent.TENT_FABRIC.getDefaultState());
                if (Math.abs(x - cx) == halfWidth) {
                    for (int y = FEET; y <= 73; y++) set(world, x, y, z, HubContent.TENT_FABRIC.getDefaultState());
                }
                if (z == cz - halfDepth) {
                    for (int y = FEET; y < 74 + rise; y++) set(world, x, y, z, HubContent.TENT_FABRIC.getDefaultState());
                }
            }
        }
        // The south face stays open across its full width for visibility and accessible beds/storage.
        for (int x : new int[]{cx - halfWidth, cx + halfWidth}) {
            column(world, x, FEET, 74, cz + halfDepth, Blocks.SPRUCE_FENCE.getDefaultState());
        }
        for (int z = cz - halfDepth; z <= cz + halfDepth; z++) {
            set(world, cx, 75 + Math.min(2, halfWidth / 2), z, Blocks.SPRUCE_SLAB.getDefaultState());
        }
        for (int x : new int[]{cx - halfWidth - 1, cx + halfWidth + 1}) {
            for (int z : new int[]{cz - halfDepth - 1, cz + halfDepth + 1}) {
                if (!HubLayout.isPath(x, z)) set(world, x, FEET, z, Blocks.SPRUCE_FENCE.getDefaultState());
            }
        }
    }

    private static void canopy(ServerWorld world, int x1, int z1, int x2, int z2, int roofY) {
        for (int x : new int[]{x1, x2}) for (int z : new int[]{z1, z2}) {
            column(world, x, FEET, roofY, z, Blocks.STRIPPED_SPRUCE_LOG.getDefaultState());
        }
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            set(world, x, roofY, z, (x == x1 || x == x2 || z == z1 || z == z2)
                    ? HubContent.REINFORCED_WOOD.getDefaultState() : HubContent.CAMOUFLAGE_NET.getDefaultState());
        }
    }

    private static void bed(ServerWorld world, int x, int z, Direction direction) {
        BlockState state = Blocks.GREEN_BED.getDefaultState().with(BedBlock.FACING, direction);
        set(world, x, FEET, z, state.with(BedBlock.PART, BedPart.FOOT));
        set(world, x + direction.getOffsetX(), FEET, z + direction.getOffsetZ(), state.with(BedBlock.PART, BedPart.HEAD));
    }

    private static void table(ServerWorld world, int cx, int z, int length) {
        for (int x = cx - length / 2; x <= cx + length / 2; x++) {
            set(world, x, FEET, z, Blocks.SPRUCE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.TOP));
        }
    }

    private static void bench(ServerWorld world, int cx, int cz, int length, Direction facing) {
        for (int n = -length / 2; n <= length / 2; n++) {
            int x = cx + (facing.getAxis() == Direction.Axis.Z ? n : 0);
            int z = cz + (facing.getAxis() == Direction.Axis.X ? n : 0);
            set(world, x, FEET, z, Blocks.SPRUCE_STAIRS.getDefaultState().with(StairsBlock.FACING, facing.getOpposite()));
        }
    }

    private static void floor(ServerWorld world, int x1, int z1, int x2, int z2, BlockState state) {
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) set(world, x, GROUND, z, state);
    }

    private static void hangingLight(ServerWorld world, int x, int y, int z) {
        // Thin camouflage netting cannot support a lantern: a small solid rafter can.
        set(world, x, y + 1, z, HubContent.REINFORCED_WOOD.getDefaultState());
        set(world, x, y, z, Blocks.LANTERN.getDefaultState().with(Properties.HANGING, true));
    }

    private static void postLight(ServerWorld world, int x, int z) {
        column(world, x, FEET, FEET + 1, z, Blocks.SPRUCE_FENCE.getDefaultState());
        set(world, x, FEET + 2, z, Blocks.LANTERN.getDefaultState());
    }

    private static void column(ServerWorld world, int x, int y1, int y2, int z, BlockState state) {
        for (int y = y1; y <= y2; y++) set(world, x, y, z, state);
    }

    private static void chest(ServerWorld world, BlockPos pos, Direction direction) {
        world.setBlockState(pos, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, direction), FLAGS);
    }

    private static void barrel(ServerWorld world, int x, int y, int z) {
        set(world, x, y, z, Blocks.BARREL.getDefaultState());
    }

    private static void crate(ServerWorld world, int x, int y, int z) {
        set(world, x, y, z, HubContent.SUPPLY_CRATE.getDefaultState());
    }

    private static void stock(ServerWorld world, BlockPos pos, ItemStack... stacks) {
        if (world.getBlockEntity(pos) instanceof Inventory inventory) {
            for (int i = 0; i < Math.min(stacks.length, inventory.size()); i++) inventory.setStack(i, stacks[i]);
            inventory.markDirty();
        }
    }

    private static BlockState facing(Block block, Direction direction) {
        return block.getDefaultState().with(Properties.HORIZONTAL_FACING, direction);
    }

    private static void set(ServerWorld world, int x, int y, int z, BlockState state) {
        world.setBlockState(new BlockPos(x, y, z), state, FLAGS);
    }

    private static void verify(ServerWorld world) {
        int r = HubLayout.HUB_RADIUS;
        for (int n = -r; n <= r; n++) for (int y = FEET; y <= 75; y++) {
            for (BlockPos point : new BlockPos[]{new BlockPos(n, y, -r), new BlockPos(n, y, r),
                    new BlockPos(-r, y, n), new BlockPos(r, y, n)}) {
                if (!world.getBlockState(point).isFullCube(world, point)) {
                    throw new IllegalStateException("Incomplete hub perimeter at " + point);
                }
            }
        }
        int barrierRadius = HubLayout.BARRIER_RADIUS;
        int top = world.getTopY() - 1;
        for (int n = -barrierRadius; n <= barrierRadius; n++) {
            for (int y = HubLayout.BARRIER_BASE_Y; y <= top; y++) {
                for (BlockPos point : new BlockPos[]{new BlockPos(n, y, -barrierRadius), new BlockPos(n, y, barrierRadius),
                        new BlockPos(-barrierRadius, y, n), new BlockPos(barrierRadius, y, n)}) {
                    if (!world.getBlockState(point).isOf(Blocks.BARRIER)) {
                        throw new IllegalStateException("Incomplete hub containment at " + point);
                    }
                }
            }
        }
        for (int x = -barrierRadius; x <= barrierRadius; x++) for (int z = -barrierRadius; z <= barrierRadius; z++) {
            if (!world.getBlockState(new BlockPos(x, top, z)).isOf(Blocks.BARRIER)) {
                throw new IllegalStateException("Incomplete hub containment ceiling");
            }
        }
        if (!world.getBlockState(TABLE).isOf(HubContent.MAP_TABLE)) throw new IllegalStateException("Missing deployment table");
        if (!(world.getBlockEntity(STORAGE) instanceof Inventory)
                || !(world.getBlockEntity(STARTER_SUPPLIES) instanceof Inventory)) throw new IllegalStateException("Missing persistent storage");
        verifyAnchor(world, SPAWN);
        verifyAnchor(world, HubLayout.MERCHANT);
        for (BlockPos anchor : HubLayout.GUARDS) verifyAnchor(world, anchor);
    }

    private static void verifyAnchor(ServerWorld world, BlockPos anchor) {
        if (!world.getBlockState(anchor).getCollisionShape(world, anchor).isEmpty()
                || !world.getBlockState(anchor.up()).getCollisionShape(world, anchor.up()).isEmpty()
                || !world.getBlockState(anchor.down()).isFullCube(world, anchor.down())) {
            throw new IllegalStateException("Unsafe hub spawn anchor at " + anchor);
        }
    }
}
