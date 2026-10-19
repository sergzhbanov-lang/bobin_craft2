package com.bobincraft.weapon;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/** A compiled input mixin does nothing unless Fabric actually loads it. */
class WeaponInputRegistrationTest {
    @Test
    void riverTextureReloadHookIsEnabledOnlyOnClients() throws Exception {
        try (var resource = getClass().getResourceAsStream("/bobin_craft.mixins.json")) {
            assertNotNull(resource);
            var config = JsonParser.parseReader(new InputStreamReader(resource, StandardCharsets.UTF_8)).getAsJsonObject();
            assertTrue(StreamSupport.stream(config.getAsJsonArray("client").spliterator(), false)
                    .anyMatch(mixin -> mixin.getAsString().equals("FluidRendererMixin")),
                    "River water textures must survive the initial texture load and resource reloads");
            assertFalse(StreamSupport.stream(config.getAsJsonArray("mixins").spliterator(), false)
                    .anyMatch(mixin -> mixin.getAsString().equals("FluidRendererMixin")),
                    "Fluid rendering must never load on a dedicated server");
        }
    }

    @Test
    void ordinaryViewAttackHookIsEnabledInThePackagedClientConfiguration() throws Exception {
        try (var resource = getClass().getResourceAsStream("/bobin_craft.mixins.json")) {
            assertNotNull(resource, "The mod must package its mixin configuration");
            var config = JsonParser.parseReader(new InputStreamReader(resource, StandardCharsets.UTF_8)).getAsJsonObject();
            var clientMixins = config.getAsJsonArray("client");
            assertTrue(StreamSupport.stream(clientMixins.spliterator(), false)
                            .anyMatch(mixin -> mixin.getAsString().equals("MinecraftClientMixin")),
                    "LMB must reach weapon handling without entering the G camera mode");
            assertFalse(StreamSupport.stream(config.getAsJsonArray("mixins").spliterator(), false)
                            .anyMatch(mixin -> mixin.getAsString().equals("MinecraftClientMixin")),
                    "MinecraftClient input must never load on a dedicated server");
        }
    }
}
