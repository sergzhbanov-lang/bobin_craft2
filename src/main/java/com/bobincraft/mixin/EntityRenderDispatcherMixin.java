package com.bobincraft.mixin;

import com.bobincraft.camera.BobinCameraState;
import com.bobincraft.render.FogOfWarManager;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    /**
     * Туман войны: существа вне радиуса обзора героя не рисуются.
     * require = 0 — если сигнатура в другой версии игры не совпадёт, мод не упадёт при запуске, а просто не спрячет существ.
     */
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    private <E extends Entity> void bobin_craft$hideInFog(E entity, Frustum frustum, double x, double y, double z,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (BobinCameraState.isActive() && FogOfWarManager.isEntityHidden(entity)) {
            cir.setReturnValue(false);
        }
    }
}
