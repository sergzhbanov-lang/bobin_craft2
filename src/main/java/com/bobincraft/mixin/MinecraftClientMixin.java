package com.bobincraft.mixin;

import com.bobincraft.camera.BobinCameraState;
import com.bobincraft.weapon.BobinWeaponHandler;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Route ordinary-view firearm input through the same weapon code used by BobinScreen. */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Unique
    private boolean bobin_craft$usingGun(MinecraftClient client) {
        return !BobinCameraState.isActive() && client.currentScreen == null
                && client.isWindowFocused() && BobinWeaponHandler.isHoldingWeapon(client.player);
    }

    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void bobin_craft$shoot(CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient client = (MinecraftClient) (Object) this;
        if (bobin_craft$usingGun(client)) {
            BobinWeaponHandler.startShooting(client, null);
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void bobin_craft$disableGunMining(boolean breaking, CallbackInfo ci) {
        if (bobin_craft$usingGun((MinecraftClient) (Object) this)) ci.cancel();
    }
}
