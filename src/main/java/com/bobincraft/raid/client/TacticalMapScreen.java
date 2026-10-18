package com.bobincraft.raid.client;

import com.bobincraft.raid.DeploymentPlanner;
import com.bobincraft.raid.RaidNetworking;
import com.bobincraft.raid.TacticalMapData;
import com.bobincraft.warzone.WarzoneDimension;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.lwjgl.glfw.GLFW;

/** A server-supplied map. Deploy requests contain a rotation token and an offered point ID. */
public class TacticalMapScreen extends Screen {
    private static final int MARGIN = 12;
    private static final int TOP = 27;
    private static final double MIN_ZOOM = 1.0;
    private static final double MAX_ZOOM = 6.0;
    private static final int ROAD_COLOR = 0xffd9bd87;
    private static final int EXTRACTION_COLOR = 0xff7dffb2;

    private final TacticalMapData map;
    private final boolean fieldMode;
    private static SavedView savedFieldView;
    private record SavedView(int min, int size, int terrainHash, double x, double z, double zoom) {}
    private Identifier terrainTexture;
    private double centerX;
    private double centerZ;
    private double zoom = MIN_ZOOM;
    private int selectedIndex;
    private int mapLeft;
    private int mapTop;
    private int mapWidth;
    private int mapHeight;
    private boolean dragging;
    private boolean submitted;
    private boolean mapKeyReleased;
    private ButtonWidget deployButton;

    public TacticalMapScreen(TacticalMapData map) {
        this(map, false);
    }

    protected TacticalMapScreen(TacticalMapData map, boolean fieldMode) {
        super(Text.translatable(fieldMode ? "gui.bobin_craft.tactical.title" : "gui.bobin_craft.raid.title"));
        if (map.fieldMap() != fieldMode) throw new IllegalArgumentException("Wrong map interface for payload");
        this.map = map;
        this.fieldMode = fieldMode;
        this.centerX = map.min() + map.size() / 2.0;
        this.centerZ = centerX;
        this.selectedIndex = map.deployments().isEmpty() ? -1 : 0;
        // Restore only a manually chosen strategic view, never a live player position.
        if (fieldMode && savedFieldView != null && savedFieldView.min() == map.min()
                && savedFieldView.size() == map.size() && savedFieldView.terrainHash() == java.util.Arrays.hashCode(map.colors())) {
            this.centerX = savedFieldView.x();
            this.centerZ = savedFieldView.z();
            this.zoom = savedFieldView.zoom();
        }
    }

    static void clearRememberedView() { savedFieldView = null; }

    @Override
    protected void init() {
        mapKeyReleased = !RaidClient.mapKeyHeld(client);
        mapLeft = MARGIN;
        mapTop = TOP;
        mapWidth = Math.max(24, width - MARGIN * 2);
        mapHeight = Math.max(24, height - TOP - (fieldMode ? 61 : 66));
        clampCenter();
        terrainTexture = TacticalMapTextureCache.texture(client, map);

        int buttonWidth = Math.max(60, Math.min(130, (width - 32) / 2));
        if (fieldMode) {
            addDrawableChild(ButtonWidget.builder(Text.translatable("gui.bobin_craft.raid.close"), button -> close())
                    .dimensions(width / 2 - buttonWidth / 2, height - 25, buttonWidth, 20).build());
            return;
        }
        deployButton = addDrawableChild(ButtonWidget.builder(
                        Text.translatable("gui.bobin_craft.raid.deploy"), button -> deploy())
                .dimensions(width / 2 - buttonWidth - 4, height - 26, buttonWidth, 20).build());
        deployButton.active = selectedPoint() != null && !submitted;
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.bobin_craft.raid.close"), button -> close())
                .dimensions(width / 2 + 4, height - 26, buttonWidth, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xf01e241b);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 9, 0xffe9e3d2);
        context.fill(mapLeft - 1, mapTop - 1, mapLeft + mapWidth + 1, mapTop + mapHeight + 1, 0xff899071);
        context.fill(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight, 0xff1a2119);

        context.enableScissor(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight);
        double scale = pixelsPerBlock();
        int terrainLeft = worldToScreenX(map.min());
        int terrainTop = worldToScreenY(map.min());
        int terrainSize = Math.max(1, (int) Math.ceil(map.size() * scale));
        context.drawTexture(terrainTexture, terrainLeft, terrainTop, terrainSize, terrainSize,
                0.0f, 0.0f, map.resolution(), map.resolution(), map.resolution(), map.resolution());

        for (TacticalMapData.Segment road : map.roads()) {
            int x0 = worldToScreenX(road.x0()), y0 = worldToScreenY(road.z0());
            int x1 = worldToScreenX(road.x1()), y1 = worldToScreenY(road.z1());
            if (Math.max(x0, x1) < mapLeft || Math.min(x0, x1) > mapLeft + mapWidth
                    || Math.max(y0, y1) < mapTop || Math.min(y0, y1) > mapTop + mapHeight) continue;
            drawLine(context, x0, y0, x1, y1, 3, 0xaa302d23);
            drawLine(context, x0, y0, x1, y1, 1, ROAD_COLOR);
        }
        for (TacticalMapData.Marker poi : map.pois()) {
            int x = worldToScreenX(poi.x());
            int y = worldToScreenY(poi.z());
            if (x < mapLeft - 4 || x > mapLeft + mapWidth + 4 || y < mapTop - 4 || y > mapTop + mapHeight + 4) continue;
            context.fill(x - 3, y - 3, x + 4, y + 4, 0xff161b1d);
            context.fill(x - 2, y - 2, x + 3, y + 3, dangerColor(poi.danger()));
        }

        DeploymentPlanner.Point extraction = map.extraction();
        if (extraction != null) {
            int x = worldToScreenX(extraction.x());
            int y = worldToScreenY(extraction.z());
            context.fill(x - 5, y - 5, x + 6, y + 6, 0xff122c23);
            context.drawBorder(x - 5, y - 5, 11, 11, EXTRACTION_COLOR);
            context.drawCenteredTextWithShadow(textRenderer, "E", x, y - 4, EXTRACTION_COLOR);
        }
        for (int i = 0; i < map.deployments().size(); i++) {
            DeploymentPlanner.Point point = map.deployments().get(i);
            int x = worldToScreenX(point.x());
            int y = worldToScreenY(point.z());
            boolean selected = i == selectedIndex;
            context.fill(x - 7, y - 7, x + 8, y + 8, selected ? 0xfff2dd82 : 0xff172a35);
            context.fill(x - 5, y - 5, x + 6, y + 6, dangerColor(point.danger()));
            context.drawCenteredTextWithShadow(textRenderer, Integer.toString(i + 1), x, y - 4, 0xffffffff);
        }
        context.disableScissor();

        if (fieldMode) {
            Text legend = Text.translatable("gui.bobin_craft.tactical.legend");
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(legend.getString(), Math.max(1, width - MARGIN * 2)),
                    MARGIN, height - 54, 0xffd7dcc0);
            Text controls = Text.translatable("gui.bobin_craft.tactical.controls", RaidClient.mapKeyName());
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(controls.getString(), Math.max(1, width - MARGIN * 2)),
                    MARGIN, height - 40, 0xffa6af92);
        } else {
            DeploymentPlanner.Point selected = selectedPoint();
            Text selection = selected == null ? Text.translatable("gui.bobin_craft.raid.none")
                    : Text.translatable("gui.bobin_craft.raid.selected", selected.label(),
                    selected.x() + ", " + selected.z(), Text.translatable("gui.bobin_craft.raid.danger", selected.danger()));
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(selection.getString(), Math.max(1, width - MARGIN * 2)),
                    MARGIN, height - 57, 0xffece6d5);
            Text controls = Text.translatable("gui.bobin_craft.raid.controls");
            context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(controls.getString(), Math.max(1, width - MARGIN * 2)),
                    MARGIN, height - 42, 0xffaab5bc);
        }
        super.render(context, mouseX, mouseY, delta);
        drawMapTooltip(context, mouseX, mouseY);
    }

    private void drawMapTooltip(DrawContext context, int mouseX, int mouseY) {
        if (!insideMap(mouseX, mouseY)) return;
        int deployment = deploymentAt(mouseX, mouseY);
        if (deployment >= 0) {
            DeploymentPlanner.Point point = map.deployments().get(deployment);
            context.drawTooltip(textRenderer, Text.translatable("gui.bobin_craft.raid.selected", point.label(),
                    point.x() + ", " + point.z(), Text.translatable("gui.bobin_craft.raid.danger", point.danger())), mouseX, mouseY);
            return;
        }
        DeploymentPlanner.Point extraction = map.extraction();
        if (extraction != null && near(mouseX, mouseY, extraction.x(), extraction.z(), 8)) {
            context.drawTooltip(textRenderer, Text.translatable("gui.bobin_craft.raid.extraction"), mouseX, mouseY);
            return;
        }
        for (TacticalMapData.Marker poi : map.pois()) {
            if (near(mouseX, mouseY, poi.x(), poi.z(), 6)) {
                Text label = fieldMode ? Text.translatable("gui.bobin_craft.tactical.landmark", poi.label(), poi.danger())
                        : Text.translatable("gui.bobin_craft.raid.selected", poi.label(),
                        poi.x() + ", " + poi.z(), Text.translatable("gui.bobin_craft.raid.danger", poi.danger()));
                context.drawTooltip(textRenderer, label, mouseX, mouseY);
                return;
            }
        }
    }

    private static int dangerColor(int danger) {
        if (danger <= 33) return 0xff759d66;
        if (danger <= 66) return 0xffb18b43;
        return 0xffe5786a;
    }

    private static void drawLine(DrawContext context, int x0, int y0, int x1, int y1, int thickness, int color) {
        double dx = x1 - (double) x0;
        double dy = y1 - (double) y0;
        int length = (int) Math.ceil(Math.hypot(dx, dy));
        if (length == 0) return;
        context.getMatrices().push();
        context.getMatrices().translate(x0, y0, 0);
        context.getMatrices().multiply(RotationAxis.POSITIVE_Z.rotation((float) Math.atan2(dy, dx)));
        context.fill(0, -thickness / 2, length, (thickness + 1) / 2, color);
        context.getMatrices().pop();
    }

    private void deploy() {
        DeploymentPlanner.Point point = selectedPoint();
        if (fieldMode || submitted || point == null || !ClientPlayNetworking.canSend(RaidNetworking.DEPLOY)) return;
        submitted = true;
        deployButton.active = false;
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeLong(map.rotation());
        buffer.writeInt(point.id());
        ClientPlayNetworking.send(RaidNetworking.DEPLOY, buffer);
        close();
    }

    private DeploymentPlanner.Point selectedPoint() {
        return selectedIndex >= 0 && selectedIndex < map.deployments().size() ? map.deployments().get(selectedIndex) : null;
    }

    private double pixelsPerBlock() {
        return Math.min(mapWidth, mapHeight) * zoom / map.size();
    }

    private int worldToScreenX(double worldX) {
        return (int) Math.round(mapLeft + mapWidth / 2.0 + (worldX - centerX) * pixelsPerBlock());
    }

    private int worldToScreenY(double worldZ) {
        return (int) Math.round(mapTop + mapHeight / 2.0 + (worldZ - centerZ) * pixelsPerBlock());
    }

    private double screenToWorldX(double screenX) {
        return centerX + (screenX - mapLeft - mapWidth / 2.0) / pixelsPerBlock();
    }

    private double screenToWorldZ(double screenY) {
        return centerZ + (screenY - mapTop - mapHeight / 2.0) / pixelsPerBlock();
    }

    private boolean insideMap(double x, double y) {
        return x >= mapLeft && y >= mapTop && x < mapLeft + mapWidth && y < mapTop + mapHeight;
    }

    private boolean near(double screenX, double screenY, double worldX, double worldZ, int radius) {
        return Math.abs(screenX - worldToScreenX(worldX)) <= radius && Math.abs(screenY - worldToScreenY(worldZ)) <= radius;
    }

    private int deploymentAt(double mouseX, double mouseY) {
        for (int i = 0; i < map.deployments().size(); i++) {
            DeploymentPlanner.Point point = map.deployments().get(i);
            if (near(mouseX, mouseY, point.x(), point.z(), 9)) return i;
        }
        return -1;
    }

    private void clampCenter() {
        double halfX = mapWidth / (2.0 * pixelsPerBlock());
        double halfZ = mapHeight / (2.0 * pixelsPerBlock());
        double midpoint = map.min() + map.size() / 2.0;
        centerX = halfX * 2 >= map.size() ? midpoint : MathHelper.clamp(centerX, map.min() + halfX, map.min() + map.size() - halfX);
        centerZ = halfZ * 2 >= map.size() ? midpoint : MathHelper.clamp(centerZ, map.min() + halfZ, map.min() + map.size() - halfZ);
    }

    private void zoomAt(double amount, double screenX, double screenY) {
        double anchorX = screenToWorldX(screenX);
        double anchorZ = screenToWorldZ(screenY);
        zoom = MathHelper.clamp(zoom * Math.pow(1.2, amount), MIN_ZOOM, MAX_ZOOM);
        centerX = anchorX - (screenX - mapLeft - mapWidth / 2.0) / pixelsPerBlock();
        centerZ = anchorZ - (screenY - mapTop - mapHeight / 2.0) / pixelsPerBlock();
        clampCenter();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (fieldMode && RaidClient.matchesMapMouse(button)) {
            if (mapKeyReleased) close();
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && insideMap(mouseX, mouseY)) {
            int point = deploymentAt(mouseX, mouseY);
            if (point >= 0) {
                selectedIndex = point;
                deployButton.active = !submitted;
            } else {
                dragging = true;
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (fieldMode && RaidClient.matchesMapKey(keyCode, scanCode)) mapKeyReleased = true;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            centerX -= deltaX / pixelsPerBlock();
            centerZ -= deltaY / pixelsPerBlock();
            clampCenter();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (fieldMode && RaidClient.matchesMapMouse(button)) mapKeyReleased = true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) dragging = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (insideMap(mouseX, mouseY)) {
            zoomAt(amount, mouseX, mouseY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (fieldMode && RaidClient.matchesMapKey(keyCode, scanCode)) {
            if (mapKeyReleased) close();
            return true;
        }
        int index = keyCode - GLFW.GLFW_KEY_1;
        if (index >= 0 && index < map.deployments().size()) {
            selectedIndex = index;
            deployButton.active = !submitted;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_EQUAL || keyCode == GLFW.GLFW_KEY_KP_ADD
                || keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT) {
            zoomAt(keyCode == GLFW.GLFW_KEY_MINUS || keyCode == GLFW.GLFW_KEY_KP_SUBTRACT ? -1 : 1,
                    mapLeft + mapWidth / 2.0, mapTop + mapHeight / 2.0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R) {
            zoom = MIN_ZOOM;
            centerX = centerZ = map.min() + map.size() / 2.0;
            clampCenter();
            return true;
        }
        double step = 20.0 / pixelsPerBlock();
        if (keyCode == GLFW.GLFW_KEY_W || keyCode == GLFW.GLFW_KEY_A
                || keyCode == GLFW.GLFW_KEY_S || keyCode == GLFW.GLFW_KEY_D) {
            if (keyCode == GLFW.GLFW_KEY_A) centerX -= step;
            if (keyCode == GLFW.GLFW_KEY_D) centerX += step;
            if (keyCode == GLFW.GLFW_KEY_W) centerZ -= step;
            if (keyCode == GLFW.GLFW_KEY_S) centerZ += step;
            clampCenter();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        if (!mapKeyReleased && client != null && !RaidClient.mapKeyHeld(client)) mapKeyReleased = true;
        if (fieldMode && (client == null || client.world == null || !WarzoneDimension.isWarzone(client.world))) close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        dragging = false;
        if (fieldMode) {
            savedFieldView = new SavedView(map.min(), map.size(), java.util.Arrays.hashCode(map.colors()), centerX, centerZ, zoom);
        }
        terrainTexture = null; // The session cache owns the reusable texture.
        super.removed();
    }
}
