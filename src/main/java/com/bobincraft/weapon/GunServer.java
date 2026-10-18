package com.bobincraft.weapon;

import net.minecraft.block.AbstractGlassBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.PaneBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверная логика уникальных эффектов нового оружия и разрушения пробиваемых блоков.
 */
public final class GunServer {
    private GunServer() {}

    /** Дальность выбивания блоков Взломщиком (клиент проверяет её же, сервер — с небольшим запасом). */
    public static final double BREACH_RANGE = 10.0;

    // Метка Разведчика: UUID -> игровой тик окончания
    private static final Map<UUID, Long> MARKED_UNTIL = new ConcurrentHashMap<>();
    // Холод Крио: UUID -> {стаки, тик окончания стаков}
    private static final Map<UUID, long[]> CHILL = new ConcurrentHashMap<>();
    // Заморозка Крио: UUID -> тик окончания
    private static final Map<UUID, Long> FROZEN_UNTIL = new ConcurrentHashMap<>();

    public static boolean isGlass(BlockState state) {
        return BulletPenetration.isFragileShatterBlock(state);
    }

    public static boolean isBreachable(BlockState state) {
        if (state == null || state.isAir()) return false;
        return state.isIn(BlockTags.WOODEN_DOORS)
                || state.isIn(BlockTags.WOODEN_TRAPDOORS)
                || state.isIn(BlockTags.FENCE_GATES)
                || state.isIn(BlockTags.WOODEN_FENCES)
                || state.isIn(BlockTags.ALL_SIGNS)
                || BulletPenetration.isFragileShatterBlock(state);
    }

    /**
     * Разрушает на сервере хрупкие/выбиваемые блоки, которые пуля прошила в полёте.
     */
    public static void breakPiercedBlocks(ServerPlayerEntity player,
                                          ServerWorld world,
                                          BulletPenetration.Caliber caliber,
                                          double maxRange,
                                          List<BlockPos> brokenBlocks) {
        if (brokenBlocks == null || brokenBlocks.isEmpty() || !player.canModifyBlocks()) return;
        double maxDistSq = (maxRange + 16.0) * (maxRange + 16.0);

        for (BlockPos pos : brokenBlocks) {
            if (pos.getSquaredDistance(player.getPos()) > maxDistSq) continue;
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (!BulletPenetration.canServerBreakBlock(state, caliber)) continue;

            if (caliber == BulletPenetration.Caliber.SHOTGUN_12G
                    && BulletPenetration.classifyBlock(state) == BulletPenetration.BlockCategory.THIN_WOOD
                    && Vec3d.ofCenter(pos).squaredDistanceTo(player.getPos()) > (BREACH_RANGE + 2.5) * (BREACH_RANGE + 2.5)) {
                continue;
            }

            world.breakBlock(pos, false, player);
        }
    }

    public static void handleHit(ServerPlayerEntity player, GunType gun, int entityId, Vec3d hit, BlockPos breach) {
        handleHit(player, gun, entityId, hit, breach, 1.0f, breach != null ? List.of(breach) : List.of());
    }

    public static void handleHit(ServerPlayerEntity player, GunType gun, int entityId, Vec3d hit,
                                 BlockPos breach, float damageMultiplier, List<BlockPos> brokenBlocks) {
        if (!Float.isFinite(damageMultiplier) || !Double.isFinite(hit.x) || !Double.isFinite(hit.y)
                || !Double.isFinite(hit.z) || gun.item == null || !owns(player, gun)
                || !ShotServer.allowShot(player, gun.item, Math.max(1, gun.intervalMs / gun.pellets), Math.max(2, gun.pellets + 1))) return;
        ServerWorld world = (ServerWorld) player.getWorld();
        BulletPenetration.Caliber caliber = BulletPenetration.getCaliber(false, gun);

        // 1. Разрушение простреленных хрупких блоков и выбитых дверей
        if (brokenBlocks != null && !brokenBlocks.isEmpty()) {
            breakPiercedBlocks(player, world, caliber, gun.range, brokenBlocks);
        } else if (breach != null) {
            breakPiercedBlocks(player, world, caliber, gun.range, List.of(breach));
        }

        if (entityId == -1) return;

        Entity entity = world.getEntityById(entityId);
        if (!(entity instanceof LivingEntity target) || !target.isAlive()) return;
        if (!target.getBoundingBox().expand(1.5).contains(hit)) return;
        double maxDist = gun.range + 20.0; // запас на полёт пули и движение игрока
        if (player.squaredDistanceTo(target) > maxDist * maxDist) return;

        applyEffects(player, world, target, gun, damageMultiplier);
    }

    /** Shared effect/damage pipeline after the caller validates an actual server shot and hit. */
    public static void applyEffects(LivingEntity player, ServerWorld world, LivingEntity target,
                                    GunType gun, float damageMultiplier) {
        if (world.isClient || !target.isAlive() || !Float.isFinite(damageMultiplier)) return;
        float mult = MathHelper.clamp(damageMultiplier, BulletPenetration.MIN_DAMAGE_MULTIPLIER, 1.0f);
        long now = world.getTime();
        purge(now);

        switch (gun) {
            case BREACHER -> {
                double dist = Math.sqrt(player.squaredDistanceTo(target));
                float falloff = (float) Math.max(0.3, 1.0 - dist / gun.range);
                hurt(player, target, gun.damage * falloff * mult, now);
                target.takeKnockback(0.12 * mult, -Math.sin(Math.toRadians(player.getYaw())), Math.cos(Math.toRadians(player.getYaw())));
            }
            case SPOTTER -> {
                hurt(player, target, gun.damage * mult, now);
                MARKED_UNTIL.put(target.getUuid(), now + 200);
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 200, 0, false, false), player);
                world.spawnParticles(ParticleTypes.WITCH, target.getX(), target.getBodyY(0.5), target.getZ(), 10, 0.3, 0.5, 0.3, 0.05);
                world.playSound(null, target.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.PLAYERS, 0.6f, 1.8f);
            }
            case CRYO -> cryo(player, world, target, gun, mult, now);
            case MEDIC -> {
                if (isFriendly(player, target)) {
                    target.heal(4.0f * mult);
                    target.removeStatusEffect(StatusEffects.POISON);
                    target.removeStatusEffect(StatusEffects.WITHER);
                    target.removeStatusEffect(StatusEffects.SLOWNESS);
                    target.removeStatusEffect(StatusEffects.WEAKNESS);
                    world.spawnParticles(ParticleTypes.HEART, target.getX(), target.getBodyY(1.0), target.getZ(), 5, 0.3, 0.3, 0.3, 0.02);
                    world.playSound(null, target.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.7f, 1.6f);
                } else {
                    hurt(player, target, gun.damage * mult, now);
                }
            }
        }
    }

    private static void cryo(LivingEntity player, ServerWorld world, LivingEntity target, GunType gun, float mult, long now) {
        UUID id = target.getUuid();
        long[] chill = CHILL.computeIfAbsent(id, k -> new long[]{0L, 0L});
        if (chill[1] < now) chill[0] = 0L;
        chill[0]++;
        chill[1] = now + 100;

        hurt(player, target, gun.damage * mult, now);
        target.extinguish();

        if (chill[0] >= 5) {
            chill[0] = 0L;
            FROZEN_UNTIL.put(id, now + 30);
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 6, false, false), player);
            target.setFrozenTicks(160);
            world.spawnParticles(ParticleTypes.SNOWFLAKE, target.getX(), target.getBodyY(0.5), target.getZ(), 25, 0.4, 0.6, 0.4, 0.08);
            world.playSound(null, target.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 0.8f, 1.6f);
        } else {
            int amp = (int) Math.min(3L, chill[0] - 1L); // Slowness I..IV
            target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, amp, false, true), player);
            world.spawnParticles(ParticleTypes.SNOWFLAKE, target.getX(), target.getBodyY(0.5), target.getZ(), 6, 0.3, 0.5, 0.3, 0.03);
        }
    }

    private static boolean isFriendly(LivingEntity shooter, LivingEntity t) {
        if (!(shooter instanceof ServerPlayerEntity player)) return t == shooter || shooter.isTeammate(t);
        return (t instanceof PlayerEntity && t != shooter)
                || (t instanceof TameableEntity tame && tame.isTamed() && tame.isOwner(player))
                || (t instanceof AbstractHorseEntity horse && horse.isTame())
                || t instanceof MerchantEntity
                || t instanceof IronGolemEntity;
    }

    /** Урон с учётом метки Разведчика (+30%) и заморозки Крио (+50%); каждая пуля/картечина засчитывается. */
    private static void hurt(LivingEntity player, LivingEntity target, float base, long now) {
        float amount = base;
        UUID id = target.getUuid();

        Long marked = MARKED_UNTIL.get(id);
        if (marked != null && marked > now) amount *= 1.3f;
        Long frozen = FROZEN_UNTIL.get(id);
        if (frozen != null && frozen > now) amount *= 1.5f;

        target.timeUntilRegen = 0; // без этого кадры неуязвимости съедали бы почти всю картечь и очередь
        target.damage(player instanceof net.minecraft.entity.player.PlayerEntity p
                ? player.getDamageSources().playerAttack(p) : player.getDamageSources().mobAttack(player), amount);
    }

    private static boolean owns(ServerPlayerEntity player, GunType gun) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(gun.item)) return true;
        }
        return false;
    }

    private static void purge(long now) {
        if (MARKED_UNTIL.size() > 256) MARKED_UNTIL.values().removeIf(t -> t < now);
        if (FROZEN_UNTIL.size() > 256) FROZEN_UNTIL.values().removeIf(t -> t < now);
        if (CHILL.size() > 256) CHILL.values().removeIf(c -> c[1] < now);
    }
}
