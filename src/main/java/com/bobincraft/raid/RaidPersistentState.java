package com.bobincraft.raid;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Save-scoped state. Inventories remain Minecraft inventories; never snapshot or grant them on return. */
public final class RaidPersistentState extends PersistentState {
    public static final int VERSION = 1;
    public boolean hubInitialized;
    /** 0 = legacy bunker; 2 = forest outpost. Never migrate existing placed blocks. */
    public int hubDesign;
    public boolean extractionMarked;
    public long worldSeed;
    public int generationVersion;
    public int arenaSize;
    private NbtCompound unsupportedData;
    public final Set<Integer> activatedEncounters = new HashSet<>();
    private final Map<UUID, PlayerRaid> players = new HashMap<>();

    public static final class PlayerRaid {
        public boolean initialized;
        public boolean active;
        public long rotation;
        public int selected = -1;
        public int completed;
        public final Set<Integer> discovered = new HashSet<>();
        public List<DeploymentPlanner.Point> choices = List.of();

        public void finish(boolean success) {
            active = false;
            selected = -1;
            if (success) completed++;
            rotation++;
            choices = List.of();
        }
    }

    public static RaidPersistentState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(
                RaidPersistentState::read, RaidPersistentState::new, "bobin_craft_raids");
    }

    public boolean isSupported() { return unsupportedData == null; }

    public PlayerRaid player(UUID uuid) {
        return players.computeIfAbsent(uuid, ignored -> new PlayerRaid());
    }

    public void invalidatePlans() {
        players.values().forEach(raid -> { raid.choices = List.of(); raid.rotation++; });
        markDirty();
    }

    public static RaidPersistentState read(NbtCompound nbt) {
        int version = nbt.getInt("Version");
        RaidPersistentState state = new RaidPersistentState();
        // PersistentStateManager treats a reader exception as a missing file. Preserve unknown
        // data instead, so an older mod can never silently rebuild a newer save's hub.
        if (version > VERSION) {
            state.unsupportedData = nbt.copy();
            return state;
        }
        state.hubInitialized = nbt.getBoolean("HubInitialized");
        state.hubDesign = Math.max(0, nbt.getInt("HubDesign"));
        state.extractionMarked = nbt.getBoolean("ExtractionMarked");
        state.worldSeed = nbt.getLong("WorldSeed");
        state.generationVersion = nbt.getInt("GenerationVersion");
        state.arenaSize = nbt.getInt("ArenaSize");
        for (int id : nbt.getIntArray("Encounters")) state.activatedEncounters.add(id);
        NbtList players = nbt.getList("Players", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < players.size(); i++) {
            NbtCompound entry = players.getCompound(i);
            if (!entry.containsUuid("UUID")) continue;
            PlayerRaid raid = new PlayerRaid();
            raid.initialized = entry.getBoolean("Initialized");
            raid.active = entry.getBoolean("Active");
            raid.rotation = entry.getLong("Rotation");
            raid.selected = entry.contains("Selected") ? entry.getInt("Selected") : -1;
            raid.completed = entry.getInt("Completed");
            for (int id : entry.getIntArray("Discovered")) raid.discovered.add(id);
            List<DeploymentPlanner.Point> choices = new ArrayList<>();
            NbtList list = entry.getList("Choices", NbtElement.COMPOUND_TYPE);
            for (int j = 0; j < list.size() && j < 3; j++) choices.add(readPoint(list.getCompound(j)));
            if (choices.size() == 3 && choices.stream().map(DeploymentPlanner.Point::id).distinct().count() == 3)
                raid.choices = List.copyOf(choices);
            state.players.put(entry.getUuid("UUID"), raid);
        }
        return state;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        if (unsupportedData != null) return unsupportedData.copy();
        nbt.putInt("Version", VERSION);
        nbt.putBoolean("HubInitialized", hubInitialized);
        nbt.putInt("HubDesign", hubDesign);
        nbt.putBoolean("ExtractionMarked", extractionMarked);
        nbt.putLong("WorldSeed", worldSeed);
        nbt.putInt("GenerationVersion", generationVersion);
        nbt.putInt("ArenaSize", arenaSize);
        nbt.putIntArray("Encounters", activatedEncounters.stream().sorted().mapToInt(Integer::intValue).toArray());
        NbtList entries = new NbtList();
        players.forEach((uuid, raid) -> {
            NbtCompound entry = new NbtCompound();
            entry.putUuid("UUID", uuid);
            entry.putBoolean("Initialized", raid.initialized);
            entry.putBoolean("Active", raid.active);
            entry.putLong("Rotation", raid.rotation);
            entry.putInt("Selected", raid.selected);
            entry.putInt("Completed", raid.completed);
            entry.putIntArray("Discovered", raid.discovered.stream().sorted().mapToInt(Integer::intValue).toArray());
            NbtList choices = new NbtList();
            raid.choices.forEach(point -> choices.add(writePoint(point)));
            entry.put("Choices", choices);
            entries.add(entry);
        });
        nbt.put("Players", entries);
        return nbt;
    }

    private static NbtCompound writePoint(DeploymentPlanner.Point p) {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("Id", p.id()); nbt.putInt("X", p.x()); nbt.putInt("Y", p.y()); nbt.putInt("Z", p.z());
        nbt.putInt("Danger", p.danger()); nbt.putString("Label", p.label());
        return nbt;
    }

    private static DeploymentPlanner.Point readPoint(NbtCompound nbt) {
        return new DeploymentPlanner.Point(nbt.getInt("Id"), nbt.getInt("X"), nbt.getInt("Y"), nbt.getInt("Z"),
                nbt.getInt("Danger"), nbt.getString("Label"));
    }
}
