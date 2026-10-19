package com.bobincraft.warzone;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NaturalForestTest {
    private static final int[][] SIX = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final long SEED = 7919L;
    private record Position(int x, int y, int z) { }

    @Test
    void silhouettesAreDistinctBoundedAndVaryWithThePlantSeed() {
        WarzoneTerrain terrain = new WarzoneTerrain(SEED, 2048, 2);
        NaturalForest forest = new NaturalForest(terrain);
        Set<Map<Position, Integer>> silhouettes = new HashSet<>();
        for (NaturalForest.Kind kind : NaturalForest.Kind.values()) {
            NaturalForest.Plant p = new NaturalForest.Plant(300, terrain.height(300, -300) + 1, -300, kind, 1234, 50, -50, false);
            Map<Position, Integer> shape = blocks(forest.rawShape(p));
            assertFalse(shape.isEmpty(), kind.toString());
            assertTrue(silhouettes.add(shape), "Each kind needs a distinct silhouette: " + kind);
            for (Position pos : shape.keySet()) {
                assertTrue(Math.abs(pos.x) <= NaturalForest.MAX_REACH && Math.abs(pos.z) <= NaturalForest.MAX_REACH,
                        "Chunk replay margin must cover " + kind + " at " + pos);
            }
            if (kind == NaturalForest.Kind.PINE) {
                assertTrue(shape.keySet().stream().mapToInt(Position::y).max().orElseThrow() >= 17);
                assertTrue(shape.entrySet().stream().filter(e -> NaturalForest.type(e.getValue()) == NaturalForest.LEAF)
                        .mapToInt(e -> e.getKey().y).min().orElseThrow() >= 8);
            }
            if (kind == NaturalForest.Kind.YOUNG_PINE)
                assertTrue(shape.keySet().stream().mapToInt(Position::y).max().orElseThrow() <= 10);
            if (kind == NaturalForest.Kind.PINE || kind == NaturalForest.Kind.SPRUCE || kind == NaturalForest.Kind.TWISTED) {
                Map<Position, Integer> other = blocks(forest.rawShape(new NaturalForest.Plant(300, 80, -300,
                        kind, 4321, 50, -50, false)));
                assertNotEquals(shape, other, "Major tree silhouettes must vary within each kind");
            }
        }
    }

    @Test
    void anchoredPlantsRespectRoutesGroundAndWoodLeafConnectivity() {
        WarzoneTerrain terrain = new WarzoneTerrain(SEED, 2048, 2);
        NaturalForest forest = terrain.naturalForest();
        Set<NaturalForest.Plant> plants = new LinkedHashSet<>();
        for (int z = -320; z <= 320; z += 80) for (int x = -320; x <= 320; x += 80)
            plants.addAll(forest.plantsForChunk(x, z));
        int inspected = 0, leaves = 0, floorObjects = 0;
        Set<NaturalForest.Kind> kinds = new HashSet<>();
        for (NaturalForest.Plant plant : plants) {
            Map<Position, Integer> shape = blocks(forest.shape(plant));
            if (shape.isEmpty()) continue;
            kinds.add(plant.kind());
            if (plant.floor()) floorObjects++;
            inspected++;
            Set<Position> wood = new HashSet<>(), supported = new HashSet<>();
            for (var entry : shape.entrySet()) {
                Position p = entry.getKey();
                int worldX = plant.x() + p.x, worldY = plant.y() + p.y, worldZ = plant.z() + p.z;
                assertTrue(terrain.inside(worldX, worldZ));
                assertFalse(terrain.isReserved(worldX, worldZ), "No branch or leaf may obstruct a reserved route");
                int ground = terrain.height(worldX, worldZ);
                assertTrue(worldY > ground, "No plant block may be embedded in terrain");
                if (NaturalForest.type(entry.getValue()) == NaturalForest.LOG) {
                    wood.add(p);
                    if (worldY == ground + 1) supported.add(p);
                } else if (NaturalForest.type(entry.getValue()) == NaturalForest.LEAF) {
                    leaves++;
                    if (!plant.floor() && plant.kind() != NaturalForest.Kind.YOUNG_PINE)
                        assertTrue(worldY >= ground + 4, "Mature canopies must preserve walking space");
                }
                if (worldY < ground + 4) assertFalse(forest.movementCorridor(worldX, worldZ));
            }
            Set<Position> connected = flood(wood, supported);
            assertEquals(wood, connected, "All wood must connect by faces to actual ground: " + plant);
            Set<Position> leafAndWood = new HashSet<>(wood);
            shape.forEach((p, c) -> { if (NaturalForest.type(c) == NaturalForest.LEAF) leafAndWood.add(p); });
            assertEquals(leafAndWood, flood(leafAndWood, connected), "All leaves must connect to wood: " + plant);
        }
        assertTrue(inspected > 150, "The fixture must exercise a real forest");
        assertTrue(leaves > 20000);
        assertTrue(floorObjects > 5, "The fixture must include natural floor obstacles");
        assertTrue(kinds.containsAll(Set.of(NaturalForest.Kind.PINE, NaturalForest.Kind.SPRUCE,
                NaturalForest.Kind.TWISTED, NaturalForest.Kind.DEAD, NaturalForest.Kind.YOUNG_PINE, NaturalForest.Kind.BIRCH)));
    }

    @Test
    void independentlyClippedChunksMatchWholeForestIncludingNegativeBordersAndReverseOrder() {
        WarzoneTerrain terrain = new WarzoneTerrain(SEED, 2048, 2);
        NaturalForest forest = terrain.naturalForest();
        List<Position> chunks = new ArrayList<>();
        for (int z = -32; z < 32; z += 16) for (int x = -32; x < 32; x += 16) chunks.add(new Position(x, 0, z));
        Set<NaturalForest.Plant> plants = new HashSet<>();
        for (Position chunk : chunks) plants.addAll(forest.plantsForChunk(chunk.x, chunk.z));
        List<NaturalForest.Plant> ordered = new ArrayList<>(plants);
        ordered.sort(Comparator.comparing(NaturalForest.Plant::floor)
                .thenComparingInt(NaturalForest.Plant::cellZ).thenComparingInt(NaturalForest.Plant::cellX));
        Map<Position, Integer> expected = new HashMap<>();
        int crossing = 0;
        for (NaturalForest.Plant plant : ordered) {
            Set<Position> touched = new HashSet<>();
            forest.shape(plant).forEach((dx, dy, dz, code) -> {
                Position p = new Position(plant.x() + dx, plant.y() + dy, plant.z() + dz);
                if (p.x < -32 || p.x >= 32 || p.z < -32 || p.z >= 32) return;
                touched.add(new Position(Math.floorDiv(p.x, 16), 0, Math.floorDiv(p.z, 16)));
                overlay(expected, p, code);
            });
            if (touched.size() > 1) crossing++;
        }
        assertTrue(crossing > 5, "The fixture must include canopies crossing several chunk borders");
        assertTrue(expected.keySet().stream().anyMatch(p -> p.x < 0 && p.z < 0));
        Map<Position, Integer> actual = replay(forest, chunks);
        assertEquals(expected, actual);
        Collections.reverse(chunks);
        NaturalForest restarted = new NaturalForest(new WarzoneTerrain(SEED, 2048, 2));
        assertEquals(expected, replay(restarted, chunks), "Fresh plans and reverse chunk order must produce identical blocks");
    }

    @Test
    void newBouldersHaveFlatTallAndRoundedFormsWhileOlderWorldShapesStayStable() {
        NaturalForest old = new NaturalForest(new WarzoneTerrain(SEED, 2048, 2));
        NaturalForest saved = new NaturalForest(new WarzoneTerrain(SEED, 2048, 3));
        NaturalForest revised = new NaturalForest(new WarzoneTerrain(SEED, 2048, 4));
        Set<Map<Position, Integer>> forms = new HashSet<>();
        Set<Integer> heights = new HashSet<>();
        for (int seed = 0; seed < 64; seed++) {
            NaturalForest.Plant plant = new NaturalForest.Plant(400, 90, -300, NaturalForest.Kind.BOULDER,
                    seed, 50, -38, true);
            assertEquals(blocks(old.rawShape(plant)), blocks(saved.rawShape(plant)), "Saved v2/v3 rocks remain stable");
            Map<Position, Integer> shape = blocks(revised.rawShape(plant));
            assertFalse(shape.isEmpty());
            forms.add(shape);
            heights.add(shape.keySet().stream().mapToInt(Position::y).max().orElseThrow());
            for (Position p : shape.keySet())
                assertTrue(Math.abs(p.x) <= 4 && Math.abs(p.z) <= 4 && p.y >= 0 && p.y <= 3);
        }
        assertTrue(forms.size() > 40, "Rock silhouettes should vary within each material");
        assertTrue(heights.containsAll(Set.of(0, 1, 2)), "Low slabs and tall outcrops should both occur");
    }

    @Test
    void revisedBouldersConnectToTheActualSlopeAfterTerrainAndRouteClipping() {
        WarzoneTerrain terrain = new WarzoneTerrain(SEED, 2048, 4);
        NaturalForest forest = terrain.naturalForest();
        int inspected = 0;
        for (int z = -380; z <= 380; z += 95) for (int x = -380; x <= 380; x += 95) {
            NaturalForest.Plant plant = new NaturalForest.Plant(x, terrain.height(x, z) + 1, z,
                    NaturalForest.Kind.BOULDER, WarzoneNoise.hash(SEED, x, z), x / 8, z / 8, true);
            Map<Position, Integer> shape = blocks(forest.shape(plant));
            if (shape.isEmpty()) continue;
            Set<Position> roots = new HashSet<>();
            shape.forEach((p, code) -> {
                assertEquals(NaturalForest.ROCK, NaturalForest.type(code));
                int ground = terrain.height(plant.x() + p.x, plant.z() + p.z);
                assertTrue(plant.y() + p.y > ground);
                assertFalse(terrain.isReserved(plant.x() + p.x, plant.z() + p.z));
                if (plant.y() + p.y == ground + 1) roots.add(p);
            });
            assertEquals(shape.keySet(), flood(shape.keySet(), roots), "No clipped rock fragment may float above a slope");
            inspected++;
        }
        assertTrue(inspected > 10);
    }

    private static Map<Position, Integer> replay(NaturalForest forest, List<Position> chunks) {
        Map<Position, Integer> result = new HashMap<>();
        for (Position chunk : chunks) for (NaturalForest.Plant plant : forest.plantsForChunk(chunk.x, chunk.z)) {
            forest.shape(plant).forEach((dx, dy, dz, code) -> {
                Position p = new Position(plant.x() + dx, plant.y() + dy, plant.z() + dz);
                if (p.x >= chunk.x && p.x < chunk.x + 16 && p.z >= chunk.z && p.z < chunk.z + 16) overlay(result, p, code);
            });
        }
        return result;
    }

    private static void overlay(Map<Position, Integer> result, Position position, int code) {
        Integer old = result.get(position);
        if (old == null || NaturalForest.type(code) == NaturalForest.LOG && NaturalForest.type(old) == NaturalForest.LEAF)
            result.put(position, code);
    }

    private static Map<Position, Integer> blocks(NaturalForest.Shape shape) {
        Map<Position, Integer> result = new HashMap<>();
        shape.forEach((x, y, z, code) -> result.put(new Position(x, y, z), code));
        return result;
    }

    private static Set<Position> flood(Set<Position> allowed, Set<Position> roots) {
        Set<Position> reached = new HashSet<>(roots);
        ArrayDeque<Position> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            Position p = pending.remove();
            for (int[] d : SIX) {
                Position next = new Position(p.x + d[0], p.y + d[1], p.z + d[2]);
                if (allowed.contains(next) && reached.add(next)) pending.add(next);
            }
        }
        return reached;
    }
}
