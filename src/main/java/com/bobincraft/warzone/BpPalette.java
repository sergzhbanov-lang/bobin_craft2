package com.bobincraft.warzone;

/**
 * Региональная и фракционная палитра материалов для чертежей (БЕЗ Minecraft: строки id и {@link BpSpec}).
 * Значения один в один повторяют {@code WarzoneStructures.Palette}, чтобы базы, вышки и укрытия выглядели одинаково
 * в старом (по чанкам) и новом (чертёж) построителях.
 */
final class BpPalette {
    BpSpec wallMain, wallCracked, wallMossy, pillar, trim, floorMain, floorAccent;
    String roofSlab, roofStairs, stoneStairs, stoneWall, log;
    BpSpec planks;
    String woodStairs, woodSlab, woodFence, woodGate, woodDoor, woodTrapdoor;
    BpSpec sandbag, yardPath;
    /** Цветная шерсть фракции (койки, флаги, вымпелы). */
    BpSpec bannerWool = BpSpec.of("green_wool");

    BpPalette(WarzoneTerrain.Region region, Faction faction) {
        this(region);
        applyFaction(faction);
    }

    BpPalette(WarzoneTerrain.Region region) {
            switch (region) {
                case HIGHLANDS -> {
                    wallMain = BpSpec.of("deepslate_bricks");
                    wallCracked = BpSpec.of("cracked_deepslate_bricks");
                    wallMossy = BpSpec.of("deepslate_tiles");
                    pillar = BpSpec.of("polished_deepslate");
                    trim = BpSpec.of("polished_andesite");
                    floorMain = BpSpec.of("polished_andesite");
                    floorAccent = BpSpec.of("deepslate_tiles");
                    roofSlab = "deepslate_tile_slab";
                    roofStairs = "deepslate_tile_stairs";
                    stoneStairs = "deepslate_brick_stairs";
                    stoneWall = "deepslate_brick_wall";
                    log = "spruce_log";
                    planks = BpSpec.of("spruce_planks");
                    woodStairs = "spruce_stairs";
                    woodSlab = "spruce_slab";
                    woodFence = "spruce_fence";
                    woodGate = "spruce_fence_gate";
                    woodDoor = "spruce_door";
                    woodTrapdoor = "spruce_trapdoor";
                    sandbag = BpSpec.of("yellow_concrete_powder");
                    yardPath = BpSpec.of("cobblestone");
                }
                case BADLANDS -> {
                    wallMain = BpSpec.of("cut_red_sandstone");
                    wallCracked = BpSpec.of("smooth_red_sandstone");
                    wallMossy = BpSpec.of("brown_terracotta");
                    pillar = BpSpec.of("chiseled_red_sandstone");
                    trim = BpSpec.of("terracotta");
                    floorMain = BpSpec.of("smooth_red_sandstone");
                    floorAccent = BpSpec.of("brown_terracotta");
                    roofSlab = "smooth_red_sandstone_slab";
                    roofStairs = "smooth_red_sandstone_stairs";
                    stoneStairs = "red_sandstone_stairs";
                    stoneWall = "red_sandstone_wall";
                    log = "dark_oak_log";
                    planks = BpSpec.of("oak_planks");
                    woodStairs = "oak_stairs";
                    woodSlab = "oak_slab";
                    woodFence = "oak_fence";
                    woodGate = "oak_fence_gate";
                    woodDoor = "dark_oak_door";
                    woodTrapdoor = "dark_oak_trapdoor";
                    sandbag = BpSpec.of("sand");
                    yardPath = BpSpec.of("terracotta");
                }
                case MARSH -> {
                    wallMain = BpSpec.of("mossy_stone_bricks");
                    wallCracked = BpSpec.of("mud_bricks");
                    wallMossy = BpSpec.of("stone_bricks");
                    pillar = BpSpec.of("polished_andesite");
                    trim = BpSpec.of("packed_mud");
                    floorMain = BpSpec.of("mud_bricks");
                    floorAccent = BpSpec.of("mossy_stone_bricks");
                    roofSlab = "mud_brick_slab";
                    roofStairs = "mud_brick_stairs";
                    stoneStairs = "mossy_stone_brick_stairs";
                    stoneWall = "mossy_stone_brick_wall";
                    log = "spruce_log";
                    planks = BpSpec.of("spruce_planks");
                    woodStairs = "spruce_stairs";
                    woodSlab = "spruce_slab";
                    woodFence = "spruce_fence";
                    woodGate = "spruce_fence_gate";
                    woodDoor = "spruce_door";
                    woodTrapdoor = "spruce_trapdoor";
                    sandbag = BpSpec.of("yellow_concrete_powder");
                    yardPath = BpSpec.of("mossy_cobblestone");
                }
                default -> {
                    wallMain = BpSpec.of("stone_bricks");
                    wallCracked = BpSpec.of("cracked_stone_bricks");
                    wallMossy = BpSpec.of("mossy_stone_bricks");
                    pillar = BpSpec.of("polished_andesite");
                    trim = BpSpec.of("smooth_stone");
                    floorMain = BpSpec.of("polished_andesite");
                    floorAccent = BpSpec.of("stone_bricks");
                    roofSlab = "stone_brick_slab";
                    roofStairs = "stone_brick_stairs";
                    stoneStairs = "stone_brick_stairs";
                    stoneWall = "stone_brick_wall";
                    log = "spruce_log";
                    planks = BpSpec.of("spruce_planks");
                    woodStairs = "spruce_stairs";
                    woodSlab = "spruce_slab";
                    woodFence = "spruce_fence";
                    woodGate = "spruce_fence_gate";
                    woodDoor = "spruce_door";
                    woodTrapdoor = "spruce_trapdoor";
                    sandbag = BpSpec.of("yellow_concrete_powder");
                    yardPath = BpSpec.of("cobblestone");
                }
            }
        }

        private void wood(String logB, String plankB, String stairB, String slabB, String fenceB, String gateB, String doorB, String trapB) {
            log = logB;
            planks = BpSpec.of(plankB);
            woodStairs = stairB;
            woodSlab = slabB;
            woodFence = fenceB;
            woodGate = gateB;
            woodDoor = doorB;
            woodTrapdoor = trapB;
        }

        /** Фракция перекрашивает базу: материалы стен, дерево, мешки с песком и цвет флагов. */
        private void applyFaction(Faction f) {
            if (f == null) return;
            switch (f) {
                case IRON_WOLVES -> {
                    wallMain = BpSpec.of("polished_blackstone_bricks");
                    wallCracked = BpSpec.of("cracked_polished_blackstone_bricks");
                    wallMossy = BpSpec.of("blackstone");
                    pillar = BpSpec.of("polished_blackstone");
                    trim = BpSpec.of("chiseled_polished_blackstone");
                    floorMain = BpSpec.of("polished_blackstone");
                    floorAccent = BpSpec.of("blackstone");
                    roofSlab = "polished_blackstone_brick_slab";
                    roofStairs = "polished_blackstone_brick_stairs";
                    stoneStairs = "polished_blackstone_brick_stairs";
                    stoneWall = "polished_blackstone_brick_wall";
                    wood("dark_oak_log", "dark_oak_planks", "dark_oak_stairs", "dark_oak_slab",
                            "dark_oak_fence", "dark_oak_fence_gate", "dark_oak_door", "dark_oak_trapdoor");
                    sandbag = BpSpec.of("gray_concrete_powder");
                    yardPath = BpSpec.of("polished_blackstone");
                    bannerWool = BpSpec.of("black_wool");
                }
                case GLASS_COMPANY -> {
                    wallMain = BpSpec.of("light_gray_concrete");
                    wallCracked = BpSpec.of("gray_concrete");
                    wallMossy = BpSpec.of("stone_bricks");
                    pillar = BpSpec.of("quartz_pillar");
                    trim = BpSpec.of("white_concrete");
                    floorMain = BpSpec.of("smooth_stone");
                    floorAccent = BpSpec.of("gray_concrete");
                    roofSlab = "smooth_quartz_slab";
                    roofStairs = "smooth_quartz_stairs";
                    stoneStairs = "stone_brick_stairs";
                    stoneWall = "stone_brick_wall";
                    wood("birch_log", "birch_planks", "birch_stairs", "birch_slab",
                            "birch_fence", "birch_fence_gate", "birch_door", "birch_trapdoor");
                    sandbag = BpSpec.of("white_concrete_powder");
                    yardPath = BpSpec.of("stone");
                    bannerWool = BpSpec.of("light_blue_wool");
                }
                case FROST_CORPS -> {
                    wallMain = BpSpec.of("stone_bricks");
                    wallCracked = BpSpec.of("cracked_stone_bricks");
                    wallMossy = BpSpec.of("snow_block");
                    pillar = BpSpec.of("polished_andesite");
                    trim = BpSpec.of("smooth_stone");
                    floorMain = BpSpec.of("polished_andesite");
                    floorAccent = BpSpec.of("stone_bricks");
                    roofSlab = "spruce_slab";
                    roofStairs = "spruce_stairs";
                    stoneStairs = "stone_brick_stairs";
                    stoneWall = "stone_brick_wall";
                    sandbag = BpSpec.of("white_concrete_powder");
                    yardPath = BpSpec.of("cobblestone");
                    bannerWool = BpSpec.of("blue_wool");
                }
                case ASH_RAIDERS -> {
                    wallMain = BpSpec.of("brown_terracotta");
                    wallCracked = BpSpec.of("terracotta");
                    wallMossy = BpSpec.of("red_terracotta");
                    pillar = BpSpec.of("acacia_planks");
                    trim = BpSpec.of("orange_terracotta");
                    floorMain = BpSpec.of("smooth_red_sandstone");
                    floorAccent = BpSpec.of("brown_terracotta");
                    roofSlab = "acacia_slab";
                    roofStairs = "acacia_stairs";
                    wood("acacia_log", "acacia_planks", "acacia_stairs", "acacia_slab",
                            "acacia_fence", "acacia_fence_gate", "acacia_door", "acacia_trapdoor");
                    sandbag = BpSpec.of("orange_concrete_powder");
                    yardPath = BpSpec.of("orange_terracotta");
                    bannerWool = BpSpec.of("orange_wool");
                }
                case BLACK_CROWS -> {
                    wallMain = BpSpec.of("dark_oak_planks");
                    wallCracked = BpSpec.of("cobbled_deepslate");
                    wallMossy = BpSpec.of("stripped_dark_oak_log");
                    pillar = BpSpec.of("dark_oak_log");
                    trim = BpSpec.of("cobbled_deepslate");
                    floorMain = BpSpec.of("cobbled_deepslate");
                    floorAccent = BpSpec.of("dark_oak_planks");
                    roofSlab = "dark_oak_slab";
                    roofStairs = "dark_oak_stairs";
                    wood("dark_oak_log", "dark_oak_planks", "dark_oak_stairs", "dark_oak_slab",
                            "dark_oak_fence", "dark_oak_fence_gate", "dark_oak_door", "dark_oak_trapdoor");
                    sandbag = BpSpec.of("gray_concrete_powder");
                    yardPath = BpSpec.of("cobbled_deepslate");
                    bannerWool = BpSpec.of("purple_wool");
                }
                case MARSH_REAVERS -> {
                    wallMain = BpSpec.of("mud_bricks");
                    wallCracked = BpSpec.of("packed_mud");
                    wallMossy = BpSpec.of("moss_block");
                    pillar = BpSpec.of("mangrove_log");
                    trim = BpSpec.of("packed_mud");
                    floorMain = BpSpec.of("mud_bricks");
                    floorAccent = BpSpec.of("packed_mud");
                    roofSlab = "mangrove_slab";
                    roofStairs = "mangrove_stairs";
                    wood("mangrove_log", "mangrove_planks", "mangrove_stairs", "mangrove_slab",
                            "mangrove_fence", "mangrove_fence_gate", "mangrove_door", "mangrove_trapdoor");
                    sandbag = BpSpec.of("green_concrete_powder");
                    yardPath = BpSpec.of("mud_bricks");
                    bannerWool = BpSpec.of("lime_wool");
                }
                default -> bannerWool = BpSpec.of("green_wool");   // REGULAR_ARMY: палитра по местности
            }
        }

    BpSpec masonry(long seed, int x, int y, int z) {
        long h = WarzoneNoise.hash(seed + 101, x * 7 + y, z * 13 - y) & 15;
        if (h == 0 || h == 1) return wallCracked;
        if (h == 2) return wallMossy;
        return wallMain;
    }
}
