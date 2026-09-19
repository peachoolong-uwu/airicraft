package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Harvests exact loaded source blocks inside a fixed local boundary. It owns
 * underwater approach and breathing; Baritone is used only to reach a safe
 * nearby position while the player is still breathing.
 */
public final class UnderwaterHarvestTaskExecutor implements WorldTaskExecutor {
	private static final double INTERACTION_RANGE_SQUARED = 20.25D;
	private static final int NAVIGATION_RADIUS_BLOCKS = 2;
	private static final int BREAK_TIMEOUT_TICKS = 200;
	private static final int PICKUP_TIMEOUT_TICKS = 80;

	private final Supplier<MinecraftClient> clientSupplier;
	private final BaritoneFacade baritone;
	private final CameraController camera;
	private final MovementController movement;
	private final MinecraftUnderwaterEscapeController underwaterEscape;

	private WorldTaskRequest appliedTask;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();
	private boolean terminalEventEmitted;
	private HarvestRun run;
	private BlockPos breakingTarget;
	private long breakStartedTick = -1L;
	private final Set<UUID> unreachableDropIds = new HashSet<>();
	private int inventoryBeforeBreak;
	private boolean navigationStarted;
	private boolean surfacing;
	private boolean completionPending;
	private String completionMessage;
	private int harvestedBlocks;
	private BlockPos assistedApproachTarget;
	private int obstacleAscentTicksRemaining;
	private BlockPos groundingTarget;
	private int groundingTicks;

	public UnderwaterHarvestTaskExecutor(BaritoneFacade baritone, CameraController camera) {
		this(MinecraftClient::getInstance, baritone, camera);
	}

	UnderwaterHarvestTaskExecutor(
		Supplier<MinecraftClient> clientSupplier,
		BaritoneFacade baritone,
		CameraController camera
	) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.baritone = baritone;
		this.camera = camera == null ? new CameraController() : camera;
		this.movement = new MovementController();
		this.underwaterEscape = new MinecraftUnderwaterEscapeController(baritone, movement, this.camera);
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.UNDERWATER_HARVEST) {
			reset();
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
			BlockPos searchOrigin = searchOrigin(request);
			run = searchOrigin == null ? null : new HarvestRun(searchOrigin);
		}
		if (sessionSnapshot == null || !sessionSnapshot.companionActuationAllowed()) {
			releaseControls();
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}
		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.world == null || client.interactionManager == null || player == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "world_unavailable"));
		}
		if (request.goal() == null || request.goal().mineSpec() == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.INVALID_ACTION, "unsupported_acquisition_method missing_mine_spec"));
		}
		if (run == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "missing_underwater_harvest_origin"));
		}
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "interaction_busy"));
		}

		GoalMineSpec spec = request.goal().mineSpec();
		int inventoryCount = matchingInventoryCount(player, spec.matchingItemIds());
		if (UnderwaterHarvestPolicy.terminalDecision(inventoryCount, spec.quantity(), 1)
			== UnderwaterHarvestPolicy.TerminalDecision.COMPLETE) {
			completionPending = true;
			completionMessage = "underwater_harvest_succeeded itemCount=" + inventoryCount
				+ " targetCount=" + spec.quantity() + " harvestedBlocks=" + harvestedBlocks;
		}
		long tick = sessionSnapshot.tickCount();
		boolean airRecoveryRequired = UnderwaterHarvestPolicy.shouldSurface(
			player.isSubmergedInWater(),
			player.getAir(),
			player.getMaxAir()
		);
		boolean airRecoveryComplete = UnderwaterHarvestPolicy.mayResumeHarvest(
			player.isSubmergedInWater(),
			player.getAir(),
			player.getMaxAir()
		);
		UnderwaterHarvestPolicy.PreSourceAction preSourceAction = UnderwaterHarvestPolicy.preSourceAction(
			surfacing,
			airRecoveryRequired,
			completionPending,
			airRecoveryComplete,
			run.pickupTicksRemaining() >= 0
		);
		if (preSourceAction == UnderwaterHarvestPolicy.PreSourceAction.RECOVER_AIR) {
			run.tickPickup(true);
			return tickSurfacing(request, client, player, tick);
		}
		if (preSourceAction == UnderwaterHarvestPolicy.PreSourceAction.COMPLETE) {
			return tickCompletion(request, client);
		}
		if (preSourceAction == UnderwaterHarvestPolicy.PreSourceAction.PICK_UP) {
			Optional<TaskTerminalEvent> pickup = tickPickup(request, client, player, spec, tick);
			if (pickup.isPresent() || run.pickupTicksRemaining() >= 0) {
				return pickup;
			}
		}
		if (run.needsSourceScan()) {
			SourceScan scan = selectBatch(client, spec, run.searchOrigin(), run.unreachableTargets());
			run.installBatch(scan);
			if (run.currentTarget().isEmpty()) {
				boolean everyDiscoveredExcluded = !scan.discoveredTargets().isEmpty()
					&& run.unreachableTargets().containsAll(scan.discoveredTargets());
				UnderwaterHarvestPolicy.SourceExhaustion exhaustion = UnderwaterHarvestPolicy.sourceExhaustion(
					scan.discoveredTargets().size(),
					run.unreachableTargets().size(),
					everyDiscoveredExcluded
				);
				String reason = exhaustion == UnderwaterHarvestPolicy.SourceExhaustion.RESOURCE_UNREACHABLE_NEARBY
					? "resource_unreachable_nearby"
					: "resource_not_found_nearby";
				return fail(request, TaskFailure.of(
					exhaustion == UnderwaterHarvestPolicy.SourceExhaustion.RESOURCE_NOT_FOUND_NEARBY
						? TaskFailureCode.MISSING_FACT
						: TaskFailureCode.UNKNOWN,
					reason + " radius=" + UnderwaterHarvestPolicy.HORIZONTAL_RADIUS
						+ " verticalRadius=" + UnderwaterHarvestPolicy.VERTICAL_RADIUS + " blockIds=" + spec.blockIds()
						+ " itemCount=" + inventoryCount + " targetCount=" + spec.quantity()
				));
			}
		}

		HarvestTarget target = run.currentTarget().orElseThrow();
		BlockState currentState = client.world.getBlockState(target.pos());
		if (!spec.blockIds().contains(blockId(currentState))) {
			cancelNavigation();
			movement.stop(client);
			clearBreak(client);
			run.invalidateBatch();
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_reassess targetPos=" + compactPos(target.pos()));
			return Optional.empty();
		}
		Optional<UnderwaterHarvestPolicy.SourceEnvironment> observedEnvironment =
			MinecraftUnderwaterSourceClassifier.classify(client, target.pos(), currentState);
		if (observedEnvironment.isEmpty()) {
			cancelNavigation();
			movement.stop(client);
			clearBreak(client);
			run.invalidateBatch();
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_no_longer_accessible targetPos="
				+ compactPos(target.pos()));
			return Optional.empty();
		}
		UnderwaterHarvestPolicy.SourceEnvironment currentEnvironment = observedEnvironment.orElseThrow();
		if (!run.reconcileEnvironment(currentEnvironment)) {
			cancelNavigation();
			movement.stop(client);
			clearBreak(client);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_environment_changed targetPos="
				+ compactPos(target.pos()) + " environment=" + currentEnvironment.name().toLowerCase(java.util.Locale.ROOT));
			return Optional.empty();
		}
		if (target.environment().underwater()
			&& UnderwaterHarvestPolicy.shouldSurface(player.isSubmergedInWater(), player.getAir(), player.getMaxAir())) {
			return tickSurfacing(request, client, player, tick);
		}
		// A successful break can consume the last required tool. Air recovery and
		// pickup must still finish before another source action needs that tool.
		if (!selectRequiredTool(client, player, spec.requiredToolItemIds())) {
			return fail(request, TaskFailure.of(TaskFailureCode.INVALID_ACTION, "unsupported_acquisition_method missing_required_tool requiredToolItemIds=" + spec.requiredToolItemIds()));
		}

		Vec3d targetCenter = Vec3d.ofCenter(target.pos());
		double distanceSquared = player.getEyePos().squaredDistanceTo(targetCenter);
		if (distanceSquared > INTERACTION_RANGE_SQUARED) {
			return tickApproach(request, client, player, target, targetCenter, distanceSquared, tick);
		}
		run.clearApproach();
		cancelNavigation();
		if (!awaitBaritoneRelease(request, client, target, "waiting_to_break_after_baritone_release")) {
			return Optional.empty();
		}
		clearApproachAssist();
		if (tickGroundingForBreak(request, client, player, target, tick)) {
			return Optional.empty();
		}
		movement.stop(client);
		camera.lookAt(client, targetCenter);
		if (!camera.isLookingAt(client, targetCenter)) return Optional.empty();
		if (breakingTarget == null) {
			if (!client.interactionManager.attackBlock(target.pos(), Direction.UP)) {
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "break_start_failed targetPos=" + compactPos(target.pos())));
			}
			breakingTarget = target.pos();
			breakStartedTick = tick;
			inventoryBeforeBreak = inventoryCount;
		}
		if (tick - breakStartedTick > BREAK_TIMEOUT_TICKS) {
			return fail(request, TaskFailure.of(TaskFailureCode.TRANSIENT, "break_timeout targetPos=" + compactPos(target.pos())));
		}
		client.interactionManager.updateBlockBreakingProgress(target.pos(), Direction.UP);
		player.swingHand(Hand.MAIN_HAND);
		if (!spec.blockIds().contains(blockId(client.world.getBlockState(target.pos())))) {
			harvestedBlocks++;
			clearBreak(client);
			// Breaking a source can expose water around other candidates. Re-scan
			// from the immutable origin so dry-first ordering uses current fluid.
			run.invalidateBatch();
			run.startPickupWindow(PICKUP_TIMEOUT_TICKS);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_broken targetPos=" + compactPos(target.pos())
				+ " environment=" + target.environment().name().toLowerCase());
			return Optional.empty();
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "breaking targetPos=" + compactPos(target.pos())
			+ " environment=" + target.environment().name().toLowerCase() + " air=" + player.getAir());
		return Optional.empty();
	}

	private boolean tickGroundingForBreak(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		HarvestTarget target,
		long tick
	) {
		BlockPos immutableTarget = target.pos().toImmutable();
		if (!immutableTarget.equals(groundingTarget)) {
			groundingTarget = immutableTarget;
			groundingTicks = 0;
		}
		UnderwaterHarvestPolicy.GroundingDecision decision = UnderwaterHarvestPolicy.groundingDecision(
			target.environment().underwater(),
			player.isSubmergedInWater(),
			player.isOnGround(),
			hasSolidSupportDirectlyBelow(client, player),
			groundingTicks
		);
		if (decision != UnderwaterHarvestPolicy.GroundingDecision.DESCEND) {
			clearGrounding();
			return false;
		}
		groundingTicks++;
		movement.moveDirectional(client, false, false, false, false, false, false, true, tick);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "descending_to_ground targetPos="
			+ compactPos(target.pos()) + " groundingTicks=" + groundingTicks);
		return true;
	}

	private static boolean hasSolidSupportDirectlyBelow(MinecraftClient client, ClientPlayerEntity player) {
		if (client == null || client.world == null || player == null) {
			return false;
		}
		BlockPos supportPos = player.getBlockPos().down();
		return client.world.getBlockState(supportPos).isSideSolidFullSquare(client.world, supportPos, Direction.UP);
	}

	private Optional<TaskTerminalEvent> tickApproach(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		HarvestTarget target,
		Vec3d targetCenter,
		double distanceSquared,
		long tick
	) {
		if (run.prepareApproach(target.pos(), Math.sqrt(distanceSquared))) {
			movement.stop(client);
			clearApproachAssist();
		}
		UnderwaterHarvestPolicy.PositioningMode positioningMode = UnderwaterHarvestPolicy.positioningMode(target.environment());
		if (positioningMode == UnderwaterHarvestPolicy.PositioningMode.BARITONE) {
			if (!navigationStarted
				&& !awaitBaritoneRelease(request, client, target, "waiting_to_start_dry_baritone")) {
				return Optional.empty();
			}
		}
		else {
			cancelNavigation();
			if (!awaitBaritoneRelease(request, client, target, "waiting_to_start_underwater_approach")) {
				return Optional.empty();
			}
		}
		UnderwaterHarvestPolicy.ApproachUpdate progress = run.observeApproach(Math.sqrt(distanceSquared));
		if (progress.decision() == UnderwaterHarvestPolicy.ApproachDecision.EXCLUDE_TARGET) {
			String reason = run.approachProgress().activeTicks() >= UnderwaterHarvestPolicy.APPROACH_TIMEOUT_TICKS
				? "approach_timeout"
				: "approach_stalled";
			return excludeTarget(request, client, target, reason);
		}
		return routeApproachEffect(
			target.environment(),
			() -> tickDryApproach(request, client, target),
			() -> tickUnderwaterApproach(request, client, player, target, targetCenter, tick)
		);
	}

	private Optional<TaskTerminalEvent> tickDryApproach(
		WorldTaskRequest request,
		MinecraftClient client,
		HarvestTarget target
	) {
		movement.stop(client);
		if (baritone == null || !baritone.isLoaded()) {
			return excludeTarget(request, client, target, "baritone_unavailable");
		}
		if (!navigationStarted) {
			baritone.startNavigateNear(goalPosition(target.pos()), NAVIGATION_RADIUS_BLOCKS);
			navigationStarted = true;
		}
		Optional<String> event = baritone.pollPathEvent();
		if (event.isPresent() && ("CALC_FAILED".equalsIgnoreCase(event.get())
			|| "CANCELLED".equalsIgnoreCase(event.get()) || "CANCELED".equalsIgnoreCase(event.get()))) {
			return excludeTarget(request, client, target, "baritone_" + event.orElseThrow().toLowerCase(java.util.Locale.ROOT));
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "approaching_dry_target_with_baritone targetPos=" + compactPos(target.pos())
			+ " approachTicks=" + run.approachProgress().activeTicks());
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickUnderwaterApproach(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		HarvestTarget target,
		Vec3d targetCenter,
		long tick
	) {
		camera.lookAt(client, targetCenter);
		UnderwaterHarvestPolicy.VerticalMotion verticalMotion = moveUnderwaterToward(
			client,
			player,
			target.pos(),
			targetCenter,
			tick
		);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "approaching_underwater_target targetPos=" + compactPos(target.pos())
			+ " environment=" + target.environment().name().toLowerCase() + " air=" + player.getAir()
			+ " verticalMotion=" + verticalMotion.name().toLowerCase()
			+ " approachTicks=" + run.approachProgress().activeTicks());
		return Optional.empty();
	}

	private UnderwaterHarvestPolicy.VerticalMotion moveUnderwaterToward(
		MinecraftClient client,
		ClientPlayerEntity player,
		BlockPos targetPos,
		Vec3d target,
		long tick
	) {
		if (!player.isTouchingWater() && !player.isSubmergedInWater()) {
			clearApproachAssist();
			movement.moveForward(client, false, false, tick);
			return UnderwaterHarvestPolicy.VerticalMotion.LEVEL;
		}
		BlockPos immutableTarget = targetPos.toImmutable();
		if (!immutableTarget.equals(assistedApproachTarget)) {
			assistedApproachTarget = immutableTarget;
			obstacleAscentTicksRemaining = 0;
		}
		boolean ascentClear = verticalClearance(client, player, 0.6D);
		if (player.horizontalCollision && ascentClear) {
			obstacleAscentTicksRemaining = UnderwaterHarvestPolicy.OBSTACLE_ASCENT_TICKS;
		}
		boolean descentClear = verticalClearance(client, player, -0.6D);
		UnderwaterHarvestPolicy.VerticalMotion motion = UnderwaterHarvestPolicy.underwaterVerticalMotion(
			target.y - player.getEyeY(),
			player.horizontalCollision,
			obstacleAscentTicksRemaining > 0,
			ascentClear,
			descentClear
		);
		if (motion == UnderwaterHarvestPolicy.VerticalMotion.ASCEND && obstacleAscentTicksRemaining > 0) {
			obstacleAscentTicksRemaining--;
		}
		else if (!ascentClear) {
			obstacleAscentTicksRemaining = 0;
		}
		movement.moveDirectional(
			client,
			true,
			false,
			false,
			false,
			false,
			motion == UnderwaterHarvestPolicy.VerticalMotion.ASCEND,
			motion == UnderwaterHarvestPolicy.VerticalMotion.DESCEND,
			tick
		);
		return motion;
	}

	private static boolean verticalClearance(MinecraftClient client, ClientPlayerEntity player, double offsetY) {
		return client != null
			&& client.world != null
			&& player != null
			&& client.world.isSpaceEmpty(player, player.getBoundingBox().offset(0.0D, offsetY, 0.0D));
	}

	static <T> T routeApproachEffect(
		UnderwaterHarvestPolicy.SourceEnvironment environment,
		Supplier<T> baritoneEffect,
		Supplier<T> directEffect
	) {
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(baritoneEffect, "baritoneEffect");
		Objects.requireNonNull(directEffect, "directEffect");
		return UnderwaterHarvestPolicy.positioningMode(environment)
			== UnderwaterHarvestPolicy.PositioningMode.BARITONE
			? baritoneEffect.get()
			: directEffect.get();
	}

	private Optional<TaskTerminalEvent> excludeTarget(
		WorldTaskRequest request,
		MinecraftClient client,
		HarvestTarget target,
		String reason
	) {
		run.excludeCurrentTarget(target.pos());
		cancelNavigation();
		clearBreak(client);
		movement.stop(client);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_excluded targetPos=" + compactPos(target.pos())
			+ " reason=" + reason);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickSurfacing(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		long tick
	) {
		if (!surfacing) {
			cancelNavigation();
			clearBreak(client);
			clearApproachAssist();
			underwaterEscape.reset(client);
			surfacing = true;
		}
		MinecraftUnderwaterEscapeController.Snapshot escape = underwaterEscape.tick(
			client,
			UnderwaterEscapeSearch.SearchMode.BREATHABLE,
			player.getAir(),
			tick,
			!player.isSubmergedInWater()
		);
		boolean mayResume = UnderwaterHarvestPolicy.mayResumeHarvest(
			player.isSubmergedInWater(), player.getAir(), player.getMaxAir());
		if (UnderwaterHarvestPolicy.recoveryComplete(
			mayResume,
			escape.navigation().phase() == UnderwaterEscapeNavigator.Phase.REACHED,
			escape.navigation().ownsBaritone(),
			escape.waitingForBaritoneRelease(),
			BaritoneReleaseBarrier.released(baritone)
		)) {
			underwaterEscape.reset(client);
			movement.stop(client);
			surfacing = false;
			if (completionPending) {
				return tickCompletion(request, client);
			}
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "air_replenished air=" + player.getAir());
			return Optional.empty();
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "surfacing_for_air air=" + player.getAir()
			+ " reserve=" + UnderwaterHarvestPolicy.AIR_RESERVE_TICKS
			+ " escapePhase=" + escape.navigation().phase().name().toLowerCase(java.util.Locale.ROOT)
			+ " escapeCandidates=" + escape.candidateCount());
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickPickup(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		GoalMineSpec spec,
		long tick
	) {
		int inventoryCount = matchingInventoryCount(player, spec.matchingItemIds());
		if (inventoryCount >= spec.quantity()) {
			run.clearPickupWindow();
			completionPending = true;
			completionMessage = "underwater_harvest_succeeded itemCount=" + inventoryCount
				+ " targetCount=" + spec.quantity() + " harvestedBlocks=" + harvestedBlocks;
			if (!UnderwaterHarvestPolicy.mayResumeHarvest(
				player.isSubmergedInWater(), player.getAir(), player.getMaxAir())) {
				return tickSurfacing(request, client, player, tick);
			}
			return tickCompletion(request, client);
		}
		Optional<ItemEntity> drop = nearestMatchingDrop(client, player, spec.matchingItemIds(), unreachableDropIds);
		UnderwaterHarvestPolicy.PickupDecision decision = UnderwaterHarvestPolicy.pickupDecision(
			inventoryBeforeBreak,
			inventoryCount,
			run.pickupTicksRemaining(),
			drop.isPresent()
		);
		if (decision == UnderwaterHarvestPolicy.PickupDecision.COLLECTED) {
			movement.stop(client);
			clearApproachAssist();
			run.clearPickupWindow();
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "pickup_collected itemCount=" + inventoryCount + " targetCount=" + spec.quantity());
			return Optional.empty();
		}
		if (decision == UnderwaterHarvestPolicy.PickupDecision.UNREACHABLE) {
			movement.stop(client);
			clearApproachAssist();
			drop.ifPresent(entity -> unreachableDropIds.add(entity.getUuid()));
			run.clearPickupWindow();
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "pickup_unreachable itemCount=" + inventoryCount + " targetCount=" + spec.quantity());
			return Optional.empty();
		}
		run.tickPickup(false);
		if (decision == UnderwaterHarvestPolicy.PickupDecision.APPROACH) {
			Vec3d itemPos = drop.orElseThrow().getPos();
			UnderwaterHarvestPolicy.PickupTarget blockCenter = UnderwaterHarvestPolicy.pickupTarget(
				itemPos.x,
				itemPos.y,
				itemPos.z
			);
			Vec3d target = new Vec3d(blockCenter.x(), blockCenter.y(), blockCenter.z());
			camera.lookAt(client, target);
			UnderwaterHarvestPolicy.VerticalMotion verticalMotion = moveUnderwaterToward(
				client,
				player,
				new BlockPos(
					(int) Math.floor(target.x),
					(int) Math.floor(target.y),
					(int) Math.floor(target.z)
				),
				target,
				tick
			);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "collecting_drop itemCount=" + inventoryCount
				+ " targetCount=" + spec.quantity() + " verticalMotion=" + verticalMotion.name().toLowerCase());
			return Optional.empty();
		}
		movement.stop(client);
		clearApproachAssist();
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_drop itemCount=" + inventoryCount);
		return Optional.empty();
	}

	private static SourceScan selectBatch(
		MinecraftClient client,
		GoalMineSpec spec,
		BlockPos origin,
		Set<BlockPos> excludedTargets
	) {
		Set<String> blockIds = new HashSet<>(spec.blockIds());
		ArrayList<UnderwaterHarvestPolicy.Target> candidates = new ArrayList<>();
		Set<BlockPos> discovered = new HashSet<>();
		for (int x = origin.getX() - UnderwaterHarvestPolicy.HORIZONTAL_RADIUS; x <= origin.getX() + UnderwaterHarvestPolicy.HORIZONTAL_RADIUS; x++) {
			for (int z = origin.getZ() - UnderwaterHarvestPolicy.HORIZONTAL_RADIUS; z <= origin.getZ() + UnderwaterHarvestPolicy.HORIZONTAL_RADIUS; z++) {
				for (int y = origin.getY() - UnderwaterHarvestPolicy.VERTICAL_RADIUS; y <= origin.getY() + UnderwaterHarvestPolicy.VERTICAL_RADIUS; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!client.world.isInBuildLimit(pos) || !client.world.isChunkLoaded(pos)) {
						continue;
					}
					BlockState state = client.world.getBlockState(pos);
					String blockId = blockId(state);
					if (!blockIds.contains(blockId)) {
						continue;
					}
					discovered.add(pos.toImmutable());
					if (excludedTargets.contains(pos)) {
						continue;
					}
					Optional<UnderwaterHarvestPolicy.SourceEnvironment> environment =
						MinecraftUnderwaterSourceClassifier.classify(client, pos, state);
					if (environment.isPresent()) {
						candidates.add(new UnderwaterHarvestPolicy.Target(
							new UnderwaterHarvestPolicy.Position(x, y, z),
							blockId,
							environment.orElseThrow()
						));
					}
				}
			}
		}
		UnderwaterHarvestPolicy.Position policyOrigin = new UnderwaterHarvestPolicy.Position(origin.getX(), origin.getY(), origin.getZ());
		List<HarvestTarget> selected = UnderwaterHarvestPolicy.selectBatch(candidates, policyOrigin).stream()
			.map(target -> new HarvestTarget(
				new BlockPos(target.position().x(), target.position().y(), target.position().z()),
				target.blockId(),
				target.environment()
			))
			.toList();
		return new SourceScan(selected, Set.copyOf(discovered));
	}

	private static Optional<ItemEntity> nearestMatchingDrop(
		MinecraftClient client,
		ClientPlayerEntity player,
		List<String> matchingItemIds,
		Set<UUID> excludedDropIds
	) {
		Set<String> ids = new HashSet<>(matchingItemIds);
		return client.world.getEntitiesByClass(
			ItemEntity.class,
			new Box(player.getBlockPos()).expand(8.0D),
			entity -> ids.contains(itemId(entity.getStack())) && !excludedDropIds.contains(entity.getUuid())
		).stream().min(java.util.Comparator.comparingDouble(player::squaredDistanceTo));
	}

	private static int matchingInventoryCount(ClientPlayerEntity player, List<String> matchingItemIds) {
		Set<String> ids = new HashSet<>(matchingItemIds);
		int total = 0;
		for (int slot = 0; slot < player.getInventory().size(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!stack.isEmpty() && ids.contains(itemId(stack))) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static boolean selectRequiredTool(MinecraftClient client, ClientPlayerEntity player, List<String> requiredToolItemIds) {
		if (requiredToolItemIds == null || requiredToolItemIds.isEmpty()) {
			return true;
		}
		Set<String> required = new HashSet<>(requiredToolItemIds);
		if (matchesRequiredTool(player.getInventory().getSelectedStack(), required)) {
			return true;
		}
		ScreenHandler handler = player.currentScreenHandler;
		int sourceSlot = findRequiredToolSlot(handler, required);
		if (sourceSlot < 0 || !isRequiredToolSourceSlot(sourceSlot)) {
			return false;
		}
		int selectedHotbarSlot = player.getInventory().getSelectedSlot();
		if (sourceSlot >= PlayerScreenHandler.HOTBAR_START && sourceSlot < PlayerScreenHandler.HOTBAR_END) {
			selectAndSyncHotbarSlot(client, player, sourceSlot - PlayerScreenHandler.HOTBAR_START);
		}
		else {
			client.interactionManager.clickSlot(handler.syncId, sourceSlot, selectedHotbarSlot, SlotActionType.SWAP, player);
			selectAndSyncHotbarSlot(client, player, selectedHotbarSlot);
		}
		return matchesRequiredTool(player.getInventory().getSelectedStack(), required);
	}

	private static int findRequiredToolSlot(ScreenHandler handler, Set<String> requiredItemIds) {
		if (!(handler instanceof PlayerScreenHandler)) {
			return -1;
		}
		for (int slot = PlayerScreenHandler.HOTBAR_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			if (matchesRequiredTool(handler.getSlot(slot).getStack(), requiredItemIds)) {
				return slot;
			}
		}
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_START; slot++) {
			if (matchesRequiredTool(handler.getSlot(slot).getStack(), requiredItemIds)) {
				return slot;
			}
		}
		return matchesRequiredTool(handler.getSlot(PlayerScreenHandler.OFFHAND_ID).getStack(), requiredItemIds)
			? PlayerScreenHandler.OFFHAND_ID
			: -1;
	}

	static boolean isRequiredToolSourceSlot(int slot) {
		return (slot >= PlayerScreenHandler.INVENTORY_START && slot < PlayerScreenHandler.HOTBAR_END)
			|| slot == PlayerScreenHandler.OFFHAND_ID;
	}

	private static boolean matchesRequiredTool(ItemStack stack, Set<String> requiredItemIds) {
		return stack != null
			&& !stack.isEmpty()
			&& requiredItemIds.contains(itemId(stack));
	}

	private static void selectAndSyncHotbarSlot(MinecraftClient client, ClientPlayerEntity player, int hotbarSlot) {
		if (player.getInventory().getSelectedSlot() == hotbarSlot) {
			return;
		}
		player.getInventory().setSelectedSlot(hotbarSlot);
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarSlot));
		}
	}

	private Optional<TaskTerminalEvent> tickCompletion(WorldTaskRequest request, MinecraftClient client) {
		cancelNavigation();
		clearBreak(client);
		movement.stop(client);
		if (!BaritoneReleaseBarrier.releaseAndDrain(baritone)) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_completion_baritone_release");
			return Optional.empty();
		}
		underwaterEscape.reset(client);
		ClientPlayerEntity player = client == null ? null : client.player;
		GoalMineSpec spec = request.goal() == null ? null : request.goal().mineSpec();
		int inventoryCount = player == null || spec == null
			? 0
			: matchingInventoryCount(player, spec.matchingItemIds());
		if (spec == null || UnderwaterHarvestPolicy.terminalDecision(inventoryCount, spec.quantity(), 1)
			!= UnderwaterHarvestPolicy.TerminalDecision.COMPLETE) {
			completionPending = false;
			completionMessage = null;
			snapshot = snapshot(
				TaskExecutionState.RUNNING,
				request,
				"completion_revalidated itemCount=" + inventoryCount
					+ " targetCount=" + (spec == null ? 0 : spec.quantity())
			);
			return Optional.empty();
		}
		String message = completionMessage == null || completionMessage.isBlank()
			? "underwater_harvest_succeeded"
			: completionMessage;
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, TaskFailure failure) {
		releaseControls();
		snapshot = snapshot(TaskExecutionState.FAILED, request, failure.detail());
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, failure.detail(), null, failure.code()));
	}

	private void clearBreak(MinecraftClient client) {
		if (breakingTarget != null && client != null && client.interactionManager != null) {
			client.interactionManager.cancelBlockBreaking();
		}
		breakingTarget = null;
		breakStartedTick = -1L;
		clearGrounding();
	}

	private void clearApproachAssist() {
		assistedApproachTarget = null;
		obstacleAscentTicksRemaining = 0;
	}

	private void clearGrounding() {
		groundingTarget = null;
		groundingTicks = 0;
	}

	private void cancelNavigation() {
		if (navigationStarted && baritone != null && baritone.isLoaded()) {
			baritone.cancel();
		}
		navigationStarted = false;
	}

	private boolean awaitBaritoneRelease(
		WorldTaskRequest request,
		MinecraftClient client,
		HarvestTarget target,
		String event
	) {
		if (BaritoneReleaseBarrier.releaseAndDrain(baritone)) {
			return true;
		}
		movement.stop(client);
		snapshot = snapshot(
			TaskExecutionState.RUNNING,
			request,
			event + " targetPos=" + compactPos(target.pos())
		);
		return false;
	}

	private void releaseControls() {
		MinecraftClient client = clientSupplier.get();
		cancelNavigation();
		clearBreak(client);
		underwaterEscape.reset(client);
		movement.stop(client);
		clearApproachAssist();
		clearGrounding();
	}

	private void reset() {
		releaseControls();
		appliedTask = null;
		snapshot = TaskExecutionSnapshot.idle();
		terminalEventEmitted = false;
		run = null;
		unreachableDropIds.clear();
		inventoryBeforeBreak = 0;
		surfacing = false;
		completionPending = false;
		completionMessage = null;
		harvestedBlocks = 0;
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		return left != null && right != null
			&& left.type() == WorldTaskType.UNDERWATER_HARVEST
			&& right.type() == WorldTaskType.UNDERWATER_HARVEST
			&& Objects.equals(left.taskId(), right.taskId());
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), request.goal(), "UnderwaterHarvest", event, null, null);
	}

	private static GoalPosition goalPosition(BlockPos pos) {
		return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), true);
	}

	BlockPos searchOriginSnapshot() {
		return run == null ? null : run.searchOrigin();
	}

	private static BlockPos searchOrigin(WorldTaskRequest request) {
		UnderwaterHarvestStepArgs args = request == null
			? null
			: ((WorldTaskRequest.UnderwaterHarvest) request.task()).args();
		GoalPosition origin = args == null ? null : args.searchOrigin();
		return origin == null ? null : new BlockPos(origin.x(), origin.y(), origin.z());
	}

	private static String blockId(BlockState state) {
		return Registries.BLOCK.getId(state.getBlock()).toString();
	}

	private static String itemId(ItemStack stack) {
		return Registries.ITEM.getId(stack.getItem()).toString();
	}

	private static String compactPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		reset();
	}

	@Override
	public void shutdown() {
		reset();
	}

	/**
	 * Mutable per-task progress isolated from the Minecraft effect shell. The
	 * handoff origin is captured once in the constructor and cannot drift while
	 * surfacing, collecting drops, or re-scanning changed fluid state.
	 */
	static final class HarvestRun {
		private final BlockPos searchOrigin;
		private List<HarvestTarget> batch = List.of();
		private int batchCursor;
		private int pickupTicksRemaining = -1;
		private final Set<BlockPos> unreachableTargets = new HashSet<>();
		private BlockPos approachTarget;
		private UnderwaterHarvestPolicy.ApproachProgress approachProgress;

		HarvestRun(BlockPos searchOrigin) {
			this.searchOrigin = Objects.requireNonNull(searchOrigin, "searchOrigin").toImmutable();
		}

		BlockPos searchOrigin() {
			return searchOrigin;
		}

		void installBatch(SourceScan scan) {
			batch = scan == null ? List.of() : List.copyOf(scan.batch());
			batchCursor = 0;
			clearApproach();
		}

		Optional<HarvestTarget> currentTarget() {
			return batchCursor >= 0 && batchCursor < batch.size()
				? Optional.of(batch.get(batchCursor))
				: Optional.empty();
		}

		boolean needsSourceScan() {
			return currentTarget().isEmpty();
		}

		void invalidateBatch() {
			batch = List.of();
			batchCursor = 0;
			clearApproach();
		}

		boolean reconcileEnvironment(UnderwaterHarvestPolicy.SourceEnvironment currentEnvironment) {
			HarvestTarget target = currentTarget().orElse(null);
			if (target != null && target.environment() == currentEnvironment) {
				return true;
			}
			invalidateBatch();
			return false;
		}

		boolean prepareApproach(BlockPos target, double distanceBlocks) {
			BlockPos immutableTarget = Objects.requireNonNull(target, "target").toImmutable();
			if (immutableTarget.equals(approachTarget)) {
				return false;
			}
			clearApproach();
			approachTarget = immutableTarget;
			approachProgress = UnderwaterHarvestPolicy.beginApproach(distanceBlocks);
			return true;
		}

		UnderwaterHarvestPolicy.ApproachProgress approachProgress() {
			return approachProgress;
		}

		UnderwaterHarvestPolicy.ApproachUpdate observeApproach(double distanceBlocks) {
			UnderwaterHarvestPolicy.ApproachUpdate update = UnderwaterHarvestPolicy.observeApproach(
				approachProgress,
				distanceBlocks
			);
			approachProgress = update.progress();
			return update;
		}

		void clearApproach() {
			approachTarget = null;
			approachProgress = null;
		}

		void excludeCurrentTarget(BlockPos target) {
			BlockPos immutableTarget = Objects.requireNonNull(target, "target").toImmutable();
			unreachableTargets.add(immutableTarget);
			if (currentTarget().map(HarvestTarget::pos).filter(immutableTarget::equals).isPresent()) {
				batchCursor++;
				clearApproach();
			}
			else {
				invalidateBatch();
			}
		}

		Set<BlockPos> unreachableTargets() {
			return Set.copyOf(unreachableTargets);
		}

		void startPickupWindow(int ticks) {
			pickupTicksRemaining = Math.max(-1, ticks);
		}

		void tickPickup(boolean recoveringAir) {
			pickupTicksRemaining = UnderwaterHarvestPolicy.pickupTicksAfterTick(
				pickupTicksRemaining,
				recoveringAir
			);
		}

		void clearPickupWindow() {
			pickupTicksRemaining = -1;
		}

		int pickupTicksRemaining() {
			return pickupTicksRemaining;
		}

		RunSnapshot snapshot() {
			HarvestTarget current = currentTarget().orElse(null);
			return new RunSnapshot(
				searchOrigin,
				pickupTicksRemaining,
				unreachableTargets,
				needsSourceScan(),
				current == null ? null : current.pos(),
				current == null ? null : current.environment(),
				approachProgress
			);
		}
	}

	record RunSnapshot(
		BlockPos searchOrigin,
		int pickupTicksRemaining,
		Set<BlockPos> unreachableTargets,
		boolean needsSourceScan,
		BlockPos currentTarget,
		UnderwaterHarvestPolicy.SourceEnvironment currentEnvironment,
		UnderwaterHarvestPolicy.ApproachProgress approachProgress
	) {
		RunSnapshot {
			searchOrigin = searchOrigin == null ? null : searchOrigin.toImmutable();
			unreachableTargets = Set.copyOf(unreachableTargets);
			currentTarget = currentTarget == null ? null : currentTarget.toImmutable();
		}
	}

	static record HarvestTarget(
		BlockPos pos,
		String blockId,
		UnderwaterHarvestPolicy.SourceEnvironment environment
	) {
		HarvestTarget {
			pos = Objects.requireNonNull(pos, "pos").toImmutable();
			Objects.requireNonNull(blockId, "blockId");
			Objects.requireNonNull(environment, "environment");
		}
	}

	static record SourceScan(List<HarvestTarget> batch, Set<BlockPos> discoveredTargets) {
		SourceScan {
			batch = List.copyOf(batch);
			discoveredTargets = Set.copyOf(discoveredTargets);
		}
	}
}
