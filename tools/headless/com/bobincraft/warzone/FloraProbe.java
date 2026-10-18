package com.bobincraft.warzone;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Только для headless-проверок (tools/headless): доступ к пакетным API флоры. Не используется игрой. */
public final class FloraProbe {
    public static final class Report {
        public String kind;
        public int x, y, z, inRock, orphan;
        public boolean steep;

        @Override
        public String toString() {
            return kind + " at " + x + "," + y + "," + z + " inRock=" + inRock + " orphan=" + orphan + " steep=" + steep;
        }
    }

    private FloraProbe() {}

    public static List<Report> check(WarzoneTerrain t, int minX, int minZ) {
        List<Report> out = new ArrayList<>();
        WarzoneFlora fl = t.flora();
        for (WarzoneFlora.Plant p : fl.plantsForChunk(minX, minZ)) {
            // каждое растение считаем один раз — в «своём» чанке
            if (Math.floorDiv(p.x, 16) != Math.floorDiv(minX, 16) || Math.floorDiv(p.z, 16) != Math.floorDiv(minZ, 16)) continue;
            WarzoneFlora.Shape sh = fl.shape(p);
            Set<Long> logs = new HashSet<>();
            Set<Long> blocked = new HashSet<>();
            sh.forEach((dx, dy, dz, code) -> {
                int ty = WarzoneFlora.type(code);
                if (ty != WarzoneFlora.T_LOG && ty != WarzoneFlora.T_ROOTS) return;
                logs.add(WarzoneFlora.Shape.key(dx, dy, dz));
                if (ty == WarzoneFlora.T_LOG && p.y + dy <= t.height(p.x + dx, p.z + dz)) blocked.add(WarzoneFlora.Shape.key(dx, dy, dz));
            });
            Report r = new Report();
            r.kind = p.kind.name();
            r.x = p.x;
            r.y = p.y;
            r.z = p.z;
            r.inRock = blocked.size();
            // что останется связанным с корнем после того, как блоки в скале пропадут (18-соседство)
            Set<Long> alive = new HashSet<>(logs);
            alive.removeAll(blocked);
            Set<Long> seen = new HashSet<>();
            ArrayList<int[]> stack = new ArrayList<>();
            for (long k : alive) {
                int[] c = unpack(k);
                if (p.y + c[1] <= t.height(p.x + c[0], p.z + c[2]) + 1) { seen.add(k); stack.add(c); }
            }
            while (!stack.isEmpty()) {
                int[] c = stack.remove(stack.size() - 1);
                for (int ox = -1; ox <= 1; ox++) for (int oy = -1; oy <= 1; oy++) for (int oz = -1; oz <= 1; oz++) {
                    int n = (ox != 0 ? 1 : 0) + (oy != 0 ? 1 : 0) + (oz != 0 ? 1 : 0);
                    if (n == 0 || n == 3) continue;
                    long nk = WarzoneFlora.Shape.key(c[0] + ox, c[1] + oy, c[2] + oz);
                    if (alive.contains(nk) && seen.add(nk)) stack.add(unpack(nk));
                }
            }
            r.orphan = alive.size() - seen.size();
            double slope = Math.max(Math.abs(t.height(p.x + 2, p.z) - t.height(p.x - 2, p.z)), Math.abs(t.height(p.x, p.z + 2) - t.height(p.x, p.z - 2))) * 0.5;
            r.steep = slope > 1.5;
            out.add(r);
        }
        return out;
    }

    private static int[] unpack(long k) {
        return new int[]{(int) ((k >> 40) & 0xFFFFF) - 512, (int) ((k >> 20) & 0xFFFFF) - 512, (int) (k & 0xFFFFF) - 512};
    }
}
