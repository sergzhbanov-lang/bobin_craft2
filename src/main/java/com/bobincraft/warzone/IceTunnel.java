package com.bobincraft.warzone;

import com.bobincraft.BobinCraft;
import com.bobincraft.block.IcicleBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.Thickness;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * Ледяной тоннель к горной крепости. Вместо канавы-«прорези» в склоне путь теперь уходит ВНУТРЬ горы:
 * рельеф снаружи не меняется, а блоки породы по оси пути вырезаются сводчатым профилем (радиус {@code halfWidth},
 * высота ~5 блоков), стены и свод облицовываются синим/плотным льдом и снегом, в стены вставлены морские фонари,
 * с потолка свисают ледяные сосульки (и растут редкие «сталагмиты» у стен).
 *
 * Вход в тоннель — у подножия (там, где пол встречается с землёй равнины, к нему ведёт дорога),
 * выход — у ворот крепости. Всё считается как чистая функция колонки (x, z), поэтому чанки стыкуются без швов.
 *
 * Обычный лёд ({@code ICE}) намеренно не используется: он тает от света фонарей и затопил бы тоннель.
 */
final class IceTunnel {

    private static final double HEIGHT = 5.0;       // высота свода по оси (блоков воздуха)
    private static final double WALL = 1.5;         // толщина облицовки стен
    private static final int LAMP_EVERY = 12;       // фонарь раз в N блоков пути

    private IceTunnel() {}

    static void carve(WarzoneTerrain t, WarzoneBasePlan plan, ChunkWriter w) {
        List<WarzoneBasePlan.TrailPath> list = plan.pathsForChunk(w.minX, w.minZ, 8);
        if (list.isEmpty()) return;

        double[] q = new double[3];
        for (WarzoneBasePlan.TrailPath p : list) {
            if (!p.tunnel) continue;
            final double r = p.halfWidth;
            final double outer = r + WALL + 0.1;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int x = w.minX + lx;
                    int z = w.minZ + lz;
                    if (!p.queryAt(x + 0.5, z + 0.5, q)) continue;
                    double d = q[0];
                    if (d > outer || Double.isNaN(q[1])) continue;
                    carveColumn(t, w, x, z, d, (int) Math.floor(q[1]), r, q[2] * 5.0);
                }
            }
        }
    }

    private static void carveColumn(WarzoneTerrain t, ChunkWriter w, int x, int z, double d, int fy, double r, double arc) {
        double a = d / r;
        int airTop = a < 1.0 ? (int) Math.ceil(HEIGHT * Math.sqrt(1.0 - a * a) - 1.0e-6) : 0;   // блоков воздуха над полом

        double so = r + WALL;
        double sa = d / so;
        int shellTop = sa < 1.0 ? (int) Math.ceil((HEIGHT + WALL) * Math.sqrt(1.0 - sa * sa) - 1.0e-6) : 0;

        double rnd = WarzoneNoise.unit(WarzoneNoise.hash(t.seed + 4242, x, z));

        // ---- пол (на оси и у стен) и подпол
        if (d <= r + 0.5) {
            w.set(x, fy, z, floorBlock(rnd));
        } else if (isSolid(w, x, fy, z)) {
            w.set(x, fy, z, wallBlock(rnd));
        }
        if (d <= so && isSolid(w, x, fy - 1, z)) {
            w.set(x, fy - 1, z, rnd < 0.5 ? Blocks.PACKED_ICE.getDefaultState() : Blocks.STONE.getDefaultState());
        }

        // ---- воздух внутри свода
        for (int dy = 1; dy <= airTop; dy++) {
            if (!w.get(x, fy + dy, z).isAir()) {
                w.set(x, fy + dy, z, Blocks.AIR.getDefaultState());
            }
        }

        // ---- облицовка стен и свода (только там, где уже есть порода: наружу в воздух ничего не достраиваем)
        boolean lampColumn = d >= r - 0.2 && d < r + 0.9 && ((int) Math.floor(arc)) % LAMP_EVERY == 0;
        for (int dy = airTop + 1; dy <= shellTop; dy++) {
            if (!isSolid(w, x, fy + dy, z)) continue;
            if (lampColumn && dy == 2) {
                w.set(x, fy + dy, z, Blocks.SEA_LANTERN.getDefaultState());
                continue;
            }
            double rr = WarzoneNoise.unit(WarzoneNoise.hash(t.seed + 4243 + dy, x, z));
            w.set(x, fy + dy, z, wallBlock(rr));
        }

        // ---- сосульки: свисают с потолка, торчат у стен
        if (airTop >= 4 && isSolid(w, x, fy + airTop + 1, z)) {
            double ri = WarzoneNoise.unit(WarzoneNoise.hash(t.seed + 4244, x, z));
            if (ri < 0.16) {
                if (airTop >= 5 && ri < 0.06) {
                    w.set(x, fy + airTop, z, icicle(Direction.DOWN, Thickness.BASE));
                    w.set(x, fy + airTop - 1, z, icicle(Direction.DOWN, Thickness.TIP));
                } else {
                    w.set(x, fy + airTop, z, icicle(Direction.DOWN, Thickness.TIP));
                }
            }
        }
        if (airTop >= 3 && a >= 0.55 && a < 0.95) {
            double ri = WarzoneNoise.unit(WarzoneNoise.hash(t.seed + 4245, x, z));
            if (ri < 0.09 && w.get(x, fy + 1, z).isAir()) {
                w.set(x, fy + 1, z, icicle(Direction.UP, Thickness.TIP));
            }
        }
    }

    private static boolean isSolid(ChunkWriter w, int x, int y, int z) {
        BlockState st = w.get(x, y, z);
        return !st.isAir() && st.getFluidState().isEmpty() && !st.isOf(Blocks.BEDROCK);
    }

    /** Пол: снег и камень (не лёд — по нему скользит герой и ломается движение). */
    private static BlockState floorBlock(double r) {
        if (r < 0.62) return Blocks.SNOW_BLOCK.getDefaultState();
        if (r < 0.80) return Blocks.CALCITE.getDefaultState();
        return Blocks.STONE.getDefaultState();
    }

    private static BlockState wallBlock(double r) {
        if (r < 0.46) return Blocks.BLUE_ICE.getDefaultState();
        if (r < 0.80) return Blocks.PACKED_ICE.getDefaultState();
        if (r < 0.93) return Blocks.SNOW_BLOCK.getDefaultState();
        return Blocks.CALCITE.getDefaultState();
    }

    private static BlockState icicle(Direction tip, Thickness thickness) {
        return BobinCraft.ICICLE.getDefaultState()
                .with(IcicleBlock.VERTICAL_DIRECTION, tip)
                .with(IcicleBlock.THICKNESS, thickness);
    }
}
