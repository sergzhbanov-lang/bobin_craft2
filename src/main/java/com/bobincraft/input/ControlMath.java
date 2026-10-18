package com.bobincraft.input;

/** Pure input math shared by camera scrolling and player movement. */
public final class ControlMath {
    private ControlMath() {}

    public record Axes(double sideways, double forward) {}

    public static Axes normalize(double sideways, double forward) {
        double length = Math.max(1.0, Math.hypot(sideways, forward));
        return new Axes(sideways / length, forward / length);
    }

    /** Minecraft input uses positive sideways for left, positive forward for ahead. */
    public static Axes playerAxes(double left, double forward, double cameraYaw, double playerYaw) {
        Axes axes = normalize(left, forward);
        double angle = Math.toRadians(cameraYaw - playerYaw);
        double sin = Math.sin(angle), cos = Math.cos(angle);
        return new Axes(axes.sideways * cos - axes.forward * sin,
                axes.forward * cos + axes.sideways * sin);
    }

    public static double edge(double cursor, double size, double band) {
        if (size <= 0 || band <= 0 || cursor < 0 || cursor > size) return 0;
        band = Math.min(band, size / 2);
        if (cursor < band) return -(1.0 - cursor / band);
        if (cursor > size - band) return 1.0 - (size - cursor) / band;
        return 0;
    }

    public static double smoothing(double rate, double seconds) {
        return -Math.expm1(-rate * Math.max(0, seconds));
    }

    /** Exact exponential follow for a target moving at constant speed during this frame. */
    public static double follow(double current, double previousTarget, double target, double rate, double seconds) {
        if (seconds <= 0 || rate <= 0) return current;
        double blend = smoothing(rate, seconds);
        return current + (previousTarget - current) * blend
                + (target - previousTarget) * (1.0 - blend / (rate * seconds));
    }
}
