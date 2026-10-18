package com.bobincraft.warzone;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.gen.chunk.ChunkGenerator;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Измерение «Зона боевых действий» ({@code bobin_craft:warzone}): регистрация генератора и источника биомов,
 * команды входа/выхода, разведки вражеских баз ({@code /bobin warzone bases}), быстрой телепортации к базам
 * ({@code /bobin warzone tp <номер>}) и удержание игроков внутри арены.
 *
 * Само измерение описано датапаком мода: data/bobin_craft/dimension/warzone.json,
 * dimension_type/warzone.json и worldgen/biome/frontier_*.json — оно появляется и в новых, и в уже созданных мирах.
 */
public final class WarzoneDimension {
    private WarzoneDimension() {}

    public static final String MOD_ID = "bobin_craft";
    public static final Identifier ID = new Identifier(MOD_ID, "warzone");
    public static final RegistryKey<World> WORLD = RegistryKey.of(RegistryKeys.WORLD, ID);

    /** Откуда игрок пришёл (чтобы /bobin warzone leave вернул его на то же место). */
    private record ReturnPoint(RegistryKey<World> world, Vec3d pos, float yaw, float pitch) {}

    private static final Map<UUID, ReturnPoint> RETURN_POINTS = new ConcurrentHashMap<>();

    public static void register() {
        org.slf4j.LoggerFactory.getLogger("bobin_craft/warzone")
                .info("[Warzone] Сборка 1.0.12: дорожные блокпосты и баррикады, старые поля боёв — чертежи по реальному рельефу (плюс базы, вышки, укрытия)");
        Registry.register(Registries.BIOME_SOURCE, ID, WarzoneBiomeSource.CODEC);
        Registry.register(Registries.CHUNK_GENERATOR, ID, WarzoneChunkGenerator.CODEC);

        ServerTickEvents.END_WORLD_TICK.register(WarzoneDimension::keepInsideArena);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("bobin")
                        .then(CommandManager.literal("warzone")
                                .executes(ctx -> enter(ctx.getSource()))
                                .then(CommandManager.literal("enter")
                                        .executes(ctx -> enter(ctx.getSource())))
                                .then(CommandManager.literal("leave")
                                        .executes(ctx -> leave(ctx.getSource())))
                                .then(CommandManager.literal("info")
                                        .executes(ctx -> info(ctx.getSource())))
                                .then(CommandManager.literal("bases")
                                        .executes(ctx -> listBases(ctx.getSource())))
                                .then(CommandManager.literal("list")
                                        .executes(ctx -> listBases(ctx.getSource())))
                                .then(CommandManager.literal("hideouts")
                                        .executes(ctx -> listHideouts(ctx.getSource())))
                                .then(CommandManager.literal("checkpoints")
                                        .executes(ctx -> listCheckpoints(ctx.getSource())))
                                .then(CommandManager.literal("battlefields")
                                        .executes(ctx -> listBattlefields(ctx.getSource())))
                                .then(CommandManager.literal("tpc")
                                        .requires(src -> src.hasPermissionLevel(2))
                                        .then(CommandManager.argument("id", IntegerArgumentType.integer(1, 512))
                                                .executes(ctx -> tpToCheckpoint(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
                                .then(CommandManager.literal("tpf")
                                        .requires(src -> src.hasPermissionLevel(2))
                                        .then(CommandManager.argument("id", IntegerArgumentType.integer(1, 512))
                                                .executes(ctx -> tpToBattlefield(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
                                .then(CommandManager.literal("tph")
                                        .requires(src -> src.hasPermissionLevel(2))
                                        .then(CommandManager.argument("id", IntegerArgumentType.integer(1, 512))
                                                .executes(ctx -> tpToHideout(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id")))))
                                .then(CommandManager.literal("tp")
                                        .requires(src -> src.hasPermissionLevel(2))
                                        .then(CommandManager.argument("id", IntegerArgumentType.integer(1, 128))
                                                .executes(ctx -> tpToBase(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "id"))))))));
    }

    // ------------------------------------------------------------------ доступ к рельефу

    /** Генератор Зоны боевых действий в данном мире, если это она. */
    public static WarzoneChunkGenerator generatorOf(ServerWorld world) {
        if (world == null) return null;
        ChunkGenerator gen = world.getChunkManager().getChunkGenerator();
        return gen instanceof WarzoneChunkGenerator wz ? wz : null;
    }

    public static boolean isWarzone(World world) {
        return world != null && world.getRegistryKey().equals(WORLD);
    }

    // ------------------------------------------------------------------ команды

    private static int enter(ServerCommandSource src) {
        if (com.bobincraft.raid.RaidManager.isManaged(src.getServer())) {
            ServerPlayerEntity player = src.getPlayer();
            if (player != null) com.bobincraft.raid.RaidManager.openMap(player, com.bobincraft.raid.HubBuilder.TABLE);
            return 0;
        }
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) {
            src.sendError(Text.literal("Команду может выполнить только игрок."));
            return 0;
        }
        MinecraftServer server = src.getServer();
        ServerWorld target = server.getWorld(WORLD);
        if (target == null) {
            src.sendError(Text.literal("Измерение bobin_craft:warzone не найдено. Мир создан без датапака мода? "
                    + "Перезапустите мир с установленным модом."));
            return 0;
        }
        WarzoneChunkGenerator gen = generatorOf(target);
        if (gen == null) {
            src.sendError(Text.literal("У измерения bobin_craft:warzone неверный генератор — проверьте data/bobin_craft/dimension/warzone.json."));
            return 0;
        }
        if (target == player.getServerWorld()) {
            src.sendError(Text.literal("Вы уже в Зоне боевых действий. Выйти: /bobin warzone leave"));
            return 0;
        }

        WarzoneTerrain t = gen.terrain();
        RETURN_POINTS.put(player.getUuid(), new ReturnPoint(player.getServerWorld().getRegistryKey(),
                player.getPos(), player.getYaw(), player.getPitch()));

        double x = t.landingX() + 0.5;
        double z = t.landingZ() + 0.5;
        double y = WarzoneTerrain.LANDING_Y + 1;
        player.teleport(target, x, y, z, 180.0f, 0.0f);   // смотрим на север — вглубь арены
        player.fallDistance = 0;
        int baseCount = t.basePlan() != null ? t.basePlan().sites().size() : 0;
        player.sendMessage(Text.literal("§6[Зона боевых действий] §fВы высадились на южной окраине арены "
                + t.size + "×" + t.size + " (вражеских объектов: §c" + baseCount
                + "§f). Разведсводка: §e/bobin warzone bases§f, выход: §e/bobin warzone leave"), false);
        return 1;
    }

    private static int leave(ServerCommandSource src) {
        ServerPlayerEntity raidPlayer = src.getPlayer();
        if (raidPlayer != null && com.bobincraft.raid.RaidManager.requestExtraction(raidPlayer)) return 0;
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) {
            src.sendError(Text.literal("Команду может выполнить только игрок."));
            return 0;
        }
        if (!isWarzone(player.getWorld())) {
            src.sendError(Text.literal("Вы не в Зоне боевых действий."));
            return 0;
        }
        MinecraftServer server = src.getServer();
        ReturnPoint rp = RETURN_POINTS.remove(player.getUuid());
        ServerWorld dest = rp == null ? null : server.getWorld(rp.world());
        if (dest != null) {
            player.teleport(dest, rp.pos().x, rp.pos().y, rp.pos().z, rp.yaw(), rp.pitch());
        } else {
            // Нет точки возврата — отправляем на точку возрождения или в мировой спавн
            ServerWorld spawnWorld = server.getWorld(player.getSpawnPointDimension());
            BlockPos sp = player.getSpawnPointPosition();
            if (spawnWorld == null || sp == null) {
                spawnWorld = server.getOverworld();
                sp = spawnWorld.getSpawnPos();
            }
            int y = spawnWorld.getTopY(Heightmap.Type.MOTION_BLOCKING, sp.getX(), sp.getZ());
            player.teleport(spawnWorld, sp.getX() + 0.5, Math.max(y, sp.getY()), sp.getZ() + 0.5, player.getYaw(), 0.0f);
        }
        player.fallDistance = 0;
        player.sendMessage(Text.literal("§6[Зона боевых действий] §fВы покинули зону."), false);
        return 1;
    }

    private static int info(ServerCommandSource src) {
        MinecraftServer server = src.getServer();
        ServerWorld wz = server.getWorld(WORLD);
        WarzoneChunkGenerator gen = generatorOf(wz);
        if (gen == null) {
            src.sendError(Text.literal("Измерение Зоны боевых действий недоступно."));
            return 0;
        }
        WarzoneTerrain t = gen.terrain();
        WarzoneBasePlan plan = t.basePlan();
        int totalSites = plan != null ? plan.sites().size() : 0;
        src.sendFeedback(() -> Text.literal("§6[Зона боевых действий] §fАрена " + t.size + "×" + t.size + " бл. (X/Z от "
                + t.min + " до " + (t.max - 1) + "), посадка (" + t.landingX() + ", " + t.landingZ()
                + "), вражеских баз и аванпостов: §e" + totalSites + " §7(список: /bobin warzone bases), лесных укрытий: §e"
                + t.hideoutPlan().hideouts().size() + "§f, блокпостов: §e" + t.poiPlan().checkpoints().size()
                + "§f, полей боёв: §e" + t.poiPlan().battlefields().size()), false);

        ServerPlayerEntity p = src.getPlayer();
        if (p != null && isWarzone(p.getWorld())) {
            int px = p.getBlockX();
            int pz = p.getBlockZ();
            WarzoneBasePlan.Site nearest = plan != null ? plan.nearestSite(px, pz) : null;
            String nearStr = nearest != null
                    ? ", ближайший объект: §e#" + nearest.id + " " + nearest.callsign + " §7("
                      + nearest.type.title + ", " + (int) Math.round(nearest.distanceTo(px, pz)) + " бл.)"
                    : "";
            src.sendFeedback(() -> Text.literal("Вы здесь: местность " + (t.inside(px, pz) ? t.region(px, pz) : "—")
                    + ", до края " + t.edgeDistance(px, pz) + " бл." + nearStr), false);
        }
        return 1;
    }

    private static int listBases(ServerCommandSource src) {
        MinecraftServer server = src.getServer();
        ServerWorld wz = server.getWorld(WORLD);
        WarzoneChunkGenerator gen = generatorOf(wz);
        if (gen == null) {
            src.sendError(Text.literal("Измерение Зоны боевых действий недоступно."));
            return 0;
        }
        WarzoneTerrain t = gen.terrain();
        WarzoneBasePlan plan = t.basePlan();
        List<WarzoneBasePlan.Site> sites = plan != null ? plan.sites() : List.of();
        ServerPlayerEntity p = src.getPlayer();
        double refX = (p != null && isWarzone(p.getWorld())) ? p.getX() : t.landingX();
        double refZ = (p != null && isWarzone(p.getWorld())) ? p.getZ() : t.landingZ();

        src.sendFeedback(() -> Text.literal("§6=== Вражеские объекты в Зоне боевых действий (" + sites.size() + ") ==="), false);
        for (WarzoneBasePlan.Site s : sites) {
            int dist = (int) Math.round(s.distanceTo(refX, refZ));
            int sentries = s.countSpots(WarzoneBasePlan.SpotRole.SENTRY);
            int garrison = s.countSpots(WarzoneBasePlan.SpotRole.SOLDIER) + s.countSpots(WarzoneBasePlan.SpotRole.BANDIT);
            int patrols = s.countSpots(WarzoneBasePlan.SpotRole.PATROL_WAYPOINT);
            src.sendFeedback(() -> Text.literal("§e#" + s.id + " §f" + s.callsign + " §7[" + s.type.title + ", " + s.region + ", " + s.faction.title + "] "
                    + "§a(" + s.cx + ", " + s.baseY + ", " + s.cz + ") §7— " + dist + " бл. "
                    + "(вышек/часовых: " + sentries + ", гарнизон: " + garrison + ", патруль: " + patrols + ")"), false);
        }
        src.sendFeedback(() -> Text.literal("§7Быстрый переход к объекту: §e/bobin warzone tp <номер>"), false);
        return 1;
    }

    private static int listHideouts(ServerCommandSource src) {
        ServerWorld wz = src.getServer().getWorld(WORLD);
        WarzoneChunkGenerator gen = generatorOf(wz);
        if (gen == null) {
            src.sendError(Text.literal("Измерение Зоны боевых действий недоступно."));
            return 0;
        }
        List<HideoutPlan.Hideout> list = gen.terrain().hideoutPlan().hideouts();
        src.sendFeedback(() -> Text.literal("§6=== Лесные укрытия (" + list.size() + ") ==="), false);
        for (HideoutPlan.Hideout h : list) {
            src.sendFeedback(() -> Text.literal("§e#" + h.id() + " §f" + h.name() + " §7[" + h.region() + "] §a("
                    + h.cx() + ", " + h.baseY() + ", " + h.cz() + ")"), false);
        }
        src.sendFeedback(() -> Text.literal("§7Быстрый переход: §e/bobin warzone tph <номер>"), false);
        return 1;
    }

    private static int tpToHideout(ServerCommandSource src, int id) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) {
            src.sendError(Text.literal("Команду может выполнить только игрок."));
            return 0;
        }
        ServerWorld target = src.getServer().getWorld(WORLD);
        WarzoneChunkGenerator gen = generatorOf(target);
        if (target == null || gen == null) {
            src.sendError(Text.literal("Измерение Зоны боевых действий недоступно."));
            return 0;
        }
        WarzoneTerrain t = gen.terrain();
        HideoutPlan.Hideout h = t.hideoutPlan().byId(id);
        if (h == null) {
            src.sendError(Text.literal("Укрытие #" + id + " не найдено (доступны 1.." + t.hideoutPlan().hideouts().size()
                    + "). Список: /bobin warzone hideouts"));
            return 0;
        }
        if (!isWarzone(player.getWorld())) {
            RETURN_POINTS.put(player.getUuid(), new ReturnPoint(player.getServerWorld().getRegistryKey(),
                    player.getPos(), player.getYaw(), player.getPitch()));
        }
        // точка в 8 блоках перед входом (локальная +V), лицом к укрытию
        int ax = WarzoneBasePlan.rotX(h.cx(), 0, 8, h.rotation());
        int az = WarzoneBasePlan.rotZ(h.cz(), 0, 8, h.rotation());
        BlockPos safe = findSafeSpot(target, t, ax, az, h.baseY());
        double ex = safe.getX() + 0.5, ez = safe.getZ() + 0.5;
        float yaw = (float) (MathHelper.atan2(h.cz() + 0.5 - ez, h.cx() + 0.5 - ex) * (180.0 / Math.PI)) - 90.0f;
        player.teleport(target, ex, safe.getY(), ez, yaw, 0.0f);
        player.fallDistance = 0;
        player.sendMessage(Text.literal("§6[Разведка] §fПеремещение к укрытию §e#" + h.id() + " " + h.name()
                + " §7(центр " + h.cx() + ", " + h.baseY() + ", " + h.cz() + ")"), false);
        return 1;
    }

    private static int listCheckpoints(ServerCommandSource src) {
        ServerWorld wz = src.getServer().getWorld(WORLD);
        WarzoneChunkGenerator gen = generatorOf(wz);
        if (gen == null) {
            src.sendError(Text.literal("Измерение Зоны боевых действий недоступно."));
            return 0;
        }
        List<PoiPlan.Checkpoint> list = gen.terrain().poiPlan().checkpoints();
        src.sendFeedback(() -> Text.literal("§6=== Дорожные блокпосты (" + list.size() + ") ==="), false);
        for (PoiPlan.Checkpoint c : list) {
            src.sendFeedback(() -> Text.literal("§e#" + c.id() + " §f" + c.name() + " §7[" + c.region() + "] §a("
                    + c.cx() + ", " + c.baseY() + ", " + c.cz() + ")"), false);
        }
        src.sendFeedback(() -> Text.literal("§7Быстрый переход: §e/bobin warzone tpc <номер>"), false);
        return 1;
    }

    private static int listBattlefields(ServerCommandSource src) {
        ServerWorld wz = src.getServer().getWorld(WORLD);
        WarzoneChunkGenerator gen = generatorOf(wz);
        if (gen == null) {
            src.sendError(Text.literal("Измерение Зоны боевых действий недоступно."));
            return 0;
        }
        List<PoiPlan.Battlefield> list = gen.terrain().poiPlan().battlefields();
        src.sendFeedback(() -> Text.literal("§6=== Старые поля боёв (" + list.size() + ") ==="), false);
        for (PoiPlan.Battlefield f : list) {
            src.sendFeedback(() -> Text.literal("§e#" + f.id() + " §f" + f.name() + " §7[" + f.region() + "] §a("
                    + f.cx() + ", " + f.baseY() + ", " + f.cz() + ")"), false);
        }
        src.sendFeedback(() -> Text.literal("§7Быстрый переход: §e/bobin warzone tpf <номер>"), false);
        return 1;
    }

    private static int tpToCheckpoint(ServerCommandSource src, int id) {
        WarzoneChunkGenerator gen = generatorOf(src.getServer().getWorld(WORLD));
        if (gen == null) {
            src.sendError(Text.literal("Измерение Зоны боевых действий недоступно."));
            return 0;
        }
        PoiPlan.Checkpoint c = gen.terrain().poiPlan().checkpointById(id);
        if (c == null) {
            src.sendError(Text.literal("Блокпост #" + id + " не найден (доступны 1.." + gen.terrain().poiPlan().checkpoints().size()
                    + "). Список: /bobin warzone checkpoints"));
            return 0;
        }
        return tpToPoi(src, "блокпосту", "#" + c.id() + " " + c.name(), c.cx(), c.baseY(), c.cz(), c.rotation(), 10);
    }

    private static int tpToBattlefield(ServerCommandSource src, int id) {
        WarzoneChunkGenerator gen = generatorOf(src.getServer().getWorld(WORLD));
        if (gen == null) {
            src.sendError(Text.literal("Измерение Зоны боевых действий недоступно."));
            return 0;
        }
        PoiPlan.Battlefield f = gen.terrain().poiPlan().battlefieldById(id);
        if (f == null) {
            src.sendError(Text.literal("Поле боя #" + id + " не найдено (доступны 1.." + gen.terrain().poiPlan().battlefields().size()
                    + "). Список: /bobin warzone battlefields"));
            return 0;
        }
        return tpToPoi(src, "полю боя", "#" + f.id() + " " + f.name(), f.cx(), f.baseY(), f.cz(), f.rotation(), 16);
    }

    /** Общий переход к лёгкой точке интереса: в {@code dist} блоках по локальной +V от центра, лицом к центру. */
    private static int tpToPoi(ServerCommandSource src, String what, String label, int cx, int baseY, int cz, int rot, int dist) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) {
            src.sendError(Text.literal("Команду может выполнить только игрок."));
            return 0;
        }
        ServerWorld target = src.getServer().getWorld(WORLD);
        WarzoneChunkGenerator gen = generatorOf(target);
        if (target == null || gen == null) {
            src.sendError(Text.literal("Измерение Зоны боевых действий недоступно."));
            return 0;
        }
        if (!isWarzone(player.getWorld())) {
            RETURN_POINTS.put(player.getUuid(), new ReturnPoint(player.getServerWorld().getRegistryKey(),
                    player.getPos(), player.getYaw(), player.getPitch()));
        }
        int ax = WarzoneBasePlan.rotX(cx, 0, dist, rot);
        int az = WarzoneBasePlan.rotZ(cz, 0, dist, rot);
        BlockPos safe = findSafeSpot(target, gen.terrain(), ax, az, baseY);
        double ex = safe.getX() + 0.5, ez = safe.getZ() + 0.5;
        float yaw = (float) (MathHelper.atan2(cz + 0.5 - ez, cx + 0.5 - ex) * (180.0 / Math.PI)) - 90.0f;
        player.teleport(target, ex, safe.getY(), ez, yaw, 0.0f);
        player.fallDistance = 0;
        player.sendMessage(Text.literal("§6[Разведка] §fПеремещение к " + what + " §e" + label
                + " §7(центр " + cx + ", " + baseY + ", " + cz + ")"), false);
        return 1;
    }

    private static int tpToBase(ServerCommandSource src, int id) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) {
            src.sendError(Text.literal("Команду может выполнить только игрок."));
            return 0;
        }
        MinecraftServer server = src.getServer();
        ServerWorld target = server.getWorld(WORLD);
        WarzoneChunkGenerator gen = generatorOf(target);
        if (target == null || gen == null) {
            src.sendError(Text.literal("Измерение Зоны боевых действий недоступно."));
            return 0;
        }
        WarzoneTerrain t = gen.terrain();
        WarzoneBasePlan plan = t.basePlan();
        WarzoneBasePlan.Site site = plan != null ? plan.byId(id) : null;
        if (site == null) {
            int maxId = plan != null ? plan.sites().size() : 0;
            src.sendError(Text.literal("Объект #" + id + " не найден (доступны 1.." + maxId + "). Список: /bobin warzone bases"));
            return 0;
        }

        if (!isWarzone(player.getWorld())) {
            RETURN_POINTS.put(player.getUuid(), new ReturnPoint(player.getServerWorld().getRegistryKey(),
                    player.getPos(), player.getYaw(), player.getPitch()));
        }

        // Берём реальные блоки сгенерированного чанка (с постройками, мостами, деревьями), а не только рельеф
        BlockPos safe = findSafeSpot(target, t, site.entranceX(), site.entranceZ(), site.baseY);
        double ex = safe.getX() + 0.5;
        double ez = safe.getZ() + 0.5;
        double ey = safe.getY();
        float yaw = (float) (MathHelper.atan2(site.cz + 0.5 - ez, site.cx + 0.5 - ex) * (180.0 / Math.PI)) - 90.0f;

        player.teleport(target, ex, ey, ez, yaw, 0.0f);
        player.fallDistance = 0;
        player.sendMessage(Text.literal("§6[Разведка] §fПеремещение к объекту §e#" + site.id + " " + site.callsign
                + " §7(" + site.type.title + ", центр " + site.cx + ", " + site.baseY + ", " + site.cz + ")"), false);
        return 1;
    }

    /**
     * Ищет безопасную точку для телепорта: твёрдый пол, два свободных блока над ним, без воды и лавы.
     * Сначала проверяет саму точку у ворот, затем ближайшие клетки по спирали; запасной вариант — посадочная площадка.
     */
    private static BlockPos findSafeSpot(ServerWorld world, WarzoneTerrain t, int x, int z, int baseY) {
        int[][] offsets = new int[49][];
        int n = 0;
        offsets[n++] = new int[]{0, 0};
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    offsets[n++] = new int[]{dx, dz};
                }
            }
        }
        for (int i = 0; i < n; i++) {
            int px = x + offsets[i][0];
            int pz = z + offsets[i][1];
            if (!t.inside(px, pz)) continue;
            BlockPos found = scanColumn(world, px, pz, Math.max(baseY, t.height(px, pz)) + 6);
            if (found != null) return found;
        }
        return new BlockPos(t.landingX(), WarzoneTerrain.LANDING_Y + 1, t.landingZ());
    }

    private static BlockPos scanColumn(ServerWorld world, int x, int z, int startY) {
        // getBlockState на незагруженном чанке сгенерирует его (нужное чанку — одно, телепорт всё равно ждёт его)
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int top = Math.min(startY, world.getTopY() - 3);
        for (int y = top; y > world.getBottomY() + 1; y--) {
            net.minecraft.block.BlockState floor = world.getBlockState(pos.set(x, y - 1, z));
            net.minecraft.block.BlockState feet = world.getBlockState(pos.set(x, y, z));
            net.minecraft.block.BlockState head = world.getBlockState(pos.set(x, y + 1, z));
            if (!floor.getFluidState().isEmpty() || !feet.getFluidState().isEmpty()) {
                if (floor.getFluidState().isEmpty() == false) return null;   // дно озера — не подходит
                continue;
            }
            boolean solidFloor = floor.isSolid() && !floor.isIn(net.minecraft.registry.tag.BlockTags.LEAVES);
            if (solidFloor && feet.getCollisionShape(world, pos.set(x, y, z)).isEmpty()
                    && head.getCollisionShape(world, pos.set(x, y + 1, z)).isEmpty()
                    && feet.getFluidState().isEmpty() && head.getFluidState().isEmpty()) {
                return new BlockPos(x, y, z);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ границы арены

    /** Выход за пределы квадрата арены (полёт над скалами, эндер-жемчуг и т.п.) — возвращает игрока внутрь. */
    private static void keepInsideArena(ServerWorld world) {
        if (!isWarzone(world)) return;
        WarzoneChunkGenerator gen = generatorOf(world);
        if (gen == null) return;
        WarzoneTerrain t = gen.terrain();
        double lo = t.min + 3.0;
        double hi = t.max - 3.0;
        for (ServerPlayerEntity player : world.getPlayers()) {
            double px = player.getX();
            double pz = player.getZ();
            if (px >= lo && px <= hi && pz >= lo && pz <= hi) continue;
            double nx = MathHelper.clamp(px, lo, hi);
            double nz = MathHelper.clamp(pz, lo, hi);
            player.teleport(world, nx, player.getY(), nz, player.getYaw(), player.getPitch());
            player.setVelocity(Vec3d.ZERO);
            player.velocityModified = true;
            player.sendMessage(Text.literal("§7Дым и скалы — дальше пути нет."), true);
        }
    }
}
