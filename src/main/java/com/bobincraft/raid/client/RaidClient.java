package com.bobincraft.raid.client;

import com.bobincraft.BobinCraftClient;
import com.bobincraft.raid.RaidNetworking;
import com.bobincraft.raid.TacticalMapData;
import com.bobincraft.raid.WarzoneEncounterManager;
import com.bobincraft.screen.BobinScreen;
import com.bobincraft.warzone.WarzoneDimension;
import com.bobincraft.weapon.BobinWeaponHandler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Client-only registration. The field key works in first person and the existing camera screen. */
public final class RaidClient {
    private static KeyBinding mapKey;
    private static long nextRequestAt;

    private RaidClient() {}

    public static void reportGunfire() {
        if (ClientPlayNetworking.canSend(RaidNetworking.GUNFIRE)) {
            ClientPlayNetworking.send(RaidNetworking.GUNFIRE, PacketByteBufs.create());
        }
    }

    public static boolean matchesMapKey(int keyCode, int scanCode) {
        return mapKey != null && mapKey.matchesKey(keyCode, scanCode);
    }

    public static boolean matchesMapMouse(int button) {
        return mapKey != null && mapKey.matchesMouse(button);
    }

    static boolean mapKeyHeld(MinecraftClient client) {
        if (mapKey == null || client.getWindow() == null) return false;
        InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(mapKey);
        if (bound.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(client.getWindow().getHandle(), bound.getCode()) == GLFW.GLFW_PRESS;
        }
        return bound.getCategory() == InputUtil.Type.KEYSYM && bound.getCode() != GLFW.GLFW_KEY_UNKNOWN
                && InputUtil.isKeyPressed(client.getWindow().getHandle(), bound.getCode());
    }

    static Text mapKeyName() {
        return mapKey == null ? Text.literal("M") : mapKey.getBoundKeyLocalizedText();
    }

    private static boolean requestFieldMap(MinecraftClient client) {
        if (client.player == null || client.world == null || !WarzoneDimension.isWarzone(client.world)
                || !ClientPlayNetworking.canSend(RaidNetworking.REQUEST_FIELD_MAP)) return false;
        // Match the server's short throttle; repeated key presses cannot queue map work.
        long now = net.minecraft.util.Util.getMeasuringTimeMs();
        if (now < nextRequestAt) return true;
        nextRequestAt = now + 500;
        stopPlayerActions();
        ClientPlayNetworking.send(RaidNetworking.REQUEST_FIELD_MAP, PacketByteBufs.create());
        return true;
    }

    private static void stopPlayerActions() {
        BobinWeaponHandler.stopShooting();
        if (BobinCraftClient.getMovementController() != null) {
            BobinCraftClient.getMovementController().clearTarget();
        }
    }

    public static void register() {
        EntityRendererRegistry.register(WarzoneEncounterManager.GUARD, WarzoneGuardRenderer::new);
        mapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.bobin_craft.tactical_map",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.category.bobin_craft"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (mapKey.wasPressed()) {
                if (client.currentScreen == null) requestFieldMap(client);
            }
        });
        // Bobin mode is an interactive Screen: normal gameplay bindings do not receive its keys.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof BobinScreen)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((current, key, scan, modifiers) ->
                    !matchesMapKey(key, scan) || !requestFieldMap(client));
            ScreenMouseEvents.allowMouseClick(screen).register((current, x, y, button) ->
                    !matchesMapMouse(button) || !requestFieldMap(client));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            nextRequestAt = 0;
            TacticalMapScreen.clearRememberedView();
            TacticalMapTextureCache.clear(client);
        });
        ClientPlayNetworking.registerGlobalReceiver(RaidNetworking.OPEN_MAP,
                (client, handler, buffer, responseSender) -> {
                    TacticalMapData map = TacticalMapData.read(buffer);
                    if (map.fieldMap()) return;
                    client.execute(() -> {
                        if (client.player == null || client.world == null || WarzoneDimension.isWarzone(client.world)) return;
                        stopPlayerActions();
                        client.setScreen(new TacticalMapScreen(map));
                    });
                });
        ClientPlayNetworking.registerGlobalReceiver(RaidNetworking.OPEN_FIELD_MAP,
                (client, handler, buffer, responseSender) -> {
                    TacticalMapData map = TacticalMapData.read(buffer);
                    if (!map.fieldMap()) return;
                    client.execute(() -> {
                        if (client.player == null || client.world == null || !WarzoneDimension.isWarzone(client.world)) return;
                        if (client.currentScreen != null && !(client.currentScreen instanceof BobinScreen)
                                && !(client.currentScreen instanceof RaidTacticalMapScreen)) return;
                        stopPlayerActions();
                        client.setScreen(new RaidTacticalMapScreen(map));
                    });
                });
    }
}
