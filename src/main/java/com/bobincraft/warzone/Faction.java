package com.bobincraft.warzone;

/**
 * Внутренние «фракции» вражеских объектов Зоны боевых действий (как банды мародёров, только для всех типов баз).
 * Фракция задаёт материалы, стиль ограды, цвет флага и набор дополнительных построек, поэтому
 * две базы одного типа выглядят по-разному. Чистая логика без Minecraft — материалы подбирает
 * {@code WarzoneStructures.Palette}.
 */
public enum Faction {
    REGULAR_ARMY("Регулярная армия", WallStyle.STONE, new Extra[]{Extra.HOSPITAL, Extra.MORTAR, Extra.RADIO}),
    IRON_WOLVES("Железные волки", WallStyle.STONE, new Extra[]{Extra.MORTAR, Extra.RADIO, Extra.MORTAR}),
    GLASS_COMPANY("ЧВК «Стекло»", WallStyle.SANDBAG, new Extra[]{Extra.HELIPAD, Extra.RADIO, Extra.HOSPITAL}),
    FROST_CORPS("Горный корпус", WallStyle.STONE, new Extra[]{Extra.RADIO, Extra.MORTAR, Extra.HOSPITAL}),
    ASH_RAIDERS("Пепельные рейдеры", WallStyle.STOCKADE, new Extra[]{Extra.TOTEM, Extra.MORTAR, Extra.TOTEM}),
    BLACK_CROWS("Чёрные вороны", WallStyle.STOCKADE, new Extra[]{Extra.TOTEM, Extra.RADIO, Extra.TOTEM}),
    MARSH_REAVERS("Болотные потрошители", WallStyle.STOCKADE, new Extra[]{Extra.TOTEM, Extra.HOSPITAL, Extra.TOTEM});

    /** Стиль внешней ограды базы. */
    public enum WallStyle { STONE, STOCKADE, SANDBAG }

    /** Дополнительные модули, которыми фракция обустраивает свободные участки двора. */
    public enum Extra { MORTAR, RADIO, HOSPITAL, HELIPAD, TOTEM }

    public final String title;
    public final WallStyle wallStyle;
    public final Extra[] kit;

    Faction(String title, WallStyle wallStyle, Extra[] kit) {
        this.title = title;
        this.wallStyle = wallStyle;
        this.kit = kit;
    }

    public boolean isBandit() {
        return this == ASH_RAIDERS || this == BLACK_CROWS || this == MARSH_REAVERS;
    }
}
