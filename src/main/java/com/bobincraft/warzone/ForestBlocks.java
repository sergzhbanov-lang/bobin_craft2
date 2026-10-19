package com.bobincraft.warzone;

import com.bobincraft.BobinCraft;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.Waterloggable;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/** Ordinary static blocks for the Warzone floor, trunks, fallen timber and undergrowth. */
public final class ForestBlocks {
    public static final Block DARK_FOREST_SOIL = new Block(soil(MapColor.SPRUCE_BROWN));
    public static final Block PINE_NEEDLE_GROUND = new Block(soil(MapColor.BROWN));
    public static final Block MOSSY_FOREST_SOIL = new Block(soil(MapColor.DARK_GREEN));
    public static final Block FOREST_LITTER = new Block(soil(MapColor.BROWN));
    public static final PillarBlock WEATHERED_BARK = new PillarBlock(wood().strength(2.0f));
    public static final PillarBlock DARK_PINE_WOOD = new PillarBlock(wood().strength(2.0f));
    public static final PillarBlock ROTTEN_WOOD = new PillarBlock(wood().strength(1.0f));
    public static final Block MOSS_COVERED_ROCK = new Block(FabricBlockSettings.create()
            .mapColor(MapColor.STONE_GRAY).strength(1.5f, 6.0f).requiresTool().sounds(BlockSoundGroup.STONE));
    public static final Block RIVER_STONE = new Block(rock(MapColor.STONE_GRAY));
    public static final Block LAYERED_SLATE = new Block(rock(MapColor.DEEPSLATE_GRAY));
    public static final Block LICHEN_ROCK = new Block(rock(MapColor.PALE_GREEN));
    public static final Block RIVER_PEBBLES = new PebbleBlock();
    public static final Block RIVER_REEDS = new RiverReedBlock();
    public static final Block DRY_UNDERGROWTH = new ForestPlantBlock(MapColor.BROWN,
            Block.createCuboidShape(2, 0, 2, 14, 12, 14));
    public static final Block FOREST_GRASS = new ForestPlantBlock(MapColor.DARK_GREEN,
            Block.createCuboidShape(2, 0, 2, 14, 13, 14));
    public static final Block PALE_SEDGE = new ForestPlantBlock(MapColor.PALE_GREEN,
            Block.createCuboidShape(2, 0, 2, 14, 14, 14));
    public static final Block LOW_FOREST_BUSH = new ForestPlantBlock(MapColor.DARK_GREEN,
            Block.createCuboidShape(1, 0, 1, 15, 13, 15));
    public static final Block NEEDLE_UNDERGROWTH = new ForestPlantBlock(MapColor.DARK_GREEN,
            Block.createCuboidShape(1, 0, 1, 15, 7, 15));

    private ForestBlocks() { }

    private static FabricBlockSettings soil(MapColor color) {
        return FabricBlockSettings.create().mapColor(color).strength(0.6f).sounds(BlockSoundGroup.ROOTED_DIRT);
    }

    private static FabricBlockSettings wood() {
        return FabricBlockSettings.create().mapColor(MapColor.SPRUCE_BROWN).sounds(BlockSoundGroup.WOOD);
    }

    private static FabricBlockSettings rock(MapColor color) {
        return FabricBlockSettings.create().mapColor(color).strength(1.6f, 6.0f)
                .requiresTool().sounds(BlockSoundGroup.STONE);
    }

    public static void register() {
        register("dark_forest_soil", DARK_FOREST_SOIL);
        register("pine_needle_ground", PINE_NEEDLE_GROUND);
        register("mossy_forest_soil", MOSSY_FOREST_SOIL);
        register("forest_litter", FOREST_LITTER);
        register("weathered_bark", WEATHERED_BARK);
        register("dark_pine_wood", DARK_PINE_WOOD);
        register("rotten_wood", ROTTEN_WOOD);
        register("moss_covered_rock", MOSS_COVERED_ROCK);
        register("river_stone", RIVER_STONE);
        register("layered_slate", LAYERED_SLATE);
        register("lichen_rock", LICHEN_ROCK);
        register("river_pebbles", RIVER_PEBBLES);
        register("river_reeds", RIVER_REEDS);
        register("dry_undergrowth", DRY_UNDERGROWTH);
        register("forest_grass", FOREST_GRASS);
        register("pale_sedge", PALE_SEDGE);
        register("low_forest_bush", LOW_FOREST_BUSH);
        register("needle_undergrowth", NEEDLE_UNDERGROWTH);
    }

    private static void register(String name, Block block) {
        Identifier id = new Identifier(BobinCraft.MOD_ID, name);
        Registry.register(Registries.BLOCK, id, block);
        BlockItem item = Registry.register(Registries.ITEM, id, new BlockItem(block, new FabricItemSettings()));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> entries.add(item));
    }

    /** PlantBlock only reacts to support changes: no random ticks, block entities or movement collision. */
    private static final class ForestPlantBlock extends PlantBlock {
        private final VoxelShape shape;

        private ForestPlantBlock(MapColor color, VoxelShape shape) {
            super(FabricBlockSettings.create().mapColor(color).noCollision().nonOpaque()
                    .breakInstantly().sounds(BlockSoundGroup.GRASS).pistonBehavior(PistonBehavior.DESTROY));
            this.shape = shape;
        }

        @Override
        protected boolean canPlantOnTop(BlockState floor, BlockView world, BlockPos pos) {
            return floor.isIn(BlockTags.DIRT) || floor.isOf(Blocks.SAND)
                    || floor.isOf(Blocks.RED_SAND) || floor.isOf(Blocks.GRAVEL)
                    || floor.isOf(Blocks.MUD) || floor.isOf(Blocks.CLAY);
        }

        @Override
        public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return shape;
        }
    }

    private static final class RiverReedBlock extends TallPlantBlock {
        private RiverReedBlock() {
            super(FabricBlockSettings.create().mapColor(MapColor.PALE_GREEN).noCollision().nonOpaque()
                    .breakInstantly().sounds(BlockSoundGroup.GRASS).pistonBehavior(PistonBehavior.DESTROY));
        }

        @Override
        protected boolean canPlantOnTop(BlockState floor, BlockView world, BlockPos pos) {
            return floor.isIn(BlockTags.DIRT) || floor.isOf(Blocks.MUD) || floor.isOf(Blocks.CLAY)
                    || floor.isOf(Blocks.SAND) || floor.isOf(Blocks.GRAVEL);
        }
    }

    /** Low stones have matching collision, outline and model geometry, including when submerged. */
    public static final class PebbleBlock extends Block implements Waterloggable {
        public static final IntProperty VARIANT = IntProperty.of("variant", 0, 2);
        private static final VoxelShape[] SHAPES = {
                net.minecraft.util.shape.VoxelShapes.union(Block.createCuboidShape(2, 0, 3, 8, 4, 9),
                        Block.createCuboidShape(9, 0, 9, 14, 3, 14)),
                net.minecraft.util.shape.VoxelShapes.union(Block.createCuboidShape(1, 0, 7, 8, 3, 14),
                        Block.createCuboidShape(9, 0, 2, 15, 5, 8)),
                net.minecraft.util.shape.VoxelShapes.union(Block.createCuboidShape(3, 0, 2, 12, 5, 10),
                        Block.createCuboidShape(10, 0, 11, 15, 2, 15))
        };

        private PebbleBlock() {
            super(rock(MapColor.STONE_GRAY).nonOpaque().strength(0.8f, 3.0f));
            setDefaultState(getStateManager().getDefaultState().with(VARIANT, 0).with(Properties.WATERLOGGED, false));
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            builder.add(VARIANT, Properties.WATERLOGGED);
        }

        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            return getDefaultState().with(VARIANT, Math.floorMod(ctx.getBlockPos().hashCode(), 3))
                    .with(Properties.WATERLOGGED, ctx.getWorld().getFluidState(ctx.getBlockPos()).isOf(Fluids.WATER));
        }

        @Override
        public FluidState getFluidState(BlockState state) {
            return state.get(Properties.WATERLOGGED) ? Fluids.WATER.getStill(false) : super.getFluidState(state);
        }

        @Override
        public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
            return world.getBlockState(pos.down()).isSideSolidFullSquare(world, pos.down(), Direction.UP);
        }

        @Override
        public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor,
                                                    WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            if (state.get(Properties.WATERLOGGED))
                world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
            if (direction == Direction.DOWN && !canPlaceAt(state, world, pos))
                return state.get(Properties.WATERLOGGED) ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState();
            return super.getStateForNeighborUpdate(state, direction, neighbor, world, pos, neighborPos);
        }

        @Override
        public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return SHAPES[state.get(VARIANT)];
        }
    }
}
