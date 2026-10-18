package com.bobincraft.mixin;

import com.bobincraft.BobinCraftClient;
import com.bobincraft.camera.BobinCameraState;
import com.bobincraft.movement.BobinMovementController;
import com.bobincraft.weapon.BobinWeaponHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Специальная процедурная анимация крадущегося шага боком (side-step sneak)
 * и отхода спиной вперёд (backward sneak), когда персонаж идёт по клику,
 * но курсором развёрнут в другую сторону от направления движения.
 *
 * Инъекция выполняется сразу после BipedEntityModel.setAngles и ДО того,
 * как PlayerEntityModel скопирует трансформации конечностей и тела на второй слой скина
 * (leftPants, rightPants, leftSleeve, rightSleeve, jacket), поэтому одежда и броня
 * автоматически повторяют все движения.
 */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin<T extends LivingEntity> extends BipedEntityModel<T> {

    protected PlayerEntityModelMixin(ModelPart root) {
        super(root);
    }

    @Inject(
            method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/entity/model/BipedEntityModel;setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void bobin_craft$animateDirectionalSneakWalk(
            T entity,
            float limbAngle,
            float limbDistance,
            float animationProgress,
            float headYaw,
            float headPitch,
            CallbackInfo ci
    ) {
        // Всегда сбрасываем боковые смещения таза и крен корпуса в дефолтные значения,
        // так как базовый BipedEntityModel.setAngles их не перезаписывает
        this.rightLeg.pivotX = -1.9f;
        this.leftLeg.pivotX = 1.9f;
        this.body.roll = 0.0f;

        if (!BobinCameraState.isActive()) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || entity != mc.player) {
            return;
        }

        BobinMovementController controller = BobinCraftClient.getMovementController();
        if (controller == null) {
            return;
        }

        float sneak = controller.getSneakFactor();
        if (sneak <= 0.01f) {
            return;
        }

        // Угол движения относительно направления взгляда (курсора):
        // 0° = вперёд, ±90° = строго боком, ±180° = спиной вперёд
        float diffRad = (float) Math.toRadians(controller.getMoveAngleDiff());
        float fwdComp = MathHelper.cos(diffRad);          // +1 вперёд, 0 боком, -1 назад
        float moveLeftRight = -MathHelper.sin(diffRad);   // +1 влево (+X модели), -1 вправо (-X модели)

        float sideAmount = Math.abs(moveLeftRight);       // [0..1] выраженность бокового шага
        float backAmount = Math.max(0.0f, -fwdComp);      // [0..1] выраженность отхода спиной вперёд

        // Фаза и амплитуда крадущегося шага (даже на замедленной скорости шаг остаётся чётко различимым)
        float phase = limbAngle * 0.95f;
        float amp = Math.max(limbDistance, 0.55f) * sneak;

        float sinPhase = MathHelper.sin(phase);
        float cosPhase = MathHelper.cos(phase);
        float stepBob = Math.abs(sinPhase) * 0.65f * amp;

        // 1. Поза крадущегося корпуса и головы (приседание + наклон вперёд + разворот таза/плеч)
        float crouchBodyPitch = (0.50f + 0.10f * backAmount) * sneak + MathHelper.sin(phase * 2.0f) * 0.045f * amp;
        this.body.pitch = MathHelper.lerp(sneak, this.body.pitch, crouchBodyPitch);

        // При движении боком корпус слегка доворачивается в сторону шага, а при отходе назад — покачивается
        float torsoTwist = -moveLeftRight * 0.34f * sideAmount * sneak + cosPhase * 0.10f * backAmount * amp;
        this.body.yaw = torsoTwist;
        this.body.roll = cosPhase * 0.07f * sideAmount * amp + moveLeftRight * 0.05f * sneak;

        this.body.pivotY = 3.2f * sneak + stepBob;
        this.head.pivotY = 4.2f * sneak + stepBob;

        // 2. Кинематика ног: комбинация бокового приставного/перекрестного шага и осторожного шага спиной вперёд
        float baseHipZ = (4.0f + 0.6f * backAmount) * sneak;
        float baseHipY = 12.0f;

        // Вспомогательные циклы для правой и левой ноги (в противофазе)
        float rLift = Math.max(0.0f, cosPhase);
        float lLift = Math.max(0.0f, -cosPhase);

        // --- А. Компонента движения СПИНОЙ ВПЕРЁД (обратная фаза маха + вынос стопы назад + сгиб колена) ---
        // При отходе назад мах ног инвертируется (-sinPhase), стопа тянется назад (+Z), колено приподнимается
        float rPitchBack = (-sinPhase * 0.70f * amp + 0.16f * sneak - rLift * 0.25f * amp);
        float lPitchBack = ( sinPhase * 0.70f * amp + 0.16f * sneak - lLift * 0.25f * amp);
        float rZBack = -sinPhase * 1.6f * backAmount * amp;
        float lZBack =  sinPhase * 1.6f * backAmount * amp;

        // --- Б. Компонента движения БОКОМ (боковое отведение/приведение бедра roll + смещение pivotX + подъём колена) ---
        float rPitchSide = 0.12f * sneak - rLift * 0.48f * amp + sinPhase * 0.22f * fwdComp * amp;
        float lPitchSide = 0.12f * sneak - lLift * 0.48f * amp - sinPhase * 0.22f * fwdComp * amp;

        this.rightLeg.pitch = MathHelper.lerp(backAmount, rPitchSide, rPitchBack);
        this.leftLeg.pitch = MathHelper.lerp(backAmount, lPitchSide, lPitchBack);

        // Боковой мах ног (roll) и боковой шаг (pivotX) при движении боком крадясь:
        // у правой ноги roll > 0 отводит стопу вправо (-X), у левой roll < 0 отводит стопу влево (+X)
        float wideStanceRoll = (0.10f + 0.06f * backAmount) * sneak;
        this.rightLeg.roll = wideStanceRoll + sinPhase * 0.38f * sideAmount * amp;
        this.leftLeg.roll = -wideStanceRoll - sinPhase * 0.38f * sideAmount * amp;

        // Разворот стоп по направлению бокового крадущегося шага
        float legTurnYaw = -moveLeftRight * 0.40f * sideAmount * sneak;
        this.rightLeg.yaw = legTurnYaw + 0.05f * sneak;
        this.leftLeg.yaw = legTurnYaw - 0.05f * sneak;

        // Смещение бёдер/стоп по X (широкая стойка + боковой переступ) и по Z (вынос ноги назад)
        this.rightLeg.pivotX = -1.9f - 0.35f * sideAmount * sneak + sinPhase * 1.05f * sideAmount * amp;
        this.leftLeg.pivotX = 1.9f + 0.35f * sideAmount * sneak - sinPhase * 1.05f * sideAmount * amp;

        this.rightLeg.pivotZ = baseHipZ + rZBack;
        this.leftLeg.pivotZ = baseHipZ + lZBack;

        // Подъём стопы в фазе переноса ноги (в пространстве модели -Y — это вверх)
        this.rightLeg.pivotY = baseHipY - rLift * 1.35f * amp;
        this.leftLeg.pivotY = baseHipY - lLift * 1.35f * amp;

        // 3. Положение рук при крадущемся шаге
        this.rightArm.pivotY = 2.0f + 3.2f * sneak + stepBob * 0.5f;
        this.leftArm.pivotY = 2.0f + 3.2f * sneak + stepBob * 0.5f;

        ClientPlayerEntity player = mc.player;
        if (!BobinWeaponHandler.isHoldingWeapon(player) && !player.isUsingItem()) {
            // Без огнестрельного оружия руки полусогнуты и разведены в стороны для баланса при крадущемся шаге
            this.rightArm.pitch = this.rightArm.pitch * 0.45f + 0.38f * sneak + cosPhase * 0.25f * amp;
            this.leftArm.pitch = this.leftArm.pitch * 0.45f + 0.38f * sneak - cosPhase * 0.25f * amp;
            this.rightArm.roll += 0.22f * sneak;
            this.leftArm.roll -= 0.22f * sneak;
            this.rightArm.yaw = torsoTwist * 0.6f;
            this.leftArm.yaw = torsoTwist * 0.6f;
        } else {
            // С оружием в руках компенсируем наклон приседания, чтобы ствол оставался направленным на курсор
            this.rightArm.pitch += 0.25f * sneak;
            this.leftArm.pitch += 0.25f * sneak;
        }

        this.hat.copyTransform(this.head);
    }
}
