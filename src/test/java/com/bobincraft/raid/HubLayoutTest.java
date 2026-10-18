package com.bobincraft.raid;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HubLayoutTest {
    @Test
    void allSixFunctionalZonesConnectToSpawnThroughShortDirtRoutes() {
        HubLayout.validate();
        assertEquals(6, HubLayout.Zone.values().length);
        Set<Long> reachable = new HashSet<>();
        var queue = new ArrayDeque<int[]>();
        queue.add(new int[]{0, 3});
        while (!queue.isEmpty()) {
            int[] p = queue.removeFirst();
            if (!HubLayout.isPath(p[0], p[1]) || !reachable.add(key(p[0], p[1]))) continue;
            queue.add(new int[]{p[0] + 1, p[1]});
            queue.add(new int[]{p[0] - 1, p[1]});
            queue.add(new int[]{p[0], p[1] + 1});
            queue.add(new int[]{p[0], p[1] - 1});
        }
        for (HubLayout.Zone zone : HubLayout.Zone.values()) {
            boolean connected = false;
            for (int x = zone.bounds.minX(); x <= zone.bounds.maxX(); x++) {
                for (int z = zone.bounds.minZ(); z <= zone.bounds.maxZ(); z++) {
                    connected |= reachable.contains(key(x, z));
                }
            }
            assertTrue(connected, () -> "No route reaches " + zone);
        }
    }

    @Test
    void perimeterIsClosedAndTowerAnchorsHaveJumpClearance() {
        int perimeterCount = 0;
        int r = HubLayout.HUB_RADIUS;
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            if (HubLayout.isPerimeter(x, z)) perimeterCount++;
            if (HubLayout.isPath(x, z)) assertFalse(HubLayout.isPerimeter(x, z));
        }
        assertEquals(8 * r, perimeterCount);
        assertTrue(r * 2 + 1 >= 90 && r * 2 + 1 <= 140);
        for (var guard : HubLayout.GUARDS) {
            if (guard.getY() > HubLayout.PLAYER_Y) {
                int platformOutside = Math.max(Math.abs(guard.getX()), Math.abs(guard.getZ())) + 3;
                assertTrue(r - platformOutside >= 7, "Watchtower must not bridge to perimeter");
            }
        }
    }

    @Test
    void terrainAndFallenCoverLeaveServicesRoutesAndWallClearanceIntact() {
        HubLayout.validate();
        int raisedColumns = 0;
        for (int x = -HubLayout.HUB_RADIUS; x <= HubLayout.HUB_RADIUS; x++) {
            for (int z = -HubLayout.HUB_RADIUS; z <= HubLayout.HUB_RADIUS; z++) {
                int rise = HubLayout.groundHeight(x, z) - HubLayout.FLOOR_Y;
                assertTrue(rise >= 0 && rise <= 3);
                if (rise == 0) continue;
                raisedColumns++;
                assertFalse(HubLayout.isReserved(x, z), "Hill obstructs reserved camp space");
            }
        }
        assertTrue(raisedColumns > 300, "The camp must contain substantial terrain cover");
        for (HubLayout.FallenTree tree : HubLayout.FALLEN_TREES) {
            assertTrue(tree.length() >= 6, "Cover should be a fallen trunk rather than a single-block step");
            for (int n = 0; n < tree.length(); n++) {
                int x = tree.x() + (tree.alongX() ? n : 0);
                int z = tree.z() + (tree.alongX() ? 0 : n);
                assertFalse(HubLayout.isReserved(x, z));
                assertEquals(HubLayout.FLOOR_Y, HubLayout.groundHeight(x, z), "Fallen trunks need continuous support");
                assertTrue(Math.max(Math.abs(x), Math.abs(z)) < HubLayout.HUB_RADIUS - 6);
            }
        }
        assertEquals(HubLayout.FLOOR_Y, HubLayout.groundHeight(0, 3), "Spawn stays level");
        assertEquals(HubLayout.FLOOR_Y, HubLayout.groundHeight(-6, -3), "Storage stays level");
        assertEquals(HubLayout.FLOOR_Y, HubLayout.groundHeight(0, -2), "Deployment table stays level");
        assertTrue(HubLayout.BARRIER_RADIUS > HubLayout.HUB_RADIUS + 1, "Containment must enclose external wall braces");
        assertTrue(HubLayout.BARRIER_BASE_Y < HubLayout.FLOOR_Y - 2, "Containment must continue beneath diggable ground");
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
}
