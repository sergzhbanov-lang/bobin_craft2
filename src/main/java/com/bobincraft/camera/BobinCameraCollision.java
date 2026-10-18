package com.bobincraft.camera;

import com.bobincraft.render.BuildingCutawayManager;
import com.bobincraft.input.ControlMath;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.util.hit.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Коллизия свободной камеры.
 *
 * Камера ведёт себя как маленькое физическое тело: каждый кадр она едет от своей прошлой позиции к желаемой
 * и упирается в блоки по осям (Y, X, Z), скользя вдоль стен и пола. Поэтому:
 *  - сквозь стены и в блоки камера не залетает;
 *  - камера НЕ притягивается к полу/герою: над крышей здания она спокойно летит над ним (коллизия работает только
 *    там, где камера реально касается блока, а не по лучу от героя);
 *  - блоки, скрытые «разрезом крыши», камеру не задерживают.
 * Если герой ушёл далеко вперёд (туннель, узкая щель, куда камере не пролезть), срабатывает запасной вариант:
 * камера подтягивается к герою по лучу, как в ванильном виде от третьего лица.
 */
public final class BobinCameraCollision {

    private static final double HALF = 0.30;        // половина размера «тела» камеры
    private static final double LEASH = 8.0;        // дальше этого от желаемой точки — запасной вариант (луч от героя)
    private static final double MAX_SWEEP = 12.0;   // большие скачки (первый кадр, телепорт) — сразу запасной вариант
    private static final double SKIN = 0.05;

    private static Vec3d pos = null;
    private static boolean easing = false;           // после упора возвращаемся на желаемую позицию плавно, без рывка

    private BobinCameraCollision() {}

    public static void reset() {
        pos = null;
        easing = false;
    }

    /**
     * @param focus    точка, на которую смотрит камера (герой + панорамирование)
     * @param heroEye  глаза героя (запасная точка отсчёта луча, если focus оказался внутри блока)
     * @param desired  желаемая позиция камеры без учёта коллизии
     */
    public static Vec3d resolve(BlockView world, Vec3d focus, Vec3d heroEye, Vec3d desired, double frameSeconds) {
        if (pos == null || pos.squaredDistanceTo(desired) > MAX_SWEEP * MAX_SWEEP) {
            pos = fallbackRay(world, focus, heroEye, desired);
            easing = false;
            return pos;
        }

        Vec3d goal = desired;
        if (easing) {
            goal = pos.add(desired.subtract(pos).multiply(ControlMath.smoothing(12.0, frameSeconds)));
        }

        Vec3d moved = sweep(world, pos, goal);
        boolean blocked = moved.squaredDistanceTo(goal) > 1.0e-6;

        // Камера застряла далеко от желаемой точки (например, герой зашёл в узкий проход) — подтягиваем по лучу
        if (moved.distanceTo(desired) > LEASH) {
            moved = fallbackRay(world, focus, heroEye, desired);
            blocked = true;
        }

        // Страховка: если тело камеры всё же оказалось в блоке — вытаскиваем лучом
        if (intersectsSolid(world, boxAt(moved))) {
            moved = fallbackRay(world, focus, heroEye, desired);
            blocked = true;
        }

        easing = blocked || (easing && moved.distanceTo(desired) > 0.05);
        pos = moved;
        return pos;
    }

    // ------------------------------------------------------------------ скольжение по блокам

    private static Box boxAt(Vec3d p) {
        return new Box(p.x - HALF, p.y - HALF, p.z - HALF, p.x + HALF, p.y + HALF, p.z + HALF);
    }

    private static Vec3d sweep(BlockView world, Vec3d from, Vec3d to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        Box box = boxAt(from);
        List<VoxelShape> shapes = collectShapes(world, box.stretch(dx, dy, dz).expand(0.1));

        dy = clip(Direction.Axis.Y, box, shapes, dy);
        box = box.offset(0, dy, 0);
        dx = clip(Direction.Axis.X, box, shapes, dx);
        box = box.offset(dx, 0, 0);
        dz = clip(Direction.Axis.Z, box, shapes, dz);
        box = box.offset(0, 0, dz);
        return box.getCenter();
    }

    private static double clip(Direction.Axis axis, Box box, List<VoxelShape> shapes, double d) {
        if (Math.abs(d) < 1.0e-9) return 0.0;
        for (VoxelShape s : shapes) {
            d = s.calculateMaxDistance(axis, box, d);
            if (Math.abs(d) < 1.0e-9) return 0.0;
        }
        return d;
    }

    private static List<VoxelShape> collectShapes(BlockView world, Box area) {
        List<VoxelShape> out = new ArrayList<>();
        int x0 = (int) Math.floor(area.minX), x1 = (int) Math.floor(area.maxX);
        int y0 = (int) Math.floor(area.minY), y1 = (int) Math.floor(area.maxY);
        int z0 = (int) Math.floor(area.minZ), z1 = (int) Math.floor(area.maxZ);
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    m.set(x, y, z);
                    if (BuildingCutawayManager.isCutawayBlock(m)) continue;
                    BlockState st = world.getBlockState(m);
                    if (!blocksCamera(st, world, m)) continue;
                    out.add(st.getCollisionShape(world, m).offset(x, y, z));
                }
            }
        }
        return out;
    }

    private static boolean intersectsSolid(BlockView world, Box box) {
        for (VoxelShape s : collectShapes(world, box)) {
            for (Box b : s.getBoundingBoxes()) {
                if (b.intersects(box.contract(0.01))) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ запасной вариант: луч от героя

    private static Vec3d fallbackRay(BlockView world, Vec3d focus, Vec3d heroEye, Vec3d desired) {
        Vec3d origin = blocksCamera(world, BlockPos.ofFloored(focus)) ? heroEye : focus;
        Vec3d delta = desired.subtract(origin);
        double len = delta.length();
        if (len < 1.0e-4) return desired;

        double allowed = len;
        for (int i = 0; i < 9; i++) {
            double ox = i == 8 ? 0 : ((i & 1) * 2 - 1) * HALF;
            double oy = i == 8 ? 0 : (((i >> 1) & 1) * 2 - 1) * HALF;
            double oz = i == 8 ? 0 : (((i >> 2) & 1) * 2 - 1) * HALF;
            Vec3d s = origin.add(ox, oy, oz);
            Vec3d e = desired.add(ox, oy, oz);
            if (i != 8 && blocksCamera(world, BlockPos.ofFloored(s))) continue;
            double d = firstHitDistance(world, s, e);
            if (d >= 0 && d < allowed) allowed = d;
        }
        allowed = Math.max(Math.min(len, 0.6), allowed - SKIN);
        return origin.add(delta.multiply(allowed / len));
    }

    private static double firstHitDistance(BlockView world, Vec3d start, Vec3d end) {
        BlockPos startBlock = BlockPos.ofFloored(start);
        BlockHitResult hit = BlockView.raycast(start, end, Boolean.TRUE, (ctx, p) -> {
            if (p.equals(startBlock)) return null;
            if (BuildingCutawayManager.isCutawayBlock(p)) return null;
            BlockState st = world.getBlockState(p);
            if (!blocksCamera(st, world, p)) return null;
            VoxelShape shape = st.getCollisionShape(world, p);
            if (shape.isEmpty()) return null;
            return shape.raycast(start, end, p);
        }, ctx -> null);
        return hit == null ? -1.0 : hit.getPos().distanceTo(start);
    }

    // ------------------------------------------------------------------ что блокирует камеру

    private static boolean blocksCamera(BlockView world, BlockPos p) {
        if (BuildingCutawayManager.isCutawayBlock(p)) return false;
        return blocksCamera(world.getBlockState(p), world, p);
    }

    /** Цельные блоки, плиты, ступени. Не блокируют: воздух, листва, трава, решётки, заборы, жидкости. */
    private static boolean blocksCamera(BlockState state, BlockView world, BlockPos p) {
        if (state.isAir() || state.isIn(BlockTags.LEAVES)) return false;
        VoxelShape shape = state.getCollisionShape(world, p);
        if (shape.isEmpty()) return false;
        if (state.isOpaque()) return true;
        Box b = shape.getBoundingBox();
        return b.getXLength() * b.getYLength() * b.getZLength() >= 0.4;
    }
}
