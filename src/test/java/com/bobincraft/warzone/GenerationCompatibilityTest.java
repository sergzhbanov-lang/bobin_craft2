package com.bobincraft.warzone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Fixtures captured from pristine v1-v3 sources before introducing the next landscape profile. */
final class GenerationCompatibilityTest {
    private static final long[] SEEDS = {42, 7919, -734842};

    @Test
    void savedLegacyProfilesRetainTheirTerrain() {
        long[] expected = {6643503317002554348L, 2164067092340480164L, -7882832264460260507L};
        for (int i = 0; i < SEEDS.length; i++) {
            WarzoneTerrain terrain = new WarzoneTerrain(SEEDS[i], 1024, 1);
            assertFalse(terrain.isNatural());
            assertFalse(terrain.usesRevisedLandscape());
            assertEquals(expected[i], samples(terrain), "Saved version-one terrain for seed " + SEEDS[i]);
        }
        assertEquals(1, new WarzoneTerrain(42, 1024).generationVersion(),
                "Existing two-argument terrain callers retain the original profile");
    }

    @Test
    void savedNaturalProfilesRetainTerrainReservationsAndRoutes() {
        long[] expectedSamples = {6521826032050493503L, -4633843686995069956L, 3632909916469520160L};
        long[] expectedPlans = {1100905219162714529L, -9171553504170297078L, -2968442288112285947L};
        for (int i = 0; i < SEEDS.length; i++) {
            WarzoneTerrain terrain = new WarzoneTerrain(SEEDS[i], 2048, 2);
            assertTrue(terrain.isNatural());
            assertFalse(terrain.usesRevisedLandscape());
            assertEquals(expectedSamples[i], samples(terrain), "Saved version-two terrain for seed " + SEEDS[i]);
            assertEquals(expectedPlans[i], plan(terrain.naturalLandscape()),
                    "Saved version-two opportunities and route centerlines for seed " + SEEDS[i]);
        }
    }

    @Test
    void savedRevisedProfilesRetainTerrainWaterAndRoutes() {
        long[] expectedSamples = {-5317921037656307672L, 4162988865404797701L, 1569315438866731449L};
        long[] expectedPlans = {-5469015476246611435L, 3206627535968869564L, -1872537336873703000L};
        long[] expectedMetadata = {2689191686506275843L, -7421609917431483739L, 7033385566632356855L};
        for (int i = 0; i < SEEDS.length; i++) {
            WarzoneTerrain terrain = new WarzoneTerrain(SEEDS[i], 2048, 3);
            assertTrue(terrain.usesRevisedLandscape());
            assertEquals(expectedSamples[i], samples(terrain), "Saved version-three terrain for seed " + SEEDS[i]);
            assertEquals(expectedPlans[i], plan(terrain.naturalLandscape()),
                    "Saved version-three reservations and routes for seed " + SEEDS[i]);
            assertEquals(expectedMetadata[i], revisedMetadata(terrain),
                    "Saved version-three water states, currents and route materials for seed " + SEEDS[i]);
        }
    }

    @Test
    void revisedProfileIsExplicitAndReusedLegacySamplesResetNewMetadata() {
        WarzoneTerrain revised = new WarzoneTerrain(42, 2048, 4);
        assertTrue(revised.isNatural());
        assertTrue(revised.usesRevisedLandscape());
        assertNotEquals(-5317921037656307672L, samples(revised), "New worlds receive the mountain-river landscape");
        assertTrue(revised.basePlan().sites().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new WarzoneTerrain(42, 2048, 5));

        WarzoneTerrain.Sample reused = new WarzoneTerrain.Sample();
        for (int version : new int[]{1, 2, 3}) {
            WarzoneTerrain old = new WarzoneTerrain(42, version == 1 ? 1024 : 2048, version);
            for (int[] point : new int[][]{{0, 0}, {old.max, 0}}) {
                reused.routeStyle = 7;
                reused.routeWear = 1;
                reused.waterState = WarzoneTerrain.WaterState.RAPIDS;
                reused.flowX = reused.flowZ = 1;
                reused.waterLevel = 180;
                old.sample(point[0], point[1], reused);
                assertEquals(WarzoneTerrain.SEA_LEVEL, reused.waterLevel);
                if (version == 3) continue;
                assertEquals(0, reused.routeStyle);
                assertEquals(0, reused.routeWear);
                assertEquals(WarzoneTerrain.WaterState.NONE, reused.waterState);
                assertEquals(0, reused.flowX);
                assertEquals(0, reused.flowZ);
            }
        }
    }

    private static long revisedMetadata(WarzoneTerrain terrain) {
        long hash = 0xcbf29ce484222325L;
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        for (int z = terrain.min - 9; z <= terrain.max + 9; z += 37) {
            for (int x = terrain.min - 9; x <= terrain.max + 9; x += 37) {
                terrain.sample(x, z, sample);
                hash = add(hash, sample.routeStyle);
                hash = add(hash, sample.waterState.ordinal());
                for (double value : new double[]{sample.routeWear, sample.flowX, sample.flowZ}) {
                    hash = add(hash, Double.doubleToLongBits(value));
                }
            }
        }
        for (NaturalLandscape.RouteSegment route : terrain.naturalLandscape().routes()) {
            hash = add(hash, route.style.ordinal());
            hash = add(hash, Double.doubleToLongBits(route.halfWidth));
            hash = add(hash, Double.doubleToLongBits(route.wear));
            hash = add(hash, route.id);
        }
        return hash;
    }

    private static long add(long hash, long value) { return (hash ^ value) * 1099511628211L; }

    private static long samples(WarzoneTerrain terrain) {
        long hash = 0xcbf29ce484222325L;
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        for (int z = terrain.min - 9; z <= terrain.max + 9; z += 37) {
            for (int x = terrain.min - 9; x <= terrain.max + 9; x += 37) {
                terrain.sample(x, z, sample);
                hash = add(hash, sample.height);
                hash = add(hash, sample.region == null ? -1 : sample.region.ordinal());
                hash = add(hash, sample.edge);
                hash = add(hash, sample.trail ? 1 : 0);
                for (double value : new double[]{sample.channel, sample.valley, sample.scar, sample.mountain,
                        sample.road, sample.basePad, sample.roadBroken, sample.path, sample.desert, sample.dune,
                        sample.pillar, sample.oasis, sample.bog, sample.pool}) {
                    hash = add(hash, Double.doubleToLongBits(value));
                }
            }
        }
        return hash;
    }

    private static long plan(NaturalLandscape terrain) {
        long hash = add(0xcbf29ce484222325L, terrain.connectedReservationCount());
        for (NaturalLandscape.Reservation reservation : terrain.reservations()) {
            for (int value : new int[]{reservation.type.ordinal(), reservation.x, reservation.z,
                    reservation.radius, reservation.height}) hash = add(hash, value);
            hash = add(hash, Double.doubleToLongBits(reservation.relief));
        }
        for (NaturalLandscape.RouteSegment route : terrain.routes()) {
            for (int value : new int[]{route.x1, route.z1, route.x2, route.z2, route.trail ? 1 : 0})
                hash = add(hash, value);
        }
        return hash;
    }
}
