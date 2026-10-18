package com.bobincraft.weapon;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Серверная проверка и применение выстрелов АК-47 / снайперской винтовки:
 * оружие должно быть у игрока, цель — в пределах дальности, темп стрельбы ограничен «ведром жетонов»,
 * точка попадания должна лежать у хитбокса цели. Попадание в голову даёт бонус урона.
 */
public final class ShotServer {
    private ShotServer() {}

    public static final float HEADSHOT_MULTIPLIER = 1.5f;

    /** Ведро жетонов: [жетоны, время последнего пополнения (нс)]. */
    private static final Map<String, double[]> BUCKETS = new ConcurrentHashMap<>();

    /** @return true, если выстрел допустим (оружие есть, темп не превышен). */
    public static boolean allowShot(ServerPlayerEntity player, Item gun, long minIntervalMs, int burstCap) {
        if (!player.isAlive() || !owns(player, gun)) return false;
        String key = player.getUuid() + ":" + System.identityHashCode(gun);
        long now = System.nanoTime();
        double[] b = BUCKETS.computeIfAbsent(key, k -> new double[]{burstCap, now});
        synchronized (b) {
            double refill = (now - b[1]) / (minIntervalMs * 1_000_000.0);
            b[0] = Math.min(burstCap, b[0] + refill);
            b[1] = now;
            if (b[0] < 1.0) return false;
            b[0] -= 1.0;
            com.bobincraft.raid.WarzoneEncounterManager.onGunfire(player);
            return true;
        }
    }

    public static void forget(UUID playerId) {
        BUCKETS.keySet().removeIf(k -> k.startsWith(playerId.toString()));
    }

    /**
     * Наносит урон выстрела с проверкой дальности и хэдшотом.
     * @return true, если урон нанесён
     */
    public static boolean applyHit(LivingEntity player, ServerWorld world, LivingEntity target,
                                   Vec3d hit, float baseDamage, double range, double knockback) {
        if (!target.isAlive() || !Double.isFinite(hit.x) || !Double.isFinite(hit.y) || !Double.isFinite(hit.z)
                || !Float.isFinite(baseDamage) || baseDamage <= 0) return false;
        double maxDist = range + 20.0; // запас на полёт пули и движение
        if (player.squaredDistanceTo(target) > maxDist * maxDist) return false;

        boolean nearTarget = target.getBoundingBox().expand(1.5).contains(hit);
        if (!nearTarget) return false;
        boolean headshot = nearTarget && hit.y >= target.getEyeY() - 0.25;
        float amount = headshot ? baseDamage * HEADSHOT_MULTIPLIER : baseDamage;

        target.timeUntilRegen = 0;
        target.damage(player instanceof net.minecraft.entity.player.PlayerEntity p
                ? player.getDamageSources().playerAttack(p) : player.getDamageSources().mobAttack(player), amount);
        float yaw = player.getYaw();
        target.takeKnockback(knockback, -Math.sin(Math.toRadians(yaw)), Math.cos(Math.toRadians(yaw)));

        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.5f, headshot ? 1.8f : 1.2f);
        if (headshot && player instanceof ServerPlayerEntity shooter) {
            shooter.sendMessage(Text.literal(target.isAlive() ? "§c✦ ХЭДШОТ" : "§4✦ ХЭДШОТ — УБИТ"), true);
        } else if (!target.isAlive() && player instanceof ServerPlayerEntity shooter) {
            shooter.sendMessage(Text.literal("§6✦ Убит"), true);
        }
        return true;
    }

    private static boolean owns(ServerPlayerEntity player, Item gun) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(gun)) return true;
        }
        return false;
    }
}
