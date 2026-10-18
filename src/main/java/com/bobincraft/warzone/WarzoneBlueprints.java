package com.bobincraft.warzone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Кэш чертежей мостов (и, в следующих этапах, баз) одного измерения. Чертёж каждого объекта строится один раз,
 * лениво и детерминированно из seed и плана; чанки только вставляют свой срез.
 */
public final class WarzoneBlueprints {
    private static final int BRIDGE_MARGIN = 28;   // хвосты оси (14) + дуга (7) + запас

    private final WarzoneTerrain terrain;
    private final Map<WarzoneBasePlan.Bridge, Integer> bridgeIndex = new IdentityHashMap<>();
    private final ConcurrentHashMap<Integer, Optional<BridgeBlueprint>> bridges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Optional<Blueprint>> towers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Optional<Blueprint>> hideoutBps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Optional<Blueprint>> checkpointBps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Optional<Blueprint>> battlefieldBps = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

    WarzoneBlueprints(WarzoneTerrain terrain) {
        this.terrain = terrain;
        List<WarzoneBasePlan.Bridge> list = terrain.basePlan().bridges();
        for (int i = 0; i < list.size(); i++) bridgeIndex.put(list.get(i), i);
        String planErr = terrain.hideoutPlanError();
        if (planErr != null) errors.add("ERR hideout plan: " + planErr);
        String poiErr = terrain.poiPlanError();
        if (poiErr != null) errors.add("ERR poi plan: " + poiErr);
    }

    /** Чертёж базы/аванпоста/бункера/лагеря/крепости/блокпоста по объекту плана или null (ошибка построения). */
    public Blueprint site(WarzoneBasePlan.Site site) {
        return towers.computeIfAbsent(site.id, i -> {
            try {
                return Optional.of(SiteBuilder.build(terrain, site));
            } catch (Throwable e) {
                errors.add("ERR site #" + site.id + " " + site.type + ": " + e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Чертёж лесного укрытия или null (ошибка построения). */
    public Blueprint hideout(HideoutPlan.Hideout h) {
        return hideoutBps.computeIfAbsent(h.id(), i -> {
            try {
                return Optional.of(HideoutBuilder.build(terrain, h));
            } catch (Throwable e) {
                errors.add("ERR hideout #" + h.id() + ": " + e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Чертёж придорожного блокпоста или null (ошибка построения). */
    public Blueprint checkpoint(PoiPlan.Checkpoint c) {
        return checkpointBps.computeIfAbsent(c.id(), i -> {
            try {
                return Optional.of(CheckpointBuilder.build(terrain, c));
            } catch (Throwable e) {
                errors.add("ERR checkpoint #" + c.id() + ": " + e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Чертёж старого поля боя или null (ошибка построения). */
    public Blueprint battlefield(PoiPlan.Battlefield f) {
        return battlefieldBps.computeIfAbsent(f.id(), i -> {
            try {
                return Optional.of(BattlefieldBuilder.build(terrain, f));
            } catch (Throwable e) {
                errors.add("ERR battlefield #" + f.id() + ": " + e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** Чертежи баз, укрытий, блокпостов и полей боёв, пересекающие чанк (угол чанка — chunkMinX/Z). */
    public List<Blueprint> poisForChunk(int chunkMinX, int chunkMinZ) {
        List<Blueprint> out = new ArrayList<>(2);
        for (WarzoneBasePlan.Site s : terrain.basePlan().sitesForChunk(chunkMinX, chunkMinZ, 2)) {
            Blueprint bp = site(s);
            if (bp != null && bp.intersectsChunk(chunkMinX, chunkMinZ)) out.add(bp);
        }
        for (HideoutPlan.Hideout h : terrain.hideoutPlan().forChunk(chunkMinX, chunkMinZ, 2)) {
            Blueprint bp = hideout(h);
            if (bp != null && bp.intersectsChunk(chunkMinX, chunkMinZ)) out.add(bp);
        }
        for (PoiPlan.Checkpoint c : terrain.poiPlan().checkpointsForChunk(chunkMinX, chunkMinZ, 2)) {
            Blueprint bp = checkpoint(c);
            if (bp != null && bp.intersectsChunk(chunkMinX, chunkMinZ)) out.add(bp);
        }
        for (PoiPlan.Battlefield f : terrain.poiPlan().battlefieldsForChunk(chunkMinX, chunkMinZ, 2)) {
            Blueprint bp = battlefield(f);
            if (bp != null && bp.intersectsChunk(chunkMinX, chunkMinZ)) out.add(bp);
        }
        return out;
    }

    /** Чертёж моста по индексу в плане или null (мост отклонён: нет берега, нет воды и т.п.). */
    public BridgeBlueprint bridge(int index) {
        return bridges.computeIfAbsent(index, i -> {
            try {
                String[] why = new String[1];
                BridgeBlueprint bb = BridgeBuilder.build(terrain, terrain.basePlan().bridges().get(i), i, why);
                if (bb == null) errors.add("INFO bridge #" + i + " rejected: " + why[0]);
                return Optional.ofNullable(bb);
            } catch (Throwable e) {
                errors.add("ERR bridge #" + i + ": " + e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    public BridgeBlueprint bridge(WarzoneBasePlan.Bridge planBridge) {
        Integer i = bridgeIndex.get(planBridge);
        return i == null ? null : bridge(i);
    }

    /** Чертежи мостов, чей bbox пересекает чанк (угол чанка — chunkMinX/Z). */
    public List<BridgeBlueprint> bridgesForChunk(int chunkMinX, int chunkMinZ) {
        List<WarzoneBasePlan.Bridge> near = terrain.basePlan().bridgesForChunk(chunkMinX, chunkMinZ, BRIDGE_MARGIN);
        if (near.isEmpty()) return Collections.emptyList();
        List<BridgeBlueprint> out = new ArrayList<>(near.size());
        for (WarzoneBasePlan.Bridge pb : near) {
            BridgeBlueprint bb = bridge(pb);
            if (bb != null && bb.blueprint.intersectsChunk(chunkMinX, chunkMinZ)) out.add(bb);
        }
        return out;
    }

    /** Сообщение об отклонённом/упавшем построении (по одному за вызов) или null. */
    public String pollMessage() {
        return errors.poll();
    }
}
