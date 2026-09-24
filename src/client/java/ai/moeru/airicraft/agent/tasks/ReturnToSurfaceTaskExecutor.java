package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class ReturnToSurfaceTaskExecutor implements WorldTaskExecutor {
	private static final int NAVIGATION_RADIUS_BLOCKS = 3;
	private static final int MAX_TOWER_BLOCKS = 96;
	private static final int BREATHABLE_STABLE_TICKS = 12;
	private static final int HEADROOM_BREAK_TIMEOUT_TICKS = 600;
	private static final int TOWER_SUPPORT_SEARCH_DEPTH = 3;
	private static final int TOWER_SUPPORT_UNAVAILABLE_TIMEOUT_TICKS = 100;
	private static final int UNDERWATER_ESCAPE_PHASE_TICKS = 20;
	private static final double TARGET_FORWARD_HORIZONTAL_DISTANCE_SQUARED = 4.0D;

	private final Supplier<MinecraftClient> clientSupplier;
	private final BaritoneFacade baritoneFacade;
	private final MovementController movementController = new MovementController();
	private final CameraController cameraController;
	private final OwnedKeyPress jumpKeyControl = new OwnedKeyPress();

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private boolean navigationStarted;
	private boolean exactSurfaceNavigationStarted;
	private boolean toweringStarted;
	private int towerStartY;
	private BlockPos headroomBreakTarget;
	private long headroomBreakStartTick = -1L;
	private boolean underwaterRecoveryStarted;
	private int breathableTicks;
	private int underwaterStuckTicks;
	private int towerSupportUnavailableTicks;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public ReturnToSurfaceTaskExecutor(BaritoneFacade baritoneFacade) {
		this(MinecraftClient::getInstance, baritoneFacade);
	}

	ReturnToSurfaceTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade baritoneFacade) {
		this(clientSupplier, baritoneFacade, new CameraController());
	}

	public ReturnToSurfaceTaskExecutor(BaritoneFacade baritoneFacade, CameraController cameraController) {
		this(MinecraftClient::getInstance, baritoneFacade, cameraController);
	}

	private ReturnToSurfaceTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade baritoneFacade, CameraController cameraController) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.baritoneFacade = baritoneFacade;
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.RETURN_TO_SURFACE) {
			if (appliedTask != null) {
				reset();
			}
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}
		if (sessionSnapshot == null || !sessionSnapshot.companionActuationAllowed()) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.world == null || client.interactionManager == null || player == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "world_unavailable"));
		}
		ReturnToSurfaceStepArgs args = ((WorldTaskRequest.ReturnToSurface) request.task()).args();
		if (isSurfaceReached(client, player)) {
			return complete(request, "surface_reached");
		}
		if (underwaterRecoveryStarted || shouldEnterUnderwaterRecovery(player)) {
			Optional<TaskTerminalEvent> recoveryEvent = tickUnderwaterRecovery(
				sessionSnapshot,
				request,
				client,
				player,
				args
			);
			if (recoveryEvent.isPresent()) {
				return recoveryEvent;
			}
			if (underwaterRecoveryStarted) {
				return Optional.empty();
			}
		}
		if (args.targetPosition() != null
			&& !exactSurfaceNavigationStarted
			&& reachedTarget(player, args.targetPosition())) {
			return handleSurfaceTargetReached(request, client, player, args, canRefineSurfaceNavigation());
		}
		if (toweringStarted || shouldTowerFirst(args)) {
			return tickTowering(request, client, player, args);
		}
		if (args.targetPosition() == null) {
			return args.useTowering()
				? tickTowering(request, client, player, args)
				: fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "surface_target_unavailable"));
		}
		if (baritoneFacade == null || !baritoneFacade.isLoaded()) {
			return args.useTowering()
				? tickTowering(request, client, player, args)
					: fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "baritone_unavailable"));
		}
		if (!navigationStarted) {
			baritoneFacade.startNavigateNear(args.targetPosition(), NAVIGATION_RADIUS_BLOCKS);
			navigationStarted = true;
		}
		Optional<String> pathEvent = baritoneFacade.pollPathEvent();
		if (pathEvent.isPresent()) {
			String event = pathEvent.get();
			if ("AT_GOAL".equalsIgnoreCase(event)) {
				return handleSurfaceTargetReached(
					request,
					client,
					player,
					args,
					canRefineSurfaceNavigation()
				);
			}
			if ("CALC_FAILED".equalsIgnoreCase(event) || "CANCELLED".equalsIgnoreCase(event) || "CANCELED".equalsIgnoreCase(event)) {
				return args.useTowering()
					? tickTowering(request, client, player, args)
					: fail(request, TaskFailure.of(TaskFailureCode.TRANSIENT, "surface_path_" + event.toLowerCase(java.util.Locale.ROOT)));
			}
		}
		snapshot = snapshot(
			TaskExecutionState.RUNNING,
			request,
			exactSurfaceNavigationStarted ? "navigating_exact_surface_target" : "navigating_to_surface"
		);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickUnderwaterRecovery(
		SessionSnapshot sessionSnapshot,
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		ReturnToSurfaceStepArgs args
	) {
		if (!underwaterRecoveryStarted) {
			cancelNavigationIfStarted();
			toweringStarted = false;
			underwaterRecoveryStarted = true;
			breathableTicks = 0;
			underwaterStuckTicks = 0;
		}
		if (isBreathable(player)) {
			breathableTicks++;
			movementController.stop(client);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "underwater_recovery:breathable");
			if (shouldExitUnderwaterRecovery(true, breathableTicks)) {
				underwaterRecoveryStarted = false;
				breathableTicks = 0;
				underwaterStuckTicks = 0;
				return Optional.empty();
			}
			return Optional.empty();
		}
		breathableTicks = 0;
		long tick = sessionSnapshot == null ? 0L : sessionSnapshot.tickCount();
		double horizontalDistanceSquared = horizontalDistanceSquared(player, args.targetPosition());
		boolean stuck = movementController.snapshot().stuck();
		RecoveryMovement recoveryMovement = recoveryMovement(
			true,
			args.targetPosition() != null,
			horizontalDistanceSquared,
			stuck
		);
		if (stuck) {
			underwaterStuckTicks++;
		}
		else {
			underwaterStuckTicks = 0;
		}
		UnderwaterRecoveryKeys keys = underwaterRecoveryKeys(recoveryMovement, underwaterStuckTicks);
		if (recoveryMovement == RecoveryMovement.TOWARD_TARGET || (recoveryMovement == RecoveryMovement.STUCK && args.targetPosition() != null)) {
			cameraController.lookAt(client, targetSwimPoint(args.targetPosition()));
		}
		movementController.swimUp(
			client,
			keys.forward(),
			keys.sprint(),
			keys.left(),
			keys.right(),
			keys.back(),
			tick
		);
		String event = recoveryMovementEvent(recoveryMovement);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, event);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> handleSurfaceTargetReached(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		ReturnToSurfaceStepArgs args,
		boolean canRefineNavigation
	) {
		return switch (surfaceTargetOutcome(
			isSurfaceReached(client, player),
			args.useTowering(),
			args.targetKind(),
			canRefineNavigation
		)) {
			case COMPLETE -> complete(request, "surface_target_reached");
			case NAVIGATE_EXACT -> startExactSurfaceNavigation(request, args.targetPosition());
			case TOWER -> tickTowering(request, client, player, args);
			case FAIL -> fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "surface_target_not_surface"));
		};
	}

	private boolean canRefineSurfaceNavigation() {
		return !exactSurfaceNavigationStarted
			&& baritoneFacade != null
			&& baritoneFacade.isLoaded();
	}

	private Optional<TaskTerminalEvent> startExactSurfaceNavigation(
		WorldTaskRequest request,
		GoalPosition targetPosition
	) {
		baritoneFacade.startNavigate(new GoalPosition(targetPosition.x(), targetPosition.y(), targetPosition.z(), true));
		navigationStarted = true;
		exactSurfaceNavigationStarted = true;
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "navigating_exact_surface_target");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickTowering(
		WorldTaskRequest request,
		MinecraftClient client,
		ClientPlayerEntity player,
		ReturnToSurfaceStepArgs args
	) {
		if (!toweringStarted) {
			movementController.stop(client);
			cancelNavigationIfStarted();
			toweringStarted = true;
			towerStartY = player.getBlockY();
		}
		if (isSurfaceReached(client, player)) {
			return complete(request, "surface_reached_by_towering");
		}
		if (shouldStopToweringAtSurfaceTargetElevation(args, player.getBlockY())) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "surface_target_elevation_reached_not_surface"));
		}
		if (player.getBlockY() - towerStartY > MAX_TOWER_BLOCKS) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "tower_limit_reached"));
		}
		Optional<HeadroomClearance> headroomClearance = clearTowerHeadroom(client, player);
		if (headroomClearance.isPresent()) {
			HeadroomClearance clearance = headroomClearance.get();
			if (clearance.failed()) {
				return fail(request, clearance.failure());
			}
			snapshot = snapshot(TaskExecutionState.RUNNING, request, clearance.event());
			return Optional.empty();
		}
		Hand hand = selectFillerHand(client, player, args.fillerBlockIds());
		if (hand == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "missing_filler_block fillerBlockIds=" + args.fillerBlockIds()));
		}
		jumpKeyControl.press(client.options.jumpKey);
		PlacementAttempt placement = placeUnderFoot(client, player, hand);
		if (placement.accepted()) {
			towerSupportUnavailableTicks = 0;
			player.swingHand(hand);
		}
		else if ("support_unavailable".equals(placement.reason())) {
			towerSupportUnavailableTicks++;
			if (towerSupportUnavailableTimedOut(towerSupportUnavailableTicks)) {
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "towering:support_unavailable_timeout"));
			}
		}
		else {
			towerSupportUnavailableTicks = 0;
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "towering:" + placement.reason());
		return Optional.empty();
	}

	private Optional<HeadroomClearance> clearTowerHeadroom(MinecraftClient client, ClientPlayerEntity player) {
		if (client == null || client.world == null || client.interactionManager == null || player == null) {
			return Optional.empty();
		}
		BlockPos target = headroomBreakTarget == null ? firstTowerHeadroomObstruction(client, player.getBlockPos()) : headroomBreakTarget;
		if (target == null) {
			clearHeadroomBreakState(client);
			return Optional.empty();
		}
		if (!client.world.isChunkLoaded(target)) {
			clearHeadroomBreakState(client);
			return Optional.of(new HeadroomClearance(
				"towering:headroom_unloaded", true,
				TaskFailure.of(TaskFailureCode.ENVIRONMENT_CHANGED, "towering:headroom_unloaded")
			));
		}
		BlockState state = client.world.getBlockState(target);
		if (!shouldClearTowerHeadroom(!state.isAir(), state.isReplaceable(), !state.getFluidState().isEmpty())) {
			clearHeadroomBreakState(client);
			return Optional.of(new HeadroomClearance("towering:headroom_cleared", false, null));
		}
		MiningToolPreparation.Result tool = MiningToolPreparation.ensureSelectedForClearance(client, player, List.of(state));
		if (!tool.ok()) {
			clearHeadroomBreakState(client);
			return Optional.of(new HeadroomClearance("towering:" + tool.message(), true,
				TaskFailure.of(TaskFailureCode.MISSING_ITEM, tool.message())));
		}
		long tick = client.world.getTime();
		if (headroomBreakTarget == null || !headroomBreakTarget.equals(target)) {
			clearHeadroomBreakState(client);
			boolean accepted = client.interactionManager.attackBlock(target, Direction.DOWN);
			if (!accepted) {
				return Optional.of(new HeadroomClearance(
					"towering:headroom_break_start_failed", true,
					TaskFailure.of(TaskFailureCode.UNKNOWN, "towering:headroom_break_start_failed")
				));
			}
			headroomBreakTarget = target;
			headroomBreakStartTick = tick;
		}
		if (tick - headroomBreakStartTick > HEADROOM_BREAK_TIMEOUT_TICKS) {
			clearHeadroomBreakState(client);
			return Optional.of(new HeadroomClearance(
				"towering:headroom_break_timeout", true,
				TaskFailure.of(TaskFailureCode.TRANSIENT, "towering:headroom_break_timeout")
			));
		}
		jumpKeyControl.release(client.options.jumpKey);
		client.interactionManager.updateBlockBreakingProgress(target, Direction.DOWN);
		player.swingHand(Hand.MAIN_HAND);
		BlockState after = client.world.isChunkLoaded(target) ? client.world.getBlockState(target) : state;
		if (!shouldClearTowerHeadroom(!after.isAir(), after.isReplaceable(), !after.getFluidState().isEmpty())) {
			clearHeadroomBreakState(client);
			return Optional.of(new HeadroomClearance("towering:headroom_cleared", false, null));
		}
		return Optional.of(new HeadroomClearance("towering:clearing_headroom", false, null));
	}

	static boolean towerSupportUnavailableTimedOut(int unavailableTicks) {
		return unavailableTicks >= TOWER_SUPPORT_UNAVAILABLE_TIMEOUT_TICKS;
	}

	private void clearHeadroomBreakState(MinecraftClient client) {
		if (headroomBreakTarget != null && client != null && client.interactionManager != null) {
			client.interactionManager.cancelBlockBreaking();
		}
		headroomBreakTarget = null;
		headroomBreakStartTick = -1L;
	}

	private static BlockPos firstTowerHeadroomObstruction(MinecraftClient client, BlockPos feetPos) {
		if (client == null || client.world == null || feetPos == null) {
			return null;
		}
		for (int offset = 1; offset <= 2; offset++) {
			BlockPos candidate = feetPos.up(offset);
			if (!client.world.isChunkLoaded(candidate)) {
				return candidate;
			}
			BlockState state = client.world.getBlockState(candidate);
			if (shouldClearTowerHeadroom(!state.isAir(), state.isReplaceable(), !state.getFluidState().isEmpty())) {
				return candidate;
			}
		}
		return null;
	}

	private boolean shouldTowerFirst(ReturnToSurfaceStepArgs args) {
		return args != null && args.useTowering() && args.targetPosition() == null;
	}

	private static boolean shouldEnterUnderwaterRecovery(ClientPlayerEntity player) {
		return player != null
			&& shouldEnterUnderwaterRecovery(player.isTouchingWater(), player.isSubmergedInWater(), isBreathable(player));
	}

	static boolean shouldEnterUnderwaterRecovery(boolean touchingWater, boolean submergedInWater, boolean breathable) {
		return !breathable && (touchingWater || submergedInWater);
	}

	private static boolean isBreathable(ClientPlayerEntity player) {
		return player != null && !player.isSubmergedInWater() && player.getAir() >= player.getMaxAir();
	}

	public static RecoveryMovement recoveryMovement(boolean underwater, boolean targetAvailable, double horizontalDistanceSquared, boolean stuck) {
		if (!underwater) {
			return RecoveryMovement.BREATHABLE;
		}
		if (stuck) {
			return RecoveryMovement.STUCK;
		}
		return targetAvailable && horizontalDistanceSquared > TARGET_FORWARD_HORIZONTAL_DISTANCE_SQUARED
			? RecoveryMovement.TOWARD_TARGET
			: RecoveryMovement.ASCENDING;
	}

	static boolean shouldExitUnderwaterRecovery(boolean breathable, int breathableTicks) {
		return breathable && breathableTicks >= BREATHABLE_STABLE_TICKS;
	}

	static boolean shouldClearTowerHeadroom(boolean occupied, boolean replaceable, boolean hasFluid) {
		return occupied && !replaceable && !hasFluid;
	}

	public static UnderwaterRecoveryKeys underwaterRecoveryKeys(RecoveryMovement recoveryMovement, int stuckTicks) {
		if (recoveryMovement == RecoveryMovement.TOWARD_TARGET) {
			return new UnderwaterRecoveryKeys(true, true, false, false, false);
		}
		if (recoveryMovement != RecoveryMovement.STUCK) {
			return new UnderwaterRecoveryKeys(false, false, false, false, false);
		}
		int phase = Math.floorDiv(Math.max(0, stuckTicks), UNDERWATER_ESCAPE_PHASE_TICKS) % 4;
		return switch (phase) {
			case 0 -> new UnderwaterRecoveryKeys(true, true, true, false, false);
			case 1 -> new UnderwaterRecoveryKeys(true, true, false, true, false);
			case 2 -> new UnderwaterRecoveryKeys(false, false, true, false, true);
			default -> new UnderwaterRecoveryKeys(false, false, false, true, true);
		};
	}

	private static double horizontalDistanceSquared(ClientPlayerEntity player, GoalPosition target) {
		if (player == null || target == null) {
			return 0.0D;
		}
		double dx = target.x() + 0.5D - player.getX();
		double dz = target.z() + 0.5D - player.getZ();
		return dx * dx + dz * dz;
	}

	private static Vec3d targetSwimPoint(GoalPosition target) {
		return new Vec3d(target.x() + 0.5D, target.y() + 1.0D, target.z() + 0.5D);
	}

	private static String recoveryMovementEvent(RecoveryMovement recoveryMovement) {
		return switch (recoveryMovement) {
			case ASCENDING -> "underwater_recovery:ascending";
			case TOWARD_TARGET -> "underwater_recovery:toward_target";
			case BREATHABLE -> "underwater_recovery:breathable";
			case STUCK -> "underwater_recovery:stuck";
		};
	}

	private static PlacementAttempt placeUnderFoot(MinecraftClient client, ClientPlayerEntity player, Hand hand) {
		Optional<BlockPos> support = findTowerSupport(client, player, hand);
		if (support.isEmpty()) {
			return new PlacementAttempt(false, "support_unavailable");
		}
		BlockPos supportPos = support.get();
		BlockHitResult hitResult = new BlockHitResult(
			new Vec3d(supportPos.getX() + 0.5D, supportPos.getY() + 1.0D, supportPos.getZ() + 0.5D),
			Direction.UP,
			supportPos,
			false
		);
		ActionResult result = client.interactionManager.interactBlock(player, hand, hitResult);
		return new PlacementAttempt(result.isAccepted(), result.isAccepted() ? "placed" : "interact_" + result);
	}

	private static Optional<BlockPos> findTowerSupport(MinecraftClient client, ClientPlayerEntity player, Hand hand) {
		if (player == null) {
			return Optional.empty();
		}
		BlockPos feet = player.getBlockPos();
		for (int offset = 1; offset <= TOWER_SUPPORT_SEARCH_DEPTH; offset++) {
			BlockPos support = feet.down(offset);
			if (canPlaceAgainst(client, player, hand, support)) {
				return Optional.of(support);
			}
		}
		return Optional.empty();
	}

	private static boolean canPlaceAgainst(MinecraftClient client, ClientPlayerEntity player, Hand hand, BlockPos support) {
		if (client == null || client.world == null || player == null || support == null) {
			return false;
		}
		BlockPos target = support.up();
		if (!client.world.isChunkLoaded(support) || !client.world.isChunkLoaded(target)) {
			return false;
		}
		BlockState supportState = client.world.getBlockState(support);
		BlockState targetState = client.world.getBlockState(target);
		ItemStack selected = hand == Hand.OFF_HAND ? player.getOffHandStack() : player.getMainHandStack();
		if (!(selected.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		return supportState.isSideSolidFullSquare(client.world, support, Direction.UP)
			&& (targetState.isAir() || targetState.isReplaceable())
			&& client.world.canPlace(blockItem.getBlock().getDefaultState(), target, ShapeContext.of(player));
	}

	private static Hand selectFillerHand(MinecraftClient client, ClientPlayerEntity player, List<String> fillerBlockIds) {
		if (matchesFiller(player.getOffHandStack(), fillerBlockIds)) {
			return Hand.OFF_HAND;
		}
		return selectHotbarFiller(client, player, fillerBlockIds) ? Hand.MAIN_HAND : null;
	}

	private static boolean selectHotbarFiller(MinecraftClient client, ClientPlayerEntity player, List<String> fillerBlockIds) {
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return false;
		}
		ScreenHandler handler = player.currentScreenHandler;
		int sourceSlot = findFillerSlot(handler, fillerBlockIds);
		if (sourceSlot < 0) {
			return false;
		}
		int selectedHotbarSlot = player.getInventory().getSelectedSlot();
		if (sourceSlot >= PlayerScreenHandler.HOTBAR_START && sourceSlot < PlayerScreenHandler.HOTBAR_END) {
			selectAndSyncHotbarSlot(client, player, sourceSlot - PlayerScreenHandler.HOTBAR_START);
			return matchesFiller(player.getInventory().getSelectedStack(), fillerBlockIds);
		}
		client.interactionManager.clickSlot(handler.syncId, sourceSlot, selectedHotbarSlot, SlotActionType.SWAP, player);
		selectAndSyncHotbarSlot(client, player, selectedHotbarSlot);
		return matchesFiller(player.getInventory().getSelectedStack(), fillerBlockIds);
	}

	private static int findFillerSlot(ScreenHandler handler, List<String> fillerBlockIds) {
		if (!(handler instanceof PlayerScreenHandler)) {
			return -1;
		}
		for (int slot = PlayerScreenHandler.HOTBAR_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			if (matchesFiller(handler.getSlot(slot).getStack(), fillerBlockIds)) {
				return slot;
			}
		}
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_START; slot++) {
			if (matchesFiller(handler.getSlot(slot).getStack(), fillerBlockIds)) {
				return slot;
			}
		}
		return -1;
	}

	private static boolean matchesFiller(ItemStack stack, List<String> fillerBlockIds) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem) || fillerBlockIds == null) {
			return false;
		}
		String itemId = Registries.ITEM.getId(stack.getItem()).toString();
		return fillerBlockIds.stream().anyMatch(itemId::equals);
	}

	private static void selectAndSyncHotbarSlot(MinecraftClient client, ClientPlayerEntity player, int hotbarSlot) {
		player.getInventory().setSelectedSlot(hotbarSlot);
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarSlot));
		}
	}

	private static boolean reachedTarget(ClientPlayerEntity player, GoalPosition position) {
		return player != null && reachedTarget(player.getBlockPos(), position);
	}

	static boolean reachedTarget(BlockPos playerPos, GoalPosition position) {
		if (playerPos == null || position == null) {
			return false;
		}
		return Math.abs(playerPos.getX() - position.x()) <= NAVIGATION_RADIUS_BLOCKS
			&& Math.abs(playerPos.getZ() - position.z()) <= NAVIGATION_RADIUS_BLOCKS
			&& Math.abs(playerPos.getY() - position.y()) <= 2;
	}

	private static boolean isSurfaceReached(MinecraftClient client, ClientPlayerEntity player) {
		return player != null
			&& player.isOnGround()
			&& SurfaceMemory.isSurfaceStandingPosition(client, player.getBlockPos());
	}

	static SurfaceTargetOutcome surfaceTargetOutcome(
		boolean surfaceReached,
		boolean useTowering,
		String targetKind,
		boolean canRefineNavigation
	) {
		if (surfaceReached) {
			return SurfaceTargetOutcome.COMPLETE;
		}
		if (isRememberedSurfaceTarget(targetKind)) {
			return canRefineNavigation ? SurfaceTargetOutcome.NAVIGATE_EXACT : SurfaceTargetOutcome.FAIL;
		}
		return useTowering ? SurfaceTargetOutcome.TOWER : SurfaceTargetOutcome.FAIL;
	}

	static boolean shouldStopToweringAtSurfaceTargetElevation(ReturnToSurfaceStepArgs args, int playerBlockY) {
		return args != null
			&& args.targetPosition() != null
			&& isRememberedSurfaceTarget(args.targetKind())
			&& playerBlockY >= args.targetPosition().y();
	}

	static boolean isRememberedSurfaceTarget(String targetKind) {
		return "nearest_surface".equals(targetKind) || "last_surface".equals(targetKind);
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		cancelNavigationIfStarted();
		releaseMovementControls();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, TaskFailure failure) {
		cancelNavigationIfStarted();
		releaseMovementControls();
		snapshot = snapshot(TaskExecutionState.FAILED, request, failure.detail());
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, failure.detail(), null, failure.code()));
	}

	private void cancelNavigationIfStarted() {
		if (navigationStarted && baritoneFacade != null && baritoneFacade.isLoaded()) {
			baritoneFacade.cancel();
		}
		navigationStarted = false;
		exactSurfaceNavigationStarted = false;
	}

	private void releaseMovementControls() {
		MinecraftClient client = clientSupplier.get();
		if (client != null) {
			movementController.stop(client);
		}
		jumpKeyControl.release(client == null ? null : client.options.jumpKey);
		clearHeadroomBreakState(client);
	}

	private void reset() {
		cancelNavigationIfStarted();
		releaseMovementControls();
		appliedTask = null;
		terminalEventEmitted = false;
		toweringStarted = false;
		towerStartY = 0;
		underwaterRecoveryStarted = false;
		breathableTicks = 0;
		underwaterStuckTicks = 0;
		towerSupportUnavailableTicks = 0;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null || left.type() != WorldTaskType.RETURN_TO_SURFACE || right.type() != WorldTaskType.RETURN_TO_SURFACE) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.task(), right.task());
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "ReturnToSurface", event, null, null);
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

	private record PlacementAttempt(boolean accepted, String reason) {
	}

	private record HeadroomClearance(String event, boolean failed, TaskFailure failure) {
	}

	public record UnderwaterRecoveryKeys(boolean forward, boolean sprint, boolean left, boolean right, boolean back) {
	}

	enum SurfaceTargetOutcome {
		COMPLETE,
		NAVIGATE_EXACT,
		TOWER,
		FAIL
	}

	public enum RecoveryMovement {
		ASCENDING,
		TOWARD_TARGET,
		BREATHABLE,
		STUCK
	}
}
