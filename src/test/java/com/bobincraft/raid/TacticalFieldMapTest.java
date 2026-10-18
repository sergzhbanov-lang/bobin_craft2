package com.bobincraft.raid;

import com.bobincraft.warzone.WarzoneTerrain;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TacticalFieldMapTest {
    @Test
    void fieldPacketCannotContainDeploymentOptionsOrAuthorizeTeleportation() {
        var point = new DeploymentPlanner.Point(1, 0, 71, 0, 20, "Start");
        assertThrows(IllegalArgumentException.class, () -> new TacticalMapData(-128, 256, 96,
                new int[96 * 96], List.of(), List.of(), List.of(point), null, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new TacticalMapData(-128, 256, 96,
                new int[96 * 96], List.of(), List.of(), List.of(), null, 123, true));
        // A deployment packet still requires its complete triple and extraction point.
        assertThrows(IllegalArgumentException.class, () -> new TacticalMapData(-128, 256, 96,
                new int[96 * 96], List.of(), List.of(), List.of(),
                new DeploymentPlanner.Point(0, 0, 71, 100, 0, "Extraction"), 0));
    }

    @Test
    void fieldPacketEndsAfterMapInformationWithoutASerializedDeploymentToken() {
        var map = new TacticalMapData(-128, 256, 96, new int[96 * 96],
                List.of(), List.of(), List.of(), null, 0, true);
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            map.write(buffer);
            assertEquals(-128, buffer.readInt());
            assertEquals(256, buffer.readVarInt());
            assertEquals(96, buffer.readVarInt());
            int pixels = buffer.readVarInt();
            assertEquals(96 * 96, pixels);
            buffer.skipBytes(pixels * Integer.BYTES);
            assertEquals(0, buffer.readVarInt()); // known landmarks
            assertEquals(0, buffer.readVarInt()); // roads
            assertTrue(buffer.readBoolean()); // read-only field map
            assertEquals(0, buffer.readVarInt()); // no deployment choices
            assertFalse(buffer.readBoolean()); // extraction not yet revealed
            assertEquals(0, buffer.readableBytes(), "A field packet must have no deployment token tail");
        } finally {
            buffer.release();
        }
    }

    @Test
    void readOnlyFieldPacketRoundTripAllowsUnrevealedExtractionAndRejectsTrailingData() {
        var map = new TacticalMapData(-128, 256, 96, new int[96 * 96],
                List.of(new TacticalMapData.Marker(5, 9, 40, "Discovered camp")), List.of(), List.of(), null, 0, true);
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer());
        try {
            map.write(buffer);
            int length = buffer.readableBytes();
            var restored = TacticalMapData.read(buffer);
            assertTrue(restored.fieldMap());
            assertTrue(restored.deployments().isEmpty());
            assertEquals(0, restored.rotation());
            assertNull(restored.extraction());
            assertEquals(map.pois(), restored.pois());
            buffer.readerIndex(0);
            buffer.writerIndex(length);
            buffer.writeByte(1);
            assertThrows(IllegalArgumentException.class, () -> TacticalMapData.read(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void discoveriesAreFilteredBeforeSendingAndPersistWithoutSavingPlayerPositions() {
        WarzoneTerrain terrain = new WarzoneTerrain(2007L, 1024);
        var undiscovered = TacticalMapData.fieldMap(terrain, Set.of(), null);
        assertTrue(undiscovered.pois().isEmpty(), "Unvisited landmarks must not appear in the network payload");
        Set<Integer> discoveries = new HashSet<>();
        for (var site : terrain.basePlan().sites()) TacticalMapData.discover(terrain, site.cx, site.cz, discoveries);
        for (var hideout : terrain.hideoutPlan().hideouts()) TacticalMapData.discover(terrain, hideout.cx(), hideout.cz(), discoveries);
        for (var checkpoint : terrain.poiPlan().checkpoints()) TacticalMapData.discover(terrain, checkpoint.cx(), checkpoint.cz(), discoveries);
        for (var field : terrain.poiPlan().battlefields()) TacticalMapData.discover(terrain, field.cx(), field.cz(), discoveries);
        assertFalse(discoveries.isEmpty(), "Fixture should contain real generated landmarks");
        var known = TacticalMapData.fieldMap(terrain, discoveries, null);
        assertFalse(known.pois().isEmpty());
        assertArrayEquals(undiscovered.colors(), known.colors(), "Discoveries reuse the same sampled terrain");
        UUID uuid = UUID.fromString("9973b0ab-eab1-4229-98d0-37578141dedb");
        RaidPersistentState state = new RaidPersistentState();
        state.player(uuid).discovered.addAll(discoveries);
        RaidPersistentState restored = RaidPersistentState.read(state.writeNbt(new net.minecraft.nbt.NbtCompound()));
        assertEquals(discoveries, restored.player(uuid).discovered);
        assertEquals(known.pois(), TacticalMapData.fieldMap(terrain, restored.player(uuid).discovered, null).pois());
    }

    @Test
    void extendedDiscoveryNamespacesDoNotCollideWithLegacyBaseIds() {
        assertTrue(TacticalMapData.hideoutDiscoveryId(1) < 0);
        assertNotEquals(TacticalMapData.hideoutDiscoveryId(1), TacticalMapData.checkpointDiscoveryId(1));
        assertNotEquals(TacticalMapData.checkpointDiscoveryId(1), TacticalMapData.battlefieldDiscoveryId(1));
    }
}
