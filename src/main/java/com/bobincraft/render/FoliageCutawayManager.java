package com.bobincraft.render;

import com.bobincraft.camera.BobinCameraState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Small camera corridor, sampled at most four times a second, never a forest-wide scan. */
public final class FoliageCutawayManager {
    private static volatile Set<Long> hidden = Set.of();
    private static ClientWorld sourceWorld;
    private static long lastTick = Long.MIN_VALUE;
    private static long lastResample = Long.MIN_VALUE;
    private static BlockPos lastPlayer;
    private static Vec3d lastCamera;

    private FoliageCutawayManager() { }

    public static boolean isHidden(BlockPos pos) { return hidden.contains(pos.asLong()); }

    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null || !BobinCameraState.isActive()) {
            clear(client);
            return;
        }
        if (sourceWorld != client.world) {
            clear(client);
            sourceWorld = client.world;
        }
        long now = client.world.getTime();
        if (lastTick != Long.MIN_VALUE && now >= lastTick && now - lastTick < 5) return;
        lastTick = now;
        BlockPos player = client.player.getBlockPos();
        Vec3d camera = client.gameRenderer.getCamera().getPos();
        if (player.equals(lastPlayer) && lastCamera != null && camera.squaredDistanceTo(lastCamera) < 2.25
                && lastResample != Long.MIN_VALUE && now >= lastResample && now - lastResample < 40) return;
        lastResample = now;
        lastPlayer = player;
        lastCamera = camera;
        Set<Long> next = new HashSet<>();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        Vec3d eye = client.player.getEyePos();
        double height = camera.y - eye.y;
        for (int y = player.getY() + 2; y <= player.getY() + 26; y++) {
            double t = height <= 0 ? 0 : Math.max(0, Math.min(1, (y - eye.y) / height));
            int cx = (int) Math.floor(eye.x + Math.max(-32, Math.min(32, camera.x - eye.x)) * t);
            int cz = (int) Math.floor(eye.z + Math.max(-32, Math.min(32, camera.z - eye.z)) * t);
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                if (dx * dx + dz * dz > 16) continue;
                pos.set(cx + dx, y, cz + dz);
                if (client.world.getBlockState(pos).isIn(BlockTags.LEAVES)) next.add(pos.asLong());
            }
        }
        if (next.equals(hidden)) return;
        Set<Long> changed = new HashSet<>(hidden);
        changed.addAll(next);
        Set<Long> same = new HashSet<>(hidden);
        same.retainAll(next);
        changed.removeAll(same);
        hidden = Collections.unmodifiableSet(next);
        rebuild(client, changed);
    }

    public static void clear(MinecraftClient client) {
        Set<Long> old = hidden;
        hidden = Set.of();
        sourceWorld = null;
        lastPlayer = null;
        lastCamera = null;
        lastTick = Long.MIN_VALUE;
        lastResample = Long.MIN_VALUE;
        rebuild(client, old);
    }

    private static void rebuild(MinecraftClient client, Set<Long> changed) {
        if (client == null || client.worldRenderer == null || changed.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long p : changed) {
            int x = BlockPos.unpackLongX(p), y = BlockPos.unpackLongY(p), z = BlockPos.unpackLongZ(p);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        client.worldRenderer.scheduleBlockRenders(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1);
    }
}
