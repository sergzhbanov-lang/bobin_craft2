package com.bobincraft.raid;

import com.bobincraft.BobinCraft;
import com.bobincraft.raid.guard.WarzoneGuardEntity;
import com.bobincraft.weapon.GunType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerProfession;
import net.minecraft.village.VillagerType;

/** Called only by first-time hub construction. Native entity saves own stock and equipment. */
public final class HubNpcs {
    private HubNpcs() { }

    public static void spawn(ServerWorld world) {
        VillagerEntity merchant = EntityType.VILLAGER.create(world);
        if (merchant == null) throw new IllegalStateException("Unable to create hub quartermaster");
        place(merchant, HubLayout.MERCHANT);
        merchant.setVillagerData(new VillagerData(VillagerType.PLAINS, VillagerProfession.WEAPONSMITH, 5));
        merchant.setAiDisabled(true);
        merchant.setInvulnerable(true);
        merchant.setPersistent();
        merchant.setCustomName(Text.translatable("entity.bobin_craft.quartermaster"));
        merchant.setCustomNameVisible(true);
        merchant.addCommandTag("bobin_hub_merchant");
        TradeOfferList offers = merchant.getOffers();
        offers.clear();
        // Emeralds already occur in Warzone loot. All exchanges use Minecraft's atomic
        // merchant handler; no custom packet grants or inventory snapshots are involved.
        offers.add(new TradeOffer(new ItemStack(Items.EMERALD, 8), new ItemStack(BobinCraft.AK47), 16, 1, 0.0f));
        offers.add(new TradeOffer(new ItemStack(Items.EMERALD, 16), new ItemStack(BobinCraft.SNIPER_RIFLE), 8, 1, 0.0f));
        offers.add(new TradeOffer(new ItemStack(Items.EMERALD, 5), new ItemStack(GunType.BREACHER.item), 16, 1, 0.0f));
        offers.add(new TradeOffer(new ItemStack(Items.EMERALD, 6), new ItemStack(Items.IRON_CHESTPLATE), 32, 1, 0.0f));
        offers.add(new TradeOffer(new ItemStack(Items.EMERALD, 3), new ItemStack(Items.IRON_HELMET), 32, 1, 0.0f));
        offers.add(new TradeOffer(new ItemStack(Items.EMERALD, 2), new ItemStack(Items.GOLDEN_APPLE), 64, 1, 0.0f));
        offers.add(new TradeOffer(new ItemStack(Items.EMERALD), new ItemStack(Items.BREAD, 8), 64, 1, 0.0f));
        offers.add(new TradeOffer(new ItemStack(Items.EMERALD), new ItemStack(Items.TORCH, 16), 64, 1, 0.0f));
        offers.add(new TradeOffer(new ItemStack(Items.IRON_INGOT, 8), new ItemStack(Items.EMERALD), 64, 1, 0.0f));
        if (!world.spawnEntity(merchant)) throw new IllegalStateException("Unable to spawn hub quartermaster");

        for (BlockPos post : HubLayout.GUARDS) {
            WarzoneGuardEntity guard = WarzoneEncounterManager.GUARD.create(world);
            if (guard == null) throw new IllegalStateException("Unable to create hub guard");
            place(guard, post);
            guard.assignHubGuard(post);
            guard.setCustomName(Text.translatable("entity.bobin_craft.hub_guard"));
            if (!world.spawnEntity(guard)) throw new IllegalStateException("Unable to spawn hub guard");
        }
    }

    private static void place(net.minecraft.entity.Entity entity, BlockPos pos) {
        entity.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 180, 0);
    }
}
