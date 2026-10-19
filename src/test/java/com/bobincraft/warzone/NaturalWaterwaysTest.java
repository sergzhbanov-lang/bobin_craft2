package com.bobincraft.warzone;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

final class NaturalWaterwaysTest {
    private static final long[] SEEDS = {0, 1, 42, 123456789, -734842};

    @Test
    void versionThreeRetainsItsCalibratedWaterDepthsAcrossSeeds() {
        for (long seed : SEEDS) {
            NaturalLandscape terrain = new NaturalLandscape(seed, 2048, true);
            WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
            EnumSet<WarzoneTerrain.WaterState> states = EnumSet.noneOf(WarzoneTerrain.WaterState.class);
            boolean[] depths = new boolean[32];
            int wet = 0, shallow = 0;
            // Cover the entire default arena. Count surface area, including tributaries, ponds
            // and wetlands; volume and channel-centerline lengths would give misleading ratios.
            for (int z = -1024; z < 1024; z += 4) {
                for (int x = -1024; x < 1024; x += 4) {
                    terrain.sample(x, z, sample);
                    assertTrue(Double.isFinite(sample.flowX) && Double.isFinite(sample.flowZ));
                    if (sample.edge > 100 && Math.hypot(x, z - 696) > 100 && sample.channel > .99) {
                        assertTrue(sample.height < WarzoneTerrain.SEA_LEVEL,
                                "River cores must remain wet through fords: seed=" + seed + ", x=" + x + ", z=" + z);
                    }
                    int depth = WarzoneTerrain.SEA_LEVEL - sample.height;
                    if (depth <= 0) {
                        assertEquals(WarzoneTerrain.WaterState.NONE, sample.waterState);
                        assertEquals(0, sample.flowX);
                        assertEquals(0, sample.flowZ);
                        continue;
                    }
                    wet++;
                    if (depth <= 2) shallow++;
                    depths[Math.min(depths.length - 1, depth)] = true;
                    states.add(sample.waterState);
                    assertNotEquals(WarzoneTerrain.WaterState.NONE, sample.waterState);
                    assertTrue(Math.hypot(sample.flowX, sample.flowZ) <= 1.000000001);
                    if (sample.waterState == WarzoneTerrain.WaterState.STILL) {
                        assertEquals(0, sample.flowX);
                        assertEquals(0, sample.flowZ);
                    }
                }
            }
            double fraction = shallow / (double) wet;
            assertTrue(wet > 1000, "Seed " + seed + " must contain substantial water");
            assertTrue(fraction >= .17 && fraction <= .23,
                    "Seed " + seed + " shallow surface area: " + fraction);
            assertEquals(EnumSet.of(WarzoneTerrain.WaterState.STILL, WarzoneTerrain.WaterState.SLOW,
                    WarzoneTerrain.WaterState.FLOWING, WarzoneTerrain.WaterState.RAPIDS), states,
                    "Seed " + seed + " needs visibly different water conditions");
            int depthCount = 0;
            for (boolean present : depths) if (present) depthCount++;
            assertTrue(depthCount >= 6, "Waterbeds need shelves and several genuinely deep levels");
            assertTrue(terrain.tributaryCount() >= 4);
        }
    }

    @Test
    void mountainSpringReachesLowlandsContinuouslyWithoutFlowingUphill() {
        for (long seed : SEEDS) {
            NaturalWaterways waterways = new NaturalWaterways(seed, 2048, 1, 0, 0, 0, true);
            WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
            int startZ = 0, deepest = -1;
            // Find the spring within its source hillside without depending on a stored route vertex.
            for (int z = -120; z <= 120; z++) {
                carve(waterways, -728, z, sample);
                int depth = sample.waterLevel - sample.height;
                if (depth > deepest) { deepest = depth; startZ = z; }
            }
            carve(waterways, -728, startZ, sample);
            assertTrue(sample.waterLevel >= WarzoneTerrain.SEA_LEVEL + 20);
            assertTrue(waterways.mountainLift(-728, startZ) > 35);
            assertTrue(waterways.mountainMask(-728, startZ) > .65);
            followToLowlands(waterways, -728, startZ, sample);
        }
    }

    @Test
    void tributariesReachTheirParentWithoutDryGapsOrUpwardWaterSteps() {
        for (long seed : SEEDS) {
            NaturalWaterways waterways = new NaturalWaterways(seed, 2048, 1, 0, 0, 0, true);
            WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
            int starts = 0;
            // Pick independent, actually wet upstream columns on both sides of the main valley.
            for (int z = -650; z <= 650; z += 50) {
                for (int x = -650; x <= 850; x += 50) {
                    if (Math.abs(z) < 130) continue;
                    carve(waterways, x, z, sample);
                    if (sample.channel < .99 || sample.height >= sample.waterLevel) continue;
                    starts++;
                    followToLowlands(waterways, x, z, sample);
                }
            }
            assertTrue(starts > 0, "Every seed needs inland tributaries, seed=" + seed);
            assertTrue(waterways.tributaryCount() >= 4);
        }
    }

    @Test
    void lowerMainRiverIsBroadAndDeepWithWetWalkableFords() {
        NaturalWaterways waterways = new NaturalWaterways(42, 2048, 1, 0, 0, 0, true);
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        int broadest = 0, deepest = 0, fords = 0;
        for (int x = 0; x <= 850; x += 4) {
            int wetWidth = 0, coreDepth = 0;
            for (int z = -110; z <= 110; z++) {
                carve(waterways, x, z, sample);
                int depth = sample.waterLevel - sample.height;
                if (depth <= 0 || sample.channel < .2) continue;
                wetWidth++;
                deepest = Math.max(deepest, depth);
                if (sample.channel > .99) coreDepth = Math.max(coreDepth, depth);
            }
            broadest = Math.max(broadest, wetWidth);
            if (coreDepth == 1) fords++;
        }
        assertTrue(broadest >= 24, "The lower river needs substantial width");
        assertTrue(deepest >= 8, "Deep pools should be visibly deeper than the old narrow streams");
        assertTrue(fords >= 8, "Several broad bars should remain wet but walkable");
    }

    @Test
    void pondsAndMarshHollowsCannotCutThroughElevatedRiverbanks() {
        NaturalLandscape terrain = new NaturalLandscape(42, 2048);
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample(), neighbor = new WarzoneTerrain.Sample();
        // A pre-existing marsh hollow crosses this elevated tributary. Its lowland floor must not
        // punch through the banks and leave source-water faces hanging several blocks in the air.
        int elevatedColumns = 0;
        for (int z = -850; z <= 850; z += 4) {
            for (int x = -850; x <= 850; x += 4) {
                terrain.sample(x, z, sample);
                if (sample.waterLevel <= WarzoneTerrain.SEA_LEVEL || sample.height >= sample.waterLevel) continue;
                elevatedColumns++;
                for (int direction = 0; direction < 4; direction++) {
                    int dx = direction == 0 ? -1 : direction == 1 ? 1 : 0;
                    int dz = direction == 2 ? -1 : direction == 3 ? 1 : 0;
                    terrain.sample(x + dx, z + dz, neighbor);
                    int surface = Math.max(neighbor.height, neighbor.waterLevel);
                    assertTrue(surface >= sample.waterLevel - 1,
                            "An elevated bank must contain its water at x=" + x + ", z=" + z);
                    if (neighbor.height < neighbor.waterLevel) {
                        assertTrue(Math.abs(neighbor.waterLevel - sample.waterLevel) <= 1,
                                "Adjacent water surfaces need a continuous downstream gradient");
                    }
                }
            }
        }
        assertTrue(elevatedColumns > 500, "The bank checks must include substantial mountain water");
    }

    @Test
    void integratedMountainSpringFlowsThroughTheLandscapeToLowlandWater() {
        NaturalLandscape terrain = new NaturalLandscape(42, 2048);
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        terrain.sample(-746, -120, sample);
        assertTrue(sample.height > 90, "A mountain stream should originate within actual raised terrain");
        double x = -714, z = -165;
        int previousLevel = Integer.MAX_VALUE;
        for (int step = 0; step < 1600; step++) {
            terrain.sample((int) Math.round(x), (int) Math.round(z), sample);
            assertTrue(sample.height < sample.waterLevel && sample.channel > .6,
                    "Integrated water must remain continuous at x=" + x + ", z=" + z);
            assertTrue(sample.waterLevel <= previousLevel);
            if (step == 0) assertTrue(sample.waterLevel > 80);
            if (sample.waterState == WarzoneTerrain.WaterState.STILL) {
                assertEquals(WarzoneTerrain.SEA_LEVEL, sample.waterLevel);
                assertTrue(x > 500, "The river should settle in its inland downstream lake");
                return;
            }
            previousLevel = sample.waterLevel;
            double speed = Math.hypot(sample.flowX, sample.flowZ);
            assertTrue(speed > 0);
            x += sample.flowX / speed * 2;
            z += sample.flowZ / speed * 2;
        }
        fail("The mountain spring must connect to the lowland river");
    }

    @Test
    void elevatedStreamsDetourAroundTheLandingBeforeTheFlatPadIsApplied() {
        for (long seed : SEEDS) {
            NaturalLandscape terrain = new NaturalLandscape(seed, 2048);
            WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
            for (int dz = -104; dz <= 104; dz += 8) {
                for (int x = -104; x <= 104; x += 8) {
                    if (x * x + dz * dz > 110 * 110) continue;
                    terrain.sample(x, 696 + dz, sample);
                    assertEquals(WarzoneTerrain.SEA_LEVEL, sample.waterLevel,
                            "Mountain water must avoid the protected landing, seed=" + seed);
                    if (x * x + dz * dz <= 22 * 22) {
                        assertEquals(WarzoneTerrain.LANDING_Y, sample.height);
                        assertEquals(WarzoneTerrain.WaterState.NONE, sample.waterState);
                    }
                }
            }
        }
    }

    @Test
    void mainChannelChangesWidthAlongItsCourse() {
        // Axis-aligned geometry isolates river widths from oblique grid intersections and ponds.
        NaturalWaterways waterways = new NaturalWaterways(42, 2048, 1, 0, 0, 0);
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        int narrowest = Integer.MAX_VALUE, widest = 0;
        for (int x = -800; x <= 800; x += 20) {
            double center = Math.sin(x / 210.0) * 68 + Math.sin(x / 82.0) * 24 + Math.sin(x / 37.0) * 7;
            // The channel centerline is domain-warped by up to a dozen blocks; start from its wettest column.
            int z = (int) Math.round(center);
            double wettest = -1;
            for (int probe = (int) Math.round(center) - 16; probe <= Math.round(center) + 16; probe++) {
                waterways.carve(x, probe, 0, 76, sample);
                if (sample.channel > wettest) {
                    wettest = sample.channel;
                    z = probe;
                }
            }
            int width = 0;
            for (int direction : new int[]{-1, 1}) {
                for (int offset = direction < 0 ? 0 : 1; offset < 40; offset++) {
                    waterways.carve(x, z + direction * offset, 0, 76, sample);
                    if (sample.channel < .5) break;
                    width++;
                }
            }
            narrowest = Math.min(narrowest, width);
            widest = Math.max(widest, width);
        }
        assertTrue(narrowest >= 4, "The main channel must remain continuous");
        assertTrue(widest >= narrowest * 1.4,
                "River width should vary meaningfully: " + narrowest + ".." + widest);
    }

    @Test
    void waterAndRouteMetadataAreDeterministicWhenSamplesAreReusedInAnotherOrder() {
        NaturalLandscape first = new NaturalLandscape(42, 2048);
        NaturalLandscape second = new NaturalLandscape(42, 2048);
        WarzoneTerrain.Sample a = new WarzoneTerrain.Sample(), b = new WarzoneTerrain.Sample();
        for (int z = -1000; z <= 1000; z += 37) {
            for (int x = -1000; x <= 1000; x += 41) {
                first.sample(x, z, a);
                second.sample(-x, -z, b);
                b.waterState = WarzoneTerrain.WaterState.RAPIDS;
                b.flowX = Double.NaN;
                b.flowZ = Double.POSITIVE_INFINITY;
                b.routeStyle = -999;
                b.routeWear = Double.NaN;
                b.waterLevel = -999;
                second.sample(x, z, b);
                assertEquals(a.height, b.height);
                assertEquals(a.waterState, b.waterState);
                assertEquals(a.flowX, b.flowX);
                assertEquals(a.flowZ, b.flowZ);
                assertEquals(a.waterLevel, b.waterLevel);
                assertEquals(a.routeStyle, b.routeStyle);
                assertEquals(a.routeWear, b.routeWear);
                assertTrue(Double.isFinite(b.routeWear));
                assertTrue(b.routeWear >= 0 && b.routeWear <= 1);
            }
        }
        second.sample(2048, 2048, b);
        assertEquals(WarzoneTerrain.WaterState.NONE, b.waterState);
        assertEquals(0, b.flowX);
        assertEquals(0, b.flowZ);
        assertEquals(0, b.routeWear);
        assertEquals(WarzoneTerrain.SEA_LEVEL, b.waterLevel);
    }

    private static void followToLowlands(NaturalWaterways waterways, int startX, int startZ,
                                         WarzoneTerrain.Sample sample) {
        double x = startX, z = startZ;
        int previousLevel = Integer.MAX_VALUE;
        for (int step = 0; step < 1600; step++) {
            carve(waterways, (int) Math.round(x), (int) Math.round(z), sample);
            String location = "x=" + x + ", z=" + z;
            assertTrue(sample.channel > .6 && sample.height < sample.waterLevel,
                    "Flow must stay inside continuous water: " + location);
            assertTrue(sample.waterLevel <= previousLevel, "Water must not flow uphill: " + location);
            previousLevel = sample.waterLevel;
            if (sample.waterState == WarzoneTerrain.WaterState.STILL) {
                assertEquals(WarzoneTerrain.SEA_LEVEL, sample.waterLevel);
                assertTrue(x > 500, "Flow should end in a genuine downstream catchment lake");
                return;
            }
            double speed = Math.hypot(sample.flowX, sample.flowZ);
            assertTrue(speed > 0 && speed <= 1.000000001);
            x += sample.flowX / speed * 2;
            z += sample.flowZ / speed * 2;
        }
        fail("A tributary must reach the downstream lowland river");
    }

    private static void carve(NaturalWaterways waterways, int x, int z, WarzoneTerrain.Sample sample) {
        sample.channel = sample.valley = 0;
        sample.flowX = sample.flowZ = 0;
        sample.waterLevel = WarzoneTerrain.SEA_LEVEL;
        sample.waterState = WarzoneTerrain.WaterState.NONE;
        sample.height = (int) Math.floor(waterways.carve(x, z, 0,
                76 + waterways.mountainLift(x, z), sample));
    }
}
