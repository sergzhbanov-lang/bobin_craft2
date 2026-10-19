package com.bobincraft;

import com.bobincraft.block.IcicleBlock;
import com.bobincraft.raid.HubContent;
import com.bobincraft.raid.HubSafety;
import com.bobincraft.raid.RaidManager;
import com.bobincraft.raid.WarzoneEncounterManager;
import com.bobincraft.siege.SiegeManager;
import com.bobincraft.warzone.NaturalWaterEffects;
import com.bobincraft.warzone.WarzoneDimension;
import com.bobincraft.weapon.AK47Item;
import com.bobincraft.weapon.BulletPenetration;
import com.bobincraft.weapon.GunItem;
import com.bobincraft.weapon.GunServer;
import com.bobincraft.weapon.GunType;
import com.bobincraft.weapon.ShotServer;
import com.bobincraft.weapon.SniperRifleItem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class BobinCraft implements ModInitializer {
    public static final String MOD_ID = "bobin_craft";

    // Предметы оружия
    public static final Item AK47 = new AK47Item(new FabricItemSettings().maxCount(1));
    public static final Item SNIPER_RIFLE = new SniperRifleItem(new FabricItemSettings().maxCount(1));

    // Ледяная сосулька (прозрачный аналог капельника) — украшение ледяных тоннелей горных крепостей
    public static final Block ICICLE = new IcicleBlock(FabricBlockSettings.create()
            .mapColor(MapColor.LIGHT_BLUE)
            .strength(0.4f)
            .sounds(BlockSoundGroup.GLASS)
            .nonOpaque());
    public static final Item ICICLE_ITEM = new BlockItem(ICICLE, new FabricItemSettings());

    // Сетевые пакеты выстрелов
    public static final Identifier SHOOT_PACKET_ID = new Identifier(MOD_ID, "ak47_shoot");
    public static final Identifier SNIPER_SHOOT_PACKET_ID = new Identifier(MOD_ID, "sniper_shoot");
    // Попадания нового оружия (Взломщик / Разведчик / Крио / Медик)
    public static final Identifier GUN_HIT_PACKET_ID = new Identifier(MOD_ID, "gun_hit");

    @Override
    public void onInitialize() {
        Registry.register(Registries.BLOCK, new Identifier(MOD_ID, "icicle"), ICICLE);
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "icicle"), ICICLE_ITEM);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.NATURAL).register(entries -> entries.add(ICICLE_ITEM));
        SiegeManager.register();
        // Измерение «Зона боевых действий» (процедурный рельеф; позже — базы и ИИ-враги)
        com.bobincraft.warzone.ForestBlocks.register();
        WarzoneDimension.register();
        NaturalWaterEffects.register();
        HubContent.register();
        WarzoneEncounterManager.register();
        RaidManager.register();
        HubSafety.register();
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "ak47"), AK47);
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "sniper_rifle"), SNIPER_RIFLE);

        for (GunType gun : GunType.values()) {
            gun.item = Registry.register(Registries.ITEM, new Identifier(MOD_ID, gun.id),
                    new GunItem(new FabricItemSettings().maxCount(1), gun));
        }

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> {
            entries.add(AK47);
            entries.add(SNIPER_RIFLE);
            for (GunType gun : GunType.values()) {
                entries.add(gun.item);
            }
        });

        // Серверный обработчик попаданий нового оружия (эффекты и прострел блоков — в GunServer)
        ServerPlayNetworking.registerGlobalReceiver(GUN_HIT_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            int gunIndex = buf.readByte();
            int targetEntityId = buf.readInt();
            double hitX = buf.readDouble();
            double hitY = buf.readDouble();
            double hitZ = buf.readDouble();
            BlockPos breachPos = buf.readBoolean() ? buf.readBlockPos() : null;
            float damageMult = buf.readableBytes() >= 4 ? buf.readFloat() : 1.0f;
            int brokenCount = buf.readableBytes() >= 4 ? Math.min(Math.max(0, buf.readInt()), 64) : 0;
            List<BlockPos> brokenBlocks = new ArrayList<>(brokenCount);
            for (int i = 0; i < brokenCount && buf.readableBytes() >= 8; i++) {
                brokenBlocks.add(buf.readBlockPos());
            }

            server.execute(() -> {
                GunType[] all = GunType.values();
                if (gunIndex < 0 || gunIndex >= all.length) return;
                GunServer.handleHit(player, all[gunIndex], targetEntityId, new Vec3d(hitX, hitY, hitZ), breachPos, damageMult, brokenBlocks);
            });
        });

        // Серверный обработчик выстрела АК-47 (базовый урон 3.0 * множитель прострела + разрушение хрупких блоков)
        ServerPlayNetworking.registerGlobalReceiver(SHOOT_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            int targetEntityId = buf.readInt();
            double hitX = buf.readDouble();
            double hitY = buf.readDouble();
            double hitZ = buf.readDouble();
            float damageMult = buf.readableBytes() >= 4 ? buf.readFloat() : 1.0f;
            int piercedCount = buf.readableBytes() >= 4 ? Math.min(Math.max(0, buf.readInt()), 64) : 0;
            List<BlockPos> brokenBlocks = new ArrayList<>(piercedCount);
            for (int i = 0; i < piercedCount && buf.readableBytes() >= 8; i++) {
                brokenBlocks.add(buf.readBlockPos());
            }

            server.execute(() -> {
                if (!ShotServer.allowShot(player, AK47, 120L, 4)) return;
                ServerWorld world = (ServerWorld) player.getWorld();

                GunServer.breakPiercedBlocks(player, world, BulletPenetration.Caliber.RIFLE_762, 28.0, brokenBlocks);

                if (targetEntityId != -1) {
                    Entity target = world.getEntityById(targetEntityId);
                    if (target instanceof LivingEntity living) {
                        float mult = MathHelper.clamp(damageMult, BulletPenetration.MIN_DAMAGE_MULTIPLIER, 1.0f);
                        ShotServer.applyHit(player, world, living, new Vec3d(hitX, hitY, hitZ), com.bobincraft.weapon.WeaponStats.AK_DAMAGE * mult, com.bobincraft.weapon.WeaponStats.AK_RANGE, 0.2 * mult);
                    }
                }
            });
        });

        // Серверный обработчик выстрела снайперской винтовки (базовый урон 50.0 * множитель прострела + разрушение блоков)
        ServerPlayNetworking.registerGlobalReceiver(SNIPER_SHOOT_PACKET_ID, (server, player, handler, buf, responseSender) -> {
            int targetEntityId = buf.readInt();
            double hitX = buf.readDouble();
            double hitY = buf.readDouble();
            double hitZ = buf.readDouble();
            float damageMult = buf.readableBytes() >= 4 ? buf.readFloat() : 1.0f;
            int piercedCount = buf.readableBytes() >= 4 ? Math.min(Math.max(0, buf.readInt()), 64) : 0;
            List<BlockPos> brokenBlocks = new ArrayList<>(piercedCount);
            for (int i = 0; i < piercedCount && buf.readableBytes() >= 8; i++) {
                brokenBlocks.add(buf.readBlockPos());
            }

            server.execute(() -> {
                ServerWorld world = (ServerWorld) player.getWorld();
                if (!ShotServer.allowShot(player, SNIPER_RIFLE, 1500L, 1)) return;

                GunServer.breakPiercedBlocks(player, world, BulletPenetration.Caliber.HEAVY_50BMG, 150.0, brokenBlocks);

                if (targetEntityId != -1) {
                    Entity target = world.getEntityById(targetEntityId);
                    if (target instanceof LivingEntity living) {
                        float mult = MathHelper.clamp(damageMult, BulletPenetration.MIN_DAMAGE_MULTIPLIER, 1.0f);
                        ShotServer.applyHit(player, world, living, new Vec3d(hitX, hitY, hitZ), com.bobincraft.weapon.WeaponStats.SNIPER_DAMAGE * mult, 150.0, 0.5 * mult);
                    }
                }
            });
        });
    }
}
