package com.bobincraft.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.Thickness;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/**
 * Ледяная сосулька: аналог капельника (pointed dripstone), но из прозрачного льда.
 * Использует те же свойства (направление острия и толщина), но без логики капель/падения:
 * держится на опоре сверху (острие вниз) или снизу (острие вверх) и ломается, если опору убрали.
 */
public class IcicleBlock extends Block {
    public static final DirectionProperty VERTICAL_DIRECTION = Properties.VERTICAL_DIRECTION;
    public static final EnumProperty<Thickness> THICKNESS = Properties.THICKNESS;

    private static final VoxelShape TIP_MERGE = Block.createCuboidShape(5, 0, 5, 11, 16, 11);
    private static final VoxelShape TIP_UP = Block.createCuboidShape(5, 0, 5, 11, 11, 11);
    private static final VoxelShape TIP_DOWN = Block.createCuboidShape(5, 5, 5, 11, 16, 11);
    private static final VoxelShape FRUSTUM = Block.createCuboidShape(4, 0, 4, 12, 16, 12);
    private static final VoxelShape MIDDLE = Block.createCuboidShape(3, 0, 3, 13, 16, 13);
    private static final VoxelShape BASE = Block.createCuboidShape(2, 0, 2, 14, 16, 14);

    public IcicleBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState()
                .with(VERTICAL_DIRECTION, Direction.DOWN)
                .with(THICKNESS, Thickness.TIP));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(VERTICAL_DIRECTION, THICKNESS);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        Thickness t = state.get(THICKNESS);
        return switch (t) {
            case TIP_MERGE -> TIP_MERGE;
            case TIP -> state.get(VERTICAL_DIRECTION) == Direction.DOWN ? TIP_DOWN : TIP_UP;
            case FRUSTUM -> FRUSTUM;
            case MIDDLE -> MIDDLE;
            default -> BASE;
        };
    }

    @Override
    public boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
        Direction tip = state.get(VERTICAL_DIRECTION);
        return isSupported(world, pos, tip);
    }

    /** Опора с противоположной от острия стороны: целый блок либо другая сосулька с тем же направлением. */
    private boolean isSupported(WorldView world, BlockPos pos, Direction tip) {
        BlockPos supportPos = pos.offset(tip.getOpposite());
        BlockState support = world.getBlockState(supportPos);
        if (support.isOf(this)) {
            return support.get(VERTICAL_DIRECTION) == tip;
        }
        return support.isSideSolidFullSquare(world, supportPos, tip);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        for (Direction tip : new Direction[]{Direction.DOWN, Direction.UP}) {
            if (isSupported(ctx.getWorld(), ctx.getBlockPos(), tip)) {
                return getDefaultState().with(VERTICAL_DIRECTION, tip).with(THICKNESS, Thickness.TIP);
            }
        }
        return null;
    }

    @Override
    public BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        Direction tip = state.get(VERTICAL_DIRECTION);
        if (direction == tip.getOpposite() && !canPlaceAt(state, world, pos)) {
            return Blocks.AIR.getDefaultState();
        }
        return state;
    }
}
