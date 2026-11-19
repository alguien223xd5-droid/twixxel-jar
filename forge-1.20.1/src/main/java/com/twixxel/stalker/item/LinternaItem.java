package com.twixxel.stalker.item;

import com.twixxel.stalker.event.Linterna;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Linterna (20 usos). Usarla la prende 3 s; prendida, aturde a los stalkers que te persiguen. */
public class LinternaItem extends Item {
    public static final int USES = 20;
    public final boolean on;

    public LinternaItem(boolean on) {
        super(new Item.Properties().durability(USES));
        this.on = on;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack st = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) Linterna.use(sp, hand, st);
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }
}
