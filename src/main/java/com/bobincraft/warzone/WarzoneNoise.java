package com.bobincraft.warzone;

/**
 * Небольшой детерминированный 2D-шум (градиентный, в духе Перлина) без зависимостей от Minecraft.
 * Нужен, чтобы форма рельефа Зоны Боевых Действий считалась одной чистой функцией от (seed, x, z):
 * её можно вызывать откуда угодно (генератор чанков, источник биомов, расстановка баз, ИИ) без загруженных чанков.
 */
final class WarzoneNoise {
    private final long seed;

    WarzoneNoise(long seed) {
        this.seed = seed;
    }

    // ------------------------------------------------------------------ хэши

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Детерминированный хэш двух целых + соли. */
    static long hash(long salt, int x, int z) {
        return mix(salt * 0x9E3779B97F4A7C15L + x * 0x632BE59BD9B4E019L + z * 0x85157AF5L + 0x165667B19E3779F9L);
    }

    /** Равномерное [0,1) из хэша. */
    static double unit(long h) {
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    // ------------------------------------------------------------------ шум

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static final double[] GX = new double[16];
    private static final double[] GZ = new double[16];

    static {
        for (int i = 0; i < 16; i++) { // 16 направлений равномерно по кругу
            GX[i] = Math.cos(i * (Math.PI / 8.0));
            GZ[i] = Math.sin(i * (Math.PI / 8.0));
        }
    }

    private double grad(int ix, int iz, double dx, double dz) {
        int a = (int) (hash(seed, ix, iz) & 15);
        return GX[a] * dx + GZ[a] * dz;
    }

    /** Градиентный шум, значения примерно в [-1; 1]. */
    double noise(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double u = fade(fx);
        double v = fade(fz);
        double n00 = grad(x0, z0, fx, fz);
        double n10 = grad(x0 + 1, z0, fx - 1, fz);
        double n01 = grad(x0, z0 + 1, fx, fz - 1);
        double n11 = grad(x0 + 1, z0 + 1, fx - 1, fz - 1);
        double nx0 = n00 + (n10 - n00) * u;
        double nx1 = n01 + (n11 - n01) * u;
        return (nx0 + (nx1 - nx0) * v) * 1.41421356; // нормировка к ~[-1;1]
    }

    /** Фрактальная сумма октав, нормированная в [-1; 1]. */
    double fbm(double x, double z, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            sum += noise(x * f + i * 17.3, z * f - i * 9.1) * amp;
            norm += amp;
            amp *= 0.5;
            f *= 2.03;
        }
        return sum / norm;
    }

    /** «Хребтовый» шум: 0..1, острые гребни там, где обычный шум пересекает ноль. */
    double ridged(double x, double z, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            double n = 1.0 - Math.abs(noise(x * f + i * 31.7, z * f + i * 13.3));
            sum += n * n * amp;
            norm += amp;
            amp *= 0.5;
            f *= 2.1;
        }
        return sum / norm;
    }

    /**
     * Мультифрактальный хребтовый шум (Мусгрейв): мелкие детали рождаются только на гребнях, долины остаются гладкими —
     * похоже на размытую эрозией горную систему. Значения 0..1.
     */
    double ridgedMulti(double x, double z, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1, weight = 1;
        for (int i = 0; i < octaves; i++) {
            double n = 1.0 - Math.abs(noise(x * f + i * 31.7, z * f + i * 13.3));
            n *= n;
            n *= weight;
            weight = Math.max(0.0, Math.min(1.0, n * 2.0));
            sum += n * amp;
            norm += amp;
            amp *= 0.5;
            f *= 2.1;
        }
        return Math.min(1.0, sum / norm * 1.35);
    }

    /** Ступенчатая «полка»: плоские площадки шириной flat (доля шага) и крутые уступы между ними. */
    static double terrace(double h, double step, double flat) {
        double t = h / step;
        double ft = Math.floor(t);
        return step * (ft + smoothstep(flat, 1.0, t - ft));
    }

    // ------------------------------------------------------------------ утилиты

    static double smoothstep(double a, double b, double x) {
        if (a == b) return x < a ? 0 : 1;
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
