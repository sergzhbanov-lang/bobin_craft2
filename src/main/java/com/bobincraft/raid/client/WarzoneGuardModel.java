package com.bobincraft.raid.client;

import com.bobincraft.raid.guard.WarzoneGuardEntity;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.BipedEntityModel;

/** Original humanoid geometry using the documented 64 by 64 faction skin atlas. */
public final class WarzoneGuardModel extends BipedEntityModel<WarzoneGuardEntity> {
    private final ModelPart backpack;

    public WarzoneGuardModel(ModelPart root) {
        super(root);
        backpack = body.getChild("backpack");
    }

    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();
        root.addChild("head", ModelPartBuilder.create().uv(0, 0)
                .cuboid(-4, -8, -4, 8, 8, 8), ModelTransform.NONE);
        root.addChild("hat", ModelPartBuilder.create().uv(32, 0)
                .cuboid(-4, -8, -4, 8, 8, 8, new Dilation(0.35F)), ModelTransform.NONE);
        ModelPartData body = root.addChild("body", ModelPartBuilder.create().uv(16, 16)
                .cuboid(-4, 0, -2, 8, 12, 4)
                .uv(16, 32).cuboid(-4, 1, -2, 8, 10, 4, new Dilation(0.30F)), ModelTransform.NONE);
        // This small 4 x 6 x 2 pack fits below the shoulder pad in the atlas.
        body.addChild("backpack", ModelPartBuilder.create().uv(40, 40)
                .cuboid(-2, 2, 2.15F, 4, 6, 2), ModelTransform.NONE);
        root.addChild("right_arm", ModelPartBuilder.create().uv(40, 16)
                .cuboid(-3, -2, -2, 4, 12, 4)
                .uv(40, 32).cuboid(-3, -2, -2, 4, 4, 4, new Dilation(0.20F)),
                ModelTransform.pivot(-5, 2, 0));
        root.addChild("left_arm", ModelPartBuilder.create().uv(32, 48)
                .cuboid(-1, -2, -2, 4, 12, 4)
                .uv(48, 48).cuboid(-1, -2, -2, 4, 4, 4, new Dilation(0.20F)),
                ModelTransform.pivot(5, 2, 0));
        root.addChild("right_leg", ModelPartBuilder.create().uv(0, 16)
                .cuboid(-2, 0, -2, 4, 12, 4)
                .uv(0, 32).cuboid(-2, 7, -2, 4, 5, 4, new Dilation(0.12F)),
                ModelTransform.pivot(-1.9F, 12, 0));
        root.addChild("left_leg", ModelPartBuilder.create().uv(16, 48)
                .cuboid(-2, 0, -2, 4, 12, 4)
                .uv(0, 48).cuboid(-2, 7, -2, 4, 5, 4, new Dilation(0.12F)),
                ModelTransform.pivot(1.9F, 12, 0));
        return TexturedModelData.of(data, 64, 64);
    }

    @Override
    public void setAngles(WarzoneGuardEntity guard, float limbAngle, float limbDistance,
                          float animationProgress, float headYaw, float headPitch) {
        rightArmPose = ArmPose.EMPTY;
        leftArmPose = ArmPose.EMPTY;
        super.setAngles(guard, limbAngle, limbDistance, animationProgress, headYaw, headPitch);
        backpack.visible = Math.floorMod(guard.appearanceVariant(), 3) == 2;
        if (!guard.getMainHandStack().isEmpty()) {
            ModelPart main = guard.isLeftHanded() ? leftArm : rightArm;
            ModelPart support = guard.isLeftHanded() ? rightArm : leftArm;
            float side = guard.isLeftHanded() ? -1 : 1;
            // MobEntity's attacking flag is synchronized; target references are server-only.
            if (guard.isAttacking() || guard.getTarget() != null) {
                main.pitch = -1.5708F + head.pitch;
                main.yaw = head.yaw - side * 0.20F;
                support.pitch = -1.43F + head.pitch;
                support.yaw = head.yaw + side * 0.48F;
            } else {
                main.pitch = -0.68F + main.pitch * 0.14F;
                support.pitch = -0.88F + support.pitch * 0.14F;
                main.yaw = -side * 0.10F;
                support.yaw = side * 0.42F;
            }
            main.roll = 0;
            support.roll = 0;
        }
    }
}
