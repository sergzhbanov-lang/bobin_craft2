package com.bobincraft.weapon;

import net.minecraft.block.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.BlockView;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Система простреливания блоков (Wallbang / Bullet Penetration) в зависимости от калибра оружия
 * и физической плотности материала.
 *
 * Категории блоков:
 *  1. FULL_PENETRATION — полный прострел без потери урона (трава, листва, паутина, цветы, посевы,
 *     водная флора, грибы, шерсть, ковры, флаги, кровати, железные прутья, цепи, строительные леса,
 *     лестницы, факелы, фонари, свечи, стержень Энда, морской огурец, горшки, кнопки, плиты,
 *     рычаги, растяжки, редстоун-мелочь, рельсы, а также сущности картин и рамок).
 *  2. Пробиваемые с потерей урона или разрушением:
 *     - FRAGILE_SHATTER (разбиваются от пули): стекло (обычное, цветное, тонированное), стеклянные панели,
 *       обычный и тающий лёд (кроме packed_ice и blue_ice), хрупкие светильники (glowstone, sea_lantern,
 *       redstone_lamp, жабосветы), украшенный горшок, черепа и головы, яйца, аметистовые бутоны/кластеры,
 *       капельник (сталактиты).
 *     - THIN_WOOD (тонкие деревянные и бамбуковые конструкции): таблички, подвесные таблички,
 *       деревянные двери и люки, деревянные заборы и калитки, бамбук и бамбуковая мозаика,
 *       книжные полки, бочки, сундуки, компостер, кафедра, нотный блок, проигрыватель, ульи и гнёзда.
 *     - SOFT_ORGANIC (мягкие, сыпучие и органические блоки — гасят 30–70% урона): снег, сноп сена,
 *       сушёная ламинария, мишень, блок мха, мангровые корни, губки, слизь, мёд, пчелиные соты,
 *       грибные блоки и ножки, блоки незерского нароста, грибосвет, тыквы и арбузы, кактус, хорус,
 *       скалковые блоки, живые и мёртвые кораллы/веера.
 *  3. Спорные блоки (настраиваются под калибр оружия):
 *     - CALIBER_PLANKS: все виды досок, деревянных ступеней и плит (#minecraft:planks, #minecraft:wooden_slabs,
 *       #minecraft:wooden_stairs) — пистолетные 9мм не пробивают, а винтовочные 7.62мм и .50 BMG прошивают.
 *     - CALIBER_SOIL_MAX_1: подозрительный песок/гравий, песок, красный песок, гравий, земля, каменистая
 *       и корнистая земля, грязь, мангровые корни в грязи, глина, песок душ, почва душ —
 *       пробиваются мощными патронами (7.62мм и .50 BMG) на глубину НЕ БОЛЕЕ 1 блока.
 *     - CALIBER_CONCRETE_POWDER: гипсокартон / сухая смесь (все 16 видов цемента *_concrete_powder).
 */
public final class BulletPenetration {

    private BulletPenetration() {}

    /** Visit every voxel crossed by a swept projectile; false stops at the first obstruction. */
    public static boolean visitBlocks(Vec3d from, Vec3d to, Predicate<BlockPos> visitor) {
        return BlockView.raycast(from, to, visitor,
                (visit, pos) -> visit.test(pos) ? null : Boolean.FALSE,
                visit -> Boolean.TRUE);
    }

    /** Минимальный остаточный множитель урона, ниже которого пуля застревает в преграде. */
    public static final float MIN_DAMAGE_MULTIPLIER = 0.15f;

    /**
     * Калибры оружия мода Bobin_craft.
     */
    public enum Caliber {
        /** Дротик с сывороткой (Медик) — пробивает только 1-ю категорию и самые хрупкие стёкла/сосуды. */
        DART("Дротик", false, false, false, 0),
        /** Пистолетный 9×19 мм (Разведчик, Крио-карабин) — пробивает категории 1 и 2, но НЕ пробивает доски/грунт/цемент. */
        PISTOL_9MM("9×19 мм", true, false, false, 0),
        /** Ружейная картечь 12 калибра (Взломщик) — выбивает двери/стёкла, пробивает категории 1–2 и тонкие доски/цемент. */
        SHOTGUN_12G("12 калибр", true, true, true, 0),
        /** Промежуточный винтовочный 7.62×39 мм (АК-47) — прошивает доски, цемент и до 1 блока сыпучего грунта. */
        RIFLE_762("7.62×39 мм", true, true, true, 1),
        /** Крупнокалиберный снайперский .50 BMG (Снайперская винтовка) — максимальная пробивная сила, грунт до 1 блока. */
        HEAVY_50BMG(".50 BMG", true, true, true, 1);

        public final String displayName;
        public final boolean canPierceThinWoodAndSoft;
        public final boolean canPiercePlanks;
        public final boolean canPierceConcretePowder;
        public final int maxSoilBlocks;

        Caliber(String displayName,
                boolean canPierceThinWoodAndSoft,
                boolean canPiercePlanks,
                boolean canPierceConcretePowder,
                int maxSoilBlocks) {
            this.displayName = displayName;
            this.canPierceThinWoodAndSoft = canPierceThinWoodAndSoft;
            this.canPiercePlanks = canPiercePlanks;
            this.canPierceConcretePowder = canPierceConcretePowder;
            this.maxSoilBlocks = maxSoilBlocks;
        }
    }

    /**
     * Категория пробиваемости блока.
     */
    public enum BlockCategory {
        FULL_PENETRATION,
        FRAGILE_SHATTER,
        THIN_WOOD,
        SOFT_ORGANIC,
        CALIBER_PLANKS,
        CALIBER_SOIL_MAX_1,
        CALIBER_CONCRETE_POWDER,
        IMPENETRABLE
    }

    /**
     * Результат прохождения пули через один блок.
     */
    public record StepResult(
            boolean penetrated,
            float newDamageMultiplier,
            int newSoilBlocksPierced,
            boolean shouldBreakBlock,
            BlockCategory category
    ) {}

    /**
     * Определяет калибр для оружия из GunType, АК-47 или снайперской винтовки.
     */
    public static Caliber getCaliber(boolean isSniper, GunType gun) {
        if (gun != null) {
            return switch (gun) {
                case BREACHER -> Caliber.SHOTGUN_12G;
                case SPOTTER, CRYO -> Caliber.PISTOL_9MM;
                case MEDIC -> Caliber.DART;
            };
        }
        return isSniper ? Caliber.HEAVY_50BMG : Caliber.RIFLE_762;
    }

    /**
     * Картины и рамки (painting, item_frame, glow_item_frame) в Майнкрафте являются сущностями
     * (AbstractDecorationEntity) и не должны останавливать пулю.
     */
    public static boolean isPassThroughDecorationEntity(Entity entity) {
        return entity instanceof AbstractDecorationEntity;
    }

    /**
     * Классифицирует блок по категориям прострела 1, 2 (2А/2Б/2В) и 3 (3А/3Б/3В).
     */
    public static BlockCategory classifyBlock(BlockState state) {
        if (state == null || state.isAir()) {
            return BlockCategory.FULL_PENETRATION;
        }

        Block b = state.getBlock();
        if (b instanceof FluidBlock || b == Blocks.WATER || b == Blocks.LAVA || b == Blocks.BUBBLE_COLUMN) {
            return BlockCategory.FULL_PENETRATION;
        }

        // Плотный и синий лёд оставляем непробиваемыми как бетон (проверяем до тега ICE!)
        if (b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE) {
            return BlockCategory.IMPENETRABLE;
        }
        // Железные двери и железные люки непробиваемы
        if (b == Blocks.IRON_DOOR || b == Blocks.IRON_TRAPDOOR) {
            return BlockCategory.IMPENETRABLE;
        }

        // =========================================================================
        // 1. ПОЛНЫЙ ПРОСТРЕЛ (трава, листва, паутина, ткани, сетки, освещение, мелочь)
        // =========================================================================
        if (isFullPenetrationBlock(state, b)) {
            return BlockCategory.FULL_PENETRATION;
        }

        // =========================================================================
        // 2А. ХРУПКИЕ БЛОКИ — СТЕКЛО, ЛЁД, СВЕТИЛЬНИКИ, КЕРАМИКА И КРИСТАЛЛЫ (разбиваются)
        // =========================================================================
        if (isFragileShatterBlock(state, b)) {
            return BlockCategory.FRAGILE_SHATTER;
        }

        // =========================================================================
        // 2Б. ТОНКИЕ ДЕРЕВЯННЫЕ И БАМБУКОВЫЕ КОНСТРУКЦИИ
        // =========================================================================
        if (isThinWoodBlock(state, b)) {
            return BlockCategory.THIN_WOOD;
        }

        // =========================================================================
        // 2В. МЯГКИЕ, СЫПУЧИЕ И ОРГАНИЧЕСКИЕ БЛОКИ (гасят 30–70% урона)
        // =========================================================================
        if (isSoftOrganicBlock(state, b)) {
            return BlockCategory.SOFT_ORGANIC;
        }

        // =========================================================================
        // 3. СПОРНЫЕ БЛОКИ (настраиваются под калибр оружия)
        // =========================================================================
        // 3А. Доски, деревянные ступени и плиты
        if (state.isIn(BlockTags.PLANKS)
                || state.isIn(BlockTags.WOODEN_SLABS)
                || state.isIn(BlockTags.WOODEN_STAIRS)) {
            return BlockCategory.CALIBER_PLANKS;
        }

        // 3Б. Подозрительный песок/гравий, песок, гравий, земля, грязь, глина, песок/почва душ (не более 1 блока)
        if (isCaliberSoilBlock(state, b)) {
            return BlockCategory.CALIBER_SOIL_MAX_1;
        }

        // 3В. Гипсокартон / сухая смесь (все 16 видов цемента *_concrete_powder)
        if (b instanceof ConcretePowderBlock) {
            return BlockCategory.CALIBER_CONCRETE_POWDER;
        }

        return BlockCategory.IMPENETRABLE;
    }

    /**
     * Категория 1: Полный прострел (0% потери урона).
     */
    public static boolean isFullPenetrationBlock(BlockState state) {
        if (state == null || state.isAir()) return true;
        return isFullPenetrationBlock(state, state.getBlock());
    }

    private static boolean isFullPenetrationBlock(BlockState state, Block b) {
        // Растительность и флора:
        // - Все виды листвы (10 видов, включая cherry_leaves, azalea_leaves, flowering_azalea_leaves)
        if (state.isIn(BlockTags.LEAVES) || b instanceof LeavesBlock) return true;
        // - Все виды саженцев и побегов (включая mangrove_propagule, cherry_sapling, кусты азалии)
        if (state.isIn(BlockTags.SAPLINGS) || b instanceof SaplingBlock || b instanceof AzaleaBlock) return true;
        // - Трава и папоротники: grass, tall_grass, fern, large_fern, dead_bush
        if (b == Blocks.GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN || b == Blocks.LARGE_FERN
                || b == Blocks.DEAD_BUSH || b instanceof FernBlock || b instanceof DeadBushBlock) return true;
        // - Цветы (все 19+ видов, включая torchflower, pitcher_plant, pink_petals)
        if (state.isIn(BlockTags.FLOWERS) || state.isIn(BlockTags.SMALL_FLOWERS) || state.isIn(BlockTags.TALL_FLOWERS)
                || b instanceof FlowerBlock || b instanceof FlowerbedBlock
                || b == Blocks.TORCHFLOWER || b == Blocks.TORCHFLOWER_CROP
                || b == Blocks.PITCHER_PLANT || b == Blocks.PITCHER_CROP
                || b == Blocks.PINK_PETALS) return true;
        // - Лианы и лозы: vine, weeping_vines, twisting_vines, cave_vines, glow_lichen
        if (b == Blocks.VINE || b == Blocks.WEEPING_VINES || b == Blocks.WEEPING_VINES_PLANT
                || b == Blocks.TWISTING_VINES || b == Blocks.TWISTING_VINES_PLANT
                || b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT
                || state.isIn(BlockTags.CAVE_VINES)
                || b instanceof VineBlock || b instanceof CaveVines || b instanceof GlowLichenBlock) return true;
        // - Посевы и кусты: wheat, carrots, potatoes, beetroots, sweet_berry_bush, nether_wart, cocoa,
        //   pumpkin_stem, melon_stem, sugar_cane, bamboo_sapling
        if (state.isIn(BlockTags.CROPS) || b instanceof CropBlock || b instanceof StemBlock || b instanceof AttachedStemBlock
                || b == Blocks.WHEAT || b == Blocks.CARROTS || b == Blocks.POTATOES || b == Blocks.BEETROOTS
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.NETHER_WART || b == Blocks.COCOA
                || b == Blocks.PUMPKIN_STEM || b == Blocks.ATTACHED_PUMPKIN_STEM
                || b == Blocks.MELON_STEM || b == Blocks.ATTACHED_MELON_STEM
                || b == Blocks.SUGAR_CANE || b == Blocks.BAMBOO_SAPLING
                || b instanceof SweetBerryBushBlock || b instanceof NetherWartBlock
                || b instanceof CocoaBlock || b instanceof SugarCaneBlock || b instanceof BambooSaplingBlock) return true;
        // - Водная и болотная растительность: lily_pad, big_dripleaf, small_dripleaf, seagrass, tall_seagrass,
        //   kelp, kelp_plant, hanging_roots, spore_blossom
        if (b == Blocks.LILY_PAD || b == Blocks.BIG_DRIPLEAF || b == Blocks.BIG_DRIPLEAF_STEM
                || b == Blocks.SMALL_DRIPLEAF || b == Blocks.SEAGRASS || b == Blocks.TALL_SEAGRASS
                || b == Blocks.KELP || b == Blocks.KELP_PLANT
                || b == Blocks.HANGING_ROOTS || b == Blocks.SPORE_BLOSSOM
                || b instanceof LilyPadBlock || b instanceof BigDripleafBlock || b instanceof BigDripleafStemBlock
                || b instanceof SmallDripleafBlock || b instanceof SeagrassBlock || b instanceof TallSeagrassBlock
                || b instanceof KelpBlock || b instanceof KelpPlantBlock
                || b instanceof HangingRootsBlock || b instanceof SporeBlossomBlock) return true;
        // - Грибы и корни: brown_mushroom, red_mushroom, crimson_fungus, warped_fungus, crimson_roots, warped_roots, nether_sprouts
        if (b == Blocks.BROWN_MUSHROOM || b == Blocks.RED_MUSHROOM
                || b == Blocks.CRIMSON_FUNGUS || b == Blocks.WARPED_FUNGUS
                || b == Blocks.CRIMSON_ROOTS || b == Blocks.WARPED_ROOTS || b == Blocks.NETHER_SPROUTS
                || b instanceof MushroomPlantBlock || b instanceof FungusBlock
                || b instanceof RootsBlock || b instanceof SproutsBlock) return true;

        // Текстиль, бумага и паутина:
        // - Паутина, шерсть (16 цветов), ковры (16 цветов + moss_carpet), флаги (16 цветов), кровати (16 цветов)
        if (b == Blocks.COBWEB || b instanceof CobwebBlock
                || state.isIn(BlockTags.WOOL)
                || state.isIn(BlockTags.WOOL_CARPETS) || b == Blocks.MOSS_CARPET || b instanceof CarpetBlock
                || state.isIn(BlockTags.BANNERS) || b instanceof AbstractBannerBlock
                || state.isIn(BlockTags.BEDS) || b instanceof BedBlock) return true;

        // Сетки, решетки и каркасы:
        // - Железные прутья (iron_bars), цепь (chain), строительные леса (scaffolding), лестница (ladder)
        if (b == Blocks.IRON_BARS
                || b == Blocks.CHAIN || b instanceof ChainBlock
                || b == Blocks.SCAFFOLDING || b instanceof ScaffoldingBlock
                || b == Blocks.LADDER || b instanceof LadderBlock) return true;

        // Освещение и мелкая утварь:
        // - Факелы, фонари, свечи (17 видов), стержень Энда, морской огурец, цветочные горшки
        if (b == Blocks.TORCH || b == Blocks.WALL_TORCH
                || b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH
                || b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_WALL_TORCH
                || b instanceof TorchBlock
                || b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN || b instanceof LanternBlock
                || state.isIn(BlockTags.CANDLES) || state.isIn(BlockTags.CANDLE_CAKES)
                || b instanceof CandleBlock || b instanceof CandleCakeBlock
                || b == Blocks.END_ROD || b instanceof EndRodBlock
                || b == Blocks.SEA_PICKLE || b instanceof SeaPickleBlock
                || state.isIn(BlockTags.FLOWER_POTS) || b instanceof FlowerPotBlock) return true;

        // Редстоун-мелочь и рельсы:
        // - Кнопки, нажимные плиты, рычаг, крюк, растяжка, редстоун-пыль, повторитель, компаратор, скалковая жила, рельсы
        if (state.isIn(BlockTags.BUTTONS) || b instanceof ButtonBlock
                || state.isIn(BlockTags.PRESSURE_PLATES) || b instanceof AbstractPressurePlateBlock
                || b == Blocks.LEVER || b instanceof LeverBlock
                || b == Blocks.TRIPWIRE_HOOK || b instanceof TripwireHookBlock
                || b == Blocks.TRIPWIRE || b instanceof TripwireBlock
                || b == Blocks.REDSTONE_WIRE || b instanceof RedstoneWireBlock
                || b == Blocks.REPEATER || b == Blocks.COMPARATOR || b instanceof AbstractRedstoneGateBlock
                || b == Blocks.SCULK_VEIN || b instanceof SculkVeinBlock
                || state.isIn(BlockTags.RAILS) || b instanceof AbstractRailBlock) return true;

        return false;
    }

    /**
     * Категория 2А: Хрупкие блоки, которые разбиваются при простреле (стекло, лёд, светильники, керамика, кристаллы).
     */
    public static boolean isFragileShatterBlock(BlockState state) {
        if (state == null || state.isAir()) return false;
        return isFragileShatterBlock(state, state.getBlock());
    }

    private static boolean isFragileShatterBlock(BlockState state, Block b) {
        // Обычное, цветное и тонированное стекло + стеклянные панели (кроме iron_bars)
        if (b instanceof AbstractGlassBlock || b == Blocks.TINTED_GLASS) return true;
        if (b instanceof PaneBlock && b != Blocks.IRON_BARS) return true;

        // Обычный и тающий лёд (packed_ice и blue_ice отсечены выше как непробиваемые)
        if (b == Blocks.ICE || b == Blocks.FROSTED_ICE) return true;

        // Хрупкие светящиеся блоки: glowstone, sea_lantern, redstone_lamp, froglights
        if (b == Blocks.GLOWSTONE || b == Blocks.SEA_LANTERN || b == Blocks.REDSTONE_LAMP
                || b == Blocks.OCHRE_FROGLIGHT || b == Blocks.VERDANT_FROGLIGHT || b == Blocks.PEARLESCENT_FROGLIGHT) {
            return true;
        }

        // Хрупкая керамика и кристаллы:
        // - Украшенный горшок (decorated_pot)
        if (b == Blocks.DECORATED_POT || b instanceof DecoratedPotBlock) return true;
        // - Черепа и головы (7 видов + настенные)
        if (b instanceof AbstractSkullBlock) return true;
        // - Яйца (turtle_egg, sniffer_egg, frogspawn)
        if (b == Blocks.TURTLE_EGG || b == Blocks.SNIFFER_EGG || b == Blocks.FROGSPAWN
                || b instanceof TurtleEggBlock || b instanceof SnifferEggBlock || b instanceof FrogspawnBlock) return true;
        // - Аметистовые бутоны и кластеры (small/medium/large_amethyst_bud, amethyst_cluster)
        if (b == Blocks.SMALL_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD
                || b == Blocks.LARGE_AMETHYST_BUD || b == Blocks.AMETHYST_CLUSTER
                || b instanceof AmethystClusterBlock) return true;
        // - Капельник / сталактиты (pointed_dripstone)
        if (b == Blocks.POINTED_DRIPSTONE || b instanceof PointedDripstoneBlock) return true;

        return false;
    }

    /**
     * Категория 2Б: Тонкие деревянные и бамбуковые конструкции.
     */
    private static boolean isThinWoodBlock(BlockState state, Block b) {
        // Все виды табличек и подвесных табличек (11 видов дерева)
        if (state.isIn(BlockTags.ALL_SIGNS) || state.isIn(BlockTags.SIGNS) || state.isIn(BlockTags.ALL_HANGING_SIGNS)
                || b instanceof AbstractSignBlock) return true;
        // Деревянные двери и люки (все кроме железных)
        if (state.isIn(BlockTags.WOODEN_DOORS) || state.isIn(BlockTags.WOODEN_TRAPDOORS)) return true;
        // Деревянные заборы и калитки
        if (state.isIn(BlockTags.WOODEN_FENCES) || state.isIn(BlockTags.FENCE_GATES) || b instanceof FenceGateBlock) return true;
        // Бамбук и бамбуковая мозаика
        if (b == Blocks.BAMBOO || b == Blocks.BAMBOO_MOSAIC
                || b == Blocks.BAMBOO_MOSAIC_SLAB || b == Blocks.BAMBOO_MOSAIC_STAIRS
                || b instanceof BambooBlock) return true;
        // Книжные полки (bookshelf, chiseled_bookshelf)
        if (b == Blocks.BOOKSHELF || b == Blocks.CHISELED_BOOKSHELF || b instanceof ChiseledBookshelfBlock) return true;
        // Пустые/тонкие деревянные контейнеры: barrel, chest, trapped_chest, composter, lectern, note_block, jukebox, beehive, bee_nest
        if (b == Blocks.BARREL || b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST
                || b == Blocks.COMPOSTER || b == Blocks.LECTERN
                || b == Blocks.NOTE_BLOCK || b == Blocks.JUKEBOX
                || b == Blocks.BEEHIVE || b == Blocks.BEE_NEST || state.isIn(BlockTags.BEEHIVES)) return true;

        return false;
    }

    /**
     * Категория 2В: Мягкие, сыпучие и органические блоки (гасят 30–70% урона).
     */
    private static boolean isSoftOrganicBlock(BlockState state, Block b) {
        // Снег: snow, snow_block, powder_snow
        if (b == Blocks.SNOW || b == Blocks.SNOW_BLOCK || b == Blocks.POWDER_SNOW || state.isIn(BlockTags.SNOW)) return true;
        // Сено, сушеная ламинария и мишень
        if (b == Blocks.HAY_BLOCK || b == Blocks.DRIED_KELP_BLOCK || b == Blocks.TARGET) return true;
        // Мох в блоках и мангровые корни
        if (b == Blocks.MOSS_BLOCK || b == Blocks.MANGROVE_ROOTS) return true;
        // Губки
        if (b == Blocks.SPONGE || b == Blocks.WET_SPONGE) return true;
        // Слизь, мёд и пчелиные соты
        if (b == Blocks.SLIME_BLOCK || b == Blocks.HONEY_BLOCK || b == Blocks.HONEYCOMB_BLOCK) return true;
        // Грибные блоки, блоки нароста и грибосвет
        if (b == Blocks.MUSHROOM_STEM || b == Blocks.BROWN_MUSHROOM_BLOCK || b == Blocks.RED_MUSHROOM_BLOCK
                || b == Blocks.NETHER_WART_BLOCK || b == Blocks.WARPED_WART_BLOCK || b == Blocks.SHROOMLIGHT
                || state.isIn(BlockTags.WART_BLOCKS) || b instanceof MushroomBlock) return true;
        // Тыквы и арбузы
        if (b == Blocks.MELON || b == Blocks.PUMPKIN || b == Blocks.CARVED_PUMPKIN || b == Blocks.JACK_O_LANTERN) return true;
        // Кактус и хорус
        if (b == Blocks.CACTUS || b == Blocks.CHORUS_PLANT || b == Blocks.CHORUS_FLOWER) return true;
        // Скалковые блоки
        if (b == Blocks.SCULK || b == Blocks.SCULK_SENSOR || b == Blocks.CALIBRATED_SCULK_SENSOR
                || b == Blocks.SCULK_SHRIEKER || b == Blocks.SCULK_CATALYST) return true;
        // Кораллы (живые и мёртвые кусты и веера, но НЕ каменистые coral_blocks)
        if (state.isIn(BlockTags.CORALS) || state.isIn(BlockTags.CORAL_PLANTS) || state.isIn(BlockTags.WALL_CORALS)
                || b instanceof CoralParentBlock) return true;

        return false;
    }

    /**
     * Категория 3Б: Грунты и сыпучие породы (пробиваются на глубину не более 1 блока мощными патронами).
     */
    private static boolean isCaliberSoilBlock(BlockState state, Block b) {
        return b == Blocks.SUSPICIOUS_SAND
                || b == Blocks.SUSPICIOUS_GRAVEL
                || b == Blocks.SAND
                || b == Blocks.RED_SAND
                || b == Blocks.GRAVEL
                || b == Blocks.DIRT
                || b == Blocks.COARSE_DIRT
                || b == Blocks.ROOTED_DIRT
                || b == Blocks.DIRT_PATH
                || b == Blocks.GRASS_BLOCK
                || b == Blocks.MUD
                || b == Blocks.MUDDY_MANGROVE_ROOTS
                || b == Blocks.CLAY
                || b == Blocks.SOUL_SAND
                || b == Blocks.SOUL_SOIL
                || state.isIn(BlockTags.SAND);
    }

    /**
     * Вычисляет долю потери урона (0.30..0.70) для мягких и органических блоков Категории 2В
     * с учётом плотности конкретного блока и калибра оружия.
     */
    private static float getSoftOrganicDamageLoss(BlockState state, Caliber caliber) {
        Block b = state.getBlock();
        // Базовый коэффициент гашения энергии блоком:
        // - лёгкие пористые (тонкий снег, кораллы, хорус, кактус, мишень, мох, мангровые корни): ~0.35
        // - средние органические (грибные блоки, тыква, арбуз, блок снега, рыхлый снег, губка, соты): ~0.48
        // - вязкие и плотные гасители пули (сноп сена, сушёная ламинария, мокрая губка, слизь, мёд, скалк): ~0.62
        float baseLoss;
        if (b == Blocks.SNOW || b instanceof CoralParentBlock || state.isIn(BlockTags.CORALS)
                || b == Blocks.CHORUS_FLOWER || b == Blocks.CHORUS_PLANT || b == Blocks.CACTUS
                || b == Blocks.MOSS_BLOCK || b == Blocks.MANGROVE_ROOTS || b == Blocks.TARGET) {
            baseLoss = 0.35f;
        } else if (b == Blocks.HAY_BLOCK || b == Blocks.DRIED_KELP_BLOCK || b == Blocks.WET_SPONGE
                || b == Blocks.SLIME_BLOCK || b == Blocks.HONEY_BLOCK
                || b == Blocks.SCULK_CATALYST || b == Blocks.SCULK_SHRIEKER
                || b == Blocks.SCULK_SENSOR || b == Blocks.CALIBRATED_SCULK_SENSOR) {
            baseLoss = 0.62f;
        } else {
            baseLoss = 0.48f;
        }

        // Мощные калибры теряют меньше энергии в мягких преградах, но потеря всегда в диапазоне 30–70%
        float caliberScale = switch (caliber) {
            case HEAVY_50BMG -> 0.72f;
            case RIFLE_762 -> 0.85f;
            case SHOTGUN_12G -> 1.00f;
            case PISTOL_9MM -> 1.12f;
            case DART -> 1.35f;
        };

        return MathHelper.clamp(baseLoss * caliberScale, 0.30f, 0.70f);
    }

    /**
     * Проверяет, может ли блок быть разрушен на сервере при попадании пули заданного калибра.
     */
    public static boolean canServerBreakBlock(BlockState state, Caliber caliber) {
        if (state == null || state.isAir()) return false;
        BlockCategory cat = classifyBlock(state);
        if (cat == BlockCategory.FRAGILE_SHATTER) {
            return true;
        }
        if (cat == BlockCategory.THIN_WOOD) {
            Block b = state.getBlock();
            if (caliber == Caliber.SHOTGUN_12G) {
                return true;
            }
            if (caliber == Caliber.HEAVY_50BMG || caliber == Caliber.RIFLE_762) {
                return state.isIn(BlockTags.ALL_SIGNS) || b instanceof AbstractSignBlock
                        || b == Blocks.BAMBOO || b instanceof BambooBlock;
            }
        }
        return false;
    }

    /**
     * Рассчитывает прохождение пули через один блок.
     */
    public static StepResult evaluateBlockStep(BlockState state,
                                               Caliber caliber,
                                               float currentDamageMultiplier,
                                               int soilBlocksPierced) {
        BlockCategory cat = classifyBlock(state);

        switch (cat) {
            case FULL_PENETRATION -> {
                return new StepResult(true, currentDamageMultiplier, soilBlocksPierced, false, cat);
            }
            case FRAGILE_SHATTER -> {
                float loss = switch (caliber) {
                    case HEAVY_50BMG -> 0.05f;
                    case RIFLE_762 -> 0.12f;
                    case SHOTGUN_12G -> 0.18f;
                    case PISTOL_9MM -> 0.22f;
                    case DART -> 0.55f;
                };
                float nextMult = currentDamageMultiplier * (1.0f - loss);
                boolean cont = nextMult >= MIN_DAMAGE_MULTIPLIER;
                // Хрупкий блок разбивается даже если это была последняя преграда на излёте пули
                return new StepResult(cont, Math.max(0.0f, nextMult), soilBlocksPierced, true, cat);
            }
            case THIN_WOOD -> {
                if (!caliber.canPierceThinWoodAndSoft) {
                    return new StepResult(false, 0.0f, soilBlocksPierced, false, cat);
                }
                float loss = switch (caliber) {
                    case HEAVY_50BMG -> 0.12f;
                    case RIFLE_762 -> 0.22f;
                    case SHOTGUN_12G -> 0.30f;
                    case PISTOL_9MM -> 0.35f;
                    case DART -> 1.0f;
                };
                float nextMult = currentDamageMultiplier * (1.0f - loss);
                boolean cont = nextMult >= MIN_DAMAGE_MULTIPLIER;
                Block b = state.getBlock();
                boolean breakWood = (caliber == Caliber.SHOTGUN_12G)
                        || ((caliber == Caliber.HEAVY_50BMG || caliber == Caliber.RIFLE_762)
                        && (state.isIn(BlockTags.ALL_SIGNS) || b instanceof AbstractSignBlock || b == Blocks.BAMBOO || b instanceof BambooBlock));
                return new StepResult(cont, Math.max(0.0f, nextMult), soilBlocksPierced, breakWood, cat);
            }
            case SOFT_ORGANIC -> {
                if (!caliber.canPierceThinWoodAndSoft) {
                    // Дротик Медика проходит только через тонкий слой снега или кораллы
                    Block b = state.getBlock();
                    if (b == Blocks.SNOW || b instanceof CoralParentBlock || state.isIn(BlockTags.CORALS)) {
                        float nextMult = currentDamageMultiplier * 0.50f;
                        return new StepResult(nextMult >= MIN_DAMAGE_MULTIPLIER, nextMult, soilBlocksPierced, false, cat);
                    }
                    return new StepResult(false, 0.0f, soilBlocksPierced, false, cat);
                }
                float loss = getSoftOrganicDamageLoss(state, caliber); // 0.30 .. 0.70 (30–70% потери урона)
                float nextMult = currentDamageMultiplier * (1.0f - loss);
                boolean cont = nextMult >= MIN_DAMAGE_MULTIPLIER;
                return new StepResult(cont, Math.max(0.0f, nextMult), soilBlocksPierced, false, cat);
            }
            case CALIBER_PLANKS -> {
                if (!caliber.canPiercePlanks) {
                    return new StepResult(false, 0.0f, soilBlocksPierced, false, cat);
                }
                float loss = switch (caliber) {
                    case HEAVY_50BMG -> 0.22f;
                    case RIFLE_762 -> 0.40f;
                    case SHOTGUN_12G -> 0.55f;
                    default -> 1.0f;
                };
                float nextMult = currentDamageMultiplier * (1.0f - loss);
                boolean cont = nextMult >= MIN_DAMAGE_MULTIPLIER;
                return new StepResult(cont, Math.max(0.0f, nextMult), soilBlocksPierced, false, cat);
            }
            case CALIBER_SOIL_MAX_1 -> {
                // Песок, гравий, земля, грязь, глина, песок/почва душ — пробиваются на глубину НЕ БОЛЕЕ 1 блока мощными патронами
                if (soilBlocksPierced >= caliber.maxSoilBlocks) {
                    return new StepResult(false, 0.0f, soilBlocksPierced, false, cat);
                }
                float loss = switch (caliber) {
                    case HEAVY_50BMG -> 0.35f;
                    case RIFLE_762 -> 0.50f;
                    default -> 1.0f;
                };
                float nextMult = currentDamageMultiplier * (1.0f - loss);
                boolean cont = nextMult >= MIN_DAMAGE_MULTIPLIER;
                return new StepResult(cont, Math.max(0.0f, nextMult), soilBlocksPierced + 1, false, cat);
            }
            case CALIBER_CONCRETE_POWDER -> {
                if (!caliber.canPierceConcretePowder) {
                    return new StepResult(false, 0.0f, soilBlocksPierced, false, cat);
                }
                float loss = switch (caliber) {
                    case HEAVY_50BMG -> 0.20f;
                    case RIFLE_762 -> 0.35f;
                    case SHOTGUN_12G -> 0.50f;
                    default -> 1.0f;
                };
                float nextMult = currentDamageMultiplier * (1.0f - loss);
                boolean cont = nextMult >= MIN_DAMAGE_MULTIPLIER;
                return new StepResult(cont, Math.max(0.0f, nextMult), soilBlocksPierced, false, cat);
            }
            default -> {
                return new StepResult(false, 0.0f, soilBlocksPierced, false, cat);
            }
        }
    }

    /**
     * Быстрая проверка, может ли свежевыпущенная пуля данного калибра пробить одиночный блок.
     */
    public static boolean isPenetrableByCaliber(BlockState state, Caliber caliber) {
        return evaluateBlockStep(state, caliber, 1.0f, 0).penetrated();
    }

    /**
     * Трассировка луча (для лазерного прицела и расчёта конечной точки полёта пули)
     * с учётом калибра, потери убойной силы и лимита в 1 блок для сыпучих грунтов.
     *
     * @param stopAtAimTargetIfImpenetrable если true (для лазерного луча), луч останавливается в aimTarget,
     *                                      когда игрок целится в непробиваемую поверхность пола/стены.
     */
    public static Vec3d traceRayWithCaliber(World world,
                                            Vec3d start,
                                            Vec3d aimTarget,
                                            double maxRange,
                                            Caliber caliber,
                                            boolean stopAtAimTargetIfImpenetrable) {
        if (world == null) return aimTarget;

        Vec3d diff = aimTarget.subtract(start);
        double aimDist = diff.length();
        if (aimDist < 1.0e-4) return aimTarget;
        Vec3d dir = diff.multiply(1.0 / aimDist);

        double step = 0.25;
        double dist = 0.0;
        Vec3d current = start;

        float damageMult = 1.0f;
        int soilPierced = 0;
        Set<BlockPos> visited = new HashSet<>();
        visited.add(BlockPos.ofFloored(start));

        while (dist < maxRange) {
            current = current.add(dir.multiply(step));
            dist += step;

            BlockPos pos = BlockPos.ofFloored(current);
            if (visited.add(pos)) {
                BlockState state = world.getBlockState(pos);
                if (!state.isAir()) {
                    StepResult sr = evaluateBlockStep(state, caliber, damageMult, soilPierced);
                    if (!sr.penetrated()) {
                        return current;
                    }
                    damageMult = sr.newDamageMultiplier();
                    soilPierced = sr.newSoilBlocksPierced();
                }
            }

            if (stopAtAimTargetIfImpenetrable && dist >= aimDist) {
                return aimTarget;
            }
        }

        return start.add(dir.multiply(maxRange));
    }

    /**
     * Клиентский визуальный эффект разрушения/прошития блока пулей.
     */
    public static void playClientPenetrationEffects(World world, BlockPos pos, Vec3d hitPos, BlockState state, boolean shattered) {
        if (world == null || state == null || state.isAir()) return;

        for (int i = 0; i < (shattered ? 10 : 5); i++) {
            world.addParticle(
                    new BlockStateParticleEffect(ParticleTypes.BLOCK, state),
                    hitPos.x, hitPos.y, hitPos.z,
                    (Math.random() - 0.5) * 0.25,
                    (Math.random() - 0.5) * 0.25,
                    (Math.random() - 0.5) * 0.25
            );
        }

        if (shattered) {
            world.playSound(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    state.getSoundGroup().getBreakSound(),
                    SoundCategory.BLOCKS,
                    0.9f, 1.0f, false
            );
            world.breakBlock(pos, false);
        } else {
            world.playSound(
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    state.getSoundGroup().getHitSound(),
                    SoundCategory.BLOCKS,
                    0.65f, 1.2f, false
            );
        }
    }
}
