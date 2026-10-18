package com.bobincraft.screen;

import com.bobincraft.render.BuildingCutawayManager;
import com.bobincraft.render.FogOfWarManager;

import com.bobincraft.BobinCraftClient;
import com.bobincraft.raid.HubContent;
import com.bobincraft.camera.BobinCameraState;
import com.bobincraft.input.RaycastHelper;
import com.bobincraft.movement.BobinMovementController;
import com.bobincraft.render.WorldRenderHandler;
import com.bobincraft.weapon.BobinWeaponHandler;
import com.bobincraft.weapon.GunType;
import net.minecraft.block.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class BobinScreen extends Screen {

    private static final Identifier WIDGETS_TEXTURE = new Identifier("minecraft", "textures/gui/widgets.png");
    private boolean switchingScreen = false;
    // Режим прицеливания броска (Z / Alt) — ТОЛЬКО 100% АВТОРАСЧЕТ
    private boolean isAimingThrow = false;

    private boolean currentThrowInRange = true;
    private float calculatedThrowYaw = 0.0f;
    private float calculatedThrowPitch = 0.0f;

    public BobinScreen() {
        super(Text.literal("BobinScreen"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void renderBackground(DrawContext context) {
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        // Динамическое обнаружение зданий и полупрозрачность крыши (Roof Cutaway)
        BuildingCutawayManager.tick(client);

        if (client != null && client.player != null) {
            // Трассировка луча до 150 блоков с гарантированным наведением
            // Попадание курсора считается в WorldRenderHandler.render() по камере ЭТОГО кадра (без отставания)
            HitResult hit = WorldRenderHandler.getHoveredHit();
            if (hit == null) {
                hit = RaycastHelper.raycastFromCursor(client, 150.0);
                WorldRenderHandler.setHoveredHit(hit);
            }

            // Плавный разворот персонажа за курсором каждый кадр (даже без оружия в руках)
            BobinMovementController moveCtrl = BobinCraftClient.getMovementController();
            if (moveCtrl != null) {
                moveCtrl.updateCursorLookFrame(client, hit, isAimingThrow);
            }

            // Логика оружия (АК-47 / Снайперская винтовка)
            BobinWeaponHandler.updateAimFrame(client, hit);

            // Сброс снайперского зума при смене оружия
            if (BobinCameraState.isSniperScopeActive() && !BobinWeaponHandler.isHoldingSniper(client.player)) {
                BobinCameraState.setSniperScopeActive(false);
            }

            // Динамический авторасчет дуги броска к целевому блоку или мобу
            if (isAimingThrow) {
                updateAutoThrowAiming(hit);
            } else {
                WorldRenderHandler.clearTrajectory();
            }

            renderHotbarHUD(context, mouseX, mouseY);
            renderWeaponHUD(context, client);
        }

        // Подсказки управления на экране
        String aimText = isAimingThrow ?
                (currentThrowInRange ? "§a[БРОСОК]: Клик ЛКМ для броска в цель | " : "§c[БРОСОК]: Не хватает дальности! | ") :
                "§7[Z/Alt] - Прицел броска | ";

        String weaponHint = "";
        if (client != null && client.player != null && BobinWeaponHandler.isHoldingSniper(client.player)) {
            weaponHint = BobinCameraState.isSniperScopeActive() ? "§b[X: Прицел ВКЛ] §f| " : "§7[X: Режим прицела] §f| ";
        }

        context.drawTextWithShadow(
                client.textRenderer,
                "§6[Bobin_craft] §f" + aimText + weaponHint + "§7[Q/E: Поворот] §f| §7[H: Крыши] §f| §7[B: Инвентарь] §f| §7[T: Выбросить] §f| WASD: Move | Screen edges: Camera | Shift/Ctrl: Height | Space/C: Center | RMB: Interact | [Esc]: Exit",
                10, 10, 0xFFFFFF
        );
    }

    /**
     * Отображение состояния патронов и шкалы перезарядки активного оружия
     */
    public static void renderWeaponHUD(DrawContext context, MinecraftClient client) {
        if (client == null || client.player == null) return;
        if (!BobinWeaponHandler.isHoldingWeapon(client.player)) return;

        int hudX = (client.getWindow().getScaledWidth() - 182) / 2;
        int hudY = client.getWindow().getScaledHeight() - 36;
        boolean isSniper = BobinWeaponHandler.isHoldingSniper(client.player);

        if (BobinWeaponHandler.isReloading()) {
            int ticksLeft = BobinWeaponHandler.getReloadTicks();
            int total = BobinWeaponHandler.getReloadDuration();
            float progress = 1.0f - ((float) ticksLeft / total);
            int barWidth = 80;
            int filled = (int) (progress * barWidth);

            // Фон шкалы
            context.fill(hudX, hudY, hudX + barWidth + 4, hudY + 10, 0xAA000000);
            // Заполнение шкалы
            GunType hudGun = BobinWeaponHandler.getHeldGun(client.player);
            int barColor = hudGun != null ? (0xFF000000 | hudGun.color) : (isSniper ? 0xFF00D4FF : 0xFFFFAA00);
            context.fill(hudX + 2, hudY + 2, hudX + 2 + filled, hudY + 8, barColor);

            context.drawTextWithShadow(
                    client.textRenderer,
                    "§eПЕРЕЗАРЯДКА... §f" + String.format("%.1f", ticksLeft / 20.0f) + "с",
                    hudX + barWidth + 10, hudY + 1, 0xFFFFFF
            );
        } else {
            int ammo = BobinWeaponHandler.getCurrentAmmo();
            int maxAmmo = BobinWeaponHandler.getMaxAmmo();
            String ammoColor = ammo > 2 ? "§a" : (ammo > 0 ? "§e" : "§c");

            GunType hudGun = BobinWeaponHandler.getHeldGun(client.player);
            String weaponTag = hudGun != null ? hudGun.hudTag : (isSniper ? "§b[Снайперка] §f" : "§6[АК-47] §f");
            String extraHint = isSniper ? " §8([X] - Прицел, [R] - Перезарядка)" : " §8([R] - Перезарядка)";

            if (isSniper && BobinWeaponHandler.getShotCooldown() > 0) {
                extraHint += " §c(КД: " + String.format("%.1f", BobinWeaponHandler.getShotCooldown() / 20.0f) + "с)";
            }

            String ammoText = weaponTag + "Патроны: " + ammoColor + ammo + "§7/§f" + maxAmmo + extraHint;

            if (ammo == 0) {
                ammoText = (hudGun != null ? "§c" + hudGun.hudTag.substring(2) : (isSniper ? "§c[Снайперка]: " : "§c[АК-47]: ")) + "ПУСТО! Нажмите [R] для перезарядки";
            }
            context.drawTextWithShadow(client.textRenderer, ammoText, hudX, hudY + 1, 0xFFFFFF);
        }
    }

    /**
     * 100% автоматический расчет баллистической параболы к выбранному блоку или существу
     * с компенсацией сопротивления воздуха Minecraft (v0 = 1.5, g = 0.03, drag = 0.99).
     */
    private void updateAutoThrowAiming(HitResult hit) {
        if (client == null || client.player == null || client.world == null) return;

        BobinMovementController controller = BobinCraftClient.getMovementController();
        if (controller != null) {
            controller.clearTarget();
        }

        Vec3d targetPos;
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            targetPos = hit.getPos();
        } else {
            targetPos = client.player.getEyePos().add(client.player.getRotationVec(1.0f).multiply(25.0));
        }

        Vec3d startPos = client.player.getEyePos().add(0, -0.1, 0);

        double dx = targetPos.x - startPos.x;
        double dy = targetPos.y - startPos.y;
        double dz = targetPos.z - startPos.z;
        double distH = Math.hypot(dx, dz);

        // Поворот персонажа точно к целевому блоку
        calculatedThrowYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        client.player.setYaw(calculatedThrowYaw);
        client.player.headYaw = calculatedThrowYaw;
        client.player.bodyYaw = calculatedThrowYaw;

        // Физический расчет баллистики (v0 = 1.5, g = 0.03, drag = 0.99)
        double v0 = 1.5;
        double g = 0.03;
        double drag = 0.99;
        double v0Sq = v0 * v0;
        double discriminant = v0Sq * v0Sq - g * (g * distH * distH + 2.0 * dy * v0Sq);

        if (discriminant >= 0 && distH <= 40.0) {
            // Начальная аппроксимация по вакуумной формуле
            double root = Math.sqrt(discriminant);
            double angleRad = Math.atan2(v0Sq - root, g * distH);
            float approxPitch = (float) (-Math.toDegrees(angleRad));

            // Двоичный поиск для 100% точного попадания в цель с учетом сопротивления воздуха 0.99
            float low = Math.max(-85.0f, approxPitch - 25.0f);
            float high = Math.min(65.0f, approxPitch + 15.0f);

            for (int iter = 0; iter < 18; iter++) {
                float mid = (low + high) * 0.5f;
                double simY = simulateThrowY(mid, distH, v0, g, drag);
                if (simY > dy) {
                    low = mid;
                } else {
                    high = mid;
                }
            }

            calculatedThrowPitch = (low + high) * 0.5f;
            calculatedThrowPitch = MathHelper.clamp(calculatedThrowPitch, -85.0f, 65.0f);

            double finalY = simulateThrowY(calculatedThrowPitch, distH, v0, g, drag);
            currentThrowInRange = Math.abs(finalY - dy) < 0.85;
        } else {
            // Цель дальше 40 блоков — максимальная дальность броска (красная дуга)
            currentThrowInRange = false;
            calculatedThrowPitch = -45.0f;
        }

        client.player.setPitch(calculatedThrowPitch);

        // Построение точек траектории дуги
        List<Vec3d> arc = new ArrayList<>();
        Vec3d simPos = startPos;

        double radPitch = Math.toRadians(calculatedThrowPitch);
        double radYaw = Math.toRadians(calculatedThrowYaw);

        double vx = -Math.sin(radYaw) * Math.cos(radPitch) * v0;
        double vy = -Math.sin(radPitch) * v0;
        double vz = Math.cos(radYaw) * Math.cos(radPitch) * v0;

        for (int step = 0; step < 70; step++) {
            arc.add(simPos);

            // Если попали в цель или подошли вплотную к блоку
            if (currentThrowInRange && simPos.distanceTo(targetPos) < 1.0) {
                arc.add(targetPos);
                break;
            }

            Vec3d nextPos = simPos.add(vx, vy, vz);

            // Проверка столкновения с твердым блоком (трава и цветы не обрывают дугу)
            BlockPos bp = BlockPos.ofFloored(nextPos);
            BlockState state = client.world.getBlockState(bp);
            if (step > 0 && !state.getCollisionShape(client.world, bp).isEmpty()) {
                arc.add(nextPos);
                break;
            }

            simPos = nextPos;
            vy -= g;
            vx *= drag;
            vy *= drag;
            vz *= drag;
        }

        WorldRenderHandler.setTrajectory(arc, currentThrowInRange, targetPos);
    }

    private double simulateThrowY(float pitchDeg, double distH, double v0, double g, double drag) {
        double radPitch = Math.toRadians(pitchDeg);
        double vx = Math.cos(radPitch) * v0;
        double vy = -Math.sin(radPitch) * v0;
        double x = 0.0;
        double y = 0.0;

        for (int step = 0; step < 70; step++) {
            double prevX = x;
            double prevY = y;
            x += vx;
            y += vy;
            vy -= g;
            vx *= drag;
            vy *= drag;

            if (prevX <= distH && distH <= x) {
                double t = (x != prevX) ? (distH - prevX) / (x - prevX) : 0.0;
                return prevY + t * (y - prevY);
            }
            if (x > distH + 2.0) {
                break;
            }
        }
        return y;
    }

    private void renderHotbarHUD(DrawContext context, int mouseX, int mouseY) {
        if (client.player == null) return;

        int hotbarWidth = 182;
        int hotbarX = (this.width - hotbarWidth) / 2;
        int hotbarY = this.height - 22;

        context.drawTexture(WIDGETS_TEXTURE, hotbarX, hotbarY, 0, 0, 182, 22);

        int selectedSlot = client.player.getInventory().selectedSlot;
        int selectX = hotbarX - 1 + selectedSlot * 20;
        context.drawTexture(WIDGETS_TEXTURE, selectX, hotbarY - 1, 0, 22, 24, 24);

        ItemStack offHandStack = client.player.getOffHandStack();
        if (!offHandStack.isEmpty()) {
            int offX = hotbarX - 29;
            int offY = hotbarY - 1;
            context.drawTexture(WIDGETS_TEXTURE, offX, offY, 24, 22, 29, 24);
            context.drawItem(offHandStack, offX + 3, hotbarY + 3);
            context.drawItemInSlot(client.textRenderer, offHandStack, offX + 3, hotbarY + 3);
        }

        ItemStack hoveredStack = ItemStack.EMPTY;

        for (int i = 0; i < 9; i++) {
            int x = hotbarX + 3 + i * 20;
            int y = hotbarY + 3;
            ItemStack stack = client.player.getInventory().getStack(i);

            if (!stack.isEmpty()) {
                context.drawItem(stack, x, y);
                context.drawItemInSlot(client.textRenderer, stack, x, y);
            }

            String slotNum = String.valueOf(i + 1);
            int numColor = (i == selectedSlot) ? 0xFFFF55 : 0xAAAAAA;
            context.drawText(client.textRenderer, slotNum, x + 1, y - 8, numColor, true);

            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18 && !stack.isEmpty()) {
                hoveredStack = stack;
            }
        }

        if (!hoveredStack.isEmpty()) {
            context.drawItemTooltip(client.textRenderer, hoveredStack, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (client == null || client.player == null) return false;
        if (BobinWeaponHandler.handleMouse(client, button)) return true;

        // 1. Клик по слотам хотбара
        int hotbarWidth = 182;
        int hotbarX = (this.width - hotbarWidth) / 2;
        int hotbarY = this.height - 22;

        if (mouseY >= hotbarY - 2 && mouseY <= hotbarY + 24) {
            if (mouseX >= hotbarX && mouseX < hotbarX + 180) {
                int clickedSlot = (int) ((mouseX - hotbarX) / 20);
                if (clickedSlot >= 0 && clickedSlot < 9) {
                    selectHotbarSlot(clickedSlot);
                    return true;
                }
            }
        }

        // 2. Обработка режима авто-броска (Z / Alt)
        if (isAimingThrow) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                // Бросок точно в цель по рассчитанной дуге
                client.player.setYaw(calculatedThrowYaw);
                client.player.headYaw = calculatedThrowYaw;
                client.player.bodyYaw = calculatedThrowYaw;
                client.player.setPitch(calculatedThrowPitch);

                ActionResult result = client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                if (!result.isAccepted()) {
                    client.interactionManager.interactItem(client.player, Hand.OFF_HAND);
                }
                client.player.swingHand(Hand.MAIN_HAND);
                return true;
            } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                // ПКМ мгновенно отменяет режим броска
                isAimingThrow = false;
                WorldRenderHandler.clearTrajectory();
                client.player.setPitch(0.0f);
                return true;
            }
        }

        HitResult hit = RaycastHelper.raycastFromCursor(client, 150.0);

        // 3. Стрельба из оружия на ЛКМ (АК-47 / Снайперская винтовка)
        if (BobinWeaponHandler.isHoldingWeapon(client.player) && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            BobinWeaponHandler.startShooting(client, hit);
            return true;
        }

        BobinMovementController controller = BobinCraftClient.getMovementController();
        if (controller == null) return false;

        // Retain existing left-click actions; right click only interacts within reach.
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                controller.setBreakGoal(client, (BlockHitResult) hit);
                WorldRenderHandler.spawnClickRipple(hit.getPos(), 0xFF3333);
                return true;
            } else if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
                controller.setAttackGoal(client, (EntityHitResult) hit);
                WorldRenderHandler.spawnClickRipple(hit.getPos(), 0xFF1111);
                return true;
            }
            return false;
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // Right click never assigns a path: native interactions require actual reach.
            controller.clearTarget();
            client.interactionManager.cancelBlockBreaking();
            BobinWeaponHandler.stopShooting();
            double reach = client.interactionManager.getReachDistance();
            if (hit instanceof EntityHitResult entityHit) {
                if (entityHit.getPos().squaredDistanceTo(client.player.getEyePos()) <= reach * reach
                        && client.player.canSee(entityHit.getEntity())) {
                    ActionResult result = client.interactionManager.interactEntityAtLocation(
                            client.player, entityHit.getEntity(), entityHit, Hand.MAIN_HAND);
                    if (!result.isAccepted()) result = client.interactionManager.interactEntity(
                            client.player, entityHit.getEntity(), Hand.MAIN_HAND);
                    if (!result.isAccepted()) client.interactionManager.interactEntity(
                            client.player, entityHit.getEntity(), Hand.OFF_HAND);
                    client.player.swingHand(Hand.MAIN_HAND);
                }
                return true;
            }
            if (hit instanceof BlockHitResult blockHit && hit.getType() != HitResult.Type.MISS) {
                if (hit.getPos().squaredDistanceTo(client.player.getEyePos()) <= reach * reach
                        && client.world.raycast(new net.minecraft.world.RaycastContext(
                                client.player.getEyePos(), blockHit.getPos(),
                                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                                net.minecraft.world.RaycastContext.FluidHandling.NONE, client.player))
                                .getBlockPos().equals(blockHit.getBlockPos())) {
                    for (Hand hand : Hand.values()) {
                        ActionResult result = client.interactionManager.interactBlock(client.player, hand, blockHit);
                        if (!result.isAccepted()) result = client.interactionManager.interactItem(client.player, hand);
                        if (result.isAccepted()) {
                            client.player.swingHand(hand);
                            return true;
                        }
                    }
                    return true;
                }
                if (isInteractiveBlock(client.world.getBlockState(blockHit.getBlockPos()))) return true;
            }
            ActionResult result = client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            if (!result.isAccepted()) client.interactionManager.interactItem(client.player, Hand.OFF_HAND);
            client.player.swingHand(Hand.MAIN_HAND);
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            BobinWeaponHandler.stopShooting();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void selectHotbarSlot(int slot) {
        if (client != null && client.player != null && slot >= 0 && slot < 9) {
            client.player.getInventory().selectedSlot = slot;
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
            }
        }
    }

    private boolean isInteractiveBlock(BlockState state) {
        Block block = state.getBlock();
        return state.isOf(HubContent.MAP_TABLE)
                || block instanceof BlockEntityProvider
                || block instanceof DoorBlock
                || block instanceof TrapdoorBlock
                || block instanceof FenceGateBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock
                || block instanceof CraftingTableBlock
                || block instanceof AnvilBlock
                || block instanceof LoomBlock
                || block instanceof CartographyTableBlock
                || block instanceof StonecutterBlock
                || block instanceof GrindstoneBlock
                || block instanceof SmithingTableBlock
                || block instanceof BedBlock
                || block instanceof BellBlock
                || block instanceof RespawnAnchorBlock
                || block instanceof LecternBlock
                || block instanceof RepeaterBlock
                || block instanceof ComparatorBlock
                || block instanceof DaylightDetectorBlock
                || block instanceof CakeBlock
                || block instanceof SweetBerryBushBlock;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        BobinCameraState.zoom(amount);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (client == null) return false;

        // Сброс камеры в исходное положение (центрирование на герое) по Пробелу или клавише C
        if (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_C) {
            BobinCameraState.resetPan();
            return true;
        }

        // Поглощение клавиш WASD и стрелок для исключения микрорывков системного автоповтора (key-repeat)
        if (keyCode == GLFW.GLFW_KEY_W || keyCode == GLFW.GLFW_KEY_S ||
                keyCode == GLFW.GLFW_KEY_A || keyCode == GLFW.GLFW_KEY_D ||
                keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN ||
                keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            return true;
        }

        if (BobinWeaponHandler.handleKey(client, keyCode, scanCode)) return true;

        // Включение/выключение режима прицеливания броска по Z или Alt
        if (keyCode == GLFW.GLFW_KEY_Z || keyCode == GLFW.GLFW_KEY_LEFT_ALT || keyCode == GLFW.GLFW_KEY_RIGHT_ALT) {
            isAimingThrow = !isAimingThrow;
            if (!isAimingThrow) {
                WorldRenderHandler.clearTrajectory();
                if (client.player != null) {
                    client.player.setPitch(0.0f);
                }
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE || BobinCraftClient.isToggleKey(keyCode, scanCode)) {
            BobinCraftClient.toggleBobinMode(client);
            return true;
        }

        // H — режим крыш: авто с контуром / авто без контура / выключено
        if (keyCode == GLFW.GLFW_KEY_H) {
            BuildingCutawayManager.cycleMode(client);
            if (client.player != null) {
                client.player.sendMessage(Text.literal(BuildingCutawayManager.getModeLabel()), true);
            }
            return true;
        }

        // V — туман войны вкл/выкл
        if (keyCode == GLFW.GLFW_KEY_V) {
            FogOfWarManager.toggle();
            if (client.player != null) {
                client.player.sendMessage(Text.literal(FogOfWarManager.getStateLabel()), true);
            }
            return true;
        }

        // Q / E — вращение камеры (обрабатывается покадрово в render), не должны попадать в другие действия
        if (keyCode == GLFW.GLFW_KEY_Q || keyCode == GLFW.GLFW_KEY_E) {
            return true;
        }

        // Выбор слотов 1-9
        for (int i = 0; i < 9; i++) {
            if (client.options.hotbarKeys[i].matchesKey(keyCode, scanCode)) {
                selectHotbarSlot(i);
                return true;
            }
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            selectHotbarSlot(keyCode - GLFW.GLFW_KEY_1);
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_KP_1 && keyCode <= GLFW.GLFW_KEY_KP_9) {
            selectHotbarSlot(keyCode - GLFW.GLFW_KEY_KP_1);
            return true;
        }

        // Инвентарь
        if (client.options.inventoryKey.matchesKey(keyCode, scanCode) || keyCode == GLFW.GLFW_KEY_B) {
            if (client.player != null) {
                this.switchingScreen = true;
                client.setScreen(new InventoryScreen(client.player));
                return true;
            }
        }

        // Выброс предмета
        if (client.options.dropKey.matchesKey(keyCode, scanCode) || keyCode == GLFW.GLFW_KEY_T) {
            if (client.player != null) {
                client.player.dropSelectedItem(hasControlDown());
                return true;
            }
        }

        // Смена рук
        if (client.options.swapHandsKey.matchesKey(keyCode, scanCode) || keyCode == GLFW.GLFW_KEY_F) {
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                        PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                        BlockPos.ORIGIN,
                        Direction.DOWN
                ));
                return true;
            }
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void removed() {
        BobinWeaponHandler.stopShooting();
        BobinCraftClient.getMovementController().clearTarget();
        super.removed();
    }

    @Override
    public void close() {
        BuildingCutawayManager.clearCutaway(client);
        isAimingThrow = false;
        WorldRenderHandler.clearTrajectory();
        BobinCameraState.setSniperScopeActive(false);
        if (!switchingScreen && client != null && BobinCameraState.isActive()) {
            BobinCraftClient.toggleBobinMode(client);
        }
        super.close();
    }
}
