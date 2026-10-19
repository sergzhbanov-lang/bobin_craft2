package com.bobincraft.raid;

import com.bobincraft.warzone.WarzoneTerrain;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TacticalTerrainPaletteTest {
    @Test
    void mapChangesFromShallowToDeepAtTheActualTwoBlockDepthBoundary() {
        WarzoneTerrain.Sample sample = drySample();
        sample.waterState = WarzoneTerrain.WaterState.FLOWING;
        sample.height = WarzoneTerrain.SEA_LEVEL - 2;
        int shallow = TacticalMapData.naturalTerrainColor(sample);
        sample.height++;
        assertEquals(shallow, TacticalMapData.naturalTerrainColor(sample));
        sample.height = WarzoneTerrain.SEA_LEVEL - 3;
        int deep = TacticalMapData.naturalTerrainColor(sample);
        assertNotEquals(shallow, deep, "A three-block channel must be recognizable as deep water");
        assertTrue(brightness(shallow) > brightness(deep), "Visible shallow shelves need a lighter map color");
        sample.height = WarzoneTerrain.SEA_LEVEL;
        int dryShore = TacticalMapData.naturalTerrainColor(sample);
        sample.height++;
        assertEquals(dryShore, TacticalMapData.naturalTerrainColor(sample),
                "The top solid block at sea level has no water above it");
        assertNotEquals(shallow, dryShore);
    }

    @Test
    void routeStylesAndWaterStatesRemainDistinguishableOnTheMap() {
        WarzoneTerrain.Sample sample = drySample();
        sample.path = 1;
        Set<Integer> routes = new HashSet<>();
        for (int style = 0; style < 4; style++) {
            sample.routeStyle = style;
            routes.add(TacticalMapData.naturalTerrainColor(sample));
        }
        assertEquals(4, routes.size(), "Grass tracks, gravel, leafy trails and bare footpaths need distinct map colors");
        sample.path = 0;
        sample.height = WarzoneTerrain.SEA_LEVEL - 6;
        Set<Integer> water = new HashSet<>();
        for (WarzoneTerrain.WaterState state : new WarzoneTerrain.WaterState[]{
                WarzoneTerrain.WaterState.STILL, WarzoneTerrain.WaterState.SLOW,
                WarzoneTerrain.WaterState.FLOWING, WarzoneTerrain.WaterState.RAPIDS}) {
            sample.waterState = state;
            water.add(TacticalMapData.naturalTerrainColor(sample));
        }
        assertEquals(4, water.size(), "Map readers should see calm, silty, flowing and rough reaches");
    }

    private static WarzoneTerrain.Sample drySample() {
        WarzoneTerrain.Sample sample = new WarzoneTerrain.Sample();
        sample.height = WarzoneTerrain.SEA_LEVEL + 10;
        sample.edge = 64;
        sample.region = WarzoneTerrain.Region.PLAINS;
        return sample;
    }

    private static int brightness(int color) {
        return ((color >>> 16) & 255) + ((color >>> 8) & 255) + (color & 255);
    }
}
