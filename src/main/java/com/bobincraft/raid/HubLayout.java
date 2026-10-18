package com.bobincraft.raid;

import net.minecraft.util.math.BlockPos;

import java.util.List;

/** Stable, seed-independent camp plan. Kept separate from block placement for validation and NPC anchors. */
public final class HubLayout {
    public static final int FLOOR_Y = 70;
    public static final int PLAYER_Y = FLOOR_Y + 1;
    public static final int HUB_RADIUS = 52;
    public static final int WALL_TOP = 76;
    public static final int BARRIER_RADIUS = HUB_RADIUS + 2;
    public static final int BARRIER_BASE_Y = FLOOR_Y - 5;
    public static final BlockPos MERCHANT = new BlockPos(32, PLAYER_Y, -20);
    public static final List<BlockPos> GUARDS = List.of(
            new BlockPos(-9, PLAYER_Y, 45), new BlockPos(9, PLAYER_Y, 45),
            new BlockPos(28, PLAYER_Y, -17),
            new BlockPos(-41, 77, -41), new BlockPos(41, 77, -41),
            new BlockPos(-41, 77, 41), new BlockPos(41, 77, 41));

    /** Exactly six functional areas; watchtowers are satellite positions of SECURITY. */
    public enum Zone {
        CENTRAL(new Rect(-18, 7, 18, 33)),
        REST(new Rect(-45, -36, -23, 10)),
        KITCHEN(new Rect(-45, 21, -23, 38)),
        DEPLOYMENT(new Rect(-18, -17, 18, 5)),
        TRADING(new Rect(23, -35, 45, -6)),
        SECURITY(new Rect(-17, 35, 17, 51));

        public final Rect bounds;
        Zone(Rect bounds) { this.bounds = bounds; }
    }

    public record Rect(int minX, int minZ, int maxX, int maxZ) {
        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /** Orthogonal dirt routes. Width includes shoulders; all six services meet the central spine. */
    public record Path(int x1, int z1, int x2, int z2, int halfWidth) {
        public boolean contains(int x, int z) {
            return x >= Math.min(x1, x2) - halfWidth && x <= Math.max(x1, x2) + halfWidth
                    && z >= Math.min(z1, z2) - halfWidth && z <= Math.max(z1, z2) + halfWidth;
        }
    }

    public static final List<Path> PATHS = List.of(
            new Path(0, -12, 0, 49, 2),
            new Path(-34, 12, 34, 12, 2),
            new Path(-21, -22, -21, 12, 1),
            new Path(-34, -22, -21, -22, 1),
            new Path(-32, -6, -21, -6, 1),
            new Path(-39, 9, -21, 9, 1),
            new Path(-32, 12, -32, 26, 1),
            new Path(22, -16, 22, 12, 1),
            new Path(22, -16, 34, -16, 1),
            new Path(34, 6, 34, 12, 1),
            new Path(0, -12, 0, -22, 1),
            new Path(0, -22, 20, -22, 1),
            new Path(20, -33, 20, -22, 1),
            new Path(-14, -6, 14, -6, 1));

    /** Small terrain remnants inside the clearing; roads and all six service areas stay level. */
    public record Mound(int x, int z, int radius, int height) {
        public int rise(int px, int pz) {
            double distance = Math.hypot(px - x, pz - z);
            return distance >= radius ? 0 : (int) Math.ceil(height * (1.0 - distance / radius));
        }
    }

    public static final List<Mound> MOUNDS = List.of(
            new Mound(-9, -31, 6, 2), new Mound(9, -33, 8, 3), new Mound(33, 29, 8, 3));

    public record FallenTree(int x, int z, int length, boolean alongX) { }

    public static final List<FallenTree> FALLEN_TREES = List.of(
            new FallenTree(-16, -21, 10, true), new FallenTree(20, 25, 8, false),
            new FallenTree(25, 38, 10, true));

    public static int groundHeight(int x, int z) {
        int rise = 0;
        for (Mound mound : MOUNDS) rise = Math.max(rise, mound.rise(x, z));
        return FLOOR_Y + rise;
    }

    public static boolean isPath(int x, int z) {
        return PATHS.stream().anyMatch(path -> path.contains(x, z));
    }

    public static boolean isPerimeter(int x, int z) {
        return Math.abs(x) <= HUB_RADIUS && Math.abs(z) <= HUB_RADIUS
                && (Math.abs(x) == HUB_RADIUS || Math.abs(z) == HUB_RADIUS);
    }

    public static boolean isReserved(int x, int z) {
        if (Math.abs(x) >= HUB_RADIUS - 6 || Math.abs(z) >= HUB_RADIUS - 6 || isPath(x, z)) return true;
        for (Zone zone : Zone.values()) if (zone.bounds.contains(x, z)) return true;
        return false;
    }

    /** Reject mistakes before the first placement instead of leaving a partially built persistent camp. */
    public static void validate() {
        if (Zone.values().length != 6) throw new IllegalStateException("Hub must have six service zones");
        for (Zone zone : Zone.values()) {
            Rect r = zone.bounds;
            if (r.minX <= -HUB_RADIUS || r.maxX >= HUB_RADIUS || r.minZ <= -HUB_RADIUS || r.maxZ >= HUB_RADIUS) {
                throw new IllegalStateException("Service zone crosses palisade: " + zone);
            }
            if (PATHS.stream().noneMatch(path -> intersects(path, r))) {
                throw new IllegalStateException("Unconnected service zone: " + zone);
            }
        }
        for (BlockPos anchor : GUARDS) {
            if (Math.abs(anchor.getX()) >= HUB_RADIUS - 6 || Math.abs(anchor.getZ()) >= HUB_RADIUS - 6) {
                throw new IllegalStateException("Guard anchor obstructs perimeter");
            }
        }
        for (Mound mound : MOUNDS) {
            for (int x = mound.x - mound.radius; x <= mound.x + mound.radius; x++) {
                for (int z = mound.z - mound.radius; z <= mound.z + mound.radius; z++) {
                    if (mound.rise(x, z) > 0 && isReserved(x, z)) {
                        throw new IllegalStateException("Terrain obstructs a service, route or perimeter");
                    }
                }
            }
        }
        for (FallenTree tree : FALLEN_TREES) {
            for (int n = 0; n < tree.length; n++) {
                int x = tree.x + (tree.alongX ? n : 0);
                int z = tree.z + (tree.alongX ? 0 : n);
                if (isReserved(x, z)) throw new IllegalStateException("Fallen tree obstructs a reserved area");
            }
        }
    }

    private static boolean intersects(Path path, Rect rect) {
        return Math.max(path.x1, path.x2) + path.halfWidth >= rect.minX
                && Math.min(path.x1, path.x2) - path.halfWidth <= rect.maxX
                && Math.max(path.z1, path.z2) + path.halfWidth >= rect.minZ
                && Math.min(path.z1, path.z2) - path.halfWidth <= rect.maxZ;
    }

    private HubLayout() { }
}
