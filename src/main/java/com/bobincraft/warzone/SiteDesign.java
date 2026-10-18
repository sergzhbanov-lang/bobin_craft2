package com.bobincraft.warzone;

import com.bobincraft.warzone.WarzoneBasePlan.SiteType;
import com.bobincraft.warzone.WarzoneBasePlan.SpotRole;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Чистая (без Minecraft) раскладка базы: какие модули где стоят, как повёрнуты и какие тактические точки у них есть.
 * Детерминирована из (тип, фракция, вариант, seed): план баз вызывает её ради точек для ИИ, а {@link SiteBuilder}
 * — ради постройки, поэтому точки всегда стоят на реальном полу построенных зданий.
 * Координаты — локальные (u, v) относительно центра базы до поворота: +V — главные ворота, −V — тыл.
 */
final class SiteDesign {
    enum Mod {
        TOWER, BARRACKS, HQ, DEPOT, MESS, WORKSHOP, ARMORY, HOSPITAL, PILLBOX, MORTAR, RADIO, HELIPAD, TOTEM,
        CABIN, TENT, CAMPFIRE, CAGE, FUEL, SANDBAG, TANKTRAP, WIRE, TRENCH, CRATES
    }

    /** Стили башен. */
    static final int T_KEEP = 0, T_DRUM = 1, T_PAGODA = 2, T_TIMBER = 3;
    /** Стили стены. */
    static final int W_STONE = 0, W_STOCKADE = 1, W_SANDBAG = 2;

    static final class Place {
        final Mod mod;
        final int u, v, face, w, d, style, salt;

        Place(Mod mod, int u, int v, int face, int w, int d, int style, int salt) {
            this.mod = mod;
            this.u = u;
            this.v = v;
            this.face = face & 3;
            this.w = w;
            this.d = d;
            this.style = style;
            this.salt = salt;
        }

        /** Прямоугольник занятых клеток (включительно) в локальных координатах базы: {u0, v0, u1, v1}. */
        int[] rect() {
            boolean odd = (face & 1) == 1;
            int fw = odd ? d : w, fd = odd ? w : d;
            return new int[]{u - fw / 2, v - fd / 2, u + fw / 2, v + fd / 2};
        }
    }

    static final class LSpot {
        final int u, dy, v;
        final SpotRole role;
        final float yaw;

        LSpot(int u, int dy, int v, SpotRole role, float yaw) {
            this.u = u;
            this.dy = dy;
            this.v = v;
            this.role = role;
            this.yaw = yaw;
        }
    }

    final SiteType type;
    final Faction faction;
    /** Радиус стены/кольца: квадрат со стороной 2R+1 или окружность радиуса R. */
    final int R;
    /** Половина стороны bbox чертежа. */
    final int half;
    final boolean circular;
    /** Есть ли замкнутая ограда (стена, частокол или кольцо проволоки). */
    final boolean fenced;
    final int wallStyle;
    final int wallH;
    /** Ворота: бит 0 — +V (главные), 1 — −V, 2 — −U, 3 — +U. */
    final int gates;
    /** Доля разрушений стены, 0..100. */
    final int ruin;
    /** Вариант покрытия двора и центральной площади. */
    final int yardKind, plazaKind;
    final long seed;
    final List<Place> places = new ArrayList<>();
    final List<LSpot> spots = new ArrayList<>();

    private SiteDesign(SiteType type, Faction f, int R, int half, boolean circular, boolean fenced, int wallStyle, int wallH,
                       int gates, int ruin, int yardKind, int plazaKind, long seed) {
        this.type = type;
        this.faction = f;
        this.R = R;
        this.half = half;
        this.circular = circular;
        this.fenced = fenced;
        this.wallStyle = wallStyle;
        this.wallH = wallH;
        this.gates = gates;
        this.ruin = ruin;
        this.yardKind = yardKind;
        this.plazaKind = plazaKind;
        this.seed = seed;
    }

    // ------------------------------------------------------------------ размеры модулей

    static int[] size(Mod m, int style, Random r) {
        return switch (m) {
            case TOWER -> new int[]{7, 7};
            case BARRACKS -> style == 0 ? new int[]{11, 7} : new int[]{9, 7};
            case HQ -> style == 0 ? new int[]{13, 9} : new int[]{11, 9};
            case DEPOT -> new int[]{9, 7};
            case MESS -> new int[]{7, 7};
            case WORKSHOP -> new int[]{9, 7};
            case ARMORY -> new int[]{7, 7};
            case HOSPITAL -> new int[]{7, 5};
            case PILLBOX -> new int[]{7, 7};
            case MORTAR -> new int[]{5, 5};
            case RADIO -> new int[]{3, 3};
            case HELIPAD -> new int[]{9, 9};
            case TOTEM -> new int[]{3, 3};
            case CABIN -> style == 0 ? new int[]{9, 7} : new int[]{7, 5};
            case TENT -> new int[]{5, 5};
            case CAMPFIRE -> new int[]{7, 7};
            case CAGE -> new int[]{5, 5};
            case FUEL -> new int[]{5, 5};
            case SANDBAG -> new int[]{5, 3};
            case TANKTRAP -> new int[]{3, 3};
            case WIRE -> new int[]{7 + 2 * r.nextInt(3), 1};
            case TRENCH -> new int[]{9 + 2 * r.nextInt(2), 3};
            case CRATES -> new int[]{3, 3};
        };
    }

    private static int apron(Mod m) {
        return switch (m) {
            case BARRACKS, HQ, DEPOT, MESS, WORKSHOP, ARMORY, PILLBOX, CABIN, TOWER, HOSPITAL, TENT, CAGE -> 2;
            default -> 0;
        };
    }

    private static boolean isDecor(Mod m) {
        return switch (m) {
            case SANDBAG, TANKTRAP, WIRE, TRENCH, CRATES, TOTEM, RADIO, FUEL, MORTAR -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ вход

    static SiteDesign design(SiteType type, Faction f, WarzoneTerrain.Region region, int variant, long seed) {
        Random r = new Random(WarzoneNoise.mix(seed * 31L + type.ordinal() * 977L + variant));
        boolean bandit = f.isBandit();
        int wallStyle = switch (f.wallStyle) {
            case STONE -> W_STONE;
            case STOCKADE -> W_STOCKADE;
            case SANDBAG -> W_SANDBAG;
        };
        int yard = r.nextInt(4);
        int plaza = r.nextInt(4);
        SiteDesign d;
        switch (type) {
            case FORTRESS -> {
                int R = 24 + r.nextInt(3);
                int gates = 1 | (r.nextInt(10) < 7 ? 2 : 0) | (r.nextInt(10) < 3 ? 4 : 0) | (r.nextInt(10) < 3 ? 8 : 0);
                d = new SiteDesign(type, f, R, 31, false, true, wallStyle, 4 + r.nextInt(2), gates, 5 + r.nextInt(25), yard, plaza, seed);
            }
            case OUTPOST -> {
                int R = 15 + r.nextInt(3);
                int gates = 1 | (r.nextInt(10) < 5 ? 2 : 0);
                d = new SiteDesign(type, f, R, 21, false, true, wallStyle, 3 + r.nextInt(2), gates, 5 + r.nextInt(30), yard, plaza, seed);
            }
            case MOUNTAIN_FORT -> {
                int R = 18 + r.nextInt(3);
                int gates = 1 | (r.nextInt(10) < 5 ? 2 : 0);
                d = new SiteDesign(type, f, R, 25, false, true, W_STONE, 5, gates, 5 + r.nextInt(20), yard, plaza, seed);
            }
            case BUNKER_COMPLEX -> {
                d = new SiteDesign(type, f, 12 + r.nextInt(2), 17, true, true, W_SANDBAG, 2, 1 | (r.nextInt(10) < 4 ? 2 : 0),
                        10 + r.nextInt(20), yard, plaza, seed);
            }
            case BANDIT_CAMP -> {
                d = new SiteDesign(type, f, 13 + r.nextInt(2), 17, true, true, W_STOCKADE, 4, 1 | (r.nextInt(10) < 5 ? 2 : 0),
                        15 + r.nextInt(30), yard, plaza, seed);
            }
            default -> {   // WATCHTOWER
                d = new SiteDesign(type, f, 8, 12, true, false, wallStyle, 3, 1, 0, yard, plaza, seed);
            }
        }
        d.layout(r, region, bandit);
        return d;
    }

    // ------------------------------------------------------------------ упаковка модулей

    private final List<int[]> blocked = new ArrayList<>();      // прямоугольники с зазором: {u0,v0,u1,v1}
    private final List<int[]> solid = new ArrayList<>();        // голые занятые клетки (для связности)

    private boolean inBounds(int[] r, boolean forecourt) {
        int lim = R - 2;
        if (forecourt) {
            int lo = R + 2, hi = half - 2;
            return r[0] >= -hi && r[2] <= hi && r[1] >= lo && r[3] <= hi;
        }
        if (circular) {
            for (int a = 0; a < 4; a++) {
                int cu = (a & 1) == 0 ? r[0] : r[2], cv = (a & 2) == 0 ? r[1] : r[3];
                if (Math.hypot(cu, cv) > lim) return false;
            }
            return true;
        }
        return r[0] >= -lim && r[2] <= lim && r[1] >= -lim && r[3] <= lim;
    }

    private static boolean overlap(int[] a, int[] b) {
        return a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1];
    }

    private static int[] inflate(int[] r, int m) {
        return new int[]{r[0] - m, r[1] - m, r[2] + m, r[3] + m};
    }

    private static int[] withApron(int[] r, int face, int n) {
        int[] o = r.clone();
        switch (face & 3) {
            case 0 -> o[3] += n;
            case 1 -> o[0] -= n;
            case 2 -> o[1] -= n;
            default -> o[2] += n;
        }
        return o;
    }

    private static int faceToCenter(int u, int v) {
        if (Math.abs(u) > Math.abs(v)) return u > 0 ? 1 : 3;
        return v > 0 ? 2 : 0;
    }

    private void keepouts() {
        int far = half;
        blocked.add(new int[]{-4, -4, 4, 4});                                   // центральная площадь
        if ((gates & 1) != 0) blocked.add(new int[]{-2, 0, 2, far});
        if ((gates & 2) != 0) blocked.add(new int[]{-2, -far, 2, 0});
        if ((gates & 4) != 0) blocked.add(new int[]{-far, -2, 0, 2});
        if ((gates & 8) != 0) blocked.add(new int[]{0, -2, far, 2});
    }

    /** Пробует поставить модуль; зона: 0 любая, 1 тыл, 2 фланги, 3 углы, 4 перед (у ворот), 5 предполье снаружи. */
    private Place tryPlace(Mod m, int style, int zone, Random r, int forcedFace, int salt) {
        int[] sz = size(m, style, r);
        boolean decor = isDecor(m);
        for (int attempt = 0; attempt < 260; attempt++) {
            int lim = R - 3;
            int u, v;
            if (zone == 5) {
                u = r.nextInt(2 * (half - 3) + 1) - (half - 3);
                v = R + 2 + r.nextInt(Math.max(1, half - 2 - (R + 2)) + 1);
            } else {
                u = r.nextInt(2 * lim + 1) - lim;
                v = r.nextInt(2 * lim + 1) - lim;
                switch (zone) {
                    case 1 -> { if (v > -R / 3) continue; }
                    case 2 -> { if (Math.abs(u) < R / 3) continue; }
                    case 3 -> { if (Math.abs(u) < R / 2 || Math.abs(v) < R / 2) continue; }
                    case 4 -> { if (v < R / 4) continue; }
                    default -> { }
                }
            }
            int face = forcedFace >= 0 ? forcedFace : (zone == 5 ? 0 : faceToCenter(u, v));
            Place p = new Place(m, u, v, face, sz[0], sz[1], style, salt);
            int[] rect = p.rect();
            if (!inBounds(rect, zone == 5)) continue;
            int[] full = decor ? rect : withApron(rect, face, apron(m));
            int[] test = decor ? inflate(full, 1) : inflate(full, 1);
            boolean bad = false;
            for (int[] b : blocked) if (overlap(test, b)) { bad = true; break; }
            if (bad) continue;
            // не должно закрывать путь к двери здания: проверяем связность двора
            solid.add(rect);
            if (!connected(p, full)) { solid.remove(solid.size() - 1); continue; }
            blocked.add(inflate(full, decor ? 0 : 1));
            places.add(p);
            return p;
        }
        return null;
    }

    /** Все двери зданий достижимы по свободным клеткам от главных ворот (грубая проверка по сетке). */
    private boolean connected(Place added, int[] full) {
        int n = 2 * half + 1;
        boolean[] wall = new boolean[n * n];
        for (int[] s : solid) {
            for (int u = Math.max(-half, s[0]); u <= Math.min(half, s[2]); u++)
                for (int v = Math.max(-half, s[1]); v <= Math.min(half, s[3]); v++) wall[(v + half) * n + (u + half)] = true;
        }
        if (fenced) {
            for (int u = -half; u <= half; u++) {
                for (int v = -half; v <= half; v++) {
                    boolean ring = circular ? Math.abs(Math.hypot(u, v) - R) < 0.8 : Math.max(Math.abs(u), Math.abs(v)) == R;
                    if (ring && !isGate(u, v)) wall[(v + half) * n + (u + half)] = true;
                }
            }
        }
        int su = 0, sv = Math.min(half, R + 1);
        if ((gates & 1) == 0) sv = 0;
        boolean[] seen = new boolean[n * n];
        int[] q = new int[n * n];
        int h = 0, t = 0;
        int start = (sv + half) * n + (su + half);
        if (wall[start]) return false;
        seen[start] = true;
        q[t++] = start;
        while (h < t) {
            int c = q[h++];
            int cu = c % n, cv = c / n;
            int[][] d4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : d4) {
                int nu = cu + d[0], nv = cv + d[1];
                if (nu < 0 || nv < 0 || nu >= n || nv >= n) continue;
                int k = nv * n + nu;
                if (seen[k] || wall[k]) continue;
                seen[k] = true;
                q[t++] = k;
            }
        }
        for (Place p : places) {
            if (!needsDoor(p.mod)) continue;
            if (!doorReachable(p, seen, n)) return false;
        }
        if (needsDoor(added.mod) && !doorReachable(added, seen, n)) return false;
        return true;
    }

    /** Клетка кольца ограды лежит в проёме ворот. */
    boolean isGate(int u, int v) {
        if ((gates & 1) != 0 && Math.abs(u) <= 2 && v > 0 && Math.abs(v) >= R - 1) return true;
        if ((gates & 2) != 0 && Math.abs(u) <= 2 && v < 0 && Math.abs(v) >= R - 1) return true;
        if ((gates & 4) != 0 && Math.abs(v) <= 2 && u < 0 && Math.abs(u) >= R - 1) return true;
        if ((gates & 8) != 0 && Math.abs(v) <= 2 && u > 0 && Math.abs(u) >= R - 1) return true;
        return false;
    }

    private boolean doorReachable(Place p, boolean[] seen, int n) {
        int[] r = p.rect();
        int fu, fv;
        switch (p.face) {
            case 0 -> { fu = p.u; fv = r[3] + 1; }
            case 1 -> { fu = r[0] - 1; fv = p.v; }
            case 2 -> { fu = p.u; fv = r[1] - 1; }
            default -> { fu = r[2] + 1; fv = p.v; }
        }
        if (Math.abs(fu) > half || Math.abs(fv) > half) return false;
        return seen[(fv + half) * n + (fu + half)];
    }

    private static boolean needsDoor(Mod m) {
        return switch (m) {
            case BARRACKS, HQ, DEPOT, MESS, WORKSHOP, ARMORY, PILLBOX, CABIN, TOWER, HOSPITAL, TENT -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ раскладка по типам

    private void layout(Random r, WarzoneTerrain.Region region, boolean bandit) {
        keepouts();
        switch (type) {
            case FORTRESS -> layoutFortress(r, bandit);
            case OUTPOST -> layoutOutpost(r, bandit);
            case MOUNTAIN_FORT -> layoutMountain(r);
            case BUNKER_COMPLEX -> layoutBunker(r);
            case BANDIT_CAMP -> layoutCamp(r);
            default -> layoutWatch(r);
        }
        makeSpots(bandit);
    }

    private int towerStyle(Random r) {
        if (faction.wallStyle == Faction.WallStyle.STOCKADE) return T_TIMBER;
        if (faction.wallStyle == Faction.WallStyle.SANDBAG) return r.nextBoolean() ? T_KEEP : T_DRUM;
        return r.nextInt(3);
    }

    /** Башня строго в углу/на стене: центр на (cu, cv), дверью к центру базы. */
    private void putTower(int cu, int cv, int style, int salt) {
        int face = (cu == 0 && cv == 0) ? 0 : faceToCenter(cu, cv);
        if (Math.abs(cu) == Math.abs(cv)) face = cv > 0 ? 2 : 0;     // в углу дверь — вдоль оси V
        Place p = new Place(Mod.TOWER, cu, cv, face, 7, 7, style, salt);
        int[] rect = p.rect();
        solid.add(rect);
        blocked.add(inflate(withApron(rect, face, 2), 1));
        places.add(p);
    }

    private void forecourt(Random r, int sandbags, int traps, int trenches, int wires) {
        if ((gates & 1) == 0) return;
        for (int i = 0; i < trenches; i++) tryPlace(Mod.TRENCH, 0, 5, r, 0, 90 + i);
        for (int i = 0; i < sandbags; i++) tryPlace(Mod.SANDBAG, 0, 5, r, 0, 80 + i);
        for (int i = 0; i < traps; i++) tryPlace(Mod.TANKTRAP, 0, 5, r, 0, 70 + i);
        for (int i = 0; i < wires; i++) tryPlace(Mod.WIRE, 0, 5, r, 0, 60 + i);
    }

    private void kit(Random r, int count, boolean small) {
        List<Faction.Extra> pool = new ArrayList<>(List.of(faction.kit));
        Collections.shuffle(pool, r);
        int placed = 0;
        for (Faction.Extra e : pool) {
            if (placed >= count) break;
            Mod m = switch (e) {
                case MORTAR -> Mod.MORTAR;
                case RADIO -> Mod.RADIO;
                case HOSPITAL -> Mod.HOSPITAL;
                case HELIPAD -> small ? null : Mod.HELIPAD;
                case TOTEM -> Mod.TOTEM;
            };
            if (m == null) continue;
            boolean dup = false;
            for (Place p : places) if (p.mod == m) dup = true;
            if (dup && m != Mod.MORTAR) continue;
            if (tryPlace(m, r.nextInt(2), m == Mod.HELIPAD ? 3 : 0, r, -1, 40 + placed) != null) placed++;
        }
    }

    private void layoutFortress(Random r, boolean bandit) {
        int tc = R - 3;
        int ts = towerStyle(r);
        boolean mixed = r.nextInt(3) == 0;
        putTower(-tc, -tc, ts, 1);
        putTower(tc, -tc, mixed ? towerStyle(r) : ts, 2);
        putTower(-tc, tc, mixed ? towerStyle(r) : ts, 3);
        putTower(tc, tc, ts, 4);
        if (r.nextInt(2) == 0 && (gates & 1) != 0) {       // сдвоенные башни у главных ворот
            putTower(-8, R - 3, ts, 5);
            putTower(8, R - 3, ts, 6);
        } else if (r.nextInt(2) == 0 && (gates & 12) == 0) {   // башни в серединах боковых стен
            putTower(-(R - 3), 0, ts, 5);
            putTower(R - 3, 0, ts, 6);
        }
        tryPlace(Mod.HQ, r.nextInt(2), 1, r, -1, 10);
        int barracks = 1 + r.nextInt(2);
        for (int i = 0; i < barracks; i++) tryPlace(Mod.BARRACKS, r.nextInt(2), 2, r, -1, 20 + i);
        tryPlace(Mod.DEPOT, 0, 0, r, -1, 30);
        if (r.nextInt(10) < 6) tryPlace(Mod.MESS, 0, 0, r, -1, 31);
        tryPlace(r.nextBoolean() ? Mod.ARMORY : Mod.WORKSHOP, 0, 2, r, -1, 32);
        if (r.nextInt(10) < 4) tryPlace(Mod.PILLBOX, 0, 4, r, -1, 33);
        kit(r, 3 + r.nextInt(2), false);
        for (int i = 0; i < 3; i++) tryPlace(Mod.CRATES, 0, 0, r, -1, 50 + i);
        for (int i = 0; i < 2 + r.nextInt(3); i++) tryPlace(Mod.SANDBAG, 0, 0, r, -1, 55 + i);
        forecourt(r, 2, 2, 2, 1);
    }

    private void layoutOutpost(Random r, boolean bandit) {
        int tc = R - 3;
        int ts = towerStyle(r);
        int pair = r.nextInt(3);
        if (pair == 0) { putTower(-tc, -tc, ts, 1); putTower(tc, -tc, ts, 2); }
        else if (pair == 1) { putTower(-tc, tc, ts, 1); putTower(tc, -tc, ts, 2); }
        else { putTower(-tc, -tc, ts, 1); putTower(tc, tc, ts, 2); }
        tryPlace(Mod.BARRACKS, r.nextInt(2), 1, r, -1, 10);
        tryPlace(r.nextBoolean() ? Mod.DEPOT : Mod.MESS, 0, 2, r, -1, 11);
        tryPlace(Mod.CAMPFIRE, 0, 0, r, 0, 12);
        if (r.nextInt(10) < 4) tryPlace(Mod.WORKSHOP, 0, 0, r, -1, 13);
        kit(r, 1 + r.nextInt(2), true);
        for (int i = 0; i < 2; i++) tryPlace(Mod.CRATES, 0, 0, r, -1, 50 + i);
        for (int i = 0; i < 1 + r.nextInt(2); i++) tryPlace(Mod.SANDBAG, 0, 0, r, -1, 55 + i);
        forecourt(r, 2, 1, 2, 1);
    }

    private void layoutMountain(Random r) {
        int tc = R - 3;
        int ts = towerStyle(r);
        putTower(-tc, -tc, ts, 1);
        putTower(tc, -tc, ts, 2);
        putTower(-tc, tc, ts, 3);
        putTower(tc, tc, ts, 4);
        tryPlace(Mod.HQ, r.nextInt(2), 1, r, -1, 10);
        tryPlace(Mod.BARRACKS, r.nextInt(2), 2, r, -1, 20);
        tryPlace(r.nextBoolean() ? Mod.DEPOT : Mod.WORKSHOP, 0, 2, r, -1, 30);
        if (r.nextBoolean()) tryPlace(Mod.MESS, 0, 0, r, -1, 31);
        if (r.nextInt(10) < 4) tryPlace(Mod.ARMORY, 0, 0, r, -1, 32);
        Mod[] must = {Mod.RADIO, Mod.MORTAR};
        for (int i = 0; i < must.length; i++) tryPlace(must[i], 0, 0, r, -1, 40 + i);
        for (int i = 0; i < 2; i++) tryPlace(Mod.CRATES, 0, 0, r, -1, 50 + i);
        for (int i = 0; i < 2; i++) tryPlace(Mod.SANDBAG, 0, 0, r, -1, 55 + i);
        forecourt(r, 1, 1, 0, 1);
    }

    private void layoutBunker(Random r) {
        // центральный ДОТ вплотную к центру, дверью к воротам
        Place p = new Place(Mod.PILLBOX, 0, 0, 0, 7, 7, 0, 10);
        int[] rect = p.rect();
        solid.add(rect);
        blocked.add(inflate(withApron(rect, 0, 2), 1));
        places.add(p);
        tryPlace(Mod.TOWER, towerStyle(r), 3, r, -1, 11);
        tryPlace(Mod.DEPOT, 0, 0, r, -1, 12);
        if (r.nextBoolean()) tryPlace(Mod.TENT, 0, 0, r, -1, 13);
        kit(r, 1, true);
        for (int i = 0; i < 3; i++) tryPlace(Mod.TRENCH, 0, 0, r, -1, 20 + i);
        for (int i = 0; i < 2 + r.nextInt(2); i++) tryPlace(Mod.SANDBAG, 0, 0, r, -1, 30 + i);
        for (int i = 0; i < 2; i++) tryPlace(Mod.CRATES, 0, 0, r, -1, 40 + i);
        tryPlace(Mod.FUEL, 0, 0, r, -1, 45);
        forecourt(r, 1, 2, 0, 2);
    }

    private void layoutCamp(Random r) {
        Place fire = new Place(Mod.CAMPFIRE, 0, 0, 0, 7, 7, 0, 10);
        solid.add(new int[]{-1, -1, 1, 1});
        blocked.add(inflate(fire.rect(), 0));
        places.add(fire);
        int ts = T_TIMBER;
        tryPlace(Mod.TOWER, ts, 3, r, -1, 11);
        if (r.nextBoolean()) tryPlace(Mod.TOWER, ts, 3, r, -1, 12);
        tryPlace(Mod.CABIN, 0, 1, r, -1, 20);
        int cabins = 1 + r.nextInt(2);
        for (int i = 0; i < cabins; i++) tryPlace(Mod.CABIN, 1, 2, r, -1, 21 + i);
        int tents = 1 + r.nextInt(3);
        for (int i = 0; i < tents; i++) tryPlace(Mod.TENT, 0, 0, r, -1, 30 + i);
        tryPlace(Mod.TOTEM, 0, 0, r, -1, 40);
        if (r.nextInt(10) < 5) tryPlace(Mod.CAGE, 0, 0, r, -1, 41);
        tryPlace(Mod.DEPOT, 0, 0, r, -1, 42);
        for (int i = 0; i < 3; i++) tryPlace(Mod.CRATES, 0, 0, r, -1, 50 + i);
        if (r.nextBoolean()) tryPlace(Mod.FUEL, 0, 0, r, -1, 45);
        forecourt(r, 0, 0, 0, 0);
    }

    private void layoutWatch(Random r) {
        Place t = new Place(Mod.TOWER, 0, -2, 0, 7, 7, towerStyle(r), 1);
        int[] rect = t.rect();
        solid.add(rect);
        blocked.add(inflate(withApron(rect, 0, 2), 1));
        places.add(t);
        tryPlace(Mod.TENT, 0, 2, r, -1, 10);
        tryPlace(Mod.CAMPFIRE, 0, 0, r, -1, 11);
        for (int i = 0; i < 2; i++) tryPlace(Mod.SANDBAG, 0, 4, r, 0, 20 + i);
        for (int i = 0; i < 2; i++) tryPlace(Mod.TANKTRAP, 0, 4, r, 0, 30 + i);
        for (int i = 0; i < 2; i++) tryPlace(Mod.CRATES, 0, 0, r, -1, 40 + i);
    }

    // ------------------------------------------------------------------ точки для ИИ

    private static float yawOf(int dx, int dz) {
        double deg = -Math.toDegrees(Math.atan2(dx, dz));
        return (float) ((deg % 360.0 + 360.0) % 360.0);
    }

    private static int[] toSite(Place p, int mu, int mv) {
        return new int[]{p.u + WarzoneBasePlan.rotX(0, mu, mv, p.face), p.v + WarzoneBasePlan.rotZ(0, mu, mv, p.face)};
    }

    private void spot(Place p, int mu, int dy, int mv, SpotRole role, boolean outward) {
        int[] s = toSite(p, mu, mv);
        float yaw = outward ? yawOf(s[0], s[1]) : yawOf(WarzoneBasePlan.rotX(0, 0, 1, p.face), WarzoneBasePlan.rotZ(0, 0, 1, p.face));
        spots.add(new LSpot(s[0], dy, s[1], role, yaw));
    }

    /** Высота пола (feet) на площадке башни выбранного стиля. */
    static int towerFeetDy(int style) {
        return style == T_TIMBER ? 7 : 8;
    }

    /** Высота feet на крыше штаба. */
    static final int HQ_ROOF_FEET = 6;
    static final int PILLBOX_ROOF_FEET = 5;

    private void makeSpots(boolean bandit) {
        SpotRole soldier = bandit ? SpotRole.BANDIT : SpotRole.SOLDIER;
        for (Place p : places) {
            switch (p.mod) {
                case TOWER -> {
                    if (p.style == T_TIMBER) spot(p, 0, towerFeetDy(p.style), 0, SpotRole.SENTRY, true);
                    else spot(p, 2, towerFeetDy(p.style), 1, SpotRole.SENTRY, true);
                }
                case BARRACKS -> { spot(p, -1, 1, 0, soldier, false); spot(p, 1, 1, 1, soldier, false); }
                case HQ -> {
                    spot(p, -2, 1, 1, soldier, false);
                    spot(p, 2, 1, 0, soldier, false);
                    spot(p, -2, HQ_ROOF_FEET, 0, SpotRole.SENTRY, true);
                }
                case DEPOT, WORKSHOP, ARMORY -> spot(p, 0, 1, 1, soldier, false);
                case MESS -> spot(p, -2, 1, 1, soldier, false);
                case PILLBOX -> {
                    spot(p, 0, 1, 1, soldier, false);
                    spot(p, 0, PILLBOX_ROOF_FEET, 0, SpotRole.SENTRY, true);
                }
                case CABIN -> spot(p, 0, 1, 0, soldier, false);
                case TENT -> spot(p, 0, 1, 1, soldier, false);
                case CAMPFIRE -> { spot(p, -2, 1, 2, soldier, false); spot(p, 2, 1, 1, soldier, false); }
                case MORTAR -> spot(p, 0, 1, 2, soldier, false);
                default -> { }
            }
        }
        // караул у главных ворот
        if (fenced && (gates & 1) != 0) {
            addFree(-3, R - 3, soldier);
            addFree(3, R - 3, soldier);
        }
        // маршрут патруля по внутреннему кольцу
        int ringR = Math.max(4, R - 3);
        int got = 0;
        for (int a = 0; a < 360 && got < 8; a += 15) {
            double rad = Math.toRadians(a);
            int u, v;
            if (circular) {
                u = (int) Math.round(Math.sin(rad) * ringR);
                v = (int) Math.round(Math.cos(rad) * ringR);
            } else {
                double s = Math.sin(rad), c = Math.cos(rad);
                double m = Math.max(Math.abs(s), Math.abs(c));
                u = (int) Math.round(s / m * ringR);
                v = (int) Math.round(c / m * ringR);
            }
            if (!freeCell(u, v, 1)) continue;
            boolean near = false;
            for (LSpot s : spots) {
                if (s.role == SpotRole.PATROL_WAYPOINT && Math.abs(s.u - u) + Math.abs(s.v - v) < Math.max(6, R / 2)) { near = true; break; }
            }
            if (near) continue;
            spots.add(new LSpot(u, 1, v, SpotRole.PATROL_WAYPOINT, yawOf(-u, -v)));
            got++;
        }
        if (got < 2) {
            spots.add(new LSpot(0, 1, R - 2 > 0 ? Math.min(R - 2, 6) : 2, SpotRole.PATROL_WAYPOINT, 0f));
            spots.add(new LSpot(0, 1, -Math.min(R - 2, 6), SpotRole.PATROL_WAYPOINT, 180f));
        }
    }

    private void addFree(int u, int v, SpotRole role) {
        if (freeCell(u, v, 0)) spots.add(new LSpot(u, 1, v, role, yawOf(u, v)));
    }

    /** Клетка не внутри построек (с запасом m). */
    boolean freeCell(int u, int v, int m) {
        int[] c = {u, v, u, v};
        for (int[] s : solid) if (overlap(inflate(c, m), s)) return false;
        return true;
    }
}
