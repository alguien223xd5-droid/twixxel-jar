package com.twixxel.stalker.item;

import com.twixxel.stalker.event.Pets;
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

/** Mascot / PET 2 / Mascot3 guardada como item: al usarla sobre un bloque o al aire vuelve a salir tu mascota. */
public class PetItem extends Item {
    private final String petId;

    public PetItem(String petId) {
        super(new Item.Properties().stacksTo(1));
        this.petId = petId;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (ctx.getPlayer() instanceof ServerPlayer p) return Pets.place(p, ctx.getItemInHand(), petId, ctx.getClickedPos(), ctx.getClickedFace());
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack st = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(st);
        if (player instanceof ServerPlayer p) {
            BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.NONE);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(player.getEyePosition()) <= 8)
                Pets.place(p, st, petId, hit.getBlockPos(), hit.getDirection());
            else Pets.place(p, st, petId, null, null);
        }
        return InteractionResultHolder.sidedSuccess(st, false);
    }
}
