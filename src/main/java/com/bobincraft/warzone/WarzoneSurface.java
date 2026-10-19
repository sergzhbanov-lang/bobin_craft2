package com.bobincraft.warzone;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/** Выбор блоков поверхности по типу местности, уклону, воде, дорогам и «шрамам» воронок. */
final class WarzoneSurface {
    private WarzoneSurface() {}

    /** Результат выбора для одной колонки. */
    static final class Column {
        BlockState top;
        BlockState filler;
        int fillerDepth;
        /** true — слои под поверхностью идут полосами терракоты (бесплодные земли). */
        boolean strata;
        /** true — полосы строятся из песчаника/терракоты (останцы пустыни), иначе — терракота меза. */
        boolean desertStrata;
    }

    private static final BlockState GRASS = Blocks.GRASS_BLOCK.getDefaultState();
    private static final BlockState DIRT = Blocks.DIRT.getDefaultState();
    private static final BlockState COARSE = Blocks.COARSE_DIRT.getDefaultState();
    private static final BlockState STONE = Blocks.STONE.getDefaultState();
    private static final BlockState ANDESITE = Blocks.ANDESITE.getDefaultState();
    private static final BlockState COBBLESTONE = Blocks.COBBLESTONE.getDefaultState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.getDefaultState();
    private static final BlockState SAND = Blocks.SAND.getDefaultState();
    private static final BlockState RED_SAND = Blocks.RED_SAND.getDefaultState();
    private static final BlockState CLAY = Blocks.CLAY.getDefaultState();
    private static final BlockState MUD = Blocks.MUD.getDefaultState();
    private static final BlockState SNOW = Blocks.SNOW_BLOCK.getDefaultState();
    private static final BlockState BLACKSTONE = Blocks.BLACKSTONE.getDefaultState();
    private static final BlockState BASALT = Blocks.BASALT.getDefaultState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.getDefaultState();
    private static final BlockState TUFF = Blocks.TUFF.getDefaultState();
    private static final BlockState PODZOL = Blocks.PODZOL.getDefaultState();
    private static final BlockState MOSS = Blocks.MOSS_BLOCK.getDefaultState();
    private static final BlockState SANDSTONE = Blocks.SANDSTONE.getDefaultState();

    private static final BlockState[] DESERT_STRATA = {
            Blocks.SANDSTONE.getDefaultState(),
            Blocks.SANDSTONE.getDefaultState(),
            Blocks.SMOOTH_SANDSTONE.getDefaultState(),
            Blocks.TERRACOTTA.getDefaultState(),
            Blocks.SANDSTONE.getDefaultState(),
            Blocks.ORANGE_TERRACOTTA.getDefaultState(),
            Blocks.CUT_SANDSTONE.getDefaultState(),
            Blocks.SANDSTONE.getDefaultState(),
            Blocks.YELLOW_TERRACOTTA.getDefaultState(),
            Blocks.RED_SANDSTONE.getDefaultState(),
            Blocks.SANDSTONE.getDefaultState(),
            Blocks.WHITE_TERRACOTTA.getDefaultState(),
    };

    private static final BlockState[] STRATA = {
            Blocks.TERRACOTTA.getDefaultState(),
            Blocks.ORANGE_TERRACOTTA.getDefaultState(),
            Blocks.TERRACOTTA.getDefaultState(),
            Blocks.YELLOW_TERRACOTTA.getDefaultState(),
            Blocks.BROWN_TERRACOTTA.getDefaultState(),
            Blocks.TERRACOTTA.getDefaultState(),
            Blocks.RED_TERRACOTTA.getDefaultState(),
            Blocks.ORANGE_TERRACOTTA.getDefaultState(),
            Blocks.TERRACOTTA.getDefaultState(),
            Blocks.WHITE_TERRACOTTA.getDefaultState(),
            Blocks.LIGHT_GRAY_TERRACOTTA.getDefaultState(),
            Blocks.TERRACOTTA.getDefaultState(),
    };

    /** Слой терракоты на высоте y (полосы одинаковы по всему миру, смещение зависит от seed). */
    static BlockState strata(WarzoneTerrain t, int y) {
        int off = (int) (WarzoneNoise.mix(t.seed + 99) & 0xFF);
        return STRATA[Math.floorMod(y + off, STRATA.length)];
    }

    /** Слой выветренного столба пустыни на высоте y. */
    static BlockState desertStrata(WarzoneTerrain t, int y) {
        int off = (int) (WarzoneNoise.mix(t.seed + 98) & 0xFF);
        return DESERT_STRATA[Math.floorMod(y + off, DESERT_STRATA.length)];
    }

    private static final BlockState SPRUCE_PLANKS = Blocks.SPRUCE_PLANKS.getDefaultState();
    private static final BlockState SMOOTH_SANDSTONE = Blocks.SMOOTH_SANDSTONE.getDefaultState();
    private static final BlockState CUT_SANDSTONE = Blocks.CUT_SANDSTONE.getDefaultState();
    private static final BlockState MOSSY_COBBLE = Blocks.MOSSY_COBBLESTONE.getDefaultState();

    /** Скин дорожного полотна по контексту: регион, высота, «главность» дороги (s.road > 0.72). */
    private static void roadSkin(WarzoneTerrain.Sample s, int h, double r, Column out) {
        boolean main = s.road > 0.72;
        out.fillerDepth = 2;
        switch (s.region) {
            case MARSH -> {
                // раскисшая лесная колея: местами уложен настил из досок, между ними грязь и подзол
                out.top = main ? (r < 0.55 ? SPRUCE_PLANKS : (r < 0.80 ? MUD : (r < 0.92 ? PODZOL : COARSE)))
                               : (r < 0.30 ? SPRUCE_PLANKS : (r < 0.70 ? MUD : PODZOL));
                out.filler = MUD;
            }
            case HIGHLANDS -> {
                if (h > 125) {
                    // снежная пыль на перевале: дорога угадывается под снегом
                    out.top = r < 0.55 ? SNOW : (r < 0.80 ? GRAVEL : ANDESITE);
                } else if (h > 95) {
                    out.top = main ? (r < 0.35 ? GRAVEL : (r < 0.65 ? ANDESITE : (r < 0.85 ? STONE : COBBLESTONE)))
                                   : (r < 0.50 ? GRAVEL : (r < 0.80 ? ANDESITE : COARSE));
                } else {
                    out.top = main ? (r < 0.45 ? GRAVEL : (r < 0.80 ? COARSE : (r < 0.92 ? COBBLESTONE : ANDESITE)))
                                   : (r < 0.60 ? COARSE : GRAVEL);
                }
                out.filler = STONE;
            }
            case BADLANDS -> {
                // пустыня и бесплодные земли: утрамбованный песчаник и красный песок
                out.top = main ? (r < 0.40 ? SANDSTONE : (r < 0.70 ? CUT_SANDSTONE : (r < 0.88 ? SMOOTH_SANDSTONE : RED_SAND)))
                               : (r < 0.55 ? SANDSTONE : (r < 0.85 ? RED_SAND : SAND));
                out.filler = SANDSTONE;
            }
            default -> {
                // равнины: военная дорога — булыжник и гравий (главная), грунтовка (второстепенная)
                if (main) {
                    out.top = r < 0.40 ? GRAVEL : (r < 0.70 ? COARSE : (r < 0.84 ? COBBLESTONE : (r < 0.93 ? MOSSY_COBBLE : ANDESITE)));
                } else {
                    out.top = r < 0.65 ? COARSE : GRAVEL;
                }
                out.filler = COARSE;
            }
        }
    }

    static void choose(WarzoneTerrain t, WarzoneTerrain.Sample s, double slope, int x, int z, Column out) {
        if (t.isNatural()) {
            NaturalSurface.choose(t, s, slope, x, z, out);
            return;
        }
        double r = WarzoneNoise.unit(WarzoneNoise.hash(t.seed + 5, x, z));
        int h = s.height;
        out.strata = false;
        out.desertStrata = false;
        out.fillerDepth = 3;

        // ---- под водой
        if (h < WarzoneTerrain.SEA_LEVEL) {
            if (s.region == WarzoneTerrain.Region.MARSH && s.channel < 0.55) {
                // илистое дно: в заводях — глина и грязь, на мелководье — грязь
                out.top = s.pool > 0.4 ? (r < 0.35 ? CLAY : MUD) : MUD;
                out.filler = MUD;
                return;
            }
            if (s.oasis > 0.05) {
                out.top = SAND;
                out.filler = SAND;
                return;
            }
            if (s.channel > 0.55 || h < WarzoneTerrain.SEA_LEVEL - 4) {
                // глубокое дно: у крутых берегов — галька, в глубине — гравий и глина
                out.top = h < WarzoneTerrain.SEA_LEVEL - 9 ? (r < 0.35 ? CLAY : GRAVEL) : (r < 0.25 ? CLAY : (r < 0.55 ? GRAVEL : SAND));
                out.filler = GRAVEL;
            } else if (s.region == WarzoneTerrain.Region.MARSH) {
                out.top = MUD;
                out.filler = MUD;
            } else if (s.region == WarzoneTerrain.Region.BADLANDS) {
                out.top = RED_SAND;
                out.filler = RED_SAND;
            } else {
                out.top = SAND;
                out.filler = SAND;
            }
            return;
        }

        // ---- выветренный останец пустыни: полосы песчаника и терракоты
        if (s.pillar > 0.15) {
            out.strata = true;
            out.desertStrata = true;
            out.fillerDepth = 60;
            out.top = desertStrata(t, h);
            out.filler = SANDSTONE;
            return;
        }

        // ---- горная дорога/тропа к крепости
        if (s.path > 0.5) {
            double rp = WarzoneNoise.unit(WarzoneNoise.hash(t.seed + 21, x, z));
            if (!s.trail) {
                out.top = rp < 0.40 ? GRAVEL : (rp < 0.72 ? COARSE : (rp < 0.90 ? COBBLESTONE : ANDESITE));
                out.filler = COARSE;
                out.fillerDepth = 2;
                return;
            }
            // скрытая тропа: еле заметная, пятнами, выше снеговой линии — под снегом
            if (rp < 0.62) {
                out.top = h > 125 ? (rp < 0.35 ? SNOW : GRAVEL) : (rp < 0.40 ? COARSE : (rp < 0.55 ? DIRT : GRAVEL));
                out.filler = DIRT;
                out.fillerDepth = 2;
                return;
            }
        }

        // ---- военная дорога между базами и аванпостами (суша)
        boolean roadHere = s.road > 0.55 || (s.road > 0.20 && r < s.road);
        if (roadHere && s.roadBroken > 0.0
                && WarzoneNoise.unit(WarzoneNoise.hash(t.seed + 17, x, z)) < s.roadBroken * 1.15) {
            roadHere = false;   // дорога разбита воронкой — полотно рвётся и переходит в выжженную землю
        }
        if (roadHere) {
            roadSkin(s, h, r, out);
            return;
        }

        // ---- берега оазиса: травяное кольцо вокруг воды
        if (s.oasis > 0.1 && s.basePad < 0.4) {
            if (h <= WarzoneTerrain.SEA_LEVEL + 1) { out.top = r < 0.3 ? GRASS : SAND; out.filler = SAND; return; }
            if (s.oasis > 0.45) { out.top = r < 0.85 ? GRASS : COARSE; out.filler = DIRT; return; }
            out.top = r < 0.35 ? GRASS : SAND; out.filler = SAND; out.fillerDepth = 4;
            return;
        }

        // ---- берег (у самой воды)
        if (h <= WarzoneTerrain.SEA_LEVEL + 1 && s.region != WarzoneTerrain.Region.HIGHLANDS && s.basePad < 0.4) {
            switch (s.region) {
                case MARSH -> { out.top = MUD; out.filler = MUD; }
                case BADLANDS -> { out.top = RED_SAND; out.filler = RED_SAND; }
                default -> {
                    out.top = r < 0.12 ? GRAVEL : SAND;
                    out.filler = SAND;
                }
            }
            return;
        }

        // ---- выжженная воронка
        if (s.scar > 0.5) {
            out.top = s.scar > 0.85 && r < 0.4 ? BLACKSTONE : (r < 0.15 ? GRAVEL : COARSE);
            out.filler = COARSE;
            out.fillerDepth = 2;
            return;
        }
        if (s.scar > 0.2 && r < s.scar) {
            out.top = COARSE;
            out.filler = DIRT;
            return;
        }

        switch (s.region) {
            case PLAINS -> {
                if (slope > 1.6) { out.top = r < 0.3 ? ANDESITE : STONE; out.filler = STONE; }
                else if (slope > 1.1 && r < 0.5) { out.top = COARSE; out.filler = DIRT; }
                else if (s.valley > 0.5 && r < 0.2) { out.top = GRAVEL; out.filler = DIRT; }
                else if (r < 0.015) { out.top = PODZOL; out.filler = DIRT; }
                else { out.top = GRASS; out.filler = DIRT; }
            }
            case MARSH -> {
                if (s.basePad > 0.5) { out.top = GRASS; out.filler = DIRT; }
                else if (s.bog > 0.35 && h >= WarzoneTerrain.SEA_LEVEL + 2) {
                    // кочка: мох в центре, трава по склонам, грязь у воды
                    out.top = r < 0.40 ? MOSS : (r < 0.55 ? MUD : GRASS);
                    out.filler = h <= WarzoneTerrain.SEA_LEVEL + 2 ? MUD : DIRT;
                }
                else if (r < 0.35) { out.top = MUD; out.filler = MUD; }
                else { out.top = GRASS; out.filler = DIRT; }
            }
            case BADLANDS -> {
                if (s.desert > 0.55) {
                    // пустыня: песок (на гребнях дюн светлее), красные пятна, под дюнами песок глубже
                    double patch = WarzoneNoise.unit(WarzoneNoise.hash(t.seed + 61, x >> 3, z >> 3));
                    out.top = (patch < 0.18 && r < 0.7) ? RED_SAND : (slope > 1.6 && r < 0.25 ? SANDSTONE : SAND);
                    out.filler = SAND;
                    out.fillerDepth = 4 + (int) Math.min(8, s.dune);
                    return;
                }
                out.strata = true;
                out.fillerDepth = 26;
                if (slope < 0.8 && r < 0.85) { out.top = RED_SAND; }
                else { out.top = strata(t, h); }
                out.filler = STONE; // не используется при strata = true
            }
            case HIGHLANDS -> highlandTop(t, s, slope, h, r, out);
        }
    }

    private static void highlandTop(WarzoneTerrain t, WarzoneTerrain.Sample s, double slope, int h, double r, Column out) {
        out.filler = STONE;
        out.fillerDepth = 2;
        // Скальное кольцо по краю арены — тёмная порода
        if (s.edge < WarzoneTerrain.RIM_WIDTH * 0.55) {
            out.top = r < 0.4 ? BLACKSTONE : (r < 0.65 ? BASALT : (r < 0.85 ? DEEPSLATE : STONE));
            out.filler = r < 0.5 ? BLACKSTONE : DEEPSLATE;
            out.fillerDepth = 4;
            if (h > 140 + (int) (r * 10)) out.top = SNOW;
            return;
        }
        if (h > 128 + (int) (r * 12)) { out.top = SNOW; out.filler = STONE; return; }
        if (slope < 0.9 && h < 105 && r < 0.6) { out.top = GRASS; out.filler = DIRT; out.fillerDepth = 3; return; }
        if (slope > 1.4) { out.top = r < 0.5 ? STONE : (r < 0.75 ? ANDESITE : TUFF); return; }
        out.top = r < 0.45 ? STONE : (r < 0.7 ? ANDESITE : (r < 0.88 ? GRAVEL : COARSE));
    }
}
