package com.bobincraft.raid.client;

import com.bobincraft.raid.guard.WarzoneGuardEntity;
import com.bobincraft.warzone.Faction;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.util.Identifier;

import java.util.Locale;

/** Faction identity belongs to the tracked entity data rather than its display name. */
public final class WarzoneGuardRenderer extends MobEntityRenderer<WarzoneGuardEntity, WarzoneGuardModel> {
    private static final Identifier[][] TEXTURES = new Identifier[Faction.values().length][3];

    static {
        for (Faction faction : Faction.values()) {
            for (int variant = 0; variant < 3; variant++) {
                TEXTURES[faction.ordinal()][variant] = new Identifier("bobin_craft",
                        "textures/entity/faction/" + faction.name().toLowerCase(Locale.ROOT) + "_" + variant + ".png");
            }
        }
    }

    public WarzoneGuardRenderer(EntityRendererFactory.Context context) {
        super(context, new WarzoneGuardModel(WarzoneGuardModel.getTexturedModelData().createModel()), 0.45F);
        addFeature(new HeldItemFeatureRenderer<>(this, context.getHeldItemRenderer()));
    }

    @Override
    public Identifier getTexture(WarzoneGuardEntity guard) {
        return TEXTURES[guard.faction().ordinal()][Math.floorMod(guard.appearanceVariant(), 3)];
    }
}
