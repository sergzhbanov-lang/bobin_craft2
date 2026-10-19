package com.bobincraft.smoke;

import com.bobincraft.BobinCraft;
import com.bobincraft.raid.*;
import com.bobincraft.raid.guard.WarzoneGuardEntity;
import com.bobincraft.warzone.WarzoneDimension;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.TradeOffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Temporary integration harness. This class is deliberately outside the delivered project. */
public final class HubSmoke implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("bobin-smoke");
    private MinecraftServer started;
    private int ticks;
    private boolean done;
    private static int assertions;
    private static final BlockPos PLAYER_BUILD = new BlockPos(20, 71, 35);

    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            started = server;
            for (BlockPos anchor : HubLayout.GUARDS) server.getOverworld().getChunk(anchor);
            server.getOverworld().getChunk(HubLayout.MERCHANT);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (started != server || done || ++ticks < 40) return;
            done = true;
            try {
                checkWorld(server);
                LOG.info("BOBIN_SMOKE_PASS assertions={}", assertions);
            } catch (Throwable failure) {
                LOG.error("BOBIN_SMOKE_FAIL assertions=" + assertions, failure);
            } finally {
                server.stop(false);
            }
        });
    }

    private static void checkWorld(MinecraftServer server) {
        try {
            DimensionProfileChecks.run(server);
            if (Boolean.getBoolean("bobin.smoke.legacy")) NaturalWorldChecks.runLegacy(server);
            else NaturalWorldChecks.run(server);
        } catch (Exception failure) {
            throw new AssertionError("Natural generation runtime checks failed", failure);
        }
        HubForestChecks.run();
        LOG.info("BOBIN_SMOKE_FOREST_PASS assertions={} deterministic chunk seams, finite clearance, terrain and heightmap samples", HubForestChecks.assertions);
        ServerWorld hub = server.getOverworld();
        check(hub.getChunkManager().getChunkGenerator() instanceof HubChunkGenerator, "managed forest hub generator");
        check(RaidPersistentState.get(server).hubInitialized, "one-time hub flag");
        check(RaidPersistentState.get(server).hubDesign == 2, "new forest layout marker");
        HubLayout.validate();
        check(HubLayout.Zone.values().length == 6, "six functional service zones");
        check(hub.getBlockState(HubBuilder.TABLE).isOf(HubContent.MAP_TABLE), "functional map table anchor");
        check(hub.getBlockEntity(HubBuilder.STORAGE) instanceof Inventory, "persistent personal supply chest");
        Inventory starter = (Inventory) hub.getBlockEntity(HubBuilder.STARTER_SUPPLIES);
        check(starter != null, "starter chest inventory");
        anchor(hub, HubBuilder.SPAWN);
        anchor(hub, HubLayout.MERCHANT);
        for (BlockPos guard : HubLayout.GUARDS) anchor(hub, guard);
        int radius = HubLayout.HUB_RADIUS;
        for (int n = -radius; n <= radius; n++) for (int y = 71; y <= 75; y++) {
            for (BlockPos point : new BlockPos[]{new BlockPos(n, y, -radius), new BlockPos(n, y, radius),
                    new BlockPos(-radius, y, n), new BlockPos(radius, y, n)}) {
                check(hub.getBlockState(point).isFullCube(hub, point), "continuous solid perimeter " + point);
            }
        }
        int barrierRadius = HubLayout.BARRIER_RADIUS;
        int barrierTop = hub.getTopY() - 1;
        BlockPos ceilingSample = new BlockPos(0, barrierTop, 0);
        check(hub.getBlockState(ceilingSample).getOpacity(hub, ceilingSample) == 0,
                "invisible containment ceiling passes skylight");
        for (int n = -barrierRadius; n <= barrierRadius; n++) {
            for (int y = HubLayout.BARRIER_BASE_Y; y <= barrierTop; y++) {
                for (BlockPos point : new BlockPos[]{new BlockPos(n, y, -barrierRadius), new BlockPos(n, y, barrierRadius),
                        new BlockPos(-barrierRadius, y, n), new BlockPos(barrierRadius, y, n)}) {
                    check(hub.getBlockState(point).isOf(Blocks.BARRIER), "closed physical perimeter " + point);
                }
            }
        }
        for (int x = -barrierRadius; x <= barrierRadius; x++) for (int z = -barrierRadius; z <= barrierRadius; z++) {
            check(hub.getBlockState(new BlockPos(x, barrierTop, z)).isOf(Blocks.BARRIER), "closed physical ceiling");
            check(hub.getBlockState(new BlockPos(x, HubLayout.BARRIER_BASE_Y, z)).isOf(
                    Math.abs(x) == barrierRadius || Math.abs(z) == barrierRadius ? Blocks.BARRIER : Blocks.BEDROCK),
                    "unbroken containment floor");
        }
        for (HubLayout.Mound mound : HubLayout.MOUNDS) {
            BlockPos top = new BlockPos(mound.x(), HubLayout.groundHeight(mound.x(), mound.z()), mound.z());
            check(top.getY() > HubLayout.FLOOR_Y && hub.getBlockState(top).isFullCube(hub, top), "supported interior ridge " + top);
        }
        for (HubLayout.FallenTree tree : HubLayout.FALLEN_TREES) {
            for (int n = 0; n < tree.length(); n++) {
                int x = tree.x() + (tree.alongX() ? n : 0);
                int z = tree.z() + (tree.alongX() ? 0 : n);
                BlockPos trunk = new BlockPos(x, HubLayout.groundHeight(x, z) + 1, z);
                check(hub.getBlockState(trunk).isOf(Blocks.SPRUCE_LOG), "fallen-tree obstacle " + trunk);
                check(hub.getBlockState(trunk.down()).isFullCube(hub, trunk.down()), "fallen-tree support " + trunk);
            }
        }
        List<WarzoneGuardEntity> guards = new ArrayList<>();
        List<VillagerEntity> merchants = new ArrayList<>();
        for (Entity entity : hub.iterateEntities()) {
            if (entity instanceof WarzoneGuardEntity guard && guard.isHubGuard()) guards.add(guard);
            if (entity instanceof VillagerEntity villager && entity.getCommandTags().contains("bobin_hub_merchant")) merchants.add(villager);
        }
        check(guards.size() == HubLayout.GUARDS.size(), "exactly seven persistent friendly guards, got " + guards.size());
        check(merchants.size() == 1, "one quartermaster, got " + merchants.size());
        VillagerEntity merchant = merchants.get(0);
        check(merchant.getOffers().size() == 9, "nine usable native merchant trades");
        for (TradeOffer offer : merchant.getOffers()) {
            check(!offer.getOriginalFirstBuyItem().isEmpty() && !offer.getSellItem().isEmpty(), "trade inputs/output registered");
            check(Registries.ITEM.containsId(Registries.ITEM.getId(offer.getSellItem().getItem())), "existing trade output item");
        }
        for (WarzoneGuardEntity guard : guards) {
            check(guard.isAiDisabled() && guard.isInvulnerable() && guard.getTarget() == null, "friendly guard safety");
            check(guard.getMainHandStack().isOf(BobinCraft.AK47), "guard holds actual mod rifle");
            NbtCompound saved = guard.writeNbt(new NbtCompound());
            WarzoneGuardEntity restored = WarzoneEncounterManager.GUARD.create(hub);
            restored.readNbt(saved);
            check(restored.isHubGuard() && restored.isAiDisabled(), "hub guard NBT round-trip retains friendly identity");
            check(restored.getUuid().equals(guard.getUuid()), "hub guard NBT UUID stable");
        }
        testEnemyPersistence(server);
        if (Boolean.getBoolean("bobin.smoke.readOnly")) {
            LOG.info("BOBIN_SMOKE_READ_ONLY_PASS: hub services and NPC persistence checked without changing inventories, trades or player blocks");
            return;
        }
        ItemStack marker = starter.getStack(26);
        if (marker.isOf(Items.PAPER) && marker.hasNbt() && marker.getNbt().getBoolean("BobinSmoke")) {
            NbtCompound state = marker.getNbt();
            check(starter.getStack(0).isEmpty(), "starter items not refilled after restart");
            check(hub.getBlockState(PLAYER_BUILD).isOf(Blocks.DIAMOND_BLOCK), "player placed block survives restart");
            check(merchant.getUuid().equals(state.getUuid("Merchant")), "merchant survives restart without duplication");
            check(merchant.getOffers().get(0).getUses() == 1, "merchant stock usage survives restart");
            NbtList savedGuards = state.getList("Guards", NbtElement.STRING_TYPE);
            check(savedGuards.size() == guards.size(), "saved guard count survives restart");
            for (int i = 0; i < savedGuards.size(); i++) {
                UUID uuid = UUID.fromString(savedGuards.getString(i));
                check(guards.stream().anyMatch(g -> g.getUuid().equals(uuid)), "guard UUID survives restart " + uuid);
            }
            LOG.info("BOBIN_SMOKE_RESTART_PASS: customized blocks, consumed starter supplies, NPC UUIDs and used trade preserved");
        } else {
            check(starter.getStack(0).isOf(BobinCraft.AK47), "initial starter rifle");
            check(starter.getStack(6).isOf(Items.EMERALD) && starter.getStack(6).getCount() == 16, "initial merchant currency");
            marker = new ItemStack(Items.PAPER);
            NbtCompound state = marker.getOrCreateNbt();
            state.putBoolean("BobinSmoke", true);
            state.putUuid("Merchant", merchant.getUuid());
            NbtList savedGuards = new NbtList();
            for (WarzoneGuardEntity guard : guards) savedGuards.add(NbtString.of(guard.getUuidAsString()));
            state.put("Guards", savedGuards);
            starter.setStack(26, marker);
            starter.setStack(0, ItemStack.EMPTY);
            starter.markDirty();
            merchant.getOffers().get(0).use();
            hub.setBlockState(PLAYER_BUILD, Blocks.DIAMOND_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
            LOG.info("BOBIN_SMOKE_INITIAL_PASS: generated new hub; persisted customized block, consumed rifle and used trade for restart verification");
        }
        server.save(false, true, true);
    }

    private static void testEnemyPersistence(MinecraftServer server) {
        ServerWorld warzone = server.getWorld(WarzoneDimension.WORLD);
        check(warzone != null, "Warzone dimension exists");
        WarzoneGuardEntity enemy = WarzoneEncounterManager.GUARD.create(warzone);
        NbtCompound saved = enemy.writeNbt(new NbtCompound());
        saved.putBoolean("BobinGuardConfigured", true);
        saved.putBoolean("BobinHubGuard", false);
        saved.putString("BobinFaction", "ASH_RAIDERS");
        saved.putInt("BobinVariant", 2);
        saved.putString("BobinWeapon", "BREACHER");
        saved.putInt("BobinDanger", 1);
        saved.putString("BobinRole", "SOLDIER");
        saved.putInt("BobinSite", 42);
        saved.putLong("BobinPost", new BlockPos(80, 71, -80).asLong());
        saved.putLong("BobinTerritory", new BlockPos(80, 71, -80).asLong());
        saved.putInt("BobinRadius", 48);
        NbtCompound gun = new NbtCompound();
        gun.putInt("Rounds", 1); gun.putInt("Reserve", 3); gun.putInt("Cooldown", 4);
        gun.putInt("Reloading", 0); gun.putInt("Burst", 0);
        saved.put("BobinFirearm", gun);
        enemy.readNbt(saved);
        NbtCompound roundtrip = enemy.writeNbt(new NbtCompound());
        check(enemy.faction().name().equals("ASH_RAIDERS") && enemy.appearanceVariant() == 2, "hostile faction/variant persisted");
        check(enemy.firearmType().name().equals("BREACHER"), "hostile actual firearm persisted");
        check(roundtrip.getCompound("BobinFirearm").equals(gun), "bounded magazine, reserve and cooldown survive NBT without refill");
        WarzoneGuardEntity reloaded = WarzoneEncounterManager.GUARD.create(warzone);
        reloaded.readNbt(roundtrip);
        NbtCompound twice = reloaded.writeNbt(new NbtCompound());
        check(twice.getCompound("BobinFirearm").equals(gun), "second firearm NBT round-trip does not refill");
        LOG.info("BOBIN_SMOKE_NPC_PERSISTENCE_PASS: faction, variant, firearm, bounded ammunition, cooldown");
    }

    private static void anchor(ServerWorld world, BlockPos pos) {
        check(world.getBlockState(pos.down()).isFullCube(world, pos.down()), "spawn support " + pos);
        check(world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                && world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty(), "spawn headroom " + pos);
    }

    private static void check(boolean condition, String description) {
        assertions++;
        if (!condition) throw new AssertionError(description);
    }
}
