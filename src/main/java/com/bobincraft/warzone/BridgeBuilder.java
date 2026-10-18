package com.bobincraft.warzone;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Строит ОДИН цельный чертёж моста по плану {@link WarzoneBasePlan.Bridge}.
 *
 * Модель: ось (прямая или плавная дуга) с фиксированными опорами на твёрдом берегу; постоянная ширина;
 * профиль настила считается один раз (полублоки, уклон не больше полублока на блок, не ниже SEA_LEVEL+3);
 * устои зарыты в берег; быки (пары брёвен + поперечная балка) идут до реального дна; перила, столбы и фонари
 * — по фиксированному шаблону. «Боевой» мост — спроектированные руины: ОДИН провал с рваными краями,
 * обломанные пеньки быков и несколько досок на воде; висящих фрагментов нет (после сборки они вычищаются).
 * Не зависит от чанков и от порядка генерации.
 */
final class BridgeBuilder {
    static final double HALF = 2.5;
    static final int TAIL = 14;        // сколько блоков оси продолжаем за концы плана, чтобы найти берег
    static final int ABUT = 4;         // устой начинается за столько блоков до первой воды
    static final int ABUT_Z = 2;       // первые/последние строки настила «врезаны» в берег
    static final int CLEAR = 5;        // высота проёма над настилом
    static final int MAX_CUT = 12;     // на подходах настил врезается в берег: выемка до поверхности, но не выше этого

    private static final BpSpec PLANKS = BpSpec.of("spruce_planks");
    private static final BpSpec PLANKS_BURNT = BpSpec.of("dark_oak_planks");
    private static final BpSpec SLAB = BpSpec.of("spruce_slab").with("type", "bottom").with("waterlogged", false);
    private static final BpSpec SLAB_BURNT = BpSpec.of("dark_oak_slab").with("type", "bottom").with("waterlogged", false);
    private static final BpSpec SLAB_WET = BpSpec.of("spruce_slab").with("type", "bottom").with("waterlogged", true);
    private static final BpSpec LOG = BpSpec.of("spruce_log").with("axis", "y");
    private static final BpSpec LOG_X = BpSpec.of("spruce_log").with("axis", "x");
    private static final BpSpec LOG_Z = BpSpec.of("spruce_log").with("axis", "z");
    private static final BpSpec COBBLE = BpSpec.of("cobblestone");
    private static final BpSpec FENCE = BpSpec.of("spruce_fence");
    private static final BpSpec LANTERN = BpSpec.of("lantern").with("hanging", false).with("waterlogged", false);

    private BridgeBuilder() {}

    static BridgeBlueprint build(WarzoneTerrain t, WarzoneBasePlan.Bridge br, int index, String[] why) {
        final int SEA = WarzoneTerrain.SEA_LEVEL;

        // ---------------------------------------------------------------- 1. ось: прямая или плавная дуга + прямые «хвосты»
        int m = br.px.length;
        double p0x = br.px[0], p0z = br.pz[0], p1x = br.px[m - 1], p1z = br.pz[m - 1];
        double cx = p1x - p0x, cz = p1z - p0z;
        double lc = Math.hypot(cx, cz);
        if (lc < 5.0) return reject(why, "chord<5");
        double ux = cx / lc, uz = cz / lc, nx = -uz, nz = ux;
        double dmax = 0;
        for (int i = 0; i < m; i++) {
            double dev = (br.px[i] - p0x) * nx + (br.pz[i] - p0z) * nz;
            if (Math.abs(dev) > Math.abs(dmax)) dmax = dev;
        }
        double off = Math.abs(dmax) <= 3.0 ? 0.0 : Math.max(-7.0, Math.min(7.0, 2.0 * dmax));
        double qx = (p0x + p1x) * 0.5 + nx * off, qz = (p0z + p1z) * 0.5 + nz * off;

        int fine = (int) Math.ceil(lc * 4) + 8;
        double[] fx = new double[fine + 1], fz = new double[fine + 1], cum = new double[fine + 1];
        for (int s = 0; s <= fine; s++) {
            double u = s / (double) fine, v = 1 - u;
            fx[s] = v * v * p0x + 2 * u * v * qx + u * u * p1x;
            fz[s] = v * v * p0z + 2 * u * v * qz + u * u * p1z;
            if (s > 0) cum[s] = cum[s - 1] + Math.hypot(fx[s] - fx[s - 1], fz[s] - fz[s - 1]);
        }
        int n = (int) Math.floor(cum[fine]);
        if (n < 5) return reject(why, "arc<5");
        int total = n + 2 * TAIL + 1;
        double[] ax = new double[total], az = new double[total];
        int ptr = 0;
        for (int j = 0; j <= n; j++) {
            while (ptr < fine - 1 && cum[ptr + 1] < j) ptr++;
            double seg = cum[ptr + 1] - cum[ptr];
            double f = seg < 1e-9 ? 0 : Math.max(0, Math.min(1, (j - cum[ptr]) / seg));
            ax[TAIL + j] = fx[ptr] + (fx[ptr + 1] - fx[ptr]) * f;
            az[TAIL + j] = fz[ptr] + (fz[ptr + 1] - fz[ptr]) * f;
        }
        double t0x = qx - p0x, t0z = qz - p0z, t0l = Math.hypot(t0x, t0z);
        double t1x = p1x - qx, t1z = p1z - qz, t1l = Math.hypot(t1x, t1z);
        t0x /= t0l; t0z /= t0l; t1x /= t1l; t1z /= t1l;
        for (int i = 1; i <= TAIL; i++) {
            ax[TAIL - i] = ax[TAIL] - t0x * i;
            az[TAIL - i] = az[TAIL] - t0z * i;
            ax[TAIL + n + i] = ax[TAIL + n] + t1x * i;
            az[TAIL + n + i] = az[TAIL + n] + t1z * i;
        }

        // ---------------------------------------------------------------- 2. высоты по оси, вода, опоры (устои) на твёрдой земле
        int[] g = new int[total];
        WarzoneTerrain.Sample tmp = new WarzoneTerrain.Sample();
        for (int k = 0; k < total; k++) {
            t.sample(rnd(ax[k]), rnd(az[k]), tmp);
            g[k] = tmp.height;
        }
        int iw0 = -1, iw1 = -1;
        boolean water = false;
        for (int k = TAIL; k <= TAIL + n; k++) {
            if (g[k] <= SEA + 2) {
                if (iw0 < 0) iw0 = k;
                iw1 = k;
            }
            if (g[k] <= SEA) water = true;
        }
        if (!water || iw0 < 0) return reject(why, "no water");
        // берег: сначала твёрдая суша (>= SEA+3), в болотистой местности — хотя бы над водой (>= SEA+1; ступенька в 1 блок проходима)
        int a = -1, b = -1;
        for (int thr = SEA + 3; thr >= SEA + 1 && (a < 1 || b < 0); thr -= 2) {
            a = iw0 - ABUT;
            while (a >= 1 && !(g[a] >= thr && g[a - 1] >= thr)) a--;
            b = iw1 + ABUT;
            while (b <= total - 2 && !(g[b] >= thr && g[b + 1] >= thr)) b++;
            if (a < 1) a = -1;
            if (b > total - 2) b = -1;
        }
        if (a < 1) return reject(why, "no bank A");
        if (b < 0) return reject(why, "no bank B");
        int L = b - a;
        if (L < 6) return reject(why, "L<6");

        // ---------------------------------------------------------------- 3. профиль настила (полублоки)
        int[] H = new int[L + 1];
        double gA = g[a] + 1, gB = g[b] + 1;
        for (int j = 0; j <= L; j++) {
            double v = gA + (gB - gA) * j / L;
            v = Math.max(v, SEA + 3);
            H[j] = (int) Math.round(v * 2.0);
        }
        // уклон: полублок на блок, а если берега слишком разной высоты — до блока на блок (проходимо прыжком)
        double dg = Math.abs(gB - gA);
        if (dg > L) return reject(why, "too steep");
        int maxStep = dg * 2.0 <= L ? 1 : 2;
        for (int j = 1; j <= L; j++) H[j] = Math.max(H[j], H[j - 1] - maxStep);
        for (int j = L - 1; j >= 0; j--) H[j] = Math.max(H[j], H[j + 1] - maxStep);
        int[] deckY = new int[L + 1];
        int maxH = 0;
        for (int j = 0; j <= L; j++) {
            deckY[j] = (H[j] & 1) == 1 ? (H[j] - 1) / 2 : H[j] / 2 - 1;
            maxH = Math.max(maxH, H[j]);
        }

        // ---------------------------------------------------------------- 4. bbox и растеризация колонок
        double mnx = 1e18, mxx = -1e18, mnz = 1e18, mxz = -1e18;
        for (int k = a; k <= b; k++) {
            mnx = Math.min(mnx, ax[k]);
            mxx = Math.max(mxx, ax[k]);
            mnz = Math.min(mnz, az[k]);
            mxz = Math.max(mxz, az[k]);
        }
        int margin = (int) Math.ceil(HALF) + 4;
        int minX = (int) Math.floor(mnx) - margin, maxX = (int) Math.ceil(mxx) + margin;
        int minZ = (int) Math.floor(mnz) - margin, maxZ = (int) Math.ceil(mxz) + margin;
        int bx = maxX - minX + 1, bz = maxZ - minZ + 1;
        int cols = bx * bz;
        int[] gh = new int[cols];
        double[] arcA = new double[cols], latA = new double[cols], distA = new double[cols];
        boolean[] deck = new boolean[cols], reserved = new boolean[cols];
        int minGround = Integer.MAX_VALUE;
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int ci = (z - minZ) * bx + (x - minX);
                t.sample(x, z, tmp);
                gh[ci] = tmp.height;
                minGround = Math.min(minGround, tmp.height);
                double best = Double.POSITIVE_INFINITY, bestArc = 0, bestSign = 1;
                for (int i = a; i < b; i++) {
                    double dx = ax[i + 1] - ax[i], dz = az[i + 1] - az[i];
                    double lenSq = dx * dx + dz * dz;
                    if (lenSq < 1e-9) continue;
                    double tu = ((x - ax[i]) * dx + (z - az[i]) * dz) / lenSq;
                    double tc = Math.max(0.0, Math.min(1.0, tu));
                    double d = Math.hypot(x - (ax[i] + tc * dx), z - (az[i] + tc * dz));
                    if (d < best) {
                        best = d;
                        double use = (i == a && tu < 0.0) || (i == b - 1 && tu > 1.0) ? tu : tc;
                        bestArc = (i - a) + use;
                        bestSign = (dx * (z - az[i]) - dz * (x - ax[i])) >= 0 ? 1 : -1;
                    }
                }
                arcA[ci] = bestArc;
                latA[ci] = bestSign * best;
                distA[ci] = best;
                boolean inside = t.inside(x, z);
                deck[ci] = inside && best <= HALF && bestArc >= -1e-6 && bestArc <= L + 1e-6;
                reserved[ci] = best <= HALF + 1.5 && bestArc >= -1.5 && bestArc <= L + 1.5;
            }
        }
        int minY = Math.max(1, minGround - 1);
        int maxY = maxH / 2 + CLEAR + 4;
        for (int ci = 0; ci < cols; ci++) {
            if (deck[ci]) maxY = Math.max(maxY, Math.min(gh[ci], deckY[Math.max(0, Math.min(L, (int) Math.floor(arcA[ci] + 0.5)))] + MAX_CUT) + 2);
        }
        Blueprint bp = new Blueprint("bridge#" + index, minX, minY, minZ, bx, maxY - minY + 1, bz);
        Blueprint.Ground ground = (x, z) -> {
            if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) return gh[(z - minZ) * bx + (x - minX)];
            WarzoneTerrain.Sample s2 = new WarzoneTerrain.Sample();
            t.sample(x, z, s2);
            return s2.height;
        };

        // отметка оси и палубный y каждой колонки
        int[] jj = new int[cols];
        int[] dy = new int[cols];
        for (int ci = 0; ci < cols; ci++) {
            if (!deck[ci]) continue;
            jj[ci] = Math.max(0, Math.min(L, (int) Math.floor(arcA[ci] + 0.5)));
            dy[ci] = deckY[jj[ci]];
            if (gh[ci] > dy[ci] + MAX_CUT) return reject(why, "cut too deep");   // гора на пути: тоннель вместо моста не строим
        }
        // граничные колонки настила (для перил и быков): есть непростроенный сосед по ширине
        boolean[] boundary = new boolean[cols];
        List<Integer> railCols = new ArrayList<>();
        for (int z = minZ + 1; z < maxZ; z++) {
            for (int x = minX + 1; x < maxX; x++) {
                int ci = (z - minZ) * bx + (x - minX);
                if (!deck[ci]) continue;
                if (!deck[ci - 1] || !deck[ci + 1] || !deck[ci - bx] || !deck[ci + bx]) {
                    boundary[ci] = true;
                    if (Math.abs(latA[ci]) > HALF - 1.25) railCols.add(ci);
                }
            }
        }

        // ---------------------------------------------------------------- 5. повреждения: ОДИН провал с рваными краями
        boolean damaged = br.damaged && L >= 16;
        int g0 = -1, g1 = -1;
        if (damaged) {
            double frac = br.length > 0 ? ((br.gapStart + br.gapEnd) * 0.5) / br.length : 0.5;
            double w01 = Math.max(0.0, Math.min(1.0, (frac - 0.38) / 0.24));
            double s0 = iw0 - a, s1 = iw1 - a;
            double mid = s0 + (s1 - s0) * (0.35 + 0.30 * w01);
            int gw = Math.max(4, Math.min(6, (int) Math.round(br.gapEnd - br.gapStart)));
            g0 = (int) Math.round(mid - gw / 2.0);
            g1 = g0 + gw;
            g0 = Math.max(g0, 6);
            g1 = Math.min(g1, L - 6);
            if (g1 - g0 < 3) damaged = false;
        }
        final boolean dmg = damaged;
        final int G0 = g0, G1 = g1;

        boolean[] keep = new boolean[cols];
        boolean[] nearTear = new boolean[cols];
        for (int ci = 0; ci < cols; ci++) {
            if (!deck[ci]) continue;
            if (!dmg) {
                keep[ci] = true;
                continue;
            }
            double arc = arcA[ci];
            int lane = (int) Math.round(latA[ci]);
            if (arc <= G0) {
                int tear = tear(br, lane, 0);
                keep[ci] = arc <= G0 - tear;
                nearTear[ci] = arc > G0 - tear - 1.5;
            } else if (arc >= G1) {
                int tear = tear(br, lane, 1);
                keep[ci] = arc >= G1 + tear;
                nearTear[ci] = arc < G1 + tear + 1.5;
            }
        }

        // ---------------------------------------------------------------- 6. запись в чертёж (слоями, порядок важен)
        // 6.1 проём над настилом
        for (int ci = 0; ci < cols; ci++) {
            if (!deck[ci]) continue;
            int x = minX + ci % bx, z = minZ + ci / bx;
            int cut = Math.max(dy[ci] + CLEAR, Math.min(gh[ci], dy[ci] + MAX_CUT));   // выемка в береговом холме — без «тоннеля»
            for (int y = dy[ci] + 1; y <= cut; y++) bp.air(x, y, z);
        }
        // 6.2 устои: заполнение между землёй и настилом в крайних строках и на низких участках суши
        for (int ci = 0; ci < cols; ci++) {
            if (!deck[ci] || !keep[ci]) continue;
            int x = minX + ci % bx, z = minZ + ci / bx;
            int gr = gh[ci];
            boolean endRow = arcA[ci] < ABUT_Z + 1.5 || arcA[ci] > L - ABUT_Z - 1.5;
            boolean lowLand = gr >= SEA && dy[ci] - 1 - gr <= 2;
            if (gr < dy[ci] - 1 && (endRow || lowLand)) {
                for (int y = gr + 1; y <= dy[ci] - 1; y++) bp.set(x, y, z, COBBLE);
            }
        }
        // 6.3 настил
        for (int ci = 0; ci < cols; ci++) {
            if (!deck[ci] || !keep[ci]) continue;
            int x = minX + ci % bx, z = minZ + ci / bx;
            boolean slab = (H[jj[ci]] & 1) == 1;
            boolean burnt = false;
            if (dmg && arcA[ci] > G0 - 7 && arcA[ci] < G1 + 7) {
                burnt = Math.floorMod(WarzoneNoise.hash(br.salt + 5, x, z), 100L) < 30;
            }
            bp.set(x, dy[ci], z, slab ? (burnt ? SLAB_BURNT : SLAB) : (burnt ? PLANKS_BURNT : PLANKS));
        }
        // 6.4 быки: пары брёвен до реального дна + поперечная балка; в провале — обломанные пеньки
        List<Integer> bents = new ArrayList<>();
        int base = (int) Math.round(L / 2.0);
        for (int q = -(L / 6) - 1; q <= L / 6 + 1; q++) {
            int j = base + 6 * q;
            if (j >= 3 && j <= L - 3) bents.add(j);
        }
        if (dmg) {
            boolean any = false;
            for (int j : bents) if (j >= G0 && j <= G1) any = true;
            if (!any) {
                bents.add((G0 + G1) / 2);
            }
        }
        Set<Integer> pillarCols = new HashSet<>();
        for (int j : bents) {
            boolean stub = dmg && j >= G0 && j <= G1;
            int left = pickRail(railCols, arcA, latA, j, -2.0), right = pickRail(railCols, arcA, latA, j, 2.0);
            int[] pc = {left, right};
            int[] built = {-1, -1};
            for (int s = 0; s < 2; s++) {
                int ci = pc[s];
                if (ci < 0) continue;
                int x = minX + ci % bx, z = minZ + ci / bx;
                int gr = gh[ci];
                int top = dy[ci] - 1;
                if (gr >= top) continue;                                   // берег: бык не нужен
                if (stub) {
                    long hv = WarzoneNoise.hash(br.salt + 41 + s, x, z);
                    top = Math.min(top, Math.max(gr + 1, SEA + 1 + (int) Math.floorMod(hv, 3L)));
                }
                for (int y = gr; y <= top; y++) bp.set(x, y, z, LOG);
                pillarCols.add(ci);
                built[s] = ci;
            }
            if (!stub && built[0] >= 0 && built[1] >= 0) {
                beam(bp, minX, minZ, bx, built[0], built[1], dy[built[0]] - 1, dy[built[1]] - 1);
            }
        }
        // 6.5 перила: столбы на быках (с фонарём) и промежуточные, между ними заборы
        Set<Integer> postCols = new HashSet<>();
        Set<Integer> lampCols = new HashSet<>();
        for (int j : bents) {
            if (dmg && j >= G0 && j <= G1) continue;
            for (int s = 0; s < 2; s++) {
                int ci = pickRail(railCols, arcA, latA, j, s == 0 ? -2.0 : 2.0);
                if (ci >= 0) {
                    postCols.add(ci);
                    lampCols.add(ci);
                }
                int mid = pickRail(railCols, arcA, latA, j + 3, s == 0 ? -2.0 : 2.0);
                if (mid >= 0 && arcA[mid] > 0.5 && arcA[mid] < L - 0.5) postCols.add(mid);
            }
        }
        for (int ci : railCols) {
            if (!keep[ci] || nearTear[ci]) continue;
            int x = minX + ci % bx, z = minZ + ci / bx;
            if (gh[ci] >= dy[ci]) continue;                                // у берега перил нет — они кончаются на земле
            int y = dy[ci] + 1;
            if (postCols.contains(ci)) {
                bp.set(x, y, z, LOG);
                bp.set(x, y + 1, z, LOG);
                if (lampCols.contains(ci)) bp.set(x, y + 2, z, LANTERN);
            } else {
                bp.set(x, y, z, FENCE);
            }
        }

        // ---------------------------------------------------------------- 7. заборы по соседям, вычистка висящего
        bp.connect(ground);
        int pruned = bp.pruneFloating(ground);

        // ---------------------------------------------------------------- 8. обломки на воде (после вычистки)
        Set<Long> debris = new HashSet<>();
        if (dmg) {
            List<long[]> cand = new ArrayList<>();
            for (int ci = 0; ci < cols; ci++) {
                if (Math.abs(latA[ci]) > 4.0 || arcA[ci] < G0 - 2 || arcA[ci] > G1 + 10) continue;
                if (gh[ci] > SEA - 2) continue;
                int x = minX + ci % bx, z = minZ + ci / bx;
                if (bp.get(x, SEA, z) != null) continue;
                cand.add(new long[]{WarzoneNoise.hash(br.salt + 77, x, z), x, z});
            }
            cand.sort((p, q) -> Long.compare(p[0], q[0]));
            List<long[]> chosen = new ArrayList<>();
            for (long[] c : cand) {
                if (chosen.size() >= 4) break;
                boolean close = false;
                for (long[] o : chosen) if (Math.abs(o[1] - c[1]) <= 1 && Math.abs(o[2] - c[2]) <= 1) close = true;
                if (close) continue;
                chosen.add(c);
                bp.set((int) c[1], SEA, (int) c[2], SLAB_WET);
                debris.add(BridgeBlueprint.key((int) c[1], SEA, (int) c[2]));
            }
        }

        // ---------------------------------------------------------------- 9. метаданные
        boolean[] present = new boolean[L + 1];
        int[] anchorA = {0, 0, Integer.MIN_VALUE}, anchorB = {0, 0, Integer.MIN_VALUE};
        double bestA = 1e18, bestB = 1e18;
        for (int ci = 0; ci < cols; ci++) {
            if (!deck[ci] || !keep[ci]) continue;
            int x = minX + ci % bx, z = minZ + ci / bx;
            BpSpec s = bp.get(x, dy[ci], z);
            if (s == null || s.isAir()) continue;
            present[jj[ci]] = true;
            double da = arcA[ci] * arcA[ci] + latA[ci] * latA[ci];
            double db = (L - arcA[ci]) * (L - arcA[ci]) + latA[ci] * latA[ci];
            if (da < bestA) {
                bestA = da;
                anchorA = new int[]{x, z, dy[ci]};
            }
            if (db < bestB) {
                bestB = db;
                anchorB = new int[]{x, z, dy[ci]};
            }
        }
        if (anchorA[2] == Integer.MIN_VALUE || anchorB[2] == Integer.MIN_VALUE) return reject(why, "empty deck");
        boolean[] reservedFinal = reserved;
        return new BridgeBlueprint(index, bp, dmg, L, anchorA[0], anchorA[1], anchorB[0], anchorB[1], anchorA[2], anchorB[2],
                g[a], g[b], deckY[0], deckY[L],
                present, dmg ? G0 : -1, dmg ? G1 : -1, debris, reservedFinal, minX, minZ, bx, bz);
    }

    // ------------------------------------------------------------------ помощники

    private static BridgeBlueprint reject(String[] why, String reason) {
        if (why != null && why.length > 0) why[0] = reason;
        return null;
    }

    private static int rnd(double v) {
        return (int) Math.floor(v + 0.5);
    }

    /** Насколько «оторван» рваный край провала на этой полосе настила (0..2 блока). */
    private static int tear(WarzoneBasePlan.Bridge br, int lane, int side) {
        if (lane == 0) return 0;                                            // центральная полоса доходит до линии разлома — край получается рваным, а не ровным
        return (int) Math.floorMod(WarzoneNoise.hash(br.salt + (side == 0 ? 11 : 23), lane, 7), 3L);
    }

    /** Ближайшая к (arc, lat) колонка из списка перильных; −1, если ближе ~1.6 блока нет. */
    private static int pickRail(List<Integer> rails, double[] arcA, double[] latA, double arc, double lat) {
        int best = -1;
        double bd = 2.6;
        for (int ci : rails) {
            double da = arcA[ci] - arc, dl = latA[ci] - lat;
            double d = da * da + dl * dl;
            if (d < bd) {
                bd = d;
                best = ci;
            }
        }
        return best;
    }

    /** Поперечная балка между двумя быками (линия Брезенхэма в плане). */
    private static void beam(Blueprint bp, int minX, int minZ, int bx, int c0, int c1, int y0, int y1) {
        int x0 = minX + c0 % bx, z0 = minZ + c0 / bx;
        int x1 = minX + c1 % bx, z1 = minZ + c1 / bx;
        int y = Math.min(y0, y1);
        BpSpec spec = Math.abs(x1 - x0) >= Math.abs(z1 - z0) ? LOG_X : LOG_Z;
        int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int x = x0, z = z0;
        while (true) {
            bp.set(x, y, z, spec);
            if (x == x1 && z == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
        }
    }
}
