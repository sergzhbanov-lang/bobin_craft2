package com.bobincraft.input;

import com.bobincraft.render.BuildingCutawayManager;
import com.bobincraft.render.FogOfWarManager;
import com.bobincraft.weapon.BobinWeaponHandler;
import com.bobincraft.weapon.BulletPenetration;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.Window;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class RaycastHelper {

    // Реальные коэффициенты проекционной матрицы текущего кадра (m00 = f/aspect, m11 = f).
    // Учитывают динамический FOV (бег, эффекты), поэтому луч совпадает с тем, что нарисовано на экране.
    private static volatile float projM00 = 0.0f;
    private static volatile float projM11 = 0.0f;

    public static void setProjection(Matrix4f projection) {
        if (projection == null) return;
        float m00 = projection.m00();
        float m11 = projection.m11();
        if (m00 > 1.0e-4f && m11 > 1.0e-4f) {
            projM00 = m00;
            projM11 = m11;
        }
    }

    /**
     * Луч от точной позиции курсора (сырые пиксели окна, без округления до GUI-пикселей).
     */
    public static HitResult raycastFromCursor(MinecraftClient client, double maxDistance) {
        if (client.mouse == null || client.getWindow() == null) return null;
        Window window = client.getWindow();
        int w = window.getWidth();
        int h = window.getHeight();
        if (w <= 0 || h <= 0) return null;
        double ndcX = (2.0 * client.mouse.getX() / w) - 1.0;
        double ndcY = 1.0 - (2.0 * client.mouse.getY() / h);
        return raycastNdc(client, ndcX, ndcY, maxDistance);
    }

    /** Совместимость: координаты в GUI-масштабе (double). */
    public static HitResult raycastFromCursor(MinecraftClient client, double mouseX, double mouseY, double maxDistance) {
        Window window = client.getWindow();
        if (window == null || window.getScaledWidth() <= 0 || window.getScaledHeight() <= 0) return null;
        double ndcX = (2.0 * mouseX / window.getScaledWidth()) - 1.0;
        double ndcY = 1.0 - (2.0 * mouseY / window.getScaledHeight());
        return raycastNdc(client, ndcX, ndcY, maxDistance);
    }

    /** Ordinary view aims from the player's eyes without rotating the player toward a stale cursor hit. */
    public static HitResult raycastFromPlayer(MinecraftClient client, double maxDistance) {
        if (client.world == null || client.player == null) return null;
        return raycastRay(client, client.player.getEyePos(), client.player.getRotationVec(1.0f), maxDistance, false);
    }

    private static HitResult raycastNdc(MinecraftClient client, double ndcX, double ndcY, double maxDistance) {
        if (client.world == null || client.player == null) {
            return null;
        }

        Camera camera = client.gameRenderer.getCamera();
        Vec3d camPos = camera.getPos();

        float xScale;
        float yScale;
        if (projM00 > 0.0f && projM11 > 0.0f) {
            xScale = 1.0f / projM00;
            yScale = 1.0f / projM11;
        } else {
            Window window = client.getWindow();
            float fov = (float) Math.toRadians(client.options.getFov().getValue());
            yScale = (float) Math.tan(fov / 2.0f);
            xScale = yScale * ((float) window.getWidth() / (float) window.getHeight());
        }

        // Вектор луча в пространстве камеры
        Vector3f camRay = new Vector3f((float) (-ndcX * xScale), (float) (ndcY * yScale), 1.0f);
        camera.getRotation().transform(camRay);
        camRay.normalize();

        Vec3d rayDir = new Vec3d(camRay.x, camRay.y, camRay.z);
        return raycastRay(client, camPos, rayDir, maxDistance, true);
    }

    private static HitResult raycastRay(MinecraftClient client, Vec3d startVec, Vec3d rayDir,
                                       double maxDistance, boolean tactical) {
        Vec3d endVec = startVec.add(rayDir.multiply(maxDistance));

        final boolean holdingWeapon = BobinWeaponHandler.isHoldingWeapon(client.player);

        // 1. Проверка попадания в блоки: скрытые блоки крыши/верхних стен (Roof Cutaway) пропускаем,
        // поэтому клик проходит сквозь прозрачную крышу прямо в пол внутри здания.
        final net.minecraft.world.World world = client.world;
        final ShapeContext shapeContext = ShapeContext.of(client.player);
        BlockHitResult blockHit = BlockView.raycast(startVec, endVec, shapeContext, (ctx, pos) -> {
            if (tactical && BuildingCutawayManager.isCutawayBlock(pos)) {
                return null;
            }
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                return null;
            }
            VoxelShape shape = state.getOutlineShape(world, pos, ctx);
            if (shape.isEmpty()) {
                return null;
            }
            return shape.raycast(startVec, endVec, pos);
        }, ctx -> null);

        // Для проверки видимости врагов сквозь траву/листву/паутину/решётки/стёкла при стрельбе:
        BlockHitResult solidBlockHit = blockHit;
        if (holdingWeapon && blockHit != null && blockHit.getType() == HitResult.Type.BLOCK) {
            BlockState hitState = world.getBlockState(blockHit.getBlockPos());
            if (BulletPenetration.isFullPenetrationBlock(hitState) || BulletPenetration.isFragileShatterBlock(hitState)) {
                solidBlockHit = BlockView.raycast(startVec, endVec, shapeContext, (ctx, pos) -> {
                    if (tactical && BuildingCutawayManager.isCutawayBlock(pos)) {
                        return null;
                    }
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir() || BulletPenetration.isFullPenetrationBlock(state) || BulletPenetration.isFragileShatterBlock(state)) {
                        return null;
                    }
                    VoxelShape shape = state.getOutlineShape(world, pos, ctx);
                    if (shape.isEmpty()) {
                        return null;
                    }
                    return shape.raycast(startVec, endVec, pos);
                }, ctx -> null);
            }
        }

        double solidBlockDistSq = (solidBlockHit != null && solidBlockHit.getType() != HitResult.Type.MISS) ?
                startVec.squaredDistanceTo(solidBlockHit.getPos()) : Double.MAX_VALUE;

        // 2. Проверка попадания в сущности (мобы, другие игроки; с оружием в руках игнорируем картины и рамки)
        Box searchBox = new Box(startVec, endVec).expand(1.0);
        EntityHitResult entityHit = ProjectileUtil.raycast(
                client.player,
                startVec,
                endVec,
                searchBox,
                entity -> !entity.isSpectator()
                        && entity.canHit()
                        && entity != client.player
                        && !(holdingWeapon && BulletPenetration.isPassThroughDecorationEntity(entity))
                        && (!tactical || !FogOfWarManager.isEntityHidden(entity)),
                maxDistance * maxDistance
        );

        if (entityHit != null) {
            double entityDistSq = startVec.squaredDistanceTo(entityHit.getPos());
            if (entityDistSq < solidBlockDistSq) {
                return entityHit;
            }
        }

        if (blockHit != null && blockHit.getType() != HitResult.Type.MISS) {
            return blockHit;
        }

        // 3. Fallback: клик в пустоту/землю на уровне ног игрока
        if (tactical && rayDir.y < -0.001) {
            double t = (client.player.getY() - startVec.y) / rayDir.y;
            if (t > 0 && t <= maxDistance) {
                Vec3d groundPos = startVec.add(rayDir.multiply(t));
                BlockPos groundBp = BlockPos.ofFloored(groundPos);
                return new BlockHitResult(groundPos, Direction.UP, groundBp, false);
            }
        }

        return blockHit;
    }
}
