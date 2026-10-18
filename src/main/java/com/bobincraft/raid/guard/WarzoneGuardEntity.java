package com.bobincraft.raid.guard;

import com.bobincraft.raid.WarzoneEncounterManager;
import com.bobincraft.warzone.WarzoneBasePlan;
import com.bobincraft.warzone.Faction;
import com.bobincraft.warzone.WarzoneDimension;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * A registered entity type is essential here: vanilla pillagers would reload their vanilla goals.
 * Perception, faction identity, finite firearm state, patrols and territory are server owned.
 */
public final class WarzoneGuardEntity extends PillagerEntity {
    private static final int MAX_TERRITORY_RADIUS = 96;
    private static final Faction[] FACTIONS = Faction.values();
    private static final TrackedData<Integer> FACTION = DataTracker.registerData(WarzoneGuardEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> VARIANT = DataTracker.registerData(WarzoneGuardEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Boolean> HUB_GUARD = DataTracker.registerData(WarzoneGuardEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private NpcWeapon weapon = NpcWeapon.AK47;
    private FactionLoadouts.Profile loadout;
    private NpcFireControl firearm;
    private int danger;

    private final GuardBrain brain = new GuardBrain();
    private BlockPos post = BlockPos.ORIGIN;
    private BlockPos territory = BlockPos.ORIGIN;
    private final List<BlockPos> patrol = new ArrayList<>();
    private WarzoneBasePlan.SpotRole role = WarzoneBasePlan.SpotRole.SOLDIER;
    private Vec3d lastKnownPosition;
    private int siteId;
    private int territoryRadius = 48;
    private int patrolIndex;
    private int nextPathAge;
    private int nextPerceptionAge;
    private int nextAlertAge;
    private boolean configured;
    private boolean simulationActive;

    public WarzoneGuardEntity(EntityType<? extends PillagerEntity> type, World world) {
        super(type, world);
        setPersistent();
        setAbleToJoinRaid(false);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        dataTracker.startTracking(FACTION, Faction.REGULAR_ARMY.ordinal());
        dataTracker.startTracking(VARIANT, 0);
        dataTracker.startTracking(HUB_GUARD, false);
    }

    public Faction faction() { return FACTIONS[Math.floorMod(dataTracker.get(FACTION), FACTIONS.length)]; }
    public int appearanceVariant() { return dataTracker.get(VARIANT); }
    public boolean isHubGuard() { return dataTracker.get(HUB_GUARD); }
    public NpcWeapon firearmType() { return weapon; }

    /** Friendly, lightweight persistent soldier. Hub construction owns placement and uniqueness. */
    public void assignHubGuard(BlockPos assignedPost) {
        dataTracker.set(HUB_GUARD, true);
        dataTracker.set(FACTION, Faction.REGULAR_ARMY.ordinal());
        dataTracker.set(VARIANT, 0);
        post = assignedPost.toImmutable();
        territory = post;
        siteId = -1;
        configured = true;
        weapon = NpcWeapon.AK47;
        equipStack(EquipmentSlot.MAINHAND, new ItemStack(weapon.item()));
        setEquipmentDropChance(EquipmentSlot.MAINHAND, 0);
        setAiDisabled(true);
        setInvulnerable(true);
        setTarget(null);
        setAttacking(false);
        setAbleToJoinRaid(false);
        setPersistent();
    }

    @Override
    protected boolean isDisallowedInPeaceful() { return !isHubGuard(); }

    @Override
    protected void initGoals() {
        goalSelector.add(0, new SwimGoal(this));
        goalSelector.add(1, new RetreatToCoverGoal());
        goalSelector.add(2, new FirearmApproachGoal());
        goalSelector.add(3, new AssignedPostGoal());
        // Intentionally no vanilla targetSelector, raid movement, or random wander goals.
    }

    public void assign(WarzoneBasePlan.Site site, WarzoneBasePlan.TacticalSpot spot, List<BlockPos> route) {
        siteId = site.id;
        post = getBlockPos().toImmutable();
        territory = new BlockPos(site.cx, site.baseY, site.cz);
        territoryRadius = Math.max(16, Math.min(MAX_TERRITORY_RADIUS, site.flatRadius + 22));
        role = spot.role();
        patrol.clear();
        if (role != WarzoneBasePlan.SpotRole.SENTRY) {
            route.stream().filter(this::insideTerritory).limit(16).forEach(patrol::add);
        }
        configured = true;
        dataTracker.set(FACTION, site.faction.ordinal());
        dataTracker.set(VARIANT, Math.floorMod(31 * site.id + spot.x() * 17 + spot.z() * 13, 3));
        danger = FactionLoadouts.danger(site);
        configureArmament(true);
    }

    private void configureArmament(boolean chooseWeapon) {
        loadout = FactionLoadouts.forFaction(faction());
        if (chooseWeapon) weapon = FactionLoadouts.choose(loadout, role, danger, appearanceVariant());
        firearm = new NpcFireControl(loadout.fireSettings(weapon, danger));
        if (chooseWeapon) {
            equipStack(EquipmentSlot.MAINHAND, new ItemStack(weapon.item()));
            equipStack(EquipmentSlot.HEAD, new ItemStack(loadout.armored() ? Items.IRON_HELMET : Items.LEATHER_HELMET));
            equipStack(EquipmentSlot.CHEST, new ItemStack(loadout.armored() && danger >= 2 ? Items.IRON_CHESTPLATE : Items.LEATHER_CHESTPLATE));
            for (EquipmentSlot slot : EquipmentSlot.values()) setEquipmentDropChance(slot, 0.0F);
            setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.05F);
            var health = getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            if (health != null) health.setBaseValue(faction().isBandit() ? 20 : danger >= 2 ? 28 : 24);
            setHealth(getMaxHealth());
        }
    }

    public int siteId() { return siteId; }
    public GuardBrain.State guardState() { return brain.state(); }

    public void hearGunfire(Vec3d position) {
        if (!configured || isHubGuard() || !insideTerritory(BlockPos.ofFloored(position))) return;
        lastKnownPosition = position;
        brain.hearNoise();
        nextPerceptionAge = 0;
    }

    public void hearAllyAlert(Vec3d position) {
        hearGunfire(position);
    }

    @Override
    public void tickMovement() {
        super.tickMovement();
        if (getWorld().isClient || !configured || isHubGuard()) return;
        tickFirearm();
        if (age < nextPerceptionAge) return;
        // UUID-derived stagger prevents a freshly spawned squad raycasting on the same tick.
        int phase = Math.floorMod(getUuid().hashCode(), 10);
        if ((age + phase) % 10 != 0) return;
        ServerWorld world = (ServerWorld) getWorld();
        ServerPlayerEntity nearest = null;
        double nearestDistance = 112.0 * 112.0;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!validTarget(player)) continue;
            double distance = squaredDistanceTo(player);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }
        simulationActive = nearest != null;
        int perceptionInterval = nearestDistance > 64.0 * 64.0 ? 30 : 10;
        nextPerceptionAge = age + perceptionInterval;
        if (!simulationActive) {
            setTarget(null);
            getNavigation().stop();
            return;
        }

        ServerPlayerEntity visible = null;
        LivingEntity oldTarget = getTarget();
        if (oldTarget instanceof ServerPlayerEntity player && canPerceive(player, true)) visible = player;
        if (visible == null) {
            double closest = 48.0 * 48.0;
            for (ServerPlayerEntity player : world.getPlayers()) {
                double distance = squaredDistanceTo(player);
                boolean alerted = brain.state() == GuardBrain.State.SEARCHING || brain.state() == GuardBrain.State.ALERTED
                        || brain.state() == GuardBrain.State.IN_COMBAT;
                if (distance < closest && canPerceive(player, alerted)) {
                    visible = player;
                    closest = distance;
                }
            }
        }

        GuardBrain.State previous = brain.state();
        brain.update(visible != null, squaredDistanceTo(Vec3d.ofCenter(post)) < 9.0, !patrol.isEmpty(), perceptionInterval);
        if (visible != null) {
            lastKnownPosition = visible.getPos();
            getLookControl().lookAt(visible, 30.0F, 30.0F);
            boolean confirmed = brain.state() == GuardBrain.State.ALERTED || brain.state() == GuardBrain.State.IN_COMBAT;
            setTarget(confirmed ? visible : null);
            if (confirmed && previous != GuardBrain.State.IN_COMBAT && age >= nextAlertAge) {
                nextAlertAge = age + 160;
                WarzoneEncounterManager.alertAllies(this, visible.getPos());
            }
        } else {
            // Lost sight starts investigation rather than shooting through a wall.
            setTarget(null);
        }
    }

    private boolean validTarget(ServerPlayerEntity player) {
        return !isHubGuard() && player.isAlive() && !player.isSpectator() && !player.isCreative();
    }

    private boolean canPerceive(ServerPlayerEntity player, boolean tracking) {
        if (!validTarget(player) || !insideTerritory(player.getBlockPos())) return false;
        double detection = loadout == null ? 42 : loadout.detectionRange();
        double range = player.isSneaking() ? Math.min(28, detection) : detection;
        if (squaredDistanceTo(player) > range * range) return false;
        if (!tracking && !GuardBrain.insideFieldOfView(player.getX() - getX(), player.getZ() - getZ(), getHeadYaw(), 0.25)) return false;
        return getVisibilityCache().canSee(player);
    }

    private boolean insideTerritory(BlockPos pos) {
        double dx = pos.getX() - territory.getX();
        double dz = pos.getZ() - territory.getZ();
        return dx * dx + dz * dz <= territoryRadius * territoryRadius;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        boolean damaged = super.damage(source, amount);
        if (damaged && !getWorld().isClient && source.getAttacker() instanceof ServerPlayerEntity player && validTarget(player)) {
            lastKnownPosition = player.getPos();
            brain.confirmThreat();
            if (age >= nextAlertAge) {
                nextAlertAge = age + 160;
                WarzoneEncounterManager.alertAllies(this, lastKnownPosition);
            }
        }
        return damaged;
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) { return false; }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putBoolean("BobinGuardConfigured", configured);
        nbt.putInt("BobinSite", siteId);
        nbt.putLong("BobinPost", post.asLong());
        nbt.putLong("BobinTerritory", territory.asLong());
        nbt.putInt("BobinRadius", territoryRadius);
        nbt.putString("BobinRole", role.name());
        nbt.putString("BobinFaction", faction().name());
        nbt.putInt("BobinVariant", appearanceVariant());
        nbt.putBoolean("BobinHubGuard", isHubGuard());
        nbt.putString("BobinWeapon", weapon.name());
        nbt.putInt("BobinDanger", danger);
        if (firearm != null) {
            NpcFireControl.Snapshot state = firearm.snapshot();
            NbtCompound gun = new NbtCompound();
            gun.putInt("Rounds", state.rounds());
            gun.putInt("Reserve", state.reserve());
            gun.putInt("Cooldown", state.cooldown());
            gun.putInt("Reloading", state.reloading());
            gun.putInt("Burst", state.burst());
            nbt.put("BobinFirearm", gun);
        }
        NbtList route = new NbtList();
        for (BlockPos waypoint : patrol) {
            NbtCompound entry = new NbtCompound();
            entry.putLong("Pos", waypoint.asLong());
            route.add(entry);
        }
        nbt.put("BobinPatrol", route);
        nbt.putInt("BobinPatrolIndex", patrolIndex);
        GuardBrain.Snapshot awareness = brain.snapshot();
        NbtCompound saved = new NbtCompound();
        saved.putString("State", awareness.state().name());
        saved.putInt("Suspicion", awareness.suspicion());
        saved.putInt("Memory", awareness.memoryTicks());
        saved.putInt("Investigation", awareness.investigationTicks());
        if (lastKnownPosition != null) {
            saved.putDouble("LastX", lastKnownPosition.x);
            saved.putDouble("LastY", lastKnownPosition.y);
            saved.putDouble("LastZ", lastKnownPosition.z);
        }
        nbt.put("BobinAwareness", saved);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        configured = nbt.getBoolean("BobinGuardConfigured");
        dataTracker.set(HUB_GUARD, nbt.getBoolean("BobinHubGuard"));
        Faction savedFaction;
        try { savedFaction = Faction.valueOf(nbt.getString("BobinFaction")); }
        catch (IllegalArgumentException ignored) { savedFaction = Faction.REGULAR_ARMY; }
        dataTracker.set(FACTION, savedFaction.ordinal());
        dataTracker.set(VARIANT, Math.floorMod(nbt.getInt("BobinVariant"), 3));
        danger = Math.max(0, Math.min(3, nbt.getInt("BobinDanger")));
        weapon = NpcWeapon.fromName(nbt.getString("BobinWeapon"));
        siteId = nbt.getInt("BobinSite");
        post = BlockPos.fromLong(nbt.getLong("BobinPost"));
        territory = BlockPos.fromLong(nbt.getLong("BobinTerritory"));
        territoryRadius = Math.max(16, Math.min(MAX_TERRITORY_RADIUS, nbt.getInt("BobinRadius")));
        try { role = WarzoneBasePlan.SpotRole.valueOf(nbt.getString("BobinRole")); }
        catch (IllegalArgumentException ignored) { role = WarzoneBasePlan.SpotRole.SOLDIER; }
        patrol.clear();
        NbtList route = nbt.getList("BobinPatrol", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < Math.min(16, route.size()); i++) {
            BlockPos waypoint = BlockPos.fromLong(route.getCompound(i).getLong("Pos"));
            if (insideTerritory(waypoint)) patrol.add(waypoint);
        }
        patrolIndex = patrol.isEmpty() ? 0 : Math.floorMod(nbt.getInt("BobinPatrolIndex"), patrol.size());
        NbtCompound saved = nbt.getCompound("BobinAwareness");
        try {
            brain.restore(new GuardBrain.Snapshot(GuardBrain.State.valueOf(saved.getString("State")),
                    saved.getInt("Suspicion"), saved.getInt("Memory"), saved.getInt("Investigation")));
        } catch (IllegalArgumentException ignored) { /* Older saves resume at their assigned post. */ }
        if (saved.contains("LastX", NbtElement.DOUBLE_TYPE)) {
            Vec3d position = new Vec3d(saved.getDouble("LastX"), saved.getDouble("LastY"), saved.getDouble("LastZ"));
            if (insideTerritory(BlockPos.ofFloored(position))) lastKnownPosition = position;
        }
        if (isHubGuard()) assignHubGuard(post);
        else if (configured) {
            boolean migrate = !nbt.contains("BobinWeapon", NbtElement.STRING_TYPE);
            if (migrate && getWorld() instanceof ServerWorld world) {
                var generator = WarzoneDimension.generatorOf(world);
                if (generator != null) for (WarzoneBasePlan.Site site : generator.terrain().basePlan().sites()) {
                    if (site.id != siteId) continue;
                    dataTracker.set(FACTION, site.faction.ordinal());
                    dataTracker.set(VARIANT, Math.floorMod(post.getX() * 17 + post.getZ() * 13 + siteId * 31, 3));
                    danger = FactionLoadouts.danger(site);
                    break;
                }
            }
            configureArmament(migrate);
            if (nbt.contains("BobinFirearm", NbtElement.COMPOUND_TYPE)) {
                NbtCompound gun = nbt.getCompound("BobinFirearm");
                firearm.restore(new NpcFireControl.Snapshot(gun.getInt("Rounds"), gun.getInt("Reserve"),
                        gun.getInt("Cooldown"), gun.getInt("Reloading"), 0, gun.getInt("Burst")));
            }
        }
        setAbleToJoinRaid(false);
        setPersistent();
    }

    private void tickFirearm() {
        if (firearm == null || loadout == null) return;
        LivingEntity target = getTarget();
        boolean clear = target != null && insideTerritory(target.getBlockPos()) && NpcWeaponAdapter.canFire(this, target, weapon);
        setAttacking(clear);
        int wasReloading = firearm.snapshot().reloading();
        if (firearm.tick(clear)) {
            double movementSpread = getVelocity().horizontalLengthSquared() > 0.0025 ? 2.5 : 0;
            NpcWeaponAdapter.fire(this, target, weapon, Math.max(2.5, loadout.spreadDegrees() - danger * 0.35) + movementSpread);
        } else if (wasReloading == 0 && firearm.snapshot().reloading() > 0) {
            getWorld().playSound(null, getBlockPos(), SoundEvents.ITEM_ARMOR_EQUIP_IRON, SoundCategory.HOSTILE, 0.55F, 1.1F);
        }
    }

    private final class FirearmApproachGoal extends Goal {
        FirearmApproachGoal() { setControls(EnumSet.of(Control.MOVE, Control.LOOK)); }
        @Override public boolean canStart() { return configured && simulationActive && !isHubGuard() && getTarget() != null; }
        @Override public boolean shouldContinue() { return canStart(); }
        @Override public void stop() { getNavigation().stop(); }
        @Override public void tick() {
            LivingEntity target = getTarget();
            if (target == null) return;
            getLookControl().lookAt(target, 30.0F, 30.0F);
            // Tower guards hold their assigned roof; other roles approach only within their own territory.
            if (role != WarzoneBasePlan.SpotRole.SENTRY && squaredDistanceTo(target) > Math.pow(weapon.range() * 0.8, 2)) {
                moveTo(target.getBlockPos(), 0.9);
            } else getNavigation().stop();
        }
    }

    private void moveTo(BlockPos destination, double speed) {
        if (age < nextPathAge || !insideTerritory(destination)) return;
        boolean found = getNavigation().startMovingTo(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5, speed);
        nextPathAge = age + (found ? 30 : 100);
    }

    private final class AssignedPostGoal extends Goal {
        private int waitUntil;
        AssignedPostGoal() { setControls(EnumSet.of(Control.MOVE, Control.LOOK)); }
        @Override public boolean canStart() { return configured && simulationActive && getTarget() == null; }
        @Override public boolean shouldContinue() { return canStart(); }
        @Override public void stop() { getNavigation().stop(); }
        @Override public void tick() {
            GuardBrain.State state = brain.state();
            if (state == GuardBrain.State.SEARCHING || state == GuardBrain.State.INVESTIGATING) {
                if (lastKnownPosition != null) moveTo(BlockPos.ofFloored(lastKnownPosition), 0.85);
            } else if (state == GuardBrain.State.RETURNING_TO_POST) {
                moveTo(post, 0.8);
            } else if (state == GuardBrain.State.PATROLLING && !patrol.isEmpty()) {
                BlockPos point = patrol.get(patrolIndex);
                if (squaredDistanceTo(Vec3d.ofBottomCenter(point)) < 4.0) {
                    getNavigation().stop();
                    if (waitUntil == 0) waitUntil = age + 40;
                    if (age >= waitUntil) {
                        patrolIndex = (patrolIndex + 1) % patrol.size();
                        waitUntil = 0;
                    }
                } else moveTo(point, 0.65);
            } else if (state == GuardBrain.State.IDLE || state == GuardBrain.State.SUSPICIOUS) {
                getNavigation().stop();
            }
        }
    }

    /** Only retreat to the assigned post if solid geometry actually shields it from the attacker. */
    private final class RetreatToCoverGoal extends Goal {
        private int until;
        private int nextCheck;
        RetreatToCoverGoal() { setControls(EnumSet.of(Control.MOVE)); }
        @Override public boolean canStart() {
            LivingEntity threat = getTarget();
            if (!simulationActive || threat == null || getHealth() > getMaxHealth() * 0.45F || age < nextCheck) return false;
            nextCheck = age + 100;
            double distance = squaredDistanceTo(Vec3d.ofCenter(post));
            if (distance < 9 || distance > 24 * 24) return false;
            Vec3d coverEye = Vec3d.ofBottomCenter(post).add(0, getStandingEyeHeight(), 0);
            return getWorld().raycast(new RaycastContext(threat.getEyePos(), coverEye,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, WarzoneGuardEntity.this)).getType() != HitResult.Type.MISS;
        }
        @Override public void start() { until = age + 80; nextPathAge = 0; moveTo(post, 1.1); }
        @Override public boolean shouldContinue() { return age < until && !getNavigation().isIdle() && simulationActive; }
        @Override public void stop() { getNavigation().stop(); }
    }
}
