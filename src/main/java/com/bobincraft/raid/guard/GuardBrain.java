package com.bobincraft.raid.guard;

/** Small server-side awareness model; time is measured in game ticks, not wall time. */
public final class GuardBrain {
    public enum State {
        IDLE, PATROLLING, SUSPICIOUS, INVESTIGATING, ALERTED, IN_COMBAT, SEARCHING, RETURNING_TO_POST
    }

    private State state = State.IDLE;
    private int suspicion;
    private int memoryTicks;
    private int investigationTicks;

    public record Snapshot(State state, int suspicion, int memoryTicks, int investigationTicks) {}

    public State state() { return state; }

    public Snapshot snapshot() { return new Snapshot(state, suspicion, memoryTicks, investigationTicks); }

    public void restore(Snapshot saved) {
        state = saved.state();
        suspicion = Math.max(0, Math.min(20, saved.suspicion()));
        memoryTicks = Math.max(0, Math.min(120, saved.memoryTicks()));
        investigationTicks = Math.max(0, Math.min(160, saved.investigationTicks()));
    }

    /** Sounds give a position to investigate, never an omniscient combat target. */
    public void hearNoise() {
        investigationTicks = 160;
        if (state != State.IN_COMBAT && state != State.ALERTED) state = State.SUSPICIOUS;
    }

    public void confirmThreat() {
        suspicion = 20;
        memoryTicks = 120;
        state = State.ALERTED;
    }

    public void update(boolean visiblePlayer, boolean atPost, boolean hasPatrol, int elapsedTicks) {
        if (visiblePlayer) {
            memoryTicks = 120;
            investigationTicks = 0;
            suspicion = Math.min(20, suspicion + elapsedTicks);
            if (state == State.ALERTED || state == State.IN_COMBAT) state = State.IN_COMBAT;
            else state = suspicion >= 20 ? State.ALERTED : State.SUSPICIOUS;
            return;
        }
        suspicion = Math.max(0, suspicion - elapsedTicks);
        if (memoryTicks > 0) {
            memoryTicks = Math.max(0, memoryTicks - elapsedTicks);
            state = State.SEARCHING;
        } else if (investigationTicks > 0) {
            investigationTicks = Math.max(0, investigationTicks - elapsedTicks);
            state = State.INVESTIGATING;
        } else {
            // Walking away from the post is the purpose of an assigned patrol.
            if (state == State.PATROLLING && hasPatrol) return;
            state = atPost ? (hasPatrol ? State.PATROLLING : State.IDLE) : State.RETURNING_TO_POST;
        }
    }

    /** Client headings use Minecraft yaw: 0 faces +Z and 90 faces -X. */
    public static boolean insideFieldOfView(double dx, double dz, float yaw, double minimumDot) {
        double length = Math.hypot(dx, dz);
        if (length < 3.0) return true;
        double radians = Math.toRadians(yaw);
        return (-Math.sin(radians) * dx + Math.cos(radians) * dz) / length >= minimumDot;
    }
}
