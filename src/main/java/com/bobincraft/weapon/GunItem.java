package com.bobincraft.weapon;

import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class GunItem extends Item {
    private final GunType gun;

    public GunItem(Settings settings, GunType gun) {
        super(settings);
        this.gun = gun;
    }

    public GunType getGunType() {
        return gun;
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        for (String line : gun.tooltip) {
            tooltip.add(Text.literal(line));
        }
        tooltip.add(Text.literal("§eЛКМ: §fвыстрел §7| §eR: §fперезарядка"));
    }
}
