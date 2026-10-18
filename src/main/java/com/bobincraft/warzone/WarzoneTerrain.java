package com.bobincraft.warzone;

import java.util.concurrent.ConcurrentHashMap;

import static com.bobincraft.warzone.WarzoneNoise.lerp;
import static com.bobincraft.warzone.WarzoneNoise.smoothstep;

/**
 * Рельеф «Зоны боевых действий» — чистая функция от (seed, x, z), без Minecraft.
 *
 * Арена — квадрат {@code size × size} блоков с центром в (0, 0). По краю арены рельеф поднимается в
 * непроходимое кольцо скал (RIM), за пределами квадрата — пустота.
 *
 * Слои рельефа (снизу вверх):
 *  1. пологие холмы + крупная «волна» высот;
 *  2. горные хребты (маска + хребтовый шум) — «Нагорье»;
 *  3. террасные плато — «Бесплодные земли» (терракота);
 *  4. болота — плоские низины почти вровень с морем;
 *  5. реки с долинами и озёра (естественные преграды и укрытия);
 *  6. воронки от снарядов (шрамы войны, случайно по сетке);
 *  7. ровная посадочная площадка игрока и выровненные террасы под вражеские базы/аванпосты ({@link WarzoneBasePlan});
 *  8. стена скал по краю арены.
 */
public final class WarzoneTerrain {
    public static final int SEA_LEVEL = 62;
    public static final int MIN_Y = 0;
    public static final int HEIGHT = 256;

    /** Ширина кольца скал по краю арены. */
    public static final int RIM_WIDTH = 40;
    /** Средняя высота верха скального кольца. */
    public static final int RIM_TOP = 150;

    /** Высота посадочной площадки и радиусы её выравнивания. */
    public static final int LANDING_Y = 70;
    public static final double LANDING_FLAT = 22.0;
    public static final double LANDING_BLEND = 52.0;

    private static final int CRATER_CELL = 56;

    /** Вероятность того, что воронка, задевающая дорогу, «разбивает» её (иначе воронка не появляется вовсе). */
    private static final double CRATER_BREAKS_ROAD = 0.30;

    /** Пороги горной маски (чуть шире прежних 0.05..0.30 — горы занимают больше площади). */
    private static final double MT_LO = 0.02;
    private static final double MT_HI = 0.28;

    public enum Region { PLAINS, HIGHLANDS, BADLANDS, MARSH }

    /** Результат выборки одной колонки. */
    public static final class Sample {
        /** Высота верхнего твёрдого блока. */
        public int height;
        public Region region;
        /** 1 — центр русла реки, 0 — вне реки. */
        public double channel;
        /** 1 — дно долины, 0 — вне долины. */
        public double valley;
        /** Интенсивность «шрама» воронки (выжженная земля), 0..1. */
        public double scar;
        /** Горная маска, 0..1. */
        public double mountain;
        /** Расстояние до края арены (≥ 0 внутри). */
        public int edge;
        /** Степень принадлежности к дороге (0..1). */
        public double road;
        /** Степень принадлежности к территории вражеской базы/аванпоста (0..1). */
        public double basePad;
        /** Насколько дорога разрушена воронкой (0..1): поверхность дороги рвётся и перетекает в воронку. */
        public double roadBroken;
        /** Степень принадлежности к горной дороге/тропе к крепости (0..1) и её тип. */
        public double path;
        public boolean trail;
        /** Вес пустыни (дюны, столбы, оазисы) внутри BADLANDS, 0..1; остальное в BADLANDS — ступенчатая меза. */
        public double desert;
        /** Высота дюн/барханов над уровнем пустыни (блоки). */
        public double dune;
        /** Скальный столб (останец) в этой колонке, 0..1. */
        public double pillar;
        /** Влияние оазиса, 0..1 (1 — водоём и берег). */
        public double oasis;
        /** Заболоченный кочкарник (мшистые кочки над водой), 0..1. */
        public double bog;
        /** Глубокая тёмная заводь болота, 0..1. */
        public double pool;
    }

    public final long seed;
    public final int size;
    public final int half;
    /** Минимальная (включительно) и максимальная (исключительно) координаты арены. */
    public final int min;
    public final int max;

    private final WarzoneNoise warpX, warpZ, broad, hills, mount, ridge, mesaN, wetN, riverN, riverW, lakeN, rimN, forestN;
    private final WarzoneNoise detW1, detW2, ravN, plateauN, duneN, desertN, bogN, poolN;
    private final long oasisSalt, barchanSalt, pillarSalt;
    private final double windCos, windSin;
    private final long craterSalt;
    /** Кэш решений по воронкам у дорог: 1 — подавлена, 2 — разрушает дорогу, 0 — дороги рядом нет. */
    private final ConcurrentHashMap<Long, Integer> craterModes = new ConcurrentHashMap<>();
    private final int landingZ;
    private final WarzoneBasePlan basePlan;

    public WarzoneTerrain(long seed, int size) {
        if (size < 256 || size % 16 != 0) {
            throw new IllegalArgumentException("Размер арены должен быть кратен 16 и не меньше 256: " + size);
        }
        this.seed = seed;
        this.size = size;
        this.half = size / 2;
        this.min = -half;
        this.max = half;
        this.landingZ = (int) (half * 0.68);

        this.warpX = new WarzoneNoise(WarzoneNoise.mix(seed + 1));
        this.warpZ = new WarzoneNoise(WarzoneNoise.mix(seed + 2));
        this.broad = new WarzoneNoise(WarzoneNoise.mix(seed + 3));
        this.hills = new WarzoneNoise(WarzoneNoise.mix(seed + 4));
        this.mount = new WarzoneNoise(WarzoneNoise.mix(seed + 5));
        this.ridge = new WarzoneNoise(WarzoneNoise.mix(seed + 6));
        this.mesaN = new WarzoneNoise(WarzoneNoise.mix(seed + 7));
        this.wetN = new WarzoneNoise(WarzoneNoise.mix(seed + 8));
        this.riverN = new WarzoneNoise(WarzoneNoise.mix(seed + 9));
        this.riverW = new WarzoneNoise(WarzoneNoise.mix(seed + 10));
        this.lakeN = new WarzoneNoise(WarzoneNoise.mix(seed + 11));
        this.rimN = new WarzoneNoise(WarzoneNoise.mix(seed + 12));
        this.forestN = new WarzoneNoise(WarzoneNoise.mix(seed + 14));
        this.craterSalt = WarzoneNoise.mix(seed + 13);
        this.detW1 = new WarzoneNoise(WarzoneNoise.mix(seed + 20));
        this.detW2 = new WarzoneNoise(WarzoneNoise.mix(seed + 21));
        this.ravN = new WarzoneNoise(WarzoneNoise.mix(seed + 22));
        this.plateauN = new WarzoneNoise(WarzoneNoise.mix(seed + 23));
        this.duneN = new WarzoneNoise(WarzoneNoise.mix(seed + 24));
        this.desertN = new WarzoneNoise(WarzoneNoise.mix(seed + 25));
        this.bogN = new WarzoneNoise(WarzoneNoise.mix(seed + 26));
        this.poolN = new WarzoneNoise(WarzoneNoise.mix(seed + 27));
        this.oasisSalt = WarzoneNoise.mix(seed + 28);
        this.barchanSalt = WarzoneNoise.mix(seed + 29);
        this.pillarSalt = WarzoneNoise.mix(seed + 30);
        double wind = WarzoneNoise.unit(WarzoneNoise.mix(seed + 31)) * Math.PI * 2.0;
        this.windCos = Math.cos(wind);
        this.windSin = Math.sin(wind);

        // Детерминированный план вражеских баз, аванпостов и дорог поверх естественного рельефа
        this.basePlan = WarzoneBasePlan.generate(this);

        // Лесные укрытия: лёгкие точки интереса вне списка баз (ошибка планирования не роняет мир — укрытий просто не будет)
        HideoutPlan hp;
        String hpErr = null;
        try {
            hp = HideoutPlan.generate(this);
        } catch (Throwable e) {
            hp = HideoutPlan.empty();
            hpErr = String.valueOf(e);
        }
        this.hideoutPlan = hp;
        this.hideoutPlanError = hpErr;

        // Придорожные блокпосты и старые поля боёв (этап c): тоже лёгкие точки интереса вне списка баз
        PoiPlan pp;
        String ppErr = null;
        try {
            pp = PoiPlan.generate(this, hp);
        } catch (Throwable e) {
            pp = PoiPlan.empty();
            ppErr = String.valueOf(e);
        }
        this.poiPlan = pp;
        this.poiPlanError = ppErr;
    }

    private final PoiPlan poiPlan;
    private final String poiPlanError;

    public PoiPlan poiPlan() {
        return poiPlan;
    }

    String poiPlanError() {
        return poiPlanError;
    }

    private final HideoutPlan hideoutPlan;
    private final String hideoutPlanError;

    public HideoutPlan hideoutPlan() {
        return hideoutPlan;
    }

    String hideoutPlanError() {
        return hideoutPlanError;
    }

    public WarzoneBasePlan basePlan() {
        return basePlan;
    }

    private volatile WarzoneBlueprints blueprints;

    /** Кэш чертежей сооружений (мосты и т.д.): каждый строится один раз, чанки лишь вставляют свои срезы. */
    public WarzoneBlueprints blueprints() {
        WarzoneBlueprints b = blueprints;
        if (b == null) {
            synchronized (this) {
                b = blueprints;
                if (b == null) {
                    b = new WarzoneBlueprints(this);
                    blueprints = b;
                }
            }
        }
        return b;
    }

    private volatile WarzoneFlora flora;

    /** Процедурная флора (деревья, кусты, густота) — чистая функция от seed, с кэшем ячеек. */
    WarzoneFlora flora() {
        WarzoneFlora f = flora;
        if (f == null) {
            synchronized (this) {
                f = flora;
                if (f == null) {
                    f = new WarzoneFlora(this);
                    flora = f;
                }
            }
        }
        return f;
    }

    /** Дешёвая проверка «рядом может быть оазис» (по ячейке, без шума) — чтобы не считать полную выборку зря. */
    boolean oasisNear(int x, int z) {
        int ocx = Math.floorDiv(x, OASIS_CELL);
        int ocz = Math.floorDiv(z, OASIS_CELL);
        long oh = WarzoneNoise.hash(oasisSalt, ocx, ocz);
        if (WarzoneNoise.unit(oh) >= 0.5) return false;
        double ox = ocx * OASIS_CELL + 34 + WarzoneNoise.unit(WarzoneNoise.mix(oh + 2)) * (OASIS_CELL - 68);
        double oz = ocz * OASIS_CELL + 34 + WarzoneNoise.unit(WarzoneNoise.mix(oh + 3)) * (OASIS_CELL - 68);
        return Math.hypot(x - ox, z - oz) < 40.0;
    }

    // ------------------------------------------------------------------ геометрия арены

    public boolean inside(int x, int z) {
        return x >= min && x < max && z >= min && z < max;
    }

    /** Расстояние от колонки до ближайшего края арены: ≥ 0 внутри, < 0 снаружи. */
    public int edgeDistance(int x, int z) {
        int dx = Math.min(x - min, max - 1 - x);
        int dz = Math.min(z - min, max - 1 - z);
        return Math.min(dx, dz);
    }

    public int landingX() {
        return 0;
    }

    public int landingZ() {
        return landingZ;
    }

    /** Колонка зарезервирована под постройки или дороги (там не растёт случайная растительность). */
    public boolean isReserved(int x, int z) {
        double dx = x - landingX();
        double dz = z - landingZ();
        if (dx * dx + dz * dz < 14.0 * 14.0) return true;
        if (basePlan != null) {
            for (WarzoneBasePlan.Site s : basePlan.sites()) {
                if (s.padDistance(x, z) <= s.flatRadius + 4.0) return true;
            }
            if (basePlan.roadFactor(x, z) > 0.05) return true;
            if (basePlan.nearPath(x, z, 3.0)) return true;
        }
        if (hideoutPlan != null && hideoutPlan.reserves(x, z)) return true;
        if (poiPlan != null && poiPlan.reserves(x, z)) return true;
        return false;
    }

    /** Густота леса в точке, 0..1 (рощи и поляны — крупными пятнами). */
    public double forest(int x, int z) {
        return smoothstep(-0.05, 0.35, forestN.fbm(x / 70.0 + 11.0, z / 70.0 - 3.0, 2));
    }

    // ------------------------------------------------------------------ выборка

    public Sample sample(int x, int z) {
        Sample s = new Sample();
        sample(x, z, s);
        return s;
    }

    public int height(int x, int z) {
        return sample(x, z).height;
    }

    /** Естественный рельеф без учёта выравнивания под базы (используется при выборе мест под базы). */
    void naturalSample(int x, int z, Sample out) {
        computeTerrain(x, z, out, false);
    }

    public void sample(int x, int z, Sample out) {
        computeTerrain(x, z, out, true);
    }

    private void computeTerrain(int x, int z, Sample out, boolean applyBases) {
        out.edge = edgeDistance(x, z);
        out.road = 0.0;
        out.basePad = 0.0;
        out.roadBroken = 0.0;
        out.path = 0.0;
        out.trail = false;
        out.desert = 0.0;
        out.dune = 0.0;
        out.pillar = 0.0;
        out.oasis = 0.0;
        out.bog = 0.0;
        out.pool = 0.0;

        // Искажение координат — убирает «сеточность» шума
        double wx = x + warpX.fbm(x / 190.0, z / 190.0, 2) * 40.0;
        double wz = z + warpZ.fbm(x / 190.0 + 57.1, z / 190.0 - 23.9, 2) * 40.0;

        // Влияние посадочной площадки (рядом с ней всегда спокойные равнины)
        double ldx = x - landingX();
        double ldz = z - landingZ;
        double ldist = Math.sqrt(ldx * ldx + ldz * ldz);
        double landing = 1.0 - smoothstep(LANDING_FLAT, LANDING_BLEND, ldist);

        // Влияние ближайшей вражеской базы/аванпоста (плавное выравнивание фундамента)
        double siteFlatten = 0.0;
        boolean siteMountain = false;
        int siteTargetY = LANDING_Y;
        if (applyBases && basePlan != null) {
            for (WarzoneBasePlan.Site site : basePlan.sites()) {
                if (Math.abs(x - site.cx) > site.blendRadius + 8 || Math.abs(z - site.cz) > site.blendRadius + 8) continue;
                double sdist = site.padDistance(x, z);
                if (sdist >= site.blendRadius) continue;
                double w = 1.0 - smoothstep(site.flatRadius, site.blendRadius, sdist);
                if (w > siteFlatten) {
                    siteFlatten = w;
                    siteTargetY = site.baseY;
                    siteMountain = site.type == WarzoneBasePlan.SiteType.MOUNTAIN_FORT;
                }
            }
            out.basePad = siteFlatten;
            out.road = basePlan.roadFactor(x, z);
        }

        double padSuppress = Math.max(landing, siteFlatten);

        // ---- маски областей
        // Площадка горной крепости не «срезает» гору вокруг себя — иначе крепость торчала бы столбом над равниной
        double mtSuppress = siteMountain ? landing : padSuppress;
        double mt = smoothstep(MT_LO, MT_HI, mount.fbm(wx / 300.0, wz / 300.0, 3)) * (1.0 - mtSuppress);
        // Вдоль дорог горы расступаются в перевал
        if (applyBases && basePlan != null && mt > 0.03) {
            mt *= 1.0 - 0.9 * basePlan.roadPass(x, z);
        }
        double mesa = smoothstep(0.05, 0.26, mesaN.fbm(wx / 340.0 + 91.0, wz / 340.0 - 40.0, 2)) * (1.0 - mt) * (1.0 - landing);
        double marsh = smoothstep(0.05, 0.26, wetN.fbm(wx / 320.0 - 12.0, wz / 320.0 + 77.0, 2)) * (1.0 - mt) * (1.0 - mesa) * (1.0 - landing);
        out.mountain = mt;

        // Мелкое искажение для деталей рельефа (маски областей считаются по крупному искажению wx/wz — регионы не меняются)
        double fx = wx + detW1.noise(x / 37.0, z / 37.0) * 6.0;
        double fz = wz + detW2.noise(x / 37.0 + 19.0, z / 37.0 - 5.0) * 6.0;

        // бесплодные земли делятся на пустыню (дюны, столбы, оазисы) и ступенчатую мезу
        double desertMask = mesa > 0.0 ? smoothstep(-0.05, 0.12, desertN.fbm(wx / 260.0 + 7.0, wz / 260.0 - 31.0, 2)) : 0.0;
        double desW = mesa * desertMask;
        double mesaTerr = mesa * (1.0 - desertMask);
        out.desert = desW;
        double pass = (applyBases && basePlan != null && mt > 0.03) ? basePlan.roadPass(x, z) : 0.0;

        // ---- базовая высота: степь с мягкими холмами, пологими увалами и редкими балками (эрозионные промоины)
        double base = 70.0 + broad.fbm(wx / 450.0, wz / 450.0, 2) * 6.0;
        double hillAmp = 5.0 + 7.0 * smoothstep(-0.25, 0.45, broad.fbm(wx / 260.0 + 9.1, wz / 260.0 - 4.4, 2));
        double rolling = hills.fbm(fx / 100.0, fz / 100.0, 4);
        double softRidge = ridge.ridged(fx / 85.0 + 3.0, fz / 85.0 - 7.0, 3);
        double h = base + rolling * hillAmp * (1.0 - 0.6 * mt) + (softRidge - 0.4) * 6.0 * (1.0 - mt);
        double gully = 1.0 - smoothstep(0.0, 0.06, Math.abs(ravN.fbm(fx / 95.0 - 3.0, fz / 95.0 + 8.0, 2)));
        h -= gully * 3.5 * (1.0 - mt) * (1.0 - marsh) * (1.0 - mesa) * (1.0 - padSuppress);

        // ---- горы: мультифрактальные хребты (острые гребни, гладкие долины), плато-уступы, глубокие ущелья
        if (mt > 0.0) {
            double rm = ridge.ridgedMulti(fx / 200.0, fz / 200.0, 5);
            double crag = hills.fbm(fx / 48.0, fz / 48.0, 3);
            h += mt * (24.0 + rm * 98.0);
            h += mt * mt * crag * (4.0 + 10.0 * rm);
            double pl = smoothstep(0.05, 0.35, plateauN.fbm(fx / 230.0 + 4.0, fz / 230.0 - 9.0, 2)) * smoothstep(0.12, 0.5, mt);
            if (pl > 0.0) {
                double step = 16.0 + 6.0 * plateauN.noise(fx / 90.0, fz / 90.0);
                h = lerp(h, WarzoneNoise.terrace(h, step, 0.62) + step * 0.35, pl * 0.85);
            }
            double rv = Math.abs(ravN.fbm(fx / 170.0 + 21.0, fz / 170.0 - 13.0, 3));
            double ravine = (1.0 - smoothstep(0.012, 0.085, rv)) * smoothstep(0.15, 0.6, mt) * (1.0 - siteFlatten) * (1.0 - 0.9 * pass);
            h -= ravine * (9.0 + 20.0 * rm);
        }

        // ---- болото: затопленная низина, мшистые кочки, глубокие заводи
        if (marsh > 0.0) {
            double sw = swampHeight(fx, fz, out);
            h = lerp(h, sw, marsh * 0.95);
            out.bog *= marsh;
            out.pool *= marsh;
        }

        // ---- меза: ступенчатые плато
        if (mesaTerr > 0.0) {
            double step = 6.0;
            double t = (h - 60.0) / step;
            double ft = Math.floor(t);
            double terraced = 60.0 + step * (ft + smoothstep(0.62, 1.0, t - ft)) + 7.0;
            h = lerp(h, terraced, mesaTerr);
        }

        // ---- пустыня: дюны, барханы, оазисы, останцы
        if (desW > 0.0) {
            double dh = desertHeight(x, z, fx, fz, out);
            h = lerp(h, dh, desW);
            out.dune *= desW;
            if (desW > 0.3 && padSuppress < 0.15) {
                double sup = (1.0 - smoothstep(0.0, 0.15, padSuppress)) * (1.0 - Math.min(1.0, out.road * 3.0));
                h = pillarLift(x, z, h, sup, out);
            }
        }

        // ---- реки: глубокое русло с крутыми берегами, пойма и долина
        double rn = Math.abs(riverN.fbm(wx / 240.0, wz / 240.0, 3));
        double rw = 0.030 + 0.018 * (riverW.fbm(wx / 300.0, wz / 300.0, 1) * 0.5 + 0.5);
        double riverMask = (1.0 - mt * 0.85) * (1.0 - padSuppress) * (1.0 - 0.5 * marsh);
        double q = rn / rw;
        double channel = (1.0 - smoothstep(0.45, 1.0, q)) * riverMask;
        double valley = (1.0 - smoothstep(1.0, 3.4, q)) * riverMask;
        if (riverMask > 0.001 && q < 3.4) {
            double depthMax = 7.5 + 5.5 * (riverW.noise(wx / 170.0 + 3.0, wz / 170.0 - 7.0) * 0.5 + 0.5);
            double vh = Math.min(h, SEA_LEVEL + 5.0);
            double target;
            if (q <= 1.0) {
                // дно: парабола, глубже всего по оси русла, у берега круто вверх
                target = SEA_LEVEL - 1.5 - depthMax * Math.pow(1.0 - q * q, 0.8);
            } else if (q < 1.9) {
                // крутой берег: быстро поднимается из воды на уровень поймы
                target = lerp(SEA_LEVEL - 1.5, vh, Math.pow(smoothstep(1.0, 1.9, q), 0.65));
            } else {
                target = lerp(vh, h, smoothstep(1.9, 3.4, q));
            }
            h = lerp(h, Math.min(h, target), riverMask);
        }
        out.channel = channel;
        out.valley = valley;

        // ---- озёра и заливы: крутые берега и глубокая чаша (до ~16 блоков)
        double lkn = lakeN.fbm(wx / 150.0 + 5.0, wz / 150.0 - 8.0, 2);
        double lake = smoothstep(0.30, 0.44, lkn) * (1.0 - mt) * (1.0 - padSuppress);
        if (lake > 0.001) {
            double lakeDepth = 7.0 + 9.0 * smoothstep(0.42, 0.62, lkn);
            double bed = SEA_LEVEL - 0.5 - lakeDepth * Math.pow(lake, 0.7);
            h = lerp(h, Math.min(h, bed), smoothstep(0.0, 0.35, lake));
        }

        // ---- рябь на дне водоёмов (не на мелководье у берега)
        if (h < SEA_LEVEL - 0.5) {
            h += hills.noise(fx / 9.0, fz / 9.0) * 0.9 * smoothstep(SEA_LEVEL - 0.5, SEA_LEVEL - 3.0, h);
        }

        // ---- воронки (не бьют по посадочной площадке, базам и дорогам)
        out.scar = 0.0;
        // Воронка, задевающая дорогу, либо разбивает её (30%, дорога «перетекает» в воронку), либо не появляется вовсе
        double roadShield = padSuppress;
        if (h > SEA_LEVEL + 2.0 && roadShield < 0.95) {
            h += craterOffset(x, z, out, applyBases) * (1.0 - roadShield) * (1.0 - mt);
            out.scar *= (1.0 - roadShield) * (1.0 - mt);
            out.roadBroken *= (1.0 - roadShield) * (1.0 - mt);
        }

        // ---- посадочная площадка и террасы вражеских баз
        h = lerp(h, LANDING_Y, landing);
        if (siteFlatten > 0.0) {
            h = lerp(h, siteTargetY, siteFlatten);
        }

        // ---- горные дороги и тропы (после выравнивания площадок: дорога врезается в склон): профиль с постоянным уклоном
        if (applyBases && basePlan != null && !basePlan.paths().isEmpty()) {
            double[] pq = null;
            for (WarzoneBasePlan.TrailPath tp : basePlan.paths()) {
                if (tp.tunnel) continue;   // ледяной тоннель прорезается внутри горы (IceTunnel), рельеф не трогаем
                if (!tp.mayContain(x, z)) continue;
                if (pq == null) pq = new double[2];
                if (!tp.query(x, z, pq)) continue;
                double dist = pq[0];
                double core = 1.0 - smoothstep(tp.halfWidth - 0.4, tp.halfWidth + 1.2, dist);
                if (core > out.path) {
                    out.path = core;
                    out.trail = tp.trail;
                }
                if (!Double.isNaN(pq[1])) {
                    double wgt = 1.0 - smoothstep(tp.halfWidth + 0.5, tp.halfWidth + 5.0, dist);
                    if (wgt > 0.0) h = lerp(h, Math.floor(pq[1]), wgt);
                }
            }
        }


        // ---- скальное кольцо по краю арены
        if (out.edge < RIM_WIDTH) {
            double t = 1.0 - Math.max(0, out.edge) / (double) RIM_WIDTH;     // 0 → внутри, 1 → на самом краю
            double w = smoothstep(0.0, 1.0, Math.pow(t, 1.15));
            double rimH = RIM_TOP + rimN.fbm(x / 36.0, z / 36.0, 3) * 16.0 + rimN.ridged(x / 14.0, z / 14.0, 2) * 8.0;
            h = lerp(h, rimH, w);
            mt = Math.max(mt, w);
            out.mountain = mt;
        }

        int hi = (int) Math.floor(h);
        out.height = Math.max(4, Math.min(HEIGHT - 6, hi));

        // ---- тип местности (для поверхности и биома)
        if (mt > 0.45) out.region = Region.HIGHLANDS;
        else if (mesa > 0.5) out.region = Region.BADLANDS;
        else if (marsh > 0.45) out.region = Region.MARSH;
        else out.region = Region.PLAINS;
    }

    /** Лёгкая версия только для определения типа местности (биомы). */
    public Region region(int x, int z) {
        double wx = x + warpX.fbm(x / 190.0, z / 190.0, 2) * 40.0;
        double wz = z + warpZ.fbm(x / 190.0 + 57.1, z / 190.0 - 23.9, 2) * 40.0;
        double ldx = x - landingX();
        double ldz = z - landingZ;
        double landing = 1.0 - smoothstep(LANDING_FLAT, LANDING_BLEND, Math.sqrt(ldx * ldx + ldz * ldz));
        double mt = smoothstep(MT_LO, MT_HI, mount.fbm(wx / 300.0, wz / 300.0, 3)) * (1.0 - landing);
        if (edgeDistance(x, z) < RIM_WIDTH / 2 || mt > 0.45) return Region.HIGHLANDS;
        double mesa = smoothstep(0.05, 0.26, mesaN.fbm(wx / 340.0 + 91.0, wz / 340.0 - 40.0, 2)) * (1.0 - mt) * (1.0 - landing);
        if (mesa > 0.5) return Region.BADLANDS;
        double marsh = smoothstep(0.05, 0.26, wetN.fbm(wx / 320.0 - 12.0, wz / 320.0 + 77.0, 2)) * (1.0 - mt) * (1.0 - mesa) * (1.0 - landing);
        if (marsh > 0.45) return Region.MARSH;
        return Region.PLAINS;
    }

    // ------------------------------------------------------------------ болото

    /** Абсолютная высота болота: мелководная низина, мшистые кочки, глубокие заводи. Пишет out.bog и out.pool. */
    private double swampHeight(double fx, double fz, Sample out) {
        double level = SEA_LEVEL - 0.35 + 1.2 * hills.fbm(fx / 60.0, fz / 60.0, 2);
        double hum = smoothstep(0.12, 0.42, bogN.fbm(fx / 16.0 + 4.0, fz / 16.0 - 6.0, 2));
        double pool = smoothstep(0.30, 0.52, poolN.fbm(fx / 48.0 + 7.0, fz / 48.0 - 2.0, 2));
        double mounds = hum * (2.4 + 2.0 * smoothstep(0.0, 0.5, bogN.fbm(fx / 52.0, fz / 52.0, 2))
                + bogN.noise(fx / 6.0, fz / 6.0) * 0.6) * (1.0 - pool);
        double poolDepth = 8.5 * smoothstep(0.0, 0.7, pool);
        out.bog = hum * (1.0 - pool);
        out.pool = pool;
        return level + mounds - poolDepth;
    }

    // ------------------------------------------------------------------ пустыня

    private static final int OASIS_CELL = 160;
    private static final int BARCHAN_CELL = 52;
    private static final int PILLAR_CELL = 46;

    /** Вес пустыни в произвольной точке (без гор/площадок) — для решения «ставить ли оазис/столб». */
    private double desertWeightAt(int x, int z) {
        double wx = x + warpX.fbm(x / 190.0, z / 190.0, 2) * 40.0;
        double wz = z + warpZ.fbm(x / 190.0 + 57.1, z / 190.0 - 23.9, 2) * 40.0;
        double mesa = smoothstep(0.05, 0.26, mesaN.fbm(wx / 340.0 + 91.0, wz / 340.0 - 40.0, 2));
        return mesa * smoothstep(-0.05, 0.12, desertN.fbm(wx / 260.0 + 7.0, wz / 260.0 - 31.0, 2));
    }

    /** Абсолютная высота пустыни: поперечные дюны с пологой наветренной и крутой подветренной стороной, барханы, оазисы. */
    private double desertHeight(int x, int z, double fx, double fz, Sample out) {
        double level = 69.0 + broad.fbm(x / 380.0 + 2.0, z / 380.0, 2) * 3.0 + hills.fbm(fx / 140.0, fz / 140.0, 2) * 2.5;

        double u = x * windCos + z * windSin;
        double warpU = duneN.fbm(fx / 60.0, fz / 60.0, 2) * 26.0 + duneN.noise(x / 23.0 + 5.0, z / 23.0) * 4.0;
        double phase = (u + warpU) / 36.0;
        double sPh = phase - Math.floor(phase);
        double prof = sPh < 0.72 ? Math.pow(sPh / 0.72, 1.3) : 1.0 - smoothstep(0.0, 1.0, (sPh - 0.72) / 0.28);
        double amp = 1.5 + 4.5 * smoothstep(-0.3, 0.5, duneN.fbm(fx / 130.0 + 8.0, fz / 130.0 - 2.0, 2));
        double erg = smoothstep(-0.1, 0.25, duneN.fbm(fx / 200.0 - 5.0, fz / 200.0 + 3.0, 2));
        double dune = erg * amp * prof + (1.0 - erg) * barchans(x, z);

        // оазис: ячейка 160×160, центр с запасом от границ (радиус влияния ≤ 30)
        int ocx = Math.floorDiv(x, OASIS_CELL);
        int ocz = Math.floorDiv(z, OASIS_CELL);
        long oh = WarzoneNoise.hash(oasisSalt, ocx, ocz);
        double oasisT = 9.0;
        if (WarzoneNoise.unit(oh) < 0.5) {
            double r = 9.0 + WarzoneNoise.unit(WarzoneNoise.mix(oh + 1)) * 6.0;
            double ox = ocx * OASIS_CELL + 34 + WarzoneNoise.unit(WarzoneNoise.mix(oh + 2)) * (OASIS_CELL - 68);
            double oz = ocz * OASIS_CELL + 34 + WarzoneNoise.unit(WarzoneNoise.mix(oh + 3)) * (OASIS_CELL - 68);
            double d = Math.hypot(x - ox, z - oz) * (1.0 + 0.18 * duneN.noise(x / 11.0, z / 11.0));
            double t = d / r;
            if (t < 2.0 && desertWeightAt((int) ox, (int) oz) > 0.7) {
                oasisT = t;
            }
        }
        if (oasisT >= 2.0) {
            out.dune = dune;
            return level + dune;
        }
        out.oasis = 1.0 - smoothstep(1.2, 2.0, oasisT);
        double hDesert = level + dune * (1.0 - out.oasis);
        out.dune = dune * (1.0 - out.oasis);
        if (oasisT <= 1.0) {
            return SEA_LEVEL - 1.0 - 4.0 * (1.0 - oasisT * oasisT);
        }
        double ring = SEA_LEVEL - 1.0 + 3.0 * smoothstep(1.0, 1.35, oasisT);
        return lerp(ring, hDesert, smoothstep(1.25, 2.0, oasisT));
    }

    /** Барханы: серповидные дюны, рожки смотрят по ветру; пологий наветренный склон и крутой лавинный откос. */
    private double barchans(int x, int z) {
        int cx = Math.floorDiv(x, BARCHAN_CELL);
        int cz = Math.floorDiv(z, BARCHAN_CELL);
        double best = 0.0;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                long hh = WarzoneNoise.hash(barchanSalt, cx + i, cz + j);
                if (WarzoneNoise.unit(hh) > 0.55) continue;
                double wd = 7.0 + WarzoneNoise.unit(WarzoneNoise.mix(hh + 1)) * 6.0;
                double len = 15.0 + WarzoneNoise.unit(WarzoneNoise.mix(hh + 2)) * 11.0;
                double ox = (cx + i) * BARCHAN_CELL + 8 + WarzoneNoise.unit(WarzoneNoise.mix(hh + 3)) * (BARCHAN_CELL - 16);
                double oz = (cz + j) * BARCHAN_CELL + 8 + WarzoneNoise.unit(WarzoneNoise.mix(hh + 4)) * (BARCHAN_CELL - 16);
                double dx = x - ox;
                double dz = z - oz;
                double a = dx * windCos + dz * windSin;
                double b = -dx * windSin + dz * windCos;
                double v = b / wd;
                if (Math.abs(v) >= 1.0) continue;
                double w = (a - 0.55 * len * v * v) / len;
                if (w < -1.0 || w > 0.2) continue;
                double p = w < 0.0 ? Math.pow(1.0 + w, 1.25) : Math.pow(1.0 - w / 0.2, 1.4);
                double hgt = (2.6 + 0.3 * wd) * Math.pow(1.0 - v * v, 0.7) * p;
                if (hgt > best) best = hgt;
            }
        }
        return best;
    }

    /** Выветренные скальные столбы (останцы): плоская макушка, неровные крутые бока, осыпной шлейф. */
    private double pillarLift(int x, int z, double h, double sup, Sample out) {
        if (sup <= 0.0) return h;
        int cx = Math.floorDiv(x, PILLAR_CELL);
        int cz = Math.floorDiv(z, PILLAR_CELL);
        long hh = WarzoneNoise.hash(pillarSalt, cx, cz);
        if (WarzoneNoise.unit(hh) > 0.34) return h;
        double r0 = 3.0 + WarzoneNoise.unit(WarzoneNoise.mix(hh + 1)) * 3.0;
        double hgt = 12.0 + WarzoneNoise.unit(WarzoneNoise.mix(hh + 2)) * 20.0;
        double ox = cx * PILLAR_CELL + 12 + WarzoneNoise.unit(WarzoneNoise.mix(hh + 3)) * (PILLAR_CELL - 24);
        double oz = cz * PILLAR_CELL + 12 + WarzoneNoise.unit(WarzoneNoise.mix(hh + 4)) * (PILLAR_CELL - 24);
        double reff = r0 * (1.0 + 0.30 * duneN.noise(x / 9.0 + ox, z / 9.0 - oz));
        double w = 2.5 + 0.12 * hgt;
        double d = Math.hypot(x - ox, z - oz) - reff;
        if (d > w) return h;
        if (desertWeightAt((int) ox, (int) oz) < 0.7) return h;
        double top = 70.0 + hgt + duneN.noise(x / 5.0, z / 5.0) * 1.2;
        double prof = d <= 0.0 ? 1.0 : Math.pow(1.0 - d / w, 1.6);
        double ph = 70.0 + (top - 70.0) * prof;
        if (ph <= h + 0.5) return h;
        out.pillar = Math.min(1.0, (ph - h) / 3.0) * sup;
        return lerp(h, ph, sup);
    }

    // ------------------------------------------------------------------ воронки

    /** Смещение высоты от воронки в точке (чаша + вал по краю) и отметка «шрама» в out.scar. */
    private double craterOffset(int x, int z, Sample out, boolean applyBases) {
        int cx = Math.floorDiv(x, CRATER_CELL);
        int cz = Math.floorDiv(z, CRATER_CELL);
        long hh = WarzoneNoise.hash(craterSalt, cx, cz);
        if (WarzoneNoise.unit(hh) > 0.30) return 0.0;
        double radius = 5.0 + WarzoneNoise.unit(WarzoneNoise.mix(hh + 1)) * 8.0;
        double ox = cx * CRATER_CELL + 18 + WarzoneNoise.unit(WarzoneNoise.mix(hh + 2)) * (CRATER_CELL - 36);
        double oz = cz * CRATER_CELL + 18 + WarzoneNoise.unit(WarzoneNoise.mix(hh + 3)) * (CRATER_CELL - 36);
        double dist = Math.hypot(x - ox, z - oz);
        if (dist >= radius * 1.45) return 0.0;

        boolean broken = false;
        if (applyBases && basePlan != null) {
            int mode = craterMode(cx, cz, hh, ox, oz, radius);
            if (mode == 1) return 0.0;        // воронка подавлена: дорога остаётся целой
            broken = mode == 2;
        }

        double depth = radius * 0.42;
        double r = dist / radius;
        double bowl = r < 1.0 ? (1.0 - r * r) : 0.0;
        double rimBump = Math.exp(-Math.pow((dist - radius) / (radius * 0.22), 2.0)) * depth * 0.30;
        out.scar = 1.0 - smoothstep(radius * 0.85, radius * 1.45, dist);
        if (broken) {
            out.roadBroken = 1.0 - smoothstep(radius * 0.55, radius * 1.45, dist);
        }
        return -depth * bowl + rimBump;
    }

    /** Решение для воронки ячейки (cx, cz): 0 — дороги рядом нет, 1 — воронку подавить, 2 — воронка разбивает дорогу. */
    private int craterMode(int cx, int cz, long hh, double ox, double oz, double radius) {
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        Integer cached = craterModes.get(key);
        if (cached != null) return cached;
        int mode = 0;
        double roadDist = basePlan.roadDistance(ox, oz);
        // дорога касается воронки, если полотно (полуширина + обочина) заходит в радиус воронки
        if (roadDist < radius * 1.45 + 4.0) {
            mode = WarzoneNoise.unit(WarzoneNoise.mix(hh + 7)) < CRATER_BREAKS_ROAD ? 2 : 1;
        }
        craterModes.put(key, mode);
        return mode;
    }
}
