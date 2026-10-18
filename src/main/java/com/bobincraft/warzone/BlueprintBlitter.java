package com.bobincraft.warzone;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Единственное место, где чертёж ({@link Blueprint}) превращается в блоки мира: вставляет в чанк только свой срез.
 * Результат не зависит от порядка генерации чанков. Соединения заборов уже посчитаны в чертеже, поэтому на время
 * вставки учёт «connectables» в ChunkWriter отключается (старый reconnect по чанку не должен их менять).
 */
final class BlueprintBlitter {
    private static final Logger LOGGER = LoggerFactory.getLogger("bobin_craft/warzone");
    private static final ConcurrentHashMap<BpSpec, BlockState> CACHE = new ConcurrentHashMap<>();

    private BlueprintBlitter() {}

    static void blit(Blueprint bp, ChunkWriter w) {
        if (!bp.intersectsChunk(w.minX, w.minZ)) return;
        boolean wasTracking = w.isTrackingConnectables();
        w.setTrackConnectables(false);
        try {
            bp.forEachInChunk(w.minX, w.minZ, (x, y, z, spec, mode) -> {
                BlockState st = state(spec);
                if (mode == Blueprint.Mode.SET) {
                    w.set(x, y, z, st);
                } else {
                    w.setIfAir(x, y, z, st);
                }
            });
            for (Blueprint.BlockEntity be : bp.blockEntities()) {
                if (!w.contains(be.x(), be.y(), be.z())) continue;
                Direction facing = be.facing() == null ? null : Direction.byName(be.facing());
                if (facing == null) facing = Direction.NORTH;
                Identifier loot = be.loot() == null ? null : new Identifier(be.loot());
                switch (be.kind()) {
                    case CHEST -> w.setChest(be.x(), be.y(), be.z(), facing, loot, be.seed());
                    case BARREL -> w.setBarrel(be.x(), be.y(), be.z(), facing, loot, be.seed());
                    case CAMPFIRE -> w.setCampfire(be.x(), be.y(), be.z(), be.lit(), be.signal());
                }
            }
        } finally {
            w.setTrackConnectables(wasTracking);
        }
    }

    static BlockState state(BpSpec spec) {
        BlockState cached = CACHE.get(spec);
        if (cached != null) return cached;
        BlockState st = parse(spec);
        CACHE.put(spec, st);
        return st;
    }

    private static BlockState parse(BpSpec spec) {
        Block block = Registries.BLOCK.get(new Identifier("minecraft:" + spec.id));
        if (block == Blocks.AIR && !spec.isAir()) {
            LOGGER.error("[Warzone] Неизвестный блок в чертеже: {}", spec);
            return Blocks.AIR.getDefaultState();
        }
        BlockState st = block.getDefaultState();
        for (int i = 0; i < spec.propCount(); i++) {
            Property<?> p = block.getStateManager().getProperty(spec.key(i));
            if (p == null) {
                LOGGER.warn("[Warzone] У блока {} нет свойства '{}' (чертёж: {})", spec.id, spec.key(i), spec);
                continue;
            }
            st = withProperty(st, p, spec.val(i));
        }
        return st;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState st, Property<T> p, String value) {
        Optional<T> v = p.parse(value);
        if (v.isEmpty()) {
            LOGGER.warn("[Warzone] Недопустимое значение '{}' свойства '{}' блока {}", value, p.getName(), st.getBlock());
            return st;
        }
        return st.with(p, v.get());
    }
}
