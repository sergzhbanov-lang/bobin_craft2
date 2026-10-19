package com.bobincraft.warzone;

import static com.bobincraft.warzone.WarzoneNoise.lerp;
import static com.bobincraft.warzone.WarzoneNoise.smoothstep;

/** Seeded drainage network. All channels meet their parent at a shared, exact confluence. */
final class NaturalWaterways {
    private final double cos, sin, phase, offset;
    private final int size;
    private final WarzoneNoise banks;
    private final WarzoneNoise meander;
    private final double[] joins, sides, bends, widths;
    private final NaturalDrainage drainage;

    NaturalWaterways(long seed, int size, double cos, double sin, double phase, double offset) {
        this(seed, size, cos, sin, phase, offset, false);
    }

    /** Version four uses a finite drainage network; the earlier constructor preserves existing saves. */
    NaturalWaterways(long seed, int size, double cos, double sin, double phase, double offset,
                     boolean landscapeRivers) {
        this.size = size;
        this.cos = cos;
        this.sin = sin;
        this.phase = phase;
        this.offset = offset;
        drainage = landscapeRivers ? new NaturalDrainage(seed, size, cos, sin, phase, offset) : null;
        banks = new WarzoneNoise(WarzoneNoise.mix(seed + 151));
        meander = new WarzoneNoise(WarzoneNoise.mix(seed + 153));
        int count = 4 + (int) (WarzoneNoise.mix(seed + 152) & 1);
        joins = new double[count];
        sides = new double[count];
        bends = new double[count];
        widths = new double[count];
        for (int i = 0; i < count; i++) {
            long hash = WarzoneNoise.mix(seed + 160 + i);
            joins[i] = size * (-0.34 + i * 0.68 / (count - 1)
                    + (WarzoneNoise.unit(hash) - 0.5) * 0.055);
            sides[i] = i % 2 == 0 ? 1 : -1;
            bends[i] = (WarzoneNoise.unit(WarzoneNoise.mix(hash + 1)) - 0.5) * 0.65;
            // Wider primary tributaries so forest streams never look like 2-block ditches.
            widths[i] = 3.8 + WarzoneNoise.unit(WarzoneNoise.mix(hash + 2)) * 2.5;
        }
    }

    int tributaryCount() { return drainage == null ? joins.length : drainage.tributaryCount(); }

    /** Source terrain is raised before carving, so headwaters emerge from hills rather than floating. */
    double mountainLift(int x, int z) { return drainage == null ? 0 : drainage.mountainLift(x, z); }

    double mountainMask(int x, int z) { return drainage == null ? 0 : drainage.mountainMask(x, z); }

    private double center(double u) {
        return offset + Math.sin(u / 210.0 + phase) * 68
                + Math.sin(u / 82.0 - phase * 0.7) * 24
                + Math.sin(u / 37.0 + phase * 1.3) * 7;
    }

    private double derivative(double u) {
        return Math.cos(u / 210.0 + phase) * 68 / 210
                + Math.cos(u / 82.0 - phase * 0.7) * 24 / 82
                + Math.cos(u / 37.0 + phase * 1.3) * 7 / 37;
    }

    /** Writes the dominant reach's geometry and water condition without allocating per column. */
    double carve(int x, int z, double small, double height, WarzoneTerrain.Sample out) {
        if (drainage != null) return drainage.carve(x, z, small, height, out);
        double warpX = meander.fbm(x / 64.0 + 5.1, z / 64.0 - 3.7, 2) * 11.0;
        double warpZ = meander.fbm(x / 64.0 - 9.4, z / 64.0 + 8.2, 2) * 11.0;
        double wx = x + warpX, wz = z + warpZ;

        double u = wx * cos + wz * sin, v = -wx * sin + wz * cos;
        double across = v - center(u), slope = derivative(u);
        double distance = Math.abs(across) / Math.sqrt(1 + slope * slope);
        double reach = 0.5 + 0.5 * Math.sin(u / 155.0 + phase);
        double pulse = banks.fbm(u / 48.0, v / 48.0, 2);
        // Broad, believable main river channel with natural pools and narrows.
        double width = 6.8 + reach * 5.8 + pulse * 1.9;
        double depth = 5.6 + reach * 2.8 + Math.max(0, pulse) * 1.2;
        double ford = ford(u, size * 0.19, 340);
        double flowU = 1, flowV = slope;
        double signedAcross = across;
        WarzoneTerrain.WaterState state = reach > .72 ? WarzoneTerrain.WaterState.SLOW
                : reach < .20 ? WarzoneTerrain.WaterState.RAPIDS : WarzoneTerrain.WaterState.FLOWING;

        for (int i = 0; i < joins.length; i++) {
            // t=0 is the parent's centerline, so every tributary ends inside the main river.
            double t = across * sides[i];
            if (t < -width || t > size * .76) continue;
            double bend = bends[i];
            double branchU = joins[i] + t * bend
                    + Math.sin(t / 78.0 + i * 1.1) * 31
                    + Math.sin(t / 31.0 - i * 0.7) * 9;
            double branchSlope = bend
                    + Math.cos(t / 78.0 + i * 1.1) * 31 / 78
                    + Math.cos(t / 31.0 - i * 0.7) * 9 / 31;
            double d = Math.abs(u - branchU) / Math.sqrt(1 + branchSlope * branchSlope);
            double taper = 1 - .32 * smoothstep(size * .10, size * .66, t);
            double w = widths[i] * taper * (1 + .28 * Math.sin(t / 61.0 + i) + .14 * pulse);
            double f = ford(t, 95 + i * 29, 255);
            if (d / w < distance / width) {
                distance = d;
                width = w;
                depth = 4.4 + .95 * Math.sin(t / 73.0 + i);
                ford = f;
                flowU = -branchSlope;
                flowV = -slope * branchSlope - sides[i];
                signedAcross = u - branchU;
                state = w < 3.3 ? WarzoneTerrain.WaterState.RAPIDS : WarzoneTerrain.WaterState.FLOWING;
            }
            // A smaller feeder forks into every other tributary farther upstream.
            if (i % 2 == 0 && t > size * .15) {
                double feederT = t - size * .22;
                double joinU = joins[i] + size * .22 * bend
                        + Math.sin(size * .22 / 78.0 + i * 1.1) * 31
                        + Math.sin(size * .22 / 31.0 - i * 0.7) * 9;
                double feederU = joinU + feederT * (bend + .58)
                        + Math.sin(feederT / 47.0 + i) * 16
                        + Math.sin(feederT / 21.0) * 5;
                double feederSlope = bend + .58
                        + Math.cos(feederT / 47.0 + i) * 16 / 47
                        + Math.cos(feederT / 21.0) * 5 / 21;
                double fd = Math.abs(u - feederU) / Math.sqrt(1 + feederSlope * feederSlope);
                double fw = 2.6 + .55 * Math.sin(feederT / 39.0 + i);
                if (feederT >= 0 && fd / fw < distance / width) {
                    distance = fd;
                    width = fw;
                    depth = 3.8 + .45 * Math.cos(feederT / 41.0);
                    ford = ford(feederT, 74, 210);
                    flowU = -feederSlope;
                    flowV = -slope * feederSlope - sides[i];
                    signedAcross = u - feederU;
                    state = WarzoneTerrain.WaterState.FLOWING;
                }
            }
        }

        // Asymmetric bank shaping creates gentler point bars on one side and steeper cut banks on the other.
        double bankBias = banks.fbm(x / 37.0 - 11.0, z / 37.0 + 6.0, 2);
        double sideFactor = signedAcross >= 0 ? (1.0 + bankBias * 0.28) : (1.0 - bankBias * 0.28);
        double roughness = banks.fbm(x / 17.0 + 7, z / 17.0 - 4, 2);
        double effectiveDistance = Math.max(0, distance * sideFactor + roughness * Math.min(1.8, width * .22));

        double valleySpan = width + 38 + Math.abs(bankBias) * 14;
        double valley = 1 - smoothstep(width * .75, valleySpan, effectiveDistance);
        double channel = 1 - smoothstep(width * .42, width + 1.8, effectiveDistance);

        // Smoothly grade the riverbank from water level (62.2) up into the surrounding valley floor.
        double bankRise = smoothstep(width * .85, valleySpan, effectiveDistance) * 3.6;
        double bank = WarzoneTerrain.SEA_LEVEL + 0.25 + bankRise + small * .32;
        height = lerp(height, Math.min(height, bank), valley * .96);

        // Thalweg profile: deeper off-center core, sloped underwater shelves, and wet shallow fords.
        double normalized = Math.min(1.0, effectiveDistance / Math.max(1.0, width));
        double bedProfile = Math.pow(1.0 - normalized * normalized, 0.65);
        double localDepth = depth * (0.52 + 0.48 * bedProfile);
        double bed = WarzoneTerrain.SEA_LEVEL - localDepth + ford * (localDepth - .35) + small * .22 * (1 - ford);
        height = lerp(height, Math.min(height, bed), channel);

        out.channel = channel;
        out.valley = Math.max(out.valley, valley);
        out.waterState = state;
        double length = Math.hypot(flowU, flowV);
        double speed = state == WarzoneTerrain.WaterState.RAPIDS ? 1 : state == WarzoneTerrain.WaterState.SLOW ? .18 : .55;
        out.flowX = (flowU * cos - flowV * sin) / length * speed;
        out.flowZ = (flowU * sin + flowV * cos) / length * speed;
        return height;
    }

    private static double ford(double coordinate, double offset, double spacing) {
        double nearest = Math.rint((coordinate - offset) / spacing) * spacing + offset;
        return 1 - smoothstep(8, 21, Math.abs(coordinate - nearest));
    }
}
