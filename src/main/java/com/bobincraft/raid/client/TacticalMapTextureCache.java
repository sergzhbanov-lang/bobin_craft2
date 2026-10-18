package com.bobincraft.raid.client;

import com.bobincraft.raid.TacticalMapData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.Arrays;

/** One low-resolution terrain texture per client session, shared by both strategic interfaces. */
final class TacticalMapTextureCache {
    private static Identifier texture;
    private static int min, size, resolution;
    private static int[] colors;

    private TacticalMapTextureCache() {}

    static Identifier texture(MinecraftClient client, TacticalMapData map) {
        if (texture != null && min == map.min() && size == map.size() && resolution == map.resolution()
                && Arrays.equals(colors, map.colors())) return texture;
        clear(client);
        min = map.min(); size = map.size(); resolution = map.resolution();
        colors = map.colors().clone();
        NativeImage image = new NativeImage(resolution, resolution, false);
        for (int z = 0; z < resolution; z++) {
            for (int x = 0; x < resolution; x++) {
                int argb = colors[z * resolution + x] | 0xff000000;
                // NativeImage uses ABGR; packets use ordinary ARGB colors.
                int abgr = (argb & 0xff00ff00) | ((argb >>> 16) & 0xff) | ((argb & 0xff) << 16);
                image.setColor(x, z, abgr);
            }
        }
        texture = client.getTextureManager().registerDynamicTexture("bobin_raid_map", new NativeImageBackedTexture(image));
        return texture;
    }

    static void clear(MinecraftClient client) {
        if (texture != null) client.getTextureManager().destroyTexture(texture);
        texture = null;
        colors = null;
    }
}
