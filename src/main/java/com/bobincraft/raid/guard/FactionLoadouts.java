package com.bobincraft.raid.guard;

import com.bobincraft.warzone.Faction;
import com.bobincraft.warzone.WarzoneBasePlan;
import com.bobincraft.warzone.WarzoneTerrain;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import org.slf4j.LoggerFactory;

/** One bounded faction profile per existing faction; designers can edit the bundled JSON. */
public final class FactionLoadouts {
    public record Profile(NpcWeapon primary, NpcWeapon sentry, NpcWeapon elite, int reactionTicks,
                          double spreadDegrees, int burstSize, int burstPauseTicks, int reserveMagazines,
                          double detectionRange, boolean armored) {
        public NpcFireControl.Settings fireSettings(NpcWeapon weapon, int danger) {
            return new NpcFireControl.Settings(weapon.magazineSize(), reserveMagazines, weapon.intervalTicks(),
                    weapon.reloadTicks(), Math.max(8, reactionTicks - danger * 2), burstSize, burstPauseTicks);
        }
    }
    private static final EnumMap<Faction, Profile> PROFILES = load();
    private FactionLoadouts() {}
    public static Profile forFaction(Faction faction) { return PROFILES.get(faction); }
    public static int danger(WarzoneBasePlan.Site site) {
        int value = switch (site.type) {
            case BANDIT_CAMP, WATCHTOWER -> 0;
            case OUTPOST, BUNKER_COMPLEX -> 1;
            case FORTRESS, MOUNTAIN_FORT -> 2;
        };
        if (site.region == WarzoneTerrain.Region.HIGHLANDS || site.region == WarzoneTerrain.Region.BADLANDS) value++;
        return value;
    }
    public static NpcWeapon choose(Profile profile, WarzoneBasePlan.SpotRole role, int danger, int variant) {
        if (danger >= 2 && variant == 2) return profile.elite;
        return role == WarzoneBasePlan.SpotRole.SENTRY ? profile.sentry : profile.primary;
    }
    private static EnumMap<Faction, Profile> load() {
        EnumMap<Faction, Profile> result = new EnumMap<>(Faction.class);
        Profile fallback = new Profile(NpcWeapon.AK47, NpcWeapon.SPOTTER, NpcWeapon.AK47, 20, 5.0, 2, 18, 3, 42, false);
        try (var stream = FactionLoadouts.class.getResourceAsStream("/data/bobin_craft/warzone/faction_loadouts.json")) {
            if (stream == null) throw new IllegalStateException("Missing faction loadouts");
            JsonObject root = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
            for (Faction faction : Faction.values()) {
                JsonObject p = root.getAsJsonObject(faction.name());
                result.put(faction, new Profile(NpcWeapon.valueOf(p.get("primary").getAsString()), NpcWeapon.valueOf(p.get("sentry").getAsString()),
                        NpcWeapon.valueOf(p.get("elite").getAsString()), bound(p, "reactionTicks", 10, 60),
                        boundDouble(p, "spreadDegrees", 2.0, 12.0), bound(p, "burstSize", 1, 5),
                        bound(p, "burstPauseTicks", 8, 80), bound(p, "reserveMagazines", 1, 5),
                        boundDouble(p, "detectionRange", 20, 48), p.get("armored").getAsBoolean()));
            }
        } catch (Exception error) {
            LoggerFactory.getLogger("bobin_craft").warn("Faction loadouts unavailable; missing profiles use a conservative rifle loadout", error);
        }
        for (Faction faction : Faction.values()) result.putIfAbsent(faction, fallback);
        return result;
    }
    private static double boundDouble(JsonObject p, String name, double min, double max) {
        double value = p.get(name).getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite loadout value: " + name);
        return Math.max(min, Math.min(max, value));
    }
    private static int bound(JsonObject p, String name, int min, int max) { return Math.max(min, Math.min(max, p.get(name).getAsInt())); }
}
