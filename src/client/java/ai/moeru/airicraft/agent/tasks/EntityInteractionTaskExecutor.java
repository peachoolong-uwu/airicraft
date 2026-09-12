package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class EntityInteractionTaskExecutor implements WorldTaskExecutor {
	private static final int TARGET_OUT_OF_RANGE_GRACE_TICKS = 20;
	private static final int BUSY_STATE_TIMEOUT_TICKS = 100;
	private static final int CHASE_GOAL_REFRESH_TICKS = 10;
	private static final int BARITONE_CHASE_RADIUS_BLOCKS = 3;
	private static final double CHASE_GOAL_REFRESH_DISTANCE_BLOCKS = 2.0D;
	private static final double DIRECT_CHASE_DISTANCE_BLOCKS = 10.0D;
	private static final float ATTACK_READY_THRESHOLD = 0.92F;

	private final Supplier<MinecraftClient> clientSupplier;
	private final BaritoneFacade navigationFacade;
	private final CameraController cameraController;
	private final MovementController movementController = new MovementController();

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private boolean landedAttack;
	private int outOfRangeTicks;
	private int busyStateTicks;
	private int attackHotbarSlot = -1;
	private GoalPosition chaseGoal;
	private int chaseGoalRefreshTicks;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public EntityInteractionTaskExecutor() {
		this(MinecraftClient::getInstance, null, new CameraController());
	}

	public EntityInteractionTaskExecutor(BaritoneFacade navigationFacade) {
		this(MinecraftClient::getInstance, navigationFacade, new CameraController());
	}

	public EntityInteractionTaskExecutor(BaritoneFacade navigationFacade, CameraController cameraController) {
		this(MinecraftClient::getInstance, navigationFacade, cameraController);
	}

	EntityInteractionTaskExecutor(Supplier<MinecraftClient> clientSupplier) {
		this(clientSupplier, null, new CameraController());
	}

	EntityInteractionTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade navigationFacade) {
		this(clientSupplier, navigationFacade, new CameraController());
	}

	EntityInteractionTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade navigationFacade, CameraController cameraController) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.navigationFacade = navigationFacade;
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || !isEntityInteractionTask(activeTask.get().type())) {
			cancelApproach();
			reset();
			return Optional.empty();
		}

		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			cancelApproach();
			reset();
			appliedTask = request;
		}

		if (!entityInteractionActuationAllowed(sessionSnapshot)) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.interactionManager == null || client.world == null || player == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "world_unavailable"));
		}
		if (dismissCurrentScreenIfSafe(client, player)) {
			busyStateTicks = 0;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "screen_dismissed");
			return Optional.empty();
		}
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			if (shouldWaitForBusyState(busyStateTicks)) {
				busyStateTicks++;
				snapshot = snapshot(TaskExecutionState.RUNNING, request, "interaction_busy");
				return Optional.empty();
			}
			return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "interaction_busy"));
		}
		busyStateTicks = 0;
		if (request.type() == WorldTaskType.ATTACK_ENTITY && attackHotbarSlot < 0) {
			attackHotbarSlot = player.getInventory().getSelectedSlot();
		}

		Selection selection = resolveSelection(client, player, interaction(request).selector());
		if (selection.status() != EntitySelectorResolver.SelectionStatus.SELECTED) {
			if (completedAfterLandedAttack(request, selection.status())) {
				return complete(request, "target_died");
			}
			return fail(request, selection.failure());
		}
		Entity target = selection.entity();
		if (target == null) {
			return fail(request, selectionFailure(EntitySelectorResolver.SelectionStatus.TARGET_NOT_FOUND));
		}
		if (!target.isAlive()) {
			if (completedAfterLandedAttack(request, EntitySelectorResolver.SelectionStatus.TARGET_NOT_ALIVE)) {
				return complete(request, "target_died");
			}
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_not_alive"));
		}
		lookAtTarget(client, target);
		double distance = player.distanceTo(target);
		boolean hasLineOfSight = hasBlockLineOfSight(client, player, target);
		boolean withinInteractionRange = EntitySelectorResolver.isWithinInteractionRange(
			player.getX(),
			player.getY(),
			player.getZ(),
			target.getX(),
			target.getY(),
			target.getZ()
		);
		if (withinInteractionRange && hasLineOfSight) {
			outOfRangeTicks = 0;
		}

		return switch (request.type()) {
			case ATTACK_ENTITY -> {
				if (withinInteractionRange && hasLineOfSight) {
					yield attackEntity(client, player, request, target);
				}
				yield approachTarget(client, request, target, distance, hasLineOfSight, sessionSnapshot.tickCount());
			}
			case USE_ENTITY -> {
				if (withinInteractionRange && hasLineOfSight) {
					yield useEntity(client, player, request, target);
				}
				yield approachTarget(client, request, target, distance, hasLineOfSight, sessionSnapshot.tickCount());
			}
			default -> Optional.empty();
		};
	}

	static boolean entityInteractionActuationAllowed(SessionSnapshot sessionSnapshot) {
		return sessionSnapshot != null && sessionSnapshot.companionActuationAllowed();
	}

	static boolean shouldWaitForBusyState(int busyStateTicks) {
		return busyStateTicks < BUSY_STATE_TIMEOUT_TICKS;
	}

	static boolean shouldRefreshChaseGoal(GoalPosition currentChaseGoal, GoalPosition nextChaseGoal, int ticksSinceRefresh) {
		return currentChaseGoal == null
			|| squaredBlockDistance(currentChaseGoal, nextChaseGoal) >= CHASE_GOAL_REFRESH_DISTANCE_BLOCKS * CHASE_GOAL_REFRESH_DISTANCE_BLOCKS
			|| ticksSinceRefresh >= CHASE_GOAL_REFRESH_TICKS;
	}

	static boolean shouldUseDirectChase(double distance, boolean hasLineOfSight, boolean directMovementStuck) {
		return distance <= DIRECT_CHASE_DISTANCE_BLOCKS && hasLineOfSight && !directMovementStuck;
	}

	private Optional<TaskTerminalEvent> attackEntity(
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		Entity target
	) {
		movementController.stop(client);
		cancelBaritoneChase();
		// Chase navigation may select tools or building blocks. Restore the hand
		// chosen for this attack before evaluating its cooldown or sending a hit.
		player.getInventory().setSelectedSlot(attackHotbarSlot);
		if (player.getAttackCooldownProgress(0.0F) < ATTACK_READY_THRESHOLD) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "attack_cooldown");
			return Optional.empty();
		}

		client.interactionManager.attackEntity(player, target);
		player.swingHand(Hand.MAIN_HAND);
		landedAttack = true;
		if (interaction(request).attackMode() == EntityAttackMode.HIT_ONCE) {
			return complete(request, "attack_landed");
		}
		if (!target.isAlive()) {
			return complete(request, "target_died");
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "attack_landed");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> useEntity(
		MinecraftClient client,
		ClientPlayerEntity player,
		WorldTaskRequest request,
		Entity target
	) {
		movementController.stop(client);
		cancelBaritoneChase();
		Hand hand = resolveInteractionHand(client, player, interaction(request).itemId());
		if (hand == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "required_item_missing"));
		}
		ActionResult result = client.interactionManager.interactEntity(player, target, hand);
		if (!result.isAccepted()) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "interaction_failed"));
		}
		player.swingHand(hand);
		return complete(request, "interaction_succeeded");
	}

	private Optional<TaskTerminalEvent> approachTarget(
		MinecraftClient client,
		WorldTaskRequest request,
		Entity target,
		double distance,
		boolean hasLineOfSight,
		long tick
	) {
		outOfRangeTicks++;
		if (shouldUseDirectChase(distance, hasLineOfSight, movementController.snapshot().stuck())) {
			cancelBaritoneChase();
			lookAtTarget(client, target);
			movementController.moveForward(client, true, false, tick);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "direct_chase");
			return Optional.empty();
		}
		if (navigationFacade != null && navigationFacade.isLoaded()) {
			movementController.stop(client);
			Optional<String> pathEvent = navigationFacade.pollPathEvent();
			if (!landedAttack && isUnreachablePathEvent(pathEvent)) {
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_unreachable"));
			}
			GoalPosition nextChaseGoal = chaseGoalFor(target);
			if (shouldRefreshChaseGoal(chaseGoal, nextChaseGoal, chaseGoalRefreshTicks)) {
				if (hasLineOfSight) {
					navigationFacade.startNavigateNear(nextChaseGoal, BARITONE_CHASE_RADIUS_BLOCKS);
				}
				else {
					// A nearby goal can already be satisfied on the wrong side of an obstruction.
					// The small feet offset also accounts for shortened support blocks such as farmland.
					navigationFacade.startNavigate(new GoalPosition(nextChaseGoal.x(), (int) Math.floor(target.getY() + 0.125D), nextChaseGoal.z(), true));
				}
				chaseGoal = nextChaseGoal;
				chaseGoalRefreshTicks = 0;
			}
			else {
				chaseGoalRefreshTicks++;
			}
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "baritone_chase");
			return Optional.empty();
		}
		movementController.stop(client);
		if (outOfRangeTicks <= TARGET_OUT_OF_RANGE_GRACE_TICKS) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_out_of_range");
			return Optional.empty();
		}
		return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_out_of_range"));
	}

	private static Hand resolveInteractionHand(MinecraftClient client, ClientPlayerEntity player, String itemId) {
		if (itemId == null || itemId.isBlank()) {
			return Hand.MAIN_HAND;
		}
		String offHandItemId = Registries.ITEM.getId(player.getOffHandStack().getItem()).toString();
		if (itemId.equals(offHandItemId) && !player.getOffHandStack().isEmpty()) {
			return Hand.OFF_HAND;
		}

		ScreenHandler handler = player.currentScreenHandler;
		int sourceSlot = findInventorySlot(handler, itemId);
		if (sourceSlot < 0) {
			return null;
		}
		int hotbarIndex = player.getInventory().getSelectedSlot();
		if (sourceSlot >= PlayerScreenHandler.HOTBAR_START && sourceSlot < PlayerScreenHandler.HOTBAR_END) {
			player.getInventory().setSelectedSlot(sourceSlot - PlayerScreenHandler.HOTBAR_START);
			return Hand.MAIN_HAND;
		}
		client.interactionManager.clickSlot(handler.syncId, sourceSlot, hotbarIndex, SlotActionType.SWAP, player);
		player.getInventory().setSelectedSlot(hotbarIndex);
		ItemStack selected = player.getInventory().getSelectedStack();
		if (selected.isEmpty()) {
			return null;
		}
		String selectedItemId = Registries.ITEM.getId(selected.getItem()).toString();
		return itemId.equals(selectedItemId) ? Hand.MAIN_HAND : null;
	}

	private static int findInventorySlot(ScreenHandler handler, String itemId) {
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (stack.isEmpty()) {
				continue;
			}
			if (itemId.equals(Registries.ITEM.getId(stack.getItem()).toString())) {
				return slot;
			}
		}
		return -1;
	}

	private static Selection resolveSelection(MinecraftClient client, ClientPlayerEntity player, EntitySelector selector) {
		Map<Integer, Entity> entitiesById = new LinkedHashMap<>();
		java.util.ArrayList<EntitySelectorResolver.EntityCandidate> candidates = new java.util.ArrayList<>();
		double radius = EntitySelectorResolver.DEFAULT_NEARBY_RADIUS_BLOCKS;
		for (Entity entity : client.world.getOtherEntities(player, player.getBoundingBox().expand(radius))) {
			entitiesById.put(entity.getId(), entity);
			candidates.add(NearbyEntityService.toCandidate(entity));
		}
		EntitySelectorResolver.SelectionResult result = EntitySelectorResolver.select(
			selector,
			candidates,
			player.getX(),
			player.getY(),
			player.getZ()
		);
		if (result.status() != EntitySelectorResolver.SelectionStatus.SELECTED || result.selected() == null) {
			return new Selection(null, result.status(), selectionFailure(result.status()));
		}
		Entity selectedEntity = entitiesById.get(result.selected().entityId());
		if (selectedEntity == null) {
			EntitySelectorResolver.SelectionStatus status = EntitySelectorResolver.SelectionStatus.TARGET_NOT_FOUND;
			return new Selection(null, status, selectionFailure(status));
		}
		return new Selection(selectedEntity, result.status(), null);
	}

	private static TaskFailure selectionFailure(EntitySelectorResolver.SelectionStatus status) {
		return switch (status) {
			case TARGET_NOT_FOUND -> TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_not_found");
			case TARGET_NOT_NEARBY -> TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_not_nearby");
			case TARGET_NOT_ALIVE -> TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_not_alive");
			case TARGET_AMBIGUOUS -> TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_ambiguous");
			case SELECTED -> null;
		};
	}

	private static GoalPosition chaseGoalFor(Entity target) {
		var blockPos = target.getBlockPos();
		return new GoalPosition(blockPos.getX(), blockPos.getY(), blockPos.getZ(), false);
	}

	private void lookAtTarget(MinecraftClient client, Entity target) {
		cameraController.lookAtNow(client, targetAimPoint(target));
	}

	private static boolean hasBlockLineOfSight(MinecraftClient client, ClientPlayerEntity player, Entity target) {
		if (client == null || client.world == null || player == null || target == null) {
			return false;
		}
		Vec3d start = player.getEyePos();
		Vec3d end = targetAimPoint(target);
		HitResult hit = client.world.raycast(new RaycastContext(
			start,
			end,
			RaycastContext.ShapeType.COLLIDER,
			RaycastContext.FluidHandling.NONE,
			player
		));
		if (hit == null || hit.getType() == HitResult.Type.MISS) {
			return true;
		}
		return hit.getPos().squaredDistanceTo(start) + 0.25D >= end.squaredDistanceTo(start);
	}

	private static Vec3d targetAimPoint(Entity target) {
		return target.getBoundingBox().getCenter();
	}

	private static double squaredBlockDistance(GoalPosition left, GoalPosition right) {
		if (left == null || right == null) {
			return Double.POSITIVE_INFINITY;
		}
		double dx = left.x() - right.x();
		double dy = left.y() - right.y();
		double dz = left.z() - right.z();
		return (dx * dx) + (dy * dy) + (dz * dz);
	}

	private static boolean isUnreachablePathEvent(Optional<String> pathEvent) {
		return pathEvent
			.map(event -> "CALC_FAILED".equals(event.trim().toUpperCase(java.util.Locale.ROOT)))
			.orElse(false);
	}

	private static boolean dismissCurrentScreenIfSafe(MinecraftClient client, ClientPlayerEntity player) {
		if (!DropItemsTaskExecutor.shouldDismissBusyScreen(currentScreenName(client))) {
			return false;
		}
		if (player.currentScreenHandler != player.playerScreenHandler) {
			return false;
		}
		if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
			return false;
		}
		ScreenCloseSafety.clearScreen(client, "entity_interaction_screen_dismiss");
		return true;
	}

	private static String currentScreenName(MinecraftClient client) {
		return client.currentScreen == null ? null : client.currentScreen.getClass().getSimpleName();
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		cancelApproach();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, null));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, TaskFailure failure) {
		cancelApproach();
		snapshot = snapshot(TaskExecutionState.FAILED, request, failure.detail());
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, failure.detail(), null, failure.code()));
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "EntityInteraction", event, null, null);
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.task(), right.task());
	}

	private static boolean isEntityInteractionTask(WorldTaskType type) {
		return type == WorldTaskType.ATTACK_ENTITY || type == WorldTaskType.USE_ENTITY;
	}

	private boolean completedAfterLandedAttack(WorldTaskRequest request, EntitySelectorResolver.SelectionStatus status) {
		return request != null
			&& completedAfterLandedAttack(request.type(), interaction(request).attackMode(), landedAttack, status);
	}

	private static EntityInteractionStepArgs interaction(WorldTaskRequest request) {
		return switch (request.task()) {
			case WorldTaskRequest.AttackEntity task -> task.args();
			case WorldTaskRequest.UseEntity task -> task.args();
			default -> throw new IllegalArgumentException("entity interaction task required");
		};
	}

	static boolean completedAfterLandedAttack(
		WorldTaskType type,
		EntityAttackMode attackMode,
		boolean landedAttack,
		EntitySelectorResolver.SelectionStatus status
	) {
		return type == WorldTaskType.ATTACK_ENTITY
			&& attackMode == EntityAttackMode.KILL
			&& landedAttack
			&& (status == EntitySelectorResolver.SelectionStatus.TARGET_NOT_FOUND
				|| status == EntitySelectorResolver.SelectionStatus.TARGET_NOT_ALIVE);
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		cancelApproach();
		reset();
	}

	@Override
	public void shutdown() {
		cancelApproach();
		reset();
	}

	private void reset() {
		attackHotbarSlot = -1;
		appliedTask = null;
		terminalEventEmitted = false;
		landedAttack = false;
		outOfRangeTicks = 0;
		busyStateTicks = 0;
		chaseGoal = null;
		chaseGoalRefreshTicks = 0;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private void cancelApproach() {
		movementController.stop(clientSupplier.get());
		cancelBaritoneChase();
	}

	private void cancelBaritoneChase() {
		if (chaseGoal != null && navigationFacade != null && navigationFacade.isLoaded()) {
			navigationFacade.cancel();
		}
		chaseGoal = null;
		chaseGoalRefreshTicks = 0;
	}

	private record Selection(Entity entity, EntitySelectorResolver.SelectionStatus status, TaskFailure failure) {
	}
}
