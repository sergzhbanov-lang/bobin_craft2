package com.bobincraft.weapon;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class AK47Item extends Item {
    public AK47Item(Settings settings) {
        super(settings);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        tooltip.add(Text.literal("§7Штурмовой автомат для обычного и тактического вида."));
        tooltip.add(Text.literal("§eЛКМ: §fОдиночный выстрел"));
        tooltip.add(Text.literal("§eЗажатие ЛКМ: §fСтрельба очередью"));
        tooltip.add(Text.literal("§eR: §fПерезарядка магазина"));
        tooltip.add(Text.literal("§cКрасный луч: §fЛазерный целеуказатель"));
    }
}
