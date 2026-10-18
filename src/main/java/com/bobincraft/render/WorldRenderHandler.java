package com.bobincraft.render;

import java.util.Set;

import com.bobincraft.BobinCraft;
import com.bobincraft.BobinCraftClient;
import com.bobincraft.camera.BobinCameraState;
import com.bobincraft.movement.BobinMovementController;
import com.bobincraft.input.RaycastHelper;
import com.bobincraft.screen.BobinScreen;
import com.bobincraft.weapon.BobinWeaponHandler;
import com.bobincraft.weapon.BulletPenetration;
import com.bobincraft.weapon.GunServer;
import com.bobincraft.weapon.GunType;
import com.bobincraft.weapon.WeaponStats;
import com.mojang.blaze3d.systems.RenderSystem;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class WorldRenderHandler {

    private static HitResult hoveredHit = null;

    private static Vec3d clickPos = null;
    private static int clickColor = 0x33FF33;
    private static int clickTicksLeft = 0;
    private static final int MAX_RIPPLE_TICKS = 20;

    private static List<Vec3d> trajectory = new ArrayList<>();
    private static boolean trajectoryInRange = true;
    private static Vec3d trajectoryTargetPos = null;

    // Физическая пуля с коллизией в полете и системой прострела по калибрам
    public static class Bullet {
        public Vec3d currentPos;
        private Vec3d previousPos;
        private final net.minecraft.world.World sourceWorld;
        public final Vec3d velocity;
        public final Vec3d targetPos;
        public double distanceTraveled = 0.0;
        public final double maxDistance;
        public boolean alive = true;
        public final boolean isSniper;
        public final GunType gun; // null для АК-47 / снайперки
        public final BulletPenetration.Caliber caliber;
        public float damageMultiplier = 1.0f;
        public int soilBlocksPierced = 0;
        public final Set<BlockPos> visitedBlocks = new java.util.HashSet<>();
        public final List<BlockPos> piercedGlass = new ArrayList<>();

        public Bullet(Vec3d start, Vec3d target, boolean isSniper) {
            this(start, target, isSniper, null);
        }

        public Bullet(Vec3d start, Vec3d target, GunType gun) {
            this(start, target, false, gun);
        }

        private Bullet(Vec3d start, Vec3d target, boolean isSniper, GunType gun) {
            this.gun = gun;
            this.currentPos = start;
            this.previousPos = start;
            this.sourceWorld = MinecraftClient.getInstance().world;
            this.targetPos = target;
            this.isSniper = isSniper;
            this.caliber = BulletPenetration.getCaliber(isSniper, gun);
            this.visitedBlocks.add(BlockPos.ofFloored(start));
            Vec3d diff = target.subtract(start);
            this.maxDistance = diff.length();
            double speed = gun != null ? gun.bulletSpeed
                    : isSniper ? WeaponStats.SNIPER_BULLET_SPEED : WeaponStats.AK_BULLET_SPEED;
            this.velocity = diff.normalize().multiply(speed);
        }

        /**
         * Пошаговая проверка блоков вдоль отрезка [from -> to] с учётом калибра,
         * разрушения хрупких блоков (стекло, лёд, горшки, кристаллы, черепа, двери) и потери урона.
         * @return false, если пуля застряла в блоке
         */
        private boolean stepThroughBlocks(MinecraftClient client, Vec3d from, Vec3d to) {
            if (client.world == null || client.player == null) return false;
            // Traverse every crossed voxel, regardless of projectile speed or segment length.
            return BulletPenetration.visitBlocks(from, to, blockPos -> {
                BlockPos bp = blockPos.toImmutable();
                if (!visitedBlocks.add(bp)) return true;
                BlockState state = client.world.getBlockState(bp);
                if (state.isAir()) return true;
                Vec3d samplePos = new Box(bp).raycast(from, to).orElse(from);

                BulletPenetration.StepResult sr = BulletPenetration.evaluateBlockStep(
                        state, caliber, damageMultiplier, soilBlocksPierced
                );

                if (sr.shouldBreakBlock()) {
                    boolean canBreakHere = true;
                    if (gun == GunType.BREACHER && sr.category() == BulletPenetration.BlockCategory.THIN_WOOD) {
                        canBreakHere = Vec3d.ofCenter(bp).squaredDistanceTo(client.player.getPos())
                                <= GunServer.BREACH_RANGE * GunServer.BREACH_RANGE;
                    }
                    if (canBreakHere && !piercedGlass.contains(bp)) {
                        piercedGlass.add(bp);
                        BulletPenetration.playClientPenetrationEffects(client.world, bp, samplePos, state, true);
                    }
                } else if (sr.penetrated() && sr.category() != BulletPenetration.BlockCategory.FULL_PENETRATION) {
                    BulletPenetration.playClientPenetrationEffects(client.world, bp, samplePos, state, false);
                }

                if (!sr.penetrated()) {
                    currentPos = samplePos;
                    alive = false;
                    client.world.addParticle(ParticleTypes.SMOKE, samplePos.x, samplePos.y, samplePos.z, 0, 0.04, 0);
                    client.world.playSound(samplePos.x, samplePos.y, samplePos.z,
                            SoundEvents.BLOCK_STONE_HIT, SoundCategory.BLOCKS, 0.8f, 1.4f, false);
                    flushBrokenBlocksToServer(client, samplePos);
                    return false;
                }

                damageMultiplier = sr.newDamageMultiplier();
                soilBlocksPierced = sr.newSoilBlocksPierced();
                return true;
            });
        }

        private void flushBrokenBlocksToServer(MinecraftClient client, Vec3d pos) {
            if (piercedGlass.isEmpty()) return;
            if (gun != null) {
                sendGunPacket(client, gun, -1, pos, null, damageMultiplier, piercedGlass);
            } else {
                sendImpactPacket(client, -1, pos, isSniper, damageMultiplier, piercedGlass);
            }
        }

        public void tick(MinecraftClient client) {
            if (!alive || client.world == null || client.player == null) return;

            previousPos = currentPos;
            double stepDistance = Math.min(velocity.length(), Math.max(0.0, maxDistance - distanceTraveled));
            Vec3d nextPos = currentPos.add(velocity.normalize().multiply(stepDistance));
            distanceTraveled += stepDistance;

            // 1. Создаем искрящийся след от летящей пули
            if (gun != null) {
                switch (gun) {
                    case BREACHER -> client.world.addParticle(ParticleTypes.SMALL_FLAME, currentPos.x, currentPos.y, currentPos.z, 0, 0, 0);
                    case SPOTTER -> client.world.addParticle(ParticleTypes.WITCH, currentPos.x, currentPos.y, currentPos.z, 0, 0, 0);
                    case CRYO -> client.world.addParticle(ParticleTypes.SNOWFLAKE, currentPos.x, currentPos.y, currentPos.z, 0, 0, 0);
                    case MEDIC -> client.world.addParticle(ParticleTypes.HAPPY_VILLAGER, currentPos.x, currentPos.y, currentPos.z, 0, 0, 0);
                }
            } else if (isSniper) {
                client.world.addParticle(ParticleTypes.CRIT, currentPos.x, currentPos.y, currentPos.z, 0, 0, 0);
                client.world.addParticle(ParticleTypes.ENCHANTED_HIT, currentPos.x, currentPos.y, currentPos.z, 0, 0, 0);
                client.world.addParticle(ParticleTypes.SMOKE, currentPos.x, currentPos.y, currentPos.z, 0, 0.02, 0);
            } else {
                client.world.addParticle(ParticleTypes.CRIT, currentPos.x, currentPos.y, currentPos.z, 0, 0, 0);
            }

            // 2. ФИЗИЧЕСКАЯ КОЛЛИЗИЯ С СУЩНОСТЯМИ (картины и рамки пропускаются насквозь):
            Box stepBox = new Box(currentPos, nextPos).expand(0.35);
            EntityHitResult entHit = ProjectileUtil.raycast(
                    client.player,
                    currentPos,
                    nextPos,
                    stepBox,
                    entity -> !entity.isSpectator()
                            && entity.canHit()
                            && entity != client.player
                            && !BulletPenetration.isPassThroughDecorationEntity(entity),
                    currentPos.squaredDistanceTo(nextPos)
            );

            if (entHit != null && entHit.getEntity() != null) {
                Vec3d impactPoint = entHit.getPos();
                // Сначала проверяем блоки до точки попадания в сущность
                if (!stepThroughBlocks(client, currentPos, impactPoint)) {
                    return;
                }

                Entity hitEntity = entHit.getEntity();
                currentPos = impactPoint;
                alive = false;

                // Новое оружие: эффект и сниженный после прострела урон считает сервер (GunServer)
                if (gun != null) {
                    client.world.playSound(impactPoint.x, impactPoint.y, impactPoint.z,
                            SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.8f, 1.5f, false);
                    client.world.addParticle(ParticleTypes.DAMAGE_INDICATOR, impactPoint.x, impactPoint.y, impactPoint.z, 0, 0.05, 0);
                    sendGunPacket(client, gun, hitEntity.getId(), impactPoint, null, damageMultiplier, piercedGlass);
                    return;
                }

                // Эффекты попадания пули в существо
                if (isSniper) {
                    client.world.playSound(impactPoint.x, impactPoint.y, impactPoint.z,
                            SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.8f, 1.8f, false);
                    client.world.playSound(impactPoint.x, impactPoint.y, impactPoint.z,
                            SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 1.0f, 0.9f, false);
                    client.world.addParticle(ParticleTypes.DAMAGE_INDICATOR, impactPoint.x, impactPoint.y, impactPoint.z, 0, 0.1, 0);
                    client.world.addParticle(ParticleTypes.FLASH, impactPoint.x, impactPoint.y, impactPoint.z, 0, 0, 0);
                } else {
                    client.world.playSound(impactPoint.x, impactPoint.y, impactPoint.z,
                            SoundEvents.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 1.0f, 1.2f, false);
                    client.world.addParticle(ParticleTypes.DAMAGE_INDICATOR, impactPoint.x, impactPoint.y, impactPoint.z, 0, 0.05, 0);
                }

                // Пакет на сервер отправляется со списком пробитых хрупких блоков и множителем остаточного урона
                sendImpactPacket(client, hitEntity.getId(), impactPoint, isSniper, damageMultiplier, piercedGlass);

                return;
            }

            // 3. Пошаговая проверка пробития блоков вдоль шага [currentPos -> nextPos]
            if (!stepThroughBlocks(client, currentPos, nextPos)) {
                return;
            }

            currentPos = nextPos;

            // 4. Достижение максимальной дистанции полета
            if (distanceTraveled >= maxDistance) {
                alive = false;
                client.world.addParticle(ParticleTypes.SMOKE, targetPos.x, targetPos.y, targetPos.z, 0, 0.04, 0);
                flushBrokenBlocksToServer(client, targetPos);
            }
        }
    }

    private static final List<Bullet> activeBullets = new ArrayList<>();

    public static void spawnBullet(Vec3d start, Vec3d target) {
        spawnBullet(start, target, false);
    }

    public static void spawnBullet(Vec3d start, Vec3d target, boolean isSniper) {
        activeBullets.add(new Bullet(start, target, isSniper));
    }

    public static void spawnBullet(Vec3d start, Vec3d target, GunType gun) {
        activeBullets.add(new Bullet(start, target, gun));
    }

    public static void sendGunPacket(MinecraftClient client, GunType gun, int targetEntityId, Vec3d hitPos, BlockPos breachPos) {
        List<BlockPos> broken = breachPos != null ? List.of(breachPos) : List.of();
        sendGunPacket(client, gun, targetEntityId, hitPos, breachPos, 1.0f, broken);
    }

    public static void sendGunPacket(MinecraftClient client, GunType gun, int targetEntityId, Vec3d hitPos,
                                     BlockPos breachPos, float damageMultiplier, List<BlockPos> brokenBlocks) {
        if (client == null || client.getNetworkHandler() == null) return;

        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeByte(gun.ordinal());
        buf.writeInt(targetEntityId);
        buf.writeDouble(hitPos.x);
        buf.writeDouble(hitPos.y);
        buf.writeDouble(hitPos.z);
        buf.writeBoolean(breachPos != null);
        if (breachPos != null) {
            buf.writeBlockPos(breachPos);
        }
        buf.writeFloat(damageMultiplier);
        int count = brokenBlocks != null ? Math.min(brokenBlocks.size(), 64) : 0;
        buf.writeInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeBlockPos(brokenBlocks.get(i));
        }
        ClientPlayNetworking.send(BobinCraft.GUN_HIT_PACKET_ID, buf);
    }

    public static void sendImpactPacket(MinecraftClient client, int targetEntityId, Vec3d hitPos, boolean isSniper, List<BlockPos> piercedGlass) {
        sendImpactPacket(client, targetEntityId, hitPos, isSniper, 1.0f, piercedGlass);
    }

    public static void sendImpactPacket(MinecraftClient client, int targetEntityId, Vec3d hitPos, boolean isSniper,
                                        float damageMultiplier, List<BlockPos> brokenBlocks) {
        if (client == null || client.getNetworkHandler() == null) return;

        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeInt(targetEntityId);
        buf.writeDouble(hitPos.x);
        buf.writeDouble(hitPos.y);
        buf.writeDouble(hitPos.z);
        buf.writeFloat(damageMultiplier);

        int count = brokenBlocks != null ? Math.min(brokenBlocks.size(), 64) : 0;
        buf.writeInt(count);
        for (int i = 0; i < count; i++) {
            buf.writeBlockPos(brokenBlocks.get(i));
        }

        if (isSniper) {
            ClientPlayNetworking.send(BobinCraft.SNIPER_SHOOT_PACKET_ID, buf);
        } else {
            ClientPlayNetworking.send(BobinCraft.SHOOT_PACKET_ID, buf);
        }
    }

    public static void setHoveredHit(HitResult hit) {
        hoveredHit = hit;
    }

    public static HitResult getHoveredHit() {
        return hoveredHit;
    }

    public static void setTrajectory(List<Vec3d> points, boolean inRange, Vec3d targetPos) {
        trajectory = points;
        trajectoryInRange = inRange;
        trajectoryTargetPos = targetPos;
    }

    public static void clearTrajectory() {
        trajectory.clear();
        trajectoryTargetPos = null;
    }

    public static void spawnClickRipple(Vec3d pos, int color) {
        clickPos = pos;
        clickColor = color;
        clickTicksLeft = MAX_RIPPLE_TICKS;
    }

    /** Projectile simulation belongs to the 20 Hz tick, independently of rendering. */
    public static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            activeBullets.clear();
            hoveredHit = null;
            clickTicksLeft = 0;
            return;
        }
        if (clickTicksLeft > 0) clickTicksLeft--;
        Iterator<Bullet> iterator = activeBullets.iterator();
        while (iterator.hasNext()) {
            Bullet bullet = iterator.next();
            // Keep a completed segment for one tick so even close impacts have an interpolated tracer.
            if (!bullet.alive || bullet.sourceWorld != client.world) {
                iterator.remove();
                continue;
            }
            bullet.tick(client);
        }
    }

    public static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return;
        }

        // Курсор считается по камере ЭТОГО кадра и реальной проекционной матрице:
        // подсветка и цель оружия больше не отстают от картинки при вращении Q / E.
        RaycastHelper.setProjection(context.projectionMatrix());
        if (BobinCameraState.isActive() && client.currentScreen instanceof BobinScreen) {
            hoveredHit = RaycastHelper.raycastFromCursor(client, 150.0);
        } else {
            hoveredHit = null;
        }
        if (!BobinCameraState.isActive() && client.currentScreen == null) {
            BobinWeaponHandler.updateAimFrame(client, hoveredHit);
        }

        Camera camera = context.camera();
        Vec3d camPos = camera.getPos();
        MatrixStack matrices = context.matrixStack();

        matrices.push();
        matrices.translate(-camPos.x, -camPos.y, -camPos.z);

        // 1. Отрисовка летящих пуль в мире (до пост-эффекта тумана войны)
        for (Bullet bullet : activeBullets) {
            renderBulletEntity(matrices, bullet, context.tickDelta());
        }

        if (!BobinCameraState.isActive()) {
            renderWeaponSight(matrices, client);
            matrices.pop();
            return;
        }

        // 2. Туман войны (Fog of War): экранный пост-эффект по буферу глубины мира
        // Выполняется до тактических маркеров/прицела, чтобы буфер глубины содержал только геометрию мира,
        // а курсор, маршрут и лазерный прицел оставались чётко видны поверх затемнения.
        FogOfWarManager.render(matrices, client, context.tickDelta());

        // 3. Подсветка блока под курсором
        if (hoveredHit != null && hoveredHit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = ((BlockHitResult) hoveredHit).getBlockPos();
            Box box = new Box(pos).expand(0.002);
            renderOutlinedBox(matrices, box, 1.0f, 0.85f, 0.2f, 0.8f);
        }

        // 3b. Плавный маркер курсора в ТОЧНОЙ точке попадания (не привязан к сетке блоков)
        if (hoveredHit != null && hoveredHit.getType() != HitResult.Type.MISS) {
            Direction side = hoveredHit.getType() == HitResult.Type.BLOCK
                    ? ((BlockHitResult) hoveredHit).getSide() : Direction.UP;
            renderCursorRing(matrices, hoveredHit.getPos(), side);
        }

        // 4. Отрисовка пути перемещения персонажа (идти в точку в любом случае)
        BobinMovementController controller = BobinCraftClient.getMovementController();
        if (controller != null) {
            List<Vec3d> waypoints = controller.getWaypoints();
            int curIdx = controller.getCurrentWaypointIndex();

            if (!waypoints.isEmpty() && curIdx < waypoints.size()) {
                for (int i = curIdx; i < waypoints.size(); i++) {
                    Vec3d wp = waypoints.get(i);
                    Box wpBox = new Box(wp.x - 0.15, wp.y + 0.05, wp.z - 0.15, wp.x + 0.15, wp.y + 0.15, wp.z + 0.15);
                    renderOutlinedBox(matrices, wpBox, 0.2f, 1.0f, 0.3f, 0.9f);
                }
            }

            BlockPos targetPos = controller.getTargetBlockPos();
            if (targetPos != null) {
                Box targetBox = new Box(targetPos).expand(0.005);
                if (controller.getPendingAction() == BobinMovementController.ActionType.BREAK_BLOCK) {
                    renderOutlinedBox(matrices, targetBox, 1.0f, 0.2f, 0.2f, 0.95f);
                } else if (controller.getPendingAction() == BobinMovementController.ActionType.INTERACT_BLOCK) {
                    renderOutlinedBox(matrices, targetBox, 0.2f, 0.6f, 1.0f, 0.95f);
                }
            }
        }

        renderWeaponSight(matrices, client);

        // 6. Динамическая дуга прицеливания броска (авторасчет)
        if (!trajectory.isEmpty()) {
            float r = trajectoryInRange ? 0.0f : 1.0f;
            float g = trajectoryInRange ? 1.0f : 0.2f;
            float b = trajectoryInRange ? 0.6f : 0.2f;

            renderTrajectoryLine(matrices, trajectory, r, g, b, 0.95f);

            Vec3d impact = (trajectoryTargetPos != null && trajectoryInRange) ?
                    trajectoryTargetPos : trajectory.get(trajectory.size() - 1);

            Box impactBox = new Box(impact.x - 0.35, impact.y + 0.02, impact.z - 0.35,
                    impact.x + 0.35, impact.y + 0.05, impact.z + 0.35);
            renderOutlinedBox(matrices, impactBox, r, g, b, 1.0f);
        }

        // 7. Маркер клика по земле
        if (clickTicksLeft > 0 && clickPos != null) {
            float progress = 1.0f - ((clickTicksLeft - context.tickDelta()) / MAX_RIPPLE_TICKS);
            float radius = 0.2f + progress * 0.4f;
            float alpha = 1.0f - progress;

            float r = ((clickColor >> 16) & 0xFF) / 255.0f;
            float g = ((clickColor >> 8) & 0xFF) / 255.0f;
            float b = (clickColor & 0xFF) / 255.0f;

            Box rippleBox = new Box(
                    clickPos.x - radius, clickPos.y + 0.02, clickPos.z - radius,
                    clickPos.x + radius, clickPos.y + 0.04, clickPos.z + radius
            );
            renderOutlinedBox(matrices, rippleBox, r, g, b, alpha);
        }

        // 8. Отрисовка полупрозрачной крыши здания (Ghost Roof Cutaway)
        if (BuildingCutawayManager.isCutawayActive()) {
            renderCutawayRoof(matrices, BuildingCutawayManager.getGhostOutline());
        }

        matrices.pop();
    }

    private static void renderWeaponSight(MatrixStack matrices, MinecraftClient client) {
        // 5. Целеуказатель оружия
        if (BobinWeaponHandler.isLaserActive()) {
            Vec3d start = BobinWeaponHandler.getLaserStart();
            Vec3d centerEnd = BobinWeaponHandler.getLaserEnd();

            if (BobinWeaponHandler.isHoldingSniper(client.player)) {
                // Двулучевой прицел снайперской винтовки:
                // Отображается ТОЛЬКО при включенном режиме снайперского прицела (X)!
                if (BobinCameraState.isSniperScopeActive()) {
                    Vec3d leftEnd = BobinWeaponHandler.getSniperLeftLaserEnd();
                    Vec3d rightEnd = BobinWeaponHandler.getSniperRightLaserEnd();
                    float spreadAngle = BobinWeaponHandler.getSniperSpreadAngle();

                    renderTwoLaserSight(matrices, start, leftEnd, rightEnd, centerEnd,
                            spreadAngle, BobinWeaponHandler.SNIPER_MAX_SPREAD_ANGLE,
                            0.0f, 1.0f, 1.0f,  // Неоново-бирюзовый при сведении
                            1.0f, 0.45f, 0.05f // Янтарно-оранжевый при разведении
                    );
                }
            } else if (BobinWeaponHandler.isHoldingGun(client.player)) {
                GunType gun = BobinWeaponHandler.getHeldGun(client.player);
                float gr = ((gun.color >> 16) & 0xFF) / 255.0f;
                float gg = ((gun.color >> 8) & 0xFF) / 255.0f;
                float gb = (gun.color & 0xFF) / 255.0f;
                renderTwoLaserSight(matrices, start,
                        BobinWeaponHandler.getGunLeftLaserEnd(), BobinWeaponHandler.getGunRightLaserEnd(), centerEnd,
                        BobinWeaponHandler.getGunSpreadAngle(), gun.maxSpread,
                        gr, gg, gb,
                        Math.min(1.0f, gr + 0.4f), Math.min(1.0f, gg + 0.4f), Math.min(1.0f, gb + 0.4f));
            } else if (BobinWeaponHandler.isHoldingAK47(client.player)) {
                // Двулучевой прицел автомата АК-47 (старый одиночный луч удален)
                Vec3d leftEnd = BobinWeaponHandler.getAkLeftLaserEnd();
                Vec3d rightEnd = BobinWeaponHandler.getAkRightLaserEnd();
                float spreadAngle = BobinWeaponHandler.getAkSpreadAngle();

                renderTwoLaserSight(matrices, start, leftEnd, rightEnd, centerEnd,
                        spreadAngle, BobinWeaponHandler.AK_MAX_SPREAD_ANGLE,
                        1.0f, 0.15f, 0.15f, // Ярко-красный лазер при сведении
                        1.0f, 0.6f, 0.1f    // Красно-оранжевый при разведении
                );
            }
        }

    }

    /**
     * Отрисовка универсального двулучевого прицела (АК-47 / Снайперская винтовка):
     * Два лазера образуют угол разброса. Если персонаж стоит на месте — лазеры равномерно сходятся
     * и соединяются вместе в одну точку для 100% точности.
     * При ходьбе или выстрелах лазеры расходятся, задавая сектор разброса пули.
     */
    private static void renderTwoLaserSight(MatrixStack matrices, Vec3d start, Vec3d leftEnd, Vec3d rightEnd, Vec3d centerEnd,
                                            float spreadAngle, float maxSpreadAngle,
                                            float rFocus, float gFocus, float bFocus,
                                            float rSpread, float gSpread, float bSpread) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        Matrix4f mat = matrices.peek().getPositionMatrix();

        float t = MathHelper.clamp(spreadAngle / maxSpreadAngle, 0.0f, 1.0f);
        float r = MathHelper.lerp(t, rFocus, rSpread);
        float g = MathHelper.lerp(t, gFocus, gSpread);
        float b = MathHelper.lerp(t, bFocus, bSpread);
        float alpha = 0.95f;

        buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

        if (spreadAngle <= 0.06f) {
            // Лазеры соединены вместе: единый яркий высокоточный лазерный луч прямо в точку курсора
            buffer.vertex(mat, (float) start.x, (float) start.y, (float) start.z).color(rFocus, gFocus, bFocus, 1.0f).next();
            buffer.vertex(mat, (float) centerEnd.x, (float) centerEnd.y, (float) centerEnd.z).color(rFocus, gFocus, bFocus, 1.0f).next();
        } else {
            // Левый луч прицела
            buffer.vertex(mat, (float) start.x, (float) start.y, (float) start.z).color(r, g, b, alpha).next();
            buffer.vertex(mat, (float) leftEnd.x, (float) leftEnd.y, (float) leftEnd.z).color(r, g, b, alpha).next();

            // Правый луч прицела
            buffer.vertex(mat, (float) start.x, (float) start.y, (float) start.z).color(r, g, b, alpha).next();
            buffer.vertex(mat, (float) rightEnd.x, (float) rightEnd.y, (float) rightEnd.z).color(r, g, b, alpha).next();

            // Соединительная прицельная линия сектора разброса на целевой дистанции
            buffer.vertex(mat, (float) leftEnd.x, (float) leftEnd.y, (float) leftEnd.z).color(r, g, b, 0.6f).next();
            buffer.vertex(mat, (float) centerEnd.x, (float) centerEnd.y, (float) centerEnd.z).color(r, g, b, 0.6f).next();

            buffer.vertex(mat, (float) centerEnd.x, (float) centerEnd.y, (float) centerEnd.z).color(r, g, b, 0.6f).next();
            buffer.vertex(mat, (float) rightEnd.x, (float) rightEnd.y, (float) rightEnd.z).color(r, g, b, 0.6f).next();

            // Тонкий вспомогательный центральный луч
            buffer.vertex(mat, (float) start.x, (float) start.y, (float) start.z).color(r, g, b, 0.2f).next();
            buffer.vertex(mat, (float) centerEnd.x, (float) centerEnd.y, (float) centerEnd.z).color(r, g, b, 0.35f).next();
        }

        // Прицельная точка в центре
        Box centerDot = new Box(centerEnd.x - 0.05, centerEnd.y - 0.05, centerEnd.z - 0.05,
                centerEnd.x + 0.05, centerEnd.y + 0.05, centerEnd.z + 0.05);
        WorldRenderer.drawBox(matrices, buffer, centerDot, r, g, b, 1.0f);

        tessellator.draw();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderBulletEntity(MatrixStack matrices, Bullet bullet, float tickDelta) {
        Vec3d p = bullet.previousPos.lerp(bullet.currentPos, tickDelta);
        double trailLength = Math.min(p.distanceTo(bullet.previousPos), bullet.isSniper ? 0.8 : 0.5);
        Vec3d tail = p.subtract(bullet.velocity.normalize().multiply(trailLength));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        Matrix4f mat = matrices.peek().getPositionMatrix();

        float gr = 1.0f, gg = 1.0f, gb = 1.0f;
        if (bullet.gun != null) {
            gr = ((bullet.gun.color >> 16) & 0xFF) / 255.0f;
            gg = ((bullet.gun.color >> 8) & 0xFF) / 255.0f;
            gb = (bullet.gun.color & 0xFF) / 255.0f;
        }

        buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        if (bullet.gun != null) {
            buffer.vertex(mat, (float) tail.x, (float) tail.y, (float) tail.z).color(gr, gg, gb, 0.5f).next();
            buffer.vertex(mat, (float) p.x, (float) p.y, (float) p.z).color(1.0f, 1.0f, 1.0f, 1.0f).next();
        } else if (bullet.isSniper) {
            // Снайперская пуля: яркий бирюзово-голубой бронебойный трассер
            buffer.vertex(mat, (float) tail.x, (float) tail.y, (float) tail.z).color(0.1f, 0.9f, 1.0f, 1.0f).next();
            buffer.vertex(mat, (float) p.x, (float) p.y, (float) p.z).color(0.5f, 1.0f, 1.0f, 1.0f).next();
        } else {
            // Автоматная пуля: золотистый луч
            buffer.vertex(mat, (float) tail.x, (float) tail.y, (float) tail.z).color(1.0f, 0.85f, 0.1f, 1.0f).next();
            buffer.vertex(mat, (float) p.x, (float) p.y, (float) p.z).color(1.0f, 1.0f, 0.3f, 1.0f).next();
        }
        tessellator.draw();

        // Светящаяся точка пули
        double boxSize = bullet.isSniper ? 0.06 : (bullet.gun != null ? 0.05 : 0.04);
        Box bulletBox = new Box(p.x - boxSize, p.y - boxSize, p.z - boxSize,
                p.x + boxSize, p.y + boxSize, p.z + boxSize);
        buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        if (bullet.gun != null) {
            WorldRenderer.drawBox(matrices, buffer, bulletBox, gr, gg, gb, 1.0f);
        } else if (bullet.isSniper) {
            WorldRenderer.drawBox(matrices, buffer, bulletBox, 0.2f, 0.95f, 1.0f, 1.0f);
        } else {
            WorldRenderer.drawBox(matrices, buffer, bulletBox, 1.0f, 0.9f, 0.2f, 1.0f);
        }
        tessellator.draw();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderLaserBeam(MatrixStack matrices, Vec3d start, Vec3d end, float r, float g, float b, float a) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        Matrix4f mat = matrices.peek().getPositionMatrix();

        buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        buffer.vertex(mat, (float) start.x, (float) start.y, (float) start.z).color(r, g, b, a).next();
        buffer.vertex(mat, (float) end.x, (float) end.y, (float) end.z).color(r, g, b, a).next();
        tessellator.draw();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderTrajectoryLine(MatrixStack matrices, List<Vec3d> points, float r, float g, float b, float a) {
        if (points.size() < 2) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        Matrix4f mat = matrices.peek().getPositionMatrix();

        buffer.begin(VertexFormat.DrawMode.DEBUG_LINE_STRIP, VertexFormats.POSITION_COLOR);
        for (Vec3d p : points) {
            buffer.vertex(mat, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, a).next();
        }
        tessellator.draw();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderOutlinedBox(MatrixStack matrices, Box box, float r, float g, float b, float a) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        WorldRenderer.drawBox(matrices, buffer, box, r, g, b, a);
        tessellator.draw();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    /**
     * Кольцо-курсор на поверхности в точной точке попадания луча (два концентрических круга + точка).
     */
    private static void renderCursorRing(MatrixStack matrices, Vec3d p, Direction side) {
        Vec3d n = new Vec3d(side.getOffsetX(), side.getOffsetY(), side.getOffsetZ());
        Vec3d t1 = Math.abs(n.y) > 0.5 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d t2 = n.crossProduct(t1).normalize();
        t1 = t2.crossProduct(n).normalize();
        Vec3d c = p.add(n.multiply(0.02));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        Matrix4f mat = matrices.peek().getPositionMatrix();

        buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        final int segments = 20;
        double[] radii = {0.24, 0.27};
        for (double r : radii) {
            for (int i = 0; i < segments; i++) {
                double a0 = 2.0 * Math.PI * i / segments;
                double a1 = 2.0 * Math.PI * (i + 1) / segments;
                Vec3d v0 = c.add(t1.multiply(Math.cos(a0) * r)).add(t2.multiply(Math.sin(a0) * r));
                Vec3d v1 = c.add(t1.multiply(Math.cos(a1) * r)).add(t2.multiply(Math.sin(a1) * r));
                buffer.vertex(mat, (float) v0.x, (float) v0.y, (float) v0.z).color(1.0f, 0.95f, 0.5f, 0.95f).next();
                buffer.vertex(mat, (float) v1.x, (float) v1.y, (float) v1.z).color(1.0f, 0.95f, 0.5f, 0.95f).next();
            }
        }
        // Перекрестие в центре
        Vec3d a = c.add(t1.multiply(0.06)), b = c.subtract(t1.multiply(0.06));
        Vec3d d = c.add(t2.multiply(0.06)), e = c.subtract(t2.multiply(0.06));
        buffer.vertex(mat, (float) a.x, (float) a.y, (float) a.z).color(1.0f, 1.0f, 1.0f, 1.0f).next();
        buffer.vertex(mat, (float) b.x, (float) b.y, (float) b.z).color(1.0f, 1.0f, 1.0f, 1.0f).next();
        buffer.vertex(mat, (float) d.x, (float) d.y, (float) d.z).color(1.0f, 1.0f, 1.0f, 1.0f).next();
        buffer.vertex(mat, (float) e.x, (float) e.y, (float) e.z).color(1.0f, 1.0f, 1.0f, 1.0f).next();
        tessellator.draw();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void renderCutawayRoof(MatrixStack matrices, java.util.Collection<BlockPos> roofBlocks) {
        if (roofBlocks.isEmpty()) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        buffer.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        for (BlockPos p : roofBlocks) {
            Box box = new Box(p).expand(0.001);
            WorldRenderer.drawBox(matrices, buffer, box, 0.65f, 0.85f, 1.0f, 0.28f);
        }
        tessellator.draw();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
