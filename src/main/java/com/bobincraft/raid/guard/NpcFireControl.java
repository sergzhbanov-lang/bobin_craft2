package com.bobincraft.raid.guard;

/** Pure server-tick magazine/aim state. No wall-clock time, client input or frame callbacks. */
public final class NpcFireControl {
    public record Settings(int magazineSize, int reserveMagazines, int intervalTicks, int reloadTicks,
                           int reactionTicks, int burstSize, int burstPauseTicks) {
        public Settings {
            if (magazineSize < 1 || magazineSize > 64 || reserveMagazines < 0 || reserveMagazines > 8
                    || intervalTicks < 1 || reloadTicks < 1 || reactionTicks < 1
                    || burstSize < 1 || burstSize > 8 || burstPauseTicks < 0) throw new IllegalArgumentException("Invalid NPC fire settings");
        }
    }
    public record Snapshot(int rounds, int reserve, int cooldown, int reloading, int aiming, int burst) {}
    private final Settings settings;
    private int rounds, reserve, cooldown, reloading, aiming, burst;

    public NpcFireControl(Settings settings) {
        this.settings = settings;
        rounds = settings.magazineSize;
        reserve = rounds * settings.reserveMagazines;
    }

    /** Called exactly once each server tick, including ticks with no visible target. */
    public boolean tick(boolean clearShot) {
        if (cooldown > 0) cooldown--;
        if (!clearShot) { aiming = 0; burst = 0; }
        if (reloading > 0) {
            if (--reloading == 0) {
                rounds = Math.min(settings.magazineSize, reserve);
                reserve -= rounds;
            }
            return false;
        }
        if (rounds == 0) {
            if (reserve > 0) reloading = settings.reloadTicks;
            return false;
        }
        if (!clearShot || ++aiming < settings.reactionTicks || cooldown > 0) return false;
        if (burst == 0) burst = settings.burstSize;
        rounds--;
        burst--;
        cooldown = settings.intervalTicks + (burst == 0 ? settings.burstPauseTicks : 0);
        return true;
    }

    public Snapshot snapshot() { return new Snapshot(rounds, reserve, cooldown, reloading, aiming, burst); }
    public void restore(Snapshot saved) {
        rounds = clamp(saved.rounds, 0, settings.magazineSize);
        reserve = clamp(saved.reserve, 0, settings.magazineSize * settings.reserveMagazines);
        cooldown = clamp(saved.cooldown, 0, settings.intervalTicks + settings.burstPauseTicks);
        reloading = clamp(saved.reloading, 0, settings.reloadTicks);
        // A reloaded chunk must reacquire sight before firing; magazine/reload progress survives.
        aiming = 0;
        burst = clamp(saved.burst, 0, settings.burstSize);
    }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
