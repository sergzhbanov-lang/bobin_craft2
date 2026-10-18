package com.bobincraft.raid.guard;

import com.bobincraft.weapon.BulletPenetration;
import com.bobincraft.weapon.GunServer;
import com.bobincraft.weapon.GunType;
import com.bobincraft.weapon.ShotServer;
import com.bobincraft.weapon.WeaponStats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3f;
import java.util.HashSet;
import java.util.Set;

/** Server-authoritative NPC hitscan. Shared caliber traversal, hit validation, damage and effects. */
public final class NpcWeaponAdapter {
    private NpcWeaponAdapter() {}

    public static boolean canFire(WarzoneGuardEntity shooter, LivingEntity target, NpcWeapon weapon) {
        if (!(target instanceof ServerPlayerEntity player) || !player.isAlive() || player.isCreative() || player.isSpectator()
                || shooter.isHubGuard() || !shooter.getMainHandStack().isOf(weapon.item())
                || shooter.squaredDistanceTo(target) > weapon.range() * weapon.range()) return false;
        // Recheck actual collision geometry on every shot, not only the throttled perception interval.
        Vec3d aim = target.getPos().add(0, target.getHeight() * 0.65, 0);
        return shooter.getWorld().raycast(new RaycastContext(shooter.getEyePos(), aim,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, shooter)).getType() == HitResult.Type.MISS;
    }

    public static void fire(WarzoneGuardEntity shooter, LivingEntity target, NpcWeapon weapon, double spreadDegrees) {
        if (!(shooter.getWorld() instanceof ServerWorld world) || !canFire(shooter, target, weapon)) return;
        Vec3d start = shooter.getEyePos();
        Vec3d aim = target.getPos().add(0, target.getHeight() * 0.65, 0).subtract(start).normalize();
        double spread = Math.tan(Math.toRadians(Math.max(weapon.special() == GunType.BREACHER ? 12 : 2, spreadDegrees)));
        double shotRange = Math.min(weapon.range(), start.distanceTo(target.getEyePos()) + 2.0);
        for (int pellet = 0; pellet < weapon.pellets(); pellet++) {
            Vec3d direction = aim.add((shooter.getRandom().nextDouble() - 0.5) * spread * 2,
                    (shooter.getRandom().nextDouble() - 0.5) * spread * 2,
                    (shooter.getRandom().nextDouble() - 0.5) * spread * 2).normalize();
            Vec3d end = BulletPenetration.traceRayWithCaliber(world, start, start.add(direction), shotRange, weapon.caliber(), false);
            LivingEntity hitEntity = null;
            Vec3d hitPoint = end;
            double nearest = start.squaredDistanceTo(end);
            Box search = new Box(start, end).expand(0.3);
            for (Entity candidate : world.getOtherEntities(shooter, search,
                    entity -> entity instanceof LivingEntity && entity.isAlive() && !entity.isSpectator())) {
                var intersection = candidate.getBoundingBox().expand(0.05).raycast(start, end);
                if (intersection.isEmpty()) continue;
                double distance = start.squaredDistanceTo(intersection.get());
                if (distance < nearest) {
                    nearest = distance;
                    hitEntity = (LivingEntity) candidate;
                    hitPoint = intersection.get();
                }
            }
            // Allies and other bystanders physically intercept the bullet; it never damages/passes through them.
            if (hitEntity instanceof ServerPlayerEntity player && !player.isCreative() && !player.isSpectator()) {
                float multiplier = penetrationMultiplier(world, start, hitPoint, weapon);
                if (multiplier >= BulletPenetration.MIN_DAMAGE_MULTIPLIER) {
                    if (weapon.special() != null) GunServer.applyEffects(shooter, world, hitEntity, weapon.special(), multiplier);
                    else ShotServer.applyHit(shooter, world, hitEntity, hitPoint,
                            (weapon == NpcWeapon.AK47 ? WeaponStats.AK_DAMAGE : WeaponStats.SNIPER_DAMAGE) * multiplier,
                            weapon.range(), (weapon == NpcWeapon.AK47 ? 0.2 : 0.5) * multiplier);
                }
            }
            // A short colored muzzle trace is bounded per shot, never streamed every frame.
            if (pellet == 0) {
                int color = weapon.special() == null ? 0xE1B26B : weapon.special().color;
                DustParticleEffect dust = new DustParticleEffect(new Vector3f(((color >> 16) & 255) / 255F,
                        ((color >> 8) & 255) / 255F, (color & 255) / 255F), 0.8F);
                for (int i = 1; i <= 6; i++) {
                    Vec3d point = start.lerp(hitPoint, i / 7.0);
                    world.spawnParticles(dust, point.x, point.y, point.z, 1, 0, 0, 0, 0);
                }
            }
        }
        world.spawnParticles(ParticleTypes.SMOKE, start.x, start.y, start.z, 2, 0.04, 0.04, 0.04, 0.01);
        var sound = weapon.special() == GunType.SPOTTER ? SoundEvents.ITEM_CROSSBOW_SHOOT
                : weapon.special() == GunType.CRYO ? SoundEvents.ENTITY_SNOW_GOLEM_SHOOT : SoundEvents.ENTITY_GENERIC_EXPLODE;
        world.playSound(null, shooter.getBlockPos(), sound, SoundCategory.HOSTILE,
                weapon.special() == GunType.SPOTTER ? 0.25F : 0.65F, weapon.special() == GunType.SPOTTER ? 1.9F : 1.5F);
        shooter.swingHand(net.minecraft.util.Hand.MAIN_HAND);
    }

    /** Same caliber loss rules as player bullets. NPCs never mutate terrain or break player blocks. */
    private static float penetrationMultiplier(ServerWorld world, Vec3d start, Vec3d hit, NpcWeapon weapon) {
        double distance = start.distanceTo(hit);
        Vec3d step = hit.subtract(start).normalize().multiply(0.25);
        Vec3d point = start;
        float multiplier = 1;
        int soil = 0;
        Set<BlockPos> visited = new HashSet<>();
        visited.add(BlockPos.ofFloored(start));
        for (double traveled = 0.25; traveled < distance; traveled += 0.25) {
            point = point.add(step);
            BlockPos pos = BlockPos.ofFloored(point);
            if (!world.isChunkLoaded(pos)) return 0;
            if (!visited.add(pos)) continue;
            var result = BulletPenetration.evaluateBlockStep(world.getBlockState(pos), weapon.caliber(), multiplier, soil);
            if (!result.penetrated()) return 0;
            multiplier = result.newDamageMultiplier();
            soil = result.newSoilBlocksPierced();
        }
        return multiplier;
    }
}
