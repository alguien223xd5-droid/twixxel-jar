package com.twixxel.stalker.item;

import com.twixxel.stalker.event.Net;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** "Configurar stalkers": abre el menu con una barra por stalker (minutos) y las opciones de calma / oscuridad. */
public class ConfigItem extends Item {
    public ConfigItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) Net.openConfig(sp);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
}
