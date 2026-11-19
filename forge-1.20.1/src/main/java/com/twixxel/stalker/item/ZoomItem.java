package com.twixxel.stalker.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Palo ZOOM: clic derecho = zoom de catalejo (FOV 20), otra vez = se quita. Sin sonido. */
public class ZoomItem extends Item {
    public ZoomItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack st = player.getItemInHand(hand);
        if (level.isClientSide) com.twixxel.stalker.client.ZoomClient.toggle();
        return InteractionResultHolder.sidedSuccess(st, level.isClientSide);
    }
}
