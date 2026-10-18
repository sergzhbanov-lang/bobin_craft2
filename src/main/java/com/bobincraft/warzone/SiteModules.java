package com.bobincraft.warzone;

import com.bobincraft.warzone.BpBuilder.Dir;
import com.bobincraft.warzone.SiteDesign.Mod;
import com.bobincraft.warzone.SiteDesign.Place;

/**
 * Модули баз: каждое здание/укрепление строится в СВОЕЙ локальной системе (центр модуля = (0,0), дверь/фронт на +V),
 * поворот и положение задаёт {@link BpBuilder#child}. Высоты согласованы с {@link SiteDesign}: пол здания — dy0,
 * ноги стоящего — dy1; крыша штаба — ноги на dy6, ДОТа — на dy5, платформа башни — на dy8 (деревянной — на dy7).
 * Лестницы: +1 блок на шаг и 3 блока воздуха над каждой ступенью (их пересчитывает headless-проверка проходимости).
 */
final class SiteModules {
    private SiteModules() {}

    static BpSpec s(String id) {
        return BpSpec.of(id);
    }

    static void build(BpBuilder site, Place p) {
        BpBuilder b = site.child(p.u, p.v, p.face);
        switch (p.mod) {
            case TOWER -> tower(b, p.style, p.salt);
            case BARRACKS -> barracks(b, p);
            case HQ -> hq(b, p);
            case DEPOT -> depot(b, p);
            case MESS -> mess(b, p);
            case WORKSHOP -> workshop(b, p);
            case ARMORY -> armory(b, p);
            case HOSPITAL -> hospital(b, p);
            case PILLBOX -> pillbox(b, p);
            case MORTAR -> mortar(b, p);
            case RADIO -> radio(b, p);
            case HELIPAD -> helipad(b);
            case TOTEM -> totem(b, p);
            case CABIN -> cabin(b, p);
            case TENT -> tent(b, 2, 2, p.salt, false);
            case CAMPFIRE -> campfire(b, p);
            case CAGE -> cage(b, p);
            case FUEL -> fuel(b, p);
            case SANDBAG -> sandbag(b, p);
            case TANKTRAP -> tankTrap(b, p);
            case WIRE -> wire(b, p);
            case TRENCH -> trench(b, p);
            case CRATES -> crates(b, p);
        }
    }

    // ================================================================== общие части

    /** Пол здания: шахматка из двух материалов палитры. */
    static void floor(BpBuilder b, int hw, int hd) {
        for (int u = -hw; u <= hw; u++)
            for (int v = -hd; v <= hd; v++) b.set(u, 0, v, ((u + v) & 1) == 0 ? b.pal.floorMain : b.pal.floorAccent);
    }

    static String bedId(BpBuilder b) {
        return b.pal.bannerWool.id.replace("_wool", "_bed");
    }

    /** Кровать: голова в (u,v), ноги в (u,v+1), смотрит в сторону −V. */
    static void bed(BpBuilder b, int u, int dy, int v, String id) {
        String f = b.dir(Dir.NORTH).id;
        b.set(u, dy, v, s(id).with("facing", f).with("part", "head").with("occupied", false));
        b.set(u, dy, v + 1, s(id).with("facing", f).with("part", "foot").with("occupied", false));
    }

    /**
     * Коробка здания: пол, стены высотой H, угловые столбы, окна, дверь на +V по центру и крыша.
     * roof: 0 — двускатная вдоль U, 1 — плоская с зубчатым парапетом, 2 — плоская плитами со свесом.
     * Возвращает высоту потолка над полом (dy, где висит фонарь).
     */
    static int shell(BpBuilder b, int hw, int hd, int H, int roof, boolean iron, boolean woodFrame) {
        BpPalette p = b.pal;
        floor(b, hw, hd);
        for (int u = -hw; u <= hw; u++) {
            for (int v = -hd; v <= hd; v++) {
                boolean eu = Math.abs(u) == hw, ev = Math.abs(v) == hd;
                if (!eu && !ev) continue;
                if (eu && ev) {
                    for (int dy = 1; dy <= H; dy++) b.set(u, dy, v, woodFrame ? b.logVertical() : p.pillar);
                    continue;
                }
                for (int dy = 1; dy <= H; dy++) b.setMasonry(u, dy, v);
                // окна
                boolean win;
                if (ev) win = Math.floorMod(u + hw, 3) == 1;
                else win = Math.floorMod(v + hd, 3) == 1;
                if (win && H >= 3) {
                    b.set(u, 2, v, iron ? b.paneAlong("iron_bars", ev) : b.paneAlong("glass_pane", ev));
                }
            }
        }
        b.door(0, 1, hd, Dir.NORTH, "left");
        b.set(0, 0, hd + 1, p.floorMain);
        switch (roof) {
            case 0 -> {
                for (int u = -hw - 1; u <= hw + 1; u++) {
                    b.set(u, H, hd + 1, b.stair(p.woodStairs, Dir.NORTH, false));
                    b.set(u, H, -hd - 1, b.stair(p.woodStairs, Dir.SOUTH, false));
                    for (int k = 1; k <= hd; k++) {
                        int dy = H + (hd - k) + 1;
                        b.set(u, dy, k, b.stair(p.woodStairs, Dir.NORTH, false));
                        b.set(u, dy, -k, b.stair(p.woodStairs, Dir.SOUTH, false));
                    }
                    b.set(u, H + hd + 1, 0, p.planks);
                }
                for (int su = -hw; su <= hw; su += 2 * hw) {
                    for (int v = -hd + 1; v <= hd - 1; v++)
                        for (int dy = H + 1; dy <= H + hd - Math.abs(v); dy++) b.setMasonry(su, dy, v);
                }
                return H + hd;
            }
            case 1 -> {
                for (int u = -hw; u <= hw; u++)
                    for (int v = -hd; v <= hd; v++) b.set(u, H + 1, v, ((u + v) & 1) == 0 ? p.floorAccent : p.floorMain);
                for (int u = -hw; u <= hw; u++) {
                    for (int v = -hd; v <= hd; v++) {
                        if (Math.abs(u) != hw && Math.abs(v) != hd) continue;
                        if (((u + v) & 1) == 0) b.setMasonry(u, H + 2, v);
                    }
                }
                return H;
            }
            default -> {
                for (int u = -hw - 1; u <= hw + 1; u++) {
                    for (int v = -hd - 1; v <= hd + 1; v++) {
                        boolean outer = Math.abs(u) == hw + 1 || Math.abs(v) == hd + 1;
                        b.set(u, H + 1, v, b.slab(p.roofSlab, outer ? "bottom" : "top"));
                    }
                }
                return H;
            }
        }
    }

    /** Наружная лестница на плоскую крышу вдоль стены u = hw+1 (идёт к −V), steps = число ступеней. */
    static void roofStair(BpBuilder b, int hw, int vStart, int steps) {
        for (int k = 1; k <= steps; k++) {
            int v = vStart - (k - 1);
            for (int y = 1; y < k; y++) b.set(hw + 1, y, v, b.pal.pillar);
            b.set(hw + 1, k, v, b.stair(b.pal.stoneStairs, Dir.NORTH, false));
        }
        b.set(hw + 1, 0, vStart + 1, b.pal.floorMain);
    }

    // ================================================================== башни

    static void tower(BpBuilder b, int style, int salt) {
        if (style == SiteDesign.T_TIMBER) towerTimber(b, salt);
        else towerStone(b, style, salt);
    }

    private static final int[][] STAIR_PATH = {{-2, 1}, {-2, 0}, {-2, -1}, {-2, -2}, {-1, -2}, {0, -2}};

    private static void towerStone(BpBuilder b, int style, int salt) {
        BpPalette p = b.pal;
        boolean drum = style == SiteDesign.T_DRUM;
        for (int u = -3; u <= 3; u++) {
            for (int v = -3; v <= 3; v++) {
                if (drum && Math.abs(u) == 3 && Math.abs(v) == 3) continue;
                boolean ring = Math.abs(u) == 3 || Math.abs(v) == 3;
                b.set(u, 0, v, ring ? p.floorAccent : p.planks);
                if (!ring) continue;
                boolean corner = Math.abs(u) == 3 && Math.abs(v) == 3;
                for (int dy = 1; dy <= 6; dy++) {
                    if (corner) b.set(u, dy, v, p.pillar);
                    else if (dy == 3 && (u == 0 || v == 0)) b.set(u, dy, v, b.paneAlong("iron_bars", Math.abs(v) == 3));
                    else b.setMasonry(u, dy, v);
                }
            }
        }
        // дверь на +V
        b.door(0, 1, 3, Dir.NORTH, "left");
        b.set(0, 0, 4, p.floorMain);
        // внутренняя лестница: ступени k=1..6 по западной и северной внутренним стенам
        for (int k = 1; k <= STAIR_PATH.length; k++) {
            int u = STAIR_PATH[k - 1][0], v = STAIR_PATH[k - 1][1];
            for (int y = 1; y < k; y++) b.set(u, y, v, p.pillar);
            Dir f = k <= 4 ? Dir.NORTH : Dir.EAST;
            b.set(u, k, v, b.stair(p.stoneStairs, f, false));
        }
        // пол площадки на dy7, кроме клеток над ступенями k>=4 (им нужен воздух над головой)
        for (int u = -3; u <= 3; u++) {
            for (int v = -3; v <= 3; v++) {
                if (drum && Math.abs(u) == 3 && Math.abs(v) == 3) continue;
                boolean hole = false;
                for (int k = 4; k <= 6; k++) if (STAIR_PATH[k - 1][0] == u && STAIR_PATH[k - 1][1] == v) hole = true;
                if (hole) continue;
                b.set(u, 7, v, Math.abs(u) == 3 || Math.abs(v) == 3 ? p.trim : p.planks);
            }
        }
        // парапет с зубцами
        for (int u = -3; u <= 3; u++) {
            for (int v = -3; v <= 3; v++) {
                if (Math.abs(u) != 3 && Math.abs(v) != 3) continue;
                if (drum && Math.abs(u) == 3 && Math.abs(v) == 3) continue;
                b.setMasonry(u, 8, v);
                if (((u + v) & 1) == 0) b.setMasonry(u, 9, v);
            }
        }
        if (!drum) {
            for (int sx = -3; sx <= 3; sx += 6) for (int sz = -3; sz <= 3; sz += 6) b.set(sx, 10, sz, p.pillar);
        }
        b.lantern(0, 6, 0, true);
        b.barrel(2, 1, 2, Dir.UP, BpBuilder.LOOT_SUPPLY, salt * 11 + 1);
        b.chest(2, 8, -2, Dir.WEST, BpBuilder.LOOT_ARMORY, salt * 17 + 3);
        // флаг на мачте в углу площадки
        b.set(-2, 8, 2, b.fenceAlong(p.woodFence, true));
        b.set(-2, 9, 2, b.fenceAlong(p.woodFence, true));
        b.set(-2, 10, 2, b.fenceAlong(p.woodFence, true));
        b.set(-1, 10, 2, p.bannerWool);
        b.set(-1, 9, 2, p.bannerWool);
        if (style == SiteDesign.T_PAGODA) {
            // двухъярусная крыша: над площадкой 3 блока воздуха (dy9..11? — стойки до dy10, плиты на dy11)
            for (int sx = -2; sx <= 2; sx += 4) for (int sz = -2; sz <= 2; sz += 4)
                for (int dy = 8; dy <= 10; dy++) b.set(sx, dy, sz, b.pal.pillar);
            for (int u = -4; u <= 4; u++) {
                for (int v = -4; v <= 4; v++) {
                    boolean outer = Math.abs(u) == 4 || Math.abs(v) == 4;
                    if (Math.abs(u) == 4 && Math.abs(v) == 4) continue;
                    b.set(u, 11, v, b.slab(p.roofSlab, outer ? "bottom" : "top"));
                }
            }
            for (int u = -2; u <= 2; u++) for (int v = -2; v <= 2; v++) b.set(u, 12, v, b.slab(p.roofSlab, "bottom"));
            b.set(0, 13, 0, p.pillar);
            b.set(0, 14, 0, s("lightning_rod").with("facing", "up").with("powered", false).with("waterlogged", false));
        }
    }

    private static void towerTimber(BpBuilder b, int salt) {
        BpPalette p = b.pal;
        for (int u = -2; u <= 2; u++) {
            for (int v = -2; v <= 2; v++) {
                boolean corner = Math.abs(u) == 2 && Math.abs(v) == 2;
                if (corner) for (int dy = 1; dy <= 9; dy++) b.set(u, dy, v, b.logVertical());
                else b.set(u, 6, v, p.planks);
            }
        }
        // перекладины между сваями
        for (int i = -1; i <= 1; i++) {
            b.set(i, 3, 2, b.logHorizontal(true));
            b.set(i, 3, -2, b.logHorizontal(true));
            b.set(2, 3, i, b.logHorizontal(false));
            b.set(-2, 3, i, b.logHorizontal(false));
        }
        // перила (вход с лестницы на +U в клетке v=-1)
        for (int i = -1; i <= 1; i++) {
            if (i != -1) b.set(2, 7, i, b.fenceAlong(p.woodFence, false));
            b.set(-2, 7, i, b.fenceAlong(p.woodFence, false));
            b.set(i, 7, 2, b.fenceAlong(p.woodFence, true));
            b.set(i, 7, -2, b.fenceAlong(p.woodFence, true));
        }
        // лестница вдоль восточной стороны: k=1..5 на v=3..-1, выход на настил в (2,-1)
        for (int k = 1; k <= 5; k++) {
            int v = 4 - k;
            for (int y = 1; y < k; y++) b.set(3, y, v, b.logVertical());
            b.set(3, k, v, b.stair(p.stoneStairs, Dir.NORTH, false));
        }
        b.set(3, 0, 4, p.floorMain);
        // крыша на dy10 (над настилом 3 блока воздуха: 7, 8, 9)
        for (int u = -3; u <= 3; u++) {
            for (int v = -3; v <= 3; v++) {
                boolean outer = Math.abs(u) == 3 || Math.abs(v) == 3;
                b.set(u, 10, v, b.slab(p.woodSlab, outer ? "bottom" : "top"));
            }
        }
        b.set(0, 11, 0, p.pillar);
        b.set(1, 11, 0, p.bannerWool);
        b.lantern(0, 9, 0, true);
        b.chest(-1, 7, -1, Dir.SOUTH, BpBuilder.LOOT_ARMORY, salt * 17 + 3);
        b.barrel(1, 7, 1, Dir.UP, BpBuilder.LOOT_SUPPLY, salt * 11 + 1);
    }

    // ================================================================== здания

    static void barracks(BpBuilder b, Place p) {
        int hw = p.w / 2, hd = p.d / 2;
        boolean flat = p.style == 1;
        int ceil = shell(b, hw, hd, 3, flat ? 1 : 0, true, false);
        String bedId = bedId(b);
        for (int u = -hw + 1; u <= hw - 1; u += 2) bed(b, u, 1, -hd + 1, bedId);
        b.barrel(-hw + 1, 1, hd - 1, Dir.UP, BpBuilder.LOOT_SUPPLY, p.salt);
        b.chest(hw - 1, 1, hd - 1, Dir.WEST, BpBuilder.LOOT_SUPPLY, p.salt + 1);
        b.lantern(0, ceil, 0, true);
        b.lantern(-hw + 2, ceil, 0, true);
        b.lantern(hw - 2, ceil, 0, true);
    }

    static void hq(BpBuilder b, Place p) {
        int hw = p.w / 2, hd = p.d / 2;
        BpPalette pal = b.pal;
        shell(b, hw, hd, 4, 1, p.style == 1, false);
        // ступени к крыше вдоль восточной стены (4 шага) и проход через парапет
        roofStair(b, hw, 2, 4);
        b.air(hw, 6, -1);
        // интерьер: карта, рация, сейф командира, флаги
        for (int u = -hw + 1; u <= hw - 1; u++) b.set(u, 0, 0, s("gray_concrete"));
        b.set(-hw + 2, 1, -hd + 1, s("cartography_table"));
        b.set(-hw + 3, 1, -hd + 1, s("lectern").with("facing", b.dir(Dir.SOUTH).id).with("has_book", false).with("powered", false));
        b.set(-hw + 4, 1, -hd + 1, s("crafting_table"));
        b.set(hw - 2, 1, -hd + 1, s("note_block"));
        b.set(hw - 3, 1, -hd + 1, s("jukebox"));
        b.chest(hw - 1, 1, -hd + 1, Dir.SOUTH, BpBuilder.LOOT_COMMAND, p.salt);
        b.barrel(-hw + 1, 1, -hd + 1, Dir.UP, BpBuilder.LOOT_SUPPLY, p.salt + 1);
        for (int i = -1; i <= 1; i++) {
            b.set(i, 3, -hd, pal.bannerWool);
            b.set(i, 2, -hd, pal.bannerWool);
        }
        b.set(0, 1, -2, s("smooth_stone_slab").with("type", "bottom").with("waterlogged", false));
        b.lantern(0, 4, 0, true);
        b.lantern(-hw + 2, 4, 1, true);
        b.lantern(hw - 2, 4, 1, true);
        // радиоантенна на крыше
        b.set(hw - 1, 6, -hd + 1, s("iron_bars"));
        b.set(hw - 1, 7, -hd + 1, s("iron_bars"));
        b.set(hw - 1, 8, -hd + 1, s("lightning_rod").with("facing", "up").with("powered", false).with("waterlogged", false));
    }

    static void depot(BpBuilder b, Place p) {
        int hw = p.w / 2, hd = p.d / 2;
        int ceil = shell(b, hw, hd, 3, 2, false, true);
        for (int u = -hw + 1; u <= hw - 1; u += 2) {
            for (int row = 0; row < 2; row++) {
                int v = -hd + 1 + row * 1;
                long h = WarzoneNoise.hash(b.terrain.seed + p.salt, b.wx(u, v), b.wz(u, v)) & 3;
                if (h == 0) b.chest(u, 1, v, Dir.SOUTH, row == 0 && u == -hw + 1 ? BpBuilder.LOOT_ARMORY : BpBuilder.LOOT_SUPPLY, p.salt + u);
                else if (h == 1) b.set(u, 1, v, s("hay_block").with("axis", "y"));
                else b.barrel(u, 1, v, Dir.UP, BpBuilder.LOOT_SUPPLY, p.salt + u + 7);
                if (h == 3) b.set(u, 2, v, s("hay_block").with("axis", "y"));
            }
        }
        b.set(-hw + 1, 1, hd - 1, s("composter").with("level", "3"));
        b.set(hw - 1, 1, hd - 1, s("hay_block").with("axis", "y"));
        b.lantern(0, ceil, 0, true);
    }

    static void mess(BpBuilder b, Place p) {
        int hw = p.w / 2, hd = p.d / 2;
        BpPalette pal = b.pal;
        int ceil = shell(b, hw, hd, 3, p.salt % 2 == 0 ? 0 : 1, false, true);
        String f = b.dir(Dir.SOUTH).id;
        b.set(-hw + 1, 1, -hd + 1, s("smoker").with("facing", f).with("lit", false));
        b.set(-hw + 2, 1, -hd + 1, s("furnace").with("facing", f).with("lit", false));
        b.set(-hw + 3, 1, -hd + 1, s("cauldron"));
        b.barrel(hw - 1, 1, -hd + 1, Dir.UP, BpBuilder.LOOT_CIVILIAN, p.salt);
        // длинный стол и скамьи
        for (int u = -1; u <= 1; u++) {
            b.set(u, 1, 0, b.slab(pal.woodSlab, "bottom"));
            b.set(u, 1, 1, b.stair(pal.woodStairs, Dir.NORTH, false));
            b.set(u, 1, -1, b.stair(pal.woodStairs, Dir.SOUTH, false));
        }
        b.chest(hw - 1, 1, hd - 1, Dir.WEST, BpBuilder.LOOT_CIVILIAN, p.salt + 1);
        b.lantern(0, ceil, 0, true);
    }

    static void workshop(BpBuilder b, Place p) {
        int hw = p.w / 2, hd = p.d / 2;
        int ceil = shell(b, hw, hd, 3, 1, true, false);
        String f = b.dir(Dir.SOUTH).id;
        b.set(-hw + 1, 1, -hd + 1, s("anvil").with("facing", b.dir(Dir.EAST).id));
        b.set(-hw + 2, 1, -hd + 1, s("grindstone").with("face", "floor").with("facing", f));
        b.set(-hw + 3, 1, -hd + 1, s("smithing_table"));
        b.set(hw - 1, 1, -hd + 1, s("blast_furnace").with("facing", f).with("lit", false));
        b.set(hw - 2, 1, -hd + 1, s("crafting_table"));
        b.set(hw - 1, 1, 0, s("iron_block"));
        b.chest(hw - 1, 1, hd - 1, Dir.WEST, BpBuilder.LOOT_TECHNICAL, p.salt);
        b.barrel(-hw + 1, 1, hd - 1, Dir.UP, BpBuilder.LOOT_TECHNICAL, p.salt + 1);
        b.lantern(0, ceil, 0, true);
    }

    static void armory(BpBuilder b, Place p) {
        int hw = p.w / 2, hd = p.d / 2;
        int ceil = shell(b, hw, hd, 3, 0, true, false);
        String f = b.dir(Dir.SOUTH).id;
        for (int u = -hw + 1; u <= hw - 1; u += 2) {
            b.chest(u, 1, -hd + 1, Dir.SOUTH, BpBuilder.LOOT_ARMORY, p.salt + u);
        }
        b.set(-hw + 1, 1, 0, s("dispenser").with("facing", b.dir(Dir.EAST).id).with("triggered", false));
        b.set(hw - 1, 1, 0, s("dispenser").with("facing", b.dir(Dir.WEST).id).with("triggered", false));
        b.set(0, 1, 0, s("grindstone").with("face", "floor").with("facing", f));
        b.set(-hw + 1, 2, -hd + 1, s("target").with("power", "0"));
        b.lantern(0, ceil, 0, true);
        // мишени у входа
        for (int u = -2; u <= 2; u += 2) b.set(u, 1, hd + 4, s("target").with("power", "0"));
    }

    static void pillbox(BpBuilder b, Place p) {
        BpPalette pal = b.pal;
        floor(b, 3, 3);
        for (int u = -3; u <= 3; u++) {
            for (int v = -3; v <= 3; v++) {
                boolean ring = Math.abs(u) == 3 || Math.abs(v) == 3;
                if (!ring) continue;
                for (int dy = 1; dy <= 3; dy++) {
                    if (dy == 2 && ((Math.abs(u) == 3 && Math.abs(v) <= 1) || (Math.abs(v) == 3 && Math.abs(u) <= 1)) && !(v == 3 && u == 0))
                        b.set(u, dy, v, b.paneAlong("iron_bars", Math.abs(v) == 3));
                    else b.set(u, dy, v, dy == 3 ? pal.pillar : pal.wallMain);
                }
            }
        }
        b.door(0, 1, 3, Dir.NORTH, "left");
        b.set(0, 0, 4, pal.floorMain);
        for (int u = -3; u <= 3; u++)
            for (int v = -3; v <= 3; v++) b.set(u, 4, v, ((u + v) & 1) == 0 ? pal.floorAccent : pal.wallMain);
        // парапет крыши, ступени к ней вдоль восточной стены
        for (int u = -3; u <= 3; u++)
            for (int v = -3; v <= 3; v++)
                if ((Math.abs(u) == 3 || Math.abs(v) == 3) && ((u + v) & 1) == 0) b.setMasonry(u, 5, v);
        roofStair(b, 3, 2, 3);
        b.air(3, 5, 0);
        b.air(3, 5, -1);
        b.air(3, 5, 1);
        // интерьер
        b.barrel(-2, 1, -2, Dir.UP, BpBuilder.LOOT_SUPPLY, p.salt);
        b.chest(2, 1, -2, Dir.WEST, BpBuilder.LOOT_BUNKER, p.salt + 1);
        b.set(0, 1, -2, s("iron_bars"));
        b.lantern(0, 3, 0, true);
    }

    static void cabin(BpBuilder b, Place p) {
        int hw = p.w / 2, hd = p.d / 2;
        boolean big = p.style == 0;
        int ceil = shell(b, hw, hd, 3, big ? 0 : 2, false, true);
        String bedId = bedId(b);
        bed(b, -hw + 1, 1, -hd + 1, bedId);
        if (big) bed(b, hw - 1, 1, -hd + 1, bedId);
        b.chest(big ? 0 : hw - 1, 1, -hd + 1, Dir.SOUTH, BpBuilder.LOOT_BANDIT, p.salt);
        b.barrel(-hw + 1, 1, hd - 1, Dir.UP, BpBuilder.LOOT_CIVILIAN, p.salt + 1);
        if (big) b.set(hw - 1, 1, hd - 1, s("hay_block").with("axis", "y"));
        b.set(0, 1, 0, b.slab(b.pal.woodSlab, "bottom"));
        b.lantern(0, ceil, 0, true);
    }

    // ================================================================== палатки и лагерные модули

    /** Двускатная палатка длиной 2·hd+1 вдоль V, шириной 2·hw+1; концы открыты. */
    static void tent(BpBuilder b, int hw, int hd, int salt, boolean medic) {
        BpPalette p = b.pal;
        BpSpec wool = medic ? s("white_wool") : p.bannerWool;
        for (int u = -hw; u <= hw; u++) for (int v = -hd; v <= hd; v++) b.set(u, 0, v, p.planks);
        int ridge = 3 + (hw - 1);
        for (int v = -hd; v <= hd; v++) {
            for (int dy = 1; dy <= 2; dy++) { b.set(-hw, dy, v, wool); b.set(hw, dy, v, wool); }
            int level = 3;
            int w = hw - 1;
            while (w > 0) {
                b.set(-w, level, v, wool);
                b.set(w, level, v, wool);
                w--;
                level++;
            }
            b.set(0, level, v, b.slab(p.woodSlab, "bottom"));
        }
        if (medic) {
            for (int v = -hd + 1; v <= hd - 1; v += 2) { bed(b, -hw + 1, 1, v, "white_bed"); }
            b.set(hw - 1, 1, -hd + 1, s("red_wool"));
            b.chest(hw - 1, 1, hd - 1, Dir.WEST, BpBuilder.LOOT_MEDICAL, salt);
        } else {
            bed(b, -hw + 1, 1, -hd + 1, bedId(b));
            b.chest(hw - 1, 1, hd - 1, Dir.WEST, BpBuilder.LOOT_SUPPLY, salt);
            b.barrel(hw - 1, 1, hd - 2 >= -hd ? hd - 2 : hd, Dir.UP, BpBuilder.LOOT_SUPPLY, salt + 1);
        }
        b.lantern(0, ridge - 1, 0, true);
    }

    static void fieldTent(BpBuilder b, int cu, int cv, int salt) {
        BpBuilder c = b.child(cu, cv, 0);
        tent(c, 2, 2, salt, false);
    }

    static void hospital(BpBuilder b, Place p) {
        tent(b, 3, 2, p.salt, true);
    }

    static void campfire(BpBuilder b, Place p) {
        b.set(0, 0, 0, s("cobblestone"));
        for (int u = -1; u <= 1; u++) for (int v = -1; v <= 1; v++) if (u != 0 || v != 0) b.set(u, 0, v, s("coarse_dirt"));
        b.campfire(0, 1, 0, p.salt % 2 == 0);
        b.set(-2, 1, 0, b.logHorizontal(false));
        b.set(-2, 1, 1, b.logHorizontal(false));
        b.set(2, 1, 0, b.logHorizontal(false));
        b.set(2, 1, -1, b.logHorizontal(false));
        b.set(0, 1, -2, b.logHorizontal(true));
        b.set(1, 1, 3, s("hay_block").with("axis", "y"));
        b.barrel(-3, 1, -2, Dir.UP, BpBuilder.LOOT_SUPPLY, p.salt);
        b.set(3, 1, 3, s("hay_block").with("axis", "y"));
        b.set(3, 2, 3, s("target").with("power", "0"));
    }

    static void cage(BpBuilder b, Place p) {
        for (int u = -2; u <= 2; u++) for (int v = -2; v <= 2; v++) b.set(u, 0, v, b.pal.planks);
        for (int u = -2; u <= 2; u++) {
            for (int v = -2; v <= 2; v++) {
                boolean ring = Math.abs(u) == 2 || Math.abs(v) == 2;
                if (ring) for (int dy = 1; dy <= 3; dy++) b.set(u, dy, v, s("iron_bars"));
                b.set(u, 4, v, s("iron_bars"));
            }
        }
        b.set(0, 1, 2, s("iron_door").with("facing", b.dir(Dir.NORTH).id).with("half", "lower").with("hinge", "left").with("open", false).with("powered", false));
        b.set(0, 2, 2, s("iron_door").with("facing", b.dir(Dir.NORTH).id).with("half", "upper").with("hinge", "left").with("open", false).with("powered", false));
        b.set(0, 0, 3, b.pal.floorMain);
        b.set(-1, 1, -1, s("hay_block").with("axis", "y"));
        b.chest(1, 1, -1, Dir.SOUTH, BpBuilder.LOOT_SUPPLY, p.salt);
    }

    static void fuel(BpBuilder b, Place p) {
        for (int u = -2; u <= 2; u++) for (int v = -2; v <= 2; v++) b.set(u, 0, v, b.pal.yardPath);
        for (int u = -2; u <= 2; u++) {
            for (int v = -2; v <= 2; v++) {
                if (Math.abs(u) != 2 && Math.abs(v) != 2) continue;
                if (v == 2 && Math.abs(u) <= 0) continue;
                b.set(u, 1, v, b.fenceAlong(b.pal.woodFence, Math.abs(v) == 2));
            }
        }
        for (int u = -1; u <= 1; u++) for (int v = -1; v <= 0; v++) {
            b.barrel(u, 1, v, Dir.UP, null, p.salt + u + v);
            if (((u + v) & 1) == 0) b.barrel(u, 2, v, Dir.UP, null, p.salt + 9 + u);
        }
        b.set(0, 1, -2, s("coal_block"));
    }

    static void mortar(BpBuilder b, Place p) {
        BpPalette pal = b.pal;
        for (int u = -2; u <= 2; u++) for (int v = -2; v <= 2; v++) {
            b.set(u, 0, v, pal.yardPath);
            boolean ring = Math.abs(u) == 2 || Math.abs(v) == 2;
            if (!ring || (v == 2 && Math.abs(u) <= 1)) continue;
            b.set(u, 1, v, pal.sandbag);
            if (((u + v) & 1) == 0) b.set(u, 2, v, pal.sandbag);
        }
        b.set(0, 1, 0, s("smooth_stone_slab").with("type", "bottom").with("waterlogged", false));
        b.set(0, 2, 0, s("dispenser").with("facing", "up").with("triggered", false));
        b.barrel(1, 1, 1, Dir.UP, BpBuilder.LOOT_ARMORY, p.salt);
    }

    static void radio(BpBuilder b, Place p) {
        for (int u = -1; u <= 1; u++) for (int v = -1; v <= 1; v++) b.set(u, 0, v, s("stone_bricks"));
        b.set(1, 1, 1, s("furnace").with("facing", b.dir(Dir.NORTH).id).with("lit", false));
        b.barrel(-1, 1, 1, Dir.UP, BpBuilder.LOOT_TECHNICAL, p.salt);
        for (int dy = 1; dy <= 10; dy++) b.set(0, dy, 0, s("iron_bars"));
        b.set(-1, 6, 0, s("iron_bars"));
        b.set(1, 6, 0, s("iron_bars"));
        b.set(0, 6, -1, s("iron_bars"));
        b.set(0, 6, 1, s("iron_bars"));
        b.set(0, 11, 0, s("lightning_rod").with("facing", "up").with("powered", false).with("waterlogged", false));
    }

    static void helipad(BpBuilder b) {
        for (int u = -4; u <= 4; u++) {
            for (int v = -4; v <= 4; v++) {
                boolean edge = Math.abs(u) == 4 || Math.abs(v) == 4;
                boolean h = (Math.abs(u) == 1 && Math.abs(v) <= 2) || (v == 0 && Math.abs(u) <= 1);
                b.set(u, 0, v, edge ? s("yellow_concrete") : (h ? s("white_concrete") : s("gray_concrete")));
            }
        }
        for (int sx = -4; sx <= 4; sx += 8) for (int sz = -4; sz <= 4; sz += 8) {
            b.set(sx, 1, sz, b.fenceAlong(b.pal.woodFence, true));
            b.lantern(sx, 2, sz, false);
        }
    }

    static void totem(BpBuilder b, Place p) {
        for (int u = -1; u <= 1; u++) for (int v = -1; v <= 1; v++) b.set(u, 0, v, s("blackstone"));
        for (int dy = 1; dy <= 4; dy++) b.set(0, dy, 0, b.logVertical());
        b.set(-1, 3, 0, b.fenceAlong(b.pal.woodFence, true));
        b.set(1, 3, 0, b.fenceAlong(b.pal.woodFence, true));
        b.set(0, 5, 0, s("skeleton_skull").with("rotation", "0").with("powered", false).with("waterlogged", false));
        b.set(-1, 4, 0, b.pal.bannerWool);
        b.set(1, 4, 0, b.pal.bannerWool);
        b.campfire(1, 1, 1, false);
    }

    // ================================================================== полевые укрепления

    static void sandbag(BpBuilder b, Place p) {
        for (int i = -2; i <= 2; i++) {
            b.set(i, 1, 1, b.pal.sandbag);
            if (Math.abs(i) == 1) b.set(i, 2, 1, b.pal.sandbag);
            else if (i == 0) b.set(i, 2, 1, b.slab(b.pal.woodSlab, "bottom"));
        }
        b.set(-2, 1, 0, b.pal.sandbag);
        b.set(2, 1, 0, b.pal.sandbag);
        if (p.salt % 2 == 0) b.set(0, 1, 0, s("hay_block").with("axis", "y"));
    }

    static void tankTrap(BpBuilder b, Place p) {
        if (p.salt % 2 == 0) {
            b.set(0, 1, 0, b.wall("cobblestone_wall"));
            b.set(-1, 1, 0, b.paneAlong("iron_bars", true));
            b.set(1, 1, 0, b.paneAlong("iron_bars", true));
            b.set(0, 1, -1, b.paneAlong("iron_bars", false));
            b.set(0, 1, 1, b.paneAlong("iron_bars", false));
            b.set(0, 2, 0, s("iron_bars"));
        } else {
            // «зубы дракона»
            for (int i = -1; i <= 1; i++) {
                b.set(i, 1, 0, s("cobblestone"));
                if (i != 0) b.set(i, 2, 0, b.stair("cobblestone_stairs", i < 0 ? Dir.EAST : Dir.WEST, false));
            }
            b.set(0, 2, 0, s("cobblestone"));
        }
    }

    static void wire(BpBuilder b, Place p) {
        int hw = p.w / 2;
        for (int u = -hw; u <= hw; u++) {
            if (Math.floorMod(u, 3) == 0) {
                b.set(u, 1, 0, b.fenceAlong(b.pal.woodFence, true));
                b.set(u, 2, 0, b.fenceAlong(b.pal.woodFence, true));
            } else {
                b.set(u, 1, 0, ((u + p.salt) & 1) == 0 ? s("iron_bars") : s("cobweb"));
            }
        }
    }

    static void trench(BpBuilder b, Place p) {
        int hw = p.w / 2;
        for (int u = -hw; u <= hw; u++) {
            if (Math.abs(u) == hw) {
                b.set(u, 0, 0, b.slab(b.pal.woodSlab, "bottom"));        // пологий выход
                continue;
            }
            b.air(u, 0, 0);
            b.set(u, -1, 0, b.pal.planks);
            b.air(u, 1, 0);
            b.air(u, 2, 0);
            b.set(u, 0, 1, b.pal.planks);                                // стенка и бруствер со стороны огня
            b.set(u, 1, 1, b.pal.sandbag);
            b.set(u, 0, -1, b.logHorizontal(true));
        }
    }

    static void crates(BpBuilder b, Place p) {
        for (int u = -1; u <= 1; u++) {
            for (int v = -1; v <= 1; v++) {
                long h = WarzoneNoise.hash(b.terrain.seed + p.salt, b.wx(u, v), b.wz(u, v)) & 7;
                if (h < 3) continue;
                if (h == 3) b.set(u, 1, v, s("hay_block").with("axis", "y"));
                else if (h < 6) b.barrel(u, 1, v, Dir.UP, BpBuilder.LOOT_SUPPLY, p.salt + u + v * 5);
                else b.chest(u, 1, v, Dir.SOUTH, BpBuilder.LOOT_SUPPLY, p.salt + u + v * 5);
                if (h == 7) b.set(u, 2, v, s("barrel").with("facing", "up").with("open", false));
            }
        }
    }
}
