package com.bobincraft.raid.guard;

import com.bobincraft.raid.GunfireThrottle;
import java.util.UUID;

/** Dependency-free checks runnable with javac/java while the game runtime is unavailable. */
public final class GuardBrainTest {
    private static int checks;
    public static void main(String[] args) {
        GuardBrain brain = new GuardBrain();
        brain.update(false, true, false, 10);
        check(brain.state() == GuardBrain.State.IDLE, "sentry holds its post");
        brain.hearNoise();
        check(brain.state() == GuardBrain.State.SUSPICIOUS, "noise raises suspicion");
        brain.update(false, true, false, 10);
        check(brain.state() == GuardBrain.State.INVESTIGATING, "noise does not grant a visible target");
        for (int i = 0; i < 18; i++) brain.update(false, true, false, 10);
        check(brain.state() == GuardBrain.State.IDLE, "unconfirmed noise expires");
        brain.update(true, true, true, 10);
        check(brain.state() == GuardBrain.State.SUSPICIOUS, "first glimpse is not instant combat");
        brain.update(true, true, true, 10);
        check(brain.state() == GuardBrain.State.ALERTED, "sustained sight alerts");
        brain.update(true, true, true, 10);
        check(brain.state() == GuardBrain.State.IN_COMBAT, "confirmed target enters combat");
        brain.update(false, false, true, 10);
        check(brain.state() == GuardBrain.State.SEARCHING, "lost sight searches last known location");
        for (int i = 0; i < 13; i++) brain.update(false, false, true, 10);
        check(brain.state() == GuardBrain.State.RETURNING_TO_POST, "combat memory expires");
        brain.update(false, true, true, 10);
        check(brain.state() == GuardBrain.State.PATROLLING, "patrol resumes after return");
        brain.update(false, false, true, 10);
        check(brain.state() == GuardBrain.State.PATROLLING, "patrol can leave its post for an assigned waypoint");
        check(GuardBrain.insideFieldOfView(0, 20, 0, 0.25), "yaw zero sees south");
        check(!GuardBrain.insideFieldOfView(0, -20, 0, 0.25), "distant rear target hidden");
        check(GuardBrain.insideFieldOfView(-20, 0, 90, 0.25), "yaw ninety sees west");
        check(GuardBrain.insideFieldOfView(0, -2, 0, 0.25), "very close threats can be heard behind");
        check(!GuardBrain.insideFieldOfView(0, -3, 0, 0.25), "close-awareness radius has a finite boundary");
        brain.hearNoise();
        brain.update(false, false, true, 10);
        GuardBrain loaded = new GuardBrain();
        loaded.restore(brain.snapshot());
        check(loaded.state() == GuardBrain.State.INVESTIGATING, "awareness survives reload");
        for (int i = 0; i < 17; i++) loaded.update(false, false, true, 10);
        check(loaded.state() == GuardBrain.State.RETURNING_TO_POST, "reloaded investigation still expires");
        GunfireThrottle throttle = new GunfireThrottle();
        UUID shooter = new UUID(1, 2);
        check(throttle.accept(shooter, 100), "first muzzle sound accepted");
        check(!throttle.accept(shooter, 100), "pellets coalesced in the same game tick");
        check(!throttle.accept(shooter, 103), "nearby duplicate packet coalesced");
        check(throttle.accept(shooter, 104), "next sound accepted after four ticks");
        check(throttle.accept(new UUID(3, 4), 104), "another multiplayer shooter is independent");
        check(new GunfireThrottle().accept(shooter, 104), "world sound budgets are independent");
        check(throttle.accept(shooter, 2), "a replaced clock cannot suppress sound forever");
        System.out.println("GuardBrain and gunfire throttle: " + checks + " checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
