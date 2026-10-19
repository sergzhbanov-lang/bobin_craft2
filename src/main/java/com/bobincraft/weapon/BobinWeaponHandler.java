package com.bobincraft.weapon;

import com.bobincraft.BobinCraft;
import com.bobincraft.BobinCraftClient;
import com.bobincraft.camera.BobinCameraState;
import com.bobincraft.input.RaycastHelper;
import com.bobincraft.movement.BobinMovementController;
import com.bobincraft.render.WorldRenderHandler;
import com.bobincraft.raid.client.RaidClient;
import com.bobincraft.screen.BobinScreen;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BobinWeaponHandler {

    private static KeyBinding reloadKey;
    private static KeyBinding scopeKey;
    private static ItemStack reloadStack = ItemStack.EMPTY;
    private static ItemStack shootingStack = ItemStack.EMPTY;
    private static net.minecraft.client.world.ClientWorld lastWorld;
    private static float scopeBlend;
    private static float previousScopeBlend;

    /** Register once; gameplay still advances through the existing client tick callback. */
    public static void initializeControls() {
        reloadKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bobin_craft.reload", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.category.bobin_craft"));
        scopeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bobin_craft.scope", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, "key.category.bobin_craft"));
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!BobinCameraState.isActive() && client.currentScreen == null && !client.options.hudHidden) {
                BobinScreen.renderWeaponHUD(context, client);
            }
        });
    }

    /** Also used by BobinScreen, whose key events do not reach normal keybinding polling. */
    public static boolean handleKey(MinecraftClient client, int keyCode, int scanCode) {
        if (!isHoldingWeapon(client.player)) return false;
        if (reloadKey != null && reloadKey.matchesKey(keyCode, scanCode)) {
            startReload(client);
            return true;
        }
        if (scopeKey != null && scopeKey.matchesKey(keyCode, scanCode) && isHoldingSniper(client.player)) {
            toggleScope(client);
            return true;
        }
        return false;
    }

    public static boolean handleMouse(MinecraftClient client, int button) {
        if (!client.isWindowFocused() || !isHoldingWeapon(client.player)) return false;
        if (reloadKey != null && reloadKey.matchesMouse(button)) {
            startReload(client);
            return true;
        }
        if (scopeKey != null && scopeKey.matchesMouse(button) && isHoldingSniper(client.player)) {
            toggleScope(client);
            return true;
        }
        return false;
    }

    public static void toggleScope(MinecraftClient client) {
        if (client.player == null || !isHoldingSniper(client.player)) return;
        synchronizeWorld(client);
        BobinCameraState.toggleSniperScope();
        if (BobinCameraState.isSniperScopeActive()) onEnterSniperScope();
        if (client.world != null) {
            client.world.playSound(client.player.getX(), client.player.getY(), client.player.getZ(),
                    SoundEvents.ITEM_SPYGLASS_USE, SoundCategory.PLAYERS, 0.9f,
                    BobinCameraState.isSniperScopeActive() ? 1.25f : 0.85f, false);
        }
    }

    /** First-person ADS blends over four game ticks, with render interpolation. */
    public static float getScopeZoom(float tickDelta) {
        return MathHelper.lerp(MathHelper.lerp(tickDelta, previousScopeBlend, scopeBlend), 1.0f, 0.25f);
    }

    public static HitResult getAimHit(MinecraftClient client, HitResult tacticalHit) {
        if (client.player == null || client.world == null) return null;
        GunType gun = getHeldGun(client.player);
        double range = gun != null ? gun.range : isHoldingSniper(client.player) ? SNIPER_MAX_RANGE : AK_MAX_RANGE;
        if (BobinCameraState.isActive()) {
            return tacticalHit != null ? tacticalHit : RaycastHelper.raycastFromCursor(client, 150.0);
        }
        return RaycastHelper.raycastFromPlayer(client, range);
    }

    private static void synchronizeWorld(MinecraftClient client) {
        if (lastWorld == client.world) return;
        stopShooting();
        isReloading = false;
        reloadTicks = shotCooldown = 0;
        reloadStack = shootingStack = ItemStack.EMPTY;
        akShotCooldown = 0;
        Arrays.fill(gunShotCooldown, 0);
        BobinCameraState.setSniperScopeActive(false);
        scopeBlend = previousScopeBlend = 0.0f;
        lastWorld = client.world;
    }

    public enum WeaponType {
        NONE,
        AK47,
        SNIPER,
        GUN // оружие из GunType (Взломщик, Разведчик, Крио, Медик)
    }

    // Параметры АК-47
    public static final int MAX_AMMO_AK = WeaponStats.AK_MAGAZINE;
    private static int currentAmmoAK = 30;
    public static final int RELOAD_DURATION_AK = WeaponStats.AK_RELOAD_TICKS; // 3.0 секунды
    // Дальность выстрела автомата уменьшена примерно на 30-40 блоков (с 60 до 28 блоков)
    public static final double AK_MAX_RANGE = WeaponStats.AK_RANGE;
    // Разброс АК-47: 13 градусов максимум, сводка 3 секунды (60 тиков), разброс +2 градуса за выстрел
    public static final float AK_MAX_SPREAD_ANGLE = 13.0f;
    private static float akSpreadAngle = 0.0f;
    private static int akRecoveryDelayTicks = 0;
    private static Vec3d akLeftLaserEnd = Vec3d.ZERO;
    private static Vec3d akRightLaserEnd = Vec3d.ZERO;

    // Скорострельность АК-47: интервал 200 мс (5 выстр./сек, обойма 30 патронов выпускается за 6.0 секунд вместо 2)
    private static int akShotCooldown = 0;

    // Параметры снайперской винтовки
    public static final int MAX_AMMO_SNIPER = WeaponStats.SNIPER_MAGAZINE;
    private static int currentAmmoSniper = 5;
    public static final int RELOAD_DURATION_SNIPER = WeaponStats.SNIPER_RELOAD_TICKS; // 3.5 секунды
    public static final int SNIPER_SHOT_COOLDOWN = WeaponStats.SNIPER_INTERVAL_TICKS; // 2.0 секунды КД
    public static final double SNIPER_MAX_RANGE = WeaponStats.SNIPER_RANGE; // 120 блоков дальность
    // Scoped spread converges over SNIPER_AIM_TICKS after the entry delay; +7 degrees per shot.
    public static final float SNIPER_MAX_SPREAD_ANGLE = 26.0f;
    private static float sniperSpreadAngle = SNIPER_MAX_SPREAD_ANGLE;
    private static int sniperRecoveryDelayTicks = 0;
    private static Vec3d sniperLeftLaserEnd = Vec3d.ZERO;
    private static Vec3d sniperRightLaserEnd = Vec3d.ZERO;

    // Общее состояние оружия
    private static boolean isReloading = false;
    private static int reloadTicks = 0;
    private static int shotCooldown = 0;
    private static boolean isShooting = false;

    // Точки прицеливания
    private static Vec3d laserStart = Vec3d.ZERO;
    private static Vec3d laserEnd = Vec3d.ZERO;
    private static boolean laserActive = false;

    private static float prevPlayerYaw = 0.0f;
    private static float prevPlayerPitch = 0.0f;

    // ---- Новое оружие (GunType): состояние на каждый тип ----
    private static final int GUN_COUNT = GunType.values().length;
    private static final int[] gunAmmo = new int[GUN_COUNT];
    private static final float[] gunSpread = new float[GUN_COUNT];
    private static final int[] gunRecoverDelay = new int[GUN_COUNT];
    private static final int[] gunShotCooldown = new int[GUN_COUNT];
    private static Vec3d gunLeftLaserEnd = Vec3d.ZERO;
    private static Vec3d gunRightLaserEnd = Vec3d.ZERO;

    static {
        for (GunType g : GunType.values()) {
            gunAmmo[g.ordinal()] = g.maxAmmo;
            gunSpread[g.ordinal()] = g.minSpread;
        }
    }

    // Карта повреждений стекол (позиция -> количество попаданий)
    private static final Map<BlockPos, Integer> GLASS_HITS = new ConcurrentHashMap<>();

    public static WeaponType getHeldWeaponType(ClientPlayerEntity player) {
        if (player == null) return WeaponType.NONE;
        ItemStack main = player.getMainHandStack();
        if (main.isEmpty()) return WeaponType.NONE;
        if (main.getItem() instanceof GunItem) return WeaponType.GUN;
        if (main.isOf(BobinCraft.SNIPER_RIFLE)) return WeaponType.SNIPER;
        if (main.isOf(BobinCraft.AK47)) return WeaponType.AK47;
        return WeaponType.NONE;
    }

    public static GunType getHeldGun(ClientPlayerEntity player) {
        if (player == null) return null;
        ItemStack main = player.getMainHandStack();
        return main.getItem() instanceof GunItem gi ? gi.getGunType() : null;
    }

    public static boolean isHoldingGun(ClientPlayerEntity player) {
        return getHeldGun(player) != null;
    }

    public static float getGunSpreadAngle() {
        MinecraftClient client = MinecraftClient.getInstance();
        GunType g = getHeldGun(client.player);
        return g == null ? 0.0f : gunSpread[g.ordinal()];
    }

    public static Vec3d getGunLeftLaserEnd() {
        return gunLeftLaserEnd;
    }

    public static Vec3d getGunRightLaserEnd() {
        return gunRightLaserEnd;
    }

    public static boolean isHoldingWeapon(ClientPlayerEntity player) {
        return getHeldWeaponType(player) != WeaponType.NONE;
    }

    public static boolean isHoldingSniper(ClientPlayerEntity player) {
        return getHeldWeaponType(player) == WeaponType.SNIPER;
    }

    public static boolean isHoldingAK47(ClientPlayerEntity player) {
        return getHeldWeaponType(player) == WeaponType.AK47;
    }

    public static int getCurrentAmmo() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;
        GunType g = getHeldGun(client.player);
        if (g != null) return gunAmmo[g.ordinal()];
        return isHoldingSniper(client.player) ? currentAmmoSniper : currentAmmoAK;
    }

    public static int getMaxAmmo() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 30;
        GunType g = getHeldGun(client.player);
        if (g != null) return g.maxAmmo;
        return isHoldingSniper(client.player) ? MAX_AMMO_SNIPER : MAX_AMMO_AK;
    }

    public static boolean isReloading() {
        return isReloading;
    }

    public static int getReloadTicks() {
        return reloadTicks;
    }

    public static int getReloadDuration() {
        MinecraftClient client = MinecraftClient.getInstance();
        GunType g = getHeldGun(client.player);
        if (g != null) return g.reloadTicks;
        if (client.player != null && isHoldingSniper(client.player)) {
            return RELOAD_DURATION_SNIPER;
        }
        return RELOAD_DURATION_AK;
    }

    public static int getShotCooldown() {
        return shotCooldown;
    }

    public static float getAkSpreadAngle() {
        return akSpreadAngle;
    }

    public static Vec3d getAkLeftLaserEnd() {
        return akLeftLaserEnd;
    }

    public static Vec3d getAkRightLaserEnd() {
        return akRightLaserEnd;
    }

    public static float getSniperSpreadAngle() {
        return sniperSpreadAngle;
    }

    public static Vec3d getSniperLeftLaserEnd() {
        return sniperLeftLaserEnd;
    }

    public static Vec3d getSniperRightLaserEnd() {
        return sniperRightLaserEnd;
    }

    /**
     * Вызывается при нажатии X для входа в снайперский прицел:
     * Лазеры раскрываются на максимум (26 градусов) и начинают сводиться,
     * а не появляются сразу сведенными на 0 градусов!
     */
    public static void onEnterSniperScope() {
        sniperSpreadAngle = SNIPER_MAX_SPREAD_ANGLE;
        sniperRecoveryDelayTicks = 10;
    }

    /**
     * Перезарядка оружия запускается ТОЛЬКО по нажатию клавиши R!
     */
    public static void startReload(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        synchronizeWorld(client);
        if (isReloading && client.player.getMainHandStack() != reloadStack) {
            isReloading = false;
            reloadTicks = 0;
        }
        if (isReloading) return;
        WeaponType weapon = getHeldWeaponType(client.player);
        if (weapon == WeaponType.NONE) return;

        GunType reloadGun = getHeldGun(client.player);
        int maxAmmo = reloadGun != null ? reloadGun.maxAmmo : ((weapon == WeaponType.SNIPER) ? MAX_AMMO_SNIPER : MAX_AMMO_AK);
        int currentAmmo = reloadGun != null ? gunAmmo[reloadGun.ordinal()] : ((weapon == WeaponType.SNIPER) ? currentAmmoSniper : currentAmmoAK);
        if (currentAmmo >= maxAmmo) return;

        isReloading = true;
        reloadStack = client.player.getMainHandStack();
        reloadTicks = reloadGun != null ? reloadGun.reloadTicks : ((weapon == WeaponType.SNIPER) ? RELOAD_DURATION_SNIPER : RELOAD_DURATION_AK);

        if (client.world != null) {
            if (weapon == WeaponType.SNIPER) {
                client.world.playSound(
                        client.player.getX(), client.player.getY(), client.player.getZ(),
                        SoundEvents.BLOCK_PISTON_EXTEND,
                        SoundCategory.PLAYERS,
                        0.9f, 1.6f, false
                );
            } else {
                client.world.playSound(
                        client.player.getX(), client.player.getY(), client.player.getZ(),
                        SoundEvents.ITEM_ARMOR_EQUIP_IRON,
                        SoundCategory.PLAYERS,
                        1.0f, 0.8f, false
                );
            }
        }
    }

    /**
     * Стрельба из оружия на ЛКМ. Перезарядка на кнопку атаки запрещена.
     */
    public static void startShooting(MinecraftClient client, HitResult hit) {
        if (client.player == null || client.world == null || !client.isWindowFocused()
                || !client.player.isAlive() || client.player.isSpectator() || client.player.isUsingItem()) return;
        synchronizeWorld(client);
        if (isReloading && client.player.getMainHandStack() != reloadStack) {
            isReloading = false;
            reloadTicks = 0;
        }
        if (isReloading) return;
        hit = getAimHit(client, hit);
        updateAimFrame(client, hit);
        WeaponType weapon = getHeldWeaponType(client.player);
        if (weapon == WeaponType.NONE) return;
        shootingStack = client.player.getMainHandStack();

        GunType shootGun = getHeldGun(client.player);
        int ammo = shootGun != null ? gunAmmo[shootGun.ordinal()] : ((weapon == WeaponType.SNIPER) ? currentAmmoSniper : currentAmmoAK);

        if (ammo <= 0) {
            // Холостой щелчок бойка (без автоперезарядки)
            if (client.world != null) {
                client.world.playSound(
                        client.player.getX(), client.player.getY(), client.player.getZ(),
                        SoundEvents.BLOCK_DISPENSER_FAIL,
                        SoundCategory.PLAYERS,
                        0.8f, 2.0f, false
                );
            }
            return;
        }

        if (shootGun != null) {
            if (shootGun.automatic) isShooting = true;
            if (gunShotCooldown[shootGun.ordinal()] <= 0) {
                fireShot(client, hit);
                gunShotCooldown[shootGun.ordinal()] = shootGun.intervalTicks();
            }
        } else if (weapon == WeaponType.SNIPER) {
            if (shotCooldown <= 0) {
                fireShot(client, hit);
                shotCooldown = SNIPER_SHOT_COOLDOWN; // 2 сек КД
            }
        } else {
            isShooting = true;
            if (akShotCooldown <= 0) {
                fireShot(client, hit);
                akShotCooldown = WeaponStats.AK_INTERVAL_TICKS;
            }
        }
    }

    public static void stopShooting() {
        if (isShooting) {
            isShooting = false;
            // При отпускании курсора автомат получает задержку на сведение, чтобы при стрельбе оно не возвращалось
            akRecoveryDelayTicks = 25; // 1 секунда кулдауна
        }
    }

    /** Advances countdowns, recovery, and held fire once per client tick (20 Hz). */
    public static void tick(MinecraftClient client, HitResult hoveredHit) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            laserActive = false;
            stopShooting();
            isReloading = false;
            BobinCameraState.setSniperScopeActive(false);
            scopeBlend = previousScopeBlend = 0.0f;
            lastWorld = null;
            return;
        }

        synchronizeWorld(client);

        boolean normalControls = !BobinCameraState.isActive() && client.currentScreen == null
                && client.isWindowFocused() && player.isAlive() && !player.isSpectator() && !player.isUsingItem();
        if (reloadKey != null) {
            while (reloadKey.wasPressed()) if (normalControls) startReload(client);
            while (scopeKey.wasPressed()) if (normalControls) toggleScope(client);
        }
        if (!isHoldingSniper(player) || !player.isAlive() || player.isSpectator()) BobinCameraState.setSniperScopeActive(false);
        previousScopeBlend = scopeBlend;
        scopeBlend = MathHelper.clamp(scopeBlend + (BobinCameraState.isSniperScopeActive() ? 0.25f : -0.25f), 0.0f, 1.0f);

        if (isReloading && player.getMainHandStack() != reloadStack) {
            isReloading = false;
            reloadTicks = 0;
        }
        if (isShooting && player.getMainHandStack() != shootingStack) stopShooting();
        WeaponType weapon = getHeldWeaponType(player);
        if (shotCooldown > 0) shotCooldown--;
        if (akShotCooldown > 0) akShotCooldown--;
        for (int i = 0; i < GUN_COUNT; i++) {
            if (gunShotCooldown[i] > 0) gunShotCooldown[i]--;
        }
        if (weapon == WeaponType.NONE) {
            laserActive = false;
            isShooting = false;
            isReloading = false;
            return;
        }
        hoveredHit = getAimHit(client, hoveredHit);

        // Логика перезарядки
        if (isReloading) {
            reloadTicks--;

            if (weapon == WeaponType.SNIPER) {
                if (reloadTicks == 45) {
                    player.swingHand(Hand.OFF_HAND);
                    client.world.playSound(player.getX(), player.getY(), player.getZ(),
                            SoundEvents.ITEM_ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 0.8f, 1.2f, false);
                } else if (reloadTicks == 15) {
                    player.swingHand(Hand.MAIN_HAND);
                    client.world.playSound(player.getX(), player.getY(), player.getZ(),
                            SoundEvents.BLOCK_PISTON_CONTRACT, SoundCategory.PLAYERS, 0.8f, 1.6f, false);
                }
            } else {
                if (reloadTicks == 40) {
                    player.swingHand(Hand.MAIN_HAND);
                    client.world.playSound(player.getX(), player.getY(), player.getZ(),
                            SoundEvents.BLOCK_PISTON_CONTRACT, SoundCategory.PLAYERS, 0.6f, 1.4f, false);
                } else if (reloadTicks == 18) {
                    player.swingHand(Hand.OFF_HAND);
                    client.world.playSound(player.getX(), player.getY(), player.getZ(),
                            SoundEvents.ITEM_ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 0.9f, 1.1f, false);
                } else if (reloadTicks == 6) {
                    player.swingHand(Hand.MAIN_HAND);
                    client.world.playSound(player.getX(), player.getY(), player.getZ(),
                            SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.PLAYERS, 0.7f, 1.8f, false);
                }
            }

            if (reloadTicks <= 0) {
                isReloading = false;
                if (weapon == WeaponType.GUN) {
                    GunType rg = getHeldGun(player);
                    if (rg != null) gunAmmo[rg.ordinal()] = rg.maxAmmo;
                } else if (weapon == WeaponType.SNIPER) {
                    currentAmmoSniper = MAX_AMMO_SNIPER;
                } else {
                    currentAmmoAK = MAX_AMMO_AK;
                }
                client.world.playSound(player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.9f, false);
            }
        }

        boolean controlsActive = client.isWindowFocused()
                && ((BobinCameraState.isActive() && client.currentScreen instanceof BobinScreen) || normalControls);
        if (!controlsActive) stopShooting();
        if (normalControls && !client.options.attackKey.isPressed()) stopShooting();
        BobinMovementController controller = BobinCraftClient.getMovementController();
        GunType tickGun = getHeldGun(player);

        // Проверка движения и поворотов игрока
        float curYaw = player.getYaw();
        float curPitch = player.getPitch();
        float yawDiff = Math.abs(MathHelper.wrapDegrees(curYaw - prevPlayerYaw));
        float pitchDiff = Math.abs(curPitch - prevPlayerPitch);
        prevPlayerYaw = curYaw;
        prevPlayerPitch = curPitch;

        boolean isMoving = player.getVelocity().horizontalLengthSquared() > 0.0001 || (controller != null && controller.shouldMoveForward());
        boolean isTurning = (yawDiff > 0.5f) || (pitchDiff > 0.5f);

        if (weapon == WeaponType.AK47) {
            if (isShooting) {
                // Пока идет стрельба — сведение заблокировано!
                akRecoveryDelayTicks = 25;
            } else if (akRecoveryDelayTicks > 0) {
                akRecoveryDelayTicks--;
            }

            if (isMoving) {
                akSpreadAngle = Math.min(AK_MAX_SPREAD_ANGLE, akSpreadAngle + 0.35f);
            } else if (isTurning) {
                akSpreadAngle = Math.min(AK_MAX_SPREAD_ANGLE, akSpreadAngle + Math.max(0.15f, yawDiff * 0.1f));
            } else if (!isShooting && akRecoveryDelayTicks <= 0) {
                // Сводка 3 секунды (60 тиков): равномерное уменьшение до 0 только когда не стреляет
                akSpreadAngle = Math.max(0.0f, akSpreadAngle - (AK_MAX_SPREAD_ANGLE / 60.0f));
            }
        }

        if (weapon == WeaponType.GUN && tickGun != null) {
            int gi = tickGun.ordinal();
            if (isShooting) {
                gunRecoverDelay[gi] = tickGun.recoverDelay;
            } else if (gunRecoverDelay[gi] > 0) {
                gunRecoverDelay[gi]--;
            }

            if (isMoving) {
                gunSpread[gi] = Math.min(tickGun.maxSpread, gunSpread[gi] + tickGun.moveGain);
            } else if (!isShooting && gunRecoverDelay[gi] <= 0) {
                gunSpread[gi] = Math.max(tickGun.minSpread, gunSpread[gi] - tickGun.recoverPerTick);
            }
        }

        if (weapon == WeaponType.SNIPER) {
            boolean inScope = BobinCameraState.isSniperScopeActive();

            if (!inScope) {
                // Вне прицела лазеры сброшены на максимальный угол разброса (26°),
                // чтобы при включении режима прицела (X) лазеры ВСЕГДА начинали сводиться с максимума!
                sniperSpreadAngle = SNIPER_MAX_SPREAD_ANGLE;
                sniperRecoveryDelayTicks = 0;
            } else {
                if (sniperRecoveryDelayTicks > 0) {
                    sniperRecoveryDelayTicks--;
                }

                if (isMoving) {
                    sniperSpreadAngle = Math.min(SNIPER_MAX_SPREAD_ANGLE, sniperSpreadAngle + 0.35f);
                } else if (isTurning) {
                    sniperSpreadAngle = Math.min(SNIPER_MAX_SPREAD_ANGLE, sniperSpreadAngle + Math.max(0.12f, yawDiff * 0.08f));
                } else if (sniperRecoveryDelayTicks <= 0) {
                    sniperSpreadAngle = Math.max(0.0f, sniperSpreadAngle - (SNIPER_MAX_SPREAD_ANGLE / WeaponStats.SNIPER_AIM_TICKS));
                }
            }
        }

        if (controlsActive) updateAimFrame(client, hoveredHit);

        if (weapon == WeaponType.AK47) {
            // Стрельба очередью с фиксацией интервала 200 мс (выпуск обоймы за 6.0 сек)
            if (isShooting && !isReloading) {
                if (akShotCooldown <= 0) {
                    if (currentAmmoAK > 0) {
                        fireShot(client, hoveredHit);
                        akShotCooldown = WeaponStats.AK_INTERVAL_TICKS;
                    } else {
                        stopShooting();
                    }
                }
            }
        }

        if (weapon == WeaponType.GUN && tickGun != null) {
            int gi = tickGun.ordinal();
            // Автоматическая стрельба (Крио): интервал берётся из GunType
            if (tickGun.automatic && isShooting && !isReloading) {
                if (gunShotCooldown[gi] <= 0) {
                    if (gunAmmo[gi] > 0) {
                        fireShot(client, hoveredHit);
                        gunShotCooldown[gi] = tickGun.intervalTicks();
                    } else {
                        stopShooting();
                    }
                }
            }
        }
    }

    /** Refreshes cursor orientation and laser geometry without advancing gameplay time. */
    public static void updateAimFrame(MinecraftClient client, HitResult hoveredHit) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            laserActive = false;
            return;
        }

        WeaponType weapon = getHeldWeaponType(player);
        if (weapon == WeaponType.NONE) {
            laserActive = false;
            return;
        }

        laserActive = true;
        if (!BobinCameraState.isActive()) hoveredHit = getAimHit(client, null);

        // Позиция выхода пули/лазера из ствола оружия (с учётом занижения в крадущемся шаге боком/задом)
        BobinMovementController controller = BobinCraftClient.getMovementController();
        double sneakDrop = (controller != null) ? (0.20 * controller.getSneakFactor()) : 0.0;
        double yawRad = Math.toRadians(player.getYaw());
        double rightX = -Math.cos(yawRad) * 0.22;
        double rightZ = -Math.sin(yawRad) * 0.22;
        laserStart = player.getEyePos().add(rightX, -0.15 - sneakDrop, rightZ);

        // УБРАНА АНТИНАВОДКА: цель строго соответствует позиции курсора игрока!
        GunType tickGun = getHeldGun(player);
        double maxDist = tickGun != null ? tickGun.range : ((weapon == WeaponType.SNIPER) ? SNIPER_MAX_RANGE : AK_MAX_RANGE);
        Vec3d targetPoint;
        if (hoveredHit != null && hoveredHit.getType() != HitResult.Type.MISS) {
            targetPoint = hoveredHit.getPos();
        } else {
            targetPoint = laserStart.add(player.getRotationVec(1.0f).multiply(maxDist));
        }

        // Прямой поворот персонажа точно к точке курсора (без авто-доводки на мобов)
        Vec3d aimVec = targetPoint.subtract(laserStart);
        double distH = Math.hypot(aimVec.x, aimVec.z);
        float aimYaw = (float) Math.toDegrees(Math.atan2(-aimVec.x, aimVec.z));
        float aimPitch = (float) (-Math.toDegrees(Math.atan2(aimVec.y, distH)));
        if (BobinCameraState.isActive() && client.currentScreen instanceof BobinScreen) {
            player.setYaw(aimYaw);
            player.headYaw = aimYaw;
            player.bodyYaw = aimYaw;
            player.setPitch(aimPitch);
        }

        Vec3d centerDir = aimVec.normalize();
        double targetDist = Math.min(aimVec.length(), maxDist);

        Vec3d upRef = (Math.abs(centerDir.y) < 0.99) ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d rightVec = centerDir.crossProduct(upRef).normalize();

        if (weapon == WeaponType.AK47) {
            double halfAngleRad = Math.toRadians(akSpreadAngle / 2.0);
            Vec3d leftDir = centerDir.multiply(Math.cos(halfAngleRad)).subtract(rightVec.multiply(Math.sin(halfAngleRad))).normalize();
            Vec3d rightDir = centerDir.multiply(Math.cos(halfAngleRad)).add(rightVec.multiply(Math.sin(halfAngleRad))).normalize();

            boolean stopLaserAtCursor = shouldStopLaserAtHovered(client, hoveredHit, BulletPenetration.Caliber.RIFLE_762);
            double laserTraceDist = stopLaserAtCursor ? targetDist : AK_MAX_RANGE;
            akLeftLaserEnd = BulletPenetration.traceRayWithCaliber(client.world, laserStart, laserStart.add(leftDir.multiply(laserTraceDist)), AK_MAX_RANGE, BulletPenetration.Caliber.RIFLE_762, stopLaserAtCursor);
            akRightLaserEnd = BulletPenetration.traceRayWithCaliber(client.world, laserStart, laserStart.add(rightDir.multiply(laserTraceDist)), AK_MAX_RANGE, BulletPenetration.Caliber.RIFLE_762, stopLaserAtCursor);
            laserEnd = BulletPenetration.traceRayWithCaliber(client.world, laserStart, laserStart.add(centerDir.multiply(laserTraceDist)), AK_MAX_RANGE, BulletPenetration.Caliber.RIFLE_762, stopLaserAtCursor);
        }

        if (weapon == WeaponType.GUN && tickGun != null) {
            int gi = tickGun.ordinal();
            double halfAngleRad = Math.toRadians(gunSpread[gi] / 2.0);
            Vec3d leftDir = centerDir.multiply(Math.cos(halfAngleRad)).subtract(rightVec.multiply(Math.sin(halfAngleRad))).normalize();
            Vec3d rightDir = centerDir.multiply(Math.cos(halfAngleRad)).add(rightVec.multiply(Math.sin(halfAngleRad))).normalize();

            BulletPenetration.Caliber gunCaliber = BulletPenetration.getCaliber(false, tickGun);
            boolean stopLaserAtCursor = shouldStopLaserAtHovered(client, hoveredHit, gunCaliber);
            double laserTraceDist = stopLaserAtCursor ? targetDist : tickGun.range;
            gunLeftLaserEnd = BulletPenetration.traceRayWithCaliber(client.world, laserStart, laserStart.add(leftDir.multiply(laserTraceDist)), tickGun.range, gunCaliber, stopLaserAtCursor);
            gunRightLaserEnd = BulletPenetration.traceRayWithCaliber(client.world, laserStart, laserStart.add(rightDir.multiply(laserTraceDist)), tickGun.range, gunCaliber, stopLaserAtCursor);
            laserEnd = BulletPenetration.traceRayWithCaliber(client.world, laserStart, laserStart.add(centerDir.multiply(laserTraceDist)), tickGun.range, gunCaliber, stopLaserAtCursor);
        }

        if (weapon == WeaponType.SNIPER) {
            double halfAngleRad = Math.toRadians(sniperSpreadAngle / 2.0);
            Vec3d leftDir = centerDir.multiply(Math.cos(halfAngleRad)).subtract(rightVec.multiply(Math.sin(halfAngleRad))).normalize();
            Vec3d rightDir = centerDir.multiply(Math.cos(halfAngleRad)).add(rightVec.multiply(Math.sin(halfAngleRad))).normalize();

            boolean stopLaserAtCursor = shouldStopLaserAtHovered(client, hoveredHit, BulletPenetration.Caliber.HEAVY_50BMG);
            double laserTraceDist = stopLaserAtCursor ? targetDist : SNIPER_MAX_RANGE;
            sniperLeftLaserEnd = BulletPenetration.traceRayWithCaliber(client.world, laserStart, laserStart.add(leftDir.multiply(laserTraceDist)), SNIPER_MAX_RANGE, BulletPenetration.Caliber.HEAVY_50BMG, stopLaserAtCursor);
            sniperRightLaserEnd = BulletPenetration.traceRayWithCaliber(client.world, laserStart, laserStart.add(rightDir.multiply(laserTraceDist)), SNIPER_MAX_RANGE, BulletPenetration.Caliber.HEAVY_50BMG, stopLaserAtCursor);
            laserEnd = BulletPenetration.traceRayWithCaliber(client.world, laserStart, laserStart.add(centerDir.multiply(laserTraceDist)), SNIPER_MAX_RANGE, BulletPenetration.Caliber.HEAVY_50BMG, stopLaserAtCursor);
        }
    }

    private static boolean shouldStopLaserAtHovered(MinecraftClient client, HitResult hoveredHit, BulletPenetration.Caliber caliber) {
        if (client.world == null || hoveredHit == null || hoveredHit.getType() == HitResult.Type.MISS) {
            return false;
        }
        if (hoveredHit.getType() == HitResult.Type.ENTITY) {
            return true;
        }
        if (hoveredHit instanceof net.minecraft.util.hit.BlockHitResult bhr) {
            BlockState state = client.world.getBlockState(bhr.getBlockPos());
            // Если курсор наведён на простреливаемую преграду (трава, листва, окно, дверь и т.д.),
            // лазер проходит сквозь неё дальше по правилам калибра; если на твёрдый пол/стену — упирается в курсор
            return !BulletPenetration.isPenetrableByCaliber(state, caliber);
        }
        return true;
    }

    public static Vec3d traceWithPenetration(MinecraftClient client, Vec3d start, Vec3d aimTarget, double maxRange, boolean isSniper) {
        if (client.world == null) return aimTarget;
        BulletPenetration.Caliber caliber = BulletPenetration.getCaliber(isSniper, null);
        return BulletPenetration.traceRayWithCaliber(client.world, start, aimTarget, maxRange, caliber, false);
    }

    public static Vec3d traceWithPenetration(MinecraftClient client, Vec3d start, Vec3d aimTarget, double maxRange, GunType gun) {
        if (client.world == null) return aimTarget;
        BulletPenetration.Caliber caliber = BulletPenetration.getCaliber(false, gun);
        return BulletPenetration.traceRayWithCaliber(client.world, start, aimTarget, maxRange, caliber, false);
    }

    public static boolean isPenetrableByBullet(BlockState state, boolean isSniper) {
        if (state == null || state.isAir()) return true;
        BulletPenetration.Caliber caliber = BulletPenetration.getCaliber(isSniper, null);
        return BulletPenetration.isPenetrableByCaliber(state, caliber);
    }

    public static boolean isGlassBlock(BlockState state) {
        return BulletPenetration.isFragileShatterBlock(state);
    }

    /**
     * Выстрел из оружия.
     * Для снайперки без прицела: невидимый разброс от 5 до 16 градусов (нельзя попасть в цель без прицела).
     * Для автомата: при выстреле разброс увеличивается на 2 градуса, при зажатии сведение заблокировано.
     */
    public static void fireShot(MinecraftClient client, HitResult hit) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;
        if (isReloading) return;

        WeaponType weapon = getHeldWeaponType(player);
        if (weapon == WeaponType.NONE) return;

        if (weapon == WeaponType.GUN) {
            fireGun(client, getHeldGun(player));
            return;
        }

        boolean isSniper = (weapon == WeaponType.SNIPER);
        int ammo = isSniper ? currentAmmoSniper : currentAmmoAK;
        if (ammo <= 0) return;

        // Расход патрона
        if (isSniper) {
            currentAmmoSniper--;
        } else {
            currentAmmoAK--;
        }
        RaidClient.reportGunfire();

        player.swingHand(Hand.MAIN_HAND);

        Vec3d centerAim = (laserEnd != null) ? laserEnd.subtract(laserStart).normalize() : player.getRotationVec(1.0f);
        Vec3d upRef = (Math.abs(centerAim.y) < 0.99) ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d rightVec = centerAim.crossProduct(upRef).normalize();

        Vec3d shotDir;
        double maxRange = isSniper ? SNIPER_MAX_RANGE : AK_MAX_RANGE;

        if (isSniper) {
            boolean inScope = BobinCameraState.isSniperScopeActive();

            if (!inScope) {
                // Без режима прицела: невидимый принудительный разброс до 16 градусов!
                // Чтобы нельзя было стрелять прямо в цель без прицела:
                // Случайный угол от 5.0 до 16.0 градусов
                double hipSpreadDeg = 5.0 + Math.random() * 11.0;
                double sign = Math.random() < 0.5 ? -1.0 : 1.0;
                double shotAngleRad = sign * Math.toRadians(hipSpreadDeg);
                shotDir = centerAim.multiply(Math.cos(shotAngleRad)).add(rightVec.multiply(Math.sin(shotAngleRad))).normalize();
            } else {
                // В режиме оптического прицела: честная механика сведения двух лазеров
                double randomT = (sniperSpreadAngle <= 0.05f) ? 0.0 : (Math.random() * 2.0 - 1.0);
                double shotAngleRad = randomT * Math.toRadians(sniperSpreadAngle / 2.0);
                shotDir = centerAim.multiply(Math.cos(shotAngleRad)).add(rightVec.multiply(Math.sin(shotAngleRad))).normalize();

                // После каждого выстрела снайперки в прицеле разброс увеличивается на 7 градусов
                sniperSpreadAngle = Math.min(SNIPER_MAX_SPREAD_ANGLE, sniperSpreadAngle + 7.0f);
                sniperRecoveryDelayTicks = 20;
            }
        } else {
            // Разброс между двумя лазерами автомата
            double randomT = (akSpreadAngle <= 0.05f) ? 0.0 : (Math.random() * 2.0 - 1.0);
            double shotAngleRad = randomT * Math.toRadians(akSpreadAngle / 2.0);
            shotDir = centerAim.multiply(Math.cos(shotAngleRad)).add(rightVec.multiply(Math.sin(shotAngleRad))).normalize();

            // При выстрелах лазеры автомата расходятся на 2 градуса за выстрел (1 выстрел: 2°, 2: 4°, 3: 6°...)
            akSpreadAngle = Math.min(AK_MAX_SPREAD_ANGLE, akSpreadAngle + 2.0f);
            // При стрельбе сведение заблокировано
            akRecoveryDelayTicks = 25;
        }

        Vec3d targetDest = traceWithPenetration(client, laserStart, laserStart.add(shotDir.multiply(maxRange)), maxRange, isSniper);

        // Звуки и эффекты выстрела
        if (isSniper) {
            client.world.playSound(
                    player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_GENERIC_EXPLODE,
                    SoundCategory.PLAYERS,
                    1.0f, 1.6f, false
            );
            client.world.playSound(
                    player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT,
                    SoundCategory.PLAYERS,
                    0.8f, 1.9f, false
            );
            client.world.addParticle(ParticleTypes.FLASH, laserStart.x, laserStart.y, laserStart.z, 0, 0, 0);
            client.world.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, laserStart.x, laserStart.y, laserStart.z, 0, 0.08, 0);
        } else {
            client.world.playSound(
                    player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_GENERIC_EXPLODE,
                    SoundCategory.PLAYERS,
                    0.65f, 2.0f, false
            );
            client.world.playSound(
                    player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ITEM_CROSSBOW_SHOOT,
                    SoundCategory.PLAYERS,
                    1.0f, 1.3f, false
            );
            client.world.addParticle(ParticleTypes.CRIT, laserStart.x, laserStart.y, laserStart.z, 0, 0, 0);
            client.world.addParticle(ParticleTypes.SMOKE, laserStart.x, laserStart.y, laserStart.z, 0, 0.05, 0);
        }

        // Создаем физическую пулю с коллизией в полете
        WorldRenderHandler.spawnBullet(laserStart, targetDest, isSniper);
    }

    /**
     * Выстрел из нового оружия: картечь (несколько пуль в конусе) или одиночная пуля с разбросом между лазерами.
     * Уникальный эффект попадания считает сервер (GunServer).
     */
    private static void fireGun(MinecraftClient client, GunType gun) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || gun == null) return;
        int gi = gun.ordinal();
        if (gunAmmo[gi] <= 0) return;
        gunAmmo[gi]--;
        RaidClient.reportGunfire();

        player.swingHand(Hand.MAIN_HAND);

        Vec3d centerAim = (laserEnd != null) ? laserEnd.subtract(laserStart).normalize() : player.getRotationVec(1.0f);
        Vec3d upRef = (Math.abs(centerAim.y) < 0.99) ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
        Vec3d rightVec = centerAim.crossProduct(upRef).normalize();
        Vec3d upVec = rightVec.crossProduct(centerAim).normalize();
        float spread = gunSpread[gi];

        for (int i = 0; i < gun.pellets; i++) {
            Vec3d dir;
            if (gun.pellets == 1) {
                // Как у АК-47: случайная точка между двумя лазерами
                double t = (spread <= 0.05f) ? 0.0 : (Math.random() * 2.0 - 1.0);
                double a = t * Math.toRadians(spread / 2.0);
                dir = centerAim.multiply(Math.cos(a)).add(rightVec.multiply(Math.sin(a))).normalize();
            } else {
                // Картечь: равномерно по кругу конуса
                double ang = Math.random() * Math.PI * 2.0;
                double r = Math.sqrt(Math.random()) * Math.toRadians(spread / 2.0);
                Vec3d off = rightVec.multiply(Math.cos(ang) * Math.sin(r)).add(upVec.multiply(Math.sin(ang) * Math.sin(r)));
                dir = centerAim.multiply(Math.cos(r)).add(off).normalize();
            }
            Vec3d dest = traceWithPenetration(client, laserStart, laserStart.add(dir.multiply(gun.range)), gun.range, gun);
            WorldRenderHandler.spawnBullet(laserStart, dest, gun);
        }

        gunSpread[gi] = Math.min(gun.maxSpread, gunSpread[gi] + gun.shotGain);
        gunRecoverDelay[gi] = gun.recoverDelay;

        switch (gun) {
            case BREACHER -> {
                client.world.playSound(player.getX(), player.getY(), player.getZ(),
                        SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.9f, 1.25f, false);
                client.world.playSound(player.getX(), player.getY(), player.getZ(),
                        SoundEvents.BLOCK_PISTON_EXTEND, SoundCategory.PLAYERS, 0.6f, 1.9f, false);
                client.world.addParticle(ParticleTypes.FLAME, laserStart.x, laserStart.y, laserStart.z, 0, 0.02, 0);
                client.world.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, laserStart.x, laserStart.y, laserStart.z, 0, 0.06, 0);
            }
            case SPOTTER -> client.world.playSound(player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ITEM_CROSSBOW_SHOOT, SoundCategory.PLAYERS, 0.25f, 1.9f, false); // тихий «глушитель»
            case CRYO -> client.world.playSound(player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_SNOW_GOLEM_SHOOT, SoundCategory.PLAYERS, 0.7f, 1.3f, false);
            case MEDIC -> client.world.playSound(player.getX(), player.getY(), player.getZ(),
                    SoundEvents.BLOCK_BREWING_STAND_BREW, SoundCategory.PLAYERS, 0.6f, 1.6f, false);
        }
    }

    public static void handleGlassPiercing(World world, BlockPos pos) {
        if (world == null || pos == null) return;
        BlockState state = world.getBlockState(pos);
        if (!isGlassBlock(state)) return;

        int currentHits = GLASS_HITS.getOrDefault(pos, 0) + 1;
        GLASS_HITS.put(pos, currentHits);

        if (currentHits == 1) {
            world.playSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.BLOCK_GLASS_HIT, SoundCategory.BLOCKS, 1.0f, 0.85f, false);
            world.setBlockBreakingInfo(pos.hashCode(), pos, 6);
            for (int i = 0; i < 8; i++) {
                world.addParticle(ParticleTypes.CRIT, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                        (Math.random() - 0.5) * 0.25, (Math.random() - 0.5) * 0.25, (Math.random() - 0.5) * 0.25);
            }
        } else {
            world.playSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1.0f, 1.0f, false);
            world.setBlockBreakingInfo(pos.hashCode(), pos, -1);
            world.breakBlock(pos, false);
            GLASS_HITS.remove(pos);
        }
    }

    public static boolean isLaserActive() {
        return laserActive;
    }

    public static Vec3d getLaserStart() {
        return laserStart;
    }

    public static Vec3d getLaserEnd() {
        return laserEnd;
    }
}
