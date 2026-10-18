package com.bobincraft.mixin;

import com.bobincraft.camera.BobinCameraCollision;
import com.bobincraft.camera.BobinCameraState;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPos(Vec3d pos);

    @Shadow
    private boolean thirdPerson;

    @Inject(method = "isThirdPerson", at = @At("HEAD"), cancellable = true)
    private void onIsThirdPerson(CallbackInfoReturnable<Boolean> cir) {
        if (BobinCameraState.isActive()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void onCameraUpdate(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
        if (!BobinCameraState.isActive() || focusedEntity == null) {
            return;
        }

        this.thirdPerson = true;

        // Покадровая плавная интерполяция (144+ FPS) для устранения эффекта "10 фпс"
        BobinCameraState.updateFrame();

        float pitch = BobinCameraState.getPitch();
        float yaw = BobinCameraState.getYaw();
        float dist = BobinCameraState.getDistance();

        this.setRotation(yaw, pitch);

        Vec3d entityPos = focusedEntity.getLerpedPos(tickDelta);
        Vec3d targetCenter = entityPos.add(0, focusedEntity.getStandingEyeHeight(), 0)
                .add(BobinCameraState.getPanOffset());

        double pitchRad = Math.toRadians(pitch);
        double yawRad = Math.toRadians(yaw);

        double cosPitch = Math.cos(pitchRad);
        double sinPitch = Math.sin(pitchRad);
        double sinYaw = Math.sin(yawRad);
        double cosYaw = Math.cos(yawRad);

        double camOffsetX = sinYaw * cosPitch * dist;
        double camOffsetY = sinPitch * dist;
        double camOffsetZ = -cosYaw * cosPitch * dist;

        Vec3d finalCameraPos = targetCenter.add(camOffsetX, camOffsetY, camOffsetZ);

        // Нижняя граница: камера может опускаться до уровня ног персонажа (feet level)
        double feetLevelY = entityPos.y;
        if (finalCameraPos.y < feetLevelY) {
            finalCameraPos = new Vec3d(finalCameraPos.x, feetLevelY, finalCameraPos.z);
        }

        // Верхняя граница: не выше максимального обзора игрока
        double maxAllowedCameraY = feetLevelY + 35.0;
        if (finalCameraPos.y > maxAllowedCameraY) {
            finalCameraPos = new Vec3d(finalCameraPos.x, maxAllowedCameraY, finalCameraPos.z);
        }

        // Коллизия: в узком месте камера подтягивается к герою и не влетает в блоки
        finalCameraPos = BobinCameraCollision.resolve(area, targetCenter,
                entityPos.add(0, focusedEntity.getStandingEyeHeight(), 0), finalCameraPos,
                BobinCameraState.getFrameSeconds());

        this.setPos(finalCameraPos);
    }
}
