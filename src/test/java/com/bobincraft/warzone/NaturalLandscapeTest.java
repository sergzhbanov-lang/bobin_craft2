package com.bobincraft.warzone;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

final class NaturalLandscapeTest {
    @Test
    void landingAndCenteredBoundsStayCompatibleWithRaids() {
        NaturalLandscape terrain = new NaturalLandscape(42, 2048);
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        for (int dz = -22; dz <= 22; dz++) {
            for (int x = -22; x <= 22; x++) {
                if (x * x + dz * dz > 22 * 22) continue;
                terrain.sample(x, 696 + dz, sample);
                assertEquals(70, sample.height, "Landing must be level and dry");
                assertEquals(0, sample.channel);
            }
        }
        for (int[] point : new int[][]{{-1024, 0}, {1023, 0}, {0, -1024}, {0, 1023}}) {
            terrain.sample(point[0], point[1], sample);
            assertEquals(0, sample.edge);
            assertTrue(sample.height > 100, "The bounded arena retains its rocky perimeter");
        }
        for (int[] point : new int[][]{{-1025, 0}, {1024, 0}, {0, -1025}, {0, 1024}}) {
            terrain.sample(point[0], point[1], sample);
            assertEquals(-1, sample.edge);
            assertEquals(0, sample.height);
        }
    }

    @Test
    void seedAndSamplingOrderCannotChangeChunkSeamsOrReuseLegacyFields() {
        NaturalLandscape forward = new NaturalLandscape(123456789, 2048);
        NaturalLandscape reverse = new NaturalLandscape(123456789, 2048);
        WarzoneTerrain.Sample a = new WarzoneTerrain.Sample(), b = new WarzoneTerrain.Sample();
        for (int chunk = -61; chunk <= 60; chunk++) {
            int x = chunk * 16;
            // Sample both sides of many chunk seams, in the opposite order in the second instance.
            for (int offset = -1; offset <= 1; offset++) {
                int z = Math.floorMod(chunk * 157, 1840) - 920;
                forward.sample(x + offset, z, a);
                reverse.sample(x + 1 - offset, -z, b);
                b.scar = b.basePad = b.roadBroken = b.desert = b.dune = b.pillar = b.oasis = 1;
                reverse.sample(x + offset, z, b);
                assertEquals(a.height, b.height);
                assertEquals(a.region, b.region);
                assertEquals(a.channel, b.channel);
                assertEquals(a.road, b.road);
                assertEquals(a.path, b.path);
                assertEquals(forward.sampleRegion(x + offset, z), a.region);
                assertEquals(0, b.scar + b.basePad + b.roadBroken + b.desert + b.dune + b.pillar + b.oasis);
                forward.sample(x + offset + 1, z, b);
                assertTrue(Math.abs(a.height - b.height) <= 3,
                        "Adjacent interior columns must not acquire chunk-sized steps");
            }
        }
        assertEquals(forward.routes().size(), reverse.routes().size());
        for (int i = 0; i < forward.routes().size(); i++) {
            NaturalLandscape.RouteSegment first = forward.routes().get(i), second = reverse.routes().get(i);
            assertArrayEquals(new int[]{first.x1, first.z1, first.x2, first.z2},
                    new int[]{second.x1, second.z1, second.x2, second.z2});
            assertEquals(first.trail, second.trail);
        }
    }

    @Test
    void futureOpportunitiesUseGentleDryGroundAndRoadsOnlyUseWalkableShallowCrossings() {
        for (long seed : new long[]{0, 1, 42, 123456789, -734842}) {
            NaturalLandscape terrain = new NaturalLandscape(seed, 2048);
            WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
            assertEquals(9, terrain.reservations().size(), "One opportunity per planning sector");
            assertEquals(terrain.reservations().size(), terrain.connectedReservationCount());
            EnumSet<NaturalLandscape.ReservationType> types = EnumSet.noneOf(NaturalLandscape.ReservationType.class);
            for (NaturalLandscape.Reservation reservation : terrain.reservations()) {
                types.add(reservation.type);
                int low = 256, high = 0;
                for (int dz = -reservation.radius; dz <= reservation.radius; dz += 6) {
                    for (int dx = -reservation.radius; dx <= reservation.radius; dx += 6) {
                        if (dx * dx + dz * dz > reservation.radius * reservation.radius) continue;
                        terrain.sample(reservation.x + dx, reservation.z + dz, sample);
                        assertTrue(sample.height >= sample.waterLevel + 2, "Future footprints must avoid water and wet depressions");
                        assertEquals(0, sample.basePad, "Future opportunities must not flatten the landscape");
                        low = Math.min(low, sample.height);
                        high = Math.max(high, sample.height);
                    }
                }
                assertTrue(high - low <= Math.max(7, reservation.radius * 0.17 + 2));
            }
            assertEquals(EnumSet.allOf(NaturalLandscape.ReservationType.class), types);
            assertFalse(terrain.routes().isEmpty());
            for (NaturalLandscape.RouteSegment route : terrain.routes()) {
                int steps = (int) Math.ceil(Math.hypot(route.x2 - route.x1, route.z2 - route.z1));
                for (int step = 0; step <= steps; step++) {
                    int x = (int) Math.round(route.x1 + (route.x2 - route.x1) * (double) step / steps);
                    int z = (int) Math.round(route.z1 + (route.z2 - route.z1) * (double) step / steps);
                    terrain.sample(x, z, sample);
                    assertTrue(sample.height >= sample.waterLevel - 1, "Roads must avoid channels deeper than one block");
                    assertTrue(sample.road > 0.9 || sample.path > 0.9, "Every planned centerline must be rendered");
                }
            }
        }
    }

    @Test
    void naturalTerrainRetainsSafeSeparatedDeterministicRaidChoices() {
        for (int version : new int[]{2, 4}) {
            for (long seed : new long[]{0, 42, -734842}) {
                WarzoneTerrain terrain = new WarzoneTerrain(seed, 2048, version);
                var points = com.bobincraft.raid.DeploymentPlanner.plan(terrain, 17);
                assertEquals(3, points.size());
                assertEquals(points, com.bobincraft.raid.DeploymentPlanner.plan(terrain, 17));
                assertTrue(terrain.basePlan().sites().isEmpty());
                assertTrue(terrain.hideoutPlan().hideouts().isEmpty());
                assertTrue(terrain.poiPlan().checkpoints().isEmpty());
                assertTrue(terrain.poiPlan().battlefields().isEmpty());
                for (int i = 0; i < points.size(); i++) {
                    var point = points.get(i);
                    assertTrue(com.bobincraft.raid.DeploymentPlanner.isSafeCandidate(terrain, point.x(), point.z()));
                    assertEquals(terrain.height(point.x(), point.z()) + 1, point.y());
                    for (int j = i + 1; j < points.size(); j++) {
                        var other = points.get(j);
                        assertTrue(Math.hypot(point.x() - other.x(), point.z() - other.z())
                                >= com.bobincraft.raid.DeploymentPlanner.minimumSeparation(terrain));
                    }
                }
            }
        }
    }

    @Test
    void woodlandWetlandsAndRockyCoverExistWithoutExcessiveMountains() {
        NaturalLandscape terrain = new NaturalLandscape(42, 2048);
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        int water = 0, marsh = 0, rocky = 0, dense = 0, clearing = 0, count = 0;
        for (int z = -928; z <= 928; z += 16) {
            for (int x = -928; x <= 928; x += 16) {
                terrain.sample(x, z, sample);
                count++;
                if (sample.height < sample.waterLevel) water++;
                if (sample.bog > 0.54) marsh++;
                if (sample.mountain > 0.42) rocky++;
                double density = terrain.forest(x, z);
                if (density > 0.80) dense++;
                if (density < 0.25) clearing++;
                assertTrue(sample.height < 145, "Headwater mountains stay inside the finite terrain height budget");
                if (sample.height >= 115)
                    assertTrue(sample.mountain > .42, "High interior elevations must belong to mountain headwaters");
            }
        }
        assertTrue(water > count * 0.005 && water < count * 0.06);
        assertTrue(marsh > count * 0.02 && marsh < count * 0.20);
        assertTrue(rocky > count * 0.01 && rocky < count * 0.15);
        assertTrue(dense > count * 0.15, "Forests need dense concealment patches");
        assertTrue(clearing > count * 0.08, "Forest patches need usable open gaps");
    }
}
