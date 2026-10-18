package com.bobincraft.siege;

import com.bobincraft.weapon.ShotServer;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Difficulty;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Режим «Осада»: игрок удерживает точку от нарастающих волн врагов.
 * Между волнами — передышка и награда (изумруды, железо, патронный припас в виде пороха).
 * Команды: /bobin siege start [волна], /bobin siege stop, /bobin siege status.
 * Волны используют ванильных мобов, поэтому работает без ресурспаков и в любом мире.
 */
public final class SiegeManager {
    private SiegeManager() {}

    private static final int PREP_TICKS = 200;          // передышка перед волной (10 с)
    private static final int SPAWN_RADIUS_MIN = 16;
    private static final int SPAWN_RADIUS_MAX = 24;
    private static final double LEAVE_RADIUS = 70.0;    // уйти дальше — осада проиграна
    private static final int MAX_MOBS_PER_WAVE = 36;

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private static final class Session {
        final UUID playerId;
        final ServerWorld world;
        final BlockPos center;
        final ServerBossBar bar = new ServerBossBar(Text.literal("Осада"), BossBar.Color.RED, BossBar.Style.NOTCHED_10);
        final List<MobEntity> mobs = new ArrayList<>();
        int wave;
        int waveTotal;
        int prepTicks = PREP_TICKS;
        int kills;
        boolean fighting;

        Session(ServerPlayerEntity player, int startWave) {
            this.playerId = player.getUuid();
            this.world = (ServerWorld) player.getWorld();
            this.center = player.getBlockPos();
            this.wave = startWave - 1;
            bar.addPlayer(player);
        }
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(SiegeManager::tick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("bobin")
                        .then(CommandManager.literal("siege")
                                .then(CommandManager.literal("start")
                                        .executes(ctx -> start(ctx.getSource(), 1))
                                        .then(CommandManager.argument("wave", IntegerArgumentType.integer(1, 100))
                                                .executes(ctx -> start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "wave")))))
                                .then(CommandManager.literal("stop")
                                        .executes(ctx -> stop(ctx.getSource())))
                                .then(CommandManager.literal("status")
                                        .executes(ctx -> status(ctx.getSource()))))));
    }

    // ---------------------------------------------------------------- команды

    private static int start(ServerCommandSource src, int startWave) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) {
            src.sendError(Text.literal("Команду может выполнить только игрок."));
            return 0;
        }
        if (player.getWorld().getDifficulty() == Difficulty.PEACEFUL) {
            src.sendError(Text.literal("На мирной сложности враги не появятся — повысьте сложность."));
            return 0;
        }
        if (SESSIONS.containsKey(player.getUuid())) {
            src.sendError(Text.literal("Осада уже идёт. Остановить: /bobin siege stop"));
            return 0;
        }
        SESSIONS.put(player.getUuid(), new Session(player, startWave));
        player.sendMessage(Text.literal("§c[Осада] §fДержите позицию! Первая волна через 10 секунд. Уйдёте дальше "
                + (int) LEAVE_RADIUS + " блоков — осада провалена."), false);
        return 1;
    }

    private static int stop(ServerCommandSource src) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) return 0;
        Session s = SESSIONS.get(player.getUuid());
        if (s == null) {
            src.sendError(Text.literal("Осада не запущена."));
            return 0;
        }
        end(s, player, "Осада остановлена");
        return 1;
    }

    private static int status(ServerCommandSource src) {
        ServerPlayerEntity player = src.getPlayer();
        if (player == null) return 0;
        Session s = SESSIONS.get(player.getUuid());
        if (s == null) {
            src.sendFeedback(() -> Text.literal("Осада не запущена. Старт: /bobin siege start [волна]"), false);
            return 0;
        }
        int alive = aliveCount(s);
        src.sendFeedback(() -> Text.literal("Осада: волна " + s.wave + ", врагов осталось " + alive + ", убито " + s.kills), false);
        return 1;
    }

    // ---------------------------------------------------------------- игровой цикл

    private static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;
        Iterator<Session> it = SESSIONS.values().iterator();
        while (it.hasNext()) {
            Session s = it.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(s.playerId);
            if (player == null) {
                cleanup(s);
                it.remove();
                continue;
            }
            if (!player.isAlive()) {
                finish(s, player, "§cВы погибли");
                it.remove();
                continue;
            }
            if (player.getWorld() != s.world || player.squaredDistanceTo(s.center.getX(), s.center.getY(), s.center.getZ()) > LEAVE_RADIUS * LEAVE_RADIUS) {
                finish(s, player, "§cВы покинули зону осады");
                it.remove();
                continue;
            }
            if (s.world.getDifficulty() == Difficulty.PEACEFUL) {
                finish(s, player, "§7Осада прервана: мирная сложность");
                it.remove();
                continue;
            }

            if (s.fighting) {
                int alive = aliveCount(s);
                s.bar.setPercent(s.waveTotal == 0 ? 0f : Math.max(0f, Math.min(1f, alive / (float) s.waveTotal)));
                s.bar.setName(Text.literal("Осада — волна " + s.wave + " | врагов: " + alive + " | убито: " + s.kills));
                if (alive == 0) {
                    reward(s, player);
                    s.fighting = false;
                    s.prepTicks = PREP_TICKS;
                }
            } else {
                s.prepTicks--;
                s.bar.setPercent(Math.max(0f, s.prepTicks / (float) PREP_TICKS));
                s.bar.setName(Text.literal("Осада — волна " + (s.wave + 1) + " через " + (s.prepTicks / 20 + 1) + " с"));
                if (s.prepTicks <= 0) {
                    s.wave++;
                    spawnWave(s, player);
                    s.fighting = true;
                }
            }
        }
    }

    private static int aliveCount(Session s) {
        int alive = 0;
        Iterator<MobEntity> it = s.mobs.iterator();
        while (it.hasNext()) {
            MobEntity m = it.next();
            if (m.isAlive() && !m.isRemoved()) {
                alive++;
            } else {
                s.kills++;
                it.remove();
            }
        }
        return alive;
    }

    // ---------------------------------------------------------------- волны

    private static void spawnWave(Session s, ServerPlayerEntity player) {
        Random rnd = s.world.getRandom();
        boolean boss = s.wave % 5 == 0;
        int count = Math.min(MAX_MOBS_PER_WAVE, 4 + s.wave * 3);
        if (boss) count = Math.max(2, count / 3);

        s.mobs.clear();
        for (int i = 0; i < count; i++) {
            BlockPos pos = findSpawnPos(s, rnd);
            if (pos == null) continue;
            EntityType<? extends MobEntity> type = pickType(s.wave, rnd);
            MobEntity mob = type.create(s.world);
            if (mob == null) continue;
            prepare(s, mob, pos, rnd, 1.0 + 0.08 * s.wave);
            mob.setTarget(player);
            s.mobs.add(mob);
        }

        if (boss) {
            BlockPos pos = findSpawnPos(s, rnd);
            MobEntity ravager = pos == null ? null : EntityType.RAVAGER.create(s.world);
            if (ravager != null) {
                prepare(s, ravager, pos, rnd, 1.0 + 0.15 * (s.wave / 5));
                ravager.setCustomName(Text.literal("§4Таран волны " + s.wave));
                ravager.setCustomNameVisible(true);
                ravager.setTarget(player);
                s.mobs.add(ravager);
            }
        }

        s.waveTotal = s.mobs.size();
        player.sendMessage(Text.literal("§c[Осада] §fВолна " + s.wave + (boss ? " §4(босс!)" : "") + " — врагов: " + s.waveTotal), false);
        if (s.waveTotal == 0) {
            // Не нашлось ни одной подходящей точки — засчитываем волну и идём дальше
            player.sendMessage(Text.literal("§7[Осада] Не удалось найти место для врагов, волна пропущена."), false);
        }
    }

    private static EntityType<? extends MobEntity> pickType(int wave, Random rnd) {
        List<EntityType<? extends MobEntity>> pool = new ArrayList<>();
        pool.add(EntityType.ZOMBIE);
        pool.add(EntityType.ZOMBIE);
        if (wave >= 2) pool.add(EntityType.SKELETON);
        if (wave >= 3) pool.add(EntityType.HUSK);
        if (wave >= 4) pool.add(EntityType.SPIDER);
        if (wave >= 5) pool.add(EntityType.STRAY);
        if (wave >= 6) pool.add(EntityType.CAVE_SPIDER);
        if (wave >= 8) pool.add(EntityType.WITCH);
        if (wave >= 10) pool.add(EntityType.VINDICATOR);
        return pool.get(rnd.nextInt(pool.size()));
    }

    private static void prepare(Session s, MobEntity mob, BlockPos pos, Random rnd, double healthScale) {
        mob.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, rnd.nextFloat() * 360f, 0f);
        mob.initialize(s.world, s.world.getLocalDifficulty(pos), SpawnReason.EVENT, null, null);
        mob.setPersistent();
        EntityAttributeInstance hp = mob.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(hp.getBaseValue() * healthScale);
            mob.setHealth(mob.getMaxHealth());
        }
        // Чтобы нежить не сгорала на солнце и волна не «таяла» сама по себе
        mob.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 1_000_000, 0, false, false));
        s.world.spawnEntity(mob);
    }

    private static BlockPos findSpawnPos(Session s, Random rnd) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double r = SPAWN_RADIUS_MIN + rnd.nextDouble() * (SPAWN_RADIUS_MAX - SPAWN_RADIUS_MIN);
            int x = s.center.getX() + (int) Math.round(Math.cos(ang) * r);
            int z = s.center.getZ() + (int) Math.round(Math.sin(ang) * r);
            if (!s.world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = s.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (!s.world.getFluidState(pos.down()).isEmpty()) continue;       // не в воде
            if (!s.world.getBlockState(pos).getCollisionShape(s.world, pos).isEmpty()) continue;
            if (Math.abs(y - s.center.getY()) > 12) continue;                // не в пропасти и не на горе
            return pos;
        }
        return null;
    }

    // ---------------------------------------------------------------- награды и завершение

    private static void reward(Session s, ServerPlayerEntity player) {
        int emeralds = 1 + s.wave;
        player.getInventory().offerOrDrop(new ItemStack(Items.EMERALD, emeralds));
        player.getInventory().offerOrDrop(new ItemStack(Items.GUNPOWDER, 2 + s.wave / 2));
        StringBuilder bonus = new StringBuilder();
        if (s.wave % 3 == 0) {
            player.getInventory().offerOrDrop(new ItemStack(Items.IRON_INGOT, 3 + s.wave / 3));
            bonus.append(" + железо");
        }
        if (s.wave % 5 == 0) {
            player.getInventory().offerOrDrop(new ItemStack(Items.GOLDEN_APPLE, 1));
            bonus.append(" + золотое яблоко");
        }
        player.heal(6.0f);
        player.getHungerManager().add(6, 0.6f);
        player.sendMessage(Text.literal("§a[Осада] §fВолна " + s.wave + " отбита! Награда: изумруды ×" + emeralds
                + ", порох" + bonus + ". Передышка 10 секунд."), false);
    }

    private static void finish(Session s, ServerPlayerEntity player, String reason) {
        end(s, player, reason);
    }

    private static void end(Session s, ServerPlayerEntity player, String reason) {
        int reached = Math.max(0, s.fighting ? s.wave - 1 : s.wave);
        aliveCount(s);
        player.sendMessage(Text.literal("§c[Осада] §f" + reason + ". Пройдено волн: " + reached + ", убито врагов: " + s.kills), false);
        cleanup(s);
        SESSIONS.remove(s.playerId);
    }

    private static void cleanup(Session s) {
        for (MobEntity m : s.mobs) {
            if (m.isAlive()) m.discard();
        }
        s.mobs.clear();
        s.bar.clearPlayers();
        ShotServer.forget(s.playerId);
    }
}
