package com.bobincraft.render;

import com.bobincraft.camera.BobinCameraState;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Туман войны (только клиент, только в режиме Bobin).
 *
 * Что делает:
 *  - вокруг героя есть круг обзора; всё, что дальше, затемняется (земля, стены, деревья, склоны),
 *    граница мягкая (плавный градиент, как в Доте);
 *  - существа вне круга обзора НЕ отображаются и на них нельзя навести курсор
 *    (исключение: помеченные «Разведчиком» — они светятся и видны сквозь туман);
 *  - радиус обзора днём больше, ночью меньше, а при включённом снайперском прицеле (X) увеличивается;
 *  - V — включить/выключить туман.
 *
 * Реализация: экранный пост-процессинг (screen-space post-processing effect).
 * Для каждого пикселя экрана мировая позиция восстанавливается по значению из буфера глубины (depth buffer)
 * через обратную матрицу вида-проекции, после чего пиксель затемняется в зависимости от расстояния до героя.
 * Явная геометрия тумана в мире не строится, поэтому стены, деревья и склоны затемняются точно так же,
 * как и земля, без каких-либо визуальных артефактов и z-fighting.
 */
public class FogOfWarManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("bobin_craft/fog");

    // --- Настройки (в блоках) ---
    public static final float DAY_RADIUS = 22.0f;
    public static final float NIGHT_RADIUS = 14.0f;
    public static final float SCOPE_MULTIPLIER = 1.5f;   // снайперский прицел расширяет обзор
    private static final float FADE_WIDTH = 5.0f;        // ширина мягкой границы
    private static final float MAX_ALPHA = 0.72f;        // насколько темно в полном тумане
    private static final float COL_R = 0.02f, COL_G = 0.03f, COL_B = 0.07f; // чёрный с лёгким синим оттенком

    // Константы OpenGL 3.2 Core
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_DEPTH_COMPONENT = 0x1902;
    private static final int GL_FLOAT = 0x1406;
    private static final int GL_NEAREST = 0x2600;
    private static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    private static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    private static final int GL_TEXTURE_WRAP_S = 0x2802;
    private static final int GL_TEXTURE_WRAP_T = 0x2803;
    private static final int GL_CLAMP_TO_EDGE = 0x812F;
    private static final int GL_TEXTURE_COMPARE_MODE = 0x884C;
    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_VERTEX_SHADER = 0x8B31;
    private static final int GL_FRAGMENT_SHADER = 0x8B30;
    private static final int GL_COMPILE_STATUS = 0x8B81;
    private static final int GL_LINK_STATUS = 0x8B82;

    private static final String VERT_SHADER_SRC = """
            #version 150

            in vec3 Position;
            out vec2 vTexCoord;

            void main() {
                vTexCoord = Position.xy * 0.5 + 0.5;
                gl_Position = vec4(Position.xy, 0.0, 1.0);
            }
            """;

    private static final String FRAG_SHADER_SRC = """
            #version 150

            uniform sampler2D uDepthTex;
            uniform mat4 uInvViewProj;
            uniform vec3 uCameraPos;
            uniform vec3 uPlayerPos;
            uniform vec4 uFogParams;
            uniform vec3 uFogColor;

            in vec2 vTexCoord;
            out vec4 fragColor;

            void main() {
                float depth = texture(uDepthTex, vTexCoord).r;

                vec4 ndc = vec4(vTexCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                vec4 unprojected = uInvViewProj * ndc;
                vec3 worldPos = unprojected.xyz / unprojected.w + uCameraPos;

                float dist = length(worldPos.xz - uPlayerPos.xz);
                float factor = (depth >= 0.99995) ? 1.0 : smoothstep(uFogParams.x, uFogParams.y, dist);
                float alpha = factor * uFogParams.z;
                if (alpha <= 0.001) {
                    discard;
                }

                fragColor = vec4(uFogColor, alpha);
            }
            """;

    private static volatile boolean enabled = true;
    private static float radius = -1.0f;

    // Ресурсы OpenGL для экранного пост-процессинга
    private static int programId = -1;
    private static boolean shaderFailed = false;
    private static int uDepthTexLoc = -1;
    private static int uInvViewProjLoc = -1;
    private static int uCameraPosLoc = -1;
    private static int uPlayerPosLoc = -1;
    private static int uFogParamsLoc = -1;
    private static int uFogColorLoc = -1;

    private static int depthCopyTexId = -1;
    private static int depthCopyWidth = -1;
    private static int depthCopyHeight = -1;

    private static final FloatBuffer MAT4_BUF = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final FloatBuffer VEC3_BUF = ByteBuffer.allocateDirect(3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static final FloatBuffer VEC4_BUF = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

    // ------------------------------------------------------------------ состояние

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle() {
        enabled = !enabled;
    }

    public static String getStateLabel() {
        return enabled ? "§a[Туман войны] Включён" : "§7[Туман войны] Выключен";
    }

    public static boolean isFogActive() {
        return enabled && BobinCameraState.isActive();
    }

    private static float targetRadius(ClientWorld world) {
        boolean day = world == null
                || world.getDimension().hasFixedTime()
                || (world.getTimeOfDay() % 24000L) < 12800L;
        float r = day ? DAY_RADIUS : NIGHT_RADIUS;
        if (BobinCameraState.isSniperScopeActive()) {
            r *= SCOPE_MULTIPLIER;
        }
        return r;
    }

    private static void updateRadius(ClientWorld world) {
        float target = targetRadius(world);
        if (radius < 0.0f) {
            radius = target;
        } else {
            radius += (target - radius) * 0.04f; // плавная смена день/ночь и зума прицела
        }
    }

    // ------------------------------------------------------------------ существа

    /** true — существо в тумане: не рисуется и не выбирается курсором. Вызывается из миксина и RaycastHelper. */
    public static boolean isEntityHidden(Entity entity) {
        if (!isFogActive() || entity == null) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || entity == mc.player) return false;
        if (entity.isGlowing()) return false; // метка «Разведчика» раскрывает цель сквозь туман

        if (radius < 0.0f) {
            radius = targetRadius(mc.world);
        }
        double dx = entity.getX() - mc.player.getX();
        double dz = entity.getZ() - mc.player.getZ();
        double limit = radius - FADE_WIDTH * 0.35;
        if (dx * dx + dz * dz > limit * limit) return true;

        // Внутри круга обзора существо всё равно должно быть В ЛИНИИ ВИДИМОСТИ героя:
        // за стенами здания (даже когда крыша скрыта) его не видно, а через окна/открытые двери — видно.
        return !isInLineOfSight(mc, entity);
    }

    // ------------------------------------------------------------------ линия видимости

    private static final long SIGHT_GRACE_MS = 160L; // небольшая «память», чтобы цель не мигала на краю окна
    private static final Map<Integer, SightEntry> SIGHT_CACHE = new HashMap<>();

    private static final class SightEntry {
        long tick = Long.MIN_VALUE;
        long lastSeenMs = Long.MIN_VALUE / 2;
    }

    private static boolean isInLineOfSight(MinecraftClient mc, Entity entity) {
        long tick = mc.world.getTime();
        long now = System.currentTimeMillis();

        SightEntry entry = SIGHT_CACHE.get(entity.getId());
        if (entry == null) {
            if (SIGHT_CACHE.size() > 512) SIGHT_CACHE.clear();
            entry = new SightEntry();
            SIGHT_CACHE.put(entity.getId(), entry);
        }
        // Трассировка считается один раз за игровой тик на существо (а не на каждый кадр / каждый вызов)
        if (entry.tick != tick) {
            entry.tick = tick;
            if (computeSight(mc, entity)) {
                entry.lastSeenMs = now;
            }
        }
        return now - entry.lastSeenMs <= SIGHT_GRACE_MS;
    }

    private static boolean computeSight(MinecraftClient mc, Entity entity) {
        Vec3d eye = mc.player.getEyePos();
        double h = entity.getHeight();
        double hw = Math.max(0.15, entity.getWidth() * 0.45);
        Vec3d p = entity.getPos();

        // Несколько точек тела: голова, середина, ноги + бока на середине (чтобы выглядывание из-за угла/окна работало)
        Vec3d[] samples = {
                p.add(0, h * 0.9, 0),
                p.add(0, h * 0.5, 0),
                p.add(0, 0.15, 0),
                p.add(hw, h * 0.5, 0),
                p.add(-hw, h * 0.5, 0),
                p.add(0, h * 0.5, hw),
                p.add(0, h * 0.5, -hw)
        };
        for (Vec3d target : samples) {
            if (isClear(mc.world, eye, target)) return true;
        }
        return false;
    }

    private static boolean isClear(BlockView world, Vec3d from, Vec3d to) {
        BlockPos startBlock = BlockPos.ofFloored(from);
        BlockPos endBlock = BlockPos.ofFloored(to);
        BlockHitResult hit = BlockView.raycast(from, to, Boolean.TRUE, (ctx, pos) -> {
            if (pos.equals(startBlock) || pos.equals(endBlock)) return null;
            if (BuildingCutawayManager.isCutawayBlock(pos)) return null; // скрытая крыша зрение не блокирует
            BlockState state = world.getBlockState(pos);
            if (!blocksVision(state)) return null;
            VoxelShape shape = state.getOutlineShape(world, pos);
            if (shape.isEmpty()) return null;
            return shape.raycast(from, to, pos);
        }, ctx -> null);
        return hit == null;
    }

    /**
     * Что закрывает обзор: сплошные непрозрачные блоки здания и закрытые двери.
     * Стекло, панели, решётки, заборы, листва, открытые двери — не закрывают (это «окна»).
     * Естественный рельеф (земля, камень, песок) обзор не закрывает, чтобы холмы не прятали врагов на склонах.
     */
    private static boolean blocksVision(BlockState state) {
        if (state.isAir()) return false;
        if (BuildingCutawayManager.isNaturalTerrain(state)) return false;
        if (state.getBlock() instanceof DoorBlock) {
            return !state.get(DoorBlock.OPEN);
        }
        return state.isOpaque();
    }

    // ------------------------------------------------------------------ рендер (screen-space post-processing)

    public static void render(MatrixStack matrices, MinecraftClient client, float tickDelta) {
        if (!isFogActive()) {
            return;
        }
        ClientWorld world = client.world;
        if (world == null || client.player == null || client.gameRenderer == null) return;

        Framebuffer fb = client.getFramebuffer();
        if (fb == null || fb.textureWidth <= 0 || fb.textureHeight <= 0) return;

        if (!ensureShaderProgram()) {
            return;
        }

        updateRadius(world);

        Camera camera = client.gameRenderer.getCamera();
        Vec3d camPos = camera.getPos();
        Vec3d hero = client.player.getLerpedPos(tickDelta);

        // Чистая матрица вращения камеры (без переноса -camPos, если он уже применён к MatrixStack).
        // Обратная матрица (Proj * ViewRot)^-1 переводит NDC в координаты относительно камеры,
        // к которым в шейдере прибавляется uCameraPos для получения точной мировой позиции пикселя.
        Matrix4f viewRot = new Matrix4f(matrices.peek().getPositionMatrix())
                .m30(0.0f)
                .m31(0.0f)
                .m32(0.0f)
                .m33(1.0f);
        Matrix4f invViewProj = new Matrix4f(RenderSystem.getProjectionMatrix())
                .mul(viewRot)
                .invert();

        float outer = radius;
        float inner = Math.max(0.0f, outer - FADE_WIDTH);

        int prevActiveTex = GlStateManager._getActiveTexture();
        ShaderProgram prevShader = RenderSystem.getShader();
        if (prevShader != null) {
            prevShader.unbind();
        }

        // Копируем текущий буфер глубины кадра в отдельную текстуру,
        // чтобы читать глубину в фрагментном шейдере без цикла обратной связи FBO
        GlStateManager._activeTexture(GL_TEXTURE0);
        ensureDepthTexture(fb.textureWidth, fb.textureHeight);
        GlStateManager._bindTexture(depthCopyTexId);
        GlStateManager._glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, fb.textureWidth, fb.textureHeight);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        GlStateManager._glUseProgram(programId);

        if (uDepthTexLoc >= 0) {
            GlStateManager._glUniform1i(uDepthTexLoc, 0);
        }
        setUniformMat4(uInvViewProjLoc, invViewProj);
        setUniformVec3(uCameraPosLoc, (float) camPos.x, (float) camPos.y, (float) camPos.z);
        setUniformVec3(uPlayerPosLoc, (float) hero.x, (float) hero.y, (float) hero.z);
        setUniformVec4(uFogParamsLoc, inner, outer, MAX_ALPHA, 0.0f);
        setUniformVec3(uFogColorLoc, COL_R, COL_G, COL_B);

        // Полноэкранный квад в NDC [-1..1]
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
        buffer.vertex(-1.0, -1.0, 0.0).next();
        buffer.vertex( 1.0, -1.0, 0.0).next();
        buffer.vertex( 1.0,  1.0, 0.0).next();
        buffer.vertex(-1.0,  1.0, 0.0).next();
        BufferRenderer.draw(buffer.end());
        VertexBuffer.unbind();

        GlStateManager._glUseProgram(0);
        GlStateManager._bindTexture(0);
        GlStateManager._activeTexture(prevActiveTex);

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void ensureDepthTexture(int width, int height) {
        if (depthCopyTexId != -1 && depthCopyWidth == width && depthCopyHeight == height) {
            return;
        }
        if (depthCopyTexId == -1) {
            depthCopyTexId = GlStateManager._genTexture();
        }
        depthCopyWidth = width;
        depthCopyHeight = height;

        GlStateManager._bindTexture(depthCopyTexId);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, 0);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(
                GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT,
                width, height, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, null
        );
    }

    private static boolean ensureShaderProgram() {
        if (programId != -1) return true;
        if (shaderFailed) return false;

        int vertId = -1;
        int fragId = -1;
        try {
            vertId = compileShader(GL_VERTEX_SHADER, VERT_SHADER_SRC);
            fragId = compileShader(GL_FRAGMENT_SHADER, FRAG_SHADER_SRC);
            if (vertId == -1 || fragId == -1) {
                shaderFailed = true;
                return false;
            }

            int prog = GlStateManager.glCreateProgram();
            if (prog <= 0) {
                shaderFailed = true;
                return false;
            }

            GlStateManager.glAttachShader(prog, vertId);
            GlStateManager.glAttachShader(prog, fragId);
            GlStateManager._glBindAttribLocation(prog, 0, "Position");
            GlStateManager.glLinkProgram(prog);

            int linked = GlStateManager.glGetProgrami(prog, GL_LINK_STATUS);
            if (linked == 0) {
                String log = GlStateManager.glGetProgramInfoLog(prog, 32768);
                LOGGER.error("Failed to link screen-space fog shader program: {}", log);
                GlStateManager.glDeleteProgram(prog);
                shaderFailed = true;
                return false;
            }

            programId = prog;
            uDepthTexLoc = GlStateManager._glGetUniformLocation(prog, "uDepthTex");
            uInvViewProjLoc = GlStateManager._glGetUniformLocation(prog, "uInvViewProj");
            uCameraPosLoc = GlStateManager._glGetUniformLocation(prog, "uCameraPos");
            uPlayerPosLoc = GlStateManager._glGetUniformLocation(prog, "uPlayerPos");
            uFogParamsLoc = GlStateManager._glGetUniformLocation(prog, "uFogParams");
            uFogColorLoc = GlStateManager._glGetUniformLocation(prog, "uFogColor");
            return true;
        } finally {
            if (vertId != -1) GlStateManager.glDeleteShader(vertId);
            if (fragId != -1) GlStateManager.glDeleteShader(fragId);
        }
    }

    private static int compileShader(int type, String source) {
        int shader = GlStateManager.glCreateShader(type);
        if (shader <= 0) return -1;
        GlStateManager.glShaderSource(shader, List.of(source));
        GlStateManager.glCompileShader(shader);
        int compiled = GlStateManager.glGetShaderi(shader, GL_COMPILE_STATUS);
        if (compiled == 0) {
            String log = GlStateManager.glGetShaderInfoLog(shader, 32768);
            LOGGER.error("Failed to compile fog shader (type {}): {}", type, log);
            GlStateManager.glDeleteShader(shader);
            return -1;
        }
        return shader;
    }

    private static void setUniformVec3(int loc, float x, float y, float z) {
        if (loc < 0) return;
        VEC3_BUF.clear();
        VEC3_BUF.put(x).put(y).put(z).flip();
        GlStateManager._glUniform3(loc, VEC3_BUF);
    }

    private static void setUniformVec4(int loc, float x, float y, float z, float w) {
        if (loc < 0) return;
        VEC4_BUF.clear();
        VEC4_BUF.put(x).put(y).put(z).put(w).flip();
        GlStateManager._glUniform4(loc, VEC4_BUF);
    }

    private static void setUniformMat4(int loc, Matrix4f mat) {
        if (loc < 0) return;
        MAT4_BUF.clear();
        mat.get(MAT4_BUF);
        MAT4_BUF.position(0).limit(16);
        GlStateManager._glUniformMatrix4(loc, false, MAT4_BUF);
    }
}
