package com.twixxel.stalker.item;

import com.twixxel.stalker.event.Verity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Peluche de Verity: al usarlo sobre un bloque (o al aire) sale la bolita amarilla que te sigue. */
public class VerityItem extends Item {
    public VerityItem() {
        super(new Item.Properties().stacksTo(16));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (ctx.getPlayer() instanceof ServerPlayer p) return Verity.place(p, ctx.getItemInHand(), ctx.getClickedPos(), ctx.getClickedFace());
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack st = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(st);
        if (player instanceof ServerPlayer p) {
            BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(player.getEyePosition()) <= 6)
                Verity.place(p, st, hit.getBlockPos(), hit.getDirection());
            else Verity.place(p, st, null, null);
        }
        return InteractionResultHolder.sidedSuccess(st, false);
    }
}
