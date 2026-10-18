package com.bobincraft.movement;

import com.bobincraft.render.WorldRenderHandler;
import com.bobincraft.weapon.BobinWeaponHandler;
import com.bobincraft.camera.BobinCameraState;
import com.bobincraft.input.ControlMath;
import com.bobincraft.screen.BobinScreen;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import net.minecraft.block.*;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BobinMovementController {

    public enum ActionType {
        NONE,
        MOVE_ONLY,
        INTERACT_BLOCK,
        INTERACT_ENTITY,
        BREAK_BLOCK,
        ATTACK_ENTITY
    }

    public enum MovePosture {
        NORMAL,
        SIDEWAYS_SNEAK,
        BACKWARD_SNEAK
    }

    // Угол (в градусах), в пределах которого персонаж считается смотрящим в точку движения (полная скорость)
    public static final float FORWARD_LOOK_CONE_DEG = 28.0f;
    // Угол, начиная с которого включается полное приседание/крадущийся шаг
    public static final float FULL_SNEAK_ANGLE_DEG = 60.0f;
    // Граница перехода между боковым крадущимся шагом и отходом спиной вперёд
    public static final float BACKWARD_ANGLE_DEG = 115.0f;

    // Множители скорости при движении боком и спиной вперёд крадясь
    public static final float SIDEWAYS_SPEED_MULT = 0.50f;
    public static final float BACKWARD_SPEED_MULT = 0.32f;

    private List<Vec3d> waypoints = new ArrayList<>();
    private int currentWaypointIndex = 0;

    private ActionType pendingAction = ActionType.NONE;
    private Vec3d exactGoal = null;
    private BlockPos targetBlockPos = null;
    private Direction targetBlockSide = Direction.UP;
    private Entity targetEntity = null;

    private boolean isMoving = false;
    private boolean keyboardMoving = false;
    private float moveForward = 0.0f;
    private float moveSideways = 0.0f;
    private boolean jump = false;

    // Состояние направленного замедления и крадущейся походки (боком / задом)
    private MovePosture posture = MovePosture.NORMAL;
    private float moveAngleDiff = 0.0f;     // [-180..180]: угол между вектором движения и взглядом (курсором)
    private float sneakFactor = 0.0f;       // [0..1]: плавный вес анимации крадущегося шага боком/задом
    private float speedMultiplier = 1.0f;   // [0.32..1.0]: текущий множитель скорости движения

    private boolean isBreaking = false;
    private int breakingTicks = 0;
    private int stuckTicks = 0;
    private int doorWaitTicks = 0;

    // Кулдаун на открытие дверей: ключ — нижняя половина двери, значение — тик, раньше которого не трогаем
    private final Map<Long, Integer> doorCooldown = new HashMap<>();
    private int tickCounter = 0;

    /**
     * Обновление точки движения на лету (зажатая ПКМ, как в Dota): не сбрасывает текущее движение,
     * просто пересчитывает путь к новой точке.
     */
    public void updateMoveGoal(MinecraftClient client, Vec3d goal) {
        if (pendingAction != ActionType.MOVE_ONLY) {
            clearTarget();
            this.pendingAction = ActionType.MOVE_ONLY;
        }
        this.targetBlockPos = null;
        this.targetEntity = null;
        this.exactGoal = goal;
        this.stuckTicks = 0;
        this.doorWaitTicks = 0;
        calculatePath(client, goal);
    }

    public void setMoveGoal(MinecraftClient client, Vec3d goal) {
        clearTarget();
        this.pendingAction = ActionType.MOVE_ONLY;
        this.exactGoal = goal;
        calculatePath(client, goal);
    }

    public void setInteractGoal(MinecraftClient client, BlockHitResult hit) {
        clearTarget();
        this.pendingAction = ActionType.INTERACT_BLOCK;
        this.targetBlockPos = hit.getBlockPos();
        this.targetBlockSide = hit.getSide();
        this.exactGoal = Vec3d.ofCenter(targetBlockPos);
        calculatePath(client, exactGoal);
    }

    /** Approach a real merchant, then use vanilla interaction and its server-side validation. */
    public void setEntityInteractGoal(MinecraftClient client, EntityHitResult hit) {
        Entity merchant = hit.getEntity();
        if (!(merchant instanceof MerchantEntity) || !merchant.isAlive() || merchant.isRemoved()
                || merchant.getWorld() != client.world) return;
        clearTarget();
        BobinWeaponHandler.stopShooting();
        this.pendingAction = ActionType.INTERACT_ENTITY;
        this.targetEntity = merchant;
        this.exactGoal = merchant.getPos();
        calculatePath(client, exactGoal);
    }

    public void setBreakGoal(MinecraftClient client, BlockHitResult hit) {
        clearTarget();
        this.pendingAction = ActionType.BREAK_BLOCK;
        this.targetBlockPos = hit.getBlockPos();
        this.targetBlockSide = hit.getSide();
        this.exactGoal = Vec3d.ofCenter(targetBlockPos);
        calculatePath(client, exactGoal);
    }

    public void setAttackGoal(MinecraftClient client, EntityHitResult hit) {
        clearTarget();
        this.pendingAction = ActionType.ATTACK_ENTITY;
        this.targetEntity = hit.getEntity();
        this.exactGoal = targetEntity.getPos();
        calculatePath(client, exactGoal);
    }

    public void clearTarget() {
        this.keyboardMoving = false;
        this.waypoints.clear();
        this.currentWaypointIndex = 0;
        this.pendingAction = ActionType.NONE;
        this.exactGoal = null;
        this.targetBlockPos = null;
        this.targetEntity = null;
        this.isMoving = false;
        this.moveForward = 0.0f;
        this.moveSideways = 0.0f;
        this.jump = false;
        this.posture = MovePosture.NORMAL;
        this.moveAngleDiff = 0.0f;
        this.sneakFactor = 0.0f;
        this.speedMultiplier = 1.0f;
        this.isBreaking = false;
        this.breakingTicks = 0;
        this.stuckTicks = 0;
        this.doorWaitTicks = 0;
    }

    /** Poll configured keys before vanilla travel, even though the tactical screen owns the cursor. */
    public void updateKeyboardMovement(MinecraftClient client) {
        if (keyboardMoving) clearTarget();
        if (client.player == null || !(client.currentScreen instanceof BobinScreen)
                || !client.isWindowFocused() || client.isPaused()) return;
        double forward = (isKeyDown(client, client.options.forwardKey) ? 1 : 0)
                - (isKeyDown(client, client.options.backKey) ? 1 : 0);
        double left = (isKeyDown(client, client.options.leftKey) ? 1 : 0)
                - (isKeyDown(client, client.options.rightKey) ? 1 : 0);
        if (forward == 0 && left == 0) return;
        if (isBreaking && client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
        clearTarget();
        ControlMath.Axes axes = ControlMath.playerAxes(left, forward,
                BobinCameraState.getYaw(), client.player.getYaw());
        moveForward = (float) axes.forward();
        moveSideways = (float) axes.sideways();
        moveAngleDiff = (float) Math.toDegrees(Math.atan2(-moveSideways, moveForward));
        // Retain directional walk animation without forced sneak-speed penalties.
        sneakFactor = Math.abs(moveAngleDiff) > FORWARD_LOOK_CONE_DEG ? 0.35f : 0;
        isMoving = true;
        keyboardMoving = true;
    }

    public static boolean isKeyDown(MinecraftClient client, KeyBinding binding) {
        if (client.getWindow() == null) return false;
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(binding);
        long handle = client.getWindow().getHandle();
        if (key.getCode() < 0) return false;
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return key.getCode() >= 0 && GLFW.glfwGetMouseButton(handle, key.getCode()) == GLFW.GLFW_PRESS;
        }
        if (key.getCategory() == InputUtil.Type.SCANCODE) {
            for (int code = GLFW.GLFW_KEY_SPACE; code <= GLFW.GLFW_KEY_LAST; code++) {
                boolean namedKey = (code >= GLFW.GLFW_KEY_ESCAPE && code <= GLFW.GLFW_KEY_END)
                        || (code >= GLFW.GLFW_KEY_CAPS_LOCK && code <= GLFW.GLFW_KEY_PAUSE)
                        || (code >= GLFW.GLFW_KEY_F1 && code <= GLFW.GLFW_KEY_F25)
                        || (code >= GLFW.GLFW_KEY_KP_0 && code <= GLFW.GLFW_KEY_KP_EQUAL)
                        || (code >= GLFW.GLFW_KEY_LEFT_SHIFT && code <= GLFW.GLFW_KEY_MENU);
                if (!namedKey && GLFW.glfwGetKeyName(code, 0) == null) continue;
                if (GLFW.glfwGetKeyScancode(code) == key.getCode()
                        && GLFW.glfwGetKey(handle, code) == GLFW.GLFW_PRESS) return true;
            }
            return false;
        }
        return key.getCode() != GLFW.GLFW_KEY_UNKNOWN && InputUtil.isKeyPressed(handle, key.getCode());
    }

    private void calculatePath(MinecraftClient client, Vec3d goal) {
        if (client.player == null || client.world == null) return;
        BlockPos start = client.player.getBlockPos();
        BlockPos target = BlockPos.ofFloored(goal);

        double distToGoal = Math.hypot(goal.x - client.player.getX(), goal.z - client.player.getZ());

        // Для близких целей (в радиусе до 3.0 блоков) идем напрямую в точку клика без лишних промежуточных узлов
        if (distToGoal <= 3.0) {
            this.waypoints = new ArrayList<>();
            this.waypoints.add(goal);
            this.currentWaypointIndex = 0;
            return;
        }

        // Для дальних расстояний используем воксельный A*
        this.waypoints = PathFinder.findPath(client.world, start, target, 400);

        if (!waypoints.isEmpty()) {
            this.waypoints.set(this.waypoints.size() - 1, goal);
        } else {
            this.waypoints.add(goal);
        }

        // ОПТИМИЗАЦИЯ: убираем вейпоинты, которые спавнятся сзади персонажа!
        while (waypoints.size() > 1) {
            Vec3d wp0 = waypoints.get(0);
            double toWp0X = wp0.x - client.player.getX();
            double toWp0Z = wp0.z - client.player.getZ();
            double distSqToWp0 = toWp0X * toWp0X + toWp0Z * toWp0Z;

            double toGoalX = goal.x - client.player.getX();
            double toGoalZ = goal.z - client.player.getZ();
            double dot = toWp0X * toGoalX + toWp0Z * toGoalZ;

            if (distSqToWp0 < 2.0 && (dot <= 0.05 || distSqToWp0 < 0.64)) {
                waypoints.remove(0);
            } else {
                break;
            }
        }

        this.currentWaypointIndex = 0;
    }

    /**
     * Плавный разворот персонажа за курсором мыши каждый кадр рендера (используется, когда в руках нет оружия,
     * чтобы персонаж всегда поворачивался за курсором и мог идти боком/спиной крадясь при отводе курсора).
     */
    public void updateCursorLookFrame(MinecraftClient client, HitResult hoveredHit, boolean isAimingThrow) {
        if (client == null || client.player == null || isAimingThrow) return;
        if (BobinWeaponHandler.isHoldingWeapon(client.player)) return;
        if (isBreaking) return;

        if (hoveredHit != null && hoveredHit.getType() != HitResult.Type.MISS) {
            Vec3d target = hoveredHit.getPos();
            double dx = target.x - client.player.getX();
            double dz = target.z - client.player.getZ();
            if (dx * dx + dz * dz > 0.04) {
                float cursorYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                smoothRotatePlayer(client.player, cursorYaw, 0.0f, 18.0f);
            }
        }
    }

    public void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        if (keyboardMoving) return;
        tickCounter++;
        if (pendingAction == ActionType.INTERACT_ENTITY) {
            if (!(targetEntity instanceof MerchantEntity) || !targetEntity.isAlive() || targetEntity.isRemoved()
                    || targetEntity.getWorld() != client.world) {
                clearTarget();
                return;
            }
            // Ordinary villagers may move; refresh their destination at most twice per second.
            if (tickCounter % 10 == 0 && targetEntity.getPos().squaredDistanceTo(exactGoal) > 1.0) {
                exactGoal = targetEntity.getPos();
                calculatePath(client, exactGoal);
            }
        }

        // 1. Проверяем расстояние досягаемости ТОЛЬКО для действий с блоком или сущностью
        if (pendingAction != ActionType.MOVE_ONLY && pendingAction != ActionType.NONE) {
            double reachDistance = pendingAction == ActionType.INTERACT_ENTITY ? 3.0 : 4.2;
            boolean inReach = false;

            if (targetBlockPos != null) {
                double distSq = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(targetBlockPos));
                inReach = distSq <= (reachDistance * reachDistance);
            } else if (targetEntity != null) {
                double distSq = player.getEyePos().squaredDistanceTo(targetEntity.getEyePos());
                inReach = distSq <= (reachDistance * reachDistance);
                if (pendingAction == ActionType.INTERACT_ENTITY) inReach &= player.canSee(targetEntity);
            }

            if (inReach) {
                isMoving = false;
                moveForward = 0.0f;
                moveSideways = 0.0f;
                jump = false;
                stuckTicks = 0;
                sneakFactor += (0.0f - sneakFactor) * 0.35f;
                if (sneakFactor < 0.01f) sneakFactor = 0.0f;
                speedMultiplier = 1.0f;
                posture = MovePosture.NORMAL;
                executeInReachAction(client);
                return;
            }
        }

        // 2. Плавание в воде
        boolean inWater = player.isTouchingWater() || player.isSubmergedInWater();

        // 3. Автоматическое открытие дверей перед персонажем на пути движения
        if (isMoving) {
            checkAndAutoOpenDoors(client, player);
        }

        // 4. Передвижение по точкам к цели с учётом угла разворота курсора
        if (currentWaypointIndex < waypoints.size()) {
            Vec3d waypoint = waypoints.get(currentWaypointIndex);
            double dx = waypoint.x - player.getX();
            double dz = waypoint.z - player.getZ();
            double horizontalDist = Math.hypot(dx, dz);

            // Направление движения к текущей точке маршрута
            float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

            boolean holdingWeapon = BobinWeaponHandler.isHoldingWeapon(player);
            if (!holdingWeapon) {
                // Если без оружия: поворачиваемся к курсору (а если курсор не навелся — к точке пути)
                HitResult hovered = WorldRenderHandler.getHoveredHit();
                if (hovered != null && hovered.getType() != HitResult.Type.MISS) {
                    Vec3d hitPos = hovered.getPos();
                    double cdx = hitPos.x - player.getX();
                    double cdz = hitPos.z - player.getZ();
                    if (cdx * cdx + cdz * cdz > 0.04) {
                        float cursorYaw = (float) Math.toDegrees(Math.atan2(-cdx, cdz));
                        smoothRotatePlayer(player, cursorYaw, 0.0f, 28.0f);
                    }
                } else {
                    smoothRotatePlayer(player, targetYaw, 0.0f, 22.0f);
                }
            }

            // Угол рассогласования между направлением движения (targetYaw) и взглядом персонажа за курсором (player.getYaw())
            float diffYaw = MathHelper.wrapDegrees(targetYaw - player.getYaw());
            float absDiff = Math.abs(diffYaw);
            this.moveAngleDiff = diffYaw;

            float targetSneak;
            float targetSpeed;

            if (absDiff <= FORWARD_LOOK_CONE_DEG) {
                // Персонаж смотрит в сторону точки движения — идёт обычно с полной скоростью
                targetSneak = 0.0f;
                targetSpeed = 1.0f;
                this.posture = MovePosture.NORMAL;
            } else if (absDiff <= BACKWARD_ANGLE_DEG) {
                // Персонаж развернулся курсором вбок — идёт боком крадясь с замедлением
                float t = MathHelper.clamp((absDiff - FORWARD_LOOK_CONE_DEG) / (FULL_SNEAK_ANGLE_DEG - FORWARD_LOOK_CONE_DEG), 0.0f, 1.0f);
                float smoothT = t * t * (3.0f - 2.0f * t);
                targetSneak = Math.max(0.35f, smoothT);

                float speedT = MathHelper.clamp((absDiff - FORWARD_LOOK_CONE_DEG) / (BACKWARD_ANGLE_DEG - FORWARD_LOOK_CONE_DEG), 0.0f, 1.0f);
                float smoothSpeedT = speedT * speedT * (3.0f - 2.0f * speedT);
                targetSpeed = MathHelper.lerp(smoothSpeedT, 1.0f, SIDEWAYS_SPEED_MULT);
                this.posture = MovePosture.SIDEWAYS_SNEAK;
            } else {
                // Персонаж развернулся курсором в противоположную сторону — идёт спиной вперёд (задом) крадясь
                targetSneak = 1.0f;
                float backT = MathHelper.clamp((absDiff - BACKWARD_ANGLE_DEG) / (180.0f - BACKWARD_ANGLE_DEG), 0.0f, 1.0f);
                float smoothBackT = backT * backT * (3.0f - 2.0f * backT);
                targetSpeed = MathHelper.lerp(smoothBackT, SIDEWAYS_SPEED_MULT, BACKWARD_SPEED_MULT);
                this.posture = MovePosture.BACKWARD_SNEAK;
            }

            this.sneakFactor += (targetSneak - this.sneakFactor) * 0.30f;
            this.speedMultiplier += (targetSpeed - this.speedMultiplier) * 0.30f;

            if (this.sneakFactor > 0.1f) {
                player.setSprinting(false);
            }

            double rad = Math.toRadians(diffYaw);
            this.moveForward = (float) Math.cos(rad) * this.speedMultiplier;
            this.moveSideways = (float) -Math.sin(rad) * this.speedMultiplier;
            this.isMoving = true;

            if (inWater) {
                jump = true;
            } else if (player.horizontalCollision && player.isOnGround()) {
                if (isClosedPassageAhead(client, player, dx, dz)) {
                    // Упёрлись в закрытую дверь: не прыгаем «в дверь», ждём пока она откроется (см. checkAndAutoOpenDoors)
                    jump = false;
                    doorWaitTicks++;
                    if (doorWaitTicks > 60) {
                        clearTarget();
                        return;
                    }
                } else {
                    jump = true;
                    stuckTicks++;
                    if (stuckTicks > 40) {
                        clearTarget();
                        return;
                    }
                }
            } else {
                jump = false;
                stuckTicks = 0;
                doorWaitTicks = 0;
            }

            // Перед дверью и в проёме подходим точнее к центру, чтобы не цеплять косяк
            double arrivalThreshold = (currentWaypointIndex == waypoints.size() - 1) ? 0.35 : 0.55;
            if (currentWaypointIndex < waypoints.size() - 1
                    && PathFinder.isDoorLike(client.world, BlockPos.ofFloored(waypoint.x, waypoint.y + 0.1, waypoint.z))) {
                arrivalThreshold = 0.3;
            }
            if (horizontalDist < arrivalThreshold) {
                currentWaypointIndex++;
                stuckTicks = 0;
                if (currentWaypointIndex >= waypoints.size()) {
                    isMoving = false;
                    moveForward = 0.0f;
                    moveSideways = 0.0f;
                    jump = false;
                    posture = MovePosture.NORMAL;
                    speedMultiplier = 1.0f;
                    if (pendingAction == ActionType.MOVE_ONLY) {
                        pendingAction = ActionType.NONE;
                    }
                }
            }
        } else {
            isMoving = false;
            moveForward = 0.0f;
            moveSideways = 0.0f;
            jump = inWater;
            stuckTicks = 0;
            posture = MovePosture.NORMAL;
            speedMultiplier = 1.0f;
            sneakFactor += (0.0f - sneakFactor) * 0.35f;
            if (sneakFactor < 0.01f) {
                sneakFactor = 0.0f;
            }

            if (pendingAction != ActionType.BREAK_BLOCK && pendingAction != ActionType.INTERACT_BLOCK) {
                float curPitch = player.getPitch();
                if (Math.abs(curPitch) > 0.5f) {
                    float step = MathHelper.clamp(-curPitch, -15.0f, 15.0f);
                    player.setPitch(curPitch + step);
                } else {
                    player.setPitch(0.0f);
                }
            }
        }
    }

    /**
     * Автоматическое открытие закрытых дверей, калиток и люков по ходу движения.
     */
    private void checkAndAutoOpenDoors(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null || currentWaypointIndex >= waypoints.size()) return;
        Vec3d wp = waypoints.get(currentWaypointIndex);
        double dx = wp.x - player.getX();
        double dz = wp.z - player.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 0.05) {
            if (currentWaypointIndex + 1 >= waypoints.size()) return;
            Vec3d next = waypoints.get(currentWaypointIndex + 1);
            dx = next.x - player.getX();
            dz = next.z - player.getZ();
            len = Math.hypot(dx, dz);
            if (len < 0.05) return;
        }
        double dirX = dx / len;
        double dirZ = dz / len;

        for (double dist = 0.6; dist <= 2.2; dist += 0.4) {
            BlockPos checkFeet = BlockPos.ofFloored(player.getX() + dirX * dist, player.getY() + 0.1, player.getZ() + dirZ * dist);
            tryOpenDoorAt(client, player, checkFeet);
            tryOpenDoorAt(client, player, checkFeet.up());
        }
    }

    /** Закрытая дверь / калитка / люк прямо перед персонажем (на уровне ног или головы). */
    private boolean isClosedPassageAhead(MinecraftClient client, ClientPlayerEntity player, double dx, double dz) {
        double len = Math.hypot(dx, dz);
        if (len < 0.01 || client.world == null) return false;
        double dirX = dx / len;
        double dirZ = dz / len;
        for (double dist = 0.5; dist <= 0.9; dist += 0.4) {
            BlockPos feet = BlockPos.ofFloored(player.getX() + dirX * dist, player.getY() + 0.1, player.getZ() + dirZ * dist);
            if (isClosedPassage(client.world.getBlockState(feet)) || isClosedPassage(client.world.getBlockState(feet.up()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClosedPassage(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof DoorBlock) return !state.get(DoorBlock.OPEN);
        if (b instanceof FenceGateBlock) return !state.get(FenceGateBlock.OPEN);
        if (b instanceof TrapdoorBlock) return !state.get(TrapdoorBlock.OPEN);
        return false;
    }

    private void tryOpenDoorAt(MinecraftClient client, ClientPlayerEntity player, BlockPos pos) {
        BlockState state = client.world.getBlockState(pos);
        Block b = state.getBlock();

        BlockPos target = pos;
        if (b instanceof DoorBlock) {
            if (state.isOf(Blocks.IRON_DOOR) || state.get(DoorBlock.OPEN)) return;
            if (state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
                target = pos.down();
            }
        } else if (b instanceof FenceGateBlock) {
            if (state.get(FenceGateBlock.OPEN)) return;
        } else if (b instanceof TrapdoorBlock) {
            if (state.isOf(Blocks.IRON_TRAPDOOR) || state.get(TrapdoorBlock.OPEN)) return;
        } else {
            return;
        }

        long key = target.asLong();
        Integer allowedAt = doorCooldown.get(key);
        if (allowedAt != null && tickCounter < allowedAt) return;
        if (doorCooldown.size() > 64) doorCooldown.clear();
        doorCooldown.put(key, tickCounter + 12);

        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
    }

    private void executeInReachAction(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;

        Vec3d lookTarget = targetBlockPos != null ? Vec3d.ofCenter(targetBlockPos) : targetEntity.getEyePos();
        double dx = lookTarget.x - player.getX();
        double dy = lookTarget.y - player.getEyeY();
        double dz = lookTarget.z - player.getZ();
        double distH = Math.hypot(dx, dz);

        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, distH)));
        smoothRotatePlayer(player, targetYaw, targetPitch, 22.0f);

        switch (pendingAction) {
            case INTERACT_BLOCK -> {
                if (targetBlockPos != null) {
                    BlockHitResult hit = new BlockHitResult(lookTarget, targetBlockSide, targetBlockPos, false);
                    client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
                    player.swingHand(Hand.MAIN_HAND);
                }
                clearTarget();
            }
            case INTERACT_ENTITY -> {
                Entity merchant = targetEntity;
                // Clear movement before vanilla interaction opens another Screen.
                clearTarget();
                if (merchant instanceof MerchantEntity && merchant.isAlive() && !merchant.isRemoved()
                        && merchant.getWorld() == client.world && player.squaredDistanceTo(merchant) <= 9.0
                        && player.canSee(merchant)) {
                    var result = client.interactionManager.interactEntity(player, merchant, Hand.MAIN_HAND);
                    if (!result.isAccepted()) client.interactionManager.interactEntity(player, merchant, Hand.OFF_HAND);
                    player.swingHand(Hand.MAIN_HAND);
                }
            }
            case BREAK_BLOCK -> {
                if (targetBlockPos != null) {
                    client.interactionManager.updateBlockBreakingProgress(targetBlockPos, targetBlockSide);
                    player.swingHand(Hand.MAIN_HAND);
                    isBreaking = true;
                    breakingTicks++;

                    if (client.world.getBlockState(targetBlockPos).isAir()) {
                        clearTarget();
                    }
                }
            }
            case ATTACK_ENTITY -> {
                if (targetEntity != null && targetEntity.isAlive()) {
                    client.interactionManager.attackEntity(player, targetEntity);
                    player.swingHand(Hand.MAIN_HAND);
                }
                clearTarget();
            }
            default -> {}
        }
    }

    private void smoothRotatePlayer(ClientPlayerEntity player, float targetYaw, float targetPitch, float maxSpeed) {
        float curYaw = player.getYaw();
        float yawDiff = MathHelper.wrapDegrees(targetYaw - curYaw);
        float stepYaw = MathHelper.clamp(yawDiff, -maxSpeed, maxSpeed);
        player.setYaw(curYaw + stepYaw);
        player.headYaw = player.getYaw();
        player.bodyYaw = player.getYaw();

        float curPitch = player.getPitch();
        float pitchDiff = targetPitch - curPitch;
        float stepPitch = MathHelper.clamp(pitchDiff, -maxSpeed, maxSpeed);
        player.setPitch(curPitch + stepPitch);
    }

    public boolean shouldMoveForward() {
        return isMoving;
    }

    public boolean isMoving() {
        return isMoving;
    }

    public float getMoveForward() {
        return moveForward;
    }

    public float getMoveSideways() {
        return moveSideways;
    }

    public boolean shouldJump() {
        return jump;
    }

    public MovePosture getPosture() {
        return posture;
    }

    public float getMoveAngleDiff() {
        return moveAngleDiff;
    }

    public float getSneakFactor() {
        return sneakFactor;
    }

    public float getSpeedMultiplier() {
        return speedMultiplier;
    }

    public boolean isSneakingWalk() {
        return isMoving && sneakFactor > 0.1f;
    }

    public List<Vec3d> getWaypoints() {
        return waypoints;
    }

    public int getCurrentWaypointIndex() {
        return currentWaypointIndex;
    }

    public BlockPos getTargetBlockPos() {
        return targetBlockPos;
    }

    public ActionType getPendingAction() {
        return pendingAction;
    }
}
