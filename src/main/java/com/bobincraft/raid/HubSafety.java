package com.bobincraft.raid;

import com.bobincraft.raid.guard.WarzoneGuardEntity;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BedBlock;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Bounded hub safety rules; no global difficulty, inventory, or world-border changes. */
public final class HubSafety {
    private HubSafety() { }

    public static boolean isHub(ServerWorld world) {
        return world.getChunkManager().getChunkGenerator() instanceof HubChunkGenerator;
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(entity.getWorld() instanceof ServerWorld world) || !isHub(world)
                        || !(entity instanceof PlayerEntity || entity.getCommandTags().contains("bobin_hub_merchant")
                        || entity instanceof WarzoneGuardEntity guard && guard.isHubGuard()));
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (isHub(world) && entity instanceof HostileEntity
                    && !(entity instanceof WarzoneGuardEntity guard && guard.isHubGuard())) entity.discard();
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world instanceof ServerWorld serverWorld && isHub(serverWorld)
                    && world.getBlockState(hit.getBlockPos()).getBlock() instanceof BedBlock) {
                player.sendMessage(Text.translatable("raid.bobin_craft.hub_bed"), true);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (!isHub(world) || world.getTime() % 20 != 0 || !RaidManager.isAvailable(world.getServer())) return;
            RaidPersistentState state = RaidPersistentState.get(world.getServer());
            if (state.hubDesign != 2) return;
            for (var player : world.getPlayers()) {
                if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
                if (Math.abs(player.getX()) <= HubLayout.HUB_RADIUS - 1
                        && Math.abs(player.getZ()) <= HubLayout.HUB_RADIUS - 1
                        && player.getY() >= HubLayout.FLOOR_Y - 2) continue;
                BlockPos safe = RaidManager.findSafeLanding(world, HubBuilder.SPAWN.getX(),
                        HubBuilder.SPAWN.getZ(), HubBuilder.SPAWN.getY(), 4);
                if (safe == null) continue;
                player.teleport(world, safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5, player.getYaw(), player.getPitch());
                player.setVelocity(Vec3d.ZERO);
                player.fallDistance = 0;
                player.sendMessage(Text.translatable("raid.bobin_craft.hub_boundary"), true);
            }
        });
    }
}
