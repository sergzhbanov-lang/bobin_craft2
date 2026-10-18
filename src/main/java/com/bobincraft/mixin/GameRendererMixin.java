package com.bobincraft.mixin;

import com.bobincraft.camera.BobinCameraState;
import com.bobincraft.weapon.BobinWeaponHandler;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void bobin_craft$scopeFov(Camera camera, float tickDelta, boolean changingFov,
                                     CallbackInfoReturnable<Double> cir) {
        if (!BobinCameraState.isActive()) {
            cir.setReturnValue(cir.getReturnValue() * BobinWeaponHandler.getScopeZoom(tickDelta));
        }
    }

    /**
     * Скрываем вид от первого лица (руки, удерживаемый предмет, лишние конечности от модов),
     * когда включена MOBA-камера.
     */
    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void onRenderHand(MatrixStack matrices, Camera camera, float tickDelta, CallbackInfo ci) {
        if (BobinCameraState.isActive()) {
            ci.cancel();
        }
    }
}
