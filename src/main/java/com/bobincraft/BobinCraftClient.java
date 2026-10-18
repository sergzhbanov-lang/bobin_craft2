package com.bobincraft;

import com.bobincraft.render.BuildingCutawayManager;
import com.bobincraft.raid.client.RaidClient;
import com.bobincraft.raid.HubContent;

import com.bobincraft.camera.BobinCameraState;
import com.bobincraft.movement.BobinMovementController;
import com.bobincraft.render.WorldRenderHandler;
import com.bobincraft.screen.BobinScreen;
import com.bobincraft.weapon.BobinWeaponHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class BobinCraftClient implements ClientModInitializer {
    public static final String MOD_ID = "bobin_craft";

    private static KeyBinding toggleKeyBinding;
    private static BobinMovementController movementController;
    private static Perspective previousPerspective = Perspective.FIRST_PERSON;

    @Override
    public void onInitializeClient() {
        RaidClient.register();
        BobinWeaponHandler.initializeControls();
        toggleKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.bobin_craft.toggle_bobin_mode",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "key.category.bobin_craft"
        ));

        // Ледяная сосулька полупрозрачная
        BlockRenderLayerMap.INSTANCE.putBlock(BobinCraft.ICICLE, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(HubContent.CAMOUFLAGE_NET, RenderLayer.getCutout());

        movementController = new BobinMovementController();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Visibility caches also follow disconnects/dimension changes while a GUI is open.
            BuildingCutawayManager.tick(client);
            if (client.player == null || client.world == null) {
                BobinWeaponHandler.tick(client, null);
                movementController.clearTarget();
                if (BobinCameraState.isActive()) {
                    BobinCameraState.setActive(false);
                    client.options.setPerspective(previousPerspective);
                    if (client.currentScreen instanceof BobinScreen) client.setScreen(null);
                }
                WorldRenderHandler.tick(client);
                return;
            }

            // Клавиша G для первичного входа в режим (когда экран еще не открыт)
            while (toggleKeyBinding.wasPressed()) {
                if (!BobinCameraState.isActive()) {
                    toggleBobinMode(client);
                }
            }

            // Автоматическое возвращение BobinScreen при закрытии инвентаря или сундука
            if (BobinCameraState.isActive() && client.currentScreen == null) {
                client.setScreen(new BobinScreen());
            }

            if (!client.isPaused()) {
                if (BobinCameraState.isActive()) {
                    BobinCameraState.tick();
                    if (client.currentScreen instanceof BobinScreen && client.isWindowFocused()) {
                        movementController.tick(client);
                    } else {
                        movementController.clearTarget();
                    }
                }
                BobinWeaponHandler.tick(client, WorldRenderHandler.getHoveredHit());
                WorldRenderHandler.tick(client);
            }
        });

        WorldRenderEvents.AFTER_TRANSLUCENT.register(WorldRenderHandler::render);
    }

    public static void toggleBobinMode(MinecraftClient client) {
        boolean newState = !BobinCameraState.isActive();
        BobinCameraState.setActive(newState);

        if (newState) {
            movementController.clearTarget();
            previousPerspective = client.options.getPerspective();
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);

            client.setScreen(new BobinScreen());
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("text.bobin_craft.mode_enabled"), true);
            }
        } else {
            if (client.currentScreen instanceof BobinScreen) {
                client.setScreen(null);
            }
            client.options.setPerspective(previousPerspective);

            if (client.mouse != null && client.currentScreen == null) {
                client.mouse.lockCursor();
            }
            BuildingCutawayManager.clearCutaway(client);
            movementController.clearTarget();
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("text.bobin_craft.mode_disabled"), true);
            }
        }
    }

    public static BobinMovementController getMovementController() {
        return movementController;
    }

    public static boolean isToggleKey(int keyCode, int scanCode) {
        return toggleKeyBinding.matchesKey(keyCode, scanCode);
    }
}
