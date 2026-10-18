package com.bobincraft.warzone;

import com.bobincraft.warzone.BpBuilder.Dir;
import com.bobincraft.warzone.SiteDesign.Place;

/**
 * Строит базу целиком одним чертежом ({@link Blueprint}) по раскладке {@link SiteDesign}: расчистка и покрытие двора,
 * ограда (каменная стена / частокол / мешки с проволокой, с воротами и разрушениями), модули ({@link SiteModules}),
 * снежная пыль на горных крепостях. Затем соединения заборов и стен, фундамент до реального рельефа и удаление
 * всего, что не связано с землёй. Вся логика без Minecraft — проверяется headless.
 */
final class SiteBuilder {
    private SiteBuilder() {}

    static final int CLEAR_TOP = 18;

    static long designSeed(long terrainSeed, int siteId) {
        return WarzoneNoise.mix(terrainSeed ^ (siteId * 0x9E3779B97F4A7C15L));
    }

    static SiteDesign designFor(long terrainSeed, WarzoneBasePlan.Site s) {
        return SiteDesign.design(s.type, s.faction, s.region, s.variant, designSeed(terrainSeed, s.id));
    }

    static Blueprint build(WarzoneTerrain t, WarzoneBasePlan.Site site) {
        SiteDesign d = designFor(t.seed, site);
        BpPalette pal = new BpPalette(site.region, site.faction);
        int H = d.half;
        // нижняя граница чертежа: фундамент должен дотянуться до земли даже на крутом склоне (до 40 блоков вниз)
        int low = site.baseY - 6;
        for (int x = site.cx - H; x <= site.cx + H; x += 2) {
            for (int z = site.cz - H; z <= site.cz + H; z += 2) {
                low = Math.min(low, Math.max(site.baseY - 40, t.height(x, z) - 1));
            }
        }
        low = Math.max(low, 1);
        int top = site.baseY + 22;
        Blueprint bp = new Blueprint("site#" + site.id + " " + site.type, site.cx - H, low, site.cz - H,
                2 * H + 1, top - low + 1, 2 * H + 1);
        BpBuilder b = new BpBuilder(t, bp, pal, site.cx, site.baseY, site.cz, site.rotation);

        ground(b, d, pal, site.region);
        if (d.fenced) perimeter(b, d, pal);
        plaza(b, d, pal);
        for (Place p : d.places) {
            if (p.mod == SiteDesign.Mod.TOWER) SiteModules.build(b, p);
        }
        for (Place p : d.places) {
            if (p.mod != SiteDesign.Mod.TOWER) SiteModules.build(b, p);
        }
        if (site.type == WarzoneBasePlan.SiteType.MOUNTAIN_FORT && site.baseY >= WarzoneBasePlan.SNOW_FORT_MIN_Y - 16) {
            snowDust(b, d);
        }

        Blueprint.Ground g = (x, z) -> t.height(x, z);
        bp.addFoundations(g, pal.wallMain, 40);
        bp.connect(g);
        bp.pruneFloating(g);
        return bp;
    }

    // ------------------------------------------------------------------ двор

    private static boolean isLane(SiteDesign d, int u, int v) {
        if ((d.gates & 1) != 0 && Math.abs(u) <= 1 && v >= 0) return true;
        if ((d.gates & 2) != 0 && Math.abs(u) <= 1 && v <= 0) return true;
        if ((d.gates & 4) != 0 && Math.abs(v) <= 1 && u <= 0) return true;
        if ((d.gates & 8) != 0 && Math.abs(v) <= 1 && u >= 0) return true;
        return false;
    }

    private static void ground(BpBuilder b, SiteDesign d, BpPalette p, WarzoneTerrain.Region region) {
        int h = d.half - 1;
        for (int u = -h; u <= h; u++) {
            for (int v = -h; v <= h; v++) {
                if (d.circular && Math.hypot(u, v) > h) continue;
                for (int dy = 1; dy <= CLEAR_TOP; dy++) b.air(u, dy, v);
                int x = b.wx(u, v), z = b.wz(u, v);
                int top = b.terrain.height(x, z);
                if (Math.abs(top - b.baseY) > 1) {
                    // внутри ограды, где рельеф ушёл вниз (гребень, обрыв), двор всё равно настилается: фундамент доведёт его до земли
                    boolean inside = d.circular ? Math.hypot(u, v) <= d.R : Math.max(Math.abs(u), Math.abs(v)) <= d.R;
                    if (!(top < b.baseY && d.fenced && inside)) continue;
                }
                int r = (int) (WarzoneNoise.hash(b.terrain.seed + 303, x, z) & 7);
                BpSpec g;
                if (isLane(d, u, v) && d.fenced) {
                    g = r == 0 ? BpSpec.of("gravel") : ((u + v) & 1) == 0 ? p.floorMain : p.yardPath;
                } else if (region == WarzoneTerrain.Region.BADLANDS) {
                    g = r <= 3 ? BpSpec.of("terracotta") : (r <= 5 ? BpSpec.of("coarse_dirt") : (r == 6 ? BpSpec.of("red_sand") : p.yardPath));
                } else if (region == WarzoneTerrain.Region.MARSH) {
                    g = r <= 2 ? BpSpec.of("mud") : (r <= 4 ? BpSpec.of("packed_mud") : (r <= 6 ? BpSpec.of("coarse_dirt") : p.yardPath));
                } else {
                    g = switch (d.yardKind) {
                        case 0 -> r <= 2 ? BpSpec.of("coarse_dirt") : (r <= 4 ? BpSpec.of("gravel") : (r == 5 ? p.yardPath : BpSpec.of("andesite")));
                        case 1 -> r <= 3 ? BpSpec.of("cobblestone") : (r <= 5 ? BpSpec.of("gravel") : (r == 6 ? BpSpec.of("mossy_cobblestone") : BpSpec.of("andesite")));
                        case 2 -> r <= 3 ? BpSpec.of("coarse_dirt") : (r <= 5 ? BpSpec.of("podzol") : (r == 6 ? BpSpec.of("gravel") : BpSpec.of("dirt_path")));
                        default -> r <= 2 ? p.floorMain : (r <= 4 ? BpSpec.of("gravel") : (r == 5 ? BpSpec.of("coarse_dirt") : p.floorAccent));
                    };
                }
                b.set(u, 0, v, g);
            }
        }
    }

    /** Центральная площадь (если центр не занят модулем): флагшток, колонна, колодец или жаровня. */
    private static void plaza(BpBuilder b, SiteDesign d, BpPalette p) {
        if (d.type == WarzoneBasePlan.SiteType.WATCHTOWER) return;
        for (Place pl : d.places) {
            int[] r = pl.rect();
            if (r[0] <= 0 && r[2] >= 0 && r[1] <= 0 && r[3] >= 0) return;
        }
        for (int u = -2; u <= 2; u++) for (int v = -2; v <= 2; v++) b.set(u, 0, v, ((u + v) & 1) == 0 ? p.floorAccent : p.floorMain);
        switch (d.plazaKind) {
            case 0 -> {   // флагшток
                b.set(0, 1, 0, p.pillar);
                b.set(0, 2, 0, p.pillar);
                for (int dy = 3; dy <= 8; dy++) b.set(0, dy, 0, b.fenceAlong(p.woodFence, true));
                b.lantern(0, 9, 0, false);
                for (int dy = 5; dy <= 7; dy++) { b.set(1, dy, 0, p.bannerWool); b.set(2, dy, 0, p.bannerWool); }
            }
            case 1 -> {   // колонна с вымпелом
                for (int dy = 1; dy <= 5; dy++) b.set(0, dy, 0, dy == 1 ? p.trim : p.pillar);
                b.set(0, 6, 0, b.slab(p.roofSlab, "bottom"));
                b.set(1, 5, 0, p.bannerWool);
                b.set(-1, 5, 0, p.bannerWool);
            }
            case 2 -> {   // колодец
                for (int u = -1; u <= 1; u++) for (int v = -1; v <= 1; v++) {
                    if (u == 0 && v == 0) b.set(u, 1, v, BpSpec.of("cauldron"));
                    else b.set(u, 1, v, p.wallMain);
                }
                b.set(0, 2, 0, BpSpec.of("air"));
            }
            default -> {  // жаровня и скамьи
                b.set(0, 0, 0, BpSpec.of("cobblestone"));
                b.campfire(0, 1, 0, true);
                b.set(-2, 1, 0, b.logHorizontal(false));
                b.set(2, 1, 0, b.logHorizontal(false));
                b.set(0, 1, 2, b.logHorizontal(true));
            }
        }
    }

    // ------------------------------------------------------------------ ограда

    private static void perimeter(BpBuilder b, SiteDesign d, BpPalette p) {
        int R = d.R;
        for (int u = -R - 1; u <= R + 1; u++) {
            for (int v = -R - 1; v <= R + 1; v++) {
                boolean ring;
                if (d.circular) {
                    double dist = Math.hypot(u, v);
                    ring = dist >= R - 0.5 && dist < R + 0.6;
                } else {
                    ring = Math.max(Math.abs(u), Math.abs(v)) == R;
                }
                if (!ring) continue;
                if (d.isGate(u, v)) {
                    gateCell(b, d, p, u, v);
                    continue;
                }
                // координата вдоль стены и расстояние до ближайших ворот
                int coord = (Math.abs(v) >= Math.abs(u)) ? u : v;
                int along = Math.abs(coord);
                int segKey = d.circular ? (int) Math.round(Math.atan2(u, v) * R / 6.0) : (Math.floorDiv(u + 64, 6) * 211 + Math.floorDiv(v + 64, 6) * 17);
                wallCell(b, d, p, u, v, coord, along, segKey);
            }
        }
    }

    /** Уровень, до которого стена разрушена в данной точке (0 — целая). */
    private static int ruinCut(BpBuilder b, SiteDesign d, int u, int v, int segKey, int H) {
        if (d.ruin <= 0) return 0;
        int x = b.wx(u, v), z = b.wz(u, v);
        long h = WarzoneNoise.hash(b.terrain.seed + 8800 + segKey, 0, 0);
        if (WarzoneNoise.unit(h) * 100.0 >= d.ruin * 0.6) return 0;
        // внутри разрушенного сегмента высота обломка зависит от клетки
        int stump = 1 + (int) (WarzoneNoise.hash(b.terrain.seed + 8801, x, z) % Math.max(2, H - 1));
        return stump;
    }

    private static void wallCell(BpBuilder b, SiteDesign d, BpPalette p, int u, int v, int coord, int along, int segKey) {
        int H = d.wallH;
        int cut = ruinCut(b, d, u, v, segKey, H);
        int x = b.wx(u, v), z = b.wz(u, v);
        switch (d.wallStyle) {
            case SiteDesign.W_STONE -> {
                int top = cut > 0 ? cut : H + 1;
                boolean post = Math.floorMod(coord, 6) == 0;
                for (int dy = 1; dy <= top; dy++) {
                    if (cut > 0) { b.setMasonry(u, dy, v); continue; }
                    if (post && dy <= H) b.set(u, dy, v, p.pillar);
                    else if (dy == 2 && Math.floorMod(coord, 4) == 2 && H >= 3) b.set(u, dy, v, b.paneAlong("iron_bars", Math.abs(v) >= Math.abs(u)));
                    else if (dy <= H - 1) b.setMasonry(u, dy, v);
                    else if (dy == H) {
                        if ((coord & 1) == 0) b.setMasonry(u, dy, v);
                        else b.set(u, dy, v, b.wall(p.stoneWall));
                    } else if ((coord & 1) == 0 || post) {
                        b.set(u, dy, v, b.slab(p.roofSlab, "bottom"));
                    }
                }
                if (cut == 0 && post) b.lantern(u, H + 2, v, false);
                if (cut > 0 && cut < H && (WarzoneNoise.hash(b.terrain.seed + 8802, x, z) & 3) == 0) {
                    b.set(u, cut + 1, v, b.stair(p.stoneStairs, Dir.NORTH, false));
                }
            }
            case SiteDesign.W_STOCKADE -> {
                int jag = (int) (WarzoneNoise.hash(b.terrain.seed + 8810, x, z) % 3) - 1;
                int top = cut > 0 ? cut : Math.max(2, H + jag);
                for (int dy = 1; dy <= top; dy++) b.set(u, dy, v, b.logVertical());
                if (cut == 0 && Math.floorMod(coord, 5) == 0) {
                    b.set(u, top + 1, v, b.fenceAlong(p.woodFence, Math.abs(v) >= Math.abs(u)));
                    b.lantern(u, top + 2, v, false);
                }
            }
            default -> {   // SANDBAG
                int top = cut > 0 ? Math.min(cut, 2) : 2;
                boolean post = Math.floorMod(coord, 6) == 0;
                for (int dy = 1; dy <= top; dy++) b.set(u, dy, v, post ? BpSpec.of("white_concrete") : p.sandbag);
                if (cut == 0) {
                    if (post) b.set(u, 3, v, BpSpec.of("white_concrete"));
                    else b.set(u, 3, v, (coord & 1) == 0 ? BpSpec.of("iron_bars") : BpSpec.of("cobweb"));
                }
            }
        }
    }

    private static void gateCell(BpBuilder b, SiteDesign d, BpPalette p, int u, int v) {
        int H = d.wallH;
        boolean alongU = Math.abs(v) >= Math.abs(u);
        int coord = alongU ? u : v;
        b.set(u, 0, v, p.floorMain);
        int a = Math.abs(coord);
        if (a == 2 && d.wallStyle != SiteDesign.W_SANDBAG) {
            // створки-колонны по краям проёма
            for (int dy = 1; dy <= H + 1; dy++) b.set(u, dy, v, d.wallStyle == SiteDesign.W_STONE ? p.pillar : b.logVertical());
            b.lantern(u, H + 2, v, false);
        } else if (a == 2) {
            for (int dy = 1; dy <= 3; dy++) b.set(u, dy, v, BpSpec.of("white_concrete"));
        } else if (d.wallStyle == SiteDesign.W_STONE) {
            if (a <= 1) b.set(u, H + 1, v, b.slab(p.roofSlab, "bottom"));
        } else if (d.wallStyle == SiteDesign.W_STOCKADE) {
            if (a <= 1) b.set(u, H + 1, v, b.logHorizontal(alongU));
        }
    }

    // ------------------------------------------------------------------ снег

    private static void snowDust(BpBuilder b, SiteDesign d) {
        Blueprint bp = b.bp;
        BpSpec snow = BpSpec.of("snow").with("layers", "1");
        for (int x = bp.minX; x <= bp.maxX(); x++) {
            for (int z = bp.minZ; z <= bp.maxZ(); z++) {
                if ((WarzoneNoise.hash(b.terrain.seed + 4242, x, z) & 7) == 0) continue;
                for (int y = bp.maxY(); y >= bp.minY; y--) {
                    BpSpec s = bp.get(x, y, z);
                    if (s == null || s.isAir()) continue;
                    if (s.isFullBlock() && !s.id.contains("snow") && !s.id.equals("grass_block")) {
                        BpSpec above = bp.get(x, y + 1, z);
                        if ((above == null || above.isAir()) && bp.inBounds(x, y + 1, z)) bp.set(x, y + 1, z, snow);
                    }
                    break;
                }
            }
        }
    }
}
