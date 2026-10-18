package com.bobincraft.weapon;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SniperRifleItem extends Item {
    public SniperRifleItem(Settings settings) {
        super(settings);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.literal("§7Тяжелая снайперская винтовка с бронебойными патронами."));
        tooltip.add(Text.literal("§eЛКМ: §fМощный выстрел (урон: §c50.0§f)"));
        tooltip.add(Text.literal("§eX: §fСнайперский прицел (+10% к макс. зуму камеры)"));
        tooltip.add(Text.literal("§eR: §fПерезарядка магазина"));
        tooltip.add(Text.literal("§bОсобенность: §fПробивает окна любой толщины (1 выстрел — трещины, 2 — разрушение)"));
    }
}
