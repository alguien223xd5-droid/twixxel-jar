package com.twixxel.stalker.block;

import com.twixxel.stalker.event.BedHide;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/** Armario de 2 bloques de alto (como una puerta). Tocalo sin agacharte y te metes adentro. */
public class ArmarioBlock extends Block {
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public ArmarioBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0f, 3.0f).sound(SoundType.WOOD).ignitedByLava());
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(OPEN, false).setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(HALF, OPEN, FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockPos pos = ctx.getClickedPos();
        Level l = ctx.getLevel();
        if (pos.getY() >= l.getMaxBuildHeight() - 1 || !l.getBlockState(pos.above()).canBeReplaced(ctx)) return null;
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite()).setValue(HALF, DoubleBlockHalf.LOWER).setValue(OPEN, false);
    }

    @Override
    public void setPlacedBy(Level l, BlockPos pos, BlockState st, LivingEntity by, ItemStack stack) {
        l.setBlock(pos.above(), st.setValue(HALF, DoubleBlockHalf.UPPER), 3);
    }

    @Override
    public BlockState updateShape(BlockState st, Direction dir, BlockState nb, LevelAccessor lv, BlockPos pos, BlockPos npos) {
        DoubleBlockHalf h = st.getValue(HALF);
        if (dir.getAxis() == Direction.Axis.Y && (h == DoubleBlockHalf.LOWER) == (dir == Direction.UP)) {
            return nb.is(this) && nb.getValue(HALF) != h ? st.setValue(FACING, nb.getValue(FACING)).setValue(OPEN, nb.getValue(OPEN)) : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(st, dir, nb, lv, pos, npos);
    }

    @Override
    public void playerWillDestroy(Level l, BlockPos pos, BlockState st, Player pl) {
        if (!l.isClientSide && pl.isCreative() && st.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos below = pos.below();
            BlockState bs = l.getBlockState(below);
            if (bs.is(st.getBlock()) && bs.getValue(HALF) == DoubleBlockHalf.LOWER) {
                l.setBlock(below, Blocks.AIR.defaultBlockState(), 35);
                l.levelEvent(pl, 2001, below, Block.getId(bs));
            }
        }
        super.playerWillDestroy(l, pos, st, pl);
    }

    @Override
    public InteractionResult use(BlockState st, Level l, BlockPos pos, Player pl, InteractionHand hand, BlockHitResult hit) {
        if (pl.isShiftKeyDown()) return InteractionResult.PASS;     // agachado: deja poner bloques contra el armario
        if (l.isClientSide) return InteractionResult.SUCCESS;
        if (hand == InteractionHand.MAIN_HAND && pl instanceof ServerPlayer sp && l instanceof ServerLevel sl) {
            BlockPos base = st.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
            BedHide.enterArmario(sp, sl, base);
        }
        return InteractionResult.CONSUME;
    }
}
