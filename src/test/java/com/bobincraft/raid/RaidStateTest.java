package com.bobincraft.raid;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RaidStateTest {
    private static final List<DeploymentPlanner.Point> CHOICES = List.of(
            new DeploymentPlanner.Point(1, -90, 71, -80, 12, "Low profile"),
            new DeploymentPlanner.Point(2, 90, 72, -75, 41, "Balanced"),
            new DeploymentPlanner.Point(3, 4, 73, 90, 82, "High stakes"));

    @Test
    void reloadPreservesHubChoicesRaidDiscoveriesAndEncounterConsumption() {
        UUID uuid = UUID.fromString("2c0174ed-6454-4e61-a610-b282bfce699f");
        RaidPersistentState state = new RaidPersistentState();
        state.hubInitialized = true;
        state.hubDesign = 2;
        state.extractionMarked = true;
        state.worldSeed = -711234L;
        state.activatedEncounters.addAll(List.of(2, 7));
        var raid = state.player(uuid);
        raid.initialized = true;
        raid.active = true;
        raid.rotation = 13;
        raid.selected = 2;
        raid.completed = 5;
        raid.choices = CHOICES;
        raid.discovered.add(7);

        NbtCompound saved = state.writeNbt(new NbtCompound());
        RaidPersistentState restored = RaidPersistentState.read(saved);
        var reloaded = restored.player(uuid);
        assertTrue(restored.hubInitialized);
        assertEquals(2, restored.hubDesign);
        assertTrue(restored.extractionMarked);
        assertEquals(-711234L, restored.worldSeed);
        assertEquals(state.activatedEncounters, restored.activatedEncounters);
        assertTrue(reloaded.active);
        assertTrue(reloaded.initialized);
        assertEquals(13, reloaded.rotation);
        assertEquals(2, reloaded.selected);
        assertEquals(5, reloaded.completed);
        assertEquals(CHOICES, reloaded.choices);
        assertEquals(raid.discovered, reloaded.discovered);
        assertEquals(saved, restored.writeNbt(new NbtCompound()));
    }

    @Test
    void legacyInitializedHubRemainsLegacyWithoutRequiringARebuild() {
        NbtCompound saved = new NbtCompound();
        saved.putInt("Version", 1);
        saved.putBoolean("HubInitialized", true);
        RaidPersistentState legacy = RaidPersistentState.read(saved);
        assertTrue(legacy.isSupported());
        assertTrue(legacy.hubInitialized);
        assertEquals(0, legacy.hubDesign);
    }

    @Test
    void completionAndDeathRefreshOnlyTheAffectedPlayerCycle() {
        RaidPersistentState state = new RaidPersistentState();
        var a = state.player(UUID.randomUUID());
        var b = state.player(UUID.randomUUID());
        a.active = true; a.choices = CHOICES; a.selected = 3;
        b.active = true; b.choices = CHOICES; b.rotation = 8;
        a.finish(true);
        assertFalse(a.active);
        assertEquals(-1, a.selected);
        assertEquals(1, a.completed);
        assertEquals(1, a.rotation);
        assertTrue(a.choices.isEmpty());
        assertEquals(CHOICES, b.choices);
        assertEquals(8, b.rotation);
        b.finish(false);
        assertEquals(0, b.completed);
        assertEquals(9, b.rotation);
    }

    @Test
    void unsupportedFutureSaveFormatDisablesInitializationAndPreservesAllUnknownData() {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("Version", RaidPersistentState.VERSION + 1);
        nbt.putString("FutureField", "Preserve this progress");
        RaidPersistentState state = RaidPersistentState.read(nbt);
        assertFalse(state.isSupported());
        assertEquals(nbt, state.writeNbt(new NbtCompound()));
    }

    @Test
    void mapPacketRoundTripPreservesCoordinatesAndRotation() {
        int[] pixels = new int[96 * 96];
        java.util.Arrays.fill(pixels, 0xff526644);
        TacticalMapData map = new TacticalMapData(-128, 256, 96, pixels,
                List.of(new TacticalMapData.Marker(-20, 30, 80, "Bunker")),
                List.of(new TacticalMapData.Segment(-100, -80, 70, 60)), CHOICES,
                new DeploymentPlanner.Point(0, 0, 71, 100, 0, "Extraction"), 19);
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            map.write(buf);
            TacticalMapData read = TacticalMapData.read(buf);
            assertArrayEquals(pixels, read.colors());
            assertEquals(map.deployments(), read.deployments());
            assertEquals(map.extraction(), read.extraction());
            assertEquals(map.pois(), read.pois());
            assertEquals(map.roads(), read.roads());
            assertEquals(19, read.rotation());
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }

    @Test
    void oversizedNetworkMapIsRejectedBeforePixelAllocation() {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            buf.writeInt(-128); buf.writeVarInt(256); buf.writeVarInt(Integer.MAX_VALUE);
            assertThrows(IllegalArgumentException.class, () -> TacticalMapData.read(buf));
        } finally { buf.release(); }
    }
}
