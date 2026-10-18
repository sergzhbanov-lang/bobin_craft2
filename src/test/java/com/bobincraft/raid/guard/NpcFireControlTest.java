package com.bobincraft.raid.guard;

/** Gameplay timing and save/reload contracts runnable without Minecraft or a graphical runtime. */
public final class NpcFireControlTest {
    private static int checks;
    public static void main(String[] args) {
        NpcFireControl.Settings settings = new NpcFireControl.Settings(3, 1, 4, 10, 5, 2, 6);
        NpcFireControl gun = new NpcFireControl(settings);
        for (int i = 0; i < 4; i++) check(!gun.tick(true), "reaction delay before first shot");
        check(gun.tick(true), "fifth aimed tick fires");
        for (int i = 0; i < 3; i++) check(!gun.tick(true), "minimum interval respected");
        check(gun.tick(true), "second round at interval");
        for (int i = 0; i < 9; i++) check(!gun.tick(true), "burst pause includes weapon interval");
        check(gun.tick(true), "next burst after pause");
        check(gun.snapshot().rounds() == 0, "magazine consumed");
        check(!gun.tick(true) && gun.snapshot().reloading() == 10, "empty magazine starts reload");
        for (int i = 0; i < 4; i++) check(!gun.tick(false), "no fire during reload without sight");
        NpcFireControl loaded = new NpcFireControl(settings);
        loaded.restore(gun.snapshot());
        check(loaded.snapshot().reloading() == 6, "reload progress survives save");
        check(loaded.snapshot().rounds() == 0 && loaded.snapshot().reserve() == 3, "ammunition survives save");
        for (int i = 0; i < 6; i++) check(!loaded.tick(false), "remaining reload duration preserved");
        check(loaded.snapshot().rounds() == 3 && loaded.snapshot().reserve() == 0, "reload consumes finite reserve");
        for (int i = 0; i < 4; i++) check(!loaded.tick(true), "reload requires fresh aiming after losing sight");
        check(loaded.tick(true), "fresh reaction complete");
        int shots = 1;
        for (int i = 0; i < 200; i++) if (loaded.tick(true)) shots++;
        check(shots == 3, "bounded reserve eventually exhausts");
        check(loaded.snapshot().rounds() == 0 && loaded.snapshot().reserve() == 0, "empty NPC cannot reload infinite ammunition");
        NpcFireControl interrupted = new NpcFireControl(settings);
        for (int i = 0; i < 4; i++) interrupted.tick(true);
        check(!interrupted.tick(false), "wall immediately prevents firing");
        for (int i = 0; i < 4; i++) check(!interrupted.tick(true), "sight reacquisition has fresh reaction delay");
        check(interrupted.tick(true), "reacquired target can be fired upon");
        NpcFireControl reloadedAim = new NpcFireControl(settings);
        reloadedAim.restore(interrupted.snapshot());
        for (int i = 0; i < 4; i++) check(!reloadedAim.tick(true), "save reload cannot preserve instant perfect aim");
        check(reloadedAim.tick(true), "saved magazine allows next legitimate shot");
        NpcFireControl corrupt = new NpcFireControl(settings);
        corrupt.restore(new NpcFireControl.Snapshot(999, 999, 999, 999, 999, 999));
        check(corrupt.snapshot().rounds() == 3 && corrupt.snapshot().reserve() == 3, "invalid persisted ammunition clamped");
        check(corrupt.snapshot().cooldown() == 10 && corrupt.snapshot().reloading() == 10, "invalid timing bounded");
        NpcFireControl noSight = new NpcFireControl(settings);
        for (int i = 0; i < 10000; i++) check(!noSight.tick(false), "no shot without line of sight");
        check(noSight.snapshot().rounds() == 3, "invisible targets never consume ammunition");
        System.out.println("NPC firearm timing and persistence: " + checks + " checks passed");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
