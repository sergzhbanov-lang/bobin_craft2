package com.bobincraft.warzone;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class NaturalRoutesTest {
    @Test
    void routesHaveDistinctWidthsSurfacesAndAlternateConnections() {
        NaturalLandscape landscape = new NaturalLandscape(42, 2048);
        EnumSet<NaturalLandscape.RouteStyle> styles = EnumSet.noneOf(NaturalLandscape.RouteStyle.class);
        Set<Integer> routes = new HashSet<>();
        int offLattice = 0, variedDirections = 0;
        double narrowest = Double.POSITIVE_INFINITY, widest = 0;
        for (NaturalLandscape.RouteSegment route : landscape.routes()) {
            styles.add(route.style);
            routes.add(route.id);
            narrowest = Math.min(narrowest, route.halfWidth);
            widest = Math.max(widest, route.halfWidth);
            if (route.x1 % 16 != 0 || route.z1 % 16 != 0) offLattice++;
            int dx = Math.abs(route.x2 - route.x1), dz = Math.abs(route.z2 - route.z1);
            if (dx != 0 && dz != 0 && dx != dz) variedDirections++;
            assertEquals(route.style == NaturalLandscape.RouteStyle.WOODLAND_TRAIL
                    || route.style == NaturalLandscape.RouteStyle.WORN_FOOTPATH, route.trail);
        }
        assertEquals(EnumSet.allOf(NaturalLandscape.RouteStyle.class), styles);
        assertTrue(widest > narrowest * 1.8, "Roads and footpaths must have visibly different widths");
        assertTrue(routes.size() > landscape.connectedReservationCount(), "Extra connections create loops and shortcuts");
        assertTrue(offLattice > landscape.routes().size() / 2, "Centerlines must leave the coarse planning lattice");
        assertTrue(variedDirections > landscape.routes().size() / 10, "Curves need more than eight grid directions");
    }

    @Test
    void roundedCurvesStayRenderedAndNeverCutThroughDeepWaterOrCliffs() {
        for (long seed : new long[]{0, 1, 42, 123456789, -734842}) {
            NaturalLandscape landscape = new NaturalLandscape(seed, 2048);
            WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
            assertFalse(landscape.routes().isEmpty());
            for (NaturalLandscape.RouteSegment route : landscape.routes()) {
                int steps = Math.max(1, (int) Math.ceil(Math.hypot(route.x2 - route.x1, route.z2 - route.z1)));
                int previousHeight = Integer.MIN_VALUE;
                for (int i = 0; i <= steps; i++) {
                    int x = (int) Math.round(route.x1 + (route.x2 - route.x1) * i / (double) steps);
                    int z = (int) Math.round(route.z1 + (route.z2 - route.z1) * i / (double) steps);
                    landscape.sample(x, z, sample);
                    assertTrue(sample.height >= sample.waterLevel - 1, "A curve must retain its shallow crossing");
                    assertTrue(sample.road > 0.9 || sample.path > 0.9, "Every rounded centerline column must be rendered");
                    if (previousHeight != Integer.MIN_VALUE) assertTrue(Math.abs(previousHeight - sample.height) <= 1,
                            "Routes must be walkable without jumping up two-block ledges");
                    previousHeight = sample.height;
                    assertTrue(sample.routeWear >= 0 && sample.routeWear <= 1);
                }
            }
        }
    }

    @Test
    void completeRoadNetworkConnectsEveryCountedDestinationToLanding() {
        for (long seed : new long[]{0, 1, 42, 123456789, -734842}) {
            NaturalLandscape landscape = new NaturalLandscape(seed, 2048);
            WarzoneTerrain.Sample landing = new WarzoneTerrain.Sample();
            landscape.sample(0, (int) (1024 * 0.68), landing);
            assertTrue(landing.height >= landing.waterLevel, "The landing must remain dry for seed " + seed);
            Map<Long, List<Long>> neighbors = new HashMap<>();
            for (NaturalLandscape.RouteSegment route : landscape.routes()) {
                long a = point(route.x1, route.z1), b = point(route.x2, route.z2);
                neighbors.computeIfAbsent(a, ignored -> new ArrayList<>()).add(b);
                neighbors.computeIfAbsent(b, ignored -> new ArrayList<>()).add(a);
            }
            Set<Long> reachable = new HashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(point(0, (int) (1024 * 0.68)));
            while (!queue.isEmpty()) {
                long current = queue.removeFirst();
                if (!reachable.add(current)) continue;
                queue.addAll(neighbors.getOrDefault(current, List.of()));
            }
            int connected = 0;
            for (NaturalLandscape.Reservation reservation : landscape.reservations()) {
                if (reachable.contains(point(reservation.x, reservation.z))) connected++;
            }
            assertEquals(landscape.connectedReservationCount(), connected,
                    "The planner may count a destination only when its actual rendered geometry reaches it");
            assertEquals(neighbors.size(), reachable.size(), "Paths must not leave disconnected curves or endpoint gaps");
            assertEquals(landscape.reservations().size(), connected, "Destinations should share one traversable network");
        }
    }

    @Test
    void roadCarriagewaysAvoidDeepWaterAndSheerBankEdges() {
        NaturalLandscape landscape = new NaturalLandscape(42, 2048);
        WarzoneTerrain.Sample center = new WarzoneTerrain.Sample(), verge = new WarzoneTerrain.Sample();
        for (NaturalLandscape.RouteSegment route : landscape.routes()) {
            double dx = route.x2 - route.x1, dz = route.z2 - route.z1, length = Math.hypot(dx, dz);
            if (length == 0) continue;
            int steps = Math.max(1, (int) Math.ceil(length));
            for (int step = 0; step <= steps; step++) {
                int x = (int) Math.round(route.x1 + dx * step / steps);
                int z = (int) Math.round(route.z1 + dz * step / steps);
                landscape.sample(x, z, center);
                for (int side : new int[]{-1, 1}) {
                    landscape.sample((int) Math.round(x - dz / length * route.halfWidth * 0.60 * side),
                            (int) Math.round(z + dx / length * route.halfWidth * 0.60 * side), verge);
                    assertTrue(verge.height >= verge.waterLevel - 1, "Wheel tracks must stay out of deep water");
                    assertTrue(Math.abs(center.height - verge.height) <= 3,
                            "Routes should follow a usable contour, with no sheer edge inside the carriageway");
                }
            }
        }
    }

    private static long point(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    @Test
    void wagonTracksHaveExposedWheelRutsAndGrassyCenters() {
        NaturalLandscape landscape = new NaturalLandscape(42, 2048);
        WarzoneTerrain.Sample center = new WarzoneTerrain.Sample(), wheel = new WarzoneTerrain.Sample();
        int pairedRuts = 0;
        for (NaturalLandscape.RouteSegment route : landscape.routes()) {
            if (route.style != NaturalLandscape.RouteStyle.WAGON_TRACK) continue;
            double dx = route.x2 - route.x1, dz = route.z2 - route.z1, length = Math.hypot(dx, dz);
            if (length < 4 || route.halfWidth < 3.5) continue;
            int x = (int) Math.round((route.x1 + route.x2) / 2.0);
            int z = (int) Math.round((route.z1 + route.z2) / 2.0);
            landscape.sample(x, z, center);
            if (center.routeStyle != route.style.ordinal() || center.routeWear > 0.4) continue;
            boolean both = true;
            for (int side : new int[]{-1, 1}) {
                landscape.sample((int) Math.round(x - dz / length * route.halfWidth * 0.52 * side),
                        (int) Math.round(z + dx / length * route.halfWidth * 0.52 * side), wheel);
                if (wheel.routeStyle != route.style.ordinal() || wheel.routeWear < 0.6) both = false;
            }
            if (both) pairedRuts++;
        }
        assertTrue(pairedRuts > 5, "Both sides of a wagon track should expose wheel ruts around a grassy center");
    }

    @Test
    void routeAppearanceIsStableAcrossChunkSeamsAndSamplingOrder() {
        NaturalLandscape first = new NaturalLandscape(123456789, 2048);
        NaturalLandscape second = new NaturalLandscape(123456789, 2048);
        assertEquals(first.routes().size(), second.routes().size());
        WarzoneTerrain.Sample a = new WarzoneTerrain.Sample(), b = new WarzoneTerrain.Sample();
        for (int i = first.routes().size() - 1; i >= 0; i -= 7) {
            NaturalLandscape.RouteSegment route = first.routes().get(i);
            NaturalLandscape.RouteSegment copy = second.routes().get(i);
            assertEquals(route.style, copy.style);
            assertEquals(route.halfWidth, copy.halfWidth);
            assertArrayEquals(new int[]{route.x1, route.z1, route.x2, route.z2},
                    new int[]{copy.x1, copy.z1, copy.x2, copy.z2});
            for (int offset = -1; offset <= 1; offset++) {
                int x = Math.floorDiv(route.x1, 16) * 16 + offset;
                first.sample(x, route.z1, a);
                second.sample(-x, -route.z1, b);
                second.sample(x, route.z1, b);
                assertEquals(a.road, b.road);
                assertEquals(a.path, b.path);
                assertEquals(a.routeStyle, b.routeStyle);
                assertEquals(a.routeWear, b.routeWear);
            }
        }
    }
}
