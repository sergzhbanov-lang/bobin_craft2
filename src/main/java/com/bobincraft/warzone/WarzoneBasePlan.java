package com.bobincraft.warzone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Детерминированный план размещения вражеских баз, аванпостов, бункеров, лагерей мародёров,
 * дозорных вышек и связывающей их дорожной сети в измерении «Зона боевых действий».
 *
 * Вычисляется один раз от (seed, size) и рельефа {@link WarzoneTerrain}. Не зависит от чанков:
 * любой чанк при генерации обращается к этому плану, чтобы выровнять фундамент, проложить дороги
 * и построить свою часть укреплений. Также хранит точки спавна часовых на вышках, солдат/бандитов
 * и маршруты патрулей для 3-го этапа (ИИ-противники).
 */
public final class WarzoneBasePlan {

    /** Тип вражеского объекта. */
    public enum SiteType {
        FORTRESS("Укреплённая база", "База", 33, 52, 96, true),
        OUTPOST("Военный аванпост", "Аванпост", 22, 38, 74, true),
        BUNKER_COMPLEX("Бункерный узел", "Бункер", 17, 29, 62, false),
        BANDIT_CAMP("Лагерь мародёров", "Лагерь", 18, 31, 64, false),
        WATCHTOWER("Дозорный блокпост", "Блокпост", 12, 22, 50, false),
        /** Горная крепость на высоком хребте: дорога-серпантин или скрытая тропа ведёт к ней снизу. */
        MOUNTAIN_FORT("Горная крепость", "Цитадель", 26, 50, 150, true);

        public final String title;
        public final String shortName;
        /** Радиус ровной площадки под постройки. */
        public final int flatRadius;
        /** Радиус плавного сопряжения высоты с естественным рельефом. */
        public final int blendRadius;
        /** Минимальный зазор между центрами соседних объектов. */
        public final int minSpacing;
        /** Квадратная форма крепостной стены (учитывает углы при выравнивании рельефа). */
        public final boolean squareCompound;

        SiteType(String title, String shortName, int flatRadius, int blendRadius, int minSpacing, boolean squareCompound) {
            this.title = title;
            this.shortName = shortName;
            this.flatRadius = flatRadius;
            this.blendRadius = blendRadius;
            this.minSpacing = minSpacing;
            this.squareCompound = squareCompound;
        }
    }

    /** Роль тактической точки для ИИ (этап 3). */
    public enum SpotRole {
        /** Часовой/снайпер на вышке или крыше бункера. */
        SENTRY,
        /** Регулярный солдат в казарме, штабе или у ворот. */
        SOLDIER,
        /** Бандит/мародёр в лагере или окопе. */
        BANDIT,
        /** Точка маршрута патруля вокруг базы или вдоль дороги. */
        PATROL_WAYPOINT
    }

    /** Точка размещения противника или вейпоинт патруля (в мировых координатах). */
    public record TacticalSpot(int x, int y, int z, SpotRole role, float yaw) {}

    /** Отрезок грунтово-щебёночной военной дороги между объектами. */
    public record RoadSegment(int x0, int z0, int x1, int z1, double halfWidth, int minX, int maxX, int minZ, int maxZ) {
        public RoadSegment(int x0, int z0, int x1, int z1, double halfWidth) {
            this(x0, z0, x1, z1, halfWidth,
                    Math.min(x0, x1) - (int) Math.ceil(halfWidth + 3),
                    Math.max(x0, x1) + (int) Math.ceil(halfWidth + 3),
                    Math.min(z0, z1) - (int) Math.ceil(halfWidth + 3),
                    Math.max(z0, z1) + (int) Math.ceil(halfWidth + 3));
        }

        /** Расстояние до отрезка без отсечения по габаритам (для широких проверок). */
        public double rawDistanceTo(double x, double z) {
            double dx = x1 - x0;
            double dz = z1 - z0;
            double lenSq = dx * dx + dz * dz;
            if (lenSq < 1e-4) return Math.hypot(x - x0, z - z0);
            double t = Math.max(0.0, Math.min(1.0, ((x - x0) * dx + (z - z0) * dz) / lenSq));
            return Math.hypot(x - (x0 + t * dx), z - (z0 + t * dz));
        }

        /** Расстояние от точки (x, z) до отрезка дороги. */
        public double distanceTo(int x, int z) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return Double.POSITIVE_INFINITY;
            double dx = x1 - x0;
            double dz = z1 - z0;
            double lenSq = dx * dx + dz * dz;
            if (lenSq < 1e-4) return Math.hypot(x - x0, z - z0);
            double t = ((x - x0) * dx + (z - z0) * dz) / lenSq;
            t = Math.max(0.0, Math.min(1.0, t));
            double px = x0 + t * dx;
            double pz = z0 + t * dz;
            return Math.hypot(x - px, z - pz);
        }
    }


    /**
     * Мост через реку/озеро. Настил идёт по линейному профилю между высотами берегов (не ниже
     * {@code SEA_LEVEL + 3}), поэтому он всегда приподнят над водой и плавно стыкуется с дорогой.
     * С вероятностью 40% мост «боевой»: разрушен, с провалом посередине и обломками.
     */
    public static final class Bridge {
        public static final double DECK_HALF = 2.5;

        public final double[] px, pz, cum;
        public final double length;
        /** Верхняя грань земли (y блока + 1) у въезда и у съезда. */
        public final int topA, topB;
        public final boolean damaged;
        public final long salt;
        public final double gapStart, gapEnd;
        public final int minX, maxX, minZ, maxZ;

        Bridge(double[] px, double[] pz, int topA, int topB, boolean damaged, long salt, double gapCenter, double gapWidth) {
            this.px = px;
            this.pz = pz;
            this.cum = new double[px.length];
            double acc = 0;
            for (int i = 1; i < px.length; i++) {
                acc += Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
                cum[i] = acc;
            }
            this.length = acc;
            this.topA = topA;
            this.topB = topB;
            this.damaged = damaged;
            this.salt = salt;
            this.gapStart = gapCenter - gapWidth * 0.5;
            this.gapEnd = gapCenter + gapWidth * 0.5;
            double mnx = Double.MAX_VALUE, mxx = -Double.MAX_VALUE, mnz = Double.MAX_VALUE, mxz = -Double.MAX_VALUE;
            for (int i = 0; i < px.length; i++) {
                mnx = Math.min(mnx, px[i]);
                mxx = Math.max(mxx, px[i]);
                mnz = Math.min(mnz, pz[i]);
                mxz = Math.max(mxz, pz[i]);
            }
            this.minX = (int) Math.floor(mnx - DECK_HALF - 3);
            this.maxX = (int) Math.ceil(mxx + DECK_HALF + 3);
            this.minZ = (int) Math.floor(mnz - DECK_HALF - 3);
            this.maxZ = (int) Math.ceil(mxz + DECK_HALF + 3);
        }

        /** out[0] = расстояние до оси моста, out[1] = позиция вдоль моста (может быть < 0 или > length у торцов). */
        public boolean query(int x, int z, double[] out) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) return false;
            double best = Double.POSITIVE_INFINITY;
            double bestArc = 0;
            int last = px.length - 2;
            for (int i = 0; i <= last; i++) {
                double dx = px[i + 1] - px[i];
                double dz = pz[i + 1] - pz[i];
                double lenSq = dx * dx + dz * dz;
                if (lenSq < 1e-9) continue;
                double tu = ((x - px[i]) * dx + (z - pz[i]) * dz) / lenSq;
                double t = Math.max(0.0, Math.min(1.0, tu));
                double d = Math.hypot(x - (px[i] + t * dx), z - (pz[i] + t * dz));
                if (d < best) {
                    best = d;
                    double seg = Math.sqrt(lenSq);
                    double use = (i == 0 && tu < 0.0) || (i == last && tu > 1.0) ? tu : t;
                    bestArc = cum[i] + use * seg;
                }
            }
            if (best == Double.POSITIVE_INFINITY) return false;
            out[0] = best;
            out[1] = bestArc;
            return true;
        }

        /** Колонка (x, z) принадлежит настилу моста; arcOut[0] получает позицию вдоль моста. */
        public boolean onDeck(int x, int z, double[] tmp) {
            if (!query(x, z, tmp)) return false;
            return tmp[0] <= DECK_HALF && tmp[1] >= 0.0 && tmp[1] <= length;
        }

        /** Высота верхней грани настила в полублоках (2 * S) на позиции arc. */
        public int surfaceHalves(double arc) {
            double f = length <= 0 ? 0 : Math.max(0.0, Math.min(1.0, arc / length));
            double s = topA + (topB - topA) * f;
            s = Math.max(s, WarzoneTerrain.SEA_LEVEL + 3);
            return (int) Math.round(s * 2.0);
        }

        public boolean inGap(double arc) {
            return damaged && arc >= gapStart && arc <= gapEnd;
        }
    }

    /**
     * Горная дорога-серпантин или скрытая тропа к горной крепости. Высота профиля {@code py}
     * задаётся равномерным уклоном (NaN — участок без выравнивания, лишь рисуется поверхность).
     */
    public static final class TrailPath {
        public final boolean trail;
        /** true — ледяной тоннель внутри горы (задан профиль высоты); false — лишь поверхностная тропа-связка. */
        public final boolean tunnel;
        public final double halfWidth;
        public final double[] px, pz, py;
        public final int minX, maxX, minZ, maxZ;
        /** Подножие: точка, где выровненный участок смыкается с землёй. */
        public final int footX, footZ;

        TrailPath(boolean trail, double halfWidth, double[] px, double[] pz, double[] py, int footX, int footZ) {
            this.trail = trail;
            this.tunnel = py.length > 0 && !Double.isNaN(py[0]);
            this.halfWidth = halfWidth;
            this.px = px;
            this.pz = pz;
            this.py = py;
            this.footX = footX;
            this.footZ = footZ;
            double mnx = Double.MAX_VALUE, mxx = -Double.MAX_VALUE, mnz = Double.MAX_VALUE, mxz = -Double.MAX_VALUE;
            for (int i = 0; i < px.length; i++) {
                mnx = Math.min(mnx, px[i]);
                mxx = Math.max(mxx, px[i]);
                mnz = Math.min(mnz, pz[i]);
                mxz = Math.max(mxz, pz[i]);
            }
            int m = (int) Math.ceil(halfWidth + 8);
            this.minX = (int) Math.floor(mnx) - m;
            this.maxX = (int) Math.ceil(mxx) + m;
            this.minZ = (int) Math.floor(mnz) - m;
            this.maxZ = (int) Math.ceil(mxz) + m;
        }

        public boolean mayContain(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        /** out[0] = расстояние до оси, out[1] = высота профиля (NaN, если участок не выравнивается). */
        public boolean query(int x, int z, double[] out) {
            if (!mayContain(x, z)) return false;
            return queryAt(x, z, out);
        }

        /**
         * То же для произвольной точки. Если {@code out.length > 2}, out[2] = позиция вдоль пути
         * (номер сегмента + доля): по ней расставляются фонари тоннеля.
         */
        public boolean queryAt(double x, double z, double[] out) {
            double best = Double.POSITIVE_INFINITY;
            double bestY = Double.NaN;
            double bestPos = 0.0;
            for (int i = 0; i + 1 < px.length; i++) {
                double dx = px[i + 1] - px[i];
                double dz = pz[i + 1] - pz[i];
                double lenSq = dx * dx + dz * dz;
                if (lenSq < 1e-9) continue;
                double t = Math.max(0.0, Math.min(1.0, ((x - px[i]) * dx + (z - pz[i]) * dz) / lenSq));
                double d = Math.hypot(x - (px[i] + t * dx), z - (pz[i] + t * dz));
                if (d < best) {
                    best = d;
                    bestY = py[i] + (py[i + 1] - py[i]) * t;
                    bestPos = i + t;
                }
            }
            if (best == Double.POSITIVE_INFINITY) return false;
            out[0] = best;
            out[1] = bestY;
            if (out.length > 2) out[2] = bestPos;
            return true;
        }

        public int length() {
            return px.length;
        }
    }

    /** Описание одной сгенерированной вражеской базы или аванпоста. */
    public static final class Site {
        public final int id;
        public final SiteType type;
        public final String callsign;
        public final int cx;
        public final int cz;
        public final int baseY;
        public final WarzoneTerrain.Region region;
        public final int flatRadius;
        public final int blendRadius;
        /** Поворот базы: 0 = юг (+Z), 1 = запад (-X), 2 = север (-Z), 3 = восток (+X). */
        public final int rotation;
        /** Вариант планировки внутри типа (0..2). */
        public final int variant;
        /** Внутренняя фракция (материалы, ограда, флаги, набор построек). */
        public final Faction faction;
        public final List<TacticalSpot> spots;

        Site(int id, SiteType type, String callsign, int cx, int cz, int baseY,
             WarzoneTerrain.Region region, int rotation, int variant, Faction faction, List<TacticalSpot> spots) {
            this.faction = faction == null ? Faction.REGULAR_ARMY : faction;
            this.id = id;
            this.type = type;
            this.callsign = callsign;
            this.cx = cx;
            this.cz = cz;
            this.baseY = baseY;
            this.region = region;
            this.flatRadius = type.flatRadius;
            this.blendRadius = type.blendRadius;
            this.rotation = rotation & 3;
            this.variant = variant;
            this.spots = Collections.unmodifiableList(spots);
        }

        public double distanceTo(double x, double z) {
            return Math.hypot(x - cx, z - cz);
        }

        /** Эффективное расстояние с учётом квадратной геометрии стен для ровного фундамента по углам. */
        public double padDistance(int x, int z) {
            double dx = Math.abs(x - cx);
            double dz = Math.abs(z - cz);
            double euclid = Math.hypot(dx, dz);
            if (!type.squareCompound) return euclid;
            double chebyshev = Math.max(dx, dz);
            return chebyshev * 0.65 + euclid * 0.35;
        }

        /** Координата точки перед главными воротами базы (для удобной телепортации и подхода). */
        public int entranceX() {
            int offset = flatRadius + 2;
            return switch (rotation) {
                case 1 -> cx - offset;
                case 2 -> cx;
                case 3 -> cx + offset;
                default -> cx;
            };
        }

        public int entranceZ() {
            int offset = flatRadius + 2;
            return switch (rotation) {
                case 1 -> cz;
                case 2 -> cz - offset;
                case 3 -> cz;
                default -> cz + offset;
            };
        }

        /** Мировая X-координата точки на оси базы: front = у главных ворот (+V), иначе у тыловых (-V). */
        public int gateX(boolean front, int offset) {
            return rotX(cx, 0, front ? offset : -offset, rotation);
        }

        public int gateZ(boolean front, int offset) {
            return rotZ(cz, 0, front ? offset : -offset, rotation);
        }

        public int countSpots(SpotRole role) {
            int c = 0;
            for (TacticalSpot s : spots) {
                if (s.role() == role) c++;
            }
            return c;
        }
    }

    private static final String[] CALLSIGNS = {
            "Север", "Бастион", "Рубеж", "Гранит", "Цитадель", "Сокол",
            "Коршун", "Утёс", "Заслон", "Шторм", "Редут", "Вал",
            "Кобальт", "Оникс", "Тайфун", "Фронт", "Пик", "Ворон",
            "Стрела", "Кречет", "Гром", "Вепрь", "Кварц", "Дозор"
    };

    private final List<Site> sites;
    private final List<RoadSegment> roads;
    private final List<Bridge> bridges;
    private final List<TrailPath> paths;

    private WarzoneBasePlan(List<Site> sites, List<RoadSegment> roads, List<Bridge> bridges, List<TrailPath> paths) {
        this.sites = Collections.unmodifiableList(sites);
        this.roads = Collections.unmodifiableList(roads);
        this.bridges = Collections.unmodifiableList(bridges);
        this.paths = Collections.unmodifiableList(paths);
    }

    public List<Bridge> bridges() {
        return bridges;
    }

    public List<TrailPath> paths() {
        return paths;
    }

    /** Мост, настил которого (с запасом) покрывает колонку; out получает {расстояние, позиция вдоль моста}. */
    public Bridge bridgeAt(int x, int z, double[] out) {
        for (Bridge b : bridges) {
            if (b.query(x, z, out) && out[0] <= Bridge.DECK_HALF + 1.0 && out[1] >= -1.0 && out[1] <= b.length + 1.0) {
                return b;
            }
        }
        return null;
    }

    public List<Bridge> bridgesForChunk(int chunkMinX, int chunkMinZ, int margin) {
        List<Bridge> result = null;
        for (Bridge b : bridges) {
            if (b.maxX >= chunkMinX - margin && b.minX <= chunkMinX + 15 + margin
                    && b.maxZ >= chunkMinZ - margin && b.minZ <= chunkMinZ + 15 + margin) {
                if (result == null) result = new ArrayList<>(2);
                result.add(b);
            }
        }
        return result == null ? Collections.emptyList() : result;
    }

    public List<TrailPath> pathsForChunk(int chunkMinX, int chunkMinZ, int margin) {
        List<TrailPath> result = null;
        for (TrailPath p : paths) {
            if (p.maxX >= chunkMinX - margin && p.minX <= chunkMinX + 15 + margin
                    && p.maxZ >= chunkMinZ - margin && p.minZ <= chunkMinZ + 15 + margin) {
                if (result == null) result = new ArrayList<>(2);
                result.add(p);
            }
        }
        return result == null ? Collections.emptyList() : result;
    }

    /** Колонка лежит у горной дороги/тропы (не сажаем растительность). */
    public boolean nearPath(int x, int z, double margin) {
        if (paths.isEmpty()) return false;
        double[] q = new double[2];
        for (TrailPath p : paths) {
            if (p.query(x, z, q) && q[0] <= p.halfWidth + margin) return true;
        }
        return false;
    }

    /** Широкая «просека» вокруг дорог: горы вдоль дороги расступаются в перевал. 1 — на дороге, 0 — дальше 22 блоков. */
    public double roadPass(int x, int z) {
        double best = Double.POSITIVE_INFINITY;
        for (RoadSegment seg : roads) {
            if (x < seg.minX() - 16 || x > seg.maxX() + 16 || z < seg.minZ() - 16 || z > seg.maxZ() + 16) continue;
            double d = seg.rawDistanceTo(x, z);
            if (d < best) best = d;
        }
        if (best >= 22.0) return 0.0;
        return 1.0 - WarzoneNoise.smoothstep(5.0, 22.0, best);
    }

    /** Расстояние от точки до ближайшей дороги или горной тропы (для воронок на дорогах). */
    public double roadDistance(double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (RoadSegment seg : roads) {
            double d = seg.rawDistanceTo(x, z);
            if (d < best) best = d;
        }
        for (TrailPath p : paths) {
            if (x < p.minX - 30 || x > p.maxX + 30 || z < p.minZ - 30 || z > p.maxZ + 30) continue;
            for (int i = 0; i + 1 < p.px.length; i++) {
                double dx = p.px[i + 1] - p.px[i];
                double dz = p.pz[i + 1] - p.pz[i];
                double lenSq = dx * dx + dz * dz;
                if (lenSq < 1e-9) continue;
                double t = Math.max(0.0, Math.min(1.0, ((x - p.px[i]) * dx + (z - p.pz[i]) * dz) / lenSq));
                double d = Math.hypot(x - (p.px[i] + t * dx), z - (p.pz[i] + t * dz));
                if (d < best) best = d;
            }
        }
        return best;
    }

    public List<Site> sites() {
        return sites;
    }

    public List<RoadSegment> roads() {
        return roads;
    }

    public Site byId(int id) {
        for (Site s : sites) {
            if (s.id == id) return s;
        }
        return null;
    }

    public Site nearestSite(double x, double z) {
        Site best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (Site s : sites) {
            double d = s.distanceTo(x, z);
            if (d < bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }

    /** Возвращает все объекты, чья зона застройки (с запасом margin) пересекает данный чанк. */
    public List<Site> sitesForChunk(int chunkMinX, int chunkMinZ, int margin) {
        int cx0 = chunkMinX - margin;
        int cz0 = chunkMinZ - margin;
        int cx1 = chunkMinX + 15 + margin;
        int cz1 = chunkMinZ + 15 + margin;
        List<Site> result = null;
        for (Site s : sites) {
            int r = s.flatRadius + 8;
            if (s.cx + r >= cx0 && s.cx - r <= cx1 && s.cz + r >= cz0 && s.cz - r <= cz1) {
                if (result == null) result = new ArrayList<>(2);
                result.add(s);
            }
        }
        return result == null ? Collections.emptyList() : result;
    }

    /** Возвращает все отрезки дорог, пересекающие данный чанк (с запасом margin). */
    public List<RoadSegment> roadsForChunk(int chunkMinX, int chunkMinZ, int margin) {
        int cx0 = chunkMinX - margin;
        int cz0 = chunkMinZ - margin;
        int cx1 = chunkMinX + 15 + margin;
        int cz1 = chunkMinZ + 15 + margin;
        List<RoadSegment> result = null;
        for (RoadSegment seg : roads) {
            if (seg.maxX() >= cx0 && seg.minX() <= cx1 && seg.maxZ() >= cz0 && seg.minZ() <= cz1) {
                if (result == null) result = new ArrayList<>(4);
                result.add(seg);
            }
        }
        return result == null ? Collections.emptyList() : result;
    }

    /**
     * Степень принадлежности точки (x, z) к полотну военной дороги:
     * 1.0 — центр дороги, 0..1 — обочина, 0.0 — вне дороги (или внутри территории базы).
     */
    public double roadFactor(int x, int z) {
        for (Site s : sites) {
            if (s.padDistance(x, z) < s.flatRadius - 2.5) return 0.0;
        }
        double minDist = Double.POSITIVE_INFINITY;
        double hw = 2.2;
        for (RoadSegment seg : roads) {
            double d = seg.distanceTo(x, z);
            if (d < minDist) {
                minDist = d;
                hw = seg.halfWidth();
            }
        }
        if (minDist > hw + 1.6) return 0.0;
        return 1.0 - WarzoneNoise.smoothstep(hw - 0.5, hw + 1.4, minDist);
    }

    // ================================================================== Построение плана

    static WarzoneBasePlan generate(WarzoneTerrain terrain) {
        Random rnd = new Random(WarzoneNoise.mix(terrain.seed + 0x5A17E5L));
        WarzoneNoise roadWarp = new WarzoneNoise(WarzoneNoise.mix(terrain.seed + 0x70AD1L));

        double scale = Math.max(0.30, Math.min(4.0, (terrain.size / 1024.0) * (terrain.size / 1024.0)));
        int targetFortresses = Math.max(1, (int) Math.round(3 * scale));
        int targetOutposts = Math.max(2, (int) Math.round(5 * scale));
        int targetBunkers = Math.max(1, (int) Math.round(3 * scale));
        int targetBandits = Math.max(1, (int) Math.round(3 * scale));
        int targetTowers = Math.max(2, (int) Math.round(4 * scale));

        List<Site> placed = new ArrayList<>();
        WarzoneTerrain.Sample tmp = new WarzoneTerrain.Sample();

        // 1. Гарантированный передовой дозорный блокпост к северу от площадки высадки (на дистанции ~115..140 бл.),
        // чтобы игрок сразу встречал дорогу и первый вражеский заслон по пути вглубь зоны.
        placeForwardCheckpoint(terrain, rnd, tmp, placed);

        // 2. Гарантированный южно-центральный аванпост на дистанции ~185..235 блоков от высадки
        placeEarlyOutpost(terrain, rnd, tmp, placed);

        // 3. Крупные укреплённые базы (FORTRESS) в глубине арены (северный, западный и восточный сектора)
        placeSitesOfType(terrain, rnd, tmp, placed, SiteType.FORTRESS, targetFortresses, 240.0, true);

        // 4. Военные аванпосты (OUTPOST)
        int remainingOutposts = Math.max(0, targetOutposts - countOfType(placed, SiteType.OUTPOST));
        placeSitesOfType(terrain, rnd, tmp, placed, SiteType.OUTPOST, remainingOutposts, 145.0, false);

        // 5. Бункерные узлы (BUNKER_COMPLEX)
        placeSitesOfType(terrain, rnd, tmp, placed, SiteType.BUNKER_COMPLEX, targetBunkers, 135.0, false);

        // 6. Лагеря мародёров (BANDIT_CAMP)
        placeSitesOfType(terrain, rnd, tmp, placed, SiteType.BANDIT_CAMP, targetBandits, 130.0, false);

        // 7. Дозорные блокпосты и вышки (WATCHTOWER)
        int remainingTowers = Math.max(0, targetTowers - countOfType(placed, SiteType.WATCHTOWER));
        placeSitesOfType(terrain, rnd, tmp, placed, SiteType.WATCHTOWER, remainingTowers, 105.0, false);

        // 7b. Горные крепости на высоких хребтах (отдельный генератор случайных чисел — раскладка остальных объектов не меняется)
        Random mrnd = new Random(WarzoneNoise.mix(terrain.seed + 0x30B7A1L));
        List<TrailPath> accessPaths = new ArrayList<>();
        List<int[]> footNodes = new ArrayList<>();
        List<TrailPath> pendingTrails = new ArrayList<>();
        int targetForts = Math.max(1, (int) Math.round(3 * scale));   // 1.0.4: 3 крепости на арене 1024 (раньше 2)
        placeMountainForts(terrain, mrnd, tmp, placed, targetForts, accessPaths, footNodes, pendingTrails);

        // Сортируем по удалению от точки высадки (чтобы #1 был ближайшим к игроку, а главные базы — дальше)
        placed.sort(Comparator.comparingDouble(s -> Math.hypot(s.cx - terrain.landingX(), s.cz - terrain.landingZ())));

        Random frnd = new Random(WarzoneNoise.mix(terrain.seed + 0xFAC710L));
        List<Site> finalSites = new ArrayList<>(placed.size());
        for (int i = 0; i < placed.size(); i++) {
            Site raw = placed.get(i);
            int id = i + 1;
            String callsign = raw.type.shortName + " «" + CALLSIGNS[i % CALLSIGNS.length] + "-" + id + "»";
            Faction faction = pickFaction(raw.type, raw.region, frnd);
            List<TacticalSpot> spots = buildSpotsForSite(raw.type, faction, raw.region, raw.cx, raw.baseY, raw.cz, raw.rotation,
                    raw.variant, SiteBuilder.designSeed(terrain.seed, id));
            finalSites.add(new Site(id, raw.type, callsign, raw.cx, raw.cz, raw.baseY,
                    raw.region, raw.rotation, raw.variant, faction, spots));
        }

        // 8. Строим связную сеть военных дорог от площадки высадки ко всем базам и аванпостам
        //    (горные крепости соединяются не дорогой, а своим серпантином/тропой; подножие серпантина — узел сети)
        List<int[]> chains = new ArrayList<>();
        List<RoadSegment> roads = buildRoadNetwork(terrain, finalSites, footNodes, roadWarp, chains);

        // 9. Мосты там, где дороги пересекают реки и озёра
        List<Bridge> bridges = detectBridges(terrain, roads, chains);

        // 10. Скрытые тропы: от подножия — к ближайшей дороге (без выравнивания рельефа)
        List<TrailPath> paths = new ArrayList<>(accessPaths);
        Random trnd = new Random(WarzoneNoise.mix(terrain.seed + 0x7A11L));
        for (TrailPath graded : pendingTrails) {
            TrailPath link = linkTrail(graded, roads, trnd);
            if (link != null) paths.add(link);
        }

        return new WarzoneBasePlan(finalSites, roads, bridges, paths);
    }

    private static int countOfType(List<Site> list, SiteType type) {
        int c = 0;
        for (Site s : list) if (s.type == type) c++;
        return c;
    }

    private static void placeForwardCheckpoint(WarzoneTerrain terrain, Random rnd,
                                               WarzoneTerrain.Sample tmp, List<Site> placed) {
        int lx = terrain.landingX();
        int lz = terrain.landingZ();
        for (int attempt = 0; attempt < 90; attempt++) {
            double angle = -Math.PI * 0.5 + (rnd.nextDouble() - 0.5) * 0.75; // на север от высадки
            double dist = 112.0 + rnd.nextDouble() * 34.0;
            int cx = ((int) Math.round(lx + Math.cos(angle) * dist)) & ~1;
            int cz = ((int) Math.round(lz + Math.sin(angle) * dist)) & ~1;
            Site candidate = tryCreateSite(terrain, rnd, tmp, placed, SiteType.WATCHTOWER, cx, cz, 95.0, false);
            if (candidate != null) {
                placed.add(candidate);
                return;
            }
        }
    }

    private static void placeEarlyOutpost(WarzoneTerrain terrain, Random rnd,
                                          WarzoneTerrain.Sample tmp, List<Site> placed) {
        int lx = terrain.landingX();
        int lz = terrain.landingZ();
        for (int attempt = 0; attempt < 120; attempt++) {
            double angle = -Math.PI * 0.5 + (rnd.nextDouble() - 0.5) * 1.15;
            double dist = 178.0 + rnd.nextDouble() * 52.0;
            int cx = ((int) Math.round(lx + Math.cos(angle) * dist)) & ~1;
            int cz = ((int) Math.round(lz + Math.sin(angle) * dist)) & ~1;
            Site candidate = tryCreateSite(terrain, rnd, tmp, placed, SiteType.OUTPOST, cx, cz, 150.0, false);
            if (candidate != null) {
                placed.add(candidate);
                return;
            }
        }
    }

    private static void placeSitesOfType(WarzoneTerrain terrain, Random rnd, WarzoneTerrain.Sample tmp,
                                         List<Site> placed, SiteType type, int count,
                                         double minLandingDist, boolean preferDeepNorth) {
        int margin = WarzoneTerrain.RIM_WIDTH + type.blendRadius + 12;
        int span = terrain.size - 2 * margin;
        if (span <= 32) return;

        for (int n = 0; n < count; n++) {
            Site best = null;
            double bestScore = -1e9;
            int tries = 140;
            for (int t = 0; t < tries; t++) {
                int cx = (terrain.min + margin + rnd.nextInt(span)) & ~1;
                int cz = (terrain.min + margin + rnd.nextInt(span)) & ~1;
                if (preferDeepNorth && t < 95 && cz > terrain.half * 0.18) {
                    cz = (terrain.min + margin + rnd.nextInt(Math.max(32, (int) (span * 0.65)))) & ~1;
                }
                Site cand = tryCreateSite(terrain, rnd, tmp, placed, type, cx, cz, minLandingDist, t > 100);
                if (cand == null) continue;

                // Оцениваем кандидата: предпочитаем равномерное распределение по арене
                double nearestOther = Double.POSITIVE_INFINITY;
                for (Site other : placed) {
                    double d = Math.hypot(cx - other.cx, cz - other.cz);
                    if (d < nearestOther) nearestOther = d;
                    if (other.type == type) {
                        nearestOther = Math.min(nearestOther, d * 0.82);
                    }
                }
                double score = Math.min(nearestOther, 320.0) + rnd.nextDouble() * 25.0;
                if (type == SiteType.BANDIT_CAMP && (cand.region == WarzoneTerrain.Region.BADLANDS || cand.region == WarzoneTerrain.Region.MARSH)) {
                    score += 35.0;
                }
                if (type == SiteType.BUNKER_COMPLEX && cand.region == WarzoneTerrain.Region.HIGHLANDS) {
                    score += 30.0;
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = cand;
                }
            }
            if (best != null) {
                placed.add(best);
            }
        }
    }

    private static Site tryCreateSite(WarzoneTerrain terrain, Random rnd, WarzoneTerrain.Sample tmp,
                                      List<Site> placed, SiteType type, int cx, int cz,
                                      double minLandingDist, boolean relaxed) {
        int margin = WarzoneTerrain.RIM_WIDTH + type.blendRadius + (relaxed ? 4 : 10);
        if (terrain.edgeDistance(cx, cz) < margin) return null;

        double ldist = Math.hypot(cx - terrain.landingX(), cz - terrain.landingZ());
        if (ldist < Math.max(minLandingDist, WarzoneTerrain.LANDING_BLEND + type.blendRadius + 24.0)) {
            return null;
        }

        for (Site s : placed) {
            double minDist = (type.minSpacing + s.type.minSpacing) * (relaxed ? 0.44 : 0.54)
                    + (type.flatRadius + s.flatRadius) * 0.80;
            if (Math.hypot(cx - s.cx, cz - s.cz) < minDist) {
                return null;
            }
        }

        terrain.naturalSample(cx, cz, tmp);
        int centerH = tmp.height;
        WarzoneTerrain.Region centerRegion = tmp.region;

        // Не ставим базы посреди глубоких рек/озёр и на крутых горных пиках
        if (centerH < WarzoneTerrain.SEA_LEVEL + (relaxed ? 0 : 1)) return null;
        if (tmp.channel > (relaxed ? 0.35 : 0.18)) return null;
        if (tmp.mountain > (relaxed ? 0.78 : 0.62) || centerH > 106) return null;

        // Проверяем перепад высот по краям площадки, чтобы база не врезалась в отвесную скалу
        int checkR = Math.max(8, (int) (type.flatRadius * 0.70));
        int minH = centerH;
        int maxH = centerH;
        int sumH = centerH;
        int waterCorners = 0;
        int[][] dirs = {{checkR, 0}, {-checkR, 0}, {0, checkR}, {0, -checkR}};
        for (int[] d : dirs) {
            terrain.naturalSample(cx + d[0], cz + d[1], tmp);
            minH = Math.min(minH, tmp.height);
            maxH = Math.max(maxH, tmp.height);
            sumH += tmp.height;
            if (tmp.height <= WarzoneTerrain.SEA_LEVEL || tmp.channel > 0.35) {
                waterCorners++;
            }
        }
        if (waterCorners > (relaxed ? 2 : 1)) return null;
        if (maxH - minH > (relaxed ? 20 : 13)) return null;

        int avgH = Math.round(sumH / 5.0f);
        int baseY = Math.max(WarzoneTerrain.SEA_LEVEL + 2, Math.min(104, avgH));

        // Ориентируем фасад базы преимущественно в сторону центра/высадки
        double toLandingX = terrain.landingX() - cx;
        double toLandingZ = terrain.landingZ() - cz;
        int rotation;
        if (Math.abs(toLandingZ) >= Math.abs(toLandingX)) {
            rotation = toLandingZ >= 0 ? 0 : 2;
        } else {
            rotation = toLandingX >= 0 ? 3 : 1;
        }
        if (rnd.nextInt(4) == 0) {
            rotation = (rotation + (rnd.nextBoolean() ? 1 : 3)) & 3;
        }
        int variant = rnd.nextInt(3);

        return new Site(0, type, "", cx, cz, baseY, centerRegion, rotation, variant, null, Collections.emptyList());
    }

    // ================================================================== Фракции

    private static Faction pickFaction(SiteType type, WarzoneTerrain.Region region, Random rnd) {
        switch (type) {
            case BANDIT_CAMP: {
                if (rnd.nextInt(4) != 0) {
                    if (region == WarzoneTerrain.Region.BADLANDS) return Faction.ASH_RAIDERS;
                    if (region == WarzoneTerrain.Region.MARSH) return Faction.MARSH_REAVERS;
                    return Faction.BLACK_CROWS;
                }
                Faction[] gangs = {Faction.ASH_RAIDERS, Faction.BLACK_CROWS, Faction.MARSH_REAVERS};
                return gangs[rnd.nextInt(gangs.length)];
            }
            case MOUNTAIN_FORT:
                return rnd.nextBoolean() ? Faction.FROST_CORPS : Faction.IRON_WOLVES;
            default: {
                int r = rnd.nextInt(10);
                return r < 4 ? Faction.REGULAR_ARMY : (r < 7 ? Faction.IRON_WOLVES : Faction.GLASS_COMPANY);
            }
        }
    }

    // ================================================================== Горные крепости и подъездные пути

    private static final int MOUNTAIN_GATE_OFFSET = 24;

    /**
     * Серпантин/тропа должны спускаться до РАВНИНЫ (раньше «подножие» обрывалось на y<=92 посреди склона,
     * и дорога на базу начиналась очень высоко над землёй). Равнина здесь ~68..80, холмы до ~85.
     */
    private static final int FOOT_MAX_Y_STRICT = 78;
    private static final double FOOT_MAX_MOUNTAIN_STRICT = 0.22;
    private static final int FOOT_MAX_Y_LOOSE = 86;
    private static final double FOOT_MAX_MOUNTAIN_LOOSE = 0.40;
    /** Шагов (по 5 блоков) ровного участка от ворот, прежде чем тоннель начнёт спускаться. */
    private static final int TUNNEL_FLAT_STEPS = 3;
    /** Минимальная толща породы над профилем пола (потолок тоннеля ~5 + свод 2–3). */
    private static final double TUNNEL_MIN_COVER = 9.0;

    /**
     * Минимальная высота площадки «снежной» крепости. Снег на вершинах начинается выше y≈128..140
     * (см. WarzoneSurface.highlandTop), поэтому крепость на отметке 142+ гарантированно стоит среди снегов.
     */
    public static final int SNOW_FORT_MIN_Y = 142;

    private static void placeMountainForts(WarzoneTerrain terrain, Random rnd, WarzoneTerrain.Sample tmp,
                                           List<Site> placed, int count, List<TrailPath> accessPaths,
                                           List<int[]> footNodes, List<TrailPath> pendingTrails) {
        SiteType type = SiteType.MOUNTAIN_FORT;
        int margin = WarzoneTerrain.RIM_WIDTH + type.blendRadius + 16;
        int span = terrain.size - 2 * margin;
        if (span <= 64) return;

        for (int n = 0; n < count; n++) {
            Site best = null;
            TrailPath bestPath = null;
            double bestScore = -1e9;
            // Сначала ищем площадку на снежной вершине (baseY >= SNOW_FORT_MIN_Y); если такой нет — прежние, более мягкие условия
            // pass 0: снежная вершина, pass 1: любая (оба — спуск строго до равнины); pass 2: запасной, чуть мягче
            for (int pass = 0; pass < 3 && best == null; pass++) {
                int minBaseY = pass == 0 ? SNOW_FORT_MIN_Y : 0;
                boolean strictFoot = pass < 2;
                int valid = 0;
                for (int t = 0; t < (pass == 0 ? 1800 : 1400) && valid < 3; t++) {
                    int cx = (terrain.min + margin + rnd.nextInt(span)) & ~1;
                    int cz = (terrain.min + margin + rnd.nextInt(span)) & ~1;
                    Site cand = tryCreateMountainSite(terrain, rnd, tmp, placed, cx, cz, minBaseY);
                    if (cand == null) continue;
                    boolean trail = rnd.nextBoolean();
                    TrailPath path = buildAccessPath(terrain, cand, trail, rnd, strictFoot);
                    if (path == null || pathsConflict(path, accessPaths)) continue;
                    valid++;
                    double nearest = Double.POSITIVE_INFINITY;
                    for (Site o : placed) nearest = Math.min(nearest, Math.hypot(cx - o.cx, cz - o.cz));
                    double score = Math.min(nearest, 400.0) + rnd.nextDouble() * 40.0;
                    if (score > bestScore) {
                        bestScore = score;
                        best = cand;
                        bestPath = path;
                    }
                }
            }
            if (best == null) continue;
            placed.add(best);
            accessPaths.add(bestPath);
            if (bestPath.trail) {
                pendingTrails.add(bestPath);
            } else {
                footNodes.add(new int[]{bestPath.footX, bestPath.footZ});
            }
        }
    }

    private static Site tryCreateMountainSite(WarzoneTerrain terrain, Random rnd, WarzoneTerrain.Sample tmp,
                                              List<Site> placed, int cx, int cz, int minBaseY) {
        SiteType type = SiteType.MOUNTAIN_FORT;
        int margin = WarzoneTerrain.RIM_WIDTH + type.blendRadius + 16;
        if (terrain.edgeDistance(cx, cz) < margin) return null;
        if (Math.hypot(cx - terrain.landingX(), cz - terrain.landingZ()) < 260.0) return null;
        for (Site s : placed) {
            // между горными крепостями — большой зазор, от остальных объектов (они стоят внизу) — обычный
            double need = s.type == SiteType.MOUNTAIN_FORT ? 230.0 : 40.0 + s.flatRadius + type.flatRadius;
            if (Math.hypot(cx - s.cx, cz - s.cz) < need) return null;
        }

        terrain.naturalSample(cx, cz, tmp);
        int centerH = tmp.height;
        if (tmp.mountain < 0.55 || centerH < 108 || centerH > 205) return null;

        // Площадка должна сидеть на плечевой террасе: умеренный перепад в радиусе 22 и горы вокруг до 40 блоков
        int sum = centerH, cnt = 1, minH = centerH, maxH = centerH;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0;
            terrain.naturalSample(cx + (int) Math.round(Math.cos(a) * 22), cz + (int) Math.round(Math.sin(a) * 22), tmp);
            sum += tmp.height;
            cnt++;
            minH = Math.min(minH, tmp.height);
            maxH = Math.max(maxH, tmp.height);
            if (tmp.channel > 0.2 || tmp.height <= WarzoneTerrain.SEA_LEVEL + 4) return null;
        }
        if (maxH - minH > 40) return null;
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2.0 + 0.4;
            terrain.naturalSample(cx + (int) Math.round(Math.cos(a) * 40), cz + (int) Math.round(Math.sin(a) * 40), tmp);
            if (tmp.mountain < 0.3) return null;
        }
        int baseY = Math.max(100, Math.min(215, Math.round(sum / (float) cnt)));
        if (baseY < minBaseY) return null;

        double toLandingX = terrain.landingX() - cx;
        double toLandingZ = terrain.landingZ() - cz;
        int rotation;
        if (Math.abs(toLandingZ) >= Math.abs(toLandingX)) {
            rotation = toLandingZ >= 0 ? 0 : 2;
        } else {
            rotation = toLandingX >= 0 ? 3 : 1;
        }
        if (rnd.nextInt(3) == 0) rotation = (rotation + (rnd.nextBoolean() ? 1 : 3)) & 3;
        int variant = rnd.nextInt(3);
        return new Site(0, type, "", cx, cz, baseY, WarzoneTerrain.Region.HIGHLANDS, rotation, variant, null,
                Collections.emptyList());
    }

    /**
     * Прокладывает путь вниз от ворот крепости с постоянным уклоном: шагая по склону, «ходок» выбирает курс,
     * при котором профиль пути ближе всего к земле (врезка/насыпь минимальны) — получаются серпантины,
     * огибающие гору. Возвращает null, если спуск не дошёл до подножия.
     * Дорога (trail=false) выходит из главных ворот, скрытая тропа (trail=true) — из тыловых.
     */
    private static TrailPath walkDownPath(WarzoneTerrain terrain, Site site, boolean trail, Random rnd, boolean strictFoot) {
        final int footMaxY = strictFoot ? FOOT_MAX_Y_STRICT : FOOT_MAX_Y_LOOSE;
        final double footMaxMt = strictFoot ? FOOT_MAX_MOUNTAIN_STRICT : FOOT_MAX_MOUNTAIN_LOOSE;
        final double step = 5.0;
        final double grade = trail ? 0.46 : 0.30;
        final double hw = trail ? 2.2 : 3.0;      // радиус ледяного тоннеля: диаметр ~5 (тропа) / ~7 (дорога)
        boolean front = !trail;
        double sx = site.gateX(front, MOUNTAIN_GATE_OFFSET);
        double sz = site.gateZ(front, MOUNTAIN_GATE_OFFSET);
        double dirX = sx - site.cx;
        double dirZ = sz - site.cz;
        double heading = Math.atan2(dirZ, dirX);

        List<double[]> pts = new ArrayList<>();
        WarzoneTerrain.Sample tmp = new WarzoneTerrain.Sample();
        WarzoneTerrain.Sample lookTmp = new WarzoneTerrain.Sample();
        double x = sx, z = sz, y = site.baseY;
        pts.add(new double[]{x, z, y});
        boolean arrived = false;
        int groundAtEnd = 0;

        for (int i = 0; i < 420; i++) {
            // первые шаги — ровно (внутри плато крепости), затем постоянный спуск
            double yDown = i < TUNNEL_FLAT_STEPS ? y : y - grade * step;
            double yN = yDown;
            double bestCost = Double.POSITIVE_INFINITY;
            double bestAng = heading, bx = x, bz = z;
            int bestH = 0;
            double bestMt = 1.0;
            double bestY = yDown;
            for (int k = -5; k <= 5; k++) {
                double ang = heading + Math.toRadians(11.0 * k);
                double nx = x + Math.cos(ang) * step;
                double nz = z + Math.sin(ang) * step;
                int ix = (int) Math.round(nx);
                int iz = (int) Math.round(nz);
                double cost = Math.abs(k) * 1.2 + rnd.nextDouble() * 0.8;
                if (terrain.edgeDistance(ix, iz) < WarzoneTerrain.RIM_WIDTH + 18) cost += 400.0;
                terrain.naturalSample(ix, iz, tmp);
                // Тоннель идёт ВНУТРИ горы: вниз он спускается только пока над потолком остаётся порода,
                // иначе идёт горизонтально (огибает склон по «горизонтали») — тоннель не вскрывается канавой
                // и не повисает над землёй. У подножия (низина) спускаемся к земле и выходим наружу.
                boolean lowland = tmp.height <= footMaxY + 6;
                if (i < TUNNEL_FLAT_STEPS) {
                    yN = y;
                } else if (lowland) {
                    yN = yDown;
                } else {
                    yN = (tmp.height - yDown >= TUNNEL_MIN_COVER) ? yDown : y;
                }
                double cover = tmp.height - yN;
                if (i >= TUNNEL_FLAT_STEPS && !lowland) {
                    // заглядываем на 2 и 3 шага вперёд по этому курсу: не уходим туда, где склон обрывается под тоннелем
                    for (int la = 2; la <= 3; la++) {
                        int lx = (int) Math.round(x + Math.cos(ang) * step * la);
                        int lz = (int) Math.round(z + Math.sin(ang) * step * la);
                        terrain.naturalSample(lx, lz, lookTmp);
                        if (lookTmp.height <= footMaxY + 6) break;   // впереди низина — выход близко
                        double lc = lookTmp.height - yN;
                        if (lc < TUNNEL_MIN_COVER) cost += (TUNNEL_MIN_COVER - lc) * 2.5;
                    }
                }
                if (i >= TUNNEL_FLAT_STEPS) {
                    if (!lowland) {
                        if (cover < TUNNEL_MIN_COVER) cost += (TUNNEL_MIN_COVER - cover) * 6.0;
                        if (cover > 45.0) cost += (cover - 45.0) * 0.4;
                        cost += 0.8;   // небольшой штраф за каждый ровный шаг — не стоять на месте
                    } else {
                        cost += Math.abs(cover);
                    }
                }
                if (tmp.height <= WarzoneTerrain.SEA_LEVEL + 1 || tmp.channel > 0.3) cost += 90.0;
                for (int j = 0; j < pts.size() - 5; j++) {
                    double[] q = pts.get(j);
                    double d = Math.hypot(nx - q[0], nz - q[1]);
                    // витки серпантина не должны сближаться: на близком расстоянии высота «перескакивала» бы между ветками
                    if (d < 11.0) cost += 3000.0;
                    else if (d < 20.0) cost += 140.0 * (1.0 - (d - 11.0) / 9.0);
                }
                // после выхода из зоны площадки не заходим обратно в радиус её выравнивания (иначе гора вокруг крепости «подтягивает» дорогу)
                if (i > 12 && Math.hypot(nx - site.cx, nz - site.cz) < site.blendRadius + 6.0) cost += 220.0;
                if (cost < bestCost) {
                    bestCost = cost;
                    bestAng = ang;
                    bx = nx;
                    bz = nz;
                    bestH = tmp.height;
                    bestMt = tmp.mountain;
                    bestY = yN;
                }
            }
            heading = bestAng;
            x = bx;
            z = bz;
            y = bestY;
            pts.add(new double[]{x, z, y});
            if (i >= 6 && bestH <= footMaxY && bestMt <= footMaxMt && y <= bestH + 1.0
                    && y >= bestH - Math.max(2.0, grade * step + 0.5)) {
                arrived = true;
                groundAtEnd = bestH;
                break;
            }
            if (y < WarzoneTerrain.SEA_LEVEL + 4) break;
        }
        if (!arrived || pts.size() > 190) return null;   // слишком длинный тоннель (> ~950 блоков) не нужен
        // Тоннель обязан идти внутри горы: если заметная часть пути «вскрыта» (потолок выше земли) или висит в воздухе — отбраковываем
        int checked = 0, exposed = 0;
        for (int k = TUNNEL_FLAT_STEPS + 1; k < pts.size() - 3; k++) {
            double[] q = pts.get(k);
            terrain.naturalSample((int) Math.round(q[0]), (int) Math.round(q[1]), tmp);
            checked++;
            if (tmp.height - q[2] < 6.0) exposed++;
        }
        if (checked > 0 && exposed > Math.max(2, checked / 14)) return null;
        for (double[] q : pts) {
            // путь у самого скального кольца арены не годится — кольцо «подтянуло» бы профиль
            if (terrain.edgeDistance((int) Math.round(q[0]), (int) Math.round(q[1])) < WarzoneTerrain.RIM_WIDTH + 6) return null;
        }

        double[] last = pts.get(pts.size() - 1);
        last[2] = groundAtEnd;
        int n = pts.size();
        double[] px = new double[n], pz = new double[n], py = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = pts.get(i)[0];
            pz[i] = pts.get(i)[1];
            py[i] = pts.get(i)[2];
        }
        return new TrailPath(trail, hw, px, pz, py, (int) Math.round(px[n - 1]), (int) Math.round(pz[n - 1]));
    }

    /** Два подъездных пути ближе 16 блоков друг к другу спорили бы за высоту рельефа. */
    private static boolean pathsConflict(TrailPath a, List<TrailPath> others) {
        for (TrailPath o : others) {
            if (a.maxX < o.minX || a.minX > o.maxX || a.maxZ < o.minZ || a.minZ > o.maxZ) continue;
            for (int i = 0; i < a.px.length; i++) {
                for (int j = 0; j < o.px.length; j++) {
                    if (Math.hypot(a.px[i] - o.px[j], a.pz[i] - o.pz[j]) < 16.0) return true;
                }
            }
        }
        return false;
    }

    private static TrailPath buildAccessPath(WarzoneTerrain terrain, Site site, boolean trail, Random rnd, boolean strictFoot) {
        return walkDownPath(terrain, site, trail, rnd, strictFoot);
    }

    /** Продлевает скрытую тропу от подножия до ближайшей дороги (рельеф не выравнивается, тропа вьётся по земле). */
    private static TrailPath linkTrail(TrailPath graded, List<RoadSegment> roads, Random rnd) {
        if (roads.isEmpty()) return null;
        double fx = graded.footX, fz = graded.footZ;
        double bestD = Double.POSITIVE_INFINITY, tx = fx, tz = fz;
        for (RoadSegment seg : roads) {
            double dx = seg.x1() - seg.x0();
            double dz = seg.z1() - seg.z0();
            double lenSq = dx * dx + dz * dz;
            double t = lenSq < 1e-4 ? 0.0 : Math.max(0.0, Math.min(1.0, ((fx - seg.x0()) * dx + (fz - seg.z0()) * dz) / lenSq));
            double qx = seg.x0() + t * dx;
            double qz = seg.z0() + t * dz;
            double d = Math.hypot(fx - qx, fz - qz);
            if (d < bestD) {
                bestD = d;
                tx = qx;
                tz = qz;
            }
        }
        if (bestD > 260.0 || bestD < 6.0) return null;
        int steps = Math.max(2, (int) Math.round(bestD / 9.0));
        double ux = (tx - fx) / bestD, uz = (tz - fz) / bestD;
        double nx = -uz, nz = ux;
        double[] px = new double[steps + 1], pz = new double[steps + 1], py = new double[steps + 1];
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double env = Math.sin(t * Math.PI);
            double jitter = (rnd.nextDouble() - 0.5) * 8.0 * env;
            px[i] = fx + (tx - fx) * t + nx * jitter;
            pz[i] = fz + (tz - fz) * t + nz * jitter;
            py[i] = Double.NaN;
        }
        return new TrailPath(true, 1.0, px, pz, py, graded.footX, graded.footZ);
    }

    // ================================================================== Мосты

    private static List<Bridge> detectBridges(WarzoneTerrain terrain, List<RoadSegment> segs, List<int[]> chains) {
        List<Bridge> out = new ArrayList<>();
        WarzoneTerrain.Sample tmp = new WarzoneTerrain.Sample();
        int chainIdx = 0;
        for (int[] ch : chains) {
            chainIdx++;
            List<double[]> pts = new ArrayList<>();
            for (int si = ch[0]; si < ch[1]; si++) {
                RoadSegment sg = segs.get(si);
                double dx = sg.x1() - sg.x0();
                double dz = sg.z1() - sg.z0();
                int steps = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz)));
                for (int k = (si == ch[0] ? 0 : 1); k <= steps; k++) {
                    double t = k / (double) steps;
                    pts.add(new double[]{sg.x0() + dx * t, sg.z0() + dz * t});
                }
            }
            int n = pts.size();
            if (n < 8) continue;
            int[] hgt = new int[n];
            boolean[] wet = new boolean[n];
            for (int i = 0; i < n; i++) {
                terrain.naturalSample((int) Math.round(pts.get(i)[0]), (int) Math.round(pts.get(i)[1]), tmp);
                hgt[i] = tmp.height;
                wet[i] = tmp.height <= WarzoneTerrain.SEA_LEVEL;
            }
            int i = 0;
            while (i < n) {
                if (!wet[i]) {
                    i++;
                    continue;
                }
                int lastWet = i;
                int k = i;
                while (k < n && k <= lastWet + 4) {
                    if (wet[k]) lastWet = k;
                    k++;
                }
                if (lastWet - i + 1 < 2) {
                    i = lastWet + 1;
                    continue;
                }
                int a = i;
                while (a > 0 && i - a < 16 && hgt[a] < WarzoneTerrain.SEA_LEVEL + 3) a--;
                int b = lastWet;
                while (b < n - 1 && b - lastWet < 16 && hgt[b] < WarzoneTerrain.SEA_LEVEL + 3) b++;
                if (b - a >= 6) {
                    int m = b - a + 1;
                    double[] bx = new double[m], bz = new double[m];
                    for (int q = 0; q < m; q++) {
                        bx[q] = pts.get(a + q)[0];
                        bz[q] = pts.get(a + q)[1];
                    }
                    Random r = new Random(WarzoneNoise.mix(terrain.seed + 0xB41D6EL + chainIdx * 7919L + a));
                    boolean damaged = r.nextDouble() < 0.40;
                    double length = m - 1;
                    double gapCenter = length * (0.38 + 0.24 * r.nextDouble());
                    double gapWidth = 3.0 + r.nextInt(4);
                    out.add(new Bridge(bx, bz, hgt[a] + 1, hgt[b] + 1, damaged, r.nextLong(), gapCenter, gapWidth));
                }
                i = b + 1;
            }
        }
        return out;
    }


    // ================================================================== Точки для ИИ (этап 3)

    /** Точки для ИИ берутся из раскладки базы ({@link SiteDesign}): они всегда стоят на реальном полу построек. */
    private static List<TacticalSpot> buildSpotsForSite(SiteType type, Faction faction, WarzoneTerrain.Region region, int cx, int baseY, int cz,
                                                        int rot, int variant, long designSeed) {
        List<TacticalSpot> list = new ArrayList<>();
        SiteDesign d = SiteDesign.design(type, faction, region, variant, designSeed);
        for (SiteDesign.LSpot sp : d.spots) {
            addSpot(list, cx, baseY, cz, rot, sp.u, sp.dy, sp.v, sp.role, sp.yaw);
        }
        return list;
    }

    private static void addSpot(List<TacticalSpot> out, int cx, int baseY, int cz, int rot,
                                int u, int dy, int v, SpotRole role, float localYaw) {
        int wx = rotX(cx, u, v, rot);
        int wz = rotZ(cz, u, v, rot);
        float yaw = (localYaw + rot * 90.0f) % 360.0f;
        out.add(new TacticalSpot(wx, baseY + dy, wz, role, yaw));
    }

    static int rotX(int cx, int u, int v, int rot) {
        return switch (rot & 3) {
            case 1 -> cx - v;
            case 2 -> cx - u;
            case 3 -> cx + v;
            default -> cx + u;
        };
    }

    static int rotZ(int cz, int u, int v, int rot) {
        return switch (rot & 3) {
            case 1 -> cz + u;
            case 2 -> cz - v;
            case 3 -> cz - u;
            default -> cz + v;
        };
    }

    // ================================================================== Сеть военных дорог

    private static List<RoadSegment> buildRoadNetwork(WarzoneTerrain terrain, List<Site> allSites, List<int[]> extraNodes,
                                                      WarzoneNoise roadWarp, List<int[]> chains) {
        // Горные крепости в дорожную сеть не входят: к ним ведёт собственный серпантин/тропа
        List<Site> sites = new ArrayList<>(allSites.size());
        for (Site s : allSites) {
            if (s.type != SiteType.MOUNTAIN_FORT) sites.add(s);
        }
        if (sites.isEmpty() && extraNodes.isEmpty()) return Collections.emptyList();
        List<RoadSegment> segments = new ArrayList<>();

        // Узел 0 — выход с посадочной площадки игрока, затем базы и аванпосты, затем подножия горных дорог
        int n = sites.size() + extraNodes.size() + 1;
        int[] nx = new int[n];
        int[] nz = new int[n];
        int[] stopR = new int[n];
        nx[0] = terrain.landingX();
        nz[0] = terrain.landingZ() - 8;
        stopR[0] = 8;
        for (int i = 0; i < sites.size(); i++) {
            Site s = sites.get(i);
            nx[i + 1] = s.cx;
            nz[i + 1] = s.cz;
            stopR[i + 1] = s.flatRadius - 2;
        }
        for (int e = 0; e < extraNodes.size(); e++) {
            int idx = sites.size() + 1 + e;
            nx[idx] = extraNodes.get(e)[0];
            nz[idx] = extraNodes.get(e)[1];
            stopR[idx] = 3;
        }

        // Остовное дерево Прима: гарантирует связность от площадки высадки до всех баз
        boolean[] inTree = new boolean[n];
        inTree[0] = true;
        boolean[][] connected = new boolean[n][n];

        for (int step = 0; step < n - 1; step++) {
            int bestA = -1, bestB = -1;
            double bestD = Double.POSITIVE_INFINITY;
            for (int a = 0; a < n; a++) {
                if (!inTree[a]) continue;
                for (int b = 0; b < n; b++) {
                    if (inTree[b]) continue;
                    double d = Math.hypot(nx[a] - nx[b], nz[a] - nz[b]);
                    if (d < bestD) {
                        bestD = d;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            if (bestA == -1) break;
            inTree[bestB] = true;
            connected[bestA][bestB] = connected[bestB][bestA] = true;
            addCurvedRoad(terrain, segments, chains, nx[bestA], nz[bestA], stopR[bestA], nx[bestB], nz[bestB], stopR[bestB], roadWarp);
        }

        // Добавляем до 3 поперечных рокадных дорог между близкими объектами для обходных маршрутов
        int extraLinks = 0;
        for (int a = 1; a < n && extraLinks < 3; a++) {
            int candidateB = -1;
            double bestD = 260.0;
            for (int b = a + 1; b < n; b++) {
                if (connected[a][b]) continue;
                double d = Math.hypot(nx[a] - nx[b], nz[a] - nz[b]);
                if (d < bestD && d > 95.0) {
                    bestD = d;
                    candidateB = b;
                }
            }
            if (candidateB != -1) {
                connected[a][candidateB] = connected[candidateB][a] = true;
                addCurvedRoad(terrain, segments, chains, nx[a], nz[a], stopR[a], nx[candidateB], nz[candidateB], stopR[candidateB], roadWarp);
                extraLinks++;
            }
        }

        return segments;
    }

    /** Штраф за прокладку дороги через точку: горы, крутые подъёмы, вода и край арены. */
    private static double roadPenalty(WarzoneTerrain terrain, WarzoneTerrain.Sample tmp, double x, double z) {
        int ix = (int) Math.round(x);
        int iz = (int) Math.round(z);
        if (terrain.edgeDistance(ix, iz) < WarzoneTerrain.RIM_WIDTH + 20) return 300.0;
        terrain.naturalSample(ix, iz, tmp);
        double p = tmp.mountain * 70.0 + Math.max(0, tmp.height - 92) * 0.9;
        if (tmp.height <= WarzoneTerrain.SEA_LEVEL) p += 20.0;
        else if (tmp.channel > 0.3) p += 12.0;
        return p;
    }

    private static void addCurvedRoad(WarzoneTerrain terrain, List<RoadSegment> out, List<int[]> chains,
                                      int ax, int az, int stopA,
                                      int bx, int bz, int stopB,
                                      WarzoneNoise roadWarp) {
        double dx = bx - ax;
        double dz = bz - az;
        double totalDist = Math.hypot(dx, dz);
        if (totalDist <= stopA + stopB + 12.0) return;

        double ux = dx / totalDist;
        double uz = dz / totalDist;
        double px = -uz;
        double pz = ux;

        double startDist = Math.max(4.0, stopA - 2.0);
        double endDist = totalDist - Math.max(4.0, stopB - 2.0);
        double span = endDist - startDist;
        int steps = Math.max(3, (int) Math.round(span / 22.0));

        int chainStart = out.size();
        int prevX = (int) Math.round(ax + ux * startDist);
        int prevZ = (int) Math.round(az + uz * startDist);
        double prevOff = 0.0;
        WarzoneTerrain.Sample tmp = new WarzoneTerrain.Sample();

        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            double d = startDist + t * span;
            double baseX = ax + ux * d;
            double baseZ = az + uz * d;
            // Плавный изгиб на промежуточных узлах (концы зафиксированы у ворот баз)
            double envelope = Math.sin(t * Math.PI);
            double offset = roadWarp.fbm(baseX / 90.0 + 7.3, baseZ / 90.0 - 4.1, 2) * 16.0 * envelope;
            if (i < steps && envelope > 0.2) {
                // Дорога обходит горы, воду и крутые подъёмы: ищем лучшее боковое смещение узла (до ±48 блоков)
                double bestOff = offset;
                double bestCost = Double.POSITIVE_INFINITY;
                for (int k = -6; k <= 6; k++) {
                    double cand = offset + k * 8.0 * envelope;
                    double cx = baseX + px * cand;
                    double cz = baseZ + pz * cand;
                    double cost = roadPenalty(terrain, tmp, cx, cz)
                            + 0.5 * roadPenalty(terrain, tmp, (cx + prevX) * 0.5, (cz + prevZ) * 0.5)
                            + Math.abs(cand - offset) * 0.12 + Math.abs(cand - prevOff) * 0.25;
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestOff = cand;
                    }
                }
                offset = bestOff;
            }
            prevOff = offset;
            int curX = (int) Math.round(baseX + px * offset);
            int curZ = (int) Math.round(baseZ + pz * offset);
            out.add(new RoadSegment(prevX, prevZ, curX, curZ, 2.2));
            prevX = curX;
            prevZ = curZ;
        }
        chains.add(new int[]{chainStart, out.size()});
    }
}
