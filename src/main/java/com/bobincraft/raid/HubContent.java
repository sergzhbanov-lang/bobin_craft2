package com.bobincraft.raid;

import com.bobincraft.BobinCraft;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/** Static original-art camp props. Vanilla chests/barrels hold all persistent inventory. */
public final class HubContent {
    public static final Block MAP_TABLE = new MapTableBlock();
    public static final Block SUPPLY_CRATE = new Block(settings().strength(2.5f).sounds(BlockSoundGroup.WOOD));
    public static final Block METAL_FLOOR = new Block(settings());
    public static final Block BUNKER_WALL = new Block(settings());
    public static final Block EQUIPMENT_PANEL = new Block(settings().luminance(state -> 4));
    public static final Block TENT_FABRIC = new Block(woodSettings().strength(0.8f).sounds(BlockSoundGroup.WOOL));
    public static final Block REINFORCED_WOOD = new Block(woodSettings().strength(3.5f, 8.0f));
    public static final Block PALISADE = new Block(woodSettings().strength(3.5f, 8.0f));
    public static final Block CAMOUFLAGE_NET = new StaticPropBlock(
            woodSettings().strength(0.3f).sounds(BlockSoundGroup.WOOL).nonOpaque().noCollision(),
            Block.createCuboidShape(0, 15, 0, 16, 16, 16));
    public static final Block SAND_BAGS = new StaticPropBlock(
            woodSettings().strength(1.5f, 5.0f).sounds(BlockSoundGroup.GRAVEL).nonOpaque(),
            Block.createCuboidShape(0, 0, 0, 16, 14, 16));
    public static final Block AMMO_CRATE = new StaticPropBlock(
            woodSettings().strength(2.5f).nonOpaque(), Block.createCuboidShape(1, 0, 1, 15, 12, 15));
    public static final Block EQUIPMENT_LOCKER = new FacingPropBlock(
            propMetal(), Block.createCuboidShape(1, 0, 1, 15, 16, 15));
    public static final Block WEAPON_RACK = new FacingPropBlock(woodSettings().nonOpaque(), VoxelShapes.union(
            Block.createCuboidShape(1, 0, 2, 15, 3, 16),
            Block.createCuboidShape(1, 0, 13, 15, 16, 16),
            Block.createCuboidShape(1, 0, 2, 3, 16, 13),
            Block.createCuboidShape(13, 0, 2, 15, 16, 13)));
    public static final Block RADIO = new FacingPropBlock(propMetal(), VoxelShapes.union(
            Block.createCuboidShape(2, 0, 3, 14, 8, 13),
            Block.createCuboidShape(11, 8, 10, 12, 16, 11)));
    public static final Block WARNING_SIGN = new FacingPropBlock(woodSettings().nonOpaque(), VoxelShapes.union(
            Block.createCuboidShape(1, 8, 6, 15, 15, 8),
            Block.createCuboidShape(7, 0, 6, 9, 8, 8)));

    private HubContent() { }

    private static FabricBlockSettings settings() {
        return FabricBlockSettings.create().mapColor(MapColor.GRAY).strength(5.0f, 1200.0f)
                .sounds(BlockSoundGroup.METAL);
    }

    private static FabricBlockSettings woodSettings() {
        return FabricBlockSettings.create().mapColor(MapColor.SPRUCE_BROWN).strength(2.5f, 5.0f)
                .sounds(BlockSoundGroup.WOOD);
    }

    private static FabricBlockSettings propMetal() {
        return FabricBlockSettings.create().mapColor(MapColor.DARK_GREEN).strength(3.5f, 8.0f)
                .sounds(BlockSoundGroup.METAL).nonOpaque();
    }

    public static void register() {
        register("map_table", MAP_TABLE);
        register("supply_crate", SUPPLY_CRATE);
        register("metal_floor", METAL_FLOOR);
        register("bunker_wall", BUNKER_WALL);
        register("equipment_panel", EQUIPMENT_PANEL);
        register("tent_fabric", TENT_FABRIC);
        register("reinforced_wood", REINFORCED_WOOD);
        register("palisade", PALISADE);
        register("camouflage_net", CAMOUFLAGE_NET);
        register("sand_bags", SAND_BAGS);
        register("ammo_crate", AMMO_CRATE);
        register("equipment_locker", EQUIPMENT_LOCKER);
        register("weapon_rack", WEAPON_RACK);
        register("radio", RADIO);
        register("warning_sign", WARNING_SIGN);
        Registry.register(Registries.CHUNK_GENERATOR, new Identifier(BobinCraft.MOD_ID, "hub"), HubChunkGenerator.CODEC);
    }

    private static class StaticPropBlock extends Block {
        private final VoxelShape shape;

        private StaticPropBlock(Settings settings, VoxelShape shape) {
            super(settings);
            this.shape = shape;
        }

        @Override
        public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return shape;
        }
    }

    /** Facing is stored in the blockstate; baked models and collisions rotate together. */
    private static final class FacingPropBlock extends HorizontalFacingBlock {
        public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;
        private final VoxelShape[] shapes = new VoxelShape[4];

        private FacingPropBlock(Settings settings, VoxelShape shape) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
            shapes[Direction.NORTH.getHorizontal()] = shape;
            VoxelShape current = shape;
            for (Direction direction : new Direction[]{Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                VoxelShape[] rotated = {VoxelShapes.empty()};
                current.forEachBox((x0, y0, z0, x1, y1, z1) -> rotated[0] = VoxelShapes.union(rotated[0],
                        VoxelShapes.cuboid(1 - z1, y0, x0, 1 - z0, y1, x1)));
                current = rotated[0].simplify();
                shapes[direction.getHorizontal()] = current;
            }
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Override
        public BlockState getPlacementState(ItemPlacementContext context) {
            return getDefaultState().with(FACING, context.getHorizontalPlayerFacing().getOpposite());
        }

        @Override
        public BlockState rotate(BlockState state, BlockRotation rotation) {
            return state.with(FACING, rotation.rotate(state.get(FACING)));
        }

        @Override
        public BlockState mirror(BlockState state, BlockMirror mirror) {
            return rotate(state, mirror.getRotation(state.get(FACING)));
        }

        @Override
        public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return shapes[state.get(FACING).getHorizontal()];
        }
    }

    private static void register(String name, Block block) {
        Identifier id = new Identifier(BobinCraft.MOD_ID, name);
        Registry.register(Registries.BLOCK, id, block);
        BlockItem item = Registry.register(Registries.ITEM, id, new BlockItem(block, new FabricItemSettings()));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(item));
    }

    private static final class MapTableBlock extends Block {
        private static final VoxelShape SHAPE = VoxelShapes.union(
                Block.createCuboidShape(0, 13, 0, 16, 16, 16),
                Block.createCuboidShape(1, 0, 1, 4, 13, 4),
                Block.createCuboidShape(12, 0, 1, 15, 13, 4),
                Block.createCuboidShape(1, 0, 12, 4, 13, 15),
                Block.createCuboidShape(12, 0, 12, 15, 13, 15));

        private MapTableBlock() { super(settings().nonOpaque()); }

        @Override
        public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return SHAPE;
        }

        @Override
        public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                  Hand hand, BlockHitResult hit) {
            if (player instanceof ServerPlayerEntity serverPlayer) RaidManager.openMap(serverPlayer, pos);
            return ActionResult.success(world.isClient);
        }
    }
}
