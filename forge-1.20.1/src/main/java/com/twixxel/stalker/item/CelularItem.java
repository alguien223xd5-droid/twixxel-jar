package com.twixxel.stalker.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** Celular: 3 lingotes de hierro. Usarlo abre el menu de videos; otra vez lo apaga. Todo del lado del cliente. */
public class CelularItem extends Item {
    public CelularItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) com.twixxel.stalker.client.CelularClient.toggle();
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide) com.twixxel.stalker.client.CelularClient.toggle();
        return InteractionResult.sidedSuccess(ctx.getLevel().isClientSide);
    }
}
