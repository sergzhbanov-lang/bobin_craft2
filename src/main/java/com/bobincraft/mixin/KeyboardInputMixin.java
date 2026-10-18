package com.bobincraft.mixin;

import com.bobincraft.BobinCraftClient;
import com.bobincraft.camera.BobinCameraState;
import com.bobincraft.movement.BobinMovementController;
import com.bobincraft.screen.BobinScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin extends Input {

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(boolean slowDown, float slowDownFactor, CallbackInfo ci) {
        if (!BobinCameraState.isActive()) {
            return;
        }

        BobinMovementController controller = BobinCraftClient.getMovementController();
        if (controller == null) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof BobinScreen) || !client.isWindowFocused() || client.isPaused()) {
            controller.clearTarget();
            this.movementForward = this.movementSideways = 0;
            this.pressingForward = this.pressingBack = this.pressingLeft = this.pressingRight = false;
            this.jumping = this.sneaking = false;
            return;
        }
        controller.updateKeyboardMovement(client);

        {
            float fwd = controller.getMoveForward();
            float side = controller.getMoveSideways();
            this.movementForward = fwd;
            this.movementSideways = side;
            this.pressingForward = fwd > 0.05f;
            this.pressingBack = fwd < -0.05f;
            this.pressingLeft = side > 0.05f;
            this.pressingRight = side < -0.05f;

            if (slowDown) {
                this.movementForward *= slowDownFactor;
                this.movementSideways *= slowDownFactor;
            }
        }

        this.jumping = controller.shouldJump() || BobinMovementController.isKeyDown(client, client.options.jumpKey);
        this.sneaking = BobinMovementController.isKeyDown(client, client.options.sneakKey);
        if (client.player != null && BobinMovementController.isKeyDown(client, client.options.sprintKey)
                && !this.sneaking && this.movementForward > 0) client.player.setSprinting(true);
    }
}
