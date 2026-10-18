package com.bobincraft.camera;

import com.bobincraft.screen.BobinScreen;
import com.bobincraft.input.ControlMath;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class BobinCameraState {
    private static volatile boolean active = false;

    public static final float MIN_PITCH = 10.0f;
    public static final float MAX_PITCH = 85.0f;
    private static float pitch = 56.0f;
    private static float currentYaw = 45.0f;
    private static float targetYaw = 45.0f;
    private static float distance = 15.0f;
    public static final float MIN_DISTANCE = 6.0f;
    public static final float MAX_DISTANCE = 32.0f;
    // Максимальная дальность зума со снайперской винтовкой (+10% к максимуму мышки)
    public static final float SNIPER_MAX_DISTANCE = MAX_DISTANCE * 1.10f; // 35.2f

    public static void adjustYaw(float deltaYaw) {
        targetYaw = MathHelper.wrapDegrees(targetYaw + deltaYaw);
    }
    private static boolean sniperScopeActive = false;
    private static float savedDistanceBeforeScope = 15.0f;

    // Сглаженные координаты панорамирования (текущие и целевые)
    private static double currentPanX = 0.0;
    private static double currentPanY = 0.0;
    private static double currentPanZ = 0.0;

    private static double targetPanX = 0.0;
    private static double targetPanY = 0.0;
    private static double targetPanZ = 0.0;

    public static final double MAX_PAN_RADIUS = 28.0; // Не дальше того что может видеть игрок

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean isActive) {
        active = isActive;
        lastFrameNanos = 0L;
        frameSeconds = 0;
        if (!active) {
            resetPan();
            currentPanX = 0.0;
            currentPanY = 0.0;
            currentPanZ = 0.0;
            setSniperScopeActive(false);
            BobinCameraCollision.reset();
        }
    }

    public static boolean isSniperScopeActive() {
        return sniperScopeActive;
    }

    public static void setSniperScopeActive(boolean active) {
        if (sniperScopeActive == active) return;
        sniperScopeActive = active;
        if (sniperScopeActive) {
            savedDistanceBeforeScope = distance;
            distance = SNIPER_MAX_DISTANCE;
        } else {
            distance = MathHelper.clamp(savedDistanceBeforeScope, MIN_DISTANCE, MAX_DISTANCE);
        }
    }

    public static void toggleSniperScope() {
        setSniperScopeActive(!sniperScopeActive);
    }

    /**
     * Плавная покадровая интерполяция (вызывается каждый кадр рендера в CameraMixin для 144+ FPS гладкости)
     */
    public static void updateFrame() {
        if (!active) {
            lastFrameNanos = 0L;
            frameSeconds = 0;
            return;
        }

        // Q / E: вращение применяется ЗДЕСЬ, до расчёта позиции камеры в этом же кадре.
        // Раньше угол менялся в BobinScreen.render (уже ПОСЛЕ отрисовки мира), поэтому камера,
        // подсветка блока и луч курсора отставали на кадр и «прыгали» по сетке блоков.
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0L ? 0.0 : (now - lastFrameNanos) / 1.0e9;
        lastFrameNanos = now;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isWindowFocused() || client.isPaused()
                || !(client.currentScreen instanceof BobinScreen)) {
            lastFrameNanos = 0L;
            frameSeconds = 0;
            return;
        }
        frameSeconds = dt;
        double oldTargetX = targetPanX, oldTargetY = targetPanY, oldTargetZ = targetPanZ;
        applyKeyRotation((float) dt);
        applyEdgeScrolling(client, dt);

        // Плавный поворот камеры на G (glide 144 FPS)
        float diffYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        if (Math.abs(diffYaw) > 0.05f) {
            currentYaw += diffYaw * (float) ControlMath.smoothing(5.0, dt);
        } else {
            currentYaw = targetYaw;
        }

        currentPanX = ControlMath.follow(currentPanX, oldTargetX, targetPanX, 12.0, dt);
        currentPanY = ControlMath.follow(currentPanY, oldTargetY, targetPanY, 12.0, dt);
        currentPanZ = ControlMath.follow(currentPanZ, oldTargetZ, targetPanZ, 12.0, dt);
    }

    private static long lastFrameNanos = 0L;
    private static double frameSeconds;

    public static double getFrameSeconds() {
        return frameSeconds;
    }
    private static final float KEY_ROTATE_SPEED = 120.0f; // градусов в секунду
    private static final double EDGE_SCROLL_SPEED = 21.0; // blocks per real second

    private static void applyEdgeScrolling(MinecraftClient client, double dt) {
        if (dt <= 0 || client.mouse == null || client.getWindow() == null) return;
        double width = client.getWindow().getWidth(), height = client.getWindow().getHeight();
        double band = Math.min(width, height) * 0.035;
        ControlMath.Axes edges = ControlMath.normalize(
                ControlMath.edge(client.mouse.getX(), width, band),
                -ControlMath.edge(client.mouse.getY(), height, band));
        panScreen(edges.sideways() * EDGE_SCROLL_SPEED * dt, edges.forward() * EDGE_SCROLL_SPEED * dt);

        long handle = client.getWindow().getHandle();
        double vertical = 0;
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS) vertical--;
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS) vertical++;
        panVertical(vertical * EDGE_SCROLL_SPEED * 0.8 * dt);
    }

    private static void applyKeyRotation(float dt) {
        if (dt <= 0.0f) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null || !(mc.currentScreen instanceof BobinScreen)) return;

        long wh = mc.getWindow().getHandle();
        float dir = 0.0f;
        if (GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_Q) == GLFW.GLFW_PRESS) dir -= 1.0f;
        if (GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS) dir += 1.0f;
        if (dir != 0.0f) {
            rotateYawImmediate(dir * KEY_ROTATE_SPEED * dt);
        }
    }

    public static void tick() {
        // Поддержка тиков логики
    }

    public static void rotateNextAngle() {
        targetYaw = MathHelper.wrapDegrees(targetYaw + 90.0f);
    }

    public static float getPitch() {
        return pitch;
    }

    public static void setPitch(float newPitch) {
        pitch = MathHelper.clamp(newPitch, MIN_PITCH, MAX_PITCH);
    }

    /** Мгновенный поворот камеры (клавиши Q / E), без сглаживания. */
    public static void rotateYawImmediate(float deltaYaw) {
        targetYaw = MathHelper.wrapDegrees(targetYaw + deltaYaw);
        currentYaw = targetYaw;
    }

    public static float getYaw() {
        return currentYaw;
    }

    public static void setYaw(float newYaw) {
        targetYaw = MathHelper.wrapDegrees(newYaw);
        currentYaw = targetYaw;
    }

    public static float getDistance() {
        return distance;
    }

    public static void zoom(double scrollAmount) {
        float max = sniperScopeActive ? SNIPER_MAX_DISTANCE : MAX_DISTANCE;
        distance = MathHelper.clamp(distance - (float) scrollAmount * 1.5f, MIN_DISTANCE, max);
    }

    /**
     * Pan in screen space: positive rightStep points right; positive fwdStep points up.
     */
    public static void panScreen(double rightStep, double fwdStep) {
        double rad = Math.toRadians(currentYaw);
        double fwdX = -Math.sin(rad);
        double fwdZ = Math.cos(rad);
        double rightX = -Math.cos(rad);
        double rightZ = -Math.sin(rad);

        targetPanX += fwdX * fwdStep + rightX * rightStep;
        targetPanZ += fwdZ * fwdStep + rightZ * rightStep;

        double radius = Math.hypot(targetPanX, targetPanZ);
        if (radius > MAX_PAN_RADIUS) {
            double scale = MAX_PAN_RADIUS / radius;
            targetPanX *= scale;
            targetPanZ *= scale;
        }
    }

    /**
     * Вертикальное перемещение камеры:
     * Расширенный диапазон (-60.0 до +25.0), чтобы камера могла опускаться до уровня ног даже на высоких возвышенностях
     */
    public static void panVertical(double dY) {
        targetPanY += dY;
        targetPanY = MathHelper.clamp(targetPanY, -60.0, 25.0);
    }

    public static void pan(double dx, double dz) {
        targetPanX += dx;
        targetPanZ += dz;
        double radius = Math.hypot(targetPanX, targetPanZ);
        if (radius > MAX_PAN_RADIUS) {
            double scale = MAX_PAN_RADIUS / radius;
            targetPanX *= scale;
            targetPanZ *= scale;
        }
    }

    public static void resetPan() {
        targetPanX = 0.0;
        targetPanY = 0.0;
        targetPanZ = 0.0;
    }

    public static Vec3d getPanOffset() {
        return new Vec3d(currentPanX, currentPanY, currentPanZ);
    }
}
