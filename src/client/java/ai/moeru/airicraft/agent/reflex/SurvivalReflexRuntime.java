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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.RangedAttackMob;
import net.minecraft.entity.CrossbowUser;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SurvivalReflexRuntime {
	static final int BREATHABLE_STABLE_TICKS = 12;
	private static final float ATTACK_READY_THRESHOLD = 0.92F;
	private static final double MELEE_ATTACK_DISTANCE = 3.0D;
	static final double MELEE_THREAT_DISTANCE = 6.0D;
	private static final int SHELTER_CONFIRM_TICKS = 200;
	private static final int MOB_ROUTE_REFRESH_TICKS = 10;

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
	private int secureEscapeTicks;
	private long mobRoutesTick = Long.MIN_VALUE;
	private Map<String, MobRoute> mobRoutes = Map.of();
	private CompletableFuture<Set<String>> aggroQuery;
	private GoalPosition combatTarget;
	private long combatRouteTick;

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
		evidence.put("combatTarget", combatTarget);
		evidence.put("secureEscapeTicks", secureEscapeTicks);
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
		stopCombatNavigation();
		aggroQuery = null;
		resetSecurityProgress();
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
		resetSecurityProgress();
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
		if (snapshot.action() != SurvivalReflexAction.DEFEND) {
			changeAction(SurvivalReflexCause.MOB_ATTACK, SurvivalReflexAction.DEFEND, tick);
		}
		equipBestCombatItem(client, player);
		attemptCloseQuarterAttack(client, player, threats, tick);
		SecurityKind security = assessMobSecurity(player, threats, tick);
		if (security == SecurityKind.SEALED) {
			secureEscapeTicks++;
			stopCombatNavigation();
			movementController.stop(client);
			if (secureEscapeTicks >= SHELTER_CONFIRM_TICKS) {
				resolve(client, player, threats, tick, "sealed_shelter", false);
				return;
			}
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
			return;
		}
		secureEscapeTicks = 0;
		try {
			if (!threats.isEmpty()) {
				defend(client, player, closestVisibleThreat(threats), tick);
			}
			else {
				movementController.stop(client);
			}
			refreshSnapshot(player, threats, lastMobDamageTick, 0, null);
		}
		catch (RuntimeException exception) {
			recordActuatorFailure("defend", exception, tick);
			refreshSnapshot(player, threats, lastMobDamageTick, 0, failureText(exception));
		}
	}

	private void defend(MinecraftClient client, ClientPlayerEntity player, ResolvedThreat threat, long tick) {
		cameraController.lookAtNow(client, threat.entity().getBoundingBox().getCenter());
		if (threat.distance() <= 3.0D && threat.lineOfSight()) {
			stopCombatNavigation();
			movementController.stop(client);
			return;
		}
		if (baritone != null && baritone.isLoaded()) {
			movementController.stop(client);
			GoalPosition target = goal(threat.entity().getBlockPos());
			if (combatTarget == null || tick - combatRouteTick >= 20L
				&& (!target.equals(combatTarget) || !baritone.processActive())) {
				baritone.applySettings();
				baritone.startNavigateNear(target, 2);
				combatTarget = target;
				combatRouteTick = tick;
			}
		}
		else if (threat.lineOfSight()) {
			movementController.moveDirectional(client, true, false, false, false, true, false, tick);
		}
		else {
			movementController.stop(client);
		}
	}

	private void stopCombatNavigation() {
		if (combatTarget != null && baritone != null) {
			baritone.cancel();
		}
		combatTarget = null;
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
		stopCombatNavigation();
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
		resetSecurityProgress();
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
		pendingEvents.add(new SurvivalReflexEvent("reflex.close_quarter_attack", mapOfNullable(
			"threatUuid", threat.observed().uuid(),
			"weaponItemId", Registries.ITEM.getId(player.getMainHandStack().getItem()).toString(),
			"entityTypeId", threat.observed().entityTypeId(),
			"distance", threat.distance(),
			"action", snapshot.action() == null ? null : snapshot.action().name(),
			"tick", tick
		)));
	}

	private static void equipBestCombatItem(MinecraftClient client, ClientPlayerEntity player) {
		List<String> itemIds = new ArrayList<>();
		for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++)
			itemIds.add(Registries.ITEM.getId(player.getInventory().getStack(slot).getItem()).toString());
		int bestSlot = bestCombatInventorySlot(itemIds);
		if (bestSlot < 0) return;
		if (bestSlot < 9) {
			player.getInventory().setSelectedSlot(bestSlot);
			return;
		}
		if (client.interactionManager == null) return;
		// Match backing inventory indices, since an interrupted task may have a container open.
		for (var slot : player.currentScreenHandler.slots) {
			if (slot.inventory == player.getInventory() && slot.getIndex() == bestSlot) {
				client.interactionManager.clickSlot(player.currentScreenHandler.syncId, slot.id,
					player.getInventory().getSelectedSlot(), net.minecraft.screen.slot.SlotActionType.SWAP, player);
				return;
			}
		}
	}

	static int bestCombatInventorySlot(List<String> itemIds) {
		int bestSlot = -1;
		int bestRank = Integer.MAX_VALUE;
		for (int slot = 0; slot < Math.min(net.minecraft.entity.player.PlayerInventory.MAIN_SIZE, itemIds.size()); slot++) {
			int rank = combatItemRank(itemIds.get(slot));
			if (rank < bestRank) {
				bestRank = rank;
				bestSlot = slot;
			}
		}
		return bestSlot;
	}

	static int combatItemRank(String itemId) {
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
		if (action != SurvivalReflexAction.DEFEND) {
			stopCombatNavigation();
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

	static boolean shouldDetectProactiveThreat(boolean targetingPlayer, boolean alive) {
		return targetingPlayer && alive;
	}

	public static boolean recentlyDamagedByMob(long tick, long lastDamageTick, int cooldownTicks) {
		return lastDamageTick != Long.MIN_VALUE && tick - lastDamageTick < Math.max(0, cooldownTicks);
	}

	static boolean shouldAttackCloseThreat(double distance, boolean lineOfSight, float attackCooldown) {
		return distance <= MELEE_ATTACK_DISTANCE
			&& lineOfSight
			&& attackCooldown >= ATTACK_READY_THRESHOLD;
	}

	static SecurityKind classifyThreatSecurity(
		RouteStatus routeStatus,
		boolean lineOfSight
	) {
		// A long or incomplete route is not shelter: pursuit can resume as soon as we stop.
		return routeStatus == RouteStatus.BLOCKED && !lineOfSight
			? SecurityKind.SEALED : SecurityKind.UNSAFE;
	}

	private SecurityKind assessMobSecurity(ClientPlayerEntity player, List<ResolvedThreat> threats, long tick) {
		if (player == null || threats == null || threats.isEmpty()) {
			return SecurityKind.UNSAFE;
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
		for (ResolvedThreat threat : threats) {
			MobRoute route = mobRoutes.getOrDefault(threat.observed().uuid(), MobRoute.unknown());
			SecurityKind threatSecurity = classifyThreatSecurity(
				route.status(),
				threat.lineOfSight()
			);
			if (threatSecurity == SecurityKind.UNSAFE) {
				combined = SecurityKind.UNSAFE;
			}

		}
		return combined;
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
		// Once an aggressor owns this encounter, fight regardless of health or mob count.
		return SurvivalReflexAction.DEFEND;
	}

	private static boolean isDrowningDamage(String damageTypeId) {
		return damageTypeId != null && (damageTypeId.equals("drown") || damageTypeId.endsWith(":drown"));
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

	private void resetSecurityProgress() {
		secureEscapeTicks = 0;
		mobRoutesTick = Long.MIN_VALUE;
		mobRoutes = Map.of();
	}

	private static GoalPosition goal(BlockPos pos) {
		return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), true);
	}

	private void detectProactiveThreats(MinecraftClient client, ClientPlayerEntity player, long tick) {
		if (client == null || client.world == null || player == null) {
			return;
		}
		Set<String> targetingPlayer = Set.of();
		if (client.getServer() != null) {
			if (aggroQuery != null && aggroQuery.isDone()) {
				targetingPlayer = aggroQuery.join();
				aggroQuery = null;
			}
			if (aggroQuery == null) {
				var server = client.getServer();
				var dimension = client.world.getRegistryKey();
				var bounds = player.getBoundingBox().expand(32.0D);
				UUID playerId = player.getUuid();
				aggroQuery = server.submit(() -> {
					var world = server.getWorld(dimension);
					if (world == null) {
						return Set.<String>of();
					}
					return world.getEntitiesByClass(MobEntity.class, bounds, Entity::isAlive).stream()
						.filter(mob -> mob.getTarget() != null && playerId.equals(mob.getTarget().getUuid()))
						.map(Entity::getUuidAsString).collect(java.util.stream.Collectors.toUnmodifiableSet());
				});
			}
		}
		for (MobEntity mob : client.world.getEntitiesByClass(MobEntity.class,
			player.getBoundingBox().expand(32.0D), Entity::isAlive)) {
			// Remote clients may not receive AI targets; damage observations remain authoritative there.
			boolean targetsUs = targetingPlayer.contains(mob.getUuidAsString())
				|| mob.getTarget() != null && player.getUuid().equals(mob.getTarget().getUuid());
			if (!shouldDetectProactiveThreat(targetsUs, mob.isAlive())
				|| !shouldTrackMobThreat(isRangedThreat(mob), player.distanceTo(mob))) {
				continue;
			}
			if (classifyThreatSecurity(computeMobRoute(player, mob).status(), player.canSee(mob)) == SecurityKind.SEALED) {
				continue;
			}
			String uuid = mob.getUuidAsString();
			if (observedThreats.containsKey(uuid)) {
				continue;
			}
			ObservedThreat observed = new ObservedThreat(uuid, mob.getName().getString(),
				Registries.ENTITY_TYPE.getId(mob.getType()).toString(), tick);
			observedThreats.put(uuid, observed);
			pendingEvents.add(new SurvivalReflexEvent("reflex.threat_detected", mapOfNullable(
				"source", "aggro_target", "uuid", uuid, "name", observed.name(),
				"entityTypeId", observed.entityTypeId(), "distance", player.distanceTo(mob),
				"lineOfSight", player.canSee(mob), "tick", tick)));
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

	static boolean shouldTrackMobThreat(boolean ranged, double distance) {
		return ranged || distance <= MELEE_THREAT_DISTANCE;
	}

	private static boolean isRangedThreat(LivingEntity entity) {
		return isRangedThreat(Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
			Registries.ITEM.getId(entity.getMainHandStack().getItem()).toString(),
			entity instanceof RangedAttackMob || entity instanceof CrossbowUser);
	}

	static boolean isRangedThreat(String entityTypeId, String mainHandItemId, boolean rangedInterface) {
		// Drowned implement RangedAttackMob even when their trident attack goal cannot start.
		if (entityTypeId.equals("minecraft:drowned")) return mainHandItemId.equals("minecraft:trident");
		return rangedInterface || switch (entityTypeId) {
				case "minecraft:blaze", "minecraft:breeze", "minecraft:ghast", "minecraft:guardian",
					"minecraft:elder_guardian", "minecraft:shulker", "minecraft:evoker",
					"minecraft:warden", "minecraft:ender_dragon" -> true;
				default -> false;
			};
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
			if (!shouldTrackMobThreat(isRangedThreat(living), distance)) {
				continue;
			}
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


	enum RouteStatus {
		REACHABLE,
		BLOCKED,
		PARTIAL,
		UNKNOWN
	}

	enum SecurityKind {
		UNSAFE,
		SEALED
	}

	private record MobRoute(RouteStatus status, int pathLength) {
		private static MobRoute unknown() {
			return new MobRoute(RouteStatus.UNKNOWN, -1);
		}
	}

}
