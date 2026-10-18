package com.bobincraft.weapon;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WeaponBallisticsTest {
    @Test
    void fastShotCannotSkipASingleBlockWall() {
        List<Integer> visited = new ArrayList<>();
        boolean reached = BulletPenetration.visitBlocks(new Vec3d(0.5, 64.5, 0.5),
                new Vec3d(43.0, 64.5, 0.5), pos -> {
                    visited.add(pos.getX());
                    return pos.getX() != 7;
                });
        assertFalse(reached);
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), visited);
    }

    @Test
    void diagonalShotsVisitNegativeCoordinatesAndKeepMaterialOrder() {
        List<BlockPos> visited = new ArrayList<>();
        assertTrue(BulletPenetration.visitBlocks(new Vec3d(4.25, 5.5, 2.75),
                new Vec3d(-34.25, -8.5, -21.75), pos -> {
                    visited.add(pos.toImmutable());
                    return true;
                }));
        assertEquals(new BlockPos(4, 5, 2), visited.get(0));
        assertEquals(new BlockPos(-35, -9, -22), visited.get(visited.size() - 1));
        assertEquals(visited.size(), visited.stream().distinct().count());
        for (int i = 1; i < visited.size(); i++) {
            BlockPos before = visited.get(i - 1);
            BlockPos next = visited.get(i);
            int crossedFaces = Math.abs(next.getX() - before.getX())
                    + Math.abs(next.getY() - before.getY()) + Math.abs(next.getZ() - before.getZ());
            assertEquals(1, crossedFaces, "No skipped voxels along the flight segment");
        }
    }

}
