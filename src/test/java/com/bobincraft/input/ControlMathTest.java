package com.bobincraft.input;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression checks for continuous movement and elapsed-time camera controls. */
class ControlMathTest {
    private static final double EPSILON = 1.0e-10;

    @ParameterizedTest
    @CsvSource({"1, 1", "1, -1", "-1, 1", "-1, -1"})
    void diagonalsHaveTheSameSpeedAsCardinalMovement(double left, double forward) {
        var axes = ControlMath.normalize(left, forward);
        assertEquals(1, Math.hypot(axes.sideways(), axes.forward()), EPSILON);
        assertEquals(left / Math.sqrt(2), axes.sideways(), EPSILON);
        assertEquals(forward / Math.sqrt(2), axes.forward(), EPSILON);
    }

    @Test
    void fractionalInputRetainsItsMagnitudeForPreciseMovement() {
        var axes = ControlMath.normalize(0.075, 0.1);
        assertEquals(0.075, axes.sideways(), EPSILON);
        assertEquals(0.1, axes.forward(), EPSILON);
        assertEquals(0.125, Math.hypot(axes.sideways(), axes.forward()), EPSILON);
        var rotated = ControlMath.playerAxes(0.125, 0, 90, 0);
        assertEquals(0, rotated.sideways(), EPSILON);
        assertEquals(0.125, rotated.forward(), EPSILON);
    }

    @Test
    void noInputProducesNoMovementAtAnyYaw() {
        for (int yaw = -720; yaw <= 720; yaw += 37) {
            var axes = ControlMath.playerAxes(0, 0, yaw, -yaw);
            assertEquals(0, axes.sideways(), EPSILON);
            assertEquals(0, axes.forward(), EPSILON);
        }
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 1", "90, -1, 0", "180, 0, -1", "270, 1, 0"})
    void forwardFollowsTheCameraRegardlessOfPlayerAim(double cameraYaw, double expectedX, double expectedZ) {
        for (double playerYaw : new double[] {-180, -45, 0, 37, 89, 180, 270}) {
            var axes = ControlMath.playerAxes(0, 1, cameraYaw, playerYaw);
            assertWorldDirection(axes, playerYaw, expectedX, expectedZ);
        }
    }

    @ParameterizedTest
    @CsvSource({"0, 1, 0", "90, 0, 1", "180, -1, 0", "270, 0, -1"})
    void leftFollowsTheCameraRegardlessOfPlayerAim(double cameraYaw, double expectedX, double expectedZ) {
        for (double playerYaw : new double[] {-180, -45, 0, 37, 89, 180, 270}) {
            var axes = ControlMath.playerAxes(1, 0, cameraYaw, playerYaw);
            assertWorldDirection(axes, playerYaw, expectedX, expectedZ);
        }
    }

    @Test
    void arbitraryYawAndTurningDoNotChangeDiagonalSpeed() {
        for (int cameraYaw = -180; cameraYaw <= 180; cameraYaw += 17) {
            for (int playerYaw = -180; playerYaw <= 180; playerYaw += 23) {
                var axes = ControlMath.playerAxes(-1, 1, cameraYaw, playerYaw);
                assertEquals(1, Math.hypot(axes.sideways(), axes.forward()), EPSILON);
            }
        }
    }

    @Test
    void equivalentWrappedYawsGiveIdenticalInput() {
        var reference = ControlMath.playerAxes(0.25, -0.5, 45, -170);
        var wrapped = ControlMath.playerAxes(0.25, -0.5, 765, 190);
        assertEquals(reference.sideways(), wrapped.sideways(), EPSILON);
        assertEquals(reference.forward(), wrapped.forward(), EPSILON);
    }

    @ParameterizedTest
    @CsvSource({"0, -1", "2.5, -0.75", "5, -0.5", "10, 0", "50, 0",
            "90, 0", "95, 0.5", "97.5, 0.75", "100, 1"})
    void edgeScrollStrengthIncreasesContinuouslyNearEachEdge(double cursor, double expected) {
        assertEquals(expected, ControlMath.edge(cursor, 100, 10), EPSILON);
    }

    @Test
    void invalidOrOutsideWindowCoordinatesDoNotScroll() {
        assertEquals(0, ControlMath.edge(-1, 100, 10));
        assertEquals(0, ControlMath.edge(101, 100, 10));
        assertEquals(0, ControlMath.edge(0, 0, 10));
        assertEquals(0, ControlMath.edge(0, -1, 10));
        assertEquals(0, ControlMath.edge(0, 100, 0));
        assertEquals(0, ControlMath.edge(0, 100, -1));
    }

    @Test
    void largeBandsRemainSymmetricInSmallWindows() {
        assertEquals(-1, ControlMath.edge(0, 20, 100), EPSILON);
        assertEquals(-0.5, ControlMath.edge(5, 20, 100), EPSILON);
        assertEquals(0, ControlMath.edge(10, 20, 100), EPSILON);
        assertEquals(0.5, ControlMath.edge(15, 20, 100), EPSILON);
        assertEquals(1, ControlMath.edge(20, 20, 100), EPSILON);
    }

    @ParameterizedTest
    @CsvSource({"0, 0, -1, 1", "100, 0, 1, 1", "0, 100, -1, -1", "100, 100, 1, -1"})
    void cornersScrollDiagonallyWithoutExtraSpeed(double x, double y, double right, double forward) {
        var axes = screenEdges(x, y, 100, 100, 10);
        assertEquals(right / Math.sqrt(2), axes.sideways(), EPSILON);
        assertEquals(forward / Math.sqrt(2), axes.forward(), EPSILON);
        assertEquals(1, Math.hypot(axes.sideways(), axes.forward()), EPSILON);
    }

    @ParameterizedTest
    @ValueSource(ints = {15, 30, 60, 144, 240})
    void fractionalEdgeScrollCoversTheSameDistanceAtDifferentFrameRates(int fps) {
        // Near-edge input is deliberately below full speed; the final interval is fractional.
        var axes = screenEdges(997.5, 4, 1000, 800, 10);
        double seconds = 2.75;
        double rightDistance = 0, forwardDistance = 0, elapsed = 0;
        while (elapsed < seconds) {
            double dt = Math.min(1.0 / fps, seconds - elapsed);
            rightDistance += axes.sideways() * 21 * dt;
            forwardDistance += axes.forward() * 21 * dt;
            elapsed += dt;
        }
        assertEquals(0.75 * 21 * seconds, rightDistance, EPSILON);
        assertEquals(0.6 * 21 * seconds, forwardDistance, EPSILON);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 15, 30, 60, 144, 240})
    void smoothingSettlesIdenticallyAcrossFrameRatesWithoutOvershoot(int fps) {
        double rate = 12, seconds = 0.75, initial = -3, target = 20;
        double current = initial, elapsed = 0;
        while (elapsed < seconds) {
            double dt = Math.min(1.0 / fps, seconds - elapsed);
            double next = current + (target - current) * ControlMath.smoothing(rate, dt);
            assertTrue(next >= current && next <= target);
            current = next;
            elapsed += dt;
        }
        assertEquals(target + (initial - target) * Math.exp(-rate * seconds), current, EPSILON);
    }

    @Test
    void smoothingDoesNotAdvanceForZeroOrNegativeElapsedTime() {
        assertEquals(0, ControlMath.smoothing(12, 0));
        assertEquals(0, ControlMath.smoothing(12, -0.5));
        assertEquals(0, ControlMath.smoothing(0, 0.5));
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 15, 60, 240})
    void smoothedEdgeScrollAndReleaseMatchRealTimeAtAllFrameRates(int fps) {
        var axes = screenEdges(997.5, 4, 1000, 800, 10);
        double rate = 12, movingSeconds = 0.75, stoppedSeconds = 0.5;
        double targetX = 0, targetZ = 0, currentX = 0, currentZ = 0, elapsed = 0;
        while (elapsed < movingSeconds) {
            double dt = Math.min(1.0 / fps, movingSeconds - elapsed);
            double previousX = targetX, previousZ = targetZ;
            targetX += axes.sideways() * 21 * dt;
            targetZ += axes.forward() * 21 * dt;
            currentX = ControlMath.follow(currentX, previousX, targetX, rate, dt);
            currentZ = ControlMath.follow(currentZ, previousZ, targetZ, rate, dt);
            elapsed += dt;
        }
        double response = movingSeconds - (1 - Math.exp(-rate * movingSeconds)) / rate;
        double expectedX = 0.75 * 21 * response, expectedZ = 0.6 * 21 * response;
        assertEquals(expectedX, currentX, EPSILON);
        assertEquals(expectedZ, currentZ, EPSILON);

        elapsed = 0;
        while (elapsed < stoppedSeconds) {
            double dt = Math.min(1.0 / fps, stoppedSeconds - elapsed);
            currentX = ControlMath.follow(currentX, targetX, targetX, rate, dt);
            currentZ = ControlMath.follow(currentZ, targetZ, targetZ, rate, dt);
            elapsed += dt;
        }
        assertEquals(targetX + (expectedX - targetX) * Math.exp(-rate * stoppedSeconds), currentX, EPSILON);
        assertEquals(targetZ + (expectedZ - targetZ) * Math.exp(-rate * stoppedSeconds), currentZ, EPSILON);
    }

    @Test
    void followingDoesNotAdvanceWithoutElapsedTime() {
        assertEquals(3, ControlMath.follow(3, 8, 10, 12, 0));
        assertEquals(3, ControlMath.follow(3, 8, 10, 12, -0.25));
    }

    private static ControlMath.Axes screenEdges(double x, double y, double width, double height, double band) {
        return ControlMath.normalize(ControlMath.edge(x, width, band), -ControlMath.edge(y, height, band));
    }

    private static void assertWorldDirection(ControlMath.Axes axes, double playerYaw, double x, double z) {
        double yaw = Math.toRadians(playerYaw);
        double worldX = axes.sideways() * Math.cos(yaw) - axes.forward() * Math.sin(yaw);
        double worldZ = axes.sideways() * Math.sin(yaw) + axes.forward() * Math.cos(yaw);
        assertEquals(x, worldX, EPSILON, "world X at player yaw " + playerYaw);
        assertEquals(z, worldZ, EPSILON, "world Z at player yaw " + playerYaw);
    }
}
