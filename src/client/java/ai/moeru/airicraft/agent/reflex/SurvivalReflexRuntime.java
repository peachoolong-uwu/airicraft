package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.tasks.MinecraftUnderwaterEscapeController;
import ai.moeru.airicraft.agent.tasks.UnderwaterEscapeNavigator;
import ai.moeru.airicraft.agent.tasks.UnderwaterEscapeSearch;
import ai.moeru.airicraft.agent.tasks.UnderwaterHarvestPolicy;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.GhastEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class SurvivalReflexRuntime {
	static final int BREATHABLE_STABLE_TICKS = 12;
	static final double DEFEND_DISTANCE = 4.5D;
	static final double PROACTIVE_THREAT_DISTANCE = 8.0D;
	private static final float ATTACK_READY_THRESHOLD = 0.92F;
	private static final int FLEE_SCAN_RADIUS = 16;
	private static final int FLEE_SCAN_VERTICAL_RADIUS = 4;
	private static final int MIN_ESCAPE_DIRECTIONS = 2;
	private static final double FLEE_WATER_PENALTY = 48.0D;
	private static final double FLEE_KNOCKBACK_DISTANCE = 3.0D;
	private static final int SECURE_ESCAPE_PATH_DISTANCE = 16;
	private static final int SHELTER_CONFIRM_TICKS = 200;
	private static final int DISTANT_PATH_TICKS = 60;
	private static final int MOB_ROUTE_REFRESH_TICKS = 10;
	private static final int MAX_FLEE_LEGS_BEFORE_DEFEND = 4;
	private static final int MAX_FLEE_CLOSE_CONTACTS = 3;

	private final AgentConfig.ReflexConfig config;
	private final MovementController movementController;
	private final CameraController cameraController;
	private final BaritoneFacade baritone;
	private final MinecraftUnderwaterEscapeController underwaterEscape;
	private final Map<String, ObservedThreat> observedThreats = new LinkedHashMap<>();
	private final List<SurvivalReflexEvent> pendingEvents = new ArrayList<>();

	private SurvivalReflexSnapshot snapshot = SurvivalReflexSnapshot.idle();
	private boolean drowningDamageObserved;
	private long lastMobDamageTick = Long.MIN_VALUE;
	private boolean safetyHoldActuating;
	private GoalPosition fleeTarget;
	private boolean fleeNavigationOwned;
	private Double fleeWaterPenaltyBase;
	private final Set<GoalPosition> failedFleeTargets = new LinkedHashSet<>();
	private int completedFleeLegs;
	private int fleeCloseContacts;
	private int secureEscapeTicks;
	private boolean fleeEscalatedToDefend;
	private long mobRoutesTick = Long.MIN_VALUE;
	private Map<String, MobRoute> mobRoutes = Map.of();

	public SurvivalReflexRuntime(AgentConfig.ReflexConfig config) {
		this(config, new MovementController(), new CameraController(), null);
	}

	public SurvivalReflexRuntime(AgentConfig.ReflexConfig config, BaritoneFacade baritone) {
		this(config, new MovementController(), new CameraController(), baritone);
	}

	SurvivalReflexRuntime(
		AgentConfig.ReflexConfig config,
		MovementController movementController,
		CameraController cameraController
	) {
		this(config, movementController, cameraController, null);
	}

	SurvivalReflexRuntime(
		AgentConfig.ReflexConfig config,
		MovementController movementController,
		CameraController cameraController,
		BaritoneFacade baritone
	) {
		this.config = Objects.requireNonNullElseGet(config, AgentConfig.ReflexConfig::defaults);
		this.movementController = Objects.requireNonNull(movementController, "movementController");
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
		this.baritone = baritone;
		this.underwaterEscape = new MinecraftUnderwaterEscapeController(
			baritone,
			this.movementController,
			this.cameraController
		);
	}

	public SurvivalReflexSnapshot snapshot() {
		return snapshot;
	}

	public Map<String, Object> decisionEvidence() {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("snapshot", snapshot);
		evidence.put("fleeTarget", fleeTarget);
		evidence.put("failedFleeTargets", List.copyOf(failedFleeTargets));
		evidence.put("completedFleeLegs", completedFleeLegs);
		evidence.put("fleeCloseContacts", fleeCloseContacts);
		evidence.put("secureEscapeTicks", secureEscapeTicks);
		evidence.put("fleeEscalatedToDefend", fleeEscalatedToDefend);
		evidence.put("fleeNavigationOwned", fleeNavigationOwned);
		evidence.put("mobRoutesTick", mobRoutesTick);
		evidence.put("mobRoutes", Map.copyOf(mobRoutes));
		return evidence;
	}

	public void observeDamage(DamageObservation observation) {
		if (observation == null) {
			return;
		}
		if (isDrowningDamage(observation.damageTypeId())) {
			drowningDamageObserved = true;
			return;
		}
		if (!observation.attackerLiving() || observation.attackerPlayer() || observation.attackerUuid() == null) {
			return;
		}
		observedThreats.put(observation.attackerUuid(), new ObservedThreat(
			observation.attackerUuid(),
			observation.attackerName(),
			observation.attackerEntityTypeId(),
			observation.tick()
		));
		lastMobDamageTick = observation.tick();
	}

	public SurvivalReflexSnapshot tick(
		MinecraftClient client,
		InterruptedWork interruptedWork,
		long tick,
		Runnable releaseNormalActuators
	) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (!config.enabled() || client == null || client.world == null || player == null || player.isDead()) {
			reset(client);
			return snapshot;
		}

		boolean drowningDanger = drowningDanger(player, config.lowAirTicks(), drowningDamageObserved);
		detectProactiveThreats(client, player, tick);
		List<ResolvedThreat> threats = resolveThreats(client, player);
		boolean mobDanger = !threats.isEmpty() || recentlyDamagedByMob(tick, lastMobDamageTick, config.threatCooldownTicks());
		if (shouldBeginReflex(snapshot.state(), drowningDanger || mobDanger)) {
			SurvivalReflexCause cause = drowningDanger ? SurvivalReflexCause.DROWNING : SurvivalReflexCause.MOB_ATTACK;
			boolean hasInterruptedWork = interruptedWork != null && interruptedWork.hasInterruptedWork();
			SurvivalReflexAction action = drowningDanger
				? drowningAction(hasInterruptedWork)
				: chooseMobAction(player, threats);
			begin(cause, action, interruptedWork, player, threats, tick, releaseNormalActuators);
		}

		if (snapshot.state() != SurvivalReflexState.ACTIVE) {
			maintainDrowningSafetyHold(client, player, tick);
			refreshSnapshot(player, threats, snapshot.lastDangerTick(), snapshot.breathableTicks(), snapshot.lastActuatorFailure());
			return snapshot;
		}

		if (drowningDanger || snapshot.cause() == SurvivalReflexCause.DROWNING) {
			tickDrowning(client, player, drowningDanger, threats, tick);
		}
		else {
			tickMobAttack(client, player, threats, tick);
		}
		drowningDamageObserved = false;
		return snapshot;
	}

	public ResumeResult resume(String holdId, long tick) {
		ResumeResult validation = validateResume(snapshot, holdId);
		if (validation != ResumeResult.RESUMED) {
			return validation;
		}
		releaseHold("resumed", tick);
		return ResumeResult.RESUMED;
	}

	public boolean releaseHold(String reason, long tick) {
		if (snapshot.state() != SurvivalReflexState.AWAITING_PLANNER) {
			return false;
		}
		pendingEvents.add(new SurvivalReflexEvent("reflex.hold_released", mapOfNullable(
			"holdId", snapshot.holdId(),
			"reason", reason == null || reason.isBlank() ? "released" : reason,
			"safetyEpoch", snapshot.safetyEpoch()
		)));
		snapshot = new SurvivalReflexSnapshot(
			SurvivalReflexState.IDLE, null, null, snapshot.safetyEpoch(), null, null, null, List.of(),
			snapshot.health(), snapshot.maxHealth(), snapshot.air(), snapshot.maxAir(), -1L, tick, 0, null
		);
		observedThreats.clear();
		return true;
	}

	public boolean discardHold(String reason, long tick) {
		if (snapshot.holdId() == null) {
			return false;
		}
		if (snapshot.state() == SurvivalReflexState.AWAITING_PLANNER) {
			return releaseHold(reason, tick);
		}
		if (snapshot.state() != SurvivalReflexState.ACTIVE) {
			return false;
		}
		pendingEvents.add(new SurvivalReflexEvent("reflex.hold_released", mapOfNullable(
			"holdId", snapshot.holdId(),
			"reason", reason == null || reason.isBlank() ? "cancelled" : reason,
			"safetyEpoch", snapshot.safetyEpoch()
		)));
		snapshot = new SurvivalReflexSnapshot(
			snapshot.state(), snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), null, null, null,
			snapshot.threats(), snapshot.health(), snapshot.maxHealth(), snapshot.air(), snapshot.maxAir(),
			snapshot.startedTick(), snapshot.lastDangerTick(), snapshot.breathableTicks(), snapshot.lastActuatorFailure()
		);
		return true;
	}

	public void reset(MinecraftClient client) {
		movementController.stop(client);
		observedThreats.clear();
		drowningDamageObserved = false;
		lastMobDamageTick = Long.MIN_VALUE;
		safetyHoldActuating = false;
		stopFleeNavigation();
		resetFleeProgress();
		underwaterEscape.reset(client);
		long epoch = snapshot.safetyEpoch();
		snapshot = new SurvivalReflexSnapshot(
			SurvivalReflexState.IDLE, null, null, epoch, null, null, null, List.of(),
			null, null, null, null, -1L, -1L, 0, null
		);
	}

	public List<SurvivalReflexEvent> drainEvents() {
		if (pendingEvents.isEmpty()) {
			return List.of();
		}
		List<SurvivalReflexEvent> events = List.copyOf(pendingEvents);
		pendingEvents.clear();
		return events;
	}

	private void begin(
		SurvivalReflexCause cause,
		SurvivalReflexAction action,
		InterruptedWork interruptedWork,
		ClientPlayerEntity player,
		List<ResolvedThreat> threats,
		long tick,
		Runnable releaseNormalActuators
	) {
		long nextEpoch = snapshot.safetyEpoch() + 1L;
		stopFleeNavigation();
		failedFleeTargets.clear();
		resetFleeProgress();
		InterruptedWork work = interruptedWork == null ? InterruptedWork.none() : interruptedWork;
		String holdId = work.hasInterruptedWork() ? UUID.randomUUID().toString() : null;
		snapshot = new SurvivalReflexSnapshot(
			SurvivalReflexState.ACTIVE, cause, action, nextEpoch, holdId, work.jobId(), work.actionExecutionId(),
			threatSnapshots(threats), player.getHealth(), player.getMaxHealth(), player.getAir(), player.getMaxAir(),
			tick, tick, 0, null
		);
		pendingEvents.add(new SurvivalReflexEvent("reflex.started", mapOfNullable(
			"safetyEpoch", nextEpoch,
			"holdId", holdId,
			"cause", cause.name(),
			"action", action.name(),
			"interruptedJobId", work.jobId(),
			"interruptedActionExecutionId", work.actionExecutionId(),
			"health", player.getHealth(),
			"air", player.getAir()
		)));
		try {
			if (releaseNormalActuators != null) {
				releaseNormalActuators.run();
			}
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("release_normal_actuators", exception, tick);
			snapshot = new SurvivalReflexSnapshot(
				snapshot.state(), snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), snapshot.holdId(),
				snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), snapshot.threats(),
				snapshot.health(), snapshot.maxHealth(), snapshot.air(), snapshot.maxAir(), snapshot.startedTick(),
				snapshot.lastDangerTick(), snapshot.breathableTicks(), failureText(exception)
			);
		}
	}

	private void tickDrowning(
		MinecraftClient client,
		ClientPlayerEntity player,
		boolean danger,
		List<ResolvedThreat> threats,
		long tick
	) {
		boolean hasInterruptedWork = snapshot.holdId() != null;
		SurvivalReflexAction desiredAction = drowningAction(hasInterruptedWork);
		if (snapshot.cause() != SurvivalReflexCause.DROWNING || snapshot.action() != desiredAction) {
			changeAction(SurvivalReflexCause.DROWNING, desiredAction, tick);
		}
		boolean airRecovered = airRecoveryMarginReached(
			player.isSubmergedInWater(),
			player.getAir(),
			player.getMaxAir()
		);
		boolean safeLand = player.isOnGround()
			&& MinecraftUnderwaterEscapeController.isSafeStandingPosition(client, player.getBlockPos());
		boolean stable = stableDrowningRecovery(airRecovered);
		int stableTicks = stable ? snapshot.breathableTicks() + 1 : 0;
		if (drowningResolved(stableTicks)) {
			if (!mobThreatsResolved(threats.size(), tick, lastMobDamageTick, config.threatCooldownTicks())) {
				underwaterEscape.reset(client);
				changeAction(SurvivalReflexCause.MOB_ATTACK, chooseMobAction(player, threats), tick);
				refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
				return;
			}
			boolean keepSafetyHold = shouldKeepDrowningSafetyHold(
				hasInterruptedWork,
				safeLand
			);
			resolve(
				client,
				player,
				threats,
				tick,
				safeLand ? "safe_land_reached" : "breathing_restored",
				keepSafetyHold
			);
			return;
		}

		try {
			if (stable) {
				underwaterEscape.reset(client);
				if (player.isTouchingWater()) {
					movementController.swimUp(client, false, false, tick);
				}
				else {
					movementController.stop(client);
				}
			}
			else {
				UnderwaterEscapeSearch.SearchMode mode = drowningSearchMode(
					hasInterruptedWork,
					player.isSubmergedInWater(),
					player.getAir(),
					player.getMaxAir()
				);
				underwaterEscape.tick(
					client,
					mode,
					player.getAir(),
					tick,
					mode == UnderwaterEscapeSearch.SearchMode.BREATHABLE
						? !player.isSubmergedInWater()
						: safeLand
				);
			}
			refreshSnapshot(player, threats, danger ? tick : snapshot.lastDangerTick(), stableTicks, null);
		}
		catch (RuntimeException exception) {
			String operation = hasInterruptedWork ? "swim_to_air" : "reach_safe_land";
			recordActuatorFailure(operation, exception, tick);
			refreshSnapshot(player, threats, danger ? tick : snapshot.lastDangerTick(), stableTicks, failureText(exception));
		}
	}

	private void tickMobAttack(MinecraftClient client, ClientPlayerEntity player, List<ResolvedThreat> threats, long tick) {
		if (mobThreatsResolved(threats.size(), tick, lastMobDamageTick, config.threatCooldownTicks())) {
			resolve(client, player, threats, tick, "threats_clear", false);
			return;
		}
		equipBestCombatHotbarItem(player);
		attemptCloseQuarterAttack(client, player, threats, tick);
		MobSecurity security = assessMobSecurity(player, threats, tick);
		if (snapshot.action() == SurvivalReflexAction.FLEE && security.kind() != SecurityKind.UNSAFE) {
			secureEscapeTicks++;
			stopFleeNavigation();
			movementController.stop(client);
			int requiredTicks = security.kind() == SecurityKind.DISTANT_PATH
				? DISTANT_PATH_TICKS
				: SHELTER_CONFIRM_TICKS;
			if (secureEscapeTicks >= requiredTicks) {
				resolve(
					client,
					player,
					threats,
					tick,
					security.kind() == SecurityKind.DISTANT_PATH ? "secure_path_separation" : "sealed_shelter",
					false
				);
				return;
			}
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
			return;
		}
		secureEscapeTicks = 0;
		if (!fleeEscalatedToDefend && snapshot.action() == SurvivalReflexAction.FLEE && !threats.isEmpty()
			&& shouldEscalateFleeToDefend(completedFleeLegs, fleeCloseContacts, security.closestReachablePathLength())) {
			fleeEscalatedToDefend = true;
			pendingEvents.add(new SurvivalReflexEvent("reflex.flee_escalated", mapOfNullable(
				"reason", fleeCloseContacts >= MAX_FLEE_CLOSE_CONTACTS ? "repeated_close_contact" : "no_secure_escape",
				"completedLegs", completedFleeLegs,
				"closeContacts", fleeCloseContacts,
				"tick", tick
			)));
		}
		SurvivalReflexAction nextAction = fleeEscalatedToDefend
			? SurvivalReflexAction.DEFEND
			: chooseMobAction(player, threats);
		if (snapshot.action() != nextAction || snapshot.cause() != SurvivalReflexCause.MOB_ATTACK) {
			changeAction(SurvivalReflexCause.MOB_ATTACK, nextAction, tick);
		}
		try {
			if (nextAction == SurvivalReflexAction.DEFEND && !threats.isEmpty()) {
				defend(client, player, closestVisibleThreat(threats), tick);
			}
			else if (!threats.isEmpty()) {
				flee(client, player, threats, tick);
			}
			else {
				movementController.stop(client);
			}
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
		}
		catch (RuntimeException exception) {
			recordActuatorFailure(nextAction.name().toLowerCase(java.util.Locale.ROOT), exception, tick);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, failureText(exception));
		}
	}

	private void defend(MinecraftClient client, ClientPlayerEntity player, ResolvedThreat threat, long tick) {
		stopFleeNavigation();
		cameraController.lookAtNow(client, threat.entity().getBoundingBox().getCenter());
		if (threat.distance() > 3.0D) {
			movementController.moveDirectional(client, true, false, false, false, true, false, tick);
		}
		else {
			movementController.stop(client);
		}
	}

	private void flee(MinecraftClient client, ClientPlayerEntity player, List<ResolvedThreat> threats, long tick) {
		movementController.stop(client);
		if (shouldUseWaterAwareFlee(player.isTouchingWater(), player.isSubmergedInWater())) {
			stopFleeNavigation();
			underwaterEscape.tick(
				client,
				UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
				player.getAir(),
				tick,
				MinecraftUnderwaterEscapeController.isSafeStandingPosition(client, player.getBlockPos())
			);
			return;
		}
		underwaterEscape.reset(client);
		if (baritone == null || !baritone.isLoaded()) {
			throw new IllegalStateException("safe_flee_pathfinder_unavailable");
		}
		if (fleeTarget != null && baritone.navigationGoalReached(fleeTarget)) {
			completedFleeLegs++;
			fleeTarget = null;
			fleeNavigationOwned = false;
		}
		Optional<String> pathEvent = baritone.pollPathEvent();
		if (fleeTarget != null && shouldRejectFleeTarget(pathEvent, baritone.processActive())) {
			pendingEvents.add(new SurvivalReflexEvent("reflex.flee_path_failed", mapOfNullable(
				"event", pathEvent.orElse(null),
				"processActive", baritone.processActive(),
				"x", fleeTarget == null ? null : fleeTarget.x(),
				"y", fleeTarget == null ? null : fleeTarget.y(),
				"z", fleeTarget == null ? null : fleeTarget.z(),
				"tick", tick
			)));
			if (fleeTarget != null) {
				failedFleeTargets.add(fleeTarget);
			}
			fleeTarget = null;
			fleeNavigationOwned = false;
		}
		if (fleeTarget != null) {
			return;
		}
		FleeSelection selection = selectFleeTarget(client, player, threats).orElse(null);
		fleeTarget = selection == null ? null : selection.goal();
		if (selection == null) {
			if (completedFleeLegs > 0) {
				fleeEscalatedToDefend = true;
				pendingEvents.add(new SurvivalReflexEvent("reflex.flee_escalated", mapOfNullable(
					"reason", "safe_path_exhausted",
					"completedLegs", completedFleeLegs,
					"closeContacts", fleeCloseContacts,
					"tick", tick
				)));
				return;
			}
			throw new IllegalStateException("safe_flee_target_unavailable");
		}
		baritone.applySettings();
		if (fleeWaterPenaltyBase == null) {
			fleeWaterPenaltyBase = baritone.walkOnWaterPenalty();
		}
		baritone.setWalkOnWaterPenalty(fleeWaterPenalty(fleeWaterPenaltyBase));
		baritone.startNavigate(fleeTarget);
		fleeNavigationOwned = true;
		pendingEvents.add(new SurvivalReflexEvent("reflex.flee_target_selected", mapOfNullable(
			"x", fleeTarget.x(),
			"y", fleeTarget.y(),
			"z", fleeTarget.z(),
			"sheltered", selection.sheltered(),
			"pathfinder", "baritone",
			"tick", tick
		)));
	}

	private void resolve(
		MinecraftClient client,
		ClientPlayerEntity player,
		List<ResolvedThreat> threats,
		long tick,
		String reason,
		boolean keepSafetyHold
	) {
		underwaterEscape.reset(client);
		stopFleeNavigation();
		movementController.stop(client);
		SurvivalReflexState nextState = keepSafetyHold || snapshot.holdId() != null
			? SurvivalReflexState.AWAITING_PLANNER
			: SurvivalReflexState.IDLE;
		String nextHoldId = safetyHoldId(snapshot.holdId(), nextState == SurvivalReflexState.AWAITING_PLANNER);
		pendingEvents.add(new SurvivalReflexEvent("reflex.resolved", mapOfNullable(
			"safetyEpoch", snapshot.safetyEpoch(),
			"holdId", nextHoldId,
			"cause", snapshot.cause() == null ? null : snapshot.cause().name(),
			"action", snapshot.action() == null ? null : snapshot.action().name(),
			"reason", reason,
			"nextState", nextState.name()
		)));
		snapshot = new SurvivalReflexSnapshot(
			nextState, snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), nextHoldId,
			snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), threatSnapshots(threats),
			player.getHealth(), player.getMaxHealth(), player.getAir(), player.getMaxAir(), snapshot.startedTick(),
			tick, snapshot.breathableTicks(), null
		);
		observedThreats.clear();
		lastMobDamageTick = Long.MIN_VALUE;
		failedFleeTargets.clear();
		resetFleeProgress();
	}

	static String safetyHoldId(String existingHoldId, boolean holdRequired) {
		if (!holdRequired) {
			return null;
		}
		return existingHoldId == null || existingHoldId.isBlank()
			? UUID.randomUUID().toString()
			: existingHoldId;
	}

	private void attemptCloseQuarterAttack(
		MinecraftClient client,
		ClientPlayerEntity player,
		List<ResolvedThreat> threats,
		long tick
	) {
		if (client.interactionManager == null || threats == null || threats.isEmpty()) {
			return;
		}
		ResolvedThreat threat = closestVisibleThreat(threats);
		float cooldown = player.getAttackCooldownProgress(0.0F);
		if (!shouldAttackCloseThreat(threat.distance(), threat.lineOfSight(), cooldown)) {
			return;
		}
		cameraController.lookAtNow(client, threat.entity().getBoundingBox().getCenter());
		client.interactionManager.attackEntity(player, threat.entity());
		player.swingHand(Hand.MAIN_HAND);
		boolean fleeing = snapshot.action() == SurvivalReflexAction.FLEE;
		if (fleeing) {
			fleeCloseContacts++;
		}
		pendingEvents.add(new SurvivalReflexEvent(fleeing ? "reflex.flee_knockback" : "reflex.close_quarter_attack", mapOfNullable(
			"threatUuid", threat.observed().uuid(),
			"entityTypeId", threat.observed().entityTypeId(),
			"distance", threat.distance(),
			"action", snapshot.action() == null ? null : snapshot.action().name(),
			"closeContacts", fleeing ? fleeCloseContacts : null,
			"tick", tick
		)));
	}

	private static void equipBestCombatHotbarItem(ClientPlayerEntity player) {
		if (player == null) {
			return;
		}
		int bestSlot = -1;
		int bestRank = Integer.MAX_VALUE;
		for (int slot = 0; slot < 9; slot++) {
			String itemId = Registries.ITEM.getId(player.getInventory().getStack(slot).getItem()).toString();
			int rank = combatHotbarRank(itemId);
			if (rank < bestRank) {
				bestRank = rank;
				bestSlot = slot;
			}
		}
		if (bestSlot >= 0 && player.getInventory().getSelectedSlot() != bestSlot) {
			player.getInventory().setSelectedSlot(bestSlot);
		}
	}

	static int combatHotbarRank(String itemId) {
		return switch (itemId == null ? "" : itemId) {
			case "minecraft:netherite_sword" -> 0;
			case "minecraft:diamond_sword" -> 1;
			case "minecraft:iron_sword" -> 2;
			case "minecraft:stone_sword" -> 3;
			case "minecraft:golden_sword" -> 4;
			case "minecraft:wooden_sword" -> 5;
			case "minecraft:netherite_axe" -> 6;
			case "minecraft:diamond_axe" -> 7;
			case "minecraft:iron_axe" -> 8;
			case "minecraft:stone_axe" -> 9;
			case "minecraft:golden_axe" -> 10;
			case "minecraft:wooden_axe" -> 11;
			case "minecraft:netherite_pickaxe" -> 12;
			case "minecraft:diamond_pickaxe" -> 13;
			case "minecraft:iron_pickaxe" -> 14;
			case "minecraft:stone_pickaxe" -> 15;
			case "minecraft:golden_pickaxe" -> 16;
			case "minecraft:wooden_pickaxe" -> 17;
			default -> Integer.MAX_VALUE;
		};
	}

	private void changeAction(SurvivalReflexCause cause, SurvivalReflexAction action, long tick) {
		if (action != SurvivalReflexAction.FLEE) {
			stopFleeNavigation();
		}
		pendingEvents.add(new SurvivalReflexEvent("reflex.action_changed", mapOfNullable(
			"safetyEpoch", snapshot.safetyEpoch(),
			"holdId", snapshot.holdId(),
			"previousCause", snapshot.cause() == null ? null : snapshot.cause().name(),
			"previousAction", snapshot.action() == null ? null : snapshot.action().name(),
			"cause", cause.name(),
			"action", action.name()
		)));
		snapshot = new SurvivalReflexSnapshot(
			snapshot.state(), cause, action, snapshot.safetyEpoch(), snapshot.holdId(),
			snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), snapshot.threats(),
			snapshot.health(), snapshot.maxHealth(), snapshot.air(), snapshot.maxAir(), snapshot.startedTick(),
			tick, snapshot.breathableTicks(), snapshot.lastActuatorFailure()
		);
	}

	private void refreshSnapshot(
		ClientPlayerEntity player,
		List<ResolvedThreat> threats,
		long lastDangerTick,
		int breathableTicks,
		String actuatorFailure
	) {
		snapshot = new SurvivalReflexSnapshot(
			snapshot.state(), snapshot.cause(), snapshot.action(), snapshot.safetyEpoch(), snapshot.holdId(),
			snapshot.interruptedJobId(), snapshot.interruptedActionExecutionId(), threatSnapshots(threats),
			player.getHealth(), player.getMaxHealth(), player.getAir(), player.getMaxAir(), snapshot.startedTick(),
			lastDangerTick, breathableTicks, actuatorFailure
		);
	}

	private void recordActuatorFailure(String operation, RuntimeException exception, long tick) {
		pendingEvents.add(new SurvivalReflexEvent("reflex.actuator_failed", mapOfNullable(
			"safetyEpoch", snapshot.safetyEpoch(),
			"holdId", snapshot.holdId(),
			"operation", operation,
			"message", failureText(exception),
			"tick", tick
		)));
	}

	static boolean drowningDanger(ClientPlayerEntity player, int lowAirTicks, boolean drowningDamageObserved) {
		return shouldStartDrowning(
			player != null && player.isSubmergedInWater(),
			player == null ? Integer.MAX_VALUE : player.getAir(),
			lowAirTicks,
			drowningDamageObserved
		);
	}

	static boolean shouldStartDrowning(boolean submerged, int air, int lowAirTicks, boolean drowningDamageObserved) {
		return drowningDamageObserved || (submerged && air <= Math.max(0, lowAirTicks));
	}

	static boolean airRecoveryMarginReached(boolean submerged, int air, int maxAir) {
		return UnderwaterHarvestPolicy.mayResumeHarvest(submerged, air, maxAir);
	}

	static boolean drowningResolved(int breathableTicks) {
		return breathableTicks >= BREATHABLE_STABLE_TICKS;
	}

	static SurvivalReflexAction drowningAction(boolean hasInterruptedWork) {
		return hasInterruptedWork ? SurvivalReflexAction.SWIM_TO_AIR : SurvivalReflexAction.REACH_SAFE_LAND;
	}

	static boolean stableDrowningRecovery(boolean airRecovered) {
		return airRecovered;
	}

	static boolean shouldKeepDrowningSafetyHold(boolean hasInterruptedWork, boolean safeLand) {
		return hasInterruptedWork || !safeLand;
	}

	static boolean safeLandSearchExhausted(
		UnderwaterEscapeSearch.SearchStatus searchStatus,
		UnderwaterEscapeNavigator.Phase navigationPhase
	) {
		return searchStatus != UnderwaterEscapeSearch.SearchStatus.SEARCHING
			&& navigationPhase == UnderwaterEscapeNavigator.Phase.EXHAUSTED;
	}

	static UnderwaterEscapeSearch.SearchMode drowningSearchMode(
		boolean hasInterruptedWork,
		boolean submerged,
		int air,
		int maxAir
	) {
		if (hasInterruptedWork || submerged || air < Math.max(0, maxAir - UnderwaterHarvestPolicy.AIR_RESUME_MARGIN_TICKS)) {
			return UnderwaterEscapeSearch.SearchMode.BREATHABLE;
		}
		return UnderwaterEscapeSearch.SearchMode.SAFE_STANDING;
	}

	static boolean shouldBeginReflex(SurvivalReflexState state, boolean dangerPresent) {
		return dangerPresent && state != SurvivalReflexState.ACTIVE;
	}

	static boolean shouldMaintainDrowningSafetyHold(
		SurvivalReflexState state,
		SurvivalReflexCause cause,
		boolean touchingWater
	) {
		return state == SurvivalReflexState.AWAITING_PLANNER
			&& cause == SurvivalReflexCause.DROWNING
			&& touchingWater;
	}

	static boolean mobThreatsResolved(int relevantThreatCount, long tick, long lastDamageTick, int cooldownTicks) {
		return relevantThreatCount == 0 && !recentlyDamagedByMob(tick, lastDamageTick, cooldownTicks);
	}

	static ResumeResult validateResume(SurvivalReflexSnapshot snapshot, String holdId) {
		SurvivalReflexSnapshot current = snapshot == null ? SurvivalReflexSnapshot.idle() : snapshot;
		if (current.state() == SurvivalReflexState.ACTIVE) {
			return ResumeResult.REFLEX_ACTIVE;
		}
		if (current.state() != SurvivalReflexState.AWAITING_PLANNER || current.holdId() == null) {
			return ResumeResult.NO_SAFETY_HOLD;
		}
		return current.holdId().equals(holdId) ? ResumeResult.RESUMED : ResumeResult.STALE_SAFETY_HOLD;
	}

	public static boolean shouldDefend(double healthRatio, int threatCount, double distance, boolean lineOfSight, double minHealthRatio) {
		return healthRatio > minHealthRatio && threatCount == 1 && distance <= DEFEND_DISTANCE && lineOfSight;
	}

	static boolean shouldDetectProactiveThreat(boolean hostile, boolean alive, double distance, boolean lineOfSight) {
		return hostile && alive && distance <= PROACTIVE_THREAT_DISTANCE && lineOfSight;
	}

	public static boolean recentlyDamagedByMob(long tick, long lastDamageTick, int cooldownTicks) {
		return lastDamageTick != Long.MIN_VALUE && tick - lastDamageTick < Math.max(0, cooldownTicks);
	}

	static double fleeWaterPenalty(double currentPenalty) {
		return Math.max(FLEE_WATER_PENALTY, currentPenalty);
	}

	static boolean shouldAttackCloseThreat(double distance, boolean lineOfSight, float attackCooldown) {
		return distance <= FLEE_KNOCKBACK_DISTANCE
			&& lineOfSight
			&& attackCooldown >= ATTACK_READY_THRESHOLD;
	}

	static boolean shouldEscalateFleeToDefend(int completedLegs, int closeContacts, double closestPathDistance) {
		return closeContacts >= MAX_FLEE_CLOSE_CONTACTS
			|| (completedLegs >= MAX_FLEE_LEGS_BEFORE_DEFEND && closestPathDistance <= PROACTIVE_THREAT_DISTANCE);
	}

	static SecurityKind classifyThreatSecurity(
		RouteStatus routeStatus,
		int pathLength,
		boolean ranged,
		boolean lineOfSight
	) {
		if (routeStatus == RouteStatus.UNKNOWN) {
			return SecurityKind.UNSAFE;
		}
		if (routeStatus == RouteStatus.BLOCKED) {
			return ranged && lineOfSight ? SecurityKind.UNSAFE : SecurityKind.SEALED;
		}
		if (routeStatus == RouteStatus.PARTIAL) {
			return ranged && lineOfSight ? SecurityKind.UNSAFE : SecurityKind.POTENTIAL_SHELTER;
		}
		return pathLength >= SECURE_ESCAPE_PATH_DISTANCE ? SecurityKind.DISTANT_PATH : SecurityKind.UNSAFE;
	}

	private MobSecurity assessMobSecurity(ClientPlayerEntity player, List<ResolvedThreat> threats, long tick) {
		if (player == null || threats == null || threats.isEmpty()) {
			return new MobSecurity(SecurityKind.UNSAFE, Double.POSITIVE_INFINITY);
		}
		Set<String> threatIds = threats.stream().map(threat -> threat.observed().uuid()).collect(java.util.stream.Collectors.toSet());
		if (mobRoutesTick == Long.MIN_VALUE
			|| tick - mobRoutesTick >= MOB_ROUTE_REFRESH_TICKS
			|| !mobRoutes.keySet().equals(threatIds)) {
			LinkedHashMap<String, MobRoute> refreshed = new LinkedHashMap<>();
			for (ResolvedThreat threat : threats) {
				refreshed.put(threat.observed().uuid(), computeMobRoute(player, threat.entity()));
			}
			mobRoutes = Map.copyOf(refreshed);
			mobRoutesTick = tick;
		}
		SecurityKind combined = SecurityKind.SEALED;
		double closestReachable = Double.POSITIVE_INFINITY;
		for (ResolvedThreat threat : threats) {
			MobRoute route = mobRoutes.getOrDefault(threat.observed().uuid(), MobRoute.unknown());
			SecurityKind threatSecurity = classifyThreatSecurity(
				route.status(),
				route.pathLength(),
				threat.entity() instanceof RangedAttackMob,
				threat.lineOfSight()
			);
			if (threatSecurity == SecurityKind.UNSAFE) {
				combined = SecurityKind.UNSAFE;
			}
			else if (threatSecurity == SecurityKind.POTENTIAL_SHELTER) {
				combined = SecurityKind.POTENTIAL_SHELTER;
			}
			else if (threatSecurity == SecurityKind.DISTANT_PATH && combined == SecurityKind.SEALED) {
				combined = SecurityKind.DISTANT_PATH;
			}
			if (route.status() == RouteStatus.REACHABLE) {
				closestReachable = Math.min(closestReachable, route.pathLength());
			}
		}
		return new MobSecurity(combined, closestReachable);
	}

	private static MobRoute computeMobRoute(ClientPlayerEntity player, LivingEntity threat) {
		if (!(threat instanceof MobEntity mob)) {
			return MobRoute.unknown();
		}
		try {
			Path path = mob.getNavigation().findPathTo(player.getBlockPos(), 0);
			if (path == null) {
				return new MobRoute(RouteStatus.BLOCKED, -1);
			}
			if (!path.reachesTarget()) {
				return new MobRoute(RouteStatus.PARTIAL, path.getLength());
			}
			return new MobRoute(RouteStatus.REACHABLE, path.getLength());
		}
		catch (RuntimeException exception) {
			return MobRoute.unknown();
		}
	}

	private SurvivalReflexAction chooseMobAction(ClientPlayerEntity player, List<ResolvedThreat> threats) {
		if (player == null || threats == null || threats.isEmpty()) {
			return SurvivalReflexAction.FLEE;
		}
		ResolvedThreat closest = closestVisibleThreat(threats);
		boolean safeThreatTypes = threats.stream().noneMatch(SurvivalReflexRuntime::unsafeCombatThreat);
		SurvivalCombatReadiness.State readiness = new SurvivalCombatReadiness.State(
			threats.size(),
			closest.distance(),
			closest.lineOfSight(),
			!safeThreatTypes
				|| hazardousEnvironment(player)
				|| healthRatio(player) <= config.defendMinHealthRatio()
		);
		return SurvivalCombatReadiness.shouldFight(readiness)
			? SurvivalReflexAction.DEFEND
			: SurvivalReflexAction.FLEE;
	}

	private static boolean isDrowningDamage(String damageTypeId) {
		return damageTypeId != null && (damageTypeId.equals("drown") || damageTypeId.endsWith(":drown"));
	}

	private static double healthRatio(ClientPlayerEntity player) {
		return player == null || player.getMaxHealth() <= 0.0F ? 0.0D : player.getHealth() / player.getMaxHealth();
	}

	private Optional<FleeSelection> selectFleeTarget(
		MinecraftClient client,
		ClientPlayerEntity player,
		List<ResolvedThreat> threats
	) {
		if (client == null || client.world == null || player == null || threats == null || threats.isEmpty()) {
			return Optional.empty();
		}
		BlockPos originPos = player.getBlockPos();
		SurvivalEscapeTargetSelector.Point origin = point(originPos);
		List<SurvivalEscapeTargetSelector.Point> threatPoints = threats.stream()
			.map(threat -> point(threat.entity().getBlockPos()))
			.toList();
		List<SurvivalEscapeTargetSelector.Candidate> candidates = new ArrayList<>();
		for (int dx = -FLEE_SCAN_RADIUS; dx <= FLEE_SCAN_RADIUS; dx++) {
			for (int dz = -FLEE_SCAN_RADIUS; dz <= FLEE_SCAN_RADIUS; dz++) {
				for (int dy = FLEE_SCAN_VERTICAL_RADIUS; dy >= -FLEE_SCAN_VERTICAL_RADIUS; dy--) {
					BlockPos candidatePos = originPos.add(dx, dy, dz);
					GoalPosition goal = goal(candidatePos);
					if (failedFleeTargets.contains(goal) || !isSafeFleeStanding(client, candidatePos)) {
						continue;
					}
					candidates.add(new SurvivalEscapeTargetSelector.Candidate(
						candidatePos.getX(), candidatePos.getY(), candidatePos.getZ(), true, false, false,
						isShelteredFromThreats(client, player, candidatePos, threats)
					));
					break;
				}
			}
		}
		return SurvivalEscapeTargetSelector.select(origin, threatPoints, candidates)
			.map(selected -> new FleeSelection(
				new GoalPosition(selected.x(), selected.y(), selected.z(), true),
				selected.sheltered()
			));
	}

	private static boolean isShelteredFromThreats(
		MinecraftClient client,
		ClientPlayerEntity player,
		BlockPos candidatePos,
		List<ResolvedThreat> threats
	) {
		Vec3d candidateEye = Vec3d.ofBottomCenter(candidatePos).add(0.0D, player.getEyeHeight(player.getPose()), 0.0D);
		return threats.stream().allMatch(threat -> client.world.raycast(new RaycastContext(
			candidateEye,
			threat.entity().getEyePos(),
			RaycastContext.ShapeType.COLLIDER,
			RaycastContext.FluidHandling.NONE,
			player
		)).getType() == HitResult.Type.BLOCK);
	}

	private static boolean isSafeFleeStanding(MinecraftClient client, BlockPos feetPos) {
		if (client == null || client.world == null || feetPos == null
			|| !client.world.isChunkLoaded(feetPos) || !client.world.isChunkLoaded(feetPos.down())) {
			return false;
		}
		BlockState feet = client.world.getBlockState(feetPos);
		BlockState head = client.world.getBlockState(feetPos.up());
		BlockState support = client.world.getBlockState(feetPos.down());
		if (!client.world.getFluidState(feetPos).isEmpty()
			|| !client.world.getFluidState(feetPos.up()).isEmpty()
			|| hazardousBlock(feet) || hazardousBlock(head) || hazardousBlock(support)) {
			return false;
		}
		boolean standing = (feet.isAir() || feet.isReplaceable())
			&& (head.isAir() || head.isReplaceable())
			&& support.isSideSolidFullSquare(client.world, feetPos.down(), Direction.UP);
		if (!standing) {
			return false;
		}
		int escapes = 0;
		for (Direction direction : Direction.Type.HORIZONTAL) {
			BlockPos adjacent = feetPos.offset(direction);
			if (basicSafeStanding(client, adjacent)) {
				escapes++;
			}
		}
		return escapes >= MIN_ESCAPE_DIRECTIONS;
	}

	private static boolean basicSafeStanding(MinecraftClient client, BlockPos feetPos) {
		if (client == null || client.world == null || feetPos == null
			|| !client.world.isChunkLoaded(feetPos) || !client.world.isChunkLoaded(feetPos.down())) {
			return false;
		}
		BlockState feet = client.world.getBlockState(feetPos);
		BlockState head = client.world.getBlockState(feetPos.up());
		BlockState support = client.world.getBlockState(feetPos.down());
		return client.world.getFluidState(feetPos).isEmpty()
			&& client.world.getFluidState(feetPos.up()).isEmpty()
			&& !hazardousBlock(feet)
			&& !hazardousBlock(head)
			&& !hazardousBlock(support)
			&& (feet.isAir() || feet.isReplaceable())
			&& (head.isAir() || head.isReplaceable())
			&& support.isSideSolidFullSquare(client.world, feetPos.down(), Direction.UP);
	}

	private static boolean hazardousBlock(BlockState state) {
		return state != null && (state.isOf(Blocks.FIRE)
			|| state.isOf(Blocks.SOUL_FIRE)
			|| state.isOf(Blocks.LAVA)
			|| state.isOf(Blocks.MAGMA_BLOCK)
			|| state.isOf(Blocks.CACTUS)
			|| state.isOf(Blocks.CAMPFIRE)
			|| state.isOf(Blocks.SOUL_CAMPFIRE)
			|| state.isOf(Blocks.POWDER_SNOW));
	}

	private static boolean hazardousEnvironment(ClientPlayerEntity player) {
		return player == null
			|| player.isTouchingWater()
			|| player.isSubmergedInWater()
			|| player.isInLava()
			|| player.isOnFire()
			|| player.fallDistance > 3.0F;
	}

	private static boolean unsafeCombatThreat(ResolvedThreat threat) {
		LivingEntity entity = threat == null ? null : threat.entity();
		return entity instanceof CreeperEntity
			|| entity instanceof RangedAttackMob
			|| entity instanceof BlazeEntity
			|| entity instanceof GhastEntity;
	}

	private static ResolvedThreat closestVisibleThreat(List<ResolvedThreat> threats) {
		return threats.stream()
			.min((left, right) -> {
				if (left.lineOfSight() != right.lineOfSight()) {
					return left.lineOfSight() ? -1 : 1;
				}
				return Double.compare(left.distance(), right.distance());
			})
			.orElseThrow();
	}

	private static boolean isTerminalPathFailure(String event) {
		return "CALC_FAILED".equals(event)
			|| "CANCELED".equals(event)
			|| "DISCARDING".equals(event);
	}

	static boolean shouldRejectFleeTarget(Optional<String> pathEvent, boolean currentProcessActive) {
		return !currentProcessActive && pathEvent.filter(SurvivalReflexRuntime::isTerminalPathFailure).isPresent();
	}

	static boolean shouldUseWaterAwareFlee(boolean touchingWater, boolean submergedInWater) {
		return touchingWater || submergedInWater;
	}

	private void stopFleeNavigation() {
		if (fleeNavigationOwned && baritone != null) {
			baritone.cancel();
		}
		if (fleeWaterPenaltyBase != null && baritone != null) {
			baritone.setWalkOnWaterPenalty(fleeWaterPenaltyBase);
		}
		fleeWaterPenaltyBase = null;
		fleeNavigationOwned = false;
		fleeTarget = null;
	}

	private void resetFleeProgress() {
		completedFleeLegs = 0;
		fleeCloseContacts = 0;
		secureEscapeTicks = 0;
		fleeEscalatedToDefend = false;
		mobRoutesTick = Long.MIN_VALUE;
		mobRoutes = Map.of();
	}

	private static GoalPosition goal(BlockPos pos) {
		return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), true);
	}

	private static SurvivalEscapeTargetSelector.Point point(BlockPos pos) {
		return new SurvivalEscapeTargetSelector.Point(pos.getX(), pos.getY(), pos.getZ());
	}

	private void detectProactiveThreats(MinecraftClient client, ClientPlayerEntity player, long tick) {
		if (client == null || client.world == null || player == null) {
			return;
		}
		for (HostileEntity hostile : client.world.getEntitiesByClass(
			HostileEntity.class,
			player.getBoundingBox().expand(PROACTIVE_THREAT_DISTANCE),
			Entity::isAlive
		)) {
			double distance = player.distanceTo(hostile);
			boolean lineOfSight = player.canSee(hostile);
			if (!shouldDetectProactiveThreat(true, hostile.isAlive(), distance, lineOfSight)) {
				continue;
			}
			String uuid = hostile.getUuidAsString();
			if (observedThreats.containsKey(uuid)) {
				continue;
			}
			ObservedThreat observed = new ObservedThreat(
				uuid,
				hostile.getName().getString(),
				Registries.ENTITY_TYPE.getId(hostile.getType()).toString(),
				tick
			);
			observedThreats.put(uuid, observed);
			pendingEvents.add(new SurvivalReflexEvent("reflex.threat_detected", mapOfNullable(
				"source", "hostile_proximity",
				"uuid", uuid,
				"name", observed.name(),
				"entityTypeId", observed.entityTypeId(),
				"distance", distance,
				"lineOfSight", true,
				"tick", tick
			)));
		}
	}

	private void maintainDrowningSafetyHold(MinecraftClient client, ClientPlayerEntity player, long tick) {
		boolean reachingSafeLand = snapshot.state() == SurvivalReflexState.AWAITING_PLANNER
			&& snapshot.cause() == SurvivalReflexCause.DROWNING
			&& snapshot.action() == SurvivalReflexAction.REACH_SAFE_LAND;
		boolean safeLand = reachingSafeLand
			&& player.isOnGround()
			&& MinecraftUnderwaterEscapeController.isSafeStandingPosition(client, player.getBlockPos());
		if (safeLand) {
			underwaterEscape.reset(client);
			movementController.stop(client);
			safetyHoldActuating = false;
			releaseHold("safe_land_reached", tick);
			return;
		}
		boolean shouldActuate = shouldMaintainDrowningSafetyHold(
			snapshot.state(), snapshot.cause(), player.isTouchingWater()
		);
		if (!shouldActuate) {
			if (safetyHoldActuating) {
				underwaterEscape.reset(client);
				safetyHoldActuating = false;
			}
			return;
		}
		try {
			if (snapshot.action() == SurvivalReflexAction.REACH_SAFE_LAND) {
				MinecraftUnderwaterEscapeController.Snapshot escape = underwaterEscape.tick(
					client,
					UnderwaterEscapeSearch.SearchMode.SAFE_STANDING,
					player.getAir(),
					tick,
					false
				);
				if (safeLandSearchExhausted(escape.searchStatus(), escape.navigation().phase())) {
					underwaterEscape.reset(client);
					changeAction(SurvivalReflexCause.DROWNING, SurvivalReflexAction.STAY_AFLOAT, tick);
					movementController.swimUp(client, false, false, tick);
				}
			}
			else {
				movementController.swimUp(client, false, false, tick);
			}
			safetyHoldActuating = true;
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("maintain_drowning_safety_hold", exception, tick);
		}
	}

	private List<ResolvedThreat> resolveThreats(MinecraftClient client, ClientPlayerEntity player) {
		if (client == null || client.world == null || player == null || observedThreats.isEmpty()) {
			return List.of();
		}
		ArrayList<ResolvedThreat> resolved = new ArrayList<>();
		for (Entity entity : client.world.getEntities()) {
			if (!(entity instanceof LivingEntity living) || entity instanceof PlayerEntity || !entity.isAlive()) {
				continue;
			}
			ObservedThreat observed = observedThreats.get(entity.getUuidAsString());
			if (observed == null) {
				continue;
			}
			double distance = player.distanceTo(entity);
			resolved.add(new ResolvedThreat(observed, living, distance, player.canSee(entity)));
		}
		return List.copyOf(resolved);
	}

	private static List<SurvivalReflexSnapshot.ThreatSnapshot> threatSnapshots(List<ResolvedThreat> threats) {
		if (threats == null || threats.isEmpty()) {
			return List.of();
		}
		return threats.stream().map(threat -> new SurvivalReflexSnapshot.ThreatSnapshot(
			threat.observed().uuid(), threat.observed().name(), threat.observed().entityTypeId(), threat.distance(),
			threat.entity().isAlive(), threat.lineOfSight()
		)).toList();
	}

	private static String failureText(RuntimeException exception) {
		return exception == null || exception.getMessage() == null
			? exception == null ? "unknown" : exception.getClass().getSimpleName()
			: exception.getMessage();
	}

	private static Map<String, Object> mapOfNullable(Object... pairs) {
		LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		for (int index = 0; index + 1 < pairs.length; index += 2) {
			if (pairs[index] != null && pairs[index + 1] != null) {
				map.put(String.valueOf(pairs[index]), pairs[index + 1]);
			}
		}
		return Map.copyOf(map);
	}

	public record DamageObservation(
		long tick,
		String damageTypeId,
		String attackerUuid,
		String attackerName,
		String attackerEntityTypeId,
		boolean attackerLiving,
		boolean attackerPlayer
	) {
	}

	public record InterruptedWork(String jobId, String actionExecutionId) {
		public static InterruptedWork none() {
			return new InterruptedWork(null, null);
		}

		public boolean hasInterruptedWork() {
			return (jobId != null && !jobId.isBlank()) || (actionExecutionId != null && !actionExecutionId.isBlank());
		}
	}

	public enum ResumeResult {
		RESUMED,
		NO_SAFETY_HOLD,
		REFLEX_ACTIVE,
		STALE_SAFETY_HOLD
	}

	record ObservedThreat(String uuid, String name, String entityTypeId, long lastDamageTick) {
	}

	record ResolvedThreat(ObservedThreat observed, LivingEntity entity, double distance, boolean lineOfSight) {
	}

	private record FleeSelection(GoalPosition goal, boolean sheltered) {
	}

	enum RouteStatus {
		REACHABLE,
		BLOCKED,
		PARTIAL,
		UNKNOWN
	}

	enum SecurityKind {
		UNSAFE,
		SEALED,
		POTENTIAL_SHELTER,
		DISTANT_PATH
	}

	private record MobRoute(RouteStatus status, int pathLength) {
		private static MobRoute unknown() {
			return new MobRoute(RouteStatus.UNKNOWN, -1);
		}
	}

	private record MobSecurity(SecurityKind kind, double closestReachablePathLength) {
	}
}
